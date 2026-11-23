package com.desgraciamon.badges

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Sincroniza al cliente:
 * 1) Las definiciones de insignias (id, iconRaw, nameRaw, descRaw) del servidor.
 * 2) El mapa de UUID -> lista de IDs de insignias visibles de los jugadores online (para NameTags).
 * 3) Las insignias desbloqueadas y ocultas del propio jugador (para la pantalla F7).
 */
data class BadgesSyncPayload(
    val definitions: List<BadgeDefEntry>,
    val worldVisibleBadges: Map<UUID, List<String>>,
    val myUnlocked: List<String>,
    val myHidden: Set<String>
) : CustomPacketPayload {

    data class BadgeDefEntry(
        val id: String,
        val icon: String,
        val name: String,
        val description: String,
        val nonHideable: Boolean = false
    )

    override fun type(): CustomPacketPayload.Type<BadgesSyncPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<BadgesSyncPayload>(
            ResourceLocation.fromNamespaceAndPath(BadgesMod.MOD_ID, "sync")
        )

        val CODEC: StreamCodec<FriendlyByteBuf, BadgesSyncPayload> = StreamCodec.of(
            { buf, payload ->
                buf.writeVarInt(payload.definitions.size)
                for (d in payload.definitions) {
                    buf.writeUtf(d.id)
                    buf.writeUtf(d.icon)
                    buf.writeUtf(d.name)
                    buf.writeUtf(d.description)
                    buf.writeBoolean(d.nonHideable)
                }
                buf.writeVarInt(payload.worldVisibleBadges.size)
                for ((uuid, list) in payload.worldVisibleBadges) {
                    buf.writeUUID(uuid)
                    buf.writeVarInt(list.size)
                    for (id in list) buf.writeUtf(id)
                }
                buf.writeVarInt(payload.myUnlocked.size)
                for (id in payload.myUnlocked) buf.writeUtf(id)
                buf.writeVarInt(payload.myHidden.size)
                for (id in payload.myHidden) buf.writeUtf(id)
            },
            { buf ->
                val defSize = buf.readVarInt()
                val defs = ArrayList<BadgeDefEntry>(defSize)
                for (i in 0 until defSize) {
                    defs.add(BadgeDefEntry(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readBoolean()))
                }
                val mapSize = buf.readVarInt()
                val map = HashMap<UUID, List<String>>(mapSize)
                for (i in 0 until mapSize) {
                    val uuid = buf.readUUID()
                    val count = buf.readVarInt()
                    val list = ArrayList<String>(count)
                    for (j in 0 until count) list.add(buf.readUtf())
                    map[uuid] = list
                }
                val unlockedCount = buf.readVarInt()
                val unlocked = ArrayList<String>(unlockedCount)
                for (i in 0 until unlockedCount) unlocked.add(buf.readUtf())
                val hiddenCount = buf.readVarInt()
                val hidden = HashSet<String>(hiddenCount)
                for (i in 0 until hiddenCount) hidden.add(buf.readUtf())
                BadgesSyncPayload(defs, map, unlocked, hidden)
            }
        )
    }
}

data class BadgeIconsSyncPayload(
    val iconPngs: Map<String, ByteArray>
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BadgeIconsSyncPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<BadgeIconsSyncPayload>(
            ResourceLocation.fromNamespaceAndPath(BadgesMod.MOD_ID, "badge_icons_sync")
        )

        val CODEC: StreamCodec<FriendlyByteBuf, BadgeIconsSyncPayload> = StreamCodec.of(
            { buf, payload ->
                buf.writeVarInt(payload.iconPngs.size)
                for ((id, bytes) in payload.iconPngs) {
                    buf.writeUtf(id, 64)
                    buf.writeByteArray(bytes)
                }
            },
            { buf ->
                val count = buf.readVarInt().coerceIn(0, 256)
                val map = LinkedHashMap<String, ByteArray>(count)
                for (i in 0 until count) {
                    val id = buf.readUtf(64)
                    val bytes = buf.readByteArray(TextCatalog.MAX_ICON_PNG_BYTES)
                    map[id] = bytes
                }
                BadgeIconsSyncPayload(map)
            }
        )
    }
}

/** Peticion del cliente al servidor para alternar la visibilidad de una insignia o pedir actualizacion. */
data class BadgeTogglePayload(
    val action: String, // "toggle", "show_all", "hide_all", "request"
    val badgeId: String
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BadgeTogglePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<BadgeTogglePayload>(
            ResourceLocation.fromNamespaceAndPath(BadgesMod.MOD_ID, "toggle")
        )
        val CODEC: StreamCodec<FriendlyByteBuf, BadgeTogglePayload> = StreamCodec.of(
            { buf, payload ->
                buf.writeUtf(payload.action)
                buf.writeUtf(payload.badgeId)
            },
            { buf -> BadgeTogglePayload(buf.readUtf(), buf.readUtf()) }
        )
    }
}

/** Paquete servidor -> cliente para alternar la visualización del propio NameTag en tercera persona (F5). */
data class BadgesToggleSelfNameTagPayload(val dummy: Boolean = true) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BadgesToggleSelfNameTagPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<BadgesToggleSelfNameTagPayload>(
            ResourceLocation.fromNamespaceAndPath(BadgesMod.MOD_ID, "toggle_self_nametag")
        )
        val CODEC: StreamCodec<FriendlyByteBuf, BadgesToggleSelfNameTagPayload> = StreamCodec.of(
            { buf, payload -> buf.writeBoolean(payload.dummy) },
            { buf -> BadgesToggleSelfNameTagPayload(buf.readBoolean()) }
        )
    }
}

data class OpenHitosPayload(val dummy: Byte = 0) : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenHitosPayload>(ResourceLocation.fromNamespaceAndPath(BadgesMod.MOD_ID, "open_hitos"))
        val CODEC: StreamCodec<FriendlyByteBuf, OpenHitosPayload> = StreamCodec.of(
            { buf, _ -> buf.writeByte(0) },
            { buf -> buf.readByte(); OpenHitosPayload() }
        )
    }
}

data class HitosSyncPayload(
    val json: String = "",
    val completedOnTime: Set<String> = emptySet(),
    val claimedRewards: Set<String> = emptySet(),
    val activeCatchupHitoId: String = "",
    val rctSummary: String = "",
    val rctAvailable: Boolean = false,
    val rctDefeatedBadges: Set<String> = emptySet(),
    val rctActiveRegion: String = "",
    val rctCompletedRegions: Set<String> = emptySet()
) : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<HitosSyncPayload>(ResourceLocation.fromNamespaceAndPath(BadgesMod.MOD_ID, "hitos_sync"))
        val CODEC: StreamCodec<FriendlyByteBuf, HitosSyncPayload> = StreamCodec.of(
            { buf, payload ->
                buf.writeUtf(payload.json, 262144)
                buf.writeVarInt(payload.completedOnTime.size)
                for (id in payload.completedOnTime) buf.writeUtf(id)
                buf.writeVarInt(payload.claimedRewards.size)
                for (id in payload.claimedRewards) buf.writeUtf(id)
                buf.writeUtf(payload.activeCatchupHitoId, 64)
                buf.writeUtf(payload.rctSummary, 160)
                buf.writeBoolean(payload.rctAvailable)
                buf.writeVarInt(payload.rctDefeatedBadges.size)
                for (id in payload.rctDefeatedBadges) buf.writeUtf(id)
                buf.writeUtf(payload.rctActiveRegion, 32)
                buf.writeVarInt(payload.rctCompletedRegions.size)
                for (reg in payload.rctCompletedRegions) buf.writeUtf(reg, 32)
            },
            { buf ->
                val json = buf.readUtf(262144)
                val onTimeCount = buf.readVarInt()
                val onTime = HashSet<String>(onTimeCount)
                for (i in 0 until onTimeCount) onTime.add(buf.readUtf())
                val claimedCount = buf.readVarInt()
                val claimed = HashSet<String>(claimedCount)
                for (i in 0 until claimedCount) claimed.add(buf.readUtf())
                val catchupId = buf.readUtf(64)
                val rctSummary = buf.readUtf(160)
                val rctAvailable = buf.readBoolean()
                val rctCount = buf.readVarInt()
                val rctBadges = HashSet<String>(rctCount)
                for (i in 0 until rctCount) rctBadges.add(buf.readUtf())
                val rctActiveRegion = buf.readUtf(32)
                val compCount = buf.readVarInt()
                val rctCompletedRegions = HashSet<String>(compCount)
                for (i in 0 until compCount) rctCompletedRegions.add(buf.readUtf(32))
                HitosSyncPayload(
                    json, onTime, claimed, catchupId, rctSummary, rctAvailable, rctBadges,
                    rctActiveRegion, rctCompletedRegions
                )
            }
        )
    }
}

object BadgeNetwork {
    private var registered = false

    fun registerCommon() {
        if (registered) return
        registered = true
        PayloadTypeRegistry.playS2C().register(BadgesSyncPayload.TYPE, BadgesSyncPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(BadgeIconsSyncPayload.TYPE, BadgeIconsSyncPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(BadgesToggleSelfNameTagPayload.TYPE, BadgesToggleSelfNameTagPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(OpenHitosPayload.TYPE, OpenHitosPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(HitosSyncPayload.TYPE, HitosSyncPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(BadgeTogglePayload.TYPE, BadgeTogglePayload.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(BadgeTogglePayload.TYPE) { payload, context ->
            val player = context.player()
            val server = player.server
            server.execute {
                when (payload.action) {
                    "toggle" -> {
                        BadgeData.toggleVisibility(player.uuid, payload.badgeId)
                        syncAll(server, includeIcons = false)
                    }
                    "show_all" -> {
                        BadgeData.setAllVisible(player.uuid, true)
                        syncAll(server, includeIcons = false)
                    }
                    "hide_all" -> {
                        BadgeData.setAllVisible(player.uuid, false)
                        syncAll(server, includeIcons = false)
                    }
                    "request" -> {
                        GymDefeatListener.syncLiveRctMilestones(player, notify = false)
                        syncPlayer(server, player)
                        syncHitosPlayer(player)
                    }
                    "claim_hito" -> handleClaimHito(server, player, payload.badgeId)
                    "toggle_catchup_xp" -> handleToggleCatchupXp(player, payload.badgeId)
                }
            }
        }
    }

    private fun handleClaimHito(server: MinecraftServer, player: ServerPlayer, rawHitoId: String) {
        val m = HitosManager.config.milestones.firstOrNull { it.id.equals(rawHitoId, true) }
        if (m == null || !m.isProgressMilestone()) return

        val pData = BadgeData.player(player.uuid)
        val cleanId = m.id.lowercase()
        if (cleanId in pData.claimedHitoRewards) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§e[Hitos] Ya reclamaste la recompensa de ${m.title}."))
            return
        }
        val rct = RctProgressBridge.readFor(player)
        val effectiveUnlocked = if (rct.available) rct.defeatedBadgeIds else emptySet()
        if (!HitosManager.hasUnlockedMilestone(effectiveUnlocked, m)) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Hitos] Aun no has completado ${m.title} en tu progreso actual."))
            return
        }
        if (cleanId !in pData.completedOnTimeHitos) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§e[Hitos] Este hito se completo fuera del plazo para recompensa a tiempo."))
            return
        }
        val currentDay = HitosManager.config.currentServerDay()
        if (currentDay < m.dayOffset) {
            player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal(
                    "§e[Hitos] La recompensa de '${m.title}' estara disponible a partir del Dia ${m.dayOffset} (Dia actual: $currentDay)."
                )
            )
            return
        }
        pData.claimedHitoRewards.add(cleanId)

        BadgeData.save()

        if (m.rewardDollars > 0L) {
            runCatching {
                com.desgraciamon.economy.CobbleDollarsBridge.deposit(player, m.rewardDollars)
            }
        }
        m.rewardCommands?.forEach { rawCmd ->
            val cmd = rawCmd.trim().removePrefix("/")
            if (cmd.isNotBlank()) {
                val resolved = cmd.replace("{player}", player.scoreboardName)
                runCatching {
                    server.commands.performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), resolved)
                }
            }
        }

        val summary = if (m.rewardSummary.isNotBlank()) m.rewardSummary else "Recompensa entregada"
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§a[Hitos] Recompensa reclamada de '${m.title}': §f$summary"))
        syncHitosPlayer(player)
    }

    private fun handleToggleCatchupXp(player: ServerPlayer, @Suppress("UNUSED_PARAMETER") rawHitoId: String) {
        val pData = BadgeData.player(player.uuid)
        val rct = RctProgressBridge.readFor(player)
        val effectiveUnlocked = if (rct.available) rct.defeatedBadgeIds else emptySet()
        val currentDay = HitosManager.config.currentServerDay()
        val earliest = HitosManager.earliestOverdueCatchupMilestone(effectiveUnlocked, currentDay)

        if (pData.activeCatchupHitoId.isNotBlank()) {
            val prevLabel = earliest?.formatXpBoostLabel()
                ?: HitosManager.config.milestones.firstOrNull { it.id.equals(pData.activeCatchupHitoId, true) }?.formatXpBoostLabel()
                ?: "XP"
            pData.activeCatchupHitoId = ""
            BadgeData.save()
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Hitos] Bono de alcance $prevLabel DESACTIVADO."))
            syncHitosPlayer(player)
            return
        }

        if (earliest == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§e[Hitos] No tienes hitos atrasados elegibles para el bono de alcance XP (Dia $currentDay)."))
            syncHitosPlayer(player)
            return
        }

        val boostLabel = earliest.formatXpBoostLabel()
        pData.activeCatchupHitoId = earliest.id.lowercase()
        BadgeData.save()
        player.sendSystemMessage(
            net.minecraft.network.chat.Component.literal(
                "§a[Hitos] Bono de alcance $boostLabel ACTIVADO (objetivo actual: '${earliest.title}')."
            )
        )
        syncHitosPlayer(player)
    }

    fun syncHitosPlayer(player: ServerPlayer) {
        if (!ServerPlayNetworking.canSend(player, HitosSyncPayload.TYPE)) return
        val pData = BadgeData.player(player.uuid)
        val rct = RctProgressBridge.readFor(player)
        val payload = HitosSyncPayload(
            json = HitosManager.toJson(),
            completedOnTime = pData.completedOnTimeHitos.toSet(),
            claimedRewards = pData.claimedHitoRewards.toSet(),
            activeCatchupHitoId = pData.activeCatchupHitoId,
            rctSummary = rct.summaryText,
            rctAvailable = rct.available,
            rctDefeatedBadges = rct.defeatedBadgeIds,
            rctActiveRegion = rct.regionName.lowercase().trim(),
            rctCompletedRegions = rct.completedRegions.map { it.lowercase().trim() }.toSet()
        )
        ServerPlayNetworking.send(player, payload)
    }

    private fun buildDefinitions(): List<BadgesSyncPayload.BadgeDefEntry> =
        TextCatalog.allBadgeIds().map { id ->
            BadgesSyncPayload.BadgeDefEntry(
                id = id,
                icon = TextCatalog.badgeIconRaw(id),
                name = TextCatalog.badgeNameRaw(id),
                description = TextCatalog.badgeDescriptionRaw(id),
                nonHideable = TextCatalog.isNonHideable(id)
            )
        }

    private fun buildWorldMap(server: MinecraftServer): Map<UUID, List<String>> {
        val map = HashMap<UUID, List<String>>()
        for (p in server.playerList.players) {
            val vis = BadgeData.visibleBadges(p.uuid)
            if (vis.isNotEmpty()) map[p.uuid] = vis
        }
        return map
    }

    fun syncIconsPlayer(player: ServerPlayer) {
        if (!ServerPlayNetworking.canSend(player, BadgeIconsSyncPayload.TYPE)) return
        ServerPlayNetworking.send(player, BadgeIconsSyncPayload(TextCatalog.allIconPngs()))
    }

    fun syncIconsAll(server: MinecraftServer) {
        val payload = BadgeIconsSyncPayload(TextCatalog.allIconPngs())
        for (player in server.playerList.players) {
            if (!ServerPlayNetworking.canSend(player, BadgeIconsSyncPayload.TYPE)) continue
            ServerPlayNetworking.send(player, payload)
        }
    }

    fun syncPlayer(server: MinecraftServer, player: ServerPlayer) {
        if (!ServerPlayNetworking.canSend(player, BadgesSyncPayload.TYPE)) return
        val pData = BadgeData.player(player.uuid)
        val payload = BadgesSyncPayload(
            definitions = buildDefinitions(),
            worldVisibleBadges = buildWorldMap(server),
            myUnlocked = pData.unlocked.filter { TextCatalog.badgeExists(it) },
            myHidden = pData.hidden.toSet()
        )
        ServerPlayNetworking.send(player, payload)
        syncIconsPlayer(player)
    }

    fun syncAll(server: MinecraftServer, includeIcons: Boolean = true) {
        val defs = buildDefinitions()
        val worldMap = buildWorldMap(server)
        for (player in server.playerList.players) {
            if (!ServerPlayNetworking.canSend(player, BadgesSyncPayload.TYPE)) continue
            val pData = BadgeData.player(player.uuid)
            ServerPlayNetworking.send(
                player,
                BadgesSyncPayload(
                    definitions = defs,
                    worldVisibleBadges = worldMap,
                    myUnlocked = pData.unlocked.filter { TextCatalog.badgeExists(it) },
                    myHidden = pData.hidden.toSet()
                )
            )
        }
        if (includeIcons) {
            syncIconsAll(server)
        }
    }
}

