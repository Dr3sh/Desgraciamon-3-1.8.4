package net.desgraciamon.guilds;

import java.math.BigInteger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;

record GuildRequestPayload(String action, String argument, BigInteger amount) implements CustomPacketPayload {
   static final Type<GuildRequestPayload> TYPE = new Type(ResourceLocation.fromNamespaceAndPath("desgraciamon_guilds", "guild_request"));
   static final StreamCodec<RegistryFriendlyByteBuf, GuildRequestPayload> CODEC = new StreamCodec<RegistryFriendlyByteBuf, GuildRequestPayload>() {
      public GuildRequestPayload decode(RegistryFriendlyByteBuf buffer) {
         String action = buffer.readUtf(32);
         String argument = buffer.readUtf(2048);

         try {
            return new GuildRequestPayload(action, argument, new BigInteger(buffer.readUtf(128)));
         } catch (NumberFormatException var5) {
            return new GuildRequestPayload("INVALID", "", BigInteger.ZERO);
         }
      }

      public void encode(RegistryFriendlyByteBuf buffer, GuildRequestPayload payload) {
         buffer.writeUtf(payload.action(), 32);
         buffer.writeUtf(payload.argument(), 2048);
         buffer.writeUtf(payload.amount().toString(), 128);
      }
   };

   GuildRequestPayload(String action, String argument, int amount) {
      this(action, argument, BigInteger.valueOf((long)amount));
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
