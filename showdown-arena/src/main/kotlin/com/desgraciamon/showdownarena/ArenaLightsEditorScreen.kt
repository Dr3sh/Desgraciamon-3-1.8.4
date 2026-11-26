package com.desgraciamon.showdownarena

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.awt.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class ArenaLightsEditorScreen(
	private val initialState: ArenaLightsStatePayload
) : Screen(Component.literal("Editor de Luces de la Arena")) {

	private var modeId: String = initialState.modeId
	private var rodMode: String = initialState.rodMode
	private val playlist: MutableList<String> = initialState.playlist.toMutableList()
	private val disabledEffects: MutableSet<String> = initialState.disabledEffects.toMutableSet()
	private var dayColor1: Int = initialState.dayColor1
	private var dayColor2: Int = initialState.dayColor2
	private var nightColor1: Int = initialState.nightColor1
	private var nightColor2: Int = initialState.nightColor2
	private var syncDomeWithLights: Boolean = initialState.syncDomeWithLights
	private var loopDurationMs: Long = initialState.loopDurationMs.coerceIn(2000L, 120000L)

	private var activeTab: Int = 0 // 0 = Dos Colores, 1 = RGB
	private var catalogScrollY: Int = 0
	private var playlistScrollX: Int = 0
	private var previewNight: Boolean = false
	private var previewFollowsActivePlaylist: Boolean = true
	private lateinit var previewFollowBtn: Button
	private var previewStartMs: Long = System.currentTimeMillis()
	private var previewPrevEffect: String = initialState.activeEffectId
	private var previewCurrEffect: String = initialState.activeEffectId
	private var lastNonOffEffect: String = if (initialState.activeEffectId != SpotlightShowMode.APAGADO.id && initialState.activeEffectId != SpotlightShowMode.AUTO.id) {
		initialState.activeEffectId
	} else {
		initialState.playlist.firstOrNull() ?: SpotlightShowMode.CENTRO.id
	}
	private var previewBlendStartMs: Long = System.currentTimeMillis()

	// Drag & drop state for playlist slots
	private var draggingSlotIndex: Int = -1
	private var dragMouseX: Double = 0.0
	private var dragMouseY: Double = 0.0

	// Color EditBoxes (hex RRGGBB) + Visual Hex Picker Popup (estilo Gremios)
	private lateinit var dayC1Box: EditBox
	private lateinit var dayC2Box: EditBox
	private lateinit var nightC1Box: EditBox
	private lateinit var nightC2Box: EditBox
	private var activePickerSlot: Int = -1 // -1=none, 0=dayC1, 1=dayC2, 2=nightC1, 3=nightC2
	private var pickerDragMode: Int = 0 // 0=none, 1=SV box, 2=Hue bar
	private var pickerHue: Float = 0.55f
	private var pickerSat: Float = 0.75f
	private var pickerBri: Float = 0.85f
	private var updatingFromPicker: Boolean = false

	// Layout coordinates computed in init()
	private var panelX = 0
	private var panelY = 0
	private var panelW = 0
	private var panelH = 0
	private var playlistY = 0
	private var playlistH = 44
	private var catalogX = 0
	private var catalogY = 0
	private var catalogW = 0
	private var catalogH = 0
	private var previewX = 0
	private var previewY = 0
	private var previewW = 0
	private var previewH = 0

	override fun init() {
		super.init()
		panelW = min(width - 20, 620)
		panelH = min(height - 20, 380)
		panelX = (width - panelW) / 2
		panelY = (height - panelH) / 2

		playlistY = panelY + 52
		catalogX = panelX + 10
		catalogY = playlistY + playlistH + 28
		catalogW = (panelW * 0.56).toInt()
		catalogH = panelY + panelH - catalogY - 12

		previewX = catalogX + catalogW + 10
		previewY = catalogY - 20
		previewW = panelX + panelW - previewX - 10
		previewH = panelY + panelH - previewY - 12

		// Top control bar buttons
		addRenderableWidget(Button.builder(Component.literal(modeButtonLabel())) { btn ->
			modeId = when (modeId) {
				SpotlightShowMode.AUTO.id -> lastNonOffEffect
				SpotlightShowMode.APAGADO.id -> SpotlightShowMode.AUTO.id
				else -> SpotlightShowMode.APAGADO.id
			}
			btn.message = Component.literal(modeButtonLabel())
		}.bounds(panelX + 10, panelY + 24, 128, 20).build())

		addRenderableWidget(Button.builder(Component.literal("Varillas: $rodMode")) { btn ->
			rodMode = when (rodMode) {
				"1" -> "3"
				"3" -> "auto"
				else -> "1"
			}
			btn.message = Component.literal("Varillas: $rodMode")
		}.bounds(panelX + 144, panelY + 24, 88, 20).build())

		addRenderableWidget(Button.builder(Component.literal("Loop: ${loopDurationMs / 1000L}s")) { btn ->
			loopDurationMs = when (loopDurationMs) {
				5000L -> 8000L
				8000L -> 10000L
				10000L -> 15000L
				15000L -> 20000L
				else -> 5000L
			}
			btn.message = Component.literal("Loop: ${loopDurationMs / 1000L}s")
		}.bounds(panelX + 238, panelY + 24, 78, 20).build())

		addRenderableWidget(Button.builder(Component.literal(if (syncDomeWithLights) "Domo: Sync ON" else "Domo: Sync OFF")) { btn ->
			syncDomeWithLights = !syncDomeWithLights
			btn.message = Component.literal(if (syncDomeWithLights) "Domo: Sync ON" else "Domo: Sync OFF")
		}.bounds(panelX + 322, panelY + 24, 108, 20).build())

		addRenderableWidget(Button.builder(Component.literal("Guardar")) {
			saveAndClose()
		}.bounds(panelX + panelW - 146, panelY + 24, 72, 20).build())

		addRenderableWidget(Button.builder(Component.literal("Cerrar")) {
			onClose()
		}.bounds(panelX + panelW - 68, panelY + 24, 58, 20).build())

		// Tabs above catalog
		addRenderableWidget(Button.builder(Component.literal("Dos Colores (27)")) {
			activeTab = 0
			catalogScrollY = 0
		}.bounds(catalogX, catalogY - 22, 120, 18).build())

		addRenderableWidget(Button.builder(Component.literal("RGB (22)")) {
			activeTab = 1
			catalogScrollY = 0
		}.bounds(catalogX + 124, catalogY - 22, 90, 18).build())

		// Preview Day/Night + Active Playlist checkbox
		val halfPrevW = (previewW - 4) / 2
		addRenderableWidget(Button.builder(Component.literal(if (previewNight) "Noche" else "Día")) { btn ->
			previewNight = !previewNight
			btn.message = Component.literal(if (previewNight) "Noche" else "Día")
		}.bounds(previewX, previewY, halfPrevW, 18).build())

		previewFollowBtn = Button.builder(Component.literal(if (previewFollowsActivePlaylist) "[✓] Activas" else "[ ] Activas")) { btn ->
			previewFollowsActivePlaylist = !previewFollowsActivePlaylist
			btn.message = Component.literal(if (previewFollowsActivePlaylist) "[✓] Activas" else "[ ] Activas")
		}.bounds(previewX + halfPrevW + 4, previewY, previewW - halfPrevW - 4, 18).build()
		addRenderableWidget(previewFollowBtn)

		// Color hex EditBoxes at bottom of preview panel (con cuadro clickable para selector visual)
		val colorTop = previewY + previewH - 64
		val boxW = (previewW - 14) / 2
		val editW = (boxW - 21).coerceAtLeast(42)
		dayC1Box = makeHexBox(previewX, colorTop + 10, editW, dayColor1) { c ->
			dayColor1 = c
			if (!updatingFromPicker && activePickerSlot == 0) syncPickerFromRgb(c)
		}
		dayC2Box = makeHexBox(previewX + boxW + 10, colorTop + 10, editW, dayColor2) { c ->
			dayColor2 = c
			if (!updatingFromPicker && activePickerSlot == 1) syncPickerFromRgb(c)
		}
		nightC1Box = makeHexBox(previewX, colorTop + 42, editW, nightColor1) { c ->
			nightColor1 = c
			if (!updatingFromPicker && activePickerSlot == 2) syncPickerFromRgb(c)
		}
		nightC2Box = makeHexBox(previewX + boxW + 10, colorTop + 42, editW, nightColor2) { c ->
			nightColor2 = c
			if (!updatingFromPicker && activePickerSlot == 3) syncPickerFromRgb(c)
		}
		addRenderableWidget(dayC1Box)
		addRenderableWidget(dayC2Box)
		addRenderableWidget(nightC1Box)
		addRenderableWidget(nightC2Box)
	}

	private fun syncPickerFromRgb(rgb: Int) {
		val hsb = Color.RGBtoHSB((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, null)
		pickerHue = hsb[0]
		pickerSat = hsb[1]
		pickerBri = hsb[2]
	}

	private fun slotColor(slot: Int): Int = when (slot) {
		0 -> dayColor1
		1 -> dayColor2
		2 -> nightColor1
		else -> nightColor2
	}

	private fun slotLabel(slot: Int): String = when (slot) {
		0 -> "Día C1"
		1 -> "Día C2"
		2 -> "Noche C1"
		else -> "Noche C2"
	}

	private fun setSlotColor(slot: Int, rgb: Int) {
		val clean = rgb and 0xFFFFFF
		val hex = "%06X".format(clean)
		updatingFromPicker = true
		try {
			when (slot) {
				0 -> { dayColor1 = clean; if (::dayC1Box.isInitialized) dayC1Box.value = hex }
				1 -> { dayColor2 = clean; if (::dayC2Box.isInitialized) dayC2Box.value = hex }
				2 -> { nightColor1 = clean; if (::nightC1Box.isInitialized) nightC1Box.value = hex }
				3 -> { nightColor2 = clean; if (::nightC2Box.isInitialized) nightC2Box.value = hex }
			}
		} finally {
			updatingFromPicker = false
		}
	}

	private fun swatchBounds(slot: Int): IntArray {
		val colorTop = previewY + previewH - 64
		val boxW = (previewW - 14) / 2
		val colX = if (slot % 2 == 0) previewX else previewX + boxW + 10
		val rowY = if (slot < 2) colorTop + 10 else colorTop + 42
		val sx = colX + boxW - 18
		return intArrayOf(sx, rowY, 18, 16)
	}

	private fun pickerBounds(): IntArray {
		val pw = 196
		val ph = 148
		val colorTop = previewY + previewH - 64
		val px = (previewX - 18).coerceIn(panelX + 8, panelX + panelW - pw - 8)
		val py = (colorTop - ph - 6).coerceIn(panelY + 24, panelY + panelH - ph - 8)
		return intArrayOf(px, py, pw, ph)
	}

	private fun makeHexBox(x: Int, y: Int, w: Int, initial: Int, onUpdate: (Int) -> Unit): EditBox {
		val box = EditBox(font, x, y, w, 16, Component.empty())
		box.setMaxLength(6)
		box.value = "%06X".format(initial and 0xFFFFFF)
		box.setResponder { txt ->
			val clean = txt.trim().removePrefix("#")
			if (clean.length == 6) {
				clean.toIntOrNull(16)?.let { onUpdate(it) }
			}
		}
		return box
	}

	private fun modeButtonLabel(): String = when (modeId) {
		SpotlightShowMode.AUTO.id -> "Modo: Playlist"
		SpotlightShowMode.APAGADO.id -> "Modo: Apagado"
		else -> "Modo: Fijo ($modeId)"
	}

	private fun saveAndClose() {
		if (playlist.isEmpty()) {
			playlist.add(SpotlightShowMode.CENTRO.id)
		}
		ClientPlayNetworking.send(
			SaveArenaLightsConfigPayload(
				modeId = modeId,
				rodMode = rodMode,
				playlist = playlist.toList(),
				disabledEffects = disabledEffects.toList(),
				dayColor1 = dayColor1,
				dayColor2 = dayColor2,
				nightColor1 = nightColor1,
				nightColor2 = nightColor2,
				syncDomeWithLights = syncDomeWithLights,
				loopDurationMs = loopDurationMs
			)
		)
		onClose()
	}

	override fun renderBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
		super.renderBackground(guiGraphics, mouseX, mouseY, partialTick)

		// Main panel
		guiGraphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xE614161D.toInt())
		guiGraphics.renderOutline(panelX, panelY, panelW, panelH, 0xFF3C4458.toInt())
		guiGraphics.drawCenteredString(font, title, panelX + panelW / 2, panelY + 8, 0xFFFFFFFF.toInt())

		renderPlaylistBar(guiGraphics, mouseX, mouseY)
		renderCatalog(guiGraphics, mouseX, mouseY)
		renderPreviewCanvas(guiGraphics)

		// Color labels & clickable visual picker swatches
		val colorTop = previewY + previewH - 64
		val boxW = (previewW - 14) / 2
		guiGraphics.drawString(font, "Día C1", previewX, colorTop, 0xFFD0D6E0.toInt(), false)
		guiGraphics.drawString(font, "Día C2", previewX + boxW + 10, colorTop, 0xFFD0D6E0.toInt(), false)
		guiGraphics.drawString(font, "Noche C1", previewX, colorTop + 32, 0xFFD0D6E0.toInt(), false)
		guiGraphics.drawString(font, "Noche C2", previewX + boxW + 10, colorTop + 32, 0xFFD0D6E0.toInt(), false)

		for (slot in 0 until 4) {
			val b = swatchBounds(slot)
			val sx = b[0]; val sy = b[1]; val sw = b[2]; val sh = b[3]
			val hovered = mouseX in sx..(sx + sw) && mouseY in sy..(sy + sh)
			val selected = activePickerSlot == slot
			val borderCol = when {
				selected -> 0xFF63B3ED.toInt()
				hovered -> 0xFFFFFFFF.toInt()
				else -> 0xFF4A5058.toInt()
			}
			guiGraphics.fill(sx, sy, sx + sw, sy + sh, 0xFF000000.toInt() or (slotColor(slot) and 0xFFFFFF))
			guiGraphics.renderOutline(sx, sy, sw, sh, borderCol)
		}
	}

	override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
		super.render(guiGraphics, mouseX, mouseY, partialTick)

		if (draggingSlotIndex in playlist.indices) {
			val slotId = playlist[draggingSlotIndex]
			val dx = dragMouseX.toInt() - 42
			val dy = dragMouseY.toInt() - 12
			guiGraphics.fill(dx, dy, dx + 84, dy + 24, 0xCC2A4365.toInt())
			guiGraphics.renderOutline(dx, dy, 84, 24, 0xFF63B3ED.toInt())
			guiGraphics.drawCenteredString(font, shortName(slotId), dx + 42, dy + 8, 0xFFFFFFFF.toInt())
		}

		if (activePickerSlot in 0..3) {
			renderColorPickerPopup(guiGraphics, mouseX, mouseY)
		} else {
			for (slot in 0..3) {
				val b = swatchBounds(slot)
				if (mouseX in b[0]..(b[0] + b[2]) && mouseY in b[1]..(b[1] + b[3])) {
					guiGraphics.renderTooltip(font, Component.literal("Selector visual de color (${slotLabel(slot)})"), mouseX, mouseY)
					break
				}
			}
		}
	}

	private fun renderColorPickerPopup(g: GuiGraphics, mouseX: Int, mouseY: Int) {
		val pb = pickerBounds()
		val px = pb[0]; val py = pb[1]; val pw = pb[2]; val ph = pb[3]

		val pose = g.pose()
		pose.pushPose()
		pose.translate(0f, 0f, 300f)

		g.fill(px - 2, py - 2, px + pw + 2, py + ph + 2, 0xE80A0D14.toInt())
		g.fill(px, py, px + pw, py + ph, 0xFA141923.toInt())
		g.renderOutline(px, py, pw, ph, 0xFF63B3ED.toInt())

		g.drawString(font, "Selector Hex • ${slotLabel(activePickerSlot)}", px + 8, py + 7, 0xFFE2E8F0.toInt(), false)
		val closeX = px + pw - 20; val closeY = py + 5
		val closeHover = mouseX in closeX..(closeX + 14) && mouseY in closeY..(closeY + 12)
		g.fill(closeX, closeY, closeX + 14, closeY + 12, if (closeHover) 0xFF9B2C2C.toInt() else 0xFF2D3748.toInt())
		g.drawCenteredString(font, "X", closeX + 7, closeY + 2, 0xFFFFFFFF.toInt())

		// 1. Cuadro 2D de Saturación (X) y Brillo (Y) estilo Gremios
		val svX = px + 8; val svY = py + 22; val svW = pw - 16; val svH = 74
		val stepX = max(2, svW / 44)
		val stepY = max(2, svH / 18)
		for (ix in 0 until svW step stepX) {
			val s = ix.toFloat() / max(1, svW - 1).toFloat()
			val w = min(stepX, svW - ix)
			for (iy in 0 until svH step stepY) {
				val b = 1.0f - iy.toFloat() / max(1, svH - 1).toFloat()
				val h = min(stepY, svH - iy)
				val rgb = Color.HSBtoRGB(pickerHue, s, b) and 0xFFFFFF
				g.fill(svX + ix, svY + iy, svX + ix + w, svY + iy + h, 0xFF000000.toInt() or rgb)
			}
		}
		g.renderOutline(svX - 1, svY - 1, svW + 2, svH + 2, 0xFF4A5058.toInt())
		val curX = svX + (pickerSat * (svW - 1)).toInt()
		val curY = svY + ((1.0f - pickerBri) * (svH - 1)).toInt()
		g.fill(curX - 2, curY - 2, curX + 3, curY + 3, 0xFFFFFFFF.toInt())
		g.fill(curX - 1, curY - 1, curX + 2, curY + 2, 0xFF000000.toInt())

		// 2. Barra arcoíris de Hue debajo
		val hueX = svX; val hueY = svY + svH + 6; val hueW = svW; val hueH = 12
		for (i in 0 until hueW) {
			val hue = i.toFloat() / max(1, hueW - 1).toFloat()
			val rgb = Color.HSBtoRGB(hue, 1.0f, 1.0f) and 0xFFFFFF
			g.fill(hueX + i, hueY, hueX + i + 1, hueY + hueH, 0xFF000000.toInt() or rgb)
		}
		g.renderOutline(hueX - 1, hueY - 1, hueW + 2, hueH + 2, 0xFF4A5058.toInt())
		val indX = hueX + (pickerHue * (hueW - 1)).toInt()
		g.fill(indX - 1, hueY - 2, indX + 2, hueY + hueH + 2, 0xFFFFFFFF.toInt())
		g.fill(indX, hueY - 1, indX + 1, hueY + hueH + 1, 0xFF101010.toInt())

		// 3. Footer: preview swatch + #RRGGBB + botón Listo
		val footY = hueY + hueH + 8
		val curRgb = slotColor(activePickerSlot) and 0xFFFFFF
		g.fill(svX, footY, svX + 24, footY + 16, 0xFF000000.toInt() or curRgb)
		g.renderOutline(svX, footY, 24, 16, 0xFFCBD5E0.toInt())
		g.drawString(font, "#%06X".format(curRgb), svX + 30, footY + 4, 0xFFFFFFFF.toInt(), false)

		val okW = 54; val okX = px + pw - okW - 8
		val okHover = mouseX in okX..(okX + okW) && mouseY in footY..(footY + 16)
		g.fill(okX, footY, okX + okW, footY + 16, if (okHover) 0xFF2B6CB0.toInt() else 0xFF1E4E8C.toInt())
		g.renderOutline(okX, footY, okW, 16, 0xFF63B3ED.toInt())
		g.drawCenteredString(font, "Listo", okX + okW / 2, footY + 4, 0xFFFFFFFF.toInt())

		pose.popPose()
	}

	private fun applyPickerAt(mouseX: Double, mouseY: Double) {
		if (activePickerSlot !in 0..3) return
		val pb = pickerBounds()
		val svX = pb[0] + 8; val svY = pb[1] + 22; val svW = pb[2] - 16; val svH = 74
		val relX = ((mouseX - svX) / max(1, svW - 1).toDouble()).toFloat().coerceIn(0f, 1f)
		if (pickerDragMode == 2) {
			pickerHue = relX
		} else {
			val relY = ((mouseY - svY) / max(1, svH - 1).toDouble()).toFloat().coerceIn(0f, 1f)
			pickerSat = relX
			pickerBri = 1.0f - relY
		}
		val rgb = Color.HSBtoRGB(pickerHue, pickerSat, pickerBri) and 0xFFFFFF
		setSlotColor(activePickerSlot, rgb)
	}

	private fun renderPlaylistBar(g: GuiGraphics, mouseX: Int, mouseY: Int) {
		val barX = panelX + 10
		val barW = panelW - 20
		g.drawString(font, "Playlist Activa (${playlist.size} loops - arrastra o usa </> para ordenar):", barX, playlistY - 10, 0xFFA0AEC0.toInt(), false)
		g.fill(barX, playlistY, barX + barW, playlistY + playlistH, 0xFF0D1017.toInt())
		g.renderOutline(barX, playlistY, barW, playlistH, 0xFF2D3748.toInt())

		g.enableScissor(barX + 1, playlistY + 1, barX + barW - 1, playlistY + playlistH - 1)
		val slotW = 94
		val arrowW = 16
		val step = slotW + arrowW
		val now = System.currentTimeMillis()
		val activeLoopIdx = if (playlist.isEmpty()) -1 else ((now - previewStartMs) / loopDurationMs.toLong()).toInt() % playlist.size

		for (i in playlist.indices) {
			val sx = barX + 6 + i * step - playlistScrollX
			val sy = playlistY + 5
			val sh = playlistH - 10
			if (sx + slotW < barX || sx > barX + barW) continue

			val id = playlist[i]
			val isPlaying = (modeId == SpotlightShowMode.AUTO.id && i == activeLoopIdx)
			val bg = if (isPlaying) 0xFF1E3A5F.toInt() else 0xFF1A202C.toInt()
			val border = if (isPlaying) 0xFF63B3ED.toInt() else 0xFF4A5568.toInt()
			g.fill(sx, sy, sx + slotW, sy + sh, bg)
			g.renderOutline(sx, sy, slotW, sh, border)

			g.drawString(font, "${i + 1}. ${shortName(id)}", sx + 4, sy + 4, 0xFFFFFFFF.toInt(), false)
			// Mini action labels: < > X
			g.drawString(font, "<", sx + 4, sy + 20, if (i > 0) 0xFF90CDF4.toInt() else 0xFF4A5568.toInt(), false)
			g.drawString(font, ">", sx + 18, sy + 20, if (i < playlist.lastIndex) 0xFF90CDF4.toInt() else 0xFF4A5568.toInt(), false)
			g.drawString(font, "Quitar", sx + slotW - 36, sy + 20, 0xFFFC8181.toInt(), false)

			if (i < playlist.lastIndex) {
				g.drawCenteredString(font, "➔", sx + slotW + arrowW / 2, sy + 12, 0xFF63B3ED.toInt())
			}
		}
		g.disableScissor()
	}

	private fun renderCatalog(g: GuiGraphics, mouseX: Int, mouseY: Int) {
		g.fill(catalogX, catalogY, catalogX + catalogW, catalogY + catalogH, 0xFF0D1017.toInt())
		g.renderOutline(catalogX, catalogY, catalogW, catalogH, 0xFF2D3748.toInt())

		val list = if (activeTab == 0) SpotlightShowMode.twoColorEffects else SpotlightShowMode.rgbEffects
		val rowH = 22
		val maxScroll = max(0, list.size * rowH - catalogH + 6)
		catalogScrollY = catalogScrollY.coerceIn(0, maxScroll)

		g.enableScissor(catalogX + 1, catalogY + 1, catalogX + catalogW - 1, catalogY + catalogH - 1)
		for (i in list.indices) {
			val mode = list[i]
			val ry = catalogY + 3 + i * rowH - catalogScrollY
			if (ry + rowH < catalogY || ry > catalogY + catalogH) continue

			val hovered = mouseX in catalogX..(catalogX + catalogW) && mouseY in ry..(ry + rowH - 2)
			val isDisabled = disabledEffects.contains(mode.id)
			val isSelectedPreview = !previewFollowsActivePlaylist && previewCurrEffect == mode.id
			val bg = when {
				isSelectedPreview -> 0xFF1E3A5F.toInt()
				hovered -> 0xFF232B3A.toInt()
				else -> 0xFF161B24.toInt()
			}
			g.fill(catalogX + 3, ry, catalogX + catalogW - 3, ry + rowH - 2, bg)

			val nameColor = if (isDisabled) 0xFF718096.toInt() else 0xFFE2E8F0.toInt()
			g.drawString(font, mode.label, catalogX + 8, ry + 6, nameColor, false)

			// + Playlist button
			val addX = catalogX + catalogW - 86
			g.fill(addX, ry + 2, addX + 36, ry + rowH - 4, 0xFF2B6CB0.toInt())
			g.drawCenteredString(font, "+ Loop", addX + 18, ry + 6, 0xFFFFFFFF.toInt())

			// Enable/Disable toggle button
			val togX = catalogX + catalogW - 46
			val togCol = if (isDisabled) 0xFF742A2A.toInt() else 0xFF276749.toInt()
			g.fill(togX, ry + 2, togX + 38, ry + rowH - 4, togCol)
			g.drawCenteredString(font, if (isDisabled) "OFF" else "ON", togX + 19, ry + 6, 0xFFFFFFFF.toInt())
		}
		g.disableScissor()
	}

	private fun renderPreviewCanvas(g: GuiGraphics) {
		val boxTop = previewY + 22
		val boxH = previewH - 92
		g.fill(previewX, boxTop, previewX + previewW, boxTop + boxH, 0xFF07090E.toInt())
		g.renderOutline(previewX, boxTop, previewW, boxH, 0xFF2D3748.toInt())

		val now = System.currentTimeMillis()
		if (previewFollowsActivePlaylist) {
			val targetEffect = when {
				modeId == SpotlightShowMode.APAGADO.id -> SpotlightShowMode.APAGADO.id
				modeId != SpotlightShowMode.AUTO.id -> modeId
				playlist.isNotEmpty() -> {
					val activeList = playlist.filter { !disabledEffects.contains(it) }.ifEmpty { playlist }
					val idx = ((now - previewStartMs) / loopDurationMs.toLong()).toInt() % activeList.size
					activeList[idx]
				}
				else -> previewCurrEffect
			}
			if (targetEffect != previewCurrEffect) {
				previewPrevEffect = previewCurrEffect
				previewCurrEffect = targetEffect
				previewBlendStartMs = now
			}
		}

		val blendT = ((now - previewBlendStartMs) / 850f).coerceIn(0f, 1f)
		val timeSec = (now - previewStartMs) / 1000.0
		val c1 = if (previewNight) nightColor1 else dayColor1
		val c2 = if (previewNight) nightColor2 else dayColor2
		val c1R = ((c1 shr 16) and 0xFF) / 255f
		val c1G = ((c1 shr 8) and 0xFF) / 255f
		val c1B = (c1 and 0xFF) / 255f
		val c2R = ((c2 shr 16) and 0xFF) / 255f
		val c2G = ((c2 shr 8) and 0xFF) / 255f
		val c2B = (c2 and 0xFF) / 255f

		val cx = previewX + previewW / 2f
		val cy = boxTop + boxH / 2f + 4f
		val radius = min(previewW, boxH) * 0.36f
		val numLights = 16

		g.drawCenteredString(
			font,
			if (previewCurrEffect == SpotlightShowMode.APAGADO.id) "APAGADO" else "Preview: ${shortName(previewCurrEffect)}",
			previewX + previewW / 2,
			boxTop + 5,
			0xFFA0AEC0.toInt()
		)

		if (previewCurrEffect == SpotlightShowMode.APAGADO.id) return

		for (i in 0 until numLights) {
			val theta = (i.toDouble() / numLights.toDouble()) * 2.0 * PI
			val sx = cx + cos(theta).toFloat() * radius
			val sy = cy + sin(theta).toFloat() * radius
			val sample = SpotlightEffectEvaluator.evaluateBlended(
				prevEffectId = previewPrevEffect,
				currEffectId = previewCurrEffect,
				blendT = blendT,
				sIdx = i,
				numSpotlights = numLights,
				timeSec = timeSec,
				minR = 32f,
				c1R = c1R, c1G = c1G, c1B = c1B,
				c2R = c2R, c2G = c2G, c2B = c2B
			)
			val inwardFrac = (sample.inward / 32f).coerceIn(-0.85f, 1.15f)
			val ex = cx + cos(theta).toFloat() * radius * (1f - inwardFrac * 0.9f) - sin(theta).toFloat() * sample.lat * radius * 0.45f
			val ey = cy + sin(theta).toFloat() * radius * (1f - inwardFrac * 0.9f) + cos(theta).toFloat() * sample.lat * radius * 0.45f - 10f

			val alpha = (sample.intensity * 230f).toInt().coerceIn(15, 255)
			val ri = (sample.r * 255f).toInt().coerceIn(0, 255)
			val gi = (sample.g * 255f).toInt().coerceIn(0, 255)
			val bi = (sample.b * 255f).toInt().coerceIn(0, 255)
			val argb = (alpha shl 24) or (ri shl 16) or (gi shl 8) or bi

			drawThickLine(g, sx, sy, ex, ey, (1.5f + sample.widthMult * 0.9f).toInt().coerceIn(1, 4), argb)
			g.fill((sx - 2).toInt(), (sy - 2).toInt(), (sx + 2).toInt(), (sy + 2).toInt(), 0xFF000000.toInt() or (ri shl 16) or (gi shl 8) or bi)
		}
	}

	private fun drawThickLine(g: GuiGraphics, x0: Float, y0: Float, x1: Float, y1: Float, thickness: Int, argb: Int) {
		val steps = max(absDiff(x1 - x0), absDiff(y1 - y0)).toInt().coerceAtLeast(1)
		val half = thickness / 2
		for (s in 0..steps) {
			val t = s.toFloat() / steps.toFloat()
			val px = (x0 + (x1 - x0) * t).toInt()
			val py = (y0 + (y1 - y0) * t).toInt()
			g.fill(px - half, py - half, px + half + 1, py + half + 1, argb)
		}
	}

	private fun absDiff(v: Float): Float = if (v < 0f) -v else v

	override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
		if (activePickerSlot in 0..3 && button == 0) {
			val pb = pickerBounds()
			val px = pb[0]; val py = pb[1]; val pw = pb[2]; val ph = pb[3]
			if (mouseX in px.toDouble()..(px + pw).toDouble() && mouseY in py.toDouble()..(py + ph).toDouble()) {
				val closeX = px + pw - 20; val closeY = py + 5
				val svX = px + 8; val svY = py + 22; val svW = pw - 16; val svH = 74
				val hueY = svY + svH + 6; val hueH = 12
				val footY = hueY + hueH + 8
				val okW = 54; val okX = px + pw - okW - 8
				when {
					mouseX in closeX.toDouble()..(closeX + 14).toDouble() && mouseY in closeY.toDouble()..(closeY + 12).toDouble() -> {
						activePickerSlot = -1
						pickerDragMode = 0
					}
					mouseX in okX.toDouble()..(okX + okW).toDouble() && mouseY in footY.toDouble()..(footY + 16).toDouble() -> {
						activePickerSlot = -1
						pickerDragMode = 0
					}
					mouseX in svX.toDouble()..(svX + svW).toDouble() && mouseY in svY.toDouble()..(svY + svH).toDouble() -> {
						pickerDragMode = 1
						applyPickerAt(mouseX, mouseY)
					}
					mouseX in svX.toDouble()..(svX + svW).toDouble() && mouseY in hueY.toDouble()..(hueY + hueH).toDouble() -> {
						pickerDragMode = 2
						applyPickerAt(mouseX, mouseY)
					}
				}
				return true
			}
			// Clicking a swatch switches directly to that slot; clicking anywhere else closes popup
			for (slot in 0..3) {
				val b = swatchBounds(slot)
				if (mouseX in b[0].toDouble()..(b[0] + b[2]).toDouble() && mouseY in b[1].toDouble()..(b[1] + b[3]).toDouble()) {
					activePickerSlot = slot
					syncPickerFromRgb(slotColor(slot))
					return true
				}
			}
			activePickerSlot = -1
			pickerDragMode = 0
			return true
		}

		if (button == 0) {
			for (slot in 0..3) {
				val b = swatchBounds(slot)
				if (mouseX in b[0].toDouble()..(b[0] + b[2]).toDouble() && mouseY in b[1].toDouble()..(b[1] + b[3]).toDouble()) {
					activePickerSlot = slot
					pickerDragMode = 0
					syncPickerFromRgb(slotColor(slot))
					return true
				}
			}
		}

		if (super.mouseClicked(mouseX, mouseY, button)) return true

		val barX = panelX + 10
		val barW = panelW - 20
		if (mouseX in barX.toDouble()..(barX + barW).toDouble() && mouseY in playlistY.toDouble()..(playlistY + playlistH).toDouble()) {
			val slotW = 94
			val step = slotW + 16
			for (i in playlist.indices) {
				val sx = barX + 6 + i * step - playlistScrollX
				val sy = playlistY + 5
				val sh = playlistH - 10
				if (mouseX in sx.toDouble()..(sx + slotW).toDouble() && mouseY in sy.toDouble()..(sy + sh).toDouble()) {
					if (mouseY >= sy + 16) {
						when {
							mouseX <= sx + 14 && i > 0 -> {
								val tmp = playlist[i - 1]
								playlist[i - 1] = playlist[i]
								playlist[i] = tmp
								return true
							}
							mouseX in (sx + 15).toDouble()..(sx + 28).toDouble() && i < playlist.lastIndex -> {
								val tmp = playlist[i + 1]
								playlist[i + 1] = playlist[i]
								playlist[i] = tmp
								return true
							}
							mouseX >= sx + slotW - 40 -> {
								playlist.removeAt(i)
								return true
							}
						}
					}
					draggingSlotIndex = i
					dragMouseX = mouseX
					dragMouseY = mouseY
					return true
				}
			}
		}

		// Catalog click
		if (mouseX in catalogX.toDouble()..(catalogX + catalogW).toDouble() && mouseY in catalogY.toDouble()..(catalogY + catalogH).toDouble()) {
			val list = if (activeTab == 0) SpotlightShowMode.twoColorEffects else SpotlightShowMode.rgbEffects
			val rowH = 22
			val idx = ((mouseY - (catalogY + 3) + catalogScrollY) / rowH).toInt()
			if (idx in list.indices) {
				val mode = list[idx]
				val addX = catalogX + catalogW - 86
				val togX = catalogX + catalogW - 46
				when {
					mouseX in addX.toDouble()..(addX + 36).toDouble() -> {
						playlist.add(mode.id)
						previewEffect(mode.id)
						return true
					}
					mouseX in togX.toDouble()..(togX + 38).toDouble() -> {
						if (!disabledEffects.add(mode.id)) {
							disabledEffects.remove(mode.id)
						}
						return true
					}
					else -> {
						previewFollowsActivePlaylist = false
						if (::previewFollowBtn.isInitialized) {
							previewFollowBtn.message = Component.literal("[ ] Activas")
						}
						previewEffect(mode.id)
						if (modeId != SpotlightShowMode.AUTO.id && modeId != SpotlightShowMode.APAGADO.id) {
							modeId = mode.id
						}
						return true
					}
				}
			}
		}

		return false
	}

	private fun previewEffect(id: String) {
		if (id != SpotlightShowMode.APAGADO.id && id != SpotlightShowMode.AUTO.id) {
			lastNonOffEffect = id
		}
		if (id != previewCurrEffect) {
			previewPrevEffect = previewCurrEffect
			previewCurrEffect = id
			previewBlendStartMs = System.currentTimeMillis()
		}
	}

	override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
		if (activePickerSlot in 0..3 && pickerDragMode != 0) {
			applyPickerAt(mouseX, mouseY)
			return true
		}
		if (draggingSlotIndex in playlist.indices) {
			dragMouseX = mouseX
			dragMouseY = mouseY
			return true
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
	}

	override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
		if (pickerDragMode != 0) {
			pickerDragMode = 0
			return true
		}
		if (draggingSlotIndex in playlist.indices) {
			val barX = panelX + 10
			val step = 110
			val targetIdx = ((mouseX - (barX + 6) + playlistScrollX) / step).toInt().coerceIn(0, playlist.lastIndex)
			if (targetIdx != draggingSlotIndex) {
				val moved = playlist.removeAt(draggingSlotIndex)
				playlist.add(targetIdx, moved)
			}
			draggingSlotIndex = -1
			return true
		}
		return super.mouseReleased(mouseX, mouseY, button)
	}

	override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
		if (keyCode == GLFW.GLFW_KEY_ESCAPE && activePickerSlot in 0..3) {
			activePickerSlot = -1
			pickerDragMode = 0
			return true
		}
		return super.keyPressed(keyCode, scanCode, modifiers)
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
		val barX = panelX + 10
		val barW = panelW - 20
		if (mouseX in barX.toDouble()..(barX + barW).toDouble() && mouseY in playlistY.toDouble()..(playlistY + playlistH).toDouble()) {
			val maxX = max(0, playlist.size * 110 - barW + 20)
			playlistScrollX = (playlistScrollX - (scrollY * 32).toInt()).coerceIn(0, maxX)
			return true
		}
		if (mouseX in catalogX.toDouble()..(catalogX + catalogW).toDouble() && mouseY in catalogY.toDouble()..(catalogY + catalogH).toDouble()) {
			catalogScrollY -= (scrollY * 22).toInt()
			return true
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
	}

	override fun isPauseScreen(): Boolean = false

	private fun shortName(id: String): String {
		return SpotlightShowMode.fromId(id)?.label?.take(12) ?: id.take(12)
	}

	companion object {
		fun openWithState(state: ArenaLightsStatePayload) {
			Minecraft.getInstance().setScreen(ArenaLightsEditorScreen(state))
		}
	}
}
