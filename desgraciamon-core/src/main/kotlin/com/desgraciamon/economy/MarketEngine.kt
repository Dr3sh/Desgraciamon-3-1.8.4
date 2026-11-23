package com.desgraciamon.economy

import net.minecraft.server.MinecraftServer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

object MarketEngine {
    private val LN2 = ln(2.0)
    private val DAILY_ZONE = java.time.ZoneId.of("America/Caracas")

    private fun ensureDailyDate() {
        val today = java.time.LocalDate.now(DAILY_ZONE).toString()
        if (MarketStore.state.dailySellDate != today) {
            MarketStore.state.dailySellDate = today
            MarketStore.state.dailyPlayerEarnings.clear()
            MarketStore.saveState()
        }
    }

    @Synchronized
    fun remainingDailyCap(playerUuid: java.util.UUID): Long {
        val cap = MarketStore.config.dailyPlayerSellCap
        if (cap <= 0L) return Long.MAX_VALUE
        ensureDailyDate()
        val earned = MarketStore.state.dailyPlayerEarnings[playerUuid.toString()] ?: 0L
        return (cap - earned).coerceAtLeast(0L)
    }

    @Synchronized
    fun recordDailyEarnings(playerUuid: java.util.UUID, amount: Long) {
        if (amount <= 0L) return
        val cap = MarketStore.config.dailyPlayerSellCap
        if (cap <= 0L) return
        ensureDailyDate()
        val key = playerUuid.toString()
        val prev = MarketStore.state.dailyPlayerEarnings[key] ?: 0L
        MarketStore.state.dailyPlayerEarnings[key] = (prev + amount).coerceAtMost(cap)
        MarketStore.saveState()
    }

    fun getItemConfig(itemId: String): ItemMarketConfig? {
        return MarketStore.config.items[itemId]
    }

    fun getSupply(itemId: String): Double {
        return MarketStore.state.supply[itemId] ?: 0.0
    }

    fun isTrending(itemId: String): Boolean {
        return MarketStore.state.trendingItems.contains(itemId)
    }

    fun getTrendingMultiplier(itemId: String): Double {
        return if (isTrending(itemId)) 1.0 + (MarketStore.config.trendingBonusPercent / 100.0) else 1.0
    }

    /**
     * Calcula el precio instantáneo unitario actual en función del suministro acumulado S
     * y aplica el multiplicador de objeto en alza si corresponde.
     */
    @JvmOverloads
    fun calculateUnitPrice(item: ItemMarketConfig, supply: Double, isTrending: Boolean = false): Double {
        val k = item.saturationVolume.coerceAtLeast(1.0)
        val rawPrice = if (supply >= 0) {
            val factor = 2.0.pow(-supply / k)
            item.minPrice + (item.basePrice - item.minPrice) * factor
        } else {
            val factor = 1.0 - 2.0.pow(supply / k)
            item.basePrice + (item.maxPrice - item.basePrice) * factor
        }.coerceIn(item.minPrice, item.maxPrice)

        val multiplier = if (isTrending) 1.0 + (MarketStore.config.trendingBonusPercent / 100.0) else 1.0
        return rawPrice * multiplier
    }

    /**
     * Calcula la cotización completa para consultar con /precio o /mercado.
     */
    fun quote(itemId: String): PriceQuote? {
        val config = getItemConfig(itemId) ?: return null
        val currentSupply = getSupply(itemId)
        val trending = isTrending(itemId)
        val unitPrice = calculateUnitPrice(config, currentSupply, trending)
        val stackPayout = calculateBatchPayout(config, currentSupply, 64, trending)
        val deltaPercent = ((unitPrice - config.basePrice) / config.basePrice) * 100.0

        return PriceQuote(
            basePrice = config.basePrice,
            currentUnitPrice = unitPrice,
            stackPrice = stackPayout.toDouble(),
            trendPercent = deltaPercent,
            trendDisplay = formatTrend(deltaPercent),
            isTrending = trending
        )
    }

    /**
     * Realiza la venta de N unidades de forma estrictamente sincronizada (Thread-Safe):
     * previene colisiones y sincronizaciones entre jugadores concurrentes,
     * actualiza el suministro acumulado y devuelve el resultado detallado.
     */
    @Synchronized
    fun executeSale(itemId: String, count: Int): SaleResult? {
        if (count <= 0) return null
        val config = getItemConfig(itemId) ?: return null
        val initialSupply = getSupply(itemId)
        val trending = isTrending(itemId)

        val totalPayout = calculateBatchPayout(config, initialSupply, count, trending)
        val newSupply = initialSupply + count
        MarketStore.state.supply[itemId] = newSupply
        MarketStore.saveState()

        val newUnitPrice = calculateUnitPrice(config, newSupply, trending)
        val deltaPercent = ((newUnitPrice - config.basePrice) / config.basePrice) * 100.0
        val avgPrice = if (count > 0) totalPayout.toDouble() / count else newUnitPrice

        return SaleResult(
            amount = count,
            totalPayout = totalPayout,
            averageUnitPrice = avgPrice,
            newPrice = newUnitPrice,
            trendPercent = deltaPercent,
            trendDisplay = formatTrend(deltaPercent)
        )
    }

    /**
     * Integral exacta de la curva entre supply y supply + count con soporte para objetos en alza.
     */
    @JvmOverloads
    fun calculateBatchPayout(config: ItemMarketConfig, supply: Double, count: Int, isTrending: Boolean = false): Long {
        val k = config.saturationVolume.coerceAtLeast(1.0)
        val endSupply = supply + count

        val baseTotal: Double = when {
            supply >= 0 -> {
                integralPositive(config, supply, count, k)
            }
            endSupply <= 0 -> {
                integralNegative(config, supply, count, k)
            }
            else -> {
                val countToZero = -supply
                val countFromZero = endSupply
                integralNegative(config, supply, countToZero.toInt(), k) +
                        integralPositive(config, 0.0, countFromZero.toInt(), k)
            }
        }

        val multiplier = if (isTrending) 1.0 + (MarketStore.config.trendingBonusPercent / 100.0) else 1.0
        val finalTotal = baseTotal * multiplier
        return finalTotal.roundToLong().coerceAtLeast(count * 1L)
    }

    /**
     * Predice de forma dinámica el impacto de vender un lote sin alterar el estado real del mercado.
     * Incluye comparativa de precio unitario antes/después y precio de stack completo antes/después.
     */
    fun predictBatch(itemId: String, count: Int, initialSupply: Double = getSupply(itemId)): BatchPrediction? {
        if (count <= 0) return null
        val config = getItemConfig(itemId) ?: return null
        val trending = isTrending(itemId)

        val totalPayout = calculateBatchPayout(config, initialSupply, count, trending)
        val startPrice = calculateUnitPrice(config, initialSupply, trending)
        val endPrice = calculateUnitPrice(config, initialSupply + count, trending)
        val startStack = calculateBatchPayout(config, initialSupply, 64, trending).toDouble()
        val endStack = calculateBatchPayout(config, initialSupply + count, 64, trending).toDouble()

        val avgPrice = totalPayout.toDouble() / count
        val dropPercent = if (startPrice > 0.0) ((startPrice - endPrice) / startPrice) * 100.0 else 0.0

        return BatchPrediction(
            itemId = itemId,
            count = count,
            totalPayout = totalPayout,
            avgUnitPrice = avgPrice,
            startUnitPrice = startPrice,
            endUnitPrice = endPrice,
            startStackPrice = startStack,
            endStackPrice = endStack,
            priceDropPercent = dropPercent,
            isTrending = trending,
            trendingBonus = if (trending) MarketStore.config.trendingBonusPercent else 0.0
        )
    }

    private fun integralPositive(config: ItemMarketConfig, s0: Double, n: Int, k: Double): Double {
        val termMin = n * config.minPrice
        val scale = (config.basePrice - config.minPrice) * (k / LN2)
        val deltaFactor = 2.0.pow(-s0 / k) - 2.0.pow(-(s0 + n) / k)
        return termMin + (scale * deltaFactor)
    }

    private fun integralNegative(config: ItemMarketConfig, s0: Double, n: Int, k: Double): Double {
        val termMax = n * config.maxPrice
        val scale = (config.maxPrice - config.basePrice) * (k / LN2)
        val deltaFactor = 2.0.pow((s0 + n) / k) - 2.0.pow(s0 / k)
        return termMax - (scale * deltaFactor)
    }

    fun formatTrend(percent: Double): String {
        return when {
            percent >= 5.0 -> TextCatalog.string("trend.up", "percent" to String.format("%.1f", percent))
            percent <= -5.0 -> TextCatalog.string("trend.down", "percent" to String.format("%.1f", abs(percent)))
            else -> TextCatalog.string("trend.stable", "percent" to String.format("%.1f", percent))
        }
    }

    /**
     * Dispara un reseteo cíclico completo de la economía:
     * - Restaura la saturación acumulada a 0 (devolviendo todos los precios a sus valores base).
     * - Elige aleatoriamente los nuevos Objetos en Alza con bono de cotización.
     * - Sincroniza al instante con el banco CobbleDollars y todos los clientes.
     */
    @Synchronized
    fun triggerEconomyReset(server: MinecraftServer?, broadcastMessage: Boolean = true): List<String> {
        MarketStore.state.supply.clear()
        MarketStore.state.lastResetTimestampMs = System.currentTimeMillis()

        val allItems = MarketStore.config.items.keys.toList()
        val trendCount = MarketStore.config.trendingItemCount.coerceIn(1, allItems.size.coerceAtLeast(1))
        val newTrending = if (allItems.isNotEmpty()) allItems.shuffled().take(trendCount) else emptyList()

        MarketStore.state.trendingItems.clear()
        MarketStore.state.trendingItems.addAll(newTrending)
        MarketStore.saveState()

        CobbleDollarsBankSync.syncPrices()

        if (server != null) {
            MarketSyncServer.broadcastSync(server)
            if (broadcastMessage) {
                val trendNames = newTrending.map { id ->
                    MarketStore.config.items[id]?.displayName ?: id
                }.joinToString("§f, §e")
                val bonus = MarketStore.config.trendingBonusPercent.toInt()
                val msg = net.minecraft.network.chat.Component.literal(
                    "§6§l[BOLSA DE VALORES] §a¡El mercado se ha restablecido! §7Precios restaurados al valor inicial.\n" +
                    "§6§lOBJETOS EN ALZA (+${bonus}% de pago): §e$trendNames§f."
                )
                for (player in server.playerList.players) {
                    player.sendSystemMessage(msg)
                }
            }
        }
        return newTrending
    }

    /**
     * Resetea el suministro de un ítem específico a 0, o de todos los ítems si itemId es null.
     */
    @Synchronized
    fun resetSupply(itemId: String? = null) {
        if (itemId == null) {
            MarketStore.state.supply.clear()
        } else {
            MarketStore.state.supply.remove(itemId)
        }
        MarketStore.saveState()
        CobbleDollarsBankSync.syncPrices()
    }

    /**
     * Resetea el suministro de un ítem específico a 0, devolviendo su precio al valor base.
     */
    @Synchronized
    fun resetItemSupply(itemId: String) {
        resetSupply(itemId)
    }

    /**
     * Establece manualmente el precio base de un ítem.
     */
    @Synchronized
    fun setItemBasePrice(itemId: String, newBasePrice: Double) {
        val cfg = MarketStore.config.items[itemId] ?: return
        val ratioMin = if (cfg.basePrice > 0.0) cfg.minPrice / cfg.basePrice else 0.20
        val ratioMax = if (cfg.basePrice > 0.0) cfg.maxPrice / cfg.basePrice else 2.0
        cfg.basePrice = newBasePrice
        cfg.minPrice = newBasePrice * ratioMin
        cfg.maxPrice = newBasePrice * ratioMax
        MarketStore.saveConfig()
        CobbleDollarsBankSync.syncPrices()
    }

    /**
     * Modifica el precio de mercado actual de un ítem alterando su suministro artificialmente.
     * Un porcentaje positivo aumenta el precio (simula alta demanda), uno negativo lo reduce.
     */
    @Synchronized
    fun modifyPricePercentage(itemId: String, percentDelta: Double) {
        val cfg = getItemConfig(itemId) ?: return
        val currentSupply = getSupply(itemId)
        // Convertir cambio porcentual deseado en desplazamiento de suministro
        val shift = -(percentDelta / 100.0) * cfg.saturationVolume
        MarketStore.state.supply[itemId] = currentSupply + shift
        MarketStore.saveState()
        CobbleDollarsBankSync.syncPrices()
    }

    /**
     * Activa o desactiva manualmente la condición de objeto en alza para un ítem.
     */
    @Synchronized
    fun setTrending(itemId: String, enable: Boolean) {
        if (enable) {
            if (!MarketStore.state.trendingItems.contains(itemId)) {
                MarketStore.state.trendingItems.add(itemId)
            }
        } else {
            MarketStore.state.trendingItems.remove(itemId)
        }
        MarketStore.saveState()
        CobbleDollarsBankSync.syncPrices()
    }

    /**
     * Aplica el decaimiento de oferta/demanda hacia el equilibrio.
     */
    fun decayStep() {
        val rate = MarketStore.config.decayRate.coerceIn(0.001, 0.5)
        val iterator = MarketStore.state.supply.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val decayed = entry.value * (1.0 - rate)
            if (abs(decayed) < 0.1) {
                iterator.remove()
                changed = true
            } else {
                entry.setValue(decayed)
                changed = true
            }
        }
        if (changed) {
            MarketStore.saveState()
        }
    }
}
