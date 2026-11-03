package gothic3.desktop.render3d;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.graphics.Camera;
import gothic3.platform.scene3d.MapSnapshot;
import java.util.ArrayList;
import java.util.List;
import javax.microedition.lcdui.Image;

/**
 * Static geometry of one map: layer 0 tiles as floor quads (or standing shapes per {@link TileTable}), layer 1
 * tiles as standing quads at the south edge of their column's base row. Split in chunks for frustum culling.
 * Built from an immutable {@link MapSnapshot} on the render thread.
 */
final class MapMesh {
    static final int CH = 16;
    /** Height of one STEPS tread (tiles). */
    static final float STEP_H = 0.09f;
    private static final float INSET = 0.02f;           // texels
    private static final float WHITE = Color.WHITE.toFloatBits();
    /** Tint of the front face of a deck (the dark underside of the platform). */
    private static final float FACE = new Color(0.72f, 0.7f, 0.68f, 1f).toFloatBits();
    static final float CARD_NY = 0.5f, CARD_NZ = 0.866f;
    private static final float DECAL_Y = 0.004f, DEPTH_PAD = 8f;

    final MapSnapshot map;
    private final TileTable table;
    private final int ncx, ncy;
    private final Chunk[] chunks;
    private final Texture tex0, tex1;
    private final boolean[][] opaqueCache = new boolean[2][];
    private final byte[][] opaqueKnown = new byte[2][];

    private static final class Chunk {
        // 0: sheet0 floor cutout, 1: sheet1 cutout, 2: sheet1 blended decals, 3: untextured (caps), 4: sheet0 blended decals,
        // 5: sheet0 standing cutouts (fences, layer-0 blocks; shadow casters), 6: structure atlas (STRUCTURES: merged houses / walls)
        @SuppressWarnings("unchecked") final List<Mesh>[] meshes = new List[7];
        final BoundingBox box = new BoundingBox();
        boolean empty = true;
    }

    private final QuadBuilder[][] builders;
    /** Baked ambient occlusion height field (null = none). */
    AoField aoField;

    /** Surround skirt (outside the map): meshes and the background colour (packed 0xRRGGBB) derived from the map's ground tiles. */
    private List<Mesh> skirt = new ArrayList<>();
    int bgRgb;

    MapMesh(MapSnapshot m, TileTable table, TextureCache tc) {
        this(m, table, tc, 0);
    }

    /** @param surround tiles of ground skirt around the map (0 = none) */
    MapMesh(MapSnapshot m, TileTable table, TextureCache tc, int surround) {
        this.map = m;
        this.table = table;
        this.tex0 = m.sheet0 == null ? null : tc.get(m.sheet0);
        this.tex1 = m.sheet1 == null ? null : tc.get(m.sheet1);
        ncx = (m.w + CH - 1) / CH;
        ncy = (m.h + CH - 1) / CH;
        chunks = new Chunk[ncx * ncy];
        builders = new QuadBuilder[chunks.length][7];
        // STRUCTURES (render3d/structures/**): merged geometry for houses / walls, their cells are skipped by the per-tile path
        gothic3.desktop.render3d.structures.Structures.Result st = buildStructures(tc);
        computePlateaus();
        build();
        if (st != null) emitStructures(st);
        curLift = 0f;
        emitSkirts();
        computeBackground();
        if (surround > 0) buildSurround(surround);
        for (int i = 0; i < chunks.length; i++) {
            Chunk c = new Chunk();
            int cx = i % ncx, cy = i / ncx;
            c.box.set(new Vector3(cx * CH, -0.6f, cy * CH - DEPTH_PAD), new Vector3(Math.min(m.w, (cx + 1) * CH), DEPTH_PAD + 2 + maxLift, Math.min(m.h, (cy + 1) * CH) + 1));
            for (int b = 0; b < 7; b++) {
                QuadBuilder qb = builders[i][b];
                if (qb != null && !qb.isEmpty()) {
                    c.meshes[b] = qb.build();
                    c.empty = false;
                    if (b != 2 && b != 4) {   // AO: rasterise the geometry heights (baked ambient occlusion, see AoField)
                        try {
                            if (aoField == null) aoField = new AoField(m.w, m.h);
                            for (Mesh am : c.meshes[b]) aoField.addMesh(am);
                        } catch (RuntimeException e) { System.err.println("[render3d] AO field failed: " + e); }
                    }
                }
            }
            chunks[i] = c;
            builders[i] = null;
        }
    }

    // ---------------------------------------------------------------- STRUCTURES hook

    private boolean[] structCovered;
    /** Height (tiles) of stacked layer-0 wall runs (castle crypt interiors, see Structures uniformWall), 0 = layer-0 upright pieces stay one tile high. */
    private float l0WallH;
    private Texture texS;

    private gothic3.desktop.render3d.structures.Structures.Result buildStructures(TextureCache tc) {
        try {
            gothic3.desktop.render3d.structures.Settings cfg = gothic3.desktop.render3d.structures.Settings.load();
            String sd = System.getProperty("g3.render3d.structdump");
            if (sd != null) gothic3.desktop.render3d.structures.StructureDump.dump(map, new java.io.File(sd));
            l0WallH = cfg.uniformWall > 0 && (cfg.uniformWallTilesets.contains(map.tileset0) || cfg.uniformWallTilesets.contains(map.tileset1)) ? cfg.uniformWall : 0f;
            if (!cfg.enabled || map.sheet1 == null) return null;
            if (cfg.caveTilesets.contains(map.tileset0) || cfg.caveTilesets.contains(map.tileset1)) return null; // caves: rock walls are per-tile ramps (tiles.properties)
            gothic3.desktop.render3d.structures.Structures.Result st = gothic3.desktop.render3d.structures.Structures.build(map, table, cfg);
            if (st.atlas == null || st.faces.isEmpty()) return null;
            texS = tc.get(st.atlas);
            structCovered = st.covered;
            System.err.println("[render3d] structures: " + st.summary);
            return st;
        } catch (RuntimeException e) {
            System.err.println("[render3d] structures failed, per-tile fallback: " + e);
            structCovered = null;
            return null;
        }
    }

    private void emitStructures(gothic3.desktop.render3d.structures.Structures.Result st) {
        for (gothic3.desktop.render3d.structures.Structures.Face f : st.faces) {
            float cxm = (f.p[0] + f.p[3] + f.p[6] + f.p[9]) / 4f, czm = (f.p[2] + f.p[5] + f.p[8] + f.p[11]) / 4f;
            curLift = cellLift((int) Math.floor(cxm), (int) Math.floor(czm));
            QuadBuilder q = qb((int) Math.floor(cxm) / CH, (int) Math.floor(czm) / CH, 6);
            q.quad(f.p[0], f.p[1], f.p[2], f.uv[0], f.uv[1], f.p[3], f.p[4], f.p[5], f.uv[2], f.uv[3],
                    f.p[6], f.p[7], f.p[8], f.uv[4], f.uv[5], f.p[9], f.p[10], f.p[11], f.uv[6], f.uv[7], WHITE, f.nx, f.ny, f.nz);
        }
    }

    private QuadBuilder qb(int cx, int cy, int bucket) {
        if (cx < 0) cx = 0;
        if (cy < 0) cy = 0;
        if (cx >= ncx) cx = ncx - 1;
        if (cy >= ncy) cy = ncy - 1;
        int i = cy * ncx + cx;
        QuadBuilder b = builders[i][bucket];
        if (b == null) b = builders[i][bucket] = new QuadBuilder();
        b.dy = curLift;
        return b;
    }

    // ---------------------------------------------------------------- tile queries

    private boolean opaque(int layer, int id) {
        Image sh = layer == 0 ? map.sheet0 : map.sheet1;
        if (sh == null) return false;
        int cols = layer == 0 ? map.cols0 : map.cols1, rows = layer == 0 ? map.rows0 : map.rows1;
        if (opaqueKnown[layer] == null) opaqueKnown[layer] = new byte[256];
        byte k = opaqueKnown[layer][id];
        if (k == 0) {
            int sx = id % cols * map.tileW, sy = id / cols % rows * map.tileH;
            boolean any = false;
            if (sx + map.tileW <= sh.getWidth() && sy + map.tileH <= sh.getHeight()) {
                int[] px = sh.getPixelsInternal();
                outer:
                for (int y = 0; y < map.tileH; y++)
                    for (int x = 0; x < map.tileW; x++)
                        if ((px[(sy + y) * sh.getWidth() + sx + x] >>> 24) != 0) { any = true; break outer; }
            }
            opaqueKnown[layer][id] = k = (byte) (any ? 1 : 2);
        }
        return k == 1;
    }

    /** UV of tile corner (a, b) in destination space [0,1]^2 after MIDP transform t. out = {u, v}. */
    private void uv(int layer, int id, int t, float a, float b, float[] out) {
        Image sh = layer == 0 ? map.sheet0 : map.sheet1;
        int cols = layer == 0 ? map.cols0 : map.cols1, rows = layer == 0 ? map.rows0 : map.rows1;
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
        int sx = id % cols * map.tileW, sy = id / cols % rows * map.tileH;
        out[0] = (sx + u * map.tileW) / sh.getWidth();
        out[1] = (sy + v * map.tileH) / sh.getHeight();
    }

    private final float[][] c = new float[4][2];

    private void corners(int layer, int id, int t) {
        uv(layer, id, t, 0, 0, c[0]);
        uv(layer, id, t, 1, 0, c[1]);
        uv(layer, id, t, 1, 1, c[2]);
        uv(layer, id, t, 0, 1, c[3]);
    }

    /** Quad lying on the ground at height y over cell (x, z). Corner order: NW, NE, SE, SW. */
    private void floor(QuadBuilder q, int x, int z, float y) {
        q.quad(x, y, z, c[0][0], c[0][1], x + 1, y, z, c[1][0], c[1][1], x + 1, y, z + 1, c[2][0], c[2][1], x, y, z + 1, c[3][0], c[3][1], WHITE, 0, 1, 0);
    }

    /**
     * Staircase on a layer-0 cell: {@code n} treads going down towards the south (the viewer) from the floor level, so a doorway reads as steps leading
     * up into a raised room. Treads and risers use the tile art band of their own strip (the art has the tread lines), side faces close the gaps
     * to the neighbouring walls (omitted where the neighbour cell holds the same tile). Uses the corner UVs set by {@link #corners}.
     */
    private void steps(QuadBuilder q, int id, int x, int z, int n) {
        float dh = STEP_H;
        boolean sameW = x > 0 && (byte) (map.cells[z * map.w + x - 1] & 0xFF) == id;
        boolean sameE = x + 1 < map.w && (byte) (map.cells[z * map.w + x + 1] & 0xFF) == id;
        float bottom = -n * dh;
        for (int i = 0; i < n; i++) {
            float za = z + (float) i / n, zb = z + (float) (i + 1) / n, y = -i * dh;
            float ta = (float) i / n, tb = (float) (i + 1) / n;
            // tread top: strip i of the tile (texture point of tile fraction (a, b) = NW + a * (NE-NW) + b * (SW-NW))
            q.quad(x, y, za, tu(ta, 0), tv(ta, 0), x + 1, y, za, tu(ta, 1), tv(ta, 1), x + 1, y, zb, tu(tb, 1), tv(tb, 1), x, y, zb, tu(tb, 0), tv(tb, 0), WHITE, 0, 1, 0);
            // riser at the south edge of the tread, down to the next level (the last one down to the bottom)
            float ytr = -(i + 1) * dh;
            q.quad(x, y, zb, tu(tb, 0), tv(tb, 0), x + 1, y, zb, tu(tb, 1), tv(tb, 1), x + 1, ytr, zb, tu(tb, 1), tv(tb, 1), x, ytr, zb, tu(tb, 0), tv(tb, 0), WHITE, 0, 0, 1);
            if (!sameW) q.quad(x, y, za, tu(ta, 0), tv(ta, 0), x, y, zb, tu(tb, 0), tv(tb, 0), x, bottom, zb, tu(tb, 0), tv(tb, 0), x, bottom, za, tu(ta, 0), tv(ta, 0), WHITE, -1, 0, 0);
            if (!sameE) q.quad(x + 1, y, zb, tu(tb, 1), tv(tb, 1), x + 1, y, za, tu(ta, 1), tv(ta, 1), x + 1, bottom, za, tu(ta, 1), tv(ta, 1), x + 1, bottom, zb, tu(tb, 1), tv(tb, 1), WHITE, 1, 0, 0);
        }
    }

    /** Layer-0 shape of cell (x, z), null outside the map / without a drawn tile. */
    private TileTable.Shape layer0Shape(int x, int z) {
        if (x < 0 || z < 0 || x >= map.w || z >= map.h || map.sheet0 == null) return null;
        int id = (byte) (map.cells[z * map.w + x] & 0xFF);
        if (id < 0 || !opaque(0, id)) return null;
        return table.get(map.mapId, 0, map.tileset0, id, -1, x, z);
    }

    /**
     * Height of the walkable surface (tiles above the ground) at the map position (x, z) in tiles: the deck of a raised platform ({@code deck}) or the tread of the
     * stairs leading up to it ({@code stairs}), else 0. Used to stand characters / objects on top of platforms (render only, never game state).
     */
    float groundHeight(float x, float z) {
        int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
        TileTable.Shape s = layer0Shape(cx, cz);
        if (s != null && s.kind == TileTable.Kind.DECK) return s.param;
        if (s != null && s.kind == TileTable.Kind.FLIGHT) {
            // continuous ramp over the run (the treads are within half a tread of it), so characters never jump between treads
            boolean south = (map.cells[cz * map.w + cx] >> 8 & 1) == 1;
            float f = Math.min(1f, Math.max(0f, z - cz));
            return s.param2 * (flightIndex(cx, cz, south) + (south ? f : 1f - f));
        }
        if (s != null && s.kind == TileTable.Kind.STAIRS) {
            boolean south = (map.cells[cz * map.w + cx] >> 8 & 1) == 1;
            float f = Math.min(1f, Math.max(0f, z - cz));
            return s.param2 * (south ? f : 1f - f);
        }
        return cellLift(cx, cz);
    }

    // ---------------------------------------------------------------- raised plateaus
    // The top of a flight of stairs (wall gap, gate) must lead to a floor of the same height, so the walkable area behind a flight is raised to the
    // height the flight reaches: its floor, the columns standing on it (facades, houses, walls: layer 1 and structures), the characters / props
    // (groundHeight) and the ground decals. Render only: the game map is untouched.

    /** Per cell: height of the raised floor, or of the floor a standing column / structure face in that cell rests on; null if the map has no plateau. */
    private float[] lift;
    /** Lift applied to everything emitted from now on (see QuadBuilder.dy); maximum over the map. */
    private float curLift, maxLift;
    private static final int MAX_PLATEAU_CELLS = 3000;

    /** Raised floor / base height of cell (x, z) in tiles above the ground (0 outside the map or without plateaus). */
    float cellLift(int x, int z) {
        return lift == null || x < 0 || z < 0 || x >= map.w || z >= map.h ? 0f : lift[z * map.w + x];
    }

    private boolean walkable(int x, int z) {
        return x >= 0 && z >= 0 && x < map.w && z < map.h && (map.cells[z * map.w + x] & 0x8000) != 0;
    }

    private boolean isSpecial(int x, int z) {
        TileTable.Shape sh = layer0Shape(x, z);
        return sh != null && (sh.kind == TileTable.Kind.FLIGHT || sh.kind == TileTable.Kind.DECK || sh.kind == TileTable.Kind.STAIRS || sh.kind == TileTable.Kind.STEPS);
    }

    private void computePlateaus() {
        if (Boolean.getBoolean("g3.render3d.noplateau")) return;   // dev switch: the old look (flights end in the air), for before / after shots
        int w = map.w, h = map.h;
        float[] pl = null;
        int[] queue = null;
        for (int z = 0; z < h; z++) for (int x = 0; x < w; x++) {
            TileTable.Shape sh = layer0Shape(x, z);
            if (sh == null || sh.kind != TileTable.Kind.FLIGHT) continue;
            boolean south = (map.cells[z * w + x] >> 8 & 1) == 1;
            int dz = south ? 1 : -1;
            if (isFlight(x, z + dz) && ((map.cells[(z + dz) * w + x] >> 8 & 1) == 1) == south) continue;   // not the top cell of its run
            float top = sh.param2 * (flightIndex(x, z, south) + 1);
            int sx = x, sz = z + dz;
            if (!walkable(sx, sz) || isSpecial(sx, sz)) continue;
            if (pl == null) { pl = new float[w * h]; queue = new int[MAX_PLATEAU_CELLS + 8]; }
            if (pl[sz * w + sx] != 0f) continue;
            int head = 0, tail = 0;
            queue[tail++] = sz * w + sx;
            pl[sz * w + sx] = top;
            boolean over = false;
            while (head < tail) {
                int c = queue[head++], cx = c % w, cz = c / w;
                for (int d = 0; d < 4; d++) {
                    int nx = cx + (d == 0 ? 1 : d == 1 ? -1 : 0), nz = cz + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (!walkable(nx, nz) || isSpecial(nx, nz) || pl[nz * w + nx] != 0f) continue;
                    if (tail >= MAX_PLATEAU_CELLS) { over = true; break; }
                    pl[nz * w + nx] = top;
                    queue[tail++] = nz * w + nx;
                }
                if (over) break;
            }
            if (over) {   // leaked out of an enclosed area: do not raise anything (the flight then ends at the ground like before)
                for (int i = 0; i < tail; i++) pl[queue[i]] = 0f;
                System.err.println("[render3d] plateau behind flight at " + x + "," + z + " is not enclosed, not raised");
            }
        }
        if (pl == null) return;
        // columns standing on the plateau (walls, facades, houses: not walkable cells): rest on the first walkable cell to the south
        float[] out = pl.clone();
        boolean any = false;
        for (int z = 0; z < h; z++) for (int x = 0; x < w; x++) {
            if (pl[z * w + x] != 0f) { any = true; maxLift = Math.max(maxLift, pl[z * w + x]); continue; }
            if (walkable(x, z)) continue;
            for (int zz = z + 1; zz < Math.min(h, z + 13); zz++) {
                if (!walkable(x, zz)) continue;
                out[z * w + x] = pl[zz * w + x];
                break;
            }
        }
        if (any) lift = out;
    }

    /** Vertical faces along the edges of a raised floor towards lower neighbours (the map border, a wall base at the ground), textured with the floor tile's edge. */
    private void emitSkirts() {
        if (lift == null) return;
        int w = map.w, h = map.h;
        float[] u0 = new float[2], u1 = new float[2];
        for (int z = 0; z < h; z++) for (int x = 0; x < w; x++) {
            float hy = lift[z * w + x];
            if (hy <= 0f || !walkable(x, z)) continue;
            int cell = map.cells[z * w + x];
            int id = (byte) (cell & 0xFF);
            if (id < 0 || map.sheet0 == null || !opaque(0, id)) continue;
            int t = cell >> 8 & 7;
            for (int d = 0; d < 4; d++) {   // 0 S, 1 W, 2 E, 3 N
                int nx = x + (d == 1 ? -1 : d == 2 ? 1 : 0), nz = z + (d == 0 ? 1 : d == 3 ? -1 : 0);
                if (nx >= 0 && nz >= 0 && nx < w && nz < h && isFlight(nx, nz)) continue;
                float nl = cellLift(nx, nz);
                if (nl >= hy - 0.01f) continue;
                QuadBuilder q = qb(x / CH, z / CH, 5);
                float px = d == 1 ? x : x + 1, pz = d == 3 ? z : z + 1;
                uv(0, id, t, d == 0 || d == 3 ? 0 : (d == 1 ? 0 : 1), d == 0 || d == 3 ? (d == 0 ? 1 : 0) : 0, u0);
                uv(0, id, t, d == 0 || d == 3 ? 1 : (d == 1 ? 0 : 1), d == 0 || d == 3 ? (d == 0 ? 1 : 0) : 1, u1);
                if (d == 0 || d == 3) q.quad(x, hy, pz, u0[0], u0[1], x + 1, hy, pz, u1[0], u1[1], x + 1, nl, pz, u1[0], u1[1], x, nl, pz, u0[0], u0[1], WHITE, 0, 0, d == 0 ? 1 : -1);
                else q.quad(px, hy, z, u0[0], u0[1], px, hy, z + 1, u1[0], u1[1], px, nl, z + 1, u1[0], u1[1], px, nl, z, u0[0], u0[1], WHITE, d == 1 ? -1 : 1, 0, 0);
            }
        }
    }

    private int platformCells = -1;

    /** True when the map has deck / stairs tiles (cached). */
    boolean hasPlatforms() {
        if (platformCells < 0) {
            int n = lift != null ? 1 : 0;
            for (int z = 0; z < map.h && n == 0; z++) for (int x = 0; x < map.w; x++) {
                TileTable.Shape sh = layer0Shape(x, z);
                if (sh != null && (sh.kind == TileTable.Kind.DECK || sh.kind == TileTable.Kind.STAIRS || sh.kind == TileTable.Kind.FLIGHT)) { n = 1; break; }
            }
            platformCells = n;
        }
        return platformCells > 0;
    }

    private boolean isDeck(int x, int z) {
        TileTable.Shape s = layer0Shape(x, z);
        return s != null && s.kind == TileTable.Kind.DECK;
    }

    /**
     * Raised wooden platform: the tile's art as the top at height {@code h}, with solid faces down to the ground on the south side and on the west / east
     * side wherever the neighbouring cell is not a deck (the north face is never visible). The front face shows the tile's art (board seams), the side
     * faces the tile's own edge column (the material's cross section). No floor is drawn below.
     */
    private void deck(QuadBuilder q, int id, int t, int x, int z, float h) {
        corners(0, id, t);
        q.quad(x, h, z, c[0][0], c[0][1], x + 1, h, z, c[1][0], c[1][1], x + 1, h, z + 1, c[2][0], c[2][1], x, h, z + 1, c[3][0], c[3][1], WHITE, 0, 1, 0);
        if (!isDeck(x, z + 1)) {
            boolean fx = (t & 7) == 2 || (t & 7) == 3;     // horizontal mirror of the art: keep it on the face
            float[] a = new float[2], b = new float[2], cc = new float[2], d = new float[2];
            uv(0, id, 0, fx ? 1 : 0, 0, a);
            uv(0, id, 0, fx ? 0 : 1, 0, b);
            uv(0, id, 0, fx ? 0 : 1, 1, cc);
            uv(0, id, 0, fx ? 1 : 0, 1, d);
            q.quad(x, h, z + 1, a[0], a[1], x + 1, h, z + 1, b[0], b[1], x + 1, 0, z + 1, cc[0], cc[1], x, 0, z + 1, d[0], d[1], FACE, 0, 0, 1);
        }
        float[] u0 = new float[2], u1 = new float[2];
        for (int side = 0; side < 2; side++) {
            if (isDeck(side == 0 ? x - 1 : x + 1, z)) continue;
            uv(0, id, t, side, 0, u0);
            uv(0, id, t, side, 1, u1);
            float px = x + side;
            q.quad(px, h, z, u0[0], u0[1], px, h, z + 1, u0[0], u0[1], px, 0, z + 1, u1[0], u1[1], px, 0, z, u1[0], u1[1], WHITE, side == 0 ? -1 : 1, 0, 0);
        }
    }

    /**
     * Stairs up to a deck of height {@code rise}: {@code n} treads rising towards the north (towards the south when the tile is flipped vertically, transform
     * bit 0: stairs at the back of a platform), the highest one a step below the deck, risers and side faces from the ground. Tread art = the tile's own strips
     * (as {@link #steps}); side faces omitted where the neighbour is the same tile.
     */
    private void stairs(QuadBuilder q, int id, int t, int x, int z, int n, float rise) {
        boolean south = (t & 1) == 1;
        boolean sameW = x > 0 && (byte) (map.cells[z * map.w + x - 1] & 0xFF) == id;
        boolean sameE = x + 1 < map.w && (byte) (map.cells[z * map.w + x + 1] & 0xFF) == id;
        for (int i = 0; i < n; i++) {
            float za = z + (float) i / n, zb = z + (float) (i + 1) / n;
            float y = rise * (south ? i + 1 : n - i) / (n + 1), yn = rise * (south ? i : n - i - 1) / (n + 1);
            float ta = (float) i / n, tb = (float) (i + 1) / n;
            q.quad(x, y, za, tu(ta, 0), tv(ta, 0), x + 1, y, za, tu(ta, 1), tv(ta, 1), x + 1, y, zb, tu(tb, 1), tv(tb, 1), x, y, zb, tu(tb, 0), tv(tb, 0), WHITE, 0, 1, 0);
            if (!south) q.quad(x, y, zb, tu(tb, 0), tv(tb, 0), x + 1, y, zb, tu(tb, 1), tv(tb, 1), x + 1, yn, zb, tu(tb, 1), tv(tb, 1), x, yn, zb, tu(tb, 0), tv(tb, 0), WHITE, 0, 0, 1);
            if (!sameW) q.quad(x, y, za, tu(ta, 0), tv(ta, 0), x, y, zb, tu(tb, 0), tv(tb, 0), x, 0, zb, tu(tb, 0), tv(tb, 0), x, 0, za, tu(ta, 0), tv(ta, 0), WHITE, -1, 0, 0);
            if (!sameE) q.quad(x + 1, y, zb, tu(tb, 1), tv(tb, 1), x + 1, y, za, tu(ta, 1), tv(ta, 1), x + 1, 0, za, tu(ta, 1), tv(ta, 1), x + 1, 0, zb, tu(tb, 1), tv(tb, 1), WHITE, 1, 0, 0);
        }
    }

    /** Number of flight cells of the straight run below cell (x, z) (south of it when it climbs north, north of it when it climbs south). */
    private int flightIndex(int x, int z, boolean south) {
        int k = 0;
        for (int zz = z + (south ? -1 : 1); k < 64; zz += south ? -1 : 1) {
            TileTable.Shape sh = layer0Shape(x, zz);
            if (sh == null || sh.kind != TileTable.Kind.FLIGHT || ((map.cells[zz * map.w + x] >> 8 & 1) == 1) != south) break;
            k++;
        }
        return k;
    }

    /**
     * One cell of a multi-cell staircase (stairs through a wall gap): {@code n} treads rising towards the north, the cell's lowest tread starts where the
     * cell south of it ended ({@code rise} per cell, see {@link #flightIndex}); risers, and side faces from the ground wherever the neighbour is not a flight.
     * Vertically flipped tiles climb to the south instead.
     */
    private void flight(QuadBuilder q, int id, int t, int x, int z, int n, float rise) {
        boolean south = (t & 1) == 1;
        float base = rise * flightIndex(x, z, south);
        boolean sameW = isFlight(x - 1, z), sameE = isFlight(x + 1, z);
        for (int j = 0; j < n; j++) {
            float za = south ? z + (float) j / n : z + 1f - (float) (j + 1) / n, zb = za + 1f / n;
            float y = base + rise * (j + 1) / n, yp = base + rise * j / n;
            float ta = za - z, tb = zb - z;
            q.quad(x, y, za, tu(ta, 0), tv(ta, 0), x + 1, y, za, tu(ta, 1), tv(ta, 1), x + 1, y, zb, tu(tb, 1), tv(tb, 1), x, y, zb, tu(tb, 0), tv(tb, 0), WHITE, 0, 1, 0);
            if (!south) q.quad(x, y, zb, tu(tb, 0), tv(tb, 0), x + 1, y, zb, tu(tb, 1), tv(tb, 1), x + 1, yp, zb, tu(tb, 1), tv(tb, 1), x, yp, zb, tu(tb, 0), tv(tb, 0), WHITE, 0, 0, 1);
            if (!sameW) q.quad(x, y, za, tu(ta, 0), tv(ta, 0), x, y, zb, tu(tb, 0), tv(tb, 0), x, 0, zb, tu(tb, 0), tv(tb, 0), x, 0, za, tu(ta, 0), tv(ta, 0), WHITE, -1, 0, 0);
            if (!sameE) q.quad(x + 1, y, zb, tu(tb, 1), tv(tb, 1), x + 1, y, za, tu(ta, 1), tv(ta, 1), x + 1, 0, za, tu(ta, 1), tv(ta, 1), x + 1, 0, zb, tu(tb, 1), tv(tb, 1), WHITE, 1, 0, 0);
        }
    }

    private boolean isFlight(int x, int z) {
        TileTable.Shape s = layer0Shape(x, z);
        return s != null && s.kind == TileTable.Kind.FLIGHT;
    }

    /** Texture coordinates of the point (fraction b to the south, a to the east) of the current tile (corners() was called). */
    private float tu(float b, float a) { return c[0][0] + a * (c[1][0] - c[0][0]) + b * (c[3][0] - c[0][0]); }
    private float tv(float b, float a) { return c[0][1] + a * (c[1][1] - c[0][1]) + b * (c[3][1] - c[0][1]); }

    /** Standing quad over column x at the plane zPlane, from height y0 to y0+1. Corner order: TL, TR, BR, BL. */
    private void upright(QuadBuilder q, int x, float zPlane, float y0) {
        q.quad(x, y0 + 1, zPlane, c[0][0], c[0][1], x + 1, y0 + 1, zPlane, c[1][0], c[1][1], x + 1, y0, zPlane, c[2][0], c[2][1], x, y0, zPlane, c[3][0], c[3][1], WHITE, 0, 0, 1);
    }

    private void cross(QuadBuilder q, int x, int zBase, float y0) {
        q.quad(x, y0 + 1, zBase + 1, c[0][0], c[0][1], x + 1, y0 + 1, zBase, c[1][0], c[1][1], x + 1, y0, zBase, c[2][0], c[2][1], x, y0, zBase + 1, c[3][0], c[3][1], WHITE, 0, CARD_NY, CARD_NZ);
        q.quad(x, y0 + 1, zBase, c[0][0], c[0][1], x + 1, y0 + 1, zBase + 1, c[1][0], c[1][1], x + 1, y0, zBase + 1, c[2][0], c[2][1], x, y0, zBase, c[3][0], c[3][1], WHITE, 0, CARD_NY, CARD_NZ);
    }

    /** Roof / top cap of a standing slab: flat quad at height yTop between zPlane - depth and zPlane. */
    private void cap(int layer, int id, int t, int x, float zPlane, float yTop, float depth) {
        int cx = x / CH, cy = (int) (zPlane - 1) / CH;
        int sheetPx = topColor(layer, id);
        if (sheetPx == 0) return;
        float col = new Color(((sheetPx >> 16 & 255) * 0.9f) / 255f, ((sheetPx >> 8 & 255) * 0.9f) / 255f, ((sheetPx & 255) * 0.9f) / 255f, 1f).toFloatBits();
        qb(cx, cy, 3).quad(x, yTop, zPlane - depth, 0, 0, x + 1, yTop, zPlane - depth, 1, 0, x + 1, yTop, zPlane, 1, 1, x, yTop, zPlane, 0, 1, col, 0, 1, 0);
    }

    /** Colour (ARGB) of the first opaque row at the top of the tile image, 0 if none. */
    private int topColor(int layer, int id) {
        Image sh = layer == 0 ? map.sheet0 : map.sheet1;
        int cols = layer == 0 ? map.cols0 : map.cols1, rows = layer == 0 ? map.rows0 : map.rows1;
        int sx = id % cols * map.tileW, sy = id / cols % rows * map.tileH;
        if (sh == null || sx + map.tileW > sh.getWidth() || sy + map.tileH > sh.getHeight()) return 0;
        int[] px = sh.getPixelsInternal();
        for (int y = 0; y < map.tileH; y++) {
            long r = 0, g = 0, b = 0, n = 0;
            for (int x = 0; x < map.tileW; x++) {
                int p = px[(sy + y) * sh.getWidth() + sx + x];
                if ((p >>> 24) > 127) { r += p >> 16 & 255; g += p >> 8 & 255; b += p & 255; n++; }
            }
            if (n > 0) return 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
        }
        return 0;
    }

    // ---------------------------------------------------------------- build

    private final int[] eR = new int[8], eId = new int[8], eT = new int[8], eD = new int[8];
    private final TileTable.Shape[] eS = new TileTable.Shape[8];
    private final boolean[] eVis = new boolean[8];

    private void build() {
        int w = map.w, h = map.h;
        int[] cells = map.cells;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int cell = cells[y * w + x];
                curLift = cellLift(x, y);
                // layer 0
                int id = (byte) (cell & 0xFF);
                if (id < 0 && map.sheet0 != null) { // no tile: optional fill (see TileTable.voidTile)
                    int vt = table.voidTile(map.tileset0);
                    if (vt >= 0 && opaque(0, vt)) {
                        corners(0, vt, 0);
                        floor(qb(x / CH, y / CH, 0), x, y, 0f);
                    }
                } else if (id >= 0 && map.sheet0 != null && opaque(0, id)) {
                    TileTable.Shape s = table.get(map.mapId, 0, map.tileset0, id, -1, x, y);
                    corners(0, id, cell >> 8 & 7);
                    emit(0, id, cell >> 8 & 7, x, y, y, s, y + 1f, 0f, false);
                }
                // layer 1: the column whose base row is this one (chain of tiles, nearest to the base first)
                if (map.sheet1 != null) {
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
                        eVis[n] = tid >= 0 && opaque(1, tid);
                        eS[n] = eVis[n] ? table.get(map.mapId, 1, map.tileset1, tid, o, x, r) : null;
                        n++;
                        o = rc >> 28 & 7;
                    }
                    // walk up the column: standing pieces continue from where the piece below ended (slopes lean back)
                    float zc = y + 1f, yc = 0f;
                    int level = 0;
                    for (int i = 0; i < n; i++) {
                        while (level < eD[i]) { yc += 1f; level++; }
                        TileTable.Shape s = eS[i];
                        float z2 = zc, y2 = yc + 1f;
                        if (s != null && s.kind == TileTable.Kind.SLOPE) {
                            double a = Math.toRadians(s.param);
                            z2 = zc - (float) Math.sin(a);
                            y2 = yc + (float) Math.cos(a);
                        }
                        if (s != null && !(structCovered != null && structCovered[eR[i] * w + x])) {
                            corners(1, eId[i], eT[i]);
                            emit(1, eId[i], eT[i], x, eR[i], y, s, zc, yc, i == n - 1, z2, y2);
                        }
                        zc = z2;
                        yc = y2;
                        level = eD[i] + 1;
                    }
                }
            }
        }
    }

    private void emit(int layer, int id, int t, int x, int r, int base, TileTable.Shape s, float zc, float yc, boolean top) {
        emit(layer, id, t, x, r, base, s, zc, yc, top, zc, yc + 1f);
    }

    /**
     * Emits one tile. (zc, yc) = bottom edge of the standing piece (z plane, height), (z2, y2) = its top edge.
     */
    private void emit(int layer, int id, int t, int x, int r, int base, TileTable.Shape s, float zc, float yc, boolean top, float z2, float y2) {
        int sheetBucket = layer == 0 ? 0 : 1;
        int standBucket = layer == 0 ? 5 : 1; // standing pieces of layer 0 (fences, blocks) cast shadows: not mixed with the floor
        switch (s.kind) {
            case HIDDEN:
                return;
            case FLOOR:
                if (s.param > 0 && layer == 0) { // "floor:<tile>": show another tile's art (e.g. replace black void cells by ground)
                    id = (int) s.param;
                    corners(layer, id, 0);
                }
                floor(qb(x / CH, r / CH, sheetBucket), x, r, layer == 0 ? 0f : DECAL_Y);
                return;
            case FLAT:
                floor(qb(x / CH, r / CH, layer == 0 ? 4 : 2), x, r, DECAL_Y);
                return;
            case STEPS:
                if (layer == 0) { steps(qb(x / CH, r / CH, standBucket), id, x, r, Math.max(1, Math.round(s.param))); return; }
                floor(qb(x / CH, r / CH, sheetBucket), x, r, DECAL_Y);
                return;
            case DECK:
                if (layer == 0) { deck(qb(x / CH, r / CH, standBucket), id, t, x, r, s.param); return; }
                floor(qb(x / CH, r / CH, sheetBucket), x, r, DECAL_Y);
                return;
            case STAIRS:
                if (layer == 0) { stairs(qb(x / CH, r / CH, standBucket), id, t, x, r, Math.max(1, Math.round(s.param)), s.param2); return; }
                floor(qb(x / CH, r / CH, sheetBucket), x, r, DECAL_Y);
                return;
            case FLIGHT:
                if (layer == 0) { flight(qb(x / CH, r / CH, standBucket), id, t, x, r, Math.max(1, Math.round(s.param)), s.param2); return; }
                floor(qb(x / CH, r / CH, sheetBucket), x, r, DECAL_Y);
                return;
            case CROSS:
                cross(qb(x / CH, base / CH, standBucket), x, base, yc);
                return;
            case BLOCK:
                block(layer, id, t, x, r, base, zc, yc, s.param, top);
                return;
            case SLOPE:
                piece(qb(x / CH, base / CH, standBucket), x, zc, yc, z2, y2);
                return;
            case UPRIGHT:
            default:
                if (layer == 0 && l0WallH > 0f && s.kind == TileTable.Kind.UPRIGHT) { // wall pieces drawn on layer 0 (the plain brick between the windows of the crypt halls): one run = one wall
                    if (!l0Upright(x, r)) return;
                    int yb = r, yt = r;
                    while (yb + 1 < map.h && l0Upright(x, yb + 1)) yb++;
                    while (yt - 1 >= 0 && l0Upright(x, yt - 1)) yt--;
                    int n = yb - yt + 1;
                    float k = n >= 2 ? l0WallH / n : 1f;
                    piece(qb(x / CH, yb / CH, standBucket), x, yb + 1f, (yb - r) * k, yb + 1f, (yb - r + 1) * k);
                    return;
                }
                piece(qb(x / CH, base / CH, standBucket), x, zc, yc, zc, yc + 1f);
        }
    }

    private boolean l0Upright(int x, int y) {
        int id = (byte) (map.cells[y * map.w + x] & 0xFF);
        if (id < 0 || map.sheet0 == null || !opaque(0, id)) return false;
        if ((byte) (map.cells[y * map.w + x] >> 16 & 0xFF) >= 0) return false; // a layer-1 tile in the same cell draws the wall there (structure / piece): no second one in its plane
        TileTable.Shape s = table.get(map.mapId, 0, map.tileset0, id, -1, x, y);
        return s != null && s.kind == TileTable.Kind.UPRIGHT;
    }

    /** Standing (or leaning) quad over column x: bottom edge at (zb, yb), top edge at (zt, yt). */
    private void piece(QuadBuilder q, int x, float zb, float yb, float zt, float yt) {
        float dy = yt - yb, dz = zt - zb, len = (float) Math.sqrt(dy * dy + dz * dz);
        float ny = len > 1e-6f ? -dz / len : 0f, nz = len > 1e-6f ? dy / len : 1f;
        q.quad(x, yt, zt, c[0][0], c[0][1], x + 1, yt, zt, c[1][0], c[1][1], x + 1, yb, zb, c[2][0], c[2][1], x, yb, zb, c[3][0], c[3][1], WHITE, 0, ny, nz);
    }

    /**
     * Solid block: south face (the tile art), top cap (colour of the tile's top row, column top only), and the west / east
     * side faces of {@code depth} tiles towards the north. Side faces stretch the tile's own edge pixel column (so they read
     * as the material's cross-section, shaded by the lighting) and are left out where an equally deep block of the same
     * level touches (no hidden faces between adjacent blocks, so no z-fighting either). The north face is never visible
     * (fixed camera yaw) and not built; shadows work because the depth pass is double sided.
     */
    private void block(int layer, int id, int t, int x, int r, int base, float zc, float yc, float depth, boolean top) {
        int sheetBucket = layer == 0 ? 5 : 1;
        int cx = x / CH, cy = base / CH;
        piece(qb(cx, cy, sheetBucket), x, zc, yc, zc, yc + 1f);
        if (depth <= 0f) return;
        int lvl = base - r;
        boolean plain = layer == 0 || Math.abs(yc - lvl) < 1e-3f;
        float[] u0 = new float[2], u1 = new float[2];
        for (int side = 0; side < 2; side++) {
            int nx = side == 0 ? x - 1 : x + 1;
            if (plain && neighbourBlockDepth(layer, nx, base, lvl) >= depth - 1e-4f) continue;
            uv(layer, id, t, side, 0, u0);
            uv(layer, id, t, side, 1, u1);
            float px = x + side, nxn = side == 0 ? -1 : 1;
            qb(cx, cy, sheetBucket).quad(px, yc + 1f, zc - depth, u0[0], u0[1], px, yc + 1f, zc, u0[0], u0[1],
                    px, yc, zc, u1[0], u1[1], px, yc, zc - depth, u1[0], u1[1], WHITE, nxn, 0, 0);
        }
        if (top || layer == 0) cap(layer, id, t, x, zc, yc + 1f, depth);
    }

    /** Depth of the block tile of {@code layer} in column nx whose base row is {@code row} at height level {@code lvl}, else -1. */
    private float neighbourBlockDepth(int layer, int nx, int row, int lvl) {
        if (nx < 0 || nx >= map.w) return -1f;
        int cell = map.cells[row * map.w + nx];
        if (layer == 0) {
            int id = (byte) (cell & 0xFF);
            if (id < 0 || !opaque(0, id)) return -1f;
            TileTable.Shape s = table.get(map.mapId, 0, map.tileset0, id, -1, nx, row);
            return s.kind == TileTable.Kind.BLOCK ? s.param : -1f;
        }
        int o = cell >> 12 & 7;
        for (int guard = 0; o != 7 && guard < 8; guard++) {
            int rr = row - o;
            if (rr < 0) break;
            int rc = map.cells[rr * map.w + nx];
            if (o == lvl) {
                int tid = (byte) (rc >> 16 & 0xFF);
                if (tid < 0 || !opaque(1, tid)) return -1f;
                TileTable.Shape s = table.get(map.mapId, 1, map.tileset1, tid, o, nx, rr);
                return s.kind == TileTable.Kind.BLOCK ? s.param : -1f;
            }
            o = rc >> 28 & 7;
        }
        return -1f;
    }

    // ---------------------------------------------------------------- map surround (outside the map)

    /** Background colour: the map's average ground colour, dark and slightly cool (snow = muted grey blue). */
    private void computeBackground() {
        long r = 0, g = 0, b = 0, n = 0;
        int[] cache = new int[256];
        Image sh = map.sheet0;
        if (sh != null) {
            int[] px = sh.getPixelsInternal();
            for (int cell : map.cells) {
                int id = (byte) (cell & 0xFF);
                if (id < 0) continue;
                if (cache[id] == 0) {
                    int sx = id % map.cols0 * map.tileW, sy = id / map.cols0 % map.rows0 * map.tileH;
                    long tr = 0, tg = 0, tb = 0, tn = 0;
                    if (sx + map.tileW <= sh.getWidth() && sy + map.tileH <= sh.getHeight()) {
                        for (int y = 0; y < map.tileH; y += 2) {
                            for (int x = 0; x < map.tileW; x += 2) {
                                int p = px[(sy + y) * sh.getWidth() + sx + x];
                                if ((p >>> 24) > 127) { tr += p >> 16 & 255; tg += p >> 8 & 255; tb += p & 255; tn++; }
                            }
                        }
                    }
                    cache[id] = tn == 0 ? 1 : 0x01000000 | (int) (tr / tn) << 16 | (int) (tg / tn) << 8 | (int) (tb / tn);
                }
                int c = cache[id];
                if (c == 1) continue;
                r += c >> 16 & 255; g += c >> 8 & 255; b += c & 255; n++;
            }
        }
        if (n == 0) { bgRgb = 0; return; }
        int rr = (int) Math.min(255, r / n * 0.30), gg = (int) Math.min(255, g / n * 0.31), bb = (int) Math.min(255, b / n * 0.34);
        bgRgb = rr << 16 | gg << 8 | bb;
    }

    private static int mirror(int v, int n) {
        if (v < 0) v = -v - 1;
        else if (v >= n) v = 2 * n - 1 - v;
        return Math.max(0, Math.min(n - 1, v));
    }

    /**
     * Ground skirt: the nearest (mirrored) floor tiles of the map continued n tiles beyond its edge, alpha fading to zero so the
     * clear / fog colour (the map's background) takes over. One blended decal-style mesh, no chunking (a few thousand quads).
     */
    private void buildSurround(int n) {
        if (map.sheet0 == null) return;
        QuadBuilder q = new QuadBuilder();
        int w = map.w, h = map.h;
        for (int y = -n; y < h + n; y++) {
            for (int x = -n; x < w + n; x++) {
                if (x >= 0 && x < w && y >= 0 && y < h) continue;
                int mx = mirror(x, w), my = mirror(y, h);
                int cell = map.cells[my * w + mx];
                int id = (byte) (cell & 0xFF);
                int t = cell >> 8 & 7;
                if (id < 0) {
                    id = table.voidTile(map.tileset0);
                    t = 0;
                    if (id < 0) continue;
                } else {
                    TileTable.Shape s = table.get(map.mapId, 0, map.tileset0, id, -1, mx, my);
                    if (s.kind == TileTable.Kind.HIDDEN) continue;
                    if (l0WallH > 0f && (byte) (cell >> 16 & 0xFF) >= 0 && s.kind == TileTable.Kind.FLOOR) { // ground under a wall piece / door jamb of the edge row: plain floor, not the jamb's brick
                        int fid = commonFloor();
                        if (fid >= 0) { id = fid; t = 0; s = table.get(map.mapId, 0, map.tileset0, fid, -1, 0, 0); }
                    }
                    if (s.kind != TileTable.Kind.FLOOR && s.kind != TileTable.Kind.FLAT) { // stairs / decks / standing shapes do not continue outside: use the edge cell's ground
                        mx = Math.max(0, Math.min(w - 1, x));
                        my = Math.max(0, Math.min(h - 1, y));
                        cell = map.cells[my * w + mx];
                        id = (byte) (cell & 0xFF);
                        t = cell >> 8 & 7;
                        if (id < 0) continue;
                        s = table.get(map.mapId, 0, map.tileset0, id, -1, mx, my);
                        if (s.kind != TileTable.Kind.FLOOR && s.kind != TileTable.Kind.FLAT) {
                            // castle crypt: the edge cell is a wall piece (door jamb ground): the skirt gets the map's commonest floor tile, no black hole in the ground
                            if (l0WallH <= 0f) continue;
                            int best = commonFloor();
                            boolean found = best >= 0;
                            if (found) { id = best; t = 0; s = table.get(map.mapId, 0, map.tileset0, best, -1, 0, 0); }
                            if (!found) continue;
                        }
                    }
                    if (s.kind == TileTable.Kind.FLOOR && s.param > 0) id = (int) s.param;
                }
                if (!opaque(0, id)) continue;
                float a0 = fade(x, y, w, h, n), a1 = fade(x + 1, y, w, h, n), a2 = fade(x + 1, y + 1, w, h, n), a3 = fade(x, y + 1, w, h, n);
                if (a0 + a1 + a2 + a3 <= 0f) continue;
                corners(0, id, t);
                q.quad(x, 0, y, c[0][0], c[0][1], x + 1, 0, y, c[1][0], c[1][1], x + 1, 0, y + 1, c[2][0], c[2][1], x, 0, y + 1, c[3][0], c[3][1],
                        a(a0), a(a1), a(a2), a(a3), 0, 1, 0);
            }
        }
        if (!q.isEmpty()) skirt = q.build();
    }

    /** The commonest layer-0 tile of the map that is a plain floor, -1 if none. */
    private int commonFloor() {
        int[] cnt = new int[256];
        int best = -1;
        for (int cc : map.cells) {
            int cid = (byte) (cc & 0xFF);
            if (cid < 0) continue;
            if (++cnt[cid] > (best < 0 ? 0 : cnt[best]) && table.get(map.mapId, 0, map.tileset0, cid, -1, 0, 0).kind == TileTable.Kind.FLOOR) best = cid;
        }
        return best;
    }

    private static float a(float v) { return new Color(1f, 1f, 1f, v).toFloatBits(); }

    /** Opacity of the skirt at a grid corner: 1 at the map edge, 0 n tiles out. */
    private static float fade(int px, int pz, int w, int h, int n) {
        float dx = Math.max(0, Math.max(-px, px - w)), dz = Math.max(0, Math.max(-pz, pz - h));
        float d = (float) Math.sqrt(dx * dx + dz * dz) / n;
        return d >= 1f ? 0f : (float) Math.pow(1f - d, 1.4);
    }

    // ---------------------------------------------------------------- draw

    int drawnChunks, totalChunks;

    /** Draws opaque/cutout geometry (buckets 0, 1) with the shader already configured. */
    void drawSolid(ShaderProgram sh, Camera cam, TextureCache tc) {
        drawBucket(sh, cam, 0, tex0);
        drawBucket(sh, cam, 5, tex0);
        drawBucket(sh, cam, 1, tex1);
        drawBucket(sh, cam, 6, texS);
        drawBucket(sh, cam, 3, tc.white());
    }

    /** ICE: the standing geometry only (no floor), for the mirrored reflection pass; {@code cam} supplies the (mirrored) frustum. */
    void drawStanding(ShaderProgram sh, Camera cam, TextureCache tc) {
        drawBucket(sh, cam, 5, tex0);
        drawBucket(sh, cam, 1, tex1);
        drawBucket(sh, cam, 6, texS);
        drawBucket(sh, cam, 3, tc.white());
    }

    /** Shadow depth pass: everything that stands (cutout alpha test in the shader). */
    void drawCasters(ShaderProgram sh, Camera lightCam, TextureCache tc) {
        drawBucket(sh, lightCam, 5, tex0);
        drawBucket(sh, lightCam, 1, tex1);
        drawBucket(sh, lightCam, 6, texS);
        drawBucket(sh, lightCam, 3, tc.white());
    }

    /** Draws blended ground decals (buckets 2, 4). */
    void drawDecals(ShaderProgram sh, Camera cam) {
        if (!skirt.isEmpty() && tex0 != null) {
            tex0.bind(0);
            sh.setUniformi("u_tex", 0);
            NormalMaps.page(sh, tex0, map.tileW, map.tileH);
            boolean snow = SnowSparkle.page(sh, map, 0, tex0);   // SNOW
            for (Mesh m : skirt) m.render(sh, GL20.GL_TRIANGLES);
            if (snow) SnowSparkle.off(sh);
        }
        drawBucket(sh, cam, 4, tex0);
        drawBucket(sh, cam, 2, tex1);
    }

    private void drawBucket(ShaderProgram sh, Camera cam, int bucket, Texture t) {
        if (t == null) return;
        boolean bound = false, snow = false;
        int drawn = 0;
        for (Chunk c : chunks) {
            if (c.empty || c.meshes[bucket] == null) continue;
            if (!cam.frustum.boundsInFrustum(c.box)) continue;
            if (!bound) {
                t.bind(0);
                sh.setUniformi("u_tex", 0);
                if (t == tex0 || t == tex1) NormalMaps.page(sh, t, map.tileW, map.tileH);
                else NormalMaps.page(sh, t, 0, 0);
                if (t == tex0 || t == tex1) snow = SnowSparkle.page(sh, map, t == tex0 ? 0 : 1, t);   // SNOW
                else if (t == texS) snow = SnowSparkle.atlas(sh, t);
                bound = true;
            }
            for (Mesh m : c.meshes[bucket]) m.render(sh, GL20.GL_TRIANGLES);
            drawn++;
        }
        if (snow) SnowSparkle.off(sh);   // SNOW
        if (bucket == 0) { drawnChunks = drawn; totalChunks = chunks.length; }
    }

    void dispose() {
        if (aoField != null) aoField.dispose();
        for (Mesh m : skirt) m.dispose();
        for (Chunk c : chunks) {
            if (c == null) continue;
            for (List<Mesh> l : c.meshes) if (l != null) for (Mesh m : l) m.dispose();
        }
    }
}
