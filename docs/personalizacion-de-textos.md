# Personalizacion de textos de los mods

Cada mod crea su propio `texts.json` en la carpeta `config/` cuando se inicia. Edita los valores que necesites y ejecuta `/reloadtextos` en el cliente o servidor correspondiente para probarlos sin reiniciar. Al cargar, el mod conserva tus cambios y agrega las claves nuevas que aparezcan en futuras versiones.

Para probar cambios sin reiniciar, ejecuta `/reloadtextos`. En el servidor lo puede usar un operador (permiso 2) para releer los catálogos disponibles en ese servidor; en el cliente, el comando de depuración relee los JSON locales de los mods disponibles en ese cliente. No sincroniza archivos entre cliente y servidor. En multijugador, ejecuta el comando en cada lado cuyos textos quieras actualizar.

| Mod | Archivo |
| --- | --- |
| Discord Bridge | `config/desgraciamon4_discord_bridge/texts.json` |
| Legendary Cooldown | `config/legendary_cooldown/texts.json` |
| Catch Assistant | `config/desgraciamon4_catch_assistant/texts.json` |
| Chat Assistant | `config/desgraciamon4_chat_assistant/texts.json` |
| Announcements | `config/desgraciamon4_announcements/texts.json` |
| Legendary Bestiary | `config/legendary_bestiary/texts.json` |
| Showdown Arena | `config/showdown_arena/texts.json` |
| Economy | `config/desgraciamon4_economy/texts.json` |

Los archivos son objetos JSON con pares `clave: texto`. Mantén las claves existentes para sobrescribir sus textos; las claves no incluidas usan el valor predeterminado del mod. Por ejemplo:

```json
{
  "privacy.enabled": "<color=#32C878>Tu equipo está privado</color>. <bold>Solo tú</bold> puedes consultarlo.",
  "discord.color.shiny": "#E3B341"
}
```

Formato disponible para mensajes de Minecraft: `{placeholder}`, `<color=#RRGGBB>...</color>`, `<bold>...</bold>` y `<italic>...</italic>`. Los nombres de placeholders disponibles se pueden consultar en el texto predeterminado generado por cada mod; conserva los que el mensaje necesita para mostrar nombres, cantidades, fechas u otros datos.

Discord no admite colores de texto arbitrarios. Los textos de sus mensajes admiten el formato que ofrece Discord, y los campos de color de embeds expuestos por un mod aceptan un valor hexadecimal como `#E3B341`.

Los textos aportados por otros mods, por Minecraft/Cobblemon, como nombres traducidos de Pokémon e ítems, y los datos escritos por jugadores no pertenecen a estos catálogos.
