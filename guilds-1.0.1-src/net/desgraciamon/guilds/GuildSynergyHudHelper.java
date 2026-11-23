package net.desgraciamon.guilds;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import net.minecraft.class_310;
import net.minecraft.class_327;
import net.minecraft.class_332;

public final class GuildSynergyHudHelper {
   private static final class_2960 EXP_BAR_BACKGROUND = class_2960.method_60656("hud/experience_bar_background");
   private static final class_2960 EXP_BAR_PROGRESS_WHITE = class_2960.method_60655("desgraciamon_guilds", "textures/gui/experience_bar_progress_white.png");
   private static int lastNearbyCount = 0;

   private GuildSynergyHudHelper() {
   }

   public static boolean isSynergyActive() {
      class_310 mc = class_310.method_1551();
      if (mc.field_1724 == null || mc.field_1761 == null || !mc.field_1761.method_2913()) {
         return false;
      } else if (mc.field_1724.method_45773() != null) {
         return false;
      } else if (GuildClientState.synergyNearbyCount < 2) {
         return false;
      } else {
         GuildClientConfig.SynergyBarMode mode = GuildClientConfig.getSynergyBarMode();
         if (mode == GuildClientConfig.SynergyBarMode.OFF) {
            return false;
         } else if (mode == GuildClientConfig.SynergyBarMode.ALWAYS) {
            return true;
         } else {
            return mode == GuildClientConfig.SynergyBarMode.CYCLE ? System.currentTimeMillis() / 6000L % 2L == 0L : false;
         }
      }
   }

   public static int getGuildColor() {
      int color = GuildClientState.parseColor(GuildClientState.synergyGuildColorHex);
      if ((color & 16777215) == 0) {
         color = 3716863;
      }

      return color;
   }

   public static void renderSynergyBar(class_332 graphics, int x) {
      int y = graphics.method_51443() - 32 + 3;
      RenderSystem.enableBlend();
      graphics.method_52706(EXP_BAR_BACKGROUND, x, y, 182, 5);
      int color = getGuildColor();
      float r = (float)(color >> 16 & 0xFF) / 255.0F;
      float g = (float)(color >> 8 & 0xFF) / 255.0F;
      float b = (float)(color & 0xFF) / 255.0F;
      graphics.method_51422(r, g, b, 1.0F);
      graphics.method_25290(EXP_BAR_PROGRESS_WHITE, x, y, 0.0F, 0.0F, 182, 5, 182, 5);
      graphics.method_51422(1.0F, 1.0F, 1.0F, 1.0F);
      RenderSystem.disableBlend();
   }

   public static void renderSynergyLevel(class_332 graphics, class_327 font) {
      int count = GuildClientState.synergyNearbyCount;
      String text = count >= 6 ? "+5" : String.valueOf(count);
      int textWidth = font.method_1727(text);
      int x = (graphics.method_51421() - textWidth) / 2;
      int y = graphics.method_51443() - 31 - 4;
      int color = getGuildColor();
      graphics.method_51433(font, text, x + 1, y, 0, false);
      graphics.method_51433(font, text, x - 1, y, 0, false);
      graphics.method_51433(font, text, x, y + 1, 0, false);
      graphics.method_51433(font, text, x, y - 1, 0, false);
      graphics.method_51433(font, text, x, y, 0xFF000000 | color, false);
   }

   public static void clientTick(class_310 client) {
      if (client.field_1724 != null) {
         int currentCount = GuildClientState.synergyNearbyCount;
         GuildClientConfig.SynergyBarMode mode = GuildClientConfig.getSynergyBarMode();
         if (mode != GuildClientConfig.SynergyBarMode.OFF && currentCount >= 2 && currentCount != lastNearbyCount) {
            int xp = GuildSynergyManager.getXpBonusPercent(currentCount);
            int money = GuildSynergyManager.getMoneyBonusPercent(currentCount);
            StringBuilder sb = new StringBuilder("✦ +" + xp + "% XP | +" + money + "% $");
            if (currentCount >= 5) {
               sb.append(" | +25% Shiny rate");
            }

            sb.append(" ✦");
            int color = getGuildColor();
            class_2561 msg = class_2561.method_43470(sb.toString()).method_27694(style -> style.method_36139(color).method_10982(true));
            client.field_1724.method_7353(msg, true);
         }

         lastNearbyCount = currentCount;
      }
   }
}
