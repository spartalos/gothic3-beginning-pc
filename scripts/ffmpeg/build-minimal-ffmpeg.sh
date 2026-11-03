#!/usr/bin/env bash
# Builds a minimal FFmpeg that can only do one thing: AMR-NB (.amr) -> PCM WAV. Used by the app to convert the game's sound
# effects (buildtools AmrConverter). Output: <out-dir>/ffmpeg[.exe] plus <out-dir>/LICENSES/*.
#
# Decoder: OpenCORE AMR-NB (opencore-amr 0.1.6, Apache-2.0), linked statically. FFmpeg's own native amrnb decoder is NOT usable:
# it fails on the game's 6.7 kbps frames and on SID frames and truncates 12 of the 25 sound effects (docs/byo-jar.md "Audio").
# OpenCORE needs ffmpeg's --enable-version3, which makes the binary LGPL-3.0-or-later (still no GPL, no nonfree parts).
#
# usage: scripts/ffmpeg/build-minimal-ffmpeg.sh <out-dir> [work-dir]
#   Linux / macOS: any Unix with a C/C++ compiler and make.
#   Windows: run from an MSYS2 MINGW64 shell (pacman -S make diffutils pkgconf mingw-w64-x86_64-gcc); the .exe is statically linked.
#   Linux x86_64 binaries link glibc dynamically only; build them on an old runner (ubuntu-22.04, glibc 2.35) for compatibility.
set -euo pipefail

FFMPEG_VERSION=9.0.2
FFMPEG_URL="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
FFMPEG_SHA256=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
OPENCORE_VERSION=0.1.6
OPENCORE_URL="https://downloads.sourceforge.net/project/opencore-amr/opencore-amr/opencore-amr-${OPENCORE_VERSION}.tar.gz"
OPENCORE_SHA256=483eb4061088e2b34b358e47540b5d495a96cd468e361050fae615b1809dc4a1

OUT=${1:?usage: build-minimal-ffmpeg.sh <out-dir> [work-dir]}
WORK=${2:-"${TMPDIR:-/tmp}/g3-ffmpeg-build"}
mkdir -p "$OUT" "$WORK"
OUT=$(cd "$OUT" && pwd); WORK=$(cd "$WORK" && pwd)

# Keep in sync with docs/byo-jar.md and NOTICE.
CONFIGURE_FLAGS=(
  --disable-everything --disable-autodetect --disable-network --disable-doc --disable-debug
  --disable-ffplay --disable-ffprobe --disable-avdevice --disable-swscale
  --enable-small --enable-static --disable-shared --disable-x86asm
  --enable-version3 --enable-libopencore-amrnb
  --enable-protocol=file
  --enable-demuxer=amr --enable-parser=amr --enable-decoder=libopencore_amrnb --enable-muxer=wav --enable-encoder=pcm_s16le
  --enable-filter=aresample,aformat,anull,abuffer,abuffersink
)

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
fetch() { # url sha256 file
  if [ ! -f "$3" ]; then curl -fsSL "$1" -o "$3"; fi
  local got; got=$(sha256 "$3")
  if [ "$got" != "$2" ]; then echo "$3: SHA-256 mismatch ($got)" >&2; exit 1; fi
}

EXE=ffmpeg; CXXLIB=-lstdc++; EXTRA=()
case "$(uname -s)" in
  MINGW*|MSYS*) EXTRA+=(--target-os=mingw32); EXE=ffmpeg.exe ;;
  Darwin) CXXLIB=-lc++ ;;
esac
JOBS=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 2)

# 1. OpenCORE AMR (static, amrnb decoder only needed; the library itself is tiny)
fetch "$OPENCORE_URL" "$OPENCORE_SHA256" "$WORK/opencore-amr-${OPENCORE_VERSION}.tar.gz"
OC_SRC="$WORK/opencore-amr-${OPENCORE_VERSION}"; OC_PREFIX="$WORK/opencore-prefix"
rm -rf "$OC_SRC" "$OC_PREFIX"; tar -C "$WORK" -xzf "$WORK/opencore-amr-${OPENCORE_VERSION}.tar.gz"
( cd "$OC_SRC" && ./configure --prefix="$OC_PREFIX" --disable-shared --enable-static --disable-examples \
    && make -j"$JOBS" && make install )

# 2. FFmpeg
fetch "$FFMPEG_URL" "$FFMPEG_SHA256" "$WORK/ffmpeg-${FFMPEG_VERSION}.tar.xz"
SRC="$WORK/ffmpeg-${FFMPEG_VERSION}"
rm -rf "$SRC"; tar -C "$WORK" -xf "$WORK/ffmpeg-${FFMPEG_VERSION}.tar.xz"
cd "$SRC"
export PKG_CONFIG_PATH="$OC_PREFIX/lib/pkgconfig"
LINK_FLAGS=(--extra-cflags="-I$OC_PREFIX/include" --extra-ldflags="-L$OC_PREFIX/lib" --extra-libs="$CXXLIB")
case "$(uname -s)" in
  MINGW*|MSYS*) LINK_FLAGS+=(--extra-ldflags=-static) ;;
  Linux) LINK_FLAGS+=(--extra-ldflags="-static-libgcc -static-libstdc++") ;;   # leaves only glibc (libc, libm) as dynamic dependencies
esac

./configure "${CONFIGURE_FLAGS[@]}" "${LINK_FLAGS[@]}" ${EXTRA[@]+"${EXTRA[@]}"}
make -j"$JOBS" "$EXE"

strip "$EXE" 2>/dev/null || true
mkdir -p "$OUT/LICENSES"
cp "$EXE" "$OUT/$EXE"
cp COPYING.LGPLv3 "$OUT/LICENSES/COPYING.LGPLv3"
cp COPYING.LGPLv2.1 "$OUT/LICENSES/COPYING.LGPLv2.1"
cp "$OC_SRC/LICENSE" "$OUT/LICENSES/opencore-amr-LICENSE-Apache-2.0.txt"
cp "$OC_SRC/opencore/NOTICE" "$OUT/LICENSES/opencore-amr-NOTICE.txt"
cat > "$OUT/LICENSES/FFMPEG-NOTICE.txt" <<NOTE
This directory belongs to a separate executable, ffmpeg, built from unmodified sources:
  FFmpeg ${FFMPEG_VERSION}      ${FFMPEG_URL}  (SHA-256 ${FFMPEG_SHA256})
  opencore-amr ${OPENCORE_VERSION}  ${OPENCORE_URL}  (SHA-256 ${OPENCORE_SHA256})
FFmpeg is licensed under the GNU Lesser General Public License version 3 or later (LGPL-3.0+; configured with --enable-version3, no GPL and
no nonfree components): COPYING.LGPLv3 (it incorporates the GPLv3 text by reference; COPYING.LGPLv2.1 is FFmpeg's licence file for the
LGPL 2.1 parts). opencore-amr is licensed under the Apache License 2.0 (opencore-amr-LICENSE-Apache-2.0.txt, opencore-amr-NOTICE.txt).
FFmpeg is a trademark of Fabrice Bellard. https://ffmpeg.org/legal.html
FFmpeg configure flags: ${CONFIGURE_FLAGS[*]}
Build script: scripts/ffmpeg/build-minimal-ffmpeg.sh in the Gothic 3 PC port source repository. The app only runs this binary as a child
process to convert the game's AMR sound effects to WAV; you can replace it with any other ffmpeg binary or put one on PATH.
NOTE
echo "built $OUT/$EXE"; "$OUT/$EXE" -version | head -1
