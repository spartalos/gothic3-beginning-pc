package gothic3.desktop.render3d.editor;

import com.badlogic.gdx.math.Vector3;
import gothic3.desktop.render3d.TileTable;
import gothic3.desktop.render3d.structures.Settings;
import gothic3.desktop.render3d.structures.Structures;
import gothic3.desktop.render3d.TileTable.Kind;
import gothic3.desktop.render3d.TileTable.Shape;
import gothic3.platform.scene3d.MapSnapshot;
import java.util.ArrayList;
import java.util.List;
import javax.microedition.lcdui.Image;

/**
 * CPU side copy of the quads that {@code MapMesh} generates for the tile layers (front faces only), for ray picking and
 * highlighting in the tile editor. One {@link Quad} per drawn tile quad, tagged with the tile it belongs to.
 * Mirrors MapMesh's column walk (layer 1 pieces stand on the south edge of their column's base row, slopes lean back).
 */
final class PickGeometry {
    static final float DECAL_Y = 0.004f;

    static final class Quad {
        /** Corners P0..P3 = texture corners (0,0) (1,0) (1,1) (0,1): x,y,z each. */
        final float[] p = new float[12];
        int layer, tile, transform, cx, cy, depth, baseRow;
        Shape shape;
        boolean decal, hiddenGhost;
    }

    static final class Hit {
        Quad quad;
        float t;
    }

    final MapSnapshot map;
    final TileTable table;
    final int tableVersion;
    final List<Quad> quads = new ArrayList<>();
    /** Depth tag of the layer-1 tile stored in each cell (its position in its column chain), -1 if not part of one. */
    final byte[] depth1;
    final int[] used0 = new int[256], used1 = new int[256];

    PickGeometry(MapSnapshot m, TileTable table) {
        this.map = m;
        this.table = table;
        this.tableVersion = table.version();
        depth1 = new byte[m.w * m.h];
        java.util.Arrays.fill(depth1, (byte) -1);
        build();
    }

    int tile0(int x, int y) { return (byte) (map.cells[y * map.w + x] & 0xFF); }
    int tile1(int x, int y) { return (byte) (map.cells[y * map.w + x] >> 16 & 0xFF); }
    int transform0(int x, int y) { return map.cells[y * map.w + x] >> 8 & 7; }
    int transform1(int x, int y) { return map.cells[y * map.w + x] >> 24 & 7; }
    int depth(int x, int y) { return depth1[y * map.w + x]; }

    private boolean opaque(int layer, int id) {
        Image sh = layer == 0 ? map.sheet0 : map.sheet1;
        if (sh == null || id < 0) return false;
        int cols = layer == 0 ? map.cols0 : map.cols1, rows = layer == 0 ? map.rows0 : map.rows1;
        int sx = id % cols * map.tileW, sy = id / cols % rows * map.tileH;
        if (sx + map.tileW > sh.getWidth() || sy + map.tileH > sh.getHeight()) return false;
        int[] px = sh.getPixelsInternal();
        for (int y = 0; y < map.tileH; y++)
            for (int x = 0; x < map.tileW; x++)
                if ((px[(sy + y) * sh.getWidth() + sx + x] >>> 24) != 0) return true;
        return false;
    }

    private final boolean[][] opq = new boolean[2][256];
    private final boolean[][] opqKnown = new boolean[2][256];

    private boolean vis(int layer, int id) {
        if (id < 0) return false;
        if (!opqKnown[layer][id]) {
            opq[layer][id] = opaque(layer, id);
            opqKnown[layer][id] = true;
        }
        return opq[layer][id];
    }

    private Quad make(int layer, int tile, int transform, int cx, int cy, int depth, int base, Shape s, boolean decal) {
        Quad q = new Quad();
        q.layer = layer;
        q.tile = tile;
        q.transform = transform;
        q.cx = cx;
        q.cy = cy;
        q.depth = depth;
        q.baseRow = base;
        q.shape = s;
        q.decal = decal;
        q.hiddenGhost = s.kind == Kind.HIDDEN;
        quads.add(q);
        return q;
    }

    private static void set(Quad q, float... v) { System.arraycopy(v, 0, q.p, 0, 12); }

    private void floor(Quad q, int x, int z, float y) {
        set(q, x, y, z, x + 1, y, z, x + 1, y, z + 1, x, y, z + 1);
    }

    private void stand(Quad q, int x, float zb, float yb, float zt, float yt) {
        set(q, x, yt, zt, x + 1, yt, zt, x + 1, yb, zb, x, yb, zb);
    }

    private void emit(int layer, int id, int t, int x, int r, int base, int depth, Shape s, float zc, float yc, float z2, float y2) {
        switch (s.kind) {
            case FLOOR:
                floor(make(layer, id, t, x, r, depth, base, s, layer == 1), x, r, layer == 0 ? 0f : DECAL_Y);
                return;
            case FLAT:
                floor(make(layer, id, t, x, r, depth, base, s, true), x, r, DECAL_Y);
                return;
            case DECK: // raised platform top at height param
                floor(make(layer, id, t, x, r, depth, base, s, false), x, r, layer == 0 ? s.param : DECAL_Y);
                return;
            case STEPS: case FLIGHT: // picked on the ground (the treads' exact height depends on the run)
                floor(make(layer, id, t, x, r, depth, base, s, false), x, r, layer == 0 ? 0f : DECAL_Y);
                return;
            case STAIRS: // picked half way up the rise
                floor(make(layer, id, t, x, r, depth, base, s, false), x, r, layer == 0 ? s.param2 * 0.5f : DECAL_Y);
                return;
            case CROSS: {
                float y0 = yc;
                set(make(layer, id, t, x, r, depth, base, s, false), x, y0 + 1, base + 1, x + 1, y0 + 1, base, x + 1, y0, base, x, y0, base + 1);
                set(make(layer, id, t, x, r, depth, base, s, false), x, y0 + 1, base, x + 1, y0 + 1, base + 1, x + 1, y0, base + 1, x, y0, base);
                return;
            }
            case SLOPE:
                stand(make(layer, id, t, x, r, depth, base, s, false), x, zc, yc, z2, y2);
                return;
            case HIDDEN:
            case BLOCK:
            case UPRIGHT:
            default:
                stand(make(layer, id, t, x, r, depth, base, s, false), x, zc, yc, zc, yc + 1f);
        }
    }

    private void build() {
        int w = map.w, h = map.h;
        int[] cells = map.cells;
        int[] eR = new int[8], eId = new int[8], eT = new int[8], eD = new int[8];
        Shape[] eS = new Shape[8];
        // structures (render3d/structures): their cells are picked on the structure's merged faces, not on per-tile pieces
        Structures.Result st = null;
        try {
            Settings cfg = Settings.load();
            if (cfg.enabled && map.sheet1 != null) st = Structures.build(map, table, cfg);
        } catch (RuntimeException e) {
            st = null;
        }
        boolean[] covered = st == null ? null : st.covered;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int cell = cells[y * w + x];
                int id = (byte) (cell & 0xFF);
                if (id >= 0 && map.sheet0 != null && vis(0, id)) {
                    used0[id]++;
                    Shape s = table.get(map.mapId, 0, map.tileset0, id, -1, x, y);
                    emit(0, id, cell >> 8 & 7, x, y, y, -1, s, y + 1f, 0f, y + 1f, 1f);
                }
                if (map.sheet1 == null) continue;
                int n = 0, o = cell >> 12 & 7;
                for (int guard = 0; o != 7 && guard < 8; guard++) {
                    int r = y - o;
                    if (r < 0) break;
                    int rc = cells[r * w + x];
                    int tid = (byte) (rc >> 16 & 0xFF);
                    eR[n] = r;
                    eId[n] = tid;
                    eT[n] = rc >> 24 & 7;
                    eD[n] = o;
                    boolean v = tid >= 0 && vis(1, tid);
                    eS[n] = v ? table.get(map.mapId, 1, map.tileset1, tid, o, x, r) : null;
                    if (v) {
                        depth1[r * w + x] = (byte) o;
                        used1[tid]++;
                    }
                    n++;
                    o = rc >> 28 & 7;
                }
                float zc = y + 1f, yc = 0f;
                int level = 0;
                for (int i = 0; i < n; i++) {
                    while (level < eD[i]) { yc += 1f; level++; }
                    Shape s = eS[i];
                    float z2 = zc, y2 = yc + 1f;
                    if (s != null && s.kind == Kind.SLOPE) {
                        double a = Math.toRadians(s.param);
                        z2 = zc - (float) Math.sin(a);
                        y2 = yc + (float) Math.cos(a);
                    }
                    if (s != null && !(covered != null && covered[eR[i] * w + x])) emit(1, eId[i], eT[i], x, eR[i], y, eD[i], s, zc, yc, z2, y2);
                    zc = z2;
                    yc = y2;
                    level = eD[i] + 1;
                }
            }
        }
        if (st != null) {
            for (Structures.Pick pk : st.picks) {
                int dep = Math.max(0, depth1[pk.y * w + pk.x]);
                Shape sh = table.get(map.mapId, 1, map.tileset1, pk.tile, dep, pk.x, pk.y);
                set(make(1, pk.tile, pk.transform, pk.x, pk.y, dep, pk.y + dep, sh, false), pk.p);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ picking

    private final Vector3 e1 = new Vector3(), e2 = new Vector3(), pv = new Vector3(), tv = new Vector3(), qv = new Vector3();

    /** Triangle (a, b, c) = point indices into q.p; returns t, with u, v in out[1], out[2]; or -1. */
    private float tri(Quad q, int a, int b, int c, Vector3 o, Vector3 d, float[] out) {
        float[] p = q.p;
        e1.set(p[b * 3] - p[a * 3], p[b * 3 + 1] - p[a * 3 + 1], p[b * 3 + 2] - p[a * 3 + 2]);
        e2.set(p[c * 3] - p[a * 3], p[c * 3 + 1] - p[a * 3 + 1], p[c * 3 + 2] - p[a * 3 + 2]);
        pv.set(d).crs(e2);
        float det = e1.dot(pv);
        if (Math.abs(det) < 1e-9f) return -1;
        float inv = 1f / det;
        tv.set(o.x - p[a * 3], o.y - p[a * 3 + 1], o.z - p[a * 3 + 2]);
        float u = tv.dot(pv) * inv;
        if (u < 0 || u > 1) return -1;
        qv.set(tv).crs(e1);
        float v = d.dot(qv) * inv;
        if (v < 0 || u + v > 1) return -1;
        float t = e2.dot(qv) * inv;
        out[1] = u;
        out[2] = v;
        return t;
    }

    /** Alpha (0-255) of the tile art at quad parameter (a, b). */
    private int alpha(Quad q, float a, float b) {
        Image sh = q.layer == 0 ? map.sheet0 : map.sheet1;
        if (sh == null) return 255;
        int cols = q.layer == 0 ? map.cols0 : map.cols1, rows = q.layer == 0 ? map.rows0 : map.rows1;
        float u, v;
        switch (q.transform & 7) {
            case 1: u = a; v = 1 - b; break;
            case 2: u = 1 - a; v = b; break;
            case 3: u = 1 - a; v = 1 - b; break;
            case 4: u = b; v = a; break;
            case 5: u = b; v = 1 - a; break;
            case 6: u = 1 - b; v = a; break;
            case 7: u = 1 - b; v = 1 - a; break;
            default: u = a; v = b;
        }
        int px = (int) Math.min(map.tileW - 1, Math.max(0, u * map.tileW));
        int py = (int) Math.min(map.tileH - 1, Math.max(0, v * map.tileH));
        int sx = q.tile % cols * map.tileW + px, sy = q.tile / cols % rows * map.tileH + py;
        if (sx >= sh.getWidth() || sy >= sh.getHeight()) return 0;
        return sh.getPixelsInternal()[sy * sh.getWidth() + sx] >>> 24;
    }

    /** Nearest tile quad hit by the ray whose art is not transparent at the hit point; null if none. */
    Hit pick(Vector3 o, Vector3 d) {
        Hit best = null;
        float[] out = new float[3];
        for (Quad q : quads) {
            float t = tri(q, 0, 1, 2, o, d, out);
            float a = 0, b = 0;
            if (t > 0) { a = out[1] + out[2]; b = out[2]; }
            else {
                t = tri(q, 0, 2, 3, o, d, out);
                if (t <= 0) continue;
                a = out[1];
                b = out[1] + out[2];
            }
            if (best != null && t >= best.t) continue;
            int al = alpha(q, a, b);
            if (al < (q.decal ? 24 : 100)) continue;
            if (best == null) best = new Hit();
            best.quad = q;
            best.t = t;
        }
        return best;
    }

    /** First quad of the tile stored in cell (x, y) of the layer, or null. */
    Quad quadAt(int layer, int x, int y) {
        for (Quad q : quads) if (q.layer == layer && q.cx == x && q.cy == y) return q;
        return null;
    }
}
