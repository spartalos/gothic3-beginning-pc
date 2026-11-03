package javax.microedition.lcdui.game;

import gothic3.platform.Platform;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

public abstract class GameCanvas extends Canvas {
    public static final int UP_PRESSED = 1 << Canvas.UP, DOWN_PRESSED = 1 << Canvas.DOWN,
            LEFT_PRESSED = 1 << Canvas.LEFT, RIGHT_PRESSED = 1 << Canvas.RIGHT, FIRE_PRESSED = 1 << Canvas.FIRE,
            GAME_A_PRESSED = 1 << Canvas.GAME_A, GAME_B_PRESSED = 1 << Canvas.GAME_B,
            GAME_C_PRESSED = 1 << Canvas.GAME_C, GAME_D_PRESSED = 1 << Canvas.GAME_D;

    private final Image buffer;
    private final boolean suppress;
    private int pressedBits, latchedBits;

    protected GameCanvas(boolean suppressKeyEvents) {
        this.suppress = suppressKeyEvents;
        this.buffer = Image.createImage(Platform.getWidth(), Platform.getHeight());
    }

    protected Graphics getGraphics() { return buffer.getGraphics(); }

    public void flushGraphics() { publishCurrent(); }

    public void flushGraphics(int x, int y, int w, int h) { publishCurrent(); }

    /** Non-MIDP: publish the back buffer to the presentation layer. */
    public void publishCurrent() {
        Platform.frames().publish(buffer.getPixelsInternal(), buffer.getWidth(), buffer.getHeight(),
                gothic3.platform.scene3d.DrawCapture.pending()); // PORT: 3D scene of this frame (or null)
    }

    /** Non-MIDP: the live back buffer (ARGB, width*height). For verification tools; do not retain. */
    public int[] pixelsInternal() { return buffer.getPixelsInternal(); }

    public synchronized int getKeyStates() {
        int r = pressedBits | latchedBits;
        latchedBits = 0;
        return r;
    }

    public boolean isSuppressingKeyEvents() { return suppress; }

    public synchronized void trackKey(int gameAction, boolean down) {
        if (gameAction == 0) return;
        int bit = 1 << gameAction;
        if (down) { pressedBits |= bit; latchedBits |= bit; }
        else pressedBits &= ~bit;
    }

    @Override
    public void paint(Graphics g) {
        g.drawImage(buffer, 0, 0, Graphics.TOP | Graphics.LEFT);
    }
}
