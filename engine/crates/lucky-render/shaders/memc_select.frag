#version 450

layout(set = 0, binding = 0) uniform isampler2D u_fwd;
layout(set = 0, binding = 1) uniform isampler2D u_bwd;
layout(set = 0, binding = 2) uniform sampler2D u_la;
layout(set = 0, binding = 3) uniform sampler2D u_lb;

layout(push_constant) uniform Select {
    vec4 rect;
    ivec2 flow_size;
    vec2 flow_scale;
    vec2 inv;
    float t;
    int iterations;
    int reach;
} pc;

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
    return texelFetch(u_fwd, clamp(ivec2(p * pc.flow_scale), ivec2(0), pc.flow_size - 1), 0);
}

ivec4 bwd(vec2 p) {
    return texelFetch(u_bwd, clamp(ivec2(p * pc.flow_scale), ivec2(0), pc.flow_size - 1), 0);
}

float inside(vec2 p) {
    return step(pc.rect.x, p.x) * step(pc.rect.y, p.y) * step(p.x, pc.rect.z) * step(p.y, pc.rect.w);
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
    float mismatch = abs(textureLod(u_la, q * pc.inv, 0.0).r - textureLod(u_lb, r * pc.inv, 0.0).r);
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
    vec2 p = gl_FragCoord.xy / pc.flow_scale;
    float t = pc.t;
    g_cell = 1.0 / min(pc.flow_scale.x, pc.flow_scale.y);
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
        if (i >= pc.iterations) {
            break;
        }
        q = p - t * vec2(fq.xy) * 0.25;
        fq = fwd(q);
    }
    vec2 r = p;
    ivec4 br = bwd(r);
    for (int i = 0; i < 4; i++) {
        if (i >= pc.iterations) {
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
        if (k >= pc.reach) {
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
    o_pick = ivec4(ivec2(round(g_best * 4.0)), int(g_okA * 1000.0 + 0.5) + int(g_okB * 1000.0 + 0.5) * 1024, int(min(g_err, 7.0) * 4096.0 + 0.5));
}
