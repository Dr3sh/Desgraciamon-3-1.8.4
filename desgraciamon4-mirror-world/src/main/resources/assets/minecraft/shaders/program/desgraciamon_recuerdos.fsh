#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform vec2 OutSize;
uniform float Time;

in vec2 texCoord;
out vec4 fragColor;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Motas orgánicas con ciclo de vida suave (aparecen, flotan con deriva curva y se desvanecen sin loop visible)
float sampleOrganicDust(vec2 uv, vec2 scale, vec2 velocity, float timeSec, float seed) {
    vec2 scrollUv = uv * scale + velocity * timeSec;
    vec2 id = floor(scrollUv);
    vec2 f = fract(scrollUv) - 0.5;

    float h = hash12(id + vec2(seed, seed * 1.6180339));
    if (h > 0.76) {
        float lifeSpeed = 0.19 + hash12(id + vec2(7.13, 2.41)) * 0.23;
        float lifePhase = hash12(id + vec2(5.91, 8.37)) * 6.2831853;
        float lifeWave = 0.5 + 0.5 * sin(timeSec * lifeSpeed + lifePhase);
        float lifeEnv = smoothstep(0.18, 0.85, lifeWave);

        vec2 basePos = (vec2(
            hash12(id + vec2(1.15, 2.73)),
            hash12(id + vec2(3.42, 4.89))
        ) - 0.5) * 0.56;

        float swayAngle = timeSec * (0.45 + h * 0.35) + lifePhase;
        vec2 sway = vec2(sin(swayAngle), cos(swayAngle * 0.73)) * 0.07;

        vec2 diff = (f - (basePos + sway)) * vec2(1.0, InSize.y / max(InSize.x, 1.0));
        float dist = length(diff);

        float brightness = (h - 0.76) / 0.24;
        return smoothstep(0.042, 0.004, dist) * brightness * lifeEnv;
    }
    return 0.0;
}

// Bokeh dorado muy sutil con frecuencias inconmensurables para evitar sensación de repetición
float softEmberBokeh(vec2 uv, float timeSec, int idx) {
    float f = float(idx);
    float p1 = f * 1.6180339;
    float p2 = f * 2.7182818;

    float px = 0.5 + 0.42 * (
        sin(timeSec * 0.017 + p1) * 0.44 +
        sin(timeSec * 0.031 + p2 * 1.3) * 0.33 +
        cos(timeSec * 0.053 + p1 * 2.1) * 0.23
    );
    float py = 0.5 + 0.38 * (
        cos(timeSec * 0.013 + p2) * 0.44 +
        cos(timeSec * 0.029 + p1 * 1.7) * 0.33 +
        sin(timeSec * 0.047 + p2 * 2.3) * 0.23
    );

    vec2 d = (uv - vec2(px, py)) * vec2(InSize.x / max(InSize.y, 1.0), 1.0);
    float dist = length(d);

    float breathe = 0.5 + 0.5 * (
        sin(timeSec * (0.21 + f * 0.037) + p1) * 0.58 +
        cos(timeSec * (0.14 + f * 0.023) + p2) * 0.42
    );
    breathe = smoothstep(0.20, 0.95, breathe);

    float halo = exp(-dist * 58.0) * breathe * 0.55;
    float core = smoothstep(0.011, 0.002, dist) * breathe * 0.75;
    return halo + core;
}

void main() {
    vec2 uv = texCoord;
    float t = Time;

    // 1. Color base y bloom cálido suave
    vec3 col = texture(DiffuseSampler, uv).rgb;
    vec2 bOffset = vec2(0.0028, 0.0028);
    vec3 blurCol = (
        texture(DiffuseSampler, uv + vec2( bOffset.x,  bOffset.y)).rgb +
        texture(DiffuseSampler, uv + vec2(-bOffset.x, -bOffset.y)).rgb +
        texture(DiffuseSampler, uv + vec2( bOffset.x, -bOffset.y)).rgb +
        texture(DiffuseSampler, uv + vec2(-bOffset.x,  bOffset.y)).rgb +
        texture(DiffuseSampler, uv + vec2( 0.0,        bOffset.y * 1.4)).rgb +
        texture(DiffuseSampler, uv + vec2( 0.0,       -bOffset.y * 1.4)).rgb +
        texture(DiffuseSampler, uv + vec2( bOffset.x * 1.4, 0.0)).rgb +
        texture(DiffuseSampler, uv + vec2(-bOffset.x * 1.4, 0.0)).rgb
    ) * 0.125;

    float bloomThreshold = 0.42;
    vec3 lightBloom = max(vec3(0.0), blurCol - bloomThreshold) * 2.0;
    vec3 warmBloom = lightBloom * vec3(1.35, 0.95, 0.50);

    // 2. Filtro cinematográfico ámbar-naranja de recuerdo
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    vec3 orangeGraded = vec3(
        col.r * 1.38 + luma * 0.22,
        col.g * 0.98 + luma * 0.10,
        col.b * 0.38
    );

    vec3 graded = mix(col, orangeGraded, 0.88);
    graded += vec3(0.095, 0.052, 0.010) * (1.0 - luma * 0.45);
    graded += warmBloom * 0.62;

    // Viñeta cálida suave
    vec2 vigCoord = (uv - 0.5) * vec2(InSize.x / max(InSize.y, 1.0), 1.0);
    float vig = smoothstep(0.94, 0.36, length(vigCoord));
    graded *= mix(vec3(0.80, 0.70, 0.56), vec3(1.0), vig);

    // 3. Polvo flotante orgánico en tres planos con velocidades primas
    float dust = 0.0;
    dust += sampleOrganicDust(uv, vec2(9.0, 7.5), vec2(0.0041, -0.0137), t, 1.25);
    dust += sampleOrganicDust(uv, vec2(14.5, 12.0), vec2(-0.0033, -0.0091), t, 3.71);
    dust += sampleOrganicDust(uv, vec2(21.0, 17.5), vec2(0.0023, -0.0067), t, 7.19);
    graded += vec3(1.18, 0.92, 0.62) * dust * 0.095;

    // 4. Bokeh dorado sutil y aperiódico
    float embers = 0.0;
    for (int i = 0; i < 6; i++) {
        embers += softEmberBokeh(uv, t, i);
    }
    graded += vec3(1.20, 0.90, 0.44) * embers * 0.28;

    fragColor = vec4(clamp(graded, 0.0, 1.0), 1.0);
}
