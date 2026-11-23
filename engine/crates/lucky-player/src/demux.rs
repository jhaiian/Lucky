use std::os::fd::OwnedFd;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use lucky_ffmpeg::{ChannelLayout, CodecParameters, Decoder, Input, Packet, SubtitleDecoder, c_str, rational_to_f64, sys};

use crate::ass;
use crate::audio::{AudioOutput, AudioParams, AudioPlan, Limits, StreamAudio, choose_plan, open_output, output_kind, run_audio};
use crate::passthrough::{EncodedCodec, EncodedParams, open_encoded, run_encoded};
use crate::external::EXTERNAL_BASE;
use crate::backend::{HardwareDecoderFactory, SoftwareBackend, VideoBackend, VideoStreamInfo};
use crate::effects::track_gain_from_tags;
use crate::frame::{ColorInfo, DoviConfig};
use crate::preroll::{Job, Preroll, duplicate_fd};
use crate::queue::PacketItem;
use crate::shared::{Shared, TrackRequest};
use crate::status::{ChapterEntry, PlayerState, TrackEntry};
use crate::subtitles::SubMode;
use crate::video::{VideoParams, report_backend, run_video_decoder};

pub(crate) fn stream_rotation(stream: &sys::AVStream) -> i32 {
    unsafe {
        let par = stream.codecpar;
        if par.is_null() || (*par).coded_side_data.is_null() {
            return 0;
        }
        let entry = sys::av_packet_side_data_get(
            (*par).coded_side_data,
            (*par).nb_coded_side_data,
            sys::AVPacketSideDataType::AV_PKT_DATA_DISPLAYMATRIX,
        );
        if entry.is_null() || (*entry).size < 36 || (*entry).data.is_null() {
            return 0;
        }
        let angle = sys::av_display_rotation_get((*entry).data as *const i32);
        if angle.is_nan() { 0 } else { ((-angle).round() as i32).rem_euclid(360) }
    }
}

fn stream_kind_is(stream: &sys::AVStream, kind: sys::AVMediaType) -> bool {
    !stream.codecpar.is_null() && unsafe { (*stream.codecpar).codec_type } == kind
}

fn decoder_threads() -> i32 {
    thread::available_parallelism().map(|n| n.get().min(16) as i32).unwrap_or(4)
}

fn dict_value(dict: *const sys::AVDictionary, key: &std::ffi::CStr) -> Option<String> {
    if dict.is_null() {
        return None;
    }
    let entry = unsafe { sys::av_dict_get(dict, key.as_ptr(), std::ptr::null(), 0) };
    if entry.is_null() {
        return None;
    }
    c_str(unsafe { (*entry).value })
}

fn track_gain(input: &Input, stream: &sys::AVStream) -> Option<f32> {
    let container = unsafe { (*input.as_ptr()).metadata };
    let tag = |key: &std::ffi::CStr| dict_value(stream.metadata, key).or_else(|| dict_value(container, key));
    track_gain_from_tags(tag(c"replaygain_track_gain").as_deref(), tag(c"r128_track_gain").as_deref())
}

fn track_entries(input: &Input, kind: sys::AVMediaType) -> Vec<TrackEntry> {
    input
        .streams()
        .iter()
        .filter(|s| !s.is_null())
        .map(|s| unsafe { &**s })
        .filter(|s| !s.codecpar.is_null() && unsafe { (*s.codecpar).codec_type } == kind)
        .filter(|s| s.disposition as u32 & sys::AV_DISPOSITION_ATTACHED_PIC == 0)
        .map(|s| {
            let par = unsafe { &*s.codecpar };
            let disposition = s.disposition as u32;
            TrackEntry {
                index: s.index,
                codec: c_str(unsafe { sys::avcodec_get_name(par.codec_id) }).unwrap_or_default(),
                language: dict_value(s.metadata, c"language").filter(|l| l != "und"),
                title: dict_value(s.metadata, c"title"),
                channels: (kind == sys::AVMediaType::AVMEDIA_TYPE_AUDIO).then_some(par.ch_layout.nb_channels),
                is_default: disposition & sys::AV_DISPOSITION_DEFAULT != 0,
                is_forced: disposition & sys::AV_DISPOSITION_FORCED != 0,
                external: false,
            }
        })
        .collect()
}

struct AudioState {
    index: Option<usize>,
    thread: Option<JoinHandle<Box<dyn AudioOutput>>>,
    stop: Arc<AtomicBool>,
    spare: Option<Box<dyn AudioOutput>>,
    limits: (Option<usize>, Limits),
}

#[allow(clippy::too_many_arguments)]
fn publish_audio(
    shared: &Shared,
    par: &sys::AVCodecParameters,
    index: usize,
    decoder: Option<&str>,
    sample_rate: u32,
    channels: u32,
    kind: &str,
    passthrough: Option<EncodedCodec>,
) {
    let mut status = shared.status();
    status.audio_codec = c_str(unsafe { sys::avcodec_get_name(par.codec_id) });
    status.audio_decoder = decoder.map(str::to_string);
    status.audio_sample_rate = Some(sample_rate);
    status.audio_channels = Some(channels);
    status.audio_output = Some(kind.to_string());
    status.audio_passthrough = passthrough.map(|codec| codec.name().to_string());
    status.selected_audio = Some(index as i32);
}

impl AudioState {
    fn stop(&mut self, shared: &Shared) {
        if let Some(thread) = self.thread.take() {
            self.stop.store(true, Ordering::Release);
            match thread.join() {
                Ok(output) => self.spare = Some(output),
                Err(_) => log::error!("audio thread panicked"),
            }
        }
        self.stop = Arc::new(AtomicBool::new(false));
        self.index = None;
        shared.audio_master.store(false, Ordering::Release);
        shared.clear_audio_finished();
        let mut status = shared.status();
        status.audio_codec = None;
        status.audio_decoder = None;
        status.audio_sample_rate = None;
        status.audio_channels = None;
        status.audio_output = None;
        status.audio_passthrough = None;
        status.selected_audio = None;
    }

    fn launch<F>(&mut self, shared: &Shared, index: usize, run: F) -> bool
    where
        F: FnOnce() -> Box<dyn AudioOutput> + Send + 'static,
    {
        match thread::Builder::new().name("lucky-audio".into()).spawn(run) {
            Ok(handle) => {
                self.thread = Some(handle);
                self.index = Some(index);
                true
            }
            Err(error) => {
                log::error!("cannot start the audio thread: {error}");
                shared.audio_master.store(false, Ordering::Release);
                false
            }
        }
    }

    fn start(&mut self, input: &Input, shared: &Arc<Shared>, index: usize, has_video: bool) -> bool {
        let Some(mut output) = self.spare.take() else {
            return false;
        };
        let Some(stream_ptr) = input.stream(index) else {
            self.spare = Some(output);
            return false;
        };
        let stream = unsafe { &*stream_ptr };
        if !stream_kind_is(stream, sys::AVMediaType::AVMEDIA_TYPE_AUDIO) {
            log::warn!("stream {index} is not an audio track");
            self.spare = Some(output);
            return false;
        }
        if self.limits.0 != Some(index) {
            self.limits = (Some(index), Limits::default());
        }
        let par = unsafe { &*stream.codecpar };
        let facts = StreamAudio::from_parameters(par);
        let mut plan = choose_plan(shared, output.as_mut(), &facts, self.limits.1);
        if let AudioPlan::Encoded(codec) = plan {
            if let Some(sample_rate) = open_encoded(output.as_mut(), &facts, codec) {
                output.flush();
                publish_audio(shared, par, index, None, sample_rate, facts.channels, "passthrough", Some(codec));
                shared.clear_audio_finished();
                shared.audio_master.store(true, Ordering::Release);
                let params = EncodedParams {
                    start_us: input.start_time_us(),
                    time_base: stream.time_base,
                    sample_rate,
                    codec,
                    has_video,
                    stream: facts,
                    limits: self.limits.1,
                };
                let audio_shared = shared.clone();
                let stop = self.stop.clone();
                return self.launch(shared, index, move || run_encoded(audio_shared, stop, output, params));
            }
            log::warn!("passthrough output is unavailable for {}, decoding instead", codec.name());
            self.limits.1.blocked_codecs |= codec.bit();
            plan = choose_plan(shared, output.as_mut(), &facts, self.limits.1);
        }
        let wanted = match plan {
            AudioPlan::Pcm(layout) => layout,
            AudioPlan::Encoded(_) => ChannelLayout::STEREO,
        };
        let decoder = match Decoder::open(stream_ptr, 1) {
            Ok(decoder) => decoder,
            Err(error) => {
                log::warn!("audio decoder unavailable: {error}");
                self.spare = Some(output);
                return false;
            }
        };
        let Some((sample_rate, layout)) = open_output(output.as_mut(), &decoder, wanted) else {
            log::warn!("audio output is unavailable");
            self.spare = Some(output);
            return false;
        };
        if layout != wanted {
            log::warn!("audio output could not open {} channels, using {}", wanted.channels(), layout.channels());
            self.limits.1.max_channels = layout.channels();
        }
        output.flush();
        publish_audio(
            shared,
            par,
            index,
            Some(decoder.name()),
            sample_rate,
            layout.channels() as u32,
            output_kind(layout),
            None,
        );
        shared.clear_audio_finished();
        shared.audio_master.store(true, Ordering::Release);
        let params = AudioParams {
            start_us: input.start_time_us(),
            time_base: stream.time_base,
            sample_rate,
            layout,
            has_video,
            stream: facts,
            plan: AudioPlan::Pcm(layout),
            limits: self.limits.1,
            track_gain_db: track_gain(input, stream),
        };
        let audio_shared = shared.clone();
        let stop = self.stop.clone();
        self.launch(shared, index, move || run_audio(audio_shared, stop, decoder, output, params))
    }
}

struct SubtitleState {
    index: Option<usize>,
    decoder: Option<SubtitleDecoder>,
    mode: SubMode,
    time_base: sys::AVRational,
    selected: Option<usize>,
}

pub(crate) fn stream_to_ms(value: i64, time_base: sys::AVRational) -> Option<i64> {
    if value == sys::AV_NOPTS_VALUE || time_base.den == 0 {
        return None;
    }
    Some((value as i128 * time_base.num as i128 * 1000 / time_base.den as i128) as i64)
}

impl SubtitleState {
    fn select(&mut self, input: &Input, shared: &Shared, selection: Option<usize>) {
        self.decoder = None;
        self.index = None;
        self.mode = SubMode::Store;
        self.selected = None;
        ass::end(shared);
        shared.subtitles.clear();
        let text_libass = shared.text_libass.load(Ordering::Acquire);
        let mut ass_active = false;
        let mut text_ass = false;
        if let Some(id) = selection {
            if id >= EXTERNAL_BASE {
                let externals = shared.externals.lock().unwrap_or_else(|e| e.into_inner());
                if let Some(external) = externals.get(id - EXTERNAL_BASE) {
                    self.selected = Some(id);
                    if let Some(header) = external.ass_header.as_deref() {
                        if ass::begin(shared, header) {
                            for event in &external.ass_events {
                                ass::event(shared, &event.data, event.start_ms, event.duration_ms);
                            }
                            ass_active = true;
                        }
                    }
                    if !ass_active && text_libass && external.text && ass::begin_text(shared) {
                        for cue in &external.cues {
                            ass::text_event(shared, cue, 0);
                        }
                        ass_active = true;
                        text_ass = true;
                    }
                    if !ass_active {
                        shared.subtitles.pin(&external.cues);
                    }
                }
            } else if let Some(stream_ptr) =
                input.stream(id).filter(|s| stream_kind_is(unsafe { &**s }, sys::AVMediaType::AVMEDIA_TYPE_SUBTITLE))
            {
                let stream = unsafe { &*stream_ptr };
                let par = unsafe { &*stream.codecpar };
                self.time_base = stream.time_base;
                let is_ass = par.codec_id == sys::AVCodecID::AV_CODEC_ID_ASS || par.codec_id == sys::AVCodecID::AV_CODEC_ID_SSA;
                if is_ass {
                    let header = if par.extradata.is_null() || par.extradata_size <= 0 {
                        &[][..]
                    } else {
                        unsafe { std::slice::from_raw_parts(par.extradata, par.extradata_size as usize) }
                    };
                    if ass::begin(shared, header) {
                        self.mode = SubMode::Ass;
                        self.index = Some(id);
                        self.selected = Some(id);
                        ass_active = true;
                    }
                }
                if self.mode != SubMode::Ass {
                    match CodecParameters::copy_from(stream.codecpar).and_then(|p| SubtitleDecoder::open(&p, stream.time_base)) {
                        Ok(decoder) => {
                            if text_libass && decoder.is_text() && ass::begin_text(shared) {
                                self.mode = SubMode::Text;
                                ass_active = true;
                                text_ass = true;
                            }
                            self.decoder = Some(decoder);
                            self.index = Some(id);
                            self.selected = Some(id);
                        }
                        Err(error) => log::warn!("subtitle decoder unavailable: {error}"),
                    }
                }
            }
        }
        let mut status = shared.status();
        status.selected_subtitle = self.selected.map(|i| i as i32);
        status.ass_active = ass_active;
        status.text_ass = text_ass;
    }
}

fn initial_subtitle(tracks: &[TrackEntry]) -> Option<usize> {
    tracks
        .iter()
        .find(|t| t.is_forced)
        .or_else(|| tracks.iter().find(|t| t.is_default))
        .map(|t| t.index as usize)
}

pub fn run_demuxer(
    shared: Arc<Shared>,
    fd: OwnedFd,
    audio_output: Option<Box<dyn AudioOutput>>,
    hardware: Option<Box<dyn HardwareDecoderFactory>>,
) {
    let spare = duplicate_fd(&fd);
    let mut input = match Input::open_fd_with_interrupt(fd, shared.interrupt.clone()) {
        Ok(input) => input,
        Err(error) => {
            if !shared.aborted() {
                shared.fail(error.to_string());
            }
            return;
        }
    };

    let video_index = input.best_stream(sys::AVMediaType::AVMEDIA_TYPE_VIDEO);
    let audio_tracks = track_entries(&input, sys::AVMediaType::AVMEDIA_TYPE_AUDIO);
    let subtitle_tracks = track_entries(&input, sys::AVMediaType::AVMEDIA_TYPE_SUBTITLE);
    let has_video = video_index.is_some();
    shared.has_video.store(has_video, Ordering::Release);
    {
        let mut status = shared.status();
        status.duration_ms = input.duration_us().map(|d| d / 1000);
        status.seekable = input.seekable();
        status.has_video = has_video;
        status.audio_tracks = audio_tracks.clone();
        status.subtitle_tracks = subtitle_tracks.clone();
    }

    let mut video_thread = None;
    if let Some(video_index) = video_index {
        let stream_ptr = input.stream(video_index).expect("video stream");
        let stream = unsafe { &*stream_ptr };
        let par = unsafe { &*stream.codecpar };
        let params_copy = match CodecParameters::copy_from(stream.codecpar) {
            Ok(params) => params,
            Err(error) => {
                shared.fail(error.to_string());
                return;
            }
        };
        let frame_rate = rational_to_f64(stream.avg_frame_rate)
            .or_else(|| rational_to_f64(stream.r_frame_rate))
            .filter(|fps| *fps > 1.0 && *fps < 500.0);
        let rotation = stream_rotation(stream);
        let dovi = DoviConfig::from_params(par);
        let dovi_reshape = dovi.is_some_and(|config| config.needs_reshape());
        let sar = if par.sample_aspect_ratio.num > 0 && par.sample_aspect_ratio.den > 0 {
            (par.sample_aspect_ratio.num, par.sample_aspect_ratio.den)
        } else if stream.sample_aspect_ratio.num > 0 && stream.sample_aspect_ratio.den > 0 {
            (stream.sample_aspect_ratio.num, stream.sample_aspect_ratio.den)
        } else {
            (1, 1)
        };
        {
            let mut status = shared.status();
            status.video_width = par.width;
            status.video_height = par.height;
            status.display_aspect = if par.height > 0 {
                par.width as f64 * sar.0 as f64 / sar.1 as f64 / par.height as f64
            } else {
                0.0
            };
            status.rotation = rotation;
            status.frame_rate = frame_rate;
            status.video_codec = c_str(unsafe { sys::avcodec_get_name(par.codec_id) });
            status.pixel_format = c_str(unsafe { sys::av_get_pix_fmt_name(sys::AVPixelFormat(par.format)) });
            status.hdr_format = match (dovi, par.color_trc) {
                (Some(config), _) => Some(format!("dv:{}:{}", config.profile, config.compatibility)),
                (None, sys::AVColorTransferCharacteristic::AVCOL_TRC_SMPTE2084) => Some("hdr10".into()),
                (None, sys::AVColorTransferCharacteristic::AVCOL_TRC_ARIB_STD_B67) => Some("hlg".into()),
                _ => None,
            };
        }
        let stream_info = VideoStreamInfo { params: params_copy, time_base: stream.time_base, frame_rate };
        let threads = decoder_threads();
        let hardware_backend = if dovi_reshape {
            log::info!("Dolby Vision profile {} needs reshaping, decoding in software", dovi.map(|c| c.profile).unwrap_or(0));
            None
        } else if hardware.is_some() && shared.defer_hardware() {
            log::info!("preloading with the software decoder, the hardware decoder starts on activation");
            None
        } else {
            hardware.as_ref().and_then(|factory| factory.create(&stream_info))
        };
        let backend: Box<dyn VideoBackend> = match hardware_backend {
            Some(backend) => backend,
            None => match SoftwareBackend::open(&stream_info, threads) {
                Ok(backend) => Box::new(backend),
                Err(error) => {
                    shared.fail(error);
                    return;
                }
            },
        };
        report_backend(&shared, backend.as_ref());
        let params = VideoParams {
            start_us: input.start_time_us(),
            frame_duration_us: frame_rate.map(|fps| (1_000_000.0 / fps) as i64).unwrap_or(40_000),
            rotation,
            sample_aspect: sar,
            color: if dovi_reshape { ColorInfo::from_params(par).with_dovi_reshape() } else { ColorInfo::from_params(par) },
            stream: stream_info,
            threads,
            field_order: par.field_order,
            hardware: if dovi_reshape { None } else { hardware },
        };
        let video_shared = shared.clone();
        match thread::Builder::new().name("lucky-vdec".into()).spawn(move || run_video_decoder(video_shared, backend, params)) {
            Ok(handle) => video_thread = Some(handle),
            Err(error) => {
                shared.fail(format!("cannot start the video decoder: {error}"));
                return;
            }
        }
    }

    if audio_output.is_some() {
        let waited = Instant::now();
        while !shared.audio_ready.load(Ordering::Acquire) && !shared.aborted() && waited.elapsed() < Duration::from_millis(1500) {
            thread::sleep(Duration::from_millis(2));
        }
    }
    let mut audio = AudioState {
        index: None,
        thread: None,
        stop: Arc::new(AtomicBool::new(false)),
        spare: audio_output,
        limits: (None, Limits::default()),
    };
    if let Some(index) = input.best_stream(sys::AVMediaType::AVMEDIA_TYPE_AUDIO) {
        audio.start(&input, &shared, index, has_video);
    }
    if !has_video && audio.index.is_none() {
        shared.fail("no playable video or audio track found".into());
        audio.stop(&shared);
        if let Some(mut output) = audio.spare.take() {
            output.close();
        }
        return;
    }

    *shared.fonts.lock().unwrap_or_else(|e| e.into_inner()) = input.font_attachments();
    {
        let start_ms = input.start_time_us() / 1000;
        shared.status().chapters = input
            .chapters()
            .into_iter()
            .map(|chapter| ChapterEntry {
                start_ms: (chapter.start_ms - start_ms).max(0),
                end_ms: chapter.end_ms.map(|end| (end - start_ms).max(0)),
                title: chapter.title,
            })
            .collect();
    }
    let mut subtitles =
        SubtitleState { index: None, decoder: None, mode: SubMode::Store, time_base: sys::AVRational { num: 0, den: 1 }, selected: None };
    subtitles.select(&input, &shared, initial_subtitle(&subtitle_tracks));
    let mut preroll = Preroll::new(shared.clone(), spare.filter(|_| input.seekable()));

    let wanted = |audio: &AudioState, subtitles: &SubtitleState| {
        let mut wanted: Vec<usize> = video_index.into_iter().collect();
        wanted.extend(audio.index);
        wanted.extend(subtitles.index);
        wanted
    };
    input.keep_only_streams(&wanted(&audio, &subtitles));
    {
        let mut status = shared.status();
        if status.state == PlayerState::Opening {
            status.state = if shared.paused.load(Ordering::Acquire) { PlayerState::Paused } else { PlayerState::Buffering };
        }
    }

    let start_us = input.start_time_us();
    let mut serial = shared.current_serial();
    let mut reached_end = false;
    while !shared.aborted() {
        if shared.seek_pending.swap(false, Ordering::AcqRel) {
            let request = shared.seek.lock().unwrap_or_else(|e| e.into_inner()).take();
            if let Some(request) = request {
                let changes: Vec<TrackRequest> =
                    std::mem::take(&mut *shared.track_requests.lock().unwrap_or_else(|e| e.into_inner()));
                for change in changes {
                    match change {
                        TrackRequest::Audio(index) => {
                            if index.is_none() && !has_video {
                                continue;
                            }
                            if index != audio.index {
                                audio.stop(&shared);
                                shared.audio_packets.flush(request.serial, None);
                                if let Some(index) = index {
                                    audio.start(&input, &shared, index, has_video);
                                }
                            }
                        }
                        TrackRequest::RestartAudio => {
                            if let Some(index) = audio.index {
                                audio.stop(&shared);
                                shared.audio_packets.flush(request.serial, None);
                                audio.start(&input, &shared, index, has_video);
                            }
                        }
                        TrackRequest::Subtitle(index) => {
                            if index != subtitles.selected {
                                subtitles.select(&input, &shared, index);
                            }
                        }
                        TrackRequest::ReloadSubtitle => {
                            if let Some(index) = subtitles.selected {
                                subtitles.select(&input, &shared, Some(index));
                            }
                        }
                    }
                }
                input.keep_only_streams(&wanted(&audio, &subtitles));
                preroll.request(subtitles.index.map(|stream| Job {
                    serial: request.serial,
                    target_us: request.target_us,
                    stream,
                    mode: subtitles.mode,
                }));
                if let Err(error) = input.seek(request.target_us) {
                    log::warn!("seek to {} us failed: {error}", request.target_us);
                }
                serial = request.serial;
                let target = request.exact.then_some(request.target_us);
                shared.video_packets.flush(serial, target);
                shared.audio_packets.flush(serial, target);
                if let Some(decoder) = subtitles.decoder.as_mut() {
                    decoder.flush();
                }
                reached_end = false;
            }
        }
        if reached_end {
            thread::sleep(Duration::from_millis(20));
            continue;
        }
        let mut packet = match Packet::new() {
            Ok(packet) => packet,
            Err(error) => {
                shared.fail(error.to_string());
                break;
            }
        };
        match input.read(&mut packet) {
            Ok(()) => {
                let index = packet.stream_index() as usize;
                let shared_ref = &shared;
                let cancelled = || shared_ref.aborted() || shared_ref.seek_pending.load(Ordering::Acquire);
                if Some(index) == video_index {
                    shared.video_packets.push(PacketItem::Packet { packet, serial }, cancelled);
                } else if Some(index) == audio.index {
                    shared.audio_packets.push(PacketItem::Packet { packet, serial }, cancelled);
                } else if Some(index) == subtitles.index {
                    match subtitles.mode {
                        SubMode::Ass => {
                            if let Some(pts_ms) = packet.pts().and_then(|pts| stream_to_ms(pts, subtitles.time_base)) {
                                let duration_ms = stream_to_ms(packet.duration(), subtitles.time_base).unwrap_or(0);
                                ass::event(&shared, packet.data(), pts_ms - start_us / 1000, duration_ms);
                            }
                        }
                        SubMode::Text => {
                            if let Some(decoded) = subtitles.decoder.as_mut().and_then(|decoder| decoder.decode(&packet)) {
                                ass::text_event(&shared, &decoded, start_us);
                            }
                        }
                        SubMode::Store => {
                            if let Some(decoded) = subtitles.decoder.as_mut().and_then(|decoder| decoder.decode(&packet)) {
                                shared.subtitles.push(decoded, start_us);
                            }
                        }
                    }
                }
            }
            Err(error) if error.is_again() => thread::sleep(Duration::from_millis(5)),
            Err(error) => {
                if shared.aborted() {
                    break;
                }
                if !error.is_eof() {
                    log::warn!("demuxer stopped: {error}");
                }
                let shared_ref = &shared;
                if video_index.is_some() {
                    shared.video_packets.push(PacketItem::End { serial }, || shared_ref.aborted());
                }
                if audio.index.is_some() {
                    shared.audio_packets.push(PacketItem::End { serial }, || shared_ref.aborted());
                }
                reached_end = true;
            }
        }
    }

    drop(preroll);
    shared.video_packets.abort();
    shared.audio_packets.abort();
    shared.video_frames.abort();
    if let Some(handle) = video_thread {
        let _ = handle.join();
    }
    if let Some(handle) = audio.thread.take() {
        if let Ok(mut output) = handle.join() {
            output.close();
        }
    }
    if let Some(mut output) = audio.spare.take() {
        output.close();
    }
}
