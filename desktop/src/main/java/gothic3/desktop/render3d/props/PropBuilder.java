package gothic3.desktop.render3d.props;

import com.badlogic.gdx.graphics.Color;
import javax.microedition.lcdui.Image;

/** Builds {@link PropModel}s from sprite pixels: voxel extrusion, lathe, crossed cards. */
final class PropBuilder {
    static final float CARD_NY = 0.5f, CARD_NZ = 0.866f;
    static final int MAX_QUADS = 3500;
    private static final float WHITE = Color.WHITE.toFloatBits();

    private float[] v = new float[9 * 4 * 128];
    private int n;                // quads
    private final float T;
    private final Image im;
    private final int xs, ys, w, h, tr;
    private final int sw, sh;
    private final int[] px;

    PropBuilder(Image im, int xs, int ys, int w, int h, int tr, float tileW) {
        this.im = im; this.xs = xs; this.ys = ys; this.w = w; this.h = h; this.tr = tr; this.T = tileW;
        sw = im.getWidth(); sh = im.getHeight();
        px = im.getPixelsInternal();
    }

    // ---- texture coordinate helpers (destination pixel space: p along x, q along y, 0..w / 0..h) ----
    private float u(float p) { p = Math.max(0.02f, Math.min(w - 0.02f, p)); return ((tr & 2) != 0 ? xs + w - p : xs + p) / sw; }
    private float vv(float q) { q = Math.max(0.02f, Math.min(h - 0.02f, q)); return ((tr & 1) != 0 ? ys + h - q : ys + q) / sh; }

    /** Fire sprites: the flame (bright warm pixels with only flame / nothing above them in the column) stays a flat card, never extruded. */
    boolean flame;
    private boolean[] flameMask;

    private boolean isFlame(int dx, int dy) {
        if (flameMask == null) {
            flameMask = new boolean[w * h];
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    int c = argb(x, y);
                    if ((c >>> 24) < 128) continue;
                    int r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
                    if (r >= 190 && g >= 100 && b < 120 && r + g > 330) flameMask[y * w + x] = true; else break;
                }
            }
        }
        return flameMask[dy * w + dx];
    }

    /** Token {@code core}: only the largest 4-connected opaque part is extruded (a stalagmite with its dithered puddle ring: the ring would become stripes). */
    boolean core;
    private boolean[] coreMask;

    private boolean isCore(int dx, int dy) {
        if (coreMask == null) {
            int[] lab = new int[w * h];
            boolean[] dense = new boolean[w * h];   // pixels with at least 3 solid 4-neighbours: thin dithered fringes drop out
            for (int i = 0; i < w * h; i++) {
                int cx = i % w, cy = i / w;
                if (!solid0(cx, cy)) continue;
                int k = (solid0(cx + 1, cy) ? 1 : 0) + (solid0(cx - 1, cy) ? 1 : 0) + (solid0(cx, cy + 1) ? 1 : 0) + (solid0(cx, cy - 1) ? 1 : 0);
                dense[i] = k >= 3;
            }
            int best = 0, bestN = 0, id = 0;
            int[] stack = new int[w * h];
            for (int i = 0; i < w * h; i++) {
                if (lab[i] != 0 || !dense[i]) continue;
                id++;
                int sp = 0, cnt = 0;
                stack[sp++] = i;
                lab[i] = id;
                while (sp > 0) {
                    int c = stack[--sp];
                    cnt++;
                    int cx = c % w, cy = c / w;
                    int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] e : d) {
                        int nx = cx + e[0], ny = cy + e[1];
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h || lab[ny * w + nx] != 0 || !dense[ny * w + nx]) continue;
                        lab[ny * w + nx] = id;
                        stack[sp++] = ny * w + nx;
                    }
                }
                if (cnt > bestN) { bestN = cnt; best = id; }
            }
            coreMask = new boolean[w * h];
            for (int i = 0; i < w * h; i++) coreMask[i] = lab[i] == best && best != 0;
        }
        return coreMask[dy * w + dx];
    }

    private boolean solid(int dx, int dy) {
        if (dx < 0 || dy < 0 || dx >= w || dy >= h) return false;
        if (flame && isFlame(dx, dy)) return false;
        if (core && !isCore(dx, dy)) return false;
        return solid0(dx, dy);
    }

    /** Table models: bottom rows of the sprite (a pedestal stub seen under the top) that are not part of the top surface. */
    private int cut;

    private boolean solid0(int dx, int dy) {
        if (dx < 0 || dy < 0 || dx >= w || dy >= h - cut) return false;
        int sx = (tr & 2) != 0 ? xs + w - 1 - dx : xs + dx, sy = (tr & 1) != 0 ? ys + h - 1 - dy : ys + dy;
        return (px[sy * sw + sx] >>> 24) >= 128;
    }

    private int argb(int dx, int dy) {
        int sx = (tr & 2) != 0 ? xs + w - 1 - dx : xs + dx, sy = (tr & 1) != 0 ? ys + h - 1 - dy : ys + dy;
        return px[sy * sw + sx];
    }

    private void quad(float x0, float y0, float z0, float u0, float v0, float x1, float y1, float z1, float u1, float v1,
                      float x2, float y2, float z2, float u2, float v2, float x3, float y3, float z3, float u3, float v3,
                      float nx, float ny, float nz) {
        int o = n * 36;
        if (o + 36 > v.length) v = java.util.Arrays.copyOf(v, v.length * 2);
        put(o, x0, y0, z0, u0, v0, nx, ny, nz);
        put(o + 9, x1, y1, z1, u1, v1, nx, ny, nz);
        put(o + 18, x2, y2, z2, u2, v2, nx, ny, nz);
        put(o + 27, x3, y3, z3, u3, v3, nx, ny, nz);
        n++;
    }

    private void put(int o, float x, float y, float z, float uu, float vv2, float nx, float ny, float nz) {
        v[o] = x; v[o + 1] = y; v[o + 2] = z; v[o + 3] = WHITE; v[o + 4] = uu; v[o + 5] = vv2;
        v[o + 6] = nx; v[o + 7] = ny; v[o + 8] = nz;
    }

    private PropModel finish(int main, int type) { return new PropModel(java.util.Arrays.copyOf(v, n * 36), main, n, type); }

    /** The plain card (also used as the fallback): one quad at z = 0.02. */
    PropModel card(float zf) {
        frontQuad(zf);
        return finish(n, PropRules.CARD);
    }

    private void frontQuad(float zf) {
        float in = 0.02f;
        float x0 = 0, x1 = w / T, y0 = 0, y1 = -h / T;
        quad(x0, y0, zf, u(in), vv(in), x1, y0, zf, u(w - in), vv(in), x1, y1, zf, u(w - in), vv(h - in), x0, y1, zf, u(in), vv(h - in),
                0, CARD_NY, CARD_NZ);
    }

    /** Two crossed cards: the front card plus one turned 90 degrees around the vertical axis through the sprite's centre. */
    PropModel cross(float zf) {
        frontQuad(zf);
        float in = 0.02f, cx = w / (2 * T), hw = w / (2 * T), y0 = 0, y1 = -h / T;
        float zc = zf - hw * 0.6f;
        quad(cx, y0, zc + hw, u(in), vv(in), cx, y0, zc - hw, u(w - in), vv(in), cx, y1, zc - hw, u(w - in), vv(h - in), cx, y1, zc + hw, u(in), vv(h - in),
                1, 0.3f, 0);
        return finish(n, PropRules.CROSS);
    }

    /** Extruded sprite: front = the art, back, side and top faces from the edge pixels; thickness depth (tiles) towards the north. */
    PropModel voxel(float depth, float zf) {
        float zb = zf - depth;
        // front (main)
        frontQuad(zf);
        // top faces: runs of opaque pixels with a transparent pixel above
        for (int dy = 0; dy < h; dy++) {
            int dx = 0;
            while (dx < w) {
                if (solid(dx, dy) && !solid(dx, dy - 1)) {
                    int e = dx;
                    while (e + 1 < w && solid(e + 1, dy) && !solid(e + 1, dy - 1)) e++;
                    float yy = -dy / T, vq = vv(dy + 0.5f), ua = u(dx + 0.03f), ub = u(e + 1 - 0.03f);
                    quad(dx / T, yy, zf, ua, vq, (e + 1) / T, yy, zf, ub, vq, (e + 1) / T, yy, zb, ub, vq, dx / T, yy, zb, ua, vq, 0, 1, 0);
                    dx = e + 1;
                } else dx++;
            }
            if (n > MAX_QUADS) return null;
        }
        // side faces: vertical runs along a column boundary
        for (int dx = 0; dx < w; dx++) {
            for (int side = 0; side < 2; side++) {
                int nb = side == 0 ? dx - 1 : dx + 1;
                float xx = (side == 0 ? dx : dx + 1) / T, uc = u(dx + 0.5f);
                int dy = 0;
                while (dy < h) {
                    if (solid(dx, dy) && !solid(nb, dy)) {
                        int e = dy;
                        while (e + 1 < h && solid(dx, e + 1) && !solid(nb, e + 1)) e++;
                        float ya = -dy / T, yb = -(e + 1) / T, va = vv(dy + 0.03f), vb = vv(e + 1 - 0.03f);
                        quad(xx, ya, zf, uc, va, xx, ya, zb, uc, va, xx, yb, zb, uc, vb, xx, yb, zf, uc, vb, side == 0 ? -1 : 1, 0, 0);
                        dy = e + 1;
                    } else dy++;
                }
            }
            if (n > MAX_QUADS) return null;
        }
        int main = n;
        // back face (shadow casting only, never visible)
        float in = 0.02f, x1 = w / T, y1 = -h / T;
        quad(0, 0, zb, u(in), vv(in), x1, 0, zb, u(w - in), vv(in), x1, y1, zb, u(w - in), vv(h - in), 0, y1, zb, u(in), vv(h - in), 0, 0, -1);
        return finish(main, PropRules.VOXEL);
    }

    /**
     * Lying object (beds, sleds, boards, corpses): the sprite art is the TOP of a low box of {@code height} tiles. The picture lies flat (x east, z towards the
     * camera, its bottom edge at the anchor, absolute heights), walls rise from the silhouette's edge pixels (south, west, east; the north side is never seen),
     * textured by the edge pixels like the voxel sides. Model y is the absolute height above the floor (see PropLayer.draw).
     */
    PropModel lie(float height) { return lie(height, 0, PropRules.LIE); }

    /**
     * Table: like {@link #lie} (the art is the top, drawn at {@code height}, a solid body below with sides from the edge pixels) but the bottom {@code cutRows}
     * pixel rows of the sprite (a leg / pedestal stub under the top-down art) are left out of the top.
     */
    PropModel table(float height, int cutRows) { return lie(height, Math.max(0, Math.min(cutRows, h - 4)), PropRules.TABLE); }

    private PropModel lie(float height, int cutRows, int type) {
        cut = cutRows;
        float H = height, hd = h / T, zc = cut / T;
        float in = 0.02f, x1 = w / T;
        quad(0, H, -hd, u(in), vv(in), x1, H, -hd, u(w - in), vv(in), x1, H, -zc, u(w - in), vv(h - cut - in), 0, H, -zc, u(in), vv(h - cut - in), 0, 1, 0);
        // south faces (z = bottom of the pixel): runs along x of opaque pixels with a transparent pixel to the south
        for (int dy = 0; dy < h; dy++) {
            int dx = 0;
            while (dx < w) {
                if (solid(dx, dy) && !solid(dx, dy + 1)) {
                    int e = dx;
                    while (e + 1 < w && solid(e + 1, dy) && !solid(e + 1, dy + 1)) e++;
                    float zz = (dy + 1 - h) / T, vq = vv(dy + 0.5f), ua = u(dx + 0.03f), ub = u(e + 1 - 0.03f);
                    quad(dx / T, H, zz, ua, vq, (e + 1) / T, H, zz, ub, vq, (e + 1) / T, 0, zz, ub, vq, dx / T, 0, zz, ua, vq, 0, 0, 1);
                    dx = e + 1;
                } else dx++;
            }
            if (n > MAX_QUADS) return null;
        }
        // west / east faces: runs along z
        for (int dx = 0; dx < w; dx++) {
            for (int side = 0; side < 2; side++) {
                int nb = side == 0 ? dx - 1 : dx + 1;
                float xx = (side == 0 ? dx : dx + 1) / T, uc = u(dx + 0.5f);
                int dy = 0;
                while (dy < h) {
                    if (solid(dx, dy) && !solid(nb, dy)) {
                        int e = dy;
                        while (e + 1 < h && solid(dx, e + 1) && !solid(nb, e + 1)) e++;
                        float za = (dy - h) / T, zb = (e + 1 - h) / T, va = vv(dy + 0.03f), vb = vv(e + 1 - 0.03f);
                        quad(xx, H, za, uc, va, xx, H, zb, uc, vb, xx, 0, zb, uc, vb, xx, 0, za, uc, va, side == 0 ? -1 : 1, 0, 0);
                        dy = e + 1;
                    } else dy++;
                }
            }
            if (n > MAX_QUADS) return null;
        }
        return finish(n, type);
    }

    /** Box of absolute heights y0..y1, x0..x1, z0 (north)..z1 (south) with four faces (top, south, west, east; no bottom / north), each face coloured by one sampled texel. */
    private void box(float x0, float x1, float y0, float y1, float z0, float z1, float uT, float vT, float uS, float vS) {
        quad(x0, y1, z0, uT, vT, x1, y1, z0, uT, vT, x1, y1, z1, uT, vT, x0, y1, z1, uT, vT, 0, 1, 0);
        quad(x0, y1, z1, uS, vS, x1, y1, z1, uS, vS, x1, y0, z1, uS, vS, x0, y0, z1, uS, vS, 0, 0, 1);
        quad(x0, y1, z0, uS, vS, x0, y1, z1, uS, vS, x0, y0, z1, uS, vS, x0, y0, z0, uS, vS, -1, 0, 0);
        quad(x1, y1, z0, uS, vS, x1, y1, z1, uS, vS, x1, y0, z1, uS, vS, x1, y0, z0, uS, vS, 1, 0, 0);
    }

    /**
     * North-south running fence (the art, i36, shows a fence seen along its length: stacked posts with snow caps and a rail strip, which a standing card turned
     * into upright planks). Rebuilt from the art as boxes standing on the floor: one post per snow cap (cap = bright connected pixels; the post stands on the
     * last opaque pixel below it, its height from the cap's top to that base), two rails along z between the first and the last post, colours sampled from the art.
     * Absolute heights (like {@link #lie}); z relative to the sprite's bottom edge. Returns null (card fallback) if no post is found.
     */
    PropModel fenceNS() {
        boolean[] seen = new boolean[w * h];
        java.util.List<int[]> posts = new java.util.ArrayList<>();   // x0, x1 (px, inclusive), capTop row, base row, cap bottom row
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (seen[y * w + x] || !bright(x, y)) continue;
                int x0 = x, x1 = x, y0 = y, y1 = y;
                java.util.ArrayDeque<int[]> st = new java.util.ArrayDeque<>();
                st.push(new int[] {x, y});
                seen[y * w + x] = true;
                while (!st.isEmpty()) {
                    int[] c = st.pop();
                    x0 = Math.min(x0, c[0]); x1 = Math.max(x1, c[0]); y0 = Math.min(y0, c[1]); y1 = Math.max(y1, c[1]);
                    int[][] nb = {{c[0] + 1, c[1]}, {c[0] - 1, c[1]}, {c[0], c[1] + 1}, {c[0], c[1] - 1}};
                    for (int[] q : nb) {
                        if (q[0] < 0 || q[1] < 0 || q[0] >= w || q[1] >= h || seen[q[1] * w + q[0]] || !bright(q[0], q[1])) continue;
                        seen[q[1] * w + q[0]] = true;
                        st.push(q);
                    }
                }
                if (x1 - x0 < 2) continue;       // specks
                int cx = (x0 + x1) / 2, base = y1;
                while (base + 1 < h && solid0(cx, base + 1) && !bright(cx, base + 1) && base - y1 < 20) base++;
                posts.add(new int[] {x0, x1, y0, base, y1});
            }
        }
        if (posts.isEmpty()) return null;
        posts.sort((a, b) -> Integer.compare(a[2], b[2]));
        float zFirst = 0, zLast = 0, xl = 1e9f, xr = -1e9f, hMax = 0;
        float uWood = 0, vWood = 0;
        for (int i = 0; i < posts.size(); i++) {
            int[] p = posts.get(i);
            float px0 = p[0] / T, px1 = (p[1] + 1) / T, zS = (p[3] + 1 - h) / T, zN = zS - (p[1] - p[0] + 1) / T;
            float ph = (p[3] + 1 - p[2]) / T;
            int cx = (p[0] + p[1]) / 2, my = (p[4] + p[3]) / 2;
            box(px0, px1, 0, ph, zN, zS, u(cx + 0.5f), vv(p[2] + 0.5f), u(cx + 0.5f), vv(my + 0.5f));
            if (i == 0) zFirst = zN;
            zLast = zS;
            xl = Math.min(xl, px0); xr = Math.max(xr, px1); hMax = Math.max(hMax, ph);
            uWood = u(cx + 0.5f); vWood = vv(my + 0.5f);
        }
        float cxm = (xl + xr) * 0.5f, rt = 0.07f;
        for (int k = 0; k < 2; k++) {
            float y0 = hMax * (k == 0 ? 0.22f : 0.6f), y1 = y0 + hMax * 0.2f;
            box(cxm - rt, cxm + rt, y0, y1, zFirst, zLast, uWood, vWood, uWood, vWood);
        }
        return finish(n, PropRules.FENCE_NS);
    }

    private boolean bright(int dx, int dy) {
        if (!solid0(dx, dy)) return false;
        int c = argb(dx, dy), r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255, mn = Math.min(r, Math.min(g, b)), mx = Math.max(r, Math.max(g, b));
        return mn >= 140 && mx - mn < 45;
    }

    /**
     * Lathe: the sprite's silhouette (per pixel row: leftmost / rightmost opaque pixel) revolved around the vertical axis through
     * the silhouette's centre, texture from the sprite columns (the front half reproduces the art, the back half mirrors it), flat top cap.
     */
    PropModel lathe(int sides, float zc) {
        int[] l = new int[h], r = new int[h];
        int top = -1, bot = -1;
        for (int dy = 0; dy < h; dy++) {
            l[dy] = -1;
            for (int dx = 0; dx < w; dx++) if (solid(dx, dy)) { if (l[dy] < 0) l[dy] = dx; r[dy] = dx; }
            if (l[dy] >= 0) { if (top < 0) top = dy; bot = dy; }
        }
        if (top < 0) return null;
        // the oblique art shows the top as an ellipse: its far half (rows top .. top+b) becomes the flat cap (planar mapping of the
        // ellipse onto the disc), the body starts at the ellipse's centre row
        float rg = 0;
        for (int dy = top; dy <= top + (bot - top) / 2; dy++) if (l[dy] >= 0) rg = Math.max(rg, (r[dy] - l[dy] + 1) * 0.5f);
        int bEll = Math.max(1, Math.min(Math.round(rg * 0.55f), (bot - top + 1) * 2 / 5));
        int capRow = Math.min(bot, top + bEll);
        while (capRow < bot && l[capRow] < 0) capRow++;
        int wide = capRow;
        for (int dy = capRow; dy <= bot; dy++) if (l[dy] >= 0 && r[dy] - l[dy] > r[wide] - l[wide]) wide = dy;
        float cxp = (l[wide] + r[wide] + 1) * 0.5f;
        float axis = cxp / T;
        for (int dy = capRow; dy <= bot; dy++) {
            if (l[dy] < 0) continue;
            float rad = (r[dy] - l[dy] + 1) * 0.5f;
            float cxr = (l[dy] + r[dy] + 1) * 0.5f;
            float yA = -dy / T, yB = -(dy + 1) / T, vA = vv(dy + 0.03f), vB = vv(dy + 1 - 0.03f);
            float radW = rad / T;
            for (int k = 0; k < sides; k++) {
                // theta: -90 (left) .. 90 (right) over the front (z towards the camera), then round the back
                double t0 = -Math.PI / 2 + 2 * Math.PI * k / sides, t1 = -Math.PI / 2 + 2 * Math.PI * (k + 1) / sides;
                float x0 = axis + radW * (float) Math.sin(t0), x1 = axis + radW * (float) Math.sin(t1);
                float z0 = zc + radW * (float) Math.cos(t0), z1 = zc + radW * (float) Math.cos(t1);
                float ua = cxr + rad * (float) Math.sin(t0), ub = cxr + rad * (float) Math.sin(t1);
                double tm = (t0 + t1) * 0.5;
                float nxm = (float) Math.sin(tm), nzm = (float) Math.cos(tm);
                float nl = (float) Math.sqrt(nxm * nxm + nzm * nzm);
                quad(x0, yA, z0, u(ua), vA, x1, yA, z1, u(ub), vA, x1, yB, z1, u(ub), vB, x0, yB, z0, u(ua), vB, nxm / nl, 0.15f, nzm / nl);
            }
            if (n > MAX_QUADS) return null;
        }
        // flat cap at the body's top: the ellipse region of the sprite mapped planar onto the disc (x -> columns, z -> rows top .. top + 2b)
        float rtPx = (r[capRow] - l[capRow] + 1) * 0.5f, rt = rtPx / T, ytop = -capRow / T;
        float cxc = (l[capRow] + r[capRow] + 1) * 0.5f;
        float b = capRow - top;
        if (b < 1) b = 1;
        float vMid = capRow;
        for (int k = 0; k < sides; k += 2) {
            double[] t = {2 * Math.PI * k / sides, 2 * Math.PI * (k + 1) / sides, 2 * Math.PI * (k + 2) / sides};
            float[] cx = new float[4], cz = new float[4];
            cx[0] = 0; cz[0] = 0;
            for (int j = 0; j < 3; j++) { cx[j + 1] = (float) Math.sin(t[j]); cz[j + 1] = (float) Math.cos(t[j]); }
            quad(axis, ytop, zc, u(cxc), vv(vMid),
                    axis + rt * cx[1], ytop, zc + rt * cz[1], u(cxc + rtPx * cx[1]), vv(vMid + b * cz[1]),
                    axis + rt * cx[2], ytop, zc + rt * cz[2], u(cxc + rtPx * cx[2]), vv(vMid + b * cz[2]),
                    axis + rt * cx[3], ytop, zc + rt * cz[3], u(cxc + rtPx * cx[3]), vv(vMid + b * cz[3]), 0, 1, 0);
        }
        return finish(n, PropRules.LATHE);
    }
}
