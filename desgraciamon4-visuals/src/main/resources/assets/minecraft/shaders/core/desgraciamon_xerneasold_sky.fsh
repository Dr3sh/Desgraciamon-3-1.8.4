#version 150

uniform float Time;
uniform float Intensity;
uniform float EventTime;

in vec3 worldDir;
out vec4 fragColor;

// Hash 2D rapido y determinista
vec2 hash2(vec2 p) {
    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453);
}

// Ruido analitico continuo
float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(dot(hash2(i + vec2(0.0, 0.0)), f - vec2(0.0, 0.0)),
                   dot(hash2(i + vec2(1.0, 0.0)), f - vec2(1.0, 0.0)), u.x),
               mix(dot(hash2(i + vec2(0.0, 1.0)), f - vec2(0.0, 1.0)),
                   dot(hash2(i + vec2(1.0, 1.0)), f - vec2(1.0, 1.0)), u.x), u.y) * 0.5 + 0.5;
}

// Voronoi geologico cristalino (facetas de cuarzo y estalagmitas celestes)
vec2 voronoiGeode(vec2 p) {
    vec2 n = floor(p);
    vec2 f = fract(p);
    vec2 mg, mr;
    float md = 8.0;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 g = vec2(float(i), float(j));
            vec2 o = hash2(n + g);
            vec2 r = g + o - f;
            float d = dot(r, r);
            if (d < md) {
                md = d;
                mr = r;
                mg = g;
            }
        }
    }
    return vec2(sqrt(md), hash2(n + mg).x);
}

// Curva espectral de difraccion de 8 gemas de Xerneas
vec3 xerneasPrism(float h) {
    // Espectro de 7 colores pastel iridiscentes
    vec3 c = clamp(abs(fract(h + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    // Realzar pastel nacarado
    return mix(c, vec3(1.0), 0.25);
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        fragColor = vec4(0.0);
        return;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    // Paleta de Xerneas: Crepusculo Etereo & Cristales de Bosque Sagrado
    vec3 cHorizonSage   = vec3(0.025, 0.085, 0.090); // Verde salvia mistico / turquesa suave horizonte
    vec3 cDeepTealSky   = vec3(0.015, 0.045, 0.080); // Azul cerceta profundo
    vec3 cPastelCyan    = vec3(0.350, 0.950, 1.150); // Cian diamantino
    vec3 cRoseMagenta   = vec3(1.100, 0.350, 0.850); // Rosa magenta sagrado
    vec3 cPearlGold     = vec3(1.450, 1.300, 0.750); // Oro feerico
    vec3 cPureWhiteGleam= vec3(1.850, 1.850, 1.950); // Destello puro cristalino

    // Si mira debajo del horizonte, extender niebla uniforme al suelo
    if (y <= 0.0) {
        float smoothProg = prog * prog * (3.0 - 2.0 * prog);
        fragColor = vec4(cHorizonSage, smoothProg);
        return;
    }

    // Coordenadas esterograficas cenitales
    vec2 p = dir.xz / (max(y, 0.0) + 0.32);
    float r = length(p);
    float angle = atan(p.y, p.x);

    // ========================================================================
    // 1. DOMO CELESTE: CRISTAL DE CUARZO FRACTAL Y LUZ BIOLUMINISCENTE
    // ========================================================================
    float skyGradient = smoothstep(0.0, 0.75, y);
    vec3 skyBase = mix(cHorizonSage, cDeepTealSky, skyGradient);

    // Facetas minerales gigantescas en el cielo (cielo abovedado de geoda)
    vec2 geode = voronoiGeode(p * 5.5 + vec2(sin(Time * 0.02), cos(Time * 0.02)));
    float crystalEdge = smoothstep(0.05, 0.005, geode.x);
    float facetShine = 0.65 + 0.35 * sin(geode.y * 25.0 + Time * 0.5);
    vec3 cCrystalVeins = xerneasPrism(geode.y + Time * 0.08) * crystalEdge * facetShine * smoothstep(0.12, 0.85, y);
    skyBase += cCrystalVeins * 1.8;

    // ========================================================================
    // 2. CONSTRUCCIÓN GEOMÉTRICA DE LAS 8 ASTAS ANULARES (LOS 8 CUERNOS DE XERNEAS)
    // ========================================================================
    // En lugar de una X tradicional, 8 cuernos curvos cristalinos nacen radialmente
    // desde un núcleo flotante central, formando una corona de ciervo sagrado
    float minAntlerDist = 10.0;
    float antlerGems = 0.0;
    vec3 antlerColorCycle = vec3(0.0);

    for (int k = 0; k < 8; k++) {
        float fk = float(k);
        float baseAngle = fk * 0.785398 + sin(Time * 0.15) * 0.05; // 8 divisiones de 45 grados
        
        // Trayectoria curva del asta (se abre y luego se bifurca en la punta)
        float curveRadius = 0.25 + 0.45 * sin(Time * 0.2 + fk * 0.6);
        vec2 antlerBase = vec2(cos(baseAngle), sin(baseAngle)) * 0.18;
        vec2 antlerMid  = vec2(cos(baseAngle + 0.18), sin(baseAngle + 0.18)) * 0.55;
        vec2 antlerTip  = vec2(cos(baseAngle + 0.05), sin(baseAngle + 0.05)) * 0.95;

        // Sub-puntas del cuerno
        vec2 subTip1 = antlerTip + vec2(-sin(baseAngle), cos(baseAngle)) * 0.22;
        vec2 subTip2 = antlerTip + vec2( sin(baseAngle), -cos(baseAngle)) * 0.18;

        // Medir distancia euclidiana a segmentos
        vec2 pa = p - antlerBase, ba = antlerMid - antlerBase;
        float h1 = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
        float d1 = length(pa - ba * h1);

        vec2 pb = p - antlerMid, bb = antlerTip - antlerMid;
        float h2 = clamp(dot(pb, bb) / dot(bb, bb), 0.0, 1.0);
        float d2 = length(pb - bb * h2);

        vec2 pc = p - antlerTip, bc = subTip1 - antlerTip;
        float h3 = clamp(dot(pc, bc) / dot(bc, bc), 0.0, 1.0);
        float d3 = length(pc - bc * h3);

        vec2 pd = p - antlerTip, bd = subTip2 - antlerTip;
        float h4 = clamp(dot(pd, bd) / dot(bd, bd), 0.0, 1.0);
        float d4 = length(pd - bd * h4);

        float curDist = min(min(min(d1, d2), d3), d4);
        if (curDist < minAntlerDist) {
            minAntlerDist = curDist;
            antlerColorCycle = xerneasPrism(fk * 0.125 + Time * 0.05);
        }

        // Gema flotante incandescente en la punta de cada asta
        float gemDist = min(length(p - subTip1), length(p - subTip2));
        float gemPulse = pow(abs(sin(Time * 2.5 + fk * 0.8)), 4.0);
        antlerGems += exp(-pow(gemDist / 0.05, 2.0)) * (0.8 + 0.6 * gemPulse);
    }

    // Trazo de los 8 cuernos con núcleo de diamante y aura bioluminiscente
    float antlerCore = exp(-pow(minAntlerDist / 0.0035, 1.8));
    float antlerEdge = exp(-pow(minAntlerDist / 0.0160, 1.8));
    float antlerGlow = exp(-pow(minAntlerDist / 0.0650, 1.5));
    vec3 cAntlerBeams = (antlerCore * 3.8 * cPureWhiteGleam)
                      + (antlerEdge * 2.5 * antlerColorCycle)
                      + (antlerGlow * 1.2 * cPastelCyan);

    // ========================================================================
    // 3. ANILLOS DE DIFRACCIÓN FRESNEL (PRISMA CELESTIAL PALPITANTE)
    // ========================================================================
    // Tres anillos concéntricos irisados en el centro que refractan la luz de vida
    float ring1 = exp(-pow(abs(r - 0.20) / 0.020, 2.0));
    float ring2 = exp(-pow(abs(r - 0.58) / 0.025, 2.0));
    float ring3 = exp(-pow(abs(r - 1.10) / 0.035, 2.0));
    vec3 cFresnelRings = (xerneasPrism(r * 2.0 - Time * 0.1) * ring1
                       + xerneasPrism(r * 1.5 + Time * 0.08) * ring2 * 0.75
                       + xerneasPrism(r * 1.0 - Time * 0.05) * ring3 * 0.50) * 1.8;

    // Núcleo central diamantino (El Corazón de Xerneas / Árbol de la Creación)
    float lifeBreath = 0.85 + 0.15 * sin(Time * 2.4);
    float diamondHeart = exp(-pow(r / (0.10 * lifeBreath), 2.2)) * 3.2;

    // ========================================================================
    // 4. RAYOS CREPUSCULARES EN ABANICO PRISMÁTICO (GEOMANCY)
    // ========================================================================
    float fanAngle = angle * 8.0 + Time * 0.15;
    float godRayFan = pow(noise(vec2(fanAngle, y * 3.5)), 2.6) * smoothstep(0.04, 0.85, y);
    vec3 cGodRayPrism = xerneasPrism(fanAngle * 0.15) * godRayFan * 2.2;

    // Polvo de hadas centelleante en todo el firmamento
    vec2 pollenUV = p * 18.0;
    vec2 pollenCell = floor(pollenUV);
    float pollenHash = hash2(pollenCell).x;
    float pollenSparkle = pow(abs(sin(Time * 3.5 + pollenHash * 40.0)), 12.0) * step(0.70, pollenHash);
    float pollenOrb = exp(-pow(length(fract(pollenUV) - 0.5) / 0.08, 2.0)) * pollenSparkle;

    // ========================================================================
    // 5. COMPOSICIÓN FINAL
    // ========================================================================
    vec3 result = skyBase;
    result += cGodRayPrism;
    result += cFresnelRings;
    result += cAntlerBeams;
    result += xerneasPrism(Time * 0.3) * antlerGems * 3.5;
    result += cPureWhiteGleam * diamondHeart * 2.0;
    result += cPearlGold * pollenOrb * 3.2;

    // Transición suave hacia el horizonte de salvia
    float horizonFade = smoothstep(0.0, 0.16, y);
    vec3 finalRGB = mix(cHorizonSage, result, horizonFade);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalRGB, smoothProg);
}
