#version 450

layout(set = 0, binding = 0) uniform sampler2D u_tiles;
layout(set = 0, binding = 1) uniform isampler2D u_field;
layout(set = 0, binding = 2) uniform isampler2D u_previous;

layout(push_constant) uniform Stats {
    ivec2 tiles_size;
    ivec2 field_size;
    int has_field;
    int has_previous;
} pc;

layout(location = 0) out vec4 o_stats;

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
        if (y >= pc.tiles_size.y) {
            break;
        }
        for (int x = 0; x < 64; x++) {
            if (x >= pc.tiles_size.x) {
                break;
            }
            vec4 t = texelFetch(u_tiles, ivec2(x, y), 0);
            peak = max(peak, t.r);
            mean += t.g;
            lightA += t.b;
            lightB += t.a;
        }
    }
    float tiles = float(max(pc.tiles_size.x * pc.tiles_size.y, 1));
    mean /= tiles;
    lightA /= tiles;
    lightB /= tiles;
    float fade = abs(lightA - lightB) / ((lightA + lightB) * 0.5 + 0.02);
    vec2 now = vec2(0.0);
    vec2 before = vec2(0.0);
    if (pc.has_field == 1) {
        for (int j = 0; j < 24; j++) {
            for (int i = 0; i < 24; i++) {
                ivec2 p = clamp(ivec2((vec2(float(i), float(j)) + 0.5) * vec2(pc.field_size) / 24.0), ivec2(0), pc.field_size - 1);
                now += lost(texelFetch(u_field, p, 0));
                if (pc.has_previous == 1) {
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
