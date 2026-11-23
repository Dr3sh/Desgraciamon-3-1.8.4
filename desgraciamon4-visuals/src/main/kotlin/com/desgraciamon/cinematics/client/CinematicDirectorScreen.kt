package com.desgraciamon.cinematics.client

import com.desgraciamon.cinematics.CinematicEditorSyncPayload
import com.desgraciamon.cinematics.CinematicRoute
import com.desgraciamon.cinematics.CinematicRouteStorage
import com.desgraciamon.cinematics.EasingMode
import com.desgraciamon.cinematics.Keyframe
import com.desgraciamon.cinematics.TransitionMode
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.min

class CinematicDirectorScreen(
    private val route: CinematicRoute
) : Screen(Component.literal("Modo Director: ${route.name}")) {

    private var selectedIdx: Int = CinematicClientEngine.selectedKeyframeIndex.coerceIn(0, (route.keyframes.size - 1).coerceAtLeast(0))

    private lateinit var dimBox: EditBox
    private lateinit var fadeColBox: EditBox
    private lateinit var fadeDurBox: EditBox
    private lateinit var musicBox: EditBox

    private lateinit var kfDurBox: EditBox
    private lateinit var kfSubBox: EditBox
    private lateinit var kfAnimBox: EditBox
    private lateinit var kfSoundBox: EditBox
    private lateinit var kfFadeBox: EditBox
    private lateinit var kfLookBox: EditBox

    private var panelX = 0
    private var panelY = 0
    private var panelW = 0
    private var panelH = 0

    override fun init() {
        super.init()
        panelW = min(width - 20, 560)
        panelH = min(height - 20, 340)
        panelX = (width - panelW) / 2
        panelY = (height - panelH) / 2

        val leftX = panelX + 12
        val rightX = panelX + 220

        // Route toggles & properties (Left column)
        addRenderableWidget(Button.builder(Component.literal("Entrada: ${route.entryMode.name}")) { b ->
            route.entryMode = nextTransition(route.entryMode)
            b.message = Component.literal("Entrada: ${route.entryMode.name}")
        }.bounds(leftX, panelY + 28, 190, 18).build())

        addRenderableWidget(Button.builder(Component.literal("Salida: ${route.exitMode.name}")) { b ->
            route.exitMode = nextTransition(route.exitMode)
            b.message = Component.literal("Salida: ${route.exitMode.name}")
        }.bounds(leftX, panelY + 50, 190, 18).build())

        addRenderableWidget(Button.builder(Component.literal("Freeze: ${if (route.freezePlayers) "ON" else "OFF"}")) { b ->
            route.freezePlayers = !route.freezePlayers
            b.message = Component.literal("Freeze: ${if (route.freezePlayers) "ON" else "OFF"}")
        }.bounds(leftX, panelY + 72, 92, 18).build())

        addRenderableWidget(Button.builder(Component.literal("Loop: ${if (route.loop) "ON" else "OFF"}")) { b ->
            route.loop = !route.loop
            b.message = Component.literal("Loop: ${if (route.loop) "ON" else "OFF"}")
        }.bounds(leftX + 98, panelY + 72, 92, 18).build())

        addRenderableWidget(Button.builder(Component.literal("Skip: ${if (route.skippable) "ON" else "OFF"}")) { b ->
            route.skippable = !route.skippable
            b.message = Component.literal("Skip: ${if (route.skippable) "ON" else "OFF"}")
        }.bounds(leftX, panelY + 94, 92, 18).build())

        addRenderableWidget(Button.builder(Component.literal("Cine: ${if (route.letterbox) "ON" else "OFF"}")) { b ->
            route.letterbox = !route.letterbox
            b.message = Component.literal("Cine: ${if (route.letterbox) "ON" else "OFF"}")
        }.bounds(leftX + 98, panelY + 94, 92, 18).build())

        dimBox = makeBox(leftX, panelY + 126, 190, route.dimension) { route.dimension = it.trim().ifEmpty { "minecraft:overworld" } }
        fadeColBox = makeBox(leftX, panelY + 158, 92, route.transitionFadeColor) { route.transitionFadeColor = it.trim().ifEmpty { "#000000" } }
        fadeDurBox = makeBox(leftX + 98, panelY + 158, 92, route.transitionFadeSeconds.toString()) {
            it.toDoubleOrNull()?.let { d -> route.transitionFadeSeconds = d.coerceIn(0.1, 10.0) }
        }
        musicBox = makeBox(leftX, panelY + 190, 190, route.musicSound ?: "") {
            route.musicSound = it.trim().ifEmpty { null }
        }
        addRenderableWidget(dimBox)
        addRenderableWidget(fadeColBox)
        addRenderableWidget(fadeDurBox)
        addRenderableWidget(musicBox)

        // Keyframe actions (Right column)
        addRenderableWidget(Button.builder(Component.literal("<")) {
            if (route.keyframes.isNotEmpty()) {
                selectedIdx = (selectedIdx - 1 + route.keyframes.size) % route.keyframes.size
                loadKeyframeFields()
            }
        }.bounds(rightX, panelY + 28, 24, 18).build())

        addRenderableWidget(Button.builder(Component.literal(">")) {
            if (route.keyframes.isNotEmpty()) {
                selectedIdx = (selectedIdx + 1) % route.keyframes.size
                loadKeyframeFields()
            }
        }.bounds(rightX + 28, panelY + 28, 24, 18).build())

        addRenderableWidget(Button.builder(Component.literal("+ Punto aquí")) {
            val p = Minecraft.getInstance().player ?: return@builder
            val kf = Keyframe(
                id = route.keyframes.size + 1,
                x = p.x, y = p.eyeY, z = p.z,
                yaw = p.yRot, pitch = p.xRot,
                durationSeconds = 4.0
            )
            route.keyframes.add(kf)
            selectedIdx = route.keyframes.lastIndex
            loadKeyframeFields()
        }.bounds(rightX + 58, panelY + 28, 86, 18).build())

        addRenderableWidget(Button.builder(Component.literal("Mover aquí")) {
            val p = Minecraft.getInstance().player ?: return@builder
            val kf = route.keyframes.getOrNull(selectedIdx) ?: return@builder
            kf.x = p.x; kf.y = p.eyeY; kf.z = p.z
            kf.yaw = p.yRot; kf.pitch = p.xRot
        }.bounds(rightX + 148, panelY + 28, 76, 18).build())

        addRenderableWidget(Button.builder(Component.literal("Borrar")) {
            if (selectedIdx in route.keyframes.indices) {
                route.keyframes.removeAt(selectedIdx)
                route.keyframes.forEachIndexed { i, k -> k.id = i + 1 }
                selectedIdx = selectedIdx.coerceIn(0, (route.keyframes.size - 1).coerceAtLeast(0))
                loadKeyframeFields()
            }
        }.bounds(rightX + 228, panelY + 28, 56, 18).build())

        addRenderableWidget(Button.builder(Component.literal("Easing")) {
            val kf = route.keyframes.getOrNull(selectedIdx) ?: return@builder
            val vals = EasingMode.entries
            kf.easing = vals[(kf.easing.ordinal + 1) % vals.size]
        }.bounds(rightX + 148, panelY + 62, 136, 18).build())

        kfDurBox = makeBox(rightX, panelY + 62, 136, "4.0") {
            val kf = route.keyframes.getOrNull(selectedIdx) ?: return@makeBox
            it.toDoubleOrNull()?.let { d -> kf.durationSeconds = d.coerceIn(0.1, 120.0) }
        }
        kfSubBox = makeBox(rightX, panelY + 96, 284, "") {
            val kf = route.keyframes.getOrNull(selectedIdx) ?: return@makeBox
            kf.subtitle = it.ifBlank { null }
        }
        kfAnimBox = makeBox(rightX, panelY + 130, 136, "") {
            val kf = route.keyframes.getOrNull(selectedIdx) ?: return@makeBox
            kf.animationTrigger = it.trim().ifEmpty { null }
        }
        kfSoundBox = makeBox(rightX + 148, panelY + 130, 136, "") {
            val kf = route.keyframes.getOrNull(selectedIdx) ?: return@makeBox
            kf.soundTrigger = it.trim().ifEmpty { null }
        }
        kfFadeBox = makeBox(rightX, panelY + 164, 136, "") {
            val kf = route.keyframes.getOrNull(selectedIdx) ?: return@makeBox
            kf.fadeColor = it.trim().ifEmpty { null }
        }
        kfLookBox = makeBox(rightX + 148, panelY + 164, 136, "") { txt ->
            val kf = route.keyframes.getOrNull(selectedIdx) ?: return@makeBox
            val parts = txt.trim().split(" ", ",").filter { it.isNotBlank() }
            if (parts.size == 3) {
                val lx = parts[0].toDoubleOrNull()
                val ly = parts[1].toDoubleOrNull()
                val lz = parts[2].toDoubleOrNull()
                if (lx != null && ly != null && lz != null) {
                    kf.lookAtX = lx; kf.lookAtY = ly; kf.lookAtZ = lz
                    kf.lookAtEntity = null
                    return@makeBox
                }
            }
            if (txt.isBlank()) {
                kf.lookAtX = null; kf.lookAtY = null; kf.lookAtZ = null; kf.lookAtEntity = null
            } else if (parts.size == 1) {
                kf.lookAtEntity = parts[0]
            }
        }

        addRenderableWidget(kfDurBox)
        addRenderableWidget(kfSubBox)
        addRenderableWidget(kfAnimBox)
        addRenderableWidget(kfSoundBox)
        addRenderableWidget(kfFadeBox)
        addRenderableWidget(kfLookBox)
        loadKeyframeFields()

        // Bottom actions
        addRenderableWidget(Button.builder(Component.literal("Guardar Ruta")) {
            ClientPlayNetworking.send(CinematicEditorSyncPayload(CinematicRouteStorage.toJson(route), openGui = false))
            onClose()
        }.bounds(panelX + panelW - 220, panelY + panelH - 28, 100, 20).build())

        addRenderableWidget(Button.builder(Component.literal("Probar")) {
            ClientPlayNetworking.send(CinematicEditorSyncPayload(CinematicRouteStorage.toJson(route), openGui = false))
            Minecraft.getInstance().player?.connection?.sendCommand("cinematicas director probar")
            onClose()
        }.bounds(panelX + panelW - 114, panelY + panelH - 28, 52, 20).build())

        addRenderableWidget(Button.builder(Component.literal("Cerrar")) { onClose() }
            .bounds(panelX + panelW - 58, panelY + panelH - 28, 48, 20).build())
    }

    private fun makeBox(x: Int, y: Int, w: Int, initial: String, onUpdate: (String) -> Unit): EditBox {
        val b = EditBox(font, x, y, w, 16, Component.empty())
        b.setMaxLength(128)
        b.value = initial
        b.setResponder(onUpdate)
        return b
    }

    private fun loadKeyframeFields() {
        CinematicClientEngine.selectedKeyframeIndex = selectedIdx
        val kf = route.keyframes.getOrNull(selectedIdx)
        kfDurBox.value = kf?.durationSeconds?.toString() ?: "4.0"
        kfSubBox.value = kf?.subtitle ?: ""
        kfAnimBox.value = kf?.animationTrigger ?: ""
        kfSoundBox.value = kf?.soundTrigger ?: ""
        kfFadeBox.value = kf?.fadeColor ?: ""
        kfLookBox.value = when {
            kf == null -> ""
            !kf.lookAtEntity.isNullOrBlank() -> kf.lookAtEntity!!
            kf.hasLookAtPoint() -> "${kf.lookAtX} ${kf.lookAtY} ${kf.lookAtZ}"
            else -> ""
        }
    }

    private fun nextTransition(m: TransitionMode): TransitionMode {
        val vals = TransitionMode.entries
        return vals[(m.ordinal + 1) % vals.size]
    }

    override fun renderBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.renderBackground(guiGraphics, mouseX, mouseY, partialTick)
        guiGraphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xE612151E.toInt())
        guiGraphics.renderOutline(panelX, panelY, panelW, panelH, 0xFF3E4A61.toInt())
        guiGraphics.drawCenteredString(font, title, panelX + panelW / 2, panelY + 8, 0xFFFFFFFF.toInt())

        val leftX = panelX + 12
        val rightX = panelX + 220
        guiGraphics.drawString(font, "Dimensión:", leftX, panelY + 116, 0xFFA0AEC0.toInt(), false)
        guiGraphics.drawString(font, "Fade Color / Dur(s):", leftX, panelY + 148, 0xFFA0AEC0.toInt(), false)
        guiGraphics.drawString(font, "Música (Sound ID):", leftX, panelY + 180, 0xFFA0AEC0.toInt(), false)

        val kf = route.keyframes.getOrNull(selectedIdx)
        val kfInfo = if (kf != null) "Punto #${selectedIdx + 1}/${route.keyframes.size} (${kf.easing.name})" else "Sin puntos"
        guiGraphics.drawString(font, kfInfo, rightX, panelY + 16, 0xFF63B3ED.toInt(), false)
        guiGraphics.drawString(font, "Duración (s):", rightX, panelY + 52, 0xFFA0AEC0.toInt(), false)
        guiGraphics.drawString(font, "Subtítulo:", rightX, panelY + 86, 0xFFA0AEC0.toInt(), false)
        guiGraphics.drawString(font, "Animación Trigger:", rightX, panelY + 120, 0xFFA0AEC0.toInt(), false)
        guiGraphics.drawString(font, "Sonido Trigger:", rightX + 148, panelY + 120, 0xFFA0AEC0.toInt(), false)
        guiGraphics.drawString(font, "Fade Color:", rightX, panelY + 154, 0xFFA0AEC0.toInt(), false)
        guiGraphics.drawString(font, "LookAt (x y z / jugador):", rightX + 148, panelY + 154, 0xFFA0AEC0.toInt(), false)

        // Timeline bar
        val tlY = panelY + 218
        val tlW = panelW - 24
        guiGraphics.drawString(font, "Línea de Tiempo (${String.format("%.1f", route.totalDurationSeconds())}s):", leftX, tlY - 12, 0xFFA0AEC0.toInt(), false)
        guiGraphics.fill(leftX, tlY, leftX + tlW, tlY + 28, 0xFF0B0E14.toInt())
        guiGraphics.renderOutline(leftX, tlY, tlW, 28, 0xFF2D3748.toInt())
        if (route.keyframes.isNotEmpty()) {
            val slotW = min(44, (tlW - 8) / route.keyframes.size)
            for (i in route.keyframes.indices) {
                val sx = leftX + 4 + i * slotW
                val bg = if (i == selectedIdx) 0xFF2B6CB0.toInt() else 0xFF1A202C.toInt()
                guiGraphics.fill(sx, tlY + 3, sx + slotW - 2, tlY + 25, bg)
                guiGraphics.drawCenteredString(font, "#${i + 1}", sx + (slotW - 2) / 2, tlY + 10, 0xFFFFFFFF.toInt())
            }
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (super.mouseClicked(mouseX, mouseY, button)) return true
        val leftX = panelX + 12
        val tlY = panelY + 218
        val tlW = panelW - 24
        if (route.keyframes.isNotEmpty() && mouseX in leftX.toDouble()..(leftX + tlW).toDouble() && mouseY in tlY.toDouble()..(tlY + 28).toDouble()) {
            val slotW = min(44, (tlW - 8) / route.keyframes.size)
            val idx = ((mouseX - (leftX + 4)) / slotW).toInt()
            if (idx in route.keyframes.indices) {
                selectedIdx = idx
                loadKeyframeFields()
                return true
            }
        }
        return false
    }

    override fun isPauseScreen(): Boolean = false
}
