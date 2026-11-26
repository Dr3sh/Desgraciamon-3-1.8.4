package com.desgraciamon.showdownarena

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.google.gson.GsonBuilder
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.phys.AABB
import java.io.File

data class ArenaZone(
    val name: String = "Arena Principal",
    val dimension: String = "minecraft:overworld",
    val minX: Double = 0.0,
    val minY: Double = -64.0,
    val minZ: Double = 0.0,
    val maxX: Double = 0.0,
    val maxY: Double = 320.0,
    val maxZ: Double = 0.0
) {
    fun contains(dimensionId: String, x: Double, y: Double, z: Double): Boolean {
        if (dimension != dimensionId) return false
        val lowX = minOf(minX, maxX)
        val highX = maxOf(minX, maxX)
        val lowZ = minOf(minZ, maxZ)
        val highZ = maxOf(minZ, maxZ)
        val halfW = (highX - lowX) * 0.5
        val halfD = (highZ - lowZ) * 0.5
        val rx = (halfW - 0.15).coerceAtLeast(0.6)
        val rz = (halfD - 0.15).coerceAtLeast(0.6)
        val cx = (lowX + highX) * 0.5
        val cz = (lowZ + highZ) * 0.5

        val dx = (x - cx) / rx
        val dz = (z - cz) / rz
        val normDistSq = dx * dx + dz * dz
        if (normDistSq > 1.0) return false

        val minR = minOf(rx, rz)
        val domeHeight = (minR * 0.94).coerceIn(6.5, 17.5)
        // Spherical dome vertical envelope so players flying far above the dome are outside
        val maxDomeY = minY + domeHeight * kotlin.math.sqrt((1.0 - normDistSq).coerceAtLeast(0.0)) + 2.0
        return y >= (minY - 4.0) && y <= maxDomeY
    }

    fun contains(entity: Entity): Boolean {
        val dim = entity.level().dimension().location().toString()
        return contains(dim, entity.x, entity.y, entity.z)
    }

    fun nearestPointOnDome(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val lowX = minOf(minX, maxX)
        val highX = maxOf(minX, maxX)
        val lowZ = minOf(minZ, maxZ)
        val highZ = maxOf(minZ, maxZ)
        val rx = ((highX - lowX) * 0.5 - 0.15).coerceAtLeast(0.6)
        val rz = ((highZ - lowZ) * 0.5 - 0.15).coerceAtLeast(0.6)
        val cx = (lowX + highX) * 0.5
        val cz = (lowZ + highZ) * 0.5

        val dx = (x - cx) / rx
        val dz = (z - cz) / rz
        val normDist = kotlin.math.sqrt(dx * dx + dz * dz)
        val (px, pz, normDistSq) = if (normDist > 1.0) {
            Triple(cx + (x - cx) / normDist, cz + (z - cz) / normDist, 1.0)
        } else {
            Triple(x, z, dx * dx + dz * dz)
        }

        val minR = minOf(rx, rz)
        val domeHeight = (minR * 0.88).coerceIn(5.5, 15.5)
        val maxDomeY = minY + domeHeight * kotlin.math.sqrt((1.0 - normDistSq).coerceAtLeast(0.0)) + 2.0
        val py = y.coerceIn(minY - 4.0, maxDomeY)
        return Triple(px, py, pz)
    }
}

data class ArenaZoneConfig(
    var enabled: Boolean = false, // Si es false, se puede combatir en cualquier lugar
    var zone: ArenaZone? = null
)

// S2C Packet para sincronizar la zona con el cliente (para renderizar el reborde)
data class ArenaZoneSyncPayload(
    val enabled: Boolean,
    val hasZone: Boolean,
    val name: String,
    val dimension: String,
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double
) : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Type<ArenaZoneSyncPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("showdown_arena", "zone_sync"))
        val CODEC: StreamCodec<FriendlyByteBuf, ArenaZoneSyncPayload> = CustomPacketPayload.codec(
            { payload, buf ->
                buf.writeBoolean(payload.enabled)
                buf.writeBoolean(payload.hasZone)
                if (payload.hasZone) {
                    buf.writeUtf(payload.name)
                    buf.writeUtf(payload.dimension)
                    buf.writeDouble(payload.minX)
                    buf.writeDouble(payload.minY)
                    buf.writeDouble(payload.minZ)
                    buf.writeDouble(payload.maxX)
                    buf.writeDouble(payload.maxY)
                    buf.writeDouble(payload.maxZ)
                }
            },
            { buf ->
                val enabled = buf.readBoolean()
                val hasZone = buf.readBoolean()
                if (hasZone) {
                    ArenaZoneSyncPayload(
                        enabled, true,
                        buf.readUtf(),
                        buf.readUtf(),
                        buf.readDouble(),
                        buf.readDouble(),
                        buf.readDouble(),
                        buf.readDouble(),
                        buf.readDouble(),
                        buf.readDouble()
                    )
                } else {
                    ArenaZoneSyncPayload(enabled, false, "", "", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
                }
            }
        )
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID
}

// S2C Packet para abrir la GUI en el cliente desde un comando o evento
data class OpenShowdownGuiPayload(
    val targetPlayerName: String
) : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Type<OpenShowdownGuiPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("showdown_arena", "open_gui"))
        val CODEC: StreamCodec<FriendlyByteBuf, OpenShowdownGuiPayload> = CustomPacketPayload.codec(
            { payload, buf -> buf.writeUtf(payload.targetPlayerName) },
            { buf -> OpenShowdownGuiPayload(buf.readUtf()) }
        )
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID
}

object ArenaZoneManager {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val legacyConfigFile = File("config/desgraciamon_arena_zone.json")
    private var activeServer: MinecraftServer? = null

    var config = ArenaZoneConfig()
        private set

    fun init() {
        PayloadTypeRegistry.playS2C().register(ArenaZoneSyncPayload.ID, ArenaZoneSyncPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(OpenShowdownGuiPayload.ID, OpenShowdownGuiPayload.CODEC)
    }

    private fun getZoneFile(server: MinecraftServer): File {
        val dir = server.getWorldPath(LevelResource.ROOT).resolve("data").toFile()
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "desgraciamon_arena_zone.json")
    }

    fun onServerStart(server: MinecraftServer) {
        activeServer = server
        load(server)
    }

    fun onServerStop() {
        activeServer?.let { save(it) }
        activeServer = null
    }

    fun load(server: MinecraftServer) {
        activeServer = server
        try {
            val file = getZoneFile(server)
            if (file.exists()) {
                config = gson.fromJson(file.readText(), ArenaZoneConfig::class.java) ?: ArenaZoneConfig()
            } else if (legacyConfigFile.exists()) {
                // Migración automática del antiguo archivo de configuración global
                config = gson.fromJson(legacyConfigFile.readText(), ArenaZoneConfig::class.java) ?: ArenaZoneConfig()
                save(server)
                legacyConfigFile.delete()
            } else {
                config = ArenaZoneConfig()
                save(server)
            }
        } catch (_: Exception) {
            config = ArenaZoneConfig()
        }
    }

    fun save(server: MinecraftServer? = activeServer) {
        val s = server ?: return
        try {
            val file = getZoneFile(s)
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(config))
        } catch (_: Exception) {}
    }

    fun setZone(zone: ArenaZone, enable: Boolean = true, server: MinecraftServer? = activeServer) {
        config.zone = zone
        config.enabled = enable
        save(server)
    }

    fun toggleEnabled(server: MinecraftServer? = activeServer): Boolean {
        config.enabled = !config.enabled
        save(server)
        return config.enabled
    }

    fun isAllowedToBattle(entity: Entity): Boolean {
        if (!config.enabled) return true
        val z = config.zone ?: return true
        return z.contains(entity)
    }

    fun createPayload(): ArenaZoneSyncPayload {
        val z = config.zone
        return if (z != null) {
            ArenaZoneSyncPayload(
                config.enabled, true,
                z.name, z.dimension,
                minOf(z.minX, z.maxX), minOf(z.minY, z.maxY), minOf(z.minZ, z.maxZ),
                maxOf(z.minX, z.maxX), maxOf(z.minY, z.maxY), maxOf(z.minZ, z.maxZ)
            )
        } else {
            ArenaZoneSyncPayload(config.enabled, false, "", "", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
    }

    fun syncToPlayer(player: ServerPlayer) {
        ServerPlayNetworking.send(player, createPayload())
    }

    fun syncToAll(server: net.minecraft.server.MinecraftServer) {
        val payload = createPayload()
        server.playerList.players.forEach { ServerPlayNetworking.send(it, payload) }
    }

    private var wildCleanupTick = 0

    /**
     * Devuelve true SOLO si es un Pokémon salvaje común:
     * excluye estrictamente Pokémon de jugadores, en combate, temporales de Showdown,
     * recuerdos, con nombre/persistencia, shinies, legendarios y míticos.
     */
    fun isCommonWildPokemon(entity: PokemonEntity): Boolean {
        if (!entity.isAlive || entity.isRemoved) return false
        if (entity.ownerUUID != null) return false
        val pkm = entity.pokemon
        if (pkm.isPlayerOwned() || pkm.getOwnerUUID() != null) return false
        if (pkm.storeCoordinates.get() != null) return false
        if (entity.isBattling || entity.battleId != null) return false
        if (pkm.persistentData.getBoolean(ArenaTeamFactory.SHOWDOWN_TEMP_NBT_KEY)) return false
        if (entity.isPersistenceRequired || entity.hasCustomName()) return false
        if ("desgraciamon4_mirror_memory" in entity.tags) return false
        if (pkm.shiny) return false
        if (pkm.isLegendary() || pkm.isMythical()) return false
        return true
    }

    fun tickWildCleanup(server: MinecraftServer) {
        wildCleanupTick++
        if (wildCleanupTick % 20 != 0) return
        if (!config.enabled) return
        val z = config.zone ?: return
        val level = server.allLevels.firstOrNull { it.dimension().location().toString() == z.dimension } ?: return

        val box = AABB(
            minOf(z.minX, z.maxX) - 1.0,
            z.minY - 5.0,
            minOf(z.minZ, z.maxZ) - 1.0,
            maxOf(z.minX, z.maxX) + 1.0,
            z.minY + 24.0,
            maxOf(z.minZ, z.maxZ) + 1.0
        )

        for (entity in level.getEntitiesOfClass(PokemonEntity::class.java, box)) {
            if (z.contains(entity) && isCommonWildPokemon(entity)) {
                level.sendParticles(
                    ParticleTypes.CLOUD,
                    entity.x, entity.y + entity.bbHeight * 0.5, entity.z,
                    6, 0.25, 0.25, 0.25, 0.02
                )
                entity.discard()
            }
        }
    }
}

