#![allow(non_upper_case_globals, non_camel_case_types, non_snake_case, dead_code, improper_ctypes, clippy::all)]

use std::os::raw::c_int;

include!(concat!(env!("OUT_DIR"), "/bindings.rs"));

pub const AV_NOPTS_VALUE: i64 = i64::MIN;
pub const AV_TIME_BASE_Q: AVRational = AVRational { num: 1, den: AV_TIME_BASE as c_int };

const fn fferrtag(a: u8, b: u8, c: u8, d: u8) -> c_int {
    -((a as c_int) | ((b as c_int) << 8) | ((c as c_int) << 16) | ((d as c_int) << 24))
}

pub const AVERROR_EOF: c_int = fferrtag(b'E', b'O', b'F', b' ');
pub const AVERROR_INVALIDDATA: c_int = fferrtag(b'I', b'N', b'D', b'A');
pub const AVERROR_DECODER_NOT_FOUND: c_int = fferrtag(0xF8, b'D', b'E', b'C');
pub const AVERROR_DEMUXER_NOT_FOUND: c_int = fferrtag(0xF8, b'D', b'E', b'M');
pub const AVERROR_STREAM_NOT_FOUND: c_int = fferrtag(0xF8, b'S', b'T', b'R');
pub const AVERROR_EXIT: c_int = fferrtag(b'E', b'X', b'I', b'T');
pub const AVERROR_EXTERNAL: c_int = fferrtag(b'E', b'X', b'T', b' ');

#[inline]
pub const fn AVERROR(errno: c_int) -> c_int {
    -errno
}

pub const AVERROR_EAGAIN: c_int = AVERROR(libc::EAGAIN);
pub const AVERROR_ENOMEM: c_int = AVERROR(libc::ENOMEM);
pub const AVERROR_EINVAL: c_int = AVERROR(libc::EINVAL);

#[inline]
pub fn av_q2d(q: AVRational) -> f64 {
    q.num as f64 / q.den as f64
}

#[inline]
pub const fn av_make_q(num: c_int, den: c_int) -> AVRational {
    AVRational { num, den }
}
