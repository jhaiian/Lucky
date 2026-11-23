use std::collections::HashSet;
use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};
use std::sync::MutexGuard;

use lucky_ass::{AssEngine, RenderOutcome, SubtitleStyle};
use lucky_ffmpeg::DecodedSubtitle;

use crate::shared::Shared;

const FALLBACK_DURATION_MS: i64 = 4000;
const MAX_TEXT_EVENTS: usize = 200_000;

struct TextEvent {
    chunk: Vec<u8>,
    start_ms: i64,
    duration_ms: i64,
}

#[derive(Default)]
struct TextTrack {
    events: Vec<TextEvent>,
    seen: HashSet<u64>,
}

impl TextTrack {
    fn add(&mut self, line: &str, start_ms: i64, duration_ms: i64) -> Option<usize> {
        let (_, rest) = line.split_once(',')?;
        if self.events.len() >= MAX_TEXT_EVENTS {
            return None;
        }
        let mut hasher = DefaultHasher::new();
        (start_ms, duration_ms, rest).hash(&mut hasher);
        if !self.seen.insert(hasher.finish()) {
            return None;
        }
        let order = self.events.len();
        self.events.push(TextEvent { chunk: format!("{order},{rest}").into_bytes(), start_ms, duration_ms });
        Some(order)
    }
}

#[derive(Default)]
pub struct AssSlot {
    engine: Option<AssEngine>,
    fonts_added: usize,
    text: Option<TextTrack>,
}

impl AssSlot {
    fn push_text(&mut self, decoded: &DecodedSubtitle, offset_us: i64) {
        let Self { engine, text, .. } = self;
        let (Some(engine), Some(track)) = (engine.as_mut(), text.as_mut()) else {
            return;
        };
        let start_ms = (decoded.start_us - offset_us) / 1000;
        let duration_ms = decoded
            .end_us
            .map(|end| (end - decoded.start_us) / 1000)
            .filter(|duration| *duration > 0)
            .unwrap_or(FALLBACK_DURATION_MS);
        for line in &decoded.ass_events {
            if let Some(index) = track.add(line, start_ms, duration_ms) {
                let event = &track.events[index];
                engine.add_event(&event.chunk, event.start_ms, event.duration_ms);
            }
        }
    }
}

fn slot(shared: &Shared) -> MutexGuard<'_, AssSlot> {
    shared.ass.lock().unwrap_or_else(|e| e.into_inner())
}

fn video_layout(shared: &Shared) -> (i32, i32, f64) {
    let (width, height, aspect, rotation) = {
        let status = shared.status();
        (status.video_width, status.video_height, status.display_aspect, status.rotation)
    };
    if width <= 0 || height <= 0 {
        return (0, 0, 0.0);
    }
    let rotated = rotation.rem_euclid(180) == 90;
    let (storage_width, storage_height) = if rotated { (height, width) } else { (width, height) };
    let pixel_aspect = if aspect.is_finite() && aspect > 0.0 {
        let shown = if rotated { 1.0 / aspect } else { aspect };
        shown / (storage_width as f64 / storage_height as f64)
    } else {
        0.0
    };
    (storage_width, storage_height, pixel_aspect)
}

fn start(slot: &mut AssSlot, shared: &Shared, header: &[u8]) -> bool {
    if slot.engine.is_none() {
        slot.engine = AssEngine::new();
        if slot.engine.is_none() {
            log::warn!("libass is unavailable, using plain subtitles");
            return false;
        }
    }
    let fonts = shared.fonts.lock().unwrap_or_else(|e| e.into_inner());
    let from = slot.fonts_added;
    let total = fonts.len();
    if let Some(engine) = slot.engine.as_mut() {
        for font in fonts.iter().skip(from) {
            engine.add_font(&font.name, &font.data);
        }
    }
    drop(fonts);
    slot.fonts_added = total;
    slot.engine.as_mut().is_some_and(|engine| engine.start_track(header))
}

fn text_header(shared: &Shared) -> String {
    let (width, height, _) = video_layout(shared);
    let style = shared.subtitle_style.lock().unwrap_or_else(|e| e.into_inner()).clone();
    style.header(width, height)
}

pub fn begin(shared: &Shared, header: &[u8]) -> bool {
    let mut slot = slot(shared);
    slot.text = None;
    start(&mut slot, shared, header)
}

pub fn begin_text(shared: &Shared) -> bool {
    let header = text_header(shared);
    let mut slot = slot(shared);
    slot.text = None;
    if start(&mut slot, shared, header.as_bytes()) {
        slot.text = Some(TextTrack::default());
        true
    } else {
        false
    }
}

pub fn end(shared: &Shared) {
    let mut slot = slot(shared);
    slot.text = None;
    if let Some(engine) = slot.engine.as_mut() {
        engine.clear_track();
    }
}

pub fn restyle(shared: &Shared) {
    let header = text_header(shared);
    let mut slot = slot(shared);
    let AssSlot { engine, text, .. } = &mut *slot;
    let (Some(engine), Some(track)) = (engine.as_mut(), text.as_ref()) else {
        return;
    };
    if engine.start_track(header.as_bytes()) {
        for event in &track.events {
            engine.add_event(&event.chunk, event.start_ms, event.duration_ms);
        }
    }
}

pub fn event(shared: &Shared, data: &[u8], start_ms: i64, duration_ms: i64) {
    if let Some(engine) = slot(shared).engine.as_mut() {
        engine.add_event(data, start_ms, duration_ms);
    }
}

pub fn event_for(shared: &Shared, serial: u64, data: &[u8], start_ms: i64, duration_ms: i64) -> bool {
    let mut slot = slot(shared);
    if shared.current_serial() != serial {
        return false;
    }
    if let Some(engine) = slot.engine.as_mut() {
        engine.add_event(data, start_ms, duration_ms);
    }
    true
}

pub fn text_event(shared: &Shared, decoded: &DecodedSubtitle, offset_us: i64) {
    slot(shared).push_text(decoded, offset_us);
}

pub fn text_event_for(shared: &Shared, serial: u64, decoded: &DecodedSubtitle, offset_us: i64) -> bool {
    let mut slot = slot(shared);
    if shared.current_serial() != serial {
        return false;
    }
    slot.push_text(decoded, offset_us);
    true
}

pub fn render(shared: &Shared, time_ms: i64, width: i32, height: i32, scale: f64, lift: f64, force: bool) -> RenderOutcome {
    let (storage_width, storage_height, pixel_aspect) = video_layout(shared);
    let mut slot = slot(shared);
    let styled = slot.text.is_some();
    match slot.engine.as_mut() {
        Some(engine) if engine.has_track() => {
            engine.set_layout(storage_width, storage_height, pixel_aspect);
            engine.set_line_position(if styled { lift } else { 0.0 });
            engine.render(time_ms, width, height, if styled { 1.0 } else { scale }, force)
        }
        _ => RenderOutcome::Empty,
    }
}

pub fn set_style(shared: &Shared, style: SubtitleStyle) {
    {
        let mut stored = shared.subtitle_style.lock().unwrap_or_else(|e| e.into_inner());
        if *stored == style {
            return;
        }
        *stored = style;
    }
    restyle(shared);
}

#[cfg(test)]
mod tests {
    use super::*;
    use lucky_ffmpeg::{Attachment, SubtitleContent};

    fn lit(outcome: &RenderOutcome) -> usize {
        match outcome {
            RenderOutcome::Image(image) => image.argb.iter().filter(|p| (*p >> 24) > 0).count(),
            _ => 0,
        }
    }

    #[test]
    fn restyle_keeps_the_events() {
        let Ok(font) = std::fs::read("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf") else {
            return;
        };
        let shared = Shared::new(true);
        shared.fonts.lock().unwrap().push(Attachment { name: "DejaVuSans.ttf".into(), data: font });
        {
            let mut status = shared.status();
            status.video_width = 1440;
            status.video_height = 1080;
            status.display_aspect = 1920.0 / 1080.0;
        }
        assert!(begin_text(&shared));
        let decoded = DecodedSubtitle {
            start_us: 1_000_000,
            end_us: Some(5_000_000),
            content: SubtitleContent::Text("Hello world".into()),
            ass_events: vec!["0,0,Default,,0,0,0,,{\\an8}Hello {\\i1}world".into()],
        };
        text_event(&shared, &decoded, 0);
        text_event(&shared, &decoded, 0);
        let first = render(&shared, 2000, 1280, 720, 1.0, 0.0, true);
        assert!(lit(&first) > 100);
        if let RenderOutcome::Image(image) = &first {
            assert!(image.y < 720 / 2, "top aligned subtitle at y={}", image.y);
        }
        set_style(&shared, SubtitleStyle { size: 0.09, ..SubtitleStyle::default() });
        let second = render(&shared, 2000, 1280, 720, 1.0, 0.0, true);
        assert!(lit(&second) > lit(&first));
    }

    #[test]
    fn text_track_renumbers_and_drops_duplicates() {
        let mut track = TextTrack::default();
        assert_eq!(track.add("7,0,Default,,0,0,0,,Hello", 1000, 2000), Some(0));
        assert_eq!(track.add("3,0,Default,,0,0,0,,Hello", 1000, 2000), None);
        assert_eq!(track.add("3,0,Default,,0,0,0,,Hello", 1000, 2500), Some(1));
        assert_eq!(track.add("no fields", 0, 0), None);
        assert_eq!(track.events[0].chunk, b"0,0,Default,,0,0,0,,Hello".to_vec());
        assert_eq!(track.events[1].chunk, b"1,0,Default,,0,0,0,,Hello".to_vec());
    }
}
