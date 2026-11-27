#version 450

layout(set = 0, binding = 0) uniform sampler2D u_a;
layout(set = 0, binding = 1) uniform sampler2D u_b;
layout(set = 0, binding = 2) uniform isampler2D u_pick;
layout(set = 0, binding = 3) uniform sampler2D u_risk;

layout(push_constant) uniform Render {
    vec4 rect;
    ivec2 flow_size;
    vec2 flow_scale;
    vec2 inv;
    vec2 unit;
    float t;
    float dither;
} pc;

layout(location = 0) out vec4 o_color;

vec3 dither_levels(vec3 color) {
    if (pc.dither <= 0.0) {
        return color;
    }
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    return color + vec3((noise - 0.5) / pc.dither);
}

vec3 sa(vec2 p) {
    return textureLod(u_a, p * pc.inv, 0.0).rgb;
}

vec3 sb(vec2 p) {
    return textureLod(u_b, p * pc.inv, 0.0).rgb;
}

float inside(vec2 p) {
    return step(pc.rect.x, p.x) * step(pc.rect.y, p.y) * step(p.x, pc.rect.z) * step(p.y, pc.rect.w);
}

ivec4 pick_at(ivec2 c) {
    return texelFetch(u_pick, clamp(c, ivec2(0), pc.flow_size - 1), 0);
}

vec3 warp(vec2 p, vec2 v, float okA, float okB) {
    float t = pc.t;
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
    float t = pc.t;
    vec3 mask = textureLod(u_risk, p * pc.inv, 0.0).rgb;
    if (mask.g > 0.5) {
        o_color = vec4(dither_levels(t < 0.5 ? sa(p) : sb(p)), 1.0);
        return;
    }
    if (inside(p) < 0.5) {
        o_color = vec4(dither_levels(mix(sa(p), sb(p), t)), 1.0);
        return;
    }
    vec2 g = p * pc.flow_scale - 0.5;
    vec2 cell = floor(g);
    vec2 f = g - cell;
    ivec2 c = ivec2(cell);
    ivec4 k0 = pick_at(c);
    ivec4 k1 = pick_at(c + ivec2(1, 0));
    ivec4 k2 = pick_at(c + ivec2(0, 1));
    ivec4 k3 = pick_at(c + ivec2(1, 1));
    vec2 v0 = vec2(k0.xy) * pc.unit;
    vec2 v1 = vec2(k1.xy) * pc.unit;
    vec2 v2 = vec2(k2.xy) * pc.unit;
    vec2 v3 = vec2(k3.xy) * pc.unit;
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
