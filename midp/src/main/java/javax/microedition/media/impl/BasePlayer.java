package javax.microedition.media.impl;

import java.util.concurrent.CopyOnWriteArrayList;
import javax.microedition.media.Control;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;

/** MIDP Player state machine on top of a {@link Backend}. */
final class BasePlayer implements Player {
    private final Backend backend;
    private final String contentType;
    private final CopyOnWriteArrayList<PlayerListener> listeners = new CopyOnWriteArrayList<>();
    private volatile int state = UNREALIZED;
    private int loopCount = 1;
    private int runId;
    private static final boolean DEBUG = Boolean.getBoolean("g3.media.debug");
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> PLAYING = new java.util.concurrent.ConcurrentHashMap<>();

    /** g3.media.debug: log start/stop with the number of players currently started per content type (shows music and sfx overlap). */
    private void trace(String what, int delta) {
        if (DEBUG) {
            PLAYING.merge(contentType, delta, Integer::sum);
            System.err.println("[media] " + what + " " + contentType + " playing=" + PLAYING);
        }
    }

    BasePlayer(Backend backend, String contentType) {
        this.backend = backend;
        this.contentType = contentType;
    }

    private void checkOpen() {
        if (state == CLOSED) {
            throw new IllegalStateException("Player is closed");
        }
    }

    @Override
    public synchronized void realize() throws MediaException {
        checkOpen();
        if (state == UNREALIZED) {
            state = REALIZED;
        }
    }

    @Override
    public synchronized void prefetch() throws MediaException {
        checkOpen();
        if (state == UNREALIZED) {
            realize();
        }
        if (state == REALIZED) {
            backend.open();
            state = PREFETCHED;
        }
    }

    @Override
    public void start() throws MediaException {
        synchronized (this) {
            checkOpen();
            if (state == STARTED) {
                return;
            }
            prefetch();
            final int id = ++runId;
            state = STARTED;
            trace("start", 1);
            backend.play(loopCount, () -> ended(id));
            fire(PlayerListener.STARTED, Long.valueOf(backend.positionUs()));
        }
    }

    private void ended(int id) {
        synchronized (this) {
            if (id != runId || state != STARTED) {
                return;
            }
            runId++;
            state = PREFETCHED;
            trace("end", -1);
            backend.setPositionUs(0);
            fire(PlayerListener.END_OF_MEDIA, Long.valueOf(backend.durationUs()));
        }
    }

    @Override
    public synchronized void stop() throws MediaException {
        checkOpen();
        if (state != STARTED) {
            return;
        }
        runId++;
        backend.pause();
        state = PREFETCHED;
        trace("stop", -1);
        fire(PlayerListener.STOPPED, Long.valueOf(backend.positionUs()));
    }

    @Override
    public synchronized void deallocate() {
        checkOpen();
        if (state == STARTED) {
            runId++;
            backend.pause();
            state = PREFETCHED;
            trace("stop", -1);
            fire(PlayerListener.STOPPED, Long.valueOf(backend.positionUs()));
        }
        if (state == PREFETCHED) {
            backend.release();
            state = REALIZED;
        }
    }

    @Override
    public synchronized void close() {
        if (state == CLOSED) {
            return;
        }
        runId++;
        if (state == STARTED) {
            trace("close", -1);
        }
        backend.release();
        state = CLOSED;
        fire(PlayerListener.CLOSED, null);
    }

    @Override
    public synchronized long setMediaTime(long now) throws MediaException {
        checkOpen();
        if (state == UNREALIZED) {
            throw new IllegalStateException("Player is unrealized");
        }
        if (now < 0) {
            now = 0;
        }
        long d = backend.durationUs();
        if (d >= 0 && now > d) {
            now = d;
        }
        backend.setPositionUs(now);
        return now;
    }

    @Override
    public synchronized long getMediaTime() {
        checkOpen();
        if (state < PREFETCHED && state != STARTED) {
            return 0;
        }
        return backend.positionUs();
    }

    @Override
    public int getState() { // lock-free: the game thread must never wait on a player busy in a callback
        return state;
    }

    @Override
    public synchronized long getDuration() {
        checkOpen();
        if (state == UNREALIZED) {
            return TIME_UNKNOWN;
        }
        return backend.durationUs();
    }

    @Override
    public synchronized String getContentType() {
        checkOpen();
        if (state == UNREALIZED) {
            throw new IllegalStateException("Player is unrealized");
        }
        return contentType;
    }

    @Override
    public synchronized void setLoopCount(int count) {
        checkOpen();
        if (state == STARTED) {
            throw new IllegalStateException("Player is started");
        }
        if (count == 0) {
            throw new IllegalArgumentException("loop count 0");
        }
        loopCount = count < 0 ? -1 : count;
    }

    @Override
    public void addPlayerListener(PlayerListener l) {
        synchronized (this) {
            checkOpen();
        }
        if (l == null) {
            throw new IllegalArgumentException("listener is null");
        }
        listeners.addIfAbsent(l);
    }

    @Override
    public void removePlayerListener(PlayerListener l) {
        synchronized (this) {
            checkOpen();
        }
        if (l == null) {
            throw new IllegalArgumentException("listener is null");
        }
        listeners.remove(l);
    }

    @Override
    public Control[] getControls() {
        return new Control[0];
    }

    @Override
    public Control getControl(String controlType) {
        if (controlType == null) {
            throw new IllegalArgumentException("controlType is null");
        }
        return null;
    }

    /** Queue the event for the listener thread (called under the lock, so ordering is preserved). */
    private void fire(final String event, final Object data) {
        if (listeners.isEmpty()) {
            return;
        }
        final PlayerListener[] ls = listeners.toArray(new PlayerListener[0]);
        Events.DISPATCH.execute(() -> {
            for (PlayerListener l : ls) {
                try {
                    l.playerUpdate(this, event, data);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        });
    }
}
