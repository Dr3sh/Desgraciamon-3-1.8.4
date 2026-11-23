package net.desgraciamon.guilds;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.function.Supplier;
import net.minecraft.class_2168;
import net.minecraft.class_2170;
import net.minecraft.class_2561;
import net.minecraft.class_3222;
import net.minecraft.server.MinecraftServer;

final class DebugGuildCommands {
   private static final long MAX_ADJUST_HOURS = 87600L;
   private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());
   private static Supplier<GuildStorage> storageSupplier;
   private static Supplier<GuildPCManager> pcManagerSupplier;

   private DebugGuildCommands() {
   }

   static void register(CommandDispatcher<class_2168> dispatcher, Supplier<GuildStorage> storage, Supplier<GuildPCManager> pcManager) {
      storageSupplier = storage;
      pcManagerSupplier = pcManager;
      LiteralArgumentBuilder<class_2168> root = (LiteralArgumentBuilder<class_2168>)((LiteralArgumentBuilder)class_2170.method_9247("debugguild")
            .requires(source -> source.method_9259(2)))
         .executes(context -> status((class_2168)context.getSource()));
      root.then(class_2170.method_9247("status").executes(context -> status((class_2168)context.getSource())));
      root.then(
         class_2170.method_9247("player")
            .then(
               class_2170.method_9244("jugador", StringArgumentType.word())
                  .executes(context -> player((class_2168)context.getSource(), StringArgumentType.getString(context, "jugador")))
            )
      );
      root.then(
         class_2170.method_9247("guild")
            .then(
               class_2170.method_9244("gremio", StringArgumentType.greedyString())
                  .executes(context -> guild((class_2168)context.getSource(), StringArgumentType.getString(context, "gremio")))
            )
      );
      LiteralArgumentBuilder<class_2168> cooldown = class_2170.method_9247("cooldown");
      RequiredArgumentBuilder<class_2168, String> cooldownPlayer = (RequiredArgumentBuilder<class_2168, String>)class_2170.method_9244(
            "jugador", StringArgumentType.word()
         )
         .executes(context -> cooldown((class_2168)context.getSource(), StringArgumentType.getString(context, "jugador")));
      cooldownPlayer.then(
         class_2170.method_9247("clear")
            .then(
               class_2170.method_9247("confirm")
                  .executes(context -> clearCooldown((class_2168)context.getSource(), StringArgumentType.getString(context, "jugador")))
            )
      );
      cooldownPlayer.then(
         class_2170.method_9247("add")
            .then(
               class_2170.method_9244("horas", LongArgumentType.longArg(1L, 87600L))
                  .then(
                     class_2170.method_9247("confirm")
                        .executes(
                           context -> adjustCooldown(
                                 (class_2168)context.getSource(), StringArgumentType.getString(context, "jugador"), LongArgumentType.getLong(context, "horas")
                              )
                        )
                  )
            )
      );
      cooldownPlayer.then(
         class_2170.method_9247("subtract")
            .then(
               class_2170.method_9244("horas", LongArgumentType.longArg(1L, 87600L))
                  .then(
                     class_2170.method_9247("confirm")
                        .executes(
                           context -> adjustCooldown(
                                 (class_2168)context.getSource(), StringArgumentType.getString(context, "jugador"), -LongArgumentType.getLong(context, "horas")
                              )
                        )
                  )
            )
      );
      cooldown.then(cooldownPlayer);
      root.then(cooldown);
      LiteralArgumentBuilder<class_2168> bank = class_2170.method_9247("bank");
      RequiredArgumentBuilder<class_2168, String> bankGuild = (RequiredArgumentBuilder<class_2168, String>)class_2170.method_9244(
            "gremio", StringArgumentType.string()
         )
         .executes(context -> bank((class_2168)context.getSource(), StringArgumentType.getString(context, "gremio")));
      bankGuild.then(
         class_2170.method_9247("set")
            .then(
               class_2170.method_9244("cantidad", StringArgumentType.word())
                  .then(
                     class_2170.method_9247("confirm")
                        .executes(
                           context -> setBank(
                                 (class_2168)context.getSource(),
                                 StringArgumentType.getString(context, "gremio"),
                                 StringArgumentType.getString(context, "cantidad")
                              )
                        )
                  )
            )
      );
      bank.then(bankGuild);
      root.then(bank);
      root.then(
         class_2170.method_9247("waypoints")
            .then(
               class_2170.method_9244("gremio", StringArgumentType.greedyString())
                  .executes(context -> waypoints((class_2168)context.getSource(), StringArgumentType.getString(context, "gremio")))
            )
      );
      root.then(
         class_2170.method_9247("pc")
            .then(
               class_2170.method_9244("gremio", StringArgumentType.greedyString())
                  .executes(context -> pc((class_2168)context.getSource(), StringArgumentType.getString(context, "gremio")))
            )
      );
      LiteralArgumentBuilder<class_2168> sync = class_2170.method_9247("sync");
      sync.then(class_2170.method_9247("all").then(class_2170.method_9247("confirm").executes(context -> syncAll((class_2168)context.getSource()))));
      sync.then(
         class_2170.method_9244("jugador", StringArgumentType.word())
            .executes(context -> syncPlayer((class_2168)context.getSource(), StringArgumentType.getString(context, "jugador")))
      );
      root.then(sync);
      root.then(
         class_2170.method_9247("validate")
            .then(
               class_2170.method_9244("gremio", StringArgumentType.greedyString())
                  .executes(context -> validate((class_2168)context.getSource(), StringArgumentType.getString(context, "gremio")))
            )
      );
      dispatcher.register(root);
   }

   private static int status(class_2168 source) {
      GuildStorage storage = storage();
      source.method_9226(
         () -> class_2561.method_43470(
               "Desgraciamon Guilds: "
                  + storage.activeGuildCount()
                  + " gremios activos, "
                  + storage.cooldownCount()
                  + " cooldowns activos, CobbleDollars="
                  + CobbleDollarsBridge.isAvailable()
                  + ", datos="
                  + storage.dataDirectory()
            ),
         false
      );
      return 1;
   }

   private static int player(class_2168 source, String nameOrUuid) {
      UUID uuid = resolveUuid(source.method_9211(), nameOrUuid);
      if (uuid == null) {
         return fail(source, "Jugador no encontrado en linea; tambien puedes usar su UUID.");
      } else {
         GuildStorage storage = storage();
         String guildId = storage.index().playerGuilds.get(uuid.toString());
         GuildStorage.Guild guild = guildId == null ? null : storage.getGuild(guildId);
         GuildStorage.Member member = guild == null ? null : guild.members.get(uuid.toString());
         class_3222 online = source.method_9211().method_3760().method_14602(uuid);
         String balance = online != null && CobbleDollarsBridge.isAvailable() ? wallet(online) : "no disponible";
         source.method_9226(
            () -> class_2561.method_43470(
                  "Jugador "
                     + uuid
                     + ": gremio="
                     + (guild == null ? "ninguno" : guild.name + " [" + (member == null ? "sin ficha" : member.role) + "]")
                     + ", CobbleDollars="
                     + balance
                     + ", "
                     + cooldownText(storage, uuid)
               ),
            false
         );
         return 1;
      }
   }

   private static int guild(class_2168 source, String identifier) {
      GuildStorage.Guild guild = findGuild(identifier);
      if (guild == null) {
         return fail(source, "No se encontro ese gremio por nombre o UUID.");
      } else {
         String leader = guild.members
            .values()
            .stream()
            .filter(member -> "LEADER".equals(member.role))
            .map(member -> member.playerName)
            .findFirst()
            .orElse("ninguno");
         source.method_9226(
            () -> class_2561.method_43470(
                  "Guild "
                     + guild.name
                     + " ["
                     + guild.guildId
                     + "]: activa="
                     + !guild.disbanded
                     + ", lider="
                     + leader
                     + ", miembros="
                     + guild.members.size()
                     + ", banco=$"
                     + guild.bankBalance
                     + ", waypoints="
                     + guild.waypoints.size()
                     + ", eventos="
                     + guild.activityLog.size()
                     + ", pokemonGuardados="
                     + guild.storedPokemon.size()
               ),
            false
         );
         return 1;
      }
   }

   private static int cooldown(class_2168 source, String nameOrUuid) {
      UUID uuid = resolveUuid(source.method_9211(), nameOrUuid);
      if (uuid == null) {
         return fail(source, "Jugador no encontrado en linea; tambien puedes usar su UUID.");
      } else {
         source.method_9226(() -> class_2561.method_43470(uuid + ": " + cooldownText(storage(), uuid)), false);
         return 1;
      }
   }

   private static int clearCooldown(class_2168 source, String nameOrUuid) {
      UUID uuid = resolveUuid(source.method_9211(), nameOrUuid);
      if (uuid == null) {
         return fail(source, "Jugador no encontrado en linea; tambien puedes usar su UUID.");
      } else {
         GuildStorage storage = storage();
         long oldExpiry = storage.cooldownExpiresAt(uuid);
         storage.clearCooldown(uuid);
         addPlayerAudit(storage, uuid, source, "DEBUG_COOLDOWN_CLEARED", "Cooldown eliminado");

         try {
            storage.save();
         } catch (RuntimeException var7) {
            storage.setCooldownExpiry(uuid, oldExpiry);
            return fail(source, "No se pudo guardar; se restauro el cooldown anterior.");
         }

         source.method_9226(() -> class_2561.method_43470("Cooldown eliminado para " + uuid + "."), true);
         return 1;
      }
   }

   private static int adjustCooldown(class_2168 source, String nameOrUuid, long hours) {
      UUID uuid = resolveUuid(source.method_9211(), nameOrUuid);
      if (uuid == null) {
         return fail(source, "Jugador no encontrado en linea; tambien puedes usar su UUID.");
      } else {
         GuildStorage storage = storage();
         long oldExpiry = storage.cooldownExpiresAt(uuid);

         long expiry;
         try {
            expiry = storage.adjustCooldown(uuid, hours);
         } catch (ArithmeticException var12) {
            return fail(source, "El ajuste excede el rango permitido.");
         }

         addPlayerAudit(storage, uuid, source, hours > 0L ? "DEBUG_COOLDOWN_ADDED" : "DEBUG_COOLDOWN_SUBTRACTED", signed(hours) + " horas");

         try {
            storage.save();
         } catch (RuntimeException var11) {
            storage.setCooldownExpiry(uuid, oldExpiry);
            return fail(source, "No se pudo guardar; se restauro el cooldown anterior.");
         }

         String result = expiry <= System.currentTimeMillis() ? "sin cooldown" : TIME.format(Instant.ofEpochMilli(expiry));
         source.method_9226(() -> class_2561.method_43470("Cooldown ajustado " + signed(hours) + " h para " + uuid + "; vence: " + result), true);
         return 1;
      }
   }

   private static int bank(class_2168 source, String identifier) {
      GuildStorage.Guild guild = findGuild(identifier);
      if (guild == null) {
         return fail(source, "No se encontro ese gremio por nombre o UUID.");
      } else {
         source.method_9226(() -> class_2561.method_43470("Banco de " + guild.name + ": $" + guild.bankBalance), false);
         return 1;
      }
   }

   private static int setBank(class_2168 source, String identifier, String amountText) {
      GuildStorage.Guild guild = findGuild(identifier);
      if (guild == null) {
         return fail(source, "No se encontro ese gremio por nombre o UUID.");
      } else if (!amountText.matches("[0-9]{1,100}")) {
         return fail(source, "La cantidad debe ser un entero no negativo de hasta 100 digitos.");
      } else {
         BigInteger amount = new BigInteger(amountText);
         BigInteger previous = guild.bankBalance;
         guild.bankBalance = amount;
         addGuildAudit(guild, source, "DEBUG_BANK_SET", "$" + previous + " -> $" + amount);

         try {
            storage().save();
         } catch (RuntimeException var7) {
            guild.bankBalance = previous;
            guild.activityLog.remove(guild.activityLog.size() - 1);
            return fail(source, "No se pudo guardar el banco; se restauro el saldo anterior.");
         }

         GuildNetwork.syncAll(source.method_9211());
         source.method_9226(() -> class_2561.method_43470("Banco de " + guild.name + " fijado en $" + amount + "."), true);
         return 1;
      }
   }

   private static int waypoints(class_2168 source, String identifier) {
      GuildStorage.Guild guild = findGuild(identifier);
      if (guild == null) {
         return fail(source, "No se encontro ese gremio por nombre o UUID.");
      } else {
         source.method_9226(() -> class_2561.method_43470("Waypoints de " + guild.name + " (" + guild.waypoints.size() + ")"), false);

         for (GuildStorage.Waypoint waypoint : guild.waypoints) {
            String owner = guild.members.containsKey(waypoint.ownerUuid) ? guild.members.get(waypoint.ownerUuid).playerName : waypoint.ownerUuid;
            source.method_9226(
               () -> class_2561.method_43470(
                     (waypoint.shared ? "[COMPARTIDO] " : "[PERSONAL] ")
                        + waypoint.name
                        + " / "
                        + owner
                        + " / "
                        + waypoint.dimension
                        + " "
                        + waypoint.x
                        + ","
                        + waypoint.y
                        + ","
                        + waypoint.z
                  ),
               false
            );
         }

         return 1;
      }
   }

   private static int pc(class_2168 source, String identifier) {
      GuildStorage.Guild guild = findGuild(identifier);
      if (guild == null) {
         return fail(source, "No se encontro ese gremio por nombre o UUID.");
      } else {
         GuildPCManager manager = pcManagerSupplier == null ? null : pcManagerSupplier.get();
         int count = manager == null ? -1 : manager.debugPokemonCount(guild);
         String detail = count < 0 ? "administrador PC no disponible" : count + " Pokemon";
         source.method_9226(() -> class_2561.method_43470("PC de " + guild.name + ": " + detail + ", JSON=" + guild.storedPokemon.size() + " campos"), false);
         return 1;
      }
   }

   private static int syncPlayer(class_2168 source, String name) {
      class_3222 player = source.method_9211().method_3760().method_14566(name);
      if (player == null) {
         return fail(source, "El jugador debe estar conectado para sincronizar.");
      } else {
         GuildNetwork.sync(player, source.method_9211(), storage());
         source.method_9226(() -> class_2561.method_43470("Sincronizacion enviada a " + name + "."), false);
         return 1;
      }
   }

   private static int syncAll(class_2168 source) {
      GuildNetwork.syncAll(source.method_9211());
      source.method_9226(() -> class_2561.method_43470("Sincronizacion enviada a todos los jugadores conectados."), false);
      return 1;
   }

   private static int validate(class_2168 source, String identifier) {
      GuildStorage storage = storage();
      GuildStorage.Guild guild = findGuild(identifier);
      if (guild == null) {
         return fail(source, "No se encontro ese gremio por nombre o UUID.");
      } else {
         List<String> problems = new ArrayList<>();
         long leaders = guild.members.values().stream().filter(member -> "LEADER".equals(member.role)).count();
         if (leaders != 1L) {
            problems.add("lideres=" + leaders);
         }

         if (!guild.members.containsKey(guild.founderUuid)) {
            problems.add("founderUuid no pertenece al gremio");
         }

         for (GuildStorage.Member member : guild.members.values()) {
            if (!List.of("LEADER", "OFFICER", "MEMBER").contains(member.role)) {
               problems.add("rango invalido para " + member.playerName + ": " + member.role);
            }

            if (!guild.guildId.equals(storage.index().playerGuilds.get(member.playerUuid))) {
               problems.add("indice incorrecto para " + member.playerName);
            }
         }

         if (guild.bankBalance == null || guild.bankBalance.signum() < 0) {
            problems.add("saldo de banco invalido");
         }

         for (GuildStorage.Waypoint waypoint : guild.waypoints) {
            if (waypoint.name == null || waypoint.name.isBlank() || waypoint.dimension == null) {
               problems.add("waypoint incompleto: " + waypoint.waypointId);
            }
         }

         for (Entry<String, String> entry : storage.index().playerGuilds.entrySet()) {
            if (guild.guildId.equals(entry.getValue()) && !guild.members.containsKey(entry.getKey())) {
               problems.add("indice huerfano para " + entry.getKey());
            }
         }

         if (problems.isEmpty()) {
            source.method_9226(() -> class_2561.method_43470("Validacion OK: " + guild.name + " (" + guild.members.size() + " miembros)."), false);
         } else {
            source.method_9213(class_2561.method_43470("Problemas en " + guild.name + ": " + String.join("; ", problems)));
         }

         return problems.isEmpty() ? 1 : 0;
      }
   }

   private static GuildStorage.Guild findGuild(String identifier) {
      GuildStorage storage = storage();
      GuildStorage.Guild guild = storage.getGuild(identifier);
      return guild != null ? guild : storage.guilds().stream().filter(candidate -> candidate.name.equalsIgnoreCase(identifier)).findFirst().orElse(null);
   }

   private static UUID resolveUuid(MinecraftServer server, String nameOrUuid) {
      try {
         return UUID.fromString(nameOrUuid);
      } catch (IllegalArgumentException var4) {
         class_3222 player = server.method_3760().method_14566(nameOrUuid);
         return player == null ? null : player.method_5667();
      }
   }

   private static String cooldownText(GuildStorage storage, UUID player) {
      long remaining = storage.cooldownRemaining(player);
      return remaining <= 0L
         ? "sin cooldown"
         : "restante="
            + remaining / 3600000L
            + "h "
            + remaining % 3600000L / 60000L
            + "m, vence="
            + TIME.format(Instant.ofEpochMilli(storage.cooldownExpiresAt(player)));
   }

   private static String wallet(class_3222 player) {
      try {
         return "$" + CobbleDollarsBridge.balance(player);
      } catch (ReflectiveOperationException var2) {
         return "error al consultar";
      }
   }

   private static String signed(long value) {
      return value > 0L ? "+" + value : Long.toString(value);
   }

   private static void addPlayerAudit(GuildStorage storage, UUID target, class_2168 source, String event, String details) {
      String guildId = storage.index().playerGuilds.get(target.toString());
      GuildStorage.Guild guild = guildId == null ? null : storage.getGuild(guildId);
      if (guild != null) {
         addGuildAudit(guild, source, event, target + " " + details);
      }
   }

   private static void addGuildAudit(GuildStorage.Guild guild, class_2168 source, String event, String details) {
      UUID actor = source.method_9228() instanceof class_3222 player ? player.method_5667() : new UUID(0L, 0L);
      guild.activityLog.add(new GuildStorage.Activity(TIME.format(Instant.now()), event, actor, details, source.method_9214()));
   }

   private static GuildStorage storage() {
      GuildStorage storage = storageSupplier == null ? null : storageSupplier.get();
      if (storage == null) {
         throw new IllegalStateException("Guild storage is not ready");
      } else {
         return storage;
      }
   }

   private static int fail(class_2168 source, String message) {
      source.method_9213(class_2561.method_43470(message));
      return 0;
   }
}
