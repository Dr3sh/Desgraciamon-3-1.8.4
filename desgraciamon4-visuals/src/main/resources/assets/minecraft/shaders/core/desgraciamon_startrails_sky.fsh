#version 150

uniform float Time;
uniform float Intensity;

in vec3 worldDir;
out vec4 fragColor;

// Direccion del Polo Celeste (eje de rotacion de los star trails, inclinacion natural realista)
const vec3 POLE_AXIS = vec3(0.08022, 0.88241, -0.46358); // normalizado

float hash1(float n) {
    return fract(sin(n) * 43758.5453123);
}

vec3 hash3(float n) {
    vec3 p = fract(vec3(n) * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yzx + 33.33);
    return fract((p.xxy + p.yzz) * p.zyx);
}

/**
 * Atmosfera exclusivamente azul nocturna con transicion 100% continua hacia el suelo.
 */
vec3 computeAtmosphere(vec3 d) {
    float y = d.y;
    vec3 col;

    vec3 cZenith  = vec3(0.006, 0.014, 0.038); // Azul medianoche profundo abisal en el cenit
    vec3 cUpper   = vec3(0.014, 0.032, 0.085); // Azul real nocturno
    vec3 cMid     = vec3(0.028, 0.065, 0.160); // Azul zafiro celestial
    vec3 cHorizon = vec3(0.028, 0.065, 0.160); // Coincide 100% con la niebla de FogRendererMixin

    if (y > 0.55) {
        float t = (y - 0.55) / 0.45;
        col = mix(cUpper, cZenith, t * t);
    } else if (y > 0.22) {
        float t = (y - 0.22) / 0.33;
        col = mix(cMid, cUpper, smoothstep(0.0, 1.0, t));
    } else if (y > 0.0) {
        float t = y / 0.22;
        col = mix(cHorizon, cMid, smoothstep(0.0, 1.0, t));
    } else {
        // Por debajo del horizonte, extender el color de forma solida y uniforme
        // sin oscurecimientos ni caidas bruscas, eliminando cualquier silueta de "montanas" en el suelo.
        col = cHorizon;
    }

    return col;
}

/**
 * Evalua una capa de trazos circulares concentricos (Star Trails) de larga exposicion ultra-finos.
 */
vec3 evaluateTrailLayer(
    float theta,
    float rotPhi,
    float ringFreq,
    float seed,
    float minArc,
    float maxArc,
    float coreW,
    float haloW,
    float starThreshold,
    float brightness
) {
    vec3 accum = vec3(0.0);
    float rCoord = theta * ringFreq;
    float baseIdx = floor(rCoord);

    // Muestrear 2 anillos contiguos
    for (int i = 0; i <= 1; i++) {
        float idx = baseIdx + float(i);
        vec3 h = hash3(idx * 17.13 + seed);
        if (h.x < starThreshold) continue;

        float rJitter = (h.y - 0.5) * 0.74;
        float rCenter = (idx + 0.5 + rJitter) / ringFreq;
        float dr = abs(theta - rCenter);

        float numStars = floor(mix(2.0, 8.0, clamp(theta * 0.70, 0.0, 1.0)) + h.z * 2.0);
        float period = 6.2831853 / max(numStars, 1.0);

        float arcLen = mix(minArc, maxArc, hash1(idx * 9.27 + seed + 2.0));
        float phi0 = hash1(idx * 31.79 + seed + 5.0) * 6.2831853;

        float localPhi = mod(rotPhi - phi0, period);
        if (localPhi < arcLen) {
            float t = localPhi / arcLen; // 0.0 = cabeza, 1.0 = cola

            float headPoint = exp(-pow(localPhi / 0.0035, 2.0)) * 2.8;
            float tailFade = smoothstep(1.0, 0.65, t);
            float body = (1.0 - t * 0.20) * tailFade;
            float arcProfile = body + headPoint;

            float core = exp(-pow(dr / coreW, 2.0));
            float halo = exp(-pow(dr / haloW, 2.0)) * 0.22;
            float intensity = (core + halo) * arcProfile;

            vec3 col;
            if (h.z < 0.65) {
                col = vec3(0.95, 0.98, 1.0);  // Blanco diamante puro
            } else if (h.z < 0.88) {
                col = vec3(0.72, 0.88, 1.0);  // Azul hielo electrico
            } else {
                col = vec3(0.85, 0.92, 1.0);  // Azul plata celestial
            }

            accum += col * intensity * brightness;
        }
    }
    return accum;
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);
    float elev = dir.y;

    // 1. Atmosfera puramente azul nocturna con extension uniforme al suelo
    vec3 skyColor = computeAtmosphere(dir);

    // 2. Base ortonormal centrada en el Polo Celeste
    vec3 pole = POLE_AXIS;
    vec3 tangentU = normalize(cross(pole, vec3(0.0, 1.0, 0.0)));
    vec3 tangentV = cross(pole, tangentU);

    float px = dot(dir, tangentU);
    float py = dot(dir, tangentV);
    float pz = dot(dir, pole);

    float theta = acos(clamp(pz, -1.0, 1.0));
    float phi = atan(py, px);

    // 3. Rotacion suave y continua hacia un unico lado
    float rotSpeed = 0.038;
    float rotPhi = phi + Time * rotSpeed;

    // 4. Capas de Star Trails con trazos ultra-finos (finitos y nitidos)
    vec3 stars = vec3(0.0);

    // Capa 0: Estrellas principales
    stars += evaluateTrailLayer(
        theta, rotPhi,
        30.0, 101.3,
        0.40, 0.76,
        0.00045, 0.00160,
        0.42, 2.20
    );

    // Capa 1: Estrellas medianas
    stars += evaluateTrailLayer(
        theta, rotPhi,
        60.0, 203.7,
        0.24, 0.52,
        0.00030, 0.00105,
        0.52, 1.60
    );

    // Capa 2: Estelas densas finas
    stars += evaluateTrailLayer(
        theta, rotPhi,
        120.0, 407.1,
        0.15, 0.36,
        0.00018, 0.00065,
        0.62, 1.10
    );

    // Capa 3: Micro-trazos ultra-delgados
    stars += evaluateTrailLayer(
        theta, rotPhi,
        220.0, 811.5,
        0.08, 0.22,
        0.00010, 0.00035,
        0.68, 0.70
    );

    // Estrella polar en el centro exacto
    if (theta < 0.040) {
        float dPolaris = abs(theta - 0.015);
        float polarisArc = exp(-pow(dPolaris / 0.00035, 2.0)) * 2.5;
        float polarisHead = exp(-pow(theta / 0.0018, 2.0)) * 3.5;
        stars += vec3(0.95, 0.98, 1.0) * (polarisArc + polarisHead);
    }

    // Suavizado elegante hacia el horizonte para que las estrellas no toquen el suelo
    float horizonStarVis = smoothstep(0.005, 0.035, elev);
    stars *= horizonStarVis;

    // 5. Composicion final con mapeo de tonos
    vec3 combined = skyColor + stars;
    vec3 toneMapped = combined / (combined + vec3(0.15)) * 1.15;

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(toneMapped, smoothProg);
}

