package com.desgraciamon.discordbridge

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

object HitosDiscordHelper {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val configFile = File("config/desgraciamon4_badges/hitos.json")
    val caracasZone: ZoneId = ZoneId.of("America/Caracas")

    data class HitoEntry(
        val id: String,
        val title: String,
        val subtitle: String,
        val leaderOrBoss: String,
        val dayOffset: Int,
        val durationDays: Int,
        val dateRange: String,
        val iconItemId: String,
        val rewardSummary: String,
        val rewardDollars: Long,
        val progression: Boolean
    )

    private val defaultBadgeIcons = mapOf(
        "gym1" to "cobbleversebadges:kanto_boulder_badge",
        "gym2" to "cobbleversebadges:kanto_cascade_badge",
        "gym3" to "cobbleversebadges:kanto_thunder_badge",
        "gym4" to "cobbleversebadges:kanto_rainbow_badge",
        "gym5" to "cobbleversebadges:kanto_soul_badge",
        "gym6" to "cobbleversebadges:kanto_marsh_badge",
        "gym7" to "cobbleversebadges:kanto_volcano_badge",
        "gym8" to "cobbleversebadges:kanto_earth_badge",
        "league" to "cobbleversebadges:kanto_league_trophy"
    )

    val presetIcons: List<Pair<String, String>> = listOf(
        "Automático" to "auto",
        "Medalla Roca" to "cobbleversebadges:kanto_boulder_badge",
        "Medalla Cascada" to "cobbleversebadges:kanto_cascade_badge",
        "Medalla Trueno" to "cobbleversebadges:kanto_thunder_badge",
        "Medalla Arcoíris" to "cobbleversebadges:kanto_rainbow_badge",
        "Medalla Alma" to "cobbleversebadges:kanto_soul_badge",
        "Medalla Pantano" to "cobbleversebadges:kanto_marsh_badge",
        "Medalla Volcán" to "cobbleversebadges:kanto_volcano_badge",
        "Medalla Tierra" to "cobbleversebadges:kanto_earth_badge",
        "Trofeo Liga" to "cobbleversebadges:kanto_league_trophy",
        "Estrella del Nether" to "minecraft:nether_star",
        "Reloj (Evento)" to "minecraft:clock",
        "Poké Ball" to "cobblemon:poke_ball",
        "Master Ball" to "cobblemon:master_ball",
        "Caramelo Raro" to "cobblemon:rare_candy",
        "Esmeralda" to "minecraft:emerald",
        "Espada de Diamante" to "minecraft:diamond_sword",
        "Corona Dorada" to "minecraft:golden_helmet"
    )

    fun todayCaracas(): LocalDate = LocalDate.now(caracasZone)

    private fun serverLogicalDateCaracas(): LocalDate =
        ZonedDateTime.now(caracasZone).minusHours(8).toLocalDate()

    fun triggerServerSync() {
        val server = DiscordBridgeRuntime.server ?: return
        server.execute {
            runCatching {
                val clazz = Class.forName("com.desgraciamon.badges.HitosManager")
                val instance = clazz.getField("INSTANCE").get(null)
                clazz.getMethod("loadAndSync", net.minecraft.server.MinecraftServer::class.java).invoke(instance, server)
            }.onFailure {
                DiscordBridgeMod.logger.error("Error triggering HitosManager loadAndSync via reflection", it)
            }
        }
    }

    private fun readConfigJson(): JsonObject {
        if (!configFile.exists()) return JsonObject()
        return runCatching {
            configFile.reader(Charsets.UTF_8).use {
                JsonParser.parseReader(it).asJsonObject
            }
        }.getOrDefault(JsonObject())
    }

    private fun writeConfigJson(json: JsonObject) {
        runCatching {
            configFile.parentFile?.mkdirs()
            configFile.writer(Charsets.UTF_8).use {
                gson.toJson(json, it)
            }
            triggerServerSync()
        }
    }

    fun getHitosRoleId(config: DiscordBridgeConfig): String {
        val json = readConfigJson()
        val fromHitosJson = if (json.has("discordAdminRoleId") && !json.get("discordAdminRoleId").isJsonNull) {
            json.get("discordAdminRoleId").asString.trim()
        } else ""
        return fromHitosJson.ifBlank { config.hitosRoleId.trim() }
    }

    fun setHitosRoleId(rawRoleId: String, config: DiscordBridgeConfig): String {
        val clean = if (
            rawRoleId.isBlank() ||
            rawRoleId.equals("ninguno", ignoreCase = true) ||
            rawRoleId.equals("none", ignoreCase = true) ||
            rawRoleId.trim() == "0"
        ) {
            ""
        } else {
            rawRoleId.replace(Regex("[^0-9]"), "")
        }
        config.hitosRoleId = clean
        config.save()

        val json = readConfigJson()
        json.addProperty("discordAdminRoleId", clean)
        writeConfigJson(json)
        return clean
    }

    fun getDia1(): String {
        val json = readConfigJson()
        return if (json.has("serverStartDate")) json.get("serverStartDate").asString else "2026-10-28"
    }

    fun getDia1Date(): LocalDate {
        return runCatching { LocalDate.parse(getDia1()) }.getOrElse { todayCaracas() }
    }

    fun getManualCurrentDay(json: JsonObject = readConfigJson()): Int {
        return if (json.has("manualCurrentDay") && !json.get("manualCurrentDay").isJsonNull) {
            json.get("manualCurrentDay").asInt.coerceAtLeast(0)
        } else 0
    }

    fun getEffectiveCurrentDay(json: JsonObject = readConfigJson()): Int {
        val manual = getManualCurrentDay(json)
        if (manual > 0) return manual
        val baseStr = if (json.has("serverStartDate")) json.get("serverStartDate").asString else "2026-10-28"
        val baseDate = try { LocalDate.parse(baseStr) } catch (_: Exception) { return 1 }
        return (ChronoUnit.DAYS.between(baseDate, serverLogicalDateCaracas()).toInt() + 1).coerceAtLeast(1)
    }

    fun setDia1(dateStr: String): Boolean {
        val trimmed = dateStr.trim()
        val finalDate = if (trimmed.equals("hoy", ignoreCase = true)) {
            todayCaracas().toString()
        } else {
            trimmed
        }
        return try {
            LocalDate.parse(finalDate)
            val json = readConfigJson()
            json.addProperty("serverStartDate", finalDate)
            recalculateJsonDates(json)
            writeConfigJson(json)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun setCurrentDay(day: Int): Int {
        val normalized = day.coerceAtLeast(0)
        val json = readConfigJson()
        json.addProperty("manualCurrentDay", normalized)
        recalculateJsonDates(json)
        writeConfigJson(json)
        return getEffectiveCurrentDay(json)
    }

    fun listMilestones(): List<HitoEntry> {
        val json = readConfigJson()
        if (!json.has("milestones")) return emptyList()
        val array = json.getAsJsonArray("milestones")
        return array.mapNotNull { elem ->
            runCatching { parseEntry(elem.asJsonObject) }.getOrNull()
        }.sortedBy { it.dayOffset }
    }

    fun getMilestone(id: String): HitoEntry? {
        return listMilestones().firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
    }

    private fun parseEntry(obj: JsonObject): HitoEntry {
        val id = if (obj.has("id") && !obj.get("id").isJsonNull) obj.get("id").asString else ""
        val title = if (obj.has("title") && !obj.get("title").isJsonNull) obj.get("title").asString else "Hito"
        val subtitle = if (obj.has("subtitle") && !obj.get("subtitle").isJsonNull) obj.get("subtitle").asString else ""
        val leader = if (obj.has("leaderOrBoss") && !obj.get("leaderOrBoss").isJsonNull) obj.get("leaderOrBoss").asString else subtitle
        val dayOffset = (if (obj.has("dayOffset") && !obj.get("dayOffset").isJsonNull) obj.get("dayOffset").asInt else 1).coerceAtLeast(1)
        val durationDays = (if (obj.has("durationDays") && !obj.get("durationDays").isJsonNull) obj.get("durationDays").asInt else 2).coerceAtLeast(1)
        val dateRange = if (obj.has("dateRange") && !obj.get("dateRange").isJsonNull) obj.get("dateRange").asString else ""
        val icon = when {
            obj.has("iconItemId") && !obj.get("iconItemId").isJsonNull && obj.get("iconItemId").asString.isNotBlank() ->
                obj.get("iconItemId").asString
            obj.has("badgeItemId") && !obj.get("badgeItemId").isJsonNull && obj.get("badgeItemId").asString.isNotBlank() ->
                obj.get("badgeItemId").asString
            else -> "minecraft:nether_star"
        }
        val rewardSummary = if (obj.has("rewardSummary") && !obj.get("rewardSummary").isJsonNull) obj.get("rewardSummary").asString else ""
        val rewardDollars = if (obj.has("rewardDollars") && !obj.get("rewardDollars").isJsonNull) obj.get("rewardDollars").asLong else 0L
        val progression = if (obj.has("progression") && !obj.get("progression").isJsonNull) obj.get("progression").asBoolean else true
        return HitoEntry(
            id = id,
            title = title,
            subtitle = subtitle,
            leaderOrBoss = leader,
            dayOffset = dayOffset,
            durationDays = durationDays,
            dateRange = dateRange,
            iconItemId = icon,
            rewardSummary = rewardSummary,
            rewardDollars = rewardDollars,
            progression = progression
        )
    }

    private fun slugify(title: String, dia: Int): String {
        val base = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(24)
        return if (base.isNotBlank()) "${base}_d$dia" else "hito_d$dia"
    }

    private fun resolveIcon(id: String, providedIcon: String?, progression: Boolean): String {
        val trimmed = providedIcon?.trim().orEmpty()
        if (trimmed.isNotBlank() && !trimmed.equals("auto", ignoreCase = true)) {
            return trimmed
        }
        defaultBadgeIcons[id.lowercase()]?.let { return it }
        return if (progression) "minecraft:nether_star" else "minecraft:clock"
    }

    fun addOrUpdateMilestone(
        id: String?,
        dia: Int,
        icon: String?,
        title: String,
        sub: String,
        rewardText: String = "",
        rewardDollars: Long = 0L,
        progression: Boolean = true,
        durationDays: Int = 2
    ): String {
        val safeDia = dia.coerceAtLeast(1)
        val safeDuration = durationDays.coerceAtLeast(1)
        val cleanId = id?.trim()?.takeIf { it.isNotBlank() } ?: slugify(title, safeDia)
        val resolvedIcon = resolveIcon(cleanId, icon, progression)

        val json = readConfigJson()
        val array = if (json.has("milestones")) json.getAsJsonArray("milestones") else JsonArray()
        var found = false
        for (elem in array) {
            val obj = elem.asJsonObject
            if (obj.has("id") && obj.get("id").asString.equals(cleanId, ignoreCase = true)) {
                obj.addProperty("dayOffset", safeDia)
                obj.addProperty("durationDays", safeDuration)
                obj.addProperty("iconItemId", resolvedIcon)
                obj.addProperty("badgeItemId", if (progression) resolvedIcon else "")
                obj.addProperty("title", title)
                obj.addProperty("progression", progression)
                if (sub.isNotBlank()) {
                    obj.addProperty("subtitle", sub)
                    obj.addProperty("leaderOrBoss", sub)
                }
                if (rewardDollars > 0L) obj.addProperty("rewardDollars", rewardDollars)
                if (rewardText.isNotBlank()) obj.addProperty("rewardSummary", rewardText)
                found = true
                break
            }
        }
        if (!found) {
            val obj = JsonObject()
            obj.addProperty("id", cleanId)
            obj.addProperty("title", title)
            obj.addProperty("subtitle", sub.ifBlank { if (progression) "Hito de Progreso" else "Evento Opcional" })
            obj.addProperty("leaderOrBoss", sub.ifBlank { if (progression) "Desafío" else "Evento" })
            obj.addProperty("dayOffset", safeDia)
            obj.addProperty("durationDays", safeDuration)
            obj.addProperty("badgeItemId", if (progression) resolvedIcon else "")
            obj.addProperty("badgeDefId", if (progression) cleanId else "")
            obj.addProperty("iconItemId", resolvedIcon)
            obj.addProperty("iconEmoji", "")
            obj.addProperty("progression", progression)
            obj.addProperty("description", if (progression) "Hito activo del cronograma de Desgraciamon." else "Evento especial del servidor (opcional para el progreso).")
            obj.addProperty(
                "rewardSummary",
                rewardText.ifBlank {
                    if (rewardDollars > 0L) "$rewardDollars CobbleDollars"
                    else if (progression) "Recompensa por superación"
                    else "Evento informativo"
                }
            )
            obj.addProperty("rewardDollars", rewardDollars.coerceAtLeast(0L))
            obj.addProperty("isCompleted", false)
            array.add(obj)
        }
        json.add("milestones", array)
        recalculateJsonDates(json)
        writeConfigJson(json)
        return cleanId
    }

    fun updateMilestoneInfo(
        id: String,
        title: String,
        dia: Int,
        subtitle: String,
        durationDays: Int
    ): Boolean {
        val json = readConfigJson()
        if (!json.has("milestones")) return false
        val array = json.getAsJsonArray("milestones")
        var updated = false
        for (elem in array) {
            val obj = elem.asJsonObject
            if (obj.has("id") && obj.get("id").asString.equals(id.trim(), ignoreCase = true)) {
                obj.addProperty("title", title.trim())
                obj.addProperty("dayOffset", dia.coerceAtLeast(1))
                obj.addProperty("durationDays", durationDays.coerceAtLeast(1))
                if (subtitle.isNotBlank()) {
                    obj.addProperty("subtitle", subtitle.trim())
                    obj.addProperty("leaderOrBoss", subtitle.trim())
                }
                updated = true
                break
            }
        }
        if (updated) {
            recalculateJsonDates(json)
            writeConfigJson(json)
        }
        return updated
    }

    fun setMilestoneIcon(id: String, iconItemId: String): Boolean {
        val json = readConfigJson()
        if (!json.has("milestones")) return false
        val array = json.getAsJsonArray("milestones")
        var updated = false
        for (elem in array) {
            val obj = elem.asJsonObject
            if (obj.has("id") && obj.get("id").asString.equals(id.trim(), ignoreCase = true)) {
                val progression = if (obj.has("progression") && !obj.get("progression").isJsonNull) obj.get("progression").asBoolean else true
                val resolved = resolveIcon(id, iconItemId, progression)
                obj.addProperty("iconItemId", resolved)
                obj.addProperty("badgeItemId", if (progression) resolved else "")
                updated = true
                break
            }
        }
        if (updated) {
            writeConfigJson(json)
        }
        return updated
    }

    fun toggleMilestoneProgression(id: String): Boolean? {
        val json = readConfigJson()
        if (!json.has("milestones")) return null
        val array = json.getAsJsonArray("milestones")
        var newState: Boolean? = null
        for (elem in array) {
            val obj = elem.asJsonObject
            if (obj.has("id") && obj.get("id").asString.equals(id.trim(), ignoreCase = true)) {
                val current = if (obj.has("progression") && !obj.get("progression").isJsonNull) obj.get("progression").asBoolean else true
                val next = !current
                obj.addProperty("progression", next)
                val icon = if (obj.has("iconItemId") && !obj.get("iconItemId").isJsonNull) obj.get("iconItemId").asString else resolveIcon(id, null, next)
                obj.addProperty("badgeItemId", if (next) icon else "")
                obj.addProperty("badgeDefId", if (next) id else "")
                newState = next
                break
            }
        }
        if (newState != null) {
            writeConfigJson(json)
        }
        return newState
    }

    fun setMilestoneReward(id: String, dollars: Long, summary: String): Boolean {
        val json = readConfigJson()
        if (!json.has("milestones")) return false
        val array = json.getAsJsonArray("milestones")
        var updated = false
        for (elem in array) {
            val obj = elem.asJsonObject
            if (obj.has("id") && obj.get("id").asString.equals(id.trim(), ignoreCase = true)) {
                val safeDollars = dollars.coerceAtLeast(0L)
                obj.addProperty("rewardDollars", safeDollars)
                obj.addProperty(
                    "rewardSummary",
                    summary.trim().ifBlank {
                        if (safeDollars > 0L) "$safeDollars CobbleDollars" else "Recompensa por superación"
                    }
                )
                updated = true
                break
            }
        }
        if (updated) {
            writeConfigJson(json)
        }
        return updated
    }

    fun removeMilestone(id: String): Boolean {
        val json = readConfigJson()
        if (!json.has("milestones")) return false
        val array = json.getAsJsonArray("milestones")
        val newArray = JsonArray()
        var removed = false
        for (elem in array) {
            val obj = elem.asJsonObject
            if (obj.has("id") && obj.get("id").asString.equals(id.trim(), ignoreCase = true)) {
                removed = true
            } else {
                newArray.add(obj)
            }
        }
        if (removed) {
            json.add("milestones", newArray)
            recalculateJsonDates(json)
            writeConfigJson(json)
        }
        return removed
    }

    private fun recalculateJsonDates(json: JsonObject) {
        val baseDateStr = if (json.has("serverStartDate")) json.get("serverStartDate").asString else "2026-10-28"
        val baseDate = try { LocalDate.parse(baseDateStr) } catch (_: Exception) { todayCaracas() }
        val spanishMonths = arrayOf("Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic")

        if (json.has("milestones")) {
            val array = json.getAsJsonArray("milestones")
            val sorted = array.map { it.asJsonObject }
                .sortedBy { if (it.has("dayOffset")) it.get("dayOffset").asInt else 1 }
            val rebuilt = JsonArray()
            for (obj in sorted) {
                val dayOffset = (if (obj.has("dayOffset")) obj.get("dayOffset").asInt else 1).coerceAtLeast(1)
                val duration = (if (obj.has("durationDays")) obj.get("durationDays").asInt else 2).coerceAtLeast(1)

                val start = baseDate.plusDays((dayOffset - 1).toLong())
                val end = start.plusDays((duration - 1).toLong())
                obj.addProperty("startDate", start.toString())
                obj.addProperty("endDate", end.toString())

                val startDayStr = String.format("%02d %s", start.dayOfMonth, spanishMonths[start.monthValue - 1])
                val endDayStr = String.format("%02d %s", end.dayOfMonth, spanishMonths[end.monthValue - 1])

                val range = if (start == end) {
                    "Dia $dayOffset | $startDayStr"
                } else {
                    "Dia $dayOffset-${dayOffset + duration - 1} | $startDayStr - $endDayStr"
                }
                obj.addProperty("dateRange", range)
                rebuilt.add(obj)
            }
            json.add("milestones", rebuilt)
        }
    }

    fun iconDisplayName(iconItemId: String): String {
        return presetIcons.firstOrNull { it.second.equals(iconItemId, ignoreCase = true) }?.first ?: iconItemId
    }

    fun buildSingleHitoEmbed(entry: HitoEntry): MessageEmbed {
        val kind = if (entry.progression) "Progreso" else "Evento opcional"
        val rewardLine = buildString {
            if (entry.rewardDollars > 0L) append("${entry.rewardDollars}$")
            if (entry.rewardSummary.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(entry.rewardSummary)
            }
            if (isEmpty()) append("Sin recompensa")
        }
        return EmbedBuilder()
            .setTitle("Editar: ${entry.title}")
            .setColor(if (entry.progression) Color(255, 170, 0) else Color(91, 192, 222))
            .addField("Día", "Día ${entry.dayOffset} (${entry.durationDays}d)\n`${entry.dateRange.ifBlank { "Sin rango" }}`", true)
            .addField("Tipo", kind, true)
            .addField("Icono", iconDisplayName(entry.iconItemId), true)
            .addField("Líder / Detalle", entry.leaderOrBoss.ifBlank { "—" }, true)
            .addField("Recompensa", rewardLine, true)
            .setFooter("ID: ${entry.id} • Cambios sincronizados en vivo con Minecraft")
            .build()
    }

    fun buildEmbed(
        config: DiscordBridgeConfig? = null,
        showAdminInfo: Boolean = false,
        statusNotice: String? = null
    ): MessageEmbed {
        val json = readConfigJson()
        val dia1 = if (json.has("serverStartDate")) json.get("serverStartDate").asString else "2026-10-28"
        val header = if (json.has("headerTitle")) json.get("headerTitle").asString else "Cronograma de Hitos"
        val currentDay = getEffectiveCurrentDay(json)
        val manualDay = getManualCurrentDay(json)
        val dayMode = if (manualDay > 0) "Manual" else "Automático"

        val desc = buildString {
            if (!statusNotice.isNullOrBlank()) {
                appendLine(statusNotice)
                appendLine()
            }
            append("**Día 1:** `$dia1`  ·  **Día actual:** `Día $currentDay`")
            if (showAdminInfo) {
                val roleId = if (config != null) getHitosRoleId(config) else {
                    if (json.has("discordAdminRoleId") && !json.get("discordAdminRoleId").isJsonNull) json.get("discordAdminRoleId").asString else ""
                }
                val roleLine = if (roleId.isNotBlank()) "<@&$roleId>" else "Solo Administradores"
                append("\n**Modo:** `$dayMode`  ·  **Rol:** $roleLine")
            }
        }

        val eb = EmbedBuilder()
            .setTitle(header)
            .setColor(Color(255, 170, 0))
            .setDescription(desc)

        val entries = listMilestones()
        if (entries.isEmpty()) {
            eb.addField("Sin hitos", "Todavía no hay hitos configurados.", false)
        } else {
            for (entry in entries.take(24)) {
                val kind = if (entry.progression) "Progreso" else "Evento"
                val body = buildString {
                    if (entry.dateRange.isNotBlank()) append("`${entry.dateRange}`")
                    if (entry.leaderOrBoss.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(entry.leaderOrBoss)
                    }
                    if (entry.rewardSummary.isNotBlank()) {
                        append("\nRecompensa: ${entry.rewardSummary}")
                    }
                }
                eb.addField("Día ${entry.dayOffset} · ${entry.title} [$kind]", body.ifBlank { "—" }, false)
            }
        }

        eb.setFooter(
            if (showAdminInfo) "Panel de Hitos • Usa los botones o el menú inferior"
            else "Desgraciamon • Cronograma de Hitos"
        )
        return eb.build()
    }
}
