#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform vec2 OutSize;
uniform float Time;
uniform float Intensity;

in vec2 texCoord;
out vec4 fragColor;

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

    // 1. Distorsion de calor / espejismo de la Tierra del Ocaso (Desolate Land)
    vec2 heatShimmer = vec2(
        sin(texCoord.y * 45.0 + Time * 6.0) * 0.0020,
        cos(texCoord.x * 45.0 + Time * 5.0) * 0.0015
    ) * strength;

    // 2. Aberracion cromatica termica
    vec2 chromDir = normalize(centered + vec2(0.0001));
    vec2 chromOffset = chromDir * pixel * (5.5 * dist) * strength;
    float rChannel = texture(DiffuseSampler, texCoord + heatShimmer + chromOffset).r;
    float gChannel = texture(DiffuseSampler, texCoord + heatShimmer).g;
    float bChannel = texture(DiffuseSampler, texCoord + heatShimmer - chromOffset).b;
    vec3 baseRGB = vec3(rChannel, gChannel, bChannel);

    float luma = dot(baseRGB, vec3(0.2126, 0.7152, 0.0722));

    // 3. Color Grading GROUDON (Furia Volcanica & Tierra Calcinada)
    // - Sombras: Obsidiana carbonizada y ceniza volcanica
    // - Medios: Rojo magma incandescente y naranja fuego
    // - Luces: Oro solar brillante y blanco de fusion termonuclear
    vec3 cObsidianChar = vec3(0.060, 0.020, 0.010);
    vec3 cMagmaRed     = vec3(1.250, 0.320, 0.060);
    vec3 cSolarGold    = vec3(1.850, 1.450, 0.350);

    vec3 groudonGrading = mix(cObsidianChar, cMagmaRed, smoothstep(0.02, 0.48, luma));
    groudonGrading = mix(groudonGrading, cSolarGold, smoothstep(0.46, 0.95, luma));

    vec3 graded = baseRGB * groudonGrading * 1.62;

    // Saturacion de magma caliente
    vec3 satBoost = mix(vec3(luma), graded, 1.55);
    graded = mix(graded, satBoost, 0.70);

    // 4. HDR Bloom solar en altas luces
    float bloomMask = pow(smoothstep(0.46, 1.0, luma), 2.2);
    vec3 cBloom = mix(vec3(1.40, 0.45, 0.08), vec3(1.80, 1.50, 0.30), 0.5 + 0.5 * sin(Time * 2.8));
    graded += cBloom * bloomMask * 0.70 * strength;

    // Viñeta de ceniza calcinada
    float vignette = clamp(dot(centered, centered) * 0.50, 0.0, 0.60);
    graded = mix(graded, cObsidianChar * 0.75, vignette * strength);

    vec3 sceneRaw = texture(DiffuseSampler, texCoord).rgb;
    vec3 finalRGB = mix(sceneRaw, clamp(graded, 0.0, 1.0), strength * 0.94);
    fragColor = vec4(finalRGB, 1.0);
}

