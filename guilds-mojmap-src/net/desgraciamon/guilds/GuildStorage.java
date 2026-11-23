package net.desgraciamon.guilds;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

final class GuildStorage {
   static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private final MinecraftServer server;
   private final Path dataDirectory;
   private final Path indexPath;
   private final GuildStorage.Index index = new GuildStorage.Index();
   private final Map<String, GuildStorage.Guild> guilds = new HashMap<>();
   private long cooldownMillis = 86400000L;

   GuildStorage(MinecraftServer server) {
      this.server = server;
      this.dataDirectory = server.getWorldPath(LevelResource.ROOT).resolve("desgraciamon_guilds").resolve("data");
      this.indexPath = this.dataDirectory.resolve("guilds_index.json");
      this.load();
   }

   GuildStorage.Index index() {
      return this.index;
   }

   GuildStorage.Guild getGuild(String id) {
      return this.guilds.get(id);
   }

   List<GuildStorage.Guild> guilds() {
      return new ArrayList<>(this.guilds.values());
   }

   void addGuild(GuildStorage.Guild guild) {
      this.guilds.put(guild.guildId, guild);
      if (!this.index.guildIds.contains(guild.guildId)) {
         this.index.guildIds.add(guild.guildId);
      }
   }

   boolean isCoolingDown(UUID player) {
      return this.index.cooldowns.getOrDefault(player.toString(), 0L) > System.currentTimeMillis();
   }

   long cooldownRemaining(UUID player) {
      return Math.max(0L, this.index.cooldowns.getOrDefault(player.toString(), 0L) - System.currentTimeMillis());
   }

   long cooldownExpiresAt(UUID player) {
      return this.index.cooldowns.getOrDefault(player.toString(), 0L);
   }

   void clearCooldown(UUID player) {
      this.index.cooldowns.remove(player.toString());
   }

   void setCooldownExpiry(UUID player, long expiry) {
      if (expiry <= 0L) {
         this.index.cooldowns.remove(player.toString());
      } else {
         this.index.cooldowns.put(player.toString(), expiry);
      }
   }

   long adjustCooldown(UUID player, long hours) {
      long now = System.currentTimeMillis();
      long current = Math.max(now, this.cooldownExpiresAt(player));
      long adjusted = Math.max(now, Math.addExact(current, Math.multiplyExact(hours, 3600000L)));
      if (adjusted == now) {
         this.index.cooldowns.remove(player.toString());
      } else {
         this.index.cooldowns.put(player.toString(), adjusted);
      }

      return adjusted;
   }

   int cooldownCount() {
      return (int)this.index.cooldowns.values().stream().filter(expiry -> expiry > System.currentTimeMillis()).count();
   }

   int activeGuildCount() {
      return (int)this.guilds.values().stream().filter(guild -> !guild.disbanded).count();
   }

   Path dataDirectory() {
      return this.dataDirectory;
   }

   void applyCooldown(UUID player) {
      this.index.cooldowns.put(player.toString(), System.currentTimeMillis() + this.cooldownMillis);
   }

   void save() {
      try {
         Files.createDirectories(this.dataDirectory);
         writeJson(this.indexPath, this.index);

         for (GuildStorage.Guild guild : this.guilds.values()) {
            if (guild.vault != null && this.server != null) {
               guild.vaultItems = new ArrayList<>();

               for (int i = 0; i < 27; i++) {
                  ItemStack stack = i < guild.vault.size() ? (ItemStack)guild.vault.get(i) : ItemStack.EMPTY;
                  if (stack != null && !stack.isEmpty()) {
                     try {
                        Tag tag = stack.save(this.server.registryAccess());
                        guild.vaultItems.add(tag.toString());
                     } catch (Exception var6) {
                        guild.vaultItems.add("");
                     }
                  } else {
                     guild.vaultItems.add("");
                  }
               }
            }

            writeJson(this.dataDirectory.resolve(guild.guildId + ".json"), guild);
         }
      } catch (IOException var7) {
         throw new IllegalStateException("Could not save guild data", var7);
      }
   }

   private void load() {
      try {
         Files.createDirectories(this.dataDirectory);
         Path configPath = this.dataDirectory.resolve("guild_config.json");
         GuildStorage.Config config = null;
         if (Files.exists(configPath)) {
            try {
               String str = Files.readString(configPath).trim();
               if (!str.isEmpty()) {
                  config = (GuildStorage.Config)GSON.fromJson(str, GuildStorage.Config.class);
               }
            } catch (Exception var15) {
               config = null;
            }
         }

         if (config == null) {
            config = new GuildStorage.Config();
         }

         config.joinCooldownHours = Math.max(12, Math.min(24, config.joinCooldownHours));
         this.cooldownMillis = (long)config.joinCooldownHours * 60L * 60L * 1000L;
         writeJson(configPath, config);
         if (Files.exists(this.indexPath)) {
            GuildStorage.Index savedIndex = null;

            try {
               String str = Files.readString(this.indexPath).trim();
               if (!str.isEmpty()) {
                  savedIndex = (GuildStorage.Index)GSON.fromJson(str, GuildStorage.Index.class);
               }
            } catch (Exception var14) {
            }

            if (savedIndex != null) {
               if (savedIndex.playerGuilds != null) {
                  this.index.playerGuilds.putAll(savedIndex.playerGuilds);
               }

               if (savedIndex.invitations != null) {
                  this.index.invitations.putAll(savedIndex.invitations);
               }

               if (savedIndex.cooldowns != null) {
                  this.index.cooldowns.putAll(savedIndex.cooldowns);
               }

               if (savedIndex.guildIds != null) {
                  this.index.guildIds.addAll(savedIndex.guildIds);
               }
            }

            for (String id : this.index.guildIds) {
               Path guildPath = this.dataDirectory.resolve(id + ".json");
               if (Files.exists(guildPath)) {
                  try {
                     String str = Files.readString(guildPath).trim();
                     if (!str.isEmpty()) {
                        GuildStorage.Guild guild = (GuildStorage.Guild)GSON.fromJson(str, GuildStorage.Guild.class);
                        if (guild != null && guild.guildId != null) {
                           if (guild.members == null) {
                              guild.members = new LinkedHashMap<>();
                           }

                           if (guild.waypoints == null) {
                              guild.waypoints = new ArrayList<>();
                           }

                           if (guild.activityLog == null) {
                              guild.activityLog = new ArrayList<>();
                           }

                           if (guild.chatMessages == null) {
                              guild.chatMessages = new ArrayList<>();
                           }

                           if (guild.storedPokemon == null) {
                              guild.storedPokemon = new JsonObject();
                           }

                           if (guild.bankBalance == null) {
                              guild.bankBalance = BigInteger.ZERO;
                           }

                           guild.vault = NonNullList.withSize(27, ItemStack.EMPTY);
                           if (guild.vaultItems != null && this.server != null) {
                              for (int i = 0; i < Math.min(27, guild.vaultItems.size()); i++) {
                                 String itemStr = guild.vaultItems.get(i);
                                 if (itemStr != null && !itemStr.isBlank()) {
                                    try {
                                       CompoundTag tag = TagParser.parseTag(itemStr);
                                       ItemStack stack = ItemStack.parse(this.server.registryAccess(), tag).orElse(ItemStack.EMPTY);
                                       guild.vault.set(i, stack);
                                    } catch (Exception var13) {
                                       guild.vault.set(i, ItemStack.EMPTY);
                                    }
                                 }
                              }
                           }

                           this.guilds.put(guild.guildId, guild);
                        }
                     }
                  } catch (Exception var16) {
                  }
               }
            }
         }
      } catch (RuntimeException | IOException var17) {
         throw new IllegalStateException("Could not load guild data from " + this.dataDirectory, var17);
      }
   }

   private static void writeJson(Path path, Object value) throws IOException {
      Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
      Files.writeString(temporary, GSON.toJson(value));

      try {
         Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException var4) {
         Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      }
   }

   static final class Activity {
      String timestamp;
      String event;
      String actorUuid;
      String actorName;
      String details;

      Activity(String timestamp, String event, UUID actor, String details) {
         this(timestamp, event, actor, details, "");
      }

      Activity(String timestamp, String event, UUID actor, String details, String actorName) {
         this.timestamp = timestamp;
         this.event = event;
         this.actorUuid = actor.toString();
         this.details = details;
         this.actorName = actorName;
      }
   }

   static final class ChatMessage {
      long timestamp;
      String senderName;
      String text;

      ChatMessage(long timestamp, String senderName, String text) {
         this.timestamp = timestamp;
         this.senderName = senderName;
         this.text = text;
      }
   }

   static final class Config {
      int joinCooldownHours = 24;
   }

   static final class Guild {
      String guildId;
      String name;
      String colorHex;
      String founderUuid;
      BigInteger bankBalance = BigInteger.ZERO;
      boolean disbanded;
      JsonObject storedPokemon = new JsonObject();
      Map<String, GuildStorage.Member> members = new LinkedHashMap<>();
      List<GuildStorage.Waypoint> waypoints = new ArrayList<>();
      List<GuildStorage.Activity> activityLog = new ArrayList<>();
      List<GuildStorage.ChatMessage> chatMessages = new ArrayList<>();
      String motd = "¡Bienvenidos al gremio! Coopera con tus compañeros y comparte rutas.";
      String icon = "pokeball";
      List<String> vaultItems = new ArrayList<>();
      transient NonNullList<ItemStack> vault = NonNullList.withSize(27, ItemStack.EMPTY);

      NonNullList<ItemStack> getVault() {
         if (this.vault == null) {
            this.vault = NonNullList.withSize(27, ItemStack.EMPTY);
         }

         return this.vault;
      }

      Guild(String name, String colorHex, UUID founder) {
         this.vault = NonNullList.withSize(27, ItemStack.EMPTY);
         this.guildId = UUID.randomUUID().toString();
         this.name = name;
         this.colorHex = colorHex;
         this.founderUuid = founder.toString();
         this.members.put(founder.toString(), new GuildStorage.Member(founder, "", "LEADER", System.currentTimeMillis() / 1000L));
      }
   }

   static final class Index {
      Map<String, String> playerGuilds = new HashMap<>();
      Map<String, String> invitations = new HashMap<>();
      Map<String, Long> cooldowns = new HashMap<>();
      List<String> guildIds = new ArrayList<>();
   }

   static final class Member {
      String playerUuid;
      String playerName;
      String role;
      long joinedTimestamp;
      long lastSeenTimestamp;

      Member(UUID playerUuid, String playerName, String role, long joinedTimestamp) {
         this.playerUuid = playerUuid.toString();
         this.playerName = playerName;
         this.role = role;
         this.joinedTimestamp = joinedTimestamp;
         this.lastSeenTimestamp = joinedTimestamp;
      }
   }

   static final class Waypoint {
      String waypointId = UUID.randomUUID().toString();
      String name;
      String ownerUuid;
      String dimension;
      int x;
      int y;
      int z;
      boolean shared;
      String sharedBy = "";
      long sharedTimestamp;

      Waypoint(String name, UUID owner, String dimension, int x, int y, int z) {
         this.name = name;
         this.ownerUuid = owner.toString();
         this.dimension = dimension;
         this.x = x;
         this.y = y;
         this.z = z;
      }
   }
}
