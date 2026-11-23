#![allow(non_camel_case_types, non_snake_case, dead_code)]

use std::ffi::{c_char, c_void};

pub type media_status_t = i32;

pub const AMEDIA_OK: media_status_t = 0;
pub const AMEDIACODEC_INFO_TRY_AGAIN_LATER: isize = -1;
pub const AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED: isize = -2;
pub const AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED: isize = -3;
pub const AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG: u32 = 2;
pub const AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM: u32 = 4;
pub const AIMAGE_FORMAT_PRIVATE: i32 = 0x22;
pub const AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE: u64 = 1 << 8;

#[repr(C)]
pub struct AMediaCodec {
    _private: [u8; 0],
}

#[repr(C)]
pub struct AMediaFormat {
    _private: [u8; 0],
}

#[repr(C)]
pub struct AMediaCrypto {
    _private: [u8; 0],
}

#[repr(C)]
pub struct AImageReader {
    _private: [u8; 0],
}

#[repr(C)]
pub struct AImage {
    _private: [u8; 0],
}

#[repr(C)]
pub struct AHardwareBuffer {
    _private: [u8; 0],
}

#[repr(C)]
pub struct ANativeWindow {
    _private: [u8; 0],
}

#[repr(C)]
#[derive(Default, Clone, Copy)]
pub struct AMediaCodecBufferInfo {
    pub offset: i32,
    pub size: i32,
    pub presentationTimeUs: i64,
    pub flags: u32,
}

#[repr(C)]
#[derive(Default, Clone, Copy)]
pub struct AImageCropRect {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

#[repr(C)]
pub struct AImageReader_ImageListener {
    pub context: *mut c_void,
    pub onImageAvailable: Option<unsafe extern "C" fn(context: *mut c_void, reader: *mut AImageReader)>,
}

#[repr(C)]
#[derive(Default, Clone, Copy)]
pub struct AHardwareBuffer_Desc {
    pub width: u32,
    pub height: u32,
    pub layers: u32,
    pub format: u32,
    pub usage: u64,
    pub stride: u32,
    pub rfu0: u32,
    pub rfu1: u64,
}

#[link(name = "mediandk")]
unsafe extern "C" {
    pub fn AMediaCodec_createDecoderByType(mime_type: *const c_char) -> *mut AMediaCodec;
    pub fn AMediaCodec_delete(codec: *mut AMediaCodec) -> media_status_t;
    pub fn AMediaCodec_configure(
        codec: *mut AMediaCodec,
        format: *const AMediaFormat,
        surface: *mut ANativeWindow,
        crypto: *mut AMediaCrypto,
        flags: u32,
    ) -> media_status_t;
    pub fn AMediaCodec_start(codec: *mut AMediaCodec) -> media_status_t;
    pub fn AMediaCodec_stop(codec: *mut AMediaCodec) -> media_status_t;
    pub fn AMediaCodec_flush(codec: *mut AMediaCodec) -> media_status_t;
    pub fn AMediaCodec_getInputBuffer(codec: *mut AMediaCodec, idx: usize, out_size: *mut usize) -> *mut u8;
    pub fn AMediaCodec_dequeueInputBuffer(codec: *mut AMediaCodec, timeout_us: i64) -> isize;
    pub fn AMediaCodec_queueInputBuffer(
        codec: *mut AMediaCodec,
        idx: usize,
        offset: libc::off_t,
        size: usize,
        time: u64,
        flags: u32,
    ) -> media_status_t;
    pub fn AMediaCodec_dequeueOutputBuffer(codec: *mut AMediaCodec, info: *mut AMediaCodecBufferInfo, timeout_us: i64) -> isize;
    pub fn AMediaCodec_getOutputFormat(codec: *mut AMediaCodec) -> *mut AMediaFormat;
    pub fn AMediaCodec_releaseOutputBuffer(codec: *mut AMediaCodec, idx: usize, render: bool) -> media_status_t;

    pub fn AMediaFormat_new() -> *mut AMediaFormat;
    pub fn AMediaFormat_delete(format: *mut AMediaFormat) -> media_status_t;
    pub fn AMediaFormat_toString(format: *mut AMediaFormat) -> *const c_char;
    pub fn AMediaFormat_getInt32(format: *mut AMediaFormat, name: *const c_char, out: *mut i32) -> bool;
    pub fn AMediaFormat_setInt32(format: *mut AMediaFormat, name: *const c_char, value: i32);
    pub fn AMediaFormat_setString(format: *mut AMediaFormat, name: *const c_char, value: *const c_char);
    pub fn AMediaFormat_setBuffer(format: *mut AMediaFormat, name: *const c_char, data: *const c_void, size: usize);

    pub fn AImageReader_newWithUsage(
        width: i32,
        height: i32,
        format: i32,
        usage: u64,
        max_images: i32,
        reader: *mut *mut AImageReader,
    ) -> media_status_t;
    pub fn AImageReader_delete(reader: *mut AImageReader);
    pub fn AImageReader_getWindow(reader: *mut AImageReader, window: *mut *mut ANativeWindow) -> media_status_t;
    pub fn AImageReader_acquireNextImage(reader: *mut AImageReader, image: *mut *mut AImage) -> media_status_t;
    pub fn AImageReader_setImageListener(reader: *mut AImageReader, listener: *mut AImageReader_ImageListener) -> media_status_t;
    pub fn AImage_delete(image: *mut AImage);
    pub fn AImage_getTimestamp(image: *const AImage, timestamp_ns: *mut i64) -> media_status_t;
    pub fn AImage_getCropRect(image: *const AImage, rect: *mut AImageCropRect) -> media_status_t;
    pub fn AImage_getWidth(image: *const AImage, width: *mut i32) -> media_status_t;
    pub fn AImage_getHeight(image: *const AImage, height: *mut i32) -> media_status_t;
    pub fn AImage_getHardwareBuffer(image: *const AImage, buffer: *mut *mut AHardwareBuffer) -> media_status_t;
}

#[link(name = "android")]
unsafe extern "C" {
    pub fn AHardwareBuffer_describe(buffer: *const AHardwareBuffer, desc: *mut AHardwareBuffer_Desc);
    pub fn ANativeWindow_acquire(window: *mut ANativeWindow);
    pub fn ANativeWindow_release(window: *mut ANativeWindow);
}

pub fn api_level() -> i32 {
    static LEVEL: std::sync::OnceLock<i32> = std::sync::OnceLock::new();
    *LEVEL.get_or_init(|| {
        let mut value = [0 as c_char; 92];
        let length = unsafe { libc::__system_property_get(c"ro.build.version.sdk".as_ptr(), value.as_mut_ptr()) };
        if length <= 0 {
            return 26;
        }
        let text = unsafe { std::ffi::CStr::from_ptr(value.as_ptr()) }.to_string_lossy();
        text.trim().parse().unwrap_or(26)
    })
}

pub type GetNameFn = unsafe extern "C" fn(codec: *mut AMediaCodec, name: *mut *mut c_char) -> media_status_t;
pub type ReleaseNameFn = unsafe extern "C" fn(codec: *mut AMediaCodec, name: *mut c_char);

pub fn codec_name(codec: *mut AMediaCodec) -> Option<String> {
    if api_level() < 28 {
        return None;
    }
    unsafe {
        let library = libc::dlopen(c"libmediandk.so".as_ptr(), libc::RTLD_NOW | libc::RTLD_NOLOAD);
        if library.is_null() {
            return None;
        }
        let get = libc::dlsym(library, c"AMediaCodec_getName".as_ptr());
        let release = libc::dlsym(library, c"AMediaCodec_releaseName".as_ptr());
        if get.is_null() || release.is_null() {
            return None;
        }
        let get: GetNameFn = std::mem::transmute(get);
        let release: ReleaseNameFn = std::mem::transmute(release);
        let mut name = std::ptr::null_mut();
        if get(codec, &mut name) != AMEDIA_OK || name.is_null() {
            return None;
        }
        let value = std::ffi::CStr::from_ptr(name).to_string_lossy().into_owned();
        release(codec, name);
        Some(value)
    }
}
