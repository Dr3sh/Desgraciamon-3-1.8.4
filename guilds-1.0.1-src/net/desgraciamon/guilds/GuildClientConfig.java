package net.desgraciamon.guilds;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import net.fabricmc.loader.api.FabricLoader;

public final class GuildClientConfig {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final File FILE = FabricLoader.getInstance().getConfigDir().resolve("desgraciamon_guild_client.json").toFile();
   public static GuildClientConfig.SynergyBarMode synergyBarMode = GuildClientConfig.SynergyBarMode.CYCLE;
   public static boolean showSynergyInInventory = false;
   public static boolean showSynergyHud = false;
   public static boolean showSynergyActionBar = true;

   public static boolean isSynergyBarEnabled() {
      return synergyBarMode != GuildClientConfig.SynergyBarMode.OFF;
   }

   public static GuildClientConfig.SynergyBarMode getSynergyBarMode() {
      return synergyBarMode;
   }

   public static String getSynergyBarModeName() {
      return switch (synergyBarMode) {
         case CYCLE -> "CICLO (6s)";
         case ALWAYS -> "SIEMPRE";
         case OFF -> "DESACTIVADO";
      };
   }

   public static String cycleSynergyBarMode() {
      synergyBarMode = switch (synergyBarMode) {
         case CYCLE -> GuildClientConfig.SynergyBarMode.ALWAYS;
         case ALWAYS -> GuildClientConfig.SynergyBarMode.OFF;
         case OFF -> GuildClientConfig.SynergyBarMode.CYCLE;
      };
      save();
      return getSynergyBarModeName();
   }

   public static boolean isInventoryEnabled() {
      return false;
   }

   public static boolean toggleInventory() {
      return false;
   }

   public static boolean isHudEnabled() {
      return false;
   }

   public static boolean toggleHud() {
      return false;
   }

   public static boolean isActionBarEnabled() {
      return isSynergyBarEnabled();
   }

   public static boolean toggleActionBar() {
      cycleSynergyBarMode();
      return isSynergyBarEnabled();
   }

   private GuildClientConfig() {
   }

   public static void load() {
      if (!FILE.exists()) {
         save();
      } else {
         try (FileReader reader = new FileReader(FILE)) {
            GuildClientConfig.Data data = (GuildClientConfig.Data)GSON.fromJson(reader, GuildClientConfig.Data.class);
            if (data != null && data.synergyBarMode != null) {
               try {
                  synergyBarMode = GuildClientConfig.SynergyBarMode.valueOf(data.synergyBarMode);
               } catch (Exception var4) {
                  synergyBarMode = GuildClientConfig.SynergyBarMode.CYCLE;
               }
            }
         } catch (Exception var6) {
         }
      }
   }

   public static void save() {
      try {
         FILE.getParentFile().mkdirs();

         try (FileWriter writer = new FileWriter(FILE)) {
            GuildClientConfig.Data data = new GuildClientConfig.Data();
            data.synergyBarMode = synergyBarMode.name();
            GSON.toJson(data, writer);
         }
      } catch (Exception var5) {
      }
   }

   private static class Data {
      String synergyBarMode = GuildClientConfig.SynergyBarMode.CYCLE.name();
   }

   public static enum SynergyBarMode {
      CYCLE,
      ALWAYS,
      OFF;
   }
}
