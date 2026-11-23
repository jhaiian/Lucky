use std::ptr;

use lucky_ffmpeg_sys as sys;

use crate::error::{Error, Result};
use crate::frame::Frame;

pub mod channel {
    pub const FRONT_LEFT: u64 = 1 << 0;
    pub const FRONT_RIGHT: u64 = 1 << 1;
    pub const FRONT_CENTER: u64 = 1 << 2;
    pub const LOW_FREQUENCY: u64 = 1 << 3;
    pub const BACK_LEFT: u64 = 1 << 4;
    pub const BACK_RIGHT: u64 = 1 << 5;
    pub const BACK_CENTER: u64 = 1 << 8;
    pub const SIDE_LEFT: u64 = 1 << 9;
    pub const SIDE_RIGHT: u64 = 1 << 10;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ChannelLayout(u64);

impl ChannelLayout {
    pub const MONO: Self = Self(channel::FRONT_CENTER);
    pub const STEREO: Self = Self(channel::FRONT_LEFT | channel::FRONT_RIGHT);
    pub const QUAD: Self = Self(channel::FRONT_LEFT | channel::FRONT_RIGHT | channel::BACK_LEFT | channel::BACK_RIGHT);
    pub const SURROUND_5_1: Self = Self(
        channel::FRONT_LEFT
            | channel::FRONT_RIGHT
            | channel::FRONT_CENTER
            | channel::LOW_FREQUENCY
            | channel::BACK_LEFT
            | channel::BACK_RIGHT,
    );
    pub const SURROUND_7_1: Self = Self(
        channel::FRONT_LEFT
            | channel::FRONT_RIGHT
            | channel::FRONT_CENTER
            | channel::LOW_FREQUENCY
            | channel::BACK_LEFT
            | channel::BACK_RIGHT
            | channel::SIDE_LEFT
            | channel::SIDE_RIGHT,
    );

    pub fn mask(self) -> u64 {
        self.0
    }

    pub fn channels(self) -> usize {
        self.0.count_ones() as usize
    }

    pub fn index_of(self, position: u64) -> Option<usize> {
        if self.0 & position == 0 {
            return None;
        }
        Some((self.0 & (position - 1)).count_ones() as usize)
    }

    pub fn nearest(mask: u64, channels: i32) -> Self {
        let count = channels.max(1) as u32;
        if mask == 0 {
            return match count {
                1 => Self::MONO,
                2 => Self::STEREO,
                3 | 4 => Self::QUAD,
                5 | 6 => Self::SURROUND_5_1,
                _ => Self::SURROUND_7_1,
            };
        }
        let sides = mask & (channel::SIDE_LEFT | channel::SIDE_RIGHT) != 0;
        let backs = mask & (channel::BACK_LEFT | channel::BACK_RIGHT | channel::BACK_CENTER) != 0;
        let centered = mask & (channel::FRONT_CENTER | channel::LOW_FREQUENCY) != 0;
        if count == 1 {
            Self::MONO
        } else if count == 2 {
            Self::STEREO
        } else if count > 6 || (sides && backs) {
            Self::SURROUND_7_1
        } else if centered {
            Self::SURROUND_5_1
        } else if count >= 4 {
            Self::QUAD
        } else {
            Self::STEREO
        }
    }

    pub fn fit(self, max_channels: usize) -> Self {
        if self.channels() <= max_channels.max(2) {
            return self;
        }
        [Self::SURROUND_7_1, Self::SURROUND_5_1, Self::QUAD]
            .into_iter()
            .find(|candidate| candidate.channels() <= max_channels && candidate.channels() < self.channels())
            .unwrap_or(Self::STEREO)
    }

    pub fn of_parameters(par: &sys::AVCodecParameters) -> Self {
        unsafe {
            let channels = par.ch_layout.nb_channels;
            let mut layout: sys::AVChannelLayout = std::mem::zeroed();
            if channels > 0 && par.ch_layout.order != sys::AVChannelOrder::AV_CHANNEL_ORDER_UNSPEC {
                sys::av_channel_layout_copy(&mut layout, &par.ch_layout);
            } else {
                sys::av_channel_layout_default(&mut layout, channels.max(1));
            }
            let mask = sys::av_channel_layout_subset(&layout, u64::MAX);
            let count = layout.nb_channels;
            sys::av_channel_layout_uninit(&mut layout);
            Self::nearest(mask, count)
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
struct InputFormat {
    format: i32,
    rate: i32,
    channels: i32,
    order: u32,
    mask: u64,
}

pub struct Resampler {
    context: *mut sys::SwrContext,
    input: Option<InputFormat>,
    out_rate: i32,
    out_layout: ChannelLayout,
}

unsafe impl Send for Resampler {}

impl Resampler {
    pub fn new(out_rate: i32, out_layout: ChannelLayout) -> Self {
        Self { context: ptr::null_mut(), input: None, out_rate, out_layout }
    }

    pub fn out_rate(&self) -> i32 {
        self.out_rate
    }

    pub fn out_layout(&self) -> ChannelLayout {
        self.out_layout
    }

    pub fn out_channels(&self) -> i32 {
        self.out_layout.channels() as i32
    }

    fn input_of(frame: &Frame) -> InputFormat {
        let raw = frame.raw();
        let mask = if raw.ch_layout.order == sys::AVChannelOrder::AV_CHANNEL_ORDER_NATIVE {
            unsafe { raw.ch_layout.u.mask }
        } else {
            0
        };
        InputFormat {
            format: raw.format,
            rate: raw.sample_rate,
            channels: raw.ch_layout.nb_channels,
            order: raw.ch_layout.order.0 as u32,
            mask,
        }
    }

    fn configure(&mut self, frame: &Frame) -> Result<()> {
        let input = Self::input_of(frame);
        if self.input == Some(input) && !self.context.is_null() {
            return Ok(());
        }
        unsafe {
            sys::swr_free(&mut self.context);
            let raw = frame.raw();
            let mut in_layout: sys::AVChannelLayout = std::mem::zeroed();
            if raw.ch_layout.nb_channels > 0 && raw.ch_layout.order != sys::AVChannelOrder::AV_CHANNEL_ORDER_UNSPEC {
                sys::av_channel_layout_copy(&mut in_layout, &raw.ch_layout);
            } else {
                sys::av_channel_layout_default(&mut in_layout, raw.ch_layout.nb_channels.max(1));
            }
            let mut out_layout: sys::AVChannelLayout = std::mem::zeroed();
            let built = sys::av_channel_layout_from_mask(&mut out_layout, self.out_layout.mask());
            if built < 0 {
                sys::av_channel_layout_uninit(&mut in_layout);
                return Err(Error::ffmpeg("building the output channel layout", built));
            }
            let code = sys::swr_alloc_set_opts2(
                &mut self.context,
                &out_layout,
                sys::AVSampleFormat::AV_SAMPLE_FMT_FLT,
                self.out_rate,
                &in_layout,
                sys::AVSampleFormat(raw.format),
                raw.sample_rate,
                0,
                ptr::null_mut(),
            );
            sys::av_channel_layout_uninit(&mut in_layout);
            sys::av_channel_layout_uninit(&mut out_layout);
            if code < 0 || self.context.is_null() {
                return Err(Error::ffmpeg("configuring the resampler", code.min(-1)));
            }
            let code = sys::swr_init(self.context);
            if code < 0 {
                sys::swr_free(&mut self.context);
                return Err(Error::ffmpeg("starting the resampler", code));
            }
        }
        self.input = Some(input);
        Ok(())
    }

    pub fn convert(&mut self, frame: &Frame, output: &mut Vec<f32>) -> Result<usize> {
        let samples = frame.raw().nb_samples;
        if samples <= 0 {
            return Ok(0);
        }
        self.configure(frame)?;
        unsafe {
            let capacity = sys::swr_get_out_samples(self.context, samples).max(samples) + 32;
            let channels = self.out_layout.channels();
            let start = output.len();
            output.resize(start + capacity as usize * channels, 0.0);
            let mut out_ptr = output.as_mut_ptr().add(start) as *mut u8;
            let raw = frame.raw();
            let converted = sys::swr_convert(
                self.context,
                &mut out_ptr as *mut *mut u8 as *const *mut u8,
                capacity,
                raw.extended_data as *const *const u8,
                samples,
            );
            if converted < 0 {
                output.truncate(start);
                return Err(Error::ffmpeg("resampling audio", converted));
            }
            output.truncate(start + converted as usize * channels);
            Ok(converted as usize)
        }
    }

    pub fn reset(&mut self) {
        unsafe { sys::swr_free(&mut self.context) };
        self.input = None;
    }
}

impl Drop for Resampler {
    fn drop(&mut self) {
        unsafe { sys::swr_free(&mut self.context) };
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const FRONT_LEFT_RIGHT_CENTER: u64 = channel::FRONT_LEFT | channel::FRONT_RIGHT | channel::FRONT_CENTER;

    #[test]
    fn layouts_keep_android_channel_order() {
        let layout = ChannelLayout::SURROUND_7_1;
        assert_eq!(layout.channels(), 8);
        assert_eq!(layout.index_of(channel::FRONT_LEFT), Some(0));
        assert_eq!(layout.index_of(channel::FRONT_CENTER), Some(2));
        assert_eq!(layout.index_of(channel::LOW_FREQUENCY), Some(3));
        assert_eq!(layout.index_of(channel::BACK_LEFT), Some(4));
        assert_eq!(layout.index_of(channel::SIDE_LEFT), Some(6));
        assert_eq!(layout.index_of(channel::SIDE_RIGHT), Some(7));
        assert_eq!(ChannelLayout::STEREO.index_of(channel::FRONT_CENTER), None);
    }

    #[test]
    fn odd_sources_map_to_the_nearest_supported_layout() {
        assert_eq!(ChannelLayout::nearest(channel::FRONT_CENTER, 1), ChannelLayout::MONO);
        assert_eq!(ChannelLayout::nearest(ChannelLayout::STEREO.mask(), 2), ChannelLayout::STEREO);
        assert_eq!(ChannelLayout::nearest(FRONT_LEFT_RIGHT_CENTER, 3), ChannelLayout::SURROUND_5_1);
        assert_eq!(ChannelLayout::nearest(ChannelLayout::QUAD.mask(), 4), ChannelLayout::QUAD);
        let five_one_side = FRONT_LEFT_RIGHT_CENTER | channel::LOW_FREQUENCY | channel::SIDE_LEFT | channel::SIDE_RIGHT;
        assert_eq!(ChannelLayout::nearest(five_one_side, 6), ChannelLayout::SURROUND_5_1);
        let six_one = FRONT_LEFT_RIGHT_CENTER | channel::LOW_FREQUENCY | channel::BACK_CENTER | channel::SIDE_LEFT | channel::SIDE_RIGHT;
        assert_eq!(ChannelLayout::nearest(six_one, 7), ChannelLayout::SURROUND_7_1);
        assert_eq!(ChannelLayout::nearest(0, 12), ChannelLayout::SURROUND_7_1);
    }

    #[test]
    fn layouts_step_down_to_what_the_route_can_play() {
        assert_eq!(ChannelLayout::SURROUND_7_1.fit(8), ChannelLayout::SURROUND_7_1);
        assert_eq!(ChannelLayout::SURROUND_7_1.fit(6), ChannelLayout::SURROUND_5_1);
        assert_eq!(ChannelLayout::SURROUND_5_1.fit(4), ChannelLayout::QUAD);
        assert_eq!(ChannelLayout::SURROUND_5_1.fit(2), ChannelLayout::STEREO);
        assert_eq!(ChannelLayout::MONO.fit(2), ChannelLayout::MONO);
    }
}
