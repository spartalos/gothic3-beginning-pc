package gothic3.desktop.render3d.props;

import gothic3.platform.scene3d.MapSnapshot;
import gothic3.platform.scene3d.Scene3D;
import java.io.File;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Extra point light sources (wall lamps, candles, windows) besides the fire sprites, table driven by {@code render3d/lights.properties}.
 * Collects static lights per map (lamp tiles of layer 1, window openings found by the atmosphere), per frame the lights of sprites with an
 * entry in the table (candles on the altar), merges them with the fire lights that {@link PropLayer} collected, picks the nearest ones to
 * the camera focus and fades lights in / out when the selection changes (no popping). Purely cosmetic, render thread only, own clock.
 *
 * <pre>
 * preset.NAME = RRGGBB:radius:intensity:flicker           colour, radius (tiles), intensity, flicker amount (0 = steady)
 * tsN.TILE    = preset:fx:fy:dz                           tile light: fx / fy = position inside the tile (0..1, y from the top), dz = tiles towards the room
 * iN[@x,y,w,h]= preset:px:py:height ...                   sprite light(s): pixel in the sprite, height above the floor (tiles)
 * win.bright / win.bright.night / win.dark.night = RRGGBB:radius:intensity   windows (cool by day, warm at night)
 * </pre>
 */
public final class LightSources {
    public static final int MAX_OUT = 24;
    private static final boolean DBG = Boolean.getBoolean("g3.render3d.lightlog");
    private int dbgN;

    private static final class Preset { int rgb = 0xFFA040; float radius = 3f, intensity = 1f, flicker = 0.5f; }

    private static final class Light {
        long key;
        float x, y, z, radius, r, g, b, flick, phase, alpha;
        int kind;   // 0 fire, 1 lamp / candle, 2 window
        boolean sel;
    }

    private static final class TileDef { Preset p; float fx = 0.5f, fy = 0f, dz = 0.3f; }
    private static final class PropDef { Preset p; float px, py, height; }

    // config
    public boolean on = true;
    private float strength = 1f, flicker = 1f;

    // table
    private final Map<String, Preset> presets = new HashMap<>();
    private final Map<Integer, TileDef> tiles = new HashMap<>();
    private final Map<String, List<PropDef>> props = new HashMap<>();
    private Preset winBright = new Preset(), winBrightNight = new Preset(), winDark = new Preset();
    private String source = "built-in defaults";

    // per map
    private MapSnapshot map;
    private final List<Light> stat = new ArrayList<>();
    private List<float[]> wins = new ArrayList<>();
    private boolean winsBright;
    private boolean instant = true;
    private float instantUntil = -1f, fadeSec = 0.45f;

    // per prepare / frame
    private final List<Light> propLights = new ArrayList<>();
    private final Map<Long, Light> state = new HashMap<>();
    private float lastT = -1f;
    private float night;
    private boolean indoor;

    public LightSources() {
        winBright.rgb = 0x9DB4E6; winBright.radius = 3f; winBright.intensity = 0.3f; winBright.flicker = 0;
        winBrightNight.rgb = 0xFFB464; winBrightNight.radius = 3f; winBrightNight.intensity = 0.35f; winBrightNight.flicker = 0;
        winDark.rgb = 0xFFAA55; winDark.radius = 2.8f; winDark.intensity = 0.7f; winDark.flicker = 0;
        load();
    }

    public String source() { return source; }

    public void configure(java.util.function.Function<String, String> o) {
        on = !o.apply("atmo.lights").equalsIgnoreCase("false") && !o.apply("atmo.enabled").equalsIgnoreCase("false");
        strength = parseF(o.apply("atmo.lights.strength"), 1f);
        flicker = parseF(o.apply("atmo.lights.flicker"), 1f);
        fadeSec = Math.max(0.01f, parseF(o.apply("atmo.lights.fade"), 0.45f));   // dev: seconds to fade a light in / out
    }

    private static float parseF(String s, float d) {
        try { return Float.parseFloat(s.trim()); } catch (RuntimeException e) { return d; }
    }

    /** (Re)reads the table (source checkout file first, else the classpath). */
    public void load() {
        Properties pr = new Properties();
        File f = new File("game/src/main/resources/render3d/lights.properties");
        try {
            if (f.isFile()) {
                try (Reader r = java.nio.file.Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) { pr.load(r); }
                source = f.getPath();
            } else {
                try (InputStream in = LightSources.class.getClassLoader().getResourceAsStream("render3d/lights.properties")) {
                    if (in != null) {
                        try (Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) { pr.load(r); }
                        source = "classpath render3d/lights.properties";
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[render3d] lights table not readable: " + e);
        }
        presets.clear();
        tiles.clear();
        props.clear();
        for (String k : pr.stringPropertyNames()) {
            String v = clean(pr.getProperty(k));
            k = k.trim();
            try {
                if (k.startsWith("preset.")) presets.put(k.substring(7), preset(v));
            } catch (RuntimeException e) { System.err.println("[render3d] lights: bad entry " + k + ": " + e); }
        }
        for (String k : pr.stringPropertyNames()) {
            String v = clean(pr.getProperty(k));
            k = k.trim();
            try {
                if (k.equals("win.bright")) winBright = preset(v);
                else if (k.equals("win.bright.night")) winBrightNight = preset(v);
                else if (k.equals("win.dark.night")) winDark = preset(v);
                else if (k.startsWith("ts")) {
                    int dot = k.indexOf('.');
                    String[] a = v.split(":");
                    TileDef t = new TileDef();
                    t.p = presets.get(a[0]);
                    if (t.p == null) continue;
                    if (a.length > 1) t.fx = Float.parseFloat(a[1]);
                    if (a.length > 2) t.fy = Float.parseFloat(a[2]);
                    if (a.length > 3) t.dz = Float.parseFloat(a[3]);
                    tiles.put(Integer.parseInt(k.substring(2, dot)) << 8 | Integer.parseInt(k.substring(dot + 1)), t);
                } else if (k.startsWith("i")) {
                    List<PropDef> l = new ArrayList<>();
                    for (String tok : v.split("\\s+")) {
                        String[] a = tok.split(":");
                        PropDef d = new PropDef();
                        d.p = presets.get(a[0]);
                        if (d.p == null) continue;
                        d.px = Float.parseFloat(a[1]);
                        d.py = Float.parseFloat(a[2]);
                        d.height = a.length > 3 ? Float.parseFloat(a[3]) : 0.6f;
                        l.add(d);
                    }
                    props.put(k, l);
                }
            } catch (RuntimeException e) { System.err.println("[render3d] lights: bad entry " + k + ": " + e); }
        }
        map = null;
        state.clear();
        instant = true;
    }

    private static String clean(String v) {
        int c = v.indexOf('#');
        return (c >= 0 ? v.substring(0, c) : v).trim();
    }

    private static Preset preset(String v) {
        String[] a = v.split(":");
        Preset p = new Preset();
        p.rgb = Integer.parseInt(a[0], 16);
        if (a.length > 1) p.radius = Float.parseFloat(a[1]);
        if (a.length > 2) p.intensity = Float.parseFloat(a[2]);
        if (a.length > 3) p.flicker = Float.parseFloat(a[3]);
        return p;
    }

    private static Light make(int kind, Preset p, float x, float y, float z) {
        Light l = new Light();
        l.kind = kind;
        l.x = x; l.y = y; l.z = z;
        l.radius = p.radius;
        l.r = (p.rgb >> 16 & 255) / 255f * p.intensity;
        l.g = (p.rgb >> 8 & 255) / 255f * p.intensity;
        l.b = (p.rgb & 255) / 255f * p.intensity;
        l.flick = p.flicker;
        l.phase = (x * 7.13f + z * 3.71f + y * 1.9f) % 6.283f;
        l.key = key(x, y, z);
        return l;
    }

    private static long key(float x, float y, float z) {
        return ((long) Math.round(x * 16) & 0xFFFFF) << 40 | ((long) Math.round(y * 16) & 0xFFFFF) << 20 | ((long) Math.round(z * 16) & 0xFFFFF);
    }

    // ------------------------------------------------------------------------------------------ per map / per prepare

    /** Per frame environment from the renderer: current map, indoor flag, night factor (0..1), the atmosphere's window openings. */
    public void env(MapSnapshot m, boolean indoorMap, float nightFactor, List<float[]> windows, boolean bright) {
        night = nightFactor;
        indoor = indoorMap;
        if (m == map) return;
        map = m;
        state.clear();
        instant = true;
        stat.clear();
        wins = new ArrayList<>(windows);
        winsBright = bright;
        if (m == null) return;
        // lamp tiles of layer 1 (same column walk as MapMesh: the tile of the base row cell stands `o` rows high)
        if (!tiles.isEmpty()) {
            for (int y = 0; y < m.h; y++) {
                for (int x = 0; x < m.w; x++) {
                    int o = m.cells[y * m.w + x] >> 12 & 7;
                    for (int guard = 0; o != 7 && guard < 8; guard++) {
                        int r = y - o;
                        if (r < 0) break;
                        int rc = m.cells[r * m.w + x];
                        int tid = (byte) (rc >> 16 & 0xFF);
                        TileDef t = tid >= 0 ? tiles.get(m.tileset1 << 8 | tid) : null;
                        if (t != null && stat.size() < 300) stat.add(make(1, t.p, x + t.fx, o + 1f - t.fy, y + 1f + t.dz));
                        o = rc >> 28 & 7;
                    }
                }
            }
        }
        System.err.println("[render3d] extra lights: " + stat.size() + " lamp tiles, " + wins.size() + " windows");
    }

    public void beginProps() { propLights.clear(); }

    /** Lights of a captured draw whose sprite has an entry in the table (candles). */
    public void propLights(String id, Scene3D s, int o, int xs, int ys, int w, int h, int tr, float T) {
        if (!on || id == null || props.isEmpty()) return;
        List<PropDef> l = props.get(id + "@" + xs + "," + ys + "," + w + "," + h);
        if (l == null) l = props.get(id);
        if (l == null) return;
        for (PropDef d : l) {
            float px = (tr & 2) != 0 ? w - 1 - d.px : d.px;
            float x = s.d[o + 8] / T + (s.d[o + 6] + px + 0.5f) / T;
            float z = s.d[o + 9] / T + (s.d[o + 7] + d.py) / T + 0.2f;
            if (propLights.size() < 200) propLights.add(make(1, d.p, x, d.height, z));
            if (DBG && dbgN % 200 == 0) System.err.println("[lights] sprite light " + id + " at " + x + ", " + d.height + ", " + z);
        }
    }

    // ------------------------------------------------------------------------------------------ selection

    /**
     * Merges the fire lights {@code fire} (stride 8: x,y,z,radius,r,g,b,phase) with the extra ones, selects the nearest {@code max}, fades and
     * writes the uniform arrays. Fire lights come first in the output ({@code fireCount[0]} of them) so embers stay on fires only.
     * @return the number of lights written
     */
    public int select(float[] fire, int fireN, float cx, float cz, float strengthIn, float lightIntensity, float t, int max,
                      float[] lightPos, float[] lightCol, int[] fireCount) {
        List<Light> cand = new ArrayList<>(fireN + stat.size() + propLights.size() + wins.size());
        for (int i = 0; i < fireN; i++) {
            Light l = new Light();
            int p = i * 8;
            l.kind = 0;
            l.x = fire[p]; l.y = fire[p + 1]; l.z = fire[p + 2]; l.radius = fire[p + 3];
            l.r = fire[p + 4]; l.g = fire[p + 5]; l.b = fire[p + 6]; l.phase = fire[p + 7];
            l.flick = 1f;
            l.key = key(l.x, l.y, l.z);
            cand.add(l);
        }
        cand.addAll(stat);
        cand.addAll(propLights);
        addWindows(cand);
        float dt = lastT < 0 || t < lastT ? 0.016f : Math.min(0.1f, t - lastT);
        lastT = t;
        // nearest first
        final float fx = cx, fz = cz;
        float[] dd = new float[cand.size()];
        Integer[] ix = new Integer[cand.size()];
        for (int i = 0; i < ix.length; i++) {
            Light l = cand.get(i);
            float dx = l.x - fx, dz = l.z - fz;
            dd[i] = dx * dx + dz * dz;
            ix[i] = i;
        }
        java.util.Arrays.sort(ix, (a, b) -> Float.compare(dd[a], dd[b]));
        for (Light l : state.values()) l.sel = false;
        int n = Math.min(max, ix.length);
        for (int j = 0; j < n; j++) {
            Light c = cand.get(ix[j]);
            Light e = state.get(c.key);
            if (e == null) { e = c; e.alpha = instant || t < instantUntil ? 1f : 0f; state.put(c.key, e); }
            else { float a = e.alpha; c.alpha = a; state.put(c.key, c); e = c; }
            e.sel = true;
        }
        if (instant) { instantUntil = t + 0.6f; instant = false; }
        float step = dt / fadeSec;
        List<Light> out = new ArrayList<>(state.size());
        for (java.util.Iterator<Light> it = state.values().iterator(); it.hasNext();) {
            Light l = it.next();
            l.alpha = l.sel ? Math.min(1f, l.alpha + step) : l.alpha - step;
            if (l.alpha <= 0f) { it.remove(); continue; }
            out.add(l);
        }
        out.sort((a, b) -> a.kind != b.kind ? Integer.compare(a.kind == 0 ? 0 : 1, b.kind == 0 ? 0 : 1) : Float.compare(b.alpha, a.alpha));
        int c = Math.min(MAX_OUT, out.size()), nf = 0;
        for (int j = 0; j < c; j++) {
            Light l = out.get(j);
            if (l.kind == 0) nf++;
            float a = l.alpha * l.alpha * (3f - 2f * l.alpha);
            float fa = flicker * (l.kind == 0 ? 1f : l.flick);
            float fl = 1f + fa * (0.10f * (float) Math.sin(t * 11.3 + l.phase) + 0.06f * (float) Math.sin(t * 23.1 + l.phase * 1.9)
                    + 0.04f * (float) Math.sin(t * 5.3 + l.phase * 0.6));
            float k = strengthIn * lightIntensity * fl * a * (l.kind == 0 ? 1f : strength);
            int q = j * 4, u = j * 3;
            lightPos[q] = l.x; lightPos[q + 1] = l.y; lightPos[q + 2] = l.z;
            lightPos[q + 3] = l.radius * (0.97f + 0.03f * fl);
            lightCol[u] = l.r * k; lightCol[u + 1] = l.g * k; lightCol[u + 2] = l.b * k;
        }
        if (DBG && ++dbgN % 60 == 0) { StringBuilder sb = new StringBuilder("[lights] " + c + " fire " + nf + " cand " + cand.size() + " dt " + dt); for (int j = 0; j < c; j++) sb.append(String.format(" [%d a%.2f]", out.get(j).kind, out.get(j).alpha)); System.err.println(sb); }
        fireCount[0] = nf;
        return c;
    }

    private void addWindows(List<Light> cand) {
        if (wins.isEmpty()) return;
        Preset p;
        float k;
        if (winsBright) {   // interior: cool daylight by day, warm glow at night
            float nn = Math.max(0f, Math.min(1f, night));
            p = winBright;
            k = 1f;
            for (float[] w : wins) {
                Light l = make(2, p, (w[0] + w[1]) * 0.5f, (w[2] + w[3]) * 0.5f, w[4] + 0.5f);
                l.r += (winBrightNight.rgb >> 16 & 255) / 255f * winBrightNight.intensity * nn - l.r * nn;
                l.g += (winBrightNight.rgb >> 8 & 255) / 255f * winBrightNight.intensity * nn - l.g * nn;
                l.b += (winBrightNight.rgb & 255) / 255f * winBrightNight.intensity * nn - l.b * nn;
                cand.add(l);
            }
        } else if (night > 0.05f && !indoor) {   // exterior: lit windows only at night
            p = winDark;
            k = night;
            for (float[] w : wins) {
                Light l = make(2, p, (w[0] + w[1]) * 0.5f, (w[2] + w[3]) * 0.5f, w[4] + 0.6f);
                l.r *= k; l.g *= k; l.b *= k;
                cand.add(l);
            }
        }
    }
}
