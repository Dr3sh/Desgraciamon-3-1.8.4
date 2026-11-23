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

    // 1. Refracción geodésica sutil en bordes (sensación de estar bajo una cúpula de cristal)
    vec2 refrOffset = vec2(sin(centered.y * 12.0 + Time), cos(centered.x * 12.0 + Time)) * pixel * 2.5 * strength * dist;
    vec2 uv = texCoord + refrOffset;

    // Dispersión cromática de gemas
    vec2 chromOffset = normalize(centered + vec2(0.0001)) * pixel * 3.8 * strength * dist;
    float rChannel = texture(DiffuseSampler, uv + chromOffset).r;
    float gChannel = texture(DiffuseSampler, uv).g;
    float bChannel = texture(DiffuseSampler, uv - chromOffset).b;
    vec3 baseRGB = vec3(rChannel, gChannel, bChannel);

    float luma = dot(baseRGB, vec3(0.2126, 0.7152, 0.0722));

    // 2. Destellos horizontales prismáticos de Geomancy
    float hSpread = pixel.x * 18.0 * strength;
    vec3 streak = texture(DiffuseSampler, uv + vec2(-hSpread * 2.0, 0.0)).rgb * 0.20;
    streak += texture(DiffuseSampler, uv + vec2(-hSpread, 0.0)).rgb * 0.30;
    streak += texture(DiffuseSampler, uv + vec2( hSpread, 0.0)).rgb * 0.30;
    streak += texture(DiffuseSampler, uv + vec2( hSpread * 2.0, 0.0)).rgb * 0.20;
    float streakLuma = dot(streak, vec3(0.2126, 0.7152, 0.0722));
    float streakMask = pow(smoothstep(0.42, 1.0, streakLuma), 2.2);

    vec3 cPrismFlare = mix(vec3(0.35, 1.10, 1.45), vec3(1.35, 0.85, 1.15), 0.5 + 0.5 * sin(Time * 1.8 + centered.x * 5.0));
    vec3 flareGlow = cPrismFlare * streakMask * 0.60;

    // 3. Bloom etéreo de bosque sagrado (Orton Glow)
    float ortonBloom = pow(smoothstep(0.30, 0.90, luma), 1.8);
    vec3 cOrtonLight = vec3(1.20, 1.15, 1.40) * ortonBloom * 0.50;

    // 4. Inyección cromática XERNEAS (Bosque de Cristales Sagrado & Geomancy)
    // - Sombras: Azul cerceta y verde salvia místico
    // - Medios: Cian diamantino y destellos magenta de pétalo sagrado
    // - Altas luces: Oro feérico perlado
    vec3 cShadowSage    = vec3(0.060, 0.160, 0.180);
    vec3 cMidBiolum     = vec3(0.350, 0.900, 1.100);
    vec3 cHighlightGold = vec3(1.450, 1.300, 0.850);

    vec3 xerneasGrading = mix(cShadowSage, cMidBiolum, smoothstep(0.02, 0.50, luma));
    xerneasGrading = mix(xerneasGrading, cHighlightGold, smoothstep(0.48, 0.95, luma));

    vec3 graded = baseRGB * xerneasGrading * 1.65;

    // Resaltar la vegetación (hiper-bioluminiscencia de hojas y flores)
    float greenDominance = clamp(baseRGB.g - max(baseRGB.r, baseRGB.b) * 0.85, 0.0, 1.0);
    vec3 floraBiolum = vec3(0.25, 1.45, 0.85) * (greenDominance * 2.2);
    graded += floraBiolum * smoothstep(0.08, 0.80, luma) * strength;

    // Pulso armónico de respiración de vida eterna
    float lifePulse = sin(Time * 2.4) * 0.04;
    graded += vec3(0.05, 0.10, 0.12) * lifePulse * strength;

    // Añadir destellos prismáticos y bloom
    graded += flareGlow + cOrtonLight;

    // 5. Viñeta mística suave de bosque de cuarzo en esquinas
    float vignette = clamp(dot(centered, centered) * 0.40, 0.0, 0.52);
    graded = mix(graded, cShadowSage * 0.75, vignette * strength);

    // Mezcla directa final
    vec3 sceneRaw = texture(DiffuseSampler, texCoord).rgb;
    vec3 finalRGB = mix(sceneRaw, clamp(graded, 0.0, 1.0), strength * 0.92);
    fragColor = vec4(finalRGB, 1.0);
}
