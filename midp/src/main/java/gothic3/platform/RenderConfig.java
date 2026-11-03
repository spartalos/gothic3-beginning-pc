package gothic3.platform;

/**
 * Render / tick configuration and live statistics, shared between the game thread (writes stats) and
 * the presenter (reads stats, writes config). libGDX-free.
 */
public final class RenderConfig {
    private RenderConfig() {}

    /** Original behaviour: one frame per 10 Hz logic tick. */
    public static final int CLASSIC = 0;
    /** Logic stays at 10 Hz, frames are rendered at display rate with interpolated motion. */
    public static final int INTERPOLATED = 1;

    private static volatile int mode = parseMode(System.getProperty("g3.render.mode", "interpolated"));
    private static volatile int maxFps = Integer.getInteger("g3.render.maxFps", 0);

    public static int parseMode(String s) {
        return s != null && s.trim().equalsIgnoreCase("classic") ? CLASSIC : INTERPOLATED;
    }

    public static int mode() { return mode; }
    public static boolean interpolated() { return mode == INTERPOLATED; }
    public static void setMode(int m) { mode = m; }
    /** 0 = follow display / vsync. */
    public static int maxFps() { return maxFps; }
    public static void setMaxFps(int f) { maxFps = Math.max(0, f); }

    // ---- pause (in-game options screen): while set, no logic ticks run; the last frame stays on screen ----
    private static volatile boolean paused;
    private static volatile long pauseBeganMs;
    private static volatile long pausedTotalMs;

    public static boolean paused() { return paused; }

    /** Pauses / resumes the game loops. The wall clock offset ({@link #pauseOffsetMs}) keeps game timers from jumping on resume. */
    public static synchronized void setPaused(boolean p) {
        if (p == paused) return;
        long now = System.currentTimeMillis();
        if (p) pauseBeganMs = now;
        else pausedTotalMs += now - pauseBeganMs;
        paused = p;
        Platform.pacer().wake();
    }

    /** Milliseconds of wall-clock time spent paused so far (frozen while paused): subtracted from the game's wall clock. */
    public static synchronized long pauseOffsetMs() {
        return pausedTotalMs + (paused ? System.currentTimeMillis() - pauseBeganMs : 0);
    }

    // ---- fast forward: logic runs `speed` times faster (docs/platform.md "Fast forward"); ignored in deterministic mode ----
    private static final boolean DETERMINISTIC = Boolean.getBoolean("g3.deterministic");
    private static volatile int speed = 1;
    private static long vWallMs, vGameMs; // virtual clock anchor: game time = vGameMs + (pause-corrected wall - vWallMs) * speed

    /** Logic speed multiplier, 1 = normal (always 1 in deterministic mode). */
    public static int speed() { return speed; }

    public static synchronized void setSpeed(int s) {
        s = DETERMINISTIC ? 1 : Math.max(1, Math.min(16, s));
        if (s == speed) return;
        long w = System.currentTimeMillis() - pauseOffsetMs();
        vGameMs = vGameMs + (w - vWallMs) * speed; // accumulate at the change: the clock stays monotonic and continuous
        vWallMs = w;
        speed = s;
    }

    /** The game's wall clock (non-deterministic mode): wall time minus options-screen pauses, scaled by {@link #speed}. */
    public static synchronized long gameMillis() {
        long w = System.currentTimeMillis() - pauseOffsetMs();
        return vGameMs + (w - vWallMs) * speed;
    }

    // ---- 3D view (docs/render3d.md) ----
    private static volatile boolean view3d;
    public static boolean view3d() { return view3d; }
    public static void setView3d(boolean on) {
        view3d = on;
        gothic3.platform.scene3d.DrawCapture.enabled = on;
    }
    public static void toggleView3d() { setView3d(!view3d); }

    // ---- 3D capture area (docs/render3d.md "Capture area"): render hint only, never read by game logic ----
    private static volatile int[] captureHint;                              // GL thread -> game thread: {x0, y0, x1, y1} in map cells (x1/y1 exclusive)
    private static volatile int[] captureMargin = {8, 16, 8, 8};           // fixed margins (cells) west, north, east, south around the 2D window
    public static void setCaptureHint(int x0, int y0, int x1, int y1) { captureHint = new int[] {x0, y0, x1, y1}; }
    public static int[] captureHint() { return captureHint; }
    public static void clearCaptureHint() { captureHint = null; }
    public static void setCaptureMargin(int w, int n, int e, int s) { captureMargin = new int[] {w, n, e, s}; }
    public static int[] captureMargin() { return captureMargin; }

    // ---- dev tool: map warp requested by the script runner (-Dg3.debug=true), executed by the game thread at the next tick ----
    private static volatile int[] warp;
    public static void requestWarp(int area, int x, int y) { warp = new int[] {area, x, y}; }
    public static int[] takeWarp() { int[] w = warp; if (w != null) warp = null; return w; }

    // ---- statistics (volatile, written by the game thread only) ----
    private static volatile long ticks;
    private static volatile long framesRendered;
    private static volatile long intermediateFrames;
    private static volatile long renderNanosAvg;  // exponential moving average of one frame render
    private static volatile long tickNanosAvg;    // exponential moving average of one logic tick (incl. canonical render)
    private static final Object TICK_LOCK = new Object();

    public static long ticks() { return ticks; }
    public static long framesRendered() { return framesRendered; }
    public static long intermediateFrames() { return intermediateFrames; }
    public static long renderNanosAvg() { return renderNanosAvg; }
    public static long tickNanosAvg() { return tickNanosAvg; }

    /** Game thread: one logic tick (and its canonical render) completed. Wakes script waiters. */
    public static void tickDone(long tickNanos) {
        synchronized (TICK_LOCK) {
            ticks++;
            tickNanosAvg = tickNanosAvg == 0 ? tickNanos : (tickNanosAvg * 15 + tickNanos) / 16;
            TICK_LOCK.notifyAll();
        }
    }

    public static void frameDone(boolean intermediate, long renderNanos) {
        framesRendered++;
        if (intermediate) intermediateFrames++;
        renderNanosAvg = renderNanosAvg == 0 ? renderNanos : (renderNanosAvg * 15 + renderNanos) / 16;
    }

    /** Automation: blocks until at least {@code target} ticks completed; returns false on timeout. */
    public static boolean awaitTicks(long target, long timeoutMs) {
        long end = System.nanoTime() + timeoutMs * 1_000_000L;
        synchronized (TICK_LOCK) {
            while (ticks < target) {
                long left = end - System.nanoTime();
                if (left <= 0) return false;
                try {
                    TICK_LOCK.wait(left / 1_000_000L + 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }
}
