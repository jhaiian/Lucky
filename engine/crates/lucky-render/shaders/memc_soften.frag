#version 450

layout(set = 0, binding = 0) uniform sampler2D u_src;
layout(set = 0, binding = 1) uniform sampler2D u_stats;

layout(push_constant) uniform Soften {
    vec2 inv;
} pc;

layout(location = 0) out vec4 o_risk;

void main() {
    vec2 p = gl_FragCoord.xy;
    float sum = 0.0;
    float peak = 0.0;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            float r = textureLod(u_src, (p + vec2(float(i), float(j)) * 2.0) * pc.inv, 0.0).r;
            sum += r;
            peak = max(peak, r);
        }
    }
    vec4 stats = textureLod(u_stats, vec2(0.5), 0.0);
    o_risk = vec4(max(sum / 25.0, peak * 0.6), stats.a, stats.b, 1.0);
}
