use std::ffi::c_void;
use std::os::fd::{FromRawFd, OwnedFd};

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JFloatArray, JIntArray, JObject, JString};
use jni::sys::{jboolean, jfloat, jint, jlong};
use lucky_android::MediaCodecFactory;
use lucky_player::{
    AudioEffects, AudioOutput, DisplayLuminance, DisplayOptions, DownscalerMode, EQ_BANDS, FitMode, HardwareDecoderFactory, MotionSettings, Player,
    RenderOutcome, ScalerMode, ScreenshotPoll, SubtitleStyle, Thumbnailer, ViewTransform,
};

use crate::audio_sink::JniAudioOutput;
use lucky_render::{AutoPresenter, NativeWindow, RendererPreference};

type AndroidPlayer = Player<NativeWindow>;

fn with_player<T: Default>(handle: jlong, action: impl FnOnce(&AndroidPlayer) -> T) -> T {
    if handle == 0 {
        return T::default();
    }
    let player = unsafe { &*(handle as *const AndroidPlayer) };
    action(player)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerCreate<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    fd: jint,
    sink: JObject<'caller>,
    prefer_hardware: jboolean,
    renderer: jint,
    autoplay: jboolean,
    preload: jboolean,
) -> jlong {
    unowned_env
        .with_env(|env| -> jni::errors::Result<_> {
            crate::init();
            if fd < 0 {
                return Ok(0);
            }
            let owned = unsafe { OwnedFd::from_raw_fd(fd) };
            let audio: Option<Box<dyn AudioOutput>> = if sink.as_raw().is_null() {
                None
            } else {
                let vm = env.get_java_vm()?;
                let global = env.new_global_ref(&sink)?;
                Some(Box::new(JniAudioOutput::new(vm, global)))
            };
            let hardware: Option<Box<dyn HardwareDecoderFactory>> =
                if prefer_hardware { Some(Box::new(MediaCodecFactory)) } else { None };
            let preference = if renderer == 1 { RendererPreference::Vulkan } else { RendererPreference::OpenGl };
            let created = if preload {
                Player::preload(owned, AutoPresenter::new(preference), audio, hardware)
            } else {
                Player::open(owned, AutoPresenter::new(preference), audio, hardware, autoplay)
            };
            match created {
                Ok(player) => Ok(Box::into_raw(Box::new(player)) as jlong),
                Err(error) => {
                    log::error!("cannot start the player: {error}");
                    Ok(0)
                }
            }
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerPlay<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.play());
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerActivate<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.activate());
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerPause<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.pause());
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSeek<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    position_ms: jlong,
    exact: jboolean,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.seek(position_ms, exact));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerStepFrame<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    forward: jboolean,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.step_frame(forward));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerRequestScreenshot<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
) -> jlong {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> { Ok(with_player(handle, |player| player.request_screenshot() as jlong)) })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerPollScreenshot<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    ticket: jlong,
) -> JIntArray<'caller> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<_> {
            let poll = with_player(handle, |player| Some(player.poll_screenshot(ticket.max(0) as u64)));
            let data: Vec<i32> = match poll {
                Some(ScreenshotPoll::Pending) => vec![0],
                Some(ScreenshotPoll::Done(Some(shot))) => {
                    let mut data = Vec::with_capacity(shot.rgba.len() / 4 + 3);
                    data.extend([1, shot.width, shot.height]);
                    data.extend(shot.rgba.chunks_exact(4).map(|p| i32::from_be_bytes([p[3], p[0], p[1], p[2]])));
                    data
                }
                _ => vec![2],
            };
            let array = env.new_int_array(data.len())?;
            array.set_region(env, 0, &data)?;
            Ok(array)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSetLoopRange<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    start_ms: jlong,
    end_ms: jlong,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.set_loop_range(start_ms, end_ms));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerClearLoopRange<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.clear_loop_range());
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSetSurface<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    surface: JObject<'caller>,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<_> {
            let window = if surface.as_raw().is_null() {
                None
            } else {
                unsafe { NativeWindow::from_surface(env.get_raw() as *mut c_void, surface.as_raw() as *mut c_void) }
            };
            with_player(handle, |player| player.set_window(window));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerRedraw<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.redraw());
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerStatus<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<_> {
            let json = with_player(handle, |player| serde_json::to_string(&player.status()).unwrap_or_default());
            env.new_string(if json.is_empty() { "{}".to_string() } else { json })
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerRelease<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            if handle != 0 {
                drop(unsafe { Box::from_raw(handle as *mut AndroidPlayer) });
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSelectAudio<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    index: jint,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.select_audio(Some(index)));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSelectSubtitle<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    index: jint,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.select_subtitle(Some(index)));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSubtitles<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    after_id: jlong,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<_> {
            let json = with_player(handle, |player| {
                serde_json::to_string(&player.subtitles_since(after_id.max(0) as u64)).unwrap_or_default()
            });
            env.new_string(if json.is_empty() { "{}".to_string() } else { json })
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSubtitleBitmap<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    id: jlong,
    rect: jint,
) -> JIntArray<'caller> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<_> {
            let bitmap = with_player(handle, |player| player.subtitle_bitmap(id.max(0) as u64, rect.max(0) as usize));
            let Some((width, height, pixels)) = bitmap else {
                return Ok(JIntArray::default());
            };
            let mut data = Vec::with_capacity(pixels.len() + 2);
            data.push(width);
            data.push(height);
            data.extend(pixels.iter().map(|p| *p as i32));
            let array = env.new_int_array(data.len())?;
            array.set_region(env, 0, &data)?;
            Ok(array)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSetViewTransform<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    fit: jint,
    aspect: jfloat,
    zoom: jfloat,
    zoom_x: jfloat,
    zoom_y: jfloat,
    pan_x: jfloat,
    pan_y: jfloat,
    turns: jint,
    mirror: jboolean,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            let transform = ViewTransform {
                fit: FitMode::from_code(fit),
                aspect,
                zoom,
                zoom_x,
                zoom_y,
                pan_x,
                pan_y,
                turns,
                mirror,
            };
            with_player(handle, |player| player.set_view_transform(transform));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSetSpeed<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    speed: jfloat,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.set_speed(speed as f64));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSetScaler<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    mode: jint,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.set_scaler(ScalerMode::from_code(mode)));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSetDownscaler<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    mode: jint,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.set_downscaler(DownscalerMode::from_code(mode)));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSetSmoothMotion<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    mode: jint,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.set_smooth_motion(mode.clamp(0, 3) as u8));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSetAudioEffects<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    delay_ms: jint,
    gain: jfloat,
    night: jboolean,
    dialogue: jboolean,
    channels: jint,
    balance: jfloat,
    normalize: jboolean,
    bands: JFloatArray<'caller>,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<_> {
            let mut eq = [0f32; EQ_BANDS];
            if !bands.is_null() {
                let length = bands.len(env)?;
                let mut values = vec![0f32; length];
                bands.get_region(env, 0, &mut values)?;
                for (slot, value) in eq.iter_mut().zip(values) {
                    *slot = value;
                }
            }
            let effects = AudioEffects {
                delay_ms,
                gain,
                night,
                dialogue,
                channels: channels.clamp(0, 2) as u8,
                balance,
                normalize,
                eq,
            };
            with_player(handle, |player| player.set_audio_effects(effects));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSetAudioOutput<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    mode: jint,
    passthrough: jint,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| {
                player.set_audio_output(mode.clamp(0, 1) as u8, passthrough.clamp(0, 0x1F) as u8)
            });
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerBeginAudio<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.begin_audio());
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerAudioRouteChanged<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.audio_route_changed());
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSetMotion<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    level: jint,
    protection: jint,
    scene: jint,
    target: jint,
    fps: jint,
    anime: jboolean,
    low_fps_only: jboolean,
    learned: jint,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            let settings = MotionSettings {
                level: level.clamp(0, 3) as u8,
                protection: protection.clamp(0, 3) as u8,
                scene: scene.clamp(0, 2) as u8,
                target: target.clamp(0, 5) as u8,
                fps: fps.clamp(0, 1000) as u16,
                anime,
                low_fps_only,
                learned: learned.clamp(0, 3) as u8,
            };
            with_player(handle, |player| player.set_motion(settings));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSetAntiring<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    strength: jfloat,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.set_antiring(strength));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSetPerformance<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    auto_quality: jboolean,
    fast_decode: jboolean,
    software_fallback: jboolean,
    learned_cost: jfloat,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.set_performance(auto_quality, fast_decode, software_fallback, learned_cost));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSetDeinterlace<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    mode: jint,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.set_deinterlace(mode.clamp(0, 2) as u8));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSetDisplay<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    hdr: jboolean,
    wide_color: jboolean,
    adaptive: jboolean,
    max_nits: jfloat,
    average_nits: jfloat,
    min_nits: jfloat,
    headroom: jfloat,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            let finite = |value: f32| if value.is_finite() && value > 0.0 { value } else { 0.0 };
            let luminance = DisplayLuminance {
                max_nits: finite(max_nits),
                average_nits: finite(average_nits),
                min_nits: finite(min_nits),
                headroom: finite(headroom),
            };
            let options = DisplayOptions { hdr, wide_color, adaptive, luminance };
            with_player(handle, |player| player.set_display(options));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerAddSubtitle<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    fd: jint,
    name: JString<'caller>,
) -> jint {
    unowned_env
        .with_env(|env| -> jni::errors::Result<_> {
            if fd < 0 {
                return Ok(-1);
            }
            let owned = unsafe { OwnedFd::from_raw_fd(fd) };
            let label = if name.as_raw().is_null() { String::new() } else { name.try_to_string(env)? };
            Ok(with_player(handle, |player| player.add_external_subtitle(owned, &label)).unwrap_or(-1))
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerRenderSubtitles<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    time_ms: jlong,
    width: jint,
    height: jint,
    scale: jfloat,
    lift: jfloat,
    force: jboolean,
) -> JIntArray<'caller> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<_> {
            let outcome =
                with_player(handle, |player| Some(player.render_subtitles(time_ms, width, height, scale as f64, lift as f64, force)));
            let data: Vec<i32> = match outcome {
                Some(RenderOutcome::Image(image)) => {
                    let mut data = Vec::with_capacity(image.argb.len() + 5);
                    data.extend([1, image.x, image.y, image.width, image.height]);
                    data.extend(image.argb.iter().map(|p| *p as i32));
                    data
                }
                Some(RenderOutcome::Empty) => vec![2],
                _ => vec![0],
            };
            let array = env.new_int_array(data.len())?;
            array.set_region(env, 0, &data)?;
            Ok(array)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSetSubtitleStyle<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    font: JString<'caller>,
    size: jfloat,
    primary: jint,
    outline: jint,
    back: jint,
    bold: jboolean,
    outline_width: jfloat,
    shadow: jfloat,
    margin: jfloat,
    boxed: jboolean,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<_> {
            let mut style = SubtitleStyle {
                size: size as f64,
                primary: primary as u32,
                outline: outline as u32,
                back: back as u32,
                bold,
                outline_width: outline_width as f64,
                shadow: shadow as f64,
                margin: margin as f64,
                boxed,
                ..SubtitleStyle::default()
            };
            if !font.as_raw().is_null() {
                style.font = font.try_to_string(env)?;
            }
            with_player(handle, |player| player.set_subtitle_style(style));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativePlayerSetTextSubtitles<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    libass: jboolean,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            with_player(handle, |player| player.set_text_subtitles(libass));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativeThumbnailerCreate<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    fd: jint,
) -> jlong {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            crate::init();
            if fd < 0 {
                return Ok(0);
            }
            let owned = unsafe { OwnedFd::from_raw_fd(fd) };
            match Thumbnailer::open(owned) {
                Ok(thumbnailer) => Ok(Box::into_raw(Box::new(thumbnailer)) as jlong),
                Err(error) => {
                    log::info!("seek previews are unavailable: {error}");
                    Ok(0)
                }
            }
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativeThumbnailerFrame<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
    position_ms: jlong,
    max_width: jint,
    max_height: jint,
) -> JIntArray<'caller> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<_> {
            if handle == 0 {
                return Ok(JIntArray::default());
            }
            let thumbnailer = unsafe { &mut *(handle as *mut Thumbnailer) };
            let Some(thumbnail) = thumbnailer.frame(position_ms, max_width, max_height) else {
                return Ok(JIntArray::default());
            };
            let mut data = Vec::with_capacity(thumbnail.pixels.len() + 2);
            data.push(thumbnail.width);
            data.push(thumbnail.height);
            data.extend_from_slice(&thumbnail.pixels);
            let array = env.new_int_array(data.len())?;
            array.set_region(env, 0, &data)?;
            Ok(array)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativeThumbnailerRelease<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    handle: jlong,
) {
    unowned_env
        .with_env(|_env| -> jni::errors::Result<_> {
            if handle != 0 {
                drop(unsafe { Box::from_raw(handle as *mut Thumbnailer) });
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}
