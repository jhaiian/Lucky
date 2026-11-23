use lucky_ffmpeg::{Frame, sys};
use lucky_player::{ColorInfo, DisplayLuminance, DisplayOptions, light_level, mastering_peak};

pub(crate) const SDR_WHITE_NITS: f32 = 203.0;
pub(crate) const MEASURE_OUTPUT: i32 = 4;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Output {
    Srgb = 0,
    P3 = 1,
    Pq = 2,
    P3Linear = 3,
}

impl Output {
    pub(crate) fn from_code(code: i32) -> Self {
        match code {
            1 => Output::P3,
            2 => Output::Pq,
            3 => Output::P3Linear,
            _ => Output::Srgb,
        }
    }
}

pub(crate) fn output_candidates(options: &DisplayOptions, color: &ColorInfo) -> Vec<Output> {
    let mut candidates = Vec::with_capacity(3);
    if options.hdr && transfer_of(color.transfer) != Transfer::Sdr {
        candidates.push(Output::Pq);
    }
    if options.wide_color {
        candidates.push(Output::P3);
        candidates.push(Output::P3Linear);
    }
    candidates.push(Output::Srgb);
    candidates
}

pub(crate) fn is_bt2020(color: &ColorInfo) -> bool {
    color.primaries == sys::AVColorPrimaries::AVCOL_PRI_BT2020
        || (color.primaries == sys::AVColorPrimaries::AVCOL_PRI_UNSPECIFIED && transfer_of(color.transfer) != Transfer::Sdr)
}

const FALLBACK_PEAK_NITS: f32 = 1000.0;

pub(crate) fn plausible_peak(nits: f32) -> bool {
    (SDR_WHITE_NITS..10000.0).contains(&nits)
}

fn static_peak(light: Option<f32>, mastering: Option<f32>) -> f32 {
    let light = light.filter(|nits| plausible_peak(*nits));
    let mastering = mastering.filter(|nits| plausible_peak(*nits));
    match (light, mastering) {
        (Some(light), Some(mastering)) => light.min(mastering),
        (Some(peak), None) | (None, Some(peak)) => peak,
        (None, None) => FALLBACK_PEAK_NITS,
    }
}

const DISPLAY_BLACK_LIMIT_NITS: f32 = 0.5;
const SDR_BLACK_LIMIT: f32 = 0.0025;

fn display_peak_nits(luminance: &DisplayLuminance) -> Option<f32> {
    (300.0..=10000.0).contains(&luminance.max_nits).then_some(luminance.max_nits)
}

fn sdr_white_nits(luminance: &DisplayLuminance) -> f32 {
    if luminance.headroom > 1.01 && luminance.max_nits > 0.0 {
        return (luminance.max_nits / luminance.headroom).max(80.0);
    }
    if luminance.average_nits >= 80.0 && (luminance.max_nits <= 0.0 || luminance.average_nits <= luminance.max_nits) {
        return luminance.average_nits;
    }
    if luminance.max_nits >= 80.0 { luminance.max_nits } else { SDR_WHITE_NITS }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub(crate) struct ToneTarget {
    pub(crate) peak: f32,
    pub(crate) black: f32,
}

impl ToneTarget {
    pub(crate) fn new(output: Output, luminance: &DisplayLuminance) -> Self {
        let black_nits = if luminance.min_nits > 0.0 && luminance.min_nits.is_finite() {
            luminance.min_nits.min(DISPLAY_BLACK_LIMIT_NITS)
        } else {
            0.0
        };
        match output {
            Output::Pq => match display_peak_nits(luminance) {
                Some(peak) => Self { peak: peak / SDR_WHITE_NITS, black: black_nits / SDR_WHITE_NITS },
                None => Self { peak: 0.0, black: 0.0 },
            },
            _ => Self { peak: 1.0, black: (black_nits / sdr_white_nits(luminance)).min(SDR_BLACK_LIMIT) },
        }
    }

    pub(crate) fn maps_to_display(&self) -> bool {
        self.peak > 0.0
    }

    pub(crate) fn hlg_nits(&self, output: Output) -> f32 {
        if output == Output::Pq && self.peak > 0.0 { (self.peak * SDR_WHITE_NITS).clamp(400.0, 2000.0) } else { 1000.0 }
    }

    pub(crate) fn should_measure(&self, output: Output, color: &ColorInfo) -> bool {
        if color.scene_nits > 0.0 {
            return false;
        }
        match transfer_of(color.transfer) {
            Transfer::Sdr => false,
            Transfer::Pq => output != Output::Pq || self.maps_to_display(),
            Transfer::Hlg => output != Output::Pq,
        }
    }

    pub(crate) fn display_nits(&self, content_nits: f32) -> f32 {
        if self.maps_to_display() { content_nits.min(self.peak * SDR_WHITE_NITS) } else { content_nits }
    }

    pub(crate) fn packed(&self) -> u32 {
        half_bits(self.peak) as u32 | (half_bits(self.black) as u32) << 16
    }
}

pub(crate) fn half_bits(value: f32) -> u16 {
    let bits = value.to_bits();
    let sign = ((bits >> 16) & 0x8000) as u16;
    let exponent = ((bits >> 23) & 0xff) as i32;
    let mantissa = bits & 0x7f_ffff;
    if exponent == 0xff {
        return sign | 0x7c00 | if mantissa != 0 { 0x200 } else { 0 };
    }
    let unbiased = exponent - 127;
    if unbiased > 15 {
        return sign | 0x7bff;
    }
    if unbiased >= -14 {
        let combined = (((unbiased + 15) as u32) << 10) + ((mantissa + 0x1000) >> 13);
        return sign | combined.min(0x7bff) as u16;
    }
    if unbiased < -25 {
        return sign;
    }
    let shift = (-unbiased - 1) as u32;
    let full = mantissa | 0x80_0000;
    sign | ((full + (1 << (shift - 1))) >> shift) as u16
}

pub(crate) fn content_peak(frame: Option<&Frame>, color: &ColorInfo, hlg_nits: f32) -> f32 {
    if transfer_of(color.transfer) == Transfer::Hlg {
        return hlg_nits;
    }
    if color.scene_nits > 0.0 {
        return color.scene_nits.clamp(SDR_WHITE_NITS, 10000.0);
    }
    let mut light = (color.peak_nits > 0.0).then_some(color.peak_nits);
    let mut mastering = (color.mastering_nits > 0.0).then_some(color.mastering_nits);
    if let Some(frame) = frame {
        if let Some((peak, _)) = light_level(frame.side_data(sys::AVFrameSideDataType::AV_FRAME_DATA_CONTENT_LIGHT_LEVEL)) {
            light = Some(peak);
        }
        if let Some(peak) = mastering_peak(frame.side_data(sys::AVFrameSideDataType::AV_FRAME_DATA_MASTERING_DISPLAY_METADATA)) {
            mastering = Some(peak);
        }
    }
    static_peak(light, mastering)
}

const PQ_M1: f32 = 0.159_301_76;
const PQ_M2: f32 = 78.843_75;
const PQ_C1: f32 = 0.835_937_5;
const PQ_C2: f32 = 18.851_563;
const PQ_C3: f32 = 18.6875;

pub(crate) fn pq_to_nits(e: f32) -> f32 {
    let p = e.clamp(0.0, 1.0).powf(1.0 / PQ_M2);
    ((p - PQ_C1).max(0.0) / (PQ_C2 - PQ_C3 * p)).powf(1.0 / PQ_M1) * 10000.0
}

#[cfg(test)]
pub(crate) fn nits_to_pq(nits: f32) -> f32 {
    let y = (nits / 10000.0).clamp(0.0, 1.0).powf(PQ_M1);
    ((PQ_C1 + PQ_C2 * y) / (1.0 + PQ_C3 * y)).powf(PQ_M2)
}

pub(crate) struct ToneAnalyzer {
    peak: f32,
    average: f32,
    ready: bool,
    serial: u64,
}

impl Default for ToneAnalyzer {
    fn default() -> Self {
        Self { peak: 0.0, average: 0.0, ready: false, serial: u64::MAX }
    }
}

impl ToneAnalyzer {
    pub(crate) fn measure(pixels: &[u8]) -> Option<(f32, f32)> {
        let count = pixels.len() / 4;
        if count == 0 {
            return None;
        }
        let mut histogram = [0u32; 256];
        let mut sum = 0u64;
        for pixel in pixels.chunks_exact(4) {
            histogram[pixel[0] as usize] += 1;
            sum += pixel[1] as u64;
        }
        let outliers = (count as f32 * 0.001) as u32;
        let mut seen = 0;
        let mut peak = 0;
        for code in (0..256).rev() {
            seen += histogram[code];
            if seen > outliers {
                peak = code;
                break;
            }
        }
        Some((peak as f32 / 255.0, sum as f32 / count as f32 / 255.0))
    }

    pub(crate) fn reset(&mut self, serial: u64) {
        if self.serial != serial {
            self.serial = serial;
            self.ready = false;
        }
    }

    pub(crate) fn push(&mut self, peak: f32, average: f32) {
        if !self.ready {
            self.peak = peak;
            self.average = average;
            self.ready = true;
            return;
        }
        let change = ((average - self.average).abs() - 0.04) / 0.06;
        let mut weight = 0.04 + 0.96 * change.clamp(0.0, 1.0);
        if peak > self.peak {
            weight = weight.max(0.25);
        }
        self.peak += (peak - self.peak) * weight;
        self.average += (average - self.average) * weight;
    }

    pub(crate) fn peak_nits(&self) -> Option<f32> {
        self.ready.then(|| pq_to_nits(self.peak).max(SDR_WHITE_NITS))
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct YuvTransform {
    pub matrix: [f32; 9],
    pub offset: [f32; 3],
}

fn coefficients(space: sys::AVColorSpace, height: i32) -> (f32, f32) {
    match space {
        sys::AVColorSpace::AVCOL_SPC_BT709 => (0.2126, 0.0722),
        sys::AVColorSpace::AVCOL_SPC_BT470BG | sys::AVColorSpace::AVCOL_SPC_SMPTE170M => (0.299, 0.114),
        sys::AVColorSpace::AVCOL_SPC_BT2020_NCL | sys::AVColorSpace::AVCOL_SPC_BT2020_CL => (0.2627, 0.0593),
        sys::AVColorSpace::AVCOL_SPC_SMPTE240M => (0.212, 0.087),
        sys::AVColorSpace::AVCOL_SPC_FCC => (0.30, 0.11),
        _ => {
            if height >= 720 {
                (0.2126, 0.0722)
            } else {
                (0.299, 0.114)
            }
        }
    }
}

pub fn yuv_transform(space: sys::AVColorSpace, range: sys::AVColorRange, depth: u32, height: i32) -> YuvTransform {
    let (kr, kb) = coefficients(space, height);
    let kg = 1.0 - kr - kb;
    let depth = depth.clamp(8, 16);
    let max = ((1u32 << depth) - 1) as f32;
    let shift = (depth - 8) as f32;
    let full = range == sys::AVColorRange::AVCOL_RANGE_JPEG;
    let (y_offset, y_scale, c_offset, c_scale) = if full {
        (0.0, 1.0, (1u32 << (depth - 1)) as f32 / max, 1.0)
    } else {
        let unit = 2f32.powf(shift);
        (16.0 * unit / max, max / (219.0 * unit), 128.0 * unit / max, max / (224.0 * unit))
    };
    let matrix = [
        y_scale,
        y_scale,
        y_scale,
        0.0,
        -c_scale * 2.0 * kb * (1.0 - kb) / kg,
        c_scale * 2.0 * (1.0 - kb),
        c_scale * 2.0 * (1.0 - kr),
        -c_scale * 2.0 * kr * (1.0 - kr) / kg,
        0.0,
    ];
    YuvTransform { matrix, offset: [y_offset, c_offset, c_offset] }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Transfer {
    Sdr = 0,
    Pq = 1,
    Hlg = 2,
}

pub fn transfer_of(trc: sys::AVColorTransferCharacteristic) -> Transfer {
    match trc {
        sys::AVColorTransferCharacteristic::AVCOL_TRC_SMPTE2084 => Transfer::Pq,
        sys::AVColorTransferCharacteristic::AVCOL_TRC_ARIB_STD_B67 => Transfer::Hlg,
        _ => Transfer::Sdr,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn apply(t: &YuvTransform, yuv: [f32; 3]) -> [f32; 3] {
        let v = [yuv[0] - t.offset[0], yuv[1] - t.offset[1], yuv[2] - t.offset[2]];
        let m = t.matrix;
        [
            m[0] * v[0] + m[3] * v[1] + m[6] * v[2],
            m[1] * v[0] + m[4] * v[1] + m[7] * v[2],
            m[2] * v[0] + m[5] * v[1] + m[8] * v[2],
        ]
    }

    #[test]
    fn pq_round_trip() {
        for nits in [0.5f32, 100.0, 203.0, 1000.0, 4000.0] {
            let back = pq_to_nits(nits_to_pq(nits));
            assert!((back - nits).abs() / nits < 1e-3, "{nits} -> {back}");
        }
    }

    #[test]
    fn half_floats_round_trip_tone_values() {
        assert_eq!(half_bits(1.0), 0x3c00);
        assert_eq!(half_bits(0.5), 0x3800);
        assert_eq!(half_bits(-2.0), 0xc000);
        assert_eq!(half_bits(0.0), 0);
        assert_eq!(half_bits(1.0e6), 0x7bff);
        assert_eq!(half_bits(2f32.powi(-24)), 1);
        assert_eq!(half_bits(2f32.powi(-20)), 16);
    }

    #[test]
    fn tone_target_follows_the_screen() {
        let oled = DisplayLuminance { max_nits: 1600.0, average_nits: 600.0, min_nits: 0.0005, headroom: 0.0 };
        let pq = ToneTarget::new(Output::Pq, &oled);
        assert!((pq.peak * SDR_WHITE_NITS - 1600.0).abs() < 0.5);
        assert!(pq.black > 0.0 && pq.black < 1e-5);
        assert!((pq.hlg_nits(Output::Pq) - 1600.0).abs() < 0.5);
        let sdr = ToneTarget::new(Output::Srgb, &oled);
        assert_eq!(sdr.peak, 1.0);
        assert!(sdr.black < 1e-5);
        assert_eq!(sdr.hlg_nits(Output::Srgb), 1000.0);
        let lcd = DisplayLuminance { max_nits: 500.0, average_nits: 450.0, min_nits: 0.4, headroom: 0.0 };
        let sdr = ToneTarget::new(Output::Srgb, &lcd);
        assert!((sdr.black - 0.4 / 450.0).abs() < 1e-6);
        let unknown = ToneTarget::new(Output::Pq, &DisplayLuminance::default());
        assert!(!unknown.maps_to_display());
        assert_eq!(unknown.hlg_nits(Output::Pq), 1000.0);
        assert_eq!(unknown.display_nits(4000.0), 4000.0);
        assert_eq!(pq.display_nits(4000.0), 1600.0);
    }

    #[test]
    fn static_peak_ignores_bogus_metadata() {
        assert_eq!(static_peak(Some(1500.0), Some(4000.0)), 1500.0);
        assert_eq!(static_peak(Some(10000.0), Some(1000.0)), 1000.0);
        assert_eq!(static_peak(Some(4000.0), Some(1000.0)), 1000.0);
        assert_eq!(static_peak(Some(65535.0), None), FALLBACK_PEAK_NITS);
        assert_eq!(static_peak(Some(1.0), Some(4000.0)), 4000.0);
        assert_eq!(static_peak(None, Some(10000.0)), FALLBACK_PEAK_NITS);
        assert_eq!(static_peak(None, None), FALLBACK_PEAK_NITS);
    }

    #[test]
    fn analyzer_ignores_tiny_outliers_and_tracks_scenes() {
        let mut pixels = vec![0u8; 128 * 72 * 4];
        let bright = (nits_to_pq(1000.0) * 255.0).round() as u8;
        let mid = (nits_to_pq(400.0) * 255.0).round() as u8;
        for (index, pixel) in pixels.chunks_exact_mut(4).enumerate() {
            pixel[0] = if index < 3 { 255 } else if index < 200 { bright } else { mid };
            pixel[1] = mid / 2;
        }
        let (peak, average) = ToneAnalyzer::measure(&pixels).unwrap();
        let mut analyzer = ToneAnalyzer::default();
        analyzer.reset(1);
        analyzer.push(peak, average);
        let first = analyzer.peak_nits().unwrap();
        assert!((first - 1000.0).abs() < 60.0, "{first}");
        analyzer.push(nits_to_pq(300.0), average);
        let smoothed = analyzer.peak_nits().unwrap();
        assert!(smoothed > 800.0, "{smoothed}");
        analyzer.push(nits_to_pq(300.0), average + 0.2);
        let cut = analyzer.peak_nits().unwrap();
        assert!((cut - 300.0).abs() < 20.0, "{cut}");
        analyzer.reset(2);
        assert!(analyzer.peak_nits().is_none());
    }

    #[test]
    fn limited_range_white_and_black() {
        let t = yuv_transform(sys::AVColorSpace::AVCOL_SPC_BT709, sys::AVColorRange::AVCOL_RANGE_MPEG, 8, 1080);
        let white = apply(&t, [235.0 / 255.0, 128.0 / 255.0, 128.0 / 255.0]);
        let black = apply(&t, [16.0 / 255.0, 128.0 / 255.0, 128.0 / 255.0]);
        for c in white {
            assert!((c - 1.0).abs() < 1e-4, "{white:?}");
        }
        for c in black {
            assert!(c.abs() < 1e-4, "{black:?}");
        }
    }

    #[test]
    fn bt709_red() {
        let t = yuv_transform(sys::AVColorSpace::AVCOL_SPC_BT709, sys::AVColorRange::AVCOL_RANGE_JPEG, 8, 1080);
        let (kr, kb) = (0.2126f32, 0.0722f32);
        let y = kr;
        let cb = -kr / (2.0 * (1.0 - kb)) + 128.0 / 255.0;
        let cr = (1.0 - kr) / (2.0 * (1.0 - kr)) + 128.0 / 255.0;
        let rgb = apply(&t, [y, cb, cr]);
        assert!((rgb[0] - 1.0).abs() < 1e-3 && rgb[1].abs() < 1e-3 && rgb[2].abs() < 1e-3, "{rgb:?}");
    }

    #[test]
    fn ten_bit_limited_white() {
        let t = yuv_transform(sys::AVColorSpace::AVCOL_SPC_BT2020_NCL, sys::AVColorRange::AVCOL_RANGE_MPEG, 10, 2160);
        let white = apply(&t, [940.0 / 1023.0, 512.0 / 1023.0, 512.0 / 1023.0]);
        for c in white {
            assert!((c - 1.0).abs() < 1e-4, "{white:?}");
        }
    }
}
