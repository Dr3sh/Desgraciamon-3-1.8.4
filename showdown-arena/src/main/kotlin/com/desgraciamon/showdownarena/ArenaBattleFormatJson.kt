package com.desgraciamon.showdownarena

import com.cobblemon.mod.common.battles.BattleFormat
import com.cobblemon.mod.common.battles.BattleRules
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

object ArenaBattleFormatJson {
    private val gson = Gson()

    @JvmStatic
    fun forBattle(format: BattleFormat): String? {
        val rulesToSend = format.ruleSet.filter {
            it != BattleRules.BAG_CLAUSE && !it.startsWith("showdownarena:")
        }
        val rulesArray = JsonArray().apply {
            if (rulesToSend.isEmpty()) {
                add("Obtainable")
                add("Past")
                add("Unobtainable")
            } else {
                rulesToSend.forEach(::add)
            }
        }
        val payload = JsonObject().apply {
            addProperty("mod", format.mod)
            addProperty("gameType", format.battleType.name.lowercase())
            addProperty("gen", format.gen)
            add("ruleset", rulesArray)
            addProperty("effectType", "Format")
        }
        return gson.toJson(payload)
    }
}
