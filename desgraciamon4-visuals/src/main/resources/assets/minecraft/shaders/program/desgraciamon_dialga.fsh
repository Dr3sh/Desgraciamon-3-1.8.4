#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform vec2 OutSize;
uniform float Time;
uniform float Intensity;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 sceneColor = texture(DiffuseSampler, texCoord);
    float strength = clamp(Intensity, 0.0, 1.0);
    if (strength <= 0.001) {
        fragColor = sceneColor;
        return;
    }

    vec3 rgb = sceneColor.rgb;
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));

    // 1. Desaturacion fria del flujo temporal
    vec3 desat = mix(rgb, vec3(luma), 0.35);

    // 2. Tinte Dialga: Azul cobalto profundo en sombras, cian diamante en medios y platino en luces
    vec3 shadowCobalt = vec3(0.05, 0.14, 0.45);
    vec3 midCyan      = vec3(0.12, 0.65, 0.95);
    vec3 highDiamond  = vec3(0.70, 0.98, 1.25);

    vec3 dialgaTone = mix(shadowCobalt, midCyan, smoothstep(0.08, 0.55, luma));
    dialgaTone = mix(dialgaTone, highDiamond, smoothstep(0.50, 0.95, luma));

    vec3 grade = desat * dialgaTone * 1.40;

    // 3. Estela difuminada azul de dilatación temporal (sensación de tiempo ralentizado)
    vec2 dirFromCenter = texCoord - 0.5;
    float rDist = length(dirFromCenter);
    vec2 blurDir = normalize(dirFromCenter + vec2(0.0001)) * (0.0030 + 0.0065 * rDist) * strength;

    vec3 sample1 = texture(DiffuseSampler, texCoord - blurDir * 1.2).rgb;
    vec3 sample2 = texture(DiffuseSampler, texCoord - blurDir * 2.5).rgb;
    vec3 sample3 = texture(DiffuseSampler, texCoord - blurDir * 4.0).rgb;

    // Tinte azul temporal para la estela de tiempo ralentizado
    vec3 cTimeStreak = vec3(0.15, 0.60, 1.25);
    vec3 ghostTrail = (sample1 * 0.45 + sample2 * 0.35 + sample3 * 0.20) * cTimeStreak;

    // Poca opacidad (toque translúcido sutil)
    float ghostOpacity = 0.26 * strength;
    grade = mix(grade, max(grade, ghostTrail), ghostOpacity);

    // Micro-pulsos de respiración cronológica
    float pulse = sin(Time * 3.14159) * 0.02;
    grade += vec3(0.0, 0.025, 0.055) * pulse;

    // 4. Viñeta de cristal en bordes
    vec2 aspect = InSize / max(OutSize, vec2(1.0));
    vec2 centered = (texCoord - 0.5) * aspect;
    float distSq = dot(centered, centered) * 2.2;
    float vignette = clamp(distSq * 0.32, 0.0, 0.38);
    grade = mix(grade, shadowCobalt * 0.45, vignette);

    // 5. Mezcla final
    vec3 finalRGB = mix(rgb, clamp(grade, 0.0, 1.0), strength * 0.85);
    fragColor = vec4(finalRGB, 1.0);
}
