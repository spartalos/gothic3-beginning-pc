package gothic3.desktop.render3d;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

/**
 * Baked ambient occlusion: a coarse height field (top height of the map geometry per 1/4 tile cell, rasterised from the built mesh quads),
 * dilated and blurred on the CPU into a tiny RGBA texture (R = tight blur, G = wide blur). The main fragment shader reads it by world
 * x / z and darkens the AMBIENT term where the blurred height is above the fragment (floor at the foot of walls, inner corners, the lower
 * part of walls). Bilinear filtering makes it smooth across cells. Render side only.
 */
final class AoField {
    static final int R = 4;               // texels per tile
    static final float X0 = -2f, Z0 = -4f; // world origin of the grid (tiles)
    static final float MAXH = 2.0f;       // heights are clamped to this (tiles)
    private final int w, h;
    private final float[] raw;
    private Texture tex;
    private boolean dirty;
    private final java.util.HashSet<Long> boxes = new java.util.HashSet<>();
    private float texRadius = -1f;

    AoField(int mapW, int mapH) {
        w = (mapW + 4) * R;
        h = (mapH + 8) * R;
        raw = new float[w * h];
    }

    float sizeX() { return w / (float) R; }
    float sizeZ() { return h / (float) R; }

    /** Rasterises the quads (9 floats per vertex: pos3, colour, uv2, normal3) of a static mesh into the height field. */
    void addMesh(Mesh m) {
        int nv = m.getNumVertices();
        int stride = m.getVertexSize() / 4;
        float[] v = new float[nv * stride];
        m.getVertices(v);
        for (int q = 0; q + 3 < nv; q += 4) addQuad(v, q * stride, stride);
    }

    private void addQuad(float[] v, int o, int st) {
        float x0 = v[o], y0 = v[o + 1], z0 = v[o + 2];
        float x1 = v[o + st], y1 = v[o + st + 1], z1 = v[o + st + 2];
        float x2 = v[o + 2 * st], y2 = v[o + 2 * st + 1], z2 = v[o + 2 * st + 2];
        float x3 = v[o + 3 * st], y3 = v[o + 3 * st + 1], z3 = v[o + 3 * st + 2];
        float ymax = Math.max(Math.max(y0, y1), Math.max(y2, y3));
        if (ymax < 0.12f) return;                       // flat ground
        // corners are in ring order 0-1-2-3: bilinear patch p(s,t) = lerp(lerp(p0,p1,s), lerp(p3,p2,s), t)
        final int N = 8;
        for (int a = 0; a <= N; a++) {
            float s = a / (float) N;
            float ax = x0 + (x1 - x0) * s, ay = y0 + (y1 - y0) * s, az = z0 + (z1 - z0) * s;
            float bx = x3 + (x2 - x3) * s, by = y3 + (y2 - y3) * s, bz = z3 + (z2 - z3) * s;
            for (int b = 0; b <= N; b++) {
                float t = b / (float) N;
                put(ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t);
            }
        }
    }

    private void put(float x, float y, float z) {
        if (y < 0.12f) return;
        int ix = (int) Math.floor((x - X0) * R), iz = (int) Math.floor((z - Z0) * R);
        if (ix < 0 || iz < 0 || ix >= w || iz >= h) return;
        float hv = Math.min(y, MAXH);
        int i = iz * w + ix;
        if (hv > raw[i]) raw[i] = hv;
    }

    /** A prop footprint (world tiles) with its top / bottom height; static props are added once (deduplicated), the texture is re-blurred lazily. */
    void addBox(float x0, float x1, float z0, float z1, float top, float base) {
        if (top < 0.15f || base > 0.5f || x1 - x0 > 2.5f || z1 - z0 > 2.5f) return;   // flat rugs, hanging things, huge models
        long key = ((long) Math.round(x0 * 8) * 73856093L) ^ ((long) Math.round(z0 * 8) * 19349663L) ^ ((long) Math.round(x1 * 8) * 83492791L) ^ ((long) Math.round(z1 * 8) * 2654435761L) ^ ((long) Math.round(top * 8) << 40);
        if (boxes.size() > 6000 || !boxes.add(key)) return;
        float hv = Math.min(top, MAXH) * 0.7f;
        float sx = (x1 - x0) * 0.2f, sz = (z1 - z0) * 0.2f;   // sprite boxes include transparent margins: use the core
        x0 += sx; x1 -= sx; z0 += sz; z1 -= sz;
        int ix0 = (int) Math.floor((x0 - X0) * R), ix1 = (int) Math.floor((x1 - X0) * R);
        int iz0 = (int) Math.floor((z0 - Z0) * R), iz1 = (int) Math.floor((z1 - Z0) * R);
        for (int z = Math.max(0, iz0); z <= Math.min(h - 1, iz1); z++)
            for (int x = Math.max(0, ix0); x <= Math.min(w - 1, ix1); x++)
                if (hv > raw[z * w + x]) raw[z * w + x] = hv;
        dirty = true;
    }

    /** The AO texture for the blur radius (tiles); rebuilt only when the radius changed. */
    Texture texture(float radius) {
        if (tex != null && !dirty && Math.abs(radius - texRadius) < 1e-4f) return tex;
        dirty = false;
        if (tex != null) tex.dispose();
        float[] d = dilate(raw);
        float[] tight = blur(d, Math.max(1, Math.round(radius * R * 0.3f)));
        float[] wide = blur(d, Math.max(2, Math.round(radius * R * 0.65f)));
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = y * w + x;
            int r = Math.min(255, Math.round(tight[i] / MAXH * 255f * 1.8f));
            int g = Math.min(255, Math.round(wide[i] / MAXH * 255f * 1.8f));
            pm.drawPixel(x, y, (r << 24) | (g << 16) | 255);
        }
        tex = new Texture(pm);
        pm.dispose();
        tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        tex.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        texRadius = radius;
        return tex;
    }

    /** 3x3 max: thin wall lines become about 3 texels wide so the blur keeps their weight. */
    private float[] dilate(float[] a) {
        float[] o = new float[a.length];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float m = 0;
            for (int j = Math.max(0, y - 1); j <= Math.min(h - 1, y + 1); j++)
                for (int i = Math.max(0, x - 1); i <= Math.min(w - 1, x + 1); i++) m = Math.max(m, a[j * w + i]);
            o[y * w + x] = m;
        }
        return o;
    }

    /** Two box blur passes per axis (about gaussian). */
    private float[] blur(float[] a, int r) {
        float[] t = a;
        for (int pass = 0; pass < 2; pass++) {
            t = box(t, r, true);
            t = box(t, r, false);
        }
        return t;
    }

    private float[] box(float[] a, int r, boolean horiz) {
        float[] o = new float[a.length];
        int n = horiz ? w : h, m = horiz ? h : w;
        float inv = 1f / (2 * r + 1);
        for (int k = 0; k < m; k++) {
            float sum = 0;
            for (int i = -r; i <= r; i++) sum += at(a, horiz, k, Math.max(0, Math.min(n - 1, i)));
            for (int i = 0; i < n; i++) {
                o[horiz ? k * w + i : i * w + k] = sum * inv;
                sum += at(a, horiz, k, Math.min(n - 1, i + r + 1)) - at(a, horiz, k, Math.max(0, i - r));
            }
        }
        return o;
    }

    private float at(float[] a, boolean horiz, int k, int i) { return a[horiz ? k * w + i : i * w + k]; }

    void dispose() {
        if (tex != null) tex.dispose();
        tex = null;
    }
}
