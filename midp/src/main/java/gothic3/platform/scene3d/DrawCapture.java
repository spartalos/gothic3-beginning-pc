package gothic3.platform.scene3d;

import java.util.Arrays;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * Game-thread draw-call capture for the 3D renderer. While a target {@link Graphics} is in capture mode its
 * drawing primitives are not rasterised: image-region draws and filled rects are recorded instead (see
 * {@link Scene3D}). The game code marks passes and entity anchors with a few {@code // PORT:} hooks.
 * Everything here is touched by the game thread only, except {@link #enabled} and {@link #takePending}.
 */
public final class DrawCapture {
    private DrawCapture() {}

    /** Master switch (F5 / config render.view): whether the game should capture world frames at all. */
    public static volatile boolean enabled;

    /** Recorded passes in addition to Scene3D.PASS_*: draws that are dropped (background buffer, tile layers). */
    public static final int PASS_IGNORE = -1;

    private static Graphics target;
    private static int pass = PASS_IGNORE;
    private static boolean hasAnchor;
    private static boolean anchorLocked, iconFlag;   // PORT: overhead icons keep the owner's feet as anchor
    private static int anchorX, anchorY;       // screen coords
    private static int camX, camY, screenW, screenH;
    private static int[] buf = new int[Scene3D.STRIDE * 512];
    private static Image[] imgs = new Image[512];
    private static int n;
    private static long frameNo;
    private static volatile Scene3D pending;
    private static boolean hasFocus;
    private static float focusX, focusY;

    /** Camera look-at target for this frame (the player's feet, world pixels); call between beginWorld and finish. */
    public static void focus(boolean valid, float x, float y) {
        hasFocus = valid;
        focusX = x;
        focusY = y;
    }

    /** Start of every render: forget the scene of the previous frame (non-world frames publish none). */
    public static void beginFrame() {
        pending = null;
        n = 0;
        if (target != null) { // a previous render was aborted half way: never leave the screen suppressed
            target.setCapture(false);
            target = null;
        }
        pass = PASS_IGNORE;
        hasAnchor = false;
    }

    /**
     * Start of the world pass. Returns false (and leaves the screen alone) when 3D is off. Otherwise the screen is
     * cleared to transparent and {@code g} switches to capture mode.
     */
    public static boolean beginWorld(Graphics g, int camXpx, int camYpx, int sw, int sh) {
        if (!enabled) return false;
        g.clearTransparent();
        g.setCapture(true);
        target = g;
        camX = camXpx;
        camY = camYpx;
        screenW = sw;
        screenH = sh;
        n = 0;
        hasFocus = false;
        pass = PASS_IGNORE;
        hasAnchor = false;
        return true;
    }

    /** Leaves capture mode (HUD and UI draw normally afterwards). */
    public static void suspend() {
        if (target != null) target.setCapture(false);
        pass = PASS_IGNORE;
        hasAnchor = false;
    }

    /** World anchored UI (icons over heads): capture again. */
    public static void resume(int p) {
        if (target == null) return;
        target.setCapture(true);
        pass = p;
        hasAnchor = false;
    }

    /**
     * World UI that marks an entity (interaction icon, target reticle): anchors the following draws at the entity's feet (screen
     * coordinates) instead of at the icon's own 2D position, so the 3D view stands the icon up at its pixel height above the feet.
     * {@code icon} also flags the draws for the renderer's lift / scale. Call {@link #markerEnd} afterwards. No-op unless capturing the overlay.
     */
    public static void markerAnchor(int feetSx, int feetSy, boolean icon) {
        if (target == null || pass != Scene3D.PASS_OVERLAY) return;
        anchorLocked = false;
        anchor(feetSx, feetSy);
        anchorLocked = true;
        iconFlag = icon;
    }

    public static void markerEnd() { anchorLocked = false; iconFlag = false; hasAnchor = false; }

    public static boolean active() { return target != null; }

    public static void pass(int p) { pass = p; hasAnchor = false; anchorLocked = false; iconFlag = false; }

    /** The entity anchor (screen coordinates = the point the 2D code positions the sprite relative to). */
    public static void anchor(int sx, int sy) {
        if (anchorLocked) return;
        hasAnchor = true;
        anchorX = sx;
        anchorY = sy;
    }

    /** Ends the capture and stores the scene for the next {@code publishCurrent}. */
    public static void finish(MapSnapshot map) {
        if (target == null) return;
        target.setCapture(false);
        target = null;
        pass = PASS_IGNORE;
        hasAnchor = false;
        pending = new Scene3D(++frameNo, screenW, screenH, camX, camY, hasFocus, focusX, focusY, map, n, Arrays.copyOf(buf, n * Scene3D.STRIDE),
                Arrays.copyOf(imgs, n));
        n = 0;
    }

    /** Publishes an earlier scene again with the current (2D only) frame, e.g. under a dialogue. */
    public static void hold(Scene3D s) { pending = s; }

    /** Called by the frame publisher: the scene that belongs to the frame being published (null for 2D frames). */
    public static Scene3D pending() { return pending; }

    // ---- recording (called from Graphics while in capture mode) ----

    private static int slot() {
        if ((n + 1) * Scene3D.STRIDE > buf.length) {
            buf = Arrays.copyOf(buf, buf.length * 2);
            imgs = Arrays.copyOf(imgs, imgs.length * 2);
        }
        return n++;
    }

    /** Image region; (dx, dy) is the destination top-left in screen coordinates after anchor handling, tw/th the size. */
    public static void region(Image src, int xs, int ys, int w, int h, int transform, int dx, int dy, int tw, int th) {
        if (pass == PASS_IGNORE || n > 20000) return;
        int s = slot();
        int o = s * Scene3D.STRIDE;
        imgs[s] = src;
        buf[o] = Scene3D.KIND_IMAGE | pass << 8;
        buf[o + 1] = xs;
        buf[o + 2] = ys;
        buf[o + 3] = w;
        buf[o + 4] = h;
        buf[o + 5] = transform;
        place(o, dx, dy, tw, th);
        buf[o + 10] = 0;
        buf[o + 11] = iconFlag ? 1 : 0;
    }

    public static void rect(int argb, int dx, int dy, int w, int h) {
        if (pass == PASS_IGNORE || n > 20000) return;
        int s = slot();
        int o = s * Scene3D.STRIDE;
        imgs[s] = null;
        buf[o] = Scene3D.KIND_RECT | pass << 8;
        buf[o + 1] = 0;
        buf[o + 2] = 0;
        buf[o + 3] = w;
        buf[o + 4] = h;
        buf[o + 5] = 0;
        place(o, dx, dy, w, h);
        buf[o + 10] = argb;
        buf[o + 11] = 0;
    }

    private static void place(int o, int dx, int dy, int tw, int th) {
        int ax, ay;
        if (hasAnchor) {
            ax = anchorX;
            ay = anchorY;
        } else { // bottom centre of the drawn rectangle
            ax = dx + tw / 2;
            ay = dy + th;
        }
        buf[o + 6] = dx - ax;
        buf[o + 7] = dy - ay;
        buf[o + 8] = ax + camX;
        buf[o + 9] = ay + camY;
    }
}
