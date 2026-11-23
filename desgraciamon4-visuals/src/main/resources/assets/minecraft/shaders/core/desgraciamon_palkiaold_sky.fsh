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

// Ruido suave
float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(dot(hash2(i + vec2(0.0, 0.0)), f - vec2(0.0, 0.0)),
                   dot(hash2(i + vec2(1.0, 0.0)), f - vec2(1.0, 0.0)), u.x),
               mix(dot(hash2(i + vec2(0.0, 1.0)), f - vec2(0.0, 1.0)),
                   dot(hash2(i + vec2(1.0, 1.0)), f - vec2(1.0, 1.0)), u.x), u.y) * 0.5 + 0.5;
}

// FBM para turbulencia hiperespacial de gran escala
float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    mat2 rot = mat2(0.8, 0.6, -0.6, 0.8);
    for (int i = 0; i < 4; i++) {
        v += noise(p) * a;
        p = rot * p * 2.08 + vec2(5.4, 2.9);
        a *= 0.5;
    }
    return v;
}

// Distancia euclidiana a un segmento de línea
float sdSegment(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

// Curva central analítica y suave de la fisura dimensional en el hemisferio celeste
float getRiftCenterY(float x) {
    return 0.22 * x * x - 0.08 * sin(x * 2.8) - 0.12;
}

// Apertura equilibrada y afilada hacia las puntas (tamaño balanceado)
float getRiftAperture(float x, float openProg) {
    float spanProg = clamp(abs(x) / 0.85, 0.0, 1.0);
    // Tapering elíptico limpio: ancho en el centro, puntas afiladas
    float spanFactor = sqrt(max(1.0 - spanProg * spanProg, 0.0));
    return 0.15 * spanFactor * openProg;
}

// Labio superior dinámico: las ramificaciones superiores nacen exactamente aquí
vec2 getUpperLip(float x, float openProg) {
    return vec2(x, getRiftCenterY(x) + getRiftAperture(x, openProg));
}

// Labio inferior dinámico: las ramificaciones inferiores nacen exactamente aquí
vec2 getLowerLip(float x, float openProg) {
    return vec2(x, getRiftCenterY(x) - getRiftAperture(x, openProg));
}

// Renderiza estrellas fugaces hiperespaciales en el cosmos
vec3 renderHyperSpaceShootingStars(vec2 p) {
    float angleWarp = -0.55;
    mat2 rotWarp = mat2(cos(angleWarp), -sin(angleWarp), sin(angleWarp), cos(angleWarp));
    vec2 pWarp = rotWarp * p;

    float speed = Time * 4.5;
    vec2 grid = vec2(pWarp.x * 2.2, pWarp.y * 12.0);
    vec2 cellId = floor(vec2(grid.x, grid.y - speed * 0.3));
    vec2 cellFrac = fract(vec2(grid.x, grid.y - speed * 0.3)) - 0.5;

    vec2 cellHash = hash2(cellId);
    float hasStar = step(0.92, cellHash.x);

    float streakY = clamp(cellFrac.y + 0.5, 0.0, 1.0);
    float head = exp(-pow(length(vec2(cellFrac.x * 8.0, cellFrac.y - 0.25)) / 0.08, 2.0));
    float tail = exp(-pow(cellFrac.x * 12.0, 2.0)) * smoothstep(0.0, 0.85, streakY) * clamp(1.0 - streakY, 0.0, 1.0);

    float starVis = (head * 2.5 + tail * 1.2) * hasStar;
    vec3 cStarCol = mix(vec3(0.95, 0.98, 1.2), vec3(1.0, 0.45, 0.85), cellHash.y);

    return cStarCol * starVis * smoothstep(0.08, 0.85, length(p));
}

// Fractura dimensional errática de gran alcance: múltiples quiebres angulares y bifurcaciones asimétricas
float computeErraticBranch(
    vec2 p,
    vec2 origin,
    vec2 dir0,
    float totalLen,
    float startProg,
    float currentProg,
    float bend1, float frac1,
    float bend2, float frac2,
    vec2 fork1Dir, float fork1At, float fork1Len,
    vec2 fork2Dir, float fork2At, float fork2Len
) {
    if (currentProg <= startProg) return 10.0;
    float growth = clamp((currentProg - startProg) / max(1.0 - startProg, 0.01), 0.0, 1.0);
    float curLen = totalLen * smoothstep(0.0, 1.0, growth);

    // Longitudes de cada tramo
    float l1 = totalLen * frac1;
    float l2 = totalLen * (frac2 - frac1);
    float l3 = totalLen * (1.0 - frac2);

    // Segmento 1
    float cur1 = clamp(curLen, 0.0, l1);
    vec2 p1 = origin + dir0 * cur1;
    float d = sdSegment(p, origin, p1);

    // Segmento 2 con quiebre errático 1
    mat2 rot1 = mat2(cos(bend1), -sin(bend1), sin(bend1), cos(bend1));
    vec2 dir1 = rot1 * dir0;
    if (curLen > l1) {
        float cur2 = clamp(curLen - l1, 0.0, l2);
        vec2 p1Fixed = origin + dir0 * l1;
        vec2 p2 = p1Fixed + dir1 * cur2;
        d = min(d, sdSegment(p, p1Fixed, p2));

        // Segmento 3 con quiebre errático 2 que se extiende lejos en el cosmos
        mat2 rot2 = mat2(cos(bend2), -sin(bend2), sin(bend2), cos(bend2));
        vec2 dir2 = rot2 * dir1;
        if (curLen > (l1 + l2)) {
            float cur3 = clamp(curLen - (l1 + l2), 0.0, l3);
            vec2 p2Fixed = p1Fixed + dir1 * l2;
            vec2 p3 = p2Fixed + dir2 * cur3;
            d = min(d, sdSegment(p, p2Fixed, p3));
        }
    }

    // Bifurcación errática 1 (dispara una ramificación secundaria asimétrica)
    if (growth > fork1At) {
        float f1Prog = (growth - fork1At) / (1.0 - fork1At);
        vec2 f1Origin = origin + dir0 * min(curLen, totalLen * fork1At);
        vec2 f1Tip = f1Origin + fork1Dir * (fork1Len * smoothstep(0.0, 1.0, f1Prog));
        d = min(d, sdSegment(p, f1Origin, f1Tip));
    }

    // Bifurcación errática 2 (más lejana y afilada)
    if (growth > fork2At) {
        float f2Prog = (growth - fork2At) / (1.0 - fork2At);
        vec2 p1Fixed = origin + dir0 * l1;
        vec2 f2Origin = p1Fixed + dir1 * min(max(curLen - l1, 0.0), totalLen * (fork2At - frac1));
        vec2 f2Tip = f2Origin + fork2Dir * (fork2Len * smoothstep(0.0, 1.0, f2Prog));
        d = min(d, sdSegment(p, f2Origin, f2Tip));
    }

    // Micro-aristas filosas de cristal quebrado
    float razorJitter = (noise(p * 20.0) - 0.5) * 0.0035;
    return d + razorJitter;
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        discard;
    }

    vec3 dir = normalize(worldDir);
    float y = dir.y;

    // Color del horizonte (magenta violáceo cósmico - coincide 100% con FogRendererMixin)
    vec3 cHorizon = vec3(0.140, 0.030, 0.160);

    // Si mira por debajo del horizonte, extender niebla uniforme al suelo sin discontinuidades
    if (y <= 0.0) {
        float smoothProg = prog * prog * (3.0 - 2.0 * prog);
        fragColor = vec4(cHorizon, smoothProg);
        return;
    }

    // Proyección del cielo fija en el mundo
    vec2 p = dir.xz / (max(y, 0.0) + 0.30);

    // ------------------------------------------------------------------------
    // ANIMACIÓN SIMÉTRICA REVERSIBLE: APERTURA Y CIERRE EN 15 SEGUNDOS EXACTOS
    // - Al aparecer: prog va de 0.0 -> 1.0 (se abre y ramifica)
    // - Al desaparecer: prog va de 1.0 -> 0.0 (se cierra en reversa exactamente igual)
    // ------------------------------------------------------------------------
    float openProg = prog;

    // Pequeño temblor/resquebrajamiento que ocurre al abrirse y al sellarse
    float shudder = 0.006 * sin(Time * 32.0) * (1.0 - openProg * 0.7);
    float smoothOpen = clamp(openProg + shudder * (1.0 - openProg), 0.0, 1.0);

    // ------------------------------------------------------------------------
    // 1. FONDO CÓSMICO DEL ESPACIO PROFUNDO (PALKIA)
    // ------------------------------------------------------------------------
    vec3 cDeepSpace = vec3(0.018, 0.006, 0.038);
    vec3 skyBase = mix(cHorizon, cDeepSpace, smoothstep(0.0, 0.65, y));

    // Nebulosas espaciales de fondo en tonos perla, rosa y violeta
    float nebFbm = fbm(p * 1.8 + vec2(sin(Time * 0.03), cos(Time * 0.03)) * 0.2);
    vec3 cNebula = mix(vec3(0.22, 0.04, 0.28), vec3(0.08, 0.02, 0.18), nebFbm);
    skyBase += cNebula * smoothstep(0.08, 0.85, y) * 0.75;

    // Estrellas fugaces en el cosmos exterior
    vec3 shootingStars = renderHyperSpaceShootingStars(p);

    // ------------------------------------------------------------------------
    // 2. GEOMETRÍA DE LA FISURA DIMENSIONAL (EQUILIBRADA, AFILADA Y ELEGANTE)
    // ------------------------------------------------------------------------
    float riftCenterY = getRiftCenterY(p.x);
    float baseAperture = getRiftAperture(p.x, smoothOpen);

    // Ondulación orgánica sutil en los labios
    float edgeWave = 0.008 * sin(p.x * 9.0 + Time * 2.0);
    float effectiveAperture = max(baseAperture + (openProg > 0.02 && baseAperture > 0.001 ? edgeWave : 0.0), 0.0);

    float distToCenter = abs(p.y - riftCenterY);
    float distToLip = abs(distToCenter - effectiveAperture);

    // ------------------------------------------------------------------------
    // 3. RAMIFICACIONES ERRÁTICAS Y DE GRAN ALCANCE (SALEN DE LOS LABIOS HACIA AFUERA)
    // ------------------------------------------------------------------------
    // Labio superior: ramificaciones erráticas que viajan lejos por la cúpula celeste
    float b1 = computeErraticBranch(p, getUpperLip(-0.70, smoothOpen), normalize(vec2(-0.85, 0.52)), 1.10, 0.05, openProg,
                                   0.55, 0.35, -0.70, 0.70, normalize(vec2(-0.45, 0.89)), 0.30, 0.50, normalize(vec2(-0.95, 0.31)), 0.65, 0.40);
    float b2 = computeErraticBranch(p, getUpperLip(-0.40, smoothOpen), normalize(vec2(-0.35, 0.94)), 1.30, 0.12, openProg,
                                  -0.62, 0.32,  0.75, 0.68, normalize(vec2( 0.55, 0.83)), 0.25, 0.60, normalize(vec2(-0.85, 0.52)), 0.55, 0.48);
    float b3 = computeErraticBranch(p, getUpperLip(-0.10, smoothOpen), normalize(vec2( 0.08, 0.99)), 1.35, 0.20, openProg,
                                   0.48, 0.30, -0.58, 0.65, normalize(vec2(-0.65, 0.75)), 0.35, 0.55, normalize(vec2( 0.80, 0.60)), 0.70, 0.45);
    float b4 = computeErraticBranch(p, getUpperLip( 0.25, smoothOpen), normalize(vec2( 0.42, 0.91)), 1.25, 0.28, openProg,
                                  -0.52, 0.38,  0.65, 0.72, normalize(vec2( 0.85, 0.52)), 0.30, 0.55, normalize(vec2( 0.15, 0.98)), 0.60, 0.42);
    float b5 = computeErraticBranch(p, getUpperLip( 0.60, smoothOpen), normalize(vec2( 0.82, 0.57)), 1.15, 0.36, openProg,
                                   0.68, 0.34, -0.55, 0.66, normalize(vec2( 0.35, 0.94)), 0.28, 0.52, normalize(vec2( 0.96, 0.28)), 0.62, 0.40);

    // Labio inferior: ramificaciones erráticas que viajan hacia abajo/afuera
    float b6 = computeErraticBranch(p, getLowerLip(-0.70, smoothOpen), normalize(vec2(-0.80, -0.60)), 1.10, 0.08, openProg,
                                  -0.55, 0.32,  0.68, 0.68, normalize(vec2(-0.95, -0.31)), 0.32, 0.48, normalize(vec2(-0.45, -0.89)), 0.65, 0.38);
    float b7 = computeErraticBranch(p, getLowerLip(-0.35, smoothOpen), normalize(vec2(-0.32, -0.95)), 1.30, 0.16, openProg,
                                   0.65, 0.35, -0.72, 0.70, normalize(vec2(-0.82, -0.57)), 0.28, 0.58, normalize(vec2( 0.40, -0.92)), 0.58, 0.45);
    float b8 = computeErraticBranch(p, getLowerLip( 0.05, smoothOpen), normalize(vec2(-0.06, -0.99)), 1.35, 0.24, openProg,
                                  -0.50, 0.30,  0.60, 0.65, normalize(vec2( 0.65, -0.76)), 0.34, 0.55, normalize(vec2(-0.70, -0.71)), 0.68, 0.42);
    float b9 = computeErraticBranch(p, getLowerLip( 0.35, smoothOpen), normalize(vec2( 0.40, -0.92)), 1.25, 0.32, openProg,
                                   0.58, 0.36, -0.62, 0.70, normalize(vec2( 0.85, -0.53)), 0.30, 0.52, normalize(vec2(-0.15, -0.99)), 0.62, 0.40);
    float b10 = computeErraticBranch(p, getLowerLip( 0.65, smoothOpen), normalize(vec2( 0.78, -0.63)), 1.15, 0.40, openProg,
                                   -0.65, 0.35,  0.55, 0.68, normalize(vec2( 0.42, -0.91)), 0.28, 0.48, normalize(vec2( 0.96, -0.28)), 0.65, 0.38);

    // Puntas extremas laterales que rasgan el espacio hacia los lados
    float b11 = computeErraticBranch(p, vec2(-0.85, getRiftCenterY(-0.85)), normalize(vec2(-0.98,  0.18)), 1.00, 0.10, openProg,
                                    0.45, 0.35, -0.60, 0.70, normalize(vec2(-0.85,  0.52)), 0.30, 0.48, normalize(vec2(-0.85, -0.52)), 0.60, 0.40);
    float b12 = computeErraticBranch(p, vec2( 0.85, getRiftCenterY( 0.85)), normalize(vec2( 0.98, -0.18)), 1.00, 0.14, openProg,
                                   -0.45, 0.35,  0.60, 0.70, normalize(vec2( 0.85, -0.52)), 0.30, 0.48, normalize(vec2( 0.85,  0.52)), 0.60, 0.40);

    float minUpper = min(min(min(b1, b2), b3), min(b4, b5));
    float minLower = min(min(min(b6, b7), b8), min(b9, b10));
    float minEnds  = min(b11, b12);
    float minBranchDist = min(min(minUpper, minLower), minEnds);

    // ------------------------------------------------------------------------
    // 4. PALETA DE COLORES: FILO DE CORTE ESPACIAL (SPACIAL REND)
    // ------------------------------------------------------------------------
    vec3 cPearlWhite   = vec3(1.65, 1.55, 1.70); // Blanco perla incandescente
    vec3 cMagentaSlash = vec3(1.30, 0.06, 0.78); // Fucsia/Magenta puro Spacial Rend
    vec3 cCyanShard    = vec3(0.35, 0.98, 1.35); // Destello cian electrizante

    // Resplandor ardiente pero afilado de los labios
    float lipCore = exp(-pow(distToLip / 0.0022, 1.8));
    float lipEdge = exp(-pow(distToLip / 0.0100, 1.8));
    float lipAura = exp(-pow(distToLip / 0.0450, 1.6));

    // Destellos afilados de resquebrajamiento a lo largo de los labios
    float crackFlicker = pow(abs(sin(Time * 30.0 + p.x * 24.0)), 12.0) * (1.1 - 0.55 * openProg);
    float edgeSparks = exp(-pow(distToLip / 0.0024, 2.0)) * crackFlicker * 3.8;
    float continuousGlint = exp(-pow(distToLip / 0.0020, 2.0)) * (0.5 + 0.5 * sin(Time * 36.0 + p.x * 60.0)) * 2.5;

    float riftActiveMask = smoothstep(0.95, 0.70, abs(p.x));
    vec3 lipColor = ((lipCore * 4.2 + (edgeSparks + continuousGlint) * 3.2) * cPearlWhite
                  + lipEdge * mix(cMagentaSlash, cCyanShard, 0.50) * 2.8
                  + lipAura * cMagentaSlash * 1.0) * riftActiveMask;

    // Resplandor de ramificaciones afiladas ("filosas", estilo fractura cristalina fina)
    float branchFlicker = 0.90 + 0.10 * sin(Time * 32.0 + minBranchDist * 140.0);
    float branchCore = exp(-pow(minBranchDist / 0.0020, 1.6)) * 4.2 * branchFlicker;
    float branchEdge = exp(-pow(minBranchDist / 0.0070, 1.6)) * 2.0 * branchFlicker;
    float branchAura = exp(-pow(minBranchDist / 0.0240, 1.4)) * 0.45;

    vec3 branchColor = (branchCore * cPearlWhite + branchEdge * cCyanShard + branchAura * cMagentaSlash) * 1.6;

    // Composición del cielo exterior (sin planeta: cosmos desgarrado con nebulosas y estrellas)
    vec3 outerSky = skyBase + shootingStars + lipColor + branchColor;

    // ------------------------------------------------------------------------
    // 5. ESPACIO TOTALMENTE NEGRO DENTRO DE LA FISURA (ABISMO ABSOLUTO)
    // ------------------------------------------------------------------------
    float inRiftVoid = smoothstep(effectiveAperture, effectiveAperture - 0.007, distToCenter);
    vec3 voidBlack = vec3(0.0, 0.0, 0.0);

    // Fusión perfecta: interior es abismo negro absoluto, exterior es el cosmos desgarrado
    vec3 skyFinal = mix(outerSky, voidBlack, inRiftVoid);

    // Transición suave hacia el horizonte para fundir con la niebla cósmica
    float horizonFade = smoothstep(0.0, 0.15, y);
    vec3 finalRGB = mix(cHorizon, skyFinal, horizonFade);

    float smoothProg = prog * prog * (3.0 - 2.0 * prog) + EventTime * 0.0;
    fragColor = vec4(finalRGB, smoothProg);
}
