layout(constant_id = 0) const int SCALE_KERNEL = 0;
layout(constant_id = 1) const int SCALE_DOWN = 0;
layout(constant_id = 2) const int SCALE_REACH = 1;

layout(push_constant) uniform Params {
    vec4 crop;
    vec4 matrix0;
    vec4 matrix1;
    vec4 matrix2;
    vec4 offset;
    vec2 scale;
    float peak;
    uint tone;
    int flags;
    float antiring;
    vec2 ratio;
    vec2 translate;
    vec2 source_size;
} params;

bool param_swap_uv() {
    return (params.flags & 1) == 1;
}

int param_filter() {
    return (params.flags >> 1) & 31;
}

int param_downscale() {
    return (params.flags >> 6) & 1;
}

int param_output() {
    return (params.flags >> 7) & 7;
}

int param_layout() {
    return (params.flags >> 10) & 3;
}

int param_transfer() {
    return (params.flags >> 12) & 3;
}

int param_bt2020() {
    return (params.flags >> 14) & 1;
}

int param_dither() {
    int depth = (params.flags >> 15) & 3;
    return depth == 1 ? 8 : (depth == 2 ? 10 : 0);
}

int param_turns() {
    return (params.flags >> 17) & 3;
}

int param_pre_turns() {
    return (params.flags >> 19) & 3;
}

vec2 param_tone() {
    return unpackHalf2x16(params.tone);
}
