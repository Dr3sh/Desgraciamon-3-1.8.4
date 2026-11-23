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
import net.minecraft.class_2168;
import net.minecraft.class_2170;
import net.minecraft.class_2561;
import net.minecraft.class_3222;

final class GuildCommands {
   private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
   private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy - HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());
   private static Supplier<GuildStorage> storageSupplier;
   private static Supplier<GuildPCManager> pcManagerSupplier;

   private GuildCommands() {
   }

   static void register(CommandDispatcher<class_2168> dispatcher, Supplier<GuildStorage> storage) {
      storageSupplier = storage;
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)class_2170.method_9247(
                                                                        "guild"
                                                                     )
                                                                     .then(
                                                                        ((LiteralArgumentBuilder)class_2170.method_9247("pc")
                                                                              .executes(context -> openPC((class_2168)context.getSource(), 0)))
                                                                           .then(
                                                                              class_2170.method_9244("box", IntegerArgumentType.integer(1, 30))
                                                                                 .executes(
                                                                                    context -> openPC(
                                                                                          (class_2168)context.getSource(),
                                                                                          IntegerArgumentType.getInteger(context, "box") - 1
                                                                                       )
                                                                                 )
                                                                           )
                                                                     ))
                                                                  .then(
                                                                     class_2170.method_9247("create")
                                                                        .then(
                                                                           class_2170.method_9244("nameAndColor", StringArgumentType.greedyString())
                                                                              .executes(
                                                                                 context -> createFromRaw(
                                                                                       (class_2168)context.getSource(),
                                                                                       StringArgumentType.getString(context, "nameAndColor")
                                                                                    )
                                                                              )
                                                                        )
                                                                  ))
                                                               .then(
                                                                  class_2170.method_9247("invite")
                                                                     .then(
                                                                        class_2170.method_9244("player", StringArgumentType.word())
                                                                           .executes(
                                                                              context -> invite(
                                                                                    (class_2168)context.getSource(),
                                                                                    StringArgumentType.getString(context, "player")
                                                                                 )
                                                                           )
                                                                     )
                                                               ))
                                                            .then(class_2170.method_9247("accept").executes(context -> accept((class_2168)context.getSource()))))
                                                         .then(class_2170.method_9247("decline").executes(context -> decline((class_2168)context.getSource()))))
                                                      .then(
                                                         ((LiteralArgumentBuilder)class_2170.method_9247("leave")
                                                               .executes(context -> leave((class_2168)context.getSource(), "")))
                                                            .then(
                                                               class_2170.method_9244("successor", StringArgumentType.word())
                                                                  .executes(
                                                                     context -> leave(
                                                                           (class_2168)context.getSource(), StringArgumentType.getString(context, "successor")
                                                                        )
                                                                  )
                                                            )
                                                      ))
                                                   .then(
                                                      class_2170.method_9247("kick")
                                                         .then(
                                                            class_2170.method_9244("player", StringArgumentType.word())
                                                               .executes(
                                                                  context -> kick(
                                                                        (class_2168)context.getSource(), StringArgumentType.getString(context, "player")
                                                                     )
                                                               )
                                                         )
                                                   ))
                                                .then(
                                                   class_2170.method_9247("promote")
                                                      .then(
                                                         class_2170.method_9244("player", StringArgumentType.word())
                                                            .executes(
                                                               context -> setRole(
                                                                     (class_2168)context.getSource(),
                                                                     StringArgumentType.getString(context, "player"),
                                                                     "OFFICER"
                                                                  )
                                                            )
                                                      )
                                                ))
                                             .then(
                                                class_2170.method_9247("demote")
                                                   .then(
                                                      class_2170.method_9244("player", StringArgumentType.word())
                                                         .executes(
                                                            context -> setRole(
                                                                  (class_2168)context.getSource(), StringArgumentType.getString(context, "player"), "MEMBER"
                                                               )
                                                         )
                                                   )
                                             ))
                                          .then(
                                             class_2170.method_9247("rename")
                                                .then(
                                                   class_2170.method_9244("name", StringArgumentType.greedyString())
                                                      .executes(
                                                         context -> rename((class_2168)context.getSource(), StringArgumentType.getString(context, "name"))
                                                      )
                                                )
                                          ))
                                       .then(
                                          class_2170.method_9247("color")
                                             .then(
                                                class_2170.method_9244("hex", StringArgumentType.greedyString())
                                                   .executes(context -> color((class_2168)context.getSource(), StringArgumentType.getString(context, "hex")))
                                             )
                                       ))
                                    .then(class_2170.method_9247("disband").executes(context -> disband((class_2168)context.getSource()))))
                                 .then(class_2170.method_9247("info").executes(context -> info((class_2168)context.getSource()))))
                              .then(class_2170.method_9247("log").executes(context -> log((class_2168)context.getSource()))))
                           .then(
                              class_2170.method_9247("deposit")
                                 .then(
                                    class_2170.method_9244("amount", StringArgumentType.word())
                                       .executes(context -> transfer((class_2168)context.getSource(), StringArgumentType.getString(context, "amount"), true))
                                 )
                           ))
                        .then(
                           class_2170.method_9247("withdraw")
                              .then(
                                 class_2170.method_9244("amount", StringArgumentType.word())
                                    .executes(context -> transfer((class_2168)context.getSource(), StringArgumentType.getString(context, "amount"), false))
                              )
                        ))
                     .then(
                        ((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)class_2170.method_9247("waypoint")
                                    .then(
                                       class_2170.method_9247("add")
                                          .then(
                                             class_2170.method_9244("name", StringArgumentType.word())
                                                .executes(
                                                   context -> addWaypoint((class_2168)context.getSource(), StringArgumentType.getString(context, "name"))
                                                )
                                          )
                                    ))
                                 .then(
                                    class_2170.method_9247("share")
                                       .then(
                                          class_2170.method_9244("name", StringArgumentType.word())
                                             .executes(context -> shareWaypoint((class_2168)context.getSource(), StringArgumentType.getString(context, "name")))
                                       )
                                 ))
                              .then(
                                 class_2170.method_9247("remove")
                                    .then(
                                       class_2170.method_9244("name", StringArgumentType.word())
                                          .executes(context -> removeWaypoint((class_2168)context.getSource(), StringArgumentType.getString(context, "name")))
                                    )
                              ))
                           .then(class_2170.method_9247("list").executes(context -> listWaypoints((class_2168)context.getSource())))
                     ))
                  .then(
                     class_2170.method_9247("chat")
                        .then(
                           class_2170.method_9244("message", StringArgumentType.greedyString())
                              .executes(context -> chat((class_2168)context.getSource(), StringArgumentType.getString(context, "message")))
                        )
                  ))
               .then(
                  ((LiteralArgumentBuilder)((LiteralArgumentBuilder)class_2170.method_9247("debug")
                           .then(
                              class_2170.method_9247("bot")
                                 .then(
                                    class_2170.method_9244("cantidad", IntegerArgumentType.integer(0, 10))
                                       .executes(context -> setDebugBots((class_2168)context.getSource(), IntegerArgumentType.getInteger(context, "cantidad")))
                                 )
                           ))
                        .then(
                           class_2170.method_9247("addnpc")
                              .then(
                                 class_2170.method_9244("nombre", StringArgumentType.word())
                                    .executes(context -> addNpcMember((class_2168)context.getSource(), StringArgumentType.getString(context, "nombre")))
                              )
                        ))
                     .then(class_2170.method_9247("clear").executes(context -> setDebugBots((class_2168)context.getSource(), 0)))
               ))
            .then(
               ((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)class_2170.method_9247("testbattle")
                           .executes(context -> RadicalTrainerBattleManager.startRandomTrainerBattle(((class_2168)context.getSource()).method_9207())))
                        .then(
                           class_2170.method_9247("lvl1")
                              .executes(context -> RadicalTrainerBattleManager.startLevel1TestBattle(((class_2168)context.getSource()).method_9207()))
                        ))
                     .then(
                        class_2170.method_9247("dummy")
                           .executes(context -> RadicalTrainerBattleManager.startLevel1TestBattle(((class_2168)context.getSource()).method_9207()))
                     ))
                  .then(
                     class_2170.method_9247("random")
                        .executes(context -> RadicalTrainerBattleManager.startRandomTrainerBattle(((class_2168)context.getSource()).method_9207()))
                  )
            )
      );
      dispatcher.register(
         (LiteralArgumentBuilder)class_2170.method_9247("gc")
            .then(
               class_2170.method_9244("message", StringArgumentType.greedyString())
                  .executes(context -> chat((class_2168)context.getSource(), StringArgumentType.getString(context, "message")))
            )
      );
   }

   static void setPcManagerSupplier(Supplier<GuildPCManager> supplier) {
      pcManagerSupplier = supplier;
   }

   private static int chat(class_2168 source, String message) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
      GuildStorage storage = storageSupplier.get();
      String id = storage.index().playerGuilds.get(player.method_5667().toString());
      if (id == null) {
         source.method_9213(class_2561.method_43470("No perteneces a ningún gremio."));
         return 0;
      } else {
         GuildStorage.Guild guild = storage.getGuild(id);
         if (guild != null && !guild.disbanded) {
            GuildNetwork.sendGuildChatMessage(player.method_5682(), player, guild, message);
            storage.save();
            GuildNetwork.syncAll(player.method_5682());
            return 1;
         } else {
            source.method_9213(class_2561.method_43470("Tu gremio ya no existe."));
            return 0;
         }
      }
   }

   private static int openPC(class_2168 source, int boxIndex) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
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

   private static int createFromRaw(class_2168 source, String raw) throws CommandSyntaxException {
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

   private static int create(class_2168 source, String name, String color) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
      GuildStorage store = getStorage();
      if (store.index().playerGuilds.containsKey(key(player))) {
         return fail(source, "Ya perteneces a un gremio.");
      } else if (store.isCoolingDown(player.method_5667())) {
         return fail(source, "Cooldown activo: " + remaining(store.cooldownRemaining(player.method_5667())) + ".");
      } else {
         name = normalizeGuildName(name);
         if (name == null) {
            return fail(source, "El nombre debe tener entre 2 y 24 caracteres (letras, numeros, espacios o guiones bajos).");
         } else {
            color = normalizeColor(color);
            if (color == null) {
               return fail(source, "Color invalido. Usa seis digitos HEX, con # opcional.");
            } else {
               GuildStorage.Guild guild = new GuildStorage.Guild(name, color.toUpperCase(Locale.ROOT), player.method_5667());
               guild.members.get(key(player)).playerName = player.method_7334().getName();
               store.addGuild(guild);
               store.index().playerGuilds.put(key(player), guild.guildId);
               activity(guild, "CREATED", player, "Gremio creado");
               store.save();
               GuildNetwork.syncAll(source.method_9211());
               success(source, "Gremio " + name + " creado.");
               return 1;
            }
         }
      }
   }

   private static int invite(class_2168 source, String targetName) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
      GuildStorage.Guild guild = guildFor(source, player);
      if (guild == null) {
         return 0;
      } else if (!hasRole(guild, player, "LEADER", "OFFICER")) {
         return fail(source, "Solo lideres y oficiales pueden invitar.");
      } else {
         class_3222 target = source.method_9211().method_3760().method_14566(targetName);
         if (target == null) {
            return fail(source, "El jugador debe estar conectado para recibir la invitacion.");
         } else if (store().index().playerGuilds.containsKey(key(target))) {
            return fail(source, "Ese jugador ya pertenece a un gremio.");
         } else if (store().isCoolingDown(target.method_5667())) {
            return fail(source, "Ese jugador tiene un cooldown activo.");
         } else {
            store().index().invitations.put(key(target), guild.guildId);
            activity(guild, "INVITED", player, target.method_7334().getName());
            store().save();
            target.method_43496(
               class_2561.method_43470(
                  "§a§l[GREMIO] §fHas recibido una invitación para unirte a §b"
                     + guild.name
                     + "§f. Abre el menú del gremio para verla o usa §e/guild accept§f."
               )
            );
            GuildNetwork.sync(target, source.method_9211(), store());
            success(source, "Invitacion enviada a " + targetName + ".");
            return 1;
         }
      }
   }

   private static int accept(class_2168 source) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
      GuildStorage current = getStorage();
      String guildId = current.index().invitations.get(key(player));
      GuildStorage.Guild guild = guildId == null ? null : current.getGuild(guildId);
      if (guild != null && !guild.disbanded) {
         if (current.index().playerGuilds.containsKey(key(player))) {
            return fail(source, "Ya perteneces a un gremio.");
         } else if (current.isCoolingDown(player.method_5667())) {
            return fail(source, "Cooldown activo: " + remaining(current.cooldownRemaining(player.method_5667())) + ".");
         } else {
            guild.members
               .put(key(player), new GuildStorage.Member(player.method_5667(), player.method_7334().getName(), "MEMBER", System.currentTimeMillis() / 1000L));
            current.index().playerGuilds.put(key(player), guild.guildId);
            current.index().invitations.remove(key(player));
            activity(guild, "JOINED", player, player.method_7334().getName());
            current.save();
            GuildNetwork.syncAll(source.method_9211());
            success(source, "Te uniste a " + guild.name + ".");
            return 1;
         }
      } else {
         return fail(source, "No tienes una invitacion pendiente.");
      }
   }

   private static int decline(class_2168 source) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
      GuildStorage current = getStorage();
      String guildId = current.index().invitations.remove(key(player));
      if (guildId == null) {
         return fail(source, "No tienes ninguna invitación pendiente.");
      } else {
         current.save();
         GuildNetwork.sync(player, source.method_9211(), current);
         success(source, "Has rechazado la invitación.");
         return 1;
      }
   }

   private static int leave(class_2168 source, String successorName) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
      GuildStorage.Guild guild = guildFor(source, player);
      if (guild == null) {
         return 0;
      } else {
         if (isLeader(guild, player)) {
            if (successorName.isBlank()) {
               return fail(source, "Debes elegir un miembro que herede el liderazgo antes de salir.");
            }

            GuildStorage.Member successor = findMember(guild, successorName);
            if (successor == null || successor.playerUuid.equals(player.method_5667().toString())) {
               return fail(source, "El sucesor debe ser otro miembro de tu gremio.");
            }

            String previousLeader = player.method_7334().getName();
            guild.members.get(key(player)).role = "OFFICER";
            successor.role = "LEADER";
            guild.founderUuid = successor.playerUuid;
            activity(guild, "LEADERSHIP_TRANSFERRED", player, previousLeader + " -> " + successor.playerName);
         } else if (!successorName.isBlank()) {
            return fail(source, "Solo el lider puede transferir el liderazgo al salir.");
         }

         removeMember(guild, player, "LEFT", "salio del gremio");
         getStorage().applyCooldown(player.method_5667());
         getStorage().save();
         success(source, "Has salido del gremio. Cooldown aplicado.");
         return 1;
      }
   }

   private static int kick(class_2168 source, String targetName) throws CommandSyntaxException {
      class_3222 actor = source.method_9207();
      GuildStorage.Guild guild = guildFor(source, actor);
      if (guild == null) {
         return 0;
      } else if (!isLeader(guild, actor)) {
         return fail(source, "Solo el lider puede expulsar miembros.");
      } else {
         class_3222 target = source.method_9211().method_3760().method_14566(targetName);
         GuildStorage.Member member = findMember(guild, targetName);
         if (member != null && !member.playerUuid.equals(guild.founderUuid)) {
            UUID targetUuid = UUID.fromString(member.playerUuid);
            removeMember(guild, targetUuid, targetName, "KICKED", "expulsado del gremio");
            getStorage().applyCooldown(targetUuid);
            getStorage().save();
            if (target != null) {
               target.method_43496(class_2561.method_43470("Has sido expulsado del gremio " + guild.name + "."));
            }

            success(source, targetName + " fue expulsado.");
            return 1;
         } else {
            return fail(source, "No se encontro un miembro expulsable con ese nombre.");
         }
      }
   }

   private static int setRole(class_2168 source, String targetName, String role) throws CommandSyntaxException {
      class_3222 actor = source.method_9207();
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

   private static int rename(class_2168 source, String name) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
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
            GuildNetwork.syncAll(source.method_9211());
            success(source, "Gremio renombrado a " + name + ".");
            return 1;
         }
      }
   }

   private static int color(class_2168 source, String hex) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
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
            GuildNetwork.syncAll(source.method_9211());
            success(source, "Color del gremio actualizado.");
            return 1;
         }
      }
   }

   private static int disband(class_2168 source) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
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

   private static int info(class_2168 source) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
      GuildStorage.Guild guild = guildFor(source, player);
      if (guild == null) {
         return 0;
      } else {
         String role = guild.members.get(key(player)).role;
         source.method_9226(
            () -> class_2561.method_43470(
                  guild.name + " " + guild.colorHex + " | " + role + " | miembros: " + guild.members.size() + " | banco: " + guild.bankBalance
               ),
            false
         );

         for (GuildStorage.Member member : guild.members.values()) {
            source.method_9226(() -> class_2561.method_43470(member.playerName + " - " + member.role), false);
         }

         return 1;
      }
   }

   private static int log(class_2168 source) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
      GuildStorage.Guild guild = guildFor(source, player);
      if (guild == null) {
         return 0;
      } else {
         int start = Math.max(0, guild.activityLog.size() - 20);

         for (int i = start; i < guild.activityLog.size(); i++) {
            GuildStorage.Activity entry = guild.activityLog.get(i);
            source.method_9226(() -> class_2561.method_43470("[" + entry.timestamp + "] " + entry.event + " " + entry.details), false);
         }

         if (guild.activityLog.isEmpty()) {
            source.method_9226(() -> class_2561.method_43470("El historial esta vacio."), false);
         }

         return 1;
      }
   }

   private static int transfer(class_2168 source, String amountText, boolean deposit) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
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
                  player.method_5667(),
                  amount.toString(),
                  player.method_7334().getName()
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

   private static int addWaypoint(class_2168 source, String name) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
      GuildStorage.Guild guild = guildFor(source, player);
      if (guild == null) {
         return 0;
      } else if (name.length() > 32) {
         return fail(source, "El nombre del waypoint no puede superar 32 caracteres.");
      } else {
         GuildStorage.Waypoint waypoint = new GuildStorage.Waypoint(
            name,
            player.method_5667(),
            player.method_37908().method_27983().method_29177().toString(),
            player.method_24515().method_10263(),
            player.method_24515().method_10264(),
            player.method_24515().method_10260()
         );
         guild.waypoints.add(waypoint);
         activity(guild, "WAYPOINT_CREATED", player, name + " " + waypoint.dimension + " " + waypoint.x + "," + waypoint.y + "," + waypoint.z);
         getStorage().save();
         success(source, "Waypoint personal creado: " + name + ". Usa /guild waypoint share " + name + " para compartirlo.");
         return 1;
      }
   }

   private static int shareWaypoint(class_2168 source, String name) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
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
            waypoint.sharedBy = player.method_7334().getName();
            waypoint.sharedTimestamp = System.currentTimeMillis();
            activity(guild, "WAYPOINT_SHARED", player, waypoint.name);
            getStorage().save();
            GuildNetwork.syncAll(source.method_9211());
            success(source, "Waypoint compartido con el gremio.");
            return 1;
         }
      }
   }

   private static int removeWaypoint(class_2168 source, String name) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
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

   private static int listWaypoints(class_2168 source) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
      GuildStorage.Guild guild = guildFor(source, player);
      if (guild == null) {
         return 0;
      } else {
         for (GuildStorage.Waypoint waypoint : guild.waypoints) {
            if (waypoint.shared || waypoint.ownerUuid.equals(key(player))) {
               source.method_9226(
                  () -> class_2561.method_43470(
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

   private static void removeMember(GuildStorage.Guild guild, class_3222 player, String event, String detail) {
      removeMember(guild, player.method_5667(), player.method_7334().getName(), event, detail);
   }

   private static void removeMember(GuildStorage.Guild guild, UUID playerId, String playerName, String event, String detail) {
      guild.members.remove(playerId.toString());
      getStorage().index().playerGuilds.remove(playerId.toString());
      activity(guild, event, playerId, playerName + " " + detail);
   }

   private static GuildStorage.Guild guildFor(class_2168 source, class_3222 player) {
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

   private static boolean hasRole(GuildStorage.Guild guild, class_3222 player, String... roles) {
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

   private static boolean isLeader(GuildStorage.Guild guild, class_3222 player) {
      return player.method_5667().toString().equals(guild.founderUuid) && hasRole(guild, player, "LEADER");
   }

   private static void activity(GuildStorage.Guild guild, String event, class_3222 actor, String details) {
      guild.activityLog.add(new GuildStorage.Activity(LOG_TIME.format(Instant.now()), event, actor.method_5667(), details, actor.method_7334().getName()));
   }

   private static void activity(GuildStorage.Guild guild, String event, UUID actor, String details) {
      GuildStorage.Member member = guild.members.get(actor.toString());
      String actorName = member == null ? "Jugador" : member.playerName;
      guild.activityLog.add(new GuildStorage.Activity(LOG_TIME.format(Instant.now()), event, actor, details, actorName));
   }

   private static String key(class_3222 player) {
      return player.method_5667().toString();
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

   private static int fail(class_2168 source, String message) {
      source.method_9213(class_2561.method_43470(message));
      return 0;
   }

   private static void success(class_2168 source, String message) {
      source.method_9226(() -> class_2561.method_43470(message), false);
   }

   private static String remaining(long millis) {
      long hours = millis / 3600000L;
      long minutes = millis % 3600000L / 60000L;
      return hours + "h " + minutes + "m";
   }

   private static int setDebugBots(class_2168 source, int count) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
      GuildSynergyManager.setDebugBots(player.method_5667(), count);
      source.method_9226(
         () -> class_2561.method_43470("§a[DEBUG] Sinergia simulada: §e" + count + " bots extra§a. (Total en grupo: " + (count > 0 ? count + 1 : 1) + ")"),
         false
      );
      return 1;
   }

   private static int addNpcMember(class_2168 source, String npcName) throws CommandSyntaxException {
      class_3222 player = source.method_9207();
      GuildStorage storage = storageSupplier.get();
      if (storage == null) {
         return 0;
      } else {
         String guildId = storage.index().playerGuilds.get(player.method_5667().toString());
         if (guildId == null) {
            source.method_9213(class_2561.method_43470("§cDebes pertenecer a un gremio para añadir un NPC."));
            return 0;
         } else {
            GuildStorage.Guild guild = storage.getGuild(guildId);
            if (guild == null) {
               return 0;
            } else {
               UUID fakeUuid = UUID.nameUUIDFromBytes(("NPC_" + npcName).getBytes(StandardCharsets.UTF_8));
               guild.members.put(fakeUuid.toString(), new GuildStorage.Member(fakeUuid, npcName, "MEMBER", System.currentTimeMillis() / 1000L));
               storage.save();
               GuildNetwork.syncAll(player.method_5682());
               source.method_9226(() -> class_2561.method_43470("§a[DEBUG] NPC §e" + npcName + "§a añadido al gremio exitosamente."), false);
               return 1;
            }
         }
      }
   }
}
