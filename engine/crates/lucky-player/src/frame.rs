use std::ffi::c_void;

use lucky_ffmpeg::{Frame, sys};

pub trait HardwareFrame: Send + Sync {
    fn width(&self) -> i32;
    fn height(&self) -> i32;
    fn hardware_buffer(&self) -> *mut c_void;
    fn buffer_size(&self) -> (i32, i32);
    fn crop(&self) -> [i32; 4];
    fn scene_nits(&self) -> f32 {
        0.0
    }
}

pub enum FrameData {
    Software(Frame),
    Hardware(Box<dyn HardwareFrame>),
}

#[derive(Clone, Copy)]
pub struct ColorInfo {
    pub space: sys::AVColorSpace,
    pub range: sys::AVColorRange,
    pub primaries: sys::AVColorPrimaries,
    pub transfer: sys::AVColorTransferCharacteristic,
    pub peak_nits: f32,
    pub average_nits: f32,
    pub mastering_nits: f32,
    pub scene_nits: f32,
    pub dovi_reshape: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DoviConfig {
    pub profile: u8,
    pub compatibility: u8,
    pub rpu: bool,
}

impl DoviConfig {
    pub fn from_params(par: &sys::AVCodecParameters) -> Option<Self> {
        let data = coded_side_data(par, sys::AVPacketSideDataType::AV_PKT_DATA_DOVI_CONF)?;
        if data.len() < std::mem::size_of::<sys::AVDOVIDecoderConfigurationRecord>() {
            return None;
        }
        let record = unsafe { std::ptr::read_unaligned(data.as_ptr() as *const sys::AVDOVIDecoderConfigurationRecord) };
        Some(Self { profile: record.dv_profile, compatibility: record.dv_bl_signal_compatibility_id, rpu: record.rpu_present_flag != 0 })
    }

    pub fn needs_reshape(&self) -> bool {
        self.rpu && self.compatibility == 0
    }
}

fn coded_side_data(par: &sys::AVCodecParameters, kind: sys::AVPacketSideDataType) -> Option<&[u8]> {
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

pub fn light_level(data: Option<&[u8]>) -> Option<(f32, f32)> {
    let data = data?;
    if data.len() < std::mem::size_of::<sys::AVContentLightMetadata>() {
        return None;
    }
    let light = unsafe { std::ptr::read_unaligned(data.as_ptr() as *const sys::AVContentLightMetadata) };
    (light.MaxCLL > 0).then(|| (light.MaxCLL as f32, light.MaxFALL as f32))
}

pub fn mastering_peak(data: Option<&[u8]>) -> Option<f32> {
    let data = data?;
    if data.len() < std::mem::size_of::<sys::AVMasteringDisplayMetadata>() {
        return None;
    }
    let mastering = unsafe { std::ptr::read_unaligned(data.as_ptr() as *const sys::AVMasteringDisplayMetadata) };
    if mastering.has_luminance == 0 || mastering.max_luminance.den <= 0 {
        return None;
    }
    let peak = mastering.max_luminance.num as f32 / mastering.max_luminance.den as f32;
    (peak > 0.0).then_some(peak)
}

fn rational(value: sys::AVRational) -> f32 {
    if value.den <= 0 { 0.0 } else { value.num as f32 / value.den as f32 }
}

fn dynamic_peak(metadata: &sys::AVDynamicHDRPlus) -> Option<f32> {
    if metadata.num_windows == 0 {
        return None;
    }
    let params = &metadata.params[0];
    let brightest = params.maxscl.iter().map(|value| rational(*value)).fold(0.0f32, f32::max);
    let count = (params.num_distribution_maxrgb_percentiles as usize).min(params.distribution_maxrgb.len());
    let percentile = params.distribution_maxrgb[..count]
        .iter()
        .filter(|entry| entry.percentage >= 99)
        .map(|entry| rational(entry.percentile))
        .fold(0.0f32, f32::max);
    let peak = if brightest > 0.0 { brightest } else { percentile } * 10000.0;
    (peak.is_finite() && peak > 0.0).then(|| peak.min(10000.0))
}

pub fn hdr10_plus_nits(data: Option<&[u8]>) -> Option<f32> {
    let data = data?;
    if data.len() < std::mem::size_of::<sys::AVDynamicHDRPlus>() {
        return None;
    }
    let metadata = unsafe { std::ptr::read_unaligned(data.as_ptr() as *const sys::AVDynamicHDRPlus) };
    dynamic_peak(&metadata)
}

fn t35_nits(payload: &[u8]) -> Option<f32> {
    if payload.len() < 7 || payload[0] != 0xB5 || payload[1..3] != [0x00, 0x3C] || payload[3..5] != [0x00, 0x01] || payload[5] != 4 {
        return None;
    }
    let body = &payload[6..];
    unsafe {
        let mut metadata: sys::AVDynamicHDRPlus = std::mem::zeroed();
        if sys::av_dynamic_hdr_plus_from_t35(&mut metadata, body.as_ptr(), body.len()) < 0 {
            return None;
        }
        dynamic_peak(&metadata)
    }
}

fn unescape(nal: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(nal.len());
    let mut zeros = 0;
    for &byte in nal {
        if zeros >= 2 && byte == 3 {
            zeros = 0;
            continue;
        }
        zeros = if byte == 0 { zeros + 1 } else { 0 };
        out.push(byte);
    }
    out
}

fn sei_nits(rbsp: &[u8]) -> Option<f32> {
    let mut at = 0;
    while at + 2 <= rbsp.len() && rbsp[at] != 0x80 {
        let mut kind = 0usize;
        while at < rbsp.len() && rbsp[at] == 0xFF {
            kind += 255;
            at += 1;
        }
        kind += *rbsp.get(at)? as usize;
        at += 1;
        let mut size = 0usize;
        while at < rbsp.len() && rbsp[at] == 0xFF {
            size += 255;
            at += 1;
        }
        size += *rbsp.get(at)? as usize;
        at += 1;
        let payload = rbsp.get(at..at + size)?;
        if kind == 4 {
            if let Some(nits) = t35_nits(payload) {
                return Some(nits);
            }
        }
        at += size;
    }
    None
}

fn next_start(data: &[u8], from: usize) -> Option<usize> {
    let mut i = from;
    while i + 3 <= data.len() {
        if data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 {
            return Some(i + 3);
        }
        i += 1;
    }
    None
}

pub fn hevc_hdr10_plus_nits(annexb: &[u8]) -> Option<f32> {
    let mut at = next_start(annexb, 0)?;
    loop {
        let kind = (*annexb.get(at)? >> 1) & 0x3F;
        if kind < 32 {
            return None;
        }
        let next = next_start(annexb, at);
        if kind == 39 {
            let mut end = next.map(|n| n - 3).unwrap_or(annexb.len());
            while end > at && annexb[end - 1] == 0 {
                end -= 1;
            }
            if end > at + 2 {
                if let Some(nits) = sei_nits(&unescape(&annexb[at + 2..end])) {
                    return Some(nits);
                }
            }
        }
        at = next?;
    }
}

impl ColorInfo {
    pub fn from_params(par: &sys::AVCodecParameters) -> Self {
        let light = light_level(coded_side_data(par, sys::AVPacketSideDataType::AV_PKT_DATA_CONTENT_LIGHT_LEVEL));
        let mastering = mastering_peak(coded_side_data(par, sys::AVPacketSideDataType::AV_PKT_DATA_MASTERING_DISPLAY_METADATA));
        Self {
            space: par.color_space,
            range: par.color_range,
            primaries: par.color_primaries,
            transfer: par.color_trc,
            peak_nits: light.map(|l| l.0).or(mastering).unwrap_or(0.0),
            average_nits: light.map(|l| l.1).unwrap_or(0.0),
            mastering_nits: mastering.unwrap_or(0.0),
            scene_nits: 0.0,
            dovi_reshape: false,
        }
    }

    pub fn with_dovi_reshape(self) -> Self {
        Self {
            primaries: sys::AVColorPrimaries::AVCOL_PRI_BT2020,
            transfer: sys::AVColorTransferCharacteristic::AVCOL_TRC_SMPTE2084,
            dovi_reshape: true,
            ..self
        }
    }

    pub fn merged_with(self, frame: &Frame) -> Self {
        if self.dovi_reshape {
            return self;
        }
        let raw = frame.raw();
        Self {
            space: if raw.colorspace == sys::AVColorSpace::AVCOL_SPC_UNSPECIFIED { self.space } else { raw.colorspace },
            range: if raw.color_range == sys::AVColorRange::AVCOL_RANGE_UNSPECIFIED { self.range } else { raw.color_range },
            primaries: if raw.color_primaries == sys::AVColorPrimaries::AVCOL_PRI_UNSPECIFIED {
                self.primaries
            } else {
                raw.color_primaries
            },
            transfer: if raw.color_trc == sys::AVColorTransferCharacteristic::AVCOL_TRC_UNSPECIFIED {
                self.transfer
            } else {
                raw.color_trc
            },
            peak_nits: self.peak_nits,
            average_nits: self.average_nits,
            mastering_nits: self.mastering_nits,
            scene_nits: hdr10_plus_nits(frame.side_data(sys::AVFrameSideDataType::AV_FRAME_DATA_DYNAMIC_HDR_PLUS)).unwrap_or(0.0),
            dovi_reshape: false,
        }
    }

    pub fn with_scene_nits(self, nits: f32) -> Self {
        Self { scene_nits: if nits.is_finite() && nits > 0.0 { nits } else { 0.0 }, ..self }
    }
}

pub struct VideoFrame {
    pub data: FrameData,
    pub pts_us: i64,
    pub duration_us: i64,
    pub serial: u64,
    pub rotation: i32,
    pub sample_aspect: (i32, i32),
    pub color: ColorInfo,
}

impl VideoFrame {
    pub fn width(&self) -> i32 {
        match &self.data {
            FrameData::Software(frame) => frame.width(),
            FrameData::Hardware(frame) => frame.width(),
        }
    }

    pub fn height(&self) -> i32 {
        match &self.data {
            FrameData::Software(frame) => frame.height(),
            FrameData::Hardware(frame) => frame.height(),
        }
    }

    pub fn display_aspect(&self) -> f64 {
        let width = self.width().max(1) as f64;
        let height = self.height().max(1) as f64;
        let (num, den) = self.sample_aspect;
        let sar = if num > 0 && den > 0 { num as f64 / den as f64 } else { 1.0 };
        width * sar / height
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hevc_sei_without_hdr10_plus_is_ignored() {
        let stream = [0, 0, 0, 1, 0x4E, 0x01, 0x05, 0x02, 0xAA, 0xBB, 0x80, 0, 0, 1, 0x26, 0x01, 0xAF];
        assert_eq!(hevc_hdr10_plus_nits(&stream), None);
    }

    #[test]
    fn emulation_prevention_bytes_are_removed() {
        assert_eq!(unescape(&[0, 0, 3, 1, 0, 0, 3, 0]), vec![0, 0, 1, 0, 0, 0]);
    }
}
