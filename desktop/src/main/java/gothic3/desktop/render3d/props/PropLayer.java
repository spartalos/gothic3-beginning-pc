package gothic3.desktop.render3d.props;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import gothic3.platform.scene3d.Scene3D;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.microedition.lcdui.Image;

/**
 * 3D models for upright sprites (voxel extrusion, lathe, crossed cards) and fire point lights, decided per captured draw by the
 * prop table ({@link PropRules}). Meshes are built once per (image, source rect, transform) from the sprite pixels and cached; each frame the
 * visible ones are copied (translated to the entity anchor) into one dynamic mesh, batched per texture. Render thread only.
 * Draws that get a model are skipped by the card layer ({@link #isModel}).
 */
public final class PropLayer {
    /** Vertex layout shared with the card layer: pos3, packed colour, uv2, normal3 (9 floats). */
    private static final int F = 9;
    /** AO: receives the world footprint box (x0, x1, z0, z1) and top height of every model drawn in the main pass (null = off). */
    public interface AoSink { void box(float x0, float x1, float z0, float z1, float top, float base); }
    public AoSink aoSink;
    private static final int MAXQ = 16000;
    private static final PropModel NONE = new PropModel(new float[0], 0, 0, PropRules.CARD);

    public static final int MAX_LIGHTS = 24;

    public static final int OFF = 0, VOXEL = 1, FULL = 2;

    private static final class Key {
        final Image im; final int xs, ys, w, h, tr, sig;
        Key(Image im, int xs, int ys, int w, int h, int tr, int sig) { this.im = im; this.xs = xs; this.ys = ys; this.w = w; this.h = h; this.tr = tr; this.sig = sig; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return k.im == im && k.xs == xs && k.ys == ys && k.w == w && k.h == h && k.tr == tr && k.sig == sig;
        }
        @Override public int hashCode() { return ((System.identityHashCode(im) * 31 + xs) * 31 + ys) * 31 + (w * 7 + h * 13 + tr * 17 + sig); }
    }

    private final Mesh mesh;
    private final float[] buf = new float[MAXQ * 4 * F];
    private final ImageIds ids;
    private final gothic3.desktop.render3d.TexSource tex;
    private PropRules rules;
    private final Map<Key, PropModel> cache = new LinkedHashMap<Key, PropModel>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, PropModel> e) { return size() > 2500; }
    };

    // config
    private int mode = FULL;
    private boolean characters;
    private float charDepth = 0.12f;
    private boolean pointLights = true;
    private int maxLights = 12;
    private float lightIntensity = 1f;
    private int sig;

    // per scene
    private Scene3D prepared;
    private int preparedSig = -1;
    private PropModel[] models = new PropModel[0];
    private int[] order = new int[0];
    private int orderN;
    private final BitSet isModel = new BitSet();
    private float[] lc = new float[0]; // collected lights: x,y,z,radius,r,g,b, phase
    private int lcN;
    /**
     * Ground contact fix: per draw, the height (tiles) the sprite is lowered so its lowest opaque pixel touches the floor. Many sprites (trees,
     * stumps, chests) carry transparent rows under the art but are anchored at the bottom of the frame, which in 3D lifts the object off the
     * ground (floating trunks, shadows that start below the trunk). Only object sprites (not characters), only small gaps (<= MAX_GAP px).
     */
    public float[] lift = new float[0];
    /**
     * Per draw: tiles the sprite is moved back (north). Vegetation cards / crosses: the transparent rows under a tree are *ground depth* in the 2D art (the
     * trunk stands on roots that belong to the floor tiles, a tree behind a fence ends above the fence), so the base of the opaque art is placed that far
     * north of the anchor instead of being lowered (which put the trunk 1 to 2 tiles in front of its roots).
     */
    public float[] back = new float[0];
    /** Per prepared draw: vertical squash factor of the model about its feet (token {@code height:H}), 1 = none. */
    private float[] hsq = new float[0];
    /** Per draw: wind sway amount of a vegetation card / cross (token {@code sway}), 0 = none. */
    public float[] sway = new float[0];
    /** Plant sway active this frame (set by the renderer; off = the layer behaves exactly as before). */
    public boolean swayOn;
    /** Per draw: extra lift (negative = raise) set by the renderer each frame, e.g. to stand on a raised platform; null = none. */
    public float[] liftAdd;

    /** Vertical scale of standing sprites (set by the renderer before {@link #prepare}); table-top lifts are given in model tiles, lift units are tiles / vscale. */
    public float vscale = 1f;
    /**
     * Foot (contact) shadows: for upright card / cross props (trees, bushes, stumps) the base of the shadow cast by the flat card is mostly hidden behind
     * the trunk (the sun shadow of a thin trunk starts at the foot but emerges from behind it a tile further on), so the tree looks detached from its
     * shadow. Per entry: draw index, centre x, centre z (world tiles, at the card plane), half width (tiles). Drawn by the sprite layer as a soft ellipse
     * stretched along the shadow direction.
     */
    public float[] foot = new float[0];
    public int[] footDraw = new int[0];
    public int footN;
    private final Map<Key, int[]> footCache = new java.util.HashMap<>();
    private static final int MAX_GAP = 10;
    /** Cards / crosses (trees, stumps, bushes) are vegetation: the big dead trees i23 / i24 carry 14 / 18 transparent rows under the art. */
    private static final int MAX_GAP_CARD = 26;
    private static final float SINK_PX = 0.6f;
    private final Map<Key, Integer> trimCache = new java.util.HashMap<>();
    public int drawnModels, drawnQuads;
    private boolean failed;

    // selected lights (uniform data)
    public final float[] lightPos = new float[MAX_LIGHTS * 4], lightCol = new float[MAX_LIGHTS * 3];
    public int lightCount;
    /** Number of fire lights at the start of the selected lights (embers / smoke only rise from those); = lightCount without extra lights. */
    public int emberCount;
    /** Extra light sources (lamps, candles, windows); null or off = only the fire lights. */
    public LightSources fx;
    private final int[] fireCnt = new int[1];

    public PropLayer(gothic3.desktop.render3d.TexSource tex, ImageIds ids, short[] quadIdx) {
        this.tex = tex;
        this.ids = ids;
        this.rules = PropRules.load();
        mesh = new Mesh(false, MAXQ * 4, MAXQ * 6, gothic3.desktop.render3d.TexSource.ATTRS);
        mesh.setIndices(quadIdx, 0, MAXQ * 6);
    }

    public void configure(String propsMode, boolean chars, float charDepth, boolean lights, int maxLights, float intensity) {
        int m = propsMode.equalsIgnoreCase("off") || propsMode.equalsIgnoreCase("false") ? OFF : propsMode.equalsIgnoreCase("voxel") ? VOXEL : FULL;
        int s = m * 4 + (chars ? 2 : 0) + (lights ? 1 : 0);
        if (m != mode || chars != characters || charDepth != this.charDepth) cache.clear();
        mode = m;
        characters = chars;
        this.charDepth = charDepth;
        pointLights = lights;
        this.maxLights = Math.max(0, Math.min(MAX_LIGHTS, maxLights));
        lightIntensity = intensity;
        sig = s;
        prepared = null;
    }

    public void reloadRules() {
        rules = PropRules.load();
        System.err.println("[render3d] props table: " + rules.source());
        cache.clear();
        prepared = null;
    }

    public String source() { return rules.source(); }

    public boolean isModel(int i) { return isModel.get(i); }

    public BitSet modelMask() { return mode == OFF || failed ? null : isModel; }

    /** Decides models and collects the fire lights of the scene (cheap when the scene was already prepared). */
    public void prepare(Scene3D s) {
        if (s == prepared && preparedSig == sig) return;
        prepared = s;
        preparedSig = sig;
        isModel.clear();
        lcN = 0;
        if (fx != null) fx.beginProps();
        orderN = 0;
        if (s.count > models.length) { models = new PropModel[s.count + 64]; order = new int[s.count + 64]; }
        if (s.count > lift.length) { lift = new float[s.count + 64]; back = new float[s.count + 64]; hsq = new float[s.count + 64]; sway = new float[s.count + 64]; }
        footN = 0;
        tabN = 0;
        if (s.count > tabH.length) { tabH = new float[s.count + 64]; tabCut = new int[s.count + 64]; }
        float T = s.map != null ? s.map.tileW : 16;
        for (int i = 0; i < s.count; i++) {
            models[i] = null;
            lift[i] = 0;
            back[i] = 0;
            hsq[i] = 1;
            sway[i] = 0;
            int o = i * Scene3D.STRIDE, head = s.d[o];
            int pass = head >> 8;
            if ((head & 255) != Scene3D.KIND_IMAGE || (pass != Scene3D.PASS_UPRIGHT && pass != Scene3D.PASS_FLAT) || s.img[i] == null) continue;
            Image im = s.img[i];
            int xs = s.d[o + 1], ys = s.d[o + 2], w = s.d[o + 3], h = s.d[o + 4], tr = s.d[o + 5];
            String id = ids.idOf(im);
            if (fx != null && fx.on) fx.propLights(id, s, o, xs, ys, w, h, tr, T);
            // floor-pass (flat) draws only get a model when the table names the sprite (tables, altars; rugs and effects stay flat decals)
            PropRules.Rule r = pass == Scene3D.PASS_FLAT ? rules.explicit(id, xs, ys, w, h) : rules.lookup(id, xs, ys, w, h);
            if (r == null) continue;
            if (pass == Scene3D.PASS_UPRIGHT && r.sway > 0f && (r.type == PropRules.CARD || r.type == PropRules.CROSS)) sway[i] = r.sway;   // SWAY
            if (pass == Scene3D.PASS_FLAT && r.type == PropRules.TABLE && ids != null) {
                if (tabN == tabs.length) tabs = java.util.Arrays.copyOf(tabs, tabN * 2 + 8);
                tabs[tabN++] = i;
                tabH[i] = r.depth >= 0 ? r.depth : 0.45f;
                tabCut[i] = r.cut;
            }
            if (pointLights && r.light) addLight(s, o, r, T, w, h, tr);
            if (mode == OFF) continue;
            if (r.hide) { isModel.set(i); continue; }   // HIDE: not drawn (card layer skips it, no model)
            boolean perched = r.perch != null && perch(s, i, o, r.perch, h, T);
            if (!perched && id != null && id.charAt(0) == 'i' && !r.floating && r.type != PropRules.LIE && r.type != PropRules.TABLE && tr < 4) {
                int hb = -(s.d[o + 7] + h) + bottomGap(im, xs, ys, w, h, tr);   // height of the lowest opaque pixel above the floor, px
                boolean veg = r.type == PropRules.CARD || r.type == PropRules.CROSS || r.type == PropRules.FENCE_NS;
                if (hb > 0 && hb <= (veg ? MAX_GAP_CARD : MAX_GAP)) {
                    if (veg) { back[i] = hb / T; lift[i] = (hb + SINK_PX) / T; }   // both: the gap is ground depth (move north) AND must not lift the art (lower it)
                    else lift[i] = (hb + SINK_PX) / T;
                }
            }
            boolean sheet = id == null || id.charAt(0) == 's';
            if (sheet && characters) { r = new PropRules.Rule(); r.type = PropRules.VOXEL; r.depth = charDepth; }
            if (pass == Scene3D.PASS_UPRIGHT && id != null && id.charAt(0) == 'i' && !r.floating && !r.noFoot && (r.type == PropRules.CARD || r.type == PropRules.CROSS) && tr < 4 && h >= 14)
                addFoot(s, i, o, im, xs, ys, w, h, tr, T);
            if (r.type == PropRules.CARD || tr > 3) continue;
            int type = r.type;
            if (mode == VOXEL) { if (type == PropRules.CROSS) continue; if (type == PropRules.LATHE) type = PropRules.VOXEL; }
            Key k = new Key(im, xs, ys, w, h, tr, type * 1000 + (int) (r.depth * 100) + r.sides * 100000 + r.cut * 100000000 + (sheet ? 7 : 0));
            PropModel m = cache.get(k);
            if (m == null) {
                try {
                    m = build(im, xs, ys, w, h, tr, type, r, T);
                } catch (RuntimeException e) {
                    System.err.println("[render3d] prop model failed for " + id + ": " + e);
                    m = null;
                }
                if (m == null) m = NONE;
                cache.put(k, m);
            }
            if (m == NONE) continue;
            if (r.height > 0 && tr < 4 && h > 0 && r.type != PropRules.LIE && r.type != PropRules.TABLE) hsq[i] = Math.min(1f, r.height * T / h);
            models[i] = m;
            isModel.set(i);
            order[orderN++] = i;
        }
        if (tabN > 0) liftOnTables(s, T);
        // batch by texture: sort the model draws by image identity (stable by index)
        Integer[] idx = new Integer[orderN];
        for (int j = 0; j < orderN; j++) idx[j] = order[j];
        final Scene3D sc = s;
        java.util.Arrays.sort(idx, (a, b) -> {
            int c = Integer.compare(System.identityHashCode(sc.img[a]), System.identityHashCode(sc.img[b]));
            return c != 0 ? c : Integer.compare(a, b);
        });
        for (int j = 0; j < orderN; j++) order[j] = idx[j];
    }


    private int[] tabs = new int[8];
    private int tabN;
    private float[] tabH = new float[0];
    private int[] tabCut = new int[0];

    /**
     * Small things lying on a table (a pickup on a desk): the game draws them as upright object sprites anchored inside the table's top-down art, which in 3D
     * is a solid box, so they would sit inside it. Every upright object sprite whose anchor lies on the tabletop (the art rect without the pedestal rows)
     * is raised to the table's top height. Render only.
     */
    private void liftOnTables(Scene3D s, float T) {
        for (int i = 0; i < s.count; i++) {
            int o = i * Scene3D.STRIDE, head = s.d[o];
            if ((head & 255) != Scene3D.KIND_IMAGE || (head >> 8) != Scene3D.PASS_UPRIGHT || s.img[i] == null) continue;
            String id = ids.idOf(s.img[i]);
            if (id == null || id.charAt(0) != 'i') continue;
            int ax = s.d[o + 8], ay = s.d[o + 9];
            for (int k = 0; k < tabN; k++) {
                int j = tabs[k], q = j * Scene3D.STRIDE;
                int x0 = s.d[q + 8] + s.d[q + 6], z0 = s.d[q + 9] + s.d[q + 7], tw = s.d[q + 3], th = s.d[q + 4] - tabCut[j];
                if (ax < x0 || ax > x0 + tw || ay < z0 || ay > z0 + th) continue;
                lift[i] -= tabH[j] / vscale;
                break;
            }
        }
    }

    /**
     * Ornaments the game draws on another tile than the thing they sit on (the horned skull of a totem pole is an object two tiles north of the pole whose screen
     * position lands near the top of the pole in the oblique 2D art): in 3D it would stand on the floor behind the pole. Finds the nearest sprite of image
     * {@code base} a little south of the ornament (within one sprite width sideways and the pole height) and moves the ornament onto that sprite's plane
     * (back < 0 = south) and raises it by the same amount, so its screen position stays that of the 2D art but it hangs on the pole. Returns false when nothing is found.
     */
    private boolean perch(Scene3D s, int i, int o, String base, int h, float T) {
        int ax = s.d[o + 8], ay = s.d[o + 9], best = Integer.MAX_VALUE;
        for (int j = 0; j < s.count; j++) {
            if (j == i) continue;
            int q = j * Scene3D.STRIDE;
            if ((s.d[q] & 255) != Scene3D.KIND_IMAGE || (s.d[q] >> 8) != Scene3D.PASS_UPRIGHT || s.img[j] == null) continue;
            int dy = s.d[q + 9] - ay, dx = Math.abs(s.d[q + 8] - ax);
            if (dy <= 0 || dy > -s.d[q + 7] || dx > 16 || dy >= best) continue;
            String jid = ids.idOf(s.img[j]);
            if (jid == null || !(jid.equals(base) || jid.startsWith(base + "."))) continue;
            best = dy;
        }
        if (best == Integer.MAX_VALUE) return false;
        float d = (best + PERCH_FRONT_PX) / T;
        back[i] = -d;
        lift[i] = -d;
        return true;
    }
    private static final float PERCH_FRONT_PX = 1f;

    /** Records the foot span of draw {@code i} (opaque pixels of the lowest rows) for the foot shadow. */
    private void addFoot(Scene3D s, int i, int o, Image im, int xs, int ys, int w, int h, int tr, float T) {
        int[] sp = footSpan(im, xs, ys, w, h, tr);
        if (sp == null) return;
        if (footN >= footDraw.length) {
            footDraw = java.util.Arrays.copyOf(footDraw, footN * 2 + 32);
            foot = java.util.Arrays.copyOf(foot, footDraw.length * 3);
        }
        float x0 = s.d[o + 8] / T + s.d[o + 6] / T;
        foot[footN * 3] = x0 + (sp[0] + sp[1] + 1) * 0.5f / T;
        foot[footN * 3 + 1] = s.d[o + 9] / T - back[i];
        foot[footN * 3 + 2] = Math.max((sp[1] - sp[0] + 1) * 0.5f / T, Math.min(0.6f, 0.1f * h / T));   // tall props: a wider foot
        footDraw[footN++] = i;
    }

    /** {minX, maxX} (destination pixels) of the opaque pixels in the lowest 3 opaque rows of the drawn sprite, or null. */
    private int[] footSpan(Image im, int xs, int ys, int w, int h, int tr) {
        Key k = new Key(im, xs, ys, w, h, tr & 3, 98);
        int[] c = footCache.get(k);
        if (c != null) return c.length == 0 ? null : c;
        int[] px = im.getPixelsInternal();
        int sw = im.getWidth(), rows = 0, mn = w, mx = -1;
        for (int dy = h - 1; dy >= 0 && rows < 3; dy--) {
            int sy = (tr & 1) != 0 ? ys + h - 1 - dy : ys + dy, n = 0, a = w, b = -1;
            for (int x = 0; x < w; x++) {
                if ((px[sy * sw + xs + x] >>> 24) >= 128) { n++; if (x < a) a = x; b = x; }
            }
            if (n < Math.min(2, w)) { if (rows == 0) continue; else break; }
            rows++;
            mn = Math.min(mn, a);
            mx = Math.max(mx, b);
        }
        int[] r = mx < 0 ? new int[0] : ((tr & 2) != 0 ? new int[] {w - 1 - mx, w - 1 - mn} : new int[] {mn, mx});
        if (footCache.size() > 5000) footCache.clear();
        footCache.put(k, r);
        return r.length == 0 ? null : r;
    }

    /** Number of fully transparent (alpha < 128, stray single pixels ignored) rows at the bottom of the drawn sprite. */
    private int bottomGap(Image im, int xs, int ys, int w, int h, int tr) {
        Key k = new Key(im, xs, ys, w, h, tr & 1, 99);
        Integer c = trimCache.get(k);
        if (c != null) return c;
        int[] px = im.getPixelsInternal();
        int sw = im.getWidth(), gap = 0;
        for (int dy = h - 1; dy >= 0; dy--) {
            int sy = (tr & 1) != 0 ? ys + h - 1 - dy : ys + dy, n = 0;
            for (int x = 0; x < w; x++) if ((px[sy * sw + xs + x] >>> 24) >= 128) n++;
            if (n >= Math.min(2, w)) break;
            gap++;
        }
        if (trimCache.size() > 5000) trimCache.clear();
        trimCache.put(k, gap);
        return gap;
    }

    private PropModel build(Image im, int xs, int ys, int w, int h, int tr, int type, PropRules.Rule r, float T) {
        PropBuilder b = new PropBuilder(im, xs, ys, w, h, tr, T);
        switch (type) {
            case PropRules.VOXEL: {
                float d = r.depth >= 0 ? r.depth : Math.max(0.12f, Math.min(0.7f, 0.45f * w / T));
                b.flame = r.light;
                b.core = r.core;
                return b.voxel(d, 0.02f);
            }
            case PropRules.LATHE: return b.lathe(r.sides, 0f);
            case PropRules.CROSS: return b.cross(0.02f);
            case PropRules.LIE: return b.lie(r.depth >= 0 ? r.depth : 0.35f);
            case PropRules.FENCE_NS: return b.fenceNS();
            case PropRules.TABLE: return b.table(r.depth >= 0 ? r.depth : 0.45f, r.cut);
            default: return null;
        }
    }

    private void addLight(Scene3D s, int o, PropRules.Rule r, float T, int w, int h, int tr) {
        if ((lcN + 1) * 8 > lc.length) lc = java.util.Arrays.copyOf(lc, Math.max(64, lc.length * 2));
        int tw = tr >= 4 ? h : w;
        int p = lcN * 8;
        lc[p] = s.d[o + 8] / T + (s.d[o + 6] + tw * 0.5f) / T;
        lc[p + 1] = r.lightHeight;
        lc[p + 2] = s.d[o + 9] / T + 0.3f;
        lc[p + 3] = r.lightRadius;
        lc[p + 4] = (r.lightRgb >> 16 & 255) / 255f * r.lightIntensity;
        lc[p + 5] = (r.lightRgb >> 8 & 255) / 255f * r.lightIntensity;
        lc[p + 6] = (r.lightRgb & 255) / 255f * r.lightIntensity;
        lc[p + 7] = (lc[p] * 7.13f + lc[p + 2] * 3.71f) % 6.283f;   // flicker phase from the position
        lcN++;
    }

    /**
     * Picks the lights nearest to (cx, cz) (up to maxLights), applies flicker and {@code strength} (indoors louder) and fills the uniform arrays.
     * @param timeSec flicker time
     */
    public void selectLights(float cx, float cz, float strength, float timeSec) {
        lightCount = 0;
        emberCount = 0;
        if (fx != null && fx.on && pointLights && maxLights > 0) {   // extra sources: merged, faded selection
            lightCount = fx.select(lc, lcN, cx, cz, strength, lightIntensity, timeSec, Math.min(MAX_LIGHTS, maxLights + 8), lightPos, lightCol, fireCnt);
            emberCount = fireCnt[0];
            return;
        }
        if (!pointLights || maxLights == 0 || lcN == 0) return;
        int n = lcN;
        float[] dd = new float[n];
        Integer[] ix = new Integer[n];
        for (int i = 0; i < n; i++) {
            float dx = lc[i * 8] - cx, dz = lc[i * 8 + 2] - cz;
            dd[i] = dx * dx + dz * dz;
            ix[i] = i;
        }
        java.util.Arrays.sort(ix, (a, b) -> Float.compare(dd[a], dd[b]));
        int c = Math.min(n, maxLights);
        for (int j = 0; j < c; j++) {
            int p = ix[j] * 8, q = j * 4, t = j * 3;
            float ph = lc[p + 7];
            float fl = 1f + 0.10f * (float) Math.sin(timeSec * 11.3 + ph) + 0.06f * (float) Math.sin(timeSec * 23.1 + ph * 1.9)
                    + 0.04f * (float) Math.sin(timeSec * 5.3 + ph * 0.6);
            lightPos[q] = lc[p];
            lightPos[q + 1] = lc[p + 1];
            lightPos[q + 2] = lc[p + 2];
            lightPos[q + 3] = lc[p + 3] * (0.97f + 0.03f * fl);
            float k = strength * lightIntensity * fl;
            lightCol[t] = lc[p + 4] * k;
            lightCol[t + 1] = lc[p + 5] * k;
            lightCol[t + 2] = lc[p + 6] * k;
        }
        lightCount = c;
        emberCount = c;
    }

    /** Forces the next {@link #prepare} to run again (extra light settings changed). */
    public void refresh() { prepared = null; }

    /** Draws the models (main pass or, with {@code casters}, the shadow depth pass). The shader is already bound. */
    public void draw(Scene3D s, ShaderProgram sh, float vscale, float lean, boolean casters) {
        if (mode == OFF || failed || orderN == 0 || prepared != s) return;
        float T = s.map != null ? s.map.tileW : 16;
        int q = 0;
        Texture cur = null;
        drawnModels = 0;
        drawnQuads = 0;
        float swayCur = 0f;
        for (int j = 0; j < orderN; j++) {
            int i = order[j];
            PropModel m = models[i];
            int nq = casters ? m.quads : m.mainQuads;
            Texture t = tex.get(s.img[i]);
            float sa = swayOn && i < sway.length ? sway[i] : 0f;
            if (sa != swayCur) { q = flush(sh, cur, q); swayCur = sa; sh.setUniformf("u_swayAmt", sa); }
            if (t != cur || q + nq > MAXQ) {
                q = flush(sh, cur, q);
                cur = t;
            }
            int o = i * Scene3D.STRIDE;
            boolean lie = m.type == PropRules.LIE || m.type == PropRules.TABLE || m.type == PropRules.FENCE_NS;
            float x0 = s.d[o + 8] / T + s.d[o + 6] / T, yb = (lie ? 0f : -s.d[o + 7] / T - lift[i]) - (liftAdd != null && i < liftAdd.length ? liftAdd[i] : 0f);
            // lying models: the picture's bottom edge sits at the sprite's bottom (anchor + oy + h), heights are absolute
            float az = s.d[o + 9] / T - back[i] + (lie ? (s.d[o + 7] + (s.d[o + 5] >= 4 ? s.d[o + 3] : s.d[o + 4])) / T : 0f);
            if (aoSink != null && !casters) {
                float bx = x0, bz = az, bt = (yb + m.box[3]) * vscale, bb = (yb + m.box[2]) * vscale;
                aoSink.box(bx + m.box[0], bx + m.box[1], bz + m.box[4], bz + m.box[5], bt, bb);
            }
            float[] v = m.v;
            int dst = q * 4 * F;
            int cnt = nq * 4 * F;
            for (int k = 0; k < cnt; k += F) {
                float yy = yb + v[k + 1];
                if (hsq[i] != 1f && !lie) { float yf = yb - s.d[o + 4] / T; yy = yf + (yy - yf) * hsq[i]; }
                float y = yy * vscale;
                buf[dst] = x0 + v[k];
                buf[dst + 1] = y;
                buf[dst + 2] = az + v[k + 2] + y * lean;
                System.arraycopy(v, k + 3, buf, dst + 3, 6);
                dst += F;
            }
            q += nq;
            drawnModels++;
            drawnQuads += nq;
        }
        flush(sh, cur, q);
        if (swayCur != 0f) sh.setUniformf("u_swayAmt", 0f);
    }

    private int flush(ShaderProgram sh, Texture t, int q) {
        if (q == 0 || t == null) return 0;
        t.bind(0);
        sh.setUniformi("u_tex", 0);
        gothic3.desktop.render3d.NormalMaps.page(sh, t, 0, 0);
        mesh.setVertices(buf, 0, q * 4 * F);
        mesh.render(sh, GL20.GL_TRIANGLES, 0, q * 6);
        return 0;
    }

    public void fail(Throwable t) {
        System.err.println("[render3d] props disabled after error: " + t);
        failed = true;
    }

    public void dispose() { mesh.dispose(); }
}
