package com.desgraciamon.catchassistant

import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.pokeball.PokeBallCaptureCalculatedEvent
import com.cobblemon.mod.common.api.events.pokeball.ThrownPokeballHitEvent
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent
import com.cobblemon.mod.common.api.pokeball.catching.CaptureContext
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
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
        CobblemonEvents.THROWN_POKEBALL_HIT.subscribe(::onPokeballHit)
        CobblemonEvents.POKE_BALL_CAPTURE_CALCULATED.subscribe(::onCaptureCalculated)
        CobblemonEvents.POKEMON_CAPTURED.subscribe(::onPokemonCaptured)
    }

    private fun onPokeballHit(event: ThrownPokeballHitEvent) {
        if (event.isCanceled) return
        val player = event.pokeBall.owner as? ServerPlayer ?: return

        val pokemon = event.pokemon.pokemon
        val path = pokemon.species.resourceIdentifier.path
        val baseSpeciesName = pokemon.species.name
        if (pokemon.isUltraBeast() || LegendaryFormHelper.isUltraBeast(path, baseSpeciesName)) return
        if (!pokemon.isLegendary() && !pokemon.isMythical()) return

        val key = LegendaryFormHelper.getCanonicalSpeciesKey(pokemon)
        val limit = LegendaryFormHelper.getMaxAllowedForSpecies(key)
        val owned = LegendaryFormHelper.countPlayerOwnedMatching(player, pokemon)

        if (owned >= limit) {
            val formName = pokemon.form.name.lowercase().trim()
            val displayName = if (LegendaryFormHelper.isNotableForm(formName)) {
                LegendaryFormHelper.getFormDisplayName(baseSpeciesName, formName)
            } else {
                baseSpeciesName
            }
            val msg = Component.literal("§c¡Ya posees el máximo permitido ($limit) de $displayName!")
            player.displayClientMessage(msg, true)
            player.sendSystemMessage(msg)
            if (event.pokemon.battleId != null) {
                event.pokeBall.captureFuture.complete(false)
            }
            event.cancel()
        }
    }

    private fun onCaptureCalculated(event: PokeBallCaptureCalculatedEvent) {
        val player = event.thrower as? ServerPlayer ?: return

        val pokemon = event.pokemonEntity.pokemon
        val path = pokemon.species.resourceIdentifier.path
        val baseSpeciesName = pokemon.species.name
        if (pokemon.isUltraBeast() || LegendaryFormHelper.isUltraBeast(path, baseSpeciesName)) return
        if (!pokemon.isLegendary() && !pokemon.isMythical()) return

        val key = LegendaryFormHelper.getCanonicalSpeciesKey(pokemon)
        val limit = LegendaryFormHelper.getMaxAllowedForSpecies(key)
        val owned = LegendaryFormHelper.countPlayerOwnedMatching(player, pokemon)

        if (owned >= limit) {
            event.captureResult = CaptureContext(
                numberOfShakes = 0,
                isSuccessfulCapture = false,
                isCriticalCapture = false
            )
        }
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
            )
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
