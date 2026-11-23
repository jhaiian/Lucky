const float SCALE_PI = 3.14159265358979;
const float JINC_ZERO = 1.2196698912665045;
const float JINC_RADIUS_3 = 3.2383154841662362;
const float JINC_RADIUS_4 = 4.2410628637960699;
const float EWA_SHARP_BLUR = 0.9812505644269356;
const float EWA_SHARPEST_BLUR = 0.8845120932605005;

float cubic_kernel(float x, float b, float c) {
    if (x < 1.0) {
        return ((12.0 - 9.0 * b - 6.0 * c) * x * x * x + (-18.0 + 12.0 * b + 6.0 * c) * x * x + (6.0 - 2.0 * b)) / 6.0;
    }
    if (x < 2.0) {
        return ((-b - 6.0 * c) * x * x * x + (6.0 * b + 30.0 * c) * x * x + (-12.0 * b - 48.0 * c) * x + (8.0 * b + 24.0 * c)) / 6.0;
    }
    return 0.0;
}

float sinc(float x) {
    if (x < 1e-5) {
        return 1.0;
    }
    float p = SCALE_PI * x;
    return sin(p) / p;
}

float bessel_j1(float x) {
    if (x < 8.0) {
        float y = x * x;
        float a = x * (72362614232.0 + y * (-7895059235.0 + y * (242396853.1 + y * (-2972611.439 + y * (15704.48260 + y * -30.16036606)))));
        float b = 144725228442.0 + y * (2300535178.0 + y * (18583304.74 + y * (99447.43394 + y * (376.9991397 + y))));
        return a / b;
    }
    float z = 8.0 / x;
    float y = z * z;
    float shifted = x - 2.356194491;
    float a = 1.0 + y * (0.183105e-2 + y * (-0.3516396496e-4 + y * (0.2457520174e-5 + y * -0.240337019e-6)));
    float b = 0.04687499995 + y * (-0.2002690873e-3 + y * (0.8449199096e-5 + y * (-0.88228987e-6 + y * 0.105787412e-6)));
    return sqrt(0.636619772 / x) * (cos(shifted) * a - z * sin(shifted) * b);
}

float jinc(float x) {
    if (x < 1e-5) {
        return 1.0;
    }
    float p = SCALE_PI * x;
    return 2.0 * bessel_j1(p) / p;
}

float spline16(float x) {
    if (x < 1.0) {
        return ((x - 9.0 / 5.0) * x - 1.0 / 5.0) * x + 1.0;
    }
    if (x < 2.0) {
        x -= 1.0;
        return ((-1.0 / 3.0 * x + 4.0 / 5.0) * x - 7.0 / 15.0) * x;
    }
    return 0.0;
}

float spline36(float x) {
    if (x < 1.0) {
        return ((13.0 / 11.0 * x - 453.0 / 209.0) * x - 3.0 / 209.0) * x + 1.0;
    }
    if (x < 2.0) {
        x -= 1.0;
        return ((-6.0 / 11.0 * x + 270.0 / 209.0) * x - 156.0 / 209.0) * x;
    }
    if (x < 3.0) {
        x -= 2.0;
        return ((1.0 / 11.0 * x - 45.0 / 209.0) * x + 26.0 / 209.0) * x;
    }
    return 0.0;
}

float spline64(float x) {
    if (x < 1.0) {
        return ((49.0 / 41.0 * x - 6387.0 / 2911.0) * x - 3.0 / 2911.0) * x + 1.0;
    }
    if (x < 2.0) {
        x -= 1.0;
        return ((-24.0 / 41.0 * x + 4032.0 / 2911.0) * x - 2328.0 / 2911.0) * x;
    }
    if (x < 3.0) {
        x -= 2.0;
        return ((6.0 / 41.0 * x - 1008.0 / 2911.0) * x + 582.0 / 2911.0) * x;
    }
    if (x < 4.0) {
        x -= 3.0;
        return ((-1.0 / 41.0 * x + 168.0 / 2911.0) * x - 97.0 / 2911.0) * x;
    }
    return 0.0;
}

float windowed_jinc(float x, float radius) {
    return x < radius ? jinc(x) * jinc(x * JINC_ZERO / radius) : 0.0;
}

float kernel_radius(int k) {
    if (k == 2 || k == 16) {
        return 1.0;
    }
    if (k == 15) {
        return 0.5;
    }
    if (k == 9 || k == 12 || k == 14) {
        return 3.0;
    }
    if (k == 10 || k == 13) {
        return 4.0;
    }
    if (k == 18) {
        return JINC_RADIUS_3;
    }
    if (k == 19) {
        return JINC_RADIUS_3 * EWA_SHARP_BLUR;
    }
    if (k == 20) {
        return JINC_RADIUS_4 * EWA_SHARPEST_BLUR;
    }
    return 2.0;
}

float kernel_weight(int k, float x) {
    x = abs(x);
    if (k == 2) {
        return cubic_kernel(x, 0.0, 0.0);
    }
    if (k == 3) {
        return x < 2.0 ? exp(-2.0 * x * x) : 0.0;
    }
    if (k == 4) {
        return cubic_kernel(x, 1.0 / 3.0, 1.0 / 3.0);
    }
    if (k == 5 || k == 17) {
        return cubic_kernel(x, 0.37821575509399867, 0.31089212245300067);
    }
    if (k == 6) {
        return cubic_kernel(x, 0.0, 0.5);
    }
    if (k == 7) {
        return cubic_kernel(x, 0.2620145123990142, 0.3689927438004929);
    }
    if (k == 8) {
        return spline16(x);
    }
    if (k == 9) {
        return spline36(x);
    }
    if (k == 10) {
        return spline64(x);
    }
    if (k == 11) {
        return x < 2.0 ? sinc(x) * sinc(x / 2.0) : 0.0;
    }
    if (k == 12) {
        return x < 3.0 ? sinc(x) * sinc(x / 3.0) : 0.0;
    }
    if (k == 13) {
        return x < 4.0 ? sinc(x) * sinc(x / 4.0) : 0.0;
    }
    if (k == 14) {
        return x < 3.0 ? sinc(x) * jinc(x * JINC_ZERO / 3.0) : 0.0;
    }
    if (k == 15) {
        return x <= 0.5 ? 1.0 : 0.0;
    }
    if (k == 16) {
        return max(1.0 - x, 0.0);
    }
    if (k == 18) {
        return windowed_jinc(x, JINC_RADIUS_3);
    }
    if (k == 19) {
        return windowed_jinc(x / EWA_SHARP_BLUR, JINC_RADIUS_3);
    }
    if (k == 20) {
        return windowed_jinc(x / EWA_SHARPEST_BLUR, JINC_RADIUS_4);
    }
    return 0.0;
}

vec3 tap(vec2 uv) {
    return split_planes() ? vec3(fetch_luma(uv), 0.0, 0.0) : fetch(uv);
}

vec3 with_chroma(vec3 value, vec2 uv) {
    if (split_planes()) {
        value.yz = fetch_chroma(uv);
    }
    return value;
}

const bool SCALE_POLAR = SCALE_KERNEL >= 17;
const int SCALE_TAPS = 2 * SCALE_REACH;

float scale_weight(float x) {
    return kernel_weight(SCALE_KERNEL, x);
}

#define SCALE_W(t, f) (SCALE_TAPS > t ? scale_weight(float(t + 1 - SCALE_REACH) - (f)) : 0.0)
#define SCALE_INNER(i, j) ((i == SCALE_REACH - 1 || i == SCALE_REACH) && (j == SCALE_REACH - 1 || j == SCALE_REACH))
#define UP_TAP(i, j, w) if (SCALE_TAPS > i) { vec2 o = vec2(float(i + 1 - SCALE_REACH), float(j + 1 - SCALE_REACH)); vec3 c = tap(clamp((base + o + 0.5) / size, bounds.xy, bounds.zw)); if (SCALE_INNER(i, j)) { lo = min(lo, c); hi = max(hi, c); } float ww = (w); sum += c * ww; total += ww; }
#define UP_ROW(j, wy) if (SCALE_TAPS > j) { UP_TAP(0, j, wx0.x * wy) UP_TAP(1, j, wx0.y * wy) UP_TAP(2, j, wx0.z * wy) UP_TAP(3, j, wx0.w * wy) UP_TAP(4, j, wx1.x * wy) UP_TAP(5, j, wx1.y * wy) UP_TAP(6, j, wx1.z * wy) UP_TAP(7, j, wx1.w * wy) }
#define POLAR_TAP(i, j) if (SCALE_TAPS > i) { vec2 o = vec2(float(i + 1 - SCALE_REACH), float(j + 1 - SCALE_REACH)); float ww = scale_weight(length(o - f)); if (ww != 0.0 || SCALE_INNER(i, j)) { vec3 c = tap(clamp((base + o + 0.5) / size, bounds.xy, bounds.zw)); if (SCALE_INNER(i, j)) { lo = min(lo, c); hi = max(hi, c); } sum += c * ww; total += ww; } }
#define POLAR_ROW(j) if (SCALE_TAPS > j) { POLAR_TAP(0, j) POLAR_TAP(1, j) POLAR_TAP(2, j) POLAR_TAP(3, j) POLAR_TAP(4, j) POLAR_TAP(5, j) POLAR_TAP(6, j) POLAR_TAP(7, j) }

vec3 sample_up(vec2 uv, vec2 size, vec4 bounds, float antiring) {
    if (SCALE_KERNEL == 1) {
        return fetch(clamp((floor(uv * size) + 0.5) / size, bounds.xy, bounds.zw));
    }
    vec2 pos = uv * size - 0.5;
    vec2 base = floor(pos);
    vec2 f = pos - base;
    vec3 sum = vec3(0.0);
    float total = 0.0;
    vec3 lo = vec3(1e9);
    vec3 hi = vec3(-1e9);
    if (SCALE_POLAR) {
        POLAR_ROW(0) POLAR_ROW(1) POLAR_ROW(2) POLAR_ROW(3) POLAR_ROW(4) POLAR_ROW(5) POLAR_ROW(6) POLAR_ROW(7)
    } else {
        vec4 wx0 = vec4(SCALE_W(0, f.x), SCALE_W(1, f.x), SCALE_W(2, f.x), SCALE_W(3, f.x));
        vec4 wx1 = vec4(SCALE_W(4, f.x), SCALE_W(5, f.x), SCALE_W(6, f.x), SCALE_W(7, f.x));
        vec4 wy0 = vec4(SCALE_W(0, f.y), SCALE_W(1, f.y), SCALE_W(2, f.y), SCALE_W(3, f.y));
        vec4 wy1 = vec4(SCALE_W(4, f.y), SCALE_W(5, f.y), SCALE_W(6, f.y), SCALE_W(7, f.y));
        UP_ROW(0, wy0.x) UP_ROW(1, wy0.y) UP_ROW(2, wy0.z) UP_ROW(3, wy0.w) UP_ROW(4, wy1.x) UP_ROW(5, wy1.y) UP_ROW(6, wy1.z) UP_ROW(7, wy1.w)
    }
    sum /= abs(total) > 1e-6 ? total : 1.0;
    return with_chroma(mix(sum, clamp(sum, lo, hi), antiring), uv);
}

vec3 sample_down(vec2 uv, vec2 size, vec4 bounds, vec2 ratio, float antiring) {
    vec2 r = max(ratio, vec2(1.0));
    vec2 window = 2.0 * kernel_radius(SCALE_KERNEL) * r;
    vec2 counts = clamp(ceil(window * 0.75), vec2(2.0), vec2(8.0));
    int nx = int(counts.x);
    int ny = int(counts.y);
    vec2 step = window / counts;
    vec2 start = -0.5 * window + 0.5 * step;
    vec2 center = 0.5 * r + 0.5 * step;
    vec3 sum = vec3(0.0);
    float total = 0.0;
    vec3 lo = vec3(1e9);
    vec3 hi = vec3(-1e9);
    for (int j = 0; j < 8; j++) {
        if (j >= ny) {
            break;
        }
        float oy = start.y + float(j) * step.y;
        float wy = kernel_weight(SCALE_KERNEL, oy / r.y);
        for (int i = 0; i < 8; i++) {
            if (i >= nx) {
                break;
            }
            float ox = start.x + float(i) * step.x;
            float w = wy * kernel_weight(SCALE_KERNEL, ox / r.x);
            bool inner = abs(ox) <= center.x && abs(oy) <= center.y;
            if (w == 0.0 && !inner) {
                continue;
            }
            vec3 c = tap(clamp(uv + vec2(ox, oy) / size, bounds.xy, bounds.zw));
            if (inner) {
                lo = min(lo, c);
                hi = max(hi, c);
            }
            sum += c * w;
            total += w;
        }
    }
    sum /= abs(total) > 1e-6 ? total : 1.0;
    return with_chroma(mix(sum, clamp(sum, lo, hi), antiring), uv);
}

vec3 sample_scaled(vec2 uv, vec2 size, vec4 bounds, vec2 ratio, float antiring) {
    if (SCALE_KERNEL == 0 || size.x < 1.0 || size.y < 1.0) {
        return fetch(uv);
    }
    if (SCALE_DOWN == 1) {
        return sample_down(uv, size, bounds, ratio, antiring);
    }
    return sample_up(uv, size, bounds, antiring);
}
