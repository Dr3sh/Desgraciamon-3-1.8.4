package com.desgraciamon.showdownarena

import com.cobblemon.mod.common.api.abilities.Abilities
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.Natures
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.api.storage.party.NPCPartyStore
import com.cobblemon.mod.common.api.storage.party.PartyStore
import com.cobblemon.mod.common.entity.npc.NPCEntity
import com.cobblemon.mod.common.pokemon.Gender
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

object ArenaTeamFactory {

    private val COMPETITIVE_ITEMS = listOf(
        "leftovers", "life_orb", "choice_band", "choice_specs", "choice_scarf",
        "focus_sash", "heavy_duty_boots", "assault_vest", "rocky_helmet", "lum_berry",
        "sitrus_berry", "expert_belt", "eviolite", "air_balloon", "white_herb"
    )

    @Volatile
    private var cachedSpeciesMap: Map<String, Species>? = null

    @Volatile
    private var cachedSupportedMoves: Set<String>? = null

    @Volatile
    private var cachedSupportedAbilities: Set<String>? = null

    @Volatile
    private var cachedItemLookup: Map<String, ResourceLocation>? = null

    private fun getSpeciesMap(): Map<String, Species> {
        val current = cachedSpeciesMap
        if (current != null && current.isNotEmpty()) return current
        val built = PokemonSpecies.implemented.associateBy { normalize(it.showdownId()) }
        if (built.isNotEmpty()) cachedSpeciesMap = built
        return built
    }

    private fun getSupportedMoves(): Set<String> {
        val current = cachedSupportedMoves
        if (current != null && current.isNotEmpty()) return current
        val built = Moves.names().map(::normalize).toSet()
        if (built.isNotEmpty()) cachedSupportedMoves = built
        return built
    }

    private fun getSupportedAbilities(): Set<String> {
        val current = cachedSupportedAbilities
        if (current != null && current.isNotEmpty()) return current
        val built = Abilities.all().map { normalize(it.name) }.toSet()
        if (built.isNotEmpty()) cachedSupportedAbilities = built
        return built
    }

    private fun getItemLookup(): Map<String, ResourceLocation> {
        val current = cachedItemLookup
        if (current != null && current.isNotEmpty()) return current
        val built = mutableMapOf<String, ResourceLocation>()
        for (key in BuiltInRegistries.ITEM.keySet()) {
            val normPath = normalize(key.path)
            // Prefer cobblemon namespace if there is a collision
            if (!built.containsKey(normPath) || key.namespace == "cobblemon") {
                built[normPath] = key
            }
            built[normalize(key.toString())] = key
        }
        if (built.isNotEmpty()) cachedItemLookup = built
        return built
    }

    fun extractSpeciesIds(party: PartyStore): Set<String> {
        val ids = LinkedHashSet<String>()
        for (pkm in party) {
            ids.add(normalize(pkm.species.showdownId()))
        }
        return ids
    }

    fun createTeam(
        owner: UUID,
        format: ArenaFormat,
        level: Int,
        excludedSpecies: Set<String> = emptySet()
    ): PartyStore {
        val targetLevel = if (level in 1..100) level else (format.fixedLevel ?: 50)
        val teamSize = format.teamSize.coerceIn(if (format.battleType == ArenaBattleType.DOUBLES) 2 else 1, 6)

        val pokemonList = generatePokemonList(format, targetLevel, teamSize, excludedSpecies)

        val party = PartyStore(UUID.randomUUID())
        pokemonList.forEachIndexed { slot, pkm ->
            pkm.heal()
            party.set(slot, pkm)
        }
        return party
    }

    fun createNpcParty(
        npc: NPCEntity,
        format: ArenaFormat,
        level: Int,
        excludedSpecies: Set<String> = emptySet()
    ): NPCPartyStore {
        val targetLevel = if (level in 1..100) level else (format.fixedLevel ?: 50)
        val teamSize = format.teamSize.coerceIn(if (format.battleType == ArenaBattleType.DOUBLES) 2 else 1, 6)

        val pokemonList = generatePokemonList(format, targetLevel, teamSize, excludedSpecies)

        val party = NPCPartyStore(npc)
        pokemonList.forEachIndexed { slot, pkm ->
            pkm.heal()
            party.set(slot, pkm)
        }
        return party
    }

    private fun generatePokemonList(
        format: ArenaFormat,
        targetLevel: Int,
        teamSize: Int,
        excludedSpecies: Set<String>
    ): List<Pokemon> {
        return when (format.category) {
            FormatCategory.RANDOM_CHAOS -> generateChaosTeam(format.generation, targetLevel, teamSize, excludedSpecies)
            FormatCategory.RANDOM_COMPETITIVE -> {
                tryGenerateRandomBattleTeam(format, targetLevel, teamSize, excludedSpecies)
                    ?: generateCompetitiveFallbackTeam(format.generation, targetLevel, teamSize, FormatCategory.RANDOM_COMPETITIVE, excludedSpecies)
            }
            FormatCategory.OU, FormatCategory.UBERS, FormatCategory.VGC_DOUBLES, FormatCategory.MONOTYPE -> {
                tryGenerateCategoryTeamWithShowdownSets(format, targetLevel, teamSize, excludedSpecies)
                    ?: generateCompetitiveFallbackTeam(format.generation, targetLevel, teamSize, format.category, excludedSpecies)
            }
        }
    }

    /**
     * Genera un equipo de Random Battle usando Showdown (con semilla real aleatoria por equipo).
     */
    private fun tryGenerateRandomBattleTeam(
        format: ArenaFormat,
        level: Int,
        teamSize: Int,
        excludedSpecies: Set<String>
    ): List<Pokemon>? {
        return try {
            val speciesMap = getSpeciesMap()
            val supportedMoves = getSupportedMoves()
            val supportedAbilities = getSupportedAbilities()
            val itemLookup = getItemLookup()

            val sets = ShowdownEngineBridge.generateRandomBattleTeam(
                generation = format.generation,
                isDoubles = format.battleType == ArenaBattleType.DOUBLES,
                teamSize = teamSize,
                implementedSpecies = speciesMap.keys,
                implementedMoves = supportedMoves,
                implementedAbilities = supportedAbilities,
                implementedItems = itemLookup.keys,
                excludedSpecies = excludedSpecies
            )

            if (sets.isEmpty()) return null

            val built = sets.mapNotNull { set ->
                val pkmSpecies = speciesMap[normalize(set.species)] ?: return@mapNotNull null
                if (normalize(pkmSpecies.showdownId()) in excludedSpecies) return@mapNotNull null
                buildPokemonFromShowdownSet(pkmSpecies, set, format, level)
            }.take(teamSize).toMutableList()

            if (built.size < teamSize) {
                val existingIds = built.map { normalize(it.species.showdownId()) }.toSet() + excludedSpecies
                val extras = generateCompetitiveFallbackTeam(format.generation, level, teamSize - built.size, format.category, existingIds)
                built.addAll(extras)
            }

            built.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Selecciona las especies en Kotlin según las reglas de OU / Ubers / VGC / Monotype y la generación,
     * y luego le pide a Showdown un set competitivo (`randomSet`) para cada una en una sola llamada rápida (<5ms).
     */
    private fun tryGenerateCategoryTeamWithShowdownSets(
        format: ArenaFormat,
        level: Int,
        teamSize: Int,
        excludedSpecies: Set<String>
    ): List<Pokemon>? {
        return try {
            val chosenSpecies = selectSpeciesForCategory(format.generation, teamSize, format.category, excludedSpecies)
            if (chosenSpecies.isEmpty()) return null

            val speciesMap = getSpeciesMap()
            val supportedMoves = getSupportedMoves()
            val supportedAbilities = getSupportedAbilities()
            val itemLookup = getItemLookup()

            val showdownSets = runCatching {
                ShowdownEngineBridge.generateSetsForSpeciesList(
                    generation = format.generation,
                    speciesIds = chosenSpecies.map { normalize(it.showdownId()) },
                    isDoubles = format.battleType == ArenaBattleType.DOUBLES,
                    implementedSpecies = speciesMap.keys,
                    implementedMoves = supportedMoves,
                    implementedAbilities = supportedAbilities,
                    implementedItems = itemLookup.keys
                )
            }.getOrDefault(emptyList())

            val setsBySpecies = showdownSets.associateBy { normalize(it.species) }

            chosenSpecies.map { species ->
                val set = setsBySpecies[normalize(species.showdownId())]
                if (set != null) {
                    buildPokemonFromShowdownSet(species, set, format, level)
                } else {
                    buildFallbackCompetitivePokemon(species, format.generation, level)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildPokemonFromShowdownSet(
        pkmSpecies: Species,
        set: ShowdownPokemonSet,
        format: ArenaFormat,
        fallbackLevel: Int
    ): Pokemon {
        val pkmLevel = if (fallbackLevel in 1..100) fallbackLevel else (format.fixedLevel ?: set.level ?: 50)
        val pkm = PokemonProperties.parse("species=${pkmSpecies.resourceIdentifier} level=$pkmLevel").create()
        pkm.initialize()

        if (!set.forme.isNullOrBlank()) {
            val targetForm = normalize(set.forme)
            pkmSpecies.forms.firstOrNull { normalize(it.name) == targetForm || normalize(it.formOnlyShowdownId()) == targetForm }?.let { matchedForm ->
                pkm.form = matchedForm
                if (matchedForm.aspects.isNotEmpty()) {
                    pkm.forcedAspects = matchedForm.aspects.toSet()
                    pkm.updateAspects()
                }
            }
        }

        if (format.generation >= 3 || format.generation == 0) {
            set.ability?.takeIf { it.isNotBlank() }?.let { abName ->
                Abilities.get(abName)?.let { pkm.updateAbility(it.create()) }
            }
        }

        set.nature?.takeIf { it.isNotBlank() }?.let { natName ->
            Natures.getNature(natName)?.let { pkm.nature = it }
        }

        set.gender?.let { g ->
            pkm.gender = when (g.uppercase()) {
                "M" -> Gender.MALE
                "F" -> Gender.FEMALE
                else -> Gender.GENDERLESS
            }
        }

        set.shiny?.let { pkm.shiny = it }

        val movesToAdd = set.moves.mapNotNull { Moves.getByName(it) }
        if (movesToAdd.isNotEmpty()) {
            pkm.moveSet.clear()
            movesToAdd.take(4).forEach { pkm.moveSet.add(it.create()) }
        } else {
            assignFallbackMoves(pkm, pkmSpecies)
        }

        if (format.generation != 1) {
            val resolvedId = set.item?.takeIf { it.isNotBlank() }?.let { resolveItemId(it) }
                ?: resolveItemId(COMPETITIVE_ITEMS.random())
            if (resolvedId != null) {
                pkm.swapHeldItem(BuiltInRegistries.ITEM.get(resolvedId).defaultInstance)
            }
        }

        for (st in Stats.PERMANENT) {
            pkm.ivs[st] = 31
        }
        set.ivs.forEach { (statName, ivVal) ->
            val stat = Stats.getStat(statName)
            if (stat != null) pkm.ivs[stat] = ivVal.coerceIn(0, 31)
        }

        if (set.evs.isNotEmpty()) {
            set.evs.forEach { (statName, evVal) ->
                val stat = Stats.getStat(statName)
                if (stat != null) pkm.evs[stat] = evVal.coerceIn(0, 252)
            }
        } else {
            applyDefaultCompetitiveEvs(pkm, pkmSpecies)
        }

        pkm.heal()
        return pkm
    }

    /**
     * Generador Caos: Pokémon puramente aleatorios de la generación con movimientos y objetos al azar.
     */
    private fun generateChaosTeam(
        generation: Int,
        level: Int,
        teamSize: Int,
        excludedSpecies: Set<String> = emptySet()
    ): List<Pokemon> {
        val rawPool = getSpeciesForGeneration(generation)
        val filtered = rawPool.filter { normalize(it.showdownId()) !in excludedSpecies }
        val pool = if (filtered.size >= teamSize) filtered else rawPool
        val selected = shuffleWithThreadRandom(pool).take(teamSize)

        return selected.map { species ->
            val pkm = PokemonProperties.parse("species=${species.resourceIdentifier} level=$level").create()
            pkm.initialize()

            val availableMoves = runCatching { species.moves.getAllLegalMoves().toList() }.getOrNull() ?: emptyList()
            if (availableMoves.size >= 2) {
                pkm.moveSet.clear()
                shuffleWithThreadRandom(availableMoves).take(4).forEach { pkm.moveSet.add(it.create()) }
            }

            if (generation != 1) {
                val itemKey = COMPETITIVE_ITEMS[ThreadLocalRandom.current().nextInt(COMPETITIVE_ITEMS.size)]
                resolveItemId(itemKey)?.let {
                    val item = BuiltInRegistries.ITEM.get(it)
                    pkm.swapHeldItem(item.defaultInstance)
                }
            }

            pkm.heal()
            pkm
        }
    }

    private fun selectSpeciesForCategory(
        generation: Int,
        teamSize: Int,
        category: FormatCategory,
        excludedSpecies: Set<String> = emptySet()
    ): List<Species> {
        val genPool = getSpeciesForGeneration(generation)
        var pool = genPool

        when (category) {
            FormatCategory.UBERS -> {
                val ubersAndLegends = genPool.filter { sp ->
                    val labels = sp.labels.map { it.lowercase() }
                    labels.contains("legendary") || labels.contains("mythical") || labels.contains("restricted") ||
                        labels.contains("ultra_beast") || labels.contains("paradox") || sp.baseStats.values.sum() >= 580
                }
                val nonExcludedUbers = ubersAndLegends.filter { normalize(it.showdownId()) !in excludedSpecies }
                pool = when {
                    nonExcludedUbers.size >= teamSize -> nonExcludedUbers
                    ubersAndLegends.size >= teamSize * 2 -> ubersAndLegends
                    else -> {
                        // Small gens (e.g. Gen 1 with 5 legendaries): supplement with top-BST fully evolved powerhouses
                        val strong = genPool
                            .filter { it.evolutions.isEmpty() && it.baseStats.values.sum() >= 515 }
                            .sortedByDescending { it.baseStats.values.sum() }
                        (ubersAndLegends + strong).distinctBy { it.resourceIdentifier }
                    }
                }
            }
            FormatCategory.OU -> {
                val ouFiltered = genPool.filter { sp ->
                    val labels = sp.labels.map { it.lowercase() }
                    val isBannedLegend = labels.contains("restricted") ||
                        (labels.contains("legendary") && sp.baseStats.values.sum() >= 600)
                    val isFullyEvolvedOrStrong = sp.evolutions.isEmpty() || sp.baseStats.values.sum() >= 450
                    !isBannedLegend && isFullyEvolvedOrStrong
                }
                if (ouFiltered.size >= teamSize) {
                    pool = ouFiltered
                }
            }
            FormatCategory.VGC_DOUBLES -> {
                val vgcFiltered = genPool.filter { sp ->
                    val labels = sp.labels.map { it.lowercase() }
                    !labels.contains("mythical") && (sp.evolutions.isEmpty() || sp.baseStats.values.sum() >= 440)
                }
                if (vgcFiltered.size >= teamSize) {
                    pool = vgcFiltered
                }
            }
            FormatCategory.MONOTYPE -> {
                val byType = mutableMapOf<String, MutableList<Species>>()
                for (sp in genPool) {
                    val t1 = sp.primaryType.name.lowercase()
                    byType.getOrPut(t1) { mutableListOf() }.add(sp)
                    sp.secondaryType?.name?.lowercase()?.let { t2 ->
                        if (t2 != t1) byType.getOrPut(t2) { mutableListOf() }.add(sp)
                    }
                }
                val cleanTypePools = byType.values
                    .map { list -> list.filter { normalize(it.showdownId()) !in excludedSpecies } }
                    .filter { it.size >= teamSize }
                val fallbackTypePools = byType.values.filter { it.size >= teamSize }
                pool = when {
                    cleanTypePools.isNotEmpty() -> cleanTypePools[ThreadLocalRandom.current().nextInt(cleanTypePools.size)]
                    fallbackTypePools.isNotEmpty() -> fallbackTypePools[ThreadLocalRandom.current().nextInt(fallbackTypePools.size)]
                    else -> genPool
                }
            }
            else -> {
                val evolved = genPool.filter { it.evolutions.isEmpty() || it.baseStats.values.sum() >= 430 }
                if (evolved.size >= teamSize) {
                    pool = evolved
                }
            }
        }

        val distinctFromOpponent = pool.filter { normalize(it.showdownId()) !in excludedSpecies }
        if (distinctFromOpponent.size >= teamSize) {
            return shuffleWithThreadRandom(distinctFromOpponent).take(teamSize)
        }

        // Maximize distinct species first, then fill only if the pool is strictly smaller than teamSize
        val picked = shuffleWithThreadRandom(distinctFromOpponent).toMutableList()
        if (picked.size < teamSize) {
            val alreadyPicked = picked.map { it.resourceIdentifier }.toSet()
            val remainder = shuffleWithThreadRandom(pool.filter { it.resourceIdentifier !in alreadyPicked })
            picked.addAll(remainder.take(teamSize - picked.size))
        }
        return picked.take(teamSize)
    }

    /**
     * Generador Competitivo Nativo de Respaldo (100% libre de fallos).
     */
    private fun generateCompetitiveFallbackTeam(
        generation: Int,
        level: Int,
        teamSize: Int,
        category: FormatCategory,
        excludedSpecies: Set<String> = emptySet()
    ): List<Pokemon> {
        val chosenSpecies = selectSpeciesForCategory(generation, teamSize, category, excludedSpecies)
        return chosenSpecies.map { species ->
            buildFallbackCompetitivePokemon(species, generation, level)
        }
    }

    private fun buildFallbackCompetitivePokemon(species: Species, generation: Int, level: Int): Pokemon {
        val pkm = PokemonProperties.parse("species=${species.resourceIdentifier} level=$level").create()
        pkm.initialize()

        assignFallbackMoves(pkm, species)

        for (st in Stats.PERMANENT) {
            pkm.ivs[st] = 31
        }
        applyDefaultCompetitiveEvs(pkm, species)

        if (generation != 1) {
            val itemKey = COMPETITIVE_ITEMS[ThreadLocalRandom.current().nextInt(COMPETITIVE_ITEMS.size)]
            resolveItemId(itemKey)?.let {
                val item = BuiltInRegistries.ITEM.get(it)
                pkm.swapHeldItem(item.defaultInstance)
            }
        }

        pkm.heal()
        return pkm
    }

    private fun assignFallbackMoves(pkm: Pokemon, species: Species) {
        val availableMoves = runCatching { species.moves.getAllLegalMoves().toList() }.getOrNull().orEmpty()
        if (availableMoves.size >= 2) {
            val strongMoves = availableMoves.filter { it.power >= 60 || it.power == 0.0 }
            val source = if (strongMoves.size >= 4) strongMoves else availableMoves
            pkm.moveSet.clear()
            shuffleWithThreadRandom(source).take(4).forEach { pkm.moveSet.add(it.create()) }
        }
    }

    private fun applyDefaultCompetitiveEvs(pkm: Pokemon, species: Species) {
        val atk = species.baseStats[Stats.ATTACK] ?: 80
        val spa = species.baseStats[Stats.SPECIAL_ATTACK] ?: 80
        val spe = species.baseStats[Stats.SPEED] ?: 80

        if (atk >= spa) {
            pkm.evs[Stats.ATTACK] = 252
        } else {
            pkm.evs[Stats.SPECIAL_ATTACK] = 252
        }

        if (spe >= 75) {
            pkm.evs[Stats.SPEED] = 252
            pkm.evs[Stats.HP] = 4
        } else {
            pkm.evs[Stats.HP] = 252
            pkm.evs[Stats.DEFENCE] = 4
        }
    }

    private fun <T> shuffleWithThreadRandom(list: List<T>): List<T> {
        val copy = list.toMutableList()
        java.util.Collections.shuffle(copy, ThreadLocalRandom.current())
        return copy
    }

    private fun getSpeciesForGeneration(generation: Int): List<Species> {
        val implemented = PokemonSpecies.implemented.toList()
        if (generation !in 1..9) return implemented

        val genPool = implemented.filter { getGeneration(it) == generation }
        return if (genPool.size >= 6) genPool else implemented
    }

    private fun getGeneration(species: Species): Int = when (species.nationalPokedexNumber) {
        in 1..151 -> 1
        in 152..251 -> 2
        in 252..386 -> 3
        in 387..493 -> 4
        in 494..649 -> 5
        in 650..721 -> 6
        in 722..809 -> 7
        in 810..905 -> 8
        in 906..1025 -> 9
        else -> 0
    }

    private fun resolveItemId(raw: String): ResourceLocation? {
        return getItemLookup()[normalize(raw)]
    }

    private fun normalize(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)
}
