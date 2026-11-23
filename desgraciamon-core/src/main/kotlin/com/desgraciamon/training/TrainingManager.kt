package com.desgraciamon.training

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.npc.NPCClasses
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.battles.BattleBuilder
import com.cobblemon.mod.common.entity.npc.NPCEntity
import com.desgraciamon.badges.AdminAuth
import com.desgraciamon.badges.HitosConfig
import com.google.gson.GsonBuilder
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

data class ActiveTraining(
    val playerUuid: UUID,
    val player: ServerPlayer,
    val npc: NPCEntity,
    val trainerName: String,
    val startedAt: Long = System.currentTimeMillis()
)

data class TrainingConfig(
    var enabled: Boolean = true,
    var dailyLimit: Int = 8,
    var cooldownSeconds: Int = 15,
    var minLevelOffset: Int = -2,
    var maxLevelOffset: Int = -1
)

private data class DailyUsageStore(
    var dayKey: String = "",
    var counts: MutableMap<String, Int> = LinkedHashMap()
)

private data class TrainerPreset(
    val name: String,
    val earlyTeam: List<String>,
    val midTeam: List<String>,
    val lateTeam: List<String>,
    val skill: Int = 3
)

object TrainingManager {
    private val logger = LoggerFactory.getLogger("desgraciamon4-training")
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val activeTrainings = ConcurrentHashMap<UUID, ActiveTraining>()
    private val playerCooldowns = ConcurrentHashMap<UUID, Long>()
    private val configFile = File("config/desgraciamon4_training/config.json")
    private val usageFile = File("config/desgraciamon4_training/daily_usage.json")
    private val usageLock = Any()

    @Volatile
    private var config: TrainingConfig = TrainingConfig()

    private var usageStore: DailyUsageStore = DailyUsageStore()

    val enabled: Boolean get() = config.enabled
    val dailyLimit: Int get() = config.dailyLimit

    private val TRAINER_PRESETS = listOf(
        TrainerPreset(
            name = "Entrenador Guay",
            earlyTeam = listOf("charmander", "psyduck", "pikachu", "riolu"),
            midTeam = listOf("charmeleon", "golduck", "raichu", "lucario"),
            lateTeam = listOf("arcanine", "starmie", "jolteon", "lucario")
        ),
        TrainerPreset(
            name = "Montanero Experto",
            earlyTeam = listOf("geodude", "rhyhorn", "onix", "drilbur"),
            midTeam = listOf("graveler", "rhyhorn", "lairon", "excadrill"),
            lateTeam = listOf("golem", "rhyperior", "aggron", "excadrill")
        ),
        TrainerPreset(
            name = "Domadragon",
            earlyTeam = listOf("dratini", "bagon", "gible", "swablu"),
            midTeam = listOf("dragonair", "shelgon", "gabite", "altaria"),
            lateTeam = listOf("dragonite", "salamence", "garchomp", "altaria")
        ),
        TrainerPreset(
            name = "Cinturon Negro",
            earlyTeam = listOf("machop", "makuhita", "riolu", "mankey"),
            midTeam = listOf("machoke", "hariyama", "lucario", "primeape"),
            lateTeam = listOf("machamp", "hariyama", "lucario", "heracross")
        ),
        TrainerPreset(
            name = "Medium Tactico",
            earlyTeam = listOf("abra", "ralts", "gastly", "eevee"),
            midTeam = listOf("kadabra", "kirlia", "haunter", "espeon"),
            lateTeam = listOf("alakazam", "gardevoir", "gengar", "metagross")
        ),
        TrainerPreset(
            name = "Pescador Veterano",
            earlyTeam = listOf("squirtle", "poliwag", "chinchou", "staryu"),
            midTeam = listOf("wartortle", "poliwhirl", "lanturn", "starmie"),
            lateTeam = listOf("gyarados", "lapras", "milotic", "swampert")
        ),
        TrainerPreset(
            name = "Cientifico de Combate",
            earlyTeam = listOf("magnemite", "voltorb", "elekid", "porygon"),
            midTeam = listOf("magneton", "electrode", "electabuzz", "porygon2"),
            lateTeam = listOf("magnezone", "electivire", "rotom", "scizor")
        ),
        TrainerPreset(
            name = "Ornitologo Viajero",
            earlyTeam = listOf("pidgey", "starly", "fletchling", "rookidee"),
            midTeam = listOf("pidgeotto", "staravia", "fletchinder", "corvisquire"),
            lateTeam = listOf("pidgeot", "staraptor", "talonflame", "corviknight")
        ),
        TrainerPreset(
            name = "Ninja de Fucsia",
            earlyTeam = listOf("zubat", "venipede", "sneasel", "skorupi"),
            midTeam = listOf("golbat", "whirlipede", "sneasel", "drapion"),
            lateTeam = listOf("crobat", "scolipede", "weavile", "drapion")
        ),
        TrainerPreset(
            name = "Piromano Tactico",
            earlyTeam = listOf("growlithe", "vulpix", "houndour", "litwick"),
            midTeam = listOf("arcanine", "ninetales", "houndoom", "lampent"),
            lateTeam = listOf("arcanine", "chandelure", "volcarona", "infernape")
        ),
        TrainerPreset(
            name = "Guarda Forestal",
            earlyTeam = listOf("bulbasaur", "shroomish", "roselia", "scyther"),
            midTeam = listOf("ivysaur", "breloom", "roserade", "scyther"),
            lateTeam = listOf("venusaur", "breloom", "roserade", "scizor")
        ),
        TrainerPreset(
            name = "Veterana de Combate",
            earlyTeam = listOf("eevee", "growlithe", "gastly", "dratini"),
            midTeam = listOf("umbreon", "arcanine", "haunter", "dragonair"),
            lateTeam = listOf("snorlax", "tyranitar", "togekiss", "dragapult")
        )
    )

    fun currentTrainingDayKey(now: ZonedDateTime = ZonedDateTime.now(HitosConfig.SERVER_ZONE)): String =
        now.withZoneSameInstant(HitosConfig.SERVER_ZONE)
            .minusHours(HitosConfig.DAY_ROLLOVER_HOUR.toLong())
            .toLocalDate()
            .toString()

    fun isTrainingBattle(battleId: UUID): Boolean = activeTrainings.containsKey(battleId)

    private fun loadConfig() {
        runCatching {
            if (configFile.exists()) {
                configFile.reader(StandardCharsets.UTF_8).use { reader ->
                    val parsed = gson.fromJson(reader, TrainingConfig::class.java)
                    if (parsed != null) {
                        parsed.dailyLimit = parsed.dailyLimit.coerceIn(0, 100)
                        parsed.cooldownSeconds = parsed.cooldownSeconds.coerceIn(5, 600)
                        parsed.minLevelOffset = parsed.minLevelOffset.coerceIn(-10, 0)
                        parsed.maxLevelOffset = parsed.maxLevelOffset.coerceIn(parsed.minLevelOffset, 0)
                        config = parsed
                    }
                }
            }
            saveConfig()
        }.onFailure {
            logger.warn("No se pudo cargar config de /entrenar, usando valores por defecto.", it)
            config = TrainingConfig()
            saveConfig()
        }
    }

    private fun saveConfig() {
        runCatching {
            configFile.parentFile?.mkdirs()
            configFile.writeText(gson.toJson(config), StandardCharsets.UTF_8)
        }
    }

    private fun loadUsage() {
        synchronized(usageLock) {
            val today = currentTrainingDayKey()
            runCatching {
                if (usageFile.exists()) {
                    usageFile.reader(StandardCharsets.UTF_8).use { reader ->
                        val parsed = gson.fromJson(reader, DailyUsageStore::class.java)
                        if (parsed != null && parsed.dayKey == today) {
                            usageStore = parsed
                            return
                        }
                    }
                }
            }.onFailure {
                logger.warn("No se pudo cargar uso diario de /entrenar, reiniciando contador.", it)
            }
            usageStore = DailyUsageStore(dayKey = today, counts = LinkedHashMap())
            saveUsageLocked()
        }
    }

    private fun saveUsageLocked() {
        runCatching {
            usageFile.parentFile?.mkdirs()
            usageFile.writeText(gson.toJson(usageStore), StandardCharsets.UTF_8)
        }
    }

    private fun ensureCurrentDayLocked() {
        val today = currentTrainingDayKey()
        if (usageStore.dayKey != today) {
            usageStore = DailyUsageStore(dayKey = today, counts = LinkedHashMap())
            saveUsageLocked()
        }
    }

    fun getDailyUsed(playerUuid: UUID): Int {
        synchronized(usageLock) {
            ensureCurrentDayLocked()
            return usageStore.counts[playerUuid.toString()] ?: 0
        }
    }

    fun getDailyRemaining(playerUuid: UUID): Int {
        val limit = config.dailyLimit
        if (limit <= 0) return Int.MAX_VALUE
        return (limit - getDailyUsed(playerUuid)).coerceAtLeast(0)
    }

    private fun recordBattleUse(playerUuid: UUID): Int {
        synchronized(usageLock) {
            ensureCurrentDayLocked()
            val key = playerUuid.toString()
            val next = (usageStore.counts[key] ?: 0) + 1
            usageStore.counts[key] = next
            saveUsageLocked()
            return next
        }
    }

    fun resetPlayerUsage(playerUuid: UUID) {
        synchronized(usageLock) {
            ensureCurrentDayLocked()
            usageStore.counts.remove(playerUuid.toString())
            saveUsageLocked()
        }
    }

    fun setEnabled(value: Boolean) {
        config.enabled = value
        saveConfig()
    }

    fun setDailyLimit(limit: Int) {
        config.dailyLimit = limit.coerceIn(0, 100)
        saveConfig()
    }

    fun init() {
        loadConfig()
        loadUsage()

        CobblemonEvents.BATTLE_VICTORY.subscribe { event ->
            val battle = event.battle
            val training = activeTrainings.remove(battle.battleId) ?: return@subscribe
            val player = training.player
            val npc = training.npc

            val isWinner = event.winners.any { it.isForPlayer(player) }
            if (isWinner) {
                player.sendSystemMessage(
                    TextCatalog.component("training.victory", "trainer" to training.trainerName)
                )
                runCatching {
                    val level = player.serverLevel()
                    level.sendParticles(
                        ParticleTypes.POOF,
                        npc.x, npc.y + 1.0, npc.z,
                        30, 0.4, 0.5, 0.4, 0.05
                    )
                }
            } else {
                player.sendSystemMessage(
                    TextCatalog.component("training.defeat", "trainer" to training.trainerName)
                )
            }

            runCatching { npc.discard() }
        }

        CobblemonEvents.BATTLE_FLED.subscribe { event ->
            val battle = event.battle
            val training = activeTrainings.remove(battle.battleId) ?: return@subscribe
            training.player.sendSystemMessage(
                TextCatalog.component("training.fled")
            )
            runCatching { training.npc.discard() }
        }

        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val playerUuid = handler.player.uuid
            val toRemove = activeTrainings.filterValues { it.playerUuid == playerUuid }
            for ((battleId, training) in toRemove) {
                activeTrainings.remove(battleId)
                runCatching { training.npc.discard() }
            }
        }
    }

    fun startTraining(player: ServerPlayer): Pair<Boolean, String> {
        val isAdmin = AdminAuth.isAdmin(player)
        if (!config.enabled && !isAdmin) {
            return Pair(false, "§c[Entrenamiento] El comando /entrenar esta desactivado temporalmente por la administracion.")
        }

        val existingBattle = Cobblemon.battleRegistry.getBattleByParticipatingPlayer(player)
        if (existingBattle != null) {
            return Pair(false, "§cYa estas participando en un combate Pokemon.")
        }

        val limit = config.dailyLimit
        val usedToday = getDailyUsed(player.uuid)
        if (!isAdmin && limit > 0 && usedToday >= limit) {
            return Pair(
                false,
                "§c[Entrenamiento] Alcanzaste tu limite diario de §e$limit/$limit §ccombates. Se reinicia a las §e8:00 AM (Venezuela)§c."
            )
        }

        val now = System.currentTimeMillis()
        val cooldownMs = config.cooldownSeconds * 1000L
        val lastTime = playerCooldowns[player.uuid] ?: 0L
        if (!isAdmin && now - lastTime < cooldownMs) {
            val remainSec = ((cooldownMs - (now - lastTime)) / 1000L) + 1
            return Pair(false, "§cDebes esperar §e${remainSec}s §cantes de volver a usar /entrenar.")
        }

        val party = Cobblemon.storage.getParty(player)
        val aliveMons = party.filterNotNull().filter { it.currentHealth > 0 }
        if (aliveMons.isEmpty()) {
            return Pair(false, "§cNo tienes ningun Pokemon con vida en tu equipo para entrenar!")
        }

        val partyAvg = aliveMons.map { it.level }.average().roundToInt().coerceIn(2, 100)
        val minOffset = config.minLevelOffset.coerceIn(-10, -1)
        val maxOffset = config.maxLevelOffset.coerceIn(minOffset, -1)
        val displayLvl = (partyAvg + maxOffset).coerceIn(1, 100)

        val level = player.serverLevel()
        val look = player.lookAngle
        val spawnPos = player.position().add(look.x * 2.5, 0.0, look.z * 2.5)

        val npc = NPCEntity(level)
        val npcClass = NPCClasses.getByName("standard") ?: NPCClasses.classes.firstOrNull()
        if (npcClass != null) {
            npc.npc = npcClass
        }

        val preset = TRAINER_PRESETS.random()
        val tierSpecies = when {
            partyAvg < 20 -> preset.earlyTeam
            partyAvg <= 36 -> preset.midTeam
            else -> preset.lateTeam
        }
        val teamSize = aliveMons.size.coerceIn(2, 4)
        val selectedSpecies = tierSpecies.take(teamSize)

        npc.setPos(spawnPos.x, player.y, spawnPos.z)
        npc.yRot = player.yRot + 180f
        npc.yHeadRot = npc.yRot
        npc.yBodyRot = npc.yRot
        npc.skill = preset.skill
        npc.customName = Component.literal("§e${preset.name} §7[Nv. $displayLvl]")
        npc.isCustomNameVisible = true
        npc.isInvulnerable = true
        npc.setNoAi(true)
        npc.setNoGravity(true)
        level.addFreshEntity(npc)

        val npcParty = npc.party ?: com.cobblemon.mod.common.api.storage.party.NPCPartyStore(npc).also { npc.party = it }
        for (speciesName in selectedSpecies) {
            val offset = (minOffset..maxOffset).random()
            val monLvl = (partyAvg + offset).coerceIn(1, 100)
            val pokemon = runCatching {
                PokemonProperties.parse("$speciesName level=$monLvl").create()
            }.getOrNull()
            if (pokemon != null) {
                npcParty.add(pokemon)
            }
        }

        if (npcParty.isEmpty()) {
            npc.discard()
            return Pair(false, "§cError al generar el equipo del entrenador rival.")
        }

        val battleResult = BattleBuilder.pvn(player, npc)

        var started = false
        var newUsedCount = usedToday
        battleResult.ifSuccessful { battle ->
            started = true
            activeTrainings[battle.battleId] = ActiveTraining(player.uuid, player, npc, preset.name)
            playerCooldowns[player.uuid] = System.currentTimeMillis()
            if (!isAdmin) {
                newUsedCount = recordBattleUse(player.uuid)
            }
        }
        battleResult.ifErrored {
            npc.discard()
        }

        return if (started) {
            val quotaNote = when {
                isAdmin -> "§7[Admin: sin limite]"
                limit > 0 -> "§7[$newUsedCount/$limit hoy]"
                else -> "§7[Solo XP, 0 CD]"
            }
            Pair(
                true,
                "§aCombate de entrenamiento contra §e${preset.name} §7(Nv. ${(partyAvg + minOffset).coerceAtLeast(1)}-${displayLvl}, Solo XP / 0 CD) $quotaNote"
            )
        } else {
            Pair(false, "§cNo se pudo iniciar el combate en tu ubicacion actual. Asegurate de tener espacio despejado.")
        }
    }
}
