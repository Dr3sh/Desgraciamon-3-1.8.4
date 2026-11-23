package com.desgraciamon.showdownarena

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel

data class SkyLogoPayload(
    val dimension: String,
    val centerX: Double,
    val centerY: Double,
    val centerZ: Double,
    val yawDeg: Float,
    val startServerMs: Long
) : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Type<SkyLogoPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("showdown_arena", "sky_logo"))

        val CODEC: StreamCodec<FriendlyByteBuf, SkyLogoPayload> = CustomPacketPayload.codec(
            { payload, buf ->
                buf.writeUtf(payload.dimension, 128)
                buf.writeDouble(payload.centerX)
                buf.writeDouble(payload.centerY)
                buf.writeDouble(payload.centerZ)
                buf.writeFloat(payload.yawDeg)
                buf.writeLong(payload.startServerMs)
            },
            { buf ->
                SkyLogoPayload(
                    buf.readUtf(128),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat(),
                    buf.readLong()
                )
            }
        )
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID
}

object SkyLogoManager {
    fun init() {
        PayloadTypeRegistry.playS2C().register(SkyLogoPayload.ID, SkyLogoPayload.CODEC)
    }

    fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val registerAlias = { name: String ->
            dispatcher.register(
                Commands.literal(name)
                    .requires { it.hasPermission(2) }
                    .executes { ctx ->
                        trigger(ctx.source, 95.0, forceHere = false)
                    }
                    .then(
                        Commands.literal("aqui")
                            .executes { ctx ->
                                trigger(ctx.source, 95.0, forceHere = true)
                            }
                            .then(
                                Commands.argument("altura", DoubleArgumentType.doubleArg(25.0, 260.0))
                                    .executes { ctx ->
                                        val h = DoubleArgumentType.getDouble(ctx, "altura")
                                        trigger(ctx.source, h, forceHere = true)
                                    }
                            )
                    )
                    .then(
                        Commands.argument("altura", DoubleArgumentType.doubleArg(25.0, 260.0))
                            .executes { ctx ->
                                val h = DoubleArgumentType.getDouble(ctx, "altura")
                                trigger(ctx.source, h, forceHere = false)
                            }
                    )
            )
        }

        registerAlias("skylogo")
        registerAlias("d3slogo")
    }

    private fun trigger(source: CommandSourceStack, heightOffset: Double, forceHere: Boolean): Int {
        val level: ServerLevel = source.level
        val dimId = level.dimension().location().toString()
        val zone = ArenaZoneManager.config.zone
        val srcPos = source.position
        val playerYaw = source.entity?.yRot ?: 0.0f

        val (cx, cy, cz, yaw) = if (!forceHere && zone != null && zone.dimension == dimId) {
            val zx = (zone.minX + zone.maxX) * 0.5
            val zz = (zone.minZ + zone.maxZ) * 0.5
            val zy = zone.minY + heightOffset
            // Face the player who triggered it if nearby, otherwise face South (0 deg)
            val dx = srcPos.x - zx
            val dz = srcPos.z - zz
            val computedYaw = if (dx * dx + dz * dz > 16.0) {
                Math.toDegrees(kotlin.math.atan2(-dx, dz)).toFloat()
            } else {
                playerYaw + 180.0f
            }
            Quad(zx, zy, zz, computedYaw)
        } else {
            // Place ahead and high in the sky in front of the player
            val rad = Math.toRadians(playerYaw.toDouble())
            val forwardX = -kotlin.math.sin(rad) * 55.0
            val forwardZ = kotlin.math.cos(rad) * 55.0
            Quad(srcPos.x + forwardX, srcPos.y + heightOffset, srcPos.z + forwardZ, playerYaw + 180.0f)
        }

        val payload = SkyLogoPayload(dimId, cx, cy, cz, yaw, System.currentTimeMillis())
        var sent = 0
        for (p in source.server.playerList.players) {
            if (p.level().dimension().location().toString() == dimId && ServerPlayNetworking.canSend(p, SkyLogoPayload.ID)) {
                ServerPlayNetworking.send(p, payload)
                sent++
            }
        }

        source.sendSuccess(
            { Component.literal("§b[D3S] §fLogo celeste activado durante 20s en el cielo ($sent jugadores).") },
            true
        )
        return 1
    }

    private data class Quad(val x: Double, val y: Double, val z: Double, val yaw: Float)
}
