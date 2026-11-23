use std::collections::HashMap;

use ash::vk;

use super::motion::{Readback, create_readback, destroy_readback};
use super::smooth::{Draw, Rt, black, offscreen_pass, record_draw};
use super::{Core, ExternalKey, Params, color_range};
use crate::color::{MEASURE_OUTPUT, ToneAnalyzer};

const WIDTH: i32 = 128;
const HEIGHT: i32 = 72;
const BYTES: usize = (WIDTH * HEIGHT * 4) as usize;
const FORMAT: vk::Format = vk::Format::R8G8B8A8_UNORM;

pub(super) struct Meter {
    render_pass: vk::RenderPass,
    target: Rt,
    readback: Readback,
    pipelines: HashMap<Option<ExternalKey>, vk::Pipeline>,
    pending: Option<(u64, u64)>,
    analyzer: ToneAnalyzer,
}

impl Meter {
    pub(super) unsafe fn new(core: &Core) -> Result<Self, String> {
        unsafe {
            let render_pass = offscreen_pass(core, FORMAT).map_err(|e| format!("meter render pass: {e}"))?;
            let target = match Rt::new(core, render_pass, FORMAT, WIDTH, HEIGHT, vk::ImageUsageFlags::TRANSFER_SRC) {
                Ok(target) => target,
                Err(error) => {
                    core.device.destroy_render_pass(render_pass, None);
                    return Err(error);
                }
            };
            let readback = match create_readback(core, BYTES as u64) {
                Ok(readback) => readback,
                Err(error) => {
                    target.destroy(core);
                    core.device.destroy_render_pass(render_pass, None);
                    return Err(error);
                }
            };
            Ok(Self { render_pass, target, readback, pipelines: HashMap::new(), pending: None, analyzer: ToneAnalyzer::default() })
        }
    }

    pub(super) fn discard_unsubmitted(&mut self, submitted: u64) {
        if self.pending.is_some_and(|(_, at)| at >= submitted) {
            self.pending = None;
        }
    }

    pub(super) unsafe fn collect(&mut self, serial: u64, completed: u64) -> Option<f32> {
        self.analyzer.reset(serial);
        if let Some((measured, at)) = self.pending {
            if completed > at {
                self.pending = None;
                if measured == serial {
                    let pixels = unsafe { std::slice::from_raw_parts(self.readback.mapped as *const u8, BYTES) };
                    if let Some((peak, average)) = ToneAnalyzer::measure(pixels) {
                        self.analyzer.push(peak, average);
                    }
                }
            }
        }
        self.analyzer.peak_nits()
    }

    #[allow(clippy::too_many_arguments)]
    pub(super) unsafe fn record(
        &mut self,
        core: &Core,
        serial: u64,
        submitted: u64,
        params: &Params,
        layout: vk::PipelineLayout,
        set: vk::DescriptorSet,
        fragment: vk::ShaderModule,
        key: Option<ExternalKey>,
    ) -> Result<(), String> {
        unsafe {
            if self.pending.is_some() {
                return Ok(());
            }
            let pipeline = match self.pipelines.get(&key) {
                Some(pipeline) => *pipeline,
                None => {
                    let pipeline =
                        core.create_pipeline(self.render_pass, layout, fragment, 0).map_err(|e| format!("meter pipeline: {e}"))?;
                    self.pipelines.insert(key, pipeline);
                    pipeline
                }
            };
            let mut push = *params;
            push.scale = [1.0, 1.0];
            push.translate = [0.0, 0.0];
            push.ratio = [1.0, 1.0];
            push.flags = (push.flags & !(0x3FE | (0x3F << 15))) | (MEASURE_OUTPUT << 7);
            let command = core.command_buffer;
            record_draw(
                core,
                command,
                &Draw {
                    render_pass: self.render_pass,
                    framebuffer: self.target.framebuffer,
                    extent: vk::Extent2D { width: WIDTH as u32, height: HEIGHT as u32 },
                    clear: black(),
                    pipeline,
                    layout,
                    set,
                    stages: vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                    push: push.bytes(),
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
                .image(self.target.image)
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
                .image_extent(vk::Extent3D { width: WIDTH as u32, height: HEIGHT as u32, depth: 1 });
            core.device.cmd_copy_image_to_buffer(command, self.target.image, vk::ImageLayout::TRANSFER_SRC_OPTIMAL, self.readback.buffer, &[region]);
            let to_host = vk::BufferMemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::HOST_READ)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .buffer(self.readback.buffer)
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
            self.pending = Some((serial, submitted));
            Ok(())
        }
    }

    pub(super) unsafe fn destroy(&mut self, core: &Core) {
        unsafe {
            for (_, pipeline) in self.pipelines.drain() {
                core.device.destroy_pipeline(pipeline, None);
            }
            destroy_readback(core, &self.readback);
            self.target.destroy(core);
            core.device.destroy_render_pass(self.render_pass, None);
        }
    }
}
