package javax.microedition.media.impl;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** No audio; just simulates the passage of time so events fire correctly. */
final class SilentBackend implements Backend {
    private final long durationUs;
    private long baseUs;
    private long startNanos;
    private boolean running;
    private ScheduledFuture<?> task;

    SilentBackend(long durationUs) {
        this.durationUs = Math.max(durationUs, 0);
    }

    @Override
    public void open() {
    }

    @Override
    public synchronized void play(int loops, Runnable onEnd) {
        cancel();
        running = true;
        startNanos = System.nanoTime();
        if (loops < 0) {
            return;
        }
        long remaining = durationUs * loops - baseUs;
        task = Events.TIMER.schedule(onEnd, Math.max(remaining, 0), TimeUnit.MICROSECONDS);
    }

    private void cancel() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    @Override
    public synchronized void pause() {
        baseUs = positionUs();
        running = false;
        cancel();
    }

    @Override
    public synchronized void release() {
        running = false;
        cancel();
        baseUs = 0;
    }

    @Override
    public long durationUs() {
        return durationUs;
    }

    @Override
    public synchronized long positionUs() {
        long p = baseUs;
        if (running) {
            p += (System.nanoTime() - startNanos) / 1000;
        }
        return durationUs > 0 ? p % (durationUs + 1) : 0;
    }

    @Override
    public synchronized void setPositionUs(long us) {
        baseUs = us;
        startNanos = System.nanoTime();
    }
}
