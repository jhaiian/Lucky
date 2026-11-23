use std::collections::HashMap;

use ash::vk;
use lucky_player::{MotionConfig, VideoFrame};

use super::motion::{Motion, MotionFrame};
use super::{Core, ExternalKey, VulkanPresenter, color_range, find_memory, spirv};
use crate::egl::monotonic_ns;
use crate::gles::GlesPresenter;
use crate::memc::{DUPLICATE_LIMIT, MemcParams};

const FULLSCREEN_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/fullscreen.vert.spv"));
const BLEND_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/blend.frag.spv"));

pub(super) const BLEND_PIPELINE: i32 = 0;
pub(super) const MOTION_PIPELINE: i32 = 1;

const SETS: u32 = 128;
const BINDINGS: u32 = 4;

pub(super) fn bytes_of<T: Copy>(value: &T) -> &[u8] {
    unsafe { std::slice::from_raw_parts(value as *const T as *const u8, std::mem::size_of::<T>()) }
}

pub(super) fn black() -> vk::ClearValue {
    vk::ClearValue { color: vk::ClearColorValue { float32: [0.0, 0.0, 0.0, 1.0] } }
}

pub(super) struct Draw<'a> {
    pub(super) render_pass: vk::RenderPass,
    pub(super) framebuffer: vk::Framebuffer,
    pub(super) extent: vk::Extent2D,
    pub(super) clear: vk::ClearValue,
    pub(super) pipeline: vk::Pipeline,
    pub(super) layout: vk::PipelineLayout,
    pub(super) set: vk::DescriptorSet,
    pub(super) stages: vk::ShaderStageFlags,
    pub(super) push: &'a [u8],
    pub(super) vertices: u32,
}

pub(super) unsafe fn record_draw(core: &Core, command: vk::CommandBuffer, draw: &Draw) {
    unsafe {
        let device = &core.device;
        let clear = [draw.clear];
        device.cmd_begin_render_pass(
            command,
            &vk::RenderPassBeginInfo::default()
                .render_pass(draw.render_pass)
                .framebuffer(draw.framebuffer)
                .render_area(vk::Rect2D { offset: vk::Offset2D::default(), extent: draw.extent })
                .clear_values(&clear),
            vk::SubpassContents::INLINE,
        );
        device.cmd_set_viewport(
            command,
            0,
            &[vk::Viewport {
                x: 0.0,
                y: 0.0,
                width: draw.extent.width as f32,
                height: draw.extent.height as f32,
                min_depth: 0.0,
                max_depth: 1.0,
            }],
        );
        device.cmd_set_scissor(command, 0, &[vk::Rect2D { offset: vk::Offset2D::default(), extent: draw.extent }]);
        device.cmd_bind_pipeline(command, vk::PipelineBindPoint::GRAPHICS, draw.pipeline);
        device.cmd_bind_descriptor_sets(command, vk::PipelineBindPoint::GRAPHICS, draw.layout, 0, &[draw.set], &[]);
        if !draw.push.is_empty() {
            device.cmd_push_constants(command, draw.layout, draw.stages, 0, draw.push);
        }
        device.cmd_draw(command, draw.vertices, 1, 0, 0);
        device.cmd_end_render_pass(command);
    }
}

pub(super) unsafe fn offscreen_pass(core: &Core, format: vk::Format) -> Result<vk::RenderPass, vk::Result> {
    unsafe {
        let attachments = [vk::AttachmentDescription::default()
            .format(format)
            .samples(vk::SampleCountFlags::TYPE_1)
            .load_op(vk::AttachmentLoadOp::CLEAR)
            .store_op(vk::AttachmentStoreOp::STORE)
            .stencil_load_op(vk::AttachmentLoadOp::DONT_CARE)
            .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
            .initial_layout(vk::ImageLayout::UNDEFINED)
            .final_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
        let color = [vk::AttachmentReference::default().attachment(0).layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL)];
        let subpasses = [vk::SubpassDescription::default()
            .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)
            .color_attachments(&color)];
        let dependencies = [
            vk::SubpassDependency::default()
                .src_subpass(vk::SUBPASS_EXTERNAL)
                .dst_subpass(0)
                .src_stage_mask(vk::PipelineStageFlags::FRAGMENT_SHADER | vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
                .dst_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
                .src_access_mask(vk::AccessFlags::empty())
                .dst_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE),
            vk::SubpassDependency::default()
                .src_subpass(0)
                .dst_subpass(vk::SUBPASS_EXTERNAL)
                .src_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
                .dst_stage_mask(vk::PipelineStageFlags::FRAGMENT_SHADER)
                .src_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)
                .dst_access_mask(vk::AccessFlags::SHADER_READ),
        ];
        core.device.create_render_pass(
            &vk::RenderPassCreateInfo::default().attachments(&attachments).subpasses(&subpasses).dependencies(&dependencies),
            None,
        )
    }
}

pub(super) unsafe fn fullscreen_pipeline(
    core: &Core,
    render_pass: vk::RenderPass,
    layout: vk::PipelineLayout,
    vertex: vk::ShaderModule,
    fragment: vk::ShaderModule,
) -> Result<vk::Pipeline, vk::Result> {
    unsafe {
        let stages = [
            vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::VERTEX).module(vertex).name(c"main"),
            vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::FRAGMENT).module(fragment).name(c"main"),
        ];
        let vertex_input = vk::PipelineVertexInputStateCreateInfo::default();
        let assembly = vk::PipelineInputAssemblyStateCreateInfo::default().topology(vk::PrimitiveTopology::TRIANGLE_LIST);
        let viewport = vk::PipelineViewportStateCreateInfo::default().viewport_count(1).scissor_count(1);
        let raster = vk::PipelineRasterizationStateCreateInfo::default()
            .polygon_mode(vk::PolygonMode::FILL)
            .cull_mode(vk::CullModeFlags::NONE)
            .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
            .line_width(1.0);
        let multisample = vk::PipelineMultisampleStateCreateInfo::default().rasterization_samples(vk::SampleCountFlags::TYPE_1);
        let attachments = [vk::PipelineColorBlendAttachmentState::default().color_write_mask(vk::ColorComponentFlags::RGBA)];
        let blend = vk::PipelineColorBlendStateCreateInfo::default().attachments(&attachments);
        let dynamic_states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
        let dynamic = vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dynamic_states);
        let info = vk::GraphicsPipelineCreateInfo::default()
            .stages(&stages)
            .vertex_input_state(&vertex_input)
            .input_assembly_state(&assembly)
            .viewport_state(&viewport)
            .rasterization_state(&raster)
            .multisample_state(&multisample)
            .color_blend_state(&blend)
            .dynamic_state(&dynamic)
            .layout(layout)
            .render_pass(render_pass)
            .subpass(0);
        core.device
            .create_graphics_pipelines(vk::PipelineCache::null(), &[info], None)
            .map(|pipelines| pipelines[0])
            .map_err(|(_, error)| error)
    }
}

pub(super) struct Rt {
    pub(super) image: vk::Image,
    pub(super) memory: vk::DeviceMemory,
    pub(super) view: vk::ImageView,
    pub(super) framebuffer: vk::Framebuffer,
    pub(super) width: i32,
    pub(super) height: i32,
}

impl Rt {
    pub(super) unsafe fn new(
        core: &Core,
        render_pass: vk::RenderPass,
        format: vk::Format,
        width: i32,
        height: i32,
        extra: vk::ImageUsageFlags,
    ) -> Result<Self, String> {
        unsafe {
            let mut rt = Rt {
                image: vk::Image::null(),
                memory: vk::DeviceMemory::null(),
                view: vk::ImageView::null(),
                framebuffer: vk::Framebuffer::null(),
                width,
                height,
            };
            match rt.build(core, render_pass, format, extra) {
                Ok(()) => Ok(rt),
                Err(error) => {
                    rt.destroy(core);
                    Err(error)
                }
            }
        }
    }

    unsafe fn build(&mut self, core: &Core, render_pass: vk::RenderPass, format: vk::Format, extra: vk::ImageUsageFlags) -> Result<(), String> {
        unsafe {
            let device = &core.device;
            self.image = device
                .create_image(
                    &vk::ImageCreateInfo::default()
                        .image_type(vk::ImageType::TYPE_2D)
                        .format(format)
                        .extent(vk::Extent3D { width: self.width as u32, height: self.height as u32, depth: 1 })
                        .mip_levels(1)
                        .array_layers(1)
                        .samples(vk::SampleCountFlags::TYPE_1)
                        .tiling(vk::ImageTiling::OPTIMAL)
                        .usage(vk::ImageUsageFlags::COLOR_ATTACHMENT | vk::ImageUsageFlags::SAMPLED | extra)
                        .sharing_mode(vk::SharingMode::EXCLUSIVE)
                        .initial_layout(vk::ImageLayout::UNDEFINED),
                    None,
                )
                .map_err(|e| format!("target image: {e}"))?;
            let requirements = device.get_image_memory_requirements(self.image);
            let type_index = find_memory(&core.memory, requirements.memory_type_bits, vk::MemoryPropertyFlags::DEVICE_LOCAL)
                .or_else(|| find_memory(&core.memory, requirements.memory_type_bits, vk::MemoryPropertyFlags::empty()))
                .ok_or("no memory type for a render target")?;
            self.memory = device
                .allocate_memory(&vk::MemoryAllocateInfo::default().allocation_size(requirements.size).memory_type_index(type_index), None)
                .map_err(|e| format!("target memory: {e}"))?;
            device.bind_image_memory(self.image, self.memory, 0).map_err(|e| format!("target bind: {e}"))?;
            self.view = device
                .create_image_view(
                    &vk::ImageViewCreateInfo::default()
                        .image(self.image)
                        .view_type(vk::ImageViewType::TYPE_2D)
                        .format(format)
                        .subresource_range(color_range()),
                    None,
                )
                .map_err(|e| format!("target view: {e}"))?;
            let views = [self.view];
            self.framebuffer = device
                .create_framebuffer(
                    &vk::FramebufferCreateInfo::default()
                        .render_pass(render_pass)
                        .attachments(&views)
                        .width(self.width as u32)
                        .height(self.height as u32)
                        .layers(1),
                    None,
                )
                .map_err(|e| format!("target framebuffer: {e}"))?;
            Ok(())
        }
    }

    pub(super) unsafe fn destroy(&self, core: &Core) {
        unsafe {
            core.device.destroy_framebuffer(self.framebuffer, None);
            core.device.destroy_image_view(self.view, None);
            core.device.destroy_image(self.image, None);
            core.device.free_memory(self.memory, None);
        }
    }
}

pub(super) struct Ctx<'a> {
    pub(super) core: &'a Core,
    pub(super) command: vk::CommandBuffer,
    pool: vk::DescriptorPool,
    set_layout: vk::DescriptorSetLayout,
    linear: vk::Sampler,
    nearest: vk::Sampler,
}

impl Ctx<'_> {
    pub(super) unsafe fn bind(&self, views: &[(vk::ImageView, bool)]) -> Result<vk::DescriptorSet, String> {
        unsafe {
            let layouts = [self.set_layout];
            let set = self
                .core
                .device
                .allocate_descriptor_sets(&vk::DescriptorSetAllocateInfo::default().descriptor_pool(self.pool).set_layouts(&layouts))
                .map_err(|e| format!("descriptor set: {e}"))?[0];
            let infos: Vec<[vk::DescriptorImageInfo; 1]> = views
                .iter()
                .map(|(view, linear)| {
                    [vk::DescriptorImageInfo::default()
                        .sampler(if *linear { self.linear } else { self.nearest })
                        .image_view(*view)
                        .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)]
                })
                .collect();
            let writes: Vec<vk::WriteDescriptorSet> = infos
                .iter()
                .enumerate()
                .map(|(binding, info)| {
                    vk::WriteDescriptorSet::default()
                        .dst_set(set)
                        .dst_binding(binding as u32)
                        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                        .image_info(info)
                })
                .collect();
            self.core.device.update_descriptor_sets(&writes, &[]);
            Ok(set)
        }
    }
}

pub(super) struct Slot {
    pub(super) rt: Rt,
    pub(super) key: Option<(u64, i64)>,
    pub(super) version: u64,
}

pub(super) struct Smooth {
    pub(super) format: vk::Format,
    pub(super) render_pass: vk::RenderPass,
    linear: vk::Sampler,
    nearest: vk::Sampler,
    set_layout: vk::DescriptorSetLayout,
    pool: vk::DescriptorPool,
    pub(super) fullscreen: vk::ShaderModule,
    blend_fragment: vk::ShaderModule,
    blend_layout: vk::PipelineLayout,
    pub(super) slots: [Option<Slot>; 2],
    slot_version: u64,
    pub(super) yuv: HashMap<i32, vk::Pipeline>,
    pub(super) external: HashMap<(ExternalKey, i32), vk::Pipeline>,
    pub(super) motion: Option<Motion>,
}

impl Smooth {
    pub(super) unsafe fn new(core: &Core, format: vk::Format) -> Result<Self, String> {
        unsafe {
            let mut smooth = Smooth {
                format,
                render_pass: vk::RenderPass::null(),
                linear: vk::Sampler::null(),
                nearest: vk::Sampler::null(),
                set_layout: vk::DescriptorSetLayout::null(),
                pool: vk::DescriptorPool::null(),
                fullscreen: vk::ShaderModule::null(),
                blend_fragment: vk::ShaderModule::null(),
                blend_layout: vk::PipelineLayout::null(),
                slots: [None, None],
                slot_version: 0,
                yuv: HashMap::new(),
                external: HashMap::new(),
                motion: None,
            };
            if let Err(error) = smooth.build(core) {
                smooth.destroy(core);
                return Err(error);
            }
            Ok(smooth)
        }
    }

    unsafe fn build(&mut self, core: &Core) -> Result<(), String> {
        unsafe {
            let device = &core.device;
            self.render_pass = offscreen_pass(core, self.format).map_err(|e| format!("smooth render pass: {e}"))?;
            let sampler = |filter: vk::Filter| {
                device.create_sampler(
                    &vk::SamplerCreateInfo::default()
                        .mag_filter(filter)
                        .min_filter(filter)
                        .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                        .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                        .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE),
                    None,
                )
            };
            self.linear = sampler(vk::Filter::LINEAR).map_err(|e| format!("smooth sampler: {e}"))?;
            self.nearest = sampler(vk::Filter::NEAREST).map_err(|e| format!("smooth sampler: {e}"))?;
            let bindings: Vec<_> = (0..BINDINGS)
                .map(|binding| {
                    vk::DescriptorSetLayoutBinding::default()
                        .binding(binding)
                        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                        .descriptor_count(1)
                        .stage_flags(vk::ShaderStageFlags::FRAGMENT)
                })
                .collect();
            self.set_layout = device
                .create_descriptor_set_layout(&vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings), None)
                .map_err(|e| format!("smooth set layout: {e}"))?;
            let pool_sizes = [vk::DescriptorPoolSize::default().ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER).descriptor_count(SETS * BINDINGS)];
            self.pool = device
                .create_descriptor_pool(&vk::DescriptorPoolCreateInfo::default().max_sets(SETS).pool_sizes(&pool_sizes), None)
                .map_err(|e| format!("smooth descriptor pool: {e}"))?;
            let module = |bytes: &[u8]| {
                let code = spirv(bytes);
                device.create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&code), None)
            };
            self.fullscreen = module(FULLSCREEN_VERT).map_err(|e| format!("smooth shader: {e}"))?;
            self.blend_fragment = module(BLEND_FRAG).map_err(|e| format!("smooth shader: {e}"))?;
            let layouts = [self.set_layout];
            let ranges = [vk::PushConstantRange::default().stage_flags(vk::ShaderStageFlags::FRAGMENT).offset(0).size(8)];
            self.blend_layout = device
                .create_pipeline_layout(&vk::PipelineLayoutCreateInfo::default().set_layouts(&layouts).push_constant_ranges(&ranges), None)
                .map_err(|e| format!("smooth pipeline layout: {e}"))?;
            Ok(())
        }
    }

    pub(super) fn pool(&self) -> vk::DescriptorPool {
        self.pool
    }

    fn ctx<'a>(&self, core: &'a Core) -> Ctx<'a> {
        Ctx {
            core,
            command: core.command_buffer,
            pool: self.pool,
            set_layout: self.set_layout,
            linear: self.linear,
            nearest: self.nearest,
        }
    }

    pub(super) fn invalidate(&mut self) {
        for slot in self.slots.iter_mut().flatten() {
            slot.key = None;
        }
    }

    unsafe fn ensure_slot(&mut self, core: &Core, index: usize, extent: vk::Extent2D) -> Result<(), String> {
        unsafe {
            let (width, height) = (extent.width as i32, extent.height as i32);
            if self.slots[index].as_ref().is_some_and(|slot| slot.rt.width == width && slot.rt.height == height) {
                return Ok(());
            }
            if let Some(old) = self.slots[index].take() {
                old.rt.destroy(core);
            }
            let rt = Rt::new(core, self.render_pass, self.format, width, height, vk::ImageUsageFlags::empty())?;
            self.slots[index] = Some(Slot { rt, key: None, version: 0 });
            Ok(())
        }
    }

    pub(super) unsafe fn destroy(&mut self, core: &Core) {
        unsafe {
            let device = &core.device;
            if let Some(mut motion) = self.motion.take() {
                motion.destroy(core);
            }
            for slot in self.slots.iter_mut().filter_map(|slot| slot.take()) {
                slot.rt.destroy(core);
            }
            for (_, pipeline) in self.yuv.drain() {
                device.destroy_pipeline(pipeline, None);
            }
            for (_, pipeline) in self.external.drain() {
                device.destroy_pipeline(pipeline, None);
            }
            device.destroy_pipeline_layout(self.blend_layout, None);
            device.destroy_shader_module(self.blend_fragment, None);
            device.destroy_shader_module(self.fullscreen, None);
            device.destroy_descriptor_pool(self.pool, None);
            device.destroy_descriptor_set_layout(self.set_layout, None);
            device.destroy_sampler(self.nearest, None);
            device.destroy_sampler(self.linear, None);
            device.destroy_render_pass(self.render_pass, None);
        }
    }
}

struct BlendPlan {
    to_slot: usize,
    to_new: bool,
    from_slot: usize,
    from_new: bool,
    motion: bool,
}

fn same_frame(a: &VideoFrame, b: &VideoFrame) -> bool {
    a.serial == b.serial && a.pts_us == b.pts_us
}

impl VulkanPresenter {
    pub fn smooth_unavailable(&self) -> bool {
        self.smooth_failed || self.core.as_ref().is_some_and(|core| core.smooth_format.is_none())
    }

    pub fn supports_blend(&self) -> bool {
        !self.failed && self.target.is_some() && !self.smooth_unavailable()
    }

    pub fn set_motion_config(&mut self, config: MotionConfig) {
        if config.level != self.motion.level {
            if let Some(motion) = self.smooth.as_mut().and_then(|smooth| smooth.motion.as_mut()) {
                motion.reset_motion();
            }
        }
        self.motion = config;
    }

    pub fn release_smooth(&mut self) {
        if self.smooth.is_none() {
            return;
        }
        let Some(core) = self.core.as_ref() else {
            return;
        };
        unsafe {
            let _ = core.device.device_wait_idle();
            if let Some(target) = self.target.as_mut() {
                for (_, pipeline) in target.smooth_pipelines.drain() {
                    core.device.destroy_pipeline(pipeline, None);
                }
            }
            if let Some(mut smooth) = self.smooth.take() {
                smooth.destroy(core);
            }
        }
    }

    pub(super) fn ensure_smooth(&mut self) -> Result<(), String> {
        if self.smooth.is_some() {
            return Ok(());
        }
        let core = self.core.as_ref().ok_or("no device")?;
        let format = core.smooth_format.ok_or("the device cannot render the formats smooth motion needs")?;
        self.smooth = Some(unsafe { Smooth::new(core, format)? });
        Ok(())
    }

    fn slot_for(&self, video: &VideoFrame) -> Option<usize> {
        let key = Some((video.serial, video.pts_us));
        let extent = self.target.as_ref()?.extent;
        let smooth = self.smooth.as_ref()?;
        (0..2).find(|index| {
            smooth.slots[*index]
                .as_ref()
                .is_some_and(|slot| slot.key == key && slot.rt.width == extent.width as i32 && slot.rt.height == extent.height as i32)
        })
    }

    fn pick_slot(&self, keep: Option<usize>) -> usize {
        match keep {
            Some(0) => 1,
            Some(1) => 0,
            _ => match self.smooth.as_ref() {
                Some(smooth) if smooth.slots[0].is_some() && smooth.slots[1].is_none() => 1,
                _ => 0,
            },
        }
    }

    fn plan_slots(&self, from: &VideoFrame, to: &VideoFrame, needs_from: bool) -> (usize, bool, usize, bool) {
        let holding = self.slot_for(from);
        let (to_slot, to_new) = match self.slot_for(to) {
            Some(index) => (index, false),
            None => (self.pick_slot(holding), true),
        };
        if !needs_from || same_frame(from, to) {
            return (to_slot, to_new, to_slot, false);
        }
        match self.slot_for(from) {
            Some(index) => (to_slot, to_new, index, false),
            None => (to_slot, to_new, self.pick_slot(Some(to_slot)), true),
        }
    }

    unsafe fn ensure_slot_image(&mut self, index: usize) -> Result<(), String> {
        unsafe {
            let extent = self.target.as_ref().ok_or("no target")?.extent;
            let core = self.core.as_ref().ok_or("no device")?;
            let smooth = self.smooth.as_mut().ok_or("no smooth state")?;
            smooth.ensure_slot(core, index, extent)
        }
    }

    unsafe fn ensure_swapchain_pipeline(&mut self, id: i32) -> Result<(), String> {
        unsafe {
            let core = self.core.as_ref().ok_or("no device")?;
            let target = self.target.as_mut().ok_or("no target")?;
            let smooth = self.smooth.as_ref().ok_or("no smooth state")?;
            if target.smooth_pipelines.contains_key(&id) {
                return Ok(());
            }
            let (layout, fragment) = if id == BLEND_PIPELINE {
                (smooth.blend_layout, smooth.blend_fragment)
            } else {
                let motion = smooth.motion.as_ref().ok_or("no motion state")?;
                (motion.render_layout(), motion.render_module())
            };
            let pipeline = fullscreen_pipeline(core, target.render_pass, layout, smooth.fullscreen, fragment)
                .map_err(|e| format!("smooth pipeline: {e}"))?;
            target.smooth_pipelines.insert(id, pipeline);
            Ok(())
        }
    }

    unsafe fn ensure_motion(&mut self, check_only: bool) -> Result<(), String> {
        unsafe {
            let extent = self.target.as_ref().ok_or("no target")?.extent;
            let size = (extent.width as i32, extent.height as i32);
            let level = self.motion.level;
            let core = self.core.as_ref().ok_or("no device")?;
            let smooth = self.smooth.as_mut().ok_or("no smooth state")?;
            if smooth.motion.is_none() {
                smooth.motion = Some(Motion::new(core, smooth.fullscreen, smooth.set_layout)?);
            }
            let motion = smooth.motion.as_mut().ok_or("no motion state")?;
            if check_only {
                motion.prepare_check(core, size)
            } else {
                motion.prepare(core, size, level)
            }
        }
    }

    fn video_rect(&self, video: &VideoFrame) -> [f32; 4] {
        let Some(target) = self.target.as_ref() else {
            return [0.0; 4];
        };
        let placement = GlesPresenter::geometry(video, target.logical.0 as i32, target.logical.1 as i32, &self.transform);
        let (sx, sy) = (placement.scale[0].abs(), placement.scale[1].abs());
        let (tx, ty) = (placement.translate[0], placement.translate[1]);
        let rotate = |c: [f32; 2]| match target.rotation {
            1 => [-c[1], c[0]],
            2 => [-c[0], -c[1]],
            3 => [c[1], -c[0]],
            _ => c,
        };
        let a = rotate([tx - sx, ty - sy]);
        let b = rotate([tx + sx, ty + sy]);
        let width = target.extent.width as f32;
        let height = target.extent.height as f32;
        let x0 = ((a[0].min(b[0]) + 1.0) * 0.5 * width).clamp(0.0, width);
        let x1 = ((a[0].max(b[0]) + 1.0) * 0.5 * width).clamp(0.0, width);
        let y0 = ((a[1].min(b[1]) + 1.0) * 0.5 * height).clamp(0.0, height);
        let y1 = ((a[1].max(b[1]) + 1.0) * 0.5 * height).clamp(0.0, height);
        [x0, y0, x1, y1]
    }

    unsafe fn render_slot(&mut self, video: &VideoFrame, index: usize) -> Result<(), String> {
        unsafe {
            let prepared = self.prepare_source(video, index, true, None)?;
            let core = self.core.as_ref().ok_or("no device")?;
            let smooth = self.smooth.as_mut().ok_or("no smooth state")?;
            let slot = smooth.slots[index].as_mut().ok_or("smooth slot is missing")?;
            slot.key = None;
            record_draw(
                core,
                core.command_buffer,
                &Draw {
                    render_pass: smooth.render_pass,
                    framebuffer: slot.rt.framebuffer,
                    extent: vk::Extent2D { width: slot.rt.width as u32, height: slot.rt.height as u32 },
                    clear: black(),
                    pipeline: prepared.pipeline,
                    layout: prepared.layout,
                    set: prepared.set,
                    stages: vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                    push: prepared.params.bytes(),
                    vertices: 4,
                },
            );
            smooth.slot_version += 1;
            slot.version = smooth.slot_version;
            slot.key = Some((video.serial, video.pts_us));
            Ok(())
        }
    }

    unsafe fn plan_blend(&mut self, from: &VideoFrame, to: &VideoFrame, weight: f32) -> Result<BlendPlan, String> {
        unsafe {
            self.ensure_smooth()?;
            self.refresh_target(to)?;
            let (to_slot, to_new, from_slot, from_new) = self.plan_slots(from, to, weight < 0.999);
            let motion = self.motion.level > 0 && from_slot != to_slot && weight > 0.002 && weight < 0.998;
            if to_new {
                self.ensure_slot_image(to_slot)?;
            }
            if from_new {
                self.ensure_slot_image(from_slot)?;
            }
            if motion {
                self.ensure_motion(false)?;
                self.ensure_swapchain_pipeline(MOTION_PIPELINE)?;
            } else {
                self.ensure_swapchain_pipeline(BLEND_PIPELINE)?;
            }
            Ok(BlendPlan { to_slot, to_new, from_slot, from_new, motion })
        }
    }

    unsafe fn record_blend_pass(&mut self, index: u32, plan: &BlendPlan, weight: f32) -> Result<(), String> {
        unsafe {
            let core = self.core.as_ref().ok_or("no device")?;
            let target = self.target.as_ref().ok_or("no target")?;
            let smooth = self.smooth.as_ref().ok_or("no smooth state")?;
            let a = smooth.slots[plan.from_slot].as_ref().ok_or("blend source is missing")?;
            let b = smooth.slots[plan.to_slot].as_ref().ok_or("blend source is missing")?;
            let ctx = smooth.ctx(core);
            let set = ctx.bind(&[(a.rt.view, false), (b.rt.view, false)])?;
            let pipeline = *target.smooth_pipelines.get(&BLEND_PIPELINE).ok_or("blend pipeline is missing")?;
            let push = [weight.clamp(0.0, 1.0), super::dither_levels(target.dither)];
            record_draw(
                core,
                core.command_buffer,
                &Draw {
                    render_pass: target.render_pass,
                    framebuffer: target.framebuffers[index as usize],
                    extent: target.extent,
                    clear: black(),
                    pipeline,
                    layout: smooth.blend_layout,
                    set,
                    stages: vk::ShaderStageFlags::FRAGMENT,
                    push: bytes_of(&push),
                    vertices: 3,
                },
            );
            Ok(())
        }
    }

    unsafe fn record_motion_pass(&mut self, index: u32, to: &VideoFrame, plan: &BlendPlan, weight: f32) -> Result<(), String> {
        unsafe {
            let rect = self.video_rect(to);
            let params = MemcParams { level: self.motion.level, protection: self.motion.protection, scene: self.motion.scene };
            let core = self.core.as_ref().ok_or("no device")?;
            let target = self.target.as_ref().ok_or("no target")?;
            let size = (target.extent.width as i32, target.extent.height as i32);
            let smooth = self.smooth.as_mut().ok_or("no smooth state")?;
            let frame = |slot: &Option<Slot>| slot.as_ref().map(|slot| MotionFrame { view: slot.rt.view, version: slot.version });
            let (Some(a), Some(b)) = (frame(&smooth.slots[plan.from_slot]), frame(&smooth.slots[plan.to_slot])) else {
                return Err("motion source is missing".into());
            };
            let ctx = smooth.ctx(core);
            let motion = smooth.motion.as_mut().ok_or("motion state is missing")?;
            let mut draw = motion.record(&ctx, a, b, size, rect, weight.clamp(0.0, 1.0), params)?;
            draw.push.set_dither(super::dither_levels(target.dither));
            let pipeline = *target.smooth_pipelines.get(&MOTION_PIPELINE).ok_or("motion pipeline is missing")?;
            record_draw(
                core,
                core.command_buffer,
                &Draw {
                    render_pass: target.render_pass,
                    framebuffer: target.framebuffers[index as usize],
                    extent: target.extent,
                    clear: black(),
                    pipeline,
                    layout: draw.layout,
                    set: draw.set,
                    stages: vk::ShaderStageFlags::FRAGMENT,
                    push: bytes_of(&draw.push),
                    vertices: 3,
                },
            );
            Ok(())
        }
    }

    unsafe fn record_blend(&mut self, from: &VideoFrame, to: &VideoFrame, weight: f32, plan: &BlendPlan, delay_us: i64) -> Result<bool, String> {
        unsafe {
            let Some(index) = self.acquire_frame()? else {
                return Ok(false);
            };
            if plan.to_new {
                self.render_slot(to, plan.to_slot)?;
            }
            if plan.from_new {
                self.render_slot(from, plan.from_slot)?;
            }
            if plan.motion {
                self.record_motion_pass(index, to, plan, weight)?;
            } else {
                self.record_blend_pass(index, plan, weight)?;
            }
            if delay_us > 0 && self.core.as_ref().is_some_and(|core| core.display_timing) {
                self.target_ns = Some(monotonic_ns() + delay_us * 1_000);
            }
            let presented = self.submit_frame(index);
            self.target_ns = None;
            presented
        }
    }

    fn mark_smooth_failed(&mut self, error: &str) {
        if !self.smooth_failed {
            log::warn!("Vulkan smooth motion is unavailable: {error}");
            self.smooth_failed = true;
        }
    }

    pub fn present_blend(&mut self, from: &VideoFrame, to: &VideoFrame, weight: f32, delay_us: i64) -> bool {
        if self.failed || self.target.is_none() {
            return false;
        }
        if self.smooth_failed {
            return self.present_paced(if weight >= 0.5 { to } else { from }, delay_us);
        }
        let plan = match unsafe { self.plan_blend(from, to, weight) } {
            Ok(plan) => plan,
            Err(error) => {
                self.mark_smooth_failed(&error);
                return self.present_paced(if weight >= 0.5 { to } else { from }, delay_us);
            }
        };
        match unsafe { self.record_blend(from, to, weight, &plan, delay_us) } {
            Ok(drawn) => drawn,
            Err(error) => {
                log::error!("Vulkan rendering failed: {error}");
                self.failed = true;
                false
            }
        }
    }

    fn poll_fence(&mut self) {
        let Some(core) = self.core.as_ref() else {
            return;
        };
        if unsafe { core.device.get_fence_status(core.fence) } == Ok(true) {
            self.completed = self.submitted;
        }
    }

    pub(super) unsafe fn submit_commands(&mut self) -> Result<(), String> {
        unsafe {
            let core = self.core.as_ref().ok_or("no device")?;
            let command = core.command_buffer;
            core.device.end_command_buffer(command).map_err(|e| e.to_string())?;
            core.device.reset_fences(&[core.fence]).map_err(|e| e.to_string())?;
            let commands = [command];
            core.device
                .queue_submit(core.queue, &[vk::SubmitInfo::default().command_buffers(&commands)], core.fence)
                .map_err(|e| format!("submit: {e}"))?;
            self.submitted += 1;
            Ok(())
        }
    }

    fn slot_versions(&self, a: usize, b: usize) -> Option<(MotionFrame, MotionFrame)> {
        let smooth = self.smooth.as_ref()?;
        let frame = |index: usize| smooth.slots[index].as_ref().map(|slot| MotionFrame { view: slot.rt.view, version: slot.version });
        Some((frame(a)?, frame(b)?))
    }

    unsafe fn run_check(&mut self, from: &VideoFrame, to: &VideoFrame) -> Result<Option<bool>, String> {
        unsafe {
            self.target.as_ref().ok_or("no target")?;
            let completed = self.completed;
            if let (Some(a), Some(b)) = (self.slot_for(from), self.slot_for(to)) {
                if let Some((frame_a, frame_b)) = self.slot_versions(a, b) {
                    if let Some(motion) = self.smooth.as_mut().and_then(|smooth| smooth.motion.as_mut()) {
                        if let Some(peak) = motion.check_result(completed, frame_a.version, frame_b.version) {
                            return Ok(Some(peak < DUPLICATE_LIMIT));
                        }
                        if motion.check_pending(frame_a.version, frame_b.version) {
                            return Ok(None);
                        }
                    }
                }
            }
            self.ensure_smooth()?;
            self.wait_gpu()?;
            let (to_slot, to_new, from_slot, from_new) = self.plan_slots(from, to, true);
            if to_new {
                self.ensure_slot_image(to_slot)?;
            }
            if from_new {
                self.ensure_slot_image(from_slot)?;
            }
            self.ensure_motion(true)?;
            self.begin_recording()?;
            if to_new {
                self.render_slot(to, to_slot)?;
            }
            if from_new {
                self.render_slot(from, from_slot)?;
            }
            let (frame_a, frame_b) = self.slot_versions(from_slot, to_slot).ok_or("check source is missing")?;
            let serial = self.submitted + 1;
            {
                let core = self.core.as_ref().ok_or("no device")?;
                let smooth = self.smooth.as_mut().ok_or("no smooth state")?;
                let ctx = smooth.ctx(core);
                let motion = smooth.motion.as_mut().ok_or("motion state is missing")?;
                motion.record_check(&ctx, frame_a, frame_b, serial)?;
            }
            self.submit_commands()?;
            self.poll_fence();
            let completed = self.completed;
            let result = self
                .smooth
                .as_mut()
                .and_then(|smooth| smooth.motion.as_mut())
                .and_then(|motion| motion.check_result(completed, frame_a.version, frame_b.version));
            Ok(result.map(|peak| peak < DUPLICATE_LIMIT))
        }
    }

    pub fn check_duplicate(&mut self, from: &VideoFrame, to: &VideoFrame) -> Option<bool> {
        if self.failed || self.smooth_failed || self.target.is_none() || self.smooth_unavailable() {
            return None;
        }
        self.poll_fence();
        match unsafe { self.run_check(from, to) } {
            Ok(result) => result,
            Err(error) => {
                self.mark_smooth_failed(&error);
                None
            }
        }
    }
}
