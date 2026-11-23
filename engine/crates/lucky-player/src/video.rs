use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::thread;
use std::time::{Duration, Instant};

use lucky_ffmpeg::{Deinterlacer, Frame, Packet, sys};

use crate::backend::{BackendOutput, HardwareDecoderFactory, SoftwareBackend, VideoBackend, VideoStreamInfo};
use crate::frame::{ColorInfo, FrameData, VideoFrame};
use crate::queue::{FrameItem, PacketItem};
use crate::shared::Shared;

pub struct VideoParams {
    pub start_us: i64,
    pub frame_duration_us: i64,
    pub rotation: i32,
    pub sample_aspect: (i32, i32),
    pub color: ColorInfo,
    pub stream: VideoStreamInfo,
    pub threads: i32,
    pub field_order: sys::AVFieldOrder,
    pub hardware: Option<Box<dyn HardwareDecoderFactory>>,
}

const HARDWARE_ATTEMPTS: u32 = 4;
const HARDWARE_RETRY: Duration = Duration::from_millis(80);
const HARDWARE_RESETS: usize = 3;
const RESET_WINDOW: Duration = Duration::from_secs(60);
const DEINTERLACE_OFF: u8 = 0;
const DEINTERLACE_AUTO: u8 = 1;
const DEINTERLACE_QUIET_FRAMES: u32 = 48;
const DEINTERLACE_FRAME_ONLY_LEVEL: u8 = 2;

fn field_layout(order: sys::AVFieldOrder) -> (bool, Option<bool>) {
    match order {
        sys::AVFieldOrder::AV_FIELD_TT | sys::AVFieldOrder::AV_FIELD_BT => (true, Some(true)),
        sys::AVFieldOrder::AV_FIELD_BB | sys::AVFieldOrder::AV_FIELD_TB => (true, Some(false)),
        _ => (false, None),
    }
}

struct Released(String);

impl VideoBackend for Released {
    fn name(&self) -> String {
        self.0.clone()
    }

    fn hardware(&self) -> bool {
        true
    }

    fn send(&mut self, _packet: Option<&Packet>) -> Result<bool, String> {
        Err("decoder released".into())
    }

    fn receive(&mut self, _wait: bool) -> Result<BackendOutput, String> {
        Err("decoder released".into())
    }

    fn flush(&mut self) {}
}

struct VideoDecodeLoop {
    shared: Arc<Shared>,
    backend: Box<dyn VideoBackend>,
    params: VideoParams,
    serial: u64,
    skip_until_us: Option<i64>,
    last_pts_us: Option<i64>,
    errors: u32,
    packets_without_output: u32,
    last_output: Instant,
    outputs: u64,
    catch_up: u8,
    catch_up_window: Instant,
    window_frames: u32,
    window_late: u32,
    calm_windows: u32,
    resets: Vec<Instant>,
    deinterlacer: Deinterlacer,
    interlaced_stream: bool,
    top_field_first: Option<bool>,
    deinterlace_mode: u8,
    deinterlace_failed: bool,
    deinterlace_quiet: u32,
    deinterlace_duration_us: i64,
    deinterlaced: bool,
}

pub fn report_backend(shared: &Shared, backend: &dyn VideoBackend) {
    let mut status = shared.status();
    status.video_decoder = Some(backend.name());
    status.decoder_kind = Some(if backend.hardware() { "hardware" } else { "software" }.into());
}

impl VideoDecodeLoop {
    fn stale(&self) -> bool {
        self.shared.aborted() || self.shared.current_serial() != self.serial || self.shared.hardware_requested()
    }

    fn activate_hardware(&mut self) {
        if !self.backend.hardware() {
            let mut fresh = None;
            if let Some(factory) = self.params.hardware.as_ref() {
                for attempt in 0..HARDWARE_ATTEMPTS {
                    if self.shared.aborted() {
                        break;
                    }
                    fresh = factory.create(&self.params.stream);
                    if fresh.is_some() {
                        break;
                    }
                    if attempt + 1 < HARDWARE_ATTEMPTS {
                        thread::sleep(HARDWARE_RETRY);
                    }
                }
            }
            match fresh {
                Some(backend) => {
                    log::info!("preloaded stream switched to the hardware decoder {}", backend.name());
                    self.backend = backend;
                    self.catch_up = 0;
                    self.errors = 0;
                    self.packets_without_output = 0;
                    self.last_output = Instant::now();
                    report_backend(&self.shared, self.backend.as_ref());
                }
                None => log::warn!("hardware decoder unavailable after preloading, staying with software decoding"),
            }
            let position_ms = self.shared.current_position_ms();
            self.shared.request_seek(position_ms, true);
        }
        self.shared.finish_hardware_swap();
    }

    fn resume_ms(&self) -> i64 {
        match self.last_pts_us.or(self.skip_until_us) {
            Some(pts) => pts.max(0) / 1000,
            None => self.shared.status().position_ms.max(0),
        }
    }

    fn recover(&mut self) {
        if !self.backend.hardware() {
            return;
        }
        self.resets.retain(|at| at.elapsed() < RESET_WINDOW);
        let allow_software = self.shared.software_fallback.load(Ordering::Relaxed);
        if self.resets.len() < HARDWARE_RESETS || !allow_software {
            if let Some(factory) = self.params.hardware.as_ref() {
                let name = self.backend.name();
                self.backend = Box::new(Released(name.clone()));
                if let Some(fresh) = factory.create(&self.params.stream) {
                    log::warn!("hardware decoder {name} stopped responding, restarting it");
                    self.resets.push(Instant::now());
                    self.backend = fresh;
                    self.errors = 0;
                    self.packets_without_output = 0;
                    self.last_output = Instant::now();
                    report_backend(&self.shared, self.backend.as_ref());
                    let resume_ms = self.resume_ms();
                    self.shared.request_seek(resume_ms, true);
                    return;
                }
            }
        }
        self.fall_back();
    }

    fn fall_back(&mut self) {
        if !self.backend.hardware() {
            return;
        }
        match SoftwareBackend::open(&self.params.stream, self.params.threads) {
            Ok(software) => {
                log::warn!("hardware decoder {} failed, switching to {}", self.backend.name(), software.name());
                self.backend = Box::new(software);
                self.catch_up = 0;
                self.errors = 0;
                self.packets_without_output = 0;
                self.last_output = Instant::now();
                report_backend(&self.shared, self.backend.as_ref());
                let resume_ms = self.resume_ms();
                self.shared.status().decoder_fallbacks += 1;
                self.shared.request_seek(resume_ms, true);
            }
            Err(error) => log::error!("software fallback failed: {error}"),
        }
    }

    fn record_error(&mut self, error: String) {
        self.errors += 1;
        log::warn!("video decoder error ({}): {error}", self.errors);
        if self.backend.hardware() && self.errors >= 3 {
            self.recover();
        }
    }

    fn emit(&mut self, data: FrameData, pts_us: Option<i64>, duration_us: Option<i64>, sample_aspect: Option<(i32, i32)>) -> bool {
        self.shared.counters.decoded.fetch_add(1, Ordering::Relaxed);
        self.outputs += 1;
        self.errors = 0;
        self.packets_without_output = 0;
        self.last_output = Instant::now();
        let duration_us = duration_us.filter(|d| *d > 0).unwrap_or(self.params.frame_duration_us);
        let pts_us = match pts_us {
            Some(pts) => pts - self.params.start_us,
            None => self.last_pts_us.map(|p| p + duration_us).unwrap_or(0),
        };
        self.last_pts_us = Some(pts_us);
        if let Some(target) = self.skip_until_us {
            if pts_us + duration_us <= target {
                return true;
            }
            self.skip_until_us = None;
        }
        self.track_lateness(pts_us, duration_us);
        let color = match &data {
            FrameData::Software(frame) => self.params.color.merged_with(frame),
            FrameData::Hardware(frame) => self.params.color.with_scene_nits(frame.scene_nits()),
        };
        let item = FrameItem::Frame(VideoFrame {
            data,
            pts_us,
            duration_us,
            serial: self.serial,
            rotation: self.params.rotation,
            sample_aspect: sample_aspect.unwrap_or(self.params.sample_aspect),
            color,
        });
        let shared = self.shared.clone();
        let serial = self.serial;
        let pushed =
            shared.video_frames.push(item, || shared.aborted() || shared.current_serial() != serial || shared.hardware_requested());
        self.last_output = Instant::now();
        pushed
    }

    fn sync_deinterlace_rate(&mut self) {
        self.deinterlacer.set_fields(self.catch_up < DEINTERLACE_FRAME_ONLY_LEVEL);
    }

    fn set_deinterlaced(&mut self, active: bool) {
        if self.deinterlaced != active {
            self.deinterlaced = active;
            self.shared.status().deinterlaced = active;
        }
    }

    fn emit_filtered(&mut self, frames: Vec<Frame>) -> bool {
        for frame in frames {
            let fallback = if self.deinterlacer.fields() { self.deinterlace_duration_us / 2 } else { self.deinterlace_duration_us };
            let pts_us = frame.timestamp();
            let duration_us = Some(frame.duration()).filter(|d| *d > 0).or(Some(fallback));
            let sample_aspect = frame.sample_aspect();
            if !self.emit(FrameData::Software(frame), pts_us, duration_us, sample_aspect) {
                return false;
            }
        }
        true
    }

    fn release_deinterlacer(&mut self) -> bool {
        self.deinterlacer.flush();
        let frames = self.deinterlacer.pull();
        self.deinterlace_quiet = 0;
        self.set_deinterlaced(false);
        self.emit_filtered(frames)
    }

    fn handle_frame(
        &mut self,
        data: FrameData,
        pts_us: Option<i64>,
        duration_us: Option<i64>,
        sample_aspect: Option<(i32, i32)>,
    ) -> bool {
        let mut frame = match data {
            FrameData::Software(frame) => frame,
            hardware => return self.emit(hardware, pts_us, duration_us, sample_aspect),
        };
        let mode = self.shared.deinterlace.load(Ordering::Relaxed);
        if mode != self.deinterlace_mode {
            if !self.release_deinterlacer() {
                return false;
            }
            self.deinterlace_mode = mode;
            self.deinterlace_failed = false;
        }
        let duration = duration_us.filter(|d| *d > 0).unwrap_or(self.params.frame_duration_us);
        let start_us = self.params.start_us;
        let skipped = self.skip_until_us.is_some_and(|target| pts_us.is_some_and(|pts| pts - start_us + duration <= target));
        if mode == DEINTERLACE_OFF || self.deinterlace_failed || skipped {
            return self.emit(FrameData::Software(frame), pts_us, duration_us, sample_aspect);
        }
        let wanted = match mode {
            DEINTERLACE_AUTO => self.interlaced_stream || frame.is_interlaced(),
            _ => true,
        };
        if wanted {
            self.deinterlace_quiet = 0;
        } else if self.deinterlacer.active() {
            self.deinterlace_quiet += 1;
            if self.deinterlace_quiet > DEINTERLACE_QUIET_FRAMES && !self.release_deinterlacer() {
                return false;
            }
        }
        if !wanted && !self.deinterlacer.active() {
            self.set_deinterlaced(false);
            return self.emit(FrameData::Software(frame), pts_us, duration_us, sample_aspect);
        }
        self.deinterlace_duration_us = duration;
        frame.set_timing(pts_us.unwrap_or(sys::AV_NOPTS_VALUE), duration);
        if let Some(top_first) = self.top_field_first {
            if !frame.is_interlaced() {
                frame.mark_interlaced(top_first);
            }
        }
        self.sync_deinterlace_rate();
        if let Err(error) = self.deinterlacer.push(&frame) {
            log::warn!("deinterlacing is unavailable, showing frames as they are: {error}");
            self.deinterlace_failed = true;
            self.deinterlacer.reset();
            self.set_deinterlaced(false);
            return self.emit(FrameData::Software(frame), pts_us, duration_us, sample_aspect);
        }
        self.set_deinterlaced(true);
        let frames = self.deinterlacer.pull();
        self.emit_filtered(frames)
    }

    fn track_lateness(&mut self, pts_us: i64, duration_us: i64) {
        if self.backend.hardware() {
            return;
        }
        let enabled = self.shared.fast_decode.load(Ordering::Relaxed);
        if !self.shared.paused.load(Ordering::Relaxed) {
            if let Some(now) = self.shared.clock.now_us() {
                self.window_frames += 1;
                if pts_us + duration_us < now {
                    self.window_late += 1;
                }
            }
        }
        if self.catch_up_window.elapsed() < Duration::from_secs(1) {
            return;
        }
        let previous = self.catch_up;
        if !enabled {
            self.catch_up = 0;
        } else if self.window_frames > 0 {
            if self.window_late * 10 > self.window_frames {
                self.catch_up = (self.catch_up + 1).min(3);
                self.calm_windows = 0;
            } else if self.window_late == 0 {
                self.calm_windows += 1;
                if self.calm_windows >= 5 && self.catch_up > 0 {
                    self.catch_up -= 1;
                    self.calm_windows = 0;
                }
            }
        }
        if self.catch_up != previous {
            log::info!("decoder catch-up level {} -> {}", previous, self.catch_up);
            self.backend.set_catch_up(self.catch_up);
            self.sync_deinterlace_rate();
            self.shared.status().decoder_catch_up = self.catch_up;
        }
        self.catch_up_window = Instant::now();
        self.window_frames = 0;
        self.window_late = 0;
    }

    fn receive_all(&mut self, wait: bool) -> bool {
        let mut wait = wait;
        loop {
            if self.stale() {
                return false;
            }
            match self.backend.receive(wait) {
                Ok(BackendOutput::Frame { data, pts_us, duration_us, sample_aspect }) => {
                    wait = false;
                    if !self.handle_frame(data, pts_us, duration_us, sample_aspect) {
                        return false;
                    }
                }
                Ok(BackendOutput::NeedsInput) | Ok(BackendOutput::Finished) => return true,
                Err(error) => {
                    self.record_error(error);
                    return true;
                }
            }
        }
    }

    fn send(&mut self, packet: Option<&Packet>) -> bool {
        let mut started = Instant::now();
        loop {
            if self.stale() {
                return false;
            }
            match self.backend.send(packet) {
                Ok(true) => return true,
                Ok(false) => {
                    let outputs = self.outputs;
                    if !self.receive_all(true) {
                        return false;
                    }
                    if self.outputs != outputs {
                        started = Instant::now();
                    } else if started.elapsed() > Duration::from_secs(3) {
                        self.record_error("decoder stopped accepting input".into());
                        return false;
                    }
                }
                Err(error) => {
                    self.record_error(error);
                    return false;
                }
            }
        }
    }

    fn decode(&mut self, packet: &Packet) {
        if self.send(Some(packet)) {
            self.receive_all(false);
        }
        if self.backend.hardware() {
            self.packets_without_output += 1;
            let stalled = self.packets_without_output > 30 && self.last_output.elapsed() > Duration::from_millis(2_500);
            if self.packets_without_output > 90 || stalled {
                log::warn!(
                    "hardware decoder produced no frames for {} packets ({} ms)",
                    self.packets_without_output,
                    self.last_output.elapsed().as_millis()
                );
                self.packets_without_output = 0;
                self.recover();
            }
        }
    }

    fn drain(&mut self) {
        self.drain_backend();
        if !self.stale() {
            self.release_deinterlacer();
        }
    }

    fn drain_backend(&mut self) {
        if !self.send(None) {
            return;
        }
        let started = Instant::now();
        while started.elapsed() < Duration::from_secs(2) {
            if self.stale() {
                return;
            }
            match self.backend.receive(true) {
                Ok(BackendOutput::Frame { data, pts_us, duration_us, sample_aspect }) => {
                    if !self.handle_frame(data, pts_us, duration_us, sample_aspect) {
                        return;
                    }
                }
                Ok(BackendOutput::NeedsInput) => continue,
                Ok(BackendOutput::Finished) => return,
                Err(error) => {
                    self.record_error(error);
                    return;
                }
            }
        }
    }

    fn run(mut self) {
        while !self.shared.aborted() {
            if self.shared.hardware_requested() {
                self.activate_hardware();
            }
            let Some(item) = self.shared.video_packets.pop(Duration::from_millis(50)) else {
                continue;
            };
            match item {
                PacketItem::Flush { serial, target_us } => {
                    self.backend.flush();
                    self.deinterlacer.reset();
                    self.deinterlace_quiet = 0;
                    self.serial = serial;
                    self.skip_until_us = target_us;
                    self.last_pts_us = None;
                    self.packets_without_output = 0;
                    self.last_output = Instant::now();
                }
                PacketItem::Packet { packet, serial } => {
                    if serial == self.serial {
                        self.decode(&packet);
                    }
                }
                PacketItem::End { serial } => {
                    if serial == self.serial {
                        self.drain();
                        self.backend.flush();
                        let shared = self.shared.clone();
                        shared
                            .video_frames
                            .push(FrameItem::End { serial }, || shared.aborted() || shared.current_serial() != serial);
                    }
                }
            }
        }
    }
}

pub fn run_video_decoder(shared: Arc<Shared>, backend: Box<dyn VideoBackend>, params: VideoParams) {
    let serial = shared.current_serial();
    let (interlaced_stream, top_field_first) = field_layout(params.field_order);
    let deinterlacer = Deinterlacer::new(sys::AV_TIME_BASE_Q, params.threads);
    VideoDecodeLoop {
        shared,
        backend,
        params,
        serial,
        skip_until_us: None,
        last_pts_us: None,
        errors: 0,
        packets_without_output: 0,
        last_output: Instant::now(),
        outputs: 0,
        catch_up: 0,
        catch_up_window: Instant::now(),
        window_frames: 0,
        window_late: 0,
        calm_windows: 0,
        resets: Vec::new(),
        deinterlacer,
        interlaced_stream,
        top_field_first,
        deinterlace_mode: DEINTERLACE_AUTO,
        deinterlace_failed: false,
        deinterlace_quiet: 0,
        deinterlace_duration_us: 0,
        deinterlaced: false,
    }
    .run();
}
