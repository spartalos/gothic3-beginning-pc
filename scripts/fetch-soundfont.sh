#!/usr/bin/env bash
# Downloads the pinned GeneralUser GS SoundFont (S. Christian Collins, v2.0.3) and its licence from the author's official GitHub
# repository, verifies the SHA-256 of both, and leaves them in OUT_DIR as GeneralUser-GS.sf2 + GeneralUser-GS-LICENSE.txt.
# The release workflow bundles that directory into the app images (-PsoundfontDir); the .sf2 is never committed (.gitignore).
# usage: scripts/fetch-soundfont.sh OUT_DIR
set -euo pipefail

out="${1:?usage: fetch-soundfont.sh OUT_DIR}"
# GeneralUser-GS has no tags/releases; this is the repository commit that introduced v2.0.3 of the SoundFont.
commit=97049183643d5fc5a9322a69c5b09efb667c6c3a
base="https://raw.githubusercontent.com/mrbumpy409/GeneralUser-GS/$commit"
sf2_sha=9575028c7a1f589f5770fccc8cff2734566af40cd26ed836944e9a5152688cfe
lic_sha=7b32efefdf95ce38a043799f0659853ddc00fbaa14d8c50f0aca16b9b8b405be

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
fetch() { # name remote-path expected-sha
  curl -fsSL --retry 3 -o "$out/$1" "$base/$2"
  got=$(sha256 "$out/$1")
  if [ "$got" != "$3" ]; then
    echo "fetch-soundfont: SHA-256 mismatch for $1: got $got, expected $3" >&2
    rm -f "$out/$1"
    exit 1
  fi
}

mkdir -p "$out"
fetch GeneralUser-GS.sf2 GeneralUser-GS.sf2 "$sf2_sha"
fetch GeneralUser-GS-LICENSE.txt documentation/LICENSE.txt "$lic_sha"
echo "fetch-soundfont: GeneralUser GS v2.0.3 verified in $out"
