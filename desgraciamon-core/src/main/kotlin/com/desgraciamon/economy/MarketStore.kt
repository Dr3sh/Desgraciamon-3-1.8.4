package com.desgraciamon.economy

import com.google.gson.GsonBuilder
import org.slf4j.LoggerFactory
import java.io.File

object MarketStore {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val logger = LoggerFactory.getLogger("desgraciamon4-economy")

    private val configFile = File("config/desgraciamon4_economy/market.json")
        private var stateFile = File("config/desgraciamon4_economy/market_state.json")

    fun initForServer(server: net.minecraft.server.MinecraftServer) {
        val worldFolder = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
        val dir = worldFolder.resolve("data/desgraciamon4_economy").toFile()
        dir.mkdirs()
        stateFile = File(dir, "market_state.json")
        val legacy = File("config/desgraciamon4_economy/market_state.json")
        if (!stateFile.exists() && legacy.exists()) {
            runCatching { legacy.copyTo(stateFile, overwrite = false) }
        }
        load()
    }

    fun resetState() {
        state = MarketState()
    }

    var config: MarketConfig = MarketConfig()
        private set

    var state: MarketState = MarketState()
        private set

    fun load() {
        configFile.parentFile?.mkdirs()
        config = if (configFile.exists()) {
            runCatching {
                configFile.reader().use { gson.fromJson(it, MarketConfig::class.java) }
            }.getOrElse {
                logger.error("Error reading market.json, loading defaults", it)
                createDefaultConfig()
            }
        } else {
            val default = createDefaultConfig()
            configFile.writeText(gson.toJson(default))
            default
        }

        if (config.resetIntervalDays <= 0) {
            config.resetIntervalDays = 2
        } else {
            config.resetIntervalDays = config.resetIntervalDays.coerceIn(1, 30)
        }
        if (config.resetHourVet !in 0..23) {
            config.resetHourVet = 8
        }

        // Cargar estado de oferta/demanda
        state = if (stateFile.exists()) {
            runCatching {
                stateFile.reader().use { gson.fromJson(it, MarketState::class.java) }
            }.getOrElse {
                MarketState()
            }
        } else {
            MarketState()
        }
    }

    fun saveState() {
        runCatching {
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(gson.toJson(state))
        }.onFailure {
            logger.error("Error saving market_state.json", it)
        }
    }

    fun saveConfig() {
        runCatching {
            configFile.parentFile?.mkdirs()
            configFile.writeText(gson.toJson(config))
        }.onFailure {
            logger.error("Error saving market.json", it)
        }
    }

    fun reload(): Boolean {
        return runCatching {
            load()
            true
        }.getOrElse { false }
    }

    private fun createDefaultConfig(): MarketConfig {
        val cfg = MarketConfig(
            decayIntervalMinutes = 10,
            decayRate = 0.05,
            resetIntervalHours = 48,
            resetIntervalDays = 2,
            resetHourVet = 8
        )

        fun item(id: String, base: Double, minRatio: Double = 0.20, maxRatio: Double = 2.0, sat: Double = 64.0, name: String? = null) {
            cfg.items[id] = ItemMarketConfig(
                basePrice = base,
                minPrice = base * minRatio,
                maxPrice = base * maxRatio,
                saturationVolume = sat,
                displayName = name
            )
        }

        // Minerales y Vanilla
        item("minecraft:emerald", 75.0, 0.20, 2.0, 500.0, "Esmeralda")
        item("minecraft:emerald_block", 675.0, 0.20, 2.0, 64.0, "Bloque de Esmeralda")
        item("minecraft:elytra", 25000.0, 0.40, 2.0, 10.0, "Élitros")
        item("minecraft:iron_ingot", 10.0, 0.20, 2.5, 1000.0, "Lingote de Hierro")
        item("minecraft:gold_ingot", 25.0, 0.20, 2.5, 500.0, "Lingote de Oro")
        item("minecraft:diamond", 200.0, 0.25, 2.5, 200.0, "Diamante")
        item("minecraft:netherite_ingot", 3000.0, 0.35, 2.5, 32.0, "Lingote de Netherita")

        // Vitaminas y PP de Cobblemon (los precios base exactos que tiene CobbleDollars)
        item("cobblemon:pp_max", 3500.0, 0.20, 2.0, 32.0, "Más PP Máx")
        item("cobblemon:pp_up", 2500.0, 0.20, 2.0, 64.0, "Más PP")
        item("cobblemon:zinc", 2500.0, 0.20, 2.0, 64.0, "Zinc")
        item("cobblemon:iron", 2500.0, 0.20, 2.0, 64.0, "Hierro (Vitamina)")
        item("cobblemon:protein", 2500.0, 0.20, 2.0, 64.0, "Proteína")
        item("cobblemon:calcium", 2500.0, 0.20, 2.0, 64.0, "Calcio")
        item("cobblemon:hp_up", 2500.0, 0.20, 2.0, 64.0, "Más PS")
        item("cobblemon:carbos", 2500.0, 0.20, 2.0, 64.0, "Carburante")

        // Monedas Reliquia Cobblemon
        item("cobblemon:relic_coin", 50.0, 0.20, 2.0, 256.0, "Moneda Reliquia")
        item("cobblemon:relic_coin_pouch", 475.0, 0.20, 2.0, 64.0, "Bolsa de Monedas Reliquia")
        item("cobblemon:relic_coin_sack", 4500.0, 0.20, 2.0, 32.0, "Saco de Monedas Reliquia")

        // Pociones y Medicina Cobblemon
        item("cobblemon:potion", 50.0, 0.20, 2.0, 128.0, "Poción")
        item("cobblemon:super_potion", 175.0, 0.20, 2.0, 128.0, "Superpoción")
        item("cobblemon:hyper_potion", 375.0, 0.20, 2.0, 64.0, "Hiperpoción")
        item("cobblemon:max_potion", 625.0, 0.20, 2.0, 64.0, "Poción Máxima")
        item("cobblemon:full_restore", 750.0, 0.20, 2.0, 64.0, "Restaura Todo")
        item("cobblemon:ether", 300.0, 0.20, 2.0, 64.0, "Éter")
        item("cobblemon:max_ether", 500.0, 0.20, 2.0, 64.0, "Éter Máximo")
        item("cobblemon:elixir", 750.0, 0.20, 2.0, 64.0, "Elixir")
        item("cobblemon:max_elixir", 1125.0, 0.20, 2.0, 64.0, "Elixir Máximo")
        item("cobblemon:revive", 500.0, 0.20, 2.0, 64.0, "Revivir")
        item("cobblemon:max_revive", 1000.0, 0.20, 2.0, 64.0, "Revivir Máximo")

        // Plumas Lumymon
        item("lumymon:glacier_feather", 5000.0, 0.20, 2.0, 32.0, "Pluma Glaciar")
        item("lumymon:thunder_feather", 5000.0, 0.20, 2.0, 32.0, "Pluma Trueno")
        item("lumymon:ember_feather", 5000.0, 0.20, 2.0, 32.0, "Pluma Ascuas")
        item("lumymon:soul_feather", 30000.0, 0.35, 2.0, 10.0, "Pluma Alma")

        // Comida Cobblemon y Vanilla
        item("cobblemon:poke_cake", 120.0, 0.20, 2.0, 64.0, "Pastel Pokémon")
        item("cobblemon:old_gateau", 40.0, 0.20, 2.0, 64.0, "Gateau Viejo")
        item("cobblemon:smoked_tail_curry", 70.0, 0.20, 2.0, 64.0, "Curry Ahumado")
        item("minecraft:apple", 5.0, 0.20, 2.0, 256.0, "Manzana")
        item("minecraft:bread", 5.0, 0.20, 2.0, 256.0, "Pan")
        item("minecraft:cooked_beef", 20.0, 0.20, 2.0, 256.0, "Filete Cocinado")
        item("minecraft:golden_carrot", 20.0, 0.20, 2.0, 256.0, "Zanahoria Dorada")

        return cfg
    }
}

