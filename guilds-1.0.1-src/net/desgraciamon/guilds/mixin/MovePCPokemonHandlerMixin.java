package net.desgraciamon.guilds.mixin;

import com.cobblemon.mod.common.net.messages.server.storage.pc.MovePCPokemonPacket;
import com.cobblemon.mod.common.net.serverhandling.storage.pc.MovePCPokemonHandler;
import net.desgraciamon.guilds.DesgraciamonGuilds;
import net.minecraft.class_3222;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({MovePCPokemonHandler.class})
abstract class MovePCPokemonHandlerMixin {
   @Inject(
      method = {"handle"},
      at = {@At("TAIL")}
   )
   private void desgraciamon$recordActor(MovePCPokemonPacket packet, MinecraftServer server, class_3222 player, CallbackInfo callback) {
      DesgraciamonGuilds.noteGuildPCAction(player);
   }
}
