vec4 dovi_texel(int x, int y) {
    return texelFetch(u_dovi, ivec2(x, y), 0);
}

float dovi_pivot(int component, int index) {
    vec4 group = dovi_texel(1 + index / 4, component);
    int slot = index - (index / 4) * 4;
    return slot == 0 ? group.x : (slot == 1 ? group.y : (slot == 2 ? group.z : group.w));
}

float dovi_reshape(int component, vec3 sig) {
    int count = int(dovi_texel(0, component).x + 0.5);
    float s = clamp(sig[component], dovi_pivot(component, 0), dovi_pivot(component, count - 1));
    int piece = 0;
    for (int i = 1; i < 8; i++) {
        if (i >= count - 1) {
            break;
        }
        if (s >= dovi_pivot(component, i)) {
            piece = i;
        }
    }
    int base = 4 + piece * 8;
    vec4 meta = dovi_texel(base, component);
    if (meta.x < 0.5) {
        vec3 k = dovi_texel(base + 1, component).xyz;
        return k.x + s * (k.y + s * k.z);
    }
    vec4 terms = vec4(sig.x * sig.y, sig.x * sig.z, sig.y * sig.z, sig.x * sig.y * sig.z);
    vec3 power = sig;
    vec4 power_terms = terms;
    float result = meta.y + dot(dovi_texel(base + 2, component).xyz, power) + dot(dovi_texel(base + 3, component), power_terms);
    if (meta.x > 1.5) {
        power *= sig;
        power_terms *= terms;
        result += dot(dovi_texel(base + 4, component).xyz, power) + dot(dovi_texel(base + 5, component), power_terms);
    }
    if (meta.x > 2.5) {
        power *= sig;
        power_terms *= terms;
        result += dot(dovi_texel(base + 6, component).xyz, power) + dot(dovi_texel(base + 7, component), power_terms);
    }
    return result;
}

vec3 dovi_decode(vec3 ycc) {
    vec3 sig = clamp(ycc, 0.0, 1.0);
    vec3 shaped = vec3(dovi_reshape(0, sig), dovi_reshape(1, sig), dovi_reshape(2, sig));
    mat3 nonlinear = mat3(dovi_texel(0, 3).xyz, dovi_texel(1, 3).xyz, dovi_texel(2, 3).xyz);
    mat3 to_rgb = mat3(dovi_texel(4, 3).xyz, dovi_texel(5, 3).xyz, dovi_texel(6, 3).xyz);
    vec3 lms = nonlinear * (shaped - dovi_texel(3, 3).xyz);
    return linear_to_pq(to_rgb * pq_to_linear(lms));
}
