use std::fs;
use std::path::{Path, PathBuf};

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum Script {
    Cjk,
    Devanagari,
    Bengali,
    Gurmukhi,
    Gujarati,
    Oriya,
    Tamil,
    Telugu,
    Kannada,
    Malayalam,
    Sinhala,
    Thai,
    Lao,
    Myanmar,
    Khmer,
    Arabic,
    Hebrew,
    Armenian,
    Georgian,
    Ethiopic,
    Tibetan,
}

pub fn script_of(ch: char) -> Option<Script> {
    let code = ch as u32;
    let script = match code {
        0x0530..=0x058F => Script::Armenian,
        0x0590..=0x05FF | 0xFB1D..=0xFB4F => Script::Hebrew,
        0x0600..=0x06FF | 0x0750..=0x077F | 0x08A0..=0x08FF | 0xFB50..=0xFDFF | 0xFE70..=0xFEFF => Script::Arabic,
        0x0900..=0x097F | 0xA8E0..=0xA8FF => Script::Devanagari,
        0x0980..=0x09FF => Script::Bengali,
        0x0A00..=0x0A7F => Script::Gurmukhi,
        0x0A80..=0x0AFF => Script::Gujarati,
        0x0B00..=0x0B7F => Script::Oriya,
        0x0B80..=0x0BFF => Script::Tamil,
        0x0C00..=0x0C7F => Script::Telugu,
        0x0C80..=0x0CFF => Script::Kannada,
        0x0D00..=0x0D7F => Script::Malayalam,
        0x0D80..=0x0DFF => Script::Sinhala,
        0x0E00..=0x0E7F => Script::Thai,
        0x0E80..=0x0EFF => Script::Lao,
        0x0F00..=0x0FFF => Script::Tibetan,
        0x1000..=0x109F => Script::Myanmar,
        0x10A0..=0x10FF => Script::Georgian,
        0x1200..=0x139F => Script::Ethiopic,
        0x1780..=0x17FF => Script::Khmer,
        0x1100..=0x11FF
        | 0x2E80..=0x2FDF
        | 0x3000..=0x30FF
        | 0x3100..=0x318F
        | 0x31A0..=0x31FF
        | 0x3200..=0x4DBF
        | 0x4E00..=0x9FFF
        | 0xA960..=0xA97F
        | 0xAC00..=0xD7FF
        | 0xF900..=0xFAFF
        | 0xFE30..=0xFE4F
        | 0xFF00..=0xFFEF
        | 0x20000..=0x3134F => Script::Cjk,
        _ => return None,
    };
    Some(script)
}

fn keys(script: Script) -> &'static [&'static str] {
    match script {
        Script::Cjk => &["NotoSansCJK", "NotoSerifCJK", "NotoSansSC", "NotoSansJP", "DroidSansFallback"],
        Script::Devanagari => &["NotoSansDevanagari", "NotoSerifDevanagari"],
        Script::Bengali => &["NotoSansBengali", "NotoSerifBengali"],
        Script::Gurmukhi => &["NotoSansGurmukhi"],
        Script::Gujarati => &["NotoSansGujarati"],
        Script::Oriya => &["NotoSansOriya"],
        Script::Tamil => &["NotoSansTamil"],
        Script::Telugu => &["NotoSansTelugu"],
        Script::Kannada => &["NotoSansKannada"],
        Script::Malayalam => &["NotoSansMalayalam"],
        Script::Sinhala => &["NotoSansSinhala"],
        Script::Thai => &["NotoSansThai", "NotoSerifThai"],
        Script::Lao => &["NotoSansLao"],
        Script::Myanmar => &["NotoSansMyanmar"],
        Script::Khmer => &["NotoSansKhmer"],
        Script::Arabic => &["NotoNaskhArabic", "NotoSansArabic", "DroidNaskh"],
        Script::Hebrew => &["NotoSansHebrew"],
        Script::Armenian => &["NotoSansArmenian"],
        Script::Georgian => &["NotoSansGeorgian"],
        Script::Ethiopic => &["NotoSansEthiopic"],
        Script::Tibetan => &["NotoSerifTibetan", "NotoSansTibetan"],
    }
}

fn score(name: &str) -> i32 {
    let mut score = 0;
    if name.contains("Regular") {
        score += 4;
    }
    if name.contains("VF") {
        score += 3;
    }
    if name.contains("UI") {
        score -= 1;
    }
    if name.contains("Serif") {
        score -= 1;
    }
    if name.contains("Bold") || name.contains("Light") || name.contains("Thin") || name.contains("Medium") {
        score -= 6;
    }
    score
}

const MAX_FONT_BYTES: u64 = 64 * 1024 * 1024;

pub struct SystemFonts {
    files: Vec<PathBuf>,
}

impl SystemFonts {
    pub fn scan() -> Self {
        let mut files = Vec::new();
        for dir in ["/system/fonts", "/product/fonts"] {
            if let Ok(entries) = fs::read_dir(Path::new(dir)) {
                for entry in entries.flatten() {
                    let path = entry.path();
                    let extension = path.extension().and_then(|e| e.to_str()).unwrap_or("").to_ascii_lowercase();
                    if matches!(extension.as_str(), "ttf" | "otf" | "ttc") {
                        files.push(path);
                    }
                }
            }
        }
        Self { files }
    }

    fn name_of(path: &Path) -> &str {
        path.file_name().and_then(|n| n.to_str()).unwrap_or("")
    }

    pub fn default_font(&self) -> Option<PathBuf> {
        for wanted in ["Roboto-Regular.ttf", "RobotoStatic-Regular.ttf", "DroidSans.ttf"] {
            if let Some(path) = self.files.iter().find(|p| Self::name_of(p) == wanted) {
                return Some(path.clone());
            }
        }
        self.files.iter().filter(|p| Self::name_of(p).contains("Roboto")).max_by_key(|p| score(Self::name_of(p))).cloned()
    }

    pub fn find(&self, script: Script) -> Option<PathBuf> {
        for key in keys(script) {
            let best = self
                .files
                .iter()
                .filter(|p| Self::name_of(p).contains(key))
                .max_by(|a, b| {
                    score(Self::name_of(a))
                        .cmp(&score(Self::name_of(b)))
                        .then_with(|| Self::name_of(b).len().cmp(&Self::name_of(a).len()))
                });
            if let Some(path) = best {
                return Some(path.clone());
            }
        }
        None
    }

    pub fn load(&self, script: Script) -> Option<(String, Vec<u8>)> {
        let path = self.find(script)?;
        let size = fs::metadata(&path).ok()?.len();
        if size == 0 || size > MAX_FONT_BYTES {
            return None;
        }
        let data = fs::read(&path).ok()?;
        Some((path.to_string_lossy().into_owned(), data))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies_scripts() {
        assert_eq!(script_of('a'), None);
        assert_eq!(script_of('я'), None);
        assert_eq!(script_of('日'), Some(Script::Cjk));
        assert_eq!(script_of('あ'), Some(Script::Cjk));
        assert_eq!(script_of('한'), Some(Script::Cjk));
        assert_eq!(script_of('क'), Some(Script::Devanagari));
        assert_eq!(script_of('ب'), Some(Script::Arabic));
        assert_eq!(script_of('ש'), Some(Script::Hebrew));
        assert_eq!(script_of('ก'), Some(Script::Thai));
    }

    #[test]
    fn prefers_regular_files() {
        assert!(score("NotoSansDevanagari-Regular.otf") > score("NotoSansDevanagari-Bold.otf"));
        assert!(score("NotoSansThai-Regular.ttf") > score("NotoSansThaiUI-Regular.ttf"));
    }
}
