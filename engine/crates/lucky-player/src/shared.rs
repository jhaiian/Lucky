use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU8, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};

use lucky_ass::SubtitleStyle;
use lucky_ffmpeg::Attachment;

use crate::ass::AssSlot;
use crate::clock::Clock;
use crate::external::ExternalSubtitle;
use crate::queue::{FrameQueue, PacketQueue};
use crate::screenshot::{Screenshot, ScreenshotPoll};
use crate::status::{PlayerState, Status};
use crate::subtitles::SubtitleStore;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrackRequest {
    Audio(Option<usize>),
    Subtitle(Option<usize>),
    ReloadSubtitle,
    RestartAudio,
}

#[derive(Debug, Clone, Copy)]
pub struct SeekRequest {
    pub target_us: i64,
    pub serial: u64,
    pub exact: bool,
}

const VIDEO_PACKET_BYTES: usize = 48 * 1024 * 1024;
const VIDEO_PACKET_ITEMS: usize = 1500;
const AUDIO_PACKET_BYTES: usize = 8 * 1024 * 1024;
const AUDIO_PACKET_ITEMS: usize = 3000;
const PRELOAD_VIDEO_BYTES: usize = 6 * 1024 * 1024;
const PRELOAD_VIDEO_ITEMS: usize = 180;
const PRELOAD_AUDIO_BYTES: usize = 1024 * 1024;
const PRELOAD_AUDIO_ITEMS: usize = 600;
const MAX_QUEUED_STEPS: u32 = 8;
const NO_PTS: i64 = i64::MIN;
const NO_LOOP: i64 = -1;

#[derive(Default)]
struct ScreenshotSlot {
    ticket: u64,
    result: Option<Option<Screenshot>>,
}

#[derive(Default)]
pub struct Counters {
    pub audio_frames: AtomicU64,
    pub decoded: AtomicU64,
    pub presented: AtomicU64,
    pub dropped: AtomicU64,
}

pub struct Shared {
    pub abort: AtomicBool,
    pub interrupt: Arc<AtomicBool>,
    pub paused: AtomicBool,
    pub serial: AtomicU64,
    pub seek_pending: AtomicBool,
    pub last_seek_ms: std::sync::atomic::AtomicI64,
    pub seek: Mutex<Option<SeekRequest>>,
    pub video_packets: PacketQueue,
    pub audio_packets: PacketQueue,
    pub video_frames: FrameQueue,
    pub audio_master: AtomicBool,
    audio_finished_serial: AtomicU64,
    audio_input_finished_serial: AtomicU64,
    pub video_ready_serial: AtomicU64,
    preload: AtomicBool,
    hardware_deferred: AtomicBool,
    play_after_swap: AtomicBool,
    pub video_start_us: AtomicI64,
    pub steps: AtomicU32,
    loop_start_us: AtomicI64,
    loop_end_us: AtomicI64,
    shown_serial: AtomicU64,
    shown_pts_us: AtomicI64,
    shown_duration_us: AtomicI64,
    shown_previous_us: AtomicI64,
    pub has_video: AtomicBool,
    pub auto_quality: AtomicBool,
    pub fast_decode: AtomicBool,
    pub software_fallback: AtomicBool,
    pub deinterlace: AtomicU8,
    pub learned_cost: AtomicU32,
    pub audio_effects: Mutex<crate::effects::AudioEffects>,
    pub effects_version: AtomicU64,
    pub audio_output_mode: AtomicU8,
    pub passthrough_codecs: AtomicU8,
    pub audio_plan_version: AtomicU64,
    pub audio_ready: AtomicBool,
    pub track_requests: Mutex<Vec<TrackRequest>>,
    pub subtitles: SubtitleStore,
    pub ass: Mutex<AssSlot>,
    pub subtitle_style: Mutex<SubtitleStyle>,
    pub text_libass: AtomicBool,
    pub fonts: Mutex<Vec<Attachment>>,
    pub externals: Mutex<Vec<ExternalSubtitle>>,
    pub clock: Clock,
    pub counters: Counters,
    screenshot: Mutex<ScreenshotSlot>,
    status: Mutex<Status>,
}

impl Shared {
    pub fn new(autoplay: bool) -> Self {
        Self {
            abort: AtomicBool::new(false),
            interrupt: Arc::new(AtomicBool::new(false)),
            paused: AtomicBool::new(!autoplay),
            serial: AtomicU64::new(0),
            seek_pending: AtomicBool::new(false),
            last_seek_ms: std::sync::atomic::AtomicI64::new(0),
            seek: Mutex::new(None),
            video_packets: PacketQueue::new(VIDEO_PACKET_BYTES, VIDEO_PACKET_ITEMS),
            audio_packets: PacketQueue::new(AUDIO_PACKET_BYTES, AUDIO_PACKET_ITEMS),
            video_frames: FrameQueue::new(4),
            audio_master: AtomicBool::new(false),
            audio_finished_serial: AtomicU64::new(u64::MAX),
            audio_input_finished_serial: AtomicU64::new(u64::MAX),
            video_ready_serial: AtomicU64::new(u64::MAX),
            preload: AtomicBool::new(false),
            hardware_deferred: AtomicBool::new(false),
            play_after_swap: AtomicBool::new(false),
            video_start_us: AtomicI64::new(0),
            steps: AtomicU32::new(0),
            loop_start_us: AtomicI64::new(0),
            loop_end_us: AtomicI64::new(NO_LOOP),
            shown_serial: AtomicU64::new(u64::MAX),
            shown_pts_us: AtomicI64::new(NO_PTS),
            shown_duration_us: AtomicI64::new(0),
            shown_previous_us: AtomicI64::new(NO_PTS),
            has_video: AtomicBool::new(true),
            auto_quality: AtomicBool::new(true),
            fast_decode: AtomicBool::new(true),
            software_fallback: AtomicBool::new(true),
            deinterlace: AtomicU8::new(1),
            learned_cost: AtomicU32::new(0f32.to_bits()),
            audio_effects: Mutex::new(crate::effects::AudioEffects::default()),
            effects_version: AtomicU64::new(0),
            audio_output_mode: AtomicU8::new(crate::audio::OUTPUT_AUTO),
            passthrough_codecs: AtomicU8::new(0),
            audio_plan_version: AtomicU64::new(0),
            audio_ready: AtomicBool::new(false),
            track_requests: Mutex::new(Vec::new()),
            subtitles: SubtitleStore::default(),
            ass: Mutex::new(AssSlot::default()),
            subtitle_style: Mutex::new(SubtitleStyle::default()),
            text_libass: AtomicBool::new(true),
            fonts: Mutex::new(Vec::new()),
            externals: Mutex::new(Vec::new()),
            clock: Clock::new(),
            counters: Counters::default(),
            screenshot: Mutex::new(ScreenshotSlot::default()),
            status: Mutex::new(Status { learned_cost: -1.0, ..Status::default() }),
        }
    }

    pub fn status(&self) -> MutexGuard<'_, Status> {
        self.status.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn aborted(&self) -> bool {
        self.abort.load(Ordering::Acquire)
    }

    pub fn current_serial(&self) -> u64 {
        self.serial.load(Ordering::Acquire)
    }

    pub fn mark_audio_finished(&self, serial: u64) {
        self.audio_finished_serial.store(serial, Ordering::Release);
    }

    pub fn clear_audio_finished(&self) {
        self.audio_finished_serial.store(u64::MAX, Ordering::Release);
        self.audio_input_finished_serial.store(u64::MAX, Ordering::Release);
    }

    pub fn audio_finished_for(&self, serial: u64) -> bool {
        self.audio_finished_serial.load(Ordering::Acquire) == serial
    }

    pub fn mark_audio_input_finished(&self, serial: u64) {
        self.audio_input_finished_serial.store(serial, Ordering::Release);
    }

    pub fn audio_input_finished_for(&self, serial: u64) -> bool {
        self.audio_input_finished_serial.load(Ordering::Acquire) == serial
    }

    pub fn begin_preload(&self) {
        self.preload.store(true, Ordering::SeqCst);
        self.video_packets.set_limits(PRELOAD_VIDEO_BYTES, PRELOAD_VIDEO_ITEMS);
        self.audio_packets.set_limits(PRELOAD_AUDIO_BYTES, PRELOAD_AUDIO_ITEMS);
    }

    pub fn end_preload(&self) {
        if self.preload.swap(false, Ordering::SeqCst) {
            self.video_packets.set_limits(VIDEO_PACKET_BYTES, VIDEO_PACKET_ITEMS);
            self.audio_packets.set_limits(AUDIO_PACKET_BYTES, AUDIO_PACKET_ITEMS);
        }
    }

    pub fn defer_hardware(&self) -> bool {
        if !self.preload.load(Ordering::SeqCst) {
            return false;
        }
        self.hardware_deferred.store(true, Ordering::SeqCst);
        true
    }

    pub fn hardware_requested(&self) -> bool {
        self.hardware_deferred.load(Ordering::SeqCst) && !self.preload.load(Ordering::SeqCst)
    }

    pub fn hold_play_for_hardware(&self) -> bool {
        if !self.hardware_deferred.load(Ordering::SeqCst) {
            return false;
        }
        self.play_after_swap.store(true, Ordering::SeqCst);
        if self.hardware_deferred.load(Ordering::SeqCst) {
            return true;
        }
        self.play_after_swap.store(false, Ordering::SeqCst);
        false
    }

    pub fn cancel_play_hold(&self) {
        self.play_after_swap.store(false, Ordering::SeqCst);
    }

    pub fn finish_hardware_swap(&self) {
        self.hardware_deferred.store(false, Ordering::SeqCst);
        if self.play_after_swap.swap(false, Ordering::SeqCst) {
            self.paused.store(false, Ordering::Release);
        }
    }

    pub fn fail(&self, message: String) {
        log::error!("{message}");
        let mut status = self.status();
        status.state = PlayerState::Error;
        status.error = Some(message);
    }

    pub fn request_seek(&self, position_ms: i64, exact: bool) -> u64 {
        let target_us = position_ms.max(0).saturating_mul(1000);
        let serial = self.serial.fetch_add(1, Ordering::AcqRel) + 1;
        *self.seek.lock().unwrap_or_else(|e| e.into_inner()) = Some(SeekRequest { target_us, serial, exact });
        self.last_seek_ms.store(position_ms.max(0), Ordering::Release);
        self.seek_pending.store(true, Ordering::Release);
        self.steps.store(0, Ordering::Release);
        self.video_frames.clear();
        self.clock.invalidate();
        self.subtitles.reset();
        let mut status = self.status();
        status.position_ms = position_ms.max(0);
        if status.state == PlayerState::Ended {
            status.state = if self.paused.load(Ordering::Acquire) { PlayerState::Paused } else { PlayerState::Buffering };
        }
        serial
    }

    pub fn record_shown(&self, serial: u64, pts_us: i64, duration_us: i64, previous_us: Option<i64>) {
        self.shown_previous_us.store(previous_us.unwrap_or(NO_PTS), Ordering::Release);
        self.shown_duration_us.store(duration_us, Ordering::Release);
        self.shown_pts_us.store(pts_us, Ordering::Release);
        self.shown_serial.store(serial, Ordering::Release);
    }

    pub fn begin_screenshot(&self) -> u64 {
        let mut slot = self.screenshot.lock().unwrap_or_else(|e| e.into_inner());
        slot.ticket += 1;
        slot.result = None;
        slot.ticket
    }

    pub fn finish_screenshot(&self, ticket: u64, shot: Option<Screenshot>) {
        let mut slot = self.screenshot.lock().unwrap_or_else(|e| e.into_inner());
        if slot.ticket == ticket {
            slot.result = Some(shot);
        }
    }

    pub fn poll_screenshot(&self, ticket: u64) -> ScreenshotPoll {
        let mut slot = self.screenshot.lock().unwrap_or_else(|e| e.into_inner());
        if slot.ticket != ticket {
            return ScreenshotPoll::Done(None);
        }
        match slot.result.take() {
            Some(shot) => ScreenshotPoll::Done(shot),
            None => ScreenshotPoll::Pending,
        }
    }

    pub fn set_loop(&self, start_us: i64, end_us: i64) {
        self.loop_end_us.store(NO_LOOP, Ordering::Release);
        self.loop_start_us.store(start_us, Ordering::Release);
        self.loop_end_us.store(end_us, Ordering::Release);
    }

    pub fn clear_loop(&self) {
        self.loop_end_us.store(NO_LOOP, Ordering::Release);
    }

    pub fn loop_range(&self) -> Option<(i64, i64)> {
        let end_us = self.loop_end_us.load(Ordering::Acquire);
        if end_us <= 0 {
            return None;
        }
        let start_us = self.loop_start_us.load(Ordering::Acquire);
        (end_us > start_us).then_some((start_us, end_us))
    }

    pub fn queue_step(&self) {
        let mut queued = self.steps.load(Ordering::Acquire);
        loop {
            let next = (queued + 1).min(MAX_QUEUED_STEPS);
            match self.steps.compare_exchange_weak(queued, next, Ordering::AcqRel, Ordering::Acquire) {
                Ok(_) => return,
                Err(actual) => queued = actual,
            }
        }
    }

    pub fn take_step(&self) -> bool {
        let mut queued = self.steps.load(Ordering::Acquire);
        loop {
            let Some(next) = queued.checked_sub(1) else {
                return false;
            };
            match self.steps.compare_exchange_weak(queued, next, Ordering::AcqRel, Ordering::Acquire) {
                Ok(_) => return true,
                Err(actual) => queued = actual,
            }
        }
    }

    pub fn step_back_target_ms(&self) -> Option<i64> {
        let shown_duration_us = self.shown_duration_us.load(Ordering::Acquire);
        let duration_us = if shown_duration_us > 0 {
            shown_duration_us
        } else {
            self.status().frame_rate.filter(|rate| *rate > 0.0).map(|rate| (1_000_000.0 / rate) as i64).unwrap_or(40_000)
        };
        let settled = !self.seek_pending.load(Ordering::Acquire) && self.shown_serial.load(Ordering::Acquire) == self.current_serial();
        let target_us = if !settled {
            self.last_seek_ms.load(Ordering::Acquire).saturating_mul(1000) - duration_us
        } else {
            let pts_us = self.shown_pts_us.load(Ordering::Acquire);
            if pts_us == NO_PTS {
                return None;
            }
            match self.shown_previous_us.load(Ordering::Acquire) {
                NO_PTS => pts_us - duration_us,
                previous_us => previous_us,
            }
        };
        Some(target_us.max(0) / 1000)
    }

    pub fn current_position_ms(&self) -> i64 {
        if self.seek_pending.load(Ordering::Acquire) {
            return self.last_seek_ms.load(Ordering::Acquire);
        }
        self.status().position_ms
    }

    pub fn bump_audio_plan(&self) {
        self.audio_plan_version.fetch_add(1, Ordering::AcqRel);
    }

    pub fn restart_audio(&self) {
        self.track_requests.lock().unwrap_or_else(|e| e.into_inner()).push(TrackRequest::RestartAudio);
        self.request_seek(self.current_position_ms(), true);
    }

    pub fn snapshot(&self) -> Status {
        let mut status = self.status().clone();
        status.decoded_frames = self.counters.decoded.load(Ordering::Relaxed);
        status.audio_frames = self.counters.audio_frames.load(Ordering::Relaxed);
        status.presented_frames = self.counters.presented.load(Ordering::Relaxed);
        status.dropped_frames = self.counters.dropped.load(Ordering::Relaxed);
        status
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stepping_back_lands_on_the_previous_frame_when_it_is_known() {
        let shared = Shared::new(false);
        shared.record_shown(0, 1_000_000, 40_000, Some(958_000));
        assert_eq!(shared.step_back_target_ms(), Some(958));
    }

    #[test]
    fn stepping_back_falls_back_to_the_frame_duration() {
        let shared = Shared::new(false);
        shared.record_shown(0, 1_000_000, 40_000, None);
        assert_eq!(shared.step_back_target_ms(), Some(960));
    }

    #[test]
    fn stepping_back_during_a_seek_starts_from_the_seek_target() {
        let shared = Shared::new(false);
        shared.record_shown(0, 5_000_000, 40_000, None);
        shared.request_seek(2_000, true);
        assert_eq!(shared.step_back_target_ms(), Some(1_960));
    }

    #[test]
    fn stepping_back_never_goes_before_the_start() {
        let shared = Shared::new(false);
        shared.record_shown(0, 10_000, 40_000, None);
        assert_eq!(shared.step_back_target_ms(), Some(0));
    }

    #[test]
    fn the_loop_range_is_only_reported_when_it_is_valid() {
        let shared = Shared::new(false);
        assert_eq!(shared.loop_range(), None);
        shared.set_loop(10_000_000, 25_000_000);
        assert_eq!(shared.loop_range(), Some((10_000_000, 25_000_000)));
        shared.set_loop(30_000_000, 25_000_000);
        assert_eq!(shared.loop_range(), None);
        shared.set_loop(0, 5_000_000);
        assert_eq!(shared.loop_range(), Some((0, 5_000_000)));
        shared.clear_loop();
        assert_eq!(shared.loop_range(), None);
    }

    #[test]
    fn a_screenshot_is_delivered_once_to_the_latest_request() {
        let shared = Shared::new(false);
        let first = shared.begin_screenshot();
        assert!(matches!(shared.poll_screenshot(first), ScreenshotPoll::Pending));
        let second = shared.begin_screenshot();
        shared.finish_screenshot(first, Some(Screenshot { width: 1, height: 1, rgba: vec![0; 4] }));
        assert!(matches!(shared.poll_screenshot(first), ScreenshotPoll::Done(None)));
        shared.finish_screenshot(second, Some(Screenshot { width: 1, height: 1, rgba: vec![1; 4] }));
        assert!(matches!(shared.poll_screenshot(second), ScreenshotPoll::Done(Some(_))));
        assert!(matches!(shared.poll_screenshot(second), ScreenshotPoll::Pending));
    }

    #[test]
    fn queued_steps_are_capped_and_a_seek_clears_them() {
        let shared = Shared::new(false);
        for _ in 0..20 {
            shared.queue_step();
        }
        assert_eq!(shared.steps.load(Ordering::Acquire), MAX_QUEUED_STEPS);
        assert!(shared.take_step());
        shared.request_seek(1_000, true);
        assert!(!shared.take_step());
    }
}
