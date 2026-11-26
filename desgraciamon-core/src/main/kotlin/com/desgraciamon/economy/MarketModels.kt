package com.desgraciamon.economy

data class ItemMarketConfig(
    var basePrice: Double = 100.0,
    var minPrice: Double = basePrice * 0.20,
    var maxPrice: Double = basePrice * 2.0,
    var saturationVolume: Double = 64.0,
    var displayName: String? = null
)

data class MarketConfig(
    var decayIntervalMinutes: Int = 10,
    var decayRate: Double = 0.05,
    var resetIntervalHours: Int = 48,        // Compatibilidad heredada (horas)
    var resetIntervalDays: Int = 2,          // Cada cuántos días se reinicia el mercado
    var resetHourVet: Int = 8,               // Hora del reinicio en zona America/Caracas (0-23)
    var trendingBonusPercent: Double = 35.0,  // Bonus extra de pago para objetos en alza
    var trendingItemCount: Int = 3,           // Cantidad de objetos en alza simultáneos
    var dailyPlayerSellCap: Long = 0L,        // Sin límite diario de ventas
    var items: MutableMap<String, ItemMarketConfig> = mutableMapOf()
)

data class MarketState(
    var supply: MutableMap<String, Double> = mutableMapOf(),
    var lastResetTimestampMs: Long = System.currentTimeMillis(),
    var trendingItems: MutableList<String> = mutableListOf(),
    var dailySellDate: String = "",
    var dailyPlayerEarnings: MutableMap<String, Long> = mutableMapOf()
)

data class SaleResult(
    val amount: Int,
    val totalPayout: Long,
    val averageUnitPrice: Double,
    val newPrice: Double,
    val trendPercent: Double,
    val trendDisplay: String
)

data class BatchPrediction(
    val itemId: String,
    val count: Int,
    val totalPayout: Long,
    val avgUnitPrice: Double,
    val startUnitPrice: Double,
    val endUnitPrice: Double,
    val startStackPrice: Double,
    val endStackPrice: Double,
    val priceDropPercent: Double,
    val isTrending: Boolean = false,
    val trendingBonus: Double = 0.0
)

data class PriceQuote(
    val basePrice: Double,
    val currentUnitPrice: Double,
    val stackPrice: Double,
    val trendPercent: Double,
    val trendDisplay: String,
    val isTrending: Boolean = false
)
