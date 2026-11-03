package gothic3.desktop.render3d.post;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import gothic3.desktop.Config;
import java.io.File;
import java.io.InputStream;

/** 16x16x16 colour LUT strips (256x16 PNG: 16 blue slices left to right, red along x, green down). */
public final class Lut {
    private Lut() { }

    /** Loads ~/.config/gothic3-pc/luts/NAME.png, else the built-in post/luts/NAME.png; null if missing. */
    public static Texture load(String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("..")) return null;
        try {
            File f = new File(new File(Config.configDir(), "luts"), name + ".png");
            Pixmap pm;
            if (f.isFile()) {
                pm = new Pixmap(new com.badlogic.gdx.files.FileHandle(f));
            } else {
                try (InputStream in = Lut.class.getClassLoader().getResourceAsStream("post/luts/" + name + ".png")) {
                    if (in == null) { System.err.println("[post] LUT '" + name + "' not found"); return null; }
                    byte[] b = in.readAllBytes();
                    pm = new Pixmap(b, 0, b.length);
                }
            }
            if (pm.getWidth() != 256 || pm.getHeight() != 16) {
                System.err.println("[post] LUT '" + name + "' must be a 256x16 strip, got " + pm.getWidth() + "x" + pm.getHeight());
                pm.dispose();
                return null;
            }
            Texture t = new Texture(pm);
            pm.dispose();
            t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            t.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
            return t;
        } catch (Throwable e) {
            System.err.println("[post] LUT '" + name + "': " + e);
            return null;
        }
    }
}
