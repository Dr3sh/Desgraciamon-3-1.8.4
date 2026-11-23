package com.desgraciamon.mirror

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.shaders.Uniform
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShaderInstance
import org.joml.Matrix4f

object MirrorSkyRenderer {

    @Volatile
    var skyShader: ShaderInstance? = null

    @Volatile
    var recuerdosSkyShader: ShaderInstance? = null

    private val identityMatrix = Matrix4f()

    fun ShaderInstance.safeGetUniform(name: String): Uniform? {
        return runCatching { this.getUniform(name) }.getOrNull()
    }

    /**
     * Renderiza el firmamento cubierto y nostálgico de la Zona Espejo
     * mediante el shader GLSL `desgraciamon_mirror_sky`.
     */
    fun renderMirrorSkybox(frustumMatrix: Matrix4f, projectionMatrix: Matrix4f, partialTick: Float) {
        val shader = skyShader ?: return
        val client = Minecraft.getInstance()
        val level = client.level ?: return
        if (!MirrorRules.isMirror(level)) return

        val timeSec = (System.currentTimeMillis() % 100_000_000L) / 1000.0f

        // Extraer únicamente la rotación pura de la cámara (sin traslación)
        val pureRotationMat = Matrix4f(frustumMatrix)
        pureRotationMat.m30(0.0f)
        pureRotationMat.m31(0.0f)
        pureRotationMat.m32(0.0f)

        val player = client.player
        val plotIndex = if (player != null) PlotManager.plotAt(player.x) else 0
        val theme = TerrainGenerator.themeFor(plotIndex)

        // Pasar Uniforms al Shader GLSL
        shader.safeGetUniform("ProjMat")?.set(projectionMatrix)
        shader.safeGetUniform("SkyRotMat")?.set(pureRotationMat)
        shader.safeGetUniform("Time")?.set(timeSec)
        shader.safeGetUniform("OvercastDensity")?.set(theme.overcastDensity)
        shader.safeGetUniform("SunDir")?.set(0.55f, 0.65f, -0.52f)
        shader.safeGetUniform("SkyTint")?.set(theme.skyTintR, theme.skyTintG, theme.skyTintB)

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.disableCull()
        RenderSystem.setShader { shader }

        val s = 100.0f
        val buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION)

        // Cara Superior (+Y - Cenit del cielo)
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

        buf.build()?.let { BufferUploader.drawWithShader(it) }

        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
        RenderSystem.enableDepthTest()
    }

    /**
     * Renderiza el atardecer crepuscular con lluvia de estrellas de dimension_recuerdos
     * mediante el shader GLSL `desgraciamon_recuerdos_sky`.
     */
    fun renderRecuerdosSkybox(frustumMatrix: Matrix4f, projectionMatrix: Matrix4f, partialTick: Float) {
        val shader = recuerdosSkyShader ?: return
        val client = Minecraft.getInstance()
        val level = client.level ?: return
        if (!MirrorRules.isRecuerdos(level)) return

        val timeSec = (System.currentTimeMillis() % 100_000_000L) / 1000.0f

        val pureRotationMat = Matrix4f(frustumMatrix)
        pureRotationMat.m30(0.0f)
        pureRotationMat.m31(0.0f)
        pureRotationMat.m32(0.0f)

        // Uniforms GLSL
        shader.safeGetUniform("ProjMat")?.set(projectionMatrix)
        shader.safeGetUniform("SkyRotMat")?.set(pureRotationMat)
        shader.safeGetUniform("Time")?.set(timeSec)
        shader.safeGetUniform("SunDir")?.set(0.85f, 0.18f, 0.45f)

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.disableCull()
        RenderSystem.setShader { shader }

        val s = 100.0f
        val buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION)

        // Cara Superior (+Y)
        buf.addVertex(identityMatrix, -s,  s, -s)
        buf.addVertex(identityMatrix, -s,  s,  s)
        buf.addVertex(identityMatrix,  s,  s,  s)
        buf.addVertex(identityMatrix,  s,  s, -s)

        // Cara Inferior (-Y)
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

        buf.build()?.let { BufferUploader.drawWithShader(it) }

        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
        RenderSystem.enableDepthTest()
    }
}
