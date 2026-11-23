use std::collections::VecDeque;
use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};

use lucky_ffmpeg::{DecodedSubtitle, SubtitleBitmap, SubtitleContent};
use serde::Serialize;

const MAX_CUES: usize = 600;
const MAX_CLEARS: usize = 64;
const CLOSE_SCAN: usize = 8;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SubMode {
    Store,
    Ass,
    Text,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BitmapRect {
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CueInfo {
    pub id: u64,
    pub start_ms: i64,
    pub end_ms: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub frame_width: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub frame_height: Option<i32>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub rects: Vec<BitmapRect>,
}

struct Cue {
    info: CueInfo,
    bitmaps: Vec<SubtitleBitmap>,
    revision: u64,
    hash: u64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CueBatch {
    pub generation: u64,
    pub revision: u64,
    pub cues: Vec<CueInfo>,
}

struct StoreState {
    cues: VecDeque<Cue>,
    clears: VecDeque<i64>,
    next_id: u64,
    revision: u64,
    pinned: bool,
}

pub struct SubtitleStore {
    generation: AtomicU64,
    state: Mutex<StoreState>,
}

impl Default for SubtitleStore {
    fn default() -> Self {
        Self {
            generation: AtomicU64::new(0),
            state: Mutex::new(StoreState { cues: VecDeque::new(), clears: VecDeque::new(), next_id: 1, revision: 0, pinned: false }),
        }
    }
}

pub(crate) fn closes(cue_start: i64, cue_end: Option<i64>, cue_bitmap: bool, later_start: i64, later_replaces: bool) -> bool {
    cue_start <= later_start && cue_end.is_none_or(|end| end > later_start) && (cue_end.is_none() || (later_replaces && cue_bitmap))
}

fn content_hash(content: &SubtitleContent) -> u64 {
    let mut hasher = DefaultHasher::new();
    match content {
        SubtitleContent::Text(text) => {
            0u8.hash(&mut hasher);
            text.hash(&mut hasher);
        }
        SubtitleContent::Bitmaps { frame_width, frame_height, rects } => {
            1u8.hash(&mut hasher);
            frame_width.hash(&mut hasher);
            frame_height.hash(&mut hasher);
            for rect in rects {
                rect.x.hash(&mut hasher);
                rect.y.hash(&mut hasher);
                rect.width.hash(&mut hasher);
                rect.height.hash(&mut hasher);
                rect.argb.hash(&mut hasher);
            }
        }
        SubtitleContent::Clear => 2u8.hash(&mut hasher),
    }
    hasher.finish()
}

fn insert_locked(state: &mut StoreState, decoded: DecodedSubtitle, hash: u64, offset_us: i64, pinned: bool) {
    let start_ms = (decoded.start_us - offset_us) / 1000;
    let mut end_ms = decoded.end_us.map(|end| (end - offset_us) / 1000);
    let replaces = !matches!(decoded.content, SubtitleContent::Text(_));
    let bitmap = matches!(decoded.content, SubtitleContent::Bitmaps { .. });
    let clear = matches!(decoded.content, SubtitleContent::Clear);
    state.revision += 1;
    let revision = state.revision;
    let lower = state.cues.partition_point(|cue| cue.info.start_ms < start_ms);
    let upper = state.cues.partition_point(|cue| cue.info.start_ms <= start_ms);
    let duplicate = if pinned || clear {
        None
    } else {
        (lower..upper).find(|&index| {
            let cue = &state.cues[index];
            cue.hash == hash && (bitmap || cue.info.end_ms == end_ms || cue.info.end_ms.is_none() || end_ms.is_none())
        })
    };
    for index in upper.saturating_sub(CLOSE_SCAN)..upper {
        if Some(index) == duplicate {
            continue;
        }
        let cue = &mut state.cues[index];
        if closes(cue.info.start_ms, cue.info.end_ms, !cue.info.rects.is_empty(), start_ms, replaces) {
            cue.info.end_ms = Some(start_ms);
            cue.revision = revision;
        }
    }
    if clear {
        if !pinned {
            state.clears.push_back(start_ms);
            while state.clears.len() > MAX_CLEARS {
                state.clears.pop_front();
            }
        }
        return;
    }
    if let Some(index) = duplicate {
        let cue = &mut state.cues[index];
        if cue.info.end_ms.is_none() && end_ms.is_some() {
            cue.info.end_ms = end_ms;
            cue.revision = revision;
        }
        return;
    }
    if !pinned {
        let later = state
            .cues
            .range(upper..)
            .find(|cue| closes(start_ms, end_ms, bitmap, cue.info.start_ms, !cue.info.rects.is_empty()))
            .map(|cue| cue.info.start_ms);
        let marker = state.clears.iter().copied().filter(|&at| at > start_ms && closes(start_ms, end_ms, bitmap, at, true)).min();
        if let Some(at) = later.into_iter().chain(marker).min() {
            end_ms = Some(at);
        }
    }
    let (text, frame, bitmaps) = match decoded.content {
        SubtitleContent::Text(text) => (Some(text), None, Vec::new()),
        SubtitleContent::Bitmaps { frame_width, frame_height, rects } => (None, Some((frame_width, frame_height)), rects),
        SubtitleContent::Clear => return,
    };
    let id = state.next_id;
    state.next_id += 1;
    let info = CueInfo {
        id,
        start_ms,
        end_ms,
        text,
        frame_width: frame.map(|f| f.0),
        frame_height: frame.map(|f| f.1),
        rects: bitmaps
            .iter()
            .map(|b| BitmapRect { x: b.x, y: b.y, width: b.width, height: b.height })
            .collect(),
    };
    state.cues.insert(upper, Cue { info, bitmaps, revision, hash });
    while !pinned && state.cues.len() > MAX_CUES {
        state.cues.pop_front();
    }
}

impl SubtitleStore {
    fn lock(&self) -> std::sync::MutexGuard<'_, StoreState> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn reset(&self) {
        let mut state = self.lock();
        if !state.pinned {
            state.cues.clear();
            state.clears.clear();
        }
        self.generation.fetch_add(1, Ordering::AcqRel);
    }

    pub fn clear(&self) {
        let mut state = self.lock();
        state.pinned = false;
        state.cues.clear();
        state.clears.clear();
        self.generation.fetch_add(1, Ordering::AcqRel);
    }

    pub fn pin(&self, cues: &[DecodedSubtitle]) {
        {
            let mut state = self.lock();
            state.cues.clear();
            state.clears.clear();
            state.pinned = true;
            for cue in cues {
                insert_locked(&mut state, cue.clone(), 0, 0, true);
            }
        }
        self.generation.fetch_add(1, Ordering::AcqRel);
    }

    pub fn push(&self, decoded: DecodedSubtitle, offset_us: i64) {
        let hash = content_hash(&decoded.content);
        let mut state = self.lock();
        if state.pinned {
            return;
        }
        insert_locked(&mut state, decoded, hash, offset_us, false);
    }

    pub fn commit(&self, batch: Vec<DecodedSubtitle>, offset_us: i64, valid: impl Fn() -> bool) -> bool {
        let hashed: Vec<(DecodedSubtitle, u64)> = batch
            .into_iter()
            .map(|decoded| {
                let hash = content_hash(&decoded.content);
                (decoded, hash)
            })
            .collect();
        let mut state = self.lock();
        if state.pinned || !valid() {
            return false;
        }
        for (decoded, hash) in hashed {
            insert_locked(&mut state, decoded, hash, offset_us, false);
        }
        true
    }

    pub fn since(&self, after_revision: u64) -> CueBatch {
        let state = self.lock();
        CueBatch {
            generation: self.generation.load(Ordering::Acquire),
            revision: state.revision,
            cues: state.cues.iter().filter(|cue| cue.revision > after_revision).map(|cue| cue.info.clone()).collect(),
        }
    }

    pub fn bitmap(&self, id: u64, rect: usize) -> Option<(i32, i32, Vec<u32>)> {
        let state = self.lock();
        let cue = state.cues.iter().find(|cue| cue.info.id == id)?;
        let bitmap = cue.bitmaps.get(rect)?;
        Some((bitmap.width, bitmap.height, bitmap.argb.clone()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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

    fn text(start_ms: i64, end_ms: Option<i64>) -> DecodedSubtitle {
        DecodedSubtitle {
            start_us: start_ms * 1000,
            end_us: end_ms.map(|end| end * 1000),
            content: SubtitleContent::Text("hi".into()),
            ass_events: Vec::new(),
        }
    }

    #[test]
    fn new_bitmap_ends_previous_and_is_reported() {
        let store = SubtitleStore::default();
        store.push(bitmap(1000, Some(5000)), 0);
        let first = store.since(0);
        assert_eq!(first.cues.len(), 1);
        store.push(bitmap(2000, None), 0);
        let update = store.since(first.revision);
        assert_eq!(update.cues.len(), 2);
        assert_eq!(update.cues[0].end_ms, Some(2000));
        store.push(DecodedSubtitle { start_us: 3_000_000, end_us: None, content: SubtitleContent::Clear, ass_events: Vec::new() }, 0);
        let cleared = store.since(update.revision);
        assert_eq!(cleared.cues.len(), 1);
        assert_eq!(cleared.cues[0].end_ms, Some(3000));
        assert!(store.since(cleared.revision).cues.is_empty());
    }

    #[test]
    fn duplicate_cues_are_dropped() {
        let store = SubtitleStore::default();
        store.push(text(1000, Some(5000)), 0);
        store.push(text(1000, Some(5000)), 0);
        store.push(text(1000, Some(6000)), 0);
        assert_eq!(store.since(0).cues.len(), 2);
    }

    #[test]
    fn duplicate_bitmap_fills_a_missing_end() {
        let store = SubtitleStore::default();
        store.push(bitmap(1000, None), 0);
        let first = store.since(0);
        store.push(bitmap(1000, Some(4000)), 0);
        let update = store.since(first.revision);
        assert_eq!(update.cues.len(), 1);
        assert_eq!(update.cues[0].end_ms, Some(4000));
        assert_eq!(store.since(0).cues.len(), 1);
    }

    #[test]
    fn late_bitmap_is_closed_by_an_earlier_clear() {
        let store = SubtitleStore::default();
        store.push(DecodedSubtitle { start_us: 3_000_000, end_us: None, content: SubtitleContent::Clear, ass_events: Vec::new() }, 0);
        store.push(bitmap(1000, None), 0);
        let batch = store.since(0);
        assert_eq!(batch.cues.len(), 1);
        assert_eq!(batch.cues[0].end_ms, Some(3000));
    }

    #[test]
    fn late_bitmap_is_closed_by_a_later_bitmap() {
        let store = SubtitleStore::default();
        store.push(bitmap(3000, None), 0);
        store.push(bitmap(1000, None), 0);
        let batch = store.since(0);
        assert_eq!(batch.cues.len(), 2);
        assert_eq!(batch.cues[0].start_ms, 1000);
        assert_eq!(batch.cues[0].end_ms, Some(3000));
        assert_eq!(batch.cues[1].end_ms, None);
    }

    #[test]
    fn late_cues_keep_chronological_order() {
        let store = SubtitleStore::default();
        store.push(text(5000, Some(6000)), 0);
        store.push(text(1000, Some(2000)), 0);
        let starts: Vec<i64> = store.since(0).cues.iter().map(|cue| cue.start_ms).collect();
        assert_eq!(starts, vec![1000, 5000]);
    }

    #[test]
    fn stale_commit_is_rejected() {
        let store = SubtitleStore::default();
        assert!(!store.commit(vec![text(1000, Some(2000))], 0, || false));
        assert!(store.since(0).cues.is_empty());
        assert!(store.commit(vec![text(1000, Some(2000))], 0, || true));
        assert_eq!(store.since(0).cues.len(), 1);
    }

    #[test]
    fn overlapping_text_is_kept() {
        let store = SubtitleStore::default();
        store.push(text(1000, Some(5000)), 0);
        store.push(text(2000, Some(4000)), 0);
        let batch = store.since(0);
        assert_eq!(batch.cues[0].end_ms, Some(5000));
        assert_eq!(batch.cues[1].end_ms, Some(4000));
    }
}
