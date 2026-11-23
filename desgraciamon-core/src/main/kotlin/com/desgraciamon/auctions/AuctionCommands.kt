package com.desgraciamon.auctions

import com.cobblemon.mod.common.Cobblemon
import com.desgraciamon.badges.AdminAuth
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack

object AuctionCommands {

    private fun checkEnabled(source: CommandSourceStack): Boolean {
        if (!AuctionManager.config.enabled) {
            source.sendFailure(Component.literal("§cLas subastas estan deshabilitadas actualmente."))
            return false
        }
        return true
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val pujarRoot = Commands.literal("pujar")
            .requires { AuctionManager.config.enabled || AdminAuth.isAdminSource(it) }
            .then(
                Commands.literal("jugador")
                    .then(
                        Commands.argument("monto", LongArgumentType.longArg(1))
                            .executes { ctx ->
                                if (!checkEnabled(ctx.source)) return@executes 0
                                val player = ctx.source.playerOrException
                                val amount = LongArgumentType.getLong(ctx, "monto")
                                val (ok, msg) = AuctionManager.bidAsPlayer(player, amount)
                                if (ok) ctx.source.sendSuccess({ Component.literal(msg) }, false)
                                else ctx.source.sendFailure(Component.literal(msg))
                                1
                            }
                    )
            )
            .then(
                Commands.literal("gremio")
                    .then(
                        Commands.argument("monto", LongArgumentType.longArg(1))
                            .executes { ctx ->
                                if (!checkEnabled(ctx.source)) return@executes 0
                                val player = ctx.source.playerOrException
                                val amount = LongArgumentType.getLong(ctx, "monto")
                                val (ok, msg) = AuctionManager.bidAsGuild(player, amount)
                                if (ok) ctx.source.sendSuccess({ Component.literal(msg) }, false)
                                else ctx.source.sendFailure(Component.literal(msg))
                                1
                            }
                    )
            )
            .then(
                Commands.argument("monto", LongArgumentType.longArg(1))
                    .executes { ctx ->
                        if (!checkEnabled(ctx.source)) return@executes 0
                        val player = ctx.source.playerOrException
                        val amount = LongArgumentType.getLong(ctx, "monto")
                        val (ok, msg) = AuctionManager.bidAsPlayer(player, amount)
                        if (ok) ctx.source.sendSuccess({ Component.literal(msg) }, false)
                        else ctx.source.sendFailure(Component.literal(msg))
                        1
                    }
            )
            .executes { ctx ->
                if (!checkEnabled(ctx.source)) return@executes 0
                showAuctionInfo(ctx.source)
                1
            }

        dispatcher.register(pujarRoot)

        val subastaRoot = Commands.literal("subasta")
            .requires { AuctionManager.config.enabled || AdminAuth.isAdminSource(it) }
            .executes { ctx ->
                if (!checkEnabled(ctx.source)) return@executes 0
                showAuctionInfo(ctx.source)
                1
            }
            .then(
                Commands.literal("admin")
                    .requires { AdminAuth.isAdminSource(it) }
                    .then(
                        Commands.literal("estado")
                            .then(
                                Commands.literal("activar").executes { ctx ->
                                    AuctionManager.config.enabled = true
                                    AuctionManager.save()
                                    ctx.source.server.playerList.players.forEach {
                                        ctx.source.server.commands.sendCommands(it)
                                    }
                                    ctx.source.sendSuccess({ Component.literal("§a[Subastas] Sistema de subastas ACTIVADO.") }, true)
                                    1
                                }
                            )
                            .then(
                                Commands.literal("desactivar").executes { ctx ->
                                    AuctionManager.config.enabled = false
                                    if (AuctionManager.config.activeAuction?.active == true) {
                                        AuctionManager.cancelAuction(ctx.source.server, "Subastas deshabilitadas por administracion")
                                    }
                                    AuctionManager.save()
                                    ctx.source.server.playerList.players.forEach {
                                        ctx.source.server.commands.sendCommands(it)
                                    }
                                    ctx.source.sendSuccess({ Component.literal("§c[Subastas] Sistema de subastas DESACTIVADO para todos los jugadores.") }, true)
                                    1
                                }
                            )
                    )
                    .then(
                        Commands.literal("pedestal")
                            .then(
                                Commands.literal("fijar").executes { ctx ->
                                    val player = ctx.source.playerOrException
                                    val pos = player.position()
                                    val dim = player.level().dimension().location().toString()
                                    AuctionManager.setStand(pos, dim, ctx.source.server)
                                    ctx.source.sendSuccess({ Component.literal("§aPedestal de subasta configurado en (${pos.x.toInt()}, ${pos.y.toInt()}, ${pos.z.toInt()}) en $dim.") }, true)
                                    1
                                }
                            )
                            .then(
                                Commands.literal("limpiar").executes { ctx ->
                                    AuctionManager.clearStandEntities(ctx.source.server)
                                    ctx.source.sendSuccess({ Component.literal("§aPedestal limpiado de entidades residuales.") }, true)
                                    1
                                }
                            )
                    )
                    .then(
                        Commands.literal("crear")
                            .then(
                                Commands.literal("pokemon")
                                    .then(
                                        Commands.argument("ranura", IntegerArgumentType.integer(1, 6))
                                            .then(
                                                Commands.argument("precio_inicial", LongArgumentType.longArg(1))
                                                    .then(
                                                        Commands.argument("incremento", LongArgumentType.longArg(1))
                                                            .then(
                                                                Commands.argument("duracion_segundos", IntegerArgumentType.integer(30, 3600))
                                                                    .executes { ctx ->
                                                                        if (!checkEnabled(ctx.source)) return@executes 0
                                                                        val player = ctx.source.playerOrException
                                                                        val slot = IntegerArgumentType.getInteger(ctx, "ranura")
                                                                        val startBid = LongArgumentType.getLong(ctx, "precio_inicial")
                                                                        val inc = LongArgumentType.getLong(ctx, "incremento")
                                                                        val dur = IntegerArgumentType.getInteger(ctx, "duracion_segundos")

                                                                        val party = Cobblemon.storage.getParty(player)
                                                                        val pokemon = party.get(slot - 1)
                                                                        if (pokemon == null) {
                                                                            ctx.source.sendFailure(Component.literal("§cNo tienes ningun Pokemon en la ranura $slot de tu equipo."))
                                                                            return@executes 0
                                                                        }

                                                                        party.remove(pokemon)
                                                                        val ok = AuctionManager.createPokemonAuction(player, pokemon, startBid, inc, dur, ctx.source.server)
                                                                        if (!ok) {
                                                                            party.add(pokemon)
                                                                            ctx.source.sendFailure(Component.literal("§cYa existe una subasta activa."))
                                                                            return@executes 0
                                                                        }
                                                                        1
                                                                    }
                                                            )
                                                    )
                                            )
                                    )
                            )
                            .then(
                                Commands.literal("item")
                                    .then(
                                        Commands.argument("precio_inicial", LongArgumentType.longArg(1))
                                            .then(
                                                Commands.argument("incremento", LongArgumentType.longArg(1))
                                                    .then(
                                                        Commands.argument("duracion_segundos", IntegerArgumentType.integer(30, 3600))
                                                            .executes { ctx ->
                                                                if (!checkEnabled(ctx.source)) return@executes 0
                                                                val player = ctx.source.playerOrException
                                                                val startBid = LongArgumentType.getLong(ctx, "precio_inicial")
                                                                val inc = LongArgumentType.getLong(ctx, "incremento")
                                                                val dur = IntegerArgumentType.getInteger(ctx, "duracion_segundos")

                                                                val stack = player.mainHandItem
                                                                if (stack.isEmpty) {
                                                                    ctx.source.sendFailure(Component.literal("§cDebes sostener el item a subastar en tu mano principal."))
                                                                    return@executes 0
                                                                }

                                                                val auctionStack = stack.copy()
                                                                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)

                                                                val ok = AuctionManager.createItemAuction(player, auctionStack, startBid, inc, dur, ctx.source.server)
                                                                if (!ok) {
                                                                    player.setItemInHand(InteractionHand.MAIN_HAND, auctionStack)
                                                                    ctx.source.sendFailure(Component.literal("§cYa existe una subasta activa."))
                                                                    return@executes 0
                                                                }
                                                                1
                                                            }
                                                    )
                                            )
                                    )
                            )
                    )
                    .then(
                        Commands.literal("cancelar")
                            .executes { ctx ->
                                AuctionManager.cancelAuction(ctx.source.server, "Cancelada por ${ctx.source.player?.scoreboardName ?: "Consola"}")
                                ctx.source.sendSuccess({ Component.literal("§aSubasta cancelada exitosamente.") }, true)
                                1
                            }
                    )
            )

        dispatcher.register(subastaRoot)
    }

    private fun showAuctionInfo(source: CommandSourceStack) {
        val auction = AuctionManager.config.activeAuction
        if (auction == null || !auction.active) {
            source.sendSuccess({ Component.literal("§7No hay ninguna subasta activa actualmente.") }, false)
        } else {
            source.sendSuccess({ Component.literal("§6§l=== SUBASTA ACTIVA ===") }, false)
            source.sendSuccess({ Component.literal("§fObjeto/Pokemon: §b${auction.title}") }, false)
            source.sendSuccess({ Component.literal("§fPujador lider: §e${auction.bidderDisplay}") }, false)
            source.sendSuccess({ Component.literal("§fPuja actual: §a${auction.currentBid}$ §7(Minima siguiente: §e${auction.minNextBid}$§7)") }, false)
            source.sendSuccess({ Component.literal("§fTiempo restante: §c${auction.timeRemainingSeconds}s") }, false)
            source.sendSuccess({ Component.literal("§7Usa §b/pujar <monto> §7o §b/pujar gremio <monto>") }, false)
        }
    }
}
