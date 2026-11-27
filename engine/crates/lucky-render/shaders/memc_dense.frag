#version 450

layout(set = 0, binding = 0) uniform sampler2D u_x;
layout(set = 0, binding = 1) uniform sampler2D u_y;
layout(set = 0, binding = 2) uniform isampler2D u_grid;

layout(push_constant) uniform Dense {
    ivec2 grid_size;
    vec2 inv;
    vec2 scale;
    float grid_step;
} pc;

layout(location = 0) out ivec4 o_flow;

float g_x0;
float g_x1;
float g_x2;
float g_x3;
float g_x4;

float sx(vec2 p) {
    return textureLod(u_x, p * pc.inv, 0.0).r;
}

float sy(vec2 q) {
    return textureLod(u_y, q * pc.inv, 0.0).r;
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
    vec2 u = q * pc.inv;
    float outside = 1.0 - step(0.0, min(u.x, u.y)) * step(max(u.x, u.y), 1.0);
    return (a - 0.75 * abs(s)) * 0.24 + outside * 0.03;
}

vec2 grid(ivec2 p) {
    return vec2(texelFetch(u_grid, clamp(p, ivec2(0), pc.grid_size - 1), 0).xy) * 0.25 * pc.scale;
}

void main() {
    vec2 p = gl_FragCoord.xy;
    load_anchor(p);
    vec2 g = p / pc.grid_step - 0.5;
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
    o_flow = ivec4(ivec2(round(best / pc.scale * 4.0)), int(min(e, 7.0) * 4096.0 + 0.5), 0);
}
