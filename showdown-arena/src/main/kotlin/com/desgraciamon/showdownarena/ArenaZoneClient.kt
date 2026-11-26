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
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import org.joml.Matrix4f
import org.ladysnake.satin.api.managed.ManagedShaderEffect
import org.ladysnake.satin.api.managed.ShaderEffectManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cliente visual y shader 3D de la Zona de Combate (Estadio Pokemon Holografico).
 * - Anillo de combate circular/eliptico adaptado al terreno con pulsos encontrados carmesi/azul electrico.
 * - Emblema central de Pokeball en la pista con mitades carmesi y azul electrico + nucleo pulsante.
 * - Domo holografico 3D con arcos meridianos, anillos de latitud y oculo cenital luminoso.
 * - Reflectores de estadio en las 4 esquinas proyectando haces de luz hacia la cupula.
 */
object ArenaZoneClient {
    var currentZone: ArenaZone? = null
    var isEnabled: Boolean = false
    var lightsMode: SpotlightShowMode = SpotlightShowMode.AUTO
    var lightsRodMode: String = "1"
    var dayColor1: Int = 0xFF335C
    var dayColor2: Int = 0x24BDFF
    var nightColor1: Int = 0xFF335C
    var nightColor2: Int = 0x24BDFF
    var syncDomeWithLights: Boolean = true
    var lastLightsState: ArenaLightsStatePayload = ArenaLightsStatePayload(
        modeId = SpotlightShowMode.AUTO.id,
        rodMode = "1",
        activeEffectId = SpotlightShowMode.CENTRO.id,
        playlist = listOf(SpotlightShowMode.CENTRO.id),
        disabledEffects = emptyList(),
        dayColor1 = 0xFF335C,
        dayColor2 = 0x24BDFF,
        nightColor1 = 0xFF335C,
        nightColor2 = 0x24BDFF,
        syncDomeWithLights = true,
        loopDurationMs = 10000
    )
    private var currActiveEffectId: String = SpotlightShowMode.CENTRO.id
    private var prevActiveEffectId: String = SpotlightShowMode.CENTRO.id
    private var effectBlendStartMs: Long = 0L
    private val clientClockStartMs: Long = System.currentTimeMillis()
    private val spotIntensity = FloatArray(8) { 1.0f }
    private val spotWidthMult = FloatArray(8) { 1.0f }
    private val spotTargetR = FloatArray(8) { 1.0f }
    private val spotTargetG = FloatArray(8) { 0.2f }
    private val spotTargetB = FloatArray(8) { 0.36f }
    @Volatile
    var concertBeamShader: ShaderInstance? = null

    @Volatile
    var domeEnergyShader: ShaderInstance? = null

    @JvmField
    @Volatile
    var insideDarkRoomFactor: Float = 0.0f
    private var lastDarkRoomUpdateMs: Long = 0L

    private var bloomNearArena = false
    private val arenaBloomEffect: ManagedShaderEffect by lazy {
        ShaderEffectManager.getInstance().manage(
            ResourceLocation.fromNamespaceAndPath("showdown_arena", "shaders/post/arena_bloom.json")
        )
    }
    internal val WHITE_TEX: ResourceLocation =
        ResourceLocation.fromNamespaceAndPath("showdown_arena", "textures/misc/white.png")

    internal fun irisTranslucentType(): RenderType = RenderType.text(WHITE_TEX)
    internal fun irisAdditiveType(): RenderType = RenderType.lightning()

    private var lastPhotonCheckMs = 0L
    private var cachedPhotonActive = false
    private var irisPackNameChecked = false
    private var irisPackNameMethod: java.lang.reflect.Method? = null

    internal fun isPhotonShaderActive(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPhotonCheckMs < 1000L) return cachedPhotonActive
        lastPhotonCheckMs = now
        if (!isIrisShaderPackActive()) {
            cachedPhotonActive = false
            return false
        }
        if (!irisPackNameChecked) {
            irisPackNameChecked = true
            irisPackNameMethod = runCatching {
                Class.forName("net.irisshaders.iris.Iris").getMethod("getCurrentPackName")
            }.getOrNull()
        }
        val name = runCatching {
            (irisPackNameMethod?.invoke(null) as? String).orEmpty()
        }.getOrDefault("")
        cachedPhotonActive = name.contains("photon", ignoreCase = true)
        return cachedPhotonActive
    }

    private var irisApiChecked = false
    private var irisApiInstance: Any? = null
    private var irisIsInUseMethod: java.lang.reflect.Method? = null
    private var irisIsShadowPassMethod: java.lang.reflect.Method? = null

    private fun ensureIrisApi() {
        if (irisApiChecked) return
        irisApiChecked = true
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            try {
                val cls = Class.forName("net.irisshaders.iris.api.v0.IrisApi")
                irisApiInstance = cls.getMethod("getInstance").invoke(null)
                irisIsInUseMethod = cls.getMethod("isShaderPackInUse")
                irisIsShadowPassMethod = cls.getMethod("isRenderingShadowPass")
            } catch (_: Throwable) {
                irisApiInstance = null
                irisIsInUseMethod = null
                irisIsShadowPassMethod = null
            }
        }
    }

    internal fun isIrisShaderPackActive(): Boolean {
        ensureIrisApi()
        val inst = irisApiInstance ?: return false
        val method = irisIsInUseMethod ?: return false
        return try {
            (method.invoke(inst) as? Boolean) == true
        } catch (_: Throwable) {
            false
        }
    }

    internal fun isIrisRenderingShadowPass(): Boolean {
        ensureIrisApi()
        val inst = irisApiInstance ?: return false
        val method = irisIsShadowPassMethod ?: return false
        return try {
            (method.invoke(inst) as? Boolean) == true
        } catch (_: Throwable) {
            false
        }
    }

    fun renderBloom(tickDelta: Float) {
        // Desactivado: en Minecraft 1.21.1 + Sodium, PostPass sobre minecraft:main limpia el framebuffer antes de la mano/UI.
        bloomNearArena = false
    }

    private var wasInsideZone = false
    private var zoneEnterTimeMs: Long = 0L
    private const val DISPLAY_DURATION_MS = 3400L

    private const val RING_SEGMENTS = 64
    private const val EMBLEM_SEGMENTS = 36
    private const val DOME_LAT_STEPS = 9
    private const val PERIMETER_SPARKS = 20
    private const val MAX_RENDER_DIST_MARGIN = 100.0f
    private const val LOOP_PERIOD_MS = 24_000L

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(ArenaZoneSyncPayload.ID) { payload, _ ->
            isEnabled = payload.enabled
            if (payload.hasZone) {
                currentZone = ArenaZone(
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

        ClientPlayNetworking.registerGlobalReceiver(OpenShowdownGuiPayload.ID) { payload, _ ->
            Minecraft.getInstance().execute {
                val target = if (payload.targetPlayerName.isBlank()) null else payload.targetPlayerName
                Minecraft.getInstance().setScreen(ShowdownBattleScreen(null, target))
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(ArenaLightsStatePayload.ID) { payload, _ ->
            lastLightsState = payload
            lightsMode = SpotlightShowMode.fromId(payload.modeId) ?: SpotlightShowMode.AUTO
            lightsRodMode = payload.rodMode.lowercase()
            dayColor1 = payload.dayColor1
            dayColor2 = payload.dayColor2
            nightColor1 = payload.nightColor1
            nightColor2 = payload.nightColor2
            syncDomeWithLights = payload.syncDomeWithLights
            if (payload.activeEffectId != currActiveEffectId) {
                prevActiveEffectId = currActiveEffectId
                currActiveEffectId = payload.activeEffectId
                effectBlendStartMs = System.currentTimeMillis()
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(OpenArenaLightsGuiPayload.ID) { _, _ ->
            Minecraft.getInstance().execute {
                ArenaLightsEditorScreen.openWithState(lastLightsState)
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
     * Renderiza el anuncio HUD "ZONA DE COMBATE" al ingresar a la arena.
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
                mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.45f))
            } catch (_: Exception) {}
        }
        wasInsideZone = isInside

        if (zoneEnterTimeMs == 0L) return
        val elapsed = System.currentTimeMillis() - zoneEnterTimeMs
        if (elapsed > DISPLAY_DURATION_MS) return

        val alpha = when {
            elapsed < 400L -> (elapsed / 400f).coerceIn(0f, 1f)
            elapsed < 2400L -> 1.0f
            else -> ((DISPLAY_DURATION_MS - elapsed) / 1000f).coerceIn(0f, 1f)
        }
        if (alpha <= 0.01f) return

        val alphaByte = (alpha * 255).toInt().coerceIn(0, 255)
        val font = mc.font
        val screenW = graphics.guiWidth()
        val screenH = graphics.guiHeight()
        val centerX = screenW / 2

        val titleY = (screenH * 0.22f).toInt()

        val matrices = graphics.pose()
        matrices.pushPose()
        matrices.translate(centerX.toFloat(), titleY.toFloat(), 0f)

        val popScale = 1.85f + (1f - alpha.coerceAtMost(1f)) * 0.12f
        matrices.scale(popScale, popScale, 1.0f)

        val titleText = "ZONA DE COMBATE"
        val titleW = font.width(titleText)

        val titleColor = (alphaByte shl 24) or 0xFF3344
        val shadowColor = (alphaByte shl 24) or 0x1A0820
        graphics.drawString(font, titleText, -titleW / 2 + 1, 1, shadowColor, false)
        graphics.drawString(font, titleText, -titleW / 2, 0, titleColor, false)

        matrices.popPose()

        val subY = titleY + (font.lineHeight * popScale).toInt() + 4
        val lineW = (170 * alpha).toInt()
        val halfW = lineW / 2
        val redLine = (alphaByte shl 24) or 0xFF2840
        val blueLine = (alphaByte shl 24) or 0x1EA7FF
        graphics.fill(centerX - halfW, subY - 3, centerX, subY - 2, redLine)
        graphics.fill(centerX, subY - 3, centerX + halfW, subY - 2, blueLine)

        val subText = "Combates Pokemon Habilitados (/combates)"
        val subW = font.width(subText)
        val subColor = (alphaByte shl 24) or 0xE2E8F0
        graphics.drawString(font, subText, centerX - subW / 2, subY, subColor, true)
    }

    @Volatile
    private var currentNightFactor: Float = 1.0f
    private var lastNightUpdateMs: Long = 0L

    private val smoothSpotR = FloatArray(8) { 0.98f }
    private val smoothSpotG = FloatArray(8) { 0.25f }
    private val smoothSpotB = FloatArray(8) { 0.45f }
    private var lastColorUpdateMs: Long = 0L

    private fun computeTargetNightFactor(level: Level): Float {
        val t = ((level.dayTime % 24000L) + 24000L) % 24000L
        return when {
            t < 12000L -> 0.0f
            t < 13500L -> (t - 12000L) / 1500.0f
            t < 22500L -> 1.0f
            else -> 1.0f - (t - 22500L) / 1500.0f
        }.coerceIn(0.0f, 1.0f)
    }

    private fun updateNightFactor(level: Level): Float {
        val target = computeTargetNightFactor(level)
        val now = System.currentTimeMillis()
        if (lastNightUpdateMs == 0L) {
            lastNightUpdateMs = now
            currentNightFactor = target
            return target
        }
        val dt = ((now - lastNightUpdateMs).coerceIn(0L, 250L)) / 1000.0f
        lastNightUpdateMs = now
        // Smooth 2.5-second fade when transitioning between day and night (even on /time set)
        val step = (dt / 2.5f).coerceIn(0.0f, 1.0f)
        val diff = target - currentNightFactor
        currentNightFactor = if (abs(diff) <= step) target else currentNightFactor + kotlin.math.sign(diff) * step
        return currentNightFactor
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun smoothStep(t: Double): Double {
        val c = t.coerceIn(0.0, 1.0)
        return c * c * (3.0 - 2.0 * c)
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Float, Float, Float> {
        val hh = ((h % 1.0f) + 1.0f) % 1.0f * 6.0f
        val i = hh.toInt()
        val f = hh - i
        val p = v * (1.0f - s)
        val q = v * (1.0f - s * f)
        val t = v * (1.0f - s * (1.0f - f))
        return when (i % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
    }

    private fun activeColor1Component(shift: Int): Float {
        val d = ((dayColor1 shr shift) and 0xFF) / 255f
        val n = ((nightColor1 shr shift) and 0xFF) / 255f
        return lerp(d, n, currentNightFactor)
    }

    private fun activeColor2Component(shift: Int): Float {
        val d = ((dayColor2 shr shift) and 0xFF) / 255f
        val n = ((nightColor2 shr shift) and 0xFF) / 255f
        return lerp(d, n, currentNightFactor)
    }

    private fun stadiumR(sideBlend: Float): Float {
        if (syncDomeWithLights) {
            return lerp(activeColor1Component(16), activeColor2Component(16), sideBlend)
        }
        val dayR = lerp(1.00f, 0.04f, sideBlend)
        val nightR = lerp(0.98f, 0.08f, sideBlend)
        return lerp(dayR, nightR, currentNightFactor)
    }

    private fun stadiumG(sideBlend: Float): Float {
        if (syncDomeWithLights) {
            return lerp(activeColor1Component(8), activeColor2Component(8), sideBlend)
        }
        val dayG = lerp(0.64f, 0.76f, sideBlend)
        val nightG = lerp(0.14f, 0.64f, sideBlend)
        return lerp(dayG, nightG, currentNightFactor)
    }

    private fun stadiumB(sideBlend: Float): Float {
        if (syncDomeWithLights) {
            return lerp(activeColor1Component(0), activeColor2Component(0), sideBlend)
        }
        val dayB = lerp(0.10f, 0.94f, sideBlend)
        val nightB = lerp(0.26f, 1.00f, sideBlend)
        return lerp(dayB, nightB, currentNightFactor)
    }

    private var activeUseIris = false
    private var activeUsePhoton = false
    private var activeIrisAlpha = 1.0f

    internal fun emitIrisTranslucentQuad(
        buf: VertexConsumer,
        m: Matrix4f,
        x0: Float, y0: Float, z0: Float, r0: Float, g0: Float, b0: Float, a0: Float,
        x1: Float, y1: Float, z1: Float, r1: Float, g1: Float, b1: Float, a1: Float,
        x2: Float, y2: Float, z2: Float, r2: Float, g2: Float, b2: Float, a2: Float,
        x3: Float, y3: Float, z3: Float, r3: Float, g3: Float, b3: Float, a3: Float,
        alphaScale: Float
    ) {
        val ca0 = (a0 * alphaScale).coerceIn(0.12f, 0.96f)
        val ca1 = (a1 * alphaScale).coerceIn(0.12f, 0.96f)
        val ca2 = (a2 * alphaScale).coerceIn(0.12f, 0.96f)
        val ca3 = (a3 * alphaScale).coerceIn(0.12f, 0.96f)
        val cScale = 0.92f

        val cr0 = (r0 * cScale).coerceIn(0f, 1f); val cg0 = (g0 * cScale).coerceIn(0f, 1f); val cb0 = (b0 * cScale).coerceIn(0f, 1f)
        val cr1 = (r1 * cScale).coerceIn(0f, 1f); val cg1 = (g1 * cScale).coerceIn(0f, 1f); val cb1 = (b1 * cScale).coerceIn(0f, 1f)
        val cr2 = (r2 * cScale).coerceIn(0f, 1f); val cg2 = (g2 * cScale).coerceIn(0f, 1f); val cb2 = (b2 * cScale).coerceIn(0f, 1f)
        val cr3 = (r3 * cScale).coerceIn(0f, 1f); val cg3 = (g3 * cScale).coerceIn(0f, 1f); val cb3 = (b3 * cScale).coerceIn(0f, 1f)

        // RenderType.text usa POSITION_COLOR_TEX_LIGHTMAP con cull; emitir doble cara
        buf.addVertex(m, x0, y0, z0).setColor(cr0, cg0, cb0, ca0).setUv(0.5f, 0.5f).setLight(LightTexture.FULL_BRIGHT)
        buf.addVertex(m, x1, y1, z1).setColor(cr1, cg1, cb1, ca1).setUv(0.5f, 0.5f).setLight(LightTexture.FULL_BRIGHT)
        buf.addVertex(m, x2, y2, z2).setColor(cr2, cg2, cb2, ca2).setUv(0.5f, 0.5f).setLight(LightTexture.FULL_BRIGHT)
        buf.addVertex(m, x3, y3, z3).setColor(cr3, cg3, cb3, ca3).setUv(0.5f, 0.5f).setLight(LightTexture.FULL_BRIGHT)

        buf.addVertex(m, x3, y3, z3).setColor(cr3, cg3, cb3, ca3).setUv(0.5f, 0.5f).setLight(LightTexture.FULL_BRIGHT)
        buf.addVertex(m, x2, y2, z2).setColor(cr2, cg2, cb2, ca2).setUv(0.5f, 0.5f).setLight(LightTexture.FULL_BRIGHT)
        buf.addVertex(m, x1, y1, z1).setColor(cr1, cg1, cb1, ca1).setUv(0.5f, 0.5f).setLight(LightTexture.FULL_BRIGHT)
        buf.addVertex(m, x0, y0, z0).setColor(cr0, cg0, cb0, ca0).setUv(0.5f, 0.5f).setLight(LightTexture.FULL_BRIGHT)
    }

    internal fun emitIrisAdditiveQuad(
        buf: VertexConsumer,
        m: Matrix4f,
        x0: Float, y0: Float, z0: Float, r0: Float, g0: Float, b0: Float, a0: Float,
        x1: Float, y1: Float, z1: Float, r1: Float, g1: Float, b1: Float, a1: Float,
        x2: Float, y2: Float, z2: Float, r2: Float, g2: Float, b2: Float, a2: Float,
        x3: Float, y3: Float, z3: Float, r3: Float, g3: Float, b3: Float, a3: Float,
        alphaScale: Float
    ) {
        val raw0 = (a0 * alphaScale).coerceIn(0f, 1f)
        val raw1 = (a1 * alphaScale).coerceIn(0f, 1f)
        val raw2 = (a2 * alphaScale).coerceIn(0f, 1f)
        val raw3 = (a3 * alphaScale).coerceIn(0f, 1f)
        if (raw0 < 0.002f && raw1 < 0.002f && raw2 < 0.002f && raw3 < 0.002f) return

        val cr0 = r0.coerceIn(0f, 1f); val cg0 = g0.coerceIn(0f, 1f); val cb0 = b0.coerceIn(0f, 1f)
        val cr1 = r1.coerceIn(0f, 1f); val cg1 = g1.coerceIn(0f, 1f); val cb1 = b1.coerceIn(0f, 1f)
        val cr2 = r2.coerceIn(0f, 1f); val cg2 = g2.coerceIn(0f, 1f); val cb2 = b2.coerceIn(0f, 1f)
        val cr3 = r3.coerceIn(0f, 1f); val cg3 = g3.coerceIn(0f, 1f); val cb3 = b3.coerceIn(0f, 1f)

        // RenderType.lightning() usa POSITION_COLOR con blending aditivo SRC_ALPHA,ONE sin aplastamiento pow1_5
        buf.addVertex(m, x0, y0, z0).setColor(cr0, cg0, cb0, raw0)
        buf.addVertex(m, x1, y1, z1).setColor(cr1, cg1, cb1, raw1)
        buf.addVertex(m, x2, y2, z2).setColor(cr2, cg2, cb2, raw2)
        buf.addVertex(m, x3, y3, z3).setColor(cr3, cg3, cb3, raw3)

        buf.addVertex(m, x3, y3, z3).setColor(cr3, cg3, cb3, raw3)
        buf.addVertex(m, x2, y2, z2).setColor(cr2, cg2, cb2, raw2)
        buf.addVertex(m, x1, y1, z1).setColor(cr1, cg1, cb1, raw1)
        buf.addVertex(m, x0, y0, z0).setColor(cr0, cg0, cb0, raw0)
    }

    internal fun emitPhotonTranslucentQuad(
        buf: VertexConsumer,
        m: Matrix4f,
        x0: Float, y0: Float, z0: Float, r0: Float, g0: Float, b0: Float, a0: Float,
        x1: Float, y1: Float, z1: Float, r1: Float, g1: Float, b1: Float, a1: Float,
        x2: Float, y2: Float, z2: Float, r2: Float, g2: Float, b2: Float, a2: Float,
        x3: Float, y3: Float, z3: Float, r3: Float, g3: Float, b3: Float, a3: Float,
        alphaScale: Float
    ) {
        val a0s = (a0 * alphaScale).coerceIn(0f, 1f)
        val a1s = (a1 * alphaScale).coerceIn(0f, 1f)
        val a2s = (a2 * alphaScale).coerceIn(0f, 1f)
        val a3s = (a3 * alphaScale).coerceIn(0f, 1f)
        if (maxOf(a0s, a1s, a2s, a3s) < 0.003f) return

        buf.addVertex(m, x0, y0, z0).setColor(r0.coerceIn(0f, 1f), g0.coerceIn(0f, 1f), b0.coerceIn(0f, 1f), a0s)
        buf.addVertex(m, x1, y1, z1).setColor(r1.coerceIn(0f, 1f), g1.coerceIn(0f, 1f), b1.coerceIn(0f, 1f), a1s)
        buf.addVertex(m, x2, y2, z2).setColor(r2.coerceIn(0f, 1f), g2.coerceIn(0f, 1f), b2.coerceIn(0f, 1f), a2s)
        buf.addVertex(m, x3, y3, z3).setColor(r3.coerceIn(0f, 1f), g3.coerceIn(0f, 1f), b3.coerceIn(0f, 1f), a3s)
    }

    internal fun emitPhotonAdditiveQuad(
        buf: VertexConsumer,
        m: Matrix4f,
        x0: Float, y0: Float, z0: Float, r0: Float, g0: Float, b0: Float, a0: Float,
        x1: Float, y1: Float, z1: Float, r1: Float, g1: Float, b1: Float, a1: Float,
        x2: Float, y2: Float, z2: Float, r2: Float, g2: Float, b2: Float, a2: Float,
        x3: Float, y3: Float, z3: Float, r3: Float, g3: Float, b3: Float, a3: Float,
        alphaScale: Float
    ) {
        val a0s = (a0 * alphaScale).coerceIn(0f, 1f)
        val a1s = (a1 * alphaScale).coerceIn(0f, 1f)
        val a2s = (a2 * alphaScale).coerceIn(0f, 1f)
        val a3s = (a3 * alphaScale).coerceIn(0f, 1f)
        if (maxOf(a0s, a1s, a2s, a3s) < 0.003f) return

        buf.addVertex(m, x0, y0, z0).setColor(r0.coerceIn(0f, 1f), g0.coerceIn(0f, 1f), b0.coerceIn(0f, 1f), a0s)
        buf.addVertex(m, x1, y1, z1).setColor(r1.coerceIn(0f, 1f), g1.coerceIn(0f, 1f), b1.coerceIn(0f, 1f), a1s)
        buf.addVertex(m, x2, y2, z2).setColor(r2.coerceIn(0f, 1f), g2.coerceIn(0f, 1f), b2.coerceIn(0f, 1f), a2s)
        buf.addVertex(m, x3, y3, z3).setColor(r3.coerceIn(0f, 1f), g3.coerceIn(0f, 1f), b3.coerceIn(0f, 1f), a3s)
    }

    internal fun emitPhotonNeonLine(
        buf: VertexConsumer,
        m: Matrix4f,
        x0: Float, y0: Float, z0: Float, r0: Float, g0: Float, b0: Float, a0: Float,
        x1: Float, y1: Float, z1: Float, r1: Float, g1: Float, b1: Float, a1: Float,
        halfWidth: Float,
        alphaScale: Float
    ) {
        val dx = x1 - x0; val dy = y1 - y0; val dz = z1 - z0
        val mx = (x0 + x1) * 0.5f; val my = (y0 + y1) * 0.5f; val mz = (z0 + z1) * 0.5f
        var sx = dy * (-mz) - dz * (-my)
        var sy = dz * (-mx) - dx * (-mz)
        var sz = dx * (-my) - dy * (-mx)
        var sLen = sqrt(sx * sx + sy * sy + sz * sz)
        if (sLen < 1e-5f) {
            sx = -dz; sy = 0f; sz = dx
            sLen = sqrt(sx * sx + sz * sz)
            if (sLen < 1e-5f) { sx = 1f; sy = 0f; sz = 0f; sLen = 1f }
        }
        val scale = halfWidth / sLen
        sx *= scale; sy *= scale; sz *= scale

        emitPhotonAdditiveQuad(
            buf, m,
            x0 - sx, y0 - sy, z0 - sz, r0, g0, b0, a0,
            x0 + sx, y0 + sy, z0 + sz, r0, g0, b0, a0,
            x1 + sx, y1 + sy, z1 + sz, r1, g1, b1, a1,
            x1 - sx, y1 - sy, z1 - sz, r1, g1, b1, a1,
            alphaScale
        )
    }

    internal fun emitIrisNeonLine(
        buf: VertexConsumer,
        m: Matrix4f,
        x0: Float, y0: Float, z0: Float, r0: Float, g0: Float, b0: Float, a0: Float,
        x1: Float, y1: Float, z1: Float, r1: Float, g1: Float, b1: Float, a1: Float,
        halfWidth: Float,
        alphaScale: Float
    ) {
        val dx = x1 - x0; val dy = y1 - y0; val dz = z1 - z0
        val mx = (x0 + x1) * 0.5f; val my = (y0 + y1) * 0.5f; val mz = (z0 + z1) * 0.5f
        // cross(dir, -midpoint) gives camera-facing perpendicular ribbon vector
        var sx = dy * (-mz) - dz * (-my)
        var sy = dz * (-mx) - dx * (-mz)
        var sz = dx * (-my) - dy * (-mx)
        var sLen = sqrt(sx * sx + sy * sy + sz * sz)
        if (sLen < 1e-5f) {
            sx = -dz; sy = 0f; sz = dx
            sLen = sqrt(sx * sx + sz * sz)
            if (sLen < 1e-5f) { sx = 1f; sy = 0f; sz = 0f; sLen = 1f }
        }
        val scale = halfWidth / sLen
        sx *= scale; sy *= scale; sz *= scale

        emitIrisAdditiveQuad(
            buf, m,
            x0 - sx, y0 - sy, z0 - sz, r0, g0, b0, a0,
            x0 + sx, y0 + sy, z0 + sz, r0, g0, b0, a0,
            x1 + sx, y1 + sy, z1 + sz, r1, g1, b1, a1,
            x1 - sx, y1 - sy, z1 - sz, r1, g1, b1, a1,
            alphaScale
        )
    }

    private fun emitQuad(
        buf: VertexConsumer,
        m: Matrix4f,
        x0: Float, y0: Float, z0: Float, r0: Float, g0: Float, b0: Float, a0: Float,
        x1: Float, y1: Float, z1: Float, r1: Float, g1: Float, b1: Float, a1: Float,
        x2: Float, y2: Float, z2: Float, r2: Float, g2: Float, b2: Float, a2: Float,
        x3: Float, y3: Float, z3: Float, r3: Float, g3: Float, b3: Float, a3: Float
    ) {
        if (activeUsePhoton) {
            emitPhotonTranslucentQuad(
                buf, m,
                x0, y0, z0, r0, g0, b0, a0,
                x1, y1, z1, r1, g1, b1, a1,
                x2, y2, z2, r2, g2, b2, a2,
                x3, y3, z3, r3, g3, b3, a3,
                activeIrisAlpha
            )
            return
        }
        if (activeUseIris) {
            emitIrisTranslucentQuad(
                buf, m,
                x0, y0, z0, r0, g0, b0, a0,
                x1, y1, z1, r1, g1, b1, a1,
                x2, y2, z2, r2, g2, b2, a2,
                x3, y3, z3, r3, g3, b3, a3,
                activeIrisAlpha
            )
            return
        }
        buf.addVertex(m, x0, y0, z0).setColor(r0, g0, b0, a0.coerceIn(0f, 1f))
        buf.addVertex(m, x1, y1, z1).setColor(r1, g1, b1, a1.coerceIn(0f, 1f))
        buf.addVertex(m, x2, y2, z2).setColor(r2, g2, b2, a2.coerceIn(0f, 1f))
        buf.addVertex(m, x3, y3, z3).setColor(r3, g3, b3, a3.coerceIn(0f, 1f))
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
            emitPhotonAdditiveQuad(
                buf, m,
                x0, y0, z0, r0, g0, b0, a0,
                x1, y1, z1, r1, g1, b1, a1,
                x2, y2, z2, r2, g2, b2, a2,
                x3, y3, z3, r3, g3, b3, a3,
                activeIrisAlpha
            )
            return
        }
        if (activeUseIris) {
            emitIrisAdditiveQuad(
                buf, m,
                x0, y0, z0, r0, g0, b0, a0,
                x1, y1, z1, r1, g1, b1, a1,
                x2, y2, z2, r2, g2, b2, a2,
                x3, y3, z3, r3, g3, b3, a3,
                activeIrisAlpha
            )
            return
        }
        buf.addVertex(m, x0, y0, z0).setColor(r0, g0, b0, a0.coerceIn(0f, 1f))
        buf.addVertex(m, x1, y1, z1).setColor(r1, g1, b1, a1.coerceIn(0f, 1f))
        buf.addVertex(m, x2, y2, z2).setColor(r2, g2, b2, a2.coerceIn(0f, 1f))
        buf.addVertex(m, x3, y3, z3).setColor(r3, g3, b3, a3.coerceIn(0f, 1f))
    }

    private fun emitLine(
        buf: VertexConsumer,
        m: Matrix4f,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        if (activeUsePhoton) {
            emitPhotonNeonLine(buf, m, x0, y0, z0, r, g, b, a, x1, y1, z1, r, g, b, a, 0.032f, activeIrisAlpha)
            return
        }
        if (activeUseIris) {
            emitIrisNeonLine(buf, m, x0, y0, z0, r, g, b, a, x1, y1, z1, r, g, b, a, 0.032f, activeIrisAlpha)
            return
        }
        val ca = a.coerceIn(0f, 1f)
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
            emitPhotonNeonLine(buf, m, x0, y0, z0, r0, g0, b0, a0, x1, y1, z1, r1, g1, b1, a1, 0.032f, activeIrisAlpha)
            return
        }
        if (activeUseIris) {
            emitIrisNeonLine(buf, m, x0, y0, z0, r0, g0, b0, a0, x1, y1, z1, r1, g1, b1, a1, 0.032f, activeIrisAlpha)
            return
        }
        buf.addVertex(m, x0, y0, z0).setColor(r0, g0, b0, a0.coerceIn(0f, 1f))
        buf.addVertex(m, x1, y1, z1).setColor(r1, g1, b1, a1.coerceIn(0f, 1f))
    }

    /**
     * Renderiza el Estadio Pokemon Holografico 3D (Anillo de combate, emblema Pokeball central,
     * domo energetico acanalado y reflectores de estadio en las esquinas).
     */
    fun render(context: WorldRenderContext) {
        val zone = currentZone ?: return
        if (!isEnabled) return
        if (isIrisRenderingShadowPass()) return

        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        val currentDim = level.dimension().location().toString()
        if (zone.dimension != currentDim) return
        val nightFactor = updateNightFactor(level)
        val isPlayerInside = zone.contains(currentDim, player.x, player.y, player.z)
        val nowDarkMs = System.currentTimeMillis()
        val darkDt = if (lastDarkRoomUpdateMs == 0L) 0.016f else ((nowDarkMs - lastDarkRoomUpdateMs).coerceIn(1L, 250L) / 1000.0f)
        lastDarkRoomUpdateMs = nowDarkMs
        val targetDark = if (isPlayerInside) 1.0f else 0.0f
        val darkStep = (darkDt / 0.25f).coerceIn(0f, 1f)
        val darkDiff = targetDark - insideDarkRoomFactor
        insideDarkRoomFactor = if (abs(darkDiff) <= darkStep) targetDark else insideDarkRoomFactor + kotlin.math.sign(darkDiff) * darkStep
        val effectiveDarkness = maxOf(nightFactor, insideDarkRoomFactor)

        val minX = minOf(zone.minX, zone.maxX)
        val maxX = maxOf(zone.minX, zone.maxX)
        val minZ = minOf(zone.minZ, zone.maxZ)
        val maxZ = maxOf(zone.minZ, zone.maxZ)
        val halfW = ((maxX - minX) * 0.5).toFloat()
        val halfD = ((maxZ - minZ) * 0.5).toFloat()
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

        val irisActive = isIrisShaderPackActive()
        val isPhoton = irisActive && isPhotonShaderActive()
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
        bloomNearArena = distToEdge <= 96.0f
        val fogFadeStart = effectiveLimit * 0.58f
        val fogAlpha = if (distToEdge <= fogFadeStart) {
            1.0f
        } else {
            (1.0f - (distToEdge - fogFadeStart) / (effectiveLimit * 0.30f).coerceAtLeast(1.0f)).coerceIn(0.0f, 1.0f)
        }
        if (fogAlpha <= 0.01f) return
        activeUseIris = useIrisNonPhoton
        activeUsePhoton = usePhotonPipeline
        activeIrisAlpha = if (useIrisPipeline) fogAlpha else 1.0f

        val rx = (halfW - 0.15f).coerceAtLeast(0.6f)
        val rz = (halfD - 0.15f).coerceAtLeast(0.6f)
        val minR = minOf(rx, rz)

        val loopT = ((System.currentTimeMillis() % LOOP_PERIOD_MS).toDouble() / LOOP_PERIOD_MS.toDouble())
        val baseTheta = loopT * 2.0 * PI
        val timeSeconds = loopT * (LOOP_PERIOD_MS / 1000.0)
        // Integer harmonics of baseTheta guarantee exact seamless looping (t=0 == t=24s)
        val pulse = (sin(baseTheta * 8.0) * 0.5 + 0.5).toFloat()
        val domeWavePhase = ((loopT * 6.0) % 1.0).toFloat()
        val domeWaveEnvelope = sin(domeWavePhase * PI).toFloat()
        val runnerRedAngle = ((loopT * 4.0) % 1.0 * (2.0 * PI)).toFloat()
        val runnerBlueAngle = (((1.0 - ((loopT * 4.0) % 1.0)) * (2.0 * PI)) % (2.0 * PI)).toFloat()

        val centerGroundWorldY = getGroundY(level, centerWorldX, centerWorldZ, zone.minY, player.y)
        val centerY = (centerGroundWorldY - camPos.y).toFloat()

        val ringCos = FloatArray(RING_SEGMENTS)
        val ringSin = FloatArray(RING_SEGMENTS)
        val rawRingGroundY = FloatArray(RING_SEGMENTS)
        val ringGroundY = FloatArray(RING_SEGMENTS)
        val ringSideBlend = FloatArray(RING_SEGMENTS)
        var ringYSum = 0f

        for (i in 0 until RING_SEGMENTS) {
            val angle = (i.toDouble() / RING_SEGMENTS) * (2.0 * PI)
            val c = cos(angle).toFloat()
            val s = sin(angle).toFloat()
            ringCos[i] = c
            ringSin[i] = s
            val wx = centerWorldX + c * rx
            val wz = centerWorldZ + s * rz
            val gy = (getGroundY(level, wx, wz, zone.minY, player.y) - camPos.y).toFloat()
            rawRingGroundY[i] = gy.coerceIn(centerY - 2.2f, centerY + 2.2f)
            ringSideBlend[i] = ((s * 2.6f) * 0.5f + 0.5f).coerceIn(0f, 1f)
        }

        // Smooth terrain height transitions across adjacent perimeter segments (up to ~2 blocks slope)
        for (i in 0 until RING_SEGMENTS) {
            val prev = (i - 1 + RING_SEGMENTS) % RING_SEGMENTS
            val next = (i + 1) % RING_SEGMENTS
            val smoothed = (rawRingGroundY[prev] + rawRingGroundY[i] * 2.0f + rawRingGroundY[next]) * 0.25f
            val finalY = maxOf(rawRingGroundY[i] + 0.04f, smoothed)
            ringGroundY[i] = finalY
            ringYSum += finalY
        }

        val avgRingY = ringYSum / RING_SEGMENTS
        val domeHeight = (minR * 0.85f).coerceIn(6.5f, 42.0f)
        val radiusScale = (minR / 16.0f).coerceIn(0.75f, 2.2f)
        val domeApexY = maxOf(centerY, avgRingY) + domeHeight
        val maxPhi = (PI * 0.43).toFloat()
        val sinMaxPhi = sin(maxPhi)
        val oculusScale = cos(maxPhi)

        matrices.pushPose()

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.enableDepthTest()
        RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL)
        RenderSystem.disableCull()
        RenderSystem.depthMask(false)
        RenderSystem.enablePolygonOffset()
        RenderSystem.polygonOffset(-1.0f, -2.0f)
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, fogAlpha)
        RenderSystem.setShader(GameRenderer::getPositionColorShader)

        val tessellator = RenderSystem.renderThreadTesselator()
        val matrix = matrices.last().pose()
        val normalMatrix = matrices.last().normal()
        val normalTmp = org.joml.Vector3f()
        val twoPi = (2.0 * PI).toFloat()

        // Helper para envolvente cerrada negra fija al mundo justo en el borde del domo (sin arrastrarse con el jugador)
        val shellRx = rx + 0.35f
        val shellRz = rz + 0.35f
        val shellBaseY = minOf(centerY, avgRingY)
        val shellBotY = shellBaseY - 22.0f
        val shellTopY = domeApexY + 1.25f

        val floorRings = 4
        val floorSlices = 40
        val floorRingY = Array(floorRings + 1) { FloatArray(floorSlices) }
        for (fr in 0..floorRings) {
            val frac = fr.toFloat() / floorRings
            for (sl in 0 until floorSlices) {
                val ang = (sl.toFloat() / floorSlices) * twoPi
                if (fr == 0) {
                    floorRingY[fr][sl] = centerY + 0.015f
                } else {
                    val wx = centerWorldX + cos(ang) * shellRx * frac
                    val wz = centerWorldZ + sin(ang) * shellRz * frac
                    val gy = (getGroundY(level, wx, wz, zone.minY, player.y) - camPos.y).toFloat()
                    floorRingY[fr][sl] = gy.coerceIn(centerY - 2.2f, centerY + 2.2f) + 0.015f
                }
            }
        }

        fun emitBlackShell(
            emit: (Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit
        ) {
            val shellSlices = floorSlices
            val skirtBaseY = shellBaseY + 0.02f
            val domeSpanY = (shellTopY - skirtBaseY).coerceAtLeast(1.0f)
            val bottomInnerR = 0.02f

            // 1) Tapa inferior bajo el suelo del domo y suelo negro interior pegado al terreno (debajo de los hologramas +0.045/+0.075)
            for (sl in 0 until shellSlices) {
                val a0 = (sl.toFloat() / shellSlices) * twoPi
                val a1 = ((sl + 1).toFloat() / shellSlices) * twoPi
                val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
                emit(
                    cx + c0 * bottomInnerR, shellBotY, cz + s0 * bottomInnerR,
                    cx + c1 * bottomInnerR, shellBotY, cz + s1 * bottomInnerR,
                    cx + c1 * shellRx, shellBotY, cz + s1 * shellRz,
                    cx + c0 * shellRx, shellBotY, cz + s0 * shellRz
                )
            }
            for (fr in 0 until floorRings) {
                val f0 = fr.toFloat() / floorRings
                val f1 = (fr + 1).toFloat() / floorRings
                val rx0 = shellRx * f0; val rz0 = shellRz * f0
                val rx1 = shellRx * f1; val rz1 = shellRz * f1
                for (sl in 0 until shellSlices) {
                    val nextSl = (sl + 1) % shellSlices
                    val a0 = (sl.toFloat() / shellSlices) * twoPi
                    val a1 = ((sl + 1).toFloat() / shellSlices) * twoPi
                    val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
                    emit(
                        cx + c0 * rx0, floorRingY[fr][sl], cz + s0 * rz0,
                        cx + c1 * rx0, floorRingY[fr][nextSl], cz + s1 * rz0,
                        cx + c1 * rx1, floorRingY[fr + 1][nextSl], cz + s1 * rz1,
                        cx + c0 * rx1, floorRingY[fr + 1][sl], cz + s0 * rz1
                    )
                }
            }

            // 2) Falda cilindrica inferior desde el subsuelo hasta la base del domo
            val skirtStacks = 2
            for (st in 0 until skirtStacks) {
                val y0 = lerp(shellBotY, skirtBaseY, st.toFloat() / skirtStacks)
                val y1 = lerp(shellBotY, skirtBaseY, (st + 1).toFloat() / skirtStacks)
                for (sl in 0 until shellSlices) {
                    val a0 = (sl.toFloat() / shellSlices) * twoPi
                    val a1 = ((sl + 1).toFloat() / shellSlices) * twoPi
                    val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
                    emit(
                        cx + c0 * shellRx, y0, cz + s0 * shellRz,
                        cx + c1 * shellRx, y0, cz + s1 * shellRz,
                        cx + c1 * shellRx, y1, cz + s1 * shellRz,
                        cx + c0 * shellRx, y1, cz + s0 * shellRz
                    )
                }
            }

            // 3) Semiesfera superior pegada a la silueta del domo y cerrada en el cenit
            val domeStacks = 12
            val halfPi = (PI * 0.5).toFloat()
            for (st in 0 until domeStacks) {
                val p0 = (st.toFloat() / domeStacks) * halfPi
                val p1 = ((st + 1).toFloat() / domeStacks) * halfPi
                val cp0 = cos(p0).coerceAtLeast(0.01f)
                val cp1 = cos(p1).coerceAtLeast(0.01f)
                val y0 = skirtBaseY + sin(p0) * domeSpanY
                val y1 = skirtBaseY + sin(p1) * domeSpanY
                val rx0 = shellRx * cp0; val rz0 = shellRz * cp0
                val rx1 = shellRx * cp1; val rz1 = shellRz * cp1
                for (sl in 0 until shellSlices) {
                    val a0 = (sl.toFloat() / shellSlices) * twoPi
                    val a1 = ((sl + 1).toFloat() / shellSlices) * twoPi
                    val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
                    emit(
                        cx + c0 * rx0, y0, cz + s0 * rz0,
                        cx + c1 * rx0, y0, cz + s1 * rz0,
                        cx + c1 * rx1, y1, cz + s1 * rz1,
                        cx + c0 * rx1, y1, cz + s0 * rz1
                    )
                }
            }
        }

        // PASS 0A: Exterior y suelo Negro Total desde dentro del domo (100% opaco en Vanilla, Complementary y Photon)
        if (insideDarkRoomFactor > 0.01f) {
            val da = insideDarkRoomFactor.coerceIn(0f, 1f)
            val fullyOpaque = da >= 0.96f
            RenderSystem.setShader(GameRenderer::getPositionColorShader)
            if (fullyOpaque) {
                RenderSystem.disableBlend()
                RenderSystem.depthMask(true)
            } else {
                RenderSystem.enableBlend()
                RenderSystem.defaultBlendFunc()
                RenderSystem.depthMask(false)
            }
            val blackA = if (fullyOpaque) 1.0f else da
            val blackBuf = tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
            emitBlackShell { x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3 ->
                blackBuf.addVertex(matrix, x0, y0, z0).setColor(0f, 0f, 0f, blackA)
                blackBuf.addVertex(matrix, x1, y1, z1).setColor(0f, 0f, 0f, blackA)
                blackBuf.addVertex(matrix, x2, y2, z2).setColor(0f, 0f, 0f, blackA)
                blackBuf.addVertex(matrix, x3, y3, z3).setColor(0f, 0f, 0f, blackA)
            }
            blackBuf.build()?.let { BufferUploader.drawWithShader(it) }
            if (fullyOpaque) {
                RenderSystem.enableBlend()
                RenderSystem.defaultBlendFunc()
                RenderSystem.depthMask(false)
            }
        }

        val photonTransAlloc: ByteBufferBuilder? = if (usePhotonPipeline) ByteBufferBuilder(262144) else null
        val photonAddAlloc: ByteBufferBuilder? = if (usePhotonPipeline) ByteBufferBuilder(262144) else null
        val photonTransBuf: BufferBuilder? = photonTransAlloc?.let { BufferBuilder(it, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR) }
        val photonAddBuf: BufferBuilder? = photonAddAlloc?.let { BufferBuilder(it, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR) }
        val irisTransBuffer: VertexConsumer = if (usePhotonPipeline) {
            photonTransBuf!!
        } else if (useIrisNonPhoton) {
            consumers!!.getBuffer(irisTranslucentType())
        } else {
            tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        }
        val irisAddBuffer: VertexConsumer = if (usePhotonPipeline) {
            photonAddBuf!!
        } else if (useIrisNonPhoton) {
            consumers!!.getBuffer(irisAdditiveType())
        } else {
            irisTransBuffer
        }

        // PASS 0B: Neblina volumétrica interior sutil dentro del domo
        if (insideDarkRoomFactor > 0.01f) {
            val da = insideDarkRoomFactor.coerceIn(0f, 1f)
            val darkBuf: VertexConsumer = if (useIrisPipeline) {
                irisAddBuffer
            } else {
                tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
            }
            val lowMistRx = rx * 0.94f; val lowMistRz = rz * 0.94f
            val lowMistTopY = centerY + domeHeight * 0.46f
            val highMistRx = rx * 0.78f; val highMistRz = rz * 0.78f
            val highMistBotY = centerY + domeHeight * 0.34f
            val highMistTopY = centerY + domeHeight * 0.76f
            for (i in 0 until RING_SEGMENTS) {
                val next = (i + 1) % RING_SEGMENTS
                val b0 = ringSideBlend[i]
                val b1 = ringSideBlend[next]
                val swirl0 = 0.5f + 0.5f * sin((i.toFloat() / RING_SEGMENTS) * twoPi * 2f + (baseTheta * 2.0).toFloat())
                val swirl1 = 0.5f + 0.5f * sin((next.toFloat() / RING_SEGMENTS) * twoPi * 2f + (baseTheta * 2.0).toFloat())
                val mA0 = da * (0.075f + 0.055f * swirl0 + 0.025f * pulse)
                val mA1 = da * (0.075f + 0.055f * swirl1 + 0.025f * pulse)

                val mr0 = stadiumR(b0); val mg0 = stadiumG(b0); val mb0 = stadiumB(b0)
                val mr1 = stadiumR(b1); val mg1 = stadiumG(b1); val mb1 = stadiumB(b1)

                emitGlowQuad(
                    darkBuf, matrix,
                    cx + ringCos[i] * lowMistRx, ringGroundY[i] + 0.35f, cz + ringSin[i] * lowMistRz, mr0, mg0, mb0, mA0,
                    cx + ringCos[next] * lowMistRx, ringGroundY[next] + 0.35f, cz + ringSin[next] * lowMistRz, mr1, mg1, mb1, mA1,
                    cx + ringCos[next] * lowMistRx, lowMistTopY, cz + ringSin[next] * lowMistRz, mr1, mg1, mb1, 0.0f,
                    cx + ringCos[i] * lowMistRx, lowMistTopY, cz + ringSin[i] * lowMistRz, mr0, mg0, mb0, 0.0f
                )
                emitGlowQuad(
                    darkBuf, matrix,
                    cx + ringCos[i] * highMistRx, highMistBotY, cz + ringSin[i] * highMistRz, mr0, mg0, mb0, 0.0f,
                    cx + ringCos[next] * highMistRx, highMistBotY, cz + ringSin[next] * highMistRz, mr1, mg1, mb1, 0.0f,
                    cx + ringCos[next] * highMistRx, highMistTopY, cz + ringSin[next] * highMistRz, mr1, mg1, mb1, mA1 * 0.75f,
                    cx + ringCos[i] * highMistRx, highMistTopY, cz + ringSin[i] * highMistRz, mr0, mg0, mb0, mA0 * 0.75f
                )
            }
            if (!useIrisPipeline && darkBuf is BufferBuilder) {
                darkBuf.build()?.let { BufferUploader.drawWithShader(it) }
            }
        }

        // =========================================================================
        // PASS 1: TRANSLUCENT QUADS (Battle Ring, Pokeball Emblem, Dome, Spotlights)
        // =========================================================================
        val quadBuffer: VertexConsumer = irisTransBuffer

        // 1A. Glowing Battle Ring Ribbon & Low Stadium Curb on the Ground
        val innerRingScale = 0.90f
        val curbHeight = 0.28f

        for (i in 0 until RING_SEGMENTS) {
            val next = (i + 1) % RING_SEGMENTS
            val angleMid = ((i + 0.5f) / RING_SEGMENTS) * twoPi

            val dRed = abs(angleMid - runnerRedAngle)
            val redGlow = exp(-min(dRed, twoPi - dRed) * 3.2f)
            val dBlue = abs(angleMid - runnerBlueAngle)
            val blueGlow = exp(-min(dBlue, twoPi - dBlue) * 3.2f)
            val clashGlow = (redGlow * blueGlow * 2.8f).coerceIn(0f, 1f)
            val combinedGlow = maxOf(redGlow, blueGlow)

            val blend0 = ringSideBlend[i]
            val blend1 = ringSideBlend[next]
            val r0 = (stadiumR(blend0) + redGlow * 0.25f + clashGlow * 0.9f).coerceIn(0f, 1f)
            val g0 = (stadiumG(blend0) + blueGlow * 0.22f + clashGlow * 0.9f).coerceIn(0f, 1f)
            val b0 = (stadiumB(blend0) + blueGlow * 0.25f + clashGlow * 0.9f).coerceIn(0f, 1f)
            val r1 = (stadiumR(blend1) + redGlow * 0.25f + clashGlow * 0.9f).coerceIn(0f, 1f)
            val g1 = (stadiumG(blend1) + blueGlow * 0.22f + clashGlow * 0.9f).coerceIn(0f, 1f)
            val b1 = (stadiumB(blend1) + blueGlow * 0.25f + clashGlow * 0.9f).coerceIn(0f, 1f)

            val ox0 = cx + ringCos[i] * rx
            val oz0 = cz + ringSin[i] * rz
            val ox1 = cx + ringCos[next] * rx
            val oz1 = cz + ringSin[next] * rz

            val ix0 = cx + ringCos[i] * rx * innerRingScale
            val iz0 = cz + ringSin[i] * rz * innerRingScale
            val ix1 = cx + ringCos[next] * rx * innerRingScale
            val iz1 = cz + ringSin[next] * rz * innerRingScale

            val y0 = ringGroundY[i] + 0.045f
            val y1 = ringGroundY[next] + 0.045f

            val ringAlphaOuter = (0.48f + pulse * 0.14f + combinedGlow * 0.32f).coerceIn(0f, 0.92f)
            val ringAlphaInner = (0.14f + combinedGlow * 0.22f).coerceIn(0f, 0.55f)

            // Flat glowing stadium floor track
            emitQuad(
                quadBuffer, matrix,
                ix0, y0, iz0, r0, g0, b0, ringAlphaInner,
                ox0, y0, oz0, r0, g0, b0, ringAlphaOuter,
                ox1, y1, oz1, r1, g1, b1, ringAlphaOuter,
                ix1, y1, iz1, r1, g1, b1, ringAlphaInner
            )

            // Low vertical holographic stadium curb (routed to irisAddBuffer under Iris for smooth fade to top)
            val curbAlphaBot = (0.58f + combinedGlow * 0.34f).coerceIn(0f, 0.94f)
            val curbAlphaTop = (0.04f + combinedGlow * 0.24f).coerceIn(0f, 0.45f)
            emitGlowQuad(
                irisAddBuffer, matrix,
                ox0, y0, oz0, r0, g0, b0, curbAlphaBot,
                ox1, y1, oz1, r1, g1, b1, curbAlphaBot,
                ox1, y1 + curbHeight, oz1, r1, g1, b1, curbAlphaTop,
                ox0, y0 + curbHeight, oz0, r0, g0, b0, curbAlphaTop
            )
        }

        // 1B. Central Pokeball Court Emblem
        val emblemR = (minR * 0.38f).coerceIn(1.6f, 16.0f)
        val emblemY = centerY + 0.075f
        val outerBandInnerR = emblemR * 0.87f
        val buttonOuterR = emblemR * 0.27f
        val buttonInnerR = emblemR * 0.16f
        val coreR = emblemR * 0.115f
        val bandHalfThick = emblemR * 0.052f

        for (i in 0 until EMBLEM_SEGMENTS) {
            val a0 = (i.toFloat() / EMBLEM_SEGMENTS) * twoPi
            val a1 = ((i + 1).toFloat() / EMBLEM_SEGMENTS) * twoPi
            val c0 = cos(a0)
            val s0 = sin(a0)
            val c1 = cos(a1)
            val s1 = sin(a1)
            val sMid = (s0 + s1) * 0.5f

            // Outer glowing white-silver Pokeball ring
            val rimAlpha = 0.56f + pulse * 0.16f
            emitQuad(
                quadBuffer, matrix,
                cx + c0 * outerBandInnerR, emblemY, cz + s0 * outerBandInnerR, 0.92f, 0.96f, 1.0f, rimAlpha,
                cx + c0 * emblemR, emblemY, cz + s0 * emblemR, 0.98f, 0.99f, 1.0f, rimAlpha,
                cx + c1 * emblemR, emblemY, cz + s1 * emblemR, 0.98f, 0.99f, 1.0f, rimAlpha,
                cx + c1 * outerBandInnerR, emblemY, cz + s1 * outerBandInnerR, 0.92f, 0.96f, 1.0f, rimAlpha
            )

            // Split Crimson (-Z) and Electric Blue (+Z) semicircles with crisp equatorial gap
            if (abs(sMid) > 0.11f) {
                val isCrimsonHalf = sMid < 0f
                val hr = if (isCrimsonHalf) 0.98f else 0.08f
                val hg = if (isCrimsonHalf) 0.14f else 0.64f
                val hb = if (isCrimsonHalf) 0.26f else 1.00f
                val fillOuterA = 0.30f + pulse * 0.10f
                val fillInnerA = 0.16f + pulse * 0.08f
                val fillMinR = buttonOuterR * 1.12f
                val fillMaxR = outerBandInnerR * 0.97f

                emitQuad(
                    quadBuffer, matrix,
                    cx + c0 * fillMinR, emblemY, cz + s0 * fillMinR, hr, hg, hb, fillInnerA,
                    cx + c0 * fillMaxR, emblemY, cz + s0 * fillMaxR, hr, hg, hb, fillOuterA,
                    cx + c1 * fillMaxR, emblemY, cz + s1 * fillMaxR, hr, hg, hb, fillOuterA,
                    cx + c1 * fillMinR, emblemY, cz + s1 * fillMinR, hr, hg, hb, fillInnerA
                )
            }

            // Center Pokeball button outer ring
            val btnAlpha = 0.68f + pulse * 0.18f
            emitQuad(
                quadBuffer, matrix,
                cx + c0 * buttonInnerR, emblemY + 0.004f, cz + s0 * buttonInnerR, 0.95f, 0.98f, 1.0f, btnAlpha,
                cx + c0 * buttonOuterR, emblemY + 0.004f, cz + s0 * buttonOuterR, 0.95f, 0.98f, 1.0f, btnAlpha,
                cx + c1 * buttonOuterR, emblemY + 0.004f, cz + s1 * buttonOuterR, 0.95f, 0.98f, 1.0f, btnAlpha,
                cx + c1 * buttonInnerR, emblemY + 0.004f, cz + s1 * buttonInnerR, 0.95f, 0.98f, 1.0f, btnAlpha
            )

            // Pulsing center button core disc
            val coreAlpha = 0.45f + pulse * 0.35f
            val coreRCol = lerp(1.0f, 0.45f, pulse)
            val coreGCol = lerp(0.86f, 0.92f, pulse)
            val coreBCol = lerp(0.88f, 1.0f, pulse)
            val coreInner = 0.02f
            emitQuad(
                quadBuffer, matrix,
                cx + c0 * coreInner, emblemY + 0.006f, cz + s0 * coreInner, 1.0f, 1.0f, 1.0f, coreAlpha,
                cx + c0 * coreR, emblemY + 0.006f, cz + s0 * coreR, coreRCol, coreGCol, coreBCol, coreAlpha * 0.72f,
                cx + c1 * coreR, emblemY + 0.006f, cz + s1 * coreR, coreRCol, coreGCol, coreBCol, coreAlpha * 0.72f,
                cx + c1 * coreInner, emblemY + 0.006f, cz + s1 * coreInner, 1.0f, 1.0f, 1.0f, coreAlpha
            )
        }

        // Equatorial Pokeball divider bars (left & right of center button)
        val eqAlpha = 0.60f + pulse * 0.16f
        emitQuad(
            quadBuffer, matrix,
            cx - outerBandInnerR, emblemY + 0.003f, cz - bandHalfThick, 0.96f, 0.98f, 1.0f, eqAlpha,
            cx - buttonOuterR, emblemY + 0.003f, cz - bandHalfThick, 0.96f, 0.98f, 1.0f, eqAlpha,
            cx - buttonOuterR, emblemY + 0.003f, cz + bandHalfThick, 0.96f, 0.98f, 1.0f, eqAlpha,
            cx - outerBandInnerR, emblemY + 0.003f, cz + bandHalfThick, 0.96f, 0.98f, 1.0f, eqAlpha
        )
        emitQuad(
            quadBuffer, matrix,
            cx + buttonOuterR, emblemY + 0.003f, cz - bandHalfThick, 0.96f, 0.98f, 1.0f, eqAlpha,
            cx + outerBandInnerR, emblemY + 0.003f, cz - bandHalfThick, 0.96f, 0.98f, 1.0f, eqAlpha,
            cx + outerBandInnerR, emblemY + 0.003f, cz + bandHalfThick, 0.96f, 0.98f, 1.0f, eqAlpha,
            cx + buttonOuterR, emblemY + 0.003f, cz + bandHalfThick, 0.96f, 0.98f, 1.0f, eqAlpha
        )

        // Mid-court extension bars connecting the Pokeball emblem to the outer stadium ring
        val courtHalfW = (emblemR * 0.032f).coerceAtLeast(0.06f)
        val courtOuterX = rx * innerRingScale
        if (courtOuterX > emblemR + 0.2f) {
            val midCourtA = 0.34f + pulse * 0.12f
            emitQuad(
                quadBuffer, matrix,
                cx - courtOuterX, emblemY + 0.002f, cz - courtHalfW, 0.90f, 0.95f, 1.0f, midCourtA * 0.7f,
                cx - emblemR, emblemY + 0.002f, cz - courtHalfW, 0.95f, 0.98f, 1.0f, midCourtA,
                cx - emblemR, emblemY + 0.002f, cz + courtHalfW, 0.95f, 0.98f, 1.0f, midCourtA,
                cx - courtOuterX, emblemY + 0.002f, cz + courtHalfW, 0.90f, 0.95f, 1.0f, midCourtA * 0.7f
            )
            emitQuad(
                quadBuffer, matrix,
                cx + emblemR, emblemY + 0.002f, cz - courtHalfW, 0.95f, 0.98f, 1.0f, midCourtA,
                cx + courtOuterX, emblemY + 0.002f, cz - courtHalfW, 0.90f, 0.95f, 1.0f, midCourtA * 0.7f,
                cx + courtOuterX, emblemY + 0.002f, cz + courtHalfW, 0.90f, 0.95f, 1.0f, midCourtA * 0.7f,
                cx + emblemR, emblemY + 0.002f, cz + courtHalfW, 0.95f, 0.98f, 1.0f, midCourtA
            )
        }

        // Interior 1: Concentric fine stadium rings & trainer battle pads (Crimson -Z vs Electric Blue +Z)
        for (ringMult in listOf(1.18f, 1.48f)) {
            val rIn = emblemR * ringMult
            val rOut = rIn + 0.09f
            if (rOut < minR * 0.88f) {
                val rAlpha = (0.34f + pulse * 0.14f).coerceIn(0f, 0.56f)
                for (i in 0 until EMBLEM_SEGMENTS) {
                    val a0 = (i.toFloat() / EMBLEM_SEGMENTS) * twoPi
                    val a1 = ((i + 1).toFloat() / EMBLEM_SEGMENTS) * twoPi
                    val c0 = cos(a0); val s0 = sin(a0)
                    val c1 = cos(a1); val s1 = sin(a1)
                    val sideB = (((s0 + s1) * 0.5f) * 1.8f * 0.5f + 0.5f).coerceIn(0f, 1f)
                    val rr = stadiumR(sideB); val rg = stadiumG(sideB); val rb = stadiumB(sideB)
                    emitQuad(
                        quadBuffer, matrix,
                        cx + c0 * rIn, emblemY + 0.002f, cz + s0 * rIn, rr, rg, rb, rAlpha,
                        cx + c0 * rOut, emblemY + 0.002f, cz + s0 * rOut, rr, rg, rb, rAlpha * 0.35f,
                        cx + c1 * rOut, emblemY + 0.002f, cz + s1 * rOut, rr, rg, rb, rAlpha * 0.35f,
                        cx + c1 * rIn, emblemY + 0.002f, cz + s1 * rIn, rr, rg, rb, rAlpha
                    )
                }
            }
        }
        val padDist = (rz * 0.72f).coerceIn(emblemR + 1.5f, rz * 0.80f)
        val padBaseR = (minR * 0.095f).coerceIn(0.85f, 2.3f)
        val musicBass = ArenaMusicClient.bassLevel
        if (padDist + padBaseR < rz * 0.91f) {
            for (sideSign in listOf(-1f, 1f)) {
                val padCx = cx
                val padCz = cz + sideSign * padDist
                val pr = if (sideSign < 0f) 1.0f else 0.14f
                val pg = if (sideSign < 0f) 0.22f else 0.76f
                val pb = if (sideSign < 0f) 0.38f else 1.0f

                // Subtle subwoofer-style bass breathing without spinning or outer shock ring
                val bounceScale = 1.0f + musicBass * 0.16f + sin(timeSeconds * 3.2).toFloat() * 0.02f
                val padR = padBaseR * bounceScale
                val padY = emblemY + 0.008f + musicBass * 0.05f
                val pA = (0.48f + musicBass * 0.38f).coerceIn(0f, 0.92f)

                val segCount = 24
                for (i in 0 until segCount) {
                    val a0 = (i.toFloat() / segCount) * twoPi
                    val a1 = ((i + 0.75f) / segCount) * twoPi
                    val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
                    emitQuad(
                        quadBuffer, matrix,
                        padCx + c0 * (padR * 0.76f), padY, padCz + s0 * (padR * 0.76f), pr, pg, pb, pA * 0.32f,
                        padCx + c0 * padR, padY, padCz + s0 * padR, pr, pg, pb, pA,
                        padCx + c1 * padR, padY, padCz + s1 * padR, pr, pg, pb, pA,
                        padCx + c1 * (padR * 0.76f), padY, padCz + s1 * (padR * 0.76f), pr, pg, pb, pA * 0.32f
                    )
                    val innerR = padR * 0.56f
                    emitQuad(
                        quadBuffer, matrix,
                        padCx + c0 * (innerR * 0.78f), padY + 0.01f, padCz + s0 * (innerR * 0.78f), pr, pg, pb, pA * 0.4f,
                        padCx + c0 * innerR, padY + 0.01f, padCz + s0 * innerR, pr, pg, pb, pA * 0.85f,
                        padCx + c1 * innerR, padY + 0.01f, padCz + s1 * innerR, pr, pg, pb, pA * 0.85f,
                        padCx + c1 * (innerR * 0.78f), padY + 0.01f, padCz + s1 * (innerR * 0.78f), pr, pg, pb, pA * 0.4f
                    )
                }
            }
        }

        // 1C. Arched 3D Holographic Stadium Dome (Fallback if domeEnergyShader not ready or Iris is active)
        if (domeEnergyShader == null || useIrisPipeline) {
            for (l in 0 until DOME_LAT_STEPS) {
            val t0 = l.toFloat() / DOME_LAT_STEPS
            val t1 = (l + 1).toFloat() / DOME_LAT_STEPS
            val phi0 = t0 * maxPhi
            val phi1 = t1 * maxPhi
            val rad0 = cos(phi0)
            val rad1 = cos(phi1)
            val h0 = sin(phi0) / sinMaxPhi
            val h1 = sin(phi1) / sinMaxPhi

            val wave0 = exp(-abs(h0 - domeWavePhase) * 6.5f) * domeWaveEnvelope
            val wave1 = exp(-abs(h1 - domeWavePhase) * 6.5f) * domeWaveEnvelope

            val shellAlpha0 = (0.045f + (1f - h0) * 0.055f + wave0 * 0.16f).coerceIn(0f, 0.34f)
            val shellAlpha1 = (0.040f + (1f - h1) * 0.055f + wave1 * 0.16f).coerceIn(0f, 0.34f)

            for (i in 0 until RING_SEGMENTS) {
                val next = (i + 1) % RING_SEGMENTS
                val b0 = ringSideBlend[i]
                val b1 = ringSideBlend[next]

                val r0 = (stadiumR(b0) + wave0 * 0.25f).coerceIn(0f, 1f)
                val g0 = (stadiumG(b0) + wave0 * 0.25f).coerceIn(0f, 1f)
                val bl0 = (stadiumB(b0) + wave0 * 0.20f).coerceIn(0f, 1f)

                val r1 = (stadiumR(b1) + wave1 * 0.25f).coerceIn(0f, 1f)
                val g1 = (stadiumG(b1) + wave1 * 0.25f).coerceIn(0f, 1f)
                val bl1 = (stadiumB(b1) + wave1 * 0.20f).coerceIn(0f, 1f)

                val x00 = cx + ringCos[i] * rx * rad0
                val z00 = cz + ringSin[i] * rz * rad0
                val y00 = lerp(ringGroundY[i] + 0.06f, domeApexY, h0)

                val x10 = cx + ringCos[next] * rx * rad0
                val z10 = cz + ringSin[next] * rz * rad0
                val y10 = lerp(ringGroundY[next] + 0.06f, domeApexY, h0)

                val x11 = cx + ringCos[next] * rx * rad1
                val z11 = cz + ringSin[next] * rz * rad1
                val y11 = lerp(ringGroundY[next] + 0.06f, domeApexY, h1)

                val x01 = cx + ringCos[i] * rx * rad1
                val z01 = cz + ringSin[i] * rz * rad1
                val y01 = lerp(ringGroundY[i] + 0.06f, domeApexY, h1)

                fun fresnelAlpha(vx: Float, vy: Float, vz: Float, cVal: Float, sVal: Float, radVal: Float, phiVal: Float, baseA: Float): Float {
                    val vLen = sqrt(vx * vx + vy * vy + vz * vz).coerceAtLeast(0.001f)
                    val nx = cVal * radVal; val ny = sin(phiVal); val nz = sVal * radVal
                    val nLen = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(0.001f)
                    val dotNV = abs((nx / nLen) * (-vx / vLen) + (ny / nLen) * (-vy / vLen) + (nz / nLen) * (-vz / vLen)).coerceIn(0f, 1f)
                    val fres = Math.pow((1.0f - dotNV).toDouble(), 1.35).toFloat()
                    return (baseA * (0.45f + fres * 1.15f)).coerceIn(0f, 0.44f)
                }

                val a00 = fresnelAlpha(x00, y00, z00, ringCos[i], ringSin[i], rad0, phi0, shellAlpha0)
                val a10 = fresnelAlpha(x10, y10, z10, ringCos[next], ringSin[next], rad0, phi0, shellAlpha0)
                val a11 = fresnelAlpha(x11, y11, z11, ringCos[next], ringSin[next], rad1, phi1, shellAlpha1)
                val a01 = fresnelAlpha(x01, y01, z01, ringCos[i], ringSin[i], rad1, phi1, shellAlpha1)

                emitGlowQuad(
                    irisAddBuffer, matrix,
                    x00, y00, z00, r0, g0, bl0, a00,
                    x10, y10, z10, r1, g1, bl1, a10,
                    x11, y11, z11, r1, g1, bl1, a11,
                    x01, y01, z01, r0, g0, bl0, a01
                )
            }
        }
        }

        // Exterior 2: Dual Counter-Rotating Animated Zenith Oculus Rings
        run {
            val rotCW = (baseTheta * 2.0).toFloat()
            val rotCCW = (-baseTheta * 3.0).toFloat()
            val segs = 24
            for (ringIdx in 0 until 2) {
                val scaleIn = if (ringIdx == 0) oculusScale * 0.78f else oculusScale * 0.92f
                val scaleOut = scaleIn + 0.045f
                val rot = if (ringIdx == 0) rotCW else rotCCW
                val yOff = domeApexY + 0.04f + ringIdx * 0.14f
                for (k in 0 until segs) {
                    if (k % 2 == 1) continue // Dashed rotating energy segments
                    val a0 = (k.toFloat() / segs) * twoPi + rot
                    val a1 = ((k + 0.75f) / segs) * twoPi + rot
                    val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
                    val blend = ((s0 * 1.4f) * 0.5f + 0.5f).coerceIn(0f, 1f)
                    val rr = lerp(stadiumR(blend), 1.0f, 0.10f)
                    val rg = lerp(stadiumG(blend), 0.98f, 0.10f)
                    val rb = lerp(stadiumB(blend), 1.0f, 0.10f)
                    val ra = (0.58f + pulse * 0.32f).coerceIn(0f, 0.95f)
                    emitQuad(
                        quadBuffer, matrix,
                        cx + c0 * rx * scaleIn, yOff, cz + s0 * rz * scaleIn, rr, rg, rb, ra,
                        cx + c0 * rx * scaleOut, yOff, cz + s0 * rz * scaleOut, rr, rg, rb, ra * 0.5f,
                        cx + c1 * rx * scaleOut, yOff, cz + s1 * rz * scaleOut, rr, rg, rb, ra * 0.5f,
                        cx + c1 * rx * scaleIn, yOff, cz + s1 * rz * scaleIn, rr, rg, rb, ra
                    )
                }
            }
        }

        // Zenith Oculus Glowing Collar Ring
        val oculusOuter = (oculusScale + 0.055f).coerceAtMost(0.95f)
        val oculusInner = (oculusScale - 0.045f).coerceAtLeast(0.08f)
        val oculusWave = exp(-abs(1.0f - domeWavePhase) * 5.0f)
        val oculusAlpha = (0.38f + pulse * 0.18f + oculusWave * 0.30f).coerceIn(0f, 0.88f)
        for (i in 0 until RING_SEGMENTS) {
            val next = (i + 1) % RING_SEGMENTS
            val r0 = (stadiumR(ringSideBlend[i]) + 0.25f).coerceIn(0f, 1f)
            val g0 = (stadiumG(ringSideBlend[i]) + 0.25f).coerceIn(0f, 1f)
            val b0 = (stadiumB(ringSideBlend[i]) + 0.25f).coerceIn(0f, 1f)
            val r1 = (stadiumR(ringSideBlend[next]) + 0.25f).coerceIn(0f, 1f)
            val g1 = (stadiumG(ringSideBlend[next]) + 0.25f).coerceIn(0f, 1f)
            val b1 = (stadiumB(ringSideBlend[next]) + 0.25f).coerceIn(0f, 1f)

            emitQuad(
                quadBuffer, matrix,
                cx + ringCos[i] * rx * oculusInner, domeApexY, cz + ringSin[i] * rz * oculusInner, r0, g0, b0, oculusAlpha,
                cx + ringCos[i] * rx * oculusOuter, domeApexY, cz + ringSin[i] * rz * oculusOuter, r0, g0, b0, oculusAlpha * 0.35f,
                cx + ringCos[next] * rx * oculusOuter, domeApexY, cz + ringSin[next] * rz * oculusOuter, r1, g1, b1, oculusAlpha * 0.35f,
                cx + ringCos[next] * rx * oculusInner, domeApexY, cz + ringSin[next] * rz * oculusInner, r1, g1, b1, oculusAlpha
            )
        }

        // 1C-TOP. Holographic Pokeball Emblem at the Zenith Apex of the Dome
        run {
            val topRx = rx * oculusScale
            val topRz = rz * oculusScale
            val topY = domeApexY + 0.02f
            val topBtnR = minOf(topRx, topRz) * 0.28f
            val topBandHalf = minOf(topRx, topRz) * 0.09f
            for (i in 0 until EMBLEM_SEGMENTS) {
                val a0 = (i.toFloat() / EMBLEM_SEGMENTS) * twoPi
                val a1 = ((i + 1).toFloat() / EMBLEM_SEGMENTS) * twoPi
                val c0 = cos(a0)
                val s0 = sin(a0)
                val c1 = cos(a1)
                val s1 = sin(a1)
                val midS = (s0 + s1) * 0.5f
                val sideBlend = if (midS < 0f) 0.0f else 1.0f
                val pr = (stadiumR(sideBlend) + 0.12f).coerceIn(0f, 1f)
                val pg = (stadiumG(sideBlend) + 0.12f).coerceIn(0f, 1f)
                val pb = (stadiumB(sideBlend) + 0.12f).coerceIn(0f, 1f)
                val pa = 0.24f + pulse * 0.10f

                if (abs(midS) > 0.12f) {
                    emitQuad(
                        quadBuffer, matrix,
                        cx + c0 * topBtnR * 1.15f, topY, cz + s0 * topBtnR * 1.15f, pr, pg, pb, pa * 0.7f,
                        cx + c0 * topRx * 0.96f, topY, cz + s0 * topRz * 0.96f, pr, pg, pb, pa,
                        cx + c1 * topRx * 0.96f, topY, cz + s1 * topRz * 0.96f, pr, pg, pb, pa,
                        cx + c1 * topBtnR * 1.15f, topY, cz + s1 * topBtnR * 1.15f, pr, pg, pb, pa * 0.7f
                    )
                }
                // Center glowing Pokeball button
                val topBtnInner = 0.02f
                emitQuad(
                    quadBuffer, matrix,
                    cx + c0 * topBtnInner, topY + 0.01f, cz + s0 * topBtnInner, 1.0f, 1.0f, 1.0f, 0.55f + pulse * 0.25f,
                    cx + c0 * topBtnR * 0.8f, topY + 0.01f, cz + s0 * topBtnR * 0.8f, 0.75f, 0.94f, 1.0f, 0.35f,
                    cx + c1 * topBtnR * 0.8f, topY + 0.01f, cz + s1 * topBtnR * 0.8f, 0.75f, 0.94f, 1.0f, 0.35f,
                    cx + c1 * topBtnInner, topY + 0.01f, cz + s1 * topBtnInner, 1.0f, 1.0f, 1.0f, 0.55f + pulse * 0.25f
                )
            }
            // Equatorial Pokeball bars on dome apex
            val eqA = 0.52f + pulse * 0.18f
            emitQuad(
                quadBuffer, matrix,
                cx - topRx * 0.96f, topY + 0.01f, cz - topBandHalf, 0.94f, 0.98f, 1.0f, eqA,
                cx - topBtnR * 1.05f, topY + 0.01f, cz - topBandHalf, 0.94f, 0.98f, 1.0f, eqA,
                cx - topBtnR * 1.05f, topY + 0.01f, cz + topBandHalf, 0.94f, 0.98f, 1.0f, eqA,
                cx - topRx * 0.96f, topY + 0.01f, cz + topBandHalf, 0.94f, 0.98f, 1.0f, eqA
            )
            emitQuad(
                quadBuffer, matrix,
                cx + topBtnR * 1.05f, topY + 0.01f, cz - topBandHalf, 0.94f, 0.98f, 1.0f, eqA,
                cx + topRx * 0.96f, topY + 0.01f, cz - topBandHalf, 0.94f, 0.98f, 1.0f, eqA,
                cx + topRx * 0.96f, topY + 0.01f, cz + topBandHalf, 0.94f, 0.98f, 1.0f, eqA,
                cx + topBtnR * 1.05f, topY + 0.01f, cz + topBandHalf, 0.94f, 0.98f, 1.0f, eqA
            )
        }

        // 1C-RIBBONS. Thick Dome Outline Cables (Meridians + Latitude Rings) as 3D Quads
        run {
            val ribHalfThick = 0.11f
            val ribStep = 4 // 64 / 4 = 16 meridian cables
            for (i in 0 until RING_SEGMENTS step ribStep) {
                val blend = ringSideBlend[i]
                val baseR = stadiumR(blend)
                val baseG = stadiumG(blend)
                val baseB = stadiumB(blend)
                val c = ringCos[i]
                val s = ringSin[i]
                val gy = ringGroundY[i] + 0.06f
                val perpX = -s * ribHalfThick
                val perpZ = c * ribHalfThick

                for (l in 0 until DOME_LAT_STEPS) {
                    val phi0 = (l.toFloat() / DOME_LAT_STEPS) * maxPhi
                    val phi1 = ((l + 1).toFloat() / DOME_LAT_STEPS) * maxPhi
                    val rad0 = cos(phi0)
                    val rad1 = cos(phi1)
                    val h0 = sin(phi0) / sinMaxPhi
                    val h1 = sin(phi1) / sinMaxPhi
                    val w0 = exp(-abs(h0 - domeWavePhase) * 6.5f) * domeWaveEnvelope
                    val w1 = exp(-abs(h1 - domeWavePhase) * 6.5f) * domeWaveEnvelope

                    val x0 = cx + c * rx * rad0
                    val y0 = lerp(gy, domeApexY, h0)
                    val z0 = cz + s * rz * rad0
                    val x1 = cx + c * rx * rad1
                    val y1 = lerp(gy, domeApexY, h1)
                    val z1 = cz + s * rz * rad1

                    val cr0 = (baseR + w0 * 0.35f).coerceIn(0f, 1f)
                    val cg0 = (baseG + w0 * 0.35f).coerceIn(0f, 1f)
                    val cb0 = (baseB + w0 * 0.30f).coerceIn(0f, 1f)
                    val ca0 = (0.38f + w0 * 0.42f).coerceIn(0f, 0.88f)
                    val cr1 = (baseR + w1 * 0.35f).coerceIn(0f, 1f)
                    val cg1 = (baseG + w1 * 0.35f).coerceIn(0f, 1f)
                    val cb1 = (baseB + w1 * 0.30f).coerceIn(0f, 1f)
                    val ca1 = (0.38f + w1 * 0.42f).coerceIn(0f, 0.88f)

                    emitQuad(
                        quadBuffer, matrix,
                        x0 - perpX, y0, z0 - perpZ, cr0, cg0, cb0, ca0,
                        x0 + perpX, y0, z0 + perpZ, cr0, cg0, cb0, ca0,
                        x1 + perpX, y1, z1 + perpZ, cr1, cg1, cb1, ca1,
                        x1 - perpX, y1, z1 - perpZ, cr1, cg1, cb1, ca1
                    )
                }
            }

            for (l in 1..DOME_LAT_STEPS) {
                val phi = (l.toFloat() / DOME_LAT_STEPS) * maxPhi
                val rad = cos(phi)
                val h = sin(phi) / sinMaxPhi
                val isZenith = (l == DOME_LAT_STEPS)
                val w = exp(-abs(h - domeWavePhase) * 6.0f) * domeWaveEnvelope * domeWaveEnvelope
                val latAlpha = if (isZenith) (0.65f + pulse * 0.20f).coerceIn(0f, 0.90f) else (0.30f + w * 0.45f).coerceIn(0f, 0.85f)
                val ringT = if (isZenith) 0.13f else 0.10f

                for (i in 0 until RING_SEGMENTS) {
                    val next = (i + 1) % RING_SEGMENTS
                    val blend = ringSideBlend[i]
                    val lr = (stadiumR(blend) + (if (isZenith) 0.22f else 0f) + w * 0.32f).coerceIn(0f, 1f)
                    val lg = (stadiumG(blend) + (if (isZenith) 0.22f else 0f) + w * 0.32f).coerceIn(0f, 1f)
                    val lb = (stadiumB(blend) + (if (isZenith) 0.22f else 0f) + w * 0.28f).coerceIn(0f, 1f)

                    val x0 = cx + ringCos[i] * rx * rad
                    val z0 = cz + ringSin[i] * rz * rad
                    val y0 = lerp(ringGroundY[i] + 0.06f, domeApexY, h)
                    val x1 = cx + ringCos[next] * rx * rad
                    val z1 = cz + ringSin[next] * rz * rad
                    val y1 = lerp(ringGroundY[next] + 0.06f, domeApexY, h)

                    emitQuad(
                        quadBuffer, matrix,
                        x0, y0 - ringT, z0, lr, lg, lb, latAlpha,
                        x1, y1 - ringT, z1, lr, lg, lb, latAlpha,
                        x1, y1 + ringT, z1, lr, lg, lb, latAlpha,
                        x0, y0 + ringT, z0, lr, lg, lb, latAlpha
                    )
                }
            }
        }

        // 1D. Compute 8 Stadium Spotlight Positions around the Arena Perimeter (spaced well outside the dome)
        val numSpotlights = 8
        val spotX = FloatArray(numSpotlights)
        val spotY = FloatArray(numSpotlights)
        val spotZ = FloatArray(numSpotlights)
        val lensX = FloatArray(numSpotlights)
        val lensY = FloatArray(numSpotlights)
        val lensZ = FloatArray(numSpotlights)
        val beamTipX = FloatArray(numSpotlights)
        val beamTipY = FloatArray(numSpotlights)
        val beamTipZ = FloatArray(numSpotlights)
        val spotIsCrimson = BooleanArray(numSpotlights)
        val skyBeamHeight = 50.0f

        // Server-synced spotlight effect with continuous monotonic clock & 0.85s smooth crossfade
        val nowMs = System.currentTimeMillis()
        val timeSec = (nowMs - clientClockStartMs) / 1000.0
        val blendT = ((nowMs - effectBlendStartMs) / 850.0f).coerceIn(0f, 1f)
        val c1R = activeColor1Component(16)
        val c1G = activeColor1Component(8)
        val c1B = activeColor1Component(0)
        val c2R = activeColor2Component(16)
        val c2G = activeColor2Component(8)
        val c2B = activeColor2Component(0)

        val patternPower = if (lightsMode == SpotlightShowMode.APAGADO || currActiveEffectId == SpotlightShowMode.APAGADO.id) 0.0f else 1.0f

        for (sIdx in 0 until numSpotlights) {
            val angle = (sIdx.toFloat() / numSpotlights) * twoPi + (twoPi / 16.0f)
            val cosA = cos(angle)
            val sinA = sin(angle)
            // Distance 1.5x farther than before (2.18x the defined dome radius), brought 3 blocks closer
            val wx = centerWorldX + cosA * (halfW * 2.18 - 3.0).coerceAtLeast(halfW + 2.0)
            val wz = centerWorldZ + sinA * (halfD * 2.18 - 3.0).coerceAtLeast(halfD + 2.0)
            val px = (wx - camPos.x).toFloat()
            val pz = (wz - camPos.z).toFloat()
            val py = (getGroundY(level, wx, wz, zone.minY, player.y) - camPos.y).toFloat() + 0.04f

            spotX[sIdx] = px
            spotY[sIdx] = py
            spotZ[sIdx] = pz
            spotIsCrimson[sIdx] = sinA < 0.0f

            val toCenterX = cx - px
            val toCenterZ = cz - pz
            val distXZ = sqrt(toCenterX * toCenterX + toCenterZ * toCenterZ).coerceAtLeast(0.01f)
            val perpX = -toCenterZ / distXZ
            val perpZ = toCenterX / distXZ

            val sample = SpotlightEffectEvaluator.evaluateBlended(
                prevEffectId = prevActiveEffectId,
                currEffectId = currActiveEffectId,
                blendT = blendT,
                sIdx = sIdx,
                numSpotlights = numSpotlights,
                timeSec = timeSec,
                minR = minR,
                c1R = c1R, c1G = c1G, c1B = c1B,
                c2R = c2R, c2G = c2G, c2B = c2B
            )
            spotIntensity[sIdx] = sample.intensity
            spotWidthMult[sIdx] = sample.widthMult
            spotTargetR[sIdx] = sample.r
            spotTargetG[sIdx] = sample.g
            spotTargetB[sIdx] = sample.b

            val finalInward = (sample.inward / minR.coerceAtLeast(1f))
            val finalLat = sample.lat * (minR * 0.85f + 10.0f)

            beamTipX[sIdx] = px + toCenterX * finalInward + perpX * finalLat
            beamTipY[sIdx] = py + skyBeamHeight
            beamTipZ[sIdx] = pz + toCenterZ * finalInward + perpZ * finalLat

            val pivotY = py + 0.62f
            val adx = beamTipX[sIdx] - px
            val ady = beamTipY[sIdx] - pivotY
            val adz = beamTipZ[sIdx] - pz
            val alen = sqrt(adx * adx + ady * ady + adz * adz).coerceAtLeast(0.01f)
            lensX[sIdx] = px + (adx / alen) * 0.34f
            lensY[sIdx] = pivotY + (ady / alen) * 0.34f
            lensZ[sIdx] = pz + (adz / alen) * 0.34f
        }

        // 1E. Pulsing Pokeball Court Energy Wave Ring radiating from center button outward
        val courtWaveT = ((timeSeconds * 0.52) % 1.0).toFloat()
        val courtWaveR = lerp(buttonOuterR, minR * innerRingScale, courtWaveT)
        val courtWaveThick = (emblemR * 0.055f).coerceIn(0.08f, 0.24f)
        val courtWaveAlpha = ((1.0f - courtWaveT) * (1.0f - courtWaveT) * 0.55f).coerceIn(0f, 0.55f)
        if (courtWaveAlpha > 0.015f) {
            val cwY = emblemY + 0.009f
            for (i in 0 until EMBLEM_SEGMENTS) {
                val a0 = (i.toFloat() / EMBLEM_SEGMENTS) * twoPi
                val a1 = ((i + 1).toFloat() / EMBLEM_SEGMENTS) * twoPi
                val c0 = cos(a0)
                val s0 = sin(a0)
                val c1 = cos(a1)
                val s1 = sin(a1)
                val sb0 = ((s0 * 2.6f) * 0.5f + 0.5f).coerceIn(0f, 1f)
                val sb1 = ((s1 * 2.6f) * 0.5f + 0.5f).coerceIn(0f, 1f)
                val wr0 = (stadiumR(sb0) + 0.25f).coerceIn(0f, 1f)
                val wg0 = (stadiumG(sb0) + 0.25f).coerceIn(0f, 1f)
                val wb0 = (stadiumB(sb0) + 0.25f).coerceIn(0f, 1f)
                val wr1 = (stadiumR(sb1) + 0.25f).coerceIn(0f, 1f)
                val wg1 = (stadiumG(sb1) + 0.25f).coerceIn(0f, 1f)
                val wb1 = (stadiumB(sb1) + 0.25f).coerceIn(0f, 1f)
                val rIn = (courtWaveR - courtWaveThick).coerceAtLeast(0.05f)
                emitGlowQuad(
                    irisAddBuffer, matrix,
                    cx + c0 * rIn, cwY, cz + s0 * rIn, wr0, wg0, wb0, 0.0f,
                    cx + c0 * courtWaveR, cwY, cz + s0 * courtWaveR, wr0, wg0, wb0, courtWaveAlpha,
                    cx + c1 * courtWaveR, cwY, cz + s1 * courtWaveR, wr1, wg1, wb1, courtWaveAlpha,
                    cx + c1 * rIn, cwY, cz + s1 * rIn, wr1, wg1, wb1, 0.0f
                )
            }
        }

        // 1F. Animated Rising Battle Sparks around the Perimeter Ring
        for (sIdx in 0 until PERIMETER_SPARKS) {
            val phase = ((timeSeconds * (0.28 + (sIdx % 4) * 0.05) + sIdx * 0.173) % 1.0).toFloat()
            val fadeIn = (phase / 0.15f).coerceIn(0f, 1f)
            val fadeOut = ((1f - phase) / 0.30f).coerceIn(0f, 1f)
            val sparkAlpha = (fadeIn * fadeOut * 0.85f).coerceIn(0f, 0.88f)
            if (sparkAlpha <= 0.02f) continue

            val ringIdx = (sIdx * RING_SEGMENTS) / PERIMETER_SPARKS
            val c = ringCos[ringIdx]
            val s = ringSin[ringIdx]
            val blend = ringSideBlend[ringIdx]
            val sr = (stadiumR(blend) + 0.22f).coerceIn(0f, 1f)
            val sg = (stadiumG(blend) + 0.22f).coerceIn(0f, 1f)
            val sb = (stadiumB(blend) + 0.18f).coerceIn(0f, 1f)

            val drift = sin(timeSeconds * 2.2 + sIdx * 1.4).toFloat() * 0.18f
            val sx = cx + c * (rx - 0.12f) - s * drift
            val sz = cz + s * (rz - 0.12f) + c * drift
            val sy = ringGroundY[ringIdx] + 0.25f + phase * (domeHeight * 0.48f)

            val spin = (timeSeconds * 3.0 + sIdx).toFloat()
            val sc = cos(spin)
            val ss = sin(spin)
            val sw = 0.095f
            val sh = 0.16f

            emitGlowQuad(
                irisAddBuffer, matrix,
                sx, sy - sh, sz, sr, sg, sb, sparkAlpha,
                sx + sc * sw, sy, sz + ss * sw, 1.0f, 0.98f, 0.96f, sparkAlpha,
                sx, sy + sh, sz, sr, sg, sb, sparkAlpha,
                sx - sc * sw, sy, sz - ss * sw, 1.0f, 0.98f, 0.96f, sparkAlpha
            )
        }

        if (!useIrisPipeline && quadBuffer is BufferBuilder) {
            quadBuffer.build()?.let { BufferUploader.drawWithShader(it) }
        }

        // =========================================================================
        val nowColorMs = System.currentTimeMillis()
        val colorDt = if (lastColorUpdateMs == 0L) 1.0f else ((nowColorMs - lastColorUpdateMs).coerceIn(1L, 250L) / 1000.0f)
        lastColorUpdateMs = nowColorMs
        val colorBlend = (colorDt * 2.4f).coerceIn(0.0f, 1.0f)

        // PASS 1B-DAYLIGHT: Smooth color interpolation + Daytime Prisma Lasers & Ascending Pulse Rings (no depthMask(true) black spots)
        RenderSystem.defaultBlendFunc()
        RenderSystem.depthMask(false)
        val beamPower = lerp(0.88f, 1.0f, effectiveDarkness) * patternPower
        val isDaylightLaserMode = effectiveDarkness < 0.35f

        if (beamPower > 0.01f) {
            val dayLaserBuffer: VertexConsumer? = if (isDaylightLaserMode) {
                if (useIrisPipeline) {
                    irisAddBuffer
                } else {
                    tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
                }
            } else {
                null
            }
            for (sIdx in 0 until numSpotlights) {
                val px = lensX[sIdx]
                val py = lensY[sIdx]
                val pz = lensZ[sIdx]
                val baseTx = beamTipX[sIdx]
                val baseTy = beamTipY[sIdx]
                val baseTz = beamTipZ[sIdx]

                smoothSpotR[sIdx] = lerp(smoothSpotR[sIdx], spotTargetR[sIdx], colorBlend)
                smoothSpotG[sIdx] = lerp(smoothSpotG[sIdx], spotTargetG[sIdx], colorBlend)
                smoothSpotB[sIdx] = lerp(smoothSpotB[sIdx], spotTargetB[sIdx], colorBlend)

                if (dayLaserBuffer != null) {
                    val sr = smoothSpotR[sIdx]
                    val sg = smoothSpotG[sIdx]
                    val sb = smoothSpotB[sIdx]
                    val flickerMul = spotIntensity[sIdx]
                    val rodCount = when (lightsRodMode) {
                        "1" -> 1
                        "3" -> 3
                        else -> when (sIdx % 3) { 0 -> 3; 1 -> 2; else -> 1 }
                    }
                    val dayFade = (1.0f - (effectiveDarkness / 0.35f)).coerceIn(0f, 1f)
                    for (rodIdx in 0 until rodCount) {
                        val fanSpread = if (rodCount == 1) 0.0f else (rodIdx - (rodCount - 1) * 0.5f) * 8.5f
                        val tx = baseTx + cos(sIdx * 0.9f + (baseTheta * 2.0).toFloat()) * fanSpread
                        val ty = baseTy
                        val tz = baseTz + sin(sIdx * 0.9f + (baseTheta * 2.0).toFloat()) * fanSpread

                        val dx = tx - px; val dy = ty - py; val dz = tz - pz
                        val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.01f)
                        val ax = dx / len; val ay = dy / len; val az = dz / len
                        var ux = -az; var uy = 0.0f; var uz = ax
                        val uLen = sqrt(ux * ux + uy * uy + uz * uz)
                        if (uLen < 0.001f) { ux = 1f; uy = 0f; uz = 0f } else { ux /= uLen; uy /= uLen; uz /= uLen }
                        val vx = ay * uz - az * uy
                        val vy = az * ux - ax * uz
                        val vz = ax * uy - ay * ux

                        val sides = 8
                        val scaleMult = if (rodCount == 1) 1.0f else 0.72f
                        val laserBaseR = 0.14f * scaleMult
                        val laserTopR = 0.24f * scaleMult
                        val laserBaseA = (0.88f * beamPower * flickerMul * dayFade).coerceIn(0f, 0.92f)
                        val laserSegs = if (usePhotonPipeline) 4 else 1

                        for (seg in 0 until laserSegs) {
                            val h0 = seg.toFloat() / laserSegs
                            val h1 = (seg + 1).toFloat() / laserSegs
                            val sx0 = lerp(px, tx, h0); val sy0 = lerp(py, ty, h0); val sz0 = lerp(pz, tz, h0)
                            val sx1 = lerp(px, tx, h1); val sy1 = lerp(py, ty, h1); val sz1 = lerp(pz, tz, h1)
                            val sr0 = lerp(laserBaseR, laserTopR, h0)
                            val sr1 = lerp(laserBaseR, laserTopR, h1)
                            val sa0 = laserBaseA * (1.0f - h0)
                            val sa1 = laserBaseA * (1.0f - h1)
                            for (k in 0 until sides) {
                                val a0 = (k.toFloat() / sides) * twoPi
                                val a1 = ((k + 1).toFloat() / sides) * twoPi
                                val c0 = cos(a0); val s0 = sin(a0)
                                val c1 = cos(a1); val s1 = sin(a1)
                                val n0x = ux * c0 + vx * s0; val n0y = uy * c0 + vy * s0; val n0z = uz * c0 + vz * s0
                                val n1x = ux * c1 + vx * s1; val n1y = uy * c1 + vy * s1; val n1z = uz * c1 + vz * s1
                                emitGlowQuad(
                                    dayLaserBuffer, matrix,
                                    sx0 + n0x * sr0, sy0 + n0y * sr0, sz0 + n0z * sr0, sr, sg, sb, sa0,
                                    sx0 + n1x * sr0, sy0 + n1y * sr0, sz0 + n1z * sr0, sr, sg, sb, sa0,
                                    sx1 + n1x * sr1, sy1 + n1y * sr1, sz1 + n1z * sr1, sr, sg, sb, sa1,
                                    sx1 + n0x * sr1, sy1 + n0y * sr1, sz1 + n0z * sr1, sr, sg, sb, sa1
                                )
                            }
                        }

                        // 3 ascending holographic pulse rings sliding up each daytime laser
                        for (ringIdx in 0 until 3) {
                            val ringT = ((loopT * 3.0 + sIdx * 0.17 + ringIdx * 0.333) % 1.0).toFloat()
                            val rxPos = px + dx * ringT
                            val ryPos = py + dy * ringT
                            val rzPos = pz + dz * ringT
                            val ringR = (0.55f + ringT * 1.15f) * scaleMult
                            val ringH = 0.18f
                            val ringA = (0.82f * (1.0f - ringT) * sin(ringT * PI.toFloat()).coerceIn(0f, 1f) * beamPower * flickerMul * dayFade).coerceIn(0f, 0.85f)
                            if (ringA <= 0.01f) continue
                            for (k in 0 until sides) {
                                val a0 = (k.toFloat() / sides) * twoPi
                                val a1 = ((k + 1).toFloat() / sides) * twoPi
                                val c0 = cos(a0); val s0 = sin(a0)
                                val c1 = cos(a1); val s1 = sin(a1)
                                val n0x = ux * c0 + vx * s0; val n0y = uy * c0 + vy * s0; val n0z = uz * c0 + vz * s0
                                val n1x = ux * c1 + vx * s1; val n1y = uy * c1 + vy * s1; val n1z = uz * c1 + vz * s1
                                emitGlowQuad(
                                    dayLaserBuffer, matrix,
                                    rxPos + n0x * ringR, ryPos + n0y * ringR, rzPos + n0z * ringR, 1.0f, 0.96f, 0.98f, ringA,
                                    rxPos + n1x * ringR, ryPos + n1y * ringR, rzPos + n1z * ringR, 1.0f, 0.96f, 0.98f, ringA,
                                    rxPos + ax * ringH + n1x * ringR, ryPos + ay * ringH + n1y * ringR, rzPos + az * ringH + n1z * ringR, sr, sg, sb, ringA * 0.7f,
                                    rxPos + ax * ringH + n0x * ringR, ryPos + ay * ringH + n0y * ringR, rzPos + az * ringH + n0z * ringR, sr, sg, sb, ringA * 0.7f
                                )
                            }
                        }
                    }
                }
            }
            if (!useIrisPipeline && dayLaserBuffer is BufferBuilder) {
                dayLaserBuffer.build()?.let { BufferUploader.drawWithShader(it) }
            }
        }

        // PASS 1B: ADDITIVE BLOOM (Living Dome Cable Pulses + Multi-Rod Outer Bloom & Sky Discs)
        // =========================================================================
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE)
        val bloomBuffer: VertexConsumer = if (useIrisPipeline) {
            irisAddBuffer
        } else {
            tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        }
        val skyCeilingY = centerY + skyBeamHeight

        // Living holographic bloom pulses traveling smoothly along the 16 dome cable meridians
        run {
            val cableBloomHalf = 0.24f
            val cableStep = 4
            for (i in 0 until RING_SEGMENTS step cableStep) {
                val blend = ringSideBlend[i]
                val br = stadiumR(blend)
                val bg = stadiumG(blend)
                val bb = stadiumB(blend)
                val c = ringCos[i]
                val s = ringSin[i]
                val gy = ringGroundY[i] + 0.06f
                val perpX = -s * cableBloomHalf
                val perpZ = c * cableBloomHalf

                for (l in 0 until DOME_LAT_STEPS) {
                    val phi0 = (l.toFloat() / DOME_LAT_STEPS) * maxPhi
                    val phi1 = ((l + 1).toFloat() / DOME_LAT_STEPS) * maxPhi
                    val rad0 = cos(phi0); val rad1 = cos(phi1)
                    val h0 = sin(phi0) / sinMaxPhi; val h1 = sin(phi1) / sinMaxPhi

                    val flow0 = (sin(h0 * 9.0f - (baseTheta * 6.0).toFloat() + i * 0.4f) * 0.5f + 0.5f)
                    val flow1 = (sin(h1 * 9.0f - (baseTheta * 6.0).toFloat() + i * 0.4f) * 0.5f + 0.5f)
                    val a0 = (0.06f + flow0 * flow0 * 0.34f).coerceIn(0f, 0.42f)
                    val a1 = (0.06f + flow1 * flow1 * 0.34f).coerceIn(0f, 0.42f)

                    val x0 = cx + c * rx * rad0; val y0 = lerp(gy, domeApexY, h0); val z0 = cz + s * rz * rad0
                    val x1 = cx + c * rx * rad1; val y1 = lerp(gy, domeApexY, h1); val z1 = cz + s * rz * rad1
                    emitGlowQuad(
                        bloomBuffer, matrix,
                        x0 - perpX, y0, z0 - perpZ, br, bg, bb, a0,
                        x0 + perpX, y0, z0 + perpZ, br, bg, bb, a0,
                        x1 + perpX, y1, z1 + perpZ, br, bg, bb, a1,
                        x1 - perpX, y1, z1 - perpZ, br, bg, bb, a1
                    )
                }
            }
        }

        for (sIdx in 0 until numSpotlights) {
            val baseX = spotX[sIdx]
            val baseY = spotY[sIdx]
            val baseZ = spotZ[sIdx]
            val px = lensX[sIdx]
            val py = lensY[sIdx]
            val pz = lensZ[sIdx]
            val baseTx = beamTipX[sIdx]
            val baseTy = beamTipY[sIdx]
            val baseTz = beamTipZ[sIdx]

            val sr = smoothSpotR[sIdx]
            val sg = smoothSpotG[sIdx]
            val sb = smoothSpotB[sIdx]

            val flickerMul = spotIntensity[sIdx]

            // Luminous light-style projector housing & glowing ground halo
            val fixtureA = (0.36f + 0.14f * pulse) * (0.35f + 0.65f * beamPower * flickerMul)
            if (fixtureA > 0.01f) {
                val pivotY = baseY + 0.62f
                val adx = baseTx - baseX; val ady = baseTy - pivotY; val adz = baseTz - baseZ
                val alen = sqrt(adx * adx + ady * ady + adz * adz).coerceAtLeast(0.01f)
                val aux = adx / alen; val auy = ady / alen; val auz = adz / alen
                val sideLen = sqrt(aux * aux + auz * auz).coerceAtLeast(0.001f)
                val arx = -auz / sideLen; val arz = aux / sideLen
                val avx = auy * arz; val avy = auz * arx - aux * arz; val avz = -auy * arx

                val bx = baseX - aux * 0.22f; val by = pivotY - auy * 0.22f; val bz = baseZ - auz * 0.22f
                val backR = 0.21f; val frontR = 0.31f
                for (k in 0 until 8) {
                    val a0 = (k / 8.0f) * twoPi; val a1 = ((k + 1) / 8.0f) * twoPi
                    val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
                    val bc0x = bx + (arx * c0 + avx * s0) * backR; val bc0y = by + (avy * s0) * backR; val bc0z = bz + (arz * c0 + avz * s0) * backR
                    val bc1x = bx + (arx * c1 + avx * s1) * backR; val bc1y = by + (avy * s1) * backR; val bc1z = bz + (arz * c1 + avz * s1) * backR
                    val fc0x = px + (arx * c0 + avx * s0) * frontR; val fc0y = py + (avy * s0) * frontR; val fc0z = pz + (arz * c0 + avz * s0) * frontR
                    val fc1x = px + (arx * c1 + avx * s1) * frontR; val fc1y = py + (avy * s1) * frontR; val fc1z = pz + (arz * c1 + avz * s1) * frontR
                    emitGlowQuad(
                        bloomBuffer, matrix,
                        bc0x, bc0y, bc0z, sr, sg, sb, fixtureA * 0.45f,
                        bc1x, bc1y, bc1z, sr, sg, sb, fixtureA * 0.45f,
                        fc1x, fc1y, fc1z, sr, sg, sb, fixtureA * 0.85f,
                        fc0x, fc0y, fc0z, sr, sg, sb, fixtureA * 0.85f
                    )
                    // Incandescent front lens disc centered at (px, py, pz)
                    val lInR = 0.015f
                    val lc0x = px + (arx * c0 + avx * s0) * lInR; val lc0y = py + (avy * s0) * lInR; val lc0z = pz + (arz * c0 + avz * s0) * lInR
                    val lc1x = px + (arx * c1 + avx * s1) * lInR; val lc1y = py + (avy * s1) * lInR; val lc1z = pz + (arz * c1 + avz * s1) * lInR
                    emitGlowQuad(
                        bloomBuffer, matrix,
                        lc0x, lc0y, lc0z, 1.0f, 0.98f, 1.0f, (fixtureA * 1.45f).coerceIn(0f, 0.95f),
                        fc0x, fc0y, fc0z, sr, sg, sb, fixtureA * 0.75f,
                        fc1x, fc1y, fc1z, sr, sg, sb, fixtureA * 0.75f,
                        lc1x, lc1y, lc1z, 1.0f, 0.98f, 1.0f, (fixtureA * 1.45f).coerceIn(0f, 0.95f)
                    )
                    // Soft glowing ground ring around pedestal
                    val gInner = 0.25f; val gOuter = 0.78f
                    emitGlowQuad(
                        bloomBuffer, matrix,
                        baseX + c0 * gInner, baseY + 0.03f, baseZ + s0 * gInner, sr, sg, sb, fixtureA * 0.55f,
                        baseX + c1 * gInner, baseY + 0.03f, baseZ + s1 * gInner, sr, sg, sb, fixtureA * 0.55f,
                        baseX + c1 * gOuter, baseY + 0.03f, baseZ + s1 * gOuter, sr, sg, sb, 0.0f,
                        baseX + c0 * gOuter, baseY + 0.03f, baseZ + s0 * gOuter, sr, sg, sb, 0.0f
                    )
                }
            }

            // Multi-rod fan variation (manual override "1" or "3", or automatic mix)
            val rodCount = when (lightsRodMode) {
                "1" -> 1
                "3" -> 3
                else -> when (sIdx % 3) {
                    0 -> 3
                    1 -> 2
                    else -> 1
                }
            }

            // Glowing projector lens & crossed flare star at the ground projector mouth
            val lensA = (0.72f * beamPower * flickerMul).coerceIn(0f, 0.85f)
            if (lensA > 0.01f) {
                val flareR = 1.45f * (0.85f + 0.15f * pulse)
                val flareH = 0.24f
                // Horizontal star diamond
                emitGlowQuad(
                    bloomBuffer, matrix,
                    px - flareR, py, pz, sr, sg, sb, 0.0f,
                    px, py, pz - flareH, 1.0f, 0.96f, 0.98f, lensA,
                    px + flareR, py, pz, sr, sg, sb, 0.0f,
                    px, py, pz + flareH, 1.0f, 0.96f, 0.98f, lensA
                )
                emitGlowQuad(
                    bloomBuffer, matrix,
                    px, py, pz - flareR, sr, sg, sb, 0.0f,
                    px + flareH, py, pz, 1.0f, 0.96f, 0.98f, lensA,
                    px, py, pz + flareR, sr, sg, sb, 0.0f,
                    px - flareH, py, pz, 1.0f, 0.96f, 0.98f, lensA
                )
                // Vertical star cross
                emitGlowQuad(
                    bloomBuffer, matrix,
                    px - flareH, py, pz, 1.0f, 0.96f, 0.98f, lensA * 0.85f,
                    px, py + flareR * 0.85f, pz, sr, sg, sb, 0.0f,
                    px + flareH, py, pz, 1.0f, 0.96f, 0.98f, lensA * 0.85f,
                    px, py - flareR * 0.35f, pz, sr, sg, sb, 0.0f
                )
                emitGlowQuad(
                    bloomBuffer, matrix,
                    px, py, pz - flareH, 1.0f, 0.96f, 0.98f, lensA * 0.85f,
                    px, py + flareR * 0.85f, pz, sr, sg, sb, 0.0f,
                    px, py, pz + flareH, 1.0f, 0.96f, 0.98f, lensA * 0.85f,
                    px, py - flareR * 0.35f, pz, sr, sg, sb, 0.0f
                )
            }

            // Illuminated floating dust motes inside the beam cone (within 80 blocks of camera)
            if (px * px + py * py + pz * pz < 6400.0f) {
                val bDx = baseTx - px; val bDy = baseTy - py; val bDz = baseTz - pz
                val bLen = sqrt(bDx * bDx + bDy * bDy + bDz * bDz).coerceAtLeast(0.01f)
                val bAx = bDx / bLen; val bAy = bDy / bLen; val bAz = bDz / bLen
                var mUx = -bAz; var mUy = 0.0f; var mUz = bAx
                val mULen = sqrt(mUx * mUx + mUy * mUy + mUz * mUz)
                if (mULen < 0.001f) { mUx = 1f; mUy = 0f; mUz = 0f } else { mUx /= mULen; mUy /= mULen; mUz /= mULen }
                val mVx = bAy * mUz - bAz * mUy
                val mVy = bAz * mUx - bAx * mUz
                val mVz = bAx * mUy - bAy * mUx

                val moteCount = 5
                for (m in 0 until moteCount) {
                    val moteT = ((baseTheta * 0.14 + sIdx * 0.19 + m * 0.21) % 1.0).toFloat()
                    val fadeEnv = sin(moteT * PI.toFloat()).coerceIn(0f, 1f) * (1.0f - moteT * 0.45f)
                    val moteA = (0.58f * fadeEnv * beamPower * flickerMul).coerceIn(0f, 0.70f)
                    if (moteA <= 0.01f) continue

                    val swirlAng = (baseTheta * 1.3 + m * 2.1 + sIdx * 0.7).toFloat()
                    val coneR = (0.18f + moteT * 1.45f) * (0.45f + 0.55f * ((m * 37 % 10) / 10.0f))
                    val offU = cos(swirlAng) * coneR
                    val offV = sin(swirlAng) * coneR
                    val mxPos = px + bDx * moteT + mUx * offU + mVx * offV
                    val myPos = py + bDy * moteT + mUy * offU + mVy * offV
                    val mzPos = pz + bDz * moteT + mUz * offU + mVz * offV
                    val ms = 0.08f + 0.04f * pulse

                    val mr = lerp(1.0f, sr, 0.45f)
                    val mg = lerp(1.0f, sg, 0.45f)
                    val mb = lerp(1.0f, sb, 0.45f)
                    emitGlowQuad(
                        bloomBuffer, matrix,
                        mxPos - mUx * ms, myPos - mUy * ms, mzPos - mUz * ms, mr, mg, mb, moteA,
                        mxPos - mVx * ms, myPos - mVy * ms, mzPos - mVz * ms, mr, mg, mb, moteA,
                        mxPos + mUx * ms, myPos + mUy * ms, mzPos + mUz * ms, mr, mg, mb, moteA,
                        mxPos + mVx * ms, myPos + mVy * ms, mzPos + mVz * ms, mr, mg, mb, moteA
                    )
                    emitGlowQuad(
                        bloomBuffer, matrix,
                        mxPos - mUx * ms, myPos - mUy * ms - ms, mzPos - mUz * ms, mr, mg, mb, moteA * 0.8f,
                        mxPos + mUx * ms, myPos + mUy * ms - ms, mzPos + mUz * ms, mr, mg, mb, moteA * 0.8f,
                        mxPos + mUx * ms, myPos + mUy * ms + ms, mzPos + mUz * ms, mr, mg, mb, moteA * 0.8f,
                        mxPos - mUx * ms, myPos - mUy * ms + ms, mzPos - mUz * ms, mr, mg, mb, moteA * 0.8f
                    )
                }
            }
        }

        // Broad overhead stadium sky-wash canopy in vanilla (skipped under Iris/Photon so shaders don't render an opaque sky disc)
        if (!useIrisPipeline) {
            val canopyOuterR = maxOf(rx, rz) * 2.4f + 45.0f
            val canopyMidR = canopyOuterR * 0.45f
            val canopyInnerR = 0.25f
            val canopyAlpha = ((0.09f + pulse * 0.04f) * (1.0f - insideDarkRoomFactor * 0.90f)).coerceIn(0f, 0.16f)
            for (i in 0 until 24) {
                val a0 = (i / 24.0f) * twoPi
                val a1 = ((i + 1) / 24.0f) * twoPi
                val c0 = cos(a0)
                val s0 = sin(a0)
                val c1 = cos(a1)
                val s1 = sin(a1)
                val b0 = ((s0 * 1.4f) * 0.5f + 0.5f).coerceIn(0f, 1f)
                val b1 = ((s1 * 1.4f) * 0.5f + 0.5f).coerceIn(0f, 1f)
                val r0 = stadiumR(b0)
                val g0 = stadiumG(b0)
                val bl0 = stadiumB(b0)
                val r1 = stadiumR(b1)
                val g1 = stadiumG(b1)
                val bl1 = stadiumB(b1)
                emitQuad(
                    bloomBuffer, matrix,
                    cx + c0 * canopyInnerR, skyCeilingY, cz + s0 * canopyInnerR, 0.72f, 0.68f, 0.95f, canopyAlpha * 1.15f,
                    cx + c0 * canopyMidR, skyCeilingY - 3.0f, cz + s0 * canopyMidR, r0, g0, bl0, canopyAlpha * 0.65f,
                    cx + c1 * canopyMidR, skyCeilingY - 3.0f, cz + s1 * canopyMidR, r1, g1, bl1, canopyAlpha * 0.65f,
                    cx + c1 * canopyInnerR, skyCeilingY, cz + s1 * canopyInnerR, 0.72f, 0.68f, 0.95f, canopyAlpha * 1.15f
                )
                emitQuad(
                    bloomBuffer, matrix,
                    cx + c0 * canopyMidR, skyCeilingY - 3.0f, cz + s0 * canopyMidR, r0, g0, bl0, canopyAlpha * 0.65f,
                    cx + c0 * canopyOuterR, skyCeilingY - 10.0f, cz + s0 * canopyOuterR, r0, g0, bl0, 0.0f,
                    cx + c1 * canopyOuterR, skyCeilingY - 10.0f, cz + s1 * canopyOuterR, r1, g1, bl1, 0.0f,
                    cx + c1 * canopyMidR, skyCeilingY - 3.0f, cz + s1 * canopyMidR, r1, g1, bl1, canopyAlpha * 0.65f
                )
            }
        }

        // Interior 2: Perimeter Audio-Pulse Equalizer Bars synced EXCLUSIVELY to the dome music channel
        run {
            val eqBars = 32
            val eqStep = RING_SEGMENTS / eqBars
            val barHalfW = 0.22f * radiusScale
            val bands = ArenaMusicClient.spectrumBands
            for (bIdx in 0 until eqBars) {
                val i = (bIdx * eqStep) % RING_SEGMENTS
                val c = ringCos[i]; val s = ringSin[i]
                val blend = ringSideBlend[i]
                val er = stadiumR(blend); val eg = stadiumG(blend); val eb = stadiumB(blend)
                val bx = cx + c * (rx * innerRingScale)
                val bz = cz + s * (rz * innerRingScale)
                val by = ringGroundY[i] + 0.06f
                val perpX = -s * barHalfW; val perpZ = c * barHalfW
                val bandEnergy = bands[bIdx % 8].coerceIn(0.0f, 1.0f)
                val barH = (0.24f + bandEnergy * 2.35f) * radiusScale.coerceAtMost(1.8f)
                val barA = (0.24f + bandEnergy * 0.62f).coerceIn(0f, 0.86f)
                emitGlowQuad(
                    bloomBuffer, matrix,
                    bx - perpX, by, bz - perpZ, er, eg, eb, barA,
                    bx + perpX, by, bz + perpZ, er, eg, eb, barA,
                    bx + perpX, by + barH, bz + perpZ, er, eg, eb, 0.0f,
                    bx - perpX, by + barH, bz - perpZ, er, eg, eb, 0.0f
                )
            }
        }

        // Interior 3: Subtle Low Stage Ground Haze + Floating Crimson/Blue Embers inside the Arena
        run {
            val hazeRIn = rx * 0.78f; val hazeROut = rx * 0.96f
            val hazeZIn = rz * 0.78f; val hazeZOut = rz * 0.96f
            val hazeA = (0.09f + 0.05f * pulse + insideDarkRoomFactor * 0.08f).coerceIn(0f, 0.22f)
            for (i in 0 until RING_SEGMENTS step 2) {
                val next = (i + 2) % RING_SEGMENTS
                val b0 = ringSideBlend[i]; val b1 = ringSideBlend[next]
                val y0 = ringGroundY[i] + 0.05f; val y1 = ringGroundY[next] + 0.05f
                emitGlowQuad(
                    bloomBuffer, matrix,
                    cx + ringCos[i] * hazeRIn, y0 + 0.35f, cz + ringSin[i] * hazeZIn, stadiumR(b0), stadiumG(b0), stadiumB(b0), 0.0f,
                    cx + ringCos[i] * hazeROut, y0, cz + ringSin[i] * hazeZOut, stadiumR(b0), stadiumG(b0), stadiumB(b0), hazeA,
                    cx + ringCos[next] * hazeROut, y1, cz + ringSin[next] * hazeZOut, stadiumR(b1), stadiumG(b1), stadiumB(b1), hazeA,
                    cx + ringCos[next] * hazeRIn, y1 + 0.35f, cz + ringSin[next] * hazeZIn, stadiumR(b1), stadiumG(b1), stadiumB(b1), 0.0f
                )
            }

            // 24 ambient glowing embers drifting upward inside the dome
            val emberCount = 24
            for (e in 0 until emberCount) {
                val eT = ((baseTheta * 0.16 + e * 0.137) % 1.0).toFloat()
                val ang = (e.toFloat() / emberCount) * twoPi + sin(baseTheta + e).toFloat() * 0.25f
                val radFrac = 0.18f + 0.68f * (((e * 53) % 17) / 17.0f)
                val ex = cx + cos(ang) * (rx * radFrac)
                val ez = cz + sin(ang) * (rz * radFrac)
                val ey = centerY + 0.3f + eT * (domeHeight * 0.72f)
                val sideB = ((sin(ang) * 2.2f) * 0.5f + 0.5f).coerceIn(0f, 1f)
                val er = stadiumR(sideB); val eg = stadiumG(sideB); val eb = stadiumB(sideB)
                val eEnv = sin(eT * PI.toFloat()).coerceIn(0f, 1f)
                val ea = (0.55f * eEnv * (0.45f + 0.55f * maxOf(pulse, insideDarkRoomFactor))).coerceIn(0f, 0.75f)
                if (ea <= 0.01f) continue
                val es = 0.085f + 0.04f * pulse
                emitGlowQuad(
                    bloomBuffer, matrix,
                    ex - es, ey, ez, er, eg, eb, ea,
                    ex, ey - es, ez, 1.0f, 0.96f, 1.0f, ea,
                    ex + es, ey, ez, er, eg, eb, ea,
                    ex, ey + es, ez, 1.0f, 0.96f, 1.0f, ea
                )
                emitGlowQuad(
                    bloomBuffer, matrix,
                    ex, ey, ez - es, er, eg, eb, ea,
                    ex, ey - es, ez, 1.0f, 0.96f, 1.0f, ea,
                    ex, ey, ez + es, er, eg, eb, ea,
                    ex, ey + es, ez, 1.0f, 0.96f, 1.0f, ea
                )
            }
        }

        if (!useIrisPipeline && bloomBuffer is BufferBuilder) {
            bloomBuffer.build()?.let { BufferUploader.drawWithShader(it) }
        }

        // PASS 1C: GPU CORE SHADER VOLUMETRIC CONCERT BEAMS (or Iris Fresnel-softened volumetric beams via RenderType.eyes)
        val beamShader = concertBeamShader
        if (!isDaylightLaserMode && beamPower > 0.01f && (useIrisPipeline || beamShader != null)) {
            if (!useIrisPipeline && beamShader != null) {
                RenderSystem.setShader { beamShader }
            }
            val shaderBuffer: VertexConsumer = if (useIrisPipeline) {
                irisAddBuffer
            } else {
                tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL)
            }
            val sides = if (usePhotonPipeline) 16 else 12
            val irisSlices = if (usePhotonPipeline) 10 else 6

            for (sIdx in 0 until numSpotlights) {
                val px = lensX[sIdx]
                val py = lensY[sIdx]
                val pz = lensZ[sIdx]
                val baseTx = beamTipX[sIdx]
                val baseTy = beamTipY[sIdx]
                val baseTz = beamTipZ[sIdx]

                val sr = smoothSpotR[sIdx]
                val sg = smoothSpotG[sIdx]
                val sb = smoothSpotB[sIdx]

                val flickerMul = spotIntensity[sIdx]

                val rodCount = when (lightsRodMode) {
                    "1" -> 1
                    "3" -> 3
                    else -> when (sIdx % 3) { 0 -> 3; 1 -> 2; else -> 1 }
                }

                for (rodIdx in 0 until rodCount) {
                    val fanSpread = if (rodCount == 1) 0.0f else (rodIdx - (rodCount - 1) * 0.5f) * 8.5f
                    val tx = baseTx + cos(sIdx * 0.9f + (baseTheta * 2.0).toFloat()) * fanSpread
                    val ty = baseTy
                    val tz = baseTz + sin(sIdx * 0.9f + (baseTheta * 2.0).toFloat()) * fanSpread

                    val dx = tx - px; val dy = ty - py; val dz = tz - pz
                    val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.01f)
                    val ax = dx / len; val ay = dy / len; val az = dz / len

                    var ux = -az; var uy = 0.0f; var uz = ax
                    val uLen = sqrt(ux * ux + uy * uy + uz * uz)
                    if (uLen < 0.001f) { ux = 1.0f; uy = 0.0f; uz = 0.0f } else { ux /= uLen; uy /= uLen; uz /= uLen }
                    val vx = ay * uz - az * uy
                    val vy = az * ux - ax * uz
                    val vz = ax * uy - ay * ux

                    val scaleMult = (if (rodCount == 1) 1.15f else 0.72f) * spotWidthMult[sIdx]
                    val innerBaseR = 0.28f * scaleMult
                    val innerTopR = 2.30f * scaleMult
                    val innerA = ((0.64f + pulse * 0.14f) * beamPower * flickerMul).coerceIn(0f, 0.92f)

                    val outerBaseR = 0.58f * scaleMult
                    val outerTopR = 5.60f * scaleMult
                    val outerA = ((0.30f + pulse * 0.08f) * beamPower * flickerMul).coerceIn(0f, 0.52f)

                    if (useIrisPipeline) {
                        for (slice in 0 until irisSlices) {
                            val h0 = slice.toFloat() / irisSlices
                            val h1 = (slice + 1).toFloat() / irisSlices
                            val baseFade0 = smoothStep((h0 / 0.05f).toDouble()).toFloat()
                            val baseFade1 = smoothStep((h1 / 0.05f).toDouble()).toFloat()
                            val topFade0 = Math.pow((1.0f - h0).toDouble(), 1.45).toFloat()
                            val topFade1 = Math.pow((1.0f - h1).toDouble(), 1.45).toFloat()

                            val cx0 = lerp(px, tx, h0); val cy0 = lerp(py, ty, h0); val cz0 = lerp(pz, tz, h0)
                            val cx1 = lerp(px, tx, h1); val cy1 = lerp(py, ty, h1); val cz1 = lerp(pz, tz, h1)

                            val inR0 = lerp(innerBaseR, innerTopR, h0)
                            val inR1 = lerp(innerBaseR, innerTopR, h1)
                            val outR0 = lerp(outerBaseR, outerTopR, h0)
                            val outR1 = lerp(outerBaseR, outerTopR, h1)

                            val hot0 = exp(-h0 * 9.0f)
                            val hot1 = exp(-h1 * 9.0f)

                            for (k in 0 until sides) {
                                val a0 = (k.toFloat() / sides) * twoPi
                                val a1 = ((k + 1).toFloat() / sides) * twoPi
                                val c0 = cos(a0); val s0 = sin(a0)
                                val c1 = cos(a1); val s1 = sin(a1)

                                val n0x = ux * c0 + vx * s0; val n0y = uy * c0 + vy * s0; val n0z = uz * c0 + vz * s0
                                val n1x = ux * c1 + vx * s1; val n1y = uy * c1 + vy * s1; val n1z = uz * c1 + vz * s1

                                fun beamVertex(vpx: Float, vpy: Float, vpz: Float, nx: Float, ny: Float, nz: Float, baseA: Float, bFade: Float, tFade: Float, hot: Float): FloatArray {
                                    val dist = sqrt(vpx * vpx + vpy * vpy + vpz * vpz).coerceAtLeast(0.001f)
                                    val facing = abs(nx * (-vpx / dist) + ny * (-vpy / dist) + nz * (-vpz / dist)).coerceIn(0f, 1f)
                                    val edgeSoft = Math.pow(facing.toDouble(), 1.75).toFloat()
                                    val whiteMix = (hot * facing * 0.65f).coerceIn(0f, 0.75f)
                                    val vr = lerp(sr, 1.0f, whiteMix)
                                    val vg = lerp(sg, 0.98f, whiteMix)
                                    val vb = lerp(sb, 1.0f, whiteMix)
                                    val va = (baseA * bFade * tFade * edgeSoft).coerceIn(0f, 0.72f)
                                    return floatArrayOf(vr, vg, vb, va)
                                }

                                val iv00x = cx0 + n0x * inR0; val iv00y = cy0 + n0y * inR0; val iv00z = cz0 + n0z * inR0
                                val iv10x = cx0 + n1x * inR0; val iv10y = cy0 + n1y * inR0; val iv10z = cz0 + n1z * inR0
                                val iv11x = cx1 + n1x * inR1; val iv11y = cy1 + n1y * inR1; val iv11z = cz1 + n1z * inR1
                                val iv01x = cx1 + n0x * inR1; val iv01y = cy1 + n0y * inR1; val iv01z = cz1 + n0z * inR1

                                val ic00 = beamVertex(iv00x, iv00y, iv00z, n0x, n0y, n0z, innerA * 0.42f, baseFade0, topFade0, hot0)
                                val ic10 = beamVertex(iv10x, iv10y, iv10z, n1x, n1y, n1z, innerA * 0.42f, baseFade0, topFade0, hot0)
                                val ic11 = beamVertex(iv11x, iv11y, iv11z, n1x, n1y, n1z, innerA * 0.42f, baseFade1, topFade1, hot1)
                                val ic01 = beamVertex(iv01x, iv01y, iv01z, n0x, n0y, n0z, innerA * 0.42f, baseFade1, topFade1, hot1)

                                emitGlowQuad(
                                    shaderBuffer, matrix,
                                    iv00x, iv00y, iv00z, ic00[0], ic00[1], ic00[2], ic00[3],
                                    iv10x, iv10y, iv10z, ic10[0], ic10[1], ic10[2], ic10[3],
                                    iv11x, iv11y, iv11z, ic11[0], ic11[1], ic11[2], ic11[3],
                                    iv01x, iv01y, iv01z, ic01[0], ic01[1], ic01[2], ic01[3]
                                )

                                val ov00x = cx0 + n0x * outR0; val ov00y = cy0 + n0y * outR0; val ov00z = cz0 + n0z * outR0
                                val ov10x = cx0 + n1x * outR0; val ov10y = cy0 + n1y * outR0; val ov10z = cz0 + n1z * outR0
                                val ov11x = cx1 + n1x * outR1; val ov11y = cy1 + n1y * outR1; val ov11z = cz1 + n1z * outR1
                                val ov01x = cx1 + n0x * outR1; val ov01y = cy1 + n0y * outR1; val ov01z = cz1 + n0z * outR1

                                val oc00 = beamVertex(ov00x, ov00y, ov00z, n0x, n0y, n0z, outerA * 0.30f, baseFade0, topFade0, 0f)
                                val oc10 = beamVertex(ov10x, ov10y, ov10z, n1x, n1y, n1z, outerA * 0.30f, baseFade0, topFade0, 0f)
                                val oc11 = beamVertex(ov11x, ov11y, ov11z, n1x, n1y, n1z, outerA * 0.30f, baseFade1, topFade1, 0f)
                                val oc01 = beamVertex(ov01x, ov01y, ov01z, n0x, n0y, n0z, outerA * 0.30f, baseFade1, topFade1, 0f)

                                emitGlowQuad(
                                    shaderBuffer, matrix,
                                    ov00x, ov00y, ov00z, oc00[0], oc00[1], oc00[2], oc00[3],
                                    ov10x, ov10y, ov10z, oc10[0], oc10[1], oc10[2], oc10[3],
                                    ov11x, ov11y, ov11z, oc11[0], oc11[1], oc11[2], oc11[3],
                                    ov01x, ov01y, ov01z, oc01[0], oc01[1], oc01[2], oc01[3]
                                )
                            }
                        }
                    } else {
                        for (k in 0 until sides) {
                            val u0 = k.toFloat() / sides
                            val u1 = (k + 1).toFloat() / sides
                            val a0 = u0 * twoPi
                            val a1 = u1 * twoPi
                            val c0 = cos(a0); val s0 = sin(a0)
                            val c1 = cos(a1); val s1 = sin(a1)

                            val n0x = ux * c0 + vx * s0; val n0y = uy * c0 + vy * s0; val n0z = uz * c0 + vz * s0
                            val n1x = ux * c1 + vx * s1; val n1y = uy * c1 + vy * s1; val n1z = uz * c1 + vz * s1

                            normalMatrix.transform(normalTmp.set(n0x, n0y, n0z)).normalize()
                            val vn0x = normalTmp.x; val vn0y = normalTmp.y; val vn0z = normalTmp.z
                            normalMatrix.transform(normalTmp.set(n1x, n1y, n1z)).normalize()
                            val vn1x = normalTmp.x; val vn1y = normalTmp.y; val vn1z = normalTmp.z

                            // Inner volumetric beam
                            shaderBuffer.addVertex(matrix, px + n0x * innerBaseR, py + n0y * innerBaseR, pz + n0z * innerBaseR).setUv(u0, 0.0f).setColor(sr, sg, sb, innerA).setNormal(vn0x, vn0y, vn0z)
                            shaderBuffer.addVertex(matrix, px + n1x * innerBaseR, py + n1y * innerBaseR, pz + n1z * innerBaseR).setUv(u1, 0.0f).setColor(sr, sg, sb, innerA).setNormal(vn1x, vn1y, vn1z)
                            shaderBuffer.addVertex(matrix, tx + n1x * innerTopR, ty + n1y * innerTopR, tz + n1z * innerTopR).setUv(u1, 1.0f).setColor(sr, sg, sb, innerA).setNormal(vn1x, vn1y, vn1z)
                            shaderBuffer.addVertex(matrix, tx + n0x * innerTopR, ty + n0y * innerTopR, tz + n0z * innerTopR).setUv(u0, 1.0f).setColor(sr, sg, sb, innerA).setNormal(vn0x, vn0y, vn0z)

                            // Outer atmospheric haze halo
                            shaderBuffer.addVertex(matrix, px + n0x * outerBaseR, py + n0y * outerBaseR, pz + n0z * outerBaseR).setUv(u0, 0.0f).setColor(sr, sg, sb, outerA).setNormal(vn0x, vn0y, vn0z)
                            shaderBuffer.addVertex(matrix, px + n1x * outerBaseR, py + n1y * outerBaseR, pz + n1z * outerBaseR).setUv(u1, 0.0f).setColor(sr, sg, sb, outerA).setNormal(vn1x, vn1y, vn1z)
                            shaderBuffer.addVertex(matrix, tx + n1x * outerTopR, ty + n1y * outerTopR, tz + n1z * outerTopR).setUv(u1, 1.0f).setColor(sr, sg, sb, outerA).setNormal(vn1x, vn1y, vn1z)
                            shaderBuffer.addVertex(matrix, tx + n0x * outerTopR, ty + n0y * outerTopR, tz + n0z * outerTopR).setUv(u0, 1.0f).setColor(sr, sg, sb, outerA).setNormal(vn0x, vn0y, vn0z)
                        }
                    }
                }
            }
            if (!useIrisPipeline && shaderBuffer is BufferBuilder) {
                shaderBuffer.build()?.let { BufferUploader.drawWithShader(it) }
                RenderSystem.setShader { GameRenderer.getPositionColorShader() }
            }
        }
        // PASS 1D: GPU CORE SHADER HOLOGRAPHIC HEX-SHIELD DOME (Exterior 1 - skipped when Iris active; fallback in PASS 1 handles it)
        val domeShader = domeEnergyShader
        if (!useIrisPipeline && domeShader != null) {
            RenderSystem.setShader { domeShader }
            val domeBuffer = tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL)
            for (l in 0 until DOME_LAT_STEPS) {
                val t0 = l.toFloat() / DOME_LAT_STEPS
                val t1 = (l + 1).toFloat() / DOME_LAT_STEPS
                val phi0 = t0 * maxPhi
                val phi1 = t1 * maxPhi
                val rad0 = cos(phi0); val rad1 = cos(phi1)
                val h0 = sin(phi0) / sinMaxPhi; val h1 = sin(phi1) / sinMaxPhi
                val ny0 = sin(phi0); val ny1 = sin(phi1)

                val baseA0 = (0.14f + (1f - h0) * 0.14f + pulse * 0.05f).coerceIn(0f, 0.42f)
                val baseA1 = (0.14f + (1f - h1) * 0.14f + pulse * 0.05f).coerceIn(0f, 0.42f)

                for (i in 0 until RING_SEGMENTS) {
                    val next = (i + 1) % RING_SEGMENTS
                    val u0 = i.toFloat() / RING_SEGMENTS
                    val u1 = (i + 1).toFloat() / RING_SEGMENTS
                    val b0 = ringSideBlend[i]; val b1 = ringSideBlend[next]
                    val r0 = stadiumR(b0); val g0 = stadiumG(b0); val bl0 = stadiumB(b0)
                    val r1 = stadiumR(b1); val g1 = stadiumG(b1); val bl1 = stadiumB(b1)

                    val x00 = cx + ringCos[i] * rx * rad0; val z00 = cz + ringSin[i] * rz * rad0; val y00 = lerp(ringGroundY[i] + 0.06f, domeApexY, h0)
                    val x10 = cx + ringCos[next] * rx * rad0; val z10 = cz + ringSin[next] * rz * rad0; val y10 = lerp(ringGroundY[next] + 0.06f, domeApexY, h0)
                    val x11 = cx + ringCos[next] * rx * rad1; val z11 = cz + ringSin[next] * rz * rad1; val y11 = lerp(ringGroundY[next] + 0.06f, domeApexY, h1)
                    val x01 = cx + ringCos[i] * rx * rad1; val z01 = cz + ringSin[i] * rz * rad1; val y01 = lerp(ringGroundY[i] + 0.06f, domeApexY, h1)

                    normalMatrix.transform(normalTmp.set(ringCos[i] * rad0, ny0, ringSin[i] * rad0)).normalize()
                    val vn00x = normalTmp.x; val vn00y = normalTmp.y; val vn00z = normalTmp.z
                    normalMatrix.transform(normalTmp.set(ringCos[next] * rad0, ny0, ringSin[next] * rad0)).normalize()
                    val vn10x = normalTmp.x; val vn10y = normalTmp.y; val vn10z = normalTmp.z
                    normalMatrix.transform(normalTmp.set(ringCos[next] * rad1, ny1, ringSin[next] * rad1)).normalize()
                    val vn11x = normalTmp.x; val vn11y = normalTmp.y; val vn11z = normalTmp.z
                    normalMatrix.transform(normalTmp.set(ringCos[i] * rad1, ny1, ringSin[i] * rad1)).normalize()
                    val vn01x = normalTmp.x; val vn01y = normalTmp.y; val vn01z = normalTmp.z

                    domeBuffer.addVertex(matrix, x00, y00, z00).setUv(u0, h0).setColor(r0, g0, bl0, baseA0).setNormal(vn00x, vn00y, vn00z)
                    domeBuffer.addVertex(matrix, x10, y10, z10).setUv(u1, h0).setColor(r1, g1, bl1, baseA0).setNormal(vn10x, vn10y, vn10z)
                    domeBuffer.addVertex(matrix, x11, y11, z11).setUv(u1, h1).setColor(r1, g1, bl1, baseA1).setNormal(vn11x, vn11y, vn11z)
                    domeBuffer.addVertex(matrix, x01, y01, z01).setUv(u0, h1).setColor(r0, g0, bl0, baseA1).setNormal(vn01x, vn01y, vn01z)
                }
            }
            domeBuffer.build()?.let { BufferUploader.drawWithShader(it) }
            RenderSystem.setShader { GameRenderer.getPositionColorShader() }
        }
        RenderSystem.defaultBlendFunc()

        // =========================================================================
        // PASS 2: NEON STRUCTURAL LINES (Ring Borders, Pokeball Trim, Meridian Ribs,
        //         Latitude Energy Rings, Corner Floodlight Pylons)
        // =========================================================================
        RenderSystem.enableDepthTest()
        RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL)
        RenderSystem.depthMask(false)
        RenderSystem.lineWidth(4.5f)
        val lineBuffer: VertexConsumer = if (useIrisPipeline) {
            irisAddBuffer
        } else {
            tessellator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR)
        }
        val lineBaseAlpha = 0.84f + pulse * 0.14f

        // 2A. Inner & Outer Battle Ring Neon Borders
        for (i in 0 until RING_SEGMENTS) {
            val next = (i + 1) % RING_SEGMENTS
            val angleMid = ((i + 0.5f) / RING_SEGMENTS) * twoPi
            val dRed = abs(angleMid - runnerRedAngle)
            val redGlow = exp(-min(dRed, twoPi - dRed) * 3.2f)
            val dBlue = abs(angleMid - runnerBlueAngle)
            val blueGlow = exp(-min(dBlue, twoPi - dBlue) * 3.2f)
            val glow = maxOf(redGlow, blueGlow)

            val blend = ringSideBlend[i]
            val lr = (stadiumR(blend) + glow * 0.35f).coerceIn(0f, 1f)
            val lg = (stadiumG(blend) + glow * 0.30f).coerceIn(0f, 1f)
            val lb = (stadiumB(blend) + glow * 0.35f).coerceIn(0f, 1f)
            val la = (lineBaseAlpha + glow * 0.16f).coerceIn(0f, 1f)

            val ox0 = cx + ringCos[i] * rx
            val oz0 = cz + ringSin[i] * rz
            val ox1 = cx + ringCos[next] * rx
            val oz1 = cz + ringSin[next] * rz
            val y0 = ringGroundY[i] + 0.05f
            val y1 = ringGroundY[next] + 0.05f

            // Outer ring ground + curb top
            emitLine(lineBuffer, matrix, ox0, y0, oz0, ox1, y1, oz1, lr, lg, lb, la)
            emitLine(lineBuffer, matrix, ox0, y0 + curbHeight, oz0, ox1, y1 + curbHeight, oz1, lr, lg, lb, la * 0.65f)

            // Inner track border
            val ix0 = cx + ringCos[i] * rx * innerRingScale
            val iz0 = cz + ringSin[i] * rz * innerRingScale
            val ix1 = cx + ringCos[next] * rx * innerRingScale
            val iz1 = cz + ringSin[next] * rz * innerRingScale
            emitLine(lineBuffer, matrix, ix0, y0, iz0, ix1, y1, iz1, lr, lg, lb, la * 0.55f)
        }

        // 2B. Pokeball Emblem Crisp Outlines
        val emblemLineY = emblemY + 0.008f
        for (i in 0 until EMBLEM_SEGMENTS) {
            val a0 = (i.toFloat() / EMBLEM_SEGMENTS) * twoPi
            val a1 = ((i + 1).toFloat() / EMBLEM_SEGMENTS) * twoPi
            val c0 = cos(a0)
            val s0 = sin(a0)
            val c1 = cos(a1)
            val s1 = sin(a1)

            // Outer Pokeball circle
            emitLine(
                lineBuffer, matrix,
                cx + c0 * emblemR, emblemLineY, cz + s0 * emblemR,
                cx + c1 * emblemR, emblemLineY, cz + s1 * emblemR,
                0.96f, 0.98f, 1.0f, 0.88f
            )
            // Center button circle
            emitLine(
                lineBuffer, matrix,
                cx + c0 * buttonOuterR, emblemLineY, cz + s0 * buttonOuterR,
                cx + c1 * buttonOuterR, emblemLineY, cz + s1 * buttonOuterR,
                1.0f, 0.98f, 0.95f, 0.95f
            )
        }

        // 2C. 16 Ribbed Meridian Arcs curving from the Battle Ring up to the Zenith Oculus
        val ribStep = 4 // 64 / 4 = 16 meridian ribs
        for (i in 0 until RING_SEGMENTS step ribStep) {
            val blend = ringSideBlend[i]
            val baseR = stadiumR(blend)
            val baseG = stadiumG(blend)
            val baseB = stadiumB(blend)
            val c = ringCos[i]
            val s = ringSin[i]
            val gy = ringGroundY[i] + 0.06f

            for (l in 0 until DOME_LAT_STEPS) {
                val phi0 = (l.toFloat() / DOME_LAT_STEPS) * maxPhi
                val phi1 = ((l + 1).toFloat() / DOME_LAT_STEPS) * maxPhi
                val rad0 = cos(phi0)
                val rad1 = cos(phi1)
                val h0 = sin(phi0) / sinMaxPhi
                val h1 = sin(phi1) / sinMaxPhi

                val w0 = exp(-abs(h0 - domeWavePhase) * 6.5f) * domeWaveEnvelope
                val w1 = exp(-abs(h1 - domeWavePhase) * 6.5f) * domeWaveEnvelope

                emitLineGrad(
                    lineBuffer, matrix,
                    cx + c * rx * rad0, lerp(gy, domeApexY, h0), cz + s * rz * rad0,
                    (baseR + w0 * 0.40f).coerceIn(0f, 1f),
                    (baseG + w0 * 0.40f).coerceIn(0f, 1f),
                    (baseB + w0 * 0.35f).coerceIn(0f, 1f),
                    (0.45f + w0 * 0.50f).coerceIn(0f, 0.96f),
                    cx + c * rx * rad1, lerp(gy, domeApexY, h1), cz + s * rz * rad1,
                    (baseR + w1 * 0.40f).coerceIn(0f, 1f),
                    (baseG + w1 * 0.40f).coerceIn(0f, 1f),
                    (baseB + w1 * 0.35f).coerceIn(0f, 1f),
                    (0.45f + w1 * 0.50f).coerceIn(0f, 0.96f)
                )
            }
        }

        // 2D. Horizontal Latitude Energy Rings (including bright Zenith Oculus Ring)
        for (l in 1..DOME_LAT_STEPS) {
            val phi = (l.toFloat() / DOME_LAT_STEPS) * maxPhi
            val rad = cos(phi)
            val h = sin(phi) / sinMaxPhi
            val isZenith = (l == DOME_LAT_STEPS)
            val w = exp(-abs(h - domeWavePhase) * 6.0f) * domeWaveEnvelope
            val latAlpha = if (isZenith) {
                (0.76f + pulse * 0.18f + w * 0.15f).coerceIn(0f, 1f)
            } else {
                (0.32f + w * 0.52f).coerceIn(0f, 0.92f)
            }

            for (i in 0 until RING_SEGMENTS) {
                val next = (i + 1) % RING_SEGMENTS
                val blend = ringSideBlend[i]
                val lr = (stadiumR(blend) + (if (isZenith) 0.25f else 0f) + w * 0.35f).coerceIn(0f, 1f)
                val lg = (stadiumG(blend) + (if (isZenith) 0.25f else 0f) + w * 0.35f).coerceIn(0f, 1f)
                val lb = (stadiumB(blend) + (if (isZenith) 0.25f else 0f) + w * 0.30f).coerceIn(0f, 1f)

                val x0 = cx + ringCos[i] * rx * rad
                val z0 = cz + ringSin[i] * rz * rad
                val y0 = lerp(ringGroundY[i] + 0.06f, domeApexY, h)
                val x1 = cx + ringCos[next] * rx * rad
                val z1 = cz + ringSin[next] * rz * rad
                val y1 = lerp(ringGroundY[next] + 0.06f, domeApexY, h)

                emitLine(lineBuffer, matrix, x0, y0, z0, x1, y1, z1, lr, lg, lb, latAlpha)
            }
        }

        // 2E. Wireframe Stadium Spotlight Projectors underneath each light halo (zero lines inside the halo!)
        for (sIdx in 0 until numSpotlights) {
            val px = spotX[sIdx]
            val py = spotY[sIdx]
            val pz = spotZ[sIdx]
            val crimson = spotIsCrimson[sIdx]
            val sr = if (crimson) 1.0f else 0.16f
            val sg = if (crimson) 0.24f else 0.76f
            val sb = if (crimson) 0.36f else 1.0f

            // Aim vector from projector pivot toward the sweeping beam tip
            val pivotY = py + 0.62f
            val adx = beamTipX[sIdx] - px
            val ady = beamTipY[sIdx] - pivotY
            val adz = beamTipZ[sIdx] - pz
            val alen = sqrt(adx * adx + ady * ady + adz * adz).coerceAtLeast(0.01f)
            val ux = adx / alen
            val uy = ady / alen
            val uz = adz / alen

            // Orthonormal basis perpendicular to the aim direction
            val sideLen = sqrt(ux * ux + uz * uz).coerceAtLeast(0.001f)
            val rxAxis = -uz / sideLen
            val rzAxis = ux / sideLen
            // v = cross(u, r)
            val vx = uy * rzAxis
            val vy = uz * rxAxis - ux * rzAxis
            val vz = -uy * rxAxis

            // 1) Tripod pedestal + U-bracket yoke on the ground
            val padR = 0.44f
            for (k in 0 until 8) {
                val a0 = (k / 8.0f) * twoPi
                val a1 = ((k + 1) / 8.0f) * twoPi
                emitLine(
                    lineBuffer, matrix,
                    px + cos(a0) * padR, py + 0.02f, pz + sin(a0) * padR,
                    px + cos(a1) * padR, py + 0.02f, pz + sin(a1) * padR,
                    sr, sg, sb, 0.85f
                )
            }
            // Tripod legs to pivot
            for (k in 0 until 3) {
                val la = (k / 3.0f) * twoPi + 0.4f
                emitLine(
                    lineBuffer, matrix,
                    px + cos(la) * (padR * 0.85f), py + 0.02f, pz + sin(la) * (padR * 0.85f),
                    px, pivotY - 0.12f, pz,
                    0.75f, 0.82f, 0.90f, 0.85f
                )
            }
            // Yoke fork arms
            val yokeW = 0.28f
            emitLine(lineBuffer, matrix, px - rxAxis * yokeW, pivotY - 0.12f, pz - rzAxis * yokeW, px + rxAxis * yokeW, pivotY - 0.12f, pz + rzAxis * yokeW, sr, sg, sb, 0.92f)
            emitLine(lineBuffer, matrix, px - rxAxis * yokeW, pivotY - 0.12f, pz - rzAxis * yokeW, px - rxAxis * yokeW, pivotY, pz - rzAxis * yokeW, sr, sg, sb, 0.92f)
            emitLine(lineBuffer, matrix, px + rxAxis * yokeW, pivotY - 0.12f, pz + rzAxis * yokeW, px + rxAxis * yokeW, pivotY, pz + rzAxis * yokeW, sr, sg, sb, 0.92f)

            // 2) Aimed octagonal spotlight barrel (back cap -> flared front lens hood)
            val backOff = -0.22f
            val frontOff = 0.34f
            val backR = 0.20f
            val frontR = 0.30f
            val bx = px + ux * backOff
            val by = pivotY + uy * backOff
            val bz = pz + uz * backOff
            val fx = px + ux * frontOff
            val fy = pivotY + uy * frontOff
            val fz = pz + uz * frontOff

            for (k in 0 until 8) {
                val a0 = (k / 8.0f) * twoPi
                val a1 = ((k + 1) / 8.0f) * twoPi
                val c0 = cos(a0)
                val s0 = sin(a0)
                val c1 = cos(a1)
                val s1 = sin(a1)

                val bc0x = bx + (rxAxis * c0 + vx * s0) * backR
                val bc0y = by + (vy * s0) * backR
                val bc0z = bz + (rzAxis * c0 + vz * s0) * backR
                val bc1x = bx + (rxAxis * c1 + vx * s1) * backR
                val bc1y = by + (vy * s1) * backR
                val bc1z = bz + (rzAxis * c1 + vz * s1) * backR

                val fc0x = fx + (rxAxis * c0 + vx * s0) * frontR
                val fc0y = fy + (vy * s0) * frontR
                val fc0z = fz + (rzAxis * c0 + vz * s0) * frontR
                val fc1x = fx + (rxAxis * c1 + vx * s1) * frontR
                val fc1y = fy + (vy * s1) * frontR
                val fc1z = fz + (rzAxis * c1 + vz * s1) * frontR

                emitLine(lineBuffer, matrix, bc0x, bc0y, bc0z, bc1x, bc1y, bc1z, sr, sg, sb, 0.85f)
                emitLine(lineBuffer, matrix, fc0x, fc0y, fc0z, fc1x, fc1y, fc1z, 1.0f, 0.98f, 0.95f, 0.95f)
                emitLine(lineBuffer, matrix, bc0x, bc0y, bc0z, fc0x, fc0y, fc0z, sr, sg, sb, 0.88f)
            }
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
            val transType = irisTranslucentType()
            val addType = irisAdditiveType()
            consumers.endBatch(transType)
            if (addType !== transType) consumers.endBatch(addType)
        }

        // PASS 3: Depth Seal en Vanilla/Iris/Photon cuando el exterior negro esta activo para que la niebla/nubes/mundo no sobrescriban el negro
        if (insideDarkRoomFactor >= 0.80f) {
            RenderSystem.colorMask(false, false, false, false)
            RenderSystem.depthMask(true)
            RenderSystem.enableDepthTest()
            RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL)
            RenderSystem.disableCull()
            RenderSystem.setShader(GameRenderer::getPositionColorShader)
            val sealBuf = tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
            emitBlackShell { x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3 ->
                sealBuf.addVertex(matrix, x0, y0, z0).setColor(0f, 0f, 0f, 1f)
                sealBuf.addVertex(matrix, x1, y1, z1).setColor(0f, 0f, 0f, 1f)
                sealBuf.addVertex(matrix, x2, y2, z2).setColor(0f, 0f, 0f, 1f)
                sealBuf.addVertex(matrix, x3, y3, z3).setColor(0f, 0f, 0f, 1f)
            }
            sealBuf.build()?.let { BufferUploader.drawWithShader(it) }
            RenderSystem.colorMask(true, true, true, true)
        }

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
        RenderSystem.disablePolygonOffset()
        RenderSystem.lineWidth(1.0f)
        RenderSystem.depthMask(true)
        RenderSystem.enableCull()
        RenderSystem.disableBlend()

        activeUseIris = false
        activeUsePhoton = false
        activeIrisAlpha = 1.0f
        matrices.popPose()
    }
}
