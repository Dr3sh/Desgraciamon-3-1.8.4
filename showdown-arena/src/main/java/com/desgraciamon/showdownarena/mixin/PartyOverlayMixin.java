package com.desgraciamon.showdownarena.mixin;

import com.cobblemon.mod.common.client.gui.PartyOverlay;
import com.cobblemon.mod.common.client.storage.ClientParty;
import com.cobblemon.mod.common.client.storage.ClientStorageManager;
import com.desgraciamon.showdownarena.ShowdownBattlePartyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PartyOverlay.class)
public class PartyOverlayMixin {
    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lcom/cobblemon/mod/common/client/storage/ClientStorageManager;getParty()Lcom/cobblemon/mod/common/client/storage/ClientParty;",
            remap = false
        )
    )
    private ClientParty showdown$useShowdownPartyInOverlay(ClientStorageManager storageManager) {
        return ShowdownBattlePartyClient.getOverlayPartyOrDefault(storageManager.getParty());
    }
}

