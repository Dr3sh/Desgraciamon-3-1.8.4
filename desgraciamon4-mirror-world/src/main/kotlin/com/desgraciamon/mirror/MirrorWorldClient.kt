package com.desgraciamon.mirror

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.desgraciamon.mirror.mixin.GameRendererAccessor
import com.desgraciamon.mirror.mixin.PostChainAccessor
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import org.joml.Matrix4f
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sin

/**
 * Cliente del Mundo Espejo:
 * - Pre-carga el shader de post-procesado (`shaders/post/memory.json`) con graduación
 *   fílmica de recuerdo y aura grisácea desaturada.
 * - Transición Swirl pre-cargada y garantizada mediante Overlay de HUD para que nunca se pierda ni parpadee.
 * - Rastro etéreo de recuerdo en forma de cintas luminosas y motas flotantes detrás de los Pokémon.
 * - Firmamento nublado procedural con nubes densas y sol velado (CoreShader).
 * - Viñeta plateada suave de memoria.
 */
class MirrorWorldClient : ClientModInitializer {
    companion object {
        private val SHADER_ID: ResourceLocation = ResourceLocation.fromNamespaceAndPath(MirrorRules.MOD_ID, "shaders/post/memory.json")
        private val RECUERDOS_SHADER_ID: ResourceLocation = ResourceLocation.fromNamespaceAndPath(MirrorRules.MOD_ID, "shaders/post/recuerdos.json")
        private val VIGNETTE_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(MirrorRules.MOD_ID, "textures/misc/memory_vignette.png")

        private var shaderLoadedByUs = false

        // Control de transición con máquina de estados sincronizada contra el cambio de nivel
        enum class TransitionPhase {
            IDLE,
            INBOUND,  // Fade in & Swirl (0.0 -> 0.5)
            HOLDING,  // Pico total (0.5): 100% velo/destello mientras el nivel se intercambia
            OUTBOUND  // Fade out & Swirl resolviéndose en el nuevo mundo (0.5 -> 1.0)
        }

        private var currentPhase = TransitionPhase.IDLE
        private var phaseStartMillis = 0L
        private var originDimension: net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>? = null
        private var isTransitionEntering = true

        private var inboundDurationMs = 900f
        private var holdDurationMs = 250f
        private var outboundDurationMs = 900f
        private var currentProgress = 0.0f
        private val clientStartMillis = System.currentTimeMillis()

        // Rastro etéreo de memoria dejado por los Pokémon
        data class MemoryTrailPoint(val pos: Vec3, val timeMs: Long, val width: Float)
        private val pokemonTrails = ConcurrentHashMap<UUID, MutableList<MemoryTrailPoint>>()
        private val entityOrigins = ConcurrentHashMap<Int, String>()

        fun triggerTransition(fadeIn: Int, hold: Int, fadeOut: Int) {
            val client = Minecraft.getInstance()
            val currentDim = client.level?.dimension()
            originDimension = currentDim
            isTransitionEntering = currentDim != MirrorRules.DIMENSION_KEY && currentDim != MirrorRules.RECUERDOS_DIMENSION_KEY

            inboundDurationMs = fadeIn.coerceIn(500, 1400).toFloat()
            holdDurationMs = hold.coerceIn(150, 600).toFloat()
            outboundDurationMs = fadeOut.coerceIn(500, 1400).toFloat()

            currentPhase = TransitionPhase.INBOUND
            phaseStartMillis = System.currentTimeMillis()
            currentProgress = 0.0f
        }

        fun triggerOutboundDissolve(durationMs: Int = 900) {
            outboundDurationMs = durationMs.coerceIn(500, 1400).toFloat()
            currentPhase = TransitionPhase.OUTBOUND
            phaseStartMillis = System.currentTimeMillis()
            currentProgress = 0.5f
        }

        fun getTransitionProgress(): Float = currentProgress

        fun isTransitionActive(): Boolean = currentPhase != TransitionPhase.IDLE

        private fun computeFadeIntensity(p: Float): Float {
            if (p <= 0.0f || p >= 1.0f) return 0.0f
            return if (p < 0.5f) {
                val t = (p / 0.5f).coerceIn(0.0f, 1.0f)
                t * t * (3.0f - 2.0f * t)
            } else {
                val t = ((1.0f - p) / 0.5f).coerceIn(0.0f, 1.0f)
                t * t * (3.0f - 2.0f * t)
            }
        }

        /**
         * Actualiza la máquina de estados de transición de forma determinista y consistente.
         */
        fun updateTransitionProgress(): Float {
            if (currentPhase == TransitionPhase.IDLE) {
                currentProgress = 0.0f
                return 0.0f
            }

            val now = System.currentTimeMillis()
            val elapsed = now - phaseStartMillis
            val client = Minecraft.getInstance()
            val currentLevel = client.level

            when (currentPhase) {
                TransitionPhase.INBOUND -> {
                    val raw = (elapsed / inboundDurationMs).coerceIn(0.0f, 1.0f)
                    if (raw >= 1.0f) {
                        currentPhase = TransitionPhase.HOLDING
                        phaseStartMillis = now
                        currentProgress = 0.5f
                    } else {
                        currentProgress = raw * 0.5f
                    }
                }
                TransitionPhase.HOLDING -> {
                    val reachedTarget = currentLevel != null && originDimension != null && currentLevel.dimension() != originDimension
                    val loadingScreenActive = client.screen != null && client.screen !is net.minecraft.client.gui.screens.ChatScreen
                    val softReady = !loadingScreenActive && ((reachedTarget && elapsed >= 150L) || elapsed >= holdDurationMs.toLong() + 900L)
                    val hardTimeout = elapsed >= 2200L

                    if (softReady || hardTimeout) {
                        currentPhase = TransitionPhase.OUTBOUND
                        phaseStartMillis = now
                        currentProgress = 0.5f
                    } else {
                        currentProgress = 0.5f
                    }
                }
                TransitionPhase.OUTBOUND -> {
                    val raw = (elapsed / outboundDurationMs).coerceIn(0.0f, 1.0f)
                    if (raw >= 1.0f) {
                        currentPhase = TransitionPhase.IDLE
                        phaseStartMillis = 0L
                        currentProgress = 0.0f
                    } else {
                        currentProgress = 0.5f + raw * 0.5f
                    }
                }
                TransitionPhase.IDLE -> {
                    currentProgress = 0.0f
                }
            }
            return currentProgress
        }

        /**
         * Transición etérea prismática inspirada en el reflejo de un cristal de memoria.
         */
        fun renderTransitionOverlay(guiGraphics: GuiGraphics) {
            val p = getTransitionProgress()
            val intensity = computeFadeIntensity(p)
            if (intensity <= 0.001f) return

            val width = guiGraphics.guiWidth()
            val height = guiGraphics.guiHeight()

            // 1. Velo de luz pura perlada / lavanda suave
            val veilAlpha = (intensity * 255).toInt().coerceIn(0, 255)
            if (veilAlpha > 1) {
                val veilColor = (veilAlpha shl 24) or 0xF6F1FA
                guiGraphics.fill(0, 0, width, height, veilColor)
            }

            // 2. Viñeta prismática envolvente en los bordes
            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            RenderSystem.setShaderColor(0.86f, 0.82f, 0.96f, (intensity * 0.65f).coerceIn(0.0f, 1.0f))
            guiGraphics.blit(VIGNETTE_TEXTURE, 0, 0, 0f, 0f, width, height, 256, 256)
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
            RenderSystem.disableBlend()
        }

        /**
         * Renderizado en la capa superior absoluta (TAIL de GameRenderer.render)
         * para garantizar que cubra minimapa, cobblemon, hotbar, vida y pantallas abiertas.
         */
        fun renderTopLevelTransition() {
            if (!isTransitionActive()) return
            val p = getTransitionProgress()
            val intensity = computeFadeIntensity(p)
            if (intensity <= 0.001f) return

            val client = Minecraft.getInstance()
            val window = client.window ?: return
            val width = window.guiScaledWidth.toFloat()
            val height = window.guiScaledHeight.toFloat()

            val prevProj = Matrix4f(RenderSystem.getProjectionMatrix())
            val prevSort = RenderSystem.getVertexSorting()

            val ortho = Matrix4f().setOrtho(0f, width, height, 0f, -1000f, 1000f)
            RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z)

            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            RenderSystem.disableDepthTest()
            RenderSystem.depthMask(false)
            RenderSystem.disableCull()

            val veilAlpha = (intensity * 255).toInt().coerceIn(0, 255)
            if (veilAlpha > 1) {
                val a = veilAlpha / 255.0f
                val r = 0xF6 / 255.0f
                val g = 0xF1 / 255.0f
                val b = 0xFA / 255.0f

                RenderSystem.setShader(GameRenderer::getPositionColorShader)
                val tessellator = Tesselator.getInstance()
                val buf = tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
                val m = Matrix4f()
                buf.addVertex(m, 0f, height, 0f).setColor(r, g, b, a)
                buf.addVertex(m, width, height, 0f).setColor(r, g, b, a)
                buf.addVertex(m, width, 0f, 0f).setColor(r, g, b, a)
                buf.addVertex(m, 0f, 0f, 0f).setColor(r, g, b, a)
                buf.build()?.let { BufferUploader.drawWithShader(it) }
            }

            RenderSystem.enableCull()
            RenderSystem.enableDepthTest()
            RenderSystem.depthMask(true)
            RenderSystem.disableBlend()
            RenderSystem.setProjectionMatrix(prevProj, prevSort)
        }
    }

    override fun onInitializeClient() {
        MirrorPackets.registerCommon()
        MirrorClientSettings.load()

        // 1. Registro del CoreShader para el cielo cubierto procedural de la Zona Espejo y dimension_recuerdos
        CoreShaderRegistrationCallback.EVENT.register { context ->
            context.register(
                ResourceLocation.fromNamespaceAndPath("minecraft", "desgraciamon_mirror_sky"),
                DefaultVertexFormat.POSITION
            ) { shaderInstance ->
                MirrorSkyRenderer.skyShader = shaderInstance
            }
            context.register(
                ResourceLocation.fromNamespaceAndPath("minecraft", "desgraciamon_recuerdos_sky"),
                DefaultVertexFormat.POSITION
            ) { shaderInstance ->
                MirrorSkyRenderer.recuerdosSkyShader = shaderInstance
            }
        }

        // 2. Pre-carga del shader al conectarse para eliminar cualquier tirón
        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            preloadShader(client)
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            currentPhase = TransitionPhase.IDLE
            currentProgress = 0.0f
            entityOrigins.clear()
            pokemonTrails.clear()
        }

        ClientPlayNetworking.registerGlobalReceiver(MirrorOriginsPayload.TYPE) { payload, context ->
            context.client().execute {
                entityOrigins.putAll(payload.origins)
            }
        }

        // 3. Receptor del paquete de transición
        ClientPlayNetworking.registerGlobalReceiver(MirrorFadePayload.TYPE) { payload, context ->
            context.client().execute {
                triggerTransition(payload.fadeInMs, payload.holdMs, payload.fadeOutMs)
                preloadShader(context.client())
            }
        }

        // 4. Verificación inmediata y actualización fluida por frame antes de renderizar
        WorldRenderEvents.START.register { _ ->
            val client = Minecraft.getInstance()
            updateTransitionProgress()
            val insideSpecialDim = MirrorRules.isMirror(client.level) || MirrorRules.isRecuerdos(client.level)
            if (!insideSpecialDim) {
                wasInsideMirror = false
                lastNearbyEntity = null
                nearbyMemoryAlpha = 0.0f
            }
            if (isTransitionActive() || insideSpecialDim) {
                ensureShaderActive(client)
            }
            updateFlashbackShader(client)
        }

        // 5. Renderizado de los rastros de recuerdo de los Pokémon en el mundo 3D
        WorldRenderEvents.AFTER_TRANSLUCENT.register { context ->
            runCatching {
                renderPokemonTrails(context)
            }
        }

        // 6. Tick de cliente: registrar posiciones de Pokémon y generar partículas ambientales celestiales
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            updatePokemonTrails(client)
            spawnCelestialAmbientParticles(client)
        }

        // 7. HUD: Viñeta plateada y título del santuario (La transición se dibuja en la capa superior absoluta)
        HudRenderCallback.EVENT.register { guiGraphics, _ ->
            val client = Minecraft.getInstance()
            val level = client.level
            if ((MirrorRules.isMirror(level) || MirrorRules.isRecuerdos(level)) && !client.options.hideGui) {
                if (MirrorClientSettings.isMotionBlurEnabled() && MirrorRules.isMirror(level)) {
                    renderMemoryVignette(guiGraphics)
                }
                renderMirrorTitleHud(guiGraphics)
                renderMemoryPokemonNearbyHud(guiGraphics)
            }
        }
    }

    private var wasInsideMirror = false
    private var mirrorEnterTimeMs = 0L
    private var nearbyMemoryAlpha = 0.0f
    private var lastNearbyEntity: PokemonEntity? = null
    private var lastTargetLockMs = 0L

    private fun normalizeSeasonOrigin(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return when {
            raw.contains("2") -> "Desgraciamon 2"
            raw.contains("1") -> "Desgraciamon 1"
            else -> raw
        }
    }

    private fun renderMirrorTitleHud(graphics: GuiGraphics) {
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val level = player.level()
        val isInsideMirror = MirrorRules.isMirror(level)
        val isInsideRecuerdos = MirrorRules.isRecuerdos(level)
        val isInside = isInsideMirror || isInsideRecuerdos

        if (!wasInsideMirror && isInside) {
            mirrorEnterTimeMs = System.currentTimeMillis()
            if (currentPhase == TransitionPhase.IDLE) {
                triggerOutboundDissolve(950)
            }
        }
        wasInsideMirror = isInside

        if (mirrorEnterTimeMs == 0L) return
        val elapsed = System.currentTimeMillis() - mirrorEnterTimeMs
        if (elapsed > 3400L) return

        val alpha = when {
            elapsed < 400L -> (elapsed / 400f).coerceIn(0f, 1f)
            elapsed < 2400L -> 1.0f
            else -> ((3400L - elapsed) / 1000f).coerceIn(0f, 1f)
        }
        if (alpha <= 0.01f) return

        val alphaByte = (alpha * 255).toInt().coerceIn(0, 255)
        val font = client.font
        val screenW = graphics.guiWidth()
        val screenH = graphics.guiHeight()
        val centerX = screenW / 2
        val titleY = (screenH * 0.28f).toInt()

        val matrices = graphics.pose()
        matrices.pushPose()
        matrices.translate(centerX.toFloat(), titleY.toFloat(), 0f)
        val popScale = 1.8f + (1f - alpha.coerceAtMost(1f)) * 0.12f
        matrices.scale(popScale, popScale, 1.0f)

        val titleText = if (isInsideRecuerdos) "DIMENSIÓN DE LOS RECUERDOS" else "SANTUARIO DEL RECUERDO"
        val titleW = font.width(titleText)
        val titleColor = (alphaByte shl 24) or 0xFAF5FF
        val shadowColor = (alphaByte shl 24) or 0x2A1B38
        graphics.drawString(font, titleText, -titleW / 2 + 1, 1, shadowColor, false)
        graphics.drawString(font, titleText, -titleW / 2, 0, titleColor, false)
        matrices.popPose()

        val subY = titleY + (font.lineHeight * popScale).toInt() + 4
        val lineW = (180 * alpha).toInt()
        val lineColor = (alphaByte shl 24) or 0xD7BCE8
        graphics.fill(centerX - lineW / 2, subY - 3, centerX + lineW / 2, subY - 2, lineColor)

        val subText = if (isInsideRecuerdos) {
            "El Jardín Marchito pero Intacto"
        } else {
            val plotIndex = PlotManager.plotAt(player.x)
            val theme = TerrainGenerator.themeFor(plotIndex)
            "${theme.displayName}"
        }
        val subW = font.width(subText)
        val subColor = (alphaByte shl 24) or 0xE9D8F6
        graphics.drawString(font, subText, centerX - subW / 2, subY, subColor, true)
    }

    private fun renderMemoryPokemonNearbyHud(graphics: GuiGraphics) {
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val level = client.level ?: return

        val eyePos = player.eyePosition
        val lookVec = player.lookAngle.normalize()
        val rayEnd = eyePos.add(lookVec.scale(6.5))
        val box = player.boundingBox.inflate(6.0)
        val candidates = level.getEntitiesOfClass(PokemonEntity::class.java, box).filter {
            it.isAlive && !it.isRemoved && (MirrorRules.MEMORY_TAG in it.tags || MirrorRules.isRecuerdos(level) || MirrorRules.isMirror(level))
        }

        // Priorizar impacto directo del rayo de mira para evitar ambigüedad cuando hay un Pokémon detrás de otro
        val directHit = candidates.mapNotNull { entity ->
            val hit = entity.boundingBox.inflate(0.22).clip(eyePos, rayEnd)
            if (hit.isPresent) entity to eyePos.distanceToSqr(hit.get()) else null
        }.minByOrNull { it.second }?.first

        val coneTarget = if (directHit == null) {
            candidates.mapNotNull { entity ->
                val dist = player.distanceTo(entity).toDouble()
                if (dist > 5.5) return@mapNotNull null
                val toEntity = entity.position().add(0.0, entity.bbHeight * 0.5, 0.0).subtract(eyePos).normalize()
                val dot = lookVec.dot(toEntity)
                if (dot <= 0.88) return@mapNotNull null
                entity to (dot - dist * 0.025)
            }.maxByOrNull { it.second }?.first
        } else null

        val bestCandidate = directHit ?: coneTarget
        val now = System.currentTimeMillis()
        val currentLocked = lastNearbyEntity?.takeIf { locked ->
            if (!locked.isAlive || locked.isRemoved) return@takeIf false
            val dist = player.distanceTo(locked)
            if (dist > 6.0f) return@takeIf false
            val toLocked = locked.position().add(0.0, locked.bbHeight * 0.5, 0.0).subtract(eyePos).normalize()
            lookVec.dot(toLocked) > 0.84
        }

        val nearest = when {
            bestCandidate == null -> null
            currentLocked == null || bestCandidate.id == currentLocked.id -> {
                if (lastNearbyEntity?.id != bestCandidate.id) lastTargetLockMs = now
                bestCandidate
            }
            directHit != null && directHit.id != currentLocked.id -> {
                lastTargetLockMs = now
                directHit
            }
            now - lastTargetLockMs >= 160L -> {
                lastTargetLockMs = now
                bestCandidate
            }
            else -> currentLocked
        }

        if (nearest != null) {
            lastNearbyEntity = nearest
        } else if (nearbyMemoryAlpha <= 0.02f) {
            lastNearbyEntity = null
        }

        val entity = lastNearbyEntity
        val originServer = normalizeSeasonOrigin(entity?.let { entityOrigins[it.id] })
        val hasValidTarget = nearest != null && originServer != null

        val targetAlpha = if (hasValidTarget) 1.0f else 0.0f
        val step = 0.14f
        nearbyMemoryAlpha = if (nearbyMemoryAlpha < targetAlpha) {
            (nearbyMemoryAlpha + step).coerceAtMost(1.0f)
        } else {
            (nearbyMemoryAlpha - step).coerceAtLeast(0.0f)
        }

        if (nearbyMemoryAlpha <= 0.01f || entity == null || originServer == null) return

        val pokemon = entity.pokemon
        val alphaByte = (nearbyMemoryAlpha * 255).toInt().coerceIn(0, 255)
        if (alphaByte <= 2) return

        val screenW = graphics.guiWidth()
        val screenH = graphics.guiHeight()
        val font = client.font

        val cardW = 190
        val cardH = 52
        // Centrada verticalmente y ubicada horizontalmente a mitad camino entre el centro y el borde derecho (75%)
        val cardX = ((screenW * 0.75f) - (cardW / 2.0f)).toInt().coerceIn(8, (screenW - cardW - 8).coerceAtLeast(8))
        val cardY = (screenH - cardH) / 2

        val bg = (alphaByte.coerceAtMost(220) shl 24) or 0x0D0B14
        val border = (alphaByte shl 24) or 0x8B5CF6
        val glow = (alphaByte.coerceAtMost(90) shl 24) or 0xC084FC

        graphics.pose().pushPose()
        graphics.pose().translate(0f, 0f, 200f)

        graphics.fill(cardX - 1, cardY - 1, cardX + cardW + 1, cardY + cardH + 1, glow)
        graphics.fill(cardX, cardY, cardX + cardW, cardY + cardH, bg)
        graphics.fill(cardX, cardY, cardX + cardW, cardY + 1, border)
        graphics.fill(cardX, cardY + cardH - 1, cardX + cardW, cardY + cardH, border)
        graphics.fill(cardX, cardY, cardX + 1, cardY + cardH, border)
        graphics.fill(cardX + cardW - 1, cardY, cardX + cardW, cardY + cardH, border)

        graphics.fill(cardX + 2, cardY + 2, cardX + 18, cardY + 3, (alphaByte shl 24) or 0xE9D5FF)

        val header = "POKÉMON DEL RECUERDO"
        val headerColor = (alphaByte shl 24) or 0xD8B4FE
        graphics.drawCenteredString(font, header, cardX + cardW / 2, cardY + 6, headerColor)

        val rawName = pokemon.species.name
        val nick = pokemon.nickname?.string
        val dispName = if (!nick.isNullOrBlank()) nick else rawName
        val shinyTag = if (pokemon.shiny) " §e[Shiny]" else ""
        val nameText = "§f$dispName$shinyTag §7(Nv. §b${pokemon.level}§7)"
        graphics.drawCenteredString(font, nameText, cardX + cardW / 2, cardY + 20, (alphaByte shl 24) or 0xFFFFFF)

        val originText = "§7Origen: §b$originServer"
        graphics.drawCenteredString(font, originText, cardX + cardW / 2, cardY + 34, (alphaByte shl 24) or 0xC4B5FD)

        graphics.pose().popPose()
    }

    private fun preloadShader(client: Minecraft) {
        runCatching {
            val renderer = client.gameRenderer
            if (renderer.currentEffect() == null) {
                (renderer as GameRendererAccessor).`desgraciamon4mirror$loadEffect`(SHADER_ID)
                shaderLoadedByUs = true
            }
        }
    }

    private fun ensureShaderActive(client: Minecraft) {
        val level = client.level ?: return
        if (!MirrorRules.isMirror(level) && !MirrorRules.isRecuerdos(level)) return
        val renderer = client.gameRenderer
        val current = renderer.currentEffect()
        val targetShader = if (MirrorRules.isRecuerdos(level)) RECUERDOS_SHADER_ID else SHADER_ID
        val accessor = renderer as? GameRendererAccessor ?: return
        if (current == null) {
            runCatching {
                accessor.`desgraciamon4mirror$loadEffect`(targetShader)
                shaderLoadedByUs = true
            }
        }
        accessor.`desgraciamon4mirror$setEffectActive`(true)
    }

    /**
     * Rastrea las posiciones recientes de los Pokémon en el Mundo Espejo para dibujar su estela de recuerdos.
     */
    private fun updatePokemonTrails(client: Minecraft) {
        val level = client.level ?: return
        if (!MirrorRules.isMirror(level)) {
            pokemonTrails.clear()
            return
        }

        val now = System.currentTimeMillis()
        val player = client.player ?: return
        val box = player.boundingBox.inflate(100.0)

        for (entity in level.getEntitiesOfClass(PokemonEntity::class.java, box)) {
            val trail = pokemonTrails.getOrPut(entity.uuid) { mutableListOf() }
            val p = entity.position()
            val last = trail.lastOrNull()

            // Registrar punto si la criatura se mueve o cada 160ms
            if (last == null || last.pos.distanceToSqr(p) > 0.03 || now - last.timeMs > 180L) {
                val h = entity.bbHeight * 0.35f
                trail.add(MemoryTrailPoint(Vec3(p.x, p.y + h, p.z), now, (entity.bbWidth * 0.75f).coerceAtLeast(0.4f)))
            }

            // Generar motas de recuerdo mágicas flotantes cuando el Pokémon se desplaza
            if (entity.deltaMovement.lengthSqr() > 0.0005 || level.random.nextFloat() < 0.10f) {
                val px = entity.x + (level.random.nextDouble() - 0.5) * entity.bbWidth * 0.7
                val py = entity.y + level.random.nextDouble() * entity.bbHeight * 0.5 + 0.1
                val pz = entity.z + (level.random.nextDouble() - 0.5) * entity.bbWidth * 0.7
                level.addParticle(
                    ParticleTypes.END_ROD,
                    px, py, pz,
                    0.0, 0.012, 0.0
                )
            }
        }

        // Purgar puntos viejos (> 1800ms)
        for (list in pokemonTrails.values) {
            list.removeIf { now - it.timeMs > 1800L }
        }
        pokemonTrails.entries.removeIf { it.value.isEmpty() }
    }

    /**
     * Renderiza las cintas etéreas y líneas de luz que dejan los Pokémon a su paso.
     */
    private fun renderPokemonTrails(context: WorldRenderContext) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        if (!MirrorRules.isMirror(level)) return

        val validTrails = pokemonTrails.values.filter { it.size >= 2 }
        if (validTrails.isEmpty()) return

        val camera = context.camera()
        val camPos = camera.position
        val matrices = context.matrixStack() ?: return
        val now = System.currentTimeMillis()

        matrices.pushPose()

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableCull()
        RenderSystem.depthMask(false)
        RenderSystem.setShader(GameRenderer::getPositionColorShader)

        val tessellator = RenderSystem.renderThreadTesselator()
        val matrix = matrices.last().pose()

        // Color del rastro de recuerdo: Plata/Cian etéreo (Aura de nostalgia)
        val r = 0.72f
        val g = 0.88f
        val b = 1.0f

        // 1. Cintas translúcidas volumétricas (Quads)
        val quadBuffer = tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        var quadVertices = 0
        for (trail in validTrails) {
            for (i in 0 until trail.size - 1) {
                val p0 = trail[i]
                val p1 = trail[i + 1]

                val age0 = (now - p0.timeMs).toFloat().coerceIn(0f, 1800f) / 1800f
                val age1 = (now - p1.timeMs).toFloat().coerceIn(0f, 1800f) / 1800f

                val alpha0 = (1.0f - age0) * 0.30f
                val alpha1 = (1.0f - age1) * 0.30f

                val x0 = (p0.pos.x - camPos.x).toFloat()
                val y0 = (p0.pos.y - camPos.y).toFloat()
                val z0 = (p0.pos.z - camPos.z).toFloat()

                val x1 = (p1.pos.x - camPos.x).toFloat()
                val y1 = (p1.pos.y - camPos.y).toFloat()
                val z1 = (p1.pos.z - camPos.z).toFloat()

                val w0 = p0.width * (1.0f - age0 * 0.4f) * 0.28f
                val w1 = p1.width * (1.0f - age1 * 0.4f) * 0.28f

                quadBuffer.addVertex(matrix, x0, y0 - w0, z0).setColor(r, g, b, alpha0)
                quadBuffer.addVertex(matrix, x1, y1 - w1, z1).setColor(r, g, b, alpha1)
                quadBuffer.addVertex(matrix, x1, y1 + w1, z1).setColor(r, g, b, alpha1 * 0.12f)
                quadBuffer.addVertex(matrix, x0, y0 + w0, z0).setColor(r, g, b, alpha0 * 0.12f)
                quadVertices += 4
            }
        }
        if (quadVertices > 0) {
            quadBuffer.build()?.let { BufferUploader.drawWithShader(it) }
        } else {
            quadBuffer.build()
        }

        // 2. Líneas etéreas nítidas (DEBUG_LINES)
        RenderSystem.lineWidth(2.5f)
        val lineBuffer = tessellator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR)
        var lineVertices = 0
        for (trail in validTrails) {
            for (i in 0 until trail.size - 1) {
                val p0 = trail[i]
                val p1 = trail[i + 1]

                val age0 = (now - p0.timeMs).toFloat().coerceIn(0f, 1800f) / 1800f
                val age1 = (now - p1.timeMs).toFloat().coerceIn(0f, 1800f) / 1800f

                val alpha0 = (1.0f - age0) * 0.60f
                val alpha1 = (1.0f - age1) * 0.60f

                val x0 = (p0.pos.x - camPos.x).toFloat()
                val y0 = (p0.pos.y - camPos.y).toFloat()
                val z0 = (p0.pos.z - camPos.z).toFloat()

                val x1 = (p1.pos.x - camPos.x).toFloat()
                val y1 = (p1.pos.y - camPos.y).toFloat()
                val z1 = (p1.pos.z - camPos.z).toFloat()

                lineBuffer.addVertex(matrix, x0, y0, z0).setColor(r, g, b, alpha0)
                lineBuffer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, alpha1)
                lineVertices += 2
            }
        }
        if (lineVertices > 0) {
            lineBuffer.build()?.let { BufferUploader.drawWithShader(it) }
        } else {
            lineBuffer.build()
        }

        RenderSystem.lineWidth(1.0f)
        RenderSystem.depthMask(true)
        RenderSystem.enableCull()
        RenderSystem.disableBlend()

        matrices.popPose()
    }

    private fun updateFlashbackShader(client: Minecraft) {
        val inRecuerdos = MirrorRules.isRecuerdos(client.level)
        val inMirror = MirrorRules.isMirror(client.level)
        val inTransition = isTransitionActive()
        val shouldShaderRun = inRecuerdos || inMirror || inTransition

        val renderer = client.gameRenderer
        val current = renderer.currentEffect()

        if (shouldShaderRun) {
            val desiredShader = if (inRecuerdos) RECUERDOS_SHADER_ID else SHADER_ID
            val isCurrentDesired = current != null && current.name.contains(if (inRecuerdos) "recuerdos" else "memory")

            if (!isCurrentDesired) {
                runCatching {
                    if (current != null && shaderLoadedByUs) {
                        renderer.shutdownEffect()
                    }
                    (renderer as GameRendererAccessor).`desgraciamon4mirror$loadEffect`(desiredShader)
                    shaderLoadedByUs = true
                }
            } else if (current != null) {
                val chainAccessor = current as? PostChainAccessor
                if (chainAccessor != null) {
                    val timeSec = (System.currentTimeMillis() - clientStartMillis) / 1000.0f
                    val blurActive = if (MirrorClientSettings.isMotionBlurEnabled()) 1.0f else 0.0f
                    val swirlProgress = getTransitionProgress()
                    val swirlActive = if (inTransition) 1.0f else 0.0f

                    for (pass in chainAccessor.`desgraciamon$getPasses`()) {
                        val effect = pass.effect
                        runCatching {
                            effect.getUniform("Time")?.set(timeSec)
                            effect.getUniform("BlurEnabled")?.set(blurActive)
                            effect.getUniform("SwirlProgress")?.set(swirlProgress)
                            effect.getUniform("SwirlActive")?.set(swirlActive)
                        }
                    }
                }
            }
        } else if (shaderLoadedByUs) {
            shaderLoadedByUs = false
            if (current != null && current.name.contains(MirrorRules.MOD_ID)) {
                renderer.shutdownEffect()
            }
            (renderer as? GameRendererAccessor)?.`desgraciamon4mirror$setEffectActive`(false)
        }
    }

    private fun renderMemoryVignette(guiGraphics: GuiGraphics) {
        val width = guiGraphics.guiWidth()
        val height = guiGraphics.guiHeight()
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        // Tono plateado/pizarra desaturado etéreo
        RenderSystem.setShaderColor(0.88f, 0.90f, 0.94f, 0.30f)
        guiGraphics.blit(VIGNETTE_TEXTURE, 0, 0, 0f, 0f, width, height, 256, 256)
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
        RenderSystem.disableBlend()
    }

    /**
     * Briznas etéreas de polvo estelar que flotan suavemente sobre la isla celestial.
     */
    private fun spawnCelestialAmbientParticles(client: Minecraft) {
        val level = client.level ?: return
        if (!MirrorRules.isMirror(level)) return
        val player = client.player ?: return
        if (level.random.nextFloat() > 0.40f) return

        val rx = (level.random.nextDouble() - 0.5) * 20.0
        val rz = (level.random.nextDouble() - 0.5) * 20.0
        val ry = level.random.nextDouble() * 3.5 - 0.5

        val px = player.x + rx
        val py = player.y + ry
        val pz = player.z + rz

        level.addParticle(
            ParticleTypes.END_ROD,
            px, py, pz,
            (level.random.nextDouble() - 0.5) * 0.012,
            0.010 + level.random.nextDouble() * 0.012,
            (level.random.nextDouble() - 0.5) * 0.012
        )
    }
}
