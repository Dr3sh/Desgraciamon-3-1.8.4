package com.addoncobblemon.legendarybestiary

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels
import com.cobblemon.mod.common.api.types.ElementalType
import com.cobblemon.mod.common.client.gui.ProfileTransformType
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.entity.PoseType
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Renderable
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import org.joml.Quaternionf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LegendaryBestiaryScreen : Screen(TextCatalog.component("screen.title")) {
    private val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Caracas"))
    private val openedAtNanos = System.nanoTime()

    private fun openingProgress(): Float {
        return Math.min(1.0F, (System.nanoTime() - openedAtNanos) / 280_000_000.0F)
    }

    private var filter = BestiaryFilter.ALL
    private var scrollOffset = 0
    private lateinit var searchBox: EditBox

    // Selected entry & 3D preview model
    private var selectedSpeciesId: String? = null
    private var selectedPokemon: Pokemon? = null
    private var currentAspects: Set<String> = emptySet()
    private var floatingState: FloatingState? = null
    private var isShiny = false
    private var rotationY = 25f
    private var isAutoRotating = true
    private var modelZoom = 1.0f

    // Drag interaction on 3D viewport
    private var isDraggingModel = false
    private var lastDragX = 0.0
    private var totalDragDelta = 0.0

    // Geometry & Responsive Canonical Scale
    companion object {
        private const val BASE_W = 560f
        private const val BASE_H = 340f
    }
    private var uiScale = 1.0f
    private var renderX = 0f
    private var renderY = 0f

    private var guiLeft = 0
    private var guiTop = 0
    private var guiWidth = 0
    private var guiHeight = 0
    private var listX = 0
    private var listY = 0
    private var listWidth = 0
    private var listHeight = 0
    private var rightX = 0
    private var rightY = 0
    private var rightWidth = 0
    private var rightHeight = 0
    private var viewportX = 0
    private var viewportY = 0
    private var viewportWidth = 0
    private var viewportHeight = 0

    private fun enableScaledScissor(guiGraphics: GuiGraphics, x1: Int, y1: Int, x2: Int, y2: Int) {
        val sx1 = (renderX + x1 * uiScale).toInt()
        val sy1 = (renderY + y1 * uiScale).toInt()
        val sx2 = (renderX + x2 * uiScale).toInt()
        val sy2 = (renderY + y2 * uiScale).toInt()
        guiGraphics.enableScissor(sx1, sy1, sx2, sy2)
    }

    private val itemHeight = 36
    private var visibleCount = 5

    private lateinit var shinyButton: ModernButton

    override fun init() {
        val margin = 8f
        uiScale = minOf(1.0f, (width - margin * 2) / BASE_W, (height - margin * 2) / BASE_H)
        renderX = (width - BASE_W * uiScale) / 2f
        renderY = (height - BASE_H * uiScale) / 2f

        guiWidth = BASE_W.toInt()
        guiHeight = BASE_H.toInt()
        guiLeft = 0
        guiTop = 0

        listX = 12
        listY = 72
        listWidth = 190
        listHeight = guiHeight - 84

        rightX = listX + listWidth + 10
        rightY = 32
        rightWidth = guiWidth - rightX - 12
        rightHeight = guiHeight - 44

        viewportX = rightX + 6
        viewportY = rightY + 38
        viewportWidth = rightWidth - 12
        viewportHeight = (rightHeight - 118).coerceAtLeast(80)

        // Close button (sleek danger icon button)
        addRenderableWidget(
            ModernButton(
                guiWidth - 21, 7, 14, 14,
                Component.literal("X"),
                isDanger = true
            ) { onClose() }
        )

        // Filter tab buttons (styled pills)
        val tabY = 27
        val tabWidth = (listWidth - 6) / 3
        listOf(
            Triple("filter.all", BestiaryFilter.ALL, 0),
            Triple("filter.captured", BestiaryFilter.CAPTURED, 1),
            Triple("filter.not_captured", BestiaryFilter.NOT_CAPTURED, 2)
        ).forEach { (label, value, idx) ->
            val bx = listX + idx * (tabWidth + 3)
            addRenderableWidget(
                ModernButton(
                    bx, tabY, tabWidth, 17,
                    TextCatalog.component(label),
                    isToggled = { filter == value }
                ) {
                    filter = value
                    scrollOffset = 0
                }
            )
        }

        // Custom search edit box (borderless, handled by our modern frame)
        searchBox = EditBox(font, listX + 5, 51, listWidth - 10, 14, TextCatalog.component("search.label"))
        searchBox.setBordered(false)
        searchBox.setMaxLength(48)
        searchBox.setHint(TextCatalog.component("search.hint"))
        searchBox.setResponder { scrollOffset = 0 }
        addRenderableWidget(searchBox)

        // 3D Viewport controls: Shiny toggle only
        shinyButton = ModernButton(
            viewportX + viewportWidth - 84, viewportY + 6, 78, 16,
            TextCatalog.component("button.shiny"),
            isToggled = { isShiny }
        ) {
            isShiny = !isShiny
            updateSelectedPokemon()
        }
        addRenderableWidget(shinyButton)

        // Initial selection
        val entries = getFilteredEntries()
        if (selectedSpeciesId == null && entries.isNotEmpty()) {
            selectEntry(entries.first())
        } else if (selectedPokemon == null && selectedSpeciesId != null) {
            updateSelectedPokemon()
        }
    }

    private fun getFilteredEntries(): List<LegendaryBestiaryEntry> {
        val query = if (::searchBox.isInitialized) searchBox.value.trim().lowercase() else ""
        return LegendaryBestiaryClientData.entries.filter { entry ->
            val matchesFilter = when (filter) {
                BestiaryFilter.ALL -> true
                BestiaryFilter.CAPTURED -> entry.captured
                BestiaryFilter.NOT_CAPTURED -> !entry.captured
            }
            matchesFilter && (query.isEmpty() || entry.name.lowercase().contains(query) || entry.speciesId.lowercase().contains(query))
        }
    }

    private fun selectEntry(entry: LegendaryBestiaryEntry) {
        selectedSpeciesId = entry.speciesId
        modelZoom = 1.0f
        updateSelectedPokemon()
    }

    private fun updateSelectedPokemon() {
        val id = selectedSpeciesId ?: return
        val baseId = id.substringBefore('#')
        val rawForm = if (id.contains('#')) id.substringAfter('#').lowercase().trim() else ""
        val formName = LegendaryBestiaryData.normalizeFormKey(rawForm)

        val builtAspects = linkedSetOf<String>()

        // Explicitly map regional forms to their Cobblemon aspects
        if (formName.isNotBlank()) {
            builtAspects.add(formName)
            when (formName) {
                "galar", "galarian" -> {
                    builtAspects.add("galar")
                    builtAspects.add("galarian")
                }
                "hisui", "hisuian" -> {
                    builtAspects.add("hisui")
                    builtAspects.add("hisuian")
                }
                "alola", "alolan" -> {
                    builtAspects.add("alola")
                    builtAspects.add("alolan")
                }
                "paldea", "paldean" -> {
                    builtAspects.add("paldea")
                    builtAspects.add("paldean")
                }
            }
        }
        if (isShiny) {
            builtAspects.add("shiny")
        }

        val propStr = buildString {
            append("species=\"$baseId\" ")
            if (formName.isNotBlank()) {
                append("form=\"$formName\" ")
                when (formName) {
                    "galar", "galarian" -> append("galarian ")
                    "hisui", "hisuian" -> append("hisuian ")
                    "alola", "alolan" -> append("alolan ")
                    "paldea", "paldean" -> append("paldean ")
                    else -> append("$formName ")
                }
            }
            if (isShiny) append("shiny=yes ")
            append("level=50")
        }

        val parsed = runCatching { PokemonProperties.parse(propStr).create() }.getOrNull()
        val p = parsed ?: runCatching {
            val res = ResourceLocation.tryParse(baseId) ?: return@runCatching null
            val species = PokemonSpecies.getByIdentifier(res) ?: return@runCatching null
            species.create(50).also { it.shiny = isShiny }
        }.getOrNull()

        if (p != null) {
            val matchedForm = p.species.forms.firstOrNull {
                it.formOnlyShowdownId().equals(formName, true) ||
                it.name.equals(formName, true) ||
                it.formOnlyShowdownId().equals(rawForm, true) ||
                it.name.equals(rawForm, true)
            }
            if (matchedForm != null) {
                p.form = matchedForm
                builtAspects.addAll(matchedForm.aspects)
            }
            builtAspects.addAll(p.aspects)
            p.forcedAspects = builtAspects.toSet()
        }

        selectedPokemon = p
        currentAspects = builtAspects
        floatingState = FloatingState().also {
            it.currentAspects = currentAspects
        }
    }

    private fun playCry() {
        val id = selectedSpeciesId ?: return
        val baseId = id.substringBefore('#')
        val cleanPath = if (':' in baseId) baseId.substringAfter(':') else baseId
        val soundId = ResourceLocation.fromNamespaceAndPath("cobblemon", "pokemon.$cleanPath.cry")
        val soundEvent = SoundEvent.createVariableRangeEvent(soundId)
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(soundEvent, 1.0f, 1.0f))

        floatingState?.let { st ->
            st.activeAnimations.clear()
            st.addFirstAnimation(setOf("cry"))
        }
    }

    /**
     * Prevents Minecraft from re-blurring over GUI elements.
     * We explicitly call renderBlurredBackground once before drawing our GUI.
     */
    override fun renderBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // No-op to avoid double/delayed blur passes
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val localMouseX = ((mouseX - renderX) / uiScale).toInt()
        val localMouseY = ((mouseY - renderY) / uiScale).toInt()

        // 1. Blur the 3D game world in the background
        renderBlurredBackground(partialTick)

        // 2. Dark transparent backdrop on top of the blurred world
        guiGraphics.fill(0, 0, width, height, 0xB8070B10.toInt())

        val matrices = guiGraphics.pose()
        matrices.pushPose()
        matrices.translate(renderX, renderY, 0.0f)
        matrices.scale(uiScale, uiScale, 1.0f)

        // Opening animation ease (Guild pop-in)
        val progress = ((System.nanoTime() - openedAtNanos) / 280_000_000.0f).coerceIn(0f, 1f)
        if (progress < 1.0f) {
            val ease = 1f - (1f - progress).let { it * it * it }
            val scale = 0.08f + 0.92f * ease
            val cx = BASE_W / 2f
            val cy = BASE_H / 2f
            matrices.translate(cx, cy, 0f)
            matrices.scale(scale, scale, 1f)
            matrices.translate(-cx, -cy, 0f)
        }

        // 3. Main modal window container (deep slate glass + glowing outline)
        guiGraphics.fill(guiLeft, guiTop, guiLeft + guiWidth, guiTop + guiHeight, 0xF5101722.toInt())
        guiGraphics.renderOutline(guiLeft, guiTop, guiWidth, guiHeight, 0xFF233547.toInt())
        guiGraphics.renderOutline(guiLeft + 1, guiTop + 1, guiWidth - 2, guiHeight - 2, 0x1A5BC0A7.toInt())

        // Header top accent line (celestial gold-emerald gradient)
        guiGraphics.fill(guiLeft + 2, guiTop + 1, guiLeft + guiWidth - 2, guiTop + 3, 0xFFFFD15C.toInt())

        // Header Title & Brand
        val title = Component.literal("").append(TextCatalog.component("header.title")).withStyle { it.withColor(0xFFFFE58F.toInt()).withBold(true) }
        guiGraphics.drawString(font, title, guiLeft + 12, guiTop + 10, 0xFFFFE58F.toInt(), false)

        val subtitle = Component.literal("Crónicas Sagradas").withStyle { it.withColor(0xFF7E918D.toInt()) }
        guiGraphics.drawString(font, subtitle, guiLeft + 16 + font.width(title), guiTop + 10, 0xFF7E918D.toInt(), false)

        val allEntries = LegendaryBestiaryClientData.entries
        val capturedCount = allEntries.count { it.captured }
        val totalCount = allEntries.size
        val summaryText = TextCatalog.string("summary", "captured" to capturedCount, "total" to totalCount)
        val summaryWidth = font.width(summaryText)
        val badgeX = guiLeft + guiWidth - summaryWidth - 36
        val badgeY = guiTop + 7
        guiGraphics.fill(badgeX - 4, badgeY, badgeX + summaryWidth + 4, badgeY + 15, 0xFF14202B.toInt())
        guiGraphics.renderOutline(badgeX - 4, badgeY, summaryWidth + 8, 15, 0xFF4A6B82.toInt())
        guiGraphics.drawString(font, summaryText, badgeX, badgeY + 3, 0xFF68D391.toInt(), false)

        // Search Bar custom frame
        val searchBorder = if (searchBox.isFocused) 0xFF5BC0A7.toInt() else 0xFF233547.toInt()
        guiGraphics.fill(listX, 48, listX + listWidth, 67, 0xF00D141C.toInt())
        guiGraphics.renderOutline(listX, 48, listWidth, 19, searchBorder)

        // Auto-rotation when not dragging
        if (isAutoRotating) {
            rotationY = (rotationY + 0.6f) % 360f
        }

        // Render Left Column: Pokémon List
        renderPokemonList(guiGraphics, localMouseX, localMouseY)

        // Render Right Column: Showcase Card (3D Model + Info)
        renderShowcaseCard(guiGraphics, localMouseX, localMouseY, partialTick)

        // 4. Render all interactive widgets (ModernButtons, EditBox) with local coordinates on front layer
        matrices.pushPose()
        matrices.translate(0.0, 0.0, 500.0)
        children().filterIsInstance<Renderable>().forEach {
            it.render(guiGraphics, localMouseX, localMouseY, partialTick)
        }
        matrices.popPose()

        matrices.popPose()
    }

    private fun renderPokemonList(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val entries = getFilteredEntries()
        visibleCount = ((listHeight - 4) / itemHeight).coerceAtLeast(1)
        val maxOffset = (entries.size - visibleCount).coerceAtLeast(0)
        scrollOffset = scrollOffset.coerceIn(0, maxOffset)

        // List frame background
        guiGraphics.fill(listX, listY, listX + listWidth, listY + listHeight, 0xE00C121A.toInt())
        guiGraphics.renderOutline(listX, listY, listWidth, listHeight, 0xFF1E2D3D.toInt())

        if (entries.isEmpty()) {
            val emptyMsg = if (LegendaryBestiaryClientData.entries.isEmpty()) "empty.waiting" else "empty.results"
            guiGraphics.drawCenteredString(font, TextCatalog.component(emptyMsg), listX + listWidth / 2, listY + listHeight / 2 - 4, 0xFF718096.toInt())
            return
        }

        // Ensure a selected entry is always active
        if (selectedSpeciesId == null || entries.none { it.speciesId == selectedSpeciesId }) {
            selectEntry(entries.first())
        }

        val visibleEntries = entries.drop(scrollOffset).take(visibleCount)
        visibleEntries.forEachIndexed { idx, entry ->
            val y = listY + 2 + idx * itemHeight
            val isSelected = entry.speciesId == selectedSpeciesId
            val isHovered = mouseX >= listX + 2 && mouseX < listX + listWidth - 6 && mouseY >= y && mouseY < y + itemHeight - 2

            // Card background
            val cardBg = when {
                isSelected -> 0xFF20364C.toInt()
                isHovered -> 0xFF192736.toInt()
                else -> 0xFF121B24.toInt()
            }
            guiGraphics.fill(listX + 2, y, listX + listWidth - 6, y + itemHeight - 2, cardBg)
            if (isSelected) {
                guiGraphics.renderOutline(listX + 2, y, listWidth - 8, itemHeight - 2, 0xFF5BC0A7.toInt())
            }

            // Left vertical accent stripe
            val accentColor = when {
                isSelected -> 0xFF5BC0A7.toInt()
                entry.captured -> 0xFF48BB78.toInt()
                else -> 0xFF4A5568.toInt()
            }
            guiGraphics.fill(listX + 2, y, listX + 5, y + itemHeight - 2, accentColor)

            // Pokémon name & Dex number
            val res = ResourceLocation.tryParse(entry.speciesId.substringBefore("#"))
            val species = res?.let { PokemonSpecies.getByIdentifier(it) }
            val dexStr = species?.let { "#%04d".format(it.nationalPokedexNumber) } ?: ""

            guiGraphics.drawString(font, dexStr, listX + 8, y + 4, 0xFF7CD4FD.toInt(), false)
            val nameColor = if (isSelected) 0xFFFFFFFF.toInt() else 0xFFE2E8F0.toInt()
            guiGraphics.drawString(font, trim(entry.name, listWidth - 55), listX + 42, y + 4, nameColor, false)

            // Status label
            if (entry.captured) {
                val ownerStr = if (entry.owner.isNotBlank()) "" + trim(entry.owner, listWidth - 36) else "Registrado"
                guiGraphics.drawString(font, ownerStr, listX + 8, y + 18, 0xFF68D391.toInt(), false)
            } else {
                guiGraphics.drawString(font, "Disponible", listX + 8, y + 18, 0xFFA0AEC0.toInt(), false)
            }
        }

        // Scrollbar track & thumb
        if (entries.size > visibleCount) {
            val trackX = listX + listWidth - 5
            val trackY = listY + 2
            val trackH = listHeight - 4
            guiGraphics.fill(trackX, trackY, trackX + 3, trackY + trackH, 0x40000000.toInt())

            val thumbHeight = ((visibleCount.toFloat() / entries.size) * trackH).toInt().coerceAtLeast(12)
            val thumbY = trackY + ((scrollOffset.toFloat() / maxOffset) * (trackH - thumbHeight)).toInt()
            guiGraphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbHeight, 0xFF5BC0A7.toInt())
        }
    }

    private fun renderShowcaseCard(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val entry = getFilteredEntries().find { it.speciesId == selectedSpeciesId }
            ?: LegendaryBestiaryClientData.entries.find { it.speciesId == selectedSpeciesId }
            ?: return

        val res = ResourceLocation.tryParse(entry.speciesId.substringBefore("#"))
        val species = res?.let { PokemonSpecies.getByIdentifier(it) }

        // Showcase main background
        guiGraphics.fill(rightX, rightY, rightX + rightWidth, rightY + rightHeight, 0xE8101822.toInt())
        guiGraphics.renderOutline(rightX, rightY, rightWidth, rightHeight, 0xFF243647.toInt())

        // Row 1 (rightY + 5): Pokémon Display Name (auto-scaled if long) + Right-aligned Dex #
        val dexText = species?.let { "#%04d".format(it.nationalPokedexNumber) } ?: ""
        val dexW = font.width(dexText)
        val dexX = rightX + rightWidth - 8 - dexW
        guiGraphics.drawString(font, dexText, dexX, rightY + 6, 0xFF90CDF4.toInt(), false)

        val nameComponent = Component.literal(entry.name).withStyle { it.withBold(true) }
        val nameW = font.width(nameComponent)
        val maxNameW = (dexX - (rightX + 8) - 8).coerceAtLeast(40)
        if (nameW > maxNameW) {
            val scale = maxNameW.toFloat() / nameW.toFloat()
            val pose = guiGraphics.pose()
            pose.pushPose()
            pose.translate((rightX + 8).toFloat(), (rightY + 6).toFloat(), 0f)
            pose.scale(scale, scale, 1f)
            guiGraphics.drawString(font, nameComponent, 0, 0, 0xFFF6E05E.toInt(), false)
            pose.popPose()
        } else {
            guiGraphics.drawString(font, nameComponent, rightX + 8, rightY + 6, 0xFFF6E05E.toInt(), false)
        }

        // Row 2 (rightY + 21): Rarity badge + Elemental Type Badges on separate row
        val isMythical = species?.labels?.any { it.equals(CobblemonPokemonLabels.MYTHICAL, true) } == true
        val rarityText = if (isMythical) "MÍTICO" else "LEGENDARIO"
        val rarityBg = if (isMythical) 0xFFB83280.toInt() else 0xFFD69E2E.toInt()
        val rarityW = font.width(rarityText) + 8
        val rarityX = rightX + 8
        val rarityY = rightY + 21
        guiGraphics.fill(rarityX, rarityY, rarityX + rarityW, rarityY + 11, rarityBg)
        guiGraphics.drawString(font, rarityText, rarityX + 4, rarityY + 2, 0xFFFFFFFF.toInt(), false)

        var typeX = rarityX + rarityW + 6
        val effPrimaryType = selectedPokemon?.form?.primaryType ?: selectedPokemon?.primaryType ?: species?.primaryType
        val effSecondaryType = selectedPokemon?.form?.secondaryType ?: selectedPokemon?.secondaryType ?: species?.secondaryType
        effPrimaryType?.let { primaryType ->
            typeX = renderTypePill(guiGraphics, primaryType, typeX, rarityY) + 4
        }
        effSecondaryType?.let { secondaryType ->
            if (secondaryType != effPrimaryType) {
                renderTypePill(guiGraphics, secondaryType, typeX, rarityY)
            }
        }

        // 3D Viewport background + platform
        guiGraphics.fill(viewportX, viewportY, viewportX + viewportWidth, viewportY + viewportHeight, 0xF00A0F15.toInt())
        guiGraphics.renderOutline(viewportX, viewportY, viewportWidth, viewportHeight, 0xFF1C2B3A.toInt())

        // Pedestal base on floor
        val centerX = viewportX + viewportWidth / 2f
        val centerY = viewportY + viewportHeight / 2f
        val floorY = viewportY + viewportHeight - 16
        guiGraphics.fill((centerX - 46).toInt(), floorY, (centerX + 46).toInt(), floorY + 3, 0x44000000.toInt())
        guiGraphics.fill((centerX - 34).toInt(), floorY + 3, (centerX + 34).toInt(), floorY + 5, 0x24000000.toInt())

        // 3D Model Rendering (Centered in viewport with scissor clipping)
        val pokemon = selectedPokemon
        val state = floatingState
        if (pokemon != null && state != null) {
            val modelAreaTop = viewportY + 26
            val modelAreaBottom = viewportY + viewportHeight - 12
            val modelAreaH = modelAreaBottom - modelAreaTop
            val modelCenterX = viewportX + viewportWidth / 2f
            val modelTranslateY = modelAreaTop + modelAreaH * 0.14f

            guiGraphics.flush()
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest()
            enableScaledScissor(
                guiGraphics,
                viewportX + 2,
                viewportY + 2,
                viewportX + viewportWidth - 2,
                viewportY + viewportHeight - 2
            )
            try {
                val aspectsToRender = currentAspects.ifEmpty { pokemon.aspects }
                state.currentAspects = aspectsToRender

                val baseScale = (modelAreaH * 0.28f).coerceIn(28.0f, 42.0f)

                val matrices = guiGraphics.pose()
                matrices.pushPose()

                // Translate near top of model area (drawProfilePokemon shifts downward by ~1.5 * profileScale * scale)
                matrices.translate(modelCenterX.toDouble(), modelTranslateY.toDouble(), 150.0)
                matrices.scale(1.0f, 1.0f, uiScale)

                val rotation = Quaternionf().rotationXYZ(
                    Math.toRadians(13.0).toFloat(),
                    Math.toRadians(rotationY.toDouble()).toFloat(),
                    0f
                )

                drawProfilePokemon(
                    renderablePokemon = RenderablePokemon(pokemon.species, aspectsToRender),
                    matrixStack = matrices,
                    rotation = rotation,
                    poseType = PoseType.PROFILE,
                    state = state,
                    partialTicks = partialTick,
                    scale = baseScale * modelZoom,
                    profileTransformType = ProfileTransformType.SUMMARY
                )

                matrices.popPose()
                guiGraphics.flush()
            } catch (t: Throwable) {
                org.slf4j.LoggerFactory.getLogger("legendary-bestiary").error("Error rendering 3D legendary ${pokemon.species.resourceIdentifier}", t)
            } finally {
                guiGraphics.disableScissor()
            }
        }

        // Bottom Capture Info Card
        val cardX = rightX + 6
        val cardY = viewportY + viewportHeight + 6
        val cardW = rightWidth - 12
        val cardH = rightHeight - (cardY - rightY) - 6
        val maxLineW = cardW - 16

        if (entry.captured) {
            guiGraphics.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0x2A153A2A.toInt())
            guiGraphics.renderOutline(cardX, cardY, cardW, cardH, 0xFF2F855A.toInt())

            val origOwner = entry.originalOwner.ifBlank { entry.currentOwner }.ifBlank { TextCatalog.string("card.captured.unknown") }
            val currOwner = entry.currentOwner.ifBlank { origOwner }

            val statusLine = Component.literal("REGISTRADO").withStyle { it.withColor(0xFF48BB78.toInt()).withBold(true) }
            drawScaledComponent(guiGraphics, statusLine, cardX + 8, cardY + 5, maxLineW, 0xFF48BB78.toInt())

            val origText = Component.literal("Dueño Original: ").withStyle { it.withColor(0xFFA0AEC0.toInt()) }
                .append(Component.literal(origOwner).withStyle { it.withColor(0xFFFFFFFF.toInt()).withBold(true) })
            drawScaledComponent(guiGraphics, origText, cardX + 8, cardY + 18, maxLineW, 0xFFFFFFFF.toInt())

            val currText = Component.literal("Dueño Actual: ").withStyle { it.withColor(0xFFA0AEC0.toInt()) }
                .append(Component.literal(currOwner).withStyle { it.withColor(if (currOwner == origOwner) 0xFFE2E8F0.toInt() else 0xFF68D7A2.toInt()).withBold(true) })
            drawScaledComponent(guiGraphics, currText, cardX + 8, cardY + 31, maxLineW, 0xFF68D7A2.toInt())

            val dateStr = if (entry.capturedAt > 0L) formatter.format(Instant.ofEpochMilli(entry.capturedAt)) else TextCatalog.string("card.captured.no_date")
            val dateText = TextCatalog.component("card.captured.date_label").withStyle { it.withColor(0xFFA0AEC0.toInt()) }
                .append(Component.literal(dateStr).withStyle { it.withColor(0xFFE2E8F0.toInt()) })
            drawScaledComponent(guiGraphics, dateText, cardX + 8, cardY + 44, maxLineW, 0xFFE2E8F0.toInt())
        } else {
            guiGraphics.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0x2A182A3A.toInt())
            guiGraphics.renderOutline(cardX, cardY, cardW, cardH, 0xFF2B4C6F.toInt())

            val headerLine = Component.literal("DISPONIBLE").withStyle { it.withColor(0xFF63B3ED.toInt()).withBold(true) }
            drawScaledComponent(guiGraphics, headerLine, cardX + 8, cardY + 6, maxLineW, 0xFF63B3ED.toInt())

            val statusText = Component.literal("En libertad por el mundo Pokémon.").withStyle { it.withColor(0xFFE2E8F0.toInt()) }
            drawScaledComponent(guiGraphics, statusText, cardX + 8, cardY + 22, maxLineW, 0xFFE2E8F0.toInt())

            val subText = Component.literal("Aún no ha sido capturado por ningún entrenador.").withStyle { it.withColor(0xFFA0AEC0.toInt()) }
            drawScaledComponent(guiGraphics, subText, cardX + 8, cardY + 36, maxLineW, 0xFFA0AEC0.toInt())
        }
    }

    private fun drawScaledString(guiGraphics: GuiGraphics, text: String, x: Int, y: Int, maxWidth: Int, color: Int) {
        val textW = font.width(text)
        if (textW > maxWidth && maxWidth > 10) {
            val scale = maxWidth.toFloat() / textW.toFloat()
            val pose = guiGraphics.pose()
            pose.pushPose()
            pose.translate(x.toFloat(), y.toFloat(), 0f)
            pose.scale(scale, scale, 1f)
            guiGraphics.drawString(font, text, 0, 0, color, false)
            pose.popPose()
        } else {
            guiGraphics.drawString(font, text, x, y, color, false)
        }
    }

    private fun drawScaledComponent(guiGraphics: GuiGraphics, text: Component, x: Int, y: Int, maxWidth: Int, color: Int) {
        val textW = font.width(text)
        if (textW > maxWidth && maxWidth > 10) {
            val scale = maxWidth.toFloat() / textW.toFloat()
            val pose = guiGraphics.pose()
            pose.pushPose()
            pose.translate(x.toFloat(), y.toFloat(), 0f)
            pose.scale(scale, scale, 1f)
            guiGraphics.drawString(font, text, 0, 0, color, false)
            pose.popPose()
        } else {
            guiGraphics.drawString(font, text, x, y, color, false)
        }
    }

    private fun drawScaledCenteredString(guiGraphics: GuiGraphics, text: String, centerX: Int, y: Int, maxWidth: Int, color: Int) {
        val textW = font.width(text)
        if (textW > maxWidth && maxWidth > 10) {
            val scale = maxWidth.toFloat() / textW.toFloat()
            val pose = guiGraphics.pose()
            pose.pushPose()
            pose.translate(centerX.toFloat(), y.toFloat(), 0f)
            pose.scale(scale, scale, 1f)
            guiGraphics.drawCenteredString(font, text, 0, 0, color)
            pose.popPose()
        } else {
            guiGraphics.drawCenteredString(font, text, centerX, y, color)
        }
    }

    private fun renderTypePill(guiGraphics: GuiGraphics, type: ElementalType, x: Int, y: Int): Int {
        val typeName = type.displayName.string.uppercase()
        val pillW = font.width(typeName) + 8
        val color = (0xFF000000.toInt() or type.hue)
        guiGraphics.fill(x, y, x + pillW, y + 11, color)
        guiGraphics.drawString(font, typeName, x + 4, y + 2, 0xFFFFFFFF.toInt(), false)
        return x + pillW
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (openingProgress() < 1.0F) return true
        val localX = (mouseX - renderX) / uiScale
        val localY = (mouseY - renderY) / uiScale

        // Check list click
        if (localX >= listX && localX < listX + listWidth && localY >= listY && localY < listY + listHeight) {
            val entries = getFilteredEntries()
            val relativeY = (localY - listY - 2).toInt()
            if (relativeY >= 0) {
                val clickedIdx = scrollOffset + (relativeY / itemHeight)
                if (clickedIdx in entries.indices) {
                    selectEntry(entries[clickedIdx])
                    Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
                    return true
                }
            }
        }

        // Check 3D viewport drag/click (disabled drag/stop - rotates continuously in center)
        if (localX >= viewportX && localX < viewportX + viewportWidth && localY >= viewportY && localY < viewportY + viewportHeight) {
            val overButtons = shinyButton.isMouseOver(localX, localY)
            if (!overButtons) {
                return true
            }
        }

        return super.mouseClicked(localX, localY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        val localX = (mouseX - renderX) / uiScale
        val localY = (mouseY - renderY) / uiScale
        val localDragX = dragX / uiScale
        val localDragY = dragY / uiScale
        return super.mouseDragged(localX, localY, button, localDragX, localDragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val localX = (mouseX - renderX) / uiScale
        val localY = (mouseY - renderY) / uiScale
        return super.mouseReleased(localX, localY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val localX = (mouseX - renderX) / uiScale
        val localY = (mouseY - renderY) / uiScale
        if (localX >= listX && localX < listX + listWidth && localY >= listY && localY < listY + listHeight) {
            val entries = getFilteredEntries()
            val maxOffset = (entries.size - visibleCount).coerceAtLeast(0)
            scrollOffset = (scrollOffset - scrollY.toInt()).coerceIn(0, maxOffset)
            return true
        }
        return super.mouseScrolled(localX, localY, scrollX, scrollY)
    }

    private fun trim(text: String, maxWidth: Int): String {
        val font = Minecraft.getInstance().font
        if (font.width(text) <= maxWidth) return text
        return font.plainSubstrByWidth(text, (maxWidth - font.width("…")).coerceAtLeast(0)) + "…"
    }
}

/**
 * Modern flat/glass RPG button tailored to the Cobblemon/Desgraciamon aesthetic.
 */
class ModernButton(
    x: Int, y: Int, width: Int, height: Int,
    message: Component,
    private val isPrimary: Boolean = false,
    private val isDanger: Boolean = false,
    var isToggled: () -> Boolean = { false },
    onPress: OnPress
) : Button(x, y, width, height, message, onPress, DEFAULT_NARRATION) {

    override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val toggled = isToggled()
        val hovered = isHoveredOrFocused

        val bg = when {
            !active -> 0x50101720.toInt()
            toggled -> 0xFF1D4A5A.toInt() // glowing cyan/teal active tab
            isDanger && hovered -> 0xFF742A2A.toInt() // subtle red hover for close
            isPrimary && hovered -> 0xFF285E61.toInt()
            hovered -> 0xFF1E2D3E.toInt()
            else -> 0xD8101822.toInt()
        }

        val border = when {
            !active -> 0xFF1A2634.toInt()
            toggled -> 0xFF5BC0A7.toInt() // glowing cyan highlight
            isDanger && hovered -> 0xFFE53E3E.toInt()
            hovered -> 0xFF5BC0A7.toInt()
            else -> 0xFF233547.toInt()
        }

        val textColor = when {
            !active -> 0xFF5A6A7A.toInt()
            toggled -> 0xFFFFFFFF.toInt()
            hovered -> 0xFFFFFFFF.toInt()
            else -> 0xFFCBD5E0.toInt()
        }

        guiGraphics.fill(x, y, x + width, y + height, bg)
        guiGraphics.renderOutline(x, y, width, height, border)

        val font = Minecraft.getInstance().font
        val textW = font.width(message)
        val availW = (width - 8).coerceAtLeast(6)
        if (textW > availW) {
            val scale = availW.toFloat() / textW.toFloat()
            val pose = guiGraphics.pose()
            pose.pushPose()
            pose.translate(x + width / 2f, y + height / 2f, 0f)
            pose.scale(scale, scale, 1f)
            guiGraphics.drawCenteredString(font, message, 0, -4, textColor)
            pose.popPose()
        } else {
            val textY = y + (height - 8) / 2
            guiGraphics.drawCenteredString(font, message, x + width / 2, textY, textColor)
        }
    }
}

private enum class BestiaryFilter { ALL, CAPTURED, NOT_CAPTURED }
