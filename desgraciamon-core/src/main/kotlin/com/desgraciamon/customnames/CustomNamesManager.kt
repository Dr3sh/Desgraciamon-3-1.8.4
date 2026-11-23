package com.desgraciamon.customnames

import com.desgraciamon.badges.RichText
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object CustomNamesManager {
    private val LOGGER = LoggerFactory.getLogger("DesgraciamonCustomNamesManager")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val namesByUuid = ConcurrentHashMap<UUID, String>()
    private val namesByUsername = ConcurrentHashMap<String, String>()
    private val usernameByUuid = ConcurrentHashMap<UUID, String>()

    private var storageFile: File? = null
    var currentServer: MinecraftServer? = null
        private set

    fun initForServer(server: MinecraftServer) {
        currentServer = server
        val worldDir = server.getWorldPath(LevelResource.ROOT)
        val worldJson = worldDir.resolve("customnames.json").toFile()
        val rootJson = File("customnames.json")
        val configJson = File("config/customnames.json")

        // Migración automática de cualquier archivo guardado por error previo en la raíz
        if (!worldJson.exists()) {
            if (rootJson.exists()) {
                try {
                    worldJson.parentFile?.mkdirs()
                    rootJson.copyTo(worldJson, overwrite = true)
                    rootJson.delete()
                    LOGGER.info("Se migró customnames.json de la raíz local al mundo: ${worldJson.absolutePath}")
                } catch (e: Exception) {
                    LOGGER.error("Error al migrar customnames.json desde la raíz", e)
                }
            } else if (configJson.exists()) {
                try {
                    worldJson.parentFile?.mkdirs()
                    configJson.copyTo(worldJson, overwrite = true)
                    configJson.delete()
                    LOGGER.info("Se migró customnames.json de config al mundo: ${worldJson.absolutePath}")
                } catch (e: Exception) {
                    LOGGER.error("Error al migrar customnames.json desde config", e)
                }
            }
        } else {
            if (rootJson.exists()) {
                runCatching { rootJson.delete() }
            }
        }

        storageFile = worldJson
        load()
        syncToAll(server)
        GuildNicknamesBridge.syncAllGuildNicknames(server)
    }

    fun resetServer() {
        currentServer = null
        storageFile = null
    }

    fun load() {
        val file = storageFile ?: currentServer?.getWorldPath(LevelResource.ROOT)?.resolve("customnames.json")?.toFile()
        if (file == null) {
            LOGGER.warn("No hay archivo de guardado ni servidor disponible para cargar customnames.json.")
            return
        }

        namesByUuid.clear()
        namesByUsername.clear()
        usernameByUuid.clear()

        if (!file.exists()) {
            createTemplateFile(file)
            return
        }

        try {
            val content = file.readText(StandardCharsets.UTF_8)
            val jsonElement = JsonParser.parseString(content)
            if (!jsonElement.isJsonObject) return
            val root = jsonElement.asJsonObject

            for ((key, elem) in root.entrySet()) {
                if (key.startsWith("_")) continue

                var nickname: String? = null
                var username: String? = null

                if (elem.isJsonPrimitive && elem.asJsonPrimitive.isString) {
                    nickname = elem.asString
                } else if (elem.isJsonObject) {
                    val obj = elem.asJsonObject
                    if (obj.has("nickname")) nickname = obj.get("nickname").asString
                    if (obj.has("username")) username = obj.get("username").asString
                }

                if (nickname.isNullOrBlank()) continue

                val uuid = runCatching { UUID.fromString(key) }.getOrNull()
                if (uuid != null) {
                    namesByUuid[uuid] = nickname
                    if (username != null) {
                        usernameByUuid[uuid] = username
                        namesByUsername[username.lowercase()] = nickname
                    }
                } else {
                    namesByUsername[key.lowercase()] = nickname
                }
            }

            // Asociar nombres de usuario con UUIDs de jugadores online o en cache si es posible
            currentServer?.let { server ->
                for ((uName, nick) in namesByUsername) {
                    val profile = server.profileCache?.get(uName)?.orElse(null)
                    if (profile != null) {
                        namesByUuid[profile.id] = nick
                        usernameByUuid[profile.id] = profile.name
                    }
                }
            }

            LOGGER.info("Se cargaron ${namesByUuid.size} nombres personalizados desde ${file.absolutePath}.")
        } catch (e: Exception) {
            LOGGER.error("Error al cargar customnames.json", e)
        }
    }

    private fun createTemplateFile(file: File) {
        try {
            file.parentFile?.mkdirs()
            val template = JsonObject()
            template.addProperty(
                "_info",
                "Guarda los apodos personalizados. Admins pueden usar <prism=#HEX1,#HEX2>Texto</prism> o <color=#HEX>Texto</color>."
            )
            file.writeText(gson.toJson(template), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            LOGGER.error("Error al crear plantilla customnames.json", e)
        }
    }

    fun save() {
        val file = storageFile ?: currentServer?.getWorldPath(LevelResource.ROOT)?.resolve("customnames.json")?.toFile()
        if (file == null) {
            LOGGER.warn("No se pudo guardar customnames.json: no hay archivo de almacenamiento ni servidor activo.")
            return
        }

        try {
            file.parentFile?.mkdirs()
            val root = JsonObject()
            root.addProperty(
                "_info",
                "Guarda los apodos personalizados. Admins pueden usar <prism=#HEX1,#HEX2>Texto</prism> o <color=#HEX>Texto</color>."
            )

            // Guardar por UUID
            for ((uuid, nick) in namesByUuid) {
                val entry = JsonObject()
                val uname = usernameByUuid[uuid] ?: currentServer?.playerList?.getPlayer(uuid)?.gameProfile?.name
                if (uname != null) entry.addProperty("username", uname)
                entry.addProperty("nickname", nick)
                root.add(uuid.toString(), entry)
            }

            // Guardar por username si aún no se ha resuelto su UUID
            for ((uname, nick) in namesByUsername) {
                val hasUuid = namesByUuid.values.any { it == nick }
                if (!hasUuid) {
                    val entry = JsonObject()
                    entry.addProperty("username", uname)
                    entry.addProperty("nickname", nick)
                    root.add(uname, entry)
                }
            }

            val tempFile = java.io.File(file.parentFile, "customnames.json.tmp")
            tempFile.writeText(gson.toJson(root), StandardCharsets.UTF_8)
            java.nio.file.Files.move(tempFile.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            LOGGER.error("Error al guardar customnames.json", e)
        }
    }

    fun getRaw(uuid: UUID): String? {
        val direct = namesByUuid[uuid]
        if (direct != null) return direct

        // Verificar por nombre de usuario
        val player = currentServer?.playerList?.getPlayer(uuid)
        if (player != null) {
            val nick = namesByUsername[player.gameProfile.name.lowercase()]
            if (nick != null) {
                namesByUuid[uuid] = nick
                usernameByUuid[uuid] = player.gameProfile.name
                return nick
            }
        }
        return null
    }

    fun getComponent(uuid: UUID): net.minecraft.network.chat.Component? {
        val raw = getRaw(uuid) ?: return null
        return RichText.parse(raw)
    }

    fun getPlain(uuid: UUID): String? {
        val raw = getRaw(uuid) ?: return null
        return raw.replace(Regex("<[^>]*>"), "")
    }

    fun getUsername(uuid: UUID): String? {
        return usernameByUuid[uuid] ?: currentServer?.playerList?.getPlayer(uuid)?.gameProfile?.name
    }

    fun setNickname(uuid: UUID, username: String, nickname: String, save: Boolean = true) {
        namesByUuid[uuid] = nickname
        namesByUsername[username.lowercase()] = nickname
        usernameByUuid[uuid] = username

        if (save) save()

        currentServer?.let { server ->
            syncToAll(server)
            GuildNicknamesBridge.updatePlayerGuildNickname(uuid, getPlain(uuid) ?: nickname, server)
            val player = server.playerList.getPlayer(uuid)
            if (player != null) {
                server.playerList.broadcastAll(
                    ClientboundPlayerInfoUpdatePacket(
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
                        player
                    )
                )
            }
        }
    }

    fun clearNickname(uuid: UUID, save: Boolean = true) {
        namesByUuid.remove(uuid)
        val uName = usernameByUuid.remove(uuid)
        if (uName != null) {
            namesByUsername.remove(uName.lowercase())
        }

        if (save) save()

        currentServer?.let { server ->
            syncToAll(server)
            val realName = uName ?: server.profileCache?.get(uuid)?.orElse(null)?.name ?: ""
            if (realName.isNotBlank()) {
                GuildNicknamesBridge.updatePlayerGuildNickname(uuid, realName, server)
            }
            val player = server.playerList.getPlayer(uuid)
            if (player != null) {
                server.playerList.broadcastAll(
                    ClientboundPlayerInfoUpdatePacket(
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
                        player
                    )
                )
            }
        }
    }

    fun findOnlinePlayerByNickname(query: String): ServerPlayer? {
        val cleanQuery = query.trim().lowercase()
        val server = currentServer ?: return null
        for (player in server.playerList.players) {
            val plain = getPlain(player.uuid)?.lowercase()
            if (plain != null && plain == cleanQuery) {
                return player
            }
        }
        return null
    }

    fun getAllNames(): Map<UUID, String> = HashMap(namesByUuid)

    fun onPlayerJoin(player: ServerPlayer) {
        // Verificar si tenía apodo registrado por username
        val nick = namesByUsername[player.gameProfile.name.lowercase()]
        if (nick != null && !namesByUuid.containsKey(player.uuid)) {
            namesByUuid[player.uuid] = nick
            usernameByUuid[player.uuid] = player.gameProfile.name
        }

        syncToPlayer(player)
        val plainNick = getPlain(player.uuid)
        if (plainNick != null) {
            GuildNicknamesBridge.updatePlayerGuildNickname(player.uuid, plainNick, player.server)
        }

        // Actualizar tab list de todos para incluir al nuevo jugador con su apodo
        player.server.playerList.broadcastAll(
            ClientboundPlayerInfoUpdatePacket(
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
                player
            )
        )
    }

    fun syncToPlayer(player: ServerPlayer) {
        if (ServerPlayNetworking.canSend(player, CustomNamesSyncPayload.TYPE)) {
            ServerPlayNetworking.send(player, CustomNamesSyncPayload(getAllNames()))
        }
    }

    fun syncToAll(server: MinecraftServer) {
        val payload = CustomNamesSyncPayload(getAllNames())
        for (p in server.playerList.players) {
            if (ServerPlayNetworking.canSend(p, CustomNamesSyncPayload.TYPE)) {
                ServerPlayNetworking.send(p, payload)
            }
        }
        if (server.playerList.players.isNotEmpty()) {
            server.playerList.broadcastAll(
                ClientboundPlayerInfoUpdatePacket(
                    EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
                    server.playerList.players
                )
            )
        }
    }
}
