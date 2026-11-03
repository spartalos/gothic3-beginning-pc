#!/usr/bin/env bash
# Smoke test for the minimal ffmpeg (scripts/ffmpeg/build-minimal-ffmpeg.sh): prints version, size and dynamic dependencies, then decodes a
# tiny synthetic AMR-NB file (generated here, no game data) with the exact arguments AmrConverter uses and checks the WAV header.
# usage: scripts/ffmpeg/test-minimal-ffmpeg.sh <ffmpeg-out-dir>
set -euo pipefail
DIR=${1:?usage: test-minimal-ffmpeg.sh <ffmpeg-out-dir>}
EXE="$DIR/ffmpeg"; [ -f "$EXE.exe" ] && EXE="$EXE.exe"
[ -f "$EXE" ] || { echo "no ffmpeg in $DIR" >&2; exit 1; }

"$EXE" -version | head -3
"$EXE" -hide_banner -decoders | grep -q libopencore_amrnb || { echo "libopencore_amrnb decoder missing" >&2; exit 1; }
ls -l "$EXE"
case "$(uname -s)" in
  Linux) ldd "$EXE" || true ;;
  Darwin) otool -L "$EXE" || true ;;
  MINGW*|MSYS*) ldd "$EXE" || true ;;
esac
for f in LICENSES/COPYING.LGPLv3 LICENSES/FFMPEG-NOTICE.txt LICENSES/opencore-amr-LICENSE-Apache-2.0.txt; do
  [ -s "$DIR/$f" ] || { echo "missing $f" >&2; exit 1; }
done

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
# "#!AMR\n" header + 6 frames: mode 0 (4.75 kbps) = TOC byte 0x04 + 12 payload bytes (all-zero bits are a valid bit pattern), and one NO_DATA frame (0x7C).
{
  printf '#!AMR\n'
  for i in 1 2 3 4 5 6; do printf '\004'; head -c 12 /dev/zero; done
  printf '\174'
} > "$T/test.amr"
# same arguments as buildtools AmrConverter
"$EXE" -v error -y -c:a libopencore_amrnb -i "$T/test.amr" -ar 22050 -ac 1 -c:a pcm_s16le "$T/test.wav"
size=$(wc -c < "$T/test.wav" | tr -d ' ')
[ "$(head -c 4 "$T/test.wav")" = "RIFF" ] || { echo "no RIFF header" >&2; exit 1; }
[ "$(dd if="$T/test.wav" bs=1 skip=8 count=4 2>/dev/null)" = "WAVE" ] || { echo "no WAVE tag" >&2; exit 1; }
# 6 frames * 20 ms = 120 ms at 22050 Hz mono s16 = ~5292 bytes of PCM (+44 header); require something sane
[ "$size" -gt 4000 ] || { echo "WAV too small: $size bytes" >&2; exit 1; }
echo "OK: decoded test AMR to a $size byte WAV"
