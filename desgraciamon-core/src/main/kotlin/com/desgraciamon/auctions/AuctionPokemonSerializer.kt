package com.desgraciamon.auctions

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.CobblemonEntities
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import com.google.gson.JsonParser
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

object AuctionPokemonSerializer {

    fun extractDetails(pokemon: Pokemon): AuctionPokemonDetails {
        val ivMap = mapOf(
            "HP" to pokemon.ivs.getOrDefault(Stats.HP),
            "ATK" to pokemon.ivs.getOrDefault(Stats.ATTACK),
            "DEF" to pokemon.ivs.getOrDefault(Stats.DEFENCE),
            "SPA" to pokemon.ivs.getOrDefault(Stats.SPECIAL_ATTACK),
            "SPD" to pokemon.ivs.getOrDefault(Stats.SPECIAL_DEFENCE),
            "SPE" to pokemon.ivs.getOrDefault(Stats.SPEED)
        )

        val evMap = mapOf(
            "HP" to pokemon.evs.getOrDefault(Stats.HP),
            "ATK" to pokemon.evs.getOrDefault(Stats.ATTACK),
            "DEF" to pokemon.evs.getOrDefault(Stats.DEFENCE),
            "SPA" to pokemon.evs.getOrDefault(Stats.SPECIAL_ATTACK),
            "SPD" to pokemon.evs.getOrDefault(Stats.SPECIAL_DEFENCE),
            "SPE" to pokemon.evs.getOrDefault(Stats.SPEED)
        )

        val typeList = mutableListOf<String>()
        typeList.add(pokemon.primaryType.name.uppercase())
        pokemon.secondaryType?.let { typeList.add(it.name.uppercase()) }

        val moves = pokemon.moveSet.map { it.displayName.string }

        return AuctionPokemonDetails(
            species = pokemon.species.name,
            displayName = pokemon.species.translatedName.string,
            level = pokemon.level,
            gender = pokemon.gender.name,
            types = typeList,
            shiny = pokemon.shiny,
            nature = pokemon.nature.name.path.replaceFirstChar { it.uppercase() },
            ability = pokemon.ability.name,
            ivs = ivMap,
            evs = evMap,
            moves = moves
        )
    }

    fun deserialize(jsonStr: String, registryAccess: net.minecraft.core.RegistryAccess): Pokemon? {
        if (jsonStr.isBlank()) return null
        return runCatching {
            val json = JsonParser.parseString(jsonStr).asJsonObject
            Pokemon().loadFromJSON(registryAccess, json)
        }.getOrNull()
    }

    fun createDisplayPokemon(level: ServerLevel, pos: Vec3, pokemon: Pokemon): Entity? {
        return runCatching {
            // Escala compacta en la instancia Pokemon (utilizada directamente por Cobblemon PokemonRenderer)
            pokemon.scaleModifier = 0.35f

            val entity = PokemonEntity(level, pokemon, CobblemonEntities.POKEMON)
            entity.setPos(pos.x, pos.y, pos.z)
            entity.setNoAi(true)
            entity.isInvulnerable = true
            entity.isSilent = true
            entity.setNoGravity(true)
            entity.noPhysics = true
            entity.deltaMovement = Vec3.ZERO
            entity.setBoundingBox(net.minecraft.world.phys.AABB.ofSize(pos, 0.01, 0.01, 0.01))
            runCatching { entity.busyLocks.add("auction") }

            // Escala compacta para exhibidor en tracked data y vanilla attributes
            runCatching {
                entity.entityData.set(PokemonEntity.SCALE_MODIFIER, 0.35f)
            }
            runCatching {
                entity.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE)?.baseValue = 0.35
            }
            entity
        }.getOrNull()
    }

    fun givePokemonToPlayer(player: ServerPlayer, pokemon: Pokemon) {
        runCatching {
            val party = Cobblemon.storage.getParty(player)
            if (!party.add(pokemon)) {
                val pc = Cobblemon.storage.getPC(player)
                val addedToPC = pc.add(pokemon)
                if (addedToPC) {
                    player.sendSystemMessage(Component.literal("§a[Subasta] Tu equipo estaba lleno, el Pokémon ha sido enviado a tu PC."))
                } else {
                    player.sendSystemMessage(Component.literal("§c[Subasta] ¡Tu equipo y tu PC están llenos! El Pokémon ha aparecido a tu lado para no perderse."))
                    pokemon.sendOut(player.serverLevel(), player.position(), null)
                }
            } else {
                player.sendSystemMessage(Component.literal("§a[Subasta] ¡El Pokémon subastado se ha añadido a tu equipo Pokémon!"))
            }
        }.onFailure {
            player.sendSystemMessage(Component.literal("§c[Subasta] Ocurrió un error al entregar el Pokémon."))
        }
    }
}
