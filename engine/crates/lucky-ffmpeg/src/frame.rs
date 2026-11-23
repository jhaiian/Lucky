use lucky_ffmpeg_sys as sys;

use crate::error::{Error, Result};

pub struct Frame {
    ptr: *mut sys::AVFrame,
}

unsafe impl Send for Frame {}
unsafe impl Sync for Frame {}

impl Frame {
    pub fn new() -> Result<Self> {
        let ptr = unsafe { sys::av_frame_alloc() };
        if ptr.is_null() {
            return Err(Error::OutOfMemory("allocating a frame"));
        }
        Ok(Self { ptr })
    }

    pub fn as_ptr(&self) -> *const sys::AVFrame {
        self.ptr
    }

    pub fn as_mut_ptr(&mut self) -> *mut sys::AVFrame {
        self.ptr
    }

    pub fn raw(&self) -> &sys::AVFrame {
        unsafe { &*self.ptr }
    }

    pub fn width(&self) -> i32 {
        self.raw().width
    }

    pub fn height(&self) -> i32 {
        self.raw().height
    }

    pub fn format(&self) -> i32 {
        self.raw().format
    }

    pub fn pixel_format(&self) -> sys::AVPixelFormat {
        sys::AVPixelFormat(self.raw().format)
    }

    pub fn timestamp(&self) -> Option<i64> {
        let frame = self.raw();
        if frame.best_effort_timestamp != sys::AV_NOPTS_VALUE {
            Some(frame.best_effort_timestamp)
        } else if frame.pts != sys::AV_NOPTS_VALUE {
            Some(frame.pts)
        } else {
            None
        }
    }

    pub fn duration(&self) -> i64 {
        self.raw().duration
    }

    pub fn set_timing(&mut self, pts: i64, duration: i64) {
        unsafe {
            let raw = &mut *self.ptr;
            raw.pts = pts;
            raw.best_effort_timestamp = pts;
            raw.duration = duration;
        }
    }

    pub fn is_interlaced(&self) -> bool {
        (self.raw().flags as u32 & sys::AV_FRAME_FLAG_INTERLACED as u32) != 0
    }

    pub fn mark_interlaced(&mut self, top_field_first: bool) {
        unsafe {
            let raw = &mut *self.ptr;
            raw.flags |= sys::AV_FRAME_FLAG_INTERLACED as i32;
            if top_field_first {
                raw.flags |= sys::AV_FRAME_FLAG_TOP_FIELD_FIRST as i32;
            } else {
                raw.flags &= !(sys::AV_FRAME_FLAG_TOP_FIELD_FIRST as i32);
            }
        }
    }

    pub fn sample_aspect(&self) -> Option<(i32, i32)> {
        let aspect = self.raw().sample_aspect_ratio;
        (aspect.num > 0 && aspect.den > 0).then_some((aspect.num, aspect.den))
    }

    pub fn plane(&self, index: usize) -> (*const u8, i32) {
        let frame = self.raw();
        (frame.data[index], frame.linesize[index])
    }

    pub fn side_data(&self, kind: sys::AVFrameSideDataType) -> Option<&[u8]> {
        unsafe {
            let entry = sys::av_frame_get_side_data(self.ptr, kind);
            if entry.is_null() || (*entry).data.is_null() {
                return None;
            }
            Some(std::slice::from_raw_parts((*entry).data, (*entry).size))
        }
    }

    pub fn unref(&mut self) {
        unsafe { sys::av_frame_unref(self.ptr) };
    }

    pub fn take(&mut self) -> Result<Frame> {
        let out = Frame::new()?;
        unsafe { sys::av_frame_move_ref(out.ptr, self.ptr) };
        Ok(out)
    }

    pub fn allocate_video(width: i32, height: i32, format: sys::AVPixelFormat) -> Result<Frame> {
        let frame = Frame::new()?;
        unsafe {
            let raw = &mut *frame.ptr;
            raw.width = width;
            raw.height = height;
            raw.format = format.0;
            let code = sys::av_frame_get_buffer(frame.ptr, 0);
            if code < 0 {
                return Err(Error::ffmpeg("allocating frame buffers", code));
            }
        }
        Ok(frame)
    }
}

impl Drop for Frame {
    fn drop(&mut self) {
        unsafe { sys::av_frame_free(&mut self.ptr) };
    }
}
