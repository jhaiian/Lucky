#version 450
#extension GL_GOOGLE_include_directive : require
#include "params.glsl"

layout(location = 0) out vec2 v_tex;

void main() {
    vec2 pos = vec2(float((gl_VertexIndex & 1) * 2 - 1), float((gl_VertexIndex >> 1) * 2 - 1));
    vec2 s = vec2((pos.x + 1.0) * 0.5, (1.0 - pos.y) * 0.5);
    int turns = param_turns();
    int pre = param_pre_turns();
    if (turns == 1) {
        s = vec2(s.y, 1.0 - s.x);
    } else if (turns == 2) {
        s = vec2(1.0 - s.x, 1.0 - s.y);
    } else if (turns == 3) {
        s = vec2(1.0 - s.y, s.x);
    }
    v_tex = params.crop.xy + s * params.crop.zw;
    vec2 clip = vec2(pos.x * params.scale.x + params.translate.x, -pos.y * params.scale.y + params.translate.y);
    if (pre == 1) {
        clip = vec2(-clip.y, clip.x);
    } else if (pre == 2) {
        clip = -clip;
    } else if (pre == 3) {
        clip = vec2(clip.y, -clip.x);
    }
    gl_Position = vec4(clip, 0.0, 1.0);
}
