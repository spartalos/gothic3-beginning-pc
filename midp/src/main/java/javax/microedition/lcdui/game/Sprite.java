package javax.microedition.lcdui.game;

/** Only the transform constants are provided (the game uses Graphics.drawRegion directly). */
public class Sprite {
    public static final int TRANS_NONE = 0, TRANS_ROT90 = 5, TRANS_ROT180 = 3, TRANS_ROT270 = 6,
            TRANS_MIRROR = 2, TRANS_MIRROR_ROT90 = 7, TRANS_MIRROR_ROT180 = 1, TRANS_MIRROR_ROT270 = 4;

    private Sprite() {}
}
