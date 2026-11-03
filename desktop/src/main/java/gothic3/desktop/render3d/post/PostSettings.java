package gothic3.desktop.render3d.post;

import gothic3.desktop.Config;

/** Parsed post-processing settings (config keys post.* for the 3D view, post2d.* for the 2D view) and the 3D presets. */
public final class PostSettings {
    // ---- 3D ----
    public boolean dof, dofTilt, bloom, ssao;
    public float dofFocus, dofBand, tsCenter, tsBand, dofBlur;
    public float bloomI, bloomThr, bloomRadius, ssaoStr, ssaoRadius;
    public float tonemap, exposure, contrast, saturation, temperature, tint, gradeAmount;
    public String grade = "none", lut = "";
    public float vignette, grain, ca, sharpen;
    public boolean fxaa;
    // ---- 2D ----
    public boolean bloom2, crt;
    public float bloom2I, bloom2Thr, sat2, contrast2, vig2, grain2, crtScan, crtMask, crtCurve;
    public String grade2 = "none";
    public int upscaler;                       // 0 nearest, 1 sharp bilinear, 2 epx

    public static PostSettings read(Config c) {
        PostSettings s = new PostSettings();
        boolean off = c.get("post.preset").equalsIgnoreCase("off");
        s.dof = !off && c.getBool("post.dof");
        s.dofTilt = c.get("post.dof.mode").equalsIgnoreCase("tiltshift");
        s.dofFocus = c.getFloat("post.dof.focus", 0);
        s.dofBand = Math.max(0.5f, c.getFloat("post.dof.band", 8));
        s.tsCenter = c.getFloat("post.dof.tsCenter", 0.5f);
        s.tsBand = Math.max(0.02f, c.getFloat("post.dof.tsBand", 0.35f));
        s.dofBlur = c.getFloat("post.dof.blur", 3);
        s.bloom = !off && c.getBool("post.bloom");
        s.bloomI = c.getFloat("post.bloom.intensity", 0.25f);
        s.bloomThr = c.getFloat("post.bloom.threshold", 1f);
        s.bloomRadius = c.getFloat("post.bloom.radius", 0.5f);
        s.ssao = !off && c.getBool("post.ssao");
        s.ssaoStr = c.getFloat("post.ssao.strength", 0.35f);
        s.ssaoRadius = c.getFloat("post.ssao.radius", 0.8f);
        s.tonemap = off ? 0 : c.getFloat("post.tonemap", 0.5f);
        s.exposure = off ? 1 : c.getFloat("post.exposure", 1f);
        s.contrast = off ? 1 : c.getFloat("post.contrast", 1f);
        s.saturation = off ? 1 : c.getFloat("post.saturation", 1f);
        s.temperature = off ? 0 : c.getFloat("post.temperature", 0f);
        s.tint = off ? 0 : c.getFloat("post.tint", 0f);
        s.grade = off ? "none" : c.get("post.grade").toLowerCase();
        s.gradeAmount = c.getFloat("post.grade.amount", 1f);
        s.lut = c.get("post.lut");
        s.vignette = off ? 0 : c.getFloat("post.vignette", 0f);
        s.grain = off ? 0 : c.getFloat("post.grain", 0f);
        s.ca = off ? 0 : c.getFloat("post.ca", 0f);
        s.sharpen = off ? 0 : c.getFloat("post.sharpen", 0f);
        s.fxaa = !off && c.getBool("post.fxaa");
        if (s.dofBlur <= 0.05f) s.dof = false;
        if (s.bloomI <= 0.001f) s.bloom = false;
        if (s.ssaoStr <= 0.001f) s.ssao = false;

        s.bloom2 = c.getBool("post2d.bloom") && c.getFloat("post2d.bloom.intensity", 0.3f) > 0.001f;
        s.bloom2I = c.getFloat("post2d.bloom.intensity", 0.3f);
        s.bloom2Thr = c.getFloat("post2d.bloom.threshold", 0.7f);
        s.grade2 = c.get("post2d.grade").toLowerCase();
        s.sat2 = c.getFloat("post2d.saturation", 1f);
        s.contrast2 = c.getFloat("post2d.contrast", 1f);
        s.vig2 = c.getFloat("post2d.vignette", 0f);
        s.grain2 = c.getFloat("post2d.grain", 0f);
        s.crt = c.getBool("post2d.crt");
        s.crtScan = c.getFloat("post2d.crt.scan", 0.45f);
        s.crtMask = c.getFloat("post2d.crt.mask", 0.15f);
        s.crtCurve = c.getFloat("post2d.crt.curve", 0.05f);
        String u = c.get("post2d.upscaler").toLowerCase();
        s.upscaler = u.equals("sharp") ? 1 : u.equals("epx") ? 2 : 0;
        return s;
    }

    /** True when the 3D scene needs the offscreen chain. */
    public boolean any3d() {
        return dof || bloom || ssao || tonemap > 0.001f || Math.abs(exposure - 1) > 0.001f || Math.abs(contrast - 1) > 0.001f
                || Math.abs(saturation - 1) > 0.001f || Math.abs(temperature) > 0.001f || Math.abs(tint) > 0.001f || !grade.equals("none")
                || vignette > 0.001f || grain > 0.001f || ca > 0.001f || sharpen > 0.001f || fxaa;
    }

    public boolean any2d() {
        return bloom2 || !grade2.equals("none") || Math.abs(sat2 - 1) > 0.001f || Math.abs(contrast2 - 1) > 0.001f || vig2 > 0.001f
                || grain2 > 0.001f || crt || upscaler != 0;
    }

    // ---- presets ----
    private static final String[] KEYS = {"post.dof", "post.dof.mode", "post.dof.focus", "post.dof.band", "post.dof.tsCenter", "post.dof.tsBand",
        "post.dof.blur", "post.bloom", "post.bloom.intensity", "post.bloom.threshold", "post.bloom.radius", "post.ssao", "post.ssao.strength",
        "post.ssao.radius", "post.tonemap", "post.exposure", "post.contrast", "post.saturation", "post.temperature", "post.tint", "post.grade",
        "post.grade.amount", "post.vignette", "post.grain", "post.ca", "post.sharpen", "post.fxaa"};
    private static final String[] SUBTLE = {"true", "depth", "0", "8", "0.5", "0.35", "3", "true", "0.25", "1.0", "0.5", "true", "0.35", "0.8",
        "0.5", "1.0", "1.04", "1.06", "0.05", "0", "none", "1.0", "0.2", "0.012", "0", "0.15", "false"};
    private static final String[] HD2D = {"true", "tiltshift", "0", "6", "0.47", "0.30", "12", "true", "0.55", "1.25", "0.7", "true", "0.6", "1.0",
        "0.7", "1.0", "1.12", "1.2", "0.15", "0", "octopath", "1.0", "0.42", "0.03", "0.15", "0.3", "false"};
    private static final String[] OFF = {"false", "depth", "0", "8", "0.5", "0.35", "3", "false", "0.25", "1.0", "0.5", "false", "0.35", "0.8",
        "0", "1.0", "1.0", "1.0", "0", "0", "none", "1.0", "0", "0", "0", "0", "false"};

    /** Writes the sliders of a preset (off | subtle | hd2d) into the config; "custom" only changes the label. */
    public static void applyPreset(Config c, String name) {
        String[] v = name.equals("off") ? OFF : name.equals("hd2d") ? HD2D : name.equals("subtle") ? SUBTLE : null;
        c.set("post.preset", name);
        if (v == null) return;
        for (int i = 0; i < KEYS.length; i++) c.set(KEYS[i], v[i]);
    }

    /** Grade preset lift / gamma / gain (display space), scaled to neutral by amount. */
    public static float[] gradeLGG(String g, float amount) {
        float[] lift = {0, 0, 0}, gamma = {1, 1, 1}, gain = {1, 1, 1};
        switch (g) {
            case "octopath": lift = new float[] {0.012f, 0.012f, 0.024f}; gain = new float[] {1.07f, 1.0f, 0.88f}; gamma = new float[] {1.04f, 1.0f, 0.96f}; break;
            case "snow": lift = new float[] {0f, 0.006f, 0.026f}; gain = new float[] {0.95f, 1.0f, 1.08f}; break;
            case "night": lift = new float[] {0f, 0.012f, 0.04f}; gain = new float[] {0.62f, 0.76f, 1.05f}; gamma = new float[] {1.1f, 1.05f, 0.95f}; break;
            default: break;
        }
        float[] out = new float[9];
        for (int i = 0; i < 3; i++) {
            out[i] = lift[i] * amount;
            out[3 + i] = 1 + (gamma[i] - 1) * amount;
            out[6 + i] = 1 + (gain[i] - 1) * amount;
        }
        return out;
    }
}
