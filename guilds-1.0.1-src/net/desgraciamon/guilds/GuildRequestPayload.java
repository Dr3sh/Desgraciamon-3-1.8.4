package net.desgraciamon.guilds;

import java.math.BigInteger;
import net.minecraft.class_2960;
import net.minecraft.class_8710;
import net.minecraft.class_9129;
import net.minecraft.class_9139;
import net.minecraft.class_8710.class_9154;

record GuildRequestPayload(String action, String argument, BigInteger amount) implements class_8710 {
   static final class_9154<GuildRequestPayload> TYPE = new class_9154(class_2960.method_60655("desgraciamon_guilds", "guild_request"));
   static final class_9139<class_9129, GuildRequestPayload> CODEC = new class_9139<class_9129, GuildRequestPayload>() {
      public GuildRequestPayload decode(class_9129 buffer) {
         String action = buffer.method_10800(32);
         String argument = buffer.method_10800(512);

         try {
            return new GuildRequestPayload(action, argument, new BigInteger(buffer.method_10800(128)));
         } catch (NumberFormatException var5) {
            return new GuildRequestPayload("INVALID", "", BigInteger.ZERO);
         }
      }

      public void encode(class_9129 buffer, GuildRequestPayload payload) {
         buffer.method_10788(payload.action(), 32);
         buffer.method_10788(payload.argument(), 512);
         buffer.method_10788(payload.amount().toString(), 128);
      }
   };

   GuildRequestPayload(String action, String argument, int amount) {
      this(action, argument, BigInteger.valueOf((long)amount));
   }

   public class_9154<? extends class_8710> method_56479() {
      return TYPE;
   }
}
