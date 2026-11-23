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

    // Desaturacion parcial
    vec3 desaturated = mix(rgb, vec3(luma), 0.45);

    // Correccion de color morado y magenta dimensional
    vec3 shadowTint = vec3(0.12, 0.02, 0.22);
    vec3 highlightTint = vec3(0.95, 0.70, 1.20);
    vec3 purpleGraded = desaturated * highlightTint + shadowTint * (1.0 - luma) * 0.50;

    // Vineta morada en los bordes
    vec2 aspect = InSize / max(OutSize, vec2(1.0));
    vec2 centered = (texCoord - 0.5) * aspect;
    float edgeDist = dot(centered, centered) * 2.2;
    float vignette = clamp(edgeDist * 0.35, 0.0, 0.38);
    purpleGraded = mix(purpleGraded, purpleGraded * vec3(0.60, 0.30, 0.85), vignette);

    float breathe = sin(Time * 0.7) * 0.015;
    purpleGraded += vec3(0.02, 0.0, 0.03) * breathe;

    vec3 finalRGB = mix(rgb, clamp(purpleGraded, 0.0, 1.0), strength * 0.80);
    fragColor = vec4(finalRGB, 1.0);
}
