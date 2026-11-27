use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::sync::mpsc::{Receiver, RecvTimeoutError, Sender};
use std::time::{Duration, Instant};

use crate::frame::{FrameData, VideoFrame};
use crate::queue::FrameItem;
use crate::screenshot::{self, Screenshot};
use crate::shared::Shared;
use crate::status::PlayerState;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FitMode {
    Fit,
    Fill,
    Stretch,
    Original,
}

impl FitMode {
    pub fn from_code(code: i32) -> Self {
        match code {
            1 => FitMode::Fill,
            2 => FitMode::Stretch,
            3 => FitMode::Original,
            _ => FitMode::Fit,
        }
    }
}

pub const KERNEL_COUNT: i32 = 21;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ScalerMode(i32);

impl ScalerMode {
    pub const BILINEAR: ScalerMode = ScalerMode(0);

    pub fn from_code(code: i32) -> Self {
        if (0..KERNEL_COUNT).contains(&code) && code != 15 && code != 16 { ScalerMode(code) } else { Self::BILINEAR }
    }

    pub fn code(self) -> i32 {
        self.0
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DownscalerMode(i32);

impl DownscalerMode {
    pub const BILINEAR: DownscalerMode = DownscalerMode(0);

    pub fn from_code(code: i32) -> Self {
        if (0..17).contains(&code) && code != 1 { DownscalerMode(code) } else { Self::BILINEAR }
    }

    pub fn code(self) -> i32 {
        self.0
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ViewTransform {
    pub fit: FitMode,
    pub aspect: f32,
    pub zoom: f32,
    pub zoom_x: f32,
    pub zoom_y: f32,
    pub pan_x: f32,
    pub pan_y: f32,
    pub turns: i32,
    pub mirror: bool,
}

impl Default for ViewTransform {
    fn default() -> Self {
        Self { fit: FitMode::Fit, aspect: 0.0, zoom: 1.0, zoom_x: 1.0, zoom_y: 1.0, pan_x: 0.0, pan_y: 0.0, turns: 0, mirror: false }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Default)]
pub struct DisplayLuminance {
    pub max_nits: f32,
    pub average_nits: f32,
    pub min_nits: f32,
    pub headroom: f32,
}

#[derive(Clone, Copy, Debug, PartialEq, Default)]
pub struct DisplayOptions {
    pub hdr: bool,
    pub wide_color: bool,
    pub adaptive: bool,
    pub luminance: DisplayLuminance,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub struct MotionSettings {
    pub level: u8,
    pub target: u8,
    pub fps: u16,
    pub anime: bool,
    pub low_fps_only: bool,
    pub learned: u8,
}

#[derive(Clone, Copy, Debug, PartialEq, Default)]
pub struct MotionConfig {
    pub level: u8,
    pub shrink: u8,
    pub hint_fps: f32,
}

impl MotionConfig {
    pub const MAX_SHRINK: u8 = 2;

    pub fn scale(&self) -> f32 {
        match self.shrink {
            0 => 1.0,
            1 => 0.75,
            _ => 0.6,
        }
    }
}

#[derive(Clone, Copy)]
pub struct Ahead<'a> {
    pub frame: &'a VideoFrame,
    pub frames_left: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Default)]
pub struct ActiveScaling {
    pub kernel: i32,
    pub direction: u8,
    pub cost: f32,
}

pub trait Presenter: Send + 'static {
    type Window: Send + 'static;

    fn set_window(&mut self, window: Option<Self::Window>);
    fn has_window(&self) -> bool;
    fn present(&mut self, frame: &VideoFrame) -> bool;
    fn name(&self) -> Option<String>;
    fn generation(&self) -> u32 {
        0
    }
    fn set_transform(&mut self, _transform: ViewTransform) {}
    fn set_scaler(&mut self, _mode: ScalerMode) {}
    fn set_downscaler(&mut self, _mode: DownscalerMode) {}
    fn set_antiring(&mut self, _strength: f32) {}
    fn set_quality_cap(&mut self, _cap: f32) {}
    fn set_smooth_motion(&mut self, _enabled: bool) {}
    fn set_motion(&mut self, _config: MotionConfig) {}
    fn check_duplicate(&mut self, _from: &VideoFrame, _to: &VideoFrame) -> Option<bool> {
        None
    }
    fn smooth_timing(&mut self) -> Option<(i64, i64, i64)> {
        None
    }
    fn present_blend(&mut self, from: &VideoFrame, to: &VideoFrame, weight: f32, delay_us: i64) -> bool {
        self.present_at(if weight >= 0.5 { to } else { from }, delay_us)
    }
    fn present_blend_ahead(&mut self, from: &VideoFrame, to: &VideoFrame, weight: f32, delay_us: i64, _ahead: Ahead<'_>) -> bool {
        self.present_blend(from, to, weight, delay_us)
    }
    fn active_scaling(&self) -> ActiveScaling {
        ActiveScaling::default()
    }
    fn set_display(&mut self, _options: DisplayOptions) {}
    fn capture(&mut self, _frame: &VideoFrame, _width: i32, _height: i32) -> Option<Vec<u8>> {
        None
    }
    fn output_mode(&self) -> i32 {
        0
    }
    fn present_at(&mut self, frame: &VideoFrame, _delay_us: i64) -> bool {
        self.present(frame)
    }
}

const PRESENT_LEAD_MIN_US: f64 = 6_000.0;
const PRESENT_LEAD_MAX_US: f64 = 24_000.0;
const TAIL_STALL: Duration = Duration::from_secs(3);
const TAIL_STEP_US: f64 = 250_000.0;
const TAIL_OVERRUN_MS: i64 = 2_000;
const LOOP_WINDOW_US: i64 = 1_500_000;

struct TailWatch {
    value: Option<i64>,
    since: Instant,
}

impl TailWatch {
    fn new(value: Option<i64>, now: Instant) -> Self {
        Self { value, since: now }
    }

    fn observe(&mut self, value: Option<i64>, step_us: i64, now: Instant) -> Duration {
        let moved = match (self.value, value) {
            (Some(seen), Some(current)) => (current - seen).abs() >= step_us,
            (None, None) => false,
            _ => true,
        };
        if moved {
            self.value = value;
            self.since = now;
            return Duration::ZERO;
        }
        now.saturating_duration_since(self.since)
    }
}

pub enum RenderCommand<W> {
    SetWindow(Option<W>, Sender<()>),
    SetTransform(ViewTransform),
    SetScaler(ScalerMode),
    SetDownscaler(DownscalerMode),
    SetAntiring(f32),
    SetSmoothMotion(u8),
    SetMotion(MotionSettings),
    SetDisplay(DisplayOptions),
    Screenshot(u64),
    Redraw,
    Wake,
    Shutdown,
}

struct RenderLoop<P: Presenter> {
    shared: Arc<Shared>,
    presenter: P,
    serial: u64,
    pending: Option<VideoFrame>,
    last: Option<VideoFrame>,
    awaiting_first: bool,
    end_of_stream: bool,
    ended: bool,
    paused: bool,
    stepped: bool,
    clock_wait: Option<Instant>,
    tail: Option<TailWatch>,
    presenter_generation: u32,
    output_mode: i32,
    render_window: Instant,
    render_total_us: u64,
    render_peak_us: u64,
    render_count: u32,
    frame_interval_us: f64,
    quality_cap: f32,
    unit_cost_us: f64,
    learned_applied: bool,
    strained_windows: u32,
    relaxed_windows: u32,
    upgrade_after: u32,
    dropped_seen: u64,
    warmup_windows: u32,
    active_scaling: ActiveScaling,
    smooth: u8,
    blend_shown: bool,
    last_blend_vsync: i64,
    pure_present: bool,
    blends_in_window: u32,
    motion: MotionSettings,
    motion_level: u8,
    motion_shrink: u8,
    motion_strained: u32,
    motion_relaxed: u32,
    motion_upgrade_after: u32,
    motion_config: MotionConfig,
    duplicate_streak: u32,
    interpolated_in_window: u32,
    output_interval_ns: i64,
    phase_anchor: (i64, i64),
}

impl<P: Presenter> RenderLoop<P> {
    fn update_state(&self, state: PlayerState) {
        let mut status = self.shared.status();
        if status.state != PlayerState::Error && status.state != PlayerState::Opening {
            status.state = state;
        }
    }

    fn update_position(&self, position_us: i64) {
        let mut status = self.shared.status();
        let mut position = position_us.max(0) / 1000;
        if let Some(duration) = status.duration_ms {
            position = position.min(duration);
        }
        status.position_ms = position;
    }

    fn show(&mut self, frame: VideoFrame) {
        self.show_at(frame, 0);
    }

    fn tail_expired(&mut self) -> bool {
        let clock = self.shared.clock.now_us();
        let duration_ms = self.shared.status().duration_ms;
        if let (Some(now), Some(duration)) = (clock, duration_ms) {
            if now > (duration + TAIL_OVERRUN_MS) * 1000 {
                log::warn!(
                    "playback clock is {} ms past the end of the media, ending playback",
                    now / 1000 - duration
                );
                return true;
            }
        }
        let now = Instant::now();
        let step_us = (TAIL_STEP_US * self.shared.clock.rate().max(0.05)) as i64;
        let still = self.tail.get_or_insert_with(|| TailWatch::new(clock, now)).observe(clock, step_us, now);
        if still >= TAIL_STALL {
            log::warn!("playback clock has not advanced for {} ms at the end of the media, ending playback", still.as_millis());
            return true;
        }
        false
    }

    fn audio_tail_expired(&mut self) -> bool {
        if self.ended || self.paused || !self.shared.audio_input_finished_for(self.serial) {
            self.tail = None;
            return false;
        }
        self.tail_expired()
    }

    fn loop_due(&self, now_us: i64) -> Option<i64> {
        let (start_us, end_us) = self.shared.loop_range()?;
        if now_us >= end_us + LOOP_WINDOW_US {
            return None;
        }
        let upcoming = self.pending.as_ref().is_some_and(|frame| {
            frame.serial == self.serial && frame.pts_us >= end_us && now_us + PRESENT_LEAD_MAX_US as i64 >= frame.pts_us
        });
        (now_us >= end_us || upcoming).then_some(start_us)
    }

    fn loop_to(&mut self, start_us: i64) {
        self.shared.request_seek(start_us / 1000, true);
        self.tail = None;
    }

    fn restart_loop_at_end(&mut self, position_us: i64) -> bool {
        let Some((start_us, end_us)) = self.shared.loop_range() else {
            return false;
        };
        if position_us - end_us >= LOOP_WINDOW_US {
            return false;
        }
        self.loop_to(start_us);
        true
    }

    fn cap_wait_for_loop(&self, wait: Duration) -> Duration {
        if wait.is_zero() || self.paused {
            return wait;
        }
        let Some((_, end_us)) = self.shared.loop_range() else {
            return wait;
        };
        let Some(now_us) = self.shared.clock.now_us() else {
            return wait;
        };
        let remaining_us = end_us - now_us;
        if remaining_us <= 0 {
            return wait;
        }
        let real_us = remaining_us as f64 / self.shared.clock.rate().max(0.05);
        wait.min(Duration::from_micros(real_us.max(500.0) as u64))
    }

    fn finish_video(&mut self) {
        let duration = self.shared.status().duration_ms.map(|d| d * 1000);
        let end = self
            .last
            .as_ref()
            .map(|frame| frame.pts_us + frame.duration_us)
            .or(duration)
            .unwrap_or(0);
        let end = duration.map(|d| end.min(d)).unwrap_or(end);
        if self.restart_loop_at_end(end) {
            return;
        }
        self.ended = true;
        self.tail = None;
        self.shared.clock.set(end, false);
        self.update_position(end);
        self.update_state(PlayerState::Ended);
    }

    fn finish_audio_only(&mut self) {
        let end = match self.shared.clock.now_us() {
            Some(now) => now,
            None => self.shared.status().position_ms.max(0) * 1000,
        };
        if self.restart_loop_at_end(end) {
            return;
        }
        self.ended = true;
        self.tail = None;
        self.shared.clock.set(end, false);
        self.update_position(end);
        self.update_state(PlayerState::Ended);
    }

    fn record_render_time(&mut self, started: Instant) {
        let spent = started.elapsed().as_micros() as u64;
        self.render_total_us += spent;
        self.render_peak_us = self.render_peak_us.max(spent);
        self.render_count += 1;
        if self.render_window.elapsed() >= Duration::from_secs(1) && self.render_count > 0 {
            let average_us = self.render_total_us as f64 / self.render_count as f64;
            if !self.guard_motion(average_us) {
                self.guard_quality(average_us);
            }
            let mut status = self.shared.status();
            status.render_ms = (average_us / 1000.0) as f32;
            status.render_peak_ms = self.render_peak_us as f32 / 1000.0;
            status.smooth_blending = self.blends_in_window > 0;
            let interpolating = self.interpolated_in_window > 0;
            status.motion_level = if interpolating { self.motion_level } else { 0 };
            status.motion_scale = if interpolating { (self.motion_config.scale() * 100.0).round() as u8 } else { 0 };
            status.output_fps =
                if interpolating && self.output_interval_ns > 0 { 1_000_000_000.0 / self.output_interval_ns as f32 } else { 0.0 };
            drop(status);
            self.render_window = Instant::now();
            self.render_total_us = 0;
            self.render_peak_us = 0;
            self.render_count = 0;
            self.blends_in_window = 0;
            self.interpolated_in_window = 0;
        }
    }

    fn apply_cap(&mut self, cap: f32, average_us: f64) {
        if cap == self.quality_cap {
            return;
        }
        log::info!(
            "automatic quality cap {:.0} -> {:.0} (render {:.1} ms, {:.3} ms per tap)",
            self.quality_cap,
            cap,
            average_us / 1000.0,
            self.unit_cost_us / 1000.0
        );
        self.quality_cap = cap;
        self.presenter.set_quality_cap(cap);
        {
            let mut status = self.shared.status();
            status.cost_cap = if cap.is_finite() { cap } else { 0.0 };
            status.learned_cost = status.cost_cap;
        }
        self.reset_frame_stats();
        self.redraw();
    }

    fn guard_motion(&mut self, average_us: f64) -> bool {
        if self.smooth != 3 || self.interpolated_in_window == 0 {
            return false;
        }
        if self.warmup_windows > 0 || self.paused {
            return false;
        }
        let automatic = self.motion.level == 0;
        let budget = (self.output_interval_ns as f64 / 1_000.0).max(4_000.0);
        let peak_us = self.render_peak_us as f64;
        let strained = average_us > budget * 0.6 || peak_us > budget * 1.2;
        let overloaded = average_us > budget * 0.8 || peak_us > budget * 1.5;
        let relaxed = average_us < budget * 0.3 && peak_us < budget * 0.8;
        let roomy = average_us < budget * 0.45 && peak_us < budget * 0.9;
        let lower_level = automatic && self.motion_level > 1 && strained;
        let shrink = !lower_level && overloaded && self.motion_shrink < MotionConfig::MAX_SHRINK;
        if lower_level || shrink {
            self.motion_relaxed = 0;
            self.motion_strained += 1;
            if self.motion_strained >= 2 {
                self.motion_strained = 0;
                self.motion_upgrade_after = (self.motion_upgrade_after * 2).min(160);
                if lower_level {
                    self.set_motion_level(self.motion_level - 1);
                } else {
                    self.set_motion_shrink(self.motion_shrink + 1);
                }
            }
            return true;
        }
        self.motion_strained = 0;
        let grow = self.motion_shrink > 0 && roomy;
        let raise = self.motion_shrink == 0 && automatic && self.motion_level < 3 && relaxed;
        if grow || raise {
            self.motion_relaxed += 1;
            if self.motion_relaxed >= self.motion_upgrade_after {
                self.motion_relaxed = 0;
                if grow {
                    self.set_motion_shrink(self.motion_shrink - 1);
                } else {
                    self.set_motion_level(self.motion_level + 1);
                }
            }
        } else {
            self.motion_relaxed = 0;
        }
        false
    }

    fn set_motion_shrink(&mut self, shrink: u8) {
        let shrink = shrink.min(MotionConfig::MAX_SHRINK);
        if shrink == self.motion_shrink {
            return;
        }
        log::info!("motion interpolation resolution step {} -> {shrink}", self.motion_shrink);
        self.motion_shrink = shrink;
        self.reset_frame_stats();
        self.apply_motion(Some(self.motion_config.hint_fps));
    }

    fn set_motion_level(&mut self, level: u8) {
        let level = level.clamp(1, 3);
        if level == self.motion_level {
            return;
        }
        log::info!("motion interpolation level {} -> {level}", self.motion_level);
        self.motion_level = level;
        self.shared.status().learned_motion = level;
        self.reset_frame_stats();
        self.apply_motion(Some(self.motion_config.hint_fps));
    }

    fn apply_motion(&mut self, hint_fps: Option<f32>) {
        let level = if self.smooth == 3 && hint_fps.is_some() { self.motion_level } else { 0 };
        let hint_fps = hint_fps.unwrap_or(0.0);
        let config = MotionConfig { level, shrink: self.motion_shrink, hint_fps };
        if config != self.motion_config {
            self.motion_config = config;
            self.presenter.set_motion(config);
        }
    }

    fn motion_cadence(&self, period_ns: i64, frame_us: f64) -> i64 {
        let screen_hz = 1_000_000_000.0 / period_ns.max(1) as f64;
        let wanted = match self.motion.target {
            1 => 60.0,
            2 => 90.0,
            3 => 120.0,
            4 => 2_000_000.0 / frame_us.max(1.0),
            5 => self.motion.fps.max(1) as f64,
            _ => screen_hz,
        };
        ((screen_hz / wanted.max(1.0)) + 0.3).floor().max(1.0) as i64
    }

    fn hint_rate(&self, frame_us: f64) -> f32 {
        match self.motion.target {
            1 => 60.0,
            2 => 90.0,
            3 => 120.0,
            4 => (2_000_000.0 / frame_us.max(1.0)) as f32,
            5 => self.motion.fps as f32,
            _ => 0.0,
        }
    }

    fn phase_media(&self, target_ns: i64, measured: i64, period_ns: i64, rate: f64, frame_us: f64) -> i64 {
        let (anchor_ns, anchor_media) = self.phase_anchor;
        if anchor_ns <= 0 || anchor_ns != self.last_blend_vsync || target_ns <= anchor_ns || period_ns <= 0 {
            return measured;
        }
        let vsyncs = ((target_ns - anchor_ns) as f64 / period_ns as f64).round().max(1.0);
        let predicted = anchor_media + (vsyncs * period_ns as f64 / 1_000.0 * rate) as i64;
        let error = measured - predicted;
        let limit = (frame_us * rate * 0.75).clamp(8_000.0, 50_000.0) as i64;
        if error.abs() > limit {
            return measured;
        }
        predicted + error / 12
    }

    fn count_missed(&self, target_ns: i64, interval_ns: i64) {
        if self.last_blend_vsync <= 0 || interval_ns <= 0 {
            return;
        }
        let late = target_ns - (self.last_blend_vsync + interval_ns);
        if late > interval_ns / 2 {
            let missed = (late + interval_ns / 2) / interval_ns;
            if missed <= 120 {
                self.shared.counters.dropped.fetch_add(missed as u64, Ordering::Relaxed);
            }
        }
    }

    fn step_interpolated(&mut self, now: i64, rate: f64) -> Option<Duration> {
        let (now_ns, vsync_ns, period_ns) = self.presenter.smooth_timing()?;
        let (last_pts, frame_pts, frame_duration) = {
            let last = self.last.as_ref()?;
            let frame = self.pending.as_ref()?;
            (last.pts_us, frame.pts_us, frame.duration_us)
        };
        let frame_us = frame_duration.max(1) as f64 / rate;
        if self.motion.low_fps_only && frame_duration > 0 && 1_000_000.0 / (frame_duration as f64) > 31.0 {
            self.last_blend_vsync = 0;
            self.apply_motion(None);
            return None;
        }
        let cadence = self.motion_cadence(period_ns, frame_us);
        let interval_ns = period_ns * cadence;
        if frame_us * 1_000.0 < interval_ns as f64 * 1.05 {
            self.last_blend_vsync = 0;
            self.apply_motion(None);
            return None;
        }
        if self.motion_level == 0 {
            self.motion_level = match (self.motion.level, self.motion.learned) {
                (0, learned @ 1..=3) => learned,
                (0, _) => 2,
                (level, _) => level.clamp(1, 3),
            };
        }
        self.apply_motion(Some(self.hint_rate(frame_us)));
        self.output_interval_ns = interval_ns;
        self.frame_interval_us = interval_ns as f64 / 1_000.0;
        let steps = ((now_ns + period_ns / 2 - vsync_ns) as f64 / period_ns as f64).ceil() as i64;
        let mut target_ns = vsync_ns + steps * period_ns;
        if self.last_blend_vsync > 0 {
            let grid = self.last_blend_vsync + interval_ns;
            if target_ns < grid - period_ns / 2 {
                let grid_steps = ((grid - vsync_ns) as f64 / period_ns as f64).round() as i64;
                target_ns = vsync_ns + grid_steps * period_ns;
            }
        }
        if (target_ns - self.last_blend_vsync).abs() < period_ns / 2 {
            return Some(Duration::from_micros((period_ns / 2_000).max(1_000) as u64));
        }
        let target_delay = (target_ns - now_ns) / 1_000;
        let lead_us = (period_ns as f64 * 1.5 / 1_000.0) as i64;
        if target_delay > lead_us + 1_000 {
            self.update_state(PlayerState::Playing);
            self.update_position(now);
            return Some(Duration::from_micros((target_delay - lead_us).clamp(1_000, 20_000) as u64));
        }
        let measured = now + (target_delay as f64 * rate) as i64;
        let media_at_target = self.phase_media(target_ns, measured, period_ns, rate, frame_us);
        if media_at_target >= frame_pts {
            if self.shared.video_frames.front_pts(self.serial).is_some() {
                self.last = self.pending.take();
                self.duplicate_streak = 0;
                return Some(Duration::ZERO);
            }
            let frame = self.pending.take()?;
            self.count_missed(target_ns, interval_ns);
            self.pure_present = true;
            self.last_blend_vsync = target_ns;
            self.phase_anchor = (target_ns, media_at_target);
            self.show_at(frame, target_delay.max(0));
            self.duplicate_streak = 0;
            self.update_state(PlayerState::Playing);
            return Some(Duration::ZERO);
        }
        let (previous, next) = (self.last.take()?, self.pending.take()?);
        if self.motion.anime && self.duplicate_streak < 2 && self.presenter.check_duplicate(&previous, &next) == Some(true) {
            self.last = Some(previous);
            self.duplicate_streak += 1;
            return Some(Duration::ZERO);
        }
        let span = (next.pts_us - last_pts).max(1) as f64;
        let weight = ((media_at_target - last_pts) as f64 / span).clamp(0.0, 1.0);
        self.count_missed(target_ns, interval_ns);
        let step_us = (interval_ns as f64 / 1_000.0 * rate).max(1.0);
        let frames_left = ((next.pts_us - media_at_target) as f64 / step_us).ceil().clamp(1.0, 64.0) as u32;
        let delay_us = target_delay.max(0);
        let started = Instant::now();
        let presenter = &mut self.presenter;
        let presented = match self.shared.video_frames.with_front(self.serial, |frame| {
            presenter.present_blend_ahead(&previous, &next, weight as f32, delay_us, Ahead { frame, frames_left })
        }) {
            Some(presented) => presented,
            None => self.presenter.present_blend(&previous, &next, weight as f32, delay_us),
        };
        self.record_render_time(started);
        if presented {
            self.shared.counters.presented.fetch_add(1, Ordering::Relaxed);
        }
        self.last = Some(previous);
        self.pending = Some(next);
        self.last_blend_vsync = target_ns;
        self.phase_anchor = (target_ns, media_at_target);
        self.blends_in_window += 1;
        self.interpolated_in_window += 1;
        self.update_state(PlayerState::Playing);
        self.update_position(now);
        Some(Duration::from_micros((period_ns / 2_000).max(1_000) as u64))
    }

    fn guard_quality(&mut self, average_us: f64) {
        let dropped = self.shared.counters.dropped.load(Ordering::Relaxed);
        let new_drops = dropped.saturating_sub(self.dropped_seen);
        self.dropped_seen = dropped;
        let auto = self.shared.auto_quality.load(Ordering::Relaxed);
        if !auto {
            self.strained_windows = 0;
            self.relaxed_windows = 0;
            self.upgrade_after = 10;
            if self.quality_cap.is_finite() {
                self.quality_cap = f32::INFINITY;
                self.presenter.set_quality_cap(f32::INFINITY);
                self.shared.status().cost_cap = 0.0;
                self.redraw();
            }
            return;
        }
        if !self.learned_applied {
            self.learned_applied = true;
            let learned = f32::from_bits(self.shared.learned_cost.load(Ordering::Relaxed));
            if learned.is_finite() && learned >= 1.0 {
                self.quality_cap = learned;
                self.presenter.set_quality_cap(learned);
                self.shared.status().cost_cap = learned;
                self.redraw();
            }
        }
        if self.warmup_windows > 0 {
            self.warmup_windows -= 1;
            self.strained_windows = 0;
            return;
        }
        if self.paused {
            return;
        }
        let scaling = self.active_scaling;
        if scaling.direction == 0 || scaling.cost < 1.0 {
            self.strained_windows = 0;
            self.relaxed_windows = 0;
            return;
        }
        let budget = self.frame_interval_us.max(4_000.0);
        let target = budget * 0.5;
        let measured = average_us / scaling.cost as f64;
        let strained = average_us > budget * 0.65 || (new_drops >= 3 && average_us > budget * 0.5);
        let relaxed = average_us < budget * 0.35;
        if strained {
            self.unit_cost_us = self.unit_cost_us.max(measured);
            self.strained_windows += 1;
            self.relaxed_windows = 0;
        } else if relaxed {
            self.relaxed_windows += 1;
            self.strained_windows = 0;
            self.unit_cost_us = (self.unit_cost_us * 0.98).max(measured);
        } else {
            self.strained_windows = 0;
            self.unit_cost_us = self.unit_cost_us.max(measured);
        }
        if self.strained_windows >= 2 {
            let affordable = (target / self.unit_cost_us.max(1.0)) as f32;
            let cap = affordable.min(scaling.cost * 0.95).max(1.0);
            self.strained_windows = 0;
            self.relaxed_windows = 0;
            self.upgrade_after = (self.upgrade_after * 2).min(160);
            self.apply_cap(cap, average_us);
        } else if self.quality_cap.is_finite() && self.relaxed_windows >= self.upgrade_after {
            self.relaxed_windows = 0;
            let affordable = (target / self.unit_cost_us.max(measured).max(1.0)) as f32;
            if affordable > scaling.cost * 1.2 {
                let cap = if affordable >= 256.0 { f32::INFINITY } else { affordable };
                self.apply_cap(cap, average_us);
            }
        }
    }

    fn reset_frame_stats(&mut self) {
        self.shared.counters.presented.store(0, Ordering::Relaxed);
        self.shared.counters.dropped.store(0, Ordering::Relaxed);
        self.dropped_seen = 0;
        self.strained_windows = 0;
        self.relaxed_windows = 0;
    }

    fn show_at(&mut self, frame: VideoFrame, delay_us: i64) {
        let rate = self.shared.clock.rate().max(0.05);
        self.frame_interval_us = frame.duration_us.max(1) as f64 / rate;
        let started = Instant::now();
        let presented = if self.pure_present {
            self.pure_present = false;
            match self.last.take() {
                Some(previous) => {
                    let presented = self.presenter.present_blend(&previous, &frame, 1.0, delay_us.max(0));
                    self.last = Some(previous);
                    presented
                }
                None => self.presenter.present_blend(&frame, &frame, 1.0, delay_us.max(0)),
            }
        } else if delay_us > 0 {
            self.presenter.present_at(&frame, delay_us)
        } else {
            self.presenter.present(&frame)
        };
        self.blend_shown = false;
        self.record_render_time(started);
        if presented {
            self.shared.counters.presented.fetch_add(1, Ordering::Relaxed);
        }
        let previous = self.last.as_ref().filter(|last| last.serial == frame.serial && last.pts_us < frame.pts_us).map(|last| last.pts_us);
        self.shared.record_shown(frame.serial, frame.pts_us, frame.duration_us, previous);
        let generation = self.presenter.generation();
        if generation != self.presenter_generation {
            self.presenter_generation = generation;
            let name = self.presenter.name();
            self.shared.status().renderer = name;
        }
        let scaling = self.presenter.active_scaling();
        if scaling != self.active_scaling {
            self.active_scaling = scaling;
            let mut status = self.shared.status();
            status.active_kernel = scaling.kernel;
            status.scaling_direction = scaling.direction;
        }
        let output = self.presenter.output_mode();
        if output != self.output_mode {
            self.output_mode = output;
            self.shared.status().display_output = output;
        }
        if frame.serial == self.shared.current_serial() {
            self.update_position(frame.pts_us);
        }
        self.last = Some(frame);
    }

    fn capture_screenshot(&mut self) -> Option<Screenshot> {
        let frame = self.last.as_ref()?;
        match &frame.data {
            FrameData::Software(source) => screenshot::software(frame, source),
            FrameData::Hardware(_) => {
                let (width, height) = screenshot::output_size(frame);
                let rgba = self.presenter.capture(frame, width, height)?;
                (rgba.len() == width as usize * height as usize * 4).then_some(Screenshot { width, height, rgba })
            }
        }
    }

    fn redraw(&mut self) {
        if let Some(frame) = self.last.take() {
            self.presenter.present(&frame);
            self.last = Some(frame);
        }
    }

    fn handle(&mut self, command: RenderCommand<P::Window>) -> bool {
        match command {
            RenderCommand::SetWindow(window, ack) => {
                let attach = window.is_some();
                self.presenter.set_window(window);
                if attach {
                    let name = self.presenter.name();
                    self.shared.status().renderer = name;
                    self.redraw();
                }
                let _ = ack.send(());
                true
            }
            RenderCommand::SetTransform(transform) => {
                self.presenter.set_transform(transform);
                self.redraw();
                true
            }
            RenderCommand::SetScaler(mode) => {
                self.presenter.set_scaler(mode);
                self.reset_frame_stats();
                self.redraw();
                true
            }
            RenderCommand::SetDownscaler(mode) => {
                self.presenter.set_downscaler(mode);
                self.reset_frame_stats();
                self.redraw();
                true
            }
            RenderCommand::SetSmoothMotion(mode) => {
                self.smooth = mode.min(3);
                self.blend_shown = false;
                self.last_blend_vsync = 0;
                self.presenter.set_smooth_motion(self.smooth > 0);
                if self.smooth != 3 {
                    self.apply_motion(None);
                }
                true
            }
            RenderCommand::SetMotion(settings) => {
                let changed_level = settings.level != self.motion.level;
                self.motion = settings;
                if changed_level || self.motion_level == 0 {
                    self.motion_level = 0;
                    self.motion_shrink = 0;
                    self.motion_strained = 0;
                    self.motion_relaxed = 0;
                    self.motion_upgrade_after = 10;
                }
                if self.motion_config.level > 0 {
                    self.apply_motion(Some(self.motion_config.hint_fps));
                }
                true
            }
            RenderCommand::SetAntiring(strength) => {
                self.presenter.set_antiring(strength);
                self.reset_frame_stats();
                self.redraw();
                true
            }
            RenderCommand::SetDisplay(options) => {
                self.presenter.set_display(options);
                self.redraw();
                true
            }
            RenderCommand::Screenshot(ticket) => {
                let shot = self.capture_screenshot();
                self.shared.finish_screenshot(ticket, shot);
                true
            }
            RenderCommand::Redraw => {
                self.redraw();
                true
            }
            RenderCommand::Wake => true,
            RenderCommand::Shutdown => false,
        }
    }

    fn step_forward(&mut self) -> Option<Duration> {
        if self.shared.steps.load(Ordering::Acquire) == 0 {
            return None;
        }
        let Some(frame) = self.pending.take() else {
            if self.end_of_stream {
                self.shared.steps.store(0, Ordering::Release);
                return None;
            }
            return Some(Duration::from_millis(5));
        };
        self.shared.take_step();
        self.show(frame);
        self.stepped = true;
        self.update_state(PlayerState::Paused);
        Some(Duration::ZERO)
    }

    fn resync_after_steps(&mut self) {
        if !std::mem::take(&mut self.stepped) {
            return;
        }
        if let Some(last) = self.last.as_ref().filter(|last| last.serial == self.serial) {
            self.shared.request_seek(last.pts_us / 1000, true);
        }
    }

    fn fetch_pending(&mut self) {
        if self.pending.is_some() {
            return;
        }
        match self.shared.video_frames.pop_current(self.serial) {
            Some(FrameItem::Frame(frame)) => self.pending = Some(frame),
            Some(FrameItem::End { .. }) => self.end_of_stream = true,
            None => {}
        }
    }

    fn step(&mut self) -> Duration {
        let serial = self.shared.current_serial();
        if serial != self.serial {
            self.serial = serial;
            self.duplicate_streak = 0;
            self.last_blend_vsync = 0;
            self.warmup_windows = 3;
            self.blend_shown = false;
            self.pending = None;
            self.awaiting_first = true;
            self.end_of_stream = false;
            self.ended = false;
            self.stepped = false;
            self.tail = None;
        }

        let paused = self.shared.paused.load(Ordering::Acquire);
        if paused != self.paused {
            self.paused = paused;
            self.last_blend_vsync = 0;
            self.tail = None;
            if paused {
                self.shared.clock.pause();
            } else {
                self.resync_after_steps();
                self.shared.clock.resume();
            }
        } else if paused {
            self.shared.clock.pause();
        }

        if !self.shared.has_video.load(Ordering::Acquire) {
            return self.step_audio_only();
        }

        self.fetch_pending();

        if self.awaiting_first {
            if let Some(frame) = self.pending.take() {
                self.awaiting_first = false;
                if !self.shared.audio_master.load(Ordering::Acquire) {
                    self.shared.clock.set(frame.pts_us, !self.paused);
                }
                let start_us = frame.pts_us;
                self.show(frame);
                self.shared.video_start_us.store(start_us, Ordering::Release);
                self.shared.video_ready_serial.store(self.serial, Ordering::Release);
                self.update_state(if self.paused { PlayerState::Paused } else { PlayerState::Playing });
                return Duration::ZERO;
            }
            if self.end_of_stream {
                self.awaiting_first = false;
            } else {
                if !self.paused {
                    self.update_state(PlayerState::Buffering);
                }
                return Duration::from_millis(5);
            }
        }

        if self.paused {
            if let Some(wait) = self.step_forward() {
                return wait;
            }
            if !self.ended {
                self.update_state(PlayerState::Paused);
            }
            return Duration::from_millis(50);
        }

        if let Some(start_us) = self.shared.clock.now_us().and_then(|now| self.loop_due(now)) {
            self.loop_to(start_us);
            return Duration::ZERO;
        }

        if self.pending.is_none() {
            if self.end_of_stream {
                let audio_running =
                    self.shared.audio_master.load(Ordering::Acquire) && !self.shared.audio_finished_for(self.serial);
                if audio_running && !self.ended && !self.tail_expired() {
                    self.update_state(PlayerState::Playing);
                    if let Some(now) = self.shared.clock.now_us() {
                        self.update_position(now);
                    }
                    return Duration::from_millis(20);
                }
                if !self.ended {
                    self.finish_video();
                }
                return Duration::from_millis(50);
            }
            self.update_state(PlayerState::Buffering);
            if let Some(now) = self.shared.clock.now_us() {
                self.update_position(now);
            }
            return Duration::from_millis(5);
        }

        let Some(now) = self.shared.clock.now_us() else {
            if self.shared.audio_master.load(Ordering::Acquire) && !self.shared.audio_finished_for(self.serial) {
                let waited = self.clock_wait.get_or_insert_with(Instant::now).elapsed();
                if waited < Duration::from_secs(2) {
                    self.update_state(PlayerState::Buffering);
                    return Duration::from_millis(5);
                }
                log::warn!("audio clock did not start, syncing to the video clock");
                self.shared.audio_master.store(false, Ordering::Release);
            }
            self.clock_wait = None;
            if let Some(frame) = self.pending.take() {
                self.shared.clock.set(frame.pts_us, true);
                self.show(frame);
            }
            return Duration::ZERO;
        };
        self.clock_wait = None;

        if self.smooth == 3 && self.last.is_some() {
            let rate = self.shared.clock.rate().max(0.05);
            self.pure_present = false;
            if let Some(wait) = self.step_interpolated(now, rate) {
                return wait;
            }
            if self.pending.is_none() {
                return Duration::ZERO;
            }
        }

        let frame = self.pending.as_ref().expect("pending frame");
        let delay = frame.pts_us - now;
        let rate = self.shared.clock.rate().max(0.05);
        let real_delay = (delay as f64 / rate) as i64;
        let lead = ((frame.duration_us as f64 / rate) * 1.5).clamp(PRESENT_LEAD_MIN_US, PRESENT_LEAD_MAX_US) as i64;
        let mut real_delay = real_delay;
        self.pure_present = false;
        if self.smooth > 0 && self.smooth < 3 && self.last.is_some() {
            if let Some((now_ns, vsync_ns, period_ns)) = self.presenter.smooth_timing() {
                let frame_ns = frame.duration_us as f64 / rate * 1_000.0;
                let ratio = frame_ns / period_ns as f64;
                let uneven = ratio > 1.05 && (ratio - ratio.round()).abs() > 0.02;
                if uneven && self.smooth == 2 {
                    let steps = ((now_ns + period_ns / 2 - vsync_ns) as f64 / period_ns as f64).ceil() as i64;
                    let target_ns = vsync_ns + steps * period_ns;
                    let target_delay = (target_ns - now_ns) / 1_000;
                    let media_at_target = now + (target_delay as f64 * rate) as i64;
                    if media_at_target < frame.pts_us {
                        if target_ns == self.last_blend_vsync {
                            return Duration::from_micros((period_ns / 2_000).max(1_000) as u64);
                        }
                        if let (Some(previous), Some(next)) = (self.last.take(), self.pending.take()) {
                            let span = (next.pts_us - previous.pts_us).max(1) as f64;
                            let weight = ((media_at_target - previous.pts_us) as f64 / span).clamp(0.0, 1.0);
                            let started = Instant::now();
                            self.presenter.present_blend(&previous, &next, weight as f32, target_delay.max(0));
                            self.record_render_time(started);
                            self.last = Some(previous);
                            self.pending = Some(next);
                            self.last_blend_vsync = target_ns;
                            self.blends_in_window += 1;
                        }
                        self.update_state(PlayerState::Playing);
                        self.update_position(now);
                        return Duration::from_micros((period_ns / 2_000).max(1_000) as u64);
                    }
                    self.last_blend_vsync = target_ns;
                    real_delay = target_delay;
                    self.pure_present = true;
                } else if uneven {
                    let start_ns = now_ns + real_delay * 1_000;
                    let steps = ((start_ns - vsync_ns) as f64 / period_ns as f64).floor() as i64;
                    let vsync_start = vsync_ns + steps * period_ns;
                    let weight = (vsync_start + period_ns - start_ns) as f64 / period_ns as f64;
                    if !self.blend_shown && weight > 0.03 && weight < 0.97 {
                        let blend_delay = (vsync_start - now_ns) / 1_000;
                        if blend_delay > lead + 1_000 {
                            self.update_state(PlayerState::Playing);
                            self.update_position(now);
                            return Duration::from_micros((blend_delay - lead).clamp(0, 20_000) as u64);
                        }
                        if let (Some(previous), Some(next)) = (self.last.take(), self.pending.take()) {
                            let started = Instant::now();
                            self.presenter.present_blend(&previous, &next, weight as f32, blend_delay.max(0));
                            self.record_render_time(started);
                            self.last = Some(previous);
                            self.pending = Some(next);
                            self.blend_shown = true;
                            self.blends_in_window += 1;
                        }
                        return Duration::ZERO;
                    }
                    let pure_ns = if self.blend_shown { vsync_start + period_ns } else { start_ns };
                    real_delay = (pure_ns - now_ns) / 1_000;
                    self.pure_present = true;
                }
            }
        }
        if real_delay > lead + 1_000 {
            self.update_state(PlayerState::Playing);
            self.update_position(now);
            return Duration::from_micros((real_delay - lead).clamp(0, 20_000) as u64);
        }

        let late = -delay;
        if late > frame.duration_us.max(20_000) {
            if let Some(next) = self.shared.video_frames.front_pts(self.serial) {
                if next <= now {
                    self.pending = None;
                    self.blend_shown = false;
                    self.shared.counters.dropped.fetch_add(1, Ordering::Relaxed);
                    return Duration::ZERO;
                }
            }
        }

        let frame = self.pending.take().expect("pending frame");
        self.show_at(frame, real_delay.max(0));
        self.update_state(PlayerState::Playing);
        Duration::ZERO
    }

    fn step_audio_only(&mut self) -> Duration {
        self.awaiting_first = false;
        if self.shared.audio_finished_for(self.serial) || self.ended || self.audio_tail_expired() {
            if !self.ended {
                self.finish_audio_only();
            }
            return Duration::from_millis(50);
        }
        match self.shared.clock.now_us() {
            Some(now) => {
                if !self.paused {
                    if let Some(start_us) = self.loop_due(now) {
                        self.loop_to(start_us);
                        return Duration::from_millis(10);
                    }
                }
                self.update_position(now);
                self.update_state(if self.paused { PlayerState::Paused } else { PlayerState::Playing });
            }
            None => self.update_state(if self.paused { PlayerState::Paused } else { PlayerState::Buffering }),
        }
        Duration::from_millis(30)
    }

    fn run(mut self, commands: Receiver<RenderCommand<P::Window>>) {
        let mut wait = Duration::ZERO;
        loop {
            let command = if wait.is_zero() {
                commands.try_recv().map_err(|e| match e {
                    std::sync::mpsc::TryRecvError::Empty => RecvTimeoutError::Timeout,
                    std::sync::mpsc::TryRecvError::Disconnected => RecvTimeoutError::Disconnected,
                })
            } else {
                commands.recv_timeout(wait)
            };
            match command {
                Ok(command) => {
                    if !self.handle(command) {
                        break;
                    }
                    wait = Duration::ZERO;
                    continue;
                }
                Err(RecvTimeoutError::Disconnected) => break,
                Err(RecvTimeoutError::Timeout) => {}
            }
            if self.shared.aborted() {
                break;
            }
            let stepped = self.step();
            wait = self.cap_wait_for_loop(stepped);
            if wait == Duration::from_millis(5) {
                self.shared.video_frames.wait_readable(wait);
                wait = Duration::ZERO;
            }
        }
        self.presenter.set_window(None);
    }
}

pub fn run_renderer<P: Presenter>(shared: Arc<Shared>, presenter: P, commands: Receiver<RenderCommand<P::Window>>) {
    let paused = shared.paused.load(Ordering::Acquire);
    let serial = shared.current_serial();
    RenderLoop {
        shared,
        presenter,
        serial,
        pending: None,
        last: None,
        awaiting_first: true,
        end_of_stream: false,
        ended: false,
        paused,
        stepped: false,
        clock_wait: None,
        tail: None,
        presenter_generation: 0,
        output_mode: 0,
        render_window: Instant::now(),
        render_total_us: 0,
        render_peak_us: 0,
        render_count: 0,
        frame_interval_us: 41_700.0,
        quality_cap: f32::INFINITY,
        unit_cost_us: 0.0,
        learned_applied: false,
        strained_windows: 0,
        relaxed_windows: 0,
        upgrade_after: 10,
        dropped_seen: 0,
        warmup_windows: 3,
        active_scaling: ActiveScaling::default(),
        smooth: 0,
        blend_shown: false,
        last_blend_vsync: 0,
        pure_present: false,
        blends_in_window: 0,
        motion: MotionSettings::default(),
        motion_level: 0,
        motion_shrink: 0,
        motion_strained: 0,
        motion_relaxed: 0,
        motion_upgrade_after: 10,
        motion_config: MotionConfig::default(),
        duplicate_streak: 0,
        interpolated_in_window: 0,
        output_interval_ns: 0,
        phase_anchor: (0, 0),
    }
    .run(commands);
}

#[cfg(test)]
mod tests {
    use super::*;

    const STEP: i64 = 250_000;

    #[test]
    fn a_frozen_clock_is_reported_as_still() {
        let start = Instant::now();
        let mut watch = TailWatch::new(Some(10_000_000), start);
        assert_eq!(watch.observe(Some(10_000_000), STEP, start + Duration::from_secs(1)), Duration::from_secs(1));
        assert_eq!(watch.observe(Some(10_004_000), STEP, start + Duration::from_secs(3)), Duration::from_secs(3));
    }

    #[test]
    fn jitter_below_the_step_does_not_reset_the_watch() {
        let start = Instant::now();
        let mut watch = TailWatch::new(Some(0), start);
        let still = watch.observe(Some(STEP - 1), STEP, start + Duration::from_secs(4));
        assert!(still >= TAIL_STALL);
    }

    #[test]
    fn a_moving_clock_restarts_the_watch() {
        let start = Instant::now();
        let mut watch = TailWatch::new(Some(0), start);
        let at = start + Duration::from_secs(2);
        assert_eq!(watch.observe(Some(STEP), STEP, at), Duration::ZERO);
        assert_eq!(watch.observe(Some(STEP), STEP, at + Duration::from_secs(1)), Duration::from_secs(1));
    }

    #[test]
    fn a_missing_clock_counts_as_still_until_it_appears() {
        let start = Instant::now();
        let mut watch = TailWatch::new(None, start);
        assert_eq!(watch.observe(None, STEP, start + Duration::from_secs(3)), Duration::from_secs(3));
        let at = start + Duration::from_secs(4);
        assert_eq!(watch.observe(Some(0), STEP, at), Duration::ZERO);
        assert_eq!(watch.observe(Some(0), STEP, at + Duration::from_secs(1)), Duration::from_secs(1));
    }
}
