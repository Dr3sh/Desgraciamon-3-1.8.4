package com.desgraciamon.announcements

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.desgraciamon.badges.GymBadges
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

object CleanMissionDisplay {

    private val POKEMON_TYPES = mapOf(
        "normal" to "Normal",
        "fire" to "Fuego",
        "water" to "Agua",
        "grass" to "Planta",
        "electric" to "Eléctrico",
        "ice" to "Hielo",
        "fighting" to "Lucha",
        "poison" to "Veneno",
        "ground" to "Tierra",
        "flying" to "Volador",
        "psychic" to "Psíquico",
        "bug" to "Bicho",
        "rock" to "Roca",
        "ghost" to "Fantasma",
        "dragon" to "Dragón",
        "dark" to "Siniestro",
        "steel" to "Acero",
        "fairy" to "Hada"
    )

    private val NATURES = mapOf(
        "hardy" to "Fuerte", "lonely" to "Huraña", "brave" to "Audaz", "adamant" to "Firme", "naughty" to "Pícara",
        "bold" to "Osada", "docile" to "Dócil", "relaxed" to "Plácida", "impish" to "Agitada", "lax" to "Floja",
        "timid" to "Miedosa", "hasty" to "Activa", "serious" to "Seria", "jolly" to "Alegre", "naive" to "Ingenua",
        "modest" to "Modesta", "mild" to "Afable", "quiet" to "Mansa", "bashful" to "Tímida", "rash" to "Alocada",
        "calm" to "Serena", "gentle" to "Amable", "sassy" to "Grosera", "careful" to "Cauta", "quirky" to "Rara"
    )

    fun typeName(rawType: String): String {
        val clean = rawType.trim().lowercase().removePrefix("cobblemon:").removePrefix("type.")
        if (clean.isBlank()) return "Cualquiera"
        return POKEMON_TYPES[clean] ?: clean.replaceFirstChar(Char::uppercase)
    }

    fun typePairName(primaryType: String, secondaryType: String = ""): String {
        val p = primaryType.trim()
        val s = secondaryType.trim()
        return when {
            p.isNotBlank() && s.isNotBlank() && !p.equals(s, ignoreCase = true) ->
                "${typeName(p)} / ${typeName(s)}"
            p.isNotBlank() -> typeName(p)
            s.isNotBlank() -> typeName(s)
            else -> "Cualquiera"
        }
    }

    fun badgeName(rawBadgeId: String): String {
        val clean = rawBadgeId.trim()
        val found = GymBadges.ALL_GYM_BADGES.firstOrNull { it.id.equals(clean, ignoreCase = true) }
        return found?.name ?: clean.substringAfter(':').replace('_', ' ').replaceFirstChar(Char::uppercase)
    }

    fun pokemonName(rawSpeciesId: String): String {
        val clean = rawSpeciesId.trim().lowercase()
        if (clean.isBlank()) return "Cualquier Pokémon"
        return runCatching {
            val identifier = if (':' in clean) ResourceLocation.parse(clean) else ResourceLocation.fromNamespaceAndPath("cobblemon", clean)
            PokemonSpecies.getByIdentifier(identifier)?.translatedName?.string
        }.getOrNull()?.takeIf(String::isNotBlank) ?: clean.substringAfter(':').replace('-', ' ').replace('_', ' ').replaceFirstChar(Char::uppercase)
    }

    fun itemDisplayName(rawItemId: String): String {
        val clean = rawItemId.trim()
        if (clean.isBlank()) return "Objeto"
        return runCatching {
            val resource = if (':' in clean) ResourceLocation.parse(clean) else ResourceLocation.withDefaultNamespace(clean)
            val item = BuiltInRegistries.ITEM.getOptional(resource).orElse(null) ?: return@runCatching null
            ItemStack(item).hoverName.string
        }.getOrNull()?.takeIf(String::isNotBlank) ?: clean.substringAfter(':').replace('_', ' ').replaceFirstChar(Char::uppercase)
    }

    fun consumeLabel(consumeOnClaim: Boolean): String =
        if (consumeOnClaim) "Entrega" else ""

    fun consumeDetailLabel(consumeOnClaim: Boolean): String =
        if (consumeOnClaim) "Entrega objetos al reclamar" else ""

    fun categoryTagLabel(mission: Mission): String = mission.resolvedCategoryTag()

    /**
     * Limpia cadenas crudas como "shiny=yes gender=female level=50 nature=adamant"
     * convirtiendolas en texto amigable y en espanol: "Shiny - Hembra - Niv. 50 - Naturaleza: Firme".
     */
    fun cleanProperties(rawProps: String): String {
        if (rawProps.isBlank()) return ""
        val parts = rawProps.trim().split("\\s+".toRegex())
        val readableList = mutableListOf<String>()

        for (part in parts) {
            val token = part.trim()
            if (token.isBlank()) continue
            val lower = token.lowercase()

            if (lower == "-" || lower == "|" || lower == "s/" || lower == "n/" ||
                lower == "\u2022" || lower == "\u00B7") continue
            if (lower.startsWith("shiny") || lower == "variocolor") {
                if (!lower.contains("no") && !lower.contains("false") && !lower.contains("ninguno")) {
                    if ("Shiny" !in readableList) readableList.add("Shiny")
                }
                continue
            }
            if (lower == "sí" || lower == "si") {
                continue
            }

            when {
                lower == "gender=male" || lower == "male" -> readableList.add("Macho")
                lower == "gender=female" || lower == "female" -> readableList.add("Hembra")
                lower == "gender=genderless" -> readableList.add("Sin género")
                lower.startsWith("level=") -> readableList.add("Niv. ${lower.substringAfter("level=")}")
                lower.startsWith("nature=") -> {
                    val natKey = lower.substringAfter("nature=")
                    readableList.add("Naturaleza: ${NATURES[natKey] ?: natKey.replaceFirstChar(Char::uppercase)}")
                }
                lower.startsWith("ability=") -> {
                    val abKey = lower.substringAfter("ability=").replace('_', ' ').replaceFirstChar(Char::uppercase)
                    readableList.add("Habilidad: $abKey")
                }
                lower.startsWith("form=") -> {
                    val formKey = lower.substringAfter("form=").replace('_', ' ').replaceFirstChar(Char::uppercase)
                    readableList.add("Forma: $formKey")
                }
                lower.startsWith("ivs=") -> readableList.add("IVs: ${token.substringAfter("ivs=")}")
                lower.startsWith("evs=") -> readableList.add("EVs: ${token.substringAfter("evs=")}")
                else -> {
                    val cleanVal = token.replace("=", ": ").replace('_', ' ')
                    if (cleanVal.isNotBlank() && cleanVal !in readableList) {
                        readableList.add(cleanVal)
                    }
                }
            }
        }
        return readableList.joinToString(" - ")
    }

    fun describeConstraints(objective: MissionObjective): List<String> {
        val parts = mutableListOf<String>()
        if (objective.minLevel > 0) {
            parts.add("Nivel min. ${objective.minLevel}")
        }
        if (objective.minEvolutionStage > 0) {
            parts.add("Etapa evolutiva min. ${objective.minEvolutionStage}")
        }
        val extraProps = cleanProperties(objective.pokemonProperties)
        if (extraProps.isNotBlank()) {
            parts.add(extraProps)
        }
        if (objective.type == ObjectiveType.GET_ITEM && objective.consumeOnClaim) {
            parts.add("Entrega")
        }
        return parts
    }

    /**
     * Descripcion completa y limpia de un objetivo para la vista detallada.
     */
    fun describeObjective(objective: MissionObjective, isGuild: Boolean = false, memberCount: Int = 1): String {
        val effectiveAmount = if (objective.targetUsesMemberCount && isGuild) memberCount.coerceAtLeast(1) else objective.amount
        val constraints = describeConstraints(objective)
        val suffix = if (constraints.isNotEmpty()) " (${constraints.joinToString(" - ")})" else ""
        val countPrefix = if (effectiveAmount > 1) "$effectiveAmount x " else ""

        val mainDesc = when (objective.type) {
            ObjectiveType.GET_ITEM -> {
                val itemName = itemDisplayName(objective.itemId)
                "Obtener ${if (effectiveAmount > 1) "$effectiveAmount x " else ""}$itemName$suffix"
            }
            ObjectiveType.DEFEAT_POKEMON -> {
                val targetDesc = when {
                    objective.pokemonId.isNotBlank() -> "$countPrefix${pokemonName(objective.pokemonId)}"
                    objective.pokemonType.isNotBlank() || objective.secondaryPokemonType.isNotBlank() ->
                        "${countPrefix}Pokémon de tipo ${typePairName(objective.pokemonType, objective.secondaryPokemonType)}"
                    else -> "${countPrefix}Pokémon"
                }
                "Derrotar $targetDesc$suffix"
            }
            ObjectiveType.CATCH_TYPE -> {
                val types = typePairName(objective.pokemonType, objective.secondaryPokemonType)
                "Capturar ${countPrefix}Pokémon de tipo $types$suffix"
            }
            ObjectiveType.CATCH_SPECIES -> {
                val pName = if (objective.pokemonId.isNotBlank()) pokemonName(objective.pokemonId) else "Pokémon"
                "Capturar $countPrefix$pName$suffix"
            }
            ObjectiveType.OBTAIN_BADGE -> {
                val bName = badgeName(objective.badgeId)
                if (isGuild && objective.targetUsesMemberCount) {
                    "Obtener la medalla $bName (x$memberCount)"
                } else {
                    "Obtener la medalla $bName"
                }
            }
            ObjectiveType.EVOLVE_SPECIES -> {
                val pName = if (objective.pokemonId.isNotBlank()) pokemonName(objective.pokemonId) else "Pokémon"
                "Evolucionar $countPrefix$pName$suffix"
            }
            ObjectiveType.WIN_PVP -> "Ganar ${countPrefix}combate(s) PvP$suffix"
            ObjectiveType.WIN_SHOWDOWN -> "Ganar ${countPrefix}combate(s) en Showdown$suffix"
            ObjectiveType.WIN_PVP_OR_SHOWDOWN -> "Ganar ${countPrefix}combate(s) PvP o Showdown$suffix"
            ObjectiveType.DEFEAT_RCT_NPC -> {
                val npc = if (objective.pokemonId.isNotBlank()) " (${objective.pokemonId})" else ""
                "Derrotar ${countPrefix}entrenador(es) NPC$npc$suffix"
            }
            ObjectiveType.CRAFT_ITEM -> {
                val itemName = itemDisplayName(objective.itemId)
                "Fabricar $countPrefix$itemName$suffix"
            }
            ObjectiveType.EARN_MONEY -> "Ganar \$${formatMoneyCount(effectiveAmount)} CobbleDollars$suffix"
            ObjectiveType.SPEND_MONEY -> "Gastar \$${formatMoneyCount(effectiveAmount)} CobbleDollars$suffix"
        }

        val guildPrefix = if (isGuild) "[Gremio] " else ""
        return "$guildPrefix$mainDesc"
    }

    /**
     * Resumen simple en una linea para tarjetas diarias y listado de misiones.
     * Ejemplo: "Captura 10 Zubat 0/10" o "Consigue 16 Bonguri Rojo 4/16".
     */
    fun simpleObjectiveSummary(
        objective: MissionObjective,
        progress: Int,
        targetAmount: Int,
        isGuild: Boolean = false
    ): String {
        val safeTarget = targetAmount.coerceAtLeast(1)
        val clamped = progress.coerceIn(0, safeTarget)
        val levelHint = if (objective.minLevel > 0) " Nv.${objective.minLevel}+" else ""
        val evoHint = if (objective.minEvolutionStage > 0) " Etapa ${objective.minEvolutionStage}+" else ""
        val extraHint = buildString {
            append(levelHint)
            append(evoHint)
            val props = cleanProperties(objective.pokemonProperties)
            if (props.isNotBlank()) append(" ($props)")
        }

        val actionText = when (objective.type) {
            ObjectiveType.GET_ITEM -> {
                val itemName = itemDisplayName(objective.itemId)
                val mode = if (objective.consumeOnClaim) "Entrega" else "Consigue"
                "$mode $safeTarget $itemName"
            }
            ObjectiveType.CATCH_SPECIES -> {
                val pName = if (objective.pokemonId.isNotBlank()) pokemonName(objective.pokemonId) else "Pokémon"
                "Captura $safeTarget $pName$extraHint"
            }
            ObjectiveType.CATCH_TYPE -> {
                val types = typePairName(objective.pokemonType, objective.secondaryPokemonType)
                "Captura $safeTarget tipo $types$extraHint"
            }
            ObjectiveType.DEFEAT_POKEMON -> {
                val targetDesc = when {
                    objective.pokemonId.isNotBlank() -> pokemonName(objective.pokemonId)
                    objective.pokemonType.isNotBlank() || objective.secondaryPokemonType.isNotBlank() ->
                        "tipo ${typePairName(objective.pokemonType, objective.secondaryPokemonType)}"
                    else -> "Pokémon"
                }
                "Derrota $safeTarget $targetDesc$extraHint"
            }
            ObjectiveType.OBTAIN_BADGE -> {
                val bName = badgeName(objective.badgeId)
                if (isGuild && objective.targetUsesMemberCount) "Medalla $bName (x$safeTarget)" else "Medalla $bName"
            }
            ObjectiveType.EVOLVE_SPECIES -> {
                val pName = if (objective.pokemonId.isNotBlank()) pokemonName(objective.pokemonId) else "Pokémon"
                "Evoluciona $safeTarget $pName"
            }
            ObjectiveType.WIN_PVP -> "Gana $safeTarget PvP"
            ObjectiveType.WIN_SHOWDOWN -> "Gana $safeTarget Showdown"
            ObjectiveType.WIN_PVP_OR_SHOWDOWN -> "Gana $safeTarget PvP/Showdown"
            ObjectiveType.DEFEAT_RCT_NPC -> {
                val npc = if (objective.pokemonId.isNotBlank()) " ${objective.pokemonId}" else " NPC"
                "Vence $safeTarget$npc"
            }
            ObjectiveType.CRAFT_ITEM -> {
                val itemName = itemDisplayName(objective.itemId)
                "Fabrica $safeTarget $itemName"
            }
            ObjectiveType.EARN_MONEY -> "Gana \$${formatMoneyCount(safeTarget)}"
            ObjectiveType.SPEND_MONEY -> "Gasta \$${formatMoneyCount(safeTarget)}"
        }
        return "$actionText $clamped/$safeTarget"
    }

    private fun formatMoneyCount(amount: Int): String =
        java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(amount)

    fun simpleMissionSummary(
        mission: Mission,
        objectiveProgress: Map<String, Int>,
        fallbackProgress: Int,
        memberCount: Int = 1
    ): String {
        val objectives = mission.objectives.filter { it.amount > 0 }.ifEmpty {
            listOf(
                MissionObjective(
                    id = "legacy",
                    type = mission.objective,
                    amount = mission.amount,
                    targetUsesMemberCount = mission.targetUsesMemberCount,
                    itemId = mission.itemId,
                    pokemonId = mission.pokemonId,
                    pokemonType = mission.pokemonType,
                    badgeId = mission.badgeId
                )
            )
        }
        val effectiveMembers = memberCount.coerceAtLeast(1)
        if (objectives.size == 1) {
            val obj = objectives.first()
            val targetAmt = if (obj.targetUsesMemberCount && mission.isGuildMission) effectiveMembers else obj.amount
            val prog = objectiveProgress[obj.id] ?: if (obj.id == "legacy") fallbackProgress else 0
            return simpleObjectiveSummary(obj, prog, targetAmt, mission.isGuildMission)
        }
        val first = objectives.first()
        val firstTarget = if (first.targetUsesMemberCount && mission.isGuildMission) effectiveMembers else first.amount
        val firstProg = objectiveProgress[first.id] ?: 0
        val totalTarget = objectives.sumOf { if (it.targetUsesMemberCount && mission.isGuildMission) effectiveMembers else it.amount }.coerceAtLeast(1)
        val totalProg = objectives.sumOf { obj ->
            val t = if (obj.targetUsesMemberCount && mission.isGuildMission) effectiveMembers else obj.amount
            (objectiveProgress[obj.id] ?: 0).coerceAtMost(t)
        }.coerceIn(0, totalTarget)
        val baseSummary = simpleObjectiveSummary(first, firstProg, firstTarget, mission.isGuildMission)
            .substringBeforeLast(" ")
        return "$baseSummary (+${objectives.size - 1} mas) $totalProg/$totalTarget"
    }

    /**
     * Descripcion limpia de recompensas de Pokemon.
     */
    fun describePokemonReward(reward: PokemonMissionReward): String {
        val name = pokemonName(reward.speciesId)
        val props = cleanProperties(reward.properties)
        return if (props.isNotBlank()) "$name ($props)" else name
    }
}
