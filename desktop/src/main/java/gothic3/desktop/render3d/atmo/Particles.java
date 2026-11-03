package gothic3.desktop.render3d.atmo;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import java.util.Random;

/**
 * GPU particles: snow flakes, dust motes, fire embers and smoke wisps. Every particle is a static quad whose motion is computed in the
 * vertex shader from a time uniform (camera-relative wrapped volume for flakes / motes, per fire light for embers / smoke), so there is
 * no per-particle CPU work. Billboards face the camera, depth tested, no depth write. Render thread only.
 */
final class Particles {
    static final VertexAttributes ATTRS = new VertexAttributes(
            new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
            new VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, "a_color"),
            new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"));
    static final int F = 9;

    static final int MAX_SNOW = 9000, MAX_DUST = 2000, LIGHTS = 16, EMBER_PER = 40, SMOKE_PER = 10;

    private static final String VERT = ""
            + "attribute vec3 a_position;\nattribute vec4 a_color;\nattribute vec2 a_texCoord0;\n"
            + "uniform mat4 u_projView;\nuniform vec3 u_right;\nuniform vec3 u_up;\nuniform vec3 u_focus;\nuniform float u_time;\n"
            + "uniform vec3 u_vol;\nuniform vec3 u_vel;\nuniform float u_size;\nuniform float u_mode;\nuniform float u_alpha;\n"
            + "uniform vec2 u_drift;\nuniform vec4 u_lp[16];\nuniform float u_nlp;\nuniform vec3 u_col;\nuniform vec3 u_col2;\nuniform vec2 u_fog;\n"
            + "varying vec2 v_c;\nvarying vec4 v_col;\n"
            + "void main() {\n"
            + "  vec3 s = a_position;\n  vec4 r = a_color;\n  vec3 pos;\n  float size = u_size;\n  float alpha = u_alpha;\n  vec3 col = u_col;\n"
            + "  if (u_mode < 1.5) {\n"                                  // 0 snow flakes, 1 dust motes
            + "    float spd = 0.6 + 0.8 * r.z;\n"
            + "    vec2 w = vec2(s.x * u_vol.x + u_vel.x * spd * u_time, s.z * u_vol.z + u_vel.z * spd * u_time);\n"
            + "    w.x += sin(u_time * 0.9 * spd + r.w * 6.283) * 0.35;\n    w.y += cos(u_time * 0.7 * spd + r.w * 6.283) * 0.35;\n"
            + "    w += u_drift * spd;\n"
            + "    vec2 rel = mod(w - u_focus.xz + u_vol.xz * 0.5, u_vol.xz) - u_vol.xz * 0.5;\n"
            + "    float wy = mod(s.y * u_vol.y + u_vel.y * spd * u_time, u_vol.y);\n"
            + "    pos = vec3(u_focus.x + rel.x, wy, u_focus.z + rel.y);\n"
            + "    vec2 e = abs(rel) / (u_vol.xz * 0.5);\n"
            + "    float edge = (1.0 - smoothstep(0.7, 1.0, e.x)) * (1.0 - smoothstep(0.7, 1.0, e.y));\n"
            + "    float vf = smoothstep(0.0, 0.4, wy) * (1.0 - smoothstep(u_vol.y - 1.5, u_vol.y, wy));\n"
            + "    alpha *= edge * vf * (0.5 + 0.5 * r.y);\n    size *= 0.6 + 0.8 * r.y;\n"
            + "    if (u_mode > 0.5) {\n"
            + "      float v = 0.0;\n"
            + "      for (int i = 0; i < 16; i++) { if (float(i) >= u_nlp) break; v = max(v, 1.0 - distance(pos, u_lp[i].xyz) / u_lp[i].w); }\n"
            + "      alpha *= (0.10 + 0.90 * clamp(v, 0.0, 1.0)) * (0.55 + 0.45 * sin(u_time * 1.3 + r.w * 50.0));\n"
            + "    }\n"
            + "  } else {\n"                                             // 2 embers, 3 smoke: per fire light
            + "    int idx = int(s.x + 0.5);\n    vec4 L = u_lp[idx];\n"
            + "    if (float(idx) >= u_nlp) alpha = 0.0;\n"
            + "    if (u_mode < 2.5) {\n"
            + "      float life = fract(r.x + u_time * (0.16 + 0.2 * r.y));\n"
            + "      pos = L.xyz + vec3((r.z - 0.5) * 0.30, life * (1.5 + 1.4 * r.z), (r.w - 0.5) * 0.20);\n"
            + "      pos.x += u_vel.x * life * life * 1.5 + sin(u_time * 2.3 + r.w * 30.0) * 0.10 * life;\n"
            + "      pos.z += cos(u_time * 1.9 + r.z * 30.0) * 0.10 * life + u_vel.z * life * life;\n"
            + "      alpha *= smoothstep(0.0, 0.06, life) * pow(1.0 - life, 1.4) * (0.75 + 0.25 * sin(u_time * 17.0 + r.x * 90.0));\n"
            + "      col = mix(u_col, u_col2, life);\n      size *= (0.7 + 0.6 * r.y) * (1.0 - 0.5 * life);\n"
            + "    } else {\n"
            + "      float life = fract(r.x + u_time * (0.07 + 0.05 * r.y));\n"
            + "      pos = L.xyz + vec3((r.z - 0.5) * 0.2, 0.25 + life * 2.6, (r.w - 0.5) * 0.15);\n"
            + "      pos.x += u_vel.x * life * life * 3.0 + sin(u_time * 0.8 + r.w * 20.0) * 0.18 * life;\n"
            + "      pos.z += cos(u_time * 0.7 + r.z * 20.0) * 0.12 * life + u_vel.z * life * life * 2.0;\n"
            + "      alpha *= smoothstep(0.0, 0.15, life) * pow(1.0 - life, 1.3);\n"
            + "      size *= 0.5 + life * 1.7;\n"
            + "    }\n"
            + "  }\n"
            + "  pos += (u_right * a_texCoord0.x + u_up * a_texCoord0.y) * size;\n"
            + "  vec4 p = u_projView * vec4(pos, 1.0);\n  gl_Position = p;\n"
            + "  float f = clamp((p.w - u_fog.x) / (u_fog.y - u_fog.x), 0.0, 1.0);\n"
            + "  v_col = vec4(col, alpha * (1.0 - f * f));\n  v_c = a_texCoord0;\n}\n";
    private static final String FRAG = ""
            + "#ifdef GL_ES\nprecision mediump float;\n#endif\n"
            + "varying vec2 v_c;\nvarying vec4 v_col;\nuniform float u_hard;\nuniform float u_glow;\n"
            + "void main() {\n  float d = dot(v_c, v_c);\n  if (d >= 1.0 || v_col.a <= 0.002) discard;\n"
            + "  float a = v_col.a * (1.0 - smoothstep(u_hard, 1.0, d));\n"
            + "  gl_FragColor = vec4(v_col.rgb * (1.0 + u_glow * (1.0 - d)), a);\n}\n";

    private ShaderProgram sh;
    private Mesh snow, dust, embers, smoke;
    private final Random rnd = new Random(7);   // own generator: never the game's RNG

    boolean init() {
        sh = new ShaderProgram(VERT, FRAG);
        if (!sh.isCompiled()) throw new IllegalStateException("particle shader: " + sh.getLog());
        short[] idx = new short[MAX_SNOW * 6];
        for (int q = 0; q < MAX_SNOW; q++) {
            int i = q * 6, v = q * 4;
            idx[i] = (short) v; idx[i + 1] = (short) (v + 1); idx[i + 2] = (short) (v + 2);
            idx[i + 3] = (short) (v + 2); idx[i + 4] = (short) (v + 3); idx[i + 5] = (short) v;
        }
        snow = build(MAX_SNOW, idx, false);
        dust = build(MAX_DUST, idx, false);
        embers = build(EMBER_PER * LIGHTS, idx, true);
        smoke = build(SMOKE_PER * LIGHTS, idx, true);
        return true;
    }

    /** Quads in k-major order for per-light meshes (quad = k * LIGHTS + light), so a prefix gives the first k particles of every light. */
    private Mesh build(int n, short[] idx, boolean perLight) {
        Mesh m = new Mesh(true, n * 4, n * 6, ATTRS);
        float[] v = new float[n * 4 * F];
        float[] cx = {-1, 1, 1, -1}, cy = {-1, -1, 1, 1};
        for (int q = 0; q < n; q++) {
            float sx = rnd.nextFloat(), sy = rnd.nextFloat(), sz = rnd.nextFloat();
            if (perLight) { sx = q % LIGHTS; sy = sz = 0; }
            float r0 = rnd.nextFloat(), r1 = rnd.nextFloat(), r2 = rnd.nextFloat(), r3 = rnd.nextFloat();
            for (int k = 0; k < 4; k++) {
                int o = (q * 4 + k) * F;
                v[o] = sx; v[o + 1] = sy; v[o + 2] = sz;
                v[o + 3] = r0; v[o + 4] = r1; v[o + 5] = r2; v[o + 6] = r3;
                v[o + 7] = cx[k]; v[o + 8] = cy[k];
            }
        }
        m.setVertices(v);
        m.setIndices(idx, 0, n * 6);
        return m;
    }

    /** Common uniforms; leaves the particle shader bound. */
    void begin(Matrix4 projView, float rx, float ry, float rz, float ux, float uy, float uz, float time, float fogStart, float fogEnd) {
        sh.bind();
        sh.setUniformMatrix("u_projView", projView);
        sh.setUniformf("u_right", rx, ry, rz);
        sh.setUniformf("u_up", ux, uy, uz);
        sh.setUniformf("u_time", time);
        sh.setUniformf("u_fog", fogStart, fogEnd);
    }

    private void lights(float[] lp, int n) {
        sh.setUniformf("u_nlp", (float) n);
        if (n > 0) sh.setUniform4fv("u_lp", lp, 0, n * 4);
    }

    void drawSnow(float fx, float fz, float count, float size, float wind, float r, float g, float b, float alpha, float driftX, float driftZ) {
        int n = Math.max(0, Math.min(MAX_SNOW, (int) count));
        if (n == 0) return;
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        sh.setUniformf("u_mode", 0f);
        sh.setUniformf("u_drift", driftX, driftZ);
        sh.setUniformf("u_focus", fx, 0f, fz);
        sh.setUniformf("u_vol", 46f, 14f, 40f);
        sh.setUniformf("u_vel", 0.9f * wind, -1.15f, 0.35f * wind);
        sh.setUniformf("u_size", 0.045f * size);
        sh.setUniformf("u_alpha", alpha);
        sh.setUniformf("u_col", r, g, b);
        sh.setUniformf("u_hard", 0.35f);
        sh.setUniformf("u_glow", 0f);
        lights(null, 0);
        snow.render(sh, GL20.GL_TRIANGLES, 0, n * 6);
    }

    void drawDust(float fx, float fz, float count, float size, float[] lp, int nlp, float r, float g, float b, float alpha) {
        int n = Math.max(0, Math.min(MAX_DUST, (int) count));
        if (n == 0) return;
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        sh.setUniformf("u_mode", 1f);
        sh.setUniformf("u_drift", 0f, 0f);
        sh.setUniformf("u_focus", fx, 0f, fz - 2f);
        sh.setUniformf("u_vol", 26f, 5f, 22f);
        sh.setUniformf("u_vel", 0.05f, -0.03f, 0.035f);
        sh.setUniformf("u_size", 0.03f * size);
        sh.setUniformf("u_alpha", alpha);
        sh.setUniformf("u_col", r, g, b);
        sh.setUniformf("u_hard", 0.0f);
        sh.setUniformf("u_glow", 0f);
        lights(lp, nlp);
        dust.render(sh, GL20.GL_TRIANGLES, 0, n * 6);
    }

    /** @param lp fire light positions (xyz + radius), n lights */
    void drawEmbers(float[] lp, int nlp, float density, float wind, float alpha, float size) {
        int per = Math.max(0, Math.min(EMBER_PER, Math.round(EMBER_PER * density)));
        if (per == 0 || nlp == 0) return;
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        sh.setUniformf("u_mode", 2f);
        sh.setUniformf("u_vel", 0.35f * wind, 0f, 0.15f * wind);
        sh.setUniformf("u_size", 0.085f * size);
        sh.setUniformf("u_alpha", alpha);
        sh.setUniformf("u_col", 1.0f, 0.78f, 0.30f);
        sh.setUniformf("u_col2", 1.0f, 0.22f, 0.04f);
        sh.setUniformf("u_hard", 0.0f);
        sh.setUniformf("u_glow", 1.2f);
        lights(lp, nlp);
        embers.render(sh, GL20.GL_TRIANGLES, 0, per * LIGHTS * 6);
    }

    void drawSmoke(float[] lp, int nlp, float density, float wind, float alpha, float r, float g, float b) {
        int per = Math.max(0, Math.min(SMOKE_PER, Math.round(SMOKE_PER * density)));
        if (per == 0 || nlp == 0) return;
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        sh.setUniformf("u_mode", 3f);
        sh.setUniformf("u_vel", 0.35f * wind, 0f, 0.15f * wind);
        sh.setUniformf("u_size", 0.32f);
        sh.setUniformf("u_alpha", alpha);
        sh.setUniformf("u_col", r, g, b);
        sh.setUniformf("u_hard", 0.0f);
        sh.setUniformf("u_glow", 0f);
        lights(lp, nlp);
        smoke.render(sh, GL20.GL_TRIANGLES, 0, per * LIGHTS * 6);
    }

    void dispose() {
        if (sh != null) sh.dispose();
        if (snow != null) snow.dispose();
        if (dust != null) dust.dispose();
        if (embers != null) embers.dispose();
        if (smoke != null) smoke.dispose();
        sh = null;
    }
}
