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

    // 1. Aberración cromática de choque supersónico (viento violento en la estratosfera)
    vec2 chromDir = normalize(centered + vec2(0.0001));
    vec2 chromOffset = chromDir * pixel * (5.5 * dist) * strength;
    float rChannel = texture(DiffuseSampler, texCoord + chromOffset).r;
    float gChannel = texture(DiffuseSampler, texCoord).g;
    float bChannel = texture(DiffuseSampler, texCoord - chromOffset).b;
    vec3 baseRGB = vec3(rChannel, gChannel, bChannel);

    // 2. Desenfoque de velocidad horizontal estratosférica (viento huracanado cortando la pantalla)
    vec2 windDir = vec2(1.0, 0.08); // vector de viento dominante hacia la derecha
    vec3 sample1 = texture(DiffuseSampler, texCoord - windDir * pixel * 4.0 * strength).rgb;
    vec3 sample2 = texture(DiffuseSampler, texCoord + windDir * pixel * 4.0 * strength).rgb;
    vec3 blurredRGB = (baseRGB * 0.50 + sample1 * 0.25 + sample2 * 0.25);

    float luma = dot(blurredRGB, vec3(0.2126, 0.7152, 0.0722));

    // 3. Transformación de color RAYQUAZA (La Capa de Ozono y el Dragón Primal)
    // - Sombras: Verde esmeralda abisal y azul petróleo de la estratosfera
    // - Medios: Verde esmeralda eléctrico y cian puro del ozono
    // - Altas luces: Oro brillante incandescente de los filamentos de Mega Rayquaza
    vec3 cAbyssOzone   = vec3(0.030, 0.120, 0.140);
    vec3 cMidDragon    = vec3(0.150, 1.150, 0.650);
    vec3 cFilamentGold = vec3(1.750, 1.500, 0.400);

    vec3 rayquazaGrading = mix(cAbyssOzone, cMidDragon, smoothstep(0.02, 0.50, luma));
    rayquazaGrading = mix(rayquazaGrading, cFilamentGold, smoothstep(0.48, 0.95, luma));

    vec3 graded = blurredRGB * rayquazaGrading * 1.65;

    // Aumento de saturación vibrante en esmeraldas y dorados
    vec3 satBoost = mix(vec3(luma), graded, 1.55);
    graded = mix(graded, satBoost, 0.70);

    // 4. HDR Bloom en luces altas (los filamentos de luz dorada y el ojo del vórtice deslumbran)
    float bloomMask = pow(smoothstep(0.45, 1.0, luma), 2.2);
    vec3 cBloom = mix(vec3(0.20, 1.50, 0.85), vec3(1.65, 1.40, 0.35), 0.5 + 0.5 * sin(Time * 2.5));
    graded += cBloom * bloomMask * 0.70 * strength;

    // Destello de presión de viento en esquinas
    float vignette = clamp(dot(centered, centered) * 0.45, 0.0, 0.55);
    graded = mix(graded, cAbyssOzone * 0.75, vignette * strength);

    // Mezcla directa final con alto impacto visible
    vec3 sceneRaw = texture(DiffuseSampler, texCoord).rgb;
    vec3 finalRGB = mix(sceneRaw, clamp(graded, 0.0, 1.0), strength * 0.94);
    fragColor = vec4(finalRGB, 1.0);
}
