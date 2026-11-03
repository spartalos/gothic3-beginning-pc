package gothic3.desktop.render3d.structures;

import gothic3.desktop.Config;
import java.io.File;
import java.io.Reader;
import java.nio.file.Files;
import java.util.Properties;

/** Structure builder tunables (config / live tune file keys {@code render3d.structures*}). */
public final class Settings {
    /** render3d.structures = true | false (false: old per-tile upright / slope pieces). */
    public boolean enabled = true;
    /** render3d.structures.stripHeight: height of flat top-only structures (snow banks along the map edge). */
    public float stripHeight = 0.4f;
    /** render3d.structures.roofDepth: scale of the gable roof depth derived from the art. */
    public float roofDepth = 1.0f;
    /** render3d.structures.capWall: walls with a dark top cap are at most this high (rooms behind stay visible); 0 = unlimited. */
    public float capWall = 2.5f;
    /** render3d.structures.wallDepth: thickness of walls without a top cap in the art. */
    public float wallDepth = 1.0f;

    /** render3d.structures.thinCaps: walls whose cap is a few black pixels in the art (partitions) are as thin as the art (not a full tile). */
    public boolean thinCaps = true;
    /** render3d.structures.minThickness: lower bound (tiles) of a thin partition. */
    public float minThickness = 0.25f;
    /** render3d.structures.maxThickness: upper bound (tiles) of a thin partition. */
    public float maxThickness = 0.32f;

    /** render3d.structures.indoorWallDepth: thickness of cap-less walls on indoor maps (render3d.indoor.tilesets); 0 = wallDepth. */
    public float indoorWallDepth = 0.4f;
    /** Tilesets of indoor maps (same key as the renderer's lighting: render3d.indoor.tilesets, default 5). */
    public java.util.Set<Integer> indoorTilesets = new java.util.HashSet<>();

    /** render3d.structures.uniformWall: height (tiles) every non-gabled wall of the {@link #uniformWallTilesets} gets (2 / 3 art rows are stretched / squashed to it), so the walls of one room agree; 0 = off. */
    public float uniformWall = 2.5f;
    /** render3d.structures.uniformWallTilesets (default 4): castle crypt interiors, where the same wall is drawn with 2 rows in one place and 3 in another. */
    public java.util.Set<Integer> uniformWallTilesets = new java.util.HashSet<>();

    /**
     * render3d.structures.southWall: height (tiles) of the south wall row of the {@link #uniformWallTilesets} maps (the bottom map row: one art row, the top of
     * the wall with the top halves of the arched windows; the rest of the wall is off the map). It is rebuilt as a finished wall: the art rows below the
     * map's last row are filled in from the tiles of the same columns (windows become whole), door jambs become plain brick; 0 = off (the one art row, cut off).
     */
    public float southWall = 1.2f;
    /** render3d.structures.southWallRows: art rows of that wall's face, 3 = the whole 2.5 high wall with whole windows (southWall 2.5: hides the room's bottom rows), 2 / 1 = only the plain brick rows below the windows (low wall). */
    public int southWallRows = 2;
    /** render3d.structures.doorLintel: 1 = a doorway gap in a uniformWall wall gets a brick lintel above the 2 tile door art (0 = off). */
    public int doorLintel = 1;

    /** Cave tilesets (render3d.cave.tilesets, default 7): maps on them skip the structure builder (rock walls are low ramps from the tile table). */
    public java.util.Set<Integer> caveTilesets = new java.util.HashSet<>();

    /** render3d.structures.split: cut side by side houses (same base row) apart where two roof outlines meet. */
    public boolean split = true;
    /** render3d.structures.mergeRoofs: gable wings that touch a flat back building share one connected roof (L / T / U compounds). */
    public boolean mergeRoofs = true;

    public static Settings load() {
        Settings s = new Settings();
        Properties tune = new Properties();
        String tn = System.getProperty("g3.render3d.tune");
        if (tn != null) {
            File f = new File(tn);
            if (f.isFile()) try (Reader r = Files.newBufferedReader(f.toPath())) { tune.load(r); } catch (Exception ignored) { }
        }
        Config cfg = null;
        try { cfg = Config.load(); } catch (Throwable ignored) { }
        s.enabled = !val(tune, cfg, "render3d.structures", "true").equalsIgnoreCase("false");
        s.stripHeight = f(val(tune, cfg, "render3d.structures.stripHeight", ""), s.stripHeight);
        s.roofDepth = f(val(tune, cfg, "render3d.structures.roofDepth", ""), s.roofDepth);
        s.capWall = f(val(tune, cfg, "render3d.structures.capWall", ""), s.capWall);
        s.wallDepth = f(val(tune, cfg, "render3d.structures.wallDepth", ""), s.wallDepth);
        s.thinCaps = !val(tune, cfg, "render3d.structures.thinCaps", "true").equalsIgnoreCase("false");
        s.minThickness = f(val(tune, cfg, "render3d.structures.minThickness", ""), s.minThickness);
        s.maxThickness = f(val(tune, cfg, "render3d.structures.maxThickness", ""), s.maxThickness);
        s.indoorWallDepth = f(val(tune, cfg, "render3d.structures.indoorWallDepth", ""), s.indoorWallDepth);
        for (String t : val(tune, cfg, "render3d.indoor.tilesets", "5").split("[,; ]+")) {
            try { if (!t.isEmpty()) s.indoorTilesets.add(Integer.parseInt(t)); } catch (NumberFormatException ignored) { }
        }
        for (String t : val(tune, cfg, "render3d.structures.indoorTilesets", "4").split("[,; ]+")) { // castle interiors (areas 17 / 18) are tileset 4
            try { if (!t.isEmpty()) s.indoorTilesets.add(Integer.parseInt(t)); } catch (NumberFormatException ignored) { }
        }
        s.uniformWall = f(val(tune, cfg, "render3d.structures.uniformWall", ""), s.uniformWall);
        for (String t : val(tune, cfg, "render3d.structures.uniformWallTilesets", "4").split("[,; ]+")) {
            try { if (!t.isEmpty()) s.uniformWallTilesets.add(Integer.parseInt(t)); } catch (NumberFormatException ignored) { }
        }
        s.southWall = f(val(tune, cfg, "render3d.structures.southWall", ""), s.southWall);
        s.southWallRows = (int) f(val(tune, cfg, "render3d.structures.southWallRows", ""), s.southWallRows);
        s.doorLintel = (int) f(val(tune, cfg, "render3d.structures.doorLintel", ""), s.doorLintel);
        for (String t : val(tune, cfg, "render3d.cave.tilesets", "7").split("[,; ]+")) {
            try { if (!t.isEmpty()) s.caveTilesets.add(Integer.parseInt(t)); } catch (NumberFormatException ignored) { }
        }
        s.split = !val(tune, cfg, "render3d.structures.split", "true").equalsIgnoreCase("false");
        s.mergeRoofs = !val(tune, cfg, "render3d.structures.mergeRoofs", "true").equalsIgnoreCase("false");
        return s;
    }

    private static String val(Properties tune, Config cfg, String k, String def) {
        String v = tune.getProperty(k);
        if (v == null && cfg != null) v = cfg.get(k);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    private static float f(String v, float def) {
        try { return Float.parseFloat(v); } catch (NumberFormatException e) { return def; }
    }
}
