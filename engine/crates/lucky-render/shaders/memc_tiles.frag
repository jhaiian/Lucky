#version 450

layout(set = 0, binding = 0) uniform sampler2D u_a;
layout(set = 0, binding = 1) uniform sampler2D u_b;

layout(push_constant) uniform Tiles {
    ivec2 size;
} pc;

layout(location = 0) out vec4 o_tile;

void main() {
    ivec2 base = ivec2(gl_FragCoord.xy) * 8;
    float peak = 0.0;
    float sum = 0.0;
    float light = 0.0;
    float count = 0.0;
    for (int j = 0; j < 8; j++) {
        for (int i = 0; i < 8; i++) {
            ivec2 p = base + ivec2(i, j);
            if (p.x < pc.size.x && p.y < pc.size.y) {
                float a = texelFetch(u_a, p, 0).r;
                float b = texelFetch(u_b, p, 0).r;
                float d = abs(a - b);
                peak = max(peak, d);
                sum += d;
                light += a + b;
                count += 1.0;
            }
        }
    }
    o_tile = count > 0.0 ? vec4(peak, sum / count, light / (2.0 * count), 1.0) : vec4(0.0);
}
