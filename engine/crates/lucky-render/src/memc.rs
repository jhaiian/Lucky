use glow::HasContext;

const VERTEX: &str = r#"#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"#;

const DOWN: &str = r#"#version 300 es
precision highp float;
uniform sampler2D u_src;
uniform vec2 u_inv;
uniform int u_color;
out vec4 o_luma;
void main() {
    vec4 c = texture(u_src, gl_FragCoord.xy * 2.0 * u_inv);
    float y = u_color == 1 ? dot(c.rgb, vec3(0.2126, 0.7152, 0.0722)) : c.r;
    o_luma = vec4(y, 0.0, 0.0, 1.0);
}
"#;

const SEARCH: &str = r#"#version 300 es
precision highp float;
precision highp int;
precision highp isampler2D;
uniform sampler2D u_x;
uniform sampler2D u_y;
uniform isampler2D u_prior;
uniform isampler2D u_temporal;
uniform vec2 u_inv;
uniform vec2 u_scale;
uniform float u_step;
uniform int u_mode;
uniform int u_has_temporal;
uniform ivec2 u_prior_size;
uniform ivec2 u_temporal_size;
uniform ivec2 u_field_size;
uniform int u_search;
uniform int u_stages;
layout(location = 0) out ivec4 o_field;

const float LAMBDA = 0.0025;

vec2 g_center;
vec2 g_pred;
float g_lambda;
vec2 g_best;
float g_score;
float g_floor;
float g_x[16];

void load_anchor() {
    for (int j = 0; j < 4; j++) {
        float y = g_center.y + float(j) * 2.0 - 3.0;
        for (int i = 0; i < 4; i++) {
            vec2 p = vec2(g_center.x + float(i) * 2.0 - 3.0, y);
            g_x[j * 4 + i] = texture(u_x, p * u_inv).r;
        }
    }
}

float block_texture() {
    float m = 0.0;
    for (int i = 0; i < 16; i++) {
        m += g_x[i];
    }
    m *= 0.0625;
    float d = 0.0;
    for (int i = 0; i < 16; i++) {
        d += abs(g_x[i] - m);
    }
    return d * 0.075;
}

float block_cost(vec2 v) {
    float s = 0.0;
    float a = 0.0;
    float n = 0.0;
    for (int j = 0; j < 4; j++) {
        float y = g_center.y + float(j) * 2.0 - 3.0;
        for (int i = 0; i < 4; i++) {
            vec2 p = vec2(g_center.x + float(i) * 2.0 - 3.0, y);
            vec2 ux = p * u_inv;
            vec2 uy = (p + v) * u_inv;
            float inside = step(0.0, min(min(ux.x, ux.y), min(uy.x, uy.y))) * step(max(max(ux.x, ux.y), max(uy.x, uy.y)), 1.0);
            float d = inside * (g_x[j * 4 + i] - texture(u_y, uy).r);
            s += d;
            a += abs(d);
            n += inside;
        }
    }
    return n > 0.5 ? (a - 0.75 * abs(s)) * 1.2 / n + (16.0 - n) * 0.0015 : 0.05;
}

void consider(vec2 v, float penalty) {
    float raw = block_cost(v);
    g_floor = min(g_floor, raw);
    vec2 drift = abs(v - g_pred);
    float score = raw + penalty + g_lambda * min(drift.x + drift.y, 4.0);
    if (score < g_score) {
        g_score = score;
        g_best = v;
    }
}

vec2 level_vector(ivec4 f) {
    return vec2(f.xy) * 0.25 * u_scale;
}

vec2 prior(ivec2 p) {
    return level_vector(texelFetch(u_prior, clamp(p, ivec2(0), u_prior_size - 1), 0));
}

float middle4(float a, float b, float c, float d) {
    return (a + b + c + d - min(min(a, b), min(c, d)) - max(max(a, b), max(c, d))) * 0.5;
}

vec2 middle4v(vec2 a, vec2 b, vec2 c, vec2 d) {
    return vec2(middle4(a.x, b.x, c.x, d.x), middle4(a.y, b.y, c.y, d.y));
}

void main() {
    ivec2 block = ivec2(gl_FragCoord.xy);
    g_center = (vec2(block) + 0.5) * u_step;
    load_anchor();
    g_lambda = LAMBDA;
    g_pred = vec2(0.0);
    if (u_mode == 2) {
        vec2 n0 = prior(block + ivec2(1, 0));
        vec2 n1 = prior(block + ivec2(-1, 0));
        vec2 n2 = prior(block + ivec2(0, 1));
        vec2 n3 = prior(block + ivec2(0, -1));
        g_pred = middle4v(n0, n1, n2, n3);
    } else if (u_mode == 1) {
        vec2 pf = (vec2(block) + 0.5) * 0.5 - 0.5;
        ivec2 i0 = ivec2(floor(pf));
        g_pred = middle4v(prior(i0), prior(i0 + ivec2(1, 0)), prior(i0 + ivec2(0, 1)), prior(i0 + ivec2(1, 1)));
    } else {
        g_lambda = LAMBDA * 0.4;
        if (u_has_temporal == 1) {
            vec2 at = (vec2(block) + 0.5) * vec2(u_temporal_size) / vec2(u_field_size);
            g_pred = level_vector(texelFetch(u_temporal, clamp(ivec2(at), ivec2(0), u_temporal_size - 1), 0));
        }
    }
    g_best = vec2(0.0);
    g_score = 1e9;
    g_floor = 1e9;
    consider(vec2(0.0), 0.0004);
    consider(g_pred, 0.0);
    if (u_mode == 2) {
        consider(prior(block), 0.0);
        consider(prior(block + ivec2(1, 0)), 0.0006);
        consider(prior(block + ivec2(-1, 0)), 0.0006);
        consider(prior(block + ivec2(0, 1)), 0.0006);
        consider(prior(block + ivec2(0, -1)), 0.0006);
        consider(prior(block + ivec2(1, 1)), 0.0008);
        consider(prior(block + ivec2(-1, -1)), 0.0008);
        consider(prior(block + ivec2(1, -1)), 0.0008);
        consider(prior(block + ivec2(-1, 1)), 0.0008);
    } else if (u_mode == 1) {
        vec2 pf = (vec2(block) + 0.5) * 0.5 - 0.5;
        ivec2 i0 = ivec2(floor(pf));
        consider(prior(i0), 0.0);
        consider(prior(i0 + ivec2(1, 0)), 0.0);
        consider(prior(i0 + ivec2(0, 1)), 0.0);
        consider(prior(i0 + ivec2(1, 1)), 0.0);
        consider(prior(i0 + ivec2(-1, 0)), 0.0008);
        consider(prior(i0 + ivec2(0, -1)), 0.0008);
        if (u_search > 0) {
            for (int dy = -8; dy <= 8; dy += 2) {
                if (dy < -u_search || dy > u_search) {
                    continue;
                }
                for (int dx = -8; dx <= 8; dx += 2) {
                    if (dx < -u_search || dx > u_search) {
                        continue;
                    }
                    consider(vec2(float(dx), float(dy)), 0.006 + 0.0002 * float(abs(dx) + abs(dy)));
                }
            }
        }
    } else {
        for (int dy = -8; dy <= 8; dy++) {
            if (dy < -u_search || dy > u_search) {
                continue;
            }
            for (int dx = -8; dx <= 8; dx++) {
                if (dx < -u_search || dx > u_search) {
                    continue;
                }
                consider(vec2(float(dx), float(dy)), 0.00015 * float(abs(dx) + abs(dy)));
            }
        }
    }
    if (u_has_temporal == 1) {
        vec2 at = (vec2(block) + 0.5) * vec2(u_temporal_size) / vec2(u_field_size);
        ivec2 tb = clamp(ivec2(at), ivec2(0), u_temporal_size - 1);
        consider(level_vector(texelFetch(u_temporal, tb, 0)), 0.0010);
    }
    g_lambda = 0.0;
    g_score = block_cost(g_best);
    float stride = u_mode == 2 ? 0.5 : 1.0;
    for (int s = 0; s < 4; s++) {
        if (s >= u_stages) {
            break;
        }
        vec2 c = g_best;
        consider(c + vec2(stride, 0.0), 0.0006);
        consider(c - vec2(stride, 0.0), 0.0006);
        consider(c + vec2(0.0, stride), 0.0006);
        consider(c - vec2(0.0, stride), 0.0006);
        stride *= 0.5;
    }
    vec2 screen = g_best / u_scale * 4.0;
    o_field = ivec4(ivec2(round(screen)), int(min(g_floor, 7.0) * 4096.0 + 0.5), int(min(block_texture(), 7.0) * 4096.0 + 0.5));
}
"#;

const MEDIAN: &str = r#"#version 300 es
precision highp float;
precision highp int;
precision highp isampler2D;
uniform isampler2D u_field;
uniform ivec2 u_size;
layout(location = 0) out ivec4 o_field;

#define ORDER(a, b) { int lo = min(a, b); b = max(a, b); a = lo; }

int median9(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
    ORDER(p1, p2) ORDER(p4, p5) ORDER(p7, p8)
    ORDER(p0, p1) ORDER(p3, p4) ORDER(p6, p7)
    ORDER(p1, p2) ORDER(p4, p5) ORDER(p7, p8)
    ORDER(p0, p3) ORDER(p5, p8) ORDER(p4, p7)
    ORDER(p3, p6) ORDER(p1, p4) ORDER(p2, p5)
    ORDER(p4, p7) ORDER(p4, p2) ORDER(p6, p4)
    ORDER(p4, p2)
    return p4;
}

ivec4 at(ivec2 p) {
    return texelFetch(u_field, clamp(p, ivec2(0), u_size - 1), 0);
}

void main() {
    ivec2 c = ivec2(gl_FragCoord.xy);
    ivec4 a = at(c + ivec2(-1, -1));
    ivec4 b = at(c + ivec2(0, -1));
    ivec4 d = at(c + ivec2(1, -1));
    ivec4 e = at(c + ivec2(-1, 0));
    ivec4 f = at(c);
    ivec4 g = at(c + ivec2(1, 0));
    ivec4 h = at(c + ivec2(-1, 1));
    ivec4 i = at(c + ivec2(0, 1));
    ivec4 j = at(c + ivec2(1, 1));
    int x = median9(a.x, b.x, d.x, e.x, f.x, g.x, h.x, i.x, j.x);
    int y = median9(a.y, b.y, d.y, e.y, f.y, g.y, h.y, i.y, j.y);
    o_field = ivec4(x, y, f.z, f.w);
}
"#;

const DENSE: &str = r#"#version 300 es
precision highp float;
precision highp int;
precision highp isampler2D;
uniform sampler2D u_x;
uniform sampler2D u_y;
uniform isampler2D u_grid;
uniform ivec2 u_grid_size;
uniform vec2 u_inv;
uniform vec2 u_scale;
uniform float u_step;
layout(location = 0) out ivec4 o_flow;

float g_x0;
float g_x1;
float g_x2;
float g_x3;
float g_x4;

float sx(vec2 p) {
    return texture(u_x, p * u_inv).r;
}

float sy(vec2 q) {
    return texture(u_y, q * u_inv).r;
}

void load_anchor(vec2 p) {
    g_x0 = sx(p);
    g_x1 = sx(p + vec2(1.0, 0.0));
    g_x2 = sx(p - vec2(1.0, 0.0));
    g_x3 = sx(p + vec2(0.0, 1.0));
    g_x4 = sx(p - vec2(0.0, 1.0));
}

float cost(vec2 p, vec2 v) {
    vec2 q = p + v;
    float d0 = g_x0 - sy(q);
    float d1 = g_x1 - sy(q + vec2(1.0, 0.0));
    float d2 = g_x2 - sy(q - vec2(1.0, 0.0));
    float d3 = g_x3 - sy(q + vec2(0.0, 1.0));
    float d4 = g_x4 - sy(q - vec2(0.0, 1.0));
    float a = abs(d0) + abs(d1) + abs(d2) + abs(d3) + abs(d4);
    float s = d0 + d1 + d2 + d3 + d4;
    vec2 u = q * u_inv;
    float outside = 1.0 - step(0.0, min(u.x, u.y)) * step(max(u.x, u.y), 1.0);
    return (a - 0.75 * abs(s)) * 0.24 + outside * 0.03;
}

vec2 grid(ivec2 p) {
    return vec2(texelFetch(u_grid, clamp(p, ivec2(0), u_grid_size - 1), 0).xy) * 0.25 * u_scale;
}

void main() {
    vec2 p = gl_FragCoord.xy;
    load_anchor(p);
    vec2 g = p / u_step - 0.5;
    vec2 fl = floor(g);
    vec2 f = g - fl;
    ivec2 i0 = ivec2(fl);
    vec2 v00 = grid(i0);
    vec2 v10 = grid(i0 + ivec2(1, 0));
    vec2 v01 = grid(i0 + ivec2(0, 1));
    vec2 v11 = grid(i0 + ivec2(1, 1));
    vec2 best = mix(mix(v00, v10, f.x), mix(v01, v11, f.x), f.y);
    float e = cost(p, best);
    float c = cost(p, v00) + 0.015;
    if (c < e) {
        e = c;
        best = v00;
    }
    c = cost(p, vec2(0.0)) + 0.012;
    if (c < e) {
        e = c;
        best = vec2(0.0);
    }
    c = cost(p, v10) + 0.015;
    if (c < e) {
        e = c;
        best = v10;
    }
    c = cost(p, v01) + 0.015;
    if (c < e) {
        e = c;
        best = v01;
    }
    c = cost(p, v11) + 0.015;
    if (c < e) {
        e = c;
        best = v11;
    }
    vec2 center = best;
    c = cost(p, center + vec2(0.25, 0.0)) + 0.0005;
    if (c < e) {
        e = c;
        best = center + vec2(0.25, 0.0);
    }
    c = cost(p, center - vec2(0.25, 0.0)) + 0.0005;
    if (c < e) {
        e = c;
        best = center - vec2(0.25, 0.0);
    }
    c = cost(p, center + vec2(0.0, 0.25)) + 0.0005;
    if (c < e) {
        e = c;
        best = center + vec2(0.0, 0.25);
    }
    c = cost(p, center - vec2(0.0, 0.25)) + 0.0005;
    if (c < e) {
        e = c;
        best = center - vec2(0.0, 0.25);
    }
    o_flow = ivec4(ivec2(round(best / u_scale * 4.0)), int(min(e, 7.0) * 4096.0 + 0.5), 0);
}
"#;

const TILES: &str = r#"#version 300 es
precision highp float;
precision highp int;
uniform sampler2D u_a;
uniform sampler2D u_b;
uniform ivec2 u_size;
out vec4 o_tile;
void main() {
    ivec2 base = ivec2(gl_FragCoord.xy) * 8;
    float peak = 0.0;
    float sum = 0.0;
    float lightA = 0.0;
    float lightB = 0.0;
    float count = 0.0;
    for (int j = 0; j < 8; j++) {
        for (int i = 0; i < 8; i++) {
            ivec2 p = base + ivec2(i, j);
            if (p.x < u_size.x && p.y < u_size.y) {
                float a = texelFetch(u_a, p, 0).r;
                float b = texelFetch(u_b, p, 0).r;
                float d = abs(a - b);
                peak = max(peak, d);
                sum += d;
                lightA += a;
                lightB += b;
                count += 1.0;
            }
        }
    }
    o_tile = count > 0.0 ? vec4(peak, sum / count, lightA / count, lightB / count) : vec4(0.0);
}
"#;

const STATS: &str = r#"#version 300 es
precision highp float;
precision highp int;
precision highp isampler2D;
uniform sampler2D u_tiles;
uniform isampler2D u_field;
uniform isampler2D u_previous;
uniform ivec2 u_tiles_size;
uniform ivec2 u_field_size;
uniform int u_has_field;
uniform int u_has_previous;
out vec4 o_stats;

vec2 lost(ivec4 cell) {
    float detail = float(cell.w) / 4096.0;
    float textured = step(0.012, detail);
    return vec2(textured, textured * step(0.3 * detail + 0.004, float(cell.z) / 4096.0));
}

void main() {
    float peak = 0.0;
    float mean = 0.0;
    float lightA = 0.0;
    float lightB = 0.0;
    for (int y = 0; y < 64; y++) {
        if (y >= u_tiles_size.y) {
            break;
        }
        for (int x = 0; x < 64; x++) {
            if (x >= u_tiles_size.x) {
                break;
            }
            vec4 t = texelFetch(u_tiles, ivec2(x, y), 0);
            peak = max(peak, t.r);
            mean += t.g;
            lightA += t.b;
            lightB += t.a;
        }
    }
    float tiles = float(max(u_tiles_size.x * u_tiles_size.y, 1));
    mean /= tiles;
    lightA /= tiles;
    lightB /= tiles;
    float fade = abs(lightA - lightB) / ((lightA + lightB) * 0.5 + 0.02);
    vec2 now = vec2(0.0);
    vec2 before = vec2(0.0);
    if (u_has_field == 1) {
        for (int j = 0; j < 24; j++) {
            for (int i = 0; i < 24; i++) {
                ivec2 p = clamp(ivec2((vec2(float(i), float(j)) + 0.5) * vec2(u_field_size) / 24.0), ivec2(0), u_field_size - 1);
                now += lost(texelFetch(u_field, p, 0));
                if (u_has_previous == 1) {
                    before += lost(texelFetch(u_previous, p, 0));
                }
            }
        }
    }
    float share = now.x >= 20.0 ? now.y / now.x : 0.0;
    bool cut;
    if (before.x >= 20.0) {
        float earlier = before.y / before.x;
        cut = share > 0.9 || (share > 0.25 && share > earlier * 2.0 + 0.2);
    } else {
        cut = share > 0.7;
    }
    o_stats = vec4(peak, mean, max(smoothstep(0.4, 0.6, share), smoothstep(0.3, 0.45, fade)), cut ? 1.0 : 0.0);
}
"#;

const SELECT: &str = r#"#version 300 es
precision highp float;
precision highp int;
precision highp isampler2D;
uniform isampler2D u_fwd;
uniform isampler2D u_bwd;
uniform sampler2D u_la;
uniform sampler2D u_lb;
uniform ivec2 u_flow_size;
uniform vec2 u_flow_scale;
uniform vec2 u_inv;
uniform vec4 u_rect;
uniform float u_t;
uniform int u_iterations;
uniform int u_reach;
layout(location = 0) out ivec4 o_pick;

float g_cell;
vec2 g_p;
float g_t;
vec2 g_best;
float g_score;
float g_okA;
float g_okB;
float g_err;
vec2 g_here_f;
vec2 g_here_b;

ivec4 fwd(vec2 p) {
    return texelFetch(u_fwd, clamp(ivec2(p * u_flow_scale), ivec2(0), u_flow_size - 1), 0);
}

ivec4 bwd(vec2 p) {
    return texelFetch(u_bwd, clamp(ivec2(p * u_flow_scale), ivec2(0), u_flow_size - 1), 0);
}

float inside(vec2 p) {
    return step(u_rect.x, p.x) * step(u_rect.y, p.y) * step(p.x, u_rect.z) * step(p.y, u_rect.w);
}

void candidate(vec2 v, float bias) {
    vec2 q = g_p - g_t * v;
    vec2 r = g_p + (1.0 - g_t) * v;
    ivec4 fq = fwd(q);
    ivec4 br = bwd(r);
    float tolerance = g_cell * 1.5 + 0.08 * length(v);
    float okA = (1.0 - smoothstep(tolerance, tolerance * 2.5, length(vec2(fq.xy) * 0.25 - v))) * inside(q);
    float okB = (1.0 - smoothstep(tolerance, tolerance * 2.5, length(vec2(br.xy) * 0.25 + v))) * inside(r);
    okB = max(okB, (1.0 - inside(q)) * inside(r) * (1.0 - smoothstep(tolerance, tolerance * 2.5, length(g_here_f - v))));
    okA = max(okA, (1.0 - inside(r)) * inside(q) * (1.0 - smoothstep(tolerance, tolerance * 2.5, length(g_here_b + v))));
    float mismatch = abs(texture(u_la, q * u_inv).r - texture(u_lb, r * u_inv).r);
    float score = okA * okB * (2.0 - 3.0 * mismatch) + 0.6 * max(okA, okB) + 0.4 * min(okA, okB) - bias;
    if (score > g_score) {
        g_score = score;
        g_best = v;
        g_okA = okA;
        g_okB = okB;
        g_err = (float(fq.z) + float(br.z)) / 8192.0;
    }
}

void main() {
    vec2 p = (gl_FragCoord.xy) / u_flow_scale;
    float t = u_t;
    g_cell = 1.0 / min(u_flow_scale.x, u_flow_scale.y);
    g_p = p;
    g_t = t;
    g_score = -10.0;
    g_best = vec2(0.0);
    g_okA = 0.0;
    g_okB = 0.0;
    g_err = 1.0;
    g_here_f = vec2(fwd(p).xy) * 0.25;
    g_here_b = vec2(bwd(p).xy) * 0.25;
    vec2 q = p;
    ivec4 fq = fwd(q);
    for (int i = 0; i < 4; i++) {
        if (i >= u_iterations) {
            break;
        }
        q = p - t * vec2(fq.xy) * 0.25;
        fq = fwd(q);
    }
    vec2 r = p;
    ivec4 br = bwd(r);
    for (int i = 0; i < 4; i++) {
        if (i >= u_iterations) {
            break;
        }
        r = p - (1.0 - t) * vec2(br.xy) * 0.25;
        br = bwd(r);
    }
    candidate(vec2(fq.xy) * 0.25, 0.0);
    candidate(-vec2(br.xy) * 0.25, 0.0);
    candidate(g_here_f, 0.01);
    candidate(-g_here_b, 0.01);
    float reach = g_cell * 3.0;
    for (int k = 0; k < 2; k++) {
        if (k >= u_reach) {
            break;
        }
        float d = reach * float(k + 1);
        candidate(vec2(fwd(p + vec2(d, 0.0)).xy) * 0.25, 0.02);
        candidate(vec2(fwd(p - vec2(d, 0.0)).xy) * 0.25, 0.02);
        candidate(vec2(fwd(p + vec2(0.0, d)).xy) * 0.25, 0.02);
        candidate(vec2(fwd(p - vec2(0.0, d)).xy) * 0.25, 0.02);
        candidate(-vec2(bwd(p + vec2(d, 0.0)).xy) * 0.25, 0.02);
        candidate(-vec2(bwd(p - vec2(d, 0.0)).xy) * 0.25, 0.02);
        candidate(-vec2(bwd(p + vec2(0.0, d)).xy) * 0.25, 0.02);
        candidate(-vec2(bwd(p - vec2(0.0, d)).xy) * 0.25, 0.02);
    }
    o_pick = ivec4(ivec2(round(g_best * 4.0)), int(g_okA * 127.0 + 0.5) + int(g_okB * 127.0 + 0.5) * 128, int(min(g_err, 7.0) * 4096.0 + 0.5));
}
"#;
const SETTLE: &str = r#"#version 300 es
precision highp float;
precision highp int;
precision highp isampler2D;
uniform isampler2D u_pick;
uniform ivec2 u_size;
layout(location = 0) out ivec4 o_pick;

#define ORDER(a, b) { int lo = min(a, b); b = max(a, b); a = lo; }

int median9(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
    ORDER(p1, p2) ORDER(p4, p5) ORDER(p7, p8)
    ORDER(p0, p1) ORDER(p3, p4) ORDER(p6, p7)
    ORDER(p1, p2) ORDER(p4, p5) ORDER(p7, p8)
    ORDER(p0, p3) ORDER(p5, p8) ORDER(p4, p7)
    ORDER(p3, p6) ORDER(p1, p4) ORDER(p2, p5)
    ORDER(p4, p7) ORDER(p4, p2) ORDER(p6, p4)
    ORDER(p4, p2)
    return p4;
}

ivec4 at(ivec2 p) {
    return texelFetch(u_pick, clamp(p, ivec2(0), u_size - 1), 0);
}

void main() {
    ivec2 c = ivec2(gl_FragCoord.xy);
    ivec4 k[9];
    for (int j = 0; j < 3; j++) {
        for (int i = 0; i < 3; i++) {
            k[j * 3 + i] = at(c + ivec2(i - 1, j - 1));
        }
    }
    ivec2 m = ivec2(
        median9(k[0].x, k[1].x, k[2].x, k[3].x, k[4].x, k[5].x, k[6].x, k[7].x, k[8].x),
        median9(k[0].y, k[1].y, k[2].y, k[3].y, k[4].y, k[5].y, k[6].y, k[7].y, k[8].y)
    );
    ivec4 best = k[4];
    int gap = abs(best.x - m.x) + abs(best.y - m.y);
    for (int n = 0; n < 9; n++) {
        int g = abs(k[n].x - m.x) + abs(k[n].y - m.y);
        if (g < gap) {
            gap = g;
            best = k[n];
        }
    }
    o_pick = ivec4(m, best.z, best.w);
}
"#;

const MASK: &str = r#"#version 300 es
precision highp float;
precision highp int;
precision highp isampler2D;
uniform isampler2D u_pick;
uniform ivec2 u_size;
out vec4 o_risk;

void main() {
    ivec2 c = ivec2(gl_FragCoord.xy);
    ivec4 k = texelFetch(u_pick, clamp(c, ivec2(0), u_size - 1), 0);
    float okA = float(k.z % 128) / 127.0;
    float okB = float(k.z / 128) / 127.0;
    float err = float(k.w) / 4096.0;
    float risk = smoothstep(0.14, 0.3, err + (1.0 - max(okA, okB)) * 0.2);
    o_risk = vec4(risk, 0.0, 0.0, 1.0);
}
"#;

const SOFTEN: &str = r#"#version 300 es
precision highp float;
uniform sampler2D u_src;
uniform sampler2D u_stats;
uniform vec2 u_inv;
out vec4 o_risk;

void main() {
    vec2 p = gl_FragCoord.xy;
    float sum = 0.0;
    float peak = 0.0;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            float r = texture(u_src, (p + vec2(float(i), float(j)) * 2.0) * u_inv).r;
            sum += r;
            peak = max(peak, r);
        }
    }
    vec4 stats = texture(u_stats, vec2(0.5));
    o_risk = vec4(max(sum / 25.0, peak * 0.6), stats.a, stats.b, 1.0);
}
"#;

const RENDER: &str = r#"#version 300 es
precision highp float;
precision highp int;
precision highp isampler2D;
uniform sampler2D u_a;
uniform sampler2D u_b;
uniform isampler2D u_pick;
uniform sampler2D u_risk;
uniform ivec2 u_flow_size;
uniform vec2 u_flow_scale;
uniform vec2 u_inv;
uniform vec2 u_unit;
uniform vec4 u_rect;
uniform float u_t;
uniform float u_dither;
out vec4 o_color;

vec3 dither_levels(vec3 color) {
    if (u_dither <= 0.0) {
        return color;
    }
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    return color + vec3((noise - 0.5) / u_dither);
}

vec3 sa(vec2 p) {
    return texture(u_a, p * u_inv).rgb;
}

vec3 sb(vec2 p) {
    return texture(u_b, p * u_inv).rgb;
}

float inside(vec2 p) {
    return step(u_rect.x, p.x) * step(u_rect.y, p.y) * step(p.x, u_rect.z) * step(p.y, u_rect.w);
}

ivec4 pick_at(ivec2 c) {
    return texelFetch(u_pick, clamp(c, ivec2(0), u_flow_size - 1), 0);
}

vec3 warp(vec2 p, vec2 v, float okA, float okB) {
    float t = u_t;
    vec2 qa = p - t * v;
    vec2 rb = p + (1.0 - t) * v;
    vec3 colorA = sa(qa);
    vec3 colorB = sb(rb);
    float wa = (1.0 - t) * mix(0.04, 1.0, okA) * inside(qa);
    float wb = t * mix(0.04, 1.0, okB) * inside(rb);
    return (wa + wb) > 1e-4 ? (colorA * wa + colorB * wb) / (wa + wb) : mix(sa(p), sb(p), t);
}

void main() {
    vec2 p = gl_FragCoord.xy;
    float t = u_t;
    vec3 mask = texture(u_risk, p * u_inv).rgb;
    if (mask.g > 0.5) {
        o_color = vec4(dither_levels(t < 0.5 ? sa(p) : sb(p)), 1.0);
        return;
    }
    if (inside(p) < 0.5) {
        o_color = vec4(dither_levels(mix(sa(p), sb(p), t)), 1.0);
        return;
    }
    vec2 g = p * u_flow_scale - 0.5;
    vec2 cell = floor(g);
    vec2 f = g - cell;
    ivec2 c = ivec2(cell);
    ivec4 k0 = pick_at(c);
    ivec4 k1 = pick_at(c + ivec2(1, 0));
    ivec4 k2 = pick_at(c + ivec2(0, 1));
    ivec4 k3 = pick_at(c + ivec2(1, 1));
    vec2 v0 = vec2(k0.xy) * u_unit;
    vec2 v1 = vec2(k1.xy) * u_unit;
    vec2 v2 = vec2(k2.xy) * u_unit;
    vec2 v3 = vec2(k3.xy) * u_unit;
    ivec4 zs = ivec4(k0.z, k1.z, k2.z, k3.z);
    vec4 okA = vec4(zs % 128) / 127.0;
    vec4 okB = vec4(zs / 128) / 127.0;
    vec4 errs = vec4(k0.w, k1.w, k2.w, k3.w) / 4096.0;
    vec4 bilinear = vec4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y), (1.0 - f.x) * f.y, f.x * f.y);
    vec2 lo = min(min(v0, v1), min(v2, v3));
    vec2 hi = max(max(v0, v1), max(v2, v3));
    vec3 color;
    float err;
    float okBest;
    if (length(hi - lo) < 1.25) {
        vec2 v = v0 * bilinear.x + v1 * bilinear.y + v2 * bilinear.z + v3 * bilinear.w;
        color = warp(p, v, dot(bilinear, okA), dot(bilinear, okB));
        err = dot(bilinear, errs);
        okBest = dot(bilinear, max(okA, okB));
    } else {
        vec3 w0 = warp(p, v0, okA.x, okB.x);
        vec3 w1 = warp(p, v1, okA.y, okB.y);
        vec3 w2 = warp(p, v2, okA.z, okB.z);
        vec3 w3 = warp(p, v3, okA.w, okB.w);
        vec4 both = okA * okB;
        vec4 cost = (1.0 - both) * 0.05 + (1.0 - bilinear) * 0.03 + errs * 0.25;
        float least = min(min(cost.x, cost.y), min(cost.z, cost.w));
        vec4 weight = exp((least - cost) * 25.0);
        weight /= dot(weight, vec4(1.0));
        color = w0 * weight.x + w1 * weight.y + w2 * weight.z + w3 * weight.w;
        err = dot(weight, errs);
        okBest = dot(weight, max(okA, okB));
    }
    err += (1.0 - okBest) * 0.2;
    float fallback = max(max(0.85 * smoothstep(0.06, 0.18, err), smoothstep(0.1, 0.45, mask.r)), mask.b);
    if (fallback > 0.002) {
        color = mix(color, mix(sa(p), sb(p), smoothstep(0.25, 0.75, t)), fallback);
    }
    o_color = vec4(dither_levels(color), 1.0);
}
"#;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MemcParams {
    pub level: u8,
}

impl Default for MemcParams {
    fn default() -> Self {
        Self { level: 2 }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct MemcFrame {
    pub texture: glow::Texture,
    pub version: u64,
}

struct Target {
    texture: glow::Texture,
    framebuffer: glow::Framebuffer,
    width: i32,
    height: i32,
}

impl Target {
    unsafe fn new(gl: &glow::Context, format: u32, width: i32, height: i32, linear: bool) -> Result<Self, String> {
        unsafe {
            let texture = gl.create_texture()?;
            gl.bind_texture(glow::TEXTURE_2D, Some(texture));
            gl.tex_storage_2d(glow::TEXTURE_2D, 1, format, width, height);
            let filter = if linear { glow::LINEAR } else { glow::NEAREST } as i32;
            gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_MIN_FILTER, filter);
            gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_MAG_FILTER, filter);
            gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_WRAP_S, glow::CLAMP_TO_EDGE as i32);
            gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_WRAP_T, glow::CLAMP_TO_EDGE as i32);
            let framebuffer = match gl.create_framebuffer() {
                Ok(framebuffer) => framebuffer,
                Err(error) => {
                    gl.delete_texture(texture);
                    return Err(error);
                }
            };
            gl.bind_framebuffer(glow::FRAMEBUFFER, Some(framebuffer));
            gl.framebuffer_texture_2d(glow::FRAMEBUFFER, glow::COLOR_ATTACHMENT0, glow::TEXTURE_2D, Some(texture), 0);
            let complete = gl.check_framebuffer_status(glow::FRAMEBUFFER) == glow::FRAMEBUFFER_COMPLETE;
            gl.bind_framebuffer(glow::FRAMEBUFFER, None);
            if !complete {
                gl.delete_framebuffer(framebuffer);
                gl.delete_texture(texture);
                return Err("motion target is incomplete".into());
            }
            Ok(Self { texture, framebuffer, width, height })
        }
    }

    unsafe fn delete(&self, gl: &glow::Context) {
        unsafe {
            gl.delete_framebuffer(self.framebuffer);
            gl.delete_texture(self.texture);
        }
    }

    unsafe fn bind(&self, gl: &glow::Context) {
        unsafe {
            gl.bind_framebuffer(glow::FRAMEBUFFER, Some(self.framebuffer));
            gl.viewport(0, 0, self.width, self.height);
        }
    }
}

struct Pyramid {
    version: Option<u64>,
    levels: Vec<Target>,
    used: u64,
}

struct Program {
    program: glow::Program,
    uniforms: Vec<Option<glow::UniformLocation>>,
}

impl Program {
    unsafe fn new(gl: &glow::Context, fragment: &str, samplers: &[&str], names: &[&str]) -> Result<Self, String> {
        unsafe {
            let program = link(gl, VERTEX, fragment)?;
            gl.use_program(Some(program));
            for (unit, name) in samplers.iter().enumerate() {
                let location = gl.get_uniform_location(program, name);
                gl.uniform_1_i32(location.as_ref(), unit as i32);
            }
            let uniforms = names.iter().map(|name| gl.get_uniform_location(program, name)).collect();
            Ok(Self { program, uniforms })
        }
    }

    fn at(&self, index: usize) -> Option<&glow::UniformLocation> {
        self.uniforms.get(index).and_then(|u| u.as_ref())
    }
}

unsafe fn compile(gl: &glow::Context, kind: u32, source: &str) -> Result<glow::Shader, String> {
    unsafe {
        let shader = gl.create_shader(kind)?;
        gl.shader_source(shader, source);
        gl.compile_shader(shader);
        if !gl.get_shader_compile_status(shader) {
            let message = gl.get_shader_info_log(shader);
            gl.delete_shader(shader);
            return Err(message);
        }
        Ok(shader)
    }
}

unsafe fn link(gl: &glow::Context, vertex_source: &str, fragment_source: &str) -> Result<glow::Program, String> {
    unsafe {
        let vertex = compile(gl, glow::VERTEX_SHADER, vertex_source)?;
        let fragment = match compile(gl, glow::FRAGMENT_SHADER, fragment_source) {
            Ok(shader) => shader,
            Err(error) => {
                gl.delete_shader(vertex);
                return Err(error);
            }
        };
        let program = gl.create_program()?;
        gl.attach_shader(program, vertex);
        gl.attach_shader(program, fragment);
        gl.link_program(program);
        gl.delete_shader(vertex);
        gl.delete_shader(fragment);
        if !gl.get_program_link_status(program) {
            let message = gl.get_program_info_log(program);
            gl.delete_program(program);
            return Err(message);
        }
        Ok(program)
    }
}

pub(crate) const PYRAMIDS: usize = 3;
pub(crate) const READBACKS: usize = 3;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Quality {
    pub(crate) limit: i32,
    pub(crate) step: i32,
    pub(crate) propagate: u32,
    pub(crate) stages: i32,
    pub(crate) iterations: i32,
    pub(crate) reach: i32,
}

pub(crate) fn quality(level: u8) -> Quality {
    match level {
        1 => Quality { limit: 400, step: 8, propagate: 0, stages: 3, iterations: 2, reach: 1 },
        3 => Quality { limit: 1600, step: 4, propagate: 2, stages: 4, iterations: 4, reach: 2 },
        _ => Quality { limit: 800, step: 4, propagate: 1, stages: 3, iterations: 3, reach: 1 },
    }
}

pub(crate) fn level_count(width: i32, height: i32) -> usize {
    let mut count = 0;
    let (mut w, mut h) = (width, height);
    while (w.max(h) + 1) / 2 >= 24 && count < 9 {
        w = (w + 1) / 2;
        h = (h + 1) / 2;
        count += 1;
    }
    count.max(1)
}

pub(crate) fn level_size(width: i32, height: i32, level: usize) -> (i32, i32) {
    let (mut w, mut h) = (width, height);
    for _ in 0..=level {
        w = ((w + 1) / 2).max(1);
        h = ((h + 1) / 2).max(1);
    }
    (w, h)
}

pub(crate) fn level_for(width: i32, height: i32, limit: i32, levels: usize) -> usize {
    let mut level = 0;
    while level + 1 < levels {
        let (w, h) = level_size(width, height, level);
        if w.max(h) as f32 <= limit as f32 * 1.25 {
            break;
        }
        level += 1;
    }
    level
}

pub fn scaled_size(size: (i32, i32), scale: f32) -> (i32, i32) {
    if scale >= 0.999 {
        return size;
    }
    (((size.0 as f32 * scale).round() as i32).max(16), ((size.1 as f32 * scale).round() as i32).max(16))
}

pub(crate) fn search_range(coarsest: bool, above_base: bool, width: i32, height: i32) -> i32 {
    if coarsest || (above_base && width.max(height) <= 360) { 8 } else { 0 }
}

pub(crate) fn grid(size: (i32, i32), step: i32) -> (i32, i32) {
    (((size.0 + step - 1) / step).max(1), ((size.1 + step - 1) / step).max(1))
}

pub(crate) fn blocks(size: (i32, i32)) -> (i32, i32) {
    grid(size, 8)
}

pub const DUPLICATE_LIMIT: f32 = 0.06;

struct Readback {
    buffer: glow::Buffer,
    pending: Option<(glow::Fence, (u64, u64))>,
}

#[derive(Default)]
struct Direction {
    fields: Vec<Option<Target>>,
    base: [Option<Target>; 3],
    last: Option<usize>,
}

impl Direction {
    unsafe fn release(&mut self, gl: &glow::Context) {
        unsafe {
            for field in self.fields.drain(..).flatten() {
                field.delete(gl);
            }
            for target in self.base.iter_mut().filter_map(|t| t.take()) {
                target.delete(gl);
            }
            self.last = None;
        }
    }
}

#[derive(Default)]
struct PairWork {
    key: Option<(u64, u64)>,
    stage: u8,
    dense: [Option<Target>; 2],
    stats: Option<Target>,
}

impl PairWork {
    fn restart(&mut self, key: (u64, u64)) {
        self.key = Some(key);
        self.stage = 0;
    }

    unsafe fn release_dense(&mut self, gl: &glow::Context) {
        unsafe {
            for target in self.dense.iter_mut().filter_map(|t| t.take()) {
                target.delete(gl);
            }
            self.key = None;
            self.stage = 0;
        }
    }

    unsafe fn release(&mut self, gl: &glow::Context) {
        unsafe {
            self.release_dense(gl);
            if let Some(stats) = self.stats.take() {
                stats.delete(gl);
            }
        }
    }
}

pub struct Memc {
    vao: glow::VertexArray,
    dummy: glow::Texture,
    linear: glow::Sampler,
    nearest: glow::Sampler,
    down: Program,
    search: Program,
    median: Program,
    dense: Program,
    tiles: Program,
    stats: Program,
    select: Program,
    settle: Program,
    mask: Program,
    soften: Program,
    render: Program,
    size: (i32, i32),
    levels: usize,
    pyramids: Vec<Pyramid>,
    clock: u64,
    layout: Option<(usize, Quality)>,
    flow: [Direction; 2],
    tile_level: usize,
    pair_tiles: Option<Target>,
    picks: [Option<Target>; 2],
    masks: [Option<Target>; 2],
    works: [PairWork; 2],
    current: usize,
    check_tiles: Option<Target>,
    check_target: Option<Target>,
    readbacks: Vec<Readback>,
    next_readback: usize,
    results: Vec<((u64, u64), f32)>,
}

impl Memc {
    pub unsafe fn new(gl: &glow::Context) -> Result<Self, String> {
        unsafe {
            let down = Program::new(gl, DOWN, &["u_src"], &["u_inv", "u_color"])?;
            let search = Program::new(
                gl,
                SEARCH,
                &["u_x", "u_y", "u_prior", "u_temporal"],
                &[
                    "u_inv",
                    "u_scale",
                    "u_step",
                    "u_mode",
                    "u_has_temporal",
                    "u_prior_size",
                    "u_temporal_size",
                    "u_field_size",
                    "u_search",
                    "u_stages",
                ],
            )?;
            let median = Program::new(gl, MEDIAN, &["u_field"], &["u_size"])?;
            let dense = Program::new(gl, DENSE, &["u_x", "u_y", "u_grid"], &["u_grid_size", "u_inv", "u_scale", "u_step"])?;
            let tiles = Program::new(gl, TILES, &["u_a", "u_b"], &["u_size"])?;
            let stats = Program::new(
                gl,
                STATS,
                &["u_tiles", "u_field", "u_previous"],
                &["u_tiles_size", "u_field_size", "u_has_field", "u_has_previous"],
            )?;
            let select = Program::new(
                gl,
                SELECT,
                &["u_fwd", "u_bwd", "u_la", "u_lb"],
                &["u_flow_size", "u_flow_scale", "u_inv", "u_rect", "u_t", "u_iterations", "u_reach"],
            )?;
            let render = Program::new(
                gl,
                RENDER,
                &["u_a", "u_b", "u_pick", "u_risk"],
                &["u_flow_size", "u_flow_scale", "u_inv", "u_rect", "u_t", "u_dither", "u_unit"],
            )?;
            let settle = Program::new(gl, SETTLE, &["u_pick"], &["u_size"])?;
            let mask = Program::new(gl, MASK, &["u_pick"], &["u_size"])?;
            let soften = Program::new(gl, SOFTEN, &["u_src", "u_stats"], &["u_inv"])?;
            let vao = gl.create_vertex_array()?;
            let dummy = gl.create_texture()?;
            gl.bind_texture(glow::TEXTURE_2D, Some(dummy));
            gl.tex_storage_2d(glow::TEXTURE_2D, 1, glow::RGBA16I, 1, 1);
            gl.tex_sub_image_2d(
                glow::TEXTURE_2D,
                0,
                0,
                0,
                1,
                1,
                glow::RGBA_INTEGER,
                glow::SHORT,
                glow::PixelUnpackData::Slice(Some(&[0u8; 8])),
            );
            gl.bind_texture(glow::TEXTURE_2D, None);
            let linear = gl.create_sampler()?;
            gl.sampler_parameter_i32(linear, glow::TEXTURE_MIN_FILTER, glow::LINEAR as i32);
            gl.sampler_parameter_i32(linear, glow::TEXTURE_MAG_FILTER, glow::LINEAR as i32);
            gl.sampler_parameter_i32(linear, glow::TEXTURE_WRAP_S, glow::CLAMP_TO_EDGE as i32);
            gl.sampler_parameter_i32(linear, glow::TEXTURE_WRAP_T, glow::CLAMP_TO_EDGE as i32);
            let nearest = gl.create_sampler()?;
            gl.sampler_parameter_i32(nearest, glow::TEXTURE_MIN_FILTER, glow::NEAREST as i32);
            gl.sampler_parameter_i32(nearest, glow::TEXTURE_MAG_FILTER, glow::NEAREST as i32);
            gl.sampler_parameter_i32(nearest, glow::TEXTURE_WRAP_S, glow::CLAMP_TO_EDGE as i32);
            gl.sampler_parameter_i32(nearest, glow::TEXTURE_WRAP_T, glow::CLAMP_TO_EDGE as i32);
            let mut readbacks = Vec::with_capacity(READBACKS);
            for _ in 0..READBACKS {
                let buffer = gl.create_buffer()?;
                gl.bind_buffer(glow::PIXEL_PACK_BUFFER, Some(buffer));
                gl.buffer_data_size(glow::PIXEL_PACK_BUFFER, 4, glow::STREAM_READ);
                readbacks.push(Readback { buffer, pending: None });
            }
            gl.bind_buffer(glow::PIXEL_PACK_BUFFER, None);
            gl.use_program(None);
            Ok(Self {
                vao,
                dummy,
                linear,
                nearest,
                down,
                search,
                median,
                dense,
                tiles,
                stats,
                select,
                settle,
                mask,
                soften,
                render,
                size: (0, 0),
                levels: 0,
                pyramids: Vec::new(),
                clock: 0,
                layout: None,
                flow: [Direction::default(), Direction::default()],
                tile_level: 0,
                pair_tiles: None,
                picks: [None, None],
                masks: [None, None],
                works: [PairWork::default(), PairWork::default()],
                current: 0,
                check_tiles: None,
                check_target: None,
                readbacks,
                next_readback: 0,
                results: Vec::new(),
            })
        }
    }

    unsafe fn release_picks(&mut self, gl: &glow::Context) {
        unsafe {
            for target in self.picks.iter_mut().chain(self.masks.iter_mut()).filter_map(|t| t.take()) {
                target.delete(gl);
            }
        }
    }

    unsafe fn release_sized(&mut self, gl: &glow::Context) {
        unsafe {
            for pyramid in self.pyramids.drain(..) {
                for level in pyramid.levels {
                    level.delete(gl);
                }
            }
            for direction in self.flow.iter_mut() {
                direction.release(gl);
            }
            for work in self.works.iter_mut() {
                work.release(gl);
            }
            self.release_picks(gl);
            for target in [self.pair_tiles.take(), self.check_tiles.take(), self.check_target.take()].into_iter().flatten() {
                target.delete(gl);
            }
            self.layout = None;
            self.size = (0, 0);
        }
    }

    unsafe fn ensure_size(&mut self, gl: &glow::Context, width: i32, height: i32) -> Result<(), String> {
        unsafe {
            if self.size == (width, height) && !self.pyramids.is_empty() {
                return Ok(());
            }
            self.release_sized(gl);
            self.levels = level_count(width, height);
            for _ in 0..PYRAMIDS {
                let mut levels = Vec::with_capacity(self.levels);
                for level in 0..self.levels {
                    let (w, h) = level_size(width, height, level);
                    levels.push(Target::new(gl, glow::R8, w, h, true)?);
                }
                self.pyramids.push(Pyramid { version: None, levels, used: 0 });
            }
            self.tile_level = level_for(width, height, 400, self.levels);
            let tile_size = blocks(level_size(width, height, self.tile_level));
            self.pair_tiles = Some(Target::new(gl, glow::RGBA8, tile_size.0, tile_size.1, false)?);
            self.check_tiles = Some(Target::new(gl, glow::RGBA8, tile_size.0, tile_size.1, false)?);
            for work in self.works.iter_mut() {
                work.stats = Some(Target::new(gl, glow::RGBA8, 1, 1, false)?);
            }
            self.check_target = Some(Target::new(gl, glow::RGBA8, 1, 1, false)?);
            self.size = (width, height);
            Ok(())
        }
    }

    unsafe fn ensure_layout(&mut self, gl: &glow::Context, base: usize, q: Quality) -> Result<(), String> {
        unsafe {
            if self.layout == Some((base, q)) {
                return Ok(());
            }
            for direction in self.flow.iter_mut() {
                direction.release(gl);
            }
            for work in self.works.iter_mut() {
                work.release_dense(gl);
            }
            self.release_picks(gl);
            let size = self.size;
            for direction in self.flow.iter_mut() {
                direction.fields = (0..self.levels).map(|_| None).collect();
                for level in base + 1..self.levels {
                    let cells = grid(level_size(size.0, size.1, level), q.step);
                    direction.fields[level] = Some(Target::new(gl, glow::RGBA16I, cells.0, cells.1, false)?);
                }
                let cells = grid(level_size(size.0, size.1, base), q.step);
                for slot in direction.base.iter_mut() {
                    *slot = Some(Target::new(gl, glow::RGBA16I, cells.0, cells.1, false)?);
                }
            }
            let flow = level_size(size.0, size.1, base);
            for work in self.works.iter_mut() {
                for dense in work.dense.iter_mut() {
                    *dense = Some(Target::new(gl, glow::RGBA16I, flow.0, flow.1, false)?);
                }
            }
            for pick in self.picks.iter_mut() {
                *pick = Some(Target::new(gl, glow::RGBA16I, flow.0, flow.1, false)?);
            }
            self.masks[0] = Some(Target::new(gl, glow::R8, flow.0, flow.1, true)?);
            self.masks[1] = Some(Target::new(gl, glow::RGBA8, flow.0, flow.1, true)?);
            self.layout = Some((base, q));
            Ok(())
        }
    }

    unsafe fn pyramid(&mut self, gl: &glow::Context, frame: MemcFrame) -> usize {
        unsafe {
            self.clock += 1;
            if let Some(index) = self.pyramids.iter().position(|p| p.version == Some(frame.version)) {
                self.pyramids[index].used = self.clock;
                return index;
            }
            let index = self
                .pyramids
                .iter()
                .enumerate()
                .min_by_key(|(_, p)| if p.version.is_none() { 0 } else { p.used })
                .map(|(i, _)| i)
                .unwrap_or(0);
            gl.use_program(Some(self.down.program));
            gl.active_texture(glow::TEXTURE0);
            gl.bind_sampler(0, Some(self.linear));
            let mut source = frame.texture;
            let mut source_size = self.size;
            for level in 0..self.levels {
                let target = &self.pyramids[index].levels[level];
                target.bind(gl);
                gl.bind_texture(glow::TEXTURE_2D, Some(source));
                gl.uniform_2_f32(self.down.at(0), 1.0 / source_size.0 as f32, 1.0 / source_size.1 as f32);
                gl.uniform_1_i32(self.down.at(1), (level == 0) as i32);
                gl.draw_arrays(glow::TRIANGLES, 0, 3);
                source = target.texture;
                source_size = (target.width, target.height);
            }
            let pyramid = &mut self.pyramids[index];
            pyramid.version = Some(frame.version);
            pyramid.used = self.clock;
            index
        }
    }

    unsafe fn bind_unit(&self, gl: &glow::Context, unit: u32, texture: glow::Texture, linear: bool) {
        unsafe {
            gl.active_texture(glow::TEXTURE0 + unit);
            gl.bind_texture(glow::TEXTURE_2D, Some(texture));
            gl.bind_sampler(unit, Some(if linear { self.linear } else { self.nearest }));
        }
    }

    unsafe fn draw_tiles(&self, gl: &glow::Context, a: usize, b: usize, target: &Target) {
        unsafe {
            let level_a = &self.pyramids[a].levels[self.tile_level];
            let level_b = &self.pyramids[b].levels[self.tile_level];
            gl.use_program(Some(self.tiles.program));
            self.bind_unit(gl, 0, level_a.texture, false);
            self.bind_unit(gl, 1, level_b.texture, false);
            gl.uniform_2_i32(self.tiles.at(0), level_a.width, level_a.height);
            target.bind(gl);
            gl.draw_arrays(glow::TRIANGLES, 0, 3);
        }
    }

    unsafe fn draw_stats(&self, gl: &glow::Context, tiles: &Target, field: Option<&Target>, previous: Option<&Target>, target: &Target) {
        unsafe {
            gl.use_program(Some(self.stats.program));
            self.bind_unit(gl, 0, tiles.texture, false);
            gl.uniform_2_i32(self.stats.at(0), tiles.width, tiles.height);
            match field {
                Some(field) => {
                    self.bind_unit(gl, 1, field.texture, false);
                    gl.uniform_2_i32(self.stats.at(1), field.width, field.height);
                    gl.uniform_1_i32(self.stats.at(2), 1);
                }
                None => {
                    self.bind_unit(gl, 1, self.dummy, false);
                    gl.uniform_2_i32(self.stats.at(1), 1, 1);
                    gl.uniform_1_i32(self.stats.at(2), 0);
                }
            }
            match previous.filter(|_| field.is_some()) {
                Some(previous) => {
                    self.bind_unit(gl, 2, previous.texture, false);
                    gl.uniform_1_i32(self.stats.at(3), 1);
                }
                None => {
                    self.bind_unit(gl, 2, self.dummy, false);
                    gl.uniform_1_i32(self.stats.at(3), 0);
                }
            }
            target.bind(gl);
            gl.draw_arrays(glow::TRIANGLES, 0, 3);
        }
    }

    unsafe fn finish(&self, gl: &glow::Context) {
        unsafe {
            for unit in 0..7 {
                gl.active_texture(glow::TEXTURE0 + unit);
                gl.bind_sampler(unit, None);
            }
            gl.active_texture(glow::TEXTURE0);
            gl.bind_vertex_array(None);
            gl.use_program(None);
        }
    }

    #[allow(clippy::too_many_arguments)]
    unsafe fn estimate(&mut self, gl: &glow::Context, side: usize, anchor: usize, target: usize, base: usize, q: Quality, work: usize) -> Option<usize> {
        unsafe {
            let size = self.size;
            let temporal = self.flow[side].last;
            let output = (0..3).find(|i| Some(*i) != temporal)?;
            let spare = (0..3).find(|i| Some(*i) != temporal && *i != output)?;
            gl.use_program(Some(self.search.program));
            gl.uniform_1_f32(self.search.at(2), q.step as f32);
            gl.uniform_1_i32(self.search.at(8), 8);
            for level in (base..self.levels).rev() {
                let direction = &self.flow[side];
                let x = &self.pyramids[anchor].levels[level];
                let y = &self.pyramids[target].levels[level];
                let coarsest = level + 1 == self.levels;
                let destination = if level == base { direction.base[output].as_ref() } else { direction.fields[level].as_ref() };
                let destination = destination?;
                self.bind_unit(gl, 0, x.texture, true);
                self.bind_unit(gl, 1, y.texture, true);
                match direction.fields.get(level + 1).and_then(|f| f.as_ref()).filter(|_| !coarsest) {
                    Some(prior) => {
                        self.bind_unit(gl, 2, prior.texture, false);
                        gl.uniform_2_i32(self.search.at(5), prior.width, prior.height);
                    }
                    None => {
                        self.bind_unit(gl, 2, self.dummy, false);
                        gl.uniform_2_i32(self.search.at(5), 1, 1);
                    }
                }
                match temporal.and_then(|i| direction.base[i].as_ref()) {
                    Some(previous) => {
                        self.bind_unit(gl, 3, previous.texture, false);
                        gl.uniform_1_i32(self.search.at(4), 1);
                        gl.uniform_2_i32(self.search.at(6), previous.width, previous.height);
                    }
                    None => {
                        self.bind_unit(gl, 3, self.dummy, false);
                        gl.uniform_1_i32(self.search.at(4), 0);
                        gl.uniform_2_i32(self.search.at(6), 1, 1);
                    }
                }
                gl.uniform_2_f32(self.search.at(0), 1.0 / x.width as f32, 1.0 / x.height as f32);
                gl.uniform_2_f32(self.search.at(1), x.width as f32 / size.0 as f32, x.height as f32 / size.1 as f32);
                gl.uniform_1_i32(self.search.at(3), if coarsest { 0 } else { 1 });
                gl.uniform_1_i32(self.search.at(8), search_range(coarsest, level > base, x.width, x.height));
                gl.uniform_2_i32(self.search.at(7), destination.width, destination.height);
                gl.uniform_1_i32(self.search.at(9), q.stages);
                destination.bind(gl);
                gl.draw_arrays(glow::TRIANGLES, 0, 3);
            }
            let x = &self.pyramids[anchor].levels[base];
            let y = &self.pyramids[target].levels[base];
            let mut current = output;
            for _ in 0..q.propagate {
                let next = if current == output { spare } else { output };
                let direction = &self.flow[side];
                let (Some(source), Some(destination)) = (direction.base[current].as_ref(), direction.base[next].as_ref()) else {
                    break;
                };
                self.bind_unit(gl, 0, x.texture, true);
                self.bind_unit(gl, 1, y.texture, true);
                self.bind_unit(gl, 2, source.texture, false);
                self.bind_unit(gl, 3, self.dummy, false);
                gl.uniform_1_i32(self.search.at(3), 2);
                gl.uniform_1_i32(self.search.at(4), 0);
                gl.uniform_2_i32(self.search.at(5), source.width, source.height);
                gl.uniform_2_i32(self.search.at(6), 1, 1);
                gl.uniform_2_i32(self.search.at(7), destination.width, destination.height);
                gl.uniform_1_i32(self.search.at(9), q.stages - 1);
                gl.uniform_2_f32(self.search.at(0), 1.0 / x.width as f32, 1.0 / x.height as f32);
                gl.uniform_2_f32(self.search.at(1), x.width as f32 / size.0 as f32, x.height as f32 / size.1 as f32);
                destination.bind(gl);
                gl.draw_arrays(glow::TRIANGLES, 0, 3);
                current = next;
            }
            let finished = if current == output { spare } else { output };
            {
                let direction = &self.flow[side];
                let (Some(source), Some(destination)) = (direction.base[current].as_ref(), direction.base[finished].as_ref()) else {
                    return None;
                };
                gl.use_program(Some(self.median.program));
                self.bind_unit(gl, 0, source.texture, false);
                gl.uniform_2_i32(self.median.at(0), source.width, source.height);
                destination.bind(gl);
                gl.draw_arrays(glow::TRIANGLES, 0, 3);
            }
            let direction = &self.flow[side];
            let (Some(cells), Some(dense)) = (direction.base[finished].as_ref(), self.works[work].dense[side].as_ref()) else {
                return None;
            };
            gl.use_program(Some(self.dense.program));
            self.bind_unit(gl, 0, x.texture, true);
            self.bind_unit(gl, 1, y.texture, true);
            self.bind_unit(gl, 2, cells.texture, false);
            gl.uniform_2_i32(self.dense.at(0), cells.width, cells.height);
            gl.uniform_2_f32(self.dense.at(1), 1.0 / x.width as f32, 1.0 / x.height as f32);
            gl.uniform_2_f32(self.dense.at(2), x.width as f32 / size.0 as f32, x.height as f32 / size.1 as f32);
            gl.uniform_1_f32(self.dense.at(3), q.step as f32);
            dense.bind(gl);
            gl.draw_arrays(glow::TRIANGLES, 0, 3);
            self.flow[side].last = Some(finished);
            Some(finished)
        }
    }

    unsafe fn run_stages(&mut self, gl: &glow::Context, work: usize, a: MemcFrame, b: MemcFrame, budget: u32) -> Result<u32, String> {
        unsafe {
            let Some((base, q)) = self.layout else {
                return Err("motion layout is not ready".into());
            };
            let mut ran = 0;
            while ran < budget && self.works[work].stage < 2 {
                let pa = self.pyramid(gl, a);
                let pb = self.pyramid(gl, b);
                if pa == pb {
                    return Err("motion frames share a pyramid".into());
                }
                if self.works[work].stage == 0 {
                    if let Some(tiles) = self.pair_tiles.as_ref() {
                        self.draw_tiles(gl, pa, pb, tiles);
                    }
                    let earlier = self.flow[0].last;
                    let forward = self.estimate(gl, 0, pa, pb, base, q, work).ok_or("motion fields are missing")?;
                    let field = self.flow[0].base[forward].as_ref();
                    let previous = earlier.and_then(|i| self.flow[0].base[i].as_ref());
                    if let (Some(tiles), Some(stats)) = (self.pair_tiles.as_ref(), self.works[work].stats.as_ref()) {
                        self.draw_stats(gl, tiles, field, previous, stats);
                    }
                } else {
                    self.estimate(gl, 1, pb, pa, base, q, work).ok_or("motion fields are missing")?;
                }
                self.works[work].stage += 1;
                ran += 1;
            }
            Ok(ran)
        }
    }

    pub fn remaining(&self, a: u64, b: u64) -> u32 {
        let key = Some((a, b));
        self.works.iter().find(|work| work.key == key).map_or(2, |work| 2 - work.stage.min(2) as u32)
    }

    #[allow(clippy::too_many_arguments)]
    pub unsafe fn advance(
        &mut self,
        gl: &glow::Context,
        a: MemcFrame,
        b: MemcFrame,
        shown: (u64, u64),
        size: (i32, i32),
        params: MemcParams,
        budget: u32,
    ) -> Result<u32, String> {
        unsafe {
            let q = quality(params.level);
            if budget == 0 || self.size != size || self.pyramids.is_empty() || self.layout != Some((level_for(size.0, size.1, q.limit, self.levels), q)) {
                return Ok(0);
            }
            let key = (a.version, b.version);
            let shown = Some(shown);
            if shown == Some(key) {
                return Ok(0);
            }
            let work = match self.works.iter().position(|work| work.key == Some(key)) {
                Some(index) => index,
                None => {
                    let other = 1 - self.current;
                    let index = if self.works[other].key == shown { self.current } else { other };
                    self.works[index].restart(key);
                    index
                }
            };
            gl.bind_vertex_array(Some(self.vao));
            let result = self.run_stages(gl, work, a, b, budget);
            self.finish(gl);
            gl.bind_framebuffer(glow::FRAMEBUFFER, None);
            result
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub unsafe fn render(
        &mut self,
        gl: &glow::Context,
        a: MemcFrame,
        b: MemcFrame,
        size: (i32, i32),
        rect: [f32; 4],
        t: f32,
        params: MemcParams,
        dither: f32,
        target: Option<glow::Framebuffer>,
        out: (i32, i32),
    ) -> Result<u32, String> {
        unsafe {
            self.ensure_size(gl, size.0, size.1)?;
            let q = quality(params.level);
            let base = level_for(size.0, size.1, q.limit, self.levels);
            self.ensure_layout(gl, base, q)?;
            gl.bind_vertex_array(Some(self.vao));
            let key = (a.version, b.version);
            let work = match self.works.iter().position(|work| work.key == Some(key)) {
                Some(index) => index,
                None => {
                    let index = self.current;
                    self.works[index].restart(key);
                    index
                }
            };
            self.current = work;
            let stages = match self.run_stages(gl, work, a, b, 2) {
                Ok(stages) => stages,
                Err(error) => {
                    self.finish(gl);
                    return Err(error);
                }
            };
            let pa = self.pyramid(gl, a);
            let pb = self.pyramid(gl, b);
            if pa == pb {
                self.finish(gl);
                return Err("motion frames share a pyramid".into());
            }
            let pair = &self.works[work];
            let (Some(forward), Some(backward), Some(stats)) = (pair.dense[0].as_ref(), pair.dense[1].as_ref(), pair.stats.as_ref()) else {
                self.finish(gl);
                return Err("motion flow is missing".into());
            };
            let (Some(pick), Some(settled), Some(risk), Some(soft)) = (self.picks[0].as_ref(), self.picks[1].as_ref(), self.masks[0].as_ref(), self.masks[1].as_ref()) else {
                self.finish(gl);
                return Err("motion pick target is missing".into());
            };
            let flow_scale = (forward.width as f32 / size.0 as f32, forward.height as f32 / size.1 as f32);
            gl.use_program(Some(self.select.program));
            self.bind_unit(gl, 0, forward.texture, false);
            self.bind_unit(gl, 1, backward.texture, false);
            self.bind_unit(gl, 2, self.pyramids[pa].levels[0].texture, true);
            self.bind_unit(gl, 3, self.pyramids[pb].levels[0].texture, true);
            gl.uniform_2_i32(self.select.at(0), forward.width, forward.height);
            gl.uniform_2_f32(self.select.at(1), flow_scale.0, flow_scale.1);
            gl.uniform_2_f32(self.select.at(2), 1.0 / size.0 as f32, 1.0 / size.1 as f32);
            gl.uniform_4_f32(self.select.at(3), rect[0], rect[1], rect[2], rect[3]);
            gl.uniform_1_f32(self.select.at(4), t.clamp(0.0, 1.0));
            gl.uniform_1_i32(self.select.at(5), q.iterations);
            gl.uniform_1_i32(self.select.at(6), q.reach);
            pick.bind(gl);
            gl.draw_arrays(glow::TRIANGLES, 0, 3);
            gl.use_program(Some(self.settle.program));
            self.bind_unit(gl, 0, pick.texture, false);
            gl.uniform_2_i32(self.settle.at(0), pick.width, pick.height);
            settled.bind(gl);
            gl.draw_arrays(glow::TRIANGLES, 0, 3);
            gl.use_program(Some(self.mask.program));
            self.bind_unit(gl, 0, settled.texture, false);
            gl.uniform_2_i32(self.mask.at(0), settled.width, settled.height);
            risk.bind(gl);
            gl.draw_arrays(glow::TRIANGLES, 0, 3);
            gl.use_program(Some(self.soften.program));
            self.bind_unit(gl, 0, risk.texture, true);
            self.bind_unit(gl, 1, stats.texture, false);
            gl.uniform_2_f32(self.soften.at(0), 1.0 / risk.width as f32, 1.0 / risk.height as f32);
            soft.bind(gl);
            gl.draw_arrays(glow::TRIANGLES, 0, 3);
            gl.bind_framebuffer(glow::FRAMEBUFFER, target);
            gl.viewport(0, 0, out.0, out.1);
            gl.use_program(Some(self.render.program));
            self.bind_unit(gl, 0, a.texture, true);
            self.bind_unit(gl, 1, b.texture, true);
            self.bind_unit(gl, 2, settled.texture, false);
            self.bind_unit(gl, 3, soft.texture, true);
            let shrink = (out.0 as f32 / size.0 as f32, out.1 as f32 / size.1 as f32);
            gl.uniform_2_i32(self.render.at(0), settled.width, settled.height);
            gl.uniform_2_f32(self.render.at(1), flow_scale.0 / shrink.0, flow_scale.1 / shrink.1);
            gl.uniform_2_f32(self.render.at(2), 1.0 / out.0 as f32, 1.0 / out.1 as f32);
            gl.uniform_4_f32(self.render.at(3), rect[0] * shrink.0, rect[1] * shrink.1, rect[2] * shrink.0, rect[3] * shrink.1);
            gl.uniform_1_f32(self.render.at(4), t.clamp(0.0, 1.0));
            gl.uniform_1_f32(self.render.at(5), dither);
            gl.uniform_2_f32(self.render.at(6), 0.25 * shrink.0, 0.25 * shrink.1);
            gl.draw_arrays(glow::TRIANGLES, 0, 3);
            self.finish(gl);
            Ok(stages)
        }
    }

    pub unsafe fn request_check(&mut self, gl: &glow::Context, a: MemcFrame, b: MemcFrame, size: (i32, i32)) -> Result<(), String> {
        unsafe {
            let pair = (a.version, b.version);
            if self.results.iter().any(|(p, _)| *p == pair) || self.readbacks.iter().any(|r| r.pending.is_some_and(|(_, p)| p == pair)) {
                return Ok(());
            }
            self.ensure_size(gl, size.0, size.1)?;
            gl.bind_vertex_array(Some(self.vao));
            let pa = self.pyramid(gl, a);
            let pb = self.pyramid(gl, b);
            if pa == pb {
                self.finish(gl);
                return Err("check frames share a pyramid".into());
            }
            let (Some(tiles), Some(target)) = (self.check_tiles.as_ref(), self.check_target.as_ref()) else {
                self.finish(gl);
                return Err("check targets are missing".into());
            };
            self.draw_tiles(gl, pa, pb, tiles);
            self.draw_stats(gl, tiles, None, None, target);
            let slot = self.next_readback;
            self.next_readback = (slot + 1) % READBACKS;
            let readback = &mut self.readbacks[slot];
            if let Some((fence, _)) = readback.pending.take() {
                gl.delete_sync(fence);
            }
            target.bind(gl);
            gl.bind_buffer(glow::PIXEL_PACK_BUFFER, Some(readback.buffer));
            gl.read_pixels(0, 0, 1, 1, glow::RGBA, glow::UNSIGNED_BYTE, glow::PixelPackData::BufferOffset(0));
            gl.bind_buffer(glow::PIXEL_PACK_BUFFER, None);
            if let Ok(fence) = gl.fence_sync(glow::SYNC_GPU_COMMANDS_COMPLETE, 0) {
                readback.pending = Some((fence, pair));
            }
            self.finish(gl);
            gl.bind_framebuffer(glow::FRAMEBUFFER, None);
            Ok(())
        }
    }

    pub unsafe fn check_result(&mut self, gl: &glow::Context, a: u64, b: u64) -> Option<f32> {
        unsafe {
            for readback in self.readbacks.iter_mut() {
                let Some((fence, pair)) = readback.pending else {
                    continue;
                };
                let status = gl.client_wait_sync(fence, 0, 0);
                if status != glow::ALREADY_SIGNALED && status != glow::CONDITION_SATISFIED {
                    continue;
                }
                gl.delete_sync(fence);
                readback.pending = None;
                gl.bind_buffer(glow::PIXEL_PACK_BUFFER, Some(readback.buffer));
                let data = gl.map_buffer_range(glow::PIXEL_PACK_BUFFER, 0, 4, glow::MAP_READ_BIT);
                if !data.is_null() {
                    let peak = *data as f32 / 255.0;
                    gl.unmap_buffer(glow::PIXEL_PACK_BUFFER);
                    self.results.push((pair, peak));
                    if self.results.len() > 8 {
                        self.results.remove(0);
                    }
                }
                gl.bind_buffer(glow::PIXEL_PACK_BUFFER, None);
            }
            self.results.iter().rev().find(|(p, _)| *p == (a, b)).map(|(_, peak)| *peak)
        }
    }

    pub fn reset_motion(&mut self) {
        for direction in self.flow.iter_mut() {
            direction.last = None;
        }
        for work in self.works.iter_mut() {
            work.key = None;
            work.stage = 0;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn levels_follow_quality() {
        let levels = level_count(2400, 1080);
        assert_eq!(levels, 6);
        assert_eq!(level_size(2400, 1080, 0), (1200, 540));
        assert_eq!(level_for(2400, 1080, quality(3).limit, levels), 0);
        assert_eq!(level_for(2400, 1080, quality(2).limit, levels), 1);
        assert_eq!(level_for(2400, 1080, quality(1).limit, levels), 2);
        assert_eq!(level_for(1080, 2400, quality(2).limit, levels), 1);
        assert_eq!(grid((600, 270), 4), (150, 68));
    }
}
