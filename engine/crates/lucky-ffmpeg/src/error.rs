use std::ffi::CStr;
use std::os::raw::{c_char, c_int};

use lucky_ffmpeg_sys as sys;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("{context}: {message} ({code})")]
    Ffmpeg { context: &'static str, code: c_int, message: String },
    #[error("{0}")]
    Io(#[from] std::io::Error),
    #[error("out of memory while {0}")]
    OutOfMemory(&'static str),
    #[error("{0}")]
    Other(String),
}

pub type Result<T> = std::result::Result<T, Error>;

pub fn error_string(code: c_int) -> String {
    let mut buffer = [0 as c_char; 256];
    unsafe {
        if sys::av_strerror(code, buffer.as_mut_ptr(), buffer.len()) < 0 {
            return format!("error {code}");
        }
        CStr::from_ptr(buffer.as_ptr()).to_string_lossy().into_owned()
    }
}

impl Error {
    pub fn ffmpeg(context: &'static str, code: c_int) -> Self {
        Error::Ffmpeg { context, code, message: error_string(code) }
    }

    pub fn code(&self) -> Option<c_int> {
        match self {
            Error::Ffmpeg { code, .. } => Some(*code),
            _ => None,
        }
    }

    pub fn is_eof(&self) -> bool {
        self.code() == Some(sys::AVERROR_EOF)
    }

    pub fn is_again(&self) -> bool {
        self.code() == Some(sys::AVERROR_EAGAIN)
    }
}

pub fn check(context: &'static str, code: c_int) -> Result<c_int> {
    if code < 0 { Err(Error::ffmpeg(context, code)) } else { Ok(code) }
}
