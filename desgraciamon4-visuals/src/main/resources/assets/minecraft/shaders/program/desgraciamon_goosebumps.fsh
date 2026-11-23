#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform vec2 OutSize;
uniform float Time;
uniform float Intensity;

in vec2 texCoord;
out vec4 fragColor;

// Luminancia perceptual
float getLuma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// Hash 1D rapido
float hash(float n) {
    return fract(sin(n) * 43758.5453123);
}

void main() {
    float prog = clamp(Intensity, 0.0, 1.0);
    if (prog <= 0.001) {
        fragColor = texture(DiffuseSampler, texCoord);
        return;
    }

    // ------------------------------------------------------------------------
    // 1. RITMO SUB-BASS 808 (PULSO DE TRAP A ~130 BPM)
    // ------------------------------------------------------------------------
    float beatRate = 2.166667; // ~130 BPM
    float beatPhase = fract(Time * beatRate);
    // Golpe seco de bombo que decae rápidamente
    float bassKick = pow(max(0.0, 1.0 - beatPhase), 5.0);
    // Cada 4 compases hay un golpe más potente
    float barPhase = fract((Time * beatRate) / 4.0);
    float superDrop = step(0.96, 1.0 - barPhase) * bassKick * 1.6;
    float totalKick = bassKick + superDrop;

    // ------------------------------------------------------------------------
    // 2. DISTORSIÓN DE LENTE OJO DE PEZ (FISHEYE / BARREL DISTORTION)
    // ------------------------------------------------------------------------
    vec2 centerOffset = texCoord - 0.5;
    float r2 = dot(centerOffset, centerOffset);
    // La lente se deforma y además late al ritmo del sub-bass
    float barrelPower = (0.24 + 0.12 * totalKick) * prog;
    vec2 warpedUV = 0.5 + centerOffset * (1.0 + barrelPower * r2);

    // Glitch horizontal analógico VHS en momentos de golpe
    float vhsGlitchLine = floor(warpedUV.y * 30.0 + Time * 10.0);
    float vhsRnd = hash(vhsGlitchLine);
    if (vhsRnd > 0.985 && totalKick > 0.3) {
        warpedUV.x += (vhsRnd - 0.99) * 0.05 * totalKick;
    }

    vec2 px = 1.0 / InSize;

    // Muestreo del color original en la coordenada original (para blend final)
    vec4 rawOrig = texture(DiffuseSampler, texCoord);

    // Si la distorsión se sale del encuadre, fundir a negro
    if (warpedUV.x < 0.0 || warpedUV.x > 1.0 || warpedUV.y < 0.0 || warpedUV.y > 1.0) {
        fragColor = mix(rawOrig, vec4(0.02, 0.005, 0.05, 1.0), prog);
        return;
    }

    // ------------------------------------------------------------------------
    // 3. SEPARACIÓN RGB & ABERRACIÓN CROMÁTICA REACTIVA
    // ------------------------------------------------------------------------
    float chromOffset = (0.005 + 0.018 * totalKick) * prog;
    vec2 chromDir = normalize(centerOffset + vec2(1e-4));

    float rChan = texture(DiffuseSampler, warpedUV + chromDir * chromOffset).r;
    float gChan = texture(DiffuseSampler, warpedUV).g;
    float bChan = texture(DiffuseSampler, warpedUV - chromDir * chromOffset).b;
    vec3 sceneColor = vec3(rChan, gChan, bChan);

    // ------------------------------------------------------------------------
    // 4. DETECCIÓN DE BORDES NEÓN (FILTRO SOBEL ESTILO TRON / WIREFRAME)
    // ------------------------------------------------------------------------
    float tl = getLuma(texture(DiffuseSampler, warpedUV + vec2(-px.x,  px.y)).rgb);
    float tm = getLuma(texture(DiffuseSampler, warpedUV + vec2(  0.0,  px.y)).rgb);
    float tr = getLuma(texture(DiffuseSampler, warpedUV + vec2( px.x,  px.y)).rgb);
    float ml = getLuma(texture(DiffuseSampler, warpedUV + vec2(-px.x,   0.0)).rgb);
    float mr = getLuma(texture(DiffuseSampler, warpedUV + vec2( px.x,   0.0)).rgb);
    float bl = getLuma(texture(DiffuseSampler, warpedUV + vec2(-px.x, -px.y)).rgb);
    float bm = getLuma(texture(DiffuseSampler, warpedUV + vec2(  0.0, -px.y)).rgb);
    float br = getLuma(texture(DiffuseSampler, warpedUV + vec2( px.x, -px.y)).rgb);

    float gx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl);
    float gy = (bl + 2.0 * bm + br) - (tl + 2.0 * tm + tr);
    float edgeStrength = sqrt(gx * gx + gy * gy);
    float edge = smoothstep(0.12, 0.38, edgeStrength);

    // Color del contorno neón psicodélico (fluctúa entre cian, magenta y verde radiactivo)
    vec3 neonEdgeCol = mix(vec3(0.00, 1.00, 0.85), vec3(1.00, 0.05, 0.70), sin(warpedUV.x * 6.0 + Time * 2.5) * 0.5 + 0.5);
    neonEdgeCol = mix(neonEdgeCol, vec3(0.55, 1.00, 0.05), sin(warpedUV.y * 5.0 - Time * 2.0) * 0.5 + 0.5);

    // ------------------------------------------------------------------------
    // 5. SOLARIZACIÓN Y FALSO COLOR TÉRMICO DE GOOSEBUMPS
    // ------------------------------------------------------------------------
    float luma = getLuma(sceneColor);
    vec3 thermalCol;

    if (luma < 0.22) {
        // Sombras profundas: Negro medianoche con tinte violeta abisal
        float f = luma / 0.22;
        thermalCol = mix(vec3(0.02, 0.005, 0.06), vec3(0.28, 0.02, 0.45), f);
    } else if (luma < 0.55) {
        // Tonos medios: De Magenta / Violeta a Cian Radiactivo
        float f = (luma - 0.22) / 0.33;
        thermalCol = mix(vec3(0.85, 0.02, 0.65), vec3(0.00, 0.95, 0.90), f);
    } else if (luma < 0.85) {
        // Tonos altos: De Cian a Amarillo Ácido Flúor
        float f = (luma - 0.55) / 0.30;
        thermalCol = mix(vec3(0.00, 0.95, 0.90), vec3(1.00, 0.90, 0.05), f);
    } else {
        // Picos de luz: Blanco cegador con halo amarillo
        float f = (luma - 0.85) / 0.15;
        thermalCol = mix(vec3(1.00, 0.90, 0.05), vec3(1.05, 1.05, 1.15), f);
    }

    // Inversión parcial psicotrópica en los brillos (solarización)
    if (luma > 0.70) {
        thermalCol = mix(thermalCol, vec3(1.0) - thermalCol, 0.45 * (0.8 + 0.2 * totalKick));
    }

    // ------------------------------------------------------------------------
    // 6. LÍNEAS DE ESCANEO ANALÓGICO (CRT / VHS SCANLINES)
    // ------------------------------------------------------------------------
    float scanline = sin(warpedUV.y * InSize.y * 1.4) * 0.09;
    thermalCol -= scanline;

    // Viñeta oscura radial envolvente
    float vignette = clamp(1.0 - r2 * 1.5, 0.0, 1.0);
    thermalCol *= vignette;

    // ------------------------------------------------------------------------
    // 7. COMPOSICIÓN FINAL: FUSIÓN DE BASE TÉRMICA + BORDES NEÓN + INTENSIDAD
    // ------------------------------------------------------------------------
    vec3 finalEffect = thermalCol + edge * neonEdgeCol * 2.8;

    // El golpe del sub-bass inyecta un fogonazo de bloom blanco/cian
    finalEffect += vec3(0.15, 0.40, 0.55) * (totalKick * 0.35);

    // Interpolación suave y orgánica con el mundo real según Intensity
    vec3 result = mix(rawOrig.rgb, finalEffect, prog);

    fragColor = vec4(result, 1.0);
}

