package com.desgraciamon.discordbridge

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

data object TeamPrivacyRequest : CustomPacketPayload {
    override fun type() = TYPE

    val TYPE = CustomPacketPayload.Type<TeamPrivacyRequest>(
        ResourceLocation.fromNamespaceAndPath("desgraciamon4_discord_bridge", "team_privacy_request")
    )
    val CODEC: StreamCodec<RegistryFriendlyByteBuf, TeamPrivacyRequest> = StreamCodec.unit(TeamPrivacyRequest)
}

data object TeamPrivacyToggleRequest : CustomPacketPayload {
    override fun type() = TYPE

    val TYPE = CustomPacketPayload.Type<TeamPrivacyToggleRequest>(
        ResourceLocation.fromNamespaceAndPath("desgraciamon4_discord_bridge", "team_privacy_toggle")
    )
    val CODEC: StreamCodec<RegistryFriendlyByteBuf, TeamPrivacyToggleRequest> = StreamCodec.unit(TeamPrivacyToggleRequest)
}

data class CooldownPreferenceToggleRequest(val preference: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<CooldownPreferenceToggleRequest>(
            ResourceLocation.fromNamespaceAndPath("desgraciamon4_discord_bridge", "cooldown_preference_toggle")
        )
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, CooldownPreferenceToggleRequest> = StreamCodec.of(
            { buffer, payload -> buffer.writeUtf(payload.preference) },
            { buffer -> CooldownPreferenceToggleRequest(buffer.readUtf(32)) }
        )
    }
}

data class TeamPrivacyStatusPayload(
    val isPrivate: Boolean,
    val isDiscordLinked: Boolean,
    val cooldownInstalled: Boolean,
    val cooldownBossBarEnabled: Boolean,
    val cooldownJoinNoticeEnabled: Boolean,
    val catchAssistantInstalled: Boolean,
    val catchNoticeEnabled: Boolean
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<TeamPrivacyStatusPayload>(
            ResourceLocation.fromNamespaceAndPath("desgraciamon4_discord_bridge", "team_privacy_status")
        )
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, TeamPrivacyStatusPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeBoolean(payload.isPrivate)
                buffer.writeBoolean(payload.isDiscordLinked)
                buffer.writeBoolean(payload.cooldownInstalled)
                buffer.writeBoolean(payload.cooldownBossBarEnabled)
                buffer.writeBoolean(payload.cooldownJoinNoticeEnabled)
                buffer.writeBoolean(payload.catchAssistantInstalled)
                buffer.writeBoolean(payload.catchNoticeEnabled)
            },
            { buffer ->
                TeamPrivacyStatusPayload(
                    buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean()
                )
            }
        )
    }
}
