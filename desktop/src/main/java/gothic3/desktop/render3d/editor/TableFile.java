package gothic3.desktop.render3d.editor;

import gothic3.desktop.render3d.TileTable;
import gothic3.desktop.render3d.TileTable.Rule;
import gothic3.desktop.render3d.TileTable.Shape;
import gothic3.desktop.render3d.TileTable.Spec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Text-level update of a tile table file: only the lines of the edited tiles change, comments and ordering are kept.
 * An edit of one tile (or cell) splits a range line that covers it, or replaces / removes an existing single line, or
 * adds a new line after the last rule of the same section (tileset or map), or in a new section at the end of the file.
 */
final class TableFile {
    private TableFile() {}

    /** Applies "tile/cell {@code sp} now has shape {@code v}" (v == null: no entry) to the file lines. */
    static void apply(List<String> lines, Spec sp, Shape v) {
        boolean done = false;
        for (int i = 0; i < lines.size(); i++) {
            Rule r = Rule.parse(lines.get(i));
            if (r == null || r.spec.type != sp.type || !r.spec.sameScope(sp)) continue;
            if (sp.type == Spec.Type.CELL) {
                if (r.spec.cx != sp.cx || r.spec.cy != sp.cy) continue;
                if (v == null) { lines.remove(i--); } else { r.shape = v; lines.set(i, r.format()); }
                done = true;
                continue;
            }
            int t = sp.lo;
            if (t < r.spec.lo || t > r.spec.hi) continue;
            if (r.spec.lo == r.spec.hi) {
                if (v == null) { lines.remove(i--); } else { r.shape = v; lines.set(i, r.format()); }
                done = true;
            } else {
                if (v != null && v.equals(r.shape)) { done = true; continue; } // already covered by the range
                List<String> parts = new ArrayList<>();
                int lo = r.spec.lo, hi = r.spec.hi;
                if (t > lo) { r.spec.hi = t - 1; parts.add(r.format()); r.trailing = ""; }
                if (t < hi) { r.spec.lo = t + 1; r.spec.hi = hi; parts.add(r.format()); }
                lines.remove(i);
                lines.addAll(i, parts);
                i += parts.size() - 1;
                if (v == null) done = true;
            }
        }
        if (done) { merge(lines, sp, v); return; }
        if (v == null) return;
        Rule nr = new Rule();
        nr.spec = sp;
        nr.shape = v;
        int last = -1;
        for (int i = 0; i < lines.size(); i++) {
            Rule r = Rule.parse(lines.get(i));
            if (r != null && r.spec.type != Spec.Type.DEFAULT && r.spec.sameSection(sp)) last = i;
        }
        if (last >= 0) {
            lines.add(last + 1, nr.format());
        } else {
            while (!lines.isEmpty() && lines.get(lines.size() - 1).trim().isEmpty()) lines.remove(lines.size() - 1);
            if (!lines.isEmpty()) lines.add("");
            lines.add(sp.mapId >= 0 ? "# --- map " + sp.mapId + " overrides (tile editor)" : "# --- tileset " + sp.tileset + " (tile editor)");
            lines.add(nr.format());
        }
        merge(lines, sp, v);
    }

    /** Joins the lines of the same scope and shape whose ranges touch the edited tile (23-29 + 30 + 31-34 -> 23-34). */
    private static void merge(List<String> lines, Spec sp, Shape v) {
        if (v == null || sp.type != Spec.Type.TILE) return;
        int first = -1, lo = sp.lo, hi = sp.hi;
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Rule r = Rule.parse(lines.get(i));
            if (r == null || r.spec.type != Spec.Type.TILE || !r.spec.sameScope(sp) || !v.equals(r.shape)) continue;
            if (r.spec.hi < sp.lo - 1 || r.spec.lo > sp.lo + 1) continue;
            hits.add(i);
            lo = Math.min(lo, r.spec.lo);
            hi = Math.max(hi, r.spec.hi);
        }
        if (hits.size() < 2) return;
        first = hits.get(0);
        Rule r = Rule.parse(lines.get(first));
        r.spec.lo = lo;
        r.spec.hi = hi;
        lines.set(first, r.format());
        for (int k = hits.size() - 1; k >= 1; k--) lines.remove((int) hits.get(k));
    }

    /** Applies all edits and writes the file (atomic replace). */
    static void save(File f, List<Spec> specs, List<Shape> values) throws IOException {
        List<String> lines = TileTable.readLines(f);
        for (int i = 0; i < specs.size(); i++) apply(lines, specs.get(i), values.get(i));
        File dir = f.getAbsoluteFile().getParentFile();
        if (dir != null) dir.mkdirs();
        File tmp = new File(dir, f.getName() + ".tmp");
        Files.write(tmp.toPath(), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
