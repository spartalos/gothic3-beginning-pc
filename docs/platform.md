# Platform layer (desktop port of the J2ME MIDP runtime)

The ORIGINAL game jar (`original/Gothic3-TheBeginning_s40v3a.jar`, default-package classes `HG`, `A`, `B`, `C`, `D`)
runs unmodified on top of a desktop MIDP implementation.

```
game thread(s) -> midp shim (CPU framebuffer, int[] ARGB) -> FrameBuffer (locked hand-off) -> presenter (libGDX)
key events: libGDX thread -> InputState -> DisplayHost.postKey -> "MIDP-UI" thread -> Canvas.keyPressed/keyReleased
```

## Modules
| module | contents |
|---|---|
| `midp` | pure Java (no libGDX). `javax.microedition.{lcdui,lcdui.game,midlet,rms}` (+ `media` owned by the audio agent). Internal package `gothic3.platform`: `Platform` (screen size, UI thread, exit hook, props), `FrameBuffer` (published frames), `GameClassLoader`. Non-MIDP bridges: `lcdui.DisplayHost`, `midlet.MIDletLauncher`. Bundled font: Liberation Sans (OFL). |
| `game` | generated from the user's jar by `setupGame` (docs/byo-jar.md): patched decompiled `g3` classes + extracted game data + audio. The original jar is exposed as `project(':game').ext.originalJar` and is deliberately NOT on the app classpath. |
| `desktop` | libGDX lwjgl3 launcher `gothic3.desktop.DesktopLauncher`, `G3App` (presenter), `InputHandler`/`InputState`, `Config`, automation (`FrameDumper`, `ScriptRunner`). libGDX 1.14.2, gdx-controllers 2.2.4. |

Why a class loader: the game's resource reader (`/i` archive) relies on J2ME stream behaviour (mark/reset, full `read`, exact `skip`). JDK jar streams violate that and
produced EOFExceptions. `GameClassLoader` (parent-first, loads the game from `-Dg3.game.classpath`) serves all resources as in-memory streams. A future recompiled
game can be loaded the same way (point `g3.game.classpath` at its classes).

## Build / run
```
./gradlew :desktop:run                       # needs JDK 17+ (tested JDK 25), Gradle 9.8.1 via wrapper
./gradlew :desktop:run --args="--scale 4"
```
Gradle `run` uses the repo root as working dir.

## Building & releases
Version: `version=` in `gradle.properties` (single source; currently 0.1.1). Needs JDK 17+ (CI uses Temurin 21; jpackage ships with the JDK).
```
./gradlew build                    # compile + tests, all modules
./gradlew :desktop:run             # run from the checkout (working dir = repo root)
./gradlew :desktop:dist            # desktop/build/libs/gothic3-<version>.jar   runnable fat jar (natives for Linux/Windows/macOS)
./gradlew :desktop:installDist     # desktop/build/install/desktop/bin/desktop(.bat)  launcher scripts (needs a JDK on PATH)
./gradlew :desktop:packageApp      # desktop/build/dist/gothic3-<version>-<os>-<arch>.tar.gz (Linux) / .zip (Windows, macOS)
```
Fat jar: `java --enable-native-access=ALL-UNNAMED -Djava.awt.headless=true -jar gothic3-0.1.1.jar [flags]`.

`packageApp` runs `jpackage --type app-image` with a jlink-trimmed runtime (about 75 MB unpacked, 55 MB archive) and must be run on the target OS (no cross packaging):
Linux `Gothic3/bin/Gothic3`, Windows `Gothic3\Gothic3.exe`, macOS `Gothic3.app` (arm64 when built on the arm64 runner; the launcher adds `-XstartOnFirstThread`, needed by LWJGL/GLFW; the app is
unsigned, so Gatekeeper needs right-click > Open or `xattr -dr com.apple.quarantine Gothic3.app`). All command line flags work on the launcher (e.g. `Gothic3 --view3d --no-audio`).

Runtime resources: the fat jar / app image contain NO game classes and NO data of the original game (bring your own jar: the game is built at first start from the player's own `Gothic3-TheBeginning_s40v3a.jar` into `~/.local/share/gothic3-pc/game/`, see `docs/byo-jar.md`; `./gradlew :desktop:checkNoGameContent` guards this). Inside the jar: Liberation Sans, `render3d/*.properties`, LUTs, and `byo.zip` (patches, mapping, pinned decompile tools). The runtime image includes `jdk.compiler` for the in-process compile. The
`game/src/main/resources/render3d/*.properties` files in the working directory are only preferred when they exist (live tuning from a checkout); in an install the bundled copies are used.
Nothing is read from the repo at run time. User files go to the per-user directories described above (config `~/.config/gothic3-pc/` or `$XDG_CONFIG_HOME/gothic3-pc/`,
saves `~/.local/share/gothic3-pc/rms/`; on Windows/macOS `~` is the user profile / home directory, so `%USERPROFILE%\.config\gothic3-pc\`). The app directory is never written to.

Cutting a release (`.github/workflows/release.yml`):
```
# bump version= in gradle.properties, commit, push, then
git tag v0.1.0 && git push origin v0.1.0
```
The workflow builds and tests on ubuntu / windows / macos runners, uploads the three app-image archives plus the fat jar and creates a GitHub Release with generated notes. It fails if the tag does
not match `gradle.properties`. "Run workflow" on the Release action (workflow_dispatch) builds everything and keeps the files as workflow artifacts without creating a release.
Settings: Actions must be enabled for the repo; the release job requests `contents: write` itself, but under Settings > Actions > General > Workflow permissions "Read and write permissions" may be
needed if the organisation/repo default restricts it. `ci.yml` runs `./gradlew build` on pushes and PRs to main.

## Screen / lifecycle
* Logical canvas size is a preset (see "Screen presets" below); the original is 240x320 (Series 40 3rd ed.). The game reads `getWidth()/getHeight()` into
  `GameEngine.screenWidth/screenHeight` and lays everything out from them, so any size works. Override: `screen.preset`, `screen.width/height` in config,
  `--view`, `--width/--height`, or `-Dg3.width/-Dg3.height`.
* `MIDletLauncher.launch("HG")` instantiates via reflection on a game thread ("MIDlet-start"); the HG constructor does all start-up and spawns the game loop thread.
  `getAppProperty` serves the jar manifest; `platformRequest` logs and returns false; `notifyDestroyed` closes the window and exits.
* Presentation: nearest-neighbour, integer scale, centred, letterboxed (fractional only if the window is smaller than 1x). F11 / Alt+Enter toggles fullscreen.

## Screen presets (widescreen, phase 1)
| preset | logical size | tiles shown (16 px) | notes |
|---|---|---|---|
| `wide` (default) | 640x360 | 40 x 22.5 | exact integer scale on 720p/1080p/1440p/4K (2/3/4/6x); at least the original's rows |
| `tight` | 480x270 | 30 x 16.9 | 4x on 1080p, 8x on 4K; only 17 rows: many 12x17-tile areas do not fit vertically, camera then scrolls/centres (playable, tight vertically) |
| `classic` | 240x320 | 15 x 20 | original; pillarboxed in a 16:9 window; void around small maps stays plain black |
| `WxH` | custom | | e.g. `--view 427x240` |

Selection order: `--width/--height` > `--view` > config `screen.width`+`screen.height` (both) > config `screen.preset` (default `wide`). Note: an old config
file that contains `screen.width = 240` / `screen.height = 320` keeps forcing classic: delete those two lines. The presenter keeps nearest-neighbour integer
scaling. Initial window = logical size * `window.scale`; `auto` (default) picks the largest integer scale that fits about 90 percent of the display
(640x360 -> 2x = 1280x720 on a 1080p display, 240x320 -> 3x).

Gameplay is identical at every size (verified with `--trace-ticks`, see docs/analysis/widescreen.md "Phase 1 implementation"): target selection and the
lock-on camera framing use a fixed virtual 240x320 view (`GameEngine.GAMEPLAY_VIEW_W/H`, tick-driven "logic camera" `logicCamX/Y`); the real camera
`cameraX/Y` is presentation only. Widgets are capped/centred on wide screens (dialogue text 320 px, message box 320 px, target bar and interact label
240 px, quickslot gap 12 px). Outside the map (small maps in a wide view) stays plain black like the original (an edge-tile smear faded to black existed once, `GameEngine.voidFillEnabled()`, now off). Cells left of and above the map are addressed via floorMod.

## Input
Game key codes actually handled (A.keyPressed/keyReleased): UP -1, DOWN -2, LEFT -3, RIGHT -4, FIRE -5, soft-left -6, soft-right -7, `0-9` 48-57, `*` 42, `#` 35.
`keyRepeated` is empty in the game and is never generated; OS auto-repeat is filtered (press only when the first source goes down).
Events are delivered on a dedicated "MIDP-UI" thread, like a phone's UI thread; the game polls its own state arrays.

Config file: `$XDG_CONFIG_HOME/gothic3-pc/config.properties` (default `~/.config/gothic3-pc/`), created on first run. Format: java properties.
```
screen.preset (wide|tight|classic|WxH), screen.width/height (override), window.scale (auto|N), window.fullscreen, window.vsync
render.mode = interpolated | classic      (default interpolated; see "Render modes")
render.maxFps = 0 | N                      (0 = follow the display / vsync)
debug.overlay = false                      (F3 toggles at run time)
key.<MIDP_KEY> = libGDX key names, comma separated      (MIDP_KEY: UP DOWN LEFT RIGHT FIRE SOFT_LEFT SOFT_RIGHT NUM0..NUM9 STAR POUND)
pad.<MIDP_KEY> = A B X Y BACK START L1 R1 L_STICK R_STICK DPAD_UP DPAD_DOWN DPAD_LEFT DPAD_RIGHT
pad.stick.enabled / pad.stick.deadzone   (left stick -> directions, default 0.5)
```
Defaults: arrows/WASD move; Enter/Space/NumpadEnter = FIRE; Q,F1 = soft-left; E,F2,Esc,Backspace = soft-right; number row and numpad = 0-9; Numpad* = `*`; F4/Numpad/ = `#`. **F3 is the FPS / tick overlay** (fixed, no longer `*`; an old config that lists F3 for `key.STAR` is ignored for F3).
Pad: d-pad + left stick = directions, A = FIRE, X/Start = soft-left, B/Back = soft-right, Y = pause (0), L1 = cycle target (`*`), R1 = quick slots (`#`).

Controls, as the game uses them (from `GameEngine.updateInput` / `ACTION_KEY_MAP`):

| MIDP key | in the world | in menus / dialogue |
|---|---|---|
| UP DOWN LEFT RIGHT (and NUM2 8 4 6) | walk; NUM1 3 7 9 are the diagonals | select entry; LEFT / RIGHT switch inventory category, stats page, quest filter |
| FIRE (and NUM5) | interact (talk, pick up, open, enter area) and attack: one action | activate selected entry, advance dialogue |
| SOFT_LEFT | character menu (menu 18), or enter the marked map location | left softkey action of the screen |
| SOFT_RIGHT | system menu (menu 14: save / load ...) | right softkey action, usually back |
| NUM0 | pause (pause screen also takes cheat digits) | typing digit in quantity dialogs |
| STAR | cycle target | inventory: switch own items / container view |
| POUND | open quick-slot ring | close ring, clear typed quantity |
| NUM1-NUM8 | (movement, see above) | inside the quick-slot ring / assigning: pick slot 1-8; optional inventory item shortcuts on NUM1 (use), NUM3, NUM7, NUM9, # |

Key layout history (`keys.layout`, see `Config.migrateKeyLayout`): v0.1.1 briefly shipped different defaults (layout 2: E,I,P,Tab,T,R). They were reverted: a config saved with `keys.layout = 2` is migrated once to layout 3 (= the original defaults above); `key.FIRE / SOFT_LEFT / SOFT_RIGHT / NUM0 / STAR / POUND` entries that still equal the layout 2 defaults are reset, entries you edited stay, and a key you bound yourself is dropped from the reset value so it never ends up on two MIDP keys. A config without `keys.layout` already holds the original defaults and is just stamped 3. Gamepad defaults are unchanged.

## Startup questions (sound, 2D/3D)
The original game asks "Activate sound?" (menu 17, answers = menu actions 9 / 10, stored in the game's own RMS settings record) on every start. The port keeps that
question and adds a second one in the same menu layout, "Graphics?  2D (original) / 3D", shown right after it (`Gothic3Midlet.portAfterSoundAnswer`, texts drawn by
`getEntryText`; they are not in the language files). The bridge to the desktop config is `gothic3.platform.StartupPrefs` (midp).

* **First run** (`firstRun.done = false`): sound question, then graphics question. The 3D answer switches the renderer at once. Answers are saved: `audio.enabled`, `render.view`, then `firstRun.done = true`.
  Quitting between the two questions saves only the sound answer and asks both again next time.
* **Later runs**: both questions are skipped. `GameEngine` calls `executeMenuAction(audio.enabled ? 9 : 10)` instead of showing menu 17, so the game state is exactly what answering would set; `render.view` is applied by the launcher as before.
* **Changing it later**: Options > Audio > "Sound (game setting)" (`audio.enabled`, applies on restart; "Mute" and the volume stay live) and Options > 3D > View (`render.view`, live). Options > Game > "Ask startup questions again" sets `firstRun.done = false` (or delete the key).
  F5 only switches the view for the current session and is not saved (so it never fights `--view3d`); the Options "View" item is the saved choice.
* **Flags win**: `--no-audio` always silences the output regardless of `audio.enabled`. `--view3d` / `--render-view` override `render.view`, and when one is given the graphics question is not asked (`render.view` is not touched; `firstRun.done` is still set after the sound answer).
* **Determinism**: the flow is active only for interactive launches (no `--script`, `--trace-ticks`, `--deterministic`, `--verify`; `-Dg3.startup.force=true` forces it, used for screenshots). Automated runs therefore keep the original flow tick for tick and never read or write the questions' config keys. The "graphics" phase flag lives in `StartupPrefs`, not in a `Gothic3Midlet` static, because the state hash covers game statics.
* Test runs: point `XDG_CONFIG_HOME` / `XDG_DATA_HOME` at a temp dir; `Config.load()` creates the config file there.

## Hotkey hints
The presenter (`G3App.drawMenuHint`) draws a one-line footer "F5: 2D/3D   F3: status   F10: more settings" (with "Start+Back" when a gamepad is connected) along the bottom of the window while the original game's Settings menu (menu 2) is
open. It is drawn after the game frame, never into the game framebuffer, so frame dumps (`shot`), traces and `--verify` are unaffected. The F10 options screen shows the same reminder in its footer. 

## Fast forward (F8)
`F8` (keyboard) or a click on the right stick (`R_STICK`, gamepad) toggles fast forward: the 10 Hz logic runs `game.fastForwardSpeed` times faster (default 3, allowed 2..8, editable in the F10 "Display" tab);
pressing the key again returns to 1x. It is a toggle (not a hold), a fixed hotkey that is never forwarded to the game (like F3/F5), and rebindable in `config.properties`
(`fastforward.key` = libGDX key name, default `F8`; `fastforward.pad` = gamepad button, default `R_STICK`; empty = unbound). While active a small ">> 3x" badge is drawn in the top right corner (2D and 3D, presenter only).
Implementation: `RenderConfig.speed()` (volatile); `PortLoop` divides its tick interval by it (interpolation alpha uses the current interval; changing the speed re-bases the schedule at the same position inside the current tick, so
there is no burst or jump; the classic loop sleeps `100 / speed` ms). The game's wall clock (`Port.millis()`, used by splash/dialogue/timers) becomes a virtual clock advancing by wall time * speed (monotonic, accumulated at speed
changes, still excluding options-screen pauses). Music and SFX are untouched (music keeps its pitch; at most one deferred SFX is played per tick, so nothing queues up). `--deterministic` / `--trace-ticks` runs ignore the speed completely
(`RenderConfig.setSpeed` is a no-op there). Script command: `speed N` (1 = normal).

## Options screen (F10)
`F10` (keyboard) or `Start + Back` (gamepad; both are also the default soft keys, so it is a chord) opens an in-game options panel on the left of the window; `Esc` / `B` / `F10`
or "Back to game" closes it. Fixed keys: it is not rebindable, and F10 is rejected as a binding. A one-time hint ("F10: options") is shown on the first interactive start
(`options.hintShown` in the config; not shown with `--hidden`, `--script` or `--trace-ticks`).

* **Pause**: while open the game does not tick (`RenderConfig.setPaused`; honoured by `PortLoop.run` and, for `render.mode = classic`, by `PortLoop.beginTick`). The last
  frame stays on screen and the 3D view keeps rendering it, so camera / lighting changes preview live. On resume the tick and frame schedule is shifted by the pause, so there
  is no catch-up burst; the game's wall clock (`Port.millis`) excludes the paused time. All held keys / buttons are released when it opens. Music keeps playing. Game state
  never changes because of the overlay (and never at all when it is not opened: `--trace-ticks` hashes are identical to before the feature).
* **Navigation**: keyboard Up/Down select, Left/Right change a value, Enter/Space activate, Tab / Shift+Tab (or PageUp/PageDown) switch tabs, Esc back, Del/Backspace clear a
  binding slot. Mouse: hover selects, click changes (left half of a choice = previous, right half = next; drag sliders), wheel scrolls. Gamepad: D-pad or left stick,
  A select, B back, X clear a slot, L1/R1 tabs (stick and D-pad repeat while held). The panel scales with the window height (readable from 720p to 4K).
* **Persistence**: every change is written to `config.properties` about half a second later and when the screen closes. Existing lines are rewritten in place, so comments
  and order are kept; keys that are not in the file yet are appended at the end under `# --- added by the in-game options screen`. New keys: `options.hintShown`,
  `audio.volume` (0..100), `audio.mute`, `audio.enabled`, `firstRun.done` (see "Startup questions").

| tab | item | takes effect |
|---|---|---|
| Display | Screen preset (`screen.preset`) | **restart** (also clears `screen.width/height`, which would override the preset) |
| | Window scale (`window.scale`) | live when windowed |
| | Fullscreen (`window.fullscreen`) | live (same as F11) |
| | VSync (`window.vsync`) | live |
| | Render mode (`render.mode`) | **restart** (the loop is chosen once at start) |
| | Max FPS (`render.maxFps`: display rate / 60 / 120 / 144 / 165 / 240 / unlimited = 1000) | live (the game loop re-reads it every iteration) |
| | FPS overlay (`debug.overlay`) | live (same as F3) |
| 3D | View 2D/3D (`render.view`) | live (same as F5) |
| | pitch, distance, fov, focus offset, lighting, shadows off/low/high, sun azimuth / elevation / strength, ambient, surround, fog, houses and walls (`render3d.*`) | live: written to a private tune file that `Renderer3D` re-reads (`Renderer3D.reloadSettings`, the `-Dg3.render3d.tune` mechanism), throttled to one rebuild per 120 ms. Not available when you pass your own `-Dg3.render3d.tune` (then use F6) |
| Atmosphere | snow, embers, smoke, dust motes, light shafts, rim light, soft / tinted / contact shadows, time of day (off / morning / noon / evening / night / cycle), window glow, ground mist: on/off + sliders (`atmo.*`) | live, at once (`Renderer3D.reloadAtmosphere`, no mesh rebuild); data table `render3d/atmosphere.properties`, see `docs/render3d.md` "Atmosphere" |
| Effects | HD-2D post-processing: preset (Off / Subtle / HD-2D / Custom), depth of field (mode, blur, focus, bands), bloom, SSAO, tone mapping, exposure / contrast / saturation / temperature / tint, grade / LUT, vignette, grain, chromatic aberration, sharpen, FXAA (`post.*`); 2D: upscaler, bloom, grade, vignette, grain, CRT (`post2d.*`) | live (read from the config every frame it changes; see docs/post.md). A preset writes all `post.*` keys, touching one switches the preset to Custom |
| Keyboard | primary + secondary key for every MIDP key (`key.*`) | live |
| Gamepad | primary + secondary button for every MIDP key (`pad.*`), left stick on/off, dead zone (`pad.stick.*`) | live |
| Audio | master volume, mute | live: `PlayerFactory.setMasterVolume` sets the gain of every open WAV/AMR clip (`MASTER_GAIN`) and sends a MIDI master-volume SysEx to the shared synthesizer; `--no-audio` is unaffected |
| Game | Back to game, Quit game (confirmation inside the panel) | |

Each tab ends with "Reset this tab to defaults". Items that need a restart are marked "(restart)"; once one differs from its value at launch the footer shows
"Restart required: ...". There is no automatic restart.

Rebinding: Enter (or click) on a slot starts "press a key..." (keyboard 15 s; gamepad 6 s, press a button); Esc cancels, Backspace/Delete clears. F3 F5 F6 F7 F8 F10 F11 are
reserved and refused. If the key / button is already used by another slot a dialog offers to swap (the other slot receives the one you replace, or just loses the binding).
Only the first two entries of a comma list in the config are shown; more entries are kept and shown as "+N". Clearing the primary slot moves the secondary up.

Automation (`ScriptRunner`): `opt open|close|toggle|key NAME|pad NAME [up]|stick X Y|tab N|sel N|col N|mouse X Y|click|release|wheel N|get KEY|set KEY VALUE|bound KEY|state`,
`speed N` (fast forward multiplier, 1 = normal; ignored in deterministic runs), `wshot name` (saves the presented window, including the overlay, as `shot_name.png` in `--frames-dir`; the plain `shot` saves only the logical game frame in 2D).
Code: `desktop/.../options/` (`OptionsOverlay` drawing + input, `OptionsMenu` tabs / apply / config, `Item`, `WindowControl`).

## RecordStore
`$XDG_DATA_HOME/gothic3-pc/rms/<urlencoded name>.rms` (default `~/.local/share/gothic3-pc/rms/`), rewritten atomically per change; ids start at 1 and are never reused;
`getSizeAvailable()` = 8 MiB - used. Override dir with `-Dg3.rms.dir`.

## Automation / verification
```
./gradlew :desktop:run --args="--frames-dir out/frames --frame-every 1000 --script scripts/smoke.script --exit-after 80000"
```
| flag | meaning |
|---|---|
| `--frames-dir <d>` | dump the logical framebuffer (only when it changed) as `frame_<ms>.png`; `shot_<name>.png` for script `shot` and `shot_final.png` at exit |
| `--frame-every <ms>` | dump interval (default 1000) |
| `--window-frames` | also dump the PRESENTED backbuffer (scaled, letterboxed) to `<frames-dir>/window/` |
| `--script <f>` | commands: `wait ms`, `press K`, `release K`, `tap K [holdMs]` (default 150, then 150 wait), `shot name`, `exit`, `# comment`. K = UP DOWN LEFT RIGHT FIRE SOFT_LEFT SOFT_RIGHT NUM0..9 STAR POUND or an int |
| `--exit-after <ms>` | quit after N ms |
| `--scale n`, `--fullscreen`, `--width/--height`, `--view <preset>`, `--midlet <class>` | misc |

`scripts/smoke.script` drives splash -> sound prompt -> main menu -> New game -> intro dialogue -> gameplay -> movement (about 80 s).
(Screenshots are not included in the repository: they show the original art.)

## Render modes (10 Hz logic, display-rate rendering)
The original loop (`GameEngine.run`) ran input -> logic -> render -> sleep at 10 Hz. The port keeps logic at exactly that rate and bit-for-bit
unchanged but can draw at the display refresh rate:

| | |
|---|---|
| `render.mode = classic` | original behaviour: one frame per tick, 10 fps |
| `render.mode = interpolated` (default) | fixed-step loop (`gothic3.port.PortLoop`): a tick runs whenever `System.nanoTime() >= nextTick` (`nextTick += 100 ms`; after a stall at most 3 ticks are run back to back, then the clock is resynced, no fast-forward). Between ticks the game thread renders in-between frames with camera and mobile-entity positions interpolated between the previous and current tick (`alpha = (now - tick time)/100 ms`, so the picture lags the logic by at most one tick). |
| `render.maxFps = 0` | vsync driven: the presenter (libGDX render thread) calls `Platform.pacer().frameWanted()` once per presented frame; the game thread waits for it (timed wait, never busy waiting), renders one frame and publishes it |
| `render.maxFps = N` | the game thread paces itself at N fps (fixed schedule), independent of the presenter. With `window.vsync=false` the presenter is capped at N as well (otherwise at max(60, display rate)) |

Threading: **all game state is touched by the game thread only.** The libGDX thread only copies published pixels and signals `FramePacer`.
In-between frames take the map-loader lock while interpolated values are in the entity rows; `TickInterpolation.end()` + `RenderMutations`
restore the exact tick state before the lock is released.

Frames that are not interpolated (boot, splash, loading, menus, dialogue, message boxes, inventory, pause, lock picking) show the tick's frame (as
classic mode) and no extra frames are produced. If no in-between frame was shown for a tick (e.g. the presenter does not signal) the tick's frame is
published when the next tick starts. Input is still sampled once per tick (original latency, plus up to one tick of display lag).

Design notes / API (`port/src/main/java/gothic3/port/`):
* `TickInterpolation` - `capture()` at the end of every tick, `interpolatedX/Y(entityIndex)`, `cameraX/Y()`, `setAlpha()` read-only queries (for a future 3D
  renderer), `begin()/end()` write-through for the unmodified 2D renderer. Entities: flag 8 (free pixel position, so not items / objects) active in both
  snapshots, same kind, moved <= 1 tile (projectiles 8, effects/spell casts 2) per tick; whole table skipped after a map change; camera skipped after
  a map change or a jump > screen width. Interpolated camera is integer pixels.
* `RenderMutations`, `SnapshotRandom` - make in-between frames free of side effects, see docs/analysis/render-side-effects.md.
* `Port` - game clock (`Port.millis()`), RNG construction, loader start; `TickTrace`, `StateHasher` - verification.

## Automation / verification of the render modes
| flag | meaning |
|---|---|
| `--render-mode classic\|interpolated`, `--max-fps N`, `--vsync on\|off` | override config |
| `--overlay` | start with the F3 overlay (presented fps, game frames/s, logic Hz, frame/tick cost); drawn by the presenter, not into the game framebuffer |
| `--perf-log stderr\|file` | one line per second: presented / uploaded / game frames per second, tick rate, mean frame and tick time |
| `--hidden` | create the window invisible (machines whose compositor does not map the window; vsync pacing then follows the hidden window) |
| `--deterministic` | virtual game clock (tick * 100 ms), loads run synchronously on the game thread right after the tick that started them, fixed RNG seed (20080101, or `--fixed-seed N` / `-Dg3.fixedSeed`), fresh empty RecordStore directory (unless `-Dg3.rms.dir`) |
| `--trace-ticks f` | implies `--deterministic`. One line per logic tick `t=<n> gs=<gameState> s=<hash of all static fields of all g3 classes + RNG> f=<hash of the canonical frame>` (+ `a1=` hash of the alpha-1 frame with `--verify`). `--trace-fields` adds `f.fields` with one hash per field and tick. Excluded from the hash (render caches / loader-thread fields): image/model caches, `bgTileX/Y/bgDirty`, `LoadTask.progress`, the real render camera, and layout caches that depend on the logical screen size (see `StateHasher.EXCLUDED`). |
| `--verify` | after every tick that allows it: render extra frames at alpha 1, 0, 0.5; check the alpha-1 frame equals the canonical frame pixel for pixel and that no hashed state changed; also lists the fields the canonical render itself mutates. Report written to `<trace>.verify` / stderr at exit |
| `--dump-ticks 400,500 --dump-dir d` | PNGs of the canonical frame (and the alpha-1 frame with `--verify`) for these ticks |
| `--frame-log f` | one line per rendered world frame: kind (C canonical, I in-between), tick, alpha, camera x/y, player x/y in pixels |
| `--burst dir N [--burst-after-tick T]` | the next N *presented* frames (uploads) as PNG + `burst.csv` |
| `--exit-after-ticks N` | quit after N logic ticks |
| script `waitticks N`, `tapticks K [hold=2] [gap=1]` | tick-based waits (absolute schedule from tick 0), so key events land on the same tick in every run; `scripts/smoke-ticks.script` and `scripts/walk-ticks.script` are tick versions of the smoke script (+ a long walk) |

Typical comparison (classic vs interpolated must give identical per-tick state hashes):
```
./gradlew :desktop:run --args="--render-mode classic      --trace-ticks out/c.trace --script scripts/walk-ticks.script"
./gradlew :desktop:run --args="--render-mode interpolated --trace-ticks out/i.trace --script scripts/walk-ticks.script --verify"
# compare the s= fields of both files (f= may differ at tick 0 only: the game thread starts before gameState is set)
```

## 3D view (optional, prototype)
`render.view = 2d|3d` (config), `--view3d` / `--render-view 2d|3d`, F5 toggles at runtime, F6 reloads the tile table, F7 opens the in-game tile editor (`docs/render3d.md`; it takes the keyboard / mouse while open). A Pokemon3D-style diorama
renderer draws the world from a tilted perspective camera; HUD / menus stay 2D on top. The game thread records the world pass instead of rasterising it
(`gothic3.platform.scene3d.DrawCapture`, `Graphics.setCapture`), publishes an immutable `Scene3D` with the frame (`FrameBuffer.publish(argb, w, h, scene)`)
and the GL thread (`desktop/.../render3d/Renderer3D`) draws it. Atmosphere effects (config block `atmo.*`: snow / embers / smoke / dust / shafts / rim / softShadows / shadow.* / tod / mist, F10 "Atmosphere" tab, `docs/render3d.md` "Atmosphere"). Config keys `render3d.pitch/fov/distance/focusOffset/lean/vscale/fog/tiles`, overhead interaction icon `render3d.iconLift` (extra tiles above the 2D position, which is already the sprite height of the NPC + 5 px; default 0.3) / `render3d.iconScale` (default 1.2; the icon and target reticle are anchored at the entity's feet via `DrawCapture.markerAnchor`), map surround `render3d.surround/surround.tiles/surround.color`, entity capture margins for deterministic runs `render3d.capture.margin`, lighting block `render3d.lighting/shadows/sun.azimuth/sun.elevation/sun.strength/ambient/indoor.tilesets/blobShadows` (sun lighting + sun shadow map, see `docs/render3d.md`), props block `render3d.props` (off|voxel|full) / `render3d.props.characters` / `render3d.props.characterDepth` and fire point lights `render3d.pointLights` / `.max` / `.intensity` (3D models for object sprites, `render3d/props.properties`, see `docs/render3d.md` "Props and point lights"); while 3D is on, PAGE_UP/PAGE_DOWN pitch, MINUS/EQUALS and wheel distance, LEFT_BRACKET/RIGHT_BRACKET fov, HOME reset. Script commands `view`, `reload3d`, `ed ...` (tile editor),
`warp` (with `-Dg3.debug=true`), `repl`. Design, tile table format, limitations: `docs/render3d.md`.

## Freeze diagnostics / audio threading
* A daemon `freeze-watchdog` thread (desktop) watches the completed-tick counter; if no tick completes for 5 s while not paused (armed after the first tick) it writes `freeze-<timestamp>.txt` (full thread dump with lock owners plus deadlock detection) to the config dir (`~/.config/gothic3-pc/`) and logs a line to stderr, once per stall. A manual dump of a hung process: `jstack <pid>`.
* All real audio device calls (`AsyncBackend`) run on one `media-audio` thread; the game thread only posts commands (queries wait at most 200 ms, `open` 5 s), so a stuck JDK sound stack can silence audio but not freeze the game. `BasePlayer.getState()` is lock-free. The MIDI backend never rewinds the JDK sequencer from the end-of-track thread (JDK `RealTimeSequencer` lock-order inversion TempoCache/DataPump); the rewind is applied by the next `play()`.

## Known issues / notes
* Font is Liberation Sans rendered without anti-aliasing (sizes 10/12/14 px); widths differ slightly from the Nokia font. Monospace face maps to the same font.
* Graphics: arcs, round rects, triangles are simple approximations (the game does not use them). DOTTED stroke is basic. No `Sprite`/`Layer`/high-level UI (`Form`, `Alert`) - unused.
* In `classic` mode the game runs the original fixed 10 fps loop and polls key arrays; a tap shorter than ~100 ms can be missed exactly as on hardware (script `tap` holds 150 ms).
* Audio is provided by the separate media package; not evaluated here.
* Gradle prints JDK 25 native-access warnings from LWJGL; harmless.
* `installDist` does not yet bundle the original jar / `g3.game.classpath`; only `:desktop:run` is wired.

## Module layout (bring-your-own-jar prep)
`midp` (MIDP shim) <- `port` (our hooks API and logic, package `gothic3.port`: `GameBridge`/`GameHooks`, `PortLoop`, `Render3DHook`, `TickTrace`, ...) <- `game` (decompiled `g3.*`; implements `GameBridge` in `g3.PortBridge`, registered from `GameEngine` static initializer) and `desktop` (depends on `port`/`midp` at compile time, on `game` only at runtime). Nothing outside `game` references `g3.*` at compile time; tools reach the game through `GameHooks.bridge()`.
