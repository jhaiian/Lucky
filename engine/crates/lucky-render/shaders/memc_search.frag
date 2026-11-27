#version 450

layout(set = 0, binding = 0) uniform sampler2D u_x;
layout(set = 0, binding = 1) uniform sampler2D u_y;
layout(set = 0, binding = 2) uniform isampler2D u_prior;
layout(set = 0, binding = 3) uniform isampler2D u_temporal;

layout(push_constant) uniform Search {
    vec2 inv;
    vec2 scale;
    ivec2 prior_size;
    ivec2 temporal_size;
    ivec2 field_size;
    float grid_step;
    int mode;
    int has_temporal;
    int search;
    int stages;
} pc;

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
            g_x[j * 4 + i] = textureLod(u_x, p * pc.inv, 0.0).r;
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
            vec2 ux = p * pc.inv;
            vec2 uy = (p + v) * pc.inv;
            float inside = step(0.0, min(min(ux.x, ux.y), min(uy.x, uy.y))) * step(max(max(ux.x, ux.y), max(uy.x, uy.y)), 1.0);
            float d = inside * (g_x[j * 4 + i] - textureLod(u_y, uy, 0.0).r);
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
    return vec2(f.xy) * 0.25 * pc.scale;
}

vec2 prior(ivec2 p) {
    return level_vector(texelFetch(u_prior, clamp(p, ivec2(0), pc.prior_size - 1), 0));
}

float middle4(float a, float b, float c, float d) {
    return (a + b + c + d - min(min(a, b), min(c, d)) - max(max(a, b), max(c, d))) * 0.5;
}

vec2 middle4v(vec2 a, vec2 b, vec2 c, vec2 d) {
    return vec2(middle4(a.x, b.x, c.x, d.x), middle4(a.y, b.y, c.y, d.y));
}

void main() {
    ivec2 block = ivec2(gl_FragCoord.xy);
    g_center = (vec2(block) + 0.5) * pc.grid_step;
    load_anchor();
    g_lambda = LAMBDA;
    g_pred = vec2(0.0);
    if (pc.mode == 2) {
        vec2 n0 = prior(block + ivec2(1, 0));
        vec2 n1 = prior(block + ivec2(-1, 0));
        vec2 n2 = prior(block + ivec2(0, 1));
        vec2 n3 = prior(block + ivec2(0, -1));
        g_pred = middle4v(n0, n1, n2, n3);
    } else if (pc.mode == 1) {
        vec2 pf = (vec2(block) + 0.5) * 0.5 - 0.5;
        ivec2 i0 = ivec2(floor(pf));
        g_pred = middle4v(prior(i0), prior(i0 + ivec2(1, 0)), prior(i0 + ivec2(0, 1)), prior(i0 + ivec2(1, 1)));
    } else {
        g_lambda = LAMBDA * 0.4;
        if (pc.has_temporal == 1) {
            vec2 at = (vec2(block) + 0.5) * vec2(pc.temporal_size) / vec2(pc.field_size);
            g_pred = level_vector(texelFetch(u_temporal, clamp(ivec2(at), ivec2(0), pc.temporal_size - 1), 0));
        }
    }
    g_best = vec2(0.0);
    g_score = 1e9;
    g_floor = 1e9;
    consider(vec2(0.0), 0.0004);
    consider(g_pred, 0.0);
    if (pc.mode == 2) {
        consider(prior(block), 0.0);
        consider(prior(block + ivec2(1, 0)), 0.0006);
        consider(prior(block + ivec2(-1, 0)), 0.0006);
        consider(prior(block + ivec2(0, 1)), 0.0006);
        consider(prior(block + ivec2(0, -1)), 0.0006);
        consider(prior(block + ivec2(1, 1)), 0.0008);
        consider(prior(block + ivec2(-1, -1)), 0.0008);
        consider(prior(block + ivec2(1, -1)), 0.0008);
        consider(prior(block + ivec2(-1, 1)), 0.0008);
    } else if (pc.mode == 1) {
        vec2 pf = (vec2(block) + 0.5) * 0.5 - 0.5;
        ivec2 i0 = ivec2(floor(pf));
        consider(prior(i0), 0.0);
        consider(prior(i0 + ivec2(1, 0)), 0.0);
        consider(prior(i0 + ivec2(0, 1)), 0.0);
        consider(prior(i0 + ivec2(1, 1)), 0.0);
        consider(prior(i0 + ivec2(-1, 0)), 0.0008);
        consider(prior(i0 + ivec2(0, -1)), 0.0008);
        if (pc.search > 0) {
            for (int dy = -8; dy <= 8; dy += 2) {
                if (dy < -pc.search || dy > pc.search) {
                    continue;
                }
                for (int dx = -8; dx <= 8; dx += 2) {
                    if (dx < -pc.search || dx > pc.search) {
                        continue;
                    }
                    consider(vec2(float(dx), float(dy)), 0.006 + 0.0002 * float(abs(dx) + abs(dy)));
                }
            }
        }
    } else {
        for (int dy = -8; dy <= 8; dy++) {
            if (dy < -pc.search || dy > pc.search) {
                continue;
            }
            for (int dx = -8; dx <= 8; dx++) {
                if (dx < -pc.search || dx > pc.search) {
                    continue;
                }
                consider(vec2(float(dx), float(dy)), 0.00015 * float(abs(dx) + abs(dy)));
            }
        }
    }
    if (pc.has_temporal == 1) {
        vec2 at = (vec2(block) + 0.5) * vec2(pc.temporal_size) / vec2(pc.field_size);
        ivec2 tb = clamp(ivec2(at), ivec2(0), pc.temporal_size - 1);
        consider(level_vector(texelFetch(u_temporal, tb, 0)), 0.0010);
    }
    g_lambda = 0.0;
    g_score = block_cost(g_best);
    float stride = pc.mode == 2 ? 0.5 : 1.0;
    for (int s = 0; s < 4; s++) {
        if (s >= pc.stages) {
            break;
        }
        vec2 c = g_best;
        consider(c + vec2(stride, 0.0), 0.0006);
        consider(c - vec2(stride, 0.0), 0.0006);
        consider(c + vec2(0.0, stride), 0.0006);
        consider(c - vec2(0.0, stride), 0.0006);
        stride *= 0.5;
    }
    vec2 screen = g_best / pc.scale * 4.0;
    o_field = ivec4(ivec2(round(screen)), int(min(g_floor, 7.0) * 4096.0 + 0.5), int(min(block_texture(), 7.0) * 4096.0 + 0.5));
}
