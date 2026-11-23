#version 150

uniform float Time;
uniform float Intensity;

in vec3 worldDir;
out vec4 fragColor;

// Hash 2D ultra-rapido
vec2 hash2(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return -1.0 + 2.0 * fract((p3.xx + p3.yz) * p3.zy);
}

// Perlin noise suave
float perlin(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);

    float va = dot(hash2(i + vec2(0.0, 0.0)), f - vec2(0.0, 0.0));
    float vb = dot(hash2(i + vec2(1.0, 0.0)), f - vec2(1.0, 0.0));
    float vc = dot(hash2(i + vec2(0.0, 1.0)), f - vec2(0.0, 1.0));
    float vd = dot(hash2(i + vec2(1.0, 1.0)), f - vec2(1.0, 1.0));

    return mix(mix(va, vb, u.x), mix(vc, vd, u.x), u.y) * 0.5 + 0.5;
}

// FBM para ondas de seda cosmica
float waveFbm(vec2 p) {
    float v = 0.0;
    float a = 0.55;
    mat2 rot = mat2(0.80, 0.60, -0.60, 0.80);
    for (int i = 0; i < 4; i++) {
        v += perlin(p) * a;
        p = rot * p * 2.05 + vec2(5.3, 11.7);
        a *= 0.50;
    }
    return v;
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    // Color base de horizonte perlado plateado (funde 100% con FogRendererMixin)
    vec3 cHorizon = vec3(0.85, 0.88, 0.92);

    // Si mira por debajo del horizonte, extender niebla uniforme al suelo sin discontinuidades
    if (y <= 0.0) {
        float smoothProg = prog * prog * (3.0 - 2.0 * prog);
        fragColor = vec4(cHorizon, smoothProg);
        return;
    }

    // Coordenadas esterograficas proyectadas del cielo
    vec2 p = dir.xz / (max(y, 0.0) + 0.28);
    float r = length(p);
    float angle = atan(p.y, p.x);

    // Movimiento fluido majestuoso de las ondas
    float slowT = Time * 0.024;

    // Deformacion de dominio (Domain Warping) para curvas de seda y lineas de flujo magnetico
    vec2 warp1 = vec2(
        perlin(p * 1.8 + vec2(slowT * 0.8, -slowT * 0.6)),
        perlin(p * 1.8 + vec2(7.3 - slowT * 0.6, 2.1 + slowT * 0.8))
    );

    vec2 warpedP = p * 1.5 + (warp1 - 0.5) * 1.8;

    // Estructura curvilinea de la Bestia Equilibrio (arcos elegantes y simetricos)
    float baseFlow = waveFbm(warpedP * 1.4 + vec2(slowT * 0.5));
    
    // Curvas estratificadas y acanaladas (como en la referencia cinematografica)
    float curveAngle = angle * 2.0 + sin(r * 3.5 + baseFlow * 4.0 - slowT * 2.0);
    float ribLines = 0.5 + 0.5 * cos(curveAngle * 3.0 + r * 6.0 + baseFlow * 3.0);
    float fineSilk = 0.5 + 0.5 * sin(r * 18.0 + baseFlow * 8.0 + curveAngle * 2.0);

    // Ondas blancas de energia divina
    float divineWaves = smoothstep(0.20, 0.85, baseFlow * 0.55 + ribLines * 0.30 + fineSilk * 0.15);

    // Resplandor incandescente celestial en el centro/cenit
    float zenithCore = exp(-pow(r / 0.85, 2.0)) * 1.25;

    // Paleta de colores: Blanco alabastro, perla pura, plata y platino
    vec3 pureWhite      = vec3(1.08, 1.08, 1.10); // Blanco divino incandescente
    vec3 pearlIvory     = vec3(0.96, 0.97, 0.99); // Blanco perla
    vec3 silverMist     = vec3(0.82, 0.85, 0.90); // Plata eterea
    vec3 platinumShadow = vec3(0.68, 0.72, 0.78); // Sombras de platino en los pliegues

    // Composicion del cielo blanco con curvas
    vec3 sky = mix(platinumShadow, silverMist, smoothstep(0.15, 0.50, divineWaves));
    sky = mix(sky, pearlIvory, smoothstep(0.45, 0.82, divineWaves));
    sky += pureWhite * zenithCore * 0.65;
    sky += pureWhite * pow(divineWaves, 3.0) * 0.35;

    // Transicion limpia hacia la niebla del horizonte
    float horizonFade = smoothstep(0.0, 0.14, y);
    vec3 finalColor = mix(cHorizon, sky, horizonFade);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalColor, smoothProg);
}

