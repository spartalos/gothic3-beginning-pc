#!/usr/bin/env bash
# Fails if game content of the original (or anything derived from it) is tracked in git.
# usage: scripts/check-no-game-files.sh        (run from anywhere inside the repo)
set -euo pipefail
cd "$(dirname "$0")/.."

bad=$(git ls-files -z | tr '\0' '\n' | grep -E \
  -e '\.(lng|mid|amr|mdl)$' \
  -e '^original/.*\.jar$' \
  -e '^game/src/main/java/g3/' \
  -e '^reference/decompiled-original/' \
  -e '^original/' \
  -e '^game/.*\.wav$' \
  -e '^game/src/main/resources/[^/]*\.png$' \
  -e '^docs/screenshots/' \
  -e '^tools/.*\.jar$' \
  || true)

if [ -n "$bad" ]; then
  n=$(printf '%s\n' "$bad" | wc -l)
  echo "check-no-game-files: $n tracked file(s) are game content (bring-your-own-jar, docs/byo-jar.md):" >&2
  printf '%s\n' "$bad" | head -40 | sed 's/^/  /' >&2
  [ "$n" -gt 40 ] && echo "  ... and $((n - 40)) more" >&2
  exit 1
fi
echo "check-no-game-files: OK, no game files tracked"
