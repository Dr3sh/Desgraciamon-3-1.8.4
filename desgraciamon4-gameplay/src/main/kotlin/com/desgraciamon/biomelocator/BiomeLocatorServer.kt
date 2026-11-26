package com.desgraciamon.biomelocator

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.pokemon.Pokemon
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.QuartPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BiomeTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.storage.LevelResource
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object BiomeLocatorServer {
    data class ConfigData(
        val token_base_requirement: Int = 5,
        val token_exponent_multiplier: Double = 1.35
    )

    private var baseRequirement: Int = 5
    private var exponentMultiplier: Double = 1.35

    private fun loadConfig() {
        try {
            val cfgFile = File("config/desgraciamon4/biome_locator.json")
            if (cfgFile.exists()) {
                val cfg = GSON.fromJson(cfgFile.readText(), ConfigData::class.java)
                if (cfg != null) {
                    baseRequirement = maxOf(1, cfg.token_base_requirement)
                    exponentMultiplier = cfg.token_exponent_multiplier.coerceIn(1.05, 3.0)
                    return
                }
            } else {
                cfgFile.parentFile?.mkdirs()
                cfgFile.writeText(GSON.toJson(ConfigData(5, 1.35)))
            }
        } catch (e: Exception) {
            System.err.println("[BiomeLocator] Failed to load config/desgraciamon4/biome_locator.json: ${e.message}")
        }
    }

    data class TierInfo(val earnedTokens: Int, val currentTierGoal: Int, val progressInTier: Int)

    fun calculateTierInfo(speciesCount: Int): TierInfo {
        var earnedTokens = 0
        var currentThreshold = 0
        while (true) {
            val req = maxOf(1, Math.ceil(baseRequirement * Math.pow(exponentMultiplier, earnedTokens.toDouble())).toInt())
            if (speciesCount < currentThreshold + req) {
                val progress = speciesCount - currentThreshold
                return TierInfo(earnedTokens, req, progress)
            }
            currentThreshold += req
            earnedTokens++
        }
    }

    private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()

    private const val EXCLUSION_RADIUS = 450
    private const val MAX_SEARCH_RADIUS = 12800
    private const val MAX_EXCLUDED_PER_BIOME = 20

    private val playerDataMap = ConcurrentHashMap<UUID, PlayerBiomeData>()
    private val activeSearches = ConcurrentHashMap<UUID, CompletableFuture<*>>()
    private val lastSearchTimestamps = ConcurrentHashMap<UUID, Long>()
    private val excludedTargets = ConcurrentHashMap<UUID, ConcurrentHashMap<String, MutableList<Pair<Int, Int>>>>()

    @Volatile
    private var searchExecutor: ExecutorService? = null

    @Synchronized
    private fun getSearchExecutor(): ExecutorService {
        val current = searchExecutor
        if (current != null && !current.isShutdown && !current.isTerminated) {
            return current
        }
        val created = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "BiomeLocator-Worker").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
        searchExecutor = created
        return created
    }

    data class PlayerBiomeData(
        val playerUuid: String,
        val discoveredSpecies: MutableSet<String> = ConcurrentHashMap.newKeySet<String>(),
        var tokens: Int = 1, // Start with 1 free token per world!
        val unlockedBiomes: MutableSet<String> = ConcurrentHashMap.newKeySet<String>(),
        val visitedDimensions: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { add("overworld") },
        var activeTrackedBiome: String? = null,
        var activeTargetX: Int = 0,
        var activeTargetZ: Int = 0,
        var activeTargetDimension: String? = null,
        var hasActiveTarget: Boolean = false
    )

    fun initialize() {
        loadConfig()
    }

    fun onServerStopping() {
        playerDataMap.clear()
        activeSearches.values.forEach { it.cancel(true) }
        activeSearches.clear()
        lastSearchTimestamps.clear()
        excludedTargets.clear()
        synchronized(this) {
            runCatching { searchExecutor?.shutdownNow() }
            searchExecutor = null
        }
        CobblemonSpawnIntegration.invalidateCache()
    }

    private fun getWorldDataDir(server: MinecraftServer): File {
        val path = server.getWorldPath(LevelResource.ROOT)
        val dir = path.resolve("desgraciamon_biome_locator").resolve("players").toFile()
        dir.mkdirs()
        return dir
    }

    fun getData(player: ServerPlayer): PlayerBiomeData {
        return playerDataMap.computeIfAbsent(player.uuid) { loadPlayerData(player.server, player.uuid) }
    }

    private fun loadPlayerData(server: MinecraftServer, uuid: UUID): PlayerBiomeData {
        val dir = getWorldDataDir(server)
        val file = File(dir, "$uuid.json")
        if (file.exists()) {
            try {
                FileReader(file).use { reader ->
                    val data = GSON.fromJson(reader, PlayerBiomeData::class.java)
                    if (data != null) return data
                }
            } catch (e: Exception) {
                System.err.println("[BiomeLocator] Error loading per-world data for $uuid: ${e.message}")
            }
        }
        val newData = PlayerBiomeData(uuid.toString())
        savePlayerData(server, newData)
        return newData
    }

    fun savePlayerData(server: MinecraftServer, data: PlayerBiomeData) {
        try {
            val dir = getWorldDataDir(server)
            val file = File(dir, "${data.playerUuid}.json")
            FileWriter(file).use { writer ->
                GSON.toJson(data, writer)
            }
        } catch (e: Exception) {
            System.err.println("[BiomeLocator] Error saving per-world data for ${data.playerUuid}: ${e.message}")
        }
    }

    fun onPlayerJoin(player: ServerPlayer) {
        loadConfig()
        val data = getData(player)
        updateVisitedDimension(player, data)
        scanInitialPokemon(player, data)
        syncToPlayer(player)
        val tracked = data.activeTrackedBiome
        if (tracked != null && data.hasActiveTarget) {
            val loc = ResourceLocation.tryParse(tracked)
            if (loc != null) {
                ServerPlayNetworking.send(player, BiomeLocatorPackets.S2CNavPayload(
                    tracked, formatBiomeName(loc), data.activeTargetX, data.activeTargetZ, "FOUND"
                ))
            }
        }
    }

    fun updateVisitedDimension(player: ServerPlayer, data: PlayerBiomeData = getData(player)) {
        val dim = player.serverLevel().dimension()
        val changed = when (dim) {
            Level.NETHER -> data.visitedDimensions.add("nether")
            Level.END -> data.visitedDimensions.add("end")
            else -> data.visitedDimensions.add("overworld")
        }
        if (changed) {
            savePlayerData(player.server, data)
            syncToPlayer(player)
        }
    }

    fun scanInitialPokemon(player: ServerPlayer, data: PlayerBiomeData = getData(player)) {
        var added = 0
        try {
            // Party
            val party = Cobblemon.storage.getParty(player)
            party.forEach { p ->
                if (p != null) {
                    val sp = p.species.showdownId()
                    if (data.discoveredSpecies.add(sp)) added++
                }
            }
            // PC Box
            val pc = Cobblemon.storage.getPC(player)
            pc.forEach { p ->
                if (p != null) {
                    val sp = p.species.showdownId()
                    if (data.discoveredSpecies.add(sp)) added++
                }
            }
        } catch (ignored: Throwable) {}

        if (added > 0) {
            recalculateTokens(data)
            savePlayerData(player.server, data)
        }
    }

    fun registerPokemon(player: ServerPlayer, pokemon: Pokemon, source: String = "Captura") {
        val data = getData(player)
        val speciesId = pokemon.species.showdownId()
        val displayName = pokemon.species.name

        if (data.discoveredSpecies.add(speciesId)) {
            val total = data.discoveredSpecies.size
            player.sendSystemMessage(Component.literal(
                "§6[Atlas de Biomas] §a¡Nueva especie registrada ($source): §e$displayName§a! §7($total registradas en este mundo)"
            ))

            val oldInfo = calculateTierInfo(total - 1)
            val newInfo = calculateTierInfo(total)
            if (newInfo.earnedTokens > oldInfo.earnedTokens) {
                data.tokens++
                player.sendSystemMessage(Component.literal(
                    "§6[Atlas de Biomas] §e§l¡Has conseguido una Ficha de Bioma! §a(Tienes ${data.tokens} disponibles para desbloquear. Siguiente meta: ${newInfo.currentTierGoal} especies)"
                ))
            }

            savePlayerData(player.server, data)
            syncToPlayer(player)
        }
    }

    private fun recalculateTokens(data: PlayerBiomeData) {
        val info = calculateTierInfo(data.discoveredSpecies.size)
        val earnedFromSpecies = 1 + info.earnedTokens
        val spentTokens = data.unlockedBiomes.size
        data.tokens = maxOf(0, earnedFromSpecies - spentTokens)
    }

    fun syncToPlayer(player: ServerPlayer) {
        val data = getData(player)
        val allBiomes = getAllRegisteredBiomes(player.server)

        val info = calculateTierInfo(data.discoveredSpecies.size)
        val payload = BiomeLocatorPackets.S2CSyncPayload(
            discoveredCount = data.discoveredSpecies.size,
            tokens = data.tokens,
            unlockedBiomes = data.unlockedBiomes.toList(),
            visitedDimensions = data.visitedDimensions.toList(),
            biomes = allBiomes,
            activeTrackedBiome = data.activeTrackedBiome,
            currentTierGoal = info.currentTierGoal,
            tierProgress = info.progressInTier
        )
        ServerPlayNetworking.send(player, payload)
    }

    fun getAllRegisteredBiomes(server: MinecraftServer): List<BiomeLocatorPackets.BiomeEntryData> {
        val registry = server.registryAccess().registryOrThrow(Registries.BIOME)
        val overworldBiomes = server.getLevel(Level.OVERWORLD)?.chunkSource?.generator?.biomeSource?.possibleBiomes()
            ?.mapNotNull { it.unwrapKey().orElse(null) }?.toSet() ?: emptySet()
        val netherBiomes = server.getLevel(Level.NETHER)?.chunkSource?.generator?.biomeSource?.possibleBiomes()
            ?.mapNotNull { it.unwrapKey().orElse(null) }?.toSet() ?: emptySet()
        val endBiomes = server.getLevel(Level.END)?.chunkSource?.generator?.biomeSource?.possibleBiomes()
            ?.mapNotNull { it.unwrapKey().orElse(null) }?.toSet() ?: emptySet()
        val list = mutableListOf<BiomeLocatorPackets.BiomeEntryData>()

        for (entry in registry.entrySet()) {
            val key = entry.key
            val id = key.location().toString()
            val biome = entry.value
            val holder = registry.wrapAsHolder(biome)

            val dim = getBiomeDimension(holder, key, overworldBiomes, netherBiomes, endBiomes)
            val category = getBiomeCategory(holder, key, biome)
            val name = formatBiomeName(key.location())
            val temp = biome.baseTemperature

            list.add(BiomeLocatorPackets.BiomeEntryData(
                id = id,
                name = name,
                dimension = dim,
                category = category,
                temperature = temp
            ))
        }

        return list.sortedBy { it.name }
    }

    private fun getBiomeDimension(
        holder: Holder<Biome>,
        key: ResourceKey<Biome>,
        overworldBiomes: Set<ResourceKey<Biome>> = emptySet(),
        netherBiomes: Set<ResourceKey<Biome>> = emptySet(),
        endBiomes: Set<ResourceKey<Biome>> = emptySet()
    ): String {
        if (key in netherBiomes) return "nether"
        if (key in endBiomes) return "end"
        if (key in overworldBiomes) return "overworld"
        val path = key.location().path.lowercase()
        return when {
            holder.`is`(BiomeTags.IS_NETHER) || path.contains("nether") || path == "crimson_forest" || path == "warped_forest" || path == "soul_sand_valley" || path == "basalt_deltas" -> "nether"
            holder.`is`(BiomeTags.IS_END) || path == "the_end" || path.startsWith("end_") || path.endsWith("_end") || path.contains("end_highlands") || path.contains("end_midlands") || path.contains("end_barrens") -> "end"
            else -> "overworld"
        }
    }

    private fun getBiomeCategory(holder: Holder<Biome>, key: ResourceKey<Biome>, biome: Biome): String {
        val path = key.location().path.lowercase()
        return when {
            holder.`is`(BiomeTags.IS_NETHER) -> "Nether"
            holder.`is`(BiomeTags.IS_END) -> "The End"
            holder.`is`(BiomeTags.IS_OCEAN) || path.contains("ocean") || path.contains("sea") -> "Océano"
            holder.`is`(BiomeTags.IS_RIVER) || path.contains("river") || path.contains("lake") -> "Río / Acuático"
            holder.`is`(BiomeTags.IS_MOUNTAIN) || path.contains("mountain") || path.contains("peaks") || path.contains("cliff") -> "Montaña"
            holder.`is`(BiomeTags.IS_FOREST) || path.contains("forest") || path.contains("wood") || path.contains("grove") -> "Bosque"
            path.contains("jungle") -> "Jungla"
            path.contains("desert") || path.contains("badlands") || path.contains("mesa") || path.contains("savanna") -> "Árido / Desierto"
            path.contains("snow") || path.contains("ice") || path.contains("frozen") || path.contains("tundra") -> "Gélido"
            path.contains("swamp") || path.contains("marsh") -> "Pantano"
            path.contains("mushroom") -> "Champiñón"
            else -> "Planicie"
        }
    }

    fun formatBiomeName(loc: ResourceLocation): String {
        return cleanEnglishBiomeName(loc.path)
    }

    fun translateBiomeName(idOrPath: String, fallbackName: String = ""): String {
        val cleanKey = idOrPath.substringAfter(':').trim()
        val english = cleanEnglishBiomeName(cleanKey)
        return if (english.isNotBlank()) english else fallbackName.ifBlank { idOrPath }
    }

    private fun cleanEnglishBiomeName(raw: String): String {
        val segment = raw.trim().substringAfterLast('/')
        val path = segment.lowercase().replace(' ', '_')
        if (path == "the_end") return "The End"
        if (path == "the_void") return "The Void"
        val words = path.split('_').filter { it.isNotBlank() }
        if (words.isEmpty()) return segment
        return words.joinToString(" ") { word ->
            when (word) {
                "of", "in", "and" -> word
                else -> word.replaceFirstChar { c -> c.uppercase() }
            }
        }
    }

    fun handleAction(player: ServerPlayer, action: String, biomeId: String) {
        val data = getData(player)
        when (action.uppercase()) {
            "OPEN" -> {
                updateVisitedDimension(player, data)
                syncToPlayer(player)
            }
            "GET_SPAWNS" -> {
                if (CobblemonSpawnIntegration.isReady()) {
                    val list = CobblemonSpawnIntegration.getSpawnsForBiome(player.server, biomeId)
                    ServerPlayNetworking.send(player, BiomeLocatorPackets.S2CBiomeSpawnsPayload(biomeId, list))
                } else {
                    getSearchExecutor().submit {
                        CobblemonSpawnIntegration.ensureIndexed(player.server)
                        val list = CobblemonSpawnIntegration.getSpawnsForBiome(player.server, biomeId)
                        player.server.execute {
                            if (!player.hasDisconnected()) {
                                ServerPlayNetworking.send(player, BiomeLocatorPackets.S2CBiomeSpawnsPayload(biomeId, list))
                            }
                        }
                    }
                }
            }
            "SEARCH_POKEMON" -> {
                if (CobblemonSpawnIntegration.isReady()) {
                    val result = CobblemonSpawnIntegration.searchPokemonBiomes(player.server, biomeId)
                    ServerPlayNetworking.send(player, BiomeLocatorPackets.S2CPokemonBiomesPayload(biomeId, result.first, result.second))
                } else {
                    getSearchExecutor().submit {
                        CobblemonSpawnIntegration.ensureIndexed(player.server)
                        val result = CobblemonSpawnIntegration.searchPokemonBiomes(player.server, biomeId)
                        player.server.execute {
                            if (!player.hasDisconnected()) {
                                ServerPlayNetworking.send(player, BiomeLocatorPackets.S2CPokemonBiomesPayload(biomeId, result.first, result.second))
                            }
                        }
                    }
                }
            }
            "UNLOCK" -> {
                if (data.tokens > 0 && !data.unlockedBiomes.contains(biomeId)) {
                    data.tokens--
                    data.unlockedBiomes.add(biomeId)
                    savePlayerData(player.server, data)
                    syncToPlayer(player)
                    val name = formatBiomeName(ResourceLocation.parse(biomeId))
                    player.sendSystemMessage(Component.literal(
                        "§6[Atlas de Biomas] §a¡Bioma desbloqueado: §f$name§a! Ahora puedes rastrearlo."
                    ))
                }
            }
            "TRACK" -> {
                if (data.unlockedBiomes.contains(biomeId)) {
                    excludedTargets[player.uuid]?.remove(biomeId)
                    data.activeTrackedBiome = biomeId
                    data.hasActiveTarget = false
                    savePlayerData(player.server, data)
                    startBiomeSearch(player, biomeId, findAnother = false)
                }
            }
            "TRACK_ANOTHER" -> {
                if (data.unlockedBiomes.contains(biomeId)) {
                    val playerExcl = excludedTargets.computeIfAbsent(player.uuid) { ConcurrentHashMap() }
                    val biomeExcl = playerExcl.computeIfAbsent(biomeId) { mutableListOf() }
                    synchronized(biomeExcl) {
                        if (data.activeTrackedBiome == biomeId && data.hasActiveTarget) {
                            addExclusionPoint(biomeExcl, data.activeTargetX, data.activeTargetZ)
                        }
                        val targetLoc = ResourceLocation.tryParse(biomeId)
                        if (targetLoc != null) {
                            val targetKey = ResourceKey.create(Registries.BIOME, targetLoc)
                            val standingInTarget = player.serverLevel().getBiome(player.blockPosition()).`is`(targetKey)
                            if (standingInTarget || biomeExcl.isEmpty()) {
                                addExclusionPoint(biomeExcl, player.blockX, player.blockZ)
                            }
                        }
                    }
                    data.activeTrackedBiome = biomeId
                    data.hasActiveTarget = false
                    savePlayerData(player.server, data)
                    startBiomeSearch(player, biomeId, findAnother = true)
                }
            }
            "STOP" -> {
                activeSearches.remove(player.uuid)?.cancel(true)
                data.activeTrackedBiome = null
                data.hasActiveTarget = false
                data.activeTargetX = 0
                data.activeTargetZ = 0
                data.activeTargetDimension = null
                savePlayerData(player.server, data)
                ServerPlayNetworking.send(player, BiomeLocatorPackets.S2CNavPayload(
                    "", "", 0, 0, "STOPPED"
                ))
                player.sendSystemMessage(TextCatalog.component("atlas.stopped"))
                syncToPlayer(player)
            }
        }
    }

    private fun addExclusionPoint(list: MutableList<Pair<Int, Int>>, x: Int, z: Int) {
        if (list.any { (ex, ez) -> (ex - x).toLong() * (ex - x) + (ez - z).toLong() * (ez - z) < 64L * 64L }) return
        list.add(x to z)
        while (list.size > MAX_EXCLUDED_PER_BIOME) {
            list.removeAt(0)
        }
    }

    private fun getLevelDimensionKey(level: ServerLevel): String {
        return when (level.dimension()) {
            Level.NETHER -> "nether"
            Level.END -> "end"
            else -> "overworld"
        }
    }

    private fun formatDimensionLabel(dim: String): String {
        return when (dim) {
            "nether" -> "el Nether"
            "end" -> "The End"
            else -> "el Overworld"
        }
    }

    fun startBiomeSearch(player: ServerPlayer, biomeId: String, findAnother: Boolean = false) {
        val now = System.currentTimeMillis()
        val lastSearch = lastSearchTimestamps.getOrDefault(player.uuid, 0L)
        if (now - lastSearch < 2000L) {
            player.sendSystemMessage(TextCatalog.component("atlas.cooldown"))
            return
        }
        lastSearchTimestamps[player.uuid] = now

        val server = player.server
        val world = player.serverLevel()
        val loc = ResourceLocation.tryParse(biomeId) ?: run {
            player.sendSystemMessage(TextCatalog.component("atlas.invalid_biome", "biomeId" to biomeId))
            return
        }
        val name = formatBiomeName(loc)
        val targetKey = ResourceKey.create(Registries.BIOME, loc)

        // Check dimension compatibility so the player gets a clear message instead of a false NOT_FOUND
        val registry = server.registryAccess().registryOrThrow(Registries.BIOME)
        val holderOpt = registry.getHolder(targetKey)
        if (holderOpt.isPresent) {
            val overworldBiomes = server.getLevel(Level.OVERWORLD)?.chunkSource?.generator?.biomeSource?.possibleBiomes()
                ?.mapNotNull { it.unwrapKey().orElse(null) }?.toSet() ?: emptySet()
            val netherBiomes = server.getLevel(Level.NETHER)?.chunkSource?.generator?.biomeSource?.possibleBiomes()
                ?.mapNotNull { it.unwrapKey().orElse(null) }?.toSet() ?: emptySet()
            val endBiomes = server.getLevel(Level.END)?.chunkSource?.generator?.biomeSource?.possibleBiomes()
                ?.mapNotNull { it.unwrapKey().orElse(null) }?.toSet() ?: emptySet()

            val biomeDim = getBiomeDimension(holderOpt.get(), targetKey, overworldBiomes, netherBiomes, endBiomes)
            val playerDim = getLevelDimensionKey(world)
            if (biomeDim != playerDim) {
                val d = getData(player)
                d.activeTrackedBiome = null
                d.hasActiveTarget = false
                savePlayerData(server, d)
                player.sendSystemMessage(Component.literal(
                    "§c[Atlas de Biomas] §e'$name' §cpertenece a §f${formatDimensionLabel(biomeDim)}§c. Viaja a esa dimensión para rastrearlo."
                ))
                ServerPlayNetworking.send(player, BiomeLocatorPackets.S2CNavPayload(
                    biomeId, name, 0, 0, "STOPPED"
                ))
                syncToPlayer(player)
                return
            }
        }

        // Cancel previous search if running
        activeSearches.remove(player.uuid)?.cancel(true)

        val originPos = player.blockPosition()
        val exclusionsSnapshot: List<Pair<Int, Int>> = if (findAnother) {
            val list = excludedTargets[player.uuid]?.get(biomeId)
            if (list != null) synchronized(list) { list.toList() } else emptyList()
        } else {
            emptyList()
        }

        val searchMsg = if (findAnother) {
            "§6[Atlas de Biomas] §bBuscando otra instancia de §f$name §b(gratis)..."
        } else {
            "§6[Atlas de Biomas] §eSintonizando radar con §f$name§e..."
        }
        player.sendSystemMessage(Component.literal(searchMsg))
        ServerPlayNetworking.send(player, BiomeLocatorPackets.S2CNavPayload(
            biomeId, name, 0, 0, "SEARCHING"
        ))

        val executor = getSearchExecutor()
        val future = CompletableFuture.supplyAsync({
            try {
                val path = loc.path.lowercase()
                val isCaveBiome = path.contains("cave") || path.contains("deep_dark") || path.contains("underground") || path.contains("dripstone")
                val (primaryYs, fallbackYs) = when {
                    world.dimension() == Level.NETHER -> intArrayOf(64, 32, 96) to intArrayOf()
                    world.dimension() == Level.END -> intArrayOf(64) to intArrayOf()
                    isCaveBiome -> intArrayOf(0, -32, 32) to intArrayOf(64, -48)
                    else -> intArrayOf(64, 144) to intArrayOf(0, -32, 208)
                }

                var found = searchNearestBiome(world, originPos, targetKey, primaryYs, exclusionsSnapshot, MAX_SEARCH_RADIUS)
                if (found == null && fallbackYs.isNotEmpty() && !Thread.currentThread().isInterrupted) {
                    found = searchNearestBiome(world, originPos, targetKey, fallbackYs, exclusionsSnapshot, MAX_SEARCH_RADIUS)
                }
                found
            } catch (t: Throwable) {
                System.err.println("[BiomeLocator] Error searching for biome $biomeId: ${t.message}")
                t.printStackTrace()
                null
            }
        }, executor).thenAccept { foundPos ->
            server.execute {
                val d = getData(player)
                // Ignore stale search results if player stopped or switched tracked biome
                if (d.activeTrackedBiome != biomeId) return@execute

                if (foundPos != null) {
                    val dx = (foundPos.x - player.blockX).toDouble()
                    val dz = (foundPos.z - player.blockZ).toDouble()
                    val dist = kotlin.math.sqrt(dx * dx + dz * dz).toInt()

                    val playerExcl = excludedTargets.computeIfAbsent(player.uuid) { ConcurrentHashMap() }
                    val biomeExcl = playerExcl.computeIfAbsent(biomeId) { mutableListOf() }
                    synchronized(biomeExcl) {
                        addExclusionPoint(biomeExcl, foundPos.x, foundPos.z)
                    }

                    if (!findAnother && dist <= 45) {
                        d.activeTrackedBiome = null
                        d.hasActiveTarget = false
                        d.activeTargetX = 0
                        d.activeTargetZ = 0
                        d.activeTargetDimension = null
                        savePlayerData(server, d)

                        player.sendSystemMessage(Component.literal(
                            "§6[Atlas de Biomas] §a¡Ya te encuentras en el hábitat §e$name§a! Pulsa §bRastrear otro §apara buscar otra zona."
                        ))
                        ServerPlayNetworking.send(player, BiomeLocatorPackets.S2CNavPayload(
                            biomeId, name, foundPos.x, foundPos.z, "REACHED"
                        ))
                        syncToPlayer(player)
                        return@execute
                    }

                    d.activeTargetX = foundPos.x
                    d.activeTargetZ = foundPos.z
                    d.activeTargetDimension = world.dimension().location().toString()
                    d.hasActiveTarget = true
                    savePlayerData(server, d)

                    val foundMsg = if (findAnother) {
                        "§6[Atlas de Biomas] §b¡Nueva instancia de §f$name §bdetectada! Distancia: §e~$dist bloques§b."
                    } else {
                        "§6[Atlas de Biomas] §a¡Bioma detectado! Rumbo establecido hacia §f$name§a. Distancia: §e~$dist bloques§a."
                    }
                    player.sendSystemMessage(Component.literal(foundMsg))

                    ServerPlayNetworking.send(player, BiomeLocatorPackets.S2CNavPayload(
                        biomeId, name, foundPos.x, foundPos.z, "FOUND"
                    ))
                } else {
                    d.activeTrackedBiome = null
                    d.hasActiveTarget = false
                    savePlayerData(server, d)

                    if (findAnother) {
                        excludedTargets[player.uuid]?.remove(biomeId)
                        player.sendSystemMessage(Component.literal(
                            "§c[Atlas de Biomas] No se encontraron otras instancias de '$name' en un radio de ${MAX_SEARCH_RADIUS} bloques. Historial reiniciado."
                        ))
                    } else {
                        player.sendSystemMessage(Component.literal(
                            "§c[Atlas de Biomas] No se encontraron formaciones de '$name' en un radio de ${MAX_SEARCH_RADIUS} bloques."
                        ))
                    }
                    ServerPlayNetworking.send(player, BiomeLocatorPackets.S2CNavPayload(
                        biomeId, name, 0, 0, "NOT_FOUND"
                    ))
                    syncToPlayer(player)
                }
            }
        }

        activeSearches[player.uuid] = future
    }

    private fun searchNearestBiome(
        world: ServerLevel,
        origin: BlockPos,
        targetKey: ResourceKey<Biome>,
        yLevels: IntArray,
        exclusions: List<Pair<Int, Int>>,
        maxRadius: Int = MAX_SEARCH_RADIUS
    ): BlockPos? {
        val biomeSource = world.chunkSource.generator.biomeSource
        val sampler = world.chunkSource.randomState().sampler()
        val quartYs = yLevels.map { QuartPos.fromBlock(it) }.distinct().toIntArray()
        val exclRadiusSq = EXCLUSION_RADIUS.toLong() * EXCLUSION_RADIUS.toLong()

        var bestX = 0
        var bestY = origin.y
        var bestZ = 0
        var bestDistSq = Long.MAX_VALUE

        fun isExcluded(wx: Int, wz: Int): Boolean {
            for ((ex, ez) in exclusions) {
                val edx = (wx - ex).toLong()
                val edz = (wz - ez).toLong()
                if (edx * edx + edz * edz <= exclRadiusSq) return true
            }
            return false
        }

        fun checkPoint(wx: Int, wz: Int) {
            val dx = (wx - origin.x).toLong()
            val dz = (wz - origin.z).toLong()
            val distSq = dx * dx + dz * dz
            if (distSq >= bestDistSq) return
            if (exclusions.isNotEmpty() && isExcluded(wx, wz)) return

            val qx = QuartPos.fromBlock(wx)
            val qz = QuartPos.fromBlock(wz)
            for (qy in quartYs) {
                val holder = biomeSource.getNoiseBiome(qx, qy, qz, sampler)
                if (holder.`is`(targetKey)) {
                    bestDistSq = distSq
                    bestX = wx
                    bestY = QuartPos.toBlock(qy)
                    bestZ = wz
                    return
                }
            }
        }

        checkPoint(origin.x, origin.z)
        if (bestDistSq == 0L) return BlockPos(bestX, bestY, bestZ)

        var r = 16
        while (r <= maxRadius) {
            if (Thread.currentThread().isInterrupted) return null
            if (bestDistSq != Long.MAX_VALUE && r.toLong() * r.toLong() >= bestDistSq) {
                break
            }

            val step = when {
                r <= 2048 -> 16
                r <= 6400 -> 32
                else -> 64
            }

            var offset = -r
            while (offset <= r) {
                checkPoint(origin.x + offset, origin.z - r)
                checkPoint(origin.x + offset, origin.z + r)
                offset += step
            }
            offset = -r + step
            while (offset < r) {
                checkPoint(origin.x - r, origin.z + offset)
                checkPoint(origin.x + r, origin.z + offset)
                offset += step
            }

            r += step
        }

        return if (bestDistSq != Long.MAX_VALUE) BlockPos(bestX, bestY, bestZ) else null
    }

    private var refreshCheckCounter = 0

    fun tick(server: MinecraftServer) {
        if (++refreshCheckCounter >= 1200) {
            refreshCheckCounter = 0
            CobblemonSpawnIntegration.checkScheduledRefresh(server)
        }
        for (player in server.playerList.players) {
            val data = getData(player)
            val targetBiome = data.activeTrackedBiome ?: continue
            if (!data.hasActiveTarget) continue
            val targetDim = data.activeTargetDimension
            if (targetDim != null && player.serverLevel().dimension().location().toString() != targetDim) continue

            val tx = data.activeTargetX
            val tz = data.activeTargetZ

            val dx = (player.blockX - tx).toLong()
            val dz = (player.blockZ - tz).toLong()
            val distSq = dx * dx + dz * dz

            // Within 45 blocks = Reached!
            if (distSq <= 45L * 45L) {
                val loc = ResourceLocation.parse(targetBiome)
                val name = formatBiomeName(loc)
                data.activeTrackedBiome = null
                data.hasActiveTarget = false
                data.activeTargetX = 0
                data.activeTargetZ = 0
                data.activeTargetDimension = null
                savePlayerData(server, data)

                player.sendSystemMessage(Component.literal(
                    "§6[Atlas de Biomas] §a¡Has llegado al hábitat: §e$name§a! ¡Explora y captura sus Pokémon!"
                ))

                ServerPlayNetworking.send(player, BiomeLocatorPackets.S2CNavPayload(
                    targetBiome, name, player.blockX, player.blockZ, "REACHED"
                ))
                syncToPlayer(player)
            }
        }
    }

    // Debug helpers for commands
    fun debugAddSpecies(player: ServerPlayer, speciesId: String) {
        val data = getData(player)
        if (data.discoveredSpecies.add(speciesId.lowercase())) {
            recalculateTokens(data)
            savePlayerData(player.server, data)
            syncToPlayer(player)
            player.sendSystemMessage(Component.literal("§a[DEBUG] Especie '$speciesId' añadida. Total: ${data.discoveredSpecies.size}. Fichas: ${data.tokens}"))
        } else {
            player.sendSystemMessage(Component.literal("§e[DEBUG] La especie '$speciesId' ya estaba registrada."))
        }
    }

    fun debugAddTokens(player: ServerPlayer, amount: Int) {
        val data = getData(player)
        data.tokens = maxOf(0, data.tokens + amount)
        savePlayerData(player.server, data)
        syncToPlayer(player)
        player.sendSystemMessage(Component.literal("§a[DEBUG] Fichas modificadas en $amount. Total fichas: ${data.tokens}"))
    }

    fun debugReset(player: ServerPlayer) {
        val data = getData(player)
        data.discoveredSpecies.clear()
        data.tokens = 1
        data.unlockedBiomes.clear()
        data.activeTrackedBiome = null
        data.hasActiveTarget = false
        data.activeTargetX = 0
        data.activeTargetZ = 0
        data.activeTargetDimension = null
        excludedTargets.remove(player.uuid)
        data.visitedDimensions.clear()
        data.visitedDimensions.add("overworld")
        savePlayerData(player.server, data)
        syncToPlayer(player)
        player.sendSystemMessage(Component.literal("§c[DEBUG] Todos los datos de biomas y especies han sido reseteados a 0 para este mundo."))
    }

    fun debugUnlockAll(player: ServerPlayer) {
        val data = getData(player)
        val all = getAllRegisteredBiomes(player.server)
        all.forEach { data.unlockedBiomes.add(it.id) }
        savePlayerData(player.server, data)
        syncToPlayer(player)
        player.sendSystemMessage(Component.literal("§a[DEBUG] Todos los ${all.size} biomas del juego han sido desbloqueados."))
    }

    fun debugUnlockDimension(player: ServerPlayer, dim: String) {
        val data = getData(player)
        if (dim == "all") {
            data.visitedDimensions.add("overworld")
            data.visitedDimensions.add("nether")
            data.visitedDimensions.add("end")
        } else {
            data.visitedDimensions.add(dim.lowercase())
        }
        savePlayerData(player.server, data)
        syncToPlayer(player)
        player.sendSystemMessage(Component.literal("§a[DEBUG] Dimensión '$dim' desbloqueada en tu menú."))
    }
}
