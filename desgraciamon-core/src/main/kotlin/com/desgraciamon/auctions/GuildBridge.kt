package com.desgraciamon.auctions

import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack
import com.cobblemon.mod.common.pokemon.Pokemon
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.util.UUID

data class GuildBidInfo(
    val id: String,
    val name: String,
    val tag: String,
    val bankBalance: Long
)

object GuildBridge {
    private val logger = LoggerFactory.getLogger("desgraciamon4-auctions-guild")

    fun isGuildsLoaded(): Boolean =
        net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("desgraciamon_guilds")

    fun getPlayerGuild(playerUuid: UUID, server: MinecraftServer): GuildBidInfo? {
        if (!isGuildsLoaded()) {
            logger.warn("desgraciamon_guilds is not loaded")
            return null
        }
        val uuidStr = playerUuid.toString().lowercase()

        return runCatching {
            val mainClass = Class.forName("net.desgraciamon.guilds.DesgraciamonGuilds")
            val storageMethod = mainClass.getDeclaredMethod("getStorage")
            storageMethod.isAccessible = true
            val storage = storageMethod.invoke(null) ?: return null

            // 1) Intento directo vía storage.index().playerGuilds
            runCatching {
                val indexMethod = storage.javaClass.getDeclaredMethod("index")
                indexMethod.isAccessible = true
                val index = indexMethod.invoke(storage)
                if (index != null) {
                    val pgField = index.javaClass.getDeclaredField("playerGuilds")
                    pgField.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val playerGuilds = pgField.get(index) as? Map<String, String>
                    val guildId = playerGuilds?.get(uuidStr) ?: playerGuilds?.get(playerUuid.toString())
                    if (!guildId.isNullOrBlank()) {
                        val getGuildMethod = storage.javaClass.getDeclaredMethod("getGuild", String::class.java)
                        getGuildMethod.isAccessible = true
                        val guild = getGuildMethod.invoke(storage, guildId)
                        if (guild != null) {
                            return extractGuildInfo(guild)
                        }
                    }
                }
            }

            // 2) Búsqueda exhaustiva en storage.guilds()
            val guildsMethod = storage.javaClass.getDeclaredMethod("guilds")
            guildsMethod.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val guildList = guildsMethod.invoke(storage) as? List<Any> ?: emptyList()

            for (guild in guildList) {
                val membersField = guild.javaClass.getDeclaredField("members")
                membersField.isAccessible = true
                val members = membersField.get(guild) as? Map<*, *> ?: continue

                val hasMember = members.keys.any { key ->
                    when (key) {
                        is String -> key.equals(uuidStr, ignoreCase = true)
                        is UUID -> key == playerUuid
                        else -> key.toString().equals(uuidStr, ignoreCase = true)
                    }
                }

                if (hasMember) {
                    return extractGuildInfo(guild)
                }
            }
            null
        }.getOrElse { e ->
            logger.error("Error detecting player guild for UUID $playerUuid", e)
            null
        }
    }

    private fun extractGuildInfo(guild: Any): GuildBidInfo {
        val nameField = guild.javaClass.getDeclaredField("name")
        nameField.isAccessible = true
        val name = nameField.get(guild) as? String ?: "Gremio"

        val idField = guild.javaClass.getDeclaredField("guildId")
        idField.isAccessible = true
        val guildId = idField.get(guild) as? String ?: ""

        val bankField = guild.javaClass.getDeclaredField("bankBalance")
        bankField.isAccessible = true
        val balance = (bankField.get(guild) as? BigInteger)?.toLong() ?: 0L

        return GuildBidInfo(
            id = guildId,
            name = name,
            tag = guildId,
            bankBalance = balance
        )
    }

    fun withdrawGuildMoney(guildTag: String, amount: Long, server: MinecraftServer): Boolean {
        if (!isGuildsLoaded() || amount <= 0) return false
        return runCatching {
            val mainClass = Class.forName("net.desgraciamon.guilds.DesgraciamonGuilds")
            val storageMethod = mainClass.getDeclaredMethod("getStorage")
            storageMethod.isAccessible = true
            val storage = storageMethod.invoke(null) ?: return false

            val getGuildMethod = storage.javaClass.getDeclaredMethod("getGuild", String::class.java)
            getGuildMethod.isAccessible = true
            val guild = getGuildMethod.invoke(storage, guildTag) ?: return false

            val bankField = guild.javaClass.getDeclaredField("bankBalance")
            bankField.isAccessible = true
            val current = (bankField.get(guild) as? BigInteger) ?: BigInteger.ZERO
            val toDeduct = BigInteger.valueOf(amount)
            if (current < toDeduct) return false

            bankField.set(guild, current.subtract(toDeduct))

            val saveMethod = storage.javaClass.getDeclaredMethod("save")
            saveMethod.isAccessible = true
            saveMethod.invoke(storage)

            // Sincronizar con todos los clientes
            runCatching {
                val netClass = Class.forName("net.desgraciamon.guilds.GuildNetwork")
                val syncMethod = netClass.getDeclaredMethod("syncAll", MinecraftServer::class.java)
                syncMethod.isAccessible = true
                syncMethod.invoke(null, server)
            }
            true
        }.getOrElse { e ->
            logger.error("Error withdrawing money from guild $guildTag", e)
            false
        }
    }

    fun depositGuildMoney(guildTag: String, amount: Long, server: MinecraftServer): Boolean {
        if (!isGuildsLoaded() || amount <= 0) return false
        return runCatching {
            val mainClass = Class.forName("net.desgraciamon.guilds.DesgraciamonGuilds")
            val storageMethod = mainClass.getDeclaredMethod("getStorage")
            storageMethod.isAccessible = true
            val storage = storageMethod.invoke(null) ?: return false

            val getGuildMethod = storage.javaClass.getDeclaredMethod("getGuild", String::class.java)
            getGuildMethod.isAccessible = true
            val guild = getGuildMethod.invoke(storage, guildTag) ?: return false

            val bankField = guild.javaClass.getDeclaredField("bankBalance")
            bankField.isAccessible = true
            val current = (bankField.get(guild) as? BigInteger) ?: BigInteger.ZERO
            val toAdd = BigInteger.valueOf(amount)

            bankField.set(guild, current.add(toAdd))

            val saveMethod = storage.javaClass.getDeclaredMethod("save")
            saveMethod.isAccessible = true
            saveMethod.invoke(storage)

            // Sincronizar
            runCatching {
                val netClass = Class.forName("net.desgraciamon.guilds.GuildNetwork")
                val syncMethod = netClass.getDeclaredMethod("syncAll", MinecraftServer::class.java)
                syncMethod.isAccessible = true
                syncMethod.invoke(null, server)
            }
            true
        }.getOrElse { e ->
            logger.error("Error depositing money to guild $guildTag", e)
            false
        }
    }

    fun depositPokemonToGuildPC(guildTag: String, pokemon: Pokemon, server: MinecraftServer): Boolean {
        if (!isGuildsLoaded()) return false
        return runCatching {
            val mainClass = Class.forName("net.desgraciamon.guilds.DesgraciamonGuilds")
            val pcManagerMethod = mainClass.getDeclaredMethod("getPcManager")
            pcManagerMethod.isAccessible = true
            val pcManager = pcManagerMethod.invoke(null) ?: return false

            val getPcMethod = pcManager.javaClass.getDeclaredMethod("getGuildPC", String::class.java)
            getPcMethod.isAccessible = true
            val pcStore = getPcMethod.invoke(pcManager, guildTag) as? com.cobblemon.mod.common.api.storage.pc.PCStore ?: return false

            pcStore.add(pokemon)
        }.getOrDefault(false)
    }

    fun depositItemToGuildVault(guildTag: String, itemStack: ItemStack, server: MinecraftServer): Boolean {
        if (!isGuildsLoaded() || itemStack.isEmpty) return false
        return runCatching {
            val mainClass = Class.forName("net.desgraciamon.guilds.DesgraciamonGuilds")
            val storageMethod = mainClass.getDeclaredMethod("getStorage")
            storageMethod.isAccessible = true
            val storage = storageMethod.invoke(null) ?: return false

            val getGuildMethod = storage.javaClass.getDeclaredMethod("getGuild", String::class.java)
            getGuildMethod.isAccessible = true
            val guild = getGuildMethod.invoke(storage, guildTag) ?: return false

            val vaultMethod = runCatching { guild.javaClass.getDeclaredMethod("getVault") }.getOrNull()
            if (vaultMethod != null) {
                vaultMethod.isAccessible = true
                val list = vaultMethod.invoke(guild) as? net.minecraft.core.NonNullList<ItemStack>
                if (list != null) {
                    for (i in list.indices) {
                        if (list[i].isEmpty) {
                            list[i] = itemStack.copy()
                            break
                        }
                    }
                }
            }

            val saveMethod = storage.javaClass.getDeclaredMethod("save")
            saveMethod.isAccessible = true
            saveMethod.invoke(storage)
            true
        }.getOrDefault(false)
    }

    fun getGuildMembers(guildId: String): Set<UUID> {
        if (!isGuildsLoaded()) return emptySet()
        return runCatching {
            val mainClass = Class.forName("net.desgraciamon.guilds.DesgraciamonGuilds")
            val storageMethod = mainClass.getDeclaredMethod("getStorage")
            storageMethod.isAccessible = true
            val storage = storageMethod.invoke(null) ?: return emptySet()

            val getGuildMethod = storage.javaClass.getDeclaredMethod("getGuild", String::class.java)
            getGuildMethod.isAccessible = true
            val guild = getGuildMethod.invoke(storage, guildId) ?: return emptySet()

            val membersField = guild.javaClass.getDeclaredField("members")
            membersField.isAccessible = true
            val members = membersField.get(guild) as? Map<*, *> ?: return emptySet()

            members.keys.mapNotNull { key ->
                when (key) {
                    is UUID -> key
                    is String -> runCatching { UUID.fromString(key) }.getOrNull()
                    else -> runCatching { UUID.fromString(key.toString()) }.getOrNull()
                }
            }.toSet()
        }.getOrDefault(emptySet())
    }
}
