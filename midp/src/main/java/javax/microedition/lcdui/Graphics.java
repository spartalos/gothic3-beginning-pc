package javax.microedition.lcdui;

/**
 * Software MIDP Graphics drawing into an int[] ARGB buffer. Drawing targets (screen
 * buffers and mutable images) are treated as opaque; source alpha is blended.
 * Colour alpha in setColor is ignored (as in MIDP).
 */
public class Graphics {
    public static final int HCENTER = 1, VCENTER = 2, LEFT = 4, RIGHT = 8, TOP = 16, BOTTOM = 32, BASELINE = 64;
    public static final int SOLID = 0, DOTTED = 1;

    private final int[] buf;
    private final int bw, bh;
    private int tx, ty;
    // user clip in absolute device coordinates (not intersected with bounds)
    private int clipX, clipY, clipW, clipH;
    // effective clip [x0,x1) x [y0,y1)
    private int x0, y0, x1, y1;
    private int color = 0xFF000000;
    private int stroke = SOLID;
    private Font font = Font.getDefaultFont();
    // PORT: 3D renderer capture mode (see gothic3.platform.scene3d.DrawCapture): primitives are recorded / dropped, not rasterised
    private boolean capturing;

    /** Non-MIDP: capture mode on/off. */
    public void setCapture(boolean on) { capturing = on; }

    /** Non-MIDP: fills the whole buffer with transparent black (ARGB 0); blending onto it then keeps real alpha. */
    public void clearTransparent() { java.util.Arrays.fill(buf, 0); }

    Graphics(int[] buf, int w, int h) {
        this.buf = buf;
        this.bw = w;
        this.bh = h;
        setClip(0, 0, w, h);
    }

    // ---------------------------------------------------------------- state

    public void translate(int x, int y) { tx += x; ty += y; }
    public int getTranslateX() { return tx; }
    public int getTranslateY() { return ty; }

    public int getColor() { return color & 0xFFFFFF; }
    public int getRedComponent() { return (color >> 16) & 255; }
    public int getGreenComponent() { return (color >> 8) & 255; }
    public int getBlueComponent() { return color & 255; }
    public int getGrayScale() { return (getRedComponent() + getGreenComponent() + getBlueComponent()) / 3; }

    public void setColor(int rgb) { color = 0xFF000000 | rgb; }
    public void setColor(int r, int g, int b) {
        if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) throw new IllegalArgumentException();
        color = 0xFF000000 | r << 16 | g << 8 | b;
    }
    public void setGrayScale(int v) {
        if (v < 0 || v > 255) throw new IllegalArgumentException();
        setColor(v, v, v);
    }

    public Font getFont() { return font; }
    public void setFont(Font f) { font = f == null ? Font.getDefaultFont() : f; }
    public int getStrokeStyle() { return stroke; }
    public void setStrokeStyle(int s) {
        if (s != SOLID && s != DOTTED) throw new IllegalArgumentException();
        stroke = s;
    }

    public int getClipX() { return clipX - tx; }
    public int getClipY() { return clipY - ty; }
    public int getClipWidth() { return clipW; }
    public int getClipHeight() { return clipH; }

    public void setClip(int x, int y, int w, int h) {
        if (w < 0) w = 0;
        if (h < 0) h = 0;
        clipX = x + tx;
        clipY = y + ty;
        clipW = w;
        clipH = h;
        updateClip();
    }

    public void clipRect(int x, int y, int w, int h) {
        int ax = x + tx, ay = y + ty;
        int nx0 = Math.max(clipX, ax), ny0 = Math.max(clipY, ay);
        int nx1 = (int) Math.min((long) clipX + clipW, (long) ax + Math.max(w, 0));
        int ny1 = (int) Math.min((long) clipY + clipH, (long) ay + Math.max(h, 0));
        clipX = nx0;
        clipY = ny0;
        clipW = Math.max(0, nx1 - nx0);
        clipH = Math.max(0, ny1 - ny0);
        updateClip();
    }

    private void updateClip() {
        x0 = Math.max(0, clipX);
        y0 = Math.max(0, clipY);
        x1 = (int) Math.min(bw, (long) clipX + clipW);
        y1 = (int) Math.min(bh, (long) clipY + clipH);
        if (x1 < x0) x1 = x0;
        if (y1 < y0) y1 = y0;
    }

    // ---------------------------------------------------------- primitives

    /** Plots in ABSOLUTE device coordinates with clip. */
    void plot(int ax, int ay) {
        if (capturing) return;
        // glyph code passes translated-space coords; callers convert below
        if (ax >= x0 && ax < x1 && ay >= y0 && ay < y1) buf[ay * bw + ax] = color;
    }

    void hline(int ax0, int ax1, int ay) {
        if (capturing) return;
        if (ay < y0 || ay >= y1) return;
        int a = Math.max(ax0, x0), b = Math.min(ax1, x1 - 1);
        if (a > b) return;
        int o = ay * bw;
        java.util.Arrays.fill(buf, o + a, o + b + 1, color);
    }

    private void fillAbs(int ax, int ay, int w, int h) {
        if (capturing) return;
        long ex = (long) ax + w, ey = (long) ay + h;
        int a = Math.max(ax, x0), b = (int) Math.min(ex, x1);
        int c = Math.max(ay, y0), d = (int) Math.min(ey, y1);
        if (a >= b || c >= d) return;
        for (int y = c; y < d; y++) {
            int o = y * bw;
            java.util.Arrays.fill(buf, o + a, o + b, color);
        }
    }

    public void fillRect(int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (capturing) {
            gothic3.platform.scene3d.DrawCapture.rect(color, x + tx, y + ty, w, h);
            return;
        }
        fillAbs(x + tx, y + ty, w, h);
    }

    /** MIDP: covers (w+1) x (h+1) pixels. */
    public void drawRect(int x, int y, int w, int h) {
        if (w < 0 || h < 0) return;
        if (stroke == DOTTED) {
            drawLine(x, y, x + w, y);
            drawLine(x + w, y, x + w, y + h);
            drawLine(x + w, y + h, x, y + h);
            drawLine(x, y + h, x, y);
            return;
        }
        int ax = x + tx, ay = y + ty;
        hline(ax, ax + w, ay);
        if (h > 0) hline(ax, ax + w, ay + h);
        if (h > 1) {
            fillAbs(ax, ay + 1, 1, h - 1);
            if (w > 0) fillAbs(ax + w, ay + 1, 1, h - 1);
        }
    }

    public void drawLine(int xa, int ya, int xb, int yb) {
        int ax = xa + tx, ay = ya + ty, bx = xb + tx, by = yb + ty;
        if (stroke == SOLID) {
            if (ay == by) { hline(Math.min(ax, bx), Math.max(ax, bx), ay); return; }
            if (ax == bx) {
                fillAbs(ax, Math.min(ay, by), 1, Math.abs(by - ay) + 1);
                return;
            }
        }
        int dx = Math.abs(bx - ax), dy = -Math.abs(by - ay);
        int sx = ax < bx ? 1 : -1, sy = ay < by ? 1 : -1;
        int err = dx + dy;
        int n = 0;
        for (;;) {
            if (stroke == SOLID || (n & 1) == 0) plot(ax, ay);
            n++;
            if (ax == bx && ay == by) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; ax += sx; }
            if (e2 <= dx) { err += dx; ay += sy; }
        }
    }

    public void fillTriangle(int xa, int ya, int xb, int yb, int xc, int yc) {
        if (capturing) return;
        int ax = xa + tx, ay = ya + ty, bx = xb + tx, by = yb + ty, cx = xc + tx, cy = yc + ty;
        int minY = Math.min(ay, Math.min(by, cy)), maxY = Math.max(ay, Math.max(by, cy));
        int minX = Math.min(ax, Math.min(bx, cx)), maxX = Math.max(ax, Math.max(bx, cx));
        for (int y = Math.max(minY, y0); y <= maxY && y < y1; y++)
            for (int x = Math.max(minX, x0); x <= maxX && x < x1; x++) {
                long d1 = (long) (x - bx) * (ay - by) - (long) (ax - bx) * (y - by);
                long d2 = (long) (x - cx) * (by - cy) - (long) (bx - cx) * (y - cy);
                long d3 = (long) (x - ax) * (cy - ay) - (long) (cx - ax) * (y - ay);
                boolean neg = d1 < 0 || d2 < 0 || d3 < 0, pos = d1 > 0 || d2 > 0 || d3 > 0;
                if (!(neg && pos)) buf[y * bw + x] = color;
            }
    }

    public void fillArc(int x, int y, int w, int h, int startAngle, int arcAngle) {
        arc(x, y, w, h, startAngle, arcAngle, true);
    }

    public void drawArc(int x, int y, int w, int h, int startAngle, int arcAngle) {
        arc(x, y, w, h, startAngle, arcAngle, false);
    }

    private void arc(int x, int y, int w, int h, int start, int sweep, boolean fill) {
        if (capturing) return;
        if (w < 0 || h < 0) return;
        double cx = x + tx + w / 2.0, cy = y + ty + h / 2.0, rx = w / 2.0, ry = h / 2.0;
        int lo = Math.max(y + ty, y0), hi = Math.min(y + ty + h, y1 - 1);
        for (int py = lo; py <= hi; py++)
            for (int px = Math.max(x + tx, x0); px <= Math.min(x + tx + w, x1 - 1); px++) {
                double dx = (px + 0.5 - cx) / Math.max(rx, 0.5), dy = (py + 0.5 - cy) / Math.max(ry, 0.5);
                double d = dx * dx + dy * dy;
                boolean in = fill ? d <= 1.0 : (d <= 1.0 && d >= 1.0 - 2.0 / Math.max(1, Math.min(w, h)));
                if (!in) continue;
                double ang = Math.toDegrees(Math.atan2(-dy, dx));
                if (ang < 0) ang += 360;
                double rel = sweep >= 0 ? ang - start : start - ang;
                rel = ((rel % 360) + 360) % 360;
                if (Math.abs(sweep) >= 360 || rel <= Math.abs(sweep)) buf[py * bw + px] = color;
            }
    }

    public void fillRoundRect(int x, int y, int w, int h, int aw, int ah) {
        if (w <= 0 || h <= 0) return;
        roundRect(x, y, w, h, aw, ah, true);
    }

    public void drawRoundRect(int x, int y, int w, int h, int aw, int ah) {
        if (w < 0 || h < 0) return;
        roundRect(x, y, w + 1, h + 1, aw, ah, false);
    }

    private void roundRect(int x, int y, int w, int h, int aw, int ah, boolean fill) {
        if (capturing) return;
        double rx = Math.min(Math.abs(aw), w) / 2.0, ry = Math.min(Math.abs(ah), h) / 2.0;
        for (int py = 0; py < h; py++)
            for (int px = 0; px < w; px++) {
                double dx = 0, dy = 0;
                if (px + 0.5 < rx) dx = (rx - px - 0.5) / Math.max(rx, 1e-9);
                else if (px + 0.5 > w - rx) dx = (px + 0.5 - (w - rx)) / Math.max(rx, 1e-9);
                if (py + 0.5 < ry) dy = (ry - py - 0.5) / Math.max(ry, 1e-9);
                else if (py + 0.5 > h - ry) dy = (py + 0.5 - (h - ry)) / Math.max(ry, 1e-9);
                double d = dx * dx + dy * dy;
                boolean in = d <= 1.0;
                if (in && !fill) {
                    boolean edge = px == 0 || py == 0 || px == w - 1 || py == h - 1 || d > 0.7;
                    in = edge;
                }
                if (in) plot(x + tx + px, y + ty + py);
            }
    }

    // ------------------------------------------------------------- text

    public void drawString(String str, int x, int y, int anchor) {
        drawSubstring(str, 0, str.length(), x, y, anchor);
    }

    public void drawSubstring(String str, int offset, int length, int x, int y, int anchor) {
        if (offset < 0 || length < 0 || offset + length > str.length()) throw new StringIndexOutOfBoundsException();
        text(str.substring(offset, offset + length), x, y, anchor);
    }

    public void drawChar(char c, int x, int y, int anchor) { text(String.valueOf(c), x, y, anchor); }

    public void drawChars(char[] data, int offset, int length, int x, int y, int anchor) {
        if (offset < 0 || length < 0 || offset + length > data.length) throw new ArrayIndexOutOfBoundsException();
        text(new String(data, offset, length), x, y, anchor);
    }

    private void text(String s, int x, int y, int anchor) {
        int w = font.stringWidth(s);
        int ax = x + tx, ay = y + ty;
        if ((anchor & HCENTER) != 0) ax -= w / 2;
        else if ((anchor & RIGHT) != 0) ax -= w;
        if ((anchor & BOTTOM) != 0) ay -= font.getHeight();
        else if ((anchor & BASELINE) != 0) ay -= font.getBaselinePosition();
        font.drawGlyphs(this, s, ax, ay);
    }

    // ----------------------------------------------------------- images

    /** (dx,dy) in transformed region -> {sx,sy} in the w x h source region. */
    static int[] srcCoord(int t, int dx, int dy, int w, int h) {
        switch (t) {
            case 0: return new int[] {dx, dy};
            case 1: return new int[] {dx, h - 1 - dy};
            case 2: return new int[] {w - 1 - dx, dy};
            case 3: return new int[] {w - 1 - dx, h - 1 - dy};
            case 4: return new int[] {dy, dx};
            case 5: return new int[] {dy, h - 1 - dx};
            case 6: return new int[] {w - 1 - dy, dx};
            case 7: return new int[] {w - 1 - dy, h - 1 - dx};
            default: throw new IllegalArgumentException("transform");
        }
    }

    public void drawImage(Image img, int x, int y, int anchor) {
        if (img == null) throw new NullPointerException();
        drawRegion(img, 0, 0, img.width, img.height, 0, x, y, anchor);
    }

    public void drawRegion(Image src, int xs, int ys, int w, int h, int transform, int xd, int yd, int anchor) {
        if (src == null) throw new NullPointerException();
        if (transform < 0 || transform > 7) throw new IllegalArgumentException("transform");
        if (w < 0 || h < 0 || xs < 0 || ys < 0 || xs + w > src.width || ys + h > src.height)
            throw new IllegalArgumentException("region");
        if (w == 0 || h == 0) return;
        boolean swap = transform >= 4;
        int tw = swap ? h : w, th = swap ? w : h;
        int ax = xd + tx, ay = yd + ty;
        if ((anchor & HCENTER) != 0) ax -= tw / 2;
        else if ((anchor & RIGHT) != 0) ax -= tw;
        if ((anchor & VCENTER) != 0) ay -= th / 2;
        else if ((anchor & BOTTOM) != 0) ay -= th;
        if (capturing) {
            gothic3.platform.scene3d.DrawCapture.region(src, xs, ys, w, h, transform, ax, ay, tw, th);
            return;
        }
        int[] sp = src.pixels;
        int sw = src.width;
        // precompute per-transform stepping: src = base + dx*stepX + dy*stepY
        int base, stepX, stepY;
        switch (transform) {
            case 0: base = ys * sw + xs; stepX = 1; stepY = sw; break;
            case 1: base = (ys + h - 1) * sw + xs; stepX = 1; stepY = -sw; break;
            case 2: base = ys * sw + xs + w - 1; stepX = -1; stepY = sw; break;
            case 3: base = (ys + h - 1) * sw + xs + w - 1; stepX = -1; stepY = -sw; break;
            case 4: base = ys * sw + xs; stepX = sw; stepY = 1; break;
            case 5: base = (ys + h - 1) * sw + xs; stepX = -sw; stepY = 1; break;
            case 6: base = ys * sw + xs + w - 1; stepX = sw; stepY = -1; break;
            default: base = (ys + h - 1) * sw + xs + w - 1; stepX = -sw; stepY = -1; break;
        }
        if (sp == buf) { // same buffer: copy first
            sp = sp.clone();
        }
        int ya = Math.max(0, y0 - ay), yb = Math.min(th, y1 - ay);
        int xa = Math.max(0, x0 - ax), xb = Math.min(tw, x1 - ax);
        for (int dy = ya; dy < yb; dy++) {
            int di = (ay + dy) * bw + ax + xa;
            int si = base + xa * stepX + dy * stepY;
            for (int dx = xa; dx < xb; dx++, di++, si += stepX) {
                int p = sp[si];
                int a = p >>> 24;
                if (a == 255) buf[di] = p;
                else if (a != 0) buf[di] = blend(p, buf[di], a);
            }
        }
    }

    private static int blend(int s, int d, int a) {
        int da = d >>> 24;
        if (da != 255) { // PORT: destination is (partly) transparent (3D mode HUD layer): ARGB source-over
            if (da == 0) return (a << 24) | (s & 0xFFFFFF);
            int oa = a * 255 + da * (255 - a);             // result alpha * 255
            int sw = a * 255, dw = da * (255 - a);
            int r = (((s >> 16) & 255) * sw + ((d >> 16) & 255) * dw + oa / 2) / oa;
            int g = (((s >> 8) & 255) * sw + ((d >> 8) & 255) * dw + oa / 2) / oa;
            int b = ((s & 255) * sw + (d & 255) * dw + oa / 2) / oa;
            return ((oa + 127) / 255) << 24 | r << 16 | g << 8 | b;
        }
        int ia = 255 - a;
        int r = (((s >> 16) & 255) * a + ((d >> 16) & 255) * ia + 127) / 255;
        int g = (((s >> 8) & 255) * a + ((d >> 8) & 255) * ia + 127) / 255;
        int b = ((s & 255) * a + (d & 255) * ia + 127) / 255;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    public void drawRGB(int[] rgb, int offset, int scanlength, int x, int y, int w, int h, boolean processAlpha) {
        if (rgb == null) throw new NullPointerException();
        if (w == 0 || h == 0 || capturing) return;
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++) {
                int ax = x + tx + i, ay = y + ty + j;
                if (ax < x0 || ax >= x1 || ay < y0 || ay >= y1) continue;
                int p = rgb[offset + j * scanlength + i];
                if (!processAlpha) buf[ay * bw + ax] = p | 0xFF000000;
                else {
                    int a = p >>> 24;
                    if (a == 255) buf[ay * bw + ax] = p;
                    else if (a != 0) buf[ay * bw + ax] = blend(p, buf[ay * bw + ax], a);
                }
            }
    }

    public void copyArea(int xs, int ys, int w, int h, int xd, int yd, int anchor) {
        if (w <= 0 || h <= 0 || capturing) return;
        int[] tmp = new int[w * h];
        int sx = xs + tx, sy = ys + ty;
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++) {
                int px = sx + i, py = sy + j;
                tmp[j * w + i] = (px >= 0 && px < bw && py >= 0 && py < bh) ? buf[py * bw + px] : 0xFFFFFFFF;
            }
        int ax = xd + tx, ay = yd + ty;
        if ((anchor & HCENTER) != 0) ax -= w / 2;
        else if ((anchor & RIGHT) != 0) ax -= w;
        if ((anchor & VCENTER) != 0) ay -= h / 2;
        else if ((anchor & BOTTOM) != 0) ay -= h;
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++) plot(ax + i, ay + j, tmp[j * w + i]);
    }

    private void plot(int ax, int ay, int c) {
        if (ax >= x0 && ax < x1 && ay >= y0 && ay < y1) buf[ay * bw + ax] = c;
    }
}
