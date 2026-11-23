#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform vec2 OutSize;
uniform float Time;
uniform float BlurEnabled;
uniform float SwirlProgress;
uniform float SwirlActive;

in vec2 texCoord;
out vec4 fragColor;

// Generador de ruido procedural para grano orgánico de recuerdo
float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec2 aspect = InSize / max(OutSize, vec2(1.0));
    vec2 uv = texCoord;

    // ----------------------------------------------------
    // 1. TRANSICIÓN CELESTIAL DE MEMORIA (Sin distorsión mareante de swirl)
    // ----------------------------------------------------
    float transIntensity = 0.0;
    if (SwirlActive > 0.5) {
        float p = clamp(SwirlProgress, 0.0, 1.0);
        transIntensity = sin(p * 3.14159265);
    }

    // ----------------------------------------------------
    // 2. AURA DE RECUERDO: SOFT-FOCUS & DISPERSIÓN PRISMÁTICA SUAVE
    // ----------------------------------------------------
    vec3 baseColor;
    if (BlurEnabled > 0.5 || transIntensity > 0.01) {
        // Dispersión sutil inspirada en prismas de cuarzo y cristal
        vec2 radialVec = (uv - 0.5) * (0.0020 + transIntensity * 0.0045);

        float rSample = texture(DiffuseSampler, uv + radialVec * 1.15).r;
        vec3 gSample  = texture(DiffuseSampler, uv).rgb;
        float bSample = texture(DiffuseSampler, uv - radialVec * 1.15).b;

        vec3 centerCol = vec3(rSample, gSample.g, bSample);
        vec3 blurCol = (
            texture(DiffuseSampler, uv + vec2( 0.0012,  0.0010)).rgb +
            texture(DiffuseSampler, uv + vec2(-0.0012, -0.0010)).rgb +
            texture(DiffuseSampler, uv + vec2(-0.0010,  0.0012)).rgb +
            texture(DiffuseSampler, uv + vec2( 0.0010, -0.0012)).rgb
        ) * 0.25;

        float blurFactor = 0.20 + transIntensity * 0.35;
        baseColor = mix(centerCol, blurCol, blurFactor);
    } else {
        baseColor = texture(DiffuseSampler, uv).rgb;
    }

    // ----------------------------------------------------
    // 3. AURA GRISÁCEA DESATURADA & GRADUACIÓN CINEMATOGRÁFICA
    // ----------------------------------------------------
    float luma = dot(baseColor, vec3(0.2126, 0.7152, 0.0722));

    // Tonos plata, pizarra y perla:
    vec3 cShadow    = vec3(0.18, 0.19, 0.22);
    vec3 cMid       = vec3(0.92, 0.93, 0.95);
    vec3 cHighlight = vec3(1.08, 1.07, 1.03);

    vec3 toneGrading = mix(cShadow, cMid, smoothstep(0.0, 0.48, luma));
    toneGrading = mix(toneGrading, cHighlight, smoothstep(0.44, 0.95, luma));

    vec3 graded = baseColor * toneGrading;

    // Resplandor de ensueño (Pro-Mist / Ethereal Bloom)
    float bloomMask = pow(smoothstep(0.50, 1.0, luma), 2.2);
    vec3 dreamGlow = vec3(0.98, 1.02, 1.06);
    graded += dreamGlow * bloomMask * (0.22 + transIntensity * 0.20);

    // Desaturación cinematográfica elegante (Aura de memoria viva)
    float gradedLuma = dot(graded, vec3(0.2126, 0.7152, 0.0722));
    float satLevel = mix(0.40, 0.20, transIntensity);
    graded = mix(vec3(gradedLuma), graded, satLevel);

    // Viñeta óptica suave
    vec2 vCoord = (uv - 0.5) * aspect;
    float vignette = 1.0 - smoothstep(0.40, 0.98, length(vCoord)) * 0.35;
    graded *= vignette;

    // Velo etéreo suave durante el paso dimensional (sin fogonazo blanco cegador)
    if (transIntensity > 0.01) {
        vec3 veilTint = vec3(0.96, 0.94, 0.98); // Perla lavanda tenue
        graded = mix(graded, veilTint, transIntensity * 0.40);
    }

    // Micro-grano tenue de memoria analógica
    float grain = (hash(uv * 512.0 + vec2(sin(Time * 9.0), cos(Time * 7.0))) - 0.5) * 0.015;
    graded += vec3(grain);

    fragColor = vec4(clamp(graded, 0.0, 1.0), 1.0);
}
