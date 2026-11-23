#version 450
#extension GL_GOOGLE_include_directive : require
#include "params.glsl"
#include "color.glsl"

layout(location = 0) in vec2 v_tex;
layout(location = 0) out vec4 o_color;
layout(set = 0, binding = 0) uniform sampler2D u_tex0;

vec3 fetch(vec2 uv) {
    return texture(u_tex0, uv).rgb;
}

bool split_planes() {
    return false;
}

float fetch_luma(vec2 uv) {
    return 0.0;
}

vec2 fetch_chroma(vec2 uv) {
    return vec2(0.0);
}

#include "scale.glsl"

void main() {
    vec2 size = params.source_size;
    vec2 half_texel = 0.5 / max(size, vec2(1.0));
    vec4 bounds = vec4(params.crop.xy + half_texel, params.crop.xy + params.crop.zw - half_texel);
    vec3 rgb = sample_scaled(v_tex, size, bounds, params.ratio, params.antiring);
    o_color = vec4(dither(finish(rgb, param_transfer(), param_bt2020(), params.peak, param_tone(), param_output()), param_dither()), 1.0);
}
