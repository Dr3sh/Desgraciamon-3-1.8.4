package net.desgraciamon.guilds.mixin;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.api.storage.pc.link.PCLinkManager;
import com.cobblemon.mod.common.net.messages.client.storage.pc.ClosePCPacket;
import com.cobblemon.mod.common.net.messages.server.storage.pc.MovePartyPokemonToPCPacket;
import com.cobblemon.mod.common.net.serverhandling.storage.pc.MovePartyPokemonToPCHandler;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.desgraciamon.guilds.DesgraciamonGuilds;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({MovePartyPokemonToPCHandler.class})
abstract class MovePartyPokemonToPCHandlerMixin {
   @Inject(
      method = {"handle(Lcom/cobblemon/mod/common/net/messages/server/storage/pc/MovePartyPokemonToPCPacket;Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerPlayer;)V"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void desgraciamon$blockLegendaryDeposit(MovePartyPokemonToPCPacket packet, MinecraftServer server, ServerPlayer player, CallbackInfo callback) {
      PCStore pc = PCLinkManager.INSTANCE.getPC(player);
      if (pc != null && DesgraciamonGuilds.isGuildPC(pc)) {
         PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
         Pokemon pokemon = party.get(packet.getPartyPosition());
         if (pokemon != null && DesgraciamonGuilds.isRestrictedPokemon(pokemon)) {
            new ClosePCPacket(pc.getUuid()).sendToPlayer(player);
            player.sendSystemMessage(Component.literal("§c[Gremio] No puedes almacenar Pokémon Legendarios ni Míticos en el PC compartido."));
            callback.cancel();
         } else if (pokemon != null) {
            pokemon.getPersistentData().putString("guild_depositor_name", player.getGameProfile().getName());
         }
      }
   }

   @Inject(
      method = {"handle(Lcom/cobblemon/mod/common/net/messages/server/storage/pc/MovePartyPokemonToPCPacket;Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerPlayer;)V"},
      at = {@At("TAIL")}
   )
   private void desgraciamon$recordActor(MovePartyPokemonToPCPacket packet, MinecraftServer server, ServerPlayer player, CallbackInfo callback) {
      DesgraciamonGuilds.noteGuildPCAction(player);
   }
}
