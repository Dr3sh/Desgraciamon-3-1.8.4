package com.desgraciamon.showdownarena.mixin;

import com.cobblemon.mod.common.client.battle.ClientBattleChallenge;
import com.cobblemon.mod.common.client.battle.ClientTeamRequest;
import com.cobblemon.mod.common.client.gui.interact.battleRequest.BattleConfigureGUI;
import com.cobblemon.mod.common.net.messages.client.PlayerInteractOptionsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BattleConfigureGUI.class)
public interface BattleConfigureGUIAccessor {
    @Accessor(value = "packet", remap = false)
    PlayerInteractOptionsPacket getPacket();

    @Accessor(value = "activeRequest", remap = false)
    ClientBattleChallenge getActiveRequest();

    @Accessor(value = "activeTeamRequest", remap = false)
    ClientTeamRequest getActiveTeamRequest();
}