use lucky_ffmpeg::{Frame, Scaler, sys};

use crate::frame::VideoFrame;

const MAX_PIXELS: f64 = 8_500_000.0;

#[derive(Clone)]
pub struct Screenshot {
    pub width: i32,
    pub height: i32,
    pub rgba: Vec<u8>,
}

pub enum ScreenshotPoll {
    Pending,
    Done(Option<Screenshot>),
}

pub fn output_size(frame: &VideoFrame) -> (i32, i32) {
    let height = frame.height().max(1) as f64;
    let aspect = frame.display_aspect();
    let aspect = if aspect.is_finite() && aspect > 0.0 { aspect } else { frame.width().max(1) as f64 / height };
    let turned = (frame.rotation.rem_euclid(360) / 90) % 2 == 1;
    let (mut width, mut shown_height) = if turned { (height, height * aspect) } else { (height * aspect, height) };
    let pixels = width * shown_height;
    if pixels > MAX_PIXELS {
        let shrink = (MAX_PIXELS / pixels).sqrt();
        width *= shrink;
        shown_height *= shrink;
    }
    ((width.round() as i32).max(1), (shown_height.round() as i32).max(1))
}

pub fn software(video: &VideoFrame, source: &Frame) -> Option<Screenshot> {
    let (width, height) = output_size(video);
    let turns = video.rotation.rem_euclid(360) / 90;
    let (upright_width, upright_height) = if turns % 2 == 1 { (height, width) } else { (width, height) };
    let scaled = Scaler::default().scale(source, sys::AVPixelFormat::AV_PIX_FMT_RGBA, upright_width, upright_height).ok()?;
    let (data, stride) = scaled.plane(0);
    if data.is_null() || stride <= 0 {
        return None;
    }
    let row = upright_width as usize * 4;
    let mut rgba = Vec::with_capacity(row * upright_height as usize);
    for y in 0..upright_height as isize {
        let line = unsafe { std::slice::from_raw_parts(data.offset(y * stride as isize), row) };
        rgba.extend_from_slice(line);
    }
    for pixel in rgba.chunks_exact_mut(4) {
        pixel[3] = 255;
    }
    Some(rotate(rgba, upright_width, upright_height, turns))
}

pub fn rotate(rgba: Vec<u8>, width: i32, height: i32, turns: i32) -> Screenshot {
    let (w, h) = (width as usize, height as usize);
    let pixel = |buffer: &[u8], index: usize| [buffer[index * 4], buffer[index * 4 + 1], buffer[index * 4 + 2], buffer[index * 4 + 3]];
    match turns {
        1 => {
            let mut out = vec![0u8; rgba.len()];
            for y in 0..h {
                for x in 0..w {
                    let target = x * h + (h - 1 - y);
                    out[target * 4..target * 4 + 4].copy_from_slice(&pixel(&rgba, y * w + x));
                }
            }
            Screenshot { width: height, height: width, rgba: out }
        }
        2 => {
            let mut out = vec![0u8; rgba.len()];
            let count = w * h;
            for index in 0..count {
                let target = count - 1 - index;
                out[target * 4..target * 4 + 4].copy_from_slice(&pixel(&rgba, index));
            }
            Screenshot { width, height, rgba: out }
        }
        3 => {
            let mut out = vec![0u8; rgba.len()];
            for y in 0..h {
                for x in 0..w {
                    let target = (w - 1 - x) * h + y;
                    out[target * 4..target * 4 + 4].copy_from_slice(&pixel(&rgba, y * w + x));
                }
            }
            Screenshot { width: height, height: width, rgba: out }
        }
        _ => Screenshot { width, height, rgba },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Vec<u8> {
        (0..6u8).flat_map(|index| [index, 0, 0, 255]).collect()
    }

    fn reds(shot: &Screenshot) -> Vec<u8> {
        shot.rgba.chunks_exact(4).map(|pixel| pixel[0]).collect()
    }

    #[test]
    fn a_quarter_turn_swaps_the_dimensions() {
        let shot = rotate(sample(), 3, 2, 1);
        assert_eq!((shot.width, shot.height), (2, 3));
        assert_eq!(reds(&shot), vec![3, 0, 4, 1, 5, 2]);
    }

    #[test]
    fn three_quarter_turns_are_the_inverse() {
        let shot = rotate(sample(), 3, 2, 3);
        assert_eq!((shot.width, shot.height), (2, 3));
        assert_eq!(reds(&shot), vec![2, 5, 1, 4, 0, 3]);
    }

    #[test]
    fn a_half_turn_reverses_the_pixels() {
        let shot = rotate(sample(), 3, 2, 2);
        assert_eq!((shot.width, shot.height), (3, 2));
        assert_eq!(reds(&shot), vec![5, 4, 3, 2, 1, 0]);
    }

    #[test]
    fn no_turn_keeps_the_pixels() {
        let shot = rotate(sample(), 3, 2, 0);
        assert_eq!(reds(&shot), vec![0, 1, 2, 3, 4, 5]);
    }
}
