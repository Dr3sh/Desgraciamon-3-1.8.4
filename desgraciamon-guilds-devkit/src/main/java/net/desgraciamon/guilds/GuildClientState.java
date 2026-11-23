package net.desgraciamon.guilds;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GuildClientState {
   static volatile GuildSyncPayload data = new GuildSyncPayload(
      false,
      "Sin gremio",
      "#FFFFFF",
      "",
      0,
      BigInteger.ZERO,
      BigInteger.ZERO,
      "",
      "pokeball",
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      List.of()
   );
   public static final Map<UUID, GuildClientState.NameTag> nameTags = new HashMap<>();
   static volatile boolean showGuildWaypoints = true;
   public static volatile boolean debugShowOwnNameTag = false;
   public static volatile int synergyNearbyCount = 0;
   public static volatile String synergyGuildName = "";
   public static volatile String synergyGuildColorHex = "#FFFFFF";

   private GuildClientState() {
   }

   static int parseColor(String color) {
      try {
         return Integer.parseInt(color.substring(1), 16);
      } catch (RuntimeException var2) {
         return 16777215;
      }
   }

   public static void reset() {
      data = new GuildSyncPayload(
         false,
         "Sin gremio",
         "#FFFFFF",
         "",
         0,
         BigInteger.ZERO,
         BigInteger.ZERO,
         "",
         "pokeball",
         List.of(),
         List.of(),
         List.of(),
         List.of(),
         List.of(),
         List.of(),
         List.of()
      );
      synchronized (nameTags) {
         nameTags.clear();
      }

      synergyNearbyCount = 0;
      synergyGuildName = "";
      synergyGuildColorHex = "#FFFFFF";
   }

   static void update(GuildSyncPayload payload) {
      data = payload;
      synchronized (nameTags) {
         nameTags.clear();

         for (String entry : payload.playerTags()) {
            String[] parts = entry.split("\\|", 4);
            if (parts.length >= 3) {
               try {
                  String role = parts.length >= 4 && !parts[3].isBlank() ? parts[3] : "MEMBER";
                  nameTags.put(UUID.fromString(parts[0]), new GuildClientState.NameTag(parts[1], parseColor(parts[2]), role));
               } catch (IllegalArgumentException var7) {
               }
            }
         }
      }
   }

   public static record NameTag(String name, int color, String role) {
      public NameTag(String name, int color) {
         this(name, color, "MEMBER");
      }
   }
}
