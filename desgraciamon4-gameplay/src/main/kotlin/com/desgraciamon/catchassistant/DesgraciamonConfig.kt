package com.desgraciamon.catchassistant

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

data class DesgraciamonConfig(
    var enabled: Boolean = true,
    var discordWebhookUrl: String = "",
    var discordWebhookName: String = "",
    var discordWebhookAvatarUrl: String = "",
    var cooldownHours: Long = 48L,
    var showOnlyLegendary: Boolean = true,
    var cardTitle: String = "Legendary Catch Assistant"
) {
    companion object {
        private val configFile = File("config/desgraciamon-catch-assistant.properties")
        val current = DesgraciamonConfig()

        fun load() {
            if (!configFile.parentFile.exists()) configFile.parentFile.mkdirs()
            if (!configFile.exists()) {
                val defaults = DesgraciamonConfig()
                save(defaults)
                return
            }

            val props = Properties()
            FileInputStream(configFile).use { props.load(it) }

            current.enabled = props.getProperty("enabled", "true").toBooleanStrictOrNull() ?: true
            current.discordWebhookUrl = props.getProperty("discordWebhookUrl", "")
            current.discordWebhookName = props.getProperty("discordWebhookName", "")
            current.discordWebhookAvatarUrl = props.getProperty("discordWebhookAvatarUrl", "")
            current.cooldownHours = props.getProperty("cooldownHours", "48").toLongOrNull()?.coerceIn(0L, 87_600L) ?: 48L
            current.showOnlyLegendary = props.getProperty("showOnlyLegendary", "true").toBooleanStrictOrNull() ?: true
            current.cardTitle = props.getProperty("cardTitle", "Legendary Catch Assistant")
        }

        fun save(config: DesgraciamonConfig = current) {
            if (!configFile.parentFile.exists()) configFile.parentFile.mkdirs()
            val props = Properties()
            props.setProperty("enabled", config.enabled.toString())
            props.setProperty("discordWebhookUrl", config.discordWebhookUrl)
            props.setProperty("discordWebhookName", config.discordWebhookName)
            props.setProperty("discordWebhookAvatarUrl", config.discordWebhookAvatarUrl)
            props.setProperty("cooldownHours", config.cooldownHours.toString())
            props.setProperty("showOnlyLegendary", config.showOnlyLegendary.toString())
            props.setProperty("cardTitle", config.cardTitle)

            FileOutputStream(configFile).use { props.store(it, "Desgraciamon Catch Assistant config") }
        }
    }
}
