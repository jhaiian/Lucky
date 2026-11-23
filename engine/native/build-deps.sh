#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/versions.env"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${NDK_HOME:-}}}"
if [ -z "$NDK" ] || [ ! -f "$NDK/source.properties" ]; then
  echo "error: set ANDROID_NDK_HOME to an Android NDK (r28 or newer)" >&2
  exit 1
fi

case "$(uname -s)" in
  Linux) HOST_TAG=linux-x86_64 ;;
  Darwin) HOST_TAG=darwin-x86_64 ;;
  *) echo "error: unsupported host $(uname -s); build on Linux or macOS" >&2; exit 1 ;;
esac

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
SYSROOT="$TOOLCHAIN/sysroot"
API="$LUCKY_ANDROID_API"
OUT_DIR="${LUCKY_DEPS_OUT:-$SCRIPT_DIR/out}"
CACHE_DIR="${LUCKY_DEPS_CACHE:-$SCRIPT_DIR/.cache}"
WORK_DIR="${LUCKY_DEPS_WORK:-$SCRIPT_DIR/.work}"
JOBS="${LUCKY_DEPS_JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)}"

if [ "$#" -gt 0 ]; then
  ABIS="$*"
else
  ABIS="${LUCKY_ABIS:-arm64-v8a armeabi-v7a}"
fi
ABIS="$(echo "$ABIS" | tr ',' ' ')"

for tool in curl tar make meson ninja pkg-config patch; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "error: '$tool' is required to build the native dependencies" >&2
    exit 1
  fi
done

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

sha256_stdin() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | cut -d' ' -f1
  else
    shasum -a 256 | cut -d' ' -f1
  fi
}

NDK_REVISION="$(grep -E '^Pkg.Revision' "$NDK/source.properties" | cut -d= -f2 | tr -d ' ')"
DEPS_ID="$({ cat "$SCRIPT_DIR/versions.env" "$SCRIPT_DIR/build-deps.sh" "$SCRIPT_DIR"/patches/*.patch; echo "$NDK_REVISION"; } | sha256_stdin | cut -c1-16)"

fetch() {
  local url="$1" file="$2" sha="$3"
  mkdir -p "$CACHE_DIR"
  if [ -f "$CACHE_DIR/$file" ] && [ "$(sha256_of "$CACHE_DIR/$file")" = "$sha" ]; then
    return
  fi
  echo "Downloading $file"
  curl -fL --retry 5 --retry-delay 3 -o "$CACHE_DIR/$file.part" "$url"
  if [ "$(sha256_of "$CACHE_DIR/$file.part")" != "$sha" ]; then
    rm -f "$CACHE_DIR/$file.part"
    echo "error: checksum mismatch for $file" >&2
    exit 1
  fi
  mv "$CACHE_DIR/$file.part" "$CACHE_DIR/$file"
}

extract() {
  local file="$1" name="$2" patch_file="${3:-}"
  mkdir -p "$WORK_DIR/src"
  if [ ! -d "$WORK_DIR/src/$name" ]; then
    tar -xf "$CACHE_DIR/$file" -C "$WORK_DIR/src"
    if [ -n "$patch_file" ]; then
      patch -d "$WORK_DIR/src/$name" -p1 < "$SCRIPT_DIR/patches/$patch_file"
    fi
  fi
}

configure_abi() {
  case "$1" in
    arm64-v8a)
      CLANG_TARGET="aarch64-linux-android"
      FF_ARCH="aarch64"
      FF_CPU="armv8-a"
      MESON_FAMILY="aarch64"
      MESON_CPU="armv8-a"
      ABI_CFLAGS=""
      ABI_LDFLAGS="-Wl,-z,max-page-size=16384"
      FF_ABI_FLAGS=""
      ;;
    armeabi-v7a)
      CLANG_TARGET="armv7a-linux-androideabi"
      FF_ARCH="arm"
      FF_CPU="armv7-a"
      MESON_FAMILY="arm"
      MESON_CPU="armv7-a"
      ABI_CFLAGS="-mfpu=neon -mfloat-abi=softfp"
      ABI_LDFLAGS=""
      FF_ABI_FLAGS="--enable-thumb"
      ;;
    *)
      echo "error: unsupported ABI '$1' (expected arm64-v8a or armeabi-v7a)" >&2
      exit 1
      ;;
  esac
  CC="$TOOLCHAIN/bin/${CLANG_TARGET}${API}-clang"
  CXX="$TOOLCHAIN/bin/${CLANG_TARGET}${API}-clang++"
  BASE_CFLAGS="-O3 -fPIC -fno-strict-aliasing -DNDEBUG $ABI_CFLAGS"
}

meson_list() {
  local out="" item
  for item in "$@"; do
    out="$out'$item', "
  done
  echo "[${out%, }]"
}

write_cross() {
  local file="$1" prefix="$2"
  cat > "$file" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$TOOLCHAIN/bin/llvm-ar'
strip = '$TOOLCHAIN/bin/llvm-strip'
pkg-config = 'pkg-config'

[properties]
pkg_config_libdir = ['$prefix/lib/pkgconfig']

[built-in options]
c_args = $(meson_list $BASE_CFLAGS)
cpp_args = $(meson_list $BASE_CFLAGS)
c_link_args = $(meson_list $ABI_LDFLAGS)
cpp_link_args = $(meson_list $ABI_LDFLAGS)

[host_machine]
system = 'android'
cpu_family = '$MESON_FAMILY'
cpu = '$MESON_CPU'
endian = 'little'
EOF
}

meson_static() {
  local name="$1" prefix="$2" src="$3"
  shift 3
  local build="$WORK_DIR/build/$ABI_NAME/$name"
  rm -rf "$build"
  mkdir -p "$build"
  write_cross "$build/cross.ini" "$prefix"
  PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" PKG_CONFIG_PATH="" meson setup "$build" "$src" \
    --cross-file "$build/cross.ini" \
    --prefix "$prefix" \
    --libdir lib \
    --buildtype release \
    --default-library static \
    -Db_staticpic=true \
    "$@"
  ninja -C "$build" -j "$JOBS" install
}

build_freetype() {
  meson_static freetype "$2" "$WORK_DIR/src/freetype-$FREETYPE_VERSION" \
    -Dbrotli=disabled \
    -Dbzip2=disabled \
    -Dharfbuzz=disabled \
    -Dpng=disabled \
    -Dzlib=internal \
    -Dtests=disabled
}

build_fribidi() {
  meson_static fribidi "$2" "$WORK_DIR/src/fribidi-$FRIBIDI_VERSION" \
    -Ddocs=false \
    -Dbin=false \
    -Dtests=false
}

build_harfbuzz() {
  meson_static harfbuzz "$2" "$WORK_DIR/src/harfbuzz-$HARFBUZZ_VERSION" \
    -Dglib=disabled \
    -Dgobject=disabled \
    -Dcairo=disabled \
    -Dchafa=disabled \
    -Dicu=disabled \
    -Dgraphite=disabled \
    -Dgraphite2=disabled \
    -Dfreetype=disabled \
    -Dtests=disabled \
    -Dintrospection=disabled \
    -Ddocs=disabled \
    -Dutilities=disabled \
    -Dbenchmark=disabled
}

build_libass() {
  local asm=disabled
  if [ "$1" = "arm64-v8a" ]; then
    asm=enabled
  fi
  meson_static libass "$2" "$WORK_DIR/src/libass-$LIBASS_VERSION" \
    -Dfontconfig=disabled \
    -Ddirectwrite=disabled \
    -Dcoretext=disabled \
    -Dlibunibreak=disabled \
    -Drequire-system-font-provider=false \
    -Dasm=$asm \
    -Dtest=false \
    -Dprofile=false
}

build_dav1d() {
  local abi="$1" prefix="$2"
  local src="$WORK_DIR/src/dav1d-$DAV1D_VERSION"
  local build="$WORK_DIR/build/$abi/dav1d"
  rm -rf "$build"
  mkdir -p "$build"
  cat > "$build/cross.ini" <<EOF
[binaries]
c = '$CC'
ar = '$TOOLCHAIN/bin/llvm-ar'
strip = '$TOOLCHAIN/bin/llvm-strip'

[built-in options]
c_args = $(meson_list $BASE_CFLAGS)
c_link_args = $(meson_list $ABI_LDFLAGS)

[host_machine]
system = 'android'
cpu_family = '$MESON_FAMILY'
cpu = '$MESON_CPU'
endian = 'little'
EOF
  meson setup "$build" "$src" \
    --cross-file "$build/cross.ini" \
    --prefix "$prefix" \
    --libdir lib \
    --buildtype release \
    --default-library static \
    -Db_staticpic=true \
    -Denable_asm=true \
    -Denable_tools=false \
    -Denable_examples=false \
    -Denable_tests=false \
    -Denable_docs=false \
    -Dlogging=false
  ninja -C "$build" -j "$JOBS" install
}

build_ffmpeg() {
  local abi="$1" prefix="$2"
  local src="$WORK_DIR/src/ffmpeg-$FFMPEG_VERSION"
  local build="$WORK_DIR/build/$abi/ffmpeg"
  rm -rf "$build"
  mkdir -p "$build"
  (
    cd "$build"
    PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" PKG_CONFIG_PATH="" "$src/configure" \
      --prefix="$prefix" \
      --target-os=android \
      --arch="$FF_ARCH" \
      --cpu="$FF_CPU" \
      --enable-cross-compile \
      --cc="$CC" \
      --cxx="$CXX" \
      --ld="$CC" \
      --nm="$TOOLCHAIN/bin/llvm-nm" \
      --ar="$TOOLCHAIN/bin/llvm-ar" \
      --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
      --strip="$TOOLCHAIN/bin/llvm-strip" \
      --sysroot="$SYSROOT" \
      --pkg-config=pkg-config \
      --pkg-config-flags=--static \
      --extra-cflags="$BASE_CFLAGS -I$prefix/include" \
      --extra-ldflags="-L$prefix/lib $ABI_LDFLAGS" \
      --enable-static \
      --disable-shared \
      --enable-pic \
      --disable-autodetect \
      --disable-debug \
      --disable-doc \
      --disable-programs \
      --disable-avdevice \
      --enable-avfilter \
      --disable-filters \
      --enable-filter=bwdif,buffer,buffersink \
      --disable-network \
      --disable-encoders \
      --disable-muxers \
      --disable-devices \
      --disable-hwaccels \
      --disable-protocols \
      --enable-protocol=file \
      --enable-protocol=pipe \
      --enable-zlib \
      --enable-libdav1d \
      --enable-asm \
      --enable-inline-asm \
      --enable-neon \
      $FF_ABI_FLAGS
    make -j "$JOBS"
    make install
  )
}

write_link_manifest() {
  local prefix="$1"
  PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" PKG_CONFIG_PATH="" \
    pkg-config --static --libs libavfilter libavformat libavcodec libswscale libswresample libavutil \
    > "$prefix/link-flags.txt"
  PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" PKG_CONFIG_PATH="" \
    pkg-config --static --libs libass \
    > "$prefix/ass-link-flags.txt"
}

fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" "ffmpeg-$FFMPEG_VERSION.tar.xz" "$FFMPEG_SHA256"
fetch "https://downloads.videolan.org/pub/videolan/dav1d/$DAV1D_VERSION/dav1d-$DAV1D_VERSION.tar.xz" "dav1d-$DAV1D_VERSION.tar.xz" "$DAV1D_SHA256"
fetch "https://download.savannah.gnu.org/releases/freetype/freetype-$FREETYPE_VERSION.tar.xz" "freetype-$FREETYPE_VERSION.tar.xz" "$FREETYPE_SHA256"
fetch "https://github.com/fribidi/fribidi/releases/download/v$FRIBIDI_VERSION/fribidi-$FRIBIDI_VERSION.tar.xz" "fribidi-$FRIBIDI_VERSION.tar.xz" "$FRIBIDI_SHA256"
fetch "https://github.com/harfbuzz/harfbuzz/releases/download/$HARFBUZZ_VERSION/harfbuzz-$HARFBUZZ_VERSION.tar.xz" "harfbuzz-$HARFBUZZ_VERSION.tar.xz" "$HARFBUZZ_SHA256"
fetch "https://github.com/libass/libass/releases/download/$LIBASS_VERSION/libass-$LIBASS_VERSION.tar.xz" "libass-$LIBASS_VERSION.tar.xz" "$LIBASS_SHA256"
extract "ffmpeg-$FFMPEG_VERSION.tar.xz" "ffmpeg-$FFMPEG_VERSION"
extract "dav1d-$DAV1D_VERSION.tar.xz" "dav1d-$DAV1D_VERSION"
extract "freetype-$FREETYPE_VERSION.tar.xz" "freetype-$FREETYPE_VERSION"
extract "fribidi-$FRIBIDI_VERSION.tar.xz" "fribidi-$FRIBIDI_VERSION"
extract "harfbuzz-$HARFBUZZ_VERSION.tar.xz" "harfbuzz-$HARFBUZZ_VERSION"
extract "libass-$LIBASS_VERSION.tar.xz" "libass-$LIBASS_VERSION" "libass-glyph-fallback.patch"

for abi in $ABIS; do
  prefix="$OUT_DIR/$abi"
  if [ -f "$prefix/deps.id" ] && [ "$(cat "$prefix/deps.id")" = "$DEPS_ID" ]; then
    echo "Native dependencies for $abi are up to date"
    continue
  fi
  echo "Building native dependencies for $abi"
  configure_abi "$abi"
  ABI_NAME="$abi"
  rm -rf "$prefix"
  mkdir -p "$prefix"
  build_dav1d "$abi" "$prefix"
  build_ffmpeg "$abi" "$prefix"
  build_freetype "$abi" "$prefix"
  build_fribidi "$abi" "$prefix"
  build_harfbuzz "$abi" "$prefix"
  build_libass "$abi" "$prefix"
  write_link_manifest "$prefix"
  rm -rf "$prefix/share" "$WORK_DIR/build/$abi"
  echo "$DEPS_ID" > "$prefix/deps.id"
done
