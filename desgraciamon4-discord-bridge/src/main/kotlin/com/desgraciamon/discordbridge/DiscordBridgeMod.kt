package com.desgraciamon.discordbridge

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.commands.Commands
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.util.UUID

class DiscordBridgeMod : ModInitializer {
    override fun onInitialize() {
        logger.info("Loading Desgraciamon 3 Discord Bridge")
        TextCatalog.load()
        val config = DiscordBridgeConfig.load()
        if (config.botToken.isBlank()) {
            logger.warn("Discord bot token is empty; linking commands will be available after configuring the bot token and restarting")
        } else {
            DiscordBotService.start(config)
        }

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            DiscordBridgeRuntime.server = server
            val cfg = DiscordBridgeConfig.load()
            if (cfg.botToken.isNotBlank()) {
                DiscordBotService.start(cfg)
            }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register {
            DiscordBridgeRuntime.server = null
            DiscordBotService.stop()
        }

        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            val player = handler.player
            val data = DiscordLinkData.get(server)
            runCatching {
                val apiClass = Class.forName("fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt")
                val method = apiClass.getMethod("getCobbleDollars", net.minecraft.world.entity.player.Player::class.java)
                val bal = (method.invoke(null, player) as? java.math.BigInteger)?.toLong() ?: 0L
                data.updateBalance(player.uuid, bal)

                val stateClass = Class.forName("com.gitlab.srcmc.rctmod.api.data.sync.PlayerState")
                val getMethod = stateClass.getMethod("get", net.minecraft.world.entity.player.Player::class.java)
                val state = getMethod.invoke(null, player)
                if (state != null) {
                    val cap = stateClass.getMethod("getLevelCap").invoke(state) as? Int
                    if (cap != null && cap > 0) data.setLevelCap(player.uuid, cap)
                }
            }
        }

        PayloadTypeRegistry.playC2S().register(TeamPrivacyRequest.TYPE, TeamPrivacyRequest.CODEC)
        PayloadTypeRegistry.playC2S().register(TeamPrivacyToggleRequest.TYPE, TeamPrivacyToggleRequest.CODEC)
        PayloadTypeRegistry.playC2S().register(CooldownPreferenceToggleRequest.TYPE, CooldownPreferenceToggleRequest.CODEC)
        PayloadTypeRegistry.playS2C().register(TeamPrivacyStatusPayload.TYPE, TeamPrivacyStatusPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(TeamPrivacyRequest.TYPE) { _, context ->
            context.server().execute {
                sendSettings(context.player())
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(TeamPrivacyToggleRequest.TYPE) { _, context ->
            context.server().execute {
                val player = context.player()
                val data = DiscordLinkData.get(player.server)
                if (DiscordLinkData.discordId(player.server, player.uuid) != null) {
                    val isPrivate = !data.isTeamPrivate(player.uuid)
                    data.setTeamPrivate(player.uuid, isPrivate)
                }
                sendSettings(player)
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(CooldownPreferenceToggleRequest.TYPE) { payload, context ->
            context.server().execute {
                CooldownSettingsIntegration.toggle(context.player().server, context.player().uuid, payload.preference)
                sendSettings(context.player())
            }
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("discord")
                    .then(Commands.literal("vincular").executes { context ->
                        val player = context.source.playerOrException
                        val code = DiscordLinkData.get(player.server).createCode(player.uuid, player.name.string)
                        val clickableCode = Component.literal(code).setStyle(
                            Style.EMPTY
                                .withColor(0x55FFFF)
                                .withUnderlined(true)
                                .withClickEvent(ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                                .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, TextCatalog.component("link.hover")))
                        )
                        player.sendSystemMessage(TextCatalog.component("link.message", "code" to clickableCode))
                        1
                    })
                    .then(Commands.literal("desvincular").executes { context ->
                        val player = context.source.playerOrException
                        val removed = DiscordLinkData.get(player.server).unlinkPlayer(player.uuid)
                        player.sendSystemMessage(TextCatalog.component(if (removed) "unlink.success" else "unlink.missing"))
                        1
                    })
                    .then(Commands.literal("equipo").executes { context ->
                        val player = context.source.playerOrException
                        val data = DiscordLinkData.get(player.server)
                        val isPrivate = !data.isTeamPrivate(player.uuid)
                        data.setTeamPrivate(player.uuid, isPrivate)
                        player.sendSystemMessage(TextCatalog.component(if (isPrivate) "privacy.enabled" else "privacy.disabled"))
                        1
                    })
                    .then(Commands.literal("admin")
                        .requires { it.hasPermission(2) }
                        .then(Commands.literal("recargar").executes { context ->
                            val result = TextCatalogReload.reloadAll()
                            context.source.sendSuccess({ Component.literal("Recargados ${result.reloaded} catalogos de texto.") }, true)
                            1
                        })
                    )
            )
        }

        CobblemonEvents.POKEMON_CAPTURED.subscribe(::onPokemonCaptured)
    }

    private fun onPokemonCaptured(event: PokemonCapturedEvent) {
        val server = event.player.server
        val data = DiscordLinkData.get(server)
        val pokemon = event.pokemon
        val path = pokemon.species.resourceIdentifier.path
        val baseSpeciesName = pokemon.species.name
        if (LegendaryFormHelper.isUltraBeast(path, baseSpeciesName)) return
        val isSpecial = pokemon.isLegendary() || pokemon.isMythical()
        data.recordCapture(event.player.uuid, pokemon.shiny, isSpecial)
        if (isSpecial) {
            val formName = pokemon.form.name.lowercase().trim()
            val isNotable = LegendaryFormHelper.isNotableForm(formName)
            val baseSpeciesId = pokemon.species.resourceIdentifier.toString()
            val speciesId = if (isNotable) "$baseSpeciesId#$formName" else baseSpeciesId
            val speciesName = if (isNotable) LegendaryFormHelper.getFormDisplayName(pokemon.species.translatedName.string, formName) else pokemon.species.translatedName.string

            data.recordSpecialCapture(
                event.player.uuid,
                pokemon.uuid,
                speciesId,
                speciesName,
                System.currentTimeMillis(),
                pokemon.shiny
            )
        }
    }
    private fun sendSettings(player: net.minecraft.server.level.ServerPlayer) {
        val data = DiscordLinkData.get(player.server)
        val cooldownInstalled = CooldownSettingsIntegration.isInstalled()
        ServerPlayNetworking.send(
            player,
            TeamPrivacyStatusPayload(
                data.isTeamPrivate(player.uuid),
                DiscordLinkData.discordId(player.server, player.uuid) != null,
                cooldownInstalled,
                CooldownSettingsIntegration.read(player.server, player.uuid, "isBossBarEnabled") ?: false,
                CooldownSettingsIntegration.read(player.server, player.uuid, "isJoinNoticeEnabled") ?: false,
                CooldownSettingsIntegration.isCatchAssistantInstalled(),
                CooldownSettingsIntegration.readCaptureNotice(player.server, player.uuid) ?: false
            )
        )
    }

    companion object {
        internal val logger = LoggerFactory.getLogger("desgraciamon4-discord-bridge")
    }
}

object DiscordBridgeRuntime {
    @Volatile
    var server: MinecraftServer? = null
}

internal fun readCooldown(server: MinecraftServer, playerId: UUID): Long {
    if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("legendary_cooldown")) {
        val integrated = runCatching {
            Class.forName("com.addoncobblemon.legendarycooldown.CooldownData")
                .getMethod("remainingMillis", MinecraftServer::class.java, UUID::class.java)
                .invoke(null, server, playerId) as Long
        }.getOrNull()
        if (integrated != null) return integrated
    }
    return DiscordLinkData.get(server).fallbackCooldownMillis(playerId)
}

internal fun formatRemaining(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) + 999L) / 1000L
    return "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
}
