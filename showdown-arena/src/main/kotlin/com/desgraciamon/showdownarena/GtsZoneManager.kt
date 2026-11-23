package com.desgraciamon.showdownarena

import com.google.gson.GsonBuilder
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.storage.LevelResource
import java.io.File

data class GtsZone(
    val name: String = "Mercado GTS",
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
        val lowY = minOf(minY, maxY)
        val highY = maxOf(minY, maxY)
        val lowZ = minOf(minZ, maxZ)
        val highZ = maxOf(minZ, maxZ)
        return x in lowX..highX && y in lowY..highY && z in lowZ..highZ
    }

    fun contains(entity: Entity): Boolean {
        val dim = entity.level().dimension().location().toString()
        return contains(dim, entity.x, entity.y, entity.z)
    }
}

data class GtsZoneConfig(
    var enabled: Boolean = true,
    var zone: GtsZone? = null
)

data class GtsZoneSyncPayload(
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
        val ID: CustomPacketPayload.Type<GtsZoneSyncPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("showdown_arena", "gts_zone_sync"))
        val CODEC: StreamCodec<FriendlyByteBuf, GtsZoneSyncPayload> = CustomPacketPayload.codec(
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
                    GtsZoneSyncPayload(
                        enabled, true,
                        buf.readUtf(), buf.readUtf(),
                        buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readDouble(), buf.readDouble(), buf.readDouble()
                    )
                } else {
                    GtsZoneSyncPayload(enabled, false, "", "", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
                }
            }
        )
    }

    override fun type(): CustomPacketPayload.Type<GtsZoneSyncPayload> = ID
}

object GtsZoneManager {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val legacyConfigFile = File("config/desgraciamon_gts_zone.json")
    private var activeServer: MinecraftServer? = null

    var config = GtsZoneConfig()
        private set

    fun init() {
        PayloadTypeRegistry.playS2C().register(GtsZoneSyncPayload.ID, GtsZoneSyncPayload.CODEC)
    }

    private fun getZoneFile(server: MinecraftServer): File {
        val dir = server.getWorldPath(LevelResource.ROOT).resolve("data").toFile()
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "desgraciamon_gts_zone.json")
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
                config = gson.fromJson(file.readText(), GtsZoneConfig::class.java) ?: GtsZoneConfig()
            } else if (legacyConfigFile.exists()) {
                // Migración automática del antiguo archivo de configuración global
                config = gson.fromJson(legacyConfigFile.readText(), GtsZoneConfig::class.java) ?: GtsZoneConfig()
                save(server)
                legacyConfigFile.delete()
            } else {
                config = GtsZoneConfig()
                save(server)
            }
        } catch (_: Exception) {
            config = GtsZoneConfig()
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

    fun setZone(zone: GtsZone, enable: Boolean = true, server: MinecraftServer? = activeServer) {
        config.zone = zone
        config.enabled = enable
        save(server)
    }

    fun toggleEnabled(server: MinecraftServer? = activeServer): Boolean {
        config.enabled = !config.enabled
        save(server)
        return config.enabled
    }

    fun canUseGts(source: CommandSourceStack): Boolean {
        val player = source.player ?: return true
        if (!config.enabled || config.zone == null) {
            source.sendFailure(Component.literal("§cEl comando /gts está deshabilitado porque el Mercado GTS no se encuentra activo."))
            return false
        }
        val z = config.zone!!
        if (!z.contains(player)) {
            source.sendFailure(Component.literal("§cEl comando /gts solo puede utilizarse dentro de la zona del Mercado GTS."))
            return false
        }
        return true
    }

    fun isAllowed(entity: Entity): Boolean {
        if (!config.enabled) return false
        val z = config.zone ?: return false
        return z.contains(entity)
    }

    fun createPayload(): GtsZoneSyncPayload {
        val z = config.zone
        return if (z != null) {
            GtsZoneSyncPayload(
                config.enabled, true,
                z.name, z.dimension,
                minOf(z.minX, z.maxX), minOf(z.minY, z.maxY), minOf(z.minZ, z.maxZ),
                maxOf(z.minX, z.maxX), maxOf(z.minY, z.maxY), maxOf(z.minZ, z.maxZ)
            )
        } else {
            GtsZoneSyncPayload(config.enabled, false, "", "", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
    }

    fun syncToPlayer(player: ServerPlayer) {
        ServerPlayNetworking.send(player, createPayload())
    }

    fun syncToAll(server: MinecraftServer) {
        val payload = createPayload()
        server.playerList.players.forEach { ServerPlayNetworking.send(it, payload) }
    }
}
