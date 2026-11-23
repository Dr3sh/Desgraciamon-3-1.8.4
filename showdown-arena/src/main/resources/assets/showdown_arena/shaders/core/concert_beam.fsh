#version 150

uniform vec4 ColorModulator;
uniform float GameTime;

in vec2 vUv;
in vec4 vColor;
in vec3 vNormalView;
in vec3 vViewPos;

out vec4 fragColor;

float beamHash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float beamNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = beamHash(i);
    float b = beamHash(i + vec2(1.0, 0.0));
    float c = beamHash(i + vec2(0.0, 1.0));
    float d = beamHash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float beamFbm(vec2 p) {
    float v = 0.0;
    float a = 0.55;
    for (int i = 0; i < 3; i++) {
        v += a * beamNoise(p);
        p = p * 2.07 + vec2(17.1, 31.7);
        a *= 0.5;
    }
    return v;
}

void main() {
    vec3 V = normalize(-vViewPos);
    vec3 N = normalize(vNormalView);
    float facing = clamp(abs(dot(N, V)), 0.0, 1.0);

    // Smooth Fresnel edge falloff eliminates polygon silhouette edges completely
    float edgeSoft = pow(facing, 1.45);

    // Seamless radial coordinates around the cone (0..2pi)
    float angle = vUv.x * 6.2831853;
    vec2 radial = vec2(cos(angle), sin(angle));
    float h = clamp(vUv.y, 0.0, 1.0);

    // Animated stage haze / concert smoke drifting upward along the beam
    float t = GameTime * 18.0;
    float haze1 = beamFbm(radial * 2.1 + vec2(h * 5.2 - t * 0.45, t * 0.12));
    float haze2 = beamFbm(radial * 3.8 + vec2(-h * 3.4 - t * 0.25, -t * 0.08));
    float smoke = mix(0.68, 1.28, haze1 * 0.65 + haze2 * 0.35);

    // Subtle internal light streaks / god-rays
    float streaks = 0.85 + 0.22 * sin(angle * 7.0 + t * 0.35) * cos(angle * 3.0 - t * 0.2);

    // Smooth longitudinal fade: soft entry at lens, smooth exponential fade to 0 at tip
    float baseFade = smoothstep(0.0, 0.025, h);
    float topFade = pow(1.0 - h, 1.30);

    // Hot white core near the projector lens & center of the beam
    float lensHotspot = exp(-h * 11.0) * pow(facing, 1.1);
    vec3 beamRgb = mix(vColor.rgb, vec3(1.0, 0.98, 1.0), clamp(lensHotspot * 0.85 + pow(facing, 4.5) * 0.28, 0.0, 0.85));

    float alpha = vColor.a * edgeSoft * baseFade * topFade * smoke * streaks;
    vec4 color = vec4(beamRgb, clamp(alpha, 0.0, 1.0)) * ColorModulator;
    if (color.a < 0.002) {
        discard;
    }
    fragColor = color;
}
