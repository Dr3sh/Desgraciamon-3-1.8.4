package com.desgraciamon.discordbridge

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import net.dv8tion.jda.api.interactions.components.text.TextInput
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle
import net.dv8tion.jda.api.interactions.modals.Modal
import java.awt.Color
import java.time.LocalDate
import java.time.YearMonth

object HitosDiscordUi {
    private val spanishMonthsFull = listOf(
        "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
        "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
    )

    fun canManageHitos(member: Member?, config: DiscordBridgeConfig, forRoleChange: Boolean = false): Boolean {
        if (member == null) return false
        val roleId = HitosDiscordHelper.getHitosRoleId(config)
        if (forRoleChange) {
            return member.hasPermission(Permission.ADMINISTRATOR) ||
                (roleId.isNotBlank() && member.roles.any { it.id == roleId })
        }
        if (roleId.isNotBlank()) {
            return member.roles.any { it.id == roleId } || member.hasPermission(Permission.ADMINISTRATOR)
        }
        return member.hasPermission(Permission.ADMINISTRATOR) || member.hasPermission(Permission.MANAGE_SERVER)
    }

    fun permissionError(config: DiscordBridgeConfig, forRoleChange: Boolean = false): String {
        val roleId = HitosDiscordHelper.getHitosRoleId(config)
        if (forRoleChange) {
            return if (roleId.isNotBlank()) {
                "Solo un Administrador o el rol <@&$roleId> puede cambiar el rol de hitos."
            } else {
                "Necesitas permisos de Administrador para configurar el rol de hitos."
            }
        }
        return if (roleId.isNotBlank()) {
            "No tienes permiso. Solo Administradores o el rol <@&$roleId> pueden gestionar hitos."
        } else {
            "Necesitas permisos de Administrador o configurar un rol con `/admin rol-hitos`."
        }
    }

    fun replyPanel(event: SlashCommandInteractionEvent, config: DiscordBridgeConfig, notice: String? = null) {
        val embed = HitosDiscordHelper.buildEmbed(config, showAdminInfo = true, statusNotice = notice)
        event.replyEmbeds(embed)
            .setComponents(buildPanelRows())
            .setEphemeral(true)
            .queue()
    }

    fun replyDatePicker(event: SlashCommandInteractionEvent, draftDate: LocalDate = HitosDiscordHelper.getDia1Date()) {
        event.replyEmbeds(buildDatePickerEmbed(draftDate))
            .setComponents(buildDatePickerRows(draftDate))
            .setEphemeral(true)
            .queue()
    }

    private fun buildPanelRows(): List<ActionRow> {
        val rows = mutableListOf<ActionRow>()
        rows.add(
            ActionRow.of(
                Button.primary("hitos:date_open", "Fecha Día 1"),
                Button.primary("hitos:day_open", "Día actual"),
                Button.success("hitos:add_hito", "Nuevo hito"),
                Button.success("hitos:add_event", "Nuevo evento")
            )
        )

        val entries = HitosDiscordHelper.listMilestones()
        if (entries.isNotEmpty()) {
            val menu = StringSelectMenu.create("hitos:select_edit")
                .setPlaceholder("Elegir un hito para editar o eliminar...")
            for (entry in entries.take(25)) {
                val kind = if (entry.progression) "Progreso" else "Evento"
                val label = "Día ${entry.dayOffset} · ${entry.title}".take(100)
                val desc = "$kind · ${entry.leaderOrBoss.ifBlank { entry.id }}".take(100)
                menu.addOption(label, entry.id.take(100), desc)
            }
            rows.add(ActionRow.of(menu.build()))
        }

        rows.add(
            ActionRow.of(
                Button.secondary("hitos:publish", "Publicar en canal"),
                Button.secondary("hitos:refresh", "Actualizar")
            )
        )
        return rows
    }

    private fun buildDatePickerEmbed(draftDate: LocalDate): MessageEmbed {
        val currentSaved = HitosDiscordHelper.getDia1()
        val monthName = spanishMonthsFull[draftDate.monthValue - 1]
        return EmbedBuilder()
            .setTitle("Selector de Fecha — Día 1")
            .setColor(Color(88, 101, 242))
            .setDescription(
                "**Fecha seleccionada:** ${draftDate.dayOfMonth} de $monthName de ${draftDate.year} (`$draftDate`)\n" +
                    "**Guardada actualmente:** `$currentSaved`\n\n" +
                    "Elige año, mes y día en los menús y pulsa **Confirmar fecha**."
            )
            .setFooter("Zona horaria: Venezuela (America/Caracas)")
            .build()
    }

    private fun buildDatePickerRows(draftDate: LocalDate): List<ActionRow> {
        val iso = draftDate.toString()
        val baseYear = HitosDiscordHelper.todayCaracas().year
        val years = ((baseYear - 1)..(baseYear + 2)).toMutableSet()
        years.add(draftDate.year)
        val sortedYears = years.sorted()

        val yearMenu = StringSelectMenu.create("hitos:date_y:$iso")
            .setPlaceholder("Año: ${draftDate.year}")
        for (y in sortedYears.take(25)) {
            yearMenu.addOption("$y", "$y")
        }
        yearMenu.setDefaultValues(listOf("${draftDate.year}"))

        val monthMenu = StringSelectMenu.create("hitos:date_m:$iso")
            .setPlaceholder("Mes: ${spanishMonthsFull[draftDate.monthValue - 1]}")
        for (m in 1..12) {
            monthMenu.addOption(spanishMonthsFull[m - 1], "$m")
        }
        monthMenu.setDefaultValues(listOf("${draftDate.monthValue}"))

        val dayFirstHalf = StringSelectMenu.create("hitos:date_d1:$iso")
            .setPlaceholder(if (draftDate.dayOfMonth <= 15) "Día seleccionado: ${draftDate.dayOfMonth}" else "Día (1 al 15)")
        for (d in 1..15) {
            dayFirstHalf.addOption("Día $d", "$d")
        }
        if (draftDate.dayOfMonth in 1..15) {
            dayFirstHalf.setDefaultValues(listOf("${draftDate.dayOfMonth}"))
        }

        val maxDay = YearMonth.of(draftDate.year, draftDate.monthValue).lengthOfMonth()
        val daySecondHalf = StringSelectMenu.create("hitos:date_d2:$iso")
            .setPlaceholder(if (draftDate.dayOfMonth >= 16) "Día seleccionado: ${draftDate.dayOfMonth}" else "Día (16 al $maxDay)")
        for (d in 16..maxDay) {
            daySecondHalf.addOption("Día $d", "$d")
        }
        if (draftDate.dayOfMonth in 16..maxDay) {
            daySecondHalf.setDefaultValues(listOf("${draftDate.dayOfMonth}"))
        }

        val buttons = ActionRow.of(
            Button.success("hitos:date_save:$iso", "Confirmar fecha"),
            Button.primary("hitos:date_today", "Usar hoy"),
            Button.secondary("hitos:panel", "Volver")
        )

        return listOf(
            ActionRow.of(yearMenu.build()),
            ActionRow.of(monthMenu.build()),
            ActionRow.of(dayFirstHalf.build()),
            ActionRow.of(daySecondHalf.build()),
            buttons
        )
    }

    private fun buildDayControlEmbed(notice: String? = null): MessageEmbed {
        val currentDay = HitosDiscordHelper.getEffectiveCurrentDay()
        val manual = HitosDiscordHelper.getManualCurrentDay()
        val dia1 = HitosDiscordHelper.getDia1()
        val mode = if (manual > 0) "Manual (Día $manual)" else "Automático (desde $dia1)"
        return EmbedBuilder()
            .setTitle("Control de Día Actual")
            .setColor(Color(88, 101, 242))
            .setDescription(
                buildString {
                    if (!notice.isNullOrBlank()) {
                        appendLine(notice)
                        appendLine()
                    }
                    appendLine("**Día activo:** `Día $currentDay`")
                    append("**Modo:** $mode")
                }
            )
            .setFooter("El modo automático cambia de día a las 08:00 AM (Venezuela)")
            .build()
    }

    private fun buildDayControlRows(): List<ActionRow> {
        return listOf(
            ActionRow.of(
                Button.secondary("hitos:day_step:-1", "-1 Día"),
                Button.secondary("hitos:day_step:+1", "+1 Día"),
                Button.primary("hitos:day_modal", "Escribir día"),
                Button.success("hitos:day_auto", "Automático"),
                Button.secondary("hitos:panel", "Volver")
            )
        )
    }

    private fun buildHitoEditorRows(entry: HitosDiscordHelper.HitoEntry): List<ActionRow> {
        val id = entry.id
        val toggleLabel = if (entry.progression) "Cambiar a Evento" else "Cambiar a Progreso"

        val iconMenu = StringSelectMenu.create("hitos:set_icon:$id")
            .setPlaceholder("Cambiar icono (${HitosDiscordHelper.iconDisplayName(entry.iconItemId)})".take(150))
        var matchedDefault: String? = null
        for ((label, value) in HitosDiscordHelper.presetIcons.take(25)) {
            iconMenu.addOption(label, value)
            if (value.equals(entry.iconItemId, ignoreCase = true)) {
                matchedDefault = value
            }
        }
        if (matchedDefault != null) {
            iconMenu.setDefaultValues(listOf(matchedDefault))
        }

        return listOf(
            ActionRow.of(
                Button.primary("hitos:edit_info:$id", "Editar datos"),
                Button.primary("hitos:edit_reward:$id", "Recompensa"),
                Button.secondary("hitos:toggle_type:$id", toggleLabel),
                Button.danger("hitos:delete_ask:$id", "Eliminar")
            ),
            ActionRow.of(iconMenu.build()),
            ActionRow.of(
                Button.secondary("hitos:panel", "Volver al panel")
            )
        )
    }

    fun handleButton(event: ButtonInteractionEvent, config: DiscordBridgeConfig): Boolean {
        val id = event.componentId
        if (!id.startsWith("hitos:")) return false

        if (!canManageHitos(event.member, config)) {
            event.reply(permissionError(config)).setEphemeral(true).queue()
            return true
        }

        val parts = id.split(":", limit = 3)
        val action = parts.getOrNull(1) ?: return true
        val payload = parts.getOrNull(2) ?: ""

        when (action) {
            "panel", "refresh" -> {
                val embed = HitosDiscordHelper.buildEmbed(config, showAdminInfo = true)
                event.editMessageEmbeds(embed).setComponents(buildPanelRows()).queue()
            }
            "publish" -> {
                val publicEmbed = HitosDiscordHelper.buildEmbed(config, showAdminInfo = false)
                event.channel.sendMessageEmbeds(publicEmbed).queue({
                    val panelEmbed = HitosDiscordHelper.buildEmbed(
                        config,
                        showAdminInfo = true,
                        statusNotice = "Cronograma publicado en este canal."
                    )
                    event.editMessageEmbeds(panelEmbed).setComponents(buildPanelRows()).queue()
                }, {
                    event.reply("No se pudo publicar en este canal (revisa los permisos del bot).").setEphemeral(true).queue()
                })
            }
            "date_open" -> {
                val draft = HitosDiscordHelper.getDia1Date()
                event.editMessageEmbeds(buildDatePickerEmbed(draft))
                    .setComponents(buildDatePickerRows(draft))
                    .queue()
            }
            "date_today" -> {
                val today = HitosDiscordHelper.todayCaracas()
                HitosDiscordHelper.setDia1(today.toString())
                val panelEmbed = HitosDiscordHelper.buildEmbed(
                    config,
                    showAdminInfo = true,
                    statusNotice = "Día 1 actualizado a hoy (`$today`). Sincronizado con Minecraft."
                )
                event.editMessageEmbeds(panelEmbed).setComponents(buildPanelRows()).queue()
            }
            "date_save" -> {
                val ok = HitosDiscordHelper.setDia1(payload)
                if (ok) {
                    val panelEmbed = HitosDiscordHelper.buildEmbed(
                        config,
                        showAdminInfo = true,
                        statusNotice = "Día 1 guardado (`$payload`). Fechas recalculadas y sincronizadas."
                    )
                    event.editMessageEmbeds(panelEmbed).setComponents(buildPanelRows()).queue()
                } else {
                    event.reply("No se pudo guardar la fecha `$payload`.").setEphemeral(true).queue()
                }
            }
            "day_open" -> {
                event.editMessageEmbeds(buildDayControlEmbed())
                    .setComponents(buildDayControlRows())
                    .queue()
            }
            "day_step" -> {
                val delta = payload.toIntOrNull() ?: 0
                val current = HitosDiscordHelper.getEffectiveCurrentDay()
                val next = (current + delta).coerceAtLeast(1)
                HitosDiscordHelper.setCurrentDay(next)
                event.editMessageEmbeds(buildDayControlEmbed("Día actual ajustado a **Día $next**."))
                    .setComponents(buildDayControlRows())
                    .queue()
            }
            "day_auto" -> {
                val effective = HitosDiscordHelper.setCurrentDay(0)
                event.editMessageEmbeds(buildDayControlEmbed("Modo automático activado (**Día $effective**)."))
                    .setComponents(buildDayControlRows())
                    .queue()
            }
            "day_modal" -> {
                val current = HitosDiscordHelper.getEffectiveCurrentDay()
                val input = TextInput.create("numero", "Número de día (0 = automático)", TextInputStyle.SHORT)
                    .setPlaceholder("Ej: 1, 5, 12 o 0")
                    .setValue("$current")
                    .setRequired(true)
                    .setMaxLength(4)
                    .build()
                val modal = Modal.create("hitos:modal_day", "Fijar Día Actual")
                    .addActionRow(input)
                    .build()
                event.replyModal(modal).queue()
            }
            "add_hito" -> {
                val modal = Modal.create("hitos:modal_add_hito", "Nuevo Hito de Progreso")
                    .addActionRow(
                        TextInput.create("titulo", "Nombre del hito", TextInputStyle.SHORT)
                            .setPlaceholder("Ej: Gimnasio Plateada")
                            .setRequired(true)
                            .setMaxLength(80)
                            .build()
                    )
                    .addActionRow(
                        TextInput.create("dia", "Día (desde Día 1)", TextInputStyle.SHORT)
                            .setPlaceholder("Ej: 4")
                            .setRequired(true)
                            .setMaxLength(4)
                            .build()
                    )
                    .addActionRow(
                        TextInput.create("subtitulo", "Líder o detalle (opcional)", TextInputStyle.SHORT)
                            .setPlaceholder("Ej: Líder Brock")
                            .setRequired(false)
                            .setMaxLength(80)
                            .build()
                    )
                    .addActionRow(
                        TextInput.create("recompensa", "Recompensa (opcional)", TextInputStyle.SHORT)
                            .setPlaceholder("Ej: 2500$ + 2 Caramelos Raros")
                            .setRequired(false)
                            .setMaxLength(100)
                            .build()
                    )
                    .build()
                event.replyModal(modal).queue()
            }
            "add_event" -> {
                val modal = Modal.create("hitos:modal_add_event", "Nuevo Evento Especial")
                    .addActionRow(
                        TextInput.create("titulo", "Nombre del evento", TextInputStyle.SHORT)
                            .setPlaceholder("Ej: Torneo de Bienvenida")
                            .setRequired(true)
                            .setMaxLength(80)
                            .build()
                    )
                    .addActionRow(
                        TextInput.create("dia", "Día (desde Día 1)", TextInputStyle.SHORT)
                            .setPlaceholder("Ej: 7")
                            .setRequired(true)
                            .setMaxLength(4)
                            .build()
                    )
                    .addActionRow(
                        TextInput.create("descripcion", "Detalle breve (opcional)", TextInputStyle.SHORT)
                            .setPlaceholder("Ej: Combates 3v3 en la arena")
                            .setRequired(false)
                            .setMaxLength(100)
                            .build()
                    )
                    .build()
                event.replyModal(modal).queue()
            }
            "edit_info" -> {
                val entry = HitosDiscordHelper.getMilestone(payload)
                if (entry == null) {
                    event.reply("No se encontró ese hito.").setEphemeral(true).queue()
                    return true
                }
                val titleInput = TextInput.create("titulo", "Nombre", TextInputStyle.SHORT)
                    .setRequired(true)
                    .setMaxLength(80)
                    .apply { if (entry.title.isNotBlank()) setValue(entry.title.take(80)) }
                    .build()
                val dayInput = TextInput.create("dia", "Día de inicio", TextInputStyle.SHORT)
                    .setRequired(true)
                    .setMaxLength(4)
                    .setValue("${entry.dayOffset}")
                    .build()
                val subInput = TextInput.create("subtitulo", "Líder / Detalle", TextInputStyle.SHORT)
                    .setRequired(false)
                    .setMaxLength(80)
                    .apply {
                        val sub = entry.leaderOrBoss.ifBlank { entry.subtitle }
                        if (sub.isNotBlank()) setValue(sub.take(80))
                    }
                    .build()
                val durInput = TextInput.create("duracion", "Duración en días", TextInputStyle.SHORT)
                    .setRequired(true)
                    .setMaxLength(3)
                    .setValue("${entry.durationDays}")
                    .build()
                val modal = Modal.create("hitos:modal_edit_info:${entry.id}", "Editar Hito")
                    .addActionRow(titleInput)
                    .addActionRow(dayInput)
                    .addActionRow(subInput)
                    .addActionRow(durInput)
                    .build()
                event.replyModal(modal).queue()
            }
            "edit_reward" -> {
                val entry = HitosDiscordHelper.getMilestone(payload)
                if (entry == null) {
                    event.reply("No se encontró ese hito.").setEphemeral(true).queue()
                    return true
                }
                val moneyInput = TextInput.create("dinero", "CobbleDollars (número)", TextInputStyle.SHORT)
                    .setPlaceholder("Ej: 2500")
                    .setRequired(true)
                    .setMaxLength(12)
                    .setValue("${entry.rewardDollars}")
                    .build()
                val descInput = TextInput.create("descripcion", "Texto de recompensa (opcional)", TextInputStyle.SHORT)
                    .setPlaceholder("Ej: 2500$ + 2 Caramelos Raros")
                    .setRequired(false)
                    .setMaxLength(100)
                    .apply { if (entry.rewardSummary.isNotBlank()) setValue(entry.rewardSummary.take(100)) }
                    .build()
                val modal = Modal.create("hitos:modal_edit_reward:${entry.id}", "Recompensa del Hito")
                    .addActionRow(moneyInput)
                    .addActionRow(descInput)
                    .build()
                event.replyModal(modal).queue()
            }
            "toggle_type" -> {
                val newState = HitosDiscordHelper.toggleMilestoneProgression(payload)
                val updated = HitosDiscordHelper.getMilestone(payload)
                if (newState == null || updated == null) {
                    event.reply("No se encontró ese hito.").setEphemeral(true).queue()
                    return true
                }
                event.editMessageEmbeds(HitosDiscordHelper.buildSingleHitoEmbed(updated))
                    .setComponents(buildHitoEditorRows(updated))
                    .queue()
            }
            "delete_ask" -> {
                val entry = HitosDiscordHelper.getMilestone(payload)
                if (entry == null) {
                    event.reply("No se encontró ese hito.").setEphemeral(true).queue()
                    return true
                }
                val confirmEmbed = EmbedBuilder()
                    .setTitle("Confirmar eliminación")
                    .setColor(Color(231, 76, 60))
                    .setDescription("¿Seguro que deseas eliminar **${entry.title}** (Día ${entry.dayOffset})?")
                    .build()
                event.editMessageEmbeds(confirmEmbed)
                    .setComponents(
                        ActionRow.of(
                            Button.danger("hitos:delete_yes:${entry.id}", "Sí, eliminar"),
                            Button.secondary("hitos:open_item:${entry.id}", "Cancelar")
                        )
                    )
                    .queue()
            }
            "delete_yes" -> {
                val ok = HitosDiscordHelper.removeMilestone(payload)
                val notice = if (ok) "Hito `$payload` eliminado y sincronizado." else "No se encontró el hito `$payload`."
                val embed = HitosDiscordHelper.buildEmbed(config, showAdminInfo = true, statusNotice = notice)
                event.editMessageEmbeds(embed).setComponents(buildPanelRows()).queue()
            }
            "open_item" -> {
                val entry = HitosDiscordHelper.getMilestone(payload)
                if (entry == null) {
                    val embed = HitosDiscordHelper.buildEmbed(config, showAdminInfo = true)
                    event.editMessageEmbeds(embed).setComponents(buildPanelRows()).queue()
                } else {
                    event.editMessageEmbeds(HitosDiscordHelper.buildSingleHitoEmbed(entry))
                        .setComponents(buildHitoEditorRows(entry))
                        .queue()
                }
            }
        }
        return true
    }

    fun handleStringSelect(event: StringSelectInteractionEvent, config: DiscordBridgeConfig): Boolean {
        val id = event.componentId
        if (!id.startsWith("hitos:")) return false

        if (!canManageHitos(event.member, config)) {
            event.reply(permissionError(config)).setEphemeral(true).queue()
            return true
        }

        val parts = id.split(":", limit = 3)
        val action = parts.getOrNull(1) ?: return true
        val payload = parts.getOrNull(2) ?: ""
        val selected = event.values.firstOrNull() ?: return true

        when (action) {
            "select_edit" -> {
                val entry = HitosDiscordHelper.getMilestone(selected)
                if (entry == null) {
                    event.reply("No se encontró ese hito.").setEphemeral(true).queue()
                    return true
                }
                event.editMessageEmbeds(HitosDiscordHelper.buildSingleHitoEmbed(entry))
                    .setComponents(buildHitoEditorRows(entry))
                    .queue()
            }
            "set_icon" -> {
                HitosDiscordHelper.setMilestoneIcon(payload, selected)
                val updated = HitosDiscordHelper.getMilestone(payload)
                if (updated == null) {
                    val embed = HitosDiscordHelper.buildEmbed(config, showAdminInfo = true)
                    event.editMessageEmbeds(embed).setComponents(buildPanelRows()).queue()
                } else {
                    event.editMessageEmbeds(HitosDiscordHelper.buildSingleHitoEmbed(updated))
                        .setComponents(buildHitoEditorRows(updated))
                        .queue()
                }
            }
            "date_y", "date_m", "date_d1", "date_d2" -> {
                val current = runCatching { LocalDate.parse(payload) }.getOrElse { HitosDiscordHelper.getDia1Date() }
                val nextDate = when (action) {
                    "date_y" -> {
                        val y = selected.toIntOrNull() ?: current.year
                        val maxD = YearMonth.of(y, current.monthValue).lengthOfMonth()
                        LocalDate.of(y, current.monthValue, current.dayOfMonth.coerceAtMost(maxD))
                    }
                    "date_m" -> {
                        val m = (selected.toIntOrNull() ?: current.monthValue).coerceIn(1, 12)
                        val maxD = YearMonth.of(current.year, m).lengthOfMonth()
                        LocalDate.of(current.year, m, current.dayOfMonth.coerceAtMost(maxD))
                    }
                    else -> {
                        val maxD = YearMonth.of(current.year, current.monthValue).lengthOfMonth()
                        val d = (selected.toIntOrNull() ?: current.dayOfMonth).coerceIn(1, maxD)
                        LocalDate.of(current.year, current.monthValue, d)
                    }
                }
                event.editMessageEmbeds(buildDatePickerEmbed(nextDate))
                    .setComponents(buildDatePickerRows(nextDate))
                    .queue()
            }
        }
        return true
    }

    fun handleModal(event: ModalInteractionEvent, config: DiscordBridgeConfig): Boolean {
        val id = event.modalId
        if (!id.startsWith("hitos:")) return false

        if (!canManageHitos(event.member, config)) {
            event.reply(permissionError(config)).setEphemeral(true).queue()
            return true
        }

        val parts = id.split(":", limit = 3)
        val action = parts.getOrNull(1) ?: return true
        val payload = parts.getOrNull(2) ?: ""

        when (action) {
            "modal_day" -> {
                val raw = event.getValue("numero")?.asString?.trim() ?: ""
                val num = raw.toIntOrNull()
                if (num == null || num < 0) {
                    event.reply("Ingresa un número entero mayor o igual a 0.").setEphemeral(true).queue()
                    return true
                }
                val effective = HitosDiscordHelper.setCurrentDay(num)
                val notice = if (num == 0) "Modo automático activado (**Día $effective**)." else "Día actual fijado en **Día $effective**."
                val embed = HitosDiscordHelper.buildEmbed(config, showAdminInfo = true, statusNotice = notice)
                event.replyEmbeds(embed).setComponents(buildPanelRows()).setEphemeral(true).queue()
            }
            "modal_add_hito" -> {
                val title = event.getValue("titulo")?.asString?.trim().orEmpty()
                val dia = event.getValue("dia")?.asString?.trim()?.toIntOrNull()
                val sub = event.getValue("subtitulo")?.asString?.trim().orEmpty()
                val reward = event.getValue("recompensa")?.asString?.trim().orEmpty()
                if (title.isBlank() || dia == null || dia < 1) {
                    event.reply("Debes indicar un nombre válido y un día mayor o igual a 1.").setEphemeral(true).queue()
                    return true
                }
                val savedId = HitosDiscordHelper.addOrUpdateMilestone(
                    id = null,
                    dia = dia,
                    icon = null,
                    title = title,
                    sub = sub,
                    rewardText = reward,
                    rewardDollars = 0L,
                    progression = true
                )
                val entry = HitosDiscordHelper.getMilestone(savedId)
                if (entry != null) {
                    event.replyEmbeds(HitosDiscordHelper.buildSingleHitoEmbed(entry))
                        .setComponents(buildHitoEditorRows(entry))
                        .setEphemeral(true)
                        .queue()
                } else {
                    val embed = HitosDiscordHelper.buildEmbed(config, showAdminInfo = true, statusNotice = "Hito **$title** guardado.")
                    event.replyEmbeds(embed).setComponents(buildPanelRows()).setEphemeral(true).queue()
                }
            }
            "modal_add_event" -> {
                val title = event.getValue("titulo")?.asString?.trim().orEmpty()
                val dia = event.getValue("dia")?.asString?.trim()?.toIntOrNull()
                val desc = event.getValue("descripcion")?.asString?.trim().orEmpty()
                if (title.isBlank() || dia == null || dia < 1) {
                    event.reply("Debes indicar un nombre válido y un día mayor o igual a 1.").setEphemeral(true).queue()
                    return true
                }
                val savedId = HitosDiscordHelper.addOrUpdateMilestone(
                    id = null,
                    dia = dia,
                    icon = null,
                    title = title,
                    sub = desc,
                    rewardText = "",
                    rewardDollars = 0L,
                    progression = false
                )
                val entry = HitosDiscordHelper.getMilestone(savedId)
                if (entry != null) {
                    event.replyEmbeds(HitosDiscordHelper.buildSingleHitoEmbed(entry))
                        .setComponents(buildHitoEditorRows(entry))
                        .setEphemeral(true)
                        .queue()
                } else {
                    val embed = HitosDiscordHelper.buildEmbed(config, showAdminInfo = true, statusNotice = "Evento **$title** guardado.")
                    event.replyEmbeds(embed).setComponents(buildPanelRows()).setEphemeral(true).queue()
                }
            }
            "modal_edit_info" -> {
                val title = event.getValue("titulo")?.asString?.trim().orEmpty()
                val dia = event.getValue("dia")?.asString?.trim()?.toIntOrNull()
                val sub = event.getValue("subtitulo")?.asString?.trim().orEmpty()
                val dur = event.getValue("duracion")?.asString?.trim()?.toIntOrNull() ?: 2
                if (title.isBlank() || dia == null || dia < 1 || dur < 1) {
                    event.reply("Revisa los campos: el día y la duración deben ser mayores o iguales a 1.").setEphemeral(true).queue()
                    return true
                }
                val ok = HitosDiscordHelper.updateMilestoneInfo(payload, title, dia, sub, dur)
                val updated = HitosDiscordHelper.getMilestone(payload)
                if (ok && updated != null) {
                    event.replyEmbeds(HitosDiscordHelper.buildSingleHitoEmbed(updated))
                        .setComponents(buildHitoEditorRows(updated))
                        .setEphemeral(true)
                        .queue()
                } else {
                    event.reply("No se pudo actualizar el hito `$payload`.").setEphemeral(true).queue()
                }
            }
            "modal_edit_reward" -> {
                val dollars = event.getValue("dinero")?.asString?.trim()?.toLongOrNull()
                val desc = event.getValue("descripcion")?.asString?.trim().orEmpty()
                if (dollars == null || dollars < 0L) {
                    event.reply("El dinero debe ser un número entero mayor o igual a 0.").setEphemeral(true).queue()
                    return true
                }
                val ok = HitosDiscordHelper.setMilestoneReward(payload, dollars, desc)
                val updated = HitosDiscordHelper.getMilestone(payload)
                if (ok && updated != null) {
                    event.replyEmbeds(HitosDiscordHelper.buildSingleHitoEmbed(updated))
                        .setComponents(buildHitoEditorRows(updated))
                        .setEphemeral(true)
                        .queue()
                } else {
                    event.reply("No se pudo actualizar la recompensa de `$payload`.").setEphemeral(true).queue()
                }
            }
        }
        return true
    }
}
