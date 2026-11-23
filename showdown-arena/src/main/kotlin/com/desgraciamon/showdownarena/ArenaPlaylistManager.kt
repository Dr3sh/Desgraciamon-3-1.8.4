package com.desgraciamon.showdownarena

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

data class ArenaPlaylistTrack(
    val trackId: String,
    val title: String,
    val durationMs: Long
)

data class ArenaPlaylistSyncPayload(
    val serverEpochMs: Long,
    val tracks: List<ArenaPlaylistTrack>
) : CustomPacketPayload {
    companion object {
        val ID: CustomPacketPayload.Type<ArenaPlaylistSyncPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("showdown_arena", "playlist_sync"))

        val CODEC: StreamCodec<FriendlyByteBuf, ArenaPlaylistSyncPayload> = CustomPacketPayload.codec(
            { payload, buf ->
                buf.writeLong(payload.serverEpochMs)
                buf.writeVarInt(payload.tracks.size)
                for (t in payload.tracks) {
                    buf.writeUtf(t.trackId, 32)
                    buf.writeUtf(t.title, 128)
                    buf.writeVarLong(t.durationMs)
                }
            },
            { buf ->
                val epoch = buf.readLong()
                val count = buf.readVarInt().coerceIn(0, 64)
                val list = ArrayList<ArenaPlaylistTrack>(count)
                for (i in 0 until count) {
                    list.add(
                        ArenaPlaylistTrack(
                            buf.readUtf(32),
                            buf.readUtf(128),
                            buf.readVarLong().coerceAtLeast(10_000L)
                        )
                    )
                }
                ArenaPlaylistSyncPayload(epoch, list)
            }
        )
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID
}

object ArenaPlaylistManager {
    private val BASE_TRACKS = listOf(
        ArenaPlaylistTrack("track_01", "Pokémon Black & White - N Final Battle", 179_409L),
        ArenaPlaylistTrack("track_02", "Pokémon Sun & Moon - Solgaleo & Lunala Battle", 300_791L),
        ArenaPlaylistTrack("track_03", "Pokémon Omega Ruby & Alpha Sapphire - Gym Leader Battle", 300_071L),
        ArenaPlaylistTrack("track_04", "Pokémon HeartGold & SoulSilver - Champion & Red Battle", 179_154L),
        ArenaPlaylistTrack("track_05", "Pokémon Origins - Mewtwo Battle", 280_056L),
        ArenaPlaylistTrack("track_06", "Pokémon Let's Go Pikachu & Eevee - Champion Battle", 179_897L),
        ArenaPlaylistTrack("track_07", "Pokémon Sun & Moon - Team Skull Leader Guzma Battle", 300_791L),
        ArenaPlaylistTrack("track_08", "Pokémon Omega Ruby & Alpha Sapphire - Primal Kyogre & Groudon Battle", 300_071L),
        ArenaPlaylistTrack("track_09", "Pokémon X & Y - World Championship Final Battle", 300_071L),
        ArenaPlaylistTrack("track_10", "Pokémon HeartGold & SoulSilver - Frontier Brain Battle", 179_363L),
        ArenaPlaylistTrack("track_11", "Pokémon Omega Ruby & Alpha Sapphire - Wally Battle", 300_071L),
        ArenaPlaylistTrack("track_12", "Pokémon Sun & Moon - Aether President Lusamine Battle", 300_791L),
        ArenaPlaylistTrack("track_13", "Pokémon Black & White - Ghetsis Battle", 179_363L),
        ArenaPlaylistTrack("track_14", "Pokémon Emerald, FireRed & LeafGreen - Deoxys Battle", 179_061L),
        ArenaPlaylistTrack("track_15", "Pokémon Omega Ruby & Alpha Sapphire - Team Aqua & Magma Leader Battle", 300_071L),
        ArenaPlaylistTrack("track_16", "Pokémon Diamond, Pearl & Platinum - Champion Cynthia Battle", 179_363L),
        ArenaPlaylistTrack("track_17", "Pokémon Omega Ruby & Alpha Sapphire - Reshiram & Zekrom Battle", 180_094L),
        ArenaPlaylistTrack("track_18", "Pokémon Sun & Moon - Frontier Brain Anabel Battle", 300_791L),
        ArenaPlaylistTrack("track_19", "Pokémon Black 2 & White 2 - Rival Battle", 170_063L),
        ArenaPlaylistTrack("track_20", "Pokémon Omega Ruby & Alpha Sapphire - Ho-Oh Battle", 180_094L),
        ArenaPlaylistTrack("track_21", "Pokémon Black & White - Low Health (Remix)", 124_204L),
        ArenaPlaylistTrack("track_22", "Pokémon Brilliant Diamond & Shining Pearl - Champion Cynthia (Remix)", 167_242L),
        ArenaPlaylistTrack("track_23", "Pokémon HeartGold & SoulSilver - Champion Lance (Remix)", 169_471L),
        ArenaPlaylistTrack("track_24", "Pokémon Mystery Dungeon - Rayquaza Defy The Legends (Remix)", 248_221L),
        ArenaPlaylistTrack("track_25", "Pokémon Omega Ruby & Alpha Sapphire - Lorekeeper Zinnia (GBA Remix)", 247_083L),
        ArenaPlaylistTrack("track_26", "Pokémon Ultra Sun & Ultra Moon - Ultra Necrozma (GaMetal Remix)", 226_517L),
        ArenaPlaylistTrack("track_27", "Pokémon Ruby, Sapphire & Emerald - Frontier Brain (Remaster)", 199_168L),
        ArenaPlaylistTrack("track_28", "Pokémon Omega Ruby & Alpha Sapphire - Rival Wally (Remix)", 213_120L),
        ArenaPlaylistTrack("track_29", "Pokémon Sword & Shield - Gym Leader Battle (Remix)", 263_851L),
        ArenaPlaylistTrack("track_30", "Pokémon FireRed & LeafGreen - Deoxys (GaMetal Remix)", 324_757L),
        ArenaPlaylistTrack("track_31", "Pokémon Platinum - Giratina (Remix)", 223_531L)
    )

    @Volatile
    var currentTracks: List<ArenaPlaylistTrack> = BASE_TRACKS.shuffled()
        private set

    fun init() {
        PayloadTypeRegistry.playS2C().register(ArenaPlaylistSyncPayload.ID, ArenaPlaylistSyncPayload.CODEC)
    }

    fun refreshAsync(server: MinecraftServer) {
        // Reshuffle once per server start so all players share the same synchronized shuffle order
        currentTracks = BASE_TRACKS.shuffled()
        server.execute { syncToAll(server) }
    }

    fun syncToPlayer(player: ServerPlayer) {
        if (!ServerPlayNetworking.canSend(player, ArenaPlaylistSyncPayload.ID)) return
        ServerPlayNetworking.send(player, ArenaPlaylistSyncPayload(System.currentTimeMillis(), currentTracks))
    }

    fun syncToAll(server: MinecraftServer) {
        val payload = ArenaPlaylistSyncPayload(System.currentTimeMillis(), currentTracks)
        for (p in server.playerList.players) {
            if (ServerPlayNetworking.canSend(p, ArenaPlaylistSyncPayload.ID)) {
                ServerPlayNetworking.send(p, payload)
            }
        }
    }
}
