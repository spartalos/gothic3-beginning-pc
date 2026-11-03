package gothic3.platform;

/**
 * Frame-pacing hand-off between the presentation layer and the game thread (libGDX-free).
 *
 * The presenter calls {@link #frameWanted()} once per presented frame (i.e. once per vsync). The game
 * thread waits in {@link #await(long)} for that signal (or a deadline, whichever comes first), renders
 * one frame and publishes it through {@link FrameBuffer}. Signals do not queue: if the game thread is
 * slower than the display, several vsyncs collapse into one pending signal.
 */
public final class FramePacer {
    private final Object lock = new Object();
    private boolean wanted;
    private boolean wake;
    private volatile boolean followDisplay;

    /** True if the presenter drives frame production (vsync); false: the game thread paces itself (maxFps). */
    public boolean followDisplay() { return followDisplay; }

    public void setFollowDisplay(boolean b) { followDisplay = b; }

    /** Presenter side: a new frame is about to be displayed, produce the next one. Never blocks. */
    public void frameWanted() {
        synchronized (lock) {
            wanted = true;
            lock.notifyAll();
        }
    }

    /** Wakes a waiting game thread without a frame signal (shutdown, mode change). */
    public void wake() {
        synchronized (lock) {
            wake = true;
            lock.notifyAll();
        }
    }

    /**
     * Game side: blocks until a frame was wanted or {@code deadlineNanos} (System.nanoTime base) passed.
     * Consumes a pending signal. Returns true if a frame was wanted, false on timeout / wake.
     */
    public boolean await(long deadlineNanos) {
        synchronized (lock) {
            for (;;) {
                if (wanted) {
                    wanted = false;
                    return true;
                }
                if (wake) {
                    wake = false;
                    return false;
                }
                long left = deadlineNanos - System.nanoTime();
                if (left <= 0) return false;
                try {
                    lock.wait(left / 1_000_000L, (int) (left % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    /** Game side: timed wait that ignores frame signals (but is cut short by {@link #wake()}). */
    public void sleepUntil(long deadlineNanos) {
        synchronized (lock) {
            for (;;) {
                if (wake) {
                    wake = false;
                    return;
                }
                long left = deadlineNanos - System.nanoTime();
                if (left <= 0) return;
                try {
                    lock.wait(left / 1_000_000L, (int) (left % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Discards a pending frame signal (call after a long tick so stale vsyncs do not trigger a burst). */
    public void clear() {
        synchronized (lock) { wanted = false; }
    }
}
