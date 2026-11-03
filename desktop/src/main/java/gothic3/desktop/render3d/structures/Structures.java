package gothic3.desktop.render3d.structures;

import gothic3.desktop.render3d.TileTable;
import gothic3.platform.scene3d.MapSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.microedition.lcdui.Image;

/**
 * Structure builder of the 3D view (see docs/render3d.md, "Structures"). The 2D art draws a building as ONE oblique picture
 * (front wall rows at the bottom, the roof or the dark wall top seen from above in the rows over it); tile by tile geometry cannot
 * rebuild that. This class groups the layer-1 cells of the map into structures and builds merged geometry per structure:
 * a continuous front face (with the gable triangle alpha keyed out of its snow), one roof plane / two roof slopes / a flat top,
 * side faces, all textured from a private atlas baked from the tile art. Cells that belong to a built structure are reported in
 * {@link Result#covered} so that the per-tile path of MapMesh skips them.
 *
 * <p>Everything here is a pure function of the immutable {@link MapSnapshot} and the {@link TileTable}.
 */
public final class Structures {
    private Structures() {}

    /** One textured quad. Corner order TL, TR, BR, BL of the texture. */
    public static final class Face {
        public final float[] p = new float[12];
        public final float[] uv = new float[8];
        public float nx, ny, nz;
        final float[] lp = new float[8];
        Atlas.Rect rect;
    }

    /** Cell-sized quad of a structure for editor picking (same corner order as the editor's pick quads: TL TR BR BL). */
    public static final class Pick {
        public final float[] p = new float[12];
        public int x, y, tile, transform, level;
    }

    public static final class Result {
        public Image atlas;
        public final List<Face> faces = new ArrayList<>();
        public final List<Pick> picks = new ArrayList<>();
        /** Layer-1 cells (y * w + x of the cell that stores the tile) rendered by the structures. */
        public boolean[] covered;
        public int structures, strips;
        public String summary = "";
    }

    public static Result build(MapSnapshot m, TileTable table, Settings cfg) {
        return new Builder(m, table, cfg).run();
    }

    // ------------------------------------------------------------------------------------------------------------

    private static final class C {
        int x, y, id, t, tag, B, idx, level;
        TileInfo ti;
        boolean top;
        int parent;
    }

    private static final class Builder {
        final MapSnapshot m;
        final TileTable table;
        final Settings cfg;
        final int w, h;
        final TileInfo[] infos = new TileInfo[256];
        final Atlas atlas = new Atlas();
        final Result res = new Result();
        final Map<Integer, Atlas.Rect> tileRects = new HashMap<>();
        final Map<Integer, Atlas.Rect> colorRects = new HashMap<>();
        final boolean debug = System.getProperty("g3.render3d.structdebug") != null;
        final StringBuilder log = new StringBuilder();

        Builder(MapSnapshot m, TileTable table, Settings cfg) {
            this.m = m;
            this.table = table;
            this.cfg = cfg;
            w = m.w;
            h = m.h;
            res.covered = new boolean[w * h];
        }

        TileInfo info(int id) {
            if (infos[id & 255] == null) infos[id & 255] = new TileInfo(m, id);
            return infos[id & 255];
        }

        // ---------------------------------------------------------------------------------------- grouping

        C[] at;

        int find(int i) {
            while (cs[i].parent != i) {
                cs[i].parent = cs[cs[i].parent].parent;
                i = cs[i].parent;
            }
            return i;
        }

        void union(int a, int b) {
            a = find(a);
            b = find(b);
            if (a != b) cs[b].parent = a;
        }

        C[] cs;

        void unionAll(Map<C, Integer> index, java.util.Set<Long> cuts, int[] root1) {
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    C c = at[y * w + x];
                    if (c == null) continue;
                    int ci = index.get(c);
                    if (y + 1 < h) {
                        C d = at[(y + 1) * w + x];
                        if (d != null && (c.B == d.B || (c.top && d.top && c.tag == d.tag) || (c.tag == 0 && d.tag == 0 && !c.top && !d.top))) union(ci, index.get(d));
                    }
                    if (x + 1 < w) {
                        C e = at[y * w + x + 1];
                        if (e != null && c.B == e.B && !(c.top && e.top && column(c) != column(e)) && (cuts == null || !cuts.contains((long) root1[ci] * 4096L + x))) union(ci, index.get(e));
                    }
                }
        }

        /**
         * A cell of a tall strip of top tiles that all carry the same depth tag (the thin ring wall / its cap of a walled compound): each cell has its own
         * base row, so it only meets a house's wall or roof row at one coincidental row. Such a column does not join the houses beside it.
         */
        boolean column(C c) {
            C a = c.y > 0 ? at[(c.y - 1) * w + c.x] : null, b = c.y + 1 < h ? at[(c.y + 1) * w + c.x] : null;
            return c.top && ((a != null && a.top && a.tag == c.tag) || (b != null && b.top && b.tag == c.tag));
        }

        /** Fraction of transparent pixels in the right (or left) pixel column of the tile as drawn (MIDP transform applied). */
        float edgeTransparent(C c, boolean right) {
            int[] t = new int[c.ti.tw * c.ti.th];
            c.ti.copyTo(t, c.ti.tw, 0, 0, c.t);
            int n = 0, col = right ? c.ti.tw - 1 : 0;
            for (int y = 0; y < c.ti.th; y++) if (!TileInfo.opaque(t[y * c.ti.tw + col])) n++;
            return n / (float) c.ti.th;
        }

        /**
         * Side by side houses with the same base row are one component. Two roof outlines meet where, at the same level, a roof tile
         * ends with a transparent right edge and the tile right of it starts with a transparent left edge (rounded eave corners).
         * Cut keys: component root * 4096 + x of the left cell.
         */
        void findCuts(int root, List<C> comp, java.util.Set<Long> cuts) {
            if (comp.size() < 8) return;
            Map<Integer, C> byIdx = new HashMap<>();
            for (C c : comp) byIdx.put(c.idx, c);
            for (C c : comp) {
                if (c.ti.kind != TileInfo.TOP_SNOW && c.ti.kind != TileInfo.MIXED) continue;
                C d = byIdx.get(c.idx + 1);
                if (d == null || c.x + 1 >= w || (d.ti.kind != TileInfo.TOP_SNOW && d.ti.kind != TileInfo.MIXED)) continue;
                if (edgeTransparent(c, true) >= 0.3f && edgeTransparent(d, false) >= 0.3f) {
                    if (cuts.add((long) root * 4096L + c.x) && debug) log.append("cut between x=").append(c.x).append(" and ").append(c.x + 1).append(" at y=").append(c.y).append('\n');
                }
            }
        }

        Result run() {
            if (m.sheet1 == null) return res;
            int[] tag = new int[w * h];
            java.util.Arrays.fill(tag, -1);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int o = m.cells[y * w + x] >> 12 & 7;
                    for (int g = 0; o != 7 && g < 8 && y - o >= 0; g++) {
                        int r = y - o;
                        tag[r * w + x] = o;
                        o = m.cells[r * w + x] >> 28 & 7;
                    }
                }
            at = new C[w * h];
            List<C> list = new ArrayList<>();
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int i = y * w + x, cell = m.cells[i], tg = tag[i];
                    int id = (byte) (cell >> 16 & 0xFF);
                    if (tg < 0 || id < 0) continue;
                    TileInfo ti = info(id);
                    if (ti.kind == TileInfo.EMPTY) continue;
                    TileTable.Shape s = table.get(m.mapId, 1, m.tileset1, id, tg, x, y);
                    if (s.kind != TileTable.Kind.UPRIGHT && s.kind != TileTable.Kind.SLOPE && s.kind != TileTable.Kind.BLOCK) continue;
                    C c = new C();
                    c.x = x;
                    c.y = y;
                    c.id = id;
                    c.t = cell >> 24 & 7;
                    c.tag = tg;
                    c.B = y + tg;
                    c.idx = i;
                    c.ti = ti;
                    c.top = ti.kind == TileInfo.TOP_SNOW || ti.kind == TileInfo.TOP_BLACK;
                    c.parent = list.size();
                    at[i] = c;
                    list.add(c);
                }
            cs = list.toArray(new C[0]);
            for (int i = 0; i < cs.length; i++) cs[i].parent = i;
            Map<C, Integer> index = new HashMap<>();
            for (int i = 0; i < cs.length; i++) index.put(cs[i], i);
            unionAll(index, null, null);
            Map<Integer, List<C>> comps = new HashMap<>();
            for (int i = 0; i < cs.length; i++) comps.computeIfAbsent(find(i), k -> new ArrayList<>()).add(cs[i]);
            // houses that touch side by side (same base row) are one component: cut them apart where two roof outlines meet
            java.util.Set<Long> cuts = new java.util.HashSet<>();
            for (Map.Entry<Integer, List<C>> e : comps.entrySet()) findCuts(e.getKey(), e.getValue(), cuts);
            if (!cuts.isEmpty() && cfg.split) {
                int[] root1 = new int[cs.length];
                for (int i = 0; i < cs.length; i++) root1[i] = find(i);
                for (int i = 0; i < cs.length; i++) cs[i].parent = i;
                unionAll(index, cuts, root1);
                comps = new HashMap<>();
                for (int i = 0; i < cs.length; i++) comps.computeIfAbsent(find(i), k -> new ArrayList<>()).add(cs[i]);
            }
            List<C> stripCells = new ArrayList<>();
            for (List<C> comp : comps.values()) {
                boolean allTop = true;
                for (C c : comp) if (!c.top) { allTop = false; break; }
                if (allTop) { stripCells.addAll(comp); continue; }
                if (comp.size() < 2) continue;
                try {
                    structure(comp);
                } catch (RuntimeException e) {
                    System.err.println("[render3d] structure failed: " + e);
                }
            }
            strips(stripCells);
            finishRoofs();
            atlas.pack();
            for (Face f : res.faces) {
                for (int i = 0; i < 4; i++) {
                    f.uv[i * 2] = atlas.u(f.rect, f.lp[i * 2]);
                    f.uv[i * 2 + 1] = atlas.v(f.rect, f.lp[i * 2 + 1]);
                }
            }
            res.atlas = atlas.image;
            res.summary = res.structures + " structures, " + res.strips + " strips, " + res.faces.size() + " faces, atlas " + Atlas.WIDTH + "x" + atlas.height;
            if (debug) System.err.print(log);
            String sd = System.getProperty("g3.render3d.structdump");
            if (sd != null && res.atlas != null) {
                try {
                    java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(Atlas.WIDTH, atlas.height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    bi.setRGB(0, 0, Atlas.WIDTH, atlas.height, res.atlas.getPixelsInternal(), 0, Atlas.WIDTH);
                    javax.imageio.ImageIO.write(bi, "png", new java.io.File(sd, "atlas_map" + m.mapId + ".png"));
                } catch (Exception e) {
                    System.err.println("[render3d] atlas dump failed: " + e);
                }
            }
            return res;
        }

        // ---------------------------------------------------------------------------------------- geometry helpers

        void face(float[] q, Atlas.Rect r, float[] lp, float nx, float ny, float nz) {
            Face f = new Face();
            System.arraycopy(q, 0, f.p, 0, 12);
            System.arraycopy(lp, 0, f.lp, 0, 8);
            f.rect = r;
            f.nx = nx;
            f.ny = ny;
            f.nz = nz;
            res.faces.add(f);
        }

        /** Face with corner order TL TR BR BL and the rect region (u0,v0)-(u1,v1) in local pixels. */
        void faceUv(float[] q, Atlas.Rect r, float u0, float v0, float u1, float v1, float nx, float ny, float nz) {
            face(q, r, new float[] {u0, v0, u1, v0, u1, v1, u0, v1}, nx, ny, nz);
        }

        void faceAuto(float[] q, Atlas.Rect r, float u0, float v0, float u1, float v1) {
            // normal from the corner order, pointing up or towards the camera (south)
            float ax = q[3] - q[0], ay = q[4] - q[1], az = q[5] - q[2];
            float bx = q[9] - q[0], by = q[10] - q[1], bz = q[11] - q[2];
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l < 1e-6f) { nx = 0; ny = 1; nz = 0; } else { nx /= l; ny /= l; nz /= l; }
            if (ny < 0 || (Math.abs(ny) < 1e-3f && nz < 0)) { nx = -nx; ny = -ny; nz = -nz; }
            faceUv(q, r, u0, v0, u1, v1, nx, ny, nz);
        }

        Atlas.Rect tileRect(TileInfo ti, int t) {
            int key = ti.id << 3 | t & 7;
            Atlas.Rect r = tileRects.get(key);
            if (r == null) {
                int[] px = new int[ti.tw * ti.th];
                ti.copyTo(px, ti.tw, 0, 0, t);
                r = atlas.add(px, ti.tw, ti.th);
                tileRects.put(key, r);
            }
            return r;
        }

        /** 2x2 solid colour rect (ARGB, alpha forced opaque). */
        Atlas.Rect colorRect(int argb) {
            argb |= 0xFF000000;
            Atlas.Rect r = colorRects.get(argb);
            if (r == null) {
                r = atlas.add(new int[] {argb, argb, argb, argb}, 2, 2);
                colorRects.put(argb, r);
            }
            return r;
        }

        static int scale(int argb, float f) {
            int r = Math.min(255, (int) ((argb >> 16 & 255) * f)), g = Math.min(255, (int) ((argb >> 8 & 255) * f)), b = Math.min(255, (int) ((argb & 255) * f));
            return 0xFF000000 | r << 16 | g << 8 | b;
        }

        static int mix(int a, int b, float f) {
            int r = (int) ((a >> 16 & 255) * (1 - f) + (b >> 16 & 255) * f), g = (int) ((a >> 8 & 255) * (1 - f) + (b >> 8 & 255) * f), bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
            return 0xFF000000 | r << 16 | g << 8 | bl;
        }

        static float luma(int c) { return 0.299f * (c >> 16 & 255) + 0.587f * (c >> 8 & 255) + 0.114f * (c & 255); }

        /** Average colour of the lighter half of the opaque pixels (the lit wood / stone, not the joints and shadows). */
        int lightTone(TileInfo ti) {
            int[] l = new int[256];
            int n = 0;
            for (int p : ti.px) if (TileInfo.opaque(p)) { l[Math.min(255, (int) luma(p))]++; n++; }
            if (n == 0) return 0xFF808080;
            int acc = 0, med = 0;
            while (med < 255 && acc + l[med] < n / 2) acc += l[med++];
            long r = 0, g = 0, b = 0, k = 0;
            for (int p : ti.px) if (TileInfo.opaque(p) && luma(p) >= med) { r += p >> 16 & 255; g += p >> 8 & 255; b += p & 255; k++; }
            return k == 0 ? avgColor(ti) : 0xFF000000 | (int) (r / k) << 16 | (int) (g / k) << 8 | (int) (b / k);
        }

        /** Wall tops must not read as holes: lift dark tones to a minimum brightness (keeps the hue). */
        static int lift(int c) {
            float lu = luma(c);
            float f = lu < 88f ? Math.min(2.4f, 88f / Math.max(24f, lu)) : 1f;
            return scale(c, f);
        }

        int avgColor(TileInfo ti) {
            long r = 0, g = 0, b = 0, n = 0;
            for (int p : ti.px) if (TileInfo.opaque(p)) { r += p >> 16 & 255; g += p >> 8 & 255; b += p & 255; n++; }
            return n == 0 ? 0xFF808080 : 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
        }

        // ---------------------------------------------------------------------------------------- strips (top-only structures)

        void strips(List<C> cells) {
            if (cells.isEmpty()) return;
            Map<Integer, C> set = new HashMap<>();
            for (C c : cells) set.put(c.idx, c);
            Map<Integer, Boolean> seen = new HashMap<>();
            for (C start : cells) {
                if (seen.containsKey(start.idx)) continue;
                List<C> region = new ArrayList<>();
                List<C> stack = new ArrayList<>();
                stack.add(start);
                seen.put(start.idx, true);
                while (!stack.isEmpty()) {
                    C c = stack.remove(stack.size() - 1);
                    region.add(c);
                    int[][] nb = {{c.x - 1, c.y}, {c.x + 1, c.y}, {c.x, c.y - 1}, {c.x, c.y + 1}};
                    for (int[] n : nb) {
                        if (n[0] < 0 || n[1] < 0 || n[0] >= w || n[1] >= h) continue;
                        int ni = n[1] * w + n[0];
                        C d = set.get(ni);
                        if (d != null && !seen.containsKey(ni)) { seen.put(ni, true); stack.add(d); }
                    }
                }
                if (region.size() < 2) continue;
                strip(region);
            }
        }

        /**
         * Castle crypt interior (uniformWallTilesets): a beam of cap tiles over a doorway, with no wall face below it. It is the lintel of the wall on both sides: a flat top at the wall
         * height over the thickness of that wall (flush with the wall tops, the door below is as high as the wall), not a low slab floating behind the door with loose edges.
         * Returns false when no wall stands beside the beam (then the plain strip is drawn).
         */
        boolean lintel(List<C> region) {
            if (cfg.uniformWall <= 0 || !(cfg.uniformWallTilesets.contains(m.tileset0) || cfg.uniformWallTilesets.contains(m.tileset1))) return false;
            int[] wallX = new int[region.size()], wallB = new int[region.size()];
            for (int i = 0; i < region.size(); i++) {
                C c = region.get(i);
                wallX[i] = -1;
                if (c.y + 1 >= h) return false;
                for (int d = 1; d <= 3 && wallX[i] < 0; d++)
                    for (int sgn = -1; sgn <= 1 && wallX[i] < 0; sgn += 2) {
                        int nx = c.x + sgn * d;
                        if (nx < 0 || nx >= w) continue;
                        C f = at[(c.y + 1) * w + nx];
                        if (f == null || f.top || f.ti.kind != TileInfo.FACE) continue;
                        int by = c.y + 1;
                        while (by + 1 < h && at[(by + 1) * w + nx] != null && !at[(by + 1) * w + nx].top) by++;
                        wallX[i] = nx;
                        wallB[i] = by;
                    }
                if (wallX[i] < 0) return false;
            }
            for (int i = 0; i < region.size(); i++) {
                C c = region.get(i);
                C f = at[(c.y + 1) * w + wallX[i]];
                res.covered[c.idx] = true;
                Atlas.Rect r = colorRect(lift(lightTone(f.ti)));
                float x = c.x, zb = wallB[i] + 1f, hh = cfg.uniformWall;
                float[] q = new float[] {x, hh, zb - 1f, x + 1, hh, zb - 1f, x + 1, hh, zb, x, hh, zb};
                faceUv(q, r, 0, 0, 2, 2, 0, 1, 0);
                Pick pk = new Pick();
                System.arraycopy(q, 0, pk.p, 0, 12);
                pk.x = c.x; pk.y = c.y; pk.tile = c.id; pk.transform = c.t; pk.level = 0;
                res.picks.add(pk);
                // front lip: closes the gap between the top and the door's top edge (no dark line / loose edge above the door)
                Atlas.Rect lip = colorRect(scale(lift(lightTone(f.ti)), 0.8f));
                q = new float[] {x, hh, zb, x + 1, hh, zb, x + 1, hh - 0.3f, zb, x, hh - 0.3f, zb};
                faceUv(q, lip, 0, 0, 2, 2, 0, 0, 1);
            }
            res.strips++;
            return true;
        }

        void strip(List<C> region) {
            if (lintel(region)) return;
            res.strips++;
            Map<Integer, C> set = new HashMap<>();
            for (C c : region) set.put(c.idx, c);
            float hh = cfg.stripHeight;
            float[] q = new float[12];
            for (C c : region) {
                res.covered[c.idx] = true;
                Atlas.Rect r = tileRect(c.ti, c.t);
                float x = c.x, z = c.y;
                q = new float[] {x, hh, z, x + 1, hh, z, x + 1, hh, z + 1, x, hh, z + 1};
                faceUv(q, r, 0, 0, c.ti.tw, c.ti.th, 0, 1, 0);
                Pick pk = new Pick();
                System.arraycopy(q, 0, pk.p, 0, 12);
                pk.x = c.x; pk.y = c.y; pk.tile = c.id; pk.transform = c.t; pk.level = 0;
                res.picks.add(pk);
                Atlas.Rect side = colorRect(scale(avgColor(c.ti), 0.8f));
                if (c.x == 0 || !set.containsKey(c.idx - 1)) {
                    q = new float[] {x, hh, z, x, hh, z + 1, x, 0, z + 1, x, 0, z};
                    faceUv(q, side, 0, 0, 2, 2, -1, 0, 0);
                }
                if (c.x == w - 1 || !set.containsKey(c.idx + 1)) {
                    q = new float[] {x + 1, hh, z + 1, x + 1, hh, z, x + 1, 0, z, x + 1, 0, z + 1};
                    faceUv(q, side, 0, 0, 2, 2, 1, 0, 0);
                }
                if (!set.containsKey(c.idx + w)) {
                    q = new float[] {x, hh, z + 1, x + 1, hh, z + 1, x + 1, 0, z + 1, x, 0, z + 1};
                    faceUv(q, side, 0, 0, 2, 2, 0, 0, 1);
                }
            }
            if (debug) log.append("strip ").append(region.size()).append(" cells at ").append(region.get(0).x).append(',').append(region.get(0).y).append('\n');
        }

        // ---------------------------------------------------------------------------------------- structures

        void structure(List<C> comp) {
            int Bs = Integer.MIN_VALUE;
            for (C c : comp) if (!c.top) Bs = Math.max(Bs, c.B);
            List<C> cells = new ArrayList<>();
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, maxLevel = 0;
            for (C c : comp) {
                c.level = Bs - c.y;
                if (c.level < 0 || c.level > 24) continue;
                cells.add(c);
                x0 = Math.min(x0, c.x);
                x1 = Math.max(x1, c.x);
                maxLevel = Math.max(maxLevel, c.level);
            }
            if (cells.size() < 2) return;
            // south wall with a cap row above its art row (map 24): the cap row is a beam drawn over the doorway too; the finished south wall (below) has its own cap
            if (cfg.southWall > 0 && maxLevel == 1 && Bs == h - 1 && (cfg.uniformWallTilesets.contains(m.tileset0) || cfg.uniformWallTilesets.contains(m.tileset1))) {
                boolean capOnly = true;
                for (C c : cells) if (c.level == 1 && !c.top) capOnly = false;
                if (capOnly) {
                    for (C c : cells) if (c.level == 1) res.covered[c.idx] = true; // swallowed: not drawn as a loose tile either
                    cells.removeIf(c -> c.level == 1);
                    maxLevel = 0;
                }
            }
            int nx = x1 - x0 + 1, L = maxLevel + 1;
            C[][] g = new C[L][nx];
            for (C c : cells) g[c.level][c.x - x0] = c;
            int[] p = new int[L], face = new int[L], mix = new int[L], topc = new int[L];
            for (C c : cells) {
                p[c.level]++;
                if (c.ti.kind == TileInfo.FACE) face[c.level]++;
                else if (c.ti.kind == TileInfo.MIXED) mix[c.level]++;
                else topc[c.level]++;
            }
            int hw = 0;
            while (hw < L && p[hw] > 0 && mix[hw] == 0 && face[hw] * 2 >= p[hw]) hw++;
            int ng = 0;
            while (hw + ng < L && mix[hw + ng] > 0) ng++;
            if (hw == 0 && ng == 0) hw = L; // nothing wall-like at the bottom: everything is face
            int Hf = hw + ng, nT = L - Hf;
            boolean gabled = ng > 0;
            boolean indoor = cfg.indoorTilesets.contains(m.tileset0) || cfg.indoorTilesets.contains(m.tileset1);
            float zf = Bs + 1f;
            float sc = 1f;
            if (!gabled && nT > 0 && cfg.capWall > 0 && hw > cfg.capWall) {
                int black = 0, tot = 0;
                for (C c : cells) if (c.level >= hw) { tot++; if (c.ti.kind == TileInfo.TOP_BLACK) black++; }
                if (tot > 0 && black * 10 >= tot * 6) sc = cfg.capWall / hw;
            } else if (!gabled && nT > 0 && hw > cfg.capWall && cfg.capWall > 0) {
                sc = 1f;
            }
            // castle crypt interiors: one wall height for every wall piece (back walls 3 rows, front segments 2 rows, the centre one 3 rows with its ledge)
            if (!gabled && hw >= 2 && cfg.uniformWall > 0 && (cfg.uniformWallTilesets.contains(m.tileset0) || cfg.uniformWallTilesets.contains(m.tileset1))) sc = cfg.uniformWall / hw;
            // -- the south wall row of a castle crypt interior (bottom map row, one art row): finished as a whole wall (see Settings.southWall)
            boolean south = !gabled && hw == 1 && L == 1 && nT == 0 && cfg.southWall > 0 && Bs == h - 1
                    && (cfg.uniformWallTilesets.contains(m.tileset0) || cfg.uniformWallTilesets.contains(m.tileset1));
            if (south) {
                sc = cfg.southWall;
                for (C c : cells) if (c.id == 8) { c.ti = info(1); } // door jamb (half black): plain brick, so the wall ends cleanly at the doorway
            }
            // -- front face composite (face + gable levels); snow pixels of gable levels are keyed out (they belong to the roof)
            int FW = nx * 16, FH = Hf * 16;
            int[] fpx = new int[FW * FH];
            for (C c : cells) {
                if (c.level >= Hf) continue;
                int ox = (c.x - x0) * 16, oy = (Hf - 1 - c.level) * 16;
                TileInfo src = c.ti;
                int st = c.t;
                if (c.level < hw && c.top) { // a cap / snow tile inside the wall (the end of a partition's cap runs into it): wall art of a neighbour
                    int xi = c.x - x0;
                    C nb = xi > 0 && g[c.level][xi - 1] != null && g[c.level][xi - 1].ti.kind == TileInfo.FACE ? g[c.level][xi - 1]
                            : xi + 1 < nx && g[c.level][xi + 1] != null && g[c.level][xi + 1].ti.kind == TileInfo.FACE ? g[c.level][xi + 1] : null;
                    if (nb != null) { src = nb.ti; st = nb.t; }
                }
                src.copyTo(fpx, FW, ox, oy, st);
                if (c.level >= hw)
                    for (int y = 0; y < 16; y++)
                        for (int x = 0; x < 16; x++) {
                            int pp = fpx[(oy + y) * FW + ox + x];
                            if (TileInfo.opaque(pp) && !TileInfo.isWarm(pp)) fpx[(oy + y) * FW + ox + x] = 0;
                        }
            }
            // holes in the wall that are occupied by another structure's cell (e.g. the cap of a partition running into this wall) take the art of a neighbour
            for (int l = 0; l < Hf; l++)
                for (int xi = 0; xi < nx; xi++) {
                    if (g[l][xi] != null) continue;
                    int gx = x0 + xi, gy = Bs - l;
                    if (gy < 0 || gy >= h || at[gy * w + gx] == null) continue;
                    C src = xi > 0 && g[l][xi - 1] != null ? g[l][xi - 1] : xi + 1 < nx ? g[l][xi + 1] : null;
                    if (src == null || src.ti.kind != TileInfo.FACE) continue;
                    src.ti.copyTo(fpx, FW, xi * 16, (Hf - 1 - l) * 16, src.t);
                }
            // castle crypt interiors: a doorway gap in a wall (no face tiles in its column, wall on both sides, the cap beam above): the door prop (2 tiles = 32 px of art) is lower than the
            // uniformWall high face, so the top of the gap becomes a brick lintel (neighbour art, flush with the face) instead of a dark band above the door / a hole over an open doorway
            if (cfg.doorLintel > 0 && !south && !gabled && hw >= 2 && cfg.uniformWall > 0 && (cfg.uniformWallTilesets.contains(m.tileset0) || cfg.uniformWallTilesets.contains(m.tileset1)))
                for (int xi = 1; xi + 1 < nx; xi++) {
                    boolean gap = true;
                    for (int l = 0; l < hw && gap; l++) {
                        int gy = Bs - l;
                        if (g[l][xi] != null || gy < 0 || gy >= h || at[gy * w + x0 + xi] != null) gap = false;
                    }
                    C nb = g[hw - 1][xi - 1] != null && g[hw - 1][xi - 1].ti.kind == TileInfo.FACE ? g[hw - 1][xi - 1] : g[hw - 1][xi + 1];
                    if (!gap || nb == null || nb.ti.kind != TileInfo.FACE || g[hw - 1][xi - 1] == null || g[hw - 1][xi + 1] == null) continue;
                    int[] tile = new int[256];
                    nb.ti.copyTo(tile, 16, 0, 0, nb.t);
                    int ox = xi * 16, oy = (Hf - 1 - (hw - 1)) * 16;
                    int rows = Math.round(16 * (hw * sc - 2f) / sc) + 1; // art rows above a 2.0 high door
                    for (int y = 0; y < Math.min(16, rows); y++)
                        for (int x = 0; x < 16; x++) fpx[(oy + y) * FW + ox + x] = tile[y * 16 + x];
                }
            if (south) { // rows below the art row: the same wall columns continued (window arch, plinth), taken from the tiles that stand under them in the north wall
                int R = Math.max(1, Math.min(3, cfg.southWallRows));
                int[] cp = new int[FW * 48];
                for (int xi = 0; xi < nx; xi++) {
                    C c = g[0][xi];
                    if (c == null) continue;
                    int id = c.id;
                    int[] col = id == 14 ? new int[] {14, 15, 16} : id == 4 ? new int[] {4, 5, 3} : id == 6 ? new int[] {6, 7, 3} : new int[] {1, 2, 3};
                    if (R < 3 && id == 14) col = new int[] {1, 2, 3}; // low wall: no half windows, plain brick
                    int tt = id == 14 ? c.t : 0;
                    for (int r = 0; r < 3; r++) {
                        TileInfo ti = r == 0 && R == 3 ? c.ti : info(col[r]);
                        ti.copyTo(cp, FW, xi * 16, r * 16, r == 0 && R == 3 ? c.t : tt);
                    }
                }
                fpx = R == 3 ? cp : java.util.Arrays.copyOfRange(cp, FW * (3 - R) * 16, FW * 48);
                FH = R * 16;
            }
            Atlas.Rect frontRect = atlas.add(fpx, FW, FH);
            float[] xl = new float[nx], xr = new float[nx]; // x extent of the volume of each column (thin partitions are narrower than a tile)
            float[] fl = new float[nx], fr = new float[nx]; // x extent of its front face (full tile where a wall continues next to it)
            for (int xi = 0; xi < nx; xi++) { xl[xi] = fl[xi] = x0 + xi; xr[xi] = fr[xi] = x0 + xi + 1; }
            float Hh = Hf * sc;
            float[] q;
            // -- depth / roof. dc[xi] = depth (towards the north) of the box over column xi
            float D = 1f;
            float[] dc = new float[nx];
            float apex = 0, ridgeH = 0, xm = x0 + nx / 2f;
            RoofJob job = null;
            if (gabled) {
                int firstRow = FH;
                outer:
                for (int y = 0; y < FH; y++)
                    for (int x = 0; x < FW; x++)
                        if (TileInfo.opaque(fpx[y * FW + x])) { firstRow = y; break outer; }
                apex = (FH - firstRow) / 16f;
                float topY = apex;
                for (C c : cells) {
                    if (c.level < Hf) continue;
                    int fo = 16;
                    for (int y = 0; y < 16 && fo == 16; y++)
                        for (int x = 0; x < 16; x++)
                            if (TileInfo.opaque(c.ti.px[y * 16 + x])) { fo = y; break; }
                    if (fo < 16) topY = Math.max(topY, c.level + (16 - fo) / 16f);
                }
                D = Math.max(1f, (topY - apex) * cfg.roofDepth);
                java.util.Arrays.fill(dc, D);
                float Hg = Math.max(0.6f, apex - hw) + 0.1f;
                ridgeH = hw + Hg;
                job = new RoofJob(cells, x0, nx, zf, hw, ridgeH, xm, D);
                jobs.add(job);
            } else {
                for (int xi = 0; xi < nx; xi++) {
                    int mx = -1;
                    for (int l = hw; l < L; l++) if (g[l][xi] != null) mx = l;
                    dc[xi] = mx >= hw ? mx - hw + 1 : indoor && cfg.indoorWallDepth > 0 ? cfg.indoorWallDepth : cfg.wallDepth;
                    D = Math.max(D, dc[xi]);
                }
                if (cfg.thinCaps && nT > 0) thinCaps(g, nx, x0, hw, L, dc, xl, xr, fl, fr);
                int TW = nx * 16, TH = Math.max(1, nT) * 16;
                int[] tpx = new int[TW * TH];
                Atlas.Rect topRect;
                if (nT > 0) {
                    for (C c : cells)
                        if (c.level >= hw) c.ti.copyTo(tpx, TW, (c.x - x0) * 16, (L - 1 - c.level) * 16, c.t);
                    // transparent corners of the top art (rounded snow edges) would show the inside of the box: fill them with the average top colour
                    {
                        long r = 0, gg = 0, bb = 0, n = 0;
                        for (int pp : tpx) if (TileInfo.opaque(pp)) { r += pp >> 16 & 255; gg += pp >> 8 & 255; bb += pp & 255; n++; }
                        if (n > 0) {
                            int fill = 0xFF000000 | (int) (r / n) << 16 | (int) (gg / n) << 8 | (int) (bb / n);
                            for (int xi = 0; xi < nx; xi++) {
                                int mx = -1;
                                for (int l = hw; l < L; l++) if (g[l][xi] != null) mx = l;
                                for (int l = hw; l <= mx; l++) {
                                    int oy = (L - 1 - l) * 16;
                                    for (int y = 0; y < 16; y++)
                                        for (int x = 0; x < 16; x++) {
                                            int idx = (oy + y) * TW + xi * 16 + x;
                                            if (!TileInfo.opaque(tpx[idx])) tpx[idx] = fill;
                                        }
                                }
                            }
                        }
                    }
                    // dark caps (wall tops drawn black in the art) get the colour of the wall: they read as the top of the wall, not as a hole
                    int anyCap = 0;
                    float bestLuma = -1;
                    for (int xi = 0; xi < nx; xi++)
                        for (int l = hw - 1; l >= 0; l--)
                            if (g[l][xi] != null) {
                                int tc = lightTone(g[l][xi].ti);
                                float lu = 0.299f * (tc >> 16 & 255) + 0.587f * (tc >> 8 & 255) + 0.114f * (tc & 255);
                                if (lu > bestLuma) { bestLuma = lu; anyCap = tc; }
                                break;
                            }
                    for (int xi = 0; xi < nx; xi++) {
                        int capCol = 0;
                        int[] grain = null; // the wall tile's own art: the cap carries its grain / joints so that it reads as the top of that wall
                        for (int l = hw - 1; l >= 0 && capCol == 0; l--)
                            if (g[l][xi] != null) {
                                capCol = lightTone(g[l][xi].ti);
                                grain = new int[256];
                                g[l][xi].ti.copyTo(grain, 16, 0, 0, g[l][xi].t);
                            }
                        if (capCol == 0 || 0.299f * (capCol >> 16 & 255) + 0.587f * (capCol >> 8 & 255) + 0.114f * (capCol & 255) < 22) { capCol = anyCap; grain = null; }
                        if (capCol == 0) continue;
                        float lf = grain == null ? 1f : Math.max(1f, Math.min(2.4f, luma(lift(capCol)) / Math.max(24f, luma(capCol)))) * 0.92f;
                        capCol = lift(capCol);
                        for (int l = hw; l < L; l++) {
                            C c = g[l][xi];
                            if (c == null || c.ti.kind != TileInfo.TOP_BLACK) continue;
                            int oy = (L - 1 - l) * 16;
                            for (int y = 0; y < 16; y++)
                                for (int x = 0; x < 16; x++) {
                                    int idx = (oy + y) * TW + xi * 16 + x;
                                    if (TileInfo.opaque(tpx[idx])) tpx[idx] = grain != null && TileInfo.opaque(grain[y * 16 + x]) ? mix(capCol, scale(grain[y * 16 + x], lf), 0.3f) : capCol;
                                }
                        }
                    }
                } else {
                    int topCol = 0xFF303030;
                    for (int l = hw - 1; l >= 0 && topCol == 0xFF303030; l--)
                        for (int xi = 0; xi < nx; xi++)
                            if (g[l][xi] != null && g[l][xi].ti.topColor != 0) { topCol = g[l][xi].ti.topColor; break; }
                    int lt = lift(topCol);
                    java.util.Arrays.fill(tpx, lt);
                    if (indoor) // wall top of an indoor wall: the wall tile's own art (grain / joints), lifted like the flat colour
                        for (int xi = 0; xi < nx; xi++) {
                            C c = g[hw - 1][xi];
                            if (c == null) continue;
                            int[] gr = new int[256];
                            TileInfo cti = south && c.id == 14 ? info(1) : c.ti; // south wall: the cap is brick, not the arch of the window below
                            cti.copyTo(gr, 16, 0, 0, south && c.id == 14 ? 0 : c.t);
                            float lf = Math.max(1f, Math.min(2.4f, luma(lt) / Math.max(24f, luma(lightTone(cti))))) * 0.92f;
                            for (int y = 0; y < 16; y++)
                                for (int x = 0; x < 16; x++)
                                    if (TileInfo.opaque(gr[y * 16 + x])) tpx[y * TW + xi * 16 + x] = scale(gr[y * 16 + x], lf);
                        }
                }
                topRect = atlas.add(tpx, TW, TH);
                float yt = hw * sc;
                float[] ext = new float[nx]; // a thin partition that runs into a wall continues through that wall's thickness (T junction)
                boolean[] plugL = new boolean[nx], plugR = new boolean[nx];
                float[] zF = new float[nx];
                if (cfg.thinCaps && nT > 0)
                    for (int xi = 0; xi < nx; xi++) {
                        boolean thin = xl[xi] > x0 + xi + 1e-3f || xr[xi] < x0 + xi + 1 - 1e-3f;
                        if (!thin || dc[xi] < 1f) continue;
                        float zn = zf - dc[xi];
                        if (Math.abs(zn - Math.round(zn)) > 1e-3f) continue;
                        int y0 = Math.round(zn) - 1;
                        // nearest wall face north of the partition's end (at most 2 empty rows away) on either side
                        int best = -1;
                        for (int side = 0; side < 2; side++) {
                            int nbx = xi + (side == 0 ? -1 : 1), gx = x0 + nbx;
                            if (gx < 0 || gx >= w) continue;
                            for (int yN = y0; yN >= 0 && yN >= y0 - 2; yN--) {
                                C nc = at[yN * w + gx];
                                if (nc == null) continue;
                                if (!nc.top && nc.ti.kind == TileInfo.FACE && (best < 0 || yN > best)) best = yN;
                                break;
                            }
                        }
                        if (best < 0) continue;
                        for (int side = 0; side < 2; side++) {
                            int nbx = xi + (side == 0 ? -1 : 1), gx = x0 + nbx;
                            if (gx < 0 || gx >= w) continue;
                            C nc = at[best * w + gx];
                            if (nc == null || nc.top || nc.ti.kind != TileInfo.FACE) continue;
                            if (side == 0) plugL[xi] = true; else plugR[xi] = true;
                        }
                        zF[xi] = best + 1;
                        if (plugL[xi] || plugR[xi]) ext[xi] = (zn - zF[xi]) + Math.min(cfg.maxThickness, 0.3f);
                    }
                for (int xi = 0; xi < nx; xi++) {
                    boolean any = false;
                    for (int l = 0; l < hw + (nT > 0 ? nT : 0); l++) if (g[l][xi] != null) any = true;
                    if (!any) continue;
                    float zN0 = zf - dc[xi];
                    dc[xi] += ext[xi];
                    float xa = x0 + xi, d = dc[xi] - ext[xi];
                    // texture rows of the column's top: the d rows just above the wall (front-most row at the bottom of the quad)
                    float vBot = nT > 0 ? (L - hw) * 16 : TH, vTop = nT > 0 ? vBot - d * 16 : 0;
                    q = new float[] {xl[xi], yt, zf - d, xr[xi], yt, zf - d, xr[xi], yt, zf, xl[xi], yt, zf};
                    faceUv(q, topRect, (xl[xi] - x0) * 16, vTop, (xr[xi] - x0) * 16, vBot, 0, 1, 0);
                    if (ext[xi] > 0) {
                        q = new float[] {xl[xi], yt, zN0 - ext[xi], xr[xi], yt, zN0 - ext[xi], xr[xi], yt, zN0, xl[xi], yt, zN0};
                        faceUv(q, topRect, (xl[xi] - x0) * 16, vTop, (xr[xi] - x0) * 16, vTop + ext[xi] * 16, 0, 1, 0);
                        float zP = zF[xi], th = ext[xi] - (zN0 - zP);
                        int yN = Math.round(zP) - 1;
                        for (int side = 0; side < 2; side++) {
                            if (side == 0 ? !plugL[xi] : !plugR[xi]) continue;
                            float e = side == 0 ? x0 + xi : x0 + xi + 1, t = side == 0 ? xl[xi] : xr[xi];
                            if (Math.abs(e - t) < 1e-3f) continue;
                            float a = Math.min(e, t), b = Math.max(e, t), gp = (b - a) * 16f;
                            int gx = x0 + xi + (side == 0 ? -1 : 1);
                            // slab top between the partition and the neighbouring wall piece, and the wall's face continued over the gap
                            q = new float[] {a, yt, zP - th, b, yt, zP - th, b, yt, zP, a, yt, zP};
                            faceUv(q, topRect, (a - x0) * 16, vTop, (b - x0) * 16, vTop + th * 16, 0, 1, 0);
                            for (int l = 0; ; l++) {
                                C cc = yN - l >= 0 ? at[(yN - l) * w + gx] : null;
                                if (cc == null || cc.top || cc.ti.kind != TileInfo.FACE) break;
                                Atlas.Rect r = tileRect(cc.ti, cc.t);
                                q = new float[] {a, (l + 1) * sc, zP, b, (l + 1) * sc, zP, b, l * sc, zP, a, l * sc, zP};
                                if (side == 0) faceUv(q, r, 16 - gp, 0, 16, 16, 0, 0, 1);
                                else faceUv(q, r, 0, 0, gp, 16, 0, 0, 1);
                            }
                        }
                    }
                    // thin partition next to a wall that continues: close the gap between the partition and the neighbour's slab
                    for (int side = 0; side < 2; side++) {
                        int nb = xi + (side == 0 ? -1 : 1);
                        if (nb < 0 || nb >= nx || g[0][nb] == null) continue;
                        float e = side == 0 ? x0 + xi : x0 + xi + 1, t = side == 0 ? xl[xi] : xr[xi];
                        if (Math.abs(e - t) < 1e-3f) continue;
                        float a = Math.min(e, t), b = Math.max(e, t), dN = Math.min(d, dc[nb]);
                        q = new float[] {a, yt, zf - dN, b, yt, zf - dN, b, yt, zf, a, yt, zf};
                        faceUv(q, topRect, (a - x0) * 16, vBot - dN * 16, (b - x0) * 16, vBot, 0, 1, 0);
                        Atlas.Rect gr = colorRect(g[0][xi] != null ? scale(avgColor(g[0][xi].ti), 0.75f) : 0xFF303030);
                        q = new float[] {b, yt, zf - dN, a, yt, zf - dN, a, 0, zf - dN, b, 0, zf - dN};
                        faceUv(q, gr, 0, 0, 2, 2, 0, 0, -1);
                    }
                }
            }
            if (!gabled && nT > 0 && sc == 1f && cfg.mergeRoofs) {
                boolean uniform = true;
                int snow = 0, tops = 0;
                for (int xi = 0; xi < nx; xi++) if (g[0][xi] == null || Math.abs(dc[xi] - D) > 1e-3f || xl[xi] > x0 + xi + 1e-3f || xr[xi] < x0 + xi + 1 - 1e-3f) uniform = false;
                for (C c : cells) if (c.level >= hw) { tops++; if (c.ti.kind == TileInfo.TOP_SNOW) snow++; }
                if (uniform && tops > 0 && snow * 10 >= tops * 7 && D >= 1f) backs.add(new Back(x0, nx, zf, hw, D));
            }
            // front slices
            for (int xi = 0; xi < nx; xi++) {
                boolean any = false;
                for (int l = 0; l < Hf; l++) if (g[l][xi] != null) any = true;
                if (!any) continue;
                float xa = x0 + xi;
                q = new float[] {fl[xi], Hh, zf, fr[xi], Hh, zf, fr[xi], 0, zf, fl[xi], 0, zf};
                faceUv(q, frontRect, (fl[xi] - x0) * 16, 0, (fr[xi] - x0) * 16, FH, 0, 0, 1);
            }
            // -- side faces: the tile's own art repeated along the depth (stone reads as stone, planks as planks); only the part not touching the neighbouring column
            for (int l = 0; l < hw; l++) {
                for (int xi = 0; xi < nx; xi++) {
                    C c = g[l][xi];
                    if (c == null) continue;
                    float y0 = l * sc, y1 = (l + 1) * sc;
                    for (int side = 0; side < 2; side++) {
                        int nb = xi + (side == 0 ? -1 : 1);
                        float covered = nb >= 0 && nb < nx && g[l][nb] != null ? dc[nb] : 0f;
                        if (covered >= dc[xi] - 1e-3f) continue;
                        float zA = zf - dc[xi], zB = zf - covered; // north end .. south end of the exposed part
                        float xa = side == 0 ? xl[xi] : xr[xi];
                        float uEnd = (dc[xi] - covered) * 16f;
                        if (south) { // end cap: the wall's own front art continued round the corner (plain brick up to the top)
                            float u0 = side == 0 ? xi * 16 : (xi + 1) * 16;
                            q = side == 0 ? new float[] {xa, y1, zA, xa, y1, zB, xa, y0, zB, xa, y0, zA} : new float[] {xa, y1, zB, xa, y1, zA, xa, y0, zA, xa, y0, zB};
                            faceUv(q, frontRect, u0, 0, side == 0 ? u0 + uEnd : u0 - uEnd, FH, side == 0 ? -1 : 1, 0, 0);
                            continue;
                        }
                        Atlas.Rect sr = sideRect(c, Math.max(1, Math.round(dc[xi] * 16)));
                        q = side == 0 ? new float[] {xa, y1, zA, xa, y1, zB, xa, y0, zB, xa, y0, zA} : new float[] {xa, y1, zB, xa, y1, zA, xa, y0, zA, xa, y0, zB};
                        if (side == 0) faceUv(q, sr, 0, 0, uEnd, 16, -1, 0, 0);
                        else faceUv(q, sr, 0, 0, uEnd, 16, 1, 0, 0); // east: u runs south -> north
                    }
                }
            }
            // -- back face (hidden from the default camera, closes the box for low pitch views): wall colour, darker
            {
                long r = 0, gg = 0, bb = 0, n = 0;
                for (int y = FH - hw * 16; y < FH; y++)
                    for (int x = 0; x < FW; x++) {
                        int pp = fpx[y * FW + x];
                        if (TileInfo.opaque(pp)) { r += pp >> 16 & 255; gg += pp >> 8 & 255; bb += pp & 255; n++; }
                    }
                int avg = n == 0 ? 0xFF404040 : 0xFF000000 | (int) (r / n) << 16 | (int) (gg / n) << 8 | (int) (bb / n);
                Atlas.Rect br = colorRect(scale(avg, 0.75f));
                for (int xi = 0; xi < nx; xi++) {
                    if (g[0][xi] == null) continue;
                    float zn = zf - dc[xi];
                    q = new float[] {xr[xi], hw * sc, zn, xl[xi], hw * sc, zn, xl[xi], 0, zn, xr[xi], 0, zn};
                    faceUv(q, br, 0, 0, 2, 2, 0, 0, -1);
                }
                if (gabled) job.br = br; // gable triangle at the back: emitted in finishRoofs (a merged roof draws its own back wall)
            }
            // -- bookkeeping: covered cells and pick quads
            res.structures++;
            for (C c : cells) {
                res.covered[c.idx] = true;
                Pick pk = new Pick();
                pk.x = c.x; pk.y = c.y; pk.tile = c.id; pk.transform = c.t; pk.level = c.level;
                float xa = c.x;
                if (c.level < Hf) {
                    float y0 = c.level * sc, y1 = (c.level + 1) * sc;
                    float[] pp = {xa, y1, zf, xa + 1, y1, zf, xa + 1, y0, zf, xa, y0, zf};
                    System.arraycopy(pp, 0, pk.p, 0, 12);
                } else {
                    int j = c.level - Hf; // 0 = front-most top row
                    float rowD = gabled ? D / Math.max(1, L - Hf) : 1f;
                    float zS = zf - j * rowD, zN = zf - (j + 1) * rowD;
                    float yl, yr;
                    if (gabled) { yl = roofH(xa, hw, ridgeH, xm, x0, x0 + nx); yr = roofH(xa + 1, hw, ridgeH, xm, x0, x0 + nx); }
                    else yl = yr = hw * sc;
                    float[] pp = {xa, yl, zN, xa + 1, yr, zN, xa + 1, yr, zS, xa, yl, zS};
                    System.arraycopy(pp, 0, pk.p, 0, 12);
                }
                res.picks.add(pk);
            }
            if (debug)
                log.append(String.format("structure map%d ts%d,%d x%d..%d Bs=%d levels=%d hw=%d gable=%d top=%d depth=%.2f sc=%.2f cells=%d%n", m.mapId, m.tileset0, m.tileset1, x0, x1, Bs, L, hw, ng, nT, D, sc, cells.size()));
        }

        /**
         * Thin partitions: a wall whose cap is drawn as a few black pixels (2D: a thin line) must not become a full tile wide slab.
         * The thickness is measured from the cap tiles' opaque pixels: columns with a cap run longer than one row (a partition running
         * north) get the opaque x extent, columns with a single cap row (a partition running east-west) the depth of the opaque part
         * of that row. Only caps that are clearly thinner than a tile are touched, big black tops keep their full volume. Sides that
         * touch a neighbouring column stay at the column edge so that junctions are closed.
         */
        void thinCaps(C[][] g, int nx, int x0, int hw, int L, float[] dc, float[] xl, float[] xr, float[] fl, float[] fr) {
            for (int xi = 0; xi < nx; xi++) {
                int mx = -1;
                for (int l = hw; l < L; l++) if (g[l][xi] != null) mx = l;
                if (mx < hw) continue;
                int minX = 16, maxX = -1, minY = 16, rows = mx - hw + 1;
                boolean black = true;
                for (int l = hw; l <= mx; l++) {
                    C c = g[l][xi];
                    if (c == null) continue;
                    if (c.ti.kind != TileInfo.TOP_BLACK) { black = false; break; }
                    int[] t = new int[c.ti.tw * c.ti.th];
                    c.ti.copyTo(t, c.ti.tw, 0, 0, c.t);
                    for (int y = 0; y < c.ti.th; y++)
                        for (int x = 0; x < c.ti.tw; x++)
                            if (TileInfo.opaque(t[y * c.ti.tw + x])) {
                                if (l > hw || rows == 1) { minX = Math.min(minX, x); maxX = Math.max(maxX, x); }
                                if (l == hw) minY = Math.min(minY, y);
                            }
                }
                if (debug) log.append(String.format("  thin? col x=%d rows=%d black=%b minX=%d maxX=%d minY=%d%n", x0 + xi, rows, black, minX, maxX, minY));
                if (!black) continue;
                float min = cfg.minThickness;
                if (rows == 1) {
                    if (minY >= 16) continue;
                    float t = (16 - minY) / 16f;
                    if (t < 0.75f) dc[xi] = Math.max(min, Math.min(t, cfg.maxThickness));
                } else if (maxX >= minX && (maxX + 1 - minX) / 16f < 0.75f) {
                    float t = Math.max(min, Math.min(cfg.maxThickness, (maxX + 1 - minX) / 16f)), mid = (minX + maxX + 1) / 32f;
                    float a = Math.max(0f, mid - t / 2), b = Math.min(1f, mid + t / 2);
                    xl[xi] = x0 + xi + a;
                    xr[xi] = x0 + xi + b;
                    fl[xi] = xi > 0 && g[0][xi - 1] != null ? x0 + xi : xl[xi];
                    fr[xi] = xi + 1 < nx && g[0][xi + 1] != null ? x0 + xi + 1 : xr[xi];
                }
            }
        }

        final Map<Long, Atlas.Rect> sideRects = new HashMap<>();

        /**
         * Side texture of a wall cell: the tile art (mirrored so the south end continues the front face's edge) repeated along the
         * depth, W pixels wide. Holes (transparent pixels) take the tile's average colour.
         */
        Atlas.Rect sideRect(C c, int W) {
            long key = ((long) c.ti.id << 40) | ((long) (c.t & 7) << 32) | W;
            Atlas.Rect r = sideRects.get(key);
            if (r != null) return r;
            int tw = c.ti.tw, th = c.ti.th;
            int[] t = new int[tw * th];
            c.ti.copyTo(t, tw, 0, 0, c.t);
            int avg = avgColor(c.ti);
            int[] px = new int[W * th];
            for (int y = 0; y < th; y++)
                for (int x = 0; x < W; x++) {
                    int p = t[y * tw + (tw - 1 - (x % tw))];
                    px[y * W + x] = TileInfo.opaque(p) ? p | 0xFF000000 : avg;
                }
            r = atlas.add(px, W, th);
            sideRects.put(key, r);
            return r;
        }

        // ---------------------------------------------------------------------------------------- connected roofs (L / T / U compounds)

        final class RoofJob {
            final List<C> cells;
            final int x0, nx;
            final float zf, hw, ridgeH, xm, D;
            Atlas.Rect br;
            boolean merged;
            RoofJob(List<C> cells, int x0, int nx, float zf, float hw, float ridgeH, float xm, float D) {
                this.cells = cells; this.x0 = x0; this.nx = nx; this.zf = zf; this.hw = hw; this.ridgeH = ridgeH; this.xm = xm; this.D = D;
            }
            float zn() { return zf - D; }
        }

        /** A flat snow topped building (front wall + top rows) that may be the back section of a roof compound. */
        final class Back {
            final int x0, nx;
            final float zf, hw, D;
            Back(int x0, int nx, float zf, float hw, float D) { this.x0 = x0; this.nx = nx; this.zf = zf; this.hw = hw; this.D = D; }
        }

        final List<RoofJob> jobs = new ArrayList<>();
        final List<Back> backs = new ArrayList<>();

        /** Plain gable roofs as before; gable wings that touch the end of a flat back building (same eave height, roof ends at its front wall) share one connected roof. */
        void finishRoofs() {
            for (Back b : backs) {
                RoofJob l = null, r = null;
                for (RoofJob j : jobs) {
                    if (j.merged || j.zn() < b.zf - 0.01f || j.zn() > b.zf + 1.01f || Math.abs(j.hw - b.hw) > 0.01f) continue; // a wing's roof may end up to one tile south of the back building's front wall (the compound closes the gap)
                    if (j.x0 + j.nx == b.x0 && l == null) l = j;
                    else if (j.x0 == b.x0 + b.nx && r == null) r = j;
                }
                if (l == null && r == null) continue;
                if (l != null) l.merged = true;
                if (r != null) r.merged = true;
                int before = res.faces.size();
                try {
                    compound(l, r, b);
                } catch (RuntimeException e) {
                    System.err.println("[render3d] roof compound failed: " + e);
                    while (res.faces.size() > before) res.faces.remove(res.faces.size() - 1);
                    if (l != null) l.merged = false;
                    if (r != null) r.merged = false;
                }
            }
            for (RoofJob j : jobs) {
                if (j.merged) continue;
                roof(j.cells, j.x0, j.nx, j.zf, j.hw, j.ridgeH, j.xm, j.D);
                if (j.br != null) {
                    float zn = j.zn();
                    faceUv(new float[] {j.xm, j.ridgeH, zn, j.xm, j.ridgeH, zn, j.x0 + j.nx, j.hw, zn, j.x0, j.hw, zn}, j.br, 0, 0, 2, 2, 0, 0, -1);
                }
            }
        }

        /** One roof plane of a prism: height = a x + b z + c over the convex plan polygon, texture u along the slope from the eave, v along the eave. */
        final class RPlane {
            float a, b, c;
            float[][] poly;
            float ox, oz, ax, az, bx, bz, su, sv; // texture frame
            int tw, th;
            float h(float x, float z) { return a * x + b * z + c; }
        }

        final class Prism {
            final float X0, X1, Z0, Z1;
            final RPlane p1, p2;
            Prism(float X0, float X1, float Z0, float Z1, RPlane p1, RPlane p2) { this.X0 = X0; this.X1 = X1; this.Z0 = Z0; this.Z1 = Z1; this.p1 = p1; this.p2 = p2; }
        }

        /** Plan rect [x0,x1]x[z0,z1], height y0 at the eave edge rising to y1 at the opposite edge (eave = low coordinate side of the across axis when fromLow). */
        RPlane plane(float x0, float x1, float z0, float z1, float y0, float y1, boolean alongX, boolean fromLow, float slant) {
            RPlane p = new RPlane();
            p.poly = new float[][] {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}};
            if (!alongX) { // ridge runs north-south, the slope rises across x
                float k = (y1 - y0) / (x1 - x0) * (fromLow ? 1 : -1);
                p.a = k;
                p.c = fromLow ? y0 - k * x0 : y0 - k * x1;
                p.ox = fromLow ? x0 : x1; p.oz = z1; p.ax = fromLow ? 1 : -1; p.az = 0; p.bx = 0; p.bz = -1;
                p.su = slant * 16f / Math.max(1e-4f, x1 - x0);
            } else { // ridge runs east-west, the slope rises across z
                float k = (y1 - y0) / (z1 - z0) * (fromLow ? 1 : -1);
                p.b = k;
                p.c = fromLow ? y0 - k * z0 : y0 - k * z1;
                p.oz = fromLow ? z0 : z1; p.ox = x0; p.az = fromLow ? 1 : -1; p.ax = 0; p.bx = 1; p.bz = 0;
                p.su = slant * 16f / Math.max(1e-4f, z1 - z0);
            }
            p.sv = 16f;
            return p;
        }

        static float area(float[][] pl) {
            float a = 0;
            for (int i = 0; i < pl.length; i++) { float[] p = pl[i], q = pl[(i + 1) % pl.length]; a += p[0] * q[1] - q[0] * p[1]; }
            return Math.abs(a) / 2f;
        }

        /** Keeps the part of a convex polygon where g = ga x + gb z + gc >= 0. */
        static float[][] clip(float[][] pl, float ga, float gb, float gc) {
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < pl.length; i++) {
                float[] p = pl[i], q = pl[(i + 1) % pl.length];
                float dp = ga * p[0] + gb * p[1] + gc, dq = ga * q[0] + gb * q[1] + gc;
                if (dp >= 0) out.add(p);
                if ((dp >= 0) != (dq >= 0)) {
                    float t = dp / (dp - dq);
                    out.add(new float[] {p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t});
                }
            }
            return out.toArray(new float[0][]);
        }

        /** Every piece minus the convex region where all g >= 0; the pieces stay convex. */
        void subtract(List<float[][]> pieces, float[][] gs) {
            List<float[][]> res2 = new ArrayList<>();
            for (float[][] pl : pieces) {
                float[][] cur = pl;
                for (float[] g : gs) {
                    if (cur.length < 3) break;
                    float[][] outside = clip(cur, -g[0], -g[1], -g[2]);
                    if (outside.length >= 3 && area(outside) > 1e-4f) res2.add(outside);
                    cur = clip(cur, g[0], g[1], g[2]);
                }
            }
            pieces.clear();
            pieces.addAll(res2);
        }

        /**
         * One connected roof for gable wings l (west) / r (east) and the flat back building b between / beside them: the wings run on to the
         * back edge of b, b gets an east-west ridge at the wings' ridge height (from wing ridge to wing ridge, or to its free end, which gets a
         * gable end). The surface is the union (max height) of the gable prisms, so valleys and hips fall out of clipping every roof plane
         * against the regions where another prism is higher. Eaves, fascia, the extension walls and the back wall follow.
         */
        void compound(RoofJob l, RoofJob r, Back b) {
            RoofJob ref = l != null ? l : r;
            float o = 0.06f, oz = 0.05f, ft = 0.13f;
            float ridgeH = Math.max(l != null ? l.ridgeH : 0f, r != null ? r.ridgeH : 0f);
            float hw = ref.hw;
            float zm = b.zf, zb = zm - b.D;
            float zN = zb - o;
            TileInfo snowTile = null;
            Map<Integer, Integer> count = new HashMap<>();
            for (RoofJob j : new RoofJob[] {l, r}) {
                if (j == null) continue;
                for (C c : j.cells) if (c.ti.kind == TileInfo.TOP_SNOW && c.ti.cov >= 0.97f) count.merge(c.id, 1, Integer::sum);
            }
            int best = -1, bc = 0;
            for (Map.Entry<Integer, Integer> e : count.entrySet()) if (e.getValue() > bc) { bc = e.getValue(); best = e.getKey(); }
            if (best >= 0) snowTile = info(best);
            int[] rimL = l != null ? rimProfile(l.cells, true, l.x0) : null;
            int[] rimR = r != null ? rimProfile(r.cells, false, r.x0 + r.nx - 1) : null;
            int[] rim = rimL != null ? rimL : rimR;
            List<Prism> prisms = new ArrayList<>();
            // back prism (ridge east-west)
            float halfB = b.D / 2f, riseB = ridgeH - hw, oyB = o * riseB / halfB;
            float eaveB = hw - oyB;
            float zr = (zb + zm) / 2f, zSb = zm + o;
            float bx0 = l != null ? l.xm : b.x0 - o, bx1 = r != null ? r.xm : b.x0 + b.nx + o;
            float slantB = (float) Math.sqrt((halfB + o) * (halfB + o) + (ridgeH - eaveB) * (ridgeH - eaveB));
            RPlane bn = plane(bx0, bx1, zN, zr, eaveB, ridgeH, true, true, slantB), bs = plane(bx0, bx1, zr, zSb, eaveB, ridgeH, true, false, slantB);
            bn.tw = bs.tw = Math.max(16, (int) Math.ceil(slantB * 16));
            bn.th = bs.th = Math.max(16, (int) Math.ceil((bx1 - bx0) * 16));
            prisms.add(new Prism(bx0, bx1, zN, zSb, bn, bs));
            // wing prisms (ridge north-south), extended to the back edge
            for (RoofJob w : new RoofJob[] {l, r}) {
                if (w == null) continue;
                float half = w.nx / 2f, rise = w.ridgeH - w.hw, oy = o * rise / half, yE = w.hw - oy;
                float xw = w.x0 - o, xe = w.x0 + w.nx + o, zS = w.zf + oz;
                float slant = (float) Math.sqrt((half + o) * (half + o) + (ridgeH - yE) * (ridgeH - yE));
                RPlane pw = plane(xw, w.xm, zN, zS, yE, ridgeH, false, true, slant), pe = plane(w.xm, xe, zN, zS, yE, ridgeH, false, false, slant);
                pw.tw = pe.tw = Math.max(16, (int) Math.ceil(slant * 16));
                pw.th = pe.th = Math.max(16, (int) Math.ceil((zS - zN) * 16));
                prisms.add(new Prism(xw, xe, zN, zS, pw, pe));
            }
            Map<RPlane, Atlas.Rect> tex = new HashMap<>();
            for (Prism pr : prisms)
                for (RPlane p : new RPlane[] {pr.p1, pr.p2}) {
                    int[] px = new int[p.tw * p.th];
                    for (int y = 0; y < p.th; y++) for (int x = 0; x < p.tw; x++) px[y * p.tw + x] = snowTile != null ? snowTile.px[(y & 15) * 16 + (x & 15)] : 0xFFE0E4EC;
                    if (rim != null) for (int y = 0; y < p.th; y++) for (int x = 0; x < 16 && x < p.tw; x++) px[y * p.tw + x] = rim[x];
                    tex.put(p, atlas.add(px, p.tw, p.th));
                }
            // visible part of every plane = its polygon minus the regions where another prism is higher
            for (Prism pi : prisms)
                for (RPlane p : new RPlane[] {pi.p1, pi.p2}) {
                    List<float[][]> pieces = new ArrayList<>();
                    pieces.add(p.poly);
                    for (Prism pj : prisms) {
                        if (pj == pi) continue;
                        float e = 1e-3f;
                        float[][] gs = {
                            {1, 0, -pj.X0}, {-1, 0, pj.X1}, {0, 1, -pj.Z0}, {0, -1, pj.Z1},
                            {pj.p1.a - p.a, pj.p1.b - p.b, pj.p1.c - p.c - e}, {pj.p2.a - p.a, pj.p2.b - p.b, pj.p2.c - p.c - e}};
                        subtract(pieces, gs);
                    }
                    Atlas.Rect rr = tex.get(p);
                    float nxn = -p.a, nzn = -p.b, nl = (float) Math.sqrt(nxn * nxn + 1 + nzn * nzn);
                    for (float[][] pl : pieces) {
                        for (int i = 1; i + 1 < pl.length; i++) {
                            float[][] tri = {pl[0], pl[i], pl[i + 1]};
                            float[] q = new float[12], lp = new float[8];
                            for (int k = 0; k < 4; k++) {
                                float[] v = tri[Math.min(k, 2)];
                                q[k * 3] = v[0]; q[k * 3 + 1] = p.h(v[0], v[1]); q[k * 3 + 2] = v[1];
                                float dx = v[0] - p.ox, dz = v[1] - p.oz;
                                lp[k * 2] = Math.max(0f, Math.min(p.tw, (dx * p.ax + dz * p.az) * p.su));
                                lp[k * 2 + 1] = Math.max(0f, Math.min(p.th, Math.abs(dx * p.bx + dz * p.bz) * p.sv));
                            }
                            face(q, rr, lp, nxn / nl, 1f / nl, nzn / nl);
                        }
                    }
                }
            // fascia (the roof's thickness at the eaves, colour of the art's rim)
            Atlas.Rect fr = colorRect(rim != null ? scale(rim[0], 0.82f) : 0xFFA8B0C0);
            float[] qf;
            for (RoofJob w : new RoofJob[] {l, r}) {
                if (w == null) continue;
                float half = w.nx / 2f, rise = w.ridgeH - w.hw, oy = o * rise / half, yE = w.hw - oy;
                float xw = w.x0 - o, xe = w.x0 + w.nx + o, zS = w.zf + oz;
                boolean left = w == l;
                float xOuter = left ? xw : xe, xInner = left ? xe : xw;
                qf = left ? new float[] {xOuter, yE, zN, xOuter, yE, zS, xOuter, yE - ft, zS, xOuter, yE - ft, zN}
                          : new float[] {xOuter, yE, zS, xOuter, yE, zN, xOuter, yE - ft, zN, xOuter, yE - ft, zS};
                faceUv(qf, fr, 0, 0, 2, 2, left ? -1 : 1, 0, 0);
                qf = left ? new float[] {xInner, yE, zS, xInner, yE, zSb, xInner, yE - ft, zSb, xInner, yE - ft, zS}
                          : new float[] {xInner, yE, zSb, xInner, yE, zS, xInner, yE - ft, zS, xInner, yE - ft, zSb};
                faceUv(qf, fr, 0, 0, 2, 2, left ? 1 : -1, 0, 0);
                for (int side = 0; side < 2; side++) {
                    float xs = side == 0 ? xw : xe;
                    float[] qg = {xs, yE, zS, w.xm, ridgeH, zS, w.xm, ridgeH - ft, zS, xs, yE - ft, zS};
                    faceUv(qg, fr, 0, 0, 2, 2, 0, 0, 1);
                }
            }
            {
                float xa = l != null ? l.x0 + l.nx + o : b.x0 - o, xb = r != null ? r.x0 - o : b.x0 + b.nx + o;
                if (xb > xa) {
                    qf = new float[] {xa, eaveB, zSb, xb, eaveB, zSb, xb, eaveB - ft, zSb, xa, eaveB - ft, zSb};
                    faceUv(qf, fr, 0, 0, 2, 2, 0, 0, 1);
                }
            }
            // walls under the extension of the wings (z zb..zm): outer side faces, back wall with the gable triangles
            Atlas.Rect brc = null;
            for (RoofJob w : new RoofJob[] {l, r}) {
                if (w == null) continue;
                if (brc == null) brc = w.br;
                boolean left = w == l;
                int cx = left ? w.x0 : w.x0 + w.nx - 1;
                float xa = left ? w.x0 : w.x0 + w.nx;
                for (C c : w.cells) {
                    if (c.x != cx || c.level >= w.hw) continue;
                    float y0 = c.level, y1 = c.level + 1;
                    Atlas.Rect sr = sideRect(c, Math.max(1, Math.round(b.D * 16)));
                    float uEnd = b.D * 16f;
                    float zw = w.zn(); // the wing's own north end: up to a tile south of the back building's front wall
                    sr = sideRect(c, Math.max(1, Math.round((zw - zb) * 16)));
                    uEnd = (zw - zb) * 16f;
                    float[] q = left ? new float[] {xa, y1, zb, xa, y1, zw, xa, y0, zw, xa, y0, zb} : new float[] {xa, y1, zw, xa, y1, zb, xa, y0, zb, xa, y0, zw};
                    faceUv(q, sr, 0, 0, uEnd, 16, left ? -1 : 1, 0, 0);
                    if (zw > zm + 1e-3f) { // gap between the wing and the back building: the inner side of the wing is open there
                        float xi2 = left ? w.x0 + w.nx : w.x0;
                        float[] qi = left ? new float[] {xi2, y1, zw, xi2, y1, zm, xi2, y0, zm, xi2, y0, zw} : new float[] {xi2, y1, zm, xi2, y1, zw, xi2, y0, zw, xi2, y0, zm};
                        faceUv(qi, sr, 0, 0, uEnd * (zw - zm) / (zw - zb), 16, left ? 1 : -1, 0, 0);
                    }
                }
                float xe = w.x0 + w.nx;
                faceUv(new float[] {xe, hw, zb, w.x0, hw, zb, w.x0, 0, zb, xe, 0, zb}, w.br, 0, 0, 2, 2, 0, 0, -1);
                faceUv(new float[] {w.xm, ridgeH, zb, w.xm, ridgeH, zb, xe, hw, zb, w.x0, hw, zb}, w.br, 0, 0, 2, 2, 0, 0, -1);
            }
            for (int side = 0; side < 2; side++) { // gable end of the back section at a free end
                if (side == 0 ? l != null : r != null) continue;
                float xg = side == 0 ? b.x0 : b.x0 + b.nx;
                float[] q = {xg, ridgeH, zr, xg, ridgeH, zr, xg, hw, zm, xg, hw, zb};
                faceUv(q, brc, 0, 0, 2, 2, side == 0 ? -1 : 1, 0, 0);
            }
            if (debug) log.append(String.format("roof compound: back x%d..%d zb=%.1f zm=%.1f ridge=%.2f left=%b right=%b%n", b.x0, b.x0 + b.nx - 1, zb, zm, ridgeH, l != null, r != null));
        }

        static float roofH(float x, float hw, float ridge, float xm, int x0, int x1) {
            float half = Math.max(0.5f, (x1 - x0) / 2f);
            float f = 1f - Math.abs(x - xm) / half;
            return hw + (ridge - hw) * Math.max(0f, f);
        }

        /** Two slopes from the eaves (height hw at both side walls) up to the ridge at xm, depth D towards the north. */
        void roof(List<C> cells, int x0, int nx, float zf, float hw, float ridgeH, float xm, float D) {
            // snow texture: tile with the most uniform full snow, tiled
            TileInfo snowTile = null;
            Map<Integer, Integer> count = new HashMap<>();
            for (C c : cells)
                if (c.ti.kind == TileInfo.TOP_SNOW && c.ti.cov >= 0.97f) count.merge(c.id, 1, Integer::sum);
            int best = -1, bc = 0;
            for (Map.Entry<Integer, Integer> e : count.entrySet()) if (e.getValue() > bc) { bc = e.getValue(); best = e.getKey(); }
            if (best >= 0) snowTile = info(best);
            float half = nx / 2f, rise = ridgeH - hw;
            float o = 0.06f, oy = o * rise / half; // eave overhang (horizontal, vertical drop along the slope)
            float slant = (float) Math.sqrt((half + o) * (half + o) + (rise + oy) * (rise + oy));
            int SW = Math.max(16, (int) Math.ceil(slant * 16)), SH = Math.max(16, (int) Math.ceil(D * 16));
            float oz = 0.05f, zn = zf - D, xe = x0 + nx, yE = hw - oy, ft = 0.13f;
            zf += oz; // the roof overhangs the gable front a little
            for (int side = 0; side < 2; side++) {
                boolean left = side == 0;
                int[] rim = rimProfile(cells, left, left ? x0 : x0 + nx - 1);
                int[] px = new int[SW * SH];
                for (int y = 0; y < SH; y++) for (int x = 0; x < SW; x++) px[y * SW + x] = snowTile != null ? snowTile.px[(y & 15) * 16 + (x & 15)] : 0xFFE0E4EC;
                if (rim != null)
                    for (int y = 0; y < SH; y++) for (int x = 0; x < 16 && x < SW; x++) px[y * SW + x] = rim[x];
                Atlas.Rect rr = atlas.add(px, SW, SH);
                float[] qs = left ? new float[] {x0 - o, yE, zf, xm, ridgeH, zf, xm, ridgeH, zn, x0 - o, yE, zn}
                                  : new float[] {xe + o, yE, zf, xm, ridgeH, zf, xm, ridgeH, zn, xe + o, yE, zn};
                faceAuto(qs, rr, 0, 0, SW, SH);
                // fascia: the thickness of the roof at the eave, in the colour of the art's rim
                int fc = rim != null ? scale(rim[0], 0.82f) : 0xFFA8B0C0;
                Atlas.Rect fr = colorRect(fc);
                float[] qf;
                if (left) {
                    float xa = x0 - o;
                    qf = new float[] {xa, yE, zn, xa, yE, zf, xa, yE - ft, zf, xa, yE - ft, zn};
                    faceUv(qf, fr, 0, 0, 2, 2, -1, 0, 0);
                } else {
                    float xa = xe + o;
                    qf = new float[] {xa, yE, zf, xa, yE, zn, xa, yE - ft, zn, xa, yE - ft, zf};
                    faceUv(qf, fr, 0, 0, 2, 2, 1, 0, 0);
                }
                // fascia along the front edge of the slope (the rim line the art draws along the gable)
                float xs = left ? x0 - o : xe + o;
                float[] qg = {xs, yE, zf, xm, ridgeH, zf, xm, ridgeH - ft, zf, xs, yE - ft, zf};
                faceUv(qg, fr, 0, 0, 2, 2, 0, 0, 1);
            }
        }

        /** Cross section of the roof's outer edge in the art: per pixel column from the outside in, the average of the edge tile (an edge tile = commonest fully opaque snow tile at the structure's outer column). */
        int[] rimProfile(List<C> cells, boolean left, int xEdge) {
            Map<Integer, Integer> count = new HashMap<>();
            Map<Integer, C> sample = new HashMap<>();
            for (C c : cells) {
                if (c.x != xEdge || c.ti.kind != TileInfo.TOP_SNOW) continue;
                if (edgeTransparent(c, !left) > 0f) continue;
                int key = c.ti.id << 3 | c.t;
                count.merge(key, 1, Integer::sum);
                sample.put(key, c);
            }
            int best = -1, bc = 0;
            for (Map.Entry<Integer, Integer> e : count.entrySet()) if (e.getValue() > bc) { bc = e.getValue(); best = e.getKey(); }
            if (best < 0) return null;
            C c = sample.get(best);
            int[] t = new int[c.ti.tw * c.ti.th];
            c.ti.copyTo(t, c.ti.tw, 0, 0, c.t);
            int[] out = new int[16];
            for (int u = 0; u < 16; u++) {
                int col = left ? u : c.ti.tw - 1 - u;
                long r = 0, g = 0, b = 0, n = 0;
                for (int y = 0; y < c.ti.th; y++) {
                    int p = t[y * c.ti.tw + col];
                    if (TileInfo.opaque(p)) { r += p >> 16 & 255; g += p >> 8 & 255; b += p & 255; n++; }
                }
                out[u] = n == 0 ? 0xFFE0E4EC : 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
            return out;
        }
    }
}
