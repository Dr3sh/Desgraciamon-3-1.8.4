package com.desgraciamon.vortex.network

import com.desgraciamon.vortex.DesgraciamonMod
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/**
 * S2C Packet (Módulo 1.3 & 6.2):
 * Syncs vortex active state, center BlockPos, noFly rule, remaining countdown seconds, and fade duration.
 */
data class VortexSyncPacket(
    val isActive: Boolean,
    val center: BlockPos,
    val noFly: Boolean,
    val remainingSeconds: Int,
    val totalSeconds: Int,
    val fadeSeconds: Float,
    val effectType: String = "startrails",
    val cameraShake: Float = 0.0f
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID

    companion object {
        val ID = CustomPacketPayload.Type<VortexSyncPacket>(
            ResourceLocation.fromNamespaceAndPath(DesgraciamonMod.MOD_ID, "vortex_sync")
        )

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, VortexSyncPacket> = StreamCodec.of(
            { buf, packet ->
                buf.writeBoolean(packet.isActive)
                buf.writeBlockPos(packet.center)
                buf.writeBoolean(packet.noFly)
                buf.writeVarInt(packet.remainingSeconds)
                buf.writeVarInt(packet.totalSeconds)
                buf.writeFloat(packet.fadeSeconds)
                buf.writeUtf(packet.effectType)
                buf.writeFloat(packet.cameraShake)
            },
            { buf ->
                val isActive = buf.readBoolean()
                val center = buf.readBlockPos()
                val noFly = buf.readBoolean()
                val remainingSeconds = buf.readVarInt()
                val totalSeconds = buf.readVarInt()
                val fadeSeconds = buf.readFloat()
                val effectType = if (buf.isReadable) buf.readUtf() else "startrails"
                val cameraShake = if (buf.isReadable) buf.readFloat() else 0.0f
                VortexSyncPacket(
                    isActive, center, noFly, remainingSeconds, totalSeconds, fadeSeconds, effectType, cameraShake
                )
            }
        )
    }
}

/**
 * S2C Packet (Módulo 5.2):
 * Sent to the winning player when they capture the vortex anomaly to open the Reward Decision Screen.
 */
data class OpenRewardScreenPacket(
    val pokemonName: String,
    val speciesIdentifier: String,
    val level: Int,
    val rewardAmount: Int
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID

    companion object {
        val ID = CustomPacketPayload.Type<OpenRewardScreenPacket>(
            ResourceLocation.fromNamespaceAndPath(DesgraciamonMod.MOD_ID, "open_reward_screen")
        )

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, OpenRewardScreenPacket> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, OpenRewardScreenPacket::pokemonName,
            ByteBufCodecs.STRING_UTF8, OpenRewardScreenPacket::speciesIdentifier,
            ByteBufCodecs.VAR_INT, OpenRewardScreenPacket::level,
            ByteBufCodecs.VAR_INT, OpenRewardScreenPacket::rewardAmount,
            ::OpenRewardScreenPacket
        )
    }
}

/**
 * C2S Packet (Módulo 5.3):
 * Sent from the winning client to the server when choosing between claiming the Pokemon or selling to the Black Market.
 */
data class RewardDecisionPacket(
    val claimPokemon: Boolean
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID

    companion object {
        val ID = CustomPacketPayload.Type<RewardDecisionPacket>(
            ResourceLocation.fromNamespaceAndPath(DesgraciamonMod.MOD_ID, "reward_decision")
        )

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, RewardDecisionPacket> = StreamCodec.composite(
            ByteBufCodecs.BOOL, RewardDecisionPacket::claimPokemon,
            ::RewardDecisionPacket
        )
    }
}

object VortexNetwork {
    fun registerPayloads() {
        PayloadTypeRegistry.playS2C().register(VortexSyncPacket.ID, VortexSyncPacket.CODEC)
        PayloadTypeRegistry.playS2C().register(OpenRewardScreenPacket.ID, OpenRewardScreenPacket.CODEC)
        PayloadTypeRegistry.playC2S().register(RewardDecisionPacket.ID, RewardDecisionPacket.CODEC)
    }
}

