package com.desgraciamon.economy

import fr.harmex.cobbledollars.common.client.gui.screen.BankScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

object BankScreenRenderer {

    fun renderPredictionHUD(screen: BankScreen, graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val mc = Minecraft.getInstance()
        val font = mc.font
        val screenX = screen.x
        val screenY = screen.y

        val predictions = BankScreenHelper.lastPredictions

        // Dimensiones y posicionamiento lateral del panel (a un costado, sin invadir la interfaz del banquero)
        val panelW = 162
        val rightX = screenX + 176 + 6
        val leftX = screenX - panelW - 6
        // Si hay espacio a la derecha, colocarlo a la derecha; sino a la izquierda
        val panelX = if (rightX + panelW <= screen.width - 4) rightX else if (leftX >= 4) leftX else (screen.width - panelW - 4).coerceAtLeast(4)
        val panelY = screenY + 6
        val panelH = 196

        // Fondo oscuro elegante con borde temático
        val maxDrop = predictions.maxOfOrNull { it.dropPercent } ?: 0.0
        val borderColor = when {
            maxDrop >= 15.0 -> 0xFFFF4444.toInt()
            maxDrop >= 5.0 -> 0xFFFFB703.toInt()
            predictions.any { it.isTrending } -> 0xFFFFD700.toInt()
            else -> 0xFF38BDF8.toInt()
        }

        // Sombra y fondo
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xF00D1520.toInt())
        drawOutline(graphics, panelX, panelY, panelW, panelH, borderColor)

        // Cabecera del Panel
        graphics.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + 18, 0x991E293B.toInt())
        graphics.drawString(font, "§6§lIMPACTO EN BOLSA", panelX + 7, panelY + 5, 0xFFFFFFFF.toInt(), false)

        var curY = panelY + 23

        if (predictions.isEmpty()) {
            // Estado de descanso: Muestra los objetos en alza y el temporizador del próximo reseteo
            graphics.drawString(font, "§7Coloca ítems en el banquero", panelX + 7, curY, 0xFFAAAAAA.toInt(), false)
            curY += 11
            graphics.drawString(font, "§7para ver el impacto en vivo.", panelX + 7, curY, 0xFFAAAAAA.toInt(), false)
            curY += 16

            val trending = MarketStore.state.trendingItems
            val bonus = MarketStore.config.trendingBonusPercent.toInt()
            graphics.drawString(font, "§6Objetos en Alza (+${bonus}%):", panelX + 7, curY, 0xFFFFD700.toInt(), false)
            curY += 13

            if (trending.isEmpty()) {
                graphics.drawString(font, "§8(Precios en equilibrio)", panelX + 12, curY, 0xFF888888.toInt(), false)
                curY += 12
            } else {
                for (trendId in trending.take(4)) {
                    val cfg = MarketEngine.getItemConfig(trendId)
                    val name = cfg?.displayName ?: trendId
                    val trimmed = if (name.length > 17) name.take(15) + ".." else name
                    graphics.drawString(font, "§e- $trimmed §a(+${bonus}%)", panelX + 9, curY, 0xFFE0E0E0.toInt(), false)
                    curY += 11
                }
            }

            curY += 8
            // Tiempo restante para el reseteo cíclico del mercado
            val nextReset = MarketEngine.getDisplayNextResetTimestampMs()
            val remaining = MarketEngine.formatDurationRemaining(nextReset - System.currentTimeMillis())
            val hourStr = String.format("%02d", MarketEngine.getResetHourVet())
            graphics.drawString(font, "§8Actualiza: §e$remaining §7(${hourStr}h VET)", panelX + 7, panelY + panelH - 16, 0xFF888888.toInt(), false)
        } else {
            // Estado activo: desglose detallado de los ítems en venta
            val visibleCount = predictions.take(3)
            for (item in visibleCount) {
                val displayName = if (item.displayName.length > 16) item.displayName.take(14) + ".." else item.displayName
                graphics.drawString(font, "§f§e${item.count}x §f$displayName", panelX + 6, curY, 0xFFFFFFFF.toInt(), false)
                curY += 10

                // Indicador de Tendencia / Caída / Alza
                val trendText = when {
                    item.isTrending -> "§6EN ALZA (+${item.trendingBonus.toInt()}%)"
                    item.dropPercent >= 1.0 -> "§c-${String.format("%.1f", item.dropPercent)}% (A la baja)"
                    else -> "§aEstable (Sin caída)"
                }
                graphics.drawString(font, trendText, panelX + 12, curY, 0xFFE0E0E0.toInt(), false)
                curY += 10

                // Comparación de Precio Unitario
                val unitComp = "§7Ud: §f$${formatPrice(item.startUnitPrice)} §8-> §e$${formatPrice(item.endUnitPrice)}"
                graphics.drawString(font, unitComp, panelX + 12, curY, 0xFFCCCCCC.toInt(), false)
                curY += 10

                // Comparación de Precio por Stack (x64)
                val stackComp = "§7Stack: §f$${formatPrice(item.startStackPrice)} §8-> §e$${formatPrice(item.endStackPrice)}"
                graphics.drawString(font, stackComp, panelX + 12, curY, 0xFFCCCCCC.toInt(), false)
                curY += 10

                // Pago calculado para este lote
                val payoutText = "§7Pago: §a+$${item.totalPayout}"
                graphics.drawString(font, payoutText, panelX + 12, curY, 0xFF88FF88.toInt(), false)
                curY += 14
            }

            if (predictions.size > 3) {
                val remaining = predictions.size - 3
                graphics.drawString(font, "§8(+ $remaining ítems más en lote...)", panelX + 12, curY, 0xFF888888.toInt(), false)
                curY += 11
            }

            // Pie de página del panel con total y alertas
            val footY = panelY + panelH - 28
            graphics.fill(panelX + 4, footY, panelX + panelW - 4, footY + 1, 0x44FFFFFF.toInt())
            graphics.drawString(font, "§7Total Pago: §e§l$${BankScreenHelper.totalPayout}", panelX + 6, footY + 4, 0xFFFFD700.toInt(), false)

            if (maxDrop >= 15.0) {
                graphics.drawString(font, "§cProvocará caída alta", panelX + 6, footY + 15, 0xFFFF5555.toInt(), false)
            } else if (maxDrop >= 5.0) {
                graphics.drawString(font, "§eImpacto moderado", panelX + 6, footY + 15, 0xFFFFCC00.toInt(), false)
            } else {
                graphics.drawString(font, "§aMercado receptivo", panelX + 6, footY + 15, 0xFF55FFAA.toInt(), false)
            }
        }
    }

    private fun formatPrice(price: Double): String {
        return if (price >= 10000.0) {
            String.format("%.1fk", price / 1000.0)
        } else if (price == price.toLong().toDouble()) {
            price.toLong().toString()
        } else {
            String.format("%.1f", price)
        }
    }

    private fun drawOutline(graphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int, color: Int) {
        graphics.fill(x, y, x + w, y + 1, color)
        graphics.fill(x, y + h - 1, x + w, y + h, color)
        graphics.fill(x, y + 1, x + 1, y + h - 1, color)
        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, color)
    }
}
