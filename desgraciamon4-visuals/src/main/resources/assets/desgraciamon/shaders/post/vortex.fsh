#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform vec2 OutSize;
uniform float Time;
uniform float Intensity;

in vec2 texCoord;
out vec4 fragColor;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);

    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));

    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    mat2 rot = mat2(cos(0.5), sin(0.5), -sin(0.5), cos(0.5));
    for (int i = 0; i < 5; i++) {
        value += amplitude * noise(p);
        p = rot * p * 2.02 + vec2(1.7, 9.2);
        amplitude *= 0.5;
    }
    return value;
}

void main() {
    float effectStrength = clamp(Intensity, 0.0, 1.0);
    if (effectStrength <= 0.001) {
        fragColor = texture(DiffuseSampler, texCoord);
        return;
    }

    vec2 centered = texCoord - 0.5;
    float aspect = OutSize.x / max(OutSize.y, 1.0);
    vec2 aspectCentered = vec2(centered.x * aspect, centered.y);

    float radius = length(aspectCentered);
    float angle = atan(aspectCentered.y, aspectCentered.x);

    float swirlAngle = angle + (2.4 / (radius + 0.28)) - Time * 1.35;
    vec2 polarUV = vec2(
        cos(swirlAngle) * radius * 3.2,
        sin(swirlAngle) * radius * 3.2
    );

    vec2 q = vec2(
        fbm(polarUV + vec2(0.0, Time * 0.45)),
        fbm(polarUV + vec2(5.2, 1.3 - Time * 0.35))
    );
    float vortexEnergy = fbm(polarUV + 2.2 * q - vec2(Time * 0.25));

    float edgeMask = smoothstep(0.18, 0.92, radius);
    vec2 swirlOffset = vec2(-sin(angle), cos(angle)) * (vortexEnergy - 0.5) * 0.012 * edgeMask * effectStrength;
    vec2 distortedUV = clamp(texCoord + swirlOffset, 0.001, 0.999);

    vec4 sceneColor = texture(DiffuseSampler, distortedUV);

    vec3 abyssBlack = vec3(0.05, 0.0, 0.10);
    vec3 vortexPurple = vec3(0.5, 0.0, 0.8);
    vec3 neonMagenta = vec3(0.78, 0.18, 1.0);

    vec3 nebulaColor = mix(abyssBlack, vortexPurple, smoothstep(0.15, 0.75, vortexEnergy));
    nebulaColor = mix(nebulaColor, neonMagenta, pow(vortexEnergy, 3.0) * 0.65);

    float luma = dot(sceneColor.rgb, vec3(0.299, 0.587, 0.114));
    vec3 purpleTintedWorld = mix(sceneColor.rgb, vec3(luma) * vec3(0.75, 0.42, 1.05), 0.42);

    float vignette = smoothstep(0.22, 0.98, radius);
    vec3 combined = mix(purpleTintedWorld, nebulaColor, vignette * (0.48 + 0.32 * vortexEnergy));

    float pulse = 0.5 + 0.5 * sin(Time * 2.5 - radius * 8.0 + vortexEnergy * 4.0);
    combined += vortexPurple * pulse * vignette * 0.18;

    vec3 finalRGB = mix(sceneColor.rgb, combined, effectStrength);
    fragColor = vec4(finalRGB, 1.0);
}

