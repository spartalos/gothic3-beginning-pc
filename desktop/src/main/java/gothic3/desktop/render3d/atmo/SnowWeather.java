package gothic3.desktop.render3d.atmo;

import java.util.Random;

/**
 * Changing snowfall (config {@code atmo.snow.weather = varying}): periods of no snow, light flurries, normal snow and now and then heavy snow, with
 * smooth transitions of tens of seconds, plus gusts of wind. Pure render side: a private {@link Random} seeded per session (or by
 * {@code atmo.snow.weather.seed}), the render clock, never any game state. {@code steady} keeps the constant level and no gusts.
 * Outputs: {@link #level} (flake count factor), {@link #windMul} (wind factor on the slider), {@link #gust} (0..1), drift (integral of the extra wind).
 */
final class SnowWeather {
    private static final float[] LEVELS = {0f, 0.3f, 1f, 1.7f};
    private static final float[] WEIGHTS = {0.20f, 0.30f, 0.35f, 0.15f};

    boolean varying;
    float period = 90f;
    float level = 1f, windMul = 1f, gust = 0f;
    /** Accumulated extra wind displacement of the flakes (tiles, x and z), see Particles u_drift. */
    double driftX, driftZ;
    /** Mean lean of the plants (wind of the weather state), also see {@link Sway}. */
    float lean = 0.45f;

    private Random rnd = new Random(System.nanoTime());
    private boolean init;
    private double segStart, segEnd, trans, last, gustStart = -1, gustEnd, nextGust;
    private float from, to, gustAmp;
    private int cat = 2;
    private double phA, phB;

    void seed(String s) {
        long v;
        try { v = s.trim().isEmpty() ? System.nanoTime() : Long.parseLong(s.trim()); } catch (NumberFormatException e) { v = System.nanoTime(); }
        rnd = new Random(v);
        init = false;
    }

    private int pick(int avoid) {
        for (int guard = 0; guard < 8; guard++) {
            float r = rnd.nextFloat(), a = 0;
            int c = 0;
            for (; c < WEIGHTS.length - 1; c++) { a += WEIGHTS[c]; if (r < a) break; }
            if (c != avoid) return c;
        }
        return 2;
    }

    /** @param now render clock, seconds (continuous); @param wind the wind slider */
    void update(double now, float wind) {
        if (!varying) { level = 1f; windMul = 1f; gust = 0f; lean = 0.45f; init = false; driftX = driftZ = 0; return; }
        if (!init) {
            init = true;
            cat = pick(-1);
            from = to = LEVELS[cat] * (0.9f + 0.2f * rnd.nextFloat());
            segStart = now;
            segEnd = now + period * (0.6 + 0.8 * rnd.nextDouble());
            trans = 0;
            gustStart = -1;
            nextGust = now + 3 + 10 * rnd.nextDouble();
            phA = rnd.nextDouble() * 6.28;
            phB = rnd.nextDouble() * 6.28;
            last = now;
        }
        while (now >= segEnd) {
            from = to;
            cat = pick(cat);
            to = LEVELS[cat] * (0.9f + 0.2f * rnd.nextFloat());
            segStart = segEnd;
            segEnd = segStart + period * (0.6 + 0.8 * rnd.nextDouble());
            trans = Math.max(4.0, Math.min(30.0, period * 0.35));
        }
        double k = trans <= 0 ? 1 : Math.min(1.0, (now - segStart) / trans);
        k = k * k * (3 - 2 * k);
        level = (float) (from + (to - from) * k);
        // gusts: one event at a time, a smooth bump of 4-9 s every 8-30 s, stronger in heavier snow; plus a faint slow breeze
        if (gustStart < 0 && now >= nextGust) {
            gustStart = now;
            gustEnd = now + 4 + 5 * rnd.nextDouble();
            gustAmp = (0.4f + 0.6f * rnd.nextFloat()) * (0.55f + 0.45f * Math.min(1.5f, level));
        }
        float g = 0f;
        if (gustStart >= 0) {
            double u = (now - gustStart) / (gustEnd - gustStart);
            if (u >= 1) { gustStart = -1; nextGust = now + 8 + 22 * rnd.nextDouble(); }
            else g = gustAmp * (float) Math.pow(Math.sin(Math.PI * u), 2);
        }
        float breeze = 0.2f * (float) Math.max(0, Math.sin(now * 0.13 + phA) * Math.sin(now * 0.071 + phB));
        gust = Math.min(1f, g + breeze);
        float base = 0.75f + 0.25f * Math.min(level, 1.7f);
        windMul = base + 0.9f * gust;
        lean = 0.45f * base + 0.7f * gust;
        double dt = Math.max(0, Math.min(0.25, now - last));
        last = now;
        double e = (windMul - 1f) * wind * dt;       // extra drift against the steady 0.9 / 0.35 flake velocity
        driftX += 0.9 * e;
        driftZ += 0.35 * e;
        if (Math.abs(driftX) > 2000) { driftX = 0; driftZ = 0; }
    }
}
