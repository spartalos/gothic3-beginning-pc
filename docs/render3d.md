# 3D view (Pokemon3D-style diorama), prototype

The 2D tile world turned into a low-poly diorama: floor tiles flat on the ground, walls / facades / roofs / fences as standing (or
leaning) textured quads, characters, items, objects and effects as camera-facing upright sprites standing on the floor, seen through a
tilted perspective camera that follows the game's own camera. HUD, menus, dialogue and message boxes stay 2D and are drawn over the 3D scene.

```
./gradlew :desktop:run --args="--view3d"          # or config render.view = 3d, or --render-view 3d
F5   toggle 2D / 3D at runtime        F6   reload the tile table (render3d/tiles.properties)        F3   overlay (shows view + chunks/sprites)
F7   tile editor (tag tiles with 3D shapes in game, see "Tile editor")
```
(Screenshots are not included in the repository: they show the original art.)

## How it works

```
game thread                                                              GL thread (G3App.render)
-----------                                                              ------------------------
GameEngine.renderWorld  --(PORT hooks)-->  DrawCapture  (record draws)
   world pass: Graphics.capture mode: drawRegion/fillRect are RECORDED,
   not rasterised; HUD/menus draw normally into the (transparent) frame
Render3DHook.snapshot()  -> MapSnapshot (immutable copy of the tile grid, shared while unchanged)
DrawCapture.finish()     -> Scene3D (camera, map, draw list)
flushGraphics            -> FrameBuffer.publish(argb frame, Scene3D)  ---->  upload HUD frame (RGBA, real alpha) + take scene
                                                                              Renderer3D.render(scene): shader + meshes
                                                                              then the HUD frame is drawn over it (alpha blend)
```

* **No new game logic, no state writes.** The game renders exactly as before (all draw calls, side effects, `RenderMutations` swaps for
  in-between frames are untouched); only the destination changes: the `Graphics` of the screen is put into *capture mode* for the world
  pass, so the pixels are not produced, the draw calls are recorded instead. Interpolated (display rate) frames work the same way, the
  recorded positions already contain the interpolated entity and camera positions. Gameplay is bit-identical with 3D on or off
  (see Verification).
* **Capture** (`midp`: `gothic3.platform.scene3d.DrawCapture`, `Graphics.setCapture`): records image-region draws (source image, source
  rect, MIDP transform, destination rect) and filled rects, each with an *anchor* (the entity's feet in world pixels), a *pass* and
  painter order. Hooks (`// PORT:` in `GameEngine`): `render` (begin frame), `renderWorld` (begin/suspend/resume/finish), `drawEntity`
  (pass: ground layer vs upright), `drawBlobShadow` (flat), `drawTileLayerTile` (ignored: tiles are built from the grid),
  `drawSpriteImpl` and `drawAnimatedEntity` (anchor).
* **Passes** (`Scene3D.PASS_*`): `FLAT` = sprites lying on the ground (object ground layers, blob shadows), `UPRIGHT` = billboards,
  `OVERLAY` = world anchored UI (interaction icon, target reticle: they are positioned relative to an entity so they are captured too). Their anchor is the entity's feet (`DrawCapture.markerAnchor`), so the icon stands at its 2D pixel height above the feet, plus `render3d.iconLift` tiles and scaled by `render3d.iconScale`, drawn last without depth test.
  The HUD itself (`drawHud`, quickslots, bars, text) is *not* captured; it is rasterised normally into the frame.
* **HUD layer**: in 3D frames the 2D buffer is cleared to transparent (ARGB 0) and `Graphics.blend` does a real source-over onto
  transparent pixels, so translucent HUD parts keep their alpha. The presenter uploads the frame with its alpha and composites it over the
  3D scene (`G3App`). Frames without a scene are forced opaque as before.
* **Scene publishing**: `Scene3D` is immutable and travels with the frame through `FrameBuffer.publish(argb, w, h, scene)` /
  `copyIfNewer(serial, dst, sceneOut)`; the GL thread never touches game state. `MapSnapshot` (tile grid copy + tileset sheet
  `Image`s) is reused while the grid, tilesets, palette and sheets are unchanged (checked every frame with a cheap compare), so the
  renderer rebuilds geometry only on map change.
* **Not 3D-able frames** show the normal 2D frame: world map, loading screens, boot/menus, full-screen dialogue, lock picking, message
  boxes are drawn by the 2D code over whatever is there. While a dialogue is open the last 3D scene is kept and shown under it
  (`DrawCapture.hold`) when the dialogue leaves parts of the screen transparent.

### Geometry (`desktop/.../render3d`)
* Units: 1 tile = 1 unit, x east, z south (screen y), y up. Camera: perspective, `pitch` 38 deg (elevation angle above horizontal, was 52),
  vertical `fov` 35 deg, `distance` 26 tiles from the look-at point. The camera looks at the player's interpolated feet position (published
  by `Render3DHook.finish()` via `DrawCapture.focus`), so the player stays centred; it falls back to the centre of the game's 2D view when the
  player is not inside it (scripted camera moments, world map). Config `render3d.pitch/fov/distance/focusOffset/lean/vscale/fog`.
* Camera keys while the 3D view is on: PAGE_UP / PAGE_DOWN pitch +/-2 deg, MINUS / EQUALS (PLUS) and mouse wheel distance, LEFT_BRACKET /
  RIGHT_BRACKET fov, HOME resets to the config values. The entities captured follow the real view, see "Capture area" below.
* **Layer 0** (`MapMesh`): floor quads at y=0, tile texture and MIDP transform (0-7) mapped through UVs. Cells with no tile can be filled
  (`tsN.void`).
* **Layer 1**: the game links layer-1 tiles into *columns* (depth tags, `buildTileOverlapLinks`): a tile with depth tag d at row r belongs to
  the column whose base row is r+d and is drawn in front of entities of that row. The mesh walks the same links: each column is a stack of
  standing pieces on the south edge of its base row (y from 0 up, one tile high each). This gives correct occlusion with a depth buffer
  (an NPC north of a wall is hidden, one south of it is in front), the same ordering the 2D painter produces.
* **Shapes** per tile (table below): `floor`, `flat` (blended decal), `upright`, `slope[:deg]` (quad leaning back, continuing the piece
  below it: roofs), `cross`, `block[:depth]` (standing quad + top cap coloured from the tile's top row), `hidden`.
* **Sprites** (`SpriteLayer`): quads standing at the entity anchor, height in tiles = pixels/16 (`vscale`), batched per texture in game
  painter order, depth *test* against the world but no depth write (parts of one character never z-fight, walls still hide them).
  Object ground layers drop their opaque black pixels (building "interior" rectangles that the 2D roof covers; in 3D they would show).
* **Steps** (`steps[:n]`, layer 0): a staircase tile: `n` treads (default 4, 0.09 tiles each) going down towards the viewer from the floor level, with risers and side faces, so doorway stairs
  read as steps leading up into a raised room (`ts5.layer0.32 = steps:4`, `ts5.layer0.28 = steps:2`; tread art = the tile's own strips).
* **Raised platforms** (`deck[:h]`, `stairs[:n[:rise]]`, layer 0): `deck` draws the tile's art as the platform top at height h (default 0.75) with a solid, darkened front face (south) and
  west / east faces (tile edge column) wherever the neighbour cell is not a deck; `stairs` = n treads rising to the north up to a deck of height `rise` (rising to the south when the tile is
  flipped vertically, transform bit 0: stairs at the back of a platform). Characters and objects standing on these cells are lifted to the surface (`Renderer3D.platformLift`, render only).
  The village podium (ts0 layer 0: planks 34-35 / 37-38, stairs 39, leg art 36 replaced by plain snow with `floor:0`) is the first user (`tiles.properties`); a second one with back stairs is in area 2.
* **Flights** (`flight[:n[:rise]]`, layer 0; `ts0.layer0.27-28 = flight:3:0.3`): a multi-cell staircase (e.g. through a wall gap). Every cell has `n` treads (default 3) and a
  straight run of flight cells climbs `rise` (default 0.3) more per cell than the cell south of it, i.e. it climbs to the north; the vertically flipped tile (MIDP transform) climbs to the south.
  The side faces drop to the ground except where the neighbour cell is another flight. Tread art = the tile's own strips, as with `steps`.
* **Raised plateaus** (automatic, `MapMesh.computePlateaus`): the walkable area behind the top cell of a flight (flood fill over walkable cells, 4-neighbour, capped at 3000 cells; an area that leaks out of its walls is left alone) is raised to the height the flight reaches, so the stairs lead onto a floor instead of into the air (area 3 abbey courtyard 1.2, area 2 gate courtyard 0.6). Raised: the floor, the standing layer-1 columns and structure faces that rest on it (a non-walkable cell rests on the first walkable cell to its south), skirt faces at its edges towards lower neighbours, and characters / props / ground decals / icons (`groundHeight`, now also for flat and overlay draws). Characters on flights and podium stairs are lifted along a continuous ramp (no hop between treads). Render only. `-Dg3.render3d.noplateau=true` switches the plateaus off (before / after shots). A flight ending at a non-walkable cell (village north gate at the map edge) raises nothing.
* **Block sides**: `block[:depth]` pieces are solid volumes: south face (tile art), top cap (colour of the tile's top row, column top
  only) and west / east side faces `depth` tiles deep towards the north. Sides stretch the tile's own edge pixel column (reads as
  the material's cross-section, shading comes from the lighting) and are omitted where an equally deep block of the same height
  level touches (layer 1: same column chain level; layer 0: neighbouring cell), so there are no hidden faces / z-fighting between
  adjacent blocks. The north face is not built (fixed yaw, never visible; the depth pass is double sided so shadows are still
  correct). Layer-0 standing shapes (fences, layer-0 blocks) live in their own mesh bucket (5) so floors never act as shadow casters.
* Texture filtering nearest, no mip maps, optional linear fog towards black (`render3d.fog`). Sheets/sprites become GL textures
  lazily (`TextureCache`, LRU).

## Capture area (entities beyond the game's 2D window)
The game only keeps entities inside its 2D cull window (plus a few cells) in the world pass; the 3D camera sees much more (lower pitch,
zoom out, widescreen sides). The capture area therefore follows the 3D frustum:
* GL thread (`Renderer3D.publishCapture`): the frustum's ground footprint (y = 0; rays above the horizon are clamped to `fogEnd + 4` tiles
  around the focus), bounding box in cells, margins 3 west / north / east and 6 south (tall sprites whose feet are below the edge), published
  as `RenderConfig.captureHint()` (immutable int[4] in a volatile). One frame of lag is covered by the margin.
* Game thread (`Render3DHook.extendWindow`, `GameEngine.renderWorld`): the hint (union with the 2D window, clamped to the map and to
  `MAX_EXTRA` = 48 cells beyond the window on every side) extends the entity loop on all four sides. Without a hint, or in deterministic
  mode (`--deterministic`, `--trace-ticks`), the fixed margins `render3d.capture.margin = west,north,east,south` (default `8,16,8,8`) are used, so traces never
  depend on GL (`-Dg3.render3d.captureFeedback=true` forces the feedback in deterministic runs, used for screenshots).
* **State stays identical to 2D** for the extra entities (cells outside the original window, `GameEngine.portExtra`):
  object animations are not advanced in the game row; every draw is bracketed by
  `RenderMutations.guardBegin/End` (scratch statics `scratchPos3/Sort/...`, `shadowPos`, `animBoundsOut`, `xD` and the game RNG are restored); the
  getSortY calls of the draw-order walk are guarded the same way (`portSortY`); each extra entity's whole row is restored after the pass; the sort-chain
  links of the window entities are saved after the window chain is built (exactly as the 2D pass builds it) and put back after the pass. Extras are inserted
  into the chains afterwards, so ties against window entities may order differently (visual only). The bookkeeping statics are excluded from `StateHasher`.
  **Object animation of the extras** runs on a render-side clock: `GameEngine.portExtraAnimFrame` keeps the animation word `[10]` of each extra object in an identity map
  `{lastRealWord, ownWord, lastTick, shownFrame}`, runs the game's own `updateObjectAnim` on the row with the own word swapped in (the real word is put straight back,
  the row is restored after the pass anyway) once per canonical render (`tickCounter` changes; in-between frames show the frame of their canonical frame) and resyncs when logic
  changes the real word (e.g. an object starts to animate). The map is cleared on a map change and excluded from `StateHasher`. When such an object enters the 2D window it continues
  with the real (frozen) value, so it can step back once. Proof: a burst of frames with the camera zoomed out (3 far fires over 4 frames); the screenshots are not included in the repository.
* Extra NPCs are not frozen by this code: the render never touches their state, their walk-cycle frame (`getAnimFrame`, a field of the entity row that logic advances) is what logic
  leaves, so they animate as far as logic updates them (the far NPCs of the zoomed-out check moved between frames).

## Map surround (`render3d.surround`)
Outside the map the view used to be hard black. `MapMesh.buildSurround` adds a ground skirt of `render3d.surround.tiles` (8) tiles around the map:
the edge floor tiles mirrored outward (layer 0 art and transform), one blended mesh, per-vertex alpha `(1 - d/n)^1.4` so it dissolves into the
clear colour. The clear colour and the fog colour are the map's background (`render3d.surround.color = auto`: average layer-0 ground colour x ~0.3,
slightly cool, so snow maps get a muted grey blue; or `RRGGBB`). Interiors (`render3d.indoor.tilesets`) keep black and no skirt. Off with `render3d.surround = false`
(black as before). Cost: about 3500 quads on a 100x100 map, no culling.

## Lighting and shadows (`Renderer3D`, `QuadBuilder`)
* Every vertex carries a normal (floor up, walls south, slopes leaning, block sides west/east, cards and sprites tilted 30 deg up
  towards the camera so characters stay readable). The vertex shader computes `max(n.L)`, the fragment shader multiplies the texture by
  `ambient + sunColour * n.L * shadow` (before fog). Sun: `render3d.sun.azimuth` (compass, 0 north, 90 east, 180 south; default 205 =
  south-south-west, so south faces are lit and shadows fall north-east), `render3d.sun.elevation` (48), `render3d.sun.strength`, `render3d.ambient`.
  The ambient is slightly cool, the sun slightly warm. World UI (overlay pass) is never lit.
* **Interiors**: maps whose layer-0 or layer-1 tileset id is in `render3d.indoor.tilesets` (default `5`, the start house) get a warm
  ambient plus a weak directional term, no sun shadows (blob shadows stay).
* **Shadows**: one sun shadow map (`render3d.shadows` off | low 1024 | high 2048), orthographic, 40x40 tiles around the camera focus
  (shifted 4 tiles north), snapped to light-space texels (no shimmer when the camera moves). Depth is packed into an RGBA8
  colour target (no depth texture / GL3 needed). Casters: layer-1 pieces and layer-0 standing shapes, block caps/sides, and all
  upright sprite cards (alpha tested at 0.5 in the depth shader, so tree and character silhouettes cast correct shadows). Receivers:
  everything. 3x3 PCF with nearest texels, constant bias plus a normal offset of 1.6 texels. The game's blob shadow decals (the 16x8
  image id 171) are skipped while shadows are on (`render3d.blobShadows = true` keeps them).
* **Fallback**: a shader or GL error in the shadow pass disables shadows for the session (logged), `render3d.lighting = false`
  restores the old flat look; a failure elsewhere still falls back to the 2D view.
* Dev: `-Dg3.render3d.tune=<file>` is a properties file whose `render3d.*` keys override the config live (re-read on F6 / script
  `reload3d`; also `render3d.tiles`), used for the screenshot sessions. Not done: day / night. Point lights for fires: see "Props and point lights".

## Occluder fade ("see the hero behind things", `Renderer3D.occluderFade`, `render3d.occFade*`)
* Things in front of the hero (tree trunks and crowns, voxel / lathe props, benches, walls, roofs, structures) dither out around him so he stays visible, with a faint
  ghost of the obstacle left. It is one extra block in the main fragment shader, so it covers every draw of that shader (tile mesh, structures, cards, prop models) without
  per-object bookkeeping and without any change to the batches.
* **Test per fragment** (all cheap uniforms): (1) the pixel lies inside a soft ellipse around the hero's chest in window pixels (full effect inside half the radius, none at the
  rim; the radii follow the camera: about 1.55 tiles wide, 2.5 hero heights tall); (2) the fragment is in front of the hero: world `z` is more than 0.35 tile south of his
  feet, eased in over 0.6 tile (the hero stands in a vertical plane; any point on the camera ray to him that is nearer to the camera has a larger `z`);
  (3) it is above knee height (`y` more than 0.3 above the ground under his feet, `MapMesh.groundHeight`, so floors, decks, steps and ground decals are never faded).
  Inside, the fragment is kept with probability `render3d.occFade.min` .. 1 using a 4x4 Bayer threshold on `gl_FragCoord` (screen-door fade), else `discard`ed.
  Discard keeps depth writes correct (no transparent sorting for cards / models), the hero card and the world UI overlay (fade switched off for that pass) are untouched, and
  shadows (the depth shader) are not affected: the faded tree still casts its shadow.
* **Animation**: the strength is eased in and out over about 200 ms on the render clock (when it is switched on, or the camera stops following the hero: scripted camera,
  cutscene). Otherwise the fade is spatial: an object dissolves smoothly as the hero's ellipse moves over it (the hero scrolls through at walking speed). No per-frame allocation (a few uniforms, one reused vector).
* **Settings** (F10 > 3D tab, applied live, no mesh rebuild): `render3d.occFade` (default `true`), `render3d.occFade.min` (0 .. 0.9, default `0.22`: opacity left on a faded object; 0 = gone, higher = stronger ghost).
* Render only: no game state is read or written (`s=` trace hashes unchanged). Limits: the test is screen-space (ellipse around the hero, not per object), so a nearer object that
  does not actually cover him but passes through the ellipse also thins; the hero is not redrawn as a silhouette (the dither lets his own pixels show through);
  only the player is handled (`hasFocus`), not NPCs behind objects. Debug: `-Dg3.render3d.occlog` prints the ellipse once per 60 frames.

## Caves (`render3d.cave.tilesets`, tileset 7)
The random cave maps (encounter terrain kind 2, 32x32, layer tileset 7 on both layers) are mazes of 1 tile wide corridors. The 2D art draws each rock mass as a 3 row high rock face
(layer-1 ids 5-13) with black cap / edge tiles (14-17) above and around it. Through the structure builder this became solid boxes up to 24 tiles high with brown, floor-like tops
(the "dark cap = wall colour" recolouring), hiding the corridors behind them. Now (config `render3d.cave.tilesets`, default `7`, empty / `none` = off):
* **Dollhouse cutaway, automatic by tileset.** Maps on a cave tileset skip the structure builder and use the per-tile table (`tiles.properties`, `ts7.*`): the three face rows are
  a low ramp (`slope:78`, 0.21 rise per row, about 0.6 at the ridge) and the black tiles are `flat` on the ground, exactly as in the 2D art, so tops are black void, never brown.
  The camera hides only `1.28 x height` of ground behind a ridge (about 0.8 tile), less than the black cap rows behind every rock mass, so every corridor tile, the hero, NPCs, enemies and
  items stay visible from the default camera; the occluder fade needs nothing (nothing tall stands in front of the hero). Tune the lean (`slope:N`, 90 = flat, 70 = steeper / higher) per rule.
* **Lighting / framing**: cave maps count as interiors (no sun shadows, black surround, dust motes) with a cool grey-blue light (`Renderer3D.caveMap`; the warm room light turned the blue-grey floor tan).
* **Stalagmite** (`i47`, a rock with a dithered puddle ring in one sprite): `voxel:0.35 core`. The new `core` prop token extrudes only the largest 4-connected part of dense pixels (>= 3 solid 4-neighbours);
  the dithered ring stays on the flat front quad instead of becoming white stripes.
* Dev: `PortBridge.warp` can reach random maps with a local hack (area 1000 + kind); the shipped game only generates them through encounters.

## Atmosphere (`render3d/atmo`, `atmo.*`, `render3d/atmosphere.properties`)
Cosmetic in-scene effects for the diorama look. Everything runs on the GL thread on its own render clock (wall time, so it keeps animating while the options
screen has the game paused); nothing reads or writes game state and nothing uses the game RNG (particle seeds come from a private `java.util.Random`).
Drawn inside the scene pass (after the upright sprites, before the world UI overlay and the HUD composite), so a post-processing target bound by the frame
plumbing gets them (bloom, DOF). All effects have on/off switches and sliders in the F10 "Atmosphere" tab (live, `Renderer3D.reloadAtmosphere`: settings only, no mesh rebuild).
(Screenshots are not included in the repository: they show the original art.)

* **Data table** `game/src/main/resources/render3d/atmosphere.properties` (F6 reloads): weather per map / tileset (`default`, `tsN` (layer-1 tileset first, then layer 0), `mapN`
  = `snow | dust | none`; maps on an indoor tileset fall back to dust) and window tiles (`window.tsN = bright|dark : ids`, layer-1 tile ids; the opening is found from the
  tile's pixels). Shipped: tilesets 0 and 3 snow, 5 and 6 dust, windows ts5 bright (18, 20), ts3 dark (8, 9, 16).
* **Snow / dust motes** (`Particles`): static quad meshes (9000 / 2000 quads); position, wrap, sway and fade are computed in the vertex shader from a time uniform, in a
  camera-relative wrapped volume (46x14x40 tiles around the focus, 5500 flakes at density 1; 900 motes in a 26x5x22 volume). Billboards, depth tested, no depth write, linear fog like the
  scene, colour = ambient + sun light level of the scene (so it follows time of day). Motes are additive and only bright near fire lights and bright windows. Sliders: amount, size, wind.
* **Embers and smoke**: per fire point light (the nearest `render3d.pointLights.max` that `PropLayer` already selected, so they need `render3d.pointLights`): 40 additive glow
  quads per light rising 1.5-3 tiles, orange to red, flickering, drifting with the wind slider; optional smoke wisps (alpha blended, grow while rising, off by default).
* **Light shafts** (`Shafts`, additive soft quads, no depth texture): interiors get three slanted sheets under every bright window tile (fall to the floor along a fixed direction, tinted
  by time of day, slow dust shimmer in the shader); outdoors a world grid of soft sun beams along the sun direction around the focus (very subtle on snow).
  Dark windows glow warm at night (`atmo.tod.windowGlow`, `atmo.tod.glow`).
* **Rim light**: in the main fragment shader (`u_rim`): the silhouette edge of an upright card on the side away from the sun (screen right when the sun is in the west, plus the top edge) is
  found from the texture's alpha (neighbour texel alpha), and brightened with a cool tint outdoors / warm indoors, added after lighting so shadows do not hide it. `SpriteLayer` batches
  by mirror / flip state (`rimSide`, `u_rimDir`, `u_texSz`); rotated sprites get the top edge only. The edge texel must have art on the opposite side (`m1` / `m2` taps): a dithered 1 px edge (burnt trunk tops `i24`, checkerboard of opaque / transparent texels) is no silhouette; before, every dither texel got the rim and the tips looked like white noise. Characters and other cards get it; prop meshes do not.
* **Soft, coloured shadows**: `atmo.softShadows` replaces the 3x3 PCF by 16 spiral taps rotated per pixel (interleaved gradient noise) with radius `atmo.shadow.softness` texels;
  `atmo.shadow.contact` adds an 8 tap blocker search so the penumbra grows with the caster distance. Shade (faces turned away or in shadow) multiplies the ambient by
  `atmo.shadow.tint` towards a tint colour (`atmo.shadow.tintColor` = `auto`: bluish outdoors, warm indoors, or `RRGGBB`).
* **Time of day** (`atmo.tod = off | morning | noon | evening | night | cycle`, `atmo.tod.cycle` seconds per day): key frames for sun azimuth / elevation, sun and ambient colour,
  fog colour and a point light boost (x2.3 at night); cycle blends night - morning - noon - evening. Off (default) is bit-for-bit the old lighting. It only changes colours / the sun direction used
  for rendering; shadows follow the sun.
* **Ground mist** (`atmo.mist`, `.density`, `.height`): exponential height fog in the main shader (mist colour from the scene light level, slow noise), outdoors only.
* **Config** (`Config` block `atmo.*`): `enabled`, `snow` / `snow.density` / `snow.size` / `snow.wind`, `embers` / `embers.density` / `embers.size`, `smoke` / `smoke.density`, `dust` / `dust.density`,
  `shafts` / `shafts.strength`, `rim` / `rim.strength`, `softShadows`, `shadow.softness` / `shadow.tint` / `shadow.tintColor` / `shadow.contact`, `tod`, `tod.cycle`, `tod.windowGlow`, `tod.glow`, `mist`, `mist.density`, `mist.height`.
  Defaults: snow, embers, dust, shafts 0.5, rim 0.5, soft shadows on (2.2 texels, tint 0.5); smoke, time of day and mist off. `-Dg3.render3d.tune` may override `atmo.*` too (used for screenshots).
* **Fallback**: shader failures in the particle or shaft passes (or a GL error after them) disable that pass for the session (logged, the rest keeps working); `atmo.enabled = false` restores the exact previous
  look (the soft shadow filter, tint, rim and mist branches in the main shader are off).
* **Cost** (RX 6600 XT, hidden, vsync off, village, post chain included, run to run noise +-25 % because other jobs shared the GPU): 1280x720 about 880 fps off, 600-610 defaults, 490-610 with everything on (smoke, mist,
  contact shadows, cycle, 8000 flakes); 1920x1080 about 670 off, 617 defaults, 400-580 everything on.
* **Verification**: `scripts/walk-ticks.script` with `--trace-ticks`, `atmo.tod = cycle`, smoke and mist on: `s=` hashes of 2D and 3D identical in all 1141 ticks; `--verify` 661/661 alpha-1 frames identical, no game state field changed.
* **Limits**: windows are placed from tile ids and the depth tag level, so structures that squash walls (`capWall`) can put a shaft or glow a little off; outdoor beams are a decorative world grid, not
  occlusion aware (no real volumetric light, no screen-space god rays); the rim also lights cards such as trees; mist and snow are not occluded by roofs (snow falls inside houses seen from above); no weather in
  the world map / 2D view; time of day does not change the game's own palette or HUD.

## Tile table (`game/src/main/resources/render3d/tiles.properties`)
Maps `(layer, tile id[, tileset id][, depth tag])` to a shape. Defaults: layer 0 `floor`, layer 1 `upright`. Reload with F6; the file is
read from `render3d.tiles` (config) or, in a source checkout, `game/src/main/resources/render3d/tiles.properties`, else from the classpath.
```
default.layer0 = floor
default.layer1 = upright
layer0.17 = floor                 # tile 17 of layer 0 in every tileset
layer1.40-47 = block:0.5          # ranges; block depth in tiles
ts3.layer1.23-34 = slope:55       # only when the layer-1 tileset id is 3 (the village set)
layer1.40.d0 = flat               # only for tiles with depth tag (rows above the base row) 0
ts0.layer0.29 = floor:7           # show tile 7's art instead of tile 29
ts0.void = 7                      # layer-0 tile for cells that have none (2D: black, hidden under roofs)
```
Later lines win over earlier ones. A `# comment` may follow a value. **Per-map overrides** (additive, old files stay valid; the map id is the game's
area id, shown in the editor panel):
```
map5.layer1.40-47 = hidden        in map 5 only: tile ids / ranges / .dN depth tags as above (the map's own tilesets), beats the table
map5.cell1.12.30 = slope:55       in map 5 only: the layer-1 tile stored in cell x=12, y=30 (layer 0: map5.cell0.X.Y); beats everything
```
Precedence: cell override, per-map tile override, table (tileset specific before any tileset, depth specific before any depth), default.
The cell of a layer-1 tile is the cell that stores it (its own row), not the base row of its column.
Area 41 (snowy ruin, ring of stone pieces around a plank floor with a skeleton) uses per-map rules: the structure builder turned the ring into one 4 high block hiding the plank floor
and the skeleton, so its pieces are `flat` (as in the 2D art), the plank floor (layer 0 id 38, else a raised deck) `floor`, the gate posts `cross`; see the `map41.*` lines of `tiles.properties`.
`flat` / `floor` / `cross` / `hidden` are the shapes that keep a tile out of the structure builder (upright / slope / block stay in it).
File loading: without an explicit file, the built-in classpath copies are loaded in the order `tiles-auto.properties` (auto tags) <
`tiles.properties` (hand tags), and `~/.config/gothic3-pc/tiles.properties` (XDG aware, the user file) is applied last on top of them. An explicit
file (`render3d.tiles` config, or the source checkout's `game/src/main/resources/render3d/tiles.properties`) is loaded instead of the built-in
hand table, with its sibling `tiles-auto.properties` loaded underneath it; the user file is not applied in that case.
Aid for tagging: `-Dg3.render3d.dump=<dir>` writes, per map, the tileset sheets with tile ids drawn on them and a usage list
(layer, tile id, count, count per depth tag).

Current table: roofs of the village set (tileset 3) lean back, fences (layer 0 ids 27-28 of set 0) stand, black void cells become ground.
Everything else uses the defaults (floors flat, all layer-1 pieces upright). Interiors work with the defaults.

## Structures (`render3d/structures`, `render3d.structures`)
The 2D art draws a building as ONE oblique picture: the bottom rows are the front wall, the rows above are the roof (or the dark top of a
wall) seen from above, and a horizontal step of one tile north looks like one tile up. Per-tile shapes (upright / slope / block) cannot
rebuild that (sawtooth ridges, a detached gable, slab roofs, staircase walls). The structure builder groups the layer-1 cells into
structures and builds merged geometry per structure. Config `render3d.structures = true|false`, `render3d.structures.split` (config / tune file, default true; false = the
old per-tile look; F6 re-reads it together with the table, the mesh is rebuilt). Tunables: `render3d.structures.stripHeight` (0.4),
`.roofDepth` (1.0, scale of the gable roof depth), `.capWall` (2.5, max height of walls with a dark cap), `.wallDepth` (1.0, thickness of walls
without a cap in the art), `.thinCaps` (true), `.minThickness` (0.25), `.maxThickness` (0.32), `.indoorWallDepth` (0.4); see "Thin partitions" below. Code: `Structures` (grouping + geometry), `TileInfo` (pixel statistics, transforms), `Atlas` (private texture
atlas, MapMesh bucket 6, solid + shadow caster), `Settings`, `StructureDump`. The hook in `MapMesh` is marked `STRUCTURES`.

**Detection.**
1. *Cells*: layer-1 tiles that the chain walk reaches, are not fully transparent and whose table shape is upright / slope / block. Explicit
   `hidden`, `flat`, `floor` and `cross` entries (hand, editor, auto) win: such tiles stay on the per-tile path and are not part of a structure.
2. *Tile kinds* from the tile pixels (`TileInfo`): `snow` (>= 80 % light grey, low saturation pixels), `black` (>= 80 % pixels with luma < 30),
   `mixed` (>= 52 % snow and >= 15 % material: the brown gable triangle tiles with snow flanks), `face` (everything else). snow / black are
   the "top" kinds (roof or wall top seen from above).
3. *Grouping* (union find over 4-neighbour cells): vertical neighbours join when they have the same base row (depth tag + row), or both are
   top tiles with the same tag (clamped tags of tall cap runs), or both are tag-0 non-top tiles (stacked rows of a wall, e.g. the map's north wall);
   horizontal neighbours join when they have the same base row. The base row is NOT trusted for the vertical position: the
   depth tag only drives the painter order of the game. Level of a cell = base row of the structure (largest base row of its non-top cells) - its row.
4. *Rows* of a structure from the bottom: `hw` = number of face levels (face tiles >= 50 %, no mixed tile): front wall height. Then `ng` gable levels
   (levels with a mixed tile). Everything above is top (`nT` levels). Structures made only of top tiles (the snow border strips along the map
   edges) are merged across rows (4-neighbours) into strips. Single cells keep the per-tile path.

**Geometry** (all coplanar quads of one texture, no seams; x east, z south, y up; base line z = base row + 1):
* *Front face*: one composite image of the face and gable levels at 16 px per tile, standing at the base line, height = levels. In gable levels
  every non-warm pixel (snow, grey) is keyed out, so the front shows the exact pentagon (wall rectangle + gable triangle) and the snow flanks
  are left to the roof. Cap / snow tiles inside the wall (the end of a partition's cap running into the north wall) take the art of a neighbour.
* *Gable roof* (structure with gable levels): two slopes from the eaves (side walls at wall height) up to a ridge at the centre. Ridge height =
  height of the topmost wall pixel of the gable + 0.1, depth = (height of the topmost roof pixel - ridge height) * `roofDepth`, i.e. the depth the
  oblique art implies. Roof texture = the commonest plain snow tile of the structure, tiled (baked into the atlas), correct normals for lighting.
* *Flat roof / wall top* (structure with top levels, no gable): a horizontal plane at wall height over the same depth as the art has rows;
  the art is mapped 1:1 (top-down). Dark caps are recoloured with the colour of the wall's top row (they read as the wall top, not as a hole). Walls with a
  dark cap are scaled to at most `capWall` tiles high (interior partitions: rooms behind them stay visible). Without top rows the top is one colour (top
  row of the wall tile) and the box is `wallDepth` thick.
* *Side faces*: per wall cell at both ends of a wall run (also at door gaps): the wall tile's own art, mirrored (its south end continues the front face's edge) and
  repeated along the depth of the box, so stone reads as stone and planks as planks (was: one stretched pixel column = vertical stripes). No back face (never visible, the shadow map is double sided).
* *Eaves* (gable roofs): each slope's outer 16 texture columns are the cross section of the art's roof edge tile (per column average of the commonest fully opaque snow tile at the
  structure's outermost column, e.g. ts3 tile 27 left / 30 right: the soft grey rim), the rest the plain snow tile. A fascia (0.13 high, rim colour) runs along each eave and along
  the front edge of each slope (the roof overhangs by 0.06 / 0.05 tiles). Slopes stay continuous.
* *Wall tops*: dark caps and one colour tops use the lighter half of the wall tile's pixels (lit wood / stone), lifted to at least luma 88 (`lift`), so indoor tops read as wood,
  not as holes (the indoor light is unchanged: the brightening is in the texture).
* *Connected roofs* (`render3d.structures.mergeRoofs`, default true): a gable house whose roof ends (north) exactly at the front wall of a flat snow topped building next to it
  (same eave height, touching x ranges; a gable on one or both ends = L / U compounds, e.g. map 1 at 21..23 / map 8 at 7..11) is not built alone. The wings run on to the back edge of
  the flat building, which gets an east-west ridge at the wings' ridge height (wing ridge to wing ridge, free end = gable end). The roof is the union (max height) of the gable prisms:
  every roof plane is clipped against the regions where another prism is higher (convex polygon subtraction), so valleys / hips fall out; eaves, fascia, extension side walls and the
  back wall follow. A wing roof may end up to one tile south of the flat building's front wall (map 2 gate compound): the compound fills the gap and walls its sides. Columns of snow tiles with one constant depth tag (a walled compound's thin ring wall) never join the houses beside them (`column`), so the wings are separate gable structures. Everything else keeps the single gable roof. Code: `Structures.finishRoofs` / `compound`.
* *Splitting*: houses that touch side by side with the same base row are one union-find component; `findCuts` cuts the component at x where, on a roof or gable level, a tile with a
  transparent right edge (>= 30 %, the rounded eave corner) is followed by one with a transparent left edge; the cut applies to all rows (walls too) and the grouping is redone.
  `render3d.structures.split = false` switches it off (before / after: `20_adjacent_houses_*`).
* *Strips* (snow banks along the village edge): box `stripHeight` high, the tile art as flat top, solid coloured sides.
* Lighting / shadows: faces carry normals (south, west, east, up, slope normals), bucket 6 is drawn in the solid pass and in the shadow depth pass.
* Cells covered by structures are skipped by the per-tile path (`Structures.Result.covered`); the editor's `PickGeometry` rebuilds the structures and
  picks on cell-sized quads that lie on the structure faces (front face, roof / top), so hover and select still work and show the tile under the cursor.

Dev: `-Dg3.render3d.structdump=<dir>` writes ASCII grids of tile ids / depth tags and tile statistics (`grid_mapN.txt`, `tilestats_tsN.txt`),
`-Dg3.render3d.structdebug=1` logs every structure (bounds, base row, hw / gable / top levels, depth) to stderr.

### Thin partitions (interior walls)
In the 2D art a partition is a front face plus a cap drawn as a few black pixels (a thin line), not a full tile. `Structures.thinCaps` measures the cap tiles' opaque pixels:
columns with a cap run longer than one row (partition running north) get the opaque x extent (centred on the art's line, clamped to `minThickness`..`maxThickness`: the art's black band is
~0.5 tile but reads as a thin wall), single cap row columns (east-west partition) the depth of the opaque part of that row, flush with the front face. Only caps thinner than 0.75 tile are
touched (big black tops keep their volume, so exterior houses are unchanged). Props in neighbouring cells no longer intersect the wall because the volume sits on the art's line.
* **Heights**: no squash for the usual two face rows; `capWall` (default 2.5) caps taller walls with a dark cap so that rooms behind stay visible; doors keep a lintel clearly below the top.
* **Cap texture**: the lifted flat cap colour with 30 % of the wall tile's grain (joints / planks); cap-less walls of indoor maps use the wall tile's art for their top.
* **Cap-less walls on indoor maps** are `indoorWallDepth` (0.4) thick instead of `wallDepth`. "Indoor" = tileset in `render3d.indoor.tilesets` (default 5, also drives lighting) or in
  `render3d.structures.indoorTilesets` (structures only, default 4 = the brick castle interiors, areas 17 / 18).
* **Junctions within a structure**: where a wall continues next to a thin column, the front face keeps the full tile and a filler top + back face closes the gap between the thin edge and the neighbour's slab.
* **North end (T junction)**: when a thin partition ends at another structure's wall (the nearest wall face at most 2 empty rows north, on either side), the partition is extended to that wall's
  face plus one wall thickness (0.3), so its cap runs through the wall's slab, and the gap between the partition's thin edge and the wall piece (also the whole tile column the partition passes
  through) is plugged with the neighbour tile's art and a filler top. Debug: `-Dg3.render3d.structdebug=1` logs structures (with map id / tilesets) and the thin-cap measurements.
* Keys: `render3d.structures.thinCaps` (true), `.minThickness` (0.25), `.maxThickness` (0.32), `.indoorWallDepth` (0.4), `.indoorTilesets` (4), `.capWall` (2.5). No per-tile table property: the
  thickness comes from the art (`thinCaps=false` restores the old slabs).

### Structures: results and known limits
* Verified: `./gradlew build` passes; `--trace-ticks` with `scripts/walk-ticks.script`: the `s=` hashes of 3D (structures on) equal 2D in all 1141 ticks; `--verify` in 3D:
  661/661 alpha-1 frames identical, no game state field changed. Performance (same setup as above, village, hidden 1280x720, vsync off): ~1050 fps with
  structures, ~1320 fps with `render3d.structures=false` on the same build (cost: ~600 more quads, one more texture, both in the shadow pass).
* Visual checks (screenshots not included in the repository): gable houses, flat stone house roof, start house interior with partition walls, temple walls with door gap, low pitch (pitch 20: houses are closed volumes), and the same place with `render3d.structures=false` (the old sawtooth roofs).
* Polish step (part B): eaves with the art's rim + fascia, side faces with the wall art, lighter wall tops, side by side house splitting, animated far objects (see Structures / Capture area).
* **Fences**: in the village (and the other maps checked) the fences and gates are *objects* (entity sprites), not tiles. The only tile fences are layer-0 tiles 27 / 28 of tileset 0
  (4 cells at the top left of the village, under the snow bank, not visible), so there is no tile-fence geometry; solid fences come from the prop model path (`render3d/props`, voxel
  extrusion of object sprites). `StructureDump` now also writes `grid0_mapN.txt` (layer 0 tile id.transform) for tagging aids.
* Not done / still off: the back of gable roofs is a flat dark triangle; a wall without a top row in the art gets `wallDepth` thickness and a one colour top; walls with a dark cap are
  squashed to `capWall` tiles; splitting only finds roofs with rounded eave corners (houses with square roof corners would still merge; no cut for flat wall runs); the eave rim is a
  one tile cross section, not the art's diagonal outline; structures are only rebuilt with the mesh (map change / F6), not per frame.

## Baked contact shading (`AoField`, `atmo.ao`)
Soft contact darkening so geometry sits on the ground: floor at the foot of walls and block volumes, inner corners, around raised plateaus, the lower part of walls (cave rock ramps too).
* **How**: when `MapMesh` finishes a map it reads the vertices of its built meshes (not the ground decals) and rasterises the top height of every quad into a coarse height field (4 texels per tile). `AoField` dilates it (3x3 max, so thin wall planes keep weight) and blurs it on the CPU (two box passes per axis, a tight and a wide radius in the R / G channels) into a tiny RGBA8 texture (bilinear, so no cell-boundary stair steps). The main fragment shader samples it by world x / z and compares the blurred height with the fragment height: occlusion = how far the neighbourhood rises above the fragment. Walls (normal not up) fade the effect out above 1 tile so facades are only darkened at their base. No depth texture, no GL3, no vertex format change, no change in `Structures` / `PropBuilder` (props receive it by their world position).
* **Props**: `PropLayer` reports the footprint box and top of every prop model it draws (`AoSink`); `AoField.addBox` adds each static box once (shrunk to its core, skipped when larger than 2.5 tiles, flat or hanging), so furniture, barrels and pillars made of prop models get a contact shadow; the tiny texture is re-blurred lazily. Props drawn as flat cards are not covered.
* **Strength**: interiors use 3x the slider (the ambient is nearly all their light), outdoors 1x (subtle, mostly visible in shade); the result is capped at 90 % darkening.
* **Applied to**: the ambient term (full strength) and 30 % of it on the sun term; point lights and sun shadows are untouched, so it never fights the shadows. Not applied to upright cards (characters, trees), the world UI or the HUD. Cut-out edges get no halo: the factor only scales the fragment's own colour.
* **Config**: `atmo.ao` (default true), `atmo.ao.strength` (0.5, max darkening of the ambient at full occlusion), `atmo.ao.radius` (0.8 tiles, blur; changing it re-blurs the tiny texture, no mesh rebuild). F10 Atmosphere tab: Contact shading / strength / radius. `atmo.enabled = false` or `atmo.ao = false`: strength uniform 0, shader branch skipped, bit-for-bit the old image. A failure in the texture build disables it for the session (logged).
* **Relation to `post.ssao`**: the post chain's screen-space AO (needs the depth texture) still exists; this one is baked, stable under camera motion and works without a depth texture, so the two stack.
* **Limits**: the field is 2.5D (a height per cell): the floor under a deck or overhang is darkened too; transparent parts of upright cut-out quads still count as occluders; AO does not follow props, doors or characters; heights are clamped to 2 tiles.

## Props and point lights (`render3d/props`, `render3d/props.properties`)
Object sprites (barrels, braziers, chests, benches, signposts, fences, statues, wells, ...) are no longer flat cards: they become small meshes built
from the sprite's own pixels, lit and shadowed like the rest (`render3d.props = off` restores the old flat cards).
* **Identification.** The capture only knows image + source rect + pass. `ImageIds` maps the game's `Image` objects back to data ids by reading
  `GameEngine.images` / `sheetImages` (read only, GL thread, re-scanned when an unknown image shows up): `i<N>` = object / item / effect images (props),
  `s<G>.<S>` = animated character sheets (NPCs, monsters, the player). Recolour variants (`i21.1`) share the rule of `i21`.
  `-Dg3.render3d.propdump=<dir>` writes a crop of every distinct sprite seen (file name = id, rect, pass) and logs light / model counts: use it to find ids.
* **Table** `game/src/main/resources/render3d/props.properties` (source checkout file, else classpath; F6 / `reload3d` reloads it). `i<N>[@x,y,w,h] = tokens`,
  `default.object` (default `voxel:auto`) and `default.sheet` (default `card`). Tokens: `card`, `voxel[:depthTiles|auto]` (auto = 0.45 x width in tiles, 0.12..0.7),
  `lathe[:sides]`, `cross`, `light:RRGGBB:radius:height[:intensity]`. Currently: fences, signposts, banners, door panels, impaled bodies: thin voxel;
  logs, chests, shelves, beds, benches, tables, statues: voxel 0.3 - 0.6; barrels, the well, the column / cushion: lathe; dead bushes: cross; dead trees,
  loose twigs, stumps: card (the extrusion of sparse branches looks like cardboard, the alpha tested card already casts the right shadow); fires: voxel + light.
* **Voxel** (`PropBuilder.voxel`): front = one quad with the exact sprite (alpha tested), back = shadow only quad, sides and tops from the pixels: for each
  opaque pixel with a transparent neighbour, runs of such faces are merged (greedy) and textured by *sampling the edge pixels* (u constant = the pixel column,
  v running along the run), so no extra texture is needed and the edge colours carry over. The volume extends from the card plane towards the north by `depth`.
  Transforms 0..3 (flips) are supported, others stay cards. More than 3500 quads: card. Flames (`light` rules): bright warm pixels with nothing solid above
  them stay flat on the front quad and are not extruded (no spikes).
* **Lathe** (`PropBuilder.lathe`): the oblique art shows the top as an ellipse; its far half becomes a flat disc cap (planar mapping of the ellipse onto the
  disc, so a well shows its dark opening, a barrel its lid), below it the silhouette is revolved around the vertical axis (per pixel row radius, `sides` segments,
  the texture columns of the sprite are wrapped round: the front looks like the art, the back mirrors it). **Cross**: the card plus a second card turned 90 degrees.
* **Cache / drawing** (`PropLayer`): meshes are cached per (image, rect, transform, type, depth) (LRU 2500), so animated props (fire frames) just build
  one model per frame once. Per scene (and shared by the shadow pass) `prepare` decides the model of every upright draw; visible models are translated to
  their anchor (vscale / lean applied) and copied into one dynamic mesh (batched per texture, sorted by texture). The card layer skips draws that got a model
  (`SpriteLayer.skip`). Models are drawn after the flat decals and before the cards with depth *write* on (characters / effects stay cards, depth tested against
  the models, so they hide behind and in front of them correctly), they cast shadows (the back quad is shadow only) and receive them.
* **Lying objects** (`lie[:height]`, `PropBuilder.lie`): sprites that LIE on the floor in the top-down art (beds, a board / sled, bedroll, skeleton remains) would stand up as boards.
  The art becomes the top of a low box (default 0.35 tiles; picture flat, bottom edge at the sprite's bottom, walls from the silhouette's edge pixels, north side omitted). Tagged:
  i41 / i55 beds (0.4), i68 bedroll (0.15), i69 camp bed (0.2), i58 snowy board (0.08), i46 skeleton (0.04). Stools (i39), logs, benches and the front-view counter i79 are upright objects and stay voxels.
* **Floor-pass furniture / tables** (`table[:height[:cutRows]]`, `PropBuilder.table`): the game draws some objects in its *floor* pass (`drawEntity(.., flat=true)`, `Scene3D.PASS_FLAT`), which the 3D
  view laid on the ground as a decal. The table i217 (16x32, top-down plank top with a pedestal stub) therefore looked like a texture on the floor. `PropLayer.prepare` now also looks at
  PASS_FLAT draws, but only gives them a model when the sprite has an *explicit* entry in props.properties (`PropRules.explicit`; rugs i241, dust / effect puffs, blob shadows stay decals), and
  `SpriteLayer` skips those draws too. `table` = the `lie` box (top at the height, default 0.45, solid body below) without the bottom `cutRows` pixel rows (the stub). Tagged: `i217 = table:0.45:5`.
  The temple altar i196 (candle at each end, also floor pass; a crystal sprite stands on it, lifted onto the top by `liftOnTables`) is `table:0.45:0` too (it was a flat decal: the chapel of map 48 showed it flat among the 3D pews). Screenshots: tavern (area 22) before / after, area 23, 19, 26.
  Dev: `-Dg3.render3d.propdumpall=true` (with `propdump`) also writes every loaded object image as `all_i<N>.png` to find more candidates.
* **Ground contact.** Many object sprites (dead trees, stumps, chests) have transparent rows under the art but are anchored at the bottom of the frame, which lifted
  trunks / feet off the floor (floating look, shadows starting below the trunk). `PropLayer.prepare` measures the transparent bottom rows of every object sprite
  (`i<N>`, not characters, flips only) and lowers the draw so the lowest opaque pixel touches the floor (+0.6 px sink), if the gap is <= 10 px (`lift[]`, used by the card layer, the
  models and the shadow pass alike). Token `float` in `props.properties` opts a sprite out (mounted / hanging things).
* **Foot shadows ("floating tree").** The sun shadow of a flat card is geometrically correct (caster and visible card share the same transform and lift; checked with a
  sun at azimuth 140 and at 80 degrees elevation: the shadow starts exactly at the foot), but with the default sun (shadow falls north-north-east) the shadow of a thin trunk
  is hidden behind the trunk itself and only emerges about a tile above / right of the foot, so a dead tree looked detached from its shadow (peter-panning bias is only ~1 sprite pixel).
  `PropLayer` therefore records the foot of every upright card / cross prop (`i<N>` sprites, >= 14 px tall, not `float` / `lie`, token `nofoot` opts out): centre and width of the opaque
  pixels of the lowest 3 rows (wider for tall sprites). `SpriteLayer.drawFoot` draws a soft dark-blue ellipse on the floor there (flat pass, sun shadows on only), stretched along the shadow
  direction, bridging foot and cast shadow; also gives bushes / twigs a grounded shadow. `render3d.footShadow` = alpha (default 0.5, 0 = off; read through the tune file / config).
  **Big gaps**: cards / crosses (`CARD` / `CROSS` rules) may be lowered by up to 26 px (`MAX_GAP_CARD`; other types stay at 10). The big dead trees i23 (14 transparent rows) and i24 (18) were above the old limit of 10 and so were never lowered: the whole trunk hovered above the floor / foot shadow (seen on the snow village and random snow maps).
  The dead tree `i23` (no rule, so it was the default voxel: a thick extruded slab) is now a `card` like `i26` / `i27`.
  Same class, found on the random snow maps (wolf / encounter fields; reach them with a dev hack, `generateRandomMap`): the big leaning dead tree `i24` and the rooted stump `i45` had no rule either (voxel slab: looked tilted and detached from its shadow), now `card`; cattails `i65` / `i66` (frozen rivers) are `cross`. Check new ids with `propdump`: any tree / bush id missing from the table is a voxel.
  **Vegetation moves back AND is lowered (snow camp, area 8; forest trunks).** The transparent rows under a tree (`hb`) are *ground depth* in the 2D art: the trunk stands on roots that are part of the floor tiles, and a tree behind a fence ends above the fence. Only lowering the card by `hb` put the trunk 1 to 2 tiles in front of its roots (the right big dead tree `i27` stood in the fence); only moving it north left the opaque base `hb` px above the ground (trunk hovering over its roots / shadow, owner report x6). For `card` / `cross` / `fencens` sprites `PropLayer.back[i]` (`hb` / tile) moves the sprite north by `hb` (`SpriteLayer`, models, foot shadow) AND `lift[i]` lowers it by `hb` + 0.6 px: the lowest opaque pixel then sits at `y = -0.6 px`, `z = anchor - hb`, which is exactly where the 2D projection draws it (the card's lean shifts z by `y * lean`, zero at the foot). Measured hb: `i23` 14, `i24` 18, `i26` 6, `i27` 4, `i44` 3, `i28` 2, `i36` 2. Other types (chests, voxel props) keep the old lowering.
  **North-south fence (`fencens`, `i36`).** The art shows a fence seen along its length (snow-capped posts stacked down the sprite, a rail strip); a standing voxel card made upright planks. `PropBuilder.fenceNS` rebuilds it from the art as boxes on the floor: one post per snow cap (height from the cap top to the last opaque pixel below it) and two rails along z, colours sampled from the art. A sprite without a detectable cap falls back to the card.
* **Totem skull (`perch:iN`).** The horned skull `i82` on a totem pole (`i70`, standing on a rock `i33`; random snow maps) is a separate object two tiles *north* of the pole in the game data: the oblique 2D art only makes it land near the top of the pole (its anchor is 31 px north of the pole's, the pole is 56 px high). In 3D it stood on the floor behind the pole, low and to one side, like lying on the ground. Token `perch:i70` (`PropLayer.perch`): the draw looks for the nearest `i70` draw up to its height south of it (|dx| <= 16 px) and moves onto that sprite's plane (`back` < 0, 1 px in front) while being raised by the same amount (`lift` < 0), so it keeps its 2D screen position but hangs on the pole; the ground-gap lowering is skipped. A skull without a pole nearby (three lie on the ground in the village) is untouched (voxel, ground contact as before). The same trick fits any other ornament that the data places on another tile than its carrier.
* **Characters** stay billboards (their sheets change every frame; parts of one character must not occlude each other). `render3d.props.characters = true`
  extrudes them thinly (`render3d.props.characterDepth`, 0.12); experimental, off.
* **Fire point lights.** A `light:` token marks fire sprites (torches `i11` / `i42`, camp fire `i12`, fire basket `i48`, fireplace `i67`). The nearest
  `render3d.pointLights.max` (12, <= 16) lights to the camera focus are uploaded as uniforms (`u_lp[16]` position + radius, `u_lc[16]` colour); the fragment
  shader adds `colour * (1 - d/r)^2 * wrapped n.l` to the light term for everything that is lit (floor, structures, props, cards, decals), so it also warms
  the ground in front of walls. Flicker = three sines with a per-light phase (time based, display only). Strength: outdoors x0.7 (subtle in daylight), indoors
  x2.0 and the indoor ambient is lowered to 80 % while fires are in view. No shadows from point lights. World UI (overlay pass) is never lit.
* **Config** (`Config`, tune file): `render3d.props = off | voxel | full` (full, default: voxel + lathe + cross; voxel: lathe becomes voxel, cross stays a card; off: cards
  as before), `render3d.props.characters`, `render3d.props.characterDepth`, `render3d.pointLights = true|false`, `render3d.pointLights.max`, `render3d.pointLights.intensity`.
* **Fallback.** A model that fails to build, a GL error while drawing the props, or a failure of the prepare step disables the props for the session (logged,
  cards from then on); lights are part of the same layer. Dev: `-Dg3.render3d.fpslog=1` prints presented fps every 2 s.
* **Verification.** `scripts/walk-ticks.script`, `--trace-ticks`: `s=` hashes of 2D and 3D identical in all 1141 ticks; `--verify` in 3D: 661/661 alpha-1 frames
  identical, no state changed by in-between frames (nothing here touches game code or state). Performance (hidden 1280x720, vsync off, uncapped, village view of
  screenshot 16 / ~70 models): ~1300 fps with `render3d.props = off`, ~1100 fps `voxel`, ~950 fps `full` (12 lights cost almost nothing).
* **Limits.** Voxel faces of sprites with black outlines get black sides (statues); the extrusion is an extruded picture, not a sculpted model (a barrel from the side
  is the same picture); lathe axis and ellipse are guessed from the silhouette (a sprite that is not round looks odd: use `voxel` / `card` in the table);
  effects / projectiles that live in `images[]` get `default.object` too (add `card` rules if one looks wrong); the table lists the ids of the first village,
  the start house and the temples, other maps use the `voxel:auto` default; lights come from the captured draws only (entities inside the capture area), frozen
  animation frames of far fires show a fixed flame but flicker normally; no light shafts / bloom.

## Tile editor (F7)
In-game tagging of the tile table, built on `TileTable` (package `desktop/.../render3d/editor`). F7 opens it while the 3D view is on (when the view
is 2D, F7 switches 3D on first); F7 / Esc closes it. While it is open all keyboard and mouse input goes to the editor, not to the game (the
game keeps running underneath and is never touched; the F3/F5/F6/F11 keys and the camera keys PAGE_UP/PAGE_DOWN/HOME/brackets still work, the
mouse wheel zooms the camera).

* **Hover**: the tile under the mouse is outlined in yellow (CPU ray against the same quads the mesh uses: floors, standing pieces, slopes, cross
  quads, deck tops at their height, steps / flights on the ground, stairs half way up their rise; transparent texels are skipped, `hidden` tiles are still pickable as a ghost at their upright position). The info panel shows
  cell x/y, layer (0 floor / 1 overlay), tile id, MIDP transform, depth tag + base row (layer 1), the effective shape and where it comes from
  (`table: ts3.layer1.23-34`, `map: ...`, `cell: ...` or `default`).
* **Select**: click a tile (cyan outline; every other use of that tile id on screen is tinted). Tab switches between the layer 0 and layer 1 tile of the selected
  cell, arrow keys move the selected cell, right click / Esc clears the selection.
* **Edit**: `1`..`9` = floor / flat / upright / slope / cross / block / hidden / steps / deck, `0` = stairs, `F` = flight (or the panel buttons, 3 rows + `-` / `+`; the
  panel labels show each kind's key; a kind added to `TileTable.Kind` later gets a colour from the `KIND_COLOR` map and a button, a hotkey only if one is assigned in `kindForKey`);
  `+` / `-` (or Ctrl + wheel, Shift = fine) change the kind's parameter: slope angle (5 deg), block depth (0.25 tiles), deck height (0.25), number of treads of steps / stairs / flight (1);
  `Alt` + `+` / `-` change the rise (second parameter, 0.25) of stairs / flight (saved as `stairs:n:rise`, `flight:n:rise`); `Del` removes the entry of the current scope. **Scope** (`M` or the panel):
  `tileset` (default: the table model, applies to every use of that tile id in the tileset), `this map` (`mapN.layerL.T` override) or `this cell`
  (`mapN.cellL.X.Y`). The mesh is rebuilt immediately. `Ctrl+Z` / `Ctrl+Y` (or Ctrl+Shift+Z) undo / redo for the session.
  If an entry at a stronger level (depth specific rule, map or cell override) shadows the edit, the panel says so.
* **Palette** (`P`): the tileset sheet of the current layer with tile ids, a colour coded badge per shape (green floor, cyan flat, blue upright, orange slope,
  magenta cross, red block, grey hidden, yellow steps, brown deck, light orange stairs, mint flight; a white outline marks entries that are not defaults), yellow frames on the tiles used on this map and dimmed unused
  ones. Click a tile to select it (tileset / map scope), Tab switches the layer, the wheel scrolls tall sheets.
* **Save** (`Ctrl+S`): in a source checkout writes `game/src/main/resources/render3d/tiles.properties`, otherwise `~/.config/gothic3-pc/tiles.properties`
  (loaded on top of the built-in table). Only the edited lines change: a range line that covers the edited tile is split (and joined again if the tile
  is set back to the range's shape), single lines are replaced / removed, new lines go after the last line of that tileset's (or map's) section, or into
  a new `# --- tileset N (tile editor)` section at the end. The panel shows `saved` / `UNSAVED (n)`; closing with unsaved changes first shows a
  warning (F7 / Esc again closes, the edits stay active in memory but are not written). F6 reloads the table from disk and drops unsaved editor edits.
* Test hooks: script command `ed CMD` (`open`, `close`, `mouse X Y`, `click`, `cell L X Y` (aims the mouse at a cell), `select L X Y`, `tile L ID`, `key [ctrl+][shift+][alt+]NAME`,
  `scope tileset|map|cell`, `palette`, `save`, `info`), see `ScriptRunner`.

## Dev tooling
* `--script`: `view 2d|3d`, `reload3d`, `warp AREA X Y` (needs `-Dg3.debug=true`: starts a map load at the next tick; areas 1 = village,
  13 = start house), `repl FILE` (keep executing lines appended to FILE; used for interactive tuning sessions).
* In 3D, script `shot` saves the presented window (backbuffer) instead of the logical frame (which is only the HUD layer). Use `--scale 2`
  for 1280x720 shots. Hidden windows (`--hidden`) render and read back fine here.

## Verification
* `./gradlew build` passes.
* **Gameplay invariance**: `scripts/walk-ticks.script` (1141 ticks: boot, menus, intro dialogue, 661 ticks of free play) with
  `--trace-ticks`: the per-tick state hash `s=` of `--render-view 3d` equals `--render-view 2d` in all 1141 ticks (also tick 0 and the
  `gs=` game state); with `--verify` in 3D: 661/661 alpha-1 frames identical to the canonical frame, no game state field changed by
  in-between frames (same report as in 2D). Only the frame hash `f=` differs (the 3D frame is the transparent HUD layer).
* **Robustness**: map warps into areas 1-19 (outdoor village, temples, arenas, interior, scripted dialogue maps), world map, loading
  screens, dialogue, menus, quickslot ring, door open/close: no exception; non-3D-able screens show the 2D frame. A renderer failure
  falls back to the 2D view (`render3d` logs and disables itself).
* **Performance** (RX 6600 XT, hidden 1280x720 window, village, free play, vsync off, uncapped): 3D presented about 2340 fps; game
  thread frame cost 0.09 ms in 3D (2D: 0.40-0.45 ms: the software rasterisation of the world pass is skipped); tick 10 Hz. Capped at
  1000 fps the 3D and 2D views both hold 1000 fps.
* Visual checks (640x360 logical, window 1280x720; screenshots not included in the repository): village street with sun shadows, houses as blocks, low / high camera, stone tower block sides, start house interior, temple. The block tags of the screenshots (stone 2-10, 35-39, wood walls 12-22, 46, 51-54 of ts3 as `block:1`) came from a scratch table, not from the shipped one. Produced with `scripts/r3d-session.sh`.
* Capture area / surround: `08_wide_view.png` (village, pitch 16, distance 38, entities far away and to the sides), `09_map_edge_surround.png` (south-east corner of the village), `10_interior_edges.png`.
  `--trace-ticks` on a village script (boot, warp to area 1 twice, walking; 830 ticks): `s=` hashes of 3D (default margins), 3D with `captureFeedback` + pitch 14 / distance 40 and 2D are identical in all 830 ticks; `--verify` 496/496 alpha-1 frames identical, no state changed by in-between frames.
  Performance at 1280x720 hidden, vsync off, village: about 1200 fps presented at the default camera, about 1350 at max zoom-out (pitch 8, distance 60); game frame 0.15 ms.


## Limitations (prototype)
* Tile shapes are mostly auto defaults; only the first village's roofs/fences are tagged. Other tilesets need entries (use the tile editor, F7). Without
  tags, layer-1 roofs are upright slabs (readable, but tall), trees/props are upright cut-outs.
* Everything that is not part of a structure (see "Structures") is a cut-out card except `block` volumes; slopes/uprights have no thickness, so from the sides roofs show their
  thin edge. Props and fires: see "Props and point lights". Sprites do not lean/rotate with the camera (camera yaw is fixed, so a fixed upright card is right).
* Draw calls that are not image regions or filled rects (lines, arcs, triangles, `drawRGB`) are dropped in the world pass (the game
  uses them only for a few fx).
* World-anchored UI goes through the same capture, other 2D HUD elements that point at the world (target name bar) are plain 2D.
* The capture area is capped at 48 cells beyond the window (its objects animate on a render-side clock, see Capture area).
* The skirt mirrors floor tiles only (dark tile art can show as mirrored patches); interiors have no skirt (black).
* Dialogue / message box screens are the 2D ones (they are opaque full-screen panels in this game); the 3D scene is only visible
  under the HUD, quickslot ring and translucent UI.

## Phase 2 ideas
* Auto-detection of roofs/walls from depth tags and texture statistics to pre-tag tilesets (the tagging editor and per-map overrides exist now).
* Hand-made prop / building models replacing cut-out cards, thickness for walls (`block` with side faces), proper roof geometry.
* Point lights (fires, torches), day/night colouring, water animation, fog of war at map edges
  (map-edge surround exists now).
* Camera: yaw control, smoother follow.

## Auto tagger (`render3d/autotag`, `tiles-auto.properties`)
`./gradlew :desktop:autotag [--args="--report --merge out/x.properties"]` regenerates `game/src/main/resources/render3d/tiles-auto.properties`
(same syntax as `tiles.properties`, one `tsN.layerL.<id>` line per tile with `# conf=.. reason=..`). Precedence: per-map overrides > hand
(`tiles.properties`) > auto > defaults. Auto lines are always tileset specific, so a hand line of the same key replaces them; a hand line without
`tsN.` (`layer1.5`) would NOT beat an auto line of that tile (tileset-specific lookups come first): write hand tags with `tsN.`.
Offline (no game start): it parses data file `d` (all maps, map 0 = 2D world map skipped) and the tileset sheets, then per tile id accumulates
walkable-bit frequency, layer-1 depth-tag columns (position, column top, single pieces, base/own cell walkable) and pixel features (opaque
coverage, transparent corner region, raggedness, flat-shading, "snow" = light grey fraction, black fraction).
Rules: fully transparent -> `hidden`; layer 1: ragged holey coloured cut-outs low in columns -> `cross`; lone tag-0 pieces on walkable ground ->
`flat`; smooth snow-light tiles (or flat-shaded gable cut-outs) sitting at depth tag >= 1.5 (or column top >= 0.9) -> `slope:55`; black filler
caps stay `upright`; everything else `upright`. Layer 0: `floor`, except almost black tiles hidden under roofs of a tileset that has roofs ->
`floor:<commonest clean ground tile>` (and `tsN.void`). Run with `--report` for the agreement with the hand tags and the confidence histogram.
Test without touching the table: `--merge FILE` writes auto + hand (hand last) and `render3d.tiles = FILE` (config or tune file) points the renderer at it.
Integration in `TileTable.load`: load classpath `render3d/tiles-auto.properties` first, then `tiles.properties` (see the agent report).
Limitations: tile ids that are floor in one cell and wall in another (interior planks) keep one shape per id (`floor`); fences/props that need a
layer-0 `upright` cannot be told from pixels alone (hand tags 27-28 of set 0 are not found); roofs without light/smooth surface (brown shingles) stay upright;
the random level generator is covered through the fixed maps and prefab rooms that use the same tilesets, not generated itself.

### Castle crypt interiors (tileset 4: areas 15-18, 24, 48)
* `render3d.structures.uniformWall` (2.5) / `.uniformWallTilesets` (4): every non-gabled wall structure of these maps is scaled to one height (back walls are 3 art rows, front segments 2, the centre one 3 with its ledge), so the walls of a room agree.
* Props token `height:H` (squash a model to at most H tiles about its feet): `i21@0,0,20,50` (the double door, 3.1 tiles of art) is `height:2.5`, so its top is flush with the walls.
* The plain brick wall between the windows of the big hall (map 48) is drawn on layer 0 (ts4 ids 1-3): `ts4.layer0.1-3 = upright`; a vertical run of layer-0 upright cells in these maps is one wall of `uniformWall` tiles (`MapMesh.l0WallH`), cells that also hold a layer-1 tile are skipped.
* The south wall row (bottom map row of these maps, e.g. map 17 row 16: one art row, the top of the wall with the top halves of the windows, the rest being off the map) was a low sliver with chopped half-arches. `render3d.structures.southWall` (1.2) / `.southWallRows` (2) rebuild it in `Structures.structure` (`south`): the face is the art rows below the window row, taken from the tiles of the same columns (pilaster 4/5/3, brick 1/2/3, window 14/15/16), the last `southWallRows` of them, over `southWall` tiles of height; the cap is brick (not the arch), door jambs (tile 8, half black) become plain brick and the wall's ends show the face art round the corner. Chosen: a LOW wall of plain brick (1.2, 2 rows), because the full 2.5 high wall with whole windows (`southWall = 2.5`, `southWallRows = 3`) hides the room's bottom 1-2 floor rows (props, enemies) from the camera. `southWall = 0` = off.
* The map surround (`MapMesh.buildSurround`) skipped the skirt tile under a wall piece on the edge row (door jamb ground, tile 1) and left a black hole in the skirt next to the doorway; on `uniformWall` tilesets it now continues the nearest floor of that edge row / column.
* South wall with a cap row above its art row (map 24, `Bs == h-1`, levels 2, cap row = top tiles, also across the doorway): the cap row is swallowed (marked covered, not drawn) and the wall is built as the plain south wall above. Skirt cells that mirror an edge-row cell holding a layer-1 tile (door jamb ground, map 15) use the map's commonest floor tile (`MapMesh.commonFloor`), no brown stubs beside the doorway.
* Map 24 doorway of the inner wall (row 7 beam of cap tiles over the door gap, no wall face below): it was a low black slab (`stripHeight` 0.4) 3 rows behind the door with loose edges (thin post, plank, black hole above the door). `Structures.lintel` now draws it as the lintel of the walls beside it: a flat top at `uniformWall` height over the wall thickness plus a 0.3 front lip; the crypt door `i21` is `height:2.4` so its top edge sits inside the wall tops (no black line at 2.5).

## Plant sway and changing snowfall
* **Plant sway** (`atmo.sway`, default on; `atmo.sway.strength`, default 1.0; F10 Atmosphere tab). Trees, bushes and reeds bend in the wind of the snow wind slider
  (`atmo.snow.wind`, same direction as the flakes; wind 0 = still). Which sprites are plants is table driven: the token `sway[:amount]` on a `card` / `cross` rule of
  `props.properties` (shipped: dead trees i23 i24 i26 i27 with 1, bushes i28 i60 i80 and cattails i65 i66 with 3, because they are short). Characters, buildings,
  fences, signs, items and tile geometry never carry the token. Not applied indoors.
* Implementation: `atmo/Sway.java` holds the GLSL (`swayPos`) pasted into the main and the shadow depth vertex shader. Displacement = amplitude * (height above the ground / 3 tiles)^2
  along the wind: the base (world y = 0) is pinned, the trunk barely moves, the crown most; slow sway + second harmonic with a travelling phase from the world position, gusts add lean
  and flutter. Cards with `sway` are cut into horizontal strips (`SpriteLayer`, 2 to 10 per card) so the bend is smooth; the vertex shader then moves whole strips (about 1 texel at
  the crown of a tree in calm wind, the art is not resampled). The shadow depth pass gets the same uniforms (`Atmosphere.swayUniforms`), so shadows follow the plants.
  Per batch uniform `u_swayAmt` (0 for everything else). If the sway shader variant does not compile, the shaders are rebuilt without it (logged), only the effect is lost.
* **Changing snowfall** (`atmo.snow.weather = steady | varying`, default varying; `atmo.snow.weather.period` seconds of one phase, default 90; dev key `atmo.snow.weather.seed` for
  reproducible runs). `SnowWeather` runs a private `java.util.Random` (seeded per session) on the render clock: phases of no snow (20 %), light flurries (30 %, 0.3 x flakes),
  normal snow (35 %) and heavy snow (15 %, 1.7 x flakes), never the same phase twice, phase length period x 0.6 to 1.4, eased transitions of period x 0.35 (4 to 30 s). Flake count,
  opacity and a little size follow the level. Gusts (4 to 9 s bumps every 8 to 30 s, stronger in heavy snow, plus a faint slow breeze) push the flakes (`u_drift`, an integral so
  the flakes never jump) and raise the lean and flutter of the plants. `steady` is the old behaviour exactly (constant 5500 flakes, no drift, no gusts, constant sway).
  Indoor / dust maps are unaffected. Nothing touches game state or the game RNG.
* **Limits**: tile geometry vegetation (map mesh) does not sway; only sprite plants with the token do. Sway is a horizontal shear of strips (no rotation).

## Normal-map lighting ("relief lighting", `render3d/NormalMaps`, `atmo.normals*`)
* The pixel art is read as a height field (luminance, times alpha so cut-out silhouettes round off) and the main fragment shader derives a
  per-texel normal from it: 3x3 Sobel on the texel grid of the texture page, taps at texel centres (nearest, so the bumps follow the pixel grid;
  nothing is stored, no extra textures). Tile sheets clamp the taps to the tile cell (no bleeding between neighbouring tiles). The tangent frame
  comes from screen-space derivatives of the world position and the UVs (works for floors, walls, tilted cards, mirrored sprites).
* The perturbed normal drives the sun term (`ndl`), the point-light term (torches / fires catch the bumps), a faint view-space "relief"
  modulation of the ambient term (so the relief also shows in shade, where the sun term is zero) and a subtle sun glint on bright texels
  with relief. Shadows, the depth pass and the post chain are unchanged.
* Calm areas stay flat: gradients below a small threshold fade out (Sobel also cancels 1 px checkerboard dither), and very bright texels
  (snow, ice) get 0.3x strength, so snow stays soft instead of looking like gravel.
* Config: `atmo.normals` (default true), `atmo.normals.strength` (0.45; F10 Atmosphere tab "Relief lighting" / "Relief strength"). Off = the
  uniform strength is 0 and the branch is skipped: bit-identical image. World UI and the flat (`render3d.lighting=false`) look are never affected.
* Failure: if the shader with the relief code does not compile (needs `dFdx`), the plain shader is used and only this effect is lost (logged).
* Limits: sprite sheets that pack several frames in one image can pick up 1 texel of the neighbouring frame at frame edges; relief is only as
  good as the art's luminance (it is not true depth).
* Map 24 plank doors (`i20`, 2 tiles = 32 px of art) in the lower partition wall (rows 13-15) and the doorways they leave when open: the face of the 2.5 high wall had a hole over the whole door column, so a 0.5 high dark band showed above the door (and a dark hole above an open doorway). `Structures.structure` now fills the top art rows of such a gap column (no face tiles in the column, wall tiles on both sides, uniformWall tilesets) with the neighbouring wall art, a brick lintel flush with the face; `render3d.structures.doorLintel` (1, 0 = off).
* OPEN doors on map 24 (object state bits set; `objstate AREA HEX` script command, e.g. `objstate 24 FFFFFFFF` before the warp): the open frames of the crypt double door (`i21` frame at x=40) and of the plank doors (`i20` frame at x=56) are voxels of a thin post + top beam that stood recessed in the gap (low frame, loose posts, dark hole). New prop token `hide` (not drawn in 3D) is set for those two frames: the walls beside the gap are the jambs, the cap beam is the flush lintel (`Structures.lintel`) / brick lintel (`doorLintel`). The half-open frame of the double door (`i21` x=20) gets `height:2.4` like the closed one.

## Reflective ice (`render3d/IceLayer`, `atmo.ice`, `ice` lines of `tiles.properties`)
The game has no open water, only ice: tile 6 of layer-0 tileset 0 is the frozen sea / lake / pond (maps 42 "coast" and 47 pond; tiles 30-33 are its shore tiles, half snow, half ice).
Cosmetic and render only (no game state, no game RNG, wall-clock shimmer); the ice reflects, snow, stone and UI never do. Screenshots are not in the repository.

* **Table**: lines in `game/src/main/resources/render3d/tiles.properties` (F6 reloads): `ice = ids` (any tileset), `ice.tsN = ids` (layer-0 tileset N), `ice.mapN = ids`; ids are layer-0 tile ids, ranges allowed.
  Shipped: `ice.ts0 = 6, 30-33`. The shape table and the tile editor ignore / keep these lines. Per pixel the ice is told from snow by the tile art itself (dark, blue texels), so shore tiles only reflect on their ice part.
  Cells that are decks / stairs or re-mapped (`floor:N`) are skipped. The map surround (`render3d.surround`) continues the ice with the same fade.
* **Pass 1, mirrored scene** (`IceLayer.reflect`): everything that stands (layer-1 pieces, structures, prop models, upright cards: the same draw calls as the main pass) is drawn mirrored about the ground plane (`projView * scale(1,-1,1)`,
  frustum culling with the mirrored frustum) into a half-resolution RGBA target (own depth), premultiplied colour + alpha. The main shader fades the alpha with the height above the ground (`u_refl`, one line, 0 = off) so
  the reflection is strongest at the object's feet. No depth texture / GL3 needed; skipped when the ice is not on screen.
* **Pass 2, composite** (`IceLayer.composite`): the ice cells are drawn again right after the opaque world (so everything standing in front still covers them, decals and sprites come later) with a small shader: 9-tap blur of the
  target at the pixel's screen position (a little more vertical), refracted by the ice tile's own pattern, weighted by a Fresnel-ish term from the view angle (more reflective towards the horizon), plus a cool sky sheen where nothing is reflected,
  a moving sun glint path (azimuth / elevation of the reflected view ray against the sun, visible when the sun is in front of the camera and not too high; follows `render3d.sun.*` and `atmo.tod`), glints of the nearest 4 point lights (fires) and a few ice
  crystal sparkles. The skirt ice is composited after the skirt decal (it is a decal).
* **Config** (F10 Atmosphere tab, live): `atmo.ice` (default true), `atmo.ice.strength` (0..2, default 1). Off, `atmo.enabled=false`, indoor maps, `render3d.lighting=false`: nothing is drawn, no GL state touched, the image is bit-for-bit the old one.
* **Fallback**: a shader / GL error in either pass disables the ice for the session (logged), the rest keeps working.
* **Limits**: planar reflection at y = 0 (ice under a raised plateau is skipped); the reflection is half resolution and blurred, characters standing in front of the ice get no reflection on the part of the ice they hide; sun shadows are not
  in the reflection (specular only); reflections are not occluded by things between the mirrored object and the ice other than what the mirrored pass draws; time-of-day tints the sheen through the scene light level. Only tileset 0 is tagged: other tilesets
  have no ice in the shipped maps.
* **Verification**: `walk-ticks.script` trace md5 unchanged (29e2f334..., full de5fa4e2...), `--view3d --verify` all alpha-1 frames identical with the ice on.

## Snow sparkle and sheen (`render3d/SnowSparkle`, `atmo.snowSparkle*`, `atmo.snowSheen*`, `snow` lines of `tiles.properties`)
Sunlit snow glitters and has a soft gloss. Cosmetic and render only (no game state, no game RNG, no clock: the effect is static); implemented in the main fragment shader, so it
survives the post chain. Screenshots are not in the repository.

* **Which surfaces**: table lines in `game/src/main/resources/render3d/tiles.properties` (F6 reloads): `snow = ids` (layer-0 tile ids, any tileset), `snow.tsN`, `snow.mapN`;
  `snow1`, `snow1.tsN`, `snow1.mapN` for layer-1 tiles (snowy roofs and caps); `snow.structures = true|false` for the merged house / wall atlas (roofs are baked into it, so it has
  no tile ids and is judged per texel only). Shipped: layer 0 tileset 0 tiles 0-2, 7-12, 15-26 and the snow part of the shore tiles 30-33, layer 1 tileset 3 the roof / cap tiles.
  The tile ids become a one-texel-per-tile mask texture of the tile sheet (unit 3, built when the map changes). Inside those tiles the art decides per texel (bright and low
  saturation: footprints, cobbles, grass, stone and the blue ice of the shore tiles stay matt). The shape table ignores `snow*` lines.
* **Sparkle** (`atmo.snowSparkle`, default true; `atmo.snowSparkle.strength`, default 1 = subtle, 2 = a bit more visible, 0..2): a stable hash grid in texel space with static random facet
  normals around the surface normal (flat ground, sloped roofs). No time term at all: glints change only when the camera or the sun direction moves (the glitter path), and the pattern
  sticks to the ground. The cell size is about 1.3 screen pixels at the camera focus, in power of two steps of the camera distance (`u_snK.w` = pixels per world unit, from the projection and
  the camera height; texels per world unit come from the surface derivatives), so it does not depend on the screen position and only re-rolls when the zoom step changes. A cell lights when
  the half vector of sun and view lines up with its facet within about 4 degrees. A lit cell is a single pixel dot, white or very slightly warm / cool, modest brightness, almost no
  extra light for the bloom. Strictly sunlight dependent: only on fully sunlit snow (shadow term above 0.92: zero in cast shadows, penumbra and on back faces), only in daylight with the sun well
  above the horizon (sun elevation above about 15 degrees fades in, full above about 30; zero at dusk, morning / evening presets and night), damped by snowfall (`Atmosphere.snowLevel()`: 1 clear, 0.7 normal, 0.4 heavy). No fire / torch glints. Expect about 0.05 % of the sunlit snow pixels as bright glints (the old effect: 0.3-0.45 %); the tilt-shift blur softens the ones in the blurred bands.
* **Sheen** (`atmo.snowSheen`, default true; `atmo.snowSheen.strength`, default 1, 0..2): a subtle wide specular lobe (half vector power 6 on the relief-perturbed normal) towards the sun (daylight only, zero in shadow),
  warmer with a low sun, plus a very faint cool sky sheen at grazing view angles. Snow stays matt snow, not ice: no mirror.
* **Reflection**: the planar reflection of the ice (`IceLayer`) is not used on snow. It needs the mirrored scene pass on every snow map (about the full cost of the ice) for
  a 10-20 % blurred ghost that read as wet snow; the sheen is the cheap stand-in.
* **Switching off**: both off, `atmo.enabled=false`, indoor maps, `render3d.lighting=false` or the relief shader variant missing (the code needs `dFdx` and the relief uniforms): the
  branch is skipped, bit-identical image. F10 Atmosphere tab: "Snow sparkle", "Sparkle strength", "Snow sheen", "Sheen strength" (live).
* **Fallback**: if the shader with the snow code does not compile, the relief-only shader is used and only this effect is lost (logged).
* **Limits**: sprites (characters, trees) and prop models never sparkle (map mesh only); the reflection pass and the shadow depth pass are skipped; houses and walls from the structure atlas
  are judged per texel only, so light stone texels can pass the snow test; snow baked into the atlas of other tilesets is not tagged (only tileset 0 / 3 maps use snow tiles in the shipped maps).

## Extra light sources (`props/LightSources`, `render3d/lights.properties`, `atmo.lights*`)
Besides the fire sprites (props.properties `light:` token) the 3D view lights wall lamps, candles and windows. Table driven, cosmetic only (render clock, no game state, no game RNG).
* **Table** `game/src/main/resources/render3d/lights.properties` (F6 reloads): `preset.NAME = RRGGBB:radius:intensity:flicker`; tile lights `tsN.TILE = preset:fx:fy:dz` (layer-1 tile id of tileset N,
  `fx` / `fy` = position inside the tile with y from the top, `dz` = tiles towards the room; the height is the tile's standing height + `1 - fy`, found with the same column walk as `MapMesh`);
  sprite lights `iN[@x,y,w,h] = preset:px:py:height ...` (pixel in the sprite, height in tiles; floor-pass sprites work too); window colours `win.bright`, `win.bright.night`, `win.dark.night`.
  Shipped: the glowing wall lamp tiles (ts4 ids 7, 27 = crypt / abbey, ts5 id 16 = house interiors; the glow is drawn over two stacked tiles, the light sits at their junction), stained glass (ts4 15),
  the temple altar candles (`i196`, two flames), windows of `atmosphere.properties` (interior bright windows: cool by day, warm at night; exterior dark windows: warm, only at night, scaled by the time-of-day night factor).
* **Selection.** `LightSources.select` merges the fire lights that `PropLayer` collected with the extra ones, picks the nearest `render3d.pointLights.max + 8` (at most 24, `MAX_LIGHTS`, shader arrays are 24 now)
  to the camera focus and fades every light in / out (0.45 s, smoothstep; lights present when a map loads appear at once) so a changing selection does not pop. Flicker per source: fires as before,
  lamps 0.45, candles 0.8, windows steady, times `atmo.lights.flicker`. Embers, smoke and dust brightening only follow fire lights (`PropLayer.emberCount`; the fire lights are first in the arrays).
* **Config**: `atmo.lights` (default true), `atmo.lights.strength` (1, scales the extra lights, fires stay as before), `atmo.lights.flicker` (1, 0 = steady; also applies to fires when changed), F10 Atmosphere tab. Dev key `atmo.lights.fade` (seconds).
  `-Dg3.render3d.lightlog=true` logs the selection. `atmo.lights = false` (or `atmo.enabled = false`) is bit-for-bit the previous image (the old selection code runs).
* **Limits**: lamp heights follow the tile's standing height, so walls squashed by `uniformWall` / `capWall` can put a light slightly off; no shadows from point lights; lights come from captured draws / tiles near the camera only;
  no halo sprite at the flames (the lamp art is brightened by its own light instead); tables lying on candles of other maps need an `iN` line in the table.

## Performance and graphics quality presets (`GraphicsPresets`, `render3d/presets.properties`, `Bench`)
**Measuring.** Earlier fps numbers were capped: with vsync off and `render.maxFps = 0` the window is capped at the display refresh (200 Hz here), with vsync on at 60, and
`opt set window.vsync false` re-applies that cap. Presented fps also depends on the compositor for hidden windows. The dev script command `bench N [label]` (`Bench.java`;
(any scripted 3D run) instead renders the scene 6 times per presented frame, each followed by `glFinish`, and records the last 5 of every batch: avg / p50 / p95 ms of the whole 3D frame
(CPU + GPU, serialized, post chain included, HUD excluded). It never touches game logic or the 10 Hz timing (determinism gates unchanged). Use `bench 120 warm` first so the GPU clocks ramp up.
Setup of the numbers below: AMD RX 6600 XT (Mesa), hidden window, 1280x720 and 1920x1080 backbuffer, deterministic walk intro then `warp`; 3 (720p) / 2 (1080p) interleaved rounds, median
of the per-round p50; other agents shared the GPU, so deltas below ~0.15 ms are noise. Effects are toggled one at a time from the defaults (= High).

Frame cost, p50 ms (fps = 1000 / ms), defaults and bounds:

| scene | 720p all off | 720p defaults | 720p everything on | 1080p all off | 1080p defaults | 1080p everything on |
|---|---|---|---|---|---|---|
| Initria (map 2, lights) | 2.17 | 2.60-2.93 | 3.04 | 2.49 | 3.9 | 3.97 |
| Khorinis (1, snow, trees) | 2.29 | 3.1-3.2 | 3.28 | 2.93 | 4.3 | 4.40 |
| Abbey inside (24, lamps) | 1.20 | 2.2 | 2.22 | 1.59 | 3.15 | 3.12 |
| Coast (42, ice) | 1.02 | 2.25 | 2.31 | 1.32 | 3.2 | 3.04 |
| Pond (47, ice) | 1.01 | 2.2 | 2.26 | 1.33 | 3.2 | 3.20 |
| Crypt (17) | 0.98 | 2.2 | 2.25 | 1.32 | 3.1 | 3.09 |

"All off" = `atmo.enabled=false`, `post.preset=off`, shadows off, point lights off. "Everything on" = defaults plus smoke, mist, contact shadows, double snow / dust, 24 lights.
Cost of each effect, ms added to the defaults (delta of turning it off, negative = cheaper without it; "+" for the three that default to off), median over the six scenes at 1080p (720p in brackets):

| effect | ms | note |
|---|---|---|
| post chain (DOF + bloom + SSAO + tone / grade) | 0.85-1.3 (0.5-0.85) | the biggest block; DOF and SSAO ~0.25-0.3 each at 1080p, bloom ~0.15 |
| atmosphere master (all particles, shafts, rim, ice, normals) | 0.2-0.55 (0.1-0.4) | |
| reflective ice pass | 0.3-0.35 on ice maps (0.25-0.3), ~0 elsewhere | extra scene pass, only where ice tiles are visible |
| relief lighting (normals) | 0.2-0.4 (0.15-0.3) | per-fragment, grows with resolution |
| soft shadow filter / contact hardening | 0.05-0.15 / 0.05-0.35 (Initria) | |
| shadows off / low vs high | ~0 on this GPU | two cheap passes; they cost fill rate on weak GPUs |
| point lights (12) / extra lamps | 0.1-0.5 in Khorinis / Initria, ~0 elsewhere | grows with the light count in the fragment loop |
| rim, shafts, sway, snow, embers, dust, smoke, mist | each below ~0.15 (up to 0.3-0.5 in the busiest outdoor scenes) | within noise |

The resolution matters more than any single effect: all fill-rate bound items (post, normals, ice) roughly double their cost from 720p to 1080p; a weak or integrated GPU (4-10x slower
than this card) turns the 3-4 ms of the defaults into 15-40 ms, so Low removes the post chain and every per-fragment extra.

**Presets** (F10 > 3D or Atmosphere > Graphics quality; one data table, `game/src/main/resources/render3d/presets.properties`, one row per key, one column per preset, `-` = not part
of the preset). The label is never stored: `GraphicsPresets.current` compares the config with each column, so existing configs that match none show Custom and nothing is overwritten
on upgrade, and changing any single setting afterwards shows Custom (settings stay editable). Picking a preset resets the `post.*` sliders to the "subtle" baseline and writes the column.
A key that has no default in `Config.java` (an effect that is not in this build, e.g. `atmo.ao`, `atmo.snowSparkle`, `atmo.snowSheen` until merged) is skipped; add a new effect with
one line. The High column must equal the built-in defaults (a new config shows High and looks as before; `-Dg3.debug=true` logs `[presets] High column differs ...` otherwise).
Script: `opt set gfx.quality low|medium|high|ultra`, `opt get gfx.quality`.

| setting | Low | Medium | High (default) | Ultra |
|---|---|---|---|---|
| sun shadows `render3d.shadows` | low | low | high | high |
| soft shadow filter / contact hardening | off / off | on / off | on / off | on / on |
| point lights max | 6 | 10 | 12 | 20 |
| lamps, candles, windows `atmo.lights` | off | on | on | on |
| relief lighting `atmo.normals` | off | on | on | on |
| reflective ice `atmo.ice` | off | on | on | on |
| rim light | off | on | on | on |
| light shafts | off | off | on | on |
| plant sway | off | on | on | on |
| snow / ember / dust density | 0.5 / 0.5 / 0.5 | 0.8 / 0.8 / 0.8 | 1 / 1 / 1 | 1.6 / 1 / 1.5 |
| smoke | off | off | off | on |
| post-processing | off | subtle without DOF and SSAO | subtle (bloom, DOF, SSAO, grade) | subtle |
| `atmo.ao` (baked, when merged) | on | on | on | on |
| `atmo.snowSparkle`, `atmo.snowSheen` (when merged) | off | off | on | on |

Not part of any preset (left as the user has them): mist, time of day, camera, lighting strengths, tint, props mode, map surround.

Preset frame cost, p50 ms at 1280x720 on the RX 6600 XT (same method; fps in brackets):

| scene | Low | Medium | High | Ultra |
|---|---|---|---|---|
| Initria | 1.84 (543) | 2.48 (404) | 3.02 (331) | 3.93 (254) |
| Abbey inside | 0.94 (1064) | 2.06 (485) | 2.10 (476) | 2.23 (448) |
| Khorinis | 2.03 (493) | 3.36 (298) | 3.31 (302) | 3.69 (271) |
| Coast (ice) | 0.84 (1190) | 1.96 (510) | 2.33 (429) | 2.33 (429) |

Low is roughly 1.5-2.5x cheaper than High here and, more to the point, drops everything per-fragment and the whole HDR chain; it keeps the sun shadows (low), props, snow, fire lights and the
plain lighting, so it still reads as the same diorama (compare with Medium / High / Ultra crops: no relief, no bloom, no AO, fewer particles).
