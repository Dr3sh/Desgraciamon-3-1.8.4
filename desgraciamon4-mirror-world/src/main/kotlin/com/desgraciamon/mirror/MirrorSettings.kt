package com.desgraciamon.mirror

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.minecraft.network.chat.MutableComponent
import org.slf4j.LoggerFactory
import java.io.File

/** Una carpeta con datos de una temporada anterior (estructura de Cobblemon: pcstore/ y playerpartystore/). */
data class MemorySource(
    var label: String = "Desgraciamon 1",
    var path: String = "config/desgraciamon4_mirror/memories/desgraciamon-1",
)

data class MirrorConfig(
    var sources: MutableList<MemorySource> = mutableListOf(
        MemorySource(label = "Desgraciamon 1", path = "config/desgraciamon4_mirror/memories/desgraciamon-1"),
        MemorySource(label = "Desgraciamon 2", path = "config/desgraciamon4_mirror/memories/desgraciamon-2"),
    ),
    /** Alias de UUID por si un jugador cambió de UUID entre temporadas (UUID actual -> lista de UUIDs anteriores). */
    var uuidAliases: MutableMap<String, List<String>> = mutableMapOf(
        "024eb95f-c7f8-48c7-aacb-9896026aa33a" to listOf("0c386e8b-d607-31f4-b012-ad927589466d")
    ),
    /** Cantidad de Pokémon activos simultáneamente en la parcela (van rotando con el resto del pool). */
    var maxActivePokemon: Int = 28,
    /** Máximo total de Pokémon en el pool de rotación del jugador. */
    var maxPokemon: Int = 120,
    /** Distancia mínima (en bloques) a la que un Pokémon que no estás mirando puede rotar por otro. */
    var rotationDistanceBlocks: Double = 18.0,
    /** Tiempo mínimo en segundos que un Pokémon permanece antes de poder rotar. */
    var rotationMinAgeSeconds: Int = 18,
    /** Duración total del efecto Black Fade al entrar/salir (en segundos). */
    var fadeTotalSeconds: Double = 2.05,
    var includeParty: Boolean = true,
    var includeLegendaries: Boolean = true,
    var includeShinies: Boolean = true,
    var includeNicknamed: Boolean = true,
    /** Si sobran huecos tras los criterios anteriores, incluir también las demás especies de mayor nivel. */
    var fillWithHighestLevel: Boolean = true,
    var inviteExpireSeconds: Int = 120,
    /** Segundos entre avisos de "esto es un recuerdo" para no saturar el chat. */
    var protectMessageCooldownSeconds: Double = 1.5,
)

object MirrorSettings {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val logger = LoggerFactory.getLogger("desgraciamon4-mirror")
    private val dir = File("config/desgraciamon4_mirror")

    var config = MirrorConfig()
        private set

    private var texts: Map<String, String> = emptyMap()

    fun load() {
        dir.mkdirs()
        val configFile = File(dir, "config.json")
        config = runCatching {
            if (configFile.exists()) configFile.reader(Charsets.UTF_8).use { gson.fromJson(it, MirrorConfig::class.java) } ?: MirrorConfig()
            else MirrorConfig()
        }.onFailure { logger.error("No se pudo leer ${configFile.path}", it) }.getOrDefault(MirrorConfig())

        // Migrar si había quedado una configuración antigua apuntando solo a temporada-1
        if (config.sources.size == 1 && config.sources[0].path.endsWith("temporada-1")) {
            config.sources = mutableListOf(
                MemorySource(label = "Desgraciamon 1", path = "config/desgraciamon4_mirror/memories/desgraciamon-1"),
                MemorySource(label = "Desgraciamon 2", path = "config/desgraciamon4_mirror/memories/desgraciamon-2"),
            )
        }
        if (config.uuidAliases.isEmpty()) {
            config.uuidAliases["024eb95f-c7f8-48c7-aacb-9896026aa33a"] = listOf("0c386e8b-d607-31f4-b012-ad927589466d")
        }
        if (config.maxActivePokemon <= 0) config.maxActivePokemon = 14
        if (config.rotationDistanceBlocks < 16.0) config.rotationDistanceBlocks = 18.0
        if (config.rotationMinAgeSeconds < 12) config.rotationMinAgeSeconds = 18
        if (config.fadeTotalSeconds <= 0.5 || config.fadeTotalSeconds > 2.6) config.fadeTotalSeconds = 2.05

        runCatching { configFile.writeText(gson.toJson(config), Charsets.UTF_8) }
        config.sources.forEach { File(it.path).mkdirs() }

        val textsDir = File(dir, ".texts")
        textsDir.mkdirs()
        runCatching { java.nio.file.Files.setAttribute(textsDir.toPath(), "dos:hidden", true) }
        val textsFile = File(textsDir, "texts.json")
        val legacyTextsFile = File(dir, "texts.json")
        if (!textsFile.exists() && legacyTextsFile.exists()) {
            runCatching { java.nio.file.Files.move(legacyTextsFile.toPath(), textsFile.toPath()) }
        }
        texts = runCatching {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val overrides: Map<String, String> =
                if (textsFile.exists()) textsFile.reader(Charsets.UTF_8).use { gson.fromJson(it, type) } ?: emptyMap() else emptyMap()
            DEFAULT_TEXTS + overrides
        }.onFailure { logger.error("No se pudo leer ${textsFile.path}", it) }.getOrDefault(DEFAULT_TEXTS)
        runCatching { textsFile.writeText(gson.toJson(texts), Charsets.UTF_8) }
    }

    fun text(key: String, vararg values: Pair<String, Any?>): MutableComponent =
        RichText.parse(texts[key] ?: DEFAULT_TEXTS[key] ?: key, values.toMap())

    private val DEFAULT_TEXTS: Map<String, String> = linkedMapOf(
        "protect.blocked" to "<color=#C4B5FD><italic>Esto es un recuerdo, las memorias no pueden ser manipuladas.</italic></color>",
        "protect.drop_blocked" to "<color=#F87171>No puedes soltar objetos en el Mundo Espejo.</color>",
        "enter.discord_required" to "<color=#F87171>[Mundo Espejo] Debes vincular tu cuenta de Discord primero para entrar.</color>\n<color=#FDE047>Usa </color><color=#38BDF8>/discord vincular</color><color=#FDE047> en Minecraft y luego </color><color=#38BDF8>/cuenta vincular</color><color=#FDE047> en Discord.</color>",
        "recuerdos.entering" to "<color=#FACC15>Iniciando tránsito a la </color><color=#F59E0B>Dimensión de los Recuerdos</color><color=#FACC15>...</color>",
        "recuerdos.arrived" to "<color=#F59E0B>Has llegado a la </color><color=#FACC15>Dimensión de los Recuerdos</color><color=#F59E0B>.</color>",
        "recuerdos.not_inside" to "<color=#F87171>No te encuentras dentro de la Dimensión de los Recuerdos.</color>",
        "recuerdos.exited" to "<color=#86EFAC>Has regresado de la Dimensión de los Recuerdos.</color>",
        "enter.generating" to "<color=#A78BFA><italic>Cerrando los ojos... tus recuerdos cobran forma.</italic></color>", "enter.welcome" to "<prism=#C4B5FD,#FDE68A,#F9A8D4 speed=0.6><bold>Mundo Espejo </bold></prism> <color=#E9D5FF>Bienvenido a tus recuerdos, {player}. Aquí te esperan {count} viejos compañeros.</color>", "enter.visit" to "<prism=#C4B5FD,#FDE68A,#F9A8D4 speed=0.6><bold>Mundo Espejo </bold></prism> <color=#E9D5FF>Estás visitando los recuerdos de {owner}.</color>", "enter.no_memories" to "<color=#F87171>No encontramos recuerdos de temporadas anteriores para ti.</color>", "enter.owner_notice" to "<color=#C4B5FD>{player} está viendo tus recuerdos.</color>", "enter.hint" to "<color=#A1A1AA><italic>Haz clic derecho sobre un Pokémon para recordarlo. Usa /espejo salir para despertar.</italic></color>", "exit.done" to "<color=#C4B5FD><italic>Despiertas del recuerdo...</italic></color>", "exit.not_inside" to "<color=#F87171>No estás dentro del Mundo Espejo.</color>", "invite.not_inside" to "<color=#F87171>Primero entra a tus recuerdos con /espejo para poder invitar a alguien.</color>", "invite.self" to "<color=#F87171>No puedes invitarte a ti mismo.</color>", "invite.sent" to "<color=#C4B5FD>Invitaste a {target} a ver tus recuerdos.</color>", "invite.received" to "<color=#E9D5FF>{owner} te invita a ver sus recuerdos en el Mundo Espejo.</color> {accept} {decline}", "invite.accept_button" to "<color=#86EFAC><bold>[Aceptar]</bold></color>", "invite.accept_hover" to "<color=#86EFAC>Entrar a los recuerdos de {owner}</color>", "invite.decline_button" to "<color=#FCA5A5><bold>[Rechazar]</bold></color>", "invite.none" to "<color=#F87171>No tienes invitaciones pendientes (o ya caducaron).</color>", "invite.declined_target" to "<color=#A1A1AA>Rechazaste la invitación de {owner}.</color>", "invite.declined_owner" to "<color=#A1A1AA>{target} rechazó tu invitación.</color>", "invite.accepted_owner" to "<color=#C4B5FD>{target} aceptó tu invitación.</color>", "kick.done" to "<color=#C4B5FD>{target} salió de tus recuerdos.</color>", "kick.kicked" to "<color=#C4B5FD><italic>{owner} cerró sus recuerdos. Despiertas...</italic></color>", "kick.not_here" to "<color=#F87171>{target} no está en tus recuerdos.</color>", "memory.shiny_tag" to " <color=#FACC15>Shiny</color>", "memory.legendary_tag" to " <color=#F472B6>Legendario</color>", "admin.reloaded" to "<color=#86EFAC>Mundo Espejo recargado: configuración, textos y recuerdos.</color>", "admin.regenerated" to "<color=#86EFAC>Se generó un terreno nuevo para {player}.</color>", "admin.list_header" to "<color=#E9D5FF><bold>Recuerdos de {player}</bold></color> <color=#A1A1AA>({shown} en rotación de {total} guardados)</color>", "admin.list_entry" to "<color=#A1A1AA>-</color> <color=#FDE68A>{name}</color> <color=#9CA3AF>{species} Nv. {level}</color>{shiny}{legendary} <color=#71717A>[{season} · {origin}]</color>",
    )
}
