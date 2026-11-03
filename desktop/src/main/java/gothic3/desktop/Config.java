package gothic3.desktop;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * User configuration: ~/.config/gothic3-pc/config.properties (XDG_CONFIG_HOME aware).
 * Created with defaults on first run. Missing keys fall back to defaults.
 */
public final class Config {
    public final Properties props = new Properties();
    public final File file;

    private static final String[][] DEFAULTS = {
        // screen.preset: logical resolution. wide = 640x360 (default), tight = 480x270, classic = 240x320 (original), or WxH.
        // screen.width / screen.height (both set) override the preset. Display is always integer nearest-neighbour scaled.
        {"screen.preset", "wide"},
        // window.scale: initial window size = logical size * scale. auto = largest integer scale that fits ~90% of the display.
        {"window.scale", "auto"},
        {"window.fullscreen", "false"},
        {"window.vsync", "true"},
        // render.mode: interpolated = logic stays at the original 10 Hz, frames are drawn at display rate with interpolated motion;
        //              classic      = original behaviour (one frame per tick, 10 fps)
        // render.maxFps: 0 = follow the display (vsync); N = cap/pace at N frames per second
        {"render.mode", "interpolated"},
        {"render.maxFps", "0"},
        // render.view: 2d = the original tile view; 3d = Pokemon3D-style diorama (tilted perspective camera). F5 toggles at runtime.
        // render3d.*: pitch (deg above horizontal), fov (vertical deg), distance (camera to look-at point, tiles), focusOffset (tiles the player sits below screen centre), lean (deg), vscale, fog,
        //             tiles (path of the tile->shape table, F6 reloads it)
        {"render.view", "2d"},
        {"render3d.pitch", "38"},
        {"render3d.fov", "35"},
        {"render3d.distance", "26"},
        {"render3d.focusOffset", "0"},
        {"render3d.lean", "0"},
        {"render3d.vscale", "1"},
        {"render3d.iconLift", "0.3"},     // overhead icons (interaction bubble): extra height in tiles above the 2D position over the head
        {"render3d.iconScale", "1.2"},    // ... and their size factor
        {"render3d.fog", "true"},
        {"render3d.occFade", "true"},     // 3D: things in front of the hero (trees, walls, roofs) dither out around him; .min = remaining opacity (0 = fully gone, 1 = off)
        {"render3d.occFade.min", "0.22"},
        {"render3d.tiles", ""},
        // structures: merged houses / roofs / walls built from the 2D building art (false = old per-tile upright / slope pieces); tunables render3d.structures.stripHeight / roofDepth / capWall / wallDepth
        {"render3d.structures", "true"},
        // --- render3d lighting (3D view): lighting = on|off (sun + ambient, per-face shading); shadows = off|low|high (sun shadow map);
        //     sun.azimuth = compass direction of the sun in degrees (0 north, 90 east, 180 south), sun.elevation above the horizon, sun.strength / ambient = light levels;
        //     indoor.tilesets = layer tileset ids that are interiors (warm ambient light, no sun / shadows)
        {"render3d.lighting", "true"},
        {"render3d.shadows", "high"},
        {"render3d.sun.azimuth", "205"},
        {"render3d.sun.elevation", "48"},
        {"render3d.sun.strength", "0.62"},
        {"render3d.ambient", "0.52"},
        {"render3d.indoor.tilesets", "5"},
        {"render3d.cave.tilesets", "7"},       // cave tilesets: lit as interiors (black surround, no sun), rock walls built as low ramps (docs/render3d.md "Caves"); empty = off
        {"render3d.blobShadows", "false"},
        // props (3D models for object sprites): props = off | voxel (extruded sprites only) | full (voxel + lathe + crossed cards, see render3d/props.properties);
        //   props.characters = also extrude characters / monsters (thin), props.characterDepth in tiles;
        //   pointLights = warm flickering point lights for fires (braziers, torches, fireplaces), .max = how many (nearest, <= 16), .intensity = multiplier
        {"render3d.props", "full"},
        {"render3d.props.characters", "false"},
        {"render3d.props.characterDepth", "0.12"},
        {"render3d.pointLights", "true"},
        {"render3d.pointLights.max", "12"},
        {"render3d.pointLights.intensity", "1.0"},
        // map surround: ground skirt of mirrored edge tiles fading into the background colour (auto = map's ground colour, dark; interiors black), also the fog colour
        {"render3d.surround", "true"},
        {"render3d.surround.tiles", "8"},
        {"render3d.surround.color", "auto"},   // auto or RRGGBB
        // entity capture area: west,north,east,south margin (cells) around the game's 2D window used in deterministic runs (traces); otherwise the 3D frustum footprint is used
        {"render3d.capture.margin", "8,16,8,8"},   // keep the game's blob shadow decals while real shadows are on
        // --- post-processing (docs/post.md). post.* = 3D view, post2d.* = 2D view (default: all off = original look).
        //     post.preset = off | subtle | hd2d | custom (a preset writes all post.* keys below; touching one switches to custom)
        {"post.preset", "subtle"},
        {"post.dof", "true"},               // depth of field; post.dof.mode = depth | tiltshift
        {"post.dof.mode", "depth"},
        {"post.dof.focus", "0"},            // depth mode: focus distance offset from the player, tiles
        {"post.dof.band", "8"},             // depth mode: width of the sharp band, tiles
        {"post.dof.tsCenter", "0.5"},       // tiltshift mode: screen height of the sharp band centre (0 bottom .. 1 top)
        {"post.dof.tsBand", "0.35"},        // tiltshift mode: sharp band height, fraction of the screen
        {"post.dof.blur", "3"},             // max blur radius, pixels at 1080p
        {"post.bloom", "true"},
        {"post.bloom.intensity", "0.25"},
        {"post.bloom.threshold", "1.0"},    // linear light, max channel
        {"post.bloom.radius", "0.5"},
        {"post.ssao", "true"},
        {"post.ssao.strength", "0.35"},
        {"post.ssao.radius", "0.8"},        // tiles
        {"post.tonemap", "0.5"},            // 0 = off .. 1 = full ACES-like curve
        {"post.exposure", "1.0"},
        {"post.contrast", "1.04"},
        {"post.saturation", "1.06"},
        {"post.temperature", "0.05"},       // -1 cold .. +1 warm
        {"post.tint", "0"},                 // -1 green .. +1 magenta
        {"post.grade", "none"},             // none | octopath | snow | night | lut
        {"post.grade.amount", "1.0"},
        {"post.lut", "warm"},               // LUT name for grade = lut (16x16x16 strip PNG, ~/.config/gothic3-pc/luts/NAME.png or built-in)
        {"post.vignette", "0.2"},
        {"post.grain", "0.012"},
        {"post.ca", "0"},                   // chromatic aberration (edges)
        {"post.sharpen", "0.15"},
        {"post.fxaa", "false"},
        {"post2d.bloom", "false"},
        {"post2d.bloom.intensity", "0.3"},
        {"post2d.bloom.threshold", "0.7"},
        {"post2d.grade", "none"},           // none | octopath | snow | night
        {"post2d.saturation", "1.0"},
        {"post2d.contrast", "1.0"},
        {"post2d.vignette", "0"},
        {"post2d.grain", "0"},
        {"post2d.crt", "false"},
        {"post2d.crt.scan", "0.45"},
        {"post2d.crt.mask", "0.15"},
        {"post2d.crt.curve", "0.05"},
        {"post2d.upscaler", "nearest"},     // nearest | sharp (sharp bilinear) | epx (corner smoothing)
        // --- atmosphere (3D view, docs/render3d.md "Atmosphere"): cosmetic in-scene effects, data table render3d/atmosphere.properties (weather per map / tileset, window tiles)
        {"atmo.enabled", "true"},
        {"atmo.snow", "true"},                // falling snow on maps whose weather is snow
        {"atmo.snow.density", "1.0"},         // 0..2 (x 5500 flakes)
        {"atmo.snow.size", "1.0"},
        {"atmo.snow.wind", "0.5"},            // drift, also tilts embers / smoke
        {"atmo.snow.weather", "varying"},     // steady | varying (periods of no / light / normal / heavy snow with wind gusts; render clock only)
        {"atmo.snow.weather.period", "90"},   // seconds of one weather phase (varying)
        {"atmo.sway", "true"},                // trees, bushes and reeds bend in the wind (plants named in props.properties by `sway`)
        {"atmo.sway.strength", "1.0"},
        {"atmo.embers", "true"},              // rising embers over fires (point lights)
        {"atmo.embers.density", "1.0"},
        {"atmo.embers.size", "1.0"},
        {"atmo.smoke", "false"},              // faint smoke wisps over fires
        {"atmo.smoke.density", "1.0"},
        {"atmo.dust", "true"},                // floating dust motes on maps whose weather is dust (interiors)
        {"atmo.dust.density", "1.0"},
        {"atmo.shafts", "true"},              // light shafts: window beams indoors, sun beams outdoors
        {"atmo.shafts.strength", "0.5"},
        {"atmo.rim", "true"},                 // thin rim light on the shaded edge of characters
        {"atmo.rim.strength", "0.5"},
        {"atmo.ao", "true"},                  // baked ambient occlusion: soft contact darkening at wall bases, corners, plateaus (ambient term only)
        {"atmo.ao.strength", "0.5"},
        {"atmo.ao.radius", "0.8"},            // blur radius in tiles
        {"atmo.normals", "true"},             // relief lighting: per-texel normals from the pixel art (sun, point lights, glint)
        {"atmo.normals.strength", "0.45"},
        {"atmo.snowSparkle", "true"},         // snow glitter: crisp sun / fire glints on tiny facets of snow tiles (render3d/tiles.properties `snow` lines)
        {"atmo.snowSparkle.strength", "1.0"}, // 0..2
        {"atmo.snowSheen", "true"},           // soft glossy sheen of the snow towards the sun, cool sky sheen at grazing angles
        {"atmo.snowSheen.strength", "1.0"},   // 0..2
        {"atmo.ice", "true"},                 // reflective ice: mirrored reflection, sky sheen and glints on the ice tiles (render3d/tiles.properties `ice` lines)
        {"atmo.ice.strength", "1.0"},         // 0..2
        {"atmo.softShadows", "true"},         // wide rotated 16-tap shadow filter (false = old 3x3)
        {"atmo.shadow.softness", "2.2"},      // filter radius in shadow map texels
        {"atmo.shadow.tint", "0.5"},          // how much shade is tinted (0..1)
        {"atmo.shadow.tintColor", "auto"},    // auto (bluish outdoors, warm indoors) or RRGGBB
        {"atmo.shadow.contact", "false"},     // contact hardening: sharp near the caster, softer further away
        {"atmo.tod", "off"},                  // off | morning | noon | evening | night | cycle (cosmetic, render clock only)
        {"atmo.tod.cycle", "120"},            // seconds for a full day in cycle mode
        {"atmo.tod.windowGlow", "true"},      // dark windows glow at night
        {"atmo.tod.glow", "1.0"},
        {"atmo.lights", "true"},              // extra point lights: wall lamps, candles, windows (render3d/lights.properties)
        {"atmo.lights.strength", "1.0"},
        {"atmo.lights.flicker", "1.0"},       // 0 = steady, 1 = normal flicker
        {"atmo.mist", "false"},               // low ground mist outdoors
        {"atmo.mist.density", "0.5"},
        {"atmo.mist.height", "2.5"},          // tiles
        {"debug.overlay", "false"},
        // keyboard: MIDP key -> comma separated libGDX key names (Input.Keys names)
        {"key.UP", "UP, W"},
        {"key.DOWN", "DOWN, S"},
        {"key.LEFT", "LEFT, A"},
        {"key.RIGHT", "RIGHT, D"},
        {"key.FIRE", "ENTER, SPACE, NUMPAD_ENTER"},
        {"key.SOFT_LEFT", "Q, F1"},
        {"key.SOFT_RIGHT", "E, F2, ESCAPE, BACKSPACE"},
        {"key.NUM0", "0, NUMPAD_0"},
        {"key.NUM1", "1, NUMPAD_1"},
        {"key.NUM2", "2, NUMPAD_2"},
        {"key.NUM3", "3, NUMPAD_3"},
        {"key.NUM4", "4, NUMPAD_4"},
        {"key.NUM5", "5, NUMPAD_5"},
        {"key.NUM6", "6, NUMPAD_6"},
        {"key.NUM7", "7, NUMPAD_7"},
        {"key.NUM8", "8, NUMPAD_8"},
        {"key.NUM9", "9, NUMPAD_9"},
        {"key.STAR", "NUMPAD_MULTIPLY, STAR"},
        {"key.POUND", "F4, NUMPAD_DIVIDE, POUND"},
        // gamepad: MIDP key -> comma separated button names
        // (A B X Y BACK START L1 R1 L_STICK R_STICK DPAD_UP DPAD_DOWN DPAD_LEFT DPAD_RIGHT)
        {"pad.UP", "DPAD_UP"},
        {"pad.DOWN", "DPAD_DOWN"},
        {"pad.LEFT", "DPAD_LEFT"},
        {"pad.RIGHT", "DPAD_RIGHT"},
        {"pad.FIRE", "A"},
        {"pad.SOFT_LEFT", "X, START"},
        {"pad.SOFT_RIGHT", "B, BACK"},
        {"pad.NUM0", "Y"},
        {"pad.STAR", "L1"},
        {"pad.POUND", "R1"},
        // fast forward (fixed hotkey, toggle): game.fastForwardSpeed = logic speed multiplier while on (2..8; editable in the F10 options screen);
        // fastforward.key = libGDX key name (default F8), fastforward.pad = gamepad button (default R_STICK = right stick click); empty = no binding.
        // Script command: speed N (1 = normal). Ignored in --deterministic / --trace-ticks runs. docs/platform.md "Fast forward"
        {"game.fastForwardSpeed", "3"},
        {"fastforward.key", "F8"},
        {"fastforward.pad", "R_STICK"},
        // left stick acts as directions
        {"pad.stick.enabled", "true"},
        {"pad.stick.deadzone", "0.5"},
        // in-game options screen (F10, or Start+Back on a gamepad): hintShown = the one-time "F10: options" hint was shown
        {"options.hintShown", "false"},
        // audio.volume: 0..100 master output level; audio.mute silences everything without changing the level
        {"audio.volume", "100"},
        {"audio.mute", "false"},
        // audio.enabled: answer to the game's startup "sound?" question (applied at start, like answering it); firstRun.done: the startup
        // questions (sound, then 2D/3D -> render.view) were answered and are skipped from now on. Set it to false to be asked again.
        {"audio.enabled", "true"},
        {"firstRun.done", "false"},
        // game.jar: your own copy of the original game (Gothic3-TheBeginning_s40v3a.jar), remembered by the first-run setup; docs/byo-jar.md
        {"game.jar", ""},
        // key layout version of this file (1/none = original defaults, 2 = v0.1.1 experiment, 3 = original defaults again); see migrateKeyLayout
        {"keys.layout", "3"},
    };

    /** The keyboard defaults v0.1.1 wrote (layout 2); a saved value equal to one of these is put back to the original default, once. */
    private static final String[][] LAYOUT2_KEY_DEFAULTS = {
        {"key.FIRE", "E, SPACE, ENTER, NUMPAD_ENTER"},
        {"key.SOFT_LEFT", "I, F1"},
        {"key.SOFT_RIGHT", "ESCAPE, BACKSPACE, F2"},
        {"key.NUM0", "P, 0, NUMPAD_0"},
        {"key.STAR", "TAB, T, NUMPAD_MULTIPLY, STAR"},
        {"key.POUND", "R, F4, NUMPAD_DIVIDE, POUND"},
    };

    /** Keys changed through {@link #set} and not yet written by {@link #save}. */
    private final Set<String> changed = new LinkedHashSet<>();

    private volatile int version;

    /** Incremented on every changed {@link #set}; lets render code re-read settings only when needed. */
    public int version() { return version; }

    private Config(File f) { file = f; }

    public static File configDir() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        File base = (xdg != null && !xdg.isEmpty()) ? new File(xdg) : new File(System.getProperty("user.home"), ".config");
        return new File(base, "gothic3-pc");
    }

    /** Per-user data directory (saves, built game cache): XDG_DATA_HOME or ~/.local/share, + gothic3-pc (same place as the RecordStores). */
    public static File dataDir() {
        String xdg = System.getenv("XDG_DATA_HOME");
        File base = (xdg != null && !xdg.isEmpty()) ? new File(xdg) : new File(System.getProperty("user.home"), ".local/share");
        return new File(base, "gothic3-pc");
    }

    public static Config load() {
        File f = new File(configDir(), "config.properties");
        Config c = new Config(f);
        for (String[] kv : DEFAULTS) c.props.setProperty(kv[0], kv[1]);
        if (f.exists()) {
            try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                java.util.Properties saved = new java.util.Properties();
                saved.load(r);
                c.props.putAll(saved);
                c.migrateKeyLayout(saved);
            } catch (IOException e) {
                System.err.println("[config] cannot read " + f + ": " + e);
            }
        } else {
            c.writeDefaults();
        }
        return c;
    }

    private static String normList(String v) {
        StringBuilder b = new StringBuilder();
        for (String t : v.split(",")) if (!t.trim().isEmpty()) b.append(b.length() > 0 ? ", " : "").append(t.trim());
        return b.toString();
    }

    /**
     * One-time move to key layout 3 (the original defaults). A file without keys.layout predates layout 2 and already holds the original
     * defaults, so it is only stamped. A layout 2 file has each key.* entry that still equals a layout 2 default reset to the original
     * default (entries the user changed stay); keys the user bound elsewhere are dropped from the reset value so no key ends up on two
     * MIDP keys. Entries missing from the file already use the original default.
     */
    private void migrateKeyLayout(java.util.Properties saved) {
        String layout = saved.getProperty("keys.layout");
        if (layout != null && !layout.trim().equals("2")) return; // 3 = current; anything else is left alone
        List<String> reverted = new ArrayList<>();
        if (layout != null) {
            Set<String> mine = new HashSet<>(); // key names bound by entries that are kept
            for (String k : saved.stringPropertyNames()) {
                if (!k.startsWith("key.")) continue;
                if (isLayout2Default(k, saved.getProperty(k))) reverted.add(k);
                else for (String t : saved.getProperty(k).split(",")) if (!t.trim().isEmpty()) mine.add(t.trim().toUpperCase());
            }
            for (String k : reverted) {
                StringBuilder b = new StringBuilder();
                for (String t : defaultOf(k).split(",")) {
                    t = t.trim();
                    if (!mine.contains(t.toUpperCase())) b.append(b.length() > 0 ? ", " : "").append(t);
                }
                props.setProperty(k, b.toString());
            }
            System.err.println("[config] key bindings moved back to the original defaults (layout 3, " + reverted.size() + " entries); see docs/platform.md");
        }
        props.setProperty("keys.layout", "3");
        changed.add("keys.layout"); // set() would skip it when equal to the default, so mark it for saving directly
        changed.addAll(reverted);
        save();
    }

    private static boolean isLayout2Default(String key, String value) {
        for (String[] o : LAYOUT2_KEY_DEFAULTS) if (o[0].equals(key) && normList(value).equalsIgnoreCase(o[1])) return true;
        return false;
    }

    private void writeDefaults() {
        try {
            file.getParentFile().mkdirs();
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))) {
                w.println("# Gothic 3 PC port - configuration (created on first run; delete to regenerate)");
                w.println("#");
                w.println("# key.<MIDP_KEY> = libGDX key names, comma separated (Input.Keys names: A..Z, 0..9, UP, ENTER, SPACE, F1, NUMPAD_0, ESCAPE ...)");
                w.println("# pad.<MIDP_KEY> = gamepad buttons: A B X Y BACK START L1 R1 L_STICK R_STICK DPAD_UP DPAD_DOWN DPAD_LEFT DPAD_RIGHT");
                w.println("# MIDP_KEY: UP DOWN LEFT RIGHT FIRE SOFT_LEFT SOFT_RIGHT NUM0..NUM9 STAR POUND");
                w.println("# Fullscreen: F11 or Alt+Enter (fixed). F3 toggles the FPS / tick overlay (fixed; no longer mapped to '*').");
                w.println("# F10 (or Start+Back on a gamepad) opens the in-game options screen, which edits this file for you.");
                w.println("# F8 toggles fast forward (key: fastforward.key, gamepad: fastforward.pad = right stick click); speed = game.fastForwardSpeed (2..8).");
                w.println("# F5 toggles the 2D / 3D view, F6 reloads the 3D tile table, F7 opens the in-game 3D tile editor (all fixed).");
                w.println("# render.mode = interpolated | classic, render.maxFps = 0 (follow display) or a frame rate cap.");
                w.println();
                String last = "";
                for (String[] kv : DEFAULTS) {
                    String grp = kv[0].substring(0, kv[0].indexOf('.'));
                    if (!grp.equals(last)) { w.println(); last = grp; }
                    w.println(kv[0] + " = " + kv[1]);
                }
            }
            System.err.println("[config] wrote default config to " + file);
        } catch (IOException e) {
            System.err.println("[config] cannot write " + file + ": " + e);
        }
    }

    /** Built-in default of a key, or null if it has none. */
    public static String defaultOf(String k) {
        for (String[] kv : DEFAULTS) if (kv[0].equals(k)) return kv[1];
        return null;
    }

    /** Sets a value in memory and marks it for {@link #save}. */
    public synchronized void set(String k, String v) {
        if (v.equals(props.getProperty(k))) return;
        version++;
        props.setProperty(k, v);
        changed.add(k);
    }

    /** Like {@link #set} but always marks the key for saving, even when it equals the built-in default (so the answer lands in the file). */
    public synchronized void setAlways(String k, String v) {
        if (!v.equals(props.getProperty(k))) { version++; props.setProperty(k, v); }
        changed.add(k);
    }

    public synchronized boolean hasUnsaved() { return !changed.isEmpty(); }

    /**
     * Writes the keys changed through {@link #set} into the file: existing lines are rewritten in place (comments, order and
     * unrelated lines are kept), keys that are not in the file yet are appended at the end.
     */
    public synchronized boolean save() {
        if (changed.isEmpty()) return true;
        try {
            List<String> lines = file.isFile() ? new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) : new ArrayList<>();
            Set<String> done = new HashSet<>();
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
                int e = 0;
                while (e < t.length() && "=: \t".indexOf(t.charAt(e)) < 0) e++;
                String key = t.substring(0, e);
                if (changed.contains(key)) {
                    lines.set(i, key + " = " + escapeValue(props.getProperty(key, "")));
                    done.add(key);
                }
            }
            boolean header = lines.contains("# --- added by the in-game options screen");
            for (String k : changed) {
                if (done.contains(k)) continue;
                if (!header) {
                    if (!lines.isEmpty() && !lines.get(lines.size() - 1).trim().isEmpty()) lines.add("");
                    lines.add("# --- added by the in-game options screen");
                    header = true;
                }
                lines.add(k + " = " + escapeValue(props.getProperty(k, "")));
            }
            file.getParentFile().mkdirs();
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.write(tmp.toPath(), lines, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            changed.clear();
            return true;
        } catch (IOException e) {
            System.err.println("[config] cannot save " + file + ": " + e);
            return false;
        }
    }

    private static String escapeValue(String v) { return v.replace("\\", "\\\\"); }

    public String get(String k) { return props.getProperty(k, "").trim(); }
    public int getInt(String k, int def) {
        try { return Integer.parseInt(get(k)); } catch (NumberFormatException e) { return def; }
    }
    public float getFloat(String k, float def) {
        try { return Float.parseFloat(get(k)); } catch (NumberFormatException e) { return def; }
    }
    public boolean getBool(String k) { return Boolean.parseBoolean(get(k)); }

    public List<String> list(String k) {
        List<String> out = new ArrayList<>();
        for (String s : get(k).split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }
}
