package com.desgraciamon.auctions

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.TagParser
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

object AuctionItemSerializer {

    fun serialize(stack: ItemStack): String {
        if (stack.isEmpty) return ""
        return runCatching {
            val key = BuiltInRegistries.ITEM.getKey(stack.item).toString()
            "$key:${stack.count}"
        }.getOrDefault("minecraft:air:1")
    }

    fun deserialize(data: String): ItemStack {
        if (data.isBlank()) return ItemStack.EMPTY
        return runCatching {
            val parts = data.split(":")
            if (parts.size >= 2) {
                val res = ResourceLocation.fromNamespaceAndPath(parts[0], parts[1])
                val count = parts.getOrNull(2)?.toIntOrNull() ?: 1
                val item = BuiltInRegistries.ITEM.get(res)
                if (item != Items.AIR) {
                    ItemStack(item, count)
                } else {
                    ItemStack.EMPTY
                }
            } else {
                ItemStack.EMPTY
            }
        }.getOrDefault(ItemStack.EMPTY)
    }
}

