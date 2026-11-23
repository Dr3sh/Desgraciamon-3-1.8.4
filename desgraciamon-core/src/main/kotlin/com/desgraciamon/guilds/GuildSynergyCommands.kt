package com.desgraciamon.guilds

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import java.util.UUID

object GuildSynergyCommands {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val applyTestCount = { ctx: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, count: Int ->
            val player = ctx.source.playerOrException
            setDebugBots(player.uuid, count)
            if (count > 0) {
                val xpBonus = getXpBonusPercent(count)
                val moneyBonus = getMoneyBonusPercent(count)
                ctx.source.sendSuccess({
                    Component.literal("§a[Gremios] §fModo prueba de sinergia configurado en §e$count §fcompañeros simulados (§b+$xpBonus% XP §f| §e+$moneyBonus% Dinero§f).")
                }, false)
            } else {
                ctx.source.sendSuccess({
                    Component.literal("§c[Gremios] §fModo prueba de sinergia §cDESACTIVADO§f.")
                }, false)
            }
            1
        }

        val synergyNode = Commands.literal("sinergia")
            .requires { it.hasPermission(2) }
            .executes { ctx ->
                val player = ctx.source.playerOrException
                val currentBots = getDebugBots(player.uuid)
                val next = if (currentBots > 0) 0 else 2
                applyTestCount(ctx, next)
            }
            .then(Commands.literal("test")
                .executes { ctx ->
                    val player = ctx.source.playerOrException
                    val currentBots = getDebugBots(player.uuid)
                    val next = if (currentBots > 0) 0 else 2
                    applyTestCount(ctx, next)
                }
                .then(Commands.argument("cantidad", IntegerArgumentType.integer(0, 100)).executes { ctx ->
                    val count = IntegerArgumentType.getInteger(ctx, "cantidad")
                    applyTestCount(ctx, count)
                })
            )
            .then(Commands.literal("probar")
                .executes { ctx ->
                    val player = ctx.source.playerOrException
                    val currentBots = getDebugBots(player.uuid)
                    val next = if (currentBots > 0) 0 else 2
                    applyTestCount(ctx, next)
                }
                .then(Commands.argument("cantidad", IntegerArgumentType.integer(0, 100)).executes { ctx ->
                    val count = IntegerArgumentType.getInteger(ctx, "cantidad")
                    applyTestCount(ctx, count)
                })
            )
            .then(Commands.literal("estado").executes { ctx ->
                val player = ctx.source.playerOrException
                val bots = getDebugBots(player.uuid)
                val count = getNearbyCount(player)
                val total = count + bots
                val xpBonus = getXpBonusPercent(total)
                val moneyBonus = getMoneyBonusPercent(total)
                ctx.source.sendSuccess({
                    Component.literal("§6[Gremios] §fEstado de Sinergia: §e$count §fmiembros reales + §b$bots simulados §f(Total: §e$total§f) → §b+$xpBonus% XP §f| §e+$moneyBonus% Dinero")
                }, false)
                1
            })
            .then(Commands.literal("parar").executes { ctx ->
                val player = ctx.source.playerOrException
                applyTestCount(ctx, 0)
            })
            .then(Commands.argument("cantidad", IntegerArgumentType.integer(0, 100)).executes { ctx ->
                val count = IntegerArgumentType.getInteger(ctx, "cantidad")
                applyTestCount(ctx, count)
            })

        val gremioNode = Commands.literal("gremio")
            .requires { it.hasPermission(2) }
            .then(synergyNode)

        val registeredGremio = dispatcher.register(gremioNode)
        dispatcher.register(Commands.literal("gremios").requires { it.hasPermission(2) }.redirect(registeredGremio))

        val registeredSynergy = dispatcher.register(synergyNode)
        dispatcher.register(Commands.literal("guildsynergy").requires { it.hasPermission(2) }.redirect(registeredSynergy))
        dispatcher.register(Commands.literal("synergy").requires { it.hasPermission(2) }.redirect(registeredSynergy))
    }

    private fun setDebugBots(uuid: UUID, count: Int) {
        runCatching {
            val clazz = Class.forName("net.desgraciamon.guilds.GuildSynergyManager")
            val method = clazz.getMethod("setDebugBots", UUID::class.java, Int::class.javaPrimitiveType)
            method.invoke(null, uuid, count)
        }
    }

    private fun getDebugBots(uuid: UUID): Int {
        return runCatching {
            val clazz = Class.forName("net.desgraciamon.guilds.GuildSynergyManager")
            val method = clazz.getMethod("getDebugBots", UUID::class.java)
            method.invoke(null, uuid) as Int
        }.getOrDefault(0)
    }

    private fun getNearbyCount(player: net.minecraft.server.level.ServerPlayer): Int {
        return runCatching {
            val clazz = Class.forName("net.desgraciamon.guilds.GuildSynergyManager")
            val method = clazz.getMethod("getNearbyCount", net.minecraft.server.level.ServerPlayer::class.java)
            method.invoke(null, player) as Int
        }.getOrDefault(0)
    }

    private fun getXpBonusPercent(count: Int): Int {
        return runCatching {
            val clazz = Class.forName("net.desgraciamon.guilds.GuildSynergyManager")
            val method = clazz.getMethod("getXpBonusPercent", Int::class.javaPrimitiveType)
            method.invoke(null, count) as Int
        }.getOrDefault(count * 5)
    }

    private fun getMoneyBonusPercent(count: Int): Int {
        return runCatching {
            val clazz = Class.forName("net.desgraciamon.guilds.GuildSynergyManager")
            val method = clazz.getMethod("getMoneyBonusPercent", Int::class.javaPrimitiveType)
            method.invoke(null, count) as Int
        }.getOrDefault(count * 3)
    }
}
