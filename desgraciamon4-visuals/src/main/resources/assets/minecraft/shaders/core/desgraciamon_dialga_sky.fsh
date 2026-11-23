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

// Voronoi cristalino para fracturas de diamante de Dialga
float crystalVoronoi(vec2 p) {
    vec2 n = floor(p);
    vec2 f = fract(p);
    float minDist = 8.0;
    float secondDist = 8.0;

    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 g = vec2(float(i), float(j));
            vec2 o = hash2(n + g);
            vec2 r = g + o - f;
            float d = dot(r, r);
            if (d < minDist) {
                secondDist = minDist;
                minDist = d;
            } else if (d < secondDist) {
                secondDist = d;
            }
        }
    }
    return clamp(sqrt(secondDist) - sqrt(minDist), 0.0, 1.0);
}

// Distancia euclidiana a segmento de línea
float sdLine(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

// Glifos básicos para números romanos (I, V, X)
float glyphI(vec2 p, vec2 o, float h) {
    return sdLine(p, o + vec2(0.0, -h), o + vec2(0.0, h));
}

float glyphV(vec2 p, vec2 o, float w, float h) {
    return min(sdLine(p, o + vec2(-w, h), o + vec2(0.0, -h)),
               sdLine(p, o + vec2(w, h), o + vec2(0.0, -h)));
}

float glyphX(vec2 p, vec2 o, float w, float h) {
    return min(sdLine(p, o + vec2(-w, -h), o + vec2(w, h)),
               sdLine(p, o + vec2(-w, h), o + vec2(w, -h)));
}

// Retorna la distancia mínima a los trazos del número romano según la hora (1 a 12)
float romanNumeralSDF(vec2 q, int hour) {
    float h = 0.015;
    float w = 0.0065;
    float s = 0.0075;

    if (hour == 1) {
        return glyphI(q, vec2(0.0), h);
    } else if (hour == 2) {
        return min(glyphI(q, vec2(-s * 0.65, 0.0), h), glyphI(q, vec2(s * 0.65, 0.0), h));
    } else if (hour == 3) {
        return min(glyphI(q, vec2(-s * 1.3, 0.0), h),
               min(glyphI(q, vec2(0.0), h),
                   glyphI(q, vec2(s * 1.3, 0.0), h)));
    } else if (hour == 4) {
        return min(glyphI(q, vec2(-s * 1.1, 0.0), h),
                   glyphV(q, vec2(s * 0.8, 0.0), w, h));
    } else if (hour == 5) {
        return glyphV(q, vec2(0.0), w, h);
    } else if (hour == 6) {
        return min(glyphV(q, vec2(-s * 0.8, 0.0), w, h),
                   glyphI(q, vec2(s * 1.1, 0.0), h));
    } else if (hour == 7) {
        return min(glyphV(q, vec2(-s * 1.5, 0.0), w, h),
               min(glyphI(q, vec2(0.0), h),
                   glyphI(q, vec2(s * 1.2, 0.0), h)));
    } else if (hour == 8) {
        return min(glyphV(q, vec2(-s * 2.0, 0.0), w, h),
               min(glyphI(q, vec2(-s * 0.6, 0.0), h),
               min(glyphI(q, vec2(s * 0.6, 0.0), h),
                   glyphI(q, vec2(s * 1.8, 0.0), h))));
    } else if (hour == 9) {
        return min(glyphI(q, vec2(-s * 1.1, 0.0), h),
                   glyphX(q, vec2(s * 0.8, 0.0), w, h));
    } else if (hour == 10) {
        return glyphX(q, vec2(0.0), w, h);
    } else if (hour == 11) {
        return min(glyphX(q, vec2(-s * 0.8, 0.0), w, h),
                   glyphI(q, vec2(s * 1.1, 0.0), h));
    } else {
        return min(glyphX(q, vec2(-s * 1.5, 0.0), w, h),
               min(glyphI(q, vec2(0.0, 0.0), h),
                   glyphI(q, vec2(s * 1.2, 0.0), h)));
    }
}

// Evalúa los 12 números romanos en el círculo del reloj astral
float getClockRomanNumerals(vec2 p, float clockRadius) {
    float minDist = 10.0;
    for (int h = 1; h <= 12; h++) {
        float ang = 1.57079632679 - float(h) * 0.52359877559;
        vec2 center = clockRadius * vec2(cos(ang), sin(ang));
        vec2 q = p - center;
        if (length(q) < 0.045) {
            float d = romanNumeralSDF(q, h);
            minDist = min(minDist, d);
        }
    }
    return minDist;
}

// Renderiza una aguja puntiaguda afilada como saeta/lanza
float renderPointyHand(vec2 p, vec2 handDir, float maxR, float baseWidth) {
    float proj = dot(p, handDir);
    if (proj < 0.008 || proj > maxR) return 0.0;
    vec2 perp = p - handDir * proj;
    float distToAxis = length(perp);

    // Anchura decreciente: ancha en la base y remata en punta afilada
    float tFrac = clamp(proj / max(maxR, 0.01), 0.0, 1.0);
    float curWidth = baseWidth * (1.0 - tFrac * 0.90);

    float core = smoothstep(curWidth, curWidth * 0.20, distToAxis);
    float glow = exp(-pow(distToAxis / (curWidth * 2.8), 2.0)) * 0.65;
    return core + glow;
}

// Renderiza un reloj astral secundario flotante de escala reducida (10% a 25% del original)
float renderMiniAstralClock(vec2 p, vec2 center, float scale, float timeOffset) {
    vec2 q = p - center;
    float dist = length(q);
    float maxR = 0.52 * scale;
    if (dist > maxR * 1.30) return 0.0;

    vec2 u = q / scale;
    float ru = length(u);
    float au = atan(u.y, u.x);

    // Anillo exterior con marcas
    float ringOutD = abs(ru - 0.50);
    float ringOut = exp(-pow(ringOutD / 0.007, 2.0)) * (0.45 + 0.55 * abs(sin(au * 12.0)));

    // Anillo interior
    float ringInD = abs(ru - 0.34);
    float ringIn = exp(-pow(ringInD / 0.006, 2.0));

    // Núcleo central
    float miniCore = exp(-pow(ru / 0.055, 2.0)) * 2.2;

    // Agujas puntiagudas en miniatura
    float hAng = 1.5708 - (Time + timeOffset) * 0.20;
    vec2 hDir = vec2(cos(hAng), sin(hAng));
    float hLine = renderPointyHand(u, hDir, 0.30, 0.015);

    float sAng = 1.5708 - (Time * 1.8 + timeOffset * 2.5);
    vec2 sDir = vec2(cos(sAng), sin(sAng));
    float sLine = renderPointyHand(u, sDir, 0.44, 0.009);

    return (ringOut * 1.3 + ringIn * 0.9 + miniCore * 1.4 + hLine * 2.0 + sLine * 2.6);
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    // Color del horizonte (azul cobalto metalico - coincide 100% con FogRendererMixin)
    vec3 cHorizon = vec3(0.020, 0.080, 0.220);

    // Si mira por debajo del horizonte, extender niebla uniforme al suelo sin discontinuidades
    if (y <= 0.0) {
        float smoothProg = prog * prog * (3.0 - 2.0 * prog);
        fragColor = vec4(cHorizon, smoothProg);
        return;
    }

    // Proyeccion esterografica del cielo
    vec2 p = dir.xz / (max(y, 0.0) + 0.30);
    float r = length(p);
    float angle = atan(p.y, p.x);

    // ------------------------------------------------------------------------
    // COREOGRAFÍA TEMPORAL EXACTA (12 SEGUNDOS TOTALES - SIMÉTRICA Y REVERSIBLE):
    // 1. [0.0 - 1.8s]: Aparece el reloj astral en el centro
    // 2. [1.8 - 5.8s]: Las agujas salen de adentro hacia afuera E INMEDIATAMENTE EMPIEZAN A GIRAR
    //                 a gran velocidad continua durante 4 segundos completos (sin tictac previo)
    // 3. [5.8 - 8.8s]: Agujas vuelven a su velocidad normal durante 3 segundos
    // 4. [8.8 - 12.0s]: Empieza todo lo demás (haces diamantinos, fracturas voronoi, polvo estelar,
    //                  y los otros relojes secundarios de 10% a 25% repartidos por el cielo)
    // Al desaparecer: se reproduce exactamente la misma secuencia en reversa.
    // ------------------------------------------------------------------------
    float t = prog * 12.0;

    // Fase 1: Aparición de la esfera del reloj
    float clockFaceAlpha = smoothstep(0.0, 1.8, t);

    // Fase 2: Agujas crecen desde el centro hacia afuera (1.8s a 3.0s)
    float handGrow = smoothstep(1.8, 3.0, t);
    float maxHourR = 0.38 * handGrow;
    float maxSecR  = 0.50 * handGrow;

    // Giro rápido continuo durante 4 segundos exactos (1.8s a 5.8s - arranca directamente con velocidad)
    float spinProg = clamp((t - 1.8) / 4.0, 0.0, 1.0);
    float spinCurve = smoothstep(0.0, 1.0, spinProg);
    // 6 vueltas completas de segundero y 2 vueltas completas de horero (múltiplo exacto de 2*pi)
    float fastSpinSec  = -spinCurve * 37.6991118;
    float fastSpinHour = -spinCurve * 12.5663706;

    // Fase 4: Todo lo demás (haces, fracturas voronoi, polvo estelar, mini-relojes)
    float otherProg = smoothstep(8.8, 12.0, t);

    // 1. Atmósfera temporal profunda: Del azul abisal cenit al azul cobalto horizonte
    vec3 cZenith = vec3(0.006, 0.015, 0.055);
    vec3 skyBase = mix(cHorizon, cZenith, smoothstep(0.0, 0.65, y));

    // Nebulosa temporal 360° en todo el firmamento
    float horizAngle = atan(dir.z, dir.x);
    vec2 tempCoord = vec2(horizAngle * 3.5, y * 5.0) + vec2(Time * 0.02, -Time * 0.015);
    float timeNebula = sin(tempCoord.x * 2.0 + sin(tempCoord.y * 3.0)) * cos(tempCoord.y * 2.5);
    timeNebula = timeNebula * 0.5 + 0.5;

    // Ondas concéntricas de distorsión temporal (Time Shockwaves)
    float timePulse = fract(Time * 0.20);
    float waveDist = exp(-pow(abs(r - timePulse * 2.2) / 0.035, 2.0)) * (1.0 - timePulse);

    // 2. Núcleo Diamantino de Dialga (Cenit)
    float heartBeat = 0.85 + 0.15 * sin(Time * 6.28318);
    float diamondCore = exp(-pow(r / 0.055, 2.0)) * 3.2 * heartBeat;

    // Haz en cruz de diamante del origen temporal (parte de todo lo demás)
    float crossBeams = exp(-pow(abs(p.x) / 0.0030, 1.8)) * exp(-pow(abs(p.y) / 0.60, 2.0))
                     + exp(-pow(abs(p.y) / 0.0030, 1.8)) * exp(-pow(abs(p.x) / 0.60, 2.0));
    vec2 rot45 = vec2(p.x - p.y, p.x + p.y) * 0.7071;
    float diagBeams = (exp(-pow(abs(rot45.x) / 0.0035, 1.8)) + exp(-pow(abs(rot45.y) / 0.0035, 1.8))) * 0.50;

    // 3. RELOJ ASTRAL CENTRAL CON NÚMEROS ROMANOS (I a XII)
    float clockRadius = 0.44;
    float romanDist = getClockRomanNumerals(p, clockRadius);
    float romanStroke = exp(-pow(romanDist / 0.0022, 2.0)) * 2.5;
    float romanGlow   = exp(-pow(romanDist / 0.0090, 1.8)) * 0.8;
    float romanTotal  = romanStroke + romanGlow;

    // Anillo exterior de minutos (r ~ 0.52) con 60 marcas horarias
    float outerRingD = abs(r - 0.52);
    float outerRing = exp(-pow(outerRingD / 0.0035, 2.0));
    float minTicks = abs(sin(angle * 30.0));
    float hourMarks = abs(sin(angle * 6.0));
    outerRing *= (0.40 + 0.60 * smoothstep(0.70, 0.98, max(minTicks, hourMarks)));

    // Anillo interior que bordea los números romanos (r ~ 0.36)
    float innerRingD = abs(r - 0.36);
    float innerRing = exp(-pow(innerRingD / 0.0030, 2.0)) * (0.6 + 0.4 * abs(sin(angle * 12.0)));

    // Anillo de engranaje exterior (r ~ 0.78) girando sutilmente
    float gearRingD = abs(r - 0.78);
    float gearAngle = angle - Time * 0.04;
    float gearTeeth = smoothstep(0.70, 0.95, abs(sin(gearAngle * 48.0)));
    float gearRing = exp(-pow(gearRingD / 0.0045, 2.0)) * (0.45 + 0.55 * gearTeeth);

    // 4. Manecillas Cuánticas Puntiagudas del Tiempo
    // Manecilla de las Horas: Giro normal continuo + fase de giro rápido
    float hourAngle = 1.570796 - Time * 0.15 + fastSpinHour;
    vec2 handHourDir = vec2(cos(hourAngle), sin(hourAngle));
    float handHourLine = handGrow > 0.001
        ? renderPointyHand(p, handHourDir, maxHourR, 0.0075) * 2.2
        : 0.0;

    // Manecilla de los Segundos: Giro continuo rápido durante los 4s, tictac discreto fuera de ellos
    float smoothSecTick = (Time * 6.0) * (3.14159265 / 30.0);
    float discreteSecTick = floor(Time * 6.0) * (3.14159265 / 30.0);
    float spinActive = smoothstep(0.0, 0.10, spinProg) * (1.0 - smoothstep(0.90, 1.0, spinProg));
    float secTick = mix(discreteSecTick, smoothSecTick, spinActive);
    float secAngle = 1.570796 - secTick + fastSpinSec;
    vec2 handSecDir = vec2(cos(secAngle), sin(secAngle));
    float handSecLine = handGrow > 0.001
        ? renderPointyHand(p, handSecDir, maxSecR, 0.0045) * 2.6
        : 0.0;

    // 5. Otros Relojes Secundarios flotando en el cielo (10% a 25% del original)
    float mini1 = renderMiniAstralClock(p, vec2(-0.75,  0.45), 0.22, 1.2);
    float mini2 = renderMiniAstralClock(p, vec2( 0.82,  0.35), 0.18, 3.7);
    float mini3 = renderMiniAstralClock(p, vec2(-0.55, -0.65), 0.15, 5.1);
    float mini4 = renderMiniAstralClock(p, vec2( 0.65, -0.55), 0.24, 2.4);
    float mini5 = renderMiniAstralClock(p, vec2( 0.20,  0.85), 0.12, 7.3);
    float mini6 = renderMiniAstralClock(p, vec2(-0.85, -0.15), 0.20, 4.2);
    float mini7 = renderMiniAstralClock(p, vec2( 0.92, -0.10), 0.11, 6.8);
    float allMiniClocks = (mini1 + mini2 + mini3 + mini4 + mini5 + mini6 + mini7) * otherProg;

    // 6. Malla de Fracturas de Diamante y Polvo Temporal (Parte de todo lo demás)
    vec2 crystalCoord = p * 4.2 + vec2(sin(Time * 0.015), cos(Time * 0.015)) * 0.2;
    float crystalEdge = crystalVoronoi(crystalCoord);
    float crystalGlow = pow(1.0 - crystalEdge, 5.5) * smoothstep(0.12, 0.85, r) * 0.75;

    // Polvo de estrellas congeladas en el tiempo
    vec2 starCell = floor(p * 85.0);
    vec2 starRnd = hash2(starCell);
    float frozenStar = step(0.982, starRnd.x) * smoothstep(0.15, 0.90, r) * (0.6 + 0.4 * starRnd.y);

    // 7. Paleta Dialga
    vec3 cCyanDiamond = vec3(0.05, 0.95, 0.88);
    vec3 cCobaltLight = vec3(0.18, 0.52, 1.10);
    vec3 cPlatinum    = vec3(0.92, 0.98, 1.15);
    vec3 cGoldClock   = vec3(1.10, 0.92, 0.55);

    vec3 romanCol   = mix(cCyanDiamond, cGoldClock, 0.35) * (romanTotal * clockFaceAlpha);
    vec3 ringsCol   = (outerRing * 1.4 + innerRing * 1.1 + gearRing * 0.8) * cCyanDiamond * clockFaceAlpha;
    vec3 coreColor  = (diamondCore * clockFaceAlpha + (crossBeams * 1.6 + diagBeams * 0.7) * otherProg) * cPlatinum;
    vec3 handsColor = (handHourLine * 1.8 + handSecLine * 2.2) * cPlatinum;
    vec3 crystalCol = (crystalGlow * cCobaltLight + frozenStar * cPlatinum * 0.75) * otherProg;
    vec3 miniClocksCol = allMiniClocks * mix(cCyanDiamond, cGoldClock, 0.40);

    vec3 nebCol = timeNebula * cCobaltLight * 0.45;
    vec3 waveCol = waveDist * cCyanDiamond * 1.8;

    vec3 skyFinal = skyBase + nebCol + waveCol + romanCol + ringsCol + coreColor + handsColor + crystalCol + miniClocksCol;

    // Transición suave hacia el horizonte para fundir con la niebla
    float horizonFade = smoothstep(0.0, 0.16, y);
    vec3 finalRGB = mix(cHorizon, skyFinal, horizonFade);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalRGB, smoothProg);
}

