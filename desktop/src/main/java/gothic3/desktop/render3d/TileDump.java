package gothic3.desktop.render3d;

import gothic3.platform.scene3d.MapSnapshot;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import javax.microedition.lcdui.Image;

/**
 * Tagging aid (-Dg3.render3d.dump=dir): writes the tileset sheets with tile ids drawn on them and a usage report
 * (layer, tile id, count, depth tags) of the current map, so tile ids in render3d/tiles.properties can be chosen.
 */
final class TileDump {
    private TileDump() {}

    static void dump(MapSnapshot m, File dir) {
        try {
            dir.mkdirs();
            TreeMap<String, int[]> use = new TreeMap<>();
            for (int y = 0; y < m.h; y++) {
                for (int x = 0; x < m.w; x++) {
                    int cell = m.cells[y * m.w + x];
                    int id = (byte) (cell & 0xFF);
                    if (id >= 0) use.computeIfAbsent("0 " + id, k -> new int[9])[8]++;
                    int o = cell >> 12 & 7;
                    for (int g = 0; o != 7 && g < 8 && y - o >= 0; g++) {
                        int rc = m.cells[(y - o) * m.w + x];
                        int tid = (byte) (rc >> 16 & 0xFF);
                        if (tid >= 0) {
                            int[] u = use.computeIfAbsent("1 " + tid, k -> new int[9]);
                            u[o]++;
                            u[8]++;
                        }
                        o = rc >> 28 & 7;
                    }
                }
            }
            try (PrintWriter w = new PrintWriter(new File(dir, "tiles_map" + m.version + "_ts" + m.tileset0 + "_" + m.tileset1 + ".txt"))) {
                w.println("# layer tile count | count per depth tag 0..7 (layer 1)");
                for (var e : use.entrySet()) {
                    int[] u = e.getValue();
                    w.println(e.getKey() + " " + u[8] + " | " + java.util.Arrays.toString(java.util.Arrays.copyOf(u, 8)));
                }
            }
            sheet(m.sheet0, m.cols0, m.rows0, m, new File(dir, "sheet_ts" + m.tileset0 + "_layer0.png"));
            sheet(m.sheet1, m.cols1, m.rows1, m, new File(dir, "sheet_ts" + m.tileset1 + "_layer1.png"));
            System.err.println("[render3d] tile dump written to " + dir);
        } catch (Exception e) {
            System.err.println("[render3d] tile dump failed: " + e);
        }
    }

    private static void sheet(Image im, int cols, int rows, MapSnapshot m, File f) throws Exception {
        if (im == null) return;
        int S = 3, w = im.getWidth(), h = im.getHeight();
        BufferedImage bi = new BufferedImage(w * S, h * S, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(new Color(60, 90, 60));
        g.fillRect(0, 0, w * S, h * S);
        BufferedImage src = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        src.setRGB(0, 0, w, h, im.getPixelsInternal(), 0, w);
        g.drawImage(src, 0, 0, w * S, h * S, null);
        g.setFont(g.getFont().deriveFont(10f));
        for (int id = 0; id < cols * rows && id < 256; id++) {
            int x = id % cols * m.tileW * S, y = id / cols * m.tileH * S;
            g.setColor(new Color(255, 255, 255, 90));
            g.drawRect(x, y, m.tileW * S, m.tileH * S);
            g.setColor(Color.BLACK);
            g.drawString(String.valueOf(id), x + 3, y + 12);
            g.setColor(Color.YELLOW);
            g.drawString(String.valueOf(id), x + 2, y + 11);
        }
        g.dispose();
        ImageIO.write(bi, "png", f);
    }
}
