use std::sync::Mutex;
use std::time::Instant;

struct ClockState {
    anchor_us: i64,
    anchor_time: Instant,
    running: bool,
    valid: bool,
    rate: f64,
}

pub struct Clock {
    state: Mutex<ClockState>,
}

impl Default for Clock {
    fn default() -> Self {
        Self::new()
    }
}

impl Clock {
    pub fn new() -> Self {
        Self {
            state: Mutex::new(ClockState { anchor_us: 0, anchor_time: Instant::now(), running: false, valid: false, rate: 1.0 }),
        }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, ClockState> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn now_us(&self) -> Option<i64> {
        let state = self.lock();
        if !state.valid {
            return None;
        }
        if state.running {
            Some(state.anchor_us + (state.anchor_time.elapsed().as_micros() as f64 * state.rate) as i64)
        } else {
            Some(state.anchor_us)
        }
    }

    pub fn set(&self, position_us: i64, running: bool) {
        let mut state = self.lock();
        state.anchor_us = position_us;
        state.anchor_time = Instant::now();
        state.running = running;
        state.valid = true;
    }

    pub fn pause(&self) {
        let mut state = self.lock();
        if state.running {
            state.anchor_us += (state.anchor_time.elapsed().as_micros() as f64 * state.rate) as i64;
            state.anchor_time = Instant::now();
            state.running = false;
        }
    }

    pub fn rate(&self) -> f64 {
        self.lock().rate
    }

    pub fn set_rate(&self, rate: f64) {
        let mut state = self.lock();
        if state.running {
            state.anchor_us += (state.anchor_time.elapsed().as_micros() as f64 * state.rate) as i64;
            state.anchor_time = Instant::now();
        }
        state.rate = rate;
    }

    pub fn resume(&self) {
        let mut state = self.lock();
        if !state.running {
            state.anchor_time = Instant::now();
            state.running = true;
        }
    }

    pub fn invalidate(&self) {
        self.lock().valid = false;
    }
}
