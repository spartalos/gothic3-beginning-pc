package javax.microedition.media.impl;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.microedition.media.MediaException;

/**
 * Runs every call into the real audio device on one shared daemon thread, so a stuck JDK sound
 * implementation can at worst silence audio and never block the game thread. Commands are ordered;
 * state-changing ones are fire-and-forget, queries wait a bounded time and fall back to the last known value.
 */
final class AsyncBackend implements Backend {
    private static final ExecutorService AUDIO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "media-audio");
        t.setDaemon(true);
        return t;
    });
    static final long OPEN_TIMEOUT_MS = 5000;
    static final long QUERY_TIMEOUT_MS = 200;

    private final Backend delegate;
    private volatile long lastPosUs;

    AsyncBackend(Backend delegate) {
        this.delegate = delegate;
    }

    private void post(String what, Runnable r) {
        try {
            AUDIO.execute(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    System.err.println("[media] " + what + " failed: " + t);
                }
            });
        } catch (RuntimeException e) {
            System.err.println("[media] " + what + " rejected: " + e);
        }
    }

    @Override
    public void open() throws MediaException {
        Future<Object> f = AUDIO.submit((Callable<Object>) () -> {
            delegate.open();
            return null;
        });
        try {
            f.get(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            System.err.println("[media] audio backend busy, continuing without waiting for open");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof MediaException) {
                throw (MediaException) e.getCause();
            }
            throw new MediaException("open failed: " + e.getCause());
        }
    }

    @Override
    public void play(int loops, Runnable onEnd) {
        post("play", () -> delegate.play(loops, onEnd));
    }

    @Override
    public void pause() {
        post("pause", delegate::pause);
    }

    @Override
    public void release() {
        post("release", delegate::release);
    }

    @Override
    public long durationUs() {
        return delegate.durationUs();
    }

    @Override
    public long positionUs() {
        try {
            long p = AUDIO.submit(delegate::positionUs).get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            lastPosUs = p;
            return p;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // timeout / failure: last known value
        }
        return lastPosUs;
    }

    @Override
    public void setPositionUs(long us) {
        lastPosUs = us;
        post("setPosition", () -> delegate.setPositionUs(us));
    }
}
