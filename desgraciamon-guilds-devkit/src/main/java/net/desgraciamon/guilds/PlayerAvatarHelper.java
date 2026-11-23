package net.desgraciamon.guilds;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;

public final class PlayerAvatarHelper {
   private static final Map<String, ResourceLocation> SKIN_CACHE = new ConcurrentHashMap<>();

   private PlayerAvatarHelper() {
   }

   public static void clearCache() {
      SKIN_CACHE.clear();
   }

   public static ResourceLocation getSkinTexture(String uuidStr, String playerName) {
      Minecraft mc = Minecraft.getInstance();
      UUID uuid = null;

      try {
         if (uuidStr != null && !uuidStr.isBlank()) {
            uuid = UUID.fromString(uuidStr);
         }
      } catch (IllegalArgumentException var10) {
      }

      if (mc.player != null) {
         boolean isLocal = uuid != null && uuid.equals(mc.player.getUUID())
            || playerName != null && playerName.equalsIgnoreCase(mc.player.getGameProfile().getName());
         if (isLocal) {
            PlayerSkin skin = mc.player.getSkin();
            if (skin != null && skin.texture() != null) {
               return skin.texture();
            }
         }
      }

      if (mc.getConnection() != null) {
         PlayerInfo info = uuid != null ? mc.getConnection().getPlayerInfo(uuid) : mc.getConnection().getPlayerInfo(playerName);
         if (info != null && info.getSkin() != null && info.getSkin().texture() != null) {
            ResourceLocation tex = info.getSkin().texture();
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

      ResourceLocation cached = SKIN_CACHE.get(cacheKey);
      if (cached != null) {
         return cached;
      } else {
         UUID profileUuid = uuid != null
            ? uuid
            : UUID.nameUUIDFromBytes(("OfflinePlayer:" + (playerName != null ? playerName : "Steve")).getBytes(StandardCharsets.UTF_8));
         GameProfile profile = new GameProfile(profileUuid, playerName != null ? playerName : "Steve");
         PlayerSkin skin = mc.getSkinManager().getInsecureSkin(profile);
         ResourceLocation tex = skin.texture();
         SKIN_CACHE.put(cacheKey, tex);
         return tex;
      }
   }

   public static void drawPlayerHead(GuiGraphics graphics, String uuidStr, String playerName, int x, int y, int size) {
      ResourceLocation skin = getSkinTexture(uuidStr, playerName);
      PlayerFaceRenderer.draw(graphics, skin, x, y, size);
   }
}
