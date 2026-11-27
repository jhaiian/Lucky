use serde::Serialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum PlayerState {
    #[default]
    Opening,
    Paused,
    Playing,
    Buffering,
    Ended,
    Error,
}

#[derive(Debug, Clone, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct TrackEntry {
    pub index: i32,
    pub codec: String,
    pub language: Option<String>,
    pub title: Option<String>,
    pub channels: Option<i32>,
    pub is_default: bool,
    pub is_forced: bool,
    pub external: bool,
}

#[derive(Debug, Clone, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ChapterEntry {
    pub start_ms: i64,
    pub end_ms: Option<i64>,
    pub title: Option<String>,
}

#[derive(Debug, Clone, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub state: PlayerState,
    pub position_ms: i64,
    pub duration_ms: Option<i64>,
    pub seekable: bool,
    pub video_width: i32,
    pub video_height: i32,
    pub display_aspect: f64,
    pub rotation: i32,
    pub frame_rate: Option<f64>,
    pub video_codec: Option<String>,
    pub video_decoder: Option<String>,
    pub decoder_kind: Option<String>,
    pub decoder_fallbacks: u32,
    pub pixel_format: Option<String>,
    pub hdr_format: Option<String>,
    pub display_output: i32,
    pub render_ms: f32,
    pub render_peak_ms: f32,
    pub cost_cap: f32,
    pub smooth_blending: bool,
    pub learned_cost: f32,
    pub motion_level: u8,
    pub motion_scale: u8,
    pub output_fps: f32,
    pub learned_motion: u8,
    pub active_kernel: i32,
    pub scaling_direction: u8,
    pub decoder_catch_up: u8,
    pub deinterlaced: bool,
    pub renderer: Option<String>,
    pub audio_codec: Option<String>,
    pub audio_decoder: Option<String>,
    pub audio_sample_rate: Option<u32>,
    pub audio_channels: Option<u32>,
    pub audio_output: Option<String>,
    pub audio_passthrough: Option<String>,
    pub audio_frames: u64,
    pub has_video: bool,
    pub audio_tracks: Vec<TrackEntry>,
    pub subtitle_tracks: Vec<TrackEntry>,
    pub selected_audio: Option<i32>,
    pub selected_subtitle: Option<i32>,
    pub ass_active: bool,
    pub text_ass: bool,
    pub chapters: Vec<ChapterEntry>,
    pub decoded_frames: u64,
    pub presented_frames: u64,
    pub dropped_frames: u64,
    pub error: Option<String>,
}
