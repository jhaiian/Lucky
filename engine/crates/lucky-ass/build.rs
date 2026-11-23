use std::env;
use std::fs;
use std::path::{Path, PathBuf};

fn android_abi(target: &str) -> Option<(&'static str, &'static str)> {
    if target.starts_with("aarch64-linux-android") {
        Some(("arm64-v8a", "aarch64-linux-android"))
    } else if target.starts_with("armv7-linux-androideabi") || target.starts_with("thumbv7neon-linux-androideabi") {
        Some(("armeabi-v7a", "armv7a-linux-androideabi"))
    } else {
        None
    }
}

fn host_tag() -> &'static str {
    if cfg!(target_os = "macos") {
        "darwin-x86_64"
    } else if cfg!(target_os = "windows") {
        "windows-x86_64"
    } else {
        "linux-x86_64"
    }
}

fn find_ndk() -> Option<PathBuf> {
    for key in ["ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "NDK_HOME", "ANDROID_NDK"] {
        if let Ok(value) = env::var(key) {
            let path = PathBuf::from(value);
            if path.join("source.properties").exists() {
                return Some(path);
            }
        }
    }
    None
}

fn read_api_level(manifest_dir: &Path) -> String {
    if let Ok(level) = env::var("LUCKY_ANDROID_API") {
        if !level.is_empty() && level.chars().all(|c| c.is_ascii_digit()) {
            return level;
        }
    }
    fs::read_to_string(manifest_dir.join("../../native/versions.env"))
        .ok()
        .and_then(|text| text.lines().find_map(|line| line.strip_prefix("LUCKY_ANDROID_API=").map(|v| v.trim().to_string())))
        .unwrap_or_else(|| "26".to_string())
}

fn emit_link_flags(prefix: &Path) {
    let lib_dir = prefix.join("lib");
    println!("cargo:rustc-link-search=native={}", lib_dir.display());
    let flags = fs::read_to_string(prefix.join("ass-link-flags.txt"))
        .unwrap_or_else(|_| "-lass -lharfbuzz -lfribidi -lfreetype -lm".to_string());
    let mut seen: Vec<String> = Vec::new();
    for token in flags.split_whitespace() {
        if let Some(dir) = token.strip_prefix("-L") {
            println!("cargo:rustc-link-search=native={dir}");
        } else if let Some(name) = token.strip_prefix("-l") {
            if seen.iter().any(|s| s == name) {
                continue;
            }
            seen.push(name.to_string());
            if lib_dir.join(format!("lib{name}.a")).exists() {
                println!("cargo:rustc-link-lib=static={name}");
            } else {
                println!("cargo:rustc-link-lib=dylib={name}");
            }
        }
    }
}

fn main() {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let out_dir = PathBuf::from(env::var("OUT_DIR").unwrap());
    let target = env::var("TARGET").unwrap();
    println!("cargo:rerun-if-changed=native/wrapper.h");
    for key in ["LUCKY_FFMPEG_DIR", "LUCKY_NATIVE_DEPS", "ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "LIBCLANG_PATH", "LUCKY_ANDROID_API"] {
        println!("cargo:rerun-if-env-changed={key}");
    }
    let abi = android_abi(&target);
    let prefix = if let Ok(dir) = env::var("LUCKY_FFMPEG_DIR") {
        PathBuf::from(dir)
    } else {
        let base = env::var("LUCKY_NATIVE_DEPS")
            .map(PathBuf::from)
            .unwrap_or_else(|_| manifest_dir.join("../../native/out"));
        let (abi_name, _) = abi.unwrap_or_else(|| panic!("unsupported target {target} (set LUCKY_FFMPEG_DIR for host builds)"));
        base.join(abi_name)
    };
    let include_dir = prefix.join("include");
    if !include_dir.join("ass/ass.h").exists() {
        panic!("libass headers not found in {}. Run engine/native/build-deps.sh first.", include_dir.display());
    }
    println!("cargo:rerun-if-changed={}", prefix.join("deps.id").display());

    let mut clang_args = vec![format!("-I{}", include_dir.display())];
    if let Some((_, clang_target)) = abi {
        clang_args.push(format!("--target={clang_target}{}", read_api_level(&manifest_dir)));
        if let Some(ndk) = find_ndk() {
            let toolchain = ndk.join("toolchains/llvm/prebuilt").join(host_tag());
            clang_args.push(format!("--sysroot={}", toolchain.join("sysroot").display()));
            if env::var_os("LIBCLANG_PATH").is_none() {
                let lib = if cfg!(target_os = "windows") { toolchain.join("bin") } else { toolchain.join("lib") };
                if lib.exists() {
                    unsafe { env::set_var("LIBCLANG_PATH", lib) };
                }
            }
        }
    }

    bindgen::Builder::default()
        .header("native/wrapper.h")
        .clang_args(&clang_args)
        .allowlist_function("ass_.*")
        .allowlist_type("ASS_.*")
        .allowlist_var("ASS_.*")
        .default_enum_style(bindgen::EnumVariation::Consts)
        .derive_default(true)
        .derive_debug(false)
        .layout_tests(false)
        .generate_comments(false)
        .merge_extern_blocks(true)
        .parse_callbacks(Box::new(bindgen::CargoCallbacks::new()))
        .generate()
        .expect("failed to generate libass bindings")
        .write_to_file(out_dir.join("bindings.rs"))
        .expect("failed to write libass bindings");

    emit_link_flags(&prefix);
}
