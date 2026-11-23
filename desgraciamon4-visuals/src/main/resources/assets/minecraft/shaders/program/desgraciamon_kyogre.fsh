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

    // 1. Refraccion acuatica / lente de presion hidro-cinetica
    vec2 waterWave = vec2(
        sin(texCoord.y * 35.0 + Time * 3.5) * 0.0025,
        cos(texCoord.x * 35.0 + Time * 3.0) * 0.0025
    ) * strength;

    // 2. Aberracion cromatica abisal
    vec2 chromDir = normalize(centered + vec2(0.0001));
    vec2 chromOffset = chromDir * pixel * (5.0 * dist) * strength;
    float rChannel = texture(DiffuseSampler, texCoord + waterWave + chromOffset).r;
    float gChannel = texture(DiffuseSampler, texCoord + waterWave).g;
    float bChannel = texture(DiffuseSampler, texCoord + waterWave - chromOffset).b;
    vec3 baseRGB = vec3(rChannel, gChannel, bChannel);

    float luma = dot(baseRGB, vec3(0.2126, 0.7152, 0.0722));

    // 3. Color Grading KYOGRE (Diluvio Primal & Fosa Mariana)
    // - Sombras: Azul abisal marino y tinta de calamar
    // - Medios: Azul zafiro y cian bioluminiscente
    // - Luces: Blanco espuma de mar y fulgor carmesi
    vec3 cAbyssNavy  = vec3(0.015, 0.045, 0.160);
    vec3 cSapphire   = vec3(0.080, 0.420, 0.950);
    vec3 cBioCyan    = vec3(0.250, 1.250, 1.450);

    vec3 kyogreGrading = mix(cAbyssNavy, cSapphire, smoothstep(0.02, 0.52, luma));
    kyogreGrading = mix(kyogreGrading, cBioCyan, smoothstep(0.50, 0.95, luma));

    vec3 graded = baseRGB * kyogreGrading * 1.60;

    // Saturacion de aguas profundas
    vec3 satBoost = mix(vec3(luma), graded, 1.50);
    graded = mix(graded, satBoost, 0.65);

    // 4. Bloom bioluminiscente en altas luces
    float bloomMask = pow(smoothstep(0.48, 1.0, luma), 2.2);
    vec3 cBloom = mix(vec3(0.10, 1.20, 1.40), vec3(1.30, 0.20, 0.35), 0.35 + 0.35 * sin(Time * 2.0));
    graded += cBloom * bloomMask * 0.65 * strength;

    // Viñeta de presion de profundidad
    float vignette = clamp(dot(centered, centered) * 0.50, 0.0, 0.60);
    graded = mix(graded, cAbyssNavy * 0.70, vignette * strength);

    vec3 sceneRaw = texture(DiffuseSampler, texCoord).rgb;
    vec3 finalRGB = mix(sceneRaw, clamp(graded, 0.0, 1.0), strength * 0.94);
    fragColor = vec4(finalRGB, 1.0);
}

