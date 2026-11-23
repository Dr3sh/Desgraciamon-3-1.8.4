package de.maxhenkel.voicechat.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager;
import de.maxhenkel.voicechat.permission.Permission;
import de.maxhenkel.voicechat.permission.PermissionManager;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import de.maxhenkel.voicechat.voice.server.ClientConnection;
import de.maxhenkel.voicechat.voice.server.Group;
import de.maxhenkel.voicechat.voice.server.PingManager;
import de.maxhenkel.voicechat.voice.server.Server;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class VoicechatCommands {

    public static final String VOICECHAT_COMMAND = "voicechat";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> literalBuilder = Commands.literal(VOICECHAT_COMMAND);

        literalBuilder.executes(commandSource -> help(dispatcher, commandSource));
        literalBuilder.then(Commands.literal("help").executes(commandSource -> help(dispatcher, commandSource)));

        literalBuilder.then(Commands.literal("test").requires((commandSource) -> checkPermission(commandSource, PermissionManager.INSTANCE.ADMIN_PERMISSION)).then(Commands.argument("target", EntityArgument.player()).executes((commandSource) -> {
            if (checkNoVoicechat(commandSource)) {
                return 0;
            }
            ServerPlayer player = EntityArgument.getPlayer(commandSource, "target");
            Server server = Voicechat.SERVER.getServer();
            if (server == null) {
                commandSource.getSource().sendSuccess(() -> Component.translatable("message.voicechat.voice_chat_unavailable"), false);
                return 1;
            }

            if (!Voicechat.SERVER.isCompatible(player)) {
                commandSource.getSource().sendSuccess(() -> Component.translatable("message.voicechat.player_no_voicechat", player.getDisplayName(), CommonCompatibilityManager.INSTANCE.getModName()), false);
                return 1;
            }

            ClientConnection clientConnection = server.getConnections().get(player.getUUID());
            if (clientConnection == null) {
                commandSource.getSource().sendSuccess(() -> Component.translatable("message.voicechat.client_not_connected"), false);
                return 1;
            }
            try {
                commandSource.getSource().sendSuccess(() -> Component.translatable("message.voicechat.sending_ping"), false);

                server.getPingManager().sendPing(clientConnection, 500, 10, new PingManager.PingListener() {

                    @Override
                    public void onPong(int attempts, long pingMilliseconds) {
                        if (attempts <= 1) {
                            commandSource.getSource().sendSuccess(() -> Component.translatable("message.voicechat.ping_received", pingMilliseconds), false);
                        } else {
                            commandSource.getSource().sendSuccess(() -> Component.translatable("message.voicechat.ping_received_attempt", pingMilliseconds, attempts), false);
                        }
                    }

                    @Override
                    public void onFailedAttempt(int attempts) {
                        commandSource.getSource().sendSuccess(() -> Component.translatable("message.voicechat.ping_retry"), false);
                    }

                    @Override
                    public void onTimeout(int attempts) {
                        commandSource.getSource().sendSuccess(() -> Component.translatable("message.voicechat.ping_timed_out", attempts), false);
                    }
                });
                commandSource.getSource().sendSuccess(() -> Component.translatable("message.voicechat.ping_sent_waiting"), false);
            } catch (Exception e) {
                commandSource.getSource().sendSuccess(() -> Component.translatable("message.voicechat.failed_to_send_ping", e.getMessage()), false);
                Voicechat.LOGGER.warn("Failed to send ping", e);
                return 1;
            }
            return 1;
        })));

        literalBuilder.then(Commands.literal("invite").then(Commands.argument("target", EntityArgument.player()).executes((commandSource) -> {
            if (checkNoVoicechat(commandSource)) {
                return 0;
            }
            ServerPlayer source = commandSource.getSource().getPlayerOrException();

            Server server = Voicechat.SERVER.getServer();
            if (server == null) {
                commandSource.getSource().sendSuccess(() -> Component.translatable("message.voicechat.voice_chat_unavailable"), false);
                return 1;
            }

            PlayerState state = server.getPlayerStateManager().getState(source.getUUID());

            if (state == null || !state.hasGroup()) {
                commandSource.getSource().sendSuccess(() -> Component.translatable("message.voicechat.not_in_group"), false);
                return 1;
            }

            ServerPlayer player = EntityArgument.getPlayer(commandSource, "target");
            Group group = server.getGroupManager().getGroup(state.getGroup());

            if (group == null) {
                return 1;
            }

            if (!Voicechat.SERVER.isCompatible(player)) {
                commandSource.getSource().sendSuccess(() -> Component.translatable("message.voicechat.player_no_voicechat", player.getDisplayName(), CommonCompatibilityManager.INSTANCE.getModName()), false);
                return 1;
            }

            String passwordSuffix = group.getPassword() == null ? "" : String.format(" %s", StringArgumentType.escapeIfRequired(group.getPassword()));
            player.sendSystemMessage(Component.translatable("message.voicechat.invite", source.getDisplayName(), Component.literal(group.getName()).withStyle(ChatFormatting.GRAY), ComponentUtils.wrapInSquareBrackets(Component.translatable("message.voicechat.accept_invite").withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/voicechat join " + group.getId().toString() + passwordSuffix)).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("message.voicechat.accept_invite.hover"))))).withStyle(ChatFormatting.GREEN)));

            commandSource.getSource().sendSuccess(() -> Component.translatable("message.voicechat.invite_successful", player.getDisplayName()), false);

            return 1;
        })));

        literalBuilder.then(Commands.literal("join").then(Commands.argument("group_id", UuidArgument.uuid()).executes((commandSource) -> {
            if (checkNoVoicechat(commandSource)) {
                return 0;
            }
            UUID groupID = UuidArgument.getUuid(commandSource, "group_id");
            return joinGroupById(commandSource.getSource(), groupID, null);
        })));

        literalBuilder.then(Commands.literal("join").then(Commands.argument("group_id", UuidArgument.uuid()).then(Commands.argument("password", StringArgumentType.string()).executes((commandSource) -> {
            if (checkNoVoicechat(commandSource)) {
                return 0;
            }
            UUID groupID = UuidArgument.getUuid(commandSource, "group_id");
            String password = StringArgumentType.getString(commandSource, "password");
            return joinGroupById(commandSource.getSource(), groupID, password.isEmpty() ? null : password);
        }))));

        literalBuilder.then(Commands.literal("join").then(Commands.argument("group_name", StringArgumentType.string()).suggests(GroupNameSuggestionProvider.INSTANCE).executes((commandSource) -> {
            if (checkNoVoicechat(commandSource)) {
                return 0;
            }
            String groupName = StringArgumentType.getString(commandSource, "group_name");
            return joinGroupByName(commandSource.getSource(), groupName, null);
        })));

        literalBuilder.then(Commands.literal("join").then(Commands.argument("group_name", StringArgumentType.string()).suggests(GroupNameSuggestionProvider.INSTANCE).then(Commands.argument("password", StringArgumentType.string()).executes((commandSource) -> {
            if (checkNoVoicechat(commandSource)) {
                return 0;
            }
            String groupName = StringArgumentType.getString(commandSource, "group_name");
            String password = StringArgumentType.getString(commandSource, "password");
            return joinGroupByName(commandSource.getSource(), groupName, password.isEmpty() ? null : password);
        }))));

        literalBuilder.then(Commands.literal("leave").executes((commandSource) -> {
            if (checkNoVoicechat(commandSource)) {
                return 0;
            }
            if (!Voicechat.SERVER_CONFIG.groupsEnabled.get()) {
                commandSource.getSource().sendFailure(Component.translatable("message.voicechat.groups_disabled"));
                return 1;
            }

            Server server = Voicechat.SERVER.getServer();
            if (server == null) {
                commandSource.getSource().sendSuccess(() -> Component.translatable("message.voicechat.voice_chat_unavailable"), false);
                return 1;
            }
            ServerPlayer source = commandSource.getSource().getPlayerOrException();

            PlayerState state = server.getPlayerStateManager().getState(source.getUUID());
            if (state == null || !state.hasGroup()) {
                commandSource.getSource().sendSuccess(() -> Component.translatable("message.voicechat.not_in_group"), false);
                return 1;
            }

            server.getGroupManager().leaveGroup(source);
            commandSource.getSource().sendSuccess(() -> Component.translatable("message.voicechat.leave_successful"), false);
            return 1;
        }));

        literalBuilder.then(Commands.literal("reconnect").executes(commandSource -> {
            ServerPlayer player = commandSource.getSource().getPlayerOrException();
            Server server = Voicechat.SERVER.getServer();
            if (server != null) {
                server.disconnectClient(player.getUUID());
                Voicechat.SERVER.initializePlayerConnection(player);
                commandSource.getSource().sendSuccess(() -> Component.literal("§a[Dr3sh Studios Voice Chat] Reconexión iniciada."), false);
            }
            return 1;
        }));

        literalBuilder.then(Commands.literal("disconnect").executes(commandSource -> {
            ServerPlayer player = commandSource.getSource().getPlayerOrException();
            Server server = Voicechat.SERVER.getServer();
            if (server != null) {
                server.disconnectClient(player.getUUID());
                commandSource.getSource().sendSuccess(() -> Component.literal("§e[Dr3sh Studios Voice Chat] Desconectado del chat de voz."), false);
            }
            return 1;
        }));

        literalBuilder.then(Commands.literal("global").requires(s -> s.hasPermission(2)).executes(commandSource -> {
            ServerPlayer player = commandSource.getSource().getPlayerOrException();
            Server server = Voicechat.SERVER.getServer();
            if (server == null) {
                commandSource.getSource().sendFailure(Component.literal("§c[Dr3sh Studios Voice Chat] Servidor de voz no disponible."));
                return 0;
            }
            boolean active = server.toggleGlobalVoice(player.getUUID());
            if (active) {
                commandSource.getSource().sendSuccess(() -> Component.literal("§a[Dr3sh Studios Voice Chat] Modo Voz Global ACTIVADO. Ahora todos los jugadores del servidor escucharán tu voz."), false);
            } else {
                commandSource.getSource().sendSuccess(() -> Component.literal("§c[Dr3sh Studios Voice Chat] Modo Voz Global DESACTIVADO."), false);
            }
            return 1;
        }));

        // Administrative mute / unmute commands (supports players, selectors like @a[tag=...], and persistent scoreboard tags)
        literalBuilder.then(Commands.literal("silenciar").requires(s -> s.hasPermission(2)).then(Commands.argument("targets", EntityArgument.players()).executes(VoicechatCommands::mutePlayers)));
        literalBuilder.then(Commands.literal("mute").requires(s -> s.hasPermission(2)).then(Commands.argument("targets", EntityArgument.players()).executes(VoicechatCommands::mutePlayers)));
        literalBuilder.then(Commands.literal("desmutear").requires(s -> s.hasPermission(2)).then(Commands.argument("targets", EntityArgument.players()).executes(VoicechatCommands::unmutePlayers)));
        literalBuilder.then(Commands.literal("unmute").requires(s -> s.hasPermission(2)).then(Commands.argument("targets", EntityArgument.players()).executes(VoicechatCommands::unmutePlayers)));
        literalBuilder.then(Commands.literal("silenciartag").requires(s -> s.hasPermission(2)).then(Commands.argument("tag", StringArgumentType.word()).executes(VoicechatCommands::muteTag)));
        literalBuilder.then(Commands.literal("mutetag").requires(s -> s.hasPermission(2)).then(Commands.argument("tag", StringArgumentType.word()).executes(VoicechatCommands::muteTag)));
        literalBuilder.then(Commands.literal("desmuteartag").requires(s -> s.hasPermission(2)).then(Commands.argument("tag", StringArgumentType.word()).executes(VoicechatCommands::unmuteTag)));
        literalBuilder.then(Commands.literal("unmutetag").requires(s -> s.hasPermission(2)).then(Commands.argument("tag", StringArgumentType.word()).executes(VoicechatCommands::unmuteTag)));
        literalBuilder.then(Commands.literal("silenciados").requires(s -> s.hasPermission(2)).executes(VoicechatCommands::listMuted));

        dispatcher.register(literalBuilder);

        // Command /voicechatglobal (admin)
        dispatcher.register(Commands.literal("voicechatglobal").requires(s -> s.hasPermission(2)).executes(commandSource -> {
            ServerPlayer player = commandSource.getSource().getPlayerOrException();
            Server server = Voicechat.SERVER.getServer();
            if (server == null) {
                commandSource.getSource().sendFailure(Component.literal("§c[Dr3sh Studios Voice Chat] Servidor de voz no disponible."));
                return 0;
            }
            boolean active = server.toggleGlobalVoice(player.getUUID());
            if (active) {
                commandSource.getSource().sendSuccess(() -> Component.literal("§a[Dr3sh Studios Voice Chat] Modo Voz Global ACTIVADO. Ahora todos los jugadores del servidor escucharán tu voz."), false);
            } else {
                commandSource.getSource().sendSuccess(() -> Component.literal("§c[Dr3sh Studios Voice Chat] Modo Voz Global DESACTIVADO."), false);
            }
            return 1;
        }));

        dispatcher.register(Commands.literal("vozglobal").requires(s -> s.hasPermission(2)).redirect(dispatcher.getRoot().getChild("voicechatglobal")));

        // Spanish aliases: /voz reconectar, /voz desconectar, /voz silenciar, /voz desmutear
        LiteralArgumentBuilder<CommandSourceStack> vozBuilder = Commands.literal("voz")
            .then(Commands.literal("reconectar").executes(commandSource -> {
                ServerPlayer player = commandSource.getSource().getPlayerOrException();
                Server server = Voicechat.SERVER.getServer();
                if (server != null) {
                    server.disconnectClient(player.getUUID());
                    Voicechat.SERVER.initializePlayerConnection(player);
                    commandSource.getSource().sendSuccess(() -> Component.literal("§a[Dr3sh Studios Voice Chat] Reconexión iniciada."), false);
                }
                return 1;
            }))
            .then(Commands.literal("desconectar").executes(commandSource -> {
                ServerPlayer player = commandSource.getSource().getPlayerOrException();
                Server server = Voicechat.SERVER.getServer();
                if (server != null) {
                    server.disconnectClient(player.getUUID());
                    commandSource.getSource().sendSuccess(() -> Component.literal("§e[Dr3sh Studios Voice Chat] Desconectado del chat de voz."), false);
                }
                return 1;
            }))
            .then(Commands.literal("global").requires(s -> s.hasPermission(2)).redirect(dispatcher.getRoot().getChild("voicechatglobal")))
            .then(Commands.literal("silenciar").requires(s -> s.hasPermission(2)).then(Commands.argument("targets", EntityArgument.players()).executes(VoicechatCommands::mutePlayers)))
            .then(Commands.literal("mute").requires(s -> s.hasPermission(2)).then(Commands.argument("targets", EntityArgument.players()).executes(VoicechatCommands::mutePlayers)))
            .then(Commands.literal("desmutear").requires(s -> s.hasPermission(2)).then(Commands.argument("targets", EntityArgument.players()).executes(VoicechatCommands::unmutePlayers)))
            .then(Commands.literal("unmute").requires(s -> s.hasPermission(2)).then(Commands.argument("targets", EntityArgument.players()).executes(VoicechatCommands::unmutePlayers)))
            .then(Commands.literal("silenciartag").requires(s -> s.hasPermission(2)).then(Commands.argument("tag", StringArgumentType.word()).executes(VoicechatCommands::muteTag)))
            .then(Commands.literal("mutetag").requires(s -> s.hasPermission(2)).then(Commands.argument("tag", StringArgumentType.word()).executes(VoicechatCommands::muteTag)))
            .then(Commands.literal("desmuteartag").requires(s -> s.hasPermission(2)).then(Commands.argument("tag", StringArgumentType.word()).executes(VoicechatCommands::unmuteTag)))
            .then(Commands.literal("unmutetag").requires(s -> s.hasPermission(2)).then(Commands.argument("tag", StringArgumentType.word()).executes(VoicechatCommands::unmuteTag)))
            .then(Commands.literal("silenciados").requires(s -> s.hasPermission(2)).executes(VoicechatCommands::listMuted));

        dispatcher.register(vozBuilder);
        dispatcher.register(Commands.literal("dr3shvoice").redirect(dispatcher.getRoot().getChild(VOICECHAT_COMMAND)));
    }

    private static Server joinGroup(CommandSourceStack source) throws CommandSyntaxException {
        if (!Voicechat.SERVER_CONFIG.groupsEnabled.get()) {
            source.sendFailure(Component.translatable("message.voicechat.groups_disabled"));
            return null;
        }

        Server server = Voicechat.SERVER.getServer();
        if (server == null) {
            source.sendSuccess(() -> Component.translatable("message.voicechat.voice_chat_unavailable"), false);
            return null;
        }
        ServerPlayer player = source.getPlayerOrException();

        if (!PermissionManager.INSTANCE.GROUPS_PERMISSION.hasPermission(player)) {
            source.sendSuccess(() -> Component.translatable("message.voicechat.no_group_permission"), false);
            return null;
        }

        return server;
    }

    private static int joinGroupByName(CommandSourceStack source, String groupName, @Nullable String password) throws CommandSyntaxException {
        Server server = joinGroup(source);
        if (server == null) {
            return 1;
        }

        List<Group> groups = server.getGroupManager().getGroups().values().stream().filter(group -> group.getName().equals(groupName)).collect(Collectors.toList());

        if (groups.isEmpty()) {
            source.sendFailure(Component.translatable("message.voicechat.group_does_not_exist"));
            return 1;
        }

        if (groups.size() > 1) {
            source.sendFailure(Component.translatable("message.voicechat.group_name_not_unique"));
            return 1;
        }

        return joinGroup(source, server, groups.get(0).getId(), password);
    }

    private static int joinGroupById(CommandSourceStack source, UUID groupID, @Nullable String password) throws CommandSyntaxException {
        Server server = joinGroup(source);
        if (server == null) {
            return 1;
        }
        return joinGroup(source, server, groupID, password);
    }

    private static int joinGroup(CommandSourceStack source, Server server, UUID groupID, @Nullable String password) throws CommandSyntaxException {
        Group group = server.getGroupManager().getGroup(groupID);

        if (group == null) {
            source.sendFailure(Component.translatable("message.voicechat.group_does_not_exist"));
            return 1;
        }

        server.getGroupManager().joinGroup(group, source.getPlayerOrException(), password);
        source.sendSuccess(() -> Component.translatable("message.voicechat.join_successful", Component.literal(group.getName()).withStyle(ChatFormatting.GRAY)), false);
        return 1;
    }

    private static int help(CommandDispatcher<CommandSourceStack> dispatcher, CommandContext<CommandSourceStack> commandSource) {
        if (checkNoVoicechat(commandSource)) {
            return 0;
        }
        CommandNode<CommandSourceStack> voicechatCommand = dispatcher.getRoot().getChild(VOICECHAT_COMMAND);
        Map<CommandNode<CommandSourceStack>, String> map = dispatcher.getSmartUsage(voicechatCommand, commandSource.getSource());
        for (Map.Entry<CommandNode<CommandSourceStack>, String> entry : map.entrySet()) {
            commandSource.getSource().sendSuccess(() -> Component.literal("/%s %s".formatted(VOICECHAT_COMMAND, entry.getValue())), false);
        }
        return map.size();
    }

    private static boolean checkNoVoicechat(CommandContext<CommandSourceStack> commandSource) {
        try {
            ServerPlayer player = commandSource.getSource().getPlayerOrException();
            if (Voicechat.SERVER.isCompatible(player)) {
                return false;
            }
            commandSource.getSource().sendFailure(Component.literal(Voicechat.TRANSLATIONS.voicechatNeededForCommandMessage.get().formatted(CommonCompatibilityManager.INSTANCE.getModName())));
            return true;
        } catch (Exception e) {
            commandSource.getSource().sendFailure(Component.literal(Voicechat.TRANSLATIONS.playerCommandMessage.get()));
            return true;
        }
    }

    private static boolean checkPermission(CommandSourceStack stack, Permission permission) {
        try {
            return permission.hasPermission(stack.getPlayerOrException());
        } catch (CommandSyntaxException e) {
            return stack.hasPermission(stack.getServer().getOperatorUserPermissionLevel());
        }
    }

    private static int mutePlayers(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Server server = Voicechat.SERVER.getServer();
        if (server == null) {
            context.getSource().sendFailure(Component.literal("§c[Chat de Voz] Servidor de voz no disponible."));
            return 0;
        }
        java.util.Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "targets");
        int count = 0;
        for (ServerPlayer player : players) {
            server.mutePlayer(player.getUUID());
            player.displayClientMessage(Component.literal("§c[Chat de Voz] Has sido silenciado administrativamente por un moderador."), false);
            count++;
        }
        int finalCount = count;
        context.getSource().sendSuccess(() -> Component.literal("§a[Chat de Voz] " + finalCount + " jugador(es) silenciado(s) administrativamente."), true);
        return finalCount;
    }

    private static int unmutePlayers(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Server server = Voicechat.SERVER.getServer();
        if (server == null) {
            context.getSource().sendFailure(Component.literal("§c[Chat de Voz] Servidor de voz no disponible."));
            return 0;
        }
        java.util.Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "targets");
        int count = 0;
        for (ServerPlayer player : players) {
            server.unmutePlayer(player.getUUID());
            player.displayClientMessage(Component.literal("§a[Chat de Voz] Has sido desmuteado administrativamente del chat de voz."), false);
            count++;
        }
        int finalCount = count;
        context.getSource().sendSuccess(() -> Component.literal("§a[Chat de Voz] " + finalCount + " jugador(es) desmuteado(s) administrativamente."), true);
        return finalCount;
    }

    private static int muteTag(CommandContext<CommandSourceStack> context) {
        Server server = Voicechat.SERVER.getServer();
        if (server == null) {
            context.getSource().sendFailure(Component.literal("§c[Chat de Voz] Servidor de voz no disponible."));
            return 0;
        }
        String tag = StringArgumentType.getString(context, "tag");
        server.muteTag(tag);
        int affected = 0;
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            if (player.getTags().contains(tag)) {
                player.displayClientMessage(Component.literal("§c[Chat de Voz] El tag '" + tag + "' ha sido silenciado administrativamente por un moderador."), false);
                affected++;
            }
        }
        int finalAffected = affected;
        context.getSource().sendSuccess(() -> Component.literal("§a[Chat de Voz] Tag §e'" + tag + "' §asilenciado administrativamente (" + finalAffected + " jugador(es) conectado(s) afectado(s))."), true);
        return 1;
    }

    private static int unmuteTag(CommandContext<CommandSourceStack> context) {
        Server server = Voicechat.SERVER.getServer();
        if (server == null) {
            context.getSource().sendFailure(Component.literal("§c[Chat de Voz] Servidor de voz no disponible."));
            return 0;
        }
        String tag = StringArgumentType.getString(context, "tag");
        boolean removed = server.unmuteTag(tag);
        if (!removed) {
            context.getSource().sendFailure(Component.literal("§e[Chat de Voz] El tag '" + tag + "' no estaba silenciado."));
            return 0;
        }
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            if (player.getTags().contains(tag)) {
                player.displayClientMessage(Component.literal("§a[Chat de Voz] El tag '" + tag + "' ha sido desmuteado del chat de voz."), false);
            }
        }
        context.getSource().sendSuccess(() -> Component.literal("§a[Chat de Voz] Tag §e'" + tag + "' §adesmuteado administrativamente."), true);
        return 1;
    }

    private static int listMuted(CommandContext<CommandSourceStack> context) {
        Server server = Voicechat.SERVER.getServer();
        if (server == null) {
            context.getSource().sendFailure(Component.literal("§c[Chat de Voz] Servidor de voz no disponible."));
            return 0;
        }
        java.util.Set<UUID> muted = server.getAdministrativelyMuted();
        java.util.Set<String> mutedTags = server.getAdministrativelyMutedTags();
        if (muted.isEmpty() && mutedTags.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("§a[Chat de Voz] No hay ningún jugador ni tag silenciado administrativamente."), false);
            return 1;
        }
        if (!muted.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("§6[Chat de Voz] Jugadores silenciados administrativamente (" + muted.size() + "):"), false);
            for (UUID id : muted) {
                ServerPlayer p = context.getSource().getServer().getPlayerList().getPlayer(id);
                String name = p != null ? p.getScoreboardName() : id.toString();
                context.getSource().sendSuccess(() -> Component.literal(" - §e" + name), false);
            }
        }
        if (!mutedTags.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("§6[Chat de Voz] Tags /tag silenciados administrativamente (" + mutedTags.size() + "):"), false);
            for (String tag : mutedTags) {
                context.getSource().sendSuccess(() -> Component.literal(" - §e#" + tag), false);
            }
        }
        return muted.size() + mutedTags.size();
    }

}
