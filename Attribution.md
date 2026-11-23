# Attribution

This document lists all third-party libraries, icons, services, and acknowledgments used in Lucky.

---

## Libraries

### AndroidX
- **Author:** Google
- **License:** [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- **URL:** https://developer.android.com/jetpack/androidx
- Includes Core, AppCompat, Activity, Lifecycle, and Preference.

### Jetpack Compose
- **Author:** Google
- **License:** [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- **URL:** https://developer.android.com/jetpack/compose
- Powers the whole interface, including Material 3, the adaptive layouts, window size classes, and the extended Material icons.

### Material Components for Android
- **Author:** Google
- **License:** [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- **URL:** https://github.com/material-components/material-components-android

---

## Engine

Lucky's engine is written in Rust and bundles these native libraries.

### FFmpeg
- **Author:** The FFmpeg developers
- **License:** [GNU Lesser General Public License v2.1 or later](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html)
- **URL:** https://ffmpeg.org
- Opens and decodes almost any video or audio format. Built without GPL-only components.

### dav1d
- **Author:** VideoLAN and dav1d authors
- **License:** [BSD 2-Clause](https://code.videolan.org/videolan/dav1d/-/blob/master/COPYING)
- **URL:** https://code.videolan.org/videolan/dav1d
- Fast AV1 video decoding.

### libass
- **Author:** libass contributors
- **License:** [ISC License](https://github.com/libass/libass/blob/master/COPYING)
- **URL:** https://github.com/libass/libass
- Renders ASS, SSA, and styled text subtitles.

### FreeType
- **Author:** The FreeType Project
- **License:** [FreeType License](https://freetype.org/license.html)
- **URL:** https://freetype.org
- Draws the fonts used by subtitles.

### HarfBuzz
- **Author:** HarfBuzz contributors
- **License:** [MIT License](https://github.com/harfbuzz/harfbuzz/blob/main/COPYING)
- **URL:** https://github.com/harfbuzz/harfbuzz
- Shapes subtitle text so complex scripts display correctly.

### FriBidi
- **Author:** GNU FriBidi contributors
- **License:** [GNU Lesser General Public License v2.1 or later](https://github.com/fribidi/fribidi/blob/master/COPYING)
- **URL:** https://github.com/fribidi/fribidi
- Handles right-to-left text in subtitles.

### zlib
- **Author:** Jean-loup Gailly and Mark Adler
- **License:** [zlib License](https://zlib.net/zlib_license.html)
- **URL:** https://zlib.net

---

## Rust Crates

### jni-rs
- **License:** [MIT / Apache License 2.0](https://github.com/jni-rs/jni-rs)
- **URL:** https://github.com/jni-rs/jni-rs
- Connects Lucky's Rust engine to Android.

### ash
- **License:** [MIT / Apache License 2.0](https://github.com/ash-rs/ash)
- **URL:** https://github.com/ash-rs/ash
- Draws video with Vulkan.

### glow
- **License:** [MIT / Apache License 2.0](https://github.com/grovesNL/glow)
- **URL:** https://github.com/grovesNL/glow
- Draws video with OpenGL ES.

### serde and serde_json
- **License:** [MIT / Apache License 2.0](https://github.com/serde-rs/serde)
- **URL:** https://serde.rs

### thiserror
- **License:** [MIT / Apache License 2.0](https://github.com/dtolnay/thiserror)
- **URL:** https://github.com/dtolnay/thiserror

### log and android_logger
- **License:** [MIT / Apache License 2.0](https://github.com/rust-lang/log)
- **URL:** https://github.com/rust-lang/log, https://github.com/rust-mobile/android_logger-rs

### libc
- **License:** [MIT / Apache License 2.0](https://github.com/rust-lang/libc)
- **URL:** https://github.com/rust-lang/libc

### bindgen and cc (build only)
- **License:** [BSD 3-Clause](https://github.com/rust-lang/rust-bindgen/blob/main/LICENSE) / [MIT / Apache License 2.0](https://github.com/rust-lang/cc-rs)
- **URL:** https://github.com/rust-lang/rust-bindgen, https://github.com/rust-lang/cc-rs
- Used while building the engine. They are not shipped in the app.

---

## Online Services

### GitHub
- **URL:** https://github.com/jhaiian/Lucky
- Checks for updates and downloads new versions. Only the GitHub build does this, and you can turn off the check on launch. The FOSS build never connects.

---

## Icons & Assets

### Material Icons
- **Author:** Google
- **License:** [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- **URL:** https://github.com/google/material-design-icons

---

## Acknowledgments

### Theme — From Clint
The theming system in Lucky comes from [Clint Browser](https://github.com/jhaiian/ClintBrowser) and [Clint Gecko](https://github.com/jhaiian/ClintGecko). Lucky shares the same accent colors, surface intensities, and theme design as the rest of the Jhaiian ecosystem.

### UI — From Kate
Lucky's interface shares a very similar look with [Kate](https://github.com/jhaiian/Kate), from the same Jhaiian ecosystem, including the setup screen, cards, buttons, and seek bar styles.
