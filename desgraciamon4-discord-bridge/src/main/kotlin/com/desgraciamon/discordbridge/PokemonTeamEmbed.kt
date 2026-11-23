package com.desgraciamon.discordbridge

import com.cobblemon.mod.common.api.pokemon.stats.Stat
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.Pokemon
import net.dv8tion.jda.api.EmbedBuilder

private data class TeamStatLabel(val shortName: String, val stat: Stat)

private val teamStatLabels = listOf(
    TeamStatLabel("team.stat.hp", Stats.HP),
    TeamStatLabel("team.stat.attack", Stats.ATTACK),
    TeamStatLabel("team.stat.defense", Stats.DEFENCE),
    TeamStatLabel("team.stat.special_attack", Stats.SPECIAL_ATTACK),
    TeamStatLabel("team.stat.special_defense", Stats.SPECIAL_DEFENCE),
    TeamStatLabel("team.stat.speed", Stats.SPEED)
)

internal fun pokemonTeamEmbed(slot: Int, pokemon: Pokemon) = EmbedBuilder().apply {
    val shinyMarker = if (pokemon.shiny) "" else ""
    val nickname = pokemon.nickname?.string?.takeIf(String::isNotBlank)
    val displayName = nickname ?: pokemon.species.translatedName.string
    val form = pokemon.form.takeIf { it != pokemon.species.standardForm }
    val formName = form?.name
    val gender = TextCatalog.string(when (pokemon.gender) {
        com.cobblemon.mod.common.pokemon.Gender.MALE -> "team.gender.male"
        com.cobblemon.mod.common.pokemon.Gender.FEMALE -> "team.gender.female"
        com.cobblemon.mod.common.pokemon.Gender.GENDERLESS -> "team.genderless"
    })
    val nature = pokemon.effectiveNature.displayName
    val spriteId = buildString {
        append(pokemon.species.showdownId())
        form?.formOnlyShowdownId()?.takeIf(String::isNotBlank)?.let { append("-").append(it) }
    }
    val spriteDirectory = if (pokemon.shiny) "ani-shiny" else "ani"
    val statLine = teamStatLabels.joinToString(" · ") { "${TextCatalog.string(it.shortName)} ${pokemon.getStat(it.stat)}" }
    val evLine = teamStatLabels.joinToString(" · ") { "${TextCatalog.string(it.shortName)} ${pokemon.evs.getOrDefault(it.stat)}" }
    val ivLine = teamStatLabels.joinToString(" · ") {
        val value = pokemon.ivs.getOrDefault(it.stat)
        "${TextCatalog.string(it.shortName)} $value${if (pokemon.ivs.isHyperTrained(it.stat)) " (HT)" else ""}"
    }

    setColor(TextCatalog.color(if (pokemon.shiny) "team.color.shiny" else "team.color.normal", if (pokemon.shiny) 0xE3B341 else 0x399B73))
    setTitle(TextCatalog.string("team.title", "shiny" to shinyMarker, "slot" to slot, "name" to displayName, "levelLabel" to TextCatalog.string("team.level"), "level" to pokemon.level))
    setDescription(TextCatalog.string(
        "team.description", "species" to pokemon.species.translatedName.string,
        "nickname" to nickname?.let { TextCatalog.string("team.nickname", "nickname" to it) }.orEmpty(),
        "status" to TextCatalog.string(if (pokemon.shiny) "team.state.shiny" else "team.state.normal"),
        "gender" to gender,
        "nature" to nature,
        "form" to formName?.let { TextCatalog.string("team.form", "form" to it) }.orEmpty(),
        "health" to pokemon.currentHealth,
        "maxHealth" to pokemon.maxHealth
    ))
    val moves = pokemon.moveSet.getMoves().take(4).joinToString("\n") { move ->
        "• ${move.displayName.string}"
    }.ifBlank { TextCatalog.string("team.no_moves") }
    addField(TextCatalog.string("team.stats"), "`$statLine`", false)
    addField(TextCatalog.string("team.evs", "current" to pokemon.evs.total()), "`$evLine`", false)
    addField(TextCatalog.string("team.ivs"), "`$ivLine`", false)
    addField(TextCatalog.string("team.moves"), moves, false)
    setImage("https://play.pokemonshowdown.com/sprites/$spriteDirectory/$spriteId.gif")
}.build()