package gothic3.desktop.render3d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import gothic3.platform.scene3d.MapSnapshot;
import java.io.File;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Sparkling, softly glossy snow (docs/render3d.md "Snow sparkle and sheen"). Cosmetic, render thread only: nothing here reads or writes game state or
 * uses the game RNG. The shader code lives in the main fragment shader (this class only builds its text, the tile mask texture and the uniforms).
 *
 * <p>Which tiles are snow comes from the {@code snow*} lines of {@code tiles.properties} (layer 0: {@code snow}, {@code snow.tsN}, {@code snow.mapN}; layer 1,
 * roofs and caps: {@code snow1}, {@code snow1.tsN}, {@code snow1.mapN}). They become a one-texel-per-tile mask texture of the tile sheet that the shader reads by
 * the fragment's tile; inside those tiles the art itself decides per texel (bright, low saturation) so footprints, stones and grass stay matt.
 */
final class SnowSparkle {
    private SnowSparkle() { }

    /** True while the main shader has the snow code and the effect is enabled this frame (tile draws then hand over their sheet's mask). */
    static boolean active;
    private static boolean sparkle = true, sheen = true, failed;
    private static float sparkleK = 1f, sheenK = 1f;

    private static final BitSet ANY0 = new BitSet(256), ANY1 = new BitSet(256);
    private static final Map<Integer, BitSet> TS0 = new HashMap<>(), TS1 = new HashMap<>(), MAP0 = new HashMap<>(), MAP1 = new HashMap<>();
    private static final Texture[] mask = new Texture[2];
    private static final long[] maskKey = {Long.MIN_VALUE, Long.MIN_VALUE};
    private static final boolean[] maskAny = new boolean[2];
    private static boolean structures = true;   // `snow.structures = true|false`: the merged house / wall atlas (roofs and caps are baked into it) is judged per texel only

    static void configure(Function<String, String> o) {
        boolean master = !o.apply("atmo.enabled").equalsIgnoreCase("false");
        String v = o.apply("atmo.snowSparkle");
        sparkle = master && (v.isEmpty() || v.equalsIgnoreCase("true"));
        v = o.apply("atmo.snowSheen");
        sheen = master && (v.isEmpty() || v.equalsIgnoreCase("true"));
        sparkleK = num(o.apply("atmo.snowSparkle.strength"), 1f);
        sheenK = num(o.apply("atmo.snowSheen.strength"), 1f);
        if (sparkleK <= 0f) sparkle = false;
        if (sheenK <= 0f) sheen = false;
    }

    private static float num(String s, float d) {
        try { return Math.max(0f, Math.min(2f, Float.parseFloat(s.trim()))); } catch (NumberFormatException e) { return d; }
    }

    // ---------------------------------------------------------------- table

    /** Reads the {@code snow*} lines of the tile table file (or the classpath copy). */
    static void loadTable(File tableFile) {
        ANY0.clear();
        ANY1.clear();
        TS0.clear();
        TS1.clear();
        MAP0.clear();
        MAP1.clear();
        structures = true;
        java.util.Properties p = new java.util.Properties();
        try {
            if (tableFile != null && tableFile.isFile()) {
                try (Reader r = java.nio.file.Files.newBufferedReader(tableFile.toPath(), StandardCharsets.UTF_8)) { p.load(r); }
            } else {
                try (InputStream in = SnowSparkle.class.getClassLoader().getResourceAsStream("render3d/tiles.properties")) {
                    if (in != null) try (Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) { p.load(r); }
                }
            }
        } catch (Exception e) {
            System.err.println("[render3d] snow table not readable: " + e);
        }
        for (String k : p.stringPropertyNames()) {
            k = k.trim();
            boolean l1 = k.equals("snow1") || k.startsWith("snow1.");
            if (!l1 && !k.equals("snow") && !k.startsWith("snow.")) continue;
            String v = p.getProperty(k);
            int c = v.indexOf('#');
            if (c >= 0) v = v.substring(0, c);
            if (k.equals("snow.structures")) {
                structures = !v.trim().equalsIgnoreCase("false");
                continue;
            }
            BitSet bs = new BitSet(256);
            try {
                for (String part : v.trim().split("[,; ]+")) {
                    if (part.isEmpty()) continue;
                    int dash = part.indexOf('-');
                    int lo = Integer.parseInt(dash > 0 ? part.substring(0, dash) : part), hi = dash > 0 ? Integer.parseInt(part.substring(dash + 1)) : lo;
                    for (int i = Math.max(0, lo); i <= Math.min(255, hi); i++) bs.set(i);
                }
                String suf = k.substring(l1 ? 5 : 4);
                if (suf.isEmpty()) (l1 ? ANY1 : ANY0).or(bs);
                else if (suf.startsWith(".ts")) (l1 ? TS1 : TS0).computeIfAbsent(Integer.parseInt(suf.substring(3)), x -> new BitSet(256)).or(bs);
                else if (suf.startsWith(".map")) (l1 ? MAP1 : MAP0).computeIfAbsent(Integer.parseInt(suf.substring(4)), x -> new BitSet(256)).or(bs);
            } catch (RuntimeException e) {
                System.err.println("[render3d] snow: bad table line " + k + " = " + v);
            }
        }
        maskKey[0] = maskKey[1] = Long.MIN_VALUE;   // masks are rebuilt on the next draw
    }

    // ---------------------------------------------------------------- per frame

    /** Called with each lighting setup (shader bound). lit=false (world UI, flat look) or an indoor map switches the effect off. */
    static void uniforms(ShaderProgram sh, boolean lit, boolean indoor, Camera cam, float weatherLevel) {
        active = lit && !indoor && (sparkle || sheen) && !failed && NormalMaps.active;
        if (failed) return;
        sh.setUniformf("u_snOn", 0f);
        if (!active) {
            sh.setUniformf("u_snK", 0f, 0f, 1f, 0f);
            return;
        }
        // weaker under overcast / heavy snowfall: level 0 (clear) = 1, normal snow (1) = 0.7, heavy (1.7) = 0.4
        float damp = 1f - 0.6f * Math.max(0f, Math.min(1f, (weatherLevel - 0.3f) / 1.4f));
        // pixels per world unit along the screen y axis of the ground at the focus: cell size of the sparkle (constant while the camera only scrolls)
        float d = Math.max(0.1f, cam.position.y / Math.max(0.05f, -cam.direction.y));
        float ppu = cam.projection.val[com.badlogic.gdx.math.Matrix4.M11] * 0.5f * cam.viewportHeight / d * Math.max(0.05f, -cam.direction.y);
        sh.setUniformf("u_snK", sparkle ? sparkleK : 0f, sheen ? sheenK : 0f, damp, ppu);
        sh.setUniformf("u_snCam", cam.position.x, cam.position.y, cam.position.z);
    }

    static void fail(Throwable t) {
        System.err.println("[render3d] snow sparkle disabled: " + t);
        failed = true;
        active = false;
    }

    static boolean failed() { return failed; }

    private static boolean has(Map<Integer, BitSet> m, int key, int id) {
        BitSet b = m.get(key);
        return b != null && b.get(id);
    }

    /**
     * A tile page of the map mesh is about to be drawn (layer 0 = sheet0, layer 1 = sheet1): binds the tile mask to unit 3 and enables the effect for the
     * draw. Returns true when the caller has to call {@link #off} after the draw.
     */
    static boolean page(ShaderProgram sh, MapSnapshot m, int layer, Texture t) {
        if (!active || t == null || !sh.hasUniform("u_snOn")) return false;
        int cols = layer == 0 ? m.cols0 : m.cols1, rows = layer == 0 ? m.rows0 : m.rows1;
        if (cols <= 0 || rows <= 0) return false;
        int ts = layer == 0 ? m.tileset0 : m.tileset1;
        long key = ((long) m.mapId << 32) ^ ((long) ts << 16) ^ ((long) cols << 8) ^ rows;
        if (maskKey[layer] != key || mask[layer] == null) {
            if (mask[layer] != null) mask[layer].dispose();
            Pixmap pm = new Pixmap(cols, rows, Pixmap.Format.RGBA8888);
            pm.setColor(0f, 0f, 0f, 0f);
            pm.fill();
            boolean any = false;
            for (int id = 0; id < cols * rows && id < 256; id++) {
                boolean snow = layer == 0
                        ? ANY0.get(id) || has(TS0, m.tileset0, id) || has(MAP0, m.mapId, id)
                        : ANY1.get(id) || has(TS1, m.tileset1, id) || has(MAP1, m.mapId, id);
                if (!snow) continue;
                pm.drawPixel(id % cols, id / cols, 0x000000FF);
                any = true;
            }
            mask[layer] = new Texture(pm);
            pm.dispose();
            mask[layer].setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            mask[layer].setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
            maskAny[layer] = any;
            maskKey[layer] = key;
        }
        if (!maskAny[layer]) return false;
        mask[layer].bind(3);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        sh.setUniformi("u_snMask", 3);
        sh.setUniformf("u_snSz", 1f / t.getWidth(), 1f / t.getHeight());
        sh.setUniformf("u_snCell", m.tileW, m.tileH);
        sh.setUniformf("u_snGrid", cols, rows);
        sh.setUniformf("u_snOn", 1f);
        return true;
    }

    /** The structure atlas (merged houses / walls) is about to be drawn: no tile mask, the art's own bright / low-saturation texels decide. */
    static boolean atlas(ShaderProgram sh, Texture t) {
        if (!active || !structures || t == null || !sh.hasUniform("u_snOn")) return false;
        sh.setUniformf("u_snSz", 1f / t.getWidth(), 1f / t.getHeight());
        sh.setUniformf("u_snCell", 0f, 0f);
        sh.setUniformf("u_snOn", 1f);
        return true;
    }

    static void off(ShaderProgram sh) {
        sh.setUniformf("u_snOn", 0f);
    }

    // ---------------------------------------------------------------- shader text

    static String decl(boolean on) {
        if (!on) return "";
        return "uniform sampler2D u_snMask;\nuniform float u_snOn;\nuniform vec2 u_snSz;\nuniform vec2 u_snCell;\nuniform vec2 u_snGrid;\nuniform vec4 u_snK;\nuniform vec3 u_snCam;\n"
                + "vec4 snHash(vec2 p) {\n"
                + "  vec3 q = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));\n"
                + "  q += dot(q, q.yzx + 33.33);\n"
                + "  vec3 r = fract((q.xxy + q.yzz) * q.zyx);\n"
                + "  return vec4(r, fract(dot(r, vec3(7.13, 3.77, 5.51)) + q.x * 11.0));\n}\n";
    }

    /** After the lighting: sparkle (rare static sun glints on random facets) and a soft sheen on the snow texels; `sh` is the shadow term, `c` the lit colour. */
    static String main(boolean on) {
        if (!on) return "";
        return ""
                + "  if (u_snOn > 0.5 && u_refl <= 0.0 && c.a > 0.01) {\n"
                + "    vec4 sa = texture2D(u_tex, v_uv);\n"
                + "    vec2 tp = v_uv / u_snSz;\n"
                + "    float ex = length(dFdx(tp)), ey = length(dFdy(tp));\n"
                + "    float tpu = (ex + ey) / (length(dFdx(v_wp)) + length(dFdy(v_wp)) + 1e-5);\n"             // texels per world unit of this surface (a constant of the draw, not of the screen position)
                + "    float tileSnow = u_snCell.x > 0.5 ? texture2D(u_snMask, (floor(tp / u_snCell) + 0.5) / u_snGrid).a : 1.0;\n"
                + "    float sl = dot(sa.rgb, vec3(0.299, 0.587, 0.114));\n"
                + "    float sm = tileSnow * sa.a * smoothstep(0.58, 0.76, sl) * (1.0 - smoothstep(0.09, 0.19, max(sa.r, max(sa.g, sa.b)) - min(sa.r, min(sa.g, sa.b))));\n"
                + "    if (sm > 0.01) {\n"
                + "      vec3 sV = normalize(u_snCam - v_wp);\n"
                + "      float sunDay = smoothstep(0.30, 0.50, u_sunCol.g) * smoothstep(0.25, 0.5, u_sunDir.y);\n"      // daylight with the sun well above the horizon: zero at dusk / night
                + "      float sunWarm = clamp(1.0 - u_sunDir.y * 2.2, 0.0, 1.0);\n"
                + "      vec3 add = vec3(0.0);\n"
                + "      if (u_snK.y > 0.0) {\n"                                                                // soft sheen: wide glossy lobe towards the sun, a very faint cool sky sheen at grazing angles
                + "        vec3 hv = normalize(u_sunDir + sV);\n"
                + "        float graz = pow(1.0 - clamp(sV.y, 0.0, 1.0), 3.0);\n"
                + "        float lobe = pow(max(dot(nn, hv), 0.0), 6.0);\n"
                + "        vec3 sunTint = u_sunCol * mix(vec3(1.0), vec3(1.15, 0.95, 0.75), sunWarm);\n"
                + "        vec3 sheenAdd = sunTint * (lobe * (0.08 + 0.15 * graz) * sh * sunDay) * mix(0.6, 1.0, u_snK.z);\n"
                + "        sheenAdd += u_ambient * vec3(0.62, 0.82, 1.12) * (graz * 0.07 * (1.0 - 0.6 * sh));\n"
                + "        add += sheenAdd * u_snK.y;\n"
                + "      }\n"
                + "      float lit = smoothstep(0.92, 1.0, sh) * sunDay * u_snK.z;\n"                                 // fully sunlit only: any cast shadow, penumbra or back face is zero
                + "      if (u_snK.x > 0.0 && lit > 0.001) {\n"                                                  // sparkle: static sub-texel cells, each with a random facet; a cell lights when the sun / view half vector hits its facet
                + "        float fw0 = max(ex, ey);\n"
                + "        float aniso = smoothstep(0.2, 0.5, min(ex, ey) / max(max(ex, ey), 1e-5));\n"
                + "        float sS = exp2(clamp(floor(log2(0.75 * u_snK.w / max(tpu, 1e-4)) + 0.5), 0.0, 4.0));\n"          // cells about 1.3 pixels wide at the focus (power of two steps of the camera distance, not of the screen position)
                + "        vec2 sp = tp * sS + floor(v_wp.xz) * 7.37;\n"
                + "        vec2 sb = floor(sp), sf = fract(sp);\n"
                + "        float fw = fw0 * sS;\n"
                + "        vec3 sT = normalize(abs(nn.z) < 0.95 ? cross(vec3(0.0, 0.0, 1.0), nn) : cross(vec3(1.0, 0.0, 0.0), nn));\n"      // facets tilt around the surface normal (flat ground, sloped roofs)
                + "        vec3 sB = cross(nn, sT);\n"
                + "        float rad = clamp(0.5 * fw, 0.3, 0.45);\n"                                           // about a pixel wide
                + "        vec3 Hs = normalize(u_sunDir + sV);\n"
                + "        vec4 r = snHash(sb);\n"
                + "        float shape = 1.0 - smoothstep(rad * 0.5, rad, length(sf - (0.5 + 0.2 * (r.xy - 0.5))));\n"
                + "        if (shape > 0.002) {\n"
                + "          vec3 fn = normalize(nn + sT * ((r.z - 0.5) * 2.8) + sB * ((r.w - 0.5) * 2.8));\n"
                + "          float e = 0.003 * (0.5 + 0.5 * min(u_snK.x, 2.0));\n"
                + "          float g = smoothstep(1.0 - e, 1.0 - e * 0.55, dot(fn, Hs)) * (0.6 + 0.4 * r.x) * shape * aniso * lit * sm;\n"
                + "          g = clamp(g * 4.5, 0.0, 1.0);\n"
                + "          vec3 tint = vec3(1.0) + (r.y - 0.5) * vec3(0.10, 0.02, -0.12);\n"                   // white, very slightly warm or cool
                + "          c.rgb = mix(c.rgb, tint, clamp(g * 0.9 * min(u_snK.x, 2.0), 0.0, 0.95));\n"
                + "          add += tint * (g * 0.3 * u_snK.x) / max(sm, 0.01);\n"
                + "        }\n"
                + "      }\n"
                + "      c.rgb += add * sm;\n"
                + "    }\n"
                + "  }\n";
    }
}
