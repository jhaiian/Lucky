#![allow(non_upper_case_globals, non_camel_case_types, non_snake_case)]

mod fonts;
mod style;

use std::collections::HashSet;
use std::ffi::{CString, c_char, c_int};
use std::ptr;

pub use fonts::{Script, SystemFonts, script_of};
pub use style::{DEFAULT_PLAY_RES, SubtitleStyle, ass_colour};

#[allow(dead_code, clippy::all)]
mod sys {
    include!(concat!(env!("OUT_DIR"), "/bindings.rs"));
}

pub struct Rendered {
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
    pub argb: Vec<u32>,
}

pub enum RenderOutcome {
    Unchanged,
    Empty,
    Image(Rendered),
}

pub struct AssEngine {
    library: *mut sys::ASS_Library,
    renderer: *mut sys::ASS_Renderer,
    track: *mut sys::ASS_Track,
    system: SystemFonts,
    default_font: Option<CString>,
    added: HashSet<String>,
    scripts: HashSet<Script>,
    fonts_dirty: bool,
    frame: (i32, i32),
    scale: f64,
    storage: (i32, i32),
    applied_storage: (i32, i32),
    par: f64,
    applied_par: f64,
    line_position: f64,
    applied_line_position: f64,
    has_output: bool,
}

unsafe impl Send for AssEngine {}

impl AssEngine {
    pub fn new() -> Option<Self> {
        unsafe {
            let library = sys::ass_library_init();
            if library.is_null() {
                return None;
            }
            sys::ass_set_extract_fonts(library, 1);
            let renderer = sys::ass_renderer_init(library);
            if renderer.is_null() {
                sys::ass_library_done(library);
                return None;
            }
            let system = SystemFonts::scan();
            let default_font = system
                .default_font()
                .and_then(|path| CString::new(path.to_string_lossy().into_owned()).ok());
            Some(Self {
                library,
                renderer,
                track: ptr::null_mut(),
                system,
                default_font,
                added: HashSet::new(),
                scripts: HashSet::new(),
                fonts_dirty: true,
                frame: (0, 0),
                scale: 1.0,
                storage: (0, 0),
                applied_storage: (0, 0),
                par: 0.0,
                applied_par: 0.0,
                line_position: 0.0,
                applied_line_position: 0.0,
                has_output: false,
            })
        }
    }

    pub fn add_font(&mut self, name: &str, data: &[u8]) {
        if data.is_empty() || data.len() > i32::MAX as usize || !self.added.insert(name.to_string()) {
            return;
        }
        let Ok(name) = CString::new(name.replace('\0', "")) else {
            return;
        };
        unsafe {
            sys::ass_add_font(self.library, name.as_ptr(), data.as_ptr() as *const c_char, data.len() as c_int);
        }
        self.fonts_dirty = true;
    }

    pub fn has_track(&self) -> bool {
        !self.track.is_null()
    }

    pub fn set_layout(&mut self, storage_width: i32, storage_height: i32, pixel_aspect: f64) {
        self.storage = if storage_width > 0 && storage_height > 0 { (storage_width, storage_height) } else { (0, 0) };
        self.par = if pixel_aspect.is_finite() && pixel_aspect > 0.0 { pixel_aspect.clamp(0.1, 10.0) } else { 0.0 };
    }

    pub fn set_line_position(&mut self, percent: f64) {
        self.line_position = if percent.is_finite() { percent.clamp(0.0, 100.0) } else { 0.0 };
    }

    pub fn start_track(&mut self, header: &[u8]) -> bool {
        self.clear_track();
        unsafe {
            let track = sys::ass_new_track(self.library);
            if track.is_null() {
                return false;
            }
            if !header.is_empty() && header.len() <= i32::MAX as usize {
                self.prepare_scripts(header);
                sys::ass_process_codec_private(track, header.as_ptr() as *const c_char, header.len() as c_int);
            }
            self.track = track;
        }
        self.has_output = false;
        true
    }

    pub fn load_script(&mut self, data: &[u8]) -> bool {
        self.clear_track();
        if data.is_empty() || data.len() > i32::MAX as usize {
            return false;
        }
        self.prepare_scripts(data);
        unsafe {
            let track = sys::ass_read_memory(self.library, data.as_ptr() as *mut c_char, data.len(), ptr::null_mut());
            if track.is_null() {
                return false;
            }
            self.track = track;
        }
        self.has_output = false;
        true
    }

    pub fn clear_track(&mut self) {
        if !self.track.is_null() {
            unsafe { sys::ass_free_track(self.track) };
            self.track = ptr::null_mut();
        }
        self.has_output = false;
    }

    pub fn add_event(&mut self, data: &[u8], start_ms: i64, duration_ms: i64) {
        if self.track.is_null() || data.is_empty() || data.len() > i32::MAX as usize {
            return;
        }
        self.prepare_scripts(data);
        unsafe {
            sys::ass_process_chunk(self.track, data.as_ptr() as *const c_char, data.len() as c_int, start_ms, duration_ms.max(0));
        }
    }

    fn prepare_scripts(&mut self, data: &[u8]) {
        let text = String::from_utf8_lossy(data);
        let mut needed = Vec::new();
        for ch in text.chars() {
            if let Some(script) = script_of(ch) {
                if !self.scripts.contains(&script) && !needed.contains(&script) {
                    needed.push(script);
                }
            }
        }
        for script in needed {
            self.scripts.insert(script);
            if let Some((name, bytes)) = self.system.load(script) {
                log::info!("loading the {script:?} subtitle font {name}");
                self.add_font(&name, &bytes);
            }
        }
    }

    pub fn render(&mut self, time_ms: i64, width: i32, height: i32, scale: f64, force: bool) -> RenderOutcome {
        if self.track.is_null() || width <= 0 || height <= 0 {
            let had = self.has_output;
            self.has_output = false;
            return if had || force { RenderOutcome::Empty } else { RenderOutcome::Unchanged };
        }
        let mut force = force;
        unsafe {
            if (width, height) != self.frame {
                sys::ass_set_frame_size(self.renderer, width, height);
                self.frame = (width, height);
                force = true;
            }
            let storage = if self.storage.0 > 0 && self.storage.1 > 0 { self.storage } else { (width, height) };
            if storage != self.applied_storage {
                sys::ass_set_storage_size(self.renderer, storage.0, storage.1);
                self.applied_storage = storage;
                force = true;
            }
            if (self.par - self.applied_par).abs() > 1e-4 {
                sys::ass_set_pixel_aspect(self.renderer, self.par);
                self.applied_par = self.par;
                force = true;
            }
            if (self.line_position - self.applied_line_position).abs() > 1e-3 {
                sys::ass_set_line_position(self.renderer, self.line_position);
                self.applied_line_position = self.line_position;
                force = true;
            }
            if (scale - self.scale).abs() > 1e-3 {
                sys::ass_set_font_scale(self.renderer, scale);
                self.scale = scale;
                force = true;
            }
            if self.fonts_dirty {
                let default_font = self.default_font.as_ref().map(|f| f.as_ptr()).unwrap_or(ptr::null());
                sys::ass_set_fonts(
                    self.renderer,
                    default_font,
                    c"sans-serif".as_ptr(),
                    sys::ASS_DefaultFontProvider_ASS_FONTPROVIDER_NONE as c_int,
                    ptr::null(),
                    1,
                );
                self.fonts_dirty = false;
                force = true;
            }
            let mut changed: c_int = 0;
            let image = sys::ass_render_frame(self.renderer, self.track, time_ms, &mut changed);
            if image.is_null() {
                let had = self.has_output;
                self.has_output = false;
                return if had || force { RenderOutcome::Empty } else { RenderOutcome::Unchanged };
            }
            if changed == 0 && !force && self.has_output {
                return RenderOutcome::Unchanged;
            }
            match composite(image) {
                Some(rendered) => {
                    self.has_output = true;
                    RenderOutcome::Image(rendered)
                }
                None => {
                    let had = self.has_output;
                    self.has_output = false;
                    if had || force { RenderOutcome::Empty } else { RenderOutcome::Unchanged }
                }
            }
        }
    }
}

impl Drop for AssEngine {
    fn drop(&mut self) {
        unsafe {
            if !self.track.is_null() {
                sys::ass_free_track(self.track);
            }
            sys::ass_renderer_done(self.renderer);
            sys::ass_library_done(self.library);
        }
    }
}

const MAX_CANVAS_PIXELS: i64 = 16 * 1024 * 1024;

unsafe fn composite(first: *mut sys::ASS_Image) -> Option<Rendered> {
    let (mut left, mut top, mut right, mut bottom) = (i32::MAX, i32::MAX, i32::MIN, i32::MIN);
    let mut node = first;
    while !node.is_null() {
        let image = unsafe { &*node };
        if image.w > 0 && image.h > 0 && !image.bitmap.is_null() {
            left = left.min(image.dst_x);
            top = top.min(image.dst_y);
            right = right.max(image.dst_x + image.w);
            bottom = bottom.max(image.dst_y + image.h);
        }
        node = image.next;
    }
    if left >= right || top >= bottom {
        return None;
    }
    let width = right - left;
    let height = bottom - top;
    if width as i64 * height as i64 > MAX_CANVAS_PIXELS {
        return None;
    }
    let mut canvas = vec![[0f32; 4]; (width * height) as usize];
    let mut node = first;
    while !node.is_null() {
        let image = unsafe { &*node };
        node = image.next;
        if image.w <= 0 || image.h <= 0 || image.bitmap.is_null() {
            continue;
        }
        let color = image.color;
        let red = ((color >> 24) & 0xFF) as f32 / 255.0;
        let green = ((color >> 16) & 0xFF) as f32 / 255.0;
        let blue = ((color >> 8) & 0xFF) as f32 / 255.0;
        let opacity = (255 - (color & 0xFF)) as f32 / 255.0;
        if opacity <= 0.0 {
            continue;
        }
        let ox = image.dst_x - left;
        let oy = image.dst_y - top;
        for y in 0..image.h {
            let row = unsafe { std::slice::from_raw_parts(image.bitmap.add((y * image.stride) as usize), image.w as usize) };
            let base = ((oy + y) * width + ox) as usize;
            for (x, &coverage) in row.iter().enumerate() {
                if coverage == 0 {
                    continue;
                }
                let alpha = coverage as f32 / 255.0 * opacity;
                let pixel = &mut canvas[base + x];
                let keep = 1.0 - alpha;
                pixel[0] = red * alpha + pixel[0] * keep;
                pixel[1] = green * alpha + pixel[1] * keep;
                pixel[2] = blue * alpha + pixel[2] * keep;
                pixel[3] = alpha + pixel[3] * keep;
            }
        }
    }
    let argb = canvas
        .iter()
        .map(|p| {
            let alpha = p[3];
            if alpha <= 0.0 {
                return 0;
            }
            let a = (alpha * 255.0 + 0.5).min(255.0) as u32;
            let r = (p[0] / alpha * 255.0 + 0.5).min(255.0) as u32;
            let g = (p[1] / alpha * 255.0 + 0.5).min(255.0) as u32;
            let b = (p[2] / alpha * 255.0 + 0.5).min(255.0) as u32;
            (a << 24) | (r << 16) | (g << 8) | b
        })
        .collect();
    Some(Rendered { x: left, y: top, width, height, argb })
}

#[cfg(test)]
mod tests {
    use super::*;

    const HEADER: &str = "[Script Info]\nScriptType: v4.00+\nPlayResX: 640\nPlayResY: 360\n\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\nStyle: Default,Arial,28,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n";

    fn font(path: &str) -> Option<Vec<u8>> {
        std::fs::read(path).ok()
    }

    fn coverage(outcome: &RenderOutcome) -> usize {
        match outcome {
            RenderOutcome::Image(image) => image.argb.iter().filter(|p| (*p >> 24) > 0).count(),
            _ => 0,
        }
    }

    #[test]
    fn renders_and_tracks_changes() {
        let Some(latin) = font("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf") else {
            return;
        };
        let mut engine = AssEngine::new().expect("engine");
        engine.add_font("DejaVuSans.ttf", &latin);
        assert!(engine.start_track(HEADER.as_bytes()));
        engine.add_event(b"1,0,Default,,0,0,0,,Hello {\\b1}world", 0, 5000);
        let first = engine.render(1000, 1280, 720, 1.0, false);
        assert!(coverage(&first) > 100);
        if let RenderOutcome::Image(image) = &first {
            assert!(image.y > 720 / 2, "bottom aligned subtitle at y={}", image.y);
        }
        assert!(matches!(engine.render(1500, 1280, 720, 1.0, false), RenderOutcome::Unchanged));
        assert!(matches!(engine.render(6000, 1280, 720, 1.0, false), RenderOutcome::Empty));
        assert!(matches!(engine.render(7000, 1280, 720, 1.0, false), RenderOutcome::Unchanged));
    }

    #[test]
    fn applies_storage_layout_and_line_position() {
        let Some(latin) = font("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf") else {
            return;
        };
        let mut engine = AssEngine::new().expect("engine");
        engine.add_font("DejaVuSans.ttf", &latin);
        assert!(engine.start_track(HEADER.as_bytes()));
        engine.add_event(b"1,0,Default,,0,0,0,,Hello world", 0, 5000);
        engine.set_layout(720, 480, 1.185);
        let low = engine.render(1000, 1280, 720, 1.0, false);
        assert!(coverage(&low) > 100);
        engine.set_line_position(40.0);
        let high = engine.render(1000, 1280, 720, 1.0, false);
        match (&low, &high) {
            (RenderOutcome::Image(low), RenderOutcome::Image(high)) => assert!(high.y < low.y, "raised y={} from y={}", high.y, low.y),
            _ => panic!("expected images"),
        }
    }

    #[test]
    fn falls_back_to_any_font_with_the_glyph() {
        let Some(latin) = font("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf") else {
            return;
        };
        let Some(cjk) = font("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc").or_else(|| font("/usr/share/fonts/truetype/fonts-japanese-gothic.ttf")) else {
            return;
        };
        let render = |fonts: &[(&str, &[u8])]| {
            let mut engine = AssEngine::new().expect("engine");
            for (name, data) in fonts {
                engine.add_font(name, data);
            }
            engine.start_track(HEADER.as_bytes());
            engine.add_event("1,0,Default,,0,0,0,,日本語".as_bytes(), 0, 5000);
            coverage(&engine.render(1000, 1280, 720, 1.0, true))
        };
        let missing = render(&[("DejaVuSans.ttf", &latin)]);
        let direct = render(&[("cjk", &cjk)]);
        let found = render(&[("DejaVuSans.ttf", &latin), ("cjk", &cjk)]);
        let close = |a: usize, b: usize| (a as f64 - b as f64).abs() <= b as f64 * 0.05;
        assert!(close(found, direct), "fallback {found} should match direct {direct}");
        assert!(!close(missing, direct), "missing {missing} should differ from direct {direct}");
    }
}
