use std::fs::File;
use std::os::fd::OwnedFd;
use std::thread::sleep;
use std::time::{Duration, Instant};

use lucky_player::{AudioOutput, Player, Presenter, VideoFrame};

struct NullPresenter;

impl Presenter for NullPresenter {
    type Window = ();
    fn set_window(&mut self, _window: Option<()>) {}
    fn has_window(&self) -> bool {
        true
    }
    fn present(&mut self, _frame: &VideoFrame) -> bool {
        true
    }
    fn name(&self) -> Option<String> {
        None
    }
}

struct Output {
    rate: i64,
    channels: usize,
    written: i64,
    base: i64,
    started: Option<Instant>,
}

impl Output {
    fn played(&self) -> i64 {
        let mut played = self.base;
        if let Some(start) = self.started {
            played += start.elapsed().as_micros() as i64 * self.rate / 1_000_000;
        }
        played.min(self.written)
    }
}

impl AudioOutput for Output {
    fn open(&mut self, sample_rate: u32, channels: u32) -> bool {
        println!("  audio output opened at {sample_rate} Hz, {channels} ch");
        self.rate = sample_rate as i64;
        self.channels = channels as usize;
        self.written = 0;
        self.base = 0;
        self.started = None;
        true
    }
    fn write(&mut self, samples: &[f32]) -> Option<usize> {
        let free = self.rate / 4 - (self.written - self.played());
        let frames = ((samples.len() / self.channels) as i64).min(free.max(0));
        self.written += frames;
        Some(frames as usize * self.channels)
    }
    fn played_frames(&mut self) -> Option<i64> {
        Some(self.played())
    }
    fn play(&mut self) {
        if self.started.is_none() {
            self.base = self.played();
            self.started = Some(Instant::now());
        }
    }
    fn pause(&mut self) {
        self.base = self.played();
        self.started = None;
    }
    fn flush(&mut self) -> bool {
        self.written = 0;
        self.base = 0;
        self.started = None;
        true
    }
    fn close(&mut self) {
        println!("  audio output closed");
    }
}

fn show(label: &str, player: &Player<()>) {
    let s = player.status();
    let cues = player.subtitles_since(0);
    let texts: Vec<String> = cues.cues.iter().map(|c| format!("[{}-{:?}] {:?}", c.start_ms, c.end_ms, c.text)).collect();
    println!(
        "{label:<16} state={:?} pos={} video={} audio={:?}({:?} {:?}) sub={:?} gen={} cues={:?}",
        s.state, s.position_ms, s.has_video, s.selected_audio, s.audio_codec, s.audio_sample_rate, s.selected_subtitle, cues.generation, texts
    );
}

fn main() {
    let path = std::env::args().nth(1).expect("path");
    let output = Output { rate: 0, channels: 2, written: 0, base: 0, started: None };
    let player: Player<()> =
        Player::open(OwnedFd::from(File::open(&path).unwrap()), NullPresenter, Some(Box::new(output)), None, true).unwrap();
    player.begin_audio();
    sleep(Duration::from_millis(600));
    let s = player.status();
    println!("audio tracks: {:?}", s.audio_tracks.iter().map(|t| (t.index, t.codec.clone(), t.language.clone())).collect::<Vec<_>>());
    println!("subtitle tracks: {:?}", s.subtitle_tracks.iter().map(|t| (t.index, t.codec.clone(), t.language.clone(), t.is_default)).collect::<Vec<_>>());
    show("start", &player);
    if s.audio_tracks.len() > 1 {
        player.select_audio(Some(s.audio_tracks[1].index));
        sleep(Duration::from_millis(500));
        show("audio switched", &player);
    }
    player.select_subtitle(None);
    sleep(Duration::from_millis(300));
    show("subs off", &player);
    if let Some(track) = s.subtitle_tracks.first() {
        player.select_subtitle(Some(track.index));
        player.seek(0, true);
        sleep(Duration::from_millis(400));
        show("subs on", &player);
    }
    player.select_audio(None);
    sleep(Duration::from_millis(500));
    show("audio off", &player);
    for _ in 0..12 {
        sleep(Duration::from_millis(300));
        if player.status().state == lucky_player::PlayerState::Ended {
            break;
        }
    }
    show("end", &player);
}
