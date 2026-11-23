package com.desgraciamon.training

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import org.slf4j.LoggerFactory

class TrainingMod : ModInitializer {
    private val logger = LoggerFactory.getLogger("desgraciamon4-training")

    override fun onInitialize() {
        logger.info("Inicializando Desgraciamon Training System...")
        TrainingManager.init()
        XpMultiplierManager.init()

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            TrainingCommands.register(dispatcher)
        }
        logger.info("Desgraciamon Training System inicializado con éxito.")
    }
}

