use std::os::fd::{FromRawFd, OwnedFd};
use std::os::raw::c_void;
use std::sync::Once;

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JObject, JString};
use jni::sys::{JNI_VERSION_1_6, JavaVM, jint};
use serde::Serialize;

#[cfg(target_os = "android")]
mod audio_sink;
#[cfg(target_os = "android")]
mod player;

static INIT: Once = Once::new();

pub(crate) fn init() {
    INIT.call_once(|| {
        #[cfg(target_os = "android")]
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("LuckyEngine"),
        );
        lucky_ffmpeg::install_log_bridge(false);
        log::info!("Lucky engine {} loaded ({})", env!("CARGO_PKG_VERSION"), std::env::consts::ARCH);
    });
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct EngineInfo {
    engine: &'static str,
    abi: &'static str,
    #[serde(flatten)]
    libraries: lucky_ffmpeg::Versions,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ProbeResult {
    #[serde(skip_serializing_if = "Option::is_none")]
    media: Option<lucky_ffmpeg::MediaInfo>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

fn abi_name() -> &'static str {
    if cfg!(target_arch = "aarch64") {
        "arm64-v8a"
    } else if cfg!(target_arch = "arm") {
        "armeabi-v7a"
    } else {
        std::env::consts::ARCH
    }
}

fn probe(fd: jint) -> ProbeResult {
    if fd < 0 {
        return ProbeResult { media: None, error: Some("invalid file descriptor".into()) };
    }
    let owned = unsafe { OwnedFd::from_raw_fd(fd) };
    match lucky_ffmpeg::Input::open_fd(owned) {
        Ok(input) => ProbeResult { media: Some(input.media_info()), error: None },
        Err(error) => {
            log::warn!("probe failed: {error}");
            ProbeResult { media: None, error: Some(error.to_string()) }
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(_vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    init();
    JNI_VERSION_1_6
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativeEngineInfo<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<_> {
            init();
            let info = EngineInfo { engine: env!("CARGO_PKG_VERSION"), abi: abi_name(), libraries: lucky_ffmpeg::versions() };
            let json = serde_json::to_string(&info).unwrap_or_else(|_| "{}".into());
            env.new_string(json)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jhaiian_lucky_engine_LuckyEngine_nativeProbe<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    fd: jint,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| -> jni::errors::Result<_> {
            init();
            let result = probe(fd);
            let json = serde_json::to_string(&result)
                .unwrap_or_else(|error| format!("{{\"error\":{:?}}}", error.to_string()));
            env.new_string(json)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}
