package com.desgraciamon.hud

import com.mojang.brigadier.CommandDispatcher
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.Component

class CustomHudClient : ClientModInitializer {
    override fun onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            registerCommands(dispatcher, "hud")
            registerCommands(dispatcher, "customhud")
            registerCommands(dispatcher, "desgraciamonhud")
        }
    }

    private fun registerCommands(dispatcher: CommandDispatcher<FabricClientCommandSource>, root: String) {
        dispatcher.register(
            ClientCommandManager.literal(root)
                .executes { ctx ->
                    val newState = CustomHudConfig.toggle()
                    sendFeedback(ctx.source, newState)
                    1
                }
                .then(
                    ClientCommandManager.literal("toggle")
                        .executes { ctx ->
                            val newState = CustomHudConfig.toggle()
                            sendFeedback(ctx.source, newState)
                            1
                        }
                )
                .then(
                    ClientCommandManager.literal("on")
                        .executes { ctx ->
                            CustomHudConfig.setEnabled(true)
                            sendFeedback(ctx.source, true)
                            1
                        }
                )
                .then(
                    ClientCommandManager.literal("enable")
                        .executes { ctx ->
                            CustomHudConfig.setEnabled(true)
                            sendFeedback(ctx.source, true)
                            1
                        }
                )
                .then(
                    ClientCommandManager.literal("off")
                        .executes { ctx ->
                            CustomHudConfig.setEnabled(false)
                            sendFeedback(ctx.source, false)
                            1
                        }
                )
                .then(
                    ClientCommandManager.literal("disable")
                        .executes { ctx ->
                            CustomHudConfig.setEnabled(false)
                            sendFeedback(ctx.source, false)
                            1
                        }
                )
                .then(
                    ClientCommandManager.literal("status")
                        .executes { ctx ->
                            val isEnabled = CustomHudConfig.isEnabled()
                            val statusText = if (isEnabled) "§aActivado" else "§cDesactivado"
                            ctx.source.sendFeedback(Component.literal("§6[Desgraciamon HUD] §7Estado actual: $statusText"))
                            1
                        }
                )
        )
    }

    private fun sendFeedback(source: FabricClientCommandSource, enabled: Boolean) {
        val msg = if (enabled) {
            "§6[Desgraciamon HUD] §aActivado§f: Barra de corazones centrada y barra de hambre oculta."
        } else {
            "§6[Desgraciamon HUD] §cDesactivado§f: Restaurado al HUD estándar de Minecraft."
        }
        source.sendFeedback(Component.literal(msg))
    }
}

