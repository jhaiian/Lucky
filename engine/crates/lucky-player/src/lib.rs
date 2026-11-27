mod ass;
mod audio;
mod backend;
mod clock;
mod demux;
mod effects;
mod external;
mod frame;
mod passthrough;
mod preroll;
mod queue;
mod render;
mod screenshot;
mod shared;
mod status;
mod subtitles;
mod thumbnail;
mod video;

use std::os::fd::OwnedFd;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::sync::mpsc::{self, Sender};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

pub use audio::AudioOutput;
pub use effects::{AudioEffects, EQ_BANDS};
pub use passthrough::EncodedCodec;
pub use backend::{BackendOutput, HardwareDecoderFactory, SoftwareBackend, VideoBackend, VideoStreamInfo};
pub use frame::{ColorInfo, DoviConfig, FrameData, HardwareFrame, VideoFrame, hdr10_plus_nits, hevc_hdr10_plus_nits, light_level, mastering_peak};
pub use render::{
    ActiveScaling, Ahead, DisplayLuminance, DisplayOptions, DownscalerMode, FitMode, MotionConfig, MotionSettings, Presenter, RenderCommand, ScalerMode,
    ViewTransform,
};
pub use lucky_ass::{RenderOutcome, Rendered, SubtitleStyle};
pub use screenshot::{Screenshot, ScreenshotPoll};
pub use status::{ChapterEntry, PlayerState, Status, TrackEntry};
pub use subtitles::{CueBatch, CueInfo};
pub use thumbnail::{Thumbnail, Thumbnailer};

use shared::{Shared, TrackRequest};

pub struct Player<W: Send + 'static> {
    shared: Arc<Shared>,
    render: Sender<RenderCommand<W>>,
    render_thread: Option<JoinHandle<()>>,
    demux_thread: Option<JoinHandle<()>>,
}

impl<W: Send + 'static> Player<W> {
    pub fn open<P: Presenter<Window = W>>(
        fd: OwnedFd,
        presenter: P,
        audio: Option<Box<dyn AudioOutput>>,
        hardware: Option<Box<dyn HardwareDecoderFactory>>,
        autoplay: bool,
    ) -> std::io::Result<Self> {
        Self::start(fd, presenter, audio, hardware, autoplay, false)
    }

    pub fn preload<P: Presenter<Window = W>>(
        fd: OwnedFd,
        presenter: P,
        audio: Option<Box<dyn AudioOutput>>,
        hardware: Option<Box<dyn HardwareDecoderFactory>>,
    ) -> std::io::Result<Self> {
        Self::start(fd, presenter, audio, hardware, false, true)
    }

    fn start<P: Presenter<Window = W>>(
        fd: OwnedFd,
        presenter: P,
        audio: Option<Box<dyn AudioOutput>>,
        hardware: Option<Box<dyn HardwareDecoderFactory>>,
        autoplay: bool,
        preload: bool,
    ) -> std::io::Result<Self> {
        let shared = Arc::new(Shared::new(autoplay));
        if preload {
            shared.begin_preload();
        }
        let (render, commands) = mpsc::channel();
        let render_shared = shared.clone();
        let render_thread = thread::Builder::new()
            .name("lucky-render".into())
            .spawn(move || render::run_renderer(render_shared, presenter, commands))?;
        let demux_shared = shared.clone();
        let demux_thread = thread::Builder::new()
            .name("lucky-demux".into())
            .spawn(move || demux::run_demuxer(demux_shared, fd, audio, hardware))?;
        Ok(Self { shared, render, render_thread: Some(render_thread), demux_thread: Some(demux_thread) })
    }

    pub fn activate(&self) {
        self.shared.end_preload();
        let _ = self.render.send(RenderCommand::Wake);
    }

    pub fn play(&self) {
        let ended = self.shared.status().state == PlayerState::Ended;
        if ended {
            self.seek(0, false);
        }
        self.shared.end_preload();
        if !self.shared.hold_play_for_hardware() {
            self.shared.paused.store(false, Ordering::Release);
        }
        let _ = self.render.send(RenderCommand::Wake);
    }

    pub fn pause(&self) {
        self.shared.cancel_play_hold();
        self.shared.paused.store(true, Ordering::Release);
        let _ = self.render.send(RenderCommand::Wake);
    }

    pub fn is_paused(&self) -> bool {
        self.shared.paused.load(Ordering::Acquire)
    }

    pub fn seek(&self, position_ms: i64, exact: bool) {
        self.shared.request_seek(position_ms, exact);
        let _ = self.render.send(RenderCommand::Wake);
    }

    pub fn step_frame(&self, forward: bool) {
        if !self.shared.paused.load(Ordering::Acquire) || !self.shared.has_video.load(Ordering::Acquire) {
            return;
        }
        if forward {
            self.shared.queue_step();
            let _ = self.render.send(RenderCommand::Wake);
        } else if let Some(target_ms) = self.shared.step_back_target_ms() {
            self.seek(target_ms, true);
        }
    }

    pub fn request_screenshot(&self) -> u64 {
        let ticket = self.shared.begin_screenshot();
        if self.render.send(RenderCommand::Screenshot(ticket)).is_err() {
            self.shared.finish_screenshot(ticket, None);
        }
        ticket
    }

    pub fn poll_screenshot(&self, ticket: u64) -> ScreenshotPoll {
        self.shared.poll_screenshot(ticket)
    }

    pub fn screenshot(&self) -> Option<Screenshot> {
        let ticket = self.request_screenshot();
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            match self.poll_screenshot(ticket) {
                ScreenshotPoll::Done(shot) => return shot,
                ScreenshotPoll::Pending if Instant::now() >= deadline => return None,
                ScreenshotPoll::Pending => thread::sleep(Duration::from_millis(5)),
            }
        }
    }

    pub fn set_loop_range(&self, start_ms: i64, end_ms: i64) {
        let start_ms = start_ms.max(0);
        if end_ms <= start_ms {
            self.shared.clear_loop();
            return;
        }
        self.shared.set_loop(start_ms.saturating_mul(1000), end_ms.saturating_mul(1000));
        let _ = self.render.send(RenderCommand::Wake);
    }

    pub fn clear_loop_range(&self) {
        self.shared.clear_loop();
    }

    fn current_position_ms(&self) -> i64 {
        self.shared.current_position_ms()
    }

    pub fn select_audio(&self, index: Option<i32>) {
        let index = index.filter(|i| *i >= 0).map(|i| i as usize);
        self.shared.track_requests.lock().unwrap_or_else(|e| e.into_inner()).push(TrackRequest::Audio(index));
        self.seek(self.current_position_ms(), true);
    }

    pub fn select_subtitle(&self, index: Option<i32>) {
        let index = index.filter(|i| *i >= 0).map(|i| i as usize);
        self.shared.track_requests.lock().unwrap_or_else(|e| e.into_inner()).push(TrackRequest::Subtitle(index));
        self.seek(self.current_position_ms(), true);
    }

    pub fn subtitles_since(&self, after_id: u64) -> CueBatch {
        self.shared.subtitles.since(after_id)
    }

    pub fn subtitle_bitmap(&self, id: u64, rect: usize) -> Option<(i32, i32, Vec<u32>)> {
        self.shared.subtitles.bitmap(id, rect)
    }

    pub fn add_external_subtitle(&self, fd: OwnedFd, name: &str) -> Option<i32> {
        let mut loaded = match external::load(fd, name) {
            Ok(loaded) => loaded,
            Err(error) => {
                log::warn!("cannot load the subtitle file {name}: {error}");
                return None;
            }
        };
        let mut externals = self.shared.externals.lock().unwrap_or_else(|e| e.into_inner());
        let id = (external::EXTERNAL_BASE + externals.len()) as i32;
        loaded.entry.index = id;
        self.shared.status().subtitle_tracks.push(loaded.entry.clone());
        externals.push(loaded);
        Some(id)
    }

    pub fn render_subtitles(&self, time_ms: i64, width: i32, height: i32, scale: f64, lift: f64, force: bool) -> RenderOutcome {
        ass::render(&self.shared, time_ms, width, height, scale, lift, force)
    }

    pub fn set_subtitle_style(&self, style: SubtitleStyle) {
        ass::set_style(&self.shared, style);
    }

    pub fn set_text_subtitles(&self, libass: bool) {
        if self.shared.text_libass.swap(libass, Ordering::AcqRel) != libass {
            self.shared.track_requests.lock().unwrap_or_else(|e| e.into_inner()).push(TrackRequest::ReloadSubtitle);
            self.seek(self.current_position_ms(), true);
        }
    }

    pub fn set_window(&self, window: Option<W>) {
        let (ack, done) = mpsc::channel();
        if self.render.send(RenderCommand::SetWindow(window, ack)).is_ok() {
            let _ = done.recv_timeout(Duration::from_secs(3));
        }
    }

    pub fn redraw(&self) {
        let _ = self.render.send(RenderCommand::Redraw);
    }

    pub fn set_view_transform(&self, transform: ViewTransform) {
        let _ = self.render.send(RenderCommand::SetTransform(transform));
    }

    pub fn set_scaler(&self, mode: ScalerMode) {
        let _ = self.render.send(RenderCommand::SetScaler(mode));
    }

    pub fn set_downscaler(&self, mode: DownscalerMode) {
        let _ = self.render.send(RenderCommand::SetDownscaler(mode));
    }

    pub fn set_smooth_motion(&self, mode: u8) {
        let _ = self.render.send(RenderCommand::SetSmoothMotion(mode));
    }

    pub fn set_audio_effects(&self, effects: AudioEffects) {
        let effects = effects.sanitized();
        let shapes_sound_changed = {
            let mut slot = self.shared.audio_effects.lock().unwrap_or_else(|e| e.into_inner());
            let changed = slot.shapes_sound() != effects.shapes_sound();
            *slot = effects;
            changed
        };
        self.shared.effects_version.fetch_add(1, Ordering::AcqRel);
        if shapes_sound_changed {
            self.shared.bump_audio_plan();
        }
    }

    pub fn set_audio_output(&self, mode: u8, passthrough_codecs: u8) {
        let mode = mode.min(audio::OUTPUT_AUTO);
        let codecs = passthrough_codecs & EncodedCodec::ALL_BITS;
        let mode_changed = self.shared.audio_output_mode.swap(mode, Ordering::AcqRel) != mode;
        let codecs_changed = self.shared.passthrough_codecs.swap(codecs, Ordering::AcqRel) != codecs;
        if mode_changed || codecs_changed {
            self.shared.bump_audio_plan();
        }
    }

    pub fn begin_audio(&self) {
        self.shared.audio_ready.store(true, Ordering::Release);
    }

    pub fn audio_route_changed(&self) {
        self.shared.bump_audio_plan();
    }

    pub fn set_motion(&self, settings: MotionSettings) {
        let _ = self.render.send(RenderCommand::SetMotion(settings));
    }

    pub fn set_antiring(&self, strength: f32) {
        let value = if strength.is_finite() { strength.clamp(0.0, 1.0) } else { 0.0 };
        let _ = self.render.send(RenderCommand::SetAntiring(value));
    }

    pub fn set_performance(&self, auto_quality: bool, fast_decode: bool, software_fallback: bool, learned_cost: f32) {
        let learned = if learned_cost.is_finite() && learned_cost >= 1.0 { learned_cost } else { 0.0 };
        self.shared.learned_cost.store(learned.to_bits(), Ordering::Relaxed);
        self.shared.auto_quality.store(auto_quality, Ordering::Relaxed);
        self.shared.fast_decode.store(fast_decode, Ordering::Relaxed);
        self.shared.software_fallback.store(software_fallback, Ordering::Relaxed);
        let _ = self.render.send(RenderCommand::Wake);
    }

    pub fn set_deinterlace(&self, mode: u8) {
        self.shared.deinterlace.store(mode.min(2), Ordering::Relaxed);
    }

    pub fn set_display(&self, options: DisplayOptions) {
        let _ = self.render.send(RenderCommand::SetDisplay(options));
    }

    pub fn set_speed(&self, speed: f64) {
        let speed = speed.clamp(0.1, 8.0);
        let was_normal = (self.shared.clock.rate() - 1.0).abs() < 1e-6;
        self.shared.clock.set_rate(speed);
        if was_normal != ((speed - 1.0).abs() < 1e-6) {
            self.shared.bump_audio_plan();
        }
        let _ = self.render.send(RenderCommand::Wake);
    }

    pub fn status(&self) -> Status {
        self.shared.snapshot()
    }
}

impl<W: Send + 'static> Drop for Player<W> {
    fn drop(&mut self) {
        self.shared.abort.store(true, Ordering::Release);
        self.shared.interrupt.store(true, Ordering::Release);
        self.shared.video_packets.abort();
        self.shared.audio_packets.abort();
        self.shared.video_frames.abort();
        let _ = self.render.send(RenderCommand::Shutdown);
        if let Some(handle) = self.demux_thread.take() {
            let _ = handle.join();
        }
        if let Some(handle) = self.render_thread.take() {
            let _ = handle.join();
        }
    }
}
