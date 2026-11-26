package net.desgraciamon.guilds;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

final class GuildLeaveScreen extends Screen {
   private final Screen parent;
   private final boolean isLeader;
   private final List<String> candidates;
   private String selectedSuccessor = "";
   private final long openedAtNanos = System.nanoTime();
   private float uiScale = 1.0F;
   private float renderX = 0.0F;
   private float renderY = 0.0F;
   private int dialogX;
   private int dialogY;
   private int dialogWidth;
   private int dialogHeight;
   private int candidateScroll;
   private int candidateVisible;
   private int candidateRowHeight;

   GuildLeaveScreen(Screen parent, GuildSyncPayload data) {
      super(Component.literal("Salir del gremio"));
      this.parent = parent;
      this.candidates = new ArrayList<>();
      int leaderCount = 0;
      for (String member : data.members()) {
         String[] fields = member.split("\\s*\\|\\s*", 3);
         if (fields.length >= 2) {
            if (fields[1].equals("LEADER")) {
               leaderCount++;
            } else {
               this.candidates.add(fields[0]);
            }
         }
      }
      // Si hay otro lider activo, no se necesita elegir sucesor al salir
      this.isLeader = "LEADER".equals(data.role()) && leaderCount <= 1;
   }

   private float openingProgress() {
      return Math.min(1.0F, (float)(System.nanoTime() - this.openedAtNanos) / 2.8E8F);
   }

   protected void init() {
      this.dialogWidth = 340;
      boolean soloLeader = this.isLeader && this.candidates.isEmpty();
      this.dialogHeight = soloLeader ? 145 : (this.isLeader ? 210 : 125);
      float maxW = Math.max(180.0F, (float)this.width - 24.0F);
      float maxH = Math.max(120.0F, (float)this.height - 24.0F);
      this.uiScale = Math.min(1.0F, Math.min(maxW / (float)this.dialogWidth, maxH / (float)this.dialogHeight));
      this.renderX = ((float)this.width - (float)this.dialogWidth * this.uiScale) / 2.0F;
      this.renderY = ((float)this.height - (float)this.dialogHeight * this.uiScale) / 2.0F;
      this.dialogX = 0;
      this.dialogY = 0;
      if (this.isLeader && !this.candidates.isEmpty()) {
         int rowY = this.dialogY + 56;
         int availableHeight = this.dialogHeight - 105;
         this.candidateRowHeight = Math.min(24, Math.max(18, availableHeight / Math.max(1, Math.min(this.candidates.size(), 4))));
         this.candidateVisible = Math.max(1, availableHeight / this.candidateRowHeight);
         this.candidateScroll = Math.min(this.candidateScroll, Math.max(0, this.candidates.size() - this.candidateVisible));

         for (int row = 0; row < Math.min(this.candidates.size() - this.candidateScroll, this.candidateVisible); row++) {
            int index = this.candidateScroll + row;
            String candidate = this.candidates.get(index);
            this.addRenderableWidget(
               new GuildLeaveScreen.CandidateButton(
                  this.dialogX + 18, rowY + row * this.candidateRowHeight, this.dialogWidth - 36, this.candidateRowHeight - 2, candidate
               )
            );
         }
      }

      int buttonY = this.dialogY + this.dialogHeight - 34;
      this.addRenderableWidget(
         new GuildLeaveScreen.DialogButton(this.dialogX + 18, buttonY, 140, 24, "Cancelar", false, () -> this.minecraft.setScreen(this.parent))
      );
      String confirmText = soloLeader ? "Disolver y salir" : (this.isLeader ? "Transferir y salir" : "Sí, salir");
      this.addRenderableWidget(
         new GuildLeaveScreen.DialogButton(
            this.dialogX + this.dialogWidth - 158, buttonY, 140, 24, confirmText, true, this::confirmExit
         )
      );
   }

   private void confirmExit() {
      if (this.isLeader && this.candidates.isEmpty()) {
         ClientPlayNetworking.send(new GuildRequestPayload("LEAVE", "", 0));
         this.minecraft.setScreen(null);
      } else if (!this.isLeader || !this.selectedSuccessor.isBlank()) {
         ClientPlayNetworking.send(new GuildRequestPayload("LEAVE", this.selectedSuccessor, 0));
         this.minecraft.setScreen(null);
      }
   }

   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      if (this.openingProgress() < 1.0F) return true;
      double localX = (mouseX - (double)this.renderX) / (double)this.uiScale;
      double localY = (mouseY - (double)this.renderY) / (double)this.uiScale;
      return super.mouseClicked(localX, localY, button);
   }

   public boolean mouseReleased(double mouseX, double mouseY, int button) {
      double localX = (mouseX - (double)this.renderX) / (double)this.uiScale;
      double localY = (mouseY - (double)this.renderY) / (double)this.uiScale;
      return super.mouseReleased(localX, localY, button);
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      graphics.fill(0, 0, this.width, this.height, -1342177280);
      int localMouseX = Math.round(((float)mouseX - this.renderX) / this.uiScale);
      int localMouseY = Math.round(((float)mouseY - this.renderY) / this.uiScale);

      graphics.pose().pushPose();
      graphics.pose().translate(this.renderX, this.renderY, 0.0F);
      graphics.pose().scale(this.uiScale, this.uiScale, 1.0F);
      float progress = this.openingProgress();
      if (progress < 1.0F) {
         float eased = 1.0F - (float)Math.pow((double)(1.0F - progress), 3.0);
         float animScale = 0.08F + eased * 0.92F;
         float cx = (float)this.dialogWidth / 2.0F;
         float cy = (float)this.dialogHeight / 2.0F;
         graphics.pose().translate(cx, cy, 0.0F);
         graphics.pose().scale(animScale, animScale, 1.0F);
         graphics.pose().translate(-cx, -cy, 0.0F);
      }

      graphics.fill(this.dialogX, this.dialogY, this.dialogX + this.dialogWidth, this.dialogY + this.dialogHeight, GuildScreen.tintSurface(0x191F22, 0.22F, 0xFF));
      graphics.fill(this.dialogX, this.dialogY, this.dialogX + this.dialogWidth, this.dialogY + 2, -1876643);
      this.drawOutline(graphics, this.dialogX, this.dialogY, this.dialogWidth, this.dialogHeight, GuildScreen.tintSurface(0x354445, 0.38F, 0xFF));
      graphics.drawCenteredString(this.font, "¿Seguro que quieres salir del gremio?", this.dialogX + this.dialogWidth / 2, this.dialogY + 16, -986127);
      if (this.isLeader) {
         if (this.candidates.isEmpty()) {
            graphics.drawCenteredString(this.font, "§c¡Eres el único miembro de este gremio!", this.dialogX + this.dialogWidth / 2, this.dialogY + 34, -2069387);
            graphics.drawCenteredString(this.font, "§eAl salir, el gremio se disolverá por completo y se", this.dialogX + this.dialogWidth / 2, this.dialogY + 48, -1905690);
            graphics.drawCenteredString(this.font, "§eperderán todos los Pokémon del PC y cofres.", this.dialogX + this.dialogWidth / 2, this.dialogY + 60, -1905690);
            graphics.drawCenteredString(this.font, "§a¡No se te aplicará cooldown por estar solo!", this.dialogX + this.dialogWidth / 2, this.dialogY + 76, -11414454);
         } else {
            graphics.drawCenteredString(
               this.font, "Elige al nuevo líder para transferir el mando:", this.dialogX + this.dialogWidth / 2, this.dialogY + 34, -4734274
            );
         }
      } else {
         graphics.drawCenteredString(
            this.font, "Se aplicará un tiempo de espera antes de unirte a otro.", this.dialogX + this.dialogWidth / 2, this.dialogY + 44, -4734274
         );
      }

      super.render(graphics, localMouseX, localMouseY, delta);
      graphics.pose().popPose();
   }

   private void drawOutline(GuiGraphics graphics, int x, int y, int w, int h, int color) {
      graphics.fill(x, y, x + w, y + 1, color);
      graphics.fill(x, y + h - 1, x + w, y + h, color);
      graphics.fill(x, y + 1, x + 1, y + h - 1, color);
      graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
   }

   public void renderBlurredBackground(float delta) {
   }

   private final class CandidateButton extends AbstractWidget {
      private final String candidate;

      private CandidateButton(int x, int y, int width, int height, String candidate) {
         super(x, y, width, height, Component.literal(candidate));
         this.candidate = candidate;
      }

      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
         boolean selected = this.candidate.equals(GuildLeaveScreen.this.selectedSuccessor);
         int bg = selected
            ? GuildScreen.accentSurface()
            : (this.isHovered() ? GuildScreen.tintSurface(0x242F32, 0.30F, 0xFF) : GuildScreen.tintSurface(0x1B2424, 0.22F, 0xFF));
         int border = selected
            ? GuildScreen.accentColor()
            : (this.isHovered() ? GuildScreen.tintSurface(0x3F4F56, 0.42F, 0xFF) : GuildScreen.tintSurface(0x2B383C, 0.34F, 0xFF));
         graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, bg);
         GuildLeaveScreen.this.drawOutline(graphics, this.getX(), this.getY(), this.width, this.height, border);
         graphics.drawString(GuildLeaveScreen.this.font, this.candidate, this.getX() + 8, this.getY() + (this.height - 8) / 2, selected ? -1 : -3352620, false);
         if (selected) {
            graphics.drawString(GuildLeaveScreen.this.font, "[OK]", this.getX() + this.width - 16, this.getY() + (this.height - 8) / 2, GuildScreen.accentColor(), false);
         }
      }

      public void playDownSound(SoundManager soundManager) {
      }

      public void onClick(double mouseX, double mouseY) {
         GuildLeaveScreen.this.selectedSuccessor = this.candidate;
         GuildUiSound.play();
      }

      protected void updateWidgetNarration(NarrationElementOutput narration) {
         narration.add(NarratedElementType.TITLE, this.getMessage());
      }
   }

   private final class DialogButton extends AbstractWidget {
      private final Runnable action;
      private final boolean primary;

      private DialogButton(int x, int y, int width, int height, String label, boolean primary, Runnable action) {
         super(x, y, width, height, Component.literal(label));
         this.action = action;
         this.primary = primary;
      }

      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
         int bg = this.primary
            ? (this.isHovered() ? -3585462 : -6081227)
            : (this.isHovered() ? GuildScreen.tintSurface(0x324043, 0.34F, 0xFF) : GuildScreen.tintSurface(0x222D2D, 0.26F, 0xFF));
         int border = this.primary
            ? -1876643
            : (this.isHovered() ? GuildScreen.accentColor() : GuildScreen.tintSurface(0x354445, 0.38F, 0xFF));
         graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, bg);
         GuildLeaveScreen.this.drawOutline(graphics, this.getX(), this.getY(), this.width, this.height, border);
         graphics.drawCenteredString(GuildLeaveScreen.this.font, this.getMessage(), this.getX() + this.width / 2, this.getY() + (this.height - 8) / 2, -1);
      }

      public void playDownSound(SoundManager soundManager) {
      }

      public void onClick(double mouseX, double mouseY) {
         GuildUiSound.play();
         this.action.run();
      }

      protected void updateWidgetNarration(NarrationElementOutput narration) {
         narration.add(NarratedElementType.TITLE, this.getMessage());
      }
   }
}
