package net.desgraciamon.guilds;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

final class GuildCommands {
   private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
   private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy - HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());
   private static Supplier<GuildStorage> storageSupplier;
   private static Supplier<GuildPCManager> pcManagerSupplier;

   private GuildCommands() {
   }

   static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<GuildStorage> storage) {
      storageSupplier = storage;
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                                                                        "guild"
                                                                     )
                                                                     .then(
                                                                        ((LiteralArgumentBuilder)Commands.literal("pc")
                                                                              .executes(context -> openPC((CommandSourceStack)context.getSource(), 0)))
                                                                           .then(
                                                                              Commands.argument("box", IntegerArgumentType.integer(1, 30))
                                                                                 .executes(
                                                                                    context -> openPC(
                                                                                          (CommandSourceStack)context.getSource(),
                                                                                          IntegerArgumentType.getInteger(context, "box") - 1
                                                                                       )
                                                                                 )
                                                                           )
                                                                     ))
                                                                  .then(
                                                                     Commands.literal("create")
                                                                        .then(
                                                                           Commands.argument("nameAndColor", StringArgumentType.greedyString())
                                                                              .executes(
                                                                                 context -> createFromRaw(
                                                                                       (CommandSourceStack)context.getSource(),
                                                                                       StringArgumentType.getString(context, "nameAndColor")
                                                                                    )
                                                                              )
                                                                        )
                                                                  ))
                                                               .then(
                                                                  Commands.literal("invite")
                                                                     .then(
                                                                        Commands.argument("player", StringArgumentType.word())
                                                                           .executes(
                                                                              context -> invite(
                                                                                    (CommandSourceStack)context.getSource(),
                                                                                    StringArgumentType.getString(context, "player")
                                                                                 )
                                                                           )
                                                                     )
                                                               ))
                                                            .then(
                                                               Commands.literal("accept").executes(context -> accept((CommandSourceStack)context.getSource()))
                                                            ))
                                                         .then(
                                                            Commands.literal("decline").executes(context -> decline((CommandSourceStack)context.getSource()))
                                                         ))
                                                      .then(
                                                         ((LiteralArgumentBuilder)Commands.literal("leave")
                                                               .executes(context -> leave((CommandSourceStack)context.getSource(), "")))
                                                            .then(
                                                               Commands.argument("successor", StringArgumentType.word())
                                                                  .executes(
                                                                     context -> leave(
                                                                           (CommandSourceStack)context.getSource(),
                                                                           StringArgumentType.getString(context, "successor")
                                                                        )
                                                                  )
                                                            )
                                                      ))
                                                   .then(
                                                      Commands.literal("kick")
                                                         .then(
                                                            Commands.argument("player", StringArgumentType.word())
                                                               .executes(
                                                                  context -> kick(
                                                                        (CommandSourceStack)context.getSource(),
                                                                        StringArgumentType.getString(context, "player")
                                                                     )
                                                               )
                                                         )
                                                   ))
                                                .then(
                                                   Commands.literal("promote")
                                                      .then(
                                                         Commands.argument("player", StringArgumentType.word())
                                                            .executes(
                                                               context -> setRole(
                                                                     (CommandSourceStack)context.getSource(),
                                                                     StringArgumentType.getString(context, "player"),
                                                                     "OFFICER"
                                                                  )
                                                            )
                                                      )
                                                ))
                                             .then(
                                                Commands.literal("demote")
                                                   .then(
                                                      Commands.argument("player", StringArgumentType.word())
                                                         .executes(
                                                            context -> setRole(
                                                                  (CommandSourceStack)context.getSource(),
                                                                  StringArgumentType.getString(context, "player"),
                                                                  "MEMBER"
                                                               )
                                                         )
                                                   )
                                             ))
                                          .then(
                                             Commands.literal("rename")
                                                .then(
                                                   Commands.argument("name", StringArgumentType.greedyString())
                                                      .executes(
                                                         context -> rename(
                                                               (CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "name")
                                                            )
                                                      )
                                                )
                                          ))
                                       .then(
                                          Commands.literal("color")
                                             .then(
                                                Commands.argument("hex", StringArgumentType.greedyString())
                                                   .executes(
                                                      context -> color((CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "hex"))
                                                   )
                                             )
                                       ))
                                    .then(Commands.literal("disband").executes(context -> disband((CommandSourceStack)context.getSource()))))
                                 .then(Commands.literal("info").executes(context -> info((CommandSourceStack)context.getSource()))))
                              .then(Commands.literal("log").executes(context -> log((CommandSourceStack)context.getSource()))))
                           .then(
                              Commands.literal("deposit")
                                 .then(
                                    Commands.argument("amount", StringArgumentType.word())
                                       .executes(
                                          context -> transfer((CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "amount"), true)
                                       )
                                 )
                           ))
                        .then(
                           Commands.literal("withdraw")
                              .then(
                                 Commands.argument("amount", StringArgumentType.word())
                                    .executes(
                                       context -> transfer((CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "amount"), false)
                                    )
                              )
                        ))
                     .then(
                        ((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("waypoint")
                                    .then(
                                       Commands.literal("add")
                                          .then(
                                             Commands.argument("name", StringArgumentType.word())
                                                .executes(
                                                   context -> addWaypoint(
                                                         (CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "name")
                                                      )
                                                )
                                          )
                                    ))
                                 .then(
                                    Commands.literal("share")
                                       .then(
                                          Commands.argument("name", StringArgumentType.word())
                                             .executes(
                                                context -> shareWaypoint((CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "name"))
                                             )
                                       )
                                 ))
                              .then(
                                 Commands.literal("remove")
                                    .then(
                                       Commands.argument("name", StringArgumentType.word())
                                          .executes(
                                             context -> removeWaypoint((CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "name"))
                                          )
                                    )
                              ))
                           .then(Commands.literal("list").executes(context -> listWaypoints((CommandSourceStack)context.getSource())))
                     ))
                  .then(
                     Commands.literal("chat")
                        .then(
                           Commands.argument("message", StringArgumentType.greedyString())
                              .executes(context -> chat((CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "message")))
                        )
                  ))
               .then(
                  ((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("debug")
                           .then(
                              Commands.literal("bot")
                                 .then(
                                    Commands.argument("cantidad", IntegerArgumentType.integer(0, 10))
                                       .executes(
                                          context -> setDebugBots((CommandSourceStack)context.getSource(), IntegerArgumentType.getInteger(context, "cantidad"))
                                       )
                                 )
                           ))
                        .then(
                           Commands.literal("addnpc")
                              .then(
                                 Commands.argument("nombre", StringArgumentType.word())
                                    .executes(context -> addNpcMember((CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "nombre")))
                              )
                        ))
                     .then(Commands.literal("clear").executes(context -> setDebugBots((CommandSourceStack)context.getSource(), 0)))
               ))
            .then(
               ((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("testbattle")
                           .executes(
                              context -> RadicalTrainerBattleManager.startRandomTrainerBattle(((CommandSourceStack)context.getSource()).getPlayerOrException())
                           ))
                        .then(
                           Commands.literal("lvl1")
                              .executes(
                                 context -> RadicalTrainerBattleManager.startLevel1TestBattle(((CommandSourceStack)context.getSource()).getPlayerOrException())
                              )
                        ))
                     .then(
                        Commands.literal("dummy")
                           .executes(
                              context -> RadicalTrainerBattleManager.startLevel1TestBattle(((CommandSourceStack)context.getSource()).getPlayerOrException())
                           )
                     ))
                  .then(
                     Commands.literal("random")
                        .executes(
                           context -> RadicalTrainerBattleManager.startRandomTrainerBattle(((CommandSourceStack)context.getSource()).getPlayerOrException())
                        )
                  )
            )
      );
      dispatcher.register(
         (LiteralArgumentBuilder)Commands.literal("gc")
            .then(
               Commands.argument("message", StringArgumentType.greedyString())
                  .executes(context -> chat((CommandSourceStack)context.getSource(), StringArgumentType.getString(context, "message")))
            )
      );
   }

   static void setPcManagerSupplier(Supplier<GuildPCManager> supplier) {
      pcManagerSupplier = supplier;
   }

   private static int chat(CommandSourceStack source, String message) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildStorage storage = storageSupplier.get();
      String id = storage.index().playerGuilds.get(player.getUUID().toString());
      if (id == null) {
         source.sendFailure(Component.literal("No perteneces a ningún gremio."));
         return 0;
      } else {
         GuildStorage.Guild guild = storage.getGuild(id);
         if (guild != null && !guild.disbanded) {
            GuildNetwork.sendGuildChatMessage(player.getServer(), player, guild, message);
            storage.save();
            GuildNetwork.syncAll(player.getServer());
            return 1;
         } else {
            source.sendFailure(Component.literal("Tu gremio ya no existe."));
            return 0;
         }
      }
   }

   private static int openPC(CommandSourceStack source, int boxIndex) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildPCManager manager = pcManagerSupplier == null ? null : pcManagerSupplier.get();
      if (manager != null && manager.open(player, boxIndex)) {
         success(source, "Abriendo caja " + (boxIndex + 1) + " del PC del gremio.");
         return 1;
      } else {
         return 0;
      }
   }

   static String normalizeGuildName(String raw) {
      if (raw == null) {
         return null;
      } else {
         String trimmed = raw.trim();
         if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
         }

         trimmed = trimmed.replaceAll("\\s+", " ");
         if (trimmed.length() < 2 || trimmed.length() > 24) {
            return null;
         } else {
            return !trimmed.matches("[\\p{L}\\p{N}_ ]+") ? null : trimmed;
         }
      }
   }

   private static int createFromRaw(CommandSourceStack source, String raw) throws CommandSyntaxException {
      if (raw == null) {
         return fail(source, "Uso: /guild create <nombre> <#RRGGBB>");
      } else {
         String trimmed = raw.trim();
         int lastSpace = trimmed.lastIndexOf(32);
         if (lastSpace <= 0) {
            return fail(source, "Uso: /guild create <nombre> <#RRGGBB>");
         } else {
            String rawName = trimmed.substring(0, lastSpace).trim();
            String rawColor = trimmed.substring(lastSpace + 1).trim();
            return create(source, rawName, rawColor);
         }
      }
   }

   private static int create(CommandSourceStack source, String name, String color) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildStorage store = getStorage();
      if (store.index().playerGuilds.containsKey(key(player))) {
         return fail(source, "Ya perteneces a un gremio.");
      } else if (store.isCoolingDown(player.getUUID())) {
         return fail(source, "Cooldown activo: " + remaining(store.cooldownRemaining(player.getUUID())) + ".");
      } else {
         name = normalizeGuildName(name);
         if (name == null) {
            return fail(source, "El nombre debe tener entre 2 y 24 caracteres (letras, numeros, espacios o guiones bajos).");
         } else {
            color = normalizeColor(color);
            if (color == null) {
               return fail(source, "Color invalido. Usa seis digitos HEX, con # opcional.");
            } else {
               GuildStorage.Guild guild = new GuildStorage.Guild(name, color.toUpperCase(Locale.ROOT), player.getUUID());
               guild.members.get(key(player)).playerName = player.getGameProfile().getName();
               store.addGuild(guild);
               store.index().playerGuilds.put(key(player), guild.guildId);
               activity(guild, "CREATED", player, "Gremio creado");
               store.save();
               GuildNetwork.syncAll(source.getServer());
               success(source, "Gremio " + name + " creado.");
               return 1;
            }
         }
      }
   }

   private static int invite(CommandSourceStack source, String targetName) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildStorage.Guild guild = guildFor(source, player);
      if (guild == null) {
         return 0;
      } else if (!hasRole(guild, player, "LEADER", "OFFICER")) {
         return fail(source, "Solo lideres y oficiales pueden invitar.");
      } else {
         ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(targetName);
         if (target == null) {
            return fail(source, "El jugador debe estar conectado para recibir la invitacion.");
         } else if (store().index().playerGuilds.containsKey(key(target))) {
            return fail(source, "Ese jugador ya pertenece a un gremio.");
         } else if (store().isCoolingDown(target.getUUID())) {
            return fail(source, "Ese jugador tiene un cooldown activo.");
         } else {
            store().index().invitations.put(key(target), guild.guildId);
            activity(guild, "INVITED", player, target.getGameProfile().getName());
            store().save();
            target.sendSystemMessage(
               Component.literal(
                  "§a§l[GREMIO] §fHas recibido una invitación para unirte a §b"
                     + guild.name
                     + "§f. Abre el menú del gremio para verla o usa §e/guild accept§f."
               )
            );
            GuildNetwork.sync(target, source.getServer(), store());
            success(source, "Invitacion enviada a " + targetName + ".");
            return 1;
         }
      }
   }

   private static int accept(CommandSourceStack source) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildStorage current = getStorage();
      String guildId = current.index().invitations.get(key(player));
      GuildStorage.Guild guild = guildId == null ? null : current.getGuild(guildId);
      if (guild != null && !guild.disbanded) {
         if (current.index().playerGuilds.containsKey(key(player))) {
            return fail(source, "Ya perteneces a un gremio.");
         } else if (current.isCoolingDown(player.getUUID())) {
            return fail(source, "Cooldown activo: " + remaining(current.cooldownRemaining(player.getUUID())) + ".");
         } else {
            guild.members
               .put(key(player), new GuildStorage.Member(player.getUUID(), player.getGameProfile().getName(), "MEMBER", System.currentTimeMillis() / 1000L));
            current.index().playerGuilds.put(key(player), guild.guildId);
            current.index().invitations.remove(key(player));
            activity(guild, "JOINED", player, player.getGameProfile().getName());
            current.save();
            GuildNetwork.syncAll(source.getServer());
            success(source, "Te uniste a " + guild.name + ".");
            return 1;
         }
      } else {
         return fail(source, "No tienes una invitacion pendiente.");
      }
   }

   private static int decline(CommandSourceStack source) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildStorage current = getStorage();
      String guildId = current.index().invitations.remove(key(player));
      if (guildId == null) {
         return fail(source, "No tienes ninguna invitación pendiente.");
      } else {
         current.save();
         GuildNetwork.sync(player, source.getServer(), current);
         success(source, "Has rechazado la invitación.");
         return 1;
      }
   }

   private static int leave(CommandSourceStack source, String successorName) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildStorage.Guild guild = guildFor(source, player);
      if (guild == null) {
         return 0;
      } else {
         if (isLeader(guild, player)) {
            if (successorName.isBlank()) {
               return fail(source, "Debes elegir un miembro que herede el liderazgo antes de salir.");
            }

            GuildStorage.Member successor = findMember(guild, successorName);
            if (successor == null || successor.playerUuid.equals(player.getUUID().toString())) {
               return fail(source, "El sucesor debe ser otro miembro de tu gremio.");
            }

            String previousLeader = player.getGameProfile().getName();
            guild.members.get(key(player)).role = "OFFICER";
            successor.role = "LEADER";
            guild.founderUuid = successor.playerUuid;
            activity(guild, "LEADERSHIP_TRANSFERRED", player, previousLeader + " -> " + successor.playerName);
         } else if (!successorName.isBlank()) {
            return fail(source, "Solo el lider puede transferir el liderazgo al salir.");
         }

         removeMember(guild, player, "LEFT", "salio del gremio");
         getStorage().applyCooldown(player.getUUID());
         getStorage().save();
         success(source, "Has salido del gremio. Cooldown aplicado.");
         return 1;
      }
   }

   private static int kick(CommandSourceStack source, String targetName) throws CommandSyntaxException {
      ServerPlayer actor = source.getPlayerOrException();
      GuildStorage.Guild guild = guildFor(source, actor);
      if (guild == null) {
         return 0;
      } else if (!isLeader(guild, actor)) {
         return fail(source, "Solo el lider puede expulsar miembros.");
      } else {
         ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(targetName);
         GuildStorage.Member member = findMember(guild, targetName);
         if (member != null && !member.playerUuid.equals(guild.founderUuid)) {
            UUID targetUuid = UUID.fromString(member.playerUuid);
            removeMember(guild, targetUuid, targetName, "KICKED", "expulsado del gremio");
            getStorage().applyCooldown(targetUuid);
            getStorage().save();
            if (target != null) {
               target.sendSystemMessage(Component.literal("Has sido expulsado del gremio " + guild.name + "."));
            }

            success(source, targetName + " fue expulsado.");
            return 1;
         } else {
            return fail(source, "No se encontro un miembro expulsable con ese nombre.");
         }
      }
   }

   private static int setRole(CommandSourceStack source, String targetName, String role) throws CommandSyntaxException {
      ServerPlayer actor = source.getPlayerOrException();
      GuildStorage.Guild guild = guildFor(source, actor);
      if (guild == null) {
         return 0;
      } else if (!isLeader(guild, actor)) {
         return fail(source, "Solo el lider puede cambiar rangos.");
      } else {
         GuildStorage.Member member = findMember(guild, targetName);
         if (member != null && !member.playerUuid.equals(guild.founderUuid)) {
            member.role = role;
            activity(guild, role.equals("OFFICER") ? "PROMOTED" : "DEMOTED", actor, targetName + " -> " + role);
            getStorage().save();
            success(source, targetName + " ahora es " + role + ".");
            return 1;
         } else {
            return fail(source, "No se encontro un miembro modificable.");
         }
      }
   }

   private static int rename(CommandSourceStack source, String name) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildStorage.Guild guild = guildFor(source, player);
      if (guild == null) {
         return 0;
      } else if (!isLeader(guild, player)) {
         return fail(source, "Solo el lider puede renombrar el gremio.");
      } else {
         name = normalizeGuildName(name);
         if (name == null) {
            return fail(source, "El nombre debe tener entre 2 y 24 caracteres (letras, numeros, espacios o guiones bajos).");
         } else {
            String previous = guild.name;
            guild.name = name;
            activity(guild, "RENAMED", player, previous + " -> " + name);
            getStorage().save();
            GuildNetwork.syncAll(source.getServer());
            success(source, "Gremio renombrado a " + name + ".");
            return 1;
         }
      }
   }

   private static int color(CommandSourceStack source, String hex) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildStorage.Guild guild = guildFor(source, player);
      if (guild == null) {
         return 0;
      } else if (!isLeader(guild, player)) {
         return fail(source, "Solo el lider puede cambiar el color.");
      } else {
         hex = normalizeColor(hex);
         if (hex == null) {
            return fail(source, "Color invalido. Usa seis digitos HEX, con # opcional.");
         } else {
            guild.colorHex = hex;
            activity(guild, "COLOR_CHANGED", player, guild.colorHex);
            getStorage().save();
            GuildNetwork.syncAll(source.getServer());
            success(source, "Color del gremio actualizado.");
            return 1;
         }
      }
   }

   private static int disband(CommandSourceStack source) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildStorage.Guild guild = guildFor(source, player);
      if (guild == null) {
         return 0;
      } else if (!isLeader(guild, player)) {
         return fail(source, "Solo el lider puede disolver el gremio.");
      } else {
         activity(guild, "DISBANDED", player, guild.name);
         guild.disbanded = true;

         for (String memberId : guild.members.keySet()) {
            getStorage().index().playerGuilds.remove(memberId);
            getStorage().applyCooldown(UUID.fromString(memberId));
         }

         getStorage().save();
         success(source, "Gremio disuelto. El historial queda archivado.");
         return 1;
      }
   }

   private static int info(CommandSourceStack source) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildStorage.Guild guild = guildFor(source, player);
      if (guild == null) {
         return 0;
      } else {
         String role = guild.members.get(key(player)).role;
         source.sendSuccess(
            () -> Component.literal(
                  guild.name + " " + guild.colorHex + " | " + role + " | miembros: " + guild.members.size() + " | banco: " + guild.bankBalance
               ),
            false
         );

         for (GuildStorage.Member member : guild.members.values()) {
            source.sendSuccess(() -> Component.literal(member.playerName + " - " + member.role), false);
         }

         return 1;
      }
   }

   private static int log(CommandSourceStack source) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildStorage.Guild guild = guildFor(source, player);
      if (guild == null) {
         return 0;
      } else {
         int start = Math.max(0, guild.activityLog.size() - 20);

         for (int i = start; i < guild.activityLog.size(); i++) {
            GuildStorage.Activity entry = guild.activityLog.get(i);
            source.sendSuccess(() -> Component.literal("[" + entry.timestamp + "] " + entry.event + " " + entry.details), false);
         }

         if (guild.activityLog.isEmpty()) {
            source.sendSuccess(() -> Component.literal("El historial esta vacio."), false);
         }

         return 1;
      }
   }

   private static int transfer(CommandSourceStack source, String amountText, boolean deposit) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildStorage.Guild guild = guildFor(source, player);
      if (guild == null) {
         return 0;
      } else if (!deposit && !hasRole(guild, player, "LEADER", "OFFICER", "MEMBER")) {
         return fail(source, "No tienes acceso al banco.");
      } else if (!CobbleDollarsBridge.isAvailable()) {
         return fail(source, "CobbleDollars no esta instalado; no se modifico ningun saldo.");
      } else if (amountText.length() <= 100 && amountText.matches("[0-9]{1,100}")) {
         BigInteger amount = new BigInteger(amountText);
         if (amount.signum() <= 0) {
            return fail(source, "La cantidad debe ser mayor que cero.");
         } else if (!deposit && guild.bankBalance.compareTo(amount) < 0) {
            return fail(source, "El banco no tiene fondos suficientes.");
         } else {
            BigInteger oldWallet;
            try {
               oldWallet = CobbleDollarsBridge.balance(player);
            } catch (ReflectiveOperationException var15) {
               return fail(source, "No se pudo leer tu saldo de CobbleDollars; el banco no fue modificado.");
            }

            if (deposit && oldWallet.compareTo(amount) < 0) {
               return fail(source, "No tienes suficientes fondos personales.");
            } else {
               BigInteger oldBank = guild.bankBalance;
               BigInteger newWallet = deposit ? oldWallet.subtract(amount) : oldWallet.add(amount);
               BigInteger newBank = deposit ? oldBank.add(amount) : oldBank.subtract(amount);
               GuildStorage.Activity activity = new GuildStorage.Activity(
                  LOG_TIME.format(Instant.now()),
                  deposit ? "MONEY_DEPOSITED" : "MONEY_WITHDRAWN",
                  player.getUUID(),
                  amount.toString(),
                  player.getGameProfile().getName()
               );

               try {
                  CobbleDollarsBridge.setBalance(player, newWallet);
                  guild.bankBalance = newBank;
                  guild.activityLog.add(activity);
                  getStorage().save();
               } catch (RuntimeException | ReflectiveOperationException var14) {
                  guild.bankBalance = oldBank;
                  guild.activityLog.remove(activity);

                  try {
                     CobbleDollarsBridge.setBalance(player, oldWallet);
                  } catch (ReflectiveOperationException var13) {
                     var14.addSuppressed(var13);
                  }

                  return fail(source, "La transferencia fallo y se intento restaurar ambos saldos.");
               }

               success(source, (deposit ? "Depositaste " : "Retiraste ") + amount + " $.");
               return 1;
            }
         }
      } else {
         return fail(source, "Escribe una cantidad positiva valida.");
      }
   }

   private static int addWaypoint(CommandSourceStack source, String name) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildStorage.Guild guild = guildFor(source, player);
      if (guild == null) {
         return 0;
      } else if (name.length() > 32) {
         return fail(source, "El nombre del waypoint no puede superar 32 caracteres.");
      } else {
         GuildStorage.Waypoint waypoint = new GuildStorage.Waypoint(
            name,
            player.getUUID(),
            player.level().dimension().location().toString(),
            player.blockPosition().getX(),
            player.blockPosition().getY(),
            player.blockPosition().getZ()
         );
         guild.waypoints.add(waypoint);
         activity(guild, "WAYPOINT_CREATED", player, name + " " + waypoint.dimension + " " + waypoint.x + "," + waypoint.y + "," + waypoint.z);
         getStorage().save();
         success(source, "Waypoint personal creado: " + name + ". Usa /guild waypoint share " + name + " para compartirlo.");
         return 1;
      }
   }

   private static int shareWaypoint(CommandSourceStack source, String name) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildStorage.Guild guild = guildFor(source, player);
      if (guild == null) {
         return 0;
      } else if (!hasRole(guild, player, "LEADER", "OFFICER")) {
         return fail(source, "Solo lideres y oficiales pueden compartir waypoints.");
      } else {
         GuildStorage.Waypoint waypoint = guild.waypoints.stream().filter(item -> item.name.equalsIgnoreCase(name)).findFirst().orElse(null);
         if (waypoint == null) {
            return fail(source, "Waypoint no encontrado.");
         } else {
            waypoint.shared = true;
            waypoint.sharedBy = player.getGameProfile().getName();
            waypoint.sharedTimestamp = System.currentTimeMillis();
            activity(guild, "WAYPOINT_SHARED", player, waypoint.name);
            getStorage().save();
            GuildNetwork.syncAll(source.getServer());
            success(source, "Waypoint compartido con el gremio.");
            return 1;
         }
      }
   }

   private static int removeWaypoint(CommandSourceStack source, String name) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildStorage.Guild guild = guildFor(source, player);
      if (guild == null) {
         return 0;
      } else {
         GuildStorage.Waypoint waypoint = guild.waypoints.stream().filter(item -> item.name.equalsIgnoreCase(name)).findFirst().orElse(null);
         if (waypoint == null) {
            return fail(source, "Waypoint no encontrado.");
         } else {
            boolean administrator = hasRole(guild, player, "LEADER", "OFFICER");
            if (waypoint.shared && !administrator) {
               return fail(source, "Solo un lider u oficial puede eliminar waypoints compartidos.");
            } else if (!waypoint.shared && !waypoint.ownerUuid.equals(key(player))) {
               return fail(source, "Solo puedes eliminar tus propios waypoints personales.");
            } else {
               guild.waypoints.remove(waypoint);
               activity(guild, "WAYPOINT_DELETED", player, waypoint.name);
               getStorage().save();
               success(source, "Waypoint eliminado.");
               return 1;
            }
         }
      }
   }

   private static int listWaypoints(CommandSourceStack source) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildStorage.Guild guild = guildFor(source, player);
      if (guild == null) {
         return 0;
      } else {
         for (GuildStorage.Waypoint waypoint : guild.waypoints) {
            if (waypoint.shared || waypoint.ownerUuid.equals(key(player))) {
               source.sendSuccess(
                  () -> Component.literal(
                        (waypoint.shared ? "[GREMIO] " : "[PERSONAL] ")
                           + waypoint.name
                           + " - "
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
         }

         return 1;
      }
   }

   private static void removeMember(GuildStorage.Guild guild, ServerPlayer player, String event, String detail) {
      removeMember(guild, player.getUUID(), player.getGameProfile().getName(), event, detail);
   }

   private static void removeMember(GuildStorage.Guild guild, UUID playerId, String playerName, String event, String detail) {
      guild.members.remove(playerId.toString());
      getStorage().index().playerGuilds.remove(playerId.toString());
      activity(guild, event, playerId, playerName + " " + detail);
   }

   private static GuildStorage.Guild guildFor(CommandSourceStack source, ServerPlayer player) {
      String guildId = getStorage().index().playerGuilds.get(key(player));
      GuildStorage.Guild guild = guildId == null ? null : getStorage().getGuild(guildId);
      if (guild != null && !guild.disbanded) {
         return guild;
      } else {
         fail(source, "No perteneces a ningun gremio.");
         return null;
      }
   }

   private static GuildStorage.Member findMember(GuildStorage.Guild guild, String name) {
      return guild.members.values().stream().filter(member -> member.playerName.equalsIgnoreCase(name)).findFirst().orElse(null);
   }

   private static boolean hasRole(GuildStorage.Guild guild, ServerPlayer player, String... roles) {
      GuildStorage.Member member = guild.members.get(key(player));
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

   private static boolean isLeader(GuildStorage.Guild guild, ServerPlayer player) {
      return player.getUUID().toString().equals(guild.founderUuid) && hasRole(guild, player, "LEADER");
   }

   private static void activity(GuildStorage.Guild guild, String event, ServerPlayer actor, String details) {
      guild.activityLog.add(new GuildStorage.Activity(LOG_TIME.format(Instant.now()), event, actor.getUUID(), details, actor.getGameProfile().getName()));
   }

   private static void activity(GuildStorage.Guild guild, String event, UUID actor, String details) {
      GuildStorage.Member member = guild.members.get(actor.toString());
      String actorName = member == null ? "Jugador" : member.playerName;
      guild.activityLog.add(new GuildStorage.Activity(LOG_TIME.format(Instant.now()), event, actor, details, actorName));
   }

   private static String key(ServerPlayer player) {
      return player.getUUID().toString();
   }

   private static String normalizeColor(String color) {
      if (color == null) {
         return null;
      } else {
         String normalized = color.startsWith("#") ? color : "#" + color;
         return COLOR.matcher(normalized).matches() ? normalized.toUpperCase(Locale.ROOT) : null;
      }
   }

   private static GuildStorage store() {
      return getStorage();
   }

   private static GuildStorage getStorage() {
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

   private static void success(CommandSourceStack source, String message) {
      source.sendSuccess(() -> Component.literal(message), false);
   }

   private static String remaining(long millis) {
      long hours = millis / 3600000L;
      long minutes = millis % 3600000L / 60000L;
      return hours + "h " + minutes + "m";
   }

   private static int setDebugBots(CommandSourceStack source, int count) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildSynergyManager.setDebugBots(player.getUUID(), count);
      source.sendSuccess(
         () -> Component.literal("§a[DEBUG] Sinergia simulada: §e" + count + " bots extra§a. (Total en grupo: " + (count > 0 ? count + 1 : 1) + ")"), false
      );
      return 1;
   }

   private static int addNpcMember(CommandSourceStack source, String npcName) throws CommandSyntaxException {
      ServerPlayer player = source.getPlayerOrException();
      GuildStorage storage = storageSupplier.get();
      if (storage == null) {
         return 0;
      } else {
         String guildId = storage.index().playerGuilds.get(player.getUUID().toString());
         if (guildId == null) {
            source.sendFailure(Component.literal("§cDebes pertenecer a un gremio para añadir un NPC."));
            return 0;
         } else {
            GuildStorage.Guild guild = storage.getGuild(guildId);
            if (guild == null) {
               return 0;
            } else {
               UUID fakeUuid = UUID.nameUUIDFromBytes(("NPC_" + npcName).getBytes(StandardCharsets.UTF_8));
               guild.members.put(fakeUuid.toString(), new GuildStorage.Member(fakeUuid, npcName, "MEMBER", System.currentTimeMillis() / 1000L));
               storage.save();
               GuildNetwork.syncAll(player.getServer());
               source.sendSuccess(() -> Component.literal("§a[DEBUG] NPC §e" + npcName + "§a añadido al gremio exitosamente."), false);
               return 1;
            }
         }
      }
   }
}
