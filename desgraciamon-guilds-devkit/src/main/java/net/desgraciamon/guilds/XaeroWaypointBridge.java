package net.desgraciamon.guilds;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class XaeroWaypointBridge {
   private static final Logger LOGGER = LoggerFactory.getLogger("DesgraciamonGuilds");
   private static final ResourceLocation ORIGIN = ResourceLocation.fromNamespaceAndPath("desgraciamon_guilds", "guild_waypoints");
   private static boolean failureLogged;

   private XaeroWaypointBridge() {
   }

   static boolean isAvailable() {
      return FabricLoader.getInstance().isModLoaded("xaerominimap");
   }

   static void clear() {
      refresh(
         new GuildSyncPayload(
            false,
            "Sin gremio",
            "#FFFFFF",
            "",
            0,
            BigInteger.ZERO,
            BigInteger.ZERO,
            "",
            "pokeball",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
         )
      );
   }

   static void refresh(GuildSyncPayload payload) {
      if (FabricLoader.getInstance().isModLoaded("xaerominimap")) {
         try {
            Object[] managers = thirdPartyContainer();
            if (managers == null) {
               return;
            }

            Object waypointManager = managers[0];
            Object thirdPartyWaypoints = waypointManager.getClass().getMethod("get", ResourceLocation.class).invoke(waypointManager, ORIGIN);
            thirdPartyWaypoints.getClass().getMethod("clear").invoke(thirdPartyWaypoints);
            failureLogged = false;
         } catch (LinkageError | IllegalArgumentException | ReflectiveOperationException var4) {
            logFailure(var4);
         }
      }
   }

   static List<XaeroWaypointBridge.LocalWaypoint> localWaypoints() {
      List<XaeroWaypointBridge.LocalWaypoint> result = new ArrayList<>();
      if (!FabricLoader.getInstance().isModLoaded("xaerominimap")) {
         return result;
      } else {
         try {
            Object[] managers = currentManagers();
            if (managers == null) {
               return result;
            }

            Object set = managers[1].getClass().getMethod("getWaypoints").invoke(managers[1]);
            Iterable<?> waypoints = (Iterable<?>)set.getClass().getMethod("getWaypoints").invoke(set);
            String dimension = currentDimension();

            for (Object waypoint : waypoints) {
               result.add(
                  new XaeroWaypointBridge.LocalWaypoint(
                     (String)waypoint.getClass().getMethod("getName").invoke(waypoint),
                     (Integer)waypoint.getClass().getMethod("getX").invoke(waypoint),
                     (Integer)waypoint.getClass().getMethod("getY").invoke(waypoint),
                     (Integer)waypoint.getClass().getMethod("getZ").invoke(waypoint),
                     dimension
                  )
               );
            }
         } catch (LinkageError | ClassCastException | ReflectiveOperationException var7) {
            logFailure(var7);
         }

         return result;
      }
   }

   static boolean addLocalWaypoint(String name, String dimension, int x, int y, int z, int color) {
      if (!FabricLoader.getInstance().isModLoaded("xaerominimap")) {
         return false;
      } else if (!dimension.equals(currentDimension())) {
         return false;
      } else {
         try {
            Object[] managers = currentManagers();
            if (managers == null) {
               return false;
            }

            Object waypointSet = managers[1].getClass().getMethod("getWaypoints").invoke(managers[1]);
            Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Constructor<?> constructor = waypointClass.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class);
            Object waypoint = constructor.newInstance(x, y, z, name, "G", color);
            if (invokeWaypointAdd(waypointSet, waypoint, waypointClass)) {
               return true;
            }

            if (waypointSet.getClass().getMethod("getWaypoints").invoke(waypointSet) instanceof Collection<?> collection) {
               return ((Collection<Object>)collection).add(waypoint);
            }
         } catch (LinkageError | IllegalArgumentException | ReflectiveOperationException var14) {
            logFailure(var14);
         }

         return false;
      }
   }

   private static boolean invokeWaypointAdd(Object target, Object waypoint, Class<?> waypointClass) throws ReflectiveOperationException {
      for (Method method : target.getClass().getMethods()) {
         if (method.getName().toLowerCase(Locale.ROOT).contains("add")
            && method.getParameterCount() == 1
            && method.getParameterTypes()[0].isAssignableFrom(waypointClass)) {
            method.invoke(target, waypoint);
            return true;
         }
      }

      return false;
   }

   static boolean importWaypoint(String requestedName) {
      if (!FabricLoader.getInstance().isModLoaded("xaerominimap")) {
         return false;
      } else {
         try {
            Object[] managers = currentManagers();
            if (managers == null) {
               return false;
            }

            Object waypointsManager = managers[1];
            Object set = waypointsManager.getClass().getMethod("getWaypoints").invoke(waypointsManager);

            for (Object waypoint : (Iterable)set.getClass().getMethod("getWaypoints").invoke(set)) {
               String name = (String)waypoint.getClass().getMethod("getName").invoke(waypoint);
               if (name.equalsIgnoreCase(requestedName)) {
                  Minecraft client = Minecraft.getInstance();
                  if (client.player == null) {
                     return false;
                  }

                  int x = (Integer)waypoint.getClass().getMethod("getX").invoke(waypoint);
                  int y = (Integer)waypoint.getClass().getMethod("getY").invoke(waypoint);
                  int z = (Integer)waypoint.getClass().getMethod("getZ").invoke(waypoint);
                  String dimension = client.player.level().dimension().location().toString();
                  ClientPlayNetworking.send(new GuildWaypointImportPayload(name, dimension, x, y, z, false));
                  client.player.sendSystemMessage(Component.literal("Waypoint Xaero enviado al servidor: " + name));
                  return true;
               }
            }
         } catch (LinkageError | IllegalArgumentException | ReflectiveOperationException var13) {
            logFailure(var13);
         }

         return false;
      }
   }

   private static Object[] thirdPartyContainer() throws ReflectiveOperationException {
      Object[] managers = currentManagers();
      if (managers == null) {
         return null;
      } else {
         Object world = managers[1].getClass().getMethod("getCurrentWorld").invoke(managers[1]);
         if (world == null) {
            return null;
         } else {
            Object container = world.getClass().getMethod("getContainer").invoke(world);
            Object thirdPartyManager = container.getClass().getMethod("getThirdPartyWaypointManager").invoke(container);
            return new Object[]{thirdPartyManager, managers[1]};
         }
      }
   }

   private static Object[] currentManagers() throws ReflectiveOperationException {
      Class<?> sessionClass = Class.forName("xaero.common.XaeroMinimapSession");
      Object session = sessionClass.getMethod("getCurrentSession").invoke(null);
      if (session == null) {
         return null;
      } else {
         Object manager = sessionClass.getMethod("getWaypointsManager").invoke(session);
         return manager == null ? null : new Object[]{session, manager};
      }
   }

   private static String currentDimension() {
      Minecraft client = Minecraft.getInstance();
      return client.player == null ? "" : client.player.level().dimension().location().toString();
   }

   private static void logFailure(Throwable exception) {
      if (!failureLogged) {
         LOGGER.warn("Xaero waypoint integration is unavailable; guild waypoints remain synced in the guild screen.", exception);
         failureLogged = true;
      }
   }

   static record LocalWaypoint(String name, int x, int y, int z, String dimension) {
   }
}
