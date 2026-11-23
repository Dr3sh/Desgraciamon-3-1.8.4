#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform vec2 OutSize;
uniform float Time;

in vec2 texCoord;
out vec4 fragColor;

// Generador de ruido hash
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// ----------------------------------------------------
// MOTAS DE POLVO FLOTANTE CON DERIVA LINEAL CONTINUA
// Totalmente libre de loops o pulsaciones cíclicas
// ----------------------------------------------------
float sampleDustLayer(vec2 uv, vec2 scale, vec2 velocity, float seed) {
    vec2 scrollUv = uv * scale + velocity * Time;
    vec2 id = floor(scrollUv);
    vec2 f = fract(scrollUv) - 0.5;

    float h = hash12(id + vec2(seed, seed * 1.618));
    if (h > 0.72) { // Celdas con mota
        vec2 pos = (vec2(
            hash12(id + vec2(1.15, 2.73)),
            hash12(id + vec2(3.42, 4.89))
        ) - 0.5) * 0.70;

        vec2 diff = (f - pos) * vec2(1.0, InSize.y / InSize.x);
        float dist = length(diff);

        float brightness = ((h - 0.72) / 0.28);
        return smoothstep(0.045, 0.002, dist) * brightness;
    }
    return 0.0;
}

// ----------------------------------------------------
// ESPÍRITUS / LUCIÉRNAGAS LUMINOSAS CON DERIVA APERIÓDICA
// Sin trayectorias en 8 ni frecuencias repetitivas
// ----------------------------------------------------
float fireflyMote(vec2 uv, int idx) {
    float f = float(idx);

    float px = 0.5 + 0.44 * (
        sin(Time * 0.031 + f * 2.37) * 0.40 +
        sin(Time * 0.073 + f * 4.19) * 0.35 +
        sin(Time * 0.137 + f * 1.11) * 0.25
    );
    float py = 0.5 + 0.40 * (
        cos(Time * 0.027 + f * 1.83) * 0.40 +
        cos(Time * 0.061 + f * 5.61) * 0.35 +
        cos(Time * 0.119 + f * 3.47) * 0.25
    );

    vec2 pos = vec2(px, py);
    vec2 d = (uv - pos) * vec2(InSize.x / InSize.y, 1.0);
    float dist = length(d);

    float breathe = 0.5 + 0.5 * (
        sin(Time * (0.43 + f * 0.11) + f * 1.7) * 0.6 +
        cos(Time * (0.29 + f * 0.07) + f * 3.1) * 0.4
    );
    breathe = pow(clamp(breathe, 0.0, 1.0), 2.2);

    float halo = exp(-dist * 75.0) * breathe;
    float core = smoothstep(0.009, 0.001, dist) * breathe * 1.8;

    return halo + core;
}

void main() {
    vec2 uv = texCoord;

    // ----------------------------------------------------
    // 1. MUESTREO DE COLOR BASE Y BLOOM CELESTIAL
    // ----------------------------------------------------
    vec3 col = texture(DiffuseSampler, uv).rgb;

    // Muestreo circundante para dispersión suave de luz (Bloom)
    vec2 bOffset = vec2(0.0028, 0.0028);
    vec3 blurCol = (
        texture(DiffuseSampler, uv + vec2( bOffset.x,  bOffset.y)).rgb +
        texture(DiffuseSampler, uv + vec2(-bOffset.x, -bOffset.y)).rgb +
        texture(DiffuseSampler, uv + vec2( bOffset.x, -bOffset.y)).rgb +
        texture(DiffuseSampler, uv + vec2(-bOffset.x,  bOffset.y)).rgb +
        texture(DiffuseSampler, uv + vec2( 0.0,        bOffset.y * 1.4)).rgb +
        texture(DiffuseSampler, uv + vec2( 0.0,       -bOffset.y * 1.4)).rgb +
        texture(DiffuseSampler, uv + vec2( bOffset.x * 1.4, 0.0)).rgb +
        texture(DiffuseSampler, uv + vec2(-bOffset.x * 1.4, 0.0)).rgb
    ) * 0.125;

    // Extraer fuentes de luz, antorchas, sol poniente y linternas
    float bloomThreshold = 0.40;
    vec3 lightBloom = max(vec3(0.0), blurCol - bloomThreshold) * 2.2;
    vec3 warmBloom = lightBloom * vec3(1.35, 0.95, 0.50);

    // ----------------------------------------------------
    // 2. FILTRO DE CÁMARA NARANJA CINEMATOGRÁFICO DE RECUERDO (INTENSO Y NOTORIO)
    // Infusión rica y marcada de tono ámbar-naranja crepuscular sobre toda la vista
    // ----------------------------------------------------
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));

    // Graduación de color con realce notable de tonos naranjas y ámbar dorado
    vec3 orangeGraded = vec3(
        col.r * 1.38 + luma * 0.22,
        col.g * 0.98 + luma * 0.10,
        col.b * 0.38
    );

    // Infusión profunda del filtro de lente naranja (88%)
    vec3 graded = mix(col, orangeGraded, 0.88);

    // Realce de calidez ambiental ámbar constante en cámara
    graded += vec3(0.095, 0.052, 0.010) * (1.0 - luma * 0.45);

    // Aplicar bloom celestial dorado-naranja
    graded += warmBloom * 0.65;

    // Viñeta cálida suave en los bordes para sensación de cámara de cine
    vec2 vigCoord = (uv - 0.5) * vec2(InSize.x / InSize.y, 1.0);
    float vig = smoothstep(0.92, 0.35, length(vigCoord));
    graded *= mix(vec3(0.80, 0.70, 0.56), vec3(1.0), vig);

    // ----------------------------------------------------
    // 3. POLVO FLOTANTE Y MOTAS DORADAS SUSPENDIDAS
    // ----------------------------------------------------
    float dust = 0.0;
    dust += sampleDustLayer(uv, vec2(11.0, 9.0), vec2(0.008, -0.024), 1.25);
    dust += sampleDustLayer(uv, vec2(17.0, 15.0), vec2(-0.006, -0.016), 3.71);
    graded += vec3(1.20, 0.95, 0.65) * dust * 0.12;

    // ----------------------------------------------------
    // 4. DESTELLOS DE LUCIÉRNAGAS APERIÓDICAS
    // ----------------------------------------------------
    float fireflies = 0.0;
    for (int i = 0; i < 4; i++) {
        fireflies += fireflyMote(uv, i);
    }
    vec3 fireflyColor = vec3(1.25, 0.95, 0.45); // Oro ámbar cálido
    graded += fireflyColor * fireflies * 0.85;

    fragColor = vec4(clamp(graded, 0.0, 1.0), 1.0);
}
