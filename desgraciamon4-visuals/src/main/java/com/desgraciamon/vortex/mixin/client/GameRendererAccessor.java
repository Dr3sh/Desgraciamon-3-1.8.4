package com.desgraciamon.vortex.mixin.client;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

    @Accessor("postEffect")
    @Nullable
    PostChain desgraciamon$getPostEffect();

    @Invoker("loadEffect")
    void desgraciamon$loadEffect(ResourceLocation resourceLocation);
}

