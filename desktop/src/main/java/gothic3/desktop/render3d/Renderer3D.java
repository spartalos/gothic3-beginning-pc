package gothic3.desktop.render3d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import gothic3.desktop.Config;
import gothic3.platform.scene3d.MapSnapshot;
import gothic3.platform.scene3d.Scene3D;
import java.io.File;

/**
 * Pokemon3D-style diorama renderer: draws a {@link Scene3D} (map snapshot + captured sprite draws) with a tilted
 * perspective camera following the game's camera. Render (GL) thread only; reads only immutable snapshots.
 */
public final class Renderer3D {
    private static final String VERT = ""
            + "attribute vec3 a_position;\nattribute vec4 a_color;\nattribute vec2 a_texCoord0;\nattribute vec3 a_normal;\n"
            + "uniform mat4 u_projView;\nuniform mat4 u_lightVP;\nuniform vec3 u_sunDir;\nuniform float u_nOff;\n"
            + gothic3.desktop.render3d.atmo.Sway.DECL                                                                                                              // SWAY
            + "varying vec2 v_uv;\nvarying vec4 v_color;\nvarying float v_dist;\nvarying float v_ndl;\nvarying vec3 v_sc;\nvarying vec3 v_wp;\nvarying vec3 v_n;\n"
            + "void main() {\n  vec3 ap = swayPos(a_position);\n  vec4 p = u_projView * vec4(ap, 1.0);\n  gl_Position = p;\n  v_uv = a_texCoord0;\n"
            + "  v_color = a_color;\n  v_dist = p.w;\n  v_wp = ap;\n  v_n = a_normal;\n"
            + "  v_ndl = max(dot(a_normal, u_sunDir), 0.0);\n"
            + "  v_sc = (u_lightVP * vec4(ap + a_normal * u_nOff, 1.0)).xyz * 0.5 + 0.5;\n}\n";
    private static final String FRAG = ""
            + "#ifdef GL_ES\nprecision mediump float;\n#endif\n"
            + "varying vec2 v_uv;\nvarying vec4 v_color;\nvarying float v_dist;\nvarying float v_ndl;\nvarying vec3 v_sc;\nvarying vec3 v_wp;\nvarying vec3 v_n;\n"
            + "uniform vec4 u_lp[24];\nuniform vec3 u_lc[24];\nuniform float u_nl;\n"
            + "uniform sampler2D u_tex;\nuniform float u_cut;\nuniform float u_dropBlack;\nuniform vec3 u_fogColor;\nuniform vec2 u_fog;\nuniform float u_outScale;\n"
            + "uniform sampler2D u_shadow;\nuniform vec3 u_ambient;\nuniform vec3 u_sunCol;\nuniform float u_shadowOn;\nuniform float u_texel;\nuniform float u_bias;\n"
            + "uniform float u_soft;\nuniform float u_contact;\nuniform vec3 u_shTint;\nuniform float u_time;\nuniform vec3 u_hf;\nuniform vec3 u_mistCol;\n"   // ATMO
            + "uniform float u_rim;\nuniform vec3 u_rimCol;\nuniform vec2 u_rimDir;\nuniform vec2 u_texSz;\n"                                                  // ATMO
            + "uniform sampler2D u_ao;\nuniform float u_aoStr;\nuniform vec4 u_aoG;\n"                                                                // AO: baked ambient occlusion (grid origin xz, 1 / size xz)
            + "uniform float u_refl;\n"                                                                                                                          // ICE: > 0 in the mirrored reflection pass (alpha fades with the height above the ground)
            + "uniform vec4 u_of;\nuniform vec4 u_ofP;\n"                                                                                                         // OCC: occluder fade (hero ellipse px, {minOpacity, zMin, yMin, amount})
            + "@NRM_DECL@"                                                                                                                                        // NORMALS: relief lighting (NormalMaps)
            + "@SNW_DECL@"                                                                                                                                        // SNOW: sparkle / sheen (SnowSparkle)
            + "float b2(vec2 p) { p = floor(mod(p, 2.0)); return mod(p.x * 2.0 + p.y * 3.0, 4.0); }\n"
            + "float bayer4(vec2 p) { return (b2(p) * 4.0 + b2(floor(p * 0.5))) / 16.0 + 0.03125; }\n"
            + "float unpackD(vec4 c) { return dot(c.rgb, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0)); }\n"
            + "float shadowAt() {\n"
            + "  if (u_shadowOn < 0.5 || v_sc.x < 0.0 || v_sc.y < 0.0 || v_sc.x > 1.0 || v_sc.y > 1.0 || v_sc.z > 1.0) return 1.0;\n"
            + "  if (u_soft > 0.0) {\n"                                                                                                                              // ATMO soft shadows
            + "    float ang = 6.2831 * fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));\n"
            + "    float ca = cos(ang), sa = sin(ang);\n"
            + "    float rad = u_soft;\n"
            + "    if (u_contact > 0.5) {\n"
            + "      float bs = 0.0, bn = 0.0;\n"
            + "      for (int i = 0; i < 8; i++) {\n"
            + "        float fi = float(i) + 0.5; float rr = sqrt(fi / 8.0) * 5.0 * u_soft; float th = fi * 2.39996 + ang;\n"
            + "        float zb = unpackD(texture2D(u_shadow, v_sc.xy + vec2(cos(th), sin(th)) * rr * u_texel));\n"
            + "        if (zb < v_sc.z - u_bias) { bs += zb; bn += 1.0; }\n"
            + "      }\n"
            + "      if (bn < 0.5) return 1.0;\n"
            + "      rad = u_soft * (0.35 + clamp((v_sc.z - bs / bn) * 130.0 * 0.9, 0.0, 3.5));\n"
            + "    }\n"
            + "    float lt = 0.0;\n"
            + "    for (int i = 0; i < 16; i++) {\n"
            + "      float fi = float(i) + 0.5; float rr = sqrt(fi / 16.0) * rad; float th = fi * 2.39996;\n"
            + "      vec2 o = vec2(cos(th), sin(th)) * rr;\n"
            + "      o = vec2(o.x * ca - o.y * sa, o.x * sa + o.y * ca);\n"
            + "      lt += step(v_sc.z - u_bias, unpackD(texture2D(u_shadow, v_sc.xy + o * u_texel)));\n"
            + "    }\n"
            + "    return lt / 16.0;\n"
            + "  }\n"
            + "  float lit = 0.0;\n"
            + "  for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++)\n"
            + "    lit += step(v_sc.z - u_bias, unpackD(texture2D(u_shadow, v_sc.xy + vec2(float(i), float(j)) * u_texel)));\n"
            + "  return lit / 9.0;\n}\n"
            + "void main() {\n  vec4 c = texture2D(u_tex, v_uv) * v_color;\n  if (c.a < u_cut) discard;\n  if (u_dropBlack > 0.5 && c.a > 0.9 && max(c.r, max(c.g, c.b)) < 0.02) discard;\n"
            + "  if (u_ofP.w > 0.0) {\n"                                                                                                                            // OCC: dithered cut-out around the hero for things in front of him
            + "    float k = u_ofP.w * smoothstep(0.0, 0.6, v_wp.z - u_ofP.y) * smoothstep(0.0, 0.4, v_wp.y - u_ofP.z);\n"
            + "    if (k > 0.0) {\n"
            + "      float d = length((gl_FragCoord.xy - u_of.xy) / u_of.zw);\n"
            + "      float op = mix(1.0, mix(u_ofP.x, 1.0, smoothstep(0.5, 1.0, d)), k);\n"
            + "      if (op < 1.0 && bayer4(gl_FragCoord.xy) > op) discard;\n"
            + "    }\n"
            + "  }\n"
            + "@NRM_MAIN@"
            + "  float sh = v_ndl > 0.0 ? shadowAt() : 0.0;\n"
            + "  vec3 pl = vec3(0.0);\n"
            + "  for (int i = 0; i < 24; i++) {\n    if (float(i) >= u_nl) break;\n"
            + "    vec3 d = u_lp[i].xyz - v_wp;\n    float dist = length(d) + 1e-4;\n"
            + "    float a = clamp(1.0 - dist / u_lp[i].w, 0.0, 1.0);\n"
            + "    float nd = clamp((dot(nn, d / dist) + 0.45) / 1.45, 0.0, 1.0);\n"
            + "    pl += u_lc[i] * (a * a * nd);\n  }\n"
            + "  float shade = 1.0 - clamp(ndl * sh * 1.25, 0.0, 1.0);\n"                                                                                // ATMO
            + "  float aoF = 1.0;\n"                                                                                                                              // AO
            + "  if (u_aoStr > 0.0) {\n"
            + "    vec2 ag = texture2D(u_ao, (v_wp.xz - u_aoG.xy) * u_aoG.zw).rg;\n"
            + "    float occ = clamp((mix(ag.r, ag.g, 0.35) * 1.15 - v_wp.y - 0.03) / 0.85, 0.0, 1.0);\n"
            + "    occ *= mix(clamp(1.0 - v_wp.y * 1.3, 0.0, 1.0), 1.0, smoothstep(0.5, 0.8, normalize(v_n).y));\n"                                              // facades: only their base
            + "    aoF = 1.0 - min(u_aoStr * occ * occ * (3.0 - 2.0 * occ), 0.9);\n"
            + "  }\n"
            + "  c.rgb *= u_ambient * aoF * (mix(vec3(1.0), u_shTint, shade) * nmAmb) + u_sunCol * (ndl * sh * (1.0 - 0.3 * (1.0 - aoF))) + pl;\n"
            + "@NRM_SPEC@"
            + "@SNW_MAIN@"
            + "  if (u_rim > 0.0) {\n"                                                                                                                                // ATMO rim light
            + "    float n1 = texture2D(u_tex, v_uv + vec2(u_rimDir.x, 0.0) * u_texSz).a;\n"
            + "    float n2 = texture2D(u_tex, v_uv + vec2(0.0, u_rimDir.y) * u_texSz).a;\n"
            + "    float m1 = texture2D(u_tex, v_uv - vec2(u_rimDir.x, 0.0) * u_texSz).a;\n"                                                                      // the texel on the lit side must be art: a dithered 1 px edge is no silhouette (it turned into white noise)
            + "    float m2 = texture2D(u_tex, v_uv - vec2(0.0, u_rimDir.y) * u_texSz).a;\n"
            + "    float e = clamp(1.0 - n1, 0.0, 1.0) * clamp(m1, 0.0, 1.0) + 0.6 * clamp(1.0 - n2, 0.0, 1.0) * clamp(m2, 0.0, 1.0);\n"
            + "    c.rgb += u_rimCol * (min(e, 1.0) * u_rim * c.a);\n"
            + "  }\n"
            + "  if (u_hf.x > 0.0) {\n"                                                                                                                               // ATMO ground mist
            + "    float hn = 0.8 + 0.2 * sin(v_wp.x * 0.31 + u_time * 0.08) * sin(v_wp.z * 0.27 - u_time * 0.06);\n"
            + "    float hm = u_hf.x * exp(-max(v_wp.y, 0.0) * u_hf.y) * (1.0 - exp(-v_dist * 0.05)) * hn;\n"
            + "    c.rgb = mix(c.rgb, u_mistCol, clamp(hm, 0.0, 0.85));\n"
            + "  }\n"
            + "  float f = clamp((v_dist - u_fog.x) / (u_fog.y - u_fog.x), 0.0, 1.0);\n"
            + "  if (u_refl > 0.0) c.a *= 1.0 - 0.7 * clamp(v_wp.y * u_refl, 0.0, 1.0);\n"                                                                           // ICE
            + "  c.rgb = mix(c.rgb, u_fogColor, f * f);\n  c.rgb *= u_outScale;\n  gl_FragColor = c;\n}\n";

    private static String frag(boolean nrm) {
        return frag(nrm, false);
    }

    /** @param snw snow sparkle / sheen code (needs the relief code: it uses its uniforms and the shading normal) */
    private static String frag(boolean nrm, boolean snw) {
        return FRAG.replace("@NRM_DECL@", NormalMaps.decl(nrm)).replace("@NRM_MAIN@", NormalMaps.main(nrm)).replace("@NRM_SPEC@", NormalMaps.spec(nrm))
                .replace("@SNW_DECL@", SnowSparkle.decl(nrm && snw)).replace("@SNW_MAIN@", SnowSparkle.main(nrm && snw));
    }

    /** Shadow depth pass: alpha tested, depth packed into RGB (portable, no depth texture / GL3 needed). */
    private static final String DEPTH_VERT = ""
            + "attribute vec3 a_position;\nattribute vec4 a_color;\nattribute vec2 a_texCoord0;\nuniform mat4 u_lightVP;\n"
            + gothic3.desktop.render3d.atmo.Sway.DECL                                                                                                              // SWAY
            + "varying vec2 v_uv;\nvarying float v_a;\n"
            + "void main() {\n  gl_Position = u_lightVP * vec4(swayPos(a_position), 1.0);\n  v_uv = a_texCoord0;\n  v_a = a_color.a;\n}\n";
    private static final String DEPTH_FRAG = ""
            + "#ifdef GL_ES\nprecision highp float;\n#endif\n"
            + "varying vec2 v_uv;\nvarying float v_a;\nuniform sampler2D u_tex;\n"
            + "void main() {\n  if (texture2D(u_tex, v_uv).a * v_a < 0.5) discard;\n"
            + "  vec3 e = fract(gl_FragCoord.z * vec3(1.0, 255.0, 65025.0));\n"
            + "  e -= e.yzz * vec3(1.0 / 255.0, 1.0 / 255.0, 0.0);\n  gl_FragColor = vec4(e, 1.0);\n}\n";

    private final Config cfg;
    private File tableFile;
    private final java.util.Properties tune = new java.util.Properties();   // dev: -Dg3.render3d.tune=<file> overrides render3d.* live
    private File tuneFile;
    private long tuneStamp;
    private gothic3.desktop.render3d.post.PostChain3D post;   // POST: HDR scene target + post chain (docs/post.md)
    private ShaderProgram shader, depthShader;
    private FrameBuffer shadowFbo;
    private int shadowRes;
    private OrthographicCamera lightCam;
    private boolean lightingFailed;
    private final gothic3.desktop.render3d.atmo.Atmosphere atmo = new gothic3.desktop.render3d.atmo.Atmosphere();   // ATMO
    private final IceLayer ice = new IceLayer();                                                                    // ICE: reflective ice (atmo.ice)
    private final float[] iceSunCol = new float[3];
    private final float[] atmoFog = new float[3], atmoLight = new float[3];
    private TextureCache tc;
    private SpriteLayer sprites;
    private final gothic3.desktop.render3d.props.ImageIds ids = new gothic3.desktop.render3d.props.ImageIds();
    private gothic3.desktop.render3d.props.PropDump propDump;
    private gothic3.desktop.render3d.props.PropLayer props;
    private boolean pointLights = true;
    private TileTable table;
    private MapMesh mesh;
    private PerspectiveCamera cam;
    private boolean failed;
    private long frames;

    // tunables (config render3d.*)
    private float iconLift = 0.3f, iconScale = 1.2f;
    private float pitch, fov, distance, focusOffset, lean, vscale, fogStart, fogEnd;
    private boolean fog;
    // occluder fade (render3d.occFade / occFade.min): dithered cut-out of things in front of the hero, see docs/render3d.md
    private boolean occFade = true;
    private float occMin = 0.22f, occAmt;
    private long occT;
    // map surround (render3d.surround / surround.tiles / surround.color)
    private boolean surround = true;
    private int surroundTiles = 8;
    private int surroundColor = -1;             // -1 = auto (map's ground colour), else 0xRRGGBB
    // lighting (config render3d.lighting / shadows / sun.* / ambient / indoor.tilesets)
    private boolean lighting;
    private int shadowQuality;                  // 0 off, 1 low, 2 high
    private float sunAz, sunEl, ambient, sunStrength, footShadow;
    private java.util.Set<Integer> indoorTilesets = new java.util.HashSet<>();
    private final java.util.Set<Integer> caveTilesets = new java.util.HashSet<>();   // caves are lit and framed as interiors (render3d.cave.tilesets)

    private boolean caveMap;   // the current map is a cave (cool grey-blue light instead of the warm room light)

    private boolean isIndoor(MapSnapshot m) {
        return indoorTilesets.contains(m.tileset0) || indoorTilesets.contains(m.tileset1) || caveTilesets.contains(m.tileset0) || caveTilesets.contains(m.tileset1);
    }

    public Renderer3D(Config cfg) {
        this.cfg = cfg;
        String tn = System.getProperty("g3.render3d.tune");
        if (tn != null) tuneFile = new File(tn);
        loadTune();
        readConfig();
    }

    /** Config value, overridden by the live tuning file (dev). */
    private String opt(String k) {
        String v = tune.getProperty(k);
        return v != null ? v.trim() : cfg.get(k);
    }

    private float optF(String k, float def) {
        try { return Float.parseFloat(opt(k)); } catch (NumberFormatException e) { return def; }
    }

    private boolean loadTune() {
        if (tuneFile == null || !tuneFile.isFile() || tuneFile.lastModified() == tuneStamp) return false;
        tuneStamp = tuneFile.lastModified();
        tune.clear();
        try (java.io.Reader r = java.nio.file.Files.newBufferedReader(tuneFile.toPath())) { tune.load(r); } catch (Exception e) { return false; }
        return true;
    }

    private void readConfig() {
        String tf = opt("render3d.tiles");
        tableFile = tf.isEmpty() ? new File("game/src/main/resources/render3d/tiles.properties") : new File(tf);
        pitch = optF("render3d.pitch", 38f);
        fov = optF("render3d.fov", 35f);
        distance = optF("render3d.distance", 26f);
        focusOffset = optF("render3d.focusOffset", 0f);
        lean = (float) Math.tan(Math.toRadians(optF("render3d.lean", 0f)));
        vscale = optF("render3d.vscale", 1f);
        iconLift = optF("render3d.iconLift", 0.3f);
        iconScale = optF("render3d.iconScale", 1.2f);
        fog = !opt("render3d.fog").equalsIgnoreCase("false");
        readOcc();
        fogStart = optF("render3d.fogStart", 22f);
        fogEnd = optF("render3d.fogEnd", 48f);
        surround = !opt("render3d.surround").equalsIgnoreCase("false");
        surroundTiles = Math.max(1, Math.min(24, (int) optF("render3d.surround.tiles", 8f)));
        String sc = opt("render3d.surround.color").replace("#", "");
        surroundColor = -1;
        if (sc.length() == 6) {
            try { surroundColor = Integer.parseInt(sc, 16); } catch (NumberFormatException ignored) { }
        }
        // --- lighting
        lighting = !opt("render3d.lighting").equalsIgnoreCase("false");
        String sq = opt("render3d.shadows").toLowerCase();
        shadowQuality = sq.equals("off") || sq.equals("false") ? 0 : sq.equals("low") ? 1 : 2;
        sunAz = optF("render3d.sun.azimuth", 205f);
        sunEl = optF("render3d.sun.elevation", 48f);
        sunStrength = optF("render3d.sun.strength", 0.62f);
        ambient = optF("render3d.ambient", 0.52f);
        footShadow = optF("render3d.footShadow", 0.5f);
        atmo.configure(this::opt);
        NormalMaps.configure(this::opt);   // NORMALS
        SnowSparkle.configure(this::opt);  // SNOW
        ice.configure(this::opt);                                    // ICE
        configureLights();
        indoorTilesets.clear();
        caveTilesets.clear();
        for (String t : opt("render3d.cave.tilesets").split("[,; ]+")) {
            try { if (!t.isEmpty()) caveTilesets.add(Integer.parseInt(t)); } catch (NumberFormatException ignored) { }
        }
        configureProps();
        for (String t : opt("render3d.indoor.tilesets").split("[,; ]+")) {
            try { if (!t.isEmpty()) indoorTilesets.add(Integer.parseInt(t)); } catch (NumberFormatException ignored) { }
        }
    }

    /** Extra light sources (atmo.lights*): settings only. */
    private void configureLights() {
        if (props == null || props.fx == null) return;
        props.fx.configure(this::opt);
        props.refresh();
    }

    private void configureProps() {
        if (props == null) return;
        pointLights = !opt("render3d.pointLights").equalsIgnoreCase("false");
        props.configure(opt("render3d.props"), opt("render3d.props.characters").equalsIgnoreCase("true"), optF("render3d.props.characterDepth", 0.12f),
                pointLights, (int) optF("render3d.pointLights.max", 12f), optF("render3d.pointLights.intensity", 1f));
    }

    private void init() {
        ShaderProgram.pedantic = false;
        shader = new ShaderProgram(VERT, frag(true, true));
        if (!shader.isCompiled()) {   // SNOW: without the sparkle code only that effect is lost
            SnowSparkle.fail(new IllegalStateException(shader.getLog()));
            shader.dispose();
            shader = new ShaderProgram(VERT, frag(true));
        }
        if (!shader.isCompiled()) {   // NORMALS: a shader without the relief lighting is the fallback; only that effect is lost
            NormalMaps.fail(new IllegalStateException(shader.getLog()));
            shader.dispose();
            shader = new ShaderProgram(VERT, frag(false));
        }
        if (!shader.isCompiled()) {   // SWAY: retry without the sway code; only that effect is lost
            System.err.println("[render3d] sway shader variant failed, plant sway off: " + shader.getLog());
            atmo.swayFailed();
            shader.dispose();
            shader = new ShaderProgram(VERT.replace(gothic3.desktop.render3d.atmo.Sway.DECL, gothic3.desktop.render3d.atmo.Sway.DECL_OFF), frag(false));
        }
        if (!shader.isCompiled()) throw new IllegalStateException("3D shader: " + shader.getLog());
        try {
            depthShader = new ShaderProgram(DEPTH_VERT, DEPTH_FRAG);
            if (!depthShader.isCompiled()) {
                atmo.swayFailed();
                depthShader = new ShaderProgram(DEPTH_VERT.replace(gothic3.desktop.render3d.atmo.Sway.DECL, gothic3.desktop.render3d.atmo.Sway.DECL_OFF), DEPTH_FRAG);
            }
            if (!depthShader.isCompiled()) throw new IllegalStateException(depthShader.getLog());
            lightCam = new OrthographicCamera();
        } catch (Throwable t) {
            System.err.println("[render3d] shadow shader unavailable, shadows off: " + t);
            depthShader = null;
        }
        tc = new TextureCache();
        String pd = System.getProperty("g3.render3d.propdump");
        if (pd != null) propDump = gothic3.desktop.render3d.props.PropDump.create(new File(pd));
        sprites = new SpriteLayer(shader, tc);
        try {
            props = new gothic3.desktop.render3d.props.PropLayer(tc, ids, QuadBuilder.QUAD_IDX);
            configureProps();
            props.fx = new gothic3.desktop.render3d.props.LightSources();
            configureLights();
        } catch (Throwable t) {
            System.err.println("[render3d] props unavailable (cards only): " + t);
            props = null;
        }
        table = TileTable.load(tableFile);
        System.err.println("[render3d] tile table: " + table.source());
        ice.loadTable(tableFile);                                    // ICE: `ice` lines of the tile table
        SnowSparkle.loadTable(tableFile);                            // SNOW: `snow` lines of the tile table
        cam = new PerspectiveCamera(fov, 1, 1);
        post = new gothic3.desktop.render3d.post.PostChain3D(cfg);
    }

    /** POST: the post chain (scene colour / depth getters for effects that need them), null before the first frame. */
    public gothic3.desktop.render3d.post.PostChain3D post() { return post; }

    /** F6: reload the tile table (and config tunables are re-read from the already loaded Config). */
    public void reload() {
        if (shader == null) return;
        if (loadTune()) readConfig();
        table = TileTable.load(tableFile);
        System.err.println("[render3d] reloaded tile table: " + table.source());
        ice.loadTable(tableFile);                                    // ICE
        SnowSparkle.loadTable(tableFile);                            // SNOW
        if (props != null) props.reloadRules();
        if (props != null && props.fx != null) props.fx.load();
        atmo.reloadTable();
        if (mesh != null) {
            mesh.dispose();
            mesh = null; // rebuilt from the same snapshot on the next frame
        }
    }

    /** Occluder fade settings, read from the Config directly (cheap, applied live by the options screen). */
    private void readOcc() {
        aoOn = !opt("atmo.enabled").equalsIgnoreCase("false") && !opt("atmo.ao").equalsIgnoreCase("false");   // AO
        aoStr = Math.max(0f, Math.min(1.5f, optF("atmo.ao.strength", 0.5f)));
        aoRadius = Math.max(0.2f, Math.min(3f, optF("atmo.ao.radius", 0.8f)));
        occFade = !cfg.get("render3d.occFade").equalsIgnoreCase("false");
        try { occMin = Math.max(0f, Math.min(1f, Float.parseFloat(cfg.get("render3d.occFade.min").trim()))); } catch (NumberFormatException e) { occMin = 0.22f; }
    }

    /** Options screen (atmo.* / occFade changes): re-reads only the atmosphere / occluder settings, nothing is rebuilt. */
    public void reloadAtmosphere() {
        readOcc();
        loadTune();
        atmo.configure(this::opt);
        NormalMaps.configure(this::opt);   // NORMALS
        SnowSparkle.configure(this::opt);  // SNOW
        ice.configure(this::opt);                                    // ICE
        configureLights();
    }

    /** Options screen: like {@link #reload()} but keeps the tile table (unsaved tile-editor edits survive); the mesh is rebuilt. */
    public void reloadSettings() {
        if (shader == null) return;
        if (loadTune()) readConfig();
        if (mesh != null) {
            mesh.dispose();
            mesh = null;
        }
    }

    // runtime tweaks (input thread = GL thread in libGDX desktop, but keep it safe): accumulated deltas applied in render()
    private static volatile float dPitch, dDist, dFov;
    private static volatile boolean resetCam;

    /** Camera tweak keys: pitch in degrees, distance in tiles, fov in degrees (relative). */
    public static synchronized void adjust(float pitchDeg, float distTiles, float fovDeg) {
        dPitch += pitchDeg;
        dDist += distTiles;
        dFov += fovDeg;
    }

    public static void resetCamera() { resetCam = true; }

    private synchronized void applyTweaks() {
        if (resetCam) {
            resetCam = false;
            dPitch = dDist = dFov = 0;
            readConfig();
        }
        pitch = Math.max(8f, Math.min(88f, pitch + dPitch));
        distance = Math.max(4f, Math.min(60f, distance + dDist));
        fov = Math.max(10f, Math.min(90f, fov + dFov));
        if (dPitch != 0 || dDist != 0 || dFov != 0) {
            System.err.println(String.format("[render3d] camera pitch %.1f distance %.1f fov %.1f", pitch, distance, fov));
        }
        dPitch = dDist = dFov = 0;
    }

    public boolean failed() { return failed; }

    // ---- tile editor hooks (render3d.editor): read access to what the last frame used, and a mesh rebuild ----
    private int lastVx, lastVy, lastVw, lastVh;

    /** Table in use (null before the first frame). */
    public TileTable table() { return table; }

    /** Camera of the last rendered frame (valid after {@link #render}); viewport in {@link #viewport()}. */
    public PerspectiveCamera camera() { return cam; }

    /** Viewport {x, y, w, h} (window pixels, y up) of the last rendered frame. */
    public int[] viewport() { return new int[] {lastVx, lastVy, lastVw, lastVh}; }

    private float[] liftBuf = new float[0];

    /**
     * Per draw lift (negative = raised) for sprites (upright, flat decals, overlay icons) standing on a raised platform / its stairs ({@code deck} / {@code stairs} tile shapes), so
     * characters and objects walk on top instead of sinking into it. Render only; null when the map has no such tiles.
     */
    private float[] platformLift(Scene3D s) {
        MapMesh m = mesh;
        if (m == null || !m.hasPlatforms() || s.map == null) return null;
        if (liftBuf.length < s.count) liftBuf = new float[s.count + 64];
        float T = s.map.tileW;
        boolean any = false;
        for (int i = 0; i < s.count; i++) {
            float l = 0f;
            int o = i * Scene3D.STRIDE;
            l = -m.groundHeight(s.d[o + 8] / T, s.d[o + 9] / T) / vscale;
            liftBuf[i] = l;
            any |= l != 0f;
        }
        return any ? liftBuf : null;
    }

    /** Snapshot the current mesh was built from, or null. */
    public MapSnapshot currentMap() { return mesh == null ? null : mesh.map; }

    /** Rebuilds the map mesh from the same snapshot (and current table contents) on the next frame; render thread only. */
    public void rebuildMesh() {
        if (mesh != null) {
            mesh.dispose();
            mesh = null;
        }
    }

    public String stats() {
        return mesh == null ? "" : "chunks " + mesh.drawnChunks + "/" + mesh.totalChunks + " sprites " + sprites.drawn;
    }

    /**
     * Draws the scene into the given viewport (window pixels). Leaves GL state ready for 2D drawing.
     * Returns false if nothing was drawn (caller shows the 2D frame as is).
     */
    public boolean render(Scene3D s, int vx, int vy, int vw, int vh) {
        if (failed || s == null || s.map == null) return false;
        try {
            if (shader == null) init();
            MapSnapshot m = s.map;
            if (mesh == null || mesh.map != m) {
                if (mesh != null) mesh.dispose();
                long t0 = System.nanoTime();
                boolean in = isIndoor(m);
                mesh = new MapMesh(m, table, tc, surround && !in ? surroundTiles : 0);
                String dd = System.getProperty("g3.render3d.dump");
                if (dd != null) TileDump.dump(m, new File(dd));
                System.err.println(String.format("[render3d] built map %dx%d in %.1f ms", m.w, m.h, (System.nanoTime() - t0) / 1e6));
            }
            float T = m.tileW;
            applyTweaks();
            // look-at point: the player's feet (centres the player on screen), else the centre of the game's view
            // (scripted camera / hidden player). No smoothing: the focus is already interpolated per frame.
            float cx, cz;
            if (s.hasFocus) {
                cx = s.focusX / T;
                cz = s.focusY / T - focusOffset;
            } else {
                cx = (s.camX + s.screenW * 0.5f) / T;
                cz = (s.camY + s.screenH * 0.5f) / T;
            }
            double p = Math.toRadians(pitch);
            float dist = distance;
            cam.fieldOfView = fov;
            cam.viewportWidth = vw;
            cam.viewportHeight = vh;
            cam.position.set(cx, (float) (dist * Math.sin(p)), cz + (float) (dist * Math.cos(p)));
            cam.up.set(0, 1, 0);
            cam.lookAt(cx, 0, cz);
            cam.near = 1f;
            cam.far = 120f;
            cam.update();
            lastVx = vx;
            lastVy = vy;
            lastVw = vw;
            lastVh = vh;

            publishCapture(cx, cz, vw, vh);
            boolean indoor = isIndoor(m);
            caveMap = caveTilesets.contains(m.tileset0) || caveTilesets.contains(m.tileset1);
            int bg = !surround || indoor ? 0 : surroundColor >= 0 ? surroundColor : mesh.bgRgb;
            bgR = (bg >> 16 & 255) / 255f;
            bgG = (bg >> 8 & 255) / 255f;
            bgB = (bg & 255) / 255f;
            atmo.setMap(m, indoor);                                  // ATMO: weather / windows of this map, time of day
            atmo.update(sunAz, sunEl);
            if (!indoor) {
                bgR = Math.min(1f, bgR * atmo.fogMul[0]);
                bgG = Math.min(1f, bgG * atmo.fogMul[1]);
                bgB = Math.min(1f, bgB * atmo.fogMul[2]);
            }
            atmoFog[0] = bgR; atmoFog[1] = bgG; atmoFog[2] = bgB;
            final boolean postOn = post != null && post.begin(vw, vh);          // POST: scene goes to the HDR target (viewport origin 0,0)
            final int ox = postOn ? 0 : vx, oy = postOn ? 0 : vy;
            Gdx.gl.glViewport(ox, oy, vw, vh);
            Gdx.gl.glClearColor(bgR, bgG, bgB, 1);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            Gdx.gl.glDisable(GL20.GL_CULL_FACE);
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

            gothic3.desktop.render3d.props.PropLayer pl = props;
            if (pl != null) {
                try {
                    pl.vscale = vscale;
                    if (pl.fx != null) pl.fx.env(m, indoor, atmo.night, atmo.windows(), atmo.windowsBright());
                    pl.prepare(s);
                    pl.selectLights(cx, cz, (indoor ? 2.0f : 0.7f) * (indoor ? 1f + (atmo.pointBoost - 1f) * 0.4f : atmo.pointBoost), (System.nanoTime() / 1000000L % 1000000L) / 1000f);
                } catch (Throwable t) {
                    pl.fail(t);
                    props = pl = null;
                }
            }
            sprites.skip = pl == null ? null : pl.modelMask();
            sprites.lift = pl == null ? null : pl.lift;
            sprites.back = pl == null ? null : pl.back;
            float[] la = platformLift(s);
            sprites.liftAdd = la;
            if (pl != null) pl.liftAdd = la;
            final boolean swayNow = atmo.swayOn() && pl != null;      // SWAY: plants (props.properties `sway`) bend in the wind
            sprites.sway = swayNow ? pl.sway : null;
            if (pl != null) pl.swayOn = swayNow;
            if (propDump != null && pl != null && frames % 120 == 0) {
                System.err.println("[props] lights " + pl.lightCount + " foot " + pl.footN + " models " + pl.drawnModels + " quads " + pl.drawnQuads);
            }
            boolean lit = lighting && !lightingFailed;
            boolean shadows = false;
            if (lit && !indoor && shadowQuality > 0 && depthShader != null) {
                try {
                    shadows = renderShadowMap(s, cx, cz);
                } catch (Throwable t) {
                    System.err.println("[render3d] shadow pass failed, shadows off: " + t);
                    depthShader = null;
                    disposeShadowFbo();
                }
                // the main pass state (the FBO pass changed viewport / clear / blend)
                if (postOn) post.rebind();                                       // POST: the shadow FBO end() unbound the scene target
                Gdx.gl.glViewport(ox, oy, vw, vh);
                Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
                Gdx.gl.glEnable(GL20.GL_BLEND);
                Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
                Gdx.gl.glClearColor(bgR, bgG, bgB, 1);
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
            }
            shader.bind();
            shader.setUniformMatrix("u_projView", cam.combined);
            shader.setUniformf("u_outScale", postOn ? post.outScale() : 1f);
            setLighting(lit, indoor, shadows);
            if (swayNow) atmo.swayUniforms(shader);                          // SWAY
            shader.setUniformf("u_fogColor", bgR, bgG, bgB);
            // ICE: mirrored standing things into the reflection target (before the occluder uniforms, it resets the main framebuffer / viewport itself)
            final boolean iceOn = lit && !indoor && ice.prepare(mesh, surround && !indoor ? surroundTiles : 0, cam, tc, table);
            if (iceOn) {
                ice.reflect(s, shader, cam, mesh, tc, sprites, pl != null && pl.modelMask() != null ? pl : null, vscale, lean, ox, oy, vw, vh,
                        fog ? fogStart : 1e6f, fog ? fogEnd : 2e6f);
            }
            occluderFade(s, mesh, ox, oy, vw, vh, T);
            if (fog) shader.setUniformf("u_fog", fogStart, fogEnd);
            else shader.setUniformf("u_fog", 1e6f, 2e6f);

            sprites.drawn = 0;
            ids.frame();
            if (propDump != null) propDump.scan(s, ids);
            sprites.skipBlob = shadows && !opt("render3d.blobShadows").equalsIgnoreCase("true");
            // 1) opaque / cutout world geometry (depth write on)
            shader.setUniformf("u_cut", 0.5f);
            Gdx.gl.glDepthMask(true);
            mesh.drawSolid(shader, cam, tc);
            if (iceOn) {   // ICE: reflection / sheen / glints on the ice cells (over the floor just drawn, under decals and sprites)
                float sk = 1f;
                iceSunCol[0] = sunStrength * 1.0f * atmo.sunMul[0] * sk;
                iceSunCol[1] = sunStrength * 0.95f * atmo.sunMul[1] * sk;
                iceSunCol[2] = sunStrength * 0.84f * atmo.sunMul[2] * sk;
                ice.composite(shader, cam, postOn ? post.outScale() : 1f, ox, oy, vw, vh, sunDir, iceSunCol, atmoLight, atmoFog, pl, false);
            }
            // 2) blended ground decals, shadows and flat sprites (depth test, no write)
            Gdx.gl.glDepthMask(false);
            shader.setUniformf("u_cut", 0.02f);
            mesh.drawDecals(shader, cam);
            if (iceOn) ice.composite(shader, cam, postOn ? post.outScale() : 1f, ox, oy, vw, vh, sunDir, iceSunCol, atmoLight, atmoFog, pl, true);   // ICE: skirt ice (after the skirt decal)
            // object ground layers contain opaque black "building interior" rectangles that the 2D roof hides: drop them
            shader.setUniformf("u_dropBlack", 1f);
            sprites.draw(s, Scene3D.PASS_FLAT, vscale, lean);
            shader.setUniformf("u_dropBlack", 0f);
            if (pl != null && shadows && footShadow > 0f) {   // foot shadows of card props (trees, bushes): the sun shadow alone looks detached from the trunk
                float sl = (float) Math.sqrt(sunDir.x * sunDir.x + sunDir.z * sunDir.z);
                if (sl > 1e-3f) sprites.drawFoot(s, pl.foot, pl.footDraw, pl.footN, -sunDir.x / sl, -sunDir.z / sl, footShadow, vscale);
            }
            // 2b) prop models (voxel / lathe meshes of object sprites): real geometry, depth write on
            if (pl != null && sprites.skip != null) {
                try {
                    shader.setUniformf("u_cut", 0.5f);
                    Gdx.gl.glDepthMask(true);
                    pl.draw(s, shader, vscale, lean, false);
                    Gdx.gl.glDepthMask(false);
                    shader.setUniformf("u_cut", 0.02f);
                    int pe = Gdx.gl.glGetError();
                    if (pe != GL20.GL_NO_ERROR) throw new IllegalStateException("GL error 0x" + Integer.toHexString(pe) + " drawing props");
                } catch (Throwable t) {
                    pl.fail(t);
                    props = null;
                    sprites.skip = null;
                    Gdx.gl.glDepthMask(false);
                    shader.setUniformf("u_cut", 0.02f);
                }
            }
            // 3) upright sprites in the game's painter order
            shader.setUniformf("u_aoStr", 0f);                                                  // AO: not on upright cards (characters, trees)
            sprites.rimSide = lit ? atmo.rimUniforms(shader, true, indoor) : 0;               // ATMO rim light on the cards
            sprites.draw(s, Scene3D.PASS_UPRIGHT, vscale, lean);
            atmo.rimUniforms(shader, false, indoor);
            sprites.rimSide = 0;
            if (postOn) {   // POST: sprites have no depth write; add them to the scene depth (DOF / SSAO), colour masked
                Gdx.gl.glColorMask(false, false, false, false);
                Gdx.gl.glDepthMask(true);
                shader.setUniformf("u_cut", 0.5f);
                sprites.draw(s, Scene3D.PASS_UPRIGHT, vscale, lean);
                Gdx.gl.glColorMask(true, true, true, true);
                Gdx.gl.glDepthMask(false);
                shader.setUniformf("u_cut", 0.02f);
            }
            // 3b) ATMO: shafts, window glow, snow / dust / embers / smoke (into the scene target, before the world UI and the HUD)
            if (lit) {
                atmo.draw(cam, cx, cz, pl != null ? pl.lightPos : new float[0], pl != null ? pl.emberCount : 0, atmoFog, fog ? fogStart : 1e6f,
                        fog ? fogEnd : 2e6f, sunDir, indoor, atmoLight);
                shader.bind();
                Gdx.gl.glDepthMask(false);
            }
            // 4) world UI over everything (unlit)
            shader.setUniformf("u_ofP", 0f, 0f, 0f, 0f);                // OCC off for the overlay
            setLighting(false, false, false);
            if (postOn) {   // POST: the world UI writes its depth too (stays as sharp as the thing it marks)
                Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
                Gdx.gl.glDepthFunc(GL20.GL_ALWAYS);
                Gdx.gl.glDepthMask(true);
            } else {
                Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            }
            sprites.iconLift = iconLift;
            sprites.iconScale = iconScale;
            sprites.draw(s, Scene3D.PASS_OVERLAY, vscale, lean);

            Gdx.gl.glDepthMask(true);
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            if (postOn) {   // POST: SSAO / DOF / bloom / grade into the window region
                float fz = s.hasFocus ? cz + focusOffset : cz;
                float focusDepth = (cx - cam.position.x) * cam.direction.x + (0f - cam.position.y) * cam.direction.y + (fz - cam.position.z) * cam.direction.z;
                post.end(vx, vy, vw, vh, focusDepth, fov, cam.near, cam.far, (System.nanoTime() / 1000000L % 1000000000L) / 1000f);
                Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            }
            frames++;
            if (FPSLOG) {
                long now = System.nanoTime();
                if (fpsT0 == 0) fpsT0 = now;
                if (now - fpsT0 >= 2_000_000_000L) {
                    System.err.println(String.format("[fps] %.0f presented/s  lights %d models %d", (frames - fpsF0) * 1e9 / (now - fpsT0),
                            props == null ? 0 : props.lightCount, props == null ? 0 : props.drawnModels));
                    fpsT0 = now;
                    fpsF0 = frames;
                }
            }
            return true;
        } catch (Throwable t) {
            Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0);
            failed = true;
            System.err.println("[render3d] renderer failed, falling back to the 2D view: " + t);
            t.printStackTrace();
            gothic3.platform.RenderConfig.setView3d(false);
            return false;
        }
    }

    private final Vector3 ocT = new Vector3();

    /** Sets the occluder-fade uniforms (shader bound): hero ellipse in window pixels, height / depth limits, eased strength. Render only. */
    private void occluderFade(Scene3D s, MapMesh m, int ox, int oy, int vw, int vh, float T) {
        long now = System.nanoTime();
        float dt = occT == 0 ? 0f : Math.min(0.1f, (now - occT) / 1e9f);
        occT = now;
        boolean on = occFade && s.hasFocus && occMin < 0.999f;
        occAmt = on ? Math.min(1f, occAmt + dt / 0.2f) : Math.max(0f, occAmt - dt / 0.2f);   // ~200 ms ease in / out
        if (occAmt <= 0f) {
            shader.setUniformf("u_ofP", 0f, 0f, 0f, 0f);
            return;
        }
        float fx = s.focusX / T, fz = s.focusY / T;
        float gy = m.groundHeight(fx, fz);
        ocT.set(fx, gy + 0.9f, fz).prj(cam.combined);
        float px = (ocT.x * 0.5f + 0.5f) * vw + ox, py = (ocT.y * 0.5f + 0.5f) * vh + oy;
        float y0 = ocT.y;
        ocT.set(fx + 1f, gy + 0.9f, fz).prj(cam.combined);
        float rx = Math.abs(ocT.x * 0.5f * vw - (px - ox - vw * 0.5f)) * 1.55f;
        ocT.set(fx, gy + 1.9f, fz).prj(cam.combined);
        float ry = Math.abs(ocT.y - y0) * 0.5f * vh * 2.5f;
        if (OCCLOG && frames % 60 == 0) System.err.println(String.format("[occ] amt %.2f c %.0f,%.0f r %.0f,%.0f feet %.1f,%.1f gy %.2f", occAmt, px, py, rx, ry, fx, fz, gy));
        shader.setUniformf("u_of", px, py, Math.max(rx, 4f), Math.max(ry, 4f));
        shader.setUniformf("u_ofP", occMin, fz + 0.35f, gy + 0.3f, occAmt);
    }

    private static final boolean OCCLOG = System.getProperty("g3.render3d.occlog") != null;
    private static final boolean FPSLOG = System.getProperty("g3.render3d.fpslog") != null;
    private long fpsT0, fpsF0;
    private float bgR, bgG, bgB;
    private final Vector3 fpN = new Vector3(), fpF = new Vector3();

    /**
     * Ground footprint of the view frustum (y = 0) as a cell rect plus margins, handed to the game thread as a render hint
     * (RenderConfig.captureHint): the world pass then draws the entities of that area. Never read by game logic.
     */
    private void publishCapture(float cx, float cz, int vw, int vh) {
        float cap = fog ? fogEnd + 4f : 60f;
        float minX = cx, maxX = cx, minZ = cz, maxZ = cz;
        for (int i = 0; i < 4; i++) {
            float nx = (i == 0 || i == 3) ? -1f : 1f, ny = i < 2 ? -1f : 1f;
            fpN.set(nx, ny, -1f).prj(cam.invProjectionView);
            fpF.set(nx, ny, 1f).prj(cam.invProjectionView);
            float dy = fpF.y - fpN.y;
            float t = dy < -1e-6f ? Math.min(1f, Math.max(0f, -fpN.y / dy)) : 1f;
            float gx = fpN.x + (fpF.x - fpN.x) * t, gz = fpN.z + (fpF.z - fpN.z) * t;
            gx = Math.max(cx - cap, Math.min(cx + cap, gx));
            gz = Math.max(cz - cap, Math.min(cz + cap, gz));
            minX = Math.min(minX, gx);
            maxX = Math.max(maxX, gx);
            minZ = Math.min(minZ, gz);
            maxZ = Math.max(maxZ, gz);
        }
        // margins: sprites are up to ~4 tiles tall (feet below the view edge can still show the head), one frame of lag
        gothic3.platform.RenderConfig.setCaptureHint((int) Math.floor(minX) - 3, (int) Math.floor(minZ) - 3, (int) Math.ceil(maxX) + 3, (int) Math.ceil(maxZ) + 6);
    }

    private final Vector3 sunDir = new Vector3(), tv = new Vector3(), tr = new Vector3(), tu = new Vector3();

    private void computeSun() {
        double az = Math.toRadians(atmo.todAz), el = Math.toRadians(atmo.todEl);     // ATMO: time of day (= sunAz / sunEl when off)
        // direction towards the sun; azimuth clockwise from north (north = -z, east = +x)
        sunDir.set((float) (Math.sin(az) * Math.cos(el)), (float) Math.sin(el), (float) (-Math.cos(az) * Math.cos(el))).nor();
    }

    /** Sets the lighting uniforms of the main shader (must be bound). */
    private void setLighting(boolean lit, boolean indoor, boolean shadows) {
        computeSun();
        int nl = lit && props != null ? props.lightCount : 0;
        shader.setUniformf("u_nl", (float) nl);
        NormalMaps.uniforms(shader, lit, cam);   // NORMALS
        SnowSparkle.uniforms(shader, lit, indoor, cam, atmo.snowLevel());   // SNOW
        if (nl > 0) {
            shader.setUniform4fv("u_lp", props.lightPos, 0, nl * 4);
            shader.setUniform3fv("u_lc", props.lightCol, 0, nl * 3);
        }
        setAo(lit, indoor);                                                                       // AO
        if (!lit) {
            atmo.mainUniforms(shader, false, indoor, atmoLight, sunDir.x);                  // ATMO
            shader.setUniformf("u_ambient", 1f, 1f, 1f);
            shader.setUniformf("u_sunCol", 0f, 0f, 0f);
            shader.setUniformf("u_shadowOn", 0f);
            shader.setUniformf("u_sunDir", sunDir.x, sunDir.y, sunDir.z);
            return;
        }
        if (indoor) { // warm ceiling light: soft ambient plus a weak directional term for volume, no shadows
            float ik = nl > 0 ? 0.8f : 1f;     // dimmer room when fires light it
            float im0 = 1f + (atmo.ambMul[0] - 1f) * 0.45f, im1 = 1f + (atmo.ambMul[1] - 1f) * 0.45f, im2 = 1f + (atmo.ambMul[2] - 1f) * 0.45f;   // ATMO time of day
            if (caveMap) { // the cave art is blue-grey stone: a cool light keeps the floor grey (the warm room light turned it tan); fires add warm point lights
                shader.setUniformf("u_ambient", 0.86f * ik * im0, 0.92f * ik * im1, 1.02f * ik * im2);
                shader.setUniformf("u_sunCol", 0.20f, 0.23f, 0.28f);
            } else {
                shader.setUniformf("u_ambient", 0.90f * ik * im0, 0.84f * ik * im1, 0.75f * ik * im2);
                shader.setUniformf("u_sunCol", 0.30f, 0.27f, 0.22f);
            }
        } else {
            shader.setUniformf("u_ambient", ambient * 0.96f * atmo.ambMul[0], ambient * 0.99f * atmo.ambMul[1], ambient * 1.08f * atmo.ambMul[2]);
            shader.setUniformf("u_sunCol", sunStrength * 1.0f * atmo.sunMul[0], sunStrength * 0.95f * atmo.sunMul[1], sunStrength * 0.84f * atmo.sunMul[2]);
        }
        atmoLight[0] = ambient * 0.96f * atmo.ambMul[0] + sunStrength * 0.6f * atmo.sunMul[0];     // ATMO: light level for snow / smoke
        atmoLight[1] = ambient * 0.99f * atmo.ambMul[1] + sunStrength * 0.57f * atmo.sunMul[1];
        atmoLight[2] = ambient * 1.08f * atmo.ambMul[2] + sunStrength * 0.5f * atmo.sunMul[2];
        if (indoor) { atmoLight[0] = 0.9f; atmoLight[1] = 0.84f; atmoLight[2] = 0.75f; }
        atmo.mainUniforms(shader, true, indoor, atmoLight, sunDir.x);
        shader.setUniformf("u_sunDir", sunDir.x, sunDir.y, sunDir.z);
        shader.setUniformf("u_shadowOn", shadows ? 1f : 0f);
        if (shadows) {
            shader.setUniformMatrix("u_lightVP", lightCam.combined);
            float texelWorld = 2f * SHADOW_R / shadowRes;
            shader.setUniformf("u_texel", 1f / shadowRes);
            shader.setUniformf("u_nOff", texelWorld * 1.6f);
            shader.setUniformf("u_bias", (texelWorld * 1.5f + 0.02f) / (lightCam.far - lightCam.near));
            shadowFbo.getColorBufferTexture().bind(1);
            shader.setUniformi("u_shadow", 1);
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        }
    }

    private static final float SHADOW_R = 20f;

    // AO: baked ambient occlusion (AoField), config atmo.ao / atmo.ao.strength / atmo.ao.radius
    private boolean aoOn = true, aoFailed;
    private float aoStr = 0.5f, aoRadius = 0.8f;

    /** Binds the AO texture (unit 2) and sets its uniforms; strength 0 when off / unavailable. Shader must be bound. */
    private void setAo(boolean lit, boolean indoor) {
        float str = 0f;
        if (lit && aoOn && !aoFailed && mesh != null && mesh.aoField != null) {
            try {
                AoField f = mesh.aoField;
                f.texture(aoRadius).bind(2);
                Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
                shader.setUniformi("u_ao", 2);
                shader.setUniformf("u_aoG", AoField.X0, AoField.Z0, 1f / f.sizeX(), 1f / f.sizeZ());
                str = aoStr * (indoor ? 3.0f : 1f);   // interiors: ambient is nearly all the light, so contact shading needs more
            } catch (Throwable t) {
                aoFailed = true;
                System.err.println("[render3d] AO disabled: " + t);
            }
        }
        shader.setUniformf("u_aoStr", str);
        if (props != null) props.aoSink = str > 0f ? mesh.aoField::addBox : null;
    }

    private void disposeShadowFbo() {
        if (shadowFbo != null) shadowFbo.dispose();
        shadowFbo = null;
    }

    /** Renders the sun's depth map around the camera focus (cx, cz). Returns false if unavailable. */
    private boolean renderShadowMap(Scene3D s, float cx, float cz) {
        int res = shadowQuality == 1 ? 1024 : 2048;
        if (shadowFbo == null || shadowRes != res) {
            disposeShadowFbo();
            shadowRes = res;
            shadowFbo = new FrameBuffer(Pixmap.Format.RGBA8888, res, res, true);
            Texture t = shadowFbo.getColorBufferTexture();
            t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            t.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        }
        computeSun();
        // ortho box around the focus (shifted north: the camera sees further north than south); texel snapped in light space
        float fx = cx, fz = cz - 4f;
        float size = 2f * SHADOW_R, texel = size / res;
        tv.set(sunDir).scl(-1f);                                   // light travel direction
        tr.set(tv).crs(0, 1, 0).nor();                             // light-space right
        tu.set(tr).crs(tv).nor();                                  // light-space up
        float r = fx * tr.x + fz * tr.z, u = fx * tu.x + fz * tu.z + 0f;
        float rs = Math.round(r / texel) * texel, us = Math.round(u / texel) * texel;
        // note: only the x/z focus is snapped; the y offset of the centre is constant (0)
        float sx = fx + tr.x * (rs - r) + tu.x * (us - u), sy = tr.y * (rs - r) + tu.y * (us - u), sz = fz + tr.z * (rs - r) + tu.z * (us - u);
        lightCam.setToOrtho(false, size, size);
        lightCam.position.set(sx + sunDir.x * 65f, sy + sunDir.y * 65f, sz + sunDir.z * 65f);
        lightCam.direction.set(tv);
        lightCam.up.set(tu);
        lightCam.near = 1f;
        lightCam.far = 130f;
        lightCam.update();

        shadowFbo.begin();
        Gdx.gl.glViewport(0, 0, res, res);
        Gdx.gl.glClearColor(1, 1, 1, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        depthShader.bind();
        depthShader.setUniformMatrix("u_lightVP", lightCam.combined);
        if (atmo.swayOn()) atmo.swayUniforms(depthShader);                  // SWAY: the shadows follow the swaying plants
        mesh.drawCasters(depthShader, lightCam, tc);
        sprites.drawCasters(depthShader, s, vscale, lean);
        if (props != null && props.modelMask() != null) props.draw(s, depthShader, vscale, lean, true);
        shadowFbo.end();
        int err = Gdx.gl.glGetError();
        if (err != GL20.GL_NO_ERROR) throw new IllegalStateException("GL error 0x" + Integer.toHexString(err) + " in the shadow pass");
        return true;
    }

    public void dispose() {
        disposeShadowFbo();
        if (post != null) post.dispose();
        if (depthShader != null) depthShader.dispose();
        if (mesh != null) mesh.dispose();
        if (sprites != null) sprites.dispose();
        if (props != null) props.dispose();
        if (tc != null) tc.dispose();
        if (shader != null) shader.dispose();
        atmo.dispose();
        ice.dispose();
        mesh = null;
        shader = null;
    }
}
