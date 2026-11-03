package gothic3.desktop.render3d.post;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;

/** Bloom: soft-knee bright pass, dual filtering downsample chain and additive upsample chain. Shared by the 3D and 2D chains. */
public final class Bloom {
    private static final int MAX_LEVELS = 6;
    private final Gfx.Fbo[] lv = new Gfx.Fbo[MAX_LEVELS];
    private int n, bw, bh;
    private boolean hdr;
    private ShaderProgram bright, down, up;
    private boolean tried, failed;

    public boolean failed() { return failed; }

    private void init() {
        tried = true;
        bright = Gfx.compile("bloom.bright", "", Shaders.BRIGHT);
        down = Gfx.compile("bloom.down", "", Shaders.DOWN);
        up = Gfx.compile("bloom.up", "", Shaders.UP);
        failed = bright == null || down == null || up == null;
    }

    private boolean ensure(int w, int h, boolean wantHdr) {
        if (n > 0 && w == bw && h == bh && wantHdr == hdr) return true;
        dispose();
        bw = w;
        bh = h;
        hdr = wantHdr;
        int cw = w, ch = h;
        for (int i = 0; i < MAX_LEVELS && cw >= 4 && ch >= 4; i++) {
            lv[i] = Gfx.Fbo.create(cw, ch, wantHdr, 0, true);
            if (lv[i] == null) { dispose(); return false; }
            n = i + 1;
            cw = Math.max(1, cw / 2);
            ch = Math.max(1, ch / 2);
        }
        return n >= 2;
    }

    /**
     * Builds the bloom of {@code srcTex} (size srcW x srcH, v flipped for 2D frames) at (bw x bh) and returns the result texture
     * (linear light), or 0 when unavailable.
     */
    public int run(int srcTex, int srcW, int srcH, boolean flip, boolean ldrSrc, int bw, int bh, boolean hdrTargets, float thr, float radius) {
        if (failed) return 0;
        if (!tried) init();
        if (failed || !ensure(bw, bh, hdrTargets)) return 0;
        GL20 gl = Gdx.gl;
        gl.glDisable(GL20.GL_BLEND);
        lv[0].bind();
        bright.bind();
        Gfx.texture(0, srcTex);
        bright.setUniformi("u_src", 0);
        bright.setUniformf("u_texel", 1f / srcW, 1f / srcH);
        bright.setUniformf("u_th", thr, 0.5f, 0f);
        bright.setUniformf("u_flip", flip ? 1f : 0f);
        bright.setUniformf("u_ldr", ldrSrc ? 1f : 0f);
        Gfx.drawQuad(bright);
        down.bind();
        down.setUniformi("u_src", 0);
        for (int i = 1; i < n; i++) {
            lv[i].bind();
            Gfx.texture(0, lv[i - 1].tex);
            down.setUniformf("u_texel", 1f / lv[i - 1].w, 1f / lv[i - 1].h);
            Gfx.drawQuad(down);
        }
        float k = 0.35f + 0.65f * Math.max(0f, Math.min(1f, radius));
        gl.glEnable(GL20.GL_BLEND);
        gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE);
        up.bind();
        up.setUniformi("u_src", 0);
        up.setUniformf("u_w", k);
        for (int i = n - 2; i >= 0; i--) {
            lv[i].bind();
            Gfx.texture(0, lv[i + 1].tex);
            up.setUniformf("u_texel", 1f / lv[i + 1].w, 1f / lv[i + 1].h);
            Gfx.drawQuad(up);
        }
        gl.glDisable(GL20.GL_BLEND);
        return lv[0].tex;
    }

    public void dispose() {
        for (int i = 0; i < MAX_LEVELS; i++) {
            if (lv[i] != null) lv[i].dispose();
            lv[i] = null;
        }
        n = 0;
    }

    public void disposeAll() {
        dispose();
        if (bright != null) bright.dispose();
        if (down != null) down.dispose();
        if (up != null) up.dispose();
        bright = down = up = null;
        tried = false;
    }
}
