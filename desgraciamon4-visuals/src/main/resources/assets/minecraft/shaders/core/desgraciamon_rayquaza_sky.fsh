#version 150

uniform sampler2D Sampler0;
uniform float Time;
uniform float Intensity;
uniform float EventTime;

in vec3 worldDir;
out vec4 fragColor;

// Hash 2D rapido
vec2 hash2(vec2 p) {
    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453);
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

// FBM turbulento para nubes del megaciclon
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

// Segmento de linea para rayos y runas
float sdSegment(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

// Triangulo equilatero exacto para el Simbolo Delta
float sdEquilateralTriangle(vec2 p, float r) {
    const float k = 1.7320508; // sqrt(3.0)
    p.x = abs(p.x) - r;
    p.y = p.y + r / k;
    if (p.x + k * p.y > 0.0) {
        p = vec2(p.x - k * p.y, -k * p.x - p.y) * 0.5;
    }
    p.x -= clamp(p.x, -2.0 * r, 0.0);
    return -length(p) * sign(p.y);
}

// Evaluacion del Simbolo Delta (Cresta del Episodio Delta)
float evalDeltaSymbol(vec2 p) {
    vec2 pDelta = p - vec2(0.0, 0.025);
    float dOuterTri = abs(sdEquilateralTriangle(pDelta, 0.155)) - 0.007;

    vec2 pInner = vec2(pDelta.x, -(pDelta.y - 0.015));
    float dInnerTri = abs(sdEquilateralTriangle(pInner, 0.072)) - 0.0055;

    float dChevron1 = sdSegment(pDelta, vec2(-0.15, -0.09), vec2(0.0, -0.14));
    float dChevron2 = sdSegment(pDelta, vec2(0.15, -0.09), vec2(0.0, -0.14));
    float dChevron = min(dChevron1, dChevron2) - 0.004;

    float apexNodule = length(pDelta - vec2(0.0, 0.18)) - 0.012;
    float leftNodule = length(pDelta - vec2(-0.155, -0.09)) - 0.010;
    float rightNodule = length(pDelta - vec2(0.155, -0.09)) - 0.010;
    float nodules = min(apexNodule, min(leftNodule, rightNodule));

    return min(min(dOuterTri, dInnerTri), min(dChevron, nodules));
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    // Color del horizonte (Verde esmeralda ionizado oscuro)
    vec3 cHorizon = vec3(0.012, 0.050, 0.038);

    if (y <= 0.0) {
        float smoothProg = prog * prog * (3.0 - 2.0 * prog);
        fragColor = vec4(cHorizon, smoothProg);
        return;
    }

    // Coordenadas esterograficas del cenit
    vec2 p = dir.xz / (max(y, 0.0) + 0.28);
    float r = length(p);
    float angle = atan(p.y, p.x);

    // Coordenadas esfericas para el horizonte y cielo medio
    float horizAngle = atan(dir.z, dir.x);

    // ------------------------------------------------------------------------
    // CRONOLOGIA DE EVOLUCION CELESTIAL (15 SEGUNDOS):
    // 0.0s - 3.5s : El Megaciclon estratosferico se forma y enciende el Gran Aro del Ozono
    // 3.5s - 7.5s : En el espacio exterior del aro emerge el Sagrado Simbolo DELTA
    // 7.5s - 9.5s : El Simbolo Delta comienza a vibrar y condensar energia celestial
    // 9.5s - 10.8s: BLINDING CELESTIAL FLASH (Estallido de luz blanca/dorada/esmeralda)
    // 10.5s - 15.0s: Emerge plenamente el Simbolo oficial de MEGA-EVOLUCION con pulso vivo
    // ------------------------------------------------------------------------
    float t = prog * 15.0;

    float stormAlpha = smoothstep(0.0, 3.2, t);
    float deltaAlpha = smoothstep(3.0, 6.0, t) * (1.0 - smoothstep(9.5, 11.0, t));
    float flash = smoothstep(8.8, 9.8, t) * (1.0 - smoothstep(10.0, 11.2, t));
    float megaAlpha = smoothstep(9.8, 12.0, t);

    // ------------------------------------------------------------------------
    // 1. EL GRAN ARO DEL OZONO (BOUNDARY EYEWALL)
    // ------------------------------------------------------------------------
    float R_RING = 0.38;
    float dRing = abs(r - R_RING);

    float inSpace = 1.0 - smoothstep(R_RING - 0.04, R_RING, r);
    float inStorm = smoothstep(R_RING - 0.02, R_RING + 0.08, r);

    float ringCore = exp(-pow(dRing / 0.0075, 2.0)) * 2.8;
    float ringBroad = exp(-pow(dRing / 0.035, 1.6)) * 0.9;
    float innerGuard = exp(-pow(abs(r - (R_RING - 0.045)) / 0.0035, 2.0)) * 1.4;
    float outerGuard = exp(-pow(abs(r - (R_RING + 0.055)) / 0.004, 2.0)) * 1.4;

    float ringRunes = smoothstep(0.82, 0.98, sin(angle * 8.0 - Time * 0.45));
    float ringTicks = smoothstep(0.85, 0.99, sin(angle * 32.0 + Time * 0.25)) * smoothstep(0.025, 0.0, dRing);

    float totalRing = (ringCore + ringBroad + (innerGuard + outerGuard) * 0.6 + ringRunes * 1.8 * ringCore + ringTicks * 1.2) * stormAlpha;

    // ------------------------------------------------------------------------
    // 2. EL GRAN TORBELLINO DE NUBES Y EL CIELO MEDIO/HORIZONTE
    // ------------------------------------------------------------------------
    // Espiral ciclonica cenital
    float spiralAngle = angle - 2.6 * log(max(r, 0.01)) + Time * 0.50;
    vec2 spiralUV = vec2(r * 3.2, spiralAngle * 1.4);
    
    vec2 warp = vec2(fbm(spiralUV + Time * 0.15), fbm(spiralUV - Time * 0.20));
    float cloudDensity = fbm(spiralUV * 1.8 + warp * 2.0) * 0.70 + fbm(spiralUV * 3.5) * 0.30;
    cloudDensity = clamp(cloudDensity + 0.35, 0.0, 1.0);

    float eyewallCloud = smoothstep(0.35, 0.85, cloudDensity) * inStorm;
    float eyewallGlow  = exp(-pow((r - (R_RING + 0.08)) / 0.09, 2.0)) * cloudDensity * 1.5;

    // NUBES VOLUMETRICAS RODANTES EN EL HORIZONTE Y CIELO BAJO (Toda la cupula)
    vec2 horizUV = vec2(horizAngle * 3.0 + Time * 0.18, y * 8.0 - Time * 0.10);
    float horizonRoll = fbm(horizUV) * 0.65 + fbm(horizUV * 2.2 + vec2(Time * 0.05, 0.0)) * 0.35;
    float horizonCloudMask = smoothstep(0.02, 0.45, y) * (1.0 - smoothstep(0.40, 0.75, y));
    float activeHorizonClouds = smoothstep(0.35, 0.85, horizonRoll) * horizonCloudMask * stormAlpha;

    // AURORAS ESTRATOSFERICAS DEL OZONO ONDULANTES EN EL CIELO MEDIO
    float auroraWave = sin(dir.x * 6.0 + dir.z * 5.0 + sin(y * 10.0 + Time * 1.8)) * 0.5 + 0.5;
    float auroraCurtain = exp(-pow((y - 0.32 - 0.08 * sin(horizAngle * 3.0 + Time * 0.9)) / 0.12, 2.0));
    float auroras = auroraWave * auroraCurtain * (0.8 + 0.2 * sin(Time * 3.0)) * stormAlpha;

    // SILUETA SERPENTINA DRACONIANA SURCANDO LAS NUBES DISTANTES
    float dragonPhase = horizAngle + Time * 0.35;
    float dragonBodyDist = abs(y - 0.24 - 0.06 * sin(dragonPhase * 3.0) - 0.03 * cos(dragonPhase * 7.0));
    float dragonSilhouette = exp(-pow(dragonBodyDist / 0.022, 2.0)) * smoothstep(0.04, 0.50, y);
    float dragonRings = smoothstep(0.80, 0.98, sin(dragonPhase * 16.0)) * dragonSilhouette;

    // Rayos ionizados esmeralda dentro de la tormenta
    float lStep = floor(Time * 7.5);
    vec2 lHash = hash2(vec2(lStep, 137.4));
    float lActive = step(0.68, lHash.x);
    float lAng = lHash.y * 6.2831853;
    vec2 lDir = vec2(cos(lAng), sin(lAng));
    float lDist = sdSegment(p, lDir * (R_RING + 0.06), lDir * 1.15 + vec2(sin(p.y * 25.0) * 0.05, 0.0));
    float lightning = exp(-pow(lDist / 0.0055, 2.0)) * lActive * stormAlpha * inStorm;

    // Relampagos lejanos de horizonte (iluminacion difusa entre nubes)
    float horizonLightning = lActive * exp(-pow((y - 0.15) / 0.10, 2.0)) * (0.4 + 0.6 * sin(horizAngle * 4.0 + Time * 10.0));

    // Cintas de energia dorada ondeando en la estratosfera
    float ribbon1Wave = sin(p.x * 14.0 - Time * 3.2) * 0.035 + cos(p.y * 9.0 - Time * 1.8) * 0.02;
    float ribbonDist1 = abs(p.y - 0.40 * p.x - ribbon1Wave - 0.18);
    float ribbon1 = exp(-pow(ribbonDist1 / 0.006, 2.0)) * smoothstep(R_RING + 0.02, 1.1, r);

    float ribbon2Wave = cos(p.x * 12.0 + Time * 2.8) * 0.035 - sin(p.y * 10.0 + Time * 2.2) * 0.02;
    float ribbonDist2 = abs(p.y + 0.40 * p.x - ribbon2Wave + 0.18);
    float ribbon2 = exp(-pow(ribbonDist2 / 0.006, 2.0)) * smoothstep(R_RING + 0.02, 1.1, r);

    float allRibbons = (ribbon1 + ribbon2) * stormAlpha;

    // ------------------------------------------------------------------------
    // 3. EL ESPACIO PROFUNDO POR DENTRO DEL ARO (r < R_RING)
    // ------------------------------------------------------------------------
    vec2 starCell1 = floor(p * 95.0);
    vec2 starRnd1 = hash2(starCell1);
    float starBlink1 = sin(Time * 3.5 + starRnd1.x * 24.0) * 0.5 + 0.5;
    float stars1 = step(0.975, starRnd1.x) * starBlink1 * (0.6 + 0.4 * starRnd1.y) * inSpace;

    vec2 starCell2 = floor(p * 180.0);
    vec2 starRnd2 = hash2(starCell2);
    float stars2 = step(0.985, starRnd2.x) * 0.75 * inSpace;

    float nebula = fbm(p * 6.0 + vec2(Time * 0.05, -Time * 0.03)) * 0.35 * inSpace;

    // ------------------------------------------------------------------------
    // 4. EL SAGRADO SIMBOLO DELTA (FASE INICIAL)
    // ------------------------------------------------------------------------
    float dDelta = evalDeltaSymbol(p);
    float deltaCore = exp(-pow(max(dDelta, 0.0) / 0.004, 2.0)) * 2.6;
    float deltaGlow = exp(-pow(max(dDelta, 0.0) / 0.025, 1.8)) * 1.2;
    
    float pulseSpeed = mix(2.5, 14.0, smoothstep(6.5, 9.5, t));
    float deltaPulse = 0.85 + 0.35 * sin(Time * pulseSpeed);
    float deltaGodrays = abs(sin(angle * 6.0 - Time * 0.8)) * exp(-r * 4.0) * inSpace;

    // ------------------------------------------------------------------------
    // 5. EL SIMBOLO DE MEGA-EVOLUCION CON BRILLO PULSANTE CONTINUO
    // ------------------------------------------------------------------------
    float megaScale = 0.50;
    vec2 qMega = p / megaScale;
    qMega.y += sin(Time * 1.8) * 0.012; // Flotacion zenital suave

    vec2 megaUV = vec2(qMega.x + 0.5, 0.5 - qMega.y);
    vec4 megaTex = vec4(0.0);
    if (megaUV.x >= 0.0 && megaUV.x <= 1.0 && megaUV.y >= 0.0 && megaUV.y <= 1.0) {
        megaTex = texture(Sampler0, megaUV);
    }

    // BRILLO PULSANTE Y PALPITANTE (Respiracion cosmica de Mega Rayquaza)
    float megaPulse = 0.78 + 0.38 * sin(Time * 2.6) + 0.14 * sin(Time * 5.2);
    vec3 megaCol = megaTex.rgb * (megaTex.a * (2.0 + 1.6 * megaPulse) * megaAlpha);
    float megaCoreGlow = exp(-pow(r / 0.22, 2.0)) * (0.40 + 0.45 * megaPulse) * megaAlpha;

    float megaGodrays = abs(sin(angle * 12.0 - Time * 0.7)) * exp(-r * 3.2) * (0.7 + 0.5 * megaPulse) * inSpace;

    // ------------------------------------------------------------------------
    // 6. PALETA CROMATICA DE RAYQUAZA
    // ------------------------------------------------------------------------
    vec3 cZenithSpace = vec3(0.001, 0.003, 0.010);
    vec3 cCloudDark   = vec3(0.012, 0.040, 0.028);
    vec3 cCloudLight  = vec3(0.045, 0.240, 0.150);
    vec3 cEmerald     = vec3(0.08, 1.05, 0.48);
    vec3 cGoldRunes   = vec3(1.20, 0.92, 0.22);
    vec3 cOzoneCyan   = vec3(0.15, 0.85, 1.00);
    vec3 cFlamePlasma = vec3(1.10, 0.48, 0.08);
    vec3 cPureWhite   = vec3(1.10, 1.15, 1.08);

    // Fondo base
    vec3 baseSky = mix(cHorizon, cZenithSpace, smoothstep(0.0, 0.55, y));
    
    // Nubes del huracan y nubes del horizonte
    vec3 stormCloudsCol = mix(cCloudDark, cCloudLight, cloudDensity) * eyewallCloud * stormAlpha;
    vec3 eyewallRimCol  = eyewallGlow * cEmerald * 1.4 * stormAlpha;
    vec3 horizonCloudsCol = mix(cCloudDark * 1.3, cCloudLight * 0.8, horizonRoll) * activeHorizonClouds;

    // Auroras y silueta del dragon
    vec3 auroraCol = auroras * (cOzoneCyan * 1.6 + cEmerald * 1.2);
    vec3 dragonCol = (dragonSilhouette * cEmerald * 0.9 + dragonRings * cGoldRunes * 2.2) * stormAlpha;

    // Estrellas y nebulosa dentro del aro
    vec3 spaceCol = (stars1 * cPureWhite * 2.2 + stars2 * cOzoneCyan * 1.5 + nebula * cEmerald) * inSpace * stormAlpha;

    // El Aro sagrado del Ozono
    vec3 ringCol = totalRing * (cGoldRunes * 1.6 + cEmerald * 1.2);

    // Rayos y cintas dragon
    vec3 lightningCol = (lightning + horizonLightning * 0.4) * cPureWhite * 3.0;
    vec3 ribbonsCol   = allRibbons * (cGoldRunes * 2.2 + cFlamePlasma * 1.6);

    // Simbolo Delta
    vec3 deltaCol = ((deltaCore * cGoldRunes * 2.8 + deltaGlow * cEmerald * 1.5) * deltaPulse + deltaGodrays * cOzoneCyan * 0.8) * deltaAlpha;

    // Estallido cegador
    float burstSphere = exp(-pow(r / 0.24, 2.0));
    vec3 flashCol = flash * (burstSphere * (cPureWhite * 5.0 + cGoldRunes * 3.5 + cEmerald * 2.5));

    // Simbolo Mega-Evolucion pulsante
    vec3 megaSymbolCol = megaCol + (megaCoreGlow * cEmerald * 1.6 + megaGodrays * cOzoneCyan * 1.3) * megaAlpha;

    // Composicion final
    vec3 skyFinal = baseSky + stormCloudsCol + eyewallRimCol + horizonCloudsCol + auroraCol + dragonCol + spaceCol + ringCol + lightningCol + ribbonsCol + deltaCol + flashCol + megaSymbolCol;

    // Fundido con niebla del horizonte
    float horizonFade = smoothstep(0.0, 0.16, y);
    vec3 finalRGB = mix(cHorizon, skyFinal, horizonFade);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalRGB, smoothProg);
}
