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

// FBM para cenizas volcanicas y piroclastos
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

// Evaluacion del Simbolo OMEGA de Groudon Primal
float evalOmegaSymbol(vec2 p) {
    vec2 pArch = p - vec2(0.0, 0.028);
    float a = atan(pArch.y, pArch.x);
    float dCircle = abs(length(pArch) - 0.095);

    vec2 p1 = vec2(0.095 * cos(-2.45), 0.028 + 0.095 * sin(-2.45));
    vec2 p2 = vec2(0.095 * cos(-0.69), 0.028 + 0.095 * sin(-0.69));

    float dArch = dCircle;
    if (a >= -2.45 && a <= -0.69) {
        dArch = min(length(p - p1), length(p - p2));
    }

    float dLeg1 = sdSegment(p, p1, vec2(-0.055, -0.082));
    float dLeg2 = sdSegment(p, p2, vec2(0.055, -0.082));

    float dFoot1 = sdSegment(p, vec2(-0.155, -0.082), vec2(-0.055, -0.082));
    float dFoot2 = sdSegment(p, vec2(0.055, -0.082), vec2(0.155, -0.082));

    float tip1 = length(p - vec2(-0.155, -0.082)) - 0.007;
    float tip2 = length(p - vec2(0.155, -0.082)) - 0.007;

    float baseDist = min(dArch, min(min(dLeg1, dLeg2), min(dFoot1, dFoot2)));
    return min(baseDist - 0.0055, min(tip1, tip2));
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    // Color del horizonte (Ceniza volcanica y tizón de magma)
    vec3 cHorizon = vec3(0.045, 0.015, 0.008);

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
    // CRONOLOGIA DE LA TIERRA DEL OCASO (DESOLATE LAND - 15 SEGUNDOS):
    // 0.0s - 3.5s : Erupcion de cenizas piroclasticas y fracturas de magma
    // 3.5s - 7.5s : El Gran Aro de Magma Solar incandescente se estabiliza
    // 7.5s - 11.0s: El sagrado Simbolo OMEGA de Groudon emerge ardiendo con pulso solar
    // 11.0s - 15.0s: Placas tectonicas, rayos volcanicos y columnas de humo
    // ------------------------------------------------------------------------
    float t = prog * 15.0;

    float stormAlpha = smoothstep(0.0, 3.2, t);
    float ringAlpha  = smoothstep(2.5, 6.0, t);
    float omegaAlpha = smoothstep(5.5, 9.5, t);
    float magmaAlpha = smoothstep(8.5, 12.0, t);

    // ------------------------------------------------------------------------
    // 1. EL GRAN ARO DE MAGMA SOLAR (PRIMAL MANTLE RING)
    // ------------------------------------------------------------------------
    float R_RING = 0.38;
    float dRing = abs(r - R_RING);

    float inSpace = 1.0 - smoothstep(R_RING - 0.04, R_RING, r);
    float inStorm = smoothstep(R_RING - 0.02, R_RING + 0.08, r);

    float flareNoise = fbm(vec2(angle * 4.0, Time * 0.8)) * 0.025;
    float dRingWarp = abs(r - (R_RING + flareNoise));

    float ringCore = exp(-pow(dRingWarp / 0.0085, 2.0)) * 2.8;
    float ringBroad = exp(-pow(dRing / 0.042, 1.6)) * 1.0;

    float plateDivision = smoothstep(0.08, 0.15, abs(fract(angle * (12.0 / 6.2831853)) - 0.5));
    float magmaSeams = smoothstep(0.88, 0.98, sin(angle * 12.0 - Time * 0.30)) * smoothstep(0.028, 0.0, dRing);

    float prominenceArc1 = sdArc(p, R_RING + 0.05, 0.5, 1.4);
    float prominenceArc2 = sdArc(p, R_RING + 0.06, 3.2, 4.3);
    float prominences = (exp(-pow(prominenceArc1 / 0.007, 2.0)) + exp(-pow(prominenceArc2 / 0.007, 2.0))) * 1.8;

    float totalRing = (ringCore + ringBroad + prominences * 0.8) * ringAlpha;

    // ------------------------------------------------------------------------
    // 2. EL CATACLISMO VOLCANICO GLOBAL (HUMO, CENIZAS Y FRACTURAS EN TODO EL CIELO)
    // ------------------------------------------------------------------------
    // Espiral de ceniza cenital
    float ashAngle = angle - 2.5 * log(max(r, 0.01)) + Time * 0.40;
    vec2 ashUV = vec2(r * 3.2, ashAngle * 1.3);

    vec2 ashWarp = vec2(fbm(ashUV + Time * 0.15), fbm(ashUV - Time * 0.18));
    float ashDensity = fbm(ashUV * 1.7 + ashWarp * 2.0) * 0.70 + fbm(ashUV * 3.4) * 0.30;
    ashDensity = clamp(ashDensity + 0.35, 0.0, 1.0);

    float ashClouds = smoothstep(0.30, 0.85, ashDensity) * inStorm;
    float magmaGlow = exp(-pow((r - (R_RING + 0.09)) / 0.11, 2.0)) * ashDensity * 1.7;

    // COLUMNAS GIGANTES DE HUMO VOLCANICO ASCENDIENDO DESDE EL HORIZONTE
    vec2 smokeUV = vec2(horizAngle * 3.2 + Time * 0.12, y * 5.0 - Time * 0.30);
    float smokeColumns = fbm(smokeUV) * 0.65 + fbm(smokeUV * 2.4 - vec2(0.0, Time * 0.20)) * 0.35;
    float smokeMask = smoothstep(0.02, 0.50, y) * (1.0 - smoothstep(0.50, 0.80, y));
    float activeSmoke = smoothstep(0.32, 0.82, smokeColumns) * smokeMask * stormAlpha;

    // RESPLANDOR Y LLAMARADAS DE CALDERAS EN EL HORIZONTE
    float calderaEruption = sin(horizAngle * 4.0 + sin(Time * 0.4)) * 0.5 + 0.5;
    float calderaGlow = exp(-pow(y / 0.16, 2.0)) * (0.6 + 0.4 * calderaEruption) * stormAlpha;

    // ONDAS DE CHOQUE TERMICAS EN EL HORIZONTE
    float horizWavePhase = fract(Time * 0.22);
    float horizShock = exp(-pow(abs(y - horizWavePhase * 0.45) / 0.025, 2.0)) * (1.0 - horizWavePhase) * stormAlpha;

    // RED RAMIFICADA DE GRIETAS DE MAGMA CRUZANDO EL CIELO MEDIO
    float crackAngle1 = 0.85;
    vec2 crackDir1 = vec2(cos(crackAngle1), sin(crackAngle1));
    float crackNoise1 = sin(p.y * 30.0 + Time * 2.0) * 0.015;
    float dCrack1 = sdSegment(p, crackDir1 * (R_RING + 0.02), crackDir1 * 1.25 + vec2(crackNoise1, 0.0));
    float crack1 = exp(-pow(dCrack1 / 0.0055, 2.0));

    float crackAngle2 = -1.95;
    vec2 crackDir2 = vec2(cos(crackAngle2), sin(crackAngle2));
    float crackNoise2 = cos(p.x * 28.0 - Time * 1.8) * 0.015;
    float dCrack2 = sdSegment(p, crackDir2 * (R_RING + 0.03), crackDir2 * 1.20 + vec2(0.0, crackNoise2));
    float crack2 = exp(-pow(dCrack2 / 0.0055, 2.0));

    // Grieta horizontal en cielo bajo
    float dCrackHoriz = abs(y - 0.20 - 0.04 * sin(horizAngle * 5.0 + Time * 0.8));
    float crackHoriz = exp(-pow(dCrackHoriz / 0.008, 2.0)) * smoothstep(0.05, 0.40, y);

    float cracks = (crack1 + crack2 + crackHoriz * 0.8) * stormAlpha * inStorm;

    // ASCUAS Y CHISPAS VOLCANICAS FLOTANDO POR TODA LA CUPULA
    vec2 skyEmberCell = floor(vec2(horizAngle * 20.0, (y - Time * 0.22) * 25.0));
    vec2 skyEmberRnd = hash2(skyEmberCell);
    float skyEmberActive = step(0.965, skyEmberRnd.x);
    float skyEmberBlink = sin(Time * 6.0 + skyEmberRnd.y * 25.0) * 0.5 + 0.5;
    float skyEmbers = skyEmberActive * skyEmberBlink * smoothstep(0.04, 0.85, y) * stormAlpha;

    // Relampagos volcanicos (Dirty Thunderstorm)
    float lStep = floor(Time * 7.0);
    vec2 lHash = hash2(vec2(lStep, 381.9));
    float lActive = step(0.67, lHash.x);
    float lAng = lHash.y * 6.2831853;
    vec2 lDir = vec2(cos(lAng), sin(lAng));
    float lDist = sdSegment(p, lDir * (R_RING + 0.06), lDir * 1.15 + vec2(sin(p.y * 26.0) * 0.05, 0.0));
    float lightning = exp(-pow(lDist / 0.0055, 2.0)) * lActive * inStorm;

    // ------------------------------------------------------------------------
    // 3. EL NUCLEO INCANDESCENTE Y ESPACIO POR DENTRO DEL ARO (r < R_RING)
    // ------------------------------------------------------------------------
    vec2 convP = p * 10.0 + vec2(sin(Time * 0.6), cos(Time * 0.5));
    float convCells = abs(sin(convP.x) * cos(convP.y));
    float boilingCore = pow(convCells, 2.0) * inSpace;

    vec2 emberCell = floor(p * 80.0 - vec2(0.0, Time * 12.0));
    vec2 emberRnd = hash2(emberCell);
    float emberBlink = sin(Time * 5.0 + emberRnd.x * 30.0) * 0.5 + 0.5;
    float embers = step(0.972, emberRnd.x) * emberBlink * (0.6 + 0.4 * emberRnd.y) * inSpace;

    float heatPhase = fract(Time * 0.32);
    float heatDist = abs(r - heatPhase * R_RING);
    float heatwave = exp(-pow(heatDist / 0.014, 2.0)) * (1.0 - heatPhase) * inSpace;

    // ------------------------------------------------------------------------
    // 4. EL SAGRADO SIMBOLO OMEGA CON PULSACION VIVA TERMONUCLEAR
    // ------------------------------------------------------------------------
    float dOmega = evalOmegaSymbol(p);
    float omegaCore = exp(-pow(max(dOmega, 0.0) / 0.0042, 2.0)) * 2.8;
    float omegaMantle = exp(-pow(max(dOmega, 0.0) / 0.024, 1.8)) * 1.5;

    // Pulso volcanico termonuclear palpitante y vivo
    float omegaPulse = 0.80 + 0.38 * sin(Time * 2.5) + 0.14 * sin(Time * 5.0);
    float omegaGodrays = abs(sin(angle * 8.0 - Time * 0.60)) * exp(-r * 3.5) * (0.8 + 0.4 * omegaPulse) * inSpace;

    // ------------------------------------------------------------------------
    // 5. PALETA CROMATICA DE GROUDON
    // ------------------------------------------------------------------------
    vec3 cFurnaceVoid = vec3(0.025, 0.008, 0.005);
    vec3 cAshDark     = vec3(0.035, 0.015, 0.010);
    vec3 cAshLight    = vec3(0.240, 0.080, 0.025);
    vec3 cMagmaCrimson= vec3(1.30, 0.16, 0.04);
    vec3 cMoltenOrange= vec3(1.40, 0.55, 0.05);
    vec3 cSolarGold   = vec3(1.50, 1.15, 0.22);
    vec3 cPureWhite   = vec3(1.15, 1.10, 1.02);

    // Base del firmamento
    vec3 baseSky = mix(cHorizon, cFurnaceVoid, smoothstep(0.0, 0.60, y));

    // Nubes del torbellino de cenizas y columnas de humo rodante
    vec3 cloudsCol = mix(cAshDark, cAshLight, ashDensity) * ashClouds * stormAlpha;
    vec3 rimCol    = magmaGlow * cMoltenOrange * 1.5 * stormAlpha;
    vec3 smokeCol  = mix(cAshDark * 1.4, cMoltenOrange * 0.6, smokeColumns) * activeSmoke;

    // Resplandor de caldera y ondas de choque
    vec3 calderaCol = calderaGlow * cMoltenOrange * 2.2 + horizShock * cSolarGold * 1.8;

    // Grietas tectonicas y relampagos volcanicos
    vec3 cracksCol    = cracks * (cSolarGold * 2.5 + cMagmaCrimson * 1.8);
    vec3 lightningCol = lightning * (cPureWhite * 2.8 + cSolarGold * 1.6) * stormAlpha;

    // Ascuas flotantes en toda la cupula
    vec3 skyEmbersCol = skyEmbers * (cSolarGold * 2.6 + cMoltenOrange * 1.8);

    // El Aro de Magma Solar
    vec3 ringBaseCol = totalRing * (cMoltenOrange * 1.6 + cMagmaCrimson * 1.2);
    vec3 ringSeams   = magmaSeams * cSolarGold * 2.8 * ringAlpha;
    vec3 ringFinalCol= (ringBaseCol * (0.6 + 0.4 * plateDivision)) + ringSeams;

    // Interior del aro: celdas de magma, ascuas y ondas de calor
    vec3 furnaceInteriorCol = (boilingCore * cMoltenOrange * 0.70 + embers * cSolarGold * 2.4 + heatwave * cMoltenOrange * 1.8) * inSpace * magmaAlpha;

    // Simbolo Omega Primal con pulso vivo
    vec3 omegaSymbolCol = ((omegaCore * cSolarGold * 3.4 + omegaMantle * cMagmaCrimson * 2.0) * omegaPulse + omegaGodrays * cMoltenOrange * 1.2) * omegaAlpha;

    // Composicion final
    vec3 skyFinal = baseSky + cloudsCol + rimCol + smokeCol + calderaCol + cracksCol + lightningCol + skyEmbersCol + ringFinalCol + furnaceInteriorCol + omegaSymbolCol;

    // Fundido con niebla del horizonte
    float horizonFade = smoothstep(0.0, 0.16, y);
    vec3 finalRGB = mix(cHorizon, skyFinal, horizonFade);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalRGB, smoothProg);
}
