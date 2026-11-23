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

// FBM para bandas de corrientes de chorro (Jet Stream)
float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    mat2 rot = mat2(0.8, 0.6, -0.6, 0.8);
    for (int i = 0; i < 3; i++) {
        v += noise(p) * a;
        p = rot * p * 2.15 + vec2(3.1, 1.9);
        a *= 0.5;
    }
    return v;
}

// Retorna la distancia mínima a un segmento
float sdSegment(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        fragColor = vec4(0.0);
        return;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    // Paleta Rayquaza: Estratosfera de Ozono, Esmeralda Dragón y Cintas de Oro
    vec3 cHorizonOzone   = vec3(0.040, 0.140, 0.120); // Horizonte de ozono verde-turquesa
    vec3 cUpperAtmosphere= vec3(0.008, 0.035, 0.065); // Estratosfera superior azul abisal
    vec3 cEmeraldDragon  = vec3(0.120, 1.950, 0.750); // Verde esmeralda hiper-eléctrico
    vec3 cGoldRibbon     = vec3(1.850, 1.450, 0.350); // Oro radiante incandescente
    vec3 cCyanGlow       = vec3(0.200, 1.350, 1.650); // Cian diamantino del ozono
    vec3 cCoreWhite      = vec3(1.950, 1.950, 1.950); // Núcleo estelar

    // Si mira debajo del horizonte, extender niebla uniforme al suelo
    if (y <= 0.0) {
        float smoothProg = prog * prog * (3.0 - 2.0 * prog);
        fragColor = vec4(cHorizonOzone, smoothProg);
        return;
    }

    vec2 p = dir.xz / (max(y, 0.0) + 0.30);
    float r = length(p);
    float angle = atan(p.y, p.x);

    // ========================================================================
    // 1. EL GRAN DRAGÓN EN ÓRBITA: CINTAS ENROSCADAS DE MEGA RAYQUAZA
    // ========================================================================
    // En lugar de anillos concéntricos genéricos, una colosal serpiente de luz
    // dorada y esmeralda serpentea en órbita continua alrededor del cénit
    float serpentBeams = 0.0;
    float serpentGoldFilaments = 0.0;
    float runeNodes = 0.0;

    // 2 Grandes hélices serpenteantes (Cuerpo del Dragón Celeste)
    for (int s = 0; s < 2; s++) {
        float fs = float(s);
        float orbitSpeed = (fs == 0.0) ? 0.65 : -0.55;
        float basePhase = fs * 3.141592;

        // Trayectoria sinusoidal en coordenadas polares
        float serpentRad = 0.52 + 0.22 * sin(angle * 3.0 + Time * orbitSpeed * 2.0 + basePhase);
        float distToBody = abs(r - serpentRad);

        // Escamas de dragón (modulación periódica afilada)
        float scales = smoothstep(0.3, 0.9, abs(sin(angle * 24.0 + Time * 3.0)));
        float bodyWidth = (0.024 + 0.012 * scales);

        float bodyCore = exp(-pow(distToBody / bodyWidth, 1.8));
        serpentBeams += bodyCore;

        // Filamentos de energía que ondean detrás del dragón (los bigotes / filamentos dorados)
        float whiskerWave = sin(angle * 6.0 - Time * 4.0 + fs) * 0.045;
        float distToWhisker = abs(r - (serpentRad + whiskerWave));
        serpentGoldFilaments += exp(-pow(distToWhisker / 0.012, 2.0)) * (0.8 + 0.4 * scales);

        // Nodos rúnicos triangulares y romboidales que marcan el cuerpo
        float runePos = abs(sin(angle * 4.0 + Time * orbitSpeed));
        runeNodes += exp(-pow(distToBody / 0.06, 2.0)) * pow(runePos, 16.0);
    }

    // ========================================================================
    // 2. VÓRTICE DE CORRIENTES EN CHORRO (JET STREAM & HURACÁN DE OZONO)
    // ========================================================================
    float jetAngle = angle - 2.5 * log(r + 0.10) - Time * 0.45;
    vec2 jetUV = vec2(jetAngle * 2.5, r * 5.0 - Time * 0.35);
    float jetClouds = fbm(jetUV);

    // Ojo de la Tormenta despejado en el centro exacto
    float eyeRadius = 0.22;
    float eyeOpening = smoothstep(eyeRadius - 0.08, eyeRadius + 0.15, r);

    vec3 skyBase = mix(cHorizonOzone, cUpperAtmosphere, smoothstep(0.0, 0.70, y));
    skyBase += cCyanGlow * (jetClouds * 0.75) * eyeOpening * smoothstep(0.12, 0.85, y);

    // ========================================================================
    // 3. ESTRELLAS DEL ESPACIO PROFUNDO EN EL OJO DEL VÓRTICE
    // ========================================================================
    vec2 starUV = p * 25.0;
    vec2 starCell = floor(starUV);
    float starHash = hash2(starCell).x;
    float twinkle = pow(abs(sin(Time * 4.0 + starHash * 50.0)), 14.0) * step(0.72, starHash);
    float stars = exp(-pow(length(fract(starUV) - 0.5) / 0.07, 2.0)) * twinkle * (1.0 - eyeOpening * 0.75);

    // Núcleo estelar del ozono en el cénit
    float ozoneZenithEye = exp(-pow(r / 0.11, 2.2)) * 3.5;

    // Rayos de ascensión de viento estratosférico
    float rayRot = angle * 6.0 + Time * 0.20;
    float ascRay = pow(noise(vec2(rayRot, y * 4.0)), 2.6) * smoothstep(0.04, 0.85, y);
    vec3 cAscensionRays = mix(cEmeraldDragon, cGoldRibbon, 0.5 + 0.5 * sin(rayRot)) * ascRay * 2.5;

    // ========================================================================
    // 4. COMPOSICIÓN FINAL
    // ========================================================================
    vec3 result = skyBase;

    // Estrellas profundas en el ojo
    result += cCoreWhite * stars * 3.2;

    // Rayos de viento
    result += cAscensionRays;

    // Cuerpo serpenteante del dragón (esmeralda eléctrica con núcleo puro)
    result += (cEmeraldDragon * serpentBeams * 2.8 + cCoreWhite * serpentBeams * 1.5);

    // Filamentos de oro de Mega Rayquaza
    result += cGoldRibbon * serpentGoldFilaments * 3.2;

    // Nodos rúnicos del cuerpo
    result += cGoldRibbon * runeNodes * 4.5;

    // Ojo central de ozono
    result += (cCyanGlow * 1.8 + cCoreWhite * 1.5) * ozoneZenithEye;

    // Transición suave hacia el horizonte de ozono
    float horizonFade = smoothstep(0.0, 0.16, y);
    vec3 finalRGB = mix(cHorizonOzone, result, horizonFade);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalRGB, smoothProg);
}
