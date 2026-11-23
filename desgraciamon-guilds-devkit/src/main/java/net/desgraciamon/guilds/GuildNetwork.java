package net.desgraciamon.guilds;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.permission.CobblemonPermissions;
import com.cobblemon.mod.common.api.storage.party.PartyPosition;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCPosition;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.api.storage.pc.link.PCLinkManager;
import com.cobblemon.mod.common.api.storage.pc.link.PermissiblePcLink;
import com.cobblemon.mod.common.net.messages.client.storage.party.SetPartyReferencePacket;
import com.cobblemon.mod.common.net.messages.client.storage.pc.OpenPCPacket;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

final class GuildNetwork {
   private static final Pattern ARGUMENT = Pattern.compile("[A-Za-z0-9_#-]{1,64}");
   private static final DateTimeFormatter OFFLINE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT).withZone(ZoneId.of("America/Caracas"));
   private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy - HH:mm:ss", Locale.ROOT).withZone(ZoneId.of("America/Caracas"));
   private static Supplier<GuildStorage> storageSupplier;

   private GuildNetwork() {
   }

   static void initialize(Supplier<GuildStorage> storage) {
      storageSupplier = storage;
      PayloadTypeRegistry.playC2S().register(GuildRequestPayload.TYPE, GuildRequestPayload.CODEC);
      PayloadTypeRegistry.playC2S().register(GuildWaypointImportPayload.TYPE, GuildWaypointImportPayload.CODEC);
      PayloadTypeRegistry.playS2C().register(GuildSyncPayload.TYPE, GuildSyncPayload.CODEC);
      PayloadTypeRegistry.playS2C().register(GuildSynergyPayload.TYPE, GuildSynergyPayload.CODEC);
      ServerPlayNetworking.registerGlobalReceiver(
         GuildRequestPayload.TYPE, (payload, context) -> context.server().execute(() -> handle(context.player(), payload))
      );
      ServerPlayNetworking.registerGlobalReceiver(
         GuildWaypointImportPayload.TYPE, (payload, context) -> context.server().execute(() -> importWaypoint(context.player(), payload))
      );
   }

   static void syncGuildMembers(MinecraftServer server, GuildStorage.Guild guild, GuildStorage storage) {
      if (server == null || guild == null || storage == null) {
         return;
      }
      for (GuildStorage.Member m : guild.members.values()) {
         try {
            ServerPlayer online = server.getPlayerList().getPlayer(UUID.fromString(m.playerUuid));
            if (online != null) {
               sync(online, server, storage);
            }
         } catch (IllegalArgumentException ignored) {
         }
      }
   }

   static void syncAll(MinecraftServer server) {
      GuildStorage storage = storageSupplier == null ? null : storageSupplier.get();
      if (storage != null) {
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sync(player, server, storage);
         }
      }
   }

   static void sync(ServerPlayer player, MinecraftServer server, GuildStorage storage) {
      String id = storage.index().playerGuilds.get(player.getUUID().toString());
      GuildStorage.Guild guild = id == null ? null : storage.getGuild(id);
      boolean member = guild != null && !guild.disbanded;
      String invitedGuildId = member ? null : storage.index().invitations.get(player.getUUID().toString());
      GuildStorage.Guild invitedGuild = invitedGuildId == null ? null : storage.getGuild(invitedGuildId);
      boolean hasInvite = !member && invitedGuild != null && !invitedGuild.disbanded;
      String name = member ? guild.name : (hasInvite ? invitedGuild.name : "Sin gremio");
      String color = member ? guild.colorHex : (hasInvite ? invitedGuild.colorHex : "#FFFFFF");
      GuildStorage.Member own = member ? guild.members.get(player.getUUID().toString()) : null;
      String role = member ? (own == null ? "" : own.role) : (hasInvite ? "INVITED" : "");
      String icon = member
         ? (guild.icon != null ? guild.icon : "pokeball")
         : (hasInvite ? (invitedGuild.icon != null ? invitedGuild.icon : "pokeball") : "pokeball");
      int memberCount = member ? guild.members.size() : (hasInvite ? invitedGuild.members.size() : 0);
      BigInteger walletBalance = BigInteger.ZERO;
      if (CobbleDollarsBridge.isAvailable()) {
         try {
            walletBalance = CobbleDollarsBridge.balance(player);
         } catch (ReflectiveOperationException var28) {
         }
      }

      List<String> members = new ArrayList<>();
      List<String> waypoints = new ArrayList<>();
      List<String> logs = new ArrayList<>();
      if (member) {
         for (GuildStorage.Member entry : guild.members.values()) {
            ServerPlayer online = server.getPlayerList().getPlayer(UUID.fromString(entry.playerUuid));
            String status = online == null
               ? "Desconectado; ultima conexion " + OFFLINE_TIME.format(Instant.ofEpochSecond(entry.lastSeenTimestamp))
               : "En linea";
            members.add(entry.playerName + " | " + entry.role + " | " + status + " | " + entry.playerUuid);
         }

         for (GuildStorage.Waypoint waypoint : guild.waypoints) {
            if (waypoint.shared || waypoint.ownerUuid.equals(player.getUUID().toString())) {
               String encodedName = Base64.getEncoder().encodeToString(waypoint.name.getBytes(StandardCharsets.UTF_8));
               String uploader = waypoint.sharedBy;
               if (uploader == null || uploader.isBlank()) {
                  GuildStorage.Member owner = guild.members.get(waypoint.ownerUuid);
                  uploader = owner == null ? "Miembro" : owner.playerName;
               }

               String encodedUploader = Base64.getEncoder().encodeToString(uploader.getBytes(StandardCharsets.UTF_8));
               waypoints.add(
                  waypoint.waypointId
                     + "|"
                     + waypoint.shared
                     + "|"
                     + encodedName
                     + "|"
                     + waypoint.dimension
                     + "|"
                     + waypoint.x
                     + "|"
                     + waypoint.y
                     + "|"
                     + waypoint.z
                     + "|"
                     + encodedUploader
                     + "|"
                     + waypoint.sharedTimestamp
               );
            }
         }

         int logStart = Math.max(0, guild.activityLog.size() - 128);

         for (int index = logStart; index < guild.activityLog.size(); index++) {
            GuildStorage.Activity activity = guild.activityLog.get(index);
            logs.add(formatActivity(activity, guild));
         }
      } else if (hasInvite) {
         for (GuildStorage.Member entry : invitedGuild.members.values()) {
            ServerPlayer online = server.getPlayerList().getPlayer(UUID.fromString(entry.playerUuid));
            String status = online == null ? "Desconectado" : "En linea";
            members.add(entry.playerName + " | " + entry.role + " | " + status + " | " + entry.playerUuid);
         }
      }

      List<String> tags = new ArrayList<>();

      for (ServerPlayer online : server.getPlayerList().getPlayers()) {
         String memberGuildId = storage.index().playerGuilds.get(online.getUUID().toString());
         GuildStorage.Guild tagGuild = memberGuildId == null ? null : storage.getGuild(memberGuildId);
         if (tagGuild != null && !tagGuild.disbanded) {
            GuildStorage.Member tagMember = tagGuild.members.get(online.getUUID().toString());
            String tagRole = tagMember != null && tagMember.role != null ? tagMember.role : "MEMBER";
            tags.add(online.getUUID() + "|" + tagGuild.name + "|" + tagGuild.colorHex + "|" + tagRole);
         }
      }

      List<String> pcPreviews = new ArrayList<>();
      if (member && DesgraciamonGuilds.getPcManager() != null) {
         pcPreviews = DesgraciamonGuilds.getPcManager().getBoxPreviews(guild);
      }

      List<String> chatList = new ArrayList<>();
      if (member && guild.chatMessages != null) {
         DateTimeFormatter chatTime = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneId.of("America/Caracas"));
         int start = Math.max(0, guild.chatMessages.size() - 60);

         for (int i = start; i < guild.chatMessages.size(); i++) {
            GuildStorage.ChatMessage cm = guild.chatMessages.get(i);
            String timeStr = chatTime.format(Instant.ofEpochMilli(cm.timestamp));
            String b64 = Base64.getEncoder().encodeToString(cm.text.getBytes(StandardCharsets.UTF_8));
            chatList.add(timeStr + "|" + cm.senderName + "|" + b64);
         }
      }

      List<ItemStack> vaultList = new ArrayList<>(27);
      if (member && guild != null) {
         NonNullList<ItemStack> v = guild.getVault();

         for (int i = 0; i < 27; i++) {
            vaultList.add(i < v.size() ? ((ItemStack)v.get(i)).copy() : ItemStack.EMPTY);
         }
      } else {
         for (int i = 0; i < 27; i++) {
            vaultList.add(ItemStack.EMPTY);
         }
      }

      ServerPlayNetworking.send(
         player,
         new GuildSyncPayload(
            member,
            name,
            color,
            role,
            memberCount,
            member ? guild.bankBalance : BigInteger.ZERO,
            walletBalance,
            member && guild.motd != null ? guild.motd : "",
            icon,
            members,
            waypoints,
            logs,
            tags,
            pcPreviews,
            chatList,
            vaultList
         )
      );
   }

   static void sendGuildChatMessage(MinecraftServer server, ServerPlayer sender, GuildStorage.Guild guild, String text) {
      if (text != null && !text.isBlank()) {
         String cleanText = text.trim();
         if (cleanText.length() > 256) {
            cleanText = cleanText.substring(0, 256);
         }

         long now = System.currentTimeMillis();
         String senderName = sender.getGameProfile().getName();
         if (guild.chatMessages == null) {
            guild.chatMessages = new ArrayList<>();
         }

         guild.chatMessages.add(new GuildStorage.ChatMessage(now, senderName, cleanText));

         while (guild.chatMessages.size() > 100) {
            guild.chatMessages.remove(0);
         }

         int parsedColor;
         try {
            parsedColor = Integer.parseInt(guild.colorHex.replace("#", ""), 16);
         } catch (Exception var14) {
            parsedColor = 3395962;
         }

         int colorInt = parsedColor;
         Component formatted = Component.literal("<" + guild.name + "> " + senderName + ": " + cleanText).withStyle(style -> style.withColor(colorInt));

         for (GuildStorage.Member m : guild.members.values()) {
            ServerPlayer online = server.getPlayerList().getPlayer(UUID.fromString(m.playerUuid));
            if (online != null) {
               online.sendSystemMessage(formatted);
            }
         }
      }
   }

   private static boolean canUseGuildVault(ServerPlayer player, GuildStorage.Guild guild) {
      return player != null
         && guild != null
         && !guild.disbanded
         && !player.isRemoved()
         && player.isAlive()
         && !player.isSpectator()
         && guild.members != null
         && guild.members.containsKey(player.getUUID().toString());
   }

   private static int countMatchingItems(ServerPlayer player, NonNullList<ItemStack> vault, ItemStack template) {
      if (template == null || template.isEmpty()) {
         return 0;
      }
      int total = 0;
      for (int i = 0; i < 27; i++) {
         ItemStack s = (ItemStack)vault.get(i);
         if (s != null && !s.isEmpty() && ItemStack.isSameItemSameComponents(s, template)) {
            total += s.getCount();
         }
      }
      for (int i = 0; i < 36; i++) {
         ItemStack s = player.getInventory().getItem(i);
         if (s != null && !s.isEmpty() && ItemStack.isSameItemSameComponents(s, template)) {
            total += s.getCount();
         }
      }
      return total;
   }

   private static ItemStack[] snapshotContainer(ServerPlayer player, NonNullList<ItemStack> vault) {
      ItemStack[] snap = new ItemStack[63];
      for (int i = 0; i < 27; i++) {
         ItemStack s = (ItemStack)vault.get(i);
         snap[i] = s == null || s.isEmpty() ? ItemStack.EMPTY : s.copy();
      }
      for (int i = 0; i < 36; i++) {
         ItemStack s = player.getInventory().getItem(i);
         snap[27 + i] = s == null || s.isEmpty() ? ItemStack.EMPTY : s.copy();
      }
      return snap;
   }

   private static void restoreContainer(ServerPlayer player, NonNullList<ItemStack> vault, ItemStack[] snap) {
      for (int i = 0; i < 27; i++) {
         vault.set(i, snap[i]);
      }
      for (int i = 0; i < 36; i++) {
         player.getInventory().setItem(i, snap[27 + i]);
      }
   }

   private static boolean isValidVaultContainerSlot(String type, int slot) {
      if ("VAULT".equals(type)) {
         return slot >= 0 && slot < 27;
      } else if ("PLAYER".equals(type)) {
         return slot >= 0 && slot < 36;
      } else {
         return false;
      }
   }

   private static ItemStack getSlotStack(ServerPlayer player, NonNullList<ItemStack> vault, String type, int slot) {
      if ("VAULT".equals(type)) {
         return slot >= 0 && slot < 27 ? (ItemStack)vault.get(slot) : ItemStack.EMPTY;
      } else if ("PLAYER".equals(type)) {
         return slot >= 0 && slot < 36 ? player.getInventory().getItem(slot) : ItemStack.EMPTY;
      } else {
         return ItemStack.EMPTY;
      }
   }

   private static void setSlotStack(ServerPlayer player, NonNullList<ItemStack> vault, String type, int slot, ItemStack stack) {
      ItemStack safe = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack;
      if ("VAULT".equals(type)) {
         if (slot >= 0 && slot < 27) {
            vault.set(slot, safe);
         }
      } else if ("PLAYER".equals(type) && slot >= 0 && slot < 36) {
         player.getInventory().setItem(slot, safe);
      }
   }

   private static int executeSingleVaultTransfer(
      ServerPlayer player,
      NonNullList<ItemStack> vault,
      String srcType,
      int srcSlot,
      String dstType,
      int dstSlot,
      int amount,
      boolean allowSwap
   ) {
      if (!isValidVaultContainerSlot(srcType, srcSlot) || !isValidVaultContainerSlot(dstType, dstSlot)) {
         return 0;
      } else if (srcType.equals(dstType) && srcSlot == dstSlot) {
         return 0;
      } else if (amount <= 0) {
         return 0;
      } else {
         ItemStack src = getSlotStack(player, vault, srcType, srcSlot);
         if (src == null || src.isEmpty()) {
            return 0;
         } else {
            int toMove = Math.min(amount, src.getCount());
            ItemStack dst = getSlotStack(player, vault, dstType, dstSlot);
            if (dst == null || dst.isEmpty()) {
               ItemStack placed = src.copyWithCount(toMove);
               ItemStack remaining = src.getCount() > toMove ? src.copyWithCount(src.getCount() - toMove) : ItemStack.EMPTY;
               setSlotStack(player, vault, dstType, dstSlot, placed);
               setSlotStack(player, vault, srcType, srcSlot, remaining);
               return toMove;
            } else if (ItemStack.isSameItemSameComponents(src, dst)) {
               int space = dst.getMaxStackSize() - dst.getCount();
               int actual = Math.min(space, toMove);
               if (actual <= 0) {
                  return 0;
               } else {
                  ItemStack merged = dst.copyWithCount(dst.getCount() + actual);
                  ItemStack remaining = src.getCount() > actual ? src.copyWithCount(src.getCount() - actual) : ItemStack.EMPTY;
                  setSlotStack(player, vault, dstType, dstSlot, merged);
                  setSlotStack(player, vault, srcType, srcSlot, remaining);
                  return actual;
               }
            } else if (allowSwap && toMove == src.getCount()) {
               ItemStack srcCopy = src.copy();
               ItemStack dstCopy = dst.copy();
               setSlotStack(player, vault, dstType, dstSlot, srcCopy);
               setSlotStack(player, vault, srcType, srcSlot, dstCopy);
               return srcCopy.getCount();
            } else {
               return 0;
            }
         }
      }
   }

   private static void handleVaultQuickMove(ServerPlayer player, GuildStorage storage, GuildStorage.Guild guild, String argument) {
      if (!canUseGuildVault(player, guild)) {
         sync(player, player.getServer(), storage);
         return;
      }
      String[] parts = argument.split("\\|", 2);
      if (parts.length < 2) {
         sync(player, player.getServer(), storage);
         return;
      }
      String srcType = parts[0];
      int srcSlot;
      try {
         srcSlot = Integer.parseInt(parts[1]);
      } catch (NumberFormatException var14) {
         sync(player, player.getServer(), storage);
         return;
      }
      if (!isValidVaultContainerSlot(srcType, srcSlot)) {
         sync(player, player.getServer(), storage);
         return;
      }

      boolean changed = false;
      synchronized (guild) {
         NonNullList<ItemStack> vault = guild.getVault();
         if ("PLAYER".equals(srcType)) {
            ItemStack src = player.getInventory().getItem(srcSlot);
            if (!src.isEmpty()) {
               ItemStack working = src.copy();
               String itemName = working.getHoverName().getString();
               int original = working.getCount();

               for (int i = 0; i < 27 && !working.isEmpty(); i++) {
                  ItemStack v = (ItemStack)vault.get(i);
                  if (!v.isEmpty() && ItemStack.isSameItemSameComponents(working, v)) {
                     int space = v.getMaxStackSize() - v.getCount();
                     if (space > 0) {
                        int toMove = Math.min(space, working.getCount());
                        vault.set(i, v.copyWithCount(v.getCount() + toMove));
                        working.shrink(toMove);
                     }
                  }
               }

               for (int i = 0; i < 27 && !working.isEmpty(); i++) {
                  ItemStack v = (ItemStack)vault.get(i);
                  if (v.isEmpty()) {
                     vault.set(i, working.copy());
                     working.setCount(0);
                     break;
                  }
               }

               int moved = original - working.getCount();
               if (moved > 0) {
                  player.getInventory().setItem(srcSlot, working.isEmpty() ? ItemStack.EMPTY : working);
                  guild.activityLog
                     .add(
                        new GuildStorage.Activity(
                           LOG_TIME.format(Instant.now()), "ITEM_DEPOSITED", player.getUUID(), moved + "x " + itemName, player.getGameProfile().getName()
                        )
                     );
                  changed = true;
               }
            }
         } else if ("VAULT".equals(srcType)) {
            ItemStack v = (ItemStack)vault.get(srcSlot);
            if (!v.isEmpty()) {
               ItemStack working = v.copy();
               String itemName = working.getHoverName().getString();
               int original = working.getCount();

               for (int i = 0; i < 36 && !working.isEmpty(); i++) {
                  ItemStack p = player.getInventory().getItem(i);
                  if (!p.isEmpty() && ItemStack.isSameItemSameComponents(working, p)) {
                     int space = p.getMaxStackSize() - p.getCount();
                     if (space > 0) {
                        int toMove = Math.min(space, working.getCount());
                        player.getInventory().setItem(i, p.copyWithCount(p.getCount() + toMove));
                        working.shrink(toMove);
                     }
                  }
               }

               for (int i = 0; i < 36 && !working.isEmpty(); i++) {
                  ItemStack p = player.getInventory().getItem(i);
                  if (p.isEmpty()) {
                     player.getInventory().setItem(i, working.copy());
                     working.setCount(0);
                     break;
                  }
               }

               int moved = original - working.getCount();
               if (moved > 0) {
                  vault.set(srcSlot, working.isEmpty() ? ItemStack.EMPTY : working);
                  guild.activityLog
                     .add(
                        new GuildStorage.Activity(
                           LOG_TIME.format(Instant.now()), "ITEM_WITHDRAWN", player.getUUID(), moved + "x " + itemName, player.getGameProfile().getName()
                        )
                     );
                  changed = true;
               }
            }
         }
      }

      player.getInventory().setChanged();
      player.inventoryMenu.broadcastChanges();
      player.containerMenu.broadcastChanges();
      if (changed) {
         storage.saveGuild(guild);
         syncGuildMembers(player.getServer(), guild, storage);
      } else {
         sync(player, player.getServer(), storage);
      }
   }

   private static void handleVaultTransfer(ServerPlayer player, GuildStorage storage, GuildStorage.Guild guild, String argument) {
      if (!canUseGuildVault(player, guild)) {
         sync(player, player.getServer(), storage);
         return;
      }
      String[] parts = argument.split("\\|", 5);
      if (parts.length < 5) {
         sync(player, player.getServer(), storage);
         return;
      }
      String srcType = parts[0];
      String dstType = parts[2];
      int srcSlot;
      int dstSlot;
      int amount;
      try {
         srcSlot = Integer.parseInt(parts[1]);
         dstSlot = Integer.parseInt(parts[3]);
         amount = Integer.parseInt(parts[4]);
      } catch (NumberFormatException var14) {
         sync(player, player.getServer(), storage);
         return;
      }

      int moved;
      String itemName = "";
      synchronized (guild) {
         NonNullList<ItemStack> vault = guild.getVault();
         ItemStack srcPreview = getSlotStack(player, vault, srcType, srcSlot);
         if (srcPreview != null && !srcPreview.isEmpty()) {
            itemName = srcPreview.getHoverName().getString();
         }
         moved = executeSingleVaultTransfer(player, vault, srcType, srcSlot, dstType, dstSlot, amount, true);
         if (moved > 0) {
            if ("PLAYER".equals(srcType) && "VAULT".equals(dstType)) {
               guild.activityLog
                  .add(
                     new GuildStorage.Activity(
                        LOG_TIME.format(Instant.now()), "ITEM_DEPOSITED", player.getUUID(), moved + "x " + itemName, player.getGameProfile().getName()
                     )
                  );
            } else if ("VAULT".equals(srcType) && "PLAYER".equals(dstType)) {
               guild.activityLog
                  .add(
                     new GuildStorage.Activity(
                        LOG_TIME.format(Instant.now()), "ITEM_WITHDRAWN", player.getUUID(), moved + "x " + itemName, player.getGameProfile().getName()
                     )
                  );
            }
         }
      }

      boolean touchedPlayer = "PLAYER".equals(srcType) || "PLAYER".equals(dstType);
      boolean touchedVault = "VAULT".equals(srcType) || "VAULT".equals(dstType);
      if (touchedPlayer) {
         player.getInventory().setChanged();
         player.inventoryMenu.broadcastChanges();
         player.containerMenu.broadcastChanges();
      }
      if (moved > 0 && touchedVault) {
         storage.saveGuild(guild);
         syncGuildMembers(player.getServer(), guild, storage);
      } else {
         sync(player, player.getServer(), storage);
      }
   }

   private static void handleVaultBatchTransfer(ServerPlayer player, GuildStorage storage, GuildStorage.Guild guild, String argument) {
      if (!canUseGuildVault(player, guild)) {
         sync(player, player.getServer(), storage);
         return;
      }
      String[] parts = argument.split("\\|", 3);
      if (parts.length < 3 || parts[2].isBlank()) {
         sync(player, player.getServer(), storage);
         return;
      }
      String srcType = parts[0];
      int srcSlot;
      try {
         srcSlot = Integer.parseInt(parts[1]);
      } catch (NumberFormatException var18) {
         sync(player, player.getServer(), storage);
         return;
      }
      if (!isValidVaultContainerSlot(srcType, srcSlot)) {
         sync(player, player.getServer(), storage);
         return;
      }

      String[] ops = parts[2].split(",");
      int totalDeposited = 0;
      int totalWithdrawn = 0;
      int totalMoved = 0;
      boolean touchedPlayer = "PLAYER".equals(srcType);
      boolean touchedVault = "VAULT".equals(srcType);
      String itemName = "";

      synchronized (guild) {
         NonNullList<ItemStack> vault = guild.getVault();
         ItemStack srcPreview = getSlotStack(player, vault, srcType, srcSlot);
         if (srcPreview != null && !srcPreview.isEmpty()) {
            itemName = srcPreview.getHoverName().getString();
            ItemStack template = srcPreview.copy();
            int totalBefore = countMatchingItems(player, vault, template);
            ItemStack[] snap = snapshotContainer(player, vault);
            java.util.Set<String> seenDst = new java.util.HashSet<>();
            int maxOps = Math.min(ops.length, 63);
            for (int opIdx = 0; opIdx < maxOps; opIdx++) {
               String op = ops[opIdx];
               if (op == null || op.isBlank()) {
                  continue;
               }
               String[] opParts = op.split(":", 3);
               if (opParts.length < 3) {
                  continue;
               }
               String dstType = opParts[0];
               int dstSlot;
               int amount;
               try {
                  dstSlot = Integer.parseInt(opParts[1]);
                  amount = Integer.parseInt(opParts[2]);
               } catch (NumberFormatException var17) {
                  continue;
               }
               if (amount <= 0 || amount > template.getMaxStackSize() || !isValidVaultContainerSlot(dstType, dstSlot)) {
                  continue;
               }
               if (!seenDst.add(dstType + ":" + dstSlot)) {
                  continue;
               }
               int moved = executeSingleVaultTransfer(player, vault, srcType, srcSlot, dstType, dstSlot, amount, false);
               if (moved > 0) {
                  totalMoved += moved;
                  if ("PLAYER".equals(dstType)) {
                     touchedPlayer = true;
                  }
                  if ("VAULT".equals(dstType)) {
                     touchedVault = true;
                  }
                  if ("PLAYER".equals(srcType) && "VAULT".equals(dstType)) {
                     totalDeposited += moved;
                  } else if ("VAULT".equals(srcType) && "PLAYER".equals(dstType)) {
                     totalWithdrawn += moved;
                  }
               }
            }

            int totalAfter = countMatchingItems(player, vault, template);
            if (totalAfter != totalBefore) {
               restoreContainer(player, vault, snap);
               totalMoved = 0;
               totalDeposited = 0;
               totalWithdrawn = 0;
               touchedPlayer = true;
               touchedVault = true;
            }

            if (totalDeposited > 0) {
               guild.activityLog
                  .add(
                     new GuildStorage.Activity(
                        LOG_TIME.format(Instant.now()), "ITEM_DEPOSITED", player.getUUID(), totalDeposited + "x " + itemName, player.getGameProfile().getName()
                     )
                  );
            }
            if (totalWithdrawn > 0) {
               guild.activityLog
                  .add(
                     new GuildStorage.Activity(
                        LOG_TIME.format(Instant.now()), "ITEM_WITHDRAWN", player.getUUID(), totalWithdrawn + "x " + itemName, player.getGameProfile().getName()
                     )
                  );
            }
         }
      }

      if (touchedPlayer) {
         player.getInventory().setChanged();
         player.inventoryMenu.broadcastChanges();
         player.containerMenu.broadcastChanges();
      }
      if (totalMoved > 0 && touchedVault) {
         storage.saveGuild(guild);
         syncGuildMembers(player.getServer(), guild, storage);
      } else {
         sync(player, player.getServer(), storage);
      }
   }

   private static void handleVaultPickupAll(ServerPlayer player, GuildStorage storage, GuildStorage.Guild guild, String argument) {
      if (!canUseGuildVault(player, guild)) {
         sync(player, player.getServer(), storage);
         return;
      }
      String[] parts = argument.split("\\|", 2);
      if (parts.length < 2) {
         sync(player, player.getServer(), storage);
         return;
      }
      String dstType = parts[0];
      int dstSlot;
      try {
         dstSlot = Integer.parseInt(parts[1]);
      } catch (NumberFormatException ex) {
         sync(player, player.getServer(), storage);
         return;
      }
      if (!isValidVaultContainerSlot(dstType, dstSlot)) {
         sync(player, player.getServer(), storage);
         return;
      }

      boolean touchedPlayer = "PLAYER".equals(dstType);
      boolean touchedVault = "VAULT".equals(dstType);
      int netVaultDelta = 0; // >0 deposited into vault, <0 withdrawn from vault
      String itemName = "";

      synchronized (guild) {
         NonNullList<ItemStack> vault = guild.getVault();
         ItemStack dst = getSlotStack(player, vault, dstType, dstSlot);
         if (dst == null || dst.isEmpty() || dst.getCount() >= dst.getMaxStackSize()) {
            sync(player, player.getServer(), storage);
            return;
         }

         ItemStack template = dst.copy();
         itemName = template.getHoverName().getString();
         int totalBefore = countMatchingItems(player, vault, template);
         ItemStack[] snap = snapshotContainer(player, vault);
         int needed = dst.getMaxStackSize() - dst.getCount();

         for (int pass = 0; pass < 2 && needed > 0; pass++) {
            boolean partialOnly = (pass == 0);
            for (int i = 0; i < 36 && needed > 0; i++) {
               if ("PLAYER".equals(dstType) && i == dstSlot) continue;
               ItemStack cand = player.getInventory().getItem(i);
               if (cand.isEmpty() || !ItemStack.isSameItemSameComponents(template, cand)) continue;
               if (partialOnly && cand.getCount() >= cand.getMaxStackSize()) continue;
               int moved = executeSingleVaultTransfer(player, vault, "PLAYER", i, dstType, dstSlot, needed, false);
               if (moved > 0) {
                  needed -= moved;
                  touchedPlayer = true;
                  if ("VAULT".equals(dstType)) netVaultDelta += moved;
               }
            }
            for (int i = 0; i < 27 && needed > 0; i++) {
               if ("VAULT".equals(dstType) && i == dstSlot) continue;
               ItemStack cand = (ItemStack)vault.get(i);
               if (cand.isEmpty() || !ItemStack.isSameItemSameComponents(template, cand)) continue;
               if (partialOnly && cand.getCount() >= cand.getMaxStackSize()) continue;
               int moved = executeSingleVaultTransfer(player, vault, "VAULT", i, dstType, dstSlot, needed, false);
               if (moved > 0) {
                  needed -= moved;
                  touchedVault = true;
                  if ("PLAYER".equals(dstType)) netVaultDelta -= moved;
               }
            }
         }

         int totalAfter = countMatchingItems(player, vault, template);
         if (totalAfter != totalBefore) {
            restoreContainer(player, vault, snap);
            netVaultDelta = 0;
            touchedPlayer = true;
            touchedVault = true;
         } else if (netVaultDelta > 0) {
            guild.activityLog.add(new GuildStorage.Activity(LOG_TIME.format(Instant.now()), "ITEM_DEPOSITED", player.getUUID(), netVaultDelta + "x " + itemName, player.getGameProfile().getName()));
         } else if (netVaultDelta < 0) {
            guild.activityLog.add(new GuildStorage.Activity(LOG_TIME.format(Instant.now()), "ITEM_WITHDRAWN", player.getUUID(), (-netVaultDelta) + "x " + itemName, player.getGameProfile().getName()));
         }
      }

      if (touchedPlayer) {
         player.getInventory().setChanged();
         player.inventoryMenu.broadcastChanges();
         player.containerMenu.broadcastChanges();
      }
      if (touchedVault) {
         storage.saveGuild(guild);
         syncGuildMembers(player.getServer(), guild, storage);
      } else {
         sync(player, player.getServer(), storage);
      }
   }

   private static void handleVaultMoveAllMatching(ServerPlayer player, GuildStorage storage, GuildStorage.Guild guild, String argument) {
      if (!canUseGuildVault(player, guild)) {
         sync(player, player.getServer(), storage);
         return;
      }
      String[] parts = argument.split("\\|", 3);
      if (parts.length < 3) {
         sync(player, player.getServer(), storage);
         return;
      }
      String srcType = parts[0];
      String refType = parts[1];
      int refSlot;
      try {
         refSlot = Integer.parseInt(parts[2]);
      } catch (NumberFormatException ex) {
         sync(player, player.getServer(), storage);
         return;
      }
      if ((!"PLAYER".equals(srcType) && !"VAULT".equals(srcType)) || !isValidVaultContainerSlot(refType, refSlot)) {
         sync(player, player.getServer(), storage);
         return;
      }

      String dstType = "PLAYER".equals(srcType) ? "VAULT" : "PLAYER";
      int totalMoved = 0;
      String itemName = "";
      synchronized (guild) {
         NonNullList<ItemStack> vault = guild.getVault();
         ItemStack ref = getSlotStack(player, vault, refType, refSlot);
         if (ref == null || ref.isEmpty()) {
            sync(player, player.getServer(), storage);
            return;
         }
         ItemStack template = ref.copy();
         itemName = template.getHoverName().getString();
         int limit = "PLAYER".equals(srcType) ? 36 : 27;
         int matchingSlots = 0;
         for (int i = 0; i < limit; i++) {
            ItemStack cand = getSlotStack(player, vault, srcType, i);
            if (cand != null && !cand.isEmpty() && ItemStack.isSameItemSameComponents(template, cand)) {
               matchingSlots++;
            }
         }
         if (matchingSlots < 1) {
            sync(player, player.getServer(), storage);
            return;
         }

         int totalBefore = countMatchingItems(player, vault, template);
         ItemStack[] snap = snapshotContainer(player, vault);
         int dstLimit = "VAULT".equals(dstType) ? 27 : 36;
         for (int i = 0; i < limit; i++) {
            ItemStack cand = getSlotStack(player, vault, srcType, i);
            if (cand == null || cand.isEmpty() || !ItemStack.isSameItemSameComponents(template, cand)) continue;
            for (int pass = 0; pass < 2; pass++) {
               boolean emptyPass = (pass == 1);
               for (int j = 0; j < dstLimit; j++) {
                  ItemStack rem = getSlotStack(player, vault, srcType, i);
                  if (rem == null || rem.isEmpty()) break;
                  ItemStack dst = getSlotStack(player, vault, dstType, j);
                  boolean dstEmpty = dst == null || dst.isEmpty();
                  if (emptyPass != dstEmpty) continue;
                  if (!emptyPass && !ItemStack.isSameItemSameComponents(template, dst)) continue;
                  totalMoved += executeSingleVaultTransfer(player, vault, srcType, i, dstType, j, rem.getCount(), false);
               }
            }
         }
         int totalAfter = countMatchingItems(player, vault, template);
         if (totalAfter != totalBefore) {
            restoreContainer(player, vault, snap);
            totalMoved = 0;
         } else if (totalMoved > 0) {
            String actType = "VAULT".equals(dstType) ? "ITEM_DEPOSITED" : "ITEM_WITHDRAWN";
            guild.activityLog.add(new GuildStorage.Activity(LOG_TIME.format(Instant.now()), actType, player.getUUID(), totalMoved + "x " + itemName, player.getGameProfile().getName()));
         }
      }

      player.getInventory().setChanged();
      player.inventoryMenu.broadcastChanges();
      player.containerMenu.broadcastChanges();
      storage.saveGuild(guild);
      syncGuildMembers(player.getServer(), guild, storage);
   }

   private static void handleVaultHotbarSwap(ServerPlayer player, GuildStorage storage, GuildStorage.Guild guild, String argument) {
      if (!canUseGuildVault(player, guild)) {
         sync(player, player.getServer(), storage);
         return;
      }
      String[] parts = argument.split("\\|", 3);
      if (parts.length < 3) {
         sync(player, player.getServer(), storage);
         return;
      }
      String targetType = parts[0];
      int targetSlot;
      int hotbarSlot;
      try {
         targetSlot = Integer.parseInt(parts[1]);
         hotbarSlot = Integer.parseInt(parts[2]);
      } catch (NumberFormatException ex) {
         sync(player, player.getServer(), storage);
         return;
      }
      if (!isValidVaultContainerSlot(targetType, targetSlot) || hotbarSlot < 0 || hotbarSlot >= 9) {
         sync(player, player.getServer(), storage);
         return;
      }
      if ("PLAYER".equals(targetType) && targetSlot == hotbarSlot) {
         return;
      }

      boolean changedVault = false;
      synchronized (guild) {
         NonNullList<ItemStack> vault = guild.getVault();
         ItemStack a = getSlotStack(player, vault, targetType, targetSlot);
         ItemStack b = player.getInventory().getItem(hotbarSlot);
         if ((a == null || a.isEmpty()) && (b == null || b.isEmpty())) {
            return;
         }
         ItemStack aCopy = a == null || a.isEmpty() ? ItemStack.EMPTY : a.copy();
         ItemStack bCopy = b == null || b.isEmpty() ? ItemStack.EMPTY : b.copy();
         int totalABefore = aCopy.isEmpty() ? 0 : countMatchingItems(player, vault, aCopy);
         int totalBBefore = (bCopy.isEmpty() || (!aCopy.isEmpty() && ItemStack.isSameItemSameComponents(aCopy, bCopy))) ? 0 : countMatchingItems(player, vault, bCopy);
         ItemStack[] snap = snapshotContainer(player, vault);

         setSlotStack(player, vault, targetType, targetSlot, bCopy);
         player.getInventory().setItem(hotbarSlot, aCopy);

         int totalAAfter = aCopy.isEmpty() ? 0 : countMatchingItems(player, vault, aCopy);
         int totalBAfter = (bCopy.isEmpty() || (!aCopy.isEmpty() && ItemStack.isSameItemSameComponents(aCopy, bCopy))) ? 0 : countMatchingItems(player, vault, bCopy);
         if (totalAAfter != totalABefore || totalBAfter != totalBBefore) {
            restoreContainer(player, vault, snap);
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            player.containerMenu.broadcastChanges();
            sync(player, player.getServer(), storage);
            return;
         }

         if ("VAULT".equals(targetType)) {
            changedVault = true;
            if (!bCopy.isEmpty()) {
               guild.activityLog.add(new GuildStorage.Activity(LOG_TIME.format(Instant.now()), "ITEM_DEPOSITED", player.getUUID(), bCopy.getCount() + "x " + bCopy.getHoverName().getString(), player.getGameProfile().getName()));
            }
            if (!aCopy.isEmpty()) {
               guild.activityLog.add(new GuildStorage.Activity(LOG_TIME.format(Instant.now()), "ITEM_WITHDRAWN", player.getUUID(), aCopy.getCount() + "x " + aCopy.getHoverName().getString(), player.getGameProfile().getName()));
            }
         }
      }

      player.getInventory().setChanged();
      player.inventoryMenu.broadcastChanges();
      player.containerMenu.broadcastChanges();
      if (changedVault) {
         storage.saveGuild(guild);
         syncGuildMembers(player.getServer(), guild, storage);
      } else {
         sync(player, player.getServer(), storage);
      }
   }

   private static void handleVaultThrow(ServerPlayer player, GuildStorage storage, GuildStorage.Guild guild, String argument) {
      if (!canUseGuildVault(player, guild)) {
         sync(player, player.getServer(), storage);
         return;
      }
      String[] parts = argument.split("\\|", 3);
      if (parts.length < 3) {
         sync(player, player.getServer(), storage);
         return;
      }
      String targetType = parts[0];
      int targetSlot;
      try {
         targetSlot = Integer.parseInt(parts[1]);
      } catch (NumberFormatException ex) {
         sync(player, player.getServer(), storage);
         return;
      }
      boolean throwAll = "1".equals(parts[2]);
      if (!isValidVaultContainerSlot(targetType, targetSlot)) {
         sync(player, player.getServer(), storage);
         return;
      }

      ItemStack dropped = ItemStack.EMPTY;
      boolean touchedVault = "VAULT".equals(targetType);
      synchronized (guild) {
         NonNullList<ItemStack> vault = guild.getVault();
         ItemStack src = getSlotStack(player, vault, targetType, targetSlot);
         if (src == null || src.isEmpty()) {
            sync(player, player.getServer(), storage);
            return;
         }
         int count = throwAll ? src.getCount() : 1;
         dropped = src.copyWithCount(count);
         ItemStack rem = src.getCount() > count ? src.copyWithCount(src.getCount() - count) : ItemStack.EMPTY;
         setSlotStack(player, vault, targetType, targetSlot, rem);

         if (touchedVault) {
            guild.activityLog.add(new GuildStorage.Activity(LOG_TIME.format(Instant.now()), "ITEM_WITHDRAWN", player.getUUID(), count + "x " + dropped.getHoverName().getString(), player.getGameProfile().getName()));
         }
      }

      if (!dropped.isEmpty()) {
         player.drop(dropped, false, true);
      }
      player.getInventory().setChanged();
      player.inventoryMenu.broadcastChanges();
      player.containerMenu.broadcastChanges();
      if (touchedVault) {
         storage.saveGuild(guild);
         syncGuildMembers(player.getServer(), guild, storage);
      } else {
         sync(player, player.getServer(), storage);
      }
   }

   private static void handle(ServerPlayer player, GuildRequestPayload payload) {
      GuildStorage storage = storageSupplier == null ? null : storageSupplier.get();
      if (storage != null) {
         GuildStorage.Guild guild = guildFor(player, storage);
         if ("VAULT_QUICK_MOVE".equals(payload.action())) {
            handleVaultQuickMove(player, storage, guild, payload.argument());
         } else if ("VAULT_TRANSFER".equals(payload.action())) {
            handleVaultTransfer(player, storage, guild, payload.argument());
         } else if ("VAULT_BATCH_TRANSFER".equals(payload.action())) {
            handleVaultBatchTransfer(player, storage, guild, payload.argument());
         } else if ("VAULT_PICKUP_ALL".equals(payload.action())) {
            handleVaultPickupAll(player, storage, guild, payload.argument());
         } else if ("VAULT_MOVE_ALL_MATCHING".equals(payload.action())) {
            handleVaultMoveAllMatching(player, storage, guild, payload.argument());
         } else if ("VAULT_HOTBAR_SWAP".equals(payload.action())) {
            handleVaultHotbarSwap(player, storage, guild, payload.argument());
         } else if ("VAULT_THROW".equals(payload.action())) {
            handleVaultThrow(player, storage, guild, payload.argument());
         } else if ("DECLINE".equals(payload.action())) {
            storage.index().invitations.remove(player.getUUID().toString());
            storage.save();
            sync(player, player.getServer(), storage);
            player.sendSystemMessage(Component.literal("Has rechazado la invitación al gremio."));
         } else if (!"CHAT".equals(payload.action()) && !"SEND_CHAT".equals(payload.action())) {
            if ("PC".equals(payload.action())) {
               GuildPCManager pcManager = DesgraciamonGuilds.getPcManager();
               if (pcManager != null) {
                  int box = payload.amount().intValue();

                  try {
                     if (payload.argument() != null && !payload.argument().isBlank()) {
                        box = Integer.parseInt(payload.argument().trim());
                     }
                  } catch (NumberFormatException var18) {
                  }

                  pcManager.open(player, Math.max(0, Math.min(29, box)));
               }
            } else if ("SYNC_PRIVATE_PC".equals(payload.action())) {
               try {
                  PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(player);
                  PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
                  PCLinkManager.INSTANCE.addLink(new PermissiblePcLink(pc, player, CobblemonPermissions.getPC()));
                  pc.addObserver(player);
                  pc.sendTo(player);
                  if (party != null) {
                     if (!party.getObserverUUIDs().contains(player.getUUID())) {
                        party.getObserverUUIDs().add(player.getUUID());
                     }

                     party.sendTo(player);
                     new SetPartyReferencePacket(party.getUuid()).sendToPlayer(player);
                  }
               } catch (Throwable var19) {
               }
            } else if ("OPEN_OWN_PC".equals(payload.action())) {
               try {
                  int box = payload.amount().intValue();

                  try {
                     if (payload.argument() != null && !payload.argument().isBlank()) {
                        box = Integer.parseInt(payload.argument().trim());
                     }
                  } catch (NumberFormatException var20) {
                  }

                  PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(player);
                  PCLinkManager.INSTANCE.addLink(new PermissiblePcLink(pc, player, CobblemonPermissions.getPC()));
                  new OpenPCPacket(pc, box).sendToPlayer(player);
               } catch (Throwable var21) {
               }
            } else if ("TRANSFER_POKEMON".equals(payload.action())) {
               try {
                  String[] parts = payload.argument().split("\\|", 6);
                  if (parts.length == 6) {
                     String srcType = parts[0];
                     int srcBox = Integer.parseInt(parts[1]);
                     int srcSlotx = Integer.parseInt(parts[2]);
                     String dstType = parts[3];
                     int dstBox = Integer.parseInt(parts[4]);
                     int dstSlot = Integer.parseInt(parts[5]);
                     PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
                     PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(player);
                     GuildPCManager pcManager = DesgraciamonGuilds.getPcManager();
                     PCStore guildPc = guild != null && !guild.disbanded && pcManager != null ? pcManager.getStore(guild) : null;
                     Pokemon srcPoke = null;
                     if ("PARTY".equals(srcType)) {
                        srcPoke = party.get(srcSlotx);
                     } else if ("PC".equals(srcType)) {
                        srcPoke = pc.get(new PCPosition(srcBox, srcSlotx));
                     } else if ("GUILD".equals(srcType) && guildPc != null) {
                        srcPoke = guildPc.get(new PCPosition(srcBox, srcSlotx));
                     }

                     if (srcPoke == null) {
                        return;
                     }

                     Pokemon dstPoke = null;
                     if ("PARTY".equals(dstType)) {
                        dstPoke = party.get(dstSlot);
                     } else if ("PC".equals(dstType)) {
                        dstPoke = pc.get(new PCPosition(dstBox, dstSlot));
                     } else if ("GUILD".equals(dstType) && guildPc != null) {
                        dstPoke = guildPc.get(new PCPosition(dstBox, dstSlot));
                     }

                     if (srcType.equals(dstType) && srcBox == dstBox && srcSlotx == dstSlot) {
                        return;
                     }

                     if ("PARTY".equals(srcType) && !"PARTY".equals(dstType) && dstPoke == null && party.occupied() <= 1) {
                        player.sendSystemMessage(Component.literal("§c[Gremio] ¡Debes tener al menos 1 Pokémon en tu equipo!"));
                        return;
                     }

                     if ("GUILD".equals(dstType) && DesgraciamonGuilds.isRestrictedPokemon(srcPoke)) {
                        player.sendSystemMessage(Component.literal("§c[Gremio] No puedes almacenar Pokémon Legendarios ni Míticos en el PC compartido."));
                        return;
                     }

                     if ("GUILD".equals(srcType) && dstPoke != null && DesgraciamonGuilds.isRestrictedPokemon(dstPoke)) {
                        player.sendSystemMessage(Component.literal("§c[Gremio] No puedes almacenar Pokémon Legendarios ni Míticos en el PC compartido."));
                        return;
                     }

                     if (srcType.equals(dstType)) {
                        if ("PARTY".equals(srcType)) {
                           party.swap(new PartyPosition(srcSlotx), new PartyPosition(dstSlot));
                        } else if ("PC".equals(srcType)) {
                           pc.swap(new PCPosition(srcBox, srcSlotx), new PCPosition(dstBox, dstSlot));
                        } else if ("GUILD".equals(srcType) && guildPc != null) {
                           guildPc.swap(new PCPosition(srcBox, srcSlotx), new PCPosition(dstBox, dstSlot));
                        }
                     } else {
                        if ("PARTY".equals(srcType)) {
                           party.remove(new PartyPosition(srcSlotx));
                        } else if ("PC".equals(srcType)) {
                           pc.remove(new PCPosition(srcBox, srcSlotx));
                        } else if ("GUILD".equals(srcType) && guildPc != null) {
                           guildPc.remove(new PCPosition(srcBox, srcSlotx));
                        }

                        if (dstPoke != null) {
                           if ("PARTY".equals(dstType)) {
                              party.remove(new PartyPosition(dstSlot));
                           } else if ("PC".equals(dstType)) {
                              pc.remove(new PCPosition(dstBox, dstSlot));
                           } else if ("GUILD".equals(dstType) && guildPc != null) {
                              guildPc.remove(new PCPosition(dstBox, dstSlot));
                           }

                           if ("PARTY".equals(srcType)) {
                              party.set(new PartyPosition(srcSlotx), dstPoke);
                           } else if ("PC".equals(srcType)) {
                              pc.set(new PCPosition(srcBox, srcSlotx), dstPoke);
                           } else if ("GUILD".equals(srcType) && guildPc != null) {
                              dstPoke.getPersistentData().putString("guild_depositor_name", player.getGameProfile().getName());
                              guildPc.set(new PCPosition(srcBox, srcSlotx), dstPoke);
                           }
                        }

                        if ("PARTY".equals(dstType)) {
                           party.set(new PartyPosition(dstSlot), srcPoke);
                        } else if ("PC".equals(dstType)) {
                           pc.set(new PCPosition(dstBox, dstSlot), srcPoke);
                        } else if ("GUILD".equals(dstType) && guildPc != null) {
                           srcPoke.getPersistentData().putString("guild_depositor_name", player.getGameProfile().getName());
                           guildPc.set(new PCPosition(dstBox, dstSlot), srcPoke);
                        }
                     }

                     if (guild != null && !guild.disbanded && pcManager != null) {
                        if ("GUILD".equals(srcType) && !"GUILD".equals(dstType)) {
                           guild.activityLog
                              .add(
                                 new GuildStorage.Activity(
                                    LOG_TIME.format(Instant.now()),
                                    "POKEMON_WITHDRAWN",
                                    player.getUUID(),
                                    srcPoke.getSpecies().getName() + " (Nv." + srcPoke.getLevel() + ")"
                                 )
                              );
                        }

                        if ("GUILD".equals(dstType) && !"GUILD".equals(srcType)) {
                           guild.activityLog
                              .add(
                                 new GuildStorage.Activity(
                                    LOG_TIME.format(Instant.now()),
                                    "POKEMON_DEPOSITED",
                                    player.getUUID(),
                                    srcPoke.getSpecies().getName() + " (Nv." + srcPoke.getLevel() + ")"
                                 )
                              );
                        }

                        pcManager.markDirty(guild.guildId);
                        storage.save();
                     }

                     pc.addObserver(player);
                     pc.sendTo(player);
                     if (!party.getObserverUUIDs().contains(player.getUUID())) {
                        party.getObserverUUIDs().add(player.getUUID());
                     }

                     party.sendTo(player);
                     new SetPartyReferencePacket(party.getUuid()).sendToPlayer(player);
                     syncAll(player.getServer());
                  }
               } catch (Throwable var23) {
                  var23.printStackTrace();
               }
            } else if ("MOTD".equals(payload.action())) {
               if (guild != null && !guild.disbanded && hasGuildRole(guild, player, "LEADER", "OFFICER")) {
                  String text = payload.argument() == null ? "" : payload.argument().trim();
                  if (text.length() > 350) {
                     text = text.substring(0, 350);
                  }

                  guild.motd = text;
                  guild.activityLog
                     .add(
                        new GuildStorage.Activity(
                           LOG_TIME.format(Instant.now()), "MOTD_UPDATED", player.getUUID(), text.replace("\n", " "), player.getGameProfile().getName()
                        )
                     );
                  storage.save();
                  syncAll(player.getServer());
               }
            } else if ("ICON".equals(payload.action())) {
               if (guild != null && !guild.disbanded && hasGuildRole(guild, player, "LEADER")) {
                  guild.icon = payload.argument().trim().toLowerCase();
                  storage.save();
                  syncAll(player.getServer());
               }
            } else if ("SHARE_POS".equals(payload.action())) {
               if (guild != null && !guild.disbanded && hasGuildRole(guild, player, "LEADER", "OFFICER", "MEMBER")) {
                  String wpName = payload.argument().isBlank() ? "Base de " + player.getGameProfile().getName() : payload.argument().trim();
                  if (wpName.length() > 32) {
                     wpName = wpName.substring(0, 32);
                  }

                  String dim = player.level().dimension().location().toString();
                  int x = player.getBlockX();
                  int y = player.getBlockY();
                  int z = player.getBlockZ();
                  GuildStorage.Waypoint wp = new GuildStorage.Waypoint(wpName, player.getUUID(), dim, x, y, z);
                  wp.shared = true;
                  wp.sharedBy = player.getGameProfile().getName();
                  wp.sharedTimestamp = System.currentTimeMillis();
                  guild.waypoints.add(wp);
                  guild.activityLog
                     .add(
                        new GuildStorage.Activity(
                           LOG_TIME.format(Instant.now()), "WAYPOINT_SHARED", player.getUUID(), wpName, player.getGameProfile().getName()
                        )
                     );
                  storage.save();
                  syncAll(player.getServer());
               }
            } else {
               String command = commandFor(payload);
               if (command != null) {
                  try {
                     player.getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
                  } catch (RuntimeException var22) {
                     player.sendSystemMessage(Component.literal("No se pudo completar la accion del gremio."));
                  }
               }

               syncAll(player.getServer());
            }
         } else {
            if (guild != null && !guild.disbanded) {
               sendGuildChatMessage(player.getServer(), player, guild, payload.argument());
               storage.save();
               syncAll(player.getServer());
            }
         }
      }
   }

   private static void importWaypoint(ServerPlayer player, GuildWaypointImportPayload payload) {
      GuildStorage storage = storageSupplier == null ? null : storageSupplier.get();
      if (storage != null) {
         String guildId = storage.index().playerGuilds.get(player.getUUID().toString());
         GuildStorage.Guild guild = guildId == null ? null : storage.getGuild(guildId);
         ResourceLocation dimension = ResourceLocation.tryParse(payload.dimension());
         if (guild != null
            && !guild.disbanded
            && !payload.name().isBlank()
            && payload.name().length() <= 64
            && dimension != null
            && Math.abs((long)payload.x()) <= 30000000L
            && Math.abs((long)payload.z()) <= 30000000L
            && payload.y() >= -2048
            && payload.y() <= 2048) {
            if (payload.share()) {
               boolean alreadyShared = guild.waypoints
                  .stream()
                  .anyMatch(
                     existing -> existing.shared
                           && existing.ownerUuid.equals(player.getUUID().toString())
                           && existing.name.equalsIgnoreCase(payload.name())
                           && existing.dimension.equals(dimension.toString())
                           && existing.x == payload.x()
                           && existing.y == payload.y()
                           && existing.z == payload.z()
                  );
               if (alreadyShared) {
                  syncAll(player.getServer());
                  return;
               }
            }

            if (!payload.share() || hasGuildRole(guild, player, "LEADER", "OFFICER", "MEMBER")) {
               GuildStorage.Waypoint waypoint = new GuildStorage.Waypoint(
                  payload.name(), player.getUUID(), dimension.toString(), payload.x(), payload.y(), payload.z()
               );
               waypoint.shared = payload.share();
               if (waypoint.shared) {
                  waypoint.sharedBy = player.getGameProfile().getName();
                  waypoint.sharedTimestamp = System.currentTimeMillis();
               }

               guild.waypoints.add(waypoint);
               guild.activityLog
                  .add(
                     new GuildStorage.Activity(
                        LOG_TIME.format(Instant.now()),
                        waypoint.shared ? "WAYPOINT_SHARED" : "WAYPOINT_IMPORTED",
                        player.getUUID(),
                        payload.name(),
                        player.getGameProfile().getName()
                     )
                  );
               storage.save();
               syncAll(player.getServer());
            }
         } else {
            player.sendSystemMessage(Component.literal("Waypoint Xaero invalido o acceso denegado."));
         }
      }
   }

   private static GuildStorage.Guild guildFor(ServerPlayer player, GuildStorage storage) {
      String id = storage.index().playerGuilds.get(player.getUUID().toString());
      return id == null ? null : storage.getGuild(id);
   }

   private static boolean hasGuildRole(GuildStorage.Guild guild, ServerPlayer player, String... roles) {
      GuildStorage.Member member = guild.members.get(player.getUUID().toString());
      if (member == null) {
         return false;
      } else {
         for (String role : roles) {
            if (role.equals(member.role)) {
               return true;
            }
         }

         return false;
      }
   }

   private static String commandFor(GuildRequestPayload payload) {
      String argument = payload.argument();
      String var2 = payload.action();

      return switch (var2) {
         case "SYNC" -> null;
         case "CREATE" -> createCommand(argument);
         case "ACCEPT" -> "gremio aceptar";
         case "LEAVE" -> argument.isBlank() ? "gremio salir" : safeCommand("gremio salir ", argument);
         case "DISBAND" -> "gremio disolver";
         case "PC" -> "gremio pc";
         case "INVITE" -> safeCommand("gremio invitar ", argument);
         case "KICK" -> safeCommand("gremio expulsar ", argument);
         case "PROMOTE" -> safeCommand("gremio ascender ", argument);
         case "DEMOTE" -> safeCommand("gremio degradar ", argument);
         case "RENAME" -> {
            String normalized = GuildCommands.normalizeGuildName(argument);
            yield normalized == null ? null : "gremio renombrar " + normalized;
         }
         case "COLOR" -> safeCommand("gremio color ", argument);
         case "WAYPOINT_ADD" -> safeCommand("gremio punto agregar ", argument);
         case "WAYPOINT_SHARE" -> safeCommand("gremio punto compartir ", argument);
         case "WAYPOINT_REMOVE" -> safeCommand("gremio punto quitar ", argument);
         case "WAYPOINT_LIST" -> "gremio punto lista";
         case "DEPOSIT" -> payload.amount().signum() > 0 && payload.amount().toString().length() <= 100
         ? "gremio depositar " + payload.amount()
         : null;
         case "WITHDRAW" -> payload.amount().signum() > 0 && payload.amount().toString().length() <= 100
         ? "gremio retirar " + payload.amount()
         : null;
         default -> null;
      };
   }

   private static String createCommand(String argument) {
      String[] values = argument.split("\\|", 2);
      if (values.length != 2) {
         return null;
      } else {
         String name = GuildCommands.normalizeGuildName(values[0]);
         if (name == null) {
            return null;
         } else {
            String color = values[1].startsWith("#") ? values[1] : "#" + values[1];
            return !color.matches("#[0-9a-fA-F]{6}") ? null : "gremio crear " + name + " " + color;
         }
      }
   }

   private static String formatActivity(GuildStorage.Activity activity, GuildStorage.Guild guild) {
      String actor = activity.actorName;
      if (actor == null || actor.isBlank()) {
         GuildStorage.Member member = guild.members.get(activity.actorUuid);
         actor = member == null ? "Un jugador" : member.playerName;
      }

      String details = activity.details == null ? "" : activity.details;
      String var5 = activity.event;

      String message = switch (var5) {
         case "CREATED" -> actor + " creó el gremio";
         case "INVITED" -> actor + " invitó a " + details;
         case "JOINED" -> actor + " se unió al gremio";
         case "LEFT" -> details.replace("salio del gremio", "salió del gremio");
         case "KICKED" -> details.replace("expulsado del gremio", "fue expulsado del gremio");
         case "PROMOTED" -> {
            String target = firstWord(details);
            String rank = details.endsWith("LEADER") ? "líder" : "oficial";
            yield actor + " ascendió a " + rank + " a " + target;
         }
         case "DEMOTED" -> {
            String target = firstWord(details);
            String rank = details.endsWith("OFFICER") ? "oficial" : "miembro";
            yield actor + " degradó a " + rank + " a " + target;
         }
         case "RENAMED" -> actor + " renombró el gremio: " + details;
         case "MOTD_UPDATED" -> actor + " actualizó el tablón: " + details;
         case "COLOR_CHANGED" -> actor + " cambió el color";
         case "DISBANDED" -> actor + " disolvió el gremio";
         case "LEADERSHIP_TRANSFERRED" -> actor + " transfirió el liderazgo: " + details;
         case "DEBUG_BANK_SET" -> actor + " ajustó el banco (debug): " + details;
         case "DEBUG_COOLDOWN_CLEARED" -> actor + " quitó el cooldown (debug) de " + firstWord(details);
         case "DEBUG_COOLDOWN_ADDED" -> actor + " añadió cooldown (debug): " + details;
         case "DEBUG_COOLDOWN_SUBTRACTED" -> actor + " restó cooldown (debug): " + details;
         case "MONEY_DEPOSITED" -> actor + " depositó " + details + " $";
         case "MONEY_WITHDRAWN" -> actor + " retiró " + details + " $";
         case "POKEMON_DEPOSITED" -> {
            String clean = stripUuidAndData(details);
            String dataSuffix = details.contains("§DATA:") ? " §DATA:" + details.substring(details.indexOf("§DATA:") + 6) + " §END" : "";
            yield actor + " guardó " + clean + dataSuffix;
         }
         case "POKEMON_WITHDRAWN" -> {
            String clean = stripUuidAndData(details);
            String dataSuffix = details.contains("§DATA:") ? " §DATA:" + details.substring(details.indexOf("§DATA:") + 6) + " §END" : "";
            yield actor + " retiró " + clean + dataSuffix;
         }
         case "ITEM_DEPOSITED" -> actor + " depositó en el cofre: " + details;
         case "ITEM_WITHDRAWN" -> actor + " retiró del cofre: " + details;
         case "WAYPOINT_CREATED" -> actor + " creó la ruta " + firstWord(details);
         case "WAYPOINT_SHARED" -> actor + " compartió la ruta " + details;
         case "WAYPOINT_DELETED" -> actor + " borró la ruta " + details;
         case "WAYPOINT_IMPORTED" -> actor + " agregó la ruta " + firstWord(details) + " al gremio";
         default -> actor + " actualizo el gremio";
      };
      return "[" + activity.timestamp + "] " + message;
   }

   private static String stripUuidAndData(String text) {
      if (text == null) {
         return "";
      } else {
         if (text.contains("§DATA:")) {
            text = text.substring(0, text.indexOf("§DATA:"));
         }

         int idx = text.indexOf("; UUID=");
         if (idx >= 0) {
            text = text.substring(0, idx);
         }

         int idx2 = text.indexOf("; uuid=");
         if (idx2 >= 0) {
            text = text.substring(0, idx2);
         }

         return text.trim();
      }
   }

   private static String firstWord(String value) {
      int separator = value.indexOf(32);
      return separator < 0 ? value : value.substring(0, separator);
   }

   private static String safeCommand(String prefix, String argument) {
      return ARGUMENT.matcher(argument).matches() ? prefix + argument : null;
   }
}
