package net.desgraciamon.guilds;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.npc.NPCClass;
import com.cobblemon.mod.common.api.npc.NPCClasses;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.storage.party.NPCPartyStore;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.battles.BattleBuilder;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import kotlin.Unit;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AfterDeath;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public final class RadicalTrainerBattleManager {
   private static final Map<UUID, RadicalTrainerBattleManager.ActiveTestBattle> activeBattlesByPlayer = new ConcurrentHashMap<>();
   private static final Map<UUID, RadicalTrainerBattleManager.ActiveTestBattle> activeBattlesById = new ConcurrentHashMap<>();
   private static final Set<UUID> testBattleIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
   private static final List<Path> trainerPaths = new ArrayList<>();
   private static boolean catalogLoaded = false;
   private static final Random RANDOM = new Random();

   private RadicalTrainerBattleManager() {
   }

   public static boolean isTestBattle(UUID battleId) {
      return battleId == null ? false : testBattleIds.contains(battleId) || activeBattlesById.containsKey(battleId);
   }

   public static void initialize() {
      try {
         loadCatalog();
         CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL, event -> {
            cleanUpBattle(event.getBattle().getBattleId(), true);
            return Unit.INSTANCE;
         });
         CobblemonEvents.BATTLE_FLED.subscribe(Priority.NORMAL, event -> {
            cleanUpBattle(event.getBattle().getBattleId(), false);
            return Unit.INSTANCE;
         });
         ServerLivingEntityEvents.AFTER_DEATH.register((AfterDeath)(entity, damageSource) -> {
            if (entity instanceof NPCEntity) {
               stopBattleForTrainer(entity.getUUID());
            }
         });
      } catch (Throwable var1) {
         System.err.println("[RadicalTrainer] Warning: Error initializing RadicalTrainerBattleManager: " + var1.getMessage());
      }
   }

   private static synchronized void loadCatalog() {
      if (!catalogLoaded) {
         trainerPaths.clear();

         try {
            ModContainer rct = (ModContainer)FabricLoader.getInstance().getModContainer("rctmod").orElse(null);
            if (rct != null) {
               rct.findPath("data/rctmod/trainers").ifPresent(dirPath -> {
                  try (Stream<Path> stream = Files.walk(dirPath)) {
                     stream.filter(p -> p.toString().endsWith(".json")).forEach(trainerPaths::add);
                  } catch (Exception var6) {
                  }
               });
            }
         } catch (Throwable var1) {
         }

         catalogLoaded = true;
      }
   }

   public static int startLevel1TestBattle(ServerPlayer player) {
      if (player == null) {
         return 0;
      } else if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
         player.sendSystemMessage(Component.literal("§cYa estás participando en un combate."));
         return 0;
      } else {
         ServerLevel world = player.serverLevel();
         NPCEntity trainer = createBaseTrainerEntity(player, world, "§eEntrenador de Pruebas (Nv. 1)");
         if (trainer == null) {
            player.sendSystemMessage(Component.literal("§cError al generar el entrenador NPC."));
            return 0;
         } else {
            NPCPartyStore party = new NPCPartyStore(trainer);

            try {
               Pokemon magikarp = PokemonProperties.Companion.parse("magikarp level=1 moves=splash").create();
               magikarp.heal();
               party.set(0, magikarp);
               trainer.setParty(party);
            } catch (Exception var6) {
               trainer.method_31472();
               player.sendSystemMessage(Component.literal("§cError al configurar Pokémon de prueba: " + var6.getMessage()));
               return 0;
            }

            long basePrize = 50L;
            return launchBattle(player, trainer, party, "Entrenador de Pruebas (Nv. 1)", basePrize);
         }
      }
   }

   public static int startRandomTrainerBattle(ServerPlayer player) {
      if (player == null) {
         return 0;
      } else if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
         player.sendSystemMessage(Component.literal("§cYa estás participando en un combate."));
         return 0;
      } else {
         loadCatalog();
         int playerAvgLevel = getPlayerTeamAverageLevel(player);
         ServerLevel world = player.serverLevel();
         RadicalTrainerBattleManager.TrainerData data = pickRandomTrainerData();
         String trainerName = data != null && data.name != null && !data.name.isBlank() ? data.name : "Entrenador Radical";
         NPCEntity trainer = createBaseTrainerEntity(player, world, "§6" + trainerName + " §7(Nv. ~" + playerAvgLevel + ")");
         if (trainer == null) {
            player.sendSystemMessage(Component.literal("§cError al generar el NPC en el mundo."));
            return 0;
         } else {
            NPCPartyStore party = new NPCPartyStore(trainer);
            int slot = 0;
            if (data != null && !data.team.isEmpty()) {
               for (RadicalTrainerBattleManager.PokemonSpec spec : data.team) {
                  if (slot >= 6) {
                     break;
                  }

                  int variation = RANDOM.nextInt(7) - 3;
                  int scaledLevel = Math.max(1, Math.min(100, playerAvgLevel + variation));

                  try {
                     String specStr = spec.species + " level=" + scaledLevel;
                     if (spec.gender != null && !spec.gender.isBlank()) {
                        specStr = specStr + " gender=" + spec.gender.toLowerCase();
                     }

                     if (spec.nature != null && !spec.nature.isBlank()) {
                        specStr = specStr + " nature=" + spec.nature.toLowerCase();
                     }

                     if (spec.ability != null && !spec.ability.isBlank()) {
                        specStr = specStr + " ability=" + spec.ability.toLowerCase();
                     }

                     if (spec.aspects != null) {
                        for (String aspect : spec.aspects) {
                           specStr = specStr + " " + aspect.toLowerCase();
                        }
                     }

                     Pokemon p = PokemonProperties.Companion.parse(specStr).create();
                     if (spec.moves != null && !spec.moves.isEmpty()) {
                        p.getMoveSet().clear();

                        for (String moveName : spec.moves) {
                           MoveTemplate mt = Moves.getByName(moveName.toLowerCase());
                           if (mt != null) {
                              p.getMoveSet().add(mt.create());
                           }
                        }
                     }

                     p.heal();
                     party.set(slot++, p);
                  } catch (Exception var18) {
                  }
               }
            }

            if (slot == 0) {
               try {
                  Pokemon p1 = PokemonProperties.Companion.parse("pikachu level=" + playerAvgLevel).create();
                  p1.heal();
                  party.set(0, p1);
                  slot = 1;
               } catch (Exception var17) {
               }
            }

            trainer.setParty(party);
            long basePrize = Math.max(60L, (long)slot * (long)playerAvgLevel * 40L);
            player.sendSystemMessage(Component.literal("§6[Radical Trainers] §e¡Te desafía §f" + trainerName + "§e con §b" + slot + " Pokémon§e!"));
            return launchBattle(player, trainer, party, trainerName, basePrize);
         }
      }
   }

   private static int launchBattle(ServerPlayer player, NPCEntity trainer, NPCPartyStore party, String trainerName, long basePrize) {
      PlayerPartyStore playerParty = Cobblemon.INSTANCE.getStorage().getParty(player);
      if (playerParty != null && !playerParty.isEmpty()) {
         try {
            BattleStartResult result = BattleBuilder.INSTANCE.pvn(player, trainer, null, BattleFormat.Companion.getGEN_9_SINGLES(), false, false, playerParty);
            boolean[] started = new boolean[]{false};
            result.ifSuccessful(
               battle -> {
                  testBattleIds.add(battle.getBattleId());
                  RadicalTrainerBattleManager.ActiveTestBattle active = new RadicalTrainerBattleManager.ActiveTestBattle(
                     battle.getBattleId(), player.getUUID(), trainer, basePrize, trainerName
                  );
                  activeBattlesByPlayer.put(player.getUUID(), active);
                  activeBattlesById.put(battle.getBattleId(), active);
                  started[0] = true;
                  return Unit.INSTANCE;
               }
            );
            result.ifErrored(err -> {
               trainer.method_31472();
               err.sendTo(player, msg -> msg.withStyle(ChatFormatting.RED));
               return Unit.INSTANCE;
            });
            return started[0] ? 1 : 0;
         } catch (Exception var9) {
            trainer.method_31472();
            player.sendSystemMessage(Component.literal("§cError al iniciar el combate: " + var9.getMessage()));
            return 0;
         }
      } else {
         trainer.method_31472();
         player.sendSystemMessage(Component.literal("§c¡No tienes ningún Pokémon en tu equipo para combatir!"));
         return 0;
      }
   }

   private static NPCEntity createBaseTrainerEntity(ServerPlayer player, ServerLevel world, String customName) {
      NPCEntity trainer = new NPCEntity(world);
      NPCClass npcClass = NPCClasses.INSTANCE.getByName("standard");
      if (npcClass == null && !NPCClasses.getClasses().isEmpty()) {
         npcClass = (NPCClass)NPCClasses.getClasses().iterator().next();
      }

      if (npcClass != null) {
         trainer.setNpc(npcClass);
      }

      trainer.method_5665(Component.literal(customName));
      trainer.method_5880(true);
      trainer.setMovable(false);
      trainer.method_5684(false);
      Vec3 look = player.getLookAngle();
      double spawnX = player.getX() + look.x * 2.0;
      double spawnY = player.getY();
      double spawnZ = player.getZ() + look.z * 2.0;
      trainer.method_5808(spawnX, spawnY, spawnZ, player.getYRot() + 180.0F, 0.0F);
      return !world.addFreshEntity(trainer) ? null : trainer;
   }

   private static int getPlayerTeamAverageLevel(ServerPlayer player) {
      try {
         PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
         if (party != null) {
            int total = 0;
            int count = 0;

            for (Pokemon p : party) {
               if (p != null) {
                  total += p.getLevel();
                  count++;
               }
            }

            if (count > 0) {
               return Math.max(1, total / count);
            }
         }
      } catch (Exception var6) {
      }

      return 25;
   }

   private static void cleanUpBattle(UUID battleId, boolean won) {
      RadicalTrainerBattleManager.ActiveTestBattle active = activeBattlesById.remove(battleId);
      if (active != null) {
         activeBattlesByPlayer.remove(active.playerUuid);
         active.trainer.method_31472();
         if (won && active.prizeMoney > 0L) {
            MinecraftServer server = active.trainer.method_5682();
            if (server != null) {
               ServerPlayer player = server.getPlayerList().getPlayer(active.playerUuid);
               if (player != null && CobbleDollarsBridge.isAvailable()) {
                  try {
                     int nearby = GuildSynergyManager.getNearbyCount(player);
                     int pct = nearby >= 2 ? GuildSynergyManager.getMoneyBonusPercent(nearby) : 0;
                     long bonus = pct > 0 ? Math.max(1L, Math.round((double)active.prizeMoney * ((double)pct / 100.0))) : 0L;
                     long total = active.prizeMoney + bonus;
                     BigInteger cur = CobbleDollarsBridge.balance(player);
                     CobbleDollarsBridge.setBalance(player, cur.add(BigInteger.valueOf(total)));
                     if (pct > 0) {
                        player.sendSystemMessage(
                           Component.literal(
                              "§6[Radical Trainers] §a¡Has derrotado a "
                                 + active.trainerName
                                 + "! Premio: §e+$"
                                 + active.prizeMoney
                                 + " §6(+§a"
                                 + pct
                                 + "% §6sinergia gremio: §e+$"
                                 + bonus
                                 + "§6) §aTotal: §e+$"
                                 + total
                                 + " CobbleDollars."
                           )
                        );
                     } else {
                        player.sendSystemMessage(
                           Component.literal(
                              "§6[Radical Trainers] §a¡Has derrotado a "
                                 + active.trainerName
                                 + "! Premio oficial: §e+$"
                                 + active.prizeMoney
                                 + " CobbleDollars."
                           )
                        );
                     }
                  } catch (Throwable var12) {
                  }
               }
            }
         }
      }
   }

   public static void stopBattleForTrainer(UUID trainerEntityUuid) {
      if (trainerEntityUuid != null) {
         for (RadicalTrainerBattleManager.ActiveTestBattle battle : activeBattlesById.values()) {
            if (battle.trainer != null && battle.trainer.method_5667().equals(trainerEntityUuid)) {
               terminateBattle(battle, "§cEl combate ha finalizado porque el entrenador de pruebas ha muerto.");
               break;
            }
         }
      }
   }

   public static void terminateBattle(RadicalTrainerBattleManager.ActiveTestBattle battle, String message) {
      activeBattlesById.remove(battle.battleId);
      activeBattlesByPlayer.remove(battle.playerUuid);

      try {
         PokemonBattle pb = BattleRegistry.getBattle(battle.battleId);
         if (pb != null) {
            pb.stop();
         }
      } catch (Throwable var5) {
      }

      try {
         battle.trainer.method_31472();
      } catch (Throwable var4) {
      }

      MinecraftServer server = battle.trainer.method_5682();
      if (server != null && message != null) {
         ServerPlayer player = server.getPlayerList().getPlayer(battle.playerUuid);
         if (player != null) {
            player.sendSystemMessage(Component.literal(message));
         }
      }
   }

   public static void tick(MinecraftServer server) {
      if (server != null && !activeBattlesById.isEmpty()) {
         List<RadicalTrainerBattleManager.ActiveTestBattle> toTerminate = new ArrayList<>();

         for (RadicalTrainerBattleManager.ActiveTestBattle battle : activeBattlesById.values()) {
            if (BattleRegistry.getBattle(battle.battleId) == null) {
               toTerminate.add(battle);
            } else if (battle.trainer.method_5805() && !battle.trainer.method_29504() && !battle.trainer.method_31481()) {
               ServerPlayer player = server.getPlayerList().getPlayer(battle.playerUuid);
               if (player == null || !player.isAlive() || player.isDeadOrDying()) {
                  toTerminate.add(battle);
               }
            } else {
               toTerminate.add(battle);
            }
         }

         for (RadicalTrainerBattleManager.ActiveTestBattle b : toTerminate) {
            terminateBattle(b, "§cEl combate ha finalizado (entrenador eliminado o fuera de combate).");
         }
      }
   }

   private static RadicalTrainerBattleManager.TrainerData pickRandomTrainerData() {
      if (!trainerPaths.isEmpty()) {
         Path p = trainerPaths.get(RANDOM.nextInt(trainerPaths.size()));

         try {
            String jsonStr = Files.readString(p, StandardCharsets.UTF_8);
            return parseTrainerJson(jsonStr);
         } catch (Exception var2) {
         }
      }

      RadicalTrainerBattleManager.TrainerData def = new RadicalTrainerBattleManager.TrainerData();
      def.name = "Joven Chano";
      RadicalTrainerBattleManager.PokemonSpec s1 = new RadicalTrainerBattleManager.PokemonSpec();
      s1.species = "rattata";
      s1.nature = "adamant";
      s1.ability = "guts";
      s1.moves = List.of("quickattack", "bite", "hyperfang");
      def.team.add(s1);
      return def;
   }

   private static RadicalTrainerBattleManager.TrainerData parseTrainerJson(String jsonStr) {
      JsonObject obj = JsonParser.parseString(jsonStr).getAsJsonObject();
      RadicalTrainerBattleManager.TrainerData data = new RadicalTrainerBattleManager.TrainerData();
      data.name = obj.has("name") ? obj.get("name").getAsString() : "Entrenador";
      if (obj.has("team") && obj.get("team").isJsonArray()) {
         for (JsonElement el : obj.getAsJsonArray("team")) {
            if (el.isJsonObject()) {
               JsonObject po = el.getAsJsonObject();
               RadicalTrainerBattleManager.PokemonSpec spec = new RadicalTrainerBattleManager.PokemonSpec();
               spec.species = po.has("species") ? po.get("species").getAsString() : "rattata";
               spec.gender = po.has("gender") ? po.get("gender").getAsString() : null;
               spec.nature = po.has("nature") ? po.get("nature").getAsString() : null;
               spec.ability = po.has("ability") ? po.get("ability").getAsString() : null;
               if (po.has("moveset") && po.get("moveset").isJsonArray()) {
                  spec.moves = new ArrayList<>();

                  for (JsonElement me : po.getAsJsonArray("moveset")) {
                     spec.moves.add(me.getAsString());
                  }
               }

               if (po.has("aspects") && po.get("aspects").isJsonArray()) {
                  spec.aspects = new ArrayList<>();

                  for (JsonElement ae : po.getAsJsonArray("aspects")) {
                     spec.aspects.add(ae.getAsString());
                  }
               }

               data.team.add(spec);
            }
         }
      }

      return data;
   }

   private static record ActiveTestBattle(UUID battleId, UUID playerUuid, NPCEntity trainer, long prizeMoney, String trainerName) {
   }

   private static class PokemonSpec {
      String species;
      String gender;
      String nature;
      String ability;
      List<String> moves;
      List<String> aspects;
   }

   private static class TrainerData {
      String name;
      List<RadicalTrainerBattleManager.PokemonSpec> team = new ArrayList<>();
   }
}
