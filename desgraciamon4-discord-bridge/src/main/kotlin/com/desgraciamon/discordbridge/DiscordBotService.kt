package com.desgraciamon.discordbridge

import com.cobblemon.mod.common.Cobblemon
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.session.ShutdownEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.requests.GatewayIntent
import java.util.UUID

object DiscordBotService {
    @Volatile
    private var jda: JDA? = null
    @Volatile
    private var isFallback = false

    fun start(config: DiscordBridgeConfig) {
        if (config.botToken.isBlank()) return
        if (jda != null) return
        isFallback = false
        startInternal(config, withMessageContent = true)
    }

    private fun startInternal(config: DiscordBridgeConfig, withMessageContent: Boolean) {
        runCatching {
            val builder = JDABuilder.createDefault(config.botToken)
                .addEventListeners(DiscordBotListener(config))
                .addEventListeners(object : ListenerAdapter() {
                    override fun onShutdown(event: ShutdownEvent) {
                        val code = event.closeCode?.code ?: 0
                        if (code == 4014 && withMessageContent && !isFallback) {
                            isFallback = true
                            DiscordBridgeMod.logger.warn("Discord rechazó el intent MESSAGE_CONTENT (código 4014). El bot se iniciará automáticamente en modo seguro. Para que el bot lea mensajes en el canal sin mencionarlo, activa 'Message Content Intent' en https://discord.com/developers/applications (sección Bot -> Privileged Gateway Intents).")
                            startInternal(config, withMessageContent = false)
                        }
                    }
                })

            if (withMessageContent) {
                builder.enableIntents(
                    GatewayIntent.MESSAGE_CONTENT,
                    GatewayIntent.GUILD_MESSAGES
                )
            } else {
                builder.enableIntents(GatewayIntent.GUILD_MESSAGES)
            }

            jda = builder.build()
        }.onFailure {
            DiscordBridgeMod.logger.error("Could not start Discord bot", it)
        }
    }

    fun stop() {
        jda?.shutdownNow()
        jda = null
    }
}

internal fun slashCommands() = listOf(
    Commands.slash("cuenta", "Vincula o desvincula tu cuenta de Minecraft con Discord")
        .addSubcommands(
            SubcommandData("vincular", TextCatalog.string("discord.command.link"))
                .addOptions(OptionData(OptionType.STRING, "codigo", TextCatalog.string("discord.command.link.code"), true)),
            SubcommandData("desvincular", TextCatalog.string("discord.command.unlink"))
        ),

    Commands.slash("perfil", "Muestra tus estadísticas de entrenador, Pokémon, medallas y economía")
        .addOptions(
            OptionData(OptionType.USER, "jugador", "Consultar el perfil de otro entrenador vinculado", false),
            OptionData(OptionType.BOOLEAN, "publico", "Mostrar el perfil en el canal (por defecto solo tú lo ves)", false)
        ),

    Commands.slash("equipo", TextCatalog.string("discord.command.team"))
        .addOptions(
            OptionData(OptionType.USER, "jugador", TextCatalog.string("discord.command.player"), false)
        ),

    Commands.slash("legendarios", TextCatalog.string("discord.command.special"))
        .addOptions(
            OptionData(OptionType.USER, "jugador", TextCatalog.string("discord.command.player"), false),
            OptionData(OptionType.BOOLEAN, "publico", "Mostrar la lista en el canal (por defecto solo tú lo ves)", false)
        ),

    Commands.slash("cooldown", TextCatalog.string("discord.command.cooldown"))
        .addOptions(
            OptionData(OptionType.USER, "jugador", TextCatalog.string("discord.command.player"), false)
        ),

    Commands.slash("skin", "Convierte una skin o NameMC a enlace permanente de Mojang")
        .addOptions(
            OptionData(OptionType.ATTACHMENT, "archivo", "Archivo PNG de la skin", false),
            OptionData(OptionType.STRING, "url", "Enlace de NameMC, URL de imagen o usuario de Minecraft", false)
        ),

    Commands.slash("hitos", "Consulta o gestiona el cronograma de hitos de Desgraciamon")
        .addSubcommands(
            SubcommandData("ver", "Muestra el cronograma actual de hitos y fechas"),
            SubcommandData("panel", "Abre el panel interactivo para administrar hitos, eventos y fechas"),
            SubcommandData("fecha", "Abre el selector interactivo de fecha para configurar el Día 1"),
            SubcommandData("dia", "Fija el día actual manualmente (usa 0 para modo automático)")
                .addOptions(OptionData(OptionType.INTEGER, "numero", "Número de día (0 = automático desde Día 1)", true))
        ),

    Commands.slash("admin", "Configuración del bot de Desgraciamon")
        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
        .addSubcommands(
            SubcommandData("canal-skins", "Configura el canal exclusivo para convertir skins automáticamente")
                .addOptions(OptionData(OptionType.CHANNEL, "canal", "Canal de Discord para skins", true)),
            SubcommandData("rol-hitos", "Configura el rol autorizado para gestionar hitos (vacío = solo admins)")
                .addOptions(OptionData(OptionType.ROLE, "rol", "Rol de Discord autorizado (opcional)", false))
        )
)

internal fun currentSpecialPokemon(
    server: net.minecraft.server.MinecraftServer,
    playerId: UUID,
    includeParty: Boolean = true
) = run {
    val registryAccess = server.registryAccess()
    ((if (includeParty) Cobblemon.storage.getParties(playerId, registryAccess).flatMap { it.toList() } else emptyList()) +
        Cobblemon.storage.getPC(playerId, registryAccess).toList())
        .filter { (it.isLegendary() || it.isMythical()) && !it.isUltraBeast() && !LegendaryFormHelper.isUltraBeast(it.species.resourceIdentifier.path, it.species.name) }
        .distinctBy { it.uuid }
}
