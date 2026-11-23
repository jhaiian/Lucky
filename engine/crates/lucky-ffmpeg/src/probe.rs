use std::os::raw::c_char;

use serde::Serialize;

use lucky_ffmpeg_sys as sys;

use crate::input::Input;
use crate::util::{c_str, dict_get, rational_to_f64, rescale_to_ms};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum StreamKind {
    Video,
    Audio,
    Subtitle,
    Attachment,
    Data,
    Unknown,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaInfo {
    pub container: String,
    pub container_long: Option<String>,
    pub duration_ms: Option<i64>,
    pub start_time_ms: Option<i64>,
    pub bit_rate: Option<i64>,
    pub size_bytes: Option<i64>,
    pub seekable: bool,
    pub title: Option<String>,
    pub chapters: u32,
    pub streams: Vec<StreamInfo>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StreamInfo {
    pub index: i32,
    pub kind: StreamKind,
    pub codec: String,
    pub codec_long: Option<String>,
    pub profile: Option<String>,
    pub bit_rate: Option<i64>,
    pub language: Option<String>,
    pub title: Option<String>,
    pub is_default: bool,
    pub is_forced: bool,
    pub is_cover_art: bool,
    pub software_decoder: Option<String>,
    pub video: Option<VideoInfo>,
    pub audio: Option<AudioInfo>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VideoInfo {
    pub width: i32,
    pub height: i32,
    pub frame_rate: Option<f64>,
    pub pixel_format: Option<String>,
    pub bit_depth: Option<i32>,
    pub color_space: Option<String>,
    pub color_primaries: Option<String>,
    pub color_transfer: Option<String>,
    pub color_range: Option<String>,
    pub hdr: Option<HdrFormat>,
    pub dolby_vision_profile: Option<String>,
    pub rotation: i32,
    pub interlaced: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum HdrFormat {
    DolbyVision,
    Hdr10,
    Hlg,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioInfo {
    pub sample_rate: i32,
    pub channels: i32,
    pub channel_layout: Option<String>,
    pub sample_format: Option<String>,
}

fn side_data(par: &sys::AVCodecParameters, kind: sys::AVPacketSideDataType) -> Option<&[u8]> {
    if par.coded_side_data.is_null() || par.nb_coded_side_data <= 0 {
        return None;
    }
    unsafe {
        let entry = sys::av_packet_side_data_get(par.coded_side_data, par.nb_coded_side_data, kind);
        if entry.is_null() || (*entry).data.is_null() || (*entry).size == 0 {
            return None;
        }
        Some(std::slice::from_raw_parts((*entry).data, (*entry).size))
    }
}

fn known<T: PartialEq>(value: T, unspecified: T) -> Option<T> {
    if value == unspecified { None } else { Some(value) }
}

fn video_info(stream: &sys::AVStream, par: &sys::AVCodecParameters) -> VideoInfo {
    let pixel_format = sys::AVPixelFormat(par.format);
    let (pixel_format_name, bit_depth) = if par.format >= 0 {
        let descriptor = unsafe { sys::av_pix_fmt_desc_get(pixel_format) };
        let depth = if descriptor.is_null() { None } else { Some(unsafe { (*descriptor).comp[0].depth }) };
        (c_str(unsafe { sys::av_get_pix_fmt_name(pixel_format) }), depth)
    } else {
        (None, None)
    };

    let frame_rate = rational_to_f64(stream.avg_frame_rate)
        .or_else(|| rational_to_f64(par.framerate))
        .or_else(|| rational_to_f64(stream.r_frame_rate))
        .filter(|fps| *fps > 0.0 && *fps < 1000.0);

    let rotation = side_data(par, sys::AVPacketSideDataType::AV_PKT_DATA_DISPLAYMATRIX)
        .filter(|data| data.len() >= 9 * 4)
        .map(|data| {
            let angle = unsafe { sys::av_display_rotation_get(data.as_ptr() as *const i32) };
            if angle.is_nan() { 0 } else { ((-angle).round() as i32).rem_euclid(360) }
        })
        .unwrap_or(0);

    let dovi = side_data(par, sys::AVPacketSideDataType::AV_PKT_DATA_DOVI_CONF)
        .filter(|data| data.len() >= std::mem::size_of::<sys::AVDOVIDecoderConfigurationRecord>())
        .map(|data| unsafe { std::ptr::read_unaligned(data.as_ptr() as *const sys::AVDOVIDecoderConfigurationRecord) });

    let transfer = par.color_trc;
    let hdr = if dovi.is_some() {
        Some(HdrFormat::DolbyVision)
    } else if transfer == sys::AVColorTransferCharacteristic::AVCOL_TRC_SMPTE2084 {
        Some(HdrFormat::Hdr10)
    } else if transfer == sys::AVColorTransferCharacteristic::AVCOL_TRC_ARIB_STD_B67 {
        Some(HdrFormat::Hlg)
    } else {
        None
    };
    let dolby_vision_profile = dovi.map(|record| {
        if record.dv_bl_signal_compatibility_id > 0 {
            format!("{}.{}", record.dv_profile, record.dv_bl_signal_compatibility_id)
        } else {
            format!("{}", record.dv_profile)
        }
    });

    let interlaced = !(par.field_order == sys::AVFieldOrder::AV_FIELD_PROGRESSIVE
        || par.field_order == sys::AVFieldOrder::AV_FIELD_UNKNOWN);

    VideoInfo {
        width: par.width,
        height: par.height,
        frame_rate,
        pixel_format: pixel_format_name,
        bit_depth,
        color_space: known(par.color_space, sys::AVColorSpace::AVCOL_SPC_UNSPECIFIED)
            .and_then(|v| c_str(unsafe { sys::av_color_space_name(v) })),
        color_primaries: known(par.color_primaries, sys::AVColorPrimaries::AVCOL_PRI_UNSPECIFIED)
            .and_then(|v| c_str(unsafe { sys::av_color_primaries_name(v) })),
        color_transfer: known(par.color_trc, sys::AVColorTransferCharacteristic::AVCOL_TRC_UNSPECIFIED)
            .and_then(|v| c_str(unsafe { sys::av_color_transfer_name(v) })),
        color_range: known(par.color_range, sys::AVColorRange::AVCOL_RANGE_UNSPECIFIED)
            .and_then(|v| c_str(unsafe { sys::av_color_range_name(v) })),
        hdr,
        dolby_vision_profile,
        rotation,
        interlaced,
    }
}

fn audio_info(par: &sys::AVCodecParameters) -> AudioInfo {
    let mut buffer = [0 as c_char; 128];
    let layout = unsafe {
        if sys::av_channel_layout_describe(&par.ch_layout, buffer.as_mut_ptr(), buffer.len()) > 0 {
            c_str(buffer.as_ptr())
        } else {
            None
        }
    };
    let sample_format = if par.format >= 0 {
        c_str(unsafe { sys::av_get_sample_fmt_name(sys::AVSampleFormat(par.format)) })
    } else {
        None
    };
    AudioInfo {
        sample_rate: par.sample_rate,
        channels: par.ch_layout.nb_channels,
        channel_layout: layout,
        sample_format,
    }
}

fn stream_kind(kind: sys::AVMediaType) -> StreamKind {
    match kind {
        sys::AVMediaType::AVMEDIA_TYPE_VIDEO => StreamKind::Video,
        sys::AVMediaType::AVMEDIA_TYPE_AUDIO => StreamKind::Audio,
        sys::AVMediaType::AVMEDIA_TYPE_SUBTITLE => StreamKind::Subtitle,
        sys::AVMediaType::AVMEDIA_TYPE_ATTACHMENT => StreamKind::Attachment,
        sys::AVMediaType::AVMEDIA_TYPE_DATA => StreamKind::Data,
        _ => StreamKind::Unknown,
    }
}

pub fn preferred_decoder(codec_id: sys::AVCodecID) -> *const sys::AVCodec {
    unsafe {
        if codec_id == sys::AVCodecID::AV_CODEC_ID_AV1 {
            let dav1d = sys::avcodec_find_decoder_by_name(c"libdav1d".as_ptr());
            if !dav1d.is_null() {
                return dav1d;
            }
        }
        sys::avcodec_find_decoder(codec_id)
    }
}

fn stream_info(stream: &sys::AVStream) -> Option<StreamInfo> {
    if stream.codecpar.is_null() {
        return None;
    }
    let par = unsafe { &*stream.codecpar };
    let kind = stream_kind(par.codec_type);
    let descriptor = unsafe { sys::avcodec_descriptor_get(par.codec_id) };
    let codec_long = if descriptor.is_null() { None } else { c_str(unsafe { (*descriptor).long_name }) };
    let profile = if par.profile >= 0 {
        c_str(unsafe { sys::avcodec_profile_name(par.codec_id, par.profile) })
    } else {
        None
    };
    let decoder = preferred_decoder(par.codec_id);
    let software_decoder = if decoder.is_null() { None } else { c_str(unsafe { (*decoder).name }) };
    let disposition = stream.disposition as u32;
    Some(StreamInfo {
        index: stream.index,
        kind,
        codec: c_str(unsafe { sys::avcodec_get_name(par.codec_id) }).unwrap_or_else(|| "unknown".into()),
        codec_long,
        profile,
        bit_rate: Some(par.bit_rate).filter(|b| *b > 0),
        language: dict_get(stream.metadata, "language").filter(|l| l != "und"),
        title: dict_get(stream.metadata, "title"),
        is_default: disposition & sys::AV_DISPOSITION_DEFAULT != 0,
        is_forced: disposition & sys::AV_DISPOSITION_FORCED != 0,
        is_cover_art: disposition & sys::AV_DISPOSITION_ATTACHED_PIC != 0,
        software_decoder,
        video: (kind == StreamKind::Video).then(|| video_info(stream, par)),
        audio: (kind == StreamKind::Audio).then(|| audio_info(par)),
    })
}

impl Input {
    pub fn media_info(&self) -> MediaInfo {
        let context = unsafe { &*self.as_ptr() };
        let format = context.iformat;
        let (container, container_long) = if format.is_null() {
            ("unknown".to_string(), None)
        } else {
            unsafe {
                (
                    c_str((*format).name).unwrap_or_else(|| "unknown".into()),
                    c_str((*format).long_name),
                )
            }
        };
        let time_base = sys::AV_TIME_BASE_Q;
        let streams = self
            .streams()
            .iter()
            .filter(|s| !s.is_null())
            .filter_map(|s| stream_info(unsafe { &**s }))
            .collect();
        MediaInfo {
            container,
            container_long,
            duration_ms: rescale_to_ms(context.duration, time_base).filter(|d| *d > 0),
            start_time_ms: rescale_to_ms(context.start_time, time_base),
            bit_rate: Some(context.bit_rate).filter(|b| *b > 0),
            size_bytes: self.file_size(),
            seekable: self.seekable(),
            title: dict_get(context.metadata, "title"),
            chapters: context.nb_chapters,
            streams,
        }
    }
}
