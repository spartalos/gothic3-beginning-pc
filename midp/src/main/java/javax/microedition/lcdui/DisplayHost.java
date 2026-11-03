package javax.microedition.lcdui;

import gothic3.platform.Platform;

/**
 * NON-MIDP bridge used by the platform layer (input mapper etc.) to reach package-private
 * delivery methods in lcdui. All key delivery happens on the MIDP-UI thread.
 */
public final class DisplayHost {
    private static volatile Displayable current;

    private DisplayHost() {}

    static void setCurrentInternal(Displayable d) { current = d; }

    public static Displayable current() { return current; }

    /** Queues a key event for the current Canvas (called from any thread). */
    public static void postKey(int midpKeyCode, boolean pressed) {
        Platform.postUi(() -> deliverKey(midpKeyCode, pressed));
    }

    private static void deliverKey(int code, boolean pressed) {
        Displayable d = current;
        if (!(d instanceof Canvas)) return;
        Canvas c = (Canvas) d;
        boolean suppress = false;
        if (c instanceof javax.microedition.lcdui.game.GameCanvas) {
            javax.microedition.lcdui.game.GameCanvas gc = (javax.microedition.lcdui.game.GameCanvas) c;
            gc.trackKey(c.getGameAction(code), pressed);
            suppress = gc.isSuppressingKeyEvents();
        }
        if (!suppress) c.deliverKey(code, pressed);
    }
}
