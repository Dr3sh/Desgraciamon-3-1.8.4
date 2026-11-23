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
    vec3 desat = mix(rgb, vec3(luma), 0.62);

    vec3 deepShadowBlue = vec3(0.05, 0.18, 0.55);
    vec3 midCelestial   = vec3(0.20, 0.52, 1.10);
    vec3 highlightIce   = vec3(0.55, 0.85, 1.40);

    vec3 blueTone = mix(deepShadowBlue, midCelestial, smoothstep(0.05, 0.55, luma));
    blueTone = mix(blueTone, highlightIce, smoothstep(0.50, 0.95, luma));

    vec3 blueWorld = desat * blueTone * 1.65;
    blueWorld = mix(blueWorld, blueTone * (luma * 1.2 + 0.15), 0.45);

    float timeMod = sin(Time * 0.5) * 0.015;
    blueWorld += vec3(0.01, 0.02, 0.05) * timeMod;

    vec2 aspect = InSize / max(OutSize, vec2(1.0));
    vec2 centered = (texCoord - 0.5) * aspect;
    float distSq = dot(centered, centered) * 2.2;
    float vignette = clamp(distSq * 0.38, 0.0, 0.45);
    blueWorld = mix(blueWorld, deepShadowBlue * 0.4, vignette);

    vec3 finalRGB = mix(rgb, clamp(blueWorld, 0.0, 1.0), strength * 0.88);

    fragColor = vec4(finalRGB, 1.0);
}
