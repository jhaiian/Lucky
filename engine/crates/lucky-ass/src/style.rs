pub const DEFAULT_PLAY_RES: (i32, i32) = (1920, 1080);

const FALLBACK_FONT: &str = "sans-serif";
const SIDE_MARGIN: f64 = 0.04;

#[derive(Clone, Debug, PartialEq)]
pub struct SubtitleStyle {
    pub font: String,
    pub size: f64,
    pub primary: u32,
    pub outline: u32,
    pub back: u32,
    pub bold: bool,
    pub outline_width: f64,
    pub shadow: f64,
    pub margin: f64,
    pub boxed: bool,
}

impl Default for SubtitleStyle {
    fn default() -> Self {
        Self {
            font: FALLBACK_FONT.to_string(),
            size: 0.06,
            primary: 0xFFFF_FFFF,
            outline: 0xFF00_0000,
            back: 0xCC00_0000,
            bold: false,
            outline_width: 0.0036,
            shadow: 0.0,
            margin: 0.06,
            boxed: false,
        }
    }
}

pub fn ass_colour(argb: u32) -> String {
    let alpha = 255 - ((argb >> 24) & 0xFF);
    let red = (argb >> 16) & 0xFF;
    let green = (argb >> 8) & 0xFF;
    let blue = argb & 0xFF;
    format!("&H{alpha:02X}{blue:02X}{green:02X}{red:02X}")
}

fn clean_font(font: &str) -> String {
    let cleaned: String = font.chars().filter(|c| !c.is_control() && *c != ',').collect();
    let cleaned = cleaned.trim();
    if cleaned.is_empty() { FALLBACK_FONT.to_string() } else { cleaned.to_string() }
}

fn ratio(value: f64, max: f64) -> f64 {
    if value.is_finite() { value.clamp(0.0, max) } else { 0.0 }
}

impl SubtitleStyle {
    pub fn header(&self, play_res_x: i32, play_res_y: i32) -> String {
        let (width, height) = if play_res_x > 0 && play_res_y > 0 { (play_res_x, play_res_y) } else { DEFAULT_PLAY_RES };
        let height_f = height as f64;
        let font = clean_font(&self.font);
        let size = (ratio(self.size, 0.25) * height_f).max(1.0);
        let outline_width = ratio(self.outline_width, 0.1) * height_f;
        let shadow = ratio(self.shadow, 0.1) * height_f;
        let margin_v = (ratio(self.margin, 0.5) * height_f).round() as i32;
        let margin_x = (width as f64 * SIDE_MARGIN).round() as i32;
        let primary = ass_colour(self.primary);
        let outline = ass_colour(self.outline);
        let back = ass_colour(self.back);
        let bold = if self.bold { -1 } else { 0 };
        let border_style = if self.boxed { 3 } else { 1 };
        format!(
            "[Script Info]\nScriptType: v4.00+\nPlayResX: {width}\nPlayResY: {height}\nScaledBorderAndShadow: yes\nWrapStyle: 0\n\n\
             [V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n\
             Style: Default,{font},{size:.2},{primary},{primary},{outline},{back},{bold},0,0,0,100,100,0,0,{border_style},{outline_width:.2},{shadow:.2},2,{margin_x},{margin_x},{margin_v},1\n\n\
             [Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn converts_argb_to_ass_colour() {
        assert_eq!(ass_colour(0xFFFF_FFFF), "&H00FFFFFF");
        assert_eq!(ass_colour(0xFF11_2233), "&H00332211");
        assert_eq!(ass_colour(0x8000_0000), "&H7F000000");
        assert_eq!(ass_colour(0x0000_0000), "&HFF000000");
    }

    #[test]
    fn header_uses_video_size_and_style() {
        let style = SubtitleStyle { size: 0.05, margin: 0.1, bold: true, boxed: true, ..SubtitleStyle::default() };
        let header = style.header(1440, 1080);
        assert!(header.contains("PlayResX: 1440\nPlayResY: 1080\n"));
        assert!(header.contains("ScaledBorderAndShadow: yes"));
        assert!(header.contains("WrapStyle: 0"));
        assert!(header.contains("Style: Default,sans-serif,54.00,&H00FFFFFF,&H00FFFFFF,&H00000000,&H33000000,-1,0,0,0,100,100,0,0,3,"));
        assert!(header.contains(",2,58,58,108,1\n"));
        assert!(header.ends_with("Effect, Text\n"));
    }

    #[test]
    fn header_falls_back_to_default_resolution() {
        let header = SubtitleStyle::default().header(0, 0);
        assert!(header.contains("PlayResX: 1920\nPlayResY: 1080\n"));
        assert!(header.contains("Style: Default,sans-serif,64.80,"));
    }

    #[test]
    fn header_sanitizes_font_and_values() {
        let style = SubtitleStyle { font: "Bad,Font\n".into(), size: f64::NAN, shadow: -3.0, ..SubtitleStyle::default() };
        let header = style.header(1280, 720);
        assert!(header.contains("Style: Default,BadFont,1.00,"));
        assert!(!header.contains("-3.00"));
    }
}
