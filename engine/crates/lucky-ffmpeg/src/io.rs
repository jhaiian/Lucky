use std::io;
use std::os::fd::{AsRawFd, OwnedFd};
use std::os::raw::{c_int, c_void};

use lucky_ffmpeg_sys as sys;

pub struct FdSource {
    fd: OwnedFd,
    position: i64,
    size: Option<i64>,
    seekable: bool,
    budget: Option<u64>,
    abort: Option<Box<dyn Fn() -> bool + Send>>,
}

fn last_errno() -> c_int {
    io::Error::last_os_error().raw_os_error().unwrap_or(libc::EIO)
}

impl FdSource {
    pub fn new(fd: OwnedFd) -> io::Result<Self> {
        let mut stat: libc::stat64 = unsafe { std::mem::zeroed() };
        if unsafe { libc::fstat64(fd.as_raw_fd(), &mut stat) } != 0 {
            return Err(io::Error::last_os_error());
        }
        let regular = (stat.st_mode as u32 & libc::S_IFMT as u32) == libc::S_IFREG as u32;
        let start = if regular { unsafe { libc::lseek64(fd.as_raw_fd(), 0, libc::SEEK_CUR) } } else { 0 };
        Ok(Self {
            fd,
            position: start.max(0),
            size: if regular { Some(stat.st_size as i64) } else { None },
            seekable: regular,
            budget: None,
            abort: None,
        })
    }

    pub fn set_budget(&mut self, budget: Option<u64>) {
        self.budget = budget;
    }

    pub fn set_abort(&mut self, abort: Option<Box<dyn Fn() -> bool + Send>>) {
        self.abort = abort;
    }

    pub fn size(&self) -> Option<i64> {
        self.size
    }

    pub fn seekable(&self) -> bool {
        self.seekable
    }

    fn read_into(&mut self, buffer: &mut [u8]) -> c_int {
        if self.abort.as_ref().is_some_and(|abort| abort()) {
            return sys::AVERROR_EOF;
        }
        let mut length = buffer.len();
        if let Some(left) = self.budget {
            if left == 0 {
                return sys::AVERROR_EOF;
            }
            length = length.min(usize::try_from(left).unwrap_or(usize::MAX));
        }
        loop {
            let result = unsafe {
                if self.seekable {
                    libc::pread64(
                        self.fd.as_raw_fd(),
                        buffer.as_mut_ptr() as *mut c_void,
                        length,
                        self.position as libc::off64_t,
                    )
                } else {
                    libc::read(self.fd.as_raw_fd(), buffer.as_mut_ptr() as *mut c_void, length)
                }
            };
            if result < 0 {
                let errno = last_errno();
                if errno == libc::EINTR {
                    continue;
                }
                return sys::AVERROR(errno);
            }
            if result == 0 {
                return sys::AVERROR_EOF;
            }
            self.position += result as i64;
            if let Some(left) = self.budget.as_mut() {
                *left = left.saturating_sub(result as u64);
            }
            return result as c_int;
        }
    }

    fn seek_to(&mut self, offset: i64, whence: c_int) -> i64 {
        if whence & sys::AVSEEK_SIZE as c_int != 0 {
            return self.size.unwrap_or(sys::AVERROR(libc::ENOSYS) as i64);
        }
        if !self.seekable {
            return sys::AVERROR(libc::ESPIPE) as i64;
        }
        let target = match whence & !(sys::AVSEEK_FORCE as c_int) {
            libc::SEEK_SET => offset,
            libc::SEEK_CUR => self.position + offset,
            libc::SEEK_END => match self.size {
                Some(size) => size + offset,
                None => return sys::AVERROR(libc::ENOSYS) as i64,
            },
            _ => return sys::AVERROR(libc::EINVAL) as i64,
        };
        if target < 0 {
            return sys::AVERROR(libc::EINVAL) as i64;
        }
        self.position = target;
        target
    }
}

pub(crate) unsafe extern "C" fn read_packet(opaque: *mut c_void, buffer: *mut u8, size: c_int) -> c_int {
    if opaque.is_null() || buffer.is_null() || size <= 0 {
        return sys::AVERROR(libc::EINVAL);
    }
    let source = unsafe { &mut *(opaque as *mut FdSource) };
    let slice = unsafe { std::slice::from_raw_parts_mut(buffer, size as usize) };
    source.read_into(slice)
}

pub(crate) unsafe extern "C" fn seek(opaque: *mut c_void, offset: i64, whence: c_int) -> i64 {
    if opaque.is_null() {
        return sys::AVERROR(libc::EINVAL) as i64;
    }
    let source = unsafe { &mut *(opaque as *mut FdSource) };
    source.seek_to(offset, whence)
}
