package gothic3.desktop.render3d.autotag;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Console report: agreement with the hand tags, coverage, confidence distribution. */
final class Report {
    static void print(AutoTagger a, Map<String, String> hand) {
        Map<String, int[]> agree = new TreeMap<>(); // hand kind -> {total, matched}
        int[][] confHist = new int[2][5];
        Map<String, Integer> kinds = new TreeMap<>();
        Map<Integer, int[]> perTs = new TreeMap<>();
        int tot = 0, ok = 0;
        for (AutoTagger.Tag t : a.tags) {
            kinds.merge("L" + t.layer + " " + AutoTagger.kind(t.shape), 1, Integer::sum);
            confHist[0][Math.min(4, (int) (t.conf * 5))]++;
            perTs.computeIfAbsent(t.ts, k -> new int[2])[t.layer]++;
            String h = hand.get(t.layer + "/" + t.ts + "/" + t.id);
            if (h == null && ((t.ts == 3 && t.layer == 1) || (t.ts == 0 && t.layer == 0))) h = t.layer == 0 ? "floor" : "upright"; // fully hand-tagged sets: unlisted = default
            if (h == null) continue;
            int[] c = agree.computeIfAbsent(AutoTagger.kind(h), k -> new int[2]);
            c[0]++; tot++;
            boolean m = AutoTagger.kind(h).equals(AutoTagger.kind(t.shape));
            if (m) { c[1]++; ok++; }
            else System.out.println("MISMATCH ts" + t.ts + ".layer" + t.layer + "." + t.id + " hand=" + h + " auto=" + t.shape + "  " + t.reason);
        }
        // hand-tagged tiles that appear in the maps but where hand says the default (not listed in the file) are not counted
        System.out.println("agreement with hand tags (tiles listed in tiles.properties): " + ok + "/" + tot);
        for (var e : agree.entrySet())
            System.out.printf(Locale.ROOT, "  hand %-8s %3d tiles, auto agrees %3d (%.0f%%)%n", e.getKey(), e.getValue()[0], e.getValue()[1], 100.0 * e.getValue()[1] / e.getValue()[0]);
        System.out.println("shapes: " + kinds);
        System.out.println("tiles per tileset [layer0, layer1]: ");
        perTs.forEach((k, v) -> System.out.println("  ts" + k + " " + v[0] + " " + v[1]));
        System.out.println("confidence histogram (0-.2 .. .8-1): " + java.util.Arrays.toString(confHist[0]));
        System.out.println("void tiles: " + a.voidTiles);
    }
}
