package com.desgraciamon.mirror.mixin;

import com.desgraciamon.mirror.MirrorRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Filtro de entidades del Mundo Espejo: solo pueden aparecer jugadores, los Pokémon-recuerdo
 * del mod y los Pokémon con dueño (los que el jugador saca de su equipo). Todo lo demás
 * (mobs, Pokémon salvajes, Poké Balls lanzadas, flechas, botes...) se bloquea.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void desgraciamon4mirror$filterEntities(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (MirrorRules.isMirror(self) && !MirrorRules.allowSpawn(entity)) {
            cir.setReturnValue(false);
        }
    }
}

