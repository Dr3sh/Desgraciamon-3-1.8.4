# User-Facing Text Catalogs

Each first-party mod creates a `texts.json` file on first launch. Server messages use the server's file; screen and client-command text use the local client's file. Restart the relevant game/server after editing. Existing unknown keys are retained, and newly added defaults are added on startup.

| Mod | Config file | Example |
| --- | --- | --- |
| Discord Bridge | `config/desgraciamon4_discord_bridge/texts.json` | [JSON](texts-examples/desgraciamon4_discord_bridge.json) |
| Legendary Cooldown | `config/legendary_cooldown/texts.json` | [JSON](texts-examples/legendary_cooldown.json) |
| Shiny Time | `config/shiny_time/texts.json` | [JSON](texts-examples/shiny_time.json) |
| Catch Assistant | `config/desgraciamon_catch_assistant/texts.json` | [JSON](texts-examples/desgraciamon4_catch_assistant.json) |
| Chat Assistant | `config/desgraciamon4_chat_assistant/texts.json` | [JSON](texts-examples/desgraciamon4_chat_assistant.json) |
| Announcements | `config/desgraciamon4_announcements/texts.json` | [JSON](texts-examples/desgraciamon4_announcements.json) |
| Legendary Bestiary | `config/legendary_bestiary/texts.json` | [JSON](texts-examples/legendary_bestiary.json) |
| Showdown Arena | `config/showdown_arena/texts.json` | [JSON](texts-examples/showdown_arena.json) |

The JSON object maps stable text keys to templates. `{placeholder}` is replaced with the value supplied by that message; an unknown placeholder remains visible. Substitutions are inserted as literal text (or as an existing component for the Bridge copy-code message), not interpreted as formatting markup.

Formatting tags create native Minecraft text styles and support nesting:

```json
{
  "cooldown.blocked": "<color=#F26D6D><bold>Wait</bold></color> {time}",
  "cooldown.ready": "<italic>You can catch again.</italic>"
}
```

Supported Minecraft tags are `<color=#RRGGBB>...</color>`, `<bold>...</bold>`, and `<italic>...</italic>`. Use exactly six hexadecimal digits for colors. Discord-bound strings accept the same placeholders, but should use Discord Markdown (such as `**bold**`) because Discord does not render Minecraft component styles. To change only selected text, start from that mod's generated file and leave the other keys in place.