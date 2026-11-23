package com.desgraciamon.biomelocator

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

object BiomeLocatorPackets {
    val C2S_ACTION_ID = ResourceLocation.fromNamespaceAndPath("desgraciamon4_biome_locator", "c2s_action")
    val S2C_SYNC_ID = ResourceLocation.fromNamespaceAndPath("desgraciamon4_biome_locator", "s2c_sync")
    val S2C_NAV_ID = ResourceLocation.fromNamespaceAndPath("desgraciamon4_biome_locator", "s2c_nav")
    val S2C_SPAWNS_ID = ResourceLocation.fromNamespaceAndPath("desgraciamon4_biome_locator", "s2c_spawns")
    val S2C_POKEMON_BIOMES_ID = ResourceLocation.fromNamespaceAndPath("desgraciamon4_biome_locator", "s2c_pokemon_biomes")

    data class BiomeEntryData(
        val id: String,
        val name: String,
        val dimension: String,
        val category: String,
        val temperature: Float
    )

    data class C2SActionPayload(
        val action: String, // "OPEN", "UNLOCK", "TRACK", "STOP", "GET_SPAWNS", "SEARCH_POKEMON"
        val biomeId: String
    ) : CustomPacketPayload {
        companion object {
            val TYPE = CustomPacketPayload.Type<C2SActionPayload>(C2S_ACTION_ID)
            val CODEC: StreamCodec<FriendlyByteBuf, C2SActionPayload> = CustomPacketPayload.codec(
                { payload, buf ->
                    buf.writeUtf(payload.action)
                    buf.writeUtf(payload.biomeId)
                },
                { buf -> C2SActionPayload(buf.readUtf(), buf.readUtf()) }
            )
        }
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    }

    data class S2CSyncPayload(
        val discoveredCount: Int,
        val tokens: Int,
        val unlockedBiomes: List<String>,
        val visitedDimensions: List<String>,
        val biomes: List<BiomeEntryData>,
        val activeTrackedBiome: String?,
        val currentTierGoal: Int = 5,
        val tierProgress: Int = 0
    ) : CustomPacketPayload {
        companion object {
            val TYPE = CustomPacketPayload.Type<S2CSyncPayload>(S2C_SYNC_ID)
            val CODEC: StreamCodec<FriendlyByteBuf, S2CSyncPayload> = CustomPacketPayload.codec(
                { payload, buf ->
                    buf.writeInt(payload.discoveredCount)
                    buf.writeInt(payload.tokens)

                    buf.writeInt(payload.unlockedBiomes.size)
                    payload.unlockedBiomes.forEach { buf.writeUtf(it) }

                    buf.writeInt(payload.visitedDimensions.size)
                    payload.visitedDimensions.forEach { buf.writeUtf(it) }

                    buf.writeInt(payload.biomes.size)
                    payload.biomes.forEach { b ->
                        buf.writeUtf(b.id)
                        buf.writeUtf(b.name)
                        buf.writeUtf(b.dimension)
                        buf.writeUtf(b.category)
                        buf.writeFloat(b.temperature)
                    }

                    buf.writeBoolean(payload.activeTrackedBiome != null)
                    if (payload.activeTrackedBiome != null) {
                        buf.writeUtf(payload.activeTrackedBiome)
                    }
                    buf.writeInt(payload.currentTierGoal)
                    buf.writeInt(payload.tierProgress)
                },
                { buf ->
                    val discovered = buf.readInt()
                    val tokens = buf.readInt()

                    val unlockedSize = buf.readInt()
                    val unlocked = (0 until unlockedSize).map { buf.readUtf() }

                    val visitedSize = buf.readInt()
                    val visited = (0 until visitedSize).map { buf.readUtf() }

                    val biomesSize = buf.readInt()
                    val biomes = (0 until biomesSize).map {
                        BiomeEntryData(
                            id = buf.readUtf(),
                            name = buf.readUtf(),
                            dimension = buf.readUtf(),
                            category = buf.readUtf(),
                            temperature = buf.readFloat()
                        )
                    }

                    val hasActive = buf.readBoolean()
                    val activeTracked = if (hasActive) buf.readUtf() else null
                    val tierGoal = buf.readInt()
                    val tierProg = buf.readInt()

                    S2CSyncPayload(discovered, tokens, unlocked, visited, biomes, activeTracked, tierGoal, tierProg)
                }
            )
        }
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    }

    data class S2CBiomeSpawnsPayload(
        val biomeId: String,
        val spawns: List<CobblemonSpawnIntegration.BiomePokemonSpawnEntry>
    ) : CustomPacketPayload {
        companion object {
            val TYPE = CustomPacketPayload.Type<S2CBiomeSpawnsPayload>(S2C_SPAWNS_ID)
            val CODEC: StreamCodec<FriendlyByteBuf, S2CBiomeSpawnsPayload> = CustomPacketPayload.codec(
                { payload, buf ->
                    buf.writeUtf(payload.biomeId)
                    buf.writeInt(payload.spawns.size)
                    for (s in payload.spawns) {
                        buf.writeUtf(s.speciesId)
                        buf.writeUtf(s.displayName)
                        buf.writeUtf(s.bucket)
                        buf.writeFloat(s.percentage)
                        buf.writeShort(s.minLevel)
                        buf.writeShort(s.maxLevel)
                        buf.writeUtf(s.timeOfDay)
                        buf.writeUtf(s.context)
                        buf.writeUtf(s.lightReq)
                        buf.writeUtf(s.weatherReq)
                        buf.writeInt(s.aspects.size)
                        for (aspect in s.aspects) {
                            buf.writeUtf(aspect)
                        }
                    }
                },
                { buf ->
                    val biomeId = buf.readUtf()
                    val count = buf.readInt()
                    val spawns = (0 until count).map {
                        val spId = buf.readUtf()
                        val dName = buf.readUtf()
                        val bkt = buf.readUtf()
                        val pct = buf.readFloat()
                        val minL = buf.readShort().toInt()
                        val maxL = buf.readShort().toInt()
                        val tod = buf.readUtf()
                        val ctx = buf.readUtf()
                        val lReq = buf.readUtf()
                        val wReq = buf.readUtf()
                        val aspCount = buf.readInt()
                        val asps = (0 until aspCount).map { buf.readUtf() }
                        CobblemonSpawnIntegration.BiomePokemonSpawnEntry(
                            speciesId = spId,
                            displayName = dName,
                            bucket = bkt,
                            weight = 0f,
                            percentage = pct,
                            minLevel = minL,
                            maxLevel = maxL,
                            timeOfDay = tod,
                            context = ctx,
                            lightReq = lReq,
                            weatherReq = wReq,
                            aspects = asps
                        )
                    }
                    S2CBiomeSpawnsPayload(biomeId, spawns)
                }
            )
        }
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    }

    data class S2CPokemonBiomesPayload(
        val query: String,
        val pokemonName: String,
        val biomeIds: List<String>
    ) : CustomPacketPayload {
        companion object {
            val TYPE = CustomPacketPayload.Type<S2CPokemonBiomesPayload>(S2C_POKEMON_BIOMES_ID)
            val CODEC: StreamCodec<FriendlyByteBuf, S2CPokemonBiomesPayload> = CustomPacketPayload.codec(
                { payload, buf ->
                    buf.writeUtf(payload.query)
                    buf.writeUtf(payload.pokemonName)
                    buf.writeInt(payload.biomeIds.size)
                    payload.biomeIds.forEach { buf.writeUtf(it) }
                },
                { buf ->
                    val query = buf.readUtf()
                    val pName = buf.readUtf()
                    val count = buf.readInt()
                    val biomes = (0 until count).map { buf.readUtf() }
                    S2CPokemonBiomesPayload(query, pName, biomes)
                }
            )
        }
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    }

    data class S2CNavPayload(
        val biomeId: String,
        val biomeName: String,
        val targetX: Int,
        val targetZ: Int,
        val status: String // "SEARCHING", "FOUND", "NOT_FOUND", "STOPPED", "REACHED"
    ) : CustomPacketPayload {
        companion object {
            val TYPE = CustomPacketPayload.Type<S2CNavPayload>(S2C_NAV_ID)
            val CODEC: StreamCodec<FriendlyByteBuf, S2CNavPayload> = CustomPacketPayload.codec(
                { payload, buf ->
                    buf.writeUtf(payload.biomeId)
                    buf.writeUtf(payload.biomeName)
                    buf.writeInt(payload.targetX)
                    buf.writeInt(payload.targetZ)
                    buf.writeUtf(payload.status)
                },
                { buf ->
                    S2CNavPayload(
                        biomeId = buf.readUtf(),
                        biomeName = buf.readUtf(),
                        targetX = buf.readInt(),
                        targetZ = buf.readInt(),
                        status = buf.readUtf()
                    )
                }
            )
        }
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    }

    fun registerCommon() {
        PayloadTypeRegistry.playC2S().register(C2SActionPayload.TYPE, C2SActionPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(S2CSyncPayload.TYPE, S2CSyncPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(S2CBiomeSpawnsPayload.TYPE, S2CBiomeSpawnsPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(S2CPokemonBiomesPayload.TYPE, S2CPokemonBiomesPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(S2CNavPayload.TYPE, S2CNavPayload.CODEC)
    }
}
