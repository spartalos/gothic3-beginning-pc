package gothic3.platform.scene3d;

import javax.microedition.lcdui.Image;

/**
 * Immutable per-frame snapshot for the optional 3D renderer. Published by the game thread together with the
 * 2D frame (which then contains only HUD / UI, transparent where the world would be) through
 * {@link gothic3.platform.FrameBuffer}.
 *
 * <p>Draw records ({@link #STRIDE} ints each, in the game's painter order):
 * <pre>
 *  0 kind | pass &lt;&lt; 8     kind: 0 image region, 1 filled rect;  pass: PASS_*
 *  1..5  srcX srcY w h transform   (image: source region + MIDP transform; rect: unused, w/h = size in screen px)
 *  6,7   ox oy    top-left of the destination rectangle relative to the anchor, in screen pixels (y down)
 *  8,9   ax ay    anchor (the entity's feet) in world pixels
 *  10    argb colour (rects)
 *  11    reserved
 * </pre>
 */
public final class Scene3D {
    public static final int STRIDE = 12;
    public static final int KIND_IMAGE = 0, KIND_RECT = 1;
    /** Sprite lying on the ground (object ground layer, blob shadows): drawn flat, before the upright sprites. */
    public static final int PASS_FLAT = 0;
    /** Upright sprite standing on the ground at its anchor (characters, objects, items, effects). */
    public static final int PASS_UPRIGHT = 1;
    /** World anchored UI (interaction icon, target reticle): upright, drawn last, ignoring depth. */
    public static final int PASS_OVERLAY = 2;

    public final long frameNo;
    public final int screenW, screenH;
    /** Game camera (top-left of the 2D view) in world pixels, interpolated like the 2D frame. */
    public final int camX, camY;
    /** Player's feet (interpolated like the 2D frame) in world pixels; valid only if {@link #hasFocus}. */
    public final float focusX, focusY;
    public final boolean hasFocus;
    public final MapSnapshot map;
    public final int count;
    public final int[] d;
    public final Image[] img;

    public Scene3D(long frameNo, int screenW, int screenH, int camX, int camY, boolean hasFocus, float focusX, float focusY, MapSnapshot map, int count, int[] d, Image[] img) {
        this.frameNo = frameNo;
        this.screenW = screenW;
        this.screenH = screenH;
        this.camX = camX;
        this.camY = camY;
        this.hasFocus = hasFocus;
        this.focusX = focusX;
        this.focusY = focusY;
        this.map = map;
        this.count = count;
        this.d = d;
        this.img = img;
    }
}
