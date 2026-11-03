package gothic3.desktop.render3d.autotag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Walks every map and accumulates {@link TileStats} per (layer, tileset, tile id). */
final class Collector {
    final Map<Long, TileStats> stats = new HashMap<>();
    final GameData data;

    Collector(GameData d) {
        data = d;
        for (GameData.MapInfo m : d.maps) if (m.id != 0) collect(m); // map 0 = 2D world map
    }

    static long key(int layer, int ts, int id) { return (long) layer << 24 | (long) ts << 8 | id; }

    TileStats get(int layer, int ts, int id) {
        return stats.computeIfAbsent(key(layer, ts, id), k -> {
            TileStats s = new TileStats();
            s.layer = layer; s.ts = ts; s.id = id;
            return s;
        });
    }

    private void touch(TileStats s, GameData.MapInfo m) {
        s.n++;
        if (s.lastMap != m.id) { s.maps++; s.lastMap = m.id; }
        if (!m.prefab()) s.fixedN++;
    }

    private void collect(GameData.MapInfo m) {
        int w = m.w, h = m.h;
        int[] c = m.cells;
        int[] colLen = new int[w * h];
        // columns: base (x, y) holds pieces from rows y-o with file tag == o (o ascending)
        int[][] chainO = new int[w * h][];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            List<Integer> os = new ArrayList<>();
            for (int o = 0; o < 7 && y - o >= 0; o++) {
                int cell = c[(y - o) * w + x];
                if ((cell >> 28 & 7) == o && (cell >> 16 & 0xFF) != 0xFF) os.add(o);
            }
            if (!os.isEmpty()) {
                chainO[y * w + x] = os.stream().mapToInt(i -> i).toArray();
                for (int o : chainO[y * w + x]) colLen[(y - o) * w + x] = os.size();
            }
        }
        boolean[] linked = new boolean[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int[] ch = chainO[y * w + x];
            if (ch == null) continue;
            for (int i = 0; i < ch.length; i++) {
                int o = ch[i], r = y - o;
                int cell = c[r * w + x];
                int id = cell >> 16 & 0xFF;
                TileStats s = get(1, m.ts1, id);
                touch(s, m);
                s.depth[o]++;
                if (i == 0) s.bottom++;
                if (i == ch.length - 1) s.top++;
                if (ch.length == 1) s.single++;
                s.posSum += i;
                s.lenSum += ch.length;
                if (walk(c[y * w + x])) s.onWalkBase++;
                if (walk(cell)) s.onWalkSelf++;
                if ((cell & 0xFF) == 0xFF) s.onNoGround++;
                if (i > 0) s.below.merge((c[(y - ch[i - 1]) * w + x] >> 16) & 0xFF, 1, Integer::sum);
                else s.below.merge(-1, 1, Integer::sum);
                if (i < ch.length - 1) s.above.merge((c[(y - ch[i + 1]) * w + x] >> 16) & 0xFF, 1, Integer::sum);
                else s.above.merge(-1, 1, Integer::sum);
                linked[r * w + x] = true;
            }
        }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int cell = c[y * w + x];
            int id = cell & 0xFF;
            int l1 = cell >> 16 & 0xFF;
            if (id != 0xFF) {
                TileStats s = get(0, m.ts0, id);
                touch(s, m);
                if (walk(cell)) s.walk++;
                s.xform[cell >> 8 & 7]++;
                if (l1 != 0xFF) { s.cover.merge(m.ts1 << 8 | l1, 1, Integer::sum); s.withL1++; if (linked[y * w + x]) s.withL1Col++; }
            }
            if (l1 != 0xFF && !linked[y * w + x]) get(1, m.ts1, l1).unlinked++;
        }
    }

    static boolean walk(int cell) { return (cell & 0x8000) != 0 && (cell & 0xFF) != 0xFF; }
}
