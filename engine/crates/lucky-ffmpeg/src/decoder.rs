use std::ptr;

use lucky_ffmpeg_sys as sys;

use crate::error::{Error, Result};
use crate::frame::Frame;
use crate::packet::Packet;
use crate::probe::preferred_decoder;
use crate::util::c_str;

pub enum DecodeStatus {
    Frame(Frame),
    NeedsInput,
    Finished,
}

pub struct Decoder {
    context: *mut sys::AVCodecContext,
    scratch: Frame,
    name: String,
    time_base: sys::AVRational,
}

unsafe impl Send for Decoder {}

impl Decoder {
    pub fn open(stream: *const sys::AVStream, threads: i32) -> Result<Self> {
        unsafe {
            let stream = &*stream;
            Self::open_params(stream.codecpar, stream.time_base, threads)
        }
    }

    pub fn open_params(par: *const sys::AVCodecParameters, time_base: sys::AVRational, threads: i32) -> Result<Self> {
        Self::open_configured(par, time_base, threads, true)
    }

    pub fn open_still(stream: *const sys::AVStream, threads: i32) -> Result<Self> {
        unsafe {
            let stream = &*stream;
            Self::open_configured(stream.codecpar, stream.time_base, threads, false)
        }
    }

    fn open_configured(
        par: *const sys::AVCodecParameters,
        time_base: sys::AVRational,
        threads: i32,
        frame_threads: bool,
    ) -> Result<Self> {
        unsafe {
            if par.is_null() {
                return Err(Error::Other("stream has no codec parameters".into()));
            }
            let codec = preferred_decoder((*par).codec_id);
            if codec.is_null() {
                return Err(Error::ffmpeg("finding a decoder", sys::AVERROR_DECODER_NOT_FOUND));
            }
            let context = sys::avcodec_alloc_context3(codec);
            if context.is_null() {
                return Err(Error::OutOfMemory("creating the decoder"));
            }
            let decoder = Decoder {
                context,
                scratch: Frame::new()?,
                name: c_str((*codec).name).unwrap_or_default(),
                time_base,
            };
            let code = sys::avcodec_parameters_to_context(context, par);
            if code < 0 {
                return Err(Error::ffmpeg("configuring the decoder", code));
            }
            (*context).pkt_timebase = time_base;
            (*context).thread_count = threads.max(0);
            if frame_threads {
                (*context).thread_type = (sys::FF_THREAD_FRAME | sys::FF_THREAD_SLICE) as i32;
            } else {
                (*context).thread_type = sys::FF_THREAD_SLICE as i32;
                (*context).flags |= sys::AV_CODEC_FLAG_LOW_DELAY as i32;
            }
            let code = sys::avcodec_open2(context, codec, ptr::null_mut());
            if code < 0 {
                return Err(Error::ffmpeg("opening the decoder", code));
            }
            Ok(decoder)
        }
    }

    pub fn name(&self) -> &str {
        &self.name
    }

    pub fn time_base(&self) -> sys::AVRational {
        self.time_base
    }

    pub fn as_ptr(&self) -> *mut sys::AVCodecContext {
        self.context
    }

    pub fn send(&mut self, packet: Option<&Packet>) -> Result<bool> {
        let code = unsafe {
            sys::avcodec_send_packet(self.context, packet.map(|p| p.as_ptr()).unwrap_or(ptr::null()))
        };
        if code == sys::AVERROR_EAGAIN {
            return Ok(false);
        }
        if code == sys::AVERROR_EOF {
            return Ok(true);
        }
        if code < 0 {
            return Err(Error::ffmpeg("decoding a packet", code));
        }
        Ok(true)
    }

    pub fn receive(&mut self) -> Result<DecodeStatus> {
        let code = unsafe { sys::avcodec_receive_frame(self.context, self.scratch.as_mut_ptr()) };
        if code == sys::AVERROR_EAGAIN {
            return Ok(DecodeStatus::NeedsInput);
        }
        if code == sys::AVERROR_EOF {
            return Ok(DecodeStatus::Finished);
        }
        if code < 0 {
            return Err(Error::ffmpeg("receiving a frame", code));
        }
        Ok(DecodeStatus::Frame(self.scratch.take()?))
    }

    pub fn flush(&mut self) {
        unsafe { sys::avcodec_flush_buffers(self.context) };
    }

    pub fn skip_to_keyframes(&mut self) {
        unsafe {
            (*self.context).skip_frame = sys::AVDiscard::AVDISCARD_NONKEY;
            (*self.context).skip_loop_filter = sys::AVDiscard::AVDISCARD_ALL;
        }
    }
}

impl Drop for Decoder {
    fn drop(&mut self) {
        unsafe { sys::avcodec_free_context(&mut self.context) };
    }
}
