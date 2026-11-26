package com.desgraciamon.biomelocator

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Biome
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object CobblemonSpawnIntegration {
    private val VE_ZONE: ZoneId = ZoneId.of("America/Caracas")
    private val GSON = GsonBuilder().create()
    private val CACHE_FILE = File("config/desgraciamon4_biome_locator/spawn_cache.json")
    private val refreshInFlight = AtomicBoolean(false)

    @Volatile
    private var lastCheckedBucket: Long = -1L

    @Volatile
    private var lastModsFingerprint: String = ""

    data class DiskSpawnCache(
        val version: Int = 1,
        val modsFingerprint: String = "",
        val lastCheckBucket: Long = -1L,
        val checkedAtVe: String = "",
        val biomes: Map<String, List<BiomePokemonSpawnEntry>> = emptyMap()
    )

    data class BiomePokemonSpawnEntry(
        val speciesId: String,          // e.g. "cobblemon:pikachu"
        val displayName: String,        // e.g. "Pikachu"
        val bucket: String,             // "common", "uncommon", "rare", "ultra-rare"
        val weight: Float,              // Spawn weight
        var percentage: Float,          // Calculated percentage within its bucket
        val minLevel: Int,
        val maxLevel: Int,
        val timeOfDay: String,          // "Día", "Noche", "Cualquiera", "Crepúsculo"
        val context: String,            // "Suelo", "Agua", "Cueva", "Aire", "Pesca"
        val lightReq: String,           // "Luz solar (8+)", "Oscuridad (0-7)", "Cualquiera"
        val weatherReq: String,         // "Lluvia", "Tormenta", "Despejado", "Cualquiera"
        val aspects: List<String> = emptyList()
    )

    private class SpeciesAccumulator(
        val speciesId: String,
        val displayName: String,
        val bucket: String,
        val aspects: List<String> = emptyList(),
        var totalWeight: Float = 0f,
        var minLevel: Int = 100,
        var maxLevel: Int = 1,
        val times: MutableSet<String> = mutableSetOf(),
        val contexts: MutableSet<String> = mutableSetOf(),
        val lights: MutableSet<String> = mutableSetOf(),
        val weathers: MutableSet<String> = mutableSetOf()
    )

    // Cache indexed by biome ID string (e.g. "minecraft:plains") -> Deduplicated list of species
    private val biomeSpawnsMap = ConcurrentHashMap<String, List<BiomePokemonSpawnEntry>>()

    // Cache indexed by lowercase Pokemon name & species ID -> Set of biome IDs
    private val pokemonToBiomesMap = ConcurrentHashMap<String, MutableSet<String>>()
    @Volatile
    private var isIndexed = false

    fun isReady(): Boolean = isIndexed && biomeSpawnsMap.isNotEmpty()

    private fun currentVe6HourBucket(): Long {
        val now = ZonedDateTime.now(VE_ZONE)
        return now.toLocalDate().toEpochDay() * 4L + (now.hour / 6L)
    }

    private fun computeModsFingerprint(): String {
        val modsPart = runCatching {
            FabricLoader.getInstance().allMods
                .map { "${it.metadata.id}=${it.metadata.version.friendlyString}" }
                .sorted()
                .joinToString("|")
        }.getOrDefault("")
        val poolSize = runCatching { CobblemonSpawnPools.WORLD_SPAWN_POOL.details.size }.getOrDefault(0)
        return "${modsPart.hashCode()}:$poolSize"
    }

    fun invalidateCache() {
        biomeSpawnsMap.clear()
        pokemonToBiomesMap.clear()
        isIndexed = false
        lastCheckedBucket = -1L
    }

    fun checkScheduledRefresh(server: MinecraftServer) {
        if (!isReady()) return
        val bucket = currentVe6HourBucket()
        val fp = computeModsFingerprint()
        if (bucket != lastCheckedBucket || fp != lastModsFingerprint) {
            scheduleAsyncRefresh(server)
        }
    }

    fun ensureIndexed(server: MinecraftServer) {
        if (isReady()) {
            checkScheduledRefresh(server)
            return
        }

        synchronized(this) {
            if (isReady()) return
            if (tryLoadDiskCache()) {
                val bucket = currentVe6HourBucket()
                val fp = computeModsFingerprint()
                if (bucket != lastCheckedBucket || fp != lastModsFingerprint) {
                    scheduleAsyncRefresh(server)
                }
                return
            }
            val result = computeSpawnMaps(server)
            applyComputedMaps(result.first, result.second, computeModsFingerprint(), currentVe6HourBucket(), saveToDisk = true)
        }
    }

    private fun scheduleAsyncRefresh(server: MinecraftServer) {
        if (!refreshInFlight.compareAndSet(false, true)) return
        CompletableFuture.runAsync {
            try {
                val result = computeSpawnMaps(server)
                val fp = computeModsFingerprint()
                val bucket = currentVe6HourBucket()
                if (result.first.isNotEmpty()) {
                    applyComputedMaps(result.first, result.second, fp, bucket, saveToDisk = true)
                } else {
                    lastCheckedBucket = bucket
                    lastModsFingerprint = fp
                }
            } catch (t: Throwable) {
                System.err.println("[BiomeLocator] Async spawn refresh error: ${t.message}")
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    private fun tryLoadDiskCache(): Boolean {
        return try {
            if (!CACHE_FILE.exists()) return false
            val cached = CACHE_FILE.bufferedReader().use { GSON.fromJson(it, DiskSpawnCache::class.java) } ?: return false
            if (cached.biomes.isEmpty()) return false

            val loadedPokemonMap = HashMap<String, MutableSet<String>>()
            for ((biomeId, entries) in cached.biomes) {
                for (entry in entries) {
                    val speciesPath = entry.speciesId.substringAfter(':').lowercase()
                    val displayLower = entry.displayName.lowercase()
                    loadedPokemonMap.computeIfAbsent(displayLower) { ConcurrentHashMap.newKeySet() }.add(biomeId)
                    loadedPokemonMap.computeIfAbsent(speciesPath) { ConcurrentHashMap.newKeySet() }.add(biomeId)
                    val baseDisplay = displayLower.substringBefore(" (").trim()
                    if (baseDisplay.isNotEmpty()) {
                        loadedPokemonMap.computeIfAbsent(baseDisplay) { ConcurrentHashMap.newKeySet() }.add(biomeId)
                    }
                }
            }
            biomeSpawnsMap.clear()
            biomeSpawnsMap.putAll(cached.biomes)
            pokemonToBiomesMap.clear()
            pokemonToBiomesMap.putAll(loadedPokemonMap)
            lastModsFingerprint = cached.modsFingerprint
            lastCheckedBucket = cached.lastCheckBucket
            isIndexed = true
            println("[BiomeLocator] Loaded spawn cache from ${CACHE_FILE.path} (${biomeSpawnsMap.size} biomes, checked ${cached.checkedAtVe}).")
            true
        } catch (t: Throwable) {
            System.err.println("[BiomeLocator] Failed reading spawn_cache.json, rebuilding: ${t.message}")
            false
        }
    }

    private fun applyComputedMaps(
        newBiomeMap: Map<String, List<BiomePokemonSpawnEntry>>,
        newPokemonMap: Map<String, MutableSet<String>>,
        fingerprint: String,
        bucket: Long,
        saveToDisk: Boolean
    ) {
        biomeSpawnsMap.clear()
        biomeSpawnsMap.putAll(newBiomeMap)
        pokemonToBiomesMap.clear()
        pokemonToBiomesMap.putAll(newPokemonMap)
        lastModsFingerprint = fingerprint
        lastCheckedBucket = bucket
        isIndexed = true
        if (saveToDisk && newBiomeMap.isNotEmpty()) {
            saveDiskCache(newBiomeMap, fingerprint, bucket)
        }
        println("[BiomeLocator] Cobblemon spawn index ready (${biomeSpawnsMap.size} biomes indexed).")
    }

    private fun saveDiskCache(biomes: Map<String, List<BiomePokemonSpawnEntry>>, fingerprint: String, bucket: Long) {
        try {
            CACHE_FILE.parentFile?.mkdirs()
            val veTimeStr = ZonedDateTime.now(VE_ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC-4'"))
            val payload = DiskSpawnCache(
                version = 1,
                modsFingerprint = fingerprint,
                lastCheckBucket = bucket,
                checkedAtVe = veTimeStr,
                biomes = biomes
            )
            CACHE_FILE.bufferedWriter().use { GSON.toJson(payload, it) }
        } catch (t: Throwable) {
            System.err.println("[BiomeLocator] Failed writing spawn_cache.json: ${t.message}")
        }
    }

    private fun computeSpawnMaps(server: MinecraftServer): Pair<Map<String, List<BiomePokemonSpawnEntry>>, Map<String, MutableSet<String>>> {
        val resultBiomeMap = HashMap<String, List<BiomePokemonSpawnEntry>>()
        val resultPokemonMap = HashMap<String, MutableSet<String>>()

        try {
            val pool = CobblemonSpawnPools.WORLD_SPAWN_POOL
            val details: List<SpawnDetail> = pool.details
            val biomeRegistry = server.registryAccess().registryOrThrow(Registries.BIOME)
            val allBiomeHolders = biomeRegistry.holders().toList()

            // Temporary accumulator: BiomeId -> Map<SpeciesKey, SpeciesAccumulator>
            val tempBiomeAccumulators = mutableMapOf<String, MutableMap<String, SpeciesAccumulator>>()

            for (detail in details) {
                if (detail !is PokemonSpawnDetail) continue

                val speciesRaw = detail.pokemon.species ?: continue
                val speciesRes = if (speciesRaw.contains(":")) {
                    ResourceLocation.tryParse(speciesRaw.lowercase())
                } else {
                    ResourceLocation.fromNamespaceAndPath("cobblemon", speciesRaw.lowercase())
                } ?: continue

                val species = try { PokemonSpecies.getByIdentifier(speciesRes) } catch (_: Throwable) { null }
                val baseDisplayName = species?.translatedName?.string ?: speciesRaw.replaceFirstChar { it.uppercase() }

                // Extract regional & form aspects
                val extractedAspects = mutableSetOf<String>()
                try {
                    detail.pokemon.updateAspects()
                } catch (_: Throwable) {}
                extractedAspects.addAll(detail.pokemon.aspects.map { it.lowercase() })
                for (prop in detail.pokemon.customProperties) {
                    val ps = prop.asString().lowercase()
                    extractedAspects.add(ps)
                    if (ps.contains("=")) {
                        extractedAspects.add(ps.substringAfter("=").trim())
                    }
                }
                val formStr = detail.pokemon.form?.lowercase()?.trim() ?: ""
                if (formStr.isNotBlank()) {
                    extractedAspects.add(formStr)
                    species?.forms?.firstOrNull {
                        it.name.equals(formStr, ignoreCase = true) || it.formOnlyShowdownId().equals(formStr, ignoreCase = true)
                    }?.aspects?.forEach { extractedAspects.add(it.lowercase()) }
                }
                val rawCombined = "${detail.pokemon.originalString} ${detail.id} $formStr $speciesRaw ${extractedAspects.joinToString(" ")}".lowercase()
                if (rawCombined.contains("galar")) extractedAspects.add("galarian")
                if (rawCombined.contains("alola")) extractedAspects.add("alolan")
                if (rawCombined.contains("hisui")) extractedAspects.add("hisuian")
                if (rawCombined.contains("paldea")) extractedAspects.add("paldean")
                if (rawCombined.contains("valencia")) extractedAspects.add("valencian")

                val regionalSuffix = when {
                    "galarian" in extractedAspects && !baseDisplayName.contains("Galar", ignoreCase = true) -> " (Galar)"
                    "alolan" in extractedAspects && !baseDisplayName.contains("Alola", ignoreCase = true) -> " (Alola)"
                    "hisuian" in extractedAspects && !baseDisplayName.contains("Hisui", ignoreCase = true) -> " (Hisui)"
                    "paldean" in extractedAspects && !baseDisplayName.contains("Paldea", ignoreCase = true) -> " (Paldea)"
                    else -> ""
                }
                val displayName = baseDisplayName + regionalSuffix
                val aspectsList = extractedAspects.sorted()

                // Determine Rarity Bucket
                val rawBucket = try { detail.bucket.lowercase(java.util.Locale.ROOT).ifBlank { "common" } } catch (_: Throwable) { "common" }
                val bucket = when {
                    rawBucket.contains("ultra") -> "ultra-rare"
                    rawBucket.contains("rare") -> "rare"
                    rawBucket.contains("uncommon") -> "uncommon"
                    else -> "common"
                }

                val weight = if (detail.weight > 0) detail.weight else 10f
                val minLvl = detail.levelRange?.first ?: 1
                val maxLvl = detail.levelRange?.last ?: 100

                // Parse conditions
                var timeOfDay = "Cualquiera"
                var context = "Suelo"
                var lightReq = "Cualquiera"
                var weatherReq = "Cualquiera"

                for (cond in detail.conditions) {
                    cond.timeRange?.let { tr ->
                        val hasDay = tr.contains(6000)
                        val hasNight = tr.contains(18000)
                        timeOfDay = when {
                            hasDay && !hasNight -> "Día"
                            hasNight && !hasDay -> "Noche"
                            tr.contains(0) || tr.contains(12000) -> "Crepúsculo"
                            else -> "Cualquiera"
                        }
                    }

                    if (cond.canSeeSky == false || (cond.maxY != null && cond.maxY!! < 48f)) {
                        context = "Cueva / Subterráneo"
                    }

                    if (cond.minLight != null || cond.maxLight != null) {
                        val minL = cond.minLight ?: 0
                        val maxL = cond.maxLight ?: 15
                        if (maxL <= 7) lightReq = "Oscuridad"
                        else if (minL >= 8) lightReq = "Luz solar"
                    }

                    if (cond.isRaining == true) weatherReq = "Lluvia"
                    if (cond.isThundering == true) weatherReq = "Tormenta"
                }

                val typeName = try { detail.spawnablePositionType.name.lowercase() } catch (_: Throwable) { "" }
                if (typeName.contains("submerged") || typeName.contains("surface") || typeName.contains("water") || typeName.contains("seafloor")) {
                    context = if (typeName.contains("seafloor")) "Fondo marino" else "Agua"
                } else if (typeName.contains("fishing")) {
                    context = "Pesca"
                } else if (typeName.contains("air") || typeName.contains("sky")) {
                    context = "Aire"
                }

                // Check genuine biome compatibility for this spawn detail
                for (holder in allBiomeHolders) {
                    if (!isDetailValidForBiome(holder, detail)) continue

                    val key = holder.unwrapKey().orElse(null) ?: continue
                    val bId = key.location().toString()
                    val biomeMap = tempBiomeAccumulators.computeIfAbsent(bId) { mutableMapOf() }

                    // Key by speciesId, aspects and bucket so regional forms and rarity variants don't clash
                    val accKey = "${speciesRes}_${aspectsList.joinToString(",")}_$bucket"
                    val acc = biomeMap.computeIfAbsent(accKey) {
                        SpeciesAccumulator(
                            speciesId = speciesRes.toString(),
                            displayName = displayName,
                            bucket = bucket,
                            aspects = aspectsList
                        )
                    }

                    acc.totalWeight += weight
                    acc.minLevel = minOf(acc.minLevel, minLvl)
                    acc.maxLevel = maxOf(acc.maxLevel, maxLvl)
                    if (timeOfDay != "Cualquiera") acc.times.add(timeOfDay)
                    if (context != "Suelo") acc.contexts.add(context)
                    if (lightReq != "Cualquiera") acc.lights.add(lightReq)
                    if (weatherReq != "Cualquiera") acc.weathers.add(weatherReq)

                    // Index for Pokemon search
                    resultPokemonMap.computeIfAbsent(displayName.lowercase()) { ConcurrentHashMap.newKeySet() }.add(bId)
                    resultPokemonMap.computeIfAbsent(baseDisplayName.lowercase()) { ConcurrentHashMap.newKeySet() }.add(bId)
                    resultPokemonMap.computeIfAbsent(speciesRaw.lowercase()) { ConcurrentHashMap.newKeySet() }.add(bId)
                }
            }

            // Convert accumulators to clean finalized lists per biome
            for ((biomeId, speciesMap) in tempBiomeAccumulators) {
                val groupedByBucket = speciesMap.values.groupBy { it.bucket }
                val finalizedList = mutableListOf<BiomePokemonSpawnEntry>()

                for ((_, bucketList) in groupedByBucket) {
                    val totalWeight = bucketList.sumOf { it.totalWeight.toDouble() }

                    for (acc in bucketList) {
                        val pct = if (totalWeight > 0) ((acc.totalWeight / totalWeight) * 100.0).toFloat() else (100f / bucketList.size)
                        val safePct = ((pct * 10).toInt() / 10f).coerceAtLeast(0.1f)

                        val hasBothTimes = acc.times.contains("Día") && acc.times.contains("Noche")
                        val timeStr = when {
                            acc.times.isEmpty() || hasBothTimes -> "Día y noche"
                            else -> acc.times.joinToString("/")
                        }
                        val ctxStr = if (acc.contexts.isEmpty()) "Suelo" else acc.contexts.joinToString(" / ")
                        val lightStr = if (acc.lights.isEmpty()) "Cualquiera" else acc.lights.joinToString(", ")
                        val weatherStr = if (acc.weathers.isEmpty()) "Cualquiera" else acc.weathers.joinToString(", ")

                        finalizedList.add(
                            BiomePokemonSpawnEntry(
                                speciesId = acc.speciesId,
                                displayName = acc.displayName,
                                bucket = acc.bucket,
                                weight = acc.totalWeight,
                                percentage = safePct,
                                minLevel = if (acc.minLevel > acc.maxLevel) 1 else acc.minLevel,
                                maxLevel = maxOf(1, acc.maxLevel),
                                timeOfDay = timeStr,
                                context = ctxStr,
                                lightReq = lightStr,
                                weatherReq = weatherStr,
                                aspects = acc.aspects
                            )
                        )
                    }
                }

                // Sort: ultra-rare first, then rare, then uncommon, then common, descending by percentage
                val bucketOrder = mapOf("ultra-rare" to 0, "rare" to 1, "uncommon" to 2, "common" to 3)
                finalizedList.sortWith(compareBy({ bucketOrder[it.bucket] ?: 4 }, { -it.percentage }))

                resultBiomeMap[biomeId] = finalizedList
            }
        } catch (t: Throwable) {
            System.err.println("[BiomeLocator] Error building Cobblemon spawn index: ${t.message}")
            t.printStackTrace()
        }
        return Pair(resultBiomeMap, resultPokemonMap)
    }

    private fun isDetailValidForBiome(holder: Holder<Biome>, detail: PokemonSpawnDetail): Boolean {
        // 1. Any condition that specifies biomes MUST be satisfied!
        val biomeConditions = detail.conditions.filter { it.biomes != null && it.biomes!!.isNotEmpty() }
        if (biomeConditions.isNotEmpty()) {
            val matches = biomeConditions.any { cond ->
                cond.biomes!!.any { it.fits(holder) }
            }
            if (!matches) return false
        } else {
            // If no condition specifies biomes, check if detail is constrained to structures or dimensions
            val hasStructureCondition = detail.conditions.any { it.structures != null && it.structures!!.isNotEmpty() }
            if (hasStructureCondition) {
                // Structure-only spawns shouldn't blanket every biome
                return false
            }
            val key = holder.unwrapKey().orElse(null)?.location()
            if (key != null && !detail.validBiomes.contains(key)) {
                return false
            }
        }

        // 2. Anticonditions: if any matches, reject!
        for (anti in detail.anticonditions) {
            if (anti.biomes != null && anti.biomes!!.isNotEmpty()) {
                if (anti.biomes!!.any { it.fits(holder) }) return false
            }
        }

        // 3. Composite condition
        if (detail.compositeCondition != null && !detail.compositeCondition!!.isBiomeValid(holder)) {
            return false
        }

        return true
    }

    fun getSpawnsForBiome(server: MinecraftServer, biomeId: String): List<BiomePokemonSpawnEntry> {
        ensureIndexed(server)
        return biomeSpawnsMap[biomeId] ?: emptyList()
    }

    fun searchPokemonBiomes(server: MinecraftServer, query: String): Pair<String, List<String>> {
        ensureIndexed(server)
        val q = query.trim().lowercase()
        if (q.isBlank()) return Pair("", emptyList())

        // 1. Exact match
        for ((key, biomes) in pokemonToBiomesMap) {
            if (key.equals(q, ignoreCase = true)) {
                val name = key.replaceFirstChar { it.uppercase() }
                return Pair(name, biomes.toList())
            }
        }

        // 2. Starts with (min length 3)
        if (q.length >= 3) {
            for ((key, biomes) in pokemonToBiomesMap) {
                if (key.startsWith(q)) {
                    val name = key.replaceFirstChar { it.uppercase() }
                    return Pair(name, biomes.toList())
                }
            }
        }

        // 3. Contains (min length 3)
        if (q.length >= 3) {
            for ((key, biomes) in pokemonToBiomesMap) {
                if (key.contains(q)) {
                    val name = key.replaceFirstChar { it.uppercase() }
                    return Pair(name, biomes.toList())
                }
            }
        }

        return Pair("", emptyList())
    }

    fun getBiomesForPokemon(server: MinecraftServer, query: String): List<String> {
        ensureIndexed(server)
        return pokemonToBiomesMap[query.trim().lowercase()]?.toList() ?: emptyList()
    }
}
