package gothic3.platform;

/**
 * Hand-off point between the game thread (publishes completed frames on
 * flushGraphics) and any presentation layer (libGDX, PNG dumper, tests).
 * The producer copies into the front buffer under a lock; consumers copy out
 * under the same lock, so a half-drawn frame is never visible.
 */
public final class FrameBuffer {
    private final Object lock = new Object();
    private int[] front = new int[0];
    private int w, h;
    private long serial;
    private gothic3.platform.scene3d.Scene3D scene; // PORT: 3D scene belonging to the front frame (null = plain opaque 2D frame)
    private TextLayer.Op[] text = TextLayer.NONE;   // PORT: HD text recorded for the front frame (drawn by the presenter, not in the pixels)

    /** Copies a completed ARGB frame (opaque) into the front buffer. */
    public void publish(int[] argb, int width, int height) { publish(argb, width, height, null); }

    /** As above, plus the 3D scene the (transparent where the world is) frame belongs to. */
    public void publish(int[] argb, int width, int height, gothic3.platform.scene3d.Scene3D scene3d) {
        publish(argb, width, height, scene3d, TextLayer.NONE);
    }

    /** As above, plus the HD text ops that belong to the frame ({@link TextLayer}). */
    public void publish(int[] argb, int width, int height, gothic3.platform.scene3d.Scene3D scene3d, TextLayer.Op[] textOps) {
        synchronized (lock) {
            this.scene = scene3d;
            this.text = textOps == null ? TextLayer.NONE : textOps;
            int n = width * height;
            if (front.length != n) front = new int[n];
            System.arraycopy(argb, 0, front, 0, n);
            w = width;
            h = height;
            serial++;
        }
    }

    public int width() { synchronized (lock) { return w; } }
    public int height() { synchronized (lock) { return h; } }
    public long serial() { synchronized (lock) { return serial; } }

    /**
     * If a newer frame than lastSerial exists, copies it to dst (must hold at least w*h ints;
     * call width()/height() first or use {@link #snapshot}) and returns its serial; otherwise
     * returns lastSerial and leaves dst untouched.
     */
    public long copyIfNewer(long lastSerial, int[] dst) {
        synchronized (lock) {
            if (serial == lastSerial || dst.length < front.length) return lastSerial;
            System.arraycopy(front, 0, dst, 0, front.length);
            if (text.length > 0) javax.microedition.lcdui.Graphics.burnText(dst, w, h, text); // screenshots: the text in logical resolution
            return serial;
        }
    }

    /** As {@link #copyIfNewer(long, int[])}; also stores the scene of the copied frame in {@code sceneOut[0]}. */
    public long copyIfNewer(long lastSerial, int[] dst, gothic3.platform.scene3d.Scene3D[] sceneOut) {
        return copyIfNewer(lastSerial, dst, sceneOut, null);
    }

    /** As above; also stores the HD text ops of the copied frame in {@code textOut[0]} (when given). */
    public long copyIfNewer(long lastSerial, int[] dst, gothic3.platform.scene3d.Scene3D[] sceneOut, TextLayer.Op[][] textOut) {
        synchronized (lock) {
            if (serial == lastSerial || dst.length < front.length) return lastSerial;
            System.arraycopy(front, 0, dst, 0, front.length);
            sceneOut[0] = scene;
            if (textOut != null) textOut[0] = text;
            return serial;
        }
    }

    /** Returns {width, height, serial} atomically. */
    public long[] info() {
        synchronized (lock) { return new long[] {w, h, serial}; }
    }
}
