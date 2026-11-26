package com.desgraciamon.showdownarena

import com.cobblemon.mod.common.client.gui.interact.battleRequest.BattleConfigureGUI
import com.desgraciamon.showdownarena.mixin.BattleConfigureGUIAccessor
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionProvider
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import net.minecraft.resources.ResourceLocation
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

class ShowdownArenaClient : ClientModInitializer {
    override fun onInitializeClient() {
        // 1. Inicializar clientes de zona (PvP Showdown y Mercado GTS)
        CoreShaderRegistrationCallback.EVENT.register { context ->
            context.register(
                ResourceLocation.fromNamespaceAndPath("showdown_arena", "concert_beam"),
                DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL
            ) { program ->
                ArenaZoneClient.concertBeamShader = program
            }
            context.register(
                ResourceLocation.fromNamespaceAndPath("showdown_arena", "dome_energy"),
                DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL
            ) { program ->
                ArenaZoneClient.domeEnergyShader = program
            }
        }
        ArenaZoneClient.init()
        ArenaMusicClient.init()
        SkyLogoClient.init()
        GtsZoneClient.init()
        ShowdownBattlePartyClient.init()

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            ArenaMusicClient.tick(client)
            ShowdownBattlePartyClient.tick(client)
        }

        // Limpiar estado en el cliente al desconectarse de cualquier mundo/servidor
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            ArenaZoneClient.currentZone = null
            ArenaZoneClient.isEnabled = false
            ArenaZoneClient.insideDarkRoomFactor = 0.0f
            ArenaMusicClient.reset()
            SkyLogoClient.reset()
            GtsZoneClient.currentZone = null
            GtsZoneClient.isEnabled = false
            ShowdownBattlePartyClient.reset()
        }

        // 2. Renderizado de campos de fuerza y mallas holográficas en el mundo (AFTER_TRANSLUCENT para respetar depth de bloques y modelos)
        WorldRenderEvents.AFTER_TRANSLUCENT.register { context ->
            ArenaZoneClient.render(context)
            SkyLogoClient.render(context)
            GtsZoneClient.render(context)
        }

        // 3. HUD Overlays para anuncios al entrar a las zonas
        HudRenderCallback.EVENT.register { graphics, _ ->
            ArenaZoneClient.renderHud(graphics)
            GtsZoneClient.renderHud(graphics)
        }

        // 4. Integración en la pantalla base de desafío de Cobblemon (BattleConfigureGUI)
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is BattleConfigureGUI) return@register
            val access = screen as BattleConfigureGUIAccessor
            if (access.getActiveRequest() != null || access.getActiveTeamRequest() != null) return@register

            val packet = access.getPacket()
            val target = Minecraft.getInstance().level?.getPlayerByUUID(packet.targetId) ?: return@register

            // Botón nativo visible y estilizado para abrir el modo Showdown
            val button = Button.builder(Component.literal("Otro tipo de combate")) {
                Minecraft.getInstance().setScreen(ShowdownBattleScreen(screen, target.gameProfile.name))
            }
                .bounds(screen.width / 2 - 70, minOf(screen.height - 26, screen.height / 2 + 95), 140, 20)
                .tooltip(Tooltip.create(Component.literal("Configurar combate con formatos y generaciones de Pokémon Showdown")))
                .build()

            Screens.getButtons(screen).add(button)
            ScreenMouseEvents.afterMouseClick(screen).register { _, _, _, btn ->
                if (Minecraft.getInstance().screen === screen && btn == 0 && button.isHovered) {
                    button.onPress()
                }
            }
        }

        val formatSuggestions = SuggestionProvider<FabricClientCommandSource> { _, builder ->
            ArenaFormatCatalog.formats.forEach { format ->
                if (format.id.startsWith(builder.remaining.lowercase())) {
                    builder.suggest(format.id)
                }
            }
            builder.buildFuture()
        }

        // 5. Registro de comandos cliente para abrir la GUI directamente (sin requerir permisos ni otro jugador)
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val registerGuiCommand = { name: String ->
                dispatcher.register(
                    ClientCommandManager.literal(name)
                        .executes {
                            // Abrir en MODO TESTEO / SOLO (sin otro jugador)
                            Minecraft.getInstance().execute {
                                Minecraft.getInstance().setScreen(ShowdownBattleScreen(null, null))
                            }
                            1
                        }
                        .then(ClientCommandManager.argument("target", StringArgumentType.word())
                            .suggests { _, builder ->
                                Minecraft.getInstance().connection?.onlinePlayers?.forEach { p ->
                                    val n = p.profile.name
                                    if (n.startsWith(builder.remaining, ignoreCase = true)) {
                                        builder.suggest(n)
                                    }
                                }
                                builder.buildFuture()
                            }
                            .executes { context ->
                                val targetName = StringArgumentType.getString(context, "target")
                                Minecraft.getInstance().execute {
                                    Minecraft.getInstance().setScreen(ShowdownBattleScreen(null, targetName))
                                }
                                1
                            }
                        )
                )
            }

            registerGuiCommand("showdown")
            registerGuiCommand("combategui")
            registerGuiCommand("debugshowdown")
            registerGuiCommand("showdowntest")
        }
    }
}
