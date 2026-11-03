package gothic3.desktop.render3d.post;

/** GLSL (1.10 compatible) sources of the post passes. All passes draw a fullscreen quad with varying v_uv (GL orientation, v up). */
final class Shaders {
    private Shaders() { }

    static final String HEAD = "#ifdef GL_ES\nprecision highp float;\n#endif\n"
            + "varying vec2 v_uv;\nuniform float u_ldr;\n"
            // LDR fallback: working buffers hold c / (1 + c)
            + "vec3 ld(vec3 e) { return u_ldr > 0.5 ? e / max(vec3(1.0) - e, vec3(0.02)) : e; }\n"
            + "vec3 st(vec3 c) { return u_ldr > 0.5 ? c / (vec3(1.0) + c) : c; }\n"
            + "uniform vec4 u_proj;\n"      // tanHalfFovY * aspect, tanHalfFovY, near, far
            + "float linz(float d) { float z = d * 2.0 - 1.0; return 2.0 * u_proj.z * u_proj.w / (u_proj.w + u_proj.z - z * (u_proj.w - u_proj.z)); }\n";

    /** Half-res SSAO from the depth texture (reconstructed normals, 12 hemisphere samples, per-pixel rotation). */
    static final String AO = HEAD
            + "uniform sampler2D u_depth;\nuniform vec2 u_texel;\nuniform vec2 u_ao;\n"   // texel of the depth texture, (radius, bias)
            + "vec3 vpos(vec2 uv) { float z = linz(texture2D(u_depth, uv).r); vec2 nd = uv * 2.0 - 1.0; return vec3(nd.x * u_proj.x * z, nd.y * u_proj.y * z, -z); }\n"
            + "float ign(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }\n"
            + "void main() {\n"
            + "  vec2 uv = v_uv;\n  vec3 P = vpos(uv);\n"
            + "  vec2 tx = vec2(u_texel.x, 0.0), ty = vec2(0.0, u_texel.y);\n"
            + "  vec3 Pr = vpos(uv + tx), Pl = vpos(uv - tx), Pu = vpos(uv + ty), Pd = vpos(uv - ty);\n"
            + "  vec3 dx = abs(Pr.z - P.z) < abs(P.z - Pl.z) ? Pr - P : P - Pl;\n"
            + "  vec3 dy = abs(Pu.z - P.z) < abs(P.z - Pd.z) ? Pu - P : P - Pd;\n"
            + "  vec3 N = normalize(cross(dx, dy) + vec3(0.0, 0.0, 1e-6));\n  if (dot(N, -P) < 0.0) N = -N;\n"
            + "  float rot = ign(gl_FragCoord.xy) * 6.2831853;\n"
            + "  vec3 rv = vec3(cos(rot), sin(rot), 0.0);\n  vec3 T = normalize(rv - N * dot(rv, N) + vec3(1e-6));\n  vec3 B = cross(N, T);\n"
            + "  float occ = 0.0;\n"
            + "  for (int i = 0; i < 12; i++) {\n"
            + "    float fi = (float(i) + 0.5) / 12.0;\n    float a = float(i) * 2.3999632;\n    float r = sqrt(fi);\n    float h = sqrt(max(1.0 - fi, 0.0));\n"
            + "    vec3 d = T * (cos(a) * r) + B * (sin(a) * r) + N * h;\n"
            + "    vec3 S = P + d * (u_ao.x * mix(0.3, 1.0, fi));\n"
            + "    vec2 suv = vec2(S.x / (-S.z) / u_proj.x, S.y / (-S.z) / u_proj.y) * 0.5 + 0.5;\n"
            + "    float zs = linz(texture2D(u_depth, suv).r);\n"
            + "    float diff = (-S.z) - zs;\n"
            + "    float rc = smoothstep(0.0, 1.0, u_ao.x / max(abs(-P.z - zs), 1e-3));\n"
            + "    occ += (diff > u_ao.y ? 1.0 : 0.0) * rc;\n  }\n"
            + "  float ao = 1.0 - occ / 12.0;\n  gl_FragColor = vec4(ao, ao, ao, 1.0);\n}\n";

    /** Depth aware separable blur of the AO. */
    static final String AOBLUR = HEAD
            + "uniform sampler2D u_ao;\nuniform sampler2D u_depth;\nuniform vec2 u_dir;\n"
            + "void main() {\n  float zc = linz(texture2D(u_depth, v_uv).r);\n"
            + "  float sum = texture2D(u_ao, v_uv).r, wt = 1.0;\n"
            + "  for (int k = 1; k <= 3; k++) {\n    for (int s = 0; s < 2; s++) {\n"
            + "      vec2 uv = v_uv + u_dir * (float(k) * (s == 0 ? 1.0 : -1.0));\n"
            + "      float zk = linz(texture2D(u_depth, uv).r);\n"
            + "      float w = (1.0 - 0.2 * float(k)) * clamp(1.0 - abs(zk - zc) / (0.04 * zc + 0.04), 0.0, 1.0);\n"
            + "      sum += texture2D(u_ao, uv).r * w;\n      wt += w;\n    }\n  }\n"
            + "  float a = sum / wt;\n  gl_FragColor = vec4(a, a, a, 1.0);\n}\n";

    /** Decodes the scene target and applies the AO (ambient occlusion on everything that is not emissive-bright). */
    static final String RESOLVE = HEAD
            + "uniform sampler2D u_scene;\nuniform sampler2D u_ao;\nuniform sampler2D u_depth;\nuniform float u_decode;\nuniform float u_aoStr;\nuniform vec2 u_aoTexel;\n"
            + "float aoAt(vec2 uv, float zc) {\n  vec2 q = uv / u_aoTexel - 0.5;\n  vec2 b = floor(q), f = q - b;\n  float s = 0.0, w = 0.0;\n"
            + "  for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {\n"
            + "    vec2 tuv = (b + vec2(float(i), float(j)) + 0.5) * u_aoTexel;\n"
            + "    float bw = (i == 0 ? 1.0 - f.x : f.x) * (j == 0 ? 1.0 - f.y : f.y);\n"
            + "    float zk = linz(texture2D(u_depth, tuv).r);\n"
            + "    float ww = bw * max(0.001, 1.0 - abs(zk - zc) / (0.05 * zc + 0.05));\n"
            + "    s += texture2D(u_ao, tuv).r * ww;\n    w += ww;\n  }\n  return s / max(w, 1e-5);\n}\n"
            + "void main() {\n  vec3 c = texture2D(u_scene, v_uv).rgb * u_decode;\n"
            + "  if (u_aoStr > 0.0) {\n    float zc = linz(texture2D(u_depth, v_uv).r);\n    float ao = aoAt(v_uv, zc);\n"
            + "    float lum = dot(c, vec3(0.299, 0.587, 0.114));\n    float k = 1.0 - smoothstep(1.0, 1.8, lum);\n"
            + "    c *= mix(1.0, mix(1.0, ao, u_aoStr), k);\n  }\n  gl_FragColor = vec4(st(c), 1.0);\n}\n";

    static final String COC = "uniform vec4 u_dof;\nuniform vec4 u_dof2;\n"   // (focusDepth, bandHalf, ramp, mode) (tsCenter, tsBandHalf, tsRamp, -)
            + "float coc(float z, vec2 uv) {\n"
            + "  if (u_dof.w > 0.5) {\n    float d = uv.y - u_dof2.x;\n    float a = clamp((abs(d) - u_dof2.y) / u_dof2.z, 0.0, 1.0);\n    a = a * a * (3.0 - 2.0 * a);\n    return d > 0.0 ? a : -a;\n  }\n"
            + "  float d = z - u_dof.x;\n  float a = clamp((abs(d) - u_dof.y) / u_dof.z, 0.0, 1.0);\n  a = a * a * (3.0 - 2.0 * a);\n  return d > 0.0 ? a : -a;\n}\n";

    /** Half-res prefilter: colour (Karis weighted) and signed circle of confusion (stored as coc * 0.5 + 0.5). */
    static final String DOFPRE = HEAD + COC
            + "uniform sampler2D u_src;\nuniform sampler2D u_depth;\nuniform vec2 u_texel;\n"
            + "void main() {\n  vec3 acc = vec3(0.0);\n  float ws = 0.0, cs = 0.0;\n"
            + "  for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {\n"
            + "    vec2 uv = v_uv + (vec2(float(i), float(j)) - 0.5) * u_texel;\n"
            + "    vec3 c = ld(texture2D(u_src, uv).rgb);\n    float z = linz(texture2D(u_depth, uv).r);\n"
            + "    float w = 1.0 / (1.0 + dot(c, vec3(0.3, 0.5, 0.2)));\n    acc += c * w;\n    ws += w;\n    cs += coc(z, uv) * 0.25;\n  }\n"
            + "  gl_FragColor = vec4(st(acc / ws), cs * 0.5 + 0.5);\n}\n";

    /** Scatter-as-gather disc blur on the half-res target: a sample only spreads onto pixels within its own CoC, far samples never leak onto nearer pixels. */
    static final String DOFBLUR = HEAD
            + "uniform sampler2D u_pre;\nuniform vec2 u_texel;\nuniform float u_R;\n"
            + "void main() {\n  vec4 c0 = texture2D(u_pre, v_uv);\n  float cp = c0.a * 2.0 - 1.0;\n"
            + "  float reachP = abs(cp) * u_R;\n  float wself = 1.0 / (1.0 + 0.5 * reachP * reachP);\n"
            + "  vec3 sum = ld(c0.rgb) * wself;\n  float wsum = wself;\n"
            + "  for (int i = 0; i < 32; i++) {\n"
            + "    float fi = (float(i) + 0.5) / 32.0;\n    float r = sqrt(fi) * u_R;\n    float a = float(i) * 2.3999632;\n"
            + "    vec4 s = texture2D(u_pre, v_uv + vec2(cos(a), sin(a)) * r * u_texel);\n"
            + "    float cs = s.a * 2.0 - 1.0;\n    float reach = abs(cs) * u_R;\n"
            + "    float cover = clamp(reach - r + 1.0, 0.0, 1.0);\n"
            + "    if (cs > 0.0) cover *= clamp((cp - cs) / 0.25 + 1.0, 0.0, 1.0);\n"
            + "    else cover *= clamp((cp - cs) / 0.05, 0.0, 1.0);\n"
            + "    float w = cover / (1.0 + 0.5 * reach * reach);\n"
            + "    sum += ld(s.rgb) * w;\n    wsum += w;\n  }\n"
            + "  gl_FragColor = vec4(st(sum / wsum), 1.0 - wself / wsum);\n}\n";

    /** Full-res composite: sharp pixel mixed with the depth-aware (bilateral by CoC) upsampled blur. */
    static final String DOFCOMP = HEAD + COC
            + "uniform sampler2D u_src;\nuniform sampler2D u_depth;\nuniform sampler2D u_pre;\nuniform sampler2D u_blur;\nuniform vec2 u_hTexel;\n"
            + "void main() {\n  vec3 sharp = ld(texture2D(u_src, v_uv).rgb);\n  float cf = coc(linz(texture2D(u_depth, v_uv).r), v_uv);\n"
            + "  vec2 q = v_uv / u_hTexel - 0.5;\n  vec2 b = floor(q), f = q - b;\n  vec3 acc = vec3(0.0);\n  float aa = 0.0, ws = 0.0;\n"
            + "  for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {\n"
            + "    vec2 tuv = (b + vec2(float(i), float(j)) + 0.5) * u_hTexel;\n"
            + "    float bw = (i == 0 ? 1.0 - f.x : f.x) * (j == 0 ? 1.0 - f.y : f.y);\n"
            + "    float ct = texture2D(u_pre, tuv).a * 2.0 - 1.0;\n"
            + "    float w = bw / (0.03 + abs(ct - cf));\n"
            + "    vec4 bl = texture2D(u_blur, tuv);\n    acc += ld(bl.rgb) * w;\n    aa += bl.a * w;\n    ws += w;\n  }\n"
            + "  acc /= ws;\n  aa = clamp(aa / ws * 1.15, 0.0, 1.0);\n"
            + "  gl_FragColor = vec4(st(mix(sharp, acc, aa)), 1.0);\n}\n";

    // ---- bloom ----
    static final String BRIGHT = HEAD
            + "uniform sampler2D u_src;\nuniform vec2 u_texel;\nuniform vec3 u_th;\nuniform float u_flip;\n"      // (threshold, knee)
            + "vec3 smp(vec2 uv) { if (u_flip > 0.5) uv.y = 1.0 - uv.y; return pow(max(ld(texture2D(u_src, uv).rgb), vec3(0.0)), vec3(2.2)); }\n"
            + "void main() {\n  vec3 acc = vec3(0.0);\n  float ws = 0.0;\n"
            + "  for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {\n"
            + "    vec3 c = smp(v_uv + (vec2(float(i), float(j)) - 0.5) * u_texel);\n"
            + "    float w = 1.0 / (1.0 + dot(c, vec3(0.3, 0.5, 0.2)));\n    acc += c * w;\n    ws += w;\n  }\n"
            + "  vec3 c = acc / ws;\n  float br = max(c.r, max(c.g, c.b));\n  float knee = u_th.x * u_th.y + 1e-4;\n"
            + "  float soft = clamp(br - u_th.x + knee, 0.0, 2.0 * knee);\n  soft = soft * soft / (4.0 * knee);\n"
            + "  float k = max(soft, br - u_th.x) / max(br, 1e-4);\n  gl_FragColor = vec4(min(c * k, vec3(64.0)), 1.0);\n}\n";

    static final String DOWN = HEAD
            + "uniform sampler2D u_src;\nuniform vec2 u_texel;\n"
            + "void main() {\n  vec3 s = texture2D(u_src, v_uv).rgb * 4.0;\n"
            + "  s += texture2D(u_src, v_uv + vec2(-1.0, -1.0) * u_texel).rgb;\n  s += texture2D(u_src, v_uv + vec2(1.0, -1.0) * u_texel).rgb;\n"
            + "  s += texture2D(u_src, v_uv + vec2(-1.0, 1.0) * u_texel).rgb;\n  s += texture2D(u_src, v_uv + vec2(1.0, 1.0) * u_texel).rgb;\n"
            + "  gl_FragColor = vec4(s / 8.0, 1.0);\n}\n";

    static final String UP = HEAD
            + "uniform sampler2D u_src;\nuniform vec2 u_texel;\nuniform float u_w;\n"
            + "void main() {\n  vec2 t = u_texel;\n  vec3 s = texture2D(u_src, v_uv + vec2(-2.0 * t.x, 0.0)).rgb;\n"
            + "  s += texture2D(u_src, v_uv + vec2(-t.x, t.y)).rgb * 2.0;\n  s += texture2D(u_src, v_uv + vec2(0.0, 2.0 * t.y)).rgb;\n"
            + "  s += texture2D(u_src, v_uv + vec2(t.x, t.y)).rgb * 2.0;\n  s += texture2D(u_src, v_uv + vec2(2.0 * t.x, 0.0)).rgb;\n"
            + "  s += texture2D(u_src, v_uv + vec2(t.x, -t.y)).rgb * 2.0;\n  s += texture2D(u_src, v_uv + vec2(0.0, -2.0 * t.y)).rgb;\n"
            + "  s += texture2D(u_src, v_uv + vec2(-t.x, -t.y)).rgb * 2.0;\n  gl_FragColor = vec4(s / 12.0 * u_w, 1.0);\n}\n";

    // ---- final: bloom add, exposure, tone map, grade, vignette, grain, CA, sharpen, FXAA-lite, 2D: upscaler + CRT ----
    static final String FINAL = HEAD
            + "uniform sampler2D u_color;\nuniform sampler2D u_bloom;\nuniform sampler2D u_lut;\n"
            + "uniform vec2 u_px;\nuniform float u_bloomI;\nuniform vec4 u_tone;\nuniform vec3 u_wb;\nuniform vec3 u_lift;\nuniform vec3 u_gamma;\nuniform vec3 u_gain;\n"
            + "uniform float u_lutAmt;\nuniform vec4 u_fx;\nuniform vec4 u_fx2;\n"      // fx: vignette grain ca sharpen; fx2: time fxaa aspect vigRadius
            + "uniform vec4 u_crt;\nuniform vec2 u_src;\nuniform float u_up;\nuniform vec2 u_dest;\n"
            + "vec3 aces(vec3 x) { return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }\n"
            + "vec3 lutc(vec3 c) {\n  c = clamp(c, 0.0, 1.0);\n  float b = c.b * 15.0;\n  float b0 = floor(b);\n  float f = b - b0;\n  float b1 = min(b0 + 1.0, 15.0);\n"
            + "  float v = (c.g * 15.0 + 0.5) / 16.0;\n"
            + "  vec3 a = texture2D(u_lut, vec2((b0 * 16.0 + c.r * 15.0 + 0.5) / 256.0, v)).rgb;\n"
            + "  vec3 d = texture2D(u_lut, vec2((b1 * 16.0 + c.r * 15.0 + 0.5) / 256.0, v)).rgb;\n  return mix(a, d, f);\n}\n"
            + "vec3 grade(vec3 lin) {\n  lin *= u_tone.x * u_wb;\n"
            + "  vec3 tm = aces(lin * 0.9) / 0.80;\n  tm = clamp(tm, 0.0, 1.0);\n"
            + "  vec3 m = mix(clamp(lin, 0.0, 1.0), tm, u_tone.y);\n"
            + "  vec3 d = pow(max(m, vec3(0.0)), vec3(1.0 / 2.2));\n"
            + "  d = pow(max(d * u_gain + u_lift, vec3(0.0)), vec3(1.0) / u_gamma);\n"
            + "  d = (d - 0.46) * u_tone.z + 0.46;\n  float l = dot(d, vec3(0.299, 0.587, 0.114));\n  d = mix(vec3(l), d, u_tone.w);\n"
            + "  if (u_lutAmt > 0.0) d = mix(d, lutc(d), u_lutAmt);\n  return clamp(d, 0.0, 1.0);\n}\n"
            + "#ifdef IS2D\n"
            + "vec3 T(vec2 ip) { return texture2D(u_color, (ip + 0.5) / u_src).rgb; }\n"
            + "bool eq(vec3 a, vec3 b) { return all(lessThan(abs(a - b), vec3(0.004))); }\n"
            + "vec3 fetchRaw(vec2 uv) {\n  vec2 p = vec2(uv.x, 1.0 - uv.y) * u_src;\n  vec2 pi = floor(p);\n  vec2 f = p - pi;\n"
            + "  if (u_up < 0.5) return T(pi);\n"
            + "  if (u_up < 1.5) {\n    float s = u_dest.x / u_src.x;\n    vec2 pos = pi + 0.5 + clamp((f - 0.5) * s, -0.5, 0.5);\n    return texture2D(u_color, pos / u_src).rgb;\n  }\n"
            + "  vec3 P = T(pi), U = T(pi + vec2(0.0, -1.0)), D = T(pi + vec2(0.0, 1.0)), L = T(pi + vec2(-1.0, 0.0)), R = T(pi + vec2(1.0, 0.0));\n"
            + "  vec3 o = P;\n"
            + "  if (f.x < 0.5 && f.y < 0.5) { if (eq(L, U) && !eq(L, D) && !eq(U, R)) o = U; }\n"
            + "  else if (f.x >= 0.5 && f.y < 0.5) { if (eq(U, R) && !eq(U, L) && !eq(R, D)) o = R; }\n"
            + "  else if (f.x < 0.5 && f.y >= 0.5) { if (eq(D, L) && !eq(D, R) && !eq(L, U)) o = L; }\n"
            + "  else { if (eq(R, D) && !eq(D, U) && !eq(R, L)) o = R; }\n  return o;\n}\n"
            + "#else\n"
            + "vec3 fetchRaw(vec2 uv) { return ld(texture2D(u_color, uv).rgb); }\n"
            + "#endif\n"
            + "vec3 fetch(vec2 uv) {\n  vec3 lin = pow(max(fetchRaw(uv), vec3(0.0)), vec3(2.2));\n  lin += texture2D(u_bloom, uv).rgb * u_bloomI;\n  return grade(lin);\n}\n"
            + "float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }\n"
            + "float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }\n"
            + "void main() {\n  vec2 uv = v_uv;\n"
            + "#ifdef IS2D\n  if (u_crt.z > 0.0) {\n    vec2 cc = uv * 2.0 - 1.0;\n    cc *= 1.0 + u_crt.z * vec2(cc.y * cc.y, cc.x * cc.x);\n    uv = cc * 0.5 + 0.5;\n"
            + "    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }\n  }\n#endif\n"
            + "  vec3 c = fetch(uv);\n"
            + "  if (u_fx.w > 0.0 || u_fx2.y > 0.5) {\n"
            + "    vec3 n = fetch(uv + vec2(0.0, u_px.y)), s = fetch(uv - vec2(0.0, u_px.y)), e = fetch(uv + vec2(u_px.x, 0.0)), w = fetch(uv - vec2(u_px.x, 0.0));\n"
            + "    if (u_fx2.y > 0.5) {\n      float lm = lum(c), lo = min(min(lum(n), lum(s)), min(lum(e), lum(w))), hi = max(max(lum(n), lum(s)), max(lum(e), lum(w)));\n"
            + "      float rng = max(hi, lm) - min(lo, lm);\n      float k = smoothstep(0.05, 0.25, rng) * 0.5;\n      c = mix(c, (c + n + s + e + w) * 0.2, k);\n    }\n"
            + "    if (u_fx.w > 0.0) {\n      vec3 mn = min(min(min(n, s), min(e, w)), c), mx = max(max(max(n, s), max(e, w)), c);\n"
            + "      vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(1e-3)), 0.0, 1.0));\n"
            + "      vec3 wg = -amp * mix(0.125, 0.2, u_fx.w);\n      c = (c + (n + s + e + w) * wg) / (vec3(1.0) + 4.0 * wg);\n    }\n  }\n"
            + "  if (u_fx.z > 0.0) {\n    vec2 dir = (uv - 0.5) * vec2(u_fx2.z, 1.0);\n    float edge = smoothstep(0.3, 1.0, length(dir) * 1.6);\n"
            + "    vec2 off = (uv - 0.5) * edge * u_fx.z * 0.012;\n    c.r = fetch(uv + off).r;\n    c.b = fetch(uv - off).b;\n  }\n"
            + "  if (u_fx.x > 0.0) {\n    float d = length((uv - 0.5) * vec2(u_fx2.z, 1.0)) * 1.1;\n    c *= 1.0 - u_fx.x * smoothstep(u_fx2.w, u_fx2.w + 0.6, d);\n  }\n"
            + "#ifdef IS2D\n  if (u_crt.x > 0.0 || u_crt.y > 0.0) {\n    float y = (1.0 - uv.y) * u_src.y;\n    float sl = sin(fract(y) * 3.14159265);\n"
            + "    c *= (1.0 - u_crt.x * (1.0 - sl)) * (1.0 + 0.35 * u_crt.x);\n"
            + "    float xm = mod(floor(gl_FragCoord.x), 3.0);\n    vec3 pat = vec3(xm < 0.5 ? 1.0 : 0.0, (xm > 0.5 && xm < 1.5) ? 1.0 : 0.0, xm > 1.5 ? 1.0 : 0.0);\n"
            + "    c *= mix(vec3(1.0), pat * 3.0, u_crt.y * 0.6) * (1.0 + 0.0 * u_crt.y);\n  }\n#endif\n"
            + "  if (u_fx.y > 0.0) {\n    vec2 cell = floor(gl_FragCoord.xy / 2.0) + floor(u_fx2.x * 24.0) * vec2(17.13, 31.71);\n"
            + "    float g = hash(cell) - 0.5;\n    c += g * u_fx.y * (1.0 - 0.6 * abs(lum(c) * 2.0 - 1.0));\n  }\n"
            + "  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);\n}\n";

    /** Plain copy (fallback when a pass fails): scene to screen. */
    static final String BLIT = HEAD
            + "uniform sampler2D u_color;\nuniform float u_scale;\nvoid main() { gl_FragColor = vec4(clamp(ld(texture2D(u_color, v_uv).rgb) * u_scale, 0.0, 1.0), 1.0); }\n";
}
