package com.desgraciamon.discordbridge

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import java.io.File

object TextCatalog {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val defaults = linkedMapOf(
        "link.hover" to "Clic para copiar el código", "link.message" to "Código Discord: {code}. En Discord usa /cuenta vincular codigo:{code} dentro de los próximos 10 minutos.", "link.already_linked_mc" to "<color=#F87171>Tu cuenta de Minecraft ya está vinculada a Discord de forma permanente (1 a 1).</color>", "unlink.permanent" to "<color=#F87171>La vinculación con Discord es permanente (1 a 1). Si hubo un error, contacta a un administrador.</color>", "unlink.success" to "Tu Discord quedó desvinculado.", "unlink.missing" to "No tienes una cuenta de Discord vinculada.", "privacy.enabled" to "Tu equipo ahora es privado en Discord. Solo tú puedes consultarlo con /equipo.", "privacy.disabled" to "Tu equipo vuelve a ser visible en Discord.", "settings.title" to "Ajustes de Discord", "discord.server_unavailable" to "El servidor de Minecraft no está disponible ahora.", "discord.linked" to "Cuenta vinculada a {player}. Ya puedes usar /perfil, /equipo, /legendarios y /cooldown.", "discord.code_invalid" to "Código inválido o vencido. Genera otro con /discord vincular dentro de Minecraft.", "discord.already_linked" to "Esta cuenta de Discord ya está vinculada a otro jugador.", "discord.unlinked" to "Se desvinculó Discord de {player}.", "discord.not_linked" to "Tu cuenta de Discord no está vinculada.", "discord.player_not_linked" to "{player} no tiene una cuenta de Minecraft vinculada.", "discord.no_special_pokemon" to "**{player} (<@{discordId}>)** no tiene Pokémon legendarios o míticos en su equipo ni en su PC.", "discord.special_pokemon_line" to "• {shinyPrefix}**{species}** ({status}) — capturado: {date}", "discord.date_missing" to "fecha no registrada", "discord.special_pokemon_more" to "\n...y {count} más.", "discord.special_pokemon_list" to "**{player} (<@{discordId}>)** tiene **{count}** legendarios o míticos:\n{pokemon}{more}", "discord.team_private" to "El equipo de {player} es privado. Solo su dueño puede consultarlo.", "discord.team_empty" to "**{player} (<@{discordId}>)** no tiene Pokémon en su equipo.", "discord.team_summary" to "Equipo actual de **{player} (<@{discordId}>)** · {count}/6 Pokémon", "discord.cooldown_ready" to "**{player} (<@{discordId}>)** ya puede capturar otro legendario o mítico.", "discord.cooldown_wait" to "A **{player} (<@{discordId}>)** le quedan **{remaining} de cooldown**.\nTermina <t:{availableAt}:F> (<t:{availableAt}:R>).", "discord.unknown_command" to "Comando no reconocido.", "discord.command.link" to "Vincula tu cuenta de Discord con el código de Minecraft", "discord.command.link.code" to "Código generado por /discord vincular", "discord.command.unlink" to "Desvincula tu cuenta de Discord de Minecraft", "discord.command.special" to "Cuenta los legendarios y míticos actuales de un jugador", "discord.command.team" to "Muestra el equipo Pokémon actual de un jugador", "discord.command.cooldown" to "Consulta el cooldown legendario restante de un jugador", "discord.command.player" to "Jugador vinculado", "team.title" to "{shiny}{slot}. {name} · {levelLabel} {level}", "team.color.shiny" to "#E3B341", "team.color.normal" to "#399B73", "team.description" to "**Especie:** {species}{nickname}\n**Estado:** {status}\n**Género:** {gender}  ·  **Naturaleza:** {nature}{form}\n**PS:** {health}/{maxHealth}", "team.nickname" to "\n**Apodo:** {nickname}", "team.form" to "\n**Forma:** {form}", "team.gender.male" to "Masculino", "team.gender.female" to "Femenino", "team.genderless" to "Sin género", "team.state.shiny" to "Shiny", "team.state.normal" to "Normal", "team.level" to "Nv.", "team.stats" to "Stats", "team.evs" to "EVs ({current}/510)", "team.ivs" to "IVs", "team.moves" to "Movimientos", "team.no_moves" to "Sin movimientos", "team.stat.hp" to "PS", "team.stat.attack" to "Atq", "team.stat.defense" to "Def", "team.stat.special_attack" to "At. Esp.", "team.stat.special_defense" to "Def. Esp.", "team.stat.speed" to "Vel"
    )
    private val file: File by lazy {
        val dir = File("config/desgraciamon4_discord_bridge/.texts")
        dir.mkdirs()
        runCatching { java.nio.file.Files.setAttribute(dir.toPath(), "dos:hidden", true) }
        val f = File(dir, "texts.json")
        val legacy = File("config/desgraciamon4_discord_bridge/texts.json")
        if (!f.exists() && legacy.exists()) {
            runCatching { java.nio.file.Files.move(legacy.toPath(), f.toPath()) }
        }
        f
    }
    private var texts: Map<String, String> = defaults

    fun load() {
        runCatching {
            file.parentFile?.mkdirs()
            if (!file.exists()) file.writeText(gson.toJson(defaults))
            val type = object : TypeToken<Map<String, String>>() {}.type
            val overrides: Map<String, String> = file.reader().use { gson.fromJson(it, type) ?: emptyMap() }
            texts = defaults + overrides
            file.writeText(gson.toJson(texts))
        }.onFailure {
            texts = defaults
            DiscordBridgeMod.logger.error("Could not load text catalog from ${file.path}", it)
        }
    }

    fun reload(): Boolean {
        load()
        return true
    }
    fun component(key: String, vararg values: Pair<String, Any?>): MutableComponent {
        val template = texts[key] ?: defaults[key] ?: key
        return RichText.parse(template, values.associate { it.first to it.second })
    }

    fun string(key: String, vararg values: Pair<String, Any?>): String = component(key, *values).string

    fun color(key: String, fallback: Int): Int = (texts[key] ?: defaults[key])
        ?.removePrefix("#")
        ?.toIntOrNull(16)
        ?.takeIf { it in 0..0xFFFFFF }
        ?: fallback
}