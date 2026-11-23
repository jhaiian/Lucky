#version 450

layout(set = 0, binding = 0) uniform sampler2D u_a;
layout(set = 0, binding = 1) uniform sampler2D u_b;
layout(set = 0, binding = 2) uniform isampler2D u_pick;
layout(set = 0, binding = 3) uniform sampler2D u_stats;

layout(push_constant) uniform Render {
    vec4 rect;
    vec4 protect;
    ivec2 flow_size;
    vec2 flow_scale;
    vec2 inv;
    float t;
} pc;

layout(location = 0) out vec4 o_color;

vec3 sa(vec2 p) {
    return textureLod(u_a, p * pc.inv, 0.0).rgb;
}

vec3 sb(vec2 p) {
    return textureLod(u_b, p * pc.inv, 0.0).rgb;
}

float inside(vec2 p) {
    return step(pc.rect.x, p.x) * step(pc.rect.y, p.y) * step(p.x, pc.rect.z) * step(p.y, pc.rect.w);
}

vec3 dither_levels(vec3 color) {
    if (pc.protect.w <= 0.0) {
        return color;
    }
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    return color + vec3((noise - 0.5) / pc.protect.w);
}

void main() {
    vec2 p = gl_FragCoord.xy;
    float t = pc.t;
    vec4 stats = textureLod(u_stats, vec2(0.5), 0.0);
    if (stats.a > 0.5) {
        o_color = vec4(dither_levels(t < 0.5 ? sa(p) : sb(p)), 1.0);
        return;
    }
    vec3 stillA = sa(p);
    vec3 stillB = sb(p);
    vec3 still = mix(stillA, stillB, t);
    if (inside(p) < 0.5) {
        o_color = vec4(dither_levels(still), 1.0);
        return;
    }
    ivec4 pick = texelFetch(u_pick, clamp(ivec2(p * pc.flow_scale), ivec2(0), pc.flow_size - 1), 0);
    vec2 v = vec2(pick.xy) * 0.25;
    float okA = float(pick.z % 1024) / 1000.0;
    float okB = float(pick.z / 1024) / 1000.0;
    float err = float(pick.w) / 4096.0 + (1.0 - max(okA, okB)) * 0.2;
    vec2 qa = p - t * v;
    vec2 rb = p + (1.0 - t) * v;
    vec3 colorA = sa(qa);
    vec3 colorB = sb(rb);
    float wa = (1.0 - t) * mix(0.04, 1.0, okA) * inside(qa);
    float wb = t * mix(0.04, 1.0, okB) * inside(rb);
    vec3 color = (wa + wb) > 1e-4 ? (colorA * wa + colorB * wb) / (wa + wb) : still;
    vec3 luma = vec3(0.2126, 0.7152, 0.0722);
    float stillGap = abs(dot(stillA - stillB, luma));
    float movingGap = abs(dot(colorA - colorB, luma));
    float keep = (1.0 - smoothstep(0.008, 0.025, stillGap)) * smoothstep(0.004, 0.02, movingGap - stillGap);
    color = mix(color, still, keep);
    float fallback = pc.protect.z * smoothstep(pc.protect.x, pc.protect.y, err) * (1.0 - keep);
    color = mix(color, still, fallback);
    o_color = vec4(dither_levels(color), 1.0);
}
