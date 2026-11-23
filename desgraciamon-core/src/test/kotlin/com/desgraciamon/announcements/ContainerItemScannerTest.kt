package com.desgraciamon.announcements

import net.minecraft.SharedConstants
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponents
import net.minecraft.server.Bootstrap
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.BundleContents
import net.minecraft.world.item.component.ItemContainerContents
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID

class ContainerItemScannerTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun bootstrapMinecraftRegistries() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    private class FakeStorageHandlerHandle(
        override val storageUuid: UUID?,
        private val slots: MutableList<ItemStack>
    ) : StorageHandlerHandle {
        override val slotCount: Int
            get() = slots.size

        override fun getStackInSlot(slot: Int): ItemStack =
            if (slot in slots.indices) slots[slot] else ItemStack.EMPTY

        override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack {
            if (slot !in slots.indices || amount <= 0) return ItemStack.EMPTY
            val current = slots[slot]
            if (current.isEmpty) return ItemStack.EMPTY
            val take = amount.coerceAtMost(current.count)
            val result = current.copyWithCount(take)
            if (!simulate) {
                current.shrink(take)
                if (current.isEmpty) {
                    slots[slot] = ItemStack.EMPTY
                }
            }
            return result
        }

        override fun setStackInSlot(slot: Int, stack: ItemStack) {
            if (slot in slots.indices) {
                slots[slot] = stack
            }
        }
    }

    private class FakeBackpackAccess(
        private val storagesByItem: Map<net.minecraft.world.item.Item, StorageHandlerHandle>,
        private val equipped: List<ItemStack> = emptyList()
    ) : BackpackAccess {
        override fun isBackpacksLoaded(): Boolean = true
        override fun isStorageLoaded(): Boolean = true

        override fun openStackStorage(
            stack: ItemStack,
            registryAccess: HolderLookup.Provider?
        ): StorageHandlerHandle? {
            if (stack.isEmpty) return null
            return storagesByItem[stack.item]
        }

        override fun forEachEquippedBackpack(player: ServerPlayer, visitor: (ItemStack) -> Unit) {
            equipped.forEach(visitor)
        }
    }

    @Test
    fun `counts direct stacks shulker boxes bundles and nested containers accurately`() {
        val nestedBundle = ItemStack(Items.BUNDLE, 1).apply {
            set(DataComponents.BUNDLE_CONTENTS, BundleContents(listOf(ItemStack(Items.DIAMOND, 4))))
        }
        val nestedShulker = ItemStack(Items.SHULKER_BOX, 1).apply {
            set(
                DataComponents.CONTAINER,
                ItemContainerContents.fromItems(listOf(ItemStack(Items.DIAMOND, 6), nestedBundle))
            )
        }
        val outerBundle = ItemStack(Items.BUNDLE, 1).apply {
            set(
                DataComponents.BUNDLE_CONTENTS,
                BundleContents(listOf(ItemStack(Items.DIAMOND, 3), ItemStack(Items.EMERALD, 2)))
            )
        }
        val directDiamonds = ItemStack(Items.DIAMOND, 5)

        val totals = ContainerItemScanner.countInStacks(listOf(directDiamonds, nestedShulker, outerBundle))
        assertEquals(18, totals["minecraft:diamond"])
        assertEquals(2, totals["minecraft:emerald"])
        assertEquals(1, totals["minecraft:shulker_box"])
        assertEquals(2, totals["minecraft:bundle"])
    }

    @Test
    fun `consumeFromStacks is atomic when total is insufficient and consumes in order when sufficient`() {
        val shulker = ItemStack(Items.SHULKER_BOX, 1).apply {
            set(DataComponents.CONTAINER, ItemContainerContents.fromItems(listOf(ItemStack(Items.DIAMOND, 5))))
        }
        val bundle = ItemStack(Items.BUNDLE, 1).apply {
            set(DataComponents.BUNDLE_CONTENTS, BundleContents(listOf(ItemStack(Items.DIAMOND, 3))))
        }
        val direct = ItemStack(Items.DIAMOND, 2)
        val stacks = mutableListOf(direct, bundle, shulker)

        // Total is 10; requesting 11 must fail atomically without modifying any stack
        assertFalse(ContainerItemScanner.consumeFromStacks(stacks, "minecraft:diamond", 11))
        assertEquals(10, ContainerItemScanner.countInStacks(stacks)["minecraft:diamond"])
        assertEquals(2, direct.count)

        // Requesting 7 must consume: 2 direct + 3 from bundle + 2 from shulker -> 3 left in shulker
        assertTrue(ContainerItemScanner.consumeFromStacks(stacks, "diamond", 7))
        assertEquals(3, ContainerItemScanner.countInStacks(stacks)["minecraft:diamond"])
        assertTrue(direct.isEmpty)
        assertTrue(bundle.get(DataComponents.BUNDLE_CONTENTS)?.isEmpty == true)

        // consumeFromStack directly on the shulker consumes the remaining 3
        val taken = ContainerItemScanner.consumeFromStack(shulker, "minecraft:diamond", 3)
        assertEquals(3, taken)
        assertEquals(0, ContainerItemScanner.countInStacks(stacks)["minecraft:diamond"] ?: 0)
    }

    @Test
    fun `sophisticated backpacks bridge counts extracts deduplicates uuids and degrades gracefully when absent`() {
        val sharedUuid = UUID.randomUUID()
        val innerShulker = ItemStack(Items.SHULKER_BOX, 1).apply {
            set(DataComponents.CONTAINER, ItemContainerContents.fromItems(listOf(ItemStack(Items.GOLD_INGOT, 4))))
        }
        val handle = FakeStorageHandlerHandle(
            storageUuid = sharedUuid,
            slots = mutableListOf(
                ItemStack(Items.GOLD_INGOT, 6),
                innerShulker,
                ItemStack.EMPTY
            )
        )

        val fakeAccess = FakeBackpackAccess(mapOf(Items.ENDER_CHEST to handle))
        try {
            SophisticatedBackpacksBridge.setAccessForTests(fakeAccess)
            assertTrue(SophisticatedBackpacksBridge.isBackpacksLoaded())
            assertTrue(SophisticatedBackpacksBridge.isStorageLoaded())

            // Two stacks pointing to the same storageUuid must NOT double-count contents
            val backpackCopy1 = ItemStack(Items.ENDER_CHEST, 1)
            val backpackCopy2 = ItemStack(Items.ENDER_CHEST, 1)
            val totals = ContainerItemScanner.countInStacks(listOf(backpackCopy1, backpackCopy2))
            assertEquals(10, totals["minecraft:gold_ingot"])
            assertEquals(2, totals["minecraft:ender_chest"])

            // Extract 8 gold ingots: 6 direct from backpack slot 0 + 2 from nested shulker in slot 1
            val stacks = mutableListOf(backpackCopy1)
            assertTrue(ContainerItemScanner.consumeFromStacks(stacks, "minecraft:gold_ingot", 8))
            assertEquals(2, ContainerItemScanner.countInStacks(stacks)["minecraft:gold_ingot"])
        } finally {
            SophisticatedBackpacksBridge.resetAccessForTests()
        }

        // With ReflectionBackpackAccess restored (mods absent in test env), bridge degrades gracefully
        assertFalse(SophisticatedBackpacksBridge.isBackpacksLoaded())
        assertFalse(SophisticatedBackpacksBridge.isStorageLoaded())
        assertNull(SophisticatedBackpacksBridge.openStackStorage(ItemStack(Items.CHEST), null))
    }
}
