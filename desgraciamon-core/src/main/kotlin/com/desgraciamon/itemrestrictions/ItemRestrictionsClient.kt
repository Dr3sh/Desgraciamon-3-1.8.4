package com.desgraciamon.itemrestrictions

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.ItemStack

class ItemRestrictionsClient : ClientModInitializer {

    override fun onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ItemRestrictionsSyncPayload.TYPE) { payload, context ->
            context.client().execute {
                update(payload.restrictedItems.toSet(), payload.restrictedTags, payload.activeForPlayer)
            }
        }
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            update(emptySet(), emptyList(), false)
        }
    }

    companion object {
        @Volatile
        private var restrictedItems: Set<String> = emptySet()
        @Volatile
        private var restrictedTags: List<String> = emptyList()
        @Volatile
        private var activeForPlayer: Boolean = false

        fun update(items: Set<String>, tags: List<String>, active: Boolean) {
            this.restrictedItems = items
            this.restrictedTags = tags
            this.activeForPlayer = active
        }

        @JvmStatic
        fun isRestricted(stack: ItemStack): Boolean {
            if (!activeForPlayer || stack.isEmpty) return false
            if (net.minecraft.client.Minecraft.getInstance().player?.isCreative == true) return false
            val itemKey = BuiltInRegistries.ITEM.getKey(stack.item).toString().lowercase()
            if (restrictedItems.contains(itemKey)) return true

            val allTagCandidates = restrictedTags + restrictedItems.filter { it.startsWith("#") }
            for (tagKey in allTagCandidates) {
                val cleanTag = tagKey.removePrefix("#")
                val rl = ResourceLocation.tryParse(cleanTag) ?: continue
                val tag = TagKey.create(Registries.ITEM, rl)
                if (stack.`is`(tag)) return true
            }
            return false
        }

        @JvmStatic
        fun isClientRestricted(stack: ItemStack): Boolean {
            return ItemRestrictionsRenderer.isRestricted(stack)
        }
    }
}
