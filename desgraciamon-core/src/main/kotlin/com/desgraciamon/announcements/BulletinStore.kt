package com.desgraciamon.announcements

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Pokemon
import com.desgraciamon.auctions.GuildBridge
import com.desgraciamon.badges.BadgeData
import com.desgraciamon.economy.CobbleDollarsBridge
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

fun interface PlayerItemScanner {
    fun countItems(player: ServerPlayer): Map<String, Int>
}

fun interface PlayerItemConsumer {
    fun consumeItem(player: ServerPlayer, itemId: String, amount: Int): Boolean
}

data class PokemonTargetSnapshot(
    val speciesId: String = "",
    val types: Set<String> = emptySet(),
    val level: Int = 1,
    val evolutionStage: Int = 1,
    val gender: String = ""
)

object BulletinStore {
    private val gson = BulletinJson.gson
    private val playerStates = ConcurrentHashMap<UUID, PlayerBulletinState>()
    private val sourcePoller = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Desgraciamon announcements source").apply { isDaemon = true }
    }
    private val pollInFlight = AtomicBoolean(false)
    @Volatile private var fileDigest = ""
    @Volatile private var sourceSettings = BulletinSourceSettings()
    @Volatile private var lastSourceError = ""
    private var remoteEtag: String? = null
    private var lastPollTick = 0L
    private var dailyEvalTick = 0L
    @Volatile var document = BulletinDocument()
        private set

    @Volatile private var activeServer: MinecraftServer? = null
    private lateinit var contentFile: Path
    private lateinit var progressFile: Path
    private lateinit var dailyStateFile: Path
    private lateinit var sourceSettingsFile: Path
    private lateinit var missionAnimationSettingsFile: Path

    @Volatile
    private var dailyScheduler: DailyScheduleManager = DailyScheduleManager(Clock.systemUTC())

    @Volatile
    private var itemScanner: PlayerItemScanner = PlayerItemScanner { player -> defaultCountItems(player) }

    @Volatile
    private var itemConsumer: PlayerItemConsumer = PlayerItemConsumer { player, itemId, amount ->
        defaultConsumeItem(player, itemId, amount)
    }

    fun registerItemScanner(scanner: PlayerItemScanner, consumer: PlayerItemConsumer? = null) {
        itemScanner = scanner
        if (consumer != null) {
            itemConsumer = consumer
        }
    }

    fun countPlayerItems(player: ServerPlayer): Map<String, Int> = itemScanner.countItems(player)

    fun start(server: MinecraftServer) {
        activeServer = server
        val configFolder = FabricLoader.getInstance().configDir.resolve("desgraciamon4-announcements")
        Files.createDirectories(configFolder)
        contentFile = configFolder.resolve("announcements.json")
        sourceSettingsFile = configFolder.resolve("source.properties")
        missionAnimationSettingsFile = configFolder.resolve("mission-animations.properties")
        MissionAnimationSettings.load(missionAnimationSettingsFile)
        sourceSettings = BulletinSourceSettings.load(sourceSettingsFile)
        val worldFolder = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
        progressFile = worldFolder.resolve("data/desgraciamon4-announcements-progress.json")
        dailyStateFile = worldFolder.resolve("data/desgraciamon4-announcements-daily.json")
        if (Files.notExists(contentFile)) {
            Files.writeString(contentFile, gson.toJson(BulletinDocument()))
        }

        val dailyCatalog = DailyMissionCatalog.load(configFolder, AnnouncementsMod.logger)
        dailyScheduler = DailyScheduleManager(Clock.systemUTC(), dailyCatalog.cycle)
        dailyScheduler.configure(
            dailyCatalog.cycle,
            dailyCatalog.itemsMissions,
            dailyCatalog.captureMissions,
            dailyCatalog.combatMissions
        )
        loadDailyState()
        saveDailyState()

        loadProgress()
        requestPoll(force = true)
    }

    fun stop() {
        activeServer = null
        saveProgress()
        saveDailyState()
    }

    fun tick() {
        if (++dailyEvalTick % 20L == 0L) {
            val eval = dailyScheduler.evaluateTick()
            if (eval.cycleReset || eval.newlyUnlockedSlots.isNotEmpty()) {
                saveDailyState()
                var progressChanged = false
                activeServer?.playerList?.players?.forEach { player ->
                    val state = stateFor(player.uuid)
                    if (resetDailyIfNeeded(state, eval.cycleId)) {
                        progressChanged = true
                    }
                    if (eval.newlyUnlockedSlots.isNotEmpty()) {
                        reconcilePlayerItems(player, itemScanner.countItems(player), syncAfter = false)
                        player.sendSystemMessage(buildDailyUnlockNotification())
                    }
                    sync(player, isJoinSync = false)
                }
                if (progressChanged) saveProgress()
            }
        }

        val sourcePollInterval = if (sourceSettings.mode == "github") 100L else 40L
        if (++lastPollTick % sourcePollInterval != 0L) return
        requestPoll(force = false)
        val cycleId = currentCycleId()
        var resetAnyPlayer = false
        activeServer?.playerList?.players?.forEach { player ->
            val state = stateFor(player.uuid)
            if (state.dailyStamp != cycleId) {
                resetDailyIfNeeded(state, cycleId)
                sync(player)
                resetAnyPlayer = true
            }
        }
        if (resetAnyPlayer) saveProgress()
    }

    private fun requestPoll(force: Boolean, onComplete: ((Boolean) -> Unit)? = null) {
        val targetServer = activeServer ?: run {
            onComplete?.invoke(false)
            return
        }
        if (!pollInFlight.compareAndSet(false, true)) {
            sourcePoller.execute {
                while (pollInFlight.get()) {
                    runCatching { Thread.sleep(50) }
                }
                targetServer.execute { onComplete?.invoke(true) }
            }
            return
        }
        sourcePoller.execute {
            var handedToServer = false
            try {
                val settings = BulletinSourceSettings.load(sourceSettingsFile)
                if (settings != sourceSettings) {
                    sourceSettings = settings
                    remoteEtag = null
                    fileDigest = ""
                }
                val fetched = fetchContent(settings)
                if (fetched == null || fetched.first == null) {
                    targetServer.execute { onComplete?.invoke(true) }
                    return@execute
                }
                val content = fetched.first!!
                val digest = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
                if (!force && digest == fileDigest) {
                    remoteEtag = fetched.second ?: remoteEtag
                    targetServer.execute { onComplete?.invoke(true) }
                    return@execute
                }
                val parsed = BulletinJson.decode(decodeContent(settings, content))
                require(parsed.schemaVersion == 1) { "Unsupported schemaVersion ${parsed.schemaVersion}" }
                parsed.announcements = parsed.announcements.filter(::validAnnouncement).toMutableList()
                parsed.missions = parsed.missions.filter(::validMission).toMutableList()
                remoteEtag = fetched.second ?: remoteEtag
                targetServer.execute {
                    try {
                        if (activeServer === targetServer) {
                            document = parsed
                            fileDigest = digest
                            lastSourceError = ""
                            targetServer.playerList.players.forEach { sync(it, isJoinSync = false) }
                            AnnouncementsMod.logger.info(
                                "Loaded ${parsed.announcements.size} announcements and ${parsed.missions.size} missions from ${settings.description(contentFile)}"
                            )
                        }
                        onComplete?.invoke(true)
                    } finally {
                        pollInFlight.set(false)
                    }
                }
                handedToServer = true
            } catch (exception: Exception) {
                val message = exception.message ?: exception.javaClass.simpleName
                if (message != lastSourceError) {
                    AnnouncementsMod.logger.error("Could not synchronize announcements source: $message")
                    lastSourceError = message
                }
                targetServer.execute { onComplete?.invoke(false) }
            } finally {
                if (!handedToServer) pollInFlight.set(false)
            }
        }
    }

    private fun fetchContent(settings: BulletinSourceSettings): Pair<ByteArray?, String?>? {
        if (settings.mode == "local") {
            if (Files.notExists(contentFile)) return null
            return Files.readAllBytes(contentFile) to null
        }
        require(settings.githubUrl.startsWith("https://")) { "Set a valid HTTPS githubUrl in $sourceSettingsFile" }
        val url = URI(settings.githubUrl).toURL()
        require(url.host.equals("api.github.com", true) || url.host.equals("raw.githubusercontent.com", true)) {
            "GitHub source must use api.github.com or raw.githubusercontent.com"
        }
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.useCaches = false
        connection.setRequestProperty("Cache-Control", "no-cache, no-store")
        connection.setRequestProperty("Pragma", "no-cache")
        connection.setRequestProperty("User-Agent", "Desgraciamon4-Announcements")
        if (settings.githubToken.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${settings.githubToken}")
        remoteEtag?.let { connection.setRequestProperty("If-None-Match", it) }
        return try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) return null to connection.getHeaderField("ETag")
            require(status == HttpURLConnection.HTTP_OK) { "GitHub returned HTTP $status for ${settings.githubUrl}" }
            val bytes = connection.inputStream.use { input ->
                val content = input.readNBytes(MAX_SOURCE_BYTES + 1)
                require(content.size <= MAX_SOURCE_BYTES) { "Announcements JSON exceeds $MAX_SOURCE_BYTES bytes" }
                content
            }
            bytes to connection.getHeaderField("ETag")
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeContent(settings: BulletinSourceSettings, bytes: ByteArray): String {
        if (!settings.githubUrl.startsWith("https://api.github.com/repos/")) return bytes.toString(Charsets.UTF_8)
        val response = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        require(response.get("encoding")?.asString == "base64") { "GitHub API response did not contain base64 file content" }
        val encoded = response.get("content").asString
        return Base64.getMimeDecoder().decode(encoded).toString(Charsets.UTF_8)
    }

    private fun BulletinSourceSettings.description(localFile: Path): String =
        if (mode == "github") githubUrl else localFile.toString()

    fun sync(player: ServerPlayer, isJoinSync: Boolean = false) {
        val state = stateFor(player.uuid)
        resetDailyIfNeeded(state)
        val guildInfo = GuildBridge.getPlayerGuild(player.uuid, player.server)
        state.playerGuildId = guildInfo?.id ?: ""
        state.guildMemberCount = if (guildInfo != null) {
            GuildBridge.getGuildMembers(guildInfo.id).size.coerceAtLeast(1)
        } else 1

        state.adminMode = com.desgraciamon.badges.AdminAuth.isAdminModeActive(player)

        if (isJoinSync) {
            reconcilePlayerItems(player, itemScanner.countItems(player), syncAfter = false)
        }

        state.knownAnnouncementIds.addAll(state.readIds)
        state.knownMissionIds.addAll(state.readMissionIds)

        val newAnnouncements = document.announcements.filter { it.id !in state.knownAnnouncementIds && it.id !in state.readIds }
        val newMissions = document.missions.filter { it.id !in state.knownMissionIds && it.id !in state.readMissionIds }
        val newAnnouncementCount = newAnnouncements.size
        val newMissionCount = newMissions.size
        val hudAnnouncementIds = if (isJoinSync) emptyList() else newAnnouncements.filter { it.showInHud }.map { it.id }

        if (newAnnouncementCount + newMissionCount > 0) {
            if (isJoinSync) {
                val keyComp = Component.keybind("key.desgraciamon4_announcements.open")
                    .withStyle(net.minecraft.ChatFormatting.YELLOW, net.minecraft.ChatFormatting.BOLD)
                val msg = when {
                    newAnnouncementCount > 0 && newMissionCount > 0 -> {
                        Component.literal("§6[Desgraciamon] §eHay §f$newAnnouncementCount §eanuncios y §f$newMissionCount §emisiones nuevas. ¡Revisa con la tecla ")
                            .append(keyComp)
                            .append(Component.literal("§e!"))
                    }
                    newAnnouncementCount > 0 -> {
                        Component.literal("§6[Desgraciamon] §eHay §f$newAnnouncementCount §eanuncios nuevos. ¡Revisa con la tecla ")
                            .append(keyComp)
                            .append(Component.literal("§e!"))
                    }
                    else -> {
                        Component.literal("§6[Desgraciamon] §eHay §f$newMissionCount §emisiones nuevas. ¡Revisa con la tecla ")
                            .append(keyComp)
                            .append(Component.literal("§e!"))
                    }
                }
                player.sendSystemMessage(msg)
            } else {
                for (mission in newMissions) {
                    sendMissionToChat(player, mission)
                }
                for (ann in newAnnouncements) {
                    if (ann.showInChat) {
                        sendAnnouncementToChat(player, ann)
                    }
                }
            }

            document.announcements.forEach { state.knownAnnouncementIds.add(it.id) }
            document.missions.forEach { state.knownMissionIds.add(it.id) }
            saveProgress()
            if (newMissionCount > 0 && !isJoinSync) playMissionAnimation(player, completed = false)
        }

        val dailySnapshot = dailyScheduler.snapshot(
            playerCompleted = state.completedMissions,
            playerClaimed = state.claimedMissions,
            bonusClaimedCycle = state.dailyBonusClaimedCycle,
            bonusConfig = DailyMissionCatalog.dailyBonus()
        )
        ServerBulletinNetworking.sendSnapshot(
            player,
            ClientBulletinSnapshot(
                document = document,
                state = state.copyDeep(),
                newAnnouncementCount = newAnnouncementCount,
                newMissionCount = newMissionCount,
                newAnnouncementIds = hudAnnouncementIds,
                dailyCycle = dailySnapshot,
                uiTheme = DailyMissionCatalog.uiTheme()
            )
        )
    }

    fun sendAnnouncementToChat(player: ServerPlayer, ann: Announcement) {
        val titleHex = ann.color.ifBlank { "#F5C842" }
        val bodyHex = ann.bodyColor.ifBlank { "#FFFFFF" }
        val titleComp = RichText.parse("<color=$titleHex><bold>${ann.title}</bold></color>", emptyMap())
        val header = TextCatalog.component("chat.announcement.header", "title" to titleComp)
        player.sendSystemMessage(header)
        if (ann.body.isNotBlank()) {
            player.sendSystemMessage(Component.empty())
            for (line in ann.body.lines()) {
                if (line.isBlank()) {
                    player.sendSystemMessage(Component.empty())
                } else {
                    val bodyComp = RichText.parse("<color=$bodyHex>$line</color>", emptyMap())
                    player.sendSystemMessage(bodyComp)
                }
            }
        }
    }

    fun sendMissionToChat(player: ServerPlayer, mission: Mission) {
        val bodyHex = mission.bodyColor.ifBlank { "#E5ECE8" }
        val prefix = if (mission.isGuildMission) "[Gremio]" else "[Nueva Misión]"
        player.sendSystemMessage(Component.literal("§a$prefix §f${mission.title}"))
        if (mission.body.isNotBlank()) {
            player.sendSystemMessage(Component.empty())
            for (line in mission.body.lines()) {
                if (line.isBlank()) {
                    player.sendSystemMessage(Component.empty())
                } else {
                    val bodyComp = RichText.parse("<color=$bodyHex>$line</color>", emptyMap())
                    player.sendSystemMessage(bodyComp)
                }
            }
        }
    }

    fun action(player: ServerPlayer, action: String, id: String) {
        val state = stateFor(player.uuid)
        when (action) {
            "read" -> if (document.announcements.any { it.id == id }) {
                state.readIds.add(id)
                state.knownAnnouncementIds.add(id)
            }
            "missionRead" -> if (document.missions.any { it.id == id } || dailyScheduler.isMissionUnlocked(id)) {
                state.readMissionIds.add(id)
                state.knownMissionIds.add(id)
            }
            "claim" -> claimMission(player, state, id)
            "claimDailyBonus" -> claimDailyBonus(player, state)
            "sync" -> Unit
            else -> return
        }
        saveProgress()
        sync(player, isJoinSync = false)
    }

    fun buildDailyUnlockNotification(
        colorHex: String = DailyMissionCatalog.uiTheme().notificationColorHex
    ): MutableComponent = TextCatalog.dailyUnlockComponent(colorHex)

    fun toggleDailyPause(actor: CommandSourceStack? = null): Boolean {
        val result = dailyScheduler.setPaused(!dailyScheduler.isPaused())
        saveDailyState()
        if (!result.paused) {
            var progressChanged = false
            activeServer?.playerList?.players?.forEach { player ->
                val state = stateFor(player.uuid)
                if (resetDailyIfNeeded(state, result.evaluation.cycleId)) {
                    progressChanged = true
                }
                reconcilePlayerItems(player, itemScanner.countItems(player), syncAfter = false)
                if (result.evaluation.newlyUnlockedSlots.isNotEmpty()) {
                    player.sendSystemMessage(buildDailyUnlockNotification())
                }
                sync(player, isJoinSync = false)
            }
            if (progressChanged) saveProgress()
        } else {
            activeServer?.playerList?.players?.forEach { sync(it, isJoinSync = false) }
        }
        return result.paused
    }

    fun rotateDailyMissions(slotSpec: String): String {
        val trimmed = slotSpec.trim().lowercase()
        if (trimmed == "recompensa" || trimmed == "bonus") {
            return resetDailyBonus()
        }
        val res = dailyScheduler.rotate(slotSpec)
        if (!res.ok) return res.errorMessage
        val resetBonus = trimmed == "todas" || trimmed == "all" || trimmed.isEmpty()
        if (res.previousMissionIds.isNotEmpty() || resetBonus) {
            playerStates.values.forEach { state ->
                res.previousMissionIds.forEach { oldId ->
                    state.missionProgress.remove(oldId)
                    state.objectiveProgress.remove(oldId)
                    state.completedMissions.remove(oldId)
                    state.claimedMissions.remove(oldId)
                    state.readMissionIds.remove(oldId)
                    state.knownMissionIds.remove(oldId)
                }
                if (resetBonus) {
                    state.dailyBonusClaimedCycle = ""
                }
            }
            saveProgress()
        }
        saveDailyState()
        activeServer?.let { server ->
            val playerList = runCatching { ArrayList(server.playerList.players) }.getOrDefault(emptyList())
            for (player in playerList) {
                runCatching {
                    if (res.unlockedRotatedSlots.isNotEmpty()) {
                        reconcilePlayerItems(player, itemScanner.countItems(player), syncAfter = false)
                        player.sendSystemMessage(buildDailyUnlockNotification())
                    }
                    sync(player, isJoinSync = false)
                }.onFailure { ex ->
                    AnnouncementsMod.logger.error("Error sincronizando jugador ${player.scoreboardName} tras rotacion diaria", ex)
                }
            }
        }
        val slotLabel = res.rotatedSlots.joinToString(", ")
        return if (resetBonus) {
            "Misiones diarias rotadas para ranura(s): $slotLabel y Recompensa del Dia reseteada."
        } else {
            "Misiones diarias rotadas para ranura(s): $slotLabel."
        }
    }

    fun resetDailyBonus(): String {
        playerStates.values.forEach { state ->
            state.dailyBonusClaimedCycle = ""
        }
        saveProgress()
        activeServer?.let { server ->
            val playerList = runCatching { ArrayList(server.playerList.players) }.getOrDefault(emptyList())
            for (player in playerList) {
                runCatching {
                    sync(player, isJoinSync = false)
                }.onFailure { ex ->
                    AnnouncementsMod.logger.error("Error sincronizando jugador ${player.scoreboardName} tras reseteo de recompensa diaria", ex)
                }
            }
        }
        return "Recompensa del dia reseteada para todos los jugadores."
    }

    fun forceUnlockDailySlot(slot: Int): Boolean {
        if (!dailyScheduler.forceOpen(slot)) return false
        saveDailyState()
        activeServer?.let { server ->
            val playerList = runCatching { ArrayList(server.playerList.players) }.getOrDefault(emptyList())
            for (player in playerList) {
                runCatching {
                    reconcilePlayerItems(player, itemScanner.countItems(player), syncAfter = false)
                    player.sendSystemMessage(buildDailyUnlockNotification())
                    sync(player, isJoinSync = false)
                }.onFailure { ex ->
                    AnnouncementsMod.logger.error("Error sincronizando jugador ${player.scoreboardName} tras abrir ranura diaria", ex)
                }
            }
        }
        return true
    }

    fun simulateDailyTime(mode: String): String {
        val res = dailyScheduler.simulateTime(mode)
        if (!res.ok) return res.message
        saveDailyState()
        val isReset = mode.trim().equals("reset", ignoreCase = true)
        var progressChanged = false
        if (isReset) {
            playerStates.values.forEach { state ->
                if (state.dailyBonusClaimedCycle.isNotEmpty()) {
                    state.dailyBonusClaimedCycle = ""
                    progressChanged = true
                }
            }
        }
        activeServer?.playerList?.players?.forEach { player ->
            val state = stateFor(player.uuid)
            if (resetDailyIfNeeded(state, res.evaluation.cycleId)) {
                progressChanged = true
            }
            if (!dailyScheduler.isPaused()) {
                reconcilePlayerItems(player, itemScanner.countItems(player), syncAfter = false)
                if (res.evaluation.newlyUnlockedSlots.isNotEmpty()) {
                    player.sendSystemMessage(buildDailyUnlockNotification())
                }
            }
            sync(player, isJoinSync = false)
        }
        if (progressChanged) saveProgress()
        return res.message
    }

    fun isMissionActiveForPlayer(mission: Mission, state: PlayerBulletinState): Boolean {
        if (mission.id in state.completedMissions) return false
        if (mission.isExpired()) return false
        if (mission.isGuildMission && state.playerGuildId.isBlank()) return false
        if (mission.dailySlot in 1..3) {
            if (dailyScheduler.isPaused()) return false
            if (!dailyScheduler.isMissionUnlocked(mission.id)) return false
        }
        return true
    }

    fun reconcilePlayerItems(player: ServerPlayer, itemTotals: Map<String, Int>) {
        reconcilePlayerItems(player, itemTotals, syncAfter = true)
    }

    private fun reconcilePlayerItems(
        player: ServerPlayer,
        itemTotals: Map<String, Int>,
        syncAfter: Boolean
    ): Boolean {
        if (itemTotals.isEmpty()) return false
        val normalizedTotals = normalizeItemTotals(itemTotals)
        val server = player.server
        val state = stateFor(player.uuid)
        resetDailyIfNeeded(state)
        val playerGuild = GuildBridge.getPlayerGuild(player.uuid, server)
        state.playerGuildId = playerGuild?.id ?: ""

        var changed = false
        activeMissionsFor().forEach { mission ->
            if (!isMissionActiveForPlayer(mission, state)) return@forEach
            val objectives = objectivesFor(mission)
            val isGuild = mission.isGuildMission
            val guildId = playerGuild?.id ?: ""
            val guildMembers = if (isGuild) GuildBridge.getGuildMembers(guildId) else setOf(player.uuid)
            val memberCount = guildMembers.size.coerceAtLeast(1)

            val progress = state.objectiveProgress.getOrPut(mission.id) { mutableMapOf() }
            var missionUpdated = false

            objectives.filter { it.type == ObjectiveType.GET_ITEM && it.itemId.isNotBlank() }.forEach { objective ->
                val targetAmount = if (objective.targetUsesMemberCount && isGuild) memberCount else objective.amount
                val ownedCount = normalizedTotals[normalizeItemId(objective.itemId)] ?: 0
                val old = progress[objective.id] ?: 0
                val next = ownedCount.coerceAtLeast(old).coerceAtMost(targetAmount)
                if (next != old) {
                    progress[objective.id] = next
                    changed = true
                    missionUpdated = true
                    if (isGuild) {
                        for (memberUuid in guildMembers) {
                            if (memberUuid != player.uuid) {
                                stateFor(memberUuid).objectiveProgress.getOrPut(mission.id) { mutableMapOf() }[objective.id] = next
                            }
                        }
                    }
                }
            }

            if (missionUpdated) {
                finalizeMissionUpdate(player, server, state, mission, objectives, isGuild, guildMembers, memberCount)
                changed = true
            }
        }

        if (changed) {
            saveProgress()
            if (syncAfter) {
                sync(player, isJoinSync = false)
            }
        }
        return changed
    }

    fun progressItem(player: ServerPlayer, itemId: String, count: Int) {
        if (count <= 0) return
        val normalizedTarget = normalizeItemId(itemId)
        updateProgress(player, ObjectiveType.GET_ITEM, count) { target ->
            normalizeItemId(target.itemId) == normalizedTarget
        }
    }

    fun progressCatch(player: ServerPlayer, pokemon: Pokemon) {
        updatePokemonObjectives(player, ObjectiveType.CATCH_SPECIES, pokemon)
        updatePokemonObjectives(player, ObjectiveType.CATCH_TYPE, pokemon)
    }

    fun progressDefeat(player: ServerPlayer, pokemon: Pokemon) {
        updatePokemonObjectives(player, ObjectiveType.DEFEAT_POKEMON, pokemon)
    }

    fun progressBadge(player: ServerPlayer, badgeId: String) {
        updateProgress(player, ObjectiveType.OBTAIN_BADGE, 1) { target ->
            target.badgeId.equals(badgeId, ignoreCase = true)
        }
    }

    private fun updatePokemonObjectives(player: ServerPlayer, type: ObjectiveType, pokemon: Pokemon) {
        updateProgress(player, type, 1) { target -> matchesPokemonTarget(target, pokemon) }
    }

    fun evolutionStageOf(form: FormData): Int {
        var stage = 1
        var current: FormData? = form
        val visited = mutableSetOf<String>()
        while (current != null) {
            val pre = current.preEvolution ?: break
            val key = "${pre.species.resourceIdentifier}|${pre.form.name}"
            if (!visited.add(key)) break
            stage += 1
            current = pre.form
        }
        return stage
    }

    private fun matchesPokemonTarget(target: MissionObjective, pokemon: Pokemon): Boolean {
        val types = pokemon.types.map { it.name.lowercase() }.toSet()
        val speciesId = pokemon.species.resourceIdentifier.toString().lowercase()
        val evoStage = evolutionStageOf(pokemon.form)
        val genderStr = pokemon.gender.name.lowercase()
        val snapshot = PokemonTargetSnapshot(
            speciesId = speciesId,
            types = types,
            level = pokemon.level,
            evolutionStage = evoStage,
            gender = genderStr
        )
        val propsMatch = if (target.pokemonProperties.isBlank()) {
            true
        } else {
            runCatching {
                PokemonProperties.parse(target.pokemonProperties).matches(pokemon)
            }.getOrDefault(false)
        }
        return matchesPokemonTargetPure(target, snapshot, propsMatch)
    }

    fun matchesPokemonTargetPure(
        target: MissionObjective,
        pokemon: PokemonTargetSnapshot,
        propertiesMatch: Boolean? = null
    ): Boolean {
        val normalizedTypes = pokemon.types.map { normalizePokemonType(it) }.toSet()
        if (target.type == ObjectiveType.CATCH_TYPE || target.type == ObjectiveType.DEFEAT_POKEMON) {
            val primary = normalizePokemonType(target.pokemonType)
            if (primary.isNotBlank() && primary !in normalizedTypes) {
                return false
            }
            val secondary = normalizePokemonType(target.secondaryPokemonType)
            if (secondary.isNotBlank() && secondary !in normalizedTypes) {
                return false
            }
        }

        if (target.minLevel > 0 && pokemon.level < target.minLevel) {
            return false
        }

        if (target.minEvolutionStage > 0 && pokemon.evolutionStage < target.minEvolutionStage) {
            return false
        }

        if (target.pokemonId.isNotBlank() && target.type != ObjectiveType.CATCH_TYPE) {
            val expectedSpecies = normalizeSpeciesId(target.pokemonId)
            val actualSpecies = normalizeSpeciesId(pokemon.speciesId)
            if (expectedSpecies != actualSpecies) return false
        }

        if (target.pokemonProperties.isBlank()) return true
        if (propertiesMatch != null) return propertiesMatch
        return matchPropertiesPure(target.pokemonProperties, pokemon)
    }

    private fun matchPropertiesPure(rawProps: String, pokemon: PokemonTargetSnapshot): Boolean {
        val tokens = rawProps.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        for (token in tokens) {
            val lower = token.lowercase()
            when {
                lower.startsWith("gender=") -> {
                    val expected = lower.substringAfter("gender=")
                    if (!pokemon.gender.equals(expected, ignoreCase = true)) return false
                }
                lower.startsWith("level=") -> {
                    val lvl = lower.substringAfter("level=").toIntOrNull() ?: return false
                    if (pokemon.level != lvl) return false
                }
                lower == "male" -> if (!pokemon.gender.equals("male", ignoreCase = true)) return false
                lower == "female" -> if (!pokemon.gender.equals("female", ignoreCase = true)) return false
            }
        }
        return true
    }

    internal fun objectivesFor(mission: Mission): List<MissionObjective> = mission.objectives
        .filter { it.amount > 0 }
        .ifEmpty {
            listOf(
                MissionObjective(
                    id = "legacy",
                    type = mission.objective,
                    amount = mission.amount,
                    itemId = mission.itemId,
                    pokemonId = mission.pokemonId,
                    pokemonType = mission.pokemonType,
                    badgeId = mission.badgeId,
                    targetUsesMemberCount = mission.targetUsesMemberCount
                )
            )
        }

    private fun activeMissionsFor(): List<Mission> {
        val daily = dailyScheduler.activeUnlockedMissions()
        val dailyIds = daily.mapTo(mutableSetOf()) { it.id }
        return daily + document.missions.filter { it.id !in dailyIds }
    }

    private fun updateProgress(
        player: ServerPlayer,
        type: ObjectiveType,
        amount: Int,
        matches: (MissionObjective) -> Boolean
    ) {
        val server = player.server
        val state = stateFor(player.uuid)
        resetDailyIfNeeded(state)
        var changed = false

        val playerGuild = GuildBridge.getPlayerGuild(player.uuid, server)
        state.playerGuildId = playerGuild?.id ?: ""

        activeMissionsFor().forEach { mission ->
            if (!isMissionActiveForPlayer(mission, state)) return@forEach
            val objectives = objectivesFor(mission)
            val isGuild = mission.isGuildMission

            val guildId = playerGuild?.id ?: ""
            val guildMembers = if (isGuild) GuildBridge.getGuildMembers(guildId) else setOf(player.uuid)
            val memberCount = guildMembers.size.coerceAtLeast(1)

            val progress = state.objectiveProgress.getOrPut(mission.id) { mutableMapOf() }
            var anyObjectiveUpdatedInThisMission = false
            var justCompletedAnObjective = false
            var updatedObjectiveLabel = ""
            var updatedObjectiveCur = 0
            var updatedObjectiveMax = 0

            objectives.filter { it.type == type && matches(it) }.forEach { objective ->
                val targetAmount = if (objective.targetUsesMemberCount && isGuild) memberCount else objective.amount

                val old = progress[objective.id] ?: 0
                val next = if (objective.type == ObjectiveType.OBTAIN_BADGE && isGuild) {
                    guildMembers.count { mUuid ->
                        BadgeData.player(mUuid).unlocked.contains(objective.badgeId)
                    }.coerceAtMost(targetAmount)
                } else if (objective.type == ObjectiveType.OBTAIN_BADGE) {
                    1.coerceAtMost(targetAmount)
                } else {
                    (old + amount).coerceAtMost(targetAmount)
                }

                if (next != old) {
                    progress[objective.id] = next
                    changed = true
                    anyObjectiveUpdatedInThisMission = true
                    updatedObjectiveLabel = CleanMissionDisplay.describeObjective(objective, isGuild, memberCount)
                    updatedObjectiveCur = next
                    updatedObjectiveMax = targetAmount
                    if (next >= targetAmount && old < targetAmount) {
                        justCompletedAnObjective = true
                    }

                    if (isGuild) {
                        for (memberUuid in guildMembers) {
                            if (memberUuid != player.uuid) {
                                val mState = stateFor(memberUuid)
                                mState.objectiveProgress.getOrPut(mission.id) { mutableMapOf() }[objective.id] = next
                            }
                        }
                    }
                }
            }

            val completedBefore = mission.id in state.completedMissions
            val (complete, completedObjectivesCount) = finalizeMissionUpdate(
                player, server, state, mission, objectives, isGuild, guildMembers, memberCount
            )
            if (!completedBefore && complete) {
                changed = true
            } else if (anyObjectiveUpdatedInThisMission && !complete) {
                val tag = if (isGuild) "[Gremio]" else "[Misión]"
                player.sendSystemMessage(Component.literal("§a$tag §f${mission.title} §7- §e$updatedObjectiveLabel: §b$updatedObjectiveCur/$updatedObjectiveMax"))
                if (justCompletedAnObjective && objectives.size > 1) {
                    player.sendSystemMessage(Component.literal("§a$tag §f${mission.title} §7- §a¡Objetivo completado! ($completedObjectivesCount/${objectives.size} objetivos)"))
                }
            }
        }
        if (changed) {
            saveProgress()
            sync(player, isJoinSync = false)
        }
    }

    private fun finalizeMissionUpdate(
        player: ServerPlayer,
        server: MinecraftServer,
        state: PlayerBulletinState,
        mission: Mission,
        objectives: List<MissionObjective>,
        isGuild: Boolean,
        guildMembers: Set<UUID>,
        memberCount: Int
    ): Pair<Boolean, Int> {
        val progress = state.objectiveProgress.getOrPut(mission.id) { mutableMapOf() }
        state.missionProgress[mission.id] = objectives.sumOf {
            val req = if (it.targetUsesMemberCount && isGuild) memberCount else it.amount
            (progress[it.id] ?: 0).coerceAtMost(req)
        }
        val completedObjectivesCount = objectives.count {
            val req = if (it.targetUsesMemberCount && isGuild) memberCount else it.amount
            (progress[it.id] ?: 0) >= req
        }
        val complete = completedObjectivesCount == objectives.size

        if (isGuild) {
            for (memberUuid in guildMembers) {
                val mState = stateFor(memberUuid)
                mState.missionProgress[mission.id] = state.missionProgress[mission.id] ?: 0
                if (complete && mState.completedMissions.add(mission.id)) {
                    server.playerList.getPlayer(memberUuid)?.let { onlineMember ->
                        val mTitleComp = RichText.parse("<color=${mission.color.ifBlank { "#68D7A2" }}><bold>${mission.title}</bold></color>", emptyMap())
                        onlineMember.displayClientMessage(TextCatalog.component("mission.completed", "title" to mTitleComp), false)
                        playMissionAnimation(onlineMember, completed = true)
                        sync(onlineMember, isJoinSync = false)
                    }
                } else if (memberUuid != player.uuid) {
                    server.playerList.getPlayer(memberUuid)?.let { sync(it, isJoinSync = false) }
                }
            }
        }

        val missionTitleComp = RichText.parse("<color=${mission.color.ifBlank { "#68D7A2" }}><bold>${mission.title}</bold></color>", emptyMap())
        if (complete && state.completedMissions.add(mission.id)) {
            player.displayClientMessage(TextCatalog.component("mission.completed", "title" to missionTitleComp), false)
            playMissionAnimation(player, completed = true)
        }
        return complete to completedObjectivesCount
    }

    private fun validAnnouncement(value: Announcement): Boolean {
        if (value.id.isBlank() || value.title.isBlank()) return false
        if (value.color.isBlank()) value.color = "#F5C842"
        if (value.bodyColor.isBlank()) value.bodyColor = "#FFFFFF"
        value.hudDurationSeconds = if (value.hudDurationSeconds <= 0) 15 else value.hudDurationSeconds.coerceIn(3, 300)
        return true
    }

    private fun validMission(value: Mission): Boolean {
        if (value.id.isBlank() || value.title.isBlank()) return false
        if (value.color.isBlank()) value.color = "#68D7A2"
        if (value.bodyColor.isBlank()) value.bodyColor = "#E5ECE8"
        return value.amount in 1..100000 && value.moneyReward in 0L..MAX_MONEY_REWARD &&
            value.objectives.size <= MAX_MISSION_OBJECTIVES && value.objectives.all { it.id.isNotBlank() && it.amount in 1..100000 }
    }

    fun forceReload() {
        fileDigest = ""
        remoteEtag = null
        if (::contentFile.isInitialized) {
            val catalog = DailyMissionCatalog.load(contentFile.parent, AnnouncementsMod.logger)
            dailyScheduler.configure(
                catalog.cycle,
                catalog.itemsMissions,
                catalog.captureMissions,
                catalog.combatMissions
            )
            saveDailyState()
        }
        requestPoll(force = true)
    }

    fun saveAdminAnnouncement(ann: Announcement) {
        if (ann.id.isBlank()) ann.id = "ann_" + UUID.randomUUID().toString().take(8)
        if (ann.title.isBlank()) ann.title = "Nuevo Anuncio"
        if (ann.color.isBlank()) ann.color = "#F5C842"
        if (ann.bodyColor.isBlank()) ann.bodyColor = "#FFFFFF"
        val list = document.announcements
        val existing = list.indexOfFirst { it.id == ann.id }
        if (existing >= 0) {
            list[existing] = ann
        } else {
            list.add(0, ann)
        }
        persistAdminChanges()
    }

    fun deleteAdminAnnouncement(id: String) {
        document.announcements.removeIf { it.id == id }
        persistAdminChanges()
    }

    fun saveAdminMission(mission: Mission) {
        if (mission.id.isBlank()) mission.id = "mis_" + UUID.randomUUID().toString().take(8)
        if (mission.title.isBlank()) mission.title = "Nueva Misión"
        if (mission.color.isBlank()) mission.color = "#68D7A2"
        if (mission.bodyColor.isBlank()) mission.bodyColor = "#E5ECE8"
        val list = document.missions
        val existing = list.indexOfFirst { it.id == mission.id }
        if (existing >= 0) {
            list[existing] = mission
        } else {
            list.add(0, mission)
        }
        persistAdminChanges()
    }

    fun deleteAdminMission(id: String) {
        document.missions.removeIf { it.id == id }
        persistAdminChanges()
    }

    private fun persistAdminChanges() {
        runCatching {
            if (!::contentFile.isInitialized) return
            Files.createDirectories(contentFile.parent)
            val json = gson.toJson(document)
            Files.writeString(contentFile, json)
            fileDigest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            activeServer?.playerList?.players?.forEach { sync(it, isJoinSync = false) }
            val settings = BulletinSourceSettings.load(sourceSettingsFile)
            if (settings.mode == "github" && settings.githubToken.isNotBlank()) {
                publishToGitHub(null)
            }
        }.onFailure { AnnouncementsMod.logger.error("Could not persist admin announcements changes", it) }
    }

    fun syncFromGitHub(feedbackPlayer: ServerPlayer? = null, onComplete: ((Boolean) -> Unit)? = null) {
        feedbackPlayer?.sendSystemMessage(Component.literal("§a[GitHub] Sincronizando anuncios y misiones con el repositorio remoto..."))
        requestPoll(force = true) { success ->
            if (success) {
                feedbackPlayer?.sendSystemMessage(Component.literal("§a[GitHub] ¡Sincronización completada exitosamente!"))
            } else {
                feedbackPlayer?.sendSystemMessage(Component.literal("§e[GitHub] Advertencia: No se pudo conectar a GitHub o se usó la copia local."))
            }
            onComplete?.invoke(success)
        }
    }

    fun publishToGitHub(feedbackPlayer: ServerPlayer? = null) {
        val targetServer = activeServer ?: return
        sourcePoller.execute {
            try {
                val settings = BulletinSourceSettings.load(sourceSettingsFile)
                if (settings.mode != "github" || settings.githubUrl.isBlank()) {
                    feedbackPlayer?.sendSystemMessage(Component.literal("§c[Anuncios] source.properties no está configurado en modo github."))
                    return@execute
                }
                if (settings.githubToken.isBlank()) {
                    feedbackPlayer?.sendSystemMessage(Component.literal("§c[Anuncios] No se encontró githubToken en source.properties."))
                    return@execute
                }

                val targetUrl = settings.githubUrl
                val uri = URI(targetUrl)
                val query = uri.query.orEmpty()
                val branch = if (query.contains("ref=")) {
                    query.substringAfter("ref=").substringBefore("&")
                } else "main"

                val getConn = URI(targetUrl).toURL().openConnection() as HttpURLConnection
                getConn.requestMethod = "GET"
                getConn.setRequestProperty("Authorization", "Bearer ${settings.githubToken}")
                getConn.setRequestProperty("Accept", "application/vnd.github+json")
                getConn.setRequestProperty("User-Agent", "Desgraciamon4-Announcements")
                getConn.connectTimeout = 8000
                getConn.readTimeout = 8000

                var currentSha: String? = null
                val getCode = getConn.responseCode
                if (getCode == HttpURLConnection.HTTP_OK) {
                    val respJson = JsonParser.parseString(getConn.inputStream.bufferedReader().readText()).asJsonObject
                    currentSha = respJson.get("sha")?.asString
                }
                getConn.disconnect()

                val contentJson = gson.toJson(document)
                val base64Content = Base64.getEncoder().encodeToString(contentJson.toByteArray(Charsets.UTF_8))

                val putUrl = if (targetUrl.contains("?")) targetUrl.substringBefore("?") else targetUrl
                val putConn = URI(putUrl).toURL().openConnection() as HttpURLConnection
                putConn.requestMethod = "PUT"
                putConn.doOutput = true
                putConn.setRequestProperty("Authorization", "Bearer ${settings.githubToken}")
                putConn.setRequestProperty("Content-Type", "application/json")
                putConn.setRequestProperty("Accept", "application/vnd.github+json")
                putConn.setRequestProperty("User-Agent", "Desgraciamon4-Announcements")
                putConn.connectTimeout = 12000
                putConn.readTimeout = 12000

                val requestObj = com.google.gson.JsonObject().apply {
                    addProperty("message", "Actualización de anuncios y misiones desde el juego")
                    addProperty("content", base64Content)
                    addProperty("branch", branch)
                    if (currentSha != null) {
                        addProperty("sha", currentSha)
                    }
                }

                putConn.outputStream.use { os ->
                    os.write(requestObj.toString().toByteArray(Charsets.UTF_8))
                }

                val putCode = putConn.responseCode
                val isSuccess = putCode in 200..204
                val respText = if (isSuccess) putConn.inputStream.bufferedReader().readText() else putConn.errorStream?.bufferedReader()?.readText().orEmpty()
                putConn.disconnect()

                targetServer.execute {
                    if (isSuccess) {
                        fileDigest = MessageDigest.getInstance("SHA-256").digest(contentJson.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                        if (::contentFile.isInitialized) {
                            Files.writeString(contentFile, contentJson)
                        }
                        targetServer.playerList.players.forEach { sync(it, isJoinSync = false) }
                        feedbackPlayer?.sendSystemMessage(Component.literal("§a[Anuncios] ¡Publicado y sincronizado con GitHub exitosamente!"))
                        AnnouncementsMod.logger.info("Successfully pushed announcements to GitHub ($putCode)")
                    } else {
                        feedbackPlayer?.sendSystemMessage(Component.literal("§c[Anuncios] Error al publicar en GitHub (HTTP $putCode): $respText"))
                        AnnouncementsMod.logger.error("Failed to push announcements to GitHub: HTTP $putCode $respText")
                    }
                }
            } catch (e: Exception) {
                val err = e.message ?: e.javaClass.simpleName
                targetServer.execute {
                    feedbackPlayer?.sendSystemMessage(Component.literal("§c[Anuncios] Excepción al sincronizar con GitHub: $err"))
                }
                AnnouncementsMod.logger.error("Exception pushing announcements to GitHub", e)
            }
        }
    }

    fun debugPreviewAnnouncement(player: ServerPlayer, announcementId: String? = null): Boolean {
        val ann = if (announcementId.isNullOrBlank()) {
            document.announcements.firstOrNull()
        } else {
            document.announcements.firstOrNull { it.id.equals(announcementId, ignoreCase = true) || it.title.equals(announcementId, ignoreCase = true) }
        } ?: Announcement(
            id = "debug-preview",
            title = "<prism=#FF6B6B,#FFD93D>Cambio de Dificultad:</prism>",
            body = "Reviil está experimentando con los Zombies.\nAhora todos pueden escalar, no se queman con el sol y spawnean con una Espada de Hierro con Filo V.",
            color = "#FF6B4A",
            bodyColor = "#F5D76E",
            showInChat = true,
            showInHud = true,
            hudDurationSeconds = 15
        )
        if (ann.showInChat) {
            sendAnnouncementToChat(player, ann)
        }
        val state = stateFor(player.uuid)
        val previewDoc = BulletinDocument(
            schemaVersion = document.schemaVersion,
            announcements = (listOf(ann) + document.announcements.filter { it.id != ann.id }).toMutableList(),
            missions = document.missions
        )
        val dailySnapshot = dailyScheduler.snapshot(
            playerCompleted = state.completedMissions,
            playerClaimed = state.claimedMissions,
            bonusClaimedCycle = state.dailyBonusClaimedCycle,
            bonusConfig = DailyMissionCatalog.dailyBonus()
        )
        ServerBulletinNetworking.sendSnapshot(
            player,
            ClientBulletinSnapshot(
                document = previewDoc,
                state = state.copyDeep(),
                newAnnouncementCount = 1,
                newMissionCount = 0,
                newAnnouncementIds = listOf(ann.id),
                dailyCycle = dailySnapshot,
                uiTheme = DailyMissionCatalog.uiTheme()
            )
        )
        return true
    }

    private fun grantItemRewards(player: ServerPlayer, rewards: List<ItemMissionReward>) {
        rewards.forEach { reward ->
            val identifier = runCatching { ResourceLocation.parse(reward.itemId) }.getOrNull() ?: return@forEach
            val item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null) ?: return@forEach
            var remaining = reward.count.coerceIn(1, 640)
            while (remaining > 0) {
                val stack = ItemStack(item, remaining.coerceAtMost(item.defaultMaxStackSize))
                remaining -= stack.count
                if (!player.inventory.add(stack)) player.drop(stack, false)
            }
        }
    }

    private fun grantRewards(player: ServerPlayer, mission: Mission) {
        grantItemRewards(player, mission.itemRewards)
        (mission.pokemonRewards.ifEmpty { listOfNotNull(mission.pokemonReward) }).forEach { reward ->
            if (reward.speciesId.isBlank()) return@forEach
            val propertiesText = buildString {
                if (reward.properties.isNotBlank()) append(' ').append(reward.properties.trim())
                append(" species=").append(reward.speciesId)
            }
            runCatching {
                val pokemon = PokemonProperties.parse(propertiesText).create(player)
                if (!Cobblemon.storage.getParty(player).add(pokemon)) {
                    val addedToPC = Cobblemon.storage.getPC(player).add(pokemon)
                    if (addedToPC) {
                        player.sendSystemMessage(TextCatalog.component("reward.party_full"))
                    } else {
                        player.sendSystemMessage(Component.literal("§c[Desgraciamon] Tu equipo y tu PC están llenos. El Pokémon ha aparecido a tu lado para que no se pierda."))
                        pokemon.sendOut(player.serverLevel(), player.position(), null)
                    }
                }
            }.onFailure { exception ->
                AnnouncementsMod.logger.error("Could not give Pokémon reward '${reward.speciesId}' for mission '${mission.id}'", exception)
                player.sendSystemMessage(TextCatalog.component("reward.failed"))
            }
        }
    }

    private fun claimMission(player: ServerPlayer, state: PlayerBulletinState, missionId: String) {
        val mission = activeMissionsFor().firstOrNull { it.id == missionId } ?: return
        if (mission.dailySlot in 1..3 && dailyScheduler.isPaused()) return
        if (missionId !in state.completedMissions || missionId in state.claimedMissions) return
        if (mission.moneyReward > 0 && !CobbleDollarsBridge.isAvailable()) {
            player.sendSystemMessage(TextCatalog.component("reward.money_mod_missing"))
            return
        }

        val objectives = objectivesFor(mission)
        val consumeObjectives = objectives.filter {
            it.type == ObjectiveType.GET_ITEM && it.consumeOnClaim && it.itemId.isNotBlank()
        }
        if (consumeObjectives.isNotEmpty()) {
            val counts = normalizeItemTotals(itemScanner.countItems(player))
            val isGuild = mission.isGuildMission
            val memberCount = state.guildMemberCount.coerceAtLeast(1)
            val totalRequiredByItem = aggregateRequiredConsumedItems(consumeObjectives, isGuild, memberCount)
            val shortfallItem = findMissingConsumedItem(totalRequiredByItem, counts)
            if (shortfallItem != null) {
                val progress = state.objectiveProgress.getOrPut(mission.id) { mutableMapOf() }
                var remaining = counts[shortfallItem] ?: 0
                for (obj in consumeObjectives) {
                    if (normalizeItemId(obj.itemId) == shortfallItem) {
                        val objReq = if (obj.targetUsesMemberCount && isGuild) memberCount else obj.amount
                        val allocated = remaining.coerceIn(0, objReq)
                        progress[obj.id] = allocated
                        remaining -= allocated
                    }
                }
                state.completedMissions.remove(mission.id)
                state.missionProgress[mission.id] = objectives.sumOf {
                    val req = if (it.targetUsesMemberCount && isGuild) memberCount else it.amount
                    (progress[it.id] ?: 0).coerceAtMost(req)
                }
                saveProgress()
                player.sendSystemMessage(Component.literal("§c[Desgraciamon] Te faltan objetos requeridos para entregar esta misión."))
                return
            }
            for ((normId, totalRequired) in totalRequiredByItem) {
                if (!itemConsumer.consumeItem(player, normId, totalRequired)) {
                    player.sendSystemMessage(Component.literal("§c[Desgraciamon] No se pudieron descontar los objetos requeridos para reclamar."))
                    return
                }
            }
        }

        state.claimedMissions.add(missionId)
        saveProgress()
        if (mission.moneyReward > 0 && !grantMoney(player, mission.moneyReward)) {
            state.claimedMissions.remove(missionId)
            saveProgress()
            player.sendSystemMessage(TextCatalog.component("reward.money_failed"))
            return
        }
        grantRewards(player, mission)
        if (mission.repeatable && mission.dailySlot == 0) {
            state.completedMissions.remove(missionId)
            state.claimedMissions.remove(missionId)
            state.missionProgress[missionId] = 0
            state.objectiveProgress.remove(missionId)
        }
        saveProgress()
        sync(player)
    }

    private fun claimDailyBonus(player: ServerPlayer, state: PlayerBulletinState) {
        if (dailyScheduler.isPaused()) return
        val bonus = DailyMissionCatalog.dailyBonus()
        val snapshot = dailyScheduler.snapshot(
            playerCompleted = state.completedMissions,
            playerClaimed = state.claimedMissions,
            bonusClaimedCycle = state.dailyBonusClaimedCycle,
            bonusConfig = bonus
        )
        val cycleId = snapshot.cycleId
        if (cycleId.isBlank() || snapshot.bonus.claimed) return
        if (snapshot.bonus.completedCount < snapshot.bonus.totalSlots) return
        if (bonus.moneyReward > 0 && !CobbleDollarsBridge.isAvailable()) {
            player.sendSystemMessage(TextCatalog.component("reward.money_mod_missing"))
            return
        }
        state.dailyBonusClaimedCycle = cycleId
        saveProgress()
        if (bonus.moneyReward > 0 && !grantMoney(player, bonus.moneyReward)) {
            state.dailyBonusClaimedCycle = ""
            saveProgress()
            player.sendSystemMessage(TextCatalog.component("reward.money_failed"))
            return
        }
        grantItemRewards(player, bonus.itemRewards)
        val hex = DailyMissionCatalog.uiTheme().notificationColorHex.ifBlank { "#38BDF8" }
        player.sendSystemMessage(
            RichText.parse("<color=$hex>Has reclamado la ${bonus.title} (3/3).</color>", emptyMap())
        )
        saveProgress()
        sync(player)
    }

    private fun grantMoney(player: ServerPlayer, amount: Long): Boolean {
        if (amount <= 0L) return true
        if (CobbleDollarsBridge.deposit(player, amount)) return true
        return runCatching {
            val command = "cobbledollars give ${player.gameProfile.name} $amount"
            player.server.commands.dispatcher.execute(command, player.server.createCommandSourceStack()) > 0
        }.onFailure { exception ->
            AnnouncementsMod.logger.error("Could not give $amount CobbleDollars to ${player.gameProfile.name}", exception)
        }.getOrDefault(false)
    }

    fun stateFor(uuid: UUID): PlayerBulletinState = playerStates.computeIfAbsent(uuid) { PlayerBulletinState() }
    fun stateFor(player: ServerPlayer): PlayerBulletinState = stateFor(player.uuid)

    private fun currentCycleId(): String =
        dailyScheduler.activeCycleId().ifBlank { LocalDate.now(dailyScheduler.getZoneId()).toString() }

    private fun resetDailyIfNeeded(state: PlayerBulletinState, targetCycleId: String = currentCycleId()): Boolean {
        if (state.dailyStamp == targetCycleId) return false
        val toClear = DailyMissionCatalog.allDailyMissionIds() + document.missions.filter { it.daily }.map { it.id }
        toClear.forEach { missionId ->
            state.missionProgress.remove(missionId)
            state.objectiveProgress.remove(missionId)
            state.completedMissions.remove(missionId)
            state.claimedMissions.remove(missionId)
            state.readMissionIds.remove(missionId)
            state.knownMissionIds.remove(missionId)
        }
        state.dailyBonusClaimedCycle = ""
        state.dailyStamp = targetCycleId
        return true
    }

    private fun defaultCountItems(player: ServerPlayer): Map<String, Int> = buildMap {
        (player.inventory.items + player.inventory.armor + player.inventory.offhand).forEach { stack ->
            if (!stack.isEmpty) {
                val id = BuiltInRegistries.ITEM.getKey(stack.item).toString()
                put(id, (get(id) ?: 0) + stack.count)
            }
        }
    }

    private fun defaultConsumeItem(player: ServerPlayer, itemId: String, amount: Int): Boolean {
        if (amount <= 0) return true
        val targetId = normalizeItemId(itemId)
        val stacks = player.inventory.items + player.inventory.armor + player.inventory.offhand
        val available = stacks.sumOf { stack ->
            if (!stack.isEmpty && normalizeItemId(BuiltInRegistries.ITEM.getKey(stack.item).toString()) == targetId) {
                stack.count
            } else 0
        }
        if (available < amount) return false
        var remaining = amount
        for (stack in stacks) {
            if (remaining <= 0) break
            if (!stack.isEmpty && normalizeItemId(BuiltInRegistries.ITEM.getKey(stack.item).toString()) == targetId) {
                val take = remaining.coerceAtMost(stack.count)
                stack.shrink(take)
                remaining -= take
            }
        }
        player.inventory.setChanged()
        return remaining == 0
    }

    internal fun aggregateRequiredConsumedItems(
        consumeObjectives: List<MissionObjective>,
        isGuildMission: Boolean,
        guildMemberCount: Int
    ): Map<String, Int> {
        val memberCount = guildMemberCount.coerceAtLeast(1)
        val totals = linkedMapOf<String, Int>()
        for (obj in consumeObjectives) {
            val normId = normalizeItemId(obj.itemId)
            if (normId.isBlank()) continue
            val required = (if (obj.targetUsesMemberCount && isGuildMission) memberCount else obj.amount).coerceAtLeast(0)
            totals[normId] = (totals[normId] ?: 0) + required
        }
        return totals
    }

    internal fun findMissingConsumedItem(
        requiredByItem: Map<String, Int>,
        normalizedCounts: Map<String, Int>
    ): String? {
        for ((normId, required) in requiredByItem) {
            val available = normalizedCounts[normId] ?: 0
            if (available < required) return normId
        }
        return null
    }

    private fun normalizeItemTotals(raw: Map<String, Int>): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        raw.forEach { (k, v) ->
            if (v > 0) {
                val nk = normalizeItemId(k)
                out[nk] = (out[nk] ?: 0) + v
            }
        }
        return out
    }

    private fun normalizeItemId(raw: String): String {
        val clean = raw.trim().lowercase()
        if (clean.isBlank()) return ""
        return if (':' in clean) clean else "minecraft:$clean"
    }

    private fun normalizeSpeciesId(raw: String): String {
        val clean = raw.trim().lowercase()
        if (clean.isBlank()) return ""
        return if (':' in clean) clean else "cobblemon:$clean"
    }

    private fun normalizePokemonType(raw: String): String {
        return raw.trim().lowercase().removePrefix("cobblemon:").removePrefix("type.")
    }

    private fun loadDailyState() {
        if (!::dailyStateFile.isInitialized || Files.notExists(dailyStateFile)) {
            dailyScheduler.loadState(DailyPersistedState(), markInitiallyUnlockedAsAnnounced = true)
            return
        }
        val persisted = runCatching {
            gson.fromJson(Files.readString(dailyStateFile), DailyPersistedState::class.java)
        }.onFailure {
            AnnouncementsMod.logger.error("Could not load daily schedule state", it)
        }.getOrNull() ?: DailyPersistedState()
        dailyScheduler.loadState(persisted, markInitiallyUnlockedAsAnnounced = true)
    }

    private fun saveDailyState() {
        if (!::dailyStateFile.isInitialized) return
        runCatching {
            Files.createDirectories(dailyStateFile.parent)
            val tempFile = Files.createTempFile(dailyStateFile.parent, "daily_", ".tmp")
            Files.writeString(tempFile, gson.toJson(dailyScheduler.exportState()))
            Files.move(tempFile, dailyStateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { AnnouncementsMod.logger.error("Could not save daily schedule state", it) }
    }

    private fun loadProgress() {
        if (Files.notExists(progressFile)) return
        runCatching {
            val root = JsonParser.parseString(Files.readString(progressFile)).asJsonObject
            root.entrySet().forEach { (id, element) ->
                val json = element.asJsonObject
                val completed = stringSet(json, "completedMissions")
                val state = PlayerBulletinState(
                    readIds = stringSet(json, "readIds"),
                    readMissionIds = stringSet(json, "readMissionIds"),
                    knownAnnouncementIds = stringSet(json, "knownAnnouncementIds"),
                    knownMissionIds = stringSet(json, "knownMissionIds"),
                    missionProgress = json.getAsJsonObject("missionProgress")?.entrySet()
                        ?.associate { it.key to it.value.asInt }?.toMutableMap() ?: mutableMapOf(),
                    objectiveProgress = json.getAsJsonObject("objectiveProgress")?.entrySet()
                        ?.associate { (missionId, targets) ->
                            missionId to targets.asJsonObject.entrySet().associate { it.key to it.value.asInt }.toMutableMap()
                        }?.toMutableMap() ?: mutableMapOf(),
                    completedMissions = completed,
                    claimedMissions = if (json.has("claimedMissions")) stringSet(json, "claimedMissions") else completed.toMutableSet(),
                    dailyStamp = json.get("dailyStamp")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                    dailyBonusClaimedCycle = json.get("dailyBonusClaimedCycle")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                )
                runCatching { UUID.fromString(id) }.getOrNull()?.let { playerStates[it] = state }
            }
        }.onFailure { AnnouncementsMod.logger.error("Could not load player announcement progress", it) }
    }

    private fun stringSet(json: com.google.gson.JsonObject, name: String): MutableSet<String> =
        json.getAsJsonArray(name)?.mapNotNull { element -> element.takeUnless { it.isJsonNull }?.asString }?.toMutableSet()
            ?: mutableSetOf()

    private fun saveProgress() {
        if (!::progressFile.isInitialized) return
        runCatching {
            Files.createDirectories(progressFile.parent)
            val output = playerStates.entries.associate { it.key.toString() to it.value }
            val tempFile = Files.createTempFile(progressFile.parent, "progress_", ".tmp")
            Files.writeString(tempFile, gson.toJson(output))
            Files.move(tempFile, progressFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { AnnouncementsMod.logger.error("Could not save player announcement progress", it) }
    }

    private fun PlayerBulletinState.copyDeep() = PlayerBulletinState(
        readIds = readIds.toMutableSet(),
        readMissionIds = readMissionIds.toMutableSet(),
        knownAnnouncementIds = knownAnnouncementIds.toMutableSet(),
        knownMissionIds = knownMissionIds.toMutableSet(),
        missionProgress = missionProgress.toMutableMap(),
        objectiveProgress = objectiveProgress.mapValues { it.value.toMutableMap() }.toMutableMap(),
        completedMissions = completedMissions.toMutableSet(),
        claimedMissions = claimedMissions.toMutableSet(),
        dailyStamp = dailyStamp,
        playerGuildId = playerGuildId,
        guildMemberCount = guildMemberCount,
        adminMode = adminMode,
        dailyBonusClaimedCycle = dailyBonusClaimedCycle
    )

    private fun playMissionAnimation(player: ServerPlayer, completed: Boolean) {
        val settings = MissionAnimationSettings.current
        if (!settings.enabled || !FabricLoader.getInstance().isModLoaded("desgraciamon4_screen_animations")) return
        val animationName = if (completed) settings.completedAnimation else settings.newMissionAnimation
        val animationFolder = FabricLoader.getInstance().configDir
            .resolve("desgraciamon4_screen_animations/animations")
            .resolve(animationName)
        if (!Files.isRegularFile(animationFolder.resolve("animation.json"))) return
        if (runCatching { Files.list(animationFolder).use { paths -> paths.anyMatch { it.fileName.toString().matches(Regex("frame_\\d{3,6}\\.png")) } } }.getOrDefault(false).not()) return
        runCatching {
            player.server.commands.dispatcher.execute(
                "animacion play $animationName ${player.gameProfile.name}",
                player.server.createCommandSourceStack()
            )
        }
    }

    private const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
    private const val MAX_MONEY_REWARD = 1_000_000_000_000L
    private const val MAX_MISSION_OBJECTIVES = 32
}
