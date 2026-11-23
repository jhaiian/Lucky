use lucky_ffmpeg_sys as sys;

use crate::error::{Error, Result};

pub struct Packet {
    ptr: *mut sys::AVPacket,
}

unsafe impl Send for Packet {}

impl Packet {
    pub fn new() -> Result<Self> {
        let ptr = unsafe { sys::av_packet_alloc() };
        if ptr.is_null() {
            return Err(Error::OutOfMemory("allocating a packet"));
        }
        Ok(Self { ptr })
    }

    pub fn as_ptr(&self) -> *const sys::AVPacket {
        self.ptr
    }

    pub fn as_mut_ptr(&mut self) -> *mut sys::AVPacket {
        self.ptr
    }

    pub fn stream_index(&self) -> i32 {
        unsafe { (*self.ptr).stream_index }
    }

    pub fn size(&self) -> usize {
        unsafe { (*self.ptr).size.max(0) as usize }
    }

    pub fn pts(&self) -> Option<i64> {
        let pts = unsafe { (*self.ptr).pts };
        (pts != sys::AV_NOPTS_VALUE).then_some(pts)
    }

    pub fn dts(&self) -> Option<i64> {
        let dts = unsafe { (*self.ptr).dts };
        (dts != sys::AV_NOPTS_VALUE).then_some(dts)
    }

    pub fn duration(&self) -> i64 {
        unsafe { (*self.ptr).duration }
    }

    pub fn is_key(&self) -> bool {
        unsafe { (*self.ptr).flags & sys::AV_PKT_FLAG_KEY as i32 != 0 }
    }

    pub fn data(&self) -> &[u8] {
        unsafe {
            let packet = &*self.ptr;
            if packet.data.is_null() || packet.size <= 0 {
                &[]
            } else {
                std::slice::from_raw_parts(packet.data, packet.size as usize)
            }
        }
    }

    pub fn side_data(&self, kind: sys::AVPacketSideDataType) -> Option<&[u8]> {
        unsafe {
            let packet = &*self.ptr;
            if packet.side_data.is_null() || packet.side_data_elems <= 0 {
                return None;
            }
            let entry = sys::av_packet_side_data_get(packet.side_data, packet.side_data_elems, kind);
            if entry.is_null() || (*entry).data.is_null() || (*entry).size == 0 {
                return None;
            }
            Some(std::slice::from_raw_parts((*entry).data, (*entry).size))
        }
    }

    pub fn unref(&mut self) {
        unsafe { sys::av_packet_unref(self.ptr) };
    }
}

impl Drop for Packet {
    fn drop(&mut self) {
        unsafe { sys::av_packet_free(&mut self.ptr) };
    }
}
