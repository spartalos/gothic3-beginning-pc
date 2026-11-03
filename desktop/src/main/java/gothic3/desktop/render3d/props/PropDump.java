package gothic3.desktop.render3d.props;

import gothic3.platform.scene3d.Scene3D;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import javax.microedition.lcdui.Image;

/** Dev aid (-Dg3.render3d.propdump=DIR): writes a crop of every distinct captured sprite (id, rect, pass) and a log line. */
public final class PropDump {
    public static PropDump create(File d) { return new PropDump(d); }
    private final File dir;
    private final Set<String> seen = new HashSet<>();

    PropDump(File dir) {
        this.dir = dir;
        dir.mkdirs();
    }

    private final Set<Integer> allDone = new HashSet<>();
    private int calls;

    /** With -Dg3.render3d.propdumpall=true: also writes every object image of the game (i<N>.png, whole sheet) once, to find lying sprites. */
    private void dumpAll() {
        if (++calls % 30 != 0 || !Boolean.getBoolean("g3.render3d.propdumpall")) return;
        try {
            Image[][] a = gothic3.port.GameHooks.bridge().images();
            if (a == null) return;
            for (int i = 0; i < a.length; i++) {
                if (a[i] == null || a[i][0] == null || !allDone.add(i)) continue;
                Image im = a[i][0];
                int w = im.getWidth(), h = im.getHeight();
                java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                bi.setRGB(0, 0, w, h, im.getPixelsInternal(), 0, w);
                javax.imageio.ImageIO.write(bi, "png", new File(dir, "all_i" + i + ".png"));
            }
        } catch (Exception e) {
            System.err.println("[props] dumpAll failed " + e);
        }
    }

    public void scan(Scene3D s, ImageIds ids) {
        dumpAll();
        for (int i = 0; i < s.count; i++) {
            int o = i * Scene3D.STRIDE;
            int head = s.d[o];
            if ((head & 255) != Scene3D.KIND_IMAGE || s.img[i] == null) continue;
            int pass = head >> 8;
            Image im = s.img[i];
            String id = ids.idOf(im);
            int xs = s.d[o + 1], ys = s.d[o + 2], w = s.d[o + 3], h = s.d[o + 4], tr = s.d[o + 5];
            String key = id + "@" + xs + "," + ys + "," + w + "," + h + (tr != 0 ? "t" + tr : "") + "_p" + pass;
            if (!seen.add(key)) continue;
            System.err.println("[props] " + key + " img " + im.getWidth() + "x" + im.getHeight());
            try {
                int[] px = im.getPixelsInternal();
                java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) bi.setRGB(x, y, px[(ys + y) * im.getWidth() + xs + x]);
                javax.imageio.ImageIO.write(bi, "png", new File(dir, key.replace(',', '_').replace('@', '_') + ".png"));
            } catch (Exception e) {
                System.err.println("[props] dump failed " + e);
            }
        }
    }
}
