package com.desgraciamon.biomelocator

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import java.util.concurrent.ConcurrentHashMap

object BiomeLocatorClientState {
    private val GSON = Gson()

    var discoveredCount: Int = 0
    var tokens: Int = 1
    var currentTierGoal: Int = 5
    var tierProgress: Int = 0
    var unlockedBiomes: MutableSet<String> = mutableSetOf()
    var visitedDimensions: MutableSet<String> = mutableSetOf("overworld")
    var allBiomes: List<BiomeLocatorPackets.BiomeEntryData> = emptyList()
    var activeTrackedBiome: String? = null

    // Navigation tracking
    var navTargetX: Int = 0
    var navTargetZ: Int = 0
    var navBiomeName: String = ""
    var navStatus: String = "STOPPED" // "SEARCHING", "FOUND", "NOT_FOUND", "STOPPED", "REACHED"
    var navLastUpdatedMs: Long = 0L

    // Pokemon Search Results
    var matchedPokemonQuery: String = ""
    var matchedPokemonName: String = ""
    var matchedPokemonBiomeIds: Set<String> = emptySet()

    // Cache of Biome ID -> List of Spawns
    val biomeSpawnsCache = ConcurrentHashMap<String, List<CobblemonSpawnIntegration.BiomePokemonSpawnEntry>>()

    fun handleSync(payload: BiomeLocatorPackets.S2CSyncPayload) {
        discoveredCount = payload.discoveredCount
        tokens = payload.tokens
        currentTierGoal = payload.currentTierGoal
        tierProgress = payload.tierProgress
        unlockedBiomes = payload.unlockedBiomes.toMutableSet()
        visitedDimensions = payload.visitedDimensions.toMutableSet()
        allBiomes = payload.biomes
        activeTrackedBiome = payload.activeTrackedBiome

        val screen = Minecraft.getInstance().screen
        if (screen is BiomeLocatorScreen) {
            screen.onSyncUpdated()
        }
    }

    fun handleSpawns(payload: BiomeLocatorPackets.S2CBiomeSpawnsPayload) {
        biomeSpawnsCache[payload.biomeId] = payload.spawns

        val screen = Minecraft.getInstance().screen
        if (screen is BiomeLocatorScreen) {
            screen.onSpawnsReceived(payload.biomeId)
        }
    }

    fun handlePokemonBiomes(payload: BiomeLocatorPackets.S2CPokemonBiomesPayload) {
        matchedPokemonQuery = payload.query
        matchedPokemonName = payload.pokemonName
        matchedPokemonBiomeIds = payload.biomeIds.toSet()

        val screen = Minecraft.getInstance().screen
        if (screen is BiomeLocatorScreen) {
            screen.onPokemonSearchResults(payload.query, payload.pokemonName, matchedPokemonBiomeIds)
        }
    }

    fun handleNav(payload: BiomeLocatorPackets.S2CNavPayload) {
        navBiomeName = payload.biomeName
        navTargetX = payload.targetX
        navTargetZ = payload.targetZ
        navStatus = payload.status
        navLastUpdatedMs = System.currentTimeMillis()

        if (payload.status == "STOPPED" || payload.status == "REACHED") {
            activeTrackedBiome = null
        } else if (payload.status == "FOUND" || payload.status == "SEARCHING") {
            activeTrackedBiome = payload.biomeId
        }

        val screen = Minecraft.getInstance().screen
        if (screen is BiomeLocatorScreen) {
            screen.onSyncUpdated()
        }
    }

    fun isDimensionUnlocked(dim: String): Boolean {
        return visitedDimensions.contains(dim.lowercase())
    }

    fun isBiomeUnlocked(biomeId: String): Boolean {
        return unlockedBiomes.contains(biomeId)
    }

    fun isBiomeTracked(biomeId: String): Boolean {
        return activeTrackedBiome == biomeId && (navStatus == "FOUND" || navStatus == "SEARCHING")
    }

    fun requestUnlock(biomeId: String) {
        ClientPlayNetworking.send(BiomeLocatorPackets.C2SActionPayload("UNLOCK", biomeId))
    }

    fun requestTrack(biomeId: String) {
        ClientPlayNetworking.send(BiomeLocatorPackets.C2SActionPayload("TRACK", biomeId))
    }

    fun requestTrackAnother(biomeId: String) {
        ClientPlayNetworking.send(BiomeLocatorPackets.C2SActionPayload("TRACK_ANOTHER", biomeId))
    }

    fun requestStop() {
        ClientPlayNetworking.send(BiomeLocatorPackets.C2SActionPayload("STOP", ""))
    }

    fun requestOpen() {
        ClientPlayNetworking.send(BiomeLocatorPackets.C2SActionPayload("OPEN", ""))
    }

    fun requestSpawns(biomeId: String) {
        ClientPlayNetworking.send(BiomeLocatorPackets.C2SActionPayload("GET_SPAWNS", biomeId))
    }

    fun requestPokemonSearch(query: String) {
        ClientPlayNetworking.send(BiomeLocatorPackets.C2SActionPayload("SEARCH_POKEMON", query))
    }
}
