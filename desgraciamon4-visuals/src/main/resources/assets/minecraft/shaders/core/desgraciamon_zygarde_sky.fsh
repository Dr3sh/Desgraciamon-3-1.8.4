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

// Distancia a caja delimitadora (para los 5 paneles de monitor de Zygarde 100%)
float sdBox(vec2 p, vec2 b) {
    vec2 d = abs(p) - b;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
}

// Coordenadas y distancia a celdas hexagonales regulares
vec4 hexCoords(vec2 uv) {
    const vec2 s = vec2(1.0, 1.7320508);
    vec4 hC = floor(vec4(uv, uv - vec2(0.5, 1.0)) / vec4(s, s)) + 0.5;
    vec4 h = vec4(uv - hC.xy * s, uv - (hC.zw + 0.5) * s);
    return dot(h.xy, h.xy) < dot(h.zw, h.zw)
        ? vec4(h.xy, hC.xy)
        : vec4(h.zw, hC.zw + 9.73);
}

// Trazo exacto del Núcleo Castigo (Core Enforcer Z)
float sdCoreEnforcerZ(vec2 p, float scale) {
    vec2 q = p / scale;
    float minDist = 10.0;

    // Barra horizontal superior
    minDist = min(minDist, sdSegment(q, vec2(-0.45,  0.42), vec2(0.45,  0.42)));
    // Diagonal cortante
    minDist = min(minDist, sdSegment(q, vec2( 0.45,  0.42), vec2(-0.45, -0.42)));
    // Barra horizontal inferior
    minDist = min(minDist, sdSegment(q, vec2(-0.45, -0.42), vec2(0.45, -0.42)));

    // Dos apéndices de runa en los extremos
    minDist = min(minDist, sdSegment(q, vec2(-0.45,  0.42), vec2(-0.45,  0.30)));
    minDist = min(minDist, sdSegment(q, vec2( 0.45, -0.42), vec2( 0.45, -0.30)));

    return minDist * scale;
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    // Color del horizonte (Verde jade obsidiana profundo)
    vec3 cHorizon = vec3(0.010, 0.045, 0.025);

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
    // COREOGRAFIA ZYGARDE (12 SEGUNDOS):
    // 0.0 - 2.5s: Incisión ardiente del rayo de Core Enforcer trazando la Z colosal
    // 2.5 - 6.0s: Despliegue de los 5 Paneles del Pecho de Zygarde 100%
    // 6.0 - 9.0s: Activación de la Red Celular Hexagonal de las Células de Kalos
    // 9.0 - 12.0s: Ondas de Choque de Mil Ondas (Thousand Waves) ordenando el ecosistema
    // ------------------------------------------------------------------------
    float t = prog * 12.0;
    float zAlpha       = smoothstep(0.0, 2.5, t);
    float panelsAlpha  = smoothstep(2.0, 5.5, t);
    float hexAlpha     = smoothstep(5.0, 8.5, t);
    float wavesAlpha   = smoothstep(8.0, 12.0, t);

    // ------------------------------------------------------------------------
    // 1. EL NUCLEO CASTIGO (CORE ENFORCER Z)
    // Marca de energía pura quemada a fuego en el firmamento
    // ------------------------------------------------------------------------
    float zDist = sdCoreEnforcerZ(p, 0.65);
    float zCore   = exp(-pow(zDist / 0.0055, 2.0)) * 3.5;
    float zGlow   = exp(-pow(zDist / 0.035, 1.8)) * 1.3;
    float zPlasma = exp(-pow(zDist / 0.12, 1.5)) * (0.8 + 0.2 * sin(Time * 6.0));

    // ------------------------------------------------------------------------
    // 2. LOS 5 PANELES SENSOR DEL PECHO DE ZYGARDE 100%
    // Cinco segmentos rectangulares ordenados horizontalmente en el cenit
    // ------------------------------------------------------------------------
    vec3 panelsColorAccum = vec3(0.0);
    float panelWidth = 0.018;
    float panelHeight = 0.055;
    float panelSpacing = 0.048;

    vec3 panelColors[5];
    panelColors[0] = vec3(1.10, 0.15, 0.15); // 1. Rojo Carmesí
    panelColors[1] = vec3(0.15, 0.45, 1.15); // 2. Azul Océano
    panelColors[2] = vec3(0.98, 1.05, 0.98); // 3. Blanco Núcleo Puro
    panelColors[3] = vec3(1.10, 0.65, 0.10); // 4. Ámbar Tierra
    panelColors[4] = vec3(0.10, 1.05, 0.70); // 5. Jade Cian

    for (int i = 0; i < 5; i++) {
        float xOffset = (float(i) - 2.0) * panelSpacing;
        vec2 pPanel = p - vec2(xOffset, -0.02);
        float dBox = sdBox(pPanel, vec2(panelWidth, panelHeight));

        float pCore = smoothstep(0.002, 0.0, dBox) * 2.5;
        float pGlow = exp(-pow(max(0.0, dBox) / 0.015, 1.8)) * 1.2;

        panelsColorAccum += (pCore + pGlow) * panelColors[i];
    }

    // ------------------------------------------------------------------------
    // 3. MATRIZ CELULAR HEXAGONAL (CELULAS DE ZYGARDE 100%)
    // ------------------------------------------------------------------------
    vec4 hInfo = hexCoords(p * 9.0);
    vec2 hLocal = hInfo.xy;
    vec2 hId = hInfo.zw;

    float rHex = max(abs(hLocal.x) * 1.1547, abs(hLocal.y) * 0.57735 + abs(hLocal.x));
    float hexEdge = abs(rHex - 0.48);
    float hexGrid = exp(-pow(hexEdge / 0.045, 2.0));

    // Pulso de computación bio-celular por celda
    float cellRnd = hash2(hId).x;
    float cellPulse = step(0.65, sin(cellRnd * 20.0 + Time * 3.0));
    float hexFilled = smoothstep(0.40, 0.0, rHex) * cellPulse * 0.45;

    float totalHex = (hexGrid * 0.9 + hexFilled);

    // Haces de interconexión láser entre células en 360°
    float horizAngle = atan(dir.z, dir.x);
    float laserBeam = abs(sin(horizAngle * 12.0 + Time * 2.0)) * exp(-abs(y - 0.25) * 6.0);

    // ------------------------------------------------------------------------
    // 4. MIL ONDAS (THOUSAND WAVES) - ONDAS CONCENTRICAS DE EQUILIBRIO
    // ------------------------------------------------------------------------
    float wavePhase = fract(Time * 0.35);
    float waveRadius = wavePhase * 1.4;
    float waveRing = exp(-pow(abs(r - waveRadius) / 0.018, 2.0)) * (1.0 - wavePhase);

    // ------------------------------------------------------------------------
    // 5. PALETA CROMATICA DE ZYGARDE
    // ------------------------------------------------------------------------
    vec3 cZenith      = vec3(0.002, 0.012, 0.008);
    vec3 cJadeGreen   = vec3(0.05, 0.98, 0.45); // Verde Jade de Zygarde
    vec3 cLaserWhite  = vec3(0.95, 1.10, 0.95); // Blanco Láser Incandescente
    vec3 cDeepEmerald = vec3(0.02, 0.55, 0.28); // Esmeralda Profundo

    vec3 skyBase = mix(cHorizon, cZenith, smoothstep(0.0, 0.70, y));

    // Núcleo Castigo Z
    vec3 zCol = (zCore * cLaserWhite + zGlow * cJadeGreen + zPlasma * cDeepEmerald) * zAlpha;

    // Los 5 Paneles del Pecho
    vec3 panelsCol = panelsColorAccum * panelsAlpha;

    // Matriz Hexagonal de Células
    vec3 hexCol = totalHex * cJadeGreen * 1.4 * hexAlpha;

    // Ondas de Choque Mil Ondas
    vec3 wavesCol = waveRing * cLaserWhite * 1.8 * wavesAlpha;

    vec3 laserCol = laserBeam * cLaserWhite * 2.2 * hexAlpha;
    vec3 skyFinal = skyBase + zCol + panelsCol + hexCol + wavesCol + laserCol;

    // Fundido con niebla del horizonte
    float horizonFade = smoothstep(0.0, 0.16, y);
    vec3 finalRGB = mix(cHorizon, skyFinal, horizonFade);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalRGB, smoothProg);
}
