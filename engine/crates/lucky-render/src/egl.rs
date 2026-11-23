#![allow(non_camel_case_types, dead_code)]

use std::ffi::{CStr, c_char, c_void};
use std::ptr;

pub type EGLDisplay = *mut c_void;
pub type EGLConfig = *mut c_void;
pub type EGLContext = *mut c_void;
pub type EGLSurface = *mut c_void;
pub type EGLint = i32;
pub type EGLBoolean = u32;

pub const EGL_NO_DISPLAY: EGLDisplay = ptr::null_mut();
pub const EGL_NO_CONTEXT: EGLContext = ptr::null_mut();
pub const EGL_NO_SURFACE: EGLSurface = ptr::null_mut();
pub const EGL_NO_CONFIG_KHR: EGLConfig = ptr::null_mut();

pub const EGL_SUCCESS: EGLint = 0x3000;
pub const EGL_BAD_NATIVE_WINDOW: EGLint = 0x300B;
pub const EGL_BAD_SURFACE: EGLint = 0x300D;
pub const EGL_CONTEXT_LOST: EGLint = 0x300E;
pub const EGL_ALPHA_SIZE: EGLint = 0x3021;
pub const EGL_BLUE_SIZE: EGLint = 0x3022;
pub const EGL_GREEN_SIZE: EGLint = 0x3023;
pub const EGL_RED_SIZE: EGLint = 0x3024;
pub const EGL_DEPTH_SIZE: EGLint = 0x3025;
pub const EGL_NATIVE_VISUAL_ID: EGLint = 0x302E;
pub const EGL_SURFACE_TYPE: EGLint = 0x3033;
pub const EGL_NONE: EGLint = 0x3038;
pub const EGL_RENDERABLE_TYPE: EGLint = 0x3040;
pub const EGL_EXTENSIONS: EGLint = 0x3055;
pub const EGL_HEIGHT: EGLint = 0x3056;
pub const EGL_WIDTH: EGLint = 0x3057;
pub const EGL_CONTEXT_CLIENT_VERSION: EGLint = 0x3098;
pub const EGL_WINDOW_BIT: EGLint = 0x0004;
pub const EGL_OPENGL_ES3_BIT: EGLint = 0x0040;
pub const EGL_IMAGE_PRESERVED_KHR: EGLint = 0x30D2;
pub const EGL_NATIVE_BUFFER_ANDROID: u32 = 0x3140;
pub const EGL_GL_COLORSPACE_KHR: EGLint = 0x309D;
pub const EGL_GL_COLORSPACE_BT2020_PQ_EXT: EGLint = 0x3340;
pub const EGL_GL_COLORSPACE_DISPLAY_P3_EXT: EGLint = 0x3363;
pub const EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT: EGLint = 0x3490;
pub const EGL_CTA861_3_MAX_CONTENT_LIGHT_LEVEL_EXT: EGLint = 0x3360;
pub const EGL_CTA861_3_MAX_FRAME_AVERAGE_LEVEL_EXT: EGLint = 0x3361;
pub const EGL_METADATA_SCALING_EXT: f32 = 50000.0;
pub const GL_TEXTURE_EXTERNAL_OES: u32 = 0x8D65;

pub type GetNativeClientBufferFn = unsafe extern "C" fn(buffer: *const c_void) -> *mut c_void;
pub type CreateImageFn =
    unsafe extern "C" fn(display: EGLDisplay, context: EGLContext, target: u32, buffer: *mut c_void, attributes: *const EGLint) -> *mut c_void;
pub type DestroyImageFn = unsafe extern "C" fn(display: EGLDisplay, image: *mut c_void) -> EGLBoolean;
pub type ImageTargetTextureFn = unsafe extern "C" fn(target: u32, image: *mut c_void);

pub type PresentationTimeFn = unsafe extern "C" fn(display: EGLDisplay, surface: EGLSurface, time: i64) -> EGLBoolean;

pub fn load_presentation_time(display: EGLDisplay) -> Option<PresentationTimeFn> {
    if !extensions(display).split_whitespace().any(|e| e == "EGL_ANDROID_presentation_time") {
        return None;
    }
    unsafe {
        let address = eglGetProcAddress(c"eglPresentationTimeANDROID".as_ptr());
        if address.is_null() {
            return None;
        }
        Some(std::mem::transmute::<*mut c_void, PresentationTimeFn>(address))
    }
}

pub fn monotonic_ns() -> i64 {
    let mut time = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut time) };
    time.tv_sec as i64 * 1_000_000_000 + time.tv_nsec as i64
}

#[derive(Clone, Copy)]
pub struct ImageFunctions {
    pub get_client_buffer: GetNativeClientBufferFn,
    pub create_image: CreateImageFn,
    pub destroy_image: DestroyImageFn,
    pub target_texture: ImageTargetTextureFn,
}

impl ImageFunctions {
    pub fn load() -> Option<Self> {
        unsafe {
            let get_client_buffer = eglGetProcAddress(c"eglGetNativeClientBufferANDROID".as_ptr());
            let create_image = eglGetProcAddress(c"eglCreateImageKHR".as_ptr());
            let destroy_image = eglGetProcAddress(c"eglDestroyImageKHR".as_ptr());
            let target_texture = eglGetProcAddress(c"glEGLImageTargetTexture2DOES".as_ptr());
            if get_client_buffer.is_null() || create_image.is_null() || destroy_image.is_null() || target_texture.is_null() {
                return None;
            }
            Some(Self {
                get_client_buffer: std::mem::transmute::<*mut c_void, GetNativeClientBufferFn>(get_client_buffer),
                create_image: std::mem::transmute::<*mut c_void, CreateImageFn>(create_image),
                destroy_image: std::mem::transmute::<*mut c_void, DestroyImageFn>(destroy_image),
                target_texture: std::mem::transmute::<*mut c_void, ImageTargetTextureFn>(target_texture),
            })
        }
    }
}

#[link(name = "EGL")]
unsafe extern "C" {
    pub fn eglGetDisplay(display_id: *mut c_void) -> EGLDisplay;
    pub fn eglInitialize(display: EGLDisplay, major: *mut EGLint, minor: *mut EGLint) -> EGLBoolean;
    pub fn eglChooseConfig(
        display: EGLDisplay,
        attributes: *const EGLint,
        configs: *mut EGLConfig,
        config_size: EGLint,
        count: *mut EGLint,
    ) -> EGLBoolean;
    pub fn eglGetConfigAttrib(display: EGLDisplay, config: EGLConfig, attribute: EGLint, value: *mut EGLint) -> EGLBoolean;
    pub fn eglCreateContext(
        display: EGLDisplay,
        config: EGLConfig,
        share: EGLContext,
        attributes: *const EGLint,
    ) -> EGLContext;
    pub fn eglDestroyContext(display: EGLDisplay, context: EGLContext) -> EGLBoolean;
    pub fn eglCreateWindowSurface(
        display: EGLDisplay,
        config: EGLConfig,
        window: *mut c_void,
        attributes: *const EGLint,
    ) -> EGLSurface;
    pub fn eglDestroySurface(display: EGLDisplay, surface: EGLSurface) -> EGLBoolean;
    pub fn eglMakeCurrent(display: EGLDisplay, draw: EGLSurface, read: EGLSurface, context: EGLContext) -> EGLBoolean;
    pub fn eglSwapBuffers(display: EGLDisplay, surface: EGLSurface) -> EGLBoolean;
    pub fn eglSwapInterval(display: EGLDisplay, interval: EGLint) -> EGLBoolean;
    pub fn eglSurfaceAttrib(display: EGLDisplay, surface: EGLSurface, attribute: EGLint, value: EGLint) -> EGLBoolean;
    pub fn eglQuerySurface(display: EGLDisplay, surface: EGLSurface, attribute: EGLint, value: *mut EGLint) -> EGLBoolean;
    pub fn eglQueryString(display: EGLDisplay, name: EGLint) -> *const c_char;
    pub fn eglGetError() -> EGLint;
    pub fn eglGetProcAddress(name: *const c_char) -> *mut c_void;
    pub fn eglReleaseThread() -> EGLBoolean;
}

pub fn extensions(display: EGLDisplay) -> String {
    unsafe {
        let ptr = eglQueryString(display, EGL_EXTENSIONS);
        if ptr.is_null() { String::new() } else { CStr::from_ptr(ptr).to_string_lossy().into_owned() }
    }
}

pub fn error() -> EGLint {
    unsafe { eglGetError() }
}
