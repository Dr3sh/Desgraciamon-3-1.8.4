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

// FBM para oleaje ciclonico y nubes abisales
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

// Distancia euclidiana a segmento de linea
float sdSegment(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

// Distancia a un arco de circunferencia
float sdArc(vec2 p, float r, float startAngle, float endAngle) {
    float a = atan(p.y, p.x);
    float d = abs(length(p) - r);
    float mid = (startAngle + endAngle) * 0.5;
    float halfLen = (endAngle - startAngle) * 0.5;
    float da = abs(mod(a - mid + 3.14159265, 6.2831853) - 3.14159265);
    if (da <= halfLen) return d;
    vec2 p1 = r * vec2(cos(startAngle), sin(startAngle));
    vec2 p2 = r * vec2(cos(endAngle), sin(endAngle));
    return min(length(p - p1), length(p - p2));
}

// Causticas acuaticas abisales
float evalWaterCaustics(vec2 p, float t) {
    vec2 p1 = p * 12.0 + vec2(t * 0.8, t * 0.5);
    vec2 p2 = p * 15.0 - vec2(t * 0.6, -t * 0.7);
    float c1 = sin(p1.x + sin(p1.y * 1.5)) * 0.5 + 0.5;
    float c2 = cos(p2.y + cos(p2.x * 1.3)) * 0.5 + 0.5;
    return pow(c1 * c2, 1.8) * 2.2;
}

// Evaluacion del Simbolo ALPHA de Kyogre Primal
float evalAlphaSymbol(vec2 p) {
    vec2 pLoop = p - vec2(-0.035, 0.0);
    float dLoop = sdArc(pLoop, 0.082, 0.62, 5.66);

    vec2 pt1 = vec2(-0.035 + 0.082 * cos(0.62), 0.082 * sin(0.62));
    vec2 pt2 = vec2(-0.035 + 0.082 * cos(-0.62), 0.082 * sin(-0.62));

    float dTail1 = sdSegment(p, pt1, vec2(0.135, -0.095));
    float dTail2 = sdSegment(p, pt2, vec2(0.135, 0.095));

    float tip1 = length(p - vec2(0.135, -0.095)) - 0.007;
    float tip2 = length(p - vec2(0.135, 0.095)) - 0.007;

    float baseDist = min(dLoop, min(dTail1, dTail2));
    return min(baseDist - 0.0055, min(tip1, tip2));
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    // Color del horizonte (Azul marino abisal profundo de fosa oceanica)
    vec3 cHorizon = vec3(0.005, 0.025, 0.075);

    if (y <= 0.0) {
        float smoothProg = prog * prog * (3.0 - 2.0 * prog);
        fragColor = vec4(cHorizon, smoothProg);
        return;
    }

    // Coordenadas esterograficas del cenit
    vec2 p = dir.xz / (max(y, 0.0) + 0.28);
    float r = length(p);
    float angle = atan(p.y, p.x);

    // Coordenadas esfericas para el horizonte y cielo medio
    float horizAngle = atan(dir.z, dir.x);

    // ------------------------------------------------------------------------
    // CRONOLOGIA DEL DILUVIO PRIMORDIAL (15 SEGUNDOS):
    // 0.0s - 3.5s : El tifon abisal se desata; el mar inunda la boveda celeste
    // 3.5s - 7.5s : El Gran Aro de Presion Marina enciende sus marcas carmesi
    // 7.5s - 11.0s: El sagrado Simbolo ALPHA de Kyogre emerge con pulsos hidro-cineticos
    // 11.0s - 15.0s: Mareas estelares, causticas marinas y rayos cian de la fosa oceanica
    // ------------------------------------------------------------------------
    float t = prog * 15.0;

    float stormAlpha = smoothstep(0.0, 3.2, t);
    float ringAlpha  = smoothstep(2.5, 6.0, t);
    float alphaAlpha = smoothstep(5.5, 9.5, t);
    float oceanAlpha = smoothstep(8.5, 12.0, t);

    // ------------------------------------------------------------------------
    // 1. EL GRAN ARO ABISAL (PRIMAL OCEAN RING)
    // ------------------------------------------------------------------------
    float R_RING = 0.38;
    float dRing = abs(r - R_RING);

    float inSpace = 1.0 - smoothstep(R_RING - 0.04, R_RING, r);
    float inStorm = smoothstep(R_RING - 0.02, R_RING + 0.08, r);

    float ripple = sin(r * 42.0 - Time * 2.8);
    float ringCore = exp(-pow(dRing / 0.008, 2.0)) * 2.8;
    float ringBroad = exp(-pow(dRing / 0.038, 1.6)) * (0.8 + 0.3 * ripple);

    float crimsonStripes = smoothstep(0.78, 0.96, sin(angle * 10.0 - Time * 0.40)) * smoothstep(0.025, 0.0, dRing);
    float droplets = smoothstep(0.88, 0.99, sin(angle * 28.0 + Time * 0.65)) * ringCore;

    float totalRing = (ringCore + ringBroad + droplets * 1.5) * ringAlpha;

    // ------------------------------------------------------------------------
    // 2. EL MAR PROFUNDO GLOBAL (DETALLES Y VIDA EN TODA LA BOVEDA CELESTE)
    // ------------------------------------------------------------------------
    // Espiral de tifon cenital
    float whirlpoolAngle = angle - 2.8 * log(max(r, 0.01)) + Time * 0.55;
    vec2 whirlpoolUV = vec2(r * 3.4, whirlpoolAngle * 1.5);
    
    vec2 waterWarp = vec2(fbm(whirlpoolUV + Time * 0.20), fbm(whirlpoolUV - Time * 0.25));
    float stormDensity = fbm(whirlpoolUV * 1.6 + waterWarp * 2.2) * 0.70 + fbm(whirlpoolUV * 3.2) * 0.30;
    stormDensity = clamp(stormDensity + 0.35, 0.0, 1.0);

    float stormClouds = smoothstep(0.32, 0.88, stormDensity) * inStorm;
    float oceanGlow   = exp(-pow((r - (R_RING + 0.09)) / 0.10, 2.0)) * stormDensity * 1.6;

    // RAYOS DE LUZ SUBACUATICOS (UNDERWATER GODRAYS CRUZANDO EL CIELO MEDIO)
    float godrayAngle = horizAngle * 2.0 + sin(Time * 0.5) * 0.3;
    float godrays = pow(abs(sin(godrayAngle * 3.5 + dir.x * 2.0)), 6.0) * smoothstep(0.08, 0.80, y);
    godrays *= (0.7 + 0.3 * sin(Time * 2.0 + dir.z * 4.0));

    // MAREAS Y CORRIENTES OCEANICAS RODANTES EN EL HORIZONTE
    vec2 oceanHorizUV = vec2(horizAngle * 2.5 + Time * 0.25, y * 6.0 - Time * 0.08);
    float oceanSwells = fbm(oceanHorizUV) * 0.70 + fbm(oceanHorizUV * 2.5) * 0.30;
    float swellMask = smoothstep(0.02, 0.40, y) * (1.0 - smoothstep(0.40, 0.70, y));
    float activeOceanSwells = smoothstep(0.30, 0.80, oceanSwells) * swellMask * stormAlpha;

    // SILUETAS DE LEVIATANES ABISALES DESLIZANDOSE POR LA CORRIENTE
    float leviathanPhase = horizAngle + Time * 0.20;
    float leviathanBody = exp(-pow((y - 0.22 - 0.05 * sin(leviathanPhase * 2.0)) / 0.035, 2.0)) * smoothstep(0.05, 0.50, y);
    float leviathanFins = exp(-pow((y - 0.22 - 0.09 * sin(leviathanPhase * 2.0 + 1.2)) / 0.015, 2.0)) * smoothstep(0.85, 0.98, sin(leviathanPhase * 6.0));
    float leviathanCreature = clamp(leviathanBody + leviathanFins * 0.7, 0.0, 1.0) * stormAlpha;

    // BURBUJAS ABISALES ASCENDENTES POR TODA LA CUPULA
    vec2 bubbleCell = floor(vec2(horizAngle * 18.0, (y + Time * 0.15) * 22.0));
    vec2 bubbleRnd = hash2(bubbleCell);
    float bubbleActive = step(0.965, bubbleRnd.x);
    vec2 bubbleFract = fract(vec2(horizAngle * 18.0, (y + Time * 0.15) * 22.0)) - 0.5;
    float bubbleDist = length(bubbleFract);
    float bubbleRing = exp(-pow(abs(bubbleDist - 0.28) / 0.06, 2.0)) * bubbleActive * smoothstep(0.05, 0.85, y);

    // CAUSTICAS SUBACUATICAS GENERALES EN CIELO MEDIO Y ALTO
    float globalCaustics = evalWaterCaustics(dir.xz * 1.5, Time * 0.6) * smoothstep(0.25, 0.95, y) * 0.45;

    // Cortinas de lluvia torrencial oblicua
    float rainPhase = fract(dir.y * 35.0 + dir.x * 12.0 + Time * 14.0);
    float rainSheets = exp(-pow(abs(rainPhase - 0.5) / 0.15, 2.0)) * smoothstep(0.05, 0.75, y) * inStorm;

    // Relampagos abisales (Cian y Azul zafiro)
    float lStep = floor(Time * 8.0);
    vec2 lHash = hash2(vec2(lStep, 214.3));
    float lActive = step(0.66, lHash.x);
    float lAng = lHash.y * 6.2831853;
    vec2 lDir = vec2(cos(lAng), sin(lAng));
    float lDist = sdSegment(p, lDir * (R_RING + 0.05), lDir * 1.20 + vec2(sin(p.y * 28.0) * 0.06, 0.0));
    float lightning = exp(-pow(lDist / 0.0055, 2.0)) * lActive * inStorm;

    // ------------------------------------------------------------------------
    // 3. EL MAR PRIMORDIAL Y ESPACIO POR DENTRO DEL ARO (r < R_RING)
    // ------------------------------------------------------------------------
    float caustics = evalWaterCaustics(p, Time * 0.75) * inSpace;

    vec2 planktonCell = floor(p * 85.0);
    vec2 planktonRnd = hash2(planktonCell);
    float planktonBlink = sin(Time * 3.0 + planktonRnd.x * 20.0) * 0.5 + 0.5;
    float plankton = step(0.970, planktonRnd.x) * planktonBlink * (0.6 + 0.4 * planktonRnd.y) * inSpace;

    float wavePhase = fract(Time * 0.35);
    float waveDist = abs(r - wavePhase * R_RING);
    float shockwave = exp(-pow(waveDist / 0.012, 2.0)) * (1.0 - wavePhase) * inSpace;

    // ------------------------------------------------------------------------
    // 4. EL SAGRADO SIMBOLO ALPHA CON PULSACION VIVA DE AGUAS PRIMORDIALES
    // ------------------------------------------------------------------------
    float dAlpha = evalAlphaSymbol(p);
    float alphaCore = exp(-pow(max(dAlpha, 0.0) / 0.0042, 2.0)) * 2.8;
    float alphaMantle = exp(-pow(max(dAlpha, 0.0) / 0.022, 1.8)) * 1.4;

    // Pulso cardiaco del mar primigenio palpitante y fluido
    float alphaPulse = 0.80 + 0.35 * sin(Time * 2.5) + 0.12 * sin(Time * 5.0);
    float alphaGodrays = abs(sin(angle * 8.0 - Time * 0.65)) * exp(-r * 3.6) * (0.8 + 0.4 * alphaPulse) * inSpace;

    // ------------------------------------------------------------------------
    // 5. PALETA CROMATICA DE KYOGRE
    // ------------------------------------------------------------------------
    vec3 cAbyssVoid   = vec3(0.002, 0.008, 0.025);
    vec3 cOceanDark   = vec3(0.010, 0.045, 0.140);
    vec3 cOceanMid    = vec3(0.035, 0.160, 0.420);
    vec3 cBioCyan     = vec3(0.08, 0.92, 1.05);
    vec3 cPrimalRed   = vec3(1.30, 0.18, 0.26);
    vec3 cDeepSeaBlue = vec3(0.12, 0.35, 0.95);
    vec3 cPureWhite   = vec3(1.05, 1.12, 1.15);

    // Base del firmamento (degradado abisal)
    vec3 baseSky = mix(cHorizon, cAbyssVoid, smoothstep(0.0, 0.60, y));

    // Nubes del tifon y oleaje del horizonte
    vec3 cloudsCol = mix(cOceanDark, cOceanMid, stormDensity) * stormClouds * stormAlpha;
    vec3 rimCol    = oceanGlow * cBioCyan * 1.5 * stormAlpha;
    vec3 swellsCol = mix(cOceanDark * 1.2, cOceanMid * 0.8, oceanSwells) * activeOceanSwells;

    // Rayos de luz subacuaticos (Godrays) y causticas generales
    vec3 godraysCol  = godrays * cBioCyan * 0.75 * stormAlpha;
    vec3 causticsCol = globalCaustics * cBioCyan * 0.80 * stormAlpha;

    // Leviatanes abisales y burbujas
    vec3 leviathanCol = leviathanCreature * (cOceanDark * 0.5 + cBioCyan * 0.8) * 1.5;
    vec3 bubblesCol   = bubbleRing * (cBioCyan * 1.6 + cPureWhite * 1.2) * stormAlpha;

    // Lluvia torrencial y relampagos
    vec3 rainCol      = rainSheets * cBioCyan * 0.45 * stormAlpha;
    vec3 lightningCol = lightning * (cPureWhite * 2.8 + cBioCyan * 1.6) * stormAlpha;

    // El Aro Abisal y marcas carmesi
    vec3 ringBaseCol = totalRing * (cBioCyan * 1.6 + cDeepSeaBlue * 1.2);
    vec3 ringCrimson = crimsonStripes * cPrimalRed * 2.6 * ringAlpha;
    vec3 ringFinalCol = ringBaseCol + ringCrimson;

    // Interior del aro: causticas, plancton y ondas de choque
    vec3 abyssInteriorCol = (caustics * cBioCyan * 0.85 + plankton * cPureWhite * 2.2 + shockwave * cBioCyan * 1.8) * inSpace * oceanAlpha;

    // Simbolo Alpha Primal con pulso vivo
    vec3 alphaSymbolCol = ((alphaCore * cPrimalRed * 3.2 + alphaMantle * cBioCyan * 1.8) * alphaPulse + alphaGodrays * cBioCyan * 1.2) * alphaAlpha;

    // Composicion final
    vec3 skyFinal = baseSky + cloudsCol + rimCol + swellsCol + godraysCol + causticsCol + leviathanCol + bubblesCol + rainCol + lightningCol + ringFinalCol + abyssInteriorCol + alphaSymbolCol;

    // Fundido con niebla del horizonte
    float horizonFade = smoothstep(0.0, 0.16, y);
    vec3 finalRGB = mix(cHorizon, skyFinal, horizonFade);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalRGB, smoothProg);
}
