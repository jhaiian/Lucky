use std::mem::size_of;
use std::ptr;

use lucky_ffmpeg::sys;

use crate::color::pq_to_nits;

pub(crate) const DOVI_WIDTH: i32 = 68;
pub(crate) const DOVI_HEIGHT: i32 = 4;
const PIECES: usize = 8;

const LMS_TO_RGB: [[f64; 3]; 3] = [
    [3.06441879, -2.16597676, 0.10155818],
    [-0.65612108, 1.78554118, -0.12943749],
    [0.01736321, -0.04725154, 1.03004253],
];

pub(crate) struct DoviTable {
    pub texels: Vec<f32>,
    pub peak_nits: f32,
}

unsafe fn read_at<T>(bytes: &[u8], offset: usize) -> Option<T> {
    let end = offset.checked_add(size_of::<T>())?;
    if end > bytes.len() {
        return None;
    }
    Some(unsafe { ptr::read_unaligned(bytes.as_ptr().add(offset) as *const T) })
}

fn ratio(value: sys::AVRational) -> f64 {
    if value.den == 0 { 0.0 } else { value.num as f64 / value.den as f64 }
}

fn set(texels: &mut [f32], x: usize, y: usize, value: [f32; 4]) {
    let index = (y * DOVI_WIDTH as usize + x) * 4;
    texels[index..index + 4].copy_from_slice(&value);
}

pub(crate) fn dovi_table(bytes: &[u8]) -> Option<DoviTable> {
    unsafe {
        let meta: sys::AVDOVIMetadata = read_at(bytes, 0)?;
        let header: sys::AVDOVIRpuDataHeader = read_at(bytes, meta.header_offset)?;
        let mapping: sys::AVDOVIDataMapping = read_at(bytes, meta.mapping_offset)?;
        let color: sys::AVDOVIColorMetadata = read_at(bytes, meta.color_offset)?;
        if header.bl_bit_depth < 8 || header.bl_bit_depth > 16 || header.coef_log2_denom > 32 {
            return None;
        }
        let pivot_scale = 1.0 / ((1u32 << header.bl_bit_depth) - 1) as f64;
        let coef_scale = 1.0 / (1u64 << header.coef_log2_denom) as f64;
        let coef = |value: i64| (value as f64 * coef_scale) as f32;
        let mut texels = vec![0f32; (DOVI_WIDTH * DOVI_HEIGHT * 4) as usize];
        for (component, curve) in mapping.curves.iter().enumerate() {
            let pivots = (curve.num_pivots as usize).clamp(2, PIECES + 1);
            set(&mut texels, 0, component, [pivots as f32, 0.0, 0.0, 0.0]);
            let mut packed = [0f32; 12];
            for (slot, pivot) in curve.pivots.iter().take(pivots).enumerate() {
                packed[slot] = (*pivot as f64 * pivot_scale) as f32;
            }
            for group in 0..3 {
                let slice = &packed[group * 4..group * 4 + 4];
                set(&mut texels, 1 + group, component, [slice[0], slice[1], slice[2], slice[3]]);
            }
            for piece in 0..pivots - 1 {
                let base = 4 + piece * 8;
                if curve.mapping_idc[piece] == sys::AVDOVIMappingMethod::AV_DOVI_MAPPING_MMR {
                    let order = curve.mmr_order[piece].clamp(1, 3) as usize;
                    set(&mut texels, base, component, [order as f32, coef(curve.mmr_constant[piece]), 0.0, 0.0]);
                    for level in 0..order {
                        let c = curve.mmr_coef[piece][level];
                        set(&mut texels, base + 2 + level * 2, component, [coef(c[0]), coef(c[1]), coef(c[2]), 0.0]);
                        set(&mut texels, base + 3 + level * 2, component, [coef(c[3]), coef(c[4]), coef(c[5]), coef(c[6])]);
                    }
                } else {
                    let order = curve.poly_order[piece].clamp(1, 2) as usize;
                    let mut poly = [0f32; 4];
                    for (slot, value) in poly.iter_mut().enumerate().take(order + 1) {
                        *value = coef(curve.poly_coef[piece][slot]);
                    }
                    set(&mut texels, base + 1, component, poly);
                }
            }
        }
        let nonlinear: Vec<f64> = color.ycc_to_rgb_matrix.iter().map(|v| ratio(*v)).collect();
        let linear: Vec<f64> = color.rgb_to_lms_matrix.iter().map(|v| ratio(*v)).collect();
        if nonlinear.iter().all(|v| *v == 0.0) || linear.iter().all(|v| *v == 0.0) {
            return None;
        }
        let mut combined = [[0f64; 3]; 3];
        for (row, out) in combined.iter_mut().enumerate() {
            for (col, value) in out.iter_mut().enumerate() {
                *value = (0..3).map(|k| LMS_TO_RGB[row][k] * linear[k * 3 + col]).sum();
            }
        }
        for col in 0..3 {
            set(
                &mut texels,
                col,
                3,
                [nonlinear[col] as f32, nonlinear[3 + col] as f32, nonlinear[6 + col] as f32, 0.0],
            );
            set(
                &mut texels,
                4 + col,
                3,
                [combined[0][col] as f32, combined[1][col] as f32, combined[2][col] as f32, 0.0],
            );
        }
        let offset = color.ycc_to_rgb_offset.map(ratio);
        set(&mut texels, 3, 3, [offset[0] as f32, offset[1] as f32, offset[2] as f32, 0.0]);
        let peak_nits = if color.source_max_pq > 0 { pq_to_nits(color.source_max_pq as f32 / 4095.0) } else { 0.0 };
        Some(DoviTable { texels, peak_nits })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::color::nits_to_pq;

    fn texel(table: &DoviTable, x: usize, y: usize) -> [f32; 4] {
        let index = (y * DOVI_WIDTH as usize + x) * 4;
        [table.texels[index], table.texels[index + 1], table.texels[index + 2], table.texels[index + 3]]
    }

    fn pivot(table: &DoviTable, component: usize, index: usize) -> f32 {
        texel(table, 1 + index / 4, component)[index % 4]
    }

    fn dot3(a: [f32; 4], b: [f32; 3]) -> f32 {
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    }

    fn dot4(a: [f32; 4], b: [f32; 4]) -> f32 {
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
    }

    fn reshape(table: &DoviTable, component: usize, sig: [f32; 3]) -> f32 {
        let count = texel(table, 0, component)[0].round() as usize;
        let s = sig[component].clamp(pivot(table, component, 0), pivot(table, component, count - 1));
        let mut piece = 0;
        for i in 1..8 {
            if i >= count - 1 {
                break;
            }
            if s >= pivot(table, component, i) {
                piece = i;
            }
        }
        let base = 4 + piece * 8;
        let meta = texel(table, base, component);
        if meta[0] < 0.5 {
            let k = texel(table, base + 1, component);
            return k[0] + s * (k[1] + s * k[2]);
        }
        let terms = [sig[0] * sig[1], sig[0] * sig[2], sig[1] * sig[2], sig[0] * sig[1] * sig[2]];
        meta[1] + dot3(texel(table, base + 2, component), sig) + dot4(texel(table, base + 3, component), terms)
    }

    fn pq_linear(e: f32) -> f32 {
        pq_to_nits(e) / 203.0
    }

    fn decode(table: &DoviTable, ycc: [f32; 3]) -> [f32; 3] {
        let shaped = [reshape(table, 0, ycc), reshape(table, 1, ycc), reshape(table, 2, ycc)];
        let offset = texel(table, 3, 3);
        let centered = [shaped[0] - offset[0], shaped[1] - offset[1], shaped[2] - offset[2]];
        let mut lms = [0f32; 3];
        for col in 0..3 {
            let column = texel(table, col, 3);
            for row in 0..3 {
                lms[row] += column[row] * centered[col];
            }
        }
        let linear = lms.map(|e| pq_linear(e.clamp(0.0, 1.0)));
        let mut rgb = [0f32; 3];
        for col in 0..3 {
            let column = texel(table, 4 + col, 3);
            for row in 0..3 {
                rgb[row] += column[row] * linear[col];
            }
        }
        rgb
    }

    fn synthetic(chroma_mmr: bool) -> Vec<u8> {
        let meta_size = size_of::<sys::AVDOVIMetadata>();
        let header_offset = meta_size.next_multiple_of(8);
        let mapping_offset = (header_offset + size_of::<sys::AVDOVIRpuDataHeader>()).next_multiple_of(8);
        let color_offset = (mapping_offset + size_of::<sys::AVDOVIDataMapping>()).next_multiple_of(8);
        let total = color_offset + size_of::<sys::AVDOVIColorMetadata>();
        let mut bytes = vec![0u8; total];
        let denom = 23u8;
        let one = 1i64 << denom;
        unsafe {
            let mut meta: sys::AVDOVIMetadata = std::mem::zeroed();
            meta.header_offset = header_offset;
            meta.mapping_offset = mapping_offset;
            meta.color_offset = color_offset;
            let mut header: sys::AVDOVIRpuDataHeader = std::mem::zeroed();
            header.bl_bit_depth = 10;
            header.coef_log2_denom = denom;
            let mut mapping: sys::AVDOVIDataMapping = std::mem::zeroed();
            for (component, curve) in mapping.curves.iter_mut().enumerate() {
                curve.num_pivots = 2;
                curve.pivots[1] = 1023;
                if component > 0 && chroma_mmr {
                    curve.mapping_idc[0] = sys::AVDOVIMappingMethod::AV_DOVI_MAPPING_MMR;
                    curve.mmr_order[0] = 1;
                    curve.mmr_coef[0][0][component] = one;
                } else {
                    curve.mapping_idc[0] = sys::AVDOVIMappingMethod::AV_DOVI_MAPPING_POLYNOMIAL;
                    curve.poly_order[0] = 1;
                    curve.poly_coef[0][1] = one;
                }
            }
            let mut color: sys::AVDOVIColorMetadata = std::mem::zeroed();
            let ycc = [8192, 799, 1681, 8192, -933, 1091, 8192, 267, -5545];
            let lms = [17081, -349, -349, -349, 17081, -349, -349, -349, 17081];
            for i in 0..9 {
                color.ycc_to_rgb_matrix[i] = sys::AVRational { num: ycc[i], den: 1 << 13 };
                color.rgb_to_lms_matrix[i] = sys::AVRational { num: lms[i], den: 1 << 14 };
            }
            color.ycc_to_rgb_offset = [
                sys::AVRational { num: 0, den: 1 << 28 },
                sys::AVRational { num: 1 << 27, den: 1 << 28 },
                sys::AVRational { num: 1 << 27, den: 1 << 28 },
            ];
            color.source_max_pq = (nits_to_pq(1000.0) * 4095.0).round() as u16;
            ptr::write_unaligned(bytes.as_mut_ptr() as *mut sys::AVDOVIMetadata, meta);
            ptr::write_unaligned(bytes.as_mut_ptr().add(header_offset) as *mut sys::AVDOVIRpuDataHeader, header);
            ptr::write_unaligned(bytes.as_mut_ptr().add(mapping_offset) as *mut sys::AVDOVIDataMapping, mapping);
            ptr::write_unaligned(bytes.as_mut_ptr().add(color_offset) as *mut sys::AVDOVIColorMetadata, color);
        }
        bytes
    }

    #[test]
    fn neutral_ipt_decodes_to_grey() {
        for mmr in [false, true] {
            let table = dovi_table(&synthetic(mmr)).expect("table");
            assert!((table.peak_nits - 1000.0).abs() < 5.0, "{}", table.peak_nits);
            let grey = decode(&table, [0.58, 0.5, 0.5]);
            assert!((grey[0] - grey[1]).abs() < 1e-3 && (grey[1] - grey[2]).abs() < 1e-3, "{grey:?}");
            assert!((grey[1] * 203.0 - 203.0).abs() < 25.0, "{grey:?}");
            let tinted = decode(&table, [0.58, 0.5, 0.56]);
            assert!(tinted[0] > tinted[2], "{tinted:?}");
        }
    }

    #[test]
    fn identity_lms_reproduces_hpe_inverse() {
        let mut combined = [[0f64; 3]; 3];
        let identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0];
        for (row, out) in combined.iter_mut().enumerate() {
            for (col, value) in out.iter_mut().enumerate() {
                *value = (0..3).map(|k| LMS_TO_RGB[row][k] * identity[k * 3 + col]).sum();
            }
        }
        let white: f64 = combined[1].iter().sum();
        assert!((white - 1.0).abs() < 1e-3, "{white}");
    }
}
