package com.desgraciamon.discordbridge

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class DiscordBotListener(private val config: DiscordBridgeConfig) : ListenerAdapter() {
    private val guildId get() = config.guildId

    override fun onReady(event: ReadyEvent) {
        val commands = slashCommands()
        val targetGuild: Guild? = guildId.takeIf(String::isNotBlank)?.let(event.jda::getGuildById)
        if (guildId.isNotBlank() && targetGuild == null) {
            DiscordBridgeMod.logger.error("Configured Discord guild ID was not found; slash commands were not registered")
            return
        }
        if (targetGuild != null) targetGuild.updateCommands().addCommands(commands).queue()
        else event.jda.updateCommands().addCommands(commands).queue()
        DiscordBridgeMod.logger.info("Discord Bridge bot is ready (Skin channel ID: ${config.skinChannelId.ifBlank { "Ninguno configurado" }})")
    }

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) return
        val targetChannelId = config.skinChannelId
        if (targetChannelId.isBlank() || event.channel.id != targetChannelId) return

        val message = event.message
        val attachment = message.attachments.firstOrNull { it.isImage || it.fileExtension?.lowercase() == "png" }
        val content = message.contentRaw.trim()

        if (attachment == null && content.isBlank()) {
            message.delete().queue(null) { /* ignore */ }
            event.channel.sendMessage(
                "${event.author.asMention}, el bot no puede leer la imagen ni el enlace porque **Message Content Intent** está desactivado en el Discord Developer Portal.\n\n" +
                    "**Para solucionarlo (1 minuto):**\n" +
                    "1. Ve a https://discord.com/developers/applications y entra a tu bot.\n" +
                    "2. Ve a la pestaña **Bot** (a la izquierda).\n" +
                    "3. En **Privileged Gateway Intents**, activa **Message Content Intent**.\n" +
                    "4. Haz clic en **Save Changes** y reinicia el servidor.\n\n" +
                    "*¡Mientras tanto, puedes usar el comando slash `/skin` aquí mismo para convertir tu skin!*"
            ).queue { temp ->
                temp.delete().queueAfter(15, TimeUnit.SECONDS, null) { /* ignore */ }
            }
            return
        }

        val hasValidLink = content.contains("namemc.com", ignoreCase = true) ||
            content.contains("textures.minecraft.net", ignoreCase = true) ||
            content.matches(Regex(".*https?://.*\\.(png|jpg|jpeg).*", RegexOption.IGNORE_CASE))

        if (attachment != null || hasValidLink) {
            event.channel.sendTyping().queue()
            val future = if (attachment != null) {
                attachment.proxy.download().thenCompose { stream ->
                    val bytes = stream.use { it.readAllBytes() }
                    SkinConverterService.convertFromBytes(bytes, attachment.fileName)
                }
            } else {
                SkinConverterService.convertFromUrlOrText(content)
            }

            future.whenComplete { result, err ->
                if (err != null) {
                    val errMsg = err.cause?.message ?: err.message ?: "Error desconocido"
                    event.channel.sendMessage("${event.author.asMention}, no se pudo procesar la skin: $errMsg").queue { temp ->
                        temp.delete().queueAfter(10, TimeUnit.SECONDS, null) { /* ignore */ }
                    }
                } else if (result != null) {
                    postSkinResult(event.channel, event.author, result)
                    message.delete().queue(null) { /* ignore */ }
                }
            }
        } else {
            message.delete().queue(null) { /* ignore */ }
            event.channel.sendMessage("${event.author.asMention}, este canal es exclusivo para skins. Sube una imagen `.png` o un enlace de NameMC.").queue { temp ->
                temp.delete().queueAfter(6, TimeUnit.SECONDS, null) { /* ignore */ }
            }
        }
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        when (event.name) {
            "cuenta" -> handleCuenta(event)
            "perfil" -> handlePerfil(event)
            "equipo", "legendarios", "cooldown" -> handleCobblemonQuery(event)
            "skin" -> handleSkin(event)
            "hitos" -> handleHitos(event)
            "admin" -> handleAdmin(event)
            else -> event.reply(TextCatalog.string("discord.unknown_command")).setEphemeral(true).queue()
        }
    }

    private fun handleCuenta(event: SlashCommandInteractionEvent) {
        val server = DiscordBridgeRuntime.server
        if (server == null) {
            event.reply(TextCatalog.string("discord.server_unavailable")).setEphemeral(true).queue()
            return
        }
        val data = DiscordLinkData.get(server)
        when (event.subcommandName) {
            "vincular" -> {
                val code = event.getOption("codigo")?.asString?.trim() ?: ""
                val (result, mcName) = data.link(code, event.user.id)
                val message = when (result) {
                    LinkResult.LINKED -> {
                        val name = mcName ?: data.playerForDiscord(event.user.id)?.let { data.minecraftName(it) } ?: "tu personaje"
                        TextCatalog.string("discord.linked", "player" to name)
                    }
                    LinkResult.INVALID_OR_EXPIRED -> TextCatalog.string("discord.code_invalid")
                    LinkResult.DISCORD_ALREADY_LINKED -> TextCatalog.string("discord.already_linked")
                    LinkResult.MINECRAFT_ALREADY_LINKED -> "Esta cuenta de Minecraft ya está vinculada a otro usuario de Discord (la vinculación es permanente 1 a 1)."
                }
                event.reply(message).setEphemeral(true).queue()
            }
            "desvincular" -> {
                event.reply("La vinculación entre Discord y Minecraft es permanente (1 a 1). Si hubo un error, contacta a un administrador.").setEphemeral(true).queue()
            }
            else -> event.reply(TextCatalog.string("discord.unknown_command")).setEphemeral(true).queue()
        }
    }

    private fun handlePerfil(event: SlashCommandInteractionEvent) {
        val isPublic = event.getOption("publico")?.asBoolean ?: false
        event.deferReply(!isPublic).queue({ hook ->
            val server = DiscordBridgeRuntime.server
            if (server == null) {
                hook.editOriginal(TextCatalog.string("discord.server_unavailable")).queue(null) { /* ignore */ }
                return@queue
            }
            val targetUser = event.getOption("jugador")?.asUser ?: event.user
            val data = DiscordLinkData.get(server)
            val playerId = data.playerForDiscord(targetUser.id)
            if (playerId == null) {
                val msg = if (targetUser.id == event.user.id) {
                    "No tienes tu cuenta de Minecraft vinculada.\nUsa `/discord vincular` dentro de Minecraft para obtener tu código y luego `/cuenta vincular` en Discord."
                } else {
                    TextCatalog.string("discord.player_not_linked", "player" to targetUser.effectiveName)
                }
                hook.editOriginal(msg).queue(null) { /* ignore */ }
                return@queue
            }

            server.execute {
                try {
                    val embed = ProfileService.buildEmbed(server, targetUser, playerId)
                    val activeRegion = GymBridge.getSummary(server, playerId).regionId
                    val selectMenu = buildRegionSelectMenu(playerId, targetUser.id, activeRegion)
                    hook.editOriginalEmbeds(embed).setActionRow(selectMenu).queue(null) { ex ->
                        DiscordBridgeMod.logger.error("Error editing profile response", ex)
                    }
                } catch (t: Throwable) {
                    DiscordBridgeMod.logger.error("Error building profile embed", t)
                    hook.editOriginal("Error al obtener las estadísticas: ${t.message ?: "Error desconocido"}").queue(null) { /* ignore */ }
                }
            }
        }, { err ->
            DiscordBridgeMod.logger.error("Failed to defer reply for /perfil", err)
        })
    }

    private fun handleCobblemonQuery(event: SlashCommandInteractionEvent) {
        val isPublic = if (event.name == "legendarios") {
            event.getOption("publico")?.asBoolean ?: false
        } else {
            false
        }
        event.deferReply(!isPublic).queue({ hook ->
            val server = DiscordBridgeRuntime.server
            if (server == null) {
                hook.editOriginal(TextCatalog.string("discord.server_unavailable")).queue(null) { /* ignore */ }
                return@queue
            }

            server.execute {
                try {
                    val data = DiscordLinkData.get(server)
                    var responseEmbeds: List<MessageEmbed> = emptyList()
                    val user = event.getOption("jugador")?.asUser ?: event.user
                    val playerId = data.playerForDiscord(user.id)

                    val message = when (event.name) {
                        "legendarios" -> {
                            val targetOption = event.getOption("jugador")?.asUser
                            val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Caracas"))
                            if (targetOption == null) {
                                // Sin @jugador: mostrar TODOS los legendarios capturados en el servidor
                                val globalEntries = runCatching {
                                    val clazz = Class.forName("com.addoncobblemon.legendarybestiary.LegendaryBestiaryData")
                                    val dataObj = clazz.getMethod("get", net.minecraft.server.MinecraftServer::class.java).invoke(null, server)
                                    @Suppress("UNCHECKED_CAST")
                                    val list = clazz.getMethod("entries").invoke(dataObj) as? List<Any> ?: emptyList()
                                    list.mapNotNull { entry ->
                                        val ec = entry.javaClass
                                        val captured = ec.getMethod("getCaptured").invoke(entry) as? Boolean ?: false
                                        if (!captured) return@mapNotNull null
                                        val name = ec.getMethod("getName").invoke(entry) as? String ?: "?"
                                        val origOwner = ec.getMethod("getOriginalOwner").invoke(entry) as? String ?: ""
                                        val currOwner = ec.getMethod("getCurrentOwner").invoke(entry) as? String ?: origOwner
                                        val capAt = ec.getMethod("getCapturedAt").invoke(entry) as? Long ?: 0L
                                        Triple(name, currOwner.ifBlank { origOwner.ifBlank { "Desconocido" } }, capAt)
                                    }.sortedByDescending { it.third }
                                }.getOrDefault(emptyList())

                                val eb = net.dv8tion.jda.api.EmbedBuilder()
                                    .setTitle("Registro Global de Legendarios")
                                    .setColor(0x38BDF8)
                                    .setFooter("Desgraciamon 3 • Legendarios capturados: ${globalEntries.size}")
                                if (globalEntries.isEmpty()) {
                                    eb.setDescription("Aún no se ha capturado ningún Pokémon legendario o mítico en el servidor.")
                                } else {
                                    val lines = globalEntries.take(25).map { (spName, owner, capAt) ->
                                        val dt = if (capAt > 0L) "<t:${capAt / 1000L}:f> (${dateFormatter.format(Instant.ofEpochMilli(capAt))})" else "Sin fecha"
                                        "**$spName**\n└ Entrenador: **$owner** • $dt"
                                    }
                                    val desc = lines.joinToString("\n\n") + if (globalEntries.size > 25) "\n\n*...y ${globalEntries.size - 25} más.*" else ""
                                    eb.setDescription(desc.take(4000))
                                }
                                responseEmbeds = listOf(eb.build())
                                ""
                            } else if (playerId == null) {
                                TextCatalog.string("discord.player_not_linked", "player" to targetOption.effectiveName)
                            } else {
                                val mcName = data.minecraftName(playerId) ?: targetOption.effectiveName
                                val pokemon = currentSpecialPokemon(server, playerId, includeParty = !data.isTeamPrivate(playerId))
                                val eb = net.dv8tion.jda.api.EmbedBuilder()
                                    .setTitle("Legendarios de $mcName")
                                    .setThumbnail(targetOption.effectiveAvatarUrl)
                                    .setColor(0x38BDF8)
                                    .setFooter("Desgraciamon 3 • Total: ${pokemon.size}")
                                if (pokemon.isEmpty()) {
                                    eb.setDescription("<@${targetOption.id}> no tiene Pokémon legendarios ni míticos registrados.")
                                } else {
                                    val lines = pokemon.take(20).map { caught ->
                                        val record = data.captureRecord(playerId, caught.uuid)
                                        val capturedAt = record?.capturedAt
                                        val dateText = capturedAt?.let { "<t:${it / 1000L}:f> (${dateFormatter.format(Instant.ofEpochMilli(it))})" }
                                            ?: TextCatalog.string("discord.date_missing")
                                        val shiny = caught.shiny || record?.shiny == true
                                        val formName = caught.form.name.lowercase().trim()
                                        val speciesDisplayName = if (record != null && record.speciesName.isNotBlank() && record.speciesName != caught.species.name) {
                                            record.speciesName
                                        } else if (LegendaryFormHelper.isNotableForm(formName)) {
                                            LegendaryFormHelper.getFormDisplayName(caught.species.translatedName.string, formName)
                                        } else {
                                            caught.species.translatedName.string
                                        }
                                        val stateTag = if (shiny) "✨ Shiny" else "Normal"
                                        "**$speciesDisplayName** `[$stateTag]`\n└ Captura: $dateText"
                                    }
                                    val desc = lines.joinToString("\n\n") + if (pokemon.size > 20) "\n\n*...y ${pokemon.size - 20} más.*" else ""
                                    eb.setDescription(desc.take(4000))
                                }
                                responseEmbeds = listOf(eb.build())
                                ""
                            }
                        }

                        "equipo" -> {
                            if (playerId == null) {
                                TextCatalog.string("discord.player_not_linked", "player" to user.effectiveName)
                            } else if (data.isTeamPrivate(playerId) && data.playerForDiscord(event.user.id) != playerId) {
                                TextCatalog.string("discord.team_private", "player" to (data.minecraftName(playerId) ?: user.effectiveName))
                            } else {
                                val team = com.cobblemon.mod.common.Cobblemon.storage
                                    .getParty(playerId, server.registryAccess())
                                    .toList()
                                if (team.isEmpty()) {
                                    TextCatalog.string(
                                        "discord.team_empty",
                                        "player" to (data.minecraftName(playerId) ?: user.effectiveName),
                                        "discordId" to user.id
                                    )
                                } else {
                                    responseEmbeds = team.mapIndexed { index, pokemon -> pokemonTeamEmbed(index + 1, pokemon) }
                                    TextCatalog.string(
                                        "discord.team_summary",
                                        "player" to (data.minecraftName(playerId) ?: user.effectiveName),
                                        "discordId" to user.id,
                                        "count" to team.size
                                    )
                                }
                            }
                        }

                        "cooldown" -> {
                            if (playerId == null) {
                                TextCatalog.string("discord.player_not_linked", "player" to user.effectiveName)
                            } else {
                                val remaining = readCooldown(server, playerId)
                                if (remaining == 0L) {
                                    TextCatalog.string(
                                        "discord.cooldown_ready",
                                        "player" to (data.minecraftName(playerId) ?: user.effectiveName),
                                        "discordId" to user.id
                                    )
                                } else {
                                    val availableAt = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() + remaining)
                                    TextCatalog.string(
                                        "discord.cooldown_wait",
                                        "player" to (data.minecraftName(playerId) ?: user.effectiveName),
                                        "discordId" to user.id,
                                        "remaining" to formatRemaining(remaining),
                                        "availableAt" to availableAt
                                    )
                                }
                            }
                        }

                        else -> TextCatalog.string("discord.unknown_command")
                    }

                    if (responseEmbeds.isEmpty()) {
                        hook.editOriginal(message).queue(null) { ex ->
                            DiscordBridgeMod.logger.error("Error editing slash response", ex)
                        }
                    } else {
                        hook.editOriginalEmbeds(responseEmbeds).setContent(message).queue(null) { ex ->
                            DiscordBridgeMod.logger.error("Error editing slash response with embeds", ex)
                        }
                    }
                } catch (t: Throwable) {
                    DiscordBridgeMod.logger.error("Error processing slash command '${event.name}'", t)
                    hook.editOriginal("Ocurrió un error al procesar el comando: ${t.message ?: "Error desconocido"}").queue(null) { /* ignore */ }
                }
            }
        }, { err ->
            DiscordBridgeMod.logger.error("Failed to defer reply for slash command '${event.name}'", err)
        })
    }

    private fun handleSkin(event: SlashCommandInteractionEvent) {
        val fileOpt = event.getOption("archivo")?.asAttachment
        val urlOpt = event.getOption("url")?.asString
        if (fileOpt == null && urlOpt.isNullOrBlank()) {
            event.reply("Debes adjuntar un archivo `.png` o ingresar una URL de NameMC / usuario.").setEphemeral(true).queue()
            return
        }

        event.deferReply(true).queue({ hook ->
            val future = if (fileOpt != null) {
                fileOpt.proxy.download().thenCompose { stream ->
                    val bytes = stream.use { it.readAllBytes() }
                    SkinConverterService.convertFromBytes(bytes, fileOpt.fileName)
                }
            } else {
                SkinConverterService.convertFromUrlOrText(urlOpt!!)
            }

            future.whenComplete { result, err ->
                if (err != null) {
                    val errMsg = err.cause?.message ?: err.message ?: "Error desconocido"
                    hook.editOriginal("Error al procesar la skin: $errMsg").queue(null) { /* ignore */ }
                } else if (result != null) {
                    editSlashSkinResult(hook, event.user, result)
                }
            }
        }, { err ->
            DiscordBridgeMod.logger.error("Failed to defer reply for /skin", err)
        })
    }

    private fun handleHitos(event: SlashCommandInteractionEvent) {
        when (event.subcommandName ?: "ver") {
            "ver" -> {
                event.replyEmbeds(HitosDiscordHelper.buildEmbed(config, showAdminInfo = false))
                    .setEphemeral(true)
                    .queue()
            }
            "panel" -> {
                if (!HitosDiscordUi.canManageHitos(event.member, config)) {
                    event.reply(HitosDiscordUi.permissionError(config)).setEphemeral(true).queue()
                    return
                }
                HitosDiscordUi.replyPanel(event, config)
            }
            "fecha" -> {
                if (!HitosDiscordUi.canManageHitos(event.member, config)) {
                    event.reply(HitosDiscordUi.permissionError(config)).setEphemeral(true).queue()
                    return
                }
                HitosDiscordUi.replyDatePicker(event)
            }
            "dia" -> {
                if (!HitosDiscordUi.canManageHitos(event.member, config)) {
                    event.reply(HitosDiscordUi.permissionError(config)).setEphemeral(true).queue()
                    return
                }
                val num = event.getOption("numero")?.asInt ?: -1
                if (num < 0) {
                    event.reply("El número de día debe ser 0 (automático) o mayor.").setEphemeral(true).queue()
                    return
                }
                val effective = HitosDiscordHelper.setCurrentDay(num)
                val notice = if (num == 0) {
                    "Modo automático activado (**Día $effective**). Sincronizado con Minecraft."
                } else {
                    "Día actual fijado en **Día $effective**. Sincronizado con Minecraft."
                }
                HitosDiscordUi.replyPanel(event, config, notice)
            }
            else -> event.reply(TextCatalog.string("discord.unknown_command")).setEphemeral(true).queue()
        }
    }

    private fun handleAdmin(event: SlashCommandInteractionEvent) {
        val member = event.member
        if (config.hasConfiguredAdminUsers() && !config.isAdminUser(event.user.id)) {
            event.reply("Solo los usuarios administradores configurados por ID pueden usar `/admin`.").setEphemeral(true).queue()
            return
        }
        when (event.subcommandName) {
            "canal-skins" -> {
                val hasPerm = if (config.hasConfiguredAdminUsers()) {
                    config.isAdminUser(event.user.id)
                } else {
                    member != null && (member.hasPermission(Permission.ADMINISTRATOR) || member.hasPermission(Permission.MANAGE_SERVER))
                }
                if (!hasPerm) {
                    event.reply("Necesitas permisos de Administrador o Gestionar Servidor.").setEphemeral(true).queue()
                    return
                }
                val channel = event.getOption("canal")!!.asChannel
                config.skinChannelId = channel.id
                config.save()
                event.reply("Canal automático de skins configurado en <#${channel.id}>.")
                    .setEphemeral(true)
                    .queue()
            }
            "rol-hitos" -> {
                if (!HitosDiscordUi.canManageHitos(member, config, forRoleChange = true)) {
                    event.reply(HitosDiscordUi.permissionError(config, forRoleChange = true)).setEphemeral(true).queue()
                    return
                }
                val role = event.getOption("rol")?.asRole
                val savedRoleId = HitosDiscordHelper.setHitosRoleId(role?.id ?: "", config)
                if (savedRoleId.isNotBlank()) {
                    event.reply("Rol autorizado para hitos configurado en <@&$savedRoleId>.")
                        .setEphemeral(true)
                        .queue()
                } else {
                    event.reply("Rol autorizado limpiado. Solo Administradores podrán gestionar hitos.")
                        .setEphemeral(true)
                        .queue()
                }
            }
            "desvincular" -> {
                val server = DiscordBridgeRuntime.server
                if (server == null) {
                    event.reply(TextCatalog.string("discord.server_unavailable")).setEphemeral(true).queue()
                    return
                }
                val targetUser = event.getOption("usuario")?.asUser
                val targetName = event.getOption("jugador")?.asString?.trim()
                if (targetUser == null && targetName.isNullOrBlank()) {
                    event.reply("Debes indicar `usuario` de Discord o `jugador` de Minecraft.").setEphemeral(true).queue()
                    return
                }
                val data = DiscordLinkData.get(server)
                val msg = if (targetUser != null) {
                    val mcName = data.unlinkDiscord(targetUser.id)
                    if (mcName != null) "Se desvinculó a <@${targetUser.id}> de **$mcName**."
                    else "<@${targetUser.id}> no tenía ninguna cuenta vinculada."
                } else {
                    val removed = data.unlinkByNameOrId(targetName!!)
                    if (removed != null) "Se desvinculó la cuenta de Minecraft **${removed.second}** (`${removed.first}`)."
                    else "No se encontró ninguna cuenta vinculada para **$targetName**."
                }
                event.reply(msg).setEphemeral(true).queue()
            }
            else -> event.reply(TextCatalog.string("discord.unknown_command")).setEphemeral(true).queue()
        }
    }

    private fun postSkinResult(
        channel: MessageChannel,
        author: User,
        result: SkinConverterService.SkinResult
    ) {
        val server = DiscordBridgeRuntime.server
        if (server != null) {
            server.execute {
                val data = DiscordLinkData.get(server)
                val linkedPlayerId = data.playerForDiscord(author.id)
                if (linkedPlayerId != null) {
                    data.setSkin(linkedPlayerId, result.textureHash, result.model)
                }
                val linkedMcName = linkedPlayerId?.let {
                    server.playerList.getPlayer(it)?.gameProfile?.name ?: data.minecraftName(it)
                }
                val embed = SkinConverterService.buildEmbed(result, author, linkedMcName)
                val action = channel.sendMessageEmbeds(embed)
                if (linkedMcName != null) {
                    val btnSteve = Button.success("sk:c:${result.textureHash}:${author.id}", "Steve (Clásico) • $linkedMcName")
                    val btnAlex = Button.primary("sk:s:${result.textureHash}:${author.id}", "Alex (Slim) • $linkedMcName")
                    action.setActionRow(btnSteve, btnAlex)
                }
                action.queue({ msg ->
                    msg.delete().queueAfter(24, TimeUnit.HOURS, null) { /* ignore */ }
                }, null)
            }
        } else {
            val embed = SkinConverterService.buildEmbed(result, author, null)
            channel.sendMessageEmbeds(embed).queue({ msg ->
                msg.delete().queueAfter(24, TimeUnit.HOURS, null) { /* ignore */ }
            }, null)
        }
    }

    private fun editSlashSkinResult(
        hook: InteractionHook,
        user: User,
        result: SkinConverterService.SkinResult
    ) {
        val server = DiscordBridgeRuntime.server
        if (server != null) {
            server.execute {
                val data = DiscordLinkData.get(server)
                val linkedPlayerId = data.playerForDiscord(user.id)
                if (linkedPlayerId != null) {
                    data.setSkin(linkedPlayerId, result.textureHash, result.model)
                }
                val linkedMcName = linkedPlayerId?.let {
                    server.playerList.getPlayer(it)?.gameProfile?.name ?: data.minecraftName(it)
                }
                val embed = SkinConverterService.buildEmbed(result, user, linkedMcName)
                val action = hook.editOriginalEmbeds(embed)
                if (linkedMcName != null) {
                    val btnSteve = Button.success("sk:c:${result.textureHash}:${user.id}", "Steve (Clásico) • $linkedMcName")
                    val btnAlex = Button.primary("sk:s:${result.textureHash}:${user.id}", "Alex (Slim) • $linkedMcName")
                    action.setActionRow(btnSteve, btnAlex)
                }
                action.queue({ msg ->
                    msg.delete().queueAfter(24, TimeUnit.HOURS, null) { /* ignore */ }
                }, null)
            }
        } else {
            val embed = SkinConverterService.buildEmbed(result, user, null)
            hook.editOriginalEmbeds(embed).queue({ msg ->
                msg.delete().queueAfter(24, TimeUnit.HOURS, null) { /* ignore */ }
            }, null)
        }
    }

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        if (HitosDiscordUi.handleButton(event, config)) return

        val buttonId = event.componentId
        if (!buttonId.startsWith("sk:")) return

        val parts = buttonId.split(":")
        if (parts.size < 4) {
            event.reply("Formato de botón no válido.").setEphemeral(true).queue()
            return
        }

        val modelCode = parts[1]
        val hash = parts[2]
        val targetDiscordId = parts[3]

        if (!hash.matches(Regex("^[a-fA-F0-9]{32,64}$")) || (modelCode != "s" && modelCode != "c")) {
            event.reply("Parámetros de textura no válidos.").setEphemeral(true).queue()
            return
        }

        if (event.user.id != targetDiscordId) {
            event.reply("Solo la persona que solicitó la skin (<@$targetDiscordId>) puede usar este botón.\n*Sube tu propia skin o usa `/skin` para aplicarte una.*")
                .setEphemeral(true)
                .queue()
            return
        }

        event.deferReply(true).queue({ hook ->
            val server = DiscordBridgeRuntime.server
            if (server == null) {
                hook.editOriginal("El servidor de Minecraft no está disponible en este momento.").queue(null) { /* ignore */ }
                return@queue
            }

            val modelArg = if (modelCode == "s") "slim" else "classic"
            val textureUrl = "https://textures.minecraft.net/texture/$hash"

            server.execute {
                try {
                    val data = DiscordLinkData.get(server)
                    val playerId = data.playerForDiscord(event.user.id)
                    if (playerId == null) {
                        hook.editOriginal("Tu cuenta de Discord no está vinculada a ningún personaje de Minecraft.\nUsa `/cuenta vincular` en el juego para vincularte.").queue(null) { /* ignore */ }
                        return@execute
                    }

                    data.setSkin(playerId, hash, modelArg)
                    val onlinePlayer = server.playerList.getPlayer(playerId)
                    val mcName = onlinePlayer?.gameProfile?.name ?: data.minecraftName(playerId) ?: "Jugador"

                    if (onlinePlayer != null) {
                        server.commands.performPrefixedCommand(
                            server.createCommandSourceStack(),
                            "skin set web $modelArg $textureUrl $mcName"
                        )
                        onlinePlayer.sendSystemMessage(
                            Component.literal("§6[Desgraciamon] §a¡Tu skin ha sido actualizada desde Discord!")
                        )
                        hook.editOriginal("¡Listo! Se ha aplicado la skin correctamente a tu personaje **$mcName** en el servidor.").queue(null) { /* ignore */ }
                    } else {
                        server.commands.performPrefixedCommand(
                            server.createCommandSourceStack(),
                            "skin set web $modelArg $textureUrl $mcName"
                        )
                        hook.editOriginal("¡Listo! Se ha guardado la skin para tu personaje **$mcName**.\nSe verá reflejada automáticamente la próxima vez que entres al servidor.").queue(null) { /* ignore */ }
                    }
                } catch (ex: Exception) {
                    DiscordBridgeMod.logger.error("Error al aplicar skin desde botón de Discord", ex)
                    val msg = ex.message ?: "Error desconocido"
                    hook.editOriginal("Ocurrió un error al aplicar la skin en el servidor: $msg").queue(null) { /* ignore */ }
                }
            }
        }, { err ->
            DiscordBridgeMod.logger.error("Failed to defer button interaction", err)
        })
    }

    private fun buildRegionSelectMenu(playerId: UUID, targetDiscordId: String, selectedRegion: String? = null): StringSelectMenu {
        val builder = StringSelectMenu.create("stats_region:$playerId:$targetDiscordId")
            .setPlaceholder("Seleccionar otra región para ver sus medallas...")
            .addOption("Kanto", "kanto", "Medallas de Gimnasio y Liga de Kanto")
            .addOption("Johto", "johto", "Medallas de Gimnasio y Liga de Johto")
            .addOption("Hoenn", "hoenn", "Medallas de Gimnasio y Liga de Hoenn")
            .addOption("Sinnoh", "sinnoh", "Medallas de Gimnasio y Liga de Sinnoh")
        if (!selectedRegion.isNullOrBlank()) {
            val normalized = selectedRegion.lowercase(Locale.ROOT)
            if (normalized in setOf("kanto", "johto", "hoenn", "sinnoh")) {
                builder.setDefaultValues(listOf(normalized))
            }
        }
        return builder.build()
    }

    override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
        if (HitosDiscordUi.handleStringSelect(event, config)) return

        val menuId = event.componentId
        if (!menuId.startsWith("stats_region:")) return

        val parts = menuId.split(":")
        if (parts.size < 3) return

        val playerId = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return
        val targetDiscordUserId = parts[2].ifBlank { event.user.id }
        val selectedRegion = event.values.firstOrNull() ?: "kanto"

        val server = DiscordBridgeRuntime.server
        if (server == null) {
            event.reply("El servidor de Minecraft no está disponible.").setEphemeral(true).queue()
            return
        }

        event.deferEdit().queue({ hook ->
            val targetUser = event.jda.getUserById(targetDiscordUserId) ?: event.user
            server.execute {
                try {
                    val newEmbed = ProfileService.buildEmbed(server, targetUser, playerId, selectedRegion)
                    val updatedMenu = buildRegionSelectMenu(playerId, targetDiscordUserId, selectedRegion)
                    hook.editOriginalEmbeds(newEmbed).setActionRow(updatedMenu).queue(null) { ex ->
                        DiscordBridgeMod.logger.error("Error editing stats dropdown response", ex)
                    }
                } catch (t: Throwable) {
                    DiscordBridgeMod.logger.error("Error updating profile with selected region", t)
                }
            }
        }, { err ->
            DiscordBridgeMod.logger.error("Failed to defer stats region selection", err)
        })
    }

    override fun onModalInteraction(event: ModalInteractionEvent) {
        HitosDiscordUi.handleModal(event, config)
    }
}
