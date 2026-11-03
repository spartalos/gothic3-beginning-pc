package gothic3.desktop.render3d;

import gothic3.desktop.Config;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tile id -> 3D shape table (see docs/render3d.md). Loaded from a properties-like file so it can be hand tuned and
 * reloaded at runtime (F6) or edited in game (F7 tile editor). Keys, one per line, later lines win:
 * <pre>
 *   default.layer0 = floor            default.layer1 = upright
 *   layer0.17 = floor                 tile 17 of layer 0, any tileset
 *   layer1.40-47 = block:0.5          ranges
 *   ts12.layer1.40 = hidden           only when the layer's tileset id is 12
 *   layer1.40.d0 = flat               only for layer-1 tiles with depth tag (rows above the base row) 0
 *   ts0.void = 7                      layer-0 tile used for cells that have none (2D shows black, hidden under roofs)
 *   map5.layer1.40-47 = hidden        per-map override: only on map (area) 5, any tileset, same range / depth syntax
 *   map5.cell1.12.30 = slope:55       per-map cell override: layer 1 tile stored in cell x=12, y=30 of map 5
 * </pre>
 * Precedence: cell override, per-map tile override, table (tileset specific before any tileset, depth specific before
 * any depth), default.
 * Values: {@code floor} (flat on the ground), {@code flat} (decal on the ground, alpha blended, layer 1),
 * {@code upright} (standing quad on the south edge of the column's base row), {@code slope[:deg]} (quad leaning back
 * by deg from vertical, default 45, continuing the piece below: roofs), {@code cross} (two crossing
 * quads), {@code block[:depth]} (upright quad plus top cap and sides, depth in tiles), {@code hidden}, layer 0 only: {@code steps[:n]} (staircase going down towards the viewer),
 * {@code deck[:h]} (the tile's art as a raised platform top at height h with solid front / side faces, default 0.75),
 * {@code stairs[:n[:rise]]} (n treads rising towards the north up to a deck of height rise: the stairs in front of a platform).
 * {@code flight[:n[:rise]]} (a multi-cell staircase, e.g. through a wall gap: n treads per cell, every cell of a straight run rises by `rise` more than the one
 * south of it, so the run climbs to the north; vertical flip = climbs to the south).
 * A {@code # comment} may follow a value.
 *
 * <p>Loading: an explicit file (config {@code render3d.tiles}, or the source checkout's resource file) is used with its sibling
 * {@code tiles-auto.properties} underneath it; otherwise
 * the built-in classpath resource is loaded and {@code ~/.config/gothic3-pc/tiles.properties} (if present) on top of it.
 */
public final class TileTable {
    /** Default height (tiles) of a raised wooden platform ({@code deck}) and of the stairs leading up to it ({@code stairs}). */
    public static final float DECK_H = 0.75f;

    /** Default rise (tiles) per cell of a {@code flight} of stairs. */
    public static final float FLIGHT_RISE = 0.3f;

    public enum Kind { FLOOR, FLAT, UPRIGHT, SLOPE, CROSS, BLOCK, HIDDEN, STEPS, DECK, STAIRS, FLIGHT }

    public static final class Shape {
        public final Kind kind;
        public final float param;
        /** Second parameter: {@code stairs:n:rise} = rise in tiles (default {@link #DECK_H}); unused by the other kinds. */
        public final float param2;
        public Shape(Kind k, float p) { this(k, p, k == Kind.STAIRS ? DECK_H : k == Kind.FLIGHT ? FLIGHT_RISE : 0f); }
        public Shape(Kind k, float p, float p2) { kind = k; param = p; param2 = p2; }

        @Override public boolean equals(Object o) {
            return o instanceof Shape && ((Shape) o).kind == kind && ((Shape) o).param == param && ((Shape) o).param2 == param2;
        }
        @Override public int hashCode() { return (kind.hashCode() * 31 + Float.hashCode(param)) * 31 + Float.hashCode(param2); }

        /** Table syntax: {@code slope:55}, {@code block:0.5}, {@code floor:7}, {@code hidden}. */
        @Override public String toString() {
            String n = kind.name().toLowerCase();
            boolean hasParam = kind == Kind.SLOPE || kind == Kind.BLOCK || kind == Kind.STEPS || kind == Kind.DECK || kind == Kind.STAIRS || kind == Kind.FLIGHT || (kind == Kind.FLOOR && param > 0);
            if (!hasParam) return n;
            String r = n + ":" + (kind == Kind.FLOOR && param == Float.MIN_VALUE ? "0" : fmt(param)); // floor:0 sentinel, see parse
            return kind == Kind.STAIRS || kind == Kind.FLIGHT ? r + ":" + fmt(param2) : r;
        }

        private static String fmt(float f) { return f == Math.rint(f) ? String.valueOf((int) f) : String.valueOf(f); }

        public static Shape parse(String v) {
            String[] a = v.trim().split(":");
            Kind k = Kind.valueOf(a[0].trim().toUpperCase());
            float prm = a.length > 1 ? Float.parseFloat(a[1]) : (k == Kind.STEPS || k == Kind.STAIRS ? 4f : k == Kind.FLIGHT ? 3f : k == Kind.BLOCK ? 0.5f : k == Kind.SLOPE ? 45f : k == Kind.DECK ? DECK_H : 0f);
            if (k == Kind.FLOOR && a.length > 1 && prm == 0f) prm = Float.MIN_VALUE; // "floor:0" = show tile 0 (param 0 means "own art")
            return k == Kind.STAIRS || k == Kind.FLIGHT ? new Shape(k, prm, a.length > 2 ? Float.parseFloat(a[2]) : k == Kind.FLIGHT ? FLIGHT_RISE : DECK_H) : new Shape(k, prm);
        }
    }

    /** The left side of a rule line. */
    public static final class Spec {
        public enum Type { TILE, VOID, DEFAULT, CELL }
        public Type type = Type.TILE;
        /** -1 = every map. */
        public int mapId = -1;
        public int layer;
        /** -1 = any tileset (for map rules always -1: the map's own tilesets). */
        public int tileset = -1;
        public int lo, hi;
        /** -1 = any depth tag. */
        public int depth = -1;
        public int cx, cy;

        public static Spec parse(String key) {
            Spec s = new Spec();
            key = key.trim();
            if (key.startsWith("default.layer")) {
                s.type = Type.DEFAULT;
                s.layer = key.charAt("default.layer".length()) - '0';
                return s;
            }
            if (key.endsWith("void")) { // "void" / "ts0.void"
                s.type = Type.VOID;
                if (key.startsWith("ts")) s.tileset = Integer.parseInt(key.substring(2, key.indexOf('.')));
                return s;
            }
            if (key.startsWith("map")) {
                int dot = key.indexOf('.');
                s.mapId = Integer.parseInt(key.substring(3, dot));
                key = key.substring(dot + 1);
            } else if (key.startsWith("ts")) {
                int dot = key.indexOf('.');
                s.tileset = Integer.parseInt(key.substring(2, dot));
                key = key.substring(dot + 1);
            }
            String[] a = key.split("\\.");
            if (a[0].startsWith("cell")) {
                if (s.mapId < 0 || a.length != 3) throw new IllegalArgumentException("expected mapM.cellL.X.Y");
                s.type = Type.CELL;
                s.layer = a[0].charAt(4) - '0';
                s.cx = Integer.parseInt(a[1]);
                s.cy = Integer.parseInt(a[2]);
                return s;
            }
            if (!a[0].startsWith("layer")) throw new IllegalArgumentException("expected layerN");
            s.layer = a[0].charAt(5) - '0';
            if (s.layer < 0 || s.layer > 1) throw new IllegalArgumentException("layer must be 0 or 1");
            if (a.length > 2) s.depth = Integer.parseInt(a[2].substring(1));
            String r = a[1];
            int dash = r.indexOf('-');
            if (dash > 0) { s.lo = Integer.parseInt(r.substring(0, dash)); s.hi = Integer.parseInt(r.substring(dash + 1)); }
            else s.lo = s.hi = Integer.parseInt(r);
            return s;
        }

        /** Canonical key text. */
        public String format() {
            switch (type) {
                case DEFAULT: return "default.layer" + layer;
                case VOID: return tileset >= 0 ? "ts" + tileset + ".void" : "void";
                case CELL: return "map" + mapId + ".cell" + layer + "." + cx + "." + cy;
                default:
            }
            String p = mapId >= 0 ? "map" + mapId + "." : tileset >= 0 ? "ts" + tileset + "." : "";
            return p + "layer" + layer + "." + (lo == hi ? String.valueOf(lo) : lo + "-" + hi) + (depth >= 0 ? ".d" + depth : "");
        }

        /** Rules of one section (written together in the file): the map for map rules, else the tileset. */
        public boolean sameSection(Spec o) { return mapId == o.mapId && (mapId >= 0 || tileset == o.tileset); }

        /** Same scope except the tile range: these rules overlap when the ranges do. */
        public boolean sameScope(Spec o) {
            return type == o.type && mapId == o.mapId && tileset == o.tileset && layer == o.layer && depth == o.depth;
        }
    }

    /** One parsed line of a table file. */
    public static final class Rule {
        public Spec spec;
        public Shape shape;      // null for void rules
        public int voidTile;
        public String trailing = ""; // whitespace + comment after the value

        /** null for blank / comment / unparseable lines. */
        public static Rule parse(String raw) {
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) return null;
            int eq = t.indexOf('=');
            if (eq < 0) return null;
            Rule r = new Rule();
            try {
                r.spec = Spec.parse(t.substring(0, eq));
                String v = t.substring(eq + 1);
                int c = v.indexOf('#');
                String tr = "";
                if (c >= 0) {
                    int s = c;
                    while (s > 0 && Character.isWhitespace(v.charAt(s - 1))) s--;
                    tr = v.substring(s);
                    v = v.substring(0, s);
                }
                v = v.trim();
                r.trailing = tr;
                if (r.spec.type == Spec.Type.VOID) r.voidTile = Integer.parseInt(v);
                else r.shape = Shape.parse(v);
            } catch (RuntimeException e) {
                return null;
            }
            return r;
        }

        public String format() {
            return spec.format() + " = " + (spec.type == Spec.Type.VOID ? String.valueOf(voidTile) : shape.toString()) + trailing;
        }
    }

    /** How a shape was resolved. */
    public enum Level { DEFAULT, TABLE, MAP_TILE, CELL }

    public static final class Resolved {
        public Shape shape;
        public Level level = Level.DEFAULT;
        /** Rule text that provides the shape ("ts3.layer1.23-34", "map5.cell1.12.30"), "default.layer1" for defaults. */
        public String source = "";
        /** Depth tag of the matched key, -1 = any depth. */
        public int keyDepth = -1;
        /** Tileset of the matched key, -1 = any tileset. */
        public int keyTileset = -1;
    }

    private static final Shape[] DEFAULT = {new Shape(Kind.FLOOR, 0), new Shape(Kind.UPRIGHT, 0)};

    private final Shape[] defaults = DEFAULT.clone();
    private final Map<Long, Shape> map = new HashMap<>();
    private final Map<Long, String> origin = new HashMap<>();
    private final Map<Long, Shape> mapTile = new HashMap<>();
    private final Map<Long, Shape> cells = new HashMap<>();
    private final Map<Integer, Integer> voidTile = new HashMap<>();
    private String source = "(defaults)";
    private File saveTarget;
    private boolean saveTargetIsUserFile;
    private int version;

    public String source() { return source; }

    /** Incremented by every edit ({@code set*}); the renderer / pick geometry rebuild when it changes. */
    public int version() { return version; }

    /** File that {@link gothic3.desktop.render3d.editor.TableFile} writes to (source file or the user override file). */
    public File saveTarget() { return saveTarget; }

    /** True if saves go to {@code ~/.config/gothic3-pc/tiles.properties} (built-in table came from the classpath). */
    public boolean saveTargetIsUserFile() { return saveTargetIsUserFile; }

    public static File userFile() { return new File(Config.configDir(), "tiles.properties"); }

    /** Layer-0 tile shown in cells that have no layer-0 tile (2D draws black there, usually hidden by a roof), or -1. */
    public int voidTile(int tileset) {
        Integer t = voidTile.get(tileset);
        if (t == null) t = voidTile.get(-1);
        return t == null ? -1 : t;
    }

    /** key: layer(1 bit) | tileset+1 (16 bits, 0 = any) | tile (8 bits) | depth+1 (4 bits, 0 = any) */
    private static long key(int layer, int tileset, int tile, int depth) {
        return ((long) layer << 40) | ((long) (tileset + 1) << 20) | ((long) tile << 8) | (depth + 1);
    }

    private static long mapKey(int mapId, int layer, int tile, int depth) {
        return ((long) (mapId + 1) << 32) | ((long) layer << 20) | ((long) tile << 8) | (depth + 1);
    }

    private static long cellKey(int mapId, int layer, int x, int y) {
        return ((long) (mapId + 1) << 40) | ((long) layer << 36) | ((long) x << 16) | y;
    }

    public Shape get(int layer, int tileset, int tile, int depth) {
        Shape s = map.get(key(layer, tileset, tile, depth));
        if (s == null) s = map.get(key(layer, -1, tile, depth));
        if (s == null && depth >= 0) s = map.get(key(layer, tileset, tile, -1));
        if (s == null && depth >= 0) s = map.get(key(layer, -1, tile, -1));
        return s != null ? s : defaults[layer];
    }

    /** Like {@link #get(int, int, int, int)} with the per-map overrides of map {@code mapId} (tile at cell x, y). */
    public Shape get(int mapId, int layer, int tileset, int tile, int depth, int x, int y) {
        if (!cells.isEmpty()) {
            Shape s = cells.get(cellKey(mapId, layer, x, y));
            if (s != null) return s;
        }
        if (!mapTile.isEmpty()) {
            Shape s = mapTile.get(mapKey(mapId, layer, tile, depth));
            if (s == null && depth >= 0) s = mapTile.get(mapKey(mapId, layer, tile, -1));
            if (s != null) return s;
        }
        return get(layer, tileset, tile, depth);
    }

    /** Full resolution including where the shape comes from (for the tile editor). */
    public Resolved resolve(int mapId, int layer, int tileset, int tile, int depth, int x, int y) {
        Resolved r = new Resolved();
        Shape s;
        if (mapId >= 0 && (s = cells.get(cellKey(mapId, layer, x, y))) != null) {
            r.shape = s;
            r.level = Level.CELL;
            r.source = "map" + mapId + ".cell" + layer + "." + x + "." + y;
            return r;
        }
        if (mapId >= 0) {
            for (int d : depth >= 0 ? new int[] {depth, -1} : new int[] {-1}) {
                if ((s = mapTile.get(mapKey(mapId, layer, tile, d))) != null) {
                    r.shape = s;
                    r.level = Level.MAP_TILE;
                    r.keyDepth = d;
                    r.source = "map" + mapId + ".layer" + layer + "." + tile + (d >= 0 ? ".d" + d : "");
                    return r;
                }
            }
        }
        int[][] order = {{tileset, depth}, {-1, depth}, {tileset, -1}, {-1, -1}};
        for (int i = 0; i < order.length; i++) {
            if (i >= 2 && depth < 0) break;
            long k = key(layer, order[i][0], tile, order[i][1]);
            if ((s = map.get(k)) != null) {
                r.shape = s;
                r.level = Level.TABLE;
                r.keyTileset = order[i][0];
                r.keyDepth = order[i][1];
                r.source = origin.getOrDefault(k, "table");
                return r;
            }
        }
        r.shape = defaults[layer];
        r.source = "default.layer" + layer;
        return r;
    }

    // ------------------------------------------------------------------------------------------ edit API

    /** Exact entry of the tileset table (no fallbacks), or null. */
    public Shape tileEntry(int layer, int tileset, int tile, int depth) { return map.get(key(layer, tileset, tile, depth)); }

    public Shape mapTileEntry(int mapId, int layer, int tile, int depth) { return mapTile.get(mapKey(mapId, layer, tile, depth)); }

    public Shape cellEntry(int mapId, int layer, int x, int y) { return cells.get(cellKey(mapId, layer, x, y)); }

    /** Sets (s != null) or removes (s == null) the table entry of one tile; returns the previous entry or null. */
    public Shape setTile(int layer, int tileset, int tile, int depth, Shape s) {
        long k = key(layer, tileset, tile, depth);
        Shape prev = s == null ? map.remove(k) : map.put(k, s);
        if (s == null) origin.remove(k);
        else {
            Spec sp = new Spec();
            sp.layer = layer;
            sp.tileset = tileset;
            sp.lo = sp.hi = tile;
            sp.depth = depth;
            origin.put(k, sp.format() + " (edited)");
        }
        version++;
        return prev;
    }

    public Shape setMapTile(int mapId, int layer, int tile, int depth, Shape s) {
        long k = mapKey(mapId, layer, tile, depth);
        Shape prev = s == null ? mapTile.remove(k) : mapTile.put(k, s);
        version++;
        return prev;
    }

    public Shape setCell(int mapId, int layer, int x, int y, Shape s) {
        long k = cellKey(mapId, layer, x, y);
        Shape prev = s == null ? cells.remove(k) : cells.put(k, s);
        version++;
        return prev;
    }

    // ------------------------------------------------------------------------------------------ loading

    /**
     * Loads from {@code override} (a file, used by F6 for hand tuning) if it exists, else from the bundled resource plus
     * the user override file. Never throws: problems are logged and the previous/default shapes stay in effect.
     *
     * <p>Load order, later lines win: auto table ({@code tiles-auto.properties}) under hand table ({@code tiles.properties}),
     * then the user override file in {@code ~/.config} (last). Per-map and per-cell overrides are kept apart from the tile
     * table and beat it regardless of file order.
     */
    public static TileTable load(File override) {
        TileTable t = new TileTable();
        try {
            if (override != null && override.isFile()) {
                // explicit hand file: its sibling auto table goes underneath it
                File auto = new File(override.getAbsoluteFile().getParentFile(), "tiles-auto.properties");
                if (auto.isFile()) t.loadLines(Files.readAllLines(auto.toPath(), StandardCharsets.UTF_8), auto.getPath());
                t.loadLines(Files.readAllLines(override.toPath(), StandardCharsets.UTF_8), override.getPath());
                t.source = override.getPath();
                t.saveTarget = override;
            } else {
                // built-in: classpath auto table first, then the classpath hand table on top of it
                try (InputStream in = TileTable.class.getClassLoader().getResourceAsStream("render3d/tiles-auto.properties")) {
                    if (in != null) {
                        t.loadLines(java.util.Arrays.asList(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n", -1)), "builtin:tiles-auto.properties");
                    }
                }
                try (InputStream in = TileTable.class.getClassLoader().getResourceAsStream("render3d/tiles.properties")) {
                    if (in != null) {
                        t.loadLines(java.util.Arrays.asList(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n", -1)), "builtin");
                        t.source = "classpath:render3d/tiles.properties";
                    }
                }
                File u = userFile();
                t.saveTarget = u;
                t.saveTargetIsUserFile = true;
                if (u.isFile()) {
                    t.loadLines(Files.readAllLines(u.toPath(), StandardCharsets.UTF_8), u.getPath());
                    t.source += " + " + u.getPath();
                }
            }
        } catch (Exception e) {
            System.err.println("[render3d] cannot read tile table: " + e);
        }
        return t;
    }

    /** Label of the file being loaded (shown as the origin of entries; an auto-tag layer would load with its own label). */
    private String srcLabel = "";

    private void loadLines(List<String> lines, String name) {
        srcLabel = new File(name).getName();
        int n = 0;
        for (String raw : lines) {
            n++;
            String tr = raw.trim();
            if (tr.isEmpty() || tr.startsWith("#") || tr.startsWith("!")) continue;
            Rule r = Rule.parse(raw);
            if (r == null && (tr.startsWith("ice=") || tr.startsWith("ice ") || tr.startsWith("ice.") || tr.startsWith("snow"))) continue;   // ICE / SNOW: `ice` / `snow` lines are read by IceLayer / SnowSparkle
            if (r == null) {
                System.err.println("[render3d] tile table: bad line " + name + ":" + n + ": " + tr);
                continue;
            }
            apply(r);
        }
    }

    private void apply(Rule r) {
        Spec sp = r.spec;
        switch (sp.type) {
            case DEFAULT: defaults[sp.layer] = r.shape; return;
            case VOID: voidTile.put(sp.tileset, r.voidTile); return;
            case CELL: cells.put(cellKey(sp.mapId, sp.layer, sp.cx, sp.cy), r.shape); return;
            default:
        }
        String text = sp.format();
        for (int t = sp.lo; t <= sp.hi; t++) {
            if (sp.mapId >= 0) {
                mapTile.put(mapKey(sp.mapId, sp.layer, t, sp.depth), r.shape);
            } else {
                long k = key(sp.layer, sp.tileset, t, sp.depth);
                map.put(k, r.shape);
                origin.put(k, text + " [" + srcLabel + "]");
            }
        }
    }

    /** All rule specs/lines of a file as text (helper for tools); empty if unreadable. */
    public static List<String> readLines(File f) {
        try {
            if (f != null && f.isFile()) return new ArrayList<>(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[render3d] cannot read " + f + ": " + e);
        }
        return new ArrayList<>();
    }
}
