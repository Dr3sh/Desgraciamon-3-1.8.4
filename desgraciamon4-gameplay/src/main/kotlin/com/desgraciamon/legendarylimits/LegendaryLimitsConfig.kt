package com.desgraciamon.legendarylimits

import com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import com.google.gson.GsonBuilder
import java.io.File

object LegendaryLimitsConfig {
    data class ConfigData(
        var enabled: Boolean = true,
        var defaultLegendaryLimit: Int = 1,
        var excludeUltraBeasts: Boolean = true,
        var cosmogGroupBaseLimit: Int = 2,
        var cosmogReducedBySolgaleoLunala: Boolean = true,
        var speciesLimits: MutableMap<String, Int> = linkedMapOf(
            "cobblemon:articuno" to 2,
            "cobblemon:zapdos" to 2,
            "cobblemon:moltres" to 2
        )
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val configFile = File("config/desgraciamon-legendary-limits.json")

    @Volatile
    var current: ConfigData = defaultConfig()
        private set

    val ULTRA_BEAST_NAMES: Set<String> = setOf(
        "nihilego", "buzzwole", "pheromosa", "xurkitree", "celesteela",
        "kartana", "guzzlord", "poipole", "naganadel", "stakataka", "blacephalon"
    )

    private fun defaultConfig(): ConfigData = ConfigData(
        enabled = true,
        defaultLegendaryLimit = 1,
        excludeUltraBeasts = true,
        cosmogGroupBaseLimit = 2,
        cosmogReducedBySolgaleoLunala = true,
        speciesLimits = linkedMapOf(
            "cobblemon:articuno" to 2,
            "cobblemon:zapdos" to 2,
            "cobblemon:moltres" to 2
        )
    )

    fun normalizeSpeciesId(raw: String): String {
        val clean = raw.trim().lowercase().substringBefore('#')
        if (clean.isEmpty()) return ""
        return if (clean.contains(':')) clean else "cobblemon:$clean"
    }

    fun isUltraBeast(pokemon: Pokemon): Boolean {
        return pokemon.isUltraBeast() || isUltraBeast(pokemon.species)
    }

    fun isUltraBeast(species: Species): Boolean {
        if (species.labels.any {
                it.equals(CobblemonPokemonLabels.ULTRA_BEAST, ignoreCase = true) ||
                    it.equals("ultra_beast", ignoreCase = true)
            }
        ) {
            return true
        }
        val path = species.resourceIdentifier.path.lowercase()
        val name = species.name.lowercase()
        return ULTRA_BEAST_NAMES.contains(path) || ULTRA_BEAST_NAMES.contains(name)
    }

    fun isLegendaryOrMythical(species: Species): Boolean {
        return species.labels.any {
            it.equals(CobblemonPokemonLabels.LEGENDARY, ignoreCase = true) ||
                it.equals("legendary", ignoreCase = true) ||
                it.equals(CobblemonPokemonLabels.MYTHICAL, ignoreCase = true) ||
                it.equals("mythical", ignoreCase = true)
        }
    }

    fun isTrackedSpecies(speciesId: String, species: Species?): Boolean {
        val cfg = current
        if (!cfg.enabled) return false
        val id = normalizeSpeciesId(speciesId)
        if (id.isEmpty()) return false

        val explicitLimit = cfg.speciesLimits[id]
        if (explicitLimit != null) {
            if (explicitLimit >= 0) return true
            return cfg.cosmogReducedBySolgaleoLunala &&
                (id == "cobblemon:solgaleo" || id == "cobblemon:lunala")
        }

        if (id == "cobblemon:cosmog" || id == "cobblemon:cosmoem") return true
        if (cfg.cosmogReducedBySolgaleoLunala &&
            (id == "cobblemon:solgaleo" || id == "cobblemon:lunala")
        ) {
            return true
        }

        if (species == null) return false
        if (cfg.excludeUltraBeasts && isUltraBeast(species)) return false
        return isLegendaryOrMythical(species)
    }

    @Synchronized
    fun load() {
        val parent = configFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        if (!configFile.exists()) {
            val defaults = defaultConfig()
            current = defaults
            save(defaults)
            return
        }

        val loaded = runCatching {
            configFile.bufferedReader(Charsets.UTF_8).use { reader ->
                gson.fromJson(reader, ConfigData::class.java)
            }
        }.getOrNull()

        if (loaded == null) {
            val defaults = defaultConfig()
            current = defaults
            return
        }

        val normalizedLimits = linkedMapOf<String, Int>()
        @Suppress("USELESS_ELVIS")
        val rawLimits = loaded.speciesLimits ?: linkedMapOf()
        for ((rawKey, value) in rawLimits) {
            val normalizedKey = normalizeSpeciesId(rawKey)
            if (normalizedKey.isNotEmpty()) {
                normalizedLimits[normalizedKey] = value
            }
        }

        current = ConfigData(
            enabled = loaded.enabled,
            defaultLegendaryLimit = loaded.defaultLegendaryLimit.coerceAtLeast(0),
            excludeUltraBeasts = loaded.excludeUltraBeasts,
            cosmogGroupBaseLimit = loaded.cosmogGroupBaseLimit.coerceAtLeast(0),
            cosmogReducedBySolgaleoLunala = loaded.cosmogReducedBySolgaleoLunala,
            speciesLimits = normalizedLimits
        )
    }

    @Synchronized
    fun save(config: ConfigData = current) {
        val parent = configFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        configFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            gson.toJson(config, writer)
        }
    }
}
