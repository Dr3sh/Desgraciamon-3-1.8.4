package com.desgraciamon.joinmessages.mixin;

import com.desgraciamon.joinmessages.JoinMessagesMod;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Redirect(
        method = "placeNewPlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"
        )
    )
    private void replaceJoinMessage(
        PlayerList playerList,
        Component message,
        boolean overlay,
        Connection connection,
        ServerPlayer player,
        CommonListenerCookie cookie
    ) {
        playerList.broadcastSystemMessage(JoinMessagesMod.Companion.joinMessage(player), overlay);
    }
}

