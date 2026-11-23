package com.desgraciamon.customnames

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.util.UUID

object GuildNicknamesBridge {
    private val LOGGER = LoggerFactory.getLogger("DesgraciamonGuildNicknamesBridge")

    fun isGuildsLoaded(): Boolean =
        FabricLoader.getInstance().isModLoaded("desgraciamon_guilds")

    fun updatePlayerGuildNickname(uuid: UUID, effectiveName: String, server: MinecraftServer?) {
        if (!isGuildsLoaded()) return
        val currentServer = server ?: CustomNamesManager.currentServer ?: return

        runCatching {
            val mainClass = Class.forName("net.desgraciamon.guilds.DesgraciamonGuilds")
            val storageMethod = mainClass.getDeclaredMethod("getStorage")
            storageMethod.isAccessible = true
            val storage = storageMethod.invoke(null) ?: return

            val guildsMethod = storage.javaClass.getDeclaredMethod("guilds")
            guildsMethod.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val guildList = guildsMethod.invoke(storage) as? List<Any> ?: return

            var changed = false
            val uuidStr = uuid.toString()

            for (guild in guildList) {
                val membersField = guild.javaClass.getDeclaredField("members")
                membersField.isAccessible = true
                val members = membersField.get(guild) as? Map<*, *> ?: continue

                for ((key, member) in members) {
                    val keyMatch = when (key) {
                        is UUID -> key == uuid
                        is String -> key.equals(uuidStr, ignoreCase = true)
                        else -> key.toString().equals(uuidStr, ignoreCase = true)
                    }
                    if (keyMatch && member != null) {
                        val nameField = member.javaClass.getDeclaredField("playerName")
                        nameField.isAccessible = true
                        val currentName = nameField.get(member) as? String
                        if (currentName != effectiveName) {
                            nameField.set(member, effectiveName)
                            changed = true
                        }
                    }
                }
            }

            if (changed) {
                val saveMethod = storage.javaClass.getDeclaredMethod("save")
                saveMethod.isAccessible = true
                saveMethod.invoke(storage)

                val netClass = Class.forName("net.desgraciamon.guilds.GuildNetwork")
                val syncMethod = netClass.getDeclaredMethod("syncAll", MinecraftServer::class.java)
                syncMethod.isAccessible = true
                syncMethod.invoke(null, currentServer)
                LOGGER.info("Se actualizó el apodo de gremio para $uuid a '$effectiveName' y se sincronizó.")
            }
        }.onFailure { e ->
            LOGGER.error("Error al actualizar apodo en gremios para $uuid", e)
        }
    }

    fun syncAllGuildNicknames(server: MinecraftServer) {
        if (!isGuildsLoaded()) return
        runCatching {
            val mainClass = Class.forName("net.desgraciamon.guilds.DesgraciamonGuilds")
            val storageMethod = mainClass.getDeclaredMethod("getStorage")
            storageMethod.isAccessible = true
            val storage = storageMethod.invoke(null) ?: return

            val guildsMethod = storage.javaClass.getDeclaredMethod("guilds")
            guildsMethod.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val guildList = guildsMethod.invoke(storage) as? List<Any> ?: return

            var changed = false

            for (guild in guildList) {
                val membersField = guild.javaClass.getDeclaredField("members")
                membersField.isAccessible = true
                val members = membersField.get(guild) as? Map<*, *> ?: continue

                for ((key, member) in members) {
                    val mUuid = when (key) {
                        is UUID -> key
                        is String -> runCatching { UUID.fromString(key) }.getOrNull()
                        else -> runCatching { UUID.fromString(key.toString()) }.getOrNull()
                    } ?: continue

                    if (member != null) {
                        val plainNick = CustomNamesManager.getPlain(mUuid)
                        val originalUsername = CustomNamesManager.getUsername(mUuid)
                            ?: server.profileCache?.get(mUuid)?.orElse(null)?.name
                        val targetName = plainNick ?: originalUsername ?: continue

                        val nameField = member.javaClass.getDeclaredField("playerName")
                        nameField.isAccessible = true
                        val currentName = nameField.get(member) as? String
                        if (currentName != targetName) {
                            nameField.set(member, targetName)
                            changed = true
                        }
                    }
                }
            }

            if (changed) {
                val saveMethod = storage.javaClass.getDeclaredMethod("save")
                saveMethod.isAccessible = true
                saveMethod.invoke(storage)

                val netClass = Class.forName("net.desgraciamon.guilds.GuildNetwork")
                val syncMethod = netClass.getDeclaredMethod("syncAll", MinecraftServer::class.java)
                syncMethod.isAccessible = true
                syncMethod.invoke(null, server)
                LOGGER.info("Se sincronizaron todos los apodos de gremios con éxito.")
            }
        }.onFailure { e ->
            LOGGER.error("Error al sincronizar apodos en gremios", e)
        }
    }
}

