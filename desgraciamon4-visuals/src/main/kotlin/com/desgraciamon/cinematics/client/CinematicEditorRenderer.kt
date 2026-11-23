package com.desgraciamon.cinematics.client

import com.desgraciamon.cinematics.CinematicRoute
import com.desgraciamon.cinematics.Keyframe
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import kotlin.math.cos
import kotlin.math.sin

object CinematicEditorRenderer {

    fun init() {
        // Renderizado 3D de trayectoria, columnas de luz y marcadores en el mundo
        WorldRenderEvents.LAST.register { context ->
            renderEditorTrajectory(context)
        }

        // Ticks de cliente: actualización de selección de punto cercano y partículas guía
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            updateEditorSelection(client)
            spawnTrajectoryParticles(client)
        }
    }

    private fun updateEditorSelection(client: Minecraft) {
        val route = CinematicClientEngine.editorRoute ?: return
        val player = client.player ?: return
        val eyePos = player.eyePosition

        var bestIdx = -1
        var bestDist = Double.MAX_VALUE

        for ((idx, kf) in route.keyframes.withIndex()) {
            val d = eyePos.distanceTo(kf.position())
            if (d < bestDist) {
                bestDist = d
                bestIdx = idx
            }
        }

        if (bestIdx != -1 && bestDist <= 30.0) {
            CinematicClientEngine.selectedKeyframeIndex = bestIdx
        }
    }

    private fun spawnTrajectoryParticles(client: Minecraft) {
        val route = CinematicClientEngine.editorRoute ?: return
        if (route.keyframes.size < 2) return
        val level = client.level ?: return

        val totalSec = route.totalDurationSeconds()
        if (totalSec <= 0.0) return

        val cycleMs = 3500L
        val progress = (System.currentTimeMillis() % cycleMs).toDouble() / cycleMs.toDouble()

        // Generar 3 partículas guía viajando a lo largo del camino
        for (i in 0..2) {
            val offsetProgress = (progress + (i * 0.333)) % 1.0
            val tSec = offsetProgress * totalSec
            val pos = route.evaluatePosition(tSec)
            level.addParticle(
                ParticleTypes.END_ROD,
                pos.x, pos.y, pos.z,
                0.0, 0.015, 0.0
            )
        }
    }

    private fun renderEditorTrajectory(context: WorldRenderContext) {
        val route = CinematicClientEngine.editorRoute ?: return
        if (route.keyframes.isEmpty()) return

        val camera = context.camera()
        val camPos = camera.position
        val poseStack = context.matrixStack() ?: return
        val matrix = poseStack.last().pose()

        // 1. Configuración de renderizado X-Ray (visible a través de paredes y terrenos)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableCull()
        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.setShader(GameRenderer::getPositionColorShader)

        // 2. Renderizar Ribbon 3D (Cinta ancha) si hay al menos 2 keyframes
        if (route.keyframes.size >= 2) {
            renderThickSplineRibbon(route, camPos, matrix)
        }

        // 3. Renderizar Pilares de Luz (Beacon Columns) y Cubos 3D para cada Keyframe
        renderKeyframeMarkersAndBeacons(route, camPos, matrix)

        // Restaurar estado gráfico
        RenderSystem.depthMask(true)
        RenderSystem.enableDepthTest()
        RenderSystem.enableCull()
        RenderSystem.disableBlend()

        // 4. Renderizar Textos Holográficos 3D con Índice y Efectos Adjuntados sobre cada Keyframe
        renderFloatingLabels(route, camPos, context)
    }

    private fun renderThickSplineRibbon(route: CinematicRoute, camPos: Vec3, matrix: Matrix4f) {
        val totalSec = route.totalDurationSeconds()
        val steps = (totalSec * 20.0).toInt().coerceIn(40, 500)

        val points = ArrayList<Vec3>(steps + 1)
        for (i in 0..steps) {
            val tSec = (i.toDouble() / steps.toDouble()) * totalSec
            val worldP = route.evaluatePosition(tSec)
            points.add(worldP.subtract(camPos))
        }

        val tesselator = Tesselator.getInstance()

        // --- Renderizar Quads (Cintas 3D Cruzadas Horizontal y Vertical) ---
        val quadBuffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        val ribbonRadius = 0.14

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val dir = p2.subtract(p1)
            val len = dir.length()
            if (len < 1e-4) continue

            val normDir = dir.scale(1.0 / len)

            var hPerp = Vec3(-normDir.z, 0.0, normDir.x)
            if (hPerp.lengthSqr() < 1e-4) {
                hPerp = Vec3(1.0, 0.0, 0.0)
            } else {
                hPerp = hPerp.normalize()
            }
            val hOffset = hPerp.scale(ribbonRadius)
            val vOffset = normDir.cross(hPerp).normalize().scale(ribbonRadius)

            val r = 0.15f
            val g = 0.95f
            val b = 0.65f
            val a = 0.65f

            // Quad Horizontal
            quadBuffer.addVertex(matrix, (p1.x - hOffset.x).toFloat(), (p1.y - hOffset.y).toFloat(), (p1.z - hOffset.z).toFloat()).setColor(r, g, b, a)
            quadBuffer.addVertex(matrix, (p2.x - hOffset.x).toFloat(), (p2.y - hOffset.y).toFloat(), (p2.z - hOffset.z).toFloat()).setColor(r, g, b, a)
            quadBuffer.addVertex(matrix, (p2.x + hOffset.x).toFloat(), (p2.y + hOffset.y).toFloat(), (p2.z + hOffset.z).toFloat()).setColor(r, g, b, a)
            quadBuffer.addVertex(matrix, (p1.x + hOffset.x).toFloat(), (p1.y + hOffset.y).toFloat(), (p1.z + hOffset.z).toFloat()).setColor(r, g, b, a)

            // Quad Vertical
            quadBuffer.addVertex(matrix, (p1.x - vOffset.x).toFloat(), (p1.y - vOffset.y).toFloat(), (p1.z - vOffset.z).toFloat()).setColor(r, g, b, a)
            quadBuffer.addVertex(matrix, (p2.x - vOffset.x).toFloat(), (p2.y - vOffset.y).toFloat(), (p2.z - vOffset.z).toFloat()).setColor(r, g, b, a)
            quadBuffer.addVertex(matrix, (p2.x + vOffset.x).toFloat(), (p2.y + vOffset.y).toFloat(), (p2.z + vOffset.z).toFloat()).setColor(r, g, b, a)
            quadBuffer.addVertex(matrix, (p1.x + vOffset.x).toFloat(), (p1.y + vOffset.y).toFloat(), (p1.z + vOffset.z).toFloat()).setColor(r, g, b, a)
        }
        BufferUploader.drawWithShader(quadBuffer.buildOrThrow())

        // --- Línea Central Brillante ---
        val lineBuffer = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR)
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            lineBuffer.addVertex(matrix, p1.x.toFloat(), p1.y.toFloat(), p1.z.toFloat()).setColor(1.0f, 1.0f, 0.4f, 0.95f)
            lineBuffer.addVertex(matrix, p2.x.toFloat(), p2.y.toFloat(), p2.z.toFloat()).setColor(1.0f, 1.0f, 0.4f, 0.95f)
        }
        BufferUploader.drawWithShader(lineBuffer.buildOrThrow())
    }

    private fun renderKeyframeMarkersAndBeacons(route: CinematicRoute, camPos: Vec3, matrix: Matrix4f) {
        val tesselator = Tesselator.getInstance()
        val quadBuffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)

        for ((idx, kf) in route.keyframes.withIndex()) {
            val isSelected = (idx == CinematicClientEngine.selectedKeyframeIndex)
            val kfRel = kf.position().subtract(camPos)

            // Determinar color temático del punto según sus efectos
            var r = 0.20f
            var g = 0.95f
            var b = 0.60f

            if (isSelected) {
                // Enfocado / Seleccionado: Oro brillante
                r = 1.0f; g = 0.85f; b = 0.1f
            } else if (kf.fadeColor != null) {
                val c = CinematicClientEngine.parseColor(kf.fadeColor)
                r = ((c shr 16) and 0xFF) / 255f
                g = ((c shr 8) and 0xFF) / 255f
                b = (c and 0xFF) / 255f
                if (r < 0.25f && g < 0.25f && b < 0.25f) {
                    r = 0.35f; g = 0.35f; b = 0.45f
                }
            } else if (kf.isSeasonJump) {
                r = 0.95f; g = 0.25f; b = 1.0f
            } else if (!kf.animationTrigger.isNullOrBlank()) {
                r = 0.75f; g = 0.35f; b = 1.0f
            } else if (!kf.shaderEffect.isNullOrBlank()) {
                r = 0.20f; g = 0.85f; b = 1.0f
            }

            // Pilar vertical brillante tipo Faro (24 bloques de altura)
            val bRadius = if (isSelected) 0.16f else 0.12f
            val yBottom = (kfRel.y - 2.0).toFloat()
            val yTop = (kfRel.y + 24.0).toFloat()
            val x = kfRel.x.toFloat()
            val z = kfRel.z.toFloat()
            val beamAlpha = if (isSelected) 0.65f else 0.35f

            // Cara X
            quadBuffer.addVertex(matrix, x - bRadius, yBottom, z).setColor(r, g, b, beamAlpha)
            quadBuffer.addVertex(matrix, x + bRadius, yBottom, z).setColor(r, g, b, beamAlpha)
            quadBuffer.addVertex(matrix, x + bRadius, yTop, z).setColor(r, g, b, 0.05f)
            quadBuffer.addVertex(matrix, x - bRadius, yTop, z).setColor(r, g, b, 0.05f)

            // Cara Z
            quadBuffer.addVertex(matrix, x, yBottom, z - bRadius).setColor(r, g, b, beamAlpha)
            quadBuffer.addVertex(matrix, x, yBottom, z + bRadius).setColor(r, g, b, beamAlpha)
            quadBuffer.addVertex(matrix, x, yTop, z + bRadius).setColor(r, g, b, 0.05f)
            quadBuffer.addVertex(matrix, x, yTop, z - bRadius).setColor(r, g, b, 0.05f)

            // Cubo de Marcador Sólido Translúcido
            val size = if (isSelected) 0.48f else 0.32f
            drawFilledCube(quadBuffer, matrix, kfRel, size, r, g, b, if (isSelected) 0.55f else 0.40f)
        }
        BufferUploader.drawWithShader(quadBuffer.buildOrThrow())

        // 2. Líneas de Contorno y Flechas de Orientación
        val lineBuffer = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR)

        for ((idx, kf) in route.keyframes.withIndex()) {
            val isSelected = (idx == CinematicClientEngine.selectedKeyframeIndex)
            val kfRel = kf.position().subtract(camPos)

            val r = if (isSelected) 1.0f else (if (kf.isSeasonJump) 1.0f else 0.3f)
            val g = if (isSelected) 1.0f else (if (kf.isSeasonJump) 0.4f else 1.0f)
            val b = if (isSelected) 0.2f else (if (kf.isSeasonJump) 1.0f else 1.0f)

            val size = if (isSelected) 0.48f else 0.32f
            drawWireframeCube(lineBuffer, matrix, kfRel, size, r, g, b)

            // Flecha indicadora de la dirección de cámara (Yaw y Pitch)
            val yawRad = Math.toRadians(-kf.yaw.toDouble() - 90.0)
            val pitchRad = Math.toRadians(-kf.pitch.toDouble())
            val lookDir = Vec3(
                cos(pitchRad) * cos(yawRad),
                sin(pitchRad),
                cos(pitchRad) * sin(yawRad)
            ).scale(1.5)

            val arrowEnd = kfRel.add(lookDir)
            lineBuffer.addVertex(matrix, kfRel.x.toFloat(), kfRel.y.toFloat(), kfRel.z.toFloat()).setColor(1.0f, 1.0f, 0.0f, 1.0f)
            lineBuffer.addVertex(matrix, arrowEnd.x.toFloat(), arrowEnd.y.toFloat(), arrowEnd.z.toFloat()).setColor(1.0f, 0.3f, 0.1f, 1.0f)
        }
        BufferUploader.drawWithShader(lineBuffer.buildOrThrow())
    }

    private fun renderFloatingLabels(route: CinematicRoute, camPos: Vec3, context: WorldRenderContext) {
        val consumers = context.consumers() ?: return
        val font = Minecraft.getInstance().font
        val camera = context.camera()
        val poseStack = context.matrixStack() ?: return

        for ((idx, kf) in route.keyframes.withIndex()) {
            val isSelected = (idx == CinematicClientEngine.selectedKeyframeIndex)
            val kfRel = kf.position().subtract(camPos)

            val lines = mutableListOf<Pair<String, Int>>()

            // 1. Línea de Encabezado con número de índice
            if (isSelected) {
                lines += Pair(">> [PUNTO CLAVE #${idx + 1}] <<", 0xFFFFD700.toInt())
            } else {
                lines += Pair("[Punto #${idx + 1}]", 0xFF55FFAA.toInt())
            }

            // 2. Línea de Duración y Curva
            val easingName = when (kf.easing) {
                com.desgraciamon.cinematics.EasingMode.LINEAR -> "Lineal"
                com.desgraciamon.cinematics.EasingMode.EASE_IN -> "Ease-In"
                com.desgraciamon.cinematics.EasingMode.EASE_OUT -> "Ease-Out"
                com.desgraciamon.cinematics.EasingMode.EASE_IN_OUT -> "Ease-InOut"
            }
            lines += Pair("${String.format("%.1f", kf.durationSeconds)}s • $easingName", 0xFFE0E0E0.toInt())

            // 3. Efectos Adjuntados
            var hasEffect = false

            if (kf.fadeColor != null || kf.shaderEffect?.lowercase() in listOf("fade", "color_fade")) {
                hasEffect = true
                val colorName = kf.fadeColor ?: "negro"
                val dur = if (kf.fadeColor != null) kf.fadeDurationSeconds else kf.shaderDurationSeconds
                lines += Pair("Fade: $colorName (${String.format("%.1f", dur)}s)", 0xFFFFAA33.toInt())
            }

            if (!kf.shaderEffect.isNullOrBlank() && kf.shaderEffect?.lowercase() !in listOf("fade", "color_fade")) {
                hasEffect = true
                lines += Pair("Shader: ${kf.shaderEffect} (${String.format("%.1f", kf.shaderDurationSeconds)}s)", 0xFF55FFFF.toInt())
            }

            if (!kf.animationTrigger.isNullOrBlank()) {
                hasEffect = true
                lines += Pair("Animación: ${kf.animationTrigger}", 0xFFDD77FF.toInt())
            }

            if (kf.isSeasonJump) {
                hasEffect = true
                lines += Pair("SALTO DE TEMPORADA", 0xFFFF55BB.toInt())
            }

            if (!kf.subtitle.isNullOrBlank()) {
                hasEffect = true
                val subText = if (kf.subtitle!!.length > 22) kf.subtitle!!.take(20) + "..." else kf.subtitle!!
                lines += Pair("\"$subText\"", 0xFFFFFF77.toInt())
            }

            if (!kf.soundTrigger.isNullOrBlank()) {
                hasEffect = true
                lines += Pair("${kf.soundTrigger}", 0xFF88FF88.toInt())
            }

            if (!hasEffect) {
                lines += Pair("(Sin efectos adjuntados)", 0xFF8899AA.toInt())
            }

            poseStack.pushPose()
            poseStack.translate(kfRel.x, kfRel.y + 0.65, kfRel.z)
            poseStack.mulPose(camera.rotation())

            val dist = camPos.distanceTo(kf.position())
            val scale = (0.020f + dist.toFloat() * 0.0012f).coerceIn(0.020f, 0.065f)
            poseStack.scale(-scale, -scale, scale)

            val textMatrix = poseStack.last().pose()
            val totalHeight = lines.size * 10.5f
            var startY = -totalHeight

            for ((text, color) in lines) {
                val textWidth = font.width(text)
                val x = -textWidth / 2f

                font.drawInBatch(
                    text, x, startY, 0x35FFFFFF, false,
                    textMatrix, consumers, Font.DisplayMode.SEE_THROUGH, 0x85000000.toInt(), 15728880
                )
                font.drawInBatch(
                    text, x, startY, color, false,
                    textMatrix, consumers, Font.DisplayMode.NORMAL, 0, 15728880
                )
                startY += 10.5f
            }

            poseStack.popPose()
        }
    }

    private fun drawFilledCube(
        buffer: com.mojang.blaze3d.vertex.BufferBuilder,
        matrix: Matrix4f,
        center: Vec3,
        size: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        val hs = size / 2f
        val x0 = (center.x - hs).toFloat()
        val x1 = (center.x + hs).toFloat()
        val y0 = (center.y - hs).toFloat()
        val y1 = (center.y + hs).toFloat()
        val z0 = (center.z - hs).toFloat()
        val z1 = (center.z + hs).toFloat()

        // 6 caras del cubo
        // Abajo
        buffer.addVertex(matrix, x0, y0, z0).setColor(r, g, b, a)
        buffer.addVertex(matrix, x1, y0, z0).setColor(r, g, b, a)
        buffer.addVertex(matrix, x1, y0, z1).setColor(r, g, b, a)
        buffer.addVertex(matrix, x0, y0, z1).setColor(r, g, b, a)

        // Arriba
        buffer.addVertex(matrix, x0, y1, z0).setColor(r, g, b, a)
        buffer.addVertex(matrix, x0, y1, z1).setColor(r, g, b, a)
        buffer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a)
        buffer.addVertex(matrix, x1, y1, z0).setColor(r, g, b, a)

        // Frente
        buffer.addVertex(matrix, x0, y0, z1).setColor(r, g, b, a)
        buffer.addVertex(matrix, x1, y0, z1).setColor(r, g, b, a)
        buffer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a)
        buffer.addVertex(matrix, x0, y1, z1).setColor(r, g, b, a)

        // Atrás
        buffer.addVertex(matrix, x0, y0, z0).setColor(r, g, b, a)
        buffer.addVertex(matrix, x0, y1, z0).setColor(r, g, b, a)
        buffer.addVertex(matrix, x1, y1, z0).setColor(r, g, b, a)
        buffer.addVertex(matrix, x1, y0, z0).setColor(r, g, b, a)

        // Izquierda
        buffer.addVertex(matrix, x0, y0, z0).setColor(r, g, b, a)
        buffer.addVertex(matrix, x0, y0, z1).setColor(r, g, b, a)
        buffer.addVertex(matrix, x0, y1, z1).setColor(r, g, b, a)
        buffer.addVertex(matrix, x0, y1, z0).setColor(r, g, b, a)

        // Derecha
        buffer.addVertex(matrix, x1, y0, z0).setColor(r, g, b, a)
        buffer.addVertex(matrix, x1, y1, z0).setColor(r, g, b, a)
        buffer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a)
        buffer.addVertex(matrix, x1, y0, z1).setColor(r, g, b, a)
    }

    private fun drawWireframeCube(
        buffer: com.mojang.blaze3d.vertex.BufferBuilder,
        matrix: Matrix4f,
        center: Vec3,
        size: Float,
        r: Float, g: Float, b: Float
    ) {
        val hs = size / 2f
        val x0 = (center.x - hs).toFloat()
        val x1 = (center.x + hs).toFloat()
        val y0 = (center.y - hs).toFloat()
        val y1 = (center.y + hs).toFloat()
        val z0 = (center.z - hs).toFloat()
        val z1 = (center.z + hs).toFloat()

        val edges = listOf(
            Pair(Vec3(x0.toDouble(), y0.toDouble(), z0.toDouble()), Vec3(x1.toDouble(), y0.toDouble(), z0.toDouble())),
            Pair(Vec3(x1.toDouble(), y0.toDouble(), z0.toDouble()), Vec3(x1.toDouble(), y0.toDouble(), z1.toDouble())),
            Pair(Vec3(x1.toDouble(), y0.toDouble(), z1.toDouble()), Vec3(x0.toDouble(), y0.toDouble(), z1.toDouble())),
            Pair(Vec3(x0.toDouble(), y0.toDouble(), z1.toDouble()), Vec3(x0.toDouble(), y0.toDouble(), z0.toDouble())),

            Pair(Vec3(x0.toDouble(), y1.toDouble(), z0.toDouble()), Vec3(x1.toDouble(), y1.toDouble(), z0.toDouble())),
            Pair(Vec3(x1.toDouble(), y1.toDouble(), z0.toDouble()), Vec3(x1.toDouble(), y1.toDouble(), z1.toDouble())),
            Pair(Vec3(x1.toDouble(), y1.toDouble(), z1.toDouble()), Vec3(x0.toDouble(), y1.toDouble(), z1.toDouble())),
            Pair(Vec3(x0.toDouble(), y1.toDouble(), z1.toDouble()), Vec3(x0.toDouble(), y1.toDouble(), z0.toDouble())),

            Pair(Vec3(x0.toDouble(), y0.toDouble(), z0.toDouble()), Vec3(x0.toDouble(), y1.toDouble(), z0.toDouble())),
            Pair(Vec3(x1.toDouble(), y0.toDouble(), z0.toDouble()), Vec3(x1.toDouble(), y1.toDouble(), z0.toDouble())),
            Pair(Vec3(x1.toDouble(), y0.toDouble(), z1.toDouble()), Vec3(x1.toDouble(), y1.toDouble(), z1.toDouble())),
            Pair(Vec3(x0.toDouble(), y0.toDouble(), z1.toDouble()), Vec3(x0.toDouble(), y1.toDouble(), z1.toDouble()))
        )

        for ((p1, p2) in edges) {
            buffer.addVertex(matrix, p1.x.toFloat(), p1.y.toFloat(), p1.z.toFloat()).setColor(r, g, b, 0.95f)
            buffer.addVertex(matrix, p2.x.toFloat(), p2.y.toFloat(), p2.z.toFloat()).setColor(r, g, b, 0.95f)
        }
    }
}
