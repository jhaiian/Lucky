use lucky_player::{ActiveScaling, DisplayOptions, DownscalerMode, MotionConfig, Presenter, ScalerMode, VideoFrame, ViewTransform};

use crate::egl::monotonic_ns;
use crate::gles::GlesPresenter;
use crate::vsync::VsyncTracker;
use crate::vulkan::VulkanPresenter;
use crate::window::NativeWindow;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RendererPreference {
    OpenGl,
    Vulkan,
}

pub struct AutoPresenter {
    vulkan: Option<VulkanPresenter>,
    gles: GlesPresenter,
    window: Option<NativeWindow>,
    frame_rate: f32,
    generation: u32,
    smooth: bool,
    vsync: Option<VsyncTracker>,
    motion_hint: Option<f32>,
}

impl AutoPresenter {
    pub fn new(preference: RendererPreference) -> Self {
        Self {
            vulkan: (preference == RendererPreference::Vulkan).then(VulkanPresenter::new),
            gles: GlesPresenter::new(),
            window: None,
            frame_rate: 0.0,
            generation: 0,
            smooth: false,
            vsync: None,
            motion_hint: None,
        }
    }

    fn use_gles(&mut self, reason: &str) {
        if self.vulkan.is_none() {
            return;
        }
        log::info!("{reason}, switching renderer");
        if let Some(vulkan) = self.vulkan.as_mut() {
            vulkan.detach();
        }
        self.disable_vulkan();
        if let Some(window) = self.window.clone() {
            self.gles.set_window(Some(window));
        }
    }

    fn disable_vulkan(&mut self) {
        if self.vulkan.take().is_some() {
            self.generation += 1;
        }
    }

    fn require_gles_for(&mut self, frame: &VideoFrame) {
        if frame.color.dovi_reshape {
            self.use_gles("Dolby Vision reshaping runs on OpenGL ES");
        }
    }

    fn hint_frame_rate(&mut self, frame: &VideoFrame) {
        let Some(window) = &self.window else {
            return;
        };
        if frame.duration_us < 4_000 || frame.duration_us > 200_000 {
            return;
        }
        let fps = match self.motion_hint {
            Some(hint) => hint,
            None => (1_000_000.0 / frame.duration_us as f64 * 1000.0).round() as f32 / 1000.0,
        };
        if (fps - self.frame_rate).abs() > 0.05 {
            self.frame_rate = fps;
            if window.set_frame_rate(fps) {
                log::info!("requested a {fps} Hz display rate");
            }
        }
    }
}

impl Presenter for AutoPresenter {
    type Window = NativeWindow;

    fn set_window(&mut self, window: Option<NativeWindow>) {
        if let Some(vulkan) = self.vulkan.as_mut() {
            vulkan.detach();
        }
        self.gles.set_window(None);
        self.window = window.clone();
        self.frame_rate = 0.0;
        let Some(window) = window else {
            return;
        };
        if let Some(vulkan) = self.vulkan.as_mut() {
            match vulkan.attach(window.clone()) {
                Ok(()) => return,
                Err(error) => log::warn!("Vulkan is unavailable ({error}), using OpenGL ES"),
            }
            self.disable_vulkan();
        }
        self.gles.set_window(Some(window));
    }

    fn has_window(&self) -> bool {
        self.window.is_some()
    }

    fn present(&mut self, frame: &VideoFrame) -> bool {
        self.hint_frame_rate(frame);
        self.require_gles_for(frame);
        if let Some(vulkan) = self.vulkan.as_mut() {
            let drawn = vulkan.present(frame);
            if !vulkan.failed() {
                return drawn;
            }
            log::warn!("switching from Vulkan to OpenGL ES");
            self.disable_vulkan();
            if let Some(window) = self.window.clone() {
                self.gles.set_window(Some(window));
            }
        }
        self.gles.present(frame)
    }

    fn name(&self) -> Option<String> {
        match &self.vulkan {
            Some(vulkan) => vulkan.name(),
            None => self.gles.name(),
        }
    }

    fn generation(&self) -> u32 {
        self.generation
    }

    fn set_transform(&mut self, transform: ViewTransform) {
        if let Some(vulkan) = self.vulkan.as_mut() {
            vulkan.set_view_transform(transform);
        }
        self.gles.set_view_transform(transform);
    }

    fn set_scaler(&mut self, mode: ScalerMode) {
        if let Some(vulkan) = self.vulkan.as_mut() {
            vulkan.set_scaler_mode(mode);
        }
        self.gles.set_scaler_mode(mode);
    }

    fn set_downscaler(&mut self, mode: DownscalerMode) {
        if let Some(vulkan) = self.vulkan.as_mut() {
            vulkan.set_downscaler_mode(mode);
        }
        self.gles.set_downscaler_mode(mode);
    }

    fn active_scaling(&self) -> ActiveScaling {
        match &self.vulkan {
            Some(vulkan) => vulkan.active_scaling(),
            None => self.gles.last_scaling(),
        }
    }

    fn set_smooth_motion(&mut self, enabled: bool) {
        self.smooth = enabled;
        if !enabled {
            self.vsync = None;
            if let Some(vulkan) = self.vulkan.as_mut() {
                vulkan.release_smooth();
            }
        } else if self.vsync.is_none() {
            self.vsync = VsyncTracker::start();
            if self.vsync.is_none() {
                log::warn!("smooth motion unavailable: no Choreographer");
            }
        }
    }

    fn smooth_timing(&mut self) -> Option<(i64, i64, i64)> {
        if !self.smooth {
            return None;
        }
        let (last, period) = self.vsync.as_ref()?.timing()?;
        if self.vulkan.as_ref().is_some_and(|vulkan| vulkan.smooth_unavailable()) {
            self.use_gles("Vulkan smooth motion is unavailable");
        }
        let supported = match &self.vulkan {
            Some(vulkan) => vulkan.supports_blend(),
            None => self.gles.supports_blend(),
        };
        if !supported {
            return None;
        }
        Some((monotonic_ns(), last, period))
    }

    fn present_blend(&mut self, from: &VideoFrame, to: &VideoFrame, weight: f32, delay_us: i64) -> bool {
        self.hint_frame_rate(to);
        self.require_gles_for(to);
        if let Some(vulkan) = self.vulkan.as_mut() {
            let drawn = vulkan.present_blend(from, to, weight, delay_us);
            let failed = vulkan.failed();
            let unavailable = vulkan.smooth_unavailable();
            if !failed && !unavailable {
                return drawn;
            }
            if failed {
                log::warn!("switching from Vulkan to OpenGL ES");
                self.disable_vulkan();
                if let Some(window) = self.window.clone() {
                    self.gles.set_window(Some(window));
                }
            } else {
                self.use_gles("Vulkan smooth motion is unavailable");
                if drawn {
                    return true;
                }
            }
        }
        self.gles.present_blend(from, to, weight, delay_us)
    }

    fn set_motion(&mut self, config: MotionConfig) {
        self.motion_hint = (config.level > 0).then_some(config.hint_fps);
        if let Some(vulkan) = self.vulkan.as_mut() {
            vulkan.set_motion_config(config);
        }
        self.gles.set_motion_config(config);
    }

    fn capture(&mut self, frame: &VideoFrame, width: i32, height: i32) -> Option<Vec<u8>> {
        match self.vulkan.as_mut() {
            Some(vulkan) => vulkan.capture(frame, width, height),
            None => self.gles.capture_frame(frame, width, height),
        }
    }

    fn check_duplicate(&mut self, from: &VideoFrame, to: &VideoFrame) -> Option<bool> {
        if let Some(vulkan) = self.vulkan.as_mut() {
            let result = vulkan.check_duplicate(from, to);
            if vulkan.smooth_unavailable() {
                self.use_gles("Vulkan smooth motion is unavailable");
                return None;
            }
            return result;
        }
        self.gles.check_duplicate_frames(from, to)
    }

    fn set_quality_cap(&mut self, cap: f32) {
        if let Some(vulkan) = self.vulkan.as_mut() {
            vulkan.set_quality_cap(cap);
        }
        self.gles.set_quality_cap_level(cap);
    }

    fn set_antiring(&mut self, strength: f32) {
        if let Some(vulkan) = self.vulkan.as_mut() {
            vulkan.set_antiring(strength);
        }
        self.gles.set_antiring_strength(strength);
    }

    fn set_display(&mut self, options: DisplayOptions) {
        if let Some(vulkan) = self.vulkan.as_mut() {
            vulkan.set_display_options(options);
        }
        self.gles.set_display_options(options);
    }

    fn output_mode(&self) -> i32 {
        match &self.vulkan {
            Some(vulkan) => vulkan.output(),
            None => self.gles.output(),
        }
    }

    fn present_at(&mut self, frame: &VideoFrame, delay_us: i64) -> bool {
        self.hint_frame_rate(frame);
        self.require_gles_for(frame);
        if let Some(vulkan) = self.vulkan.as_mut() {
            let drawn = vulkan.present_paced(frame, delay_us);
            if !vulkan.failed() {
                return drawn;
            }
            log::warn!("switching from Vulkan to OpenGL ES");
            self.disable_vulkan();
            if let Some(window) = self.window.clone() {
                self.gles.set_window(Some(window));
            }
        }
        self.gles.present_paced(frame, delay_us)
    }
}
