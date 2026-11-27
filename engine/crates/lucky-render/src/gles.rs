use std::collections::HashMap;
use std::ffi::c_void;
use std::ptr;
use std::sync::OnceLock;

use glow::HasContext;
use lucky_ffmpeg::{Frame, Scaler, sys};
use lucky_player::{
    ActiveScaling, Ahead, DisplayOptions, DownscalerMode, FitMode, FrameData, HardwareFrame, MotionConfig, Presenter, ScalerMode, VideoFrame,
    ViewTransform,
};

use crate::color::{
    MEASURE_OUTPUT, Output, SDR_WHITE_NITS, ToneAnalyzer, ToneTarget, content_peak, is_bt2020, output_candidates, plausible_peak, transfer_of,
    yuv_transform,
};
use crate::dovi::{DOVI_HEIGHT, DOVI_WIDTH, dovi_table};
use crate::egl::*;
use crate::memc::{DUPLICATE_LIMIT, Memc, MemcFrame, MemcParams, scaled_size};
use crate::shaders;
use crate::window::{DATASPACE_BT2020_PQ, DATASPACE_UNKNOWN, NativeWindow};

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum Layout {
    Planar = 0,
    SemiPlanar = 1,
    Rgba = 2,
    Gray = 3,
}

#[derive(Clone, Copy)]
pub(crate) struct Plan {
    pub layout: Layout,
    pub wide: bool,
    pub depth: u32,
    pub shift: u32,
    pub chroma_w: u32,
    pub chroma_h: u32,
    pub swap_uv: bool,
}

pub(crate) fn plan_for(format: sys::AVPixelFormat, norm16: bool) -> Option<Plan> {
    if format == sys::AVPixelFormat::AV_PIX_FMT_RGBA || format == sys::AVPixelFormat::AV_PIX_FMT_RGB0 {
        return Some(Plan { layout: Layout::Rgba, wide: false, depth: 8, shift: 0, chroma_w: 0, chroma_h: 0, swap_uv: false });
    }
    let desc = unsafe { sys::av_pix_fmt_desc_get(format) };
    if desc.is_null() {
        return None;
    }
    let desc = unsafe { &*desc };
    let rejected = (sys::AV_PIX_FMT_FLAG_HWACCEL
        | sys::AV_PIX_FMT_FLAG_BE
        | sys::AV_PIX_FMT_FLAG_PAL
        | sys::AV_PIX_FMT_FLAG_FLOAT
        | sys::AV_PIX_FMT_FLAG_BAYER
        | sys::AV_PIX_FMT_FLAG_RGB) as u64;
    if desc.flags & rejected != 0 {
        return None;
    }
    let c0 = desc.comp[0];
    let depth = c0.depth as u32;
    if depth == 0 || depth > 16 {
        return None;
    }
    let wide = depth > 8;
    if wide && !norm16 {
        return None;
    }
    let sample_step = if wide { 2 } else { 1 };
    let base = Plan {
        layout: Layout::Planar,
        wide,
        depth,
        shift: c0.shift as u32,
        chroma_w: desc.log2_chroma_w as u32,
        chroma_h: desc.log2_chroma_h as u32,
        swap_uv: false,
    };
    if desc.nb_components == 1 || (desc.nb_components == 2 && desc.flags & sys::AV_PIX_FMT_FLAG_ALPHA as u64 != 0) {
        if c0.plane == 0 && c0.step == sample_step {
            return Some(Plan { layout: Layout::Gray, ..base });
        }
        return None;
    }
    if desc.nb_components < 3 || desc.flags & sys::AV_PIX_FMT_FLAG_PLANAR as u64 == 0 {
        return None;
    }
    let (c1, c2) = (desc.comp[1], desc.comp[2]);
    if c0.plane != 0 || c0.step != sample_step || c1.depth != c0.depth || c2.depth != c0.depth {
        return None;
    }
    if c1.plane == 1 && c2.plane == 2 && c1.step == sample_step && c2.step == sample_step {
        return Some(base);
    }
    if c1.plane == 1 && c2.plane == 1 && c1.step == sample_step * 2 && c2.step == sample_step * 2 {
        return Some(Plan { layout: Layout::SemiPlanar, swap_uv: c1.offset > c2.offset, ..base });
    }
    None
}

pub(crate) fn conversion_target(format: sys::AVPixelFormat, norm16: bool) -> sys::AVPixelFormat {
    let desc = unsafe { sys::av_pix_fmt_desc_get(format) };
    if desc.is_null() {
        return sys::AVPixelFormat::AV_PIX_FMT_RGBA;
    }
    let desc = unsafe { &*desc };
    if desc.flags & sys::AV_PIX_FMT_FLAG_RGB as u64 != 0 || desc.nb_components < 3 && desc.comp[0].depth > 16 {
        return sys::AVPixelFormat::AV_PIX_FMT_RGBA;
    }
    if desc.nb_components < 3 {
        return if desc.comp[0].depth > 8 && norm16 {
            sys::AVPixelFormat::AV_PIX_FMT_GRAY16LE
        } else {
            sys::AVPixelFormat::AV_PIX_FMT_GRAY8
        };
    }
    if desc.comp[0].depth > 8 && norm16 {
        sys::AVPixelFormat::AV_PIX_FMT_YUV420P10LE
    } else {
        sys::AVPixelFormat::AV_PIX_FMT_YUV420P
    }
}

#[derive(Clone)]
struct ExternalProgram {
    program: glow::Program,
    scale: Option<glow::UniformLocation>,
    translate: Option<glow::UniformLocation>,
    turns: Option<glow::UniformLocation>,
    crop: Option<glow::UniformLocation>,
    source_size: Option<glow::UniformLocation>,
    transfer: Option<glow::UniformLocation>,
    bt2020: Option<glow::UniformLocation>,
    peak: Option<glow::UniformLocation>,
    output: Option<glow::UniformLocation>,
    ratio: Option<glow::UniformLocation>,
    antiring: Option<glow::UniformLocation>,
    tone: Option<glow::UniformLocation>,
    dither: Option<glow::UniformLocation>,
}

#[derive(Clone)]
struct Uniforms {
    crop: Option<glow::UniformLocation>,
    scale: Option<glow::UniformLocation>,
    translate: Option<glow::UniformLocation>,
    turns: Option<glow::UniformLocation>,
    layout: Option<glow::UniformLocation>,
    sample_scale: Option<glow::UniformLocation>,
    matrix: Option<glow::UniformLocation>,
    offset: Option<glow::UniformLocation>,
    transfer: Option<glow::UniformLocation>,
    bt2020: Option<glow::UniformLocation>,
    peak: Option<glow::UniformLocation>,
    swap_uv: Option<glow::UniformLocation>,
    output: Option<glow::UniformLocation>,
    dovi_on: Option<glow::UniformLocation>,
    ratio: Option<glow::UniformLocation>,
    antiring: Option<glow::UniformLocation>,
    tone: Option<glow::UniformLocation>,
    dither: Option<glow::UniformLocation>,
}

struct DoviTexture {
    texture: glow::Texture,
    source: Vec<u8>,
    peak_nits: f32,
}

const METER_WIDTH: i32 = 128;
const METER_HEIGHT: i32 = 72;
const METER_SLOTS: usize = 3;
const METER_BYTES: i32 = METER_WIDTH * METER_HEIGHT * 4;

struct Meter {
    _texture: glow::Texture,
    framebuffer: glow::Framebuffer,
    buffers: [glow::Buffer; METER_SLOTS],
    pending: [Option<(glow::Fence, u64)>; METER_SLOTS],
    next: usize,
    analyzer: ToneAnalyzer,
}

impl Meter {
    unsafe fn new(gl: &glow::Context) -> Result<Self, String> {
        unsafe {
            let texture = gl.create_texture()?;
            gl.bind_texture(glow::TEXTURE_2D, Some(texture));
            gl.tex_storage_2d(glow::TEXTURE_2D, 1, glow::RGBA8, METER_WIDTH, METER_HEIGHT);
            gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_MIN_FILTER, glow::NEAREST as i32);
            gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_MAG_FILTER, glow::NEAREST as i32);
            let framebuffer = match gl.create_framebuffer() {
                Ok(framebuffer) => framebuffer,
                Err(error) => {
                    gl.delete_texture(texture);
                    return Err(error);
                }
            };
            gl.bind_framebuffer(glow::FRAMEBUFFER, Some(framebuffer));
            gl.framebuffer_texture_2d(glow::FRAMEBUFFER, glow::COLOR_ATTACHMENT0, glow::TEXTURE_2D, Some(texture), 0);
            let complete = gl.check_framebuffer_status(glow::FRAMEBUFFER) == glow::FRAMEBUFFER_COMPLETE;
            gl.bind_framebuffer(glow::FRAMEBUFFER, None);
            if !complete {
                gl.delete_framebuffer(framebuffer);
                gl.delete_texture(texture);
                return Err("the measurement target is incomplete".into());
            }
            let mut buffers = Vec::with_capacity(METER_SLOTS);
            for _ in 0..METER_SLOTS {
                let buffer = gl.create_buffer()?;
                gl.bind_buffer(glow::PIXEL_PACK_BUFFER, Some(buffer));
                gl.buffer_data_size(glow::PIXEL_PACK_BUFFER, METER_BYTES, glow::STREAM_READ);
                buffers.push(buffer);
            }
            gl.bind_buffer(glow::PIXEL_PACK_BUFFER, None);
            Ok(Self {
                _texture: texture,
                framebuffer,
                buffers: [buffers[0], buffers[1], buffers[2]],
                pending: [None; METER_SLOTS],
                next: 0,
                analyzer: ToneAnalyzer::default(),
            })
        }
    }

    unsafe fn collect(&mut self, gl: &glow::Context, serial: u64) -> Option<f32> {
        unsafe {
            self.analyzer.reset(serial);
            for step in 0..METER_SLOTS {
                let slot = (self.next + step) % METER_SLOTS;
                let Some((fence, measured)) = self.pending[slot] else {
                    continue;
                };
                let status = gl.client_wait_sync(fence, 0, 0);
                if status != glow::ALREADY_SIGNALED && status != glow::CONDITION_SATISFIED {
                    break;
                }
                gl.delete_sync(fence);
                self.pending[slot] = None;
                if measured != serial {
                    continue;
                }
                gl.bind_buffer(glow::PIXEL_PACK_BUFFER, Some(self.buffers[slot]));
                let data = gl.map_buffer_range(glow::PIXEL_PACK_BUFFER, 0, METER_BYTES, glow::MAP_READ_BIT);
                if !data.is_null() {
                    let pixels = std::slice::from_raw_parts(data, METER_BYTES as usize);
                    if let Some((peak, average)) = ToneAnalyzer::measure(pixels) {
                        self.analyzer.push(peak, average);
                    }
                    gl.unmap_buffer(glow::PIXEL_PACK_BUFFER);
                }
                gl.bind_buffer(glow::PIXEL_PACK_BUFFER, None);
            }
            self.analyzer.peak_nits()
        }
    }

    unsafe fn begin(&self, gl: &glow::Context) {
        unsafe {
            gl.bind_framebuffer(glow::FRAMEBUFFER, Some(self.framebuffer));
            gl.viewport(0, 0, METER_WIDTH, METER_HEIGHT);
        }
    }

    unsafe fn end(&mut self, gl: &glow::Context, serial: u64) {
        unsafe {
            let slot = self.next;
            if let Some((fence, _)) = self.pending[slot].take() {
                gl.delete_sync(fence);
            }
            gl.bind_buffer(glow::PIXEL_PACK_BUFFER, Some(self.buffers[slot]));
            gl.read_pixels(0, 0, METER_WIDTH, METER_HEIGHT, glow::RGBA, glow::UNSIGNED_BYTE, glow::PixelPackData::BufferOffset(0));
            gl.bind_buffer(glow::PIXEL_PACK_BUFFER, None);
            if let Ok(fence) = gl.fence_sync(glow::SYNC_GPU_COMMANDS_COMPLETE, 0) {
                self.pending[slot] = Some((fence, serial));
            }
            self.next = (slot + 1) % METER_SLOTS;
            gl.bind_framebuffer(glow::FRAMEBUFFER, None);
        }
    }
}

struct BlendSlot {
    texture: glow::Texture,
    framebuffer: glow::Framebuffer,
    size: (i32, i32),
    key: Option<(u64, i64)>,
    version: u64,
}

struct Blend {
    program: glow::Program,
    weight: Option<glow::UniformLocation>,
    dither: Option<glow::UniformLocation>,
    scale: Option<glow::UniformLocation>,
    translate: Option<glow::UniformLocation>,
    turns: Option<glow::UniformLocation>,
    crop: Option<glow::UniformLocation>,
    slots: [Option<BlendSlot>; 3],
    shrunk: Option<BlendSlot>,
}

impl Blend {
    unsafe fn new(gl: &glow::Context) -> Result<Self, String> {
        unsafe {
            let program = link(gl, shaders::VERTEX, shaders::BLEND_FRAGMENT)?;
            gl.use_program(Some(program));
            let a = gl.get_uniform_location(program, "u_a");
            gl.uniform_1_i32(a.as_ref(), 0);
            let b = gl.get_uniform_location(program, "u_b");
            gl.uniform_1_i32(b.as_ref(), 1);
            let uniform = |name: &str| gl.get_uniform_location(program, name);
            Ok(Self {
                program,
                weight: uniform("u_weight"),
                dither: uniform("u_dither"),
                scale: uniform("u_scale"),
                translate: uniform("u_translate"),
                turns: uniform("u_turns"),
                crop: uniform("u_crop"),
                slots: [None, None, None],
                shrunk: None,
            })
        }
    }

    unsafe fn ensure_slot(&mut self, gl: &glow::Context, index: usize, width: i32, height: i32) -> Result<(), String> {
        unsafe {
            if self.slots[index].as_ref().is_some_and(|slot| slot.size == (width, height)) {
                return Ok(());
            }
            if let Some(old) = self.slots[index].take() {
                gl.delete_framebuffer(old.framebuffer);
                gl.delete_texture(old.texture);
            }
            self.slots[index] = Some(new_slot(gl, width, height, glow::NEAREST)?);
            Ok(())
        }
    }

    unsafe fn ensure_shrunk(&mut self, gl: &glow::Context, width: i32, height: i32) -> Result<(), String> {
        unsafe {
            if self.shrunk.as_ref().is_some_and(|slot| slot.size == (width, height)) {
                return Ok(());
            }
            if let Some(old) = self.shrunk.take() {
                gl.delete_framebuffer(old.framebuffer);
                gl.delete_texture(old.texture);
            }
            self.shrunk = Some(new_slot(gl, width, height, glow::LINEAR)?);
            Ok(())
        }
    }
}

unsafe fn upscale(state: &GlState, texture: glow::Texture, size: (i32, i32), levels: f32) {
    let Some(blend) = state.blend.as_ref() else {
        return;
    };
    unsafe {
        let gl = &state.gl;
        gl.bind_framebuffer(glow::FRAMEBUFFER, None);
        gl.viewport(0, 0, size.0, size.1);
        gl.use_program(Some(blend.program));
        gl.uniform_2_f32(blend.scale.as_ref(), 1.0, 1.0);
        gl.uniform_2_f32(blend.translate.as_ref(), 0.0, 0.0);
        gl.uniform_1_i32(blend.turns.as_ref(), 0);
        gl.uniform_4_f32(blend.crop.as_ref(), 0.0, 1.0, 1.0, -1.0);
        gl.uniform_1_f32(blend.weight.as_ref(), 0.0);
        gl.uniform_1_f32(blend.dither.as_ref(), levels);
        gl.active_texture(glow::TEXTURE0);
        gl.bind_texture(glow::TEXTURE_2D, Some(texture));
        gl.active_texture(glow::TEXTURE1);
        gl.bind_texture(glow::TEXTURE_2D, Some(texture));
        gl.bind_vertex_array(Some(state.vao));
        gl.draw_arrays(glow::TRIANGLE_STRIP, 0, 4);
        gl.active_texture(glow::TEXTURE0);
    }
}

unsafe fn new_slot(gl: &glow::Context, width: i32, height: i32, filter: u32) -> Result<BlendSlot, String> {
    unsafe {
        let texture = gl.create_texture()?;
        gl.bind_texture(glow::TEXTURE_2D, Some(texture));
        gl.tex_storage_2d(glow::TEXTURE_2D, 1, glow::RGB10_A2, width, height);
        gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_MIN_FILTER, filter as i32);
        gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_MAG_FILTER, filter as i32);
        gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_WRAP_S, glow::CLAMP_TO_EDGE as i32);
        gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_WRAP_T, glow::CLAMP_TO_EDGE as i32);
        let framebuffer = gl.create_framebuffer()?;
        gl.bind_framebuffer(glow::FRAMEBUFFER, Some(framebuffer));
        gl.framebuffer_texture_2d(glow::FRAMEBUFFER, glow::COLOR_ATTACHMENT0, glow::TEXTURE_2D, Some(texture), 0);
        let complete = gl.check_framebuffer_status(glow::FRAMEBUFFER) == glow::FRAMEBUFFER_COMPLETE;
        gl.bind_framebuffer(glow::FRAMEBUFFER, None);
        if !complete {
            gl.delete_framebuffer(framebuffer);
            gl.delete_texture(texture);
            return Err("blend target is incomplete".into());
        }
        Ok(BlendSlot { texture, framebuffer, size: (width, height), key: None, version: 0 })
    }
}

struct GlState {
    gl: glow::Context,
    programs: HashMap<i32, (glow::Program, Uniforms)>,
    vao: glow::VertexArray,
    textures: [glow::Texture; 3],
    sizes: [(i32, i32, u32); 3],
    externals: HashMap<i32, ExternalProgram>,
    external_texture: Option<glow::Texture>,
    image_functions: Option<ImageFunctions>,
    norm16: bool,
    name: String,
    meter: Option<Meter>,
    meter_failed: bool,
    dovi: Option<DoviTexture>,
    blend: Option<Blend>,
    blend_failed: bool,
    memc: Option<Memc>,
    memc_failed: bool,
    slot_version: u64,
}

fn gles_library() -> *mut c_void {
    static LIBRARY: OnceLock<usize> = OnceLock::new();
    *LIBRARY.get_or_init(|| unsafe {
        let mut handle = libc::dlopen(c"libGLESv3.so".as_ptr(), libc::RTLD_NOW | libc::RTLD_LOCAL);
        if handle.is_null() {
            handle = libc::dlopen(c"libGLESv2.so".as_ptr(), libc::RTLD_NOW | libc::RTLD_LOCAL);
        }
        handle as usize
    }) as *mut c_void
}

unsafe fn link(gl: &glow::Context, vertex_source: &str, fragment_source: &str) -> Result<glow::Program, String> {
    unsafe {
        let vertex = compile(gl, glow::VERTEX_SHADER, vertex_source)?;
        let fragment = match compile(gl, glow::FRAGMENT_SHADER, fragment_source) {
            Ok(shader) => shader,
            Err(error) => {
                gl.delete_shader(vertex);
                return Err(error);
            }
        };
        let program = gl.create_program()?;
        gl.attach_shader(program, vertex);
        gl.attach_shader(program, fragment);
        gl.link_program(program);
        gl.delete_shader(vertex);
        gl.delete_shader(fragment);
        if !gl.get_program_link_status(program) {
            let message = gl.get_program_info_log(program);
            gl.delete_program(program);
            return Err(message);
        }
        Ok(program)
    }
}

pub(crate) fn kernel_reach(kernel: i32) -> i32 {
    match kernel {
        9 | 12 | 14 => 3,
        10 | 13 | 18 | 19 | 20 => 4,
        3..=8 | 11 | 17 => 2,
        _ => 1,
    }
}

fn variant_key(kernel: i32, downscale: bool) -> i32 {
    kernel * 2 + downscale as i32
}

fn variant_source(source: &str, key: i32) -> String {
    let kernel = key / 2;
    shaders::specialize(source, kernel, key % 2 == 1, kernel_reach(kernel))
}

unsafe fn external_program(gl: &glow::Context, key: i32) -> Result<ExternalProgram, String> {
    unsafe {
        let source = variant_source(&format!("{}{}", shaders::EXTERNAL_PREAMBLE_ESSL3, shaders::EXTERNAL_BODY), key);
        let program = link(gl, shaders::VERTEX, &source)?;
        gl.use_program(Some(program));
        let sampler = gl.get_uniform_location(program, "u_tex0");
        gl.uniform_1_i32(sampler.as_ref(), 0);
        let uniform = |name: &str| gl.get_uniform_location(program, name);
        Ok(ExternalProgram {
            program,
            scale: uniform("u_scale"),
            translate: uniform("u_translate"),
            turns: uniform("u_turns"),
            crop: uniform("u_crop"),
            source_size: uniform("u_source_size"),
            transfer: uniform("u_transfer"),
            bt2020: uniform("u_bt2020"),
            peak: uniform("u_peak"),
            output: uniform("u_output"),
            ratio: uniform("u_ratio"),
            antiring: uniform("u_antiring"),
            tone: uniform("u_tone"),
            dither: uniform("u_dither"),
        })
    }
}

unsafe fn yuv_program(gl: &glow::Context, key: i32) -> Result<(glow::Program, Uniforms), String> {
    unsafe {
        let program = link(gl, shaders::VERTEX, &variant_source(shaders::FRAGMENT, key))?;
        gl.use_program(Some(program));
        for (index, name) in ["u_tex0", "u_tex1", "u_tex2", "u_dovi"].iter().enumerate() {
            let location = gl.get_uniform_location(program, name);
            gl.uniform_1_i32(location.as_ref(), index as i32);
        }
        let uniform = |name: &str| gl.get_uniform_location(program, name);
        let uniforms = Uniforms {
            crop: uniform("u_crop"),
            scale: uniform("u_scale"),
            translate: uniform("u_translate"),
            turns: uniform("u_turns"),
            layout: uniform("u_layout"),
            sample_scale: uniform("u_sample_scale"),
            matrix: uniform("u_matrix"),
            offset: uniform("u_offset"),
            transfer: uniform("u_transfer"),
            bt2020: uniform("u_bt2020"),
            peak: uniform("u_peak"),
            swap_uv: uniform("u_swap_uv"),
            output: uniform("u_output"),
            dovi_on: uniform("u_dovi_on"),
            ratio: uniform("u_ratio"),
            antiring: uniform("u_antiring"),
            tone: uniform("u_tone"),
            dither: uniform("u_dither"),
        };
        Ok((program, uniforms))
    }
}

unsafe fn capture_target(gl: &glow::Context, width: i32, height: i32) -> Option<(glow::Texture, glow::Framebuffer)> {
    unsafe {
        let texture = gl.create_texture().ok()?;
        gl.active_texture(glow::TEXTURE0);
        gl.bind_texture(glow::TEXTURE_2D, Some(texture));
        gl.tex_storage_2d(glow::TEXTURE_2D, 1, glow::RGBA8, width, height);
        gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_MIN_FILTER, glow::NEAREST as i32);
        gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_MAG_FILTER, glow::NEAREST as i32);
        let Ok(framebuffer) = gl.create_framebuffer() else {
            gl.delete_texture(texture);
            return None;
        };
        gl.bind_framebuffer(glow::FRAMEBUFFER, Some(framebuffer));
        gl.framebuffer_texture_2d(glow::FRAMEBUFFER, glow::COLOR_ATTACHMENT0, glow::TEXTURE_2D, Some(texture), 0);
        if gl.check_framebuffer_status(glow::FRAMEBUFFER) != glow::FRAMEBUFFER_COMPLETE {
            gl.bind_framebuffer(glow::FRAMEBUFFER, None);
            gl.delete_framebuffer(framebuffer);
            gl.delete_texture(texture);
            return None;
        }
        Some((texture, framebuffer))
    }
}

unsafe fn read_capture(gl: &glow::Context, framebuffer: glow::Framebuffer, width: i32, height: i32) -> Option<Vec<u8>> {
    unsafe {
        let size = width as usize * height as usize * 4;
        let buffer = gl.create_buffer().ok()?;
        gl.bind_buffer(glow::PIXEL_PACK_BUFFER, Some(buffer));
        gl.buffer_data_size(glow::PIXEL_PACK_BUFFER, size as i32, glow::STREAM_READ);
        gl.bind_framebuffer(glow::FRAMEBUFFER, Some(framebuffer));
        gl.read_pixels(0, 0, width, height, glow::RGBA, glow::UNSIGNED_BYTE, glow::PixelPackData::BufferOffset(0));
        let data = gl.map_buffer_range(glow::PIXEL_PACK_BUFFER, 0, size as i32, glow::MAP_READ_BIT);
        let mut pixels = None;
        if !data.is_null() {
            let raw = std::slice::from_raw_parts(data as *const u8, size);
            let row = width as usize * 4;
            let mut flipped = Vec::with_capacity(size);
            for y in (0..height as usize).rev() {
                flipped.extend_from_slice(&raw[y * row..(y + 1) * row]);
            }
            for pixel in flipped.chunks_exact_mut(4) {
                pixel[3] = 255;
            }
            gl.unmap_buffer(glow::PIXEL_PACK_BUFFER);
            pixels = Some(flipped);
        }
        gl.bind_buffer(glow::PIXEL_PACK_BUFFER, None);
        gl.delete_buffer(buffer);
        pixels
    }
}

unsafe fn compile(gl: &glow::Context, kind: u32, source: &str) -> Result<glow::Shader, String> {
    unsafe {
        let shader = gl.create_shader(kind)?;
        gl.shader_source(shader, source);
        gl.compile_shader(shader);
        if !gl.get_shader_compile_status(shader) {
            let message = gl.get_shader_info_log(shader);
            gl.delete_shader(shader);
            return Err(message);
        }
        Ok(shader)
    }
}

impl GlState {
    unsafe fn new() -> Result<Self, String> {
        unsafe {
            let library = gles_library();
            let gl = glow::Context::from_loader_function_cstr(|name| {
                let mut address = if library.is_null() { ptr::null_mut() } else { libc::dlsym(library, name.as_ptr()) };
                if address.is_null() {
                    address = eglGetProcAddress(name.as_ptr());
                }
                address as *const c_void
            });
            let mut programs = HashMap::new();
            programs.insert(0, yuv_program(&gl, 0)?);
            let vao = gl.create_vertex_array()?;
            let mut textures = Vec::with_capacity(3);
            for _ in 0..3 {
                let texture = gl.create_texture()?;
                gl.bind_texture(glow::TEXTURE_2D, Some(texture));
                gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_MIN_FILTER, glow::LINEAR as i32);
                gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_MAG_FILTER, glow::LINEAR as i32);
                gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_WRAP_S, glow::CLAMP_TO_EDGE as i32);
                gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_WRAP_T, glow::CLAMP_TO_EDGE as i32);
                textures.push(texture);
            }
            let norm16 = gl.supported_extensions().contains("GL_EXT_texture_norm16");
            let mut externals = HashMap::new();
            let external_texture = match external_program(&gl, 0) {
                Ok(program) => {
                    externals.insert(0, program);
                    let texture = gl.create_texture()?;
                    gl.bind_texture(GL_TEXTURE_EXTERNAL_OES, Some(texture));
                    gl.tex_parameter_i32(GL_TEXTURE_EXTERNAL_OES, glow::TEXTURE_MIN_FILTER, glow::LINEAR as i32);
                    gl.tex_parameter_i32(GL_TEXTURE_EXTERNAL_OES, glow::TEXTURE_MAG_FILTER, glow::LINEAR as i32);
                    gl.tex_parameter_i32(GL_TEXTURE_EXTERNAL_OES, glow::TEXTURE_WRAP_S, glow::CLAMP_TO_EDGE as i32);
                    gl.tex_parameter_i32(GL_TEXTURE_EXTERNAL_OES, glow::TEXTURE_WRAP_T, glow::CLAMP_TO_EDGE as i32);
                    Some(texture)
                }
                Err(error) => {
                    log::warn!("external texture rendering unavailable: {error}");
                    None
                }
            };
            let image_functions = ImageFunctions::load();
            if image_functions.is_none() {
                log::warn!("EGL image import is unavailable");
            }
            let version = gl.version();
            let renderer = gl.get_parameter_string(glow::RENDERER);
            let name = format!("OpenGL ES {}.{} · {}", version.major, version.minor, renderer.trim());
            log::info!("renderer ready: {name} (norm16: {norm16})");
            Ok(Self {
                gl,
                programs,
                vao,
                textures: [textures[0], textures[1], textures[2]],
                sizes: [(0, 0, 0); 3],
                externals,
                external_texture,
                image_functions,
                norm16,
                name,
                meter: None,
                meter_failed: false,
                dovi: None,
                blend: None,
                blend_failed: false,
                memc: None,
                memc_failed: false,
                slot_version: 0,
            })
        }
    }

    unsafe fn yuv_variant(&mut self, key: i32) -> (glow::Program, Uniforms) {
        if !self.programs.contains_key(&key) {
            match unsafe { yuv_program(&self.gl, key) } {
                Ok(program) => {
                    self.programs.insert(key, program);
                }
                Err(error) => {
                    log::warn!("scaler variant {key} failed to compile: {error}");
                    let fallback = self.programs[&0].clone();
                    self.programs.insert(key, fallback);
                }
            }
        }
        self.programs[&key].clone()
    }

    unsafe fn external_variant(&mut self, key: i32) -> Option<ExternalProgram> {
        if !self.externals.contains_key(&key) {
            match unsafe { external_program(&self.gl, key) } {
                Ok(program) => {
                    self.externals.insert(key, program);
                }
                Err(error) => {
                    log::warn!("external scaler variant {key} failed to compile: {error}");
                    let fallback = self.externals.get(&0)?.clone();
                    self.externals.insert(key, fallback);
                }
            }
        }
        self.externals.get(&key).cloned()
    }

    unsafe fn meter(&mut self) -> Option<&mut Meter> {
        if self.meter.is_none() && !self.meter_failed {
            match unsafe { Meter::new(&self.gl) } {
                Ok(meter) => self.meter = Some(meter),
                Err(error) => {
                    log::warn!("adaptive tone mapping unavailable: {error}");
                    self.meter_failed = true;
                }
            }
        }
        self.meter.as_mut()
    }

    unsafe fn update_dovi(&mut self, source: Option<&[u8]>) -> Option<f32> {
        unsafe {
            let gl = &self.gl;
            if let Some(bytes) = source {
                let unchanged = self.dovi.as_ref().is_some_and(|dovi| dovi.source == bytes);
                if !unchanged {
                    if let Some(table) = dovi_table(bytes) {
                        let texture = match self.dovi.as_ref() {
                            Some(dovi) => dovi.texture,
                            None => {
                                let texture = gl.create_texture().ok()?;
                                gl.active_texture(glow::TEXTURE3);
                                gl.bind_texture(glow::TEXTURE_2D, Some(texture));
                                gl.tex_storage_2d(glow::TEXTURE_2D, 1, glow::RGBA32F, DOVI_WIDTH, DOVI_HEIGHT);
                                gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_MIN_FILTER, glow::NEAREST as i32);
                                gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_MAG_FILTER, glow::NEAREST as i32);
                                texture
                            }
                        };
                        gl.active_texture(glow::TEXTURE3);
                        gl.bind_texture(glow::TEXTURE_2D, Some(texture));
                        gl.pixel_store_i32(glow::UNPACK_ALIGNMENT, 4);
                        gl.tex_sub_image_2d(
                            glow::TEXTURE_2D,
                            0,
                            0,
                            0,
                            DOVI_WIDTH,
                            DOVI_HEIGHT,
                            glow::RGBA,
                            glow::FLOAT,
                            glow::PixelUnpackData::Slice(Some(std::slice::from_raw_parts(
                                table.texels.as_ptr() as *const u8,
                                table.texels.len() * 4,
                            ))),
                        );
                        self.dovi = Some(DoviTexture { texture, source: bytes.to_vec(), peak_nits: table.peak_nits });
                    }
                }
            }
            let dovi = self.dovi.as_ref()?;
            gl.active_texture(glow::TEXTURE3);
            gl.bind_texture(glow::TEXTURE_2D, Some(dovi.texture));
            Some(dovi.peak_nits)
        }
    }

    unsafe fn tone_peak(&mut self, video: &VideoFrame, static_peak: f32, measure: bool) -> f32 {
        if !measure {
            return static_peak;
        }
        let serial = video.serial;
        let gl = &self.gl;
        let measured = match self.meter.as_mut() {
            Some(meter) => unsafe { meter.collect(gl, serial) },
            None => None,
        };
        measured.unwrap_or(static_peak)
    }

    unsafe fn upload(&mut self, index: usize, frame: &Frame, plan: &Plan) -> bool {
        unsafe {
            let gl = &self.gl;
            let (width, height) = if index == 0 || plan.layout == Layout::Rgba {
                (frame.width(), frame.height())
            } else {
                (
                    -((-frame.width()) >> plan.chroma_w),
                    -((-frame.height()) >> plan.chroma_h),
                )
            };
            let (internal, format, kind, texel) = match (plan.layout, index, plan.wide) {
                (Layout::Rgba, _, _) => (glow::RGBA8, glow::RGBA, glow::UNSIGNED_BYTE, 4),
                (Layout::SemiPlanar, 1, false) => (glow::RG8, glow::RG, glow::UNSIGNED_BYTE, 2),
                (Layout::SemiPlanar, 1, true) => (glow::RG16, glow::RG, glow::UNSIGNED_SHORT, 4),
                (_, _, false) => (glow::R8, glow::RED, glow::UNSIGNED_BYTE, 1),
                (_, _, true) => (glow::R16, glow::RED, glow::UNSIGNED_SHORT, 2),
            };
            let (data, linesize) = frame.plane(index);
            if data.is_null() || linesize <= 0 || linesize % texel != 0 || width <= 0 || height <= 0 {
                return false;
            }
            gl.active_texture(glow::TEXTURE0 + index as u32);
            gl.bind_texture(glow::TEXTURE_2D, Some(self.textures[index]));
            if self.sizes[index] != (width, height, internal) {
                gl.tex_image_2d(
                    glow::TEXTURE_2D,
                    0,
                    internal as i32,
                    width,
                    height,
                    0,
                    format,
                    kind,
                    glow::PixelUnpackData::Slice(None),
                );
                self.sizes[index] = (width, height, internal);
            }
            gl.pixel_store_i32(glow::UNPACK_ALIGNMENT, 1);
            gl.pixel_store_i32(glow::UNPACK_ROW_LENGTH, linesize / texel);
            let length = linesize as usize * (height as usize - 1) + width as usize * texel as usize;
            let bytes = std::slice::from_raw_parts(data, length);
            gl.tex_sub_image_2d(
                glow::TEXTURE_2D,
                0,
                0,
                0,
                width,
                height,
                format,
                kind,
                glow::PixelUnpackData::Slice(Some(bytes)),
            );
            gl.pixel_store_i32(glow::UNPACK_ROW_LENGTH, 0);
            true
        }
    }
}

fn choose_config(display: EGLDisplay, color_bits: EGLint, alpha_bits: EGLint) -> Option<EGLConfig> {
    unsafe {
        let attributes = [
            EGL_RENDERABLE_TYPE,
            EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE,
            EGL_WINDOW_BIT,
            EGL_RED_SIZE,
            color_bits,
            EGL_GREEN_SIZE,
            color_bits,
            EGL_BLUE_SIZE,
            color_bits,
            EGL_ALPHA_SIZE,
            alpha_bits,
            EGL_DEPTH_SIZE,
            0,
            EGL_NONE,
        ];
        let mut configs = [ptr::null_mut(); 32];
        let mut count = 0;
        if eglChooseConfig(display, attributes.as_ptr(), configs.as_mut_ptr(), configs.len() as EGLint, &mut count) == 0 {
            return None;
        }
        let size = |config: EGLConfig, attribute: EGLint| {
            let mut value = 0;
            eglGetConfigAttrib(display, config, attribute, &mut value);
            value
        };
        let found = &configs[..count.clamp(0, configs.len() as EGLint) as usize];
        found
            .iter()
            .copied()
            .find(|config| {
                size(*config, EGL_RED_SIZE) == color_bits
                    && size(*config, EGL_GREEN_SIZE) == color_bits
                    && size(*config, EGL_BLUE_SIZE) == color_bits
                    && size(*config, EGL_ALPHA_SIZE) == alpha_bits
            })
            .or_else(|| if color_bits == 8 { found.first().copied() } else { None })
    }
}

fn kernel_radius(kernel: i32) -> f32 {
    match kernel {
        2 | 16 => 1.0,
        15 => 0.5,
        9 | 12 | 14 => 3.0,
        10 | 13 => 4.0,
        _ => 2.0,
    }
}

pub(crate) fn kernel_cost(kernel: i32, downscale: bool, ratio: [f32; 2]) -> f32 {
    if kernel == 0 || kernel == 1 {
        return 1.0;
    }
    if downscale {
        let axis = |r: f32| (2.0 * kernel_radius(kernel) * r.max(1.0) * 0.75).ceil().clamp(2.0, 8.0);
        return axis(ratio[0]) * axis(ratio[1]);
    }
    match kernel {
        2 | 15 | 16 => 4.0,
        9 | 12 | 14 => 36.0,
        10 | 13 => 64.0,
        17 => 20.0,
        18 => 110.0,
        19 => 100.0,
        20 => 128.0,
        _ => 16.0,
    }
}

fn step_down(kernel: i32, downscale: bool) -> i32 {
    if downscale {
        return match kernel {
            13 => 12,
            12 => 11,
            10 => 9,
            9 => 8,
            11 | 8 | 3 | 4 | 5 | 7 | 14 => 6,
            6 | 2 => 16,
            16 => 15,
            _ => 0,
        };
    }
    match kernel {
        13 => 12,
        12 | 14 => 11,
        10 => 9,
        9 => 8,
        20 => 19,
        19 => 18,
        18 => 17,
        17 => 5,
        3..=8 | 11 => 2,
        _ => 0,
    }
}

pub(crate) fn limit_kernel(kernel: i32, downscale: bool, ratio: [f32; 2], cap: f32) -> i32 {
    let mut chosen = kernel;
    while chosen != 0 && kernel_cost(chosen, downscale, ratio) > cap {
        chosen = step_down(chosen, downscale);
    }
    chosen
}

pub(crate) struct Scaling {
    pub(crate) kernel: i32,
    pub(crate) downscale: bool,
    pub(crate) ratio: [f32; 2],
    pub(crate) direction: u8,
}

impl Scaling {
    pub(crate) fn summary(&self) -> ActiveScaling {
        ActiveScaling {
            kernel: self.kernel,
            direction: self.direction,
            cost: kernel_cost(self.kernel, self.downscale, self.ratio),
        }
    }
}

pub(crate) struct Placement {
    pub(crate) scale: [f32; 2],
    pub(crate) translate: [f32; 2],
    pub(crate) turns: i32,
}

#[derive(Clone, Copy, Default)]
struct SurfaceCaps {
    pq: bool,
    pq_by_dataspace: bool,
    p3: bool,
    p3_passthrough: bool,
    light_metadata: bool,
    no_config: bool,
}

pub struct GlesPresenter {
    display: EGLDisplay,
    config: EGLConfig,
    hdr_config: EGLConfig,
    context_config: EGLConfig,
    caps: SurfaceCaps,
    options: DisplayOptions,
    output: Output,
    blocked: Vec<Output>,
    light_metadata: (f32, f32),
    tagged_dataspace: bool,
    context: EGLContext,
    surface: EGLSurface,
    window: Option<NativeWindow>,
    state: Option<GlState>,
    scaler: Scaler,
    failed: bool,
    transform: ViewTransform,
    scaler_mode: ScalerMode,
    downscaler_mode: DownscalerMode,
    antiring: f32,
    quality_cap: f32,
    active_scaling: ActiveScaling,
    draw_target: Option<glow::Framebuffer>,
    offscreen_dither: i32,
    presentation_time: Option<PresentationTimeFn>,
    target_ns: Option<i64>,
    motion: MotionConfig,
}

unsafe impl Send for GlesPresenter {}

impl Default for GlesPresenter {
    fn default() -> Self {
        Self::new()
    }
}

impl GlesPresenter {
    pub fn new() -> Self {
        Self {
            display: EGL_NO_DISPLAY,
            config: ptr::null_mut(),
            hdr_config: ptr::null_mut(),
            context_config: ptr::null_mut(),
            caps: SurfaceCaps::default(),
            options: DisplayOptions::default(),
            output: Output::Srgb,
            blocked: Vec::new(),
            light_metadata: (0.0, 0.0),
            tagged_dataspace: false,
            context: EGL_NO_CONTEXT,
            surface: EGL_NO_SURFACE,
            window: None,
            state: None,
            scaler: Scaler::default(),
            failed: false,
            transform: ViewTransform::default(),
            scaler_mode: ScalerMode::BILINEAR,
            downscaler_mode: DownscalerMode::BILINEAR,
            antiring: 0.6,
            quality_cap: f32::INFINITY,
            active_scaling: ActiveScaling::default(),
            draw_target: None,
            offscreen_dither: 0,
            presentation_time: None,
            target_ns: None,
            motion: MotionConfig::default(),
        }
    }

    fn invalidate_slots(&mut self) {
        if let Some(blend) = self.state.as_mut().and_then(|state| state.blend.as_mut()) {
            for slot in blend.slots.iter_mut().flatten() {
                slot.key = None;
            }
        }
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

    pub fn set_antiring_strength(&mut self, strength: f32) {
        if strength != self.antiring {
            self.invalidate_slots();
        }
        self.antiring = strength;
    }

    pub fn set_quality_cap_level(&mut self, cap: f32) {
        if cap != self.quality_cap {
            self.invalidate_slots();
        }
        self.quality_cap = cap;
    }

    pub fn last_scaling(&self) -> ActiveScaling {
        self.active_scaling
    }

    pub fn set_display_options(&mut self, options: DisplayOptions) {
        if options != self.options {
            self.invalidate_slots();
        }
        self.options = options;
    }

    pub fn output(&self) -> i32 {
        self.output as i32
    }

    fn dither_bits(&self) -> i32 {
        if self.draw_target.is_some() {
            self.offscreen_dither
        } else if self.output == Output::Pq {
            10
        } else {
            8
        }
    }

    fn dither_levels(&self) -> f32 {
        if self.output == Output::Pq { 1023.0 } else { 255.0 }
    }

    fn tone_target(&self) -> ToneTarget {
        ToneTarget::new(self.output, &self.options.luminance)
    }

    fn supports(&self, output: Output) -> bool {
        if self.blocked.contains(&output) {
            return false;
        }
        match output {
            Output::Srgb => true,
            Output::P3 => self.caps.p3_passthrough,
            Output::P3Linear => self.caps.p3 && !self.caps.p3_passthrough,
            Output::Pq => self.caps.pq || self.caps.pq_by_dataspace,
        }
    }

    fn wanted_output(&self, video: &VideoFrame) -> Output {
        output_candidates(&self.options, &video.color)
            .into_iter()
            .find(|output| self.supports(*output))
            .unwrap_or(Output::Srgb)
    }

    pub fn present_paced(&mut self, frame: &VideoFrame, delay_us: i64) -> bool {
        if self.presentation_time.is_some() && delay_us > 0 {
            self.target_ns = Some(monotonic_ns() + delay_us * 1_000);
        }
        let presented = self.present(frame);
        self.target_ns = None;
        presented
    }

    pub(crate) fn scaling(
        up: ScalerMode,
        down: DownscalerMode,
        cap: f32,
        video: &VideoFrame,
        placement: &Placement,
        surface_width: i32,
        surface_height: i32,
    ) -> Scaling {
        let shown_width = placement.scale[0].abs() as f64 * surface_width as f64;
        let shown_height = placement.scale[1].abs() as f64 * surface_height as f64;
        let (along_x, along_y) = if placement.turns % 2 == 1 { (shown_height, shown_width) } else { (shown_width, shown_height) };
        let ratio_x = video.width().max(1) as f64 / along_x.max(1.0);
        let ratio_y = video.height().max(1) as f64 / along_y.max(1.0);
        let ratio = [ratio_x.max(1.0) as f32, ratio_y.max(1.0) as f32];
        if ratio_x < 1.0 / 1.02 || ratio_y < 1.0 / 1.02 {
            Scaling { kernel: limit_kernel(up.code(), false, ratio, cap), downscale: false, ratio, direction: 1 }
        } else if ratio_x > 1.02 || ratio_y > 1.02 {
            Scaling { kernel: limit_kernel(down.code(), true, ratio, cap), downscale: true, ratio, direction: 2 }
        } else {
            Scaling { kernel: 0, downscale: false, ratio, direction: 0 }
        }
    }

    fn init_display(&mut self) -> Result<(), String> {
        if self.display != EGL_NO_DISPLAY {
            return Ok(());
        }
        unsafe {
            let display = eglGetDisplay(ptr::null_mut());
            if display == EGL_NO_DISPLAY {
                return Err("no EGL display".into());
            }
            let (mut major, mut minor) = (0, 0);
            if eglInitialize(display, &mut major, &mut minor) == 0 {
                return Err(format!("eglInitialize failed: {:#x}", error()));
            }
            self.display = display;
            self.presentation_time = load_presentation_time(display);
            self.config = choose_config(display, 8, 8).ok_or_else(|| format!("no OpenGL ES 3 config: {:#x}", error()))?;
            self.hdr_config = choose_config(display, 10, 2).unwrap_or(ptr::null_mut());
            let names = extensions(display);
            let has = |name: &str| names.split_whitespace().any(|e| e == name);
            self.caps = SurfaceCaps {
                pq: has("EGL_EXT_gl_colorspace_bt2020_pq") && !self.hdr_config.is_null(),
                pq_by_dataspace: !self.hdr_config.is_null() && NativeWindow::can_set_data_space(),
                p3: has("EGL_EXT_gl_colorspace_display_p3"),
                p3_passthrough: has("EGL_EXT_gl_colorspace_display_p3_passthrough"),
                light_metadata: has("EGL_EXT_surface_CTA861_3_metadata"),
                no_config: has("EGL_KHR_no_config_context"),
            };
            log::info!(
                "EGL surfaces: HDR10 {} (dataspace {}), Display P3 {}, P3 passthrough {}",
                self.caps.pq,
                self.caps.pq_by_dataspace,
                self.caps.p3,
                self.caps.p3_passthrough
            );
        }
        Ok(())
    }

    fn ensure_context(&mut self, config: EGLConfig) -> Result<(), String> {
        if self.context != EGL_NO_CONTEXT {
            if self.caps.no_config || self.context_config == config {
                return Ok(());
            }
            self.destroy_context();
        }
        unsafe {
            let context_attributes = [EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE];
            let shared_config = if self.caps.no_config { EGL_NO_CONFIG_KHR } else { config };
            let mut context = eglCreateContext(self.display, shared_config, EGL_NO_CONTEXT, context_attributes.as_ptr());
            if context == EGL_NO_CONTEXT && self.caps.no_config {
                self.caps.no_config = false;
                context = eglCreateContext(self.display, config, EGL_NO_CONTEXT, context_attributes.as_ptr());
            }
            if context == EGL_NO_CONTEXT {
                return Err(format!("eglCreateContext failed: {:#x}", error()));
            }
            self.context = context;
            self.context_config = config;
        }
        Ok(())
    }

    fn attach(&mut self, window: NativeWindow, output: Output) -> Result<(), String> {
        self.init_display()?;
        let output = if self.supports(output) { output } else { Output::Srgb };
        let config = if output == Output::Pq { self.hdr_config } else { self.config };
        self.ensure_context(config)?;
        unsafe {
            let mut visual = 0;
            eglGetConfigAttrib(self.display, config, EGL_NATIVE_VISUAL_ID, &mut visual);
            window.set_buffers_format(visual);
            if self.tagged_dataspace {
                window.set_data_space(DATASPACE_UNKNOWN);
                self.tagged_dataspace = false;
            }
            let tag_pq = output == Output::Pq && !self.caps.pq;
            let colorspace = match output {
                Output::Srgb => None,
                Output::P3 => Some(EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT),
                Output::P3Linear => Some(EGL_GL_COLORSPACE_DISPLAY_P3_EXT),
                Output::Pq if tag_pq => None,
                Output::Pq => Some(EGL_GL_COLORSPACE_BT2020_PQ_EXT),
            };
            let attributes = match colorspace {
                Some(space) => vec![EGL_GL_COLORSPACE_KHR, space, EGL_NONE],
                None => vec![EGL_NONE],
            };
            let surface = eglCreateWindowSurface(self.display, config, window.as_ptr() as *mut c_void, attributes.as_ptr());
            if surface == EGL_NO_SURFACE {
                let code = error();
                if output != Output::Srgb {
                    log::warn!("cannot create a {output:?} surface ({code:#x}), using sRGB");
                    self.blocked.push(output);
                    return self.attach(window, Output::Srgb);
                }
                return Err(format!("eglCreateWindowSurface failed: {code:#x}"));
            }
            if tag_pq {
                if !window.set_data_space(DATASPACE_BT2020_PQ) {
                    log::warn!("the window refused the HDR10 dataspace, using sRGB");
                    eglDestroySurface(self.display, surface);
                    window.set_data_space(DATASPACE_UNKNOWN);
                    self.blocked.push(Output::Pq);
                    return self.attach(window, Output::Srgb);
                }
                self.tagged_dataspace = true;
            }
            if eglMakeCurrent(self.display, surface, surface, self.context) == 0 {
                eglDestroySurface(self.display, surface);
                return Err(format!("eglMakeCurrent failed: {:#x}", error()));
            }
            eglSwapInterval(self.display, 1);
            self.surface = surface;
            self.window = Some(window);
            self.output = output;
            self.light_metadata = (0.0, 0.0);
            if output != Output::Srgb {
                log::info!("rendering to a {output:?} surface");
            }
            if self.state.is_none() {
                self.state = Some(GlState::new()?);
            }
        }
        Ok(())
    }

    fn switch_output(&mut self, wanted: Output) -> bool {
        let Some(window) = self.window.clone() else {
            return false;
        };
        self.detach();
        if let Err(message) = self.attach(window, wanted) {
            log::error!("cannot switch the output surface: {message}");
            self.detach();
            self.failed = true;
            return false;
        }
        true
    }

    fn apply_light_metadata(&mut self, video: &VideoFrame) {
        if self.output != Output::Pq || !self.caps.light_metadata {
            return;
        }
        let tone = self.tone_target();
        let peak = if tone.maps_to_display() {
            tone.display_nits(content_peak(None, &video.color.with_scene_nits(0.0), tone.hlg_nits(Output::Pq)))
        } else {
            video.color.peak_nits
        };
        let average = if peak > 0.0 { video.color.average_nits.min(peak) } else { video.color.average_nits };
        let wanted = (peak, average);
        if wanted == self.light_metadata || wanted.0 <= 0.0 {
            return;
        }
        self.light_metadata = wanted;
        unsafe {
            let scale = |nits: f32| (nits.min(40000.0) * EGL_METADATA_SCALING_EXT) as EGLint;
            eglSurfaceAttrib(self.display, self.surface, EGL_CTA861_3_MAX_CONTENT_LIGHT_LEVEL_EXT, scale(wanted.0));
            if wanted.1 > 0.0 {
                eglSurfaceAttrib(self.display, self.surface, EGL_CTA861_3_MAX_FRAME_AVERAGE_LEVEL_EXT, scale(wanted.1));
            }
        }
    }

    fn detach(&mut self) {
        unsafe {
            if self.display != EGL_NO_DISPLAY {
                eglMakeCurrent(self.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            }
            if self.surface != EGL_NO_SURFACE {
                eglDestroySurface(self.display, self.surface);
                self.surface = EGL_NO_SURFACE;
            }
        }
        if self.tagged_dataspace {
            if let Some(window) = &self.window {
                window.set_data_space(DATASPACE_UNKNOWN);
            }
            self.tagged_dataspace = false;
        }
        self.window = None;
    }

    fn destroy_context(&mut self) {
        self.detach();
        self.state = None;
        unsafe {
            if self.context != EGL_NO_CONTEXT {
                eglDestroyContext(self.display, self.context);
                self.context = EGL_NO_CONTEXT;
            }
        }
    }

    fn surface_size(&self) -> (i32, i32) {
        if let Some(window) = &self.window {
            let (width, height) = (window.width(), window.height());
            if width > 0 && height > 0 {
                return (width, height);
            }
        }
        let (mut width, mut height) = (0, 0);
        unsafe {
            eglQuerySurface(self.display, self.surface, EGL_WIDTH, &mut width);
            eglQuerySurface(self.display, self.surface, EGL_HEIGHT, &mut height);
        }
        (width, height)
    }

    pub(crate) fn geometry(video: &VideoFrame, surface_width: i32, surface_height: i32, transform: &ViewTransform) -> Placement {
        let turns = ((video.rotation.rem_euclid(360) / 90) + transform.turns).rem_euclid(4);
        let mut source = video.display_aspect();
        if !source.is_finite() || source <= 0.0 {
            source = 1.0;
        }
        let aspect = if transform.aspect > 0.0 {
            transform.aspect as f64
        } else if turns % 2 == 1 {
            1.0 / source
        } else {
            source
        };
        let width = surface_width.max(1) as f64;
        let height = surface_height.max(1) as f64;
        let surface_aspect = width / height;
        let (mut scale_x, mut scale_y) = match transform.fit {
            FitMode::Fit => {
                if aspect > surface_aspect {
                    (1.0, surface_aspect / aspect)
                } else {
                    (aspect / surface_aspect, 1.0)
                }
            }
            FitMode::Fill => {
                if aspect > surface_aspect {
                    (aspect / surface_aspect, 1.0)
                } else {
                    (1.0, surface_aspect / aspect)
                }
            }
            FitMode::Stretch => (1.0, 1.0),
            FitMode::Original => {
                let picture_height = video.height().max(1) as f64;
                let picture_width = picture_height * source;
                let shown_height = if turns % 2 == 1 { picture_width } else { picture_height };
                let shown_width = if transform.aspect > 0.0 {
                    shown_height * aspect
                } else if turns % 2 == 1 {
                    picture_height
                } else {
                    picture_width
                };
                (shown_width / width, shown_height / height)
            }
        };
        let zoom = if transform.zoom.is_finite() { (transform.zoom as f64).clamp(0.1, 12.0) } else { 1.0 };
        let zoom_x = if transform.zoom_x.is_finite() { (transform.zoom_x as f64).clamp(0.1, 12.0) } else { 1.0 };
        let zoom_y = if transform.zoom_y.is_finite() { (transform.zoom_y as f64).clamp(0.1, 12.0) } else { 1.0 };
        scale_x *= zoom * zoom_x;
        scale_y *= zoom * zoom_y;
        let limit_x = (scale_x - 1.0).max(0.0);
        let limit_y = (scale_y - 1.0).max(0.0);
        let translate_x = if transform.pan_x.is_finite() { (transform.pan_x as f64).clamp(-limit_x, limit_x) } else { 0.0 };
        let translate_y = if transform.pan_y.is_finite() { (transform.pan_y as f64).clamp(-limit_y, limit_y) } else { 0.0 };
        if transform.mirror {
            scale_x = -scale_x;
        }
        Placement {
            scale: [scale_x as f32, scale_y as f32],
            translate: [translate_x as f32, translate_y as f32],
            turns,
        }
    }

    fn draw(&mut self, video: &VideoFrame) -> bool {
        let wanted = self.wanted_output(video);
        if wanted != self.output && !self.switch_output(wanted) {
            return false;
        }
        self.apply_light_metadata(video);
        let (width, height) = self.surface_size();
        let drawn = match &video.data {
            FrameData::Software(frame) => self.draw_software(video, frame, width, height),
            FrameData::Hardware(frame) => self.draw_hardware(video, frame.as_ref(), width, height),
        };
        drawn && self.swap()
    }

    pub fn supports_blend(&self) -> bool {
        self.surface != EGL_NO_SURFACE && !self.failed && self.state.as_ref().is_some_and(|state| !state.blend_failed)
    }

    fn cache_frame(&mut self, video: &VideoFrame, keep: &[usize], width: i32, height: i32) -> Option<usize> {
        let key = (video.serial, video.pts_us);
        {
            let state = self.state.as_mut()?;
            if state.blend.is_none() && !state.blend_failed {
                match unsafe { Blend::new(&state.gl) } {
                    Ok(blend) => state.blend = Some(blend),
                    Err(error) => {
                        log::warn!("smooth motion unavailable: {error}");
                        state.blend_failed = true;
                    }
                }
            }
            let blend = state.blend.as_mut()?;
            if let Some(index) = (0..3).find(|i| {
                blend.slots[*i].as_ref().is_some_and(|slot| slot.key == Some(key) && slot.size == (width, height))
            }) {
                return Some(index);
            }
        }
        let index = {
            let blend = self.state.as_ref()?.blend.as_ref()?;
            let age = |index: usize| blend.slots[index].as_ref().filter(|slot| slot.key.is_some()).map_or(0, |slot| slot.version + 1);
            (0..3).filter(|index| !keep.contains(index)).min_by_key(|index| age(*index))?
        };
        let framebuffer = {
            let state = self.state.as_mut()?;
            let gl = &state.gl;
            let blend = state.blend.as_mut()?;
            if let Err(error) = unsafe { blend.ensure_slot(gl, index, width, height) } {
                log::warn!("smooth motion unavailable: {error}");
                state.blend_failed = true;
                return None;
            }
            let slot = blend.slots[index].as_mut()?;
            slot.key = None;
            slot.framebuffer
        };
        self.draw_target = Some(framebuffer);
        let drawn = match &video.data {
            FrameData::Software(frame) => self.draw_software(video, frame, width, height),
            FrameData::Hardware(frame) => self.draw_hardware(video, frame.as_ref(), width, height),
        };
        self.draw_target = None;
        let state = self.state.as_mut()?;
        unsafe { state.gl.bind_framebuffer(glow::FRAMEBUFFER, None) };
        if !drawn {
            return None;
        }
        state.slot_version += 1;
        let version = state.slot_version;
        let slot = state.blend.as_mut()?.slots[index].as_mut()?;
        slot.key = Some(key);
        slot.version = version;
        Some(index)
    }

    fn cached_slot(&self, video: &VideoFrame, width: i32, height: i32) -> Option<usize> {
        let key = (video.serial, video.pts_us);
        let blend = self.state.as_ref()?.blend.as_ref()?;
        (0..3).find(|i| blend.slots[*i].as_ref().is_some_and(|slot| slot.key == Some(key) && slot.size == (width, height)))
    }

    pub fn set_motion_config(&mut self, config: MotionConfig) {
        if config.level != self.motion.level {
            if let Some(memc) = self.state.as_mut().and_then(|state| state.memc.as_mut()) {
                memc.reset_motion();
            }
        }
        self.motion = config;
    }

    fn video_rect(&self, video: &VideoFrame, width: i32, height: i32) -> [f32; 4] {
        let placement = Self::geometry(video, width, height, &self.transform);
        let (sx, sy) = (placement.scale[0].abs(), placement.scale[1].abs());
        let (tx, ty) = (placement.translate[0], -placement.translate[1]);
        let x0 = ((tx - sx + 1.0) * 0.5 * width as f32).clamp(0.0, width as f32);
        let x1 = ((tx + sx + 1.0) * 0.5 * width as f32).clamp(0.0, width as f32);
        let y0 = ((ty - sy + 1.0) * 0.5 * height as f32).clamp(0.0, height as f32);
        let y1 = ((ty + sy + 1.0) * 0.5 * height as f32).clamp(0.0, height as f32);
        [x0, y0, x1, y1]
    }

    fn ensure_memc(&mut self) -> bool {
        let Some(state) = self.state.as_mut() else {
            return false;
        };
        if state.memc.is_none() && !state.memc_failed {
            match unsafe { Memc::new(&state.gl) } {
                Ok(memc) => state.memc = Some(memc),
                Err(error) => {
                    log::warn!("motion interpolation unavailable: {error}");
                    state.memc_failed = true;
                }
            }
        }
        state.memc.is_some()
    }

    fn slot_frame(&self, index: usize) -> Option<MemcFrame> {
        let slot = self.state.as_ref()?.blend.as_ref()?.slots[index].as_ref()?;
        Some(MemcFrame { texture: slot.texture, version: slot.version })
    }

    fn restore_bindings(&self) {
        let Some(state) = self.state.as_ref() else {
            return;
        };
        unsafe {
            let gl = &state.gl;
            for (index, texture) in state.textures.iter().enumerate() {
                gl.active_texture(glow::TEXTURE0 + index as u32);
                gl.bind_texture(glow::TEXTURE_2D, Some(*texture));
            }
            gl.active_texture(glow::TEXTURE0);
            gl.bind_vertex_array(Some(state.vao));
        }
    }

    fn present_motion(&mut self, from_slot: usize, to_slot: usize, rect: [f32; 4], weight: f32, size: (i32, i32), levels: f32) -> Option<u32> {
        if !self.ensure_memc() {
            return None;
        }
        let (Some(a), Some(b)) = (self.slot_frame(from_slot), self.slot_frame(to_slot)) else {
            return None;
        };
        let params = MemcParams { level: self.motion.level };
        let out = scaled_size(size, self.motion.scale());
        let result = {
            let state = self.state.as_mut()?;
            let shrunk = if out != size {
                let blend = state.blend.as_mut()?;
                if let Err(error) = unsafe { blend.ensure_shrunk(&state.gl, out.0, out.1) } {
                    log::warn!("reduced motion target failed: {error}");
                    None
                } else {
                    blend.shrunk.as_ref().map(|slot| (slot.framebuffer, slot.texture))
                }
            } else {
                None
            };
            let memc = state.memc.as_mut()?;
            match shrunk {
                Some((framebuffer, texture)) => unsafe {
                    let rendered = memc.render(&state.gl, a, b, size, rect, weight, params, 0.0, Some(framebuffer), out);
                    if rendered.is_ok() {
                        upscale(state, texture, size, levels);
                    }
                    rendered
                },
                None => unsafe { memc.render(&state.gl, a, b, size, rect, weight, params, levels, None, size) },
            }
        };
        self.restore_bindings();
        match result {
            Ok(stages) => Some(stages),
            Err(error) => {
                log::warn!("motion interpolation failed: {error}");
                if let Some(state) = self.state.as_mut() {
                    state.memc_failed = true;
                }
                None
            }
        }
    }

    fn prepare_ahead(&mut self, from_slot: usize, to_slot: usize, ahead: Ahead<'_>, spent: u32, size: (i32, i32)) {
        if spent > 0 || self.motion.level == 0 || self.state.as_ref().is_none_or(|state| state.memc_failed) {
            return;
        }
        let params = MemcParams { level: self.motion.level };
        let cached = self.cached_slot(ahead.frame, size.0, size.1);
        if cached == Some(to_slot) || cached == Some(from_slot) {
            return;
        }
        let memc = self.state.as_ref().and_then(|state| state.memc.as_ref());
        let pending = match (cached.and_then(|slot| self.slot_frame(slot)), self.slot_frame(to_slot), memc) {
            (Some(next), Some(to), Some(memc)) => memc.remaining(to.version, next.version),
            _ => 2,
        };
        let remaining = pending + cached.is_none() as u32;
        if remaining == 0 {
            return;
        }
        let mut budget = remaining.div_ceil(ahead.frames_left.max(1));
        let slot = match cached {
            Some(slot) => slot,
            None => {
                let Some(slot) = self.cache_frame(ahead.frame, &[to_slot, from_slot], size.0, size.1) else {
                    return;
                };
                budget -= 1;
                slot
            }
        };
        if budget == 0 || !self.ensure_memc() {
            return;
        }
        let (Some(a), Some(b), Some(c)) = (self.slot_frame(from_slot), self.slot_frame(to_slot), self.slot_frame(slot)) else {
            return;
        };
        let result = {
            let Some(state) = self.state.as_mut() else {
                return;
            };
            let Some(memc) = state.memc.as_mut() else {
                return;
            };
            unsafe { memc.advance(&state.gl, b, c, (a.version, b.version), size, params, budget) }
        };
        self.restore_bindings();
        if let Err(error) = result {
            log::warn!("motion interpolation failed: {error}");
            if let Some(state) = self.state.as_mut() {
                state.memc_failed = true;
            }
        }
    }

    pub fn check_duplicate_frames(&mut self, from: &VideoFrame, to: &VideoFrame) -> Option<bool> {
        if self.surface == EGL_NO_SURFACE || self.failed || !self.ensure_memc() {
            return None;
        }
        let (width, height) = self.surface_size();
        let holding = self.cached_slot(from, width, height);
        let to_slot = self.cache_frame(to, holding.as_slice(), width, height)?;
        let from_slot = self.cache_frame(from, &[to_slot], width, height)?;
        let (a, b) = (self.slot_frame(from_slot)?, self.slot_frame(to_slot)?);
        let result = {
            let state = self.state.as_mut()?;
            let memc = state.memc.as_mut()?;
            unsafe {
                if let Err(error) = memc.request_check(&state.gl, a, b, (width, height)) {
                    log::warn!("duplicate check failed: {error}");
                }
                memc.check_result(&state.gl, a.version, b.version)
            }
        };
        self.restore_bindings();
        result.map(|peak| peak < DUPLICATE_LIMIT)
    }

    pub fn present_blend(&mut self, from: &VideoFrame, to: &VideoFrame, weight: f32, delay_us: i64, ahead: Option<Ahead<'_>>) -> bool {
        if self.surface == EGL_NO_SURFACE || self.failed {
            return false;
        }
        let wanted = self.wanted_output(to);
        if wanted != self.output && !self.switch_output(wanted) {
            return false;
        }
        self.apply_light_metadata(to);
        let (width, height) = self.surface_size();
        let holding = self.cached_slot(from, width, height);
        let Some(to_slot) = self.cache_frame(to, holding.as_slice(), width, height) else {
            return self.present_paced(if weight >= 0.5 { to } else { from }, delay_us);
        };
        let from_slot = if weight < 0.999 { self.cache_frame(from, &[to_slot], width, height).unwrap_or(to_slot) } else { to_slot };
        if self.motion.level > 0 && from_slot != to_slot && weight > 0.002 && weight < 0.998 {
            let rect = self.video_rect(to, width, height);
            let levels = self.dither_levels();
            if let Some(spent) = self.present_motion(from_slot, to_slot, rect, weight, (width, height), levels) {
                if self.presentation_time.is_some() && delay_us > 0 {
                    self.target_ns = Some(monotonic_ns() + delay_us * 1_000);
                }
                let swapped = self.swap();
                self.target_ns = None;
                if let Some(ahead) = ahead {
                    self.prepare_ahead(from_slot, to_slot, ahead, spent, (width, height));
                }
                return swapped;
            }
        }
        let levels = self.dither_levels();
        let Some(state) = self.state.as_ref() else {
            return false;
        };
        let Some(blend) = state.blend.as_ref() else {
            return false;
        };
        let (Some(a), Some(b)) = (blend.slots[from_slot].as_ref(), blend.slots[to_slot].as_ref()) else {
            return false;
        };
        unsafe {
            let gl = &state.gl;
            gl.bind_framebuffer(glow::FRAMEBUFFER, None);
            gl.viewport(0, 0, width, height);
            gl.use_program(Some(blend.program));
            gl.uniform_2_f32(blend.scale.as_ref(), 1.0, 1.0);
            gl.uniform_2_f32(blend.translate.as_ref(), 0.0, 0.0);
            gl.uniform_1_i32(blend.turns.as_ref(), 0);
            gl.uniform_4_f32(blend.crop.as_ref(), 0.0, 1.0, 1.0, -1.0);
            gl.uniform_1_f32(blend.weight.as_ref(), weight.clamp(0.0, 1.0));
            gl.uniform_1_f32(blend.dither.as_ref(), levels);
            gl.active_texture(glow::TEXTURE0);
            gl.bind_texture(glow::TEXTURE_2D, Some(a.texture));
            gl.active_texture(glow::TEXTURE1);
            gl.bind_texture(glow::TEXTURE_2D, Some(b.texture));
            gl.bind_vertex_array(Some(state.vao));
            gl.draw_arrays(glow::TRIANGLE_STRIP, 0, 4);
            gl.active_texture(glow::TEXTURE0);
            for (index, texture) in state.textures.iter().enumerate().take(2) {
                gl.active_texture(glow::TEXTURE0 + index as u32);
                gl.bind_texture(glow::TEXTURE_2D, Some(*texture));
            }
            gl.active_texture(glow::TEXTURE0);
        }
        if self.presentation_time.is_some() && delay_us > 0 {
            self.target_ns = Some(monotonic_ns() + delay_us * 1_000);
        }
        let swapped = self.swap();
        self.target_ns = None;
        if let Some(ahead) = ahead {
            self.prepare_ahead(from_slot, to_slot, ahead, 0, (width, height));
        }
        swapped
    }

    pub fn capture_frame(&mut self, video: &VideoFrame, width: i32, height: i32) -> Option<Vec<u8>> {
        if self.surface == EGL_NO_SURFACE || self.failed || width <= 0 || height <= 0 {
            return None;
        }
        let (texture, framebuffer) = unsafe { capture_target(&self.state.as_ref()?.gl, width, height) }?;
        let saved = (self.transform, self.output, self.active_scaling);
        self.transform = ViewTransform::default();
        self.output = Output::Srgb;
        self.draw_target = Some(framebuffer);
        self.offscreen_dither = 8;
        let drawn = match &video.data {
            FrameData::Software(frame) => self.draw_software(video, frame, width, height),
            FrameData::Hardware(frame) => self.draw_hardware(video, frame.as_ref(), width, height),
        };
        self.offscreen_dither = 0;
        self.draw_target = None;
        (self.transform, self.output, self.active_scaling) = saved;
        let pixels = if drawn { unsafe { read_capture(&self.state.as_ref()?.gl, framebuffer, width, height) } } else { None };
        if let Some(state) = self.state.as_ref() {
            unsafe {
                state.gl.bind_framebuffer(glow::FRAMEBUFFER, None);
                state.gl.delete_framebuffer(framebuffer);
                state.gl.delete_texture(texture);
            }
        }
        self.restore_bindings();
        pixels
    }

    fn draw_software(&mut self, video: &VideoFrame, source: &Frame, surface_width: i32, surface_height: i32) -> bool {
        let target = self.draw_target;
        let dither = self.dither_bits();
        let tone = self.tone_target();
        let Some(state) = self.state.as_mut() else {
            return false;
        };
        let norm16 = state.norm16;
        let mut format = source.pixel_format();
        let mut plan = plan_for(format, norm16);
        let converted;
        let mut frame = source;
        let negative_stride = (0..3).any(|i| frame.plane(i).1 < 0);
        if plan.is_none() || negative_stride {
            let target = conversion_target(format, norm16);
            converted = match self.scaler.convert(source, target) {
                Ok(frame) => frame,
                Err(error) => {
                    log::warn!("cannot convert frame: {error}");
                    return false;
                }
            };
            frame = &converted;
            format = target;
            plan = plan_for(format, norm16);
        }
        let Some(plan) = plan else {
            return false;
        };
        let output = self.output;
        let adaptive = self.options.adaptive;
        unsafe {
            let planes = match plan.layout {
                Layout::Planar => 3,
                Layout::SemiPlanar => 2,
                Layout::Rgba | Layout::Gray => 1,
            };
            for index in 0..planes {
                if !state.upload(index, frame, &plan) {
                    return false;
                }
            }
            let color = video.color;
            let transform = yuv_transform(color.space, color.range, plan.depth, frame.height());
            let transfer = transfer_of(color.transfer);
            let bt2020 = is_bt2020(&color);
            let dovi_peak = if color.dovi_reshape && plan.layout != Layout::Rgba {
                state.update_dovi(source.side_data(sys::AVFrameSideDataType::AV_FRAME_DATA_DOVI_METADATA))
            } else {
                None
            };
            let static_peak = match dovi_peak {
                Some(peak) if plausible_peak(peak) => peak,
                _ => content_peak(Some(frame), &color, tone.hlg_nits(output)),
            };
            let measure = adaptive && tone.should_measure(output, &color) && plan.layout != Layout::Rgba;
            let peak = state.tone_peak(video, static_peak, measure) / SDR_WHITE_NITS;
            let container_max = if plan.wide { 65535.0 } else { 255.0 };
            let sample_scale = container_max / ((1u32 << plan.shift) as f32 * ((1u32 << plan.depth) - 1) as f32);
            let placement = Self::geometry(video, surface_width, surface_height, &self.transform);

            let scaling = Self::scaling(self.scaler_mode, self.downscaler_mode, self.quality_cap, video, &placement, surface_width, surface_height);
            self.active_scaling = scaling.summary();
            let antiring = self.antiring;
            let measuring = measure && state.meter().is_some();
            let (program, u) = state.yuv_variant(variant_key(scaling.kernel, scaling.downscale));
            let gl = &state.gl;
            gl.use_program(Some(program));
            gl.uniform_4_f32(u.crop.as_ref(), 0.0, 0.0, 1.0, 1.0);
            gl.uniform_1_i32(u.layout.as_ref(), plan.layout as i32);
            gl.uniform_1_f32(u.sample_scale.as_ref(), sample_scale);
            gl.uniform_matrix_3_f32_slice(u.matrix.as_ref(), false, &transform.matrix);
            gl.uniform_3_f32(u.offset.as_ref(), transform.offset[0], transform.offset[1], transform.offset[2]);
            gl.uniform_1_i32(u.transfer.as_ref(), transfer as i32);
            gl.uniform_1_i32(u.bt2020.as_ref(), bt2020 as i32);
            gl.uniform_1_f32(u.peak.as_ref(), peak);
            gl.uniform_1_i32(u.swap_uv.as_ref(), plan.swap_uv as i32);
            gl.uniform_1_i32(u.dovi_on.as_ref(), dovi_peak.is_some() as i32);
            gl.uniform_2_f32(u.tone.as_ref(), tone.peak, tone.black);
            gl.uniform_1_i32(u.dither.as_ref(), 0);
            gl.bind_vertex_array(Some(state.vao));
            if measuring {
                let meter = state.meter.as_mut().expect("meter");
                meter.begin(gl);
                gl.uniform_2_f32(u.scale.as_ref(), 1.0, 1.0);
                gl.uniform_2_f32(u.translate.as_ref(), 0.0, 0.0);
                gl.uniform_1_i32(u.turns.as_ref(), 0);
                gl.uniform_1_i32(u.output.as_ref(), MEASURE_OUTPUT);
                gl.draw_arrays(glow::TRIANGLE_STRIP, 0, 4);
                meter.end(gl, video.serial);
            }
            gl.bind_framebuffer(glow::FRAMEBUFFER, target);
            gl.viewport(0, 0, surface_width, surface_height);
            gl.clear_color(0.0, 0.0, 0.0, 1.0);
            gl.clear(glow::COLOR_BUFFER_BIT);
            gl.uniform_2_f32(u.scale.as_ref(), placement.scale[0], placement.scale[1]);
            gl.uniform_2_f32(u.translate.as_ref(), placement.translate[0], placement.translate[1]);
            gl.uniform_1_i32(u.turns.as_ref(), placement.turns);
            gl.uniform_2_f32(u.ratio.as_ref(), scaling.ratio[0], scaling.ratio[1]);
            gl.uniform_1_f32(u.antiring.as_ref(), antiring);
            gl.uniform_1_i32(u.output.as_ref(), output as i32);
            gl.uniform_1_i32(u.dither.as_ref(), dither);
            gl.draw_arrays(glow::TRIANGLE_STRIP, 0, 4);
        }
        true
    }

    fn draw_hardware(&mut self, video: &VideoFrame, frame: &dyn HardwareFrame, surface_width: i32, surface_height: i32) -> bool {
        let target = self.draw_target;
        let dither = self.dither_bits();
        let tone = self.tone_target();
        let display = self.display;
        let output = self.output;
        let adaptive = self.options.adaptive;
        let antiring = self.antiring;
        let scaling = {
            let placement = Self::geometry(video, surface_width, surface_height, &self.transform);
            Self::scaling(self.scaler_mode, self.downscaler_mode, self.quality_cap, video, &placement, surface_width, surface_height)
        };
        self.active_scaling = scaling.summary();
        let Some(state) = self.state.as_mut() else {
            return false;
        };
        let Some(external_texture) = state.external_texture else {
            return false;
        };
        if state.image_functions.is_none() {
            return false;
        }
        let Some(external) = (unsafe { state.external_variant(variant_key(scaling.kernel, scaling.downscale)) }) else {
            return false;
        };
        let color = video.color;
        let transfer = transfer_of(color.transfer);
        let measure = adaptive && tone.should_measure(output, &color);
        let peak = unsafe { state.tone_peak(video, content_peak(None, &color, tone.hlg_nits(output)), measure) } / SDR_WHITE_NITS;
        let measuring = measure && unsafe { state.meter() }.is_some();
        let Some(functions) = state.image_functions else {
            return false;
        };
        unsafe {
            let client = (functions.get_client_buffer)(frame.hardware_buffer());
            if client.is_null() {
                log::warn!("cannot wrap the hardware buffer");
                return false;
            }
            let attributes = [EGL_IMAGE_PRESERVED_KHR, 1, EGL_NONE];
            let image = (functions.create_image)(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client, attributes.as_ptr());
            if image.is_null() {
                log::warn!("eglCreateImageKHR failed: {:#x}", error());
                return false;
            }
            let gl = &state.gl;
            gl.active_texture(glow::TEXTURE0);
            gl.bind_texture(GL_TEXTURE_EXTERNAL_OES, Some(external_texture));
            (functions.target_texture)(GL_TEXTURE_EXTERNAL_OES, image);

            let (buffer_width, buffer_height) = frame.buffer_size();
            let [left, top, right, bottom] = frame.crop();
            let crop = if buffer_width > 0 && buffer_height > 0 && right > left && bottom > top {
                let w = buffer_width as f32;
                let h = buffer_height as f32;
                [left as f32 / w, top as f32 / h, (right - left) as f32 / w, (bottom - top) as f32 / h]
            } else {
                [0.0, 0.0, 1.0, 1.0]
            };
            let bt2020 = is_bt2020(&color);
            let placement = Self::geometry(video, surface_width, surface_height, &self.transform);

            gl.use_program(Some(external.program));
            gl.uniform_4_f32(external.crop.as_ref(), crop[0], crop[1], crop[2], crop[3]);
            gl.uniform_2_f32(external.source_size.as_ref(), buffer_width.max(1) as f32, buffer_height.max(1) as f32);
            gl.uniform_1_i32(external.transfer.as_ref(), transfer as i32);
            gl.uniform_1_i32(external.bt2020.as_ref(), bt2020 as i32);
            gl.uniform_1_f32(external.peak.as_ref(), peak);
            gl.uniform_2_f32(external.tone.as_ref(), tone.peak, tone.black);
            gl.uniform_1_i32(external.dither.as_ref(), 0);
            gl.bind_vertex_array(Some(state.vao));
            if measuring {
                let meter = state.meter.as_mut().expect("meter");
                meter.begin(gl);
                gl.uniform_2_f32(external.scale.as_ref(), 1.0, 1.0);
                gl.uniform_2_f32(external.translate.as_ref(), 0.0, 0.0);
                gl.uniform_1_i32(external.turns.as_ref(), 0);
                gl.uniform_1_i32(external.output.as_ref(), MEASURE_OUTPUT);
                gl.draw_arrays(glow::TRIANGLE_STRIP, 0, 4);
                meter.end(gl, video.serial);
            }
            gl.bind_framebuffer(glow::FRAMEBUFFER, target);
            gl.viewport(0, 0, surface_width, surface_height);
            gl.clear_color(0.0, 0.0, 0.0, 1.0);
            gl.clear(glow::COLOR_BUFFER_BIT);
            gl.uniform_2_f32(external.scale.as_ref(), placement.scale[0], placement.scale[1]);
            gl.uniform_2_f32(external.translate.as_ref(), placement.translate[0], placement.translate[1]);
            gl.uniform_1_i32(external.turns.as_ref(), placement.turns);
            gl.uniform_2_f32(external.ratio.as_ref(), scaling.ratio[0], scaling.ratio[1]);
            gl.uniform_1_f32(external.antiring.as_ref(), antiring);
            gl.uniform_1_i32(external.output.as_ref(), output as i32);
            gl.uniform_1_i32(external.dither.as_ref(), dither);
            gl.draw_arrays(glow::TRIANGLE_STRIP, 0, 4);
            gl.bind_texture(GL_TEXTURE_EXTERNAL_OES, None);
            (functions.destroy_image)(display, image);
        }
        true
    }

    fn swap(&mut self) -> bool {
        unsafe {
            if let (Some(set_time), Some(target)) = (self.presentation_time, self.target_ns) {
                set_time(self.display, self.surface, target);
            }
            if eglSwapBuffers(self.display, self.surface) != 0 {
                return true;
            }
        }
        let code = error();
        log::warn!("eglSwapBuffers failed: {code:#x}");
        if code == EGL_CONTEXT_LOST {
            let window = self.window.take();
            self.destroy_context();
            if let Some(window) = window {
                let output = self.output;
                if let Err(message) = self.attach(window, output) {
                    log::error!("cannot recover the renderer: {message}");
                    self.failed = true;
                }
            }
        } else if code == EGL_BAD_SURFACE || code == EGL_BAD_NATIVE_WINDOW {
            self.detach();
        }
        false
    }
}

impl Presenter for GlesPresenter {
    type Window = NativeWindow;

    fn set_window(&mut self, window: Option<NativeWindow>) {
        self.detach();
        if let Some(window) = window {
            if self.failed {
                return;
            }
            let output = self.output;
            if let Err(message) = self.attach(window, output) {
                log::error!("renderer setup failed: {message}");
                self.detach();
                self.failed = true;
            }
        }
    }

    fn has_window(&self) -> bool {
        self.surface != EGL_NO_SURFACE
    }

    fn present(&mut self, frame: &VideoFrame) -> bool {
        if self.surface == EGL_NO_SURFACE || self.failed {
            return false;
        }
        self.draw(frame)
    }

    fn name(&self) -> Option<String> {
        self.state.as_ref().map(|state| state.name.clone())
    }

    fn set_transform(&mut self, transform: ViewTransform) {
        self.set_view_transform(transform);
    }

    fn set_scaler(&mut self, mode: ScalerMode) {
        self.set_scaler_mode(mode);
    }

    fn set_downscaler(&mut self, mode: DownscalerMode) {
        self.set_downscaler_mode(mode);
    }

    fn set_antiring(&mut self, strength: f32) {
        self.set_antiring_strength(strength);
    }

    fn set_quality_cap(&mut self, cap: f32) {
        self.set_quality_cap_level(cap);
    }

    fn active_scaling(&self) -> ActiveScaling {
        self.active_scaling
    }

    fn set_display(&mut self, options: DisplayOptions) {
        self.set_display_options(options);
    }

    fn output_mode(&self) -> i32 {
        self.output()
    }

    fn present_at(&mut self, frame: &VideoFrame, delay_us: i64) -> bool {
        self.present_paced(frame, delay_us)
    }

    fn set_motion(&mut self, config: MotionConfig) {
        self.set_motion_config(config);
    }

    fn capture(&mut self, frame: &VideoFrame, width: i32, height: i32) -> Option<Vec<u8>> {
        self.capture_frame(frame, width, height)
    }

    fn check_duplicate(&mut self, from: &VideoFrame, to: &VideoFrame) -> Option<bool> {
        self.check_duplicate_frames(from, to)
    }
}

impl Drop for GlesPresenter {
    fn drop(&mut self) {
        self.destroy_context();
        unsafe { eglReleaseThread() };
    }
}

#[cfg(test)]
mod tests {
    use super::limit_kernel;

    #[test]
    fn quality_cap_stays_in_the_filter_family() {
        let one = [1.0, 1.0];
        assert_eq!(limit_kernel(13, false, one, f32::INFINITY), 13);
        assert_eq!(limit_kernel(13, false, one, 40.0), 12);
        assert_eq!(limit_kernel(13, false, one, 22.0), 11);
        assert_eq!(limit_kernel(13, false, one, 10.0), 2);
        assert_eq!(limit_kernel(13, false, one, 2.0), 0);
        assert_eq!(limit_kernel(10, false, one, 22.0), 8);
        assert_eq!(limit_kernel(20, false, one, 105.0), 19);
        assert_eq!(limit_kernel(20, false, one, 25.0), 17);
        assert_eq!(limit_kernel(20, false, one, 18.0), 5);
        assert_eq!(limit_kernel(6, false, one, 16.0), 6);
        assert_eq!(limit_kernel(13, true, [2.0, 2.0], 40.0), 11);
        assert_eq!(limit_kernel(6, true, [2.0, 2.0], 20.0), 16);
        assert_eq!(limit_kernel(6, true, [2.0, 2.0], 1.0), 0);
    }
}
