package com.desgraciamon.badges

data class GymBadgeDef(
    val id: String,
    val name: String,
    val gymLeaderId: String,
    val gymLeaderName: String,
    val region: String,
    val itemIdentifier: String,
    val icon: String,
    val description: String
)

object GymBadges {
    val ALL_GYM_BADGES: List<GymBadgeDef> = listOf(
        // Kanto
        GymBadgeDef(
            id = "kanto_boulder_badge",
            name = "Medalla Roca",
            gymLeaderId = "kanto_brock",
            gymLeaderName = "Brock",
            region = "Kanto",
            itemIdentifier = "cobbleversebadges:kanto_boulder_badge",
            icon = "<color=#8B8B83>[M]</color>",
            description = "Otorgada al derrotar a Brock en el Gimnasio de Ciudad Plateada."
        ),
        GymBadgeDef(
            id = "kanto_cascade_badge",
            name = "Medalla Cascada",
            gymLeaderId = "kanto_misty",
            gymLeaderName = "Misty",
            region = "Kanto",
            itemIdentifier = "cobbleversebadges:kanto_cascade_badge",
            icon = "<color=#00BFFF>[M]</color>",
            description = "Otorgada al derrotar a Misty en el Gimnasio de Ciudad Celeste."
        ),
        GymBadgeDef(
            id = "kanto_thunder_badge",
            name = "Medalla Trueno",
            gymLeaderId = "kanto_ltsurge",
            gymLeaderName = "Lt. Surge",
            region = "Kanto",
            itemIdentifier = "cobbleversebadges:kanto_thunder_badge",
            icon = "<color=#FFD700>[M]</color>",
            description = "Otorgada al derrotar a Lt. Surge en el Gimnasio de Ciudad Carmín."
        ),
        GymBadgeDef(
            id = "kanto_rainbow_badge",
            name = "Medalla Arcoíris",
            gymLeaderId = "kanto_erika",
            gymLeaderName = "Erika",
            region = "Kanto",
            itemIdentifier = "cobbleversebadges:kanto_rainbow_badge",
            icon = "<prism=#FF0000,#00FF00,#0000FF>[M]</prism>",
            description = "Otorgada al derrotar a Erika en el Gimnasio de Ciudad Azulona."
        ),
        GymBadgeDef(
            id = "kanto_soul_badge",
            name = "Medalla Alma",
            gymLeaderId = "kanto_koga",
            gymLeaderName = "Koga",
            region = "Kanto",
            itemIdentifier = "cobbleversebadges:kanto_soul_badge",
            icon = "<color=#AA00AA>[M]</color>",
            description = "Otorgada al derrotar a Koga en el Gimnasio de Ciudad Fucsia."
        ),
        GymBadgeDef(
            id = "kanto_marsh_badge",
            name = "Medalla Pantano",
            gymLeaderId = "kanto_sabrina",
            gymLeaderName = "Sabrina",
            region = "Kanto",
            itemIdentifier = "cobbleversebadges:kanto_marsh_badge",
            icon = "<color=#FFD700>[M]</color>",
            description = "Otorgada al derrotar a Sabrina en el Gimnasio de Ciudad Azafrán."
        ),
        GymBadgeDef(
            id = "kanto_volcano_badge",
            name = "Medalla Volcán",
            gymLeaderId = "kanto_blaine",
            gymLeaderName = "Blaine",
            region = "Kanto",
            itemIdentifier = "cobbleversebadges:kanto_volcano_badge",
            icon = "<color=#FF4500>[M]</color>",
            description = "Otorgada al derrotar a Blaine en el Gimnasio de Isla Canela."
        ),
        GymBadgeDef(
            id = "kanto_earth_badge",
            name = "Medalla Tierra",
            gymLeaderId = "kanto_giovanni",
            gymLeaderName = "Giovanni",
            region = "Kanto",
            itemIdentifier = "cobbleversebadges:kanto_earth_badge",
            icon = "<color=#8B4513>[M]</color>",
            description = "Otorgada al derrotar a Giovanni en el Gimnasio de Ciudad Verde."
        ),
        GymBadgeDef(
            id = "kanto_league_trophy",
            name = "Trofeo Liga Kanto",
            gymLeaderId = "kanto_champion_blue",
            gymLeaderName = "Azul",
            region = "Kanto",
            itemIdentifier = "cobbleversebadges:kanto_league_trophy",
            icon = "<prism=#FFD700,#FFA500>[Liga]</prism>",
            description = "Otorgado al derrotar al Campeón Azul en la Liga Pokémon de Kanto."
        ),

        // Johto
        GymBadgeDef(
            id = "johto_zephyr_badge",
            name = "Medalla Céfiro",
            gymLeaderId = "johto_valerio",
            gymLeaderName = "Pegaso",
            region = "Johto",
            itemIdentifier = "cobbleversebadges:johto_zephyr_badge",
            icon = "<color=#87CEEB>[M]</color>",
            description = "Otorgada al derrotar a Pegaso en el Gimnasio de Ciudad Malva."
        ),
        GymBadgeDef(
            id = "johto_hive_badge",
            name = "Medalla Colmena",
            gymLeaderId = "johto_raffaello",
            gymLeaderName = "Antón",
            region = "Johto",
            itemIdentifier = "cobbleversebadges:johto_hive_badge",
            icon = "<color=#A8B820>[M]</color>",
            description = "Otorgada al derrotar a Antón en el Gimnasio de Pueblo Azalea."
        ),
        GymBadgeDef(
            id = "johto_plain_badge",
            name = "Medalla Planicie",
            gymLeaderId = "johto_chiara",
            gymLeaderName = "Blanca",
            region = "Johto",
            itemIdentifier = "cobbleversebadges:johto_plain_badge",
            icon = "<color=#FFA07A>[M]</color>",
            description = "Otorgada al derrotar a Blanca en el Gimnasio de Ciudad Trigal."
        ),
        GymBadgeDef(
            id = "johto_fog_badge",
            name = "Medalla Niebla",
            gymLeaderId = "johto_angelo",
            gymLeaderName = "Morti",
            region = "Johto",
            itemIdentifier = "cobbleversebadges:johto_fog_badge",
            icon = "<color=#705898>[M]</color>",
            description = "Otorgada al derrotar a Morti en el Gimnasio de Ciudad Iris."
        ),
        GymBadgeDef(
            id = "johto_storm_badge",
            name = "Medalla Tormenta",
            gymLeaderId = "johto_furio",
            gymLeaderName = "Aníbal",
            region = "Johto",
            itemIdentifier = "cobbleversebadges:johto_storm_badge",
            icon = "<color=#C03028>[M]</color>",
            description = "Otorgada al derrotar a Aníbal en el Gimnasio de Ciudad Orquídea."
        ),
        GymBadgeDef(
            id = "johto_mineral_badge",
            name = "Medalla Mineral",
            gymLeaderId = "johto_jasmine",
            gymLeaderName = "Yasmina",
            region = "Johto",
            itemIdentifier = "cobbleversebadges:johto_mineral_badge",
            icon = "<color=#B8B8D0>[M]</color>",
            description = "Otorgada al derrotar a Yasmina en el Gimnasio de Ciudad Olivo."
        ),
        GymBadgeDef(
            id = "johto_glacier_badge",
            name = "Medalla Glaciar",
            gymLeaderId = "johto_alfredo",
            gymLeaderName = "Fredo",
            region = "Johto",
            itemIdentifier = "cobbleversebadges:johto_glacier_badge",
            icon = "<color=#98D8D8>[M]</color>",
            description = "Otorgada al derrotar a Fredo en el Gimnasio de Pueblo Caoba."
        ),
        GymBadgeDef(
            id = "johto_rising_badge",
            name = "Medalla Dragón",
            gymLeaderId = "johto_sandra",
            gymLeaderName = "Débora",
            region = "Johto",
            itemIdentifier = "cobbleversebadges:johto_rising_badge",
            icon = "<color=#7038F8>[M]</color>",
            description = "Otorgada al derrotar a Débora en el Gimnasio de Ciudad Endrino."
        ),
        GymBadgeDef(
            id = "johto_league_trophy",
            name = "Trofeo Liga Johto",
            gymLeaderId = "johto_champion_lance",
            gymLeaderName = "Lance",
            region = "Johto",
            itemIdentifier = "cobbleversebadges:johto_league_trophy",
            icon = "<prism=#E69A0B,#FF3CAC>[Liga]</prism>",
            description = "Otorgado al derrotar al Campeón Lance en la Liga Pokémon de Johto."
        ),

        // Hoenn
        GymBadgeDef(
            id = "hoenn_stone_badge",
            name = "Medalla Piedra",
            gymLeaderId = "hoenn_petra",
            gymLeaderName = "Petra",
            region = "Hoenn",
            itemIdentifier = "cobbleversebadges:hoenn_stone_badge",
            icon = "<color=#B8A038>[M]</color>",
            description = "Otorgada al derrotar a Petra en el Gimnasio de Ciudad Férrica."
        ),
        GymBadgeDef(
            id = "hoenn_knuckle_badge",
            name = "Medalla Puño",
            gymLeaderId = "hoenn_rudi",
            gymLeaderName = "Marcial",
            region = "Hoenn",
            itemIdentifier = "cobbleversebadges:hoenn_knuckle_badge",
            icon = "<color=#C03028>[M]</color>",
            description = "Otorgada al derrotar a Marcial en el Gimnasio de Pueblo Azuliza."
        ),
        GymBadgeDef(
            id = "hoenn_dynamo_badge",
            name = "Medalla Dinamo",
            gymLeaderId = "hoenn_walter",
            gymLeaderName = "Erico",
            region = "Hoenn",
            itemIdentifier = "cobbleversebadges:hoenn_dynamo_badge",
            icon = "<color=#F8D030>[M]</color>",
            description = "Otorgada al derrotar a Erico en el Gimnasio de Ciudad Malvalona."
        ),
        GymBadgeDef(
            id = "hoenn_heat_badge",
            name = "Medalla Calor",
            gymLeaderId = "hoenn_fiammetta",
            gymLeaderName = "Candela",
            region = "Hoenn",
            itemIdentifier = "cobbleversebadges:hoenn_heat_badge",
            icon = "<color=#F08030>[M]</color>",
            description = "Otorgada al derrotar a Candela en el Gimnasio de Pueblo Lavacalda."
        ),
        GymBadgeDef(
            id = "hoenn_balance_badge",
            name = "Medalla Equilibrio",
            gymLeaderId = "hoenn_norman",
            gymLeaderName = "Norman",
            region = "Hoenn",
            itemIdentifier = "cobbleversebadges:hoenn_balance_badge",
            icon = "<color=#A8A878>[M]</color>",
            description = "Otorgada al derrotar a Norman en el Gimnasio de Ciudad Petalia."
        ),
        GymBadgeDef(
            id = "hoenn_feather_badge",
            name = "Medalla Pluma",
            gymLeaderId = "hoenn_alice",
            gymLeaderName = "Alana",
            region = "Hoenn",
            itemIdentifier = "cobbleversebadges:hoenn_feather_badge",
            icon = "<color=#A890F0>[M]</color>",
            description = "Otorgada al derrotar a Alana en el Gimnasio de Ciudad Arborada."
        ),
        GymBadgeDef(
            id = "hoenn_mind_badge",
            name = "Medalla Mente",
            gymLeaderId = "hoenn_tell",
            gymLeaderName = "Vito y Leti",
            region = "Hoenn",
            itemIdentifier = "cobbleversebadges:hoenn_mind_badge",
            icon = "<color=#F85888>[M]</color>",
            description = "Otorgada al derrotar a Vito y Leti en el Gimnasio de Ciudad Algaria."
        ),
        GymBadgeDef(
            id = "hoenn_rain_badge",
            name = "Medalla Lluvia",
            gymLeaderId = "hoenn_adriano",
            gymLeaderName = "Plubio",
            region = "Hoenn",
            itemIdentifier = "cobbleversebadges:hoenn_rain_badge",
            icon = "<color=#6890F0>[M]</color>",
            description = "Otorgada al derrotar a Plubio en el Gimnasio de Arrecípolis."
        ),
        GymBadgeDef(
            id = "hoenn_league_trophy",
            name = "Trofeo Liga Hoenn",
            gymLeaderId = "hoenn_champion_rocco",
            gymLeaderName = "Máximo",
            region = "Hoenn",
            itemIdentifier = "cobbleversebadges:hoenn_league_trophy",
            icon = "<prism=#00F2FE,#4FACFE>[Liga]</prism>",
            description = "Otorgado al derrotar al Campeón Máximo en la Liga Pokémon de Hoenn."
        ),

        // Sinnoh
        GymBadgeDef(
            id = "sinnoh_coal_badge",
            name = "Medalla Lignito",
            gymLeaderId = "sinnoh_pedro",
            gymLeaderName = "Roco",
            region = "Sinnoh",
            itemIdentifier = "cobbleversebadges:sinnoh_coal_badge",
            icon = "<color=#705848>[M]</color>",
            description = "Otorgada al derrotar a Roco en el Gimnasio de Ciudad Pirita."
        ),
        GymBadgeDef(
            id = "sinnoh_forest_badge",
            name = "Medalla Bosque",
            gymLeaderId = "sinnoh_gardenia",
            gymLeaderName = "Gardenia",
            region = "Sinnoh",
            itemIdentifier = "cobbleversebadges:sinnoh_forest_badge",
            icon = "<color=#78C850>[M]</color>",
            description = "Otorgada al derrotar a Gardenia en el Gimnasio de Ciudad Vetusta."
        ),
        GymBadgeDef(
            id = "sinnoh_cobble_badge",
            name = "Medalla Adoquín",
            gymLeaderId = "sinnoh_marzia",
            gymLeaderName = "Brega",
            region = "Sinnoh",
            itemIdentifier = "cobbleversebadges:sinnoh_cobble_badge",
            icon = "<color=#C03028>[M]</color>",
            description = "Otorgada al derrotar a Brega en el Gimnasio de Ciudad Rocavelo."
        ),
        GymBadgeDef(
            id = "sinnoh_fen_badge",
            name = "Medalla Ciénaga",
            gymLeaderId = "sinnoh_omar",
            gymLeaderName = "Mananti",
            region = "Sinnoh",
            itemIdentifier = "cobbleversebadges:sinnoh_fen_badge",
            icon = "<color=#6890F0>[M]</color>",
            description = "Otorgada al derrotar a Mananti en el Gimnasio de Ciudad Pradera."
        ),
        GymBadgeDef(
            id = "sinnoh_relic_badge",
            name = "Medalla Reliquia",
            gymLeaderId = "sinnoh_fannie",
            gymLeaderName = "Fantina",
            region = "Sinnoh",
            itemIdentifier = "cobbleversebadges:sinnoh_relic_badge",
            icon = "<color=#705898>[M]</color>",
            description = "Otorgada al derrotar a Fantina en el Gimnasio de Ciudad Corazón."
        ),
        GymBadgeDef(
            id = "sinnoh_mine_badge",
            name = "Medalla Mina",
            gymLeaderId = "sinnoh_ferruccio",
            gymLeaderName = "Acerón",
            region = "Sinnoh",
            itemIdentifier = "cobbleversebadges:sinnoh_mine_badge",
            icon = "<color=#B8B8D0>[M]</color>",
            description = "Otorgada al derrotar a Acerón en el Gimnasio de Ciudad Canal."
        ),
        GymBadgeDef(
            id = "sinnoh_icicle_badge",
            name = "Medalla Carámbano",
            gymLeaderId = "sinnoh_bianca",
            gymLeaderName = "Inverna",
            region = "Sinnoh",
            itemIdentifier = "cobbleversebadges:sinnoh_icicle_badge",
            icon = "<color=#98D8D8>[M]</color>",
            description = "Otorgada al derrotar a Inverna en el Gimnasio de Ciudad Puntaneva."
        ),
        GymBadgeDef(
            id = "sinnoh_beacon_badge",
            name = "Medalla Faro",
            gymLeaderId = "sinnoh_corrado",
            gymLeaderName = "Lectro",
            region = "Sinnoh",
            itemIdentifier = "cobbleversebadges:sinnoh_beacon_badge",
            icon = "<color=#F8D030>[M]</color>",
            description = "Otorgada al derrotar a Lectro en el Gimnasio de Ciudad Marina."
        ),
        GymBadgeDef(
            id = "sinnoh_league_trophy",
            name = "Trofeo Liga Sinnoh",
            gymLeaderId = "sinnoh_champion_camilla",
            gymLeaderName = "Cintia",
            region = "Sinnoh",
            itemIdentifier = "cobbleversebadges:sinnoh_league_trophy",
            icon = "<prism=#FF3CAC,#784BA0>[Liga]</prism>",
            description = "Otorgado al derrotar a la Campeona Cintia en la Liga Pokémon de Sinnoh."
        )
    )

    val BY_BADGE_ID: Map<String, GymBadgeDef> = ALL_GYM_BADGES.associateBy { it.id.lowercase() }
    val BY_GYM_LEADER_ID: Map<String, GymBadgeDef> = ALL_GYM_BADGES.associateBy { it.gymLeaderId.lowercase() }
    val BY_ITEM_ID: Map<String, GymBadgeDef> = ALL_GYM_BADGES.associateBy { it.itemIdentifier.lowercase() }

    fun findByLeader(trainerId: String): GymBadgeDef? {
        val clean = trainerId.lowercase().trim()
        BY_GYM_LEADER_ID[clean]?.let { return it }
        val normalized = clean.substringAfterLast('/').substringBefore('.')
        if (normalized == "hoenn_pat") return BY_BADGE_ID["hoenn_mind_badge"]
        return BY_GYM_LEADER_ID[normalized]
    }

    fun badgesForRegion(region: String): List<GymBadgeDef> =
        ALL_GYM_BADGES.filter { it.region.equals(region, ignoreCase = true) }

    fun findByItem(itemId: String): GymBadgeDef? {
        val clean = itemId.lowercase().trim()
        BY_ITEM_ID[clean]?.let { return it }
        val idOnly = clean.substringAfter(':')
        return BY_BADGE_ID[idOnly]
    }
}

