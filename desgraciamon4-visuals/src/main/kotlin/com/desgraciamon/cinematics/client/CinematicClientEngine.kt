package com.desgraciamon.cinematics.client

import com.desgraciamon.cinematics.CinematicClientReadyPayload
import com.desgraciamon.cinematics.CinematicRoute
import com.desgraciamon.cinematics.CinematicSkipPayload
import com.desgraciamon.cinematics.TransitionMode
import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3
import java.util.function.BiConsumer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object CinematicClientEngine {
    var activeRoute: CinematicRoute? = null
        private set

    var isPreparing: Boolean = false
        private set

    var isPaused: Boolean = false
        private set

    private var startServerMs: Long = 0L
    private var serverClockOffsetMs: Long = 0L
    private var driftCorrectionMs: Double = 0.0
    private var pausedElapsedSec: Double = 0.0

    var isEditorMode: Boolean = false
        private set

    var editorRoute: CinematicRoute? = null
        private set

    var selectedKeyframeIndex: Int = 0

    // Posición y orientación actual de la cámara virtual interpolada
    var cameraPos: Vec3 = Vec3.ZERO
        private set
    var cameraYaw: Float = 0f
        private set
    var cameraPitch: Float = 0f
        private set
    var cameraRoll: Float = 0f
        private set

    private var virtualCameraEntity: ArmorStand? = null
    private var previousHideGui: Boolean? = null
    private var activeMusicSound: SoundInstance? = null
    private var entryFadeTriggered = false
    private var exitFadeTriggered = false

    // Control de temblor de cámara (Camera Shake)
    private var shakeIntensity: Float = 0f
    private var shakeDurationSec: Float = 0f
    private var shakeStartSec: Double = 0.0

    // Control de Fade de pantalla (color y duración personalizables)
    var activeFadeColor: Int = 0x000000
        private set
    var fadeDurationSec: Float = 0f
        private set
    var fadeStartSec: Double = 0.0
        private set

    fun resetFade() {
        fadeDurationSec = 0f
    }

    // Acciones ya disparadas para no repetirlas (por índice de keyframe)
    private val triggeredKeyframeIndices = mutableSetOf<Int>()
    private var lastLoopCycle: Int = 0

    fun isCinematicActive(): Boolean = activeRoute != null

    fun currentElapsedSec(): Double {
        if (isPaused) return pausedElapsedSec
        if (startServerMs == 0L) return 0.0
        val serverNow = System.currentTimeMillis() + serverClockOffsetMs
        val rawMs = (serverNow - startServerMs).toDouble() + driftCorrectionMs
        return (rawMs / 1000.0).coerceAtLeast(0.0)
    }

    @Suppress("UNCHECKED_CAST")
    fun preparePlayback(route: CinematicRoute, requiredAnimations: List<String>) {
        isPreparing = true
        activeFadeColor = parseColor(route.transitionFadeColor)
        if (route.entryMode == TransitionMode.FADE) {
            triggerFade(activeFadeColor, (route.transitionFadeSeconds.toFloat() * 1.4f).coerceAtLeast(0.8f), System.currentTimeMillis() / 1000.0)
        }

        val share = FabricLoader.getInstance().objectShare
        val hook = share.get("desgraciamon:prewarm_animations") as? BiConsumer<List<String>, Runnable>
        if (hook != null && requiredAnimations.isNotEmpty()) {
            hook.accept(requiredAnimations) {
                Minecraft.getInstance().execute {
                    if (isPreparing) {
                        ClientPlayNetworking.send(CinematicClientReadyPayload(route.name))
                    }
                }
            }
        } else {
            ClientPlayNetworking.send(CinematicClientReadyPayload(route.name))
        }
    }

    fun onHeartbeat(serverElapsedMs: Long, paused: Boolean) {
        if (activeRoute == null) return
        isPaused = paused
        if (paused) {
            pausedElapsedSec = serverElapsedMs / 1000.0
            return
        }
        val localMs = currentElapsedSec() * 1000.0
        val diff = serverElapsedMs.toDouble() - localMs
        if (kotlin.math.abs(diff) > 400.0) {
            driftCorrectionMs += diff
        } else if (kotlin.math.abs(diff) > 75.0) {
            driftCorrectionMs += diff * 0.35
        }
    }

    fun startPlayback(route: CinematicRoute, startMs: Long, serverNowMs: Long = startMs) {
        if (startMs == -1L) {
            isEditorMode = true
            editorRoute = route
            activeRoute = null
            isPreparing = false
            startServerMs = 0L
            return
        }

        val client = Minecraft.getInstance()
        isPreparing = false
        isPaused = false
        activeRoute = route
        serverClockOffsetMs = serverNowMs - System.currentTimeMillis()
        startServerMs = startMs
        driftCorrectionMs = 0.0
        pausedElapsedSec = 0.0
        lastLoopCycle = 0
        isEditorMode = false
        editorRoute = null
        triggeredKeyframeIndices.clear()
        shakeDurationSec = 0f
        entryFadeTriggered = false
        exitFadeTriggered = false

        if (previousHideGui == null) {
            previousHideGui = client.options.hideGui
        }
        if (route.hideHud) {
            client.options.hideGui = true
        }

        // Crear cámara virtual local para no ocultar el cuerpo del propio jugador
        val level = client.level
        val first = route.keyframes.firstOrNull()
        if (level != null && first != null) {
            val cam = ArmorStand(level, first.x, first.y, first.z)
            cam.isInvisible = true
            cam.isNoGravity = true
            virtualCameraEntity = cam
            client.cameraEntity = cam
        }

        // Iniciar música sincronizada si la ruta tiene pista
        stopMusic()
        route.musicSound?.trim()?.takeIf { it.isNotEmpty() }?.let { soundId ->
            val rl = ResourceLocation.tryParse(soundId)
            if (rl != null) {
                val evt = BuiltInRegistries.SOUND_EVENT.get(rl) ?: SoundEvent.createVariableRangeEvent(rl)
                val inst = SimpleSoundInstance(
                    evt.location,
                    SoundSource.RECORDS,
                    route.musicVolume,
                    route.musicPitch,
                    SoundInstance.createUnseededRandom(),
                    false,
                    0,
                    SoundInstance.Attenuation.NONE,
                    0.0, 0.0, 0.0,
                    true
                )
                activeMusicSound = inst
                client.soundManager.play(inst)
            }
        }
    }

    fun stopPlayback(reason: String = "finished") {
        activeRoute = null
        isPreparing = false
        isPaused = false
        startServerMs = 0L
        driftCorrectionMs = 0.0
        pausedElapsedSec = 0.0
        isEditorMode = false
        editorRoute = null
        triggeredKeyframeIndices.clear()
        fadeDurationSec = 0f
        shakeDurationSec = 0f
        entryFadeTriggered = false
        exitFadeTriggered = false

        stopMusic()
        (FabricLoader.getInstance().objectShare.get("desgraciamon:stop_animations") as? Runnable)?.run()

        val client = Minecraft.getInstance()
        previousHideGui?.let {
            client.options.hideGui = it
            previousHideGui = null
        }
        if (virtualCameraEntity != null) {
            virtualCameraEntity = null
            if (client.player != null) {
                client.cameraEntity = client.player
            }
        }
    }

    private fun stopMusic() {
        activeMusicSound?.let {
            Minecraft.getInstance().soundManager.stop(it)
            activeMusicSound = null
        }
    }

    fun sendSkipRequest() {
        val route = activeRoute ?: return
        if (!route.skippable) return
        ClientPlayNetworking.send(CinematicSkipPayload(route.name))
    }

    fun triggerShake(intensity: Float, durationSec: Float, currentElapsed: Double = currentElapsedSec()) {
        shakeIntensity = intensity
        shakeDurationSec = durationSec
        shakeStartSec = currentElapsed
    }

    fun triggerFade(color: Int, durationSec: Float, currentElapsed: Double = currentElapsedSec()) {
        activeFadeColor = color
        fadeDurationSec = if (durationSec > 0.05f) durationSec else 0.8f
        fadeStartSec = currentElapsed
    }

    fun parseColor(colorStr: String?): Int {
        if (colorStr.isNullOrBlank()) return 0x000000
        val clean = colorStr.trim().lowercase()
        return when (clean) {
            "black", "negro" -> 0x000000
            "white", "blanco" -> 0xFFFFFF
            "gold", "dorado", "yellow", "amarillo" -> 0xFFD700
            "red", "rojo" -> 0xEE2222
            "blue", "azul" -> 0x2266FF
            "cyan", "celeste" -> 0x00E5FF
            "green", "verde" -> 0x22CC55
            "purple", "morado", "magenta" -> 0xBA55D3
            "orange", "naranja" -> 0xFF7A00
            "pink", "rosa" -> 0xFF69B4
            "dark", "oscuro" -> 0x0A0F1D
            else -> runCatching {
                val hex = if (clean.startsWith("#")) clean.substring(1) else clean
                when {
                    hex.length == 6 -> hex.toInt(16)
                    hex.length >= 8 -> hex.takeLast(6).toInt(16)
                    else -> 0x000000
                }
            }.getOrDefault(0x000000)
        }
    }

    fun updateCamera(partialTick: Float) {
        val route = activeRoute ?: return
        val client = Minecraft.getInstance()
        val player = client.player ?: return

        val elapsedSec = currentElapsedSec()
        val totalSec = route.totalDurationSeconds()

        if (elapsedSec >= totalSec && !route.loop) {
            stopPlayback("completed")
            return
        }

        val cycleSec = if (route.loop && totalSec > 0.0) {
            val cycle = (elapsedSec / totalSec).toInt()
            if (cycle != lastLoopCycle) {
                lastLoopCycle = cycle
                triggeredKeyframeIndices.clear()
            }
            elapsedSec % totalSec
        } else {
            elapsedSec
        }

        val targetPos = route.evaluatePosition(cycleSec)
        val (catYaw, catPitch, catRoll) = route.evaluateRotationFull(cycleSec)

        // Modo lookAt por keyframe (punto fijo o entidad/jugador)
        var targetYaw = catYaw
        var targetPitch = catPitch
        val (lookPoint, lookEntityName) = route.evaluateLookAtTarget(cycleSec)
        val resolvedLook = resolveLookAtPos(client, lookPoint, lookEntityName, partialTick)
        if (resolvedLook != null) {
            val (ly, lp) = computeLookAngles(targetPos, resolvedLook)
            targetYaw = ly
            targetPitch = lp
        }

        // Entrada y salida configurables (adiós playerEyePos.lerp obligatorio)
        val fadeSec = route.transitionFadeSeconds.coerceIn(0.2, 4.0)
        val playerEyePos = player.getPosition(partialTick).add(0.0, player.getEyeHeight(player.pose).toDouble(), 0.0)
        var blendedPos = targetPos

        val firstPos = route.keyframes.firstOrNull()?.position() ?: targetPos
        val lastPos = route.keyframes.lastOrNull()?.position() ?: targetPos
        val nearEntry = playerEyePos.distanceTo(firstPos) <= route.lerpNearDistance
        val nearExit = playerEyePos.distanceTo(lastPos) <= route.lerpNearDistance

        if (elapsedSec < fadeSec) {
            when (route.entryMode) {
                TransitionMode.DIRECT -> Unit
                TransitionMode.FADE -> {
                    if (!entryFadeTriggered) {
                        entryFadeTriggered = true
                        triggerFade(parseColor(route.transitionFadeColor), fadeSec.toFloat(), elapsedSec)
                    }
                }
                TransitionMode.PLAYER_LERP_IF_NEAR -> {
                    if (nearEntry) {
                        val t = (elapsedSec / fadeSec).toFloat()
                        val smooth = t * t * (3f - 2f * t)
                        blendedPos = playerEyePos.lerp(targetPos, smooth.toDouble())
                    } else if (!entryFadeTriggered) {
                        entryFadeTriggered = true
                        triggerFade(parseColor(route.transitionFadeColor), fadeSec.toFloat(), elapsedSec)
                    }
                }
            }
        } else if (!route.loop && totalSec > fadeSec && elapsedSec > totalSec - fadeSec) {
            when (route.exitMode) {
                TransitionMode.DIRECT -> Unit
                TransitionMode.FADE -> {
                    if (!exitFadeTriggered) {
                        exitFadeTriggered = true
                        triggerFade(parseColor(route.transitionFadeColor), fadeSec.toFloat(), elapsedSec)
                    }
                }
                TransitionMode.PLAYER_LERP_IF_NEAR -> {
                    if (nearExit) {
                        val t = ((totalSec - elapsedSec) / fadeSec).toFloat().coerceIn(0f, 1f)
                        val smooth = t * t * (3f - 2f * t)
                        blendedPos = playerEyePos.lerp(targetPos, smooth.toDouble())
                    } else if (!exitFadeTriggered) {
                        exitFadeTriggered = true
                        triggerFade(parseColor(route.transitionFadeColor), fadeSec.toFloat(), elapsedSec)
                    }
                }
            }
        }

        // Aplicar Camera Shake si está activo
        var finalPos = blendedPos
        var finalYaw = targetYaw
        var finalPitch = targetPitch

        if (shakeIntensity > 0.001f && (elapsedSec - shakeStartSec) < shakeDurationSec) {
            val shakeTime = (elapsedSec - shakeStartSec).toFloat()
            val fade = 1.0f - (shakeTime / shakeDurationSec)
            val currentAmp = shakeIntensity * fade

            val shakeX = sin(shakeTime * 45.0) * 0.12 * currentAmp
            val shakeY = cos(shakeTime * 52.0) * 0.12 * currentAmp
            val shakeRot = sin(shakeTime * 38.0).toFloat() * 1.5f * currentAmp

            finalPos = finalPos.add(shakeX, shakeY, 0.0)
            finalYaw += shakeRot
            finalPitch += (shakeY * 5.0).toFloat()
        }

        cameraPos = finalPos
        cameraYaw = finalYaw
        cameraPitch = finalPitch
        cameraRoll = catRoll

        if (virtualCameraEntity == null && client.level != null) {
            val cam = ArmorStand(client.level!!, finalPos.x, finalPos.y, finalPos.z)
            cam.isInvisible = true
            cam.isNoGravity = true
            virtualCameraEntity = cam
        }
        virtualCameraEntity?.let { cam ->
            if (client.cameraEntity !== cam) {
                client.cameraEntity = cam
            }
            cam.setPosRaw(finalPos.x, finalPos.y, finalPos.z)
            cam.xo = finalPos.x
            cam.yo = finalPos.y
            cam.zo = finalPos.z
            cam.yRot = finalYaw
            cam.xRot = finalPitch
        }

        checkLocalTriggers(route, cycleSec, elapsedSec)
    }

    private fun resolveLookAtPos(client: Minecraft, point: Vec3?, entityName: String?, partialTick: Float): Vec3? {
        if (!entityName.isNullOrBlank()) {
            val level = client.level ?: return point
            val target: Entity? = level.players().firstOrNull { it.gameProfile.name.equals(entityName, ignoreCase = true) }
            if (target != null) {
                return target.getPosition(partialTick).add(0.0, target.eyeHeight.toDouble(), 0.0)
            }
        }
        return point
    }

    private fun computeLookAngles(from: Vec3, to: Vec3): Pair<Float, Float> {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val horiz = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-5)
        val yaw = (Math.toDegrees(atan2(-dx, dz))).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy, horiz))).toFloat().coerceIn(-90f, 90f)
        return yaw to pitch
    }

    private fun checkLocalTriggers(route: CinematicRoute, cycleSec: Double, absoluteElapsedSec: Double) {
        var accumulated = 0.0
        for ((idx, kf) in route.keyframes.withIndex()) {
            if (cycleSec >= accumulated && !triggeredKeyframeIndices.contains(idx)) {
                triggeredKeyframeIndices.add(idx)

                if (kf.fadeColor != null) {
                    val color = parseColor(kf.fadeColor)
                    triggerFade(color, kf.fadeDurationSeconds.toFloat(), absoluteElapsedSec)
                } else if (kf.shaderEffect?.lowercase() in listOf("fade", "color_fade")) {
                    triggerFade(0x000000, kf.shaderDurationSeconds.toFloat(), absoluteElapsedSec)
                } else if (kf.isSeasonJump) {
                    triggerFade(0x000000, 2.0f, absoluteElapsedSec)
                }

                kf.soundTrigger?.trim()?.takeIf { it.isNotEmpty() }?.let { soundId ->
                    val rl = ResourceLocation.tryParse(soundId)
                    if (rl != null) {
                        val evt = BuiltInRegistries.SOUND_EVENT.get(rl) ?: SoundEvent.createVariableRangeEvent(rl)
                        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(evt, 1.0f, 1.0f))
                    }
                }

                kf.shaderEffect?.let { effect ->
                    when (effect.lowercase()) {
                        "swirl" -> triggerShake(0.4f, kf.shaderDurationSeconds.toFloat(), absoluteElapsedSec)
                        "shake" -> triggerShake(0.8f, kf.shaderDurationSeconds.toFloat(), absoluteElapsedSec)
                        "flash_gold" -> {
                            triggerShake(0.2f, 1.0f, absoluteElapsedSec)
                            triggerFade(0xFFD700, 1.0f, absoluteElapsedSec)
                        }
                        "flash_white" -> {
                            triggerShake(0.2f, 1.0f, absoluteElapsedSec)
                            triggerFade(0xFFFFFF, 1.0f, absoluteElapsedSec)
                        }
                    }
                }
            }
            accumulated += kf.durationSeconds
        }
    }

    fun getActiveSubtitle(route: CinematicRoute, elapsedSec: Double): String? {
        val totalTime = route.totalDurationSeconds()
        val clampedTime = if (route.loop && totalTime > 0.0) elapsedSec % totalTime else elapsedSec
        var accumulated = 0.0
        for (kf in route.keyframes) {
            val dur = kf.durationSeconds.coerceAtLeast(0.05)
            if (clampedTime in accumulated..(accumulated + dur)) {
                return if (kf.subtitle.isNullOrBlank()) null else kf.subtitle
            }
            accumulated += dur
        }
        return null
    }

    fun renderCinematicHud(graphics: GuiGraphics, partialTick: Float) {
        val width = graphics.guiWidth()
        val height = graphics.guiHeight()
        val font = Minecraft.getInstance().font

        if (isPreparing) {
            graphics.fill(0, 0, width, height, 0xCC000000.toInt())
            graphics.drawCenteredString(font, "§7Cargando escena cinemática...", width / 2, height / 2, 0xFFFFFFFF.toInt())
            return
        }

        val route = activeRoute
        if (route != null) {
            val elapsedSec = currentElapsedSec()

            if (route.letterbox) {
                val barHeight = (height * 0.10f).toInt()
                RenderSystem.enableBlend()
                RenderSystem.defaultBlendFunc()
                graphics.fill(0, 0, width, barHeight, 0xFF000000.toInt())
                graphics.fill(0, height - barHeight, width, height, 0xFF000000.toInt())
                RenderSystem.disableBlend()
            }

            val sub = getActiveSubtitle(route, elapsedSec)
            if (sub != null) {
                val yPos = if (route.letterbox) height - (height * 0.07f).toInt() else height - 35
                graphics.drawCenteredString(font, sub, width / 2, yPos, 0xFFFFFFFF.toInt())
            }

            if (route.skippable) {
                graphics.drawString(font, "§7[Espacio / ESC] Saltar", width - 118, 8, 0xFFD0D0D0.toInt())
            }
            if (isPaused) {
                graphics.drawCenteredString(font, "§e§l[PAUSADO]", width / 2, 16, 0xFFFFFFFF.toInt())
            }
        }

        if (isEditorMode && editorRoute != null) {
            renderEditorHud(graphics, width, height)
        }
    }

    private fun renderEditorHud(graphics: GuiGraphics, width: Int, height: Int) {
        val route = editorRoute ?: return
        val client = Minecraft.getInstance()
        val font = client.font

        val selIdx = selectedKeyframeIndex.coerceIn(0, (route.keyframes.size - 1).coerceAtLeast(0))
        val selKf = route.keyframes.getOrNull(selIdx)

        val boxWidth = 290
        val boxHeight = 68
        RenderSystem.enableBlend()
        graphics.fill(8, 8, 8 + boxWidth, 8 + boxHeight, 0x90101625.toInt())
        RenderSystem.disableBlend()

        graphics.drawString(font, "§6MODO DIRECTOR: §e${route.name}", 14, 13, 0xFFFFFFFF.toInt())
        graphics.drawString(font, "§7Puntos: §a${route.keyframes.size} §8| §7Duración: §e${String.format("%.1f", route.totalDurationSeconds())}s", 14, 25, 0xFFCCCCCC.toInt())

        if (selKf != null) {
            val dist = client.player?.let { (it.eyePosition.distanceTo(selKf.position())).toInt() } ?: 0
            val effectBadge = when {
                selKf.fadeColor != null -> "§6[Fade: ${selKf.fadeColor}]"
                selKf.animationTrigger != null -> "§d[Anim: ${selKf.animationTrigger}]"
                selKf.isSeasonJump -> "§d[SALTO]"
                selKf.shaderEffect != null -> "§b[Shader: ${selKf.shaderEffect}]"
                else -> "§8(Sin efectos)"
            }
            graphics.drawString(font, "§bEnfocado: §f#${selIdx + 1} §7(${dist}m, ${String.format("%.1f", selKf.durationSeconds)}s) $effectBadge", 14, 38, 0xFFFFFFFF.toInt())
        } else {
            graphics.drawString(font, net.minecraft.network.chat.Component.literal("§7Añade tu primer punto con §e/cinematicas director agregar §7o tecla §e").append(net.minecraft.network.chat.Component.keybind("key.desgraciamon.director_add")), 14, 38, 0xFFAAAAAA.toInt(), false)
        }

        graphics.drawString(font, "§8Rápido: §eK§8=Añadir | §eM§8=Mover | §e[ / ]§8=Sel | §eP§8=GUI", 14, 52, 0xFF888888.toInt())
    }
}
