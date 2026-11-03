#!/usr/bin/env bash
# Rename the original game bytecode with docs/mapping/*.map, move it into package g3,
# and decompile the result into an output source directory.
#
# usage: scripts/remap/remap.sh <out-src-dir>
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_SRC="${1:?usage: remap.sh <out-src-dir>}"
JAR="$ROOT/original/Gothic3-TheBeginning_s40v3a.jar"
# pinned tools are fetched from Maven and SHA-256 verified by: ./gradlew :buildtools:fetchPipelineTools
REMAPPER="$ROOT/build/pipeline-tools/tiny-remapper-0.14.1-fat.jar"
VINEFLOWER="$ROOT/build/pipeline-tools/vineflower-1.12.0.jar"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/g3-remap.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# Classes only; resources stay where they are (game/src/main/resources).
mkdir -p "$WORK/classes"
unzip -q "$JAR" '*.class' -d "$WORK/classes"
(cd "$WORK/classes" && jar --create --file "$WORK/classes.jar" ./*.class)
CLASSES=$(cd "$WORK/classes" && ls ./*.class | sed 's|^\./||; s|\.class$||')

python3 -I "$ROOT/scripts/remap/build_tiny.py" "${MAPPING_DIR:-$ROOT/docs/mapping}" "$WORK/mappings.tiny" $CLASSES
java -jar "$REMAPPER" "$WORK/classes.jar" "$WORK/remapped.jar" "$WORK/mappings.tiny" official named

mkdir -p "$WORK/decompiled"
java -jar "$VINEFLOWER" -dgs=1 -log=WARN "$WORK/remapped.jar" "$WORK/decompiled" >/dev/null
mkdir -p "$OUT_SRC"
(cd "$WORK/decompiled" && unzip -qo ./*.jar -d "$OUT_SRC" 2>/dev/null) || cp -r "$WORK/decompiled/." "$OUT_SRC/"
rm -rf "$OUT_SRC/META-INF"
echo "decompiled sources written to $OUT_SRC"
