package net.desgraciamon.guilds;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class GuildSynergyHudHelper {
   private static final ResourceLocation EXP_BAR_BACKGROUND = ResourceLocation.withDefaultNamespace("hud/experience_bar_background");
   private static final ResourceLocation EXP_BAR_PROGRESS_WHITE = ResourceLocation.fromNamespaceAndPath(
      "desgraciamon_guilds", "textures/gui/experience_bar_progress_white.png"
   );
   private static int lastNearbyCount = 0;

   private GuildSynergyHudHelper() {
   }

   public static boolean isSynergyActive() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player == null || mc.gameMode == null || !mc.gameMode.hasExperience()) {
         return false;
      } else if (mc.player.jumpableVehicle() != null) {
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

   public static void renderSynergyBar(GuiGraphics graphics, int x) {
      int y = graphics.guiHeight() - 32 + 3;
      RenderSystem.enableBlend();
      graphics.blitSprite(EXP_BAR_BACKGROUND, x, y, 182, 5);
      int color = getGuildColor();
      float r = (float)(color >> 16 & 0xFF) / 255.0F;
      float g = (float)(color >> 8 & 0xFF) / 255.0F;
      float b = (float)(color & 0xFF) / 255.0F;
      graphics.setColor(r, g, b, 1.0F);
      graphics.blit(EXP_BAR_PROGRESS_WHITE, x, y, 0.0F, 0.0F, 182, 5, 182, 5);
      graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
      RenderSystem.disableBlend();
   }

   public static void renderSynergyLevel(GuiGraphics graphics, Font font) {
      int count = GuildClientState.synergyNearbyCount;
      String text = count >= 6 ? "+5" : String.valueOf(count);
      int textWidth = font.width(text);
      int x = (graphics.guiWidth() - textWidth) / 2;
      int y = graphics.guiHeight() - 31 - 4;
      int color = getGuildColor();
      graphics.drawString(font, text, x + 1, y, 0, false);
      graphics.drawString(font, text, x - 1, y, 0, false);
      graphics.drawString(font, text, x, y + 1, 0, false);
      graphics.drawString(font, text, x, y - 1, 0, false);
      graphics.drawString(font, text, x, y, 0xFF000000 | color, false);
   }

   public static void clientTick(Minecraft client) {
      if (client.player != null) {
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
            Component msg = Component.literal(sb.toString()).withStyle(style -> style.withColor(color).withBold(true));
            client.player.displayClientMessage(msg, true);
         }

         lastNearbyCount = currentCount;
      }
   }
}
