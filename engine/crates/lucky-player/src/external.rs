use std::os::fd::OwnedFd;

use lucky_ffmpeg::{CodecParameters, DecodedSubtitle, Input, Packet, SubtitleDecoder, c_str, sys};

use crate::status::TrackEntry;

pub const EXTERNAL_BASE: usize = 10_000;

pub struct AssEvent {
    pub data: Vec<u8>,
    pub start_ms: i64,
    pub duration_ms: i64,
}

pub struct ExternalSubtitle {
    pub entry: TrackEntry,
    pub ass_header: Option<Vec<u8>>,
    pub ass_events: Vec<AssEvent>,
    pub cues: Vec<DecodedSubtitle>,
    pub text: bool,
}

fn to_ms(value: i64, time_base: sys::AVRational) -> i64 {
    if value == sys::AV_NOPTS_VALUE || time_base.den == 0 {
        return 0;
    }
    (value as i128 * time_base.num as i128 * 1000 / time_base.den as i128) as i64
}

pub fn language_from_name(name: &str) -> Option<String> {
    let stem = name.rsplit_once('.').map(|(stem, _)| stem).unwrap_or(name);
    stem.split('.')
        .skip(1)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .map(|part| part.to_ascii_lowercase())
        .find(|part| {
            (part.len() == 2 || part.len() == 3)
                && part.chars().all(|c| c.is_ascii_alphabetic())
                && !matches!(part.as_str(), "sdh" | "cc" | "mkv" | "mp4" | "avi" | "mov" | "wmv" | "m4v" | "ts" | "flv")
        })
}

pub fn load(fd: OwnedFd, name: &str) -> Result<ExternalSubtitle, String> {
    let mut input = Input::open_fd(fd).map_err(|e| e.to_string())?;
    let found = input.streams().iter().enumerate().find_map(|(index, &stream)| {
        if stream.is_null() {
            return None;
        }
        let stream = unsafe { &*stream };
        if stream.codecpar.is_null() || unsafe { (*stream.codecpar).codec_type } != sys::AVMediaType::AVMEDIA_TYPE_SUBTITLE {
            return None;
        }
        Some((index, stream as *const sys::AVStream))
    });
    let Some((index, stream)) = found else {
        return Err("the file has no subtitles".into());
    };
    let stream = unsafe { &*stream };
    let par = unsafe { &*stream.codecpar };
    let time_base = stream.time_base;
    let is_ass = par.codec_id == sys::AVCodecID::AV_CODEC_ID_ASS || par.codec_id == sys::AVCodecID::AV_CODEC_ID_SSA;
    let ass_header = (is_ass && !par.extradata.is_null() && par.extradata_size > 0)
        .then(|| unsafe { std::slice::from_raw_parts(par.extradata, par.extradata_size as usize) }.to_vec());
    let codec = c_str(unsafe { sys::avcodec_get_name(par.codec_id) }).unwrap_or_default();
    let mut decoder = CodecParameters::copy_from(stream.codecpar)
        .and_then(|params| SubtitleDecoder::open(&params, time_base))
        .map_err(|e| log::warn!("external subtitle decoder unavailable: {e}"))
        .ok();
    let text = decoder.as_ref().is_some_and(|decoder| decoder.is_text());
    let mut ass_events = Vec::new();
    let mut cues = Vec::new();
    loop {
        let mut packet = Packet::new().map_err(|e| e.to_string())?;
        match input.read(&mut packet) {
            Ok(()) => {
                if packet.stream_index() as usize != index {
                    continue;
                }
                if ass_header.is_some() {
                    let start_ms = packet.pts().map(|pts| to_ms(pts, time_base)).unwrap_or(0);
                    let duration_ms = to_ms(packet.duration(), time_base);
                    ass_events.push(AssEvent { data: packet.data().to_vec(), start_ms, duration_ms });
                }
                if let Some(decoded) = decoder.as_mut().and_then(|d| d.decode(&packet)) {
                    cues.push(decoded);
                }
            }
            Err(error) if error.is_again() => continue,
            Err(_) => break,
        }
    }
    if ass_events.is_empty() && cues.is_empty() {
        return Err("no subtitle lines could be read".into());
    }
    let lower = name.to_ascii_lowercase();
    let entry = TrackEntry {
        index: 0,
        codec,
        language: language_from_name(name),
        title: Some(name.to_string()),
        channels: None,
        is_default: false,
        is_forced: lower.contains(".forced.") || lower.contains("[forced]"),
        external: true,
    };
    Ok(ExternalSubtitle { entry, ass_header, ass_events, cues, text })
}

#[cfg(test)]
mod tests {
    use super::language_from_name;

    #[test]
    fn reads_language_from_file_name() {
        assert_eq!(language_from_name("Movie.en.srt"), Some("en".into()));
        assert_eq!(language_from_name("Movie.2024.eng.forced.srt"), Some("eng".into()));
        assert_eq!(language_from_name("Movie.srt"), None);
        assert_eq!(language_from_name("Show.S01E01.ass"), None);
        assert_eq!(language_from_name("Movie.mkv.srt"), None);
    }
}
