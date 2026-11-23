package net.desgraciamon.guilds;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStarted;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStopping;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.EndTick;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.Disconnect;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.Join;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

public final class DesgraciamonGuilds implements ModInitializer {
   public static final String MOD_ID = "desgraciamon_guilds";
   private static GuildStorage storage;
   private static GuildPCManager pcManager;
   private static int syncTicks;
   private static final Set<String> ULTRA_BEASTS = Set.of(
      "nihilego", "buzzwole", "pheromosa", "xurkitree", "celesteela", "kartana", "guzzlord", "poipole", "naganadel", "stakataka", "blacephalon"
   );
   private static final Set<String> KNOWN_RESTRICTED = Set.of(
      "articuno",
      "zapdos",
      "moltres",
      "mewtwo",
      "mew",
      "raikou",
      "entei",
      "suicune",
      "lugia",
      "hooh",
      "ho-oh",
      "celebi",
      "regirock",
      "regice",
      "registeel",
      "latias",
      "latios",
      "kyogre",
      "groudon",
      "rayquaza",
      "jirachi",
      "deoxys",
      "uxie",
      "mesprit",
      "azelf",
      "dialga",
      "palkia",
      "heatran",
      "regigigas",
      "giratina",
      "cresselia",
      "phione",
      "manaphy",
      "darkrai",
      "shaymin",
      "arceus",
      "victini",
      "cobalion",
      "terrakion",
      "virizion",
      "tornadus",
      "thundurus",
      "reshiram",
      "zekrom",
      "landorus",
      "kyurem",
      "keldeo",
      "meloetta",
      "genesect",
      "xerneas",
      "yveltal",
      "zygarde",
      "diancie",
      "hoopa",
      "volcanion",
      "typenull",
      "type: null",
      "type:null",
      "silvally",
      "tapukoko",
      "tapu koko",
      "tapulele",
      "tapu lele",
      "tapubulu",
      "tapu bulu",
      "tapufini",
      "tapu fini",
      "cosmog",
      "cosmoem",
      "solgaleo",
      "lunala",
      "necrozma",
      "magearna",
      "marshadow",
      "zeraora",
      "meltan",
      "melmetal",
      "zacian",
      "zamazenta",
      "eternatus",
      "kubfu",
      "urshifu",
      "zarude",
      "regieleki",
      "regidrago",
      "glastrier",
      "spectrier",
      "calyrex",
      "enamorus",
      "wo-chien",
      "wochien",
      "chien-pao",
      "chienpao",
      "ting-lu",
      "tinglu",
      "chi-yu",
      "chiyu",
      "koraidon",
      "miraidon",
      "walking wake",
      "walkingwake",
      "iron leaves",
      "ironleaves",
      "okidogi",
      "munkidori",
      "fezandipiti",
      "ogerpon",
      "gouging fire",
      "gougingfire",
      "raging bolt",
      "ragingbolt",
      "iron boulder",
      "ironboulder",
      "iron crown",
      "ironcrown",
      "terapagos",
      "pecharunt"
   );

   public static void noteGuildPCAction(ServerPlayer player) {
      if (pcManager != null) {
         pcManager.noteActor(player);
      }
   }

   public static GuildPCManager getPcManager() {
      return pcManager;
   }

   public static GuildStorage getStorage() {
      return storage;
   }

   public static boolean isGuildPC(PCStore store) {
      return store != null && storage != null ? storage.getGuild(store.getUuid().toString()) != null : false;
   }

   public static boolean isRestrictedPokemon(Pokemon pokemon) {
      if (pokemon == null) {
         return false;
      } else if (pokemon.isUltraBeast() || pokemon.hasLabels(new String[]{"ultra_beast"})) {
         return false;
      } else if (!pokemon.isLegendary() && !pokemon.isMythical()) {
         if (pokemon.getForm() != null && pokemon.getForm().getLabels() != null) {
            for (String label : pokemon.getForm().getLabels()) {
               if (label != null) {
                  String lower = label.toLowerCase(Locale.ROOT);
                  if (lower.equals("legendary") || lower.equals("mythical") || lower.equals("restricted")) {
                     return true;
                  }
               }
            }
         }

         Species species = pokemon.getSpecies();
         if (species != null) {
            if (species.getLabels() != null) {
               if (species.getLabels().contains("ultra_beast")) {
                  return false;
               }

               for (String labelx : species.getLabels()) {
                  if (labelx != null) {
                     String lower = labelx.toLowerCase(Locale.ROOT);
                     if (lower.equals("legendary") || lower.equals("mythical") || lower.equals("restricted")) {
                        return true;
                     }
                  }
               }
            }

            if (isRestrictedSpecies(species.getName()) || isRestrictedSpecies(species.getResourceIdentifier().getPath())) {
               return true;
            }
         }

         return false;
      } else {
         return true;
      }
   }

   public static boolean isRestrictedSpecies(String speciesName) {
      if (speciesName != null && !speciesName.isBlank()) {
         String clean = speciesName.trim().toLowerCase(Locale.ROOT);
         String stripped = clean.replace("-", "").replace(" ", "").replace(":", "").replace(".", "").replace("'", "");
         if (ULTRA_BEASTS.contains(clean) || ULTRA_BEASTS.contains(stripped)) {
            return false;
         } else if (!KNOWN_RESTRICTED.contains(clean) && !KNOWN_RESTRICTED.contains(stripped)) {
            try {
               Species species = PokemonSpecies.getByName(clean);
               if (species == null) {
                  species = PokemonSpecies.getByName(stripped);
               }

               if (species != null && species.getLabels() != null) {
                  if (species.getLabels().contains("ultra_beast")) {
                     return false;
                  }

                  for (String label : species.getLabels()) {
                     if (label != null) {
                        String lower = label.toLowerCase(Locale.ROOT);
                        if (lower.equals("legendary") || lower.equals("mythical") || lower.equals("restricted")) {
                           return true;
                        }
                     }
                  }
               }
            } catch (Throwable var7) {
            }

            return false;
         } else {
            return true;
         }
      } else {
         return false;
      }
   }

   public void onInitialize() {
      GuildNetwork.initialize(() -> storage);
      GuildSynergyManager.initialize();
      RadicalTrainerBattleManager.initialize();
      CommandRegistrationCallback.EVENT
         .register(
            (CommandRegistrationCallback)(dispatcher, registryAccess, environment) -> {
               GuildCommands.register(dispatcher, () -> storage);
               DebugGuildCommands.register(dispatcher, () -> storage, () -> pcManager);
               dispatcher.register(
                  (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("rctest")
                              .executes(
                                 ctx -> RadicalTrainerBattleManager.startRandomTrainerBattle(((CommandSourceStack)ctx.getSource()).getPlayerOrException())
                              ))
                           .then(
                              Commands.literal("random")
                                 .executes(
                                    ctx -> RadicalTrainerBattleManager.startRandomTrainerBattle(((CommandSourceStack)ctx.getSource()).getPlayerOrException())
                                 )
                           ))
                        .then(
                           Commands.literal("lvl1")
                              .executes(ctx -> RadicalTrainerBattleManager.startLevel1TestBattle(((CommandSourceStack)ctx.getSource()).getPlayerOrException()))
                        ))
                     .then(
                        Commands.literal("dummy")
                           .executes(ctx -> RadicalTrainerBattleManager.startLevel1TestBattle(((CommandSourceStack)ctx.getSource()).getPlayerOrException()))
                     )
               );
            }
         );
      GuildCommands.setPcManagerSupplier(() -> pcManager);
      ServerLifecycleEvents.SERVER_STARTED.register((ServerStarted)server -> {
         storage = new GuildStorage(server);
         pcManager = new GuildPCManager(server, storage);
      });
      ServerTickEvents.END_SERVER_TICK.register((EndTick)server -> {
         if (pcManager != null) {
            pcManager.tick();
         }

         GuildSynergyManager.tick(server);
         RadicalTrainerBattleManager.tick(server);
         if (++syncTicks >= 40) {
            GuildNetwork.syncAll(server);
            syncTicks = 0;
         }
      });
      ServerPlayConnectionEvents.JOIN.register((Join)(handler, sender, server) -> {
         if (storage != null) {
            GuildNetwork.sync(handler.player, server, storage);
         }
      });
      ServerPlayConnectionEvents.DISCONNECT.register((Disconnect)(handler, server) -> {
         if (storage != null) {
            String guildId = storage.index().playerGuilds.get(handler.player.getUUID().toString());
            GuildStorage.Guild guild = guildId == null ? null : storage.getGuild(guildId);
            GuildStorage.Member member = guild == null ? null : guild.members.get(handler.player.getUUID().toString());
            if (member != null) {
               member.lastSeenTimestamp = System.currentTimeMillis() / 1000L;
               storage.save();
            }
         }
      });
      ServerLifecycleEvents.SERVER_STOPPING.register((ServerStopping)server -> {
         if (storage != null) {
            if (pcManager != null) {
               pcManager.saveAll();
            }

            storage.save();
            storage = null;
            pcManager = null;
         }
      });
   }
}
