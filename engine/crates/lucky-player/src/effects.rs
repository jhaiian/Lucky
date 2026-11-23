use std::f32::consts::PI;

use lucky_ffmpeg::{ChannelLayout, channel};

pub const TARGET_LUFS: f32 = -16.0;
const REPLAYGAIN_REFERENCE_LUFS: f32 = -18.0;
const R128_REFERENCE_LUFS: f32 = -23.0;
const MAX_BOOST_DB: f32 = 12.0;
const MAX_CUT_DB: f32 = 15.0;
const GATE_LUFS: f32 = -50.0;
const RELATIVE_GATE: f32 = 0.1;
const BLOCK_SECONDS: f32 = 0.1;
const WINDOW_BLOCKS: usize = 30;
const MIN_BLOCKS: usize = 4;
const BOOST_SECONDS: f32 = 3.0;
const CUT_SECONDS: f32 = 1.0;
const GAIN_REFRESH_FRAMES: usize = 32;

pub const EQ_BANDS: usize = 10;
pub const EQ_FREQUENCIES: [f32; EQ_BANDS] = [31.0, 62.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0];

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct AudioEffects {
    pub delay_ms: i32,
    pub gain: f32,
    pub night: bool,
    pub dialogue: bool,
    pub channels: u8,
    pub balance: f32,
    pub normalize: bool,
    pub eq: [f32; EQ_BANDS],
}

impl Default for AudioEffects {
    fn default() -> Self {
        Self { delay_ms: 0, gain: 1.0, night: false, dialogue: false, channels: 0, balance: 0.0, normalize: false, eq: [0.0; EQ_BANDS] }
    }
}

impl AudioEffects {
    pub fn sanitized(self) -> Self {
        let mut eq = self.eq;
        for band in eq.iter_mut() {
            *band = if band.is_finite() { band.clamp(-12.0, 12.0) } else { 0.0 };
        }
        Self {
            delay_ms: self.delay_ms.clamp(-5_000, 5_000),
            gain: if self.gain.is_finite() { self.gain.clamp(0.0, 3.0) } else { 1.0 },
            night: self.night,
            dialogue: self.dialogue,
            channels: self.channels.min(2),
            balance: if self.balance.is_finite() { self.balance.clamp(-1.0, 1.0) } else { 0.0 },
            normalize: self.normalize,
            eq,
        }
    }

    pub fn shapes_sound(&self) -> bool {
        self.gain != 1.0
            || self.night
            || self.dialogue
            || self.channels != 0
            || self.balance != 0.0
            || self.normalize
            || self.eq.iter().any(|g| *g != 0.0)
    }
}

fn leading_number(value: &str) -> Option<f32> {
    let trimmed = value.trim().replace(',', ".");
    let end = trimmed.find(|c: char| !(c.is_ascii_digit() || matches!(c, '+' | '-' | '.'))).unwrap_or(trimmed.len());
    trimmed[..end].parse::<f32>().ok().filter(|number| number.is_finite() && number.abs() <= 60.0)
}

pub fn track_gain_from_tags(replay_gain: Option<&str>, r128: Option<&str>) -> Option<f32> {
    if let Some(gain) = replay_gain.and_then(leading_number) {
        return Some(gain + TARGET_LUFS - REPLAYGAIN_REFERENCE_LUFS);
    }
    let fixed_point = r128?.trim().parse::<f32>().ok().filter(|value| value.is_finite())?;
    let gain = fixed_point / 256.0;
    (gain.abs() <= 60.0).then_some(gain + TARGET_LUFS - R128_REFERENCE_LUFS)
}

#[derive(Clone, Copy, Default)]
struct Biquad {
    b0: f32,
    b1: f32,
    b2: f32,
    a1: f32,
    a2: f32,
}

impl Biquad {
    fn peaking(rate: f32, frequency: f32, q: f32, gain_db: f32) -> Self {
        let a = 10f32.powf(gain_db / 40.0);
        let w = 2.0 * PI * (frequency / rate).min(0.49);
        let alpha = w.sin() / (2.0 * q);
        let cos = w.cos();
        let a0 = 1.0 + alpha / a;
        Self {
            b0: (1.0 + alpha * a) / a0,
            b1: -2.0 * cos / a0,
            b2: (1.0 - alpha * a) / a0,
            a1: -2.0 * cos / a0,
            a2: (1.0 - alpha / a) / a0,
        }
    }

    fn shelf(rate: f32, frequency: f32, gain_db: f32, high: bool) -> Self {
        let a = 10f32.powf(gain_db / 40.0);
        let w = 2.0 * PI * (frequency / rate).min(0.49);
        let cos = w.cos();
        let alpha = w.sin() / 2.0 * (2.0f32).sqrt();
        let root = 2.0 * a.sqrt() * alpha;
        if high {
            let a0 = (a + 1.0) - (a - 1.0) * cos + root;
            Self {
                b0: a * ((a + 1.0) + (a - 1.0) * cos + root) / a0,
                b1: -2.0 * a * ((a - 1.0) + (a + 1.0) * cos) / a0,
                b2: a * ((a + 1.0) + (a - 1.0) * cos - root) / a0,
                a1: 2.0 * ((a - 1.0) - (a + 1.0) * cos) / a0,
                a2: ((a + 1.0) - (a - 1.0) * cos - root) / a0,
            }
        } else {
            let a0 = (a + 1.0) + (a - 1.0) * cos + root;
            Self {
                b0: a * ((a + 1.0) - (a - 1.0) * cos + root) / a0,
                b1: 2.0 * a * ((a - 1.0) - (a + 1.0) * cos) / a0,
                b2: a * ((a + 1.0) - (a - 1.0) * cos - root) / a0,
                a1: -2.0 * ((a - 1.0) + (a + 1.0) * cos) / a0,
                a2: ((a + 1.0) + (a - 1.0) * cos - root) / a0,
            }
        }
    }
}

impl Biquad {
    fn k_shelf(rate: f32) -> Self {
        let k = (std::f64::consts::PI * 1_681.974_450_955_533 / rate as f64).tan();
        let q = 0.707_175_236_955_419_6;
        let vh = 10f64.powf(3.999_843_853_973_347 / 20.0);
        let vb = vh.powf(0.499_666_774_154_541_6);
        let a0 = 1.0 + k / q + k * k;
        Self {
            b0: ((vh + vb * k / q + k * k) / a0) as f32,
            b1: (2.0 * (k * k - vh) / a0) as f32,
            b2: ((vh - vb * k / q + k * k) / a0) as f32,
            a1: (2.0 * (k * k - 1.0) / a0) as f32,
            a2: ((1.0 - k / q + k * k) / a0) as f32,
        }
    }

    fn k_high_pass(rate: f32) -> Self {
        let k = (std::f64::consts::PI * 38.135_470_876_024_44 / rate as f64).tan();
        let q = 0.500_327_037_323_877_3;
        let a0 = 1.0 + k / q + k * k;
        Self { b0: 1.0, b1: -2.0, b2: 1.0, a1: (2.0 * (k * k - 1.0) / a0) as f32, a2: ((1.0 - k / q + k * k) / a0) as f32 }
    }
}

#[derive(Clone, Copy, Default)]
struct Memory {
    x1: f32,
    x2: f32,
    y1: f32,
    y2: f32,
}

impl Memory {
    fn run(&mut self, f: &Biquad, x: f32) -> f32 {
        let y = f.b0 * x + f.b1 * self.x1 + f.b2 * self.x2 - f.a1 * self.y1 - f.a2 * self.y2;
        self.x2 = self.x1;
        self.x1 = x;
        self.y2 = self.y1;
        self.y1 = if y.abs() < 1e-20 { 0.0 } else { y };
        self.y1
    }
}

struct Normalizer {
    enabled: bool,
    weights: Vec<f32>,
    shelf: Biquad,
    high_pass: Biquad,
    shelf_memory: Vec<Memory>,
    high_pass_memory: Vec<Memory>,
    block_frames: usize,
    block_count: usize,
    block_energy: f64,
    history: [f32; WINDOW_BLOCKS],
    filled: usize,
    head: usize,
    gate_energy: f32,
    track_gain: Option<f32>,
    gain_db: f32,
    target_db: f32,
    boost: f32,
    cut: f32,
    linear: f32,
    refresh: usize,
}

impl Normalizer {
    fn new(rate: f32, weights: Vec<f32>) -> Self {
        let channels = weights.len();
        Self {
            enabled: false,
            weights,
            shelf: Biquad::k_shelf(rate),
            high_pass: Biquad::k_high_pass(rate),
            shelf_memory: vec![Memory::default(); channels],
            high_pass_memory: vec![Memory::default(); channels],
            block_frames: ((rate * BLOCK_SECONDS) as usize).max(1),
            block_count: 0,
            block_energy: 0.0,
            history: [0.0; WINDOW_BLOCKS],
            filled: 0,
            head: 0,
            gate_energy: 10f32.powf((GATE_LUFS + 0.691) / 10.0),
            track_gain: None,
            gain_db: 0.0,
            target_db: 0.0,
            boost: 1.0 - (-1.0 / (BOOST_SECONDS * rate)).exp(),
            cut: 1.0 - (-1.0 / (CUT_SECONDS * rate)).exp(),
            linear: 1.0,
            refresh: 0,
        }
    }

    fn set_enabled(&mut self, enabled: bool) {
        if enabled == self.enabled {
            return;
        }
        self.enabled = enabled;
        self.reset();
        self.gain_db = self.track_gain.map(|gain| gain.clamp(-MAX_CUT_DB, MAX_BOOST_DB)).unwrap_or(0.0);
        self.target_db = self.gain_db;
        self.linear = 10f32.powf(self.gain_db / 20.0);
    }

    fn set_track_gain(&mut self, gain: Option<f32>) {
        self.track_gain = gain.filter(|value| value.is_finite());
        if let Some(gain) = self.track_gain {
            self.target_db = gain.clamp(-MAX_CUT_DB, MAX_BOOST_DB);
            self.gain_db = self.target_db;
            self.linear = 10f32.powf(self.gain_db / 20.0);
        }
    }

    fn reset(&mut self) {
        self.block_count = 0;
        self.block_energy = 0.0;
        self.filled = 0;
        self.head = 0;
        self.history = [0.0; WINDOW_BLOCKS];
        self.shelf_memory.iter_mut().for_each(|memory| *memory = Memory::default());
        self.high_pass_memory.iter_mut().for_each(|memory| *memory = Memory::default());
    }

    fn finish_block(&mut self) {
        let mean = (self.block_energy / self.block_count.max(1) as f64) as f32;
        self.block_energy = 0.0;
        self.block_count = 0;
        self.history[self.head] = mean;
        self.head = (self.head + 1) % WINDOW_BLOCKS;
        self.filled = (self.filled + 1).min(WINDOW_BLOCKS);
        let window = &self.history[..self.filled];
        let gated = |floor: f32| {
            window.iter().filter(|energy| **energy >= floor).fold((0f32, 0usize), |(sum, count), energy| (sum + energy, count + 1))
        };
        let (sum, count) = gated(self.gate_energy);
        if count < MIN_BLOCKS {
            return;
        }
        let (sum, count) = gated(self.gate_energy.max(sum / count as f32 * RELATIVE_GATE));
        let loudness = -0.691 + 10.0 * (sum / count.max(1) as f32).max(1e-12).log10();
        self.target_db = (TARGET_LUFS - loudness).clamp(-MAX_CUT_DB, MAX_BOOST_DB);
    }

    fn step(&mut self, frame: &[f32]) -> f32 {
        if self.track_gain.is_none() {
            let mut energy = 0f32;
            for (channel, sample) in frame.iter().enumerate() {
                let weight = self.weights[channel];
                if weight == 0.0 {
                    continue;
                }
                let shelved = self.shelf_memory[channel].run(&self.shelf, *sample);
                let filtered = self.high_pass_memory[channel].run(&self.high_pass, shelved);
                energy += weight * filtered * filtered;
            }
            self.block_energy += energy as f64;
            self.block_count += 1;
            if self.block_count >= self.block_frames {
                self.finish_block();
            }
            let delta = self.target_db - self.gain_db;
            self.gain_db += delta * if delta > 0.0 { self.boost } else { self.cut };
        }
        self.refresh += 1;
        if self.refresh >= GAIN_REFRESH_FRAMES {
            self.refresh = 0;
            self.linear = 10f32.powf(self.gain_db / 20.0);
        }
        self.linear
    }
}

pub struct AudioProcessor {
    rate: f32,
    channels: usize,
    settings: AudioEffects,
    active: bool,
    bands: Vec<(Biquad, Vec<Memory>)>,
    voice: Biquad,
    voice_memory: Memory,
    envelope: f32,
    attack: f32,
    release: f32,
    pairs: Vec<(usize, usize)>,
    center: Option<usize>,
    lfe: Option<usize>,
    gains: Vec<f32>,
    normalizer: Normalizer,
}

impl AudioProcessor {
    pub fn new(rate: u32, layout: ChannelLayout) -> Self {
        let rate = rate.max(8_000) as f32;
        let pairs = [
            (channel::FRONT_LEFT, channel::FRONT_RIGHT),
            (channel::BACK_LEFT, channel::BACK_RIGHT),
            (channel::SIDE_LEFT, channel::SIDE_RIGHT),
        ]
        .into_iter()
        .filter_map(|(left, right)| Some((layout.index_of(left)?, layout.index_of(right)?)))
        .collect::<Vec<(usize, usize)>>();
        let mut weights = vec![1.0f32; layout.channels()];
        for surround in [channel::BACK_LEFT, channel::BACK_RIGHT, channel::SIDE_LEFT, channel::SIDE_RIGHT] {
            if let Some(index) = layout.index_of(surround) {
                weights[index] = 1.41;
            }
        }
        if let Some(lfe) = layout.index_of(channel::LOW_FREQUENCY) {
            weights[lfe] = 0.0;
        }
        Self {
            rate,
            channels: layout.channels(),
            settings: AudioEffects::default(),
            active: false,
            bands: Vec::new(),
            voice: Biquad::peaking(rate, 2_500.0, 0.8, 5.0),
            voice_memory: Memory::default(),
            envelope: 0.0,
            attack: (-1.0 / (0.005 * rate)).exp(),
            release: (-1.0 / (0.3 * rate)).exp(),
            pairs,
            center: layout.index_of(channel::FRONT_CENTER),
            lfe: layout.index_of(channel::LOW_FREQUENCY),
            gains: vec![1.0; layout.channels()],
            normalizer: Normalizer::new(rate, weights),
        }
    }

    pub fn set_track_gain(&mut self, gain: Option<f32>) {
        self.normalizer.set_track_gain(gain);
    }

    pub fn reset(&mut self) {
        self.normalizer.reset();
    }

    pub fn configure(&mut self, settings: AudioEffects) {
        let settings = settings.sanitized();
        if settings == self.settings {
            return;
        }
        let eq_changed = settings.eq != self.settings.eq;
        self.settings = settings;
        self.normalizer.set_enabled(settings.normalize);
        self.active = settings.shapes_sound();
        self.gains = self.channel_gains();
        if eq_changed {
            let channels = self.channels;
            self.bands = EQ_FREQUENCIES
                .iter()
                .zip(settings.eq.iter())
                .enumerate()
                .filter(|(_, (_, gain))| **gain != 0.0)
                .map(|(index, (frequency, gain))| {
                    let filter = match index {
                        0 => Biquad::shelf(self.rate, *frequency * 1.4, *gain, false),
                        i if i == EQ_BANDS - 1 => Biquad::shelf(self.rate, *frequency * 0.7, *gain, true),
                        _ => Biquad::peaking(self.rate, *frequency, 1.41, *gain),
                    };
                    (filter, vec![Memory::default(); channels])
                })
                .collect();
        }
    }

    fn channel_gains(&self) -> Vec<f32> {
        let settings = self.settings;
        let mut gains = vec![settings.gain; self.channels];
        let left = settings.gain * (1.0 - settings.balance).min(1.0);
        let right = settings.gain * (1.0 + settings.balance).min(1.0);
        for (left_index, right_index) in &self.pairs {
            gains[*left_index] = left;
            gains[*right_index] = right;
        }
        gains
    }

    pub fn delay_us(&self) -> i64 {
        self.settings.delay_ms as i64 * 1_000
    }

    pub fn process(&mut self, samples: &mut [f32]) {
        if !self.active {
            return;
        }
        if self.channels == 2 {
            self.process_stereo(samples);
        } else {
            self.process_surround(samples);
        }
    }

    fn process_stereo(&mut self, samples: &mut [f32]) {
        let settings = self.settings;
        let left_gain = settings.gain * (1.0 - settings.balance).min(1.0);
        let right_gain = settings.gain * (1.0 + settings.balance).min(1.0);
        let threshold = 0.0316f32;
        let normalize = settings.normalize;
        for frame in samples.chunks_exact_mut(2) {
            let level = if normalize { self.normalizer.step(frame) } else { 1.0 };
            let (mut left, mut right) = (frame[0] * level, frame[1] * level);
            match settings.channels {
                1 => {
                    let mono = (left + right) * 0.5;
                    left = mono;
                    right = mono;
                }
                2 => std::mem::swap(&mut left, &mut right),
                _ => {}
            }
            for (filter, memory) in self.bands.iter_mut() {
                left = memory[0].run(filter, left);
                right = memory[1].run(filter, right);
            }
            if settings.dialogue {
                let mid = (left + right) * 0.5;
                let side = (left - right) * 0.5 * 0.7;
                let voiced = self.voice_memory.run(&self.voice, mid);
                left = voiced + side;
                right = voiced - side;
            }
            if settings.night {
                let level = left.abs().max(right.abs());
                let coefficient = if level > self.envelope { self.attack } else { self.release };
                self.envelope = coefficient * self.envelope + (1.0 - coefficient) * level;
                let mut gain = 2.8;
                if self.envelope > threshold {
                    let over = 20.0 * (self.envelope / threshold).log10();
                    gain *= 10f32.powf(-(over * 0.75) / 20.0);
                }
                left *= gain;
                right *= gain;
            }
            frame[0] = limit(left * left_gain);
            frame[1] = limit(right * right_gain);
        }
    }

    fn process_surround(&mut self, samples: &mut [f32]) {
        let settings = self.settings;
        let channels = self.channels;
        let lfe = self.lfe;
        let threshold = 0.0316f32;
        let normalize = settings.normalize;
        for frame in samples.chunks_exact_mut(channels) {
            if normalize {
                let level = self.normalizer.step(frame);
                for value in frame.iter_mut() {
                    *value *= level;
                }
            }
            for (filter, memory) in self.bands.iter_mut() {
                for (value, state) in frame.iter_mut().zip(memory.iter_mut()) {
                    *value = state.run(filter, *value);
                }
            }
            if settings.dialogue {
                if let Some(center) = self.center {
                    frame[center] = self.voice_memory.run(&self.voice, frame[center]);
                } else if let Some((left, right)) = self.pairs.first().copied() {
                    let mid = (frame[left] + frame[right]) * 0.5;
                    let side = (frame[left] - frame[right]) * 0.5 * 0.7;
                    let voiced = self.voice_memory.run(&self.voice, mid);
                    frame[left] = voiced + side;
                    frame[right] = voiced - side;
                }
            }
            if settings.night {
                let mut level = 0f32;
                for (index, value) in frame.iter().enumerate() {
                    if Some(index) != lfe {
                        level = level.max(value.abs());
                    }
                }
                let coefficient = if level > self.envelope { self.attack } else { self.release };
                self.envelope = coefficient * self.envelope + (1.0 - coefficient) * level;
                let mut gain = 2.8;
                if self.envelope > threshold {
                    let over = 20.0 * (self.envelope / threshold).log10();
                    gain *= 10f32.powf(-(over * 0.75) / 20.0);
                }
                for value in frame.iter_mut() {
                    *value *= gain;
                }
            }
            for (value, gain) in frame.iter_mut().zip(self.gains.iter()) {
                *value = limit(*value * *gain);
            }
        }
    }
}

fn limit(sample: f32) -> f32 {
    let magnitude = sample.abs();
    if magnitude <= 0.9 {
        return sample;
    }
    let shaped = 0.9 + 0.1 * ((magnitude - 0.9) / 0.1).tanh();
    shaped.copysign(sample)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tone(rate: f32, frequency: f32, frames: usize, level: f32) -> Vec<f32> {
        (0..frames)
            .flat_map(|i| {
                let v = level * (2.0 * PI * frequency * i as f32 / rate).sin();
                [v, v]
            })
            .collect()
    }

    fn peak(samples: &[f32]) -> f32 {
        samples.iter().skip(samples.len() / 2).fold(0.0f32, |m, v| m.max(v.abs()))
    }

    #[test]
    fn flat_settings_leave_sound_untouched() {
        let mut processor = AudioProcessor::new(48_000, ChannelLayout::STEREO);
        processor.configure(AudioEffects::default());
        let original = tone(48_000.0, 440.0, 4_800, 0.5);
        let mut samples = original.clone();
        processor.process(&mut samples);
        assert_eq!(samples, original);
    }

    #[test]
    fn equalizer_boosts_only_its_band() {
        let mut processor = AudioProcessor::new(48_000, ChannelLayout::STEREO);
        let mut eq = [0.0; EQ_BANDS];
        eq[5] = 12.0;
        processor.configure(AudioEffects { eq, ..AudioEffects::default() });
        let mut on = tone(48_000.0, 1_000.0, 9_600, 0.1);
        processor.process(&mut on);
        assert!(peak(&on) > 0.3, "1 kHz should rise by about 12 dB, got {}", peak(&on));
        let mut processor = AudioProcessor::new(48_000, ChannelLayout::STEREO);
        processor.configure(AudioEffects { eq, ..AudioEffects::default() });
        let mut off = tone(48_000.0, 60.0, 9_600, 0.1);
        processor.process(&mut off);
        assert!(peak(&off) < 0.12, "60 Hz should stay about the same, got {}", peak(&off));
    }

    #[test]
    fn boost_never_clips() {
        let mut processor = AudioProcessor::new(48_000, ChannelLayout::STEREO);
        processor.configure(AudioEffects { gain: 3.0, ..AudioEffects::default() });
        let mut samples = tone(48_000.0, 440.0, 4_800, 0.9);
        processor.process(&mut samples);
        assert!(samples.iter().all(|v| v.abs() <= 1.0));
        assert!(peak(&samples) > 0.95);
    }

    #[test]
    fn night_mode_lifts_quiet_and_tames_loud() {
        let mut quiet = AudioProcessor::new(48_000, ChannelLayout::STEREO);
        quiet.configure(AudioEffects { night: true, ..AudioEffects::default() });
        let mut soft = tone(48_000.0, 440.0, 48_000, 0.01);
        quiet.process(&mut soft);
        assert!(peak(&soft) > 0.02);
        let mut loud = AudioProcessor::new(48_000, ChannelLayout::STEREO);
        loud.configure(AudioEffects { night: true, ..AudioEffects::default() });
        let mut strong = tone(48_000.0, 440.0, 48_000, 0.8);
        loud.process(&mut strong);
        assert!(peak(&strong) < 0.5);
    }

    #[test]
    fn channel_modes() {
        let mut processor = AudioProcessor::new(48_000, ChannelLayout::STEREO);
        processor.configure(AudioEffects { channels: 2, ..AudioEffects::default() });
        let mut samples = vec![0.1, 0.4, 0.2, 0.3];
        processor.process(&mut samples);
        assert_eq!(samples, vec![0.4, 0.1, 0.3, 0.2]);
        processor.configure(AudioEffects { channels: 1, ..AudioEffects::default() });
        let mut samples = vec![0.2, 0.4];
        processor.process(&mut samples);
        assert!((samples[0] - 0.3).abs() < 1e-6 && (samples[1] - 0.3).abs() < 1e-6);
    }

    fn tone_on(channels: usize, channel: usize, frequency: f32, frames: usize, level: f32) -> Vec<f32> {
        let mut samples = vec![0.0; frames * channels];
        for i in 0..frames {
            samples[i * channels + channel] = level * (2.0 * PI * frequency * i as f32 / 48_000.0).sin();
        }
        samples
    }

    fn channel_peak(samples: &[f32], channels: usize, channel: usize) -> f32 {
        samples
            .chunks_exact(channels)
            .skip(samples.len() / channels / 2)
            .fold(0.0f32, |m, frame| m.max(frame[channel].abs()))
    }

    #[test]
    fn gain_and_equalizer_reach_every_surround_channel() {
        let mut processor = AudioProcessor::new(48_000, ChannelLayout::SURROUND_5_1);
        processor.configure(AudioEffects { gain: 0.5, ..AudioEffects::default() });
        let mut samples = vec![0.4; 6 * 8];
        processor.process(&mut samples);
        assert!(samples.iter().all(|v| (v - 0.2).abs() < 1e-6));
        let mut eq = [0.0; EQ_BANDS];
        eq[5] = 12.0;
        let mut processor = AudioProcessor::new(48_000, ChannelLayout::SURROUND_5_1);
        processor.configure(AudioEffects { eq, ..AudioEffects::default() });
        let mut rear = tone_on(6, 4, 1_000.0, 9_600, 0.1);
        processor.process(&mut rear);
        assert!(channel_peak(&rear, 6, 4) > 0.3, "rear left should be boosted too");
        assert_eq!(channel_peak(&rear, 6, 0), 0.0);
    }

    #[test]
    fn balance_only_moves_left_right_pairs() {
        let mut processor = AudioProcessor::new(48_000, ChannelLayout::SURROUND_5_1);
        processor.configure(AudioEffects { balance: 1.0, ..AudioEffects::default() });
        let mut samples = vec![0.5; 6];
        processor.process(&mut samples);
        assert_eq!(samples, vec![0.0, 0.5, 0.5, 0.5, 0.0, 0.5]);
    }

    #[test]
    fn dialogue_boost_uses_the_center_channel() {
        let mut processor = AudioProcessor::new(48_000, ChannelLayout::SURROUND_5_1);
        processor.configure(AudioEffects { dialogue: true, ..AudioEffects::default() });
        let mut center = tone_on(6, 2, 2_500.0, 9_600, 0.1);
        processor.process(&mut center);
        assert!(channel_peak(&center, 6, 2) > 0.15, "center should rise, got {}", channel_peak(&center, 6, 2));
        let mut processor = AudioProcessor::new(48_000, ChannelLayout::SURROUND_5_1);
        processor.configure(AudioEffects { dialogue: true, ..AudioEffects::default() });
        let mut front = tone_on(6, 0, 2_500.0, 9_600, 0.1);
        processor.process(&mut front);
        assert!((channel_peak(&front, 6, 0) - 0.1).abs() < 2e-3);
    }

    fn settled_peak(effects: AudioEffects, track_gain: Option<f32>, level: f32, seconds: usize) -> f32 {
        let mut processor = AudioProcessor::new(48_000, ChannelLayout::STEREO);
        processor.configure(effects);
        processor.set_track_gain(track_gain);
        let mut last = Vec::new();
        for _ in 0..seconds {
            let mut samples = tone(48_000.0, 997.0, 48_000, level);
            processor.process(&mut samples);
            last = samples;
        }
        peak(&last)
    }

    fn normalizing() -> AudioEffects {
        AudioEffects { normalize: true, ..AudioEffects::default() }
    }

    #[test]
    fn a_loud_signal_is_turned_down_toward_the_target() {
        let result = settled_peak(normalizing(), None, 0.5, 40);
        assert!((result - 0.158).abs() < 0.012, "expected about 0.158, got {result}");
    }

    #[test]
    fn a_quiet_signal_is_boosted_by_at_most_twelve_decibels() {
        let result = settled_peak(normalizing(), None, 0.01, 40);
        assert!(result > 0.036 && result < 0.042, "expected about 0.0398, got {result}");
    }

    #[test]
    fn a_signal_at_the_target_is_left_alone() {
        let result = settled_peak(normalizing(), None, 0.158, 40);
        assert!((result - 0.158).abs() < 0.012, "expected about 0.158, got {result}");
    }

    #[test]
    fn silence_does_not_pump_the_gain_up() {
        let mut processor = AudioProcessor::new(48_000, ChannelLayout::STEREO);
        processor.configure(normalizing());
        for _ in 0..30 {
            processor.process(&mut tone(48_000.0, 997.0, 48_000, 0.5));
        }
        for _ in 0..20 {
            processor.process(&mut vec![0.0; 96_000]);
        }
        let mut again = tone(48_000.0, 997.0, 24_000, 0.5);
        processor.process(&mut again);
        assert!(peak(&again) < 0.25, "the gain should have been held, got {}", peak(&again));
    }

    #[test]
    fn a_track_gain_from_the_tags_replaces_the_measurement() {
        let result = settled_peak(normalizing(), Some(-6.0206), 0.1, 2);
        assert!((result - 0.05).abs() < 0.002, "expected about 0.05, got {result}");
    }

    #[test]
    fn normalization_off_leaves_the_sound_untouched() {
        let mut processor = AudioProcessor::new(48_000, ChannelLayout::STEREO);
        processor.configure(AudioEffects::default());
        processor.set_track_gain(Some(-12.0));
        let original = tone(48_000.0, 440.0, 4_800, 0.5);
        let mut samples = original.clone();
        processor.process(&mut samples);
        assert_eq!(samples, original);
    }

    #[test]
    fn tag_gains_are_parsed_and_moved_to_the_target() {
        let close = |value: Option<f32>, expected: f32| value.is_some_and(|value| (value - expected).abs() < 1e-4);
        assert!(close(track_gain_from_tags(Some("-6.54 dB"), None), -4.54));
        assert!(close(track_gain_from_tags(Some("+3,20 dB"), Some("0")), 5.2));
        assert!(close(track_gain_from_tags(None, Some("-512")), 5.0));
        assert_eq!(track_gain_from_tags(Some("loud"), None), None);
        assert_eq!(track_gain_from_tags(None, None), None);
    }

    #[test]
    fn channel_modes_leave_surround_alone() {
        let mut processor = AudioProcessor::new(48_000, ChannelLayout::SURROUND_5_1);
        processor.configure(AudioEffects { channels: 2, ..AudioEffects::default() });
        let original = vec![0.1, 0.2, 0.3, 0.4, 0.5, 0.6];
        let mut samples = original.clone();
        processor.process(&mut samples);
        assert_eq!(samples, original);
        processor.configure(AudioEffects { channels: 1, ..AudioEffects::default() });
        processor.process(&mut samples);
        assert_eq!(samples, original);
    }
}
