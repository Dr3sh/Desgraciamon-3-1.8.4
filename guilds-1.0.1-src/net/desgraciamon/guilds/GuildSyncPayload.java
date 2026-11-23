package net.desgraciamon.guilds;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.class_1799;
import net.minecraft.class_2960;
import net.minecraft.class_8710;
import net.minecraft.class_9129;
import net.minecraft.class_9139;
import net.minecraft.class_8710.class_9154;

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
   List<class_1799> vaultItems
) implements class_8710 {
   static final class_9154<GuildSyncPayload> TYPE = new class_9154(class_2960.method_60655("desgraciamon_guilds", "guild_sync"));
   static final class_9139<class_9129, GuildSyncPayload> CODEC = new class_9139<class_9129, GuildSyncPayload>() {
      public GuildSyncPayload decode(class_9129 buffer) {
         boolean member = buffer.readBoolean();
         String name = buffer.method_10800(64);
         String color = buffer.method_10800(7);
         String role = buffer.method_10800(16);
         int memberCount = buffer.method_10816();

         BigInteger balance;
         try {
            balance = new BigInteger(buffer.method_10800(128));
         } catch (NumberFormatException var12) {
            balance = BigInteger.ZERO;
         }

         BigInteger walletBalance;
         try {
            walletBalance = new BigInteger(buffer.method_10800(128));
         } catch (NumberFormatException var11) {
            walletBalance = BigInteger.ZERO;
         }

         String motd = buffer.method_10800(256);
         String icon = buffer.method_10800(32);
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
            (List<class_1799>)class_1799.field_49269.decode(buffer)
         );
      }

      public void encode(class_9129 buffer, GuildSyncPayload payload) {
         buffer.method_52964(payload.member());
         buffer.method_10788(payload.name(), 64);
         buffer.method_10788(payload.color(), 7);
         buffer.method_10788(payload.role(), 16);
         buffer.method_10804(payload.memberCount());
         buffer.method_10788(payload.balance().toString(), 128);
         buffer.method_10788(payload.walletBalance().toString(), 128);
         buffer.method_10788(payload.motd() == null ? "" : payload.motd(), 256);
         buffer.method_10788(payload.icon() == null ? "pokeball" : payload.icon(), 32);
         GuildSyncPayload.writeList(buffer, payload.members(), 128);
         GuildSyncPayload.writeList(buffer, payload.waypoints(), 128);
         GuildSyncPayload.writeList(buffer, payload.activityLog(), 128);
         GuildSyncPayload.writeList(buffer, payload.playerTags(), 512);
         GuildSyncPayload.writeList(buffer, payload.pcPreviews() == null ? List.of() : payload.pcPreviews(), 512);
         GuildSyncPayload.writeList(buffer, payload.chatMessages() == null ? List.of() : payload.chatMessages(), 128);
         class_1799.field_49269.encode(buffer, payload.vaultItems() == null ? List.of() : payload.vaultItems());
      }
   };

   private static List<String> readList(class_9129 buffer, int maximum) {
      int size = Math.min(buffer.method_10816(), maximum);
      List<String> values = new ArrayList<>(size);

      for (int index = 0; index < size; index++) {
         values.add(buffer.method_10800(512));
      }

      return values;
   }

   private static void writeList(class_9129 buffer, List<String> values, int maximum) {
      int size = Math.min(values.size(), maximum);
      buffer.method_10804(size);

      for (int index = 0; index < size; index++) {
         buffer.method_10788(values.get(index), 512);
      }
   }

   public class_9154<? extends class_8710> method_56479() {
      return TYPE;
   }
}
