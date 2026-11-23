package net.desgraciamon.guilds.mixin;

import com.cobblemon.mod.common.net.messages.server.storage.pc.MovePCPokemonToPartyPacket;
import com.cobblemon.mod.common.net.serverhandling.storage.pc.MovePCPokemonToPartyHandler;
import net.desgraciamon.guilds.DesgraciamonGuilds;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({MovePCPokemonToPartyHandler.class})
abstract class MovePCPokemonToPartyHandlerMixin {
   @Inject(
      method = {"handle"},
      at = {@At("TAIL")}
   )
   private void desgraciamon$recordActor(MovePCPokemonToPartyPacket packet, MinecraftServer server, ServerPlayer player, CallbackInfo callback) {
      DesgraciamonGuilds.noteGuildPCAction(player);
   }
}
