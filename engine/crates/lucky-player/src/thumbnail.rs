use std::os::fd::OwnedFd;

use lucky_ffmpeg::{DecodeStatus, Decoder, Frame, Input, Packet, Scaler, sys};

use crate::demux::stream_rotation;

const THUMBNAIL_THREADS: i32 = 2;
const MAX_PACKETS: u32 = 600;
const MAX_KEYFRAMES: u32 = 3;
const MAX_BLANK_KEYFRAMES: u32 = 6;
const BLANK_SAMPLE_STEP: usize = 7;
const BLANK_MEAN_LUMA: u64 = 10;
const BLANK_BRIGHT_LUMA: u32 = 48;
const BLANK_BRIGHT_PERMILLE: usize = 5;

#[derive(Clone)]
pub struct Thumbnail {
    pub width: i32,
    pub height: i32,
    pub pixels: Vec<i32>,
}

struct Remembered {
    key_pts: i64,
    max_width: i32,
    max_height: i32,
    thumbnail: Thumbnail,
}

pub struct Thumbnailer {
    input: Input,
    decoder: Decoder,
    scaler: Scaler,
    packet: Packet,
    index: usize,
    sample_aspect: (i32, i32),
    rotation: i32,
    remembered: Option<Remembered>,
}

fn rotate(pixels: Vec<i32>, width: i32, height: i32, turns: i32) -> Thumbnail {
    let (w, h) = (width as usize, height as usize);
    match turns {
        1 => {
            let mut out = vec![0; pixels.len()];
            for y in 0..h {
                for x in 0..w {
                    out[x * h + (h - 1 - y)] = pixels[y * w + x];
                }
            }
            Thumbnail { width: height, height: width, pixels: out }
        }
        2 => {
            let mut out = pixels;
            out.reverse();
            Thumbnail { width, height, pixels: out }
        }
        3 => {
            let mut out = vec![0; pixels.len()];
            for y in 0..h {
                for x in 0..w {
                    out[(w - 1 - x) * h + y] = pixels[y * w + x];
                }
            }
            Thumbnail { width: height, height: width, pixels: out }
        }
        _ => Thumbnail { width, height, pixels },
    }
}

fn is_blank(thumbnail: &Thumbnail) -> bool {
    let mut count = 0usize;
    let mut sum = 0u64;
    let mut bright = 0usize;
    for pixel in thumbnail.pixels.iter().step_by(BLANK_SAMPLE_STEP) {
        let value = *pixel as u32;
        let red = (value >> 16) & 0xff;
        let green = (value >> 8) & 0xff;
        let blue = value & 0xff;
        let luma = (red * 54 + green * 183 + blue * 19) >> 8;
        sum += luma as u64;
        if luma > BLANK_BRIGHT_LUMA {
            bright += 1;
        }
        count += 1;
    }
    if count == 0 {
        return true;
    }
    let mean = sum / (count as u64);
    mean < BLANK_MEAN_LUMA && bright * 1000 < count * BLANK_BRIGHT_PERMILLE
}

impl Thumbnailer {
    pub fn open(fd: OwnedFd) -> Result<Self, String> {
        let input = Input::open_fd(fd).map_err(|error| error.to_string())?;
        if !input.seekable() {
            return Err("the media cannot be seeked".into());
        }
        let index = input.best_stream(sys::AVMediaType::AVMEDIA_TYPE_VIDEO).ok_or_else(|| "no video stream".to_string())?;
        let stream_ptr = input.stream(index).ok_or_else(|| "no video stream".to_string())?;
        let stream = unsafe { &*stream_ptr };
        if stream.codecpar.is_null() {
            return Err("the video stream has no parameters".into());
        }
        let par = unsafe { &*stream.codecpar };
        let sample_aspect = if par.sample_aspect_ratio.num > 0 && par.sample_aspect_ratio.den > 0 {
            (par.sample_aspect_ratio.num, par.sample_aspect_ratio.den)
        } else if stream.sample_aspect_ratio.num > 0 && stream.sample_aspect_ratio.den > 0 {
            (stream.sample_aspect_ratio.num, stream.sample_aspect_ratio.den)
        } else {
            (1, 1)
        };
        let rotation = stream_rotation(stream);
        let mut decoder = Decoder::open_still(stream_ptr, THUMBNAIL_THREADS).map_err(|error| error.to_string())?;
        decoder.skip_to_keyframes();
        input.keep_only_streams(&[index]);
        let packet = Packet::new().map_err(|error| error.to_string())?;
        Ok(Self { input, decoder, scaler: Scaler::default(), packet, index, sample_aspect, rotation, remembered: None })
    }

    pub fn frame(&mut self, position_ms: i64, max_width: i32, max_height: i32) -> Option<Thumbnail> {
        if max_width < 2 || max_height < 2 {
            return None;
        }
        self.input.seek(position_ms.max(0).saturating_mul(1000)).ok()?;
        self.decoder.flush();
        let mut packets = 0;
        let mut failures = 0;
        let mut blanks = 0;
        let mut first = true;
        let mut first_pts: Option<i64> = None;
        let mut found: Option<Thumbnail> = None;
        let mut fallback: Option<Thumbnail> = None;
        loop {
            if self.input.read(&mut self.packet).is_err() {
                break;
            }
            packets += 1;
            if packets > MAX_PACKETS {
                break;
            }
            if self.packet.stream_index() as usize != self.index || !self.packet.is_key() {
                continue;
            }
            let key_pts = self.packet.pts().or_else(|| self.packet.dts());
            if first {
                first = false;
                first_pts = key_pts;
                if let (Some(pts), Some(remembered)) = (key_pts, self.remembered.as_ref()) {
                    if remembered.key_pts == pts && remembered.max_width == max_width && remembered.max_height == max_height {
                        return Some(remembered.thumbnail.clone());
                    }
                }
            }
            match self.decode_key() {
                Some(frame) => {
                    let Some(thumbnail) = self.render(&frame, max_width, max_height) else {
                        break;
                    };
                    if !is_blank(&thumbnail) {
                        found = Some(thumbnail);
                        break;
                    }
                    if fallback.is_none() {
                        fallback = Some(thumbnail);
                    }
                    blanks += 1;
                    if blanks >= MAX_BLANK_KEYFRAMES {
                        break;
                    }
                }
                None => {
                    failures += 1;
                    if failures >= MAX_KEYFRAMES {
                        break;
                    }
                }
            }
            self.decoder.flush();
        }
        let thumbnail = found.or(fallback)?;
        self.remembered = first_pts.map(|key_pts| Remembered { key_pts, max_width, max_height, thumbnail: thumbnail.clone() });
        Some(thumbnail)
    }

    fn decode_key(&mut self) -> Option<Frame> {
        for _ in 0..4 {
            match self.decoder.send(Some(&self.packet)) {
                Ok(true) => break,
                Ok(false) => {
                    if let Ok(DecodeStatus::Frame(frame)) = self.decoder.receive() {
                        return Some(frame);
                    }
                }
                Err(_) => return None,
            }
        }
        if let Ok(DecodeStatus::Frame(frame)) = self.decoder.receive() {
            return Some(frame);
        }
        let _ = self.decoder.send(None);
        match self.decoder.receive() {
            Ok(DecodeStatus::Frame(frame)) => Some(frame),
            _ => None,
        }
    }

    fn render(&mut self, frame: &Frame, max_width: i32, max_height: i32) -> Option<Thumbnail> {
        if frame.width() <= 0 || frame.height() <= 0 {
            return None;
        }
        let (aspect_num, aspect_den) = frame.sample_aspect().unwrap_or(self.sample_aspect);
        let display_width = frame.width() as f64 * aspect_num as f64 / aspect_den as f64;
        let display_height = frame.height() as f64;
        let turns = self.rotation.rem_euclid(360) / 90;
        let turned = turns % 2 == 1;
        let (shown_width, shown_height) = if turned { (display_height, display_width) } else { (display_width, display_height) };
        let scale = (max_width as f64 / shown_width).min(max_height as f64 / shown_height).min(1.0);
        let target_width = ((shown_width * scale).round() as i32).max(1);
        let target_height = ((shown_height * scale).round() as i32).max(1);
        let (width, height) = if turned { (target_height, target_width) } else { (target_width, target_height) };
        let scaled = self.scaler.scale(frame, sys::AVPixelFormat::AV_PIX_FMT_BGRA, width, height).ok()?;
        let (data, stride) = scaled.plane(0);
        if data.is_null() {
            return None;
        }
        let mut pixels = Vec::with_capacity(width as usize * height as usize);
        for row in 0..height as isize {
            let line = unsafe { std::slice::from_raw_parts(data.offset(row * stride as isize), width as usize * 4) };
            pixels.extend(line.chunks_exact(4).map(|pixel| i32::from_le_bytes([pixel[0], pixel[1], pixel[2], pixel[3]])));
        }
        Some(rotate(pixels, width, height, turns))
    }
}
