package net.desgraciamon.guilds;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

record GuildSyncPayload(
   boolean member,
   String name,
   String color,
   String role,
   int memberCount,
   BigInteger balance,
   BigInteger walletBalance,
   String motd,
   String icon,
   List<String> members,
   List<String> waypoints,
   List<String> activityLog,
   List<String> playerTags,
   List<String> pcPreviews,
   List<String> chatMessages,
   List<ItemStack> vaultItems
) implements CustomPacketPayload {
   static final Type<GuildSyncPayload> TYPE = new Type(ResourceLocation.fromNamespaceAndPath("desgraciamon_guilds", "guild_sync"));
   static final StreamCodec<RegistryFriendlyByteBuf, GuildSyncPayload> CODEC = new StreamCodec<RegistryFriendlyByteBuf, GuildSyncPayload>() {
      public GuildSyncPayload decode(RegistryFriendlyByteBuf buffer) {
         boolean member = buffer.readBoolean();
         String name = buffer.readUtf(64);
         String color = buffer.readUtf(7);
         String role = buffer.readUtf(16);
         int memberCount = buffer.readVarInt();

         BigInteger balance;
         try {
            balance = new BigInteger(buffer.readUtf(128));
         } catch (NumberFormatException var12) {
            balance = BigInteger.ZERO;
         }

         BigInteger walletBalance;
         try {
            walletBalance = new BigInteger(buffer.readUtf(128));
         } catch (NumberFormatException var11) {
            walletBalance = BigInteger.ZERO;
         }

         String motd = buffer.readUtf(256);
         String icon = buffer.readUtf(32);
         return new GuildSyncPayload(
            member,
            name,
            color,
            role,
            memberCount,
            balance,
            walletBalance,
            motd,
            icon,
            GuildSyncPayload.readList(buffer, 128),
            GuildSyncPayload.readList(buffer, 128),
            GuildSyncPayload.readList(buffer, 128),
            GuildSyncPayload.readList(buffer, 512),
            GuildSyncPayload.readList(buffer, 512),
            GuildSyncPayload.readList(buffer, 128),
            (List<ItemStack>)ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buffer)
         );
      }

      public void encode(RegistryFriendlyByteBuf buffer, GuildSyncPayload payload) {
         buffer.writeBoolean(payload.member());
         buffer.writeUtf(payload.name(), 64);
         buffer.writeUtf(payload.color(), 7);
         buffer.writeUtf(payload.role(), 16);
         buffer.writeVarInt(payload.memberCount());
         buffer.writeUtf(payload.balance().toString(), 128);
         buffer.writeUtf(payload.walletBalance().toString(), 128);
         buffer.writeUtf(payload.motd() == null ? "" : payload.motd(), 256);
         buffer.writeUtf(payload.icon() == null ? "pokeball" : payload.icon(), 32);
         GuildSyncPayload.writeList(buffer, payload.members(), 128);
         GuildSyncPayload.writeList(buffer, payload.waypoints(), 128);
         GuildSyncPayload.writeList(buffer, payload.activityLog(), 128);
         GuildSyncPayload.writeList(buffer, payload.playerTags(), 512);
         GuildSyncPayload.writeList(buffer, payload.pcPreviews() == null ? List.of() : payload.pcPreviews(), 512);
         GuildSyncPayload.writeList(buffer, payload.chatMessages() == null ? List.of() : payload.chatMessages(), 128);
         ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buffer, payload.vaultItems() == null ? List.of() : payload.vaultItems());
      }
   };

   private static List<String> readList(RegistryFriendlyByteBuf buffer, int maximum) {
      int size = Math.min(buffer.readVarInt(), maximum);
      List<String> values = new ArrayList<>(size);

      for (int index = 0; index < size; index++) {
         values.add(buffer.readUtf(512));
      }

      return values;
   }

   private static void writeList(RegistryFriendlyByteBuf buffer, List<String> values, int maximum) {
      int size = Math.min(values.size(), maximum);
      buffer.writeVarInt(size);

      for (int index = 0; index < size; index++) {
         buffer.writeUtf(values.get(index), 512);
      }
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
