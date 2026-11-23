use lucky_ffmpeg_sys as sys;

use crate::error::{Error, Result, check};

pub struct CodecParameters {
    ptr: *mut sys::AVCodecParameters,
}

unsafe impl Send for CodecParameters {}
unsafe impl Sync for CodecParameters {}

impl CodecParameters {
    pub fn copy_from(source: *const sys::AVCodecParameters) -> Result<Self> {
        let ptr = unsafe { sys::avcodec_parameters_alloc() };
        if ptr.is_null() {
            return Err(Error::OutOfMemory("allocating codec parameters"));
        }
        let params = Self { ptr };
        check("copying codec parameters", unsafe { sys::avcodec_parameters_copy(ptr, source) })?;
        Ok(params)
    }

    pub fn as_ptr(&self) -> *const sys::AVCodecParameters {
        self.ptr
    }

    pub fn raw(&self) -> &sys::AVCodecParameters {
        unsafe { &*self.ptr }
    }

    pub fn extradata(&self) -> &[u8] {
        let raw = self.raw();
        if raw.extradata.is_null() || raw.extradata_size <= 0 {
            &[]
        } else {
            unsafe { std::slice::from_raw_parts(raw.extradata, raw.extradata_size as usize) }
        }
    }
}

impl Clone for CodecParameters {
    fn clone(&self) -> Self {
        Self::copy_from(self.ptr).expect("codec parameters copy")
    }
}

impl Drop for CodecParameters {
    fn drop(&mut self) {
        unsafe { sys::avcodec_parameters_free(&mut self.ptr) };
    }
}
