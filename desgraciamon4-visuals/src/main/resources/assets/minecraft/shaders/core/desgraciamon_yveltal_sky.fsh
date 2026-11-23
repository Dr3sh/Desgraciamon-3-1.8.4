#version 150

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

// Distancia euclidiana a segmento de linea
float sdSegment(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

// Rotacion 2D
vec2 rot2D(vec2 p, float a) {
    float s = sin(a), c = cos(a);
    return vec2(c * p.x - s * p.y, s * p.x + c * p.y);
}

// Renderiza una extremidad alada completa de Yveltal:
// Nace exactamente desde el origen (0,0) sin espacios vacíos,
// con membrana alada amplia, hueso principal y 5 garras afiladas en la punta.
vec3 renderYveltalLimb(vec2 p) {
    // 1. Brazo principal que nace EXACTAMENTE desde (0,0) hasta (0.0, 0.62)
    float boneDist = sdSegment(p, vec2(0.0, 0.0), vec2(0.0, 0.62));
    
    // 2. Membrana alada ancha (forma triangular / romboide que une el cuerpo con las alas)
    float wingWidth = clamp(p.y / 0.62, 0.0, 1.0) * 0.16 * (1.0 - clamp((p.y - 0.45) / 0.25, 0.0, 1.0));
    float membraneDist = max(abs(p.x) - wingWidth, 0.0);
    float inMembrane = step(abs(p.x), wingWidth) * step(0.0, p.y) * step(p.y, 0.62);

    // 3. Las 5 garras afiladas de la punta (nacen en 0.60 y se extienden curvadas hacia afuera)
    vec2 tipBase = vec2(0.0, 0.60);
    float claw1 = sdSegment(p, tipBase, tipBase + vec2( 0.00, 0.24)); // Garra central
    float claw2 = sdSegment(p, tipBase, tipBase + vec2(-0.09, 0.21)); // Garra interior izq
    float claw3 = sdSegment(p, tipBase, tipBase + vec2( 0.09, 0.21)); // Garra interior der
    float claw4 = sdSegment(p, tipBase + vec2(-0.03, -0.06), tipBase + vec2(-0.19, 0.14)); // Garra exterior izq
    float claw5 = sdSegment(p, tipBase + vec2( 0.03, -0.06), tipBase + vec2( 0.19, 0.14)); // Garra exterior der

    float minClaw = min(claw1, min(min(claw2, claw3), min(claw4, claw5)));

    // Retorna: x = grosor de hueso/garra, y = membrana alada, z = distancia minima total
    float allDist = min(boneDist, minClaw);
    return vec3(allDist, inMembrane, minClaw);
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    // Color del horizonte (Rojo sangre abisal oscuro / ceniza)
    vec3 cHorizon = vec3(0.065, 0.008, 0.012);

    if (y <= 0.0) {
        float smoothProg = prog * prog * (3.0 - 2.0 * prog);
        fragColor = vec4(cHorizon, smoothProg);
        return;
    }

    // Proyeccion esterografica
    vec2 p = dir.xz / (max(y, 0.0) + 0.30);
    float r = length(p);
    float angle = atan(p.y, p.x);

    // ------------------------------------------------------------------------
    // COREOGRAFIA SIMETRICA DE ENTRADA Y SALIDA (12 SEGUNDOS TOTALES):
    // 0.0 - 3.0s: El Gran Yveltal desciende: el torso y las 3 extremidades con 15 garras
    // 3.0 - 6.5s: Despertar del Capullo de Sangre y el Ojo de la Destrucción en el centro
    // 6.5 - 9.5s: Succión de fuerza vital y haz de Oblivion Wing (Ala Mortífera)
    // 9.5 - 12.0s: Grietas de magma negro, cenizas flotantes y relámpagos carmesí
    // En la salida (Intensity 1.0 -> 0.0): se reproduce exactamente a la inversa.
    // ------------------------------------------------------------------------
    float t = prog * 12.0;
    float wingsAlpha   = smoothstep(0.0, 3.0, t);
    float eyeAlpha     = smoothstep(2.5, 6.5, t);
    float oblvAlpha    = smoothstep(6.0, 9.5, t);
    float absorbAlpha  = smoothstep(9.0, 12.0, t);

    // ------------------------------------------------------------------------
    // 1. EL TORSO CENTRAL DE YVELTAL (CONEXIÓN TOTAL EN EL ORIGEN SIN HUECOS)
    // ------------------------------------------------------------------------
    // Núcleo central del cuerpo en (0,0)
    float bodyR = 0.12;
    float bodyCore = smoothstep(bodyR, 0.02, r);
    float bodyGlow = exp(-pow(r / 0.24, 2.0)) * 1.5;

    // ------------------------------------------------------------------------
    // 2. LAS 3 ALAS/EXTREMIDIDADES DE LA MUERTE (A 120 GRADOS - TOCAN EL CENTRO)
    // ------------------------------------------------------------------------
    vec3 limb1 = renderYveltalLimb(rot2D(p, 0.0));
    vec3 limb2 = renderYveltalLimb(rot2D(p, 2.094395));  // +120 grados
    vec3 limb3 = renderYveltalLimb(rot2D(p, -2.094395)); // -120 grados

    float minDistBone = min(limb1.x, min(limb2.x, limb3.x));
    float totalMembrane = clamp(limb1.y + limb2.y + limb3.y, 0.0, 1.0);
    float minDistClaws = min(limb1.z, min(limb2.z, limb3.z));

    // Trazo de los huesos y de las 15 garras afiladas
    float boneStroke = exp(-pow(minDistBone / 0.0075, 2.0)) * 3.5;
    float clawGlow   = exp(-pow(minDistClaws / 0.018, 2.0)) * 2.2;
    float limbGlow   = exp(-pow(minDistBone / 0.045, 1.8)) * 1.2;

    // Patrón de venas carmesí / sangre dentro de las membranas aladas
    float wingVeins = abs(sin(r * 32.0 - Time * 2.5 + angle * 6.0));
    float membraneColorPattern = (0.55 + 0.45 * smoothstep(0.60, 0.95, wingVeins)) * totalMembrane;

    // ------------------------------------------------------------------------
    // 3. EL ECLIPSE DE SANGRE Y EL OJO DEL CAPULLO
    // ------------------------------------------------------------------------
    // Pupila negra abisal devoradora
    float pupilR = 0.06;
    float pupil = smoothstep(pupilR, pupilR * 0.4, r);

    // Iris carmesí con filamentos ardientes
    float irisD = abs(r - 0.095);
    float irisFilaments = abs(sin(angle * 24.0 + Time * 0.8));
    float iris = exp(-pow(irisD / 0.010, 2.0)) * (0.9 + 0.7 * irisFilaments);

    // Latido cardiaco siniestro del capullo
    float heartBeat = 0.85 + 0.35 * pow(max(0.0, sin(Time * 3.8)), 8.0);
    float cocoonGlow = exp(-pow(r / 0.28, 2.0)) * 2.4 * heartBeat;

    // ------------------------------------------------------------------------
    // 4. OBLIVION WING (ALA MORTÍFERA) Y VÓRTICE DE DESTRUCCIÓN
    // ------------------------------------------------------------------------
    // Anillos de succión colapsando hacia el centro
    float succionPhase = fract(-Time * 0.55);
    float succionRing1 = exp(-pow(abs(r - succionPhase * 1.1) / 0.018, 2.0));
    float succionRing2 = exp(-pow(abs(r - fract(succionPhase + 0.5) * 1.1) / 0.018, 2.0));
    float suctionWaves = (succionRing1 + succionRing2) * clamp(1.2 - r, 0.0, 1.0);

    // Haz de rayo de la muerte perforando el centro
    float deathBeam = exp(-pow(r / 0.040, 2.0)) * 4.2;

    // Cenizas flotantes y brasas de magma negro
    vec2 ashCell = floor(p * 65.0 + vec2(0.0, -Time * 0.6));
    vec2 ashRnd = hash2(ashCell);
    float ash = step(0.980, ashRnd.x) * smoothstep(0.10, 0.95, r);

    // ------------------------------------------------------------------------
    // 5. PALETA CROMATICA DE YVELTAL
    // ------------------------------------------------------------------------
    vec3 cZenith       = vec3(0.005, 0.001, 0.002); // Abismo Negro Puro
    vec3 cBloodCrimson = vec3(1.20, 0.08, 0.16); // Rojo Carmesí Sangre
    vec3 cBlackFeather = vec3(0.05, 0.01, 0.02); // Negro Azabache Yveltal
    vec3 cMoltenOrange = vec3(1.15, 0.40, 0.05); // Naranja Magma Fuego
    vec3 cPureWhite    = vec3(1.05, 0.95, 0.95); // Destello Blanco/Rosa

    vec3 skyBase = mix(cHorizon, cZenith, smoothstep(0.0, 0.70, y));

    // 1. Nebulosa necrótica y nubes de tormenta carmesí en 360°
    float horizAngle = atan(dir.z, dir.x);
    vec2 stormUV = vec2(horizAngle * 3.5, y * 5.0) + vec2(Time * 0.04, -Time * 0.02);
    float necroticClouds = sin(stormUV.x * 2.5 + sin(stormUV.y * 3.0)) * cos(stormUV.y * 2.2);
    necroticClouds = necroticClouds * 0.5 + 0.5;

    // Relámpagos carmesí en las nubes lejanas
    float lPulse = step(0.85, hash2(vec2(floor(Time * 4.0), 3.14)).x) * exp(-fract(Time * 4.0) * 4.0);
    float lBolt = lPulse * exp(-pow(abs(sin(horizAngle * 6.0 + y * 8.0)) / 0.05, 2.0));

    // Torso central sólido (une todo en el origen)
    vec3 stormCol = (necroticClouds * cBloodCrimson * 0.35 + lBolt * cPureWhite * 2.0) * wingsAlpha;
    vec3 bodyCol = (bodyCore * cBlackFeather * 2.5 + bodyGlow * cBloodCrimson) * wingsAlpha;

    // Membranas aladas anchas
    vec3 membraneCol = membraneColorPattern * (cBlackFeather * 0.6 + cBloodCrimson * 0.7) * wingsAlpha;

    // Huesos de las extremidades y las 15 Garras
    vec3 limbsCol = (boneStroke * cPureWhite + clawGlow * cMoltenOrange * 2.0 + limbGlow * cBloodCrimson) * wingsAlpha;

    // Eclipse y Ojo del Capullo (con centro de pupila negra)
    vec3 eyeCol = (iris * cBloodCrimson * 2.8 + cocoonGlow * cMoltenOrange) * (1.0 - pupil * 0.98) * eyeAlpha;

    // Oblivion Wing y Ondas de Absorción
    vec3 oblvCol = (deathBeam * cPureWhite + suctionWaves * cBloodCrimson * 2.5) * oblvAlpha;

    // Cenizas flotantes
    vec3 ashCol = ash * cMoltenOrange * 2.0 * absorbAlpha;

    vec3 skyFinal = skyBase + stormCol + bodyCol + membraneCol + limbsCol + eyeCol + oblvCol + ashCol;

    // Fundido con niebla del horizonte
    float horizonFade = smoothstep(0.0, 0.16, y);
    vec3 finalRGB = mix(cHorizon, skyFinal, horizonFade);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog);
    fragColor = vec4(finalRGB, smoothProg);
}
