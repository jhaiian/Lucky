use std::ptr;

use lucky_ffmpeg_sys as sys;

use crate::error::{Error, Result};
use crate::frame::Frame;

pub struct Scaler {
    context: *mut sys::SwsContext,
}

unsafe impl Send for Scaler {}

impl Default for Scaler {
    fn default() -> Self {
        Self { context: ptr::null_mut() }
    }
}

impl Scaler {
    pub fn convert(&mut self, source: &Frame, format: sys::AVPixelFormat) -> Result<Frame> {
        let width = source.width();
        let height = source.height();
        unsafe {
            self.context = sys::sws_getCachedContext(
                self.context,
                width,
                height,
                source.pixel_format(),
                width,
                height,
                format,
                sys::SwsFlags::SWS_BILINEAR.0 as i32,
                ptr::null_mut(),
                ptr::null_mut(),
                ptr::null(),
            );
            if self.context.is_null() {
                return Err(Error::Other(format!("cannot convert pixel format {}", source.format())));
            }
            let mut output = Frame::allocate_video(width, height, format)?;
            let src = source.raw();
            let dst = output.raw();
            let code = sys::sws_scale(
                self.context,
                src.data.as_ptr() as *const *const u8,
                src.linesize.as_ptr(),
                0,
                height,
                dst.data.as_ptr(),
                dst.linesize.as_ptr(),
            );
            if code < 0 {
                return Err(Error::ffmpeg("converting a frame", code));
            }
            let out = &mut *output.as_mut_ptr();
            out.pts = src.pts;
            out.best_effort_timestamp = src.best_effort_timestamp;
            out.duration = src.duration;
            out.color_range = src.color_range;
            out.color_primaries = src.color_primaries;
            out.color_trc = src.color_trc;
            out.colorspace = src.colorspace;
            out.sample_aspect_ratio = src.sample_aspect_ratio;
            Ok(output)
        }
    }
}

impl Scaler {
    pub fn scale(&mut self, source: &Frame, format: sys::AVPixelFormat, width: i32, height: i32) -> Result<Frame> {
        if width <= 0 || height <= 0 || source.width() <= 0 || source.height() <= 0 {
            return Err(Error::Other("cannot scale to an empty size".into()));
        }
        unsafe {
            self.context = sys::sws_getCachedContext(
                self.context,
                source.width(),
                source.height(),
                source.pixel_format(),
                width,
                height,
                format,
                sys::SwsFlags::SWS_AREA.0 as i32,
                ptr::null_mut(),
                ptr::null_mut(),
                ptr::null(),
            );
            if self.context.is_null() {
                return Err(Error::Other(format!("cannot scale pixel format {}", source.format())));
            }
            let src = source.raw();
            let matrix = match src.colorspace {
                sys::AVColorSpace::AVCOL_SPC_BT709 => 1,
                sys::AVColorSpace::AVCOL_SPC_FCC => 4,
                sys::AVColorSpace::AVCOL_SPC_BT470BG | sys::AVColorSpace::AVCOL_SPC_SMPTE170M => 5,
                sys::AVColorSpace::AVCOL_SPC_SMPTE240M => 7,
                sys::AVColorSpace::AVCOL_SPC_BT2020_NCL | sys::AVColorSpace::AVCOL_SPC_BT2020_CL => 9,
                _ if src.height >= 720 => 1,
                _ => 5,
            };
            let coefficients = sys::sws_getCoefficients(matrix);
            let full_range = (src.color_range == sys::AVColorRange::AVCOL_RANGE_JPEG) as i32;
            sys::sws_setColorspaceDetails(self.context, coefficients, full_range, coefficients, 1, 0, 1 << 16, 1 << 16);
            let output = Frame::allocate_video(width, height, format)?;
            let dst = output.raw();
            let code = sys::sws_scale(
                self.context,
                src.data.as_ptr() as *const *const u8,
                src.linesize.as_ptr(),
                0,
                src.height,
                dst.data.as_ptr(),
                dst.linesize.as_ptr(),
            );
            if code < 0 {
                return Err(Error::ffmpeg("scaling a frame", code));
            }
            Ok(output)
        }
    }
}

impl Drop for Scaler {
    fn drop(&mut self) {
        if !self.context.is_null() {
            unsafe { sys::sws_freeContext(self.context) };
        }
    }
}
