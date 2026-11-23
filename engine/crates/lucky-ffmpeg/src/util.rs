use std::ffi::{CStr, CString};
use std::os::raw::c_char;

use lucky_ffmpeg_sys as sys;

pub fn c_str(ptr: *const c_char) -> Option<String> {
    if ptr.is_null() {
        return None;
    }
    let value = unsafe { CStr::from_ptr(ptr) }.to_string_lossy().trim().to_string();
    if value.is_empty() { None } else { Some(value) }
}

pub fn dict_get(dict: *const sys::AVDictionary, key: &str) -> Option<String> {
    if dict.is_null() {
        return None;
    }
    let key = CString::new(key).ok()?;
    let entry = unsafe { sys::av_dict_get(dict, key.as_ptr(), std::ptr::null(), 0) };
    if entry.is_null() {
        return None;
    }
    c_str(unsafe { (*entry).value })
}

pub fn rational_to_f64(value: sys::AVRational) -> Option<f64> {
    if value.num <= 0 || value.den <= 0 {
        None
    } else {
        Some(value.num as f64 / value.den as f64)
    }
}

pub fn version_string(packed: u32) -> String {
    format!("{}.{}.{}", packed >> 16, (packed >> 8) & 0xff, packed & 0xff)
}

pub fn rescale_to_ms(value: i64, time_base: sys::AVRational) -> Option<i64> {
    if value == sys::AV_NOPTS_VALUE || time_base.den == 0 {
        return None;
    }
    Some(unsafe { sys::av_rescale_q(value, time_base, sys::AVRational { num: 1, den: 1000 }) })
}
