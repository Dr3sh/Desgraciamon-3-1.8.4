#version 150

uniform float Time;
uniform float Intensity;
uniform float EventTime;

in vec3 worldDir;
out vec4 fragColor;

// ============================================================================
// SOLGALEO: THE SOLAR CORONA RUPTURE - CORE SKY SHADER
// Multi-layer procedural volumetric skybox pipeline with zero performance compromise.
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

// Voronoi celular para granulación solar (células convectivas de plasma)
vec2 voronoi(vec2 x) {
    vec2 n = floor(x);
    vec2 f = fract(x);
    vec2 mg, mr;
    float md = 8.0;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 g = vec2(float(i), float(j));
            vec2 o = hash2(n + g);
            o = 0.5 + 0.41 * sin(Time * 2.0 + 6.2831 * o);
            vec2 r = g + o - f;
            float d = dot(r, r);
            if (d < md) {
                md = d;
                mr = r;
                mg = g;
            }
        }
    }
    return vec2(sqrt(md), length(mr));
}

// Distancia euclidiana a segmento de línea
float sdSegment(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

// Fractura cristalina angular detallada de Ultra Umbral (Ultra Wormhole Crystalline Fissure)
float crystallineFissureComplex(vec2 p, float openAmount) {
    // Fracturas en zigzag multifrecuencia
    float jag1 = abs(p.x - 0.14 * sin(p.y * 12.0) - 0.07 * cos(p.y * 36.0) - 0.02 * sin(p.y * 72.0));
    float jag2 = abs(p.y - 0.12 * cos(p.x * 10.0) - 0.05 * sin(p.x * 30.0) - 0.02 * cos(p.x * 64.0));
    
    // Ramificaciones diagonales cristalinas secundarias
    vec2 rotP1 = mat2(0.7071, -0.7071, 0.7071, 0.7071) * p;
    float jagBranch1 = abs(rotP1.x - 0.08 * sin(rotP1.y * 20.0)) + 0.15 * length(p);
    float jagBranch2 = abs(rotP1.y - 0.08 * cos(rotP1.x * 20.0)) + 0.18 * length(p);
    
    float dMain = min(jag1, jag2);
    float dFissure = min(dMain, min(jagBranch1, jagBranch2));
    
    float fissureGap = openAmount * (0.055 + 0.035 * fbm6(p * 10.0 + vec2(Time * 0.2)));
    return dFissure - fissureGap;
}

// Prominencias solares y filamentos magnéticos arqueados (Solar Prominences)
float detailedSolarProminences(vec2 p, float t) {
    float r = length(p);
    float angle = atan(p.y, p.x);
    
    float prominencesTotal = 0.0;
    
    // 8 arcos magnéticos principales de plasma solar
    for (int i = 0; i < 8; i++) {
        float baseAngle = float(i) * (6.2831853 / 8.0);
        float archSeed = float(i) * 1.618;
        float dynamicOffset = sin(Time * 2.5 + archSeed * 4.0) * 0.03;
        
        float diffAngle = fract((angle - baseAngle) / 6.2831853 + 0.5) - 0.5;
        float archProfile = cos(diffAngle * 6.2831853 * 2.0);
        
        if (archProfile > 0.0) {
            float targetR = 0.26 + (0.13 + dynamicOffset) * pow(archProfile, 0.6);
            float dArch = abs(r - targetR);
            float filamentNoise = noise2D(vec2(angle * 14.0, r * 40.0 - Time * 4.0));
            float intensity = exp(-pow(dArch / (0.007 + 0.004 * filamentNoise), 2.0)) * archProfile;
            prominencesTotal += intensity;
        }
    }
    return prominencesTotal;
}

// Emblema sagrado de Solgaleo Radiant Sun (Ocho puntas solares + sigilo central)
vec3 renderRadiantSunSigil(vec2 p, float t) {
    float r = length(p);
    float angle = atan(p.y, p.x);
    
    // Anillos solares concentricos
    float ring1 = exp(-pow(abs(r - 0.11) / 0.004, 2.0));
    float ring2 = exp(-pow(abs(r - 0.15) / 0.005, 2.0));
    
    // Rayos principales de 8 puntas de estrella
    float spikes8 = pow(abs(cos(angle * 4.0)), 28.0) * exp(-pow(abs(r - 0.18) / 0.05, 2.0));
    // Rayos secundarios desfasados
    float spikesSec = pow(abs(sin(angle * 4.0)), 36.0) * exp(-pow(abs(r - 0.15) / 0.035, 2.0)) * 0.75;
    
    // Granulación de diamante central
    float centerSigil = smoothstep(0.045, 0.0, r);
    
    vec3 cSigilGold  = vec3(1.8, 1.4, 0.25);
    vec3 cSigilWhite = vec3(2.5, 2.3, 1.9);
    
    return (ring1 + ring2 * 0.8) * cSigilGold * 2.5
         + (spikes8 * 4.0 + spikesSec * 2.5) * cSigilWhite
         + centerSigil * cSigilWhite * 3.5;
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    // Paleta cromática atmosférica de Solgaleo
    vec3 cCopperHorizon = vec3(0.32, 0.13, 0.03);
    vec3 cCopperZenith  = vec3(0.55, 0.26, 0.06);
    vec3 cStarkGold     = vec3(1.65, 1.25, 0.22);
    vec3 cSolarAmber    = vec3(1.45, 0.55, 0.08);
    vec3 cBlindingWhite = vec3(2.20, 2.10, 1.95);
    vec3 cDeepPitchBlack= vec3(0.015, 0.006, 0.002);

    if (y <= 0.0) {
        float smoothProg = prog * prog * (3.0 - 2.0 * prog);
        fragColor = vec4(cCopperHorizon, smoothProg);
        return;
    }

    // Coordenadas polares estereográficas sobre la cúpula
    vec2 p = dir.xz / (max(y, 0.0) + 0.26);
    float r = length(p);
    float angle = atan(p.y, p.x);
    float horizAngle = atan(dir.z, dir.x);

    // Timeline sincronizado (0.0s – 10.0s)
    float t = (EventTime > 0.0) ? EventTime : (prog * 10.0);

    // Control de Fases:
    float p1Alpha = smoothstep(0.0, 1.5, t);
    float p2Alpha = smoothstep(2.8, 4.2, t) * (1.0 - smoothstep(6.5, 7.2, t));
    
    // Destello de sobreexposición blanca a 6.5s (duración exacta 0.4s: 6.45s a 6.85s)
    float flashFrame = smoothstep(6.35, 6.50, t) * (1.0 - smoothstep(6.75, 7.10, t));
    
    // Fase 3 permanente
    float p3Alpha = smoothstep(6.7, 8.2, t);

    // ========================================================================
    // FASE 1: GRAVITATIONAL TENSION & SOLAR WARP (0.0s – 3.0s)
    // - Cielo cobre-ámbar denso
    // - Nubes expulsadas radialmente hacia afuera abriendo un ojo circular
    // - God-ray mask sutil desde el centro del horizonte hacia arriba
    // ========================================================================
    float radialSpeed = 1.0 + t * 1.1;
    vec2 pRadial = p * radialSpeed;
    float cloudNoise1 = fbm6(pRadial * 2.4 + vec2(Time * 0.12, -Time * 0.09));
    float cloudNoise2 = fbm6(pRadial * 4.8 - vec2(Time * 0.06, Time * 0.08));
    float cloudsCombined = cloudNoise1 * 0.65 + cloudNoise2 * 0.35;
    
    // Ojo circular despejado directo en el cenit
    float zenithEye = smoothstep(0.18, 0.52, r * radialSpeed);
    float cloudsPhase1 = smoothstep(0.28, 0.78, cloudsCombined) * zenithEye * p1Alpha;

    // Máscara de rayos crepusculares (god-rays) desde el horizonte hacia arriba
    float godRayFanning = pow(abs(sin(horizAngle * 10.0 + Time * 0.25) * cos(horizAngle * 6.0)), 4.0);
    float godRayElevation = smoothstep(0.02, 0.75, y) * exp(-y * 1.8);
    float godRayMask = godRayFanning * godRayElevation * (1.0 - cloudsPhase1 * 0.5);

    // ========================================================================
    // FASE 2: ULTRA WORMHOLE FRACTURE (3.0s – 6.5s)
    // - Fractura cristalina angular desgarrando el cenit
    // - Textura de Ultraespacio interior: púrpuras profundos, nebulosa dorada y cúmulos estelares
    // - Haces solares concentrados y violentos descendiendo hacia las coordenadas de spawn
    // ========================================================================
    float openAmount = smoothstep(3.0, 5.2, t);
    float dFissure = crystallineFissureComplex(p, openAmount);
    float insideRift = 1.0 - smoothstep(0.0, 0.018, dFissure);
    float riftFacetGlow = exp(-pow(dFissure / 0.008, 2.0)) * 3.2;
    float riftRefraction = noise2D(p * 24.0 + Time * 3.0);

    // Textura procedimental del Ultraespacio en el interior de la fisura
    vec2 ultraCoord = p * 5.0 + vec2(Time * 0.05, Time * 0.03);
    float ultraNebulaCore = fbm6(ultraCoord * 2.0);
    float ultraFilaments  = fbm6(ultraCoord * 6.0 - vec2(Time * 0.1));
    
    // Cúmulos estelares y estrellas titilantes de Ultraespacio
    vec2 starCell = floor(p * 150.0);
    vec2 starRnd = hash2(starCell);
    float starDenseCluster = step(0.965, starRnd.x) * (0.65 + 0.35 * sin(Time * 6.0 + starRnd.y * 35.0));

    vec3 cUltraDeepPurple = vec3(0.08, 0.015, 0.18);
    vec3 cUltraMagentaNeb = vec3(0.65, 0.08, 0.55);
    vec3 cUltraGoldNebula = vec3(1.55, 1.20, 0.28);
    vec3 cUltraSpaceSky = mix(cUltraDeepPurple, cUltraMagentaNeb, ultraNebulaCore)
                        + ultraFilaments * cUltraGoldNebula * 0.85
                        + starDenseCluster * vec3(1.8, 1.8, 2.0);

    // Haces solares volumétricos concentrados (concentrated sunbeams)
    float sunbeamShafts = pow(abs(sin(angle * 14.0 - Time * 0.75) * cos(angle * 8.0)), 6.0) * exp(-r * 2.8);
    float spawnKineticPillar = exp(-pow(r / 0.09, 2.0)) * 4.5;

    // ========================================================================
    // FASE 3: RADIANT SUNBURST & PERMANENT OVERSIZED SOLAR CORONA (6.5s – 10.0s+)
    // - Corona solar permanente colosal
    // - Núcleo incandescente de oro solar con células convectivas de Voronoi
    // - Arcos de prominencias solares magnéticas
    // - Emblema sagrado de Solgaleo Radiant Sun
    // ========================================================================
    float coreRadius = 0.23;
    float coreMask = 1.0 - smoothstep(coreRadius - 0.03, coreRadius, r);
    
    // Células convectivas de plasma solar (Voronoi granulation)
    vec2 solarGran = voronoi(p * 26.0);
    float solarBoilingPlasma = (1.0 - solarGran.x) * coreMask;

    // Resplandor de la corona solar externa
    float coronaGlow = exp(-pow(r / (coreRadius * 2.0), 2.0)) * 3.0;
    
    // Rayos y llamaradas de plasma rotatorias (coronal rays)
    float coronalFlares1 = pow(abs(sin(angle * 12.0 + Time * 0.40)), 4.0) * exp(-r * 2.0);
    float coronalFlares2 = pow(abs(cos(angle * 20.0 - Time * 0.60)), 5.0) * exp(-r * 2.5);
    float totalCoronalFlares = (coronalFlares1 * 1.5 + coronalFlares2) * 1.4;

    // Arcos de prominencias magnéticas solares
    float prominences = detailedSolarProminences(p, Time);

    // Sigilo sagrado Radiant Sun de Solgaleo
    vec3 sigilEmblem = renderRadiantSunSigil(p, Time);

    // ========================================================================
    // MEZCLA Y COMPOSICIÓN DE CAPAS ATMOSFÉRICAS
    // ========================================================================
    // 1. Cielo base ámbar-cobre
    vec3 baseSky = mix(cCopperHorizon, cCopperZenith, smoothstep(0.0, 0.75, y));

    // 2. Nubes Fase 1 con sombras afiladas y god-rays
    vec3 cloudsCol = mix(cDeepPitchBlack, cCopperZenith * 1.5, cloudsPhase1)
                   + godRayMask * cStarkGold * 1.1 * p1Alpha;

    // 3. Fisura y Ultraespacio Fase 2
    vec3 riftInterior = (cUltraSpaceSky + sunbeamShafts * cStarkGold * 2.8) * insideRift * p2Alpha;
    vec3 riftBorder   = riftFacetGlow * (cStarkGold * 2.5 + riftRefraction * cSolarAmber) * p2Alpha;
    vec3 kineticPillarCol = spawnKineticPillar * cBlindingWhite * 4.0 * p2Alpha;

    // 4. Corona solar Fase 3
    vec3 solarCoreCol = (coreMask * cBlindingWhite * 4.0 + solarBoilingPlasma * cStarkGold * 2.5) * p3Alpha;
    vec3 coronaCol    = (coronaGlow * cStarkGold * 2.8 + totalCoronalFlares * cSolarAmber * 2.2) * p3Alpha;
    vec3 prominenceCol = prominences * (cSolarAmber * 3.5 + cStarkGold * 2.2) * p3Alpha;
    vec3 sigilCol      = sigilEmblem * p3Alpha;

    // Composición final acumulativa
    vec3 skyFinal = baseSky 
                  + cloudsCol 
                  + riftInterior 
                  + riftBorder 
                  + kineticPillarCol 
                  + solarCoreCol 
                  + coronaCol 
                  + prominenceCol 
                  + sigilCol;

    // Destello de sobreexposición total blanco (Flash Frame a 6.5s)
    skyFinal = mix(skyFinal, cBlindingWhite * 6.0, flashFrame);

    // Fundido con el horizonte
    float horizonBlend = smoothstep(0.0, 0.16, y);
    vec3 finalRGB = mix(cCopperHorizon, skyFinal, horizonBlend);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalRGB, smoothProg);
}
