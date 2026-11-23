use lucky_ffmpeg::{CodecParameters, DecodeStatus, Decoder, Packet, sys};

use crate::frame::FrameData;

pub enum BackendOutput {
    Frame { data: FrameData, pts_us: Option<i64>, duration_us: Option<i64>, sample_aspect: Option<(i32, i32)> },
    NeedsInput,
    Finished,
}

pub trait VideoBackend: Send {
    fn name(&self) -> String;
    fn hardware(&self) -> bool;
    fn send(&mut self, packet: Option<&Packet>) -> Result<bool, String>;
    fn receive(&mut self, wait: bool) -> Result<BackendOutput, String>;
    fn flush(&mut self);
    fn set_catch_up(&mut self, _level: u8) {}
}

pub struct VideoStreamInfo {
    pub params: CodecParameters,
    pub time_base: sys::AVRational,
    pub frame_rate: Option<f64>,
}

pub trait HardwareDecoderFactory: Send + 'static {
    fn create(&self, stream: &VideoStreamInfo) -> Option<Box<dyn VideoBackend>>;
}

pub struct SoftwareBackend {
    decoder: Decoder,
}

impl SoftwareBackend {
    pub fn open(stream: &VideoStreamInfo, threads: i32) -> Result<Self, String> {
        Decoder::open_params(stream.params.as_ptr(), stream.time_base, threads)
            .map(|decoder| Self { decoder })
            .map_err(|error| error.to_string())
    }

    fn rescale(&self, value: i64) -> i64 {
        let tb = self.decoder.time_base();
        if tb.den == 0 {
            return 0;
        }
        (value as i128 * tb.num as i128 * 1_000_000 / tb.den as i128) as i64
    }
}

impl VideoBackend for SoftwareBackend {
    fn name(&self) -> String {
        self.decoder.name().to_string()
    }

    fn hardware(&self) -> bool {
        false
    }

    fn send(&mut self, packet: Option<&Packet>) -> Result<bool, String> {
        self.decoder.send(packet).map_err(|error| error.to_string())
    }

    fn receive(&mut self, _wait: bool) -> Result<BackendOutput, String> {
        match self.decoder.receive().map_err(|error| error.to_string())? {
            DecodeStatus::Frame(frame) => {
                let pts_us = frame.timestamp().map(|ts| self.rescale(ts));
                let duration_us = Some(frame.duration()).filter(|d| *d > 0).map(|d| self.rescale(d));
                let raw = frame.raw();
                let sample_aspect = (raw.sample_aspect_ratio.num > 0 && raw.sample_aspect_ratio.den > 0)
                    .then_some((raw.sample_aspect_ratio.num, raw.sample_aspect_ratio.den));
                Ok(BackendOutput::Frame { data: FrameData::Software(frame), pts_us, duration_us, sample_aspect })
            }
            DecodeStatus::NeedsInput => Ok(BackendOutput::NeedsInput),
            DecodeStatus::Finished => Ok(BackendOutput::Finished),
        }
    }

    fn flush(&mut self) {
        self.decoder.flush();
    }

    fn set_catch_up(&mut self, level: u8) {
        let context = self.decoder.as_ptr();
        if context.is_null() {
            return;
        }
        unsafe {
            (*context).skip_loop_filter = match level {
                0 => sys::AVDiscard::AVDISCARD_DEFAULT,
                1 => sys::AVDiscard::AVDISCARD_NONREF,
                _ => sys::AVDiscard::AVDISCARD_ALL,
            };
            (*context).skip_frame = if level >= 3 { sys::AVDiscard::AVDISCARD_NONREF } else { sys::AVDiscard::AVDISCARD_DEFAULT };
        }
    }
}
