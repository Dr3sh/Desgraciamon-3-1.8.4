#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform vec2 OutSize;
uniform float Time;
uniform float Intensity;
uniform float EventTime;

in vec2 texCoord;
out vec4 fragColor;

// ============================================================================
// SOLGALEO: THE SOLAR CORONA RUPTURE - POST-PROCESSING PROGRAM
// Full viewport pipeline: Heat-haze, sharp exposure flare, gold LUT, blinding flash
// ============================================================================

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

    float t = (EventTime > 0.0) ? EventTime : (strength * 10.0);

    // ------------------------------------------------------------------------
    // FASE 1: 0.3s Sharp Exposure Flare (al segundo 1.0s - 1.3s)
    // ------------------------------------------------------------------------
    float p1Flare = smoothstep(0.95, 1.05, t) * (1.0 - smoothstep(1.20, 1.35, t)) * 2.5;

    // ------------------------------------------------------------------------
    // FASE 2: Distorsión de Calima Senoidal Multicapa (Custom Heat-Haze)
    // Se intensifica fuertemente alrededor de la columna central de luz
    // ------------------------------------------------------------------------
    float hazeIntensity = smoothstep(1.5, 4.0, t);
    float columnMask = exp(-pow(centered.x / 0.35, 2.0));
    
    float wave1 = sin(texCoord.y * 56.0 + Time * 8.5) * cos(texCoord.x * 38.0 + Time * 6.2);
    float wave2 = sin(texCoord.y * 110.0 - Time * 12.0) * 0.5;
    float totalHaze = (wave1 + wave2) * (0.0035 + 0.0045 * columnMask) * hazeIntensity * strength;
    
    vec2 heatOffset = vec2(totalHaze, totalHaze * 0.75);
    vec2 distortedUV = texCoord + heatOffset;

    // ------------------------------------------------------------------------
    // Aberración Cromática Térmica (separación espectral)
    // ------------------------------------------------------------------------
    vec2 chromDir = normalize(centered + vec2(0.0001));
    vec2 chromOffset = chromDir * pixel * (8.0 * dist * hazeIntensity + 2.0) * strength;
    float rCh = texture(DiffuseSampler, distortedUV + chromOffset).r;
    float gCh = texture(DiffuseSampler, distortedUV).g;
    float bCh = texture(DiffuseSampler, distortedUV - chromOffset).b;
    vec3 baseRGB = vec3(rCh, gCh, bCh);

    float luma = dot(baseRGB, vec3(0.2126, 0.7152, 0.0722));

    // ------------------------------------------------------------------------
    // COLOR GRADING LUT: Cobre Ámbar, Oro Solar Intenso y Sombras Razor-Sharp
    // ------------------------------------------------------------------------
    vec3 cDeepCopper   = vec3(0.14, 0.05, 0.015);
    vec3 cSolarGold    = vec3(1.85, 1.40, 0.32);
    vec3 cBlindingNoon = vec3(1.60, 1.45, 1.25);

    // Contraste solar opresivo: sombras afiladas y negras
    float highContrastLuma = pow(luma, 1.55);
    vec3 graded = mix(cDeepCopper, cSolarGold, smoothstep(0.02, 0.48, highContrastLuma));
    graded = mix(graded, cBlindingNoon, smoothstep(0.46, 0.94, highContrastLuma));
    graded = baseRGB * graded * 1.75;

    // ------------------------------------------------------------------------
    // Bloom Solar Incandescente Permanente en Altas Luces
    // ------------------------------------------------------------------------
    float bloomMask = pow(smoothstep(0.40, 0.98, luma), 2.4);
    vec3 cBloom = vec3(1.80, 1.35, 0.45) * bloomMask * 1.10 * strength;
    graded += cBloom;

    // ------------------------------------------------------------------------
    // Viñeta Pesada Naranja-Dorada Creeping from borders (Phase 2 & 3)
    // ------------------------------------------------------------------------
    float vignetteCreep = smoothstep(2.5, 6.0, t);
    float edgeDist = clamp(dot(centered, centered) * (0.70 + 0.30 * vignetteCreep), 0.0, 0.95);
    vec3 cOrangeVignette = vec3(1.40, 0.52, 0.08);
    graded = mix(graded, cOrangeVignette * 0.90 + cDeepCopper * 0.6, edgeDist * strength * 0.90);

    // Aplicar el flash de exposición de 0.3s de la Fase 1
    graded += vec3(p1Flare) * cSolarGold;

    // ------------------------------------------------------------------------
    // FASE 3: Flash Frame Cegador Total Blanco (0.4s en el segundo 6.45s – 6.85s)
    // ------------------------------------------------------------------------
    float flashFrame = smoothstep(6.35, 6.48, t) * (1.0 - smoothstep(6.75, 7.10, t));
    graded = mix(graded, vec3(3.5, 3.5, 3.2), flashFrame);

    vec3 sceneRaw = texture(DiffuseSampler, texCoord).rgb;
    vec3 finalRGB = mix(sceneRaw, clamp(graded, 0.0, 1.0), strength * 0.95);
    fragColor = vec4(finalRGB, 1.0);
}
