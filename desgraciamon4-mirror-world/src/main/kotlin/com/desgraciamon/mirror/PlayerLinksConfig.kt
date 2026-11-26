package com.desgraciamon.mirror

import com.google.gson.GsonBuilder
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.Method
import java.util.UUID

data class PlayerLinkEntry(
    var label: String = "",
    var username: String = "",
    var discordId: String = "",
    var memoryUuids: MutableList<String> = mutableListOf(),
)

data class PlayerLinksFile(
    var players: MutableList<PlayerLinkEntry> = mutableListOf(),
)

object PlayerLinksConfig {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val logger = LoggerFactory.getLogger("desgraciamon4-mirror")
    private val file = File("config/desgraciamon4_mirror/player_links.json")

    var data = PlayerLinksFile()
        private set

    fun load() {
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            data = defaultData()
            save()
            return
        }
        data = runCatching {
            file.reader(Charsets.UTF_8).use { gson.fromJson(it, PlayerLinksFile::class.java) } ?: defaultData()
        }.onFailure {
            logger.error("No se pudo leer ${file.path}", it)
        }.getOrDefault(defaultData())
        if (data.players.isEmpty()) {
            data = defaultData()
            save()
        }
    }

    fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(data), Charsets.UTF_8)
        }.onFailure {
            logger.error("No se pudo guardar ${file.path}", it)
        }
    }

    fun resolveMemoryUuids(discordId: String?, username: String?, playerUuid: UUID): List<String> {
        val trimmedDiscord = discordId?.trim().orEmpty()
        if (trimmedDiscord.isNotEmpty()) {
            val byDiscord = data.players.firstOrNull { it.discordId.trim() == trimmedDiscord }
            if (byDiscord != null && byDiscord.memoryUuids.isNotEmpty()) {
                return byDiscord.memoryUuids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            }
        }

        val trimmedUser = username?.trim().orEmpty()
        if (trimmedUser.isNotEmpty()) {
            val byUser = data.players.firstOrNull { it.username.trim().equals(trimmedUser, ignoreCase = true) }
            if (byUser != null && byUser.memoryUuids.isNotEmpty()) {
                return byUser.memoryUuids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            }
        }

        val uuidStr = playerUuid.toString()
        val byUuid = data.players.firstOrNull { entry ->
            entry.memoryUuids.any { it.trim().equals(uuidStr, ignoreCase = true) }
        }
        if (byUuid != null && byUuid.memoryUuids.isNotEmpty()) {
            return byUuid.memoryUuids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }

        val aliases = MirrorSettings.config.uuidAliases[uuidStr].orEmpty()
        return (listOf(uuidStr) + aliases).distinct()
    }

    private fun defaultData() = PlayerLinksFile(
        players = mutableListOf(
            PlayerLinkEntry(
                label = "fkdr3sh",
                username = "fkdr3sh",
                discordId = "1028488955961806978",
                memoryUuids = mutableListOf(
                    "024eb95f-c7f8-48c7-aacb-9896026aa33a",
                    "0c386e8b-d607-31f4-b012-ad927589466d",
                ),
            ),
            PlayerLinkEntry(
                label = "choi_beomgyuu",
                username = "choi_beomgyuu",
                discordId = "895359866309345300",
                memoryUuids = mutableListOf("551604b9-caf3-4aae-b94d-f0f739e90965"),
            ),
            PlayerLinkEntry(
                label = "Tatoman",
                username = "Tatoman",
                discordId = "1048023378235236412",
                memoryUuids = mutableListOf("5aee8228-f604-3585-8cc6-225e01956138"),
            ),
            PlayerLinkEntry(
                label = "pelele",
                username = "pelele",
                discordId = "982790668722270218",
                memoryUuids = mutableListOf("71e8f6a1-f9c4-3acc-b11f-90cb17d98f49"),
            ),
            PlayerLinkEntry(
                label = "Anthwank",
                username = "Anthwank",
                discordId = "389962167836999681",
                memoryUuids = mutableListOf("828ebab7-411d-3f31-a18d-7361a12cc6f9"),
            ),
            PlayerLinkEntry(
                label = "DaiTheYeezus",
                username = "DaiTheYeezus",
                discordId = "926971621233791016",
                memoryUuids = mutableListOf("e1b17736-eb95-3c12-9114-8bfcd7f93277"),
            ),
            PlayerLinkEntry(
                label = "Jugador 11 (sin asignar)",
                username = "",
                discordId = "",
                memoryUuids = mutableListOf("e555a427-7109-404f-bf6b-1221b6f5389e"),
            ),
            PlayerLinkEntry(
                label = "_Lotus",
                username = "_Lotus",
                discordId = "543562425484574730",
                memoryUuids = mutableListOf("e5c1a995-8484-3531-8b2c-675c4d120f96"),
            ),
        )
    )
}

object DiscordBridgeHook {
    private var resolved = false
    private var discordIdMethod: Method? = null

    fun getDiscordId(server: MinecraftServer, playerUuid: UUID): String? {
        if (!resolved) {
            resolved = true
            discordIdMethod = runCatching {
                val clazz = Class.forName("com.desgraciamon.discordbridge.DiscordLinkData")
                clazz.getMethod("discordId", MinecraftServer::class.java, UUID::class.java)
            }.getOrNull()
        }
        val method = discordIdMethod ?: return null
        return runCatching {
            method.invoke(null, server, playerUuid) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
