package gothic3.desktop.render3d;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.microedition.lcdui.Image;

/** MIDP Image -> GL texture (nearest, clamped), identity keyed, LRU bounded. Render thread only. */
final class TextureCache implements TexSource {
    private final LinkedHashMap<Image, Texture> map = new LinkedHashMap<Image, Texture>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Image, Texture> e) {
            if (size() > 160) {
                e.getValue().dispose();
                return true;
            }
            return false;
        }
    };
    private Texture white;

    @Override public Texture get(Image im) {
        Texture t = map.get(im);
        if (t == null) {
            t = create(im.getPixelsInternal(), im.getWidth(), im.getHeight());
            map.put(im, t);
        }
        return t;
    }

    /** 1x1 white texture for untextured quads. */
    Texture white() {
        if (white == null) white = create(new int[] {0xFFFFFFFF}, 1, 1);
        return white;
    }

    static Texture create(int[] argb, int w, int h) {
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        int[] rgba = new int[w * h];
        for (int i = 0; i < rgba.length; i++) rgba[i] = (argb[i] << 8) | (argb[i] >>> 24);
        ByteBuffer bb = pm.getPixels();
        bb.clear();
        ByteOrder old = bb.order();
        bb.order(ByteOrder.BIG_ENDIAN);
        IntBuffer ib = bb.asIntBuffer();
        ib.put(rgba);
        bb.order(old);
        bb.position(0);
        Texture t = new Texture(pm);
        pm.dispose();
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        t.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        return t;
    }

    void dispose() {
        for (Texture t : map.values()) t.dispose();
        map.clear();
        if (white != null) white.dispose();
        white = null;
    }
}
