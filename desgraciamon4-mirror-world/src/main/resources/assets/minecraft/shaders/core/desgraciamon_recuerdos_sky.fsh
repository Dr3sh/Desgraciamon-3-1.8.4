#version 150

uniform float Time;
uniform vec3 SunDir;

in vec3 worldDir;
out vec4 fragColor;

// Generador de ruido procedural hash
float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float hash31(vec3 p) {
    p = fract(p * vec3(123.34, 456.21, 789.54));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y * p.z);
}

float noise2D(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);

    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));

    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    mat2 rot = mat2(0.80, 0.60, -0.60, 0.80);
    for (int i = 0; i < 4; i++) {
        v += a * noise2D(p);
        p = rot * p * 2.02 + vec2(1.4, 2.7);
        a *= 0.5;
    }
    return v;
}

// ----------------------------------------------------
// SISTEMA DE FIRMAMENTO ESTELAR MULTICAPA 100% ESFÉRICO Y ANIMADO
// Estrellas estrictamente redondas, sin formas poligonales ni tamaños desmedidos
// ----------------------------------------------------

// Capa 1: Miriada de micro-estrellas densas con parpadeo vivo y respiración circular
vec3 microStars(vec3 p) {
    vec3 pStar = p * 130.0;
    vec3 id = floor(pStar);
    vec3 f = fract(pStar) - 0.5;
    float h = hash31(id);
    if (h > 0.76) { // 24% de celdas
        // Distancia euclidiana pura: garantiza forma 100% circular perfecta
        float dist = length(f);

        // Dilatación y contracción controlada (grande y pequeña pero sutil)
        float pulseSpeed = 2.5 + fract(h * 11.13) * 3.5;
        float sizeOsc = 0.65 + 0.45 * sin(Time * pulseSpeed + h * 47.0);
        float baseR = mix(0.018, 0.042, fract(h * 23.41));
        float curSize = baseR * sizeOsc;

        // Centelleo de alto brillo dinámico
        float twSpeed = 2.8 + fract(h * 19.31) * 3.8;
        float twOsc = sin(Time * twSpeed + h * 31.0);
        float scintillation = pow(max(0.0, twOsc), 3.0);
        float twinkle = 0.35 + 2.5 * scintillation + 0.35 * max(0.0, twOsc);

        float bright = mix(0.80, 4.0, fract(h * 41.77)) * twinkle;
        float core = smoothstep(curSize, 0.001, dist) * bright * ((h - 0.76) / 0.24);

        // Tonalidades celestiales suaves: doradas, celestes y blancas
        vec3 col = mix(vec3(1.05, 0.94, 0.82), vec3(0.85, 0.96, 1.25), fract(h * 7.13));
        return col * core;
    }
    return vec3(0.0);
}

// Capa 2: Estrellas medianas circulares con halo radial suave (sin crucetas poligonales)
vec3 mediumStars(vec3 p) {
    vec3 pStar = p * 75.0;
    vec3 id = floor(pStar);
    vec3 f = fract(pStar) - 0.5;
    float h = hash31(id + vec3(4.2, 8.1, 1.9));
    if (h > 0.82) { // 18% de celdas
        float dist = length(f);

        // Respiración rítmica del tamaño circular
        float pulseRate = 1.8 + fract(h * 31.73) * 2.2;
        float breath = sin(Time * pulseRate + h * 53.0);
        float sizeOsc = 0.70 + 0.45 * (0.5 + 0.5 * breath);
        float baseR = mix(0.028, 0.058, fract(h * 13.87));
        float curSize = baseR * sizeOsc;

        // Centelleo de brillo concentrado
        float scint = pow(0.5 + 0.5 * sin(Time * (pulseRate * 1.35) + h * 67.0), 3.5);
        float bright = mix(1.5, 5.2, fract(h * 53.61)) * (0.55 + 2.4 * scint);

        // Núcleo circular nítido
        float core = smoothstep(curSize, 0.002, dist) * 2.2;
        // Halo radial puramente circular (exponencial, nunca poligonal)
        float halo = exp(-dist * 48.0) * (0.50 + 0.90 * scint);

        vec3 col = mix(vec3(1.15, 0.90, 0.65), vec3(0.85, 1.02, 1.30), fract(h * 13.7));
        return col * (core + halo) * bright * ((h - 0.82) / 0.18);
    }
    return vec3(0.0);
}

// Capa 3: Estrellas prominentes luminosas con aura celestial circular
vec3 majorStars(vec3 p) {
    vec3 pStar = p * 42.0;
    vec3 id = floor(pStar);
    vec3 f = fract(pStar) - 0.5;
    float h = hash31(id + vec3(12.7, 3.4, 9.8));
    if (h > 0.91) { // 9% de celdas
        float dist = length(f);

        // Latido cósmico circular majestuoso
        float breathSpeed = 1.3 + fract(h * 17.41) * 1.5;
        float breath = sin(Time * breathSpeed + h * 60.0);
        float sizeOsc = 0.72 + 0.45 * (0.5 + 0.5 * breath);
        // Radio controlado para evitar estrellas gigantescas
        float baseR = mix(0.040, 0.075, fract(h * 19.45));
        float curSize = baseR * sizeOsc;

        // Fulgor resplandeciente
        float flash = pow(0.5 + 0.5 * breath, 2.2);
        float bright = mix(2.2, 6.5, fract(h * 61.27)) * (0.70 + 2.2 * flash);

        // Núcleo circular brillante
        float core = smoothstep(curSize, 0.003, dist) * 3.4;
        // Aura celestial 100% circular suave (sin picos de difracción lineales que se vean cuadrados)
        float aura = exp(-dist * 26.0) * (0.80 + 1.4 * flash);

        vec3 col = mix(vec3(1.30, 1.02, 0.72), vec3(0.82, 1.12, 1.45), fract(h * 5.9));
        return col * (core + aura) * bright * ((h - 0.91) / 0.09);
    }
    return vec3(0.0);
}

// Distancia de un punto a un segmento en el espacio esférico
float distToSegment(vec3 p, vec3 a, vec3 b, out float tSeg) {
    vec3 ab = b - a;
    float lenSq = dot(ab, ab);
    if (lenSq < 0.00001) {
        tSeg = 0.0;
        return length(p - a);
    }
    tSeg = clamp(dot(p - a, ab) / lenSq, 0.0, 1.0);
    vec3 proj = a + ab * tSeg;
    return length(p - proj);
}

// ----------------------------------------------------
// EVALUACIÓN DE ESTRELLA FUGAZ BRILLANTE Y MAJESTUOSA
// ----------------------------------------------------
vec3 evaluateShootingStar(vec3 dir, vec3 start, vec3 velocity, float period, float timeOffset) {
    float t = mod(Time + timeOffset, period);
    float dur = 1.25; // Mayor duración en pantalla para verla claramente
    if (t > dur) return vec3(0.0);

    float progress = t / dur;
    // La estrella acelera ligeramente al caer
    float easeProg = progress * progress * 0.35 + progress * 0.65;
    vec3 head = start + velocity * easeProg;
    // Cola larga y persistente que dibuja el trazo en la memoria
    vec3 tail = start + velocity * max(0.0, easeProg - 0.50);

    float tSeg;
    float d = distToSegment(dir, tail, head, tSeg);

    // Trazo principal grueso y bien visible
    float coreStreak = smoothstep(0.026, 0.002, d) * tSeg * (1.2 - progress * 0.35);
    // Resplandor celestial circundante a lo largo de la estela
    float haloStreak = smoothstep(0.075, 0.006, d) * tSeg * 0.55;

    // Cabeza hiper-brillante con destello y corona de refracción
    float headDist = length(dir - head);
    float headCore = smoothstep(0.048, 0.002, headDist) * 3.8;
    float headCorona = smoothstep(0.14, 0.015, headDist) * 1.4;

    float totalGlow = coreStreak + haloStreak + headCore + headCorona;
    // Color blanco dorado estelar con corona de brillo ámbar
    vec3 coreColor = vec3(1.30, 1.15, 0.95);
    vec3 auraColor = vec3(1.10, 0.75, 0.40);
    return mix(auraColor, coreColor, clamp(headCore * 0.8 + coreStreak * 0.5, 0.0, 1.0)) * totalGlow;
}

void main() {
    vec3 dir = normalize(worldDir);
    float up = dir.y;

    // ----------------------------------------------------
    // 1. GRADIENTE DE ATARDECER NOSTÁLGICO (Tonos Rosados y Naranjas)
    // ----------------------------------------------------
    vec3 cNadir       = vec3(0.09, 0.05, 0.13); // Abismo crepuscular inferior
    vec3 cHorizonGlow = vec3(0.96, 0.44, 0.18); // Resplandor naranja ámbar en el horizonte
    vec3 cSunsetLow   = vec3(1.00, 0.56, 0.22); // Naranja radiante del ocaso
    vec3 cSunsetMid   = vec3(0.92, 0.40, 0.46); // Rosa salmón suave de memoria
    vec3 cSunsetHigh  = vec3(0.58, 0.22, 0.44); // Amatista rosado crepuscular
    vec3 cZenith      = vec3(0.22, 0.09, 0.30); // Violeta crepuscular profundo en el cenit

    vec3 skyCol;
    if (up < 0.0) {
        float f = clamp(-up * 2.5, 0.0, 1.0);
        skyCol = mix(cHorizonGlow, cNadir, f);
    } else {
        if (up < 0.14) {
            float t = up / 0.14;
            skyCol = mix(cSunsetLow, cSunsetMid, t);
        } else if (up < 0.42) {
            float t = (up - 0.14) / 0.28;
            skyCol = mix(cSunsetMid, cSunsetHigh, t);
        } else {
            float t = clamp((up - 0.42) / 0.58, 0.0, 1.0);
            skyCol = mix(cSunsetHigh, cZenith, t);
        }
    }

    // ----------------------------------------------------
    // 2. DISCO SOLAR PONIENTE Y CORONA CELESTIAL
    // ----------------------------------------------------
    vec3 sDir = normalize(SunDir);
    float sunDot = dot(dir, sDir);

    float sunDisk = smoothstep(0.9991, 0.9998, sunDot);
    vec3 sunCoreCol = vec3(1.20, 0.95, 0.85);

    float coronaWide = pow(max(0.0, sunDot), 6.0) * 0.42;
    float coronaTight = pow(max(0.0, sunDot), 28.0) * 0.85;
    vec3 sunGlowCol = vec3(1.00, 0.62, 0.28) * (coronaWide + coronaTight);

    skyCol += sunDisk * sunCoreCol + sunGlowCol;

    // ----------------------------------------------------
    // 3. FIRMAMENTO ESTELAR 100% CIRCULAR Y VIBRANTE (3 Capas)
    // ----------------------------------------------------
    // Visible desde apenas por encima del horizonte hasta el cenit
    if (up > 0.015) {
        float starVis = smoothstep(0.015, 0.22, up);

        vec3 stars = vec3(0.0);
        stars += microStars(dir);
        stars += mediumStars(dir);
        stars += majorStars(dir);

        skyCol += stars * starVis;
    }

    // ----------------------------------------------------
    // 4. LLUVIA DE ESTRELLAS FUGACES BIEN VISIBLES
    // ----------------------------------------------------
    if (up > 0.03) {
        vec3 shootingGlow = vec3(0.0);

        // 6 trayectorias con periodos y ángulos variados para presencia constante y majestuosa
        shootingGlow += evaluateShootingStar(
            dir,
            normalize(vec3(-0.35, 0.82, 0.25)),
            vec3(0.65, -0.48, -0.40),
            6.8,
            0.0
        );
        shootingGlow += evaluateShootingStar(
            dir,
            normalize(vec3(0.48, 0.85, -0.22)),
            vec3(-0.55, -0.42, 0.52),
            9.4,
            3.2
        );
        shootingGlow += evaluateShootingStar(
            dir,
            normalize(vec3(-0.58, 0.72, -0.45)),
            vec3(0.42, -0.38, -0.62),
            12.6,
            7.5
        );
        shootingGlow += evaluateShootingStar(
            dir,
            normalize(vec3(0.28, 0.78, 0.62)),
            vec3(-0.60, -0.45, -0.36),
            15.8,
            11.1
        );
        shootingGlow += evaluateShootingStar(
            dir,
            normalize(vec3(0.12, 0.90, -0.50)),
            vec3(-0.38, -0.52, 0.48),
            19.2,
            5.7
        );
        shootingGlow += evaluateShootingStar(
            dir,
            normalize(vec3(-0.45, 0.76, 0.50)),
            vec3(0.58, -0.40, -0.42),
            22.4,
            16.3
        );

        float shootVis = smoothstep(0.03, 0.16, up);
        skyCol += shootingGlow * shootVis;
    }

    // ----------------------------------------------------
    // 5. NUBES CIRRUS CREPUSCULARES SUAVES
    // ----------------------------------------------------
    if (up > 0.02) {
        vec2 cloudUv = (dir.xz / (up + 0.18)) * 0.30 + vec2(Time * 0.004, Time * 0.0018);
        float cloudNoise = fbm(cloudUv * 2.2);
        float cloudMask = smoothstep(0.48, 0.74, cloudNoise) * smoothstep(0.02, 0.25, up);

        vec3 cloudColor = mix(vec3(0.98, 0.52, 0.30), vec3(0.72, 0.38, 0.54), up);
        skyCol = mix(skyCol, cloudColor, cloudMask * 0.28);
    }

    fragColor = vec4(clamp(skyCol, 0.0, 1.0), 1.0);
}
