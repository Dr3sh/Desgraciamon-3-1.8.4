package com.desgraciamon.showdownarena

import com.google.gson.GsonBuilder
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

enum class SpotlightShowMode(
    val id: String,
    val label: String,
    val isRgb: Boolean = false,
    val isMeta: Boolean = false
) {
    AUTO("auto", "Playlist Activa", isRgb = false, isMeta = true),
    APAGADO("apagado", "Apagado", isRgb = false, isMeta = true),

    // A. Dos Colores (27 modos)
    CENTRO("centro", "Centro"),
    FAN("fan", "Fan / Corona"),
    SWEEP("sweep", "Sweep"),
    FLYOUT("flyout", "Fly-out"),
    BALLYHOO("ballyhoo", "Ballyhoo"),
    XCROSS("xcross", "X-Cross / Domo"),
    CONVERGE("converge", "Converge"),
    CHASE("chase", "Chase Circular"),
    COMET("comet", "Circular Trail"),
    DUAL_COMET("dual_comet", "Dual Comet"),
    WAVE("wave", "Wave / Phase"),
    MIRROR("mirror", "Mirror"),
    COLOR_FLIP("color_flip", "Color Flip"),
    VORTEX("vortex", "Spiral Vortex"),
    HEARTBEAT("heartbeat", "Heartbeat"),
    BUMP("bump", "Bump / Flash"),
    BLACKOUT("blackout", "Blackout + Blinder"),
    CATHEDRAL("cathedral", "Cathedral / Columnas"),
    HELIX("helix", "Double Helix"),
    SHOCKWAVE("shockwave", "Shockwave"),
    PENDULUM("pendulum", "Pendulum"),
    SPARKLE("sparkle", "Fireflies / Sparkle"),
    PRISM_SPLIT("prism_split", "Prism Split"),
    PINGPONG("pingpong", "Ping-Pong"),
    CROWN("crown", "Crown Pulse"),
    CAGE("cage", "Laser Cage"),
    METEOR("meteor", "Meteor Shower"),

    // B. RGB (22 modos)
    RGB_COMET("rgb_comet", "RGB Trail", isRgb = true),
    RGB_DUAL_COMET("rgb_dual_comet", "RGB Dual Comet", isRgb = true),
    RGB_WAVE("rgb_wave", "RGB Wave", isRgb = true),
    RGB_FAN("rgb_fan", "RGB Fan", isRgb = true),
    RGB_VORTEX("rgb_vortex", "RGB Vortex", isRgb = true),
    RGB_BALLYHOO("rgb_ballyhoo", "RGB Ballyhoo", isRgb = true),
    RGB_XCROSS("rgb_xcross", "RGB X-Cross", isRgb = true),
    RGB_RADAR("rgb_radar", "RGB Radar", isRgb = true),
    RGB_BUMP("rgb_bump", "RGB Bump", isRgb = true),
    RGB_STROBE("rgb_strobe", "RGB Strobe", isRgb = true),
    RGB_CONVERGE("rgb_converge", "RGB Converge", isRgb = true),
    RGB_BLACKOUT("rgb_blackout", "RGB Blinder", isRgb = true),
    RGB_AURORA("rgb_aurora", "RGB Aurora", isRgb = true),
    RGB_HELIX("rgb_helix", "RGB Double Helix", isRgb = true),
    RGB_STARBURST("rgb_starburst", "RGB Starburst", isRgb = true),
    RGB_KALEIDO("rgb_kaleido", "RGB Kaleidoscope", isRgb = true),
    RGB_SUPERNOVA("rgb_supernova", "RGB Supernova", isRgb = true),
    RGB_NEON("rgb_neon", "RGB Neon Halo", isRgb = true),
    RGB_CYBER("rgb_cyber", "RGB Cyber Grid", isRgb = true),
    RGB_METEOR("rgb_meteor", "RGB Meteor", isRgb = true),
    RGB_BREATHE("rgb_breathe", "RGB Breathe", isRgb = true),
    RGB_DISCO("rgb_disco", "RGB Disco", isRgb = true);

    companion object {
        val twoColorEffects: List<SpotlightShowMode> by lazy { entries.filter { !it.isMeta && !it.isRgb } }
        val rgbEffects: List<SpotlightShowMode> by lazy { entries.filter { !it.isMeta && it.isRgb } }
        val allEffects: List<SpotlightShowMode> by lazy { entries.filter { !it.isMeta } }

        fun fromId(raw: String): SpotlightShowMode? = entries.firstOrNull { it.id.equals(raw, ignoreCase = true) }
    }
}

data class ArenaLightsStatePayload(
    val modeId: String,
    val rodMode: String,
    val activeEffectId: String,
    val playlist: List<String>,
    val disabledEffects: List<String>,
    val dayColor1: Int,
    val dayColor2: Int,
    val nightColor1: Int,
    val nightColor2: Int,
    val syncDomeWithLights: Boolean,
    val loopDurationMs: Long
) : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Type<ArenaLightsStatePayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("showdown_arena", "lights_state"))

        val CODEC: StreamCodec<FriendlyByteBuf, ArenaLightsStatePayload> = CustomPacketPayload.codec(
            { p, buf ->
                buf.writeUtf(p.modeId, 32)
                buf.writeUtf(p.rodMode, 16)
                buf.writeUtf(p.activeEffectId, 32)
                buf.writeVarInt(p.playlist.size)
                p.playlist.forEach { buf.writeUtf(it, 32) }
                buf.writeVarInt(p.disabledEffects.size)
                p.disabledEffects.forEach { buf.writeUtf(it, 32) }
                buf.writeInt(p.dayColor1)
                buf.writeInt(p.dayColor2)
                buf.writeInt(p.nightColor1)
                buf.writeInt(p.nightColor2)
                buf.writeBoolean(p.syncDomeWithLights)
                buf.writeLong(p.loopDurationMs)
            },
            { buf ->
                val modeId = buf.readUtf(32)
                val rodMode = buf.readUtf(16)
                val activeEffectId = buf.readUtf(32)
                val plCount = buf.readVarInt().coerceIn(0, 128)
                val playlist = List(plCount) { buf.readUtf(32) }
                val disCount = buf.readVarInt().coerceIn(0, 128)
                val disabled = List(disCount) { buf.readUtf(32) }
                ArenaLightsStatePayload(
                    modeId = modeId,
                    rodMode = rodMode,
                    activeEffectId = activeEffectId,
                    playlist = playlist,
                    disabledEffects = disabled,
                    dayColor1 = buf.readInt(),
                    dayColor2 = buf.readInt(),
                    nightColor1 = buf.readInt(),
                    nightColor2 = buf.readInt(),
                    syncDomeWithLights = buf.readBoolean(),
                    loopDurationMs = buf.readLong()
                )
            }
        )
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID
}

class OpenArenaLightsGuiPayload : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Type<OpenArenaLightsGuiPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("showdown_arena", "open_lights_gui"))
        val CODEC: StreamCodec<FriendlyByteBuf, OpenArenaLightsGuiPayload> =
            CustomPacketPayload.codec({ _, _ -> }, { OpenArenaLightsGuiPayload() })
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID
}

data class SaveArenaLightsConfigPayload(
    val modeId: String,
    val rodMode: String,
    val playlist: List<String>,
    val disabledEffects: List<String>,
    val dayColor1: Int,
    val dayColor2: Int,
    val nightColor1: Int,
    val nightColor2: Int,
    val syncDomeWithLights: Boolean,
    val loopDurationMs: Long
) : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Type<SaveArenaLightsConfigPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("showdown_arena", "save_lights_config"))

        val CODEC: StreamCodec<FriendlyByteBuf, SaveArenaLightsConfigPayload> = CustomPacketPayload.codec(
            { p, buf ->
                buf.writeUtf(p.modeId, 32)
                buf.writeUtf(p.rodMode, 16)
                buf.writeVarInt(p.playlist.size)
                p.playlist.forEach { buf.writeUtf(it, 32) }
                buf.writeVarInt(p.disabledEffects.size)
                p.disabledEffects.forEach { buf.writeUtf(it, 32) }
                buf.writeInt(p.dayColor1)
                buf.writeInt(p.dayColor2)
                buf.writeInt(p.nightColor1)
                buf.writeInt(p.nightColor2)
                buf.writeBoolean(p.syncDomeWithLights)
                buf.writeLong(p.loopDurationMs)
            },
            { buf ->
                val modeId = buf.readUtf(32)
                val rodMode = buf.readUtf(16)
                val plCount = buf.readVarInt().coerceIn(0, 128)
                val playlist = List(plCount) { buf.readUtf(32) }
                val disCount = buf.readVarInt().coerceIn(0, 128)
                val disabled = List(disCount) { buf.readUtf(32) }
                SaveArenaLightsConfigPayload(
                    modeId = modeId,
                    rodMode = rodMode,
                    playlist = playlist,
                    disabledEffects = disabled,
                    dayColor1 = buf.readInt(),
                    dayColor2 = buf.readInt(),
                    nightColor1 = buf.readInt(),
                    nightColor2 = buf.readInt(),
                    syncDomeWithLights = buf.readBoolean(),
                    loopDurationMs = buf.readLong()
                )
            }
        )
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID
}

object ArenaLightsManager {
    private data class LightsConfig(
        val modeId: String = "auto",
        val rodMode: String = "1",
        val playlist: List<String> = DEFAULT_PLAYLIST,
        val disabledEffects: List<String> = emptyList(),
        val dayColor1: Int = 0xFF335C,
        val dayColor2: Int = 0x24BDFF,
        val nightColor1: Int = 0xFF335C,
        val nightColor2: Int = 0x24BDFF,
        val syncDomeWithLights: Boolean = true,
        val loopDurationMs: Long = 12_000L
    )

    private val DEFAULT_PLAYLIST = listOf(
        "centro", "fan", "ballyhoo", "rgb_aurora", "dual_comet", "vortex", "rgb_wave", "heartbeat"
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var configPath: Path? = null

    @Volatile var currentMode: SpotlightShowMode = SpotlightShowMode.AUTO
        private set
    @Volatile var currentRodMode: String = "1"
        private set
    @Volatile var playlist: List<String> = DEFAULT_PLAYLIST
        private set
    @Volatile var disabledEffects: Set<String> = emptySet()
        private set
    @Volatile var dayColor1: Int = 0xFF335C
        private set
    @Volatile var dayColor2: Int = 0x24BDFF
        private set
    @Volatile var nightColor1: Int = 0xFF335C
        private set
    @Volatile var nightColor2: Int = 0x24BDFF
        private set
    @Volatile var syncDomeWithLights: Boolean = true
        private set
    @Volatile var loopDurationMs: Long = 12_000L
        private set

    @Volatile private var playlistIndex: Int = 0
    @Volatile private var lastLoopSwitchMs: Long = System.currentTimeMillis()
    @Volatile private var lastBroadcastEffectId: String = "centro"

    fun init() {
        PayloadTypeRegistry.playS2C().register(ArenaLightsStatePayload.ID, ArenaLightsStatePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(OpenArenaLightsGuiPayload.ID, OpenArenaLightsGuiPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(SaveArenaLightsConfigPayload.ID, SaveArenaLightsConfigPayload.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(SaveArenaLightsConfigPayload.ID) { payload, context ->
            val player = context.player()
            context.server().execute {
                if (!player.hasPermissions(2)) {
                    player.sendSystemMessage(Component.literal("§cNo tienes permisos para configurar las luces de la arena."))
                    syncToPlayer(player)
                    return@execute
                }
                applyConfig(
                    modeId = payload.modeId,
                    rodMode = payload.rodMode,
                    newPlaylist = payload.playlist,
                    newDisabled = payload.disabledEffects,
                    d1 = payload.dayColor1,
                    d2 = payload.dayColor2,
                    n1 = payload.nightColor1,
                    n2 = payload.nightColor2,
                    syncDome = payload.syncDomeWithLights,
                    durationMs = payload.loopDurationMs
                )
                save()
                syncToAll(context.server())
                player.sendSystemMessage(Component.literal("§b[Arena] §aConfiguración de luces guardada y sincronizada."))
            }
        }
    }

    private fun applyConfig(
        modeId: String,
        rodMode: String,
        newPlaylist: List<String>,
        newDisabled: List<String>,
        d1: Int,
        d2: Int,
        n1: Int,
        n2: Int,
        syncDome: Boolean,
        durationMs: Long
    ) {
        currentMode = SpotlightShowMode.fromId(modeId) ?: SpotlightShowMode.AUTO
        val rm = rodMode.lowercase()
        currentRodMode = if (rm in listOf("1", "3", "auto")) rm else "1"
        val validEffects = newPlaylist.mapNotNull { SpotlightShowMode.fromId(it)?.takeIf { m -> !m.isMeta }?.id }
        playlist = if (validEffects.isNotEmpty()) validEffects else DEFAULT_PLAYLIST
        disabledEffects = newDisabled.mapNotNull { SpotlightShowMode.fromId(it)?.takeIf { m -> !m.isMeta }?.id }.toSet()
        dayColor1 = d1 and 0xFFFFFF
        dayColor2 = d2 and 0xFFFFFF
        nightColor1 = n1 and 0xFFFFFF
        nightColor2 = n2 and 0xFFFFFF
        syncDomeWithLights = syncDome
        loopDurationMs = durationMs.coerceIn(4_000L, 60_000L)
        if (playlistIndex >= playlist.size) playlistIndex = 0
        lastBroadcastEffectId = resolveActiveEffectId()
    }

    fun resolveActiveEffectId(): String {
        if (currentMode == SpotlightShowMode.APAGADO) return SpotlightShowMode.APAGADO.id
        if (currentMode != SpotlightShowMode.AUTO) {
            return if (currentMode.id !in disabledEffects) currentMode.id else SpotlightShowMode.CENTRO.id
        }
        val activePool = playlist.filter { it !in disabledEffects }
        if (activePool.isNotEmpty()) {
            return activePool[playlistIndex % activePool.size]
        }
        val fallbackPool = SpotlightShowMode.allEffects.filter { it.id !in disabledEffects }
        return if (fallbackPool.isNotEmpty()) {
            fallbackPool[playlistIndex % fallbackPool.size].id
        } else {
            SpotlightShowMode.APAGADO.id
        }
    }

    fun tick(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        if (currentMode == SpotlightShowMode.AUTO && now - lastLoopSwitchMs >= loopDurationMs) {
            lastLoopSwitchMs = now
            playlistIndex++
            val nextId = resolveActiveEffectId()
            if (nextId != lastBroadcastEffectId) {
                lastBroadcastEffectId = nextId
                syncToAll(server)
            }
        }
    }

    fun onServerStart(server: MinecraftServer) {
        val dataDir = server.getWorldPath(LevelResource.ROOT).resolve("data")
        Files.createDirectories(dataDir)
        configPath = dataDir.resolve("desgraciamon_lights_config.json")
        load()
        lastLoopSwitchMs = System.currentTimeMillis()
        lastBroadcastEffectId = resolveActiveEffectId()
    }

    fun onServerStop() {
        save()
        configPath = null
    }

    private fun load() {
        val path = configPath ?: return
        if (!Files.exists(path)) {
            save()
            return
        }
        try {
            val loaded = gson.fromJson(Files.readString(path), LightsConfig::class.java)
            if (loaded != null) {
                applyConfig(
                    modeId = loaded.modeId,
                    rodMode = loaded.rodMode,
                    newPlaylist = loaded.playlist,
                    newDisabled = loaded.disabledEffects,
                    d1 = loaded.dayColor1,
                    d2 = loaded.dayColor2,
                    n1 = loaded.nightColor1,
                    n2 = loaded.nightColor2,
                    syncDome = loaded.syncDomeWithLights,
                    durationMs = loaded.loopDurationMs
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun save() {
        val path = configPath ?: return
        try {
            val cfg = LightsConfig(
                modeId = currentMode.id,
                rodMode = currentRodMode,
                playlist = playlist,
                disabledEffects = disabledEffects.toList(),
                dayColor1 = dayColor1,
                dayColor2 = dayColor2,
                nightColor1 = nightColor1,
                nightColor2 = nightColor2,
                syncDomeWithLights = syncDomeWithLights,
                loopDurationMs = loopDurationMs
            )
            val tmp = path.resolveSibling("${path.fileName}.tmp")
            Files.writeString(tmp, gson.toJson(cfg))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            try {
                Files.writeString(path, gson.toJson(LightsConfig(
                    modeId = currentMode.id,
                    rodMode = currentRodMode,
                    playlist = playlist,
                    disabledEffects = disabledEffects.toList(),
                    dayColor1 = dayColor1,
                    dayColor2 = dayColor2,
                    nightColor1 = nightColor1,
                    nightColor2 = nightColor2,
                    syncDomeWithLights = syncDomeWithLights,
                    loopDurationMs = loopDurationMs
                )))
            } catch (_: Exception) {}
        }
    }

    private fun buildPayload(): ArenaLightsStatePayload = ArenaLightsStatePayload(
        modeId = currentMode.id,
        rodMode = currentRodMode,
        activeEffectId = resolveActiveEffectId(),
        playlist = playlist,
        disabledEffects = disabledEffects.toList(),
        dayColor1 = dayColor1,
        dayColor2 = dayColor2,
        nightColor1 = nightColor1,
        nightColor2 = nightColor2,
        syncDomeWithLights = syncDomeWithLights,
        loopDurationMs = loopDurationMs
    )

    fun syncToPlayer(player: ServerPlayer) {
        if (!ServerPlayNetworking.canSend(player, ArenaLightsStatePayload.ID)) return
        ServerPlayNetworking.send(player, buildPayload())
    }

    fun syncToAll(server: MinecraftServer) {
        val payload = buildPayload()
        for (p in server.playerList.players) {
            if (ServerPlayNetworking.canSend(p, ArenaLightsStatePayload.ID)) {
                ServerPlayNetworking.send(p, payload)
            }
        }
    }

    private fun openEditorFor(player: ServerPlayer) {
        syncToPlayer(player)
        if (ServerPlayNetworking.canSend(player, OpenArenaLightsGuiPayload.ID)) {
            ServerPlayNetworking.send(player, OpenArenaLightsGuiPayload())
        }
    }

    fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val registerCmd = { name: String ->
            dispatcher.register(
                Commands.literal(name)
                    .requires { it.hasPermission(2) }
                    .executes { ctx ->
                        val player = ctx.source.playerOrException
                        openEditorFor(player)
                        1
                    }
                    .then(
                        Commands.literal("gui")
                            .executes { ctx ->
                                val player = ctx.source.playerOrException
                                openEditorFor(player)
                                1
                            }
                    )
                    .then(
                        Commands.literal("varillas")
                            .then(
                                Commands.argument("cantidad", StringArgumentType.word())
                                    .suggests { _, builder ->
                                        listOf("1", "3", "auto").forEach { opt ->
                                            if (opt.startsWith(builder.remaining, ignoreCase = true)) builder.suggest(opt)
                                        }
                                        builder.buildFuture()
                                    }
                                    .executes { ctx ->
                                        val raw = StringArgumentType.getString(ctx, "cantidad").lowercase()
                                        if (raw !in listOf("auto", "1", "3")) {
                                            ctx.source.sendFailure(Component.literal("§cUsa: 1, 3 o auto."))
                                            return@executes 0
                                        }
                                        currentRodMode = raw
                                        save()
                                        syncToAll(ctx.source.server)
                                        val desc = when (raw) {
                                            "1" -> "1 varilla por reflector"
                                            "3" -> "3 varillas por reflector"
                                            else -> "Automático (1, 2 y 3 varillas)"
                                        }
                                        ctx.source.sendSuccess(
                                            { Component.literal("§b[Arena] §fVarillas de reflectores: §e$desc") },
                                            true
                                        )
                                        1
                                    }
                            )
                    )
                    .then(
                        Commands.argument("modo", StringArgumentType.word())
                            .suggests { _, builder ->
                                SpotlightShowMode.entries.forEach { m ->
                                    if (m.id.startsWith(builder.remaining, ignoreCase = true)) {
                                        builder.suggest(m.id)
                                    }
                                }
                                builder.buildFuture()
                            }
                            .executes { ctx ->
                                val raw = StringArgumentType.getString(ctx, "modo")
                                val mode = SpotlightShowMode.fromId(raw)
                                if (mode == null) {
                                    ctx.source.sendFailure(Component.literal("§cModo inválido. Usa §e/arenaluces gui §cpara ver los 49 efectos."))
                                    return@executes 0
                                }
                                currentMode = mode
                                lastBroadcastEffectId = resolveActiveEffectId()
                                save()
                                syncToAll(ctx.source.server)
                                ctx.source.sendSuccess(
                                    { Component.literal("§b[Arena] §fModo de reflectores: §e${mode.label}") },
                                    true
                                )
                                1
                            }
                    )
            )
        }
        registerCmd("arenaluces")
        registerCmd("lucesarena")
    }
}
