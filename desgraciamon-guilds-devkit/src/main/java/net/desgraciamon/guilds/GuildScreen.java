package net.desgraciamon.guilds;

import com.cobblemon.mod.common.client.gui.PokemonGuiUtilsKt;
import com.cobblemon.mod.common.client.gui.ProfileTransformType;
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState;
import com.cobblemon.mod.common.entity.PoseType;
import com.cobblemon.mod.common.pokemon.RenderablePokemon;
import org.joml.Quaternionf;
import java.util.Objects;
import com.cobblemon.mod.common.CobblemonSounds;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.storage.ClientBox;
import com.cobblemon.mod.common.client.storage.ClientPC;
import com.cobblemon.mod.common.client.storage.ClientParty;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokemon.EVs;
import com.cobblemon.mod.common.pokemon.IVs;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.mojang.blaze3d.systems.RenderSystem;
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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

final class GuildScreen extends Screen {
   private static final int BASE_WIDTH = 540;
   private static final int BASE_HEIGHT = 330;
   private float uiScale = 1.0F;
   private float renderX = 0.0F;
   private float renderY = 0.0F;
   private static final String[] TABS = new String[]{"General", "Miembros", "PC", "Inventario", "Waypoints", "Registro", "Personalizar"};
   private static final String[] GUILD_ICONS = new String[]{"pokeball", "espada", "escudo", "dragon", "rayo", "estrella", "corazon"};
   private static final String[] DIM_FILTERS = new String[]{"Todas", "Overworld", "Nether", "End"};
   private GuildScreen.HeldVaultItem heldVaultItem = null;
   private int vaultDragButton = -1;
   private boolean vaultDragMoved = false;
   private final java.util.LinkedHashSet<String> vaultDragSlots = new java.util.LinkedHashSet<>();
   private long lastVaultClickTimeMs = 0L;
   private String lastVaultClickSlotKey = "";
   private int lastVaultClickButton = -1;
   private ItemStack lastVaultDiffHeldTarget = ItemStack.EMPTY;
   private List<ItemStack> localVault = null;
   private int pendingVaultOps = 0;
   private long lastVaultOpTimeMs = 0L;
   private ItemStack hoveredVaultTooltip = ItemStack.EMPTY;
   private String hoveredLogTooltip = "";
   private static final Map<String, ItemStack> ITEM_CACHE = new HashMap<>();
   private final int tab;
   private EditBox nameInput;
   private EditBox colorInput;
   private EditBox actionInput;
   private EditBox amountInput;
   private EditBox chatInput;
   private String draftChat = "";
   private int chatScrollOffset = 0;
   private EditBox searchMemberInput;
   private EditBox motdEditInput;
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
   private List<Component> hoveredMemberTooltip = null;
   private List<String> playerSuggestions = new ArrayList<>();
   private GuildScreen.HeldPokemon heldPokemon = null;
   private String warningBanner = "";
   private int warningBannerTicks = 0;

   private static ItemStack getOrCreatePokemonStack(String species, boolean shiny, String form) {
      if (species == null || species.isBlank()) return ItemStack.EMPTY;
      String key = species.toLowerCase(Locale.ROOT) + ":" + shiny + ":" + (form == null ? "" : form.toLowerCase(Locale.ROOT));
      if (ITEM_CACHE.containsKey(key)) {
         ItemStack cached = ITEM_CACHE.get(key);
         if (cached != null && !cached.isEmpty()) {
            return cached;
         }
      }
      try {
         String cleanSpecies = species.toLowerCase(Locale.ROOT).replace(" ", "_");
         String shortName = cleanSpecies.contains(":") ? cleanSpecies.substring(cleanSpecies.indexOf(':') + 1) : cleanSpecies;
         Species speciesObj = PokemonSpecies.getByName(shortName);
         if (speciesObj == null) {
            speciesObj = PokemonSpecies.getByIdentifier(ResourceLocation.tryParse("cobblemon:" + shortName));
         }
         if (speciesObj == null) {
            speciesObj = PokemonSpecies.getByIdentifier(ResourceLocation.tryParse(cleanSpecies));
         }
         if (speciesObj != null) {
            Pokemon poke = speciesObj.create(1);
            poke.setShiny(shiny);
            if (form != null && !form.isBlank() && !form.equalsIgnoreCase("normal") && !form.equalsIgnoreCase("standard")) {
               for (String part : form.trim().split("[\\s,]+")) {
                  String lower = part.toLowerCase(Locale.ROOT);
                  if (!lower.equals("normal") && !lower.equals("standard")) {
                     poke.getAspects().add(lower);
                  }
               }
            }
            ItemStack stack = PokemonItem.from(poke, 1);
            if (!stack.isEmpty()) {
               ITEM_CACHE.put(key, stack);
               return stack;
            }
         }
      } catch (Throwable ignored) {}

      try {
         String cleanSpecies = species.toLowerCase(Locale.ROOT).replace(" ", "_");
         StringBuilder query = new StringBuilder(cleanSpecies);
         if (shiny) {
            query.append(" shiny=yes");
         }
         if (form != null && !form.isBlank() && !form.equalsIgnoreCase("normal") && !form.equalsIgnoreCase("standard")) {
            query.append(" ").append(form.toLowerCase(Locale.ROOT));
         }
         PokemonProperties props = PokemonProperties.Companion.parse(query.toString());
         ItemStack stack = PokemonItem.from(props, 1);
         if (!stack.isEmpty()) {
            ITEM_CACHE.put(key, stack);
            return stack;
         }
      } catch (Throwable ignored) {}

      ITEM_CACHE.put(key, ItemStack.EMPTY);
      return ItemStack.EMPTY;
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

   private int renderLogEntryCard(GuiGraphics graphics, String raw, int x, int y, int cardW, int mouseX, int mouseY) {
      GuildScreen.ParsedLogEntry entry = parseLogEntry(raw);
      List<FormattedCharSequence> lines = this.font.split(Component.literal(entry.actionText()), cardW - 14);
      int cardH = Math.max(26, 15 + lines.size() * 10);
      boolean isHovered = mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + cardH;
      int bg = isHovered ? tintSurface(0x1D2532, 0.28F, 0xFF) : tintSurface(0x151B24, 0.20F, 0xFF);
      int border = isHovered ? accentColor() : tintSurface(0x252E3C, 0.34F, 0xFF);
      graphics.fill(x, y, x + cardW, y + cardH, bg);
      this.drawOutline(graphics, x, y, cardW, cardH, border);
      graphics.fill(x, y, x + 3, y + cardH, entry.color());
      String timeStr = entry.time().isEmpty() ? "" : entry.time() + " • ";
      graphics.drawString(this.font, timeStr, x + 7, y + 3, -8482419, false);
      int timeW = this.font.width(timeStr);
      graphics.drawString(this.font, entry.author(), x + 7 + timeW, y + 3, entry.color(), false);

      for (int i = 0; i < lines.size(); i++) {
         graphics.drawString(this.font, lines.get(i), x + 7, y + 14 + i * 10, -1905690, false);
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
      if (this.minecraft != null && this.minecraft.getConnection() != null) {
         for (PlayerInfo p : this.minecraft.getConnection().getOnlinePlayers()) {
            if (p != null && p.getProfile() != null && p.getProfile().getName() != null) {
               String n = p.getProfile().getName();
               if (!n.equalsIgnoreCase(this.minecraft.getUser().getName())) {
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
            if (this.minecraft != null && this.minecraft.player != null) {
               ClientPC pc = stores.get(this.minecraft.player.getUUID());
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
         if (this.minecraft != null && this.minecraft.player != null) {
            ClientParty cp = (ClientParty)CobblemonClient.INSTANCE.getStorage().getPartyStores().get(this.minecraft.player.getUUID());
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
         if (this.minecraft != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(CobblemonSounds.PC_GRAB, 1.0F));
         }
      } catch (Throwable var2) {
      }
   }

   private void playVaultSound() {
      this.playPcDropSound();
   }

   private void playPcDropSound() {
      try {
         if (this.minecraft != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(CobblemonSounds.PC_DROP, 1.0F));
         }
      } catch (Throwable var2) {
      }
   }

   private void playPcSound() {
      this.playPcDropSound();
   }

   private void playDenySound() {
      try {
         if (this.minecraft != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.VILLAGER_NO, 1.0F));
         }
      } catch (Throwable var2) {
      }
   }

   public void onClose() {
      this.heldPokemon = null;
      this.heldVaultItem = null;
      this.vaultDragButton = -1;
      this.vaultDragMoved = false;
      this.vaultDragSlots.clear();
      super.onClose();
   }

   GuildScreen() {
      this(0, false);
   }

   GuildScreen(boolean animateOpening) {
      this(0, animateOpening);
   }

   private GuildScreen(int tab, boolean animateOpening) {
      super(Component.literal("Gremio"));
      this.tab = tab;
      this.animateOpening = animateOpening;
      this.openedAtNanos = System.nanoTime();
   }

   protected void init() {
      PlayerAvatarHelper.clearCache();
      float margin = 10.0F;
      float maxW = (float)this.width - margin * 2.0F;
      float maxH = (float)this.height - margin * 2.0F;
      this.uiScale = Math.min(1.0F, Math.min(maxW / 540.0F, maxH / 330.0F));
      this.panelWidth = 540;
      this.panelHeight = 330;
      this.renderX = ((float)this.width - 540.0F * this.uiScale) / 2.0F;
      this.renderY = ((float)this.height - 330.0F * this.uiScale) / 2.0F;
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
            this.addRenderableWidget(new GuildScreen.IconTabButton(this.panelX + 12, this.panelY + 42 + index * 38, index, index == this.tab, () -> {
               this.rememberDrafts();
               this.minecraft.setScreen(new GuildScreen(selectedTab, false));
            }));
         }

         int headerBtnY = this.panelY + 8;
         this.addRenderableWidget(
            new GuildScreen.HeaderIconButton(this.panelX + this.panelWidth - 48, headerBtnY, 18, 18, "X", -2534577, this::onClose, "Cerrar menú")
         );
         this.addRenderableWidget(
            new GuildScreen.HeaderIconButton(
               this.panelX + this.panelWidth - 72,
               headerBtnY,
               18,
               18,
               "⎋",
               -2069387,
               () -> this.minecraft.setScreen(new GuildLeaveScreen(this, data)),
               "Salir del gremio"
            )
         );
      } else {
         this.addRenderableWidget(
            new GuildScreen.HeaderIconButton(this.panelX + this.panelWidth - 30, this.panelY + 8, 18, 18, "X", -2534577, this::onClose, "Cerrar")
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
            this.addAction("Aceptar invitación", startBtnX, footerY, btnW, 24, () -> send("ACCEPT", "", 0), true);
            this.addAction("Rechazar", startBtnX + btnW + gap, footerY, btnW, 24, () -> send("DECLINE", "", 0), false);
         } else {
            int formY = cy + 32;
            this.nameInput = this.addInput(cx + 30, formY + 16, cardW - 60, 20, "Pirate Token Gang", 24, this.draftName);
            this.nameInput.setResponder(val -> this.draftName = val);
            int barW = cardW - 130;
            int specY = formY + 52;
            this.addRenderableWidget(new GuildScreen.HueBarWidget(cx + 30, specY, barW, 46, hex -> this.draftColor = hex));
            int iconSize = 22;
            int iconGap = 6;
            int totalIconsW = GUILD_ICONS.length * iconSize + (GUILD_ICONS.length - 1) * iconGap;
            int iconsStartX = cx + 30;
            int iconsY = formY + 92;

// Icons picker removed

            int footerY = cy + cardH - 34;
            this.addAction("\ud83c\udfdb Fundar Gremio", cx + (cardW - 180) / 2, footerY, 180, 24, this::createGuild, true);
         }
      } else {
         int cardY = this.panelY + 46;
         int cardHeight = 56;
         int bankY = cardY + cardHeight + 8;
         int inputW = Math.min(85, Math.max(60, (this.contentWidth - 220) / 2));
         this.amountInput = this.addInput(this.contentX + 10, bankY + 18, inputW, 20, "$ Monto", 18, this.draftAmount);
         this.amountInput.setFilter(s -> s.matches("\\d*"));
         int pillX = this.contentX + 10 + inputW + 6;
         int pillW = 27;
         this.addRenderableWidget(new GuildScreen.QuickPillButton(pillX, bankY + 21, pillW, 14, "+1k", () -> this.addDraftAmount(1000L)));
         this.addRenderableWidget(new GuildScreen.QuickPillButton(pillX + 31, bankY + 21, pillW + 3, 14, "+10k", () -> this.addDraftAmount(10000L)));
         this.addRenderableWidget(new GuildScreen.QuickPillButton(pillX + 66, bankY + 21, pillW + 3, 14, "+50k", () -> this.addDraftAmount(50000L)));
         this.addRenderableWidget(
            new GuildScreen.QuickPillButton(pillX + 101, bankY + 21, pillW + 2, 14, "Todo", () -> this.setDraftAmount(data.walletBalance()))
         );
         int btnW = 58;
         int depX = this.contentX + this.contentWidth - btnW * 2 - 12;
         int withX = this.contentX + this.contentWidth - btnW - 6;
         this.addAction("Depositar", depX, bankY + 17, btnW, 22, () -> this.sendAmount("DEPOSIT"), true);
         this.addAction("Retirar", withX, bankY + 17, btnW, 22, () -> this.sendAmount("WITHDRAW"), false);
         int chatY = bankY + 46 + 8;
         int chatHeight = this.panelY + this.panelHeight - chatY - 10;
         int chatX = this.contentX;
         int chatW = this.contentWidth;
         int inputH = 18;
         int inputY = chatY + chatHeight - inputH - 5;
         int sendBtnW = 42;
         this.chatInput = this.addInput(chatX + 6, inputY, chatW - sendBtnW - 14, inputH, "Escribe un mensaje...", 200, this.draftChat);
         this.chatInput.setResponder(val -> this.draftChat = val);
         this.addRenderableWidget(new GuildScreen.QuickPillButton(chatX + chatW - sendBtnW - 6, inputY, sendBtnW, inputH, "Enviar", this::sendChatMessage));
      }
   }

   private void sendChatMessage() {
      if (this.chatInput != null) {
         String text = this.chatInput.getValue().trim();
         if (!text.isEmpty()) {
            send("CHAT", text, 0);
            this.chatInput.setValue("");
            this.draftChat = "";
            this.chatScrollOffset = 0;
            this.setFocused(this.chatInput);
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
         this.amountInput.setValue(this.draftAmount);
      }
   }

   private void setDraftAmount(BigInteger total) {
      this.draftAmount = total.toString();
      if (this.amountInput != null) {
         this.amountInput.setValue(this.draftAmount);
      }
   }

   private void initMembers(GuildSyncPayload data) {
      int topBarY = this.panelY + 48;
      this.searchMemberInput = this.addInput(this.contentX, topBarY, 150, 22, "Buscar miembro...", 32, this.memberFilter);
      this.searchMemberInput.setResponder(text -> {
         this.memberFilter = text.trim().toLowerCase();
         this.memberScrollOffset = 0;
         this.rebuildWidgets();
      });
      boolean canInvite = "LEADER".equals(data.role()) || "OFFICER".equals(data.role());
      if (canInvite) {
         int invX = this.contentX + 160;
         this.actionInput = this.addInput(invX, topBarY, Math.min(170, this.contentWidth - 238), 22, "Invitar jugador...", 32, this.draftAction);
         this.actionInput.setResponder(text -> {
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
         if ("LEADER".equals(data.role()) && !m.name.equalsIgnoreCase(this.minecraft.getUser().getName())) {
            canManage = true;
         } else if ("OFFICER".equals(data.role()) && "MEMBER".equals(m.role) && !m.name.equalsIgnoreCase(this.minecraft.getUser().getName())) {
            canManage = true;
         }

         if (canManage) {
            String targetName = m.name;
            rightX -= 22;
            this.addRenderableWidget(
               new GuildScreen.RowActionButton(
                  rightX, rowY + 5, 20, 18, "X", -2534577, () -> send("KICK", targetName, 0), "Expulsar del gremio\n§7Elimina a " + targetName + " del gremio."
               )
            );
            if ("LEADER".equals(data.role())) {
               if ("MEMBER".equals(m.role)) {
                  rightX -= 24;
                  this.addRenderableWidget(
                     new GuildScreen.RowActionButton(
                        rightX,
                        rowY + 5,
                        20,
                        18,
                        "^",
                        -10764066,
                        () -> send("PROMOTE", targetName, 0),
                        "Subir rango a Oficial\n§7Otorga permisos de invitación y gestión."
                     )
                  );
               } else if ("OFFICER".equals(m.role)) {
                  rightX -= 24;
                  this.addRenderableWidget(
                     new GuildScreen.RowActionButton(
                        rightX,
                        rowY + 5,
                        20,
                        18,
                        "v",
                        -1004210,
                        () -> send("DEMOTE", targetName, 0),
                        "Bajar rango a Miembro\n§7Reduce sus permisos a miembro regular."
                     )
                  );
                  rightX -= 24;
                  this.addRenderableWidget(
                     new GuildScreen.RowActionButton(
                        rightX,
                        rowY + 5,
                        20,
                        18,
                        "^",
                        -10496,
                        () -> send("PROMOTE", targetName, 0),
                        "Subir rango a Líder\n§7Otorga control total del gremio."
                     )
                  );
               } else if ("LEADER".equals(m.role)) {
                  rightX -= 24;
                  this.addRenderableWidget(
                     new GuildScreen.RowActionButton(
                        rightX,
                        rowY + 5,
                        20,
                        18,
                        "v",
                        -1004210,
                        () -> send("DEMOTE", targetName, 0),
                        "Bajar rango a Oficial\n§7Reduce sus permisos a oficial."
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
         this.addRenderableWidget(new GuildScreen.QuickPillButton(gridX + i * 49, boxTabY, tabW, 18, label, () -> {
            this.selectedPcBox = boxIdx;
            this.rebuildWidgets();
         }));
      }

      this.addRenderableWidget(new GuildScreen.QuickPillButton(privX, boxTabY, 20, 18, "<", () -> {
         ClientPC pc = this.getClientPC();
         int total = pc != null && pc.getBoxes().size() > 0 ? pc.getBoxes().size() : 1;
         privatePcBox = (privatePcBox - 1 + total) % total;
         this.rebuildWidgets();
      }));
      this.addRenderableWidget(new GuildScreen.QuickPillButton(privX + 6 * slotSize - 20, boxTabY, 20, 18, ">", () -> {
         ClientPC pc = this.getClientPC();
         int total = pc != null && pc.getBoxes().size() > 0 ? pc.getBoxes().size() : 1;
         privatePcBox = (privatePcBox + 1) % total;
         this.rebuildWidgets();
      }));
   }

   private static List<XaeroWaypointBridge.LocalWaypoint> combineLocalWaypoints() {
      List<XaeroWaypointBridge.LocalWaypoint> combined = new ArrayList<>(XaeroWaypointBridge.localWaypoints());
      combined.addAll(WaystonesWaypointBridge.localWaystones());
      return combined;
   }

   private void initWaypoints(GuildSyncPayload data) {
      this.localWaypoints = combineLocalWaypoints();
      int filterY = this.panelY + 46;
      int chipW = 54;

      for (int f = 0; f < DIM_FILTERS.length; f++) {
         int filterIdx = f;
         this.addRenderableWidget(new GuildScreen.QuickPillButton(this.contentX + f * (chipW + 5), filterY, chipW, 18, DIM_FILTERS[f], () -> {
            this.waypointDimFilter = filterIdx;
            this.localWaypointScroll = 0;
            this.sharedWaypointScroll = 0;
            this.rebuildWidgets();
         }));
      }

      int shareLocW = 144;
      int shareLocX = this.contentX + this.contentWidth - shareLocW;
      this.addRenderableWidget(
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
         this.addRenderableWidget(
            new GuildScreen.WaypointSelectButton(
               this.contentX,
               listTop + row * rowHeight,
               halfWidth,
               rowHeight - 2,
               waypoint.name(),
               waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z(),
               this.selectedLocalWaypoint == index,
               () -> {
                  this.selectedLocalWaypoint = index;
                  this.selectedGuildWaypoint = "";
                  this.rebuildWidgets();
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
         this.addRenderableWidget(
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
                  this.rebuildWidgets();
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
      this.addRenderableWidget(new GuildScreen.QuickPillButton(this.contentX, filterY, btnW1, 18, (this.logFilterTab == 0 ? "• " : "") + "GENERAL", () -> {
         this.logFilterTab = 0;
         this.logScrollOffset = 0;
         this.rebuildWidgets();
      }));
      this.addRenderableWidget(
         new GuildScreen.QuickPillButton(this.contentX + btnW1 + 4, filterY, btnW2, 18, (this.logFilterTab == 1 ? "• " : "") + "PC / POKÉMON", () -> {
            this.logFilterTab = 1;
            this.logScrollOffset = 0;
            this.rebuildWidgets();
         })
      );
      this.addRenderableWidget(
         new GuildScreen.QuickPillButton(
            this.contentX + btnW1 + 4 + btnW2 + 4, filterY, btnW3, 18, (this.logFilterTab == 2 ? "• " : "") + "INVENTARIO", () -> {
               this.logFilterTab = 2;
               this.logScrollOffset = 0;
               this.rebuildWidgets();
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
         this.nameInput = this.addInput(this.contentX + 10, card1Y + 18, nameW, 20, "Ej. Antau maldito abandonico!", 24, this.draftName);
         this.nameInput.setResponder(val -> this.draftName = val);
         int card2Y = card1Y + card1H + 6;
         int card2H = 78;
         int barW = this.contentWidth - 88;
         this.addRenderableWidget(new GuildScreen.HueBarWidget(this.contentX + 10, card2Y + 20, barW, 50, hex -> this.draftColor = hex));
         int card3Y = card2Y + card2H + 6;
         int iconSize = 26;
         int iconGap = 6;

// Icons edit picker removed

         int footerY = this.panelY + this.panelHeight - 32;
         this.addAction("Guardar cambios", this.contentX, footerY, 120, 22, this::saveGuildDetails, true);
         this.addAction("Disolver gremio", this.contentX + this.contentWidth - 110, footerY, 110, 22, () -> send("DISBAND", "", 0), false);
      }
   }

   private void sendChatCmd(String cmd) {
      if (this.minecraft != null && this.minecraft.player != null && this.minecraft.getConnection() != null) {
         this.minecraft.player.connection.sendCommand(cmd);
      }
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      if (this.closeAfterCreate && GuildClientState.data.member()) {
         this.minecraft.setScreen(null);
      } else {
         if (GuildClientState.data.member() != this.guildMode) {
            this.guildMode = GuildClientState.data.member();
            this.rebuildWidgets();
         }

         graphics.fill(0, 0, this.width, this.height, -1257631726);
         int localMouseX = Math.round(((float)mouseX - this.renderX) / this.uiScale);
         int localMouseY = Math.round(((float)mouseY - this.renderY) / this.uiScale);
         graphics.pose().pushPose();
         graphics.pose().translate(this.renderX, this.renderY, 0.0F);
         graphics.pose().scale(this.uiScale, this.uiScale, 1.0F);
         if (this.animateOpening) {
            float progress = this.openingProgress();
            float eased = 1.0F - (float)Math.pow((double)(1.0F - progress), 3.0);
            float animScale = 0.08F + eased * 0.92F;
            float centerX = (float)this.panelWidth / 2.0F;
            float centerY = (float)this.panelHeight / 2.0F;
            graphics.pose().translate(centerX, centerY, 0.0F);
            graphics.pose().scale(animScale, animScale, 1.0F);
            graphics.pose().translate(-centerX, -centerY, 0.0F);
         }

         graphics.fill(this.panelX, this.panelY, this.panelX + this.panelWidth, this.panelY + this.panelHeight, tintSurface(0x10131A, 0.16F, 0xF2));
         this.drawOutline(graphics, this.panelX, this.panelY, this.panelWidth, this.panelHeight, tintSurface(0x242B38, 0.34F, 0xFF));
         graphics.fill(this.panelX + 1, this.panelY + 1, this.panelX + this.panelWidth - 1, this.panelY + 36, tintSurface(0x161B24, 0.24F, 0xFF));
         graphics.fill(this.panelX + 1, this.panelY + 36, this.panelX + this.panelWidth - 1, this.panelY + 37, accentColor());
         String guildTitle = GuildClientState.data.member() ? GuildClientState.data.name() : "Sin gremio";
         String currentIcon = GuildClientState.data.icon() != null && !GuildClientState.data.icon().isBlank() ? GuildClientState.data.icon() : "pokeball";
         this.drawGuildIcon(graphics, currentIcon, this.panelX + 12, this.panelY + 9, 18, accentColor());
         graphics.drawString(this.font, this.fit(guildTitle, 220), this.panelX + 36, this.panelY + 14, accentColor(), false);
         if (GuildClientState.data.member()) {
            graphics.drawString(
               this.font, "• " + TABS[this.tab].toUpperCase(), this.panelX + 42 + this.font.width(this.fit(guildTitle, 220)), this.panelY + 14, tintSurface(0xAAB4C4, 0.25F, 0xFF), false
            );
            graphics.fill(this.panelX + 1, this.panelY + 37, this.contentX - 6, this.panelY + this.panelHeight - 1, tintSurface(0x12161E, 0.20F, 0xFF));
            graphics.fill(this.contentX - 6, this.panelY + 37, this.contentX - 5, this.panelY + this.panelHeight - 1, tintSurface(0x242B38, 0.32F, 0xFF));
         }

         this.hoveredVaultTooltip = ItemStack.EMPTY;
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

         super.render(graphics, localMouseX, localMouseY, delta);
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
               graphics.renderTooltip(this.font, Component.literal(this.hoveredLogTooltip), localMouseX, localMouseY);
            }
         }

         if (this.hoveredMemberTooltip != null && !this.hoveredMemberTooltip.isEmpty()) {
            graphics.renderComponentTooltip(this.font, this.hoveredMemberTooltip, localMouseX, localMouseY);
         }

         if (this.tab == 3 && this.heldVaultItem != null && !this.heldVaultItem.stack.isEmpty()) {
            ItemStack cursorDisplay = this.heldVaultItem.stack;
            int dragUsed = this.computeVaultDragUsedCount();
            if (dragUsed > 0) {
               int rem = Math.max(0, cursorDisplay.getCount() - dragUsed);
               cursorDisplay = rem > 0 ? cursorDisplay.copyWithCount(rem) : ItemStack.EMPTY;
            }
            if (!cursorDisplay.isEmpty()) {
               graphics.renderItem(cursorDisplay, localMouseX - 8, localMouseY - 8);
               graphics.renderItemDecorations(this.font, cursorDisplay, localMouseX - 8, localMouseY - 8);
            }
         }

         if (this.tab == 2 && this.heldPokemon != null) {
            ItemStack heldStack = this.heldPokemon.heldStack();
            if (heldStack == null || heldStack.isEmpty()) {
               heldStack = getOrCreatePokemonStack(this.heldPokemon.species(), this.heldPokemon.shiny(), this.heldPokemon.form());
            }
            float scale = 1.65F;
            int offset = (int)Math.round((double)(16.0F * scale) / 2.0);
            if (heldStack != null && !heldStack.isEmpty()) {
               graphics.flush();
               RenderSystem.enableDepthTest();
               graphics.pose().pushPose();
               graphics.pose().translate((float)(localMouseX - offset), (float)(localMouseY - offset), 200.0F);
               graphics.pose().scale(scale, scale, scale);
               graphics.renderItem(heldStack, 0, 0);
               graphics.pose().popPose();
               graphics.flush();
            } else {
               renderSlotPokemon3D(graphics, this.heldPokemon.species(), this.heldPokemon.shiny(), this.heldPokemon.form(), localMouseX - offset, localMouseY - offset, offset * 2);
            }

            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 300.0F);
            if (this.heldPokemon.shiny()) {
               graphics.drawString(this.font, "S", localMouseX + offset - 8, localMouseY - offset, -11940, true);
            }

            String lvl = String.valueOf(this.heldPokemon.level());
            graphics.drawString(this.font, lvl, localMouseX + offset - this.font.width(lvl), localMouseY + offset - 8, -3352620, true);
            if (this.heldPokemon.restricted()) {
               int cols = 6;
               int rows = 5;
               int slotSize = 32;
               int gap = 14;
               int totalW = cols * slotSize + gap + slotSize + gap + cols * slotSize;
               int gridX = this.contentX + Math.max(6, (this.contentWidth - totalW) / 2);
               int gridY = this.panelY + 82;
               if (localMouseX >= gridX && localMouseX < gridX + cols * slotSize && localMouseY >= gridY && localMouseY < gridY + rows * slotSize) {
                  graphics.drawString(this.font, "No permitido en Gremio", localMouseX + 12, localMouseY - 6, -48060, true);
               }
            }

            graphics.pose().popPose();
         }

         if (this.warningBannerTicks > 0 && !this.warningBanner.isBlank()) {
            this.warningBannerTicks--;
            int bannerW = this.font.width(this.warningBanner) + 24;
            int bannerH = 18;
            int bannerX = this.panelX + (this.panelWidth - bannerW) / 2;
            int bannerY = this.panelY + this.panelHeight - 26;
            graphics.fill(bannerX, bannerY, bannerX + bannerW, bannerY + bannerH, -292880384);
            this.drawOutline(graphics, bannerX, bannerY, bannerW, bannerH, -52429);
            graphics.drawCenteredString(this.font, this.warningBanner, bannerX + bannerW / 2, bannerY + 5, -1);
         }

         graphics.pose().popPose();
      }
   }

   private void initVault(GuildSyncPayload data) {
      this.hoveredVaultTooltip = ItemStack.EMPTY;
      this.hoveredLogTooltip = "";
      this.refreshLocalVaultFrom(data);
   }

   private void refreshLocalVaultFrom(GuildSyncPayload data) {
      List<ItemStack> fresh = new ArrayList<>(27);
      List<ItemStack> src = data == null ? null : data.vaultItems();
      for (int i = 0; i < 27; i++) {
         ItemStack s = src != null && i < src.size() ? src.get(i) : ItemStack.EMPTY;
         fresh.add(s != null && !s.isEmpty() ? s.copy() : ItemStack.EMPTY);
      }
      this.localVault = fresh;
   }

   private List<ItemStack> getEffectiveVault(GuildSyncPayload data) {
      if (this.pendingVaultOps > 0 && System.currentTimeMillis() - this.lastVaultOpTimeMs > 1500L) {
         this.pendingVaultOps = 0;
         this.refreshLocalVaultFrom(data);
      } else if (this.localVault == null) {
         this.refreshLocalVaultFrom(data);
      }
      return this.localVault;
   }

   private ItemStack getLocalSlot(String container, int slot, Player player) {
      if ("VAULT".equals(container)) {
         List<ItemStack> vault = this.getEffectiveVault(GuildClientState.data);
         if (vault != null && slot >= 0 && slot < vault.size()) {
            ItemStack s = vault.get(slot);
            return s == null ? ItemStack.EMPTY : s;
         }
      } else if ("PLAYER".equals(container) && player != null && slot >= 0 && slot < 36) {
         ItemStack s = player.getInventory().getItem(slot);
         return s == null ? ItemStack.EMPTY : s;
      }
      return ItemStack.EMPTY;
   }

   private void setLocalSlot(String container, int slot, Player player, ItemStack stack) {
      ItemStack safe = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
      if ("VAULT".equals(container)) {
         List<ItemStack> vault = this.getEffectiveVault(GuildClientState.data);
         if (vault != null && slot >= 0 && slot < vault.size()) {
            vault.set(slot, safe);
         }
      } else if ("PLAYER".equals(container) && player != null && slot >= 0 && slot < 36) {
         player.getInventory().setItem(slot, safe);
      }
   }

   private int applyLocalTransfer(String srcType, int srcSlot, String dstType, int dstSlot, int amount, boolean allowSwap, Player player) {
      if (amount <= 0 || (srcType.equals(dstType) && srcSlot == dstSlot)) {
         return 0;
      }
      ItemStack src = this.getLocalSlot(srcType, srcSlot, player);
      if (src.isEmpty()) {
         return 0;
      }
      int toMove = Math.min(amount, src.getCount());
      ItemStack dst = this.getLocalSlot(dstType, dstSlot, player);
      if (dst.isEmpty()) {
         ItemStack placed = src.copyWithCount(toMove);
         ItemStack remaining = src.getCount() > toMove ? src.copyWithCount(src.getCount() - toMove) : ItemStack.EMPTY;
         this.setLocalSlot(dstType, dstSlot, player, placed);
         this.setLocalSlot(srcType, srcSlot, player, remaining);
         return toMove;
      } else if (ItemStack.isSameItemSameComponents(src, dst)) {
         int space = dst.getMaxStackSize() - dst.getCount();
         int actual = Math.min(space, toMove);
         if (actual <= 0) {
            return 0;
         }
         ItemStack merged = dst.copyWithCount(dst.getCount() + actual);
         ItemStack remaining = src.getCount() > actual ? src.copyWithCount(src.getCount() - actual) : ItemStack.EMPTY;
         this.setLocalSlot(dstType, dstSlot, player, merged);
         this.setLocalSlot(srcType, srcSlot, player, remaining);
         return actual;
      } else if (allowSwap && toMove == src.getCount()) {
         ItemStack srcCopy = src.copy();
         ItemStack dstCopy = dst.copy();
         this.setLocalSlot(dstType, dstSlot, player, srcCopy);
         this.setLocalSlot(srcType, srcSlot, player, dstCopy);
         return srcCopy.getCount();
      }
      return 0;
   }

   private boolean applyLocalQuickMove(String srcType, int srcSlot, Player player) {
      ItemStack src = this.getLocalSlot(srcType, srcSlot, player);
      if (src.isEmpty()) {
         return false;
      }
      ItemStack working = src.copy();
      int original = working.getCount();
      String dstType = "PLAYER".equals(srcType) ? "VAULT" : "PLAYER";
      int dstSize = "VAULT".equals(dstType) ? 27 : 36;
      for (int i = 0; i < dstSize && !working.isEmpty(); i++) {
         ItemStack dst = this.getLocalSlot(dstType, i, player);
         if (!dst.isEmpty() && ItemStack.isSameItemSameComponents(working, dst)) {
            int space = dst.getMaxStackSize() - dst.getCount();
            if (space > 0) {
               int toMove = Math.min(space, working.getCount());
               this.setLocalSlot(dstType, i, player, dst.copyWithCount(dst.getCount() + toMove));
               working.shrink(toMove);
            }
         }
      }
      for (int i = 0; i < dstSize && !working.isEmpty(); i++) {
         ItemStack dst = this.getLocalSlot(dstType, i, player);
         if (dst.isEmpty()) {
            this.setLocalSlot(dstType, i, player, working.copy());
            working.setCount(0);
            break;
         }
      }
      int moved = original - working.getCount();
      if (moved > 0) {
         this.setLocalSlot(srcType, srcSlot, player, working.isEmpty() ? ItemStack.EMPTY : working);
         return true;
      }
      return false;
   }

   private void validateHeldVaultItem() {
      if (this.heldVaultItem == null || this.minecraft == null || this.minecraft.player == null) {
         return;
      }
      ItemStack real = this.getLocalSlot(this.heldVaultItem.container, this.heldVaultItem.slot, this.minecraft.player);
      int needed = this.heldVaultItem.stack.getCount() + this.heldVaultItem.remainingInSource;
      if (real.isEmpty() || !ItemStack.isSameItemSameComponents(real, this.heldVaultItem.stack) || real.getCount() < needed) {
         this.heldVaultItem = null;
         this.vaultDragButton = -1;
         this.vaultDragMoved = false;
         this.vaultDragSlots.clear();
      }
   }

   private void renderVault(GuiGraphics graphics, GuildSyncPayload data, int mouseX, int mouseY) {
      if (data.member() && this.minecraft != null && this.minecraft.player != null) {
         LocalPlayer player = this.minecraft.player;
         this.hoveredVaultTooltip = ItemStack.EMPTY;
         int cardW = 444;
         int cx = this.contentX + 10;
         int slotSize = 30;
         int gridX = cx + (cardW - 9 * slotSize) / 2;
         int card1Y = this.panelY + 44;
         int card1H = 114;
         graphics.fill(cx, card1Y, cx + cardW, card1Y + card1H, tintSurface(0x12161E, 0.20F, 0xFF));
         this.drawOutline(graphics, cx, card1Y, cardW, card1H, tintSurface(0x283040, 0.34F, 0xFF));
         graphics.drawString(this.font, "COFRE DEL GREMIO", cx + 10, card1Y + 7, accentColor(), false);
         int vaultGridY = card1Y + 20;
         List<ItemStack> vaultItems = this.getEffectiveVault(data);

         for (int i = 0; i < 27; i++) {
            int c = i % 9;
            int r = i / 9;
            int sx = gridX + c * slotSize;
            int sy = vaultGridY + r * slotSize;
            graphics.fill(sx, sy, sx + slotSize - 2, sy + slotSize - 2, tintSurface(0x161B25, 0.22F, 0xFF));
            this.drawOutline(graphics, sx, sy, slotSize - 2, slotSize - 2, tintSurface(0x2B3445, 0.34F, 0xFF));
            int dragAdd = this.vaultDragPreviewAdd("VAULT", i);
            boolean hovered = mouseX >= sx && mouseX < sx + slotSize - 2 && mouseY >= sy && mouseY < sy + slotSize - 2;
            if (dragAdd > 0) {
               graphics.fill(sx, sy, sx + slotSize - 2, sy + slotSize - 2, tintSurface(0xFFFFFF, 0.65F, 0x55));
            } else if (hovered) {
               graphics.fill(sx, sy, sx + slotSize - 2, sy + slotSize - 2, 1157627903);
            }

            ItemStack stack = vaultItems != null && i < vaultItems.size() ? vaultItems.get(i) : ItemStack.EMPTY;
            ItemStack renderStack = stack;
            if (this.heldVaultItem != null && "VAULT".equals(this.heldVaultItem.container) && this.heldVaultItem.slot == i) {
               if (this.heldVaultItem.remainingInSource > 0 && stack != null && !stack.isEmpty()) {
                  renderStack = stack.copyWithCount(this.heldVaultItem.remainingInSource);
               } else {
                  renderStack = ItemStack.EMPTY;
               }
            }
            if (dragAdd > 0 && this.heldVaultItem != null) {
               int baseCnt = (renderStack != null && !renderStack.isEmpty()) ? renderStack.getCount() : 0;
               renderStack = this.heldVaultItem.stack.copyWithCount(Math.min(this.heldVaultItem.stack.getMaxStackSize(), baseCnt + dragAdd));
            }

            if (renderStack != null && !renderStack.isEmpty()) {
               graphics.pose().pushPose();
               graphics.pose().translate((float)(sx + 3), (float)(sy + 3), 0.0F);
               graphics.pose().scale(1.5F, 1.5F, 1.0F);
               graphics.renderItem(renderStack, 0, 0);
               graphics.pose().popPose();
               graphics.renderItemDecorations(this.font, renderStack, sx + 7, sy + 7);
               if (hovered && this.heldVaultItem == null) {
                  this.hoveredVaultTooltip = renderStack;
               }
            }
         }

         int card2Y = this.panelY + 162;
         int card2H = 156;
         graphics.fill(cx, card2Y, cx + cardW, card2Y + card2H, tintSurface(0x12161E, 0.20F, 0xFF));
         this.drawOutline(graphics, cx, card2Y, cardW, card2H, tintSurface(0x283040, 0.34F, 0xFF));
         graphics.drawString(this.font, "TU INVENTARIO", cx + 10, card2Y + 7, accentColor(), false);
         int playerMainGridY = card2Y + 20;

         for (int rx = 0; rx < 3; rx++) {
            for (int cxx = 0; cxx < 9; cxx++) {
               int pSlot = 9 + rx * 9 + cxx;
               int sxx = gridX + cxx * slotSize;
               int syx = playerMainGridY + rx * slotSize;
               graphics.fill(sxx, syx, sxx + slotSize - 2, syx + slotSize - 2, tintSurface(0x161B25, 0.22F, 0xFF));
               this.drawOutline(graphics, sxx, syx, slotSize - 2, slotSize - 2, tintSurface(0x2B3445, 0.34F, 0xFF));
               int dragAddP = this.vaultDragPreviewAdd("PLAYER", pSlot);
               boolean hoveredx = mouseX >= sxx && mouseX < sxx + slotSize - 2 && mouseY >= syx && mouseY < syx + slotSize - 2;
               if (dragAddP > 0) {
                  graphics.fill(sxx, syx, sxx + slotSize - 2, syx + slotSize - 2, tintSurface(0xFFFFFF, 0.65F, 0x55));
               } else if (hoveredx) {
                  graphics.fill(sxx, syx, sxx + slotSize - 2, syx + slotSize - 2, 1157627903);
               }

               ItemStack stackx = player.getInventory().getItem(pSlot);
               ItemStack renderStackx = stackx;
               if (this.heldVaultItem != null && "PLAYER".equals(this.heldVaultItem.container) && this.heldVaultItem.slot == pSlot) {
                  if (this.heldVaultItem.remainingInSource > 0 && stackx != null && !stackx.isEmpty()) {
                     renderStackx = stackx.copyWithCount(this.heldVaultItem.remainingInSource);
                  } else {
                     renderStackx = ItemStack.EMPTY;
                  }
               }
               if (dragAddP > 0 && this.heldVaultItem != null) {
                  int baseCnt = (renderStackx != null && !renderStackx.isEmpty()) ? renderStackx.getCount() : 0;
                  renderStackx = this.heldVaultItem.stack.copyWithCount(Math.min(this.heldVaultItem.stack.getMaxStackSize(), baseCnt + dragAddP));
               }

               if (renderStackx != null && !renderStackx.isEmpty()) {
                  graphics.pose().pushPose();
                  graphics.pose().translate((float)(sxx + 3), (float)(syx + 3), 0.0F);
                  graphics.pose().scale(1.5F, 1.5F, 1.0F);
                  graphics.renderItem(renderStackx, 0, 0);
                  graphics.pose().popPose();
                  graphics.renderItemDecorations(this.font, renderStackx, sxx + 7, syx + 7);
                  if (hoveredx && this.heldVaultItem == null) {
                     this.hoveredVaultTooltip = renderStackx;
                  }
               }
            }
         }

         int hotbarGridY = playerMainGridY + 3 * slotSize + 4;

         for (int cxx = 0; cxx < 9; cxx++) {
            int sxxx = gridX + cxx * slotSize;
            graphics.fill(sxxx, hotbarGridY, sxxx + slotSize - 2, hotbarGridY + slotSize - 2, tintSurface(0x181E29, 0.24F, 0xFF));
            this.drawOutline(graphics, sxxx, hotbarGridY, slotSize - 2, slotSize - 2, tintSurface(0x323D52, 0.38F, 0xFF));
            int dragAddH = this.vaultDragPreviewAdd("PLAYER", cxx);
            boolean hoveredxx = mouseX >= sxxx && mouseX < sxxx + slotSize - 2 && mouseY >= hotbarGridY && mouseY < hotbarGridY + slotSize - 2;
            if (dragAddH > 0) {
               graphics.fill(sxxx, hotbarGridY, sxxx + slotSize - 2, hotbarGridY + slotSize - 2, tintSurface(0xFFFFFF, 0.65F, 0x55));
            } else if (hoveredxx) {
               graphics.fill(sxxx, hotbarGridY, sxxx + slotSize - 2, hotbarGridY + slotSize - 2, 1157627903);
            }

            ItemStack stackxx = player.getInventory().getItem(cxx);
            ItemStack renderStackxx = stackxx;
            if (this.heldVaultItem != null && "PLAYER".equals(this.heldVaultItem.container) && this.heldVaultItem.slot == cxx) {
               if (this.heldVaultItem.remainingInSource > 0 && stackxx != null && !stackxx.isEmpty()) {
                  renderStackxx = stackxx.copyWithCount(this.heldVaultItem.remainingInSource);
               } else {
                  renderStackxx = ItemStack.EMPTY;
               }
            }
            if (dragAddH > 0 && this.heldVaultItem != null) {
               int baseCnt = (renderStackxx != null && !renderStackxx.isEmpty()) ? renderStackxx.getCount() : 0;
               renderStackxx = this.heldVaultItem.stack.copyWithCount(Math.min(this.heldVaultItem.stack.getMaxStackSize(), baseCnt + dragAddH));
            }

            if (renderStackxx != null && !renderStackxx.isEmpty()) {
               graphics.pose().pushPose();
               graphics.pose().translate((float)(sxxx + 3), (float)(hotbarGridY + 3), 0.0F);
               graphics.pose().scale(1.5F, 1.5F, 1.0F);
               graphics.renderItem(renderStackxx, 0, 0);
               graphics.pose().popPose();
               graphics.renderItemDecorations(this.font, renderStackxx, sxxx + 7, hotbarGridY + 7);
               if (hoveredxx && this.heldVaultItem == null) {
                  this.hoveredVaultTooltip = renderStackxx;
               }
            }
         }
      }
   }

   private void renderVaultTooltip(GuiGraphics graphics, GuildSyncPayload data, int mouseX, int mouseY) {
      if (this.hoveredVaultTooltip != null && !this.hoveredVaultTooltip.isEmpty()) {
         graphics.renderTooltip(this.font, this.hoveredVaultTooltip, mouseX, mouseY);
      }
   }

   private int getVaultDragSlotSpace(String container, int slot) {
      if (this.heldVaultItem == null || this.minecraft == null || this.minecraft.player == null) return 0;
      int maxStack = this.heldVaultItem.stack.getMaxStackSize();
      if (this.heldVaultItem.container.equals(container) && this.heldVaultItem.slot == slot) {
         return Math.max(0, maxStack - this.heldVaultItem.remainingInSource);
      }
      ItemStack existing = this.getLocalSlot(container, slot, this.minecraft.player);
      if (existing.isEmpty()) {
         return maxStack;
      }
      if (!ItemStack.isSameItemSameComponents(this.heldVaultItem.stack, existing)) {
         return 0;
      }
      return Math.max(0, maxStack - existing.getCount());
   }

   private int computeVaultDragPerSlot() {
      if (!this.vaultDragMoved || this.heldVaultItem == null || this.vaultDragSlots.isEmpty()) return 0;
      int heldCount = this.heldVaultItem.stack.getCount();
      if (this.vaultDragButton == 0) {
         return Math.max(1, heldCount / this.vaultDragSlots.size());
      }
      return this.vaultDragButton == 1 ? 1 : 0;
   }

   private int computeVaultDragUsedCount() {
      if (!this.vaultDragMoved || this.heldVaultItem == null || this.vaultDragSlots.isEmpty()) return 0;
      int per = this.computeVaultDragPerSlot();
      int used = 0;
      int remaining = this.heldVaultItem.stack.getCount();
      for (String k : this.vaultDragSlots) {
         if (remaining <= 0) break;
         String[] parts = k.split("\\|", 2);
         int space = this.getVaultDragSlotSpace(parts[0], Integer.parseInt(parts[1]));
         int add = Math.min(Math.min(per, remaining), space);
         used += add;
         remaining -= add;
      }
      return used;
   }

   private int vaultDragPreviewAdd(String container, int slot) {
      if (!this.vaultDragMoved || this.heldVaultItem == null || this.vaultDragSlots.isEmpty()) return 0;
      String key = container + "|" + slot;
      if (!this.vaultDragSlots.contains(key)) return 0;
      int per = this.computeVaultDragPerSlot();
      int remaining = this.heldVaultItem.stack.getCount();
      for (String k : this.vaultDragSlots) {
         if (remaining <= 0) return 0;
         String[] parts = k.split("\\|", 2);
         int space = this.getVaultDragSlotSpace(parts[0], Integer.parseInt(parts[1]));
         int add = Math.min(Math.min(per, remaining), space);
         if (k.equals(key)) {
            return add;
         }
         remaining -= add;
      }
      return 0;
   }

   private String findVaultSlotKeyAt(double mouseX, double mouseY) {
      int cardW = 444;
      int cx = this.contentX + 10;
      int slotSize = 30;
      int gridX = cx + (cardW - 9 * slotSize) / 2;
      int vaultGridY = this.panelY + 44 + 20;
      int playerMainGridY = this.panelY + 162 + 20;
      int hotbarGridY = playerMainGridY + 3 * slotSize + 4;

      for (int i = 0; i < 27; i++) {
         int c = i % 9;
         int r = i / 9;
         int sx = gridX + c * slotSize;
         int sy = vaultGridY + r * slotSize;
         if (mouseX >= (double)sx && mouseX < (double)(sx + slotSize - 2) && mouseY >= (double)sy && mouseY < (double)(sy + slotSize - 2)) {
            return "VAULT|" + i;
         }
      }
      for (int r = 0; r < 3; r++) {
         for (int c = 0; c < 9; c++) {
            int pSlot = 9 + r * 9 + c;
            int sx = gridX + c * slotSize;
            int sy = playerMainGridY + r * slotSize;
            if (mouseX >= (double)sx && mouseX < (double)(sx + slotSize - 2) && mouseY >= (double)sy && mouseY < (double)(sy + slotSize - 2)) {
               return "PLAYER|" + pSlot;
            }
         }
      }
      for (int c = 0; c < 9; c++) {
         int sx = gridX + c * slotSize;
         if (mouseX >= (double)sx && mouseX < (double)(sx + slotSize - 2) && mouseY >= (double)hotbarGridY && mouseY < (double)(hotbarGridY + slotSize - 2)) {
            return "PLAYER|" + c;
         }
      }
      return null;
   }

   private boolean canVaultDragInto(String slotKey) {
      if (this.heldVaultItem == null || slotKey == null || this.minecraft == null || this.minecraft.player == null) return false;
      String[] parts = slotKey.split("\\|", 2);
      String container = parts[0];
      int slot = Integer.parseInt(parts[1]);
      if (this.heldVaultItem.container.equals(container) && this.heldVaultItem.slot == slot) {
         return this.heldVaultItem.remainingInSource < this.heldVaultItem.stack.getMaxStackSize();
      }
      ItemStack existing = this.getLocalSlot(container, slot, this.minecraft.player);
      if (existing.isEmpty()) return true;
      return ItemStack.isSameItemSameComponents(this.heldVaultItem.stack, existing) && existing.getCount() < existing.getMaxStackSize();
   }

   private boolean handleVaultMouseClicked(double mouseX, double mouseY, int button) {
      if (this.minecraft != null && this.minecraft.player != null) {
         LocalPlayer player = this.minecraft.player;
         boolean shift = hasShiftDown();
         String slotKey = this.findVaultSlotKeyAt(mouseX, mouseY);
         if (slotKey == null) return false;
         if (!shift && this.heldVaultItem != null && (button == 0 || button == 1) && this.canVaultDragInto(slotKey)) {
            this.vaultDragButton = button;
            this.vaultDragMoved = false;
            this.vaultDragSlots.clear();
            this.vaultDragSlots.add(slotKey);
            return true;
         }
         String[] parts = slotKey.split("\\|", 2);
         String container = parts[0];
         int slot = Integer.parseInt(parts[1]);
         ItemStack stack = this.getLocalSlot(container, slot, player);

         long nowMs = System.currentTimeMillis();
         boolean isDoubleClick = button == 0 && !shift
            && nowMs - this.lastVaultClickTimeMs <= 350L
            && slotKey.equals(this.lastVaultClickSlotKey)
            && this.lastVaultClickButton == 0;
         this.lastVaultClickTimeMs = nowMs;
         this.lastVaultClickSlotKey = slotKey;
         this.lastVaultClickButton = button;

         if (isDoubleClick) {
            if (!this.lastVaultDiffHeldTarget.isEmpty() && this.heldVaultItem != null) {
               ItemStack targetTemplate = this.lastVaultDiffHeldTarget.copy();
               this.lastVaultDiffHeldTarget = ItemStack.EMPTY;
               int limit = "PLAYER".equals(container) ? 36 : 27;
               int matchCount = 0;
               for (int i = 0; i < limit; i++) {
                  ItemStack s = this.getLocalSlot(container, i, player);
                  if (s != null && !s.isEmpty() && ItemStack.isSameItemSameComponents(targetTemplate, s)) {
                     matchCount++;
                  }
               }
               if (matchCount >= 1) {
                  boolean movedAny = false;
                  for (int i = 0; i < limit; i++) {
                     ItemStack s = this.getLocalSlot(container, i, player);
                     if (s != null && !s.isEmpty() && ItemStack.isSameItemSameComponents(targetTemplate, s)) {
                        if (this.applyLocalQuickMove(container, i, player)) {
                           movedAny = true;
                        }
                     }
                  }
                  if (movedAny) {
                     this.pendingVaultOps++;
                     this.lastVaultOpTimeMs = System.currentTimeMillis();
                     send("VAULT_MOVE_ALL_MATCHING", container + "|" + this.heldVaultItem.container + "|" + this.heldVaultItem.slot, 0);
                     this.playVaultSound();
                     return true;
                  }
               }
            }
            this.lastVaultDiffHeldTarget = ItemStack.EMPTY;
            String targetContainer = this.heldVaultItem != null ? this.heldVaultItem.container : container;
            int targetSlot = this.heldVaultItem != null ? this.heldVaultItem.slot : slot;
            if (this.applyLocalPickupAll(targetContainer, targetSlot, player)) {
               if (this.heldVaultItem == null) {
                  ItemStack gathered = this.getLocalSlot(targetContainer, targetSlot, player);
                  if (gathered != null && !gathered.isEmpty()) {
                     this.heldVaultItem = new GuildScreen.HeldVaultItem(targetContainer, targetSlot, gathered.copy(), 0);
                  }
               }
               this.pendingVaultOps++;
               this.lastVaultOpTimeMs = System.currentTimeMillis();
               send("VAULT_PICKUP_ALL", targetContainer + "|" + targetSlot, 0);
               this.playVaultSound();
               return true;
            }
         } else if (button == 0 && !shift && this.heldVaultItem != null && stack != null && !stack.isEmpty()
            && !ItemStack.isSameItemSameComponents(this.heldVaultItem.stack, stack)) {
            this.lastVaultDiffHeldTarget = stack.copy();
         } else {
            this.lastVaultDiffHeldTarget = ItemStack.EMPTY;
         }

         return this.handleSlotClick(container, slot, stack, button, shift, player);
      } else {
         return false;
      }
   }

   private boolean handleSlotClick(String targetContainer, int targetSlot, ItemStack targetStack, int button, boolean shift, Player player) {
      if (button != 0 && button != 1) {
         return false;
      } else if (shift) {
         if (this.heldVaultItem == null && targetStack != null && !targetStack.isEmpty()) {
            if (this.applyLocalQuickMove(targetContainer, targetSlot, player)) {
               this.pendingVaultOps++;
               this.lastVaultOpTimeMs = System.currentTimeMillis();
               send("VAULT_QUICK_MOVE", targetContainer + "|" + targetSlot, 0);
               this.playVaultSound();
            }
            return true;
         } else {
            return true;
         }
      } else if (this.heldVaultItem == null) {
         if (targetStack == null || targetStack.isEmpty()) {
            return false;
         } else if (button == 0) {
            this.heldVaultItem = new GuildScreen.HeldVaultItem(targetContainer, targetSlot, targetStack.copy(), 0);
            this.playVaultSound();
            return true;
         } else if (button == 1) {
            int count = targetStack.getCount();
            int take = (count + 1) / 2;
            int rem = count - take;
            this.heldVaultItem = new GuildScreen.HeldVaultItem(targetContainer, targetSlot, targetStack.copyWithCount(take), rem);
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
               this.heldVaultItem.stack.shrink(1);
               this.heldVaultItem.remainingInSource++;
               if (this.heldVaultItem.stack.isEmpty()) {
                  this.heldVaultItem = null;
               }

               this.playVaultSound();
               return true;
            } else {
               return false;
            }
         } else if (targetStack != null && !targetStack.isEmpty()) {
            if (ItemStack.isSameItemSameComponents(this.heldVaultItem.stack, targetStack)) {
               int space = targetStack.getMaxStackSize() - targetStack.getCount();
               if (space <= 0) {
                  return false;
               } else if (button == 0) {
                  int toMove = Math.min(space, this.heldVaultItem.stack.getCount());
                  int moved = this.applyLocalTransfer(this.heldVaultItem.container, this.heldVaultItem.slot, targetContainer, targetSlot, toMove, false, player);
                  if (moved > 0) {
                     this.pendingVaultOps++;
                     this.lastVaultOpTimeMs = System.currentTimeMillis();
                     send(
                        "VAULT_TRANSFER",
                        this.heldVaultItem.container + "|" + this.heldVaultItem.slot + "|" + targetContainer + "|" + targetSlot + "|" + moved,
                        0
                     );
                     this.heldVaultItem.stack.shrink(moved);
                     if (this.heldVaultItem.stack.isEmpty()) {
                        this.heldVaultItem = null;
                     }
                     this.playVaultSound();
                  }
                  return true;
               } else if (button == 1) {
                  int moved = this.applyLocalTransfer(this.heldVaultItem.container, this.heldVaultItem.slot, targetContainer, targetSlot, 1, false, player);
                  if (moved > 0) {
                     this.pendingVaultOps++;
                     this.lastVaultOpTimeMs = System.currentTimeMillis();
                     send("VAULT_TRANSFER", this.heldVaultItem.container + "|" + this.heldVaultItem.slot + "|" + targetContainer + "|" + targetSlot + "|1", 0);
                     this.heldVaultItem.stack.shrink(1);
                     if (this.heldVaultItem.stack.isEmpty()) {
                        this.heldVaultItem = null;
                     }
                     this.playVaultSound();
                  }
                  return true;
               } else {
                  return false;
               }
            } else {
               if (button == 0 && this.heldVaultItem.remainingInSource == 0) {
                  int srcCount = this.getSlotCount(this.heldVaultItem.container, this.heldVaultItem.slot, player);
                  if (this.heldVaultItem.stack.getCount() == srcCount) {
                     ItemStack swappedTarget = targetStack.copy();
                     int moved = this.applyLocalTransfer(
                        this.heldVaultItem.container, this.heldVaultItem.slot, targetContainer, targetSlot, this.heldVaultItem.stack.getCount(), true, player
                     );
                     if (moved > 0) {
                        this.pendingVaultOps++;
                        this.lastVaultOpTimeMs = System.currentTimeMillis();
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
                              + moved,
                           0
                        );
                        this.heldVaultItem = new GuildScreen.HeldVaultItem(this.heldVaultItem.container, this.heldVaultItem.slot, swappedTarget, 0);
                        this.playVaultSound();
                        return true;
                     }
                  }
               }

               return false;
            }
         } else if (button == 0) {
            int toMove = this.heldVaultItem.stack.getCount();
            int moved = this.applyLocalTransfer(this.heldVaultItem.container, this.heldVaultItem.slot, targetContainer, targetSlot, toMove, false, player);
            if (moved > 0) {
               this.pendingVaultOps++;
               this.lastVaultOpTimeMs = System.currentTimeMillis();
               send("VAULT_TRANSFER", this.heldVaultItem.container + "|" + this.heldVaultItem.slot + "|" + targetContainer + "|" + targetSlot + "|" + moved, 0);
               this.heldVaultItem.stack.shrink(moved);
               if (this.heldVaultItem.stack.isEmpty()) {
                  this.heldVaultItem = null;
               }
               this.playVaultSound();
            }
            return true;
         } else if (button == 1) {
            int moved = this.applyLocalTransfer(this.heldVaultItem.container, this.heldVaultItem.slot, targetContainer, targetSlot, 1, false, player);
            if (moved > 0) {
               this.pendingVaultOps++;
               this.lastVaultOpTimeMs = System.currentTimeMillis();
               send("VAULT_TRANSFER", this.heldVaultItem.container + "|" + this.heldVaultItem.slot + "|" + targetContainer + "|" + targetSlot + "|1", 0);
               this.heldVaultItem.stack.shrink(1);
               if (this.heldVaultItem.stack.isEmpty()) {
                  this.heldVaultItem = null;
               }
               this.playVaultSound();
            }
            return true;
         } else {
            return false;
         }
      }
   }

   private int getSlotCount(String container, int slot, Player player) {
      ItemStack s = this.getLocalSlot(container, slot, player);
      return s != null && !s.isEmpty() ? s.getCount() : 0;
   }

   private boolean applyLocalPickupAll(String dstContainer, int dstSlot, Player player) {
      ItemStack dst = this.getLocalSlot(dstContainer, dstSlot, player);
      if (dst == null || dst.isEmpty() || dst.getCount() >= dst.getMaxStackSize()) {
         return false;
      }
      ItemStack template = dst.copy();
      int needed = dst.getMaxStackSize() - dst.getCount();
      boolean movedAny = false;
      for (int pass = 0; pass < 2 && needed > 0; pass++) {
         boolean partialOnly = pass == 0;
         for (int i = 0; i < 36 && needed > 0; i++) {
            if ("PLAYER".equals(dstContainer) && i == dstSlot) continue;
            ItemStack cand = player.getInventory().getItem(i);
            if (cand.isEmpty() || !ItemStack.isSameItemSameComponents(template, cand)) continue;
            if (partialOnly && cand.getCount() >= cand.getMaxStackSize()) continue;
            int moved = this.applyLocalTransfer("PLAYER", i, dstContainer, dstSlot, needed, false, player);
            if (moved > 0) {
               needed -= moved;
               movedAny = true;
            }
         }
         for (int i = 0; i < 27 && needed > 0; i++) {
            if ("VAULT".equals(dstContainer) && i == dstSlot) continue;
            ItemStack cand = this.getLocalSlot("VAULT", i, player);
            if (cand.isEmpty() || !ItemStack.isSameItemSameComponents(template, cand)) continue;
            if (partialOnly && cand.getCount() >= cand.getMaxStackSize()) continue;
            int moved = this.applyLocalTransfer("VAULT", i, dstContainer, dstSlot, needed, false, player);
            if (moved > 0) {
               needed -= moved;
               movedAny = true;
            }
         }
      }
      if (movedAny && this.heldVaultItem != null && this.heldVaultItem.container.equals(dstContainer) && this.heldVaultItem.slot == dstSlot) {
         ItemStack updated = this.getLocalSlot(dstContainer, dstSlot, player);
         this.heldVaultItem.stack = updated.copy();
         this.heldVaultItem.remainingInSource = 0;
      }
      return movedAny;
   }

   private double getLocalMouseX() {
      if (this.minecraft == null) return -1.0;
      double screenX = this.minecraft.mouseHandler.xpos() * (double)this.width / (double)Math.max(1, this.minecraft.getWindow().getScreenWidth());
      return (screenX - (double)this.renderX) / (double)this.uiScale;
   }

   private double getLocalMouseY() {
      if (this.minecraft == null) return -1.0;
      double screenY = this.minecraft.mouseHandler.ypos() * (double)this.height / (double)Math.max(1, this.minecraft.getWindow().getScreenHeight());
      return (screenY - (double)this.renderY) / (double)this.uiScale;
   }

   private void renderGeneral(GuiGraphics graphics, GuildSyncPayload data, int mouseX, int mouseY) {
      if (!data.member()) {
         boolean isInvited = "INVITED".equals(data.role());
         int cardW = this.contentWidth - 20;
         int cardH = this.panelHeight - 56;
         int cx = this.contentX + 10;
         int cy = this.panelY + 44;
         if (isInvited) {
            this.renderCard(graphics, cx, cy, cardW, cardH, "INVITACIÓN DE GREMIO PENDIENTE", -10238030);
            graphics.drawString(this.font, "Has recibido una invitación para unirte a un gremio:", cx + 20, cy + 24, -3352620, false);
            int bannerY = cy + 40;
            graphics.fill(cx + 20, bannerY, cx + cardW - 20, bannerY + 42, tintSurface(0x111718, 0.22F, 0xFF));
            this.drawOutline(graphics, cx + 20, bannerY, cardW - 40, 42, accentColor());
            this.drawGuildIcon(graphics, data.icon(), cx + 30, bannerY + 9, 24, accentColor());
            graphics.drawString(this.font, "§l" + data.name(), cx + 64, bannerY + 12, -1, false);
            graphics.drawString(this.font, "§aInvitación activa §8• §7Total de miembros: §e" + data.memberCount(), cx + 64, bannerY + 26, -7364196, false);
            int memY = bannerY + 52;
            graphics.drawString(this.font, "§7Miembros del gremio (Pasa el cursor para ver sus nombres):", cx + 20, memY, -6443350, false);
            int headX = cx + 20;
            int headY = memY + 14;
            int drawn = 0;

            for (GuildScreen.MemberInfo m : this.parseMembers(data)) {
               if (drawn < 14) {
                  int hx = headX + drawn * 24;
                  PlayerAvatarHelper.drawPlayerHead(graphics, m.uuid, m.name, hx, headY, 18);
                  if (mouseX >= hx && mouseX < hx + 18 && mouseY >= headY && mouseY < headY + 18) {
                     this.hoveredMemberTooltip = List.of(Component.literal("§b" + m.name), Component.literal("§7Rango: §e" + roleLabel(m.role)));
                  }

                  drawn++;
               }
            }

            int perksY = headY + 30;
            graphics.fill(cx + 20, perksY, cx + cardW - 20, perksY + 44, tintSurface(0x101518, 0.22F, 0x99));
            this.drawOutline(graphics, cx + 20, perksY, cardW - 40, 44, tintSurface(0x273436, 0.34F, 0xFF));
            graphics.drawString(this.font, "§eBeneficios de unirte:", cx + 28, perksY + 6, -11940, false);
            graphics.drawString(this.font, "§7• Acceso a la Bóveda compartida y PC de almacenamiento de Pokémon.", cx + 28, perksY + 18, -3352620, false);
            graphics.drawString(
               this.font, "§7• Sinergia de gremio con bonos de EXP, CobbleDollars y Shiny al jugar juntos.", cx + 28, perksY + 30, -3352620, false
            );
         } else {
            this.renderCard(graphics, cx, cy, cardW, cardH, "FUNDAR UN NUEVO GREMIO", -10238030);
            graphics.drawString(this.font, "Crea tu propio gremio para compartir bóveda, PC y activar la sinergia de grupo.", cx + 20, cy + 22, -6443350, false);
            int formY = cy + 32;
            graphics.drawString(this.font, "Nombre del gremio (2 a 24 caracteres):", cx + 30, formY + 4, -3352620, false);
            graphics.drawString(this.font, "Color identificativo:", cx + 30, formY + 40, -3352620, false);
            int barW = cardW - 130;
            int swatchX = cx + 30 + barW + 12;
            int swatchY = formY + 52;
            this.drawSelectedColor(graphics, swatchX, swatchY, this.draftColor);
            String hexText = "#" + (this.draftColor.startsWith("#") ? this.draftColor.substring(1) : this.draftColor).toUpperCase(Locale.ROOT);
            graphics.drawString(this.font, hexText, swatchX, swatchY + 26, -3352620, false);
            int prevW = 200;
            int prevX = cx + (cardW - prevW) / 2;
            int prevY = formY + 110;
            graphics.drawString(this.font, "Vista previa del gremio:", prevX, prevY - 12, -7364196, false);
            graphics.fill(prevX, prevY, prevX + prevW, prevY + 24, tintSurface(0x111718, 0.22F, 0xFF));
            this.drawOutline(graphics, prevX, prevY, prevW, 24, accentColor());
            this.drawGuildIcon(graphics, this.draftIcon, prevX + 6, prevY + 3, 18, accentColor());
            graphics.drawString(this.font, this.fit(this.draftName.isBlank() ? "Ej. Antau maldito abandonico!" : this.draftName, prevW - 32), prevX + 28, prevY + 7, -1, false);
         }
      } else {
         int cardY = this.panelY + 46;
         int cardHeight = 56;
         int gap = 8;
         int cardWidth = (this.contentWidth - 2 * gap) / 3;
         this.renderCard(graphics, this.contentX, cardY, cardWidth, cardHeight, "BANCO GREMIAL", -11940);
         graphics.drawString(this.font, this.fit("$ " + data.balance(), cardWidth - 16), this.contentX + 10, cardY + 22, -11940, false);
         graphics.drawString(this.font, this.fit("Cartera: $ " + data.walletBalance(), cardWidth - 16), this.contentX + 10, cardY + 38, -6369041, false);
         int card2X = this.contentX + cardWidth + gap;
         this.renderCard(graphics, card2X, cardY, cardWidth, cardHeight, "MIEMBROS", -10238030);
         long onlineCount = this.countOnlineMembers(data);
         graphics.drawString(this.font, onlineCount + " / " + data.memberCount() + " online", card2X + 10, cardY + 22, -1, false);
         graphics.fill(card2X + cardWidth - 16, cardY + 23, card2X + cardWidth - 10, cardY + 29, -13381254);
         int headX = card2X + 10;
         int headY = cardY + 35;
         int headsDrawn = 0;
         int maxHeads = Math.max(1, (cardWidth - 20) / 20);

         for (GuildScreen.MemberInfo mx : this.parseMembers(data)) {
            if (mx.online && headsDrawn < maxHeads) {
               int hx = headX + headsDrawn * 18;
               PlayerAvatarHelper.drawPlayerHead(graphics, mx.uuid, mx.name, hx, headY, 16);
               if (mouseX >= hx && mouseX < hx + 16 && mouseY >= headY && mouseY < headY + 16) {
                  this.hoveredMemberTooltip = List.of(Component.literal("§b" + mx.name), Component.literal("§aEn línea"));
               }

               headsDrawn++;
            }
         }

         int card3X = card2X + cardWidth + gap;
         int card3W = this.contentWidth - 2 * (cardWidth + gap);
         this.renderCard(graphics, card3X, cardY, card3W, cardHeight, "TU RANGO", accentColor());
         String roleStr = roleLabel(data.role());
         graphics.drawString(this.font, roleStr, card3X + 10, cardY + 22, roleColor(data.role()), false);
         String myRole = data.role();

         String permDesc = switch (myRole) {
            case "LEADER" -> "Control total";
            case "OFFICER" -> "Gestión y banco";
            default -> "Acceso de miembro";
         };
         graphics.drawString(this.font, permDesc, card3X + 10, cardY + 38, -6443350, false);
         int bankY = cardY + cardHeight + 8;
         int bankH = 46;
         this.renderCard(graphics, this.contentX, bankY, this.contentWidth, bankH, "TRANSFERENCIAS DE BANCO", -11940);
         int chatY = bankY + bankH + 8;
         int chatHeight = this.panelY + this.panelHeight - chatY - 10;
         int chatX = this.contentX;
         int chatW = this.contentWidth;
         this.renderCard(graphics, chatX, chatY, chatW, chatHeight, "CHAT DEL GREMIO", accentColor());
         int inputH = 18;
         int inputY = chatY + chatHeight - inputH - 5;
         int msgAreaX = chatX + 6;
         int msgAreaY = chatY + 18;
         int msgAreaW = chatW - 12;
         int msgAreaH = inputY - msgAreaY - 4;
         graphics.fill(msgAreaX, msgAreaY, msgAreaX + msgAreaW, msgAreaY + msgAreaH, tintSurface(0x111718, 0.22F, 0xFF));
         this.drawOutline(graphics, msgAreaX, msgAreaY, msgAreaW, msgAreaH, tintSurface(0x2A383A, 0.36F, 0xFF));
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
            if (!sender.isBlank()) {
               formatted = (timeStr.isBlank() ? "" : "§8[" + timeStr + "] ") + "§e" + sender + ": §f" + text;
            } else {
               formatted = text;
            }

            int my = msgAreaY + 4 + (mxx - startIdx) * lineHeight;
            graphics.drawString(this.font, this.fit(formatted, msgAreaW - 6), msgAreaX + 4, my, -1, false);
         }
      }
   }

   private void renderMembers(GuiGraphics graphics, GuildSyncPayload data, int mouseX, int mouseY) {
      int listTop = this.panelY + 76;
      int rowHeight = 28;
      int visibleCount = Math.max(1, (this.panelHeight - 110) / rowHeight);
      List<GuildScreen.MemberInfo> filtered = this.getFilteredMembers(data);
      int maxOffset = Math.max(0, filtered.size() - visibleCount);
      this.memberScrollOffset = Math.min(this.memberScrollOffset, maxOffset);
      if (filtered.isEmpty()) {
         graphics.drawString(this.font, "No se encontraron miembros.", this.contentX + 8, listTop + 14, -6443350, false);
      } else {
         for (int i = 0; i < visibleCount && this.memberScrollOffset + i < filtered.size(); i++) {
            GuildScreen.MemberInfo m = filtered.get(this.memberScrollOffset + i);
            int rowY = listTop + i * rowHeight;
            int rowW = this.contentWidth;
            graphics.fill(this.contentX, rowY, this.contentX + rowW, rowY + rowHeight - 2, tintSurface(0x192125, 0.22F, 0xFF));
            graphics.fill(this.contentX, rowY, this.contentX + 2, rowY + rowHeight - 2, roleColor(m.role));
            PlayerAvatarHelper.drawPlayerHead(graphics, m.uuid, m.name, this.contentX + 6, rowY + 3, 20);
            if (mouseX >= this.contentX + 6 && mouseX < this.contentX + 26 && mouseY >= rowY + 3 && mouseY < rowY + 23) {
               this.hoveredMemberTooltip = List.of(Component.literal("§b" + m.name), Component.literal("§7Rango: §e" + roleLabel(m.role)));
            }

            graphics.drawString(this.font, this.fit(m.name, 105), this.contentX + 32, rowY + 5, -1, false);
            int statusDotColor = m.online ? -13381254 : -8944253;
            graphics.fill(this.contentX + 142, rowY + 9, this.contentX + 147, rowY + 14, statusDotColor);
            this.drawRoleBadge(graphics, this.contentX + 155, rowY + 4, m.role);
            String statusText = m.online ? "En línea" : "Desconectado";
            int statusColor = m.online ? -13381254 : -7825516;
            int statusX = this.contentX + 225;
            int statusY = rowY + 7;
            int textW = this.font.width(statusText);
            int textH = 10;
            graphics.drawString(this.font, statusText, statusX, statusY, statusColor, false);
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

               this.hoveredMemberTooltip = List.of(Component.literal("§6Última conexión:"), Component.literal("§f" + timeInfo));
            }
         }

         String footerText = "Total: " + filtered.size() + " miembros";
         graphics.drawString(this.font, footerText, this.contentX, this.panelY + this.panelHeight - 18, -7825516, false);
         if (this.actionInput != null && this.actionInput.isFocused() && !this.playerSuggestions.isEmpty()) {
            int dropX = this.actionInput.getX();
            int dropY = this.actionInput.getY() + this.actionInput.getHeight() + 2;
            int dropW = this.actionInput.getWidth();
            int itemH = 16;
            int dropH = this.playerSuggestions.size() * itemH + 4;
            graphics.fill(dropX, dropY, dropX + dropW, dropY + dropH, tintSurface(0x101518, 0.24F, 0xF5));
            this.drawOutline(graphics, dropX, dropY, dropW, dropH, accentColor());

            for (int sIdx = 0; sIdx < this.playerSuggestions.size(); sIdx++) {
               String sName = this.playerSuggestions.get(sIdx);
               int iy = dropY + 2 + sIdx * itemH;
               boolean hovered = mouseX >= dropX && mouseX < dropX + dropW && mouseY >= iy && mouseY < iy + itemH;
               if (hovered) {
                  graphics.fill(dropX + 2, iy, dropX + dropW - 2, iy + itemH, 1146863783);
               }

               PlayerAvatarHelper.drawPlayerHead(graphics, null, sName, dropX + 4, iy + 2, 12);
               graphics.drawString(this.font, sName, dropX + 20, iy + 4, hovered ? -1 : -3352620, false);
            }
         }
      }
   }

   private void renderPC(GuiGraphics graphics, GuildSyncPayload data, int mouseX, int mouseY) {
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
      graphics.drawString(this.font, "CAJA GREMIO", startX, labelY, accentColor(), false);
      graphics.drawCenteredString(this.font, "EQUIPO", partyX + slotSize / 2, labelY, -10764121);
      graphics.drawCenteredString(this.font, "CAJA PC", privX + cols * slotSize / 2, labelY, -10764121);
      graphics.fill(startX - 3, gridY - 3, startX + cols * slotSize + 3, gridY + rows * slotSize + 3, tintSurface(0x151D20, 0.22F, 0xFF));
      this.drawOutline(graphics, startX - 3, gridY - 3, cols * slotSize + 6, rows * slotSize + 6, tintSurface(0x2A383A, 0.36F, 0xFF));
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
            graphics.fill(sx, sy, sx + slotSize - 2, sy + slotSize - 2, tintSurface(0x1D272A, 0.24F, 0xFF));
            this.drawOutline(graphics, sx, sy, slotSize - 2, slotSize - 2, tintSurface(0x2E3D40, 0.38F, 0xFF));
            boolean isHeld = this.heldPokemon != null
               && "GUILD".equals(this.heldPokemon.container())
               && this.heldPokemon.box() == this.selectedPcBox
               && this.heldPokemon.slot() == s;
            GuildScreen.PokemonSlotPreview px = currentBoxSlots.get(s);
            if (px != null && !isHeld) {
               ItemStack stack = getOrCreatePokemonStack(px.species(), px.shiny(), px.form());
               if (!stack.isEmpty()) {
                  graphics.flush();
                  RenderSystem.enableDepthTest();
                  graphics.pose().pushPose();
                  graphics.pose().translate((float)(sx + 2), (float)(sy + 2), 150.0F);
                  graphics.pose().scale(1.65F, 1.65F, 1.65F);
                  graphics.renderItem(stack, 0, 0);
                  graphics.pose().popPose();
                  graphics.flush();
               } else {
                  renderSlotPokemon3D(graphics, px.species(), px.shiny(), px.form(), sx + 2, sy + 2, slotSize - 6);
               }

               graphics.pose().pushPose();
               graphics.pose().translate(0.0F, 0.0F, 300.0F);
               if (px.shiny()) {
                  graphics.drawString(this.font, "S", sx + slotSize - 9, sy + 2, -11940, true);
               }

               String lvl = String.valueOf(px.level());
               graphics.drawString(this.font, lvl, sx + slotSize - this.font.width(lvl) - 3, sy + slotSize - 10, -3352620, true);
               graphics.pose().popPose();
            } else if (isHeld) {
               graphics.fill(sx, sy, sx + slotSize - 2, sy + slotSize - 2, 861651111);
               this.drawOutline(graphics, sx, sy, slotSize - 2, slotSize - 2, 1717289127);
            }

            if (mouseX >= sx && mouseX < sx + slotSize - 2 && mouseY >= sy && mouseY < sy + slotSize - 2) {
               if (this.heldPokemon != null && this.heldPokemon.restricted()) {
                  graphics.fill(sx, sy, sx + slotSize - 2, sy + slotSize - 2, 1726434364);
                  this.drawOutline(graphics, sx, sy, slotSize - 2, slotSize - 2, -56798);
               } else {
                  graphics.fill(sx, sy, sx + slotSize - 2, sy + slotSize - 2, 1146863783);
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

      graphics.drawCenteredString(this.font, partyOccupied + "/6", partyX + slotSize / 2, boxTabY + 5, -6443350);
      graphics.fill(partyX - 3, gridY - 3, partyX + slotSize + 3, gridY + 6 * slotSize + 3, tintSurface(0x151D20, 0.22F, 0xFF));
      this.drawOutline(graphics, partyX - 3, gridY - 3, slotSize + 6, 6 * slotSize + 6, tintSurface(0x2A383A, 0.36F, 0xFF));

      for (int ix = 0; ix < 6; ix++) {
         int py = gridY + ix * slotSize;
         graphics.fill(partyX, py, partyX + slotSize - 2, py + slotSize - 2, tintSurface(0x1D272A, 0.24F, 0xFF));
         this.drawOutline(graphics, partyX, py, slotSize - 2, slotSize - 2, tintSurface(0x2E3D40, 0.38F, 0xFF));
         boolean isHeldx = this.heldPokemon != null && "PARTY".equals(this.heldPokemon.container()) && this.heldPokemon.slot() == ix;
         if (party != null && !isHeldx) {
            Pokemon pxx = party.get(ix);
            if (pxx != null) {
               ItemStack stackx = PokemonItem.from(pxx, 1);
               if (!stackx.isEmpty()) {
                  graphics.pose().pushPose();
                  graphics.pose().translate((float)(partyX + 2), (float)(py + 2), 0.0F);
                  graphics.pose().scale(1.65F, 1.65F, 1.0F);
                  graphics.renderItem(stackx, 0, 0);
                  graphics.pose().popPose();
               } else {
                  graphics.fill(partyX + 3, py + 3, partyX + slotSize - 5, py + slotSize - 5, -13807798);
                  String name = pxx.getSpecies().getName();
                  String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
                  graphics.drawCenteredString(this.font, initial, partyX + slotSize / 2, py + (slotSize - 8) / 2, -10764121);
               }

               graphics.pose().pushPose();
               graphics.pose().translate(0.0F, 0.0F, 300.0F);
               if (pxx.getShiny()) {
                  graphics.drawString(this.font, "S", partyX + slotSize - 9, py + 2, -11940, true);
               }

               String lvl = String.valueOf(pxx.getLevel());
               graphics.drawString(this.font, lvl, partyX + slotSize - this.font.width(lvl) - 3, py + slotSize - 10, -3352620, true);
               graphics.pose().popPose();
            }
         } else if (isHeldx) {
            graphics.fill(partyX, py, partyX + slotSize - 2, py + slotSize - 2, 861651111);
            this.drawOutline(graphics, partyX, py, slotSize - 2, slotSize - 2, 1717289127);
         }

         if (mouseX >= partyX && mouseX < partyX + slotSize - 2 && mouseY >= py && mouseY < py + slotSize - 2) {
            graphics.fill(partyX, py, partyX + slotSize - 2, py + slotSize - 2, 1146863783);
            this.drawOutline(graphics, partyX, py, slotSize - 2, slotSize - 2, -10764121);
         }
      }

      ClientPC clientPc = this.getClientPC();
      ClientBox clientBox = clientPc != null && privatePcBox < clientPc.getBoxes().size() ? (ClientBox)clientPc.getBoxes().get(privatePcBox) : null;
      int totalPcBoxes = clientPc != null && clientPc.getBoxes().size() > 0 ? clientPc.getBoxes().size() : 1;
      graphics.drawCenteredString(this.font, "Caja " + (privatePcBox + 1) + " / " + totalPcBoxes, privX + cols * slotSize / 2, boxTabY + 5, -1905690);
      graphics.fill(privX - 3, gridY - 3, privX + cols * slotSize + 3, gridY + rows * slotSize + 3, tintSurface(0x151D20, 0.22F, 0xFF));
      this.drawOutline(graphics, privX - 3, gridY - 3, cols * slotSize + 6, rows * slotSize + 6, tintSurface(0x2A383A, 0.36F, 0xFF));

      for (int r = 0; r < rows; r++) {
         for (int c = 0; c < cols; c++) {
            int sxx = r * cols + c;
            int sxxx = privX + c * slotSize;
            int syx = gridY + r * slotSize;
            graphics.fill(sxxx, syx, sxxx + slotSize - 2, syx + slotSize - 2, tintSurface(0x1D272A, 0.24F, 0xFF));
            this.drawOutline(graphics, sxxx, syx, slotSize - 2, slotSize - 2, tintSurface(0x2E3D40, 0.38F, 0xFF));
            boolean isHeldxx = this.heldPokemon != null
               && "PC".equals(this.heldPokemon.container())
               && this.heldPokemon.box() == privatePcBox
               && this.heldPokemon.slot() == sxx;
            if (clientBox != null && sxx < clientBox.getSlots().size() && !isHeldxx) {
               Pokemon pxx = (Pokemon)clientBox.getSlots().get(sxx);
               if (pxx != null) {
                  ItemStack stackxx = PokemonItem.from(pxx, 1);
                  if (!stackxx.isEmpty()) {
                     graphics.pose().pushPose();
                     graphics.pose().translate((float)(sxxx + 2), (float)(syx + 2), 0.0F);
                     graphics.pose().scale(1.65F, 1.65F, 1.0F);
                     graphics.renderItem(stackxx, 0, 0);
                     graphics.pose().popPose();
                  } else {
                     graphics.fill(sxxx + 3, syx + 3, sxxx + slotSize - 5, syx + slotSize - 5, -13807798);
                     String name = pxx.getSpecies().getName();
                     String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
                     graphics.drawCenteredString(this.font, initial, sxxx + slotSize / 2, syx + (slotSize - 8) / 2, -10764121);
                  }

                  graphics.pose().pushPose();
                  graphics.pose().translate(0.0F, 0.0F, 300.0F);
                  if (pxx.getShiny()) {
                     graphics.drawString(this.font, "S", sxxx + slotSize - 9, syx + 2, -11940, true);
                  }

                  String lvl = String.valueOf(pxx.getLevel());
                  graphics.drawString(this.font, lvl, sxxx + slotSize - this.font.width(lvl) - 3, syx + slotSize - 10, -3352620, true);
                  graphics.pose().popPose();
               }
            } else if (isHeldxx) {
               graphics.fill(sxxx, syx, sxxx + slotSize - 2, syx + slotSize - 2, 861651111);
               this.drawOutline(graphics, sxxx, syx, slotSize - 2, slotSize - 2, 1717289127);
            }

            if (mouseX >= sxxx && mouseX < sxxx + slotSize - 2 && mouseY >= syx && mouseY < syx + slotSize - 2) {
               graphics.fill(sxxx, syx, sxxx + slotSize - 2, syx + slotSize - 2, 1146863783);
               this.drawOutline(graphics, sxxx, syx, slotSize - 2, slotSize - 2, -10764121);
            }
         }
      }
   }

   private void renderPCTooltip(GuiGraphics graphics, GuildSyncPayload data, int mouseX, int mouseY) {
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

   private void renderLivePokemonTooltip(GuiGraphics graphics, Pokemon p, int mouseX, int mouseY) {
      List<Component> tooltip = new ArrayList<>();
      String title = (p.getShiny() ? "[Shiny] " : "") + p.getDisplayName(false).getString();
      tooltip.add(Component.literal("§b§l" + title));
      tooltip.add(Component.literal("§7Especie: §f" + capitalize(p.getSpecies().getName()) + " §7| Nivel: §e" + p.getLevel()));
      String form = p.getForm().getName();
      if (!form.isBlank() && !form.equals("normal") && !form.equals("standard")) {
         tooltip.add(Component.literal("§7Forma: §f" + form));
      }

      if (p.getShiny()) {
         tooltip.add(Component.literal("§6Variocolor"));
      }

      EVs evs = p.getEvs();
      tooltip.add(
         Component.literal(
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
         Component.literal(
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
         tooltip.add(Component.literal("§eMovimientos:"));

         for (Move m : moves) {
            tooltip.add(Component.literal(" §7• §f" + m.getTemplate().getDisplayName().getString() + " §8(" + m.getCurrentPp() + "/" + m.getMaxPp() + " PP)"));
         }
      }

      tooltip.add(Component.literal("§8[Shift+Clic] Transferencia rápida"));
      graphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
   }

   private void renderPreviewPokemonTooltip(GuiGraphics graphics, GuildScreen.PokemonSlotPreview p, int mouseX, int mouseY) {
      List<Component> tooltip = new ArrayList<>();
      String rawName = p.displayName();
      if (rawName == null || rawName.isBlank() || rawName.equalsIgnoreCase("normal") || rawName.equalsIgnoreCase("standard")) {
         rawName = capitalize(p.species());
      }

      String title = (p.shiny() ? "[Shiny] " : "") + rawName;
      tooltip.add(Component.literal("§b§l" + title));
      tooltip.add(Component.literal("§7Especie: §f" + capitalize(p.species()) + " §7| Nivel: §e" + p.level()));
      if (!p.form().isBlank() && !p.form().equals("normal") && !p.form().equals("standard")) {
         tooltip.add(Component.literal("§7Forma: §f" + p.form()));
      }

      if (p.shiny()) {
         tooltip.add(Component.literal("§6Variocolor"));
      }

      tooltip.add(Component.literal("§d\ud83d\udc64 Depositado por: §e" + p.depositor()));
      if (p.evs() != null && !p.evs().isBlank()) {
         String[] evVals = p.evs().split("/");
         if (evVals.length == 6) {
            tooltip.add(
               Component.literal(
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
               Component.literal(
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
         tooltip.add(Component.literal("§eMovimientos:"));

         for (String mStr : p.moves().split(";")) {
            String[] mParts = mStr.split(",");
            if (mParts.length >= 3) {
               tooltip.add(Component.literal(" §7• §f" + mParts[0] + " §8(" + mParts[1] + "/" + mParts[2] + " PP)"));
            } else if (mParts.length >= 1 && !mParts[0].isBlank()) {
               tooltip.add(Component.literal(" §7• §f" + mParts[0]));
            }
         }
      }

      graphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
   }

   private void renderWaypoints(GuiGraphics graphics, GuildSyncPayload data) {
      int halfWidth = (this.contentWidth - 12) / 2;
      int listTop = this.panelY + 70;
      graphics.drawString(this.font, "MIS WAYPOINTS (XAERO / WAYSTONES)", this.contentX, listTop, -1774361, false);
      graphics.drawString(this.font, "WAYPOINTS COMPARTIDOS", this.contentX + halfWidth + 12, listTop, -1774361, false);
      graphics.fill(this.contentX, listTop + 13, this.contentX + halfWidth, listTop + 14, tintSurface(0x354445, 0.38F, 0xFF));
      graphics.fill(this.contentX + halfWidth + 12, listTop + 13, this.contentX + this.contentWidth, listTop + 14, tintSurface(0x354445, 0.38F, 0xFF));
      List<XaeroWaypointBridge.LocalWaypoint> filteredLocal = this.filterLocalWaypoints(this.localWaypoints, this.waypointDimFilter);
      if (filteredLocal.isEmpty()) {
         String message = (XaeroWaypointBridge.isAvailable() || WaystonesWaypointBridge.isAvailable())
            ? "Sin waypoints (" + DIM_FILTERS[this.waypointDimFilter] + ")"
            : "Ni Xaero ni Waystones detectados";
         graphics.drawString(this.font, message, this.contentX, listTop + 24, -6443350, false);
      }

      List<String> rawShared = new ArrayList<>(
         data.waypoints().stream().filter(encoded -> encoded.split("\\|", 9).length == 9).filter(encoded -> encoded.split("\\|", 9)[1].equals("true")).toList()
      );
      List<String> filteredShared = this.filterSharedWaypoints(rawShared, this.waypointDimFilter);
      if (filteredShared.isEmpty()) {
         graphics.drawString(
            this.font, "No hay rutas (" + DIM_FILTERS[this.waypointDimFilter] + ")", this.contentX + halfWidth + 12, listTop + 24, -6443350, false
         );
      }
   }

   private void renderLog(GuiGraphics graphics, GuildSyncPayload data, int mouseX, int mouseY) {
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
            graphics.drawString(this.font, emptyMsg, this.contentX, listTop + 10, -6443350, false);
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
         graphics.drawString(this.font, "No hay eventos registrados recientemente.", this.contentX, listTop + 10, -6443350, false);
      }
   }

   private void renderCustomize(GuiGraphics graphics, GuildSyncPayload data) {
      boolean isLeader = "LEADER".equals(data.role());
      if (isLeader) {
         int card1Y = this.panelY + 44;
         int card1H = 46;
         this.renderCard(graphics, this.contentX, card1Y, this.contentWidth, card1H, "NOMBRE DEL GREMIO", -10238030);
         int badgeX = this.contentX + this.contentWidth - 116;
         int badgeY = card1Y + 10;
         int badgeW = 106;
         int badgeH = 28;
         graphics.fill(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH, tintSurface(0x111718, 0.22F, 0xFF));
         this.drawOutline(graphics, badgeX, badgeY, badgeW, badgeH, accentColor());
         this.drawGuildIcon(graphics, this.draftIcon, badgeX + 4, badgeY + 4, 20, accentColor());
         graphics.drawString(this.font, this.fit(this.draftName.isBlank() ? "Ej. Antau maldito abandonico!" : this.draftName, badgeW - 30), badgeX + 28, badgeY + 10, -1, false);
         int card2Y = card1Y + card1H + 6;
         int card2H = 78;
         this.renderCard(graphics, this.contentX, card2Y, this.contentWidth, card2H, "COLOR DEL GREMIO (SELECTOR VISUAL)", -10238030);
         int barW = this.contentWidth - 88;
         int prevBoxX = this.contentX + 10 + barW + 12;
         int prevBoxY = card2Y + 20;
         this.drawSelectedColor(graphics, prevBoxX, prevBoxY, this.draftColor);
         String hexText = "#" + (this.draftColor.startsWith("#") ? this.draftColor.substring(1) : this.draftColor).toUpperCase(Locale.ROOT);
         graphics.drawString(this.font, hexText, prevBoxX, prevBoxY + 26, -3352620, false);
      } else {
         int cardW = Math.min(360, this.contentWidth - 40);
         int cardH = 100;
         int cx = this.contentX + (this.contentWidth - cardW) / 2;
         int cy = this.panelY + (this.panelHeight - cardH) / 2;
         graphics.fill(cx, cy, cx + cardW, cy + cardH, tintSurface(0x141B1E, 0.20F, 0xFF));
         this.drawOutline(graphics, cx, cy, cardW, cardH, tintSurface(0x2A393C, 0.34F, 0xFF));
         graphics.drawCenteredString(this.font, "\ud83d\udd12 ACCESO RESTRINGIDO", cx + cardW / 2, cy + 24, -2069387);
         graphics.drawCenteredString(this.font, "Solo el líder del gremio tiene permisos para", cx + cardW / 2, cy + 44, -3352620);
         graphics.drawCenteredString(this.font, "modificar el nombre y el color del gremio.", cx + cardW / 2, cy + 58, -6443350);
      }
   }

   private void renderCard(GuiGraphics graphics, int x, int y, int width, int height, String title, int titleColor) {
      graphics.fill(x, y, x + width, y + height, tintSurface(0x171F22, 0.22F, 0xFF));
      this.drawOutline(graphics, x, y, width, height, tintSurface(0x2A383A, 0.36F, 0xFF));
      graphics.drawString(this.font, this.fit(title, width - 16), x + 8, y + 8, titleColor, false);
   }

   private void drawOutline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
      graphics.fill(x, y, x + width, y + 1, color);
      graphics.fill(x, y + height - 1, x + width, y + height, color);
      graphics.fill(x, y, x + 1, y + height, color);
      graphics.fill(x + width - 1, y, x + width, y + height, color);
   }

   private void drawRoleBadge(GuiGraphics graphics, int x, int y, String role) {
      int color = roleColor(role);
      String label = roleLabel(role).toUpperCase();
      int pillW = this.font.width(label) + 8;
      graphics.fill(x, y, x + pillW, y + 13, tintSurface(0x242F31, 0.28F, 0xFF));
      this.drawOutline(graphics, x, y, pillW, 13, color);
      graphics.drawString(this.font, label, x + 4, y + 3, color, false);
   }

   private static int roleColor(String role) {
      return switch (role) {
         case "LEADER" -> -10496;
         case "OFFICER" -> -11141121;
         default -> -3309774;
      };
   }

   static int tintSurface(int baseRgb, float mix, int alpha) {
      int acc = accentColor();
      int br = (baseRgb >> 16) & 0xFF;
      int bg = (baseRgb >> 8) & 0xFF;
      int bb = baseRgb & 0xFF;
      int ar = (acc >> 16) & 0xFF;
      int ag = (acc >> 8) & 0xFF;
      int ab = acc & 0xFF;
      int r = Math.min(255, Math.max(0, Math.round(br * (1.0F - mix) + ar * mix)));
      int g = Math.min(255, Math.max(0, Math.round(bg * (1.0F - mix) + ag * mix)));
      int b = Math.min(255, Math.max(0, Math.round(bb * (1.0F - mix) + ab * mix)));
      return ((alpha & 0xFF) << 24) | (r << 16) | (g << 8) | b;
   }

   private static int blendColor(int rgb, float factor) {
      int r = Math.min(255, Math.max(0, Math.round(((rgb >> 16) & 0xFF) * factor)));
      int g = Math.min(255, Math.max(0, Math.round(((rgb >> 8) & 0xFF) * factor)));
      int b = Math.min(255, Math.max(0, Math.round((rgb & 0xFF) * factor)));
      return 0xFF000000 | (r << 16) | (g << 8) | b;
   }

   private static String[] guildIconSprite(String icon) {
      String key = icon == null ? "pokeball" : icon.toLowerCase(Locale.ROOT).trim();
      return switch (key) {
         case "espada" -> new String[]{
            "........OO..",
            ".......OWWO.",
            "......OWAWO.",
            ".....OWAWO..",
            "....OWAWO...",
            "..OOWAWO....",
            ".OGGAAO.....",
            "..OGGO......",
            "..ODOGO.....",
            ".ODDO.O.....",
            "OGGO........",
            ".OO........."
         };
         case "escudo" -> new String[]{
            "..OOOOOOOO..",
            ".OLLLLLLAAO.",
            ".OLAAWWAAAO.",
            ".OAAWGGWAAO.",
            ".OAWGGGGWAO.",
            ".OAAWGGWAAO.",
            ".ODAAWWAADO.",
            "..ODAAAADO..",
            "..ODDDDDDO..",
            "...ODDDDO...",
            "....ODDO....",
            ".....OO....."
         };
         case "dragon" -> new String[]{
            "..OO....OO..",
            ".OGAO..OAGO.",
            "..OAAOOAAO..",
            "..OALLLAAO..",
            ".OALLWLLAAO.",
            ".OALROOAAAO.",
            ".OAAAAAAAOO.",
            "..OAAADDDGO.",
            "..OAAADDDDO.",
            "...OAADDO...",
            "...OADDDO...",
            "....OOOO...."
         };
         case "rayo" -> new String[]{
            "......OOOO..",
            ".....OWWGO..",
            "....OWWGGO..",
            "...OWWGGGO..",
            "..OWWGGGO...",
            ".OOWWWWWGO..",
            ".OGGGGGGGGO.",
            "....OOGGGO..",
            "...OOGGGO...",
            "..OOGGGO....",
            "..OGGGO.....",
            "..OOOO......"
         };
         case "estrella" -> new String[]{
            ".....OO.....",
            "....OWGO....",
            "....OGGO....",
            ".OOOWGGWOOO.",
            "OWGGGGGGGGGO",
            ".OGGGGGGGGO.",
            "..OGGGGGGO..",
            "..OGGGGGGO..",
            ".OGGGOOGGGO.",
            ".OGGO..OGGO.",
            ".OOO....OOO.",
            "............"
         };
         case "corazon" -> new String[]{
            "............",
            ".OOO....OOO.",
            "OAAAO..OAAAO",
            "OAWAAOOAAAAO",
            "OAWAAAAAAAAO",
            "OAAAAAAAAADO",
            ".OAAAAAAADO.",
            "..OAAAAADO..",
            "...OAAADO...",
            "....OADO....",
            ".....OO.....",
            "............"
         };
         default -> new String[]{
            ".....OOOOOO.....",
            "...OOLLLLAAOO...",
            "..OLLHHAAAAADO..",
            ".OLHHAAAAAAADDO.",
            ".OLHAAAAAAAADDO.",
            "OAAAAAAAAAAADDDO",
            "OAAADDDDDDDDAAAO",
            "OOOOOOOWWOOOOOOO",
            "OOOOOOWBBWOOOOOO",
            "OSSSSOOWWOOSSSSO",
            ".OSSSSOOOOSSSSO.",
            ".OSSSSSSSSSSSSO.",
            "..OSSSSSSSSSSO..",
            "...OSSSSSSSSO...",
            ".....OOOOOO.....",
            "................"
         };
      };
   }

   private void drawGuildIcon(GuiGraphics graphics, String icon, int x, int y, int size, int color) {
      graphics.fill(x, y, x + size, y + size, tintSurface(0x121720, 0.26F, 0xFF));
      this.drawOutline(graphics, x, y, size, size, blendColor(color, 0.78F));
      String[] sprite = guildIconSprite(icon);
      int grid = sprite.length;
      int pad = size >= 20 ? 2 : 1;
      int inner = Math.max(grid, size - pad * 2);
      int light = blendColor(color, 1.30F);
      int dark = blendColor(color, 0.58F);

      for (int row = 0; row < grid; row++) {
         String line = sprite[row];
         int py1 = y + pad + (row * inner) / grid;
         int py2 = y + pad + ((row + 1) * inner) / grid;
         if (py2 <= py1) py2 = py1 + 1;
         for (int col = 0; col < grid; col++) {
            char c = line.charAt(col);
            if (c == '.') continue;
            int px1 = x + pad + (col * inner) / grid;
            int px2 = x + pad + ((col + 1) * inner) / grid;
            if (px2 <= px1) px2 = px1 + 1;
            int pxColor = switch (c) {
               case 'O' -> 0xFF0A0D14;
               case 'A' -> 0xFF000000 | (color & 0xFFFFFF);
               case 'L' -> light;
               case 'D' -> dark;
               case 'H' -> 0xFFF8FAFC;
               case 'B' -> 0xFFE2E8F0;
               case 'W' -> 0xFFFFFFFF;
               case 'S' -> 0xFFC5CEDC;
               case 'G' -> 0xFFF5B72F;
               case 'R' -> 0xFFE63946;
               default -> 0xFF000000 | (color & 0xFFFFFF);
            };
            graphics.fill(px1, py1, px2, py2, pxColor);
         }
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

   private void drawSelectedColor(GuiGraphics graphics, int x, int y, String hex) {
      graphics.fill(x, y, x + 20, y + 20, -10724259);
      graphics.fill(x + 1, y + 1, x + 19, y + 19, 0xFF3A4048);
      int raw = GuildClientState.parseColor(hex.startsWith("#") ? hex : "#" + hex);
      graphics.fill(x + 2, y + 2, x + 18, y + 18, softenWithGray(raw));
   }

   private static void renderSlotPokemon3D(GuiGraphics graphics, String species, boolean shiny, String form, int x, int y, int size) {
      try {
         String clean = species == null ? "" : species.toLowerCase(Locale.ROOT).replace(" ", "_");
         String shortName = clean.contains(":") ? clean.substring(clean.indexOf(':') + 1) : clean;
         Species speciesObj = PokemonSpecies.getByName(shortName);
         if (speciesObj == null) {
            speciesObj = PokemonSpecies.getByIdentifier(ResourceLocation.tryParse("cobblemon:" + shortName));
         }
         if (speciesObj == null) {
            speciesObj = PokemonSpecies.getByIdentifier(ResourceLocation.tryParse(clean));
         }
         if (speciesObj != null) {
            Set<String> aspects = new HashSet<>();
            if (shiny) aspects.add("shiny");
            if (form != null && !form.isBlank() && !form.equalsIgnoreCase("normal") && !form.equalsIgnoreCase("standard")) {
               for (String part : form.trim().split("[\\s,]+")) {
                  String lower = part.toLowerCase(Locale.ROOT);
                  if (!lower.equals("normal") && !lower.equals("standard")) aspects.add(lower);
               }
            }
            FloatingState state = new FloatingState();
            state.setCurrentAspects(aspects);
            graphics.flush();
            RenderSystem.enableDepthTest();
            graphics.pose().pushPose();
            graphics.pose().translate(x + size / 2.0, y + size / 2.0 + 4.0, 250.0);
            Quaternionf rot = new Quaternionf().rotationXYZ((float)Math.toRadians(12.0), (float)Math.toRadians(35.0), 0.0f);
            PokemonGuiUtilsKt.drawProfilePokemon(
               new RenderablePokemon(speciesObj, aspects, ItemStack.EMPTY),
               graphics.pose(),
               rot,
               PoseType.PROFILE,
               state,
               0.0f,
               Math.max(8.0f, size * 0.45f),
               ProfileTransformType.PROFILE,
               true,
               1.0f,
               1.0f,
               1.0f,
               1.0f,
               0.0f,
               0.0f,
               15728880
            );
            graphics.pose().popPose();
            graphics.flush();
            return;
         }
      } catch (Throwable ignored) {}
      graphics.fill(x + 1, y + 1, x + size - 1, y + size - 1, -13807798);
      String initial = species == null || species.isEmpty() ? "?" : species.substring(0, 1).toUpperCase(Locale.ROOT);
      graphics.drawCenteredString(net.minecraft.client.Minecraft.getInstance().font, initial, x + size / 2, y + (size - 8) / 2, -10764121);
   }

   private float openingProgress() {
      return !this.animateOpening ? 1.0F : Math.min(1.0F, (float)(System.nanoTime() - this.openedAtNanos) / 2.8E8F);
   }

   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      if (this.animateOpening && this.openingProgress() < 1.0F) {
         return true;
      } else {
         double localX = (mouseX - (double)this.renderX) / (double)this.uiScale;
         double localY = (mouseY - (double)this.renderY) / (double)this.uiScale;
         if (this.actionInput != null && this.actionInput.isFocused() && !this.playerSuggestions.isEmpty()) {
            int dropX = this.actionInput.getX();
            int dropY = this.actionInput.getY() + this.actionInput.getHeight() + 2;
            int dropW = this.actionInput.getWidth();
            int itemH = 16;
            int dropH = this.playerSuggestions.size() * itemH + 4;
            if (localX >= (double)dropX && localX < (double)(dropX + dropW) && localY >= (double)dropY && localY < (double)(dropY + dropH)) {
               int clickedIdx = (int)((localY - (double)dropY - 2.0) / (double)itemH);
               if (clickedIdx >= 0 && clickedIdx < this.playerSuggestions.size()) {
                  this.actionInput.setValue(this.playerSuggestions.get(clickedIdx));
                  this.draftAction = this.actionInput.getValue();
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

            boolean shift = hasShiftDown();

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
                              ItemStack stack = getOrCreatePokemonStack(preview.species(), preview.shiny(), preview.form());
                              this.heldPokemon = new GuildScreen.HeldPokemon(
                                 "GUILD",
                                 this.selectedPcBox,
                                 s,
                                 preview.species(),
                                 preview.level(),
                                 preview.shiny(),
                                 preview.form(),
                                 preview.displayName(),
                                 DesgraciamonGuilds.isRestrictedSpecies(preview.species()),
                                 stack
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
                           ItemStack stack = PokemonItem.from(px, 1);
                           this.heldPokemon = new GuildScreen.HeldPokemon(
                              "PARTY",
                              0,
                              ixx,
                              px.getSpecies().getName().toLowerCase(Locale.ROOT),
                              px.getLevel(),
                              px.getShiny(),
                              px.getForm().getName().toLowerCase(Locale.ROOT),
                              px.getDisplayName(false).getString(),
                              DesgraciamonGuilds.isRestrictedPokemon(px),
                              stack
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
                                 ItemStack stack = PokemonItem.from(pxx, 1);
                                 this.heldPokemon = new GuildScreen.HeldPokemon(
                                    "PC",
                                    privatePcBox,
                                    sx,
                                    pxx.getSpecies().getName().toLowerCase(Locale.ROOT),
                                    pxx.getLevel(),
                                    pxx.getShiny(),
                                    pxx.getForm().getName().toLowerCase(Locale.ROOT),
                                    pxx.getDisplayName(false).getString(),
                                    DesgraciamonGuilds.isRestrictedPokemon(pxx),
                                    stack
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
                           if (!DesgraciamonGuilds.isRestrictedPokemon(pxx)) {
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
                              this.warningBanner = "¡No se permiten Legendarios en el Gremio y tu equipo está lleno!";
                              this.warningBannerTicks = 70;
                              this.playDenySound();
                              return true;
                           }
                        }

                        return true;
                     }
                  }
               }
            }

            boolean clickedWidget = super.mouseClicked(mouseX, mouseY, button);
            if (!clickedWidget && this.heldPokemon != null) {
               this.heldPokemon = null;
               this.playPcDropSound();
               return true;
            } else {
               return clickedWidget;
            }
         }
      } else {
         return super.mouseClicked(mouseX, mouseY, button);
      }
   }

   private boolean isAnyEditBoxFocused() {
      return (this.nameInput != null && this.nameInput.isFocused())
         || (this.colorInput != null && this.colorInput.isFocused())
         || (this.actionInput != null && this.actionInput.isFocused())
         || (this.amountInput != null && this.amountInput.isFocused())
         || (this.chatInput != null && this.chatInput.isFocused())
         || (this.searchMemberInput != null && this.searchMemberInput.isFocused())
         || (this.motdEditInput != null && this.motdEditInput.isFocused());
   }

   public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
      if (this.chatInput == null || !this.chatInput.isFocused() || keyCode != 257 && keyCode != 335) {
         if (this.actionInput != null && this.actionInput.isFocused() && keyCode == 258 && !this.playerSuggestions.isEmpty()) {
            this.actionInput.setValue(this.playerSuggestions.get(0));
            this.draftAction = this.actionInput.getValue();
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

            if (this.tab == 3 && this.minecraft != null && this.minecraft.player != null && !this.isAnyEditBoxFocused()) {
               LocalPlayer player = this.minecraft.player;
               if (this.heldVaultItem == null && keyCode >= 49 && keyCode <= 57) {
                  String slotKey = this.findVaultSlotKeyAt(this.getLocalMouseX(), this.getLocalMouseY());
                  if (slotKey != null) {
                     String[] parts = slotKey.split("\\|", 2);
                     String targetContainer = parts[0];
                     int targetSlot = Integer.parseInt(parts[1]);
                     int hotbarSlot = keyCode - 49;
                     if (!("PLAYER".equals(targetContainer) && targetSlot == hotbarSlot)) {
                        this.applyLocalTransfer(targetContainer, targetSlot, "PLAYER", hotbarSlot, 64, true, player);
                        this.pendingVaultOps++;
                        this.lastVaultOpTimeMs = System.currentTimeMillis();
                        send("VAULT_HOTBAR_SWAP", targetContainer + "|" + targetSlot + "|" + hotbarSlot, 0);
                        this.playVaultSound();
                     }
                     return true;
                  }
               }

               if (this.minecraft.options.keyDrop.matches(keyCode, scanCode)) {
                  String targetContainer = null;
                  int targetSlot = -1;
                  if (this.heldVaultItem != null) {
                     targetContainer = this.heldVaultItem.container;
                     targetSlot = this.heldVaultItem.slot;
                  } else {
                     String slotKey = this.findVaultSlotKeyAt(this.getLocalMouseX(), this.getLocalMouseY());
                     if (slotKey != null) {
                        String[] parts = slotKey.split("\\|", 2);
                        targetContainer = parts[0];
                        targetSlot = Integer.parseInt(parts[1]);
                     }
                  }
                  if (targetContainer != null && targetSlot >= 0) {
                     ItemStack stack = this.getLocalSlot(targetContainer, targetSlot, player);
                     if (!stack.isEmpty()) {
                        boolean throwAll = hasControlDown();
                        int dropCount = throwAll ? stack.getCount() : 1;
                        ItemStack rem = stack.getCount() > dropCount ? stack.copyWithCount(stack.getCount() - dropCount) : ItemStack.EMPTY;
                        if ("VAULT".equals(targetContainer) && this.localVault != null && targetSlot < this.localVault.size()) {
                           this.localVault.set(targetSlot, rem);
                        } else if ("PLAYER".equals(targetContainer)) {
                           player.getInventory().setItem(targetSlot, rem);
                        }
                        if (this.heldVaultItem != null && this.heldVaultItem.container.equals(targetContainer) && this.heldVaultItem.slot == targetSlot) {
                           this.heldVaultItem.stack.shrink(dropCount);
                           if (this.heldVaultItem.stack.isEmpty()) {
                              this.heldVaultItem = null;
                           }
                        }
                        this.pendingVaultOps++;
                        this.lastVaultOpTimeMs = System.currentTimeMillis();
                        send("VAULT_THROW", targetContainer + "|" + targetSlot + "|" + (throwAll ? "1" : "0"), 0);
                        this.playVaultSound();
                        return true;
                     }
                  }
               }
            }

            return super.keyPressed(keyCode, scanCode, modifiers);
         }
      } else {
         this.sendChatMessage();
         return true;
      }
   }

   public boolean mouseReleased(double mouseX, double mouseY, int button) {
      double localX = (mouseX - (double)this.renderX) / (double)this.uiScale;
      double localY = (mouseY - (double)this.renderY) / (double)this.uiScale;
      if (this.tab == 3 && this.vaultDragButton == button && this.heldVaultItem != null && !this.vaultDragSlots.isEmpty()) {
         if (this.vaultDragMoved && this.vaultDragSlots.size() > 1 && this.minecraft != null && this.minecraft.player != null) {
            int per = this.computeVaultDragPerSlot();
            int remaining = this.heldVaultItem.stack.getCount();
            int totalUsed = 0;
            StringBuilder batchOps = new StringBuilder();
            for (String slotKey : this.vaultDragSlots) {
               if (remaining <= 0) break;
               String[] parts = slotKey.split("\\|", 2);
               String dstContainer = parts[0];
               int dstSlot = Integer.parseInt(parts[1]);
               int space = this.getVaultDragSlotSpace(dstContainer, dstSlot);
               int toMove = Math.min(Math.min(per, remaining), space);
               if (toMove <= 0) continue;
               boolean isSourceSlot = this.heldVaultItem.container.equals(dstContainer) && this.heldVaultItem.slot == dstSlot;
               if (isSourceSlot) {
                  this.heldVaultItem.remainingInSource += toMove;
                  totalUsed += toMove;
                  remaining -= toMove;
               } else {
                  int moved = this.applyLocalTransfer(
                     this.heldVaultItem.container, this.heldVaultItem.slot, dstContainer, dstSlot, toMove, false, this.minecraft.player
                  );
                  if (moved > 0) {
                     if (batchOps.length() > 0) {
                        batchOps.append(',');
                     }
                     batchOps.append(dstContainer).append(':').append(dstSlot).append(':').append(moved);
                     totalUsed += moved;
                     remaining -= moved;
                  }
               }
            }
            if (batchOps.length() > 0) {
               this.pendingVaultOps++;
               this.lastVaultOpTimeMs = System.currentTimeMillis();
               send("VAULT_BATCH_TRANSFER", this.heldVaultItem.container + "|" + this.heldVaultItem.slot + "|" + batchOps, 0);
            }
            if (totalUsed > 0) {
               this.heldVaultItem.stack.shrink(totalUsed);
               if (this.heldVaultItem.stack.isEmpty()) {
                  this.heldVaultItem = null;
               }
               this.playVaultSound();
            }
         } else if (this.minecraft != null && this.minecraft.player != null) {
            String firstKey = this.vaultDragSlots.iterator().next();
            String[] parts = firstKey.split("\\|", 2);
            String container = parts[0];
            int slot = Integer.parseInt(parts[1]);
            ItemStack stack = this.getLocalSlot(container, slot, this.minecraft.player);
            this.handleSlotClick(container, slot, stack, button, false, this.minecraft.player);
         }
         this.vaultDragButton = -1;
         this.vaultDragMoved = false;
         this.vaultDragSlots.clear();
         return true;
      }
      this.vaultDragButton = -1;
      this.vaultDragMoved = false;
      this.vaultDragSlots.clear();
      return super.mouseReleased(localX, localY, button);
   }

   public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
      double localX = (mouseX - (double)this.renderX) / (double)this.uiScale;
      double localY = (mouseY - (double)this.renderY) / (double)this.uiScale;
      if (this.tab == 3 && this.vaultDragButton == button && this.heldVaultItem != null) {
         String slotKey = this.findVaultSlotKeyAt(localX, localY);
         if (slotKey != null && this.canVaultDragInto(slotKey)) {
            int maxSlots = this.heldVaultItem.stack.getCount();
            if (this.vaultDragSlots.size() < maxSlots && this.vaultDragSlots.add(slotKey)) {
               if (this.vaultDragSlots.size() > 1) {
                  this.vaultDragMoved = true;
               }
            }
         }
         return true;
      }
      return super.mouseDragged(localX, localY, button, dragX / (double)this.uiScale, dragY / (double)this.uiScale);
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
      double localX = (mouseX - (double)this.renderX) / (double)this.uiScale;
      double localY = (mouseY - (double)this.renderY) / (double)this.uiScale;
      return this.handleMouseScrolled(localX, localY, horizontalAmount, verticalAmount);
   }

   private boolean handleMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
      if (this.tab == 0) {
         int chatX = this.contentX;
         int chatW = this.contentWidth;
         int chatY = this.panelY + 46 + 56 + 8 + 46 + 8;
         int chatH = this.panelY + this.panelHeight - chatY - 10;
         if (mouseX >= (double)chatX && mouseX <= (double)(chatX + chatW) && mouseY >= (double)chatY && mouseY <= (double)(chatY + chatH)) {
            this.chatScrollOffset = Math.max(0, this.chatScrollOffset + (verticalAmount > 0.0 ? 1 : -1));
            return true;
         }
      }

      if (this.tab == 1) {
         this.memberScrollOffset = Math.max(0, this.memberScrollOffset + (verticalAmount < 0.0 ? 1 : -1));
         this.rebuildWidgets();
         return true;
      } else {
         if (this.tab == 2) {
            ClientPC pc = this.getClientPC();
            if (pc != null && pc.getBoxes().size() > 0) {
               int total = pc.getBoxes().size();
               privatePcBox = (privatePcBox + (verticalAmount < 0.0 ? 1 : -1) + total) % total;
               this.rebuildWidgets();
               return true;
            }
         } else {
            if (this.tab == 4) {
               if (mouseX < (double)this.contentX + (double)this.contentWidth / 2.0) {
                  this.localWaypointScroll = Math.max(0, this.localWaypointScroll + (verticalAmount < 0.0 ? 1 : -1));
               } else {
                  this.sharedWaypointScroll = Math.max(0, this.sharedWaypointScroll + (verticalAmount < 0.0 ? 1 : -1));
               }

               this.rebuildWidgets();
               return true;
            }

            if (this.tab == 5) {
               this.logScrollOffset = Math.max(0, this.logScrollOffset + (verticalAmount < 0.0 ? 1 : -1));
               return true;
            }
         }

         return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
      }
   }

   public void renderBlurredBackground(float delta) {
   }

   public void renderMenuBackground(GuiGraphics graphics) {
   }

   public boolean isPauseScreen() {
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
         this.rebuildWidgets();
         ClientPlayNetworking.send(new GuildWaypointImportPayload(waypoint.name(), waypoint.dimension(), waypoint.x(), waypoint.y(), waypoint.z(), true));
      }
   }

   private String lastSyncedRole = "";

   void onSyncUpdated() {
      if (this.minecraft != null && this.minecraft.screen == this) {
         String newRole = GuildClientState.data.role() != null ? GuildClientState.data.role() : "";
         boolean roleChanged = !Objects.equals(this.lastSyncedRole, newRole);
         this.lastSyncedRole = newRole;
         if (roleChanged && "LEADER".equals(newRole)) {
            this.draftName = "";
         }

         if (this.pendingVaultOps > 0) {
            this.pendingVaultOps--;
         }
         if (this.pendingVaultOps == 0) {
            this.refreshLocalVaultFrom(GuildClientState.data);
            this.validateHeldVaultItem();
         }

         if (!roleChanged) {
            if (this.tab == 3 || this.tab == 6 || this.getFocused() instanceof EditBox) {
               return;
            }
         }

         this.rebuildWidgets();
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
            if (!added && this.minecraft.player != null) {
               this.minecraft.player.sendSystemMessage(Component.literal("No se pudo agregar a los waypoints personales de Xaero."));
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
            : DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.of("America/Caracas")).format(Instant.ofEpochMilli(uploadedAt));
      } catch (RuntimeException var3) {
         return "fecha desconocida";
      }
   }

   private EditBox addInput(int x, int y, int boxWidth, int boxHeight, String hint, int maxLength, String value) {
      EditBox box = new EditBox(this.font, x, y, boxWidth, boxHeight, Component.literal(hint));
      box.setMaxLength(maxLength);
      box.setHint(Component.literal(hint));
      box.setValue(value == null ? "" : value);
      this.addRenderableWidget(box);
      return box;
   }

   private void addAction(String label, int x, int y, int buttonWidth, int buttonHeight, Runnable action, boolean accent) {
      this.addRenderableWidget(new GuildScreen.ActionButton(label, x, y, buttonWidth, buttonHeight, action, accent));
   }

   private void createGuild() {
      this.rememberDrafts();
      String name = this.nameInput == null ? "" : GuildCommands.normalizeGuildName(this.nameInput.getValue());
      String color = this.draftColor != null && !this.draftColor.isBlank() ? this.draftColor.replace("#", "").trim() : "5BC0A7";
      if (name == null) {
         if (this.minecraft.player != null) {
            this.minecraft.player.sendSystemMessage(Component.literal("Usa un nombre de 2 a 24 caracteres (letras, números, espacios o guiones bajos)."));
         }
      } else {
         this.closeAfterCreate = true;
         send("CREATE", name + "|" + color, 0);
      }
   }

   private void saveGuildDetails() {
      this.rememberDrafts();
      if (this.nameInput != null && !this.nameInput.getValue().trim().isEmpty()) {
         String normalized = GuildCommands.normalizeGuildName(this.nameInput.getValue());
         if (normalized == null) {
            if (this.minecraft.player != null) {
               this.minecraft.player.sendSystemMessage(Component.literal("Usa un nombre de 2 a 24 caracteres (letras, números, espacios o guiones bajos)."));
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

      if (this.minecraft.player != null) {
         this.minecraft.player.sendSystemMessage(Component.literal("§a[Gremio] ¡Personalización actualizada!"));
      }
   }

   private void rememberDrafts() {
      if (this.nameInput != null) {
         this.draftName = this.nameInput.getValue();
      }

      if (this.colorInput != null) {
         this.draftColor = this.colorInput.getValue();
      }

      if (this.actionInput != null) {
         this.draftAction = this.actionInput.getValue();
      }

      if (this.amountInput != null) {
         this.draftAmount = this.amountInput.getValue();
      }

      if (this.searchMemberInput != null) {
         this.memberFilter = this.searchMemberInput.getValue();
      }

      if (this.motdEditInput != null) {
         this.draftMotd = this.motdEditInput.getValue();
      }
   }

   private static String value(EditBox input) {
      return input == null ? "" : input.getValue().trim();
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
      if (this.font.width(text) <= maxWidth) {
         return text;
      } else {
         String shortened = text;

         while (!shortened.isEmpty() && this.font.width(shortened + "...") > maxWidth) {
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

   private static ResourceLocation icon(int type) {
      String[] names = new String[]{"general", "members", "pc", "vault", "waypoints", "log", "customize"};
      return ResourceLocation.fromNamespaceAndPath("desgraciamon_guilds", "textures/gui/icons/" + names[type] + ".png");
   }

   private static void drawOriginalIcon(
      GuiGraphics graphics, ResourceLocation texture, int x, int y, int size, int sourceX, int sourceY, int sourceWidth, int sourceHeight
   ) {
      float scale = Math.min((float)size / (float)sourceWidth, (float)size / (float)sourceHeight);
      int drawWidth = Math.round((float)sourceWidth * scale);
      int drawHeight = Math.round((float)sourceHeight * scale);
      int drawX = x + (size - drawWidth) / 2;
      int drawY = y + (size - drawHeight) / 2;
      graphics.blit(texture, drawX, drawY, drawWidth, drawHeight, (float)sourceX, (float)sourceY, sourceWidth, sourceHeight, 512, 512);
   }

   private static int softenWithGray(int rgb) {
      int r = (((rgb >> 16) & 0xFF) * 60 + 58 * 40) / 100;
      int g = (((rgb >> 8) & 0xFF) * 60 + 64 * 40) / 100;
      int b = ((rgb & 0xFF) * 60 + 72 * 40) / 100;
      return 0xFF000000 | (r << 16) | (g << 8) | b;
   }

   static int accentColor() {
      return !GuildClientState.data.member() ? -10764121 : softenWithGray(GuildClientState.parseColor(GuildClientState.data.color()));
   }

   static int accentSurface() {
      int accent = accentColor();
      int red = (accent >> 16 & 0xFF) * 35 / 100 + 36;
      int green = (accent >> 8 & 0xFF) * 35 / 100 + 40;
      int blue = (accent & 0xFF) * 35 / 100 + 44;
      return 0xFF000000 | red << 16 | green << 8 | blue;
   }

   private final class ActionButton extends AbstractWidget {
      private final Runnable action;
      private final boolean accent;

      private ActionButton(String label, int x, int y, int buttonWidth, int buttonHeight, Runnable action, boolean accent) {
         super(x, y, buttonWidth, buttonHeight, Component.literal(label));
         this.action = action;
         this.accent = accent;
      }

      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
         int background = this.accent
            ? (this.isHovered() ? GuildScreen.accentColor() : GuildScreen.accentSurface())
            : (this.isHovered() ? GuildScreen.tintSurface(0x344344, 0.34F, 0xFF) : GuildScreen.tintSurface(0x242F31, 0.28F, 0xFF));
         int border = this.accent ? GuildScreen.accentColor() : (this.isHovered() ? GuildScreen.tintSurface(0x5A736F, 0.48F, 0xFF) : GuildScreen.tintSurface(0x3A4A4C, 0.38F, 0xFF));
         graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, background);
         GuildScreen.this.drawOutline(graphics, this.getX(), this.getY(), this.width, this.height, border);
         String label = this.getMessage().getString();
         int textColor = this.active ? -1379602 : -7891569;
         graphics.drawString(
            GuildScreen.this.font,
            label,
            this.getX() + (this.width - GuildScreen.this.font.width(label)) / 2,
            this.getY() + (this.height - 8) / 2,
            textColor,
            false
         );
      }

      public void playDownSound(SoundManager soundManager) {
      }

      public void onClick(double mouseX, double mouseY) {
         if (this.active) {
            GuildUiSound.play();
            this.action.run();
         }
      }

      protected void updateWidgetNarration(NarrationElementOutput narration) {
         narration.add(NarratedElementType.TITLE, this.getMessage());
      }
   }

   private final class HeaderIconButton extends AbstractWidget {
      private final Runnable action;
      private final int hoverColor;

      private HeaderIconButton(int x, int y, int width, int height, String label, int hoverColor, Runnable action, String tooltip) {
         super(x, y, width, height, Component.literal(label));
         this.action = action;
         this.hoverColor = hoverColor;
         this.setTooltip(Tooltip.create(Component.literal(tooltip)));
      }

      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
         int bg = this.isHovered() ? this.hoverColor : GuildScreen.tintSurface(0x252F32, 0.28F, 0xFF);
         graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, bg);
         GuildScreen.this.drawOutline(graphics, this.getX(), this.getY(), this.width, this.height, this.isHovered() ? -1 : GuildScreen.tintSurface(0x3E4D51, 0.40F, 0xFF));
         if ("⎋".equals(this.getMessage().getString())) {
            ResourceLocation exitTex = ResourceLocation.fromNamespaceAndPath("desgraciamon_guilds", "textures/gui/icons/exit-guild.png");
            RenderSystem.enableBlend();
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            graphics.blit(exitTex, this.getX() + (this.width - 16) / 2, this.getY() + (this.height - 16) / 2, 16, 16, 0.0F, 0.0F, 16, 16, 16, 16);
            RenderSystem.disableBlend();
         } else {
            graphics.drawString(
               GuildScreen.this.font,
               this.getMessage(),
               this.getX() + (this.width - GuildScreen.this.font.width(this.getMessage())) / 2,
               this.getY() + (this.height - 8) / 2,
               -1,
               false
            );
         }
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

   static record HeldPokemon(String container, int box, int slot, String species, int level, boolean shiny, String form, String displayName, boolean restricted, ItemStack heldStack) {
   }

   static class HeldVaultItem {
      final String container;
      final int slot;
      ItemStack stack;
      int remainingInSource;

      HeldVaultItem(String container, int slot, ItemStack stack, int remainingInSource) {
         this.container = container;
         this.slot = slot;
         this.stack = stack;
         this.remainingInSource = remainingInSource;
      }
   }

   private final class HueBarWidget extends AbstractWidget {
      private final Consumer<String> onColorSelected;
      private int dragMode = 0; // 0=none, 1=SV box, 2=Hue bar
      private float currentHue = 0.55F;
      private float currentSat = 0.75F;
      private float currentBri = 0.85F;

      private HueBarWidget(int x, int y, int width, int height, Consumer<String> onColorSelected) {
         super(x, y, width, height, Component.literal("Selector de Color"));
         this.onColorSelected = onColorSelected;
         this.setTooltip(Tooltip.create(Component.literal("Cuadro Hex: elige tono, saturación y brillo")));
         syncFromDraft();
      }

      private void syncFromDraft() {
         try {
            int c = Integer.parseInt(GuildScreen.this.draftColor.replace("#", ""), 16);
            float[] hsb = Color.RGBtoHSB((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, null);
            this.currentHue = hsb[0];
            this.currentSat = hsb[1];
            this.currentBri = hsb[2];
         } catch (Exception ignored) {}
      }

      private int hueBarHeight() {
         return Math.max(8, Math.min(10, this.height / 4));
      }

      private int svBoxHeight() {
         return Math.max(12, this.height - hueBarHeight() - 4);
      }

      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
         int svH = svBoxHeight();
         int hueY = this.getY() + svH + 4;
         int hueH = hueBarHeight();

         // 1. Cuadro 2D de Saturación (X) y Brillo (Y)
         int stepX = Math.max(2, this.width / 48);
         int stepY = Math.max(2, svH / 16);
         for (int px = 0; px < this.width; px += stepX) {
            float s = (float)px / (float)Math.max(1, this.width - 1);
            int w = Math.min(stepX, this.width - px);
            for (int py = 0; py < svH; py += stepY) {
               float b = 1.0F - (float)py / (float)Math.max(1, svH - 1);
               int h = Math.min(stepY, svH - py);
               int rgb = Color.HSBtoRGB(this.currentHue, s, b) & 0xFFFFFF;
               graphics.fill(this.getX() + px, this.getY() + py, this.getX() + px + w, this.getY() + py + h, 0xFF000000 | rgb);
            }
         }
         GuildScreen.this.drawOutline(graphics, this.getX() - 1, this.getY() - 1, this.width + 2, svH + 2, 0xFF4A5058);

         // Cursor en cuadro SV
         int curX = this.getX() + (int)(this.currentSat * (this.width - 1));
         int curY = this.getY() + (int)((1.0F - this.currentBri) * (svH - 1));
         graphics.fill(curX - 2, curY - 2, curX + 3, curY + 3, 0xFFFFFFFF);
         graphics.fill(curX - 1, curY - 1, curX + 2, curY + 2, 0xFF000000);

         // 2. Barra arcoíris de Hue debajo
         for (int i = 0; i < this.width; i++) {
            float hue = (float)i / (float)Math.max(1, this.width - 1);
            int rgb = Color.HSBtoRGB(hue, 1.0F, 1.0F) & 0xFFFFFF;
            graphics.fill(this.getX() + i, hueY, this.getX() + i + 1, hueY + hueH, 0xFF000000 | rgb);
         }
         GuildScreen.this.drawOutline(graphics, this.getX() - 1, hueY - 1, this.width + 2, hueH + 2, 0xFF4A5058);

         int indX = this.getX() + (int)(this.currentHue * (this.width - 1));
         graphics.fill(indX - 1, hueY - 2, indX + 2, hueY + hueH + 2, 0xFFFFFFFF);
         graphics.fill(indX, hueY - 1, indX + 1, hueY + hueH + 1, 0xFF101010);
      }

      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         if (this.active && this.visible && this.isValidClickButton(button) && this.clicked(mouseX, mouseY)) {
            int svH = svBoxHeight();
            this.dragMode = (mouseY < this.getY() + svH + 2) ? 1 : 2;
            GuildUiSound.play();
            this.applyPick(mouseX, mouseY);
            return true;
         }
         return false;
      }

      public boolean mouseReleased(double mouseX, double mouseY, int button) {
         this.dragMode = 0;
         return super.mouseReleased(mouseX, mouseY, button);
      }

      public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
         if (this.dragMode == 0 && !this.clicked(mouseX, mouseY)) {
            return false;
         }
         if (this.dragMode == 0) {
            int svH = svBoxHeight();
            this.dragMode = (mouseY < this.getY() + svH + 2) ? 1 : 2;
         }
         this.applyPick(mouseX, mouseY);
         return true;
      }

      private void applyPick(double mouseX, double mouseY) {
         float relX = Math.max(0.0F, Math.min(1.0F, (float)(mouseX - this.getX()) / (float)Math.max(1, this.width - 1)));
         if (this.dragMode == 2) {
            this.currentHue = relX;
         } else {
            int svH = svBoxHeight();
            float relY = Math.max(0.0F, Math.min(1.0F, (float)(mouseY - this.getY()) / (float)Math.max(1, svH - 1)));
            this.currentSat = relX;
            this.currentBri = 1.0F - relY;
         }
         int rgb = Color.HSBtoRGB(this.currentHue, this.currentSat, this.currentBri) & 0xFFFFFF;
         String hex = String.format("%06X", rgb);
         GuildScreen.this.draftColor = hex;
         this.onColorSelected.accept(hex);
      }

      protected void updateWidgetNarration(NarrationElementOutput narration) {
         narration.add(NarratedElementType.TITLE, this.getMessage());
      }
   }

   private static String[] tabIconSprite(int tabIdx) {
      return switch (tabIdx) {
         case 0 -> new String[]{ // General: Casa / Estandarte de Gremio
            ".....OO.....",
            "....OAAO....",
            "...OALAAO...",
            "..OALAAAAO..",
            ".OAAAAAAAAO.",
            "OOODDDDDDOOO",
            "..ODWWWWDO..",
            "..ODWGGWDO..",
            "..ODWGGWDO..",
            "..ODDDDDDO..",
            "..OOOOOOOO..",
            "............"
         };
         case 1 -> new String[]{ // Miembros: Dos entrenadores
            "............",
            "..OOO..OOO..",
            ".OWWWOOWWWO.",
            ".OWWWOOLLWO.",
            "..OOO..OOO..",
            ".OAAAO.OGGO.",
            "OAAAAAOOGGGO",
            "OAAAAAOOGGGO",
            "OADDDDOOGGGO",
            ".OOOOO.OOOO.",
            "............",
            "............"
         };
         case 2 -> new String[]{ // PC: Monitor con Pokeball
            ".OOOOOOOOOO.",
            "OSSSSSSSSSSO",
            "OSOOORROOOSO",
            "OSORRRWRROSO",
            "OSOOOWWOOOSO",
            "OSOWWWWWWSO.",
            "OSSSSSSSSSSO",
            ".OOOOOOOOOO.",
            "....OOOO....",
            "...OSSSSO...",
            "..OOOOOOOO..",
            "............"
         };
         case 3 -> new String[]{ // Inventario: Cofre de tesoro
            "............",
            ".OOOOOOOOOO.",
            "OLLLLLLLLLLO",
            "OAAAAAAAAAOO",
            "OOOOOGGOOOOO",
            "ODDDOWWODDD.",
            "ODDDDOODDDDO",
            "ODDDDDDDDDDO",
            "ODDDDDDDDDDO",
            ".OOOOOOOOOO.",
            "............",
            "............"
         };
         case 4 -> new String[]{ // Waypoints: Pin / Brujula
            "....OOOO....",
            "...OLLLL0...",
            "..OLWWWWLO..",
            "..OLWAAWLO..",
            "..OAAWWAAO..",
            "..OAAAAAAO..",
            "...OAAAAD...",
            "....OADO....",
            "....OADO....",
            ".....OO.....",
            "...OOOOOO...",
            "............"
         };
         case 5 -> new String[]{ // Registro: Pergamino / Historial
            "..OOOOOOOO..",
            ".OWWWWWWWWO.",
            ".OWOAAAOOWO.",
            ".OWWWWWWWWO.",
            ".OWOSSSSOWO.",
            ".OWWWWWWWWO.",
            ".OWOSSSSOWO.",
            ".OWWWWWWWWO.",
            ".OWOAAOWGGO.",
            ".OWWWWWWGGO.",
            "..OOOOOOOO..",
            "............"
         };
         default -> new String[]{ // Personalizar: Paleta de colores
            "...OOOOOO...",
            "..OSSSSSSO..",
            ".OSRROOGGSO.",
            ".OSRROOGGSO.",
            "OSSSOOOOSSSO",
            "OSAAO..OSSSO",
            "OSAAO..OLLSO",
            ".OSSO..OLLSO",
            ".OSSSSSSSSO.",
            "..OSSSSSSO..",
            "...OOOOOO...",
            "............"
         };
      };
   }

   private void drawTabIcon(GuiGraphics graphics, int tabIdx, int x, int y, int size, boolean selected) {
      String[] sprite = tabIconSprite(tabIdx);
      int color = selected ? accentColor() : tintSurface(0x94A3B8, 0.35F, 0xFF);
      int light = blendColor(color, 1.32F);
      int dark = blendColor(color, 0.65F);
      for (int row = 0; row < 12; row++) {
         String line = sprite[row];
         int py1 = y + (row * size) / 12;
         int py2 = y + ((row + 1) * size) / 12;
         if (py2 <= py1) py2 = py1 + 1;
         for (int col = 0; col < 12; col++) {
            char c = line.charAt(col);
            if (c == '.') continue;
            int px1 = x + (col * size) / 12;
            int px2 = x + ((col + 1) * size) / 12;
            if (px2 <= px1) px2 = px1 + 1;
            int pxColor = switch (c) {
               case 'O', '0' -> 0xFF0A0D12;
               case 'A' -> 0xFF000000 | (color & 0xFFFFFF);
               case 'L' -> light;
               case 'D' -> dark;
               case 'W' -> 0xFFFFFFFF;
               case 'S' -> 0xFFD2DAE6;
               case 'G' -> 0xFFF5B72F;
               case 'R' -> 0xFFEF4444;
               default -> 0xFF000000 | (color & 0xFFFFFF);
            };
            graphics.fill(px1, py1, px2, py2, pxColor);
         }
      }
   }

   private final class IconTabButton extends AbstractWidget {
      private final int icon;
      private final boolean selected;
      private final Runnable action;

      private IconTabButton(int x, int y, int icon, boolean selected, Runnable action) {
         super(x, y, 36, 36, Component.literal(GuildScreen.TABS[icon]));
         this.icon = icon;
         this.selected = selected;
         this.action = action;
         this.setTooltip(Tooltip.create(Component.literal(GuildScreen.TABS[icon])));
      }

      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
         int background = this.selected
            ? GuildScreen.tintSurface(0x1B2230, 0.38F, 0xFF)
            : (this.isHovered() ? GuildScreen.tintSurface(0x19202D, 0.28F, 0xFF) : GuildScreen.tintSurface(0x121620, 0.18F, 0xFF));
         graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, background);
         int border = this.selected
            ? GuildScreen.accentColor()
            : (this.isHovered() ? GuildScreen.tintSurface(0x52607A, 0.45F, 0xFF) : GuildScreen.tintSurface(0x252D3D, 0.30F, 0xFF));
         GuildScreen.this.drawOutline(graphics, this.getX(), this.getY(), this.width, this.height, border);
         if (this.selected) {
            graphics.fill(this.getX() + 1, this.getY() + 1, this.getX() + 3, this.getY() + this.height - 1, GuildScreen.accentColor());
         }
         int iconSize = 16;
         int iconX = this.getX() + (this.width - iconSize) / 2;
         int iconY = this.getY() + (this.height - iconSize) / 2;
         RenderSystem.enableBlend();
         if (this.selected) {
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
         } else if (this.isHovered()) {
            graphics.setColor(0.92F, 0.95F, 1.0F, 1.0F);
         } else {
            graphics.setColor(0.68F, 0.74F, 0.84F, 1.0F);
         }
         graphics.blit(GuildScreen.icon(this.icon), iconX, iconY, iconSize, iconSize, 0.0F, 0.0F, 16, 16, 16, 16);
         graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
         RenderSystem.disableBlend();
      }

      public void playDownSound(SoundManager soundManager) {
      }

      public void onClick(double mouseX, double mouseY) {
         GuildUiSound.play();
         this.action.run();
      }

      protected void updateWidgetNarration(NarrationElementOutput narration) {
         narration.add(NarratedElementType.TITLE, Component.literal(GuildScreen.TABS[this.icon]));
      }
   }

   private final class IconTileButton extends AbstractWidget {
      private final String iconKey;
      private final Runnable onSelect;

      private IconTileButton(int x, int y, int size, String iconKey, Runnable onSelect) {
         super(x, y, size, size, Component.literal(iconKey));
         this.iconKey = iconKey;
         this.onSelect = onSelect;
         String label = iconKey.substring(0, 1).toUpperCase() + iconKey.substring(1);
         this.setTooltip(Tooltip.create(Component.literal("Emblema: " + label)));
      }

      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
         boolean selected = this.iconKey.equalsIgnoreCase(GuildScreen.this.draftIcon);
         int bgColor = selected
            ? GuildScreen.accentSurface()
            : (this.isHovered() ? GuildScreen.tintSurface(0x222D31, 0.30F, 0xFF) : GuildScreen.tintSurface(0x161E22, 0.22F, 0xFF));
         graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, bgColor);
         int borderColor = selected
            ? GuildScreen.accentColor()
            : (this.isHovered() ? GuildScreen.tintSurface(0x6A8682, 0.45F, 0xFF) : GuildScreen.tintSurface(0x2B373B, 0.36F, 0xFF));
         GuildScreen.this.drawOutline(graphics, this.getX(), this.getY(), this.width, this.height, borderColor);
         int iconColor = selected ? GuildScreen.accentColor() : (this.isHovered() ? -1642262 : -7363682);
         int iconSize = this.width - 6;
         GuildScreen.this.drawGuildIcon(graphics, this.iconKey, this.getX() + 3, this.getY() + 3, iconSize, iconColor);
      }

      public void playDownSound(SoundManager soundManager) {
      }

      public void onClick(double mouseX, double mouseY) {
         GuildUiSound.play();
         GuildScreen.this.draftIcon = this.iconKey;
         this.onSelect.run();
      }

      protected void updateWidgetNarration(NarrationElementOutput narration) {
         narration.add(NarratedElementType.TITLE, this.getMessage());
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

   private final class QuickPillButton extends AbstractWidget {
      private final Runnable action;

      private QuickPillButton(int x, int y, int width, int height, String label, Runnable action) {
         super(x, y, width, height, Component.literal(label));
         this.action = action;
      }

      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
         int bg = this.isHovered() ? GuildScreen.tintSurface(0x3D4F51, 0.36F, 0xFF) : GuildScreen.tintSurface(0x222D2E, 0.28F, 0xFF);
         graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, bg);
         GuildScreen.this.drawOutline(
            graphics, this.getX(), this.getY(), this.width, this.height, this.isHovered() ? GuildScreen.accentColor() : GuildScreen.tintSurface(0x354446, 0.38F, 0xFF)
         );
         graphics.drawString(
            GuildScreen.this.font,
            this.getMessage(),
            this.getX() + (this.width - GuildScreen.this.font.width(this.getMessage())) / 2,
            this.getY() + (this.height - 8) / 2,
            -1905690,
            false
         );
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

   private final class RowActionButton extends AbstractWidget {
      private final int btnColor;
      private final Runnable action;

      private RowActionButton(int x, int y, int width, int height, String label, int btnColor, Runnable action, String tooltipText) {
         super(x, y, width, height, Component.literal(label));
         this.btnColor = btnColor;
         this.action = action;
         if (tooltipText != null && !tooltipText.isBlank()) {
            this.setTooltip(Tooltip.create(Component.literal(tooltipText)));
         }
      }

      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
         int bg = this.isHovered() ? this.btnColor : GuildScreen.tintSurface(0x222E30, 0.28F, 0xFF);
         graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, bg);
         GuildScreen.this.drawOutline(graphics, this.getX(), this.getY(), this.width, this.height, this.isHovered() ? -1 : this.btnColor);
         int textCol = this.isHovered() ? -1 : this.btnColor;
         graphics.drawCenteredString(GuildScreen.this.font, this.getMessage(), this.getX() + this.width / 2, this.getY() + (this.height - 8) / 2, textCol);
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

   private final class WaypointSelectButton extends AbstractWidget {
      private final String detail;
      private final boolean selected;
      private final Runnable action;

      private WaypointSelectButton(int x, int y, int buttonWidth, int buttonHeight, String name, String detail, boolean selected, Runnable action) {
         super(x, y, buttonWidth, buttonHeight, Component.literal(name));
         this.detail = detail;
         this.selected = selected;
         this.action = action;
         this.setTooltip(Tooltip.create(Component.literal(name + "\n" + detail)));
      }

      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
         int background = this.selected
            ? GuildScreen.accentSurface()
            : (this.isHovered() ? GuildScreen.tintSurface(0x252E36, 0.30F, 0xFF) : GuildScreen.tintSurface(0x172024, 0.22F, 0xFF));
         graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, background);
         GuildScreen.this.drawOutline(
            graphics,
            this.getX(),
            this.getY(),
            this.width,
            this.height,
            this.selected ? GuildScreen.accentColor() : (this.isHovered() ? GuildScreen.tintSurface(0x4B6061, 0.42F, 0xFF) : GuildScreen.tintSurface(0x2E3D3F, 0.36F, 0xFF))
         );
         int dimColor = -13381254;
         String lowerDetail = this.detail.toLowerCase(Locale.ROOT);
         if (lowerDetail.contains("nether")) {
            dimColor = -43691;
         } else if (lowerDetail.contains("end")) {
            dimColor = -5745161;
         }

         graphics.fill(this.getX(), this.getY(), this.getX() + 3, this.getY() + this.height, dimColor);
         graphics.drawString(
            GuildScreen.this.font, GuildScreen.this.fit(this.getMessage().getString(), this.width - 12), this.getX() + 7, this.getY() + 3, -1, false
         );
         graphics.drawString(GuildScreen.this.font, GuildScreen.this.fit(this.detail, this.width - 12), this.getX() + 7, this.getY() + 14, -6443350, false);
      }

      public void onClick(double mouseX, double mouseY) {
         GuildUiSound.play();
         this.action.run();
      }

      protected void updateWidgetNarration(NarrationElementOutput narration) {
         narration.add(NarratedElementType.TITLE, this.getMessage());
         narration.add(NarratedElementType.HINT, Component.literal(this.detail));
      }
   }
}
