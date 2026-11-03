package gothic3.desktop.render3d.autotag;

/** Image features of one tile (ARGB pixels, tw x th). */
final class Features {
    float coverage;      // fraction of pixels with alpha >= 128
    float cornerHole;    // largest transparent region touching the tile border (fraction of tile)
    float ragged;        // alpha transitions per pixel (cut-out sprites such as plants are high)
    float smooth;        // mean abs luma difference of neighbouring opaque pixels (0..255): low = flat shaded surface
    float snow;          // opaque pixels that are light and nearly grey
    float dark;          // opaque pixels with luma < 30
    float luma;          // mean luma of opaque pixels
    float sat;           // mean saturation (max-min) of opaque pixels
    float holes;         // transparent fraction not touching the border (holey sprites)

    static Features of(int[] px, int tw, int th) {
        Features f = new Features();
        int n = tw * th, opaque = 0, snow = 0, dark = 0, trans = 0;
        double luma = 0, sat = 0, diff = 0;
        int dn = 0;
        boolean[] op = new boolean[n];
        for (int i = 0; i < n; i++) {
            int p = px[i];
            op[i] = (p >>> 24) >= 128;
            if (!op[i]) continue;
            opaque++;
            int r = p >> 16 & 255, g = p >> 8 & 255, b = p & 255;
            float l = 0.299f * r + 0.587f * g + 0.114f * b;
            luma += l;
            int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
            sat += mx - mn;
            if (mn >= 140 && mx - mn <= 45) snow++;
            if (l < 30) dark++;
        }
        for (int y = 0; y < th; y++) for (int x = 0; x < tw; x++) {
            int i = y * tw + x;
            if (op[i] != (x + 1 < tw ? op[i + 1] : op[i])) trans++;
            if (op[i] != (y + 1 < th ? op[i + tw] : op[i])) trans++;
            if (!op[i]) continue;
            if (x + 1 < tw && op[i + 1]) { diff += Math.abs(lum(px[i]) - lum(px[i + 1])); dn++; }
            if (y + 1 < th && op[i + tw]) { diff += Math.abs(lum(px[i]) - lum(px[i + tw])); dn++; }
        }
        // transparent regions: flood fill from the border
        boolean[] seen = new boolean[n];
        int border = 0, best = 0;
        int[] stack = new int[n];
        for (int s = 0; s < n; s++) {
            int x0 = s % tw, y0 = s / tw;
            if (op[s] || seen[s] || !(x0 == 0 || y0 == 0 || x0 == tw - 1 || y0 == th - 1)) continue;
            int sp = 0, cnt = 0;
            stack[sp++] = s; seen[s] = true;
            while (sp > 0) {
                int c = stack[--sp]; cnt++;
                int cx = c % tw, cy = c / tw;
                if (cx > 0 && !op[c - 1] && !seen[c - 1]) { seen[c - 1] = true; stack[sp++] = c - 1; }
                if (cx < tw - 1 && !op[c + 1] && !seen[c + 1]) { seen[c + 1] = true; stack[sp++] = c + 1; }
                if (cy > 0 && !op[c - tw] && !seen[c - tw]) { seen[c - tw] = true; stack[sp++] = c - tw; }
                if (cy < th - 1 && !op[c + tw] && !seen[c + tw]) { seen[c + tw] = true; stack[sp++] = c + tw; }
            }
            border += cnt;
            best = Math.max(best, cnt);
        }
        for (int i = 0; i < n; i++) if (!op[i]) trans += 0;
        int transparent = n - opaque;
        f.coverage = opaque / (float) n;
        f.cornerHole = best / (float) n;
        f.ragged = trans / (float) n;
        f.smooth = dn == 0 ? 0 : (float) (diff / dn);
        f.snow = opaque == 0 ? 0 : snow / (float) opaque;
        f.dark = opaque == 0 ? 0 : dark / (float) opaque;
        f.luma = opaque == 0 ? 0 : (float) (luma / opaque);
        f.sat = opaque == 0 ? 0 : (float) (sat / opaque);
        f.holes = (transparent - border) / (float) n;
        return f;
    }

    private static float lum(int p) { return 0.299f * (p >> 16 & 255) + 0.587f * (p >> 8 & 255) + 0.114f * (p & 255); }
}
