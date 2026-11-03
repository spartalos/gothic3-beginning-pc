package gothic3.desktop.render3d.structures;

import gothic3.platform.scene3d.MapSnapshot;
import java.io.File;
import java.io.PrintWriter;

/** Debug aid (-Dg3.render3d.structdump=dir): ASCII grids of the layer-1 tiles (id / depth tag / base row) of the current map. */
public final class StructureDump {
    private StructureDump() {}

    public static void dump(MapSnapshot m, File dir) {
        try {
            dir.mkdirs();
            try (PrintWriter w = new PrintWriter(new File(dir, "grid_map" + m.mapId + ".txt"))) {
                w.println("# map " + m.mapId + " " + m.w + "x" + m.h + " ts " + m.tileset0 + "/" + m.tileset1 + "; cell = tile id(2 digits) + depth tag (own cell, bits 28-30), '.' empty");
                StringBuilder hdr = new StringBuilder("     ");
                for (int x = 0; x < m.w; x++) hdr.append(String.format("%-4d", x));
                w.println(hdr);
                for (int y = 0; y < m.h; y++) {
                    StringBuilder sb = new StringBuilder(String.format("%3d: ", y));
                    for (int x = 0; x < m.w; x++) {
                        int c = m.cells[y * m.w + x];
                        int t = (byte) (c >> 16 & 0xFF);
                        int d = c >> 28 & 7;
                        // depth tag of the tile stored here is "the offset to the next tile" in the chain; the tile's own tag is
                        // recovered through the chain heads below
                        sb.append(t < 0 ? " .  " : String.format("%2d  ", t));
                    }
                    w.println(sb);
                }
                w.println();
                w.println("# own depth tag (rows to the base row) of each layer-1 tile (via the base cell's chain), '.' none / not reached");
                int[] tag = new int[m.w * m.h];
                java.util.Arrays.fill(tag, -1);
                for (int y = 0; y < m.h; y++)
                    for (int x = 0; x < m.w; x++) {
                        int o = m.cells[y * m.w + x] >> 12 & 7;
                        for (int g = 0; o != 7 && g < 8 && y - o >= 0; g++) {
                            tag[(y - o) * m.w + x] = o;
                            o = m.cells[(y - o) * m.w + x] >> 28 & 7;
                        }
                    }
                w.println(hdr);
                for (int y = 0; y < m.h; y++) {
                    StringBuilder sb = new StringBuilder(String.format("%3d: ", y));
                    for (int x = 0; x < m.w; x++) sb.append(tag[y * m.w + x] < 0 ? ".   " : tag[y * m.w + x] + "   ");
                    w.println(sb);
                }
            }
            try (PrintWriter w = new PrintWriter(new File(dir, "grid0_map" + m.mapId + ".txt"))) {
                w.println("# map " + m.mapId + " layer 0: tile id + MIDP transform (id.t), '.' none");
                StringBuilder hdr = new StringBuilder("     ");
                for (int x = 0; x < m.w; x++) hdr.append(String.format("%-5d", x));
                w.println(hdr);
                for (int y = 0; y < m.h; y++) {
                    StringBuilder sb = new StringBuilder(String.format("%3d: ", y));
                    for (int x = 0; x < m.w; x++) {
                        int c = m.cells[y * m.w + x];
                        int t = (byte) (c & 0xFF);
                        sb.append(t < 0 ? ".    " : String.format("%2d.%d ", t, c >> 8 & 7));
                    }
                    w.println(sb);
                }
            }
            try (PrintWriter w = new PrintWriter(new File(dir, "tilestats_ts" + m.tileset1 + ".txt"))) {
                w.println("# id kind cov snow dark mat");
                for (int id = 0; id < m.cols1 * m.rows1 && id < 256; id++) {
                    TileInfo t = new TileInfo(m, id);
                    w.println(String.format("%3d %-6s cov %.2f snow %.2f dark %.2f mat %.2f", id, TileInfo.NAMES[t.kind], t.cov, t.snow, t.dark, t.mat));
                }
            }
            System.err.println("[render3d] structure dump written to " + dir);
        } catch (Exception e) {
            System.err.println("[render3d] structure dump failed: " + e);
        }
    }
}
