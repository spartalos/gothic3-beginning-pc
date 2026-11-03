package gothic3.desktop.render3d.atmo;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector3;
import gothic3.platform.scene3d.MapSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * In-scene atmosphere of the 3D view (docs/render3d.md "Atmosphere"): weather particles, fire embers / smoke, dust motes, light shafts,
 * window glow, sprite rim light and shadow softness / tint uniforms, time-of-day tint, ground mist. All cosmetic: it reads only the
 * published scene (map snapshot, light positions), never game state, and animates on its own render clock. Render thread only.
 * Every effect that hits a GL / shader error disables itself (logged), the others keep running.
 */
public final class Atmosphere {
    // ---- settings (config atmo.*)
    private boolean enabled = true, snow = true, embers = true, smoke, dust = true, shafts = true, rim = true;
    private boolean softShadows = true, contact, mist, windowsGlow = true;
    private float snowDensity = 1f, snowSize = 1f, snowWind = 0.5f, emberDensity = 1f, emberSize = 1f, smokeDensity = 1f, dustDensity = 1f,
            shaftStrength = 0.5f, rimStrength = 0.5f, softness = 2.2f, tintAmt = 0.5f, glowStrength = 1f, cycleSec = 120f, mistDensity = 0.5f,
            mistHeight = 2.5f;
    private int tintColor = -1;
    // plant sway + changing snowfall (SWAY block)
    private boolean sway = true, swayFailed;
    private float swayStrength = 1f;
    private final SnowWeather weather3 = new SnowWeather();
    private float swayTime;
    private String tod = "off";

    private AtmoTable table = AtmoTable.load();
    private Particles particles;
    private Shafts shaftGeo;
    private boolean particlesFailed, shaftsFailed;
    private final long t0 = System.nanoTime();

    // ---- per map
    private String weather = "none";
    private boolean indoor;
    private int mapKey = -1;
    private MapSnapshot map;
    private final List<float[]> wins = new ArrayList<>();      // x0, x1, yb, yt, z
    private boolean winsBright;

    // ---- time of day outputs (identity when off)
    public float todAz, todEl, pointBoost = 1f, night;
    public final float[] sunMul = {1, 1, 1}, ambMul = {1, 1, 1}, fogMul = {1, 1, 1};
    /** +1: rim light on the screen's right side of sprites, -1 left (opposite of the sun). */
    private int rimSide = 1;

    private static final float[][] KEYS = {  // az, el, sun rgb x3, amb rgb x3, fog rgb x3, boost, night
        {205, 30, 0.22f, 0.32f, 0.62f, 0.42f, 0.50f, 0.82f, 0.30f, 0.36f, 0.62f, 2.3f, 1f},      // night
        {105, 15, 1.35f, 0.82f, 0.58f, 0.95f, 0.88f, 1.00f, 1.15f, 0.92f, 0.95f, 1.1f, 0f},     // morning
        {190, 64, 1.10f, 1.05f, 0.98f, 1.05f, 1.05f, 1.02f, 1.05f, 1.08f, 1.12f, 0.75f, 0f},    // noon
        {262, 10, 1.60f, 0.74f, 0.40f, 1.00f, 0.78f, 0.80f, 1.25f, 0.78f, 0.66f, 1.3f, 0f},     // evening
    };

    public void configure(Function<String, String> o) {
        enabled = !o.apply("atmo.enabled").equalsIgnoreCase("false");
        snow = on(o, "atmo.snow", true);
        snowDensity = f(o, "atmo.snow.density", 1f);
        snowSize = f(o, "atmo.snow.size", 1f);
        snowWind = f(o, "atmo.snow.wind", 0.5f);
        embers = on(o, "atmo.embers", true);
        emberDensity = f(o, "atmo.embers.density", 1f);
        emberSize = f(o, "atmo.embers.size", 1f);
        smoke = on(o, "atmo.smoke", false);
        smokeDensity = f(o, "atmo.smoke.density", 1f);
        dust = on(o, "atmo.dust", true);
        dustDensity = f(o, "atmo.dust.density", 1f);
        shafts = on(o, "atmo.shafts", true);
        shaftStrength = f(o, "atmo.shafts.strength", 0.5f);
        rim = on(o, "atmo.rim", true);
        rimStrength = f(o, "atmo.rim.strength", 0.5f);
        softShadows = on(o, "atmo.softShadows", true);
        softness = f(o, "atmo.shadow.softness", 2.2f);
        tintAmt = f(o, "atmo.shadow.tint", 0.5f);
        contact = on(o, "atmo.shadow.contact", false);
        String tc = o.apply("atmo.shadow.tintColor").replace("#", "").trim();
        tintColor = -1;
        if (tc.length() == 6) {
            try { tintColor = Integer.parseInt(tc, 16); } catch (NumberFormatException ignored) { }
        }
        tod = o.apply("atmo.tod").toLowerCase();
        if (tod.isEmpty()) tod = "off";
        cycleSec = Math.max(10f, f(o, "atmo.tod.cycle", 120f));
        windowsGlow = on(o, "atmo.tod.windowGlow", true);
        glowStrength = f(o, "atmo.tod.glow", 1f);
        sway = on(o, "atmo.sway", true);                                  // SWAY
        swayStrength = f(o, "atmo.sway.strength", 1f);
        weather3.varying = !o.apply("atmo.snow.weather").equalsIgnoreCase("steady");
        weather3.period = Math.max(8f, f(o, "atmo.snow.weather.period", 90f));
        if (!o.apply("atmo.snow.weather.seed").equals(lastSeed)) { lastSeed = o.apply("atmo.snow.weather.seed"); weather3.seed(lastSeed); }
        mist = on(o, "atmo.mist", false);
        mistDensity = f(o, "atmo.mist.density", 0.5f);
        mistHeight = Math.max(0.3f, f(o, "atmo.mist.height", 2.5f));
    }

    private static boolean on(Function<String, String> o, String k, boolean def) {
        String v = o.apply(k);
        return v.isEmpty() ? def : v.equalsIgnoreCase("true");
    }

    private static float f(Function<String, String> o, String k, float def) {
        try { return Float.parseFloat(o.apply(k)); } catch (NumberFormatException e) { return def; }
    }

    /** F6: re-read the data table. */
    public void reloadTable() {
        table = AtmoTable.load();
        System.err.println("[render3d] atmosphere table: " + table.source);
        map = null;   // weather and windows are re-derived by the next setMap
    }

    private String lastSeed = "\0";

    /** Plant sway is active this frame (3D view, outdoors, sway on, wind above zero, shader variant compiled). */
    public boolean swayOn() { return enabled && sway && !indoor && !swayFailed && swayStrength > 0f && snowWind > 0f; }

    /** Called when the sway shader variant did not compile: the effect stays off for the session. */
    public void swayFailed() { swayFailed = true; }

    /** Sets the sway uniforms of a bound vertex shader (main or depth): same values in both passes, so shadows follow the plants. */
    public void swayUniforms(ShaderProgram sh) {
        float wn = (float) Math.sqrt(0.9 * 0.9 + 0.35 * 0.35);
        float wf = Math.min(2.5f, snowWind / 0.5f);
        sh.setUniformf("u_swA", 0.9f / wn, 0.35f / wn, 0.05f * swayStrength * wf, swayTime);
        sh.setUniformf("u_swG", weather3.gust, weather3.lean);
    }

    /** Render clock in seconds (wraps hourly; cosmetic only). */
    public float time() { return (float) ((System.nanoTime() - t0) / 1e9 % 3600.0); }

    // ------------------------------------------------------------------------------------------------------ per map

    public void setMap(MapSnapshot m, boolean indoorMap) {
        if (m == map && indoorMap == indoor) return;
        map = m;
        indoor = indoorMap;
        weather = table.weather(m.mapId, m.tileset0, m.tileset1);
        if (indoorMap && weather.equals("none")) weather = "dust";
        wins.clear();
        winsBright = false;
        AtmoTable.WindowSet ws = table.windows(m.tileset1);
        if (ws == null || m.sheet1 == null || ws.ids.length == 0) return;
        winsBright = ws.bright;
        java.util.Map<Integer, float[]> rects = new java.util.HashMap<>();
        for (int id : ws.ids) {
            float[] r = windowRect(m, id, ws.bright);
            if (r != null) rects.put(id, r);
        }
        if (rects.isEmpty()) return;
        for (int y = 0; y < m.h; y++) {
            for (int x = 0; x < m.w; x++) {
                int cell = m.cells[y * m.w + x];
                int o = cell >> 12 & 7;
                for (int guard = 0; o != 7 && guard < 8; guard++) {
                    int r = y - o;
                    if (r < 0) break;
                    int rc = m.cells[r * m.w + x];
                    int tid = (byte) (rc >> 16 & 0xFF);
                    float[] rr = tid >= 0 ? rects.get(tid) : null;
                    if (rr != null && wins.size() < 400) wins.add(new float[] {x + rr[0], x + rr[1], o + rr[2], o + rr[3], y + 1f});
                    o = rc >> 28 & 7;
                }
            }
        }
        System.err.println("[render3d] atmosphere: weather " + weather + ", " + wins.size() + " windows (" + (ws.bright ? "bright" : "dark") + ")");
    }

    /** Bounding box (x0, x1, yb, yt in tile units, y up from the tile's bottom) of the bright / dark opening pixels of a tile, or null. */
    private static float[] windowRect(MapSnapshot m, int id, boolean bright) {
        int tw = m.tileW, th = m.tileH;
        int[] sp = m.sheet1.getPixelsInternal();
        int sx = id % m.cols1 * tw, sy = id / m.cols1 % m.rows1 * th, sw = m.sheet1.getWidth();
        if (sx + tw > sw || sy + th > m.sheet1.getHeight()) return null;
        int x0 = tw, x1 = -1, y0 = th, y1 = -1, n = 0;
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                int p = sp[(sy + y) * sw + sx + x];
                if ((p >>> 24) < 128) continue;
                float l = 0.299f * (p >> 16 & 255) + 0.587f * (p >> 8 & 255) + 0.114f * (p & 255);
                if (bright ? l >= 165 : l <= 20) {
                    n++;
                    x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y);
                }
            }
        }
        if (n < 6) return null;
        return new float[] {x0 / (float) tw, (x1 + 1) / (float) tw, 1f - (y1 + 1) / (float) th, 1f - y0 / (float) th};
    }

    // ------------------------------------------------------------------------------------------------------ time of day

    /** Updates the time-of-day outputs for this frame (identity when off). */
    public void update(float baseAz, float baseEl) {
        long ns = System.nanoTime() - t0;                                // SWAY / weather clock: continuous
        swayTime = (float) (ns / 1e9);
        weather3.update(ns / 1e9, snowWind);
        night = 0;
        pointBoost = 1;
        todAz = baseAz;
        todEl = baseEl;
        for (int i = 0; i < 3; i++) sunMul[i] = ambMul[i] = fogMul[i] = 1f;
        if (!enabled || tod.equals("off") || tod.equals("false")) return;
        float[] k = new float[KEYS[0].length];
        switch (tod) {
            case "night": System.arraycopy(KEYS[0], 0, k, 0, k.length); break;
            case "morning": System.arraycopy(KEYS[1], 0, k, 0, k.length); break;
            case "noon": System.arraycopy(KEYS[2], 0, k, 0, k.length); break;
            case "evening": System.arraycopy(KEYS[3], 0, k, 0, k.length); break;
            case "cycle": {
                float ph = (0.2f + time() / cycleSec) % 1f * 4f;   // night, morning, noon, evening, night
                int a = (int) ph % 4, b = (a + 1) % 4;
                float t = ph - (int) ph;
                t = t * t * (3 - 2 * t);
                for (int i = 0; i < k.length; i++) k[i] = KEYS[a][i] + (KEYS[b][i] - KEYS[a][i]) * t;
                break;
            }
            default: return;
        }
        todAz = k[0];
        todEl = Math.max(6f, k[1]);
        for (int i = 0; i < 3; i++) { sunMul[i] = k[2 + i]; ambMul[i] = k[5 + i]; fogMul[i] = k[8 + i]; }
        pointBoost = k[11];
        night = k[12];
    }

    // ------------------------------------------------------------------------------------------------------ main shader uniforms

    /**
     * Soft / coloured shadow, ground mist and time uniforms of the main shader (bound). {@code lit} false (world UI pass) switches
     * everything off. {@code fog} = light level rgb (tints the mist).
     */
    public void mainUniforms(ShaderProgram sh, boolean lit, boolean indoorMap, float[] fog, float sunX) {
        sh.setUniformf("u_time", time());
        sh.setUniformf("u_rim", 0f);
        if (!lit || !enabled) {
            sh.setUniformf("u_soft", 0f);
            sh.setUniformf("u_shTint", 1f, 1f, 1f);
            sh.setUniformf("u_contact", 0f);
            sh.setUniformf("u_hf", 0f, 0f, 0f);
            return;
        }
        sh.setUniformf("u_soft", softShadows ? Math.max(0.5f, softness) : 0f);
        sh.setUniformf("u_contact", contact ? 1f : 0f);
        float tr, tg, tb;
        if (tintColor >= 0) {
            float r = (tintColor >> 16 & 255) / 255f + 0.02f, g = (tintColor >> 8 & 255) / 255f + 0.02f, b = (tintColor & 255) / 255f + 0.02f;
            float avg = (r + g + b) / 3f;
            tr = r / avg * 0.92f; tg = g / avg * 0.92f; tb = b / avg * 0.92f;
        } else if (indoorMap) {
            tr = 1.0f; tg = 0.86f; tb = 0.66f;      // warm dark
        } else {
            tr = 0.68f; tg = 0.82f; tb = 1.16f;     // bluish
        }
        float a = tintAmt;
        sh.setUniformf("u_shTint", 1f + (tr - 1f) * a, 1f + (tg - 1f) * a, 1f + (tb - 1f) * a);
        boolean m = mist && !indoorMap;
        sh.setUniformf("u_hf", m ? mistDensity * 0.8f : 0f, 1f / mistHeight, 0f);
        sh.setUniformf("u_mistCol", Math.min(1f, fog[0] * 0.85f + 0.02f), Math.min(1f, fog[1] * 0.9f + 0.04f), Math.min(1f, fog[2] * 0.95f + 0.08f));
        rimSide = sunX > 0.04f ? -1 : 1;
    }

    /** Rim light on/off for the following sprite draws (upright cards). Returns the side (+1 right / -1 left) for the sprite layer, 0 = off. */
    public int rimUniforms(ShaderProgram sh, boolean on, boolean indoorMap) {
        if (!on || !enabled || !rim || rimStrength <= 0f) {
            sh.setUniformf("u_rim", 0f);
            return 0;
        }
        sh.setUniformf("u_rim", rimStrength);
        float k = 1f - 0.35f * night;
        if (indoorMap) sh.setUniformf("u_rimCol", 1.0f * k, 0.74f * k, 0.46f * k);
        else sh.setUniformf("u_rimCol", (0.78f + 0.22f * sunMul[0] * 0.5f) * k, 0.90f * k, 1.0f * k);
        return rimSide;
    }

    public float rimStrength() { return enabled && rim ? rimStrength : 0f; }

    // ------------------------------------------------------------------------------------------------------ drawing

    private final Vector3 right = new Vector3(), up = new Vector3();
    private final float[] dlp = new float[Particles.LIGHTS * 4];
    private final float[] elp = new float[Particles.LIGHTS * 4];
    private final float[] p0 = new float[3], p1 = new float[3], p2 = new float[3], p3 = new float[3];

    /**
     * Draws shafts, window glows and particles into the currently bound scene target (depth tested, no depth write, blend on). Leaves the
     * blend func as (SRC_ALPHA, ONE_MINUS_SRC_ALPHA); the caller re-binds its own shader.
     *
     * @param lightPos fire point lights (x, y, z, radius) as selected for this frame
     * @param light3 ambient + sun light level rgb (to tint snow and smoke)
     */
    public void draw(PerspectiveCamera cam, float cx, float cz, float[] lightPos, int nLights, float[] fog3, float fogStart, float fogEnd,
                     Vector3 sunDir, boolean indoorMap, float[] light3) {
        if (!enabled) return;
        float time = time();
        right.set(cam.direction).crs(cam.up).nor();
        up.set(right).crs(cam.direction).nor();
        if (up.y < 0) up.scl(-1);
        boolean wantShafts = shafts && shaftStrength > 0f && !shaftsFailed;
        if (wantShafts || (windowsGlow && !shaftsFailed)) {
            try {
                if (shaftGeo == null) { shaftGeo = new Shafts(); shaftGeo.init(); }
                drawShafts(cam, cx, cz, fogStart, fogEnd, sunDir, indoorMap, time, wantShafts);
                glCheck("shafts");
            } catch (Throwable t) {
                System.err.println("[render3d] atmosphere: shafts disabled: " + t);
                shaftsFailed = true;
            }
        }
        boolean needParticles = (weather.equals("snow") && snow) || (weather.equals("dust") && dust) || ((embers || smoke) && nLights > 0);
        if (needParticles && !particlesFailed) {
            try {
                if (particles == null) { particles = new Particles(); particles.init(); }
                drawParticles(cam, cx, cz, lightPos, nLights, fogStart, fogEnd, time, light3);
                glCheck("particles");
            } catch (Throwable t) {
                System.err.println("[render3d] atmosphere: particles disabled: " + t);
                particlesFailed = true;
            }
        }
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void glCheck(String what) {
        int e = Gdx.gl.glGetError();
        if (e != GL20.GL_NO_ERROR) throw new IllegalStateException("GL error 0x" + Integer.toHexString(e) + " drawing " + what);
    }

    private void drawParticles(PerspectiveCamera cam, float cx, float cz, float[] lightPos, int nLights, float fogS, float fogE, float time, float[] light3) {
        particles.begin(cam.combined, right.x, right.y, right.z, up.x, up.y, up.z, time, fogS, fogE);
        float lr = Math.min(1f, light3[0] * 1.15f + 0.12f), lg = Math.min(1f, light3[1] * 1.15f + 0.12f), lb = Math.min(1f, light3[2] * 1.15f + 0.12f);
        int n = Math.min(nLights, Particles.LIGHTS);
        for (int i = 0; i < n; i++) {
            elp[i * 4] = lightPos[i * 4]; elp[i * 4 + 1] = lightPos[i * 4 + 1]; elp[i * 4 + 2] = lightPos[i * 4 + 2]; elp[i * 4 + 3] = lightPos[i * 4 + 3];
        }
        if (weather.equals("snow") && snow) {
            if (!weather3.varying) particles.drawSnow(cx, cz - 5f, 5500f * snowDensity, snowSize, snowWind, lr, lg, lb, 0.9f, 0f, 0f);
            else {   // changing snowfall: flake count and opacity follow the weather level, gusts push the flakes (drift)
                float L = weather3.level;
                float fade = L <= 0f ? 0f : Math.min(1f, L / 0.08f);
                float a = Math.min(1f, 0.35f + 1.3f * L) * fade * 0.9f;
                particles.drawSnow(cx, cz - 5f, 5500f * snowDensity * L, snowSize * (1f + 0.12f * (Math.min(L, 1.7f) - 1f)), snowWind, lr, lg, lb, a,
                        (float) weather3.driftX, (float) weather3.driftZ);
            }
        }
        if (weather.equals("dust") && dust) {
            int k = 0;
            for (; k < Math.min(n, 12); k++) {
                dlp[k * 4] = lightPos[k * 4]; dlp[k * 4 + 1] = lightPos[k * 4 + 1]; dlp[k * 4 + 2] = lightPos[k * 4 + 2]; dlp[k * 4 + 3] = Math.max(3f, lightPos[k * 4 + 3] * 0.9f);
            }
            if (winsBright) {   // window shafts light the dust near them
                for (float[] w : wins) {
                    if (k >= Particles.LIGHTS) break;
                    if (Math.abs(w[0] - cx) > 16 || Math.abs(w[4] - cz) > 14) continue;
                    dlp[k * 4] = (w[0] + w[1]) * 0.5f; dlp[k * 4 + 1] = (w[2] + w[3]) * 0.5f - 0.6f; dlp[k * 4 + 2] = w[4] + 0.8f; dlp[k * 4 + 3] = 3.2f;
                    k++;
                }
            }
            particles.drawDust(cx, cz, 900f * dustDensity, 1.5f, dlp, k, 1.0f, 0.86f - 0.2f * night, 0.62f + 0.3f * night, 0.55f);
        }
        if (embers && n > 0) particles.drawEmbers(elp, n, emberDensity, snowWind, 0.95f, emberSize);
        if (smoke && n > 0) particles.drawSmoke(elp, n, smokeDensity, snowWind, 0.16f, lr * 0.55f, lg * 0.55f, lb * 0.6f);
    }

    private void drawShafts(PerspectiveCamera cam, float cx, float cz, float fogS, float fogE, Vector3 sunDir, boolean indoorMap, float time, boolean beams) {
        shaftGeo.begin(cam.combined, time, fogS, fogE);
        float nt = night;
        if (beams && indoorMap && winsBright) {
            float cr = 1.0f + (0.45f - 1.0f) * nt, cg = 0.90f + (0.6f - 0.90f) * nt, cb = 0.66f + (1.0f - 0.66f) * nt;
            float a = 0.62f * shaftStrength * (1f - 0.45f * nt);
            float dx = 0.10f, dy = -0.62f, dz = 0.78f;
            for (float[] w : wins) {
                if (Math.abs((w[0] + w[1]) * 0.5f - cx) > 24 || Math.abs(w[4] - cz) > 24) continue;
                float wx = w[1] - w[0];
                for (int s = 0; s < 3; s++) {
                    float y = w[3] + (w[2] - w[3]) * (s / 2f);
                    float t = y / -dy;
                    float sp = 0.12f + 0.05f * s;
                    set(p0, w[0], y, w[4]); set(p1, w[1], y, w[4]);
                    set(p3, w[0] - wx * sp + dx * t, 0.02f, w[4] + dz * t); set(p2, w[1] + wx * sp + dx * t, 0.02f, w[4] + dz * t);
                    shaftGeo.quad(p0, p1, p2, p3, cr, cg, cb, a * (s == 1 ? 1f : 0.7f));
                }
            }
            shaftGeo.flush(0);
        } else if (beams && !indoorMap) {
            // sun beams in a world grid around the focus, soft, slowly pulsing
            float ey = Math.max(0.2f, sunDir.y);
            float cr = 1.0f + (0.5f - 1.0f) * nt, cg = 0.93f + (0.65f - 0.93f) * nt, cb = 0.78f + (1.0f - 0.78f) * nt;
            float hx = -sunDir.z, hz = sunDir.x, hl = (float) Math.sqrt(hx * hx + hz * hz);
            if (hl < 1e-3f) { hx = 1; hz = 0; hl = 1; }
            hx /= hl; hz /= hl;
            float len = 11f / ey;
            int gx0 = (int) Math.floor((cx - 22f) / 7f), gx1 = (int) Math.floor((cx + 22f) / 7f);
            int gz0 = (int) Math.floor((cz - 24f) / 7f), gz1 = (int) Math.floor((cz + 14f) / 7f);
            for (int gx = gx0; gx <= gx1; gx++) {
                for (int gz = gz0; gz <= gz1; gz++) {
                    int h = (gx * 73856093) ^ (gz * 19349663);
                    h ^= h >>> 13; h *= 0x5bd1e995; h ^= h >>> 15;
                    float h1 = (h & 255) / 255f, h2 = (h >> 8 & 255) / 255f, h3 = (h >> 16 & 255) / 255f, h4 = (h >>> 24) / 255f;
                    if (h1 > 0.5f) continue;
                    float gxp = (gx + h2) * 7f, gzp = (gz + h3) * 7f, w = 1.1f + 1.8f * h4;
                    float a = 0.26f * shaftStrength * (0.55f + 0.45f * (float) Math.sin(time * 0.22 + h * 0.001)) * (1f - 0.5f * nt);
                    float tx = gxp + sunDir.x * len, ty = sunDir.y * len, tz = gzp + sunDir.z * len;
                    set(p0, tx - hx * w * 0.5f, ty, tz - hz * w * 0.5f); set(p1, tx + hx * w * 0.5f, ty, tz + hz * w * 0.5f);
                    set(p2, gxp + hx * w * 0.8f, 0.02f, gzp + hz * w * 0.8f); set(p3, gxp - hx * w * 0.8f, 0.02f, gzp - hz * w * 0.8f);
                    shaftGeo.quad(p0, p1, p2, p3, cr, cg, cb, a);
                }
            }
            shaftGeo.flush(0);
        }
        // window glow at night (dark window openings of exteriors)
        if (windowsGlow && !indoorMap && !winsBright && nt > 0.05f && !wins.isEmpty()) {
            for (float[] w : wins) {
                if (Math.abs((w[0] + w[1]) * 0.5f - cx) > 30 || Math.abs(w[4] - cz) > 30) continue;
                shaftGeo.glow(w[0], w[1], w[2], w[3], w[4] + 0.03f, 1.0f, 0.70f, 0.32f, 0.85f * nt * glowStrength);
            }
            shaftGeo.flush(1);
        }
    }

    private static void set(float[] p, float x, float y, float z) { p[0] = x; p[1] = y; p[2] = z; }

    /** Window openings of the current map (x0, x1, yb, yt, z), for the extra light sources. */
    public List<float[]> windows() { return wins; }

    public boolean windowsBright() { return winsBright; }

    /** Windows found on the current map (for tests / the overlay). */
    public int windowCount() { return wins.size(); }

    public String weather() { return weather; }

    /** SNOW: current snowfall level (0 clear, 0.3 light, 1 normal, 1.7 heavy; 1 when steady or not a snow map): damps the snow sparkle. */
    public float snowLevel() { return weather.equals("snow") && snow && weather3.varying ? weather3.level : 1f; }

    public void dispose() {
        if (particles != null) particles.dispose();
        if (shaftGeo != null) shaftGeo.dispose();
        particles = null;
        shaftGeo = null;
    }
}
