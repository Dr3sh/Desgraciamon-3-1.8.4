package de.maxhenkel.voicechat;

import de.maxhenkel.voicechat.integration.clothconfig.ClothConfig;
import de.maxhenkel.voicechat.voice.client.ClientManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
public class FabricVoicechatClientMod extends VoicechatClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        initializeClient();

        ClothConfig.init();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("voicechat")
                .then(ClientCommandManager.literal("reconnect").executes(ctx -> {
                    ClientManager.getPlayerStateManager().setDisabled(false);
                    ClientManager.instance().reconnect();
                    ctx.getSource().sendFeedback(Component.literal("§a[Dr3sh Studios Voice Chat] Reconectando chat de voz..."));
                    return 1;
                }))
                .then(ClientCommandManager.literal("disconnect").executes(ctx -> {
                    ClientManager.getPlayerStateManager().setDisabled(true);
                    ClientManager.instance().disconnect();
                    ctx.getSource().sendFeedback(Component.literal("§e[Dr3sh Studios Voice Chat] Chat de voz desconectado."));
                    return 1;
                }))
            );

            dispatcher.register(ClientCommandManager.literal("voz")
                .then(ClientCommandManager.literal("reconectar").executes(ctx -> {
                    ClientManager.getPlayerStateManager().setDisabled(false);
                    ClientManager.instance().reconnect();
                    ctx.getSource().sendFeedback(Component.literal("§a[Dr3sh Studios Voice Chat] Reconectando chat de voz..."));
                    return 1;
                }))
                .then(ClientCommandManager.literal("desconectar").executes(ctx -> {
                    ClientManager.getPlayerStateManager().setDisabled(true);
                    ClientManager.instance().disconnect();
                    ctx.getSource().sendFeedback(Component.literal("§e[Dr3sh Studios Voice Chat] Chat de voz desconectado."));
                    return 1;
                }))
            );

            dispatcher.register(ClientCommandManager.literal("dr3shvoice")
                .redirect(dispatcher.getRoot().getChild("voicechat"))
            );
        });
    }

}
