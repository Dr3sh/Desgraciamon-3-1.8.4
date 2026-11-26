package com.desgraciamon.antidupe

import com.google.gson.GsonBuilder
import org.slf4j.LoggerFactory
import java.io.File

data class AntiDupeConfig(
    var enabled: Boolean = true,
    var discordWebhookUrl: String = "",
    var removeConfirmedUuidDuplicates: Boolean = true,
    var ignoreCreativeAndAdmins: Boolean = true,
    var scanIntervalSeconds: Int = 15,
    var spikeWindowSeconds: Int = 45,
    var alertCooldownSeconds: Int = 30,
    var moneySpikeThreshold: Long = 250000L,
    var stampUnstackableItems: Boolean = true,
    var watchedItemSpikeThresholds: MutableMap<String, Int> = linkedMapOf(
        "cobblemon:master_ball" to 3,
        "cobblemon:ancient_origin_ball" to 3,
        "minecraft:netherite_ingot" to 24,
        "minecraft:nether_star" to 6,
        "cobblemon:rare_candy" to 32,
        "obc:bottle_cap_gold" to 6,
        "casinorocket:diamond_coin" to 10,
        "cobblemon-cards:god_pack_ticket" to 3,
        "cobblemon-cards:booster_pack" to 20
    )
)

object AntiDupeConfigStore {
    private val logger = LoggerFactory.getLogger("desgraciamon4-antidupe")
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val configFile = File("config/desgraciamon4_antidupe/config.json")

    var config: AntiDupeConfig = AntiDupeConfig()
        private set

    fun load() {
        configFile.parentFile?.mkdirs()
        config = if (configFile.exists()) {
            runCatching {
                configFile.reader(Charsets.UTF_8).use { gson.fromJson(it, AntiDupeConfig::class.java) }
            }.getOrElse {
                logger.error("Error leyendo config/desgraciamon4_antidupe/config.json, usando valores por defecto", it)
                AntiDupeConfig()
            }
        } else {
            val def = AntiDupeConfig()
            save(def)
            def
        }
        normalize()
    }

    private fun normalize() {
        if (config.scanIntervalSeconds !in 5..300) config.scanIntervalSeconds = 15
        if (config.spikeWindowSeconds !in 10..600) config.spikeWindowSeconds = 45
        if (config.alertCooldownSeconds !in 5..600) config.alertCooldownSeconds = 30
        if (config.moneySpikeThreshold < 1000L) config.moneySpikeThreshold = 250000L
        if (config.watchedItemSpikeThresholds == null) {
            config.watchedItemSpikeThresholds = AntiDupeConfig().watchedItemSpikeThresholds
        }
        if (config.discordWebhookUrl == null) {
            config.discordWebhookUrl = ""
        }
    }

    fun save(target: AntiDupeConfig = config) {
        runCatching {
            configFile.parentFile?.mkdirs()
            configFile.writeText(gson.toJson(target), Charsets.UTF_8)
        }.onFailure {
            logger.error("Error guardando config/desgraciamon4_antidupe/config.json", it)
        }
    }
}
