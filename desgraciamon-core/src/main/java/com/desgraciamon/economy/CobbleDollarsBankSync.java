package com.desgraciamon.economy;

import fr.harmex.cobbledollars.common.CobbleDollars;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Bank;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import net.minecraft.core.registries.BuiltInRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;

public class CobbleDollarsBankSync {
    private static final Logger LOGGER = LoggerFactory.getLogger("desgraciamon4-economy");

    public static void syncPrices() {
        if (!CobbleDollarsBridge.INSTANCE.isAvailable()) return;
        try {
            Bank bank = CobbleDollars.INSTANCE.getBankConfig().getBank();
            if (bank == null) return;

            for (Offer offer : bank) {
                if (offer.getItem() == null) continue;
                String itemId = BuiltInRegistries.ITEM.getKey(offer.getItem().getItem()).toString();
                ItemMarketConfig config = MarketEngine.INSTANCE.getItemConfig(itemId);
                if (config == null) continue;

                double supply = MarketEngine.INSTANCE.getSupply(itemId);
                boolean isTrending = MarketEngine.INSTANCE.isTrending(itemId);
                double dynamicPrice = MarketEngine.INSTANCE.calculateUnitPrice(config, supply, isTrending);
                offer.setPrice(BigInteger.valueOf((long) dynamicPrice));
            }
        } catch (Throwable t) {
            LOGGER.warn("No se pudieron sincronizar los precios con CobbleDollars Bank: " + t.getMessage());
        }
    }
}

