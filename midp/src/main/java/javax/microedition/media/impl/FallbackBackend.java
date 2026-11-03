package javax.microedition.media.impl;

import javax.microedition.media.MediaException;

/** Uses the real device backend if it opens; otherwise degrades to a silent timer. */
final class FallbackBackend implements Backend {
    private final Backend primary;
    private final Backend silent;
    private volatile Backend active;

    FallbackBackend(Backend primary, long durationUs) {
        this.primary = primary;
        this.silent = new SilentBackend(durationUs);
        this.active = primary;
    }

    @Override
    public void open() {
        try {
            primary.open();
            active = primary;
        } catch (Throwable t) {
            System.err.println("[media] audio device unavailable, playing silently: " + t);
            active = silent;
        }
    }

    @Override
    public void play(int loops, Runnable onEnd) {
        active.play(loops, onEnd);
    }

    @Override
    public void pause() {
        active.pause();
    }

    @Override
    public void release() {
        primary.release();
        silent.release();
    }

    @Override
    public long durationUs() {
        return active.durationUs();
    }

    @Override
    public long positionUs() {
        return active.positionUs();
    }

    @Override
    public void setPositionUs(long us) {
        active.setPositionUs(us);
    }
}
