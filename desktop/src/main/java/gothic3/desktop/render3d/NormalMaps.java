package gothic3.desktop.render3d;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import java.util.function.Function;

/**
 * Relief lighting (docs/render3d.md "Normal-map lighting"): the pixel art's own luminance (plus alpha edges) is read as a height field and
 * the main fragment shader derives a per-texel normal from it (Sobel on the texel grid, nearest sampled, so the bumps follow the pixel
 * grid), perturbing the sun, point-light and specular terms. No extra textures: the normal map is computed in the shader from the albedo
 * page, only the page's texel size (and the tile cell size of the tile sheets) is handed over per draw. Cosmetic, render thread only.
 */
public final class NormalMaps {
    private NormalMaps() { }

    /** True while the main shader runs with relief lighting; the draw layers then hand over texture page sizes. */
    static boolean active;
    private static float strength = 0.45f;
    private static boolean enabled = true, failed;

    static void configure(Function<String, String> o) {
        String v = o.apply("atmo.normals");
        enabled = v.isEmpty() || v.equalsIgnoreCase("true");
        try { strength = Float.parseFloat(o.apply("atmo.normals.strength").trim()); } catch (NumberFormatException e) { strength = 0.45f; }
        strength = Math.max(0f, Math.min(3f, strength));
    }

    /** Called with each lighting setup (shader bound). lit=false (world UI, flat look) switches the effect off. */
    static void uniforms(ShaderProgram sh, boolean lit, Camera cam) {
        active = lit && enabled && !failed && strength > 0f;
        if (failed) return;
        sh.setUniformf("u_nmK", active ? strength : 0f);
        if (active) sh.setUniformf("u_nmCam", cam.position.x, cam.position.y, cam.position.z);
    }

    static void fail(Throwable t) {
        System.err.println("[render3d] normal-map lighting disabled: " + t);
        failed = true;
        active = false;
    }

    static boolean failed() { return failed; }

    /** Texture page just bound for the main shader: its texel size, and the tile cell size in texels (0 = no cell clamping). */
    public static void page(ShaderProgram sh, Texture t, int cellW, int cellH) {
        if (!active || t == null) return;
        sh.setUniformf("u_nmSz", 1f / t.getWidth(), 1f / t.getHeight());
        sh.setUniformf("u_nmCell", cellW, cellH);
    }

    static String decl(boolean on) {
        if (!on) return "";
        return "uniform float u_nmK;\nuniform vec3 u_nmCam;\nuniform vec2 u_nmSz;\nuniform vec2 u_nmCell;\nuniform vec3 u_sunDir;\n"
                + "float nmH(vec2 q, vec2 lo, vec2 hi) {\n"
                + "  vec4 t = texture2D(u_tex, (clamp(q, lo, hi) + 0.5) * u_nmSz);\n"
                + "  return t.a * (0.6 + dot(t.rgb, vec3(0.299, 0.587, 0.114)));\n}\n";
    }

    /** Computes ndl (sun n.L with the relief), nn (shading normal) and nmSpec (sun glint); the plain variant keeps the vertex values. */
    static String main(boolean on) {
        String base = "  float ndl = v_ndl;\n  vec3 nn = normalize(v_n);\n  float nmSpec = 0.0;\n  float nmAmb = 1.0;\n";
        if (!on) return base;
        return base
                + "  if (u_nmK > 0.0) {\n"
                + "    vec2 p = floor(v_uv / u_nmSz);\n"
                + "    vec2 lo = vec2(0.0), hi = 1.0 / u_nmSz - 1.0;\n"
                + "    if (u_nmCell.x > 0.5) { lo = floor(p / u_nmCell) * u_nmCell; hi = lo + u_nmCell - 1.0; }\n"
                + "    float a0 = nmH(p + vec2(-1.0, -1.0), lo, hi), a1 = nmH(p + vec2(0.0, -1.0), lo, hi), a2 = nmH(p + vec2(1.0, -1.0), lo, hi);\n"
                + "    float b0 = nmH(p + vec2(-1.0, 0.0), lo, hi), b2 = nmH(p + vec2(1.0, 0.0), lo, hi);\n"
                + "    float c0 = nmH(p + vec2(-1.0, 1.0), lo, hi), c1 = nmH(p + vec2(0.0, 1.0), lo, hi), c2 = nmH(p + vec2(1.0, 1.0), lo, hi);\n"
                + "    vec2 g = vec2((a2 + 2.0 * b2 + c2) - (a0 + 2.0 * b0 + c0), (c0 + 2.0 * c1 + c2) - (a0 + 2.0 * a1 + a2)) * 0.25;\n"
                + "    float gm = length(g);\n"
                + "    float lum = dot(texture2D(u_tex, (p + 0.5) * u_nmSz).rgb, vec3(0.299, 0.587, 0.114));\n"
                + "    float fl = smoothstep(0.02, 0.10, gm);\n"                                                              // flat / dithered areas stay flat
                + "    float k = u_nmK * fl * mix(1.0, 0.3, smoothstep(0.62, 0.9, lum)) * 3.0;\n"                              // bright low-contrast snow stays soft
                + "    vec3 dp1 = dFdx(v_wp), dp2 = dFdy(v_wp);\n"
                + "    vec2 du1 = dFdx(v_uv), du2 = dFdy(v_uv);\n"
                + "    vec3 q2 = cross(dp2, nn), q1 = cross(nn, dp1);\n"
                + "    vec3 T = q2 * du1.x + q1 * du2.x, B = q2 * du1.y + q1 * du2.y;\n"
                + "    float im = inversesqrt(max(max(dot(T, T), dot(B, B)), 1e-12));\n"
                + "    vec3 nb = normalize(nn - k * (g.x * T + g.y * B) * im);\n"
                + "    if (v_ndl > 0.0) ndl = clamp(v_ndl + dot(nb, u_sunDir) - dot(nn, u_sunDir), 0.0, 1.0);\n"
                + "    nmAmb = clamp(1.0 + dot(nb - nn, normalize(vec3(-0.45, 0.75, 0.5))) * k * 0.9, 0.55, 1.6);\n"   // fixed view-space light: the relief also shows in shade
                + "    nn = nb;\n"
                + "    vec3 hv = normalize(u_sunDir + normalize(u_nmCam - v_wp));\n"
                + "    nmSpec = pow(max(dot(nb, hv), 0.0), 28.0) * smoothstep(0.55, 0.85, lum) * fl * u_nmK * 0.5;\n"
                + "  }\n";
    }

    static String spec(boolean on) {
        return on ? "  c.rgb += u_sunCol * (sh * nmSpec);\n" : "";
    }
}
