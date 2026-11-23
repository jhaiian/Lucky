use ash::vk;
use lucky_player::{VideoFrame, ViewTransform};

use super::motion::{Readback, create_readback, destroy_readback};
use super::smooth::{Draw, Rt, black, record_draw};
use super::{View, VulkanPresenter, color_range};
use crate::color::Output;

fn half_to_f32(bits: u16) -> f32 {
    let negative = bits & 0x8000 != 0;
    let exponent = ((bits >> 10) & 0x1f) as i32;
    let mantissa = (bits & 0x3ff) as f32;
    let value = match exponent {
        0 => mantissa * 2f32.powi(-24),
        31 if mantissa == 0.0 => f32::INFINITY,
        31 => f32::NAN,
        _ => (1.0 + mantissa / 1024.0) * 2f32.powi(exponent - 15),
    };
    if negative { -value } else { value }
}

fn unpack_packed(words: &[u32]) -> Vec<u8> {
    let to8 = |value: u32| ((value * 255 + 511) / 1023) as u8;
    let mut rgba = Vec::with_capacity(words.len() * 4);
    for word in words {
        rgba.extend_from_slice(&[to8(word & 0x3ff), to8((word >> 10) & 0x3ff), to8((word >> 20) & 0x3ff), 255]);
    }
    rgba
}

fn unpack_half(halves: &[u16]) -> Vec<u8> {
    let to8 = |bits: u16| (half_to_f32(bits).clamp(0.0, 1.0) * 255.0 + 0.5) as u8;
    let mut rgba = Vec::with_capacity(halves.len());
    for texel in halves.chunks_exact(4) {
        rgba.extend_from_slice(&[to8(texel[0]), to8(texel[1]), to8(texel[2]), 255]);
    }
    rgba
}

impl VulkanPresenter {
    pub fn capture(&mut self, video: &VideoFrame, width: i32, height: i32) -> Option<Vec<u8>> {
        if self.failed || self.core.is_none() || width <= 0 || height <= 0 {
            return None;
        }
        let existed = self.smooth.is_some();
        let scaling = self.active_scaling;
        let result = unsafe { self.run_capture(video, width, height) };
        self.active_scaling = scaling;
        if !existed {
            self.release_smooth();
        }
        match result {
            Ok(pixels) => Some(pixels),
            Err(error) => {
                log::warn!("the screenshot failed: {error}");
                None
            }
        }
    }

    unsafe fn run_capture(&mut self, video: &VideoFrame, width: i32, height: i32) -> Result<Vec<u8>, String> {
        unsafe {
            self.ensure_smooth()?;
            self.wait_gpu()?;
            let (format, render_pass) = {
                let smooth = self.smooth.as_ref().ok_or("no smooth state")?;
                (smooth.format, smooth.render_pass)
            };
            let packed = format == vk::Format::A2B10G10R10_UNORM_PACK32;
            let size = width as u64 * height as u64 * if packed { 4 } else { 8 };
            let core = self.core.as_ref().ok_or("no device")?;
            let image = Rt::new(core, render_pass, format, width, height, vk::ImageUsageFlags::TRANSFER_SRC)?;
            let readback = match create_readback(core, size) {
                Ok(readback) => readback,
                Err(error) => {
                    image.destroy(core);
                    return Err(error);
                }
            };
            let outcome = self.draw_capture(video, width, height, &image, &readback);
            let core = self.core.as_ref().ok_or("no device")?;
            let _ = core.device.device_wait_idle();
            let count = width as usize * height as usize;
            let pixels = outcome.map(|()| {
                if packed {
                    unpack_packed(std::slice::from_raw_parts(readback.mapped as *const u32, count))
                } else {
                    unpack_half(std::slice::from_raw_parts(readback.mapped as *const u16, count * 4))
                }
            });
            destroy_readback(core, &readback);
            image.destroy(core);
            pixels
        }
    }

    unsafe fn draw_capture(&mut self, video: &VideoFrame, width: i32, height: i32, image: &Rt, readback: &Readback) -> Result<(), String> {
        unsafe {
            self.begin_recording()?;
            let view = View { width, height, output: Output::Srgb as i32, transform: ViewTransform::default() };
            let prepared = self.prepare_source(video, 0, true, Some(&view))?;
            let core = self.core.as_ref().ok_or("no device")?;
            let smooth = self.smooth.as_ref().ok_or("no smooth state")?;
            let command = core.command_buffer;
            record_draw(
                core,
                command,
                &Draw {
                    render_pass: smooth.render_pass,
                    framebuffer: image.framebuffer,
                    extent: vk::Extent2D { width: width as u32, height: height as u32 },
                    clear: black(),
                    pipeline: prepared.pipeline,
                    layout: prepared.layout,
                    set: prepared.set,
                    stages: vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                    push: prepared.params.bytes(),
                    vertices: 4,
                },
            );
            let to_transfer = vk::ImageMemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)
                .dst_access_mask(vk::AccessFlags::TRANSFER_READ)
                .old_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
                .new_layout(vk::ImageLayout::TRANSFER_SRC_OPTIMAL)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .image(image.image)
                .subresource_range(color_range());
            core.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT | vk::PipelineStageFlags::FRAGMENT_SHADER,
                vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(),
                &[],
                &[],
                &[to_transfer],
            );
            let region = vk::BufferImageCopy::default()
                .buffer_offset(0)
                .buffer_row_length(0)
                .buffer_image_height(0)
                .image_subresource(
                    vk::ImageSubresourceLayers::default().aspect_mask(vk::ImageAspectFlags::COLOR).mip_level(0).base_array_layer(0).layer_count(1),
                )
                .image_extent(vk::Extent3D { width: width as u32, height: height as u32, depth: 1 });
            core.device.cmd_copy_image_to_buffer(command, image.image, vk::ImageLayout::TRANSFER_SRC_OPTIMAL, readback.buffer, &[region]);
            let to_host = vk::BufferMemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::HOST_READ)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .buffer(readback.buffer)
                .offset(0)
                .size(vk::WHOLE_SIZE);
            core.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),
                &[],
                &[to_host],
                &[],
            );
            self.submit_commands()?;
            self.wait_gpu()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn half_floats_convert_to_eight_bits() {
        assert_eq!(unpack_half(&[0x0000, 0x3800, 0x3c00, 0x3c00]), vec![0, 128, 255, 255]);
        assert_eq!(unpack_half(&[0x4000, 0xbc00, 0x7e00, 0x0000]), vec![255, 0, 0, 255]);
    }

    #[test]
    fn ten_bit_words_convert_to_eight_bits() {
        let white = 0x3ff | (0x3ff << 10) | (0x3ff << 20);
        let mid = 512 | (512 << 10) | (512 << 20);
        assert_eq!(unpack_packed(&[0, white, mid]), vec![0, 0, 0, 255, 255, 255, 255, 255, 128, 128, 128, 255]);
    }
}
