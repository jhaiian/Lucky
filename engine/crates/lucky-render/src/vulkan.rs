use std::collections::HashMap;
use std::ffi::{CStr, c_char};
use std::io::Cursor;
use std::ptr;

use ash::vk;
use ash::{android, khr};
use lucky_ffmpeg::{Frame, Scaler, sys};
use lucky_player::{
    ActiveScaling, ColorInfo, DisplayOptions, DownscalerMode, FrameData, HardwareFrame, MotionConfig, ScalerMode, VideoFrame, ViewTransform,
};

use crate::color::{Output, SDR_WHITE_NITS, ToneTarget, content_peak, is_bt2020, output_candidates, transfer_of, yuv_transform};
use crate::egl::monotonic_ns;
use crate::gles::{GlesPresenter, Layout, Plan, conversion_target, plan_for};
use crate::window::NativeWindow;

mod capture;
mod meter;
mod motion;
mod smooth;

const QUAD_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/quad.vert.spv"));
const YUV_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/yuv.frag.spv"));
const EXTERNAL_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/external.frag.spv"));
const FENCE_TIMEOUT: u64 = 1_000_000_000;

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct Params {
    crop: [f32; 4],
    matrix0: [f32; 4],
    matrix1: [f32; 4],
    matrix2: [f32; 4],
    offset: [f32; 4],
    scale: [f32; 2],
    peak: f32,
    tone: u32,
    flags: i32,
    antiring: f32,
    ratio: [f32; 2],
    translate: [f32; 2],
    source_size: [f32; 2],
}

impl Params {
    fn bytes(&self) -> &[u8] {
        unsafe { std::slice::from_raw_parts(self as *const Self as *const u8, std::mem::size_of::<Self>()) }
    }
}

fn spirv(bytes: &[u8]) -> Vec<u32> {
    ash::util::read_spv(&mut Cursor::new(bytes)).expect("valid SPIR-V")
}

fn find_memory(properties: &vk::PhysicalDeviceMemoryProperties, bits: u32, flags: vk::MemoryPropertyFlags) -> Option<u32> {
    (0..properties.memory_type_count).find(|&index| {
        bits & (1 << index) != 0 && properties.memory_types[index as usize].property_flags.contains(flags)
    })
}

struct Core {
    _entry: ash::Entry,
    instance: ash::Instance,
    surface_fn: khr::surface::Instance,
    android_surface_fn: khr::android_surface::Instance,
    physical: vk::PhysicalDevice,
    device: ash::Device,
    swapchain_fn: khr::swapchain::Device,
    ahb_fn: Option<android::external_memory_android_hardware_buffer::Device>,
    hdr_metadata_fn: Option<ash::ext::hdr_metadata::Device>,
    foreign_queue: bool,
    display_timing: bool,
    queue: vk::Queue,
    queue_family: u32,
    memory: vk::PhysicalDeviceMemoryProperties,
    command_pool: vk::CommandPool,
    command_buffer: vk::CommandBuffer,
    fence: vk::Fence,
    acquire_semaphore: vk::Semaphore,
    sampler: vk::Sampler,
    vertex: vk::ShaderModule,
    yuv_fragment: vk::ShaderModule,
    external_fragment: vk::ShaderModule,
    yuv_set_layout: vk::DescriptorSetLayout,
    yuv_pipeline_layout: vk::PipelineLayout,
    descriptor_pool: vk::DescriptorPool,
    yuv_sets: [vk::DescriptorSet; 2],
    norm16: bool,
    smooth_format: Option<vk::Format>,
    name: String,
}

impl Core {
    unsafe fn new(window: &NativeWindow) -> Result<(Self, vk::SurfaceKHR), String> {
        unsafe {
            let entry = ash::Entry::load().map_err(|error| format!("Vulkan loader unavailable: {error}"))?;
            let version = entry.try_enumerate_instance_version().ok().flatten().unwrap_or(vk::API_VERSION_1_0);
            if version < vk::API_VERSION_1_1 {
                return Err("Vulkan 1.1 is required".into());
            }
            let app_name = c"Lucky";
            let app = vk::ApplicationInfo::default()
                .application_name(app_name)
                .engine_name(app_name)
                .api_version(vk::API_VERSION_1_1);
            let colorspaces = entry
                .enumerate_instance_extension_properties(None)
                .unwrap_or_default()
                .iter()
                .any(|e| CStr::from_ptr(e.extension_name.as_ptr()) == ash::ext::swapchain_colorspace::NAME);
            let mut extensions = vec![khr::surface::NAME.as_ptr(), khr::android_surface::NAME.as_ptr()];
            if colorspaces {
                extensions.push(ash::ext::swapchain_colorspace::NAME.as_ptr());
            }
            let instance = entry
                .create_instance(&vk::InstanceCreateInfo::default().application_info(&app).enabled_extension_names(&extensions), None)
                .map_err(|error| format!("vkCreateInstance failed: {error}"))?;
            let surface_fn = khr::surface::Instance::new(&entry, &instance);
            let android_surface_fn = khr::android_surface::Instance::new(&entry, &instance);
            let surface = match android_surface_fn.create_android_surface(
                &vk::AndroidSurfaceCreateInfoKHR::default().window(window.as_ptr() as *mut _),
                None,
            ) {
                Ok(surface) => surface,
                Err(error) => {
                    instance.destroy_instance(None);
                    return Err(format!("vkCreateAndroidSurfaceKHR failed: {error}"));
                }
            };
            match Self::with_instance(entry, instance, surface_fn, android_surface_fn, surface) {
                Ok(core) => Ok((core, surface)),
                Err((instance, surface_fn, error)) => {
                    surface_fn.destroy_surface(surface, None);
                    instance.destroy_instance(None);
                    Err(error)
                }
            }
        }
    }

    unsafe fn with_instance(
        entry: ash::Entry,
        instance: ash::Instance,
        surface_fn: khr::surface::Instance,
        android_surface_fn: khr::android_surface::Instance,
        surface: vk::SurfaceKHR,
    ) -> Result<Self, (ash::Instance, khr::surface::Instance, String)> {
        unsafe {
            let devices = match instance.enumerate_physical_devices() {
                Ok(devices) => devices,
                Err(error) => return Err((instance, surface_fn, format!("no Vulkan devices: {error}"))),
            };
            let mut chosen = None;
            for physical in devices {
                let properties = instance.get_physical_device_properties(physical);
                if properties.api_version < vk::API_VERSION_1_1 {
                    continue;
                }
                let families = instance.get_physical_device_queue_family_properties(physical);
                for (index, family) in families.iter().enumerate() {
                    let present = surface_fn
                        .get_physical_device_surface_support(physical, index as u32, surface)
                        .unwrap_or(false);
                    if family.queue_flags.contains(vk::QueueFlags::GRAPHICS) && present {
                        chosen = Some((physical, index as u32, properties));
                        break;
                    }
                }
                if chosen.is_some() {
                    break;
                }
            }
            let Some((physical, queue_family, properties)) = chosen else {
                return Err((instance, surface_fn, "no Vulkan device can present to this surface".into()));
            };
            let available: Vec<String> = instance
                .enumerate_device_extension_properties(physical)
                .unwrap_or_default()
                .iter()
                .map(|e| CStr::from_ptr(e.extension_name.as_ptr()).to_string_lossy().into_owned())
                .collect();
            let has = |name: &CStr| available.iter().any(|e| e.as_str() == name.to_string_lossy());

            let mut ycbcr_features = vk::PhysicalDeviceSamplerYcbcrConversionFeatures::default();
            let mut features = vk::PhysicalDeviceFeatures2::default().push_next(&mut ycbcr_features);
            instance.get_physical_device_features2(physical, &mut features);
            let ycbcr = ycbcr_features.sampler_ycbcr_conversion == vk::TRUE;

            let mut extensions: Vec<*const c_char> = vec![khr::swapchain::NAME.as_ptr()];
            let ahb = ycbcr && has(android::external_memory_android_hardware_buffer::NAME);
            let foreign = has(ash::ext::queue_family_foreign::NAME);
            let display_timing = has(ash::google::display_timing::NAME);
            if display_timing {
                extensions.push(ash::google::display_timing::NAME.as_ptr());
            }
            let hdr_metadata = has(ash::ext::hdr_metadata::NAME);
            if hdr_metadata {
                extensions.push(ash::ext::hdr_metadata::NAME.as_ptr());
            }
            if ahb {
                extensions.push(android::external_memory_android_hardware_buffer::NAME.as_ptr());
                if foreign {
                    extensions.push(ash::ext::queue_family_foreign::NAME.as_ptr());
                }
            }
            let priorities = [1.0f32];
            let queues = [vk::DeviceQueueCreateInfo::default().queue_family_index(queue_family).queue_priorities(&priorities)];
            let mut enable_ycbcr = vk::PhysicalDeviceSamplerYcbcrConversionFeatures::default().sampler_ycbcr_conversion(ahb);
            let create = vk::DeviceCreateInfo::default()
                .queue_create_infos(&queues)
                .enabled_extension_names(&extensions)
                .push_next(&mut enable_ycbcr);
            let device = match instance.create_device(physical, &create, None) {
                Ok(device) => device,
                Err(error) => return Err((instance, surface_fn, format!("vkCreateDevice failed: {error}"))),
            };
            let queue = device.get_device_queue(queue_family, 0);
            let swapchain_fn = khr::swapchain::Device::new(&instance, &device);
            let ahb_fn = ahb.then(|| android::external_memory_android_hardware_buffer::Device::new(&instance, &device));
            let hdr_metadata_fn = hdr_metadata.then(|| ash::ext::hdr_metadata::Device::new(&instance, &device));
            let memory = instance.get_physical_device_memory_properties(physical);
            let filterable = |format: vk::Format| {
                let features = instance.get_physical_device_format_properties(physical, format).optimal_tiling_features;
                features.contains(vk::FormatFeatureFlags::SAMPLED_IMAGE | vk::FormatFeatureFlags::SAMPLED_IMAGE_FILTER_LINEAR)
            };
            let norm16 = filterable(vk::Format::R16_UNORM) && filterable(vk::Format::R16G16_UNORM);
            let usable = |format: vk::Format, wanted: vk::FormatFeatureFlags| {
                instance.get_physical_device_format_properties(physical, format).optimal_tiling_features.contains(wanted)
            };
            let target_features = vk::FormatFeatureFlags::SAMPLED_IMAGE | vk::FormatFeatureFlags::COLOR_ATTACHMENT;
            let filtered_target = target_features | vk::FormatFeatureFlags::SAMPLED_IMAGE_FILTER_LINEAR;
            let smooth_format = [vk::Format::A2B10G10R10_UNORM_PACK32, vk::Format::R16G16B16A16_SFLOAT]
                .into_iter()
                .find(|format| usable(*format, filtered_target))
                .filter(|_| {
                    usable(vk::Format::R8_UNORM, filtered_target)
                        && usable(vk::Format::R8G8B8A8_UNORM, target_features)
                        && usable(vk::Format::R16G16B16A16_SINT, target_features)
                        && usable(vk::Format::R32G32B32A32_SINT, target_features)
                });
            let name = format!(
                "Vulkan {}.{} · {}",
                vk::api_version_major(properties.api_version),
                vk::api_version_minor(properties.api_version),
                CStr::from_ptr(properties.device_name.as_ptr()).to_string_lossy()
            );

            let built = (|| -> Result<_, vk::Result> {
                let command_pool = device.create_command_pool(
                    &vk::CommandPoolCreateInfo::default()
                        .queue_family_index(queue_family)
                        .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER),
                    None,
                )?;
                let command_buffer = device.allocate_command_buffers(
                    &vk::CommandBufferAllocateInfo::default()
                        .command_pool(command_pool)
                        .level(vk::CommandBufferLevel::PRIMARY)
                        .command_buffer_count(1),
                )?[0];
                let fence = device.create_fence(&vk::FenceCreateInfo::default().flags(vk::FenceCreateFlags::SIGNALED), None)?;
                let acquire_semaphore = device.create_semaphore(&vk::SemaphoreCreateInfo::default(), None)?;
                let sampler = device.create_sampler(
                    &vk::SamplerCreateInfo::default()
                        .mag_filter(vk::Filter::LINEAR)
                        .min_filter(vk::Filter::LINEAR)
                        .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                        .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                        .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE),
                    None,
                )?;
                let module = |bytes: &[u8]| {
                    let code = spirv(bytes);
                    device.create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&code), None)
                };
                let vertex = module(QUAD_VERT)?;
                let yuv_fragment = module(YUV_FRAG)?;
                let external_fragment = module(EXTERNAL_FRAG)?;
                let bindings: Vec<_> = (0..3)
                    .map(|binding| {
                        vk::DescriptorSetLayoutBinding::default()
                            .binding(binding)
                            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                            .descriptor_count(1)
                            .stage_flags(vk::ShaderStageFlags::FRAGMENT)
                    })
                    .collect();
                let yuv_set_layout =
                    device.create_descriptor_set_layout(&vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings), None)?;
                let ranges = [vk::PushConstantRange::default()
                    .stage_flags(vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT)
                    .size(std::mem::size_of::<Params>() as u32)];
                let set_layouts = [yuv_set_layout];
                let yuv_pipeline_layout = device.create_pipeline_layout(
                    &vk::PipelineLayoutCreateInfo::default().set_layouts(&set_layouts).push_constant_ranges(&ranges),
                    None,
                )?;
                let pool_sizes = [vk::DescriptorPoolSize::default().ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER).descriptor_count(128)];
                let descriptor_pool = device.create_descriptor_pool(
                    &vk::DescriptorPoolCreateInfo::default()
                        .flags(vk::DescriptorPoolCreateFlags::FREE_DESCRIPTOR_SET)
                        .max_sets(64)
                        .pool_sizes(&pool_sizes),
                    None,
                )?;
                let yuv_layouts = [yuv_set_layout; 2];
                let yuv_allocated = device.allocate_descriptor_sets(
                    &vk::DescriptorSetAllocateInfo::default().descriptor_pool(descriptor_pool).set_layouts(&yuv_layouts),
                )?;
                let yuv_sets = [yuv_allocated[0], yuv_allocated[1]];
                Ok((
                    command_pool,
                    command_buffer,
                    fence,
                    acquire_semaphore,
                    sampler,
                    vertex,
                    yuv_fragment,
                    external_fragment,
                    yuv_set_layout,
                    yuv_pipeline_layout,
                    descriptor_pool,
                    yuv_sets,
                ))
            })();
            let (
                command_pool,
                command_buffer,
                fence,
                acquire_semaphore,
                sampler,
                vertex,
                yuv_fragment,
                external_fragment,
                yuv_set_layout,
                yuv_pipeline_layout,
                descriptor_pool,
                yuv_sets,
            ) = match built {
                Ok(objects) => objects,
                Err(error) => {
                    device.destroy_device(None);
                    return Err((instance, surface_fn, format!("Vulkan setup failed: {error}")));
                }
            };
            log::info!("renderer ready: {name} (hardware frames: {ahb}, norm16: {norm16})");
            Ok(Self {
                _entry: entry,
                instance,
                surface_fn,
                android_surface_fn,
                physical,
                device,
                swapchain_fn,
                ahb_fn,
                hdr_metadata_fn,
                foreign_queue: foreign,
                display_timing,
                queue,
                queue_family,
                memory,
                command_pool,
                command_buffer,
                fence,
                acquire_semaphore,
                sampler,
                vertex,
                yuv_fragment,
                external_fragment,
                yuv_set_layout,
                yuv_pipeline_layout,
                descriptor_pool,
                yuv_sets,
                norm16,
                smooth_format,
                name,
            })
        }
    }

    unsafe fn create_pipeline(
        &self,
        render_pass: vk::RenderPass,
        layout: vk::PipelineLayout,
        fragment: vk::ShaderModule,
        variant: i32,
    ) -> Result<vk::Pipeline, vk::Result> {
        unsafe {
            let kernel = variant / 2;
            let constants = [kernel, variant % 2, crate::gles::kernel_reach(kernel)];
            let entries = [
                vk::SpecializationMapEntry { constant_id: 0, offset: 0, size: 4 },
                vk::SpecializationMapEntry { constant_id: 1, offset: 4, size: 4 },
                vk::SpecializationMapEntry { constant_id: 2, offset: 8, size: 4 },
            ];
            let bytes = std::slice::from_raw_parts(constants.as_ptr() as *const u8, 12);
            let specialization = vk::SpecializationInfo::default().map_entries(&entries).data(bytes);
            let stages = [
                vk::PipelineShaderStageCreateInfo::default()
                    .stage(vk::ShaderStageFlags::VERTEX)
                    .module(self.vertex)
                    .name(c"main"),
                vk::PipelineShaderStageCreateInfo::default()
                    .stage(vk::ShaderStageFlags::FRAGMENT)
                    .module(fragment)
                    .name(c"main")
                    .specialization_info(&specialization),
            ];
            let vertex_input = vk::PipelineVertexInputStateCreateInfo::default();
            let assembly = vk::PipelineInputAssemblyStateCreateInfo::default().topology(vk::PrimitiveTopology::TRIANGLE_STRIP);
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
            self.device
                .create_graphics_pipelines(vk::PipelineCache::null(), &[info], None)
                .map(|pipelines| pipelines[0])
                .map_err(|(_, error)| error)
        }
    }

    unsafe fn destroy(&mut self) {
        unsafe {
            let device = &self.device;
            let _ = device.device_wait_idle();
            device.destroy_descriptor_pool(self.descriptor_pool, None);
            device.destroy_pipeline_layout(self.yuv_pipeline_layout, None);
            device.destroy_descriptor_set_layout(self.yuv_set_layout, None);
            device.destroy_shader_module(self.vertex, None);
            device.destroy_shader_module(self.yuv_fragment, None);
            device.destroy_shader_module(self.external_fragment, None);
            device.destroy_sampler(self.sampler, None);
            device.destroy_semaphore(self.acquire_semaphore, None);
            device.destroy_fence(self.fence, None);
            device.destroy_command_pool(self.command_pool, None);
            device.destroy_device(None);
            self.instance.destroy_instance(None);
        }
    }
}

struct Target {
    surface: vk::SurfaceKHR,
    swapchain: vk::SwapchainKHR,
    extent: vk::Extent2D,
    views: Vec<vk::ImageView>,
    images: Vec<vk::Image>,
    framebuffers: Vec<vk::Framebuffer>,
    present_semaphores: Vec<vk::Semaphore>,
    render_pass: vk::RenderPass,
    yuv_pipelines: HashMap<i32, vk::Pipeline>,
    smooth_pipelines: HashMap<i32, vk::Pipeline>,
    stale: bool,
    output: Output,
    dither: i32,
    light_metadata: (f32, f32),
    requested: Vec<Output>,
    transform: vk::SurfaceTransformFlagsKHR,
    rotation: i32,
    logical: (u32, u32),
}

fn dither_code(format: vk::Format) -> i32 {
    match format {
        vk::Format::A2B10G10R10_UNORM_PACK32 | vk::Format::A2R10G10B10_UNORM_PACK32 => 2,
        vk::Format::R16G16B16A16_SFLOAT => 0,
        _ => 1,
    }
}

fn dither_levels(code: i32) -> f32 {
    match code {
        2 => 1023.0,
        1 => 255.0,
        _ => 0.0,
    }
}

fn rotation_of(transform: vk::SurfaceTransformFlagsKHR) -> i32 {
    if transform == vk::SurfaceTransformFlagsKHR::ROTATE_90 {
        1
    } else if transform == vk::SurfaceTransformFlagsKHR::ROTATE_180 {
        2
    } else if transform == vk::SurfaceTransformFlagsKHR::ROTATE_270 {
        3
    } else {
        0
    }
}

fn pick_format(formats: &[vk::SurfaceFormatKHR], output: Output) -> Option<vk::SurfaceFormatKHR> {
    let find = |space: vk::ColorSpaceKHR, accepted: &[vk::Format]| {
        accepted
            .iter()
            .find_map(|format| formats.iter().find(|f| f.color_space == space && f.format == *format))
            .copied()
    };
    match output {
        Output::Pq => find(
            vk::ColorSpaceKHR::HDR10_ST2084_EXT,
            &[vk::Format::A2B10G10R10_UNORM_PACK32, vk::Format::A2R10G10B10_UNORM_PACK32],
        ),
        Output::P3 => find(
            vk::ColorSpaceKHR::DISPLAY_P3_NONLINEAR_EXT,
            &[vk::Format::R8G8B8A8_UNORM, vk::Format::B8G8R8A8_UNORM, vk::Format::A2B10G10R10_UNORM_PACK32],
        ),
        Output::P3Linear => None,
        Output::Srgb => find(vk::ColorSpaceKHR::SRGB_NONLINEAR, &[vk::Format::R8G8B8A8_UNORM, vk::Format::B8G8R8A8_UNORM])
            .or_else(|| formats.iter().find(|f| f.color_space == vk::ColorSpaceKHR::SRGB_NONLINEAR).copied())
            .or_else(|| formats.first().copied()),
    }
}

impl Target {
    unsafe fn create(
        core: &Core,
        surface: vk::SurfaceKHR,
        window: &NativeWindow,
        old: vk::SwapchainKHR,
        wanted: &[Output],
    ) -> Result<Self, String> {
        unsafe {
            let capabilities = core
                .surface_fn
                .get_physical_device_surface_capabilities(core.physical, surface)
                .map_err(|e| format!("surface capabilities: {e}"))?;
            let formats = core
                .surface_fn
                .get_physical_device_surface_formats(core.physical, surface)
                .map_err(|e| format!("surface formats: {e}"))?;
            let (output, format) = wanted
                .iter()
                .chain(std::iter::once(&Output::Srgb))
                .find_map(|output| pick_format(&formats, *output).map(|format| (*output, format)))
                .ok_or("no surface formats")?;
            if output != Output::Srgb {
                log::info!("Vulkan swapchain uses {output:?} ({}, {})", format.format.as_raw(), format.color_space.as_raw());
            }
            let logical = if window.width() > 0 && window.height() > 0 {
                vk::Extent2D { width: window.width() as u32, height: window.height() as u32 }
            } else if capabilities.current_extent.width != u32::MAX {
                capabilities.current_extent
            } else {
                vk::Extent2D { width: 1, height: 1 }
            };
            let mut count = (capabilities.min_image_count + 1).max(3);
            if capabilities.max_image_count > 0 {
                count = count.min(capabilities.max_image_count);
            }
            let current = capabilities.current_transform;
            let transform = if rotation_of(current) != 0 && capabilities.supported_transforms.contains(current) {
                current
            } else if capabilities.supported_transforms.contains(vk::SurfaceTransformFlagsKHR::IDENTITY) {
                vk::SurfaceTransformFlagsKHR::IDENTITY
            } else {
                current
            };
            let rotation = rotation_of(transform);
            let extent = if rotation % 2 == 1 {
                vk::Extent2D { width: logical.height, height: logical.width }
            } else {
                logical
            };
            let alpha = [
                vk::CompositeAlphaFlagsKHR::OPAQUE,
                vk::CompositeAlphaFlagsKHR::INHERIT,
                vk::CompositeAlphaFlagsKHR::PRE_MULTIPLIED,
                vk::CompositeAlphaFlagsKHR::POST_MULTIPLIED,
            ]
            .into_iter()
            .find(|flag| capabilities.supported_composite_alpha.contains(*flag))
            .unwrap_or(vk::CompositeAlphaFlagsKHR::INHERIT);
            let swapchain = core
                .swapchain_fn
                .create_swapchain(
                    &vk::SwapchainCreateInfoKHR::default()
                        .surface(surface)
                        .min_image_count(count)
                        .image_format(format.format)
                        .image_color_space(format.color_space)
                        .image_extent(extent)
                        .image_array_layers(1)
                        .image_usage(vk::ImageUsageFlags::COLOR_ATTACHMENT)
                        .image_sharing_mode(vk::SharingMode::EXCLUSIVE)
                        .pre_transform(transform)
                        .composite_alpha(alpha)
                        .present_mode(vk::PresentModeKHR::FIFO)
                        .clipped(true)
                        .old_swapchain(old),
                    None,
                )
                .map_err(|e| format!("vkCreateSwapchainKHR failed: {e}"))?;
            let device = &core.device;
            let attachments = [vk::AttachmentDescription::default()
                .format(format.format)
                .samples(vk::SampleCountFlags::TYPE_1)
                .load_op(vk::AttachmentLoadOp::CLEAR)
                .store_op(vk::AttachmentStoreOp::STORE)
                .stencil_load_op(vk::AttachmentLoadOp::DONT_CARE)
                .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
                .initial_layout(vk::ImageLayout::UNDEFINED)
                .final_layout(vk::ImageLayout::PRESENT_SRC_KHR)];
            let color = [vk::AttachmentReference::default().attachment(0).layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL)];
            let subpasses = [vk::SubpassDescription::default()
                .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)
                .color_attachments(&color)];
            let dependencies = [vk::SubpassDependency::default()
                .src_subpass(vk::SUBPASS_EXTERNAL)
                .dst_subpass(0)
                .src_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
                .dst_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
                .dst_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)];
            let render_pass = device
                .create_render_pass(
                    &vk::RenderPassCreateInfo::default()
                        .attachments(&attachments)
                        .subpasses(&subpasses)
                        .dependencies(&dependencies),
                    None,
                )
                .map_err(|e| format!("render pass: {e}"))?;
            let images = core.swapchain_fn.get_swapchain_images(swapchain).map_err(|e| format!("swapchain images: {e}"))?;
            let mut target = Target {
                surface,
                swapchain,
                extent,
                views: Vec::new(),
                images: images.clone(),
                framebuffers: Vec::new(),
                present_semaphores: Vec::new(),
                render_pass,
                yuv_pipelines: HashMap::new(),
                smooth_pipelines: HashMap::new(),
                stale: false,
                output,
                dither: dither_code(format.format),
                light_metadata: (0.0, 0.0),
                requested: wanted.to_vec(),
                transform,
                rotation,
                logical: (logical.width, logical.height),
            };
            for image in images {
                let view = device
                    .create_image_view(
                        &vk::ImageViewCreateInfo::default()
                            .image(image)
                            .view_type(vk::ImageViewType::TYPE_2D)
                            .format(format.format)
                            .subresource_range(color_range()),
                        None,
                    )
                    .map_err(|e| format!("swapchain view: {e}"))?;
                target.views.push(view);
                let views = [view];
                let framebuffer = device
                    .create_framebuffer(
                        &vk::FramebufferCreateInfo::default()
                            .render_pass(render_pass)
                            .attachments(&views)
                            .width(extent.width)
                            .height(extent.height)
                            .layers(1),
                        None,
                    )
                    .map_err(|e| format!("framebuffer: {e}"))?;
                target.framebuffers.push(framebuffer);
                target
                    .present_semaphores
                    .push(device.create_semaphore(&vk::SemaphoreCreateInfo::default(), None).map_err(|e| e.to_string())?);
            }
            Ok(target)
        }
    }

    unsafe fn destroy_swapchain_objects(&mut self, core: &Core) {
        unsafe {
            let device = &core.device;
            for framebuffer in self.framebuffers.drain(..) {
                device.destroy_framebuffer(framebuffer, None);
            }
            for view in self.views.drain(..) {
                device.destroy_image_view(view, None);
            }
            for semaphore in self.present_semaphores.drain(..) {
                device.destroy_semaphore(semaphore, None);
            }
            for (_, pipeline) in self.yuv_pipelines.drain() {
                device.destroy_pipeline(pipeline, None);
            }
            for (_, pipeline) in self.smooth_pipelines.drain() {
                device.destroy_pipeline(pipeline, None);
            }
            device.destroy_render_pass(self.render_pass, None);
            self.images.clear();
        }
    }
}

fn color_range() -> vk::ImageSubresourceRange {
    vk::ImageSubresourceRange::default()
        .aspect_mask(vk::ImageAspectFlags::COLOR)
        .base_mip_level(0)
        .level_count(1)
        .base_array_layer(0)
        .layer_count(1)
}

struct PlaneImage {
    image: vk::Image,
    memory: vk::DeviceMemory,
    view: vk::ImageView,
    width: u32,
    height: u32,
    format: vk::Format,
    initialized: bool,
}

struct View {
    width: i32,
    height: i32,
    output: i32,
    transform: ViewTransform,
}

struct Prepared {
    pipeline: vk::Pipeline,
    layout: vk::PipelineLayout,
    set: vk::DescriptorSet,
    params: Params,
}

struct Staging {
    buffer: vk::Buffer,
    memory: vk::DeviceMemory,
    mapped: *mut u8,
    size: u64,
}

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
struct ExternalKey {
    external_format: u64,
    format: i32,
    model: i32,
    range: i32,
}

struct ExternalPipeline {
    conversion: vk::SamplerYcbcrConversion,
    sampler: vk::Sampler,
    set_layout: vk::DescriptorSetLayout,
    pipeline_layout: vk::PipelineLayout,
    sets: [vk::DescriptorSet; 2],
    pipelines: HashMap<i32, vk::Pipeline>,
}

struct Imported {
    image: vk::Image,
    memory: vk::DeviceMemory,
    view: vk::ImageView,
}

struct CachedImport {
    buffer: usize,
    key: ExternalKey,
    imported: Imported,
    used: u64,
}

const IMPORT_CACHE: usize = 10;

pub struct VulkanPresenter {
    core: Option<Core>,
    target: Option<Target>,
    window: Option<NativeWindow>,
    planes: [[Option<PlaneImage>; 3]; 2],
    staging: Option<Staging>,
    staging_cursor: u64,
    retired_staging: Vec<Staging>,
    external: HashMap<ExternalKey, ExternalPipeline>,
    retired: Vec<Imported>,
    imports: Vec<CachedImport>,
    import_clock: u64,
    scaler: Scaler,
    failed: bool,
    transform: ViewTransform,
    scaler_mode: ScalerMode,
    downscaler_mode: DownscalerMode,
    antiring: f32,
    quality_cap: f32,
    active_scaling: ActiveScaling,
    target_ns: Option<i64>,
    present_id: u32,
    options: DisplayOptions,
    wanted: Vec<Output>,
    smooth: Option<smooth::Smooth>,
    smooth_failed: bool,
    meter: Option<meter::Meter>,
    meter_failed: bool,
    motion: MotionConfig,
    submitted: u64,
    completed: u64,
}

unsafe impl Send for VulkanPresenter {}

impl Default for VulkanPresenter {
    fn default() -> Self {
        Self::new()
    }
}

fn ycbcr_model(color: &ColorInfo, height: i32) -> vk::SamplerYcbcrModelConversion {
    match color.space {
        sys::AVColorSpace::AVCOL_SPC_BT709 => vk::SamplerYcbcrModelConversion::YCBCR_709,
        sys::AVColorSpace::AVCOL_SPC_BT470BG | sys::AVColorSpace::AVCOL_SPC_SMPTE170M => vk::SamplerYcbcrModelConversion::YCBCR_601,
        sys::AVColorSpace::AVCOL_SPC_BT2020_NCL | sys::AVColorSpace::AVCOL_SPC_BT2020_CL => vk::SamplerYcbcrModelConversion::YCBCR_2020,
        _ if color.transfer == sys::AVColorTransferCharacteristic::AVCOL_TRC_SMPTE2084
            || color.transfer == sys::AVColorTransferCharacteristic::AVCOL_TRC_ARIB_STD_B67 =>
        {
            vk::SamplerYcbcrModelConversion::YCBCR_2020
        }
        _ if height >= 720 => vk::SamplerYcbcrModelConversion::YCBCR_709,
        _ => vk::SamplerYcbcrModelConversion::YCBCR_601,
    }
}

impl VulkanPresenter {
    pub fn new() -> Self {
        Self {
            core: None,
            target: None,
            window: None,
            planes: [[None, None, None], [None, None, None]],
            staging: None,
            staging_cursor: 0,
            retired_staging: Vec::new(),
            external: HashMap::new(),
            retired: Vec::new(),
            imports: Vec::new(),
            import_clock: 0,
            scaler: Scaler::default(),
            failed: false,
            transform: ViewTransform::default(),
            scaler_mode: ScalerMode::BILINEAR,
            downscaler_mode: DownscalerMode::BILINEAR,
            antiring: 0.6,
            quality_cap: f32::INFINITY,
            active_scaling: ActiveScaling::default(),
            target_ns: None,
            present_id: 0,
            options: DisplayOptions::default(),
            wanted: vec![Output::Srgb],
            smooth: None,
            smooth_failed: false,
            meter: None,
            meter_failed: false,
            motion: MotionConfig::default(),
            submitted: 0,
            completed: 0,
        }
    }

    fn invalidate_slots(&mut self) {
        if let Some(smooth) = self.smooth.as_mut() {
            smooth.invalidate();
        }
    }

    pub fn set_display_options(&mut self, options: DisplayOptions) {
        if options != self.options {
            self.invalidate_slots();
        }
        self.options = options;
    }

    pub fn output(&self) -> i32 {
        self.target.as_ref().map(|target| target.output as i32).unwrap_or(0)
    }

    pub fn set_view_transform(&mut self, transform: ViewTransform) {
        if transform != self.transform {
            self.invalidate_slots();
        }
        self.transform = transform;
    }

    pub fn set_scaler_mode(&mut self, mode: ScalerMode) {
        if mode != self.scaler_mode {
            self.invalidate_slots();
        }
        self.scaler_mode = mode;
    }

    pub fn set_downscaler_mode(&mut self, mode: DownscalerMode) {
        if mode != self.downscaler_mode {
            self.invalidate_slots();
        }
        self.downscaler_mode = mode;
    }

    pub fn set_antiring(&mut self, strength: f32) {
        if strength != self.antiring {
            self.invalidate_slots();
        }
        self.antiring = strength;
    }

    pub fn set_quality_cap(&mut self, cap: f32) {
        if cap != self.quality_cap {
            self.invalidate_slots();
        }
        self.quality_cap = cap;
    }

    pub fn active_scaling(&self) -> ActiveScaling {
        self.active_scaling
    }

    pub fn present_paced(&mut self, frame: &VideoFrame, delay_us: i64) -> bool {
        if delay_us > 0 && self.core.as_ref().is_some_and(|core| core.display_timing) {
            self.target_ns = Some(monotonic_ns() + delay_us * 1_000);
        }
        let presented = self.present(frame);
        self.target_ns = None;
        presented
    }

    pub fn failed(&self) -> bool {
        self.failed
    }

    pub fn name(&self) -> Option<String> {
        self.core.as_ref().map(|core| core.name.clone())
    }

    pub fn has_window(&self) -> bool {
        self.target.is_some()
    }

    pub fn attach(&mut self, window: NativeWindow) -> Result<(), String> {
        unsafe {
            let surface = match self.core.as_ref() {
                None => {
                    let (core, surface) = Core::new(&window)?;
                    self.core = Some(core);
                    surface
                }
                Some(core) => core
                    .android_surface_fn
                    .create_android_surface(&vk::AndroidSurfaceCreateInfoKHR::default().window(window.as_ptr() as *mut _), None)
                    .map_err(|e| format!("vkCreateAndroidSurfaceKHR failed: {e}"))?,
            };
            let core = self.core.as_ref().expect("core");
            let supported = core
                .surface_fn
                .get_physical_device_surface_support(core.physical, core.queue_family, surface)
                .unwrap_or(false);
            if !supported {
                core.surface_fn.destroy_surface(surface, None);
                return Err("the Vulkan queue cannot present to this surface".into());
            }
            match Target::create(core, surface, &window, vk::SwapchainKHR::null(), &self.wanted) {
                Ok(target) => {
                    self.target = Some(target);
                    self.window = Some(window);
                    Ok(())
                }
                Err(error) => {
                    core.surface_fn.destroy_surface(surface, None);
                    Err(error)
                }
            }
        }
    }

    pub fn detach(&mut self) {
        unsafe {
            if let Some(core) = self.core.as_ref() {
                let _ = core.device.device_wait_idle();
                let cached: Vec<Imported> = self.imports.drain(..).map(|entry| entry.imported).collect();
                self.retired.extend(cached);
                self.release_retired();
                if let Some(mut target) = self.target.take() {
                    let core = self.core.as_ref().expect("core");
                    target.destroy_swapchain_objects(core);
                    core.swapchain_fn.destroy_swapchain(target.swapchain, None);
                    core.surface_fn.destroy_surface(target.surface, None);
                }
                let core = self.core.as_ref().expect("core");
                for external in self.external.values_mut() {
                    for (_, pipeline) in external.pipelines.drain() {
                        core.device.destroy_pipeline(pipeline, None);
                    }
                }
                if let Some(mut smooth) = self.smooth.take() {
                    smooth.destroy(core);
                }
            }
        }
        self.window = None;
    }

    fn recreate(&mut self) -> Result<(), String> {
        let (Some(core), Some(window)) = (self.core.as_ref(), self.window.as_ref()) else {
            return Err("no window".into());
        };
        unsafe {
            let _ = core.device.device_wait_idle();
            let mut old = self.target.take().ok_or("no target")?;
            old.destroy_swapchain_objects(core);
            if let Some(smooth) = self.smooth.as_mut() {
                smooth.invalidate();
            }
            for external in self.external.values_mut() {
                for (_, pipeline) in external.pipelines.drain() {
                    core.device.destroy_pipeline(pipeline, None);
                }
            }
            let result = Target::create(core, old.surface, window, old.swapchain, &self.wanted);
            core.swapchain_fn.destroy_swapchain(old.swapchain, None);
            match result {
                Ok(target) => {
                    self.target = Some(target);
                    Ok(())
                }
                Err(error) => {
                    core.surface_fn.destroy_surface(old.surface, None);
                    Err(error)
                }
            }
        }
    }

    unsafe fn release_retired(&mut self) {
        let Some(core) = self.core.as_ref() else {
            return;
        };
        unsafe {
            for imported in self.retired.drain(..) {
                core.device.destroy_image_view(imported.view, None);
                core.device.destroy_image(imported.image, None);
                core.device.free_memory(imported.memory, None);
            }
            for staging in self.retired_staging.drain(..) {
                core.device.unmap_memory(staging.memory);
                core.device.destroy_buffer(staging.buffer, None);
                core.device.free_memory(staging.memory, None);
            }
        }
    }

    unsafe fn reserve_staging(&mut self, bytes: u64) -> Result<(vk::Buffer, *mut u8, u64), vk::Result> {
        unsafe {
            let start = (self.staging_cursor + 15) & !15;
            if let Some(staging) = &self.staging {
                if start + bytes <= staging.size {
                    self.staging_cursor = start + bytes;
                    return Ok((staging.buffer, staging.mapped, start));
                }
            }
            if let Some(old) = self.staging.take() {
                self.retired_staging.push(old);
            }
            let core = self.core.as_ref().expect("core");
            let size = bytes.next_power_of_two().max(1 << 20);
            let buffer = core.device.create_buffer(
                &vk::BufferCreateInfo::default()
                    .size(size)
                    .usage(vk::BufferUsageFlags::TRANSFER_SRC)
                    .sharing_mode(vk::SharingMode::EXCLUSIVE),
                None,
            )?;
            let requirements = core.device.get_buffer_memory_requirements(buffer);
            let Some(type_index) = find_memory(
                &core.memory,
                requirements.memory_type_bits,
                vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
            ) else {
                core.device.destroy_buffer(buffer, None);
                return Err(vk::Result::ERROR_OUT_OF_DEVICE_MEMORY);
            };
            let memory = core.device.allocate_memory(
                &vk::MemoryAllocateInfo::default().allocation_size(requirements.size).memory_type_index(type_index),
                None,
            )?;
            core.device.bind_buffer_memory(buffer, memory, 0)?;
            let mapped = core.device.map_memory(memory, 0, vk::WHOLE_SIZE, vk::MemoryMapFlags::empty())? as *mut u8;
            self.staging = Some(Staging { buffer, memory, mapped, size });
            self.staging_cursor = bytes;
            Ok((buffer, mapped, 0))
        }
    }

    unsafe fn ensure_plane(&mut self, lane: usize, index: usize, width: u32, height: u32, format: vk::Format) -> Result<(), vk::Result> {
        unsafe {
            if let Some(plane) = &self.planes[lane][index] {
                if plane.width == width && plane.height == height && plane.format == format {
                    return Ok(());
                }
            }
            let core = self.core.as_ref().expect("core");
            if let Some(old) = self.planes[lane][index].take() {
                core.device.destroy_image_view(old.view, None);
                core.device.destroy_image(old.image, None);
                core.device.free_memory(old.memory, None);
            }
            let image = core.device.create_image(
                &vk::ImageCreateInfo::default()
                    .image_type(vk::ImageType::TYPE_2D)
                    .format(format)
                    .extent(vk::Extent3D { width, height, depth: 1 })
                    .mip_levels(1)
                    .array_layers(1)
                    .samples(vk::SampleCountFlags::TYPE_1)
                    .tiling(vk::ImageTiling::OPTIMAL)
                    .usage(vk::ImageUsageFlags::SAMPLED | vk::ImageUsageFlags::TRANSFER_DST)
                    .sharing_mode(vk::SharingMode::EXCLUSIVE)
                    .initial_layout(vk::ImageLayout::UNDEFINED),
                None,
            )?;
            let requirements = core.device.get_image_memory_requirements(image);
            let Some(type_index) =
                find_memory(&core.memory, requirements.memory_type_bits, vk::MemoryPropertyFlags::DEVICE_LOCAL)
                    .or_else(|| find_memory(&core.memory, requirements.memory_type_bits, vk::MemoryPropertyFlags::empty()))
            else {
                core.device.destroy_image(image, None);
                return Err(vk::Result::ERROR_OUT_OF_DEVICE_MEMORY);
            };
            let memory = core.device.allocate_memory(
                &vk::MemoryAllocateInfo::default().allocation_size(requirements.size).memory_type_index(type_index),
                None,
            )?;
            core.device.bind_image_memory(image, memory, 0)?;
            let view = core.device.create_image_view(
                &vk::ImageViewCreateInfo::default()
                    .image(image)
                    .view_type(vk::ImageViewType::TYPE_2D)
                    .format(format)
                    .subresource_range(color_range()),
                None,
            )?;
            self.planes[lane][index] = Some(PlaneImage { image, memory, view, width, height, format, initialized: false });
            Ok(())
        }
    }

    unsafe fn external_pipeline(&mut self, key: ExternalKey, props: &vk::AndroidHardwareBufferFormatPropertiesANDROID<'_>) -> Result<(), vk::Result> {
        unsafe {
            let core = self.core.as_ref().expect("core");
            if self.external.contains_key(&key) {
                return Ok(());
            }
            let linear = props
                .format_features
                .contains(vk::FormatFeatureFlags::SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER);
            let filter = if linear { vk::Filter::LINEAR } else { vk::Filter::NEAREST };
            let mut external_format = vk::ExternalFormatANDROID::default().external_format(key.external_format);
            let mut info = vk::SamplerYcbcrConversionCreateInfo::default()
                .format(props.format)
                .ycbcr_model(vk::SamplerYcbcrModelConversion::from_raw(key.model))
                .ycbcr_range(vk::SamplerYcbcrRange::from_raw(key.range))
                .components(props.sampler_ycbcr_conversion_components)
                .x_chroma_offset(props.suggested_x_chroma_offset)
                .y_chroma_offset(props.suggested_y_chroma_offset)
                .chroma_filter(filter)
                .force_explicit_reconstruction(false);
            if props.format == vk::Format::UNDEFINED {
                info = info.push_next(&mut external_format);
            }
            let conversion = core.device.create_sampler_ycbcr_conversion(&info, None)?;
            let mut conversion_info = vk::SamplerYcbcrConversionInfo::default().conversion(conversion);
            let sampler = core.device.create_sampler(
                &vk::SamplerCreateInfo::default()
                    .mag_filter(filter)
                    .min_filter(filter)
                    .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                    .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                    .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                    .push_next(&mut conversion_info),
                None,
            )?;
            let samplers = [sampler];
            let bindings = [vk::DescriptorSetLayoutBinding::default()
                .binding(0)
                .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::FRAGMENT)
                .immutable_samplers(&samplers)];
            let set_layout =
                core.device.create_descriptor_set_layout(&vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings), None)?;
            let set_layouts = [set_layout];
            let ranges = [vk::PushConstantRange::default()
                .stage_flags(vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT)
                .size(std::mem::size_of::<Params>() as u32)];
            let pipeline_layout = core.device.create_pipeline_layout(
                &vk::PipelineLayoutCreateInfo::default().set_layouts(&set_layouts).push_constant_ranges(&ranges),
                None,
            )?;
            let pair_layouts = [set_layout; 2];
            let allocated = core.device.allocate_descriptor_sets(
                &vk::DescriptorSetAllocateInfo::default().descriptor_pool(core.descriptor_pool).set_layouts(&pair_layouts),
            )?;
            let sets = [allocated[0], allocated[1]];
            self.external.insert(key, ExternalPipeline { conversion, sampler, set_layout, pipeline_layout, sets, pipelines: HashMap::new() });
            Ok(())
        }
    }

    unsafe fn yuv_variant(&mut self, variant: i32, offscreen: bool) -> Result<vk::Pipeline, String> {
        unsafe {
            let core = self.core.as_ref().ok_or("no device")?;
            if offscreen {
                let smooth = self.smooth.as_mut().ok_or("no smooth state")?;
                if let Some(pipeline) = smooth.yuv.get(&variant) {
                    return Ok(*pipeline);
                }
                let pipeline = core
                    .create_pipeline(smooth.render_pass, core.yuv_pipeline_layout, core.yuv_fragment, variant)
                    .map_err(|e| format!("pipeline: {e}"))?;
                smooth.yuv.insert(variant, pipeline);
                return Ok(pipeline);
            }
            let target = self.target.as_mut().ok_or("no target")?;
            if let Some(pipeline) = target.yuv_pipelines.get(&variant) {
                return Ok(*pipeline);
            }
            let pipeline = core
                .create_pipeline(target.render_pass, core.yuv_pipeline_layout, core.yuv_fragment, variant)
                .map_err(|e| format!("pipeline: {e}"))?;
            target.yuv_pipelines.insert(variant, pipeline);
            Ok(pipeline)
        }
    }

    unsafe fn external_variant(&mut self, key: ExternalKey, variant: i32, offscreen: bool) -> Result<vk::Pipeline, String> {
        unsafe {
            let core = self.core.as_ref().ok_or("no device")?;
            let external = self.external.get_mut(&key).ok_or("no external pipeline")?;
            if offscreen {
                let smooth = self.smooth.as_mut().ok_or("no smooth state")?;
                if let Some(pipeline) = smooth.external.get(&(key, variant)) {
                    return Ok(*pipeline);
                }
                let pipeline = core
                    .create_pipeline(smooth.render_pass, external.pipeline_layout, core.external_fragment, variant)
                    .map_err(|e| format!("external pipeline: {e}"))?;
                smooth.external.insert((key, variant), pipeline);
                return Ok(pipeline);
            }
            let target = self.target.as_ref().ok_or("no target")?;
            if let Some(pipeline) = external.pipelines.get(&variant) {
                return Ok(*pipeline);
            }
            let pipeline = core
                .create_pipeline(target.render_pass, external.pipeline_layout, core.external_fragment, variant)
                .map_err(|e| format!("external pipeline: {e}"))?;
            external.pipelines.insert(variant, pipeline);
            Ok(pipeline)
        }
    }

    unsafe fn import(
        &mut self,
        frame: &dyn HardwareFrame,
        color: &ColorInfo,
    ) -> Result<(ExternalKey, vk::ImageView, vk::ImageMemoryBarrier<'static>), String> {
        unsafe {
            let core = self.core.as_ref().expect("core");
            let ahb_fn = core.ahb_fn.as_ref().ok_or("hardware buffers are not supported by this Vulkan driver")?;
            let buffer = frame.hardware_buffer();
            let mut format_props = vk::AndroidHardwareBufferFormatPropertiesANDROID::default();
            let mut props = vk::AndroidHardwareBufferPropertiesANDROID::default().push_next(&mut format_props);
            ahb_fn
                .get_android_hardware_buffer_properties(buffer as *const _, &mut props)
                .map_err(|e| format!("buffer properties: {e}"))?;
            let allocation_size = props.allocation_size;
            let memory_bits = props.memory_type_bits;
            let format_props = format_props;
            let model = ycbcr_model(color, frame.height());
            let range = if color.range == sys::AVColorRange::AVCOL_RANGE_JPEG {
                vk::SamplerYcbcrRange::ITU_FULL
            } else {
                vk::SamplerYcbcrRange::ITU_NARROW
            };
            let key = ExternalKey {
                external_format: format_props.external_format,
                format: format_props.format.as_raw(),
                model: model.as_raw(),
                range: range.as_raw(),
            };
            self.external_pipeline(key, &format_props).map_err(|e| format!("external pipeline: {e}"))?;
            let core = self.core.as_ref().expect("core");
            let source_family = if core.foreign_queue { vk::QUEUE_FAMILY_FOREIGN_EXT } else { vk::QUEUE_FAMILY_EXTERNAL };
            let queue_family = core.queue_family;
            let acquire = move |image: vk::Image| {
                vk::ImageMemoryBarrier::default()
                    .src_access_mask(vk::AccessFlags::empty())
                    .dst_access_mask(vk::AccessFlags::SHADER_READ)
                    .old_layout(vk::ImageLayout::UNDEFINED)
                    .new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
                    .src_queue_family_index(source_family)
                    .dst_queue_family_index(queue_family)
                    .image(image)
                    .subresource_range(color_range())
            };
            self.import_clock += 1;
            let clock = self.import_clock;
            let address = buffer as usize;
            if let Some(entry) = self.imports.iter_mut().find(|entry| entry.buffer == address && entry.key == key) {
                entry.used = clock;
                return Ok((key, entry.imported.view, acquire(entry.imported.image)));
            }
            let conversion = self.external[&key].conversion;
            let (width, height) = frame.buffer_size();
            let mut external_format = vk::ExternalFormatANDROID::default().external_format(format_props.external_format);
            let mut external_memory = vk::ExternalMemoryImageCreateInfo::default()
                .handle_types(vk::ExternalMemoryHandleTypeFlags::ANDROID_HARDWARE_BUFFER_ANDROID);
            let mut image_info = vk::ImageCreateInfo::default()
                .image_type(vk::ImageType::TYPE_2D)
                .format(format_props.format)
                .extent(vk::Extent3D { width: width.max(1) as u32, height: height.max(1) as u32, depth: 1 })
                .mip_levels(1)
                .array_layers(1)
                .samples(vk::SampleCountFlags::TYPE_1)
                .tiling(vk::ImageTiling::OPTIMAL)
                .usage(vk::ImageUsageFlags::SAMPLED)
                .sharing_mode(vk::SharingMode::EXCLUSIVE)
                .initial_layout(vk::ImageLayout::UNDEFINED)
                .push_next(&mut external_memory);
            if format_props.format == vk::Format::UNDEFINED {
                image_info = image_info.push_next(&mut external_format);
            }
            let image = core.device.create_image(&image_info, None).map_err(|e| format!("import image: {e}"))?;
            let type_index = memory_bits.trailing_zeros();
            let mut import = vk::ImportAndroidHardwareBufferInfoANDROID::default().buffer(buffer as *mut _);
            let mut dedicated = vk::MemoryDedicatedAllocateInfo::default().image(image);
            let memory = match core.device.allocate_memory(
                &vk::MemoryAllocateInfo::default()
                    .allocation_size(allocation_size)
                    .memory_type_index(type_index)
                    .push_next(&mut import)
                    .push_next(&mut dedicated),
                None,
            ) {
                Ok(memory) => memory,
                Err(error) => {
                    core.device.destroy_image(image, None);
                    return Err(format!("import memory: {error}"));
                }
            };
            if let Err(error) = core.device.bind_image_memory(image, memory, 0) {
                core.device.destroy_image(image, None);
                core.device.free_memory(memory, None);
                return Err(format!("bind import: {error}"));
            }
            let mut conversion_info = vk::SamplerYcbcrConversionInfo::default().conversion(conversion);
            let view = match core.device.create_image_view(
                &vk::ImageViewCreateInfo::default()
                    .image(image)
                    .view_type(vk::ImageViewType::TYPE_2D)
                    .format(format_props.format)
                    .subresource_range(color_range())
                    .push_next(&mut conversion_info),
                None,
            ) {
                Ok(view) => view,
                Err(error) => {
                    core.device.destroy_image(image, None);
                    core.device.free_memory(memory, None);
                    return Err(format!("import view: {error}"));
                }
            };
            if let Some(stale) = self.imports.iter().position(|entry| entry.buffer == address) {
                let entry = self.imports.swap_remove(stale);
                self.retired.push(entry.imported);
            }
            if self.imports.len() >= IMPORT_CACHE {
                if let Some(oldest) = self.imports.iter().enumerate().min_by_key(|(_, entry)| entry.used).map(|(index, _)| index) {
                    let entry = self.imports.swap_remove(oldest);
                    self.retired.push(entry.imported);
                }
            }
            self.imports.push(CachedImport { buffer: address, key, imported: Imported { image, memory, view }, used: clock });
            Ok((key, view, acquire(image)))
        }
    }

    unsafe fn record_software(
        &mut self,
        video: &VideoFrame,
        source: &Frame,
        lane: usize,
        params: &mut Params,
        hlg_nits: f32,
    ) -> Result<(), String> {
        unsafe {
            let norm16 = self.core.as_ref().expect("core").norm16;
            let mut format = source.pixel_format();
            let mut plan = plan_for(format, norm16);
            let converted;
            let mut frame = source;
            if plan.is_none() || (0..3).any(|i| frame.plane(i).1 < 0) {
                let target = conversion_target(format, norm16);
                converted = self.scaler.convert(source, target).map_err(|e| e.to_string())?;
                frame = &converted;
                format = target;
                plan = plan_for(format, norm16);
            }
            let plan: Plan = plan.ok_or("unsupported pixel format")?;
            let planes = match plan.layout {
                Layout::Planar => 3,
                Layout::SemiPlanar => 2,
                Layout::Rgba | Layout::Gray => 1,
            };
            let mut uploads = Vec::with_capacity(planes);
            let mut total = 0u64;
            for index in 0..planes {
                let (width, height) = if index == 0 || plan.layout == Layout::Rgba {
                    (frame.width(), frame.height())
                } else {
                    (-((-frame.width()) >> plan.chroma_w), -((-frame.height()) >> plan.chroma_h))
                };
                let (format, texel) = match (plan.layout, index, plan.wide) {
                    (Layout::Rgba, _, _) => (vk::Format::R8G8B8A8_UNORM, 4),
                    (Layout::SemiPlanar, 1, false) => (vk::Format::R8G8_UNORM, 2),
                    (Layout::SemiPlanar, 1, true) => (vk::Format::R16G16_UNORM, 4),
                    (_, _, false) => (vk::Format::R8_UNORM, 1),
                    (_, _, true) => (vk::Format::R16_UNORM, 2),
                };
                let (data, linesize) = frame.plane(index);
                if data.is_null() || linesize <= 0 || linesize % texel != 0 || width <= 0 || height <= 0 {
                    return Err("frame plane is not uploadable".into());
                }
                let bytes = linesize as u64 * (height as u64 - 1) + width as u64 * texel as u64;
                let offset = (total + 15) & !15;
                total = offset + bytes;
                uploads.push((index, width as u32, height as u32, format, linesize / texel, data, bytes, offset));
            }
            let (staging_buffer, staging_mapped, staging_base) = self.reserve_staging(total).map_err(|e| format!("staging: {e}"))?;
            for &(index, width, height, format, ..) in &uploads {
                self.ensure_plane(lane, index, width, height, format).map_err(|e| format!("plane image: {e}"))?;
            }
            let core = self.core.as_ref().expect("core");
            let command = core.command_buffer;
            for &(index, width, height, _, row_length, data, bytes, offset) in &uploads {
                let offset = staging_base + offset;
                ptr::copy_nonoverlapping(data, staging_mapped.add(offset as usize), bytes as usize);
                let plane = self.planes[lane][index].as_mut().expect("plane");
                let to_transfer = vk::ImageMemoryBarrier::default()
                    .src_access_mask(if plane.initialized { vk::AccessFlags::SHADER_READ } else { vk::AccessFlags::empty() })
                    .dst_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                    .old_layout(vk::ImageLayout::UNDEFINED)
                    .new_layout(vk::ImageLayout::TRANSFER_DST_OPTIMAL)
                    .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                    .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                    .image(plane.image)
                    .subresource_range(color_range());
                core.device.cmd_pipeline_barrier(
                    command,
                    vk::PipelineStageFlags::FRAGMENT_SHADER | vk::PipelineStageFlags::TOP_OF_PIPE,
                    vk::PipelineStageFlags::TRANSFER,
                    vk::DependencyFlags::empty(),
                    &[],
                    &[],
                    &[to_transfer],
                );
                let region = vk::BufferImageCopy::default()
                    .buffer_offset(offset)
                    .buffer_row_length(row_length as u32)
                    .buffer_image_height(height)
                    .image_subresource(
                        vk::ImageSubresourceLayers::default()
                            .aspect_mask(vk::ImageAspectFlags::COLOR)
                            .mip_level(0)
                            .base_array_layer(0)
                            .layer_count(1),
                    )
                    .image_extent(vk::Extent3D { width, height, depth: 1 });
                core.device.cmd_copy_buffer_to_image(
                    command,
                    staging_buffer,
                    plane.image,
                    vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                    &[region],
                );
                let to_shader = vk::ImageMemoryBarrier::default()
                    .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                    .dst_access_mask(vk::AccessFlags::SHADER_READ)
                    .old_layout(vk::ImageLayout::TRANSFER_DST_OPTIMAL)
                    .new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
                    .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                    .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                    .image(plane.image)
                    .subresource_range(color_range());
                core.device.cmd_pipeline_barrier(
                    command,
                    vk::PipelineStageFlags::TRANSFER,
                    vk::PipelineStageFlags::FRAGMENT_SHADER,
                    vk::DependencyFlags::empty(),
                    &[],
                    &[],
                    &[to_shader],
                );
                plane.initialized = true;
            }
            let first = self.planes[lane][0].as_ref().expect("plane 0").view;
            let views: Vec<vk::ImageView> = (0..3)
                .map(|i| if i < planes { self.planes[lane][i].as_ref().map(|p| p.view).unwrap_or(first) } else { first })
                .collect();
            let infos: Vec<[vk::DescriptorImageInfo; 1]> = views
                .iter()
                .map(|view| {
                    [vk::DescriptorImageInfo::default()
                        .sampler(core.sampler)
                        .image_view(*view)
                        .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)]
                })
                .collect();
            let writes: Vec<vk::WriteDescriptorSet> = infos
                .iter()
                .enumerate()
                .map(|(binding, info)| {
                    vk::WriteDescriptorSet::default()
                        .dst_set(core.yuv_sets[lane])
                        .dst_binding(binding as u32)
                        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                        .image_info(info)
                })
                .collect();
            core.device.update_descriptor_sets(&writes, &[]);

            let color = video.color;
            let transform = yuv_transform(color.space, color.range, plan.depth, frame.height());
            let transfer = transfer_of(color.transfer);
            let container_max = if plan.wide { 65535.0 } else { 255.0 };
            let sample_scale = container_max / ((1u32 << plan.shift) as f32 * ((1u32 << plan.depth) - 1) as f32);
            let m = transform.matrix;
            params.matrix0 = [m[0], m[1], m[2], 0.0];
            params.matrix1 = [m[3], m[4], m[5], 0.0];
            params.matrix2 = [m[6], m[7], m[8], 0.0];
            params.offset = [transform.offset[0], transform.offset[1], transform.offset[2], sample_scale];
            params.flags = (plan.swap_uv as i32) | ((plan.layout as i32) << 10) | ((transfer as i32) << 12) | ((is_bt2020(&color) as i32) << 14);
            params.peak = content_peak(Some(frame), &color, hlg_nits) / SDR_WHITE_NITS;
            params.crop = [0.0, 0.0, 1.0, 1.0];
            Ok(())
        }
    }

    fn refresh_target(&mut self, video: &VideoFrame) -> Result<(), String> {
        let window_size = self.window.as_ref().map(|w| (w.width(), w.height())).unwrap_or((0, 0));
        self.wanted = output_candidates(&self.options, &video.color).into_iter().filter(|o| *o != Output::P3Linear).collect();
        if let Some(target) = &self.target {
            if target.stale
                || target.requested != self.wanted
                || (window_size.0 > 0 && (window_size.0 as u32, window_size.1 as u32) != target.logical)
            {
                self.recreate()?;
            }
        }
        self.apply_hdr_metadata(video);
        self.wait_gpu()
    }

    fn apply_hdr_metadata(&mut self, video: &VideoFrame) {
        let (Some(core), Some(target)) = (self.core.as_ref(), self.target.as_mut()) else {
            return;
        };
        let Some(functions) = core.hdr_metadata_fn.as_ref() else {
            return;
        };
        if target.output != Output::Pq {
            return;
        }
        let tone = ToneTarget::new(Output::Pq, &self.options.luminance);
        let content = content_peak(None, &video.color.with_scene_nits(0.0), tone.hlg_nits(Output::Pq));
        let peak = if tone.maps_to_display() {
            tone.display_nits(content)
        } else if video.color.peak_nits > 0.0 {
            video.color.peak_nits
        } else {
            content
        };
        let average = video.color.average_nits.min(peak).max(0.0);
        if target.light_metadata == (peak, average) {
            return;
        }
        target.light_metadata = (peak, average);
        let mastering = if !tone.maps_to_display() && video.color.mastering_nits > 0.0 { video.color.mastering_nits } else { peak };
        let metadata = vk::HdrMetadataEXT::default()
            .display_primary_red(vk::XYColorEXT { x: 0.708, y: 0.292 })
            .display_primary_green(vk::XYColorEXT { x: 0.170, y: 0.797 })
            .display_primary_blue(vk::XYColorEXT { x: 0.131, y: 0.046 })
            .white_point(vk::XYColorEXT { x: 0.3127, y: 0.3290 })
            .max_luminance(mastering)
            .min_luminance(tone.black * SDR_WHITE_NITS)
            .max_content_light_level(peak)
            .max_frame_average_light_level(average);
        unsafe { functions.set_hdr_metadata(&[target.swapchain], &[metadata]) };
    }

    unsafe fn measure_scene(
        &mut self,
        serial: u64,
        params: &Params,
        layout: vk::PipelineLayout,
        set: vk::DescriptorSet,
        key: Option<ExternalKey>,
    ) -> Option<f32> {
        unsafe {
            if self.meter.is_none() && !self.meter_failed {
                match meter::Meter::new(self.core.as_ref()?) {
                    Ok(meter) => self.meter = Some(meter),
                    Err(error) => {
                        log::warn!("adaptive tone mapping unavailable: {error}");
                        self.meter_failed = true;
                    }
                }
            }
            let (completed, submitted) = (self.completed, self.submitted);
            let core = self.core.as_ref()?;
            let meter = self.meter.as_mut()?;
            let measured = meter.collect(serial, completed);
            let fragment = if key.is_some() { core.external_fragment } else { core.yuv_fragment };
            if let Err(error) = meter.record(core, serial, submitted, params, layout, set, fragment, key) {
                log::warn!("adaptive tone mapping unavailable: {error}");
                if let Some(mut meter) = self.meter.take() {
                    let _ = core.device.device_wait_idle();
                    meter.destroy(core);
                }
                self.meter_failed = true;
                return None;
            }
            measured
        }
    }

    fn wait_gpu(&mut self) -> Result<(), String> {
        unsafe {
            let core = self.core.as_ref().ok_or("no device")?;
            match core.device.wait_for_fences(&[core.fence], true, FENCE_TIMEOUT) {
                Ok(()) => {}
                Err(vk::Result::TIMEOUT) => return Err("GPU timed out".into()),
                Err(error) => return Err(format!("wait for fence: {error}")),
            }
            self.completed = self.submitted;
            self.staging_cursor = 0;
            self.release_retired();
            if let (Some(core), Some(smooth)) = (self.core.as_ref(), self.smooth.as_ref()) {
                core.device
                    .reset_descriptor_pool(smooth.pool(), vk::DescriptorPoolResetFlags::empty())
                    .map_err(|e| format!("descriptor pool: {e}"))?;
            }
            Ok(())
        }
    }

    fn begin_recording(&self) -> Result<(), String> {
        unsafe {
            let core = self.core.as_ref().ok_or("no device")?;
            let command = core.command_buffer;
            core.device
                .reset_command_buffer(command, vk::CommandBufferResetFlags::empty())
                .map_err(|e| e.to_string())?;
            core.device
                .begin_command_buffer(command, &vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT))
                .map_err(|e| e.to_string())
        }
    }

    fn acquire_frame(&mut self) -> Result<Option<u32>, String> {
        unsafe {
            let core = self.core.as_ref().ok_or("no device")?;
            let target = self.target.as_ref().ok_or("no target")?;
            let (index, _) = match core.swapchain_fn.acquire_next_image(
                target.swapchain,
                100_000_000,
                core.acquire_semaphore,
                vk::Fence::null(),
            ) {
                Ok(result) => result,
                Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => {
                    self.recreate()?;
                    return Ok(None);
                }
                Err(vk::Result::TIMEOUT) | Err(vk::Result::NOT_READY) => return Ok(None),
                Err(error) => return Err(format!("acquire: {error}")),
            };
            if let Some(meter) = self.meter.as_mut() {
                meter.discard_unsubmitted(self.submitted);
            }
            self.begin_recording()?;
            Ok(Some(index))
        }
    }

    unsafe fn prepare_source(&mut self, video: &VideoFrame, lane: usize, offscreen: bool, view: Option<&View>) -> Result<Prepared, String> {
        unsafe {
            let (logical_width, logical_height, rotation, output, transform, dither) = match view {
                Some(view) => (view.width, view.height, 0, view.output, view.transform, 1),
                None => {
                    let target = self.target.as_ref().ok_or("no target")?;
                    let dither = if offscreen { 0 } else { target.dither };
                    (target.logical.0 as i32, target.logical.1 as i32, target.rotation, target.output as i32, self.transform, dither)
                }
            };
            let output_kind = Output::from_code(output);
            let tone = ToneTarget::new(output_kind, &self.options.luminance);
            let hlg_nits = tone.hlg_nits(output_kind);
            let placement = GlesPresenter::geometry(video, logical_width, logical_height, &transform);
            let mut params = Params { scale: placement.scale, translate: placement.translate, tone: tone.packed(), ..Default::default() };
            let scaling = GlesPresenter::scaling(self.scaler_mode, self.downscaler_mode, self.quality_cap, video, &placement, logical_width, logical_height);
            self.active_scaling = scaling.summary();
            params.ratio = scaling.ratio;
            params.antiring = self.antiring;
            let variant = scaling.kernel * 2 + scaling.downscale as i32;
            let (pipeline, layout, set, external_key) = match &video.data {
                FrameData::Software(frame) => {
                    self.record_software(video, frame, lane, &mut params, hlg_nits)?;
                    let pipeline = self.yuv_variant(variant, offscreen)?;
                    let core = self.core.as_ref().expect("core");
                    (pipeline, core.yuv_pipeline_layout, core.yuv_sets[lane], None)
                }
                FrameData::Hardware(frame) => {
                    let (key, imported_view, barrier) = self.import(frame.as_ref(), &video.color)?;
                    let core = self.core.as_ref().expect("core");
                    core.device.cmd_pipeline_barrier(
                        core.command_buffer,
                        vk::PipelineStageFlags::TOP_OF_PIPE,
                        vk::PipelineStageFlags::FRAGMENT_SHADER,
                        vk::DependencyFlags::empty(),
                        &[],
                        &[],
                        &[barrier],
                    );
                    let external = &self.external[&key];
                    let info = [vk::DescriptorImageInfo::default()
                        .image_view(imported_view)
                        .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
                    let write = vk::WriteDescriptorSet::default()
                        .dst_set(external.sets[lane])
                        .dst_binding(0)
                        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                        .image_info(&info);
                    core.device.update_descriptor_sets(&[write], &[]);
                    let (buffer_width, buffer_height) = frame.buffer_size();
                    let [left, top, right, bottom] = frame.crop();
                    params.crop = if buffer_width > 0 && buffer_height > 0 && right > left && bottom > top {
                        let w = buffer_width as f32;
                        let h = buffer_height as f32;
                        [left as f32 / w, top as f32 / h, (right - left) as f32 / w, (bottom - top) as f32 / h]
                    } else {
                        [0.0, 0.0, 1.0, 1.0]
                    };
                    let color = video.color;
                    let transfer = transfer_of(color.transfer);
                    params.flags = ((transfer as i32) << 12) | ((is_bt2020(&color) as i32) << 14);
                    params.peak = content_peak(None, &color, hlg_nits) / SDR_WHITE_NITS;
                    params.source_size = [buffer_width.max(1) as f32, buffer_height.max(1) as f32];
                    let (layout, set) = (external.pipeline_layout, external.sets[lane]);
                    (self.external_variant(key, variant, offscreen)?, layout, set, Some(key))
                }
            };
            params.flags = (params.flags & !0x3FE)
                | ((scaling.kernel & 31) << 1)
                | ((scaling.downscale as i32) << 6)
                | (output << 7)
                | ((dither & 3) << 15)
                | ((placement.turns & 3) << 17)
                | ((rotation & 3) << 19);
            let rgba = (params.flags >> 10) & 3 == 2;
            if view.is_none() && !rgba && self.options.adaptive && tone.should_measure(output_kind, &video.color) {
                if let Some(nits) = self.measure_scene(video.serial, &params, layout, set, external_key) {
                    params.peak = nits / SDR_WHITE_NITS;
                }
            }
            Ok(Prepared { pipeline, layout, set, params })
        }
    }

    fn record_swapchain(&self, index: u32, prepared: &Prepared) -> Result<(), String> {
        unsafe {
            let core = self.core.as_ref().ok_or("no device")?;
            let target = self.target.as_ref().ok_or("no target")?;
            smooth::record_draw(
                core,
                core.command_buffer,
                &smooth::Draw {
                    render_pass: target.render_pass,
                    framebuffer: target.framebuffers[index as usize],
                    extent: target.extent,
                    clear: smooth::black(),
                    pipeline: prepared.pipeline,
                    layout: prepared.layout,
                    set: prepared.set,
                    stages: vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                    push: prepared.params.bytes(),
                    vertices: 4,
                },
            );
            Ok(())
        }
    }

    fn submit_frame(&mut self, index: u32) -> Result<bool, String> {
        unsafe {
            let core = self.core.as_ref().ok_or("no device")?;
            let target = self.target.as_ref().ok_or("no target")?;
            let command = core.command_buffer;
            core.device.end_command_buffer(command).map_err(|e| e.to_string())?;

            core.device.reset_fences(&[core.fence]).map_err(|e| e.to_string())?;
            let wait = [core.acquire_semaphore];
            let stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
            let commands = [command];
            let signal = [target.present_semaphores[index as usize]];
            core.device
                .queue_submit(
                    core.queue,
                    &[vk::SubmitInfo::default()
                        .wait_semaphores(&wait)
                        .wait_dst_stage_mask(&stages)
                        .command_buffers(&commands)
                        .signal_semaphores(&signal)],
                    core.fence,
                )
                .map_err(|e| format!("submit: {e}"))?;
            self.submitted += 1;
            let swapchains = [target.swapchain];
            let indices = [index];
            self.present_id = self.present_id.wrapping_add(1);
            let times = [vk::PresentTimeGOOGLE {
                present_id: self.present_id,
                desired_present_time: self.target_ns.unwrap_or(0).max(0) as u64,
            }];
            let mut timing = vk::PresentTimesInfoGOOGLE::default().times(&times);
            let mut info = vk::PresentInfoKHR::default().wait_semaphores(&signal).swapchains(&swapchains).image_indices(&indices);
            if core.display_timing && self.target_ns.is_some() {
                info = info.push_next(&mut timing);
            }
            let present = core.swapchain_fn.queue_present(core.queue, &info);
            match present {
                Ok(false) => Ok(true),
                Ok(true) => {
                    let core = self.core.as_ref().expect("core");
                    if let Some(target) = self.target.as_mut() {
                        let (current, supported) = core
                            .surface_fn
                            .get_physical_device_surface_capabilities(core.physical, target.surface)
                            .map(|capabilities| (capabilities.current_transform, capabilities.supported_transforms))
                            .unwrap_or((target.transform, vk::SurfaceTransformFlagsKHR::empty()));
                        if supported.contains(current) && rotation_of(current) != rotation_of(target.transform) {
                            target.stale = true;
                        }
                    }
                    Ok(true)
                }
                Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => {
                    if let Some(target) = self.target.as_mut() {
                        target.stale = true;
                    }
                    Ok(true)
                }
                Err(error) => Err(format!("present: {error}")),
            }
        }
    }

    fn draw(&mut self, video: &VideoFrame) -> Result<bool, String> {
        unsafe {
            self.refresh_target(video)?;
            let Some(index) = self.acquire_frame()? else {
                return Ok(false);
            };
            let prepared = self.prepare_source(video, 0, false, None)?;
            self.record_swapchain(index, &prepared)?;
            self.submit_frame(index)
        }
    }

    pub fn present(&mut self, video: &VideoFrame) -> bool {
        if self.failed || self.target.is_none() {
            return false;
        }
        match self.draw(video) {
            Ok(drawn) => drawn,
            Err(error) => {
                log::error!("Vulkan rendering failed: {error}");
                self.failed = true;
                false
            }
        }
    }

    fn destroy_all(&mut self) {
        self.detach();
        let Some(mut core) = self.core.take() else {
            return;
        };
        unsafe {
            let _ = core.device.device_wait_idle();
            self.retired.clear();
            for plane in self.planes.iter_mut().flat_map(|lane| lane.iter_mut()).filter_map(|p| p.take()) {
                core.device.destroy_image_view(plane.view, None);
                core.device.destroy_image(plane.image, None);
                core.device.free_memory(plane.memory, None);
            }
            for staging in self.staging.take().into_iter().chain(self.retired_staging.drain(..)) {
                core.device.unmap_memory(staging.memory);
                core.device.destroy_buffer(staging.buffer, None);
                core.device.free_memory(staging.memory, None);
            }
            if let Some(mut meter) = self.meter.take() {
                meter.destroy(&core);
            }
            for (_, mut pipeline) in self.external.drain() {
                for (_, variant) in pipeline.pipelines.drain() {
                    core.device.destroy_pipeline(variant, None);
                }
                core.device.destroy_pipeline_layout(pipeline.pipeline_layout, None);
                core.device.destroy_descriptor_set_layout(pipeline.set_layout, None);
                core.device.destroy_sampler(pipeline.sampler, None);
                core.device.destroy_sampler_ycbcr_conversion(pipeline.conversion, None);
            }
            core.destroy();
        }
    }
}

impl Drop for VulkanPresenter {
    fn drop(&mut self) {
        self.destroy_all();
    }
}
