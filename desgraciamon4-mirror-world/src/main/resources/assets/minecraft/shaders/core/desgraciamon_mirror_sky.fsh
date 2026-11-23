#version 150

uniform float Time;
uniform float OvercastDensity;
uniform vec3 SunDir;
uniform vec3 SkyTint;

in vec3 worldDir;
out vec4 fragColor;

// Generador de ruido procedural suave (Value Noise 2D)
float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise2D(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);

    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));

    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// FBM (Fractal Brownian Motion) de 4 octavas
float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    mat2 rot = mat2(0.80, 0.60, -0.60, 0.80);
    for (int i = 0; i < 4; i++) {
        v += a * noise2D(p);
        p = rot * p * 2.02 + vec2(1.4, 2.7);
        a *= 0.5;
    }
    return v;
}

// Campo de estrellas parpadeantes de memoria
float stars(vec3 p) {
    vec3 pStar = p * 42.0;
    vec3 id = floor(pStar);
    vec3 f = fract(pStar) - 0.5;
    float h = hash21(id.xy + id.z * 17.13);
    if (h > 0.955) {
        float dist = length(f);
        float twinkle = 0.65 + 0.35 * sin(Time * 2.5 + h * 30.0);
        return smoothstep(0.075, 0.015, dist) * twinkle * ((h - 0.955) / 0.045);
    }
    return 0.0;
}

void main() {
    vec3 dir = normalize(worldDir);
    float up = dir.y;

    // Paleta base del horizonte etéreo
    vec3 cHorizon = vec3(0.82, 0.80, 0.78) * SkyTint;
    vec3 cZenith  = vec3(0.42, 0.48, 0.62) * SkyTint;
    vec3 cAbyss   = vec3(0.18, 0.16, 0.25) * SkyTint; // Vacío cósmico inferior

    // ----------------------------------------------------
    // 1. HEMISFERIO INFERIOR (Mar de nubes celestiales bajo las islas flotantes)
    // ----------------------------------------------------
    if (up < 0.0) {
        float downDepth = clamp(-up * 2.2, 0.0, 1.0);
        vec2 cloudSeaUV = (dir.xz / max(-up + 0.12, 0.08)) * 0.32;
        vec2 seaWind = vec2(Time * 0.007, Time * 0.004);

        float seaNoise = fbm(cloudSeaUV + seaWind);
        vec3 cSeaClouds = mix(cHorizon * 0.92, vec3(0.68, 0.70, 0.78) * SkyTint, seaNoise);
        cSeaClouds = mix(cSeaClouds, cAbyss, downDepth * 0.75);

        // Estrellas distantes en el abismo
        float starDust = stars(dir);
        cSeaClouds += vec3(0.9, 0.95, 1.1) * starDust * (downDepth * 0.85);

        // Transición suave al horizonte
        float horizBlend = smoothstep(-0.18, 0.0, up);
        vec3 col = mix(cSeaClouds, cHorizon, horizBlend);
        fragColor = vec4(col, 1.0);
        return;
    }

    // ----------------------------------------------------
    // 2. HEMISFERIO SUPERIOR (Cielo Celestial con Auroras y Sol Velado)
    // ----------------------------------------------------
    float skyMask = smoothstep(0.0, 0.25, up);
    vec3 skyBase = mix(cHorizon, cZenith, clamp(up * 1.35, 0.0, 1.0));

    // Estrellas sutiles que asoman en el cenit
    float starField = stars(dir) * smoothstep(0.35, 0.85, up) * (1.0 - OvercastDensity * 0.45);
    skyBase += vec3(1.0, 1.05, 1.15) * starField * 0.85;

    // 3. Auroras Boreales Celestiales (Cintas ondulantes en tonos menta, cian y lavanda)
    if (up > 0.15) {
        float auroraY = (up - 0.15) / 0.85;
        float wave1 = sin(dir.x * 3.5 + Time * 0.25 + dir.z * 2.0);
        float wave2 = cos(dir.z * 4.2 - Time * 0.18 + dir.x * 1.8);
        float auroraMask = smoothstep(0.2, 0.75, auroraY) * smoothstep(0.95, 0.65, auroraY);
        float auroraIntensity = pow(clamp(0.5 + 0.5 * (wave1 * 0.6 + wave2 * 0.4), 0.0, 1.0), 3.0) * auroraMask;

        vec3 cAuroraA = vec3(0.35, 0.92, 0.80); // Menta / Cian celestial
        vec3 cAuroraB = vec3(0.85, 0.55, 0.95); // Lavanda / Orquídea
        vec3 auroraCol = mix(cAuroraA, cAuroraB, clamp(dir.x * 0.5 + 0.5, 0.0, 1.0));

        skyBase += auroraCol * auroraIntensity * 0.42 * (1.1 - OvercastDensity * 0.35);
    }

    // 4. Manto de nubes procedimentales
    vec2 cloudPlane = (dir.xz / max(up + 0.15, 0.07)) * 0.38;
    vec2 wind1 = vec2(Time * 0.009, Time * 0.005);
    vec2 wind2 = vec2(Time * 0.016, -Time * 0.007);

    float baseNoise = fbm(cloudPlane + wind1);
    float detailNoise = fbm(cloudPlane * 2.1 + wind2);
    float cloudNoise = baseNoise * 0.65 + detailNoise * 0.35;

    float coverage = clamp(OvercastDensity * 0.75 + 0.10, 0.50, 0.88);
    float cloudDensity = smoothstep(1.0 - coverage, 1.15 - coverage * 0.35, cloudNoise);

    // 5. Posición del Sol Velado y Rayos Celestiales (God Rays)
    vec3 sDir = normalize(SunDir);
    float sunDot = max(dot(dir, sDir), 0.0);
    float distToSun = acos(clamp(sunDot, -1.0, 1.0));

    vec2 sunLocalUV = (dir.xz - sDir.xz) * 4.2 + vec2(sin(Time * 0.012) * 0.12, cos(Time * 0.010) * 0.10);
    float sunCoverCloud = fbm(sunLocalUV * 1.4 + vec2(0.4, 0.2));
    float sunCloudMask = smoothstep(0.30, 0.62, sunCoverCloud);

    float sunDisk = smoothstep(0.075, 0.020, distToSun);
    float sunHalo = pow(sunDot, 5.0) * 0.90 + pow(sunDot, 18.0) * 1.1;

    float sunOcclusion = mix(0.15, 0.85, sunCloudMask);
    float visibleSun = sunDisk * (1.0 - sunOcclusion * 0.75);
    float visibleHalo = sunHalo * (1.0 - sunCloudMask * 0.50);

    float rimGradient = abs(sunCoverCloud - 0.48);
    float silverLining = smoothstep(0.20, 0.01, rimGradient) * pow(sunDot, 3.5) * 2.4;

    float godRayNoise = noise2D(vec2(atan(dir.z - sDir.z, dir.x - sDir.x) * 5.0, Time * 0.03));
    float godRays = pow(sunDot, 3.2) * godRayNoise * 0.40 * (1.0 - cloudDensity * 0.4);

    // 6. Color e Iluminación de las Nubes
    vec3 cCloudShadow = vec3(0.50, 0.52, 0.62) * SkyTint;
    vec3 cCloudBody   = vec3(0.76, 0.78, 0.84);
    vec3 cCloudLight  = vec3(1.06, 1.02, 0.94); // Luz marfil cálida

    float sunLightFactor = pow(sunDot, 1.8);
    vec3 cloudColor = mix(cCloudShadow, cCloudBody, cloudNoise);
    cloudColor = mix(cloudColor, cCloudLight, sunLightFactor * 0.60 + silverLining * 0.40);
    cloudColor += vec3(1.15, 1.05, 0.88) * silverLining * 0.60;

    // 7. Composición Final del Firmamento
    vec3 finalSky = mix(skyBase, cloudColor, cloudDensity * 0.88);

    vec3 sunColor = vec3(1.30, 1.22, 1.05);
    finalSky += sunColor * visibleSun * 1.5;
    finalSky += vec3(1.10, 1.00, 0.85) * visibleHalo * 0.38;
    finalSky += vec3(1.05, 0.98, 0.82) * godRays;

    // Fusión suave con el horizonte perlado
    finalSky = mix(cHorizon, finalSky, skyMask);

    fragColor = vec4(finalSky, 1.0);
}
