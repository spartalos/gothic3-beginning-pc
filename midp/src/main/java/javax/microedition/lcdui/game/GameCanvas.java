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

    private final gothic3.platform.TextLayer text = new gothic3.platform.TextLayer();   // PORT: HD text recorded on the screen buffer

    protected Graphics getGraphics() {
        Graphics g = buffer.getGraphics();
        g.setTextLayer(text);
        return g;
    }

    public void flushGraphics() { publishCurrent(); }

    public void flushGraphics(int x, int y, int w, int h) { publishCurrent(); }

    /** Non-MIDP: publish the back buffer to the presentation layer. */
    public void publishCurrent() {
        if (!gothic3.platform.TextLayer.enabled && text.size() > 0) flushText(); // HD text switched off: the old look from the next frame on
        Platform.frames().publish(buffer.getPixelsInternal(), buffer.getWidth(), buffer.getHeight(),
                gothic3.platform.scene3d.DrawCapture.pending(), // PORT: 3D scene of this frame (or null)
                text.snapshot());                               // PORT: HD text of this frame
    }

    private void flushText() {
        Graphics g = getGraphics();
        g.flushAllText();
    }

    /** Non-MIDP: the live back buffer (ARGB, width*height) with all text rasterised into it. For verification tools; do not retain. */
    public int[] pixelsInternal() {
        if (text.size() > 0) flushText();
        return buffer.getPixelsInternal();
    }

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
