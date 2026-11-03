package gothic3.desktop;

import gothic3.platform.RenderConfig;
import java.io.File;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Diagnostics only: daemon thread that notices when the game logic stops completing ticks (while not paused)
 * and writes one full thread dump (with lock owners and deadlock detection) to {@code freeze-<timestamp>.txt} in the
 * config directory. Read-only: it only reads {@link RenderConfig#ticks()}, so game behaviour and determinism are untouched.
 */
final class FreezeWatchdog {
    private static final long STALL_MS = 5000;
    private static final long POLL_MS = 500;

    private FreezeWatchdog() {
    }

    static void start() {
        Thread t = new Thread(FreezeWatchdog::run, "freeze-watchdog");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        long lastTicks = RenderConfig.ticks();
        long lastProgress = System.nanoTime();
        boolean reported = false;
        while (true) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            long ticks = RenderConfig.ticks();
            // not armed before the first tick (startup/loading), and a pause (options screen) is not a stall
            if (ticks != lastTicks || ticks == 0 || RenderConfig.paused()) {
                lastTicks = ticks;
                lastProgress = now;
                reported = false;
                continue;
            }
            if (!reported && now - lastProgress > STALL_MS * 1_000_000L) {
                reported = true;
                dump(ticks);
            }
        }
    }

    private static void dump(long ticks) {
        File dir = Config.configDir();
        dir.mkdirs();
        File f = new File(dir, "freeze-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".txt");
        try (PrintWriter w = new PrintWriter(f, StandardCharsets.UTF_8)) {
            w.println("Game logic made no progress for " + STALL_MS + " ms (ticks completed: " + ticks + ")");
            ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            long[] dl = mx.findDeadlockedThreads();
            if (dl != null) {
                w.println("DEADLOCK among " + dl.length + " threads:");
                for (ThreadInfo ti : mx.getThreadInfo(dl, true, true)) {
                    w.println("  " + ti.getThreadName() + " waits for " + ti.getLockName() + " held by " + ti.getLockOwnerName());
                }
            } else {
                w.println("No monitor/synchronizer deadlock detected.");
            }
            w.println();
            for (ThreadInfo ti : mx.dumpAllThreads(true, true, 64)) {
                w.println("\"" + ti.getThreadName() + "\" " + ti.getThreadState()
                        + (ti.getLockName() != null ? " on " + ti.getLockName() : "")
                        + (ti.getLockOwnerName() != null ? " owned by " + ti.getLockOwnerName() : ""));
                for (StackTraceElement e : ti.getStackTrace()) {
                    w.println("\tat " + e);
                }
                for (java.lang.management.LockInfo li : ti.getLockedSynchronizers()) {
                    w.println("\t- holds synchronizer " + li);
                }
                w.println();
            }
        } catch (Exception e) {
            System.err.println("[watchdog] cannot write " + f + ": " + e);
            return;
        }
        System.err.println("[watchdog] game logic stalled for " + STALL_MS + " ms, thread dump written to " + f);
    }
}
