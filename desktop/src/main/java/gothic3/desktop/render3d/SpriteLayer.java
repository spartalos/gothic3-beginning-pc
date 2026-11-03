package gothic3.desktop.render3d;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import gothic3.platform.scene3d.Scene3D;
import javax.microedition.lcdui.Image;

/**
 * Draws the captured draw calls of a frame: ground decals (flat), upright sprites, then world UI. Batches runs of
 * the same texture into one dynamic mesh, preserving the game's painter order.
 */
final class SpriteLayer {
    private static final int MAX_QUADS = 2048;
    private final Mesh mesh = new Mesh(false, MAX_QUADS * 4, MAX_QUADS * 6, QuadBuilder.ATTRS);
    private final float[] v = new float[MAX_QUADS * 4 * QuadBuilder.FLOATS];
    private int quads;
    private Texture cur;
    private ShaderProgram sh;
    private final TextureCache tc;
    int drawn;
    /** Skip the game's blob shadow decals (the 16x8 dither sprite, image id 171) when real shadows are drawn. */
    boolean skipBlob;
    /** Draws that have a prop model (drawn by the prop layer instead): skipped in the upright pass. */
    java.util.BitSet skip;
    /** ATMO rim light: 0 = off, +1 / -1 = rim on the screen's right / left edge of the upright cards (uniform u_rim is set by the caller). */
    int rimSide;
    /** Per draw: tiles to lower an upright object sprite onto the floor (PropLayer.lift), or null. */
    float[] lift;
    /** Per draw: tiles to move an upright vegetation card back (north), see PropLayer.back, or null. */
    float[] back;
    /** Per draw: extra lift (negative = raise), see PropLayer.liftAdd, or null. */
    float[] liftAdd;
    /** SWAY: per draw wind sway amount of vegetation cards (PropLayer.sway), or null = off. Cards that sway are cut into horizontal strips so the bend is smooth. */
    float[] sway;
    private float swayCur;
    /** Overhead icons (overlay draws flagged by the game): lift in tiles above the 2D height, and size factor about the icon's bottom centre. */
    float iconLift, iconScale = 1f;
    private int rimState = -1;

    SpriteLayer(ShaderProgram sh, TextureCache tc) {
        this.sh = sh;
        this.tc = tc;
        mesh.setIndices(QuadBuilder.QUAD_IDX, 0, MAX_QUADS * 6);
    }

    private void flush() {
        if (quads == 0 || cur == null) { quads = 0; return; }
        cur.bind(0);
        sh.setUniformi("u_tex", 0);
        NormalMaps.page(sh, cur, 0, 0);
        if (rimSide != 0 && rimState >= 0) { // ATMO: edge direction in texture space (flips) and texel size for the rim light
            int fx = rimState & 3, fy = rimState >> 2;     // fx: 0 none, 1 normal, 2 mirrored; fy: 1 normal, 2 flipped
            sh.setUniformf("u_rimDir", fx == 0 ? 0f : (fx == 1 ? rimSide : -rimSide), fy == 2 ? 1f : -1f);
            sh.setUniformf("u_texSz", 1f / cur.getWidth(), 1f / cur.getHeight());
        }
        mesh.setVertices(v, 0, quads * 4 * QuadBuilder.FLOATS);
        mesh.render(sh, GL20.GL_TRIANGLES, 0, quads * 6);
        quads = 0;
    }

    private void use(Texture t) {
        if (t != cur || quads == MAX_QUADS) {
            flush();
            cur = t;
        }
    }

    private float nx, ny = 1, nz;

    private void vert(int i, float x, float y, float z, float c, float u, float w) {
        int o = (quads * 4 + i) * QuadBuilder.FLOATS;
        v[o] = x; v[o + 1] = y; v[o + 2] = z; v[o + 3] = c; v[o + 4] = u; v[o + 5] = w;
        v[o + 6] = nx; v[o + 7] = ny; v[o + 8] = nz;
    }

    /**
     * @param pass which Scene3D.PASS_* to draw now
     * @param vscale vertical scale of standing sprites, @param lean tan of the lean angle (top moves towards the camera)
     */
    void draw(Scene3D s, int pass, float vscale, float lean) { draw(s, pass, vscale, lean, false); }

    /** Shadow depth pass: the upright image sprites only (the shader alpha tests them). */
    void drawCasters(ShaderProgram depth, Scene3D s, float vscale, float lean) {
        ShaderProgram old = sh;
        sh = depth;
        try { draw(s, Scene3D.PASS_UPRIGHT, vscale, lean, true); } finally { sh = old; }
    }

    private void draw(Scene3D s, int pass, float vscale, float lean, boolean casters) {
        float T = s.map != null ? s.map.tileW : 16;
        cur = null;
        quads = 0;
        rimState = -1;
        swayCur = 0f;
        float[] uv = new float[8];
        for (int i = 0; i < s.count; i++) {
            int o = i * Scene3D.STRIDE;
            int head = s.d[o];
            if ((head >> 8) != pass) continue;
            if (skip != null && (pass == Scene3D.PASS_UPRIGHT || pass == Scene3D.PASS_FLAT) && skip.get(i)) continue;
            int kind = head & 255;
            Image im = s.img[i];
            Texture t;
            float color = Color.WHITE.toFloatBits();
            int w = s.d[o + 3], h = s.d[o + 4], tr = s.d[o + 5];
            int tw = (tr >= 4) ? h : w, th = (tr >= 4) ? w : h;
            if (kind == Scene3D.KIND_RECT) {
                if (casters) continue;
                t = tc.white();
                int argb = s.d[o + 10];
                color = new Color((argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f, (argb >>> 24) / 255f).toFloatBits();
                tw = w;
                th = h;
                uv[0] = uv[1] = 0; uv[2] = 1; uv[3] = 0; uv[4] = 1; uv[5] = 1; uv[6] = 0; uv[7] = 1;
            } else {
                if (im == null) continue;
                if (skipBlob && pass == Scene3D.PASS_FLAT && w == 16 && h == 8 && im.getWidth() == 16 && im.getHeight() == 8) continue;
                t = tc.get(im);
                int sw = im.getWidth(), sh2 = im.getHeight();
                float inset = 0.02f;
                int xs = s.d[o + 1], ys = s.d[o + 2];
                for (int k = 0; k < 4; k++) {
                    float a = (k == 1 || k == 2) ? 1 : 0, b = k >= 2 ? 1 : 0;
                    float uu, vv;
                    switch (tr) {
                        case 1: uu = a; vv = 1 - b; break;
                        case 2: uu = 1 - a; vv = b; break;
                        case 3: uu = 1 - a; vv = 1 - b; break;
                        case 4: uu = b; vv = a; break;
                        case 5: uu = b; vv = 1 - a; break;
                        case 6: uu = 1 - b; vv = a; break;
                        case 7: uu = 1 - b; vv = 1 - a; break;
                        default: uu = a; vv = b;
                    }
                    uu = inset + uu * (w - 2 * inset);
                    vv = inset + vv * (h - 2 * inset);
                    uv[k * 2] = (xs + uu) / sw;
                    uv[k * 2 + 1] = (ys + vv) / sh2;
                }
            }
            if (rimSide != 0 && pass == Scene3D.PASS_UPRIGHT && !casters) {
                int st = tr == 0 ? 1 | 4 : tr == 1 ? 1 | 8 : tr == 2 ? 2 | 4 : tr == 3 ? 2 | 8 : 4;   // fx | fy << 2
                if (st != rimState) { flush(); rimState = st; }
            }
            float sa = sway != null && pass == Scene3D.PASS_UPRIGHT && i < sway.length ? sway[i] : 0f;
            if (sa != swayCur) { flush(); swayCur = sa; sh.setUniformf("u_swayAmt", sa); }
            use(t);
            float ax = s.d[o + 8] / T, az = s.d[o + 9] / T;
            float x0 = ax + s.d[o + 6] / T, x1 = x0 + tw / T;
            float oy = s.d[o + 7] / T, hh = th / T;
            if (pass == Scene3D.PASS_FLAT) {
                nx = 0; ny = 1; nz = 0;
                float y = 0.006f + (i & 3) * 0.0004f - (liftAdd != null && i < liftAdd.length ? liftAdd[i] * vscale : 0f);
                float z0 = az + oy, z1 = z0 + hh;
                vert(0, x0, y, z0, color, uv[0], uv[1]);
                vert(1, x1, y, z0, color, uv[2], uv[3]);
                vert(2, x1, y, z1, color, uv[4], uv[5]);
                vert(3, x0, y, z1, color, uv[6], uv[7]);
            } else {
                nx = 0; ny = MapMesh.CARD_NY; nz = MapMesh.CARD_NZ;
                float lf = lift != null && pass == Scene3D.PASS_UPRIGHT && i < lift.length ? lift[i] : 0f;
                if (liftAdd != null && i < liftAdd.length) lf += liftAdd[i];
                float yTop = (-oy - lf) * vscale, yBot = (-(oy + hh) - lf) * vscale;
                if (pass == Scene3D.PASS_OVERLAY && s.d[o + 11] == 1) {   // icon: bigger and floating clearly above the head
                    float cxm = (x0 + x1) * 0.5f, half = (x1 - x0) * 0.5f * iconScale;
                    x0 = cxm - half;
                    x1 = cxm + half;
                    yTop = yBot + (yTop - yBot) * iconScale + iconLift;
                    yBot += iconLift;
                }
                float bk = back != null && pass == Scene3D.PASS_UPRIGHT && i < back.length ? back[i] : 0f;
                float zt = az - bk + yTop * lean, zb = az - bk + yBot * lean;
                if (sa > 0f) {   // SWAY: horizontal strips (the shader bends them by height)
                    int n = Math.max(2, Math.min(10, (int) Math.ceil((yTop - yBot) * 3f)));
                    if (quads + n > MAX_QUADS) flush();
                    for (int k = 0; k < n; k++) {
                        float a0 = k / (float) n, a1 = (k + 1) / (float) n;
                        float ya = yTop + (yBot - yTop) * a0, yb = yTop + (yBot - yTop) * a1, za = zt + (zb - zt) * a0, zc = zt + (zb - zt) * a1;
                        vert(0, x0, ya, za, color, uv[0] + (uv[6] - uv[0]) * a0, uv[1] + (uv[7] - uv[1]) * a0);
                        vert(1, x1, ya, za, color, uv[2] + (uv[4] - uv[2]) * a0, uv[3] + (uv[5] - uv[3]) * a0);
                        vert(2, x1, yb, zc, color, uv[2] + (uv[4] - uv[2]) * a1, uv[3] + (uv[5] - uv[3]) * a1);
                        vert(3, x0, yb, zc, color, uv[0] + (uv[6] - uv[0]) * a1, uv[1] + (uv[7] - uv[1]) * a1);
                        quads++;
                    }
                    drawn++;
                    continue;
                }
                vert(0, x0, yTop, zt, color, uv[0], uv[1]);
                vert(1, x1, yTop, zt, color, uv[2], uv[3]);
                vert(2, x1, yBot, zb, color, uv[4], uv[5]);
                vert(3, x0, yBot, zb, color, uv[6], uv[7]);
            }
            quads++;
            drawn++;
        }
        flush();
        if (swayCur != 0f) { sh.setUniformf("u_swayAmt", 0f); swayCur = 0f; }
    }

    private Texture softTex;

    /** A 32x32 white disc with a smooth radial alpha falloff (foot shadows). */
    private Texture soft() {
        if (softTex == null) {
            com.badlogic.gdx.graphics.Pixmap pm = new com.badlogic.gdx.graphics.Pixmap(32, 32, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    float dx = (x + 0.5f - 16f) / 16f, dy = (y + 0.5f - 16f) / 16f, d = (float) Math.sqrt(dx * dx + dy * dy);
                    float a = Math.max(0f, Math.min(1f, 1f - d));
                    a = a * a * (3f - 2f * a);
                    pm.drawPixel(x, y, 0xFFFFFF00 | Math.round(a * 255f));
                }
            }
            softTex = new Texture(pm);
            softTex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            softTex.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
            pm.dispose();
        }
        return softTex;
    }

    /**
     * Foot (contact) shadows of upright card props: soft dark ellipses on the ground under the base, stretched along the shadow direction (sx, sz) (unit,
     * ground plane), see PropLayer.foot. Flat pass state (blend on, depth test, no depth write).
     * @param foot per entry: centre x, centre z, half width (tiles); draws: draw index per entry
     */
    void drawFoot(Scene3D s, float[] foot, int[] draws, int n, float sx, float sz, float strength, float vscale) {
        if (n == 0 || strength <= 0f) return;
        cur = null;
        quads = 0;
        use(soft());
        float col = new Color(0.05f, 0.07f, 0.17f, strength).toFloatBits();
        nx = 0; ny = 1; nz = 0;
        float px = -sz, pz = sx;                       // perpendicular
        for (int k = 0; k < n; k++) {
            float cx = foot[k * 3], cz = foot[k * 3 + 1], hw = Math.max(0.34f, foot[k * 3 + 2] * 1.2f);
            int i = draws[k];
            float lift = liftAdd != null && i < liftAdd.length ? -liftAdd[i] * vscale : 0f;
            float L = Math.min(1.6f, 0.55f + hw * 1.3f), W = hw * 1.15f;     // half length along the shadow, half width across it
            float mx = cx + sx * L * 0.85f, mz = cz + sz * L * 0.85f;
            float y = 0.03f + lift;     // (0.007 was lost to depth precision at the camera distance)
            use(softTex);
            vert(0, mx - sx * L - px * W, y, mz - sz * L - pz * W, col, 0, 0);
            vert(1, mx - sx * L + px * W, y, mz - sz * L + pz * W, col, 1, 0);
            vert(2, mx + sx * L + px * W, y, mz + sz * L + pz * W, col, 1, 1);
            vert(3, mx + sx * L - px * W, y, mz + sz * L - pz * W, col, 0, 1);
            quads++;
        }
        flush();
    }

    void dispose() { mesh.dispose(); if (softTex != null) softTex.dispose(); }
}
