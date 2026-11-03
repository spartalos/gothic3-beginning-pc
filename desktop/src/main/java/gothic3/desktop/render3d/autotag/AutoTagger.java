package gothic3.desktop.render3d.autotag;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Generates render3d/tiles-auto.properties: a 3D shape per tile id per tileset, derived from usage statistics over all maps of
 * data file {@code d} (walkable bit, depth-tag columns, neighbours) and the tile pixels. See docs/render3d.md "Auto tagger".
 *
 * <pre>
 * ./gradlew :desktop:autotag                       writes game/src/main/resources/render3d/tiles-auto.properties
 * ./gradlew :desktop:autotag --args="--report"     also prints the agreement with the hand tags and a per-tile table
 * </pre>
 * Options: {@code --out FILE}, {@code --hand FILE}, {@code --merge FILE} (hand over auto, for testing via render3d.tiles),
 * {@code --report}, {@code --verbose}.
 */
public final class AutoTagger {
    static final float SLOPE_DEG = 55f;

    /** One decision. */
    static final class Tag {
        int layer, ts, id;
        String shape;      // table syntax
        float conf;
        String reason;
        String fix;        // visual override flag
    }

    final GameData data;
    final Collector col;
    final Map<Long, Features> feat = new HashMap<>();
    final List<Tag> tags = new ArrayList<>();
    final Map<Integer, Integer> voidTiles = new TreeMap<>();
    final Map<Integer, String> l1Shape = new HashMap<>();

    AutoTagger() throws Exception {
        data = new GameData();
        col = new Collector(data);
        attachImages();
        decide();
    }

    private void attachImages() {
        Map<Integer, GameData.Sheet> sheets = new HashMap<>();
        for (TileStats s : col.stats.values()) {
            GameData.Sheet sh = sheets.computeIfAbsent(s.ts, t -> data.sheet(t, 0));
            if (sh == null || s.id >= sh.count()) continue;
            s.img = Features.of(sh.tile(s.id), sh.tw, sh.th);
        }
    }

    // ------------------------------------------------------------------------------------------ rules

    private void decide() {
        // most common clean walkable ground tile per layer-0 tileset (substitute for black "inside of a building" cells)
        Map<Integer, TileStats> ground = new HashMap<>();
        for (TileStats s : col.stats.values()) {
            if (s.layer != 0 || s.img == null || s.walk < 0.9f * s.n || s.img.dark > 0.1f || s.img.coverage < 0.99f) continue;
            TileStats g = ground.get(s.ts);
            if (g == null || s.fixedN > g.fixedN) ground.put(s.ts, s);
        }
        List<TileStats> all = new ArrayList<>(col.stats.values());
        all.sort((a, b) -> a.layer != b.layer ? b.layer - a.layer : a.ts != b.ts ? a.ts - b.ts : a.id - b.id); // layer 1 first (roof tiles are needed for layer 0)
        for (TileStats s : all) {
            if (s.img == null || (s.n == 0)) continue;
            Tag t = new Tag();
            t.layer = s.layer; t.ts = s.ts; t.id = s.id;
            if (s.img.coverage == 0) { t.shape = "hidden"; t.conf = 0.99f; t.reason = "fully-transparent"; }
            else if (s.layer == 0) layer0(s, t, ground.get(s.ts));
            else layer1(s, t);
            tags.add(t);
            if (t.layer == 1) l1Shape.put(t.ts << 8 | t.id, t.reason.startsWith("void-cap") ? "cap" : t.shape);
        }
        tags.sort((a, b) -> a.ts != b.ts ? a.ts - b.ts : a.layer != b.layer ? a.layer - b.layer : a.id - b.id);
        // void fill: layer-0 empty cells hidden under layer-1 roofs (as the hand table does for the village set)
        Map<Integer, int[]> voids = new HashMap<>();
        for (GameData.MapInfo m : data.maps) {
            if (m.prefab()) continue;
            int[] v = voids.computeIfAbsent(m.ts0, k -> new int[3]);
            for (int i = 0; i < m.cells.length; i++) {
                v[2]++;
                if ((m.cells[i] & 0xFF) == 0xFF) { v[0]++; if (roof(m, i)) v[1]++; }
            }
        }
        for (var e : voids.entrySet()) {
            int[] v = e.getValue();
            TileStats g = ground.get(e.getKey());
            if (g != null && v[0] > 0.01 * v[2] && v[1] >= 0.3 * v[0]) voidTiles.put(e.getKey(), g.id);
        }
    }

    /** Cells of this layer-0 tile that sit under a layer-1 tile of a tileset with roofs (village: building interiors under roofs). */
    private int roofCover(TileStats s) {
        int c = 0;
        for (var e : s.cover.entrySet()) if (covers(e.getKey())) c += e.getValue();
        return c;
    }

    /** A layer-1 piece (ts << 8 | id) that hides black layer-0 cells: not a black void cap, in a tileset that has roofs. */
    private boolean covers(int key) {
        String sh = l1Shape.get(key);
        if (sh == null || sh.equals("cap")) return false;
        for (var e : l1Shape.entrySet()) if (e.getKey() >> 8 == key >> 8 && AutoTagger.kind(e.getValue()).equals("slope")) return true;
        return false;
    }

    private boolean roof(GameData.MapInfo m, int i) {
        int l1 = m.cells[i] >> 16 & 0xFF;
        return l1 != 0xFF && covers(m.ts1 << 8 | l1);
    }

    private void layer0(TileStats s, Tag t, TileStats ground) {
        Features f = s.img;
        float walk = s.walk / (float) s.n, cov = roofCover(s) / (float) s.n;
        if (System.getProperty("autotag.debug") != null && f.dark > 0.5f) System.err.println("dark L0 ts" + s.ts + " #" + s.id + " cover=" + s.cover + " roof=" + cov);
        if (f.dark > 0.6f && cov > 0.3f && ground != null && ground.ts == s.ts && ground.id != s.id) {
            t.shape = "floor:" + ground.id;
            t.conf = 0.8f;
            t.reason = String.format(Locale.ROOT, "dark=%.2f under-roof=%.2f", f.dark, cov);
            return;
        }
        t.shape = "floor";
        t.conf = walk > 0.5f ? 0.6f + 0.4f * walk : 0.5f;
        t.reason = String.format(Locale.ROOT, "walk=%.2f", walk);
    }

    private void layer1(TileStats s, Tag t) {
        Features f = s.img;
        float n = s.n;
        if (n == 0) return;
        float meanD = 0;
        for (int d = 0; d < 8; d++) meanD += d * s.depth[d];
        meanD /= n;
        float d0 = s.depth[0] / n, top = s.top / n, single = s.single / n, wSelf = s.onWalkSelf / n, wBase = s.onWalkBase / n;
        String stat = String.format(Locale.ROOT, "n=%d d=%.1f top=%.2f", s.n, meanD, top);
        // cross: ragged cut-out sprite (plants), lying on the ground
        if (f.coverage < 0.7f && f.ragged > 0.35f && f.holes > 0.1f && f.sat > 25 && f.snow < 0.2f && meanD < 1.5f) {
            t.shape = "cross"; t.conf = 0.6f; t.reason = String.format(Locale.ROOT, "ragged=%.2f cover=%.2f %s", f.ragged, f.coverage, stat);
            return;
        }
        // flat decal: lies on walkable ground, never part of a tall column
        if (d0 >= 0.8f && single >= 0.5f && wSelf >= 0.8f && wBase >= 0.8f) {
            t.shape = "flat"; t.conf = 0.5f + 0.3f * single; t.reason = String.format(Locale.ROOT, "walkable-single %s", stat);
            return;
        }
        // roof: smooth light surface (snow) or gable cut-out, sitting high in columns
        boolean snowy = f.snow >= 0.5f && f.smooth < 40;
        boolean voidCap = f.dark >= 0.5f; // black filler above walls: stays upright
        boolean gable = !voidCap && f.cornerHole >= 0.12f && f.cornerHole <= 0.55f && f.coverage >= 0.4f && f.coverage < 0.9f && f.smooth < 20;
        if ((snowy || gable) && (meanD >= 1.5f || (snowy && top >= 0.9f && s.n >= 20))) {
            float c = 0.55f + 0.1f * Math.min(3f, meanD - 1.5f) + 0.1f * top;
            t.shape = "slope:" + (int) SLOPE_DEG; t.conf = Math.min(0.95f, c);
            t.reason = String.format(Locale.ROOT, "%s snow=%.2f hole=%.2f smooth=%.0f %s", snowy ? "smooth-light" : "gable", f.snow, f.cornerHole, f.smooth, stat);
            return;
        }
        t.shape = "upright";
        if (voidCap) { t.conf = 0.7f; t.reason = String.format(Locale.ROOT, "void-cap dark=%.2f %s", f.dark, stat); return; }
        t.conf = 0.6f + 0.2f * Math.min(1f, (1f - top) + (meanD < 1.5f ? 0.5f : 0f));
        t.reason = String.format(Locale.ROOT, "wall snow=%.2f smooth=%.0f %s", f.snow, f.smooth, stat);
    }

    // ------------------------------------------------------------------------------------------ hand table

    /** Minimal reader of tiles.properties: key "tsN.layerL.a-b" -> shape text. Per tile: [layer][ts] -> id -> shape. */
    static Map<String, String> readTable(File f) throws Exception {
        Map<String, String> m = new HashMap<>();
        if (f == null || !f.isFile()) return m;
        for (String raw : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) continue;
            String k = t.substring(0, t.indexOf('=')).trim(), v = t.substring(t.indexOf('=') + 1);
            if (v.contains("#")) v = v.substring(0, v.indexOf('#'));
            v = v.trim();
            if (!k.startsWith("ts")) continue;
            String[] a = k.split("\\.");
            if (a.length < 2 || !a[1].startsWith("layer")) continue;
            int ts = Integer.parseInt(a[0].substring(2)), layer = a[1].charAt(5) - '0';
            String r = a[2];
            int lo, hi;
            if (r.contains("-")) { lo = Integer.parseInt(r.substring(0, r.indexOf('-'))); hi = Integer.parseInt(r.substring(r.indexOf('-') + 1)); }
            else lo = hi = Integer.parseInt(r);
            for (int i = lo; i <= hi; i++) m.put(layer + "/" + ts + "/" + i, v);
        }
        return m;
    }

    static String kind(String shape) { int c = shape.indexOf(':'); return c < 0 ? shape : shape.substring(0, c); }

    // ------------------------------------------------------------------------------------------ output

    void write(File out, Map<String, String> hand) throws Exception {
        out.getParentFile().mkdirs();
        try (PrintWriter w = new PrintWriter(out, "UTF-8")) {
            w.println("# GENERATED by gothic3.desktop.render3d.autotag.AutoTagger - do not edit, regenerate with ./gradlew :desktop:autotag");
            w.println("# Automatic tile -> 3D shape tags (docs/render3d.md, section Auto tagger). Loaded UNDER tiles.properties: hand tags win.");
            w.println("# Every line: tsN.layerL.<id> = shape   # conf=<0..1> reason=<why>   ('visual' = fixed by looking at screenshots)");
            w.println("# Statistics come from all 53 maps + 91 prefab rooms of data file d (walkable bit, layer-1 depth tag columns, tile pixels).");
            w.println();
            int lastTs = -2, lastLayer = -2;
            for (Tag t : tags) {
                if (hand.containsKey(t.layer + "/" + t.ts + "/" + t.id) && false) continue;
                if (t.ts != lastTs || t.layer != lastLayer) {
                    w.println();
                    w.println("# --- tileset " + t.ts + " layer " + t.layer);
                    lastTs = t.ts; lastLayer = t.layer;
                }
                w.println("ts" + t.ts + ".layer" + t.layer + "." + t.id + " = " + t.shape + "   # conf=" + String.format(Locale.ROOT, "%.2f", t.conf) + " reason=" + t.reason);
            }
            w.println();
            for (var e : voidTiles.entrySet())
                w.println("ts" + e.getKey() + ".void = " + e.getValue() + "   # conf=0.70 reason=layer-0 holes are covered by layer-1 roofs");
        }
    }

    public static void main(String[] args) throws Exception {
        File out = new File("game/src/main/resources/render3d/tiles-auto.properties");
        File handF = new File("game/src/main/resources/render3d/tiles.properties");
        File merge = null;
        boolean report = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out": out = new File(args[++i]); break;
                case "--hand": handF = new File(args[++i]); break;
                case "--merge": merge = new File(args[++i]); break;
                case "--report": report = true; break;
                default: throw new IllegalArgumentException(args[i]);
            }
        }
        AutoTagger a = new AutoTagger();
        Map<String, String> hand = readTable(handF);
        a.write(out, hand);
        System.out.println("wrote " + out + " (" + a.tags.size() + " tiles)");
        if (merge != null) {
            merge.getParentFile().mkdirs();
            List<String> lines = new ArrayList<>(Files.readAllLines(out.toPath(), StandardCharsets.UTF_8));
            lines.add("");
            lines.add("# ---- hand tags");
            lines.addAll(Files.readAllLines(handF.toPath(), StandardCharsets.UTF_8));
            Files.write(merge.toPath(), lines, StandardCharsets.UTF_8);
            System.out.println("merged -> " + merge);
        }
        if (report) Report.print(a, hand);
    }
}
