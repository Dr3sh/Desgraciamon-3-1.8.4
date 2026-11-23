package com.desgraciamon.discordbridge

import net.minecraft.server.MinecraftServer
import net.fabricmc.loader.api.FabricLoader
import java.util.UUID

internal object CooldownSettingsIntegration {
    private const val DATA_CLASS = "com.addoncobblemon.legendarycooldown.CooldownData"

    fun isInstalled(): Boolean = FabricLoader.getInstance().isModLoaded("legendary_cooldown")

    fun isCatchAssistantInstalled(): Boolean = FabricLoader.getInstance().isModLoaded("desgraciamon_catch_assistant")

    fun read(server: MinecraftServer, playerId: UUID, method: String): Boolean? {
        if (!isInstalled()) return null
        return runCatching {
            Class.forName(DATA_CLASS)
                .getMethod(method, MinecraftServer::class.java, UUID::class.java)
                .invoke(null, server, playerId) as Boolean
        }.getOrNull()
    }

    fun toggle(server: MinecraftServer, playerId: UUID, preference: String) {
        if (preference == "capture") {
            val enabled = readCaptureNotice(server, playerId) ?: return
            runCatching {
                Class.forName("com.desgraciamon.catchassistant.LegendaryCaptureData")
                    .getMethod("setCaptureNoticeEnabled", MinecraftServer::class.java, UUID::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(null, server, playerId, !enabled)
            }.onFailure {
                DiscordBridgeMod.logger.warn("Could not update optional capture notice preference", it)
            }
            return
        }
        if (!isInstalled()) return
        val getter = when (preference) {
            "bossbar" -> "isBossBarEnabled"
            "notice" -> "isJoinNoticeEnabled"
            else -> return
        }
        val setter = when (preference) {
            "bossbar" -> "setBossBarEnabled"
            "notice" -> "setJoinNoticeEnabled"
            else -> return
        }
        val current = read(server, playerId, getter) ?: return
        runCatching {
            Class.forName(DATA_CLASS)
                .getMethod(setter, MinecraftServer::class.java, UUID::class.java, Boolean::class.javaPrimitiveType)
                .invoke(null, server, playerId, !current)
        }.onFailure {
            DiscordBridgeMod.logger.warn("Could not update optional cooldown preference", it)
        }
    }

    fun readCaptureNotice(server: MinecraftServer, playerId: UUID): Boolean? {
        if (!isCatchAssistantInstalled()) return null
        return runCatching {
            Class.forName("com.desgraciamon.catchassistant.LegendaryCaptureData")
                .getMethod("isCaptureNoticeEnabled", MinecraftServer::class.java, UUID::class.java)
                .invoke(null, server, playerId) as Boolean
        }.getOrNull()
    }
}
