package gothic3.desktop.render3d.atmo;

/**
 * Wind sway of vegetation cards (docs/render3d.md "Plant sway and changing snowfall"): the GLSL shared by the main and the shadow depth vertex
 * shader. The displacement grows with the height above the ground (world y = 0 is the foot of a card), so the base stays pinned; the plants that
 * sway are named by the {@code sway[:amount]} token of {@code props.properties}. Render clock only, no game state.
 */
public final class Sway {
    private Sway() { }

    /** Uniforms and {@code swayPos}; pasted into both vertex shaders. {@code u_swayAmt} (per batch, 0 = not a plant) is set by the sprite / prop layer. */
    public static final String DECL = ""
            + "uniform float u_swayAmt;\nuniform vec4 u_swA;\nuniform vec2 u_swG;\n"        // SWAY: amt per batch; (wind dir x, z, amplitude, time); (gust 0..1, mean lean)
            + "vec3 swayPos(vec3 p) {\n"
            + "  if (u_swayAmt <= 0.0) return p;\n"
            + "  float hh = clamp(max(p.y, 0.0) / 3.0, 0.0, 1.3);\n"
            + "  float w = hh * hh;\n"                                                         // trunk-like lower parts barely move
            + "  float ph = dot(p.xz, u_swA.xy) * 0.28;\n"                                      // travelling wave along the wind
            + "  float t = u_swA.w;\n"
            + "  float osc = sin(t * 1.25 - ph) * 0.55 + sin(t * 2.3 - ph * 1.6 + 1.7) * 0.25;\n"
            + "  osc *= 1.0 + u_swG.x;\n"
            + "  osc += u_swG.x * 0.35 * sin(t * 4.1 - ph * 2.3);\n"                           // gust flutter
            + "  float d = u_swA.z * u_swayAmt * w * (u_swG.y + osc);\n"
            + "  return vec3(p.x + u_swA.x * d, p.y, p.z + u_swA.y * d * 0.6);\n"
            + "}\n";
    /** Same interface without the effect (used when the sway variant of a shader does not compile). */
    public static final String DECL_OFF = "uniform float u_swayAmt;\nvec3 swayPos(vec3 p) { return p; }\n";
}
