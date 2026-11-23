use std::ptr;

use lucky_ffmpeg_sys as sys;

use crate::error::{Error, Result};
use crate::params::CodecParameters;
use crate::probe::preferred_decoder;
use crate::util::c_str;

#[derive(Clone)]
pub struct SubtitleBitmap {
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
    pub argb: Vec<u32>,
}

#[derive(Clone)]
pub enum SubtitleContent {
    Text(String),
    Bitmaps { frame_width: i32, frame_height: i32, rects: Vec<SubtitleBitmap> },
    Clear,
}

#[derive(Clone)]
pub struct DecodedSubtitle {
    pub start_us: i64,
    pub end_us: Option<i64>,
    pub content: SubtitleContent,
    pub ass_events: Vec<String>,
}

pub struct SubtitleDecoder {
    context: *mut sys::AVCodecContext,
    time_base: sys::AVRational,
    text: bool,
}

unsafe impl Send for SubtitleDecoder {}

fn ass_dialogue_text(ass: &str) -> &str {
    let mut fields = 0;
    for (index, ch) in ass.char_indices() {
        if ch == ',' {
            fields += 1;
            if fields == 8 {
                return &ass[index + 1..];
            }
        }
    }
    ass
}

fn plain_event(text: &str) -> String {
    let escaped = text.replace('{', "(").replace('}', ")").replace("\r\n", "\\N").replace('\n', "\\N");
    format!("0,0,Default,,0,0,0,,{escaped}")
}

impl SubtitleDecoder {
    pub fn open(params: &CodecParameters, time_base: sys::AVRational) -> Result<Self> {
        unsafe {
            let codec = preferred_decoder(params.raw().codec_id);
            if codec.is_null() {
                return Err(Error::ffmpeg("finding a subtitle decoder", sys::AVERROR_DECODER_NOT_FOUND));
            }
            let descriptor = sys::avcodec_descriptor_get(params.raw().codec_id);
            let text = !descriptor.is_null() && (*descriptor).props as u32 & sys::AV_CODEC_PROP_TEXT_SUB != 0;
            let context = sys::avcodec_alloc_context3(codec);
            if context.is_null() {
                return Err(Error::OutOfMemory("creating the subtitle decoder"));
            }
            let decoder = Self { context, time_base, text };
            let code = sys::avcodec_parameters_to_context(context, params.as_ptr());
            if code < 0 {
                return Err(Error::ffmpeg("configuring the subtitle decoder", code));
            }
            (*context).pkt_timebase = time_base;
            let code = sys::avcodec_open2(context, codec, ptr::null_mut());
            if code < 0 {
                return Err(Error::ffmpeg("opening the subtitle decoder", code));
            }
            Ok(decoder)
        }
    }

    pub fn is_text(&self) -> bool {
        self.text
    }

    fn rescale(&self, value: i64) -> i64 {
        let tb = self.time_base;
        if tb.den == 0 {
            return 0;
        }
        (value as i128 * tb.num as i128 * 1_000_000 / tb.den as i128) as i64
    }

    pub fn decode(&mut self, packet: &crate::Packet) -> Option<DecodedSubtitle> {
        unsafe {
            let mut subtitle: sys::AVSubtitle = std::mem::zeroed();
            let mut got = 0;
            let code = sys::avcodec_decode_subtitle2(self.context, &mut subtitle, &mut got, packet.as_ptr());
            if code < 0 || got == 0 {
                return None;
            }
            let base_us = if subtitle.pts != sys::AV_NOPTS_VALUE {
                subtitle.pts
            } else {
                packet.pts().map(|pts| self.rescale(pts)).unwrap_or(0)
            };
            let start_us = base_us + subtitle.start_display_time as i64 * 1000;
            let end_us = if subtitle.end_display_time > subtitle.start_display_time && subtitle.end_display_time != u32::MAX {
                Some(base_us + subtitle.end_display_time as i64 * 1000)
            } else if packet.duration() > 0 {
                Some(base_us + self.rescale(packet.duration()))
            } else {
                None
            };
            let rects = if subtitle.rects.is_null() || subtitle.num_rects == 0 {
                &[][..]
            } else {
                std::slice::from_raw_parts(subtitle.rects, subtitle.num_rects as usize)
            };
            let mut lines = Vec::new();
            let mut events = Vec::new();
            let mut bitmaps = Vec::new();
            for rect in rects.iter().filter(|r| !r.is_null()) {
                let rect = &**rect;
                if rect.type_ == sys::AVSubtitleType::SUBTITLE_BITMAP {
                    if rect.w <= 0 || rect.h <= 0 || rect.data[0].is_null() || rect.data[1].is_null() {
                        continue;
                    }
                    let palette = std::slice::from_raw_parts(rect.data[1] as *const u32, rect.nb_colors.clamp(0, 256) as usize);
                    let mut argb = Vec::with_capacity((rect.w * rect.h) as usize);
                    for row in 0..rect.h {
                        let line = std::slice::from_raw_parts(rect.data[0].add((row * rect.linesize[0]) as usize), rect.w as usize);
                        argb.extend(line.iter().map(|&index| palette.get(index as usize).copied().unwrap_or(0)));
                    }
                    bitmaps.push(SubtitleBitmap { x: rect.x, y: rect.y, width: rect.w, height: rect.h, argb });
                } else if let Some(ass) = c_str(rect.ass) {
                    lines.push(ass_dialogue_text(&ass).to_string());
                    events.push(ass);
                } else if let Some(text) = c_str(rect.text) {
                    events.push(plain_event(&text));
                    lines.push(text);
                }
            }
            let content = if !bitmaps.is_empty() {
                let context = &*self.context;
                SubtitleContent::Bitmaps { frame_width: context.width, frame_height: context.height, rects: bitmaps }
            } else if !lines.is_empty() {
                SubtitleContent::Text(lines.join("\\N"))
            } else {
                SubtitleContent::Clear
            };
            sys::avsubtitle_free(&mut subtitle);
            Some(DecodedSubtitle { start_us, end_us, content, ass_events: events })
        }
    }

    pub fn flush(&mut self) {
        unsafe { sys::avcodec_flush_buffers(self.context) };
    }
}

impl Drop for SubtitleDecoder {
    fn drop(&mut self) {
        unsafe { sys::avcodec_free_context(&mut self.context) };
    }
}

#[cfg(test)]
mod tests {
    use super::{ass_dialogue_text, plain_event};

    #[test]
    fn wraps_plain_text_as_an_event() {
        assert_eq!(plain_event("One\nTwo {x}"), "0,0,Default,,0,0,0,,One\\NTwo (x)");
    }

    #[test]
    fn extracts_dialogue_text() {
        assert_eq!(ass_dialogue_text("0,0,Default,,0,0,0,,Hello, {\\i1}world{\\i0}"), "Hello, {\\i1}world{\\i0}");
        assert_eq!(ass_dialogue_text("plain"), "plain");
    }
}
