package gothic3.desktop.render3d.post;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import gothic3.desktop.Config;

/**
 * HD-2D post chain of the 3D view (docs/post.md). {@link #begin} redirects the scene into an HDR target (RGBA16F colour + depth texture),
 * {@link #end} runs SSAO, tilt-shift depth of field, bloom and the final grade pass into the backbuffer. Render thread only.
 * Any failure disables the chain for the session (the scene is then drawn straight to the screen as before).
 */
public final class PostChain3D {
    private final Config cfg;
    private PostSettings S;
    private int ver = -1;
    private Gfx.Fbo scene, base, dofPre, dofBlur, dofOut, aoA, aoB;
    private final Bloom bloom = new Bloom();
    private final FinalPass fin = new FinalPass(false);
    private boolean hdr = true, depthOk = true, broken, active;
    private final boolean forceLdr = Boolean.getBoolean("g3.post.rgba8"), noDepthTex = Boolean.getBoolean("g3.post.nodepthtex");
    private final ShaderProgram[] sh = new ShaderProgram[8];
    private final boolean[] shTried = new boolean[8];
    private static final int AO = 0, AOBLUR = 1, RESOLVE = 2, DOFPRE = 3, DOFBLUR = 4, DOFCOMP = 5;
    private static final String[] NAMES = {"ao", "aoblur", "resolve", "dofpre", "dofblur", "dofcomp"};
    private static final String[] SRC = {Shaders.AO, Shaders.AOBLUR, Shaders.RESOLVE, Shaders.DOFPRE, Shaders.DOFBLUR, Shaders.DOFCOMP};

    public PostChain3D(Config cfg) { this.cfg = cfg; }

    private ShaderProgram sh(int i) {
        if (!shTried[i]) {
            shTried[i] = true;
            sh[i] = Gfx.compile(NAMES[i], "", SRC[i]);
        }
        return sh[i];
    }

    private void fail(Throwable t) {
        broken = true;
        active = false;
        System.err.println("[post] chain disabled for this session: " + t);
        if (!(t instanceof IllegalStateException)) t.printStackTrace();
        try { dispose(); } catch (Throwable ignored) { /* best effort */ }
    }

    /** Settings (re-read when the config changed). */
    public PostSettings settings() {
        if (cfg.version() != ver) {
            ver = cfg.version();
            S = PostSettings.read(cfg);
        }
        return S;
    }

    /** Output scale the scene shader must apply (0.5 in the RGBA8 fallback so values up to 2 survive). */
    public float outScale() { return hdr ? 1f : 0.5f; }

    public boolean active() { return active; }

    /** GL handles of the scene target of the current frame (0 when the chain is not active): for effects that want the scene depth (light shafts). */
    public int sceneFbo() { return active && scene != null ? scene.fbo : 0; }
    public int sceneColorTex() { return active && scene != null ? scene.tex : 0; }
    public int sceneDepthTex() { return active && scene != null ? scene.depthTex : 0; }

    /** Binds the scene target of size vw x vh. Returns false when the chain is off (render straight to the screen). */
    public boolean begin(int vw, int vh) {
        active = false;
        if (broken) return false;
        try {
            if (!settings().any3d()) return false;
            if (scene == null || scene.w != vw || scene.h != vh) {
                freeTargets();
                scene = null;
                boolean[] hdrTry = forceLdr ? new boolean[] {false} : new boolean[] {true, false};
                outer:
                for (int dm = noDepthTex ? 2 : 1; dm <= 2; dm++) {
                    for (boolean h : hdrTry) {
                        scene = Gfx.Fbo.create(vw, vh, h, dm, false);
                        if (scene != null) {
                            hdr = h;
                            depthOk = dm == 1;
                            break outer;
                        }
                    }
                }
                if (scene == null) throw new IllegalStateException("no usable framebuffer format");
                System.err.println("[post] scene target " + vw + "x" + vh + (hdr ? " RGBA16F" : " RGBA8 (encoded)") + (depthOk ? " + depth texture" : ", no depth texture (SSAO off, DOF tilt-shift only)"));
            }
            scene.bind();
            active = true;
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    /** Re-binds the scene target (after another pass changed the framebuffer). */
    public void rebind() { if (active && scene != null) scene.bind(); }

    private Gfx.Fbo fbo(Gfx.Fbo cur, int w, int h, boolean hd, boolean linear) {
        if (cur != null && cur.w == w && cur.h == h && cur.hdr == hd) return cur;
        if (cur != null) cur.dispose();
        Gfx.Fbo f = Gfx.Fbo.create(w, h, hd, 0, linear);
        if (f == null) throw new IllegalStateException("cannot create " + w + "x" + h + " target");
        return f;
    }

    /**
     * Runs the chain and draws the result into the backbuffer region (vx, vy, vw, vh).
     * focusDepth = view depth (tiles) of the player, fovDeg vertical field of view, near/far camera planes, time in seconds.
     */
    public void end(int vx, int vy, int vw, int vh, float focusDepth, float fovDeg, float near, float far, float time) {
        if (!active) return;
        active = true;
        try {
            run(vx, vy, vw, vh, focusDepth, fovDeg, near, far, time);
        } catch (Throwable t) {
            fail(t);
        }
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0);
        Gdx.gl.glViewport(vx, vy, vw, vh);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        active = false;
    }

    private void run(int vx, int vy, int vw, int vh, float focusDepth, float fovDeg, float near, float far, float time) {
        GL20 gl = Gdx.gl;
        PostSettings s = S;
        gl.glDisable(GL20.GL_DEPTH_TEST);
        gl.glDisable(GL20.GL_BLEND);
        gl.glDisable(GL20.GL_CULL_FACE);
        gl.glColorMask(true, true, true, true);
        float ldr = hdr ? 0f : 1f;
        float tanH = (float) Math.tan(Math.toRadians(fovDeg) * 0.5);
        float aspect = vw / (float) vh;
        int hw = Math.max(1, vw / 2), hh = Math.max(1, vh / 2);
        int src = scene.tex;

        // ---- SSAO (half res) ----
        boolean useAo = s.ssao && depthOk && sh(AO) != null && sh(AOBLUR) != null;
        if (useAo) {
            aoA = fbo(aoA, hw, hh, false, true);
            aoB = fbo(aoB, hw, hh, false, true);
            ShaderProgram p = sh[AO];
            aoA.bind();
            p.bind();
            Gfx.texture(0, scene.depthTex);
            p.setUniformi("u_depth", 0);
            p.setUniformf("u_texel", 1f / vw, 1f / vh);
            p.setUniformf("u_proj", tanH * aspect, tanH, near, far);
            p.setUniformf("u_ao", s.ssaoRadius, 0.03f + 0.03f * s.ssaoRadius);
            Gfx.drawQuad(p);
            ShaderProgram b = sh[AOBLUR];
            b.bind();
            b.setUniformi("u_ao", 0);
            b.setUniformi("u_depth", 1);
            b.setUniformf("u_proj", tanH * aspect, tanH, near, far);
            Gfx.texture(1, scene.depthTex);
            aoB.bind();
            Gfx.texture(0, aoA.tex);
            b.setUniformf("u_dir", 1f / hw, 0f);
            Gfx.drawQuad(b);
            aoA.bind();
            Gfx.texture(0, aoB.tex);
            b.setUniformf("u_dir", 0f, 1f / hh);
            Gfx.drawQuad(b);
        }
        // ---- resolve: decode + AO ----
        if (useAo || !hdr) {
            ShaderProgram p = sh(RESOLVE);
            if (p == null) throw new IllegalStateException("resolve shader missing");
            base = fbo(base, vw, vh, hdr, false);
            base.bind();
            p.bind();
            Gfx.texture(0, scene.tex);
            p.setUniformi("u_scene", 0);
            Gfx.texture(1, useAo ? aoA.tex : scene.tex);
            p.setUniformi("u_ao", 1);
            Gfx.texture(2, depthOk ? scene.depthTex : scene.tex);
            p.setUniformi("u_depth", 2);
            p.setUniformf("u_decode", hdr ? 1f : 2f);
            p.setUniformf("u_aoStr", useAo ? s.ssaoStr : 0f);
            p.setUniformf("u_aoTexel", 1f / hw, 1f / hh);
            p.setUniformf("u_ldr", ldr);
            p.setUniformf("u_proj", tanH * aspect, tanH, near, far);
            Gfx.drawQuad(p);
            src = base.tex;
        }
        // ---- depth of field ----
        float blurPx = s.dofBlur * vh / 1080f;
        boolean useDof = s.dof && blurPx > 0.5f && sh(DOFPRE) != null && sh(DOFBLUR) != null && sh(DOFCOMP) != null;
        if (useDof) {
            dofPre = fbo(dofPre, hw, hh, hdr, true);
            dofBlur = fbo(dofBlur, hw, hh, hdr, true);
            dofOut = fbo(dofOut, vw, vh, hdr, false);
            boolean tilt = s.dofTilt || !depthOk;
            float fd = focusDepth + s.dofFocus;
            float[] dof = {fd, s.dofBand * 0.5f, Math.max(3f, s.dofBand * 0.8f), tilt ? 1f : 0f};
            float[] dof2 = {s.tsCenter, s.tsBand * 0.5f, 0.28f, 0f};
            int dtex = depthOk ? scene.depthTex : scene.tex;
            ShaderProgram p = sh[DOFPRE];
            dofPre.bind();
            p.bind();
            Gfx.texture(0, src);
            p.setUniformi("u_src", 0);
            Gfx.texture(1, dtex);
            p.setUniformi("u_depth", 1);
            p.setUniformf("u_texel", 1f / vw, 1f / vh);
            p.setUniformf("u_ldr", ldr);
            p.setUniformf("u_proj", tanH * aspect, tanH, near, far);
            p.setUniformf("u_dof", dof[0], dof[1], dof[2], dof[3]);
            p.setUniformf("u_dof2", dof2[0], dof2[1], dof2[2], dof2[3]);
            Gfx.drawQuad(p);
            p = sh[DOFBLUR];
            dofBlur.bind();
            p.bind();
            Gfx.texture(0, dofPre.tex);
            p.setUniformi("u_pre", 0);
            p.setUniformf("u_texel", 1f / hw, 1f / hh);
            p.setUniformf("u_R", Math.max(0.5f, blurPx * 0.5f));
            p.setUniformf("u_ldr", ldr);
            Gfx.drawQuad(p);
            p = sh[DOFCOMP];
            dofOut.bind();
            p.bind();
            Gfx.texture(0, src);
            p.setUniformi("u_src", 0);
            Gfx.texture(1, dtex);
            p.setUniformi("u_depth", 1);
            Gfx.texture(2, dofPre.tex);
            p.setUniformi("u_pre", 2);
            Gfx.texture(3, dofBlur.tex);
            p.setUniformi("u_blur", 3);
            p.setUniformf("u_hTexel", 1f / hw, 1f / hh);
            p.setUniformf("u_ldr", ldr);
            p.setUniformf("u_proj", tanH * aspect, tanH, near, far);
            p.setUniformf("u_dof", dof[0], dof[1], dof[2], dof[3]);
            p.setUniformf("u_dof2", dof2[0], dof2[1], dof2[2], dof2[3]);
            Gfx.drawQuad(p);
            src = dofOut.tex;
        }
        // ---- bloom ----
        int bt = 0;
        if (s.bloom) bt = bloom.run(src, vw, vh, false, !hdr, hw, hh, hdr, s.bloomThr, s.bloomRadius);

        // ---- final ----
        FinalPass.Params p = new FinalPass.Params();
        p.colorTex = src;
        p.bloomTex = bt;
        p.bloomI = s.bloomI;
        p.exposure = s.exposure;
        p.tonemap = s.tonemap;
        p.contrast = s.contrast;
        p.saturation = s.saturation;
        p.temperature = s.temperature;
        p.tint = s.tint;
        p.grade = s.grade;
        p.gradeAmount = s.gradeAmount;
        p.lut = s.lut;
        p.vignette = s.vignette;
        p.grain = s.grain;
        p.ca = s.ca;
        p.sharpen = s.sharpen;
        p.fxaa = s.fxaa;
        p.ldr = !hdr;
        p.time = time;
        p.vx = vx;
        p.vy = vy;
        p.vw = vw;
        p.vh = vh;
        if (!fin.draw(p)) throw new IllegalStateException("final shader failed");
    }

    private void freeTargets() {
        for (Gfx.Fbo f : new Gfx.Fbo[] {scene, base, dofPre, dofBlur, dofOut, aoA, aoB}) if (f != null) f.dispose();
        scene = base = dofPre = dofBlur = dofOut = aoA = aoB = null;
        bloom.dispose();
    }

    public void dispose() {
        freeTargets();
        bloom.disposeAll();
        fin.dispose();
        for (int i = 0; i < sh.length; i++) {
            if (sh[i] != null) sh[i].dispose();
            sh[i] = null;
            shTried[i] = false;
        }
    }
}
