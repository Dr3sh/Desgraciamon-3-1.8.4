package com.desgraciamon.announcements

import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.BundleContents
import net.minecraft.world.item.component.ItemContainerContents
import java.util.UUID

object ContainerItemScanner : PlayerItemScanner, PlayerItemConsumer {
    private const val MAX_CONTAINER_DEPTH = 16

    override fun countItems(player: ServerPlayer): Map<String, Int> {
        val totals = mutableMapOf<String, Int>()
        val visitedStorageUuids = mutableSetOf<UUID>()
        val registryAccess = player.registryAccess()

        val inventoryGroups = listOf(
            player.inventory.items,
            player.inventory.armor,
            player.inventory.offhand
        )
        for (group in inventoryGroups) {
            for (stack in group) {
                if (!stack.isEmpty) {
                    countInStack(stack, totals, registryAccess, visitedStorageUuids, depth = 0)
                }
            }
        }

        SophisticatedBackpacksBridge.forEachEquippedBackpack(player) { equippedStack ->
            if (!equippedStack.isEmpty && equippedStack.count == 1) {
                val handle = SophisticatedBackpacksBridge.openStackStorage(equippedStack, registryAccess)
                if (handle != null) {
                    val uuid = handle.storageUuid
                    if (uuid == null || visitedStorageUuids.add(uuid)) {
                        val id = itemIdOf(equippedStack)
                        if (id.isNotBlank()) {
                            totals[id] = (totals[id] ?: 0) + equippedStack.count
                        }
                        countStorageHandle(handle, totals, registryAccess, visitedStorageUuids, depth = 1)
                    }
                }
            }
        }

        return totals
    }

    override fun consumeItem(player: ServerPlayer, itemId: String, amount: Int): Boolean {
        if (amount <= 0) return true
        val targetId = normalizeItemId(itemId)
        if (targetId.isBlank()) return false

        val totalAvailable = countItems(player)[targetId] ?: 0
        if (totalAvailable < amount) return false

        val registryAccess = player.registryAccess()
        val visitedStorageUuids = mutableSetOf<UUID>()
        val inventoryGroups = listOf(
            player.inventory.items,
            player.inventory.offhand,
            player.inventory.armor
        )

        var remaining = amount

        // 1. Direct inventory slots (items -> offhand -> armor)
        for (group in inventoryGroups) {
            if (remaining <= 0) break
            for (i in group.indices) {
                if (remaining <= 0) break
                val stack = group[i]
                if (!stack.isEmpty && itemIdOf(stack) == targetId) {
                    val take = remaining.coerceAtMost(stack.count)
                    stack.shrink(take)
                    remaining -= take
                }
            }
        }

        // 2. Vanilla Bundles (DataComponents.BUNDLE_CONTENTS)
        for (group in inventoryGroups) {
            if (remaining <= 0) break
            for (i in group.indices) {
                if (remaining <= 0) break
                val stack = group[i]
                if (!stack.isEmpty && stack.count == 1 && stack.get(DataComponents.BUNDLE_CONTENTS) != null) {
                    remaining -= consumeFromBundle(
                        stack,
                        targetId,
                        remaining,
                        registryAccess,
                        visitedStorageUuids,
                        depth = 0
                    )
                }
            }
        }

        // 3. Vanilla Shulker Boxes / container items (DataComponents.CONTAINER)
        for (group in inventoryGroups) {
            if (remaining <= 0) break
            for (i in group.indices) {
                if (remaining <= 0) break
                val stack = group[i]
                if (!stack.isEmpty && stack.count == 1 && stack.get(DataComponents.CONTAINER) != null) {
                    remaining -= consumeFromItemContainer(
                        stack,
                        targetId,
                        remaining,
                        registryAccess,
                        visitedStorageUuids,
                        depth = 0
                    )
                }
            }
        }

        // 4. Sophisticated Backpacks / Storage in inventory and equipped accessory slots
        for (group in inventoryGroups) {
            if (remaining <= 0) break
            for (i in group.indices) {
                if (remaining <= 0) break
                val stack = group[i]
                if (!stack.isEmpty && stack.count == 1) {
                    remaining -= consumeFromSophisticatedStorage(
                        stack,
                        targetId,
                        remaining,
                        registryAccess,
                        visitedStorageUuids,
                        depth = 0
                    )
                }
            }
        }
        if (remaining > 0) {
            SophisticatedBackpacksBridge.forEachEquippedBackpack(player) { equippedStack ->
                if (remaining > 0 && !equippedStack.isEmpty && equippedStack.count == 1) {
                    remaining -= consumeFromSophisticatedStorage(
                        equippedStack,
                        targetId,
                        remaining,
                        registryAccess,
                        visitedStorageUuids,
                        depth = 0
                    )
                }
            }
        }

        if (remaining < amount) {
            player.inventory.setChanged()
            player.containerMenu.broadcastChanges()
        }
        return remaining == 0
    }

    fun countInStacks(
        stacks: Iterable<ItemStack>,
        registryAccess: HolderLookup.Provider? = null
    ): Map<String, Int> {
        val totals = mutableMapOf<String, Int>()
        val visitedStorageUuids = mutableSetOf<UUID>()
        for (stack in stacks) {
            if (!stack.isEmpty) {
                countInStack(stack, totals, registryAccess, visitedStorageUuids, depth = 0)
            }
        }
        return totals
    }

    fun countInStack(
        stack: ItemStack,
        totals: MutableMap<String, Int>,
        registryAccess: HolderLookup.Provider? = null,
        visitedStorageUuids: MutableSet<UUID> = mutableSetOf(),
        depth: Int = 0
    ) {
        if (stack.isEmpty || depth > MAX_CONTAINER_DEPTH) return

        val id = itemIdOf(stack)
        if (id.isNotBlank()) {
            totals[id] = (totals[id] ?: 0) + stack.count
        }

        if (stack.count != 1) return

        val container = stack.get(DataComponents.CONTAINER)
        if (container != null) {
            for (inner in container.nonEmptyItems()) {
                if (!inner.isEmpty) {
                    countInStack(inner, totals, registryAccess, visitedStorageUuids, depth + 1)
                }
            }
        }

        val bundle = stack.get(DataComponents.BUNDLE_CONTENTS)
        if (bundle != null && !bundle.isEmpty) {
            for (inner in bundle.items()) {
                if (!inner.isEmpty) {
                    countInStack(inner, totals, registryAccess, visitedStorageUuids, depth + 1)
                }
            }
        }

        val storageHandle = SophisticatedBackpacksBridge.openStackStorage(stack, registryAccess)
        if (storageHandle != null) {
            val uuid = storageHandle.storageUuid
            if (uuid == null || visitedStorageUuids.add(uuid)) {
                countStorageHandle(storageHandle, totals, registryAccess, visitedStorageUuids, depth + 1)
            }
        }
    }

    fun consumeFromStacks(
        stacks: MutableList<ItemStack>,
        itemId: String,
        amount: Int,
        registryAccess: HolderLookup.Provider? = null
    ): Boolean {
        if (amount <= 0) return true
        val targetId = normalizeItemId(itemId)
        if (targetId.isBlank()) return false

        val totalAvailable = countInStacks(stacks, registryAccess)[targetId] ?: 0
        if (totalAvailable < amount) return false

        val visitedStorageUuids = mutableSetOf<UUID>()
        var remaining = amount

        // 1. Direct stacks
        for (i in stacks.indices) {
            if (remaining <= 0) break
            val stack = stacks[i]
            if (!stack.isEmpty && itemIdOf(stack) == targetId) {
                val take = remaining.coerceAtMost(stack.count)
                stack.shrink(take)
                remaining -= take
            }
        }

        // 2. Bundles
        for (i in stacks.indices) {
            if (remaining <= 0) break
            val stack = stacks[i]
            if (!stack.isEmpty && stack.count == 1 && stack.get(DataComponents.BUNDLE_CONTENTS) != null) {
                remaining -= consumeFromBundle(stack, targetId, remaining, registryAccess, visitedStorageUuids, 0)
            }
        }

        // 3. Shulker Boxes / ItemContainerContents
        for (i in stacks.indices) {
            if (remaining <= 0) break
            val stack = stacks[i]
            if (!stack.isEmpty && stack.count == 1 && stack.get(DataComponents.CONTAINER) != null) {
                remaining -= consumeFromItemContainer(stack, targetId, remaining, registryAccess, visitedStorageUuids, 0)
            }
        }

        // 4. Sophisticated containers
        for (i in stacks.indices) {
            if (remaining <= 0) break
            val stack = stacks[i]
            if (!stack.isEmpty && stack.count == 1) {
                remaining -= consumeFromSophisticatedStorage(stack, targetId, remaining, registryAccess, visitedStorageUuids, 0)
            }
        }

        return remaining == 0
    }

    fun consumeFromStack(
        stack: ItemStack,
        itemId: String,
        amount: Int,
        registryAccess: HolderLookup.Provider? = null,
        visitedStorageUuids: MutableSet<UUID> = mutableSetOf(),
        depth: Int = 0
    ): Int {
        if (amount <= 0 || stack.isEmpty || depth > MAX_CONTAINER_DEPTH) return 0
        val targetId = normalizeItemId(itemId)
        if (targetId.isBlank()) return 0

        var remaining = amount
        if (stack.count == 1) {
            if (stack.get(DataComponents.BUNDLE_CONTENTS) != null) {
                remaining -= consumeFromBundle(stack, targetId, remaining, registryAccess, visitedStorageUuids, depth)
            }
            if (remaining > 0 && stack.get(DataComponents.CONTAINER) != null) {
                remaining -= consumeFromItemContainer(stack, targetId, remaining, registryAccess, visitedStorageUuids, depth)
            }
            if (remaining > 0) {
                remaining -= consumeFromSophisticatedStorage(stack, targetId, remaining, registryAccess, visitedStorageUuids, depth)
            }
        }
        if (remaining > 0 && itemIdOf(stack) == targetId) {
            val take = remaining.coerceAtMost(stack.count)
            stack.shrink(take)
            remaining -= take
        }
        return amount - remaining
    }

    private fun countStorageHandle(
        handle: StorageHandlerHandle,
        totals: MutableMap<String, Int>,
        registryAccess: HolderLookup.Provider?,
        visitedStorageUuids: MutableSet<UUID>,
        depth: Int
    ) {
        if (depth > MAX_CONTAINER_DEPTH) return
        val slotCount = handle.slotCount
        for (slot in 0 until slotCount) {
            val inner = handle.getStackInSlot(slot)
            if (!inner.isEmpty) {
                countInStack(inner, totals, registryAccess, visitedStorageUuids, depth)
            }
        }
    }

    private fun consumeFromBundle(
        bundleStack: ItemStack,
        targetId: String,
        maxToConsume: Int,
        registryAccess: HolderLookup.Provider?,
        visitedStorageUuids: MutableSet<UUID>,
        depth: Int
    ): Int {
        if (maxToConsume <= 0 || bundleStack.isEmpty || bundleStack.count != 1 || depth > MAX_CONTAINER_DEPTH) return 0
        val contents = bundleStack.get(DataComponents.BUNDLE_CONTENTS) ?: return 0
        if (contents.isEmpty) return 0

        val items = contents.itemsCopy().toMutableList()
        var remaining = maxToConsume
        var mutated = false

        // Direct items inside bundle
        for (inner in items) {
            if (remaining <= 0) break
            if (!inner.isEmpty && itemIdOf(inner) == targetId) {
                val take = remaining.coerceAtMost(inner.count)
                inner.shrink(take)
                remaining -= take
                mutated = true
            }
        }

        // Nested containers inside bundle
        if (remaining > 0) {
            for (inner in items) {
                if (remaining <= 0) break
                if (!inner.isEmpty && inner.count == 1) {
                    val nestedTaken = consumeFromNestedContainers(
                        inner,
                        targetId,
                        remaining,
                        registryAccess,
                        visitedStorageUuids,
                        depth + 1
                    )
                    if (nestedTaken > 0) {
                        remaining -= nestedTaken
                        mutated = true
                    }
                }
            }
        }

        if (mutated) {
            val updated = items.filter { !it.isEmpty }
            bundleStack.set(
                DataComponents.BUNDLE_CONTENTS,
                if (updated.isEmpty()) BundleContents.EMPTY else BundleContents(updated)
            )
        }
        return maxToConsume - remaining
    }

    private fun consumeFromItemContainer(
        containerStack: ItemStack,
        targetId: String,
        maxToConsume: Int,
        registryAccess: HolderLookup.Provider?,
        visitedStorageUuids: MutableSet<UUID>,
        depth: Int
    ): Int {
        if (maxToConsume <= 0 || containerStack.isEmpty || containerStack.count != 1 || depth > MAX_CONTAINER_DEPTH) return 0
        val contents = containerStack.get(DataComponents.CONTAINER) ?: return 0

        val slots = contents.stream().map { it.copy() }.toList().toMutableList()
        if (slots.isEmpty()) return 0

        var remaining = maxToConsume
        var mutated = false

        // Direct items inside container
        for (inner in slots) {
            if (remaining <= 0) break
            if (!inner.isEmpty && itemIdOf(inner) == targetId) {
                val take = remaining.coerceAtMost(inner.count)
                inner.shrink(take)
                remaining -= take
                mutated = true
            }
        }

        // Nested containers inside container
        if (remaining > 0) {
            for (inner in slots) {
                if (remaining <= 0) break
                if (!inner.isEmpty && inner.count == 1) {
                    val nestedTaken = consumeFromNestedContainers(
                        inner,
                        targetId,
                        remaining,
                        registryAccess,
                        visitedStorageUuids,
                        depth + 1
                    )
                    if (nestedTaken > 0) {
                        remaining -= nestedTaken
                        mutated = true
                    }
                }
            }
        }

        if (mutated) {
            containerStack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots))
        }
        return maxToConsume - remaining
    }

    private fun consumeFromSophisticatedStorage(
        storageStack: ItemStack,
        targetId: String,
        maxToConsume: Int,
        registryAccess: HolderLookup.Provider?,
        visitedStorageUuids: MutableSet<UUID>,
        depth: Int
    ): Int {
        if (maxToConsume <= 0 || storageStack.isEmpty || storageStack.count != 1 || depth > MAX_CONTAINER_DEPTH) return 0
        val handle = SophisticatedBackpacksBridge.openStackStorage(storageStack, registryAccess) ?: return 0
        val uuid = handle.storageUuid
        if (uuid != null && !visitedStorageUuids.add(uuid)) return 0

        var remaining = maxToConsume
        val slotCount = handle.slotCount

        // Direct slots inside backpack / Sophisticated shulker
        for (slot in 0 until slotCount) {
            if (remaining <= 0) break
            val inner = handle.getStackInSlot(slot)
            if (!inner.isEmpty && itemIdOf(inner) == targetId) {
                val toTake = remaining.coerceAtMost(inner.count)
                val extracted = handle.extractItem(slot, toTake, false)
                remaining -= extracted.count
            }
        }

        // Nested containers inside backpack / Sophisticated shulker
        if (remaining > 0) {
            for (slot in 0 until slotCount) {
                if (remaining <= 0) break
                val inner = handle.getStackInSlot(slot)
                if (!inner.isEmpty && inner.count == 1) {
                    val hasVanillaNested = inner.get(DataComponents.BUNDLE_CONTENTS) != null ||
                        inner.get(DataComponents.CONTAINER) != null
                    if (hasVanillaNested) {
                        val copy = inner.copy()
                        val takenFromVanilla = consumeFromNestedVanillaContainers(
                            copy,
                            targetId,
                            remaining,
                            registryAccess,
                            visitedStorageUuids,
                            depth + 1
                        )
                        if (takenFromVanilla > 0) {
                            handle.setStackInSlot(slot, copy)
                            remaining -= takenFromVanilla
                        }
                    }
                    if (remaining > 0) {
                        remaining -= consumeFromSophisticatedStorage(
                            inner,
                            targetId,
                            remaining,
                            registryAccess,
                            visitedStorageUuids,
                            depth + 1
                        )
                    }
                }
            }
        }

        return maxToConsume - remaining
    }

    private fun consumeFromNestedVanillaContainers(
        stack: ItemStack,
        targetId: String,
        maxToConsume: Int,
        registryAccess: HolderLookup.Provider?,
        visitedStorageUuids: MutableSet<UUID>,
        depth: Int
    ): Int {
        var remaining = maxToConsume
        if (remaining > 0 && stack.get(DataComponents.BUNDLE_CONTENTS) != null) {
            remaining -= consumeFromBundle(stack, targetId, remaining, registryAccess, visitedStorageUuids, depth)
        }
        if (remaining > 0 && stack.get(DataComponents.CONTAINER) != null) {
            remaining -= consumeFromItemContainer(stack, targetId, remaining, registryAccess, visitedStorageUuids, depth)
        }
        return maxToConsume - remaining
    }

    private fun consumeFromNestedContainers(
        stack: ItemStack,
        targetId: String,
        maxToConsume: Int,
        registryAccess: HolderLookup.Provider?,
        visitedStorageUuids: MutableSet<UUID>,
        depth: Int
    ): Int {
        var remaining = maxToConsume
        remaining -= consumeFromNestedVanillaContainers(
            stack,
            targetId,
            remaining,
            registryAccess,
            visitedStorageUuids,
            depth
        )
        if (remaining > 0) {
            remaining -= consumeFromSophisticatedStorage(
                stack,
                targetId,
                remaining,
                registryAccess,
                visitedStorageUuids,
                depth
            )
        }
        return maxToConsume - remaining
    }

    internal fun itemIdOf(stack: ItemStack): String {
        if (stack.isEmpty) return ""
        return normalizeItemId(BuiltInRegistries.ITEM.getKey(stack.item).toString())
    }

    fun normalizeItemId(raw: String): String {
        val clean = raw.trim().lowercase()
        if (clean.isBlank()) return ""
        return if (':' in clean) clean else "minecraft:$clean"
    }
}
