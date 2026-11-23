package com.desgraciamon.screeneffects

import com.desgraciamon.cinematics.client.CinematicClientEngine
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import org.joml.Matrix4f
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.sin

object ScreenEffectsClientEngine {
    private val logger = LoggerFactory.getLogger("desgraciamon4-screeneffects")

    data class ActiveEffect(
        val type: String,
        val color: Int,
        val fadeInMs: Int,
        val durationMs: Int,
        val fadeOutMs: Int,
        val intensity: Float,
        val startEpochMs: Long,
    ) {
        val totalDurationMs: Long = fadeInMs.toLong() + durationMs.toLong() + fadeOutMs.toLong()

        fun isFinished(now: Long): Boolean = now >= startEpochMs + totalDurationMs

        fun currentAlpha(now: Long): Float {
            val elapsed = now - startEpochMs
            if (elapsed <= 0L) return 0f
            if (elapsed >= totalDurationMs) return 0f

            return when {
                elapsed < fadeInMs -> {
                    val t = (elapsed.toFloat() / fadeInMs.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                    t * t * (3f - 2f * t)
                }
                elapsed < fadeInMs + durationMs -> 1.0f
                else -> {
                    val outElapsed = elapsed - (fadeInMs + durationMs)
                    val t = (outElapsed.toFloat() / fadeOutMs.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                    1.0f - (t * t * (3f - 2f * t))
                }
            }
        }
    }

    private val activeEffects = ConcurrentLinkedQueue<ActiveEffect>()

    fun triggerEffect(packet: ScreenEffectPacket) {
        if (packet.effectType == "CLEAR") {
            activeEffects.clear()
            logger.info("[ScreenEffects] Efectos de pantalla limpiados.")
            return
        }

        val now = System.currentTimeMillis()
        // Compensación de sincronización: si el reloj del cliente difiere en más de 600ms, iniciar de inmediato
        val effectiveStart = if (Math.abs(now - packet.startEpochMs) < 600L) packet.startEpochMs else now

        if (packet.effectType == "FADE") {
            activeEffects.removeIf { it.type == "FADE" }
        }

        logger.info("[ScreenEffects] Activando efecto ${packet.effectType} (color=0x${Integer.toHexString(packet.color)}, In=${packet.fadeInMs}ms, Dur=${packet.durationMs}ms, Out=${packet.fadeOutMs}ms, Int=${packet.intensity})")

        activeEffects.add(
            ActiveEffect(
                type = packet.effectType,
                color = packet.color,
                fadeInMs = packet.fadeInMs,
                durationMs = packet.durationMs,
                fadeOutMs = packet.fadeOutMs,
                intensity = packet.intensity.coerceIn(0.1f, 5.0f),
                startEpochMs = effectiveStart
            )
        )
    }

    fun clearAll() {
        activeEffects.clear()
    }

    /**
     * Dibuja un cuadrilátero sólido o translúcido 2D inmediato en coordenadas de pantalla.
     * Utiliza Z = 0f con proyección ortográfica [-1000, 1000] sin traslaciones que provoquen clipping.
     */
    private fun drawSolidQuad(x1: Float, y1: Float, x2: Float, y2: Float, colorArgb: Int) {
        val a = ((colorArgb ushr 24) and 0xFF) / 255.0f
        val r = ((colorArgb ushr 16) and 0xFF) / 255.0f
        val g = ((colorArgb ushr 8) and 0xFF) / 255.0f
        val b = (colorArgb and 0xFF) / 255.0f
        if (a <= 0.001f) return

        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        val tessellator = Tesselator.getInstance()
        val buf = tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        val m = Matrix4f()

        buf.addVertex(m, x1, y2, 0f).setColor(r, g, b, a)
        buf.addVertex(m, x2, y2, 0f).setColor(r, g, b, a)
        buf.addVertex(m, x2, y1, 0f).setColor(r, g, b, a)
        buf.addVertex(m, x1, y1, 0f).setColor(r, g, b, a)

        buf.build()?.let { BufferUploader.drawWithShader(it) }
    }

    /**
     * Comprueba si la pantalla abierta debe superponerse POR ENCIMA de los efectos de pantalla.
     * Únicamente el menú de Pausa y el Chat tienen permitido superponerse.
     */
    fun shouldSuperimposeScreen(screen: net.minecraft.client.gui.screens.Screen?): Boolean {
        if (screen == null) return false
        return screen is net.minecraft.client.gui.screens.ChatScreen ||
               screen is net.minecraft.client.gui.screens.PauseScreen
    }

    private fun renderAnimationsLayerOne(isOnTop: Boolean) {
        runCatching {
            val clazz = Class.forName("com.desgraciamon.screenanimations.ScreenAnimationsClient")
            val method = clazz.getMethod("renderTopLayerFromVisuals", Boolean::class.javaPrimitiveType)
            method.invoke(null, isOnTop)
        }
    }

    /**
     * Renderizado de la capa 0 de efectos visuales (/fade, /blink, /glitch, /desaturate, /bloom),
     * seguido siempre por la capa 1 de videos y animaciones por encima.
     * @param isDirectScreenCall true si es llamado desde eventos de pantalla especificos
     */
    @JvmOverloads
    fun renderTopLevel(partialTick: Float, isDirectScreenCall: Boolean = false) {
        val client = Minecraft.getInstance()

        // Si se llama desde el TAIL global de GameRenderer pero hay una pantalla abierta,
        // dejamos que los eventos de pantalla (beforeRender para Chat/Pausa y afterRender para otros) lo gestionen.
        if (!isDirectScreenCall && client.screen != null) {
            return
        }

        val isOnTop = !shouldSuperimposeScreen(client.screen)

        val window = client.window ?: return
        val width = window.guiScaledWidth.toFloat()
        val height = window.guiScaledHeight.toFloat()
        val now = System.currentTimeMillis()

        // Purgar efectos expirados
        activeEffects.removeIf { it.isFinished(now) }

        val hasUniversalEffects = activeEffects.isNotEmpty()
        val hasCinematicFade = CinematicClientEngine.fadeDurationSec > 0f
        val mirrorProgress = getMirrorWorldTransitionProgress()
        val hasMirrorTransition = mirrorProgress > 0.0f && mirrorProgress < 1.0f

        if (!hasUniversalEffects && !hasCinematicFade && !hasMirrorTransition) {
            renderAnimationsLayerOne(isOnTop)
            return
        }

        // Guardar estado previo de proyección y pila ModelView
        val prevProj = Matrix4f(RenderSystem.getProjectionMatrix())
        val prevSort = RenderSystem.getVertexSorting()
        val mvStack = RenderSystem.getModelViewStack()
        mvStack.pushMatrix()

        try {
            mvStack.identity()
            RenderSystem.applyModelViewMatrix()

            // Configurar matriz ortográfica centrada en Z=0 (garantía anti-clipping de OpenGL)
            val ortho = Matrix4f().setOrtho(0f, width, height, 0f, -1000f, 1000f)
            RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z)

            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            RenderSystem.disableDepthTest()
            RenderSystem.depthMask(false)
            RenderSystem.disableCull()

            // 1. Cinemática Fade
            if (hasCinematicFade) {
                renderCinematicFadeDirect(width, height)
            }

            // 2. Transición Mundo Espejo
            if (hasMirrorTransition) {
                renderMirrorTransitionDirect(width, height, mirrorProgress)
            }

            // 3. Efectos Universales (/fade, /blink, /glitch, /desaturate, /bloom)
            for (effect in activeEffects) {
                val alpha = (effect.currentAlpha(now) * effect.intensity).coerceIn(0f, 1f)
                if (alpha <= 0.001f) continue

                when (effect.type) {
                    "FADE" -> {
                        val alphaByte = (alpha * 255f).toInt().coerceIn(0, 255)
                        val argb = (alphaByte shl 24) or (effect.color and 0x00FFFFFF)
                        drawSolidQuad(0f, 0f, width, height, argb)
                    }
                    "BLINK", "PESTANEO", "MAREO" -> {
                        renderBlinkDirect(width, height, effect, now, alpha)
                    }
                    "GLITCH" -> {
                        renderGlitchDirect(width, height, effect, now, alpha)
                    }
                    "DESATURATE", "DESATURACION" -> {
                        val alphaByte = (alpha * 0.65f * 255f).toInt().coerceIn(0, 255)
                        val argb = (alphaByte shl 24) or 0x6E7280
                        drawSolidQuad(0f, 0f, width, height, argb)
                    }
                    "BLOOM" -> {
                        val bloomColor = if (effect.color == 0x000000) 0xFFF2D8 else effect.color
                        val alphaByte = (alpha * 0.60f * 255f).toInt().coerceIn(0, 255)
                        val argb = (alphaByte shl 24) or (bloomColor and 0x00FFFFFF)
                        drawSolidQuad(0f, 0f, width, height, argb)
                    }
                }
            }
        } finally {
            // Restaurar estado de renderizado de Capa 0 antes de dibujar Capa 1 (videos/animaciones) encima
            RenderSystem.enableCull()
            RenderSystem.enableDepthTest()
            RenderSystem.depthMask(true)
            RenderSystem.disableBlend()
            mvStack.popMatrix()
            RenderSystem.applyModelViewMatrix()
            RenderSystem.setProjectionMatrix(prevProj, prevSort)
        }

        renderAnimationsLayerOne(isOnTop)
    }

    private fun renderCinematicFadeDirect(width: Float, height: Float) {
        val duration = CinematicClientEngine.fadeDurationSec
        if (duration <= 0f) return

        val currentElapsed = if (CinematicClientEngine.activeRoute != null) {
            CinematicClientEngine.currentElapsedSec()
        } else {
            System.currentTimeMillis() / 1000.0
        }
        val elapsedSec = (currentElapsed - CinematicClientEngine.fadeStartSec).toFloat()
        if (elapsedSec >= duration || elapsedSec < 0f) {
            if (elapsedSec >= duration) CinematicClientEngine.resetFade()
            return
        }

        val progress = (elapsedSec / duration).coerceIn(0f, 1f)
        val alpha = sin(progress * PI.toFloat()).coerceIn(0f, 1f)
        val alphaInt = (alpha * 255f).toInt().coerceIn(0, 255)
        val argb = (alphaInt shl 24) or (CinematicClientEngine.activeFadeColor and 0x00FFFFFF)

        drawSolidQuad(0f, 0f, width, height, argb)
    }

    private fun renderMirrorTransitionDirect(width: Float, height: Float, progress: Float) {
        val intensity = sin(progress.toDouble() * Math.PI).toFloat()
        if (intensity <= 0.001f) return

        val veilFactor = Math.pow(intensity.toDouble(), 1.7).toFloat()
        if (veilFactor > 0.005f) {
            val veilAlpha = (veilFactor * 255).toInt().coerceIn(0, 255)
            val veilColor = (veilAlpha shl 24) or 0xF6F1FA
            drawSolidQuad(0f, 0f, width, height, veilColor)
        }
    }

    private fun renderBlinkDirect(width: Float, height: Float, effect: ActiveEffect, now: Long, alpha: Float) {
        val halfH = height / 2f
        val closeProgress = alpha

        val upperY = halfH * closeProgress
        val lowerY = height - (halfH * closeProgress)

        // Relleno sólido párpados
        if (upperY > 0.5f) {
            drawSolidQuad(0f, 0f, width, upperY, 0xFF000000.toInt())
        }
        if (lowerY < height - 0.5f) {
            drawSolidQuad(0f, lowerY, width, height, 0xFF000000.toInt())
        }

        // Arco curvo orgánico de párpados
        if (closeProgress < 0.98f && closeProgress > 0.02f) {
            val steps = 30
            val stepW = width / steps
            for (i in 0 until steps) {
                val x0 = i * stepW
                val x1 = (i + 1) * stepW
                val midX = (x0 + x1) / 2f
                val curve = (sin(midX / width * PI) * (height * 0.10f) * (1f - closeProgress)).toFloat()

                // Borde superior curvo
                drawSolidQuad(x0, upperY, x1, (upperY + curve).coerceAtMost(halfH), 0xE6000000.toInt())
                // Borde inferior curvo
                drawSolidQuad(x0, (lowerY - curve).coerceAtLeast(halfH), x1, lowerY, 0xE6000000.toInt())
            }
        }

        // Viñeta periférica de túnel (mareo / desvanecimiento)
        val vigAlpha = ((alpha * 0.70f) * 255f).toInt().coerceIn(0, 255)
        val vigColor = (vigAlpha shl 24) or 0x080810
        val vigW = width * 0.12f
        drawSolidQuad(0f, 0f, vigW, height, vigColor)
        drawSolidQuad(width - vigW, 0f, width, height, vigColor)
    }

    private fun renderGlitchDirect(width: Float, height: Float, effect: ActiveEffect, now: Long, alpha: Float) {
        val frameSeed = (now / 65L) * 19L
        val barCount = (8 + (sin(frameSeed * 0.2) * 5).toInt()).coerceIn(5, 14)

        for (i in 0 until barCount) {
            val hash = ((frameSeed + i * 31L) * 2654435761L).toInt()
            val y = (Math.abs(hash) % height.toInt()).toFloat().coerceAtMost(height - 12f)
            val h = (4 + (Math.abs(hash shr 8) % 18)).toFloat().coerceAtMost(height - y)
            val shift = ((hash shr 16) % 36).toFloat()

            val barAlpha = ((alpha * 0.75f) * 255f).toInt().coerceIn(0, 255)

            // Desfase Cian / Celeste a la derecha
            val cyanColor = (barAlpha shl 24) or 0x00E5FF
            drawSolidQuad((shift * 0.8f).coerceAtLeast(0f), y, width, y + h, cyanColor)

            // Desfase Magenta / Rojo a la izquierda
            val magentaColor = (barAlpha shl 24) or 0xFF0055
            drawSolidQuad(0f, y, (width - shift * 0.8f).coerceAtMost(width), y + h, magentaColor)

            // Núcleo blanco de interferencia
            if (i % 2 == 0) {
                val whiteAlpha = ((barAlpha * 0.7f).toInt()).coerceIn(0, 255)
                val whiteColor = (whiteAlpha shl 24) or 0xFFFFFF
                val tearX = (Math.abs(hash shr 4) % (width.toInt() / 2)).toFloat()
                val tearW = 40f + (Math.abs(hash shr 12) % (width.toInt() / 2)).toFloat()
                drawSolidQuad(tearX, y + 1f, (tearX + tearW).coerceAtMost(width), y + h - 1f, whiteColor)
            }
        }
    }

    // Comprobación segura de MirrorWorldClient
    private var mirrorClientChecked = false
    private var mirrorGetProgressMethod: java.lang.reflect.Method? = null

    private fun getMirrorWorldTransitionProgress(): Float {
        if (!mirrorClientChecked) {
            mirrorClientChecked = true
            runCatching {
                val clazz = Class.forName("com.desgraciamon.mirror.MirrorWorldClient")
                val companionField = clazz.getField("Companion")
                val companion = companionField.get(null)
                mirrorGetProgressMethod = companion.javaClass.getMethod("getTransitionProgress")
            }
        }
        val m = mirrorGetProgressMethod ?: return 0f
        return runCatching {
            val clazz = Class.forName("com.desgraciamon.mirror.MirrorWorldClient")
            val companion = clazz.getField("Companion").get(null)
            m.invoke(companion) as? Float ?: 0f
        }.getOrDefault(0f)
    }
}
