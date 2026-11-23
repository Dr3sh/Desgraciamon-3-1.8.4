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

    // 1. Desaturación sombría y fría característica del Mundo Distorsión
    vec3 desaturated = mix(rgb, vec3(luma), 0.38);

    // 2. Alto contraste siniestro
    vec3 contrasted = clamp(pow(desaturated, vec3(1.22)), 0.0, 1.0);
    float lumaContrast = dot(contrasted, vec3(0.2126, 0.7152, 0.0722));

    // 3. Tinte Giratina: Vacío abisal y morado oscuro en sombras, gris cenizo en medios y destello turquesa/azulado del vacío en altas luces
    vec3 voidAbyss   = vec3(0.018, 0.012, 0.025);
    vec3 shadowPurp  = vec3(0.18, 0.08, 0.28);
    vec3 ashGray     = vec3(0.55, 0.58, 0.62);
    vec3 voidGlimmer = vec3(0.35, 0.85, 0.90);

    vec3 giratinaTone = mix(voidAbyss, shadowPurp, smoothstep(0.08, 0.45, lumaContrast));
    giratinaTone = mix(giratinaTone, ashGray, smoothstep(0.40, 0.78, lumaContrast));
    giratinaTone = mix(giratinaTone, voidGlimmer, smoothstep(0.75, 0.98, lumaContrast));

    vec3 grade = contrasted * giratinaTone * 1.45;

    // Ondulación sutil de destellos del vacío
    float voidPulse = sin(Time * 1.8) * 0.015;
    grade += vec3(0.05, 0.12, 0.15) * voidPulse;

    // 4. Viñeta sombría en los bordes
    vec2 aspect = InSize / max(OutSize, vec2(1.0));
    vec2 centered = (texCoord - 0.5) * aspect;
    float distSq = dot(centered, centered) * 2.2;
    float vignette = clamp(distSq * 0.40, 0.0, 0.50);
    grade = mix(grade, voidAbyss, vignette);

    vec3 finalRGB = mix(rgb, clamp(grade, 0.0, 1.0), strength * 0.85);
    fragColor = vec4(finalRGB, 1.0);
}
