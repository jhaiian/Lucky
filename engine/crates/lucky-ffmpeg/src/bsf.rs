use std::ffi::CString;

use lucky_ffmpeg_sys as sys;

use crate::error::{Error, Result, check};
use crate::packet::Packet;
use crate::params::CodecParameters;

pub struct BitstreamFilter {
    context: *mut sys::AVBSFContext,
}

unsafe impl Send for BitstreamFilter {}

impl BitstreamFilter {
    pub fn new(name: &str, params: &CodecParameters, time_base: sys::AVRational) -> Result<Self> {
        let name = CString::new(name).map_err(|_| Error::Other("invalid filter name".into()))?;
        unsafe {
            let filter = sys::av_bsf_get_by_name(name.as_ptr());
            if filter.is_null() {
                return Err(Error::Other(format!("bitstream filter {name:?} is not available")));
            }
            let mut context = std::ptr::null_mut();
            check("allocating a bitstream filter", sys::av_bsf_alloc(filter, &mut context))?;
            let filter = Self { context };
            check("configuring a bitstream filter", sys::avcodec_parameters_copy((*context).par_in, params.as_ptr()))?;
            (*context).time_base_in = time_base;
            check("starting a bitstream filter", sys::av_bsf_init(context))?;
            Ok(filter)
        }
    }

    pub fn output_extradata(&self) -> &[u8] {
        unsafe {
            let par = (*self.context).par_out;
            if par.is_null() || (*par).extradata.is_null() || (*par).extradata_size <= 0 {
                &[]
            } else {
                std::slice::from_raw_parts((*par).extradata, (*par).extradata_size as usize)
            }
        }
    }

    pub fn send(&mut self, packet: &Packet) -> Result<()> {
        let mut copy = Packet::new()?;
        check("referencing a packet", unsafe { sys::av_packet_ref(copy.as_mut_ptr(), packet.as_ptr()) })?;
        check("filtering a packet", unsafe { sys::av_bsf_send_packet(self.context, copy.as_mut_ptr()) })?;
        Ok(())
    }

    pub fn receive(&mut self, packet: &mut Packet) -> Result<bool> {
        packet.unref();
        let code = unsafe { sys::av_bsf_receive_packet(self.context, packet.as_mut_ptr()) };
        if code == sys::AVERROR_EAGAIN || code == sys::AVERROR_EOF {
            return Ok(false);
        }
        check("receiving a filtered packet", code)?;
        Ok(true)
    }

    pub fn flush(&mut self) {
        unsafe { sys::av_bsf_flush(self.context) };
    }
}

impl Drop for BitstreamFilter {
    fn drop(&mut self) {
        unsafe { sys::av_bsf_free(&mut self.context) };
    }
}
