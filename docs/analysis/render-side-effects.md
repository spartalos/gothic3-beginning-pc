# Render side effects (audit for interpolated rendering)

Goal: render extra, in-between frames at display rate while game logic stays at exactly 10 Hz and bit-for-bit unchanged.
The original `render(Graphics)` (`GameEngine.render`, A.W) is not a pure function of the game state; this document lists everything
that the render call tree writes and how each class of write is handled. Line numbers are those of
`game/src/main/java/g3/*.java` before the port edits (they shift by a few lines for GameEngine.java after line 12730 only).

## Method

* Static call graph from `GameEngine.render` over the compiled `g3` classes (javap bytecode, all invoke* edges, same-class and
  cross-class): **136 reachable methods** (of 554 methods in the five g3 classes). Reachability is an over-approximation (virtual/dead branches included).
* In those methods **303 store instructions** were listed (`putstatic`, `*astore`; 101 of them are local-variable `astore`, i.e. not
  state, leaving 202 real sites: 66 `putstatic`, 52 `sastore`, 40 `iastore`, 25 `aastore`, 19 `bastore`). Every site was reviewed by
  hand against the source and classified below. The external calls reachable from render: `Random.nextInt` (via `randomBelow`, one caller),
  `System.currentTimeMillis` (one caller, `startDialogue`), `System.gc` / `Platform.openResource` (lazy image/model/language loads).
  No sound call (`playSound`, `startSound` ...) is reachable from render.
* Dynamic confirmation (`-Dg3.verify=true`, see docs/platform.md): per tick the verifier hashes every static field of the g3 classes
  before/after the canonical render (this is what the "canonical render mutates" list below is made of, observed over ~1100 ticks of
  boot, menus, intro dialogue and free play in the first village map and on the overview map), and again before/after three extra
  in-between renders (alpha 1, 0, 0.5).

## Design consequence

A frame is a function of (game state, camera/entity positions). The first render after every tick ("canonical frame") is the unmodified
original render and performs all of its writes exactly as in the original (same order, same RNG draws), so the state seen by the next
tick is identical to classic mode. Every extra frame is rendered between the previous and current tick state (alpha in 0..1) and must
leave no trace. Because the canonical frame has already run when an in-between frame is drawn, one-shot / idempotent writes are
already done; what remains are writes that depend on how often render runs. They are handled by one of:

| code | handling |
|---|---|
| **S** swap | `gothic3.port.RenderMutations`: the pre-render values (before the canonical frame) are written back before an in-between frame renders and the post-render values (what the canonical frame left) are written back after it. The in-between frame therefore sees exactly what the canonical frame saw (so it is pixel-identical at alpha 1) and logic never sees what it did. Covers: all entity rows, a list of static scratch/scroll arrays, and the game RNG state. |
| **G** guard | `GameEngine.portIntermediateFrame` is true while an in-between frame renders; the write is skipped. |
| **C** cache | pure function of resource files / map data; may be filled by any frame, excluded from the state hash. |
| **N** n/a | only reachable while a menu / modal is open; in-between frames are not produced then (the tick frame is simply shown). |
| **L** local | writes only temp data (fresh arrays, strings, locals). |

`PortLoop.interpolatable()` allows in-between frames only when: `gameState==6`, not paused, no HG menu (`menuInputEnabled`, `menuViewFlags`),
no dialogue, no message box, no lock-picking, world table loaded. Everything else (boot, splash, loading, menus, dialogue, message boxes,
inventory, pause, lock picking, death screens that use menus) shows the canonical tick frame, exactly as in classic mode.

## Mutations by class

| # | method (line) | write | nature | decision |
|---|---|---|---|---|
| 1 | `drawEntity` -> `updateObjectAnim` (7564; 7571-7586) | `entity[10]` of world objects (4096): frame low 16 bits, "advance every 2nd render" counter in bits 24+, loop/active flags | **state advanced by render**: animation frame is read *then* advanced per render (1 frame / 2 renders = per tick) | **S** (entity rows). In-between frames draw the same frame the canonical frame drew. Observed: changes in every free-play frame. |
| 2 | `renderWorld` (1885-1886) + `insertSorted` / `insertSortedChecked` (1815-1823) | `entity[1]` high 16 bits (sorted render chain), all entities in the cull rect | render-time scratch, rebuilt per frame; stale value left behind | **S** (entity rows). Without it the per-tick state hash differs between modes. |
| 3 | `drawAnimatedEntity` (7127) -> `setRecolorChoice` (6856) -> `setSlotByte` (6808-6812) | `entity[9]`/`[10]` model slot bytes (a never-set slot -1 becomes 0) and the sheet cache, when a sheet image is not loaded yet | lazy default + lazy cache | entity words: **S** (entity rows are swapped complete); sheet images: **C** |
| 4 | `drawFx3` (~11436) -> `Gothic3Midlet.randomBelow` | shared game RNG (`Gothic3Midlet.RANDOM`) is advanced per sprite of effect type 3 (random sprite frame) | **visual jitter from the logic RNG** | **S**: `RANDOM` is a `g3.port.SnapshotRandom` (same LCG as `java.util.Random`, verified identical for 4x10^6 draws) whose 48-bit state is rewound before and restored after an in-between frame, so the frame draws the same numbers as the canonical frame and logic's sequence is untouched. This is the only RNG use in the render tree. |
| 5 | `drawLinesInArea` (806; 865-955) | `textAreaScroll[area*3+2]`, `textAreaScrollDir[area]` | marquee / scroll position advanced after drawing (text longer than its area) | **S** (static arrays) |
| 6 | `drawLinesInArea` setters `setTextAreaVisible` (756), `setTextAreaClip` (764), `defineTextArea` (771), `resetTextAreaScroll` (780), `setTextAreaLineGap` (786) | `textAreaRects`, `textAreaScroll` | layout configuration, called from menu / dialogue draw code | **N** (menus, dialogue) |
| 7 | `drawTextInArea` (790) | `singleLineBuf[0]` | scratch single-line array | **N** (menu/dialogue/message paths) |
| 8 | `updateBackgroundBuffer` (2038-2052), `redrawBackgroundRegion` (2004) | `bgTileX`, `bgTileY`, `bgDirty`, contents of `bgBuffer` | **render cache driven by camera tile deltas** | **C**. See "Background buffer" below. |
| 9 | `getEntityPos` / `getEntityPos3` / `getHitbox` / `getSortY` (GameWorld 1538-1626), `drawBlobShadow` (`shadowPos`), `renderWorldMap` (`worldMapPlayerPos`), `getAnimBounds` (7183-7220, `animBoundsOut`), `drawSpellProjectile` (10938-10939, `xD`) | `scratchPos3`, `scratchPosHitbox`, `scratchPosSort`, `scratchRectSort`, `scratchPosTile`, `shadowPos`, `worldMapPlayerPos`, `animBoundsOut`, `xD` | scratch vectors shared with logic (always written before read) | **S** (static arrays; makes the state hash and any stale-read hazard identical to classic) |
| 10 | `ensureImageVariant` (6290-6440), `loadRawImage` (6454-6499), `rewindImagePack` (6549-6571), `readImageFromPack` (6583-6657), `readModelSheet` (7509-7549), `crc32` (7625-7638), `recolorPng*` / `fixChunkCrc` / `isPngSignature` (7592-7712) | `images`, `imageFrames`, `imageTable` (w/h/frame count of the image just loaded), `sheetImages`, `sheetPngBytes`, `rawImageBytes`, `rawOffsetBytes`, `pack*`, `crcTable`; `System.gc()` before each lazy load | **lazy resource cache** (image missing -> load from `/NNN.png` etc.) | **C**. A frame may load an image earlier than the canonical frame would (an entity enters the cull rect slightly earlier); it is a pure function of the resource, and a failed load marks `imageTable[..+9]|=1` exactly as before. These fields are excluded from the state hash. |
| 11 | `Gothic3Midlet.getText` -> `loadLanguageFile` (721-760), `decodeString` (770-806) | `currentLang`, `langRawBuf`, `langStrings[]` | lazy language-file cache | **C** (not excluded from the hash; fills only in menus / first dialogue, never differed) |
| 12 | `drawLockpicking` (10150-10154) -> `startDialogue` (9828-9869) | `dialogueSpeakerTextId`, `iD`, `globalFlags[2] |= 128`, all `dialogue*` fields, `dialogueSkipAllowedAt` (clock) | one-shot tutorial dialogue **started from draw code** | **G** + **N** (lock picking is not interpolatable) |
| 13 | `drawMessageBox` (9333-9369) | `messageLines[priceLineIdx/qtyLineIdx]` | rewritten for the draw and restored afterwards (net zero) | **N**, no net effect |
| 14 | `drawMenu` (3716-3837) and helpers: `drawIconButton` (2159: `GameEngine.quickslotLabel`), `scrollToSelection` (3262-3274: `scrollY`), `moreSelectableBelow/Above`, `anyEntryDrawn`, `firstEntryBelowView`, `lastEntryAboveView`, `lastDrawnEntry`, `wrapText` caches | HG menu layout/scroll state recomputed in the draw | layout state kept in statics | **N**: HG menu drawing is only reached when `menuInputEnabled`; menus never get in-between frames |
| 15 | `renderState` / `drawSplashImage` / loading screen (1527-1595), `drawPauseScreen` | none (reads `LoadTask.progress`, `splashImage`) | - | **N** |
| 16 | `drawEntity`, `drawTileLayerTile`, `drawHud*`, `drawHpBar`, `drawManaBar`, `drawTargetBar`, `drawTargetReticle`, `drawInteractionIcon`, `drawQuickslots`, `drawQuickslotLabel`, `drawProjectile*`, `drawFx0..14`, `drawWorldMapBanner`, `drawDialogue*` | no stores to game state (locals and Graphics only) | - | **L** |
| 17 | remaining `iastore/sastore/bastore/aastore` sites in `wrapText`, `decodeString`, `getScriptText` (9490-9519), `recolor*`, `fixChunkCrc`, `isPngSignature` | fresh local arrays / strings | - | **L** |

Of the 202 real store sites, the large majority are lazy caches (row 10), menu/dialogue layout state (rows 6, 7, 14), entity-row writes (rows 1-3)
and scratch vectors (row 9); the sites that matter for gameplay-visible state are rows 1-5 and 12 (all handled), everything else is idempotent or
unreachable from an in-between frame.

### Things that read, not write, but need the same value as the canonical frame
* `tickCounter` (`GameEngine.tickCounter`, selects the sprite frame of FX 14 and spell items): the loop increments it *after* the canonical
  render. In-between frames temporarily set it back to the value the canonical frame saw.
* wall clock: none in render (only `startDialogue`, see 12).

## Background buffer (`bgBuffer`) with an interpolated camera

`updateBackgroundBuffer` keeps a wrapping scroll buffer of `(screenW/tileW+2) x (screenH/tileH+2)` tiles holding the map window that starts at
tile `(bgTileX, bgTileY)`; each call compares the new camera tile with the stored one and redraws only the newly exposed columns/rows
(modulo buffer size). It is stateless with respect to *direction*: it redraws exactly the columns/rows that differ from the window it holds,
so moving the camera forward and then back (alpha 0 -> 1 inside a tick, next tick's alpha 0 starts at the previous tick's value) keeps the
buffer consistent as long as a single step is smaller than the buffer (original code has the same limit: a jump larger than the buffer
requires `bgDirty`, set at map load). Interpolated camera steps are at most one tick's camera step; teleports/map changes are not
interpolated (`TickInterpolation` epoch change / camera jump > one screen), so the case does not arise. Therefore in-between frames call the
unmodified `updateBackgroundBuffer`/`drawBackgroundBuffer` with the interpolated camera. Verified: the alpha-1 frame equals the canonical
frame pixel for pixel in 661/661 free-play ticks, in 240x320 and in 144x160 (where the camera really moves). `bgTileX/bgTileY/bgDirty` are
declared a cache and excluded from the state hash (after an in-between frame they describe the window the buffer holds, which is the
interpolated one).

## What is NOT interpolated / known limits
* Particle systems (`effectParticles`), projectile *shape*, animation frames, HUD, text scroll: tick quantised (10 Hz). Only camera and the
  `[4],[5]` position (projectiles also `[6],[7]`) of flag-8 entities (player, NPCs, projectiles, effects, spell casts) are interpolated.
* Frames while a dialogue / message box / menu / pause / lock picking is open are not interpolated (the world is static or not drawn
  there anyway, except the camera settling and an NPC walking during a message box).
* Coverage of the dynamic checks (scripts/walk-ticks.script, 1141 ticks): boot, sound prompt, menus, intro dialogue (480 ticks that are
  canonical-only) and 661 ticks of free play in the start area (player walking in 8 directions, NPC idle animation, object animation, camera
  moving at 144x160). Earlier non-deterministic smoke runs also crossed map changes (epoch changes) without errors. Combat, projectiles,
  spell effects, the world map camera and lock picking were reviewed statically (rows 1-5, 9, 12) but not exercised by the scripted runs.

## 3D extra capture area: object animation
`GameEngine.portExtraAnimFrame` (PORT) animates objects outside the 2D window for rendering only: the animation word `[10]` is kept in a render-side identity map
(`portAnim`, excluded from `StateHasher`), `updateObjectAnim` runs on the row with that word swapped in and the real word is restored immediately (the row is restored after the
world pass as before). No game state is written; verified with identical `s=` hashes (see docs/render3d.md).
