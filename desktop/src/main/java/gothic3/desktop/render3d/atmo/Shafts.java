package gothic3.desktop.render3d.atmo;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;

/**
 * Light shafts and window glows as additive soft quads (geometry based, no depth texture needed): slanted sheets under windows (interiors),
 * sun beams in a camera-relative world grid (outdoors), warm glow patches over dark windows at night. Rebuilt on the CPU every frame
 * (a few dozen quads). Render thread only.
 */
final class Shafts {
    private static final int MAXQ = 512;
    private static final int F = Particles.F;

    private static final String VERT = ""
            + "attribute vec3 a_position;\nattribute vec4 a_color;\nattribute vec2 a_texCoord0;\nuniform mat4 u_projView;\n"
            + "varying vec2 v_uv;\nvarying vec4 v_col;\nvarying float v_dist;\nvarying vec3 v_wp;\n"
            + "void main() {\n  vec4 p = u_projView * vec4(a_position, 1.0);\n  gl_Position = p;\n  v_uv = a_texCoord0;\n  v_col = a_color;\n  v_dist = p.w;\n  v_wp = a_position;\n}\n";
    private static final String FRAG = ""
            + "#ifdef GL_ES\nprecision mediump float;\n#endif\n"
            + "varying vec2 v_uv;\nvarying vec4 v_col;\nvarying float v_dist;\nvarying vec3 v_wp;\n"
            + "uniform float u_time;\nuniform vec2 u_fog;\nuniform float u_kind;\n"
            + "void main() {\n"
            + "  float f = clamp((v_dist - u_fog.x) / (u_fog.y - u_fog.x), 0.0, 1.0);\n"
            + "  float a;\n"
            + "  if (u_kind < 0.5) {\n"
            + "    float across = sin(clamp(v_uv.x, 0.0, 1.0) * 3.14159);\n"
            + "    float along = smoothstep(0.0, 0.08, v_uv.y) * pow(max(1.0 - v_uv.y, 0.0), 1.3);\n"
            + "    float mote = 0.82 + 0.18 * sin(v_wp.x * 2.9 + v_wp.z * 2.1 + u_time * 0.35) * sin(v_wp.y * 2.3 - v_wp.z * 1.7 - u_time * 0.27);\n"
            + "    a = v_col.a * across * across * along * mote;\n"
            + "  } else {\n"
            + "    vec2 e = min(v_uv, 1.0 - v_uv);\n"
            + "    a = v_col.a * smoothstep(0.0, 0.25, e.x) * smoothstep(0.0, 0.25, e.y) * (0.9 + 0.1 * sin(u_time * 3.1 + v_wp.x * 9.0));\n"
            + "  }\n"
            + "  gl_FragColor = vec4(v_col.rgb, a * (1.0 - f * f));\n}\n";

    private ShaderProgram sh;
    private Mesh mesh;
    private final float[] v = new float[MAXQ * 4 * F];
    private int q;
    private int mode = -1;       // buffer holds beams (0) or glows (1)

    void init() {
        sh = new ShaderProgram(VERT, FRAG);
        if (!sh.isCompiled()) throw new IllegalStateException("shaft shader: " + sh.getLog());
        mesh = new Mesh(false, MAXQ * 4, MAXQ * 6, Particles.ATTRS);
        short[] idx = new short[MAXQ * 6];
        for (int i = 0; i < MAXQ; i++) {
            int o = i * 6, b = i * 4;
            idx[o] = (short) b; idx[o + 1] = (short) (b + 1); idx[o + 2] = (short) (b + 2);
            idx[o + 3] = (short) (b + 2); idx[o + 4] = (short) (b + 3); idx[o + 5] = (short) b;
        }
        mesh.setIndices(idx);
    }

    void begin(Matrix4 projView, float time, float fogStart, float fogEnd) {
        sh.bind();
        sh.setUniformMatrix("u_projView", projView);
        sh.setUniformf("u_time", time);
        sh.setUniformf("u_fog", fogStart, fogEnd);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        q = 0;
    }

    private void vert(int i, float x, float y, float z, float r, float g, float b, float a, float u, float w) {
        int o = (q * 4 + i) * F;
        v[o] = x; v[o + 1] = y; v[o + 2] = z;
        v[o + 3] = r; v[o + 4] = g; v[o + 5] = b; v[o + 6] = a;
        v[o + 7] = u; v[o + 8] = w;
    }

    /** Quad with corners p0 p1 (start edge) and p2 p3 (end edge, p2 below p1, p3 below p0); u across, v along. */
    void quad(float[] p0, float[] p1, float[] p2, float[] p3, float r, float g, float b, float a) {
        if (q >= MAXQ) return;
        vert(0, p0[0], p0[1], p0[2], r, g, b, a, 0, 0);
        vert(1, p1[0], p1[1], p1[2], r, g, b, a, 1, 0);
        vert(2, p2[0], p2[1], p2[2], r, g, b, a, 1, 1);
        vert(3, p3[0], p3[1], p3[2], r, g, b, a, 0, 1);
        q++;
    }

    /** Glow patch: p0 top left, p1 top right, p2 bottom right, p3 bottom left (uv covers the rect). */
    void glow(float x0, float x1, float yb, float yt, float z, float r, float g, float b, float a) {
        if (q >= MAXQ) return;
        vert(0, x0, yt, z, r, g, b, a, 0, 0);
        vert(1, x1, yt, z, r, g, b, a, 1, 0);
        vert(2, x1, yb, z, r, g, b, a, 1, 1);
        vert(3, x0, yb, z, r, g, b, a, 0, 1);
        q++;
    }

    /** Draws what was added since {@link #begin} / the last flush as beams (kind 0) or glows (kind 1). */
    void flush(int kind) {
        if (q == 0) return;
        sh.setUniformf("u_kind", (float) kind);
        mesh.setVertices(v, 0, q * 4 * F);
        mesh.render(sh, GL20.GL_TRIANGLES, 0, q * 6);
        q = 0;
    }

    void dispose() {
        if (sh != null) sh.dispose();
        if (mesh != null) mesh.dispose();
        sh = null;
    }
}
