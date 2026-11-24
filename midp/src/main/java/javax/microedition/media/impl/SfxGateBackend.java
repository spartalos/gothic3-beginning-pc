package javax.microedition.media.impl;

import javax.microedition.media.MediaException;

/**
 * Wraps the backend of a sound-effect (AMR) player. While effects are disabled (options screen, --no-sfx) a play-through
 * runs on a silent timer instead of the real backend, so player state, positions and events stay identical.
 * The choice is made per play(); a play-through already running keeps its backend.
 */
final class SfxGateBackend implements Backend {
    private final Backend real;
    private final Backend silent;
    private volatile Backend active;

    SfxGateBackend(Backend real, long durationUs) {
        this.real = real;
        this.silent = new SilentBackend(durationUs);
        this.active = real;
    }

    @Override
    public void open() throws MediaException {
        real.open();
    }

    @Override
    public void play(int loops, Runnable onEnd) {
        Backend next = PlayerFactory.sfxEnabled() ? real : silent;
        if (next != active) {
            long pos = active.positionUs();
            active.pause();
            active = next;
            active.setPositionUs(pos);
        }
        if (PlayerFactory.DEBUG) {
            System.err.println("[media] sfx play via " + (next == real ? "audio" : "MUTED (sound effects off)"));
        }
        active.play(loops, onEnd);
    }

    @Override
    public void pause() {
        active.pause();
    }

    @Override
    public void release() {
        real.release();
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
