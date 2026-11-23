#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform vec2 OutSize;
uniform float Time;
uniform float Intensity;
uniform float EventTime;

in vec2 texCoord;
out vec4 fragColor;

// ============================================================================
// LUNALA: THE FULL MOON ECLIPSE - POST-PROCESSING PROGRAM
// Full viewport pipeline: Sensory deprivation, barrel distortion, heartbeat pulse
// ============================================================================

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

    float t = (EventTime > 0.0) ? EventTime : (strength * 10.0);

    // ------------------------------------------------------------------------
    // FASE 1: Caída de Luz a Cero Absoluto dentro de 1.5s
    // ------------------------------------------------------------------------
    float lightDrop = smoothstep(0.0, 1.5, t);

    // ------------------------------------------------------------------------
    // FASE 2: Distorsión de Lente en Barril (Barrel Distortion / Gravitational Vacuum)
    // ------------------------------------------------------------------------
    float barrelIntensity = smoothstep(2.5, 4.5, t);
    float barrelDistort = 1.0 + 0.16 * dist * dist * barrelIntensity * strength;
    vec2 barrelUV = (texCoord - 0.5) * barrelDistort + 0.5;

    // ------------------------------------------------------------------------
    // FASE 2: Latido Etéreo Rítmico (Ethereal Heartbeat Cadence)
    // Pulsación rítmica en dos golpes (lub-dub)
    // ------------------------------------------------------------------------
    float heartTime = t * 3.8;
    float beat1 = pow(max(sin(heartTime), 0.0), 6.0);
    float beat2 = pow(max(sin(heartTime - 0.55), 0.0), 8.0) * 0.70;
    float heartbeat = (beat1 + beat2) * smoothstep(2.5, 6.5, t);

    // ------------------------------------------------------------------------
    // Aberración Cromática Radial Pulsante outward con el latido
    // ------------------------------------------------------------------------
    vec2 chromDir = normalize(centered + vec2(0.0001));
    float chromMag = (6.0 * dist + 12.0 * dist * heartbeat) * strength;
    vec2 chromOffset = chromDir * pixel * chromMag;
    
    float rCh = texture(DiffuseSampler, barrelUV + chromOffset).r;
    float gCh = texture(DiffuseSampler, barrelUV).g;
    float bCh = texture(DiffuseSampler, barrelUV - chromOffset).b;
    vec3 baseRGB = vec3(rCh, gCh, bCh);

    float luma = dot(baseRGB, vec3(0.2126, 0.7152, 0.0722));

    // ------------------------------------------------------------------------
    // COLOR GRADING DUOTONO FRÍO: Desaturación total de tonos cálidos
    // ------------------------------------------------------------------------
    // Supresión agresiva de rojos/amarillos normales de la escena
    vec3 cColdShadows  = vec3(0.005, 0.008, 0.045); // Sombras índigo frío
    vec3 cNocturnalMid = vec3(0.080, 0.220, 0.650); // Medios azul noche
    vec3 cStarlightCyan= vec3(0.250, 1.250, 1.450); // Luces cian etéreo

    // Mapeo duotono
    vec3 duotoneGrading = mix(cColdShadows, cNocturnalMid, smoothstep(0.02, 0.52, luma));
    duotoneGrading = mix(duotoneGrading, cStarlightCyan, smoothstep(0.50, 0.95, luma));
    
    // Desaturar escena base y multiplicar por paleta fría
    vec3 desatScene = vec3(luma);
    vec3 graded = desatScene * duotoneGrading * 2.1;

    // Resplandor carmesí suave en las áreas de mayor oscuridad (luz lunar indirecta)
    graded += vec3(0.05, 0.005, 0.01) * (1.0 - luma) * smoothstep(3.0, 7.0, t);

    // ------------------------------------------------------------------------
    // Viñeta Nebulosa Púrpura Pulsante
    // ------------------------------------------------------------------------
    float edgeDist = clamp(dot(centered, centered) * 0.75, 0.0, 0.95);
    vec3 cNebulaPurple = vec3(0.70, 0.06, 0.60) * (0.85 + 0.50 * heartbeat);
    graded = mix(graded, cColdShadows * 0.90 + cNebulaPurple * 0.50, edgeDist * strength);

    // ------------------------------------------------------------------------
    // FASE 3: Onda Expansiva Silenciosa y Pulso Astral a 6.5s
    // ------------------------------------------------------------------------
    float shockPulse = smoothstep(6.35, 6.52, t) * (1.0 - smoothstep(6.65, 7.20, t));
    graded += vec3(0.1, 0.9, 1.2) * shockPulse * 0.75;

    vec3 sceneRaw = texture(DiffuseSampler, texCoord).rgb;
    vec3 finalRGB = mix(sceneRaw, clamp(graded, 0.0, 1.0), strength * 0.96);
    fragColor = vec4(finalRGB, 1.0);
}
