package gothic3.desktop.render3d.structures;

import java.util.ArrayList;
import java.util.List;
import javax.microedition.lcdui.Image;

/**
 * Texture atlas of the structure builder: images (ARGB) are registered while geometry is built, {@link #pack()} lays them out
 * (shelf packing, 1 px edge padding) and creates the atlas image; {@link Rect#u}/{@link Rect#v} then resolve local pixel
 * coordinates to atlas UVs.
 */
final class Atlas {
    static final int WIDTH = 2048;

    static final class Rect {
        final int[] px;
        final int w, h;
        int x, y;
        Rect(int[] px, int w, int h) { this.px = px; this.w = w; this.h = h; }
    }

    final List<Rect> rects = new ArrayList<>();
    int height;
    Image image;

    Rect add(int[] argb, int w, int h) {
        Rect r = new Rect(argb, w, h);
        rects.add(r);
        return r;
    }

    void pack() {
        // tallest first shelf packing
        List<Rect> order = new ArrayList<>(rects);
        order.sort((a, b) -> b.h - a.h);
        int x = 1, y = 1, shelf = 0;
        for (Rect r : order) {
            if (x + r.w + 1 > WIDTH) { x = 1; y += shelf + 2; shelf = 0; }
            r.x = x;
            r.y = y;
            x += r.w + 2;
            shelf = Math.max(shelf, r.h);
        }
        height = 1;
        while (height < y + shelf + 1) height <<= 1;
        int[] out = new int[WIDTH * height];
        for (Rect r : rects) {
            for (int j = -1; j <= r.h; j++) {
                int sj = Math.max(0, Math.min(r.h - 1, j));
                for (int i = -1; i <= r.w; i++) {
                    int si = Math.max(0, Math.min(r.w - 1, i));
                    out[(r.y + j) * WIDTH + r.x + i] = r.px[sj * r.w + si];
                }
            }
        }
        image = Image.createRGBImage(out, WIDTH, height, true);
    }

    float u(Rect r, float px) { return (r.x + px) / WIDTH; }

    float v(Rect r, float py) { return (r.y + py) / height; }
}
