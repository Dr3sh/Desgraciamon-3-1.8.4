package com.desgraciamon.badges

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import java.io.File
import java.nio.charset.StandardCharsets

object TextCatalog {
    data class DefaultBadge(
        val id: String,
        val icon: String,
        val name: String,
        val description: String,
        val nonHideable: Boolean = false
    )

    val DEFAULT_BADGES: List<DefaultBadge> = emptyList()

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val file: File by lazy {
        val dir = File("config/desgraciamon4_badges/.texts")
        dir.mkdirs()
        runCatching { java.nio.file.Files.setAttribute(dir.toPath(), "dos:hidden", true) }
        val f = File(dir, "texts.json")
        val legacy = File("config/desgraciamon4_badges/texts.json")
        if (!f.exists() && legacy.exists()) {
            runCatching { java.nio.file.Files.move(legacy.toPath(), f.toPath()) }
        }
        f
    }
    val iconsDir = File("config/desgraciamon4_badges/icons")
    const val MAX_ICON_PNG_BYTES = 65536
    private const val MAX_TOTAL_ICONS_BYTES = 768 * 1024
    private val VALID_BADGE_ID = Regex("^[a-z0-9_\\-]+(?:/[a-z0-9_\\-]+)*$")
    private val PNG_HEADER = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    private val defaults: LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
        put("badge.unlocked.broadcast", "<prism=#FFD700,#FF3CAC,#00F2FE><bold>¡INSIGNIA DESBLOQUEADA!</bold></prism> <color=#FFFFFF>{player}</color> obtuvo la medalla {badge} <color=#AAAAAA>({name})</color>!")
        put("badge.unlocked.personal", "<color=#55FF55>¡Has recibido la insignia</color> {badge} {name}<color=#55FF55>! Puedes activarla o desactivarla con</color> <bold>[{key}]</bold><color=#55FF55>.</color>")
        put("badge.revoked.admin", "<color=#FF6666>Se quitó la insignia</color> <bold>{id}</bold> <color=#FF6666>de {player}.</color>")
        put("badge.granted.admin", "<color=#55FF55>Insignia</color> {badge} {name} <color=#55FF55>otorgada a {player}.</color>")
        put("badge.already_owned", "<color=#FFAA00>{player} ya tenía la insignia {id}.</color>")
        put("badge.unknown", "<color=#FF5555>No existe la insignia '{id}'. Usa /insignias lista para ver las disponibles.</color>")
        put("badge.list.header", "<prism=#00F2FE,#4FACFE><bold>=== Catálogo de Insignias ({count}) ===</bold></prism>")
        put("badge.list.entry", "{badge} <bold>{id}</bold> <color=#888888>—</color> {name} <color=#777777>({description})</color>")
        put("badge.player.header", "<prism=#FFD700,#FF8C00><bold>Insignias de {player} ({count}):</bold></prism>")
        put("badge.player.empty", "<color=#AAAAAA>{player} aún no tiene insignias.</color>")
        put("badge.hover.format", "{badge} {name}\n{description}")
        put("gui.title", "Mis Insignias (Badges)")
        put("gui.subtitle", "Haz clic en tus medallas para mostrarlas u ocultarlas en el chat y sobre tu nombre")
        put("gui.empty", "Todavía no tienes insignias desbloqueadas.")
        put("gui.state.visible", "<color=#55FF55>Visible</color>")
        put("gui.state.hidden", "<color=#FF6666>Oculta</color>")
        put("gui.button.back", "Volver")
        put("gui.f7.button", "Insignias (Badges): {visible}/{total} visibles")

        for (b in DEFAULT_BADGES) {
            put("badge.${b.id}.icon", b.icon)
            put("badge.${b.id}.name", b.name)
            put("badge.${b.id}.description", b.description)
            if (b.nonHideable) {
                put("badge.${b.id}.non_hideable", "true")
            }
        }
    }

    private var texts: Map<String, String> = defaults
    @Volatile
    private var iconPngs: Map<String, ByteArray> = emptyMap()
    @Volatile
    private var lastLoadedTimestamp: Long = 0L
    @Volatile
    private var lastIconsSignature: String = ""

    private fun isValidPng(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_HEADER.size || bytes.size > MAX_ICON_PNG_BYTES) return false
        for (i in PNG_HEADER.indices) {
            if (bytes[i] != PNG_HEADER[i]) return false
        }
        return true
    }

    private fun listIconFiles(): List<Pair<String, File>> {
        if (!iconsDir.isDirectory) return emptyList()
        val basePath = iconsDir.toPath().toAbsolutePath().normalize()
        return iconsDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".png", ignoreCase = true) }
            .mapNotNull { file ->
                val rel = basePath.relativize(file.toPath().toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/')
                val id = rel.lowercase().removeSuffix(".png").trim()
                if (id.isEmpty() || !VALID_BADGE_ID.matches(id)) null else id to file
            }
            .sortedBy { it.first }
            .toList()
    }

    private fun computeIconsSignature(): String {
        val files = listIconFiles()
        if (files.isEmpty()) return ""
        return files.joinToString("|") { (id, f) -> "$id:${f.length()}:${f.lastModified()}" }
    }

    private fun loadIconPngs() {
        runCatching {
            iconsDir.mkdirs()
            val files = listIconFiles()
            val loaded = LinkedHashMap<String, ByteArray>()
            var totalBytes = 0
            for ((id, f) in files) {
                val len = f.length()
                if (len <= 0L || len > MAX_ICON_PNG_BYTES) {
                    BadgesMod.LOGGER.warn("[Badges] Icono PNG ignorado (${f.name}): tamano $len bytes excede limite de $MAX_ICON_PNG_BYTES bytes.")
                    continue
                }
                val bytes = f.readBytes()
                if (!isValidPng(bytes)) {
                    BadgesMod.LOGGER.warn("[Badges] Icono PNG ignorado (${f.name}): cabecera PNG invalida.")
                    continue
                }
                if (totalBytes + bytes.size > MAX_TOTAL_ICONS_BYTES) {
                    BadgesMod.LOGGER.warn("[Badges] Limite total de sincronizacion de iconos PNG alcanzado; omitiendo ${f.name}.")
                    break
                }
                loaded[id] = bytes
                totalBytes += bytes.size
            }
            for (b in DEFAULT_BADGES) {
                if (!loaded.containsKey(b.id)) {
                    TextCatalog::class.java.getResourceAsStream("/assets/desgraciamon4_badges/textures/badges/${b.id}.png")?.use { stream ->
                        val bytes = stream.readBytes()
                        if (isValidPng(bytes) && bytes.size <= MAX_ICON_PNG_BYTES) {
                            loaded[b.id] = bytes
                        }
                    }
                }
            }
            iconPngs = loaded
            lastIconsSignature = computeIconsSignature()
        }.onFailure {
            BadgesMod.LOGGER.error("No se pudieron cargar los iconos PNG en ${iconsDir.path}", it)
        }
    }

    fun allIconPngs(): Map<String, ByteArray> = iconPngs

    fun hasIconPng(id: String): Boolean = iconPngs.containsKey(id.lowercase())

    fun load() {
        runCatching {
            file.parentFile?.mkdirs()
            iconsDir.mkdirs()
            if (!file.exists()) {
                file.writeText(gson.toJson(defaults), StandardCharsets.UTF_8)
            }
            lastLoadedTimestamp = file.lastModified()
            val type = object : TypeToken<LinkedHashMap<String, String>>() {}.type
            val overrides: Map<String, String> = file.reader(StandardCharsets.UTF_8).use { gson.fromJson(it, type) ?: emptyMap() }
            val merged = LinkedHashMap<String, String>()
            merged.putAll(defaults)
            merged.putAll(overrides)
            if (merged["badge.admin.icon"] == "<prism=#FF3B3B,#FF9F1C><bold>[ADMIN]</bold></prism>") {
                defaults["badge.admin.icon"]?.let { merged["badge.admin.icon"] = it }
            }
            // Eliminar antiguas insignias automaticas de gimnasio del catalogo
            val removedGymIds = GymBadges.BY_BADGE_ID.keys
            merged.keys.removeIf { k ->
                if (k.startsWith("badge.")) {
                    val seg = k.removePrefix("badge.").substringBefore('.')
                    seg in removedGymIds
                } else {
                    false
                }
            }
            texts = merged
            file.writeText(gson.toJson(merged), StandardCharsets.UTF_8)
            lastLoadedTimestamp = file.lastModified()
            loadIconPngs()
        }.onFailure {
            texts = defaults
            loadIconPngs()
            BadgesMod.LOGGER.error("No se pudo cargar config/desgraciamon4_badges/texts.json", it)
        }
    }

    fun checkAndReloadIfModified(server: net.minecraft.server.MinecraftServer): Boolean {
        val textsChanged = file.exists() && lastLoadedTimestamp > 0L && file.lastModified() > lastLoadedTimestamp
        val iconsChanged = computeIconsSignature() != lastIconsSignature
        if (textsChanged || iconsChanged) {
            load()
            com.desgraciamon.serverassets.ServerAssetsManager.reloadAndSync(server)
            BadgeNetwork.syncAll(server, includeIcons = true)
            BadgesMod.LOGGER.info(
                "[Badges] Catalogo/iconos modificados en disco. Sincronizadas ${allBadgeIds().size} insignias y ${iconPngs.size} iconos PNG en vivo."
            )
            return true
        }
        return false
    }

    fun reload(): Boolean {
        load()
        return true
    }

    fun raw(key: String): String = texts[key] ?: defaults[key] ?: key

    fun component(key: String, vararg values: Pair<String, Any?>): MutableComponent {
        val template = texts[key] ?: defaults[key] ?: key
        return RichText.parse(template, values.associate { it.first to it.second })
    }

    /** Devuelve todos los IDs de insignias definidos en texts.json (`badge.<id>.icon`) o como PNG en `icons/<id>.png`. */
    fun allBadgeIds(): List<String> {
        val ids = LinkedHashSet<String>()
        for (b in DEFAULT_BADGES) ids.add(b.id)
        for (k in texts.keys) {
            if (k.startsWith("badge.") && k.endsWith(".icon")) {
                val id = k.removePrefix("badge.").removeSuffix(".icon").trim()
                if (id.isNotEmpty() && !id.contains('.')) ids.add(id)
            }
        }
        for (id in iconPngs.keys) {
            ids.add(id)
        }
        return ids.toList()
    }

    fun badgeExists(id: String): Boolean {
        val clean = id.lowercase()
        return texts.containsKey("badge.$clean.icon") || defaults.containsKey("badge.$clean.icon") || iconPngs.containsKey(clean)
    }

    fun badgeIconRaw(id: String): String {
        val clean = id.lowercase()
        return texts["badge.$clean.icon"] ?: defaults["badge.$clean.icon"] ?: "[${clean.uppercase()}]"
    }

    fun badgeNameRaw(id: String): String {
        val clean = id.lowercase()
        return texts["badge.$clean.name"] ?: defaults["badge.$clean.name"] ?: clean.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    fun badgeDescriptionRaw(id: String): String {
        val clean = id.lowercase()
        return texts["badge.$clean.description"] ?: defaults["badge.$clean.description"] ?: "Insignia personalizada del servidor."
    }
    fun isNonHideable(id: String): Boolean {
        val clean = id.lowercase()
        if (clean == AdminAuth.ADMIN_BADGE_ID) return true
        val rawVal = texts["badge.$clean.non_hideable"] ?: defaults["badge.$clean.non_hideable"]
        return rawVal.equals("true", ignoreCase = true)
    }

    /**Construye el componente de una medalla con su HoverEvent mostrando el nombre y descripción.*/
    fun badgeComponent(id: String, withHover: Boolean = true): MutableComponent {
        val clean = id.lowercase()
        val iconComp = RichText.parse(badgeIconRaw(clean))
        if (!withHover) return iconComp
        val nameComp = RichText.parse(badgeNameRaw(clean))
        val descComp = RichText.parse(badgeDescriptionRaw(clean))
        val hoverText = component(
            "badge.hover.format", "badge" to RichText.parse(badgeIconRaw(clean)),
            "name" to nameComp,
            "description" to descComp
        )
        val hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverText)
        return applyHoverRecursively(iconComp, hoverEvent)
    }

    private fun applyHoverRecursively(comp: Component, hover: HoverEvent): MutableComponent {
        val copy = comp.plainCopy().withStyle(comp.style.withHoverEvent(hover))
        for (sibling in comp.siblings) {
            copy.append(applyHoverRecursively(sibling, hover))
        }
        return copy
    }

    /** Construye el bloque `[badge1 badge2]` para anteponer al nombre del jugador en el chat. */
    fun formatBadgesPrefix(badgeIds: List<String>, withHover: Boolean = true): MutableComponent {
        val out = Component.empty()
        val valid = badgeIds.filter { badgeExists(it) }
        if (valid.isEmpty()) return out
        for (id in valid) {
            out.append(badgeComponent(id, withHover))
            out.append(Component.literal(" ").withStyle(Style.EMPTY))
        }
        return out
    }

    /** Construye el bloque de medallas para colocar a la DERECHA del nombre del jugador: ` [badge1] [badge2]`. */
    fun formatBadgesSuffix(badgeIds: List<String>, withHover: Boolean = true): MutableComponent {
        val out = Component.empty()
        val valid = badgeIds.filter { badgeExists(it) }
        if (valid.isEmpty()) return out
        for (id in valid) {
            out.append(Component.literal(" ").withStyle(Style.EMPTY))
            out.append(badgeComponent(id, withHover))
        }
        return out
    }
}

