package net.desgraciamon.guilds;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.actor.ActorType;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import kotlin.Unit;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.class_2561;
import net.minecraft.class_3222;
import net.minecraft.server.MinecraftServer;

public final class GuildSynergyManager {
   private static final double PROXIMITY_RADIUS_SQ = 1024.0;
   private static final Map<UUID, Integer> debugBots = new ConcurrentHashMap<>();
   private static final Map<UUID, Integer> lastKnownCounts = new ConcurrentHashMap<>();
   private static int tickCounter = 0;

   private GuildSynergyManager() {
   }

   public static void initialize() {
      try {
         CobblemonEvents.EXPERIENCE_GAINED_EVENT_PRE.subscribe(Priority.NORMAL, event -> {
            Pokemon pokemon = event.getPokemon();
            if (pokemon == null) {
               return Unit.INSTANCE;
            } else {
               class_3222 player = pokemon.getOwnerPlayer();
               if (player == null) {
                  return Unit.INSTANCE;
               } else {
                  int nearby = getNearbyCount(player);
                  if (nearby >= 2) {
                     float multiplier = getXpMultiplier(nearby);
                     int boosted = Math.round((float)event.getExperience() * multiplier);
                     event.setExperience(boosted);
                  }

                  return Unit.INSTANCE;
               }
            }
         });
         CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL, event -> {
            if (RadicalTrainerBattleManager.isTestBattle(event.getBattle().getBattleId())) {
               return Unit.INSTANCE;
            } else {
               boolean hasNpcOpponent = event.getLosers().stream().anyMatch(actor -> actor.getType() == ActorType.NPC);

               for (BattleActor winner : event.getWinners()) {
                  if (winner instanceof PlayerBattleActor) {
                     PlayerBattleActor playerActor = (PlayerBattleActor)winner;
                     class_3222 player = playerActor.getEntity();
                     if (player != null) {
                        int nearby = getNearbyCount(player);
                        if (nearby >= 2) {
                           awardBattleBonus(player, nearby, hasNpcOpponent);
                        }
                     }
                  }
               }

               return Unit.INSTANCE;
            }
         });
         CobblemonEvents.SHINY_CHANCE_CALCULATION.subscribe(Priority.NORMAL, event -> {
            event.addModificationFunction((chance, player, pokemon) -> player != null && getNearbyCount(player) >= 5 ? chance * 1.25F : chance);
            return Unit.INSTANCE;
         });
      } catch (Throwable var1) {
         System.err.println("[GuildSynergy] Warning: Failed to hook Cobblemon events: " + var1.getMessage());
      }
   }

   public static void setDebugBots(UUID playerUuid, int count) {
      if (count <= 0) {
         debugBots.remove(playerUuid);
      } else {
         debugBots.put(playerUuid, count);
      }
   }

   public static int getDebugBots(UUID playerUuid) {
      return debugBots.getOrDefault(playerUuid, 0);
   }

   public static int getNearbyCount(class_3222 player) {
      if (player == null) {
         return 0;
      } else {
         GuildStorage storage = DesgraciamonGuilds.getStorage();
         if (storage == null) {
            return 0;
         } else {
            String guildId = storage.index().playerGuilds.get(player.method_5667().toString());
            if (guildId == null) {
               return 0;
            } else {
               GuildStorage.Guild guild = storage.getGuild(guildId);
               if (guild == null) {
                  return 0;
               } else {
                  int count = 0;
                  MinecraftServer server = player.method_5682();
                  if (server != null) {
                     for (GuildStorage.Member member : guild.members.values()) {
                        if (!member.playerUuid.equals(player.method_5667().toString())) {
                           class_3222 other = server.method_3760().method_14602(UUID.fromString(member.playerUuid));
                           if (other != null && other.method_37908() == player.method_37908() && other.method_5858(player) <= 1024.0) {
                              count++;
                           }
                        }
                     }
                  }

                  int bots = debugBots.getOrDefault(player.method_5667(), 0);
                  count += bots;
                  return count > 0 ? count + 1 : 0;
               }
            }
         }
      }
   }

   public static float getXpMultiplier(int nearby) {
      return switch (nearby) {
         case 2 -> 1.2F;
         case 3 -> 1.35F;
         case 4 -> 1.5F;
         default -> 1.65F;
      };
   }

   public static int getXpBonusPercent(int nearby) {
      return switch (nearby) {
         case 2 -> 20;
         case 3 -> 35;
         case 4 -> 50;
         default -> 65;
      };
   }

   public static int getMoneyBonusPercent(int nearby) {
      return switch (nearby) {
         case 2 -> 10;
         case 3 -> 20;
         case 4 -> 30;
         default -> 40;
      };
   }

   private static void awardBattleBonus(class_3222 player, int nearby, boolean isNpc) {
      if (CobbleDollarsBridge.isAvailable()) {
         try {
            int pct = getMoneyBonusPercent(nearby);
            long baseAward = isNpc ? 100L : 25L;
            long bonus = Math.max(1L, Math.round((double)baseAward * ((double)pct / 100.0)));
            long total = baseAward + bonus;
            BigInteger current = CobbleDollarsBridge.balance(player);
            CobbleDollarsBridge.setBalance(player, current.add(BigInteger.valueOf(total)));
            String target = isNpc ? "Entrenador NPC" : "Combate Salvaje";
            player.method_43496(
               class_2561.method_43470(
                  "§6[Sinergia de Gremio] §a¡Victoria ("
                     + target
                     + ")! §e+$"
                     + baseAward
                     + " §6(+§a"
                     + pct
                     + "% §6gremio: §e+$"
                     + bonus
                     + "§6) §aTotal: §e+$"
                     + total
                     + " CobbleDollars"
               )
            );
         } catch (Throwable var12) {
         }
      }
   }

   public static void tick(MinecraftServer server) {
      if (server != null) {
         GuildStorage storage = DesgraciamonGuilds.getStorage();
         if (storage != null) {
            tickCounter++;
            boolean periodicSync = tickCounter % 100 == 0;

            for (class_3222 player : server.method_3760().method_14571()) {
               String guildId = storage.index().playerGuilds.get(player.method_5667().toString());
               GuildStorage.Guild guild = guildId != null ? storage.getGuild(guildId) : null;
               int nearby = guild != null ? getNearbyCount(player) : 0;
               int last = lastKnownCounts.getOrDefault(player.method_5667(), 0);
               if (nearby != last || periodicSync) {
                  lastKnownCounts.put(player.method_5667(), nearby);
                  String gName = guild != null ? guild.name : "";
                  String gColor = guild != null && guild.colorHex != null ? guild.colorHex : "#FFFFFF";
                  ServerPlayNetworking.send(player, new GuildSynergyPayload(nearby, gName, gColor));
               }
            }
         }
      }
   }
}
