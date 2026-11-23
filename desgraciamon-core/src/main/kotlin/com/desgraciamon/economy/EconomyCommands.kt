package com.desgraciamon.economy

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

object EconomyCommands {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val sellCommand = Commands.literal("vender")
            .executes { ctx -> executeSell(ctx.source.playerOrException, SellMode.HAND) }
            .then(Commands.literal("mano").executes { ctx ->
                executeSell(ctx.source.playerOrException, SellMode.HAND)
            })
            .then(Commands.literal("todo").executes { ctx ->
                executeSell(ctx.source.playerOrException, SellMode.ALL)
            })
            .then(Commands.argument("cantidad", IntegerArgumentType.integer(1)).executes { ctx ->
                val amount = IntegerArgumentType.getInteger(ctx, "cantidad")
                executeSell(ctx.source.playerOrException, SellMode.COUNT, amount)
            })

        val marketCommand = Commands.literal("mercado")
            .executes { ctx -> executeMarketOverview(ctx.source.playerOrException) }
            .then(Commands.literal("top").executes { ctx ->
                executeMarketTop(ctx.source.playerOrException)
            })
            .then(Commands.literal("precio").executes { ctx ->
                executePrice(ctx.source.playerOrException)
            })

        val economiaCommand = Commands.literal("economia")
            .requires { com.desgraciamon.badges.AdminAuth.isAdminSource(it) }
            .then(Commands.literal("reiniciar")
                .executes { ctx ->
                    MarketEngine.resetSupply(null)
                    CobbleDollarsBankSync.syncPrices()
                    MarketSyncServer.broadcastSync(ctx.source.server)
                    ctx.source.sendSuccess({ Component.literal("§aTodos los precios de mercado han sido reseteados a sus valores normales.") }, true)
                    1
                }
                .then(Commands.argument("item", StringArgumentType.word())
                    .suggests { _, builder ->
                        MarketStore.config.items.keys.forEach { builder.suggest(it) }
                        builder.buildFuture()
                    }
                    .executes { ctx ->
                        val item = StringArgumentType.getString(ctx, "item")
                        MarketEngine.resetItemSupply(item)
                        CobbleDollarsBankSync.syncPrices()
                        MarketSyncServer.broadcastSync(ctx.source.server)
                        ctx.source.sendSuccess({ Component.literal("§aEl precio de '§e$item§a' ha sido restaurado al valor base.") }, true)
                        1
                    }
                )
            )
            .then(Commands.literal("reiniciar_mercado")
                .executes { ctx ->
                    val newTrending = MarketEngine.triggerEconomyReset(ctx.source.server, broadcastMessage = true)
                    ctx.source.sendSuccess({ Component.literal("§aReseteo de ciclo completado. Precios base restaurados y nuevos objetos en alza definidos: §e${newTrending.joinToString(", ")}") }, true)
                    1
                }
            )
            .then(Commands.literal("establecer")
                .then(Commands.argument("item", StringArgumentType.word())
                    .suggests { _, builder ->
                        MarketStore.config.items.keys.forEach { builder.suggest(it) }
                        builder.buildFuture()
                    }
                    .then(Commands.argument("precio", DoubleArgumentType.doubleArg(1.0, 1000000.0))
                        .executes { ctx ->
                            val item = StringArgumentType.getString(ctx, "item")
                            val price = DoubleArgumentType.getDouble(ctx, "precio")
                            MarketEngine.setItemBasePrice(item, price)
                            MarketSyncServer.broadcastSync(ctx.source.server)
                            ctx.source.sendSuccess({ Component.literal("§aPrecio base de '§e$item§a' configurado a §e$price$§a.") }, true)
                            1
                        }
                    )
                )
            )
            .then(Commands.literal("modificar")
                .then(Commands.argument("item", StringArgumentType.word())
                    .suggests { _, builder ->
                        MarketStore.config.items.keys.forEach { builder.suggest(it) }
                        builder.buildFuture()
                    }
                    .then(Commands.argument("porcentaje", DoubleArgumentType.doubleArg(-90.0, 200.0))
                        .executes { ctx ->
                            val item = StringArgumentType.getString(ctx, "item")
                            val pct = DoubleArgumentType.getDouble(ctx, "porcentaje")
                            MarketEngine.modifyPricePercentage(item, pct)
                            MarketSyncServer.broadcastSync(ctx.source.server)
                            val action = if (pct >= 0) "subido en +$pct%" else "bajado en $pct%"
                            ctx.source.sendSuccess({ Component.literal("§aPrecio actual de '§e$item§a' $action.") }, true)
                            1
                        }
                    )
                )
            )
            .then(Commands.literal("alza")
                .then(Commands.argument("item", StringArgumentType.word())
                    .suggests { _, builder ->
                        MarketStore.config.items.keys.forEach { builder.suggest(it) }
                        builder.buildFuture()
                    }
                    .then(Commands.argument("activo", BoolArgumentType.bool())
                        .executes { ctx ->
                            val item = StringArgumentType.getString(ctx, "item")
                            val active = BoolArgumentType.getBool(ctx, "activo")
                            MarketEngine.setTrending(item, active)
                            MarketSyncServer.broadcastSync(ctx.source.server)
                            val status = if (active) "§aACTIVADO como Objeto en Alza" else "§cDESACTIVADO de Objetos en Alza"
                            ctx.source.sendSuccess({ Component.literal("§e$item $status.") }, true)
                            1
                        }
                    )
                )
            )
            .then(Commands.literal("config")
                .then(Commands.literal("horas_reinicio")
                    .then(Commands.argument("horas", IntegerArgumentType.integer(1, 168))
                        .executes { ctx ->
                            val hours = IntegerArgumentType.getInteger(ctx, "horas")
                            MarketStore.config.resetIntervalHours = hours
                            MarketStore.saveConfig()
                            MarketSyncServer.broadcastSync(ctx.source.server)
                            ctx.source.sendSuccess({ Component.literal("§aIntervalo de reseteo automatico de economia configurado a §e$hours horas§a.") }, true)
                            1
                        }
                    )
                )
                .then(Commands.literal("bono_alza")
                    .then(Commands.argument("porcentaje", DoubleArgumentType.doubleArg(5.0, 200.0))
                        .executes { ctx ->
                            val bonus = DoubleArgumentType.getDouble(ctx, "porcentaje")
                            MarketStore.config.trendingBonusPercent = bonus
                            MarketStore.saveConfig()
                            CobbleDollarsBankSync.syncPrices()
                            MarketSyncServer.broadcastSync(ctx.source.server)
                            ctx.source.sendSuccess({ Component.literal("§aBonus para objetos en alza configurado a §e+$bonus%§a.") }, true)
                            1
                        }
                    )
                )
            )
            .then(Commands.literal("banquero")
                .executes { ctx ->
                    val player = ctx.source.playerOrException
                    val success = BuilderMerchantSpawner.spawn(player, noAi = true)
                    if (success) {
                        ctx.source.sendSuccess({ Component.literal("§aMaestro Constructor spawneado correctamente.") }, true)
                        1
                    } else {
                        ctx.source.sendFailure(Component.literal("§cError al spawnear el Maestro Constructor."))
                        0
                    }
                }
                .then(Commands.literal("movil").executes { ctx ->
                    val player = ctx.source.playerOrException
                    val success = BuilderMerchantSpawner.spawn(player, noAi = false)
                    if (success) {
                        ctx.source.sendSuccess({ Component.literal("§aMaestro Constructor (con movimiento) spawneado correctamente.") }, true)
                        1
                    } else {
                        ctx.source.sendFailure(Component.literal("§cError al spawnear el Maestro Constructor."))
                        0
                    }
                })
                .then(Commands.literal("recargar").executes { ctx ->
                    val shop = BuilderMerchantSpawner.loadBuilderShop()
                    ctx.source.sendSuccess({ Component.literal("§aTienda de construccion recargada (${shop.size} categorias).") }, true)
                    1
                })
            )
            .then(Commands.literal("recargar")
                .executes { ctx ->
                    MarketStore.reload()
                    TextCatalog.reload()
                    CobbleDollarsBankSync.syncPrices()
                    ctx.source.sendSuccess({ TextCatalog.component("market.reload") }, true)
                    1
                }
            )

        dispatcher.register(sellCommand)
        dispatcher.register(marketCommand)
        dispatcher.register(economiaCommand)
    }

    private enum class SellMode {
        HAND, ALL, COUNT
    }

    private val lastCommandSellMs = java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long>()

    @Synchronized
    private fun executeSell(player: ServerPlayer, mode: SellMode, requestedAmount: Int = 0): Int {
        if (!player.isAlive || player.isSpectator) return 0
        val now = System.currentTimeMillis()
        val lastSell = lastCommandSellMs[player.uuid] ?: 0L
        if (now - lastSell < 600L) return 0

        val mainHand = player.mainHandItem
        if (mainHand.isEmpty) {
            player.sendSystemMessage(TextCatalog.component("sell.empty_hand"))
            return 0
        }

        val itemId = BuiltInRegistries.ITEM.getKey(mainHand.item).toString()
        val config = MarketEngine.getItemConfig(itemId)
        if (config == null) {
            player.sendSystemMessage(TextCatalog.component("sell.not_accepted"))
            return 0
        }

        val totalAvailable = countItem(player, mainHand.item)
        if (totalAvailable <= 0) {
            player.sendSystemMessage(TextCatalog.component("sell.no_items"))
            return 0
        }

        val countToSell = when (mode) {
            SellMode.HAND -> mainHand.count
            SellMode.ALL -> totalAvailable
            SellMode.COUNT -> minOf(requestedAmount, totalAvailable)
        }

        if (countToSell <= 0) {
            player.sendSystemMessage(TextCatalog.component("sell.no_items"))
            return 0
        }

        val prediction = MarketEngine.predictBatch(itemId, countToSell)
        val remainingCap = MarketEngine.remainingDailyCap(player.uuid)
        if (remainingCap <= 0L) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Economía] Has alcanzado tu límite diario de ganancias en el mercado (${MarketStore.config.dailyPlayerSellCap}$)."))
            return 0
        }
        if (prediction != null && prediction.totalPayout > remainingCap) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Economía] Esta venta (${prediction.totalPayout}$) supera tu límite diario restante ($remainingCap$). Vende menos cantidad."))
            return 0
        }

        lastCommandSellMs[player.uuid] = now
        val removed = removeItem(player, mainHand.item, countToSell)
        if (removed <= 0) {
            player.sendSystemMessage(TextCatalog.component("sell.no_items"))
            return 0
        }

        val result = MarketEngine.executeSale(itemId, removed)
        if (result == null) {
            // Si por alguna razón el mercado no acepta, devolver los ítems retirados
            player.inventory.add(net.minecraft.world.item.ItemStack(mainHand.item, removed))
            player.sendSystemMessage(TextCatalog.component("sell.not_accepted"))
            return 0
        }

        MarketEngine.recordDailyEarnings(player.uuid, result.totalPayout)
        CobbleDollarsBridge.deposit(player, result.totalPayout)
        CobbleDollarsBankSync.syncPrices()
        MarketSyncServer.broadcastSync(player.server)

            val displayName = config.displayName ?: mainHand.hoverName.string
            player.sendSystemMessage(
                TextCatalog.component(
                    "sell.success", "amount" to removed,
                    "item" to displayName,
                    "total" to result.totalPayout,
                    "unit_price" to String.format("%.1f", result.averageUnitPrice),
                    "trend" to result.trendDisplay
                )
            )

            // Alerta global si hubo una gran caída de precio (>= 15%)
            if (prediction != null && prediction.priceDropPercent >= 15.0) {
                val alertMsg = TextCatalog.component(
                    "market.alert.crash", "player" to player.name.string,
                    "amount" to removed,
                    "item" to displayName,
                    "percent" to String.format("%.1f", prediction.priceDropPercent),
                    "price" to String.format("%.1f", result.newPrice)
                )
                for (target in player.server.playerList.players) {
                    if (MarketServerPreferences.isAlertsEnabled(target.uuid)) {
                        target.sendSystemMessage(alertMsg)
                    }
                }
            }

        return removed
    }

    private fun executePrice(player: ServerPlayer): Int {
        val mainHand = player.mainHandItem
        if (mainHand.isEmpty) {
            player.sendSystemMessage(TextCatalog.component("sell.empty_hand"))
            return 0
        }

        val itemId = BuiltInRegistries.ITEM.getKey(mainHand.item).toString()
        val quote = MarketEngine.quote(itemId)
        if (quote == null) {
            player.sendSystemMessage(TextCatalog.component("sell.not_accepted"))
            return 0
        }

        val config = MarketEngine.getItemConfig(itemId)
        val itemName = config?.displayName ?: mainHand.hoverName.string
        val trendingBonus = if (quote.isTrending) " §6[EN ALZA +${MarketStore.config.trendingBonusPercent.toInt()}%]" else ""

        player.sendSystemMessage(
            TextCatalog.component(
                "price.info", "item" to (itemName + trendingBonus),
                "base_price" to String.format("%.0f", quote.basePrice),
                "unit_price" to String.format("%.1f", quote.currentUnitPrice),
                "stack_price" to String.format("%.0f", quote.stackPrice),
                "trend" to quote.trendDisplay
            )
        )
        return 1
    }

    private fun executeMarketOverview(player: ServerPlayer): Int {
        player.sendSystemMessage(TextCatalog.component("market.header"))

        // Temporizador de reseteo general
        val nextReset = MarketStore.state.lastResetTimestampMs + (MarketStore.config.resetIntervalHours * 3600000L)
        val diffMs = (nextReset - System.currentTimeMillis()).coerceAtLeast(0L)
        val hours = diffMs / 3600000L
        val mins = (diffMs % 3600000L) / 60000L
        player.sendSystemMessage(Component.literal("§8Próximo reseteo cíclico de bolsa: §e${hours}h ${mins}m §7(cada ${MarketStore.config.resetIntervalHours}h)"))

        // Objetos en alza
        val trending = MarketStore.state.trendingItems
        if (trending.isNotEmpty()) {
            val bonus = MarketStore.config.trendingBonusPercent.toInt()
            val trendNames = trending.map { id ->
                MarketStore.config.items[id]?.displayName ?: id
            }.joinToString("§f, §e")
            player.sendSystemMessage(Component.literal("§6OBJETOS EN ALZA (+${bonus}% de pago): §e$trendNames"))
        }

        // Mostrar un resumen de los ítems principales
        val itemsToShow = MarketStore.config.items.entries.take(12)
        for ((id, itemConfig) in itemsToShow) {
            val quote = MarketEngine.quote(id) ?: continue
            val trendBadge = if (quote.isTrending) " §6[ALZA]" else ""
            val name = (itemConfig.displayName ?: id) + trendBadge
            player.sendSystemMessage(
                TextCatalog.component(
                    "market.row", "item" to name,
                    "price" to String.format("%.0f", quote.currentUnitPrice),
                    "trend" to quote.trendDisplay
                )
            )
        }
        return 1
    }

    private fun executeMarketTop(player: ServerPlayer): Int {
        player.sendSystemMessage(TextCatalog.component("market.header"))

        val quotes = MarketStore.config.items.mapNotNull { (id, cfg) ->
            val q = MarketEngine.quote(id) ?: return@mapNotNull null
            (cfg.displayName ?: id) to q
        }

        val topGainers = quotes.sortedByDescending { it.second.trendPercent }.take(3)
        val topLosers = quotes.sortedBy { it.second.trendPercent }.take(3)

        player.sendSystemMessage(RichText.parse("<color=#55FF55><bold>+ ÍTEMS MÁS COTIZADOS (AL ALZA):</bold></color>", emptyMap()))
        for ((name, q) in topGainers) {
            player.sendSystemMessage(
                TextCatalog.component(
                    "market.row", "item" to name,
                    "price" to String.format("%.0f", q.currentUnitPrice),
                    "trend" to q.trendDisplay
                )
            )
        }

        player.sendSystemMessage(RichText.parse("<color=#FF5555><bold>- ÍTEMS MÁS SATURADOS (A LA BAJA):</bold></color>", emptyMap()))
        for ((name, q) in topLosers) {
            player.sendSystemMessage(
                TextCatalog.component(
                    "market.row", "item" to name,
                    "price" to String.format("%.0f", q.currentUnitPrice),
                    "trend" to q.trendDisplay
                )
            )
        }
        return 1
    }

    private fun countItem(player: ServerPlayer, item: Item): Int {
        var count = 0
        for (i in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(i)
            if (stack.item == item) count += stack.count
        }
        return count
    }

    private fun removeItem(player: ServerPlayer, item: Item, amountToRemove: Int): Int {
        var remaining = amountToRemove
        for (i in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(i)
            if (stack.item == item) {
                val toTake = minOf(remaining, stack.count)
                stack.shrink(toTake)
                remaining -= toTake
                if (remaining <= 0) break
            }
        }
        player.inventory.setChanged()
        return amountToRemove - remaining
    }
}
