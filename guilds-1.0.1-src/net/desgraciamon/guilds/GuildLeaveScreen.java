package net.desgraciamon.guilds;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.class_1144;
import net.minecraft.class_2561;
import net.minecraft.class_332;
import net.minecraft.class_339;
import net.minecraft.class_437;
import net.minecraft.class_6381;
import net.minecraft.class_6382;

final class GuildLeaveScreen extends class_437 {
   private final class_437 parent;
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

   GuildLeaveScreen(class_437 parent, GuildSyncPayload data) {
      super(class_2561.method_43470("Salir del gremio"));
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

   protected void method_25426() {
      this.dialogWidth = 340;
      this.dialogHeight = this.isLeader ? 210 : 125;
      this.dialogX = (this.field_22789 - this.dialogWidth) / 2;
      this.dialogY = (this.field_22790 - this.dialogHeight) / 2;
      if (this.isLeader) {
         int rowY = this.dialogY + 56;
         int availableHeight = this.dialogHeight - 105;
         this.candidateRowHeight = Math.min(24, Math.max(18, availableHeight / Math.max(1, Math.min(this.candidates.size(), 4))));
         this.candidateVisible = Math.max(1, availableHeight / this.candidateRowHeight);
         this.candidateScroll = Math.min(this.candidateScroll, Math.max(0, this.candidates.size() - this.candidateVisible));

         for (int row = 0; row < Math.min(this.candidates.size() - this.candidateScroll, this.candidateVisible); row++) {
            int index = this.candidateScroll + row;
            String candidate = this.candidates.get(index);
            this.method_37063(
               new GuildLeaveScreen.CandidateButton(
                  this.dialogX + 18, rowY + row * this.candidateRowHeight, this.dialogWidth - 36, this.candidateRowHeight - 2, candidate
               )
            );
         }
      }

      int buttonY = this.dialogY + this.dialogHeight - 34;
      this.method_37063(
         new GuildLeaveScreen.DialogButton(this.dialogX + 18, buttonY, 140, 24, "Cancelar", false, () -> this.field_22787.method_1507(this.parent))
      );
      this.method_37063(
         new GuildLeaveScreen.DialogButton(
            this.dialogX + this.dialogWidth - 158, buttonY, 140, 24, this.isLeader ? "Transferir y salir" : "Sí, salir", true, this::confirmExit
         )
      );
   }

   private void confirmExit() {
      if (!this.isLeader || !this.selectedSuccessor.isBlank()) {
         ClientPlayNetworking.send(new GuildRequestPayload("LEAVE", this.selectedSuccessor, 0));
         this.field_22787.method_1507(null);
      }
   }

   public void method_25394(class_332 graphics, int mouseX, int mouseY, float delta) {
      graphics.method_25294(0, 0, this.field_22789, this.field_22790, -1342177280);
      graphics.method_25294(this.dialogX, this.dialogY, this.dialogX + this.dialogWidth, this.dialogY + this.dialogHeight, -15130334);
      graphics.method_25294(this.dialogX, this.dialogY, this.dialogX + this.dialogWidth, this.dialogY + 2, -1876643);
      this.drawOutline(graphics, this.dialogX, this.dialogY, this.dialogWidth, this.dialogHeight, -13286331);
      graphics.method_25300(this.field_22793, "¿Seguro que quieres salir del gremio?", this.dialogX + this.dialogWidth / 2, this.dialogY + 16, -986127);
      if (this.isLeader) {
         graphics.method_25300(
            this.field_22793, "Elige al nuevo líder para transferir el mando:", this.dialogX + this.dialogWidth / 2, this.dialogY + 34, -4734274
         );
         if (this.candidates.isEmpty()) {
            graphics.method_25300(this.field_22793, "No hay otro miembro que pueda liderar.", this.dialogX + this.dialogWidth / 2, this.dialogY + 75, -1869190);
         }
      } else {
         graphics.method_25300(
            this.field_22793, "Se aplicará un tiempo de espera antes de unirte a otro.", this.dialogX + this.dialogWidth / 2, this.dialogY + 44, -4734274
         );
      }

      super.method_25394(graphics, mouseX, mouseY, delta);
   }

   private void drawOutline(class_332 graphics, int x, int y, int w, int h, int color) {
      graphics.method_25294(x, y, x + w, y + 1, color);
      graphics.method_25294(x, y + h - 1, x + w, y + h, color);
      graphics.method_25294(x, y + 1, x + 1, y + h - 1, color);
      graphics.method_25294(x + w - 1, y + 1, x + w, y + h - 1, color);
   }

   public void method_57734(float delta) {
   }

   private final class CandidateButton extends class_339 {
      private final String candidate;

      private CandidateButton(int x, int y, int width, int height, String candidate) {
         super(x, y, width, height, class_2561.method_43470(candidate));
         this.candidate = candidate;
      }

      protected void method_48579(class_332 graphics, int mouseX, int mouseY, float delta) {
         boolean selected = this.candidate.equals(GuildLeaveScreen.this.selectedSuccessor);
         int bg = selected ? -13807798 : (this.method_49606() ? -14405582 : -14998748);
         int border = selected ? -10764121 : (this.method_49606() ? -12626858 : -13944260);
         graphics.method_25294(this.method_46426(), this.method_46427(), this.method_46426() + this.field_22758, this.method_46427() + this.field_22759, bg);
         GuildLeaveScreen.this.drawOutline(graphics, this.method_46426(), this.method_46427(), this.field_22758, this.field_22759, border);
         graphics.method_51433(
            GuildLeaveScreen.this.field_22793,
            this.candidate,
            this.method_46426() + 8,
            this.method_46427() + (this.field_22759 - 8) / 2,
            selected ? -1 : -3352620,
            false
         );
         if (selected) {
            graphics.method_51433(
               GuildLeaveScreen.this.field_22793,
               "✔",
               this.method_46426() + this.field_22758 - 16,
               this.method_46427() + (this.field_22759 - 8) / 2,
               -10764121,
               false
            );
         }
      }

      public void method_25354(class_1144 soundManager) {
      }

      public void method_25348(double mouseX, double mouseY) {
         GuildLeaveScreen.this.selectedSuccessor = this.candidate;
         GuildUiSound.play();
      }

      protected void method_47399(class_6382 narration) {
         narration.method_37034(class_6381.field_33788, this.method_25369());
      }
   }

   private final class DialogButton extends class_339 {
      private final Runnable action;
      private final boolean primary;

      private DialogButton(int x, int y, int width, int height, String label, boolean primary, Runnable action) {
         super(x, y, width, height, class_2561.method_43470(label));
         this.action = action;
         this.primary = primary;
      }

      protected void method_48579(class_332 graphics, int mouseX, int mouseY, float delta) {
         int bg = this.primary ? (this.method_49606() ? -3585462 : -6081227) : (this.method_49606() ? -13483709 : -14537683);
         int border = this.primary ? -1876643 : (this.method_49606() ? -11311255 : -13286331);
         graphics.method_25294(this.method_46426(), this.method_46427(), this.method_46426() + this.field_22758, this.method_46427() + this.field_22759, bg);
         GuildLeaveScreen.this.drawOutline(graphics, this.method_46426(), this.method_46427(), this.field_22758, this.field_22759, border);
         graphics.method_27534(
            GuildLeaveScreen.this.field_22793,
            this.method_25369(),
            this.method_46426() + this.field_22758 / 2,
            this.method_46427() + (this.field_22759 - 8) / 2,
            -1
         );
      }

      public void method_25354(class_1144 soundManager) {
      }

      public void method_25348(double mouseX, double mouseY) {
         GuildUiSound.play();
         this.action.run();
      }

      protected void method_47399(class_6382 narration) {
         narration.method_37034(class_6381.field_33788, this.method_25369());
      }
   }
}
