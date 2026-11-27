#version 450

layout(set = 0, binding = 0) uniform isampler2D u_pick;

layout(push_constant) uniform Settle {
    ivec2 size;
} pc;

layout(location = 0) out ivec4 o_pick;

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
    return texelFetch(u_pick, clamp(p, ivec2(0), pc.size - 1), 0);
}

void main() {
    ivec2 c = ivec2(gl_FragCoord.xy);
    ivec4 k[9];
    for (int j = 0; j < 3; j++) {
        for (int i = 0; i < 3; i++) {
            k[j * 3 + i] = at(c + ivec2(i - 1, j - 1));
        }
    }
    ivec2 m = ivec2(
        median9(k[0].x, k[1].x, k[2].x, k[3].x, k[4].x, k[5].x, k[6].x, k[7].x, k[8].x),
        median9(k[0].y, k[1].y, k[2].y, k[3].y, k[4].y, k[5].y, k[6].y, k[7].y, k[8].y)
    );
    ivec4 best = k[4];
    int gap = abs(best.x - m.x) + abs(best.y - m.y);
    for (int n = 0; n < 9; n++) {
        int g = abs(k[n].x - m.x) + abs(k[n].y - m.y);
        if (g < gap) {
            gap = g;
            best = k[n];
        }
    }
    o_pick = ivec4(m, best.z, best.w);
}
