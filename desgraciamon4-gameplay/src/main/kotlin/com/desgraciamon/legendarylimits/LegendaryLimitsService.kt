package com.desgraciamon.legendarylimits

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer

data class LimitStatus(
    val speciesId: String,
    val displayName: String,
    val tracked: Boolean,
    val currentCount: Int,
    val effectiveLimit: Int,
    val baseLimit: Int,
    val owners: List<String>,
    val note: String
) {
    val canCapture: Boolean
        get() = !tracked || currentCount < effectiveLimit
}

object LegendaryLimitsService {
    fun resolveSpecies(rawSpeciesId: String): Species? {
        val normalized = LegendaryLimitsConfig.normalizeSpeciesId(rawSpeciesId)
        if (normalized.isEmpty()) return null
        val rl = ResourceLocation.tryParse(normalized)
        return (rl?.let { PokemonSpecies.getByIdentifier(it) })
            ?: PokemonSpecies.getByName(normalized.substringAfter(':'))
    }

    fun getLimitStatus(
        server: MinecraftServer,
        rawSpeciesId: String,
        speciesHint: Species? = null
    ): LimitStatus {
        val cfg = LegendaryLimitsConfig.current
        val speciesId = LegendaryLimitsConfig.normalizeSpeciesId(rawSpeciesId)
        val species = speciesHint ?: resolveSpecies(speciesId)
        val displayName = species?.name ?: speciesId

        if (!cfg.enabled || speciesId.isEmpty()) {
            return LimitStatus(
                speciesId = speciesId,
                displayName = displayName,
                tracked = false,
                currentCount = 0,
                effectiveLimit = Int.MAX_VALUE,
                baseLimit = Int.MAX_VALUE,
                owners = emptyList(),
                note = "Sistema de límites desactivado"
            )
        }

        val data = LegendaryLimitsData.get(server)
        val explicitLimit = cfg.speciesLimits[speciesId]

        if (speciesId == "cobblemon:cosmog" || speciesId == "cobblemon:cosmoem") {
            if (explicitLimit != null && explicitLimit < 0) {
                return LimitStatus(
                    speciesId = speciesId,
                    displayName = displayName,
                    tracked = false,
                    currentCount = 0,
                    effectiveLimit = Int.MAX_VALUE,
                    baseLimit = -1,
                    owners = emptyList(),
                    note = "Exento por configuración explícita ($explicitLimit)"
                )
            }

            val baseLimit = (explicitLimit ?: cfg.cosmogGroupBaseLimit).coerceAtLeast(0)
            val solgaleoCount = data.countSpecies("cobblemon:solgaleo")
            val lunalaCount = data.countSpecies("cobblemon:lunala")
            val reduction = if (cfg.cosmogReducedBySolgaleoLunala) {
                solgaleoCount + lunalaCount
            } else {
                0
            }
            val effectiveLimit = (baseLimit - reduction).coerceAtLeast(0)
            val cosmogEntries = data.entriesForSpecies("cobblemon:cosmog")
            val cosmoemEntries = data.entriesForSpecies("cobblemon:cosmoem")
            val currentCount = cosmogEntries.size + cosmoemEntries.size
            val owners = (cosmogEntries + cosmoemEntries)
                .map { it.ownerName }
                .filter { it.isNotBlank() }
                .distinct()

            return LimitStatus(
                speciesId = speciesId,
                displayName = displayName,
                tracked = true,
                currentCount = currentCount,
                effectiveLimit = effectiveLimit,
                baseLimit = baseLimit,
                owners = owners,
                note = "Grupo Cosmog/Cosmoem (base $baseLimit, -$reduction por Solgaleo:$solgaleoCount / Lunala:$lunalaCount)"
            )
        }

        if (explicitLimit != null) {
            if (explicitLimit < 0) {
                return LimitStatus(
                    speciesId = speciesId,
                    displayName = displayName,
                    tracked = false,
                    currentCount = 0,
                    effectiveLimit = Int.MAX_VALUE,
                    baseLimit = explicitLimit,
                    owners = emptyList(),
                    note = "Exento por configuración explícita ($explicitLimit)"
                )
            }

            val matched = data.entriesForSpecies(speciesId)
            val owners = matched.map { it.ownerName }.filter { it.isNotBlank() }.distinct()
            return LimitStatus(
                speciesId = speciesId,
                displayName = displayName,
                tracked = true,
                currentCount = matched.size,
                effectiveLimit = explicitLimit,
                baseLimit = explicitLimit,
                owners = owners,
                note = "Límite personalizado en config ($explicitLimit)"
            )
        }

        if (species != null && cfg.excludeUltraBeasts && LegendaryLimitsConfig.isUltraBeast(species)) {
            return LimitStatus(
                speciesId = speciesId,
                displayName = displayName,
                tracked = false,
                currentCount = 0,
                effectiveLimit = Int.MAX_VALUE,
                baseLimit = Int.MAX_VALUE,
                owners = emptyList(),
                note = "Ultraente excluido del límite"
            )
        }

        if (species != null && LegendaryLimitsConfig.isLegendaryOrMythical(species)) {
            val limit = cfg.defaultLegendaryLimit.coerceAtLeast(0)
            val matched = data.entriesForSpecies(speciesId)
            val owners = matched.map { it.ownerName }.filter { it.isNotBlank() }.distinct()
            return LimitStatus(
                speciesId = speciesId,
                displayName = displayName,
                tracked = true,
                currentCount = matched.size,
                effectiveLimit = limit,
                baseLimit = limit,
                owners = owners,
                note = "Límite legendario/mítico por defecto ($limit)"
            )
        }

        return LimitStatus(
            speciesId = speciesId,
            displayName = displayName,
            tracked = false,
            currentCount = 0,
            effectiveLimit = Int.MAX_VALUE,
            baseLimit = Int.MAX_VALUE,
            owners = emptyList(),
            note = "Sin límite de captura"
        )
    }
}
