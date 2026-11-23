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

    // Aberracion cromatica espacial sutil
    vec2 offset = vec2(sin(Time * 0.5), cos(Time * 0.5)) * 0.0018 * strength;
    float rChannel = texture(DiffuseSampler, texCoord + offset).r;
    float gChannel = sceneColor.g;
    float bChannel = texture(DiffuseSampler, texCoord - offset).b;
    vec3 rgb = vec3(rChannel, gChannel, bChannel);

    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 desat = mix(rgb, vec3(luma), 0.35);

    // Tinte Palkia: Púrpura cósmico en sombras, fucsia/magenta en medios y perla nacarada en luces
    vec3 shadowPurple = vec3(0.22, 0.04, 0.32);
    vec3 midMagenta   = vec3(0.85, 0.12, 0.65);
    vec3 pearlLight   = vec3(1.10, 0.95, 1.15);

    vec3 palkiaTone = mix(shadowPurple, midMagenta, smoothstep(0.06, 0.55, luma));
    palkiaTone = mix(palkiaTone, pearlLight, smoothstep(0.50, 0.92, luma));

    vec3 grade = desat * palkiaTone * 1.35;

    // Resplandor nacarado suave
    float nacreBloom = pow(smoothstep(0.50, 1.0, luma), 2.0);
    grade += pearlLight * nacreBloom * 0.35;

    // Viñeta hiperespacial
    vec2 aspect = InSize / max(OutSize, vec2(1.0));
    vec2 centered = (texCoord - 0.5) * aspect;
    float distSq = dot(centered, centered) * 2.2;
    float vignette = clamp(distSq * 0.30, 0.0, 0.38);
    grade = mix(grade, shadowPurple * 0.6, vignette);

    vec3 finalRGB = mix(rgb, clamp(grade, 0.0, 1.0), strength * 0.85);
    fragColor = vec4(finalRGB, 1.0);
}

