#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform vec2 OutSize;
uniform float Time;
uniform float Intensity;

in vec2 texCoord;
out vec4 fragColor;

// Hash determinista para grano cinemático
float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    float strength = clamp(Intensity, 0.0, 1.0);
    if (strength <= 0.001) {
        fragColor = texture(DiffuseSampler, texCoord);
        return;
    }

    vec2 aspect = InSize / max(OutSize, vec2(1.0));
    vec2 centered = (texCoord - vec2(0.5, 0.38)) * aspect; // Ojo de succión elevado
    float dist = length(centered);

    // 1. Efecto Sifón de Almas: distorsión espiral hacia el cénit
    float angle = atan(centered.y, centered.x);
    float swirlAngle = angle + (0.08 / (dist + 0.15)) * strength;
    vec2 swirlDir = vec2(cos(swirlAngle), sin(swirlAngle));
    float pullAmount = pow(dist, 1.3) * 0.022 * strength;
    vec2 uvDistorted = texCoord - (swirlDir / aspect) * pullAmount;
    uvDistorted = clamp(uvDistorted, vec2(0.001), vec2(0.999));

    // Desfase cromático sangriento en los bordes
    vec2 redOffset = swirlDir * 0.0045 * strength * dist;
    float rChannel = texture(DiffuseSampler, uvDistorted + redOffset).r;
    float gChannel = texture(DiffuseSampler, uvDistorted).g;
    float bChannel = texture(DiffuseSampler, uvDistorted - redOffset).b;
    vec3 baseRGB = vec3(rChannel, gChannel, bChannel);

    float luma = dot(baseRGB, vec3(0.2126, 0.7152, 0.0722));

    // 2. Detección de tonos carmesí y fuego para preservarlos vivos
    float redDominance = baseRGB.r - max(baseRGB.g * 1.25, baseRGB.b * 0.90);
    float magentaDominance = min(baseRGB.r, baseRGB.b) - baseRGB.g * 1.40;
    float isBloodOrFire = smoothstep(0.05, 0.25, max(redDominance, magentaDominance));

    // 3. Transformación de color YVELTAL (El Juicio de la Muerte y la Ceniza)
    // - Mundo drenado a piedra basáltica gris fría (85% desaturado)
    // - Sombras: Obsidiana necrótica y violeta abisal
    // - Altas luces: Resplandor carmesí de lava de aniquilación
    vec3 cObsidianDark = vec3(0.040, 0.008, 0.018);
    vec3 cBasaltGray   = vec3(0.380, 0.320, 0.360);
    vec3 cBloodLava    = vec3(1.750, 0.100, 0.150);

    vec3 yveltalGrading = mix(cObsidianDark, cBasaltGray, smoothstep(0.02, 0.52, luma));
    yveltalGrading = mix(yveltalGrading, cBloodLava, smoothstep(0.55, 0.98, luma));

    vec3 stoneWorld = mix(baseRGB, vec3(luma), 0.85);
    vec3 graded = stoneWorld * yveltalGrading * 1.75;

    // Fuego y carmesí permanecen hiper-saturados y ardientes
    vec3 hyperBlood = vec3(pow(baseRGB.r, 0.75) * 1.70, baseRGB.g * 0.18, baseRGB.b * 0.32);
    graded = mix(graded, hyperBlood, isBloodOrFire * 0.95);

    // Contraste opresivo gótico
    graded = clamp(pow(graded, vec3(1.25)), 0.0, 1.0);

    // Pulso cardíaco visceral en toda la escena (lub-dub rítmico)
    float heartBeat = pow(clamp(sin(Time * 3.4), 0.0, 1.0), 10.0) * 0.075
                    + pow(clamp(sin(Time * 3.4 - 0.38), 0.0, 1.0), 14.0) * 0.055;
    graded += vec3(0.14, 0.010, 0.025) * heartBeat * strength;

    // 4. Grano de película de alta tensión cinematográfica
    vec2 grainUV = texCoord * InSize + vec2(sin(Time * 28.0) * 80.0, cos(Time * 28.0) * 80.0);
    float grain = (hash(grainUV) - 0.5) * 0.075 * strength;
    graded += vec3(grain) * smoothstep(0.04, 0.85, luma);

    // 5. Viñeta profunda de sangre de obsidiana
    float vignette = clamp(dot(centered, centered) * 0.55, 0.0, 0.80);
    graded = mix(graded, cObsidianDark * 0.85, vignette * strength);

    // Mezcla directa final con alto impacto
    vec3 sceneRaw = texture(DiffuseSampler, texCoord).rgb;
    vec3 finalRGB = mix(sceneRaw, clamp(graded, 0.0, 1.0), strength * 0.94);
    fragColor = vec4(finalRGB, 1.0);
}
