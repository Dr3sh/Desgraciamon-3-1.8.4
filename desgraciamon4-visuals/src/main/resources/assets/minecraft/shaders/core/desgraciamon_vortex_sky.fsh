#version 150

uniform float Time;
uniform float Intensity;

in vec3 worldDir;
out vec4 fragColor;

// Hash 2D ultra-rapido sin funciones trigonometricas
vec2 hash2Fast(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return -1.0 + 2.0 * fract((p3.xx + p3.yz) * p3.zy);
}

// Gradient / Perlin Noise 2D
float fastPerlin(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);

    float va = dot(hash2Fast(i + vec2(0.0, 0.0)), f - vec2(0.0, 0.0));
    float vb = dot(hash2Fast(i + vec2(1.0, 0.0)), f - vec2(1.0, 0.0));
    float vc = dot(hash2Fast(i + vec2(0.0, 1.0)), f - vec2(0.0, 1.0));
    float vd = dot(hash2Fast(i + vec2(1.0, 1.0)), f - vec2(1.0, 1.0));

    return mix(mix(va, vb, u.x), mix(vc, vd, u.x), u.y) * 0.5 + 0.5;
}

float billowNoise(vec2 p) {
    float n = fastPerlin(p);
    float ridge = 1.0 - abs(n * 2.0 - 1.0);
    float puff = mix(n, ridge * ridge, 0.58);
    return puff * puff * (3.0 - 2.0 * puff);
}

float cloudFbm4(vec2 p) {
    float total = 0.0;
    float amp = 0.52;
    mat2 rot = mat2(0.80, 0.60, -0.60, 0.80);

    for (int i = 0; i < 4; i++) {
        total += billowNoise(p) * amp;
        p = rot * p * 2.03 + vec2(13.7, 29.3);
        amp *= 0.50;
    }
    return total;
}

float warpFbm2(vec2 p) {
    float v = billowNoise(p) * 0.65;
    p = mat2(0.80, -0.60, 0.60, 0.80) * p * 2.05 + vec2(7.3, 19.1);
    v += billowNoise(p) * 0.35;
    return v;
}

vec2 rotate2D(vec2 v, float a) {
    float s = sin(a);
    float c = cos(a);
    return vec2(c * v.x - s * v.y, s * v.x + c * v.y);
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);

    float denom = max(1.0 + dir.y, 0.14);
    vec2 planePos = dir.xz / denom;
    float r = length(planePos);
    float angle = atan(planePos.y, planePos.x);

    vec2 cloudSpace = normalize(planePos + vec2(0.00001)) * (log(1.0 + r * 2.2) * 1.65);

    float baseRotation = Time * 0.032;
    float spiralTwist = -2.15 * log(r + 0.14) + 1.05 / (r + 0.35);
    vec2 swirledPos = rotate2D(cloudSpace, baseRotation + spiralTwist);

    float slowT = Time * 0.022;
    vec2 warp = vec2(
        warpFbm2(swirledPos * 1.15 + vec2(slowT, -slowT)),
        warpFbm2(swirledPos * 1.15 + vec2(4.7 - slowT, 2.3 + slowT))
    );

    vec2 warpedCoord = swirledPos * 1.45 + (warp - 0.5) * 1.20;
    float baseClouds = cloudFbm4(warpedCoord);
    float detailPuff = billowNoise(warpedCoord * 2.6 - warp * 0.6 + vec2(slowT * 1.3));

    float spiralAngle = angle - 2.35 * log(r + 0.12) + Time * 0.036 + (warp.x - 0.5) * 1.75;
    float majorArms = 0.5 + 0.5 * cos(spiralAngle * 2.0);
    float minorArms = 0.5 + 0.5 * sin(spiralAngle * 4.0 + 1.2);
    float armMask = mix(0.45, 1.0, majorArms * 0.65 + minorArms * 0.35);

    float cloudDensity = clamp((baseClouds * 0.72 + detailPuff * 0.28) * armMask * 1.28, 0.0, 1.0);
    float fluffyBillows = smoothstep(0.18, 0.85, cloudDensity);

    vec2 toCenterDir = normalize(-swirledPos + vec2(0.0001));
    float innerSample = warpFbm2(warpedCoord + toCenterDir * 0.16);
    float cloudRimLight = clamp((innerSample - baseClouds) * 2.5 + 0.48, 0.0, 1.0) * fluffyBillows;

    float crevicePattern = 1.0 - abs(detailPuff * 2.0 - 1.0);
    float lightningFilaments = pow(clamp(crevicePattern * armMask, 0.0, 1.0), 4.2) * smoothstep(0.06, 0.65, r);

    float eyeVoid = smoothstep(0.035, 0.135, r);
    float eyeWallGlow = exp(-pow((r - 0.125) / 0.065, 2.0)) * (0.65 + 0.35 * detailPuff);

    vec3 voidBlack       = vec3(0.010, 0.002, 0.022);
    vec3 darkCloudShadow = vec3(0.085, 0.018, 0.165);
    vec3 midCloudPurple  = vec3(0.280, 0.065, 0.480);
    vec3 litCloudViolet  = vec3(0.560, 0.160, 0.840);
    vec3 glowingMagenta  = vec3(0.880, 0.320, 1.000);

    vec3 col = mix(voidBlack, darkCloudShadow, smoothstep(0.08, 0.45, fluffyBillows));
    col = mix(col, midCloudPurple, smoothstep(0.32, 0.72, fluffyBillows));
    col = mix(col, litCloudViolet, smoothstep(0.45, 0.92, cloudRimLight * fluffyBillows));

    col += glowingMagenta * lightningFilaments * 0.75;
    col += mix(litCloudViolet, glowingMagenta, 0.65) * eyeWallGlow * 0.85;

    vec2 starCell = floor(planePos * 95.0);
    vec2 starHash = hash2Fast(starCell);
    float stars = step(0.988, starHash.x) * (1.0 - fluffyBillows * 0.88) * smoothstep(0.18, 0.85, r);
    col += vec3(0.85, 0.65, 1.0) * stars * 0.50;

    col *= eyeVoid;

    // Transicion limpia y continua con el horizonte y la niebla
    vec3 horizonHaze = vec3(0.09, 0.015, 0.16);
    if (dir.y > 0.0) {
        float horizonFade = smoothstep(0.0, 0.18, dir.y);
        col = mix(horizonHaze, col, horizonFade);
    } else {
        col = horizonHaze; // Extender niebla uniforme por debajo del horizonte
    }

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(col, smoothProg);
}
