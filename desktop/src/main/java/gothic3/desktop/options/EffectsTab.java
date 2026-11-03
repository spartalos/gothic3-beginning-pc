package gothic3.desktop.options;

import gothic3.desktop.render3d.post.PostSettings;

/** The "Effects" tab of the options screen: HD-2D post-processing (3D) and the optional 2D filters (docs/post.md). */
final class EffectsTab {
    private EffectsTab() { }

    private static String[] a(String... s) { return s; }

    private static Item.Setting add(OptionsMenu.Tab t, OptionsMenu m, String label, String key) {
        Item.Setting s = new Item.Setting(m, label, key);
        t.items.add(s);
        return s;
    }

    static OptionsMenu.Tab build(OptionsMenu m) {
        OptionsMenu.Tab t = new OptionsMenu.Tab("Effects");
        t.items.add(new Item.Header("3D view post-processing"));
        add(t, m, "Preset", "post.preset").choice(a("off", "subtle", "hd2d", "custom"), a("Off", "Subtle", "HD-2D", "Custom"))
                .hint("Sets all sliders below; touching a slider switches to Custom.");
        t.items.add(new Item.Header("Depth of field (tilt-shift)"));
        add(t, m, "Depth of field", "post.dof").toggle();
        add(t, m, "DOF mode", "post.dof.mode").choice(a("depth", "tiltshift"), a("Depth (distance from the player)", "Tilt-shift (screen bands)"));
        add(t, m, "Max blur", "post.dof.blur").slider(0, 24, 0.5).hint("Blur radius in pixels at 1080p.");
        add(t, m, "Focus offset (depth)", "post.dof.focus").slider(-8, 8, 0.5).hint("Tiles from the player; depth mode.");
        add(t, m, "Sharp band (depth)", "post.dof.band").slider(1, 24, 0.5).hint("Width of the sharp zone in tiles; depth mode.");
        add(t, m, "Band centre (tilt-shift)", "post.dof.tsCenter").slider(0.2, 0.8, 0.01).hint("Screen height of the sharp band, 0 bottom, 1 top.");
        add(t, m, "Band height (tilt-shift)", "post.dof.tsBand").slider(0.05, 0.8, 0.01);
        t.items.add(new Item.Header("Bloom and ambient occlusion"));
        add(t, m, "Bloom", "post.bloom").toggle();
        add(t, m, "Bloom intensity", "post.bloom.intensity").slider(0, 2, 0.05);
        add(t, m, "Bloom threshold", "post.bloom.threshold").slider(0.3, 3, 0.05).hint("Linear brightness where glow starts (fires ~1+, snow ~0.9).");
        add(t, m, "Bloom radius", "post.bloom.radius").slider(0, 1, 0.05);
        add(t, m, "Ambient occlusion", "post.ssao").toggle().hint("Darkens creases, wall bases and prop contacts.");
        add(t, m, "AO strength", "post.ssao.strength").slider(0, 1, 0.05);
        add(t, m, "AO radius", "post.ssao.radius").slider(0.2, 2.5, 0.1).hint("Tiles.");
        t.items.add(new Item.Header("Tone and colour"));
        add(t, m, "Tone mapping", "post.tonemap").slider(0, 1, 0.05).hint("Blend into the ACES-like filmic curve, 0 = off.");
        add(t, m, "Exposure", "post.exposure").slider(0.5, 2, 0.02);
        add(t, m, "Contrast", "post.contrast").slider(0.7, 1.5, 0.02);
        add(t, m, "Saturation", "post.saturation").slider(0.4, 1.6, 0.02);
        add(t, m, "Temperature", "post.temperature").slider(-1, 1, 0.05).hint("Cold (blue) to warm (orange).");
        add(t, m, "Tint", "post.tint").slider(-1, 1, 0.05).hint("Green to magenta.");
        add(t, m, "Grade", "post.grade").choice(a("none", "octopath", "snow", "night", "lut"), a("None", "Octopath warm", "Cold snow", "Night", "LUT file"))
                .hint("Colour grading look; LUT: 16x16x16 strip in ~/.config/gothic3-pc/luts/.");
        add(t, m, "Grade amount", "post.grade.amount").slider(0, 1, 0.05);
        add(t, m, "LUT", "post.lut").choice(a("warm", "cool", "film"), a("warm", "cool", "film")).hint("Used when Grade = LUT file.");
        t.items.add(new Item.Header("Lens"));
        add(t, m, "Vignette", "post.vignette").slider(0, 1, 0.02);
        add(t, m, "Film grain", "post.grain").slider(0, 0.1, 0.01);
        add(t, m, "Chromatic aberration", "post.ca").slider(0, 1, 0.05).hint("Colour fringing towards the screen edges.");
        add(t, m, "Sharpen", "post.sharpen").slider(0, 1, 0.05).hint("Keeps pixel art crisp after the blur chain.");
        add(t, m, "FXAA", "post.fxaa").toggle().hint("Edge smoothing; off by default (pixel art).");
        t.items.add(new Item.Header("2D view (original look when everything is off)"));
        add(t, m, "Upscaler", "post2d.upscaler").choice(a("nearest", "sharp", "epx"), a("Nearest (original)", "Sharp bilinear", "Corner smoothing (EPX)"));
        add(t, m, "2D bloom", "post2d.bloom").toggle();
        add(t, m, "2D bloom intensity", "post2d.bloom.intensity").slider(0, 1.5, 0.05);
        add(t, m, "2D bloom threshold", "post2d.bloom.threshold").slider(0.2, 1.5, 0.05);
        add(t, m, "2D grade", "post2d.grade").choice(a("none", "octopath", "snow", "night"), a("None", "Octopath warm", "Cold snow", "Night"));
        add(t, m, "2D saturation", "post2d.saturation").slider(0.4, 1.6, 0.02);
        add(t, m, "2D contrast", "post2d.contrast").slider(0.7, 1.5, 0.02);
        add(t, m, "2D vignette", "post2d.vignette").slider(0, 1, 0.02);
        add(t, m, "2D film grain", "post2d.grain").slider(0, 0.1, 0.01);
        add(t, m, "CRT filter", "post2d.crt").toggle().hint("Scanlines per logical pixel row, shadow mask and slight curvature.");
        add(t, m, "CRT scanlines", "post2d.crt.scan").slider(0, 1, 0.05);
        add(t, m, "CRT mask", "post2d.crt.mask").slider(0, 1, 0.05);
        add(t, m, "CRT curvature", "post2d.crt.curve").slider(0, 0.3, 0.01);
        t.items.add(new Item.Action("Reset this tab to defaults", () -> reset(m, t)));
        return t;
    }

    private static void reset(OptionsMenu m, OptionsMenu.Tab t) {
        PostSettings.applyPreset(m.cfg, "subtle");
        for (Item i : t.items) {
            if (i.key() != null && i.key().startsWith("post2d.")) i.reset();
        }
        m.toast("Defaults restored");
    }
}
