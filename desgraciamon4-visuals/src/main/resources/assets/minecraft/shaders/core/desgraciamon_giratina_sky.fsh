#version 150

uniform float Time;
uniform float Intensity;
uniform float EventTime;

in vec3 worldDir;
out vec4 fragColor;

// ============================================================================
// GIRATINA: THE DISTORTION WORLD SINGULARITY - 360° APOCALYPTIC SPECTACLE
// Relativistic black hole, antimatter lightning, and shadow tendrils spanning 360°.
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

// Relámpago procedural de antimateria
float antimatterBolt(vec2 p, float seed, float time) {
    float bolt = 0.0;
    float tOff = floor(time * 3.5 + seed);
    float flash = step(0.78, hash2(vec2(tOff, seed)).x);
    if (flash > 0.0) {
        float xPath = sin(p.y * 16.0 + seed * 20.0) * 0.12 + cos(p.y * 38.0) * 0.05;
        float dBolt = abs(p.x - xPath);
        bolt = exp(-pow(dBolt / 0.006, 2.0)) * 2.8;
    }
    return bolt;
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    vec3 cHorizon = vec3(0.025, 0.005, 0.015);
    if (y <= 0.0) {
        float smoothProg = prog * prog * (3.0 - 2.0 * prog);
        fragColor = vec4(cHorizon, smoothProg);
        return;
    }

    vec2 p = dir.xz / (max(y, 0.0) + 0.28);
    float r = length(p);
    float angle = atan(p.y, p.x);
    float horizAngle = atan(dir.z, dir.x);

    float t = (EventTime > 0.0) ? EventTime : (prog * 12.0);

    // ========================================================================
    // 1. SINGULARIDAD DEL MUNDO DISTORSIÓN (AGUJERO NEGRO EN EL CENIT)
    // Horizonte de sucesos negro absoluto con deflexión gravitatoria extrema
    // ========================================================================
    float eventHorizonR = 0.14;
    float blackHoleCore = smoothstep(eventHorizonR + 0.01, eventHorizonR - 0.01, r);

    // Disco de acreción relativista (con efecto Doppler: más brillante en el lado que rota hacia el frente)
    float rotAngle = angle - Time * 2.0;
    float dopplerBeaming = 1.0 + 0.65 * cos(rotAngle);
    float accretionDiskR = abs(r - 0.26);
    float accretionGlow = exp(-pow(accretionDiskR / 0.045, 2.0)) * dopplerBeaming * 2.8;

    // Fotones en órbita / Anillo de luz fotónica del agujero negro
    float photonRing = exp(-pow(abs(r - 0.16) / 0.006, 2.0)) * 3.5;

    // ========================================================================
    // 2. TENTÁCULOS DE SOMBRA Y RAÍCES DE ANTIMATERIA (SPANNING 360°)
    // 6 tentáculos primarios de Giratina que serpentean desde el cenit hasta el horizonte
    // ========================================================================
    float tentacleCount = 6.0;
    float tentacleWave = sin(angle * tentacleCount + sin(r * 12.0 - Time * 3.0) * 1.5);
    float tentacleCore = smoothstep(0.85, 0.98, tentacleWave) * smoothstep(0.12, 0.85, r);
    float tentacleGlow = exp(-pow((1.0 - tentacleWave) / 0.18, 2.0)) * smoothstep(0.12, 0.85, r) * 1.8;

    // ========================================================================
    // 3. RELÁMPAGOS DE ANTIMATERIA ROJO SANGRE Y DESTELLOS CAÓTICOS
    // ========================================================================
    vec2 boltCoord1 = vec2(horizAngle * 2.5, y * 4.0);
    vec2 boltCoord2 = vec2(p.x * 2.0, p.y * 2.0);
    float lightning1 = antimatterBolt(boltCoord1, 1.618, Time);
    float lightning2 = antimatterBolt(boltCoord2, 3.141, Time * 1.2);
    float totalLightning = lightning1 + lightning2;

    // ========================================================================
    // 4. NEBULOSA DEL MUNDO DISTORSIÓN Y POLVO DE GRAVEDAD INVERTIDA (360°)
    // ========================================================================
    vec2 distCoord = vec2(horizAngle * 4.0, y * 5.0) + vec2(sin(Time * 0.4), cos(Time * 0.3));
    float distNebula = fbm4(distCoord * 1.6);
    float distSwirl  = fbm4(p * 3.5 - vec2(Time * 0.15));
    float fullSkyDistortion = (distNebula * 0.6 + distSwirl * 0.4);

    // ========================================================================
    // 5. FRAGMENTOS DE CRISTAL FLOTANTES DEL MUNDO DISTORSIÓN
    // ========================================================================
    vec2 shardCell = floor(p * 18.0);
    vec2 shardRnd = hash2(shardCell);
    float shard = step(0.94, shardRnd.x) * (0.5 + 0.5 * sin(Time * 4.0 + shardRnd.y * 20.0)) * smoothstep(0.15, 0.95, r);

    // ========================================================================
    // 6. PALETA CROMÁTICA DE GIRATINA: ROJO ANTIMATERIA, NEGRO VACÍO Y ORO FANTASMAL
    // ========================================================================
    vec3 cVoidBlack       = vec3(0.002, 0.001, 0.004);
    vec3 cCrimsonEnergy   = vec3(1.85, 0.08, 0.15); // Rojo Antimateria
    vec3 cGhostGold       = vec3(1.40, 1.05, 0.25); // Oro Fantasmal de los Cuernos
    vec3 cDistortionGrey  = vec3(0.12, 0.05, 0.10); // Sombras del Mundo Distorsión
    vec3 cPhotonWhite     = vec3(2.50, 2.20, 2.00); // Anillo de Fotones

    vec3 skyBase = mix(cHorizon, cVoidBlack, smoothstep(0.0, 0.70, y));

    // Nebulosa del Mundo Distorsión
    vec3 nebulaCol = mix(cDistortionGrey, cCrimsonEnergy * 0.45, fullSkyDistortion);

    // Disco de acreción y anillo fotónico
    vec3 accretionCol = accretionGlow * mix(cCrimsonEnergy, cGhostGold, 0.4) 
                      + photonRing * cPhotonWhite;

    // Tentáculos de sombra
    vec3 tentacleCol = tentacleCore * cVoidBlack * 2.0 
                     + tentacleGlow * cCrimsonEnergy * 1.8;

    // Relámpagos
    vec3 lightningCol = totalLightning * (cCrimsonEnergy * 3.0 + cPhotonWhite * 1.5);

    // Fragmentos
    vec3 shardCol = shard * cGhostGold * 1.6;

    // Composición final
    vec3 skyFinal = skyBase + nebulaCol + accretionCol + tentacleCol + lightningCol + shardCol;

    // Oclusión del agujero negro central (absorbe toda la luz en el horizonte de sucesos)
    skyFinal = mix(skyFinal, cVoidBlack, blackHoleCore);

    float horizonFade = smoothstep(0.0, 0.15, y);
    vec3 finalRGB = mix(cHorizon, skyFinal, horizonFade);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalRGB, smoothProg);
}
