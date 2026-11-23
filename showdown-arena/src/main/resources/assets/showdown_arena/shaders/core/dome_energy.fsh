#version 150

uniform vec4 ColorModulator;
uniform float GameTime;

in vec2 vUv;
in vec4 vColor;
in vec3 vNormalView;
in vec3 vViewPos;

out vec4 fragColor;

float domeHexDist(vec2 p) {
    p = abs(p);
    float c = dot(p, normalize(vec2(1.0, 1.7320508)));
    return max(c, p.x);
}

float domeHexGrid(vec2 uv) {
    vec2 r = vec2(1.0, 1.7320508);
    vec2 h = r * 0.5;
    vec2 a = mod(uv, r) - h;
    vec2 b = mod(uv - h, r) - h;
    vec2 gv = dot(a, a) < dot(b, b) ? a : b;
    float edge = 0.5 - domeHexDist(gv);
    return 1.0 - smoothstep(0.015, 0.065, edge);
}

void main() {
    vec3 V = normalize(-vViewPos);
    vec3 N = normalize(vNormalView);
    float facing = clamp(abs(dot(N, V)), 0.0, 1.0);
    float fresnel = pow(1.0 - facing, 1.55);

    // Integer horizontal tiling guarantees seamless wrap around u = 0..1
    vec2 gridUv = vec2(vUv.x * 36.0, vUv.y * 12.0);
    float hexLine = domeHexGrid(gridUv);

    float h = clamp(vUv.y, 0.0, 1.0);
    float t = GameTime * 36.0;

    // Traveling energy pulse waves rising from the arena base to the zenith oculus
    float wave1 = pow(0.5 + 0.5 * sin(h * 16.0 - t * 0.14), 5.0);
    float wave2 = pow(0.5 + 0.5 * sin(h * 8.0 - t * 0.07 + 2.1), 4.0);
    float baseAnchor = exp(-h * 8.0);
    float oculusRim = smoothstep(0.84, 1.0, h);

    float energy = 0.14 + fresnel * 0.52 + hexLine * (0.48 + wave1 * 0.70) + wave2 * 0.16 + baseAnchor * 0.42 + oculusRim * 0.48;
    vec3 rgb = mix(vColor.rgb, min(vColor.rgb * 1.35 + vec3(0.05), vec3(1.0)), clamp(hexLine * wave1 * 0.28 + oculusRim * 0.18, 0.0, 0.35));
    float alpha = vColor.a * energy;

    vec4 color = vec4(rgb, clamp(alpha, 0.0, 0.92)) * ColorModulator;
    if (color.a < 0.004) {
        discard;
    }
    fragColor = color;
}
