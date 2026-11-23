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
   private static final DateTimeFormatter OFFLINE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());
   private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy - HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());
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
            tags.add(online.getUUID() + "|" + tagGuild.name + "|" + tagGuild.colorHex);
         }
      }

      List<String> pcPreviews = new ArrayList<>();
      if (member && DesgraciamonGuilds.getPcManager() != null) {
         pcPreviews = DesgraciamonGuilds.getPcManager().getBoxPreviews(guild);
      }

      List<String> chatList = new ArrayList<>();
      if (member && guild.chatMessages != null) {
         DateTimeFormatter chatTime = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());
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

   private static void handle(ServerPlayer player, GuildRequestPayload payload) {
      GuildStorage storage = storageSupplier == null ? null : storageSupplier.get();
      if (storage != null) {
         GuildStorage.Guild guild = guildFor(player, storage);
         if ("VAULT_QUICK_MOVE".equals(payload.action())) {
            if (guild != null && !guild.disbanded) {
               String[] parts = payload.argument().split("\\|", 2);
               if (parts.length >= 2) {
                  String srcType = parts[0];

                  int srcSlot;
                  try {
                     srcSlot = Integer.parseInt(parts[1]);
                  } catch (NumberFormatException var17) {
                     return;
                  }

                  if ("PLAYER".equals(srcType)) {
                     if (srcSlot < 0 || srcSlot >= player.getInventory().getContainerSize()) {
                        return;
                     }

                     ItemStack stack = player.getInventory().getItem(srcSlot);
                     if (stack.isEmpty()) {
                        return;
                     }

                     String itemName = stack.getHoverName().getString();
                     int original = stack.getCount();
                     NonNullList<ItemStack> vault = guild.getVault();

                     for (int i = 0; i < 27 && !stack.isEmpty(); i++) {
                        ItemStack v = (ItemStack)vault.get(i);
                        if (!v.isEmpty() && ItemStack.isSameItemSameComponents(stack, v)) {
                           int space = v.getMaxStackSize() - v.getCount();
                           if (space > 0) {
                              int toMove = Math.min(space, stack.getCount());
                              v.grow(toMove);
                              stack.shrink(toMove);
                           }
                        }
                     }

                     for (int ix = 0; ix < 27 && !stack.isEmpty(); ix++) {
                        ItemStack v = (ItemStack)vault.get(ix);
                        if (v.isEmpty()) {
                           vault.set(ix, stack.copy());
                           stack.setCount(0);
                           break;
                        }
                     }

                     int moved = original - stack.getCount();
                     if (moved > 0) {
                        if (stack.isEmpty()) {
                           player.getInventory().setItem(srcSlot, ItemStack.EMPTY);
                        }

                        guild.activityLog
                           .add(
                              new GuildStorage.Activity(
                                 LOG_TIME.format(Instant.now()), "ITEM_DEPOSITED", player.getUUID(), moved + "x " + itemName, player.getGameProfile().getName()
                              )
                           );
                        storage.save();
                        player.containerMenu.broadcastChanges();
                        syncAll(player.getServer());
                     }
                  } else if ("VAULT".equals(srcType)) {
                     if (srcSlot < 0 || srcSlot >= 27) {
                        return;
                     }

                     NonNullList<ItemStack> vault = guild.getVault();
                     ItemStack v = (ItemStack)vault.get(srcSlot);
                     if (v.isEmpty()) {
                        return;
                     }

                     String itemName = v.getHoverName().getString();
                     int original = v.getCount();
                     ItemStack toInsert = v.copy();
                     player.getInventory().add(toInsert);
                     int moved = original - toInsert.getCount();
                     if (moved > 0) {
                        if (toInsert.isEmpty()) {
                           vault.set(srcSlot, ItemStack.EMPTY);
                        } else {
                           v.setCount(toInsert.getCount());
                        }

                        guild.activityLog
                           .add(
                              new GuildStorage.Activity(
                                 LOG_TIME.format(Instant.now()), "ITEM_WITHDRAWN", player.getUUID(), moved + "x " + itemName, player.getGameProfile().getName()
                              )
                           );
                        storage.save();
                        player.containerMenu.broadcastChanges();
                        syncAll(player.getServer());
                     }
                  }
               }
            }
         } else if ("VAULT_TRANSFER".equals(payload.action())) {
            if (guild != null && !guild.disbanded) {
               String[] parts = payload.argument().split("\\|", 5);
               if (parts.length >= 5) {
                  String srcType = parts[0];
                  int srcSlotx = Integer.parseInt(parts[1]);
                  String dstType = parts[2];
                  int dstSlot = Integer.parseInt(parts[3]);
                  int amount = Integer.parseInt(parts[4]);
                  NonNullList<ItemStack> vaultx = guild.getVault();
                  if ("PLAYER".equals(srcType) && "VAULT".equals(dstType)) {
                     if (srcSlotx < 0 || srcSlotx >= player.getInventory().getContainerSize()) {
                        return;
                     }

                     ItemStack src = player.getInventory().getItem(srcSlotx);
                     if (src.isEmpty()) {
                        return;
                     }

                     int toMove = amount > 0 && amount <= src.getCount() ? amount : src.getCount();
                     String itemName = src.getHoverName().getString();
                     if (dstSlot >= 0 && dstSlot < 27) {
                        ItemStack dst = (ItemStack)vaultx.get(dstSlot);
                        if (dst.isEmpty()) {
                           vaultx.set(dstSlot, src.copyWithCount(toMove));
                           src.shrink(toMove);
                           if (src.isEmpty()) {
                              player.getInventory().setItem(srcSlotx, ItemStack.EMPTY);
                           }

                           guild.activityLog
                              .add(
                                 new GuildStorage.Activity(
                                    LOG_TIME.format(Instant.now()),
                                    "ITEM_DEPOSITED",
                                    player.getUUID(),
                                    toMove + "x " + itemName,
                                    player.getGameProfile().getName()
                                 )
                              );
                        } else if (ItemStack.isSameItemSameComponents(src, dst)) {
                           int space = dst.getMaxStackSize() - dst.getCount();
                           int actual = Math.min(space, toMove);
                           if (actual > 0) {
                              dst.grow(actual);
                              src.shrink(actual);
                              if (src.isEmpty()) {
                                 player.getInventory().setItem(srcSlotx, ItemStack.EMPTY);
                              }

                              guild.activityLog
                                 .add(
                                    new GuildStorage.Activity(
                                       LOG_TIME.format(Instant.now()),
                                       "ITEM_DEPOSITED",
                                       player.getUUID(),
                                       actual + "x " + itemName,
                                       player.getGameProfile().getName()
                                    )
                                 );
                           }
                        } else if (toMove == src.getCount()) {
                           vaultx.set(dstSlot, src.copy());
                           player.getInventory().setItem(srcSlotx, dst.copy());
                           guild.activityLog
                              .add(
                                 new GuildStorage.Activity(
                                    LOG_TIME.format(Instant.now()),
                                    "ITEM_DEPOSITED",
                                    player.getUUID(),
                                    src.getCount() + "x " + itemName,
                                    player.getGameProfile().getName()
                                 )
                              );
                        }

                        storage.save();
                        player.containerMenu.broadcastChanges();
                        syncAll(player.getServer());
                     }
                  } else if ("VAULT".equals(srcType) && "PLAYER".equals(dstType)) {
                     if (srcSlotx < 0 || srcSlotx >= 27) {
                        return;
                     }

                     ItemStack srcx = (ItemStack)vaultx.get(srcSlotx);
                     if (srcx.isEmpty()) {
                        return;
                     }

                     int toMove = amount > 0 && amount <= srcx.getCount() ? amount : srcx.getCount();
                     String itemName = srcx.getHoverName().getString();
                     if (dstSlot >= 0 && dstSlot < player.getInventory().getContainerSize()) {
                        ItemStack dst = player.getInventory().getItem(dstSlot);
                        if (dst.isEmpty()) {
                           player.getInventory().setItem(dstSlot, srcx.copyWithCount(toMove));
                           srcx.shrink(toMove);
                           if (srcx.isEmpty()) {
                              vaultx.set(srcSlotx, ItemStack.EMPTY);
                           }

                           guild.activityLog
                              .add(
                                 new GuildStorage.Activity(
                                    LOG_TIME.format(Instant.now()),
                                    "ITEM_WITHDRAWN",
                                    player.getUUID(),
                                    toMove + "x " + itemName,
                                    player.getGameProfile().getName()
                                 )
                              );
                        } else if (ItemStack.isSameItemSameComponents(srcx, dst)) {
                           int space = dst.getMaxStackSize() - dst.getCount();
                           int actual = Math.min(space, toMove);
                           if (actual > 0) {
                              dst.grow(actual);
                              srcx.shrink(actual);
                              if (srcx.isEmpty()) {
                                 vaultx.set(srcSlotx, ItemStack.EMPTY);
                              }

                              guild.activityLog
                                 .add(
                                    new GuildStorage.Activity(
                                       LOG_TIME.format(Instant.now()),
                                       "ITEM_WITHDRAWN",
                                       player.getUUID(),
                                       actual + "x " + itemName,
                                       player.getGameProfile().getName()
                                    )
                                 );
                           }
                        } else if (toMove == srcx.getCount()) {
                           player.getInventory().setItem(dstSlot, srcx.copy());
                           vaultx.set(srcSlotx, dst.copy());
                           guild.activityLog
                              .add(
                                 new GuildStorage.Activity(
                                    LOG_TIME.format(Instant.now()),
                                    "ITEM_WITHDRAWN",
                                    player.getUUID(),
                                    srcx.getCount() + "x " + itemName,
                                    player.getGameProfile().getName()
                                 )
                              );
                        }

                        storage.save();
                        player.containerMenu.broadcastChanges();
                        syncAll(player.getServer());
                     }
                  } else if ("VAULT".equals(srcType) && "VAULT".equals(dstType)) {
                     if (srcSlotx < 0 || srcSlotx >= 27 || dstSlot < 0 || dstSlot >= 27 || srcSlotx == dstSlot) {
                        return;
                     }

                     ItemStack srcxx = (ItemStack)vaultx.get(srcSlotx);
                     if (srcxx.isEmpty()) {
                        return;
                     }

                     int toMove = amount > 0 && amount <= srcxx.getCount() ? amount : srcxx.getCount();
                     ItemStack dst = (ItemStack)vaultx.get(dstSlot);
                     if (dst.isEmpty()) {
                        vaultx.set(dstSlot, srcxx.copyWithCount(toMove));
                        srcxx.shrink(toMove);
                        if (srcxx.isEmpty()) {
                           vaultx.set(srcSlotx, ItemStack.EMPTY);
                        }
                     } else if (ItemStack.isSameItemSameComponents(srcxx, dst)) {
                        int space = dst.getMaxStackSize() - dst.getCount();
                        int actual = Math.min(space, toMove);
                        if (actual > 0) {
                           dst.grow(actual);
                           srcxx.shrink(actual);
                           if (srcxx.isEmpty()) {
                              vaultx.set(srcSlotx, ItemStack.EMPTY);
                           }
                        }
                     } else if (toMove == srcxx.getCount()) {
                        vaultx.set(dstSlot, srcxx);
                        vaultx.set(srcSlotx, dst);
                     }

                     storage.save();
                     syncAll(player.getServer());
                  } else if ("PLAYER".equals(srcType) && "PLAYER".equals(dstType)) {
                     if (srcSlotx < 0
                        || srcSlotx >= player.getInventory().getContainerSize()
                        || dstSlot < 0
                        || dstSlot >= player.getInventory().getContainerSize()
                        || srcSlotx == dstSlot) {
                        return;
                     }

                     ItemStack srcxxx = player.getInventory().getItem(srcSlotx);
                     if (srcxxx.isEmpty()) {
                        return;
                     }

                     int toMove = amount > 0 && amount <= srcxxx.getCount() ? amount : srcxxx.getCount();
                     ItemStack dst = player.getInventory().getItem(dstSlot);
                     if (dst.isEmpty()) {
                        player.getInventory().setItem(dstSlot, srcxxx.copyWithCount(toMove));
                        srcxxx.shrink(toMove);
                        if (srcxxx.isEmpty()) {
                           player.getInventory().setItem(srcSlotx, ItemStack.EMPTY);
                        }
                     } else if (ItemStack.isSameItemSameComponents(srcxxx, dst)) {
                        int space = dst.getMaxStackSize() - dst.getCount();
                        int actual = Math.min(space, toMove);
                        if (actual > 0) {
                           dst.grow(actual);
                           srcxxx.shrink(actual);
                           if (srcxxx.isEmpty()) {
                              player.getInventory().setItem(srcSlotx, ItemStack.EMPTY);
                           }
                        }
                     } else if (toMove == srcxxx.getCount()) {
                        player.getInventory().setItem(dstSlot, srcxxx.copy());
                        player.getInventory().setItem(srcSlotx, dst.copy());
                     }

                     player.containerMenu.broadcastChanges();
                     sync(player, player.getServer(), storage);
                  }
               }
            }
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
         case "ACCEPT" -> "guild accept";
         case "LEAVE" -> argument.isBlank() ? "guild leave" : safeCommand("guild leave ", argument);
         case "DISBAND" -> "guild disband";
         case "PC" -> "guild pc";
         case "INVITE" -> safeCommand("guild invite ", argument);
         case "KICK" -> safeCommand("guild kick ", argument);
         case "PROMOTE" -> safeCommand("guild promote ", argument);
         case "DEMOTE" -> safeCommand("guild demote ", argument);
         case "RENAME" -> {
            String normalized = GuildCommands.normalizeGuildName(argument);
            yield normalized == null ? null : "guild rename " + normalized;
         }
         case "COLOR" -> safeCommand("guild color ", argument);
         case "WAYPOINT_ADD" -> safeCommand("guild waypoint add ", argument);
         case "WAYPOINT_SHARE" -> safeCommand("guild waypoint share ", argument);
         case "WAYPOINT_REMOVE" -> safeCommand("guild waypoint remove ", argument);
         case "WAYPOINT_LIST" -> "guild waypoint list";
         case "DEPOSIT", "WITHDRAW" -> payload.amount().signum() > 0 && payload.amount().toString().length() <= 100
         ? "guild " + payload.action().toLowerCase(Locale.ROOT) + " " + payload.amount()
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
            return !color.matches("#[0-9a-fA-F]{6}") ? null : "guild create " + name + " " + color;
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
         case "PROMOTED" -> actor + " ascendió a oficial a " + firstWord(details);
         case "DEMOTED" -> actor + " devolvió al rango de miembro a " + firstWord(details);
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
