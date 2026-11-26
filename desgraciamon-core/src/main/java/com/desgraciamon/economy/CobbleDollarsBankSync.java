package com.desgraciamon.economy;

import fr.harmex.cobbledollars.common.CobbleDollars;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.Map;

public class CobbleDollarsBankSync {
    private static final Logger LOGGER = LoggerFactory.getLogger("desgraciamon4-economy");

    public static void syncPrices() {
        if (!CobbleDollarsBridge.INSTANCE.isAvailable()) return;
        try {
            Object bankConfig = CobbleDollars.INSTANCE.getBankConfig();
            if (bankConfig == null) return;

            Field cfgBankField = bankConfig.getClass().getDeclaredField("bank");
            cfgBankField.setAccessible(true);
            Object bankRefOrBank = cfgBankField.get(bankConfig);
            if (bankRefOrBank == null) return;

            Object bank = bankRefOrBank;
            if (bankRefOrBank.getClass().getName().contains("BankRef")) {
                Field valField = bankRefOrBank.getClass().getDeclaredField("value");
                valField.setAccessible(true);
                bank = valField.get(bankRefOrBank);
            }
            if (bank == null) return;

            Field offersField = bank.getClass().getDeclaredField("offers");
            offersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Object, Object> currentOffers = (Map<Object, Object>) offersField.get(bank);
            if (currentOffers == null || currentOffers.isEmpty()) return;

            for (Object offer : currentOffers.keySet()) {
                Field ingField = offer.getClass().getDeclaredField("ingredient");
                ingField.setAccessible(true);
                Ingredient ingredient = (Ingredient) ingField.get(offer);
                if (ingredient == null) continue;
                ItemStack[] stacks = ingredient.getItems();
                if (stacks.length == 0) continue;

                String itemId = BuiltInRegistries.ITEM.getKey(stacks[0].getItem()).toString();
                ItemMarketConfig config = MarketEngine.INSTANCE.getItemConfig(itemId);
                if (config == null) continue;

                double supply = MarketEngine.INSTANCE.getSupply(itemId);
                boolean isTrending = MarketEngine.INSTANCE.isTrending(itemId);
                double dynamicPrice = MarketEngine.INSTANCE.calculateUnitPrice(config, supply, isTrending);
                BigInteger newPrice = BigInteger.valueOf(Math.max(1L, (long) dynamicPrice));

                Field priceField = offer.getClass().getDeclaredField("price");
                priceField.setAccessible(true);
                BigInteger currentPrice = (BigInteger) priceField.get(offer);
                if (!newPrice.equals(currentPrice)) {
                    priceField.set(offer, newPrice);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("No se pudieron sincronizar los precios con CobbleDollars Bank: " + t.getMessage());
        }
    }
}

