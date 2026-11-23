const mat3 BT2020_TO_BT709 = mat3(1.660491, -0.124550, -0.018151, -0.587641, 1.132900, -0.100579, -0.072850, -0.008349, 1.118730);
const mat3 BT709_TO_BT2020 = mat3(0.627404, 0.069097, 0.016391, 0.329283, 0.919540, 0.088013, 0.043313, 0.011362, 0.895595);
const mat3 BT2020_TO_P3 = mat3(1.343578, -0.065297, 0.002822, -0.282180, 1.075788, -0.019598, -0.061399, -0.010490, 1.016777);
const mat3 BT709_TO_P3 = mat3(0.822462, 0.033194, 0.017083, 0.177538, 0.966806, 0.072397, 0.0, 0.0, 0.910520);
const vec3 LUMA_709 = vec3(0.2126, 0.7152, 0.0722);
const vec3 LUMA_2020 = vec3(0.2627, 0.6780, 0.0593);
const vec3 LUMA_P3 = vec3(0.2290, 0.6917, 0.0793);
const float PQ_M1 = 0.1593017578125;
const float PQ_M2 = 78.84375;
const float PQ_C1 = 0.8359375;
const float PQ_C2 = 18.8515625;
const float PQ_C3 = 18.6875;
const float REFERENCE_WHITE = 203.0 / 10000.0;

vec3 pq_to_linear(vec3 e) {
    vec3 p = pow(clamp(e, 0.0, 1.0), vec3(1.0 / PQ_M2));
    vec3 l = pow(max(p - PQ_C1, 0.0) / (PQ_C2 - PQ_C3 * p), vec3(1.0 / PQ_M1));
    return l / REFERENCE_WHITE;
}

vec3 linear_to_pq(vec3 c) {
    vec3 y = pow(clamp(c * REFERENCE_WHITE, 0.0, 1.0), vec3(PQ_M1));
    return pow((PQ_C1 + PQ_C2 * y) / (1.0 + PQ_C3 * y), vec3(PQ_M2));
}

float encode_pq(float c) {
    float y = pow(clamp(c * REFERENCE_WHITE, 0.0, 1.0), PQ_M1);
    return pow((PQ_C1 + PQ_C2 * y) / (1.0 + PQ_C3 * y), PQ_M2);
}

float decode_pq(float e) {
    float p = pow(clamp(e, 0.0, 1.0), 1.0 / PQ_M2);
    return pow(max(p - PQ_C1, 0.0) / (PQ_C2 - PQ_C3 * p), 1.0 / PQ_M1) / REFERENCE_WHITE;
}

float hlg_peak(float target, int output_mode) {
    return output_mode == 2 && target > 0.0 ? clamp(target * 203.0, 400.0, 2000.0) : 1000.0;
}

vec3 hlg_to_linear(vec3 e, float peak_nits) {
    const float a = 0.17883277;
    const float b = 0.28466892;
    const float c = 0.55991073;
    e = clamp(e, 0.0, 1.0);
    vec3 low = e * e / 3.0;
    vec3 high = (exp((e - c) / a) + b) / 12.0;
    vec3 scene = mix(low, high, step(vec3(0.5), e));
    float ys = dot(scene, LUMA_2020);
    float gamma = 1.2 + 0.1824053 * log(peak_nits / 1000.0);
    vec3 display = scene * pow(max(ys, 1e-6), gamma - 1.0);
    return display * (peak_nits / 203.0);
}

vec3 tone_map(vec3 c, float peak, float target, float black) {
    float m = max(max(c.r, c.g), c.b);
    if (m <= 0.0) {
        return vec3(0.0);
    }
    if (peak <= target && black <= 0.0) {
        return c;
    }
    float source = encode_pq(max(peak, target));
    float high = encode_pq(target) / source;
    float e = min(encode_pq(m) / source, 1.0);
    if (peak > target) {
        float knee = 1.5 * high - 0.5;
        if (e > knee) {
            float t = (e - knee) / (1.0 - knee);
            float t2 = t * t;
            float t3 = t2 * t;
            e = (2.0 * t3 - 3.0 * t2 + 1.0) * knee + (t3 - 2.0 * t2 + t) * (1.0 - knee) + (-2.0 * t3 + 3.0 * t2) * high;
        }
    }
    if (black > 0.0) {
        float low = encode_pq(black) / source;
        float rest = max(1.0 - e / high, 0.0);
        e += low * rest * rest * rest * rest;
    }
    return c * (decode_pq(e * source) / m);
}

vec3 remove_black(vec3 c, float black) {
    return black > 0.0 ? max(c - vec3(black), 0.0) / (1.0 - black) : c;
}

vec3 dither(vec3 c, int bits) {
    if (bits <= 0) {
        return c;
    }
    float levels = bits >= 10 ? 1023.0 : 255.0;
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    return c + vec3((noise - 0.5) / levels);
}

vec3 fit_gamut(vec3 c, vec3 luma) {
    float l = dot(c, luma);
    if (l <= 0.0) {
        return vec3(0.0);
    }
    l = min(l, 1.0);
    float top = max(max(c.r, c.g), c.b);
    float bottom = min(min(c.r, c.g), c.b);
    float k = 1.0;
    if (top > 1.0) {
        k = min(k, (1.0 - l) / max(top - l, 1e-6));
    }
    if (bottom < 0.0) {
        k = min(k, l / max(l - bottom, 1e-6));
    }
    return clamp(vec3(l) + (c - vec3(l)) * k, 0.0, 1.0);
}

vec3 finish(vec3 rgb, int transfer, int bt2020, float peak, vec2 tone, int target) {
    if (transfer == 0) {
        if (target == 4 || (target == 0 && bt2020 == 0)) {
            return clamp(rgb, 0.0, 1.0);
        }
        vec3 sdr = pow(max(rgb, 0.0), vec3(2.2));
        if (target == 2) {
            return linear_to_pq(bt2020 == 1 ? sdr : BT709_TO_BT2020 * sdr);
        }
        if (target == 0) {
            return pow(fit_gamut(BT2020_TO_BT709 * sdr, LUMA_709), vec3(1.0 / 2.2));
        }
        sdr = bt2020 == 1 ? fit_gamut(BT2020_TO_P3 * sdr, LUMA_P3) : clamp(BT709_TO_P3 * sdr, 0.0, 1.0);
        return target == 3 ? sdr : pow(sdr, vec3(1.0 / 2.2));
    }
    vec3 linear = max(transfer == 1 ? pq_to_linear(rgb) : hlg_to_linear(rgb, hlg_peak(tone.x, target)), 0.0);
    vec3 luma = bt2020 == 1 ? LUMA_2020 : LUMA_709;
    if (target == 4) {
        return vec3(encode_pq(max(max(linear.r, linear.g), linear.b)), encode_pq(dot(linear, luma)), 0.0);
    }
    if (target == 2) {
        if (tone.x > 0.0) {
            linear = tone_map(linear, peak, tone.x, tone.y);
        }
        return linear_to_pq(bt2020 == 1 ? linear : BT709_TO_BT2020 * linear);
    }
    linear = tone_map(linear, peak, tone.x > 0.0 ? tone.x : 1.0, tone.y);
    if (target == 0) {
        linear = fit_gamut(bt2020 == 1 ? BT2020_TO_BT709 * linear : linear, LUMA_709);
        return pow(remove_black(linear, tone.y), vec3(1.0 / 2.2));
    }
    linear = remove_black(fit_gamut(bt2020 == 1 ? BT2020_TO_P3 * linear : BT709_TO_P3 * linear, LUMA_P3), tone.y);
    return target == 3 ? linear : pow(linear, vec3(1.0 / 2.2));
}
