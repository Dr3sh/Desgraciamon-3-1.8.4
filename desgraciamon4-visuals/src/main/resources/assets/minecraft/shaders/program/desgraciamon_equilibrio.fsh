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

    // 1. Desaturacion sutil hacia blanco perla y plata eterea
    vec3 desat = mix(rgb, vec3(luma), 0.35);

    // 2. Filtro Bestia Equilibrio: Luz divina blanca, reflejos de platino y resplandor perlado
    vec3 platinumMist   = vec3(0.85, 0.88, 0.94);
    vec3 divineRadiance = vec3(1.12, 1.10, 1.08);

    vec3 ethereal = desat * platinumMist * 1.15;
    // Resplandor celestial en luces altas (bloom suave)
    float highlightBloom = pow(smoothstep(0.45, 1.0, luma), 2.0);
    ethereal += divineRadiance * highlightBloom * 0.45;

    // Sutil respiracion de luz
    float breathe = sin(Time * 0.8) * 0.015;
    ethereal += vec3(breathe);

    // 3. Vineta muy suave blanco-plateada en las esquinas
    vec2 aspect = InSize / max(OutSize, vec2(1.0));
    vec2 centered = (texCoord - 0.5) * aspect;
    float distSq = dot(centered, centered) * 2.2;
    float vignette = clamp(distSq * 0.25, 0.0, 0.30);
    ethereal = mix(ethereal, platinumMist * 0.85, vignette);

    // 4. Mezcla final segun Intensity
    vec3 finalRGB = mix(rgb, clamp(ethereal, 0.0, 1.0), strength * 0.80);
    fragColor = vec4(finalRGB, 1.0);
}

