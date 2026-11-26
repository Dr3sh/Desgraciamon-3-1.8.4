package com.desgraciamon.showdownarena

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import org.joml.Matrix4f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cliente visual y shader 3D para el Mercado Global GTS (Mercado de Buhoneros / Bazar Ambulante).
 * - Postes de carpa mercantil en el perimetro con remates dorados.
 * - Toldos y marquesinas arqueadas a rayas (carmesi calido, ambar-oro y esmeralda) sobre arcos abiertos.
 * - Faroles luminosos colgantes balanceandose suavemente en guirnaldas entre los postes.
 * - Medallon de bazar dorado-carmesi en el suelo central y motas ascendentes de monedas doradas.
 */
object GtsZoneClient {
    var currentZone: GtsZone? = null
    var isEnabled: Boolean = true

    private var wasInsideZone = false
    private var zoneEnterTimeMs: Long = 0L
    private const val DISPLAY_DURATION_MS = 3600L

    private const val AWNING_STRIPES = 6
    private const val RING_SEGMENTS = 32
    private const val MOTE_COUNT = 24
    private const val MAX_RENDER_DIST_MARGIN = 100.0f

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(GtsZoneSyncPayload.ID) { payload, _ ->
            isEnabled = payload.enabled
            if (payload.hasZone) {
                currentZone = GtsZone(
                    name = payload.name,
                    dimension = payload.dimension,
                    minX = payload.minX,
                    minY = payload.minY,
                    minZ = payload.minZ,
                    maxX = payload.maxX,
                    maxY = payload.maxY,
                    maxZ = payload.maxZ
                )
            } else {
                currentZone = null
            }
        }
    }

    /**
     * Obtiene la elevacion exacta del terreno en (worldX, worldZ) adaptandose a bloques,
     * losas, escaleras, agua y construcciones en tiempo real.
     */
    fun getGroundY(level: Level, worldX: Double, worldZ: Double, referenceY: Double, playerY: Double): Float {
        val bx = Mth.floor(worldX)
        val bz = Mth.floor(worldZ)
        val refY = Mth.floor(referenceY)
        val pY = Mth.floor(playerY)
        val maxY = (maxOf(refY, pY) + 12).coerceAtMost(level.maxBuildHeight - 2)
        val minY = (minOf(refY, pY) - 12).coerceAtLeast(level.minBuildHeight)

        val checkPos = BlockPos.MutableBlockPos()
        val abovePos = BlockPos.MutableBlockPos()
        for (y in maxY downTo minY) {
            checkPos.set(bx, y, bz)
            val state = level.getBlockState(checkPos)
            if (state.isAir) continue
            val shape = state.getCollisionShape(level, checkPos)
            val hasFluid = !state.fluidState.isEmpty
            if (shape.isEmpty && !hasFluid) continue
            abovePos.set(bx, y + 1, bz)
            val stateAbove = level.getBlockState(abovePos)
            val aboveShape = stateAbove.getCollisionShape(level, abovePos)
            if (stateAbove.isAir || aboveShape.isEmpty || stateAbove.canBeReplaced()) {
                val topOffset = if (!shape.isEmpty) {
                    shape.max(Direction.Axis.Y)
                } else {
                    0.88
                }
                return (y + topOffset).toFloat()
            }
        }
        return referenceY.toFloat()
    }

    /**
     * Anuncio HUD al ingresar al Mercado Global GTS.
     */
    fun renderHud(graphics: GuiGraphics) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val zone = currentZone
        if (!isEnabled || zone == null) {
            wasInsideZone = false
            return
        }

        val currentDim = player.level().dimension().location().toString()
        val isInside = zone.contains(currentDim, player.x, player.y, player.z)

        if (!wasInsideZone && isInside) {
            zoneEnterTimeMs = System.currentTimeMillis()
            try {
                mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.25f))
                mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.9f))
            } catch (_: Exception) {}
        }
        wasInsideZone = isInside

        if (zoneEnterTimeMs == 0L) return
        val elapsed = System.currentTimeMillis() - zoneEnterTimeMs
        if (elapsed > DISPLAY_DURATION_MS) return

        val alpha = when {
            elapsed < 450L -> (elapsed / 450f).coerceIn(0f, 1f)
            elapsed < 2600L -> 1.0f
            else -> ((DISPLAY_DURATION_MS - elapsed) / 1000f).coerceIn(0f, 1f)
        }
        if (alpha <= 0.01f) return

        val alphaByte = (alpha * 255).toInt().coerceIn(0, 255)
        val font = mc.font
        val screenW = graphics.guiWidth()
        val screenH = graphics.guiHeight()
        val centerX = screenW / 2

        val titleY = (screenH * 0.21f).toInt()

        val matrices = graphics.pose()
        matrices.pushPose()
        matrices.translate(centerX.toFloat(), titleY.toFloat(), 0f)

        val popScale = 1.75f + (1f - alpha.coerceAtMost(1f)) * 0.10f
        matrices.scale(popScale, popScale, 1.0f)

        val titleText = "MERCADO GLOBAL GTS"
        val titleW = font.width(titleText)

        val titleColor = (alphaByte shl 24) or 0xFFD700
        val shadowColor = (alphaByte shl 24) or 0x4A2C00
        graphics.drawString(font, titleText, -titleW / 2 + 1, 1, shadowColor, false)
        graphics.drawString(font, titleText, -titleW / 2, 0, titleColor, false)

        matrices.popPose()

        val subY = titleY + (font.lineHeight * popScale).toInt() + 5
        val lineW = (160 * alpha).toInt()
        val lineColor = (alphaByte shl 24) or 0xFFB300
        graphics.fill(centerX - lineW / 2, subY - 3, centerX + lineW / 2, subY - 2, lineColor)

        val subText = "Comercio e Intercambio Pokémon Habilitados (/gts)"
        val subW = font.width(subText)
        val subColor = (alphaByte shl 24) or 0xFFF3B0
        graphics.drawString(font, subText, centerX - subW / 2, subY, subColor, true)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private var activeUseIris = false
    private var activeUsePhoton = false
    private var activeIrisAlpha = 1.0f

    private fun emitQuad(
        buf: VertexConsumer,
        m: Matrix4f,
        x0: Float, y0: Float, z0: Float, r0: Float, g0: Float, b0: Float, a0: Float,
        x1: Float, y1: Float, z1: Float, r1: Float, g1: Float, b1: Float, a1: Float,
        x2: Float, y2: Float, z2: Float, r2: Float, g2: Float, b2: Float, a2: Float,
        x3: Float, y3: Float, z3: Float, r3: Float, g3: Float, b3: Float, a3: Float
    ) {
        if (activeUsePhoton) {
            ArenaZoneClient.emitPhotonTranslucentQuad(
                buf, m,
                x0, y0, z0, r0, g0, b0, a0 * activeIrisAlpha,
                x1, y1, z1, r1, g1, b1, a1 * activeIrisAlpha,
                x2, y2, z2, r2, g2, b2, a2 * activeIrisAlpha,
                x3, y3, z3, r3, g3, b3, a3 * activeIrisAlpha,
                1.0f
            )
            return
        }
        if (activeUseIris) {
            ArenaZoneClient.emitIrisTranslucentQuad(
                buf, m,
                x0, y0, z0, r0, g0, b0, a0,
                x1, y1, z1, r1, g1, b1, a1,
                x2, y2, z2, r2, g2, b2, a2,
                x3, y3, z3, r3, g3, b3, a3,
                activeIrisAlpha
            )
            return
        }
        buf.addVertex(m, x0, y0, z0).setColor(r0, g0, b0, (a0 * activeIrisAlpha).coerceIn(0f, 1f))
        buf.addVertex(m, x1, y1, z1).setColor(r1, g1, b1, (a1 * activeIrisAlpha).coerceIn(0f, 1f))
        buf.addVertex(m, x2, y2, z2).setColor(r2, g2, b2, (a2 * activeIrisAlpha).coerceIn(0f, 1f))
        buf.addVertex(m, x3, y3, z3).setColor(r3, g3, b3, (a3 * activeIrisAlpha).coerceIn(0f, 1f))
    }

    private fun emitGlowQuad(
        buf: VertexConsumer,
        m: Matrix4f,
        x0: Float, y0: Float, z0: Float, r0: Float, g0: Float, b0: Float, a0: Float,
        x1: Float, y1: Float, z1: Float, r1: Float, g1: Float, b1: Float, a1: Float,
        x2: Float, y2: Float, z2: Float, r2: Float, g2: Float, b2: Float, a2: Float,
        x3: Float, y3: Float, z3: Float, r3: Float, g3: Float, b3: Float, a3: Float
    ) {
        if (activeUsePhoton) {
            ArenaZoneClient.emitPhotonAdditiveQuad(
                buf, m,
                x0, y0, z0, r0, g0, b0, a0 * activeIrisAlpha,
                x1, y1, z1, r1, g1, b1, a1 * activeIrisAlpha,
                x2, y2, z2, r2, g2, b2, a2 * activeIrisAlpha,
                x3, y3, z3, r3, g3, b3, a3 * activeIrisAlpha,
                1.0f
            )
            return
        }
        if (activeUseIris) {
            ArenaZoneClient.emitIrisAdditiveQuad(
                buf, m,
                x0, y0, z0, r0, g0, b0, a0,
                x1, y1, z1, r1, g1, b1, a1,
                x2, y2, z2, r2, g2, b2, a2,
                x3, y3, z3, r3, g3, b3, a3,
                activeIrisAlpha
            )
            return
        }
        buf.addVertex(m, x0, y0, z0).setColor(r0, g0, b0, (a0 * activeIrisAlpha).coerceIn(0f, 1f))
        buf.addVertex(m, x1, y1, z1).setColor(r1, g1, b1, (a1 * activeIrisAlpha).coerceIn(0f, 1f))
        buf.addVertex(m, x2, y2, z2).setColor(r2, g2, b2, (a2 * activeIrisAlpha).coerceIn(0f, 1f))
        buf.addVertex(m, x3, y3, z3).setColor(r3, g3, b3, (a3 * activeIrisAlpha).coerceIn(0f, 1f))
    }

    private fun emitLine(
        buf: VertexConsumer,
        m: Matrix4f,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        if (activeUsePhoton) {
            ArenaZoneClient.emitPhotonNeonLine(buf, m, x0, y0, z0, r, g, b, a * activeIrisAlpha, x1, y1, z1, r, g, b, a * activeIrisAlpha, 0.025f, 1.0f)
            return
        }
        if (activeUseIris) {
            ArenaZoneClient.emitIrisNeonLine(buf, m, x0, y0, z0, r, g, b, a, x1, y1, z1, r, g, b, a, 0.025f, activeIrisAlpha)
            return
        }
        val ca = (a * activeIrisAlpha).coerceIn(0f, 1f)
        buf.addVertex(m, x0, y0, z0).setColor(r, g, b, ca)
        buf.addVertex(m, x1, y1, z1).setColor(r, g, b, ca)
    }

    private fun emitLineGrad(
        buf: VertexConsumer,
        m: Matrix4f,
        x0: Float, y0: Float, z0: Float, r0: Float, g0: Float, b0: Float, a0: Float,
        x1: Float, y1: Float, z1: Float, r1: Float, g1: Float, b1: Float, a1: Float
    ) {
        if (activeUsePhoton) {
            ArenaZoneClient.emitPhotonNeonLine(buf, m, x0, y0, z0, r0, g0, b0, a0 * activeIrisAlpha, x1, y1, z1, r1, g1, b1, a1 * activeIrisAlpha, 0.025f, 1.0f)
            return
        }
        if (activeUseIris) {
            ArenaZoneClient.emitIrisNeonLine(buf, m, x0, y0, z0, r0, g0, b0, a0, x1, y1, z1, r1, g1, b1, a1, 0.025f, activeIrisAlpha)
            return
        }
        buf.addVertex(m, x0, y0, z0).setColor(r0, g0, b0, (a0 * activeIrisAlpha).coerceIn(0f, 1f))
        buf.addVertex(m, x1, y1, z1).setColor(r1, g1, b1, (a1 * activeIrisAlpha).coerceIn(0f, 1f))
    }

    /**
     * Emite un farol colgante luminoso 3D (cuerpo calido de papel/laton + aureola interna).
     */
    private fun emitLanternQuads(
        buf: VertexConsumer,
        m: Matrix4f,
        lx: Float, ly: Float, lz: Float,
        radius: Float, halfH: Float,
        glow: Float
    ) {
        val topY = ly + halfH
        val botY = ly - halfH
        val rCore = 1.0f
        val gCore = (0.78f + glow * 0.18f).coerceIn(0f, 1f)
        val bCore = (0.24f + glow * 0.28f).coerceIn(0f, 1f)
        val aMid = (0.72f + glow * 0.22f).coerceIn(0f, 0.94f)
        val aCap = 0.52f

        val dx = floatArrayOf(radius, 0f, -radius, 0f)
        val dz = floatArrayOf(0f, radius, 0f, -radius)

        for (k in 0 until 4) {
            val next = (k + 1) % 4
            // Upper lantern bevel
            emitQuad(
                buf, m,
                lx + dx[k] * 0.55f, topY, lz + dz[k] * 0.55f, 0.92f, 0.24f, 0.20f, aCap,
                lx + dx[next] * 0.55f, topY, lz + dz[next] * 0.55f, 0.92f, 0.24f, 0.20f, aCap,
                lx + dx[next], ly, lz + dz[next], rCore, gCore, bCore, aMid,
                lx + dx[k], ly, lz + dz[k], rCore, gCore, bCore, aMid
            )
            // Lower lantern bevel
            emitQuad(
                buf, m,
                lx + dx[k], ly, lz + dz[k], rCore, gCore, bCore, aMid,
                lx + dx[next], ly, lz + dz[next], rCore, gCore, bCore, aMid,
                lx + dx[next] * 0.50f, botY, lz + dz[next] * 0.50f, 1.0f, 0.58f, 0.14f, aCap,
                lx + dx[k] * 0.50f, botY, lz + dz[k] * 0.50f, 1.0f, 0.58f, 0.14f, aCap
            )
        }
    }

    /**
     * Renderiza el Mercado de Buhoneros 3D (Postes mercantiles, toldos arqueados a rayas,
     * faroles colgantes con balanceo, medallon de bazar central y motas doradas).
     */
    fun render(context: WorldRenderContext) {
        val zone = currentZone ?: return
        if (!isEnabled) return
        if (ArenaZoneClient.isIrisRenderingShadowPass()) return
        val domeExclusionAlpha = (1.0f - ArenaZoneClient.insideDarkRoomFactor).coerceIn(0.0f, 1.0f)
        if (domeExclusionAlpha <= 0.01f) return

        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        val currentDim = level.dimension().location().toString()
        if (zone.dimension != currentDim) return

        val minX = minOf(zone.minX, zone.maxX)
        val maxX = maxOf(zone.minX, zone.maxX)
        val minZ = minOf(zone.minZ, zone.maxZ)
        val maxZ = maxOf(zone.minZ, zone.maxZ)
        val width = (maxX - minX).toFloat()
        val depth = (maxZ - minZ).toFloat()
        val halfW = width * 0.5f
        val halfD = depth * 0.5f
        if (halfW < 0.75f || halfD < 0.75f) return

        val camera = context.camera()
        val camPos = camera.position
        val matrices = context.matrixStack() ?: return

        val centerWorldX = (minX + maxX) * 0.5
        val centerWorldZ = (minZ + maxZ) * 0.5
        val chunkX = Mth.floor(centerWorldX) shr 4
        val chunkZ = Mth.floor(centerWorldZ) shr 4
        if (!level.chunkSource.hasChunk(chunkX, chunkZ)) return

        val cx = (centerWorldX - camPos.x).toFloat()
        val cz = (centerWorldZ - camPos.z).toFloat()

        val irisActive = ArenaZoneClient.isIrisShaderPackActive()
        val isPhoton = irisActive && ArenaZoneClient.isPhotonShaderActive()
        val consumers = if (irisActive && !isPhoton) context.consumers() else null
        val useIrisNonPhoton = irisActive && !isPhoton && consumers != null
        val usePhotonPipeline = isPhoton
        val useIrisPipeline = useIrisNonPhoton || usePhotonPipeline

        val maxRadius = maxOf(halfW, halfD)
        val renderDistBlocks = mc.options.renderDistance().get() * 16.0f
        val fogEnd = RenderSystem.getShaderFogEnd()
        val effectiveLimit = if (useIrisPipeline) renderDistBlocks else if (fogEnd > 16.0f) minOf(renderDistBlocks, fogEnd) else renderDistBlocks
        val distToEdge = (sqrt(cx * cx + cz * cz) - maxRadius).coerceAtLeast(0.0f)
        if (distToEdge > effectiveLimit * 0.88f) return
        val fogFadeStart = effectiveLimit * 0.58f
        val fogAlpha = if (distToEdge <= fogFadeStart) {
            1.0f
        } else {
            (1.0f - (distToEdge - fogFadeStart) / (effectiveLimit * 0.30f).coerceAtLeast(1.0f)).coerceIn(0.0f, 1.0f)
        }
        val effectiveAlpha = fogAlpha * domeExclusionAlpha
        if (effectiveAlpha <= 0.01f) return

        val minR = minOf(halfW, halfD)
        val timeSeconds = (System.currentTimeMillis() % 1000000L) / 1000.0
        val pulse = (sin(timeSeconds * 2.1) * 0.5 + 0.5).toFloat()
        val twoPi = (2.0 * PI).toFloat()

        val centerGroundY = (getGroundY(level, centerWorldX, centerWorldZ, zone.minY, player.y) - camPos.y).toFloat()

        // Build evenly spaced merchant tent post positions around the perimeter with outward edge normals
        val segsX = (width / 4.8f).roundToInt().coerceIn(1, 8)
        val segsZ = (depth / 4.8f).roundToInt().coerceIn(1, 8)
        val postCount = 2 * (segsX + segsZ)
        val postX = FloatArray(postCount)
        val postY = FloatArray(postCount)
        val postZ = FloatArray(postCount)
        val spanNormX = FloatArray(postCount)
        val spanNormZ = FloatArray(postCount)
        val cornerIndices = IntArray(4)

        var idx = 0
        fun addPost(wx: Double, wz: Double, nx: Float, nz: Float) {
            postX[idx] = (wx - camPos.x).toFloat()
            postZ[idx] = (wz - camPos.z).toFloat()
            postY[idx] = (getGroundY(level, wx, wz, zone.minY, player.y) - camPos.y).toFloat() + 0.02f
            spanNormX[idx] = nx
            spanNormZ[idx] = nz
            idx++
        }

        // North edge (-Z outward)
        cornerIndices[0] = idx
        for (i in 0 until segsX) {
            val t = i.toDouble() / segsX
            addPost(minX + (maxX - minX) * t, minZ, 0f, -1f)
        }
        // East edge (+X outward)
        cornerIndices[1] = idx
        for (i in 0 until segsZ) {
            val t = i.toDouble() / segsZ
            addPost(maxX, minZ + (maxZ - minZ) * t, 1f, 0f)
        }
        // South edge (+Z outward)
        cornerIndices[2] = idx
        for (i in 0 until segsX) {
            val t = i.toDouble() / segsX
            addPost(maxX - (maxX - minX) * t, maxZ, 0f, 1f)
        }
        // West edge (-X outward)
        cornerIndices[3] = idx
        for (i in 0 until segsZ) {
            val t = i.toDouble() / segsZ
            addPost(minX, maxZ - (maxZ - minZ) * t, -1f, 0f)
        }

        val postH = (minR * 0.44f).coerceIn(3.2f, 4.9f)
        var avgPostTopY = 0f
        for (i in 0 until postCount) {
            avgPostTopY += postY[i] + postH
        }
        avgPostTopY /= postCount

        val chandelierY = maxOf(centerGroundY + postH, avgPostTopY) + 1.35f + sin(timeSeconds * 1.35).toFloat() * 0.14f
        val chandelierR = (minR * 0.24f).coerceIn(1.1f, 2.8f)

        // Festive Bazaar Palette: Warm Crimson, Amber-Gold, Emerald Silk
        val crimR = 0.94f; val crimG = 0.20f; val crimB = 0.24f
        val goldR = 1.00f; val goldG = 0.78f + pulse * 0.10f; val goldB = 0.16f
        val emR = 0.10f; val emG = 0.84f + pulse * 0.08f; val emB = 0.48f

        activeUseIris = useIrisNonPhoton
        activeUsePhoton = usePhotonPipeline
        activeIrisAlpha = effectiveAlpha

        matrices.pushPose()

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.enableDepthTest()
        RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL)
        RenderSystem.disableCull()
        RenderSystem.depthMask(false)
        RenderSystem.enablePolygonOffset()
        RenderSystem.polygonOffset(-1.0f, -2.0f)
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, effectiveAlpha)
        RenderSystem.setShader(GameRenderer::getPositionColorShader)

        val tessellator = RenderSystem.renderThreadTesselator()
        val matrix = matrices.last().pose()

        // =========================================================================
        // PASS 1: TRANSLUCENT QUADS (Tent Posts, Striped Bazaar Awnings, Swaying
        //         Market Lanterns, Central Chandelier, Bazaar Floor Medallion, Motes)
        // =========================================================================
        val photonTransAlloc: ByteBufferBuilder? = if (usePhotonPipeline) ByteBufferBuilder(262144) else null
        val photonAddAlloc: ByteBufferBuilder? = if (usePhotonPipeline) ByteBufferBuilder(262144) else null
        val photonTransBuf: BufferBuilder? = photonTransAlloc?.let { BufferBuilder(it, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR) }
        val photonAddBuf: BufferBuilder? = photonAddAlloc?.let { BufferBuilder(it, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR) }
        val quadBuffer: VertexConsumer = if (usePhotonPipeline) {
            photonTransBuf!!
        } else if (useIrisNonPhoton) {
            consumers!!.getBuffer(ArenaZoneClient.irisTranslucentType())
        } else {
            tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        }
        val irisAddBuffer: VertexConsumer = if (usePhotonPipeline) {
            photonAddBuf!!
        } else if (useIrisNonPhoton) {
            consumers!!.getBuffer(ArenaZoneClient.irisAdditiveType())
        } else {
            quadBuffer
        }

        // 1A. Perimeter Merchant Tent Posts with Golden Finials
        val postR = 0.11f
        val finialH = 0.44f
        val sqX = floatArrayOf(postR, postR, -postR, -postR)
        val sqZ = floatArrayOf(postR, -postR, -postR, postR)

        for (i in 0 until postCount) {
            val px = postX[i]
            val py = postY[i]
            val pz = postZ[i]
            val topY = py + postH
            val warmGlow = 0.50f + 0.12f * sin(timeSeconds * 2.0 + i * 0.9).toFloat()

            // Square luminous timber/brass tent pole
            for (k in 0 until 4) {
                val next = (k + 1) % 4
                emitQuad(
                    quadBuffer, matrix,
                    px + sqX[k], py, pz + sqZ[k], 0.68f, 0.36f, 0.12f, warmGlow,
                    px + sqX[next], py, pz + sqZ[next], 0.68f, 0.36f, 0.12f, warmGlow,
                    px + sqX[next], topY, pz + sqZ[next], goldR, goldG, goldB, warmGlow + 0.14f,
                    px + sqX[k], topY, pz + sqZ[k], goldR, goldG, goldB, warmGlow + 0.14f
                )
            }

            // Golden diamond finial atop each post (non-degenerate cap for Iris/Photon compatibility)
            val fMidY = topY + finialH * 0.45f
            val fTopY = topY + finialH
            val fR = 0.19f
            val fCapR = 0.015f
            val fx = floatArrayOf(px + fR, px, px - fR, px)
            val fz = floatArrayOf(pz, pz + fR, pz, pz - fR)
            val fcx = floatArrayOf(px + fCapR, px, px - fCapR, px)
            val fcz = floatArrayOf(pz, pz + fCapR, pz, pz - fCapR)
            for (k in 0 until 4) {
                val next = (k + 1) % 4
                emitQuad(
                    quadBuffer, matrix,
                    fcx[k], fTopY, fcz[k], 1.0f, 0.96f, 0.56f, 0.78f,
                    fx[k], fMidY, fz[k], goldR, goldG, goldB, 0.68f,
                    fx[next], fMidY, fz[next], goldR, goldG, goldB, 0.68f,
                    fcx[next], fTopY, fcz[next], 1.0f, 0.96f, 0.56f, 0.78f
                )
                emitQuad(
                    quadBuffer, matrix,
                    fcx[k], topY, fcz[k], 0.90f, 0.52f, 0.12f, 0.62f,
                    fx[next], fMidY, fz[next], goldR, goldG, goldB, 0.68f,
                    fx[k], fMidY, fz[k], goldR, goldG, goldB, 0.68f,
                    fcx[next], topY, fcz[next], 0.90f, 0.52f, 0.12f, 0.62f
                )
            }
        }

        // 1B. Arched Striped Bazaar Awnings & Swaying Hanging Market Lanterns between Posts
        val archRise = 0.52f
        val awningOut = 0.88f
        val awningIn = 0.42f
        val awningDrop = 0.42f
        val valanceDrop = 0.24f

        for (i in 0 until postCount) {
            val next = (i + 1) % postCount
            val x0 = postX[i]
            val z0 = postZ[i]
            val y0 = postY[i] + postH
            val x1 = postX[next]
            val z1 = postZ[next]
            val y1 = postY[next] + postH
            val nx = spanNormX[i]
            val nz = spanNormZ[i]

            val dx = x1 - x0
            val dz = z1 - z0
            val spanLen = sqrt(dx * dx + dz * dz).coerceAtLeast(0.1f)
            val tanX = dx / spanLen
            val tanZ = dz / spanLen

            // Striped arched merchant canopy roof (Crimson / Amber-Gold / Emerald)
            for (s in 0 until AWNING_STRIPES) {
                val t0 = s.toFloat() / AWNING_STRIPES
                val t1 = (s + 1).toFloat() / AWNING_STRIPES

                val arch0 = sin(t0 * PI).toFloat() * archRise
                val arch1 = sin(t1 * PI).toFloat() * archRise

                val bx0 = lerp(x0, x1, t0)
                val bz0 = lerp(z0, z1, t0)
                val by0 = lerp(y0, y1, t0) + arch0

                val bx1 = lerp(x0, x1, t1)
                val bz1 = lerp(z0, z1, t1)
                val by1 = lerp(y0, y1, t1) + arch1

                val colorIdx = (s + i) % 3
                val sr = when (colorIdx) { 0 -> crimR; 1 -> goldR; else -> emR }
                val sg = when (colorIdx) { 0 -> crimG; 1 -> goldG; else -> emG }
                val sb = when (colorIdx) { 0 -> crimB; 1 -> goldB; else -> emB }
                val wave = 0.5f + 0.5f * sin(timeSeconds * 1.8 + i * 0.7 + s * 0.5).toFloat()
                val roofAlpha = 0.46f + wave * 0.14f

                // Outer sloped awning canopy (from ridge outward)
                emitQuad(
                    quadBuffer, matrix,
                    bx0 - nx * awningIn, by0 + 0.14f, bz0 - nz * awningIn, sr, sg, sb, roofAlpha * 0.72f,
                    bx1 - nx * awningIn, by1 + 0.14f, bz1 - nz * awningIn, sr, sg, sb, roofAlpha * 0.72f,
                    bx1 + nx * awningOut, by1 - awningDrop, bz1 + nz * awningOut, sr, sg, sb, roofAlpha,
                    bx0 + nx * awningOut, by0 - awningDrop, bz0 + nz * awningOut, sr, sg, sb, roofAlpha
                )

                // Hanging scalloped valance / fringe along outer awning lip
                val flutter = sin(timeSeconds * 2.6 + i * 1.1 + s * 0.9).toFloat() * 0.04f
                emitQuad(
                    quadBuffer, matrix,
                    bx0 + nx * awningOut, by0 - awningDrop, bz0 + nz * awningOut, sr, sg, sb, roofAlpha * 0.92f,
                    bx1 + nx * awningOut, by1 - awningDrop, bz1 + nz * awningOut, sr, sg, sb, roofAlpha * 0.92f,
                    bx1 + nx * (awningOut + flutter), by1 - awningDrop - valanceDrop, bz1 + nz * (awningOut + flutter), goldR, goldG, goldB, roofAlpha * 0.35f,
                    bx0 + nx * (awningOut + flutter), by0 - awningDrop - valanceDrop, bz0 + nz * (awningOut + flutter), goldR, goldG, goldB, roofAlpha * 0.35f
                )
            }

            // Hanging Glowing Market Lanterns swaying gently along each archway span
            val lanternCount = if (spanLen >= 3.4f) 2 else 1
            for (lIdx in 0 until lanternCount) {
                val lt = if (lanternCount == 2) (if (lIdx == 0) 0.32f else 0.68f) else 0.5f
                val sag = sin(lt * PI).toFloat() * 0.55f
                val anchorX = lerp(x0, x1, lt)
                val anchorZ = lerp(z0, z1, lt)
                val anchorY = lerp(y0, y1, lt) - sag

                val swayPhase = timeSeconds * 1.85 + i * 1.37 + lIdx * 2.4
                val swayAmt = sin(swayPhase).toFloat() * 0.13f
                val swayOut = cos(swayPhase * 0.75).toFloat() * 0.06f

                val lx = anchorX + tanX * swayAmt + nx * swayOut
                val lz = anchorZ + tanZ * swayAmt + nz * swayOut
                val ly = anchorY - 0.42f
                val lanternGlow = (sin(timeSeconds * 3.1 + i * 1.9 + lIdx) * 0.5 + 0.5).toFloat()

                emitLanternQuads(quadBuffer, matrix, lx, ly, lz, 0.16f, 0.21f, lanternGlow)
            }

            // Subtle warm amber ground threshold glow ribbon between post bases
            val gy0 = postY[i] + 0.025f
            val gy1 = postY[next] + 0.025f
            emitGlowQuad(
                irisAddBuffer, matrix,
                x0, gy0, z0, goldR, goldG, goldB, 0.26f + pulse * 0.10f,
                x1, gy1, z1, goldR, goldG, goldB, 0.26f + pulse * 0.10f,
                x1, gy1 + 0.08f, z1, crimR, crimG, crimB, 0.04f,
                x0, gy0 + 0.08f, z0, crimR, crimG, crimB, 0.04f
            )
        }

        // 1C. Central Overhead Grand Bazaar Chandelier Ring & 4 Hanging Lanterns
        val chInnerR = chandelierR * 0.84f
        for (i in 0 until RING_SEGMENTS) {
            val a0 = (i.toFloat() / RING_SEGMENTS) * twoPi
            val a1 = ((i + 1).toFloat() / RING_SEGMENTS) * twoPi
            val ca = 0.42f + pulse * 0.16f
            emitQuad(
                quadBuffer, matrix,
                cx + cos(a0) * chInnerR, chandelierY, cz + sin(a0) * chInnerR, goldR, goldG, goldB, ca,
                cx + cos(a0) * chandelierR, chandelierY, cz + sin(a0) * chandelierR, crimR, crimG, crimB, ca * 0.65f,
                cx + cos(a1) * chandelierR, chandelierY, cz + sin(a1) * chandelierR, crimR, crimG, crimB, ca * 0.65f,
                cx + cos(a1) * chInnerR, chandelierY, cz + sin(a1) * chInnerR, goldR, goldG, goldB, ca
            )
        }

        val chSpin = (timeSeconds * 0.38).toFloat()
        for (k in 0 until 4) {
            val a = chSpin + k * (PI.toFloat() * 0.5f)
            val lx = cx + cos(a) * chandelierR
            val lz = cz + sin(a) * chandelierR
            val ly = chandelierY - 0.55f + sin(timeSeconds * 2.2 + k * 1.5).toFloat() * 0.06f
            emitLanternQuads(quadBuffer, matrix, lx, ly, lz, 0.20f, 0.26f, pulse)
        }
        // Center grand merchant lantern
        emitLanternQuads(quadBuffer, matrix, cx, chandelierY - 0.12f, cz, 0.28f, 0.36f, pulse)

        // 1D. Cozy Bazaar Floor Medallion & Woven Merchant Carpet Inlay
        val medR = (minR * 0.42f).coerceIn(1.8f, 7.6f)
        val medY = centerGroundY + 0.068f
        val outerIn = medR * 0.86f
        val midOut = medR * 0.62f
        val midIn = medR * 0.54f
        val coinR = medR * 0.20f

        for (i in 0 until RING_SEGMENTS) {
            val a0 = (i.toFloat() / RING_SEGMENTS) * twoPi
            val a1 = ((i + 1).toFloat() / RING_SEGMENTS) * twoPi
            val c0 = cos(a0); val s0 = sin(a0)
            val c1 = cos(a1); val s1 = sin(a1)

            // Outer warm crimson & amber-gold bazaar ring
            val oa = 0.44f + pulse * 0.14f
            emitQuad(
                quadBuffer, matrix,
                cx + c0 * outerIn, medY, cz + s0 * outerIn, goldR, goldG, goldB, oa * 0.7f,
                cx + c0 * medR, medY, cz + s0 * medR, crimR, crimG, crimB, oa,
                cx + c1 * medR, medY, cz + s1 * medR, crimR, crimG, crimB, oa,
                cx + c1 * outerIn, medY, cz + s1 * outerIn, goldR, goldG, goldB, oa * 0.7f
            )

            // Inner emerald merchant ring
            val ia = 0.38f + pulse * 0.14f
            emitQuad(
                quadBuffer, matrix,
                cx + c0 * midIn, medY + 0.002f, cz + s0 * midIn, emR, emG, emB, ia,
                cx + c0 * midOut, medY + 0.002f, cz + s0 * midOut, emR, emG, emB, ia * 0.6f,
                cx + c1 * midOut, medY + 0.002f, cz + s1 * midOut, emR, emG, emB, ia * 0.6f,
                cx + c1 * midIn, medY + 0.002f, cz + s1 * midIn, emR, emG, emB, ia
            )

            // Center glowing golden coin emblem
            val ca = 0.40f + pulse * 0.22f
            val coinInner = 0.015f
            emitQuad(
                quadBuffer, matrix,
                cx + c0 * coinInner, medY + 0.004f, cz + s0 * coinInner, 1.0f, 0.96f, 0.58f, ca,
                cx + c0 * coinR, medY + 0.004f, cz + s0 * coinR, goldR, goldG, goldB, ca * 0.55f,
                cx + c1 * coinR, medY + 0.004f, cz + s1 * coinR, goldR, goldG, goldB, ca * 0.55f,
                cx + c1 * coinInner, medY + 0.004f, cz + s1 * coinInner, 1.0f, 0.96f, 0.58f, ca
            )
        }

        // 8-Pointed Bazaar Diamond Star Petals
        for (k in 0 until 8) {
            val a = (k / 8.0f) * twoPi
            val dirX = cos(a); val dirZ = sin(a)
            val perpX = -dirZ; val perpZ = dirX
            val pr = when (k % 3) { 0 -> goldR; 1 -> crimR; else -> emR }
            val pg = when (k % 3) { 0 -> goldG; 1 -> crimG; else -> emG }
            val pb = when (k % 3) { 0 -> goldB; 1 -> crimB; else -> emB }
            val pa = 0.44f + pulse * 0.16f

            val tipR = medR * 0.64f
            val midR = medR * 0.75f
            val tailR = medR * 0.85f
            val halfSpan = medR * 0.085f

            emitQuad(
                quadBuffer, matrix,
                cx + dirX * tipR, medY + 0.003f, cz + dirZ * tipR, 1.0f, 0.95f, 0.56f, pa,
                cx + dirX * midR + perpX * halfSpan, medY + 0.003f, cz + dirZ * midR + perpZ * halfSpan, pr, pg, pb, pa * 0.85f,
                cx + dirX * tailR, medY + 0.003f, cz + dirZ * tailR, pr, pg, pb, pa * 0.45f,
                cx + dirX * midR - perpX * halfSpan, medY + 0.003f, cz + dirZ * midR - perpZ * halfSpan, pr, pg, pb, pa * 0.85f
            )
        }

        // 1E. Rising Golden Coin & Sparkle Motes
        val moteTravelH = (chandelierY - centerGroundY).coerceAtLeast(3.0f)
        val goldenAngle = 2.3999632f
        for (m in 0 until MOTE_COUNT) {
            val phase = ((timeSeconds * (0.14 + (m % 5) * 0.022) + m * 0.137) % 1.0).toFloat()
            val fadeIn = (phase / 0.18f).coerceIn(0f, 1f)
            val fadeOut = ((1f - phase) / 0.24f).coerceIn(0f, 1f)
            val moteAlpha = (fadeIn * fadeOut * 0.86f).coerceIn(0f, 0.88f)
            if (moteAlpha <= 0.02f) continue

            val radialFrac = 0.16f + ((m * 0.31f) % 0.76f)
            val swirlAngle = m * goldenAngle + (timeSeconds * (0.32 + (m % 3) * 0.10)).toFloat()
            val mx = cx + cos(swirlAngle) * (halfW * radialFrac * (1f - phase * 0.22f))
            val mz = cz + sin(swirlAngle) * (halfD * radialFrac * (1f - phase * 0.22f))
            val my = centerGroundY + 0.30f + phase * moteTravelH

            val spin = (timeSeconds * 2.8 + m * 1.2).toFloat()
            val sc = cos(spin); val ss = sin(spin)
            val sizeW = if (m % 3 == 0) 0.13f else 0.10f
            val sizeH = sizeW * 1.35f

            val mr = if (m % 5 == 0) emR + 0.12f else 1.0f
            val mg = if (m % 5 == 0) 0.96f else 0.86f
            val mb = if (m % 5 == 0) 0.58f else 0.20f

            emitGlowQuad(
                irisAddBuffer, matrix,
                mx, my - sizeH, mz, mr, mg, mb, moteAlpha,
                mx + sc * sizeW, my, mz + ss * sizeW, 1.0f, 0.98f, 0.74f, moteAlpha,
                mx, my + sizeH, mz, mr, mg, mb, moteAlpha,
                mx - sc * sizeW, my, mz - ss * sizeW, 1.0f, 0.98f, 0.74f, moteAlpha
            )
        }

        if (!useIrisPipeline && quadBuffer is BufferBuilder) {
            quadBuffer.build()?.let { BufferUploader.drawWithShader(it) }
        }

        // =========================================================================
        // PASS 2: WARM BAZAAR STRUCTURAL LINES (Archways, Festoon Lantern Cords,
        //         Corner Garlands to Chandelier, Medallion Trim)
        // =========================================================================
        RenderSystem.lineWidth(2.6f)
        val lineBuffer: VertexConsumer = if (useIrisPipeline) {
            irisAddBuffer
        } else {
            tessellator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR)
        }
        val lineAlpha = 0.84f + pulse * 0.14f

        // 2A. Merchant Tent Posts Vertical Highlight & Ground Rings
        for (i in 0 until postCount) {
            val px = postX[i]
            val py = postY[i]
            val pz = postZ[i]
            emitLineGrad(
                lineBuffer, matrix,
                px, py, pz, crimR, crimG, crimB, lineAlpha * 0.75f,
                px, py + postH + finialH, pz, 1.0f, 0.94f, 0.46f, lineAlpha
            )
        }

        // 2B. Arched Stall Cornice Lines, Festoon Catenary Cords & Lantern Strings
        val cordSteps = 8
        for (i in 0 until postCount) {
            val next = (i + 1) % postCount
            val x0 = postX[i]; val z0 = postZ[i]; val y0 = postY[i] + postH
            val x1 = postX[next]; val z1 = postZ[next]; val y1 = postY[next] + postH
            val nx = spanNormX[i]; val nz = spanNormZ[i]
            val dx = x1 - x0; val dz = z1 - z0
            val spanLen = sqrt(dx * dx + dz * dz).coerceAtLeast(0.1f)
            val tanX = dx / spanLen; val tanZ = dz / spanLen

            for (step in 0 until cordSteps) {
                val t0 = step.toFloat() / cordSteps
                val t1 = (step + 1).toFloat() / cordSteps

                // Arched golden trading-stall archway ridge
                val ax0 = lerp(x0, x1, t0); val az0 = lerp(z0, z1, t0)
                val ax1 = lerp(x0, x1, t1); val az1 = lerp(z0, z1, t1)
                val ay0 = lerp(y0, y1, t0) + sin(t0 * PI).toFloat() * archRise
                val ay1 = lerp(y0, y1, t1) + sin(t1 * PI).toFloat() * archRise
                emitLine(lineBuffer, matrix, ax0, ay0, az0, ax1, ay1, az1, 1.0f, 0.90f, 0.34f, lineAlpha)

                // Outer awning lip trim
                emitLine(
                    lineBuffer, matrix,
                    ax0 + nx * awningOut, ay0 - awningDrop, az0 + nz * awningOut,
                    ax1 + nx * awningOut, ay1 - awningDrop, az1 + nz * awningOut,
                    goldR, goldG, goldB, lineAlpha * 0.75f
                )

                // Hanging festoon catenary cord sagging softly between posts
                val cy0 = lerp(y0, y1, t0) - sin(t0 * PI).toFloat() * 0.55f
                val cy1 = lerp(y0, y1, t1) - sin(t1 * PI).toFloat() * 0.55f
                emitLine(lineBuffer, matrix, ax0, cy0, az0, ax1, cy1, az1, 1.0f, 0.82f, 0.38f, lineAlpha * 0.85f)
            }

            // Lantern suspension cords
            val lanternCount = if (spanLen >= 3.4f) 2 else 1
            for (lIdx in 0 until lanternCount) {
                val lt = if (lanternCount == 2) (if (lIdx == 0) 0.32f else 0.68f) else 0.5f
                val sag = sin(lt * PI).toFloat() * 0.55f
                val anchorX = lerp(x0, x1, lt)
                val anchorZ = lerp(z0, z1, lt)
                val anchorY = lerp(y0, y1, lt) - sag

                val swayPhase = timeSeconds * 1.85 + i * 1.37 + lIdx * 2.4
                val swayAmt = sin(swayPhase).toFloat() * 0.13f
                val swayOut = cos(swayPhase * 0.75).toFloat() * 0.06f

                val lx = anchorX + tanX * swayAmt + nx * swayOut
                val lz = anchorZ + tanZ * swayAmt + nz * swayOut
                val ly = anchorY - 0.21f
                emitLine(lineBuffer, matrix, anchorX, anchorY, anchorZ, lx, ly, lz, 1.0f, 0.88f, 0.45f, 0.85f)
            }

            // Subtle warm amber ground boundary line
            emitLine(
                lineBuffer, matrix,
                x0, postY[i] + 0.03f, z0,
                x1, postY[next] + 0.03f, z1,
                goldR, goldG, goldB, lineAlpha * 0.52f
            )
        }

        // 2C. Festive Overhead Garland Cables from the 4 Corner Posts to Central Chandelier
        for (c in 0 until 4) {
            val pIdx = cornerIndices[c]
            val sx = postX[pIdx]
            val sy = postY[pIdx] + postH + finialH * 0.5f
            val sz = postZ[pIdx]
            for (step in 0 until 6) {
                val t0 = step / 6.0f
                val t1 = (step + 1) / 6.0f
                val gx0 = lerp(sx, cx, t0); val gz0 = lerp(sz, cz, t0)
                val gx1 = lerp(sx, cx, t1); val gz1 = lerp(sz, cz, t1)
                val gy0 = lerp(sy, chandelierY, t0) - sin(t0 * PI).toFloat() * 0.65f
                val gy1 = lerp(sy, chandelierY, t1) - sin(t1 * PI).toFloat() * 0.65f
                emitLine(lineBuffer, matrix, gx0, gy0, gz0, gx1, gy1, gz1, goldR, goldG, goldB, 0.62f)
            }
        }

        // Chandelier ring outline & suspension chains
        for (i in 0 until RING_SEGMENTS) {
            val a0 = (i.toFloat() / RING_SEGMENTS) * twoPi
            val a1 = ((i + 1).toFloat() / RING_SEGMENTS) * twoPi
            emitLine(
                lineBuffer, matrix,
                cx + cos(a0) * chandelierR, chandelierY, cz + sin(a0) * chandelierR,
                cx + cos(a1) * chandelierR, chandelierY, cz + sin(a1) * chandelierR,
                1.0f, 0.94f, 0.45f, 0.88f
            )
        }
        for (k in 0 until 4) {
            val a = chSpin + k * (PI.toFloat() * 0.5f)
            val lx = cx + cos(a) * chandelierR
            val lz = cz + sin(a) * chandelierR
            emitLine(lineBuffer, matrix, lx, chandelierY, lz, lx, chandelierY - 0.30f, lz, 1.0f, 0.88f, 0.45f, 0.85f)
        }

        // 2D. Bazaar Floor Medallion Outlines
        val medLineY = medY + 0.008f
        for (i in 0 until RING_SEGMENTS) {
            val a0 = (i.toFloat() / RING_SEGMENTS) * twoPi
            val a1 = ((i + 1).toFloat() / RING_SEGMENTS) * twoPi
            emitLine(
                lineBuffer, matrix,
                cx + cos(a0) * medR, medLineY, cz + sin(a0) * medR,
                cx + cos(a1) * medR, medLineY, cz + sin(a1) * medR,
                1.0f, 0.88f, 0.34f, 0.86f
            )
            emitLine(
                lineBuffer, matrix,
                cx + cos(a0) * midOut, medLineY, cz + sin(a0) * midOut,
                cx + cos(a1) * midOut, medLineY, cz + sin(a1) * midOut,
                emR, emG, emB, 0.78f
            )
        }

        if (usePhotonPipeline) {
            RenderSystem.setShader(GameRenderer::getPositionColorShader)
            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            photonTransBuf?.build()?.let { BufferUploader.drawWithShader(it) }
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE)
            photonAddBuf?.build()?.let { BufferUploader.drawWithShader(it) }
            RenderSystem.defaultBlendFunc()
            photonTransAlloc?.close()
            photonAddAlloc?.close()
        } else if (!useIrisPipeline && lineBuffer is BufferBuilder) {
            lineBuffer.build()?.let { BufferUploader.drawWithShader(it) }
        } else if (useIrisNonPhoton && consumers is MultiBufferSource.BufferSource) {
            val transType = ArenaZoneClient.irisTranslucentType()
            val addType = ArenaZoneClient.irisAdditiveType()
            consumers.endBatch(transType)
            if (addType !== transType) consumers.endBatch(addType)
        }

        activeUseIris = false
        activeUsePhoton = false
        activeIrisAlpha = 1.0f

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
        RenderSystem.disablePolygonOffset()
        RenderSystem.lineWidth(1.0f)
        RenderSystem.depthMask(true)
        RenderSystem.enableCull()
        RenderSystem.disableBlend()

        matrices.popPose()
    }
}
