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

    // 1. Distorsión térmica y onda de choque radial en los bordes del embudo
    float shockwaveRadius = fract(Time * 0.40) * 0.85;
    float shockDist = abs(dist - shockwaveRadius);
    float shockFactor = exp(-pow(shockDist / 0.045, 2.0)) * (1.0 - shockwaveRadius);
    vec2 shockDir = normalize(centered + vec2(0.0001));
    vec2 distortedUV = texCoord + shockDir * (shockFactor * 0.015 + sin(dist * 25.0 - Time * 4.0) * 0.003) * strength;

    // 2. Desenfoque de movimiento radial agresivo en la periferia de la vista
    float blurWeight = smoothstep(0.18, 0.75, dist) * strength;
    vec3 radialSample = vec3(0.0);
    const int SAMPLES = 6;
    for (int i = 0; i < SAMPLES; i++) {
        float scale = 1.0 - float(i) * 0.008 * blurWeight;
        vec2 sampleUV = (distortedUV - 0.5) * scale + 0.5;
        radialSample += texture(DiffuseSampler, sampleUV).rgb;
    }
    radialSample /= float(SAMPLES);

    // 3. Aberración cromática ionizada verde jade y dorada
    vec2 chromOffset = shockDir * pixel * (6.0 * dist) * strength;
    float rCh = texture(DiffuseSampler, distortedUV + chromOffset).r;
    float gCh = radialSample.g;
    float bCh = texture(DiffuseSampler, distortedUV - chromOffset).b;
    vec3 baseRGB = vec3(rCh, gCh, bCh);

    float luma = dot(baseRGB, vec3(0.2126, 0.7152, 0.0722));

    // Colorimetría Rayquaza: Jade esmeralda, dorado y humo
    vec3 cSmoke    = vec3(0.04, 0.07, 0.05);
    vec3 cJade     = vec3(0.10, 1.15, 0.55);
    vec3 cGoldSheen= vec3(1.70, 1.45, 0.35);

    vec3 grading = mix(cSmoke, cJade, smoothstep(0.02, 0.50, luma));
    grading = mix(grading, cGoldSheen, smoothstep(0.48, 0.95, luma));
    vec3 graded = baseRGB * grading * 1.62;

    float vignette = clamp(dot(centered, centered) * 0.50, 0.0, 0.60);
    graded = mix(graded, cSmoke * 0.75, vignette * strength);

    vec3 sceneRaw = texture(DiffuseSampler, texCoord).rgb;
    vec3 finalRGB = mix(sceneRaw, clamp(graded, 0.0, 1.0), strength * 0.94);
    fragColor = vec4(finalRGB, 1.0);
}

