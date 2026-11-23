use std::os::fd::OwnedFd;
use std::os::raw::{c_int, c_void};
use std::ptr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use lucky_ffmpeg_sys as sys;

use crate::error::{Error, Result, check};
use crate::io::{self, FdSource};
use crate::packet::Packet;
use crate::util::{dict_get, rescale_to_ms};

const IO_BUFFER_SIZE: usize = 256 * 1024;

pub struct Input {
    context: *mut sys::AVFormatContext,
    avio: *mut sys::AVIOContext,
    source: *mut FdSource,
    interrupt: *mut Arc<AtomicBool>,
}

unsafe impl Send for Input {}

unsafe extern "C" fn interrupt_callback(opaque: *mut c_void) -> c_int {
    if opaque.is_null() {
        return 0;
    }
    let flag = unsafe { &*(opaque as *const Arc<AtomicBool>) };
    flag.load(Ordering::Relaxed) as c_int
}

impl Input {
    pub fn open_fd(fd: OwnedFd) -> Result<Self> {
        Self::open_fd_with_interrupt(fd, Arc::new(AtomicBool::new(false)))
    }

    pub fn open_fd_with_interrupt(fd: OwnedFd, interrupt: Arc<AtomicBool>) -> Result<Self> {
        let source = FdSource::new(fd)?;
        let seekable = source.seekable();
        let mut input = Input {
            context: ptr::null_mut(),
            avio: ptr::null_mut(),
            source: Box::into_raw(Box::new(source)),
            interrupt: Box::into_raw(Box::new(interrupt)),
        };
        unsafe {
            let buffer = sys::av_malloc(IO_BUFFER_SIZE) as *mut u8;
            if buffer.is_null() {
                return Err(Error::OutOfMemory("allocating the I/O buffer"));
            }
            input.avio = sys::avio_alloc_context(
                buffer,
                IO_BUFFER_SIZE as c_int,
                0,
                input.source as *mut c_void,
                Some(io::read_packet),
                None,
                if seekable { Some(io::seek) } else { None },
            );
            if input.avio.is_null() {
                sys::av_free(buffer as *mut c_void);
                return Err(Error::OutOfMemory("creating the I/O context"));
            }
            (*input.avio).seekable = if seekable { sys::AVIO_SEEKABLE_NORMAL as c_int } else { 0 };

            let mut context = sys::avformat_alloc_context();
            if context.is_null() {
                return Err(Error::OutOfMemory("creating the demuxer"));
            }
            (*context).pb = input.avio;
            (*context).flags |= sys::AVFMT_FLAG_CUSTOM_IO as c_int;
            (*context).interrupt_callback = sys::AVIOInterruptCB {
                callback: Some(interrupt_callback),
                opaque: input.interrupt as *mut c_void,
            };
            let code = sys::avformat_open_input(&mut context, c"".as_ptr(), ptr::null(), ptr::null_mut());
            if code < 0 {
                return Err(Error::ffmpeg("opening the media", code));
            }
            input.context = context;
            check("reading stream information", sys::avformat_find_stream_info(context, ptr::null_mut()))?;
        }
        Ok(input)
    }

    pub fn as_ptr(&self) -> *mut sys::AVFormatContext {
        self.context
    }

    pub fn streams(&self) -> &[*mut sys::AVStream] {
        unsafe {
            let context = &*self.context;
            if context.streams.is_null() || context.nb_streams == 0 {
                &[]
            } else {
                std::slice::from_raw_parts(context.streams, context.nb_streams as usize)
            }
        }
    }

    pub fn file_size(&self) -> Option<i64> {
        unsafe { (*self.source).size() }
    }

    pub fn seekable(&self) -> bool {
        unsafe { (*self.source).seekable() }
    }

    pub fn set_read_budget(&mut self, bytes: Option<u64>) {
        unsafe { (*self.source).set_budget(bytes) }
    }

    pub fn set_abort_check(&mut self, check: Option<Box<dyn Fn() -> bool + Send>>) {
        unsafe { (*self.source).set_abort(check) }
    }

    pub fn best_stream(&self, kind: sys::AVMediaType) -> Option<usize> {
        let index = unsafe { sys::av_find_best_stream(self.context, kind, -1, -1, ptr::null_mut(), 0) };
        if index < 0 {
            return None;
        }
        let stream = *self.streams().get(index as usize)?;
        let attached = unsafe { (*stream).disposition } as u32 & sys::AV_DISPOSITION_ATTACHED_PIC != 0;
        if attached {
            return self.streams().iter().position(|s| unsafe {
                let s = &**s;
                !s.codecpar.is_null()
                    && (*s.codecpar).codec_type == kind
                    && (s.disposition as u32 & sys::AV_DISPOSITION_ATTACHED_PIC) == 0
            });
        }
        Some(index as usize)
    }

    pub fn stream(&self, index: usize) -> Option<*mut sys::AVStream> {
        self.streams().get(index).copied()
    }

    pub fn keep_only_streams(&self, wanted: &[usize]) {
        for (index, stream) in self.streams().iter().enumerate() {
            unsafe {
                (**stream).discard = if wanted.contains(&index) {
                    sys::AVDiscard::AVDISCARD_DEFAULT
                } else {
                    sys::AVDiscard::AVDISCARD_ALL
                };
            }
        }
    }

    pub fn read(&mut self, packet: &mut Packet) -> Result<()> {
        packet.unref();
        check("reading a packet", unsafe { sys::av_read_frame(self.context, packet.as_mut_ptr()) })?;
        Ok(())
    }

    pub fn seek(&mut self, target_us: i64) -> Result<()> {
        let start = unsafe { (*self.context).start_time };
        let base = if start == sys::AV_NOPTS_VALUE { 0 } else { start };
        let target = base.saturating_add(target_us.max(0));
        check("seeking", unsafe {
            sys::avformat_seek_file(self.context, -1, i64::MIN, target, target, 0)
        })?;
        Ok(())
    }

    pub fn start_time_us(&self) -> i64 {
        let start = unsafe { (*self.context).start_time };
        if start == sys::AV_NOPTS_VALUE { 0 } else { start }
    }

    pub fn duration_us(&self) -> Option<i64> {
        let duration = unsafe { (*self.context).duration };
        (duration != sys::AV_NOPTS_VALUE && duration > 0).then_some(duration)
    }

    pub fn interrupt_flag(&self) -> Arc<AtomicBool> {
        unsafe { (*self.interrupt).clone() }
    }

    pub fn font_attachments(&self) -> Vec<Attachment> {
        let mut fonts = Vec::new();
        for &stream in self.streams() {
            if stream.is_null() {
                continue;
            }
            let stream = unsafe { &*stream };
            if stream.codecpar.is_null() {
                continue;
            }
            let par = unsafe { &*stream.codecpar };
            if par.codec_type != sys::AVMediaType::AVMEDIA_TYPE_ATTACHMENT || par.extradata.is_null() || par.extradata_size <= 0 {
                continue;
            }
            let name = dict_get(stream.metadata, "filename").unwrap_or_else(|| format!("attachment-{}", stream.index));
            let mime = dict_get(stream.metadata, "mimetype").unwrap_or_default().to_ascii_lowercase();
            let lower = name.to_ascii_lowercase();
            let is_font = par.codec_id == sys::AVCodecID::AV_CODEC_ID_TTF
                || par.codec_id == sys::AVCodecID::AV_CODEC_ID_OTF
                || mime.contains("font")
                || mime.contains("truetype")
                || mime.contains("opentype")
                || lower.ends_with(".ttf")
                || lower.ends_with(".otf")
                || lower.ends_with(".ttc");
            if !is_font {
                continue;
            }
            let data = unsafe { std::slice::from_raw_parts(par.extradata, par.extradata_size as usize) }.to_vec();
            fonts.push(Attachment { name, data });
        }
        fonts
    }

    pub fn chapters(&self) -> Vec<Chapter> {
        let context = unsafe { &*self.context };
        if context.chapters.is_null() || context.nb_chapters == 0 {
            return Vec::new();
        }
        let list = unsafe { std::slice::from_raw_parts(context.chapters, context.nb_chapters as usize) };
        let mut chapters: Vec<Chapter> = list
            .iter()
            .filter(|c| !c.is_null())
            .filter_map(|c| {
                let chapter = unsafe { &**c };
                let start_ms = rescale_to_ms(chapter.start, chapter.time_base)?;
                let end_ms = rescale_to_ms(chapter.end, chapter.time_base).filter(|end| *end > start_ms);
                Some(Chapter { start_ms, end_ms, title: dict_get(chapter.metadata, "title") })
            })
            .collect();
        chapters.sort_by_key(|c| c.start_ms);
        chapters
    }
}

pub struct Attachment {
    pub name: String,
    pub data: Vec<u8>,
}

pub struct Chapter {
    pub start_ms: i64,
    pub end_ms: Option<i64>,
    pub title: Option<String>,
}

impl Drop for Input {
    fn drop(&mut self) {
        unsafe {
            if !self.context.is_null() {
                sys::avformat_close_input(&mut self.context);
            }
            if !self.avio.is_null() {
                sys::av_freep(&mut (*self.avio).buffer as *mut *mut u8 as *mut c_void);
                sys::avio_context_free(&mut self.avio);
            }
            if !self.source.is_null() {
                drop(Box::from_raw(self.source));
                self.source = ptr::null_mut();
            }
            if !self.interrupt.is_null() {
                drop(Box::from_raw(self.interrupt));
                self.interrupt = ptr::null_mut();
            }
        }
    }
}
