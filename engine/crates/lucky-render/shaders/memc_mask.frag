#version 450

layout(set = 0, binding = 0) uniform isampler2D u_pick;

layout(push_constant) uniform Mask {
    ivec2 size;
} pc;

layout(location = 0) out vec4 o_risk;

void main() {
    ivec2 c = ivec2(gl_FragCoord.xy);
    ivec4 k = texelFetch(u_pick, clamp(c, ivec2(0), pc.size - 1), 0);
    float okA = float(k.z % 128) / 127.0;
    float okB = float(k.z / 128) / 127.0;
    float err = float(k.w) / 4096.0;
    float risk = smoothstep(0.14, 0.3, err + (1.0 - max(okA, okB)) * 0.2);
    o_risk = vec4(risk, 0.0, 0.0, 1.0);
}
