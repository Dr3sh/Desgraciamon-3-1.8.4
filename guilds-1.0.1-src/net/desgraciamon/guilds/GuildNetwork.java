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
import net.minecraft.class_1799;
import net.minecraft.class_2371;
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import net.minecraft.class_3222;
import net.minecraft.server.MinecraftServer;

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
         for (class_3222 player : server.method_3760().method_14571()) {
            sync(player, server, storage);
         }
      }
   }

   static void sync(class_3222 player, MinecraftServer server, GuildStorage storage) {
      String id = storage.index().playerGuilds.get(player.method_5667().toString());
      GuildStorage.Guild guild = id == null ? null : storage.getGuild(id);
      boolean member = guild != null && !guild.disbanded;
      String invitedGuildId = member ? null : storage.index().invitations.get(player.method_5667().toString());
      GuildStorage.Guild invitedGuild = invitedGuildId == null ? null : storage.getGuild(invitedGuildId);
      boolean hasInvite = !member && invitedGuild != null && !invitedGuild.disbanded;
      String name = member ? guild.name : (hasInvite ? invitedGuild.name : "Sin gremio");
      String color = member ? guild.colorHex : (hasInvite ? invitedGuild.colorHex : "#FFFFFF");
      GuildStorage.Member own = member ? guild.members.get(player.method_5667().toString()) : null;
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
            class_3222 online = server.method_3760().method_14602(UUID.fromString(entry.playerUuid));
            String status = online == null
               ? "Desconectado; ultima conexion " + OFFLINE_TIME.format(Instant.ofEpochSecond(entry.lastSeenTimestamp))
               : "En linea";
            members.add(entry.playerName + " | " + entry.role + " | " + status + " | " + entry.playerUuid);
         }

         for (GuildStorage.Waypoint waypoint : guild.waypoints) {
            if (waypoint.shared || waypoint.ownerUuid.equals(player.method_5667().toString())) {
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
            class_3222 online = server.method_3760().method_14602(UUID.fromString(entry.playerUuid));
            String status = online == null ? "Desconectado" : "En linea";
            members.add(entry.playerName + " | " + entry.role + " | " + status + " | " + entry.playerUuid);
         }
      }

      List<String> tags = new ArrayList<>();

      for (class_3222 online : server.method_3760().method_14571()) {
         String memberGuildId = storage.index().playerGuilds.get(online.method_5667().toString());
         GuildStorage.Guild tagGuild = memberGuildId == null ? null : storage.getGuild(memberGuildId);
         if (tagGuild != null && !tagGuild.disbanded) {
            tags.add(online.method_5667() + "|" + tagGuild.name + "|" + tagGuild.colorHex);
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

      List<class_1799> vaultList = new ArrayList<>(27);
      if (member && guild != null) {
         class_2371<class_1799> v = guild.getVault();

         for (int i = 0; i < 27; i++) {
            vaultList.add(i < v.size() ? ((class_1799)v.get(i)).method_7972() : class_1799.field_8037);
         }
      } else {
         for (int i = 0; i < 27; i++) {
            vaultList.add(class_1799.field_8037);
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

   static void sendGuildChatMessage(MinecraftServer server, class_3222 sender, GuildStorage.Guild guild, String text) {
      if (text != null && !text.isBlank()) {
         String cleanText = text.trim();
         if (cleanText.length() > 256) {
            cleanText = cleanText.substring(0, 256);
         }

         long now = System.currentTimeMillis();
         String senderName = sender.method_7334().getName();
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
         class_2561 formatted = class_2561.method_43470("<" + guild.name + "> " + senderName + ": " + cleanText)
            .method_27694(style -> style.method_36139(colorInt));

         for (GuildStorage.Member m : guild.members.values()) {
            class_3222 online = server.method_3760().method_14602(UUID.fromString(m.playerUuid));
            if (online != null) {
               online.method_43496(formatted);
            }
         }
      }
   }

   private static void handle(class_3222 player, GuildRequestPayload payload) {
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
                     if (srcSlot < 0 || srcSlot >= player.method_31548().method_5439()) {
                        return;
                     }

                     class_1799 stack = player.method_31548().method_5438(srcSlot);
                     if (stack.method_7960()) {
                        return;
                     }

                     String itemName = stack.method_7964().getString();
                     int original = stack.method_7947();
                     class_2371<class_1799> vault = guild.getVault();

                     for (int i = 0; i < 27 && !stack.method_7960(); i++) {
                        class_1799 v = (class_1799)vault.get(i);
                        if (!v.method_7960() && class_1799.method_31577(stack, v)) {
                           int space = v.method_7914() - v.method_7947();
                           if (space > 0) {
                              int toMove = Math.min(space, stack.method_7947());
                              v.method_7933(toMove);
                              stack.method_7934(toMove);
                           }
                        }
                     }

                     for (int ix = 0; ix < 27 && !stack.method_7960(); ix++) {
                        class_1799 v = (class_1799)vault.get(ix);
                        if (v.method_7960()) {
                           vault.set(ix, stack.method_7972());
                           stack.method_7939(0);
                           break;
                        }
                     }

                     int moved = original - stack.method_7947();
                     if (moved > 0) {
                        if (stack.method_7960()) {
                           player.method_31548().method_5447(srcSlot, class_1799.field_8037);
                        }

                        guild.activityLog
                           .add(
                              new GuildStorage.Activity(
                                 LOG_TIME.format(Instant.now()),
                                 "ITEM_DEPOSITED",
                                 player.method_5667(),
                                 moved + "x " + itemName,
                                 player.method_7334().getName()
                              )
                           );
                        storage.save();
                        player.field_7512.method_7623();
                        syncAll(player.method_5682());
                     }
                  } else if ("VAULT".equals(srcType)) {
                     if (srcSlot < 0 || srcSlot >= 27) {
                        return;
                     }

                     class_2371<class_1799> vault = guild.getVault();
                     class_1799 v = (class_1799)vault.get(srcSlot);
                     if (v.method_7960()) {
                        return;
                     }

                     String itemName = v.method_7964().getString();
                     int original = v.method_7947();
                     class_1799 toInsert = v.method_7972();
                     player.method_31548().method_7394(toInsert);
                     int moved = original - toInsert.method_7947();
                     if (moved > 0) {
                        if (toInsert.method_7960()) {
                           vault.set(srcSlot, class_1799.field_8037);
                        } else {
                           v.method_7939(toInsert.method_7947());
                        }

                        guild.activityLog
                           .add(
                              new GuildStorage.Activity(
                                 LOG_TIME.format(Instant.now()),
                                 "ITEM_WITHDRAWN",
                                 player.method_5667(),
                                 moved + "x " + itemName,
                                 player.method_7334().getName()
                              )
                           );
                        storage.save();
                        player.field_7512.method_7623();
                        syncAll(player.method_5682());
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
                  class_2371<class_1799> vaultx = guild.getVault();
                  if ("PLAYER".equals(srcType) && "VAULT".equals(dstType)) {
                     if (srcSlotx < 0 || srcSlotx >= player.method_31548().method_5439()) {
                        return;
                     }

                     class_1799 src = player.method_31548().method_5438(srcSlotx);
                     if (src.method_7960()) {
                        return;
                     }

                     int toMove = amount > 0 && amount <= src.method_7947() ? amount : src.method_7947();
                     String itemName = src.method_7964().getString();
                     if (dstSlot >= 0 && dstSlot < 27) {
                        class_1799 dst = (class_1799)vaultx.get(dstSlot);
                        if (dst.method_7960()) {
                           vaultx.set(dstSlot, src.method_46651(toMove));
                           src.method_7934(toMove);
                           if (src.method_7960()) {
                              player.method_31548().method_5447(srcSlotx, class_1799.field_8037);
                           }

                           guild.activityLog
                              .add(
                                 new GuildStorage.Activity(
                                    LOG_TIME.format(Instant.now()),
                                    "ITEM_DEPOSITED",
                                    player.method_5667(),
                                    toMove + "x " + itemName,
                                    player.method_7334().getName()
                                 )
                              );
                        } else if (class_1799.method_31577(src, dst)) {
                           int space = dst.method_7914() - dst.method_7947();
                           int actual = Math.min(space, toMove);
                           if (actual > 0) {
                              dst.method_7933(actual);
                              src.method_7934(actual);
                              if (src.method_7960()) {
                                 player.method_31548().method_5447(srcSlotx, class_1799.field_8037);
                              }

                              guild.activityLog
                                 .add(
                                    new GuildStorage.Activity(
                                       LOG_TIME.format(Instant.now()),
                                       "ITEM_DEPOSITED",
                                       player.method_5667(),
                                       actual + "x " + itemName,
                                       player.method_7334().getName()
                                    )
                                 );
                           }
                        } else if (toMove == src.method_7947()) {
                           vaultx.set(dstSlot, src.method_7972());
                           player.method_31548().method_5447(srcSlotx, dst.method_7972());
                           guild.activityLog
                              .add(
                                 new GuildStorage.Activity(
                                    LOG_TIME.format(Instant.now()),
                                    "ITEM_DEPOSITED",
                                    player.method_5667(),
                                    src.method_7947() + "x " + itemName,
                                    player.method_7334().getName()
                                 )
                              );
                        }

                        storage.save();
                        player.field_7512.method_7623();
                        syncAll(player.method_5682());
                     }
                  } else if ("VAULT".equals(srcType) && "PLAYER".equals(dstType)) {
                     if (srcSlotx < 0 || srcSlotx >= 27) {
                        return;
                     }

                     class_1799 srcx = (class_1799)vaultx.get(srcSlotx);
                     if (srcx.method_7960()) {
                        return;
                     }

                     int toMove = amount > 0 && amount <= srcx.method_7947() ? amount : srcx.method_7947();
                     String itemName = srcx.method_7964().getString();
                     if (dstSlot >= 0 && dstSlot < player.method_31548().method_5439()) {
                        class_1799 dst = player.method_31548().method_5438(dstSlot);
                        if (dst.method_7960()) {
                           player.method_31548().method_5447(dstSlot, srcx.method_46651(toMove));
                           srcx.method_7934(toMove);
                           if (srcx.method_7960()) {
                              vaultx.set(srcSlotx, class_1799.field_8037);
                           }

                           guild.activityLog
                              .add(
                                 new GuildStorage.Activity(
                                    LOG_TIME.format(Instant.now()),
                                    "ITEM_WITHDRAWN",
                                    player.method_5667(),
                                    toMove + "x " + itemName,
                                    player.method_7334().getName()
                                 )
                              );
                        } else if (class_1799.method_31577(srcx, dst)) {
                           int space = dst.method_7914() - dst.method_7947();
                           int actual = Math.min(space, toMove);
                           if (actual > 0) {
                              dst.method_7933(actual);
                              srcx.method_7934(actual);
                              if (srcx.method_7960()) {
                                 vaultx.set(srcSlotx, class_1799.field_8037);
                              }

                              guild.activityLog
                                 .add(
                                    new GuildStorage.Activity(
                                       LOG_TIME.format(Instant.now()),
                                       "ITEM_WITHDRAWN",
                                       player.method_5667(),
                                       actual + "x " + itemName,
                                       player.method_7334().getName()
                                    )
                                 );
                           }
                        } else if (toMove == srcx.method_7947()) {
                           player.method_31548().method_5447(dstSlot, srcx.method_7972());
                           vaultx.set(srcSlotx, dst.method_7972());
                           guild.activityLog
                              .add(
                                 new GuildStorage.Activity(
                                    LOG_TIME.format(Instant.now()),
                                    "ITEM_WITHDRAWN",
                                    player.method_5667(),
                                    srcx.method_7947() + "x " + itemName,
                                    player.method_7334().getName()
                                 )
                              );
                        }

                        storage.save();
                        player.field_7512.method_7623();
                        syncAll(player.method_5682());
                     }
                  } else if ("VAULT".equals(srcType) && "VAULT".equals(dstType)) {
                     if (srcSlotx < 0 || srcSlotx >= 27 || dstSlot < 0 || dstSlot >= 27 || srcSlotx == dstSlot) {
                        return;
                     }

                     class_1799 srcxx = (class_1799)vaultx.get(srcSlotx);
                     if (srcxx.method_7960()) {
                        return;
                     }

                     int toMove = amount > 0 && amount <= srcxx.method_7947() ? amount : srcxx.method_7947();
                     class_1799 dst = (class_1799)vaultx.get(dstSlot);
                     if (dst.method_7960()) {
                        vaultx.set(dstSlot, srcxx.method_46651(toMove));
                        srcxx.method_7934(toMove);
                        if (srcxx.method_7960()) {
                           vaultx.set(srcSlotx, class_1799.field_8037);
                        }
                     } else if (class_1799.method_31577(srcxx, dst)) {
                        int space = dst.method_7914() - dst.method_7947();
                        int actual = Math.min(space, toMove);
                        if (actual > 0) {
                           dst.method_7933(actual);
                           srcxx.method_7934(actual);
                           if (srcxx.method_7960()) {
                              vaultx.set(srcSlotx, class_1799.field_8037);
                           }
                        }
                     } else if (toMove == srcxx.method_7947()) {
                        vaultx.set(dstSlot, srcxx);
                        vaultx.set(srcSlotx, dst);
                     }

                     storage.save();
                     syncAll(player.method_5682());
                  } else if ("PLAYER".equals(srcType) && "PLAYER".equals(dstType)) {
                     if (srcSlotx < 0
                        || srcSlotx >= player.method_31548().method_5439()
                        || dstSlot < 0
                        || dstSlot >= player.method_31548().method_5439()
                        || srcSlotx == dstSlot) {
                        return;
                     }

                     class_1799 srcxxx = player.method_31548().method_5438(srcSlotx);
                     if (srcxxx.method_7960()) {
                        return;
                     }

                     int toMove = amount > 0 && amount <= srcxxx.method_7947() ? amount : srcxxx.method_7947();
                     class_1799 dst = player.method_31548().method_5438(dstSlot);
                     if (dst.method_7960()) {
                        player.method_31548().method_5447(dstSlot, srcxxx.method_46651(toMove));
                        srcxxx.method_7934(toMove);
                        if (srcxxx.method_7960()) {
                           player.method_31548().method_5447(srcSlotx, class_1799.field_8037);
                        }
                     } else if (class_1799.method_31577(srcxxx, dst)) {
                        int space = dst.method_7914() - dst.method_7947();
                        int actual = Math.min(space, toMove);
                        if (actual > 0) {
                           dst.method_7933(actual);
                           srcxxx.method_7934(actual);
                           if (srcxxx.method_7960()) {
                              player.method_31548().method_5447(srcSlotx, class_1799.field_8037);
                           }
                        }
                     } else if (toMove == srcxxx.method_7947()) {
                        player.method_31548().method_5447(dstSlot, srcxxx.method_7972());
                        player.method_31548().method_5447(srcSlotx, dst.method_7972());
                     }

                     player.field_7512.method_7623();
                     sync(player, player.method_5682(), storage);
                  }
               }
            }
         } else if ("DECLINE".equals(payload.action())) {
            storage.index().invitations.remove(player.method_5667().toString());
            storage.save();
            sync(player, player.method_5682(), storage);
            player.method_43496(class_2561.method_43470("Has rechazado la invitación al gremio."));
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
                     if (!party.getObserverUUIDs().contains(player.method_5667())) {
                        party.getObserverUUIDs().add(player.method_5667());
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
                        player.method_43496(class_2561.method_43470("§c[Gremio] ¡Debes tener al menos 1 Pokémon en tu equipo!"));
                        return;
                     }

                     if ("GUILD".equals(dstType) && DesgraciamonGuilds.isRestrictedPokemon(srcPoke)) {
                        player.method_43496(class_2561.method_43470("§c[Gremio] No puedes almacenar Pokémon Legendarios ni Míticos en el PC compartido."));
                        return;
                     }

                     if ("GUILD".equals(srcType) && dstPoke != null && DesgraciamonGuilds.isRestrictedPokemon(dstPoke)) {
                        player.method_43496(class_2561.method_43470("§c[Gremio] No puedes almacenar Pokémon Legendarios ni Míticos en el PC compartido."));
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
                              dstPoke.getPersistentData().method_10582("guild_depositor_name", player.method_7334().getName());
                              guildPc.set(new PCPosition(srcBox, srcSlotx), dstPoke);
                           }
                        }

                        if ("PARTY".equals(dstType)) {
                           party.set(new PartyPosition(dstSlot), srcPoke);
                        } else if ("PC".equals(dstType)) {
                           pc.set(new PCPosition(dstBox, dstSlot), srcPoke);
                        } else if ("GUILD".equals(dstType) && guildPc != null) {
                           srcPoke.getPersistentData().method_10582("guild_depositor_name", player.method_7334().getName());
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
                                    player.method_5667(),
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
                                    player.method_5667(),
                                    srcPoke.getSpecies().getName() + " (Nv." + srcPoke.getLevel() + ")"
                                 )
                              );
                        }

                        pcManager.markDirty(guild.guildId);
                        storage.save();
                     }

                     pc.addObserver(player);
                     pc.sendTo(player);
                     if (!party.getObserverUUIDs().contains(player.method_5667())) {
                        party.getObserverUUIDs().add(player.method_5667());
                     }

                     party.sendTo(player);
                     new SetPartyReferencePacket(party.getUuid()).sendToPlayer(player);
                     syncAll(player.method_5682());
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
                           LOG_TIME.format(Instant.now()), "MOTD_UPDATED", player.method_5667(), text.replace("\n", " "), player.method_7334().getName()
                        )
                     );
                  storage.save();
                  syncAll(player.method_5682());
               }
            } else if ("ICON".equals(payload.action())) {
               if (guild != null && !guild.disbanded && hasGuildRole(guild, player, "LEADER")) {
                  guild.icon = payload.argument().trim().toLowerCase();
                  storage.save();
                  syncAll(player.method_5682());
               }
            } else if ("SHARE_POS".equals(payload.action())) {
               if (guild != null && !guild.disbanded && hasGuildRole(guild, player, "LEADER", "OFFICER", "MEMBER")) {
                  String wpName = payload.argument().isBlank() ? "Base de " + player.method_7334().getName() : payload.argument().trim();
                  if (wpName.length() > 32) {
                     wpName = wpName.substring(0, 32);
                  }

                  String dim = player.method_37908().method_27983().method_29177().toString();
                  int x = player.method_31477();
                  int y = player.method_31478();
                  int z = player.method_31479();
                  GuildStorage.Waypoint wp = new GuildStorage.Waypoint(wpName, player.method_5667(), dim, x, y, z);
                  wp.shared = true;
                  wp.sharedBy = player.method_7334().getName();
                  wp.sharedTimestamp = System.currentTimeMillis();
                  guild.waypoints.add(wp);
                  guild.activityLog
                     .add(
                        new GuildStorage.Activity(
                           LOG_TIME.format(Instant.now()), "WAYPOINT_SHARED", player.method_5667(), wpName, player.method_7334().getName()
                        )
                     );
                  storage.save();
                  syncAll(player.method_5682());
               }
            } else {
               String command = commandFor(payload);
               if (command != null) {
                  try {
                     player.method_5682().method_3734().method_44252(player.method_5671(), command);
                  } catch (RuntimeException var22) {
                     player.method_43496(class_2561.method_43470("No se pudo completar la accion del gremio."));
                  }
               }

               syncAll(player.method_5682());
            }
         } else {
            if (guild != null && !guild.disbanded) {
               sendGuildChatMessage(player.method_5682(), player, guild, payload.argument());
               storage.save();
               syncAll(player.method_5682());
            }
         }
      }
   }

   private static void importWaypoint(class_3222 player, GuildWaypointImportPayload payload) {
      GuildStorage storage = storageSupplier == null ? null : storageSupplier.get();
      if (storage != null) {
         String guildId = storage.index().playerGuilds.get(player.method_5667().toString());
         GuildStorage.Guild guild = guildId == null ? null : storage.getGuild(guildId);
         class_2960 dimension = class_2960.method_12829(payload.dimension());
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
                           && existing.ownerUuid.equals(player.method_5667().toString())
                           && existing.name.equalsIgnoreCase(payload.name())
                           && existing.dimension.equals(dimension.toString())
                           && existing.x == payload.x()
                           && existing.y == payload.y()
                           && existing.z == payload.z()
                  );
               if (alreadyShared) {
                  syncAll(player.method_5682());
                  return;
               }
            }

            if (!payload.share() || hasGuildRole(guild, player, "LEADER", "OFFICER", "MEMBER")) {
               GuildStorage.Waypoint waypoint = new GuildStorage.Waypoint(
                  payload.name(), player.method_5667(), dimension.toString(), payload.x(), payload.y(), payload.z()
               );
               waypoint.shared = payload.share();
               if (waypoint.shared) {
                  waypoint.sharedBy = player.method_7334().getName();
                  waypoint.sharedTimestamp = System.currentTimeMillis();
               }

               guild.waypoints.add(waypoint);
               guild.activityLog
                  .add(
                     new GuildStorage.Activity(
                        LOG_TIME.format(Instant.now()),
                        waypoint.shared ? "WAYPOINT_SHARED" : "WAYPOINT_IMPORTED",
                        player.method_5667(),
                        payload.name(),
                        player.method_7334().getName()
                     )
                  );
               storage.save();
               syncAll(player.method_5682());
            }
         } else {
            player.method_43496(class_2561.method_43470("Waypoint Xaero invalido o acceso denegado."));
         }
      }
   }

   private static GuildStorage.Guild guildFor(class_3222 player, GuildStorage storage) {
      String id = storage.index().playerGuilds.get(player.method_5667().toString());
      return id == null ? null : storage.getGuild(id);
   }

   private static boolean hasGuildRole(GuildStorage.Guild guild, class_3222 player, String... roles) {
      GuildStorage.Member member = guild.members.get(player.method_5667().toString());
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
