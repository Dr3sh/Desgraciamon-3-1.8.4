package net.desgraciamon.guilds;

import net.minecraft.class_2960;
import net.minecraft.class_8710;
import net.minecraft.class_9129;
import net.minecraft.class_9139;
import net.minecraft.class_8710.class_9154;

record GuildWaypointImportPayload(String name, String dimension, int x, int y, int z, boolean share) implements class_8710 {
   static final class_9154<GuildWaypointImportPayload> TYPE = new class_9154(class_2960.method_60655("desgraciamon_guilds", "waypoint_import"));
   static final class_9139<class_9129, GuildWaypointImportPayload> CODEC = new class_9139<class_9129, GuildWaypointImportPayload>() {
      public GuildWaypointImportPayload decode(class_9129 buffer) {
         return new GuildWaypointImportPayload(
            buffer.method_10800(64), buffer.method_10800(128), buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readBoolean()
         );
      }

      public void encode(class_9129 buffer, GuildWaypointImportPayload payload) {
         buffer.method_10788(payload.name(), 64);
         buffer.method_10788(payload.dimension(), 128);
         buffer.method_53002(payload.x());
         buffer.method_53002(payload.y());
         buffer.method_53002(payload.z());
         buffer.method_52964(payload.share());
      }
   };

   public class_9154<? extends class_8710> method_56479() {
      return TYPE;
   }
}
