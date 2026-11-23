use std::collections::VecDeque;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Condvar, Mutex, MutexGuard};
use std::time::Duration;

use lucky_ffmpeg::Packet;

use crate::frame::VideoFrame;

const WAIT_SLICE: Duration = Duration::from_millis(20);

pub enum PacketItem {
    Packet { packet: Packet, serial: u64 },
    Flush { serial: u64, target_us: Option<i64> },
    End { serial: u64 },
}

impl PacketItem {
    fn bytes(&self) -> usize {
        match self {
            PacketItem::Packet { packet, .. } => packet.size() + 64,
            _ => 0,
        }
    }
}

struct PacketState {
    items: VecDeque<PacketItem>,
    bytes: usize,
    aborted: bool,
}

pub struct PacketQueue {
    state: Mutex<PacketState>,
    readable: Condvar,
    writable: Condvar,
    max_bytes: AtomicUsize,
    max_items: AtomicUsize,
}

impl PacketQueue {
    pub fn new(max_bytes: usize, max_items: usize) -> Self {
        Self {
            state: Mutex::new(PacketState { items: VecDeque::new(), bytes: 0, aborted: false }),
            readable: Condvar::new(),
            writable: Condvar::new(),
            max_bytes: AtomicUsize::new(max_bytes),
            max_items: AtomicUsize::new(max_items),
        }
    }

    fn lock(&self) -> MutexGuard<'_, PacketState> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn set_limits(&self, max_bytes: usize, max_items: usize) {
        self.max_bytes.store(max_bytes, Ordering::Release);
        self.max_items.store(max_items, Ordering::Release);
        self.writable.notify_all();
    }

    pub fn push(&self, item: PacketItem, cancelled: impl Fn() -> bool) -> bool {
        let mut state = self.lock();
        let is_packet = matches!(item, PacketItem::Packet { .. });
        loop {
            if state.aborted || cancelled() {
                return false;
            }
            if !is_packet
                || (state.bytes < self.max_bytes.load(Ordering::Acquire) && state.items.len() < self.max_items.load(Ordering::Acquire))
            {
                break;
            }
            state = self.writable.wait_timeout(state, WAIT_SLICE).unwrap_or_else(|e| e.into_inner()).0;
        }
        state.bytes += item.bytes();
        state.items.push_back(item);
        self.readable.notify_one();
        true
    }

    pub fn flush(&self, serial: u64, target_us: Option<i64>) {
        let mut state = self.lock();
        state.items.clear();
        state.bytes = 0;
        state.items.push_back(PacketItem::Flush { serial, target_us });
        self.readable.notify_all();
        self.writable.notify_all();
    }

    pub fn pop(&self, timeout: Duration) -> Option<PacketItem> {
        let mut state = self.lock();
        if state.items.is_empty() && !state.aborted {
            state = self.readable.wait_timeout(state, timeout).unwrap_or_else(|e| e.into_inner()).0;
        }
        let item = state.items.pop_front()?;
        state.bytes = state.bytes.saturating_sub(item.bytes());
        self.writable.notify_one();
        Some(item)
    }

    pub fn abort(&self) {
        let mut state = self.lock();
        state.aborted = true;
        state.items.clear();
        state.bytes = 0;
        self.readable.notify_all();
        self.writable.notify_all();
    }
}

pub enum FrameItem {
    Frame(VideoFrame),
    End { serial: u64 },
}

impl FrameItem {
    pub fn serial(&self) -> u64 {
        match self {
            FrameItem::Frame(frame) => frame.serial,
            FrameItem::End { serial } => *serial,
        }
    }
}

struct FrameState {
    items: VecDeque<FrameItem>,
    aborted: bool,
}

pub struct FrameQueue {
    state: Mutex<FrameState>,
    readable: Condvar,
    writable: Condvar,
    capacity: usize,
}

impl FrameQueue {
    pub fn new(capacity: usize) -> Self {
        Self {
            state: Mutex::new(FrameState { items: VecDeque::new(), aborted: false }),
            readable: Condvar::new(),
            writable: Condvar::new(),
            capacity,
        }
    }

    fn lock(&self) -> MutexGuard<'_, FrameState> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn push(&self, item: FrameItem, cancelled: impl Fn() -> bool) -> bool {
        let mut state = self.lock();
        let is_frame = matches!(item, FrameItem::Frame(_));
        loop {
            if state.aborted || cancelled() {
                return false;
            }
            if !is_frame || state.items.len() < self.capacity {
                break;
            }
            state = self.writable.wait_timeout(state, WAIT_SLICE).unwrap_or_else(|e| e.into_inner()).0;
        }
        state.items.push_back(item);
        self.readable.notify_one();
        true
    }

    pub fn pop_current(&self, serial: u64) -> Option<FrameItem> {
        let mut state = self.lock();
        while let Some(front) = state.items.front() {
            if front.serial() == serial {
                break;
            }
            state.items.pop_front();
            self.writable.notify_one();
        }
        let item = state.items.pop_front();
        if item.is_some() {
            self.writable.notify_one();
        }
        item
    }

    pub fn front_pts(&self, serial: u64) -> Option<i64> {
        let state = self.lock();
        state.items.iter().find_map(|item| match item {
            FrameItem::Frame(frame) if frame.serial == serial => Some(frame.pts_us),
            _ => None,
        })
    }

    pub fn wait_readable(&self, timeout: Duration) {
        let state = self.lock();
        if state.items.is_empty() && !state.aborted {
            let _ = self.readable.wait_timeout(state, timeout);
        }
    }

    pub fn clear(&self) {
        let mut state = self.lock();
        state.items.clear();
        self.writable.notify_all();
    }

    pub fn abort(&self) {
        let mut state = self.lock();
        state.aborted = true;
        state.items.clear();
        self.readable.notify_all();
        self.writable.notify_all();
    }
}
