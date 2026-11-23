pub const VERTEX: &str = r#"#version 300 es
uniform vec2 u_scale;
uniform vec2 u_translate;
uniform int u_turns;
uniform vec4 u_crop;
out vec2 v_tex;
void main() {
    vec2 pos = vec2(float((gl_VertexID & 1) * 2 - 1), float((gl_VertexID >> 1) * 2 - 1));
    vec2 s = vec2((pos.x + 1.0) * 0.5, (1.0 - pos.y) * 0.5);
    if (u_turns == 1) {
        s = vec2(s.y, 1.0 - s.x);
    } else if (u_turns == 2) {
        s = vec2(1.0 - s.x, 1.0 - s.y);
    } else if (u_turns == 3) {
        s = vec2(1.0 - s.y, s.x);
    }
    v_tex = u_crop.xy + s * u_crop.zw;
    gl_Position = vec4(pos * u_scale + vec2(u_translate.x, -u_translate.y), 0.0, 1.0);
}
"#;

pub const FRAGMENT: &str = concat!(
    r#"#version 300 es
precision highp float;
in vec2 v_tex;
out vec4 o_color;
uniform sampler2D u_tex0;
uniform sampler2D u_tex1;
uniform sampler2D u_tex2;
uniform int u_layout;
uniform float u_sample_scale;
uniform mat3 u_matrix;
uniform vec3 u_offset;
uniform int u_transfer;
uniform int u_bt2020;
uniform float u_peak;
uniform int u_swap_uv;
uniform int u_output;
uniform vec2 u_ratio;
uniform float u_antiring;
uniform highp sampler2D u_dovi;
uniform int u_dovi_on;
uniform vec2 u_tone;
uniform int u_dither;

vec3 fetch(vec2 uv) {
    if (u_layout == 2) {
        return texture(u_tex0, uv).rgb;
    }
    if (u_layout == 1) {
        vec2 c = texture(u_tex1, uv).rg;
        if (u_swap_uv == 1) {
            c = c.yx;
        }
        return vec3(texture(u_tex0, uv).r, c) * u_sample_scale;
    }
    if (u_layout == 3) {
        return vec3(texture(u_tex0, uv).r * u_sample_scale, u_offset.yz);
    }
    return vec3(texture(u_tex0, uv).r, texture(u_tex1, uv).r, texture(u_tex2, uv).r) * u_sample_scale;
}

bool split_planes() {
    return u_layout != 2;
}

float fetch_luma(vec2 uv) {
    return texture(u_tex0, uv).r * u_sample_scale;
}

vec2 fetch_chroma(vec2 uv) {
    if (u_layout == 1) {
        vec2 c = texture(u_tex1, uv).rg;
        return (u_swap_uv == 1 ? c.yx : c) * u_sample_scale;
    }
    if (u_layout == 3) {
        return u_offset.yz;
    }
    return vec2(texture(u_tex1, uv).r, texture(u_tex2, uv).r) * u_sample_scale;
}
"#,
    include_str!("../shaders/scale.glsl"),
    include_str!("../shaders/color.glsl"),
    include_str!("../shaders/dovi.glsl"),
    r#"
void main() {
    vec2 size = vec2(textureSize(u_tex0, 0));
    vec4 bounds = vec4(0.5 / size, 1.0 - 0.5 / size);
    vec3 value = sample_scaled(v_tex, size, bounds, u_ratio, u_antiring);
    if (u_layout == 2) {
        o_color = vec4(dither(finish(value, 0, 0, 1.0, u_tone, u_output), u_dither), 1.0);
        return;
    }
    vec3 rgb = u_dovi_on == 1 ? dovi_decode(value) : u_matrix * (value - u_offset);
    o_color = vec4(dither(finish(rgb, u_transfer, u_bt2020, u_peak, u_tone, u_output), u_dither), 1.0);
}
"#
);

pub const EXTERNAL_PREAMBLE_ESSL3: &str = "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\n";

pub const EXTERNAL_BODY: &str = concat!(
    r#"precision highp float;
in vec2 v_tex;
out vec4 o_color;
uniform samplerExternalOES u_tex0;
uniform int u_transfer;
uniform int u_bt2020;
uniform float u_peak;
uniform int u_output;
uniform vec2 u_ratio;
uniform float u_antiring;
uniform vec2 u_source_size;
uniform vec4 u_crop;
uniform vec2 u_tone;
uniform int u_dither;

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
"#,
    include_str!("../shaders/scale.glsl"),
    include_str!("../shaders/color.glsl"),
    r#"
void main() {
    vec2 half_texel = 0.5 / max(u_source_size, vec2(1.0));
    vec4 bounds = vec4(u_crop.xy + half_texel, u_crop.xy + u_crop.zw - half_texel);
    vec3 rgb = sample_scaled(v_tex, u_source_size, bounds, u_ratio, u_antiring);
    o_color = vec4(dither(finish(rgb, u_transfer, u_bt2020, u_peak, u_tone, u_output), u_dither), 1.0);
}
"#
);

pub fn specialize(source: &str, kernel: i32, downscale: bool, reach: i32) -> String {
    let constants = format!(
        "precision highp float;\nconst int SCALE_KERNEL = {kernel};\nconst int SCALE_DOWN = {};\nconst int SCALE_REACH = {reach};\n",
        downscale as i32
    );
    source.replacen("precision highp float;\n", &constants, 1)
}

pub const BLEND_FRAGMENT: &str = r#"#version 300 es
precision highp float;
in vec2 v_tex;
out vec4 o_color;
uniform sampler2D u_a;
uniform sampler2D u_b;
uniform float u_weight;
uniform float u_dither;
void main() {
    vec3 color = mix(texture(u_a, v_tex).rgb, texture(u_b, v_tex).rgb, u_weight);
    if (u_dither > 0.0) {
        float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
        color += vec3((noise - 0.5) / u_dither);
    }
    o_color = vec4(color, 1.0);
}
"#;
