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

    // 1. Distorsión térmica de calima
    vec2 heatWave = vec2(
        sin(texCoord.y * 50.0 + Time * 6.5) * 0.0022,
        cos(texCoord.x * 50.0 + Time * 5.5) * 0.0018
    ) * strength;

    // 2. Grano de película pesado (Film Grain analógico)
    vec2 grainCoord = texCoord * InSize + vec2(sin(Time * 45.0), cos(Time * 35.0)) * 100.0;
    float filmGrain = (hash2(grainCoord).x - 0.5) * 0.14 * strength;

    // 3. Ceniza negra volcánica cayendo por la pantalla
    vec2 ashCoord = texCoord * vec2(40.0, 15.0);
    ashCoord.y -= Time * 2.2;
    ashCoord.x += sin(ashCoord.y * 3.0 + Time * 2.0) * 0.5;
    vec2 ashId = floor(ashCoord);
    vec2 ashFract = fract(ashCoord) - 0.5;
    vec2 ashRnd = hash2(ashId);
    float ashFlake = 0.0;
    if (ashRnd.x > 0.94) {
        float fDist = length(ashFract);
        if (fDist < 0.22) {
            ashFlake = (1.0 - fDist / 0.22) * 0.65 * strength;
        }
    }

    // Aberración cromática térmica
    vec2 chromDir = normalize(centered + vec2(0.0001));
    vec2 chromOffset = chromDir * pixel * (5.5 * dist) * strength;
    float rCh = texture(DiffuseSampler, texCoord + heatWave + chromOffset).r;
    float gCh = texture(DiffuseSampler, texCoord + heatWave).g;
    float bCh = texture(DiffuseSampler, texCoord + heatWave - chromOffset).b;
    vec3 baseRGB = vec3(rCh, gCh, bCh);

    // Aplicar grano y ceniza negra
    baseRGB += vec3(filmGrain);
    baseRGB = mix(baseRGB, vec3(0.015, 0.008, 0.005), ashFlake);

    float luma = dot(baseRGB, vec3(0.2126, 0.7152, 0.0722));

    // Colorimetría Cataclysmic Drought: Bermellón, carbón y naranja incandescente
    vec3 cCharcoal    = vec3(0.060, 0.020, 0.010);
    vec3 cVermillion  = vec3(1.300, 0.350, 0.060);
    vec3 cSolarGold   = vec3(1.850, 1.450, 0.350);

    vec3 grading = mix(cCharcoal, cVermillion, smoothstep(0.02, 0.48, luma));
    grading = mix(grading, cSolarGold, smoothstep(0.46, 0.95, luma));
    vec3 graded = baseRGB * grading * 1.62;

    // 4. Quemadura de bordes (Vignette con tinte cobrizo e incandescente)
    float edgeBurn = clamp(dot(centered, centered) * 0.65, 0.0, 0.85);
    vec3 cCopperGlow = vec3(0.85, 0.25, 0.05) * 1.5;
    graded = mix(graded, cCharcoal * 0.5 + cCopperGlow * 0.5, edgeBurn * strength);

    vec3 sceneRaw = texture(DiffuseSampler, texCoord).rgb;
    vec3 finalRGB = mix(sceneRaw, clamp(graded, 0.0, 1.0), strength * 0.94);
    fragColor = vec4(finalRGB, 1.0);
}

