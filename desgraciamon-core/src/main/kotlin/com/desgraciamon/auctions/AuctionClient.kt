package com.desgraciamon.auctions

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import kotlin.math.max
import kotlin.math.min

object AuctionClientState {
    var active: Boolean = false
    var standX: Double = 0.0
    var standY: Double = 0.0
    var standZ: Double = 0.0
    var standDimension: String = "minecraft:overworld"
    var isPokemon: Boolean = false
    var title: String = ""
    var currentBid: Long = 0L
    var minNextBid: Long = 0L
    var highestBidder: String = "Nadie"
    var timeRemainingSeconds: Int = 0
    var pokemonDetails: AuctionPokemonDetails? = null

    var fadeAlpha: Float = 0.0f
}

@Environment(EnvType.CLIENT)
class AuctionClient : ClientModInitializer {

    override fun onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(AuctionSyncPayload.TYPE) { payload, context ->
            context.client().execute {
                AuctionClientState.active = payload.active
                AuctionClientState.standX = payload.standX
                AuctionClientState.standY = payload.standY
                AuctionClientState.standZ = payload.standZ
                AuctionClientState.standDimension = payload.standDimension
                AuctionClientState.isPokemon = payload.isPokemon
                AuctionClientState.title = payload.title
                AuctionClientState.currentBid = payload.currentBid
                AuctionClientState.minNextBid = payload.minNextBid
                AuctionClientState.highestBidder = payload.highestBidder
                AuctionClientState.timeRemainingSeconds = payload.timeRemainingSeconds
                AuctionClientState.pokemonDetails = payload.pokemonDetails
            }
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            AuctionClientState.active = false
            AuctionClientState.fadeAlpha = 0.0f
        }

        // Rotación continua suave del Pokémon en el pedestal en el cliente (60 FPS)
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register { client ->
            val level = client.level ?: return@register
            val state = AuctionClientState
            if (state.active && state.isPokemon) {
                val center = Vec3(state.standX + 0.5, state.standY + 0.5, state.standZ + 0.5)
                val box = net.minecraft.world.phys.AABB(center.x - 2.0, center.y - 1.5, center.z - 2.0, center.x + 2.0, center.y + 3.0, center.z + 2.0)
                val entities = level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity::class.java, box)
                for (e in entities) {
                    val rot = (e.yRot + 2.5f) % 360f
                    e.yRot = rot
                    e.yHeadRot = rot
                    e.yBodyRot = rot
                }
            }
        }

        HudRenderCallback.EVENT.register(::renderAuctionHud)
    }

    private fun renderAuctionHud(graphics: GuiGraphics, tickCounter: DeltaTracker) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (mc.options.hideGui || mc.screen != null) return

        val state = AuctionClientState
        if (!state.active || !state.isPokemon || state.pokemonDetails == null) {
            state.fadeAlpha = max(0.0f, state.fadeAlpha - 0.08f)
            return
        }

        val currentDim = player.level().dimension().location().toString()
        if (currentDim != state.standDimension) {
            state.fadeAlpha = max(0.0f, state.fadeAlpha - 0.08f)
            return
        }

        val standCenter = Vec3(state.standX + 0.5, state.standY + 0.8, state.standZ + 0.5)
        val dist = player.position().distanceTo(standCenter)

        var isLooking = false
        if (dist in 0.5..10.0) {
            val partial = tickCounter.getGameTimeDeltaPartialTick(false)
            val eyePos = player.getEyePosition(partial)
            val toStand = standCenter.subtract(eyePos)
            val dir = toStand.normalize()
            val look = player.getViewVector(partial).normalize()
            val dot = look.dot(dir)

            if (dot > 0.84) {
                isLooking = true
            } else if (mc.crosshairPickEntity != null) {
                val picked = mc.crosshairPickEntity!!
                if (picked.tags.contains("desgraciamon_auction_stand") || picked.position().distanceTo(standCenter) < 2.2) {
                    isLooking = true
                }
            }
        }

        if (isLooking) {
            state.fadeAlpha = min(1.0f, state.fadeAlpha + 0.12f)
        } else {
            state.fadeAlpha = max(0.0f, state.fadeAlpha - 0.08f)
        }

        if (state.fadeAlpha > 0.02f) {
            renderPokemonCard(graphics, mc, state.pokemonDetails!!, state, state.fadeAlpha)
        }
    }

    private fun renderPokemonCard(
        graphics: GuiGraphics,
        mc: Minecraft,
        details: AuctionPokemonDetails,
        state: AuctionClientState,
        alpha: Float
    ) {
        val font = mc.font
        val screenW = mc.window.guiScaledWidth
        val screenH = mc.window.guiScaledHeight

        val cardW = 230
        val cardH = 246
        val cardX = (screenW - cardW - 14).coerceAtLeast(screenW / 2 + 10)
        val cardY = ((screenH - cardH) / 2).coerceAtLeast(8)

        // 1. Fondos y Bordes
        val borderRgb = if (details.shiny) 0xFFD700 else 0x3F72AF
        val primaryTypeColor = getTypeColor(details.types.firstOrNull())

        // Fondo exterior
        graphics.fill(cardX, cardY, cardX + cardW, cardY + cardH, argb(0.92f * alpha, 0x0B0E14))
        // Fondo interior
        graphics.fill(cardX + 2, cardY + 2, cardX + cardW - 2, cardY + cardH - 2, argb(0.85f * alpha, 0x141822))

        // Borde fino
        drawBorder(graphics, cardX, cardY, cardW, cardH, argb(0.70f * alpha, borderRgb))
        // Barra superior de acento con el color del tipo principal
        graphics.fill(cardX + 2, cardY + 2, cardX + cardW - 2, cardY + 5, argb(0.95f * alpha, primaryTypeColor))

        var curY = cardY + 9

        // 2. Cabecera (Nombre, Nivel, Shiny)
        val titleText = StringBuilder()
        if (details.shiny) {
            titleText.append("§6[Shiny] ")
        }
        titleText.append("§f§l${details.displayName.ifBlank { details.species }}")
        if (details.shiny) {
            titleText.append(" §e(Shiny)")
        }
        graphics.drawString(font, Component.literal(titleText.toString()), cardX + 8, curY, argb(1.0f * alpha, 0xFFFFFF), true)

        val levelStr = "§eNv. §f${details.level}"
        val levelW = font.width(Component.literal(levelStr))
        graphics.drawString(font, Component.literal(levelStr), cardX + cardW - 8 - levelW, curY, argb(1.0f * alpha, 0xFFFFFF), true)

        curY += font.lineHeight + 4

        // 3. Género y Badges de Tipo
        val genderStr = when (details.gender.uppercase()) {
            "MALE" -> "§bMacho"
            "FEMALE" -> "§dHembra"
            else -> "§7Sin género"
        }
        graphics.drawString(font, Component.literal(genderStr), cardX + 8, curY + 1, argb(1.0f * alpha, 0xFFFFFF), true)

        // Renderizar pastillas de tipos en la derecha
        var badgeRight = cardX + cardW - 8
        for (type in details.types.reversed()) {
            val typeColor = getTypeColor(type)
            val typeLabel = type.uppercase()
            val textW = font.width(typeLabel)
            val badgeW = textW + 8
            val badgeX = badgeRight - badgeW
            val badgeY = curY - 1

            graphics.fill(badgeX, badgeY, badgeX + badgeW, badgeY + 11, argb(0.85f * alpha, typeColor))
            drawBorder(graphics, badgeX, badgeY, badgeW, 11, argb(0.95f * alpha, 0x000000))
            graphics.drawString(font, typeLabel, badgeX + 4, badgeY + 2, argb(1.0f * alpha, 0xFFFFFF), true)

            badgeRight = badgeX - 4
        }

        curY += 15

        // 4. Habilidad y Naturaleza
        graphics.drawString(font, Component.literal("§7Habilidad: §f${details.ability.ifBlank { "Desconocida" }}"), cardX + 8, curY, argb(1.0f * alpha, 0xFFFFFF), true)
        curY += font.lineHeight + 2
        graphics.drawString(font, Component.literal("§7Naturaleza: §e${details.nature}"), cardX + 8, curY, argb(1.0f * alpha, 0xFFFFFF), true)
        curY += font.lineHeight + 4

        // Divisor
        graphics.fill(cardX + 8, curY, cardX + cardW - 8, curY + 1, argb(0.35f * alpha, 0x4E5D6C))
        curY += 5

        // 5. Estadísticas (IVs y EVs)
        val totalIvs = details.ivs.values.sum()
        val ivPct = (totalIvs * 100) / 186
        graphics.drawString(font, Component.literal("§9§lSTATS (IVs)"), cardX + 8, curY, argb(1.0f * alpha, 0x5BC0DE), true)
        val pctStr = "§b$ivPct% §7($totalIvs/186)"
        val pctW = font.width(Component.literal(pctStr))
        graphics.drawString(font, Component.literal(pctStr), cardX + cardW - 8 - pctW, curY, argb(1.0f * alpha, 0xFFFFFF), true)
        curY += font.lineHeight + 3

        val col1X = cardX + 8
        val col2X = cardX + (cardW / 2) + 2

        val statsCol1 = listOf("HP" to "PS", "ATK" to "ATQ", "DEF" to "DEF")
        val statsCol2 = listOf("SPA" to "AT.ESP", "SPD" to "DF.ESP", "SPE" to "VEL")

        for (i in 0..2) {
            val (statKey1, label1) = statsCol1[i]
            val iv1 = details.ivs[statKey1] ?: 0
            val ev1 = details.evs[statKey1] ?: 0
            val str1 = "§7$label1: ${formatStat(iv1)} §8(+${ev1}EV)"
            graphics.drawString(font, Component.literal(str1), col1X, curY, argb(1.0f * alpha, 0xFFFFFF), true)

            val (statKey2, label2) = statsCol2[i]
            val iv2 = details.ivs[statKey2] ?: 0
            val ev2 = details.evs[statKey2] ?: 0
            val str2 = "§7$label2: ${formatStat(iv2)} §8(+${ev2}EV)"
            graphics.drawString(font, Component.literal(str2), col2X, curY, argb(1.0f * alpha, 0xFFFFFF), true)

            curY += font.lineHeight + 2
        }

        curY += 2
        // Divisor
        graphics.fill(cardX + 8, curY, cardX + cardW - 8, curY + 1, argb(0.35f * alpha, 0x4E5D6C))
        curY += 5

        // 6. Movimientos
        graphics.drawString(font, Component.literal("§9§lMOVIMIENTOS"), cardX + 8, curY, argb(1.0f * alpha, 0x5BC0DE), true)
        curY += font.lineHeight + 3

        if (details.moves.isEmpty()) {
            graphics.drawString(font, Component.literal("§8Sin movimientos registrados"), cardX + 8, curY, argb(1.0f * alpha, 0x888888), true)
            curY += font.lineHeight + 2
        } else {
            val moveRows = details.moves.take(4).chunked(2)
            for (row in moveRows) {
                val m1 = "§f- ${row[0]}"
                graphics.drawString(font, Component.literal(m1), col1X, curY, argb(1.0f * alpha, 0xFFFFFF), true)
                if (row.size > 1) {
                    val m2 = "§f- ${row[1]}"
                    graphics.drawString(font, Component.literal(m2), col2X, curY, argb(1.0f * alpha, 0xFFFFFF), true)
                }
                curY += font.lineHeight + 2
            }
        }

        curY += 2
        // Divisor
        graphics.fill(cardX + 8, curY, cardX + cardW - 8, curY + 1, argb(0.35f * alpha, 0x4E5D6C))
        curY += 5

        // 7. Footer de Subasta Live
        val timeMin = state.timeRemainingSeconds / 60
        val timeSec = state.timeRemainingSeconds % 60
        val timeStr = String.format("%02d:%02d", timeMin, timeSec)

        val bidInfo = "§7Puja: §a${state.currentBid}$ §7(§e${state.highestBidder}§7)"
        graphics.drawString(font, Component.literal(bidInfo), cardX + 8, curY, argb(1.0f * alpha, 0xFFFFFF), true)
        curY += font.lineHeight + 2

        val nextBidInfo = "§7Mín: §e${state.minNextBid}$ §7| Tiempo: §c$timeStr"
        graphics.drawString(font, Component.literal(nextBidInfo), cardX + 8, curY, argb(1.0f * alpha, 0xFFFFFF), true)
        curY += font.lineHeight + 2

        val hint = "§b/bid as player <monto> §8| §b/bid as guild <monto>"
        graphics.drawString(font, Component.literal(hint), cardX + 8, curY, argb(0.85f * alpha, 0xAAAAAA), true)
    }

    private fun formatStat(iv: Int): String {
        return when {
            iv >= 31 -> "§6§l31"
            iv >= 25 -> "§a$iv"
            iv >= 15 -> "§e$iv"
            else -> "§c$iv"
        }
    }

    private fun drawBorder(graphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int, color: Int) {
        graphics.fill(x, y, x + w, y + 1, color)
        graphics.fill(x, y + h - 1, x + w, y + h, color)
        graphics.fill(x, y + 1, x + 1, y + h - 1, color)
        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, color)
    }

    private fun argb(alpha: Float, rgb: Int): Int {
        val a = (alpha * 255.0f).toInt().coerceIn(0, 255)
        return (a shl 24) or (rgb and 0x00FFFFFF)
    }

    private fun getTypeColor(typeName: String?): Int {
        return when (typeName?.uppercase()) {
            "FIRE", "FUEGO" -> 0xFA6555
            "WATER", "AGUA" -> 0x58ABF6
            "GRASS", "PLANTA" -> 0x2CDAB1
            "ELECTRIC", "ELECTRICO", "ELÉCTRICO" -> 0xF7D02C
            "ICE", "HIELO" -> 0x76EEFF
            "FIGHTING", "LUCHA" -> 0xD56723
            "POISON", "VENENO" -> 0xB97FC9
            "GROUND", "TIERRA" -> 0xD8B868
            "FLYING", "VOLADOR" -> 0x98A4E2
            "PSYCHIC", "PSIQUICO", "PSÍQUICO" -> 0xFA7179
            "BUG", "BICHO" -> 0xA8B820
            "ROCK", "ROCA" -> 0xB8A038
            "GHOST", "FANTASMA" -> 0x7B62A3
            "DRAGON", "DRAGÓN" -> 0x7038F8
            "STEEL", "ACERO" -> 0x99B0BD
            "FAIRY", "HADA" -> 0xFDB9E9
            "DARK", "SINIESTRO" -> 0x705746
            else -> 0x9DA0A8
        }
    }
}

