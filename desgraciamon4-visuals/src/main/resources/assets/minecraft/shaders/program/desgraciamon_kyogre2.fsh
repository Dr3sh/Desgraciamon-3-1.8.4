#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform vec2 OutSize;
uniform float Time;
uniform float Intensity;

in vec2 texCoord;
out vec4 fragColor;

// Hash 2D rapido
vec2 hash2(vec2 p) {
    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453);
}

// Simulacion de gotas gruesas de agua resbalando sobre la camara
vec2 raindropNormal(vec2 uv, float t) {
    vec2 normal = vec2(0.0);
    
    // Capa 1: Gotas gruesas deslizantes
    vec2 grid1 = uv * vec2(12.0, 4.0);
    grid1.y += t * 1.8;
    vec2 id1 = floor(grid1);
    vec2 f1 = fract(grid1) - 0.5;
    vec2 rnd1 = hash2(id1);
    if (rnd1.x > 0.65) {
        float dropShape = length(f1 * vec2(1.0, 1.8));
        if (dropShape < 0.35) {
            normal += normalize(f1) * (1.0 - dropShape / 0.35) * 0.025;
        }
    }

    // Capa 2: Gotas estáticas y regueros finos
    vec2 grid2 = uv * vec2(24.0, 16.0);
    vec2 id2 = floor(grid2);
    vec2 f2 = fract(grid2) - 0.5;
    vec2 rnd2 = hash2(id2);
    if (rnd2.y > 0.75) {
        float smallDrop = length(f2);
        if (smallDrop < 0.28) {
            normal += normalize(f2) * (1.0 - smallDrop / 0.28) * 0.015;
        }
    }

    // Ondulación de lámina de agua de fondo
    normal.x += sin(uv.y * 35.0 + t * 4.0) * 0.003;
    normal.y += cos(uv.x * 35.0 + t * 3.5) * 0.003;

    return normal;
}

void main() {
    float strength = clamp(Intensity, 0.0, 1.0);
    if (strength <= 0.001) {
        fragColor = texture(DiffuseSampler, texCoord);
        return;
    }

    vec2 pixel = 1.0 / max(InSize, vec2(1.0));
    vec2 aspect = InSize / max(OutSize, vec2(1.0));
    vec2 centered = (texCoord - 0.5) * aspect;
    float dist = length(centered);

    // 1. Distorsión por refracción de gotas de agua (Normal Map distortion)
    vec2 normalOffset = raindropNormal(texCoord, Time) * strength;
    vec2 refractedUV = texCoord + normalOffset;

    // 2. Aberración cromática acuatica
    vec2 chromDir = normalize(centered + vec2(0.0001));
    vec2 chromOffset = (chromDir * pixel * (5.5 * dist) + normalOffset * 1.5) * strength;
    float rCh = texture(DiffuseSampler, refractedUV + chromOffset).r;
    float gCh = texture(DiffuseSampler, refractedUV).g;
    float bCh = texture(DiffuseSampler, refractedUV - chromOffset).b;
    vec3 baseRGB = vec3(rCh, gCh, bCh);

    float luma = dot(baseRGB, vec3(0.2126, 0.7152, 0.0722));

    // Colorimetría Kyogre: Azul marino, pizarra y cian eléctrico
    vec3 cNavyAbyss   = vec3(0.015, 0.045, 0.160);
    vec3 cSlate       = vec3(0.080, 0.380, 0.880);
    vec3 cElectricCyan= vec3(0.350, 1.250, 1.500);

    vec3 grading = mix(cNavyAbyss, cSlate, smoothstep(0.02, 0.52, luma));
    grading = mix(grading, cElectricCyan, smoothstep(0.50, 0.95, luma));
    vec3 graded = baseRGB * grading * 1.62;

    float vignette = clamp(dot(centered, centered) * 0.50, 0.0, 0.60);
    graded = mix(graded, cNavyAbyss * 0.75, vignette * strength);

    vec3 sceneRaw = texture(DiffuseSampler, texCoord).rgb;
    vec3 finalRGB = mix(sceneRaw, clamp(graded, 0.0, 1.0), strength * 0.94);
    fragColor = vec4(finalRGB, 1.0);
}

