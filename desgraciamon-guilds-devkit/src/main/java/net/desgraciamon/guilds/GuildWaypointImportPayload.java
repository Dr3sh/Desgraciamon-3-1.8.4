package net.desgraciamon.guilds;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;

record GuildWaypointImportPayload(String name, String dimension, int x, int y, int z, boolean share) implements CustomPacketPayload {
   static final Type<GuildWaypointImportPayload> TYPE = new Type(ResourceLocation.fromNamespaceAndPath("desgraciamon_guilds", "waypoint_import"));
   static final StreamCodec<RegistryFriendlyByteBuf, GuildWaypointImportPayload> CODEC = new StreamCodec<RegistryFriendlyByteBuf, GuildWaypointImportPayload>() {
      public GuildWaypointImportPayload decode(RegistryFriendlyByteBuf buffer) {
         return new GuildWaypointImportPayload(
            buffer.readUtf(64), buffer.readUtf(128), buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readBoolean()
         );
      }

      public void encode(RegistryFriendlyByteBuf buffer, GuildWaypointImportPayload payload) {
         buffer.writeUtf(payload.name(), 64);
         buffer.writeUtf(payload.dimension(), 128);
         buffer.writeInt(payload.x());
         buffer.writeInt(payload.y());
         buffer.writeInt(payload.z());
         buffer.writeBoolean(payload.share());
      }
   };

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
