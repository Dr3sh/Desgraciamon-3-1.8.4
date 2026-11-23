package net.desgraciamon.guilds;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;

public record GuildSynergyPayload(int nearbyCount, String guildName, String guildColorHex) implements CustomPacketPayload {
   public static final Type<GuildSynergyPayload> TYPE = new Type(ResourceLocation.fromNamespaceAndPath("desgraciamon_guilds", "guild_synergy"));
   public static final StreamCodec<FriendlyByteBuf, GuildSynergyPayload> CODEC = StreamCodec.of((buf, payload) -> {
      buf.writeVarInt(payload.nearbyCount());
      buf.writeUtf(payload.guildName(), 64);
      buf.writeUtf(payload.guildColorHex(), 16);
   }, buf -> new GuildSynergyPayload(buf.readVarInt(), buf.readUtf(64), buf.readUtf(16)));

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
