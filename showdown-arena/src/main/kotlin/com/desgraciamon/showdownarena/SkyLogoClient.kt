package com.desgraciamon.showdownarena

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.math.Axis
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import org.joml.Matrix4f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

object SkyLogoClient {
    private val TEX_BODY = ResourceLocation.fromNamespaceAndPath("showdown_arena", "textures/sky/d3s_body.png")
    private val TEX_THREE = ResourceLocation.fromNamespaceAndPath("showdown_arena", "textures/sky/d3s_three.png")

    private const val TOTAL_DURATION_SEC = 20.0f
    private const val LOGO_WIDTH = 300.0f
    private const val LOGO_HEIGHT = LOGO_WIDTH * (182.0f / 1024.0f) // ~53.3 blocks tall

    private data class ConstellationStar(
        val startX: Float,
        val startY: Float,
        val startZ: Float,
        val targetX: Float,
        val targetY: Float,
        val appearDelay: Float
    )

    private data class ActiveShow(
        val dimension: String,
        val centerX: Double,
        val centerY: Double,
        val centerZ: Double,
        val yawDeg: Float,
        val startLocalMs: Long,
        val stars: List<ConstellationStar>,
        val fireworks: List<FireworkBurst>
    )

    private data class FireworkBurst(
        val launchSec: Float,
        val burstSec: Float,
        val offsetX: Float,
        val offsetY: Float,
        val offsetZ: Float,
        val colorR: Float,
        val colorG: Float,
        val colorB: Float,
        val sparks: List<Spark>
    )

    private data class Spark(
        val vx: Float,
        val vy: Float,
        val vz: Float,
        val size: Float,
        val lifeSec: Float,
        val tintWhite: Float
    )

    @Volatile
    private var activeShow: ActiveShow? = null

    @Volatile
    private var playedBurstIndex = -1

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(SkyLogoPayload.ID) { payload, _ ->
            val now = System.currentTimeMillis()
            activeShow = ActiveShow(
                dimension = payload.dimension,
                centerX = payload.centerX,
                centerY = payload.centerY,
                centerZ = payload.centerZ,
                yawDeg = payload.yawDeg,
                startLocalMs = now,
                stars = generateThreeConstellation(payload.startServerMs),
                fireworks = generateFireworks(payload.startServerMs)
            )
            playedBurstIndex = -1
        }
    }

    fun reset() {
        activeShow = null
        playedBurstIndex = -1
    }

    /**
     * Builds an 11-star constellation tracing the exact '3' position inside the D3SGRACIAMON logo.
     */
    private fun generateThreeConstellation(seed: Long): List<ConstellationStar> {
        val rng = Random(seed + 77L)
        val halfW = LOGO_WIDTH * 0.5f
        val halfH = LOGO_HEIGHT * 0.5f
        // Normalized coordinates of the '3' within the 1024x182 logo (centered around x = -0.71 * halfW)
        val nodes = listOf(
            -0.78f to 0.72f,
            -0.66f to 0.80f,
            -0.59f to 0.45f,
            -0.64f to 0.08f,
            -0.74f to 0.02f,
            -0.62f to -0.06f,
            -0.58f to -0.46f,
            -0.66f to -0.78f,
            -0.79f to -0.70f
        )
        return nodes.mapIndexed { idx, (nx, ny) ->
            val tx = nx * halfW
            val ty = ny * halfH
            val scatterAng = rng.nextFloat() * (2.0f * PI.toFloat())
            val scatterDist = 34.0f + rng.nextFloat() * 44.0f
            ConstellationStar(
                startX = tx + cos(scatterAng) * scatterDist,
                startY = ty + sin(scatterAng) * scatterDist,
                startZ = -24.0f + (rng.nextFloat() - 0.5f) * 26.0f,
                targetX = tx,
                targetY = ty,
                appearDelay = idx * 0.14f
            )
        }
    }

    private fun generateFireworks(seed: Long): List<FireworkBurst> {
        val rng = Random(seed)
        val palette = listOf(
            Triple(0.32f, 0.88f, 1.00f), // Sky cyan
            Triple(1.00f, 0.82f, 0.22f), // Gold
            Triple(1.00f, 0.24f, 0.38f), // Crimson
            Triple(0.28f, 0.96f, 0.56f), // Emerald
            Triple(0.78f, 0.36f, 1.00f), // Violet
            Triple(1.00f, 0.52f, 0.16f), // Amber orange
            Triple(0.38f, 0.68f, 1.00f), // Electric blue
            Triple(0.95f, 0.98f, 1.00f), // Star white
            Triple(1.00f, 0.42f, 0.80f)  // Magenta
        )

        val totalBursts = 68
        val bursts = ArrayList<FireworkBurst>(totalBursts)
        val safeHalfW = LOGO_WIDTH * 0.5f + 22.0f  // 172 blocks
        val safeHalfH = LOGO_HEIGHT * 0.5f + 18.0f // 44.6 blocks

        for (i in 0 until totalBursts) {
            val burstT = if (i in 18..31) {
                5.4f + (i - 18) * 0.16f // Grand burst wave when D_SGRACIAMON finishes appearing
            } else {
                0.8f + (i.toFloat() / totalBursts) * 16.8f
            }
            val col = palette[i % palette.size]

            // Strictly outside the 300x53 logo rectangle so sparks never collide with letters
            val zoneType = i % 4
            val (ox, oy, oz) = when (zoneType) {
                0 -> Triple(-(safeHalfW + rng.nextFloat() * 48.0f), (rng.nextFloat() - 0.5f) * (safeHalfH * 1.8f), -8.0f + (rng.nextFloat() - 0.5f) * 20.0f)
                1 -> Triple(safeHalfW + rng.nextFloat() * 48.0f, (rng.nextFloat() - 0.5f) * (safeHalfH * 1.8f), -8.0f + (rng.nextFloat() - 0.5f) * 20.0f)
                2 -> Triple((rng.nextFloat() - 0.5f) * (safeHalfW * 1.9f), safeHalfH + rng.nextFloat() * 34.0f, -8.0f + (rng.nextFloat() - 0.5f) * 18.0f)
                else -> Triple((rng.nextFloat() - 0.5f) * (safeHalfW * 1.9f), -(safeHalfH + rng.nextFloat() * 34.0f), -8.0f + (rng.nextFloat() - 0.5f) * 18.0f)
            }

            val isGrand = (i in 18..31) || (i % 5 == 0)
            val sparkCount = if (isGrand) 34 else 22
            val sparks = List(sparkCount) { s ->
                val theta = (s.toFloat() / sparkCount) * (2.0f * PI.toFloat()) + rng.nextFloat() * 0.25f
                val phi = (rng.nextFloat() - 0.5f) * PI.toFloat() * 0.85f
                val speed = (if (isGrand) 12.0f else 8.5f) + rng.nextFloat() * 9.0f
                Spark(
                    vx = cos(theta) * cos(phi) * speed,
                    vy = sin(theta) * cos(phi) * speed,
                    vz = sin(phi) * speed,
                    size = (if (isGrand) 0.72f else 0.55f) + rng.nextFloat() * 0.38f,
                    lifeSec = 1.6f + rng.nextFloat() * 0.75f,
                    tintWhite = if (s % 4 == 0) 0.72f else 0.10f
                )
            }

            bursts.add(
                FireworkBurst(
                    launchSec = (burstT - 0.75f).coerceAtLeast(0.15f),
                    burstSec = burstT,
                    offsetX = ox,
                    offsetY = oy,
                    offsetZ = oz,
                    colorR = col.first,
                    colorG = col.second,
                    colorB = col.third,
                    sparks = sparks
                )
            )
        }
        return bursts
    }

    fun render(context: WorldRenderContext) {
        val show = activeShow ?: return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        if (level.dimension().location().toString() != show.dimension) return

        val elapsed = (System.currentTimeMillis() - show.startLocalMs) / 1000.0f
        if (elapsed > TOTAL_DURATION_SEC) {
            activeShow = null
            return
        }
        if (elapsed < 0.0f) return

        // Global fade-out + gentle ascent (17.0s -> 20.0s)
        val exitProg = ((elapsed - 17.0f) / 3.0f).coerceIn(0.0f, 1.0f)
        val globalFadeOut = 1.0f - smoothStep(exitProg)
        val exitRiseY = exitProg * exitProg * 22.0f

        // Phase 1 (0.0s -> 5.2s): Constellation stars appear with fade & move to form the '3', then '3' illuminates
        val moveProg = ((elapsed - 1.2f) / 2.8f).coerceIn(0.0f, 1.0f)
        val moveEase = 1.0f - (1.0f - moveProg).pow(3)
        val constellationLineAlpha = ((elapsed - 3.0f) / 1.4f).coerceIn(0.0f, 1.0f) *
            (1.0f - ((elapsed - 6.2f) / 1.4f).coerceIn(0.0f, 1.0f)) * globalFadeOut

        val threeProg = ((elapsed - 4.0f) / 1.6f).coerceIn(0.0f, 1.0f)
        val threeAlpha = (smoothStep(threeProg) * globalFadeOut).coerceIn(0.0f, 1.0f)
        val threeFlash = if (elapsed in 4.0f..6.4f) {
            sin(((elapsed - 4.0f) / 2.4f) * PI.toFloat()).pow(2) * 0.85f
        } else {
            0.0f
        }

        // Phase 2 (5.4s -> 7.8s): Rest of the text ('D _ SGRACIAMON') fades in with elastic expand
        val bodyEntryProg = ((elapsed - 5.4f) / 2.2f).coerceIn(0.0f, 1.0f)
        val bodyEaseOut = 1.0f - (1.0f - bodyEntryProg).pow(3)
        val bodyAlpha = (smoothStep(bodyEntryProg) * globalFadeOut).coerceIn(0.0f, 1.0f)
        val bodyScale = 0.84f + 0.16f * bodyEaseOut + exitProg * 0.06f

        val floatY = sin(elapsed * 1.4f) * 1.5f
        val pulse = (sin(elapsed * 2.6f) * 0.5f + 0.5f)
        val camPos = context.camera().position
        val matrices = context.matrixStack() ?: return
        val consumers = context.consumers()
        val useIrisPipeline = ArenaZoneClient.isIrisShaderPackActive() && consumers != null
        val usePhoton = useIrisPipeline && ArenaZoneClient.isPhotonShaderActive()
        val tessellator = RenderSystem.renderThreadTesselator()

        // Distant firework burst sound
        for (idx in show.fireworks.indices) {
            val fw = show.fireworks[idx]
            if (idx > playedBurstIndex && elapsed >= fw.burstSec && elapsed < fw.burstSec + 0.25f) {
                playedBurstIndex = idx
                if (idx % 4 == 0) {
                    val dx = player.x - show.centerX
                    val dy = player.y - show.centerY
                    val dz = player.z - show.centerZ
                    if (dx * dx + dy * dy + dz * dz < 600.0 * 600.0) {
                        runCatching {
                            level.playLocalSound(
                                player.x, player.y + 8.0, player.z,
                                SoundEvents.FIREWORK_ROCKET_LARGE_BLAST,
                                SoundSource.AMBIENT,
                                0.15f,
                                0.90f + (idx % 4) * 0.10f,
                                false
                            )
                        }
                    }
                }
            }
        }

        matrices.pushPose()
        matrices.translate(
            (show.centerX - camPos.x).toFloat(),
            (show.centerY + floatY + exitRiseY - camPos.y).toFloat(),
            (show.centerZ - camPos.z).toFloat()
        )
        matrices.mulPose(Axis.YP.rotationDegrees(-show.yawDeg))
        // Full flat horizontal facing straight down (-Y) so players on the ground read it looking up
        matrices.mulPose(Axis.XP.rotationDegrees(90.0f))

        RenderSystem.enableBlend()
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE)
        RenderSystem.disableCull()
        RenderSystem.depthMask(false)

        val m = matrices.last().pose()
        val halfW = LOGO_WIDTH * 0.5f
        val halfH = LOGO_HEIGHT * 0.5f

        // 1. Render '3' first (once its constellation completes at ~4.0s)
        if (threeAlpha > 0.01f) {
            drawLogoSingleOutline(
                tessellator, consumers, useIrisPipeline, usePhoton, m, TEX_THREE,
                halfW * (0.94f + 0.06f * bodyEaseOut + exitProg * 0.06f),
                halfH * (0.94f + 0.06f * bodyEaseOut + exitProg * 0.06f),
                0.02f, threeAlpha, pulse, threeFlash
            )
        }

        // 2. Render 'D _ SGRACIAMON' after the '3' constellation reveals
        if (bodyAlpha > 0.01f) {
            drawLogoSingleOutline(
                tessellator, consumers, useIrisPipeline, usePhoton, m, TEX_BODY,
                halfW * bodyScale, halfH * bodyScale,
                0.0f, bodyAlpha, pulse, 0.0f
            )
        }

        // 3. Render Constellation Stars, Constellation Beams, Orbiting Halo & 68 Multi-Color Fireworks
        if (!useIrisPipeline) {
            RenderSystem.setShader(GameRenderer::getPositionColorShader)
        }
        val fwAddType = if (useIrisPipeline && !usePhoton) ArenaZoneClient.irisAdditiveType() else null
        val fwBuf: com.mojang.blaze3d.vertex.VertexConsumer = if (useIrisPipeline && !usePhoton) {
            consumers!!.getBuffer(fwAddType!!)
        } else {
            tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        }
        var hasFw = false

        // 3A. '3' Constellation stars appearing with fade & moving into formation
        val starFadeOutWithThree = (1.0f - ((elapsed - 6.8f) / 1.8f).coerceIn(0.0f, 1.0f)) * globalFadeOut
        if (starFadeOutWithThree > 0.01f) {
            val curPositions = ArrayList<Triple<Float, Float, Float>>(show.stars.size)
            for (i in show.stars.indices) {
                val st = show.stars[i]
                val sFadeIn = smoothStep(((elapsed - st.appearDelay) / 1.1f).coerceIn(0.0f, 1.0f))
                val sAlpha = sFadeIn * starFadeOutWithThree
                val cx = lerp(st.startX, st.targetX, moveEase)
                val cy = lerp(st.startY, st.targetY, moveEase)
                val cz = lerp(st.startZ, 0.04f, moveEase)
                curPositions.add(Triple(cx, cy, cz))

                if (sAlpha > 0.02f) {
                    val twinkle = 0.80f + 0.20f * sin(elapsed * 5.0f + i)
                    emitBillboardQuad(fwBuf, useIrisPipeline, usePhoton, m, cx, cy, cz, 3.6f, 3.6f, 0.36f, 0.88f, 1.0f, sAlpha * 0.45f * twinkle)
                    emitBillboardQuad(fwBuf, useIrisPipeline, usePhoton, m, cx, cy, cz, 1.4f, 1.4f, 0.92f, 0.99f, 1.0f, sAlpha * 0.95f * twinkle)
                    hasFw = true
                }
            }

            // Constellation connecting beams forming the '3'
            if (constellationLineAlpha > 0.02f) {
                for (i in 0 until curPositions.size - 1) {
                    val p0 = curPositions[i]
                    val p1 = curPositions[i + 1]
                    val dx = p1.first - p0.first
                    val dy = p1.second - p0.second
                    val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
                    val nx = -dy / len * 0.45f
                    val ny = dx / len * 0.45f
                    val la = constellationLineAlpha * 0.78f
                    if (usePhoton) {
                        ArenaZoneClient.emitPhotonAdditiveQuad(
                            fwBuf, m,
                            p0.first - nx, p0.second - ny, p0.third, 0.45f, 0.92f, 1.0f, la,
                            p0.first + nx, p0.second + ny, p0.third, 0.45f, 0.92f, 1.0f, la,
                            p1.first + nx, p1.second + ny, p1.third, 0.45f, 0.92f, 1.0f, la,
                            p1.first - nx, p1.second - ny, p1.third, 0.45f, 0.92f, 1.0f, la,
                            1.0f
                        )
                    } else {
                        emitFwVertex(fwBuf, useIrisPipeline, usePhoton, m, p0.first - nx, p0.second - ny, p0.third, 0.45f, 0.92f, 1.0f, la)
                        emitFwVertex(fwBuf, useIrisPipeline, usePhoton, m, p0.first + nx, p0.second + ny, p0.third, 0.45f, 0.92f, 1.0f, la)
                        emitFwVertex(fwBuf, useIrisPipeline, usePhoton, m, p1.first + nx, p1.second + ny, p1.third, 0.45f, 0.92f, 1.0f, la)
                        emitFwVertex(fwBuf, useIrisPipeline, usePhoton, m, p1.first - nx, p1.second - ny, p1.third, 0.45f, 0.92f, 1.0f, la)
                    }
                    hasFw = true
                }
            }
        }

        // 3B. Expanding sky-blue shockwave ring when the full name reveals (5.4s -> 7.8s)
        if (elapsed in 5.4f..7.8f) {
            val ringT = (elapsed - 5.4f) / 2.4f
            val ringAlpha = (1.0f - ringT).pow(2) * 0.70f * globalFadeOut
            val ringRx = lerp(12.0f, halfW * 1.15f, ringT)
            val ringRy = lerp(8.0f, halfH * 1.45f, ringT)
            val ringSegs = 40
            for (k in 0 until ringSegs) {
                val a0 = (k.toFloat() / ringSegs) * (2.0f * PI.toFloat())
                val a1 = ((k + 1).toFloat() / ringSegs) * (2.0f * PI.toFloat())
                val x0 = cos(a0) * ringRx; val y0 = sin(a0) * ringRy
                val x1 = cos(a1) * ringRx; val y1 = sin(a1) * ringRy
                val thick = 1.4f
                if (usePhoton) {
                    ArenaZoneClient.emitPhotonAdditiveQuad(
                        fwBuf, m,
                        x0, y0, 0.1f, 0.45f, 0.90f, 1.0f, ringAlpha,
                        x1, y1, 0.1f, 0.45f, 0.90f, 1.0f, ringAlpha,
                        x1 + cos(a1) * thick, y1 + sin(a1) * thick, 0.1f, 0.45f, 0.90f, 1.0f, 0.0f,
                        x0 + cos(a0) * thick, y0 + sin(a0) * thick, 0.1f, 0.45f, 0.90f, 1.0f, 0.0f,
                        1.0f
                    )
                } else {
                    emitFwVertex(fwBuf, useIrisPipeline, usePhoton, m, x0, y0, 0.1f, 0.45f, 0.90f, 1.0f, ringAlpha)
                    emitFwVertex(fwBuf, useIrisPipeline, usePhoton, m, x1, y1, 0.1f, 0.45f, 0.90f, 1.0f, ringAlpha)
                    emitFwVertex(fwBuf, useIrisPipeline, usePhoton, m, x1 + cos(a1) * thick, y1 + sin(a1) * thick, 0.1f, 0.45f, 0.90f, 1.0f, 0.0f)
                    emitFwVertex(fwBuf, useIrisPipeline, usePhoton, m, x0 + cos(a0) * thick, y0 + sin(a0) * thick, 0.1f, 0.45f, 0.90f, 1.0f, 0.0f)
                }
                hasFw = true
            }
        }

        // 3C. Orbiting twinkling star motes framing the giant logo
        val starCount = 34
        for (s in 0 until starCount) {
            val ang = (s.toFloat() / starCount) * (2.0f * PI.toFloat()) + elapsed * 0.22f
            val sx = cos(ang) * (halfW + 18.0f)
            val sy = sin(ang) * (halfH + 14.0f)
            val sz = sin(elapsed * 2.0f + s) * 2.2f
            val twinkle = (sin(elapsed * 4.5f + s * 1.7f) * 0.5f + 0.5f) * bodyAlpha
            if (twinkle > 0.04f) {
                emitBillboardQuad(fwBuf, useIrisPipeline, usePhoton, m, sx, sy, sz, 1.6f, 1.6f, 0.48f, 0.90f, 1.0f, twinkle * 0.55f)
                emitBillboardQuad(fwBuf, useIrisPipeline, usePhoton, m, sx, sy, sz, 0.72f, 0.72f, 0.95f, 0.99f, 1.0f, twinkle * 0.90f)
                hasFw = true
            }
        }

        // 3D. 68 multi-color fireworks framing the logo without colliding with letters
        for (fw in show.fireworks) {
            if (elapsed < fw.launchSec) continue

            if (elapsed < fw.burstSec) {
                val prog = ((elapsed - fw.launchSec) / (fw.burstSec - fw.launchSec)).coerceIn(0.0f, 1.0f)
                val rx = fw.offsetX
                val ry = fw.offsetY
                val rz = fw.offsetZ + 38.0f * (1.0f - prog)
                emitBillboardQuad(fwBuf, useIrisPipeline, usePhoton, m, rx, ry, rz, 0.85f, 0.85f, fw.colorR, fw.colorG, fw.colorB, 0.85f * globalFadeOut)
                emitBillboardQuad(fwBuf, useIrisPipeline, usePhoton, m, rx, ry, rz + 2.2f, 1.6f, 1.6f, 0.92f, 0.98f, 1.0f, 0.55f * globalFadeOut)
                hasFw = true
            } else {
                val age = elapsed - fw.burstSec
                for (sp in fw.sparks) {
                    if (age > sp.lifeSec) continue
                    val p = (age / sp.lifeSec).coerceIn(0.0f, 1.0f)
                    val fade = (1.0f - p).pow(1.5f) * globalFadeOut
                    if (fade <= 0.02f) continue

                    val drag = 1.0f - 0.30f * age.coerceAtMost(1.8f)
                    val sx = fw.offsetX + sp.vx * age * drag
                    val sy = fw.offsetY + sp.vy * age * drag
                    val sz = fw.offsetZ + sp.vz * age * drag + 3.2f * age * age

                    val r = (fw.colorR + sp.tintWhite * (1.0f - fw.colorR)).coerceIn(0f, 1f)
                    val g = (fw.colorG + sp.tintWhite * (1.0f - fw.colorG)).coerceIn(0f, 1f)
                    val b = (fw.colorB + sp.tintWhite * (1.0f - fw.colorB)).coerceIn(0f, 1f)

                    emitBillboardQuad(fwBuf, useIrisPipeline, usePhoton, m, sx, sy, sz, sp.size * 2.7f, sp.size * 2.7f, r, g, b, fade * 0.48f)
                    emitBillboardQuad(fwBuf, useIrisPipeline, usePhoton, m, sx, sy, sz, sp.size, sp.size, 0.96f, 0.98f, 1.0f, fade * 0.96f)
                    hasFw = true
                }
            }
        }

        if ((!useIrisPipeline || usePhoton) && fwBuf is BufferBuilder) {
            if (usePhoton) {
                RenderSystem.setShader(GameRenderer::getPositionColorShader)
                RenderSystem.defaultBlendFunc()
            }
            if (hasFw) {
                BufferUploader.drawWithShader(fwBuf.buildOrThrow())
            } else {
                runCatching { fwBuf.build()?.close() }
            }
        } else if (useIrisPipeline && !usePhoton && consumers is net.minecraft.client.renderer.MultiBufferSource.BufferSource) {
            if (fwAddType != null) consumers.endBatch(fwAddType)
        }

        matrices.popPose()

        RenderSystem.depthMask(true)
        RenderSystem.enableCull()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableBlend()
    }

    private fun drawLogoSingleOutline(
        tessellator: com.mojang.blaze3d.vertex.Tesselator,
        consumers: net.minecraft.client.renderer.MultiBufferSource?,
        useIrisPipeline: Boolean,
        usePhoton: Boolean,
        m: Matrix4f,
        tex: ResourceLocation,
        halfW: Float,
        halfH: Float,
        zOffset: Float,
        alpha: Float,
        pulse: Float,
        extraFlash: Float
    ) {
        val bloomA = ((0.70f + pulse * 0.18f + extraFlash * 0.30f) * alpha).coerceIn(0.0f, 1.0f)
        val coreA = ((0.94f + pulse * 0.06f) * alpha).coerceIn(0.0f, 1.0f)
        val coreR = (0.74f + extraFlash * 0.26f).coerceIn(0.0f, 1.0f)

        if (useIrisPipeline && consumers != null) {
            val rt = net.minecraft.client.renderer.RenderType.text(tex)
            val buf = consumers.getBuffer(rt)
            val paBloom = if (usePhoton && bloomA > 0.015f) bloomA.coerceAtLeast(0.14f) else bloomA
            val paCore = if (usePhoton && coreA > 0.015f) coreA.coerceAtLeast(0.14f) else coreA
            emitIrisTexturedQuad(buf, m, halfW, halfH, zOffset, 0.36f, 0.86f, 1.0f, paBloom)
            emitIrisTexturedQuad(buf, m, halfW, halfH, zOffset + 0.02f, coreR, 0.96f, 1.0f, paCore)
            if (consumers is net.minecraft.client.renderer.MultiBufferSource.BufferSource) {
                consumers.endBatch(rt)
            }
            return
        }

        RenderSystem.setShader(GameRenderer::getPositionTexColorShader)
        RenderSystem.setShaderTexture(0, tex)

        val buf = tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
        emitTexturedQuad(buf, m, halfW, halfH, zOffset, 0.36f, 0.86f, 1.0f, bloomA)
        emitTexturedQuad(buf, m, halfW, halfH, zOffset + 0.02f, coreR, 0.96f, 1.0f, coreA)
        BufferUploader.drawWithShader(buf.buildOrThrow())
    }

    private fun emitIrisTexturedQuad(
        buf: com.mojang.blaze3d.vertex.VertexConsumer,
        m: Matrix4f,
        halfW: Float,
        halfH: Float,
        z: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float
    ) {
        val lb = net.minecraft.client.renderer.LightTexture.FULL_BRIGHT
        buf.addVertex(m, -halfW, -halfH, z).setColor(r, g, b, a).setUv(0.0f, 1.0f).setLight(lb)
        buf.addVertex(m, halfW, -halfH, z).setColor(r, g, b, a).setUv(1.0f, 1.0f).setLight(lb)
        buf.addVertex(m, halfW, halfH, z).setColor(r, g, b, a).setUv(1.0f, 0.0f).setLight(lb)
        buf.addVertex(m, -halfW, halfH, z).setColor(r, g, b, a).setUv(0.0f, 0.0f).setLight(lb)

        buf.addVertex(m, -halfW, halfH, z).setColor(r, g, b, a).setUv(0.0f, 0.0f).setLight(lb)
        buf.addVertex(m, halfW, halfH, z).setColor(r, g, b, a).setUv(1.0f, 0.0f).setLight(lb)
        buf.addVertex(m, halfW, -halfH, z).setColor(r, g, b, a).setUv(1.0f, 1.0f).setLight(lb)
        buf.addVertex(m, -halfW, -halfH, z).setColor(r, g, b, a).setUv(0.0f, 1.0f).setLight(lb)
    }

    private fun emitTexturedQuad(
        buf: BufferBuilder,
        m: Matrix4f,
        halfW: Float,
        halfH: Float,
        z: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float
    ) {
        buf.addVertex(m, -halfW, -halfH, z).setUv(0.0f, 1.0f).setColor(r, g, b, a)
        buf.addVertex(m, halfW, -halfH, z).setUv(1.0f, 1.0f).setColor(r, g, b, a)
        buf.addVertex(m, halfW, halfH, z).setUv(1.0f, 0.0f).setColor(r, g, b, a)
        buf.addVertex(m, -halfW, halfH, z).setUv(0.0f, 0.0f).setColor(r, g, b, a)
    }

    private fun emitFwVertex(
        buf: com.mojang.blaze3d.vertex.VertexConsumer,
        useIrisPipeline: Boolean,
        usePhoton: Boolean,
        m: Matrix4f,
        x: Float,
        y: Float,
        z: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float
    ) {
        buf.addVertex(m, x, y, z).setColor(r, g, b, a.coerceIn(0f, 1f))
    }

    private fun emitBillboardQuad(
        buf: com.mojang.blaze3d.vertex.VertexConsumer,
        useIrisPipeline: Boolean,
        usePhoton: Boolean,
        m: Matrix4f,
        x: Float,
        y: Float,
        z: Float,
        w: Float,
        h: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float
    ) {
        val hw = w * 0.5f
        val hh = h * 0.5f
        if (usePhoton) {
            ArenaZoneClient.emitPhotonAdditiveQuad(
                buf, m,
                x - hw, y - hh, z, r, g, b, a,
                x + hw, y - hh, z, r, g, b, a,
                x + hw, y + hh, z, r, g, b, a,
                x - hw, y + hh, z, r, g, b, a,
                1.0f
            )
            return
        }
        emitFwVertex(buf, useIrisPipeline, usePhoton, m, x - hw, y - hh, z, r, g, b, a)
        emitFwVertex(buf, useIrisPipeline, usePhoton, m, x + hw, y - hh, z, r, g, b, a)
        emitFwVertex(buf, useIrisPipeline, usePhoton, m, x + hw, y + hh, z, r, g, b, a)
        emitFwVertex(buf, useIrisPipeline, usePhoton, m, x - hw, y + hh, z, r, g, b, a)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun smoothStep(t: Float): Float {
        val c = t.coerceIn(0.0f, 1.0f)
        return c * c * (3.0f - 2.0f * c)
    }
}
