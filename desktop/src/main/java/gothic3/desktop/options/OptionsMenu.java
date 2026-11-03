package gothic3.desktop.options;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import gothic3.desktop.Config;
import gothic3.desktop.G3App;
import gothic3.desktop.InputHandler;
import gothic3.desktop.MidpKey;
import gothic3.platform.RenderConfig;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The content of the options screen: tabs of {@link Item}s, and everything that happens when a value changes (live apply,
 * config persistence, binding edits). No drawing here, see {@link OptionsOverlay}. Render thread only.
 */
public final class OptionsMenu {
    /** A tab of items. */
    static final class Tab {
        final String name;
        final List<Item> items = new ArrayList<>();
        Tab(String name) { this.name = name; }
        boolean hasBinds() {
            for (Item i : items) if (i instanceof Item.Bind) return true;
            return false;
        }
    }

    /** A captured binding that is already used by another slot; {@link #resolveSwap} applies the swap. */
    static final class Conflict {
        Item.Bind target, other;
        int slot, otherSlot;
        String name, old;
    }

    final Config cfg;
    private final G3App app;
    private final InputHandler input;
    private final WindowControl win;
    private final File tuneFile;               // our private render3d.* override file (null: the user set -Dg3.render3d.tune, then use F6)
    private long tuneTime;
    private final Runnable close, quit;
    final List<Tab> tabs = new ArrayList<>();

    private boolean dirty3d;
    private long last3dApply, saveAt;
    String toast = "";
    long toastUntil;

    /** What each MIDP key does in the game (docs/platform.md "Controls"); the MIDP name stays in brackets. */
    private static final String[] MIDP_LABELS = {
        "UP", "Move up (UP)", "DOWN", "Move down (DOWN)", "LEFT", "Move left (LEFT)", "RIGHT", "Move right (RIGHT)",
        "FIRE", "Attack / talk / OK (FIRE)",
        "SOFT_LEFT", "Character menu (SOFT_LEFT)", "SOFT_RIGHT", "Menu / back (SOFT_RIGHT)",
        "NUM0", "Pause (NUM0)", "NUM1", "Slot 1 / up-left (NUM1)", "NUM2", "Slot 2 / up (NUM2)", "NUM3", "Slot 3 / up-right (NUM3)",
        "NUM4", "Slot 4 / left (NUM4)", "NUM5", "Slot 5 / = FIRE (NUM5)", "NUM6", "Slot 6 / right (NUM6)",
        "NUM7", "Slot 7 / down-left (NUM7)", "NUM8", "Slot 8 / down (NUM8)", "NUM9", "Down-right (NUM9)",
        "STAR", "Next target (STAR)", "POUND", "Quick slots (POUND)"};

    public OptionsMenu(Config cfg, G3App app, InputHandler input, WindowControl win, File tuneFile, Runnable close, Runnable quit) {
        this.cfg = cfg;
        this.app = app;
        this.input = input;
        this.win = win;
        this.tuneFile = tuneFile;
        this.close = close;
        this.quit = quit;
        build();
        if (Boolean.getBoolean("g3.debug")) for (String b : gothic3.desktop.GraphicsPresets.highVsDefaults()) System.err.println("[presets] High column differs from the default: " + b);
        for (Tab t : tabs) for (Item i : t.items) i.startText = i.text();
    }

    // ---------------------------------------------------------------------------------------------- content

    private Item.Setting set(Tab t, String label, String key) {
        Item.Setting s = new Item.Setting(this, label, key);
        t.items.add(s);
        return s;
    }

    private static String[] a(String... s) { return s; }

    private void build() {
        Tab d = new Tab("Display");
        set(d, "Screen preset", "screen.preset").choice(a("wide", "tight", "classic"), a("Wide 640x360", "Tight 480x270", "Classic 240x320")).restart()
                .hint("Logical resolution of the game picture.");
        set(d, "Window scale", "window.scale").choice(a("auto", "1", "2", "3", "4", "5", "6"), a("Auto", "1x", "2x", "3x", "4x", "5x", "6x"))
                .hint("Window size = logical size x scale (applies when windowed).");
        set(d, "Fullscreen", "window.fullscreen").toggle().live(() -> String.valueOf(win.isFullscreen())).hint("Same as F11 / Alt+Enter.");
        set(d, "VSync", "window.vsync").toggle();
        set(d, "Render mode", "render.mode").choice(a("interpolated", "classic"), a("Interpolated (smooth)", "Classic (10 fps)")).restart()
                .hint("Interpolated keeps the 10 Hz logic but draws at display rate.");
        set(d, "Max FPS", "render.maxFps").choice(a("0", "60", "120", "144", "165", "240", "1000"),
                a("Display rate", "60", "120", "144", "165", "240", "Unlimited")).hint("Display rate = one frame per vsync.");
        set(d, "Fast forward speed", "game.fastForwardSpeed").choice(a("2", "3", "4", "5", "6", "7", "8"), a("2x", "3x", "4x", "5x", "6x", "7x", "8x"))
                .hint("Logic speed while fast forward is on (F8 / right stick click toggles).");
        set(d, "FPS overlay", "debug.overlay").toggle().live(() -> String.valueOf(app.overlayVisible())).hint("Same as F3.");
        d.items.add(reset(d));
        tabs.add(d);

        Tab r = new Tab("3D");
        qualityItem(r);
        set(r, "View", "render.view").choice(a("2d", "3d"), a("2D (original)", "3D diorama")).live(() -> RenderConfig.view3d() ? "3d" : "2d").hint("Same as F5.");
        set(r, "Camera pitch", "render3d.pitch").slider(8, 88, 1).hint("Degrees above the horizon (PageUp / PageDown in game).");
        set(r, "Camera distance", "render3d.distance").slider(4, 60, 1).hint("Tiles from the camera to the look-at point (- / = in game).");
        set(r, "Field of view", "render3d.fov").slider(10, 90, 1).hint("Vertical degrees ([ / ] in game).");
        set(r, "Focus offset", "render3d.focusOffset").slider(-4, 8, 0.5).hint("Tiles the player sits below the screen centre.");
        set(r, "Lighting", "render3d.lighting").toggle().hint("Sun + ambient light with per-face shading.");
        set(r, "Shadows", "render3d.shadows").choice(a("off", "low", "high"), a("Off", "Low", "High"));
        set(r, "Sun azimuth", "render3d.sun.azimuth").slider(0, 355, 5).hint("Compass direction of the sun: 0 north, 90 east, 180 south.");
        set(r, "Sun elevation", "render3d.sun.elevation").slider(5, 85, 1);
        set(r, "Sun strength", "render3d.sun.strength").slider(0, 1.5, 0.02);
        set(r, "Ambient light", "render3d.ambient").slider(0, 1.5, 0.02);
        set(r, "Map surround", "render3d.surround").toggle().hint("Mirrored edge tiles fading out around the map.");
        set(r, "Fog", "render3d.fog").toggle();
        set(r, "See hero behind objects", "render3d.occFade").toggle().hint("Trees, walls and roofs in front of the hero dither out around him (3D view).");
        set(r, "Occluder opacity", "render3d.occFade.min").slider(0, 0.9, 0.02).hint("Opacity left on faded objects: low = see-through, high = faint ghost.");
        set(r, "Houses and walls", "render3d.structures").toggle().hint("Merged 3D houses, roofs and walls instead of per-tile pieces.");
        r.items.add(reset(r));
        tabs.add(r);

        Tab at = new Tab("Atmosphere");   // ATMO
        qualityItem(at);
        set(at, "Atmosphere effects", "atmo.enabled").toggle().hint("Master switch for everything on this tab (3D view only).");
        at.items.add(new Item.Header("Weather and fire"));
        set(at, "Snow", "atmo.snow").toggle().hint("Falling snow on snowy maps (map / tileset table: render3d/atmosphere.properties).");
        set(at, "Snow amount", "atmo.snow.density").slider(0, 2, 0.1);
        set(at, "Snow flake size", "atmo.snow.size").slider(0.4, 2.5, 0.1);
        set(at, "Wind", "atmo.snow.wind").slider(0, 3, 0.1).hint("Drift of snow, embers and smoke.");
        set(at, "Snowfall", "atmo.snow.weather").choice(a("steady", "varying"), a("Steady", "Changing")).hint("Changing: spells of no, light, normal and heavy snow with gusts of wind.");
        set(at, "Plant sway", "atmo.sway").toggle().hint("Trees, bushes and reeds bend in the wind (the same wind as the snow).");
        set(at, "Sway strength", "atmo.sway.strength").slider(0, 2.5, 0.1);
        set(at, "Embers", "atmo.embers").toggle().hint("Glowing sparks rising from fires and torches.");
        set(at, "Ember amount", "atmo.embers.density").slider(0, 1, 0.1);
        set(at, "Ember size", "atmo.embers.size").slider(0.4, 2.5, 0.1);
        set(at, "Smoke", "atmo.smoke").toggle().hint("Faint smoke wisps over fires.");
        set(at, "Dust motes", "atmo.dust").toggle().hint("Floating dust in interiors, brightest in lit areas.");
        set(at, "Dust amount", "atmo.dust.density").slider(0, 2, 0.1);
        at.items.add(new Item.Header("Light"));
        set(at, "Light shafts", "atmo.shafts").toggle().hint("Soft beams through windows (interiors) and from the sun (outdoors).");
        set(at, "Shaft strength", "atmo.shafts.strength").slider(0, 2, 0.05);
        set(at, "Rim light", "atmo.rim").toggle().hint("Thin bright edge on the shaded side of characters.");
        set(at, "Rim strength", "atmo.rim.strength").slider(0, 1.5, 0.05);
        set(at, "Contact shading", "atmo.ao").toggle().hint("Baked soft darkening where walls meet the floor, in corners and around raised ground.");
        set(at, "Contact strength", "atmo.ao.strength").slider(0, 1.5, 0.05);
        set(at, "Contact radius", "atmo.ao.radius").slider(0.3, 2.0, 0.1).hint("Tiles.");
        set(at, "Relief lighting", "atmo.normals").toggle().hint("Bumps from the pixel art catch sun and torchlight (bricks, wood, armour).");
        set(at, "Relief strength", "atmo.normals.strength").slider(0, 2, 0.05);
        set(at, "Snow sparkle", "atmo.snowSparkle").toggle().hint("Tiny crisp glints on sunlit snow (and near fires) that twinkle as the view and light change.");
        set(at, "Sparkle strength", "atmo.snowSparkle.strength").slider(0, 2, 0.05);
        set(at, "Snow sheen", "atmo.snowSheen").toggle().hint("Soft glossy sheen of snow towards the sun, cool at grazing angles.");
        set(at, "Sheen strength", "atmo.snowSheen.strength").slider(0, 2, 0.05);
        set(at, "Reflective ice", "atmo.ice").toggle().hint("Frozen lakes and sea mirror characters, trees and buildings, with a sky sheen and moving glints.");
        set(at, "Ice reflection strength", "atmo.ice.strength").slider(0, 2, 0.05);
        set(at, "Soft shadows", "atmo.softShadows").toggle().hint("Wide rotated shadow filter instead of the 3x3 one (needs shadows on, 3D tab).");
        set(at, "Shadow softness", "atmo.shadow.softness").slider(0.5, 8, 0.1).hint("Filter radius in shadow map texels.");
        set(at, "Shadow tint", "atmo.shadow.tint").slider(0, 1, 0.05).hint("Colour of the shade: bluish outdoors, warm indoors.");
        set(at, "Contact hardening", "atmo.shadow.contact").toggle().hint("Sharp near the caster, softer further away.");
        at.items.add(new Item.Header("Time of day (cosmetic only)"));
        set(at, "Time of day", "atmo.tod").choice(a("off", "morning", "noon", "evening", "night", "cycle"),
                a("Off", "Morning", "Noon", "Evening", "Night", "Cycle")).hint("Sun, ambient, fog and fire light tint; never touches the game.");
        set(at, "Cycle length (s)", "atmo.tod.cycle").slider(20, 600, 10).hint("Seconds for a full day in Cycle mode.");
        set(at, "Window glow", "atmo.tod.windowGlow").toggle().hint("Dark windows glow warm at night.");
        set(at, "Lamps, candles, windows", "atmo.lights").toggle().hint("Extra point lights: wall lamps, altar candles, lit windows (table: render3d/lights.properties).");
        set(at, "Lamp light strength", "atmo.lights.strength").slider(0, 2, 0.05);
        set(at, "Lamp flicker", "atmo.lights.flicker").slider(0, 2, 0.1);
        set(at, "Ground mist", "atmo.mist").toggle().hint("Low-lying fog layer outdoors.");
        set(at, "Mist density", "atmo.mist.density").slider(0, 1.5, 0.05);
        set(at, "Mist height", "atmo.mist.height").slider(0.5, 8, 0.5).hint("Tiles above the ground.");
        at.items.add(reset(at));
        tabs.add(at);

        tabs.add(EffectsTab.build(this));   // POST: Effects tab (HD-2D post-processing)

        Tab k = new Tab("Keyboard");
        k.items.add(new Item.Header("Enter / click on a slot, then press a key.  Backspace clears, Esc cancels."));
        for (String m : MidpKey.BY_NAME.keySet()) k.items.add(new Item.Bind(m, midpLabel(m), false));
        k.items.add(reset(k));
        tabs.add(k);

        Tab p = new Tab("Gamepad");
        set(p, "Left stick as directions", "pad.stick.enabled").toggle();
        set(p, "Stick dead zone", "pad.stick.deadzone").slider(0.1, 0.9, 0.05).hint("Higher = the stick must move further.");
        p.items.add(new Item.Header("Enter / click on a slot, then press a pad button.  Back-space / Y clears, Esc / B cancels."));
        for (String m : MidpKey.BY_NAME.keySet()) p.items.add(new Item.Bind(m, midpLabel(m), true));
        p.items.add(reset(p));
        tabs.add(p);

        Tab au = new Tab("Audio");
        set(au, "Master volume", "audio.volume").slider(0, 100, 5).hint("Music and effects share one level.");
        set(au, "Mute", "audio.mute").toggle();
        set(au, "Sound (game setting)", "audio.enabled").toggle().restart()
                .hint("The startup sound question; applies on restart. Mute above is live. --no-audio always wins.");
        au.items.add(reset(au));
        tabs.add(au);

        Tab g = new Tab("Game");
        set(g, "Ask startup questions again", "firstRun.done").choice(a("true", "false"), a("No", "Yes"))
                .hint("Yes = the sound and 2D/3D questions are asked again at the next start.");
        if (gothic3.desktop.byo.GameProvider.current != null) {
            g.items.add(new Item.Action("Rebuild game data (quits)", () -> {
                if (gothic3.desktop.byo.GameProvider.current.invalidateCache()) quit.run();
                else toast("Could not clear the built game");
            }).hint("Builds the game again from your jar at the next start, e.g. after installing ffmpeg for the sound effects."));
        }
        g.items.add(new Item.Action("Back to game", close));
        g.items.add(new Item.Action("Quit game", quit).danger());
        tabs.add(g);
    }

    /** "Graphics quality" (presets, GraphicsPresets): the label is computed from the config, so changing any single effect shows Custom. */
    private void qualityItem(Tab t) {
        set(t, "Graphics quality", "gfx.quality").choice(a("low", "medium", "high", "ultra", "custom"), a("Low", "Medium", "High", "Ultra", "Custom"))
                .live(() -> gothic3.desktop.GraphicsPresets.current(cfg))
                .hint("Sets shadows, post-processing and most 3D effects at once. Every effect stays editable; changing one shows Custom.");
        if (t.name.equals("3D")) t.items.add(new Item.Header("Camera and lighting"));
    }

    private static String midpLabel(String m) {
        for (int i = 0; i < MIDP_LABELS.length; i += 2) if (MIDP_LABELS[i].equals(m)) return MIDP_LABELS[i + 1];
        return m;
    }

    private Item reset(Tab t) {
        return new Item.Action("Reset this tab to defaults", () -> resetTab(t));
    }

    void resetTab(Tab t) {
        for (Item i : t.items) {
            if (i instanceof Item.Bind) {
                Item.Bind b = (Item.Bind) i;
                String def = Config.defaultOf(b.key());
                cfg.set(b.key(), def == null ? "" : def);
            } else if (i.key() != null) {
                i.reset();
            }
        }
        if (t.hasBinds()) input.reloadBindings(cfg);
        scheduleSave();
        toast("Defaults restored");
    }

    // ---------------------------------------------------------------------------------------------- changes

    void toast(String s) {
        toast = s;
        toastUntil = System.currentTimeMillis() + 3500;
    }

    private void scheduleSave() { saveAt = System.currentTimeMillis() + 500; }

    private static boolean on(String v) { return v.equalsIgnoreCase("true"); }

    /** Sets a config value and applies it live where possible. */
    public void change(String key, String value) {
        if (key.equals("gfx.quality")) {   // presets are never stored: the label is computed from the config (GraphicsPresets.current)
            if (!value.equals(gothic3.desktop.GraphicsPresets.CUSTOM)) {
                gothic3.desktop.GraphicsPresets.apply(value, this::change);
                toast("Graphics quality: " + gothic3.desktop.GraphicsPresets.label(value));
            }
            return;
        }
        cfg.set(key, value);
        switch (key) {
            case "screen.preset":
                for (String k : new String[] {"screen.width", "screen.height"}) if (!cfg.get(k).isEmpty()) cfg.set(k, ""); // they would override the preset
                break;
            case "window.scale": win.setScale(value); break;
            case "window.fullscreen": win.setFullscreen(on(value)); break;
            case "window.vsync": win.setVsync(on(value)); app.vsync(on(value)); win.applyPacing(Math.max(0, cfg.getInt("render.maxFps", 0))); break;
            case "render.maxFps": win.applyPacing(Math.max(0, cfg.getInt("render.maxFps", 0))); break;
            case "debug.overlay": if (on(value) != app.overlayVisible()) app.toggleOverlay(); break;
            case "render.view": if (value.equalsIgnoreCase("3d") != RenderConfig.view3d()) app.toggleView3d(); break;
            case "audio.volume": case "audio.mute": applyAudio(); break;
            case "pad.stick.enabled": case "pad.stick.deadzone": input.reloadBindings(cfg); break;
            case "post.preset": if (!value.equals("custom")) gothic3.desktop.render3d.post.PostSettings.applyPreset(cfg, value); break;
            default:
                if (key.startsWith("post.")) cfg.set("post.preset", "custom");   // POST: a slider was touched
                if (key.startsWith("atmo.")) app.applyAtmoSettings();   // ATMO: cheap, applied at once
                if (key.startsWith("render3d.occFade")) app.applyAtmoSettings();   // occluder fade: live, no mesh rebuild
                else if (key.startsWith("render3d.")) { dirty3d = true; last3dApply = 0; }
        }
        scheduleSave();
    }

    public void applyAudio() {
        float v = cfg.getBool("audio.mute") ? 0f : Math.max(0, Math.min(100, cfg.getInt("audio.volume", 100))) / 100f;
        javax.microedition.media.impl.PlayerFactory.setMasterVolume(v);
    }

    /** Called every frame: throttled 3D settings reload and debounced config save. */
    void tick() {
        long now = System.currentTimeMillis();
        if (dirty3d && now - last3dApply >= 120) apply3d(now);
        if (saveAt != 0 && now >= saveAt) save();
    }

    private void apply3d(long now) {
        dirty3d = false;
        last3dApply = now;
        if (tuneFile != null) {
            Properties p = new Properties();
            for (Map.Entry<Object, Object> e : cfg.props.entrySet()) {
                if (((String) e.getKey()).startsWith("render3d.")) p.setProperty((String) e.getKey(), (String) e.getValue());
            }
            try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(tuneFile.toPath())) {
                p.store(w, "render3d.* as set in the options screen (live preview)");
            } catch (java.io.IOException ex) {
                System.err.println("[options] cannot write " + tuneFile + ": " + ex);
            }
            tuneTime = Math.max(tuneTime + 1000, now);   // the renderer reloads when the file's timestamp changes
            tuneFile.setLastModified(tuneTime);
        }
        cfg.save(); // the structures builder also reads the config file
        app.applyRender3dSettings();
    }

    private void save() {
        saveAt = 0;
        cfg.save();
    }

    /** Applies pending 3D settings and writes the config now (overlay closes, game exits). */
    public void flush() {
        if (dirty3d) apply3d(System.currentTimeMillis());
        save();
    }

    /** Labels of items that changed since launch and need a restart. */
    List<String> restartNotes() {
        List<String> out = new ArrayList<>();
        for (Tab t : tabs) for (Item i : t.items) if (i.restart && !i.text().equals(i.startText)) out.add(i.label);
        return out;
    }

    // ---------------------------------------------------------------------------------------------- bindings

    List<String> bindings(Item.Bind b) { return cfg.list(b.key()); }

    String bindName(Item.Bind b, int slot) {
        List<String> l = bindings(b);
        return slot < l.size() ? l.get(slot) : "";
    }

    /** Number of bindings beyond the two shown slots. */
    int extraBindings(Item.Bind b) { return Math.max(0, bindings(b).size() - 2); }

    private void write(Item.Bind b, List<String> l) {
        cfg.set(b.key(), String.join(", ", l));
        input.reloadBindings(cfg);
        scheduleSave();
    }

    private static void setSlot(List<String> l, int slot, String name) {
        if (name == null) {
            if (slot < l.size()) l.remove(slot);
        } else if (slot < l.size()) {
            l.set(slot, name);
        } else {
            l.add(name);
        }
    }

    private static boolean same(boolean pad, String x, String y) {
        if (pad) return x.equalsIgnoreCase(y);
        int a = InputHandler.keyByName(x), b = InputHandler.keyByName(y);
        return a >= 0 && a == b;
    }

    void clear(Item.Bind b, int slot) {
        List<String> l = bindings(b);
        if (slot >= l.size()) return;
        l.remove(slot);
        write(b, l);
        toast(b.label + ": slot " + (slot + 1) + " cleared");
    }

    /** Binds {@code name} to a slot. Returns null when done, or the conflict (nothing changed yet) if another slot already uses it. */
    Conflict assign(Item.Bind b, int slot, String name) {
        List<String> mine = bindings(b);
        String old = slot < mine.size() ? mine.get(slot) : null;
        if (old != null && same(b.pad, old, name)) return null;
        for (Tab t : tabs) {
            for (Item i : t.items) {
                if (!(i instanceof Item.Bind) || ((Item.Bind) i).pad != b.pad) continue;
                Item.Bind ob = (Item.Bind) i;
                List<String> ol = bindings(ob);
                for (int j = 0; j < ol.size(); j++) {
                    if (same(b.pad, ol.get(j), name)) {
                        Conflict c = new Conflict();
                        c.target = b; c.other = ob; c.slot = slot; c.otherSlot = j; c.name = name; c.old = old;
                        return c;
                    }
                }
            }
        }
        setSlot(mine, slot, name);
        write(b, mine);
        toast(b.label + " = " + name);
        return null;
    }

    void resolveSwap(Conflict c) {
        List<String> ol = bindings(c.other);
        if (c.old != null) ol.set(c.otherSlot, c.old); else ol.remove(c.otherSlot);
        write(c.other, ol);
        List<String> mine = bindings(c.target);
        setSlot(mine, c.slot, c.name);
        write(c.target, mine);
        toast(c.old != null ? "Swapped with " + c.other.label : "Moved from " + c.other.label);
    }

    /** gdx key code -> config name, or null if the key cannot be stored / is reserved. */
    static String keyName(int code) {
        switch (code) {
            case Input.Keys.F3: case Input.Keys.F5: case Input.Keys.F6: case Input.Keys.F7: case Input.Keys.F8: case Input.Keys.F10: case Input.Keys.F11:
                return null;
            default:
        }
        String n;
        try { n = Input.Keys.toString(code); } catch (RuntimeException e) { return null; }
        if (n == null || n.isEmpty() || InputHandler.keyByName(n) != code) return null;
        return n;
    }

    boolean fullscreenNow() { return Gdx.graphics.isFullscreen(); }
}
