mod bsf;
mod decoder;
mod error;
mod filter;
mod frame;
mod input;
mod io;
mod log_bridge;
mod packet;
mod params;
mod probe;
mod resample;
mod scale;
mod subtitle;
mod util;

use serde::Serialize;

pub use bsf::BitstreamFilter;
pub use decoder::{DecodeStatus, Decoder};
pub use error::{Error, Result, check, error_string};
pub use filter::Deinterlacer;
pub use frame::Frame;
pub use packet::Packet;
pub use params::CodecParameters;
pub use resample::{ChannelLayout, Resampler, channel};
pub use scale::Scaler;
pub use subtitle::{DecodedSubtitle, SubtitleBitmap, SubtitleContent, SubtitleDecoder};
pub use input::{Attachment, Chapter, Input};
pub use io::FdSource;
pub use log_bridge::install_log_bridge;
pub use lucky_ffmpeg_sys as sys;
pub use probe::{AudioInfo, HdrFormat, MediaInfo, StreamInfo, StreamKind, VideoInfo, preferred_decoder};
pub use util::{c_str, rational_to_f64};

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Versions {
    pub ffmpeg: String,
    pub avcodec: String,
    pub avformat: String,
    pub dav1d: String,
}

pub fn versions() -> Versions {
    unsafe {
        Versions {
            ffmpeg: util::c_str(sys::av_version_info()).unwrap_or_default(),
            avcodec: util::version_string(sys::avcodec_version()),
            avformat: util::version_string(sys::avformat_version()),
            dav1d: util::c_str(sys::lucky_dav1d_version()).unwrap_or_default(),
        }
    }
}
