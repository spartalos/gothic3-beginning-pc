# Widescreen / arbitrary logical resolution - feasibility and plan

Status: analysis only (no code changed in the repo). Line numbers refer to `game/src/main/java/g3/GameEngine.java` ("GE") and `Gothic3Midlet.java` ("GM")
as of this analysis; the main-loop refactor will shift GE numbers, so use the method names.

## 0. Summary

* **Feasible, and mostly already works.** The game derives essentially all layout from two statics, `GameEngine.screenWidth/screenHeight` (GE:23-24, copied from
  `getWidth()/getHeight()` in the constructor, GE:12226-12227, and in `sizeChanged`, GE:12772). Running with `--width 427 --height 240`, `320x180`, `640x360`,
  `960x540` and `1280x720` produced correct, playable output on every screen I tried (splash, loading, main menu, in-game menus, dialogue, message box, quickslots,
  world map, normal maps) with zero code changes.
* **What is NOT free (the real work):**
  1. one gameplay rule depends on the view size: `isNpcTargetable` (target cycling) and the lock-on camera framing in `updateCamera` (section 2a);
  2. the *content* is tiny: 34 of 52 maps are <= 15 tiles wide, so a wide view mostly shows black void (section 1, 3);
  3. several widgets spread out / stretch ungracefully at >= 400 px width (quickslot ring, target bar, message box, dialogue lines) - cosmetic;
  4. changing the size at runtime (window resize -> new logical size) needs plumbing (canvas buffer, bg buffer, layout caches) - only needed if we want a
     dynamic aspect-adaptive mode, not for fixed presets.
* **Recommended:** a fixed list of logical presets chosen at startup (phase 1, ~1-2 days incl. the targeting decoupling and UI caps), then optionally a dynamic
  "logical height 360, width = window/scale" mode (phase 2, ~2-3 days). Keep one render path for all sizes; do NOT build a separate 240x320 UI layer first.
* **Top constraints for the main-loop refactor:** read the size only from `screenWidth/screenHeight` (never 240/320, never cache), keep gameplay on the tick-state
  camera, keep world / HUD / menu draw calls separable, allocate per-frame buffers by current size (section 4).

## 1. What happens today at non-240x320 sizes (experiment)

Method: copy of the repo in `/tmp/claude-1000/widescreen/repo` (throwaway; patched only to jump into arbitrary maps via env `G3_MAP`), run through
`:desktop:run --width W --height H --frames-dir ... --script ...`. Screenshots are in `/tmp/claude-1000/widescreen/` (`out_*`, `f427/`, `mm*.png`; ephemeral).
Sizes tried: 427x240, 320x180, 640x360, 960x540, 1280x720 (and 240x320 for comparison). No exceptions, no clipped/garbled UI at any of them.

| screen | result |
|---|---|
| JoWooD / HandyGames splash (`drawSplashImage`, GE:1513) | OK. Background colour fills the whole screen, 172x66 logo centred (`drawImage(..., w/2, h/2, 3)`). No full-screen art exists (only `s00.png`/`s01.png`, 172x66), so nothing to pillarbox. |
| Loading bar (`renderState` state 1/3, GE:1526-1580) | OK. Bar is `screenWidth/2` wide, centred, text above it. |
| Sound prompt / main menu / settings / ingame menu (`drawMenuBackground` GE:3741, `layoutMenu` GM:2982) | OK. Frame, corner ornaments, title logo and soft-key icons are anchored to corners/centre; list area and entries just get wider (centre-aligned text, selection bar spans full width). At 640x360 it looks like a big dark panel with a small logo and small text, but it is correct. At 320x180 the list scrolls (arrow shown), still usable. |
| Dialogue (`layoutDialogue` GE:9807, `drawDialogue` GE:9909) | OK at 427x240 and 640x360: portrait strip at the bottom, text rect `screenWidth - 2*(...)`. Lines get long (60+ chars at 640), ornament divider centred. |
| Message box (`showMessageBox` GE:9271, `drawMessageBoxFrame`) | OK, full-width bar centred vertically (world map encounter prompt checked). |
| World view (normal maps) | OK. Visible tiles 26.7x15 (427x240), 40x22.5 (640x360), 60x33.8 (960x540). Camera follows/clamps correctly, bg buffer scrolls correctly, entities are not cut off at the edges, HUD stays in the corners. Cost: render() measured 0.26 ms (240x320), 0.49 ms (640x360), 0.94 ms (960x540), 1.36 ms (1280x720) steady-state on the dev box -> not a concern even for 144 Hz interpolation (GPU upload of the texture is separate). |
| Small maps (hut, 10x10; most areas) | The map is centred (existing code, GE:4754-4783) and surrounded by **black void**: e.g. hut at 427x240 is a 160x160 island. This already happened on 240x320 for 30 of 52 maps (horizontal) - it is level data, not a bug. |
| World map (`renderWorldMap` GE:1979, tile 8, 30x40 tiles = 240x320 px) | OK but pillarboxed: a 240-wide strip in the middle with black sides; banner + HUD span the full width. |
| Quickslot ring (`layoutQuickslots` GE:2133) | Works, but slots are spread over the whole width (gap = (W - 2*img126 - 4*img150)/5): at 640 wide they sit ~100 px apart. Cosmetic. |
| Target bar / hover label | Target bar = `W - 2*(W/8)` (3/4 of the width, GE:2392); interact label spans between the two HUD corners (GE:2461). Long but functional. |
| Lockpicking | Not run; see bug note in 2d (`setClip(10,0,W-20,W)` uses width as height). |

Map statistics (`/d` resource, maps 1..52, tile 16 px; "void" = map smaller than view in that axis, rough, vertical uses h+5 rows because of the camera's extra bottom rows):

| logical size | tiles shown | maps narrower than view | maps shorter than view | maps that scroll in both axes |
|---|---|---|---|---|
| 240x320 (orig) | 15.0 x 20.0 | 30 | 28 | 17 |
| 320x180 | 20.0 x 11.3 | 40 | 2 | 12 |
| 427x240 | 26.7 x 15.0 | 46 | 13 | 6 |
| 480x270 | 30.0 x 16.9 | 47 | 26 | 3 |
| 640x360 | 40.0 x 22.5 | 49 | 40 | 2 |

Maps >= 30 tiles wide: 1 (40x40), 2 (50x30), 9 (30x10), 39 (50x10), 52 (32x32); plus procedural encounter levels (32x32) and map 0 (world, 30x40 at 8 px).
Conclusion: a wide view adds visible content on only a handful of maps; elsewhere it adds void. A 16:9 view is also *shorter* than the original in rows unless the
height is >= 320 (240 -> 15 rows, 270 -> 16.9 rows vs 20 originally); the many 12x17-tile areas no longer fit vertically at 240/270 high.

## 2. Inventory of every place that must (or may) change

### 2a. World view and gameplay coupling

Pure rendering, already size-agnostic (verified; only need rebuilding when the size changes):

| what | where | notes |
|---|---|---|
| bg wrap buffer size | `createBackgroundBuffer` GE:1600 `(W/tw+2) x (H/th+2)` tiles; called only from `GameWorld.initMapRuntime` (GameWorld.java:547) | Rebuilt on every map load, so fixed presets are fine. **Not** rebuilt by `sizeChanged` (GE:12772) -> required for any live resize (also sets `bgDirty`, `bgTileX/Y=-1`). `drawBackgroundBuffer` GE:2090 / `updateBackgroundBuffer` GE:2037 / `redrawBackgroundRegion` GE:2003 use `bgTilesW/H` only (4-way wrap blit). Note `updateBackgroundBuffer` mutates state at render time (see 4). |
| visible tile rect + entity cull | `renderWorld` GE:1828-1870: `W/tw + 2 + camTile`, entity rect = visible -6 tiles / +6 tiles in x, rows `top .. bottom+7` | Margins are in tiles and depend on sprite size (<= ~6 tiles wide, <= 7 rows overlap), not on the screen; correct for any view. Entity gather is a full-grid-cell walk + insertion sort per frame: at 960x540 on a 40x40 map it is the whole map; still < 1 ms. |
| camera follow/clamp | `snapCameraToPlayer` GE:4696, `updateCamera` GE:4703-4787 | Target = player - `W/2`,`H/2` shifted 1 tile by facing; low-pass `cam = (cam*100 + target*28) >> 7`; clamp `[0, mapW*tw - W]`, `[.., (mapH+5)*th - H]` (world map `+3`), and **centre** when the map is smaller than the view (lines 4754-4759, 4778-4783). All already generic. (Cosmetic quirk: Y uses `tileWidth` instead of `tileHeight` in 4699/4713/4719; equal today.) |
| tile/entity screen position | everything does `(pos*tw>>12) - cameraX` (GE:1786, 2720, 6172, 7136, 9201, 10944, 11301... ~25 sites) | Size independent. |
| FX / projectiles | `drawProjectileStyle0/1` GE:9200-9230, `drawFx*` | Use camera only. |

**Gameplay that depends on the view size (must be decoupled so the wide view does not change rules):** I grepped every use of `cameraX/Y/screenWidth/Height`
outside of drawing. Exactly two spots:

1. `isNpcTargetable(int)` GE:4159-4173: an NPC is targetable only if its pixel position lies inside `[0,screenWidth] x [0,screenHeight]` relative to the camera.
   Callers: `findNearestTarget` GE:4110 (auto-target, also requires `isNpcInRange`), `cycleTarget` GE:4129 (the `*` key: **no range check**, only on-screen), and the
   per-tick validity check in `updatePlayer` GE:4614 (a target that leaves the screen is dropped). With a 640x360 view the player could cycle to / keep a lock on
   enemies up to 20 tiles sideways (original: 7.5 tiles sideways, 10 up/down), which also drives bow/spell targeting UX and aggro-bar display.
2. `updateCamera` GE:4706-4714: lock-on framing (camera = midpoint of player and target) is used only if the target is within `~screen` (`abs(dx)*4/3 < (W<<12)/tw && abs(dy)*4/3 < (H<<12)/tw`).

   -> introduce a **virtual gameplay view** = the original 240x320 (constants `GAMEPLAY_VIEW_W/H`, ideally from the 240x320 default `Platform.DEFAULT_WIDTH/HEIGHT`):
   * (1) keep a second, tick-driven "logic camera" computed with the exact same `updateCamera` math but with `vw=240, vh=320` (own clamp/centre, own low-pass state,
     own look-ahead; fed by the same player/target) and make `isNpcTargetable` use it. This is bit-exact with the original rule, including map-edge clamping
     (a plain "centre rect around the real camera" differs near clamped edges). Cost: ~40 lines; or
   * (1b) cheaper approximation: rect of 240x320 centred on the *real* camera centre (`cameraX + (W-240)/2`, `cameraY + (H-320)/2`).
   * (2) in `updateCamera` use `vw/vh` for the 4/3 test but real `W/H` for the resulting camera target.
   All other gameplay is world-distance based and independent of the screen: NPC AI/aggro (`updateNpcs` GE:4788 iterates all NPCs every tick; aggro `npc[29]` vs
   player distance), projectiles (`updateProjectiles` GE:9136, ray casts, `tmr` countdown, no despawn on leaving screen), effects, encounters (world-map tile roll
   `tryWorldMapEncounter`), triggers (tile rects), interaction (`updateInteractionTarget` GE:2628, player position), spawn = map load. No activation radius is tied to the screen.

### 2b. HUD (all in `GameEngine`, drawn at the end of `renderWorld`, GE:1960-1973)

| element | method / line | behaviour at wide sizes | action |
|---|---|---|---|
| frame, portrait (bottom-left), weapon/mana (bottom-right), soft-key arrows | `drawHud` GE:10350, `drawHudFrame` GE:10364 (`drawSpriteScreen(W,H,...)` corner anchored) | correct, corners | none |
| HP bar | `drawHpBar` GE:10427: hard-coded `fillRect(33, ..., 5, h)` relative to bottom-left sprite | correct (anchored to the left corner) | none |
| mana bar | `drawManaBar` GE:10443: `W - img138.w - img126.w + 2` | correct | none |
| quickslot ring | `layoutQuickslots` GE:2133 (cache `quickslotPos`, text area 5), called from `initQuickslots` per map load | slots spread over the full width | clamp `quickslotGapX` to a max (e.g. 12) so the 4x2 grid stays compact and centred (one line at GE:2135-2140) |
| target bar | `layoutTargetBar` GE:2392 (`W>>3` margins), `drawTargetBar` | very wide | cap `targetBarW` (e.g. 240) and centre |
| interact label | `initInteractLabel` GE:2461 | spans corner to corner | cap + centre (optional) |
| world map banner | `initWorldMapBanner` GE:11099, `drawWorldMapBanner` GE:11142 (`W-1` frame) | full-width bar, fine | none |
| pause screen | `drawPauseScreen` GE:1504 | full-screen text area, fine | none |

All HUD layout caches (`initWorldMapBanner`, `initQuickslots`, `initInteractLabel`, `initTargetBar`, `layoutDialogue`) are recomputed in `GameWorld.initMapRuntime`
(GameWorld.java:564-572) on every map load. So for fixed presets nothing stale can occur; for a live resize call the same block again.

### 2c. Menus, dialogue, message boxes

Everything is relative to `screenWidth/Height` and image sizes (`imageTable[...]`), no fixed 240/320 anywhere (grep for 240/320/239/319 hits only text ids 239/240).

| component | where | fixed vs relative | wide-size issue |
|---|---|---|---|
| menu chrome (frame, corner sprites, bottom bar, coin counter) | `drawMenuBackground` GE:3741-3880 | relative, corner/centre anchored | none |
| menu content area / list | `layoutMenu` GM:2982-3020 (`contentX=17`, `contentW = W - 2*17`, `contentH = H - ...`), `drawMenu` GM:3715, `openMenu` GM:3605 | relative; recomputed on every `openMenu` | text wrap width = `contentW-6` (GM:3686) -> long lines; centred lists look sparse |
| softkeys / scroll arrows / icon rows | `drawSoftkeysAndScrollArrows` GM:2372, `drawIconButton` GM:2139, `drawIconLabelRow` GM:2263, `drawButton` GM:2503, `drawCenteredText` GM:2205 | relative (`W>>1`, `W - w`) | none |
| dialogue | `layoutDialogue` GE:9807 (`rectW = W - (2*x+16)`), `drawDialogue` GE:9909, `drawDialogueText` GE:9873 (centre divider `W>>1`) | relative; wrapped at open time with `dialogueRectW` (GE:9824, 9844) | very long lines; cap `dialogueRectW` to ~320 and centre by adding to `dialogueRectX` (single edit in `layoutDialogue`) |
| message box | `showMessageBox` GE:9271 (wrap `W-10`, height clamp `H/4 .. 2H/3`), `drawMessageBoxFrame` GE:9302-9330 (`fillRect(0,top,W,h)`, icons at `W>>1`, `W-1`) | relative; wrapped at open time | full-width bar; acceptable. If we want a narrower box, do via the UI-viewport trick below |
| lockpicking UI | `drawLockpicking` GE:10149-10255 (centred on `W>>1`) | relative | **latent bug**: `setClip(10, 0, W-20, W)` (GE:10167) uses width as height; with H < W (all landscape sizes) it clips nothing, with 240x320 it clips to 240 rows. Harmless for wide, but fix to `H` |
| text area system | `defineTextArea`/`textAreaRects` (165 shorts), `drawTextInArea` GE:~690-800 | rects defined from W/H at layout time | rects must be redefined on resize (done by the layouts above) |
| wrapped caches | `entryWrappedTexts` (GM:3686), `dialogueHistory` (GE:9824), `messageLines` (GE:9277) | wrapped once at creation | on live resize either close/reopen or re-wrap; for fixed presets irrelevant |

### 2d. Full-screen images / backdrops

There are no full-screen bitmaps. Splash logos (172x66), the Gothic3 title logo and all frames are small sprites drawn centred / corner-anchored over solid colour fills
(`uiColors[...]`), so a wide screen needs no pillarboxing and no new art. The only "wide-unfriendly" visuals are:
* black void around small maps and around the 240x320 world map (2a, section 3 option V);
* the menu "panel" being one huge rectangle at >= 640 wide (section 3 option M).

### 2e. Platform / desktop

| item | where | change |
|---|---|---|
| logical size source | `Platform.width/height` (static, `-Dg3.width/height`, `Platform.setScreenSize`), `DesktopLauncher` lines 20-23 (`--width/--height`, `screen.width/height`) | fine for fixed presets; add preset names (`classic`, `wide360`...) and window-size limit `setWindowSizeLimits(w,h)` already scales with it |
| framebuffer | `GameCanvas` (final `buffer = Image.createImage(Platform.getWidth(), ...)`, midp), `GameEngine.screenGraphics` (instance initialiser GE:37) | fixed at construction. Live resize needs a non-MIDP `GameCanvas.resize(w,h)` (new buffer + new Graphics), `GameEngine` re-reading the Graphics, then `sizeChanged` |
| frame hand-off / presenter | `FrameBuffer.publish(argb,w,h)`, `G3App.upload()` (reallocates texture when `w/h` change), `G3App.render()` integer scale `min(bw/fw, bh/fh)` | **already handles a changing frame size**; no change needed |
| `Displayable.getWidth/getHeight` + `sizeChanged` hook | midp `Displayable` lines 24-27 | must call `sizeChanged` on live resize |
| screenshots / FrameDumper | follows frame size | none |

## 3. Recommended approach, alternatives, resolutions

### Approach (recommended): one render path, logical size is a parameter; world and UI both laid out from `screenWidth/Height`

1. **Phase 1 - fixed presets (config / CLI), no runtime resize.** Add presets, decouple gameplay (2a), cap a few widgets (2b/2c), draw a better void (V). Everything else is already done.
2. **Phase 2 (optional) - dynamic aspect-adaptive size.** `scale = max(1, round(windowH / 360))`, `logicalH = floor(windowH/scale)`, `logicalW = floor(windowW/scale)` (clamp W to e.g. 320..960). Presenter keeps nearest-neighbour integer scale with at most `scale-1` px bars. Gives pixel-exact scaling on 720p/1080p/1440p/4K (scale 2/3/4/6) and
   natural support for 16:10 (640x400 @1200p), 21:9 (860x360 @3440x1440) etc. Needs resize plumbing (section 2e + a `GameEngine.applyResize()` that re-runs the map-load layout block).
3. Optional polish: **UI viewport** so menus/dialogue/message boxes can be limited to a centred max width (say 320) even on a wide screen: split `screenWidth/Height` into world
   (`screenWidth/Height`) and UI (`uiWidth/uiHeight`) statics, `render()` calls the menu/dialogue/message draw with `g.translate((W-uiW)/2, (H-uiH)/2)` after dimming the world; HUD stays on real corners.
   It is mechanical (~120 of the 177 `screenWidth/Height` sites are UI; the world ones are GE:1602-1603, 1834-1835, 4166, 4698-4783 and the `renderState` clears), but touches
   a huge number of lines in `GameEngine` -> do it after the loop refactor lands.

Void presentation options (V): (a) keep black (authentic, current); (b) fill with a per-tileset dark ambient colour / vignette or repeat the map's edge row/column
darkened (cheap: draw into `bgBuffer` clamped tile coordinates in `redrawBackgroundRegion` instead of `fillRect(0)` for out-of-map cells, plus darken in a final pass);
(c) never clamp the camera on small maps (centre only): already the behaviour.

Alternatives considered:
* **Keep 240x320 UI layer, widen only the world:** needs two framebuffers or heavy clip/translate in every call; no benefit because UI code is already size-relative. Rejected as default; the UI-viewport (above) gives the same look for menus at far lower cost.
* **Uniform scale (render 240x320 world, scale up/pillarbox):** that is today's behaviour in a 16:9 window (integer scale with pillarbox bars); keep it as the `classic` preset.
* **Render at window resolution (no logical pixel grid):** needs sprite scaling; breaks pixel art. Only relevant to the future 3D renderer.

Logical resolution options (tile = 16 px; original = 240x320 = 15 x 20 tiles):

| logical | tiles (w x h) | 720p | 1080p | 1440p | 4K | comment |
|---|---|---|---|---|---|---|
| 240x320 (classic) | 15 x 20 | pillarbox | x3 (720x960) | x4 | x6 | reference |
| 320x180 | 20 x 11.3 | x4 | x6 | x8 | x12 | exact everywhere, but only 11 rows (HUD takes 40 px); too short |
| 384x216 | 24 x 13.5 | - | x5 | - | x10 | meh |
| 427x240 | 26.7 x 15 | x3 (1281x720) | x4 (bars) | x6 (bars) | x9 | 1080p/1440p not exact |
| 480x270 | 30 x 16.9 | - | **x4** | x5 (bars 160x90) | **x8** | closest to "classic feel", 17 rows < 20 originally |
| **640x360** | **40 x 22.5** | **x2** | **x3** | **x4** | **x6** | **exact on all common 16:9 targets, >= 20 rows, 2.7x width** |
| 960x540 | 60 x 33.8 | - | x2 | - | x4 | far too much void; HUD tiny |

Recommendation: ship `640x360` as the wide default (all integer scales exact, vertical extent >= original), `480x270` as "tighter", `240x320` classic,
`427x240`/custom via `--width/--height`; Phase 2 picks the size automatically from the window around a 360 px logical height.
Given the map data (section 1), do not expect the wide view to look "full": invest in the void (V) and in keeping UI compact.

## 4. Constraints for the high-fps / interpolation refactor and the later 3D renderer

For `GameEngine` main-loop refactor (do not paint us into a corner):
1. **No 240/320 literals, no cached size.** Read `screenWidth/screenHeight` (or `Platform.getWidth/getHeight` at init only) at use time. Any buffers the interpolation
   needs (previous-frame snapshot, scratch images, back buffer) must be allocated lazily and reallocated when the size differs from the cached size.
2. **Camera:** interpolate `cameraX/cameraY` between tick N-1 and N (they are tick-state ints produced by the low-pass in `updateCamera`; clamp results are monotone, so
   interpolating the clamped values is safe). Gameplay (`isNpcTargetable`, lock-on framing, any future "on screen" rule) must read the **tick-state** camera / virtual
   240x320 logic camera (2a), never the interpolated one. Keep `updateCamera` a pure function of (player, target, view w/h) so a second instance with `vw=240,vh=320` is trivial.
3. **Render-time mutation:** `updateBackgroundBuffer` (called from `drawBackgroundBuffer`) mutates `bgTileX/Y` and repaints strips; it tolerates arbitrary camera
   jumps (while-loops) so calling it for several interpolated frames per tick is fine, but it must always see a `bgBuffer` sized for the current `screenWidth/Height`
   (rebuild via `createBackgroundBuffer`, also on size change). Keep interpolated frames pixel-snapped to the *logical* grid (round, do not sub-pixel), otherwise the integer-scaled presentation shimmers.
4. **Cull rect from the interpolated camera** (margins remain tiles: x +-6, rows +7); entity positions interpolated between ticks (max 0.25 tile/tick) are well inside the margins.
5. **Keep draw phases separable:** world (`renderWorld` steps 1-6), HUD/overlays (step 7: `drawInteractionIcon`, `drawTargetReticle`, `U`, `drawHud`, dialogue/lockpick/message box)
   and menu (`render()` -> `Gothic3Midlet.drawMenu`). Do not merge them into one monolithic method; a 3D world layer and a UI-viewport both rely on this seam.
6. **Resize safe point:** apply a new size on the game thread between ticks, under `synchronized (loaderTask.lock)` (renderState holds it in state 6), then run the map-load
   layout block (`createBackgroundBuffer`, `initWorldMapBanner`, `initQuickslots`, `initInteractLabel`, `initTargetBar`, `layoutDialogue`, `snapCameraToPlayer`) and re-open/re-layout any open menu/message box.
7. `FramePacer`/`RenderConfig`/`FrameBuffer`/`G3App`: no size assumptions allowed; the presenter already handles per-frame width/height changes.
8. Do not tie any new timing/AI/spawn logic to what is "visible"; use world distance (`isNear`) like the existing code.

For the later 3D renderer:
* Treat world rendering as a layer separate from the UI layer. UI layout must read `uiWidth/uiHeight` (or `screenWidth/Height`) - never the 3D target size; the 3D layer renders at
  window or integer-scaled resolution with horizontal-FOV derived from aspect, the UI stays on the pixel grid.
* Cull by frustum/world distance, not by `cameraX/Y` rect; keep gameplay "target on screen" semantics on the virtual 240x320 logic camera or replace by a world-space region (e.g. +-7.5 x +-10 tiles around the logic camera centre).
* The tile size is data-driven (`GameWorld.tileWidth/tileHeight` = 8 on the world map, 16 elsewhere): never assume 16.

## 5. Effort estimate and risks

| component | effort | notes |
|---|---|---|
| Presets + CLI/config (`--view classic|wide`, `--width/--height` already exist) | S | trivial; document in platform.md |
| Gameplay decoupling: `isNpcTargetable` + `updateCamera` lock rule (virtual 240x320 logic camera) | S (S-M if bit-exact logic camera) | needs tests: cycle target with 2 NPCs at edge of screen on 240x320 vs wide must pick the same set |
| HUD/UI caps (quickslot gap, target bar, interact label, dialogue width, message box) | S-M | a few lines each; visual tuning takes the time |
| Void presentation (edge-extended / ambient fill) + world-map surround | S-M | in `redrawBackgroundRegion` and `renderWorldMap`; art decision |
| Dynamic logical size (window-driven): canvas buffer realloc, `sizeChanged` wiring, re-layout, bg rebuild, open-menu handling | M | main risk is stale layout/wrapped text mid-menu; simplest policy: apply resize only when no menu/dialogue/message box is open, otherwise defer |
| UI viewport (`uiWidth/uiHeight`, centred max-width menus/dialogue) | M | mechanical but touches ~120 lines of `GameEngine`/`Gothic3Midlet`; do after the loop refactor merges to avoid conflicts |
| Verification matrix (240x320, 427x240, 640x360, 1920x1080 window, 4K scale, world map, hut, map 1, dialogue, quickslots, lockpick, menus) | S-M | scripted with `--frames-dir/--script`; add a debug `--start-map` option (my throwaway patch used env `G3_MAP`, new game -> `loadMap(id, x, y)` + nearest walkable tile) |
| 3D (not in scope) | L | see section 4 |

Overall: Phase 1 about 1-2 days, Phase 2 about 2-3 days, UI viewport about 1-2 days.

Risks:
* **Content, not code:** most maps are tiny; wide view = black void unless V is done; the 12x17-tile areas no longer fit vertically at 240/270 px height (use >= 320 px height, hence 640x360).
* Gameplay drift if `isNpcTargetable` is left on the real view (target cycling reaches far enemies); easy to miss because it only shows with `*` / auto-target.
* Concurrent edits: the loop refactor touches `GameEngine` heavily; the UI/world size split (177 sites) will conflict - sequence it after the refactor, or restrict phase 1 to the small edits above.
* Live resize with open modal UI (wrapped text caches, `scrollY`, `contentX/W`) - restrict to safe points.
* Odd logical sizes (427x240) work (checked) but integer scaling on 1080p is not exact; prefer sizes from the table.
* Font metrics (Liberation Sans vs the original Nokia font) are independent of the view size; very long wrapped lines at >= 640 wide hurt readability (hence width caps).
* Minor latent bugs found: lockpicking clip uses width as height (GE:10167); camera Y uses `tileWidth` (GE:4699, 4713, 4719) - harmless while the tiles are square.

## 6. Phase 1 implementation notes

Implemented (all marked `// PORT:`):
* Presets: `screen.preset` / `--view` (`wide` 640x360 default, `tight` 480x270, `classic` 240x320, `WxH`), `window.scale = auto` (docs/platform.md).
* Gameplay decoupling with the bit-exact variant (option 1): `updateCamera` runs the original math twice through `stepCamera`: for the real view (rendering) and for a virtual
  240x320 view (`logicCamX/Y`). `isNpcTargetable` and the lock-on framing test use only the virtual view, so gameplay is identical at every size, including map-edge clamping.
* Widget caps: dialogue text column and message box 320 px (centred), target bar and interact label 240 px, quickslot gap <= 12.
* Void: `redrawBackgroundRegion` draws the clamped edge tile for out-of-map cells with a black overlay that gets darker with distance (alpha 0x70..0xE0). It is a pure function of the cell,
  so the incremental bgBuffer redraw and interpolated cameras are unaffected. Disabled on exactly 240x320 (`voidFillEnabled`) to keep the original look. Small maps are centred by the
  existing camera clamp/centre code.
* Latent bugs fixed: lockpicking clip height, camera Y with `tileHeight`.
* Verification: `s=` state hashes of `scripts/walk-ticks.script` identical across 240x320, 480x270, 640x360 and classic/interpolated, apart from tick 0 (known race). The real camera and
  size-dependent layout caches are excluded from the hash (`StateHasher.EXCLUDED`); with `--trace-fields` the only fields that differed between 240x320 and 640x360 were exactly these.
