use std::ffi::CStr;
use std::os::raw::{c_char, c_int};

use lucky_ffmpeg_sys as sys;

unsafe extern "C" fn sink(level: c_int, line: *const c_char) {
    if line.is_null() {
        return;
    }
    let text = unsafe { CStr::from_ptr(line) }.to_string_lossy();
    let text = text.trim_end();
    if text.is_empty() {
        return;
    }
    let level = level as u32;
    if level <= sys::AV_LOG_ERROR {
        log::error!(target: "ffmpeg", "{text}");
    } else if level <= sys::AV_LOG_WARNING {
        log::warn!(target: "ffmpeg", "{text}");
    } else if level <= sys::AV_LOG_INFO {
        log::info!(target: "ffmpeg", "{text}");
    } else if level <= sys::AV_LOG_VERBOSE {
        log::debug!(target: "ffmpeg", "{text}");
    } else {
        log::trace!(target: "ffmpeg", "{text}");
    }
}

pub fn install_log_bridge(verbose: bool) {
    let level = if verbose { sys::AV_LOG_VERBOSE } else { sys::AV_LOG_WARNING };
    unsafe { sys::lucky_ffmpeg_set_log_sink(Some(sink), level as c_int) };
}
