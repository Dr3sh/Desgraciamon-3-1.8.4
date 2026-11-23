package net.desgraciamon.guilds;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

final class GuildUiSound {
   private static final String ALIAS = "DesgraciamonGuildUi";
   private static Path audioFile;

   private GuildUiSound() {
   }

   static void play() {
      if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
         try {
            Path file = audioFile();
            Class<?> nativeLibraryType = Class.forName("com.sun.jna.NativeLibrary");
            Object library = nativeLibraryType.getMethod("getInstance", String.class).invoke(null, "winmm");
            Object function = nativeLibraryType.getMethod("getFunction", String.class).invoke(library, "mciSendStringA");
            Method invokeInt = function.getClass().getMethod("invokeInt", Object[].class);
            send(invokeInt, function, "close DesgraciamonGuildUi");
            send(invokeInt, function, "open \"" + file + "\" type MPEGVideo alias DesgraciamonGuildUi");
            send(invokeInt, function, "setaudio DesgraciamonGuildUi volume to 500");
            send(invokeInt, function, "play DesgraciamonGuildUi");
         } catch (IOException | LinkageError | ReflectiveOperationException var5) {
         }
      }
   }

   private static void send(Method invokeInt, Object function, String command) throws ReflectiveOperationException {
      invokeInt.invoke(function, (Object)new Object[]{command, null, 0, null});
   }

   private static Path audioFile() throws IOException {
      if (audioFile != null && Files.exists(audioFile)) {
         return audioFile;
      } else {
         audioFile = Files.createTempFile("desgraciamon-guild-ui-", ".mp3");

         try (InputStream stream = ((Path)((ModContainer)FabricLoader.getInstance().getModContainer("desgraciamon_guilds").orElseThrow())
                  .findPath("assets/desgraciamon_guilds/sounds/ui.mp3")
                  .orElseThrow())
               .toUri()
               .toURL()
               .openStream()) {
            Files.copy(stream, audioFile, StandardCopyOption.REPLACE_EXISTING);
         }

         return audioFile;
      }
   }
}
