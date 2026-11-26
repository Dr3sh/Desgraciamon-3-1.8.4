package com.desgraciamon.economy

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import org.slf4j.LoggerFactory

class EconomyMod : ModInitializer {
    private var tickCounter = 0

    override fun onInitialize() {
        logger.info("Iniciando Desgraciamon 3 Economy (Mercado Fluctuante)")

        // Registro de paquetes de red
        PayloadTypeRegistry.playS2C().register(MarketSyncPayload.TYPE, MarketSyncPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(MarketAlertTogglePayload.TYPE, MarketAlertTogglePayload.CODEC)
        PayloadTypeRegistry.playC2S().register(EconomySellPayload.TYPE, EconomySellPayload.CODEC)

        TextCatalog.load(
            mapOf(
                "sell.success" to "<color=#55D69E>Has vendido <bold>{amount}x {item}</bold> por <bold>{total}$</bold> (promedio: {unit_price}$/ud) {trend}</color>",
                "sell.empty_hand" to "<color=#FF5555>Debes sostener en la mano principal el ítem que deseas vender.</color>",
                "sell.not_accepted" to "<color=#FF5555>El mercado no acepta este ítem para su compra.</color>",
                "sell.no_items" to "<color=#FF5555>No tienes suficientes unidades de este ítem en tu inventario.</color>",
                "price.info" to "<color=#55D69E>Cotización de <bold>{item}</bold>:</color> Base: <color=#FFD04E>{base_price}$</color> | Actual: <color=#78DCA9>{unit_price}$</color> | Stack (x64): <color=#FFD04E>{stack_price}$</color> | {trend}",
                "market.header" to "<prism=#55D69E,#FFD04E><bold>═══ [BOLSA DE VALORES DESGRACIAMON] ═══</bold></prism>",
                "market.row" to "<color=#E5ECE8>{item}:</color> <color=#FFD04E><bold>{price}$</bold></color> {trend}",
                "market.reload" to "<color=#55D69E>Configuración de mercado y textos recargados correctamente.</color>",
                "market.alert.crash" to "<prism=#FF5555,#FFD04E><bold>[MERCADO]</bold></prism> <color=#FF8888>Gran caída de precios:</color> <color=#FFFFFF>{player}</color> vendió <color=#FFD04E><bold>{amount}x {item}</bold></color> <color=#FF5555>(-{percent}% caída)</color>. Cotización actual: <color=#78DCA9>{price}$</color>",
                "trend.up" to "<color=#55FF55>+{percent}% (Alta Demanda)</color>",
                "trend.down" to "<color=#FF5555>-{percent}% (Saturado)</color>",
                "trend.stable" to "<color=#AAAAAA>{percent}% (Estable)</color>",
                "error.money" to "<color=#FF5555>Aviso: CobbleDollars no está disponible para acreditar los fondos.</color>"
            )
        )

        MarketStore.load()
        TcgMerchantManager.loadConfig()
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            MarketStore.initForServer(server)
            MarketServerPreferences.initForServer(server)
            TcgMerchantManager.initForServer(server)
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            EconomyCommands.register(dispatcher)
            com.desgraciamon.guilds.GuildSynergyCommands.register(dispatcher)
        }

        // Manejador del toggle de alertas desde el menú F7
        ServerPlayNetworking.registerGlobalReceiver(MarketAlertTogglePayload.TYPE) { payload, context ->
            val player = context.player()
            MarketServerPreferences.setAlertsEnabled(player.uuid, payload.enabled)
        }

        // Manejador de venta directa del banquero
        ServerPlayNetworking.registerGlobalReceiver(EconomySellPayload.TYPE) { _, context ->
            val player = context.player()
            context.server().execute {
                BankHook.onBankSell(player)
            }
        }

        // Sincronizar mercado al conectarse el jugador
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            MarketSyncServer.syncToPlayer(handler.player)
        }

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            if (MarketStore.state.trendingItems.isEmpty() || MarketEngine.isResetDue(System.currentTimeMillis())) {
                MarketEngine.triggerEconomyReset(null, broadcastMessage = false)
            }
            CobbleDollarsBankSync.syncPrices()
            TcgMerchantManager.initOnServerStarted(server)
        }

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register { entity, level ->
            TcgMerchantManager.onEntityLoad(entity, level.server)
        }

        // Ticker del servidor para el decaimiento de la saturación y reseteo cíclico
        ServerTickEvents.END_SERVER_TICK.register { server ->
            val currentTick = ++tickCounter
            if (currentTick % 100 == 0) {
                TcgMerchantManager.tick(server)
            }
            if (currentTick % 1200 == 0) { // Cada 60 segundos (1200 ticks)
                val now = System.currentTimeMillis()
                if (MarketEngine.isResetDue(now)) {
                    MarketEngine.triggerEconomyReset(server, broadcastMessage = true)
                } else {
                    val intervalTicks = (MarketStore.config.decayIntervalMinutes * 60 * 20).coerceAtLeast(1200)
                    if (tickCounter % intervalTicks == 0) {
                        MarketEngine.decayStep()
                        CobbleDollarsBankSync.syncPrices()
                        MarketSyncServer.broadcastSync(server)
                    }
                }
            }
        }

        ServerLifecycleEvents.SERVER_STOPPING.register {
            MarketStore.saveState()
            MarketServerPreferences.save()
        }
    }

    companion object {
        internal val logger = LoggerFactory.getLogger("desgraciamon4-economy")
    }
}
