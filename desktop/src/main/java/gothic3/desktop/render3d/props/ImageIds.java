package gothic3.desktop.render3d.props;

import java.util.IdentityHashMap;
import javax.microedition.lcdui.Image;

/**
 * Maps the game's {@link Image} objects back to their data ids (read only, GL thread): {@code i<id>} for the object /
 * item / effect images ({@code GameEngine.images[id][palette]}), {@code s<group>.<sheet>} for the animated character
 * sheets ({@code GameEngine.sheetImages}). Re-scans (rate limited) when an unknown image shows up, since the game loads images lazily.
 */
public final class ImageIds {
    private final IdentityHashMap<Image, String> ids = new IdentityHashMap<>();
    private long lastScan = -1000;
    private long frame;

    public String idOf(Image im) {
        String s = ids.get(im);
        if (s != null) return s;
        if (frame - lastScan > 20) {
            lastScan = frame;
            scan();
            s = ids.get(im);
            if (s != null) return s;
        }
        return null;
    }

    public void frame() { frame++; }

    private void scan() {
        try {
            Image[][] a = gothic3.port.GameHooks.bridge().images();
            if (a != null) {
                for (int i = 0; i < a.length; i++) {
                    Image[] p = a[i];
                    if (p == null) continue;
                    for (int k = 0; k < p.length; k++) if (p[k] != null) ids.putIfAbsent(p[k], k == 0 ? "i" + i : "i" + i + "." + k);
                }
            }
            Image[][][] b = gothic3.port.GameHooks.bridge().sheetImages();
            if (b != null) {
                for (int g = 0; g < b.length; g++) {
                    if (b[g] == null) continue;
                    for (int sh = 0; sh < b[g].length; sh++) {
                        Image[] p = b[g][sh];
                        if (p == null) continue;
                        for (int k = 0; k < p.length; k++) if (p[k] != null) ids.putIfAbsent(p[k], "s" + g + "." + sh);
                    }
                }
            }
        } catch (RuntimeException e) {
            // racing with a level load: try again later
        }
    }
}
