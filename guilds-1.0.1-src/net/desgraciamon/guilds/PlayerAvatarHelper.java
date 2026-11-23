package net.desgraciamon.guilds;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.class_2960;
import net.minecraft.class_310;
import net.minecraft.class_332;
import net.minecraft.class_640;
import net.minecraft.class_7532;
import net.minecraft.class_8685;

public final class PlayerAvatarHelper {
   private static final Map<String, class_2960> SKIN_CACHE = new ConcurrentHashMap<>();

   private PlayerAvatarHelper() {
   }

   public static void clearCache() {
      SKIN_CACHE.clear();
   }

   public static class_2960 getSkinTexture(String uuidStr, String playerName) {
      class_310 mc = class_310.method_1551();
      UUID uuid = null;

      try {
         if (uuidStr != null && !uuidStr.isBlank()) {
            uuid = UUID.fromString(uuidStr);
         }
      } catch (IllegalArgumentException var10) {
      }

      if (mc.field_1724 != null) {
         boolean isLocal = uuid != null && uuid.equals(mc.field_1724.method_5667())
            || playerName != null && playerName.equalsIgnoreCase(mc.field_1724.method_7334().getName());
         if (isLocal) {
            class_8685 skin = mc.field_1724.method_52814();
            if (skin != null && skin.comp_1626() != null) {
               return skin.comp_1626();
            }
         }
      }

      if (mc.method_1562() != null) {
         class_640 info = uuid != null ? mc.method_1562().method_2871(uuid) : mc.method_1562().method_2874(playerName);
         if (info != null && info.method_52810() != null && info.method_52810().comp_1626() != null) {
            class_2960 tex = info.method_52810().comp_1626();
            String cacheKey = uuidStr != null && !uuidStr.isBlank() ? uuidStr : playerName;
            if (cacheKey != null) {
               SKIN_CACHE.put(cacheKey, tex);
            }

            return tex;
         }
      }

      String cacheKey = uuidStr != null && !uuidStr.isBlank() ? uuidStr : playerName;
      if (cacheKey == null || cacheKey.isBlank()) {
         cacheKey = "default";
      }

      class_2960 cached = SKIN_CACHE.get(cacheKey);
      if (cached != null) {
         return cached;
      } else {
         UUID profileUuid = uuid != null
            ? uuid
            : UUID.nameUUIDFromBytes(("OfflinePlayer:" + (playerName != null ? playerName : "Steve")).getBytes(StandardCharsets.UTF_8));
         GameProfile profile = new GameProfile(profileUuid, playerName != null ? playerName : "Steve");
         class_8685 skin = mc.method_1582().method_52862(profile);
         class_2960 tex = skin.comp_1626();
         SKIN_CACHE.put(cacheKey, tex);
         return tex;
      }
   }

   public static void drawPlayerHead(class_332 graphics, String uuidStr, String playerName, int x, int y, int size) {
      class_2960 skin = getSkinTexture(uuidStr, playerName);
      class_7532.method_44443(graphics, skin, x, y, size);
   }
}
