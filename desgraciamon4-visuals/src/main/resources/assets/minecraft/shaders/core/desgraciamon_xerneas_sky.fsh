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

// Distancia euclidiana a segmento de linea
float sdSegment(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

// Retorna la posicion angular de cada una de las 8 gemas míticas de Xerneas en la bóveda celeste
vec2 getXerneasGemPos(int i) {
    float angle = float(i) * (6.2831853 / 8.0) + 0.392699; // 8 gemas repartidas a 45 grados
    float radius = (i % 2 == 0) ? 0.48 : 0.58;             // Alternancia de distancia para formar la corona
    return radius * vec2(cos(angle), sin(angle));
}

// Color canónico de cada una de las 8 gemas de Xerneas
vec3 getXerneasGemColor(int i) {
    if (i == 0) return vec3(0.00, 0.96, 1.00); // 1. Cian Puro
    if (i == 1) return vec3(0.12, 0.45, 1.00); // 2. Azul Cobalto
    if (i == 2) return vec3(0.70, 0.15, 0.98); // 3. Violeta
    if (i == 3) return vec3(1.00, 0.18, 0.75); // 4. Magenta
    if (i == 4) return vec3(1.00, 0.42, 0.10); // 5. Naranja Fuego
    if (i == 5) return vec3(1.00, 0.85, 0.15); // 6. Ámbar Dorado
    if (i == 6) return vec3(0.15, 1.00, 0.55); // 7. Verde Esmeralda
    return vec3(0.98, 0.98, 1.15);             // 8. Diamante Cristal
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    // Color del horizonte (Azul noche mágico profundo con brillo esmeralda/nácar)
    vec3 cHorizon = vec3(0.015, 0.035, 0.085);

    if (y <= 0.0) {
        float smoothProg = prog * prog * (3.0 - 2.0 * prog);
        fragColor = vec4(cHorizon, smoothProg);
        return;
    }

    // Proyeccion esterografica
    vec2 p = dir.xz / (max(y, 0.0) + 0.30);
    float r = length(p);
    float angle = atan(p.y, p.x);

    // ------------------------------------------------------------------------
    // COREOGRAFIA SIMETRICA DE ENTRADA Y SALIDA (12 SEGUNDOS TOTALES):
    // 0.0 - 3.0s: La Gran 'X' de Cristal corta el firmamento cubriendo todo el cielo
    // 3.0 - 6.5s: Brotan las ramas de la Gran Cornamenta (Árbol de la Vida) en 360°
    // 6.5 - 9.5s: Se encienden las 8 Gemas de Geomancy y sus haces de resonancia sagrada
    // 9.5 - 12.0s: Rayos solares crepusculares (godrays), parhelios y lluvia de polen
    // En la salida (Intensity 1.0 -> 0.0): se reproduce exactamente a la inversa.
    // ------------------------------------------------------------------------
    float t = prog * 12.0;
    float xAlpha     = smoothstep(0.0, 3.0, t);
    float treeAlpha  = smoothstep(2.5, 6.5, t);
    float gemsAlpha  = smoothstep(6.0, 9.5, t);
    float sunAlpha   = smoothstep(9.0, 12.0, t);

    // ------------------------------------------------------------------------
    // 1. LA COLOSAL LETRA 'X' DE CRISTAL (CRUZA TODO EL CIELO)
    // ------------------------------------------------------------------------
    // Diagonal 1: (p.x - p.y) = 0
    float diag1Dist = abs(p.x - p.y) * 0.70710678;
    float xBeam1 = exp(-pow(diag1Dist / 0.010, 2.0)) * 2.8 + exp(-pow(diag1Dist / 0.045, 1.8)) * 0.9;

    // Diagonal 2: (p.x + p.y) = 0
    float diag2Dist = abs(p.x + p.y) * 0.70710678;
    float xBeam2 = exp(-pow(diag2Dist / 0.010, 2.0)) * 2.8 + exp(-pow(diag2Dist / 0.045, 1.8)) * 0.9;

    float xBeams = (xBeam1 + xBeam2) * exp(-r * 0.85);

    // Núcleo diamantino en el centro de la 'X'
    float xCore = exp(-pow(r / 0.075, 2.0)) * 3.5 * (0.85 + 0.15 * sin(Time * 3.5));

    // ------------------------------------------------------------------------
    // 2. LA GRAN CORNAMENTA RADIAL (EL ÁRBOL DE LA VIDA EN 360 GRADOS)
    // Ramas de luz cristalina que se extienden hacia las 8 direcciones cardinales
    // ------------------------------------------------------------------------
    float treeDist = 10.0;
    for (int i = 0; i < 8; i++) {
        vec2 gemTarget = getXerneasGemPos(i);
        // Rama desde el tronco central hacia la gema
        treeDist = min(treeDist, sdSegment(p, vec2(0.0, 0.0), gemTarget));

        // Sub-bifurcaciones laterales en cada rama
        vec2 midPt = gemTarget * 0.55;
        vec2 perp = vec2(-gemTarget.y, gemTarget.x) * 0.22;
        treeDist = min(treeDist, sdSegment(p, midPt, midPt + perp));
        treeDist = min(treeDist, sdSegment(p, midPt, midPt - perp * 0.8));
    }

    float treeStroke = exp(-pow(treeDist / 0.0065, 2.0)) * 3.2;
    float treeGlow   = exp(-pow(treeDist / 0.035, 1.8)) * 1.1;
    float treeTotal  = treeStroke + treeGlow;

    // Pulsación de savia de vida recorriendo las ramas
    float sapPulse = 0.80 + 0.20 * sin(r * 22.0 - Time * 4.0);

    // ------------------------------------------------------------------------
    // 3. LAS 8 GEMAS RADIANTES DE XERNEAS (GEOMANCY)
    // ------------------------------------------------------------------------
    vec3 gemsColorAccum = vec3(0.0);
    float gemRadius = 0.035;

    for (int i = 0; i < 8; i++) {
        vec2 gPos = getXerneasGemPos(i);
        vec3 gCol = getXerneasGemColor(i);
        float dGem = length(p - gPos);

        // Núcleo brillante
        float gCore = exp(-pow(dGem / 0.015, 2.0)) * 4.5;
        // Halo de luz
        float gHalo = exp(-pow(dGem / (gemRadius * 2.8), 1.8)) * 1.8;
        // Destello en cruz de 4 puntas
        vec2 dP = abs(p - gPos);
        float gCross = (exp(-pow(dP.x / 0.0025, 2.0)) + exp(-pow(dP.y / 0.0025, 2.0))) * exp(-dGem * 12.0) * 1.5;

        gemsColorAccum += (gCore + gHalo + gCross) * gCol;
    }

    // Haces de resonancia de Geocontrol (conectan las gemas contiguas formando la corona)
    float crownRing = 0.0;
    for (int i = 0; i < 8; i++) {
        vec2 pA = getXerneasGemPos(i);
        vec2 pB = getXerneasGemPos((i + 1) % 8);
        float dSeg = sdSegment(p, pA, pB);
        crownRing += exp(-pow(dSeg / 0.005, 2.0));
    }

    // ------------------------------------------------------------------------
    // 4. RAYOS CREPUSCULARES (GODRAYS), PARHELIOS, AURORA BOREAL DE GEOMANCY Y POLEN
    // ------------------------------------------------------------------------
    // Aurora boreal de Geomancy 360° ondeando por todo el horizonte
    float horizAngle = atan(dir.z, dir.x);
    float auroraHeight = 0.32 + 0.12 * sin(horizAngle * 4.0 + Time * 0.6);
    float auroraBand = exp(-pow((y - auroraHeight) / 0.09, 2.0));
    float auroraCurtain = abs(sin(horizAngle * 16.0 + Time * 1.5)) * auroraBand;
    vec3 cAurora = vec3(0.5 + 0.5 * sin(horizAngle + Time), 0.5 + 0.5 * sin(horizAngle + 2.0 + Time), 0.5 + 0.5 * sin(horizAngle + 4.0 + Time));

    // Rayos solares rotatorios (godrays)
    float godrays = abs(sin(angle * 12.0 + Time * 0.25)) * exp(-r * 1.2);

    // Halo cristalino de Parhelio (Sun Dog) a r ~ 0.52
    float parhelioD = abs(r - 0.52);
    float parhelio = exp(-pow(parhelioD / 0.008, 2.0)) * (0.6 + 0.4 * sin(angle * 8.0 - Time * 0.4));

    // Polen de vida eterna cayendo
    vec2 pollenCell = floor(p * 55.0 + vec2(0.0, Time * 0.7));
    vec2 pollenRnd = hash2(pollenCell);
    float pollen = step(0.975, pollenRnd.x) * smoothstep(0.08, 0.90, r);
    vec3 pollenCol = mix(vec3(1.0, 0.85, 0.2), vec3(0.2, 0.95, 1.0), pollenRnd.y);

    // ------------------------------------------------------------------------
    // 5. PALETA CROMATICA DE XERNEAS & COMPOSICION FINAL
    // ------------------------------------------------------------------------
    vec3 cZenith       = vec3(0.005, 0.010, 0.035); // Azul noche celeste
    vec3 cCrystalWhite = vec3(0.98, 0.98, 1.15);   // Blanco Cristal Puro
    vec3 cAuraGold     = vec3(1.10, 0.92, 0.45);   // Oro Sagrado de la Vida
    vec3 cPrismTeal    = vec3(0.15, 0.90, 0.95);   // Cian Mágico

    vec3 skyBase = mix(cHorizon, cZenith, smoothstep(0.0, 0.70, y));

    // La Gran 'X' de Cristal
    vec3 xCol = (xBeams * cPrismTeal * 1.6 + xCore * cCrystalWhite) * xAlpha;

    // El Árbol de la Vida / Cornamenta
    vec3 treeCol = (treeTotal * sapPulse) * (cCrystalWhite * 1.8 + cAuraGold * 0.8) * treeAlpha;

    // Las 8 Gemas y la Corona de Geomancy
    vec3 gemsCol = (gemsColorAccum + crownRing * cAuraGold * 1.5) * gemsAlpha;

    // Rayos de sol, Parhelio y Polen
    vec3 sunCol = (godrays * cAuraGold * 1.4 + parhelio * cCrystalWhite * 1.8) * sunAlpha;
    vec3 polCol = pollen * pollenCol * 2.2 * sunAlpha;

    vec3 auroraCol = auroraCurtain * cAurora * 2.2 * sunAlpha;
    vec3 skyFinal = skyBase + xCol + treeCol + gemsCol + sunCol + polCol + auroraCol;

    // Fundido con niebla del horizonte
    float horizonFade = smoothstep(0.0, 0.16, y);
    vec3 finalRGB = mix(cHorizon, skyFinal, horizonFade);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalRGB, smoothProg);
}
