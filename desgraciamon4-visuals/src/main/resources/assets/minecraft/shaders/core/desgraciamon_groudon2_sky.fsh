#version 150

uniform float Time;
uniform float Intensity;
uniform float EventTime;

in vec3 worldDir;
out vec4 fragColor;

// Hash 2D y 3D rapido
vec2 hash2(vec2 p) {
    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453);
}

// Ruido de gradiente 2D
float noise2D(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(dot(hash2(i + vec2(0.0, 0.0)) - 0.5, f - vec2(0.0, 0.0)),
                   dot(hash2(i + vec2(1.0, 0.0)) - 0.5, f - vec2(1.0, 0.0)), u.x),
               mix(dot(hash2(i + vec2(0.0, 1.0)) - 0.5, f - vec2(0.0, 1.0)),
                   dot(hash2(i + vec2(1.0, 1.0)) - 0.5, f - vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    mat2 rot = mat2(0.80, 0.60, -0.60, 0.80);
    for (int i = 0; i < 5; i++) {
        v += a * noise2D(p);
        p = rot * p * 2.05;
        a *= 0.5;
    }
    return v;
}

float sdSegment(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

float evalOmegaSymbol(vec2 p) {
    vec2 pArch = p - vec2(0.0, 0.028);
    float a = atan(pArch.y, pArch.x);
    float dCircle = abs(length(pArch) - 0.095);

    vec2 p1 = vec2(0.095 * cos(-2.45), 0.028 + 0.095 * sin(-2.45));
    vec2 p2 = vec2(0.095 * cos(-0.69), 0.028 + 0.095 * sin(-0.69));

    float dArch = dCircle;
    if (a >= -2.45 && a <= -0.69) {
        dArch = min(length(p - p1), length(p - p2));
    }

    float dLeg1 = sdSegment(p, p1, vec2(-0.055, -0.082));
    float dLeg2 = sdSegment(p, p2, vec2(0.055, -0.082));
    float dFoot1 = sdSegment(p, vec2(-0.155, -0.082), vec2(-0.055, -0.082));
    float dFoot2 = sdSegment(p, vec2(0.055, -0.082), vec2(0.155, -0.082));

    float tip1 = length(p - vec2(-0.155, -0.082)) - 0.007;
    float tip2 = length(p - vec2(0.155, -0.082)) - 0.007;

    return min(min(dArch, min(min(dLeg1, dLeg2), min(dFoot1, dFoot2))) - 0.0055, min(tip1, tip2));
}

// Dispersión de Mie pesada de aerosol volcánico (Henyey-Greenstein)
float miePhase(float cosTheta, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * 3.14159 * pow(1.0 + g2 - 2.0 * g * cosTheta, 1.5));
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    // Calima densa carbonizada en el horizonte
    vec3 cHorizon = vec3(0.045, 0.012, 0.005);

    if (y <= 0.0) {
        float smoothProg = prog * prog * (3.0 - 2.0 * prog);
        fragColor = vec4(cHorizon, smoothProg);
        return;
    }

    // Coordenadas esterograficas
    vec2 p = dir.xz / (max(y, 0.0) + 0.28);
    float r = length(p);
    float angle = atan(p.y, p.x);
    float horizAngle = atan(dir.z, dir.x);

    float t = prog * 15.0;
    float stormAlpha = smoothstep(0.0, 3.2, t);
    float omegaAlpha = smoothstep(5.0, 9.5, t);

    // ------------------------------------------------------------------------
    // 1. CALOR VOLUMETRICO & MASA OPRESIVA DE CENIZAS PIROCLASTICAS
    // ------------------------------------------------------------------------
    // Deformacion termica del horizonte por ondas de calor de baja frecuencia
    float heatDeform = sin(horizAngle * 6.0 + Time * 3.5) * 0.015 + cos(y * 20.0 + Time * 2.8) * 0.010;
    vec2 pyroUV = vec2(horizAngle * 3.0 + heatDeform, y * 6.0 - Time * 0.25);
    
    float pyroNoise1 = fbm(pyroUV);
    float pyroNoise2 = fbm(pyroUV * 2.2 - vec2(0.0, Time * 0.15));
    float ashDensity = clamp(pyroNoise1 * 0.65 + pyroNoise2 * 0.35 + 0.25, 0.0, 1.0);
    float ashClouds = smoothstep(0.30, 0.85, ashDensity);

    // ------------------------------------------------------------------------
    // 2. EL SOL APOCALIPTICO & DISPERSION DE MIE PESADA
    // ------------------------------------------------------------------------
    // Posicion del sol hinchado en el cenit
    vec3 sunPos = normalize(vec3(0.0, 1.0, 0.0));
    float cosTheta = dot(dir, sunPos);

    // Dispersion de Mie extrema (g = 0.88) a traves del aerosol volcanico
    float mieScatter = miePhase(cosTheta, 0.88);
    float sunDisk = smoothstep(0.992, 0.999, cosTheta);
    float solarAtmosphere = mieScatter * (1.0 - ashDensity * 0.75) * 2.8;

    // Haces de luz solar cortados por columnas de humo (Shafts de calor)
    float sunShaft = abs(sin(angle * 8.0 + Time * 0.40)) * exp(-r * 2.5);

    // ------------------------------------------------------------------------
    // 3. CHISPAS ARDIENTES SUSPENDIDAS & EMISION DE LAVA
    // ------------------------------------------------------------------------
    vec2 sparkCell = floor(vec2(horizAngle * 24.0, (y - Time * 0.30) * 30.0));
    vec2 sparkRnd = hash2(sparkCell);
    float sparkActive = step(0.960, sparkRnd.x);
    float sparkFlicker = sin(Time * 8.0 + sparkRnd.y * 30.0) * 0.5 + 0.5;
    float sparks = sparkActive * sparkFlicker * smoothstep(0.03, 0.88, y);

    // Rios de magma y fracturas en la boveda
    float dMagmaRiver = abs(y - 0.22 - 0.05 * sin(horizAngle * 4.0 + Time * 0.5));
    float magmaRiver = exp(-pow(dMagmaRiver / 0.010, 2.0)) * smoothstep(0.04, 0.45, y);

    // ------------------------------------------------------------------------
    // 4. EL ARO DE FUEGO & SAGRADO SIMBOLO OMEGA
    // ------------------------------------------------------------------------
    float R_RING = 0.38;
    float inEye = 1.0 - smoothstep(R_RING - 0.04, R_RING, r);
    float dRing = abs(r - R_RING);
    float ringCore = exp(-pow(dRing / 0.0085, 2.0)) * 2.8 + exp(-pow(dRing / 0.040, 1.6)) * 1.0;

    float dOmega = evalOmegaSymbol(p);
    float omegaCore = exp(-pow(max(dOmega, 0.0) / 0.0042, 2.0)) * 2.8;
    float omegaMantle = exp(-pow(max(dOmega, 0.0) / 0.024, 1.8)) * 1.5;

    // Pulso solar termonuclear
    float omegaPulse = 0.80 + 0.38 * sin(Time * 2.5) + 0.14 * sin(Time * 5.0);
    float omegaGodrays = abs(sin(angle * 8.0 - Time * 0.60)) * exp(-r * 3.5) * (0.8 + 0.4 * omegaPulse) * inEye;

    // ------------------------------------------------------------------------
    // 5. COLORIMETRIA APOCALIPTICA: BERMELLON, CARBON Y NARANJA INCANDESCENTE
    // ------------------------------------------------------------------------
    vec3 cVermillion  = vec3(0.85, 0.22, 0.04);  // Bermellon volcanico oficial
    vec3 cMoltenOrange= vec3(1.40, 0.55, 0.05);  // Naranja incandescente
    vec3 cCharcoal    = vec3(0.035, 0.012, 0.008); // Negro carbon
    vec3 cSolarGold   = vec3(1.60, 1.20, 0.25);  // Sol hinchado dorado
    vec3 cIncandescent= vec3(1.15, 1.10, 1.02);

    // Fondo base monocromatico de brasas y oleaje piroclástico 360°
    float ashSurge = abs(sin(horizAngle * 6.0 + Time * 0.8 + y * 4.0)) * exp(-abs(y - 0.22) * 5.0);
    vec3 baseSky = mix(cHorizon, cCharcoal, smoothstep(0.0, 0.65, y));

    // Nubes de piroclastos y calima
    vec3 ashCol = mix(cCharcoal * 1.3, cVermillion * 1.2, ashClouds) * stormAlpha
                + ashSurge * cMoltenOrange * 1.8 * stormAlpha;

    // Sol apocalíptico y dispersion de Mie
    vec3 sunCol = (sunDisk * cIncandescent * 3.5 + solarAtmosphere * cSolarGold * 2.0 + sunShaft * cVermillion * 1.5) * stormAlpha;

    // Chispas ardientes y rio de magma
    vec3 sparksCol = sparks * (cSolarGold * 2.8 + cMoltenOrange * 2.0) * stormAlpha;
    vec3 riverCol  = magmaRiver * (cMoltenOrange * 2.5 + cVermillion * 1.8) * stormAlpha;

    // Aro y Simbolo Omega Primal
    vec3 ringCol  = ringCore * (cMoltenOrange * 1.8 + cVermillion * 1.4) * stormAlpha;
    vec3 omegaCol = ((omegaCore * cSolarGold * 3.4 + omegaMantle * cVermillion * 2.2) * omegaPulse + omegaGodrays * cMoltenOrange * 1.3) * omegaAlpha;

    vec3 skyFinal = baseSky + ashCol + sunCol + sparksCol + riverCol + ringCol + omegaCol;

    float horizonFade = smoothstep(0.0, 0.14, y);
    vec3 finalRGB = mix(cHorizon, skyFinal, horizonFade);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalRGB, smoothProg);
}

