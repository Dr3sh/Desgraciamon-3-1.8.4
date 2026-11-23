package com.desgraciamon.auctions

import com.google.gson.GsonBuilder
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.io.File
import java.util.UUID

enum class AuctionType {
    ITEM,
    POKEMON
}

enum class BidType {
    PLAYER,
    GUILD
}

data class AuctionPokemonDetails(
    val species: String = "",
    val displayName: String = "",
    val level: Int = 1,
    val gender: String = "GENDERLESS",
    val types: List<String> = emptyList(),
    val shiny: Boolean = false,
    val nature: String = "Hardy",
    val ability: String = "",
    val ivs: Map<String, Int> = emptyMap(),
    val evs: Map<String, Int> = emptyMap(),
    val moves: List<String> = emptyList()
)

data class AuctionStandPos(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val dimension: String = "minecraft:overworld"
) {
    fun toBlockPos(): BlockPos = BlockPos(x.toInt(), y.toInt(), z.toInt())
}

data class AuctionState(
    var id: String = UUID.randomUUID().toString(),
    var type: AuctionType = AuctionType.ITEM,
    var title: String = "",
    var itemTagJson: String = "",
    var pokemonJson: String = "",
    var pokemonDetails: AuctionPokemonDetails? = null,
    var currentBid: Long = 0,
    var minIncrement: Long = 1,
    var startingBid: Long = 1,
    var highestBidderUuid: UUID? = null,
    var highestBidderName: String = "Nadie",
    var highestBidderType: BidType = BidType.PLAYER,
    var highestBidderGuildTag: String = "",
    var highestBidderGuildName: String = "",
    var sellerUuid: UUID? = null,
    var sellerName: String = "Servidor",
    var timeRemainingSeconds: Int = 120,
    var totalDurationSeconds: Int = 120,
    var active: Boolean = false
) {
    val minNextBid: Long
        get() = if (currentBid <= 0) startingBid else currentBid + minIncrement

    val bidderDisplay: String
        get() = if (highestBidderType == BidType.GUILD && highestBidderGuildTag.isNotBlank()) {
            "[Gremio] $highestBidderGuildName"
        } else {
            highestBidderName
        }
}

data class AuctionPendingClaim(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: AuctionType,
    val itemTagJson: String = "",
    val pokemonJson: String = "",
    val reason: String = "SUBASTA"
)

data class AuctionConfig(
    var enabled: Boolean = true,
    var standPos: AuctionStandPos? = null,
    var activeAuction: AuctionState? = null,
    val pendingClaims: MutableMap<String, MutableList<AuctionPendingClaim>> = mutableMapOf()
)

object AuctionManager {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var configFile: File = File("config/desgraciamon4_auctions/auction.json")

    var config = AuctionConfig()
        private set

    private var tickCounter = 0
    private var hologramEntityId: Int? = null
    private var displayItemOrMonId: Int? = null

    fun init(server: MinecraftServer) {
        val worldFolder = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
        val dir = worldFolder.resolve("data/desgraciamon4_auctions").toFile()
        dir.mkdirs()
        val serverFile = File(dir, "auction.json")
        val legacy = File("config/desgraciamon4_auctions/auction.json")
        if (!serverFile.exists() && legacy.exists()) {
            runCatching { legacy.copyTo(serverFile, overwrite = false) }
        }
        configFile = serverFile
        load()
        updateStandEntities(server)
    }

    fun load() {
        runCatching {
            if (configFile.exists()) {
                configFile.reader(Charsets.UTF_8).use {
                    config = gson.fromJson(it, AuctionConfig::class.java) ?: AuctionConfig()
                }
            } else {
                config = AuctionConfig()
                save()
            }
        }.onFailure {
            config = AuctionConfig()
        }
    }

    fun save() {
        runCatching {
            configFile.parentFile?.mkdirs()
            configFile.writer(Charsets.UTF_8).use {
                gson.toJson(config, it)
            }
        }
    }

    fun setStand(pos: Vec3, dimension: String, server: MinecraftServer) {
        config.standPos = AuctionStandPos(pos.x, pos.y, pos.z, dimension)
        save()
        updateStandEntities(server)
        syncToAll(server)
    }

    fun tick(server: MinecraftServer) {
        val auction = config.activeAuction ?: return
        if (!auction.active) return

        // Rotación continua del modelo 3D / ítem en el pedestal
        val stand = config.standPos
        if (stand != null && displayItemOrMonId != null) {
            val level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.parse(stand.dimension)
            ))
            val entity = level?.getEntity(displayItemOrMonId!!)
            if (entity != null) {
                val newRot = (entity.yRot + 2.5f) % 360f
                entity.setYRot(newRot)
                if (entity is net.minecraft.world.entity.LivingEntity) {
                    entity.yHeadRot = newRot
                    entity.yBodyRot = newRot
                }
            }
        }

        tickCounter++
        if (tickCounter % 20 == 0) { // Cada 1 segundo
            if (auction.timeRemainingSeconds > 0) {
                auction.timeRemainingSeconds--
                updateHologramText(server)
                if (auction.timeRemainingSeconds % 10 == 0 || auction.timeRemainingSeconds <= 5) {
                    syncToAll(server)
                }
            } else {
                resolveAuction(server)
            }
        }
    }

    fun createItemAuction(
        seller: ServerPlayer,
        stack: ItemStack,
        startingBid: Long,
        minIncrement: Long,
        durationSeconds: Int,
        server: MinecraftServer
    ): Boolean {
        if (config.activeAuction?.active == true) return false
        val title = stack.hoverName.string

        val state = AuctionState(
            type = AuctionType.ITEM,
            title = title,
            itemTagJson = com.desgraciamon.auctions.AuctionItemSerializer.serialize(stack),
            currentBid = 0,
            startingBid = startingBid.coerceAtLeast(1),
            minIncrement = minIncrement.coerceAtLeast(1),
            sellerUuid = seller.uuid,
            sellerName = seller.scoreboardName,
            timeRemainingSeconds = durationSeconds.coerceIn(30, 3600),
            totalDurationSeconds = durationSeconds.coerceIn(30, 3600),
            active = true
        )
        config.activeAuction = state
        save()
        updateStandEntities(server)
        syncToAll(server)

        broadcast(server, "§6§l[SUBASTA] §e¡Ha comenzado la subasta de §f$title§e!")
        broadcast(server, "§7Puja inicial: §a$startingBid$ §7| Incremento mín: §a$minIncrement$ §7| Duración: §c${durationSeconds}s")
        broadcast(server, "§7Usa §b/bid as player <monto> §7o §b/bid as guild <monto> §7para participar.")
        return true
    }

    fun createPokemonAuction(
        seller: ServerPlayer,
        pokemon: com.cobblemon.mod.common.pokemon.Pokemon,
        startingBid: Long,
        minIncrement: Long,
        durationSeconds: Int,
        server: MinecraftServer
    ): Boolean {
        if (config.activeAuction?.active == true) return false
        val details = AuctionPokemonSerializer.extractDetails(pokemon)
        val title = if (details.shiny) "${details.displayName} (Shiny)" else details.displayName

        val state = AuctionState(
            type = AuctionType.POKEMON,
            title = title,
            pokemonJson = pokemon.saveToJSON(server.registryAccess(), com.google.gson.JsonObject()).toString(),
            pokemonDetails = details,
            currentBid = 0,
            startingBid = startingBid.coerceAtLeast(1),
            minIncrement = minIncrement.coerceAtLeast(1),
            sellerUuid = seller.uuid,
            sellerName = seller.scoreboardName,
            timeRemainingSeconds = durationSeconds.coerceIn(30, 3600),
            totalDurationSeconds = durationSeconds.coerceIn(30, 3600),
            active = true
        )
        config.activeAuction = state
        save()
        updateStandEntities(server)
        syncToAll(server)

        broadcast(server, "§6§l[SUBASTA POKÉMON] §e¡Ha comenzado la subasta de §b$title §e(Nv. ${details.level})!")
        broadcast(server, "§7Puja inicial: §a$startingBid$ §7| Incremento mín: §a$minIncrement$ §7| Duración: §c${durationSeconds}s")
        broadcast(server, "§7¡Acércate al pedestal de subastas y mira al Pokémon para ver sus IVs, naturaleza y movimientos!")
        broadcast(server, "§7Usa §b/bid as player <monto> §7o §b/bid as guild <monto> §7para participar.")
        return true
    }

    @Synchronized
    fun bidAsPlayer(player: ServerPlayer, amount: Long): Pair<Boolean, String> {
        if (!player.isAlive || player.isSpectator) return Pair(false, "No puedes pujar en este estado.")
        if (amount <= 0L || amount > 1_000_000_000L) return Pair(false, "Monto de puja inválido.")
        val auction = config.activeAuction ?: return Pair(false, "No hay ninguna subasta activa en este momento.")
        if (!auction.active) return Pair(false, "La subasta ya ha finalizado.")
        if (amount < auction.minNextBid) {
            return Pair(false, "La puja mínima requerida es de §e${auction.minNextBid}$§c.")
        }

        val balanceBig = com.desgraciamon.economy.CobbleDollarsBridge.getBalance(player)
        val amountBig = java.math.BigInteger.valueOf(amount)
        if (balanceBig < amountBig) {
            return Pair(false, "No tienes suficientes fondos. Tienes §e$balanceBig$§c y requieres §e$amount$§c.")
        }

        // Retirar dinero al nuevo pujador
        val success = com.desgraciamon.economy.CobbleDollarsBridge.withdraw(player, amount)
        if (!success) return Pair(false, "Error al procesar la transacción económica.")

        // Reembolsar al pujador anterior
        refundPreviousBidder(player.server, auction)

        // Registrar nueva puja
        auction.currentBid = amount
        auction.highestBidderUuid = player.uuid
        auction.highestBidderName = player.scoreboardName
        auction.highestBidderType = BidType.PLAYER
        auction.highestBidderGuildTag = ""
        auction.highestBidderGuildName = ""

        // Anti-snipe: si quedan menos de 25 segundos, añadir 20 segundos
        if (auction.timeRemainingSeconds < 25) {
            auction.timeRemainingSeconds += 20
        }

        save()
        updateHologramText(player.server)
        syncToAll(player.server)

        broadcast(player.server, "§6[Subasta] §e${player.scoreboardName} §fha pujado §a$amount$ §fpor §b${auction.title}§f!")
        return Pair(true, "§a¡Has realizado una puja exitosa de $amount$ como jugador!")
    }

    @Synchronized
    fun bidAsGuild(player: ServerPlayer, amount: Long): Pair<Boolean, String> {
        if (!player.isAlive || player.isSpectator) return Pair(false, "No puedes pujar en este estado.")
        if (amount <= 0L || amount > 1_000_000_000L) return Pair(false, "Monto de puja inválido.")
        val auction = config.activeAuction ?: return Pair(false, "No hay ninguna subasta activa en este momento.")
        if (!auction.active) return Pair(false, "La subasta ya ha finalizado.")
        if (amount < auction.minNextBid) {
            return Pair(false, "La puja mínima requerida es de §e${auction.minNextBid}$§c.")
        }

        val guild = GuildBridge.getPlayerGuild(player.uuid, player.server)
            ?: return Pair(false, "No perteneces a ningún gremio para pujar en nombre de un gremio.")

        if (guild.bankBalance < amount) {
            return Pair(false, "El banco del gremio no tiene fondos suficientes. Fondos: §e${guild.bankBalance}$§c.")
        }

        // Retirar del banco del gremio
        val success = GuildBridge.withdrawGuildMoney(guild.tag, amount, player.server)
        if (!success) return Pair(false, "Error al retirar fondos del banco del gremio.")

        // Reembolsar al pujador anterior
        refundPreviousBidder(player.server, auction)

        // Registrar nueva puja
        auction.currentBid = amount
        auction.highestBidderUuid = player.uuid
        auction.highestBidderName = "[Gremio] ${guild.name}"
        auction.highestBidderType = BidType.GUILD
        auction.highestBidderGuildTag = guild.tag
        auction.highestBidderGuildName = guild.name

        if (auction.timeRemainingSeconds < 25) {
            auction.timeRemainingSeconds += 20
        }

        save()
        updateHologramText(player.server)
        syncToAll(player.server)

        broadcast(player.server, "§6[Subasta] §eEl Gremio ${guild.name} §7[${guild.tag}] §f(por §b${player.scoreboardName}§f) ha pujado §a$amount$ §fpor §b${auction.title}§f!")
        return Pair(true, "§a¡Tu gremio [${guild.tag}] ha realizado una puja de $amount$!")
    }

    private fun refundPreviousBidder(server: MinecraftServer, auction: AuctionState) {
        val prevUuid = auction.highestBidderUuid ?: return
        val prevAmount = auction.currentBid
        if (prevAmount <= 0) return

        if (auction.highestBidderType == BidType.PLAYER) {
            val prevPlayer = server.playerList.getPlayer(prevUuid)
            if (prevPlayer != null) {
                com.desgraciamon.economy.CobbleDollarsBridge.deposit(prevPlayer, prevAmount)
                prevPlayer.sendSystemMessage(Component.literal("§c¡Has sido sobrepujado en la subasta de ${auction.title}! Se te han reembolsado §e$prevAmount$§c."))
            } else {
                // Reembolso a jugador offline
                runCatching {
                    val apiClass = Class.forName("fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt")
                    val method = apiClass.getMethod("addOfflineCobbleDollars", UUID::class.java, MinecraftServer::class.java, java.math.BigInteger::class.java)
                    method.invoke(null, prevUuid, server, java.math.BigInteger.valueOf(prevAmount))
                }
            }
        } else if (auction.highestBidderType == BidType.GUILD) {
            GuildBridge.depositGuildMoney(auction.highestBidderGuildTag, prevAmount, server)
            val prevPlayer = server.playerList.getPlayer(prevUuid)
            prevPlayer?.sendSystemMessage(Component.literal("§cTu gremio [${auction.highestBidderGuildTag}] ha sido sobrepujado. Se reembolsaron §e$prevAmount$ §cal banco del gremio."))
        }
    }

    fun resolveAuction(server: MinecraftServer) {
        val auction = config.activeAuction ?: return
        auction.active = false

        if (auction.highestBidderUuid == null || auction.currentBid <= 0) {
            // Subasta expiró sin pujas -> Devolver objeto al vendedor
            broadcast(server, "§6[Subasta] §fLa subasta de §b${auction.title} §fha concluido §csin ofertas§f.")
            returnItemOrMonToSeller(server, auction)
        } else {
            // Entregar el premio al ganador
            val winnerName = auction.bidderDisplay
            val amount = auction.currentBid
            broadcast(server, "§6§l[SUBASTA FINALIZADA] §e¡$winnerName §fha ganado la subasta de §b${auction.title} §fpor §a$amount$§f!")

            awardPrize(server, auction)

            // Pagar al vendedor
            if (auction.sellerUuid != null) {
                val sellerPlayer = server.playerList.getPlayer(auction.sellerUuid!!)
                if (sellerPlayer != null) {
                    com.desgraciamon.economy.CobbleDollarsBridge.deposit(sellerPlayer, amount)
                    sellerPlayer.sendSystemMessage(Component.literal("§a¡Tu subasta ha finalizado con éxito! Has recibido §e$amount$§a."))
                } else {
                    runCatching {
                        val apiClass = Class.forName("fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt")
                        val method = apiClass.getMethod("addOfflineCobbleDollars", UUID::class.java, MinecraftServer::class.java, java.math.BigInteger::class.java)
                        method.invoke(null, auction.sellerUuid, server, java.math.BigInteger.valueOf(amount))
                    }
                }
            }
        }

        clearStandEntities(server)
        config.activeAuction = null
        save()
        syncToAll(server)
    }

    fun cancelAuction(server: MinecraftServer, reason: String = "Cancelada por un administrador") {
        val auction = config.activeAuction ?: return
        auction.active = false
        refundPreviousBidder(server, auction)
        returnItemOrMonToSeller(server, auction)
        clearStandEntities(server)
        config.activeAuction = null
        save()
        syncToAll(server)
        broadcast(server, "§c[Subasta] La subasta de ${auction.title} ha sido cancelada: $reason.")
    }

    fun claimPendingRewards(player: ServerPlayer) {
        val uuidStr = player.uuid.toString()
        val claims = config.pendingClaims.remove(uuidStr) ?: return
        if (claims.isEmpty()) return
        save()

        var deliveredCount = 0
        claims.forEach { claim ->
            if (claim.type == AuctionType.ITEM && claim.itemTagJson.isNotBlank()) {
                val item = com.desgraciamon.auctions.AuctionItemSerializer.deserialize(claim.itemTagJson)
                if (!player.inventory.add(item)) player.drop(item, false)
                deliveredCount++
            } else if (claim.type == AuctionType.POKEMON && claim.pokemonJson.isNotBlank()) {
                val pokemon = AuctionPokemonSerializer.deserialize(claim.pokemonJson, player.server.registryAccess())
                if (pokemon != null) {
                    AuctionPokemonSerializer.givePokemonToPlayer(player, pokemon)
                    deliveredCount++
                }
            }
        }
        if (deliveredCount > 0) {
            player.sendSystemMessage(Component.literal("§a[Subastas] ¡Se te han entregado $deliveredCount objetos/Pokémon pendientes de subastas mientras estabas desconectado!"))
        }
    }

    private fun awardPrize(server: MinecraftServer, auction: AuctionState) {
        val winnerUuid = auction.highestBidderUuid ?: return
        val winnerPlayer = server.playerList.getPlayer(winnerUuid)

        if (auction.type == AuctionType.ITEM) {
            val item = com.desgraciamon.auctions.AuctionItemSerializer.deserialize(auction.itemTagJson)
            if (auction.highestBidderType == BidType.GUILD && auction.highestBidderGuildTag.isNotBlank()) {
                val deposited = GuildBridge.depositItemToGuildVault(auction.highestBidderGuildTag, item, server)
                if (!deposited) {
                    if (winnerPlayer != null) {
                        winnerPlayer.inventory.add(item)
                    } else {
                        config.pendingClaims.computeIfAbsent(winnerUuid.toString()) { mutableListOf() }.add(
                            AuctionPendingClaim(type = auction.type, itemTagJson = auction.itemTagJson, reason = "PREMIO_SUBASTA")
                        )
                        save()
                    }
                }
            } else if (winnerPlayer != null) {
                if (!winnerPlayer.inventory.add(item)) {
                    winnerPlayer.drop(item, false)
                }
            } else {
                config.pendingClaims.computeIfAbsent(winnerUuid.toString()) { mutableListOf() }.add(
                    AuctionPendingClaim(type = auction.type, itemTagJson = auction.itemTagJson, reason = "PREMIO_SUBASTA")
                )
                save()
            }
        } else if (auction.type == AuctionType.POKEMON) {
            val pokemon = AuctionPokemonSerializer.deserialize(auction.pokemonJson, server.registryAccess())
            if (pokemon != null) {
                if (auction.highestBidderType == BidType.GUILD && auction.highestBidderGuildTag.isNotBlank()) {
                    val pcOk = GuildBridge.depositPokemonToGuildPC(auction.highestBidderGuildTag, pokemon, server)
                    if (!pcOk) {
                        if (winnerPlayer != null) {
                            AuctionPokemonSerializer.givePokemonToPlayer(winnerPlayer, pokemon)
                        } else {
                            config.pendingClaims.computeIfAbsent(winnerUuid.toString()) { mutableListOf() }.add(
                                AuctionPendingClaim(type = auction.type, pokemonJson = auction.pokemonJson, reason = "PREMIO_SUBASTA")
                            )
                            save()
                        }
                    }
                } else if (winnerPlayer != null) {
                    AuctionPokemonSerializer.givePokemonToPlayer(winnerPlayer, pokemon)
                } else {
                    config.pendingClaims.computeIfAbsent(winnerUuid.toString()) { mutableListOf() }.add(
                        AuctionPendingClaim(type = auction.type, pokemonJson = auction.pokemonJson, reason = "PREMIO_SUBASTA")
                    )
                    save()
                }
            }
        }
    }

    private fun returnItemOrMonToSeller(server: MinecraftServer, auction: AuctionState) {
        val sellerUuid = auction.sellerUuid ?: return
        val seller = server.playerList.getPlayer(sellerUuid)

        if (auction.type == AuctionType.ITEM) {
            val item = com.desgraciamon.auctions.AuctionItemSerializer.deserialize(auction.itemTagJson)
            if (seller != null) {
                if (!seller.inventory.add(item)) seller.drop(item, false)
            } else {
                config.pendingClaims.computeIfAbsent(sellerUuid.toString()) { mutableListOf() }.add(
                    AuctionPendingClaim(type = auction.type, itemTagJson = auction.itemTagJson, reason = "DEVOLUCION_SUBASTA")
                )
                save()
            }
        } else if (auction.type == AuctionType.POKEMON) {
            val pokemon = AuctionPokemonSerializer.deserialize(auction.pokemonJson, server.registryAccess())
            if (pokemon != null) {
                if (seller != null) {
                    AuctionPokemonSerializer.givePokemonToPlayer(seller, pokemon)
                } else {
                    config.pendingClaims.computeIfAbsent(sellerUuid.toString()) { mutableListOf() }.add(
                        AuctionPendingClaim(type = auction.type, pokemonJson = auction.pokemonJson, reason = "DEVOLUCION_SUBASTA")
                    )
                    save()
                }
            }
        }
    }

    fun updateStandEntities(server: MinecraftServer) {
        clearStandEntities(server)
        val stand = config.standPos ?: return
        val auction = config.activeAuction ?: return
        if (!auction.active) return

        val level = server.getLevel(net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            ResourceLocation.parse(stand.dimension)
        )) ?: server.overworld() ?: return

        val centerPos = Vec3(stand.x + 0.5, stand.y, stand.z + 0.5)

        // 1. Spawner de Entidad 3D (Ítem o Pokémon)
        if (auction.type == AuctionType.ITEM) {
            val stack = com.desgraciamon.auctions.AuctionItemSerializer.deserialize(auction.itemTagJson)
            val itemEntity = ItemEntity(level, centerPos.x, centerPos.y + 0.6, centerPos.z, stack)
            itemEntity.setNoGravity(true)
            itemEntity.isInvulnerable = true
            itemEntity.setPickUpDelay(32767)
            itemEntity.deltaMovement = Vec3.ZERO
            itemEntity.addTag("desgraciamon_auction_stand")
            level.addFreshEntity(itemEntity)
            displayItemOrMonId = itemEntity.id
        } else if (auction.type == AuctionType.POKEMON) {
            val mon = AuctionPokemonSerializer.deserialize(auction.pokemonJson, server.registryAccess())
            if (mon != null) {
                val monEntity = AuctionPokemonSerializer.createDisplayPokemon(level, centerPos, mon)
                if (monEntity != null) {
                    monEntity.addTag("desgraciamon_auction_stand")
                    level.addFreshEntity(monEntity)
                    displayItemOrMonId = monEntity.id
                }
            }
        }

        // 2. Holograma de Texto (TextDisplay nativo de Minecraft)
        val textDisplay = Display.TextDisplay(EntityType.TEXT_DISPLAY, level)
        textDisplay.setPos(centerPos.x, centerPos.y + 1.25, centerPos.z)
        textDisplay.setBillboardConstraints(Display.BillboardConstraints.CENTER)
        textDisplay.viewRange = 32f
        textDisplay.addTag("desgraciamon_auction_stand")
        textDisplay.text = buildHologramComponent(auction)
        level.addFreshEntity(textDisplay)
        hologramEntityId = textDisplay.id
    }

    private fun updateHologramText(server: MinecraftServer) {
        val stand = config.standPos ?: return
        val auction = config.activeAuction ?: return
        val id = hologramEntityId ?: return

        val level = server.getLevel(net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            ResourceLocation.parse(stand.dimension)
        )) ?: server.overworld() ?: return

        val entity = level.getEntity(id)
        if (entity is Display.TextDisplay) {
            entity.text = buildHologramComponent(auction)
        }
    }

    fun buildHologramComponent(auction: AuctionState): Component {
        val minStr = if (auction.currentBid <= 0) "${auction.startingBid}$" else "${auction.minNextBid}$"
        val timeMin = auction.timeRemainingSeconds / 60
        val timeSec = auction.timeRemainingSeconds % 60
        val timeStr = String.format("%02d:%02d", timeMin, timeSec)

        val full = StringBuilder()
        full.append("§6§l${auction.title}\n")
        full.append("§fPujador: §b${auction.bidderDisplay}\n")
        full.append("§fPuja actual: §e${auction.currentBid}$\n")
        full.append("§fIncremento mínimo: §6${auction.minIncrement}$\n")
        full.append("§fPuja mínima: §a$minStr\n")
        full.append("§7Tiempo: §c$timeStr")
        return Component.literal(full.toString())
    }

    fun clearStandEntities(server: MinecraftServer) {
        val stand = config.standPos ?: return
        val level = server.getLevel(net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            ResourceLocation.parse(stand.dimension)
        )) ?: server.overworld() ?: return

        val box = AABB(stand.x - 4.0, stand.y - 2.0, stand.z - 4.0, stand.x + 4.0, stand.y + 4.0, stand.z + 4.0)
        val entities = level.getEntities(null as Entity?, box) {
            it.tags.contains("desgraciamon_auction_stand") ||
            it is com.cobblemon.mod.common.entity.pokemon.PokemonEntity ||
            it is Display.TextDisplay ||
            it is ItemEntity
        }
        for (e in entities) {
            e.discard()
        }
        hologramEntityId = null
        displayItemOrMonId = null
    }

    fun syncToAll(server: MinecraftServer) {
        val payload = createSyncPayload()
        for (player in server.playerList.players) {
            if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, AuctionSyncPayload.TYPE)) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload)
            }
        }
    }

    fun syncToPlayer(player: ServerPlayer) {
        if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, AuctionSyncPayload.TYPE)) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, createSyncPayload())
        }
    }

    fun createSyncPayload(): AuctionSyncPayload {
        val auction = config.activeAuction
        val stand = config.standPos
        return if (auction != null && auction.active) {
            AuctionSyncPayload(
                active = true,
                standX = stand?.x ?: 0.0,
                standY = stand?.y ?: 0.0,
                standZ = stand?.z ?: 0.0,
                standDimension = stand?.dimension ?: "minecraft:overworld",
                isPokemon = auction.type == AuctionType.POKEMON,
                title = auction.title,
                currentBid = auction.currentBid,
                minNextBid = auction.minNextBid,
                highestBidder = auction.bidderDisplay,
                timeRemainingSeconds = auction.timeRemainingSeconds,
                pokemonDetails = auction.pokemonDetails
            )
        } else {
            AuctionSyncPayload(
                active = false,
                standX = stand?.x ?: 0.0,
                standY = stand?.y ?: 0.0,
                standZ = stand?.z ?: 0.0,
                standDimension = stand?.dimension ?: "minecraft:overworld",
                isPokemon = false,
                title = "",
                currentBid = 0,
                minNextBid = 0,
                highestBidder = "Nadie",
                timeRemainingSeconds = 0,
                pokemonDetails = null
            )
        }
    }

    private fun broadcast(server: MinecraftServer, text: String) {
        val comp = Component.literal(text)
        for (p in server.playerList.players) {
            p.sendSystemMessage(comp)
        }
    }
}

