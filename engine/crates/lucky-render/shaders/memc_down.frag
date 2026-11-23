#version 450

layout(set = 0, binding = 0) uniform sampler2D u_src;

layout(push_constant) uniform Down {
    vec2 inv;
    int color;
} pc;

layout(location = 0) out vec4 o_luma;

void main() {
    vec4 c = textureLod(u_src, gl_FragCoord.xy * 2.0 * pc.inv, 0.0);
    float y = pc.color == 1 ? dot(c.rgb, vec3(0.2126, 0.7152, 0.0722)) : c.r;
    o_luma = vec4(y, 0.0, 0.0, 1.0);
}
