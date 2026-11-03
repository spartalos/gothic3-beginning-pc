# HD-2D post-processing (3D view) and 2D filters

Octopath-style "photographed diorama" look on top of the 3D renderer, plus optional filters for the original 2D view. Everything is render-side only: no game code or
state is touched (`--trace-ticks` hashes are identical with the chain on, see Verification). Code: `desktop/.../render3d/post/` (chain, passes, shaders, settings),
`desktop/.../post2d/PostChain2D`, options tab `desktop/.../options/EffectsTab`, hooks marked `POST` in `Renderer3D` and `G3App`.
(Screenshots are not included in the repository: they show the original art.)

## Pipeline (3D view)
```
Renderer3D.render:  PostChain3D.begin  -> scene target (RGBA16F + depth texture), viewport (0,0,w,h)
                    shadow pass / world / props / sprites / atmosphere / world UI   (unchanged shaders, one extra uniform u_outScale)
                    + depth patch: sprites and the world UI are added to the depth buffer (colour masked), they have no depth write otherwise
                    PostChain3D.end    -> SSAO -> resolve -> DOF -> bloom -> final pass into the window region
G3App:              HUD / menus / dialogue are drawn afterwards with the SpriteBatch (never post-processed); F7 editor and F10 options too
```
1. **SSAO** (half res, depth only): linear depth is reconstructed from the depth texture, normals from depth derivatives, 12 cosine-hemisphere samples rotated per pixel
   (interleaved gradient noise), range check, then a depth-aware separable blur (7 taps) and a depth-aware (bilateral) upsample. Applied as a multiplier on the lit
   colour (the game has no separate ambient buffer); pixels brighter than 1.0 (fires, emissive) are exempt. `post.ssao.strength` / `.radius` (tiles).
2. **Depth of field** (half res). Circle of confusion per pixel, signed (far +, near -):
   * `depth` mode: from view depth relative to the player's feet (`post.dof.focus` offset, sharp band `post.dof.band` tiles, smoothstep ramp);
   * `tiltshift` mode: from screen height (`post.dof.tsCenter`, `post.dof.tsBand`, ramp 0.28 of the screen), the classic miniature look.
   Prefilter (Karis weighted 2x2 + CoC) -> 32 tap golden-angle disc **scatter-as-gather** blur: a sample only spreads onto pixels inside its own CoC, weights fall with
   1/CoC^2, far samples never leak onto nearer (sharper) pixels, near samples spread over everything behind them. The full resolution composite mixes the sharp pixel
   with the blurred one using a 4 tap bilateral upsample keyed on the CoC, so sharp sprites in front of a blurred background keep clean edges (no halo).
   `post.dof.blur` = max radius in pixels at 1080p (scaled with the window height).
3. **Bloom** (`Bloom`): soft-knee bright pass on linear light (max channel, Karis weighted), dual-filter downsample chain (up to 6 levels) and additive upsample chain.
   `post.bloom.intensity`, `.threshold` (linear light: sunlit snow is about 1.0, fires and point lit areas exceed it; default 1.0 / HD-2D 1.25 so only fire, torches and
   lit windows glow), `.radius` (weight of the deeper levels).
4. **Final pass** (one shader, straight into the backbuffer): bloom add, exposure and white balance (temperature / tint), blend between clamp and an ACES-like curve
   (`post.tonemap` 0..1), display gamma, lift / gamma / gain of the grade preset, contrast, saturation, optional 3D LUT, then sharpen (CAS-like, 4 neighbours),
   FXAA-lite (luma edge blend, off by default), chromatic aberration (radial, edges only), vignette, animated film grain (2 px cells, 24 steps per second).
5. The scene is drawn with the unchanged lighting shaders; the only edit is `u_outScale` (1, or 0.5 in the RGBA8 fallback).

## Presets (`post.preset`)
| | Off | Subtle (default) | HD-2D |
|---|---|---|---|
| chain | not used, scene goes straight to the screen (old look, no cost) | | |
| DOF | - | depth mode, blur 3, band 8 | tilt-shift, blur 12, band 0.30 at 0.47 |
| bloom | - | 0.25 / threshold 1.0 | 0.55 / threshold 1.25 / radius 0.7 |
| SSAO | - | 0.35 radius 0.8 | 0.6 radius 1.0 |
| tone / exposure / contrast / sat / temp | - | 0.5 / 1.0 / 1.04 / 1.06 / 0.05 | 0.7 / 1.0 / 1.12 / 1.2 / 0.15, grade Octopath warm |
| vignette / grain / CA / sharpen | - | 0.2 / 0.012 / 0 / 0.15 | 0.42 / 0.03 / 0.15 / 0.3 |
A preset writes all `post.*` keys; changing any of them (options screen) sets `post.preset = custom`. Hand edited configs keep working: the individual keys are what counts,
`post.preset = off` forces everything off.

## Config keys (defaults = Subtle)
`post.preset`, `post.dof` (bool), `post.dof.mode` (depth | tiltshift), `post.dof.focus`, `post.dof.band`, `post.dof.tsCenter`, `post.dof.tsBand`, `post.dof.blur`,
`post.bloom`, `post.bloom.intensity|threshold|radius`, `post.ssao`, `post.ssao.strength|radius`, `post.tonemap`, `post.exposure`, `post.contrast`, `post.saturation`,
`post.temperature`, `post.tint`, `post.grade` (none | octopath | snow | night | lut), `post.grade.amount`, `post.lut`, `post.vignette`, `post.grain`, `post.ca`,
`post.sharpen`, `post.fxaa`.
2D (all off by default): `post2d.upscaler` (nearest | sharp | epx), `post2d.bloom`, `.bloom.intensity|threshold`, `post2d.grade` (none | octopath | snow | night),
`post2d.saturation`, `post2d.contrast`, `post2d.vignette`, `post2d.grain`, `post2d.crt`, `post2d.crt.scan|mask|curve`.
All are in the F10 "Effects" tab and apply live (the chain keeps rendering while the game is paused behind the overlay).

**Grades**: Octopath warm (warm highlights, cool lifted shadows), Cold snow, Night, or `lut`: a 16x16x16 colour cube as a 256x16 PNG strip (16 blue slices left to
right, red along x, green down, top = 0). Built in: `warm`, `cool`, `film` (`game/src/main/resources/post/luts/`); drop `NAME.png` into `~/.config/gothic3-pc/luts/`
and set `post.lut = NAME` (the options list shows the built in names).

## 2D view (`PostChain2D`)
Used only when something in `post2d.*` is on (otherwise the plain nearest blit as before) and whenever the frame is not a 3D frame. The logical frame texture is
sampled by the final shader directly: **sharp** = sharp bilinear (blend only across one window pixel at texel borders), **epx** = EPX corner smoothing (per sub-pixel, 4
neighbour compare). Bloom, grade, vignette, grain and the **CRT** filter (scanline per logical row, RGB shadow mask, slight barrel curvature) run in the same pass. The
HUD is part of the 2D frame, so it is filtered too.

## Fallbacks
* No RGBA16F render target: RGBA8 targets with a tone-map-friendly encoding (`-Dg3.post.rgba8=true` forces it): the scene is written as c/2 and the working
  buffers hold c/(1+c). Particles of the atmosphere pass are not scaled there (slightly brighter), bloom is weaker above 2.0.
* No depth texture (`-Dg3.post.nodepthtex=true` forces a depth renderbuffer): SSAO is off, DOF uses the tilt-shift mode only.
* A shader that fails to compile disables just its effect for the session (log `[post] shader 'x' failed`); an exception in the chain disables the chain
  (`[post] chain disabled`, the scene is drawn directly from then on); the renderer never falls back to 2D because of post.
* `Renderer3D.post()` exposes `sceneFbo()`, `sceneColorTex()`, `sceneDepthTex()` (valid while the chain is active) for effects that want the scene depth.

## Verification and performance
* `./gradlew build` passes. `--trace-ticks` with `scripts/walk-ticks.script`: the 1141 `s=` hashes of 2D and 3D (Subtle preset active) are identical; `--verify` in 3D: 589/589 alpha-1 frames identical, no state field changed by in-between frames.
* Performance (RX 6600 XT, hidden window, vsync off, village, distance 26, other GPU work on the machine at the same time): 1920x1080 about 950 fps off, 580 Subtle,
  547 HD-2D; 1280x720 about 1000 (capped at the 1000 fps limit) off, 600-630 Subtle, 710-750 HD-2D (noisy: shared GPU). Everything stays far above 144 fps.

## Limitations
* The world is lit in display (gamma) space; the chain converts to linear for bloom and grading, so "HDR" only exists where lights add above 1.0.
* SSAO is multiplied onto the lit result (no ambient-only buffer) and is subtle on bright snow. Sprite cards write depth through the extra depth patch, so DOF / AO see
  them as vertical cards at their feet depth.
* DOF depth mode on a camera with large pitch changes does not follow the focus point exactly (it uses the player's feet depth).
* EPX is the only edge-aware upscaler (no xBR / ScaleFX); CRT curvature distorts the HUD of the 2D frame together with the picture.
