#version 450

layout(set = 0, binding = 0) uniform sampler2D u_tiles;
layout(set = 0, binding = 1) uniform isampler2D u_field;

layout(push_constant) uniform Stats {
    ivec2 tiles_size;
    ivec2 field_size;
    vec2 cut;
    int has_field;
} pc;

layout(location = 0) out vec4 o_stats;

void main() {
    float peak = 0.0;
    float mean = 0.0;
    float light = 0.0;
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
            light += t.b;
        }
    }
    float tiles = float(max(pc.tiles_size.x * pc.tiles_size.y, 1));
    mean /= tiles;
    light /= tiles;
    float cost = 0.0;
    float failed = 0.0;
    float limit = pc.cut.x * clamp(light / 0.35, 0.3, 1.0);
    if (pc.has_field == 1) {
        for (int j = 0; j < 24; j++) {
            for (int i = 0; i < 24; i++) {
                ivec2 p = ivec2((vec2(float(i), float(j)) + 0.5) * vec2(pc.field_size) / 24.0);
                float c = float(texelFetch(u_field, clamp(p, ivec2(0), pc.field_size - 1), 0).z) / 4096.0;
                cost += c;
                failed += step(limit, c);
            }
        }
        cost /= 576.0;
        failed /= 576.0;
    }
    bool cut = pc.has_field == 1 && failed > pc.cut.y && cost > 0.12 * mean;
    o_stats = vec4(peak, mean, clamp(cost * 4.0, 0.0, 1.0), cut ? 1.0 : 0.0);
}
