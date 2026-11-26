package com.desgraciamon.economy

import com.google.gson.GsonBuilder
import fr.harmex.cobbledollars.common.world.entity.CobbleDollarsEntities
import fr.harmex.cobbledollars.common.world.entity.CobbleMerchant
import net.minecraft.ChatFormatting
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong
import kotlin.random.Random

data class TcgVendorConfig(
    var rotationIntervalHours: Int = 2,
    var vendorName: String = "Coleccionista TCG",
    var broadcastOnRotation: Boolean = true,
    var includeClassicPack: Boolean = true,
    var genPacksPerRotation: Int = 1,
    var typePacksPerRotation: Int = 1,
    var classicPackPrice: Long = 2250L,
    var classicPackStock: Int = 10,
    var genPackPrice: Long = 3750L,
    var genPackStock: Int = 5,
    var typePackPrice: Long = 3750L,
    var typePackStock: Int = 5,
    var featuredCardEnabled: Boolean = true,
    var featuredCardMinRarity: String = "rare",
    var featuredCardStock: Int = 1,
    var featuredCardPriceRare: Long = 6750L,
    var featuredCardPriceEpic: Long = 13500L,
    var featuredCardPriceLegendary: Long = 27000L,
    var featuredCardPriceMythic: Long = 48000L,
    var featuredCardPriceRareShiny: Long = 10125L,
    var featuredCardPriceEpicShiny: Long = 20250L,
    var featuredCardPriceLegendaryShiny: Long = 40500L,
    var featuredCardPriceMythicShiny: Long = 72000L,
    var featuredCardShinyMultiplier: Double = 1.5,
    var packPrices: MutableMap<String, Long> = LinkedHashMap()
)

data class TcgVendorState(
    var slotStartMs: Long = 0L,
    var selectedPackIds: MutableList<String> = mutableListOf(),
    var featuredCardSummary: String = "",
    var featuredCardPrice: Long = 0L
)

object TcgMerchantManager {
    private val logger = LoggerFactory.getLogger("desgraciamon4-economy")
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val configFile = File("config/desgraciamon4_economy/tcg_vendor.json")
    private var stateFile = File("config/desgraciamon4_economy/tcg_vendor_state.json")

    const val VENDOR_TAG = "desgraciamon_tcg_vendor"
    private const val SLOT_TAG_PREFIX = "desgraciamon_tcg_slot_"
    private val VET_ZONE = ZoneId.of("America/Caracas")
    private val TIME_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    private val GEN_PACK_IDS = (1..9).map { "cobblemon-cards:booster_pack_gen$it" }
    private val TYPE_PACK_IDS = listOf(
        "fire", "water", "grass", "electric", "ice", "fighting",
        "poison", "ground", "flying", "psychic", "bug", "rock",
        "ghost", "dragon", "steel", "fairy", "dark", "normal"
    ).map { "cobblemon-cards:booster_pack_$it" }

    var config: TcgVendorConfig = TcgVendorConfig()
        private set

    var state: TcgVendorState = TcgVendorState()
        private set

    private data class ActiveOfferItem(
        val stack: ItemStack,
        val price: Long,
        val stock: Int,
        val isFeaturedCard: Boolean,
        val label: String
    )

    private val activeOffers = ArrayList<ActiveOfferItem>()

    fun initForServer(server: MinecraftServer) {
        val worldRoot = server.getWorldPath(LevelResource.ROOT)
        val dir = worldRoot.resolve("data/desgraciamon4_economy").toFile()
        dir.mkdirs()
        stateFile = File(dir, "tcg_vendor_state.json")
        loadConfig()
        loadState()
    }

    private fun createDefaultConfig(): TcgVendorConfig {
        val def = TcgVendorConfig()
        ensureDefaultPackPrices(def)
        return def
    }

    private fun ensureDefaultPackPrices(target: TcgVendorConfig) {
        if (target.packPrices == null) {
            target.packPrices = LinkedHashMap()
        }
        target.packPrices.putIfAbsent("cobblemon-cards:booster_pack", target.classicPackPrice.coerceAtLeast(1L))
        for (genId in GEN_PACK_IDS) {
            target.packPrices.putIfAbsent(genId, target.genPackPrice.coerceAtLeast(1L))
        }
        for (typeId in TYPE_PACK_IDS) {
            target.packPrices.putIfAbsent(typeId, target.typePackPrice.coerceAtLeast(1L))
        }
    }

    fun loadConfig() {
        configFile.parentFile?.mkdirs()
        config = if (configFile.exists()) {
            runCatching {
                configFile.reader(Charsets.UTF_8).use { gson.fromJson(it, TcgVendorConfig::class.java) }
            }.getOrElse {
                logger.error("Error leyendo tcg_vendor.json, usando valores por defecto", it)
                createDefaultConfig()
            }
        } else {
            val def = createDefaultConfig()
            saveConfig(def)
            def
        }
        normalizeConfig()
    }

    private fun normalizeConfig() {
        if (config.rotationIntervalHours !in 1..24) config.rotationIntervalHours = 2
        if (config.vendorName.isBlank()) config.vendorName = "Coleccionista TCG"
        config.genPacksPerRotation = config.genPacksPerRotation.coerceIn(0, GEN_PACK_IDS.size)
        config.typePacksPerRotation = config.typePacksPerRotation.coerceIn(0, TYPE_PACK_IDS.size)
        if (config.featuredCardMinRarity.isBlank()) config.featuredCardMinRarity = "rare"
        if (config.featuredCardShinyMultiplier < 1.0) config.featuredCardShinyMultiplier = 1.5
        ensureDefaultPackPrices(config)
    }

    fun saveConfig(target: TcgVendorConfig = config) {
        runCatching {
            configFile.parentFile?.mkdirs()
            configFile.writeText(gson.toJson(target), Charsets.UTF_8)
        }.onFailure {
            logger.error("Error guardando tcg_vendor.json", it)
        }
    }

    private fun loadState() {
        state = if (stateFile.exists()) {
            runCatching {
                stateFile.reader(Charsets.UTF_8).use { gson.fromJson(it, TcgVendorState::class.java) }
            }.getOrElse { TcgVendorState() }
        } else {
            TcgVendorState()
        }
    }

    private fun saveState() {
        runCatching {
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(gson.toJson(state), Charsets.UTF_8)
        }.onFailure {
            logger.error("Error guardando tcg_vendor_state.json", it)
        }
    }

    /**
     * Devuelve el inicio del bloque horario actual alineado al reloj VET (ej: cada 2h -> 00:00, 02:00, 04:00...).
     */
    fun getCurrentSlotStartMs(nowMs: Long = System.currentTimeMillis()): Long {
        val interval = config.rotationIntervalHours.coerceIn(1, 24)
        val zdt = Instant.ofEpochMilli(nowMs).atZone(VET_ZONE)
        val alignedHour = (zdt.hour / interval) * interval
        return zdt.toLocalDate().atTime(alignedHour, 0).atZone(VET_ZONE).toInstant().toEpochMilli()
    }

    fun getNextRotationMs(nowMs: Long = System.currentTimeMillis()): Long {
        val interval = config.rotationIntervalHours.coerceIn(1, 24).toLong()
        val slotStart = Instant.ofEpochMilli(getCurrentSlotStartMs(nowMs)).atZone(VET_ZONE)
        return slotStart.plusHours(interval).toInstant().toEpochMilli()
    }

    fun formatDurationRemaining(diffMs: Long): String {
        val safe = diffMs.coerceAtLeast(0L)
        val hours = safe / 3600000L
        val mins = (safe % 3600000L) / 60000L
        return "${hours}h ${mins}m"
    }

    fun formatTimestampVet(epochMs: Long): String {
        val zdt = Instant.ofEpochMilli(epochMs).atZone(VET_ZONE)
        return "${TIME_FMT.format(zdt)} VET"
    }

    fun initOnServerStarted(server: MinecraftServer) {
        val currentSlot = getCurrentSlotStartMs()
        if (state.slotStartMs != currentSlot || activeOffers.isEmpty()) {
            rotateNow(server, broadcast = false)
        } else {
            applyShopToLoadedVendors(server, force = false)
        }
    }

    fun tick(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        val currentSlot = getCurrentSlotStartMs(now)
        if (currentSlot > state.slotStartMs || activeOffers.isEmpty()) {
            rotateNow(server, broadcast = config.broadcastOnRotation)
        } else {
            applyShopToLoadedVendors(server, force = false)
        }
    }

    fun onEntityLoad(entity: Entity, server: MinecraftServer) {
        if (entity is CobbleMerchant && entity.tags.contains(VENDOR_TAG)) {
            if (activeOffers.isEmpty()) {
                generateRotation(getCurrentSlotStartMs())
            }
            val currentTag = "$SLOT_TAG_PREFIX${state.slotStartMs}"
            if (!entity.tags.contains(currentTag)) {
                applyShopToMerchant(entity, currentTag)
            }
        }
    }

    @Synchronized
    fun rotateNow(server: MinecraftServer?, broadcast: Boolean): String {
        val currentSlot = getCurrentSlotStartMs()
        generateRotation(currentSlot)
        if (server != null) {
            applyShopToLoadedVendors(server, force = true)
            if (broadcast) {
                val packsText = state.selectedPackIds.joinToString(", ") { prettyPackName(it) }
                val cardText = if (state.featuredCardSummary.isNotBlank()) {
                    state.featuredCardSummary
                } else {
                    "Ninguna"
                }
                val nextFormatted = formatTimestampVet(getNextRotationMs())
                val msg = Component.literal(
                    "§6§l[${config.vendorName}] §a¡Stock rotado y repuesto!\n" +
                    "§7Sobres disponibles: §e$packsText\n" +
                    "§7Carta destacada: §d$cardText §7(§e${state.featuredCardPrice}$§7)\n" +
                    "§8Próxima rotación: §e$nextFormatted"
                )
                for (player in server.playerList.players) {
                    player.sendSystemMessage(msg)
                }
            }
        }
        return buildStatusSummary()
    }

    @Synchronized
    private fun generateRotation(slotStartMs: Long) {
        activeOffers.clear()
        val rng = Random(System.nanoTime() xor slotStartMs)

        val chosenPacks = ArrayList<String>()
        if (config.includeClassicPack) {
            chosenPacks.add("cobblemon-cards:booster_pack")
        }

        val gens = GEN_PACK_IDS.shuffled(rng).take(config.genPacksPerRotation)
        chosenPacks.addAll(gens)

        val types = TYPE_PACK_IDS.shuffled(rng).take(config.typePacksPerRotation)
        chosenPacks.addAll(types)

        state.slotStartMs = slotStartMs
        state.selectedPackIds.clear()
        state.selectedPackIds.addAll(chosenPacks)

        for (packId in chosenPacks) {
            val item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(packId)).orElse(null)
            if (item == null) {
                logger.warn("Ítem TCG no encontrado en registro: $packId")
                continue
            }
            val (fallbackPrice, stock) = when {
                packId == "cobblemon-cards:booster_pack" -> config.classicPackPrice to config.classicPackStock
                packId.startsWith("cobblemon-cards:booster_pack_gen") -> config.genPackPrice to config.genPackStock
                else -> config.typePackPrice to config.typePackStock
            }
            val configuredPrice = config.packPrices[packId]?.takeIf { it > 0L } ?: fallbackPrice
            activeOffers.add(
                ActiveOfferItem(
                    stack = ItemStack(item, 1),
                    price = configuredPrice.coerceAtLeast(1L),
                    stock = stock,
                    isFeaturedCard = false,
                    label = prettyPackName(packId)
                )
            )
        }

        state.featuredCardSummary = ""
        state.featuredCardPrice = 0L

        if (config.featuredCardEnabled) {
            val generatedCard = rollFeaturedCard(config.featuredCardMinRarity)
            if (generatedCard != null && !generatedCard.stack.isEmpty) {
                activeOffers.add(generatedCard)
                state.featuredCardSummary = generatedCard.label
                state.featuredCardPrice = generatedCard.price
            }
        }

        saveState()
    }

    private fun rollFeaturedCard(minRarity: String): ActiveOfferItem? {
        return try {
            val lootClass = Class.forName("com.howlite.cobblemoncards.item.custom.loot.BoosterLootTable")
            val method = lootClass.getMethod("getRandomRewardAbove", String::class.java, String::class.java)
            val stack = method.invoke(null, minRarity, "base") as? ItemStack ?: return null
            if (stack.isEmpty) return null

            var pokemonId = "desconocido"
            var rarity = minRarity
            var isShiny = false

            runCatching {
                val compClass = Class.forName("com.howlite.cobblemoncards.component.ModDataComponents")
                val field = compClass.getField("CARD_DATA")
                @Suppress("UNCHECKED_CAST")
                val compType = field.get(null) as net.minecraft.core.component.DataComponentType<Any>
                val data = stack.get(compType)
                if (data != null) {
                    pokemonId = data.javaClass.getMethod("pokemonId").invoke(data) as String
                    rarity = data.javaClass.getMethod("rarity").invoke(data) as String
                    isShiny = data.javaClass.getMethod("isShiny").invoke(data) as Boolean
                }
            }

            val rarityLower = rarity.lowercase()
            val basePrice = when (rarityLower) {
                "mythic" -> config.featuredCardPriceMythic
                "legendary" -> config.featuredCardPriceLegendary
                "epic" -> config.featuredCardPriceEpic
                else -> config.featuredCardPriceRare
            }
            val explicitShinyPrice = when (rarityLower) {
                "mythic" -> config.featuredCardPriceMythicShiny
                "legendary" -> config.featuredCardPriceLegendaryShiny
                "epic" -> config.featuredCardPriceEpicShiny
                else -> config.featuredCardPriceRareShiny
            }
            val finalPrice = if (isShiny) {
                if (explicitShinyPrice > 0L) explicitShinyPrice
                else (basePrice * config.featuredCardShinyMultiplier).roundToLong()
            } else {
                basePrice
            }.coerceAtLeast(1L)

            val shinyTag = if (isShiny) " Shiny" else ""
            val prettyPoke = pokemonId.substringAfter(':').replaceFirstChar { it.uppercase() }
            val prettyRarity = rarity.replaceFirstChar { it.uppercase() }
            val label = "$prettyPoke$shinyTag ($prettyRarity)"

            ActiveOfferItem(
                stack = stack,
                price = finalPrice,
                stock = config.featuredCardStock,
                isFeaturedCard = true,
                label = label
            )
        } catch (e: Throwable) {
            logger.error("No se pudo generar carta aleatoria de Cobblemon Cards (¿mod cargado?)", e)
            null
        }
    }

    private fun buildCobbleDollarsShop(): Any? {
        return try {
            val offerClass = Class.forName("fr.harmex.cobbledollars.common.api.shop.Offer")
            val offerCtor = offerClass.getConstructor(ItemStack::class.java, BigInteger::class.java, Int::class.javaPrimitiveType)

            val categoryClass = Class.forName("fr.harmex.cobbledollars.common.api.shop.Category")
            val categoryCtor = categoryClass.getConstructor(String::class.java, List::class.java)

            val shopClass = Class.forName("fr.harmex.cobbledollars.common.api.shop.Shop")
            val shopCtor = shopClass.getConstructor(List::class.java)

            val packOffers = ArrayList<Any>()
            val cardOffers = ArrayList<Any>()

            for (entry in activeOffers) {
                val offer = offerCtor.newInstance(entry.stack.copy(), BigInteger.valueOf(entry.price), entry.stock)
                if (entry.isFeaturedCard) {
                    cardOffers.add(offer)
                } else {
                    packOffers.add(offer)
                }
            }

            val categories = ArrayList<Any>()
            if (packOffers.isNotEmpty()) {
                categories.add(categoryCtor.newInstance("Sobres TCG", packOffers))
            }
            if (cardOffers.isNotEmpty()) {
                categories.add(categoryCtor.newInstance("Carta Destacada", cardOffers))
            }

            shopCtor.newInstance(categories)
        } catch (e: Throwable) {
            logger.error("Error construyendo Shop de CobbleDollars 2.0 por reflexión", e)
            null
        }
    }

    private fun applyShopToMerchant(merchant: CobbleMerchant, currentTag: String) {
        val shopObj = buildCobbleDollarsShop() ?: return
        try {
            val inlineClass = Class.forName("fr.harmex.cobbledollars.common.world.item.trading.CobbleDollarsShopHolder\$ShopRef\$Inline")
            val shopClass = Class.forName("fr.harmex.cobbledollars.common.api.shop.Shop")
            val inlineCtor = inlineClass.getConstructor(shopClass)
            val shopRef = inlineCtor.newInstance(shopObj)

            val shopRefInterface = Class.forName("fr.harmex.cobbledollars.common.world.item.trading.CobbleDollarsShopHolder\$ShopRef")
            val setShopRefMethod = merchant.javaClass.getMethod("setShopRef", shopRefInterface)
            setShopRefMethod.invoke(merchant, shopRef)

            val staleTags = merchant.tags.filter { it.startsWith(SLOT_TAG_PREFIX) && it != currentTag }
            for (oldTag in staleTags) {
                merchant.removeTag(oldTag)
            }
            merchant.addTag(currentTag)
        } catch (e: Throwable) {
            logger.error("Error aplicando ShopRef a CobbleMerchant Coleccionista TCG", e)
        }
    }

    private fun applyShopToLoadedVendors(server: MinecraftServer, force: Boolean) {
        if (activeOffers.isEmpty()) {
            generateRotation(getCurrentSlotStartMs())
        }
        val currentTag = "$SLOT_TAG_PREFIX${state.slotStartMs}"
        for (level in server.allLevels) {
            for (entity in level.allEntities) {
                if (entity is CobbleMerchant && entity.tags.contains(VENDOR_TAG)) {
                    if (force || !entity.tags.contains(currentTag)) {
                        applyShopToMerchant(entity, currentTag)
                    }
                }
            }
        }
    }

    fun spawn(player: ServerPlayer, noAi: Boolean = true): Boolean {
        return try {
            if (activeOffers.isEmpty()) {
                generateRotation(getCurrentSlotStartMs())
            }
            val level = player.serverLevel()
            val merchant = CobbleMerchant(CobbleDollarsEntities.COBBLE_MERCHANT, level)
            merchant.moveTo(player.x, player.y, player.z, player.yRot, 0f)
            merchant.customName = Component.literal(config.vendorName).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            merchant.isCustomNameVisible = true
            merchant.isInvulnerable = true
            merchant.setNoAi(noAi)
            merchant.setPersistenceRequired()
            merchant.addTag(VENDOR_TAG)
            applyShopToMerchant(merchant, "$SLOT_TAG_PREFIX${state.slotStartMs}")
            level.addFreshEntity(merchant)
            true
        } catch (e: Throwable) {
            logger.error("Error al spawnear Coleccionista TCG", e)
            false
        }
    }

    fun buildStatusSummary(): String {
        val now = System.currentTimeMillis()
        val nextMs = getNextRotationMs(now)
        val remaining = formatDurationRemaining(nextMs - now)
        val nextFormatted = formatTimestampVet(nextMs)
        val packsText = if (state.selectedPackIds.isEmpty()) "Ninguno" else state.selectedPackIds.joinToString(", ") { prettyPackName(it) }
        val cardText = if (state.featuredCardSummary.isNotBlank()) "${state.featuredCardSummary} (${state.featuredCardPrice}$)" else "Ninguna"
        return "§6§l[${config.vendorName} - ESTADO]\n" +
            "§7Frecuencia: §bCada ${config.rotationIntervalHours}h §7(reloj Venezuela)\n" +
            "§7Próxima rotación: §a$nextFormatted §7(en §e$remaining§7)\n" +
            "§7Sobres actuales: §e$packsText\n" +
            "§7Carta destacada: §d$cardText"
    }

    private fun prettyPackName(packId: String): String {
        val path = packId.substringAfter(':')
        if (path == "booster_pack") return "Sobre Clásico"
        if (path.startsWith("booster_pack_gen")) {
            return "Sobre Gen ${path.removePrefix("booster_pack_gen")}"
        }
        val typeRaw = path.removePrefix("booster_pack_")
        return "Sobre ${typeRaw.replaceFirstChar { it.uppercase() }}"
    }
}
