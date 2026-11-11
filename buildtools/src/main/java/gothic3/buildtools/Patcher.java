package gothic3.buildtools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Zero-context unified diffs ("-U0"): one hunk per changed run of lines, addressed by line number and checked against
 * the removed lines. Text is handled as ISO-8859-1 so every byte round-trips unchanged.
 */
final class Patcher {
    private Patcher() {}

    static List<String> readLines(Path p) throws IOException {
        String s = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
        if (s.isEmpty()) return new ArrayList<>();
        if (!s.endsWith("\n")) throw new IOException(p + ": does not end with a newline (unsupported)");
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            int e = s.indexOf('\n', i);
            // tolerate CRLF (a Windows checkout with core.autocrlf turns the bundled patches into CRLF)
            out.add(s.substring(i, e > i && s.charAt(e - 1) == '\r' ? e - 1 : e));
            i = e + 1;
        }
        return out;
    }

    static void writeLines(Path p, List<String> lines) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        Files.createDirectories(p.getParent());
        Files.write(p, sb.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Applies a -U0 patch; fails loudly when a removed line does not match. */
    static List<String> apply(List<String> orig, List<String> patch, String name) throws IOException {
        List<String> out = new ArrayList<>();
        int pos = 0; // next original line (0-based) not yet copied
        int i = 0;
        while (i < patch.size()) {
            String h = patch.get(i);
            if (h.startsWith("--- ") || h.startsWith("+++ ")) { i++; continue; }
            if (!h.startsWith("@@ -")) throw new IOException(name + ": bad patch line " + (i + 1) + ": " + h);
            // @@ -a[,b] +c[,d] @@
            String[] t = h.split(" ");
            int[] old = range(t[1].substring(1));
            int start = old[1] == 0 ? old[0] : old[0] - 1; // 0-based index of the first removed line
            if (start < pos || start + old[1] > orig.size())
                throw new IOException(name + ": hunk out of range: " + h);
            while (pos < start) out.add(orig.get(pos++));
            i++;
            int removed = 0;
            while (i < patch.size() && !patch.get(i).startsWith("@@ -")) {
                String l = patch.get(i);
                if (l.startsWith("-")) {
                    if (pos >= orig.size() || !orig.get(pos).equals(l.substring(1)))
                        throw new IOException(name + ": removed line does not match at original line " + (pos + 1)
                            + " (hunk " + h + ")\n  patch:    " + l.substring(1)
                            + "\n  original: " + (pos < orig.size() ? orig.get(pos) : "<EOF>"));
                    pos++;
                    removed++;
                } else if (l.startsWith("+")) {
                    out.add(l.substring(1));
                } else {
                    throw new IOException(name + ": bad hunk body line: " + l);
                }
                i++;
            }
            if (removed != old[1]) throw new IOException(name + ": hunk length mismatch: " + h);
        }
        while (pos < orig.size()) out.add(orig.get(pos++));
        return out;
    }

    private static int[] range(String s) {
        int c = s.indexOf(',');
        if (c < 0) return new int[] {Integer.parseInt(s), 1};
        return new int[] {Integer.parseInt(s.substring(0, c)), Integer.parseInt(s.substring(c + 1))};
    }

    /** Minimal (Myers) line diff as a -U0 patch; empty list when the files are equal. */
    static List<String> diff(List<String> a, List<String> b, String name) {
        // strip common prefix/suffix, then Myers on the middle
        int pre = 0;
        while (pre < a.size() && pre < b.size() && a.get(pre).equals(b.get(pre))) pre++;
        int suf = 0;
        while (suf < a.size() - pre && suf < b.size() - pre
            && a.get(a.size() - 1 - suf).equals(b.get(b.size() - 1 - suf))) suf++;
        int n = a.size() - pre - suf, m = b.size() - pre - suf;
        List<String> out = new ArrayList<>();
        if (n == 0 && m == 0) return out;
        String[] x = a.subList(pre, pre + n).toArray(new String[0]);
        String[] y = b.subList(pre, pre + m).toArray(new String[0]);
        // intern-by-index so comparisons are int compares
        java.util.Map<String, Integer> ids = new java.util.HashMap<>();
        int[] xi = new int[n], yi = new int[m];
        for (int k = 0; k < n; k++) xi[k] = ids.computeIfAbsent(x[k], z -> ids.size());
        for (int k = 0; k < m; k++) yi[k] = ids.computeIfAbsent(y[k], z -> ids.size());
        boolean[] delX = new boolean[n], insY = new boolean[m];
        myers(xi, yi, delX, insY);
        out.add("--- a/" + name);
        out.add("+++ b/" + name);
        int ix = 0, iy = 0;
        while (ix < n || iy < m) {
            if (ix < n && !delX[ix] && iy < m && !insY[iy]) { ix++; iy++; continue; }
            int sx = ix, sy = iy;
            while (ix < n && delX[ix]) ix++;
            while (iy < m && insY[iy]) iy++;
            int ca = ix - sx, cb = iy - sy;
            int oldStart = pre + sx + 1, newStart = pre + sy + 1;
            out.add("@@ -" + (ca == 0 ? (oldStart - 1) + ",0" : ca == 1 ? "" + oldStart : oldStart + "," + ca)
                + " +" + (cb == 0 ? (newStart - 1) + ",0" : cb == 1 ? "" + newStart : newStart + "," + cb) + " @@");
            for (int k = sx; k < ix; k++) out.add("-" + x[k]);
            for (int k = sy; k < iy; k++) out.add("+" + y[k]);
        }
        return out;
    }

    private static void myers(int[] a, int[] b, boolean[] delA, boolean[] insB) {
        int n = a.length, m = b.length, max = n + m;
        int off = max + 1;
        int[] v = new int[2 * max + 3];
        List<int[]> trace = new ArrayList<>();
        int dEnd = -1;
        outer:
        for (int d = 0; d <= max; d++) {
            trace.add(java.util.Arrays.copyOfRange(v, off - d - 1, off + d + 2));
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) x = v[off + k + 1];
                else x = v[off + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a[x] == b[y]) { x++; y++; }
                v[off + k] = x;
                if (x >= n && y >= m) { dEnd = d; break outer; }
            }
        }
        int x = n, y = m;
        for (int d = dEnd; d > 0; d--) {
            int[] vv = trace.get(d);
            int k = x - y;
            int base = d + 1;
            int prevK = (k == -d || (k != d && vv[base + k - 1] < vv[base + k + 1])) ? k + 1 : k - 1;
            int prevX = vv[base + prevK], prevY = prevX - prevK;
            while (x > prevX && y > prevY) { x--; y--; }
            if (x == prevX) { insB[prevY] = true; y = prevY; }
            else { delA[prevX] = true; x = prevX; }
        }
    }
}
