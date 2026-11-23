use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::thread;
use std::time::{Duration, Instant};

use lucky_ffmpeg::{ChannelLayout, DecodeStatus, Decoder, Frame, Resampler, sys};

use crate::effects::AudioProcessor;
use crate::passthrough::EncodedCodec;
use crate::queue::PacketItem;
use crate::shared::Shared;

pub const OUTPUT_AUTO: u8 = 1;

pub trait AudioOutput: Send + 'static {
    fn open(&mut self, sample_rate: u32, channels: u32) -> bool;
    fn open_encoded(&mut self, _sample_rate: u32, _channels: u32, _codec: EncodedCodec) -> bool {
        false
    }
    fn write(&mut self, samples: &[f32]) -> Option<usize>;
    fn write_encoded(&mut self, _bytes: &[u8]) -> Option<usize> {
        None
    }
    fn played_frames(&mut self) -> Option<i64>;
    fn play(&mut self);
    fn pause(&mut self);
    fn flush(&mut self) -> bool;
    fn close(&mut self);
    fn max_channels(&mut self) -> u32 {
        2
    }
    fn supports_encoded(&mut self, _sample_rate: u32, _channels: u32, _codec: EncodedCodec) -> bool {
        false
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AudioPlan {
    Pcm(ChannelLayout),
    Encoded(EncodedCodec),
}

#[derive(Debug, Clone, Copy)]
pub struct StreamAudio {
    pub codec: Option<EncodedCodec>,
    pub sample_rate: u32,
    pub channels: u32,
    pub layout: ChannelLayout,
}

impl StreamAudio {
    pub fn from_parameters(par: &sys::AVCodecParameters) -> Self {
        Self {
            codec: EncodedCodec::classify(par),
            sample_rate: par.sample_rate.max(0) as u32,
            channels: par.ch_layout.nb_channels.max(0) as u32,
            layout: ChannelLayout::of_parameters(par),
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct Limits {
    pub blocked_codecs: u8,
    pub max_channels: usize,
}

impl Default for Limits {
    fn default() -> Self {
        Self { blocked_codecs: 0, max_channels: usize::MAX }
    }
}

pub fn output_kind(layout: ChannelLayout) -> &'static str {
    if layout == ChannelLayout::STEREO {
        "stereo"
    } else if layout == ChannelLayout::MONO {
        "mono"
    } else {
        "multichannel"
    }
}

fn passthrough_codec(shared: &Shared, output: &mut dyn AudioOutput, stream: &StreamAudio, limits: Limits) -> Option<EncodedCodec> {
    let enabled = shared.passthrough_codecs.load(Ordering::Acquire);
    if enabled == 0 {
        return None;
    }
    let codec = stream.codec?;
    if enabled & codec.bit() == 0 || limits.blocked_codecs & codec.bit() != 0 {
        return None;
    }
    if (shared.clock.rate() - 1.0).abs() > 1e-6 {
        return None;
    }
    if shared.audio_effects.lock().unwrap_or_else(|e| e.into_inner()).shapes_sound() {
        return None;
    }
    if !(32_000..=192_000).contains(&stream.sample_rate) {
        return None;
    }
    output.supports_encoded(stream.sample_rate, stream.channels.max(1), codec).then_some(codec)
}

fn pcm_layout(shared: &Shared, output: &mut dyn AudioOutput, source: ChannelLayout, limits: Limits) -> ChannelLayout {
    if shared.audio_output_mode.load(Ordering::Acquire) != OUTPUT_AUTO || source == ChannelLayout::STEREO {
        return ChannelLayout::STEREO;
    }
    let capable = (output.max_channels() as usize).min(limits.max_channels);
    if capable <= 2 {
        return ChannelLayout::STEREO;
    }
    source.fit(capable)
}

pub fn choose_plan(shared: &Shared, output: &mut dyn AudioOutput, stream: &StreamAudio, limits: Limits) -> AudioPlan {
    if let Some(codec) = passthrough_codec(shared, output, stream, limits) {
        return AudioPlan::Encoded(codec);
    }
    AudioPlan::Pcm(pcm_layout(shared, output, stream.layout, limits))
}

pub fn rescale_us(value: i64, time_base: sys::AVRational) -> i64 {
    if time_base.den == 0 {
        return 0;
    }
    (value as i128 * time_base.num as i128 * 1_000_000 / time_base.den as i128) as i64
}

pub struct AudioParams {
    pub start_us: i64,
    pub time_base: sys::AVRational,
    pub sample_rate: u32,
    pub layout: ChannelLayout,
    pub has_video: bool,
    pub stream: StreamAudio,
    pub plan: AudioPlan,
    pub limits: Limits,
    pub track_gain_db: Option<f32>,
}

struct AudioLoop {
    shared: Arc<Shared>,
    stop: Arc<std::sync::atomic::AtomicBool>,
    decoder: Decoder,
    resampler: Resampler,
    output: Box<dyn AudioOutput>,
    params: AudioParams,
    serial: u64,
    decode_serial: u64,
    pending: Vec<f32>,
    offset: usize,
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
    effects: AudioProcessor,
    effects_version: u64,
    plan_version: u64,
    restart_requested: bool,
}

pub const START_GATE: Duration = Duration::from_millis(1500);
pub const ALIGN_MIN_US: i64 = 40_000;
pub const ALIGN_MAX_US: i64 = 10_000_000;

impl AudioLoop {
    fn rate(&self) -> i64 {
        self.params.sample_rate as i64
    }

    fn channels(&self) -> usize {
        self.params.layout.channels()
    }

    fn rescale(&self, value: i64) -> i64 {
        rescale_us(value, self.params.time_base)
    }

    fn reset_output(&mut self) {
        if self.playing {
            self.output.pause();
        }
        self.playing = false;
        self.pending.clear();
        self.offset = 0;
        self.effects.reset();
        self.base_pts_us = None;
        self.frames_written = 0;
        self.input_finished = false;
        self.finished = false;
        self.next_pts_us = None;
        self.gate_started = None;
        self.flush_pending = Some(Instant::now());
        self.aligned = false;
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

    fn append(&mut self, frame: &Frame) {
        let pts_us = frame.timestamp().map(|ts| self.rescale(ts) - self.params.start_us).or(self.next_pts_us);
        let start = self.pending.len();
        let converted = match self.resampler.convert(frame, &mut self.pending) {
            Ok(count) => count,
            Err(error) => {
                log::warn!("audio conversion failed: {error}");
                return;
            }
        };
        if converted == 0 {
            return;
        }
        let duration_us = converted as i64 * 1_000_000 / self.rate();
        let mut first_pts = pts_us.unwrap_or(0);
        let end_pts = first_pts + duration_us;
        if let Some(target) = self.skip_until_us {
            if end_pts <= target {
                self.pending.truncate(start);
                self.next_pts_us = Some(end_pts);
                return;
            }
            if first_pts < target {
                let skip_frames = ((target - first_pts) * self.rate() / 1_000_000) as usize;
                let skip_samples = (skip_frames * self.channels()).min(self.pending.len() - start);
                self.pending.drain(start..start + skip_samples);
                first_pts += skip_frames as i64 * 1_000_000 / self.rate();
            }
            self.skip_until_us = None;
        }
        self.effects.process(&mut self.pending[start..]);
        if self.base_pts_us.is_none() {
            let queued_frames = (start - self.offset.min(start)) / self.channels();
            self.base_pts_us = Some(first_pts - (self.frames_written + queued_frames as i64) * 1_000_000 / self.rate());
        }
        self.next_pts_us = Some(end_pts);
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
        let channels = self.channels();
        let frames = (gap.abs() * self.rate() / 1_000_000) as usize;
        let shift_us = frames as i64 * 1_000_000 / self.rate();
        if gap > 0 {
            let mut padded = Vec::with_capacity(self.pending.len() + frames * channels);
            padded.extend_from_slice(&self.pending[..self.offset]);
            padded.resize(self.offset + frames * channels, 0.0);
            padded.extend_from_slice(&self.pending[self.offset..]);
            self.pending = padded;
            self.base_pts_us = Some(base - shift_us);
        } else {
            let available = (self.pending.len() - self.offset) / channels;
            if frames < available {
                self.pending.drain(self.offset..self.offset + frames * channels);
                self.base_pts_us = Some(base + shift_us);
            } else {
                self.pending.truncate(self.offset);
                self.base_pts_us = None;
                self.skip_until_us = Some(video_start);
            }
        }
        true
    }

    fn receive_all(&mut self) {
        loop {
            match self.decoder.receive() {
                Ok(DecodeStatus::Frame(frame)) => {
                    self.shared.counters.audio_frames.fetch_add(1, Ordering::Relaxed);
                    self.append(&frame);
                }
                Ok(_) => return,
                Err(error) => {
                    log::warn!("audio decode error: {error}");
                    return;
                }
            }
        }
    }

    fn decode(&mut self, packet: Option<&lucky_ffmpeg::Packet>) {
        for _ in 0..64 {
            match self.decoder.send(packet) {
                Ok(true) => break,
                Ok(false) => self.receive_all(),
                Err(error) => {
                    log::warn!("audio packet rejected: {error}");
                    break;
                }
            }
        }
        self.receive_all();
    }

    fn fill(&mut self) {
        if self.offset > 0 && self.offset >= self.pending.len() {
            self.pending.clear();
            self.offset = 0;
        }
        while self.pending.len() - self.offset < self.channels() * 2048 && !self.input_finished {
            if self.shared.current_serial() != self.serial {
                return;
            }
            let Some(item) = self.shared.audio_packets.pop(Duration::from_millis(5)) else {
                return;
            };
            match item {
                PacketItem::Flush { serial, target_us } => {
                    self.decoder.flush();
                    self.resampler.reset();
                    self.decode_serial = serial;
                    self.skip_until_us = target_us;
                }
                PacketItem::Packet { packet, serial } => {
                    if serial == self.decode_serial && serial == self.serial {
                        self.decode(Some(&packet));
                    }
                }
                PacketItem::End { serial } => {
                    if serial == self.decode_serial && serial == self.serial {
                        self.decode(None);
                        self.decoder.flush();
                        self.input_finished = true;
                        self.shared.mark_audio_input_finished(self.serial);
                    }
                }
            }
        }
    }

    fn write(&mut self) -> bool {
        if self.offset >= self.pending.len() || !self.flush_if_settled() || !self.align_start() {
            return false;
        }
        let channels = self.channels();
        let available = (self.pending.len() - self.offset) / channels * channels;
        if available == 0 {
            return false;
        }
        match self.output.write(&self.pending[self.offset..self.offset + available]) {
            Some(written) => {
                let written = written / channels * channels;
                self.offset += written;
                self.frames_written += (written / channels) as i64;
                written > 0
            }
            None => {
                if let Some(base) = self.base_pts_us {
                    self.base_pts_us = Some(base + self.frames_written * 1_000_000 / self.rate());
                }
                self.frames_written = 0;
                self.playing = false;
                if !self.output.flush() {
                    thread::sleep(Duration::from_millis(200));
                }
                false
            }
        }
    }

    fn may_start(&mut self) -> bool {
        let buffered = self.frames_written >= self.rate() / 5 || (self.input_finished && self.offset >= self.pending.len());
        if !buffered || self.frames_written == 0 {
            return false;
        }
        if !self.params.has_video || self.shared.video_ready_serial.load(Ordering::Acquire) == self.serial {
            return true;
        }
        let started = *self.gate_started.get_or_insert_with(Instant::now);
        started.elapsed() > START_GATE
    }

    fn update_clock(&mut self) {
        let Some(base) = self.base_pts_us else {
            return;
        };
        let Some(played) = self.output.played_frames() else {
            return;
        };
        let played = played.clamp(0, self.frames_written);
        let position = base + played * 1_000_000 / self.rate() + self.effects.delay_us();
        if self.shared.current_serial() == self.serial {
            if !self.shared.audio_master.swap(true, Ordering::AcqRel) {
                log::info!("audio output is running again, following the audio clock");
            }
            self.shared.clock.set(position, !self.shared.paused.load(Ordering::Acquire));
        }
        if self.input_finished && self.offset >= self.pending.len() && played >= self.frames_written - self.rate() / 50 {
            self.finished = true;
            self.shared.mark_audio_finished(self.serial);
        }
    }

    fn reconsider(&mut self) {
        if self.restart_requested {
            return;
        }
        let wanted = choose_plan(&self.shared, self.output.as_mut(), &self.params.stream, self.params.limits);
        if wanted != self.params.plan {
            self.restart_requested = true;
            self.shared.restart_audio();
        }
    }

    fn run(mut self) -> Box<dyn AudioOutput> {
        while !self.shared.aborted() && !self.stop.load(Ordering::Acquire) {
            let version = self.shared.effects_version.load(Ordering::Acquire);
            if version != self.effects_version {
                self.effects_version = version;
                let settings = *self.shared.audio_effects.lock().unwrap_or_else(|e| e.into_inner());
                self.effects.configure(settings);
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
                self.fill();
                self.write();
                thread::sleep(Duration::from_millis(10));
                continue;
            }
            self.fill();
            let wrote = self.write();
            if !self.playing && self.flush_pending.is_none() && self.may_start() {
                self.output.play();
                self.playing = true;
            }
            if self.playing && !self.finished {
                self.update_clock();
            }
            if !self.finished && self.input_finished && self.offset >= self.pending.len() && self.frames_written == 0 {
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

pub fn open_output(output: &mut dyn AudioOutput, decoder: &Decoder, layout: ChannelLayout) -> Option<(u32, ChannelLayout)> {
    let context = unsafe { &*decoder.as_ptr() };
    let source_rate = context.sample_rate;
    let rate = if (8_000..=192_000).contains(&source_rate) { source_rate as u32 } else { 48_000 };
    let mut candidates = vec![layout];
    if layout != ChannelLayout::STEREO {
        candidates.push(ChannelLayout::STEREO);
    }
    for candidate in candidates {
        let channels = candidate.channels() as u32;
        if output.open(rate, channels) {
            return Some((rate, candidate));
        }
        if rate != 48_000 && output.open(48_000, channels) {
            return Some((48_000, candidate));
        }
    }
    None
}

pub fn run_audio(
    shared: Arc<Shared>,
    stop: Arc<std::sync::atomic::AtomicBool>,
    decoder: Decoder,
    output: Box<dyn AudioOutput>,
    params: AudioParams,
) -> Box<dyn AudioOutput> {
    let serial = shared.current_serial();
    let resampler = Resampler::new(params.sample_rate as i32, params.layout);
    let mut effects = AudioProcessor::new(params.sample_rate, params.layout);
    effects.configure(*shared.audio_effects.lock().unwrap_or_else(|e| e.into_inner()));
    effects.set_track_gain(params.track_gain_db);
    let effects_version = shared.effects_version.load(Ordering::Acquire);
    let plan_version = shared.audio_plan_version.load(Ordering::Acquire);
    AudioLoop {
        shared,
        stop,
        decoder,
        resampler,
        output,
        params,
        serial,
        decode_serial: serial,
        pending: Vec::new(),
        offset: 0,
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
        effects,
        effects_version,
        plan_version,
        restart_requested: false,
    }
    .run()
}
