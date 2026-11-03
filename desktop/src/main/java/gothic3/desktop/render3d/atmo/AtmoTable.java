package gothic3.desktop.render3d.atmo;

import java.io.File;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Data table {@code render3d/atmosphere.properties}: weather per map / tileset and window tile ids. */
final class AtmoTable {
    /** Window tile ids of one tileset. */
    static final class WindowSet {
        boolean bright;
        int[] ids = new int[0];
    }

    private final Map<String, String> weather = new HashMap<>();
    private final Map<Integer, WindowSet> windows = new HashMap<>();
    private String deflt = "none";
    String source = "built-in defaults";

    static AtmoTable load() {
        AtmoTable t = new AtmoTable();
        Properties p = new Properties();
        File f = new File("game/src/main/resources/render3d/atmosphere.properties");
        try {
            if (f.isFile()) {
                try (Reader r = java.nio.file.Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) { p.load(r); }
                t.source = f.getPath();
            } else {
                try (InputStream in = AtmoTable.class.getClassLoader().getResourceAsStream("render3d/atmosphere.properties")) {
                    if (in != null) {
                        try (Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) { p.load(r); }
                        t.source = "classpath render3d/atmosphere.properties";
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[render3d] atmosphere table not readable: " + e);
        }
        for (String k : p.stringPropertyNames()) {
            String v = p.getProperty(k).trim();
            int c = v.indexOf('#');
            if (c >= 0) v = v.substring(0, c).trim();
            k = k.trim();
            if (k.equals("default")) t.deflt = v;
            else if (k.startsWith("window.ts")) {
                try {
                    WindowSet w = new WindowSet();
                    int colon = v.indexOf(':');
                    w.bright = !v.substring(0, Math.max(0, colon)).trim().equalsIgnoreCase("dark");
                    String[] ids = v.substring(colon + 1).split("[,; ]+");
                    int[] tmp = new int[ids.length];
                    int n = 0;
                    for (String s : ids) if (!s.isEmpty()) tmp[n++] = Integer.parseInt(s);
                    w.ids = java.util.Arrays.copyOf(tmp, n);
                    t.windows.put(Integer.parseInt(k.substring(9)), w);
                } catch (RuntimeException e) {
                    System.err.println("[render3d] atmosphere: bad window line " + k + " = " + v);
                }
            } else t.weather.put(k, v.toLowerCase());
        }
        return t;
    }

    /** Weather kind (snow | dust | none) for a map: map id, then layer-1 tileset, then layer-0 tileset, then the default. */
    String weather(int mapId, int ts0, int ts1) {
        String v = weather.get("map" + mapId);
        if (v == null) v = weather.get("ts" + ts1);
        if (v == null) v = weather.get("ts" + ts0);
        return v != null ? v : deflt.toLowerCase();
    }

    WindowSet windows(int tileset1) { return windows.get(tileset1); }
}
