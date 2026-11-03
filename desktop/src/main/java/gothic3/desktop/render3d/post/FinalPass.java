package gothic3.desktop.render3d.post;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;

/** The last pass of both chains: bloom add, grading, vignette, grain, CA, sharpen, FXAA-lite (and for 2D the upscaler + CRT) straight into the backbuffer. */
public final class FinalPass {
    /** Everything the pass needs for one draw. Defaults = neutral. */
    public static final class Params {
        public int colorTex, bloomTex;
        public float bloomI, exposure = 1, tonemap, contrast = 1, saturation = 1, temperature, tint;
        public String grade = "none", lut = "";
        public float gradeAmount = 1;
        public float vignette, vigRadius = 0.35f, grain, ca, sharpen;
        public boolean fxaa, ldr;
        public float time;
        public int vx, vy, vw, vh;
        // 2D
        public float crtScan, crtMask, crtCurve;
        public int srcW, srcH, upscaler;
    }

    private final boolean is2d;
    private ShaderProgram sh;
    private boolean tried;
    private Texture lut;
    private String lutName = "";
    private int dummyTex;

    public FinalPass(boolean is2d) { this.is2d = is2d; }

    public boolean failed() { return tried && sh == null; }

    public boolean draw(Params p) {
        if (!tried) {
            tried = true;
            sh = Gfx.compile(is2d ? "final2d" : "final", is2d ? "#define IS2D 1\n" : "", Shaders.FINAL);
        }
        if (sh == null) return false;
        GL20 gl = Gdx.gl;
        gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0);
        gl.glViewport(p.vx, p.vy, p.vw, p.vh);
        gl.glDisable(GL20.GL_BLEND);
        gl.glDisable(GL20.GL_DEPTH_TEST);
        gl.glDisable(GL20.GL_CULL_FACE);
        sh.bind();
        Gfx.texture(0, p.colorTex);
        sh.setUniformi("u_color", 0);
        Gfx.texture(1, p.bloomTex != 0 ? p.bloomTex : p.colorTex);
        sh.setUniformi("u_bloom", 1);
        boolean useLut = !is2d && p.grade.equals("lut") && p.lut != null && !p.lut.isEmpty();
        if (useLut) {
            if (!p.lut.equals(lutName)) {
                if (lut != null) lut.dispose();
                lut = Lut.load(p.lut);
                lutName = p.lut;
            }
            useLut = lut != null;
        }
        Gfx.texture(2, useLut ? lut.getTextureObjectHandle() : p.colorTex);
        sh.setUniformi("u_lut", 2);
        sh.setUniformf("u_lutAmt", useLut ? p.gradeAmount : 0f);
        sh.setUniformf("u_ldr", p.ldr ? 1f : 0f);
        sh.setUniformf("u_px", 1f / p.vw, 1f / p.vh);
        sh.setUniformf("u_bloomI", p.bloomTex != 0 ? p.bloomI : 0f);
        sh.setUniformf("u_tone", p.exposure, p.tonemap, p.contrast, p.saturation);
        float t = p.temperature, ti = p.tint;
        sh.setUniformf("u_wb", 1f + 0.20f * t + 0.05f * ti, 1f - 0.10f * ti, 1f - 0.25f * t + 0.05f * ti);
        float[] lgg = PostSettings.gradeLGG(useLut ? "none" : p.grade, p.gradeAmount);
        sh.setUniformf("u_lift", lgg[0], lgg[1], lgg[2]);
        sh.setUniformf("u_gamma", lgg[3], lgg[4], lgg[5]);
        sh.setUniformf("u_gain", lgg[6], lgg[7], lgg[8]);
        sh.setUniformf("u_fx", p.vignette, p.grain, p.ca, p.sharpen);
        sh.setUniformf("u_fx2", p.time, p.fxaa ? 1f : 0f, p.vw / (float) p.vh, p.vigRadius);
        if (is2d) {
            sh.setUniformf("u_crt", p.crtScan, p.crtMask, p.crtCurve, 0f);
            sh.setUniformf("u_src", p.srcW, p.srcH);
            sh.setUniformf("u_up", p.upscaler);
            sh.setUniformf("u_dest", p.vw, p.vh);
        }
        Gfx.drawQuad(sh);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        return true;
    }

    public void dispose() {
        if (sh != null) sh.dispose();
        sh = null;
        tried = false;
        if (lut != null) lut.dispose();
        lut = null;
        lutName = "";
    }
}
