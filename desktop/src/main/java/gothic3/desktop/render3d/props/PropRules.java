package gothic3.desktop.render3d.props;

import java.io.File;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The prop table ({@code render3d/props.properties}): image id (+ optional source rect) to model type, and fire lights.
 * <pre>
 * default.object = voxel:auto          # images[] sprites (props, items, effects)
 * default.sheet  = card                # animated sheets (characters, monsters)
 * i61 = lathe:12                       # image 61, every frame
 * i48@0,0,18,22 = voxel:0.3 light:ff8030:3.5:0.7      # only this source rect
 * </pre>
 * Tokens: {@code card}, {@code voxel[:depthTiles|auto]}, {@code lathe[:sides]}, {@code cross}, {@code lie[:heightTiles]} (lying object: art = top of a low box), {@code table[:heightTiles[:cutRows]]} (top-down furniture: art = tabletop at that height, solid body below), {@code fencens} (north-south fence seen along its length: rebuilt as posts + rails), {@code light:RRGGBB:radius:height[:intensity]}, {@code float}, {@code nofoot}, {@code perch:iN} (ornament that sits on top of the nearest sprite of image iN, e.g. the horned skull on a totem pole), {@code core} (voxel: extrude only the largest connected part), {@code sway[:amount]} (vegetation card / cross that bends in the wind, see atmo.sway), {@code height:H} (squash the model to at most H tiles tall, e.g. a door that must not stand taller than its wall).
 */
public final class PropRules {
    public static final int CARD = 0, VOXEL = 1, LATHE = 2, CROSS = 3, LIE = 4, TABLE = 5, FENCE_NS = 6;

    public static final class Rule {
        public int type = CARD;
        public float depth = -1;          // tiles, <0 = auto
        public int sides = 12;
        /** table: sprite rows at the bottom that are not part of the top (pedestal stub). */
        public int cut;
        /** parsed from an explicit table entry (not the default rule). */
        public boolean explicit;
        public boolean light;
        /** token {@code float}: do not drop the sprite onto the ground (hanging / mounted things). */
        public boolean floating;
        /** token {@code nofoot}: no soft foot (contact) shadow under this card / cross prop. */
        public boolean noFoot;
        /** token {@code perch:iN}: the sprite is an ornament on top of a sprite of image iN that the game draws on another tile (see PropLayer.perch); null = none. */
        public String perch;
        /** token {@code core}: voxel only extrudes the largest connected solid part; dithered puddle / rubble pixels around it stay flat on the front quad. */
        public boolean core;
        /** token {@code height:H}: the model is squashed vertically (about its feet) so that it is at most H tiles tall; 0 = off. */
        public float height;
        /** token {@code sway[:amount]}: a plant that bends in the wind (atmo.sway); 0 = not a plant. */
        public float sway;
        /** token {@code hide}: the sprite is not drawn at all in 3D (e.g. the open frame of a door: the walls beside the gap already are its jambs and lintel). */
        public boolean hide;
        public int lightRgb = 0xFF8A3A;
        public float lightRadius = 3.5f, lightHeight = 0.8f, lightIntensity = 1f;
        Rule copy() {
            Rule r = new Rule();
            r.type = type; r.depth = depth; r.sides = sides; r.cut = cut; r.explicit = explicit; r.light = light; r.floating = floating; r.noFoot = noFoot; r.perch = perch; r.core = core; r.height = height; r.sway = sway; r.hide = hide; r.lightRgb = lightRgb;
            r.lightRadius = lightRadius; r.lightHeight = lightHeight; r.lightIntensity = lightIntensity;
            return r;
        }
    }

    private final Map<String, Rule> exact = new HashMap<>();
    private Rule defObject = parse("voxel:auto"), defSheet = parse("card");
    private String source = "built-in defaults";

    public String source() { return source; }

    public static PropRules load() {
        PropRules p = new PropRules();
        Properties pr = new Properties();
        File f = new File("game/src/main/resources/render3d/props.properties");
        try {
            if (f.isFile()) {
                try (Reader r = java.nio.file.Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) { pr.load(r); }
                p.source = f.getPath();
            } else {
                try (InputStream in = PropRules.class.getClassLoader().getResourceAsStream("render3d/props.properties")) {
                    if (in != null) {
                        try (Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) { pr.load(r); }
                        p.source = "classpath render3d/props.properties";
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[render3d] props table not readable: " + e);
        }
        for (String k : pr.stringPropertyNames()) {
            String v = pr.getProperty(k).trim();
            int c = v.indexOf('#');
            if (c >= 0) v = v.substring(0, c).trim();
            if (k.equals("default.object")) p.defObject = parse(v);
            else if (k.equals("default.sheet")) p.defSheet = parse(v);
            else p.exact.put(k.trim(), parse(v));
        }
        return p;
    }

    static Rule parse(String v) {
        Rule r = new Rule();
        for (String t : v.split("\\s+")) {
            if (t.isEmpty()) continue;
            String[] a = t.split(":");
            try {
                switch (a[0]) {
                    case "card": r.type = CARD; break;
                    case "voxel":
                        r.type = VOXEL;
                        r.depth = a.length > 1 && !a[1].equals("auto") ? Float.parseFloat(a[1]) : -1;
                        break;
                    case "lathe":
                        r.type = LATHE;
                        if (a.length > 1) r.sides = Math.max(4, Math.min(32, Integer.parseInt(a[1])));
                        break;
                    case "cross": r.type = CROSS; break;
                    case "lie":
                        r.type = LIE;
                        r.depth = a.length > 1 ? Float.parseFloat(a[1]) : 0.35f;   // box height in tiles
                        break;
                    case "table":
                        r.type = TABLE;
                        r.depth = a.length > 1 ? Float.parseFloat(a[1]) : 0.45f;   // top height in tiles
                        r.cut = a.length > 2 ? Math.max(0, Math.min(15, Integer.parseInt(a[2]))) : 0;
                        break;
                    case "fencens": r.type = FENCE_NS; break;
                    case "float": r.floating = true; break;
                    case "nofoot": r.noFoot = true; break;
                    case "perch": r.perch = a.length > 1 ? a[1] : null; break;
                    case "core": r.core = true; break;
                    case "hide": r.hide = true; break;
                    case "height": r.height = a.length > 1 ? Float.parseFloat(a[1]) : 0; break;
                    case "sway": r.sway = a.length > 1 ? Float.parseFloat(a[1]) : 1f; break;
                    case "light":
                        r.light = true;
                        if (a.length > 1) r.lightRgb = Integer.parseInt(a[1], 16);
                        if (a.length > 2) r.lightRadius = Float.parseFloat(a[2]);
                        if (a.length > 3) r.lightHeight = Float.parseFloat(a[3]);
                        if (a.length > 4) r.lightIntensity = Float.parseFloat(a[4]);
                        break;
                    default: System.err.println("[render3d] props: unknown token " + t);
                }
            } catch (NumberFormatException e) {
                System.err.println("[render3d] props: bad token " + t);
            }
        }
        return r;
    }

    /** The explicit rule of a sprite (exact id / rect entry), or null when only the defaults would apply. Used for floor-pass (flat) sprites. */
    public Rule explicit(String id, int xs, int ys, int w, int h) {
        if (id == null) return null;
        String base = id;
        int dot = base.indexOf('.');
        if (base.charAt(0) == 'i' && dot > 0) base = base.substring(0, dot);
        Rule r = exact.get(base + "@" + xs + "," + ys + "," + w + "," + h);
        return r != null ? r : exact.get(base);
    }

    /** Rule for a sprite: id {@code i61}, {@code i61.2} (recolour) or {@code s2.14}; {@code null} id = unknown image. */
    public Rule lookup(String id, int xs, int ys, int w, int h) {
        if (id == null) return defSheet;
        String base = id;
        int dot = base.indexOf('.');
        if (base.charAt(0) == 'i' && dot > 0) base = base.substring(0, dot);
        Rule r = exact.get(base + "@" + xs + "," + ys + "," + w + "," + h);
        if (r == null) r = exact.get(base);
        if (r != null) return r;
        return id.charAt(0) == 'i' ? defObject : defSheet;
    }
}
