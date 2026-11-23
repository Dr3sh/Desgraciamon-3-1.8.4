package com.desgraciamon.economy

import fr.harmex.cobbledollars.common.world.inventory.BankMenu
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object BankHook {
    // Control de debounce/cooldown para evitar spam de paquetes o ventas simultáneas
    private val lastPlayerSellMs = ConcurrentHashMap<UUID, Long>()

    @JvmStatic
    @Synchronized
    fun onBankSell(player: ServerPlayer): Boolean {
        val now = System.currentTimeMillis()
        val lastSell = lastPlayerSellMs[player.uuid] ?: 0L
        if (now - lastSell < 600L) {
            // Anti-spam / Cooldown: si intenta vender antes de 600ms, no reprocesar MarketEngine
            return false
        }

        val bankMenu = player.containerMenu as? BankMenu ?: return false
        val container = bankMenu.bankContainer

        val itemsToSell = mutableMapOf<String, Int>()
        val itemSlots = mutableMapOf<String, MutableList<Int>>()

        for (i in 0 until container.containerSize) {
            val stack = container.getItem(i)
            if (!stack.isEmpty) {
                val itemId = BuiltInRegistries.ITEM.getKey(stack.item).toString()
                if (MarketEngine.getItemConfig(itemId) != null) {
                    itemsToSell[itemId] = (itemsToSell[itemId] ?: 0) + stack.count
                    itemSlots.getOrPut(itemId) { mutableListOf() }.add(i)
                }
            }
        }

        if (itemsToSell.isEmpty()) {
            return false
        }

        val remainingCap = MarketEngine.remainingDailyCap(player.uuid)
        if (remainingCap <= 0L) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Economía] Has alcanzado tu límite diario de ganancias en el mercado (${MarketStore.config.dailyPlayerSellCap}$)."))
            return false
        }
        val estimatedTotal = itemsToSell.entries.sumOf { (itemId, count) ->
            MarketEngine.predictBatch(itemId, count)?.totalPayout ?: 0L
        }
        if (estimatedTotal > remainingCap) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Economía] Esta venta ($estimatedTotal$) supera tu límite diario restante ($remainingCap$)."))
            return false
        }

        lastPlayerSellMs[player.uuid] = now
        var totalEarned = 0L
        var soldAny = false

        // Procesamiento atómico en cola secuencial: cada venta afecta al suministro al instante,
        // garantizando que si dos jugadores venden al mismo tiempo, el segundo sufre la caída real de precio.
        for ((itemId, count) in itemsToSell) {
            val pred = MarketEngine.predictBatch(itemId, count)
            val result = MarketEngine.executeSale(itemId, count)
            if (result != null) {
                soldAny = true
                totalEarned += result.totalPayout
                itemSlots[itemId]?.forEach { slotIdx ->
                    container.setItem(slotIdx, ItemStack.EMPTY)
                }
                val config = MarketEngine.getItemConfig(itemId)
                val displayName = config?.displayName ?: itemId
                player.sendSystemMessage(
                    TextCatalog.component(
                        "sell.success",
                        "amount" to count,
                        "item" to displayName,
                        "total" to result.totalPayout,
                        "unit_price" to String.format("%.1f", result.averageUnitPrice),
                        "trend" to result.trendDisplay
                    )
                )

                // Alerta en chat global si hubo una gran caída de precio (>= 15%)
                if (pred != null && pred.priceDropPercent >= 15.0) {
                    val alertMsg = TextCatalog.component(
                        "market.alert.crash",
                        "player" to player.name.string,
                        "amount" to count,
                        "item" to displayName,
                        "percent" to String.format("%.1f", pred.priceDropPercent),
                        "price" to String.format("%.1f", result.newPrice)
                    )
                    for (target in player.server.playerList.players) {
                        if (MarketServerPreferences.isAlertsEnabled(target.uuid)) {
                            target.sendSystemMessage(alertMsg)
                        }
                    }
                }
            }
        }

        if (totalEarned > 0) {
            MarketEngine.recordDailyEarnings(player.uuid, totalEarned)
            CobbleDollarsBridge.deposit(player, totalEarned)
            runCatching {
                val dataClass = Class.forName("com.desgraciamon.discordbridge.DiscordLinkData")
                val getMethod = dataClass.getMethod("get", net.minecraft.server.MinecraftServer::class.java)
                val data = getMethod.invoke(null, player.server)
                dataClass.getMethod("recordMoneyEarned", UUID::class.java, Long::class.javaPrimitiveType).invoke(data, player.uuid, totalEarned)
            }
        }

        if (soldAny) {
            container.setChanged()
            bankMenu.broadcastChanges()
            // Sincronizar los nuevos precios calculados en el banquero y clientes
            CobbleDollarsBankSync.syncPrices()
            MarketSyncServer.broadcastSync(player.server)
        }

        // Si aún quedan otros ítems (no gestionados por MarketEngine), permitir que CobbleDollars procese el resto
        val hasRemainingItems = (0 until container.containerSize).any { !container.getItem(it).isEmpty }
        return soldAny && !hasRemainingItems
    }
}
