# Gothic 3: The Beginning — PC port

A PC port of the 2008 J2ME game *Gothic 3: The Beginning*, built from renamed and decompiled sources (package `g3`). It runs on a desktop MIDP implementation (`midp/`) and is launched through a libGDX launcher (`desktop/`).

## Playing: you need your own copy of the game

This port does not contain the game. It builds it on your computer from your own copy of the original mobile game:
**`Gothic3-TheBeginning_s40v3a.jar`** (Nokia Series 40 build, 416,549 bytes, SHA-256 `0f02bc1b...cb7f`, MIDlet "Gothic3TheBeginning" by handy-games.com; use your own
copy, e.g. from a phone backup). Put it in your Downloads folder (or start the app with `--jar <path>`, or choose / drop it on the setup screen). The first start
builds the game (about 10 seconds), later starts are instant. The 25 sound effects are converted with a small ffmpeg that ships inside the app images (the plain fat jar uses `ffmpeg`
from PATH; without any ffmpeg the game runs silent apart from the music). Details: `docs/byo-jar.md`.

Run the release build with `java -jar gothic3-<version>.jar` or start the app image (`Gothic3/bin/Gothic3`, `Gothic3.app`, `Gothic3.exe`).

## Developing

`./gradlew build` works on a clean checkout without any game jar: the `:game` module is then skipped (one log line) and everything else (midp, port, buildtools,
desktop, `:desktop:dist`, `:desktop:packageApp`) builds; this is what CI and the release workflow do (no secrets; the release workflow also builds the minimal ffmpeg, `scripts/ffmpeg`). The decompile tools (Vineflower,
tiny-remapper) are fetched from Maven and SHA-256 verified.

With your jar (`original/Gothic3-TheBeginning_s40v3a.jar` or `-PgameJar=<path>`), `:game` is generated at build time and `./gradlew :desktop:run` runs it directly;
`./gradlew clean build -PgameJar=...` plus the determinism trace is the opt-in integration check (docs/byo-jar.md). Release builds (fat jar, per-OS app
images, GitHub Releases): see "Building & releases" in `docs/platform.md`; they contain no game content (checked by `./gradlew :desktop:checkNoGameContent`, and
`scripts/check-no-game-files.sh` for the repository). Game code edits: `docs/byo-jar.md`.

## Hotkeys
| key | action |
|---|---|
| F5 | switch between the 2D (original) and 3D view (this session; the saved choice is in the F10 options) |
| F3 | status overlay (fps, tick rate) |
| F8 (gamepad: right stick click) | fast forward on / off (speed in F10 > Display) |
| F10 (gamepad: Start + Back) | advanced settings (display, 3D, controls, audio); 3D > Graphics quality picks Low / Medium / High / Ultra at once |
| F11 / Alt + Enter | fullscreen |

On the first start the game asks whether you want sound and whether you want the 2D or the 3D view. Both answers are saved and not asked again; change them in the F10 options (Audio > Sound, 3D > View, Game > Ask startup questions again).

Further documentation:
- `docs/platform.md`: platform notes
- `docs/audio.md`: audio notes
- `docs/analysis/`: analysis of the original game
- `docs/mapping/`: symbol mapping from original to renamed sources

## Legal

This repository contains no code or assets of the original game. You need your own legally obtained copy of `Gothic3-TheBeginning_s40v3a.jar`
(SHA-256 `0f02bc1b70c2492b0f08b0d7ef5a3ab6c71d01608a8363b52c77678d3cc6cb7f`, 416,549 bytes). Unofficial fan project, not affiliated with or endorsed by
THQ Nordic, Piranha Bytes or HandyGames. Gothic is a trademark of its respective owners.

Third-party components and their licences: see `NOTICE`. The licence of this project's own code has not been chosen yet.

Screenshots of the game are not part of the repository (they show the original art), so the docs describe visual results in words.
