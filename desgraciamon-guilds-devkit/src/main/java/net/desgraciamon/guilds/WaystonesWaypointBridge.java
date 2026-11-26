package net.desgraciamon.guilds;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Puente por reflexion hacia el mod Waystones (BlayTheNinth) para listar los waystones activados
 * del jugador en el menu de gremio y permitir compartirlos como waypoints del gremio.
 */
public final class WaystonesWaypointBridge {
   private WaystonesWaypointBridge() {
   }

   public static boolean isAvailable() {
      return FabricLoader.getInstance().isModLoaded("waystones");
   }

   public static List<XaeroWaypointBridge.LocalWaypoint> localWaystones() {
      List<XaeroWaypointBridge.LocalWaypoint> result = new ArrayList<>();
      if (!isAvailable()) {
         return result;
      }
      Minecraft mc = Minecraft.getInstance();
      if (mc.player == null) {
         return result;
      }
      try {
         Class<?> apiClass = Class.forName("net.blay09.mods.waystones.api.WaystonesAPI");
         Method getActivated = apiClass.getMethod("getActivatedWaystones", Player.class);
         Object rawCol = getActivated.invoke(null, mc.player);
         if (!(rawCol instanceof Collection<?> waystones)) {
            return result;
         }
         for (Object w : waystones) {
            if (w == null) continue;
            Boolean valid = (Boolean) w.getClass().getMethod("isValid").invoke(w);
            if (valid != null && !valid) continue;

            Component effName = null;
            try {
               effName = (Component) w.getClass().getMethod("getEffectiveName").invoke(w);
            } catch (Throwable ignored) {
               effName = (Component) w.getClass().getMethod("getName").invoke(w);
            }
            String rawName = effName != null ? effName.getString().trim() : "Waystone";
            if (rawName.isBlank()) rawName = "Waystone";
            String label = "[Waystone] " + rawName;
            if (label.length() > 64) {
               label = label.substring(0, 64);
            }

            ResourceKey<Level> dimKey = (ResourceKey<Level>) w.getClass().getMethod("getDimension").invoke(w);
            String dim = dimKey != null ? dimKey.location().toString() : "minecraft:overworld";
            BlockPos pos = (BlockPos) w.getClass().getMethod("getPos").invoke(w);
            if (pos != null) {
               result.add(new XaeroWaypointBridge.LocalWaypoint(label, pos.getX(), pos.getY(), pos.getZ(), dim));
            }
         }
      } catch (Throwable ignored) {
      }
      return result;
   }
}
