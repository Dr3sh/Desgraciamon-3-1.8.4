package com.desgraciamon.showdownarena

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.storage.party.PartyPosition
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.storage.ClientParty
import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.client.Minecraft
import java.util.UUID
import kotlin.math.roundToInt
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer

/**
 * Sincroniza el equipo temporal de Showdown con el cliente para que los overlays
 * (como la barra lateral de Cobblemon Battle Extras) muestren el equipo real del combate.
 */
data class ShowdownBattlePartyPayload(
    val active: Boolean,
    val pokemonTags: List<CompoundTag>
) : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Type<ShowdownBattlePartyPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("showdown_arena", "battle_party_sync"))

        val CODEC: StreamCodec<FriendlyByteBuf, ShowdownBattlePartyPayload> = CustomPacketPayload.codec(
            { payload, buf ->
                buf.writeBoolean(payload.active)
                buf.writeVarInt(payload.pokemonTags.size)
                for (tag in payload.pokemonTags) {
                    buf.writeNbt(tag)
                }
            },
            { buf ->
                val active = buf.readBoolean()
                val size = buf.readVarInt().coerceIn(0, 12)
                val tags = ArrayList<CompoundTag>(size)
                for (i in 0 until size) {
                    tags.add(buf.readNbt() ?: CompoundTag())
                }
                ShowdownBattlePartyPayload(active, tags)
            }
        )
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID
}

object ShowdownBattlePartySync {
    fun register() {
        PayloadTypeRegistry.playS2C().register(ShowdownBattlePartyPayload.ID, ShowdownBattlePartyPayload.CODEC)
    }

    fun sendParty(player: ServerPlayer, battle: PokemonBattle) {
        val actor = battle.getActor(player) ?: battle.getActor(player.uuid) ?: return
        val registryAccess = player.registryAccess()
        val tags = actor.pokemonList.map { bp ->
            bp.effectedPokemon.saveToNBT(registryAccess, CompoundTag())
        }
        ServerPlayNetworking.send(player, ShowdownBattlePartyPayload(true, tags))
    }

    fun clearParty(player: ServerPlayer) {
        ServerPlayNetworking.send(player, ShowdownBattlePartyPayload(false, emptyList()))
    }
}

object ShowdownBattlePartyClient {
    @JvmField
    @Volatile
    var activeShowdownParty: List<Pokemon> = emptyList()

    @Volatile
    private var cachedOverlayParty: ClientParty? = null

    @Volatile
    private var seenBattleActive: Boolean = false

    @Volatile
    private var waitingTicks: Int = 0

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(ShowdownBattlePartyPayload.ID) { payload, context ->
            context.client().execute {
                if (!payload.active) {
                    reset()
                    return@execute
                }
                val client = context.client()
                val registryAccess = client.level?.registryAccess() ?: client.connection?.registryAccess() ?: return@execute
                val loaded = payload.pokemonTags.mapNotNull { tag ->
                    runCatching { Pokemon().loadFromNBT(registryAccess, tag) }.getOrNull()
                }
                activeShowdownParty = loaded
                seenBattleActive = CobblemonClient.battle != null
                waitingTicks = 0
                rebuildOverlayParty(loaded)
                clearBattleExtrasCache()
            }
        }
    }

    private fun rebuildOverlayParty(partyList: List<Pokemon>) {
        if (partyList.isEmpty()) {
            cachedOverlayParty = null
            return
        }
        val overlay = ClientParty(UUID(0L, 1L), maxOf(6, partyList.size))
        for (i in partyList.indices) {
            overlay.set(PartyPosition(i), partyList[i])
        }
        cachedOverlayParty = overlay
    }

    @JvmStatic
    fun getOverlayPartyOrDefault(defaultParty: ClientParty): ClientParty {
        val battle = CobblemonClient.battle ?: return defaultParty
        if (activeShowdownParty.isEmpty()) return defaultParty
        val overlay = cachedOverlayParty ?: return defaultParty

        val playerUuid = Minecraft.getInstance().player?.uuid
        val myActor = if (playerUuid != null) {
            battle.sides.asSequence().flatMap { it.actors.asSequence() }.firstOrNull { it.uuid == playerUuid }
        } else null

        if (myActor != null) {
            for (actorMon in myActor.pokemon) {
                val match = overlay.findByUUID(actorMon.uuid)
                if (match != null) {
                    match.currentHealth = actorMon.currentHealth
                }
            }
            for (activeMon in myActor.side.activeClientBattlePokemon) {
                val bp = activeMon.battlePokemon ?: continue
                val match = overlay.findByUUID(bp.uuid) ?: continue
                val hp = if (bp.isHpFlat) {
                    bp.hpValue.roundToInt()
                } else {
                    (bp.hpValue * match.maxHealth).roundToInt()
                }
                match.currentHealth = hp.coerceIn(0, match.maxHealth)
            }
        }
        return overlay
    }

    fun tick(client: Minecraft) {
        if (activeShowdownParty.isEmpty()) return
        if (CobblemonClient.battle != null) {
            seenBattleActive = true
            waitingTicks = 0
        } else if (seenBattleActive) {
            reset()
        } else {
            waitingTicks++
            if (waitingTicks > 100) {
                reset()
            }
        }
    }

    fun reset() {
        seenBattleActive = false
        waitingTicks = 0
        cachedOverlayParty = null
        if (activeShowdownParty.isNotEmpty()) {
            activeShowdownParty = emptyList()
            clearBattleExtrasCache()
        }
    }

    private fun clearBattleExtrasCache() {
        runCatching {
            val cls = Class.forName("name.modid.client.LocalBattlePartyTracker")
            cls.getMethod("clear").invoke(null)
        }
    }
}
