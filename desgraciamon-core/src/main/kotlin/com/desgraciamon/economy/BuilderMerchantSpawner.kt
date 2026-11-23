package com.desgraciamon.economy

import com.google.gson.JsonParser
import fr.harmex.cobbledollars.common.world.entity.CobbleDollarsEntities
import fr.harmex.cobbledollars.common.world.entity.CobbleMerchant
import fr.harmex.cobbledollars.common.world.item.trading.shop.Category
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer
import fr.harmex.cobbledollars.common.world.item.trading.shop.Shop
import net.minecraft.ChatFormatting
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigInteger

object BuilderMerchantSpawner {
    private val logger = LoggerFactory.getLogger("desgraciamon4-economy")
    private val configFile = File("config/desgraciamon4_economy/builder_shop.json")

    fun loadBuilderShop(): Shop {
        val shop = Shop()
        if (!configFile.exists()) {
            logger.warn("builder_shop.json no encontrado en ${configFile.absolutePath}")
            return shop
        }
        try {
            val rootArray = JsonParser.parseReader(configFile.reader(Charsets.UTF_8)).asJsonArray
            for (element in rootArray) {
                if (!element.isJsonObject) continue
                val obj = element.asJsonObject
                for ((catName, offersElement) in obj.entrySet()) {
                    val offersArray = offersElement.asJsonArray
                    val offersList = ArrayList<Offer>()
                    for (offerEl in offersArray) {
                        val offerObj = offerEl.asJsonObject
                        val itemId = offerObj.get("item").asString
                        val price = offerObj.get("price").asLong
                        val item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId))
                        if (item != null) {
                            offersList.add(Offer(ItemStack(item), BigInteger.valueOf(price), -1))
                        }
                    }
                    shop.add(Category(catName, offersList))
                }
            }
            logger.info("Cargadas ${shop.size} categorías de construcción para el Maestro Constructor.")
        } catch (e: Throwable) {
            logger.error("Error al cargar builder_shop.json", e)
        }
        return shop
    }

    fun spawn(player: ServerPlayer, noAi: Boolean = true): Boolean {
        return try {
            val level = player.serverLevel()
            val merchant = CobbleMerchant(CobbleDollarsEntities.COBBLE_MERCHANT, level)
            merchant.moveTo(player.x, player.y, player.z, player.yRot, 0f)
            merchant.customName = Component.literal("Maestro Constructor").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            merchant.isCustomNameVisible = true
            merchant.isInvulnerable = true
            merchant.setNoAi(noAi)
            merchant.setPersistenceRequired()
            merchant.shop = loadBuilderShop()
            level.addFreshEntity(merchant)
            true
        } catch (e: Throwable) {
            logger.error("Error al spawnear Maestro Constructor", e)
            false
        }
    }
}

