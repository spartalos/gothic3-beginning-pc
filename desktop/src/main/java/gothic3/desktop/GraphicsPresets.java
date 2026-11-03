package gothic3.desktop;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Overall graphics quality presets (Low / Medium / High / Ultra) as one data table, {@code render3d/presets.properties}:
 * one row per config key, one column per preset. The current preset is never stored: {@link #current} compares the config
 * against the table, so a config that matches no column is "custom" (this also covers configs written before presets existed
 * and any single setting changed afterwards). Keys that have no built-in default in {@link Config} (an effect that is not in
 * this build) are skipped when applying and when matching.
 */
public final class GraphicsPresets {
    private GraphicsPresets() { }

    public static final String[] NAMES = {"low", "medium", "high", "ultra"};
    public static final String CUSTOM = "custom";
    private static final String POST_PRESET = "post.preset";

    private static List<String[]> rows;   // {key, low, medium, high, ultra}

    private static synchronized List<String[]> rows() {
        if (rows != null) return rows;
        List<String[]> out = new ArrayList<>();
        try (InputStream in = GraphicsPresets.class.getResourceAsStream("/render3d/presets.properties")) {
            if (in == null) throw new java.io.IOException("render3d/presets.properties not found");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                int h = line.indexOf('#');
                if (h >= 0) line = line.substring(0, h);
                line = line.trim();
                if (line.isEmpty()) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String[] cells = line.substring(eq + 1).split("\\|");
                if (cells.length != NAMES.length) {
                    System.err.println("[presets] bad row (need " + NAMES.length + " columns): " + line);
                    continue;
                }
                String[] row = new String[NAMES.length + 1];
                row[0] = line.substring(0, eq).trim();
                for (int i = 0; i < NAMES.length; i++) row[i + 1] = cells[i].trim();
                out.add(row);
            }
        } catch (java.io.IOException e) {
            System.err.println("[presets] cannot read the preset table: " + e);
        }
        return rows = out;
    }

    private static int column(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equalsIgnoreCase(name)) return i + 1;
        return -1;
    }

    /** True when the row takes part: it has a value in this column ('-' = leave as is) and the key exists in this build. */
    private static boolean active(String[] row, int col) {
        return !row[col].equals("-") && Config.defaultOf(row[0]) != null;
    }

    private static boolean same(String a, String b) {
        if (a.equalsIgnoreCase(b)) return true;
        try { return Math.abs(Double.parseDouble(a) - Double.parseDouble(b)) < 1e-6; } catch (NumberFormatException e) { return false; }
    }

    /**
     * Writes the values of a preset through {@code setter} (key, value). The post.preset row goes last because setting it
     * to a named post preset rewrites all post.* sliders, and setting a post.* slider flips it to "custom".
     */
    public static void apply(String name, BiConsumer<String, String> setter) {
        int col = column(name);
        if (col < 0) return;
        String post = null;
        for (String[] row : rows()) if (row[0].equals(POST_PRESET) && active(row, col)) setter.accept(POST_PRESET, "subtle");   // baseline for the post.* sliders
        for (String[] row : rows()) {
            if (!active(row, col)) continue;
            if (row[0].equals(POST_PRESET)) post = row[col];
            else setter.accept(row[0], row[col]);
        }
        if (post != null) setter.accept(POST_PRESET, post);
    }

    /** The preset whose column equals the current config, or "custom". */
    public static String current(Config cfg) {
        for (int c = 1; c <= NAMES.length; c++) {
            boolean ok = true;
            for (String[] row : rows()) {
                if (active(row, c) && !same(cfg.get(row[0]), row[c])) { ok = false; break; }
            }
            if (ok) return NAMES[c - 1];
        }
        return CUSTOM;
    }

    /** Dev check: the High column must equal the built-in defaults (a new config then shows "High" and looks as before). */
    public static List<String> highVsDefaults() {
        List<String> bad = new ArrayList<>();
        int col = column("high");
        for (String[] row : rows()) {
            String d = Config.defaultOf(row[0]);
            if (d != null && !row[col].equals("-") && !same(d, row[col])) bad.add(row[0] + ": high=" + row[col] + " default=" + d);
        }
        return bad;
    }

    public static String label(String name) {
        return name.isEmpty() ? name : name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }
}
