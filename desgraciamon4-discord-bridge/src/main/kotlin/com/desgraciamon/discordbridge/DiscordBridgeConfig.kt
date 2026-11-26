package com.desgraciamon.discordbridge

import java.io.File
import java.util.Properties

data class DiscordBridgeConfig(
    var botToken: String = "",
    var guildId: String = "",
    var skinChannelId: String = "",
    var hitosRoleId: String = "",
    var adminUserIds: String = ""
) {
    fun isAdminUser(userId: String?): Boolean {
        if (userId.isNullOrBlank()) return false
        val configured = adminUserIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return configured.contains(userId.trim())
    }

    fun hasConfiguredAdminUsers(): Boolean {
        return adminUserIds.split(',').any { it.trim().isNotEmpty() }
    }

    fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val properties = Properties()
            if (file.exists()) {
                file.inputStream().use(properties::load)
            }
            properties.setProperty("botToken", botToken)
            properties.setProperty("guildId", guildId)
            properties.setProperty("skinChannelId", skinChannelId)
            properties.setProperty("hitosRoleId", hitosRoleId)
            properties.setProperty("adminUserIds", adminUserIds)
            file.outputStream().use { properties.store(it, "Desgraciamon 4 Discord Bridge Configuration") }
        }.onFailure {
            DiscordBridgeMod.logger.error("Could not save Discord Bridge config", it)
        }
    }

    companion object {
        private val file = File("config/desgraciamon4-discord-bridge.properties")

        fun load(): DiscordBridgeConfig {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText("# Keep the bot token private. Restart the server after changing this file.\nbotToken=\nguildId=\nskinChannelId=\nhitosRoleId=\nadminUserIds=\n")
                return DiscordBridgeConfig()
            }
            return runCatching {
                val properties = Properties().apply { file.inputStream().use(::load) }
                DiscordBridgeConfig(
                    botToken = properties.getProperty("botToken", "").trim(),
                    guildId = properties.getProperty("guildId", "").trim(),
                    skinChannelId = properties.getProperty("skinChannelId", "").trim(),
                    hitosRoleId = properties.getProperty("hitosRoleId", "").trim(),
                    adminUserIds = properties.getProperty("adminUserIds", "").trim()
                )
            }.getOrElse {
                DiscordBridgeMod.logger.error("Could not read Discord Bridge config", it)
                DiscordBridgeConfig()
            }
        }
    }
}
