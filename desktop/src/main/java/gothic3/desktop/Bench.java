package gothic3.desktop;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/**
 * Dev-only frame cost benchmark (script command {@code bench N label}). The GL thread brackets Renderer3D.render with
 * glFinish, so the time is the real CPU+GPU cost of the 3D frame (not the vsync / FPS-cap paced interval). Prints the
 * average, 95th percentile and the implied uncapped fps. Never touches game logic or timing.
 */
public final class Bench {
    private Bench() { }

    /** Renders per presented frame while benchmarking (the first one of each batch is not recorded). */
    public static final int BATCH = 6;

    private static volatile boolean active;
    private static long[] t;
    private static int n, want;
    private static String label = "";
    private static CountDownLatch done;

    /** Script thread: runs a benchmark of N presented 3D frames and blocks until it is finished. */
    public static void run(int frames, String name) throws InterruptedException {
        CountDownLatch d = new CountDownLatch(1);
        synchronized (Bench.class) {
            t = new long[frames];
            n = 0;
            want = frames;
            label = name;
            done = d;
            active = true;
        }
        d.await();
    }

    public static boolean active() { return active; }

    /** GL thread, around the 3D render: returns the start stamp. */
    public static long begin() { return System.nanoTime(); }

    /** GL thread, after glFinish. */
    public static void end(long t0, boolean record) {
        long dt = System.nanoTime() - t0;
        synchronized (Bench.class) {
            if (!active || !record) return;
            t[n++] = dt;
            if (n < want) return;
            active = false;
            long[] s = t.clone();
            Arrays.sort(s);
            double sum = 0;
            for (long v : s) sum += v;
            double avg = sum / want / 1e6, p95 = s[Math.min(want - 1, (int) (want * 0.95))] / 1e6, p50 = s[want / 2] / 1e6;
            System.err.println(String.format(java.util.Locale.ROOT, "[bench] %s frames=%d avg_ms=%.3f p50_ms=%.3f p95_ms=%.3f max_ms=%.3f fps=%.0f",
                    label, want, avg, p50, p95, s[want - 1] / 1e6, 1000.0 / avg));
            done.countDown();
        }
    }
}
