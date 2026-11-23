package com.desgraciamon.badges

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.pokemon.experience.CandyExperienceSource
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Sincroniza en vivo el progreso de RCTMod con los Hitos y el bono de alcance XP.
 * Las insignias de gimnasio ya NO se otorgan automaticamente; solo el admin las entrega manualmente.
 */
object GymDefeatListener {
    @Volatile
    private var tickListenerRegistered = false
    @Volatile
    private var expListenerRegistered = false
    @Volatile
    private var battleListenerRegistered = false
    private var tickCounter = 0
    private val lastRctStateKey = java.util.concurrent.ConcurrentHashMap<UUID, String>()

    fun init(server: MinecraftServer) {
        if (!expListenerRegistered) {
            expListenerRegistered = true
            runCatching {
                CobblemonEvents.EXPERIENCE_GAINED_EVENT_PRE.subscribe(Priority.NORMAL) { event ->
                    if (event.source is CandyExperienceSource) return@subscribe
                    val pokemon = event.pokemon ?: return@subscribe
                    val player = pokemon.getOwnerPlayer() ?: return@subscribe
                    val pData = BadgeData.player(player.uuid)
                    val activeId = pData.activeCatchupHitoId
                    if (activeId.isBlank()) return@subscribe

                    val currentDay = HitosManager.config.currentServerDay()
                    val rct = RctProgressBridge.readFor(player)
                    val effectiveUnlocked = if (rct.available) rct.defeatedBadgeIds else emptySet()
                    val earliest = HitosManager.earliestOverdueCatchupMilestone(effectiveUnlocked, currentDay)

                    if (earliest == null) {
                        pData.activeCatchupHitoId = ""
                        BadgeData.save()
                        BadgeNetwork.syncHitosPlayer(player)
                        return@subscribe
                    }

                    val earliestId = earliest.id.lowercase()
                    if (!activeId.equals(earliestId, ignoreCase = true)) {
                        pData.activeCatchupHitoId = earliestId
                        BadgeData.save()
                        BadgeNetwork.syncHitosPlayer(player)
                    }

                    val base = event.experience
                    if (base > 0) {
                        event.experience = (base * earliest.effectiveXpMultiplier()).roundToInt().coerceAtLeast(1)
                    }
                }
            }.onFailure {
                BadgesMod.LOGGER.warn("No se pudo registrar listener de experiencia para hitos: ${it.message}")
            }
        }

        if (!battleListenerRegistered) {
            battleListenerRegistered = true
            runCatching {
                CobblemonEvents.BATTLE_VICTORY.subscribe { event ->
                    try {
                        for (winner in event.winners) {
                            for (uuid in getActorPlayerUUIDs(winner)) {
                                val p = server.playerList.getPlayer(uuid) ?: continue
                                syncLiveRctMilestones(p, notify = true)
                            }
                        }
                    } catch (e: Throwable) {
                        BadgesMod.LOGGER.error("Error en Cobblemon BATTLE_VICTORY listener para hitos", e)
                    }
                }
            }.onFailure {
                BadgesMod.LOGGER.warn("Cobblemon BATTLE_VICTORY no pudo ser registrado directamente: ${it.message}")
            }
        }

        if (!tickListenerRegistered) {
            tickListenerRegistered = true
            ServerTickEvents.END_SERVER_TICK.register { srv ->
                tickCounter++
                if (tickCounter >= 60) {
                    tickCounter = 0
                    TextCatalog.checkAndReloadIfModified(srv)
                    for (player in srv.playerList.players) {
                        syncLiveRctMilestones(player, notify = true)
                    }
                }
            }
        }
    }

    private fun getActorPlayerUUIDs(actor: Any): Collection<UUID> {
        return runCatching {
            val m = actor.javaClass.getMethod("getPlayerUUIDs")
            @Suppress("UNCHECKED_CAST")
            (m.invoke(actor) as? Iterable<UUID>)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun getTrainerIdFromEntity(entity: Any?): String? {
        if (entity == null) return null
        return runCatching {
            val method = entity.javaClass.getMethod("getTrainerId")
            method.invoke(entity) as? String
        }.getOrNull()
    }

    fun onTrainerEntityDefeated(player: ServerPlayer, mob: Any?) {
        val trainerId = getTrainerIdFromEntity(mob) ?: return
        onTrainerDefeated(player, trainerId)
    }

    fun onTrainerDefeated(player: ServerPlayer, trainerId: String) {
        val gymDef = GymBadges.findByLeader(trainerId)
        if (gymDef != null) {
            runCatching {
                com.desgraciamon.announcements.BulletinStore.progressBadge(player, gymDef.id)
            }
        }
        syncLiveRctMilestones(player, notify = true)
    }

    fun onPlayerJoin(player: ServerPlayer) {
        lastRctStateKey.remove(player.uuid)
        syncLiveRctMilestones(player, notify = false)
    }

    /**
     * Evalua siempre el progreso ACTUAL de RCTMod del jugador.
     * - Si un admin adelanta o retrocede el progreso en RCT, los hitos se actualizan al instante.
     * - Nunca borra `claimedHitoRewards` para impedir que una recompensa se reclame dos veces.
     * - Nunca otorga insignias de jugador automaticamente.
     */
    fun syncLiveRctMilestones(player: ServerPlayer, notify: Boolean) {
        val summary = RctProgressBridge.readFor(player)
        if (!summary.available) return

        val pData = BadgeData.player(player.uuid)
        val currentDay = HitosManager.config.currentServerDay()
        var dirty = false

        for (m in HitosManager.config.progressionMilestones()) {
            val cleanId = m.id.lowercase()
            val unlockedNow = HitosManager.hasUnlockedMilestone(summary.defeatedBadgeIds, m)

            if (unlockedNow) {
                if (currentDay <= HitosManager.config.endDayOf(m)) {
                    if (pData.completedOnTimeHitos.add(cleanId)) {
                        dirty = true
                        if (notify && cleanId !in pData.claimedHitoRewards) {
                            val msg = if (currentDay < m.dayOffset) {
                                "§a[Hitos] Completaste '${m.title}'. Podras reclamar tu recompensa a partir del Dia ${m.dayOffset} en /hitos."
                            } else {
                                "§a[Hitos] Completaste a tiempo '${m.title}'. Puedes reclamar tu recompensa en /hitos."
                            }
                            player.sendSystemMessage(Component.literal(msg))
                        }
                    }
                }
            }
        }

        if (pData.activeCatchupHitoId.isNotBlank()) {
            val earliest = HitosManager.earliestOverdueCatchupMilestone(summary.defeatedBadgeIds, currentDay)
            if (earliest == null) {
                pData.activeCatchupHitoId = ""
                dirty = true
                if (notify) {
                    player.sendSystemMessage(
                        Component.literal("§e[Hitos] Bono de alcance XP desactivado automaticamente: ya no tienes hitos atrasados.")
                    )
                }
            } else if (!pData.activeCatchupHitoId.equals(earliest.id, ignoreCase = true)) {
                pData.activeCatchupHitoId = earliest.id.lowercase()
                dirty = true
                if (notify) {
                    player.sendSystemMessage(
                        Component.literal(
                            "§a[Hitos] Bono de alcance XP actualizado a ${earliest.formatXpBoostLabel()} (siguiente hito atrasado: '${earliest.title}')."
                        )
                    )
                }
            }
        }

        if (dirty) {
            BadgeData.save()
        }

        val stateKey = buildString {
            append(summary.seriesId).append('|')
            append(summary.regionName).append('|')
            append(summary.levelCap).append('|')
            append(summary.completedRegions.sorted().joinToString(",")).append('|')
            append(summary.defeatedBadgeIds.sorted().joinToString(","))
        }
        val prevKey = lastRctStateKey[player.uuid]
        if (dirty || prevKey != stateKey) {
            lastRctStateKey[player.uuid] = stateKey
            BadgeNetwork.syncHitosPlayer(player)
        }
    }
}
