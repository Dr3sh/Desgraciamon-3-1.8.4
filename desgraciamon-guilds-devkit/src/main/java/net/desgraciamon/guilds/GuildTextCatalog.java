package net.desgraciamon.guilds;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

public final class GuildTextCatalog {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
   private static final Pattern TOKEN = Pattern.compile(
      "<color=#?(?:[0-9a-f]{6}|[0-9a-f]{3})>|<bold>|<italic>|<prism=[^<>]{1,200}>|</(?:color|bold|italic|prism)>|\\{[A-Za-z][A-Za-z0-9_]*}",
      Pattern.CASE_INSENSITIVE
   );
   private static final Pattern HEX = Pattern.compile("#?([0-9a-fA-F]{6}|[0-9a-fA-F]{3})(?![0-9a-fA-F])");

   private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
   private static Map<String, String> texts = new LinkedHashMap<>();

   static {
      DEFAULTS.put(
         "guild.invite.received",
         "<color=#55FF55><bold>[GREMIO]</bold></color> <color=#FFFFFF>Has recibido una invitación para unirte a </color><color=#55FFFF>{guild}</color><color=#FFFFFF>. Pulsa [{key}] para abrir el menú del gremio o usa </color><color=#FFFF55>/gremio aceptar</color><color=#FFFFFF>.</color>"
      );
      DEFAULTS.put("guild.kicked", "<color=#FF5555>[Gremio] Has sido expulsado del gremio {guild}.</color>");
      DEFAULTS.put("guild.pc.min_party", "<color=#FF5555>[Gremio] ¡Debes tener al menos 1 Pokémon en tu equipo!</color>");
      DEFAULTS.put("guild.pc.no_legendary", "<color=#FF5555>[Gremio] No puedes almacenar Pokémon Legendarios ni Míticos en el PC compartido.</color>");
      texts.putAll(DEFAULTS);
   }

   private GuildTextCatalog() {}

   public static void load() {
      File dir = new File("config/desgraciamon_guilds/.texts");
      dir.mkdirs();
      try {
         Files.setAttribute(dir.toPath(), "dos:hidden", true);
      } catch (Exception ignored) {
      }
      File file = new File(dir, "texts.json");
      Map<String, String> merged = new LinkedHashMap<>(DEFAULTS);
      if (file.exists()) {
         try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> overrides = GSON.fromJson(reader, type);
            if (overrides != null) {
               merged.putAll(overrides);
            }
         } catch (Exception ignored) {
         }
      }
      texts = merged;
      try {
         Files.writeString(file.toPath(), GSON.toJson(texts), StandardCharsets.UTF_8);
      } catch (Exception ignored) {
      }
   }

   public static MutableComponent component(String key, Object... pairs) {
      Map<String, Object> map = new LinkedHashMap<>();
      for (int i = 0; i + 1 < pairs.length; i += 2) {
         map.put(String.valueOf(pairs[i]), pairs[i + 1]);
      }
      String template = texts.getOrDefault(key, DEFAULTS.getOrDefault(key, key));
      return parseRich(template, map);
   }

   private static MutableComponent parseRich(String template, Map<String, Object> replacements) {
      MutableComponent result = Component.empty();
      Style style = Style.EMPTY;
      List<Style> stack = new ArrayList<>();
      Matcher m = TOKEN.matcher(template);
      int cursor = 0;
      while (m.find()) {
         if (m.start() > cursor) {
            result.append(Component.literal(template.substring(cursor, m.start())).withStyle(style));
         }
         String tok = m.group();
         String low = tok.toLowerCase();
         if (low.startsWith("<color=")) {
            stack.add(style);
            style = style.withColor(TextColor.fromRgb(parseHex(tok.substring(7, tok.length() - 1))));
         } else if (low.equals("<bold>")) {
            stack.add(style);
            style = style.withBold(true);
         } else if (low.equals("<italic>")) {
            stack.add(style);
            style = style.withItalic(true);
         } else if (low.startsWith("<prism=")) {
            stack.add(style);
            Matcher hm = HEX.matcher(tok.substring(7, tok.length() - 1));
            if (hm.find()) {
               style = style.withColor(TextColor.fromRgb(parseHex(hm.group(1))));
            }
         } else if (low.startsWith("</")) {
            if (!stack.isEmpty()) {
               style = stack.remove(stack.size() - 1);
            }
         } else if (tok.startsWith("{") && tok.endsWith("}")) {
            String name = tok.substring(1, tok.length() - 1);
            Object val = replacements.get(name);
            if (val instanceof Component comp) {
               result.append(comp);
            } else {
               result.append(Component.literal(val == null ? tok : String.valueOf(val)).withStyle(style));
            }
         }
         cursor = m.end();
      }
      if (cursor < template.length()) {
         result.append(Component.literal(template.substring(cursor)).withStyle(style));
      }
      return result;
   }

   private static int parseHex(String raw) {
      String h = raw.trim().replace("#", "");
      if (h.length() == 3) {
         h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
      }
      try {
         return Integer.parseInt(h, 16) & 0xFFFFFF;
      } catch (Exception e) {
         return 0xFFFFFF;
      }
   }
}
