package com.desgraciamon.antidupe

import com.cobblemon.mod.common.Cobblemon
import com.desgraciamon.announcements.ContainerItemScanner
import com.desgraciamon.announcements.SophisticatedBackpacksBridge
import com.desgraciamon.badges.AdminAuth
import com.desgraciamon.economy.CobbleDollarsBridge
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors

object AntiDupeEngine {
    private val logger = LoggerFactory.getLogger("desgraciamon4-antidupe")
    private val logFile = File("config/desgraciamon4_antidupe/alerts.log")
    private val VET_ZONE = ZoneId.of("America/Caracas")
    private val TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val webhookExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "desgraciamon-antidupe-webhook").apply { isDaemon = true }
    }

    private const val STAMP_KEY = "desgraciamon_uid"

    data class AlertRecord(
        val timestampVet: String,
        val severity: String,
        val playerName: String,
        val summary: String,
        val details: String,
        val actionTaken: String
    )

    private data class BaselineSnapshot(
        val timestampMs: Long,
        val counts: Map<String, Int>,
        val balance: Long
    )

    private val baselines = ConcurrentHashMap<UUID, BaselineSnapshot>()
    private val alertCooldowns = ConcurrentHashMap<String, Long>()
    private val recentAlerts = ConcurrentLinkedDeque<AlertRecord>()

    fun isExempt(player: ServerPlayer): Boolean {
        if (!AntiDupeConfigStore.config.ignoreCreativeAndAdmins) return false
        return player.isCreative || AdminAuth.isAdmin(player) || player.hasPermissions(2)
    }

    fun getRecentAlerts(limit: Int = 15): List<AlertRecord> {
        return recentAlerts.take(limit.coerceIn(1, 100))
    }

    fun clearPlayerBaseline(uuid: UUID) {
        baselines.remove(uuid)
    }

    @Synchronized
    fun runGlobalScan(server: MinecraftServer): Int {
        val cfg = AntiDupeConfigStore.config
        if (!cfg.enabled) return 0

        var findings = 0
        findings += scanPokemonUuids(server)
        findings += scanBackpackUuids(server)
        if (cfg.stampUnstackableItems) {
            findings += scanAndStampItemUuids(server)
        }
        findings += scanHeuristicSpikes(server)
        return findings
    }

    fun scanSinglePlayer(player: ServerPlayer): Int {
        val server = player.server
        return runGlobalScan(server)
    }

    private fun scanPokemonUuids(server: MinecraftServer): Int {
        val seen = HashMap<UUID, String>()
        var findings = 0
        val removeDupes = AntiDupeConfigStore.config.removeConfirmedUuidDuplicates

        for (player in server.playerList.players) {
            val exempt = isExempt(player)
            runCatching {
                val party = Cobblemon.storage.getParty(player)
                val toRemove = ArrayList<com.cobblemon.mod.common.pokemon.Pokemon>()
                for (pk in party) {
                    val loc = "${player.scoreboardName} (Equipo)"
                    val prev = seen.putIfAbsent(pk.uuid, loc)
                    if (prev != null && !exempt) {
                        findings++
                        val action = if (removeDupes) {
                            toRemove.add(pk)
                            "Copia eliminada automáticamente"
                        } else {
                            "Solo alerta"
                        }
                        emitAlert(
                            server = server,
                            cooldownKey = "pk:${player.uuid}:${pk.uuid}",
                            severity = "DUPE CONFIRMADO",
                            player = player,
                            summary = "Pokémon duplicado por UUID: ${pk.species.name} (${pk.uuid})",
                            details = "Original: $prev | Copia en: $loc",
                            actionTaken = action
                        )
                    }
                }
                for (pk in toRemove) {
                    party.remove(pk)
                }

                val pc = Cobblemon.storage.getPC(player)
                val pcToRemove = ArrayList<com.cobblemon.mod.common.pokemon.Pokemon>()
                for (pk in pc) {
                    val loc = "${player.scoreboardName} (PC)"
                    val prev = seen.putIfAbsent(pk.uuid, loc)
                    if (prev != null && !exempt) {
                        findings++
                        val action = if (removeDupes) {
                            pcToRemove.add(pk)
                            "Copia eliminada automáticamente del PC"
                        } else {
                            "Solo alerta"
                        }
                        emitAlert(
                            server = server,
                            cooldownKey = "pk:${player.uuid}:${pk.uuid}",
                            severity = "DUPE CONFIRMADO",
                            player = player,
                            summary = "Pokémon duplicado en PC por UUID: ${pk.species.name} (${pk.uuid})",
                            details = "Original: $prev | Copia en: $loc",
                            actionTaken = action
                        )
                    }
                }
                for (pk in pcToRemove) {
                    pc.remove(pk)
                }
            }
        }
        return findings
    }

    private fun scanBackpackUuids(server: MinecraftServer): Int {
        val seen = HashMap<UUID, String>()
        var findings = 0
        val removeDupes = AntiDupeConfigStore.config.removeConfirmedUuidDuplicates

        for (player in server.playerList.players) {
            val exempt = isExempt(player)
            var inventoryChanged = false

            fun checkBackpackStack(stack: ItemStack, slotLabel: String) {
                if (stack.isEmpty || stack.count <= 0) return
                val uuidType = SophisticatedBackpacksBridge.resolveStorageUuidComponentType() ?: return
                val uuid = stack.get(uuidType) ?: return
                val holderDesc = "${player.scoreboardName} ($slotLabel)"
                val prev = seen.putIfAbsent(uuid, holderDesc)
                if (prev != null && !exempt) {
                    findings++
                    val action = if (removeDupes) {
                        stack.count = 0
                        inventoryChanged = true
                        "Mochila duplicada eliminada automáticamente"
                    } else {
                        "Solo alerta"
                    }
                    emitAlert(
                        server = server,
                        cooldownKey = "bp:${player.uuid}:$uuid",
                        severity = "DUPE CONFIRMADO",
                        player = player,
                        summary = "Mochila/Storage duplicada por storage_uuid ($uuid)",
                        details = "Original: $prev | Copia en: $holderDesc",
                        actionTaken = action
                    )
                }
            }

            SophisticatedBackpacksBridge.forEachEquippedBackpack(player) { stack ->
                checkBackpackStack(stack, "Mochila Equipada")
            }
            for (i in player.inventory.items.indices) {
                checkBackpackStack(player.inventory.items[i], "Inventario slot $i")
            }
            for (i in 0 until player.enderChestInventory.containerSize) {
                checkBackpackStack(player.enderChestInventory.getItem(i), "Ender Chest slot $i")
            }

            if (inventoryChanged) {
                player.inventory.setChanged()
                player.containerMenu.broadcastChanges()
            }
        }
        return findings
    }

    private fun scanAndStampItemUuids(server: MinecraftServer): Int {
        val seen = HashMap<String, String>()
        var findings = 0
        val removeDupes = AntiDupeConfigStore.config.removeConfirmedUuidDuplicates

        for (player in server.playerList.players) {
            val exempt = isExempt(player)
            var changed = false

            fun processStack(stack: ItemStack, slotLabel: String) {
                if (stack.isEmpty || stack.count != 1 || stack.maxStackSize != 1) return
                if (!shouldStampItem(stack)) return

                var uid = readStamp(stack)
                if (uid == null) {
                    if (!player.isCreative) {
                        uid = UUID.randomUUID().toString()
                        writeStamp(stack, uid)
                        changed = true
                    } else {
                        return
                    }
                }

                val holderDesc = "${player.scoreboardName} ($slotLabel)"
                val prev = seen.putIfAbsent(uid, holderDesc)
                if (prev != null && !exempt) {
                    findings++
                    val itemName = stack.hoverName.string
                    val action = if (removeDupes) {
                        stack.count = 0
                        changed = true
                        "Ítem duplicado eliminado automáticamente"
                    } else {
                        "Solo alerta"
                    }
                    emitAlert(
                        server = server,
                        cooldownKey = "item:${player.uuid}:$uid",
                        severity = "DUPE CONFIRMADO",
                        player = player,
                        summary = "Ítem único duplicado por UID: $itemName ($uid)",
                        details = "Original: $prev | Copia en: $holderDesc",
                        actionTaken = action
                    )
                }
            }

            for (i in player.inventory.items.indices) {
                processStack(player.inventory.items[i], "Inventario slot $i")
            }
            for (stack in player.inventory.offhand) {
                processStack(stack, "Mano secundaria")
            }
            for (i in 0 until player.enderChestInventory.containerSize) {
                processStack(player.enderChestInventory.getItem(i), "Ender Chest slot $i")
            }

            if (changed) {
                player.inventory.setChanged()
                player.containerMenu.broadcastChanges()
            }
        }
        return findings
    }

    private fun shouldStampItem(stack: ItemStack): Boolean {
        val id = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        if (id == "minecraft:elytra") return true
        if (ContainerInspector.isVanillaShulker(stack)) {
            val cont = stack.get(DataComponents.CONTAINER)
            return cont != null && cont.nonEmptyItems().iterator().hasNext()
        }
        if (id == "cobblemon-cards:card") {
            return runCatching {
                val compClass = Class.forName("com.howlite.cobblemoncards.component.ModDataComponents")
                val field = compClass.getField("CARD_DATA")
                @Suppress("UNCHECKED_CAST")
                val compType = field.get(null) as net.minecraft.core.component.DataComponentType<Any>
                val data = stack.get(compType) ?: return false
                val rarity = (data.javaClass.getMethod("rarity").invoke(data) as String).lowercase()
                val shiny = data.javaClass.getMethod("isShiny").invoke(data) as Boolean
                shiny || rarity == "legendary" || rarity == "mythic"
            }.getOrDefault(false)
        }
        return false
    }

    private fun readStamp(stack: ItemStack): String? {
        val custom = stack.get(DataComponents.CUSTOM_DATA) ?: return null
        val tag = custom.copyTag()
        return if (tag.contains(STAMP_KEY)) tag.getString(STAMP_KEY) else null
    }

    private fun writeStamp(stack: ItemStack, uid: String) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack) { tag ->
            tag.putString(STAMP_KEY, uid)
        }
    }

    private fun scanHeuristicSpikes(server: MinecraftServer): Int {
        val cfg = AntiDupeConfigStore.config
        val now = System.currentTimeMillis()
        val windowMs = cfg.spikeWindowSeconds * 1000L
        var findings = 0

        for (player in server.playerList.players) {
            if (isExempt(player)) {
                baselines.remove(player.uuid)
                continue
            }
            val currentCounts = ContainerItemScanner.countItems(player)
            val currentBalance = CobbleDollarsBridge.getBalance(player).toLong()

            val prev = baselines[player.uuid]
            if (prev == null || (now - prev.timestampMs) > windowMs) {
                baselines[player.uuid] = BaselineSnapshot(now, currentCounts, currentBalance)
                continue
            }

            val menuClass = player.containerMenu.javaClass.simpleName
            val coords = "${player.level().dimension().location()} @ ${player.blockX}, ${player.blockY}, ${player.blockZ}"

            for ((itemId, threshold) in cfg.watchedItemSpikeThresholds) {
                val before = prev.counts[itemId] ?: 0
                val after = currentCounts[itemId] ?: 0
                val delta = after - before
                if (delta >= threshold && threshold > 0) {
                    findings++
                    emitAlert(
                        server = server,
                        cooldownKey = "spike:${player.uuid}:$itemId",
                        severity = "SOSPECHOSO",
                        player = player,
                        summary = "Aumento brusco de $itemId (+$delta en ${(now - prev.timestampMs) / 1000}s)",
                        details = "Total: $before -> $after | Menú: $menuClass | Pos: $coords",
                        actionTaken = "Solo alerta (revisión manual recomendada)"
                    )
                }
            }

            val moneyDelta = currentBalance - prev.balance
            if (moneyDelta >= cfg.moneySpikeThreshold) {
                findings++
                emitAlert(
                    server = server,
                    cooldownKey = "money:${player.uuid}",
                    severity = "SOSPECHOSO",
                    player = player,
                    summary = "Pico brusco de CobbleDollars (+$moneyDelta$ en ${(now - prev.timestampMs) / 1000}s)",
                    details = "Saldo: ${prev.balance}$ -> $currentBalance$ | Menú: $menuClass | Pos: $coords",
                    actionTaken = "Solo alerta (revisión manual recomendada)"
                )
            }
        }
        return findings
    }

    private fun emitAlert(
        server: MinecraftServer,
        cooldownKey: String,
        severity: String,
        player: ServerPlayer,
        summary: String,
        details: String,
        actionTaken: String
    ) {
        val now = System.currentTimeMillis()
        val cooldownMs = AntiDupeConfigStore.config.alertCooldownSeconds * 1000L
        val lastSent = alertCooldowns[cooldownKey] ?: 0L
        if (now - lastSent < cooldownMs) return
        alertCooldowns[cooldownKey] = now

        val ts = TIME_FMT.format(Instant.ofEpochMilli(now).atZone(VET_ZONE)) + " VET"
        val record = AlertRecord(ts, severity, player.scoreboardName, summary, details, actionTaken)
        recentAlerts.addFirst(record)
        while (recentAlerts.size > 100) {
            recentAlerts.removeLast()
        }

        val logLine = "[$ts] [$severity] [${player.scoreboardName}] $summary | $details | Acción: $actionTaken"
        logger.warn(logLine)
        appendToLogFile(logLine)

        val color = if (severity == "DUPE CONFIRMADO") "§c§l" else "§6§l"
        val invBtn = Component.literal(" §b[Ver Inv]")
            .withStyle { style ->
                style.withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/invsee ${player.scoreboardName}"))
                    .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Abrir inventario de ${player.scoreboardName}")))
            }
        val tpBtn = Component.literal(" §e[TP]")
            .withStyle { style ->
                style.withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tp ${player.scoreboardName}"))
                    .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Teletransportarte a ${player.scoreboardName}")))
            }

        val msg = Component.literal("$color[$severity] §f${player.scoreboardName}: §e$summary\n§7$details §8| §f$actionTaken")
            .append(invBtn)
            .append(tpBtn)

        for (admin in server.playerList.players) {
            if (AdminAuth.isAdmin(admin) || admin.hasPermissions(2)) {
                admin.sendSystemMessage(msg)
            }
        }

        sendDiscordWebhook(record)
    }

    private fun appendToLogFile(line: String) {
        runCatching {
            logFile.parentFile?.mkdirs()
            logFile.appendText(line + System.lineSeparator(), Charsets.UTF_8)
        }
    }

    private fun sendDiscordWebhook(record: AlertRecord) {
        val url = AntiDupeConfigStore.config.discordWebhookUrl.trim()
        if (url.isBlank()) return

        webhookExecutor.submit {
            runCatching {
                val embed = JsonObject().apply {
                    addProperty("title", "[${record.severity}] ${record.playerName}")
                    addProperty("description", record.summary)
                    addProperty("color", if (record.severity == "DUPE CONFIRMADO") 16726832 else 16750080)
                    val fields = JsonArray()
                    fields.add(JsonObject().apply {
                        addProperty("name", "Detalles")
                        addProperty("value", record.details)
                        addProperty("inline", false)
                    })
                    fields.add(JsonObject().apply {
                        addProperty("name", "Acción")
                        addProperty("value", record.actionTaken)
                        addProperty("inline", true)
                    })
                    fields.add(JsonObject().apply {
                        addProperty("name", "Hora (VET)")
                        addProperty("value", record.timestampVet)
                        addProperty("inline", true)
                    })
                    add("fields", fields)
                }
                val embeds = JsonArray().apply { add(embed) }
                val payload = JsonObject().apply {
                    addProperty("username", "Desgraciamon Anti-Dupe")
                    add("embeds", embeds)
                }

                val req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "DiscordBot (https://desgraciamon.local, 1.0)")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), Charsets.UTF_8))
                    .build()
                val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
                if (res.statusCode() !in 200..299) {
                    logger.warn("Discord webhook AntiDupe devolvió HTTP ${res.statusCode()}: ${res.body().orEmpty().take(300)}")
                }
            }.onFailure {
                logger.warn("Error enviando alerta AntiDupe a Discord webhook", it)
            }
        }
    }
}
