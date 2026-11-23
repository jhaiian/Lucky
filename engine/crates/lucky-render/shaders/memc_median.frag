#version 450

layout(set = 0, binding = 0) uniform isampler2D u_field;

layout(push_constant) uniform Median {
    ivec2 size;
} pc;

layout(location = 0) out ivec4 o_field;

#define ORDER(a, b) { int lo = min(a, b); b = max(a, b); a = lo; }

int median9(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
    ORDER(p1, p2) ORDER(p4, p5) ORDER(p7, p8)
    ORDER(p0, p1) ORDER(p3, p4) ORDER(p6, p7)
    ORDER(p1, p2) ORDER(p4, p5) ORDER(p7, p8)
    ORDER(p0, p3) ORDER(p5, p8) ORDER(p4, p7)
    ORDER(p3, p6) ORDER(p1, p4) ORDER(p2, p5)
    ORDER(p4, p7) ORDER(p4, p2) ORDER(p6, p4)
    ORDER(p4, p2)
    return p4;
}

ivec4 at(ivec2 p) {
    return texelFetch(u_field, clamp(p, ivec2(0), pc.size - 1), 0);
}

void main() {
    ivec2 c = ivec2(gl_FragCoord.xy);
    ivec4 a = at(c + ivec2(-1, -1));
    ivec4 b = at(c + ivec2(0, -1));
    ivec4 d = at(c + ivec2(1, -1));
    ivec4 e = at(c + ivec2(-1, 0));
    ivec4 f = at(c);
    ivec4 g = at(c + ivec2(1, 0));
    ivec4 h = at(c + ivec2(-1, 1));
    ivec4 i = at(c + ivec2(0, 1));
    ivec4 j = at(c + ivec2(1, 1));
    int x = median9(a.x, b.x, d.x, e.x, f.x, g.x, h.x, i.x, j.x);
    int y = median9(a.y, b.y, d.y, e.y, f.y, g.y, h.y, i.y, j.y);
    o_field = ivec4(x, y, f.z, 0);
}
