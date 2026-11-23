package com.desgraciamon.biomelocator

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.ProfileTransformType
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.entity.PoseType
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.joml.Quaternionf

class BiomeLocatorScreen(private val previousScreen: Screen? = null) : Screen(Component.literal("Codex de Hábitats & Expediciones")) {
    companion object {
        const val BASE_W = 590
        const val BASE_H = 340
    }

    private var uiScale: Float = 1.0f
    private var renderX: Float = 0f
    private var renderY: Float = 0f

    private fun enableScaledScissor(graphics: GuiGraphics, x1: Int, y1: Int, x2: Int, y2: Int) {
        val sx1 = (renderX + x1 * uiScale).toInt()
        val sy1 = (renderY + y1 * uiScale).toInt()
        val sx2 = (renderX + x2 * uiScale).toInt()
        val sy2 = (renderY + y2 * uiScale).toInt()
        graphics.enableScissor(sx1, sy1, sx2, sy2)
    }

    private val openedAtNanos = System.nanoTime()
    private val cardStates = mutableMapOf<String, FloatingState>()

    private var selectedDimension: String = "overworld"
    private var selectedCategory: String = "ALL"
    private var selectedRarityFilter: String = "ALL"
    private var onlyRegisteredFilter: Boolean = false
    private var searchQuery: String = ""
    private var searchBox: EditBox? = null

    private fun getLeftListY(top: Int = 0): Int = if (searchQuery.isNotBlank()) top + 99 else top + 80
    private fun getLeftListH(top: Int = 0): Int = (top + BASE_H - 12) - getLeftListY(top)

    // Selected Biome for Inspector Panel
    private var selectedBiome: BiomeLocatorPackets.BiomeEntryData? = null

    // Scrolling states
    private var leftScrollOffset: Double = 0.0
    private var leftMaxScroll: Double = 0.0
    private var isDraggingLeftScroll: Boolean = false

    private var rightScrollOffset: Double = 0.0
    private var rightMaxScroll: Double = 0.0
    private var isDraggingRightScroll: Boolean = false

    private val ICON_OVERWORLD = ResourceLocation.fromNamespaceAndPath("desgraciamon4_biome_locator", "textures/gui/icon_overworld.png")
    private val ICON_NETHER = ResourceLocation.fromNamespaceAndPath("desgraciamon4_biome_locator", "textures/gui/icon_nether.png")
    private val ICON_END = ResourceLocation.fromNamespaceAndPath("desgraciamon4_biome_locator", "textures/gui/icon_end.png")
    private val ICON_LOCK = ResourceLocation.fromNamespaceAndPath("desgraciamon4_biome_locator", "textures/gui/icon_lock.png")
    private val ICON_SEARCH = ResourceLocation.fromNamespaceAndPath("desgraciamon4_biome_locator", "textures/gui/icon_search.png")

    override fun init() {
        super.init()
        val margin = 10f
        val maxW = width - margin * 2f
        val maxH = height - margin * 2f
        uiScale = minOf(1.0f, maxW / BASE_W.toFloat(), maxH / BASE_H.toFloat())
        renderX = (width - BASE_W * uiScale) / 2f
        renderY = (height - BASE_H * uiScale) / 2f

        val left = 0
        val top = 0

        // Search edit box on top of left column (clean bounds, no overlap)
        val searchX = left + 14
        val searchY = top + 42
        val searchW = 214
        val searchH = 16

        val currentText = searchBox?.value ?: ""
        searchBox = EditBox(font, searchX + 18, searchY + 2, searchW - 22, searchH - 4, Component.literal("Buscar")).apply {
            setMaxLength(36)
            setBordered(false)
            setTextColor(0xFFF3E9D2.toInt())
            setValue(currentText)
            setResponder {
                searchQuery = it.trim().lowercase()
                leftScrollOffset = 0.0
                if (searchQuery.length >= 2) {
                    BiomeLocatorClientState.requestPokemonSearch(searchQuery)
                } else {
                    BiomeLocatorClientState.matchedPokemonQuery = ""
                    BiomeLocatorClientState.matchedPokemonName = ""
                    BiomeLocatorClientState.matchedPokemonBiomeIds = emptySet()
                }
                recalculateLeftScroll()
                autoSelectOnSearch()
            }
        }
        addWidget(searchBox!!)

        // Auto-select first available biome if none selected
        if (selectedBiome == null) {
            val biomes = getFilteredBiomes()
            if (biomes.isNotEmpty()) {
                selectBiome(biomes.first())
            }
        }

        recalculateLeftScroll()
        recalculateRightScroll()
    }

    fun onSyncUpdated() {
        recalculateLeftScroll()
        recalculateRightScroll()
    }

    fun onSpawnsReceived(biomeId: String) {
        if (selectedBiome?.id == biomeId) {
            recalculateRightScroll()
        }
    }

    fun onPokemonSearchResults(query: String, pokemonName: String, biomeIds: Set<String>) {
        if (searchQuery == query.trim().lowercase()) {
            recalculateLeftScroll()
            autoSelectOnSearch()
        }
    }

    private fun selectBiome(biome: BiomeLocatorPackets.BiomeEntryData) {
        selectedBiome = biome
        rightScrollOffset = 0.0
        if (!BiomeLocatorClientState.biomeSpawnsCache.containsKey(biome.id)) {
            BiomeLocatorClientState.requestSpawns(biome.id)
        }
        recalculateRightScroll()
    }

    private fun autoSelectOnSearch() {
        val biomes = getFilteredBiomes()
        if (biomes.isNotEmpty() && (selectedBiome == null || !biomes.any { it.id == selectedBiome!!.id })) {
            selectBiome(biomes.first())
        }
    }

    private fun recalculateLeftScroll() {
        val filtered = getFilteredBiomes()
        val itemHeight = 38
        val visibleHeight = getLeftListH()
        leftMaxScroll = maxOf(0.0, (filtered.size * itemHeight + 6 - visibleHeight).toDouble())
        leftScrollOffset = leftScrollOffset.coerceIn(0.0, leftMaxScroll)
    }

    private fun recalculateRightScroll() {
        val spawns = getFilteredSpawns()
        val totalRows = (spawns.size + 1) / 2
        val cardH = 80
        val cardGap = 6
        val visibleHeight = 202
        rightMaxScroll = maxOf(0.0, (totalRows * (cardH + cardGap) - visibleHeight).toDouble())
        rightScrollOffset = rightScrollOffset.coerceIn(0.0, rightMaxScroll)
    }

    private fun getFilteredBiomes(): List<BiomeLocatorPackets.BiomeEntryData> {
        val hasPokemonMatch = BiomeLocatorClientState.matchedPokemonQuery == searchQuery && BiomeLocatorClientState.matchedPokemonBiomeIds.isNotEmpty()

        return BiomeLocatorClientState.allBiomes.filter { b ->
            // Filter by dimension
            if (b.dimension.lowercase() != selectedDimension.lowercase()) return@filter false

            // Filter by already-registered biomes
            if (onlyRegisteredFilter && !BiomeLocatorClientState.isBiomeUnlocked(b.id)) return@filter false

            // Filter by category
            if (selectedCategory != "ALL") {
                if (!b.category.contains(selectedCategory, ignoreCase = true)) return@filter false
            }

            // Filter by Pokémon match or Biome search query (matches both Spanish and English biome/Pokémon names)
            if (searchQuery.isNotBlank()) {
                val spanishName = BiomeLocatorServer.translateBiomeName(b.id, b.name).lowercase()
                val englishWords = b.id.substringAfter(':').replace('_', ' ').lowercase()
                val matchBiome = spanishName.contains(searchQuery) ||
                    b.name.lowercase().contains(searchQuery) ||
                    b.id.lowercase().contains(searchQuery) ||
                    englishWords.contains(searchQuery) ||
                    b.category.lowercase().contains(searchQuery)
                val cachedSpawns = BiomeLocatorClientState.biomeSpawnsCache[b.id]
                val matchLocalSpawn = cachedSpawns != null && cachedSpawns.any {
                    it.displayName.lowercase().contains(searchQuery) || it.speciesId.lowercase().contains(searchQuery)
                }
                val matchRemotePokemon = hasPokemonMatch && BiomeLocatorClientState.matchedPokemonBiomeIds.contains(b.id)
                if (!matchBiome && !matchLocalSpawn && !matchRemotePokemon) return@filter false
            }

            true
        }
    }

    private fun getFilteredSpawns(): List<CobblemonSpawnIntegration.BiomePokemonSpawnEntry> {
        val biome = selectedBiome ?: return emptyList()
        val allSpawns = BiomeLocatorClientState.biomeSpawnsCache[biome.id] ?: return emptyList()

        val list = if (selectedRarityFilter == "ALL") allSpawns else allSpawns.filter { it.bucket.equals(selectedRarityFilter, ignoreCase = true) }

        // If user searched for a Pokémon name, bump the matched Pokémon to the top of the grid!
        if (searchQuery.isNotBlank()) {
            val q = searchQuery
            return list.sortedByDescending { it.displayName.lowercase().contains(q) || it.speciesId.lowercase().contains(q) }
        }

        return list
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val localX = (mouseX - renderX) / uiScale
        val localY = (mouseY - renderY) / uiScale
        return handleMouseScrolled(localX, localY, scrollX, scrollY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        isDraggingLeftScroll = false
        isDraggingRightScroll = false
        val localX = (mouseX - renderX) / uiScale
        val localY = (mouseY - renderY) / uiScale
        return super.mouseReleased(localX, localY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        val localX = (mouseX - renderX) / uiScale
        val localY = (mouseY - renderY) / uiScale
        val dragDeltaY = dragY / uiScale

        if (isDraggingLeftScroll && leftMaxScroll > 0) {
            val top = 0
            val listY = getLeftListY(top)
            val listH = getLeftListH(top)
            val leftBarH = listH - 6
            val thumbH = maxOf(16, (leftBarH * (listH / (leftMaxScroll + listH))).toInt())
            val usableH = leftBarH - thumbH
            if (usableH > 0) {
                leftScrollOffset = (leftScrollOffset + (dragDeltaY / usableH * leftMaxScroll)).coerceIn(0.0, leftMaxScroll)
            }
            return true
        }

        if (isDraggingRightScroll && rightMaxScroll > 0) {
            val top = 0
            val rightY = top + 68
            val rightH = BASE_H.toInt() - 78
            val spawnsListY = rightY + 56
            val spawnsListH = rightH - 60
            val rightBarH = spawnsListH
            val thumbH = maxOf(16, (rightBarH * (spawnsListH / (rightMaxScroll + spawnsListH))).toInt())
            val usableH = rightBarH - thumbH
            if (usableH > 0) {
                rightScrollOffset = (rightScrollOffset + (dragDeltaY / usableH * rightMaxScroll)).coerceIn(0.0, rightMaxScroll)
            }
            return true
        }

        return super.mouseDragged(localX, localY, button, dragX / uiScale, dragY / uiScale)
    }

    private fun handleMouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val panelHeight = BASE_H
        val left = 0
        val top = 0

        val leftColW = 214
        val listY = getLeftListY(top)

        if (mouseX >= left + 14 && mouseX <= left + 14 + leftColW && mouseY >= listY && mouseY <= top + panelHeight - 12) {
            if (leftMaxScroll > 0) {
                leftScrollOffset = (leftScrollOffset - scrollY * 26.0).coerceIn(0.0, leftMaxScroll)
                return true
            }
        } else {
            if (rightMaxScroll > 0) {
                rightScrollOffset = (rightScrollOffset - scrollY * 28.0).coerceIn(0.0, rightMaxScroll)
                return true
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val localX = (mouseX - renderX) / uiScale
        val localY = (mouseY - renderY) / uiScale
        return handleMouseClicked(localX, localY, button)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (searchBox?.isFocused == true) {
            if (keyCode == 256) {
                searchBox?.isFocused = false
                this.focused = null
                return true
            }
            if (searchBox!!.keyPressed(keyCode, scanCode, modifiers)) {
                return true
            }
            if (minecraft?.options?.keyInventory?.matches(keyCode, scanCode) == true ||
                BiomeLocatorClient.KEY_OPEN.matches(keyCode, scanCode)
            ) {
                return true
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (searchBox?.isFocused == true && searchBox!!.charTyped(codePoint, modifiers)) {
            return true
        }
        return super.charTyped(codePoint, modifiers)
    }

    private fun handleMouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val panelWidth = BASE_W
        val panelHeight = BASE_H
        val left = 0
        val top = 0

        // Scrollbar Click Detection
        run {
            val sbListY = getLeftListY(top)
            val sbListH = getLeftListH(top)
            val leftBarX = left + 14 + 214 - 7
            val leftBarY = sbListY + 3
            val leftBarH = sbListH - 6
            if (leftMaxScroll > 0 && mouseX >= leftBarX - 6 && mouseX <= leftBarX + 16 && mouseY >= leftBarY && mouseY <= leftBarY + leftBarH) {
                isDraggingLeftScroll = true
                val thumbH = maxOf(16, (leftBarH * (sbListH / (leftMaxScroll + sbListH))).toInt())
                val usableH = leftBarH - thumbH
                if (usableH > 0) {
                    leftScrollOffset = ((mouseY - leftBarY - thumbH / 2.0) / usableH * leftMaxScroll).coerceIn(0.0, leftMaxScroll)
                }
                return true
            }

            val sbRightX = left + 242
            val sbRightW = panelWidth.toInt() - sbRightX - 14
            val sbRightY = top + 68
            val sbRightH = panelHeight.toInt() - 78
            val spawnsListY = sbRightY + 56
            val spawnsListH = sbRightH - 60
            val rightBarX = sbRightX + sbRightW - 7
            val rightBarY = spawnsListY
            val rightBarH = spawnsListH
            if (rightMaxScroll > 0 && mouseX >= rightBarX - 6 && mouseX <= rightBarX + 16 && mouseY >= rightBarY && mouseY <= rightBarY + rightBarH) {
                isDraggingRightScroll = true
                val thumbH = maxOf(16, (rightBarH * (spawnsListH / (rightMaxScroll + spawnsListH))).toInt())
                val usableH = rightBarH - thumbH
                if (usableH > 0) {
                    rightScrollOffset = ((mouseY - rightBarY - thumbH / 2.0) / usableH * rightMaxScroll).coerceIn(0.0, rightMaxScroll)
                }
                return true
            }
        }

        // 0. Search Bar Click Handling
        if (mouseX >= left + 12 && mouseX <= left + 230 && mouseY >= top + 38 && mouseY <= top + 60) {
            searchBox?.isFocused = true
            this.focused = searchBox
            if (button == 1) {
                searchBox?.value = ""
            } else {
                searchBox?.mouseClicked(mouseX, mouseY, button)
            }
            return true
        } else {
            searchBox?.isFocused = false
            if (this.focused == searchBox) {
                this.focused = null
            }
        }

        // 1. Dimension Tabs
        val tabW = 86
        val tabH = 19
        val tabY = top + 8
        val dims = listOf("overworld" to "Overworld", "nether" to "Nether", "end" to "The End")

        for (i in dims.indices) {
            val (dimKey, _) = dims[i]
            val tx = left + 14 + i * (tabW + 6)
            if (mouseX >= tx && mouseX <= tx + tabW && mouseY >= tabY && mouseY <= tabY + tabH) {
                if (BiomeLocatorClientState.isDimensionUnlocked(dimKey)) {
                    selectedDimension = dimKey
                    leftScrollOffset = 0.0
                    selectedBiome = null
                    recalculateLeftScroll()
                    autoSelectOnSearch()
                    return true
                }
            }
        }

        // 2. Stop Tracking Button in Header
        if (BiomeLocatorClientState.activeTrackedBiome != null) {
            val stopX = left + panelWidth - 84
            val stopY = top + 9
            if (mouseX >= stopX && mouseX <= stopX + 72 && mouseY >= stopY && mouseY <= stopY + 17) {
                BiomeLocatorClientState.requestStop()
                return true
            }
        }

        // 3. Category Pills (Right side of header)
        val catY = top + 42
        val catStartX = left + 236
        val categories = listOf("ALL" to "Todos", "Bosque" to "Bosques", "Árido" to "Áridos", "Acuático" to "Acuáticos", "Montaña" to "Montañas", "Gélido" to "Gélidos")
        var currentPillX = catStartX

        for ((catKey, catLabel) in categories) {
            val pw = font.width(catLabel) + 10
            if (mouseX >= currentPillX && mouseX <= currentPillX + pw && mouseY >= catY && mouseY <= catY + 16) {
                selectedCategory = catKey
                leftScrollOffset = 0.0
                recalculateLeftScroll()
                autoSelectOnSearch()
                return true
            }
            currentPillX += pw + 4
        }

        // 3b. "Biomas ya registrados" Filter Toggle (Left Column, below search bar)
        val regFilterX = left + 14
        val regFilterY = top + 61
        val regFilterW = 214
        val regFilterH = 16
        if (mouseX >= regFilterX && mouseX <= regFilterX + regFilterW && mouseY >= regFilterY && mouseY <= regFilterY + regFilterH) {
            onlyRegisteredFilter = !onlyRegisteredFilter
            leftScrollOffset = 0.0
            recalculateLeftScroll()
            autoSelectOnSearch()
            return true
        }

        // 4. Biome Cards in Left Column
        val listX = left + 14
        val listW = 214
        val listY = getLeftListY(top)
        val listH = getLeftListH(top)

        if (mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH) {
            val filtered = getFilteredBiomes()
            val cardH = 34
            val cardGap = 4

            for (i in filtered.indices) {
                val cy = listY + 3 + i * (cardH + cardGap) - leftScrollOffset.toInt()
                if (cy + cardH < listY || cy > listY + listH) continue

                if (mouseY >= cy && mouseY <= cy + cardH) {
                    selectBiome(filtered[i])
                    return true
                }
            }
        }

        // 5. Right Inspector Actions (Action Button & Rarity Tabs)
        val rightX = left + 242
        val rightY = top + 68
        val rightW = panelWidth - 256

        selectedBiome?.let { b ->
            val isUnlocked = BiomeLocatorClientState.isBiomeUnlocked(b.id)
            val isTracked = BiomeLocatorClientState.isBiomeTracked(b.id)
            val actionBtnH = 18
            val actionBtnY = rightY + 8

            if (isUnlocked) {
                val primaryW = 64
                val primaryX = rightX + rightW - primaryW - 8
                val anotherW = 86
                val anotherX = primaryX - anotherW - 4

                // Free "Rastrear otro" button
                if (mouseX >= anotherX && mouseX <= anotherX + anotherW && mouseY >= actionBtnY && mouseY <= actionBtnY + actionBtnH) {
                    BiomeLocatorClientState.requestTrackAnother(b.id)
                    return true
                }

                // Primary Track / Stop button
                if (mouseX >= primaryX && mouseX <= primaryX + primaryW && mouseY >= actionBtnY && mouseY <= actionBtnY + actionBtnH) {
                    if (isTracked) {
                        BiomeLocatorClientState.requestStop()
                    } else {
                        BiomeLocatorClientState.requestTrack(b.id)
                    }
                    return true
                }
            } else {
                val actionBtnW = 138
                val actionBtnX = rightX + rightW - actionBtnW - 8
                if (mouseX >= actionBtnX && mouseX <= actionBtnX + actionBtnW && mouseY >= actionBtnY && mouseY <= actionBtnY + actionBtnH) {
                    if (BiomeLocatorClientState.tokens > 0) {
                        BiomeLocatorClientState.requestUnlock(b.id)
                    }
                    return true
                }
            }

            // Rarity Filter Pills - Exact coordinates matching render
            val rarityY = rightY + 32
            val rarities = listOf("ALL" to "Todos", "ultra-rare" to "Ultra Raros", "rare" to "Raros", "uncommon" to "Poco comunes", "common" to "Comunes")
            var rx = rightX + 8

            for ((rKey, rLabel) in rarities) {
                val rw = font.width(rLabel) + 10
                if (mouseX >= rx && mouseX <= rx + rw && mouseY >= rarityY && mouseY <= rarityY + 16) {
                    selectedRarityFilter = rKey
                    rightScrollOffset = 0.0
                    recalculateRightScroll()
                    return true
                }
                rx += rw + 4
            }
        }

        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // No-op to avoid double/delayed blur passes
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // 1. Blur only the 3D game world in the background
        renderBlurredBackground(partialTick)

        // 2. Dim background with warm dark tint
        graphics.fill(0, 0, width, height, 0xB40C0A09.toInt())

        val localMouseX = ((mouseX - renderX) / uiScale).toInt()
        val localMouseY = ((mouseY - renderY) / uiScale).toInt()

        val pose = graphics.pose()
        pose.pushPose()
        pose.translate(renderX, renderY, 0f)
        pose.scale(uiScale, uiScale, 1.0f)

        // Opening animation ease (Guild pop-in)
        val progress = ((System.nanoTime() - openedAtNanos) / 280_000_000.0f).coerceIn(0f, 1f)
        if (progress < 1.0f) {
            val ease = 1f - (1f - progress).let { it * it * it }
            val scale = 0.08f + 0.92f * ease
            val cx = BASE_W / 2f
            val cy = BASE_H / 2f
            pose.translate(cx, cy, 0f)
            pose.scale(scale, scale, 1f)
            pose.translate(-cx, -cy, 0f)
        }

        val left = 0
        val top = 0
        val panelWidth = BASE_W
        val panelHeight = BASE_H

        // --- ENCYCLOPEDIA CODEX FRAME ---
        // 1. Antique Leather Outer Binding
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xF8181412.toInt())
        drawBorder(graphics, left, top, panelWidth, panelHeight, 0xFF4A3A2C.toInt())
        drawBorder(graphics, left + 1, top + 1, panelWidth - 2, panelHeight - 2, 0xFF2A2018.toInt())

        // 2. Parchment page background (Left & Right pages)
        val pageY = top + 34
        val pageH = panelHeight - 44
        graphics.fill(left + 6, pageY, left + panelWidth - 6, pageY + pageH, 0xF61C1815.toInt())
        drawBorder(graphics, left + 6, pageY, panelWidth - 12, pageH, 0xFF3D2F23.toInt())

        // 3. Central Book Spine Embossed Divider
        val spineX = left + 233
        graphics.fill(spineX, pageY, spineX + 3, pageY + pageH, 0xFF120E0C.toInt())
        graphics.fill(spineX + 1, pageY + 4, spineX + 2, pageY + pageH - 4, 0xFF5C4736.toInt())

        // Top Bookmark Ribbon Tabs
        renderDimensionTabs(graphics, left, top, localMouseX, localMouseY)

        // Header Medallions & Study Progress
        renderHeaderStats(graphics, left, top, panelWidth)

        // Subheader (Search bar & Category pills)
        renderSubheader(graphics, left, top, panelWidth, localMouseX, localMouseY)

        // Left Page: Biome Index List
        renderBiomeList(graphics, left, top, panelHeight, localMouseX, localMouseY)

        // Right Page: Field Observations & Pokemon Grid
        renderBiomeInspector(graphics, left, top, panelWidth, panelHeight, localMouseX, localMouseY, partialTick)

        // Render searchBox inside the scaled canvas at its local bounds
        searchBox?.render(graphics, localMouseX, localMouseY, partialTick)

        pose.popPose()

        // Tooltips rendered on top in screen space
        renderTooltips(graphics, left, top, panelWidth, panelHeight, localMouseX, localMouseY, mouseX, mouseY)
    }

    private fun renderDimensionTabs(graphics: GuiGraphics, left: Int, top: Int, mouseX: Int, mouseY: Int) {
        val tabW = 86
        val tabH = 19
        val tabY = top + 8
        val dims = listOf(
            Triple("overworld", "Overworld", ICON_OVERWORLD),
            Triple("nether", "Nether", ICON_NETHER),
            Triple("end", "The End", ICON_END)
        )

        for (i in dims.indices) {
            val (dimKey, label, icon) = dims[i]
            val tx = left + 14 + i * (tabW + 6)
            val isSelected = selectedDimension == dimKey
            val isUnlocked = BiomeLocatorClientState.isDimensionUnlocked(dimKey)
            val isHovered = mouseX >= tx && mouseX <= tx + tabW && mouseY >= tabY && mouseY <= tabY + tabH

            val bg = when {
                !isUnlocked -> 0xFF1E1714.toInt()
                isSelected -> 0xFF3A2B1E.toInt()
                isHovered -> 0xFF2A2016.toInt()
                else -> 0xFF1F1813.toInt()
            }

            graphics.fill(tx, tabY, tx + tabW, tabY + tabH, bg)
            val borderCol = if (isSelected) 0xFFD4AF37.toInt() else 0xFF4A392A.toInt()
            drawBorder(graphics, tx, tabY, tabW, tabH, borderCol)

            if (isUnlocked) {
                graphics.blit(icon, tx + 6, tabY + 2, 0f, 0f, 16, 16, 16, 16)
                val textColor = if (isSelected) 0xFFFFE8A3.toInt() else 0xFFBCA789.toInt()
                graphics.drawString(font, label, tx + 26, tabY + 5, textColor, false)
            } else {
                graphics.blit(ICON_LOCK, tx + 6, tabY + 2, 0f, 0f, 16, 16, 16, 16)
                graphics.drawString(font, "Bloqueado", tx + 26, tabY + 5, 0xFF7A6A5C.toInt(), false)
            }
        }
    }

    private fun renderHeaderStats(graphics: GuiGraphics, left: Int, top: Int, panelWidth: Int) {
        val count = BiomeLocatorClientState.discoveredCount
        val tokens = BiomeLocatorClientState.tokens
        val progressInTier = BiomeLocatorClientState.tierProgress
        val tierGoal = maxOf(1, BiomeLocatorClientState.currentTierGoal)
        val ratio = (progressInTier.toFloat() / tierGoal.toFloat()).coerceIn(0f, 1f)

        // Token Brass Medallion
        val tokenX = left + 296
        val tokenY = top + 9
        val tokenW = 96
        val tokenH = 17
        graphics.fill(tokenX, tokenY, tokenX + tokenW, tokenY + tokenH, 0xFF2A2012.toInt())
        drawBorder(graphics, tokenX, tokenY, tokenW, tokenH, 0xFFC59B27.toInt())
        graphics.drawCenteredString(font, "$tokens Fichas", tokenX + tokenW / 2, tokenY + 4, 0xFFFFE57F.toInt())

        // Archival Study Bar
        val barX = tokenX + tokenW + 8
        val barY = top + 9
        val barW = 104
        val barH = 17

        graphics.fill(barX, barY, barX + barW, barY + barH, 0xFF19130F.toInt())
        val fillW = (barW * ratio).toInt()
        if (fillW > 0) {
            graphics.fill(barX, barY, barX + fillW, barY + barH, 0xFF5A4325.toInt())
            graphics.fill(barX, barY + barH - 2, barX + fillW, barY + barH, 0xFFD4AF37.toInt())
        }
        drawBorder(graphics, barX, barY, barW, barH, 0xFF4A3724.toInt())
        graphics.drawCenteredString(font, "Estudios: $count ($progressInTier/$tierGoal)", barX + barW / 2, barY + 4, 0xFFF5ECD7.toInt())

        // Stop Tracking Button
        if (BiomeLocatorClientState.activeTrackedBiome != null) {
            val stopX = left + panelWidth - 84
            val stopY = top + 9
            graphics.fill(stopX, stopY, stopX + 72, stopY + 17, 0xFF5E1D1D.toInt())
            drawBorder(graphics, stopX, stopY, 72, 17, 0xFFA83636.toInt())
            graphics.drawCenteredString(font, "Detener", stopX + 36, stopY + 4, 0xFFFFFFFF.toInt())
        }
    }

    private fun renderSubheader(graphics: GuiGraphics, left: Int, top: Int, panelWidth: Int, mouseX: Int, mouseY: Int) {
        val searchX = left + 14
        val searchY = top + 42
        val searchW = 214
        val searchH = 16

        // Search Input Box (styled as a scholar's parchment scroll)
        graphics.fill(searchX, searchY, searchX + searchW, searchY + searchH, 0xFF171310.toInt())
        val searchBorder = if (searchBox?.isFocused == true) 0xFFD4AF37.toInt() else 0xFF4A392A.toInt()
        drawBorder(graphics, searchX, searchY, searchW, searchH, searchBorder)
        graphics.blit(ICON_SEARCH, searchX + 2, searchY, 0f, 0f, 16, 16, 16, 16)

        // Category Pills (styled as antique chapter bookmark ribbons)
        val catY = top + 42
        val catStartX = left + 236
        val categories = listOf("ALL" to "Todos", "Bosque" to "Bosques", "Árido" to "Áridos", "Acuático" to "Acuáticos", "Montaña" to "Montañas", "Gélido" to "Gélidos")
        var currentPillX = catStartX

        for ((catKey, catLabel) in categories) {
            val pw = font.width(catLabel) + 10
            val isSelected = selectedCategory == catKey
            val isHovered = mouseX >= currentPillX && mouseX <= currentPillX + pw && mouseY >= catY && mouseY <= catY + 16

            val bg = when {
                isSelected -> 0xFF4A3724.toInt()
                isHovered -> 0xFF2A2016.toInt()
                else -> 0xFF1A1410.toInt()
            }
            graphics.fill(currentPillX, catY, currentPillX + pw, catY + 16, bg)
            val border = if (isSelected) 0xFFD4AF37.toInt() else 0xFF3D2D1E.toInt()
            drawBorder(graphics, currentPillX, catY, pw, 16, border)

            val textCol = if (isSelected) 0xFFFFE8A3.toInt() else 0xFFBCA789.toInt()
            graphics.drawCenteredString(font, catLabel, currentPillX + pw / 2, catY + 4, textCol)
            currentPillX += pw + 4
        }
    }

    private fun renderBiomeList(graphics: GuiGraphics, left: Int, top: Int, panelHeight: Int, mouseX: Int, mouseY: Int) {
        val listX = left + 14
        val listW = 214

        // "Biomas ya registrados" Filter Toggle Button
        val regFilterY = top + 61
        val regFilterH = 16
        val regHover = mouseX >= listX && mouseX <= listX + listW && mouseY >= regFilterY && mouseY <= regFilterY + regFilterH
        val registeredInDim = BiomeLocatorClientState.allBiomes.count {
            it.dimension.lowercase() == selectedDimension.lowercase() && BiomeLocatorClientState.isBiomeUnlocked(it.id)
        }
        val regBg = when {
            onlyRegisteredFilter -> 0xFF1F3B2E.toInt()
            regHover -> 0xFF2A2016.toInt()
            else -> 0xFF1A1410.toInt()
        }
        val regBorder = when {
            onlyRegisteredFilter -> 0xFF5BC0A7.toInt()
            regHover -> 0xFF5A432F.toInt()
            else -> 0xFF3D2D1E.toInt()
        }
        val regTextCol = if (onlyRegisteredFilter) 0xFF9DE6A6.toInt() else 0xFFBCA789.toInt()
        val regPrefix = if (onlyRegisteredFilter) "✔ " else "☐ "
        graphics.fill(listX, regFilterY, listX + listW, regFilterY + regFilterH, regBg)
        drawBorder(graphics, listX, regFilterY, listW, regFilterH, regBorder)
        val regLabel = font.plainSubstrByWidth("${regPrefix}Biomas ya registrados ($registeredInDim)", listW - 10)
        graphics.drawString(font, regLabel, listX + 5, regFilterY + 4, regTextCol, false)

        // Clean Search Banner handling (never overlaps searchBox or filter toggle)
        val hasPokemonMatch = BiomeLocatorClientState.matchedPokemonQuery == searchQuery && BiomeLocatorClientState.matchedPokemonName.isNotBlank()
        val hasSearch = searchQuery.isNotBlank()

        val listY = getLeftListY(top)
        val listH = getLeftListH(top)

        if (hasSearch) {
            val bannerY = top + 80
            val matchCount = getFilteredBiomes().size
            graphics.fill(listX, bannerY, listX + listW, bannerY + 16, 0xFF2A2012.toInt())
            drawBorder(graphics, listX, bannerY, listW, 16, 0xFFC59B27.toInt())

            val bannerRaw = if (hasPokemonMatch) {
                "Hábitats de: ${BiomeLocatorClientState.matchedPokemonName} ($matchCount)"
            } else {
                "Filtrando: $searchQuery ($matchCount biomas)"
            }
            val clippedBanner = font.plainSubstrByWidth(bannerRaw, listW - 10)
            graphics.drawString(font, clippedBanner, listX + 5, bannerY + 4, 0xFFFFE8A3.toInt(), false)
        }

        // List frame (Parchment folio column)
        graphics.fill(listX, listY, listX + listW, listY + listH, 0xFF14100D.toInt())
        drawBorder(graphics, listX, listY, listW, listH, 0xFF3A2D22.toInt())

        enableScaledScissor(graphics, listX + 1, listY + 1, listX + listW - 1, listY + listH - 1)

        val filtered = getFilteredBiomes()
        val cardH = 34
        val cardGap = 4
        val cardW = listW - 8

        for (i in filtered.indices) {
            val cy = listY + 3 + i * (cardH + cardGap) - leftScrollOffset.toInt()
            if (cy + cardH < listY || cy > listY + listH) continue

            val biome = filtered[i]
            val isSelected = selectedBiome?.id == biome.id
            val isUnlocked = BiomeLocatorClientState.isBiomeUnlocked(biome.id)
            val isTracked = BiomeLocatorClientState.isBiomeTracked(biome.id)
            val isHovered = mouseX >= listX + 3 && mouseX <= listX + listW - 5 && mouseY >= cy && mouseY <= cy + cardH

            val bg = when {
                isSelected -> 0xFF3A2A1B.toInt()
                isHovered -> 0xFF261D15.toInt()
                else -> 0xFF1A1410.toInt()
            }
            val border = when {
                isSelected -> 0xFFD4AF37.toInt()
                isTracked -> 0xFF5BC0A7.toInt()
                isHovered -> 0xFF5A432F.toInt()
                else -> 0xFF30241A.toInt()
            }

            graphics.fill(listX + 3, cy, listX + 3 + cardW, cy + cardH, bg)
            drawBorder(graphics, listX + 3, cy, cardW, cardH, border)

            // Biome Name translated to Spanish and strictly clipped to card width
            val nameColor = if (isSelected) 0xFFFFFAEB.toInt() else 0xFFE2D6C3.toInt()
            val displayBiomeName = BiomeLocatorServer.translateBiomeName(biome.id, biome.name)
            val clippedName = font.plainSubstrByWidth(displayBiomeName, cardW - 10)
            graphics.drawString(font, clippedName, listX + 7, cy + 4, nameColor, false)

            // Category & Status Tag strictly clipped to card width
            val statusLabel = if (isTracked) "§bRastreando" else if (isUnlocked) "§aRegistrado" else "§7Inexplorado"
            val subtextRaw = "${biome.category} • $statusLabel"
            val clippedSubtext = font.plainSubstrByWidth(subtextRaw, cardW - 10)
            graphics.drawString(font, clippedSubtext, listX + 7, cy + 18, 0xFF9E8B75.toInt(), false)
        }

        graphics.disableScissor()

        // Antique Scrollbar
        if (leftMaxScroll > 0) {
            val barW = 6
            val barH = listH - 6
            val barX = listX + listW - 7
            val barY = listY + 3
            val thumbH = maxOf(16, (barH * (listH / (leftMaxScroll + listH))).toInt())
            val thumbY = barY + ((barH - thumbH) * (leftScrollOffset / leftMaxScroll)).toInt()

            graphics.fill(barX, barY, barX + barW, barY + barH, 0xFF19130F.toInt())
            graphics.fill(barX, thumbY, barX + barW, thumbY + thumbH, 0xFFC59B27.toInt())
            graphics.renderOutline(barX, thumbY, barW, thumbH, 0xFFF3E9D2.toInt())
        }
    }

    private fun renderBiomeInspector(graphics: GuiGraphics, left: Int, top: Int, panelWidth: Int, panelHeight: Int, mouseX: Int, mouseY: Int, partialTick: Float) {
        val rightX = left + 242
        val rightY = top + 68
        val rightW = panelWidth - 256
        val rightH = panelHeight - 78

        // Frame
        graphics.fill(rightX, rightY, rightX + rightW, rightY + rightH, 0xFF14100D.toInt())
        drawBorder(graphics, rightX, rightY, rightW, rightH, 0xFF3A2D22.toInt())

        val biome = selectedBiome
        if (biome == null) {
            graphics.drawCenteredString(font, "Selecciona un bioma para consultar sus registros y especies.", rightX + rightW / 2, rightY + rightH / 2 - 6, 0xFF9E8B75.toInt())
            return
        }

        val isUnlocked = BiomeLocatorClientState.isBiomeUnlocked(biome.id)
        val isTracked = BiomeLocatorClientState.isBiomeTracked(biome.id)

        // Action Button(s)
        val actionBtnH = 18
        val actionBtnY = rightY + 8
        val titleMaxWidth: Int

        if (isUnlocked) {
            val primaryW = 64
            val primaryX = rightX + rightW - primaryW - 8
            val anotherW = 86
            val anotherX = primaryX - anotherW - 4
            titleMaxWidth = (anotherX - rightX - 10).coerceAtLeast(40)

            val isHoverAnother = mouseX >= anotherX && mouseX <= anotherX + anotherW && mouseY >= actionBtnY && mouseY <= actionBtnY + actionBtnH
            val anotherBg = if (isHoverAnother) 0xFF24546B.toInt() else 0xFF1A3D4F.toInt()
            graphics.fill(anotherX, actionBtnY, anotherX + anotherW, actionBtnY + actionBtnH, anotherBg)
            drawBorder(graphics, anotherX, actionBtnY, anotherW, actionBtnH, 0xFF5BC0E8.toInt())
            graphics.drawCenteredString(font, "Rastrear otro", anotherX + anotherW / 2, actionBtnY + 5, 0xFFE6FAFF.toInt())

            val isHoverPrimary = mouseX >= primaryX && mouseX <= primaryX + primaryW && mouseY >= actionBtnY && mouseY <= actionBtnY + actionBtnH
            val (btnLabel, btnBg, btnBorder) = if (isTracked) {
                Triple("Detener", if (isHoverPrimary) 0xFF6D2424.toInt() else 0xFF541A1A.toInt(), 0xFFA83636.toInt())
            } else {
                Triple("Rastrear", if (isHoverPrimary) 0xFF3D6B58.toInt() else 0xFF2C5243.toInt(), 0xFF5BC0A7.toInt())
            }
            graphics.fill(primaryX, actionBtnY, primaryX + primaryW, actionBtnY + actionBtnH, btnBg)
            drawBorder(graphics, primaryX, actionBtnY, primaryW, actionBtnH, btnBorder)
            graphics.drawCenteredString(font, btnLabel, primaryX + primaryW / 2, actionBtnY + 5, 0xFFFFFFFF.toInt())
        } else {
            val actionBtnW = 138
            val actionBtnX = rightX + rightW - actionBtnW - 8
            titleMaxWidth = (actionBtnX - rightX - 12).coerceAtLeast(40)
            val isHoverAction = mouseX >= actionBtnX && mouseX <= actionBtnX + actionBtnW && mouseY >= actionBtnY && mouseY <= actionBtnY + actionBtnH

            val (btnLabel, btnBg, btnBorder) = if (BiomeLocatorClientState.tokens > 0) {
                Triple("Desbloquear (1 Ficha)", if (isHoverAction) 0xFF6B551C.toInt() else 0xFF524013.toInt(), 0xFFD4AF37.toInt())
            } else {
                Triple("Fichas Insuficientes", 0xFF261D15.toInt(), 0xFF3D2F23.toInt())
            }

            graphics.fill(actionBtnX, actionBtnY, actionBtnX + actionBtnW, actionBtnY + actionBtnH, btnBg)
            drawBorder(graphics, actionBtnX, actionBtnY, actionBtnW, actionBtnH, btnBorder)
            graphics.drawCenteredString(font, btnLabel, actionBtnX + actionBtnW / 2, actionBtnY + 5, 0xFFFFFFFF.toInt())
        }

        // Inspector Header strictly bounded to not touch action button(s)
        val displayBiomeTitle = BiomeLocatorServer.translateBiomeName(biome.id, biome.name)
        val clippedTitle = font.plainSubstrByWidth(displayBiomeTitle, titleMaxWidth)
        graphics.drawString(font, clippedTitle, rightX + 8, rightY + 8, 0xFFFFFAEB.toInt(), false)

        val idRaw = "ID: ${biome.id}"
        val clippedId = font.plainSubstrByWidth(idRaw, titleMaxWidth)
        graphics.drawString(font, "§8$clippedId", rightX + 8, rightY + 20, 0xFF9E8B75.toInt(), false)

        // Rarity Filter Pills - Exact coordinates matching mouseClicked
        val rarityY = rightY + 32
        val rarities = listOf("ALL" to "Todos", "ultra-rare" to "Ultra Raros", "rare" to "Raros", "uncommon" to "Poco comunes", "common" to "Comunes")
        var rx = rightX + 8

        for ((rKey, rLabel) in rarities) {
            val rw = font.width(rLabel) + 10
            val isSelected = selectedRarityFilter == rKey
            val isHovered = mouseX >= rx && mouseX <= rx + rw && mouseY >= rarityY && mouseY <= rarityY + 16

            val bg = when {
                isSelected -> 0xFF4A3724.toInt()
                isHovered -> 0xFF2A2016.toInt()
                else -> 0xFF19130F.toInt()
            }
            graphics.fill(rx, rarityY, rx + rw, rarityY + 16, bg)
            val border = if (isSelected) 0xFFD4AF37.toInt() else 0xFF35261A.toInt()
            drawBorder(graphics, rx, rarityY, rw, 16, border)

            val textCol = if (isSelected) 0xFFFFE8A3.toInt() else 0xFF9E8B75.toInt()
            graphics.drawString(font, rLabel, rx + 5, rarityY + 4, textCol, false)
            rx += rw + 4
        }

        // Horizontal separator line
        graphics.fill(rightX + 6, rightY + 52, rightX + rightW - 6, rightY + 53, 0xFF3A2D22.toInt())

        // --- POKEMON GRID (CUADRÍCULA DE 2 COLUMNAS) ---
        val spawnsListY = rightY + 56
        val spawnsListH = rightH - 60
        enableScaledScissor(graphics, rightX + 2, spawnsListY, rightX + rightW - 2, spawnsListY + spawnsListH)

        val spawns = getFilteredSpawns()
        if (spawns.isEmpty()) {
            if (!BiomeLocatorClientState.biomeSpawnsCache.containsKey(biome.id)) {
                graphics.drawCenteredString(font, "Consultando archivos de hábitat...", rightX + rightW / 2, spawnsListY + 36, 0xFFBCA789.toInt())
            } else {
                graphics.drawCenteredString(font, "No se registran especies silvestres en este hábitat.", rightX + rightW / 2, spawnsListY + 36, 0xFF7A6A5C.toInt())
            }
        } else {
            val cardW = (rightW - 18) / 2
            val cardH = 80
            val cardGap = 6
            val animAngle = ((System.currentTimeMillis() % 16000L) / 16000f) * 360f

            for (i in spawns.indices) {
                val col = i % 2
                val row = i / 2
                val cx = rightX + 6 + col * (cardW + 6)
                val cy = spawnsListY + 4 + row * (cardH + cardGap) - rightScrollOffset.toInt()

                if (cy + cardH < spawnsListY || cy > spawnsListY + spawnsListH) continue

                val spawn = spawns[i]
                val (rarityCol, rarityBorder, rarityLabel) = when (spawn.bucket) {
                    "ultra-rare" -> Triple(0xFFFFE57F.toInt(), 0xFFD4AF37.toInt(), "Ultra Raro")
                    "rare" -> Triple(0xFFFF9BE8.toInt(), 0xFFC055A8.toInt(), "Raro")
                    "uncommon" -> Triple(0xFF7CE7F5.toInt(), 0xFF359FB0.toInt(), "Poco común")
                    else -> Triple(0xFF9DE6A6.toInt(), 0xFF4A8057.toInt(), "Común")
                }

                // Check if this card matches the active search query
                val isSearchHit = searchQuery.isNotBlank() && (spawn.displayName.lowercase().contains(searchQuery) || spawn.speciesId.lowercase().contains(searchQuery))

                // Card background (folio card)
                val cardBg = if (isSearchHit) 0xFF2E2415.toInt() else 0xFF19130F.toInt()
                val cardBorder = if (isSearchHit) 0xFFFFD700.toInt() else 0xFF3A2D22.toInt()
                graphics.fill(cx, cy, cx + cardW, cy + cardH, cardBg)
                drawBorder(graphics, cx, cy, cardW, cardH, cardBorder)

                // Left decorative rarity strip
                graphics.fill(cx, cy, cx + 3, cy + cardH, rarityBorder)

                // 3D Mini Pokemon Specimen Frame (upper-left)
                val modelBoxX = cx + 6
                val modelBoxY = cy + 6
                val modelBoxSize = 38
                graphics.fill(modelBoxX, modelBoxY, modelBoxX + modelBoxSize, modelBoxY + modelBoxSize, 0xFF100D0B.toInt())
                drawBorder(graphics, modelBoxX, modelBoxY, modelBoxSize, modelBoxSize, 0xFF2B2017.toInt())

                // STRICT SCISSOR FOR MODEL BOX: Prevents 3D model from EVER drawing outside the 38x38 box!
                enableScaledScissor(graphics, modelBoxX + 1, modelBoxY + 1, modelBoxX + modelBoxSize - 1, modelBoxY + modelBoxSize - 1)
                try {
                    val rawRes = ResourceLocation.tryParse(spawn.speciesId.lowercase())
                        ?: ResourceLocation.fromNamespaceAndPath("cobblemon", spawn.speciesId.lowercase())
                    val speciesObj = PokemonSpecies.getByIdentifier(rawRes) ?: run {
                        val cleanPath = rawRes.path
                            .replace("_galarian", "").replace("-galarian", "").replace("_galar", "").replace("-galar", "")
                            .replace("_alolan", "").replace("-alolan", "").replace("_alola", "").replace("-alola", "")
                            .replace("_hisuian", "").replace("-hisuian", "").replace("_hisui", "").replace("-hisui", "")
                            .replace("_paldean", "").replace("-paldean", "").replace("_paldea", "").replace("-paldea", "")
                        PokemonSpecies.getByIdentifier(ResourceLocation.fromNamespaceAndPath(rawRes.namespace, cleanPath))
                    }

                    val aspects = mutableSetOf<String>()
                    aspects.addAll(spawn.aspects.map { it.lowercase() })
                    val checkStr = "${spawn.speciesId} ${spawn.displayName} ${spawn.aspects.joinToString(" ")}".lowercase()
                    if (checkStr.contains("galar")) {
                        aspects.add("galarian")
                        aspects.add("galar")
                    }
                    if (checkStr.contains("hisui")) {
                        aspects.add("hisuian")
                        aspects.add("hisui")
                    }
                    if (checkStr.contains("alola")) {
                        aspects.add("alolan")
                        aspects.add("alola")
                    }
                    if (checkStr.contains("paldea")) {
                        aspects.add("paldean")
                        aspects.add("paldea")
                    }
                    if (checkStr.contains("valencia")) aspects.add("valencian")

                    if (speciesObj != null) {
                        val matchedForm = speciesObj.forms.firstOrNull { f ->
                            val fn = f.name.lowercase()
                            (aspects.contains("galarian") && (fn == "galar" || fn == "galarian")) ||
                                (aspects.contains("hisuian") && (fn == "hisui" || fn == "hisuian")) ||
                                (aspects.contains("alolan") && (fn == "alola" || fn == "alolan")) ||
                                (aspects.contains("paldean") && (fn == "paldea" || fn == "paldean"))
                        }
                        if (matchedForm != null) {
                            aspects.addAll(matchedForm.aspects.map { it.lowercase() })
                        }
                    }

                    val stateKey = "${spawn.speciesId}#${aspects.sorted().joinToString(",")}"
                    val cardState = cardStates.getOrPut(stateKey) { FloatingState() }
                    cardState.currentAspects = aspects

                    val cardRotation = Quaternionf()
                        .rotationY(Math.toRadians(animAngle.toDouble()).toFloat())
                        .rotateX(Math.toRadians(8.0).toFloat())

                    graphics.pose().pushPose()
                    graphics.pose().translate(modelBoxX + modelBoxSize / 2f, modelBoxY + 5.5f, 100f)
                    if (speciesObj != null) {
                        drawProfilePokemon(
                            renderablePokemon = RenderablePokemon(speciesObj, aspects),
                            matrixStack = graphics.pose(),
                            rotation = cardRotation,
                            poseType = PoseType.STAND,
                            state = cardState,
                            partialTicks = partialTick,
                            scale = 14.0f,
                            profileTransformType = ProfileTransformType.PROFILE
                        )
                    } else {
                        drawProfilePokemon(
                            species = rawRes,
                            matrixStack = graphics.pose(),
                            rotation = cardRotation,
                            poseType = PoseType.STAND,
                            state = cardState,
                            partialTicks = partialTick,
                            scale = 14.0f,
                            profileTransformType = ProfileTransformType.PROFILE
                        )
                    }
                    graphics.pose().popPose()
                    graphics.flush()
                } catch (_: Throwable) {}
                graphics.disableScissor()

                // Pokemon Info (upper-right of card)
                val infoX = cx + 48
                val infoW = cardW - 52
                val clippedName = font.plainSubstrByWidth(spawn.displayName, infoW)
                graphics.drawString(font, clippedName, infoX, cy + 6, 0xFFFFFAEB.toInt(), false)

                val lvlText = "Nv. ${spawn.minLevel}-${spawn.maxLevel}"
                val clippedLvl = font.plainSubstrByWidth(lvlText, infoW)
                graphics.drawString(font, clippedLvl, infoX, cy + 18, 0xFFBCA789.toInt(), false)

                val pctText = "$rarityLabel • ${spawn.percentage}%"
                val clippedPct = font.plainSubstrByWidth(pctText, infoW)
                graphics.drawString(font, clippedPct, infoX, cy + 30, rarityCol, false)

                // Lower section: Divider & Condition Badges
                val condDividerY = cy + 48
                graphics.fill(cx + 6, condDividerY, cx + cardW - 6, condDividerY + 1, 0xFF281E15.toInt())

                val timeBadge = when (spawn.timeOfDay) {
                    "Día" -> "Día"
                    "Noche" -> "Noche"
                    "Crepúsculo" -> "Ocaso"
                    else -> "Cualquiera"
                }
                val contextBadge = when {
                    spawn.context.contains("Agua") || spawn.context.contains("Pesca") -> "Agua"
                    spawn.context.contains("Cueva") -> "Cueva"
                    spawn.context.contains("Aire") -> "Aire"
                    else -> "Tierra"
                }

                val condLine = "$timeBadge • $contextBadge"
                val clippedCond = font.plainSubstrByWidth(condLine, cardW - 12)
                graphics.drawString(font, clippedCond, cx + 6, cy + 52, 0xFF9E8B75.toInt(), false)

                val extraInfo = if (spawn.weatherReq != "Cualquiera") "${spawn.weatherReq}" else if (spawn.lightReq != "Cualquiera") "${spawn.lightReq}" else ""
                if (extraInfo.isNotBlank()) {
                    val clippedExtra = font.plainSubstrByWidth(extraInfo, cardW - 12)
                    graphics.drawString(font, clippedExtra, cx + 6, cy + 64, 0xFF7A6A5C.toInt(), false)
                }
            }
        }

        graphics.disableScissor()

        // Right Scrollbar
        if (rightMaxScroll > 0) {
            val barW = 6
            val barH = spawnsListH
            val barX = rightX + rightW - 7
            val barY = spawnsListY
            val thumbH = maxOf(16, (barH * (spawnsListH / (rightMaxScroll + spawnsListH))).toInt())
            val thumbY = barY + ((barH - thumbH) * (rightScrollOffset / rightMaxScroll)).toInt()

            graphics.fill(barX, barY, barX + barW, barY + barH, 0xFF19130F.toInt())
            graphics.fill(barX, thumbY, barX + barW, thumbY + thumbH, 0xFFD4AF37.toInt())
            graphics.renderOutline(barX, thumbY, barW, thumbH, 0xFFF3E9D2.toInt())
        }
    }

    private fun renderTooltips(graphics: GuiGraphics, left: Int, top: Int, panelWidth: Int, panelHeight: Int, localMouseX: Int, localMouseY: Int, screenMouseX: Int, screenMouseY: Int) {
        val tabW = 86
        val tabH = 19
        val tabY = top + 8
        val dims = listOf("overworld" to "Overworld", "nether" to "Nether", "end" to "The End")

        for (i in dims.indices) {
            val (dimKey, label) = dims[i]
            val tx = left + 14 + i * (tabW + 6)
            if (localMouseX >= tx && localMouseX <= tx + tabW && localMouseY >= tabY && localMouseY <= tabY + tabH) {
                if (!BiomeLocatorClientState.isDimensionUnlocked(dimKey)) {
                    val tooltip = listOf(
                        Component.literal("§cDimensión Bloqueada"),
                        Component.literal("§7Viaja al " + label + " para registrar sus hábitats en este códex.")
                    )
                    graphics.renderComponentTooltip(font, tooltip, screenMouseX, screenMouseY)
                }
            }
        }

        // Tooltip for free "Rastrear otro" button
        selectedBiome?.let { b ->
            if (BiomeLocatorClientState.isBiomeUnlocked(b.id)) {
                val rightX = left + 242
                val rightY = top + 68
                val rightW = panelWidth - 256
                val primaryW = 64
                val primaryX = rightX + rightW - primaryW - 8
                val anotherW = 86
                val anotherX = primaryX - anotherW - 4
                val actionBtnY = rightY + 8
                val actionBtnH = 18
                if (localMouseX >= anotherX && localMouseX <= anotherX + anotherW && localMouseY >= actionBtnY && localMouseY <= actionBtnY + actionBtnH) {
                    val tooltip = listOf(
                        Component.literal("§bRastrear otro §a(Gratis)"),
                        Component.literal("§7Busca una instancia diferente de este bioma"),
                        Component.literal("§7sin gastar ninguna Ficha adicional.")
                    )
                    graphics.renderComponentTooltip(font, tooltip, screenMouseX, screenMouseY)
                }
            }
        }
    }

    private fun drawBorder(graphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int, color: Int) {
        graphics.fill(x, y, x + w, y + 1, color)
        graphics.fill(x, y + h - 1, x + w, y + h, color)
        graphics.fill(x, y + 1, x + 1, y + h - 1, color)
        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, color)
    }

    override fun isPauseScreen(): Boolean = false
}
