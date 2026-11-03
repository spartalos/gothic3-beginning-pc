# CLAUDE.md

libGDX PC port of the J2ME game *Gothic 3: The Beginning*. Public repo `spartalos/gothic3-beginning-pc` (`main`, MIT). The old private repo with the pre-public history (it contains game files) is remote `private` / branch `private-history`: never push it to `origin`. Overview: `README.md`; details in `docs/`.

## Modules
- `midp/`: desktop MIDP implementation (`javax.microedition.*`) and `gothic3.platform` (RenderConfig, FramePacer).
- `port/`: `gothic3.port` (PortLoop: 10 Hz logic plus display-rate interpolated rendering, Port.millis game clock, TickTrace).
- `game/`: generated into `game/gen/` from the player's jar (tiny-remapper with `docs/mapping`, Vineflower, then `game-patches/g3/*.patch`). Never edit `game/gen` directly. Edit it, then run `./gradlew makePatches` (see `docs/byo-jar.md`). `game/src/main/resources/render3d/*.properties` are the 3D tuning tables (tiles, props).
- `buildtools/`, `desktop/` (launcher, input, F10 options, `byo/` first-run builder, `render3d/` HD-2D renderer; see `docs/render3d.md`).

## Bring your own jar: no game content in the repo
- Only `Gothic3-TheBeginning_s40v3a.jar` is supported (416,549 bytes, SHA-256 `0f02bc1b70c2492b0f08b0d7ef5a3ab6c71d01608a8363b52c77678d3cc6cb7f`). The local copy is `original/` (gitignored). Build with `-PgameJar=<path>` if it isn't found.
- Never commit game files: the jar, `.lng`, `.mid`, `.amr`, `.wav`, decompiled `g3` sources, or screenshots of game art. Never link to downloads of the game.
- Guards: `scripts/check-no-game-files.sh` and `./gradlew :desktop:checkNoGameContent`. CI and releases build with no jar (`:game` is skipped).

## Build / verify
- `./gradlew build checkNoGameContent -PgameJar=original/Gothic3-TheBeginning_s40v3a.jar`
- Determinism gate: `scripts/walk-ticks.script` with `--trace-ticks FILE --exit-after-ticks 1200 --deterministic`. The trace must not change unless the change is intended to.
- 3D gate: `--view3d --verify` together with `--script` and `--trace-ticks` must report 661/661 identical. A bare `--verify` hangs.
- Screenshot runs: `-Dg3.debug=true` with a script (`warp AREA X Y`, `waitticks N`, `wshot name`, `view 2d`, `speed N`, `exit`), plus `--frames-dir DIR --deterministic --width 1280 --height 720`. Commands are listed in `ScriptRunner.java`.
- For 3D visual fixes, compare zoomed BEFORE | AFTER | 2D crops before calling a fix done.

## Hard rules for running the game
- Always pass `--no-audio --hidden`. Never produce audible sound.
- Use your own output dir and a temp HOME: `-Duser.home=$TMP`, with `XDG_CONFIG_HOME` and `XDG_DATA_HOME` inside it.
- Never touch the real `~/.config/gothic3-pc` or `~/.local/share/gothic3-pc` (real saves are in `rms/`). Reading them is fine.
- Never `pkill`/`killall java`; kill only PIDs you started (the user may be playing).
- Put scratch output in the session scratchpad, never in the repo.

## Workflow
- Delegate implementation to subagents in git worktrees. Agents commit only on their worktree branch: no push, no tags, no touching `main`. The orchestrator reviews, merges with `--no-ff`, pushes, and removes the worktree.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Release: bump `version=` in `gradle.properties` (and the "currently" note in `docs/platform.md`), commit, push, then push tag `vX.Y.Z`. `.github/workflows/release.yml` builds the Linux/Windows/macOS app images and the fat jar and publishes the GitHub Release.
