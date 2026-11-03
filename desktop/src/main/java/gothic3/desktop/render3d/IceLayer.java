package gothic3.desktop.render3d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.BufferUtils;
import gothic3.platform.scene3d.MapSnapshot;
import gothic3.platform.scene3d.Scene3D;
import java.io.File;
import java.io.InputStream;
import java.io.Reader;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Reflective ice (docs/render3d.md "Reflective ice"). Cosmetic and render only: nothing here reads or writes game state or uses the game RNG.
 *
 * <p>Ice floor tiles are listed in {@code tiles.properties} ({@code ice = 6, 30-33}, {@code ice.ts0 = ...}, {@code ice.map42 = ...}, layer 0 tile ids). Per frame:
 * <ol>
 *   <li>{@link #reflect}: everything that stands (layer-1 pieces, structures, upright cards, prop models) is drawn mirrored about the ground plane into a half
 *       resolution RGBA target (premultiplied colour + alpha; the main shader fades the alpha with the height above the ground via {@code u_refl}).</li>
 *   <li>{@link #composite}: the ice cells are drawn once more (right after the opaque world, before the decals and sprites, so everything standing in front still
 *       covers them) with a shader that blurs / tints that target at the pixel's screen position, adds a Fresnel-weighted sky sheen and moving sun / fire glints.
 *       The ice mask comes from the tile art itself (dark blue texels), so the shore tiles that are half snow only reflect on their ice part.</li>
 * </ol>
 * A shader / GL failure disables only this effect for the session.
 */
final class IceLayer {
    private static final String VERT = ""
            + "attribute vec3 a_position;\nattribute vec4 a_color;\nattribute vec2 a_texCoord0;\n"
            + "uniform mat4 u_projView;\nvarying vec2 v_uv;\nvarying vec4 v_color;\nvarying vec3 v_wp;\n"
            + "void main() {\n  gl_Position = u_projView * vec4(a_position, 1.0);\n  v_uv = a_texCoord0;\n  v_color = a_color;\n  v_wp = a_position;\n}\n";
    private static final String FRAG = ""
            + "#ifdef GL_ES\nprecision mediump float;\n#endif\n"
            + "varying vec2 v_uv;\nvarying vec4 v_color;\nvarying vec3 v_wp;\n"
            + "uniform sampler2D u_tex;\nuniform sampler2D u_rtex;\nuniform vec2 u_texSz;\nuniform vec2 u_rp;\nuniform vec4 u_vp;\n"
            + "uniform vec3 u_cam;\nuniform vec3 u_sun;\nuniform vec3 u_sunCol;\nuniform vec3 u_sky;\nuniform vec4 u_k;\n"
            + "uniform vec4 u_lp[4];\nuniform vec3 u_lc[4];\nuniform float u_nl;\n"
            + "float h21(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }\n"
            + "float lumOf(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }\n"
            + "void main() {\n"
            + "  vec4 t = texture2D(u_tex, v_uv);\n"
            + "  float lum = lumOf(t.rgb);\n"
            + "  float mask = (1.0 - smoothstep(0.42, 0.60, lum)) * smoothstep(0.02, 0.08, t.b - t.r) * t.a * v_color.a;\n"      // ice = dark blue texels of the tile art
            + "  if (mask < 0.004) discard;\n"
            + "  vec3 V = normalize(u_cam - v_wp);\n"
            + "  float cosT = clamp(V.y, 0.05, 1.0);\n"
            + "  float fr = pow(1.0 - cosT, 3.0);\n"                                                                                  // Fresnel-ish: stronger towards the horizon
            + "  float kR = clamp(u_k.x * (0.62 + 2.4 * fr), 0.0, 0.92);\n"
            + "  float lx = lumOf(texture2D(u_tex, v_uv + vec2(u_texSz.x, 0.0)).rgb), ly = lumOf(texture2D(u_tex, v_uv + vec2(0.0, u_texSz.y)).rgb);\n"
            + "  vec2 suv = (gl_FragCoord.xy - u_vp.xy) * u_vp.zw + vec2(lx - lum, ly - lum) * 0.045;\n"                              // the ice's own pattern refracts a little
            + "  vec2 o = u_rp * vec2(1.4, 1.9);\n"                                                                                  // blur, a bit more smeared vertically
            + "  vec4 r = texture2D(u_rtex, suv) * 0.25;\n"
            + "  r += (texture2D(u_rtex, suv + vec2(o.x, 0.0)) + texture2D(u_rtex, suv - vec2(o.x, 0.0)) + texture2D(u_rtex, suv + vec2(0.0, o.y)) + texture2D(u_rtex, suv - vec2(0.0, o.y))) * 0.125;\n"
            + "  r += (texture2D(u_rtex, suv + o) + texture2D(u_rtex, suv - o) + texture2D(u_rtex, suv + vec2(o.x, -o.y)) + texture2D(u_rtex, suv + vec2(-o.x, o.y))) * 0.0625;\n"
            + "  float kk = mask * kR;\n"
            + "  vec3 outC = r.rgb * kk * u_k.w;\n"                                                                                   // premultiplied reflection
            + "  float outA = r.a * kk * u_k.w;\n"
            + "  vec3 R = vec3(-V.x, V.y, -V.z);\n"
            + "  float skyW = mask * u_k.x * (0.10 + 0.85 * fr) * (1.0 - r.a);\n"                                                       // sky sheen where nothing stands
            + "  vec3 add = u_sky * (0.75 + 0.55 * (1.0 - R.y)) * skyW;\n"
            + "  vec2 rh = normalize(R.xz + vec2(1e-4)), sh2 = normalize(u_sun.xz + vec2(1e-4));\n"
            + "  float azA = max(dot(rh, sh2), 0.0);\n"
            + "  float de = R.y - u_sun.y;\n"
            + "  float shim = 0.8 + 0.2 * sin(dot(v_wp.xz, vec2(2.3, 1.1)) + u_k.z * 1.1) * sin(dot(v_wp.xz, vec2(-1.2, 2.6)) - u_k.z * 0.8);\n"
            + "  vec3 g = u_sunCol * (pow(azA, 40.0) * exp(-de * de * 12.0) * 0.75 + pow(max(dot(R, u_sun), 0.0), 6.0) * 0.06) * shim * (1.5 - u_sun.y);\n"                                          // moving sun glint
            + "  for (int i = 0; i < 4; i++) {\n    if (float(i) >= u_nl) break;\n"
            + "    vec3 d = u_lp[i].xyz - v_wp;\n    float dist = length(d) + 1e-4;\n"
            + "    float a = clamp(1.0 - dist / (u_lp[i].w * 1.5), 0.0, 1.0);\n"
            + "    float s = max(dot(R, d / dist), 0.0);\n"
            + "    g += u_lc[i] * (a * a) * (pow(s, 14.0) * 0.55 + pow(s, 4.0) * 0.12) * (0.8 + 0.2 * sin(u_k.z * 7.0 + float(i) * 2.1 + v_wp.x * 1.7));\n"
            + "  }\n"
            + "  float sp = step(0.9935, h21(floor(v_wp.xz * 11.0) + floor(u_k.z * 1.7))) * (0.25 + 0.75 * h21(floor(v_wp.xz * 11.0) + 3.7));\n"      // ice crystal glitter
            + "  g += (u_sunCol + vec3(0.05)) * sp * 0.55 * smoothstep(0.1, 0.5, u_sunCol.g + u_nl * 0.2);\n"
            + "  add += g * mask * u_k.x;\n"
            + "  gl_FragColor = vec4(outC + add * u_k.y, outA + skyW);\n}\n";

    private boolean on = true;
    private float strength = 1f;
    private boolean failed;
    private ShaderProgram sh;
    private FrameBuffer fbo;
    private int fw, fh;
    private final BitSet anyTs = new BitSet(256);
    private final Map<Integer, BitSet> byTs = new HashMap<>(), byMap = new HashMap<>();
    private MapMesh builtFor;
    private int builtSurround = -1;
    private List<Mesh> meshes = new ArrayList<>(), outerMeshes = new ArrayList<>();   // map cells / surround skirt (drawn after the decals: the skirt is a decal)
    private final BoundingBox box = new BoundingBox();
    private boolean hasIce;
    private boolean reflOk;
    private Texture sheet;
    private final Matrix4 mir = new Matrix4().scl(1f, -1f, 1f), pv = new Matrix4();
    private PerspectiveCamera mcam;
    private final IntBuffer fb = BufferUtils.newIntBuffer(16);
    private final long t0 = System.nanoTime();
    private final float[] lp = new float[16], lc = new float[12];
    private final Vector3 tmp = new Vector3();

    boolean active() { return on && !failed; }

    boolean failed() { return failed; }

    /** {@code atmo.enabled} (master), {@code atmo.ice}, {@code atmo.ice.strength}. */
    void configure(Function<String, String> o) {
        boolean master = !o.apply("atmo.enabled").equalsIgnoreCase("false");
        String v = o.apply("atmo.ice");
        on = master && (v.isEmpty() || v.equalsIgnoreCase("true"));
        try { strength = Math.max(0f, Math.min(2f, Float.parseFloat(o.apply("atmo.ice.strength").trim()))); } catch (NumberFormatException e) { strength = 1f; }
        if (strength <= 0f) on = false;
    }

    // ---------------------------------------------------------------- table

    /** Reads the {@code ice*} lines of the tile table file (or the classpath copy): {@code ice = ids}, {@code ice.tsN = ids}, {@code ice.mapN = ids}. */
    void loadTable(File tableFile) {
        anyTs.clear();
        byTs.clear();
        byMap.clear();
        builtFor = null;
        java.util.Properties p = new java.util.Properties();
        try {
            if (tableFile != null && tableFile.isFile()) {
                try (Reader r = java.nio.file.Files.newBufferedReader(tableFile.toPath(), StandardCharsets.UTF_8)) { p.load(r); }
            } else {
                try (InputStream in = IceLayer.class.getClassLoader().getResourceAsStream("render3d/tiles.properties")) {
                    if (in != null) try (Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) { p.load(r); }
                }
            }
        } catch (Exception e) {
            System.err.println("[render3d] ice table not readable: " + e);
        }
        for (String k : p.stringPropertyNames()) {
            k = k.trim();
            if (!k.equals("ice") && !k.startsWith("ice.")) continue;
            String v = p.getProperty(k);
            int c = v.indexOf('#');
            if (c >= 0) v = v.substring(0, c);
            BitSet bs = new BitSet(256);
            try {
                for (String part : v.trim().split("[,; ]+")) {
                    if (part.isEmpty()) continue;
                    int dash = part.indexOf('-');
                    int lo = Integer.parseInt(dash > 0 ? part.substring(0, dash) : part), hi = dash > 0 ? Integer.parseInt(part.substring(dash + 1)) : lo;
                    for (int i = Math.max(0, lo); i <= Math.min(255, hi); i++) bs.set(i);
                }
                if (k.equals("ice")) anyTs.or(bs);
                else if (k.startsWith("ice.ts")) byTs.computeIfAbsent(Integer.parseInt(k.substring(6)), x -> new BitSet(256)).or(bs);
                else if (k.startsWith("ice.map")) byMap.computeIfAbsent(Integer.parseInt(k.substring(7)), x -> new BitSet(256)).or(bs);
            } catch (RuntimeException e) {
                System.err.println("[render3d] ice: bad table line " + k + " = " + v);
            }
        }
    }

    private boolean isIce(MapSnapshot m, int id) {
        if (id < 0) return false;
        if (anyTs.get(id)) return true;
        BitSet b = byTs.get(m.tileset0);
        if (b != null && b.get(id)) return true;
        b = byMap.get(m.mapId);
        return b != null && b.get(id);
    }

    // ---------------------------------------------------------------- ice mesh

    private static final float INSET = 0.02f, LIFT = 0.002f;

    private static int mirrorIdx(int v, int n) {
        if (v < 0) v = -v - 1;
        else if (v >= n) v = 2 * n - 1 - v;
        return Math.max(0, Math.min(n - 1, v));
    }

    private static float fade(int px, int pz, int w, int h, int n) {
        float dx = Math.max(0, Math.max(-px, px - w)), dz = Math.max(0, Math.max(-pz, pz - h));
        float d = (float) Math.sqrt(dx * dx + dz * dz) / n;
        return d >= 1f ? 0f : (float) Math.pow(1f - d, 1.4);
    }

    private static float alpha(float v) { return new Color(1f, 1f, 1f, v).toFloatBits(); }

    private void uvc(MapSnapshot m, int id, int t, float a, float b, float[] out) {
        float u, v;
        switch (t & 7) {
            case 1: u = a; v = 1 - b; break;
            case 2: u = 1 - a; v = b; break;
            case 3: u = 1 - a; v = 1 - b; break;
            case 4: u = b; v = a; break;
            case 5: u = b; v = 1 - a; break;
            case 6: u = 1 - b; v = a; break;
            case 7: u = 1 - b; v = 1 - a; break;
            default: u = a; v = b;
        }
        u = INSET + u * (1 - 2 * INSET);
        v = INSET + v * (1 - 2 * INSET);
        int sx = id % m.cols0 * m.tileW, sy = id / m.cols0 % m.rows0 * m.tileH;
        out[0] = (sx + u * m.tileW) / m.sheet0.getWidth();
        out[1] = (sy + v * m.tileH) / m.sheet0.getHeight();
    }

    private void build(MapMesh mesh, int surround, TextureCache tc, TileTable table) {
        disposeMesh();
        builtFor = mesh;
        builtSurround = surround;
        hasIce = false;
        MapSnapshot m = mesh.map;
        if (m.sheet0 == null || (anyTs.isEmpty() && byTs.isEmpty() && byMap.isEmpty())) return;
        sheet = tc.get(m.sheet0);
        QuadBuilder qIn = new QuadBuilder(), qOut = new QuadBuilder();
        float[][] c = new float[4][2];
        int w = m.w, h = m.h, n = Math.max(0, surround);
        float minX = 1e9f, maxX = -1e9f, minZ = 1e9f, maxZ = -1e9f;
        int cnt = 0;
        for (int y = -n; y < h + n; y++) {
            for (int x = -n; x < w + n; x++) {
                boolean inside = x >= 0 && x < w && y >= 0 && y < h;
                int mx = inside ? x : mirrorIdx(x, w), my = inside ? y : mirrorIdx(y, h);
                int cell = m.cells[my * w + mx];
                int id = (byte) (cell & 0xFF);
                if (!isIce(m, id)) continue;
                TileTable.Shape s = table.get(m.mapId, 0, m.tileset0, id, -1, mx, my);
                if (s.kind != TileTable.Kind.FLOOR || s.param > 0f) continue;       // decks / stairs / re-mapped art: no ice
                if (mesh.groundHeight(mx + 0.5f, my + 0.5f) != 0f) continue;
                float a0 = 1f, a1 = 1f, a2 = 1f, a3 = 1f;
                if (!inside) {
                    a0 = fade(x, y, w, h, n);
                    a1 = fade(x + 1, y, w, h, n);
                    a2 = fade(x + 1, y + 1, w, h, n);
                    a3 = fade(x, y + 1, w, h, n);
                    if (a0 + a1 + a2 + a3 <= 0f) continue;
                }
                int t = cell >> 8 & 7;
                uvc(m, id, t, 0, 0, c[0]);
                uvc(m, id, t, 1, 0, c[1]);
                uvc(m, id, t, 1, 1, c[2]);
                uvc(m, id, t, 0, 1, c[3]);
                (inside ? qIn : qOut).quad(x, LIFT, y, c[0][0], c[0][1], x + 1, LIFT, y, c[1][0], c[1][1], x + 1, LIFT, y + 1, c[2][0], c[2][1], x, LIFT, y + 1, c[3][0], c[3][1],
                        alpha(a0), alpha(a1), alpha(a2), alpha(a3), 0, 1, 0);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x + 1);
                minZ = Math.min(minZ, y);
                maxZ = Math.max(maxZ, y + 1);
                cnt++;
            }
        }
        if (cnt == 0) return;
        if (!qIn.isEmpty()) meshes = qIn.build();
        if (!qOut.isEmpty()) outerMeshes = qOut.build();
        box.set(new Vector3(minX, -0.5f, minZ), new Vector3(maxX, 0.5f, maxZ));
        hasIce = true;
        System.err.println("[render3d] ice: " + cnt + " cells");
    }

    private void disposeMesh() {
        for (Mesh x : meshes) x.dispose();
        for (Mesh x : outerMeshes) x.dispose();
        meshes = new ArrayList<>();
        outerMeshes = new ArrayList<>();
    }

    /** True when ice is enabled, the map has ice cells (built / cached per map mesh) and they are inside the camera frustum. */
    boolean prepare(MapMesh mesh, int surround, Camera cam, TextureCache tc, TileTable table) {
        if (!active()) return false;
        try {
            if (mesh != builtFor || surround != builtSurround) build(mesh, surround, tc, table);
        } catch (Throwable t) {
            fail("build", t);
            return false;
        }
        return hasIce && cam.frustum.boundsInFrustum(box);
    }

    private void fail(String what, Throwable t) {
        System.err.println("[render3d] ice " + what + " failed, ice reflections off: " + t);
        failed = true;
        reflOk = false;
        try { Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0); } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- pass 1: mirrored scene

    /**
     * Draws the standing things mirrored about y = 0 into the half-resolution reflection target. The main shader must be bound with the lighting / fog
     * uniforms of the frame set; on return the main framebuffer, viewport and the basic GL state are restored and {@code u_projView} is the real camera again.
     * The caller sets the occluder-fade uniforms afterwards.
     */
    void reflect(Scene3D s, ShaderProgram shader, Camera cam, MapMesh mesh, TextureCache tc, SpriteLayer sprites, gothic3.desktop.render3d.props.PropLayer pl,
                 float vscale, float lean, int ox, int oy, int vw, int vh, float fogStart, float fogEnd) {
        reflOk = false;
        if (failed) return;
        try {
            int w = Math.max(64, vw / 2), h = Math.max(64, vh / 2);
            if (fbo == null || fw != w || fh != h) {
                if (fbo != null) fbo.dispose();
                fbo = new FrameBuffer(Pixmap.Format.RGBA8888, w, h, true);
                fbo.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
                fbo.getColorBufferTexture().setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
                fw = w;
                fh = h;
            }
            if (mcam == null) mcam = new PerspectiveCamera();
            mcam.invProjectionView.set(cam.invProjectionView).mulLeft(mir);       // frustum of the mirrored world (culling)
            mcam.frustum.update(mcam.invProjectionView);
            pv.set(cam.combined).mul(mir);

            fb.clear();
            Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, fb);
            int prev = fb.get(0);
            fbo.begin();
            Gdx.gl.glViewport(0, 0, w, h);
            Gdx.gl.glClearColor(0f, 0f, 0f, 0f);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            Gdx.gl.glDisable(GL20.GL_CULL_FACE);
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFuncSeparate(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
            shader.bind();
            shader.setUniformMatrix("u_projView", pv);
            shader.setUniformf("u_refl", 0.14f);
            shader.setUniformf("u_ofP", 0f, 0f, 0f, 0f);
            shader.setUniformf("u_rim", 0f);
            shader.setUniformf("u_dropBlack", 0f);
            shader.setUniformf("u_fog", fogStart, fogEnd);
            // standing world geometry (opaque / cutout, depth write)
            shader.setUniformf("u_cut", 0.5f);
            Gdx.gl.glDepthMask(true);
            mesh.drawStanding(shader, mcam, tc);
            if (pl != null && sprites.skip != null) pl.draw(s, shader, vscale, lean, false);
            // upright cards in painter order (blended, no depth write)
            Gdx.gl.glDepthMask(false);
            shader.setUniformf("u_cut", 0.02f);
            int rs = sprites.rimSide;
            sprites.rimSide = 0;
            sprites.draw(s, Scene3D.PASS_UPRIGHT, vscale, lean);
            sprites.rimSide = rs;
            fbo.end();
            Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, prev);
            Gdx.gl.glViewport(ox, oy, vw, vh);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            Gdx.gl.glDepthMask(true);
            shader.setUniformMatrix("u_projView", cam.combined);
            shader.setUniformf("u_refl", 0f);
            int err = Gdx.gl.glGetError();
            if (err != GL20.GL_NO_ERROR) throw new IllegalStateException("GL error 0x" + Integer.toHexString(err));
            reflOk = true;
        } catch (Throwable t) {
            fail("reflection pass", t);
            shader.bind();
            shader.setUniformMatrix("u_projView", cam.combined);
            shader.setUniformf("u_refl", 0f);
            Gdx.gl.glViewport(ox, oy, vw, vh);
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        }
    }

    // ---------------------------------------------------------------- pass 2: composite on the ice

    /**
     * Draws the ice cells with the reflection / sheen / glints (blend ONE, ONE_MINUS_SRC_ALPHA over the floor already drawn). Call after the opaque world
     * (depth written), before decals and sprites. Restores the main shader (bound) and {@code depthMask(true)}.
     * @param light scene light level (rgb), @param fogRgb fog / background colour, @param sunDir unit vector towards the sun, @param sunCol sun colour
     */
    void composite(ShaderProgram main, Camera cam, float outScale, int ox, int oy, int vw, int vh, Vector3 sunDir, float[] sunCol, float[] light, float[] fogRgb,
                   gothic3.desktop.render3d.props.PropLayer pl, boolean outer) {
        List<Mesh> ms = outer ? outerMeshes : meshes;
        if (!reflOk || failed || ms.isEmpty() || sheet == null || fbo == null) return;
        try {
            if (sh == null) {
                sh = new ShaderProgram(VERT, FRAG);
                if (!sh.isCompiled()) throw new IllegalStateException("shader: " + sh.getLog());
            }
            sh.bind();
            sh.setUniformMatrix("u_projView", cam.combined);
            sheet.bind(0);
            sh.setUniformi("u_tex", 0);
            fbo.getColorBufferTexture().bind(2);
            sh.setUniformi("u_rtex", 2);
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
            sh.setUniformf("u_texSz", 1f / sheet.getWidth(), 1f / sheet.getHeight());
            sh.setUniformf("u_rp", 1f / fw, 1f / fh);
            sh.setUniformf("u_vp", ox, oy, 1f / vw, 1f / vh);
            sh.setUniformf("u_cam", cam.position.x, cam.position.y, cam.position.z);
            sh.setUniformf("u_sun", sunDir.x, sunDir.y, sunDir.z);
            sh.setUniformf("u_sunCol", sunCol[0], sunCol[1], sunCol[2]);
            // sky colour: the scene light level pulled towards the fog colour, cool tint
            float k = 0.9f;
            sh.setUniformf("u_sky", (light[0] * 0.55f + fogRgb[0] * 1.2f) * 0.75f * k, (light[1] * 0.62f + fogRgb[1] * 1.2f) * 0.8f * k, (light[2] * 0.8f + fogRgb[2] * 1.2f) * k);
            float time = ((System.nanoTime() - t0) / 1000000L % 1000000L) / 1000f;
            sh.setUniformf("u_k", strength, outScale, time, 1f);
            int nl = pl == null ? 0 : Math.min(4, pl.lightCount);
            sh.setUniformf("u_nl", (float) nl);
            if (nl > 0) {
                // nearest-to-camera-focus first is already how PropLayer orders them; take the first four
                System.arraycopy(pl.lightPos, 0, lp, 0, nl * 4);
                System.arraycopy(pl.lightCol, 0, lc, 0, nl * 3);
                sh.setUniform4fv("u_lp", lp, 0, nl * 4);
                sh.setUniform3fv("u_lc", lc, 0, nl * 3);
            }
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            Gdx.gl.glDepthMask(false);
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
            for (Mesh m : ms) m.render(sh, GL20.GL_TRIANGLES);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            Gdx.gl.glDepthMask(true);
            main.bind();
            int err = Gdx.gl.glGetError();
            if (err != GL20.GL_NO_ERROR) throw new IllegalStateException("GL error 0x" + Integer.toHexString(err));
        } catch (Throwable t) {
            fail("composite", t);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            Gdx.gl.glDepthMask(true);
            main.bind();
        }
    }

    void dispose() {
        disposeMesh();
        if (fbo != null) fbo.dispose();
        fbo = null;
        if (sh != null) sh.dispose();
        sh = null;
        builtFor = null;
    }
}
