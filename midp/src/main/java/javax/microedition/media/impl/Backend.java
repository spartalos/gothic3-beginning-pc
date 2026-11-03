package javax.microedition.media.impl;

import javax.microedition.media.MediaException;

/** Playback engine behind a {@link BasePlayer}. All calls are made under the player's lock. */
interface Backend {
    /** Acquire devices / preload data (prefetch). */
    void open() throws MediaException;

    /**
     * Start or resume playback. {@code loops} is the total number of play-throughs (-1 = infinite).
     * {@code onEnd} must be invoked (from any thread, not under the player lock) once all
     * play-throughs finish naturally, and never after pause()/release().
     */
    void play(int loops, Runnable onEnd);

    /** Pause, keeping the media position. */
    void pause();

    /** Release devices (deallocate / close); position is reset. */
    void release();

    /** Duration of a single play-through in microseconds, or -1. */
    long durationUs();

    long positionUs();

    void setPositionUs(long us);
}
