package com.desgraciamon.announcements

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class DailyScheduleManager(
    private val clock: Clock = Clock.systemUTC(),
    initialConfig: DailyCycleConfig = DailyCycleConfig()
) {
    data class TickEvaluation(
        val cycleReset: Boolean = false,
        val newlyUnlockedSlots: List<Int> = emptyList(),
        val cycleId: String = ""
    )

    data class PauseResult(
        val changed: Boolean,
        val paused: Boolean,
        val evaluation: TickEvaluation
    )

    data class RotateResult(
        val ok: Boolean,
        val rotatedSlots: List<Int> = emptyList(),
        val previousMissionIds: Set<String> = emptySet(),
        val newMissionIds: Map<Int, String> = emptyMap(),
        val unlockedRotatedSlots: List<Int> = emptyList(),
        val errorMessage: String = ""
    )

    data class SimulateResult(
        val ok: Boolean,
        val mode: String = "",
        val evaluation: TickEvaluation = TickEvaluation(),
        val message: String = ""
    )

    private var config: DailyCycleConfig = initialConfig
    private var zoneId: ZoneId = resolveZoneId(initialConfig.timezone)
    private var resetTime: LocalTime = parseTime(initialConfig.resetTime, LocalTime.of(8, 0))
    private var slotUnlockTimes: Map<Int, LocalTime> = mapOf(
        1 to parseTime(initialConfig.slot1UnlockTime, LocalTime.of(8, 0)),
        2 to parseTime(initialConfig.slot2UnlockTime, LocalTime.of(13, 30)),
        3 to parseTime(initialConfig.slot3UnlockTime, LocalTime.of(19, 0))
    )

    private var state: DailyPersistedState = DailyPersistedState(shuffleSeed = initialConfig.seed)
    private var poolsBySlot: Map<Int, List<Mission>> = mapOf(
        1 to emptyList(),
        2 to emptyList(),
        3 to emptyList()
    )
    private var missionIndex: Map<String, Mission> = emptyMap()

    @Volatile private var simulatedBaseInstant: Instant? = null
    @Volatile private var simulatedSetAtRealInstant: Instant? = null

    fun getZoneId(): ZoneId = zoneId
    fun isPaused(): Boolean = state.paused
    fun activeCycleId(): String = state.activeCycleId
    fun isSimulated(): Boolean = simulatedBaseInstant != null

    fun configure(
        newConfig: DailyCycleConfig,
        itemsMissions: List<Mission>,
        captureMissions: List<Mission>,
        combatMissions: List<Mission>
    ) {
        config = newConfig
        zoneId = resolveZoneId(newConfig.timezone)
        resetTime = parseTime(newConfig.resetTime, LocalTime.of(8, 0))
        slotUnlockTimes = mapOf(
            1 to parseTime(newConfig.slot1UnlockTime, LocalTime.of(8, 0)),
            2 to parseTime(newConfig.slot2UnlockTime, LocalTime.of(13, 30)),
            3 to parseTime(newConfig.slot3UnlockTime, LocalTime.of(19, 0))
        )
        if (state.shuffleSeed == 0L) {
            state.shuffleSeed = newConfig.seed
        }
        val p1 = itemsMissions.map { prepareSlotMission(it, 1, "ITEMS") }.sortedBy { it.id }
        val p2 = captureMissions.map { prepareSlotMission(it, 2, "CAPTURA") }.sortedBy { it.id }
        val p3 = combatMissions.map { prepareSlotMission(it, 3, "COMBATE") }.sortedBy { it.id }
        poolsBySlot = mapOf(1 to p1, 2 to p2, 3 to p3)
        missionIndex = (p1 + p2 + p3).associateBy { it.id }
        ensureSlotDefaults()
    }

    fun loadState(persisted: DailyPersistedState, markInitiallyUnlockedAsAnnounced: Boolean = true) {
        state = DailyPersistedState(
            schemaVersion = persisted.schemaVersion,
            activeCycleId = persisted.activeCycleId,
            paused = persisted.paused,
            shuffleSeed = if (persisted.shuffleSeed != 0L) persisted.shuffleSeed else config.seed,
            slotEpochs = persisted.slotEpochs.toMutableMap(),
            slotCursors = persisted.slotCursors.toMutableMap(),
            slotMissionIds = persisted.slotMissionIds.toMutableMap(),
            slotEpochAvoidIds = persisted.slotEpochAvoidIds.toMutableMap(),
            forceOpenedSlots = persisted.forceOpenedSlots.toMutableSet(),
            announcedSlotsInCycle = persisted.announcedSlotsInCycle.toMutableSet()
        )
        clearSimulation()
        ensureSlotDefaults()

        val effInstant = effectiveInstant()
        val effZdt = ZonedDateTime.ofInstant(effInstant, zoneId)
        val currentCycleDate = computeCycleDate(effZdt)
        if (state.activeCycleId.isBlank()) {
            state.activeCycleId = currentCycleDate.toString()
            for (slot in 1..3) {
                if (state.slotMissionIds[slot].isNullOrBlank()) {
                    pickInitialSlot(slot)
                }
            }
            if (markInitiallyUnlockedAsAnnounced && !state.paused) {
                for (slot in 1..3) {
                    if (isSlotUnlockedAt(currentCycleDate, effInstant, slot)) {
                        state.announcedSlotsInCycle.add(slot)
                    }
                }
            }
        } else {
            repairMissingSlotMissions()
            if (!state.paused) {
                evaluateTick()
            }
        }
    }

    fun exportState(): DailyPersistedState = DailyPersistedState(
        schemaVersion = state.schemaVersion,
        activeCycleId = state.activeCycleId,
        paused = state.paused,
        shuffleSeed = state.shuffleSeed,
        slotEpochs = state.slotEpochs.toMutableMap(),
        slotCursors = state.slotCursors.toMutableMap(),
        slotMissionIds = state.slotMissionIds.toMutableMap(),
        slotEpochAvoidIds = state.slotEpochAvoidIds.toMutableMap(),
        forceOpenedSlots = state.forceOpenedSlots.toMutableSet(),
        announcedSlotsInCycle = state.announcedSlotsInCycle.toMutableSet()
    )

    fun evaluateTick(): TickEvaluation {
        if (state.paused) {
            return TickEvaluation(cycleReset = false, newlyUnlockedSlots = emptyList(), cycleId = state.activeCycleId)
        }
        val effInstant = effectiveInstant()
        val effZdt = ZonedDateTime.ofInstant(effInstant, zoneId)
        val currentCycleDate = computeCycleDate(effZdt)
        val currentCycleId = currentCycleDate.toString()

        var cycleReset = false
        if (state.activeCycleId.isBlank()) {
            state.activeCycleId = currentCycleId
            for (slot in 1..3) {
                if (state.slotMissionIds[slot].isNullOrBlank()) {
                    pickInitialSlot(slot)
                }
            }
        } else if (state.activeCycleId != currentCycleId) {
            state.activeCycleId = currentCycleId
            state.forceOpenedSlots.clear()
            state.announcedSlotsInCycle.clear()
            for (slot in 1..3) {
                advanceSlot(slot)
            }
            cycleReset = true
        } else {
            repairMissingSlotMissions()
        }

        val newlyUnlocked = mutableListOf<Int>()
        for (slot in 1..3) {
            if (isSlotUnlockedAt(currentCycleDate, effInstant, slot)) {
                if (state.announcedSlotsInCycle.add(slot)) {
                    newlyUnlocked.add(slot)
                }
            }
        }
        return TickEvaluation(cycleReset = cycleReset, newlyUnlockedSlots = newlyUnlocked, cycleId = currentCycleId)
    }

    fun setPaused(newPaused: Boolean): PauseResult {
        if (newPaused == state.paused) {
            return PauseResult(changed = false, paused = state.paused, evaluation = TickEvaluation(cycleId = state.activeCycleId))
        }
        if (newPaused) {
            state.paused = true
            return PauseResult(changed = true, paused = true, evaluation = TickEvaluation(cycleId = state.activeCycleId))
        }
        state.paused = false
        clearSimulation()
        val evaluation = evaluateTick()
        return PauseResult(changed = true, paused = false, evaluation = evaluation)
    }

    fun rotate(slotSpec: String): RotateResult {
        val trimmed = slotSpec.trim().lowercase()
        val slots = when (trimmed) {
            "1" -> listOf(1)
            "2" -> listOf(2)
            "3" -> listOf(3)
            "todas", "all", "" -> listOf(1, 2, 3)
            else -> return RotateResult(ok = false, errorMessage = "Parametro invalido: usa 1, 2, 3 o todas.")
        }
        val prevIds = mutableSetOf<String>()
        val newIds = mutableMapOf<Int, String>()
        for (slot in slots) {
            state.slotMissionIds[slot]?.takeIf { it.isNotBlank() }?.let { prevIds.add(it) }
            val nextId = advanceSlot(slot)
            if (nextId.isNotBlank()) {
                newIds[slot] = nextId
            }
        }
        val effInstant = effectiveInstant()
        val cycleDate = parseCycleDateOrDefault(state.activeCycleId, computeCycleDate(ZonedDateTime.ofInstant(effInstant, zoneId)))
        val unlockedRotated = if (state.paused) {
            emptyList()
        } else {
            slots.filter { isSlotUnlockedAt(cycleDate, effInstant, it) }
        }
        return RotateResult(
            ok = true,
            rotatedSlots = slots,
            previousMissionIds = prevIds,
            newMissionIds = newIds,
            unlockedRotatedSlots = unlockedRotated
        )
    }

    fun forceOpen(slot: Int): Boolean {
        if (slot !in 1..3 || state.paused) return false
        val effInstant = effectiveInstant()
        val cycleDate = parseCycleDateOrDefault(state.activeCycleId, computeCycleDate(ZonedDateTime.ofInstant(effInstant, zoneId)))
        if (isSlotUnlockedAt(cycleDate, effInstant, slot)) return false
        state.forceOpenedSlots.add(slot)
        state.announcedSlotsInCycle.add(slot)
        return true
    }

    fun simulateTime(mode: String): SimulateResult {
        val normalized = mode.trim().lowercase()
        if (normalized == "reset") {
            clearSimulation()
            state.forceOpenedSlots.clear()
            val realInstant = clock.instant()
            val realZdt = ZonedDateTime.ofInstant(realInstant, zoneId)
            val realCycleDate = computeCycleDate(realZdt)
            state.announcedSlotsInCycle.removeIf { slot -> !isSlotUnlockedAt(realCycleDate, realInstant, slot) }
            val eval = evaluateTick()
            return SimulateResult(
                ok = true,
                mode = "reset",
                evaluation = eval,
                message = "Simulacion restablecida al reloj real (${zoneId.id})."
            )
        }
        val targetTime = when (normalized) {
            "08:00", "8:00" -> LocalTime.of(8, 0)
            "13:30" -> LocalTime.of(13, 30)
            "19:00" -> LocalTime.of(19, 0)
            else -> return SimulateResult(
                ok = false,
                mode = normalized,
                message = "Hora invalida. Usa: 08:00, 13:30, 19:00 o reset."
            )
        }
        val realNow = clock.instant()
        val realZdt = ZonedDateTime.ofInstant(realNow, zoneId)
        val baseCycleDate = if (state.activeCycleId.isNotBlank()) {
            parseCycleDateOrDefault(state.activeCycleId, computeCycleDate(realZdt))
        } else {
            computeCycleDate(realZdt)
        }
        val simDate = if (targetTime.isBefore(resetTime)) baseCycleDate.plusDays(1) else baseCycleDate
        val simInstant = simDate.atTime(targetTime).atZone(zoneId).toInstant()
        simulatedBaseInstant = simInstant
        simulatedSetAtRealInstant = realNow
        state.forceOpenedSlots.clear()
        state.announcedSlotsInCycle.removeIf { slot -> !isSlotUnlockedAt(baseCycleDate, simInstant, slot) }
        val eval = evaluateTick()
        return SimulateResult(
            ok = true,
            mode = normalized,
            evaluation = eval,
            message = "Hora simulada aplicada: $normalized (${zoneId.id})."
        )
    }

    fun activeUnlockedMissions(): List<Mission> {
        if (state.paused) return emptyList()
        val effInstant = effectiveInstant()
        val cycleDate = parseCycleDateOrDefault(state.activeCycleId, computeCycleDate(ZonedDateTime.ofInstant(effInstant, zoneId)))
        return (1..3).mapNotNull { slot ->
            if (!isSlotUnlockedAt(cycleDate, effInstant, slot)) null
            else missionForSlot(slot)
        }
    }

    fun isMissionUnlocked(missionId: String): Boolean {
        if (state.paused) return false
        return activeUnlockedMissions().any { it.id == missionId }
    }

    fun snapshot(
        playerCompleted: Set<String> = emptySet(),
        playerClaimed: Set<String> = emptySet(),
        bonusClaimedCycle: String = "",
        bonusConfig: DailyBonusConfig = DailyBonusConfig()
    ): DailyCycleSnapshot {
        val effInstant = effectiveInstant()
        val effZdt = ZonedDateTime.ofInstant(effInstant, zoneId)
        val computedCycleDate = computeCycleDate(effZdt)
        val displayCycleDate = parseCycleDateOrDefault(state.activeCycleId, computedCycleDate)
        val cycleId = state.activeCycleId.ifBlank { displayCycleDate.toString() }
        val nextResetInstant = nextResetZdt(if (state.paused) computedCycleDate else displayCycleDate).toInstant()
        val resetRemaining = Duration.between(effInstant, nextResetInstant).seconds.coerceAtLeast(0L)

        val slots = (1..3).map { slot ->
            val slotType = slotTypeName(slot)
            if (state.paused) {
                DailySlotSnapshot(
                    slotIndex = slot,
                    slotType = slotType,
                    unlocked = false,
                    unlockRemainingSeconds = 0L,
                    mission = null,
                    completed = false,
                    claimed = false
                )
            } else {
                val unlockInstant = slotUnlockZdt(displayCycleDate, slot).toInstant()
                val unlocked = !effInstant.isBefore(unlockInstant) || slot in state.forceOpenedSlots
                val remaining = if (unlocked) 0L else Duration.between(effInstant, unlockInstant).seconds.coerceAtLeast(0L)
                val mission = if (unlocked) missionForSlot(slot) else null
                val completed = unlocked && mission != null && mission.id in playerCompleted
                val claimed = unlocked && mission != null && mission.id in playerClaimed
                DailySlotSnapshot(
                    slotIndex = slot,
                    slotType = slotType,
                    unlocked = unlocked,
                    unlockRemainingSeconds = remaining,
                    mission = mission,
                    completed = completed,
                    claimed = claimed
                )
            }
        }

        val completedCount = slots.count { it.completed }
        val bonusClaimed = cycleId.isNotBlank() && bonusClaimedCycle == cycleId
        val bonus = DailyBonusSnapshot(
            completedCount = completedCount,
            totalSlots = 3,
            claimed = bonusClaimed,
            moneyReward = bonusConfig.moneyReward,
            itemRewards = bonusConfig.itemRewards.map { it.copy() }
        )
        return DailyCycleSnapshot(
            paused = state.paused,
            cycleId = cycleId,
            resetRemainingSeconds = resetRemaining,
            serverTimeEpochMs = effInstant.toEpochMilli(),
            slots = slots,
            bonus = bonus
        )
    }

    fun missionForSlot(slot: Int): Mission? {
        val id = state.slotMissionIds[slot].orEmpty()
        if (id.isNotBlank()) {
            missionIndex[id]?.let { return it }
        }
        val pool = poolsBySlot[slot].orEmpty()
        if (pool.isEmpty()) return null
        val cursor = (state.slotCursors[slot] ?: 0).coerceIn(0, pool.lastIndex)
        val epoch = state.slotEpochs[slot] ?: 0L
        val avoidId = state.slotEpochAvoidIds[slot].orEmpty()
        val deck = shuffledDeck(slot, epoch, avoidId)
        val fallbackId = deck.getOrNull(cursor) ?: pool[cursor].id
        return missionIndex[fallbackId] ?: pool.first()
    }

    private fun clearSimulation() {
        simulatedBaseInstant = null
        simulatedSetAtRealInstant = null
    }

    private fun effectiveInstant(): Instant {
        val realNow = clock.instant()
        val base = simulatedBaseInstant ?: return realNow
        val setAt = simulatedSetAtRealInstant ?: return realNow
        val elapsed = Duration.between(setAt, realNow)
        return if (elapsed.isNegative) base else base.plus(elapsed)
    }

    private fun computeCycleDate(zdt: ZonedDateTime): LocalDate {
        return if (!zdt.toLocalTime().isBefore(resetTime)) zdt.toLocalDate() else zdt.toLocalDate().minusDays(1)
    }

    private fun nextResetZdt(cycleDate: LocalDate): ZonedDateTime {
        return cycleDate.plusDays(1).atTime(resetTime).atZone(zoneId)
    }

    private fun slotUnlockZdt(cycleDate: LocalDate, slot: Int): ZonedDateTime {
        val slotTime = slotUnlockTimes[slot] ?: resetTime
        val unlockDate = if (slotTime.isBefore(resetTime)) cycleDate.plusDays(1) else cycleDate
        return unlockDate.atTime(slotTime).atZone(zoneId)
    }

    private fun isSlotUnlockedAt(cycleDate: LocalDate, instant: Instant, slot: Int): Boolean {
        if (state.paused) return false
        if (slot in state.forceOpenedSlots) return true
        val unlockInstant = slotUnlockZdt(cycleDate, slot).toInstant()
        return !instant.isBefore(unlockInstant)
    }

    private fun ensureSlotDefaults() {
        for (slot in 1..3) {
            state.slotEpochs.putIfAbsent(slot, 0L)
            state.slotCursors.putIfAbsent(slot, 0)
        }
    }

    private fun pickInitialSlot(slot: Int) {
        val deck = shuffledDeck(slot, state.slotEpochs[slot] ?: 0L, state.slotEpochAvoidIds[slot].orEmpty())
        if (deck.isEmpty()) {
            state.slotMissionIds[slot] = ""
            return
        }
        val cursor = (state.slotCursors[slot] ?: 0).coerceIn(0, deck.lastIndex)
        state.slotCursors[slot] = cursor
        state.slotMissionIds[slot] = deck[cursor]
    }

    private fun repairMissingSlotMissions() {
        for (slot in 1..3) {
            val pool = poolsBySlot[slot].orEmpty()
            if (pool.isEmpty()) continue
            val currentId = state.slotMissionIds[slot].orEmpty()
            if (currentId.isBlank() || pool.none { it.id == currentId }) {
                val epoch = state.slotEpochs[slot] ?: 0L
                val avoidId = state.slotEpochAvoidIds[slot].orEmpty()
                val deck = shuffledDeck(slot, epoch, avoidId)
                val cursor = (state.slotCursors[slot] ?: 0).coerceIn(0, deck.lastIndex)
                state.slotCursors[slot] = cursor
                state.slotMissionIds[slot] = deck[cursor]
            }
        }
    }

    private fun advanceSlot(slot: Int): String {
        val pool = poolsBySlot[slot].orEmpty()
        if (pool.isEmpty()) {
            state.slotMissionIds[slot] = ""
            return ""
        }
        val prevId = state.slotMissionIds[slot].orEmpty()
        var epoch = state.slotEpochs[slot] ?: 0L
        var avoidId = state.slotEpochAvoidIds[slot].orEmpty()
        var deck = shuffledDeck(slot, epoch, avoidId)
        var cursor = (state.slotCursors[slot] ?: 0) + 1
        if (cursor >= deck.size) {
            epoch += 1L
            cursor = 0
            avoidId = prevId
            state.slotEpochAvoidIds[slot] = avoidId
            deck = shuffledDeck(slot, epoch, avoidId)
        }
        state.slotEpochs[slot] = epoch
        state.slotCursors[slot] = cursor
        val nextId = deck[cursor]
        state.slotMissionIds[slot] = nextId
        return nextId
    }

    private fun shuffledDeck(slot: Int, epoch: Long, avoidFirstId: String): List<String> {
        val pool = poolsBySlot[slot].orEmpty()
        if (pool.isEmpty()) return emptyList()
        val list = pool.map { it.id }.toMutableList()
        val rng = Random(mixSeed(state.shuffleSeed, slot, epoch))
        for (i in list.lastIndex downTo 1) {
            val j = rng.nextInt(i + 1)
            if (i != j) {
                val tmp = list[i]
                list[i] = list[j]
                list[j] = tmp
            }
        }
        if (avoidFirstId.isNotEmpty() && list.size > 1 && list[0] == avoidFirstId) {
            val tmp = list[0]
            list[0] = list[1]
            list[1] = tmp
        }
        return list
    }

    private fun mixSeed(baseSeed: Long, slot: Int, epoch: Long): Long {
        var z = baseSeed xor (slot.toLong() * 0x9E3779B97F4A7C15UL.toLong()) xor (epoch * 0xBF58476D1CE4E5B9UL.toLong())
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBUL.toLong()
        return z xor (z ushr 31)
    }

    private fun prepareSlotMission(mission: Mission, slot: Int, tag: String): Mission {
        val copy = mission.copy(
            objectives = mission.objectives.map { it.copy() }.toMutableList(),
            itemRewards = mission.itemRewards.map { it.copy() }.toMutableList(),
            pokemonReward = null,
            pokemonRewards = mutableListOf(),
            daily = true,
            repeatable = false,
            isGuildMission = false,
            targetUsesMemberCount = false,
            dailySlot = slot,
            categoryTag = mission.categoryTag.ifBlank { tag }
        )
        return copy
    }

    private fun parseCycleDateOrDefault(raw: String, fallback: LocalDate): LocalDate {
        if (raw.isBlank()) return fallback
        return runCatching { LocalDate.parse(raw.trim()) }.getOrDefault(fallback)
    }

    private fun resolveZoneId(raw: String): ZoneId {
        if (raw.isBlank()) return DEFAULT_ZONE_ID
        return runCatching { ZoneId.of(raw.trim()) }.getOrDefault(DEFAULT_ZONE_ID)
    }

    private fun parseTime(raw: String, fallback: LocalTime): LocalTime {
        if (raw.isBlank()) return fallback
        return runCatching { LocalTime.parse(raw.trim()) }.getOrDefault(fallback)
    }

    private fun slotTypeName(slot: Int): String = when (slot) {
        1 -> "ITEMS"
        2 -> "CAPTURA"
        3 -> "COMBATE"
        else -> "ESPECIAL"
    }

    companion object {
        val DEFAULT_ZONE_ID: ZoneId = ZoneId.of("America/Caracas")
    }
}
