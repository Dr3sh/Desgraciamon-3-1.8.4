#version 150

uniform float Time;
uniform float Intensity;

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

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    // Color del horizonte (Púrpura neón ciberespacial oscuro)
    vec3 cHorizon = vec3(0.045, 0.008, 0.065);

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
    // RITMO SUB-BASS 808 (PULSO AUDIO-REACTIVO)
    // ------------------------------------------------------------------------
    float beatRate = 2.166667; // ~130 BPM
    float beatPhase = fract(Time * beatRate);
    float bassDrop = pow(max(0.0, 1.0 - beatPhase), 4.0);
    float totalKick = bassDrop;

    // ------------------------------------------------------------------------
    // COREOGRAFIA SIMETRICA (12 SEGUNDOS):
    // 0.0 - 3.0s: El cielo colapsa en la Noche Neón y emerge la Rejilla Holográfica
    // 3.0 - 6.5s: Despliegue de la Ciber-Esfera Wireframe Astroworld en el cenit
    // 6.5 - 9.5s: Ondas de choque del ecualizador de sonido (Soundwave Rings)
    // 9.5 - 12.0s: Haces de láseres neón y estrellas de glitch
    // ------------------------------------------------------------------------
    float t = prog * 12.0;
    float gridAlpha   = smoothstep(0.0, 3.0, t);
    float sphereAlpha = smoothstep(2.5, 6.5, t);
    float wavesAlpha  = smoothstep(6.0, 9.5, t);
    float lasersAlpha = smoothstep(9.0, 12.0, t);

    // ------------------------------------------------------------------------
    // 1. REJILLA CIBERNÉTICA EN EL HORIZONTE (RETRO-GRID)
    // ------------------------------------------------------------------------
    // Líneas de perspectiva en el horizonte
    float gridLat = abs(sin(r * 24.0 - Time * 0.8));
    float gridLong = abs(sin(angle * 16.0));
    float cyberGrid = smoothstep(0.92, 0.99, max(gridLat, gridLong)) * exp(-r * 0.9);

    // ------------------------------------------------------------------------
    // 2. LA CIBER-ESFERA WIREFRAME DE ASTROWORLD EN EL CENIT
    // Esfera holográfica 3D con meridianos y paralelos rotatorios
    // ------------------------------------------------------------------------
    float sphereRadius = 0.38 + 0.03 * totalKick;
    float sphereD = abs(r - sphereRadius);
    float sphereContour = exp(-pow(sphereD / 0.008, 2.0));

    // Meridianos rotatorios (líneas de longitud 3D)
    float rotAngle = angle + Time * 0.40;
    float meridians = abs(sin(rotAngle * 8.0));
    float meridianLines = smoothstep(0.85, 0.98, meridians) * step(r, sphereRadius * 1.02);

    // Paralelos concéntricos (anillos de latitud)
    float parallels = abs(sin((r / sphereRadius) * 3.14159 * 4.0));
    float parallelLines = smoothstep(0.82, 0.96, parallels) * step(r, sphereRadius * 1.02);

    // Anillo ecuatorial flotante exterior estilo Saturno
    float eqRingD = abs(r - (sphereRadius * 1.35));
    float eqRing = exp(-pow(eqRingD / 0.006, 2.0)) * (0.6 + 0.4 * sin(angle * 12.0 - Time * 2.0));

    // Núcleo brillante central de la esfera
    float sphereCore = exp(-pow(r / 0.12, 2.0)) * (2.2 + 1.2 * totalKick);

    float wireframeSphere = (sphereContour * 2.0 + (meridianLines + parallelLines) * 1.2 + eqRing * 1.8);

    // ------------------------------------------------------------------------
    // 3. ONDAS DE SONIDO DEL SUB-BASS (EQUALIZER SOUNDWAVES)
    // Anillos concéntricos expansivos que explotan con cada golpe del bajo
    // ------------------------------------------------------------------------
    float wavePhase1 = fract(Time * beatRate * 0.50);
    float waveRadius1 = wavePhase1 * 1.5;
    float soundwave1 = exp(-pow(abs(r - waveRadius1) / 0.022, 2.0)) * (1.0 - wavePhase1);

    float wavePhase2 = fract(Time * beatRate * 0.50 + 0.50);
    float waveRadius2 = wavePhase2 * 1.5;
    float soundwave2 = exp(-pow(abs(r - waveRadius2) / 0.022, 2.0)) * (1.0 - wavePhase2);

    float allSoundwaves = (soundwave1 + soundwave2) * wavesAlpha;

    // ------------------------------------------------------------------------
    // 4. LÁSERES NEÓN Y ESTRELLAS PSICODÉLICAS
    // ------------------------------------------------------------------------
    // 4 Haces láser cruzados estilo concierto masivo
    float laser1 = exp(-pow(abs(p.x - p.y) / 0.006, 2.0));
    float laser2 = exp(-pow(abs(p.x + p.y) / 0.006, 2.0));
    float allLasers = (laser1 + laser2) * exp(-r * 0.8) * lasersAlpha;

    // Estrellas titilantes neón
    vec2 starCell = floor(p * 75.0);
    vec2 starRnd = hash2(starCell);
    float star = step(0.980, starRnd.x) * smoothstep(0.15, 0.95, r) * (0.7 + 0.3 * sin(Time * 5.0 + starRnd.y * 10.0));

    // ------------------------------------------------------------------------
    // 5. PALETA CROMATICA PSICODELICA TRAVIS SCOTT
    // ------------------------------------------------------------------------
    vec3 cZenithDeep   = vec3(0.003, 0.001, 0.010); // Negro cósmico
    vec3 cNeonMagenta  = vec3(1.15, 0.05, 0.75);   // Fucsia / Magenta Neón
    vec3 cNeonCyan     = vec3(0.00, 1.05, 0.90);   // Cian Eléctrico
    vec3 cNeonGreen    = vec3(0.55, 1.15, 0.05);   // Verde Radiactivo Flúor
    vec3 cWhiteBloom   = vec3(1.05, 1.05, 1.20);   // Blanco Neón

    vec3 skyBase = mix(cHorizon, cZenithDeep, smoothstep(0.0, 0.70, y));

    // Colores de elementos
    vec3 gridCol = cyberGrid * mix(cNeonMagenta, cNeonCyan, 0.5) * 1.8 * gridAlpha;
    vec3 sphereCol = (wireframeSphere * cNeonCyan + sphereCore * cWhiteBloom) * sphereAlpha;
    vec3 wavesCol = allSoundwaves * mix(cNeonMagenta, cNeonGreen, sin(Time * 3.0) * 0.5 + 0.5) * 2.2;
    vec3 lasersCol = allLasers * cNeonMagenta * 2.5;
    vec3 starsCol = star * cNeonCyan * 1.5;

    vec3 skyFinal = skyBase + gridCol + sphereCol + wavesCol + lasersCol + starsCol;

    // Fogonazo de luz en cada golpe del bajo
    skyFinal += cNeonCyan * (totalKick * 0.25 * prog);

    // Fundido con niebla del horizonte
    float horizonFade = smoothstep(0.0, 0.16, y);
    vec3 finalRGB = mix(cHorizon, skyFinal, horizonFade);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalRGB, smoothProg);
}

