package com.desgraciamon.announcements

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.UUID

interface StorageHandlerHandle {
    val storageUuid: UUID?
    val slotCount: Int
    fun getStackInSlot(slot: Int): ItemStack
    fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack
    fun setStackInSlot(slot: Int, stack: ItemStack)
}

interface BackpackAccess {
    fun isBackpacksLoaded(): Boolean
    fun isStorageLoaded(): Boolean
    fun openStackStorage(stack: ItemStack, registryAccess: HolderLookup.Provider?): StorageHandlerHandle?
    fun forEachEquippedBackpack(player: ServerPlayer, visitor: (ItemStack) -> Unit)
}

object SophisticatedBackpacksBridge {
    private const val STORAGE_UUID_NAMESPACE = "sophisticatedcore"
    private const val STORAGE_UUID_PATH = "storage_uuid"

    @Volatile
    internal var access: BackpackAccess = ReflectionBackpackAccess

    fun isBackpacksLoaded(): Boolean = access.isBackpacksLoaded()

    fun isStorageLoaded(): Boolean = access.isStorageLoaded()

    fun openStackStorage(
        stack: ItemStack,
        registryAccess: HolderLookup.Provider? = null
    ): StorageHandlerHandle? = access.openStackStorage(stack, registryAccess)

    fun forEachEquippedBackpack(player: ServerPlayer, visitor: (ItemStack) -> Unit) {
        access.forEachEquippedBackpack(player, visitor)
    }

    internal fun setAccessForTests(customAccess: BackpackAccess) {
        access = customAccess
    }

    internal fun resetAccessForTests() {
        access = ReflectionBackpackAccess
    }

    internal fun resolveStorageUuidComponentType(): DataComponentType<UUID>? {
        return runCatching {
            val id = ResourceLocation.fromNamespaceAndPath(STORAGE_UUID_NAMESPACE, STORAGE_UUID_PATH)
            @Suppress("UNCHECKED_CAST")
            BuiltInRegistries.DATA_COMPONENT_TYPE.get(id) as? DataComponentType<UUID>
        }.getOrNull()
    }
}

private object ReflectionBackpackAccess : BackpackAccess {
    private data class CoreReflection(
        val getInventoryHandler: Method,
        val getContentsUuid: Method,
        val getSlotCount: Method,
        val getStackInSlot: Method,
        val extractItem: Method,
        val setStackInSlot: Method
    )

    private data class BackpacksReflection(
        val backpackItemClass: Class<*>,
        val backpackFromStack: Method,
        val getProvider: Method,
        val consumerClass: Class<*>,
        val runOnBackpacks: Method,
        val core: CoreReflection
    )

    private data class StorageReflection(
        val shulkerBoxItemClass: Class<*>,
        val storageFromStack: Method,
        val hasContents: Method,
        val core: CoreReflection
    )

    @Volatile
    private var coreCache: CoreReflection? = null

    @Volatile
    private var backpacksCache: BackpacksReflection? = null

    @Volatile
    private var storageCache: StorageReflection? = null

    @Volatile
    private var backpacksInitFailed = false

    @Volatile
    private var storageInitFailed = false

    override fun isBackpacksLoaded(): Boolean {
        return runCatching { FabricLoader.getInstance().isModLoaded("sophisticatedbackpacks") }
            .getOrDefault(false)
    }

    override fun isStorageLoaded(): Boolean {
        return runCatching { FabricLoader.getInstance().isModLoaded("sophisticatedstorage") }
            .getOrDefault(false)
    }

    override fun openStackStorage(
        stack: ItemStack,
        registryAccess: HolderLookup.Provider?
    ): StorageHandlerHandle? {
        if (stack.isEmpty) return null

        if (isBackpacksLoaded()) {
            val bp = getBackpacksReflection()
            if (bp != null && bp.backpackItemClass.isInstance(stack.item)) {
                return runCatching {
                    val uuidType = SophisticatedBackpacksBridge.resolveStorageUuidComponentType() ?: return@runCatching null
                    val uuid = stack.get(uuidType) ?: return@runCatching null
                    val wrapper = bp.backpackFromStack.invoke(null, stack) ?: return@runCatching null
                    val handler = bp.core.getInventoryHandler.invoke(wrapper) ?: return@runCatching null
                    ReflectionStorageHandle(uuid, handler, bp.core)
                }.onFailure { err ->
                    AnnouncementsMod.logger.debug("Failed to inspect Sophisticated backpack stack", err)
                }.getOrNull()
            }
        }

        if (isStorageLoaded() && registryAccess != null) {
            val st = getStorageReflection()
            if (st != null && st.shulkerBoxItemClass.isInstance(stack.item)) {
                return runCatching {
                    val wrapper = st.storageFromStack.invoke(null, registryAccess, stack) ?: return@runCatching null
                    val hasContents = (st.hasContents.invoke(wrapper) as? Boolean) ?: false
                    if (!hasContents) return@runCatching null
                    val uuidOpt = st.core.getContentsUuid.invoke(wrapper) as? Optional<*>
                    val uuid = (uuidOpt?.orElse(null) as? UUID)
                        ?: SophisticatedBackpacksBridge.resolveStorageUuidComponentType()?.let { stack.get(it) }
                    val handler = st.core.getInventoryHandler.invoke(wrapper) ?: return@runCatching null
                    ReflectionStorageHandle(uuid, handler, st.core)
                }.onFailure { err ->
                    AnnouncementsMod.logger.debug("Failed to inspect Sophisticated storage stack", err)
                }.getOrNull()
            }
        }

        return null
    }

    override fun forEachEquippedBackpack(player: ServerPlayer, visitor: (ItemStack) -> Unit) {
        if (!isBackpacksLoaded()) return
        val bp = getBackpacksReflection() ?: return
        runCatching {
            val provider = bp.getProvider.invoke(null) ?: return
            val proxy = Proxy.newProxyInstance(
                bp.consumerClass.classLoader,
                arrayOf(bp.consumerClass)
            ) { instance, method, args ->
                when {
                    method.declaringClass == Any::class.java -> when (method.name) {
                        "toString" -> "DesgraciamonBackpackSlotConsumer"
                        "hashCode" -> System.identityHashCode(instance)
                        "equals" -> instance === args?.getOrNull(0)
                        else -> null
                    }
                    method.name == "accept" && args != null && args.isNotEmpty() -> {
                        val stack = args[0] as? ItemStack
                        if (stack != null && !stack.isEmpty) {
                            visitor(stack)
                        }
                        false
                    }
                    else -> false
                }
            }
            bp.runOnBackpacks.invoke(provider, player, proxy)
        }.onFailure { err ->
            AnnouncementsMod.logger.debug("Failed to iterate equipped Sophisticated backpacks for ${player.scoreboardName}", err)
        }
    }

    private fun getCoreReflection(): CoreReflection? {
        coreCache?.let { return it }
        return runCatching {
            val iStorageWrapper = Class.forName("net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper")
            val inventoryHandler = Class.forName("net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler")
            CoreReflection(
                getInventoryHandler = iStorageWrapper.getMethod("getInventoryHandler"),
                getContentsUuid = iStorageWrapper.getMethod("getContentsUuid"),
                getSlotCount = inventoryHandler.getMethod("getSlotCount"),
                getStackInSlot = inventoryHandler.getMethod("getStackInSlot", Int::class.javaPrimitiveType),
                extractItem = inventoryHandler.getMethod(
                    "extractItem",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                ),
                setStackInSlot = inventoryHandler.getMethod(
                    "setStackInSlot",
                    Int::class.javaPrimitiveType,
                    ItemStack::class.java
                )
            ).also { coreCache = it }
        }.onFailure { err ->
            AnnouncementsMod.logger.warn("Could not initialize Sophisticated Core reflection", err)
        }.getOrNull()
    }

    private fun getBackpacksReflection(): BackpacksReflection? {
        backpacksCache?.let { return it }
        if (backpacksInitFailed) return null
        val core = getCoreReflection() ?: run {
            backpacksInitFailed = true
            return null
        }
        return runCatching {
            val backpackItem = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem")
            val backpackWrapper = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper")
            val provider = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider")
            val consumer = Class.forName(
                "net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider\$BackpackInventorySlotConsumer"
            )
            BackpacksReflection(
                backpackItemClass = backpackItem,
                backpackFromStack = backpackWrapper.getMethod("fromStack", ItemStack::class.java),
                getProvider = provider.getMethod("get"),
                consumerClass = consumer,
                runOnBackpacks = provider.getMethod("runOnBackpacks", Player::class.java, consumer),
                core = core
            ).also { backpacksCache = it }
        }.onFailure { err ->
            backpacksInitFailed = true
            AnnouncementsMod.logger.warn("Could not initialize Sophisticated Backpacks reflection", err)
        }.getOrNull()
    }

    private fun getStorageReflection(): StorageReflection? {
        storageCache?.let { return it }
        if (storageInitFailed) return null
        val core = getCoreReflection() ?: run {
            storageInitFailed = true
            return null
        }
        return runCatching {
            val shulkerBoxItem = Class.forName("net.p3pp3rf1y.sophisticatedstorage.item.ShulkerBoxItem")
            val stackStorageWrapper = Class.forName("net.p3pp3rf1y.sophisticatedstorage.item.StackStorageWrapper")
            StorageReflection(
                shulkerBoxItemClass = shulkerBoxItem,
                storageFromStack = stackStorageWrapper.getMethod(
                    "fromStack",
                    HolderLookup.Provider::class.java,
                    ItemStack::class.java
                ),
                hasContents = stackStorageWrapper.getMethod("hasContents"),
                core = core
            ).also { storageCache = it }
        }.onFailure { err ->
            storageInitFailed = true
            AnnouncementsMod.logger.warn("Could not initialize Sophisticated Storage reflection", err)
        }.getOrNull()
    }

    private class ReflectionStorageHandle(
        override val storageUuid: UUID?,
        private val handlerInstance: Any,
        private val core: CoreReflection
    ) : StorageHandlerHandle {
        override val slotCount: Int
            get() = runCatching {
                (core.getSlotCount.invoke(handlerInstance) as? Int) ?: 0
            }.getOrDefault(0)

        override fun getStackInSlot(slot: Int): ItemStack {
            return runCatching {
                (core.getStackInSlot.invoke(handlerInstance, slot) as? ItemStack) ?: ItemStack.EMPTY
            }.getOrDefault(ItemStack.EMPTY)
        }

        override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack {
            if (amount <= 0) return ItemStack.EMPTY
            return runCatching {
                (core.extractItem.invoke(handlerInstance, slot, amount, simulate) as? ItemStack) ?: ItemStack.EMPTY
            }.getOrDefault(ItemStack.EMPTY)
        }

        override fun setStackInSlot(slot: Int, stack: ItemStack) {
            runCatching {
                core.setStackInSlot.invoke(handlerInstance, slot, stack)
            }
        }
    }
}
