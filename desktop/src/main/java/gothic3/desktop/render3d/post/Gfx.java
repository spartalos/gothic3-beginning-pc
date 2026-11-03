package gothic3.desktop.render3d.post;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;

/** Small raw-GL helpers of the post chain: render targets, fullscreen quad, shader compile. Render thread only. */
public final class Gfx {
    private Gfx() { }

    static final int RGBA16F = 0x881A, HALF_FLOAT = 0x140B, DEPTH24 = 0x81A6;

    /** A framebuffer with one colour texture and optionally a depth texture (or depth renderbuffer). */
    public static final class Fbo {
        public int fbo, tex, depthTex, depthRb, w, h;
        public boolean hdr;

        /** depth: 0 none, 1 texture, 2 renderbuffer. Returns null if the framebuffer is not complete. */
        public static Fbo create(int w, int h, boolean hdr, int depth, boolean linear) {
            GL20 gl = Gdx.gl;
            Fbo f = new Fbo();
            f.w = w;
            f.h = h;
            f.hdr = hdr;
            while (gl.glGetError() != GL20.GL_NO_ERROR) { /* clear */ }
            f.tex = gl.glGenTexture();
            gl.glBindTexture(GL20.GL_TEXTURE_2D, f.tex);
            gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, hdr ? RGBA16F : GL20.GL_RGBA, w, h, 0, GL20.GL_RGBA, hdr ? HALF_FLOAT : GL20.GL_UNSIGNED_BYTE, null);
            params(gl, linear);
            if (depth == 1) {
                f.depthTex = gl.glGenTexture();
                gl.glBindTexture(GL20.GL_TEXTURE_2D, f.depthTex);
                gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, DEPTH24, w, h, 0, GL20.GL_DEPTH_COMPONENT, GL20.GL_UNSIGNED_INT, null);
                params(gl, false);
            } else if (depth == 2) {
                f.depthRb = gl.glGenRenderbuffer();
                gl.glBindRenderbuffer(GL20.GL_RENDERBUFFER, f.depthRb);
                gl.glRenderbufferStorage(GL20.GL_RENDERBUFFER, GL20.GL_DEPTH_COMPONENT16, w, h);
            }
            f.fbo = gl.glGenFramebuffer();
            gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, f.fbo);
            gl.glFramebufferTexture2D(GL20.GL_FRAMEBUFFER, GL20.GL_COLOR_ATTACHMENT0, GL20.GL_TEXTURE_2D, f.tex, 0);
            if (depth == 1) gl.glFramebufferTexture2D(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT, GL20.GL_TEXTURE_2D, f.depthTex, 0);
            if (depth == 2) gl.glFramebufferRenderbuffer(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT, GL20.GL_RENDERBUFFER, f.depthRb);
            int st = gl.glCheckFramebufferStatus(GL20.GL_FRAMEBUFFER);
            int err = gl.glGetError();
            gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0);
            if (st != GL20.GL_FRAMEBUFFER_COMPLETE || err != GL20.GL_NO_ERROR) {
                f.dispose();
                return null;
            }
            return f;
        }

        private static void params(GL20 gl, boolean linear) {
            int fl = linear ? GL20.GL_LINEAR : GL20.GL_NEAREST;
            gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, fl);
            gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MAG_FILTER, fl);
            gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_S, GL20.GL_CLAMP_TO_EDGE);
            gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_T, GL20.GL_CLAMP_TO_EDGE);
        }

        public void bind() {
            Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, fbo);
            Gdx.gl.glViewport(0, 0, w, h);
        }

        public void dispose() {
            GL20 gl = Gdx.gl;
            if (fbo != 0) gl.glDeleteFramebuffer(fbo);
            if (tex != 0) gl.glDeleteTexture(tex);
            if (depthTex != 0) gl.glDeleteTexture(depthTex);
            if (depthRb != 0) gl.glDeleteRenderbuffer(depthRb);
            fbo = tex = depthTex = depthRb = 0;
        }
    }

    public static void texture(int unit, int tex) {
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0 + unit);
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, tex);
    }

    private static Mesh quad;

    public static void drawQuad(ShaderProgram sp) {
        if (quad == null) {
            quad = new Mesh(true, 4, 0, VertexAttribute.Position(), VertexAttribute.TexCoords(0));
            quad.setVertices(new float[] {-1, -1, 0, 0, 0, 1, -1, 0, 1, 0, -1, 1, 0, 0, 1, 1, 1, 0, 1, 1});
        }
        quad.render(sp, GL20.GL_TRIANGLE_STRIP);
    }

    public static void disposeShared() {
        if (quad != null) quad.dispose();
        quad = null;
    }

    static final String VERT = "attribute vec3 a_position;\nattribute vec2 a_texCoord0;\nvarying vec2 v_uv;\n"
            + "void main() {\n  v_uv = a_texCoord0;\n  gl_Position = vec4(a_position.xy, 0.0, 1.0);\n}\n";

    /** Compiles a fullscreen-pass shader; null (logged) on failure. {@code defines} is prepended (e.g. "#define IS2D 1\n"). */
    public static ShaderProgram compile(String name, String defines, String frag) {
        ShaderProgram.pedantic = false;
        ShaderProgram sp = new ShaderProgram(VERT, defines + frag);
        if (!sp.isCompiled()) {
            System.err.println("[post] shader '" + name + "' failed, effect disabled for this session: " + sp.getLog());
            sp.dispose();
            return null;
        }
        return sp;
    }
}
