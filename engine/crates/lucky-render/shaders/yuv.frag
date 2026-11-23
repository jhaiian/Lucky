#version 450
#extension GL_GOOGLE_include_directive : require
#include "params.glsl"
#include "color.glsl"

layout(location = 0) in vec2 v_tex;
layout(location = 0) out vec4 o_color;
layout(set = 0, binding = 0) uniform sampler2D u_tex0;
layout(set = 0, binding = 1) uniform sampler2D u_tex1;
layout(set = 0, binding = 2) uniform sampler2D u_tex2;

vec3 fetch(vec2 uv) {
    float scale = params.offset.w;
    if (param_layout() == 2) {
        return texture(u_tex0, uv).rgb;
    }
    if (param_layout() == 1) {
        vec2 c = texture(u_tex1, uv).rg;
        if (param_swap_uv()) {
            c = c.yx;
        }
        return vec3(texture(u_tex0, uv).r, c) * scale;
    }
    if (param_layout() == 3) {
        return vec3(texture(u_tex0, uv).r * scale, params.offset.yz);
    }
    return vec3(texture(u_tex0, uv).r, texture(u_tex1, uv).r, texture(u_tex2, uv).r) * scale;
}

bool split_planes() {
    return param_layout() != 2;
}

float fetch_luma(vec2 uv) {
    return texture(u_tex0, uv).r * params.offset.w;
}

vec2 fetch_chroma(vec2 uv) {
    int layout_mode = param_layout();
    if (layout_mode == 1) {
        vec2 c = texture(u_tex1, uv).rg;
        return (param_swap_uv() ? c.yx : c) * params.offset.w;
    }
    if (layout_mode == 3) {
        return params.offset.yz;
    }
    return vec2(texture(u_tex1, uv).r, texture(u_tex2, uv).r) * params.offset.w;
}

#include "scale.glsl"

void main() {
    vec2 size = vec2(textureSize(u_tex0, 0));
    vec4 bounds = vec4(0.5 / size, 1.0 - 0.5 / size);
    vec3 value = sample_scaled(v_tex, size, bounds, params.ratio, params.antiring);
    if (param_layout() == 2) {
        o_color = vec4(dither(finish(value, 0, 0, 1.0, param_tone(), param_output()), param_dither()), 1.0);
        return;
    }
    mat3 m = mat3(params.matrix0.xyz, params.matrix1.xyz, params.matrix2.xyz);
    vec3 rgb = m * (value - params.offset.xyz);
    o_color = vec4(dither(finish(rgb, param_transfer(), param_bt2020(), params.peak, param_tone(), param_output()), param_dither()), 1.0);
}
