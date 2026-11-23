use std::fs::File;
use std::os::fd::OwnedFd;
use std::thread::sleep;
use std::time::{Duration, Instant};

use lucky_player::{AudioOutput, Player, Presenter, VideoFrame};

struct SimulatedOutput {
    rate: i64,
    channels: usize,
    capacity: i64,
    written: i64,
    played_base: i64,
    started: Option<Instant>,
}

impl SimulatedOutput {
    fn new() -> Self {
        Self { rate: 0, channels: 0, capacity: 0, written: 0, played_base: 0, started: None }
    }

    fn played(&self) -> i64 {
        let mut played = self.played_base;
        if let Some(start) = self.started {
            played += start.elapsed().as_micros() as i64 * self.rate / 1_000_000;
        }
        played.min(self.written)
    }
}

impl AudioOutput for SimulatedOutput {
    fn open(&mut self, sample_rate: u32, channels: u32) -> bool {
        self.rate = sample_rate as i64;
        self.channels = channels as usize;
        self.capacity = self.rate / 4;
        true
    }

    fn write(&mut self, samples: &[f32]) -> Option<usize> {
        let free = self.capacity - (self.written - self.played());
        let frames = ((samples.len() / self.channels) as i64).min(free.max(0));
        self.written += frames;
        Some(frames as usize * self.channels)
    }

    fn played_frames(&mut self) -> Option<i64> {
        Some(self.played())
    }

    fn play(&mut self) {
        if self.started.is_none() {
            self.played_base = self.played();
            self.started = Some(Instant::now());
        }
    }

    fn pause(&mut self) {
        self.played_base = self.played();
        self.started = None;
    }

    fn flush(&mut self) -> bool {
        self.written = 0;
        self.played_base = 0;
        self.started = None;
        true
    }

    fn close(&mut self) {}
}

struct NullPresenter {
    window: bool,
}

impl Presenter for NullPresenter {
    type Window = ();

    fn set_window(&mut self, window: Option<()>) {
        self.window = window.is_some();
    }

    fn has_window(&self) -> bool {
        self.window
    }

    fn present(&mut self, frame: &VideoFrame) -> bool {
        frame.width() > 0
    }

    fn name(&self) -> Option<String> {
        Some("null".into())
    }
}

fn report(label: &str, player: &Player<()>, start: Instant) {
    let s = player.status();
    println!(
        "{:>6}ms {label:<14} state={:?} pos={} dur={:?} decoded={} presented={} dropped={} audio={} err={:?}",
        start.elapsed().as_millis(),
        s.state,
        s.position_ms,
        s.duration_ms,
        s.decoded_frames,
        s.presented_frames,
        s.dropped_frames,
        s.audio_frames,
        s.error
    );
}

fn main() {
    let path = std::env::args().nth(1).expect("path");
    let file = File::open(&path).expect("open");
    let start = Instant::now();
    let audio: Option<Box<dyn AudioOutput>> =
        if std::env::var("NO_AUDIO").is_ok() { None } else { Some(Box::new(SimulatedOutput::new())) };
    let player: Player<()> = Player::open(OwnedFd::from(file), NullPresenter { window: false }, audio, None, true).unwrap();
    player.begin_audio();
    player.set_window(Some(()));
    for _ in 0..5 {
        sleep(Duration::from_millis(200));
        report("playing", &player, start);
    }
    player.seek(2000, true);
    report("seek 2000", &player, start);
    sleep(Duration::from_millis(100));
    report("after seek", &player, start);
    player.pause();
    sleep(Duration::from_millis(300));
    report("paused", &player, start);
    sleep(Duration::from_millis(300));
    report("still paused", &player, start);
    player.seek(500, true);
    sleep(Duration::from_millis(200));
    report("paused seek", &player, start);
    player.play();
    for _ in 0..20 {
        sleep(Duration::from_millis(250));
        report("playing", &player, start);
        if player.status().state == lucky_player::PlayerState::Ended {
            break;
        }
    }
    player.play();
    sleep(Duration::from_millis(300));
    report("replay", &player, start);
    for i in 0..50 {
        player.seek((i * 53) % 2900, false);
        sleep(Duration::from_millis(10));
    }
    report("scrubbed", &player, start);
    player.seek(1500, true);
    sleep(Duration::from_millis(400));
    report("after scrub", &player, start);
    drop(player);
    println!("dropped cleanly after {}ms", start.elapsed().as_millis());
}
