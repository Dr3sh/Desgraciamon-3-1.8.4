package net.desgraciamon.guilds;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.storage.pc.PCBox;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.api.storage.pc.link.PCLinkManager;
import com.cobblemon.mod.common.net.messages.client.storage.pc.OpenPCPacket;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import kotlin.Unit;
import net.minecraft.class_2561;
import net.minecraft.class_3222;
import net.minecraft.server.MinecraftServer;

final class GuildPCManager {
   private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy - HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());
   private final MinecraftServer server;
   private final GuildStorage storage;
   private final Map<String, PCStore> stores = new HashMap<>();
   private final Map<String, Map<String, String>> contents = new HashMap<>();
   private final Map<String, String> serializedStores = new HashMap<>();
   private final Map<String, UUID> lastActors = new HashMap<>();
   private final Set<String> dirtyGuilds = new HashSet<>();

   GuildPCManager(MinecraftServer server, GuildStorage storage) {
      this.server = server;
      this.storage = storage;
   }

   boolean open(class_3222 player) {
      return this.open(player, 0);
   }

   boolean open(class_3222 player, int boxIndex) {
      GuildStorage.Guild guild = this.guildFor(player);
      if (guild != null && !guild.disbanded) {
         PCStore store = this.stores.computeIfAbsent(guild.guildId, ignored -> this.loadStore(guild));
         this.contents.computeIfAbsent(guild.guildId, ignored -> snapshot(store));
         this.serializedStores.putIfAbsent(guild.guildId, guild.storedPokemon == null ? "{}" : guild.storedPokemon.toString());
         this.lastActors.put(guild.guildId, player.method_5667());
         PCLinkManager.INSTANCE.addLink(player.method_5667(), store, candidate -> {
            String activeGuildId = this.storage.index().playerGuilds.get(candidate.method_5667().toString());
            return guild.guildId.equals(activeGuildId) && !guild.disbanded;
         });
         store.addObserver(player);
         int validBox = Math.max(0, Math.min(boxIndex, Math.max(0, store.getBoxes().size() - 1)));
         new OpenPCPacket(store, validBox).sendToPlayer(player);
         return true;
      } else {
         player.method_43496(class_2561.method_43470("No perteneces a ningun gremio activo."));
         return false;
      }
   }

   PCStore getStore(GuildStorage.Guild guild) {
      if (guild != null && !guild.disbanded) {
         PCStore store = this.stores.computeIfAbsent(guild.guildId, ignored -> this.loadStore(guild));
         this.contents.computeIfAbsent(guild.guildId, ignored -> snapshot(store));
         this.serializedStores.putIfAbsent(guild.guildId, guild.storedPokemon == null ? "{}" : guild.storedPokemon.toString());
         return store;
      } else {
         return null;
      }
   }

   void markDirty(String guildId) {
      this.dirtyGuilds.add(guildId);
   }

   List<String> getBoxPreviews(GuildStorage.Guild guild) {
      List<String> list = new ArrayList<>();
      if (guild == null) {
         return list;
      } else {
         PCStore store = this.stores.computeIfAbsent(guild.guildId, ignored -> this.loadStore(guild));
         List<PCBox> boxes = store.getBoxes();

         for (int b = 0; b < boxes.size(); b++) {
            PCBox box = boxes.get(b);

            for (int s = 0; s < 30; s++) {
               Pokemon p = box.get(s);
               if (p != null) {
                  String species = p.getSpecies().getName().toLowerCase(Locale.ROOT);
                  int lvl = p.getLevel();
                  boolean shiny = p.getShiny();
                  String name = p.getDisplayName(false).getString();
                  Set<String> aspects = new HashSet<>(p.getAspects());
                  String formName = p.getForm().getName().toLowerCase(Locale.ROOT);
                  if (!formName.isEmpty() && !formName.equals("normal") && !formName.equals("standard")) {
                     aspects.add(formName);
                  }

                  if (formName.contains("alola")) {
                     aspects.add("alolan");
                  }

                  if (formName.contains("galar")) {
                     aspects.add("galarian");
                  }

                  if (formName.contains("hisui")) {
                     aspects.add("hisuian");
                  }

                  if (formName.contains("paldea")) {
                     aspects.add("paldean");
                  }

                  String form = String.join(" ", aspects);
                  if (form.isBlank()) {
                     form = "standard";
                  }

                  String depositor = p.getPersistentData().method_10558("guild_depositor_name");
                  if (depositor == null || depositor.isBlank()) {
                     String ot = p.getOriginalTrainerName();
                     depositor = ot != null && !ot.isBlank() ? ot : "Miembro";
                  }

                  String ivs = p.getIvs().getOrDefault(Stats.HP)
                     + "/"
                     + p.getIvs().getOrDefault(Stats.ATTACK)
                     + "/"
                     + p.getIvs().getOrDefault(Stats.DEFENCE)
                     + "/"
                     + p.getIvs().getOrDefault(Stats.SPECIAL_ATTACK)
                     + "/"
                     + p.getIvs().getOrDefault(Stats.SPECIAL_DEFENCE)
                     + "/"
                     + p.getIvs().getOrDefault(Stats.SPEED);
                  String evs = p.getEvs().getOrDefault(Stats.HP)
                     + "/"
                     + p.getEvs().getOrDefault(Stats.ATTACK)
                     + "/"
                     + p.getEvs().getOrDefault(Stats.DEFENCE)
                     + "/"
                     + p.getEvs().getOrDefault(Stats.SPECIAL_ATTACK)
                     + "/"
                     + p.getEvs().getOrDefault(Stats.SPECIAL_DEFENCE)
                     + "/"
                     + p.getEvs().getOrDefault(Stats.SPEED);
                  StringBuilder movesB = new StringBuilder();

                  for (Move m : p.getMoveSet().getMoves()) {
                     if (movesB.length() > 0) {
                        movesB.append(";");
                     }

                     movesB.append(m.getTemplate().getDisplayName().getString()).append(",").append(m.getCurrentPp()).append(",").append(m.getMaxPp());
                  }

                  list.add(
                     b
                        + "|"
                        + s
                        + "|"
                        + species
                        + "|"
                        + lvl
                        + "|"
                        + (shiny ? "1" : "0")
                        + "|"
                        + name
                        + "|"
                        + form
                        + "|"
                        + depositor
                        + "|"
                        + ivs
                        + "|"
                        + evs
                        + "|"
                        + movesB
                  );
               }
            }
         }

         return list;
      }
   }

   void tick() {
      for (String guildId : new HashSet<>(this.dirtyGuilds)) {
         PCStore store = this.stores.get(guildId);
         GuildStorage.Guild guild = this.storage.getGuild(guildId);
         if (guild != null && store != null) {
            Map<String, String> previous = this.contents.computeIfAbsent(guildId, ignored -> snapshot(store));
            Map<String, String> current = snapshot(store);
            String serialized = store.saveToJSON(new JsonObject(), this.server.method_30611()).toString();
            boolean changed = !serialized.equals(this.serializedStores.get(guildId));
            if (!changed) {
               this.dirtyGuilds.remove(guildId);
            } else {
               this.recordPokemonChanges(guild, previous, current);
               guild.storedPokemon = store.saveToJSON(new JsonObject(), this.server.method_30611());
               this.contents.put(guildId, current);
               this.serializedStores.put(guildId, serialized);
               this.storage.save();
               GuildNetwork.syncAll(this.server);
               this.dirtyGuilds.remove(guildId);
            }
         } else {
            this.dirtyGuilds.remove(guildId);
         }
      }
   }

   void saveAll() {
      for (Entry<String, PCStore> entry : this.stores.entrySet()) {
         GuildStorage.Guild guild = this.storage.getGuild(entry.getKey());
         if (guild != null) {
            guild.storedPokemon = entry.getValue().saveToJSON(new JsonObject(), this.server.method_30611());
         }
      }

      this.storage.save();
   }

   void noteActor(class_3222 player) {
      String guildId = this.storage.index().playerGuilds.get(player.method_5667().toString());
      if (guildId != null && this.stores.get(guildId) == PCLinkManager.INSTANCE.getPC(player)) {
         this.lastActors.put(guildId, player.method_5667());
      }
   }

   int debugPokemonCount(GuildStorage.Guild guild) {
      PCStore store = this.stores.computeIfAbsent(guild.guildId, ignoredx -> this.loadStore(guild));
      int count = 0;

      for (Pokemon ignored : store) {
         count++;
      }

      return count;
   }

   private PCStore loadStore(GuildStorage.Guild guild) {
      UUID storeId = UUID.fromString(guild.guildId);
      PCStore store = new PCStore(storeId);
      if (guild.storedPokemon != null && !guild.storedPokemon.entrySet().isEmpty()) {
         store.loadFromJSON(guild.storedPokemon, this.server.method_30611());
      } else {
         store.resize(Cobblemon.INSTANCE.getConfig().getDefaultBoxCount(), false, pokemon -> Unit.INSTANCE);
      }

      store.initialize();
      this.sanitizeStore(guild, store);
      store.getPcChangeObservable().subscribe(Priority.NORMAL, ignored -> this.dirtyGuilds.add(guild.guildId));
      return store;
   }

   private void sanitizeStore(GuildStorage.Guild guild, PCStore store) {
      List<Pokemon> toEject = new ArrayList<>();

      for (Pokemon p : store) {
         if (p != null && DesgraciamonGuilds.isRestrictedPokemon(p)) {
            toEject.add(p);
         }
      }

      if (!toEject.isEmpty()) {
         for (Pokemon px : toEject) {
            store.remove(px);

            try {
               UUID targetUuid = null;
               String depositorName = px.getPersistentData().method_10558("guild_depositor_name");
               if (depositorName != null && !depositorName.isEmpty() && this.server.method_3793() != null) {
                  GameProfile cached = (GameProfile)this.server.method_3793().method_14515(depositorName).orElse(null);
                  if (cached != null) {
                     targetUuid = cached.getId();
                  }
               }

               if (targetUuid == null && guild.founderUuid != null) {
                  try {
                     targetUuid = UUID.fromString(guild.founderUuid);
                  } catch (Exception var10) {
                  }
               }

               if (targetUuid != null) {
                  PCStore playerPc = Cobblemon.INSTANCE.getStorage().getPC(targetUuid, this.server.method_30611());
                  if (playerPc != null) {
                     playerPc.add(px);
                     playerPc.saveToJSON(new JsonObject(), this.server.method_30611());
                  }
               }
            } catch (Exception var11) {
            }

            UUID founder = null;

            try {
               founder = UUID.fromString(guild.founderUuid);
            } catch (Exception var9) {
            }

            if (founder == null) {
               founder = UUID.randomUUID();
            }

            guild.activityLog
               .add(
                  new GuildStorage.Activity(
                     LOG_TIME.format(Instant.now()),
                     "POKEMON_EJECTED_RESTRICTED",
                     founder,
                     px.getDisplayName(false).getString() + " (Eyectado: Legendario no permitido)"
                  )
               );
         }

         guild.storedPokemon = store.saveToJSON(new JsonObject(), this.server.method_30611());
         this.storage.save();
      }
   }

   private void recordPokemonChanges(GuildStorage.Guild guild, Map<String, String> previous, Map<String, String> current) {
      UUID actor = this.lastActors.getOrDefault(guild.guildId, UUID.fromString(guild.founderUuid));
      class_3222 actorPlayer = this.server.method_3760().method_14602(actor);
      String actorName = actorPlayer != null ? actorPlayer.method_7334().getName() : null;
      if (actorName == null && this.server.method_3793() != null) {
         GameProfile cached = (GameProfile)this.server.method_3793().method_14512(actor).orElse(null);
         if (cached != null) {
            actorName = cached.getName();
         }
      }

      if (actorName == null) {
         actorName = "Miembro";
      }

      Set<String> added = new HashSet<>(current.keySet());
      added.removeAll(previous.keySet());
      PCStore store = this.stores.get(guild.guildId);

      for (String pokemonId : added) {
         guild.activityLog.add(new GuildStorage.Activity(LOG_TIME.format(Instant.now()), "POKEMON_DEPOSITED", actor, current.get(pokemonId)));
         if (store != null) {
            try {
               UUID u = UUID.fromString(pokemonId);

               for (Pokemon p : store) {
                  if (p.getUuid().equals(u)) {
                     if (p.getPersistentData().method_10558("guild_depositor_name").isEmpty()) {
                        p.getPersistentData().method_10582("guild_depositor_name", actorName);
                     }
                     break;
                  }
               }
            } catch (Exception var14) {
            }
         }
      }

      Set<String> removed = new HashSet<>(previous.keySet());
      removed.removeAll(current.keySet());

      for (String pokemonIdx : removed) {
         guild.activityLog.add(new GuildStorage.Activity(LOG_TIME.format(Instant.now()), "POKEMON_WITHDRAWN", actor, previous.get(pokemonIdx)));
      }
   }

   private static Map<String, String> snapshot(PCStore store) {
      Map<String, String> snapshot = new HashMap<>();

      for (Pokemon pokemon : store) {
         String species = pokemon.getSpecies().getName().toLowerCase(Locale.ROOT);
         int level = pokemon.getLevel();
         boolean shiny = pokemon.getShiny();
         Set<String> aspects = new HashSet<>(pokemon.getAspects());
         String formName = pokemon.getForm().getName().toLowerCase(Locale.ROOT);
         if (!formName.isEmpty() && !formName.equals("normal") && !formName.equals("standard")) {
            aspects.add(formName);
         }

         if (formName.contains("alola")) {
            aspects.add("alolan");
         }

         if (formName.contains("galar")) {
            aspects.add("galarian");
         }

         if (formName.contains("hisui")) {
            aspects.add("hisuian");
         }

         if (formName.contains("paldea")) {
            aspects.add("paldean");
         }

         String form = String.join(" ", aspects);
         if (form.isBlank()) {
            form = "standard";
         }

         String displayName = pokemon.getDisplayName(false).getString();
         String depositor = pokemon.getPersistentData().method_10558("guild_depositor_name");
         if (depositor.isEmpty()) {
            depositor = "Miembro";
         }

         String ivs = pokemon.getIvs().getOrDefault(Stats.HP)
            + "/"
            + pokemon.getIvs().getOrDefault(Stats.ATTACK)
            + "/"
            + pokemon.getIvs().getOrDefault(Stats.DEFENCE)
            + "/"
            + pokemon.getIvs().getOrDefault(Stats.SPECIAL_ATTACK)
            + "/"
            + pokemon.getIvs().getOrDefault(Stats.SPECIAL_DEFENCE)
            + "/"
            + pokemon.getIvs().getOrDefault(Stats.SPEED);
         String evs = pokemon.getEvs().getOrDefault(Stats.HP)
            + "/"
            + pokemon.getEvs().getOrDefault(Stats.ATTACK)
            + "/"
            + pokemon.getEvs().getOrDefault(Stats.DEFENCE)
            + "/"
            + pokemon.getEvs().getOrDefault(Stats.SPECIAL_ATTACK)
            + "/"
            + pokemon.getEvs().getOrDefault(Stats.SPECIAL_DEFENCE)
            + "/"
            + pokemon.getEvs().getOrDefault(Stats.SPEED);
         StringBuilder moves = new StringBuilder();

         for (Move m : pokemon.getMoveSet().getMoves()) {
            if (moves.length() > 0) {
               moves.append(";");
            }

            moves.append(m.getTemplate().getDisplayName().getString()).append(",").append(m.getCurrentPp()).append(",").append(m.getMaxPp());
         }

         String pokeData = species + "|" + level + "|" + shiny + "|" + form + "|" + displayName + "|" + depositor + "|" + ivs + "|" + evs + "|" + moves;
         String description = displayName + " (Nv." + level + (shiny ? " ★" : "") + ") §DATA:" + pokeData;
         snapshot.put(pokemon.getUuid().toString(), description);
      }

      return snapshot;
   }

   private GuildStorage.Guild guildFor(class_3222 player) {
      String id = this.storage.index().playerGuilds.get(player.method_5667().toString());
      return id == null ? null : this.storage.getGuild(id);
   }
}
