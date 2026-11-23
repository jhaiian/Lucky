use std::ffi::c_void;
use std::ptr::NonNull;

#[repr(C)]
pub struct ANativeWindow {
    _private: [u8; 0],
}

#[link(name = "android")]
unsafe extern "C" {
    fn ANativeWindow_acquire(window: *mut ANativeWindow);
    fn ANativeWindow_fromSurface(env: *mut c_void, surface: *mut c_void) -> *mut ANativeWindow;
    fn ANativeWindow_release(window: *mut ANativeWindow);
    fn ANativeWindow_getWidth(window: *mut ANativeWindow) -> i32;
    fn ANativeWindow_getHeight(window: *mut ANativeWindow) -> i32;
    fn ANativeWindow_setBuffersGeometry(window: *mut ANativeWindow, width: i32, height: i32, format: i32) -> i32;
}

pub struct NativeWindow {
    ptr: NonNull<ANativeWindow>,
}

unsafe impl Send for NativeWindow {}
unsafe impl Sync for NativeWindow {}

impl NativeWindow {
    pub unsafe fn from_surface(env: *mut c_void, surface: *mut c_void) -> Option<Self> {
        let ptr = unsafe { ANativeWindow_fromSurface(env, surface) };
        NonNull::new(ptr).map(|ptr| Self { ptr })
    }

    pub fn as_ptr(&self) -> *mut ANativeWindow {
        self.ptr.as_ptr()
    }

    pub fn width(&self) -> i32 {
        unsafe { ANativeWindow_getWidth(self.ptr.as_ptr()) }
    }

    pub fn height(&self) -> i32 {
        unsafe { ANativeWindow_getHeight(self.ptr.as_ptr()) }
    }

    pub fn set_buffers_format(&self, format: i32) {
        unsafe { ANativeWindow_setBuffersGeometry(self.ptr.as_ptr(), 0, 0, format) };
    }
}

impl Clone for NativeWindow {
    fn clone(&self) -> Self {
        unsafe { ANativeWindow_acquire(self.ptr.as_ptr()) };
        Self { ptr: self.ptr }
    }
}

type SetFrameRateFn = unsafe extern "C" fn(*mut ANativeWindow, f32, i8) -> i32;
type SetFrameRateStrategyFn = unsafe extern "C" fn(*mut ANativeWindow, f32, i8, i8) -> i32;

struct FrameRateFunctions {
    with_strategy: Option<SetFrameRateStrategyFn>,
    basic: Option<SetFrameRateFn>,
}

fn frame_rate_functions() -> &'static FrameRateFunctions {
    static FUNCTIONS: std::sync::OnceLock<FrameRateFunctions> = std::sync::OnceLock::new();
    FUNCTIONS.get_or_init(|| unsafe {
        let library = libc::dlopen(c"libandroid.so".as_ptr(), libc::RTLD_NOW | libc::RTLD_NOLOAD);
        if library.is_null() {
            return FrameRateFunctions { with_strategy: None, basic: None };
        }
        let strategy = libc::dlsym(library, c"ANativeWindow_setFrameRateWithChangeStrategy".as_ptr());
        let basic = libc::dlsym(library, c"ANativeWindow_setFrameRate".as_ptr());
        FrameRateFunctions {
            with_strategy: (!strategy.is_null()).then(|| std::mem::transmute::<*mut c_void, SetFrameRateStrategyFn>(strategy)),
            basic: (!basic.is_null()).then(|| std::mem::transmute::<*mut c_void, SetFrameRateFn>(basic)),
        }
    })
}

impl NativeWindow {
    pub fn set_frame_rate(&self, fps: f32) -> bool {
        const FIXED_SOURCE: i8 = 1;
        const ONLY_IF_SEAMLESS: i8 = 0;
        let functions = frame_rate_functions();
        let result = unsafe {
            if let Some(set) = functions.with_strategy {
                set(self.ptr.as_ptr(), fps, FIXED_SOURCE, ONLY_IF_SEAMLESS)
            } else if let Some(set) = functions.basic {
                set(self.ptr.as_ptr(), fps, FIXED_SOURCE)
            } else {
                return false;
            }
        };
        result == 0
    }
}

type SetDataSpaceFn = unsafe extern "C" fn(*mut ANativeWindow, i32) -> i32;

fn data_space_function() -> Option<SetDataSpaceFn> {
    static FUNCTION: std::sync::OnceLock<usize> = std::sync::OnceLock::new();
    let address = *FUNCTION.get_or_init(|| unsafe {
        let library = libc::dlopen(c"libandroid.so".as_ptr(), libc::RTLD_NOW | libc::RTLD_NOLOAD);
        if library.is_null() {
            return 0;
        }
        libc::dlsym(library, c"ANativeWindow_setBuffersDataSpace".as_ptr()) as usize
    });
    (address != 0).then(|| unsafe { std::mem::transmute::<usize, SetDataSpaceFn>(address) })
}

pub const DATASPACE_UNKNOWN: i32 = 0;
pub const DATASPACE_BT2020_PQ: i32 = 163_971_072;

impl NativeWindow {
    pub fn can_set_data_space() -> bool {
        data_space_function().is_some()
    }

    pub fn set_data_space(&self, data_space: i32) -> bool {
        match data_space_function() {
            Some(set) => unsafe { set(self.ptr.as_ptr(), data_space) == 0 },
            None => false,
        }
    }
}

impl Drop for NativeWindow {
    fn drop(&mut self) {
        unsafe { ANativeWindow_release(self.ptr.as_ptr()) };
    }
}
