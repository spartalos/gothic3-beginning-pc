package javax.microedition.lcdui;

import gothic3.platform.Platform;

public abstract class Canvas extends Displayable {
    public static final int UP = 1, LEFT = 2, RIGHT = 5, DOWN = 6, FIRE = 8, GAME_A = 9, GAME_B = 10, GAME_C = 11, GAME_D = 12;
    public static final int KEY_NUM0 = 48, KEY_NUM1 = 49, KEY_NUM2 = 50, KEY_NUM3 = 51, KEY_NUM4 = 52,
            KEY_NUM5 = 53, KEY_NUM6 = 54, KEY_NUM7 = 55, KEY_NUM8 = 56, KEY_NUM9 = 57, KEY_STAR = 42, KEY_POUND = 35;

    private volatile boolean repaintPending;
    private boolean fullScreen;

    protected Canvas() {}

    public boolean isDoubleBuffered() { return true; }
    public boolean hasPointerEvents() { return false; }
    public boolean hasPointerMotionEvents() { return false; }
    public boolean hasRepeatEvents() { return true; }
    public void setFullScreenMode(boolean mode) { fullScreen = mode; }

    public int getKeyCode(int gameAction) {
        switch (gameAction) {
            case UP: return -1;
            case DOWN: return -2;
            case LEFT: return -3;
            case RIGHT: return -4;
            case FIRE: return -5;
            case GAME_A: return KEY_NUM1;
            case GAME_B: return KEY_NUM3;
            case GAME_C: return KEY_NUM7;
            case GAME_D: return KEY_NUM9;
            default: throw new IllegalArgumentException();
        }
    }

    public int getGameAction(int keyCode) {
        switch (keyCode) {
            case -1: case KEY_NUM2: return UP;
            case -2: case KEY_NUM8: return DOWN;
            case -3: case KEY_NUM4: return LEFT;
            case -4: case KEY_NUM6: return RIGHT;
            case -5: case KEY_NUM5: return FIRE;
            case KEY_NUM1: return GAME_A;
            case KEY_NUM3: return GAME_B;
            case KEY_NUM7: return GAME_C;
            case KEY_NUM9: return GAME_D;
            default: return 0;
        }
    }

    public String getKeyName(int keyCode) {
        switch (keyCode) {
            case -1: return "UP";
            case -2: return "DOWN";
            case -3: return "LEFT";
            case -4: return "RIGHT";
            case -5: return "SELECT";
            case -6: return "SOFT1";
            case -7: return "SOFT2";
            default:
                if (keyCode >= 32 && keyCode < 127) return String.valueOf((char) keyCode);
                throw new IllegalArgumentException();
        }
    }

    protected abstract void paint(Graphics g);

    protected void keyPressed(int keyCode) {}
    protected void keyRepeated(int keyCode) {}
    protected void keyReleased(int keyCode) {}
    protected void pointerPressed(int x, int y) {}
    protected void pointerReleased(int x, int y) {}
    protected void pointerDragged(int x, int y) {}
    protected void showNotify() {}
    protected void hideNotify() {}

    public final void repaint() { repaint(0, 0, getWidth(), getHeight()); }

    public final void repaint(int x, int y, int w, int h) {
        if (!shown) return;
        if (!repaintPending) {
            repaintPending = true;
            Platform.postUi(this::doRepaint);
        }
    }

    public final void serviceRepaints() {
        if (!shown) return;
        if (Platform.onUiThread()) {
            if (repaintPending) doRepaint();
        } else if (repaintPending) {
            doRepaint();
        }
    }

    synchronized void doRepaint() {
        if (!repaintPending) return;
        repaintPending = false;
        if (!shown) return;
        int w = getWidth(), h = getHeight();
        int[] px = screenBuffer(w, h);
        Graphics g = new Graphics(px, w, h);
        paint(g);
        Platform.frames().publish(px, w, h);
    }

    private int[] screenBuf;
    private int[] screenBuffer(int w, int h) {
        if (screenBuf == null || screenBuf.length != w * h) {
            screenBuf = new int[w * h];
            java.util.Arrays.fill(screenBuf, 0xFFFFFFFF);
        }
        return screenBuf;
    }

    // package-level hooks used by DisplayHost
    void deliverShow() { shown = true; showNotify(); }
    void deliverHide() { shown = false; hideNotify(); }
    void deliverKey(int code, boolean down) {
        if (down) keyPressed(code);
        else keyReleased(code);
    }
}
