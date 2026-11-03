package javax.microedition.media.impl;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Shared daemon threads: one ordered thread for listener callbacks, one for timers. */
final class Events {
    static final ExecutorService DISPATCH = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "media-events");
        t.setDaemon(true);
        return t;
    });

    static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "media-timer");
        t.setDaemon(true);
        return t;
    });

    private Events() {
    }
}
