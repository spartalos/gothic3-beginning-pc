package gothic3.desktop.render3d.autotag;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.microedition.lcdui.Image;

/** Offline loader for the tile grids of all map entries of data file {@code d} and the tileset sheets (no game start needed). */
final class GameData {
    /** One map entry. cells use the in-game layout (layer 0 low 16 bits, layer 1 high 16 bits, file depth tags in bits 28-30). */
    static final class MapInfo {
        int id, w, h, ts0 = -1, ts1 = -1;
        int[] cells;
        boolean prefab() { return id >= 53; }
        int theme() { // GameWorld.readMapEntry
            int v = 0;
            if (id == 4 || id == 15) v = 1;
            if (id == 5 || id == 16) v = 0;
            if (id == 6 || id == 17) v = 2;
            if (id == 7 || id == 18) v = 3;
            if (id == 12 || id == 48) v = 4;
            if (id == 3 || id == 24) v = 0;
            return v;
        }
    }

    /** Tileset sheet with grid geometry. */
    static final class Sheet {
        int id, cols, rows, tw, th, w, h;
        int[] px;
        int[] tile(int tileId) {
            int sx = tileId % cols * tw, sy = tileId / cols % rows * th;
            int[] t = new int[tw * th];
            for (int y = 0; y < th; y++) System.arraycopy(px, (sy + y) * w + sx, t, y * tw, tw);
            return t;
        }
        int count() { return cols * rows; }
    }

    final List<MapInfo> maps = new ArrayList<>();
    private final short[] imageTable;

    GameData() throws Exception {
        try (DataInputStream in = new DataInputStream(open("/a"))) {
            imageTable = new short[in.readShort()];
            for (int i = 0; i < imageTable.length; i++) imageTable[i] = in.readShort();
        }
        gothic3.port.GameBridge game = gothic3.port.GameHooks.bridge();
        game.setImageTable(imageTable);
        game.initImageTable();
        try (DataInputStream in = new DataInputStream(open("/d"))) {
            for (int t = 0; t < 9; t++) {
                int size = in.readShort() & 0xFFFF;
                in.skipNBytes(size);
            }
            in.readShort();
            int n = in.readShort();
            for (int i = 0; i < n; i++) {
                int size = in.readShort() & 0xFFFF;
                byte[] b = new byte[size];
                in.readFully(b);
                maps.add(parse(i, new DataInputStream(new java.io.ByteArrayInputStream(b))));
            }
        }
    }

    private static InputStream open(String n) {
        InputStream s = GameData.class.getResourceAsStream(n);
        if (s == null) throw new IllegalStateException("resource missing: " + n);
        return s;
    }

    private static MapInfo parse(int id, DataInputStream in) throws Exception {
        MapInfo m = new MapInfo();
        m.id = id;
        m.w = in.readShort();
        m.h = in.readShort();
        int layers = in.readByte();
        m.cells = new int[m.w * m.h];
        for (int l = 0; l < layers; l++) {
            int w = in.readShort(), h = in.readShort();
            int ts = in.readByte();
            if (l == 0) m.ts0 = ts; else m.ts1 = ts;
            int comp = in.readByte();
            int p = 0, total = w * h;
            if (comp == 0) {
                for (; p < total; p++) put(m, l, p, in.readByte() & 0xFF | (in.readByte() & 0xFF) << 8);
            } else {
                while (p < total) {
                    int n = in.readByte();
                    if (n < 0) {
                        while (++n <= 0 && p < total) put(m, l, p++, 0xFFFF);
                    } else {
                        while (--n >= 0 && p < total) put(m, l, p++, in.readByte() & 0xFF | (in.readByte() & 0xFF) << 8);
                    }
                }
            }
        }
        return m;
    }

    private static void put(MapInfo m, int layer, int p, int v) {
        m.cells[p] |= v << 16 * layer;
    }

    /** Sheet of a tileset id for a palette theme, or null. */
    Sheet sheet(int ts, int theme) {
        if (ts < 0 || ts >= 273) return null;
        int pal = imageTable[ts * 11 + 4] == 5 ? theme : 0;
        if (!gothic3.port.GameHooks.bridge().ensureImageVariant(ts, pal)) return null;
        Image im = gothic3.port.GameHooks.bridge().images()[ts][pal];
        if (im == null) return null;
        Sheet s = new Sheet();
        s.id = ts;
        s.cols = imageTable[ts * 11 + 5];
        s.rows = imageTable[ts * 11 + 6];
        s.w = im.getWidth();
        s.h = im.getHeight();
        s.tw = s.w / s.cols;
        s.th = s.h / s.rows;
        s.px = im.getPixelsInternal().clone();
        return s;
    }
}
