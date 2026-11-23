use std::env;
use std::path::{Path, PathBuf};
use std::process::Command;

fn host_tag() -> &'static str {
    if cfg!(target_os = "macos") {
        "darwin-x86_64"
    } else if cfg!(target_os = "windows") {
        "windows-x86_64"
    } else {
        "linux-x86_64"
    }
}

fn find_glslc() -> PathBuf {
    if let Ok(path) = env::var("GLSLC") {
        return PathBuf::from(path);
    }
    for key in ["ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "NDK_HOME", "ANDROID_NDK"] {
        if let Ok(ndk) = env::var(key) {
            let name = if cfg!(target_os = "windows") { "glslc.exe" } else { "glslc" };
            let candidate = Path::new(&ndk).join("shader-tools").join(host_tag()).join(name);
            if candidate.exists() {
                return candidate;
            }
        }
    }
    PathBuf::from("glslc")
}

fn main() {
    let out_dir = PathBuf::from(env::var("OUT_DIR").unwrap());
    let shader_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap()).join("shaders");
    println!("cargo:rerun-if-changed=shaders");
    for key in ["GLSLC", "ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"] {
        println!("cargo:rerun-if-env-changed={key}");
    }
    let glslc = find_glslc();
    let shaders = [
        "quad.vert",
        "yuv.frag",
        "external.frag",
        "fullscreen.vert",
        "blend.frag",
        "memc_down.frag",
        "memc_search.frag",
        "memc_median.frag",
        "memc_dense.frag",
        "memc_tiles.frag",
        "memc_stats.frag",
        "memc_select.frag",
        "memc_render.frag",
    ];
    for shader in shaders {
        let output = out_dir.join(format!("{shader}.spv"));
        let status = Command::new(&glslc)
            .arg("--target-env=vulkan1.1")
            .arg("-O")
            .arg("-I")
            .arg(&shader_dir)
            .arg(shader_dir.join(shader))
            .arg("-o")
            .arg(&output)
            .status()
            .unwrap_or_else(|error| panic!("cannot run glslc ({}): {error}", glslc.display()));
        if !status.success() {
            panic!("glslc failed for {shader}");
        }
    }
}
