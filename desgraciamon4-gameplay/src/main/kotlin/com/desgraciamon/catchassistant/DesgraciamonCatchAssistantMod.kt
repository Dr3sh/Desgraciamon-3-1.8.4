package com.desgraciamon.catchassistant

import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.time.Instant

class DesgraciamonCatchAssistantMod : ModInitializer {
    override fun onInitialize() {
        TextCatalog.load(mapOf(
            "capture.notice" to "{player} capturó a {species} ({form})",
            "discord.color.shiny" to "#E3B341",
            "discord.color.legendary" to "#42C98A",
            "screen.title" to "Desgraciamon Catch Assistant",
            "screen.waiting" to "Esperando datos del servidor...",
            "species.shiny" to "{species} (Shiny)",
            "species.normal" to "{species} (Normal)",
            "capture.by" to "{player} · {date}",
            "status.available" to "Disponible {date}",
            "status.cooldown_done" to "Cooldown completado",
            "status.no_captures" to "Sin capturas registradas",
            "discord.username" to "Desgraciamon 3 Bot",
            "discord.avatar_url" to "https://raw.githubusercontent.com/Dr3sh/desgraciamon-assets/main/bot/desgraciamon3_pfp.png",
            "discord.content.shiny" to "{identity} capturó **{species}** (Shiny).",
            "discord.content.normal" to "{identity} capturó **{species}**.",
            "discord.title.shiny" to "✨ {species} (Shiny) capturado",
            "discord.title.legendary" to "{species} capturado",
            "discord.description.shiny" to "**Entrenador**\n{identity}\n\n**Rareza**\n✨ Shiny\n\n**Capturado**\n<t:{captured}:F> · <t:{captured}:R>\n\n**Siguiente captura de legendario**\n<t:{next}:F> · <t:{next}:R>",
            "discord.description.normal" to "**Entrenador**\n{identity}\n\n**Capturado**\n<t:{captured}:F> · <t:{captured}:R>\n\n**Siguiente captura de legendario**\n<t:{next}:F> · <t:{next}:R>",
            "discord.footer" to "Desgraciamon 3"
        ))
        DesgraciamonConfig.load()
        PayloadTypeRegistry.playS2C().register(LegendaryCaptureSyncPayload.TYPE, LegendaryCaptureSyncPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(LegendaryCaptureSyncRequest.TYPE, LegendaryCaptureSyncRequest.CODEC)
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> sendSnapshot(handler.player) }
        ServerPlayNetworking.registerGlobalReceiver(LegendaryCaptureSyncRequest.TYPE) { _, context ->
            context.server().execute { sendSnapshot(context.player()) }
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("catchassistant")
                    .requires { it.hasPermission(2) }
                    .then(Commands.literal("reload").executes { ctx ->
                        DesgraciamonConfig.load()
                        TextCatalog.reload()
                        ctx.source.sendSuccess({ Component.literal("§aCatch Assistant recargado (enabled=${DesgraciamonConfig.current.enabled}).") }, false)
                        1
                    })
                    .then(Commands.literal("testwebhook").executes { ctx ->
                        DesgraciamonConfig.load()
                        val server = ctx.source.server
                        val senderName = ctx.source.textName
                        val senderUuid = (ctx.source.entity as? ServerPlayer)?.uuid ?: java.util.UUID(0L, 0L)
                        val now = Instant.now()
                        val next = now.plusSeconds(DesgraciamonConfig.current.cooldownHours * 3600L)
                        ctx.source.sendSuccess({ Component.literal("§eEnviando prueba de webhook a Discord...") }, false)
                        DiscordWebhookSender.send(server, "Rayquaza (Prueba)", senderName, senderUuid, 384, now, next, true) { ok, info ->
                            server.execute {
                                val msg = if (ok) "§aWebhook de Discord respondió correctamente ($info)." else "§cError enviando webhook de Discord: $info"
                                ctx.source.sendSuccess({ Component.literal(msg) }, false)
                            }
                        }
                        1
                    })
            )
        }
        CobblemonEvents.POKEMON_CAPTURED.subscribe(::onPokemonCaptured)
    }

    private fun onPokemonCaptured(event: PokemonCapturedEvent) {
        val pokemon = event.pokemon
        val path = pokemon.species.resourceIdentifier.path
        val baseSpeciesName = pokemon.species.name
        if (LegendaryFormHelper.isUltraBeast(path, baseSpeciesName)) return
        if (!pokemon.isLegendary() && !pokemon.isMythical()) return

        val server = event.player.server ?: return
        val data = LegendaryCaptureData.get(server)
        val formName = pokemon.form.name.lowercase().trim()
        val isNotable = LegendaryFormHelper.isNotableForm(formName)
        val baseSpeciesId = pokemon.species.resourceIdentifier.toString()
        val speciesId = if (isNotable) "$baseSpeciesId#$formName" else baseSpeciesId
        val speciesName = if (isNotable) LegendaryFormHelper.getFormDisplayName(baseSpeciesName, formName) else baseSpeciesName
        val playerName = event.player.name.string
        val playerUuid = event.player.uuid
        val now = Instant.now()
        val cooldownHours = DesgraciamonConfig.current.cooldownHours

        val nextCaptureAt = now.plusSeconds(cooldownHours * 60L * 60L)

        val record = LegendaryCaptureRecord(
            speciesId = speciesId,
            speciesName = speciesName,
            capturedByPlayerName = playerName,
            capturedByPlayerUuid = playerUuid,
            capturedAt = now,
            nextCaptureAt = nextCaptureAt,
            lastDiscordSent = false,
            shiny = pokemon.shiny
        )
        data.records[speciesId] = record
        if (isNotable) {
            val canonicalForm = LegendaryFormHelper.normalizeFormKey(formName)
            data.records["$baseSpeciesId#$canonicalForm"] = record
        }
        data.setDirty()

        val captureNotice = TextCatalog.component("capture.notice", "player" to playerName, "species" to speciesName, "form" to if (pokemon.shiny) "Shiny" else "Normal")
        server.playerList.players
            .filter { data.captureNoticeEnabled(it.uuid) }
            .forEach { it.sendSystemMessage(captureNotice) }

        if (DesgraciamonConfig.current.enabled && DesgraciamonConfig.current.discordWebhookUrl.isNotBlank()) {
            val dexNumber = LegendaryFormHelper.getFormPokeApiId(path, formName) ?: pokemon.species.nationalPokedexNumber
            DiscordWebhookSender.send(
                server,
                speciesName,
                playerName,
                playerUuid,
                dexNumber,
                now,
                nextCaptureAt,
                pokemon.shiny
            ) { ok, _ ->
                if (ok) {
                    server.execute {
                        record.lastDiscordSent = true
                        data.setDirty()
                    }
                }
            }
        }

        server.playerList.players.forEach(::sendSnapshot)
    }

    private fun sendSnapshot(player: ServerPlayer) {
        val records = LegendaryCaptureData.get(player.server).records
        val baseSpeciesList = PokemonSpecies.species
            .filter { species ->
                !LegendaryFormHelper.isUltraBeast(species.resourceIdentifier.path, species.name) &&
                species.labels.any { it.equals("legendary", true) || it.equals("mythical", true) }
            }

        val entries = mutableListOf<LegendaryCaptureView>()
        for (species in baseSpeciesList) {
            val baseId = species.resourceIdentifier.toString()
            val baseRecord = records[baseId]
            entries.add(
                LegendaryCaptureView(
                    speciesId = baseId,
                    speciesName = species.name,
                    capturedBy = baseRecord?.capturedByPlayerName.orEmpty(),
                    capturedAt = baseRecord?.capturedAt?.toEpochMilli() ?: 0L,
                    nextCaptureAt = baseRecord?.nextCaptureAt?.toEpochMilli() ?: 0L,
                    shiny = baseRecord?.shiny ?: false
                )
            )

            val addedForms = mutableSetOf<String>()
            for (form in species.forms) {
                val fName = form.name.lowercase().trim()
                if (LegendaryFormHelper.isNotableForm(fName)) {
                    val canonicalForm = LegendaryFormHelper.normalizeFormKey(fName)
                    if (!addedForms.add(canonicalForm)) continue
                    val formKey = "$baseId#$fName"
                    val formRecord = records[formKey] ?: records["$baseId#$canonicalForm"]
                    entries.add(
                        LegendaryCaptureView(
                            speciesId = formKey,
                            speciesName = LegendaryFormHelper.getFormDisplayName(species.name, fName),
                            capturedBy = formRecord?.capturedByPlayerName.orEmpty(),
                            capturedAt = formRecord?.capturedAt?.toEpochMilli() ?: 0L,
                            nextCaptureAt = formRecord?.nextCaptureAt?.toEpochMilli() ?: 0L,
                            shiny = formRecord?.shiny ?: false
                        )
                    )
                }
            }
        }

        val sortedEntries = entries.sortedBy { it.speciesName.lowercase() }
        ServerPlayNetworking.send(player, LegendaryCaptureSyncPayload(sortedEntries))
    }
}
