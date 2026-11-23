#version 450

layout(location = 0) in vec2 v_tex;
layout(location = 0) out vec4 o_color;
layout(set = 0, binding = 0) uniform sampler2D u_a;
layout(set = 0, binding = 1) uniform sampler2D u_b;

layout(push_constant) uniform Blend {
    float weight;
    float dither;
} blend;

void main() {
    vec3 a = textureLod(u_a, v_tex, 0.0).rgb;
    vec3 b = textureLod(u_b, v_tex, 0.0).rgb;
    vec3 color = mix(a, b, blend.weight);
    if (blend.dither > 0.0) {
        float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
        color += vec3((noise - 0.5) / blend.dither);
    }
    o_color = vec4(color, 1.0);
}
