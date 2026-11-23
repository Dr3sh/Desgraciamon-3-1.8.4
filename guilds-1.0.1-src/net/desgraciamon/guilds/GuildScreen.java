package net.desgraciamon.guilds;

import com.cobblemon.mod.common.CobblemonSounds;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.storage.ClientBox;
import com.cobblemon.mod.common.client.storage.ClientPC;
import com.cobblemon.mod.common.client.storage.ClientParty;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokemon.EVs;
import com.cobblemon.mod.common.pokemon.IVs;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.awt.Color;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.class_1109;
import net.minecraft.class_1144;
import net.minecraft.class_1657;
import net.minecraft.class_1799;
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import net.minecraft.class_332;
import net.minecraft.class_339;
import net.minecraft.class_3417;
import net.minecraft.class_342;
import net.minecraft.class_437;
import net.minecraft.class_5481;
import net.minecraft.class_6381;
import net.minecraft.class_6382;
import net.minecraft.class_640;
import net.minecraft.class_746;
import net.minecraft.class_7919;

final class GuildScreen extends class_437 {
   private static final int BASE_WIDTH = 540;
   private static final int BASE_HEIGHT = 330;
   private float uiScale = 1.0F;
   private float renderX = 0.0F;
   private float renderY = 0.0F;
   private static final String[] TABS = new String[]{"General", "Miembros", "PC", "Inventario", "Waypoints", "Registro", "Personalizar"};
   private static final String[] GUILD_ICONS = new String[]{"pokeball", "espada", "escudo", "dragon", "rayo", "estrella", "corazon"};
   private static final String[] DIM_FILTERS = new String[]{"Todas", "Overworld", "Nether", "End"};
   private GuildScreen.HeldVaultItem heldVaultItem = null;
   private class_1799 hoveredVaultTooltip = class_1799.field_8037;
   private String hoveredLogTooltip = "";
   private static final Map<String, class_1799> ITEM_CACHE = new HashMap<>();
   private final int tab;
   private class_342 nameInput;
   private class_342 colorInput;
   private class_342 actionInput;
   private class_342 amountInput;
   private class_342 chatInput;
   private String draftChat = "";
   private int chatScrollOffset = 0;
   private class_342 searchMemberInput;
   private class_342 motdEditInput;
   private boolean colorMenuOpen;
   private boolean editingMotd = false;
   private String draftName = "";
   private String draftColor = "55AAFF";
   private String draftAction = "";
   private String draftAmount = "";
   private String draftMotd = "";
   private String draftIcon = "pokeball";
   private String memberFilter = "";
   private int waypointDimFilter = 0;
   private List<XaeroWaypointBridge.LocalWaypoint> localWaypoints = List.of();
   private int selectedLocalWaypoint = -1;
   private String selectedGuildWaypoint = "";
   private GuildScreen.PendingWaypoint pendingUpload;
   private int localWaypointScroll;
   private int sharedWaypointScroll;
   private int memberScrollOffset;
   private int logScrollOffset;
   private int panelX;
   private int panelY;
   private int panelWidth;
   private int panelHeight;
   private int contentX;
   private int contentWidth;
   private boolean guildMode;
   private boolean closeAfterCreate;
   private final boolean animateOpening;
   private final long openedAtNanos;
   private int selectedPcBox = 0;
   private static int privatePcBox = 0;
   private int logFilterTab = 0;
   private GuildScreen.PokemonSlotPreview hoveredLogPokemon = null;
   private List<class_2561> hoveredMemberTooltip = null;
   private List<String> playerSuggestions = new ArrayList<>();
   private GuildScreen.HeldPokemon heldPokemon = null;
   private String warningBanner = "";
   private int warningBannerTicks = 0;

   private static class_1799 getOrCreatePokemonStack(String species, boolean shiny, String form) {
      String key = species + ":" + shiny + ":" + form;
      if (ITEM_CACHE.containsKey(key)) {
         return ITEM_CACHE.get(key);
      } else {
         try {
            StringBuilder query = new StringBuilder(species);
            if (shiny) {
               query.append(" shiny=yes");
            }

            Set<String> aspectsToAdd = new HashSet<>();
            if (form != null && !form.isBlank() && !form.equalsIgnoreCase("normal") && !form.equalsIgnoreCase("standard")) {
               for (String part : form.trim().split("[\\s,]+")) {
                  if (!part.equalsIgnoreCase("normal") && !part.equalsIgnoreCase("standard")) {
                     String lower = part.toLowerCase(Locale.ROOT);
                     if (lower.contains("alola")) {
                        aspectsToAdd.add("alolan");
                        aspectsToAdd.add("alola");
                     } else if (lower.contains("galar")) {
                        aspectsToAdd.add("galarian");
                        aspectsToAdd.add("galar");
                     } else if (lower.contains("hisui")) {
                        aspectsToAdd.add("hisuian");
                        aspectsToAdd.add("hisui");
                     } else if (lower.contains("paldea")) {
                        aspectsToAdd.add("paldean");
                        aspectsToAdd.add("paldea");
                     } else {
                        aspectsToAdd.add(lower);
                     }
                  }
               }
            }

            for (String asp : aspectsToAdd) {
               query.append(" ").append(asp);
            }

            PokemonProperties props = PokemonProperties.Companion.parse(query.toString());
            props.getAspects().addAll(aspectsToAdd);
            class_1799 stack = PokemonItem.from(props, 1);
            ITEM_CACHE.put(key, stack);
            return stack;
         } catch (Throwable var11) {
            ITEM_CACHE.put(key, class_1799.field_8037);
            return class_1799.field_8037;
         }
      }
   }

   private List<GuildScreen.PokemonSlotPreview> parsePreviews(GuildSyncPayload data) {
      List<GuildScreen.PokemonSlotPreview> list = new ArrayList<>();
      if (data.pcPreviews() == null) {
         return list;
      } else {
         for (String entry : data.pcPreviews()) {
            String[] parts = entry.split("\\|", 11);
            if (parts.length >= 6) {
               try {
                  int b = Integer.parseInt(parts[0]);
                  int s = Integer.parseInt(parts[1]);
                  String species = parts[2];
                  int lvl = Integer.parseInt(parts[3]);
                  boolean shiny = "1".equals(parts[4]);
                  String name = parts[5];
                  String form = parts.length > 6 ? parts[6] : "";
                  String depositor = parts.length > 7 && !parts[7].isBlank() ? parts[7] : "Miembro";
                  String ivs = parts.length > 8 ? parts[8] : "";
                  String evs = parts.length > 9 ? parts[9] : "";
                  String moves = parts.length > 10 ? parts[10] : "";
                  list.add(new GuildScreen.PokemonSlotPreview(b, s, species, lvl, shiny, name, form, depositor, ivs, evs, moves));
               } catch (Exception var17) {
               }
            }
         }

         return list;
      }
   }

   private static String capitalize(String str) {
      return str != null && !str.isEmpty() ? Character.toUpperCase(str.charAt(0)) + str.substring(1) : "";
   }

   private static GuildScreen.ParsedLogEntry parseLogEntry(String raw) {
      if (raw != null && !raw.isBlank()) {
         String time = "";
         String rest = raw;
         if (raw.startsWith("[")) {
            int endBracket = raw.indexOf(93);
            if (endBracket > 0) {
               String fullTime = raw.substring(1, endBracket);
               if (fullTime.contains(" - ")) {
                  String[] tparts = fullTime.split(" - ");
                  String timePart = tparts[1];
                  time = timePart.length() >= 5 ? timePart.substring(0, 5) : timePart;
               } else {
                  time = fullTime;
               }

               rest = raw.substring(endBracket + 1).trim();
            }
         }

         if (rest.contains("§DATA:") && rest.contains("§END")) {
            int dStart = rest.indexOf("§DATA:");
            int dEnd = rest.indexOf("§END", dStart) + 4;
            rest = (rest.substring(0, dStart) + rest.substring(dEnd)).trim();
         }

         int uuidIdx = rest.indexOf("; UUID=");
         if (uuidIdx >= 0) {
            rest = rest.substring(0, uuidIdx).trim();
         }

         int firstSpace = rest.indexOf(32);
         String author = firstSpace > 0 ? rest.substring(0, firstSpace) : "Gremio";
         String action = firstSpace > 0 ? rest.substring(firstSpace + 1) : rest;
         int color = -10764121;
         String lower = rest.toLowerCase(Locale.ROOT);
         if (lower.contains("depositó") || lower.contains("guardó") || lower.contains("creó") || lower.contains("unió")) {
            color = -13381254;
         } else if (lower.contains("retiró") || lower.contains("borró") || lower.contains("devolvió")) {
            color = -22016;
         } else if (lower.contains("ascendió") || lower.contains("transfirió")) {
            color = -10496;
         } else if (lower.contains("disolvió") || lower.contains("expulsado") || lower.contains("salió")) {
            color = -43691;
         } else if (lower.contains("ruta") || lower.contains("compartió")) {
            color = -13388315;
         }

         return new GuildScreen.ParsedLogEntry(time, author, action, color);
      } else {
         return new GuildScreen.ParsedLogEntry("", "", "", -6377814);
      }
   }

   private int renderLogEntryCard(class_332 graphics, String raw, int x, int y, int cardW, int mouseX, int mouseY) {
      GuildScreen.ParsedLogEntry entry = parseLogEntry(raw);
      List<class_5481> lines = this.field_22793.method_1728(class_2561.method_43470(entry.actionText()), cardW - 14);
      int cardH = Math.max(26, 15 + lines.size() * 10);
      boolean isHovered = mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + cardH;
      int bg = isHovered ? -14866132 : -15392991;
      int border = isHovered ? -12692394 : -14339274;
      graphics.method_25294(x, y, x + cardW, y + cardH, bg);
      this.drawOutline(graphics, x, y, cardW, cardH, border);
      graphics.method_25294(x, y, x + 3, y + cardH, entry.color());
      String timeStr = entry.time().isEmpty() ? "" : entry.time() + " • ";
      graphics.method_51433(this.field_22793, timeStr, x + 7, y + 3, -8482419, false);
      int timeW = this.field_22793.method_1727(timeStr);
      graphics.method_51433(this.field_22793, entry.author(), x + 7 + timeW, y + 3, entry.color(), false);

      for (int i = 0; i < lines.size(); i++) {
         graphics.method_51430(this.field_22793, lines.get(i), x + 7, y + 14 + i * 10, -1905690, false);
      }

      if (isHovered) {
         if (raw.contains("§DATA:") && raw.contains("§END")) {
            try {
               int dStart = raw.indexOf("§DATA:") + 6;
               int dEnd = raw.indexOf("§END", dStart);
               String pData = raw.substring(dStart, dEnd).trim();
               String[] p = pData.split("\\|", 10);
               if (p.length >= 8) {
                  String sp = p[0];
                  int lvl = Integer.parseInt(p[1]);
                  boolean sh = Boolean.parseBoolean(p[2]);
                  String fo = p[3];
                  String dn = p[4];
                  String dep = p[5];
                  String iv = p[6];
                  String ev = p[7];
                  String mv = p.length >= 9 ? p[8] : "";
                  if ((dn.equalsIgnoreCase("normal") || dn.equalsIgnoreCase("standard")) && !fo.equalsIgnoreCase("normal") && !fo.isBlank()) {
                     String temp = dn;
                     dn = fo;
                     fo = temp;
                  }

                  if (dn.isBlank() || dn.equalsIgnoreCase("normal") || dn.equalsIgnoreCase("standard")) {
                     dn = capitalize(sp);
                  }

                  this.hoveredLogPokemon = new GuildScreen.PokemonSlotPreview(0, 0, sp, lvl, sh, dn, fo, dep, iv, ev, mv);
               }
            } catch (Exception var30) {
            }
         } else if (!raw.toLowerCase(Locale.ROOT).contains("guardó") && !raw.toLowerCase(Locale.ROOT).contains("retiró")) {
            this.hoveredLogTooltip = entry.actionText();
         } else {
            for (GuildScreen.PokemonSlotPreview prev : this.parsePreviews(GuildClientState.data)) {
               if (raw.toLowerCase(Locale.ROOT).contains(prev.species().toLowerCase(Locale.ROOT))
                  || raw.toLowerCase(Locale.ROOT).contains(prev.displayName().toLowerCase(Locale.ROOT))) {
                  this.hoveredLogPokemon = prev;
                  break;
               }
            }

            if (this.hoveredLogPokemon == null) {
               this.hoveredLogTooltip = entry.actionText();
            }
         }
      }

      return cardH;
   }

   private List<String> getOnlinePlayerNames() {
      List<String> names = new ArrayList<>();
      if (this.field_22787 != null && this.field_22787.method_1562() != null) {
         for (class_640 p : this.field_22787.method_1562().method_2880()) {
            if (p != null && p.method_2966() != null && p.method_2966().getName() != null) {
               String n = p.method_2966().getName();
               if (!n.equalsIgnoreCase(this.field_22787.method_1548().method_1676())) {
                  names.add(n);
               }
            }
         }
      }

      Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
      return names;
   }

   private void updatePlayerSuggestions() {
      this.playerSuggestions.clear();
      String query = this.draftAction == null ? "" : this.draftAction.trim().toLowerCase(Locale.ROOT);
      if (!query.isEmpty()) {
         Set<String> existingMembers = new HashSet<>();

         for (GuildScreen.MemberInfo m : this.parseMembers(GuildClientState.data)) {
            existingMembers.add(m.name.toLowerCase(Locale.ROOT));
         }

         for (String name : this.getOnlinePlayerNames()) {
            if (name.toLowerCase(Locale.ROOT).contains(query) && !existingMembers.contains(name.toLowerCase(Locale.ROOT))) {
               this.playerSuggestions.add(name);
               if (this.playerSuggestions.size() >= 5) {
                  break;
               }
            }
         }
      }
   }

   private ClientPC getClientPC() {
      try {
         Map<UUID, ClientPC> stores = CobblemonClient.INSTANCE.getStorage().getPcStores();
         if (stores.isEmpty()) {
            send("SYNC_PRIVATE_PC", "", 0);
            return null;
         } else {
            if (this.field_22787 != null && this.field_22787.field_1724 != null) {
               ClientPC pc = stores.get(this.field_22787.field_1724.method_5667());
               if (pc != null) {
                  return pc;
               }
            }

            return stores.values().stream().findFirst().orElse(null);
         }
      } catch (Throwable var3) {
         return null;
      }
   }

   private ClientParty getClientParty() {
      try {
         if (this.field_22787 != null && this.field_22787.field_1724 != null) {
            ClientParty cp = (ClientParty)CobblemonClient.INSTANCE.getStorage().getPartyStores().get(this.field_22787.field_1724.method_5667());
            if (cp != null) {
               CobblemonClient.INSTANCE.getStorage().setParty(cp);
               return cp;
            }
         }

         return CobblemonClient.INSTANCE.getStorage().getParty();
      } catch (Throwable var2) {
         return null;
      }
   }

   private void playPcGrabSound() {
      try {
         if (this.field_22787 != null) {
            this.field_22787.method_1483().method_4873(class_1109.method_4758(CobblemonSounds.PC_GRAB, 1.0F));
         }
      } catch (Throwable var2) {
      }
   }

   private void playVaultSound() {
      this.playPcDropSound();
   }

   private void playPcDropSound() {
      try {
         if (this.field_22787 != null) {
            this.field_22787.method_1483().method_4873(class_1109.method_4758(CobblemonSounds.PC_DROP, 1.0F));
         }
      } catch (Throwable var2) {
      }
   }

   private void playPcSound() {
      this.playPcDropSound();
   }

   private void playDenySound() {
      try {
         if (this.field_22787 != null) {
            this.field_22787.method_1483().method_4873(class_1109.method_4758(class_3417.field_15008, 1.0F));
         }
      } catch (Throwable var2) {
      }
   }

   public void method_25419() {
      this.heldPokemon = null;
      this.heldVaultItem = null;
      super.method_25419();
   }

   GuildScreen() {
      this(0, false);
   }

   GuildScreen(boolean animateOpening) {
      this(0, animateOpening);
   }

   private GuildScreen(int tab, boolean animateOpening) {
      super(class_2561.method_43470("Gremio"));
      this.tab = tab;
      this.animateOpening = animateOpening;
      this.openedAtNanos = System.nanoTime();
   }

   protected void method_25426() {
      PlayerAvatarHelper.clearCache();
      float margin = 10.0F;
      float maxW = (float)this.field_22789 - margin * 2.0F;
      float maxH = (float)this.field_22790 - margin * 2.0F;
      this.uiScale = Math.min(1.0F, Math.min(maxW / 540.0F, maxH / 330.0F));
      this.panelWidth = 540;
      this.panelHeight = 330;
      this.renderX = ((float)this.field_22789 - 540.0F * this.uiScale) / 2.0F;
      this.renderY = ((float)this.field_22790 - 330.0F * this.uiScale) / 2.0F;
      this.panelX = 0;
      this.panelY = 0;
      GuildSyncPayload data = GuildClientState.data;
      this.guildMode = data.member();
      this.contentX = this.panelX + (data.member() ? 64 : 20);
      this.contentWidth = this.panelWidth - (data.member() ? 76 : 40);
      if (this.draftIcon.isBlank()) {
         this.draftIcon = data.icon() != null && !data.icon().isBlank() ? data.icon() : "pokeball";
      }

      if (data.member()) {
         for (int index = 0; index < TABS.length; index++) {
            int selectedTab = index;
            this.method_37063(new GuildScreen.IconTabButton(this.panelX + 12, this.panelY + 42 + index * 38, index, index == this.tab, () -> {
               this.rememberDrafts();
               this.field_22787.method_1507(new GuildScreen(selectedTab, false));
            }));
         }

         int headerBtnY = this.panelY + 8;
         this.method_37063(
            new GuildScreen.HeaderIconButton(this.panelX + this.panelWidth - 48, headerBtnY, 18, 18, "✕", -2534577, this::method_25419, "Cerrar menú")
         );
         this.method_37063(
            new GuildScreen.HeaderIconButton(
               this.panelX + this.panelWidth - 72,
               headerBtnY,
               18,
               18,
               "⎋",
               -2069387,
               () -> this.field_22787.method_1507(new GuildLeaveScreen(this, data)),
               "Salir del gremio"
            )
         );
      } else {
         this.method_37063(
            new GuildScreen.HeaderIconButton(this.panelX + this.panelWidth - 30, this.panelY + 8, 18, 18, "✕", -2534577, this::method_25419, "Cerrar")
         );
      }

      if (this.tab == 0) {
         this.initGeneral(data);
      }

      if (this.tab == 1) {
         this.initMembers(data);
      }

      if (this.tab == 2) {
         this.initPC(data);
      }

      if (this.tab == 3) {
         this.initVault(data);
      }

      if (this.tab == 4) {
         this.initWaypoints(data);
      }

      if (this.tab == 5) {
         this.initLog(data);
      }

      if (this.tab == 6) {
         this.initCustomize(data);
      }
   }

   private void initGeneral(GuildSyncPayload data) {
      if (!data.member()) {
         boolean isInvited = "INVITED".equals(data.role());
         int cardW = this.contentWidth - 20;
         int cardH = this.panelHeight - 56;
         int cx = this.contentX + 10;
         int cy = this.panelY + 44;
         if (isInvited) {
            int footerY = cy + cardH - 34;
            int btnW = 150;
            int gap = 20;
            int startBtnX = cx + (cardW - (btnW * 2 + gap)) / 2;
            this.addAction("✔ Aceptar invitación", startBtnX, footerY, btnW, 24, () -> send("ACCEPT", "", 0), true);
            this.addAction("✖ Rechazar", startBtnX + btnW + gap, footerY, btnW, 24, () -> send("DECLINE", "", 0), false);
         } else {
            int formY = cy + 32;
            this.nameInput = this.addInput(cx + 30, formY + 16, cardW - 60, 20, "Ej: Los Invencibles", 24, this.draftName);
            this.nameInput.method_1863(val -> this.draftName = val);
            int barW = cardW - 100;
            int specY = formY + 54;
            this.method_37063(new GuildScreen.HueBarWidget(cx + 30, specY, barW, 18, hex -> this.draftColor = hex));
            int iconSize = 22;
            int iconGap = 6;
            int totalIconsW = GUILD_ICONS.length * iconSize + (GUILD_ICONS.length - 1) * iconGap;
            int iconsStartX = cx + 30;
            int iconsY = formY + 92;

            for (int i = 0; i < GUILD_ICONS.length; i++) {
               String iconKey = GUILD_ICONS[i];
               int ix = iconsStartX + i * (iconSize + iconGap);
               this.method_37063(new GuildScreen.IconTileButton(ix, iconsY, iconSize, iconKey, () -> this.draftIcon = iconKey));
            }

            int footerY = cy + cardH - 34;
            this.addAction("\ud83c\udfdb Fundar Gremio", cx + (cardW - 180) / 2, footerY, 180, 24, this::createGuild, true);
         }
      } else {
         int cardY = this.panelY + 46;
         int cardHeight = 56;
         int bankY = cardY + cardHeight + 8;
         int inputW = Math.min(85, Math.max(60, (this.contentWidth - 220) / 2));
         this.amountInput = this.addInput(this.contentX + 10, bankY + 18, inputW, 20, "$ Monto", 18, this.draftAmount);
         int pillX = this.contentX + 10 + inputW + 6;
         int pillW = 27;
         this.method_37063(new GuildScreen.QuickPillButton(pillX, bankY + 21, pillW, 14, "+1k", () -> this.addDraftAmount(1000L)));
         this.method_37063(new GuildScreen.QuickPillButton(pillX + 31, bankY + 21, pillW + 3, 14, "+10k", () -> this.addDraftAmount(10000L)));
         this.method_37063(new GuildScreen.QuickPillButton(pillX + 66, bankY + 21, pillW + 3, 14, "+50k", () -> this.addDraftAmount(50000L)));
         this.method_37063(new GuildScreen.QuickPillButton(pillX + 101, bankY + 21, pillW + 2, 14, "Todo", () -> this.setDraftAmount(data.walletBalance())));
         int btnW = 58;
         int depX = this.contentX + this.contentWidth - btnW * 2 - 12;
         int withX = this.contentX + this.contentWidth - btnW - 6;
         this.addAction("Depositar", depX, bankY + 17, btnW, 22, () -> this.sendAmount("DEPOSIT"), true);
         this.addAction("Retirar", withX, bankY + 17, btnW, 22, () -> this.sendAmount("WITHDRAW"), false);
         int motdY = bankY + 46 + 8;
         int motdHeight = this.panelY + this.panelHeight - motdY - 10;
         int motdW = Math.max(130, (this.contentWidth - 8) * 36 / 100);
         int chatX = this.contentX + motdW + 8;
         int chatW = this.contentWidth - motdW - 8;
         boolean canEditMotd = "LEADER".equals(data.role()) || "OFFICER".equals(data.role());
         if (canEditMotd) {
            if (this.editingMotd) {
               this.motdEditInput = this.addInput(
                  this.contentX + 8,
                  motdY + 22,
                  motdW - 16,
                  20,
                  "Anuncio...",
                  180,
                  this.draftMotd.isBlank() ? (data.motd().isBlank() ? "" : data.motd()) : this.draftMotd
               );
               this.method_37063(new GuildScreen.QuickPillButton(this.contentX + 8, motdY + 46, 50, 16, "Guardar", () -> {
                  if (this.motdEditInput != null) {
                     this.draftMotd = this.motdEditInput.method_1882();
                     send("MOTD", this.draftMotd, 0);
                  }

                  this.editingMotd = false;
                  this.method_41843();
               }));
               this.method_37063(new GuildScreen.QuickPillButton(this.contentX + 62, motdY + 46, 50, 16, "Cancelar", () -> {
                  this.editingMotd = false;
                  this.method_41843();
               }));
            } else {
               this.method_37063(new GuildScreen.QuickPillButton(this.contentX + motdW - 48, motdY + 5, 42, 14, "Editar", () -> {
                  this.editingMotd = true;
                  this.draftMotd = data.motd();
                  this.method_41843();
               }));
            }
         }

         int inputH = 18;
         int inputY = motdY + motdHeight - inputH - 5;
         int sendBtnW = 42;
         this.chatInput = this.addInput(chatX + 6, inputY, chatW - sendBtnW - 14, inputH, "Escribe un mensaje...", 200, this.draftChat);
         this.chatInput.method_1863(val -> this.draftChat = val);
         this.method_37063(new GuildScreen.QuickPillButton(chatX + chatW - sendBtnW - 6, inputY, sendBtnW, inputH, "Enviar", this::sendChatMessage));
      }
   }

   private void sendChatMessage() {
      if (this.chatInput != null) {
         String text = this.chatInput.method_1882().trim();
         if (!text.isEmpty()) {
            send("CHAT", text, 0);
            this.chatInput.method_1852("");
            this.draftChat = "";
            this.chatScrollOffset = 0;
            this.method_25395(this.chatInput);
         }
      }
   }

   private void addDraftAmount(long add) {
      try {
         BigInteger current = this.draftAmount.isBlank() ? BigInteger.ZERO : new BigInteger(this.draftAmount);
         current = current.add(BigInteger.valueOf(add));
         this.draftAmount = current.toString();
      } catch (Exception var4) {
         this.draftAmount = String.valueOf(add);
      }

      if (this.amountInput != null) {
         this.amountInput.method_1852(this.draftAmount);
      }
   }

   private void setDraftAmount(BigInteger total) {
      this.draftAmount = total.toString();
      if (this.amountInput != null) {
         this.amountInput.method_1852(this.draftAmount);
      }
   }

   private void initMembers(GuildSyncPayload data) {
      int topBarY = this.panelY + 48;
      this.searchMemberInput = this.addInput(this.contentX, topBarY, 150, 22, "Buscar miembro...", 32, this.memberFilter);
      this.searchMemberInput.method_1863(text -> {
         this.memberFilter = text.trim().toLowerCase();
         this.memberScrollOffset = 0;
         this.method_41843();
      });
      boolean canInvite = "LEADER".equals(data.role()) || "OFFICER".equals(data.role());
      if (canInvite) {
         int invX = this.contentX + 160;
         this.actionInput = this.addInput(invX, topBarY, Math.min(170, this.contentWidth - 238), 22, "Invitar jugador...", 32, this.draftAction);
         this.actionInput.method_1863(text -> {
            this.draftAction = text;
            this.updatePlayerSuggestions();
         });
         this.addAction("Invitar", invX + Math.min(170, this.contentWidth - 238) + 6, topBarY, 64, 22, () -> send("INVITE", value(this.actionInput), 0), true);
      }

      List<GuildScreen.MemberInfo> filtered = this.getFilteredMembers(data);
      int rowHeight = 28;
      int listTop = this.panelY + 76;
      int visibleCount = Math.max(1, (this.panelHeight - 110) / rowHeight);

      for (int i = 0; i < visibleCount && this.memberScrollOffset + i < filtered.size(); i++) {
         GuildScreen.MemberInfo m = filtered.get(this.memberScrollOffset + i);
         int rowY = listTop + i * rowHeight;
         int rightX = this.contentX + this.contentWidth - 8;
         boolean canManage = false;
         if ("LEADER".equals(data.role()) && !m.name.equalsIgnoreCase(this.field_22787.method_1548().method_1676())) {
            canManage = true;
         } else if ("OFFICER".equals(data.role()) && "MEMBER".equals(m.role) && !m.name.equalsIgnoreCase(this.field_22787.method_1548().method_1676())) {
            canManage = true;
         }

         if (canManage) {
            String targetName = m.name;
            rightX -= 22;
            this.method_37063(
               new GuildScreen.RowActionButton(
                  rightX, rowY + 5, 20, 18, "✕", -2534577, () -> send("KICK", targetName, 0), "Expulsar del gremio\n§7Elimina a " + targetName + " del gremio."
               )
            );
            if ("LEADER".equals(data.role())) {
               if ("MEMBER".equals(m.role)) {
                  rightX -= 24;
                  this.method_37063(
                     new GuildScreen.RowActionButton(
                        rightX,
                        rowY + 5,
                        20,
                        18,
                        "▲",
                        -10764066,
                        () -> send("PROMOTE", targetName, 0),
                        "Subir rango a Oficial\n§7Otorga permisos de invitación y gestión."
                     )
                  );
               } else if ("OFFICER".equals(m.role)) {
                  rightX -= 24;
                  this.method_37063(
                     new GuildScreen.RowActionButton(
                        rightX,
                        rowY + 5,
                        20,
                        18,
                        "▼",
                        -1004210,
                        () -> send("DEMOTE", targetName, 0),
                        "Bajar rango a Miembro\n§7Reduce sus permisos a miembro regular."
                     )
                  );
               }
            }
         }
      }
   }

   private void initPC(GuildSyncPayload data) {
      send("SYNC_PRIVATE_PC", "", 0);
      int slotSize = 32;
      int cols = 6;
      int gap = 14;
      int totalW = cols * slotSize + gap + slotSize + gap + cols * slotSize;
      int startX = this.contentX + Math.max(6, (this.contentWidth - totalW) / 2);
      int gridX = startX;
      int partyX = startX + cols * slotSize + gap;
      int privX = partyX + slotSize + gap;
      int boxTabY = this.panelY + 58;
      int totalBoxes = 4;
      int tabW = 45;

      for (int i = 0; i < totalBoxes; i++) {
         int boxIdx = i;
         String label = (this.selectedPcBox == boxIdx ? "• Caja " : "Caja ") + (i + 1);
         this.method_37063(new GuildScreen.QuickPillButton(gridX + i * 49, boxTabY, tabW, 18, label, () -> {
            this.selectedPcBox = boxIdx;
            this.method_41843();
         }));
      }

      this.method_37063(new GuildScreen.QuickPillButton(privX, boxTabY, 20, 18, "◀", () -> {
         ClientPC pc = this.getClientPC();
         int total = pc != null && pc.getBoxes().size() > 0 ? pc.getBoxes().size() : 1;
         privatePcBox = (privatePcBox - 1 + total) % total;
         this.method_41843();
      }));
      this.method_37063(new GuildScreen.QuickPillButton(privX + 6 * slotSize - 20, boxTabY, 20, 18, "▶", () -> {
         ClientPC pc = this.getClientPC();
         int total = pc != null && pc.getBoxes().size() > 0 ? pc.getBoxes().size() : 1;
         privatePcBox = (privatePcBox + 1) % total;
         this.method_41843();
      }));
   }

   private void initWaypoints(GuildSyncPayload data) {
      this.localWaypoints = XaeroWaypointBridge.localWaypoints();
      int filterY = this.panelY + 46;
      int chipW = 54;

      for (int f = 0; f < DIM_FILTERS.length; f++) {
         int filterIdx = f;
         this.method_37063(new GuildScreen.QuickPillButton(this.contentX + f * (chipW + 5), filterY, chipW, 18, DIM_FILTERS[f], () -> {
            this.waypointDimFilter = filterIdx;
            this.localWaypointScroll = 0;
            this.sharedWaypointScroll = 0;
            this.method_41843();
         }));
      }

      int shareLocW = 144;
      int shareLocX = this.contentX + this.contentWidth - shareLocW;
      this.method_37063(
         new GuildScreen.QuickPillButton(shareLocX, filterY, shareLocW, 18, "\ud83d\udccd Compartir mi posición", () -> send("SHARE_POS", "", 0))
      );
      int halfWidth = (this.contentWidth - 12) / 2;
      int listTop = this.panelY + 86;
      int rowHeight = 27;
      int visible = Math.max(1, (this.panelHeight - 146) / rowHeight);
      List<XaeroWaypointBridge.LocalWaypoint> filteredLocal = this.filterLocalWaypoints(this.localWaypoints, this.waypointDimFilter);

      for (int row = 0; row < visible && this.localWaypointScroll + row < filteredLocal.size(); row++) {
         int index = this.localWaypointScroll + row;
         XaeroWaypointBridge.LocalWaypoint waypoint = filteredLocal.get(index);
         this.method_37063(
            new GuildScreen.WaypointSelectButton(
               this.contentX,
               listTop + row * rowHeight,
               halfWidth,
               rowHeight - 2,
               waypoint.name(),
               waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z(),
               this.selectedLocalWaypoint == row,
               () -> {
                  this.selectedLocalWaypoint = index;
                  this.selectedGuildWaypoint = "";
                  this.method_41843();
               }
            )
         );
      }

      List<String> rawShared = new ArrayList<>(
         data.waypoints()
            .stream()
            .filter(encodedx -> encodedx.split("\\|", 9).length == 9)
            .filter(encodedx -> encodedx.split("\\|", 9)[1].equals("true"))
            .toList()
      );
      List<String> filteredShared = this.filterSharedWaypoints(rawShared, this.waypointDimFilter);
      this.localWaypointScroll = Math.min(this.localWaypointScroll, Math.max(0, filteredLocal.size() - visible));
      this.sharedWaypointScroll = Math.min(this.sharedWaypointScroll, Math.max(0, filteredShared.size() - visible));

      for (int row = 0; row < visible && this.sharedWaypointScroll + row < filteredShared.size(); row++) {
         String encoded = filteredShared.get(this.sharedWaypointScroll + row);
         String[] parts = encoded.split("\\|", 9);
         String name = decode(parts[2]);
         String id = parts[0];
         String uploader = decode(parts[7]);
         String uploadTime = uploadTime(parts[8]);
         String detail = uploader + " / " + uploadTime;
         this.method_37063(
            new GuildScreen.WaypointSelectButton(
               this.contentX + halfWidth + 12,
               listTop + row * rowHeight,
               halfWidth,
               rowHeight - 2,
               name,
               detail,
               id.equals(this.selectedGuildWaypoint),
               () -> {
                  this.selectedGuildWaypoint = id;
                  this.selectedLocalWaypoint = -1;
                  this.method_41843();
               }
            )
         );
      }

      int footerY = this.panelY + this.panelHeight - 38;
      if (this.selectedLocalWaypoint >= 0 && this.selectedLocalWaypoint < filteredLocal.size()) {
         this.addAction("Compartir waypoint en gremio", this.contentX, footerY, Math.min(220, this.contentWidth), 26, this::uploadSelectedWaypoint, true);
      } else if (!this.selectedGuildWaypoint.isBlank()) {
         this.addAction("Copiar a mis waypoints locales", this.contentX, footerY, Math.min(210, this.contentWidth), 26, this::copySelectedGuildWaypoint, true);
      }
   }

   private List<XaeroWaypointBridge.LocalWaypoint> filterLocalWaypoints(List<XaeroWaypointBridge.LocalWaypoint> list, int filter) {
      return filter == 0 ? list : list.stream().filter(w -> matchesDimFilter(w.dimension(), filter)).toList();
   }

   private List<String> filterSharedWaypoints(List<String> list, int filter) {
      return filter == 0 ? list : list.stream().filter(raw -> {
         String[] parts = raw.split("\\|", 9);
         return parts.length > 3 && matchesDimFilter(parts[3], filter);
      }).toList();
   }

   private static boolean matchesDimFilter(String dim, int filter) {
      if (filter == 0) {
         return true;
      } else if (dim == null) {
         return false;
      } else {
         String lower = dim.toLowerCase();
         if (filter != 1) {
            if (filter == 2) {
               return lower.contains("nether");
            } else {
               return filter == 3 ? lower.contains("end") : true;
            }
         } else {
            return lower.contains("overworld") || !lower.contains("nether") && !lower.contains("end");
         }
      }
   }

   private void initLog(GuildSyncPayload data) {
      int filterY = this.panelY + 46;
      int btnW1 = 70;
      int btnW2 = 96;
      int btnW3 = 88;
      this.method_37063(new GuildScreen.QuickPillButton(this.contentX, filterY, btnW1, 18, (this.logFilterTab == 0 ? "• " : "") + "GENERAL", () -> {
         this.logFilterTab = 0;
         this.logScrollOffset = 0;
         this.method_41843();
      }));
      this.method_37063(
         new GuildScreen.QuickPillButton(this.contentX + btnW1 + 4, filterY, btnW2, 18, (this.logFilterTab == 1 ? "• " : "") + "PC / POKÉMON", () -> {
            this.logFilterTab = 1;
            this.logScrollOffset = 0;
            this.method_41843();
         })
      );
      this.method_37063(
         new GuildScreen.QuickPillButton(
            this.contentX + btnW1 + 4 + btnW2 + 4, filterY, btnW3, 18, (this.logFilterTab == 2 ? "• " : "") + "INVENTARIO", () -> {
               this.logFilterTab = 2;
               this.logScrollOffset = 0;
               this.method_41843();
            }
         )
      );
      this.addAction("Actualizar registro", this.panelX + this.panelWidth - 110, this.panelY + this.panelHeight - 34, 96, 22, () -> send("SYNC", "", 0), false);
   }

   private void initCustomize(GuildSyncPayload data) {
      boolean isLeader = "LEADER".equals(data.role());
      if (isLeader) {
         if (this.draftName.isBlank()) {
            this.draftName = data.name();
            this.draftColor = data.color().replace("#", "");
            this.draftIcon = data.icon() != null && !data.icon().isBlank() ? data.icon() : "pokeball";
         }

         int card1Y = this.panelY + 44;
         int card1H = 46;
         int nameW = Math.max(120, this.contentWidth - 140);
         this.nameInput = this.addInput(this.contentX + 10, card1Y + 18, nameW, 20, "Nombre del gremio", 24, this.draftName);
         this.nameInput.method_1863(val -> this.draftName = val);
         int card2Y = card1Y + card1H + 6;
         int card2H = 54;
         int barW = this.contentWidth - 48;
         this.method_37063(new GuildScreen.HueBarWidget(this.contentX + 10, card2Y + 22, barW, 20, hex -> this.draftColor = hex));
         int card3Y = card2Y + card2H + 6;
         int iconSize = 26;
         int iconGap = 6;

         for (int i = 0; i < GUILD_ICONS.length; i++) {
            String iconKey = GUILD_ICONS[i];
            int ix = this.contentX + 10 + i * (iconSize + iconGap);
            int iy = card3Y + 18;
            this.method_37063(new GuildScreen.IconTileButton(ix, iy, iconSize, iconKey, () -> this.draftIcon = iconKey));
         }

         int footerY = this.panelY + this.panelHeight - 32;
         this.addAction("Guardar cambios", this.contentX, footerY, 120, 22, this::saveGuildDetails, true);
         this.addAction("Disolver gremio", this.contentX + this.contentWidth - 110, footerY, 110, 22, () -> send("DISBAND", "", 0), false);
      }
   }

   private void sendChatCmd(String cmd) {
      if (this.field_22787 != null && this.field_22787.field_1724 != null && this.field_22787.method_1562() != null) {
         this.field_22787.field_1724.field_3944.method_45730(cmd);
      }
   }

   public void method_25394(class_332 graphics, int mouseX, int mouseY, float delta) {
      if (this.closeAfterCreate && GuildClientState.data.member()) {
         this.field_22787.method_1507(null);
      } else {
         if (GuildClientState.data.member() != this.guildMode) {
            this.guildMode = GuildClientState.data.member();
            this.method_41843();
         }

         graphics.method_25294(0, 0, this.field_22789, this.field_22790, -1257631726);
         int localMouseX = Math.round(((float)mouseX - this.renderX) / this.uiScale);
         int localMouseY = Math.round(((float)mouseY - this.renderY) / this.uiScale);
         graphics.method_51448().method_22903();
         graphics.method_51448().method_46416(this.renderX, this.renderY, 0.0F);
         graphics.method_51448().method_22905(this.uiScale, this.uiScale, 1.0F);
         if (this.animateOpening) {
            float progress = this.openingProgress();
            float eased = 1.0F - (float)Math.pow((double)(1.0F - progress), 3.0);
            float animScale = 0.08F + eased * 0.92F;
            float centerX = (float)this.panelWidth / 2.0F;
            float centerY = (float)this.panelHeight / 2.0F;
            graphics.method_51448().method_46416(centerX, centerY, 0.0F);
            graphics.method_51448().method_22905(animScale, animScale, 1.0F);
            graphics.method_51448().method_46416(-centerX, -centerY, 0.0F);
         }

         graphics.method_25294(this.panelX, this.panelY, this.panelX + this.panelWidth, this.panelY + this.panelHeight, -267249895);
         this.drawOutline(graphics, this.panelX, this.panelY, this.panelWidth, this.panelHeight, -14010824);
         graphics.method_25294(this.panelX + 1, this.panelY + 1, this.panelX + this.panelWidth - 1, this.panelY + 36, -15130076);
         graphics.method_25294(this.panelX + 1, this.panelY + 36, this.panelX + this.panelWidth - 1, this.panelY + 37, accentColor());
         String guildTitle = GuildClientState.data.member() ? GuildClientState.data.name() : "Sin gremio";
         String currentIcon = GuildClientState.data.icon() != null && !GuildClientState.data.icon().isBlank() ? GuildClientState.data.icon() : "pokeball";
         this.drawGuildIcon(graphics, currentIcon, this.panelX + 12, this.panelY + 9, 18, accentColor());
         graphics.method_51433(this.field_22793, this.fit(guildTitle, 220), this.panelX + 36, this.panelY + 14, accentColor(), false);
         if (GuildClientState.data.member()) {
            graphics.method_51433(
               this.field_22793,
               "• " + TABS[this.tab].toUpperCase(),
               this.panelX + 42 + this.field_22793.method_1727(this.fit(guildTitle, 220)),
               this.panelY + 14,
               -6377814,
               false
            );
            graphics.method_25294(this.panelX + 1, this.panelY + 37, this.contentX - 6, this.panelY + this.panelHeight - 1, -15393505);
            graphics.method_25294(this.contentX - 6, this.panelY + 37, this.contentX - 5, this.panelY + this.panelHeight - 1, -14537682);
         }

         this.hoveredVaultTooltip = class_1799.field_8037;
         this.hoveredLogTooltip = "";
         this.hoveredLogPokemon = null;
         this.hoveredMemberTooltip = null;
         if (this.tab == 0) {
            this.renderGeneral(graphics, GuildClientState.data, localMouseX, localMouseY);
         }

         if (this.tab == 1) {
            this.renderMembers(graphics, GuildClientState.data, localMouseX, localMouseY);
         }

         if (this.tab == 2) {
            this.renderPC(graphics, GuildClientState.data, localMouseX, localMouseY);
         }

         if (this.tab == 3) {
            this.renderVault(graphics, GuildClientState.data, localMouseX, localMouseY);
         }

         if (this.tab == 4) {
            this.renderWaypoints(graphics, GuildClientState.data);
         }

         if (this.tab == 5) {
            this.renderLog(graphics, GuildClientState.data, localMouseX, localMouseY);
         }

         if (this.tab == 6) {
            this.renderCustomize(graphics, GuildClientState.data);
         }

         super.method_25394(graphics, localMouseX, localMouseY, delta);
         if (this.tab == 2) {
            this.renderPCTooltip(graphics, GuildClientState.data, localMouseX, localMouseY);
         }

         if (this.tab == 3) {
            this.renderVaultTooltip(graphics, GuildClientState.data, localMouseX, localMouseY);
         }

         if (this.tab == 5) {
            if (this.hoveredLogPokemon != null) {
               this.renderPreviewPokemonTooltip(graphics, this.hoveredLogPokemon, localMouseX, localMouseY);
            } else if (!this.hoveredLogTooltip.isEmpty()) {
               graphics.method_51438(this.field_22793, class_2561.method_43470(this.hoveredLogTooltip), localMouseX, localMouseY);
            }
         }

         if (this.hoveredMemberTooltip != null && !this.hoveredMemberTooltip.isEmpty()) {
            graphics.method_51434(this.field_22793, this.hoveredMemberTooltip, localMouseX, localMouseY);
         }

         if (this.tab == 3 && this.heldVaultItem != null && !this.heldVaultItem.stack.method_7960()) {
            graphics.method_51427(this.heldVaultItem.stack, localMouseX - 8, localMouseY - 8);
            graphics.method_51431(this.field_22793, this.heldVaultItem.stack, localMouseX - 8, localMouseY - 8);
         }

         if (this.tab == 2 && this.heldPokemon != null) {
            class_1799 heldStack = getOrCreatePokemonStack(this.heldPokemon.species(), this.heldPokemon.shiny(), this.heldPokemon.form());
            float scale = 1.65F;
            int offset = (int)Math.round((double)(16.0F * scale) / 2.0);
            if (!heldStack.method_7960()) {
               graphics.method_51448().method_22903();
               graphics.method_51448().method_46416((float)(localMouseX - offset), (float)(localMouseY - offset), 0.0F);
               graphics.method_51448().method_22905(scale, scale, 1.0F);
               graphics.method_51427(heldStack, 0, 0);
               graphics.method_51448().method_22909();
            } else {
               graphics.method_25294(localMouseX - offset, localMouseY - offset, localMouseX + offset, localMouseY + offset, -13807798);
               String initial = this.heldPokemon.species().isEmpty() ? "?" : this.heldPokemon.species().substring(0, 1).toUpperCase(Locale.ROOT);
               graphics.method_25300(this.field_22793, initial, localMouseX, localMouseY - 4, -10764121);
            }

            graphics.method_51448().method_22903();
            graphics.method_51448().method_46416(0.0F, 0.0F, 300.0F);
            if (this.heldPokemon.shiny()) {
               graphics.method_51433(this.field_22793, "★", localMouseX + offset - 8, localMouseY - offset, -11940, true);
            }

            String lvl = String.valueOf(this.heldPokemon.level());
            graphics.method_51433(this.field_22793, lvl, localMouseX + offset - this.field_22793.method_1727(lvl), localMouseY + offset - 8, -3352620, true);
            if (this.heldPokemon.restricted()) {
               int cols = 6;
               int rows = 5;
               int slotSize = 32;
               int gap = 14;
               int totalW = cols * slotSize + gap + slotSize + gap + cols * slotSize;
               int gridX = this.contentX + Math.max(6, (this.contentWidth - totalW) / 2);
               int gridY = this.panelY + 82;
               if (localMouseX >= gridX && localMouseX < gridX + cols * slotSize && localMouseY >= gridY && localMouseY < gridY + rows * slotSize) {
                  graphics.method_51433(this.field_22793, "⛔ No permitido en Gremio", localMouseX + 12, localMouseY - 6, -48060, true);
               }
            }

            graphics.method_51448().method_22909();
         }

         if (this.warningBannerTicks > 0 && !this.warningBanner.isBlank()) {
            this.warningBannerTicks--;
            int bannerW = this.field_22793.method_1727(this.warningBanner) + 24;
            int bannerH = 18;
            int bannerX = this.panelX + (this.panelWidth - bannerW) / 2;
            int bannerY = this.panelY + this.panelHeight - 26;
            graphics.method_25294(bannerX, bannerY, bannerX + bannerW, bannerY + bannerH, -292880384);
            this.drawOutline(graphics, bannerX, bannerY, bannerW, bannerH, -52429);
            graphics.method_25300(this.field_22793, this.warningBanner, bannerX + bannerW / 2, bannerY + 5, -1);
         }

         graphics.method_51448().method_22909();
      }
   }

   private void initVault(GuildSyncPayload data) {
      this.hoveredVaultTooltip = class_1799.field_8037;
      this.hoveredLogTooltip = "";
   }

   private void renderVault(class_332 graphics, GuildSyncPayload data, int mouseX, int mouseY) {
      if (data.member() && this.field_22787 != null && this.field_22787.field_1724 != null) {
         class_746 player = this.field_22787.field_1724;
         this.hoveredVaultTooltip = class_1799.field_8037;
         int cardW = 444;
         int cx = this.contentX + 10;
         int slotSize = 30;
         int gridX = cx + (cardW - 9 * slotSize) / 2;
         int card1Y = this.panelY + 44;
         int card1H = 114;
         graphics.method_25294(cx, card1Y, cx + cardW, card1Y + card1H, -15590884);
         this.drawOutline(graphics, cx, card1Y, cardW, card1H, -14141381);
         graphics.method_51433(this.field_22793, "COFRE DEL GREMIO", cx + 10, card1Y + 7, accentColor(), false);
         int vaultGridY = card1Y + 20;
         List<class_1799> vaultItems = data.vaultItems();

         for (int i = 0; i < 27; i++) {
            int c = i % 9;
            int r = i / 9;
            int sx = gridX + c * slotSize;
            int sy = vaultGridY + r * slotSize;
            graphics.method_25294(sx, sy, sx + slotSize - 2, sy + slotSize - 2, -15195355);
            this.drawOutline(graphics, sx, sy, slotSize - 2, slotSize - 2, -14009795);
            boolean hovered = mouseX >= sx && mouseX < sx + slotSize - 2 && mouseY >= sy && mouseY < sy + slotSize - 2;
            if (hovered) {
               graphics.method_25294(sx, sy, sx + slotSize - 2, sy + slotSize - 2, 1157627903);
            }

            class_1799 stack = vaultItems != null && i < vaultItems.size() ? vaultItems.get(i) : class_1799.field_8037;
            class_1799 renderStack = stack;
            if (this.heldVaultItem != null && "VAULT".equals(this.heldVaultItem.container) && this.heldVaultItem.slot == i) {
               if (this.heldVaultItem.remainingInSource > 0 && stack != null && !stack.method_7960()) {
                  renderStack = stack.method_46651(this.heldVaultItem.remainingInSource);
               } else {
                  renderStack = class_1799.field_8037;
               }
            }

            if (renderStack != null && !renderStack.method_7960()) {
               graphics.method_51448().method_22903();
               graphics.method_51448().method_46416((float)(sx + 3), (float)(sy + 3), 0.0F);
               graphics.method_51448().method_22905(1.5F, 1.5F, 1.0F);
               graphics.method_51427(renderStack, 0, 0);
               graphics.method_51448().method_22909();
               graphics.method_51431(this.field_22793, renderStack, sx + 7, sy + 7);
               if (hovered && this.heldVaultItem == null) {
                  this.hoveredVaultTooltip = renderStack;
               }
            }
         }

         int card2Y = this.panelY + 162;
         int card2H = 156;
         graphics.method_25294(cx, card2Y, cx + cardW, card2Y + card2H, -15590884);
         this.drawOutline(graphics, cx, card2Y, cardW, card2H, -14141381);
         graphics.method_51433(this.field_22793, "TU INVENTARIO", cx + 10, card2Y + 7, -10764121, false);
         int playerMainGridY = card2Y + 20;

         for (int rx = 0; rx < 3; rx++) {
            for (int cxx = 0; cxx < 9; cxx++) {
               int pSlot = 9 + rx * 9 + cxx;
               int sxx = gridX + cxx * slotSize;
               int syx = playerMainGridY + rx * slotSize;
               graphics.method_25294(sxx, syx, sxx + slotSize - 2, syx + slotSize - 2, -15195355);
               this.drawOutline(graphics, sxx, syx, slotSize - 2, slotSize - 2, -14009795);
               boolean hoveredx = mouseX >= sxx && mouseX < sxx + slotSize - 2 && mouseY >= syx && mouseY < syx + slotSize - 2;
               if (hoveredx) {
                  graphics.method_25294(sxx, syx, sxx + slotSize - 2, syx + slotSize - 2, 1157627903);
               }

               class_1799 stackx = player.method_31548().method_5438(pSlot);
               class_1799 renderStackx = stackx;
               if (this.heldVaultItem != null && "PLAYER".equals(this.heldVaultItem.container) && this.heldVaultItem.slot == pSlot) {
                  if (this.heldVaultItem.remainingInSource > 0 && stackx != null && !stackx.method_7960()) {
                     renderStackx = stackx.method_46651(this.heldVaultItem.remainingInSource);
                  } else {
                     renderStackx = class_1799.field_8037;
                  }
               }

               if (renderStackx != null && !renderStackx.method_7960()) {
                  graphics.method_51448().method_22903();
                  graphics.method_51448().method_46416((float)(sxx + 3), (float)(syx + 3), 0.0F);
                  graphics.method_51448().method_22905(1.5F, 1.5F, 1.0F);
                  graphics.method_51427(renderStackx, 0, 0);
                  graphics.method_51448().method_22909();
                  graphics.method_51431(this.field_22793, renderStackx, sxx + 7, syx + 7);
                  if (hoveredx && this.heldVaultItem == null) {
                     this.hoveredVaultTooltip = renderStackx;
                  }
               }
            }
         }

         int hotbarGridY = playerMainGridY + 3 * slotSize + 4;

         for (int cxx = 0; cxx < 9; cxx++) {
            int sxxx = gridX + cxx * slotSize;
            graphics.method_25294(sxxx, hotbarGridY, sxxx + slotSize - 2, hotbarGridY + slotSize - 2, -14997462);
            this.drawOutline(graphics, sxxx, hotbarGridY, slotSize - 2, slotSize - 2, -13284530);
            boolean hoveredxx = mouseX >= sxxx && mouseX < sxxx + slotSize - 2 && mouseY >= hotbarGridY && mouseY < hotbarGridY + slotSize - 2;
            if (hoveredxx) {
               graphics.method_25294(sxxx, hotbarGridY, sxxx + slotSize - 2, hotbarGridY + slotSize - 2, 1157627903);
            }

            class_1799 stackxx = player.method_31548().method_5438(cxx);
            class_1799 renderStackxx = stackxx;
            if (this.heldVaultItem != null && "PLAYER".equals(this.heldVaultItem.container) && this.heldVaultItem.slot == cxx) {
               if (this.heldVaultItem.remainingInSource > 0 && stackxx != null && !stackxx.method_7960()) {
                  renderStackxx = stackxx.method_46651(this.heldVaultItem.remainingInSource);
               } else {
                  renderStackxx = class_1799.field_8037;
               }
            }

            if (renderStackxx != null && !renderStackxx.method_7960()) {
               graphics.method_51448().method_22903();
               graphics.method_51448().method_46416((float)(sxxx + 3), (float)(hotbarGridY + 3), 0.0F);
               graphics.method_51448().method_22905(1.5F, 1.5F, 1.0F);
               graphics.method_51427(renderStackxx, 0, 0);
               graphics.method_51448().method_22909();
               graphics.method_51431(this.field_22793, renderStackxx, sxxx + 7, hotbarGridY + 7);
               if (hoveredxx && this.heldVaultItem == null) {
                  this.hoveredVaultTooltip = renderStackxx;
               }
            }
         }
      }
   }

   private void renderVaultTooltip(class_332 graphics, GuildSyncPayload data, int mouseX, int mouseY) {
      if (this.hoveredVaultTooltip != null && !this.hoveredVaultTooltip.method_7960()) {
         graphics.method_51446(this.field_22793, this.hoveredVaultTooltip, mouseX, mouseY);
      }
   }

   private boolean handleVaultMouseClicked(double mouseX, double mouseY, int button) {
      if (this.field_22787 != null && this.field_22787.field_1724 != null) {
         class_746 player = this.field_22787.field_1724;
         int cardW = 444;
         int cx = this.contentX + 10;
         int slotSize = 30;
         int gridX = cx + (cardW - 9 * slotSize) / 2;
         int vaultGridY = this.panelY + 44 + 20;
         int playerMainGridY = this.panelY + 162 + 20;
         int hotbarGridY = playerMainGridY + 3 * slotSize + 4;
         boolean shift = method_25442();
         List<class_1799> vaultItems = GuildClientState.data.vaultItems();

         for (int i = 0; i < 27; i++) {
            int c = i % 9;
            int r = i / 9;
            int sx = gridX + c * slotSize;
            int sy = vaultGridY + r * slotSize;
            if (mouseX >= (double)sx && mouseX < (double)(sx + slotSize - 2) && mouseY >= (double)sy && mouseY < (double)(sy + slotSize - 2)) {
               class_1799 stack = vaultItems != null && i < vaultItems.size() ? vaultItems.get(i) : class_1799.field_8037;
               return this.handleSlotClick("VAULT", i, stack, button, shift, player);
            }
         }

         for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
               int pSlot = 9 + r * 9 + c;
               int sx = gridX + c * slotSize;
               int sy = playerMainGridY + r * slotSize;
               if (mouseX >= (double)sx && mouseX < (double)(sx + slotSize - 2) && mouseY >= (double)sy && mouseY < (double)(sy + slotSize - 2)) {
                  class_1799 pStack = player.method_31548().method_5438(pSlot);
                  return this.handleSlotClick("PLAYER", pSlot, pStack, button, shift, player);
               }
            }
         }

         for (int cxx = 0; cxx < 9; cxx++) {
            int sx = gridX + cxx * slotSize;
            if (mouseX >= (double)sx && mouseX < (double)(sx + slotSize - 2) && mouseY >= (double)hotbarGridY && mouseY < (double)(hotbarGridY + slotSize - 2)) {
               class_1799 pStack = player.method_31548().method_5438(cxx);
               return this.handleSlotClick("PLAYER", cxx, pStack, button, shift, player);
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private boolean handleSlotClick(String targetContainer, int targetSlot, class_1799 targetStack, int button, boolean shift, class_1657 player) {
      if (button != 0 && button != 1) {
         return false;
      } else if (shift) {
         if (this.heldVaultItem == null && targetStack != null && !targetStack.method_7960()) {
            send("VAULT_QUICK_MOVE", targetContainer + "|" + targetSlot, 0);
            this.playVaultSound();
            return true;
         } else {
            return true;
         }
      } else if (this.heldVaultItem == null) {
         if (targetStack == null || targetStack.method_7960()) {
            return false;
         } else if (button == 0) {
            int count = targetStack.method_7947();
            this.heldVaultItem = new GuildScreen.HeldVaultItem(targetContainer, targetSlot, targetStack.method_7972(), 0);
            this.playVaultSound();
            return true;
         } else if (button == 1) {
            int count = targetStack.method_7947();
            int take = (count + 1) / 2;
            int rem = count - take;
            this.heldVaultItem = new GuildScreen.HeldVaultItem(targetContainer, targetSlot, targetStack.method_46651(take), rem);
            this.playVaultSound();
            return true;
         } else {
            return false;
         }
      } else {
         boolean isSameSlot = this.heldVaultItem.container.equals(targetContainer) && this.heldVaultItem.slot == targetSlot;
         if (isSameSlot) {
            if (button == 0) {
               this.heldVaultItem = null;
               this.playVaultSound();
               return true;
            } else if (button == 1) {
               this.heldVaultItem.stack.method_7934(1);
               this.heldVaultItem.remainingInSource++;
               if (this.heldVaultItem.stack.method_7960()) {
                  this.heldVaultItem = null;
               }

               this.playVaultSound();
               return true;
            } else {
               return false;
            }
         } else if (targetStack != null && !targetStack.method_7960()) {
            if (class_1799.method_31577(this.heldVaultItem.stack, targetStack)) {
               int space = targetStack.method_7914() - targetStack.method_7947();
               if (space <= 0) {
                  return false;
               } else if (button == 0) {
                  int toMove = Math.min(space, this.heldVaultItem.stack.method_7947());
                  send(
                     "VAULT_TRANSFER",
                     this.heldVaultItem.container + "|" + this.heldVaultItem.slot + "|" + targetContainer + "|" + targetSlot + "|" + toMove,
                     0
                  );
                  this.heldVaultItem.stack.method_7934(toMove);
                  if (this.heldVaultItem.stack.method_7960()) {
                     this.heldVaultItem = null;
                  }

                  this.playVaultSound();
                  return true;
               } else if (button == 1) {
                  send("VAULT_TRANSFER", this.heldVaultItem.container + "|" + this.heldVaultItem.slot + "|" + targetContainer + "|" + targetSlot + "|1", 0);
                  this.heldVaultItem.stack.method_7934(1);
                  if (this.heldVaultItem.stack.method_7960()) {
                     this.heldVaultItem = null;
                  }

                  this.playVaultSound();
                  return true;
               } else {
                  return false;
               }
            } else {
               if (button == 0) {
                  int srcCount = this.getSlotCount(this.heldVaultItem.container, this.heldVaultItem.slot, player);
                  if (this.heldVaultItem.stack.method_7947() == srcCount) {
                     send(
                        "VAULT_TRANSFER",
                        this.heldVaultItem.container
                           + "|"
                           + this.heldVaultItem.slot
                           + "|"
                           + targetContainer
                           + "|"
                           + targetSlot
                           + "|"
                           + this.heldVaultItem.stack.method_7947(),
                        0
                     );
                     this.heldVaultItem = new GuildScreen.HeldVaultItem(this.heldVaultItem.container, this.heldVaultItem.slot, targetStack.method_7972(), 0);
                     this.playVaultSound();
                     return true;
                  }
               }

               return false;
            }
         } else if (button == 0) {
            int toMove = this.heldVaultItem.stack.method_7947();
            send("VAULT_TRANSFER", this.heldVaultItem.container + "|" + this.heldVaultItem.slot + "|" + targetContainer + "|" + targetSlot + "|" + toMove, 0);
            this.heldVaultItem = null;
            this.playVaultSound();
            return true;
         } else if (button == 1) {
            send("VAULT_TRANSFER", this.heldVaultItem.container + "|" + this.heldVaultItem.slot + "|" + targetContainer + "|" + targetSlot + "|1", 0);
            this.heldVaultItem.stack.method_7934(1);
            if (this.heldVaultItem.stack.method_7960()) {
               this.heldVaultItem = null;
            }

            this.playVaultSound();
            return true;
         } else {
            return false;
         }
      }
   }

   private int getSlotCount(String container, int slot, class_1657 player) {
      if ("VAULT".equals(container)) {
         List<class_1799> vault = GuildClientState.data.vaultItems();
         if (vault != null && slot >= 0 && slot < vault.size()) {
            class_1799 s = vault.get(slot);
            return s != null ? s.method_7947() : 0;
         }
      } else if ("PLAYER".equals(container) && player != null && slot >= 0 && slot < player.method_31548().method_5439()) {
         class_1799 s = player.method_31548().method_5438(slot);
         return s != null ? s.method_7947() : 0;
      }

      return 0;
   }

   private void renderGeneral(class_332 graphics, GuildSyncPayload data, int mouseX, int mouseY) {
      if (!data.member()) {
         boolean isInvited = "INVITED".equals(data.role());
         int cardW = this.contentWidth - 20;
         int cardH = this.panelHeight - 56;
         int cx = this.contentX + 10;
         int cy = this.panelY + 44;
         if (isInvited) {
            this.renderCard(graphics, cx, cy, cardW, cardH, "INVITACIÓN DE GREMIO PENDIENTE", -10238030);
            graphics.method_51433(this.field_22793, "Has recibido una invitación para unirte a un gremio:", cx + 20, cy + 24, -3352620, false);
            int bannerY = cy + 40;
            graphics.method_25294(cx + 20, bannerY, cx + cardW - 20, bannerY + 42, -15657192);
            this.drawOutline(graphics, cx + 20, bannerY, cardW - 40, 42, accentColor());
            this.drawGuildIcon(graphics, data.icon(), cx + 30, bannerY + 9, 24, accentColor());
            graphics.method_51433(this.field_22793, "§l" + data.name(), cx + 64, bannerY + 12, -1, false);
            graphics.method_51433(
               this.field_22793, "§aInvitación activa §8• §7Total de miembros: §e" + data.memberCount(), cx + 64, bannerY + 26, -7364196, false
            );
            int memY = bannerY + 52;
            graphics.method_51433(this.field_22793, "§7Miembros del gremio (Pasa el cursor para ver sus nombres):", cx + 20, memY, -6443350, false);
            int headX = cx + 20;
            int headY = memY + 14;
            int drawn = 0;

            for (GuildScreen.MemberInfo m : this.parseMembers(data)) {
               if (drawn < 14) {
                  int hx = headX + drawn * 24;
                  PlayerAvatarHelper.drawPlayerHead(graphics, m.uuid, m.name, hx, headY, 18);
                  if (mouseX >= hx && mouseX < hx + 18 && mouseY >= headY && mouseY < headY + 18) {
                     this.hoveredMemberTooltip = List.of(class_2561.method_43470("§b" + m.name), class_2561.method_43470("§7Rango: §e" + roleLabel(m.role)));
                  }

                  drawn++;
               }
            }

            int perksY = headY + 30;
            graphics.method_25294(cx + 20, perksY, cx + cardW - 20, perksY + 44, -1726999016);
            this.drawOutline(graphics, cx + 20, perksY, cardW - 40, 44, -14207946);
            graphics.method_51433(this.field_22793, "§e✦ Beneficios de unirte:", cx + 28, perksY + 6, -11940, false);
            graphics.method_51433(
               this.field_22793, "§7• Acceso a la Bóveda compartida y PC de almacenamiento de Pokémon.", cx + 28, perksY + 18, -3352620, false
            );
            graphics.method_51433(
               this.field_22793, "§7• Sinergia de gremio con bonos de EXP, CobbleDollars y Shiny al jugar juntos.", cx + 28, perksY + 30, -3352620, false
            );
         } else {
            this.renderCard(graphics, cx, cy, cardW, cardH, "FUNDAR UN NUEVO GREMIO", -10238030);
            graphics.method_51433(
               this.field_22793, "Crea tu propio gremio para compartir bóveda, PC y activar la sinergia de grupo.", cx + 20, cy + 22, -6443350, false
            );
            int formY = cy + 32;
            graphics.method_51433(this.field_22793, "Nombre del gremio (2 a 24 caracteres):", cx + 30, formY + 4, -3352620, false);
            graphics.method_51433(this.field_22793, "Color identificativo:", cx + 30, formY + 40, -3352620, false);
            int barW = cardW - 100;
            this.drawSelectedColor(graphics, cx + 30 + barW + 10, formY + 54, this.draftColor);
            graphics.method_51433(this.field_22793, "Emblema del gremio:", cx + 30, formY + 78, -3352620, false);
            int prevW = 200;
            int prevX = cx + (cardW - prevW) / 2;
            int prevY = formY + 124;
            graphics.method_51433(this.field_22793, "Vista previa del gremio:", prevX, prevY - 12, -7364196, false);
            graphics.method_25294(prevX, prevY, prevX + prevW, prevY + 24, -15657192);
            this.drawOutline(graphics, prevX, prevY, prevW, 24, accentColor());
            this.drawGuildIcon(graphics, this.draftIcon, prevX + 6, prevY + 3, 18, accentColor());
            graphics.method_51433(
               this.field_22793, this.fit(this.draftName.isBlank() ? "Mi Gremio" : this.draftName, prevW - 32), prevX + 28, prevY + 7, -1, false
            );
         }
      } else {
         int cardY = this.panelY + 46;
         int cardHeight = 56;
         int gap = 8;
         int cardWidth = (this.contentWidth - 2 * gap) / 3;
         this.renderCard(graphics, this.contentX, cardY, cardWidth, cardHeight, "BANCO GREMIAL", -11940);
         graphics.method_51433(this.field_22793, this.fit("$ " + data.balance(), cardWidth - 16), this.contentX + 10, cardY + 22, -11940, false);
         graphics.method_51433(
            this.field_22793, this.fit("Cartera: $ " + data.walletBalance(), cardWidth - 16), this.contentX + 10, cardY + 38, -6369041, false
         );
         int card2X = this.contentX + cardWidth + gap;
         this.renderCard(graphics, card2X, cardY, cardWidth, cardHeight, "MIEMBROS", -10238030);
         long onlineCount = this.countOnlineMembers(data);
         graphics.method_51433(this.field_22793, onlineCount + " / " + data.memberCount() + " online", card2X + 10, cardY + 22, -1, false);
         graphics.method_25294(card2X + cardWidth - 16, cardY + 23, card2X + cardWidth - 10, cardY + 29, -13381254);
         int headX = card2X + 10;
         int headY = cardY + 35;
         int headsDrawn = 0;
         int maxHeads = Math.max(1, (cardWidth - 20) / 20);

         for (GuildScreen.MemberInfo mx : this.parseMembers(data)) {
            if (mx.online && headsDrawn < maxHeads) {
               int hx = headX + headsDrawn * 18;
               PlayerAvatarHelper.drawPlayerHead(graphics, mx.uuid, mx.name, hx, headY, 16);
               if (mouseX >= hx && mouseX < hx + 16 && mouseY >= headY && mouseY < headY + 16) {
                  this.hoveredMemberTooltip = List.of(class_2561.method_43470("§b" + mx.name), class_2561.method_43470("§aEn línea"));
               }

               headsDrawn++;
            }
         }

         int card3X = card2X + cardWidth + gap;
         int card3W = this.contentWidth - 2 * (cardWidth + gap);
         this.renderCard(graphics, card3X, cardY, card3W, cardHeight, "TU RANGO", accentColor());
         String roleStr = roleLabel(data.role());
         graphics.method_51433(this.field_22793, roleStr, card3X + 10, cardY + 22, roleColor(data.role()), false);
         String bankY = data.role();

         String permDesc = switch (bankY) {
            case "LEADER" -> "Control total";
            case "OFFICER" -> "Gestión y banco";
            default -> "Acceso de miembro";
         };
         graphics.method_51433(this.field_22793, permDesc, card3X + 10, cardY + 38, -6443350, false);
         int bankY = cardY + cardHeight + 8;
         int bankH = 46;
         this.renderCard(graphics, this.contentX, bankY, this.contentWidth, bankH, "TRANSFERENCIAS DE BANCO", -11940);
         int motdY = bankY + bankH + 8;
         int motdHeight = this.panelY + this.panelHeight - motdY - 10;
         int motdW = Math.max(130, (this.contentWidth - 8) * 36 / 100);
         int chatX = this.contentX + motdW + 8;
         int chatW = this.contentWidth - motdW - 8;
         this.renderCard(graphics, this.contentX, motdY, motdW, motdHeight, "TABLÓN DE ANUNCIOS", accentColor());
         if (!this.editingMotd) {
            String motdMsg = data.motd().isBlank()
               ? "¡Bienvenidos al gremio! Coopera con tus compañeros, usa el PC compartido y comparte waypoints."
               : data.motd();
            List<class_5481> lines = this.field_22793.method_1728(class_2561.method_43470(motdMsg), motdW - 16);
            int maxLines = Math.max(1, (motdHeight - 34) / 10);

            for (int l = 0; l < Math.min(lines.size(), maxLines); l++) {
               graphics.method_51430(this.field_22793, lines.get(l), this.contentX + 8, motdY + 24 + l * 10, -1905690, false);
            }
         }

         this.renderCard(graphics, chatX, motdY, chatW, motdHeight, "CHAT DEL GREMIO", accentColor());
         int inputH = 18;
         int inputY = motdY + motdHeight - inputH - 5;
         int msgAreaX = chatX + 6;
         int msgAreaY = motdY + 18;
         int msgAreaW = chatW - 12;
         int msgAreaH = inputY - 3 - msgAreaY;
         graphics.method_25294(msgAreaX, msgAreaY, msgAreaX + msgAreaW, msgAreaY + msgAreaH, -586411244);
         this.drawOutline(graphics, msgAreaX, msgAreaY, msgAreaW, msgAreaH, 872415231);
         List<String> messages = data.chatMessages();
         int lineHeight = 10;
         int maxLines = Math.max(1, (msgAreaH - 4) / lineHeight);
         int totalMessages = messages.size();
         int startIdx = Math.max(0, totalMessages - maxLines - this.chatScrollOffset);
         int endIdx = Math.min(totalMessages, startIdx + maxLines);

         for (int mxx = startIdx; mxx < endIdx; mxx++) {
            String raw = messages.get(mxx);
            String timeStr = "";
            String sender = "";
            String text = raw;
            if (raw != null && raw.contains("|")) {
               String[] parts = raw.split("\\|", 3);
               if (parts.length >= 3) {
                  timeStr = parts[0];
                  sender = parts[1];

                  try {
                     byte[] decoded = Base64.getDecoder().decode(parts[2]);
                     text = new String(decoded, StandardCharsets.UTF_8);
                  } catch (Exception var47) {
                     text = parts[2];
                  }
               } else if (parts.length == 2) {
                  sender = parts[0];
                  text = parts[1];
               }
            }

            String formatted;
            if (!timeStr.isEmpty() && !sender.isEmpty()) {
               formatted = "§7[" + timeStr + "] §b" + sender + "§8: §f" + text;
            } else if (!sender.isEmpty()) {
               formatted = "§b" + sender + "§8: §f" + text;
            } else {
               formatted = text;
            }

            int my = msgAreaY + 4 + (mxx - startIdx) * lineHeight;
            graphics.method_51433(this.field_22793, this.fit(formatted, msgAreaW - 6), msgAreaX + 4, my, -1, false);
         }
      }
   }

   private void renderMembers(class_332 graphics, GuildSyncPayload data, int mouseX, int mouseY) {
      int listTop = this.panelY + 76;
      int rowHeight = 28;
      int visibleCount = Math.max(1, (this.panelHeight - 110) / rowHeight);
      List<GuildScreen.MemberInfo> filtered = this.getFilteredMembers(data);
      int maxOffset = Math.max(0, filtered.size() - visibleCount);
      this.memberScrollOffset = Math.min(this.memberScrollOffset, maxOffset);
      if (filtered.isEmpty()) {
         graphics.method_51433(this.field_22793, "No se encontraron miembros.", this.contentX + 8, listTop + 14, -6443350, false);
      } else {
         for (int i = 0; i < visibleCount && this.memberScrollOffset + i < filtered.size(); i++) {
            GuildScreen.MemberInfo m = filtered.get(this.memberScrollOffset + i);
            int rowY = listTop + i * rowHeight;
            int rowW = this.contentWidth;
            graphics.method_25294(this.contentX, rowY, this.contentX + rowW, rowY + rowHeight - 2, -15129819);
            graphics.method_25294(this.contentX, rowY, this.contentX + 2, rowY + rowHeight - 2, roleColor(m.role));
            PlayerAvatarHelper.drawPlayerHead(graphics, m.uuid, m.name, this.contentX + 6, rowY + 3, 20);
            if (mouseX >= this.contentX + 6 && mouseX < this.contentX + 26 && mouseY >= rowY + 3 && mouseY < rowY + 23) {
               this.hoveredMemberTooltip = List.of(class_2561.method_43470("§b" + m.name), class_2561.method_43470("§7Rango: §e" + roleLabel(m.role)));
            }

            graphics.method_51433(this.field_22793, this.fit(m.name, 105), this.contentX + 32, rowY + 5, -1, false);
            int statusDotColor = m.online ? -13381254 : -8944253;
            graphics.method_25294(this.contentX + 142, rowY + 9, this.contentX + 147, rowY + 14, statusDotColor);
            this.drawRoleBadge(graphics, this.contentX + 155, rowY + 4, m.role);
            String statusText = m.online ? "En línea" : "Desconectado";
            int statusColor = m.online ? -13381254 : -7825516;
            int statusX = this.contentX + 225;
            int statusY = rowY + 7;
            int textW = this.field_22793.method_1727(statusText);
            int textH = 10;
            graphics.method_51433(this.field_22793, statusText, statusX, statusY, statusColor, false);
            if (!m.online && mouseX >= statusX && mouseX < statusX + textW && mouseY >= statusY - 1 && mouseY < statusY + textH + 1) {
               String timeInfo = m.statusDetail;
               if (timeInfo.contains(";")) {
                  timeInfo = timeInfo.substring(timeInfo.indexOf(";") + 1).trim();
               }

               String lowerTime = timeInfo.toLowerCase(Locale.ROOT);
               if (lowerTime.startsWith("ultima conexion")) {
                  timeInfo = timeInfo.substring("ultima conexion".length()).trim();
               } else if (lowerTime.startsWith("última conexión")) {
                  timeInfo = timeInfo.substring("última conexión".length()).trim();
               } else if (lowerTime.startsWith("ultima vez")) {
                  timeInfo = timeInfo.substring("ultima vez".length()).trim();
               } else if (lowerTime.startsWith("última vez")) {
                  timeInfo = timeInfo.substring("última vez".length()).trim();
               }

               if (timeInfo.isBlank()) {
                  timeInfo = "Desconocida";
               }

               this.hoveredMemberTooltip = List.of(class_2561.method_43470("§6Última conexión:"), class_2561.method_43470("§f" + timeInfo));
            }
         }

         String footerText = "Total: " + filtered.size() + " miembros";
         graphics.method_51433(this.field_22793, footerText, this.contentX, this.panelY + this.panelHeight - 18, -7825516, false);
         if (this.actionInput != null && this.actionInput.method_25370() && !this.playerSuggestions.isEmpty()) {
            int dropX = this.actionInput.method_46426();
            int dropY = this.actionInput.method_46427() + this.actionInput.method_25364() + 2;
            int dropW = this.actionInput.method_25368();
            int itemH = 16;
            int dropH = this.playerSuggestions.size() * itemH + 4;
            graphics.method_25294(dropX, dropY, dropX + dropW, dropY + dropH, -183495144);
            this.drawOutline(graphics, dropX, dropY, dropW, dropH, accentColor());

            for (int sIdx = 0; sIdx < this.playerSuggestions.size(); sIdx++) {
               String sName = this.playerSuggestions.get(sIdx);
               int iy = dropY + 2 + sIdx * itemH;
               boolean hovered = mouseX >= dropX && mouseX < dropX + dropW && mouseY >= iy && mouseY < iy + itemH;
               if (hovered) {
                  graphics.method_25294(dropX + 2, iy, dropX + dropW - 2, iy + itemH, 1146863783);
               }

               PlayerAvatarHelper.drawPlayerHead(graphics, null, sName, dropX + 4, iy + 2, 12);
               graphics.method_51433(this.field_22793, sName, dropX + 20, iy + 4, hovered ? -1 : -3352620, false);
            }
         }
      }
   }

   private void renderPC(class_332 graphics, GuildSyncPayload data, int mouseX, int mouseY) {
      int slotSize = 32;
      int cols = 6;
      int rows = 5;
      int gap = 14;
      int totalW = cols * slotSize + gap + slotSize + gap + cols * slotSize;
      int startX = this.contentX + Math.max(6, (this.contentWidth - totalW) / 2);
      int gridX = startX;
      int partyX = startX + cols * slotSize + gap;
      int privX = partyX + slotSize + gap;
      int labelY = this.panelY + 46;
      int boxTabY = this.panelY + 58;
      int gridY = this.panelY + 82;
      graphics.method_51433(this.field_22793, "CAJA GREMIO", startX, labelY, accentColor(), false);
      graphics.method_25300(this.field_22793, "EQUIPO", partyX + slotSize / 2, labelY, -10764121);
      graphics.method_25300(this.field_22793, "CAJA PC", privX + cols * slotSize / 2, labelY, -10764121);
      graphics.method_25294(startX - 3, gridY - 3, startX + cols * slotSize + 3, gridY + rows * slotSize + 3, -15393248);
      this.drawOutline(graphics, startX - 3, gridY - 3, cols * slotSize + 6, rows * slotSize + 6, -14010310);
      List<GuildScreen.PokemonSlotPreview> allPreviews = this.parsePreviews(data);
      Map<Integer, GuildScreen.PokemonSlotPreview> currentBoxSlots = new HashMap<>();

      for (GuildScreen.PokemonSlotPreview p : allPreviews) {
         if (p.box() == this.selectedPcBox) {
            currentBoxSlots.put(p.slot(), p);
         }
      }

      for (int r = 0; r < rows; r++) {
         for (int c = 0; c < cols; c++) {
            int s = r * cols + c;
            int sx = gridX + c * slotSize;
            int sy = gridY + r * slotSize;
            graphics.method_25294(sx, sy, sx + slotSize - 2, sy + slotSize - 2, -14866390);
            this.drawOutline(graphics, sx, sy, slotSize - 2, slotSize - 2, -13746880);
            boolean isHeld = this.heldPokemon != null
               && "GUILD".equals(this.heldPokemon.container())
               && this.heldPokemon.box() == this.selectedPcBox
               && this.heldPokemon.slot() == s;
            GuildScreen.PokemonSlotPreview px = currentBoxSlots.get(s);
            if (px != null && !isHeld) {
               class_1799 stack = getOrCreatePokemonStack(px.species(), px.shiny(), px.form());
               if (!stack.method_7960()) {
                  graphics.method_51448().method_22903();
                  graphics.method_51448().method_46416((float)(sx + 2), (float)(sy + 2), 0.0F);
                  graphics.method_51448().method_22905(1.65F, 1.65F, 1.0F);
                  graphics.method_51427(stack, 0, 0);
                  graphics.method_51448().method_22909();
               } else {
                  graphics.method_25294(sx + 3, sy + 3, sx + slotSize - 5, sy + slotSize - 5, -13807798);
                  String initial = px.species().isEmpty() ? "?" : px.species().substring(0, 1).toUpperCase(Locale.ROOT);
                  graphics.method_25300(this.field_22793, initial, sx + slotSize / 2, sy + (slotSize - 8) / 2, -10764121);
               }

               graphics.method_51448().method_22903();
               graphics.method_51448().method_46416(0.0F, 0.0F, 300.0F);
               if (px.shiny()) {
                  graphics.method_51433(this.field_22793, "★", sx + slotSize - 9, sy + 2, -11940, true);
               }

               String lvl = String.valueOf(px.level());
               graphics.method_51433(this.field_22793, lvl, sx + slotSize - this.field_22793.method_1727(lvl) - 3, sy + slotSize - 10, -3352620, true);
               graphics.method_51448().method_22909();
            } else if (isHeld) {
               graphics.method_25294(sx, sy, sx + slotSize - 2, sy + slotSize - 2, 861651111);
               this.drawOutline(graphics, sx, sy, slotSize - 2, slotSize - 2, 1717289127);
            }

            if (mouseX >= sx && mouseX < sx + slotSize - 2 && mouseY >= sy && mouseY < sy + slotSize - 2) {
               if (this.heldPokemon != null && this.heldPokemon.restricted()) {
                  graphics.method_25294(sx, sy, sx + slotSize - 2, sy + slotSize - 2, 1726434364);
                  this.drawOutline(graphics, sx, sy, slotSize - 2, slotSize - 2, -56798);
               } else {
                  graphics.method_25294(sx, sy, sx + slotSize - 2, sy + slotSize - 2, 1146863783);
                  this.drawOutline(graphics, sx, sy, slotSize - 2, slotSize - 2, -10764121);
               }
            }
         }
      }

      ClientParty party = this.getClientParty();
      int partyOccupied = 0;
      if (party != null) {
         for (int i = 0; i < 6; i++) {
            if (party.get(i) != null) {
               partyOccupied++;
            }
         }
      }

      graphics.method_25300(this.field_22793, partyOccupied + "/6", partyX + slotSize / 2, boxTabY + 5, -6443350);
      graphics.method_25294(partyX - 3, gridY - 3, partyX + slotSize + 3, gridY + 6 * slotSize + 3, -15393248);
      this.drawOutline(graphics, partyX - 3, gridY - 3, slotSize + 6, 6 * slotSize + 6, -14010310);

      for (int ix = 0; ix < 6; ix++) {
         int py = gridY + ix * slotSize;
         graphics.method_25294(partyX, py, partyX + slotSize - 2, py + slotSize - 2, -14866390);
         this.drawOutline(graphics, partyX, py, slotSize - 2, slotSize - 2, -13746880);
         boolean isHeldx = this.heldPokemon != null && "PARTY".equals(this.heldPokemon.container()) && this.heldPokemon.slot() == ix;
         if (party != null && !isHeldx) {
            Pokemon pxx = party.get(ix);
            if (pxx != null) {
               class_1799 stackx = PokemonItem.from(pxx, 1);
               if (!stackx.method_7960()) {
                  graphics.method_51448().method_22903();
                  graphics.method_51448().method_46416((float)(partyX + 2), (float)(py + 2), 0.0F);
                  graphics.method_51448().method_22905(1.65F, 1.65F, 1.0F);
                  graphics.method_51427(stackx, 0, 0);
                  graphics.method_51448().method_22909();
               } else {
                  graphics.method_25294(partyX + 3, py + 3, partyX + slotSize - 5, py + slotSize - 5, -13807798);
                  String name = pxx.getSpecies().getName();
                  String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
                  graphics.method_25300(this.field_22793, initial, partyX + slotSize / 2, py + (slotSize - 8) / 2, -10764121);
               }

               graphics.method_51448().method_22903();
               graphics.method_51448().method_46416(0.0F, 0.0F, 300.0F);
               if (pxx.getShiny()) {
                  graphics.method_51433(this.field_22793, "★", partyX + slotSize - 9, py + 2, -11940, true);
               }

               String lvl = String.valueOf(pxx.getLevel());
               graphics.method_51433(this.field_22793, lvl, partyX + slotSize - this.field_22793.method_1727(lvl) - 3, py + slotSize - 10, -3352620, true);
               graphics.method_51448().method_22909();
            }
         } else if (isHeldx) {
            graphics.method_25294(partyX, py, partyX + slotSize - 2, py + slotSize - 2, 861651111);
            this.drawOutline(graphics, partyX, py, slotSize - 2, slotSize - 2, 1717289127);
         }

         if (mouseX >= partyX && mouseX < partyX + slotSize - 2 && mouseY >= py && mouseY < py + slotSize - 2) {
            graphics.method_25294(partyX, py, partyX + slotSize - 2, py + slotSize - 2, 1146863783);
            this.drawOutline(graphics, partyX, py, slotSize - 2, slotSize - 2, -10764121);
         }
      }

      ClientPC clientPc = this.getClientPC();
      ClientBox clientBox = clientPc != null && privatePcBox < clientPc.getBoxes().size() ? (ClientBox)clientPc.getBoxes().get(privatePcBox) : null;
      int totalPcBoxes = clientPc != null && clientPc.getBoxes().size() > 0 ? clientPc.getBoxes().size() : 1;
      graphics.method_25300(this.field_22793, "Caja " + (privatePcBox + 1) + " / " + totalPcBoxes, privX + cols * slotSize / 2, boxTabY + 5, -1905690);
      graphics.method_25294(privX - 3, gridY - 3, privX + cols * slotSize + 3, gridY + rows * slotSize + 3, -15393248);
      this.drawOutline(graphics, privX - 3, gridY - 3, cols * slotSize + 6, rows * slotSize + 6, -14010310);

      for (int r = 0; r < rows; r++) {
         for (int c = 0; c < cols; c++) {
            int sxx = r * cols + c;
            int sxxx = privX + c * slotSize;
            int syx = gridY + r * slotSize;
            graphics.method_25294(sxxx, syx, sxxx + slotSize - 2, syx + slotSize - 2, -14866390);
            this.drawOutline(graphics, sxxx, syx, slotSize - 2, slotSize - 2, -13746880);
            boolean isHeldxx = this.heldPokemon != null
               && "PC".equals(this.heldPokemon.container())
               && this.heldPokemon.box() == privatePcBox
               && this.heldPokemon.slot() == sxx;
            if (clientBox != null && sxx < clientBox.getSlots().size() && !isHeldxx) {
               Pokemon pxx = (Pokemon)clientBox.getSlots().get(sxx);
               if (pxx != null) {
                  class_1799 stackxx = PokemonItem.from(pxx, 1);
                  if (!stackxx.method_7960()) {
                     graphics.method_51448().method_22903();
                     graphics.method_51448().method_46416((float)(sxxx + 2), (float)(syx + 2), 0.0F);
                     graphics.method_51448().method_22905(1.65F, 1.65F, 1.0F);
                     graphics.method_51427(stackxx, 0, 0);
                     graphics.method_51448().method_22909();
                  } else {
                     graphics.method_25294(sxxx + 3, syx + 3, sxxx + slotSize - 5, syx + slotSize - 5, -13807798);
                     String name = pxx.getSpecies().getName();
                     String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
                     graphics.method_25300(this.field_22793, initial, sxxx + slotSize / 2, syx + (slotSize - 8) / 2, -10764121);
                  }

                  graphics.method_51448().method_22903();
                  graphics.method_51448().method_46416(0.0F, 0.0F, 300.0F);
                  if (pxx.getShiny()) {
                     graphics.method_51433(this.field_22793, "★", sxxx + slotSize - 9, syx + 2, -11940, true);
                  }

                  String lvl = String.valueOf(pxx.getLevel());
                  graphics.method_51433(this.field_22793, lvl, sxxx + slotSize - this.field_22793.method_1727(lvl) - 3, syx + slotSize - 10, -3352620, true);
                  graphics.method_51448().method_22909();
               }
            } else if (isHeldxx) {
               graphics.method_25294(sxxx, syx, sxxx + slotSize - 2, syx + slotSize - 2, 861651111);
               this.drawOutline(graphics, sxxx, syx, slotSize - 2, slotSize - 2, 1717289127);
            }

            if (mouseX >= sxxx && mouseX < sxxx + slotSize - 2 && mouseY >= syx && mouseY < syx + slotSize - 2) {
               graphics.method_25294(sxxx, syx, sxxx + slotSize - 2, syx + slotSize - 2, 1146863783);
               this.drawOutline(graphics, sxxx, syx, slotSize - 2, slotSize - 2, -10764121);
            }
         }
      }
   }

   private void renderPCTooltip(class_332 graphics, GuildSyncPayload data, int mouseX, int mouseY) {
      if (this.heldPokemon == null) {
         int slotSize = 32;
         int cols = 6;
         int rows = 5;
         int gap = 14;
         int totalW = cols * slotSize + gap + slotSize + gap + cols * slotSize;
         int startX = this.contentX + Math.max(6, (this.contentWidth - totalW) / 2);
         int gridX = startX;
         int partyX = startX + cols * slotSize + gap;
         int privX = partyX + slotSize + gap;
         int gridY = this.panelY + 82;
         List<GuildScreen.PokemonSlotPreview> allPreviews = this.parsePreviews(data);
         Map<Integer, GuildScreen.PokemonSlotPreview> currentBoxSlots = new HashMap<>();

         for (GuildScreen.PokemonSlotPreview p : allPreviews) {
            if (p.box() == this.selectedPcBox) {
               currentBoxSlots.put(p.slot(), p);
            }
         }

         for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
               int s = r * cols + c;
               int sx = gridX + c * slotSize;
               int sy = gridY + r * slotSize;
               if (mouseX >= sx && mouseX < sx + slotSize - 2 && mouseY >= sy && mouseY < sy + slotSize - 2) {
                  GuildScreen.PokemonSlotPreview px = currentBoxSlots.get(s);
                  if (px != null) {
                     this.renderPreviewPokemonTooltip(graphics, px, mouseX, mouseY);
                  }

                  return;
               }
            }
         }

         ClientParty party = this.getClientParty();
         if (party != null) {
            for (int i = 0; i < 6; i++) {
               int py = gridY + i * slotSize;
               if (mouseX >= partyX && mouseX < partyX + slotSize - 2 && mouseY >= py && mouseY < py + slotSize - 2) {
                  Pokemon px = party.get(i);
                  if (px != null) {
                     this.renderLivePokemonTooltip(graphics, px, mouseX, mouseY);
                  }

                  return;
               }
            }
         }

         ClientPC clientPc = this.getClientPC();
         ClientBox clientBox = clientPc != null && privatePcBox < clientPc.getBoxes().size() ? (ClientBox)clientPc.getBoxes().get(privatePcBox) : null;
         if (clientBox != null) {
            for (int r = 0; r < rows; r++) {
               for (int cx = 0; cx < cols; cx++) {
                  int s = r * cols + cx;
                  int sx = privX + cx * slotSize;
                  int sy = gridY + r * slotSize;
                  if (mouseX >= sx && mouseX < sx + slotSize - 2 && mouseY >= sy && mouseY < sy + slotSize - 2) {
                     if (s < clientBox.getSlots().size()) {
                        Pokemon px = (Pokemon)clientBox.getSlots().get(s);
                        if (px != null) {
                           this.renderLivePokemonTooltip(graphics, px, mouseX, mouseY);
                        }
                     }

                     return;
                  }
               }
            }
         }
      }
   }

   private void renderLivePokemonTooltip(class_332 graphics, Pokemon p, int mouseX, int mouseY) {
      List<class_2561> tooltip = new ArrayList<>();
      String title = (p.getShiny() ? "★ " : "") + p.getDisplayName(false).getString();
      tooltip.add(class_2561.method_43470("§b§l" + title));
      tooltip.add(class_2561.method_43470("§7Especie: §f" + capitalize(p.getSpecies().getName()) + " §7| Nivel: §e" + p.getLevel()));
      String form = p.getForm().getName();
      if (!form.isBlank() && !form.equals("normal") && !form.equals("standard")) {
         tooltip.add(class_2561.method_43470("§7Forma: §f" + form));
      }

      if (p.getShiny()) {
         tooltip.add(class_2561.method_43470("§6★ Variocolor"));
      }

      EVs evs = p.getEvs();
      tooltip.add(
         class_2561.method_43470(
            "§dEVs: §aHP §f"
               + evs.getOrDefault(Stats.HP)
               + " §8| §cAtk §f"
               + evs.getOrDefault(Stats.ATTACK)
               + " §8| §eDef §f"
               + evs.getOrDefault(Stats.DEFENCE)
               + " §8| §bSpA §f"
               + evs.getOrDefault(Stats.SPECIAL_ATTACK)
               + " §8| §9SpD §f"
               + evs.getOrDefault(Stats.SPECIAL_DEFENCE)
               + " §8| §6Spe §f"
               + evs.getOrDefault(Stats.SPEED)
         )
      );
      IVs ivs = p.getIvs();
      tooltip.add(
         class_2561.method_43470(
            "§bIVs: §aHP §f"
               + ivs.getOrDefault(Stats.HP)
               + " §8| §cAtk §f"
               + ivs.getOrDefault(Stats.ATTACK)
               + " §8| §eDef §f"
               + ivs.getOrDefault(Stats.DEFENCE)
               + " §8| §bSpA §f"
               + ivs.getOrDefault(Stats.SPECIAL_ATTACK)
               + " §8| §9SpD §f"
               + ivs.getOrDefault(Stats.SPECIAL_DEFENCE)
               + " §8| §6Spe §f"
               + ivs.getOrDefault(Stats.SPEED)
         )
      );
      List<Move> moves = p.getMoveSet().getMoves();
      if (!moves.isEmpty()) {
         tooltip.add(class_2561.method_43470("§eMovimientos:"));

         for (Move m : moves) {
            tooltip.add(
               class_2561.method_43470(" §7• §f" + m.getTemplate().getDisplayName().getString() + " §8(" + m.getCurrentPp() + "/" + m.getMaxPp() + " PP)")
            );
         }
      }

      tooltip.add(class_2561.method_43470("§8[Shift+Clic] Transferencia rápida"));
      graphics.method_51434(this.field_22793, tooltip, mouseX, mouseY);
   }

   private void renderPreviewPokemonTooltip(class_332 graphics, GuildScreen.PokemonSlotPreview p, int mouseX, int mouseY) {
      List<class_2561> tooltip = new ArrayList<>();
      String rawName = p.displayName();
      if (rawName == null || rawName.isBlank() || rawName.equalsIgnoreCase("normal") || rawName.equalsIgnoreCase("standard")) {
         rawName = capitalize(p.species());
      }

      String title = (p.shiny() ? "★ " : "") + rawName;
      tooltip.add(class_2561.method_43470("§b§l" + title));
      tooltip.add(class_2561.method_43470("§7Especie: §f" + capitalize(p.species()) + " §7| Nivel: §e" + p.level()));
      if (!p.form().isBlank() && !p.form().equals("normal") && !p.form().equals("standard")) {
         tooltip.add(class_2561.method_43470("§7Forma: §f" + p.form()));
      }

      if (p.shiny()) {
         tooltip.add(class_2561.method_43470("§6★ Variocolor"));
      }

      tooltip.add(class_2561.method_43470("§d\ud83d\udc64 Depositado por: §e" + p.depositor()));
      if (p.evs() != null && !p.evs().isBlank()) {
         String[] evVals = p.evs().split("/");
         if (evVals.length == 6) {
            tooltip.add(
               class_2561.method_43470(
                  "§dEVs: §aHP §f"
                     + evVals[0]
                     + " §8| §cAtk §f"
                     + evVals[1]
                     + " §8| §eDef §f"
                     + evVals[2]
                     + " §8| §bSpA §f"
                     + evVals[3]
                     + " §8| §9SpD §f"
                     + evVals[4]
                     + " §8| §6Spe §f"
                     + evVals[5]
               )
            );
         }
      }

      if (p.ivs() != null && !p.ivs().isBlank()) {
         String[] ivVals = p.ivs().split("/");
         if (ivVals.length == 6) {
            tooltip.add(
               class_2561.method_43470(
                  "§bIVs: §aHP §f"
                     + ivVals[0]
                     + " §8| §cAtk §f"
                     + ivVals[1]
                     + " §8| §eDef §f"
                     + ivVals[2]
                     + " §8| §bSpA §f"
                     + ivVals[3]
                     + " §8| §9SpD §f"
                     + ivVals[4]
                     + " §8| §6Spe §f"
                     + ivVals[5]
               )
            );
         }
      }

      if (p.moves() != null && !p.moves().isBlank()) {
         tooltip.add(class_2561.method_43470("§eMovimientos:"));

         for (String mStr : p.moves().split(";")) {
            String[] mParts = mStr.split(",");
            if (mParts.length >= 3) {
               tooltip.add(class_2561.method_43470(" §7• §f" + mParts[0] + " §8(" + mParts[1] + "/" + mParts[2] + " PP)"));
            } else if (mParts.length >= 1 && !mParts[0].isBlank()) {
               tooltip.add(class_2561.method_43470(" §7• §f" + mParts[0]));
            }
         }
      }

      graphics.method_51434(this.field_22793, tooltip, mouseX, mouseY);
   }

   private void renderWaypoints(class_332 graphics, GuildSyncPayload data) {
      int halfWidth = (this.contentWidth - 12) / 2;
      int listTop = this.panelY + 70;
      graphics.method_51433(this.field_22793, "MIS WAYPOINTS (XAERO)", this.contentX, listTop, -1774361, false);
      graphics.method_51433(this.field_22793, "WAYPOINTS COMPARTIDOS", this.contentX + halfWidth + 12, listTop, -1774361, false);
      graphics.method_25294(this.contentX, listTop + 13, this.contentX + halfWidth, listTop + 14, -13286331);
      graphics.method_25294(this.contentX + halfWidth + 12, listTop + 13, this.contentX + this.contentWidth, listTop + 14, -13286331);
      List<XaeroWaypointBridge.LocalWaypoint> filteredLocal = this.filterLocalWaypoints(this.localWaypoints, this.waypointDimFilter);
      if (filteredLocal.isEmpty()) {
         String message = XaeroWaypointBridge.isAvailable() ? "Sin waypoints (" + DIM_FILTERS[this.waypointDimFilter] + ")" : "Xaero no detectado";
         graphics.method_51433(this.field_22793, message, this.contentX, listTop + 24, -6443350, false);
      }

      List<String> rawShared = new ArrayList<>(
         data.waypoints().stream().filter(encoded -> encoded.split("\\|", 9).length == 9).filter(encoded -> encoded.split("\\|", 9)[1].equals("true")).toList()
      );
      List<String> filteredShared = this.filterSharedWaypoints(rawShared, this.waypointDimFilter);
      if (filteredShared.isEmpty()) {
         graphics.method_51433(
            this.field_22793, "No hay rutas (" + DIM_FILTERS[this.waypointDimFilter] + ")", this.contentX + halfWidth + 12, listTop + 24, -6443350, false
         );
      }
   }

   private void renderLog(class_332 graphics, GuildSyncPayload data, int mouseX, int mouseY) {
      int listTop = this.panelY + 70;
      List<String> rawRows = data.activityLog();
      if (rawRows != null && !rawRows.isEmpty()) {
         List<String> filtered = new ArrayList<>();

         for (int i = rawRows.size() - 1; i >= 0; i--) {
            String raw = rawRows.get(i);
            String lower = raw.toLowerCase(Locale.ROOT);
            boolean isVault = lower.contains("cofre");
            boolean isPc = lower.contains("guardó") || lower.contains("retiró") || raw.contains("POKEMON") || raw.contains("§DATA:");
            if (isVault) {
               isPc = false;
            }

            if (this.logFilterTab == 0) {
               if (!isVault && !isPc) {
                  filtered.add(raw);
               }
            } else if (this.logFilterTab == 1) {
               if (isPc) {
                  filtered.add(raw);
               }
            } else if (this.logFilterTab == 2 && isVault) {
               filtered.add(raw);
            }
         }

         if (filtered.isEmpty()) {
            String emptyMsg = switch (this.logFilterTab) {
               case 1 -> "No hay registros de movimientos en la PC.";
               case 2 -> "No hay registros de movimientos en el cofre.";
               default -> "No hay eventos generales registrados.";
            };
            graphics.method_51433(this.field_22793, emptyMsg, this.contentX, listTop + 10, -6443350, false);
         } else {
            int maxOffset = Math.max(0, filtered.size() - 5);
            this.logScrollOffset = Math.min(this.logScrollOffset, maxOffset);
            int currentY = listTop;
            int maxBottom = this.panelY + this.panelHeight - 40;

            for (int i = this.logScrollOffset; i < filtered.size(); i++) {
               String rawx = filtered.get(i);
               int cardH = this.renderLogEntryCard(graphics, rawx, this.contentX, currentY, this.contentWidth, mouseX, mouseY);
               currentY += cardH + 4;
               if (currentY + 24 > maxBottom) {
                  break;
               }
            }
         }
      } else {
         graphics.method_51433(this.field_22793, "No hay eventos registrados recientemente.", this.contentX, listTop + 10, -6443350, false);
      }
   }

   private void renderCustomize(class_332 graphics, GuildSyncPayload data) {
      boolean isLeader = "LEADER".equals(data.role());
      if (isLeader) {
         int card1Y = this.panelY + 44;
         int card1H = 46;
         this.renderCard(graphics, this.contentX, card1Y, this.contentWidth, card1H, "NOMBRE DEL GREMIO", -10238030);
         int badgeX = this.contentX + this.contentWidth - 116;
         int badgeY = card1Y + 10;
         int badgeW = 106;
         int badgeH = 28;
         graphics.method_25294(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH, -15657192);
         this.drawOutline(graphics, badgeX, badgeY, badgeW, badgeH, accentColor());
         this.drawGuildIcon(graphics, this.draftIcon, badgeX + 4, badgeY + 4, 20, accentColor());
         graphics.method_51433(
            this.field_22793, this.fit(this.draftName.isBlank() ? "Gremio" : this.draftName, badgeW - 30), badgeX + 28, badgeY + 10, -1, false
         );
         int card2Y = card1Y + card1H + 6;
         int card2H = 54;
         this.renderCard(graphics, this.contentX, card2Y, this.contentWidth, card2H, "COLOR DEL GREMIO (SELECTOR VISUAL)", -10238030);
         int barW = this.contentWidth - 48;
         int prevBoxX = this.contentX + 10 + barW + 8;
         int prevBoxY = card2Y + 22;
         this.drawSelectedColor(graphics, prevBoxX, prevBoxY, this.draftColor);
         int card3Y = card2Y + card2H + 6;
         int card3H = 52;
         this.renderCard(graphics, this.contentX, card3Y, this.contentWidth, card3H, "EMBLEMA DEL GREMIO", -10238030);
         int emblemInfoX = this.contentX + 10 + 224 + 8;
         if (this.contentWidth > 320) {
            graphics.method_51433(this.field_22793, "Actual: §f" + this.draftIcon.toUpperCase(), emblemInfoX, card3Y + 24, accentColor(), false);
         }
      } else {
         int cardW = Math.min(360, this.contentWidth - 40);
         int cardH = 100;
         int cx = this.contentX + (this.contentWidth - cardW) / 2;
         int cy = this.panelY + (this.panelHeight - cardH) / 2;
         graphics.method_25294(cx, cy, cx + cardW, cy + cardH, -15459298);
         this.drawOutline(graphics, cx, cy, cardW, cardH, -14010052);
         graphics.method_25300(this.field_22793, "\ud83d\udd12 ACCESO RESTRINGIDO", cx + cardW / 2, cy + 24, -2069387);
         graphics.method_25300(this.field_22793, "Solo el líder del gremio tiene permisos para", cx + cardW / 2, cy + 44, -3352620);
         graphics.method_25300(this.field_22793, "modificar el nombre, color y emblema.", cx + cardW / 2, cy + 58, -6443350);
      }
   }

   private void renderCard(class_332 graphics, int x, int y, int width, int height, String title, int titleColor) {
      graphics.method_25294(x, y, x + width, y + height, -15261662);
      this.drawOutline(graphics, x, y, width, height, -14010310);
      graphics.method_51433(this.field_22793, this.fit(title, width - 16), x + 8, y + 8, titleColor, false);
   }

   private void drawOutline(class_332 graphics, int x, int y, int width, int height, int color) {
      graphics.method_25294(x, y, x + width, y + 1, color);
      graphics.method_25294(x, y + height - 1, x + width, y + height, color);
      graphics.method_25294(x, y, x + 1, y + height, color);
      graphics.method_25294(x + width - 1, y, x + width, y + height, color);
   }

   private void drawRoleBadge(class_332 graphics, int x, int y, String role) {
      int color = roleColor(role);
      String label = roleLabel(role).toUpperCase();
      int pillW = this.field_22793.method_1727(label) + 8;
      graphics.method_25294(x, y, x + pillW, y + 13, -14405839);
      this.drawOutline(graphics, x, y, pillW, 13, color);
      graphics.method_51433(this.field_22793, label, x + 4, y + 3, color, false);
   }

   private static int roleColor(String role) {
      return switch (role) {
         case "LEADER" -> -10496;
         case "OFFICER" -> -11141121;
         default -> -3309774;
      };
   }

   private void drawGuildIcon(class_332 graphics, String icon, int x, int y, int size, int color) {
      graphics.method_25294(x, y, x + size, y + size, -14669269);
      this.drawOutline(graphics, x, y, size, size, color);
      String var7 = icon.toLowerCase();
      switch (var7) {
         case "espada":
            graphics.method_25294(x + size / 2 - 1, y + 3, x + size / 2 + 1, y + size - 3, -2039584);
            graphics.method_25294(x + 3, y + size - 6, x + size - 3, y + size - 4, color);
            break;
         case "escudo":
            graphics.method_25294(x + 3, y + 3, x + size - 3, y + size - 4, color);
            graphics.method_25294(x + 5, y + 5, x + size - 5, y + size - 6, -14669269);
            graphics.method_25294(x + size / 2 - 1, y + 7, x + size / 2 + 1, y + size - 8, color);
            break;
         case "dragon":
            graphics.method_25294(x + 3, y + 3, x + size - 3, y + size - 3, -7453523);
            graphics.method_25294(x + size / 2 - 2, y + 4, x + size / 2 + 2, y + 8, -932849);
            break;
         case "rayo":
            graphics.method_25294(x + size / 2, y + 2, x + size - 4, y + size / 2, -932849);
            graphics.method_25294(x + 4, y + size / 2 - 1, x + size / 2 + 1, y + size - 2, -812014);
            break;
         case "estrella":
            graphics.method_25294(x + size / 2 - 1, y + 2, x + size / 2 + 1, y + size - 2, -932849);
            graphics.method_25294(x + 2, y + size / 2 - 1, x + size - 2, y + size / 2 + 1, -932849);
            break;
         case "corazon":
            graphics.method_25294(x + 3, y + 3, x + size / 2 - 1, y + size / 2, -1618884);
            graphics.method_25294(x + size / 2 + 1, y + 3, x + size - 3, y + size / 2, -1618884);
            graphics.method_25294(x + 4, y + size / 2, x + size - 4, y + size - 3, -4179669);
            break;
         default:
            graphics.method_25294(x + 2, y + 2, x + size - 2, y + size / 2, -2075821);
            graphics.method_25294(x + 2, y + size / 2, x + size - 2, y + size - 2, -986896);
            graphics.method_25294(x + 1, y + size / 2 - 1, x + size - 1, y + size / 2 + 1, -15197155);
            graphics.method_25294(x + size / 2 - 2, y + size / 2 - 2, x + size / 2 + 2, y + size / 2 + 2, -1);
            this.drawOutline(graphics, x + size / 2 - 2, y + size / 2 - 2, 4, 4, -15197155);
      }
   }

   private long countOnlineMembers(GuildSyncPayload data) {
      return this.parseMembers(data).stream().filter(m -> m.online).count();
   }

   private List<GuildScreen.MemberInfo> parseMembers(GuildSyncPayload data) {
      List<GuildScreen.MemberInfo> list = new ArrayList<>();

      for (String line : data.members()) {
         String[] parts = line.split("\\s*\\|\\s*");
         if (parts.length >= 2) {
            String name = parts[0];
            String role = parts[1];
            String status = parts.length > 2 ? parts[2] : "";
            String uuid = parts.length > 3 ? parts[3] : "";
            boolean online = status.toLowerCase().contains("en linea") || status.toLowerCase().contains("en línea");
            list.add(new GuildScreen.MemberInfo(name, role, status, uuid, online));
         }
      }

      return list;
   }

   private List<GuildScreen.MemberInfo> getFilteredMembers(GuildSyncPayload data) {
      List<GuildScreen.MemberInfo> all = this.parseMembers(data);
      return this.memberFilter.isBlank() ? all : all.stream().filter(m -> m.name.toLowerCase().contains(this.memberFilter)).toList();
   }

   private void drawSelectedColor(class_332 graphics, int x, int y, String hex) {
      graphics.method_25294(x, y, x + 20, y + 20, -1);
      graphics.method_25294(x + 2, y + 2, x + 18, y + 18, 0xFF000000 | GuildClientState.parseColor(hex.startsWith("#") ? hex : "#" + hex));
   }

   private float openingProgress() {
      return !this.animateOpening ? 1.0F : Math.min(1.0F, (float)(System.nanoTime() - this.openedAtNanos) / 2.8E8F);
   }

   public boolean method_25402(double mouseX, double mouseY, int button) {
      if (this.animateOpening && this.openingProgress() < 1.0F) {
         return true;
      } else {
         double localX = (mouseX - (double)this.renderX) / (double)this.uiScale;
         double localY = (mouseY - (double)this.renderY) / (double)this.uiScale;
         if (this.actionInput != null && this.actionInput.method_25370() && !this.playerSuggestions.isEmpty()) {
            int dropX = this.actionInput.method_46426();
            int dropY = this.actionInput.method_46427() + this.actionInput.method_25364() + 2;
            int dropW = this.actionInput.method_25368();
            int itemH = 16;
            int dropH = this.playerSuggestions.size() * itemH + 4;
            if (localX >= (double)dropX && localX < (double)(dropX + dropW) && localY >= (double)dropY && localY < (double)(dropY + dropH)) {
               int clickedIdx = (int)((localY - (double)dropY - 2.0) / (double)itemH);
               if (clickedIdx >= 0 && clickedIdx < this.playerSuggestions.size()) {
                  this.actionInput.method_1852(this.playerSuggestions.get(clickedIdx));
                  this.draftAction = this.actionInput.method_1882();
                  this.playerSuggestions.clear();
                  GuildUiSound.play();
                  return true;
               }
            }
         }

         return this.handleMouseClicked(localX, localY, button);
      }
   }

   private boolean handleMouseClicked(double mouseX, double mouseY, int button) {
      if (this.tab == 3 && this.handleVaultMouseClicked(mouseX, mouseY, button)) {
         return true;
      } else if (this.tab == 2) {
         if (button == 1 && this.heldPokemon != null) {
            this.heldPokemon = null;
            this.playPcDropSound();
            return true;
         } else {
            int slotSize = 32;
            int cols = 6;
            int rows = 5;
            int gap = 14;
            int totalW = cols * slotSize + gap + slotSize + gap + cols * slotSize;
            int startX = this.contentX + Math.max(6, (this.contentWidth - totalW) / 2);
            int gridX = startX;
            int partyX = startX + cols * slotSize + gap;
            int privX = partyX + slotSize + gap;
            int gridY = this.panelY + 82;
            int partyY = gridY;
            ClientParty party = this.getClientParty();
            ClientPC clientPc = this.getClientPC();
            ClientBox clientBox = clientPc != null && privatePcBox < clientPc.getBoxes().size() ? (ClientBox)clientPc.getBoxes().get(privatePcBox) : null;
            List<GuildScreen.PokemonSlotPreview> allPreviews = this.parsePreviews(GuildClientState.data);
            Map<Integer, GuildScreen.PokemonSlotPreview> currentBoxSlots = new HashMap<>();

            for (GuildScreen.PokemonSlotPreview p : allPreviews) {
               if (p.box() == this.selectedPcBox) {
                  currentBoxSlots.put(p.slot(), p);
               }
            }

            int partyOccupied = 0;
            if (party != null) {
               for (int i = 0; i < 6; i++) {
                  if (party.get(i) != null) {
                     partyOccupied++;
                  }
               }
            }

            boolean shift = method_25442();

            for (int r = 0; r < rows; r++) {
               for (int c = 0; c < cols; c++) {
                  int s = r * cols + c;
                  int sx = gridX + c * slotSize;
                  int sy = gridY + r * slotSize;
                  if (mouseX >= (double)sx && mouseX < (double)(sx + slotSize - 2) && mouseY >= (double)sy && mouseY < (double)(sy + slotSize - 2)) {
                     GuildScreen.PokemonSlotPreview preview = currentBoxSlots.get(s);
                     if (!shift) {
                        if (this.heldPokemon == null) {
                           if (preview != null) {
                              this.heldPokemon = new GuildScreen.HeldPokemon(
                                 "GUILD",
                                 this.selectedPcBox,
                                 s,
                                 preview.species(),
                                 preview.level(),
                                 preview.shiny(),
                                 preview.form(),
                                 preview.displayName(),
                                 DesgraciamonGuilds.isRestrictedSpecies(preview.species())
                              );
                              this.playPcGrabSound();
                           }
                        } else {
                           if ("GUILD".equals(this.heldPokemon.container()) && this.heldPokemon.box() == this.selectedPcBox && this.heldPokemon.slot() == s) {
                              this.heldPokemon = null;
                              this.playPcDropSound();
                              return true;
                           }

                           if (this.heldPokemon.restricted()) {
                              this.warningBanner = "¡No puedes almacenar Pokémon Legendarios ni Míticos en el Gremio!";
                              this.warningBannerTicks = 70;
                              this.playDenySound();
                              return true;
                           }

                           if ("PARTY".equals(this.heldPokemon.container()) && preview == null && partyOccupied <= 1) {
                              this.warningBanner = "¡Debes mantener al menos 1 Pokémon en tu equipo!";
                              this.warningBannerTicks = 70;
                              this.playDenySound();
                              return true;
                           }

                           send(
                              "TRANSFER_POKEMON",
                              this.heldPokemon.container()
                                 + "|"
                                 + this.heldPokemon.box()
                                 + "|"
                                 + this.heldPokemon.slot()
                                 + "|GUILD|"
                                 + this.selectedPcBox
                                 + "|"
                                 + s,
                              0
                           );
                           this.playPcDropSound();
                           this.heldPokemon = null;
                        }

                        return true;
                     }

                     if (preview != null) {
                        int emptyParty = -1;
                        if (party != null) {
                           for (int ix = 0; ix < 6; ix++) {
                              if (party.get(ix) == null) {
                                 emptyParty = ix;
                                 break;
                              }
                           }
                        }

                        if (emptyParty != -1) {
                           send("TRANSFER_POKEMON", "GUILD|" + this.selectedPcBox + "|" + s + "|PARTY|0|" + emptyParty, 0);
                           this.playPcDropSound();
                           return true;
                        }

                        if (clientBox != null) {
                           int emptyPc = -1;

                           for (int slotIdx = 0; slotIdx < 30; slotIdx++) {
                              if (clientBox.getSlots().get(slotIdx) == null) {
                                 emptyPc = slotIdx;
                                 break;
                              }
                           }

                           if (emptyPc != -1) {
                              send("TRANSFER_POKEMON", "GUILD|" + this.selectedPcBox + "|" + s + "|PC|" + privatePcBox + "|" + emptyPc, 0);
                              this.playPcDropSound();
                              return true;
                           }
                        }
                     }

                     return true;
                  }
               }
            }

            for (int ixx = 0; ixx < 6; ixx++) {
               int py = partyY + ixx * slotSize;
               if (mouseX >= (double)partyX && mouseX < (double)(partyX + slotSize - 2) && mouseY >= (double)py && mouseY < (double)(py + slotSize - 2)) {
                  Pokemon px = party != null ? party.get(ixx) : null;
                  if (!shift) {
                     if (this.heldPokemon == null) {
                        if (px != null) {
                           this.heldPokemon = new GuildScreen.HeldPokemon(
                              "PARTY",
                              0,
                              ixx,
                              px.getSpecies().getName().toLowerCase(Locale.ROOT),
                              px.getLevel(),
                              px.getShiny(),
                              px.getForm().getName().toLowerCase(Locale.ROOT),
                              px.getDisplayName(false).getString(),
                              DesgraciamonGuilds.isRestrictedPokemon(px)
                           );
                           this.playPcGrabSound();
                        }
                     } else {
                        if ("PARTY".equals(this.heldPokemon.container()) && this.heldPokemon.slot() == ixx) {
                           this.heldPokemon = null;
                           this.playPcDropSound();
                           return true;
                        }

                        send(
                           "TRANSFER_POKEMON",
                           this.heldPokemon.container() + "|" + this.heldPokemon.box() + "|" + this.heldPokemon.slot() + "|PARTY|0|" + ixx,
                           0
                        );
                        this.playPcDropSound();
                        this.heldPokemon = null;
                     }

                     return true;
                  }

                  if (px != null) {
                     if (partyOccupied <= 1) {
                        this.warningBanner = "¡Debes mantener al menos 1 Pokémon en tu equipo!";
                        this.warningBannerTicks = 70;
                        this.playDenySound();
                        return true;
                     }

                     if (clientBox != null) {
                        int emptyPc = -1;

                        for (int slotIdxx = 0; slotIdxx < 30; slotIdxx++) {
                           if (clientBox.getSlots().get(slotIdxx) == null) {
                              emptyPc = slotIdxx;
                              break;
                           }
                        }

                        if (emptyPc != -1) {
                           send("TRANSFER_POKEMON", "PARTY|0|" + ixx + "|PC|" + privatePcBox + "|" + emptyPc, 0);
                           this.playPcDropSound();
                           return true;
                        }
                     }

                     if (DesgraciamonGuilds.isRestrictedPokemon(px)) {
                        this.warningBanner = "¡No se permiten Legendarios en el Gremio y tu PC está llena!";
                        this.warningBannerTicks = 70;
                        this.playDenySound();
                        return true;
                     }

                     int emptyGuild = -1;

                     for (int s = 0; s < 30; s++) {
                        if (!currentBoxSlots.containsKey(s)) {
                           emptyGuild = s;
                           break;
                        }
                     }

                     if (emptyGuild != -1) {
                        send("TRANSFER_POKEMON", "PARTY|0|" + ixx + "|GUILD|" + this.selectedPcBox + "|" + emptyGuild, 0);
                        this.playPcDropSound();
                        return true;
                     }
                  }

                  return true;
               }
            }

            if (clientBox != null) {
               for (int r = 0; r < rows; r++) {
                  for (int cx = 0; cx < cols; cx++) {
                     int sx = r * cols + cx;
                     int sxx = privX + cx * slotSize;
                     int sy = gridY + r * slotSize;
                     if (mouseX >= (double)sxx && mouseX < (double)(sxx + slotSize - 2) && mouseY >= (double)sy && mouseY < (double)(sy + slotSize - 2)) {
                        Pokemon pxx = sx < clientBox.getSlots().size() ? (Pokemon)clientBox.getSlots().get(sx) : null;
                        if (!shift) {
                           if (this.heldPokemon == null) {
                              if (pxx != null) {
                                 this.heldPokemon = new GuildScreen.HeldPokemon(
                                    "PC",
                                    privatePcBox,
                                    sx,
                                    pxx.getSpecies().getName().toLowerCase(Locale.ROOT),
                                    pxx.getLevel(),
                                    pxx.getShiny(),
                                    pxx.getForm().getName().toLowerCase(Locale.ROOT),
                                    pxx.getDisplayName(false).getString(),
                                    DesgraciamonGuilds.isRestrictedPokemon(pxx)
                                 );
                                 this.playPcGrabSound();
                              }
                           } else {
                              if ("PC".equals(this.heldPokemon.container()) && this.heldPokemon.box() == privatePcBox && this.heldPokemon.slot() == sx) {
                                 this.heldPokemon = null;
                                 this.playPcDropSound();
                                 return true;
                              }

                              if ("PARTY".equals(this.heldPokemon.container()) && pxx == null && partyOccupied <= 1) {
                                 this.warningBanner = "¡Debes mantener al menos 1 Pokémon en tu equipo!";
                                 this.warningBannerTicks = 70;
                                 this.playDenySound();
                                 return true;
                              }

                              send(
                                 "TRANSFER_POKEMON",
                                 this.heldPokemon.container() + "|" + this.heldPokemon.box() + "|" + this.heldPokemon.slot() + "|PC|" + privatePcBox + "|" + sx,
                                 0
                              );
                              this.playPcDropSound();
                              this.heldPokemon = null;
                           }

                           return true;
                        }

                        if (pxx != null) {
                           int emptyPartyx = -1;
                           if (party != null) {
                              for (int ixxx = 0; ixxx < 6; ixxx++) {
                                 if (party.get(ixxx) == null) {
                                    emptyPartyx = ixxx;
                                    break;
                                 }
                              }
                           }

                           if (emptyPartyx != -1) {
                              send("TRANSFER_POKEMON", "PC|" + privatePcBox + "|" + sx + "|PARTY|0|" + emptyPartyx, 0);
                              this.playPcDropSound();
                              return true;
                           }

                           if (DesgraciamonGuilds.isRestrictedPokemon(pxx)) {
                              this.warningBanner = "¡Equipo lleno y no se permiten Legendarios en el Gremio!";
                              this.warningBannerTicks = 70;
                              this.playDenySound();
                              return true;
                           }

                           int emptyGuild = -1;

                           for (int slotIdxxx = 0; slotIdxxx < 30; slotIdxxx++) {
                              if (!currentBoxSlots.containsKey(slotIdxxx)) {
                                 emptyGuild = slotIdxxx;
                                 break;
                              }
                           }

                           if (emptyGuild != -1) {
                              send("TRANSFER_POKEMON", "PC|" + privatePcBox + "|" + sx + "|GUILD|" + this.selectedPcBox + "|" + emptyGuild, 0);
                              this.playPcDropSound();
                              return true;
                           }
                        }

                        return true;
                     }
                  }
               }
            }

            boolean clickedWidget = super.method_25402(mouseX, mouseY, button);
            if (!clickedWidget && this.heldPokemon != null) {
               this.heldPokemon = null;
               this.playPcDropSound();
               return true;
            } else {
               return clickedWidget;
            }
         }
      } else {
         return super.method_25402(mouseX, mouseY, button);
      }
   }

   public boolean method_25404(int keyCode, int scanCode, int modifiers) {
      if (this.chatInput == null || !this.chatInput.method_25370() || keyCode != 257 && keyCode != 335) {
         if (this.actionInput != null && this.actionInput.method_25370() && keyCode == 258 && !this.playerSuggestions.isEmpty()) {
            this.actionInput.method_1852(this.playerSuggestions.get(0));
            this.draftAction = this.actionInput.method_1882();
            this.playerSuggestions.clear();
            return true;
         } else {
            if (keyCode == 256) {
               if (this.heldPokemon != null) {
                  this.heldPokemon = null;
                  this.playPcDropSound();
                  return true;
               }

               if (this.heldVaultItem != null) {
                  this.heldVaultItem = null;
                  this.playVaultSound();
                  return true;
               }
            }

            return super.method_25404(keyCode, scanCode, modifiers);
         }
      } else {
         this.sendChatMessage();
         return true;
      }
   }

   public boolean method_25406(double mouseX, double mouseY, int button) {
      double localX = (mouseX - (double)this.renderX) / (double)this.uiScale;
      double localY = (mouseY - (double)this.renderY) / (double)this.uiScale;
      return super.method_25406(localX, localY, button);
   }

   public boolean method_25403(double mouseX, double mouseY, int button, double dragX, double dragY) {
      double localX = (mouseX - (double)this.renderX) / (double)this.uiScale;
      double localY = (mouseY - (double)this.renderY) / (double)this.uiScale;
      return super.method_25403(localX, localY, button, dragX / (double)this.uiScale, dragY / (double)this.uiScale);
   }

   public boolean method_25401(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
      double localX = (mouseX - (double)this.renderX) / (double)this.uiScale;
      double localY = (mouseY - (double)this.renderY) / (double)this.uiScale;
      return this.handleMouseScrolled(localX, localY, horizontalAmount, verticalAmount);
   }

   private boolean handleMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
      if (this.tab == 0) {
         int motdW = Math.max(130, (this.contentWidth - 8) * 36 / 100);
         int chatX = this.contentX + motdW + 8;
         int chatW = this.contentWidth - motdW - 8;
         int motdY = this.panelY + 46 + 56 + 8 + 46 + 8;
         int chatH = this.panelY + this.panelHeight - motdY - 10;
         if (mouseX >= (double)chatX && mouseX <= (double)(chatX + chatW) && mouseY >= (double)motdY && mouseY <= (double)(motdY + chatH)) {
            this.chatScrollOffset = Math.max(0, this.chatScrollOffset + (verticalAmount > 0.0 ? 1 : -1));
            return true;
         }
      }

      if (this.tab == 1) {
         this.memberScrollOffset = Math.max(0, this.memberScrollOffset + (verticalAmount < 0.0 ? 1 : -1));
         this.method_41843();
         return true;
      } else {
         if (this.tab == 2) {
            ClientPC pc = this.getClientPC();
            if (pc != null && pc.getBoxes().size() > 0) {
               int total = pc.getBoxes().size();
               privatePcBox = (privatePcBox + (verticalAmount < 0.0 ? 1 : -1) + total) % total;
               this.method_41843();
               return true;
            }
         } else {
            if (this.tab == 4) {
               if (mouseX < (double)this.contentX + (double)this.contentWidth / 2.0) {
                  this.localWaypointScroll = Math.max(0, this.localWaypointScroll + (verticalAmount < 0.0 ? 1 : -1));
               } else {
                  this.sharedWaypointScroll = Math.max(0, this.sharedWaypointScroll + (verticalAmount < 0.0 ? 1 : -1));
               }

               this.method_41843();
               return true;
            }

            if (this.tab == 5) {
               this.logScrollOffset = Math.max(0, this.logScrollOffset + (verticalAmount < 0.0 ? 1 : -1));
               return true;
            }
         }

         return super.method_25401(mouseX, mouseY, horizontalAmount, verticalAmount);
      }
   }

   public void method_57734(float delta) {
   }

   public void method_57735(class_332 graphics) {
   }

   public boolean method_25421() {
      return false;
   }

   private void uploadSelectedWaypoint() {
      List<XaeroWaypointBridge.LocalWaypoint> filtered = this.filterLocalWaypoints(this.localWaypoints, this.waypointDimFilter);
      if (this.selectedLocalWaypoint >= 0 && this.selectedLocalWaypoint < filtered.size()) {
         XaeroWaypointBridge.LocalWaypoint waypoint = filtered.get(this.selectedLocalWaypoint);
         this.pendingUpload = new GuildScreen.PendingWaypoint(
            waypoint.name(), waypoint.dimension(), waypoint.x(), waypoint.y(), waypoint.z(), System.currentTimeMillis()
         );
         this.selectedGuildWaypoint = "pending";
         this.selectedLocalWaypoint = -1;
         GuildUiSound.play();
         this.method_41843();
         ClientPlayNetworking.send(new GuildWaypointImportPayload(waypoint.name(), waypoint.dimension(), waypoint.x(), waypoint.y(), waypoint.z(), true));
      }
   }

   void onSyncUpdated() {
      if (this.field_22787 != null && this.field_22787.field_1755 == this) {
         if (this.tab == 6) {
            return;
         }

         if (this.method_25399() instanceof class_342) {
            return;
         }

         this.method_41843();
      }
   }

   private static boolean matchesPending(String encoded, GuildScreen.PendingWaypoint pending) {
      String[] parts = encoded.split("\\|", 9);
      return parts.length == 9
         && parts[1].equals("true")
         && decode(parts[2]).equalsIgnoreCase(pending.name())
         && parts[3].equals(pending.dimension())
         && parts[4].equals(Integer.toString(pending.x()))
         && parts[5].equals(Integer.toString(pending.y()))
         && parts[6].equals(Integer.toString(pending.z()));
   }

   private void copySelectedGuildWaypoint() {
      for (String encoded : GuildClientState.data.waypoints()) {
         String[] parts = encoded.split("\\|", 9);
         if (parts.length == 9 && parts[0].equals(this.selectedGuildWaypoint) && parts[1].equals("true")) {
            boolean added = XaeroWaypointBridge.addLocalWaypoint(
               decode(parts[2]),
               parts[3],
               Integer.parseInt(parts[4]),
               Integer.parseInt(parts[5]),
               Integer.parseInt(parts[6]),
               GuildClientState.parseColor(GuildClientState.data.color())
            );
            if (!added && this.field_22787.field_1724 != null) {
               this.field_22787.field_1724.method_43496(class_2561.method_43470("No se pudo agregar a los waypoints personales de Xaero."));
            }

            return;
         }
      }
   }

   private static String decode(String value) {
      try {
         return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
      } catch (IllegalArgumentException var2) {
         return value;
      }
   }

   private static String uploadTime(String timestamp) {
      try {
         long uploadedAt = Long.parseLong(timestamp);
         return uploadedAt <= 0L
            ? "fecha anterior"
            : DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(uploadedAt));
      } catch (RuntimeException var3) {
         return "fecha desconocida";
      }
   }

   private class_342 addInput(int x, int y, int boxWidth, int boxHeight, String hint, int maxLength, String value) {
      class_342 box = new class_342(this.field_22793, x, y, boxWidth, boxHeight, class_2561.method_43470(hint));
      box.method_1880(maxLength);
      box.method_47404(class_2561.method_43470(hint));
      box.method_1852(value == null ? "" : value);
      this.method_37063(box);
      return box;
   }

   private void addAction(String label, int x, int y, int buttonWidth, int buttonHeight, Runnable action, boolean accent) {
      this.method_37063(new GuildScreen.ActionButton(label, x, y, buttonWidth, buttonHeight, action, accent));
   }

   private void createGuild() {
      this.rememberDrafts();
      String name = this.nameInput == null ? "" : GuildCommands.normalizeGuildName(this.nameInput.method_1882());
      String color = this.draftColor != null && !this.draftColor.isBlank() ? this.draftColor.replace("#", "").trim() : "5BC0A7";
      if (name == null) {
         if (this.field_22787.field_1724 != null) {
            this.field_22787
               .field_1724
               .method_43496(class_2561.method_43470("Usa un nombre de 2 a 24 caracteres (letras, números, espacios o guiones bajos)."));
         }
      } else {
         this.closeAfterCreate = true;
         send("CREATE", name + "|" + color, 0);
      }
   }

   private void saveGuildDetails() {
      this.rememberDrafts();
      if (this.nameInput != null && !this.nameInput.method_1882().trim().isEmpty()) {
         String normalized = GuildCommands.normalizeGuildName(this.nameInput.method_1882());
         if (normalized == null) {
            if (this.field_22787.field_1724 != null) {
               this.field_22787
                  .field_1724
                  .method_43496(class_2561.method_43470("Usa un nombre de 2 a 24 caracteres (letras, números, espacios o guiones bajos)."));
            }

            return;
         }

         send("RENAME", normalized, 0);
      }

      if (this.draftColor != null && this.draftColor.matches("#?[0-9a-fA-F]{6}")) {
         send("COLOR", this.draftColor.replace("#", "").trim(), 0);
      }

      if (this.draftIcon != null && !this.draftIcon.isBlank()) {
         send("ICON", this.draftIcon, 0);
      }

      if (this.field_22787.field_1724 != null) {
         this.field_22787.field_1724.method_43496(class_2561.method_43470("§a[Gremio] ¡Personalización actualizada!"));
      }
   }

   private void rememberDrafts() {
      if (this.nameInput != null) {
         this.draftName = this.nameInput.method_1882();
      }

      if (this.colorInput != null) {
         this.draftColor = this.colorInput.method_1882();
      }

      if (this.actionInput != null) {
         this.draftAction = this.actionInput.method_1882();
      }

      if (this.amountInput != null) {
         this.draftAmount = this.amountInput.method_1882();
      }

      if (this.searchMemberInput != null) {
         this.memberFilter = this.searchMemberInput.method_1882();
      }

      if (this.motdEditInput != null) {
         this.draftMotd = this.motdEditInput.method_1882();
      }
   }

   private static String value(class_342 input) {
      return input == null ? "" : input.method_1882().trim();
   }

   private void sendAmount(String action) {
      try {
         BigInteger amount = new BigInteger(value(this.amountInput));
         if (amount.signum() > 0 && amount.toString().length() <= 100) {
            send(action, "", amount);
         }
      } catch (NumberFormatException var3) {
      }
   }

   private static void send(String action, String argument, int amount) {
      send(action, argument, BigInteger.valueOf((long)amount));
   }

   private static void send(String action, String argument, BigInteger amount) {
      ClientPlayNetworking.send(new GuildRequestPayload(action, argument, amount));
   }

   private String fit(String text, int maxWidth) {
      if (this.field_22793.method_1727(text) <= maxWidth) {
         return text;
      } else {
         String shortened = text;

         while (!shortened.isEmpty() && this.field_22793.method_1727(shortened + "...") > maxWidth) {
            shortened = shortened.substring(0, shortened.length() - 1);
         }

         return shortened + "...";
      }
   }

   private static String roleLabel(String role) {
      return switch (role) {
         case "LEADER" -> "Líder";
         case "OFFICER" -> "Oficial";
         case "MEMBER" -> "Miembro";
         default -> role;
      };
   }

   private static class_2960 icon(int type) {
      String[] names = new String[]{"general", "members", "pc", "vault", "waypoints", "log", "customize"};
      return class_2960.method_60655("desgraciamon_guilds", "textures/gui/icons/" + names[type] + ".png");
   }

   private static void drawOriginalIcon(
      class_332 graphics, class_2960 texture, int x, int y, int size, int sourceX, int sourceY, int sourceWidth, int sourceHeight
   ) {
      float scale = Math.min((float)size / (float)sourceWidth, (float)size / (float)sourceHeight);
      int drawWidth = Math.round((float)sourceWidth * scale);
      int drawHeight = Math.round((float)sourceHeight * scale);
      int drawX = x + (size - drawWidth) / 2;
      int drawY = y + (size - drawHeight) / 2;
      graphics.method_25293(texture, drawX, drawY, drawWidth, drawHeight, (float)sourceX, (float)sourceY, sourceWidth, sourceHeight, 512, 512);
   }

   private static int accentColor() {
      return !GuildClientState.data.member() ? -10764121 : 0xFF000000 | GuildClientState.parseColor(GuildClientState.data.color());
   }

   private static int accentSurface() {
      int accent = accentColor();
      int red = (accent >> 16 & 0xFF) * 2 / 5 + 15;
      int green = (accent >> 8 & 0xFF) * 2 / 5 + 21;
      int blue = (accent & 0xFF) * 2 / 5 + 21;
      return 0xFF000000 | red << 16 | green << 8 | blue;
   }

   private final class ActionButton extends class_339 {
      private final Runnable action;
      private final boolean accent;

      private ActionButton(String label, int x, int y, int buttonWidth, int buttonHeight, Runnable action, boolean accent) {
         super(x, y, buttonWidth, buttonHeight, class_2561.method_43470(label));
         this.action = action;
         this.accent = accent;
      }

      protected void method_48579(class_332 graphics, int mouseX, int mouseY, float delta) {
         int background = this.accent
            ? (this.method_49606() ? GuildScreen.accentColor() : GuildScreen.accentSurface())
            : (this.method_49606() ? -13352124 : -14405583);
         int border = this.accent ? GuildScreen.accentColor() : (this.method_49606() ? -10849681 : -12956852);
         graphics.method_25294(
            this.method_46426(), this.method_46427(), this.method_46426() + this.field_22758, this.method_46427() + this.field_22759, background
         );
         GuildScreen.this.drawOutline(graphics, this.method_46426(), this.method_46427(), this.field_22758, this.field_22759, border);
         String label = this.method_25369().getString();
         int textColor = this.field_22763 ? -1379602 : -7891569;
         graphics.method_51433(
            GuildScreen.this.field_22793,
            label,
            this.method_46426() + (this.field_22758 - GuildScreen.this.field_22793.method_1727(label)) / 2,
            this.method_46427() + (this.field_22759 - 8) / 2,
            textColor,
            false
         );
      }

      public void method_25354(class_1144 soundManager) {
      }

      public void method_25348(double mouseX, double mouseY) {
         if (this.field_22763) {
            GuildUiSound.play();
            this.action.run();
         }
      }

      protected void method_47399(class_6382 narration) {
         narration.method_37034(class_6381.field_33788, this.method_25369());
      }
   }

   private final class HeaderIconButton extends class_339 {
      private final Runnable action;
      private final int hoverColor;

      private HeaderIconButton(int x, int y, int width, int height, String label, int hoverColor, Runnable action, String tooltip) {
         super(x, y, width, height, class_2561.method_43470(label));
         this.action = action;
         this.hoverColor = hoverColor;
         this.method_47400(class_7919.method_47407(class_2561.method_43470(tooltip)));
      }

      protected void method_48579(class_332 graphics, int mouseX, int mouseY, float delta) {
         int bg = this.method_49606() ? this.hoverColor : -14340046;
         graphics.method_25294(this.method_46426(), this.method_46427(), this.method_46426() + this.field_22758, this.method_46427() + this.field_22759, bg);
         GuildScreen.this.drawOutline(
            graphics, this.method_46426(), this.method_46427(), this.field_22758, this.field_22759, this.method_49606() ? -1 : -12693679
         );
         graphics.method_51439(
            GuildScreen.this.field_22793,
            this.method_25369(),
            this.method_46426() + (this.field_22758 - GuildScreen.this.field_22793.method_27525(this.method_25369())) / 2,
            this.method_46427() + (this.field_22759 - 8) / 2,
            -1,
            false
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

   static record HeldPokemon(String container, int box, int slot, String species, int level, boolean shiny, String form, String displayName, boolean restricted) {
   }

   static class HeldVaultItem {
      final String container;
      final int slot;
      class_1799 stack;
      int remainingInSource;

      HeldVaultItem(String container, int slot, class_1799 stack, int remainingInSource) {
         this.container = container;
         this.slot = slot;
         this.stack = stack;
         this.remainingInSource = remainingInSource;
      }
   }

   private final class HueBarWidget extends class_339 {
      private final Consumer<String> onColorSelected;
      private boolean dragging = false;

      private HueBarWidget(int x, int y, int width, int height, Consumer<String> onColorSelected) {
         super(x, y, width, height, class_2561.method_43470("Selector de Color"));
         this.onColorSelected = onColorSelected;
         this.method_47400(class_7919.method_47407(class_2561.method_43470("Selector de Color Visual")));
      }

      protected void method_48579(class_332 graphics, int mouseX, int mouseY, float delta) {
         for (int i = 0; i < this.field_22758; i++) {
            float hue = (float)i / (float)this.field_22758;
            int rgb = Color.HSBtoRGB(hue, 0.85F, 0.95F) & 16777215;
            graphics.method_25294(
               this.method_46426() + i, this.method_46427(), this.method_46426() + i + 1, this.method_46427() + this.field_22759, 0xFF000000 | rgb
            );
         }

         GuildScreen.this.drawOutline(
            graphics, this.method_46426() - 1, this.method_46427() - 1, this.field_22758 + 2, this.field_22759 + 2, this.method_49606() ? -1 : -13286330
         );

         try {
            int c = Integer.parseInt(GuildScreen.this.draftColor.replace("#", ""), 16);
            float[] hsb = Color.RGBtoHSB(c >> 16 & 0xFF, c >> 8 & 0xFF, c & 0xFF, null);
            int indX = this.method_46426() + (int)(hsb[0] * (float)(this.field_22758 - 1));
            if (indX >= this.method_46426() && indX <= this.method_46426() + this.field_22758) {
               graphics.method_25294(indX - 1, this.method_46427() - 3, indX + 2, this.method_46427() + this.field_22759 + 3, -1);
               graphics.method_25294(indX, this.method_46427() - 2, indX + 1, this.method_46427() + this.field_22759 + 2, -15722471);
            }
         } catch (Exception var8) {
         }
      }

      public boolean method_25402(double mouseX, double mouseY, int button) {
         if (this.field_22763 && this.field_22764 && this.method_25351(button) && this.method_25361(mouseX, mouseY)) {
            this.dragging = true;
            GuildUiSound.play();
            this.pickHue(mouseX);
            return true;
         } else {
            return false;
         }
      }

      public boolean method_25406(double mouseX, double mouseY, int button) {
         this.dragging = false;
         return super.method_25406(mouseX, mouseY, button);
      }

      public boolean method_25403(double mouseX, double mouseY, int button, double dragX, double dragY) {
         if (!this.dragging && !this.method_25361(mouseX, mouseY)) {
            return false;
         } else {
            this.pickHue(mouseX);
            return true;
         }
      }

      private void pickHue(double mouseX) {
         float rel = (float)(mouseX - (double)this.method_46426()) / (float)this.field_22758;
         rel = Math.max(0.0F, Math.min(1.0F, rel));
         int rgb = Color.HSBtoRGB(rel, 0.85F, 0.95F) & 16777215;
         String hex = String.format("%06X", rgb);
         GuildScreen.this.draftColor = hex;
         this.onColorSelected.accept(hex);
      }

      protected void method_47399(class_6382 narration) {
         narration.method_37034(class_6381.field_33788, this.method_25369());
      }
   }

   private final class IconTabButton extends class_339 {
      private final int icon;
      private final boolean selected;
      private final Runnable action;

      private IconTabButton(int x, int y, int icon, boolean selected, Runnable action) {
         super(x, y, 36, 36, class_2561.method_43470(GuildScreen.TABS[icon]));
         this.icon = icon;
         this.selected = selected;
         this.action = action;
         this.method_47400(class_7919.method_47407(class_2561.method_43470(GuildScreen.TABS[icon])));
      }

      protected void method_48579(class_332 graphics, int mouseX, int mouseY, float delta) {
         int background = this.selected ? GuildScreen.accentSurface() : (this.method_49606() ? -14010569 : -14932697);
         graphics.method_25294(
            this.method_46426(), this.method_46427(), this.method_46426() + this.field_22758, this.method_46427() + this.field_22759, background
         );
         int border = this.selected ? GuildScreen.accentColor() : (this.method_49606() ? -10980501 : -13878981);
         GuildScreen.this.drawOutline(graphics, this.method_46426(), this.method_46427(), this.field_22758, this.field_22759, border);
         int iconSize = Math.round((float)Math.min(this.field_22758, this.field_22759) * 0.68F);
         int iconX = this.method_46426() + (this.field_22758 - iconSize) / 2;
         int iconY = this.method_46427() + (this.field_22759 - iconSize) / 2;
         graphics.method_25293(GuildScreen.icon(this.icon), iconX, iconY, iconSize, iconSize, 0.0F, 0.0F, 64, 64, 64, 64);
      }

      public void method_25354(class_1144 soundManager) {
      }

      public void method_25348(double mouseX, double mouseY) {
         GuildUiSound.play();
         this.action.run();
      }

      protected void method_47399(class_6382 narration) {
         narration.method_37034(class_6381.field_33788, class_2561.method_43470(GuildScreen.TABS[this.icon]));
      }
   }

   private final class IconTileButton extends class_339 {
      private final String iconKey;
      private final Runnable onSelect;

      private IconTileButton(int x, int y, int size, String iconKey, Runnable onSelect) {
         super(x, y, size, size, class_2561.method_43470(iconKey));
         this.iconKey = iconKey;
         this.onSelect = onSelect;
         String label = iconKey.substring(0, 1).toUpperCase() + iconKey.substring(1);
         this.method_47400(class_7919.method_47407(class_2561.method_43470("Emblema: " + label)));
      }

      protected void method_48579(class_332 graphics, int mouseX, int mouseY, float delta) {
         boolean selected = this.iconKey.equalsIgnoreCase(GuildScreen.this.draftIcon);
         int bgColor = selected ? -14009028 : (this.method_49606() ? -14536911 : -15327198);
         graphics.method_25294(
            this.method_46426(), this.method_46427(), this.method_46426() + this.field_22758, this.method_46427() + this.field_22759, bgColor
         );
         int borderColor = selected ? GuildScreen.accentColor() : (this.method_49606() ? -9795966 : -13944517);
         GuildScreen.this.drawOutline(graphics, this.method_46426(), this.method_46427(), this.field_22758, this.field_22759, borderColor);
         int iconColor = selected ? GuildScreen.accentColor() : (this.method_49606() ? -1642262 : -7363682);
         int iconSize = this.field_22758 - 6;
         GuildScreen.this.drawGuildIcon(graphics, this.iconKey, this.method_46426() + 3, this.method_46427() + 3, iconSize, iconColor);
      }

      public void method_25354(class_1144 soundManager) {
      }

      public void method_25348(double mouseX, double mouseY) {
         GuildUiSound.play();
         GuildScreen.this.draftIcon = this.iconKey;
         this.onSelect.run();
      }

      protected void method_47399(class_6382 narration) {
         narration.method_37034(class_6381.field_33788, this.method_25369());
      }
   }

   private static record MemberInfo(String name, String role, String statusDetail, String uuid, boolean online) {
   }

   static record ParsedLogEntry(String time, String author, String actionText, int color) {
   }

   private static record PendingWaypoint(String name, String dimension, int x, int y, int z, long createdAtMillis) {
   }

   static record PokemonSlotPreview(
      int box, int slot, String species, int level, boolean shiny, String displayName, String form, String depositor, String ivs, String evs, String moves
   ) {
   }

   private final class QuickPillButton extends class_339 {
      private final Runnable action;

      private QuickPillButton(int x, int y, int width, int height, String label, Runnable action) {
         super(x, y, width, height, class_2561.method_43470(label));
         this.action = action;
      }

      protected void method_48579(class_332 graphics, int mouseX, int mouseY, float delta) {
         int bg = this.method_49606() ? -12759215 : -14537426;
         graphics.method_25294(this.method_46426(), this.method_46427(), this.method_46426() + this.field_22758, this.method_46427() + this.field_22759, bg);
         GuildScreen.this.drawOutline(
            graphics, this.method_46426(), this.method_46427(), this.field_22758, this.field_22759, this.method_49606() ? GuildScreen.accentColor() : -13286330
         );
         graphics.method_51439(
            GuildScreen.this.field_22793,
            this.method_25369(),
            this.method_46426() + (this.field_22758 - GuildScreen.this.field_22793.method_27525(this.method_25369())) / 2,
            this.method_46427() + (this.field_22759 - 8) / 2,
            -1905690,
            false
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

   private final class RowActionButton extends class_339 {
      private final int btnColor;
      private final Runnable action;

      private RowActionButton(int x, int y, int width, int height, String label, int btnColor, Runnable action, String tooltipText) {
         super(x, y, width, height, class_2561.method_43470(label));
         this.btnColor = btnColor;
         this.action = action;
         if (tooltipText != null && !tooltipText.isBlank()) {
            this.method_47400(class_7919.method_47407(class_2561.method_43470(tooltipText)));
         }
      }

      protected void method_48579(class_332 graphics, int mouseX, int mouseY, float delta) {
         int bg = this.method_49606() ? this.btnColor : -14537168;
         graphics.method_25294(this.method_46426(), this.method_46427(), this.method_46426() + this.field_22758, this.method_46427() + this.field_22759, bg);
         GuildScreen.this.drawOutline(
            graphics, this.method_46426(), this.method_46427(), this.field_22758, this.field_22759, this.method_49606() ? -1 : this.btnColor
         );
         int textCol = this.method_49606() ? -1 : this.btnColor;
         graphics.method_27534(
            GuildScreen.this.field_22793,
            this.method_25369(),
            this.method_46426() + this.field_22758 / 2,
            this.method_46427() + (this.field_22759 - 8) / 2,
            textCol
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

   private final class WaypointSelectButton extends class_339 {
      private final String detail;
      private final boolean selected;
      private final Runnable action;

      private WaypointSelectButton(int x, int y, int buttonWidth, int buttonHeight, String name, String detail, boolean selected, Runnable action) {
         super(x, y, buttonWidth, buttonHeight, class_2561.method_43470(name));
         this.detail = detail;
         this.selected = selected;
         this.action = action;
         this.method_47400(class_7919.method_47407(class_2561.method_43470(name + "\n" + detail)));
      }

      protected void method_48579(class_332 graphics, int mouseX, int mouseY, float delta) {
         int background = this.selected ? GuildScreen.accentSurface() : (this.method_49606() ? -14339274 : -15261148);
         graphics.method_25294(
            this.method_46426(), this.method_46427(), this.method_46426() + this.field_22758, this.method_46427() + this.field_22759, background
         );
         GuildScreen.this.drawOutline(
            graphics,
            this.method_46426(),
            this.method_46427(),
            this.field_22758,
            this.field_22759,
            this.selected ? GuildScreen.accentColor() : (this.method_49606() ? -11837599 : -13746881)
         );
         int dimColor = -13381254;
         String lowerDetail = this.detail.toLowerCase(Locale.ROOT);
         if (lowerDetail.contains("nether")) {
            dimColor = -43691;
         } else if (lowerDetail.contains("end")) {
            dimColor = -5745161;
         }

         graphics.method_25294(this.method_46426(), this.method_46427(), this.method_46426() + 3, this.method_46427() + this.field_22759, dimColor);
         graphics.method_51433(
            GuildScreen.this.field_22793,
            GuildScreen.this.fit(this.method_25369().getString(), this.field_22758 - 12),
            this.method_46426() + 7,
            this.method_46427() + 3,
            -1,
            false
         );
         graphics.method_51433(
            GuildScreen.this.field_22793,
            GuildScreen.this.fit(this.detail, this.field_22758 - 12),
            this.method_46426() + 7,
            this.method_46427() + 14,
            -6443350,
            false
         );
      }

      public void method_25348(double mouseX, double mouseY) {
         GuildUiSound.play();
         this.action.run();
      }

      protected void method_47399(class_6382 narration) {
         narration.method_37034(class_6381.field_33788, this.method_25369());
         narration.method_37034(class_6381.field_33790, class_2561.method_43470(this.detail));
      }
   }
}
