use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use lucky_ffmpeg::{CodecParameters, DecodedSubtitle, Input, Packet, SubtitleContent, SubtitleDecoder, sys};

use crate::ass;
use crate::demux::stream_to_ms;
use crate::shared::Shared;
use crate::subtitles::{SubMode, closes};

const WINDOW_US: i64 = 10_000_000;
const KEEP_MARGIN_MS: i64 = 500;
const BUDGET_SECONDS: f64 = 40.0;
const MIN_BUDGET: u64 = 32 << 20;
const MAX_BUDGET: u64 = 640 << 20;
const UNKNOWN_BUDGET: u64 = 128 << 20;
const WORKER_NICE: i32 = 10;

#[derive(Clone, Copy)]
pub struct Job {
    pub serial: u64,
    pub target_us: i64,
    pub stream: usize,
    pub mode: SubMode,
}

struct Mailbox {
    slot: Mutex<Option<Job>>,
    wake: Condvar,
    closed: AtomicBool,
}

impl Mailbox {
    fn lock(&self) -> std::sync::MutexGuard<'_, Option<Job>> {
        self.slot.lock().unwrap_or_else(|e| e.into_inner())
    }
}

pub struct Preroll {
    shared: Arc<Shared>,
    mailbox: Arc<Mailbox>,
    spare: Option<OwnedFd>,
    worker: Option<JoinHandle<()>>,
}

pub fn duplicate_fd(fd: &OwnedFd) -> Option<OwnedFd> {
    let copy = unsafe { libc::dup(fd.as_raw_fd()) };
    if copy < 0 { None } else { Some(unsafe { OwnedFd::from_raw_fd(copy) }) }
}

impl Preroll {
    pub fn new(shared: Arc<Shared>, spare: Option<OwnedFd>) -> Self {
        let mailbox = Arc::new(Mailbox { slot: Mutex::new(None), wake: Condvar::new(), closed: AtomicBool::new(false) });
        Self { shared, mailbox, spare, worker: None }
    }

    pub fn request(&mut self, job: Option<Job>) {
        let Some(job) = job.filter(|job| job.target_us > 0) else {
            self.mailbox.lock().take();
            return;
        };
        if self.worker.is_none() {
            let Some(fd) = self.spare.take() else {
                return;
            };
            let shared = self.shared.clone();
            let mailbox = self.mailbox.clone();
            match thread::Builder::new().name("lucky-preroll".into()).spawn(move || run(shared, mailbox, fd)) {
                Ok(handle) => self.worker = Some(handle),
                Err(error) => {
                    log::warn!("cannot start the subtitle preroll thread: {error}");
                    return;
                }
            }
        }
        *self.mailbox.lock() = Some(job);
        self.mailbox.wake.notify_one();
    }
}

impl Drop for Preroll {
    fn drop(&mut self) {
        {
            let mut slot = self.mailbox.lock();
            slot.take();
            self.mailbox.closed.store(true, Ordering::Release);
        }
        self.mailbox.wake.notify_all();
        if let Some(handle) = self.worker.take() {
            let _ = handle.join();
        }
    }
}

fn cancelled(shared: &Shared, mailbox: &Mailbox, serial: u64) -> bool {
    shared.aborted() || mailbox.closed.load(Ordering::Acquire) || shared.current_serial() != serial
}

fn run(shared: Arc<Shared>, mailbox: Arc<Mailbox>, fd: OwnedFd) {
    unsafe { libc::setpriority(libc::PRIO_PROCESS, 0, WORKER_NICE) };
    let mut fd = Some(fd);
    let mut input: Option<Input> = None;
    let mut decoder: Option<(usize, SubtitleDecoder)> = None;
    loop {
        let job = {
            let mut slot = mailbox.lock();
            loop {
                if mailbox.closed.load(Ordering::Acquire) {
                    return;
                }
                if let Some(job) = slot.take() {
                    break job;
                }
                slot = mailbox.wake.wait(slot).unwrap_or_else(|e| e.into_inner());
            }
        };
        if cancelled(&shared, &mailbox, job.serial) {
            continue;
        }
        if input.is_none() {
            let Some(source) = fd.take() else {
                return;
            };
            match Input::open_fd_with_interrupt(source, shared.interrupt.clone()) {
                Ok(opened) => input = Some(opened),
                Err(error) => {
                    if !shared.aborted() {
                        log::warn!("cannot open the subtitle preroll source: {error}");
                    }
                    return;
                }
            }
            if cancelled(&shared, &mailbox, job.serial) {
                continue;
            }
        }
        if let Some(input) = input.as_mut() {
            pass(&shared, &mailbox, input, &mut decoder, job);
        }
    }
}

fn read_budget(input: &Input) -> u64 {
    match (input.file_size(), input.duration_us()) {
        (Some(size), Some(duration)) if size > 0 && duration > 0 => {
            let per_second = size as f64 * 1_000_000.0 / duration as f64;
            ((per_second * BUDGET_SECONDS) as u64).clamp(MIN_BUDGET, MAX_BUDGET)
        }
        _ => UNKNOWN_BUDGET,
    }
}

fn pass(shared: &Arc<Shared>, mailbox: &Arc<Mailbox>, input: &mut Input, cache: &mut Option<(usize, SubtitleDecoder)>, job: Job) {
    let Some(stream_ptr) = input.stream(job.stream) else {
        return;
    };
    let stream = unsafe { &*stream_ptr };
    if stream.codecpar.is_null() || unsafe { (*stream.codecpar).codec_type } != sys::AVMediaType::AVMEDIA_TYPE_SUBTITLE {
        return;
    }
    let time_base = stream.time_base;
    if job.mode != SubMode::Ass {
        if cache.as_ref().is_none_or(|(index, _)| *index != job.stream) {
            match CodecParameters::copy_from(stream.codecpar).and_then(|params| SubtitleDecoder::open(&params, time_base)) {
                Ok(opened) => *cache = Some((job.stream, opened)),
                Err(error) => {
                    log::warn!("subtitle preroll decoder unavailable: {error}");
                    *cache = None;
                    return;
                }
            }
        }
        if let Some((_, decoder)) = cache.as_mut() {
            decoder.flush();
        }
    }
    let guard_shared = shared.clone();
    let guard_mailbox = mailbox.clone();
    let serial = job.serial;
    input.set_abort_check(Some(Box::new(move || cancelled(&guard_shared, &guard_mailbox, serial))));
    let budget = read_budget(input);
    input.set_read_budget(Some(budget));
    let decoder = cache.as_mut().map(|(_, decoder)| decoder);
    let items = read_window(shared, mailbox, input, decoder, job, time_base);
    input.set_abort_check(None);
    input.set_read_budget(None);
    let Some(items) = items else {
        return;
    };
    let offset_us = input.start_time_us();
    let keep_after_us = job.target_us + offset_us - KEEP_MARGIN_MS * 1000;
    let batch = settle(items, keep_after_us);
    if !batch.is_empty() {
        shared.subtitles.commit(batch, offset_us, || !cancelled(shared, mailbox, serial));
    }
}

fn read_window(
    shared: &Arc<Shared>,
    mailbox: &Arc<Mailbox>,
    input: &mut Input,
    mut decoder: Option<&mut SubtitleDecoder>,
    job: Job,
    time_base: sys::AVRational,
) -> Option<Vec<DecodedSubtitle>> {
    let all: Vec<usize> = (0..input.streams().len()).collect();
    input.keep_only_streams(&all);
    if let Err(error) = input.seek((job.target_us - WINDOW_US).max(0)) {
        log::warn!("subtitle preroll seek failed: {error}");
        return None;
    }
    input.keep_only_streams(&[job.stream]);
    let offset_us = input.start_time_us();
    let offset_ms = offset_us / 1000;
    let target_ms = job.target_us / 1000;
    let mut packet = Packet::new().ok()?;
    let mut items = Vec::new();
    loop {
        if cancelled(shared, mailbox, job.serial) {
            return None;
        }
        match input.read(&mut packet) {
            Ok(()) => {
                if packet.stream_index() as usize != job.stream {
                    continue;
                }
                let pts_ms = packet.pts().and_then(|pts| stream_to_ms(pts, time_base)).map(|ms| ms - offset_ms);
                if pts_ms.is_some_and(|ms| ms > target_ms) {
                    break;
                }
                match job.mode {
                    SubMode::Ass => {
                        let Some(start_ms) = pts_ms else {
                            continue;
                        };
                        let duration_ms = stream_to_ms(packet.duration(), time_base).unwrap_or(0).max(0);
                        if start_ms + duration_ms > target_ms - KEEP_MARGIN_MS
                            && !ass::event_for(shared, job.serial, packet.data(), start_ms, duration_ms)
                        {
                            return None;
                        }
                    }
                    SubMode::Text => {
                        if let Some(decoded) = decoder.as_mut().and_then(|decoder| decoder.decode(&packet)) {
                            let finished = decoded.end_us.is_some_and(|end| (end - offset_us) / 1000 <= target_ms - KEEP_MARGIN_MS);
                            if !finished && !ass::text_event_for(shared, job.serial, &decoded, offset_us) {
                                return None;
                            }
                        }
                    }
                    SubMode::Store => {
                        if let Some(decoded) = decoder.as_mut().and_then(|decoder| decoder.decode(&packet)) {
                            items.push(decoded);
                        }
                    }
                }
            }
            Err(error) if error.is_again() => thread::sleep(Duration::from_millis(5)),
            Err(_) => break,
        }
    }
    Some(items)
}

fn settle(items: Vec<DecodedSubtitle>, keep_after_us: i64) -> Vec<DecodedSubtitle> {
    let mut ends: Vec<Option<i64>> = items.iter().map(|item| item.end_us).collect();
    for index in 0..items.len() {
        let bitmap = matches!(items[index].content, SubtitleContent::Bitmaps { .. });
        let closer = items[index + 1..]
            .iter()
            .find(|later| closes(items[index].start_us, ends[index], bitmap, later.start_us, !matches!(later.content, SubtitleContent::Text(_))))
            .map(|later| later.start_us);
        if closer.is_some() {
            ends[index] = closer;
        }
    }
    items
        .into_iter()
        .zip(ends)
        .filter_map(|(mut item, end)| {
            if matches!(item.content, SubtitleContent::Clear) {
                return None;
            }
            item.end_us = end;
            end.is_none_or(|end| end > keep_after_us).then_some(item)
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use lucky_ffmpeg::SubtitleBitmap;

    fn bitmap(start_ms: i64, end_ms: Option<i64>) -> DecodedSubtitle {
        DecodedSubtitle {
            start_us: start_ms * 1000,
            end_us: end_ms.map(|end| end * 1000),
            content: SubtitleContent::Bitmaps {
                frame_width: 1920,
                frame_height: 1080,
                rects: vec![SubtitleBitmap { x: 0, y: 900, width: 4, height: 2, argb: vec![0; 8] }],
            },
            ass_events: Vec::new(),
        }
    }

    fn clear(start_ms: i64) -> DecodedSubtitle {
        DecodedSubtitle { start_us: start_ms * 1000, end_us: None, content: SubtitleContent::Clear, ass_events: Vec::new() }
    }

    fn text(start_ms: i64, end_ms: Option<i64>) -> DecodedSubtitle {
        DecodedSubtitle {
            start_us: start_ms * 1000,
            end_us: end_ms.map(|end| end * 1000),
            content: SubtitleContent::Text("hi".into()),
            ass_events: Vec::new(),
        }
    }

    #[test]
    fn clearing_event_resolves_the_bitmap_end() {
        let kept = settle(vec![bitmap(1000, None), clear(4000), bitmap(9000, None)], 5_000_000);
        assert_eq!(kept.len(), 1);
        assert_eq!(kept[0].start_us, 9_000_000);
        assert_eq!(kept[0].end_us, None);
    }

    #[test]
    fn bitmap_open_at_the_target_is_kept() {
        let kept = settle(vec![bitmap(1000, None)], 5_000_000);
        assert_eq!(kept.len(), 1);
        assert_eq!(kept[0].end_us, None);
    }

    #[test]
    fn finished_text_is_dropped() {
        let kept = settle(vec![text(1000, Some(2000)), text(3000, Some(9000))], 5_000_000);
        assert_eq!(kept.len(), 1);
        assert_eq!(kept[0].start_us, 3_000_000);
    }

    #[test]
    fn replaced_bitmap_gets_the_replacement_start_as_end() {
        let kept = settle(vec![bitmap(4000, None), bitmap(6000, None)], 5_000_000);
        assert_eq!(kept.len(), 2);
        assert_eq!(kept[0].end_us, Some(6_000_000));
    }
}
