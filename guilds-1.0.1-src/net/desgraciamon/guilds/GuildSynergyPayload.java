package net.desgraciamon.guilds;

import net.minecraft.class_2540;
import net.minecraft.class_2960;
import net.minecraft.class_8710;
import net.minecraft.class_9139;
import net.minecraft.class_8710.class_9154;

public record GuildSynergyPayload(int nearbyCount, String guildName, String guildColorHex) implements class_8710 {
   public static final class_9154<GuildSynergyPayload> TYPE = new class_9154(class_2960.method_60655("desgraciamon_guilds", "guild_synergy"));
   public static final class_9139<class_2540, GuildSynergyPayload> CODEC = class_9139.method_56437((buf, payload) -> {
      buf.method_10804(payload.nearbyCount());
      buf.method_10788(payload.guildName(), 64);
      buf.method_10788(payload.guildColorHex(), 16);
   }, buf -> new GuildSynergyPayload(buf.method_10816(), buf.method_10800(64), buf.method_10800(16)));

   public class_9154<? extends class_8710> method_56479() {
      return TYPE;
   }
}
