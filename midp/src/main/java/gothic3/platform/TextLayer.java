package gothic3.platform;

import javax.microedition.lcdui.Font;

/**
 * PORT: "HD text". Text drawn onto the screen buffer is not rasterised at the game's logical resolution; it is recorded as
 * {@link Op}s that travel with the published frame (see {@link FrameBuffer}) and are drawn by the presenter at window resolution
 * (docs/platform.md "HD text"). The layer persists with the screen buffer: an op lives until a later draw overlaps it, at which point
 * {@link javax.microedition.lcdui.Graphics} rasterises it into the buffer (old look, correct layering). Game thread only.
 */
public final class TextLayer {
    /** Master switch (F10 "HD text"): new text on the screen buffer is recorded instead of rasterised. */
    public static volatile boolean enabled = true;

    /** One recorded string. Immutable. Coordinates are absolute buffer pixels: (x, y) = top-left of the text line. */
    public static final class Op {
        public final String text;
        public final Font font;
        public final int rgb;                  // 0xRRGGBB
        public final int x, y;
        public final int cx0, cy0, cx1, cy1;   // effective clip [cx0,cx1) x [cy0,cy1)
        public final int bx0, by0, bx1, by1;   // conservative bounds of the drawn pixels (already intersected with the clip)

        public Op(String text, Font font, int rgb, int x, int y, int cx0, int cy0, int cx1, int cy1) {
            this.text = text;
            this.font = font;
            this.rgb = rgb & 0xFFFFFF;
            this.x = x;
            this.y = y;
            this.cx0 = cx0;
            this.cy0 = cy0;
            this.cx1 = cx1;
            this.cy1 = cy1;
            this.bx0 = Math.max(cx0, x - 3);
            this.by0 = Math.max(cy0, y - 3);
            this.bx1 = Math.min(cx1, x + font.stringWidth(text) + 3);
            this.by1 = Math.min(cy1, y + font.getHeight() + 3);
        }

        public boolean empty() { return bx0 >= bx1 || by0 >= by1; }

        public boolean intersects(int ax0, int ay0, int ax1, int ay1) {
            return bx0 < ax1 && ax0 < bx1 && by0 < ay1 && ay0 < by1;
        }
    }

    public static final Op[] NONE = new Op[0];

    private Op[] ops = new Op[64];
    private int n;

    public int size() { return n; }
    public Op get(int i) { return ops[i]; }

    public void add(Op o) {
        if (n == ops.length) ops = java.util.Arrays.copyOf(ops, n * 2);
        ops[n++] = o;
    }

    public void clear() {
        java.util.Arrays.fill(ops, 0, n, null);
        n = 0;
    }

    /** Removes the ops flagged in {@code drop} (indices 0..n-1), keeping the order of the rest. */
    public void remove(boolean[] drop) {
        int j = 0;
        for (int i = 0; i < n; i++) if (!drop[i]) ops[j++] = ops[i];
        java.util.Arrays.fill(ops, j, n, null);
        n = j;
    }

    /** Immutable copy for the frame being published. */
    public Op[] snapshot() { return n == 0 ? NONE : java.util.Arrays.copyOf(ops, n); }
}
