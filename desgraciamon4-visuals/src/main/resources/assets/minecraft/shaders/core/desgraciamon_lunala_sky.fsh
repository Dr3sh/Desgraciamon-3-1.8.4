#version 150

uniform float Time;
uniform float Intensity;
uniform float EventTime;

in vec3 worldDir;
out vec4 fragColor;

// ============================================================================
// LUNALA: THE FULL MOON ECLIPSE - CORE SKY SHADER
// Deep space sensory deprivation, liquid gravitational vortex & crimson eclipse.
// ============================================================================

// --- Generadores de Ruido y Hashing Matemático ---
vec2 hash2(vec2 p) {
    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453123);
}

vec3 hash3(vec3 p) {
    p = vec3(dot(p, vec3(127.1, 311.7, 74.7)),
             dot(p, vec3(269.5, 183.3, 246.1)),
             dot(p, vec3(113.5, 271.9, 124.6)));
    return fract(sin(p) * 43758.5453123);
}

float noise2D(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(dot(hash2(i + vec2(0.0, 0.0)) - 0.5, f - vec2(0.0, 0.0)),
                   dot(hash2(i + vec2(1.0, 0.0)) - 0.5, f - vec2(1.0, 0.0)), u.x),
               mix(dot(hash2(i + vec2(0.0, 1.0)) - 0.5, f - vec2(0.0, 1.0)),
                   dot(hash2(i + vec2(1.0, 1.0)) - 0.5, f - vec2(1.0, 1.0)), u.x), u.y);
}

float fbm6(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    mat2 rot = mat2(0.80, 0.60, -0.60, 0.80);
    for (int i = 0; i < 6; i++) {
        v += a * noise2D(p);
        p = rot * p * 2.04;
        a *= 0.5;
    }
    return v;
}

// Distancia euclidiana a segmento de línea
float sdSegment(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

// Sistema de constelaciones hiper-nítidas y estrellas de cielo profundo sin dispersión
vec3 renderConstellationsAndStars(vec2 p, float time) {
    // 1. Rejilla estelar primaria (estrellas de fondo de diferentes colores y magnitudes)
    vec2 cell = floor(p * 110.0);
    vec2 f = fract(p * 110.0) - 0.5;
    vec2 rnd = hash2(cell);
    
    vec3 starAccum = vec3(0.0);
    if (rnd.x > 0.960) {
        float brightness = pow((rnd.x - 0.960) / 0.040, 2.0);
        float twinkle = 0.75 + 0.25 * sin(time * (3.0 + rnd.y * 5.0) + rnd.y * 62.8);
        float starDist = length(f);
        float starDisc = exp(-pow(starDist / (0.04 + 0.03 * rnd.y), 2.0));
        
        // Colores estelares según clase espectral
        vec3 starCol;
        if (rnd.y > 0.70) starCol = vec3(0.65, 0.85, 1.60); // Gigante azul (Clase O/B)
        else if (rnd.y > 0.35) starCol = vec3(1.30, 1.35, 1.50); // Estrella blanca (Clase A)
        else starCol = vec3(1.40, 0.85, 0.55); // Estrella naranja (Clase K)
        
        starAccum += starDisc * starCol * brightness * twinkle * 2.8;
    }

    // 2. Líneas de constelación geométricas hiper-nítidas (Polyline geometric mesh)
    vec2 constCell = floor(p * 7.0);
    vec2 constFract = fract(p * 7.0) - 0.5;
    vec2 cRnd1 = hash2(constCell);
    vec2 cRnd2 = hash2(constCell + vec2(1.0, 0.0));
    vec2 cRnd3 = hash2(constCell + vec2(0.0, 1.0));
    
    float constLines = 0.0;
    // Segmentos que conectan nodos celestes
    if (cRnd1.x > 0.55) {
        vec2 nodeA = (cRnd1 - 0.5) * 0.7;
        vec2 nodeB = (cRnd2 - 0.5) * 0.7 + vec2(1.0, 0.0);
        vec2 nodeC = (cRnd3 - 0.5) * 0.7 + vec2(0.0, 1.0);
        
        float dSeg1 = sdSegment(constFract, nodeA, nodeB);
        float dSeg2 = sdSegment(constFract, nodeA, nodeC);
        
        float line1 = exp(-pow(dSeg1 / 0.007, 2.0));
        float line2 = exp(-pow(dSeg2 / 0.007, 2.0));
        constLines += (line1 + line2) * 0.45;
        
        // Nodos luminiscentes en los vértices
        float dNodeA = length(constFract - nodeA);
        float nodeGlow = exp(-pow(dNodeA / 0.025, 2.0)) * 1.5;
        constLines += nodeGlow;
    }

    vec3 cCyanConstellation = vec3(0.20, 0.95, 1.25);
    return starAccum + constLines * cCyanConstellation;
}

// Evaluación de Luna Creciente (Fase 2)
float sdCrescentMoon(vec2 p, float r, float offset) {
    float d1 = length(p) - r;
    float d2 = length(p - vec2(offset, 0.0)) - r * 0.96;
    return max(d1, -d2);
}

// Detalle de superficie lunar (cráteres y mares de lava solidificada en relieve)
float lunarSurfaceDetails(vec2 p) {
    vec2 uv = p * 16.0;
    float fbmDetail = fbm6(uv);
    float craters = abs(sin(uv.x * 2.5) * cos(uv.y * 2.5));
    return mix(fbmDetail, craters, 0.35);
}

// Simulación de Lunala Full Moon: silueta de alas en arco con bordes luminosos y tercer ojo
vec3 renderLunalaManifestation(vec2 p, float time) {
    // Escala del cuerpo
    vec2 scaledP = p * 2.2;
    
    // Alas esqueléticas lunares en medialuna ascendente
    float wingArcLeft = abs(length(scaledP - vec2(-0.48, 0.22)) - 0.65);
    float wingArcRight = abs(length(scaledP - vec2(0.48, 0.22)) - 0.65);
    float wingCut = smoothstep(0.45, -0.15, scaledP.y);
    
    float wingBorderLeft = exp(-pow(wingArcLeft / 0.012, 2.0)) * wingCut;
    float wingBorderRight = exp(-pow(wingArcRight / 0.012, 2.0)) * wingCut;
    float wings = wingBorderLeft + wingBorderRight;

    // Cresta dorada lunar superior
    float crestArc = abs(length(scaledP - vec2(0.0, -0.12)) - 0.38);
    float crestCut = smoothstep(-0.15, 0.25, scaledP.y) * smoothstep(0.45, 0.0, abs(scaledP.x));
    float crestBorder = exp(-pow(crestArc / 0.010, 2.0)) * crestCut;

    // Tercer ojo rojo carmesí en el centro exacto
    float thirdEyeDist = length(scaledP - vec2(0.0, 0.08));
    float thirdEyeCore = smoothstep(0.040, 0.010, thirdEyeDist);
    float thirdEyeGlow = exp(-pow(thirdEyeDist / 0.055, 2.0)) * 2.8;

    vec3 cCyanEdge = vec3(0.15, 1.25, 1.45);
    vec3 cGoldCrest = vec3(1.50, 1.20, 0.30);
    vec3 cCrimsonEye = vec3(2.40, 0.15, 0.22);

    return wings * cCyanEdge * 3.0 
         + crestBorder * cGoldCrest * 2.5 
         + (thirdEyeCore * 4.0 + thirdEyeGlow) * cCrimsonEye;
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    // Paleta cromática atmosférica de Lunala: Abismo índigo y eclipse carmesí
    vec3 cVoidIndigo   = vec3(0.002, 0.003, 0.014); // Cero absoluto lumínico
    vec3 cCrimsonBlood = vec3(1.65, 0.12, 0.18);   // Luna roja sangre
    vec3 cGlowCyan     = vec3(0.15, 1.15, 1.35);   // Cian eléctrico puro
    vec3 cGlowMagenta  = vec3(1.45, 0.16, 1.10);   // Magenta luminiscente
    vec3 cStarTurquoise= vec3(0.25, 1.35, 1.25);   // Estrellas fugaces turquesa
    vec3 cUltraDeepSea = vec3(0.08, 0.02, 0.26);   // Nebulosa Ultra Deep Sea

    if (y <= 0.0) {
        float smoothProg = prog * prog * (3.0 - 2.0 * prog);
        fragColor = vec4(cVoidIndigo, smoothProg);
        return;
    }

    vec2 p = dir.xz / (max(y, 0.0) + 0.26);
    float r = length(p);
    float angle = atan(p.y, p.x);
    float horizAngle = atan(dir.z, dir.x);

    // Timeline sincronizado (0.0s – 10.0s)
    float t = (EventTime > 0.0) ? EventTime : (prog * 10.0);

    // Control de Fases:
    float p1Alpha = smoothstep(0.0, 1.5, t);
    float p2Alpha = smoothstep(2.8, 4.2, t) * (1.0 - smoothstep(6.5, 7.2, t));
    
    // Onda expansiva en cascada a los 6.5s
    float shockwaveTime = clamp((t - 6.5) / 2.8, 0.0, 1.0);
    float shockwaveRadius = shockwaveTime * 1.6;
    float shockwaveThickness = 0.045;
    float shockwaveRing = exp(-pow(abs(r - shockwaveRadius) / shockwaveThickness, 2.0)) * (1.0 - shockwaveTime) * step(6.5, t);

    // Fase 3 permanente
    float p3Alpha = smoothstep(6.6, 8.2, t);

    // ========================================================================
    // FASE 1: LIGHT DRAIN & THE VEIL DROP (0.0s – 3.0s)
    // - Caída instantánea a cero absoluto lumínico
    // - Vacío abisal índigo-negro
    // - Constelaciones geométricas hiper-nítidas y estrellas frías sin dispersión
    // ========================================================================
    vec3 celestialStars = renderConstellationsAndStars(p, Time) * p1Alpha;

    // ========================================================================
    // FASE 2: UMBRAL RIFT & COSMIC HORIZON (3.0s – 6.5s)
    // - Vórtice gravitatorio líquido en espiral en el cenit
    // - Anillos luminiscentes magenta y cian enmarcando el vórtice
    // - Luna creciente roja sangre ascendiendo
    // - Polvo cósmico bioluminiscente de Ultra Deep Sea
    // ========================================================================
    // Deformación de curvatura gravitatoria en espiral logarítmica
    float spiralTwist = 1.8 * sin(angle * 3.0 - Time * 1.5) * exp(-r * 3.5);
    vec2 pWarped = p + vec2(cos(angle + spiralTwist), sin(angle + spiralTwist)) * (0.03 * exp(-r * 2.5));
    float warpedR = length(pWarped);

    // Anillos concéntricos luminiscentes de interferencia
    float ringMagenta = exp(-pow(abs(warpedR - 0.36) / 0.008, 2.0)) * 3.2;
    float ringCyan    = exp(-pow(abs(warpedR - 0.42) / 0.007, 2.0)) * 2.8;
    float ringInnerViolet = exp(-pow(abs(warpedR - 0.28) / 0.010, 2.0)) * 2.2;

    // Polvo bioluminiscente de Ultra Deep Sea girando dentro del vórtice
    vec2 seaUV = p * 7.0 + vec2(sin(Time * 0.4), cos(Time * 0.3));
    float seaDustMotes = fbm6(seaUV * 2.2);
    vec2 biolumCell = floor(p * 80.0);
    float biolumPlankton = step(0.970, hash2(biolumCell).x) * (0.6 + 0.4 * sin(Time * 4.0 + hash2(biolumCell).y * 20.0));

    // Luna creciente roja sangre de la Fase 2
    float crescentD = sdCrescentMoon(pWarped, 0.19, 0.075);
    float crescentMoonDisc = smoothstep(0.006, -0.006, crescentD);
    float crescentLimbGlow = exp(-pow(max(crescentD, 0.0) / 0.028, 2.0)) * 2.4;

    // ========================================================================
    // FASE 3: FULL MOON AWAKENING & MANIFESTATION (6.5s – 10.0s+)
    // - Luna Llena Roja colosal radiante (Crimson Full Moon Disc)
    // - Corona doble cian y violeta
    // - Onda expansiva silenciosa refractando el cielo
    // - Lluvia de estrellas fugaces turquesa en cascada
    // - Columna vertical de luz estelar índigo descendiendo al suelo
    // - Manifestación de Lunala: alas lunares, cresta dorada y tercer ojo rojo
    // ========================================================================
    float moonRadius = 0.21;
    float fullMoonD = length(p) - moonRadius;
    float fullMoonDisc = smoothstep(0.006, -0.006, fullMoonD);
    
    // Detalle de la superficie lunar en relieve
    float moonSurface = lunarSurfaceDetails(p) * fullMoonDisc;

    // Resplandor y coronas dobles: Cian interno y Violeta externo
    float moonCyanCorona = exp(-pow(abs(r - moonRadius) / 0.009, 2.0)) * 3.5;
    float moonVioletHalo = exp(-pow(r / (moonRadius * 2.2), 2.0)) * 2.6;

    // Columna vertical descendente de luz índigo (starlight pillar al suelo)
    float starlightPillar = exp(-pow(r / 0.075, 2.0)) * 3.5;

    // Estrellas fugaces turquesa en cascada (desprendidas tras la onda expansiva)
    vec2 shootUV = vec2(horizAngle * 8.0, y * 12.0 - Time * 4.5);
    vec2 shootCell = floor(shootUV);
    vec2 shootRnd = hash2(shootCell);
    float shootStreak = step(0.975, shootRnd.x) * exp(-pow(fract(shootUV.y) / 0.35, 2.0)) * smoothstep(0.05, 0.85, y);

    // Manifestación de Lunala Full Moon (Alas, cresta y tercer ojo)
    vec3 lunalaManifest = renderLunalaManifestation(p, Time);

    // ========================================================================
    // MEZCLA Y COMPOSICIÓN DE CAPAS ATMOSFÉRICAS
    // ========================================================================
    // 1. Fondo base: Cero absoluto de luz índigo profundo
    vec3 baseSky = cVoidIndigo;

    // 2. Estrellas y constelaciones hiper-nítidas Fase 1
    vec3 starsCol = celestialStars;

    // 3. Vórtice gravitatorio, anillos y luna creciente Fase 2
    vec3 ringsCol = (ringMagenta * cGlowMagenta * 1.6 + ringCyan * cGlowCyan * 1.6 + ringInnerViolet * cUltraDeepSea * 2.0) * p2Alpha;
    vec3 seaDustCol = (seaDustMotes * cUltraDeepSea * 1.8 + biolumPlankton * cGlowCyan * 2.0) * p2Alpha;
    vec3 crescentCol = (crescentMoonDisc * cCrimsonBlood * 3.5 + crescentLimbGlow * cCrimsonBlood * 2.0) * p2Alpha;

    // 4. Luna Llena Roja, onda expansiva y Lunala Fase 3
    vec3 moonCol = (fullMoonDisc * cCrimsonBlood * 4.0 
                  - moonSurface * cVoidIndigo * 0.45 
                  + moonCyanCorona * cGlowCyan * 3.0 
                  + moonVioletHalo * cGlowMagenta * 2.2) * p3Alpha;
    vec3 pillarCol = starlightPillar * (cVoidIndigo * 1.5 + cGlowCyan * 2.2) * p3Alpha;
    vec3 shockwaveCol = shockwaveRing * cGlowCyan * 4.0;
    vec3 meteorsCol = shootStreak * cStarTurquoise * 3.5 * p3Alpha;
    vec3 lunalaCol = lunalaManifest * p3Alpha;

    // Composición final acumulativa
    vec3 skyFinal = baseSky 
                  + starsCol 
                  + ringsCol 
                  + seaDustCol 
                  + crescentCol 
                  + moonCol 
                  + pillarCol 
                  + shockwaveCol 
                  + meteorsCol 
                  + lunalaCol;

    // Fundido con el horizonte
    float horizonBlend = smoothstep(0.0, 0.15, y);
    vec3 finalRGB = mix(cVoidIndigo, skyFinal, horizonBlend);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalRGB, smoothProg);
}
