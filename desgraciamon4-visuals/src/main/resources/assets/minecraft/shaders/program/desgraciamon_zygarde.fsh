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

    // 1. Aberración cromática sutil de campo geomagnético
    vec2 chromDir = normalize(centered + vec2(0.0001));
    vec2 chromOffset = chromDir * pixel * (4.5 * dist) * strength;
    float rChannel = texture(DiffuseSampler, texCoord + chromOffset).r;
    float gChannel = texture(DiffuseSampler, texCoord).g;
    float bChannel = texture(DiffuseSampler, texCoord - chromOffset).b;
    vec3 baseRGB = vec3(rChannel, gChannel, bChannel);

    float luma = dot(baseRGB, vec3(0.2126, 0.7152, 0.0722));

    // 2. Color Grading ZYGARDE:
    // - Sombras: Jade abisal y verde bosque profundo
    // - Medios: Esmeralda puro y turquesa
    // - Altas luces: Oro solar y blanco de núcleo celular
    vec3 cAbyssJade  = vec3(0.020, 0.080, 0.040);
    vec3 cMidEmerald = vec3(0.120, 1.250, 0.550);
    vec3 cCoreGold   = vec3(1.850, 1.650, 0.400);

    vec3 zygardeGrading = mix(cAbyssJade, cMidEmerald, smoothstep(0.05, 0.55, luma));
    zygardeGrading = mix(zygardeGrading, cCoreGold, smoothstep(0.50, 0.95, luma));

    vec3 graded = baseRGB * zygardeGrading * 1.60;

    // Saturación de vida y equilibrio
    vec3 satBoost = mix(vec3(luma), graded, 1.45);
    graded = mix(graded, satBoost, 0.65);

    // 3. Bloom en luces altas de los núcleos de Zygarde
    float bloomMask = pow(smoothstep(0.48, 1.0, luma), 2.2);
    vec3 cBloom = mix(vec3(0.10, 1.60, 0.70), vec3(1.70, 1.50, 0.35), 0.5 + 0.5 * sin(Time * 2.0));
    graded += cBloom * bloomMask * 0.65 * strength;

    // 4. Viñeta de contención y equilibrio ecológico
    float vignette = clamp(dot(centered, centered) * 0.45, 0.0, 0.50);
    graded = mix(graded, cAbyssJade * 0.65, vignette * strength);

    // Mezcla directa final
    vec3 sceneRaw = texture(DiffuseSampler, texCoord).rgb;
    vec3 finalRGB = mix(sceneRaw, clamp(graded, 0.0, 1.0), strength * 0.92);
    fragColor = vec4(finalRGB, 1.0);
}

