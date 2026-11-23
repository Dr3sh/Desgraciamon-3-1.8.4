package net.desgraciamon.guilds;

import com.mojang.blaze3d.platform.InputConstants.Type;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.Disconnect;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.Join;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class DesgraciamonGuildsClient implements ClientModInitializer {
   private static final KeyMapping OPEN_GUILD = KeyBindingHelper.registerKeyBinding(
      new KeyMapping("key.desgraciamon_guilds.open", Type.KEYSYM, 71, "key.categories.misc")
   );

   public void onInitializeClient() {
      GuildClientConfig.load();
      ClientPlayNetworking.registerGlobalReceiver(
         GuildSynergyPayload.TYPE, (payload, context) -> context.client().execute(() -> handleSynergy(payload, context.client()))
      );
      ClientPlayNetworking.registerGlobalReceiver(GuildSyncPayload.TYPE, (payload, context) -> context.client().execute(() -> {
            GuildClientState.update(payload);
            XaeroWaypointBridge.refresh(payload);
            if (context.client().screen instanceof GuildScreen screen) {
               screen.onSyncUpdated();
            }
         }));
      ClientPlayConnectionEvents.JOIN.register((Join)(handler, sender, client) -> requestSync());
      ClientPlayConnectionEvents.DISCONNECT.register((Disconnect)(handler, client) -> {
         GuildClientState.reset();
         XaeroWaypointBridge.clear();
      });
      ClientTickEvents.END_CLIENT_TICK.register((EndTick)client -> {
         GuildSynergyHudHelper.clientTick(client);

         while (OPEN_GUILD.consumeClick()) {
            GuildUiSound.play();
            client.setScreen(new GuildScreen(true));
            requestSync();
         }
      });
      ClientCommandRegistrationCallback.EVENT
         .register(
            (ClientCommandRegistrationCallback)(dispatcher, registryAccess) -> dispatcher.register(
                  (LiteralArgumentBuilder)ClientCommandManager.literal("minametag")
                     .executes(
                        ctx -> {
                           boolean enabled = !GuildClientState.debugShowOwnNameTag;
                           GuildClientState.debugShowOwnNameTag = enabled;
                           syncBadgesNameTagFlag(enabled);
                           ((FabricClientCommandSource)ctx.getSource())
                              .sendFeedback(
                                 Component.literal(
                                    enabled
                                       ? "§a[DEBUG] §fVista de tu propio NameTag §aACTIVADA§f. Pulsa §eF5§f (tercera persona) para ver tu nombre, insignias y <Gremio>."
                                       : "§c[DEBUG] §fVista de tu propio NameTag §cDESACTIVADA§f."
                                 )
                              );
                           return 1;
                        }
                     )
               )
         );
   }

   private static void syncBadgesNameTagFlag(boolean enabled) {
      try {
         Class<?> badgesState = Class.forName("com.desgraciamon.badges.BadgesClientState");
         badgesState.getMethod("setDebugShowOwnNameTag", boolean.class).invoke(null, enabled);
      } catch (Throwable var2) {
      }
   }

   private static void requestSync() {
      if (Minecraft.getInstance().getConnection() != null) {
         ClientPlayNetworking.send(new GuildRequestPayload("SYNC", "", 0));
      }
   }

   private static void handleSynergy(GuildSynergyPayload payload, Minecraft client) {
      GuildClientState.synergyNearbyCount = payload.nearbyCount();
      GuildClientState.synergyGuildName = payload.guildName();
      GuildClientState.synergyGuildColorHex = payload.guildColorHex();
   }
}
