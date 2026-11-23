package com.desgraciamon.economy

import fr.harmex.cobbledollars.common.client.gui.screen.BankScreen
import net.minecraft.core.registries.BuiltInRegistries
import java.math.BigInteger

data class BankPredictionSummary(
    val itemId: String,
    val displayName: String,
    val count: Int,
    val totalPayout: Long,
    val avgUnitPrice: Double,
    val startUnitPrice: Double,
    val endUnitPrice: Double,
    val startStackPrice: Double,
    val endStackPrice: Double,
    val dropPercent: Double,
    val isTrending: Boolean = false,
    val trendingBonus: Double = 0.0
)

object BankScreenHelper {
    var lastPredictions: List<BankPredictionSummary> = emptyList()
        private set
    var totalPayout: Long = 0L
        private set

    fun onBankContainerChanged(screen: BankScreen) {
        val container = screen.menu.bankContainer
        val counts = linkedMapOf<String, Int>()

        for (i in 0 until container.containerSize) {
            val stack = container.getItem(i)
            if (!stack.isEmpty) {
                val id = BuiltInRegistries.ITEM.getKey(stack.item).toString()
                counts[id] = (counts[id] ?: 0) + stack.count
            }
        }

        var sum = 0L
        val list = mutableListOf<BankPredictionSummary>()

        for ((id, count) in counts) {
            val config = MarketEngine.getItemConfig(id)
            val name = config?.displayName ?: id
            val pred = MarketEngine.predictBatch(id, count)
            if (pred != null) {
                sum += pred.totalPayout
                list.add(
                    BankPredictionSummary(
                        itemId = id,
                        displayName = name,
                        count = count,
                        totalPayout = pred.totalPayout,
                        avgUnitPrice = pred.avgUnitPrice,
                        startUnitPrice = pred.startUnitPrice,
                        endUnitPrice = pred.endUnitPrice,
                        startStackPrice = pred.startStackPrice,
                        endStackPrice = pred.endStackPrice,
                        dropPercent = pred.priceDropPercent,
                        isTrending = pred.isTrending,
                        trendingBonus = pred.trendingBonus
                    )
                )
            }
        }

        totalPayout = sum
        lastPredictions = list
        screen.sellPrice = BigInteger.valueOf(sum)
    }
}
