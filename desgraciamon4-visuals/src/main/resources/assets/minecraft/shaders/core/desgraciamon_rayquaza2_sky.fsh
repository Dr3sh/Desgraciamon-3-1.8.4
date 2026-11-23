#version 150

uniform sampler2D Sampler0;
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
        p = rot * p * 2.04;
        a *= 0.5;
    }
    return v;
}

float sdSegment(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

float sdEquilateralTriangle(vec2 p, float r) {
    const float k = 1.7320508;
    p.x = abs(p.x) - r;
    p.y = p.y + r / k;
    if (p.x + k * p.y > 0.0) {
        p = vec2(p.x - k * p.y, -k * p.x - p.y) * 0.5;
    }
    p.x -= clamp(p.x, -2.0 * r, 0.0);
    return -length(p) * sign(p.y);
}

float evalDeltaSymbol(vec2 p) {
    vec2 pDelta = p - vec2(0.0, 0.025);
    float dOuterTri = abs(sdEquilateralTriangle(pDelta, 0.155)) - 0.007;
    vec2 pInner = vec2(pDelta.x, -(pDelta.y - 0.015));
    float dInnerTri = abs(sdEquilateralTriangle(pInner, 0.072)) - 0.0055;
    float dChevron1 = sdSegment(pDelta, vec2(-0.15, -0.09), vec2(0.0, -0.14));
    float dChevron2 = sdSegment(pDelta, vec2(0.15, -0.09), vec2(0.0, -0.14));
    float dChevron = min(dChevron1, dChevron2) - 0.004;
    float apexNodule = length(pDelta - vec2(0.0, 0.18)) - 0.012;
    float leftNodule = length(pDelta - vec2(-0.155, -0.09)) - 0.010;
    float rightNodule = length(pDelta - vec2(0.155, -0.09)) - 0.010;
    return min(min(dOuterTri, dInnerTri), min(dChevron, min(apexNodule, min(leftNodule, rightNodule))));
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    // Vientos estratosfericos gris humo en el horizonte
    vec3 cHorizon = vec3(0.025, 0.045, 0.035);

    if (y <= 0.0) {
        float smoothProg = prog * prog * (3.0 - 2.0 * prog);
        fragColor = vec4(cHorizon, smoothProg);
        return;
    }

    vec2 p = dir.xz / (max(y, 0.0) + 0.28);
    float r = length(p);
    float angle = atan(p.y, p.x);

    float t = prog * 15.0;
    float stormAlpha = smoothstep(0.0, 3.2, t);
    float deltaAlpha = smoothstep(3.0, 6.0, t) * (1.0 - smoothstep(9.5, 11.0, t));
    float flash = smoothstep(8.8, 9.8, t) * (1.0 - smoothstep(10.0, 11.2, t));
    float megaAlpha = smoothstep(9.8, 12.0, t);

    // ------------------------------------------------------------------------
    // 1. ROTACION POLAR ESCALONADA POR ALTURA DE CAPA (SKY-HIGH MAELSTROM)
    // ------------------------------------------------------------------------
    // Capa 1: Vientos estratosféricos altos (giro rápido)
    float rotLayer1 = angle - 3.2 * log(max(r, 0.01)) + Time * 0.70;
    vec2 uvLayer1 = vec2(r * 3.5, rotLayer1 * 1.5);
    float cloudsHigh = fbm(uvLayer1);

    // Capa 2: Vientos medios de cizalladura (giro en sentido opuesto con desfase)
    float rotLayer2 = angle - 2.0 * log(max(r, 0.01)) - Time * 0.35;
    vec2 uvLayer2 = vec2(r * 2.8, rotLayer2 * 1.2);
    float cloudsMid = fbm(uvLayer2 + vec2(cloudsHigh * 0.4, 0.0));

    // Capa 3: Embudo del vórtice hacia el centro
    float rotLayer3 = angle - 4.0 * log(max(r, 0.01)) + Time * 1.10;
    vec2 uvLayer3 = vec2(r * 4.2, rotLayer3 * 1.8);
    float cloudsLow = fbm(uvLayer3);

    // Oclusión volumétrica entre capas de viento
    float cloudVolumetric = clamp(cloudsHigh * 0.50 + cloudsMid * 0.35 + cloudsLow * 0.35, 0.0, 1.0);
    float shadowOcclusion = clamp(1.0 - (cloudsHigh - cloudsMid) * 0.65, 0.2, 1.0);

    // ------------------------------------------------------------------------
    // 2. RAYOS ANULARES EN ESPIRAL (VERDE LIMA & AMARILLO IONIZADO)
    // ------------------------------------------------------------------------
    // Arcos eléctricos que viajan en espiral a lo largo de las corrientes del tornado
    float spiralWave = sin(rotLayer1 * 6.0 - Time * 8.0);
    float annularDist = abs(r - (0.42 + 0.12 * spiralWave));
    float annularFlash = smoothstep(0.82, 0.98, sin(Time * 14.0 + angle * 4.0));
    float annularLightning = exp(-pow(annularDist / 0.006, 2.0)) * annularFlash * stormAlpha;

    // Rayos en horquilla radiales entre el vórtice y la tierra
    float lStep = floor(Time * 8.5);
    vec2 lHash = hash2(vec2(lStep, 412.8));
    float lActive = step(0.65, lHash.x);
    vec2 lDir = vec2(cos(lHash.y * 6.28), sin(lHash.y * 6.28));
    float lDist = sdSegment(p, lDir * 0.38, lDir * 1.25 + vec2(sin(p.y * 28.0) * 0.05, 0.0));
    float forkLightning = exp(-pow(lDist / 0.0055, 2.0)) * lActive * stormAlpha;

    // ------------------------------------------------------------------------
    // 3. AURA DE DRAGON CELESTE & GOD RAYS ESMERALDA EN EL EPICENTRO
    // ------------------------------------------------------------------------
    float R_RING = 0.38;
    float inEye = 1.0 - smoothstep(R_RING - 0.04, R_RING, r);
    float dRing = abs(r - R_RING);
    float ringGlow = exp(-pow(dRing / 0.008, 2.0)) * 2.8 + exp(-pow(dRing / 0.038, 1.6)) * 1.1;

    // God Rays esmeralda crepusculares que perforan el centro de la tormenta hacia la tierra
    float godrayPhase = angle * 10.0 - Time * 0.75;
    float godrays = pow(abs(sin(godrayPhase)), 5.0) * exp(-r * 2.8) * inEye;

    // Estrellas profundas en el ojo
    vec2 starCell = floor(p * 110.0);
    vec2 starRnd = hash2(starCell);
    float stars = step(0.978, starRnd.x) * (0.6 + 0.4 * sin(Time * 4.0 + starRnd.y * 20.0)) * inEye;

    // ------------------------------------------------------------------------
    // 4. TRANSICION DELTA -> MEGA-EVOLUCION CON PULSO ORGANICO
    // ------------------------------------------------------------------------
    float dDelta = evalDeltaSymbol(p);
    float deltaCore = exp(-pow(max(dDelta, 0.0) / 0.004, 2.0)) * 2.8;
    float deltaGlow = exp(-pow(max(dDelta, 0.0) / 0.025, 1.8)) * 1.3;
    float deltaPulse = 0.85 + 0.35 * sin(Time * mix(2.5, 14.0, smoothstep(6.5, 9.5, t)));

    // Simbolo de Mega-Evolucion 1:1 con brillo pulsante vivo
    float megaScale = 0.50;
    vec2 qMega = p / megaScale;
    qMega.y += sin(Time * 1.8) * 0.012;
    vec2 megaUV = vec2(qMega.x + 0.5, 0.5 - qMega.y);
    vec4 megaTex = vec4(0.0);
    if (megaUV.x >= 0.0 && megaUV.x <= 1.0 && megaUV.y >= 0.0 && megaUV.y <= 1.0) {
        megaTex = texture(Sampler0, megaUV);
    }

    float megaPulse = 0.78 + 0.38 * sin(Time * 2.6) + 0.14 * sin(Time * 5.2);
    vec3 megaCol = megaTex.rgb * (megaTex.a * (2.2 + 1.4 * megaPulse) * megaAlpha);
    float megaCoreGlow = exp(-pow(r / 0.22, 2.0)) * (0.40 + 0.45 * megaPulse) * megaAlpha;

    // Estallido cegador
    float burstSphere = exp(-pow(r / 0.24, 2.0));
    vec3 flashCol = flash * (burstSphere * vec3(1.2, 1.25, 1.15) * 5.0);

    // ------------------------------------------------------------------------
    // 5. COLORIMETRIA OFICIAL: VERDE JADE, REFLEJOS DORADOS Y GRIS HUMO
    // ------------------------------------------------------------------------
    vec3 cJadeGreen   = vec3(0.08, 0.45, 0.25); // Verde jade esmeralda
    vec3 cGoldenSheen = vec3(1.25, 0.95, 0.22); // Reflejos dorados
    vec3 cSmokeGray   = vec3(0.035, 0.055, 0.045); // Vientos estratosfericos gris humo
    vec3 cLimeLight   = vec3(0.45, 1.25, 0.20); // Rayo anular verde lima
    vec3 cOzoneWhite  = vec3(1.10, 1.15, 1.10);

    // Fondo y nubes estratosféricas a 360°
    float horizAngle = atan(dir.z, dir.x);
    float shearStreamers = abs(sin(horizAngle * 8.0 - Time * 2.0 + y * 6.0)) * exp(-abs(y - 0.28) * 4.0);
    vec3 baseSky = mix(cHorizon, vec3(0.002, 0.006, 0.012), smoothstep(0.0, 0.65, y));
    vec3 cloudsCol = mix(cSmokeGray, cJadeGreen * 1.5, cloudVolumetric) * shadowOcclusion * stormAlpha
                   + shearStreamers * cLimeLight * 1.6 * stormAlpha;

    // Rayos anulares y rayos en horquilla
    vec3 annularCol = annularLightning * (cLimeLight * 3.0 + cGoldenSheen * 2.0);
    vec3 forkCol    = forkLightning * (cOzoneWhite * 3.5 + cLimeLight * 1.5);

    // God Rays esmeralda y Aro del ojo
    vec3 godraysCol = godrays * (cJadeGreen * 2.5 + cGoldenSheen * 1.2) * (0.8 + 0.4 * megaPulse) * stormAlpha;
    vec3 ringCol    = ringGlow * (cGoldenSheen * 1.8 + cJadeGreen * 1.4) * stormAlpha;
    vec3 starsCol   = stars * cOzoneWhite * 2.0 * stormAlpha;

    // Simbolos Delta y Mega-Evolucion
    vec3 deltaCol = (deltaCore * cGoldenSheen * 3.0 + deltaGlow * cJadeGreen * 1.8) * deltaPulse * deltaAlpha;
    vec3 megaSymbolCol = megaCol + (megaCoreGlow * cJadeGreen * 1.8) * megaAlpha;

    vec3 skyFinal = baseSky + cloudsCol + annularCol + forkCol + godraysCol + ringCol + starsCol + deltaCol + flashCol + megaSymbolCol;

    float horizonFade = smoothstep(0.0, 0.14, y);
    vec3 finalRGB = mix(cHorizon, skyFinal, horizonFade);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalRGB, smoothProg);
}

