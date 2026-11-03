package gothic3.desktop.render3d.structures;

import gothic3.platform.scene3d.MapSnapshot;

/** Pixels and simple material statistics of one layer-1 tile (transform 0). */
final class TileInfo {
    static final int EMPTY = 0, TOP_SNOW = 1, TOP_BLACK = 2, FACE = 3, MIXED = 4;
    static final String[] NAMES = {"empty", "snow", "black", "face", "mixed"};

    final int id;
    final int tw, th;
    final int[] px;
    float cov, snow, dark, mat;
    int kind;
    /** Average colour (ARGB) of the first opaque pixel row from the top, 0 if none. */
    int topColor;

    TileInfo(MapSnapshot m, int id) {
        this.id = id;
        tw = m.tileW;
        th = m.tileH;
        px = new int[tw * th];
        if (m.sheet1 != null && id >= 0) {
            int sx = id % m.cols1 * tw, sy = id / m.cols1 % m.rows1 * th;
            int[] sp = m.sheet1.getPixelsInternal();
            if (sx + tw <= m.sheet1.getWidth() && sy + th <= m.sheet1.getHeight())
                for (int y = 0; y < th; y++) System.arraycopy(sp, (sy + y) * m.sheet1.getWidth() + sx, px, y * tw, tw);
        }
        int n = 0, sn = 0, dk = 0;
        for (int p : px) {
            if (!opaque(p)) continue;
            n++;
            if (isSnow(p)) sn++;
            else if (isDark(p)) dk++;
        }
        cov = n / (float) px.length;
        if (n == 0) { kind = EMPTY; return; }
        snow = sn / (float) n;
        dark = dk / (float) n;
        mat = 1f - snow - dark;
        if (dark >= 0.8f) kind = TOP_BLACK;
        else if (snow >= 0.8f) kind = TOP_SNOW;
        else if (snow >= 0.52f && mat >= 0.15f) kind = MIXED;
        else kind = FACE;
        for (int y = 0; y < th && topColor == 0; y++) {
            long r = 0, g = 0, b = 0, c = 0;
            for (int x = 0; x < tw; x++) {
                int p = px[y * tw + x];
                if (opaque(p)) { r += p >> 16 & 255; g += p >> 8 & 255; b += p & 255; c++; }
            }
            if (c > 0) topColor = 0xFF000000 | (int) (r / c) << 16 | (int) (g / c) << 8 | (int) (b / c);
        }
    }

    static boolean opaque(int p) { return (p >>> 24) >= 128; }

    static boolean isSnow(int p) {
        int r = p >> 16 & 255, g = p >> 8 & 255, b = p & 255;
        float l = 0.299f * r + 0.587f * g + 0.114f * b;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        return l >= 115 && mx - mn <= 45 && b >= r - 8;
    }

    /** Brown wood / warm pixels (kept in gable triangles); snow, grey and blue shades are not. */
    static boolean isWarm(int p) {
        int r = p >> 16 & 255, b = p & 255;
        return r - b >= 7;
    }

    static boolean isDark(int p) {
        return 0.299f * (p >> 16 & 255) + 0.587f * (p >> 8 & 255) + 0.114f * (p & 255) < 30;
    }

    /** Copies the tile with MIDP transform t (0-7) into dst (row stride) at (dx, dy). */
    void copyTo(int[] dst, int stride, int dx, int dy, int t) {
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                float a = (x + 0.5f) / tw, b = (y + 0.5f) / th, u, v;
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
                int sx = Math.min(tw - 1, (int) (u * tw)), sy = Math.min(th - 1, (int) (v * th));
                dst[(dy + y) * stride + dx + x] = px[sy * tw + sx];
            }
        }
    }
}
