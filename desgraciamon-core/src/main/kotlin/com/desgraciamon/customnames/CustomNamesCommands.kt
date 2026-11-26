package com.desgraciamon.customnames

import com.desgraciamon.badges.AdminAuth
import com.desgraciamon.badges.RichText
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component

object CustomNamesCommands {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val apodoRoot = Commands.literal("apodo")
            .then(Commands.literal("quitar").executes { ctx -> clearSelf(ctx.source) })
            .then(Commands.literal("admin")
                .requires { AdminAuth.isAdminSource(it) }
                .then(Commands.literal("fijar")
                    .then(Commands.argument("jugador", EntityArgument.player())
                        .then(Commands.argument("apodo", StringArgumentType.greedyString())
                            .executes { ctx ->
                                val target = EntityArgument.getPlayer(ctx, "jugador")
                                val rawNick = StringArgumentType.getString(ctx, "apodo").trim()
                                if (rawNick.isEmpty()) {
                                    ctx.source.sendFailure(Component.literal("§cEl apodo no puede estar vacio."))
                                    return@executes 0
                                }
                                CustomNamesManager.setNickname(target.uuid, target.gameProfile.name, rawNick)
                                val parsed = RichText.parse(rawNick)
                                ctx.source.sendSuccess(
                                    { Component.empty().append("§a[Apodos] §fEl apodo de §e${target.gameProfile.name} §fahora es: ").append(parsed) },
                                    true
                                )
                                target.sendSystemMessage(
                                    Component.empty().append("§a[Apodos] §fUn administrador ha cambiado tu apodo a: ").append(parsed)
                                )
                                1
                            }
                        )
                    )
                )
                .then(Commands.literal("quitar")
                    .then(Commands.argument("jugador", EntityArgument.player())
                        .executes { ctx ->
                            val target = EntityArgument.getPlayer(ctx, "jugador")
                            CustomNamesManager.clearNickname(target.uuid)
                            ctx.source.sendSuccess(
                                { Component.literal("§a[Apodos] §fApodo de §e${target.gameProfile.name} §feliminado.") },
                                true
                            )
                            1
                        }
                    )
                )
            )
            .then(Commands.argument("nuevo_apodo", StringArgumentType.greedyString())
                .executes { ctx ->
                    val player = ctx.source.playerOrException
                    val rawNick = StringArgumentType.getString(ctx, "nuevo_apodo").trim()

                    if (rawNick.equals("quitar", ignoreCase = true)) {
                        return@executes clearSelf(ctx.source)
                    }

                    val isAdmin = AdminAuth.isAdmin(player)
                    val hasTags = rawNick.contains("<") || rawNick.contains(">") || rawNick.contains("&") || rawNick.contains("§")

                    if (hasTags && !isAdmin) {
                        ctx.source.sendFailure(
                            Component.literal("§c[Apodos] §fSolo los administradores pueden usar formato de color (<color>) o Prism (<prism>).")
                        )
                        return@executes 0
                    }

                    val cleanNick = if (isAdmin) rawNick else rawNick.replace(Regex("<[^>]*>"), "").trim()

                    if (cleanNick.length < 2) {
                        ctx.source.sendFailure(Component.literal("§cEl apodo debe tener al menos 2 caracteres."))
                        return@executes 0
                    }
                    if (cleanNick.length > 24) {
                        ctx.source.sendFailure(Component.literal("§cEl apodo no puede superar los 24 caracteres."))
                        return@executes 0
                    }

                    if (!isAdmin && !cleanNick.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                        ctx.source.sendFailure(Component.literal("§cEl apodo solo puede contener letras, numeros, guiones y barras bajas (sin espacios)."))
                        return@executes 0
                    }

                    CustomNamesManager.setNickname(player.uuid, player.gameProfile.name, cleanNick)
                    val parsed = RichText.parse(cleanNick)
                    ctx.source.sendSuccess(
                        { Component.empty().append("§a[Apodos] §fTu apodo se ha actualizado a: ").append(parsed) },
                        false
                    )
                    1
                }
            )

        val tabColorRoot = Commands.literal("tabcolor")
            .requires { AdminAuth.isAdminSource(it) }
            .then(Commands.literal("quitar")
                .then(Commands.argument("jugador", EntityArgument.player())
                    .executes { ctx ->
                        val target = EntityArgument.getPlayer(ctx, "jugador")
                        CustomNamesManager.clearTabColor(target.uuid)
                        ctx.source.sendSuccess(
                            { Component.literal("§a[Tablist] §fColor personalizado de §e${target.gameProfile.name} §feliminado.") },
                            true
                        )
                        1
                    }
                )
            )
            .then(Commands.argument("jugador", EntityArgument.player())
                .then(Commands.argument("hex", StringArgumentType.word())
                    .executes { ctx ->
                        val target = EntityArgument.getPlayer(ctx, "jugador")
                        val rawHex = StringArgumentType.getString(ctx, "hex").trim().removePrefix("#")
                        if (!rawHex.matches(Regex("^[0-9a-fA-F]{6}$"))) {
                            ctx.source.sendFailure(Component.literal("§cColor hex invalido. Usa formato #RRGGBB (ej: #38BDF8)."))
                            return@executes 0
                        }
                        val hex = "#${rawHex.uppercase()}"
                        CustomNamesManager.setTabColor(target.uuid, hex)
                        ctx.source.sendSuccess(
                            { Component.literal("§a[Tablist] §fColor de §e${target.gameProfile.name} §fcambiado a §f$hex§a.") },
                            true
                        )
                        1
                    }
                )
            )

        dispatcher.register(apodoRoot)
        dispatcher.register(tabColorRoot)
    }

    private fun clearSelf(source: CommandSourceStack): Int {
        val player = source.playerOrException
        CustomNamesManager.clearNickname(player.uuid)
        source.sendSuccess(
            { Component.literal("§e[Apodos] §fHas eliminado tu apodo. Tu nombre ahora es: §a${player.gameProfile.name}") },
            false
        )
        return 1
    }
}
