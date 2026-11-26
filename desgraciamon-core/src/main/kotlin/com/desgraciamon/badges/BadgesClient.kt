package com.desgraciamon.badges



import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4f
import org.lwjgl.glfw.GLFW
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap



object BadgesClientState {

    @Volatile

    var clientInitialized: Boolean = false



    val definitions = ConcurrentHashMap<String, BadgesSyncPayload.BadgeDefEntry>()

    val worldVisible = ConcurrentHashMap<UUID, List<String>>()

    val dynamicIconTextures = ConcurrentHashMap<String, ResourceLocation>()

    private val registeredDynamicTextures = ConcurrentHashMap<String, DynamicTexture>()

    var myUnlocked: List<String> = emptyList()

    var myHidden: Set<String> = emptySet()

    var completedOnTimeHitos: Set<String> = emptySet()

    var claimedHitoRewards: Set<String> = emptySet()

    var activeCatchupHitoId: String = ""

    var rctSummary: String = ""

    var rctAvailable: Boolean = false

    var rctDefeatedBadges: Set<String> = emptySet()

    var rctActiveRegion: String = ""

    var rctCompletedRegions: Set<String> = emptySet()

    fun effectiveHitosBadges(): Collection<String> =
        if (rctAvailable) rctDefeatedBadges else emptySet()



    @JvmStatic

    @Volatile

    var debugShowOwnNameTag: Boolean = false



    fun update(payload: BadgesSyncPayload) {

        definitions.clear()

        for (d in payload.definitions) definitions[d.id.lowercase()] = d

        worldVisible.clear()

        worldVisible.putAll(payload.worldVisibleBadges)

        myUnlocked = payload.myUnlocked

        myHidden = payload.myHidden

    }



    fun reset() {

        definitions.clear()

        worldVisible.clear()

        myUnlocked = emptyList()

        myHidden = emptySet()

        completedOnTimeHitos = emptySet()

        claimedHitoRewards = emptySet()

        activeCatchupHitoId = ""

        rctSummary = ""

        rctAvailable = false

        rctDefeatedBadges = emptySet()

        rctActiveRegion = ""

        rctCompletedRegions = emptySet()

        clearDynamicIcons()
        HitosManager.resetForDisconnect()

    }

    fun getDynamicIconTexture(id: String): ResourceLocation? =
        dynamicIconTextures[id.lowercase()]

    fun updateDynamicIcons(iconPngs: Map<String, ByteArray>) {
        val client = Minecraft.getInstance()
        val texManager = client.textureManager
        val normalizedIncoming = LinkedHashMap<String, ByteArray>()
        for ((rawId, bytes) in iconPngs) {
            val clean = rawId.lowercase().trim()
            if (clean.isNotEmpty() && bytes.isNotEmpty()) {
                normalizedIncoming[clean] = bytes
            }
        }

        val staleIds = dynamicIconTextures.keys.filter { it !in normalizedIncoming.keys }
        for (stale in staleIds) {
            dynamicIconTextures.remove(stale)?.let { loc ->
                runCatching { texManager.release(loc) }
            }
            registeredDynamicTextures.remove(stale)?.let { tex ->
                runCatching { tex.close() }
            }
        }

        for ((id, bytes) in normalizedIncoming) {
            runCatching {
                val image = NativeImage.read(ByteArrayInputStream(bytes))
                val loc = ResourceLocation.fromNamespaceAndPath(BadgesMod.MOD_ID, "dynamic_badge/$id")
                registeredDynamicTextures.remove(id)?.let { old ->
                    runCatching { old.close() }
                }
                val dynTex = DynamicTexture(image)
                texManager.register(loc, dynTex)
                registeredDynamicTextures[id] = dynTex
                dynamicIconTextures[id] = loc
            }.onFailure {
                BadgesMod.LOGGER.warn("[Badges] No se pudo decodificar icono PNG dinamico para '$id': ${it.message}")
            }
        }
        com.desgraciamon.serverassets.ServerAssetsClientState.syncDynamicBadgeEmojis(dynamicIconTextures)
    }

    fun clearDynamicIcons() {
        val client = Minecraft.getInstance()
        val texManager = client.textureManager
        for ((id, loc) in dynamicIconTextures.entries.toList()) {
            dynamicIconTextures.remove(id)
            runCatching { texManager.release(loc) }
        }
        for ((id, tex) in registeredDynamicTextures.entries.toList()) {
            registeredDynamicTextures.remove(id)
            runCatching { tex.close() }
        }
        com.desgraciamon.serverassets.ServerAssetsClientState.syncDynamicBadgeEmojis(emptyMap())
    }



    /** Convierte una cadena que puede tener :emojis: de Chat Emojis en un Component. */

    fun processChatEmojis(component: Component): Component {
        val withServer = com.desgraciamon.serverassets.ServerAssetsClientState.processEmojis(component)
        if (!FabricLoader.getInstance().isModLoaded("chat_emojis")) return withServer

        return runCatching {

            val clazz = Class.forName("com.dr3sh.chatemojis.EmojiManager")

            val method = clazz.getMethod("process", Component::class.java)

            (method.invoke(null, withServer) as? Component) ?: withServer

        }.getOrDefault(withServer)

    }



    /** Renderiza el icono de una insignia aplicando tanto RichText (<prism>, <color>) como Chat Emojis (:nombre:). */

    fun renderBadgeIcon(id: String): Component {

        val clean = id.lowercase()

        val rawIcon = definitions[clean]?.icon ?: TextCatalog.badgeIconRaw(clean)

        val parsed = RichText.parse(rawIcon)

        return processChatEmojis(parsed)

    }



    fun renderBadgeName(id: String): Component {

        val clean = id.lowercase()

        val rawName = definitions[clean]?.name ?: TextCatalog.badgeNameRaw(clean)

        return processChatEmojis(RichText.parse(rawName))

    }



    fun renderBadgeDescription(id: String): Component {

        val clean = id.lowercase()

        val rawDesc = definitions[clean]?.description ?: TextCatalog.badgeDescriptionRaw(clean)

        return processChatEmojis(RichText.parse(rawDesc))

    }

    fun isNonHideable(id: String): Boolean {
        val clean = id.lowercase()
        if (clean == AdminAuth.ADMIN_BADGE_ID) return true
        return definitions[clean]?.nonHideable ?: TextCatalog.isNonHideable(clean)
    }

    fun hasAdminBadge(): Boolean {
        return myUnlocked.any { it.equals(AdminAuth.ADMIN_BADGE_ID, ignoreCase = true) }
    }



    /**

     * Construye el bloque de insignias que se dibuja a la izquierda del NameTag centrado

     * sobre la cabeza del jugador en el mundo 3D, sin desplazar el nombre del centro.

     */

    @JvmStatic
    fun hasVisibleNameTagBadges(uuid: UUID): Boolean = !worldVisible[uuid].isNullOrEmpty()

    @JvmStatic
    fun renderNameTagBadges(
        uuid: UUID,
        font: Font,
        matrix: Matrix4f,
        bufferSource: MultiBufferSource,
        startX: Float,
        y: Int,
        notSneaking: Boolean,
        bgAlpha: Int,
        packedLight: Int
    ) {
        val visible = worldVisible[uuid] ?: return
        if (visible.isEmpty()) return

        var cursorX = startX
        for ((index, id) in visible.withIndex()) {
            if (index > 0) cursorX += 2.0f
            val comp = renderBadgeIcon(id)
            font.drawInBatch(
                comp, cursorX, y.toFloat(),
                553648127,
                false, matrix, bufferSource,
                if (notSneaking) Font.DisplayMode.SEE_THROUGH else Font.DisplayMode.NORMAL,
                bgAlpha, packedLight
            )
            if (notSneaking) {
                font.drawInBatch(
                    comp, cursorX, y.toFloat(),
                    -1,
                    false, matrix, bufferSource,
                    Font.DisplayMode.NORMAL,
                    0, packedLight
                )
            }
            cursorX += font.width(comp).toFloat()
        }
    }

    @JvmStatic

    fun buildNameTagBadgesComponent(uuid: UUID): Component? {

        val visible = worldVisible[uuid] ?: return null

        if (visible.isEmpty()) return null

        val prefix = Component.empty()

        for ((index, id) in visible.withIndex()) {

            if (index > 0) prefix.append(Component.literal(" "))

            prefix.append(renderBadgeIcon(id))

        }

        return prefix

    }



    @JvmStatic

    fun decoratePlayerNameTag(uuid: UUID, originalDisplayName: Component): Component {

        val visible = worldVisible[uuid] ?: return originalDisplayName

        if (visible.isEmpty()) return originalDisplayName

        val prefix = Component.empty()

        for (id in visible) {

            prefix.append(renderBadgeIcon(id))

            prefix.append(Component.literal(" "))

        }

        return prefix.append(originalDisplayName)

    }



    @JvmStatic

    fun toggleOwnNameTag(): Boolean {

        val next = !debugShowOwnNameTag

        debugShowOwnNameTag = next

        runCatching {

            val guildState = Class.forName("net.desgraciamon.guilds.GuildClientState")

            guildState.getField("debugShowOwnNameTag").setBoolean(null, next)

        }

        return next

    }



    /** Llamado por DiscordSettingsScreen (menú F7) mediante reflexión o directamente. */

    @JvmStatic

    fun openBadgesScreen(previous: Screen?) {

        val client = Minecraft.getInstance()

        client.setScreen(BadgesSettingsScreen(previous))

    }



    @JvmStatic

    fun f7ButtonSummary(): Component {

        val total = myUnlocked.size

        val visible = myUnlocked.count { it !in myHidden }

        return TextCatalog.component("gui.f7.button", "visible" to visible, "total" to total)

    }

}



class BadgesClient : ClientModInitializer {

    override fun onInitializeClient() {

        if (BadgesClientState.clientInitialized) return

        BadgesClientState.clientInitialized = true



        TextCatalog.load()

        BadgeNetwork.registerCommon()



        ClientPlayNetworking.registerGlobalReceiver(BadgesSyncPayload.TYPE) { payload, context ->

            context.client().execute {

                BadgesClientState.update(payload)

                (context.client().screen as? BadgesSettingsScreen)?.refreshButtons()

            }

        }

        ClientPlayNetworking.registerGlobalReceiver(BadgeIconsSyncPayload.TYPE) { payload, context ->
            context.client().execute {
                BadgesClientState.updateDynamicIcons(payload.iconPngs)
                (context.client().screen as? BadgesSettingsScreen)?.refreshButtons()
            }
        }



        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->

            BadgesClientState.reset()

        }



        ClientPlayNetworking.registerGlobalReceiver(OpenHitosPayload.TYPE) { _, context ->

            context.client().execute {

                Minecraft.getInstance().setScreen(HitosTimelineScreen(Minecraft.getInstance().screen))

            }

        }



        ClientPlayNetworking.registerGlobalReceiver(HitosSyncPayload.TYPE) { payload, context ->

            context.client().execute {

                if (!Minecraft.getInstance().hasSingleplayerServer()) {
                    HitosManager.loadFromJson(payload.json)
                }

                BadgesClientState.completedOnTimeHitos = payload.completedOnTime

                BadgesClientState.claimedHitoRewards = payload.claimedRewards

                BadgesClientState.activeCatchupHitoId = payload.activeCatchupHitoId

                BadgesClientState.rctSummary = payload.rctSummary

                BadgesClientState.rctAvailable = payload.rctAvailable

                BadgesClientState.rctDefeatedBadges = payload.rctDefeatedBadges

                BadgesClientState.rctActiveRegion = payload.rctActiveRegion

                BadgesClientState.rctCompletedRegions = payload.rctCompletedRegions

                (Minecraft.getInstance().screen as? HitosTimelineScreen)?.refresh()
                (Minecraft.getInstance().screen as? com.desgraciamon.announcements.AnnouncementsAdminScreen)?.refreshHitosFromSync()

            }

        }



        val hitosKey = KeyBindingHelper.registerKeyBinding(

            KeyMapping("key.desgraciamon.hitos", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.category.desgraciamon3")

        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->

            while (hitosKey.consumeClick()) {

                if (client.player != null && client.screen == null) {

                    client.setScreen(HitosTimelineScreen(null))

                }

            }

        }



        ClientPlayNetworking.registerGlobalReceiver(BadgesToggleSelfNameTagPayload.TYPE) { _, context ->

            context.client().execute {

                val enabled = BadgesClientState.toggleOwnNameTag()

                val msg = if (enabled) {

                    RichText.parse("<prism=#55FF55,#00F2FE><bold>[DEBUG]</bold></prism> <color=#E8F5E9>Vista de tu propio NameTag <bold>ACTIVADA</bold>. Pulsa <color=#FFD700><bold>F5</bold></color> (tercera persona) para ver tus insignias, nombre centrado y <Gremio>.</color>")

                } else {

                    RichText.parse("<color=#FF6666><bold>[DEBUG]</bold> Vista de tu propio NameTag <bold>DESACTIVADA</bold>.</color>")

                }

                context.client().player?.sendSystemMessage(msg)

            }

        }



        // Integración automática con el menú F7 (DiscordSettingsScreen)

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->

            if (screen.javaClass.name == "com.desgraciamon.discordbridge.DiscordSettingsScreen") {

                if (ClientPlayNetworking.canSend(BadgeTogglePayload.TYPE)) {

                    ClientPlayNetworking.send(BadgeTogglePayload("request", ""))

                }

                F7ScreenIntegration.onDiscordSettingsScreenInit(screen)

            }

        }



        // Si discord-bridge no está cargado, registramos F7 directamente para abrir el gestor de insignias

        if (!FabricLoader.getInstance().isModLoaded("desgraciamon4_discord_bridge")) {

            val f7Key = KeyBindingHelper.registerKeyBinding(

                KeyMapping("key.desgraciamon4_badges.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, "key.category.desgraciamon3")

            )

            ClientTickEvents.END_CLIENT_TICK.register { client ->

                while (f7Key.consumeClick()) {

                    if (client.player != null) {

                        BadgesClientState.openBadgesScreen(client.screen)

                    }

                }

            }

        }



        // Client commands removed during command purge; use /insignias or F7.
    }

}



class BadgesSettingsScreen(private val previousScreen: Screen?) : Screen(TextCatalog.component("gui.title")) {

    companion object {

        private const val BASE_W = 352f

        private const val BASE_H = 260f

    }

    private var uiScale = 1.0f

    private var renderX = 0f

    private var renderY = 0f

    private var scrollOffset = 0
    private var requestedInitialSync = false



    private val openedAtNanos = System.nanoTime()

    private val rowButtons = mutableListOf<Pair<String, Button>>()

    private var showAllButton: Button? = null

    private var hideAllButton: Button? = null

    private var backButton: Button? = null



    private fun openingProgress(): Float = Math.min(1.0F, (System.nanoTime() - openedAtNanos) / 280_000_000.0F)



    override fun renderBlurredBackground(delta: Float) {}

    override fun renderMenuBackground(graphics: GuiGraphics) {}



    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {

        if (openingProgress() < 1.0F) return true

        val localX = (mouseX - renderX) / uiScale

        val localY = (mouseY - renderY) / uiScale

        return super.mouseClicked(localX, localY, button)

    }



    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {

        val localX = (mouseX - renderX) / uiScale

        val localY = (mouseY - renderY) / uiScale

        return super.mouseReleased(localX, localY, button)

    }



    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {

        val localX = (mouseX - renderX) / uiScale

        val localY = (mouseY - renderY) / uiScale

        val localDragX = dragX / uiScale

        val localDragY = dragY / uiScale

        return super.mouseDragged(localX, localY, button, localDragX, localDragY)

    }



    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {

        val unlocked = BadgesClientState.myUnlocked

        val maxScroll = (unlocked.size - 6).coerceAtLeast(0)

        if (maxScroll > 0) {

            val old = scrollOffset

            scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxScroll)

            if (old != scrollOffset) {

                init()

                return true

            }

        }

        val localX = (mouseX - renderX) / uiScale

        val localY = (mouseY - renderY) / uiScale

        return super.mouseScrolled(localX, localY, horizontalAmount, verticalAmount)

    }



    override fun init() {

        clearWidgets()

        rowButtons.clear()



        if (!requestedInitialSync && ClientPlayNetworking.canSend(BadgeTogglePayload.TYPE)) {
            requestedInitialSync = true
            ClientPlayNetworking.send(BadgeTogglePayload("request", ""))
        }



        val margin = 8f

        uiScale = minOf(1.0f, (width - margin * 2) / BASE_W, (height - margin * 2) / BASE_H)

        renderX = (width - BASE_W * uiScale) / 2f

        renderY = (height - BASE_H * uiScale) / 2f



        val unlocked = BadgesClientState.myUnlocked

        val maxScroll = (unlocked.size - 6).coerceAtLeast(0)

        scrollOffset = scrollOffset.coerceIn(0, maxScroll)



        val buttonWidth = 320

        val centerX = (BASE_W.toInt() - buttonWidth) / 2

        var y = 44



        for (id in unlocked.drop(scrollOffset).take(6)) {
            val nonHideable = BadgesClientState.isNonHideable(id)
            val btn = addRenderableWidget(
                textlessButton(centerX, y, buttonWidth, 22, rowLabel(id)) {
                    if (!nonHideable && ClientPlayNetworking.canSend(BadgeTogglePayload.TYPE)) {
                        ClientPlayNetworking.send(BadgeTogglePayload("toggle", id))
                    }
                }
            )
            btn.active = !nonHideable
            rowButtons.add(id to btn)
            y += 26
        }



        val bottomY = BASE_H.toInt() - 28

        val thirdW = (buttonWidth - 8) / 3

        showAllButton = addRenderableWidget(

            textlessButton(centerX, bottomY, thirdW, 20, Component.literal("Mostrar todas")) {

                if (ClientPlayNetworking.canSend(BadgeTogglePayload.TYPE)) {

                    ClientPlayNetworking.send(BadgeTogglePayload("show_all", ""))

                }

            }

        )

        hideAllButton = addRenderableWidget(

            textlessButton(centerX + thirdW + 4, bottomY, thirdW, 20, Component.literal("Ocultar todas")) {

                if (ClientPlayNetworking.canSend(BadgeTogglePayload.TYPE)) {

                    ClientPlayNetworking.send(BadgeTogglePayload("hide_all", ""))

                }

            }

        )

        backButton = addRenderableWidget(

            textlessButton(centerX + (thirdW + 4) * 2, bottomY, buttonWidth - (thirdW + 4) * 2, 20, TextCatalog.component("gui.button.back")) {

                onClose()

            }

        )

    }



    fun refreshButtons() {

        init()

    }



    private fun rowLabel(id: String): Component {
        val nonHideable = BadgesClientState.isNonHideable(id)
        val visible = nonHideable || id !in BadgesClientState.myHidden
        val state = if (nonHideable) {
            RichText.parse("<color=#FFD04E>Fija</color>")
        } else {
            TextCatalog.component(if (visible) "gui.state.visible" else "gui.state.hidden")
        }
        val base = Component.empty()
        if (BadgesClientState.getDynamicIconTexture(id) == null) {
            base.append(BadgesClientState.renderBadgeIcon(id))
            base.append(Component.literal("  "))
        }
        return base
            .append(BadgesClientState.renderBadgeName(id))
            .append(Component.literal("   ["))
            .append(state)
            .append(Component.literal("]"))
    }



    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {

        val localMouseX = ((mouseX - renderX) / uiScale).toInt()

        val localMouseY = ((mouseY - renderY) / uiScale).toInt()



        graphics.fill(0, 0, width, height, 0xB50A1012.toInt())

        val unlocked = BadgesClientState.myUnlocked



        val matrices = graphics.pose()

        matrices.pushPose()

        matrices.translate(renderX, renderY, 0.0f)

        matrices.scale(uiScale, uiScale, 1.0f)

        val progress = openingProgress()
        if (progress < 1.0f) {
            val inv = 1.0f - progress
            val eased = 1.0f - inv * inv * inv
            val animScale = 0.08f + eased * 0.92f
            val cx = BASE_W / 2.0f
            val cy = BASE_H / 2.0f
            matrices.translate(cx, cy, 0.0f)
            matrices.scale(animScale, animScale, 1.0f)
            matrices.translate(-cx, -cy, 0.0f)
        }



        val panelWidth = BASE_W.toInt()

        val panelHeight = BASE_H.toInt()

        val left = 0

        val top = 0



        // Panel estilo Gremio (Charcoal dark slate)

        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xF0121719.toInt())

        drawOutline(graphics, left, top, panelWidth, panelHeight, 0xFF2A3638.toInt())



        // Cabecera elegante con acento esmeralda

        graphics.fill(left + 1, top + 1, left + panelWidth - 1, top + 34, 0xFF192224.toInt())

        graphics.fill(left + 1, top + 34, left + panelWidth - 1, top + 35, 0xFF5BC0A7.toInt())



        graphics.drawString(font, "GESTOR DE INSIGNIAS", left + 10, top + 9, 0xFFFFFFFF.toInt(), false)
        val subtitleComp = TextCatalog.component("gui.subtitle")
        val maxSubWidth = (panelWidth - 20).coerceAtLeast(60)
        val subWidth = font.width(subtitleComp)
        if (subWidth > maxSubWidth) {
            val scale = (maxSubWidth.toFloat() / subWidth.toFloat()).coerceIn(0.6f, 1.0f)
            graphics.pose().pushPose()
            graphics.pose().translate((left + 10).toFloat(), (top + 21).toFloat(), 0f)
            graphics.pose().scale(scale, scale, 1f)
            graphics.drawString(font, subtitleComp, 0, 0, 0xFF8A9A96.toInt(), false)
            graphics.pose().popPose()
        } else {
            graphics.drawString(font, subtitleComp, left + 10, top + 21, 0xFF8A9A96.toInt(), false)
        }



        if (unlocked.isEmpty()) {

            graphics.drawCenteredString(font, TextCatalog.component("gui.empty"), panelWidth / 2, top + 62, 0xFF9DA79F.toInt())

        }



        var hoveredBadgeId: String? = null

        for ((id, btn) in rowButtons) {

            val isVis = id !in BadgesClientState.myHidden

            drawButtonRow(graphics, btn, localMouseX, localMouseY, accent = if (isVis) 0xFF5BC0A7.toInt() else 0xFFB7343C.toInt(), badgeId = id)

            if (localMouseX in btn.x until (btn.x + btn.width) && localMouseY in btn.y until (btn.y + btn.height)) {

                hoveredBadgeId = id

            }

        }



        drawButtonRow(graphics, showAllButton, localMouseX, localMouseY, accent = 0xFF5BC0A7.toInt())

        drawButtonRow(graphics, hideAllButton, localMouseX, localMouseY, accent = 0xFFB7343C.toInt())

        drawButtonRow(graphics, backButton, localMouseX, localMouseY, primary = true, accent = 0xFFFFD04E.toInt())



        // Scrollbar

        if (unlocked.size > 6) {

            val trackX = panelWidth - 10

            val trackY = 44

            val trackH = 6 * 26 - 4

            graphics.fill(trackX, trackY, trackX + 3, trackY + trackH, 0x40000000.toInt())

            val maxScroll = unlocked.size - 6

            val thumbH = ((6f / unlocked.size) * trackH).toInt().coerceAtLeast(12)

            val thumbY = trackY + ((scrollOffset.toFloat() / maxScroll) * (trackH - thumbH)).toInt()

            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFF5BC0A7.toInt())

        }



        children().filterIsInstance<net.minecraft.client.gui.components.Renderable>().forEach {

            it.render(graphics, localMouseX, localMouseY, partialTick)

        }



        // Tooltip al pasar el ratón por encima de una insignia

        if (hoveredBadgeId != null) {

            val tooltipLines = listOf(

                Component.empty().append(BadgesClientState.renderBadgeIcon(hoveredBadgeId)).append(Component.literal(" ")).append(BadgesClientState.renderBadgeName(hoveredBadgeId)),

                BadgesClientState.renderBadgeDescription(hoveredBadgeId)

            )

            graphics.renderComponentTooltip(font, tooltipLines, localMouseX, localMouseY)

        }



        matrices.popPose()

    }



    private fun drawButtonRow(
        graphics: GuiGraphics,
        button: Button?,
        localMouseX: Int,
        localMouseY: Int,
        primary: Boolean = false,
        accent: Int = 0xFFB7343C.toInt(),
        badgeId: String? = null
    ) {
        button ?: return

        val isHovered = localMouseX >= button.x && localMouseX < button.x + button.width &&
                        localMouseY >= button.y && localMouseY < button.y + button.height

        val bg = when {
            primary -> 0xFF244838.toInt()
            isHovered -> 0xFF243032.toInt()
            else -> 0xFF192224.toInt()
        }

        graphics.fill(button.x, button.y, button.x + button.width, button.y + button.height, bg)
        graphics.fill(button.x, button.y, button.x + 3, button.y + button.height, accent)
        drawOutline(graphics, button.x, button.y, button.width, button.height, if (isHovered) 0xFF3D4F52.toInt() else 0xFF222C2E.toInt())

        val iconTex = badgeId?.let { BadgesClientState.getDynamicIconTexture(it) }
        if (iconTex != null) {
            val iconSize = 14
            val gap = 5
            val textW = font.width(button.message)
            val totalW = iconSize + gap + textW
            val startX = button.x + (button.width - totalW) / 2
            val iconY = button.y + (button.height - iconSize) / 2
            RenderSystem.enableBlend()
            graphics.blit(iconTex, startX, iconY, 0.0f, 0.0f, iconSize, iconSize, iconSize, iconSize)
            RenderSystem.disableBlend()
            graphics.drawString(font, button.message, startX + iconSize + gap, button.y + 7, 0xFFF0F4F2.toInt(), false)
        } else {
            graphics.drawCenteredString(font, button.message, button.x + button.width / 2, button.y + 6, 0xFFF0F4F2.toInt())
        }
    }



    private fun drawOutline(graphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int, color: Int) {

        graphics.fill(x, y, x + w, y + 1, color)

        graphics.fill(x, y + h - 1, x + w, y + h, color)

        graphics.fill(x, y + 1, x + 1, y + h - 1, color)

        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, color)

    }



    private fun textlessButton(x: Int, y: Int, w: Int, h: Int, message: Component, onPress: (Button) -> Unit): Button =

        object : Button(x, y, w, h, message, onPress, { narration -> narration.get() }) {

            override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {}

        }



    override fun onClose() {

        minecraft?.setScreen(previousScreen)

    }

}

