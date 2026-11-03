package gothic3.desktop.post2d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import gothic3.desktop.Config;
import gothic3.desktop.render3d.post.Bloom;
import gothic3.desktop.render3d.post.FinalPass;
import gothic3.desktop.render3d.post.PostSettings;

/**
 * Light post chain of the 2D view (post2d.* config keys): pixel-art upscaler, bloom, grading, vignette, grain, CRT scanlines. Draws the logical
 * frame texture straight into the window region; default everything off (then the caller uses the plain nearest SpriteBatch path). Render thread only.
 */
public final class PostChain2D {
    private final Config cfg;
    private PostSettings S;
    private int ver = -1;
    private final Bloom bloom = new Bloom();
    private final FinalPass fin = new FinalPass(true);
    private boolean broken;
    private Texture.TextureFilter lastFilter;

    public PostChain2D(Config cfg) { this.cfg = cfg; }

    private PostSettings settings() {
        if (cfg.version() != ver) {
            ver = cfg.version();
            S = PostSettings.read(cfg);
        }
        return S;
    }

    public boolean wanted() { return !broken && settings().any2d(); }

    /** Draws {@code frame} (fw x fh, opaque logical frame) into the window region. Returns false on failure (caller falls back to the plain path). */
    public boolean draw(Texture frame, int fw, int fh, int x, int y, int dw, int dh, float time) {
        PostSettings s = settings();
        try {
            Texture.TextureFilter f = Texture.TextureFilter.Linear; // the shader samples exact texel centres itself, linear only matters for "sharp"
            if (lastFilter != f) {
                frame.setFilter(f, f);
                lastFilter = f;
            }
            int bt = 0;
            if (s.bloom2) bt = bloom.run(frame.getTextureObjectHandle(), fw, fh, true, false, Math.max(2, dw / 2), Math.max(2, dh / 2), true, s.bloom2Thr, 0.6f);
            FinalPass.Params p = new FinalPass.Params();
            p.colorTex = frame.getTextureObjectHandle();
            p.bloomTex = bt;
            p.bloomI = s.bloom2I;
            p.grade = s.grade2;
            p.saturation = s.sat2;
            p.contrast = s.contrast2;
            p.vignette = s.vig2;
            p.grain = s.grain2;
            p.time = time;
            p.crtScan = s.crt ? s.crtScan : 0;
            p.crtMask = s.crt ? s.crtMask : 0;
            p.crtCurve = s.crt ? s.crtCurve : 0;
            p.srcW = fw;
            p.srcH = fh;
            p.upscaler = s.upscaler;
            p.vx = x;
            p.vy = y;
            p.vw = dw;
            p.vh = dh;
            boolean ok = fin.draw(p);
            if (!ok) broken = true;
            return ok;
        } catch (Throwable t) {
            broken = true;
            System.err.println("[post2d] disabled for this session: " + t);
            return false;
        }
    }

    public void invalidate() { lastFilter = null; }

    /** Restores the plain nearest filter of the frame texture (when the post chain is switched off). */
    public void restoreFilter(Texture frame) {
        if (lastFilter != null) {
            frame.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            lastFilter = null;
        }
    }

    public void dispose() {
        bloom.disposeAll();
        fin.dispose();
    }
}
