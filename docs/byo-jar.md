# Bring your own jar

The shipped app (fat jar and jpackage app images) contains **no game code and no game data of the original**. The player supplies their own copy of the
original mobile game at run time; the app builds the playable game from it on the player's computer. The same pipeline also generates the `game` module for
developers at build time (`./gradlew setupGame`). Part 1 is for players, part 2 describes the run-time build, part 3 is for developers.

## 1. For players

You need one file: **`Gothic3-TheBeginning_s40v3a.jar`**, the *Nokia Series 40* build of the MIDlet "Gothic3TheBeginning" by handy-games.com
(416,549 bytes, SHA-256 `0f02bc1b70c2492b0f08b0d7ef5a3ab6c71d01608a8363b52c77678d3cc6cb7f`). Use your own copy, for example from a phone backup. Other
builds (the "452x416" and the "multiscreen" versions are recognised and named) do not work. This repository gives no download links.

Where the app looks for it, in this order:

1. `--jar <path>` on the command line.
2. The path remembered in `~/.config/gothic3-pc/config.properties` (`game.jar`, written after the first successful start).
3. An automatic search of (non-recursive, `*.jar` files of exactly the right size only): the current directory, the directory of the app's jar, `~/Downloads`,
   `~/Desktop` and your home directory. So the easiest way is to drop the jar into your Downloads folder.
4. Otherwise a **setup screen** opens: *Choose jar...* (native file dialog; on Linux this needs `zenity` or `kdialog`), *Search again*, *Quit*; you can also drop the
   jar onto the window. Keyboard (Up/Down/Enter), gamepad (D-pad + A) and mouse work. A wrong file is named ("This is the 452x416 build ...") with the found and
   the expected size and SHA-256.

The first start builds the game (about 10 s: verify, decompile, patch, convert audio, compile; a progress screen shows the stages) into
`$XDG_DATA_HOME/gothic3-pc/game/<jar-sha>-<patchset-hash>/game.jar` (default `~/.local/share/gothic3-pc/game/...`). Later starts use the cache and take no
extra time. The cache is rebuilt automatically when the app ships a different patch set / version. `--rebuild-game` or F10 > Game > "Rebuild game data" discards it.

**Sound effects need ffmpeg, which the app images bring along.** The 25 sound effects are AMR-NB, which the JDK cannot play. The app image contains a minimal
ffmpeg (`<app>/ffmpeg/ffmpeg[.exe]`, next to the app jar; see "Audio" below) that converts them to WAV at build time. If it is missing (e.g. you run the plain fat
jar) the app uses `ffmpeg` from PATH (ideally with libopencore-amrnb) or `-Dg3.ffmpeg=<file or dir>`; with none the game runs without sound effects (the music is
MIDI and unaffected) and the setup screen says so. Install ffmpeg, then F10 > Game > "Rebuild game data".

## 2. Run-time build (what the app does)

```
GameProvider (desktop/.../byo)   find + verify the jar (JarCheck: SHA-256 whitelist, named wrong builds), cache lookup
  -> ByoBundle                   byo.zip inside the app: game-patches/, docs/mapping/, tools/ (tiny-remapper, Vineflower), game/src/port/java/g3/PortBridge.java
  -> GameBuild (buildtools)      GamePipeline.generate  (the very same code as gradle setupGame)  +  javax.tools compile against the app's classpath + jar packing
  -> game.jar in the cache       classes + the jar's resources (+ audio/*.wav) + MIDlet manifest
  -> -Dg3.game.classpath         existing GameClassLoader path (MIDletLauncher)
```

* The decompile subprocesses run `java -jar` of the pinned tools with the app's own runtime (`java.home`); the jpackage runtime therefore includes `jdk.compiler`
  (compile) and the tools run on the same JDK modules as the app.
* `byo.zip` contains our patches (whose `-` lines are the only original text) and the tools, never a decompiled or compiled game class. It is built by
  `:desktop:byoBundle` from `game-patches/`, `docs/mapping/`, the pinned tools (see "Pinned tools") and `game/src/port/java`.
* The compiled classes behave identically to the build-time ones (same sources, same compiler options: `-source/-target 17 -g`): the determinism traces of the
  run-time built game are byte-identical to the build-time ones (`--trace-ticks`, 2D and 3D, `--verify` 661/661).
* The desktop module's own data (`render3d/*.properties`, `post/luts`) is packaged in the desktop jar, not in the game jar.
* Guard: `./gradlew :desktop:checkNoGameContent` (part of `check`/`build`) fails if the fat jar contains `g3/` classes or jar-derived resources (`*.lng`, `*.mid`,
  `*.amr`, `*.mdl`, the atlas files `a c d f i ldf`, `s00.png`, `s01.png`, `icon.png`, `audio/*.wav`); `packageApp` runs the same check on the app image.

## 3. For developers: how the `game` module is generated

`./gradlew :desktop:run` still uses the game generated at build time (`:game:jar` via `-Dg3.game.classpath`), so the dev loop needs no setup screen; the jar for
`setupGame` is `original/Gothic3-TheBeginning_s40v3a.jar` or `-PgameJar=`. To try the player flow from a checkout run the fat jar:
`./gradlew :desktop:dist && java -jar desktop/build/libs/gothic3-*.jar --jar /path/to/Gothic3-TheBeginning_s40v3a.jar` (use a temporary `HOME`/`XDG_*` to keep your
real config and saves out of it).

The `game` module (package `g3`, the original game logic with our `// PORT:` / `DECOMP-FIX` edits, plus the game's data files) is no longer compiled from
tracked files. It is generated from the user's own copy of the original jar:

```
Gothic3-TheBeginning_s40v3a.jar   (the ONLY supported build; SHA-256 0f02bc1b...cc6cb7f, 416,549 bytes)
   |  verify SHA-256 + size                       game-patches/build.properties
   |  classes -> tiny-remapper 0.14.1 + docs/mapping/*.map -> Vineflower 1.12.0   (fetched from Maven, SHA-256 pinned)
   |  verify SHA-256 of every raw decompiled file                                  game-patches/decompile.sha256
   |  apply game-patches/g3/*.patch                                                (zero-context unified diffs)
   v
game/gen/src/g3/*.java       compiled together with game/src/port/java/g3/PortBridge.java (ours)
game/gen/resources/**        maps, sprites, dialogue, music, models, icon + audio/*.wav + audio/index.properties
game/gen/manifest/MANIFEST.MF  MIDlet-* attributes for MIDlet.getAppProperty (used by the game jar task)
game/gen/pristine/           the raw decompile (needed by makePatches), gitignored
```

`game/gen/` is gitignored. Nothing in `game/gen` is ever committed.

## Builds without a game jar (CI, releases, fresh clones)

The game jar is optional at build time. Gradle looks for `-PgameJar=<path>`, else `original/Gothic3-TheBeginning_s40v3a.jar`. If neither exists, the build logs one line

```
game: no game jar (-PgameJar=... or original/Gothic3-TheBeginning_s40v3a.jar) - the :game module is skipped (setupGame, compile, jar)
```

and the `:game` tasks `setupGame`, `makePatches`, `recordDecompileHashes`, `compileJava`, `processResources` and `jar` are disabled (SKIPPED). Nothing
else depends on `:game` at compile time: `midp`, `port`, `buildtools` and `desktop` build, `./gradlew build`, `:desktop:dist` and `:desktop:packageApp` work, and
the `checkNoGameContent*` guards pass. `:desktop:run` then behaves like a release (first-run setup screen). `-PgameJar=` pointing at a missing file is an error. CI and
the release workflow therefore need no secrets and no jar (the release workflow builds the minimal ffmpeg itself, see "Audio").

### Pinned tools

Vineflower 1.12.0 (`org.vineflower:vineflower`, Maven Central) and tiny-remapper 0.14.1 (`net.fabricmc:tiny-remapper:0.14.1:fat`, maven.fabricmc.net) are not committed.
`./gradlew :buildtools:fetchPipelineTools` resolves them through Gradle (cached in the Gradle cache), checks their SHA-256 against `game-patches/build.properties`
(`*.sha256`; the coordinates are `*.maven` there) and places them in `build/pipeline-tools/`. A mismatch fails the build. `setupGame` and `:desktop:byoBundle` (which packs
them into `byo.zip` for the run-time build) depend on that task. The run-time build re-verifies the same hashes. CFR is not used by the pipeline and not fetched.
`scripts/remap/remap.sh` (reference script) uses `build/pipeline-tools` too.

### Release guard

* `checkNoGameContent` (fat jar) runs in `check`/`build`, `checkNoGameContentImage` (jpackage image) in `packageApp`; `release.yml` additionally runs both as explicit steps on
  Linux, Windows and macOS before anything is uploaded.
* `scripts/check-no-game-files.sh` fails if git tracks `*.lng`, `*.mid`, `*.amr`, `*.mdl`, `*.wav` under `game/`, `*.png` at the root of `game/src/main/resources`, anything in `original/`, `game/src/main/java/g3/`, `reference/decompiled-original/`, `docs/screenshots/` or `tools/*.jar`.
  Both workflows run it as a blocking step.

### Opt-in integration check (developers with the jar, never in CI)

CI has no jar and no secrets. A developer who has the jar checks the whole pipeline locally:

```
./gradlew clean build -PgameJar=/path/to/Gothic3-TheBeginning_s40v3a.jar   # (the -P is not needed with original/ in place)
./gradlew :desktop:dist
# determinism: build the game at run time from the jar and compare the tick trace with a baseline (temporary HOME/XDG dirs, no audio, hidden window)
H=$(mktemp -d); mkdir -p $H/.config $H/.local/share
HOME=$H XDG_CONFIG_HOME=$H/.config XDG_DATA_HOME=$H/.local/share java -Duser.home=$H -jar desktop/build/libs/gothic3-*.jar \
    --jar /path/to/Gothic3-TheBeginning_s40v3a.jar --no-audio --hidden --script scripts/walk-ticks.script \
    --trace-ticks $H/trace.txt --exit-after-ticks 1200 --view3d
```

`trace.txt` must be byte-identical to the one from the baseline commit (same command on the old build).

## Gradle tasks

| task | what it does |
|---|---|
| `./gradlew setupGame [-PgameJar=path.jar] [-Pforce]` | generates `game/gen`. Default jar: `original/Gothic3-TheBeginning_s40v3a.jar`. No-op when nothing changed. Refuses to overwrite hand edits (see below) unless `-Pforce`. `compileJava`, `processResources` and `jar` of `:game` depend on it, so a plain `./gradlew build` / `:desktop:run` sets everything up. |
| `./gradlew makePatches` | regenerates `game-patches/g3/*.patch` from the difference between `game/gen/pristine` and your edited `game/gen/src`; checks that applying the patches reproduces your tree exactly. |
| `./gradlew recordDecompileHashes` | maintainers only: rewrites `game-patches/decompile.sha256` from `game/gen/pristine` (after intentionally changing mapping/tools). |

Requirements: JDK 17+ (decompile output verified identical on JDK 21 and 25), network access for the first Gradle run (pinned tools from Maven), and ffmpeg on PATH only if you want the sound effects (see Audio).

## How to edit game code now

1. `./gradlew setupGame` (once per jar/patch change).
2. Edit `game/gen/src/g3/*.java` like normal source (rebuild/run with the usual tasks; `setupGame` leaves your edits alone: it prints
   "generated sources have local edits - keeping them").
3. `./gradlew makePatches` to turn your edits into `game-patches/g3/*.patch`. Commit the patch files.
4. Do not delete `game/gen` before step 3: `gradle clean` does not touch it, but `setupGame -Pforce` does overwrite it. If the inputs change (git pull brings new
   patches) while you have unsaved edits in `game/gen`, `setupGame` stops and tells you to run `makePatches` or `-Pforce`.

New classes, and anything that is not an edit of a decompiled class, belong in our own sources: `game/src/port/java` (e.g. `g3.PortBridge`) or the `port` module.
Only the five decompiled classes can be patched (`GameEngine`, `GameWorld`, `Gothic3Midlet`, `LoadTask`, `SoundThread`); deleting one is not supported.

Keep `// PORT:` comments in edits as before. Prefer moving logic into `gothic3.port` and leaving one-line hooks in the game classes: every removed or replaced
original line is stored in the patch (see below).

### Patch format

`game-patches/g3/<Class>.java.patch` is a unified diff with **zero context** (`-U0`): one hunk per changed run of lines, addressed by line number
(`@@ -a,b +c,d @@`). The applier (`buildtools/.../Patcher.java`) verifies every removed line against the decompile before applying, so a wrong base fails loudly with
the line number and both texts. Because the raw decompile is also hash-pinned (`decompile.sha256`), a hunk can only ever apply to exactly the intended text.
No external `patch`/`git` is needed. The only original (THQ-derived) text left in the patch set is the `-` lines (the lines that our edits remove or replace).

If `setupGame` reports that the decompile output differs from the pinned hashes: the jar, tools or `docs/mapping` changed, or the JDK running Gradle
decompiles differently. The raw output is kept in `game/gen/pristine`. After an intentional mapping change, `setupGame` fails but keeps `game/gen/pristine`: run `recordDecompileHashes`, then re-create the patches against the new base.

## Resources

Everything in the jar that is not a `.class` or `META-INF/` entry is extracted verbatim to `game/gen/resources` (maps `a c d f i ldf`, sprites, `*.lng`,
`*.mid`, `*.mdl`, `*.amr`, `icon.png`, `s00/s01.png`). Our own resources stay tracked in `game/src/main/resources/{render3d,post}` and are added to the game jar.

### Audio

The JDK cannot decode AMR-NB. (A pure-Java decoder was searched for: Maven Central has none; the only Java-side options are JNI/native wrappers of
OpenCORE or the VO codec, which are not pure Java, so ffmpeg stays the converter and a missing ffmpeg means "no sound effects", see part 1.) `AmrConverter` runs, per `.amr`:

```
ffmpeg -v error -y -c:a libopencore_amrnb -i NN.amr -ar 22050 -ac 1 -c:a pcm_s16le audio/NN.wav
```

(`-c:a libopencore_amrnb` only when that decoder exists. ffmpeg's native `amrnb` decoder is NOT usable: the game's sounds are 6.7 kbps frames plus SID / NO_DATA
frames, and the native decoder reports "Corrupt bitstream" / "Not yet implemented" and truncates 12 of the 25 files, tested with ffmpeg 9.0.2) and writes
`audio/index.properties` (`sha1(original amr bytes)=audio/NN.wav`, which the MIDP shim uses at run time).

**Which ffmpeg** (`AmrConverter.findFfmpeg`): `-Dg3.ffmpeg=<file or dir>`, then the bundled `<app>/ffmpeg/ffmpeg[.exe]` (directory next to the app jar), then PATH.
The bundled one is a minimal static build made by `scripts/ffmpeg/build-minimal-ffmpeg.sh <out> [work]` (pinned FFmpeg 9.0.2 + opencore-amr 0.1.6, SHA-256
checked; only the `amr` demuxer/parser, `libopencore_amrnb`, `aresample`/`aformat`/format filters, `pcm_s16le`, `wav` muxer and `file` protocol; about 1.2 MB). OpenCORE
requires `--enable-version3`, so the binary is LGPL-3.0-or-later (Apache-2.0 for opencore-amr), not GPL; it is a separate executable, licence files and the
exact flags ship in `ffmpeg/LICENSES/` and in `NOTICE`. `scripts/ffmpeg/test-minimal-ffmpeg.sh <out>` smoke-tests it with a synthetic AMR file (no game data).
Packaging: `:desktop:appImage` / `packageApp` copy `-PffmpegDir=<out>` (or env `G3_FFMPEG_DIR`) to `<app>/ffmpeg/`; `release.yml` builds it per OS (job `ffmpeg`,
cached by script hash), `ffmpeg.yml` is the manual / path-triggered build check. Compared with a system ffmpeg's OpenCORE the WAVs are sample-identical (+-1) for
23 of 25 files, 2 files differ slightly (comfort-noise SID frames). Nothing hashes the WAV bytes (cache key = jar SHA + byo bundle hash).

Pure-Java AMR-NB decoding would be a few thousand lines of codec and tables, so it was not done here.

## Layout

```
game-patches/build.properties     jar name/SHA-256/size, tool paths + SHA-256
game-patches/decompile.sha256     SHA-256 of each raw decompiled file
game-patches/g3/*.patch           our edits
buildtools/                       GameSetup (gradle), GamePipeline + GameBuild + JarCheck (shared with the app), Patcher, TinyMapping, AmrConverter (plain Java, no dependencies)
desktop/.../byo/                  run-time side: GameProvider (locate/verify/build/cache), SetupScreen, ByoBundle, FileDialog (tinyfd)
game/src/port/java/g3/            our own classes that live in package g3 (PortBridge)
game/gen/                         generated, gitignored
```

### Forcing the jar-less build

`-PnoGame` skips the `:game` module even when `original/` holds a jar. CI and `release.yml` always pass it, so they build exactly what players download.
