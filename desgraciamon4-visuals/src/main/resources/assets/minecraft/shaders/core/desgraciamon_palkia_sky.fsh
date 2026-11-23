#version 150

uniform float Time;
uniform float Intensity;
uniform float EventTime;

in vec3 worldDir;
out vec4 fragColor;

// ============================================================================
// PALKIA: THE SPATIAL SINGULARITY & SPACIAL REND - 360° COSMIC SPECTACLE
// Relativistic gravitational lensing, dimensional fractures, and pearlescent cosmos.
// ============================================================================

vec2 hash2(vec2 p) {
    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
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

float fbm4(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    mat2 rot = mat2(0.80, 0.60, -0.60, 0.80);
    for (int i = 0; i < 4; i++) {
        v += a * noise2D(p);
        p = rot * p * 2.05;
        a *= 0.5;
    }
    return v;
}

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

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    vec3 cHorizon = vec3(0.045, 0.015, 0.085);
    if (y <= 0.0) {
        float smoothProg = prog * prog * (3.0 - 2.0 * prog);
        fragColor = vec4(cHorizon, smoothProg);
        return;
    }

    vec2 pRaw = dir.xz / (max(y, 0.0) + 0.28);
    float rRaw = length(pRaw);
    float angleRaw = atan(pRaw.y, pRaw.x);
    float horizAngle = atan(dir.z, dir.x);

    float t = (EventTime > 0.0) ? EventTime : (prog * 12.0);

    float gridAlpha   = smoothstep(0.0, 2.5, t);
    float pearlAlpha  = smoothstep(2.0, 5.0, t);
    float rendAlpha   = smoothstep(4.5, 8.0, t);
    float shockAlpha  = smoothstep(7.5, 12.0, t);

    // ========================================================================
    // 1. LENTE GRAVITACIONAL DE EINSTEIN (SCHWARZSCHILD DEFLECTION)
    // Desvía los rayos cósmicos alrededor del pozo gravitatorio de Palkia
    // ========================================================================
    float einsteinRadius = 0.22;
    float deflection = einsteinRadius * einsteinRadius / max(rRaw * rRaw + 0.04, 0.01);
    vec2 pLensed = pRaw + normalize(pRaw + vec2(0.0001)) * (deflection * 0.06);

    // ========================================================================
    // 2. CORTE ESPACIAL (SPACIAL REND) CON DISLOCACIÓN MULTIDIMENSIONAL
    // Los tajos desgarran la cúpula celeste desplazando las coordenadas
    // ========================================================================
    vec2 n1 = vec2(cos(0.61), sin(0.61));
    float dLine1 = dot(pLensed, n1) - 0.08 * sin(Time * 0.85);
    float cut1 = smoothstep(-0.004, 0.004, dLine1) - 0.5;
    float cutLineGlow1 = exp(-pow(dLine1 / 0.007, 2.0)) * 2.8;

    vec2 n2 = vec2(cos(-0.95), sin(-0.95));
    float dLine2 = dot(pLensed, n2) + 0.12 * cos(Time * 0.65);
    float cut2 = smoothstep(-0.004, 0.004, dLine2) - 0.5;
    float cutLineGlow2 = exp(-pow(dLine2 / 0.007, 2.0)) * 2.8;

    vec2 p = pLensed;
    float rendShift = 0.065 * rendAlpha;
    p += vec2(-n1.y, n1.x) * (cut1 * rendShift);
    p += vec2(-n2.y, n2.x) * (cut2 * rendShift);

    float r = length(p);
    float angle = atan(p.y, p.x);

    // ========================================================================
    // 3. NEBULOSA CÓSMICA Y POLVO DE ESTRELLAS 360° (TODO EL CIELO)
    // ========================================================================
    // Capa de nebulosa espiral volumétrica en todo el firmamento
    vec2 nebCoord = vec2(horizAngle * 3.0, y * 6.0) + vec2(Time * 0.03, -Time * 0.02);
    float nebula360_1 = fbm4(nebCoord * 1.5);
    float nebula360_2 = fbm4(p * 3.5 + vec2(Time * 0.05, 0.0));
    float cosmicNebula = (nebula360_1 * 0.6 + nebula360_2 * 0.4);

    // Campo de estrellas de cielo profundo lensed por la gravedad
    vec2 starCell = floor(pLensed * 90.0);
    vec2 starRnd = hash2(starCell);
    float stars = step(0.972, starRnd.x) * (0.65 + 0.35 * sin(Time * 4.0 + starRnd.y * 30.0));

    // ========================================================================
    // 4. MALLA ESPACIO-TEMPORAL 3D Y CURVATURA DE GRAVEDAD
    // ========================================================================
    float latLines = abs(sin(r * 24.0 - Time * 0.5));
    float longLines = abs(sin(angle * 14.0));
    float polarGrid = smoothstep(0.90, 0.99, max(latLines, longLines));

    vec2 cartGrid = abs(fract(p * 9.0) - 0.5);
    float cartLines = smoothstep(0.45, 0.49, max(cartGrid.x, cartGrid.y));
    float spaceGrid = (polarGrid * 0.85 + cartLines * 0.55) * gridAlpha;

    // ========================================================================
    // 5. LA GRAN PERLA LUSTROSA DE PALKIA (ESFERA 3D CON LUZ ESPECULAR)
    // ========================================================================
    float pearlR = 0.16;
    float pearlDist = length(p);
    float pearlBody = smoothstep(pearlR, pearlR - 0.02, pearlDist);

    vec2 pearlNormal2D = p / max(pearlR, 0.001);
    float pearlZ = sqrt(max(0.0, 1.0 - dot(pearlNormal2D, pearlNormal2D)));
    vec3 pearlNorm = normalize(vec3(pearlNormal2D, pearlZ));
    vec3 lightDir = normalize(vec3(0.5, 0.5, 0.7));
    float pearlDiff = max(dot(pearlNorm, lightDir), 0.0);
    float pearlSpec = pow(max(dot(reflect(-lightDir, pearlNorm), vec3(0.0, 0.0, 1.0)), 0.0), 24.0);
    float pearlFresnel = pow(1.0 - max(pearlNorm.z, 0.0), 3.5);

    float pearlGlow = exp(-pow(pearlDist / (pearlR * 2.2), 2.0)) * 2.2;
    float pearlCorePulse = 0.90 + 0.10 * sin(Time * 5.0);

    // ========================================================================
    // 6. HOMBRERAS ANULARES Y GEMAS DE PALKIA
    // ========================================================================
    float ring1 = sdArc(p, 0.32, -1.8, 1.8);
    float ring1Glow = exp(-pow(ring1 / 0.007, 2.0)) * 2.5 + exp(-pow(ring1 / 0.03, 1.5)) * 0.8;

    float ring2 = sdArc(p, 0.42, 1.34, 4.94);
    float ring2Glow = exp(-pow(ring2 / 0.007, 2.0)) * 2.5 + exp(-pow(ring2 / 0.03, 1.5)) * 0.8;

    vec2 gemPos1 = 0.32 * vec2(cos(0.0), sin(0.0));
    vec2 gemPos2 = 0.42 * vec2(cos(3.14159), sin(3.14159));
    float gem1 = exp(-pow(length(p - gemPos1) / 0.024, 2.0)) * 2.8;
    float gem2 = exp(-pow(length(p - gemPos2) / 0.024, 2.0)) * 2.8;

    // ========================================================================
    // 7. ONDAS DE CHOQUE GRAVITACIONALES EXPANSIVAS (360° A TODO EL CIELO)
    // ========================================================================
    float wavePhase1 = fract(Time * 0.22);
    float waveR1 = wavePhase1 * 2.2;
    float wave1 = exp(-pow(abs(r - waveR1) / 0.025, 2.0)) * (1.0 - wavePhase1);

    float wavePhase2 = fract(Time * 0.22 + 0.50);
    float waveR2 = wavePhase2 * 2.2;
    float wave2 = exp(-pow(abs(r - waveR2) / 0.025, 2.0)) * (1.0 - wavePhase2);

    float shockwaves = (wave1 + wave2) * shockAlpha;

    // ========================================================================
    // 8. PALETA CROMÁTICA ICONICA DE PALKIA
    // ========================================================================
    vec3 cDeepCosmos   = vec3(0.015, 0.005, 0.035);
    vec3 cLustrousPink = vec3(1.15, 0.45, 0.90);  // Rosa Perla Lustrosa
    vec3 cMagentaVoid  = vec3(0.85, 0.15, 0.70);  // Magenta Dimensional
    vec3 cPearlWhite   = vec3(1.50, 1.45, 1.65);  // Blanco Nácar Puro
    vec3 cPearlCyan    = vec3(0.35, 0.95, 1.15);  // Iridiscencia Cian
    vec3 cGoldArmor    = vec3(1.25, 1.05, 0.45);  // Anillos de Hombrera

    vec3 skyBase = mix(cHorizon, cDeepCosmos, smoothstep(0.0, 0.70, y));

    // Nebulosa de fondo a 360°
    vec3 nebulaCol = mix(cMagentaVoid * 0.4, cPearlCyan * 0.35, cosmicNebula) * (0.8 + 0.4 * gridAlpha);

    // Malla espacial
    vec3 gridCol = mix(cMagentaVoid, cPearlCyan, 0.4) * (spaceGrid * 1.8);

    // Perla Lustrosa
    vec3 pearlShade = mix(cLustrousPink, cPearlWhite, pearlDiff * 0.7 + pearlSpec * 1.2);
    pearlShade += cPearlCyan * pearlFresnel * 1.1;
    vec3 pearlCol = (pearlBody * pearlShade * pearlCorePulse + pearlGlow * cLustrousPink * 1.5) * pearlAlpha;

    // Hombreras
    vec3 ringsCol = (ring1Glow + ring2Glow) * cGoldArmor * pearlAlpha;
    vec3 gemsCol  = (gem1 + gem2) * cPearlWhite * 2.0 * pearlAlpha;

    // Tajos de Spacial Rend
    vec3 cutsCol = (cutLineGlow1 + cutLineGlow2) * (cPearlWhite * 3.5 + cLustrousPink * 2.2) * rendAlpha;

    // Ondas y estrellas
    vec3 wavesCol = shockwaves * (cLustrousPink * 2.0 + cPearlCyan * 1.5);
    vec3 starCol  = stars * (cPearlWhite * 1.5 + cPearlCyan * 0.8);

    vec3 skyFinal = skyBase + nebulaCol + gridCol + pearlCol + ringsCol + gemsCol + cutsCol + wavesCol + starCol;

    float horizonFade = smoothstep(0.0, 0.15, y);
    vec3 finalRGB = mix(cHorizon, skyFinal, horizonFade);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalRGB, smoothProg);
}
