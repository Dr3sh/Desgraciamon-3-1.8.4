#version 150

uniform float Time;
uniform float Intensity;
uniform float EventTime;

in vec3 worldDir;
out vec4 fragColor;

// Hash 2D rapido
vec2 hash2(vec2 p) {
    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453);
}

// Ruido suave
float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(dot(hash2(i + vec2(0.0, 0.0)), f - vec2(0.0, 0.0)),
                   dot(hash2(i + vec2(1.0, 0.0)), f - vec2(1.0, 0.0)), u.x),
               mix(dot(hash2(i + vec2(0.0, 1.0)), f - vec2(0.0, 1.0)),
                   dot(hash2(i + vec2(1.0, 1.0)), f - vec2(1.0, 1.0)), u.x), u.y) * 0.5 + 0.5;
}

// FBM turbulento para humo de ceniza volcánica y niebla de muerte
float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    mat2 rot = mat2(0.8, 0.6, -0.6, 0.8);
    for (int i = 0; i < 3; i++) {
        v += noise(p) * a;
        p = rot * p * 2.18 + vec2(2.5, 1.3);
        a *= 0.5;
    }
    return v;
}

// Pulso cardiaco visceral asincrónico (latido de muerte)
float deathHeartbeat(float t) {
    float b1 = pow(clamp(sin(t * 3.4), 0.0, 1.0), 10.0) * 0.75;
    float b2 = pow(clamp(sin(t * 3.4 - 0.38), 0.0, 1.0), 14.0) * 0.55;
    return b1 + b2;
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        fragColor = vec4(0.0);
        return;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    // Paleta Yveltal: Sangre de Obsidiana, Luna de Ruina y Ceniza
    vec3 cHorizonAsh    = vec3(0.080, 0.015, 0.025); // Ceniza sanguinolenta en horizonte
    vec3 cPitchObsidian = vec3(0.005, 0.002, 0.006); // Cielo de obsidiana profundo
    vec3 cCrimsonGlow   = vec3(1.850, 0.040, 0.080); // Plasma carmesí de sangre viva
    vec3 cVioletRot     = vec3(0.450, 0.020, 0.650); // Violeta necrótico
    vec3 cHotWhiteCore  = vec3(1.950, 1.550, 1.650); // Núcleo incandescente de aniquilación

    // Si mira debajo del horizonte, extender niebla uniforme al suelo
    if (y <= 0.0) {
        float smoothProg = prog * prog * (3.0 - 2.0 * prog);
        fragColor = vec4(cHorizonAsh, smoothProg);
        return;
    }

    vec2 p = dir.xz / (max(y, 0.0) + 0.30);
    float r = length(p);
    float angle = atan(p.y, p.x);

    // ========================================================================
    // 1. EL OJO DEL ABISMO: LA LUNA DE RUINA (OBSIDIAN BLOOD MOON)
    // ========================================================================
    // En lugar de una Y convencional, una esfera de obsidiana gigantesca domina el cénit,
    // coronada por una grieta que palpita como una herida abierta en el firmamento
    float beat = deathHeartbeat(Time);
    float moonRadius = 0.28 + 0.025 * beat;
    float moonDist = r - moonRadius;

    // Anillo de fuego de sangre que estrangula a la luna
    float bloodCorona = exp(-pow(abs(moonDist) / (0.045 + 0.030 * beat), 1.8));

    // ========================================================================
    // 2. TENTÁCULOS DE PETRIFICACIÓN / RAÍCES DE SOMBRA (LAS 5 GARRAS DEL ALA)
    // ========================================================================
    // 5 colosales grietas en espiral de sombra y fuego carmesí que se extienden
    // desde la luna hacia el horizonte, simulando las alas y garras de Yveltal envolviendo el mundo
    float minTendonDist = 10.0;
    float tendonPlasma = 0.0;

    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        float baseArmAngle = fi * 1.256637 + sin(Time * 0.10 + fi) * 0.08; // 5 garras principales

        // Espiral logarítmica descendente con ramificaciones irregulares
        float spiralWarp = angle - baseArmAngle - 1.4 * log(r + 0.15) + sin(r * 8.0 - Time * 0.8) * 0.08;
        float armDist = abs(sin(spiralWarp * 0.5)) * r;

        // Dientes y púas a lo largo de cada garra
        float barbFlicker = abs(sin(r * 24.0 + Time * 3.0));
        float armWidth = (0.025 + 0.035 * (1.0 - smoothstep(0.2, 1.4, r))) * (0.8 + 0.4 * barbFlicker);

        float curArmD = abs(armDist - 0.0) / max(armWidth, 0.005);
        minTendonDist = min(minTendonDist, curArmD);

        // Rayos de plasma que corren por las venas de las alas
        float sparkPulse = pow(abs(sin(r * 18.0 - Time * 6.0 + fi * 2.0)), 12.0);
        tendonPlasma += exp(-pow(curArmD, 2.0)) * sparkPulse;
    }

    float tendonCore = exp(-pow(minTendonDist, 1.8));
    float tendonGlow = exp(-pow(minTendonDist / 3.0, 1.5));
    vec3 cTendons = (tendonCore * cHotWhiteCore * 2.5)
                  + (tendonGlow * cCrimsonGlow * 3.2)
                  + (tendonPlasma * cVioletRot * 4.0);

    // ========================================================================
    // 3. FOSO DE CENIZA Y HUMO APOCALÍPTICO ARREMOLINADO
    // ========================================================================
    float ashAngle = angle - Time * 0.15 - 1.2 * log(r + 0.2);
    vec2 ashUV = vec2(ashAngle * 2.0, r * 4.5 + Time * 0.12);
    float ashSmoke = fbm(ashUV);
    float ashMask = smoothstep(0.35, 0.85, ashSmoke) * smoothstep(0.08, 0.85, y);

    vec3 skyBase = mix(cHorizonAsh, cPitchObsidian, smoothstep(0.0, 0.70, y));
    skyBase = mix(skyBase, cVioletRot * 0.25, ashMask * 0.65);

    // ========================================================================
    // 4. GRIETA CENTRAL EN FORMA DE CORAZÓN SANGRANTE
    // ========================================================================
    // Dentro de la luna de obsidiana, una fisura vertical incandescente gotea sangre hacia abajo
    vec2 q = p / moonRadius;
    float slitDist = abs(q.x) - 0.04 * (1.0 - q.y * q.y);
    float slitGlow = exp(-pow(max(0.0, slitDist) / 0.08, 2.0)) * step(length(q), 0.98);
    vec3 cSlit = mix(cCrimsonGlow, cHotWhiteCore, clamp(1.0 - slitDist * 8.0, 0.0, 1.0)) * slitGlow * 3.5 * (1.0 + 0.4 * beat);

    // Disco negro absoluto de la luna de obsidiana
    float moonMask = smoothstep(moonRadius + 0.008, moonRadius - 0.005, r);

    // ========================================================================
    // 5. COMPOSICIÓN FINAL
    // ========================================================================
    vec3 result = skyBase;
    result += cTendons * smoothstep(0.12, 0.95, y);
    result += cCrimsonGlow * bloodCorona * 3.8;

    // La luna eclipsa el fondo excepto por su grieta sangrante
    result = mix(result, cPitchObsidian, moonMask);
    result += cSlit;

    // Transición suave hacia el horizonte de ceniza
    float horizonFade = smoothstep(0.0, 0.16, y);
    vec3 finalRGB = mix(cHorizonAsh, result, horizonFade);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalRGB, smoothProg);
}
