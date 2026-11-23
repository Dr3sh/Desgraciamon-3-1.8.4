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
      this.isLeader = "LEADER".equals(data.role());
      this.candidates = new ArrayList<>();

      for (String member : data.members()) {
         String[] fields = member.split("\\s*\\|\\s*", 3);
         if (fields.length >= 2 && !fields[1].equals("LEADER")) {
            this.candidates.add(fields[0]);
         }
      }
   }

   protected void init() {
      this.dialogWidth = 340;
      this.dialogHeight = this.isLeader ? 210 : 125;
      this.dialogX = (this.width - this.dialogWidth) / 2;
      this.dialogY = (this.height - this.dialogHeight) / 2;
      if (this.isLeader) {
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
      this.addRenderableWidget(
         new GuildLeaveScreen.DialogButton(
            this.dialogX + this.dialogWidth - 158, buttonY, 140, 24, this.isLeader ? "Transferir y salir" : "Sí, salir", true, this::confirmExit
         )
      );
   }

   private void confirmExit() {
      if (!this.isLeader || !this.selectedSuccessor.isBlank()) {
         ClientPlayNetworking.send(new GuildRequestPayload("LEAVE", this.selectedSuccessor, 0));
         this.minecraft.setScreen(null);
      }
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      graphics.fill(0, 0, this.width, this.height, -1342177280);
      graphics.fill(this.dialogX, this.dialogY, this.dialogX + this.dialogWidth, this.dialogY + this.dialogHeight, -15130334);
      graphics.fill(this.dialogX, this.dialogY, this.dialogX + this.dialogWidth, this.dialogY + 2, -1876643);
      this.drawOutline(graphics, this.dialogX, this.dialogY, this.dialogWidth, this.dialogHeight, -13286331);
      graphics.drawCenteredString(this.font, "¿Seguro que quieres salir del gremio?", this.dialogX + this.dialogWidth / 2, this.dialogY + 16, -986127);
      if (this.isLeader) {
         graphics.drawCenteredString(
            this.font, "Elige al nuevo líder para transferir el mando:", this.dialogX + this.dialogWidth / 2, this.dialogY + 34, -4734274
         );
         if (this.candidates.isEmpty()) {
            graphics.drawCenteredString(this.font, "No hay otro miembro que pueda liderar.", this.dialogX + this.dialogWidth / 2, this.dialogY + 75, -1869190);
         }
      } else {
         graphics.drawCenteredString(
            this.font, "Se aplicará un tiempo de espera antes de unirte a otro.", this.dialogX + this.dialogWidth / 2, this.dialogY + 44, -4734274
         );
      }

      super.render(graphics, mouseX, mouseY, delta);
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
         int bg = selected ? -13807798 : (this.isHovered() ? -14405582 : -14998748);
         int border = selected ? -10764121 : (this.isHovered() ? -12626858 : -13944260);
         graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, bg);
         GuildLeaveScreen.this.drawOutline(graphics, this.getX(), this.getY(), this.width, this.height, border);
         graphics.drawString(GuildLeaveScreen.this.font, this.candidate, this.getX() + 8, this.getY() + (this.height - 8) / 2, selected ? -1 : -3352620, false);
         if (selected) {
            graphics.drawString(GuildLeaveScreen.this.font, "✔", this.getX() + this.width - 16, this.getY() + (this.height - 8) / 2, -10764121, false);
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
         int bg = this.primary ? (this.isHovered() ? -3585462 : -6081227) : (this.isHovered() ? -13483709 : -14537683);
         int border = this.primary ? -1876643 : (this.isHovered() ? -11311255 : -13286331);
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
