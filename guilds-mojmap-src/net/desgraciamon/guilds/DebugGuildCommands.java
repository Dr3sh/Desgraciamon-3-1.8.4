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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class DebugGuildCommands {
   private static final long MAX_ADJUST_HOURS = 87600L;
   private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());
   private static Supplier<GuildStorage> storageSupplier;
   private static Supplier<GuildPCManager> pcManagerSupplier;

   private DebugGuildCommands() {
   }

   static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<GuildStorage> storage, Supplier<GuildPCManager> pcManager) {
      storageSupplier = storage;
      pcManagerSupplier = pcManager;
      LiteralArgumentBuilder<CommandSourceStack> root = (LiteralArgumentBuilder<CommandSourceStack>)((LiteralArgumentBuilder)Commands.literal("debugguild")
            .requires(source -> source.hasPermission(2)))
         .executes(context -> status((CommandSourceStack)context.getSource()));
      root.then(Commands.literal("status").executes(context -> status((CommandSourceStack)context.getSource())));
      root.then(
         Commands.literal("player")
            .then(
               Commands.argument("jugador", StringArgumentType.word())
                  .executes(context -> player((CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "jugador")))
            )
      );
      root.then(
         Commands.literal("guild")
            .then(
               Commands.argument("gremio", StringArgumentType.greedyString())
                  .executes(context -> guild((CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "gremio")))
            )
      );
      LiteralArgumentBuilder<CommandSourceStack> cooldown = Commands.literal("cooldown");
      RequiredArgumentBuilder<CommandSourceStack, String> cooldownPlayer = (RequiredArgumentBuilder<CommandSourceStack, String>)Commands.argument(
            "jugador", StringArgumentType.word()
         )
         .executes(context -> cooldown((CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "jugador")));
      cooldownPlayer.then(
         Commands.literal("clear")
            .then(
               Commands.literal("confirm")
                  .executes(context -> clearCooldown((CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "jugador")))
            )
      );
      cooldownPlayer.then(
         Commands.literal("add")
            .then(
               Commands.argument("horas", LongArgumentType.longArg(1L, 87600L))
                  .then(
                     Commands.literal("confirm")
                        .executes(
                           context -> adjustCooldown(
                                 (CommandSourceStack)context.getSource(),
                                 StringArgumentType.getString(context, "jugador"),
                                 LongArgumentType.getLong(context, "horas")
                              )
                        )
                  )
            )
      );
      cooldownPlayer.then(
         Commands.literal("subtract")
            .then(
               Commands.argument("horas", LongArgumentType.longArg(1L, 87600L))
                  .then(
                     Commands.literal("confirm")
                        .executes(
                           context -> adjustCooldown(
                                 (CommandSourceStack)context.getSource(),
                                 StringArgumentType.getString(context, "jugador"),
                                 -LongArgumentType.getLong(context, "horas")
                              )
                        )
                  )
            )
      );
      cooldown.then(cooldownPlayer);
      root.then(cooldown);
      LiteralArgumentBuilder<CommandSourceStack> bank = Commands.literal("bank");
      RequiredArgumentBuilder<CommandSourceStack, String> bankGuild = (RequiredArgumentBuilder<CommandSourceStack, String>)Commands.argument(
            "gremio", StringArgumentType.string()
         )
         .executes(context -> bank((CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "gremio")));
      bankGuild.then(
         Commands.literal("set")
            .then(
               Commands.argument("cantidad", StringArgumentType.word())
                  .then(
                     Commands.literal("confirm")
                        .executes(
                           context -> setBank(
                                 (CommandSourceStack)context.getSource(),
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
         Commands.literal("waypoints")
            .then(
               Commands.argument("gremio", StringArgumentType.greedyString())
                  .executes(context -> waypoints((CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "gremio")))
            )
      );
      root.then(
         Commands.literal("pc")
            .then(
               Commands.argument("gremio", StringArgumentType.greedyString())
                  .executes(context -> pc((CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "gremio")))
            )
      );
      LiteralArgumentBuilder<CommandSourceStack> sync = Commands.literal("sync");
      sync.then(Commands.literal("all").then(Commands.literal("confirm").executes(context -> syncAll((CommandSourceStack)context.getSource()))));
      sync.then(
         Commands.argument("jugador", StringArgumentType.word())
            .executes(context -> syncPlayer((CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "jugador")))
      );
      root.then(sync);
      root.then(
         Commands.literal("validate")
            .then(
               Commands.argument("gremio", StringArgumentType.greedyString())
                  .executes(context -> validate((CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "gremio")))
            )
      );
      dispatcher.register(root);
   }

   private static int status(CommandSourceStack source) {
      GuildStorage storage = storage();
      source.sendSuccess(
         () -> Component.literal(
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

   private static int player(CommandSourceStack source, String nameOrUuid) {
      UUID uuid = resolveUuid(source.getServer(), nameOrUuid);
      if (uuid == null) {
         return fail(source, "Jugador no encontrado en linea; tambien puedes usar su UUID.");
      } else {
         GuildStorage storage = storage();
         String guildId = storage.index().playerGuilds.get(uuid.toString());
         GuildStorage.Guild guild = guildId == null ? null : storage.getGuild(guildId);
         GuildStorage.Member member = guild == null ? null : guild.members.get(uuid.toString());
         ServerPlayer online = source.getServer().getPlayerList().getPlayer(uuid);
         String balance = online != null && CobbleDollarsBridge.isAvailable() ? wallet(online) : "no disponible";
         source.sendSuccess(
            () -> Component.literal(
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

   private static int guild(CommandSourceStack source, String identifier) {
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
         source.sendSuccess(
            () -> Component.literal(
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

   private static int cooldown(CommandSourceStack source, String nameOrUuid) {
      UUID uuid = resolveUuid(source.getServer(), nameOrUuid);
      if (uuid == null) {
         return fail(source, "Jugador no encontrado en linea; tambien puedes usar su UUID.");
      } else {
         source.sendSuccess(() -> Component.literal(uuid + ": " + cooldownText(storage(), uuid)), false);
         return 1;
      }
   }

   private static int clearCooldown(CommandSourceStack source, String nameOrUuid) {
      UUID uuid = resolveUuid(source.getServer(), nameOrUuid);
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

         source.sendSuccess(() -> Component.literal("Cooldown eliminado para " + uuid + "."), true);
         return 1;
      }
   }

   private static int adjustCooldown(CommandSourceStack source, String nameOrUuid, long hours) {
      UUID uuid = resolveUuid(source.getServer(), nameOrUuid);
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
         source.sendSuccess(() -> Component.literal("Cooldown ajustado " + signed(hours) + " h para " + uuid + "; vence: " + result), true);
         return 1;
      }
   }

   private static int bank(CommandSourceStack source, String identifier) {
      GuildStorage.Guild guild = findGuild(identifier);
      if (guild == null) {
         return fail(source, "No se encontro ese gremio por nombre o UUID.");
      } else {
         source.sendSuccess(() -> Component.literal("Banco de " + guild.name + ": $" + guild.bankBalance), false);
         return 1;
      }
   }

   private static int setBank(CommandSourceStack source, String identifier, String amountText) {
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

         GuildNetwork.syncAll(source.getServer());
         source.sendSuccess(() -> Component.literal("Banco de " + guild.name + " fijado en $" + amount + "."), true);
         return 1;
      }
   }

   private static int waypoints(CommandSourceStack source, String identifier) {
      GuildStorage.Guild guild = findGuild(identifier);
      if (guild == null) {
         return fail(source, "No se encontro ese gremio por nombre o UUID.");
      } else {
         source.sendSuccess(() -> Component.literal("Waypoints de " + guild.name + " (" + guild.waypoints.size() + ")"), false);

         for (GuildStorage.Waypoint waypoint : guild.waypoints) {
            String owner = guild.members.containsKey(waypoint.ownerUuid) ? guild.members.get(waypoint.ownerUuid).playerName : waypoint.ownerUuid;
            source.sendSuccess(
               () -> Component.literal(
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

   private static int pc(CommandSourceStack source, String identifier) {
      GuildStorage.Guild guild = findGuild(identifier);
      if (guild == null) {
         return fail(source, "No se encontro ese gremio por nombre o UUID.");
      } else {
         GuildPCManager manager = pcManagerSupplier == null ? null : pcManagerSupplier.get();
         int count = manager == null ? -1 : manager.debugPokemonCount(guild);
         String detail = count < 0 ? "administrador PC no disponible" : count + " Pokemon";
         source.sendSuccess(() -> Component.literal("PC de " + guild.name + ": " + detail + ", JSON=" + guild.storedPokemon.size() + " campos"), false);
         return 1;
      }
   }

   private static int syncPlayer(CommandSourceStack source, String name) {
      ServerPlayer player = source.getServer().getPlayerList().getPlayerByName(name);
      if (player == null) {
         return fail(source, "El jugador debe estar conectado para sincronizar.");
      } else {
         GuildNetwork.sync(player, source.getServer(), storage());
         source.sendSuccess(() -> Component.literal("Sincronizacion enviada a " + name + "."), false);
         return 1;
      }
   }

   private static int syncAll(CommandSourceStack source) {
      GuildNetwork.syncAll(source.getServer());
      source.sendSuccess(() -> Component.literal("Sincronizacion enviada a todos los jugadores conectados."), false);
      return 1;
   }

   private static int validate(CommandSourceStack source, String identifier) {
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
            source.sendSuccess(() -> Component.literal("Validacion OK: " + guild.name + " (" + guild.members.size() + " miembros)."), false);
         } else {
            source.sendFailure(Component.literal("Problemas en " + guild.name + ": " + String.join("; ", problems)));
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
         ServerPlayer player = server.getPlayerList().getPlayerByName(nameOrUuid);
         return player == null ? null : player.getUUID();
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

   private static String wallet(ServerPlayer player) {
      try {
         return "$" + CobbleDollarsBridge.balance(player);
      } catch (ReflectiveOperationException var2) {
         return "error al consultar";
      }
   }

   private static String signed(long value) {
      return value > 0L ? "+" + value : Long.toString(value);
   }

   private static void addPlayerAudit(GuildStorage storage, UUID target, CommandSourceStack source, String event, String details) {
      String guildId = storage.index().playerGuilds.get(target.toString());
      GuildStorage.Guild guild = guildId == null ? null : storage.getGuild(guildId);
      if (guild != null) {
         addGuildAudit(guild, source, event, target + " " + details);
      }
   }

   private static void addGuildAudit(GuildStorage.Guild guild, CommandSourceStack source, String event, String details) {
      UUID actor = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : new UUID(0L, 0L);
      guild.activityLog.add(new GuildStorage.Activity(TIME.format(Instant.now()), event, actor, details, source.getTextName()));
   }

   private static GuildStorage storage() {
      GuildStorage storage = storageSupplier == null ? null : storageSupplier.get();
      if (storage == null) {
         throw new IllegalStateException("Guild storage is not ready");
      } else {
         return storage;
      }
   }

   private static int fail(CommandSourceStack source, String message) {
      source.sendFailure(Component.literal(message));
      return 0;
   }
}
