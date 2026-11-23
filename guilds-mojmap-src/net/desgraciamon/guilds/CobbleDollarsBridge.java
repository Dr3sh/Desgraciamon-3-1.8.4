package net.desgraciamon.guilds;

import java.math.BigInteger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

final class CobbleDollarsBridge {
   private static final String API = "fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt";

   private CobbleDollarsBridge() {
   }

   static boolean isAvailable() {
      return FabricLoader.getInstance().isModLoaded("cobbledollars");
   }

   static BigInteger balance(ServerPlayer player) throws ReflectiveOperationException {
      return (BigInteger)api().getMethod("getCobbleDollars", Player.class).invoke(null, player);
   }

   static void setBalance(ServerPlayer player, BigInteger amount) throws ReflectiveOperationException {
      Class<?> api = api();
      api.getMethod("setCobbleDollars", Player.class, BigInteger.class).invoke(null, player, amount);
      api.getMethod("updateCobbleDollarsAccount", ServerPlayer.class).invoke(null, player);
   }

   private static Class<?> api() throws ClassNotFoundException {
      if (!isAvailable()) {
         throw new ClassNotFoundException("CobbleDollars is not installed");
      } else {
         try {
            return Class.forName("fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt");
         } catch (LinkageError var1) {
            throw new ClassNotFoundException("CobbleDollars API could not be loaded", var1);
         }
      }
   }
}
