use std::collections::VecDeque;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::{Duration, Instant};

use lucky_ffmpeg::{Packet, sys};

use crate::audio::{ALIGN_MAX_US, ALIGN_MIN_US, AudioOutput, AudioPlan, Limits, START_GATE, StreamAudio, choose_plan, rescale_us};
use crate::queue::PacketItem;
use crate::shared::Shared;

const RECHUNK_UNITS: usize = 16;
const POSITION_TOLERANCE_DIVISOR: i64 = 25;
const STALL_LIMIT: Duration = Duration::from_millis(1500);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EncodedCodec {
    Ac3,
    Eac3,
    Dts,
    DtsHd,
    TrueHd,
}

impl EncodedCodec {
    pub const ALL_BITS: u8 = 0x1F;

    pub fn bit(self) -> u8 {
        match self {
            Self::Ac3 => 1,
            Self::Eac3 => 2,
            Self::Dts => 4,
            Self::DtsHd => 8,
            Self::TrueHd => 16,
        }
    }

    pub fn encoding(self) -> i32 {
        match self {
            Self::Ac3 => 5,
            Self::Eac3 => 6,
            Self::Dts => 7,
            Self::DtsHd => 8,
            Self::TrueHd => 14,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Self::Ac3 => "ac3",
            Self::Eac3 => "eac3",
            Self::Dts => "dts",
            Self::DtsHd => "dtshd",
            Self::TrueHd => "truehd",
        }
    }

    pub fn classify(par: &sys::AVCodecParameters) -> Option<Self> {
        let id = par.codec_id;
        if id == sys::AVCodecID::AV_CODEC_ID_AC3 {
            Some(Self::Ac3)
        } else if id == sys::AVCodecID::AV_CODEC_ID_EAC3 {
            Some(Self::Eac3)
        } else if id == sys::AVCodecID::AV_CODEC_ID_TRUEHD {
            Some(Self::TrueHd)
        } else if id == sys::AVCodecID::AV_CODEC_ID_DTS {
            Some(if matches!(par.profile, 50 | 60 | 61 | 62) { Self::DtsHd } else { Self::Dts })
        } else {
            None
        }
    }
}

fn eac3_frames(data: &[u8]) -> Option<i64> {
    if data.len() < 5 || data[0] != 0x0B || data[1] != 0x77 {
        return None;
    }
    let blocks: i64 = if data[4] >> 6 == 3 { 6 } else { [1, 2, 3, 6][((data[4] >> 4) & 3) as usize] };
    Some(blocks * 256)
}

fn dts_frames(data: &[u8]) -> Option<i64> {
    if data.len() < 6 || data[..4] != [0x7F, 0xFE, 0x80, 0x01] {
        return None;
    }
    let blocks = (((data[4] & 0x01) as i64) << 6) | (data[5] >> 2) as i64;
    Some((blocks + 1) * 32)
}

fn truehd_frames(sample_rate: u32) -> i64 {
    if sample_rate >= 176_400 {
        160
    } else if sample_rate >= 88_200 {
        80
    } else {
        40
    }
}

fn is_truehd_major_sync(data: &[u8]) -> bool {
    data.len() >= 8 && data[4..8] == [0xF8, 0x72, 0x6F, 0xBA]
}

struct Unit {
    data: Vec<u8>,
    frames: i64,
    pts_us: Option<i64>,
}

struct Rechunker {
    data: Vec<u8>,
    count: usize,
    frames: i64,
    pts_us: Option<i64>,
    synced: bool,
}

impl Rechunker {
    fn new() -> Self {
        Self { data: Vec::new(), count: 0, frames: 0, pts_us: None, synced: false }
    }

    fn reset(&mut self) {
        self.data.clear();
        self.count = 0;
        self.frames = 0;
        self.pts_us = None;
        self.synced = false;
    }

    fn take(&mut self) -> Unit {
        let unit = Unit { data: std::mem::take(&mut self.data), frames: self.frames, pts_us: self.pts_us };
        self.count = 0;
        self.frames = 0;
        self.pts_us = None;
        unit
    }

    fn push(&mut self, packet: &[u8], pts_us: Option<i64>, frames: i64) -> Option<Unit> {
        if !self.synced {
            if !is_truehd_major_sync(packet) {
                return None;
            }
            self.synced = true;
        }
        if self.count == 0 {
            self.pts_us = pts_us;
        }
        self.data.extend_from_slice(packet);
        self.count += 1;
        self.frames += frames;
        if self.count >= RECHUNK_UNITS { Some(self.take()) } else { None }
    }

    fn finish(&mut self) -> Option<Unit> {
        if self.count > 0 { Some(self.take()) } else { None }
    }
}

pub struct EncodedParams {
    pub start_us: i64,
    pub time_base: sys::AVRational,
    pub sample_rate: u32,
    pub codec: EncodedCodec,
    pub has_video: bool,
    pub stream: StreamAudio,
    pub limits: Limits,
}

struct EncodedLoop {
    shared: Arc<Shared>,
    stop: Arc<AtomicBool>,
    output: Box<dyn AudioOutput>,
    params: EncodedParams,
    serial: u64,
    decode_serial: u64,
    queue: VecDeque<Unit>,
    front_written: usize,
    queued_frames: i64,
    rechunker: Rechunker,
    base_pts_us: Option<i64>,
    frames_written: i64,
    playing: bool,
    input_finished: bool,
    finished: bool,
    skip_until_us: Option<i64>,
    next_pts_us: Option<i64>,
    gate_started: Option<Instant>,
    flush_pending: Option<Instant>,
    aligned: bool,
    buffer_full: bool,
    lead_us: i64,
    lead_done_us: i64,
    lead_frames: i64,
    lead_last: Option<Instant>,
    delay_us: i64,
    effects_version: u64,
    plan_version: u64,
    restart_requested: bool,
    last_played: i64,
    last_progress: Instant,
}

impl EncodedLoop {
    fn rate(&self) -> i64 {
        self.params.sample_rate as i64
    }

    fn reset_output(&mut self) {
        if self.playing {
            self.output.pause();
        }
        self.playing = false;
        self.queue.clear();
        self.front_written = 0;
        self.queued_frames = 0;
        self.rechunker.reset();
        self.base_pts_us = None;
        self.frames_written = 0;
        self.input_finished = false;
        self.finished = false;
        self.next_pts_us = None;
        self.gate_started = None;
        self.flush_pending = Some(Instant::now());
        self.aligned = false;
        self.buffer_full = false;
        self.lead_us = 0;
        self.lead_done_us = 0;
        self.lead_frames = 0;
        self.lead_last = None;
        self.last_played = 0;
        self.last_progress = Instant::now();
    }

    fn flush_if_settled(&mut self) -> bool {
        let Some(since) = self.flush_pending else {
            return true;
        };
        if since.elapsed() < Duration::from_millis(80) {
            return false;
        }
        if !self.output.flush() {
            log::warn!("audio output could not be flushed");
        }
        self.flush_pending = None;
        true
    }

    fn duration_frames(&self, packet: &Packet) -> Option<i64> {
        let time_base = self.params.time_base;
        if packet.duration() <= 0 || time_base.den <= 0 {
            return None;
        }
        let frames = (packet.duration() as i128 * time_base.num as i128 * self.rate() as i128 / time_base.den as i128) as i64;
        (frames > 0).then_some(frames)
    }

    fn frames_of(&self, packet: &Packet) -> i64 {
        let data = packet.data();
        match self.params.codec {
            EncodedCodec::Ac3 => 1536,
            EncodedCodec::Eac3 => eac3_frames(data).or_else(|| self.duration_frames(packet)).unwrap_or(1536),
            EncodedCodec::Dts | EncodedCodec::DtsHd => dts_frames(data).or_else(|| self.duration_frames(packet)).unwrap_or(512),
            EncodedCodec::TrueHd => truehd_frames(self.params.sample_rate),
        }
    }

    fn append(&mut self, unit: Unit) {
        let rate = self.rate();
        let first_pts = unit.pts_us.or(self.next_pts_us).unwrap_or(0);
        let end_pts = first_pts + unit.frames * 1_000_000 / rate;
        if let Some(target) = self.skip_until_us {
            if end_pts <= target {
                self.next_pts_us = Some(end_pts);
                return;
            }
            self.skip_until_us = None;
        }
        if self.base_pts_us.is_none() {
            self.base_pts_us = Some(first_pts - (self.frames_written + self.queued_frames) * 1_000_000 / rate);
        }
        self.queued_frames += unit.frames;
        self.next_pts_us = Some(end_pts);
        self.queue.push_back(unit);
    }

    fn ingest(&mut self, packet: &Packet) {
        let data = packet.data();
        if data.is_empty() {
            return;
        }
        let time_base = self.params.time_base;
        let start_us = self.params.start_us;
        let pts_us = packet.pts().or_else(|| packet.dts()).map(|ts| rescale_us(ts, time_base) - start_us);
        let frames = self.frames_of(packet);
        self.shared.counters.audio_frames.fetch_add(1, Ordering::Relaxed);
        if self.params.codec == EncodedCodec::TrueHd {
            if let Some(unit) = self.rechunker.push(data, pts_us, frames) {
                self.append(unit);
            }
        } else {
            self.append(Unit { data: data.to_vec(), frames, pts_us });
        }
    }

    fn align_start(&mut self) -> bool {
        if self.aligned {
            return true;
        }
        if !self.params.has_video {
            self.aligned = true;
            return true;
        }
        if self.shared.video_ready_serial.load(Ordering::Acquire) != self.serial {
            let started = *self.gate_started.get_or_insert_with(Instant::now);
            if started.elapsed() < START_GATE {
                return false;
            }
            self.aligned = true;
            return true;
        }
        let Some(base) = self.base_pts_us else {
            return self.input_finished;
        };
        self.aligned = true;
        let video_start = self.shared.video_start_us.load(Ordering::Acquire);
        let gap = base - video_start;
        if gap.abs() < ALIGN_MIN_US || gap.abs() > ALIGN_MAX_US {
            return true;
        }
        if gap > 0 {
            self.lead_us = gap;
            self.lead_done_us = 0;
            self.lead_last = None;
            self.base_pts_us = Some(base - gap);
            return true;
        }
        let rate = self.rate();
        let mut dropped_us = 0i64;
        while let Some((frames, length)) = self.queue.front().map(|unit| (unit.frames, unit.frames * 1_000_000 / rate)) {
            if dropped_us + length > -gap {
                break;
            }
            dropped_us += length;
            self.queued_frames -= frames;
            self.queue.pop_front();
        }
        self.front_written = 0;
        if self.queue.is_empty() {
            self.base_pts_us = None;
            self.skip_until_us = Some(video_start);
        } else {
            self.base_pts_us = Some(base + dropped_us);
        }
        true
    }

    fn drain_rechunker(&mut self) {
        if let Some(unit) = self.rechunker.finish() {
            self.append(unit);
        }
    }

    fn fill(&mut self) {
        while self.queued_frames < self.rate() / 5 && !self.input_finished {
            if self.shared.current_serial() != self.serial {
                return;
            }
            let Some(item) = self.shared.audio_packets.pop(Duration::from_millis(5)) else {
                return;
            };
            match item {
                PacketItem::Flush { serial, target_us } => {
                    self.decode_serial = serial;
                    self.skip_until_us = target_us;
                    self.rechunker.reset();
                }
                PacketItem::Packet { packet, serial } => {
                    if serial == self.decode_serial && serial == self.serial {
                        self.ingest(&packet);
                    }
                }
                PacketItem::End { serial } => {
                    if serial == self.decode_serial && serial == self.serial {
                        self.drain_rechunker();
                        self.input_finished = true;
                        self.shared.mark_audio_input_finished(self.serial);
                    }
                }
            }
        }
    }

    fn write(&mut self) -> bool {
        if self.queue.is_empty() || !self.flush_if_settled() || !self.align_start() {
            return false;
        }
        let mut progressed = false;
        loop {
            let result = match self.queue.front() {
                Some(unit) => self.output.write_encoded(&unit.data[self.front_written..]),
                None => break,
            };
            match result {
                Some(written) => {
                    self.front_written += written;
                    if written > 0 {
                        progressed = true;
                    }
                    let complete = self.queue.front().map(|unit| self.front_written >= unit.data.len()).unwrap_or(true);
                    if !complete {
                        self.buffer_full = true;
                        break;
                    }
                    if let Some(unit) = self.queue.pop_front() {
                        self.frames_written += unit.frames;
                        self.queued_frames -= unit.frames;
                    }
                    self.front_written = 0;
                    self.buffer_full = false;
                }
                None => {
                    if let Some(base) = self.base_pts_us {
                        self.base_pts_us = Some(base + self.frames_written * 1_000_000 / self.rate());
                    }
                    self.frames_written = 0;
                    self.front_written = 0;
                    self.buffer_full = false;
                    self.playing = false;
                    if !self.output.flush() {
                        thread::sleep(Duration::from_millis(200));
                    }
                    return false;
                }
            }
        }
        progressed
    }

    fn may_start(&mut self) -> bool {
        if self.lead_us > 0 {
            return false;
        }
        let buffered =
            self.frames_written >= self.rate() / 10 || self.buffer_full || (self.input_finished && self.queue.is_empty());
        if !buffered || self.frames_written == 0 {
            return false;
        }
        if !self.params.has_video || self.shared.video_ready_serial.load(Ordering::Acquire) == self.serial {
            return true;
        }
        let started = *self.gate_started.get_or_insert_with(Instant::now);
        started.elapsed() > START_GATE
    }

    fn drive_lead(&mut self) {
        let now = Instant::now();
        if let Some(last) = self.lead_last {
            self.lead_done_us += now.duration_since(last).as_micros() as i64;
        }
        self.lead_last = Some(now);
        let Some(base) = self.base_pts_us else {
            return;
        };
        if self.lead_done_us >= self.lead_us {
            self.lead_frames = self.lead_us * self.rate() / 1_000_000;
            self.lead_us = 0;
            self.lead_last = None;
            return;
        }
        if self.shared.current_serial() == self.serial {
            if !self.shared.audio_master.swap(true, Ordering::AcqRel) {
                log::info!("audio output is running again, following the audio clock");
            }
            self.shared.clock.set(base + self.lead_done_us + self.delay_us, true);
        }
    }

    fn update_clock(&mut self) {
        let Some(base) = self.base_pts_us else {
            return;
        };
        let Some(played) = self.output.played_frames() else {
            return;
        };
        let rate = self.rate();
        let played = played.clamp(0, self.frames_written + rate / POSITION_TOLERANCE_DIVISOR);
        let position = base + (self.lead_frames + played) * 1_000_000 / rate + self.delay_us;
        if self.shared.current_serial() == self.serial {
            if !self.shared.audio_master.swap(true, Ordering::AcqRel) {
                log::info!("audio output is running again, following the audio clock");
            }
            self.shared.clock.set(position, !self.shared.paused.load(Ordering::Acquire));
        }
        if self.input_finished && self.queue.is_empty() {
            if played != self.last_played {
                self.last_played = played;
                self.last_progress = Instant::now();
            }
            if played >= self.frames_written - rate / 50 || self.last_progress.elapsed() > STALL_LIMIT {
                self.finished = true;
                self.shared.mark_audio_finished(self.serial);
            }
        } else {
            self.last_played = played;
            self.last_progress = Instant::now();
        }
    }

    fn reconsider(&mut self) {
        if self.restart_requested {
            return;
        }
        let wanted = choose_plan(&self.shared, self.output.as_mut(), &self.params.stream, self.params.limits);
        if wanted != AudioPlan::Encoded(self.params.codec) {
            self.restart_requested = true;
            self.shared.restart_audio();
        }
    }

    fn run(mut self) -> Box<dyn AudioOutput> {
        while !self.shared.aborted() && !self.stop.load(Ordering::Acquire) {
            let version = self.shared.effects_version.load(Ordering::Acquire);
            if version != self.effects_version {
                self.effects_version = version;
                self.delay_us = self.shared.audio_effects.lock().unwrap_or_else(|e| e.into_inner()).delay_ms as i64 * 1_000;
            }
            let plan_version = self.shared.audio_plan_version.load(Ordering::Acquire);
            if plan_version != self.plan_version {
                self.plan_version = plan_version;
                self.reconsider();
            }
            let serial = self.shared.current_serial();
            if serial != self.serial {
                self.serial = serial;
                self.reset_output();
            }
            if self.shared.paused.load(Ordering::Acquire) {
                if self.playing {
                    self.output.pause();
                    self.playing = false;
                }
                self.lead_last = None;
                self.last_progress = Instant::now();
                self.fill();
                self.write();
                thread::sleep(Duration::from_millis(10));
                continue;
            }
            self.fill();
            let wrote = self.write();
            if self.lead_us > 0 {
                self.drive_lead();
            }
            if !self.playing && self.flush_pending.is_none() && self.may_start() {
                self.output.play();
                self.playing = true;
            }
            if self.playing && !self.finished {
                self.update_clock();
            }
            if !self.finished && self.input_finished && self.queue.is_empty() && self.frames_written == 0 {
                self.finished = true;
                self.shared.mark_audio_finished(self.serial);
            }
            if self.finished {
                thread::sleep(Duration::from_millis(20));
            } else if !wrote {
                thread::sleep(Duration::from_millis(5));
            }
        }
        if self.playing {
            self.output.pause();
        }
        if self.shared.aborted() {
            self.output.close();
        }
        self.output
    }
}

pub fn open_encoded(output: &mut dyn AudioOutput, stream: &StreamAudio, codec: EncodedCodec) -> Option<u32> {
    if !(32_000..=192_000).contains(&stream.sample_rate) {
        return None;
    }
    output.open_encoded(stream.sample_rate, stream.channels.max(1), codec).then_some(stream.sample_rate)
}

pub fn run_encoded(
    shared: Arc<Shared>,
    stop: Arc<AtomicBool>,
    output: Box<dyn AudioOutput>,
    params: EncodedParams,
) -> Box<dyn AudioOutput> {
    let serial = shared.current_serial();
    let effects_version = shared.effects_version.load(Ordering::Acquire);
    let plan_version = shared.audio_plan_version.load(Ordering::Acquire);
    let delay_us = shared.audio_effects.lock().unwrap_or_else(|e| e.into_inner()).delay_ms as i64 * 1_000;
    EncodedLoop {
        shared,
        stop,
        output,
        params,
        serial,
        decode_serial: serial,
        queue: VecDeque::new(),
        front_written: 0,
        queued_frames: 0,
        rechunker: Rechunker::new(),
        base_pts_us: None,
        frames_written: 0,
        playing: false,
        input_finished: false,
        finished: false,
        skip_until_us: None,
        next_pts_us: None,
        gate_started: None,
        flush_pending: None,
        aligned: false,
        buffer_full: false,
        lead_us: 0,
        lead_done_us: 0,
        lead_frames: 0,
        lead_last: None,
        delay_us,
        effects_version,
        plan_version,
        restart_requested: false,
        last_played: 0,
        last_progress: Instant::now(),
    }
    .run()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frame_counts_come_from_the_stream_headers() {
        let mut eac3 = vec![0x0B, 0x77, 0x01, 0x00, 0b0001_0000];
        assert_eq!(eac3_frames(&eac3), Some(512));
        eac3[4] = 0b1100_0000;
        assert_eq!(eac3_frames(&eac3), Some(1536));
        assert_eq!(eac3_frames(&[0x00; 6]), None);
        let dts = [0x7F, 0xFE, 0x80, 0x01, 0x00, 0b0011_1100];
        assert_eq!(dts_frames(&dts), Some(16 * 32));
        assert_eq!(truehd_frames(48_000), 40);
        assert_eq!(truehd_frames(96_000), 80);
        assert_eq!(truehd_frames(192_000), 160);
    }

    #[test]
    fn truehd_units_are_regrouped_from_the_first_major_sync() {
        let mut rechunker = Rechunker::new();
        let minor = [0u8; 12];
        let major = [0, 0, 0, 0, 0xF8, 0x72, 0x6F, 0xBA, 0, 0, 0, 0];
        assert!(rechunker.push(&minor, Some(0), 40).is_none());
        assert!(rechunker.push(&major, Some(1_000), 40).is_none());
        let mut chunk = None;
        for index in 1..RECHUNK_UNITS {
            chunk = rechunker.push(&minor, Some(1_000 + index as i64 * 833), 40);
        }
        let chunk = chunk.expect("sixteen units make a chunk");
        assert_eq!(chunk.frames, 40 * RECHUNK_UNITS as i64);
        assert_eq!(chunk.pts_us, Some(1_000));
        assert_eq!(chunk.data.len(), 12 * RECHUNK_UNITS);
        assert!(rechunker.finish().is_none());
    }
}
