package com.desgraciamon.vortex.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

data class VortexConfig(
    val durationSeconds: Int = 600,
    val blackMarketReward: Int = 150000,
    val anomalyLevel: Int = 50,
    val slowFallingDurationSeconds: Int = 5,
    val shaderFadeSeconds: Float = 15.0f,
    val cameraShakeIntensity: Float = 0.20f,
    val cobbleDollarsCommandTemplate: String = "cobbledollars give %player% %amount%",
    val allowedBuckets: List<String> = listOf("rare", "ultra-rare"),
    val excludedLabels: List<String> = listOf("legendary", "mythical", "ultra_beast", "paradox")
) {
    companion object {
        private val LOGGER = LoggerFactory.getLogger("Desgraciamon-Config")
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

        @Volatile
        var INSTANCE: VortexConfig = VortexConfig()
            private set

        fun getConfigFile(): File {
            val baseDir = FabricLoader.getInstance().configDir
            val newDir = baseDir.resolve("desgraciamon4_special_effects").toFile()
            val newFile = File(newDir, "config.json")
            if (newFile.exists() && newFile.length() > 5L) return newFile

            val legacyDir = baseDir.resolve("desgraciamon").toFile()
            val legacyFile = File(legacyDir, "config.json")
            if (legacyFile.exists() && legacyFile.length() > 5L) return legacyFile

            if (!newDir.exists()) {
                newDir.mkdirs()
            }
            return newFile
        }

        fun load(): VortexConfig {
            val file = getConfigFile()
            if (!file.exists() || file.length() < 5L) {
                val defaultConfig = VortexConfig()
                save(defaultConfig)
                INSTANCE = defaultConfig
                return defaultConfig
            }
            return try {
                file.reader(Charsets.UTF_8).use { reader ->
                    val raw = GSON.fromJson(reader, VortexConfig::class.java) ?: VortexConfig()
                    val loaded = if (raw.shaderFadeSeconds == 5.0f) raw.copy(shaderFadeSeconds = 12.0f) else raw
                    INSTANCE = loaded
                    save(loaded) // Ensure any new default fields are written out
                    loaded
                }
            } catch (e: Exception) {
                LOGGER.warn("Failed to load config, restoring defaults", e)
                val fallback = VortexConfig()
                save(fallback)
                INSTANCE = fallback
                fallback
            }
        }

        fun save(config: VortexConfig = INSTANCE) {
            try {
                val file = getConfigFile()
                file.parentFile?.mkdirs()
                file.writer(Charsets.UTF_8).use { writer ->
                    GSON.toJson(config, writer)
                }
            } catch (e: Exception) {
                LOGGER.error("Failed to save config", e)
            }
        }
    }
}

