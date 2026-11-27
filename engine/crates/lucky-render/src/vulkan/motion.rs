use ash::vk;

use super::smooth::{Ctx, Draw, Rt, bytes_of, fullscreen_pipeline, offscreen_pass, record_draw};
use super::{Core, color_range, find_memory, spirv};
use crate::memc::{MemcParams, PYRAMIDS, Quality, READBACKS, blocks, grid, level_count, level_for, level_size, quality, search_range};

const DOWN_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/memc_down.frag.spv"));
const SEARCH_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/memc_search.frag.spv"));
const MEDIAN_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/memc_median.frag.spv"));
const DENSE_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/memc_dense.frag.spv"));
const TILES_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/memc_tiles.frag.spv"));
const STATS_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/memc_stats.frag.spv"));
const SELECT_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/memc_select.frag.spv"));
const SETTLE_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/memc_settle.frag.spv"));
const MASK_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/memc_mask.frag.spv"));
const SOFTEN_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/memc_soften.frag.spv"));
const RENDER_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/memc_render.frag.spv"));

#[repr(C)]
#[derive(Clone, Copy)]
struct DownPush {
    inv: [f32; 2],
    color: i32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct SearchPush {
    inv: [f32; 2],
    scale: [f32; 2],
    prior_size: [i32; 2],
    temporal_size: [i32; 2],
    field_size: [i32; 2],
    grid_step: f32,
    mode: i32,
    has_temporal: i32,
    search: i32,
    stages: i32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct MedianPush {
    size: [i32; 2],
}

#[repr(C)]
#[derive(Clone, Copy)]
struct DensePush {
    grid_size: [i32; 2],
    inv: [f32; 2],
    scale: [f32; 2],
    grid_step: f32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct TilesPush {
    size: [i32; 2],
}

#[repr(C)]
#[derive(Clone, Copy)]
struct StatsPush {
    tiles_size: [i32; 2],
    field_size: [i32; 2],
    has_field: i32,
    has_previous: i32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct SelectPush {
    rect: [f32; 4],
    flow_size: [i32; 2],
    flow_scale: [f32; 2],
    inv: [f32; 2],
    t: f32,
    iterations: i32,
    reach: i32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct SizePush {
    size: [i32; 2],
}

#[repr(C)]
#[derive(Clone, Copy)]
struct SoftenPush {
    inv: [f32; 2],
}

#[repr(C)]
#[derive(Clone, Copy)]
pub(super) struct RenderPush {
    rect: [f32; 4],
    flow_size: [i32; 2],
    flow_scale: [f32; 2],
    inv: [f32; 2],
    unit: [f32; 2],
    t: f32,
    dither: f32,
}

impl RenderPush {
    pub(super) fn set_dither(&mut self, levels: f32) {
        self.dither = levels;
    }
}

pub(super) struct RenderDraw {
    pub(super) set: vk::DescriptorSet,
    pub(super) layout: vk::PipelineLayout,
    pub(super) push: RenderPush,
    pub(super) stages: u32,
}

#[derive(Clone, Copy)]
pub(super) struct MotionFrame {
    pub(super) view: vk::ImageView,
    pub(super) version: u64,
}

fn clear_float() -> vk::ClearValue {
    vk::ClearValue { color: vk::ClearColorValue { float32: [0.0; 4] } }
}

fn clear_int() -> vk::ClearValue {
    vk::ClearValue { color: vk::ClearColorValue { int32: [0; 4] } }
}

struct Program {
    layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    module: vk::ShaderModule,
}

impl Program {
    fn empty() -> Self {
        Self { layout: vk::PipelineLayout::null(), pipeline: vk::Pipeline::null(), module: vk::ShaderModule::null() }
    }

    unsafe fn build(
        &mut self,
        core: &Core,
        vertex: vk::ShaderModule,
        bytes: &[u8],
        set_layout: vk::DescriptorSetLayout,
        push: usize,
        render_pass: Option<vk::RenderPass>,
    ) -> Result<(), String> {
        unsafe {
            let code = spirv(bytes);
            self.module = core
                .device
                .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&code), None)
                .map_err(|e| format!("motion shader: {e}"))?;
            let layouts = [set_layout];
            let ranges = [vk::PushConstantRange::default().stage_flags(vk::ShaderStageFlags::FRAGMENT).offset(0).size(push as u32)];
            self.layout = core
                .device
                .create_pipeline_layout(&vk::PipelineLayoutCreateInfo::default().set_layouts(&layouts).push_constant_ranges(&ranges), None)
                .map_err(|e| format!("motion pipeline layout: {e}"))?;
            if let Some(render_pass) = render_pass {
                self.pipeline = fullscreen_pipeline(core, render_pass, self.layout, vertex, self.module)
                    .map_err(|e| format!("motion pipeline: {e}"))?;
            }
            Ok(())
        }
    }

    unsafe fn destroy(&self, core: &Core) {
        unsafe {
            core.device.destroy_pipeline(self.pipeline, None);
            core.device.destroy_pipeline_layout(self.layout, None);
            core.device.destroy_shader_module(self.module, None);
        }
    }
}

struct Pyramid {
    version: Option<u64>,
    levels: Vec<Rt>,
    used: u64,
}

#[derive(Default)]
struct Direction {
    fields: Vec<Option<Rt>>,
    base: [Option<Rt>; 3],
    last: Option<usize>,
}

impl Direction {
    unsafe fn release(&mut self, core: &Core) {
        unsafe {
            for field in self.fields.drain(..).flatten() {
                field.destroy(core);
            }
            for target in self.base.iter_mut().filter_map(|t| t.take()) {
                target.destroy(core);
            }
            self.last = None;
        }
    }
}

#[derive(Default)]
struct PairWork {
    key: Option<(u64, u64)>,
    stage: u8,
    dense: [Option<Rt>; 2],
    stats: Option<Rt>,
}

impl PairWork {
    fn restart(&mut self, key: (u64, u64)) {
        self.key = Some(key);
        self.stage = 0;
    }

    unsafe fn release_dense(&mut self, core: &Core) {
        unsafe {
            for target in self.dense.iter_mut().filter_map(|t| t.take()) {
                target.destroy(core);
            }
            self.key = None;
            self.stage = 0;
        }
    }

    unsafe fn release(&mut self, core: &Core) {
        unsafe {
            self.release_dense(core);
            if let Some(stats) = self.stats.take() {
                stats.destroy(core);
            }
        }
    }
}

pub(super) struct Readback {
    pub(super) buffer: vk::Buffer,
    pub(super) memory: vk::DeviceMemory,
    pub(super) mapped: *mut u8,
    pending: Option<(u64, (u64, u64))>,
}

pub(super) unsafe fn destroy_readback(core: &Core, readback: &Readback) {
    unsafe {
        core.device.unmap_memory(readback.memory);
        core.device.destroy_buffer(readback.buffer, None);
        core.device.free_memory(readback.memory, None);
    }
}

pub(super) unsafe fn create_readback(core: &Core, size: u64) -> Result<Readback, String> {
    unsafe {
        let device = &core.device;
        let buffer = device
            .create_buffer(
                &vk::BufferCreateInfo::default().size(size).usage(vk::BufferUsageFlags::TRANSFER_DST).sharing_mode(vk::SharingMode::EXCLUSIVE),
                None,
            )
            .map_err(|e| format!("readback buffer: {e}"))?;
        let requirements = device.get_buffer_memory_requirements(buffer);
        let coherent = vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT;
        let Some(type_index) = find_memory(&core.memory, requirements.memory_type_bits, coherent | vk::MemoryPropertyFlags::HOST_CACHED)
            .or_else(|| find_memory(&core.memory, requirements.memory_type_bits, coherent))
        else {
            device.destroy_buffer(buffer, None);
            return Err("no host memory type for a readback".into());
        };
        let memory = match device
            .allocate_memory(&vk::MemoryAllocateInfo::default().allocation_size(requirements.size).memory_type_index(type_index), None)
        {
            Ok(memory) => memory,
            Err(error) => {
                device.destroy_buffer(buffer, None);
                return Err(format!("readback memory: {error}"));
            }
        };
        if let Err(error) = device.bind_buffer_memory(buffer, memory, 0) {
            device.destroy_buffer(buffer, None);
            device.free_memory(memory, None);
            return Err(format!("readback bind: {error}"));
        }
        let mapped = match device.map_memory(memory, 0, vk::WHOLE_SIZE, vk::MemoryMapFlags::empty()) {
            Ok(mapped) => mapped as *mut u8,
            Err(error) => {
                device.destroy_buffer(buffer, None);
                device.free_memory(memory, None);
                return Err(format!("readback map: {error}"));
            }
        };
        std::ptr::write_bytes(mapped, 0, size.min(4) as usize);
        Ok(Readback { buffer, memory, mapped, pending: None })
    }
}

unsafe fn run(
    ctx: &Ctx,
    program: &Program,
    render_pass: vk::RenderPass,
    clear: vk::ClearValue,
    target: &Rt,
    views: &[(vk::ImageView, bool)],
    push: &[u8],
) -> Result<(), String> {
    unsafe {
        let set = ctx.bind(views)?;
        record_draw(
            ctx.core,
            ctx.command,
            &Draw {
                render_pass,
                framebuffer: target.framebuffer,
                extent: vk::Extent2D { width: target.width as u32, height: target.height as u32 },
                clear,
                pipeline: program.pipeline,
                layout: program.layout,
                set,
                stages: vk::ShaderStageFlags::FRAGMENT,
                push,
                vertices: 3,
            },
        );
        Ok(())
    }
}

pub(super) struct Motion {
    pass_r8: vk::RenderPass,
    pass_field: vk::RenderPass,
    pass_rgba8: vk::RenderPass,
    down: Program,
    search: Program,
    median: Program,
    dense: Program,
    tiles: Program,
    stats: Program,
    select: Program,
    settle: Program,
    mask: Program,
    soften: Program,
    render: Program,
    dummy: Option<Rt>,
    dummy_ready: bool,
    size: (i32, i32),
    levels: usize,
    pyramids: Vec<Pyramid>,
    clock: u64,
    layout: Option<(usize, Quality)>,
    flow: [Direction; 2],
    tile_level: usize,
    pair_tiles: Option<Rt>,
    picks: [Option<Rt>; 2],
    masks: [Option<Rt>; 2],
    works: [PairWork; 2],
    current: usize,
    check_tiles: Option<Rt>,
    check_target: Option<Rt>,
    readbacks: Vec<Readback>,
    next_readback: usize,
    results: Vec<((u64, u64), f32)>,
}

impl Motion {
    pub(super) unsafe fn new(core: &Core, vertex: vk::ShaderModule, set_layout: vk::DescriptorSetLayout) -> Result<Self, String> {
        unsafe {
            let mut motion = Motion {
                pass_r8: vk::RenderPass::null(),
                pass_field: vk::RenderPass::null(),
                pass_rgba8: vk::RenderPass::null(),
                down: Program::empty(),
                search: Program::empty(),
                median: Program::empty(),
                dense: Program::empty(),
                tiles: Program::empty(),
                stats: Program::empty(),
                select: Program::empty(),
                settle: Program::empty(),
                mask: Program::empty(),
                soften: Program::empty(),
                render: Program::empty(),
                dummy: None,
                dummy_ready: false,
                size: (0, 0),
                levels: 0,
                pyramids: Vec::new(),
                clock: 0,
                layout: None,
                flow: [Direction::default(), Direction::default()],
                tile_level: 0,
                pair_tiles: None,
                picks: [None, None],
                masks: [None, None],
                works: [PairWork::default(), PairWork::default()],
                current: 0,
                check_tiles: None,
                check_target: None,
                readbacks: Vec::new(),
                next_readback: 0,
                results: Vec::new(),
            };
            if let Err(error) = motion.build(core, vertex, set_layout) {
                motion.destroy(core);
                return Err(error);
            }
            Ok(motion)
        }
    }

    unsafe fn build(&mut self, core: &Core, vertex: vk::ShaderModule, set_layout: vk::DescriptorSetLayout) -> Result<(), String> {
        unsafe {
            let pass = |format: vk::Format| offscreen_pass(core, format).map_err(|e| format!("motion render pass: {e}"));
            self.pass_r8 = pass(vk::Format::R8_UNORM)?;
            self.pass_field = pass(vk::Format::R16G16B16A16_SINT)?;
            self.pass_rgba8 = pass(vk::Format::R8G8B8A8_UNORM)?;
            self.down.build(core, vertex, DOWN_FRAG, set_layout, std::mem::size_of::<DownPush>(), Some(self.pass_r8))?;
            self.search.build(core, vertex, SEARCH_FRAG, set_layout, std::mem::size_of::<SearchPush>(), Some(self.pass_field))?;
            self.median.build(core, vertex, MEDIAN_FRAG, set_layout, std::mem::size_of::<MedianPush>(), Some(self.pass_field))?;
            self.dense.build(core, vertex, DENSE_FRAG, set_layout, std::mem::size_of::<DensePush>(), Some(self.pass_field))?;
            self.tiles.build(core, vertex, TILES_FRAG, set_layout, std::mem::size_of::<TilesPush>(), Some(self.pass_rgba8))?;
            self.stats.build(core, vertex, STATS_FRAG, set_layout, std::mem::size_of::<StatsPush>(), Some(self.pass_rgba8))?;
            self.select.build(core, vertex, SELECT_FRAG, set_layout, std::mem::size_of::<SelectPush>(), Some(self.pass_field))?;
            self.settle.build(core, vertex, SETTLE_FRAG, set_layout, std::mem::size_of::<SizePush>(), Some(self.pass_field))?;
            self.mask.build(core, vertex, MASK_FRAG, set_layout, std::mem::size_of::<SizePush>(), Some(self.pass_r8))?;
            self.soften.build(core, vertex, SOFTEN_FRAG, set_layout, std::mem::size_of::<SoftenPush>(), Some(self.pass_r8))?;
            self.render.build(core, vertex, RENDER_FRAG, set_layout, std::mem::size_of::<RenderPush>(), None)?;
            self.dummy = Some(Rt::new(core, self.pass_field, vk::Format::R16G16B16A16_SINT, 1, 1, vk::ImageUsageFlags::empty())?);
            for _ in 0..READBACKS {
                let readback = create_readback(core, 64)?;
                self.readbacks.push(readback);
            }
            Ok(())
        }
    }

    pub(super) fn render_layout(&self) -> vk::PipelineLayout {
        self.render.layout
    }

    pub(super) fn render_module(&self) -> vk::ShaderModule {
        self.render.module
    }

    unsafe fn release_picks(&mut self, core: &Core) {
        unsafe {
            for target in self.picks.iter_mut().chain(self.masks.iter_mut()).filter_map(|t| t.take()) {
                target.destroy(core);
            }
        }
    }

    unsafe fn release_sized(&mut self, core: &Core) {
        unsafe {
            for pyramid in self.pyramids.drain(..) {
                for level in pyramid.levels {
                    level.destroy(core);
                }
            }
            for direction in self.flow.iter_mut() {
                direction.release(core);
            }
            for work in self.works.iter_mut() {
                work.release(core);
            }
            self.release_picks(core);
            for target in [self.pair_tiles.take(), self.check_tiles.take(), self.check_target.take()].into_iter().flatten() {
                target.destroy(core);
            }
            self.layout = None;
            self.size = (0, 0);
        }
    }

    unsafe fn ensure_size(&mut self, core: &Core, width: i32, height: i32) -> Result<(), String> {
        unsafe {
            if self.size == (width, height) && !self.pyramids.is_empty() {
                return Ok(());
            }
            self.release_sized(core);
            self.levels = level_count(width, height);
            for _ in 0..PYRAMIDS {
                self.pyramids.push(Pyramid { version: None, levels: Vec::with_capacity(self.levels), used: 0 });
                let index = self.pyramids.len() - 1;
                for level in 0..self.levels {
                    let (w, h) = level_size(width, height, level);
                    let target = Rt::new(core, self.pass_r8, vk::Format::R8_UNORM, w, h, vk::ImageUsageFlags::empty())?;
                    self.pyramids[index].levels.push(target);
                }
            }
            self.tile_level = level_for(width, height, 400, self.levels);
            let tile_size = blocks(level_size(width, height, self.tile_level));
            let none = vk::ImageUsageFlags::empty();
            self.pair_tiles = Some(Rt::new(core, self.pass_rgba8, vk::Format::R8G8B8A8_UNORM, tile_size.0, tile_size.1, none)?);
            self.check_tiles = Some(Rt::new(core, self.pass_rgba8, vk::Format::R8G8B8A8_UNORM, tile_size.0, tile_size.1, none)?);
            for index in 0..2 {
                let stats = Rt::new(core, self.pass_rgba8, vk::Format::R8G8B8A8_UNORM, 1, 1, none)?;
                self.works[index].stats = Some(stats);
            }
            self.check_target =
                Some(Rt::new(core, self.pass_rgba8, vk::Format::R8G8B8A8_UNORM, 1, 1, vk::ImageUsageFlags::TRANSFER_SRC)?);
            self.size = (width, height);
            Ok(())
        }
    }

    unsafe fn ensure_layout(&mut self, core: &Core, base: usize, q: Quality) -> Result<(), String> {
        unsafe {
            if self.layout == Some((base, q)) {
                return Ok(());
            }
            for direction in self.flow.iter_mut() {
                direction.release(core);
            }
            for work in self.works.iter_mut() {
                work.release_dense(core);
            }
            self.release_picks(core);
            let size = self.size;
            let none = vk::ImageUsageFlags::empty();
            for side in 0..2 {
                self.flow[side].fields = (0..self.levels).map(|_| None).collect();
                for level in base + 1..self.levels {
                    let cells = grid(level_size(size.0, size.1, level), q.step);
                    let target = Rt::new(core, self.pass_field, vk::Format::R16G16B16A16_SINT, cells.0, cells.1, none)?;
                    self.flow[side].fields[level] = Some(target);
                }
                let cells = grid(level_size(size.0, size.1, base), q.step);
                for slot in 0..3 {
                    let target = Rt::new(core, self.pass_field, vk::Format::R16G16B16A16_SINT, cells.0, cells.1, none)?;
                    self.flow[side].base[slot] = Some(target);
                }
            }
            let flow = level_size(size.0, size.1, base);
            for index in 0..2 {
                for side in 0..2 {
                    let target = Rt::new(core, self.pass_field, vk::Format::R16G16B16A16_SINT, flow.0, flow.1, none)?;
                    self.works[index].dense[side] = Some(target);
                }
            }
            for index in 0..2 {
                self.picks[index] = Some(Rt::new(core, self.pass_field, vk::Format::R16G16B16A16_SINT, flow.0, flow.1, none)?);
            }
            self.masks[0] = Some(Rt::new(core, self.pass_r8, vk::Format::R8_UNORM, flow.0, flow.1, none)?);
            self.masks[1] = Some(Rt::new(core, self.pass_rgba8, vk::Format::R8G8B8A8_UNORM, flow.0, flow.1, none)?);
            self.layout = Some((base, q));
            Ok(())
        }
    }

    pub(super) unsafe fn prepare(&mut self, core: &Core, size: (i32, i32), level: u8) -> Result<(), String> {
        unsafe {
            self.ensure_size(core, size.0, size.1)?;
            let q = quality(level);
            let base = level_for(size.0, size.1, q.limit, self.levels);
            self.ensure_layout(core, base, q)
        }
    }

    pub(super) unsafe fn prepare_check(&mut self, core: &Core, size: (i32, i32)) -> Result<(), String> {
        unsafe { self.ensure_size(core, size.0, size.1) }
    }

    unsafe fn init_dummy(&mut self, ctx: &Ctx) -> Result<(), String> {
        unsafe {
            if self.dummy_ready {
                return Ok(());
            }
            let dummy = self.dummy.as_ref().ok_or("motion placeholder is missing")?;
            let clear = [clear_int()];
            ctx.core.device.cmd_begin_render_pass(
                ctx.command,
                &vk::RenderPassBeginInfo::default()
                    .render_pass(self.pass_field)
                    .framebuffer(dummy.framebuffer)
                    .render_area(vk::Rect2D { offset: vk::Offset2D::default(), extent: vk::Extent2D { width: 1, height: 1 } })
                    .clear_values(&clear),
                vk::SubpassContents::INLINE,
            );
            ctx.core.device.cmd_end_render_pass(ctx.command);
            self.dummy_ready = true;
            Ok(())
        }
    }

    unsafe fn pyramid(&mut self, ctx: &Ctx, frame: MotionFrame) -> Result<usize, String> {
        unsafe {
            self.clock += 1;
            if let Some(index) = self.pyramids.iter().position(|p| p.version == Some(frame.version)) {
                self.pyramids[index].used = self.clock;
                return Ok(index);
            }
            let index = self
                .pyramids
                .iter()
                .enumerate()
                .min_by_key(|(_, p)| if p.version.is_none() { 0 } else { p.used })
                .map(|(i, _)| i)
                .unwrap_or(0);
            let mut source = frame.view;
            let mut source_size = self.size;
            for level in 0..self.levels {
                let target = &self.pyramids[index].levels[level];
                let push = DownPush { inv: [1.0 / source_size.0 as f32, 1.0 / source_size.1 as f32], color: (level == 0) as i32 };
                run(ctx, &self.down, self.pass_r8, clear_float(), target, &[(source, true)], bytes_of(&push))?;
                source = target.view;
                source_size = (target.width, target.height);
            }
            let pyramid = &mut self.pyramids[index];
            pyramid.version = Some(frame.version);
            pyramid.used = self.clock;
            Ok(index)
        }
    }

    unsafe fn draw_tiles(&self, ctx: &Ctx, a: usize, b: usize, target: &Rt) -> Result<(), String> {
        unsafe {
            let level_a = &self.pyramids[a].levels[self.tile_level];
            let level_b = &self.pyramids[b].levels[self.tile_level];
            let push = TilesPush { size: [level_a.width, level_a.height] };
            run(ctx, &self.tiles, self.pass_rgba8, clear_float(), target, &[(level_a.view, false), (level_b.view, false)], bytes_of(&push))
        }
    }

    unsafe fn draw_stats(&self, ctx: &Ctx, tiles: &Rt, field: Option<&Rt>, previous: Option<&Rt>, target: &Rt) -> Result<(), String> {
        unsafe {
            let dummy = self.dummy.as_ref().ok_or("motion placeholder is missing")?;
            let (field_view, field_size, has_field) = match field {
                Some(field) => (field.view, [field.width, field.height], 1),
                None => (dummy.view, [1, 1], 0),
            };
            let (previous_view, has_previous) = match previous.filter(|_| field.is_some()) {
                Some(previous) => (previous.view, 1),
                None => (dummy.view, 0),
            };
            let push = StatsPush { tiles_size: [tiles.width, tiles.height], field_size, has_field, has_previous };
            run(
                ctx,
                &self.stats,
                self.pass_rgba8,
                clear_float(),
                target,
                &[(tiles.view, false), (field_view, false), (previous_view, false)],
                bytes_of(&push),
            )
        }
    }

    #[allow(clippy::too_many_arguments)]
    unsafe fn estimate(
        &mut self,
        ctx: &Ctx,
        side: usize,
        anchor: usize,
        target: usize,
        base: usize,
        q: Quality,
        work: usize,
    ) -> Result<Option<usize>, String> {
        unsafe {
            let size = self.size;
            let temporal = self.flow[side].last;
            let Some(output) = (0..3).find(|i| Some(*i) != temporal) else {
                return Ok(None);
            };
            let Some(spare) = (0..3).find(|i| Some(*i) != temporal && *i != output) else {
                return Ok(None);
            };
            let dummy = self.dummy.as_ref().ok_or("motion placeholder is missing")?;
            let direction = &self.flow[side];
            for level in (base..self.levels).rev() {
                let x = &self.pyramids[anchor].levels[level];
                let y = &self.pyramids[target].levels[level];
                let coarsest = level + 1 == self.levels;
                let destination = if level == base { direction.base[output].as_ref() } else { direction.fields[level].as_ref() };
                let Some(destination) = destination else {
                    return Ok(None);
                };
                let (prior_view, prior_size) = match direction.fields.get(level + 1).and_then(|f| f.as_ref()).filter(|_| !coarsest) {
                    Some(prior) => (prior.view, [prior.width, prior.height]),
                    None => (dummy.view, [1, 1]),
                };
                let (temporal_view, temporal_size, has_temporal) = match temporal.and_then(|i| direction.base[i].as_ref()) {
                    Some(previous) => (previous.view, [previous.width, previous.height], 1),
                    None => (dummy.view, [1, 1], 0),
                };
                let push = SearchPush {
                    inv: [1.0 / x.width as f32, 1.0 / x.height as f32],
                    scale: [x.width as f32 / size.0 as f32, x.height as f32 / size.1 as f32],
                    prior_size,
                    temporal_size,
                    field_size: [destination.width, destination.height],
                    grid_step: q.step as f32,
                    mode: if coarsest { 0 } else { 1 },
                    has_temporal,
                    search: search_range(coarsest, level > base, x.width, x.height),
                    stages: q.stages,
                };
                run(
                    ctx,
                    &self.search,
                    self.pass_field,
                    clear_int(),
                    destination,
                    &[(x.view, true), (y.view, true), (prior_view, false), (temporal_view, false)],
                    bytes_of(&push),
                )?;
            }
            let x = &self.pyramids[anchor].levels[base];
            let y = &self.pyramids[target].levels[base];
            let mut current = output;
            for _ in 0..q.propagate {
                let next = if current == output { spare } else { output };
                let (Some(source), Some(destination)) = (direction.base[current].as_ref(), direction.base[next].as_ref()) else {
                    break;
                };
                let push = SearchPush {
                    inv: [1.0 / x.width as f32, 1.0 / x.height as f32],
                    scale: [x.width as f32 / size.0 as f32, x.height as f32 / size.1 as f32],
                    prior_size: [source.width, source.height],
                    temporal_size: [1, 1],
                    field_size: [destination.width, destination.height],
                    grid_step: q.step as f32,
                    mode: 2,
                    has_temporal: 0,
                    search: 8,
                    stages: q.stages - 1,
                };
                run(
                    ctx,
                    &self.search,
                    self.pass_field,
                    clear_int(),
                    destination,
                    &[(x.view, true), (y.view, true), (source.view, false), (dummy.view, false)],
                    bytes_of(&push),
                )?;
                current = next;
            }
            let finished = if current == output { spare } else { output };
            let (Some(source), Some(destination)) = (direction.base[current].as_ref(), direction.base[finished].as_ref()) else {
                return Ok(None);
            };
            let push = MedianPush { size: [source.width, source.height] };
            run(ctx, &self.median, self.pass_field, clear_int(), destination, &[(source.view, false)], bytes_of(&push))?;
            let (Some(cells), Some(dense)) = (direction.base[finished].as_ref(), self.works[work].dense[side].as_ref()) else {
                return Ok(None);
            };
            let push = DensePush {
                grid_size: [cells.width, cells.height],
                inv: [1.0 / x.width as f32, 1.0 / x.height as f32],
                scale: [x.width as f32 / size.0 as f32, x.height as f32 / size.1 as f32],
                grid_step: q.step as f32,
            };
            run(
                ctx,
                &self.dense,
                self.pass_field,
                clear_int(),
                dense,
                &[(x.view, true), (y.view, true), (cells.view, false)],
                bytes_of(&push),
            )?;
            self.flow[side].last = Some(finished);
            Ok(Some(finished))
        }
    }

    unsafe fn run_stages(&mut self, ctx: &Ctx, work: usize, a: MotionFrame, b: MotionFrame, budget: u32) -> Result<u32, String> {
        unsafe {
            let Some((base, q)) = self.layout else {
                return Err("motion layout is not ready".into());
            };
            let mut ran = 0;
            while ran < budget && self.works[work].stage < 2 {
                let pa = self.pyramid(ctx, a)?;
                let pb = self.pyramid(ctx, b)?;
                if pa == pb {
                    return Err("motion frames share a pyramid".into());
                }
                if self.works[work].stage == 0 {
                    if let Some(tiles) = self.pair_tiles.as_ref() {
                        self.draw_tiles(ctx, pa, pb, tiles)?;
                    }
                    let earlier = self.flow[0].last;
                    let forward = self.estimate(ctx, 0, pa, pb, base, q, work)?.ok_or("motion fields are missing")?;
                    let field = self.flow[0].base[forward].as_ref();
                    let previous = earlier.and_then(|i| self.flow[0].base[i].as_ref());
                    if let (Some(tiles), Some(stats)) = (self.pair_tiles.as_ref(), self.works[work].stats.as_ref()) {
                        self.draw_stats(ctx, tiles, field, previous, stats)?;
                    }
                } else {
                    self.estimate(ctx, 1, pb, pa, base, q, work)?.ok_or("motion fields are missing")?;
                }
                self.works[work].stage += 1;
                ran += 1;
            }
            Ok(ran)
        }
    }

    fn ready(&self, size: (i32, i32), level: u8) -> bool {
        if self.size != size || self.pyramids.is_empty() {
            return false;
        }
        let q = quality(level);
        self.layout == Some((level_for(size.0, size.1, q.limit, self.levels), q))
    }

    pub(super) fn remaining(&self, a: u64, b: u64) -> u32 {
        let key = Some((a, b));
        self.works.iter().find(|work| work.key == key).map_or(2, |work| 2 - work.stage.min(2) as u32)
    }

    pub(super) unsafe fn advance(
        &mut self,
        ctx: &Ctx,
        a: MotionFrame,
        b: MotionFrame,
        shown: (u64, u64),
        params: MemcParams,
        budget: u32,
    ) -> Result<u32, String> {
        unsafe {
            if budget == 0 || !self.ready(self.size, params.level) {
                return Ok(0);
            }
            self.init_dummy(ctx)?;
            let key = (a.version, b.version);
            let shown = Some(shown);
            if shown == Some(key) {
                return Ok(0);
            }
            let work = match self.works.iter().position(|work| work.key == Some(key)) {
                Some(index) => index,
                None => {
                    let other = 1 - self.current;
                    let index = if self.works[other].key == shown { self.current } else { other };
                    self.works[index].restart(key);
                    index
                }
            };
            self.run_stages(ctx, work, a, b, budget)
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub(super) unsafe fn record(
        &mut self,
        ctx: &Ctx,
        a: MotionFrame,
        b: MotionFrame,
        size: (i32, i32),
        rect: [f32; 4],
        t: f32,
        params: MemcParams,
        out: (i32, i32),
    ) -> Result<RenderDraw, String> {
        unsafe {
            self.init_dummy(ctx)?;
            let q = quality(params.level);
            if !self.ready(size, params.level) {
                return Err("motion targets are not ready".into());
            }
            let key = (a.version, b.version);
            let work = match self.works.iter().position(|work| work.key == Some(key)) {
                Some(index) => index,
                None => {
                    let index = self.current;
                    self.works[index].restart(key);
                    index
                }
            };
            self.current = work;
            let stages = self.run_stages(ctx, work, a, b, 2)?;
            let pa = self.pyramid(ctx, a)?;
            let pb = self.pyramid(ctx, b)?;
            if pa == pb {
                return Err("motion frames share a pyramid".into());
            }
            let pair = &self.works[work];
            let (Some(forward), Some(backward), Some(stats)) = (pair.dense[0].as_ref(), pair.dense[1].as_ref(), pair.stats.as_ref()) else {
                return Err("motion flow is missing".into());
            };
            let (Some(pick), Some(settled), Some(risk), Some(soft)) = (self.picks[0].as_ref(), self.picks[1].as_ref(), self.masks[0].as_ref(), self.masks[1].as_ref()) else {
                return Err("motion pick target is missing".into());
            };
            let t = t.clamp(0.0, 1.0);
            let flow_scale = [forward.width as f32 / size.0 as f32, forward.height as f32 / size.1 as f32];
            let inv = [1.0 / size.0 as f32, 1.0 / size.1 as f32];
            let select = SelectPush {
                rect,
                flow_size: [forward.width, forward.height],
                flow_scale,
                inv,
                t,
                iterations: q.iterations,
                reach: q.reach,
            };
            run(
                ctx,
                &self.select,
                self.pass_field,
                clear_int(),
                pick,
                &[
                    (forward.view, false),
                    (backward.view, false),
                    (self.pyramids[pa].levels[0].view, true),
                    (self.pyramids[pb].levels[0].view, true),
                ],
                bytes_of(&select),
            )?;
            let size_push = SizePush { size: [pick.width, pick.height] };
            run(ctx, &self.settle, self.pass_field, clear_int(), settled, &[(pick.view, false)], bytes_of(&size_push))?;
            run(ctx, &self.mask, self.pass_r8, clear_float(), risk, &[(settled.view, false)], bytes_of(&size_push))?;
            let soften = SoftenPush { inv: [1.0 / risk.width as f32, 1.0 / risk.height as f32] };
            run(ctx, &self.soften, self.pass_rgba8, clear_float(), soft, &[(risk.view, true), (stats.view, false)], bytes_of(&soften))?;
            let shrink = [out.0 as f32 / size.0 as f32, out.1 as f32 / size.1 as f32];
            let push = RenderPush {
                rect: [rect[0] * shrink[0], rect[1] * shrink[1], rect[2] * shrink[0], rect[3] * shrink[1]],
                flow_size: [settled.width, settled.height],
                flow_scale: [flow_scale[0] / shrink[0], flow_scale[1] / shrink[1]],
                inv: [1.0 / out.0 as f32, 1.0 / out.1 as f32],
                unit: [0.25 * shrink[0], 0.25 * shrink[1]],
                t,
                dither: 0.0,
            };
            let set = ctx.bind(&[(a.view, true), (b.view, true), (settled.view, false), (soft.view, true)])?;
            Ok(RenderDraw { set, layout: self.render.layout, push, stages })
        }
    }

    pub(super) unsafe fn record_check(&mut self, ctx: &Ctx, a: MotionFrame, b: MotionFrame, serial: u64) -> Result<(), String> {
        unsafe {
            let pair = (a.version, b.version);
            if self.results.iter().any(|(p, _)| *p == pair) || self.readbacks.iter().any(|r| r.pending.is_some_and(|(_, p)| p == pair)) {
                return Ok(());
            }
            self.init_dummy(ctx)?;
            let pa = self.pyramid(ctx, a)?;
            let pb = self.pyramid(ctx, b)?;
            if pa == pb {
                return Err("check frames share a pyramid".into());
            }
            let (Some(tiles), Some(target)) = (self.check_tiles.as_ref(), self.check_target.as_ref()) else {
                return Err("check targets are missing".into());
            };
            self.draw_tiles(ctx, pa, pb, tiles)?;
            self.draw_stats(ctx, tiles, None, None, target)?;
            let slot = self.next_readback;
            self.next_readback = (slot + 1) % READBACKS;
            let readback = &mut self.readbacks[slot];
            let device = &ctx.core.device;
            let to_transfer = vk::ImageMemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)
                .dst_access_mask(vk::AccessFlags::TRANSFER_READ)
                .old_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
                .new_layout(vk::ImageLayout::TRANSFER_SRC_OPTIMAL)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .image(target.image)
                .subresource_range(color_range());
            device.cmd_pipeline_barrier(
                ctx.command,
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
                .image_extent(vk::Extent3D { width: 1, height: 1, depth: 1 });
            device.cmd_copy_image_to_buffer(ctx.command, target.image, vk::ImageLayout::TRANSFER_SRC_OPTIMAL, readback.buffer, &[region]);
            let to_host = vk::BufferMemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::HOST_READ)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .buffer(readback.buffer)
                .offset(0)
                .size(vk::WHOLE_SIZE);
            device.cmd_pipeline_barrier(
                ctx.command,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),
                &[],
                &[to_host],
                &[],
            );
            readback.pending = Some((serial, pair));
            Ok(())
        }
    }

    pub(super) unsafe fn check_result(&mut self, completed: u64, a: u64, b: u64) -> Option<f32> {
        unsafe {
            for readback in self.readbacks.iter_mut() {
                let Some((serial, pair)) = readback.pending else {
                    continue;
                };
                if serial > completed {
                    continue;
                }
                readback.pending = None;
                let peak = std::ptr::read_volatile(readback.mapped) as f32 / 255.0;
                self.results.push((pair, peak));
                if self.results.len() > 8 {
                    self.results.remove(0);
                }
            }
            self.results.iter().rev().find(|(p, _)| *p == (a, b)).map(|(_, peak)| *peak)
        }
    }

    pub(super) fn check_pending(&self, a: u64, b: u64) -> bool {
        self.readbacks.iter().any(|r| r.pending.is_some_and(|(_, pair)| pair == (a, b)))
    }

    pub(super) fn reset_motion(&mut self) {
        for direction in self.flow.iter_mut() {
            direction.last = None;
        }
        for work in self.works.iter_mut() {
            work.key = None;
            work.stage = 0;
        }
    }

    pub(super) unsafe fn destroy(&mut self, core: &Core) {
        unsafe {
            self.release_sized(core);
            if let Some(dummy) = self.dummy.take() {
                dummy.destroy(core);
            }
            for readback in self.readbacks.drain(..) {
                core.device.unmap_memory(readback.memory);
                core.device.destroy_buffer(readback.buffer, None);
                core.device.free_memory(readback.memory, None);
            }
            for program in [
                &self.down,
                &self.search,
                &self.median,
                &self.dense,
                &self.tiles,
                &self.stats,
                &self.select,
                &self.settle,
                &self.mask,
                &self.soften,
                &self.render,
            ] {
                program.destroy(core);
            }
            for pass in [self.pass_r8, self.pass_field, self.pass_rgba8] {
                core.device.destroy_render_pass(pass, None);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use std::mem::size_of;

    use super::*;

    #[test]
    fn push_blocks_match_the_shaders() {
        assert_eq!(size_of::<DownPush>(), 12);
        assert_eq!(size_of::<SearchPush>(), 60);
        assert_eq!(size_of::<MedianPush>(), 8);
        assert_eq!(size_of::<DensePush>(), 28);
        assert_eq!(size_of::<TilesPush>(), 8);
        assert_eq!(size_of::<StatsPush>(), 24);
        assert_eq!(size_of::<SelectPush>(), 52);
        assert_eq!(size_of::<SizePush>(), 8);
        assert_eq!(size_of::<SoftenPush>(), 8);
        assert_eq!(size_of::<RenderPush>(), 56);
    }
}
