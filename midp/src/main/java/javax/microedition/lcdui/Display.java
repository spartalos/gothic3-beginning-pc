package javax.microedition.lcdui;

import gothic3.platform.Platform;
import javax.microedition.midlet.MIDlet;

public class Display {
    public static final int LIST_ELEMENT = 1, CHOICE_GROUP_ELEMENT = 2, ALERT = 3, COLOR_BACKGROUND = 0,
            COLOR_FOREGROUND = 1, COLOR_HIGHLIGHTED_BACKGROUND = 2, COLOR_HIGHLIGHTED_FOREGROUND = 3,
            COLOR_BORDER = 4, COLOR_HIGHLIGHTED_BORDER = 5;

    private static Display instance;
    private Displayable current;

    private Display() {}

    public static synchronized Display getDisplay(MIDlet m) {
        if (m == null) throw new NullPointerException();
        if (instance == null) instance = new Display();
        return instance;
    }

    public Displayable getCurrent() { return current; }

    public void setCurrent(Displayable d) {
        if (d == null) return;
        Displayable old;
        synchronized (this) {
            old = current;
            current = d;
        }
        DisplayHost.setCurrentInternal(d);
        if (old != d) {
            if (old instanceof Canvas) ((Canvas) old).deliverHide();
            if (d instanceof Canvas) {
                Canvas c = (Canvas) d;
                c.deliverShow();
                if (c instanceof javax.microedition.lcdui.game.GameCanvas) {
                    ((javax.microedition.lcdui.game.GameCanvas) c).publishCurrent();
                } else {
                    c.repaint();
                }
            }
        }
    }

    public void setCurrentItem(Item i) {}
    public void callSerially(Runnable r) { Platform.postUi(r); }
    public boolean isColor() { return true; }
    public int numColors() { return 1 << 24; }
    public int numAlphaLevels() { return 256; }
    public int getColor(int c) { return c == COLOR_BACKGROUND ? 0xFFFFFF : 0; }
    public int getBorderStyle(boolean h) { return Graphics.SOLID; }
    public boolean flashBacklight(int d) { return false; }
    public boolean vibrate(int d) { return false; }
}
