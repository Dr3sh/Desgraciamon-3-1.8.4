package com.desgraciamon.vortex.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.ShaderInstance
import org.joml.Matrix4f

object VortexSkyRenderer {

    @Volatile
    var vortexSkyShader: ShaderInstance? = null
    @Volatile
    var starTrailsSkyShader: ShaderInstance? = null
    @Volatile
    var goosebumpsSkyShader: ShaderInstance? = null

    private val identityMatrix = Matrix4f()

    /**
     * Renderiza el Skybox 100% mediante el shader GLSL correspondiente al efecto activo.
     * Pasa `SkyRotMat` directamente al uniform del shader sin alterar el `ModelViewStack` de OpenGL,
     * garantizando que las coordenadas del cubo (`worldDir`) estén fijas en el mundo y jamás se muevan con la cámara.
     */
    fun renderVortexSkybox(frustumMatrix: Matrix4f, partialTick: Float) {
        val intensity = VortexShaderController.getSmoothIntensity(partialTick)
        if (!VortexShaderController.isEventActiveClient && intensity <= 0.002f) {
            return
        }

        val effect = VortexShaderController.currentEffectType.lowercase()
        val shader = when {
            effect.contains("vortex") -> vortexSkyShader
            effect.contains("goosebumps") || effect.contains("travis") || effect.contains("astronomical") -> goosebumpsSkyShader
            else -> starTrailsSkyShader
        } ?: vortexSkyShader ?: starTrailsSkyShader ?: return
        val timeSec = VortexShaderController.getSmoothTimeSeconds(partialTick)
        val eventAgeSec = VortexShaderController.getSmoothEventAgeSeconds(partialTick)

        // Extraer únicamente la rotación pura 3x3 de la cámara (sin ninguna traslación ni bobbing)
        val pureRotationMat = Matrix4f(frustumMatrix)
        pureRotationMat.m30(0.0f)
        pureRotationMat.m31(0.0f)
        pureRotationMat.m32(0.0f)

        // Pasar los Uniforms al Shader GLSL
        shader.safeGetUniform("SkyRotMat").set(pureRotationMat)
        shader.safeGetUniform("Time").set(timeSec)
        shader.safeGetUniform("Intensity").set(intensity)
        shader.safeGetUniform("EventTime")?.set(eventAgeSec)

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.depthMask(false)
        RenderSystem.disableCull()
        RenderSystem.setShader { shader }

        // IMPORTANTE: Usamos identityMatrix en los vértices para que el atributo `Position` llegue intacto
        // al Vertex Shader como la dirección cardinal fija del cielo (`worldDir`), y no sea rotado en la CPU!
        val s = 100.0f
        val buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION)

        // Cara Superior (+Y - Cenit fijo del cielo)
        buf.addVertex(identityMatrix, -s,  s, -s)
        buf.addVertex(identityMatrix, -s,  s,  s)
        buf.addVertex(identityMatrix,  s,  s,  s)
        buf.addVertex(identityMatrix,  s,  s, -s)

        // Cara Inferior (-Y - Nadir)
        buf.addVertex(identityMatrix, -s, -s,  s)
        buf.addVertex(identityMatrix, -s, -s, -s)
        buf.addVertex(identityMatrix,  s, -s, -s)
        buf.addVertex(identityMatrix,  s, -s,  s)

        // Cara Norte (-Z)
        buf.addVertex(identityMatrix, -s,  s, -s)
        buf.addVertex(identityMatrix,  s,  s, -s)
        buf.addVertex(identityMatrix,  s, -s, -s)
        buf.addVertex(identityMatrix, -s, -s, -s)

        // Cara Sur (+Z)
        buf.addVertex(identityMatrix,  s,  s,  s)
        buf.addVertex(identityMatrix, -s,  s,  s)
        buf.addVertex(identityMatrix, -s, -s,  s)
        buf.addVertex(identityMatrix,  s, -s,  s)

        // Cara Oeste (-X)
        buf.addVertex(identityMatrix, -s,  s,  s)
        buf.addVertex(identityMatrix, -s,  s, -s)
        buf.addVertex(identityMatrix, -s, -s, -s)
        buf.addVertex(identityMatrix, -s, -s,  s)

        // Cara Este (+X)
        buf.addVertex(identityMatrix,  s,  s, -s)
        buf.addVertex(identityMatrix,  s,  s,  s)
        buf.addVertex(identityMatrix,  s, -s,  s)
        buf.addVertex(identityMatrix,  s, -s, -s)

        BufferUploader.drawWithShader(buf.buildOrThrow())

        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
        RenderSystem.disableBlend()
    }
}
