use std::collections::VecDeque;
use std::ffi::{CString, c_void};
use std::ptr;
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use lucky_ffmpeg::{BitstreamFilter, Packet, sys};
use lucky_player::{
    BackendOutput, FrameData, HardwareDecoderFactory, HardwareFrame, VideoBackend, VideoStreamInfo, hdr10_plus_nits, hevc_hdr10_plus_nits,
};

use crate::ffi::*;

const MAX_IMAGES: i32 = 10;
const MAX_QUEUED_INPUTS: usize = 6;

struct Signal {
    available: Mutex<u32>,
    condvar: Condvar,
}

unsafe extern "C" fn on_image_available(context: *mut c_void, _reader: *mut AImageReader) {
    if context.is_null() {
        return;
    }
    let signal = unsafe { &*(context as *const Signal) };
    let mut available = signal.available.lock().unwrap_or_else(|e| e.into_inner());
    *available += 1;
    signal.condvar.notify_all();
}

struct Reader {
    reader: *mut AImageReader,
    window: *mut ANativeWindow,
    signal: Box<Signal>,
    _listener: Box<AImageReader_ImageListener>,
}

unsafe impl Send for Reader {}
unsafe impl Sync for Reader {}

impl Reader {
    fn new(width: i32, height: i32) -> Result<Self, String> {
        unsafe {
            let mut reader = ptr::null_mut();
            let status = AImageReader_newWithUsage(
                width.max(16),
                height.max(16),
                AIMAGE_FORMAT_PRIVATE,
                AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
                MAX_IMAGES,
                &mut reader,
            );
            if status != AMEDIA_OK || reader.is_null() {
                return Err(format!("AImageReader_newWithUsage failed: {status}"));
            }
            let signal = Box::new(Signal { available: Mutex::new(0), condvar: Condvar::new() });
            let mut listener = Box::new(AImageReader_ImageListener {
                context: &*signal as *const Signal as *mut c_void,
                onImageAvailable: Some(on_image_available),
            });
            AImageReader_setImageListener(reader, &mut *listener);
            let mut window = ptr::null_mut();
            let status = AImageReader_getWindow(reader, &mut window);
            if status != AMEDIA_OK || window.is_null() {
                AImageReader_delete(reader);
                return Err(format!("AImageReader_getWindow failed: {status}"));
            }
            Ok(Self { reader, window, signal, _listener: listener })
        }
    }

    fn try_acquire(&self) -> Option<*mut AImage> {
        let mut image = ptr::null_mut();
        let status = unsafe { AImageReader_acquireNextImage(self.reader, &mut image) };
        if status == AMEDIA_OK && !image.is_null() { Some(image) } else { None }
    }

    fn wait_for_image(&self, timeout: Duration) -> Option<*mut AImage> {
        let deadline = Instant::now() + timeout;
        loop {
            if let Some(image) = self.try_acquire() {
                return Some(image);
            }
            let now = Instant::now();
            if now >= deadline {
                return None;
            }
            let guard = self.signal.available.lock().unwrap_or_else(|e| e.into_inner());
            let (mut guard, _) = self
                .signal
                .condvar
                .wait_timeout(guard, (deadline - now).min(Duration::from_millis(4)))
                .unwrap_or_else(|e| e.into_inner());
            *guard = 0;
        }
    }

    fn discard_available(&self) {
        while let Some(image) = self.try_acquire() {
            unsafe { AImage_delete(image) };
        }
    }
}

impl Drop for Reader {
    fn drop(&mut self) {
        unsafe {
            let mut empty = AImageReader_ImageListener { context: ptr::null_mut(), onImageAvailable: None };
            AImageReader_setImageListener(self.reader, &mut empty);
            AImageReader_delete(self.reader);
        }
    }
}

struct CodecImage {
    image: *mut AImage,
    buffer: *mut AHardwareBuffer,
    width: i32,
    height: i32,
    buffer_size: (i32, i32),
    crop: [i32; 4],
    scene_nits: f32,
    _reader: Arc<Reader>,
}

unsafe impl Send for CodecImage {}
unsafe impl Sync for CodecImage {}

impl CodecImage {
    fn new(image: *mut AImage, reader: Arc<Reader>) -> Option<Self> {
        unsafe {
            let mut buffer = ptr::null_mut();
            if AImage_getHardwareBuffer(image, &mut buffer) != AMEDIA_OK || buffer.is_null() {
                AImage_delete(image);
                return None;
            }
            let mut desc = AHardwareBuffer_Desc::default();
            AHardwareBuffer_describe(buffer, &mut desc);
            let (mut width, mut height) = (0, 0);
            AImage_getWidth(image, &mut width);
            AImage_getHeight(image, &mut height);
            let mut rect = AImageCropRect::default();
            let crop = if AImage_getCropRect(image, &mut rect) == AMEDIA_OK && rect.right > rect.left && rect.bottom > rect.top {
                [rect.left, rect.top, rect.right, rect.bottom]
            } else {
                [0, 0, width, height]
            };
            Some(Self {
                image,
                buffer,
                width: crop[2] - crop[0],
                height: crop[3] - crop[1],
                buffer_size: (desc.width as i32, desc.height as i32),
                crop,
                scene_nits: 0.0,
                _reader: reader,
            })
        }
    }
}

impl HardwareFrame for CodecImage {
    fn width(&self) -> i32 {
        self.width
    }

    fn height(&self) -> i32 {
        self.height
    }

    fn hardware_buffer(&self) -> *mut c_void {
        self.buffer as *mut c_void
    }

    fn buffer_size(&self) -> (i32, i32) {
        self.buffer_size
    }

    fn crop(&self) -> [i32; 4] {
        self.crop
    }

    fn scene_nits(&self) -> f32 {
        self.scene_nits
    }
}

impl Drop for CodecImage {
    fn drop(&mut self) {
        unsafe { AImage_delete(self.image) };
    }
}

struct Input {
    data: Vec<u8>,
    pts_us: i64,
}

pub struct MediaCodecBackend {
    codec: *mut AMediaCodec,
    reader: Arc<Reader>,
    filter: Option<BitstreamFilter>,
    filtered: Packet,
    inputs: VecDeque<Input>,
    time_base: sys::AVRational,
    name: String,
    end_requested: bool,
    end_queued: bool,
    end_received: bool,
    untrusted_timestamps: bool,
    hevc: bool,
    scenes: VecDeque<(i64, f32)>,
    scene_nits: f32,
}

const SCENE_ENTRIES: usize = 96;

unsafe impl Send for MediaCodecBackend {}

fn start_code_split(data: &[u8]) -> Vec<&[u8]> {
    let mut units = Vec::new();
    let mut starts = Vec::new();
    let mut i = 0;
    while i + 3 <= data.len() {
        if data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 {
            starts.push(i + 3);
            i += 3;
        } else {
            i += 1;
        }
    }
    for (index, start) in starts.iter().enumerate() {
        let mut end = starts.get(index + 1).map(|next| next - 3).unwrap_or(data.len());
        while end > *start && data[end - 1] == 0 {
            end -= 1;
        }
        if end > *start {
            units.push(&data[*start..end]);
        }
    }
    units
}

fn with_start_code(units: &[&[u8]]) -> Vec<u8> {
    let mut out = Vec::new();
    for unit in units {
        out.extend_from_slice(&[0, 0, 0, 1]);
        out.extend_from_slice(unit);
    }
    out
}

fn is_software_codec(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    lower.starts_with("omx.google.") || lower.starts_with("c2.android.") || lower.starts_with("omx.ffmpeg") || lower.contains(".sw.")
}

struct Plan {
    mime: &'static str,
    filter: Option<&'static str>,
}

fn plan(stream: &VideoStreamInfo) -> Result<Plan, String> {
    let par = stream.params.raw();
    let format = sys::AVPixelFormat(par.format);
    let profile = par.profile;
    let extradata = stream.params.extradata();
    let eight_bit_420 = format == sys::AVPixelFormat::AV_PIX_FMT_YUV420P
        || format == sys::AVPixelFormat::AV_PIX_FMT_YUVJ420P
        || format == sys::AVPixelFormat::AV_PIX_FMT_NV12;
    let ten_bit_420 = format == sys::AVPixelFormat::AV_PIX_FMT_YUV420P10LE || format == sys::AVPixelFormat::AV_PIX_FMT_P010LE;
    match par.codec_id {
        sys::AVCodecID::AV_CODEC_ID_H264 => {
            if !(eight_bit_420 || par.format < 0) || matches!(profile, 110 | 122 | 244 | 44) {
                return Err("H.264 profile needs software decoding".into());
            }
            let filter = (extradata.first() == Some(&1)).then_some("h264_mp4toannexb");
            Ok(Plan { mime: "video/avc", filter })
        }
        sys::AVCodecID::AV_CODEC_ID_HEVC => {
            if !(eight_bit_420 || ten_bit_420 || par.format < 0) || profile > 2 && profile != 3 {
                return Err("HEVC profile needs software decoding".into());
            }
            let filter = (extradata.len() > 22 && extradata[0] == 1).then_some("hevc_mp4toannexb");
            Ok(Plan { mime: "video/hevc", filter })
        }
        sys::AVCodecID::AV_CODEC_ID_VP9 => {
            if profile == 1 || profile == 3 {
                return Err("VP9 4:4:4 needs software decoding".into());
            }
            Ok(Plan { mime: "video/x-vnd.on2.vp9", filter: None })
        }
        sys::AVCodecID::AV_CODEC_ID_VP8 => Ok(Plan { mime: "video/x-vnd.on2.vp8", filter: None }),
        sys::AVCodecID::AV_CODEC_ID_AV1 => {
            if api_level() < 29 || profile > 0 {
                return Err("AV1 hardware decoding is not available".into());
            }
            Ok(Plan { mime: "video/av01", filter: None })
        }
        sys::AVCodecID::AV_CODEC_ID_MPEG2VIDEO => Ok(Plan { mime: "video/mpeg2", filter: None }),
        sys::AVCodecID::AV_CODEC_ID_MPEG4 => Ok(Plan { mime: "video/mp4v-es", filter: None }),
        _ => Err("codec has no hardware path".into()),
    }
}

impl MediaCodecBackend {
    pub fn open(stream: &VideoStreamInfo) -> Result<Self, String> {
        let plan = plan(stream)?;
        let par = stream.params.raw();
        if par.width <= 0 || par.height <= 0 {
            return Err("unknown video size".into());
        }
        let filter = match plan.filter {
            Some(name) => Some(BitstreamFilter::new(name, &stream.params, stream.time_base).map_err(|e| e.to_string())?),
            None => None,
        };
        let annexb: Vec<u8> = match &filter {
            Some(filter) => filter.output_extradata().to_vec(),
            None => stream.params.extradata().to_vec(),
        };
        let reader = Arc::new(Reader::new(par.width, par.height)?);
        let mime = CString::new(plan.mime).expect("mime");
        unsafe {
            let codec = AMediaCodec_createDecoderByType(mime.as_ptr());
            if codec.is_null() {
                return Err(format!("no decoder for {}", plan.mime));
            }
            let name = codec_name(codec).unwrap_or_else(|| plan.mime.to_string());
            if is_software_codec(&name) {
                AMediaCodec_delete(codec);
                return Err(format!("{name} is a software decoder"));
            }
            let format = AMediaFormat_new();
            AMediaFormat_setString(format, c"mime".as_ptr(), mime.as_ptr());
            AMediaFormat_setInt32(format, c"width".as_ptr(), par.width);
            AMediaFormat_setInt32(format, c"height".as_ptr(), par.height);
            let max_input = (par.width as i64 * par.height as i64 * 3 / 2).clamp(1 << 20, 16 << 20) as i32;
            AMediaFormat_setInt32(format, c"max-input-size".as_ptr(), max_input);
            if let Some(fps) = stream.frame_rate {
                AMediaFormat_setInt32(format, c"frame-rate".as_ptr(), fps.round().max(1.0) as i32);
            }
            if !annexb.is_empty() {
                match par.codec_id {
                    sys::AVCodecID::AV_CODEC_ID_H264 => {
                        let units = start_code_split(&annexb);
                        let sps: Vec<&[u8]> = units.iter().copied().filter(|u| !u.is_empty() && u[0] & 0x1f == 7).collect();
                        let pps: Vec<&[u8]> = units.iter().copied().filter(|u| !u.is_empty() && u[0] & 0x1f == 8).collect();
                        if !sps.is_empty() {
                            let data = with_start_code(&sps);
                            AMediaFormat_setBuffer(format, c"csd-0".as_ptr(), data.as_ptr() as *const c_void, data.len());
                        }
                        if !pps.is_empty() {
                            let data = with_start_code(&pps);
                            AMediaFormat_setBuffer(format, c"csd-1".as_ptr(), data.as_ptr() as *const c_void, data.len());
                        }
                    }
                    sys::AVCodecID::AV_CODEC_ID_HEVC => {
                        let units = start_code_split(&annexb);
                        if !units.is_empty() {
                            let data = with_start_code(&units);
                            AMediaFormat_setBuffer(format, c"csd-0".as_ptr(), data.as_ptr() as *const c_void, data.len());
                        }
                    }
                    sys::AVCodecID::AV_CODEC_ID_MPEG4 | sys::AVCodecID::AV_CODEC_ID_MPEG2VIDEO => {
                        AMediaFormat_setBuffer(format, c"csd-0".as_ptr(), annexb.as_ptr() as *const c_void, annexb.len());
                    }
                    _ => {}
                }
            }
            let status = AMediaCodec_configure(codec, format, reader.window, ptr::null_mut(), 0);
            AMediaFormat_delete(format);
            if status != AMEDIA_OK {
                AMediaCodec_delete(codec);
                return Err(format!("{name} configure failed: {status}"));
            }
            let status = AMediaCodec_start(codec);
            if status != AMEDIA_OK {
                AMediaCodec_delete(codec);
                return Err(format!("{name} start failed: {status}"));
            }
            log::info!("hardware decoder {name} started for {}", plan.mime);
            Ok(Self {
                codec,
                reader,
                filter,
                filtered: Packet::new().map_err(|e| e.to_string())?,
                inputs: VecDeque::new(),
                time_base: stream.time_base,
                name,
                end_requested: false,
                end_queued: false,
                end_received: false,
                untrusted_timestamps: false,
                hevc: par.codec_id == sys::AVCodecID::AV_CODEC_ID_HEVC,
                scenes: VecDeque::new(),
                scene_nits: 0.0,
            })
        }
    }

    fn rescale(&self, value: i64) -> i64 {
        let tb = self.time_base;
        if tb.den == 0 {
            return 0;
        }
        (value as i128 * tb.num as i128 * 1_000_000 / tb.den as i128) as i64
    }

    fn remember_scene(&mut self, pts_us: i64, nits: Option<f32>) {
        if let Some(nits) = nits {
            if self.scenes.len() >= SCENE_ENTRIES {
                self.scenes.pop_front();
            }
            self.scenes.push_back((pts_us, nits));
        }
    }

    fn scene_for(&mut self, pts_us: i64) -> f32 {
        if let Some(index) = self.scenes.iter().position(|(pts, _)| *pts == pts_us) {
            if let Some((_, nits)) = self.scenes.remove(index) {
                self.scene_nits = nits;
            }
        }
        self.scene_nits
    }

    fn enqueue(&mut self, packet: &Packet) -> Result<(), String> {
        let pts = packet.pts().or(packet.dts()).map(|ts| self.rescale(ts)).unwrap_or(0);
        let attached = hdr10_plus_nits(packet.side_data(sys::AVPacketSideDataType::AV_PKT_DATA_DYNAMIC_HDR10_PLUS));
        let queued = self.inputs.len();
        match self.filter.as_mut() {
            Some(filter) => {
                filter.send(packet).map_err(|e| e.to_string())?;
                while filter.receive(&mut self.filtered).map_err(|e| e.to_string())? {
                    let pts = self.filtered.pts().or(self.filtered.dts()).map(|ts| {
                        let tb = self.time_base;
                        if tb.den == 0 { 0 } else { (ts as i128 * tb.num as i128 * 1_000_000 / tb.den as i128) as i64 }
                    });
                    self.inputs.push_back(Input { data: self.filtered.data().to_vec(), pts_us: pts.unwrap_or(0) });
                }
            }
            None => self.inputs.push_back(Input { data: packet.data().to_vec(), pts_us: pts }),
        }
        if attached.is_some() || self.hevc {
            let added: Vec<(i64, Option<f32>)> = self
                .inputs
                .iter()
                .skip(queued)
                .map(|input| (input.pts_us, attached.or_else(|| if self.hevc { hevc_hdr10_plus_nits(&input.data) } else { None })))
                .collect();
            for (pts_us, nits) in added {
                self.remember_scene(pts_us, nits);
            }
        }
        Ok(())
    }

    fn feed(&mut self) -> Result<(), String> {
        unsafe {
            while let Some(input) = self.inputs.front() {
                let index = AMediaCodec_dequeueInputBuffer(self.codec, 0);
                if index < 0 {
                    return Ok(());
                }
                let mut capacity = 0usize;
                let buffer = AMediaCodec_getInputBuffer(self.codec, index as usize, &mut capacity);
                if buffer.is_null() || capacity < input.data.len() {
                    let needed = input.data.len();
                    let pts = input.pts_us;
                    AMediaCodec_queueInputBuffer(self.codec, index as usize, 0, 0, pts as u64, 0);
                    self.inputs.pop_front();
                    return Err(format!("input buffer too small ({capacity} < {needed})"));
                }
                ptr::copy_nonoverlapping(input.data.as_ptr(), buffer, input.data.len());
                let status = AMediaCodec_queueInputBuffer(self.codec, index as usize, 0, input.data.len(), input.pts_us as u64, 0);
                self.inputs.pop_front();
                if status != AMEDIA_OK {
                    return Err(format!("queueInputBuffer failed: {status}"));
                }
            }
            if self.end_requested && !self.end_queued {
                let index = AMediaCodec_dequeueInputBuffer(self.codec, 0);
                if index >= 0 {
                    AMediaCodec_queueInputBuffer(self.codec, index as usize, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    self.end_queued = true;
                }
            }
        }
        Ok(())
    }

    fn take_image(&mut self, expected_us: i64) -> Option<CodecImage> {
        let deadline = Instant::now() + Duration::from_millis(250);
        let mut mismatches = 0;
        let mut stale = 0;
        while Instant::now() < deadline {
            let image = self.reader.wait_for_image(deadline.saturating_duration_since(Instant::now()))?;
            let mut timestamp_ns = 0i64;
            unsafe { AImage_getTimestamp(image, &mut timestamp_ns) };
            let offset_us = timestamp_ns / 1000 - expected_us;
            let matches = self.untrusted_timestamps || timestamp_ns == 0 || offset_us.abs() <= 2_000;
            if !matches && offset_us < 0 && stale < 12 {
                stale += 1;
                unsafe { AImage_delete(image) };
                continue;
            }
            if !matches {
                mismatches += 1;
                if mismatches >= 3 {
                    log::warn!("decoder image timestamps look unreliable, matching by order");
                    self.untrusted_timestamps = true;
                } else {
                    unsafe { AImage_delete(image) };
                    continue;
                }
            }
            return CodecImage::new(image, self.reader.clone());
        }
        None
    }
}

impl VideoBackend for MediaCodecBackend {
    fn name(&self) -> String {
        self.name.clone()
    }

    fn hardware(&self) -> bool {
        true
    }

    fn send(&mut self, packet: Option<&Packet>) -> Result<bool, String> {
        self.feed()?;
        if self.inputs.len() >= MAX_QUEUED_INPUTS {
            return Ok(false);
        }
        match packet {
            Some(packet) => self.enqueue(packet)?,
            None => self.end_requested = true,
        }
        self.feed()?;
        Ok(true)
    }

    fn receive(&mut self, wait: bool) -> Result<BackendOutput, String> {
        self.feed()?;
        if self.end_received {
            return Ok(BackendOutput::Finished);
        }
        let mut info = AMediaCodecBufferInfo::default();
        let timeout = if wait { 10_000 } else { 0 };
        let index = unsafe { AMediaCodec_dequeueOutputBuffer(self.codec, &mut info, timeout) };
        if index >= 0 {
            let end = info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM != 0;
            let render = info.size > 0 || !end;
            unsafe { AMediaCodec_releaseOutputBuffer(self.codec, index as usize, render) };
            if end {
                self.end_received = true;
            }
            if !render {
                return Ok(if end { BackendOutput::Finished } else { BackendOutput::NeedsInput });
            }
            let expected = info.presentationTimeUs;
            return match self.take_image(expected) {
                Some(mut image) => {
                    image.scene_nits = self.scene_for(expected);
                    Ok(BackendOutput::Frame {
                        data: FrameData::Hardware(Box::new(image)),
                        pts_us: Some(expected),
                        duration_us: None,
                        sample_aspect: None,
                    })
                }
                None => {
                    log::warn!("hardware frame at {expected} us did not arrive");
                    Ok(BackendOutput::NeedsInput)
                }
            };
        }
        match index {
            AMEDIACODEC_INFO_TRY_AGAIN_LATER | AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED | AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED => {
                Ok(BackendOutput::NeedsInput)
            }
            code => Err(format!("dequeueOutputBuffer failed: {code}")),
        }
    }

    fn flush(&mut self) {
        unsafe { AMediaCodec_flush(self.codec) };
        if let Some(filter) = self.filter.as_mut() {
            filter.flush();
        }
        self.inputs.clear();
        self.end_requested = false;
        self.end_queued = false;
        self.end_received = false;
        self.untrusted_timestamps = false;
        self.scenes.clear();
        self.scene_nits = 0.0;
        self.reader.discard_available();
    }
}

impl Drop for MediaCodecBackend {
    fn drop(&mut self) {
        unsafe {
            AMediaCodec_stop(self.codec);
            AMediaCodec_delete(self.codec);
        }
    }
}

pub struct MediaCodecFactory;

impl HardwareDecoderFactory for MediaCodecFactory {
    fn create(&self, stream: &VideoStreamInfo) -> Option<Box<dyn VideoBackend>> {
        match MediaCodecBackend::open(stream) {
            Ok(backend) => Some(Box::new(backend)),
            Err(reason) => {
                log::info!("using software decoding: {reason}");
                None
            }
        }
    }
}
