package com.desgraciamon.itemrestrictions.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.Map;

@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {

    @Inject(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("HEAD"))
    private void desgraciamon$removeMasterBallRecipes(
        Map<ResourceLocation, JsonElement> object,
        ResourceManager resourceManager,
        ProfilerFiller profiler,
        CallbackInfo ci
    ) {
        Iterator<Map.Entry<ResourceLocation, JsonElement>> it = object.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ResourceLocation, JsonElement> entry = it.next();
            ResourceLocation id = entry.getKey();
            if ("cobblemon".equals(id.getNamespace()) && "master_ball".equals(id.getPath())) {
                it.remove();
                continue;
            }
            if (desgraciamon$outputsMasterBall(entry.getValue())) {
                it.remove();
            }
        }
    }

    private static boolean desgraciamon$outputsMasterBall(JsonElement element) {
        if (element == null || !element.isJsonObject()) return false;
        JsonObject obj = element.getAsJsonObject();
        if (!obj.has("result")) return false;
        JsonElement result = obj.get("result");
        if (result.isJsonPrimitive() && result.getAsJsonPrimitive().isString()) {
            return "cobblemon:master_ball".equalsIgnoreCase(result.getAsString());
        }
        if (result.isJsonObject()) {
            JsonObject resObj = result.getAsJsonObject();
            if (resObj.has("id") && resObj.get("id").isJsonPrimitive()) {
                return "cobblemon:master_ball".equalsIgnoreCase(resObj.get("id").getAsString());
            }
            if (resObj.has("item") && resObj.get("item").isJsonPrimitive()) {
                return "cobblemon:master_ball".equalsIgnoreCase(resObj.get("item").getAsString());
            }
        }
        return false;
    }
}
