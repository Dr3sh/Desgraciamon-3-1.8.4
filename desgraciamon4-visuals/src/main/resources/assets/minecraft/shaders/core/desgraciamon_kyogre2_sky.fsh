#version 150

uniform float Time;
uniform float Intensity;
uniform float EventTime;

in vec3 worldDir;
out vec4 fragColor;

// Hash 2D y 3D rapido
vec2 hash2(vec2 p) {
    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453);
}

vec3 hash3(vec3 p) {
    p = vec3(dot(p, vec3(127.1, 311.7, 74.7)),
             dot(p, vec3(269.5, 183.3, 246.1)),
             dot(p, vec3(113.5, 271.9, 124.6)));
    return fract(sin(p) * 43758.5453);
}

// Ruido 3D celular / Worley simulando cumulonimbos supercelulares y trombas marinas
float worley3D(vec3 p) {
    vec3 id = floor(p);
    vec3 f = fract(p);
    float minDist = 1.0;
    for (int z = -1; z <= 1; z++) {
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                vec3 cell = vec3(float(x), float(y), float(z));
                vec3 pt = hash3(id + cell);
                vec3 diff = cell + pt - f;
                minDist = min(minDist, length(diff));
            }
        }
    }
    return minDist;
}

// Ruido de gradiente 2D
float noise2D(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(dot(hash2(i + vec2(0.0, 0.0)) - 0.5, f - vec2(0.0, 0.0)),
                   dot(hash2(i + vec2(1.0, 0.0)) - 0.5, f - vec2(1.0, 0.0)), u.x),
               mix(dot(hash2(i + vec2(0.0, 1.0)) - 0.5, f - vec2(0.0, 1.0)),
                   dot(hash2(i + vec2(1.0, 1.0)) - 0.5, f - vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    mat2 rot = mat2(0.80, 0.60, -0.60, 0.80);
    for (int i = 0; i < 5; i++) {
        v += a * noise2D(p);
        p = rot * p * 2.04;
        a *= 0.5;
    }
    return v;
}

// Distancia euclidiana a segmento de linea
float sdSegment(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

float sdArc(vec2 p, float r, float startAngle, float endAngle) {
    float a = atan(p.y, p.x);
    float d = abs(length(p) - r);
    float mid = (startAngle + endAngle) * 0.5;
    float halfLen = (endAngle - startAngle) * 0.5;
    float da = abs(mod(a - mid + 3.14159265, 6.2831853) - 3.14159265);
    if (da <= halfLen) return d;
    vec2 p1 = r * vec2(cos(startAngle), sin(startAngle));
    vec2 p2 = r * vec2(cos(endAngle), sin(endAngle));
    return min(length(p - p1), length(p - p2));
}

// Simbolo ALPHA de Kyogre Primal
float evalAlphaSymbol(vec2 p) {
    vec2 pLoop = p - vec2(-0.035, 0.0);
    float dLoop = sdArc(pLoop, 0.082, 0.62, 5.66);

    vec2 pt1 = vec2(-0.035 + 0.082 * cos(0.62), 0.082 * sin(0.62));
    vec2 pt2 = vec2(-0.035 + 0.082 * cos(-0.62), 0.082 * sin(-0.62));

    float dTail1 = sdSegment(p, pt1, vec2(0.135, -0.095));
    float dTail2 = sdSegment(p, pt2, vec2(0.135, 0.095));

    float tip1 = length(p - vec2(0.135, -0.095)) - 0.007;
    float tip2 = length(p - vec2(0.135, 0.095)) - 0.007;

    return min(min(dLoop, min(dTail1, dTail2)) - 0.0055, min(tip1, tip2));
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    // Domo turbulento: pizarra y negro carbon abisal
    vec3 cHorizon = vec3(0.012, 0.025, 0.060);

    if (y <= 0.0) {
        float smoothProg = prog * prog * (3.0 - 2.0 * prog);
        fragColor = vec4(cHorizon, smoothProg);
        return;
    }

    // Coordenadas esterograficas
    vec2 p = dir.xz / (max(y, 0.0) + 0.28);
    float r = length(p);
    float angle = atan(p.y, p.x);
    float horizAngle = atan(dir.z, dir.x);

    float t = prog * 15.0;
    float stormAlpha = smoothstep(0.0, 3.0, t);
    float alphaAlpha = smoothstep(5.0, 9.0, t);

    // ------------------------------------------------------------------------
    // 1. CUMULONIMBOS SUPERCELULARES & VOLUMETRIC NOISE (3D WORLEY / DOWNBURSTS)
    // ------------------------------------------------------------------------
    // Muestreo 3D a alta velocidad simulando vórtices descendentes hacia el mar
    vec3 cloudCoord = vec3(dir.x * 2.5, dir.y * 3.5 - Time * 0.40, dir.z * 2.5);
    float wNoise = worley3D(cloudCoord * 1.8);
    float pNoise = fbm(p * 2.2 + vec2(Time * 0.15, -Time * 0.12));
    
    // Densidad supercelular con capas escalonadas
    float cloudDensity = clamp((1.0 - wNoise * 0.85) * 0.65 + pNoise * 0.55, 0.0, 1.0);
    float supercellMesh = smoothstep(0.28, 0.82, cloudDensity);

    // ------------------------------------------------------------------------
    // 2. RELAMPAGOS VOLUMETRICOS (80 LUX) & FORWARD RAYLEIGH SCATTERING
    // ------------------------------------------------------------------------
    float flashStep = floor(Time * 6.5);
    vec2 lHash = hash2(vec2(flashStep, 91.7));
    float lightningFlash = step(0.64, lHash.x);
    float flashPulse = pow(fract(Time * 6.5), 0.35); // caida exponencial brusca
    float currentLux = lightningFlash * (1.0 - flashPulse) * 4.5;

    // Centro del relampago en la nube
    vec3 lightPos = vec3(cos(lHash.y * 6.28), 0.45, sin(lHash.y * 6.28));
    float cosTheta = dot(dir, normalize(lightPos));
    
    // Dispersión de Rayleigh hacia adelante (Forward Scattering): nubes iluminadas desde dentro
    float rayleighPhase = (3.0 / (16.0 * 3.14159)) * (1.0 + cosTheta * cosTheta);
    float inCloudIllum = currentLux * exp(-pow((1.0 - cloudDensity) / 0.35, 2.0)) * rayleighPhase * 3.5;

    // Rayos en horquilla cortando la supercelula
    vec2 lDir2D = normalize(lightPos.xz);
    float lDist = sdSegment(p, lDir2D * 0.30, lDir2D * 1.35 + vec2(sin(p.y * 30.0) * 0.06, 0.0));
    float lightningBolt = exp(-pow(lDist / 0.0055, 2.0)) * lightningFlash * (1.0 - flashPulse);

    // ------------------------------------------------------------------------
    // 3. REFLEJO DE FRESNEL EN CRESTAS DE OLEAJE / NUBES ACUATICAS
    // ------------------------------------------------------------------------
    float viewAngle = max(dir.y, 0.0);
    float fresnel = pow(1.0 - viewAngle, 4.5);
    float waveCrests = abs(sin(r * 32.0 - Time * 3.0) * cos(angle * 12.0));
    float fresnelSpecular = fresnel * waveCrests * (1.0 + currentLux * 2.0);

    // ------------------------------------------------------------------------
    // 4. EL OJO DEL DILUVIO & SAGRADO SIMBOLO ALPHA
    // ------------------------------------------------------------------------
    float R_RING = 0.38;
    float inEye = 1.0 - smoothstep(R_RING - 0.04, R_RING, r);
    float dRing = abs(r - R_RING);
    float ringGlow = exp(-pow(dRing / 0.008, 2.0)) * 2.6 + exp(-pow(dRing / 0.035, 1.6)) * 0.8;

    float dAlpha = evalAlphaSymbol(p);
    float alphaCore = exp(-pow(max(dAlpha, 0.0) / 0.0042, 2.0)) * 2.8;
    float alphaMantle = exp(-pow(max(dAlpha, 0.0) / 0.022, 1.8)) * 1.4;
    float alphaPulse = 0.80 + 0.38 * sin(Time * 2.6) + 0.14 * sin(Time * 5.2);
    float alphaGodrays = abs(sin(angle * 8.0 - Time * 0.65)) * exp(-r * 3.5) * (0.8 + 0.4 * alphaPulse) * inEye;

    // Trombas marinas bajando hacia el mar y cáusticas oceánicas 360°
    float waterspout1 = exp(-pow(abs(horizAngle - 1.2 + sin(Time * 0.8) * 0.15) / 0.04, 2.0)) * smoothstep(0.04, 0.55, y);
    float waterspout2 = exp(-pow(abs(horizAngle + 2.1 + cos(Time * 0.9) * 0.15) / 0.04, 2.0)) * smoothstep(0.04, 0.55, y);
    float waterspout = waterspout1 + waterspout2;
    float oceanCaustics = pow(sin(p.x * 18.0 + Time * 2.0) * cos(p.y * 18.0 + Time * 2.4) * 0.5 + 0.5, 2.5) * exp(-r * 1.5);

    // ------------------------------------------------------------------------
    // 5. PALETA CROMATICA DE ALTA DEFINICION (ABYSSAL DELUGE)
    // ------------------------------------------------------------------------
    vec3 cNavyAbyss   = vec3(0.02, 0.05, 0.12);  // Azul marino profundo
    vec3 cSlateGray   = vec3(0.04, 0.07, 0.10);  // Gris pizarra
    vec3 cElectricCyan= vec3(0.40, 0.80, 1.00);  // Cian electrico (80 lux)
    vec3 cKyogreRed   = vec3(1.35, 0.15, 0.25);  // Carmesi Primal
    vec3 cWhiteLight  = vec3(1.10, 1.15, 1.20);

    // Fondo base con gradiente supercelular
    vec3 baseSky = mix(cHorizon, cNavyAbyss, smoothstep(0.0, 0.70, y));

    // Nubes iluminadas desde el interior por relampagos volumétricos
    vec3 cloudsShaded = mix(cSlateGray, cNavyAbyss * 1.4, supercellMesh);
    vec3 inCloudLight = (cElectricCyan * 2.2 + cWhiteLight * 1.5) * inCloudIllum;
    vec3 totalClouds  = (cloudsShaded + inCloudLight) * stormAlpha;

    // Rayos en horquilla y especularidad Fresnel en crestas
    vec3 boltCol    = lightningBolt * (cWhiteLight * 3.5 + cElectricCyan * 2.0);
    vec3 fresnelCol = fresnelSpecular * cElectricCyan * 1.2 * stormAlpha;
    vec3 spoutCol   = waterspout * (cNavyAbyss * 1.8 + cElectricCyan * 0.8) * stormAlpha;

    // Aro y Simbolo Alfa de Kyogre
    vec3 ringCol  = ringGlow * (cElectricCyan * 1.8 + cNavyAbyss * 1.2) * stormAlpha;
    vec3 alphaCol = ((alphaCore * cKyogreRed * 3.4 + alphaMantle * cElectricCyan * 2.0) * alphaPulse + alphaGodrays * cElectricCyan * 1.3) * alphaAlpha;

    vec3 causticsCol = oceanCaustics * cElectricCyan * 1.6 * stormAlpha;
    vec3 skyFinal = baseSky + totalClouds + boltCol + fresnelCol + spoutCol + ringCol + alphaCol + causticsCol;

    float horizonFade = smoothstep(0.0, 0.14, y);
    vec3 finalRGB = mix(cHorizon, skyFinal, horizonFade);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalRGB, smoothProg);
}

