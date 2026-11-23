# Desgraciamon 3 Discord Bridge

Fabric mod for Minecraft 1.21.1 and Cobblemon 1.7.3. It can run by itself; installing `legendary-cooldown` makes `/cooldown` read that mod's actual timer. Without it, the bridge reports its own 48-hour fallback timer. Install `desgraciamon4-catch-assistant` to show Discord-linked names in capture webhook messages.

## Discord setup

1. Create a Discord application and bot in the Discord Developer Portal. Copy the bot token and keep it private.
2. Invite the bot to the server with the `bot` and `applications.commands` OAuth scopes. It does not require privileged gateway intents.
3. Start Minecraft once with the bridge installed. Edit `config/desgraciamon4-discord-bridge.properties`:

```properties
botToken=YOUR_BOT_TOKEN
# Optional. Set this to your Discord server ID for immediate guild command registration.
guildId=YOUR_GUILD_ID
```

4. Restart the Minecraft server. If `guildId` is empty, the bot registers global slash commands, which can take time to appear.

## Link a player

In Minecraft, the player runs `/vinculardiscord`. The server prints a random, one-use code that expires after 10 minutes. The player then runs `/vincular codigo:CODE` in Discord. Discord confirms the link privately. A Discord account and a Minecraft account can each be linked only once. Use `/vinculardiscord desvincular` in Minecraft or `/desvincular` in Discord to unlink.

## Queries

- `/legendarios jugador:@usuario` lists the player's current legendary and mythical Pokémon from party and PC, with capture dates when the Bridge recorded the capture. This response is public; captures made before installing Bridge show as an unrecorded date.
- `/equipo jugador:@usuario` shows the linked player's current party, with a per-Pokémon animated Showdown sprite, shiny marker, level, gender, nature, current HP, calculated stats, IVs, and EVs. The response is only visible to the person who ran the command. Only the active party is included; PC Pokémon are not.
- `/cooldown jugador:@usuario` reports the remaining cooldown plus Discord absolute and relative timestamps. When `legendary-cooldown` is installed, its timer is authoritative.

Players can run `/privatizarequipo` in Minecraft to toggle team privacy. When private, only the Discord account linked to the owner can query that party with `/equipo`; `/legendarios` will omit legendary and mythical Pokémon in that party. The preference is saved with the world's Discord Bridge data and is off by default.

Open the personal settings panel with `/ajustesdiscord` or F7. It can toggle team privacy, the cooldown boss bar, the cooldown join notice, legendary capture notices, and chat image previews. Optional controls are disabled when their corresponding mod is not installed. The same player preferences can still be changed with `/privatizarequipo`, `/legendarioscooldown`, and `/noticooldown`.

Mod text catalogs and formatting syntax are documented in [../docs/personalizacion-de-textos.md](../docs/personalizacion-de-textos.md).
Use `/reloadtextos` to reload the available local text catalogs without restarting. The server command requires operator permission; client and server reload their own files independently, without synchronization.

The bridge stores account links and the fallback capture timer in the world's overworld `SavedData`. Temporary link codes are stored as hashes and expire after 10 minutes. The bot token is only read from the server config and is never written to world data or logs.
