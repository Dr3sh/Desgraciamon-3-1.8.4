package net.desgraciamon.guilds;

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
import net.minecraft.class_2561;
import net.minecraft.class_304;
import net.minecraft.class_310;
import net.minecraft.class_3675.class_307;

public final class DesgraciamonGuildsClient implements ClientModInitializer {
   private static final class_304 OPEN_GUILD = KeyBindingHelper.registerKeyBinding(
      new class_304("key.desgraciamon_guilds.open", class_307.field_1668, 71, "key.categories.misc")
   );

   public void onInitializeClient() {
      GuildClientConfig.load();
      ClientPlayNetworking.registerGlobalReceiver(
         GuildSynergyPayload.TYPE, (payload, context) -> context.client().execute(() -> handleSynergy(payload, context.client()))
      );
      ClientPlayNetworking.registerGlobalReceiver(GuildSyncPayload.TYPE, (payload, context) -> context.client().execute(() -> {
            GuildClientState.update(payload);
            XaeroWaypointBridge.refresh(payload);
            if (context.client().field_1755 instanceof GuildScreen screen) {
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

         while (OPEN_GUILD.method_1436()) {
            GuildUiSound.play();
            client.method_1507(new GuildScreen(true));
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
                                 class_2561.method_43470(
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
      if (class_310.method_1551().method_1562() != null) {
         ClientPlayNetworking.send(new GuildRequestPayload("SYNC", "", 0));
      }
   }

   private static void handleSynergy(GuildSynergyPayload payload, class_310 client) {
      GuildClientState.synergyNearbyCount = payload.nearbyCount();
      GuildClientState.synergyGuildName = payload.guildName();
      GuildClientState.synergyGuildColorHex = payload.guildColorHex();
   }
}
