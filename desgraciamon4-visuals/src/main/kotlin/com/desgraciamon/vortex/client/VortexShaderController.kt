package com.desgraciamon.vortex.client

import com.desgraciamon.vortex.DesgraciamonMod
import com.desgraciamon.vortex.mixin.client.GameRendererAccessor
import com.desgraciamon.vortex.mixin.client.PostChainAccessor
import com.desgraciamon.vortex.network.VortexSyncPacket
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import org.joml.Vector3f
import org.slf4j.LoggerFactory
import java.util.Locale

object VortexShaderController {
    private val LOGGER = LoggerFactory.getLogger("Desgraciamon-Shader")

    var currentEffectType: String = "startrails"
        private set

    fun isLegendary(): Boolean {
        return when (currentEffectType.lowercase(Locale.ROOT)) {
            "dialga", "palkia", "giratina", "yveltal", "xerneas", "rayquaza", "kyogre", "groudon", "zygarde",
            "rayquaza2", "kyogre2", "groudon2", "solgaleo", "lunala",
            "palkiaold", "rayquazaold", "xerneasold", "yveltalold",
            "goosebumps", "travis", "astronomical" -> true
            else -> false
        }
    }

    fun getPostShaderForEffect(): ResourceLocation {
        val path = when (currentEffectType.lowercase(Locale.ROOT)) {
            "vortex" -> "shaders/post/vortex.json"
            "equilibrio" -> "shaders/post/equilibrio.json"
            "dialga" -> "shaders/post/dialga.json"
            "palkia" -> "shaders/post/palkia.json"
            "giratina" -> "shaders/post/giratina.json"
            "yveltal" -> "shaders/post/yveltal.json"
            "xerneas" -> "shaders/post/xerneas.json"
            "rayquaza" -> "shaders/post/rayquaza.json"
            "kyogre" -> "shaders/post/kyogre.json"
            "groudon" -> "shaders/post/groudon.json"
            "rayquaza2" -> "shaders/post/rayquaza2.json"
            "kyogre2" -> "shaders/post/kyogre2.json"
            "groudon2" -> "shaders/post/groudon2.json"
            "solgaleo" -> "shaders/post/solgaleo.json"
            "lunala" -> "shaders/post/lunala.json"
            "zygarde" -> "shaders/post/zygarde.json"
            "palkiaold" -> "shaders/post/palkiaold.json"
            "rayquazaold" -> "shaders/post/rayquazaold.json"
            "xerneasold" -> "shaders/post/xerneasold.json"
            "yveltalold" -> "shaders/post/yveltalold.json"
            "goosebumps", "travis", "astronomical" -> "shaders/post/goosebumps.json"
            else -> "shaders/post/startrails.json"
        }
        return ResourceLocation.fromNamespaceAndPath(DesgraciamonMod.MOD_ID, path)
    }

    var isEventActiveClient: Boolean = false
        private set

    var vortexCenter: BlockPos = BlockPos.ZERO
        private set

    var isNoFlyClient: Boolean = false
        private set

    var remainingSecondsClient: Int = 0
        private set

    var totalSecondsClient: Int = 600
        private set

    var fadeDurationSeconds: Float = 12.0f
        private set

    var currentIntensity: Float = 0.0f
        private set

    var currentCameraShake: Float = 0.0f
        private set

    var eventElapsedTicks: Long = 0L
        private set

    private var prevIntensity: Float = 0.0f
    private var clientTicks: Long = 0L
    private var localSubSecondTicks: Int = 0
    private var postShaderFailed: Boolean = false

    /**
     * Recibe el VortexSyncPacket desde el servidor (Módulo 1.3, 2.3 & 6.2).
     */
    fun onSyncPacket(packet: VortexSyncPacket) {
        val wasActive = isEventActiveClient
        isEventActiveClient = packet.isActive
        vortexCenter = packet.center
        isNoFlyClient = packet.noFly
        remainingSecondsClient = packet.remainingSeconds
        totalSecondsClient = packet.totalSeconds
        fadeDurationSeconds = packet.fadeSeconds
        currentEffectType = packet.effectType
        currentCameraShake = packet.cameraShake
        localSubSecondTicks = 0

        // Si se recibe un forcestop (fadeSeconds <= 0.05f y no activo), cortar inmediatamente a 0
        if (!isEventActiveClient && packet.fadeSeconds <= 0.05f) {
            currentIntensity = 0.0f
            prevIntensity = 0.0f
            eventElapsedTicks = 0L
            val mc = Minecraft.getInstance()
            val accessor = mc.gameRenderer as? GameRendererAccessor
            val activeChain = accessor?.`desgraciamon$getPostEffect`()
            if (activeChain != null && (activeChain.name == getPostShaderForEffect().toString() || activeChain.name.contains("vortex") || activeChain.name.contains("special_effects"))) {
                mc.gameRenderer.shutdownEffect()
            }
            dialgaSoundInstance?.let { sound -> mc.soundManager.stop(sound) }
            dialgaSoundInstance = null
            palkiaSoundInstance?.let { sound -> mc.soundManager.stop(sound) }
            palkiaSoundInstance = null
            return
        }

        if (isEventActiveClient) {
            val elapsed = (packet.totalSeconds - packet.remainingSeconds).coerceAtLeast(0)
            if (!wasActive) {
                eventElapsedTicks = elapsed * 20L
            }
            postShaderFailed = false
            val mc = Minecraft.getInstance()
            val accessor = mc.gameRenderer as? GameRendererAccessor ?: return
            val activeChain = accessor.`desgraciamon$getPostEffect`()
            val targetShader = getPostShaderForEffect()
            if (activeChain == null || activeChain.name != targetShader.toString()) {
                try {
                    accessor.`desgraciamon$loadEffect`(targetShader)
                } catch (e: Exception) {
                    postShaderFailed = true
                    LOGGER.warn("Could not load post-processing color grade shader $targetShader", e)
                }
            }
        } else {
            eventElapsedTicks = 0L
        }
    }

    /**
     * Llamado en cada tick del cliente (20 veces por segundo).
     * Maneja la interpolación suave de Intensity (0.0 -> 1.0 en 12s al iniciar, 1.0 -> 0.0 al finalizar)
     * tanto para el Skybox GLSL como para el filtro de cámara post-procesado morado/desaturado.
     */
    fun onClientTick(client: Minecraft) {
        if (client.level == null) {
            resetClientState(client)
            return
        }

        clientTicks++
        if (isEventActiveClient) {
            eventElapsedTicks++
        }
        prevIntensity = currentIntensity

        // Actualizar el reloj cada 20 ticks en el cliente entre sincronizaciones del servidor
        if (isEventActiveClient && remainingSecondsClient > 0) {
            localSubSecondTicks++
            if (localSubSecondTicks >= 20) {
                localSubSecondTicks = 0
                remainingSecondsClient = (remainingSecondsClient - 1).coerceAtLeast(0)
            }
        }

        if (!isEventActiveClient && fadeDurationSeconds <= 0.05f) {
            currentIntensity = 0.0f
            prevIntensity = 0.0f
            val accessor = client.gameRenderer as? GameRendererAccessor
            val activeChain = accessor?.`desgraciamon$getPostEffect`()
            if (activeChain != null && (activeChain.name == getPostShaderForEffect().toString() || activeChain.name.contains("vortex") || activeChain.name.contains("special_effects"))) {
                client.gameRenderer.shutdownEffect()
            }
            return
        }

        // Paso de intensidad por tick: 1.0 / (fadeDurationSeconds * 20 ticks)
        val effectiveFadeSeconds = when (currentEffectType.lowercase(Locale.ROOT)) {
            "solgaleo", "lunala" -> 10.0f
            "xerneas", "yveltal", "palkia", "zygarde", "rayquaza", "kyogre", "groudon",
            "rayquaza2", "kyogre2", "groudon2",
            "palkiaold", "rayquazaold", "xerneasold", "yveltalold" -> 15.0f
            "dialga", "goosebumps", "travis", "astronomical" -> 12.0f
            else -> fadeDurationSeconds.coerceAtLeast(0.5f)
        }
        val stepPerTick = 1.0f / (effectiveFadeSeconds * 20.0f)

        if (isEventActiveClient) {
            currentIntensity = (currentIntensity + stepPerTick).coerceAtMost(1.0f)
        } else if (currentIntensity > 0.0f) {
            currentIntensity = (currentIntensity - stepPerTick).coerceAtLeast(0.0f)
            if (currentIntensity <= 0.0001f) {
                currentIntensity = 0.0f
                prevIntensity = 0.0f
                val accessor = client.gameRenderer as? GameRendererAccessor
                val activeChain = accessor?.`desgraciamon$getPostEffect`()
                if (activeChain != null && (activeChain.name == getPostShaderForEffect().toString() || activeChain.name.contains("vortex") || activeChain.name.contains("special_effects"))) {
                    client.gameRenderer.shutdownEffect()
                }
            }
        }

        // Xerneas: Cronología sonora de 15s y partículas de vida eterna / polen arcoíris
        val isXerneas = isEventActiveClient && currentEffectType.lowercase(Locale.ROOT) == "xerneas" && currentIntensity > 0.005f
        if (isXerneas) {
            val player = client.player
            val level = client.level
            if (player != null && level != null) {
                // 1. Cronología de audio de 15 segundos
                // 0:00 - 0:03: Carillón cristalino resonante
                if (eventElapsedTicks in listOf(10L, 26L, 42L)) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.AMETHYST_BLOCK_CHIME, net.minecraft.sounds.SoundSource.AMBIENT, 1.2f, 0.95f + (eventElapsedTicks / 42.0f) * 0.45f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.AMETHYST_BLOCK_RESONATE, net.minecraft.sounds.SoundSource.AMBIENT, 0.8f, 1.2f, false)
                }
                // 0:06 - 0:09: Onda expansiva de luz feérica iridiscente
                if (eventElapsedTicks == 136L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.BEACON_ACTIVATE, net.minecraft.sounds.SoundSource.AMBIENT, 1.2f, 1.35f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.PLAYER_LEVELUP, net.minecraft.sounds.SoundSource.AMBIENT, 0.9f, 1.25f, false)
                }
                // 0:12 - 0:15: Llamado majestuoso del ciervo de la vida (Stag Cry)
                if (eventElapsedTicks == 250L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.GOAT_SCREAMING_HORN_BREAK, net.minecraft.sounds.SoundSource.AMBIENT, 1.2f, 1.15f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.BEACON_POWER_SELECT, net.minecraft.sounds.SoundSource.AMBIENT, 1.1f, 1.4f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.AMETHYST_CLUSTER_STEP, net.minecraft.sounds.SoundSource.AMBIENT, 1.0f, 0.7f, false)
                }
                // Pasados los 15s: pulso armónico de vida cada 2.5s
                if (eventElapsedTicks > 300L && eventElapsedTicks % 50L == 0L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.AMETHYST_BLOCK_CHIME, net.minecraft.sounds.SoundSource.AMBIENT, 0.75f, 1.4f, false)
                }

                // 2. Partículas: Orbes de fuerza vital ascendentes y polen arcoíris centelleante
                val random = level.random
                val spawnCount = if (eventElapsedTicks > 180L) 3 else 1
                for (i in 0 until spawnCount) {
                    val offsetX = (random.nextFloat() - 0.5) * 26.0
                    val offsetZ = (random.nextFloat() - 0.5) * 26.0
                    val px = player.x + offsetX
                    val pz = player.z + offsetZ
                    val py = player.y - 0.5 + random.nextFloat() * 2.5

                    // Orbes ascendentes de luz blanca/dorada
                    level.addParticle(ParticleTypes.END_ROD, px, py, pz, 0.0, 0.08 + random.nextFloat() * 0.12, 0.0)

                    // Polen arcoíris de Geomancy (tonos pastel cíclicos)
                    val colorPick = random.nextInt(3)
                    val particleColor = when (colorPick) {
                        0 -> Vector3f(0.35f, 0.88f, 0.95f) // Cian pastel
                        1 -> Vector3f(1.00f, 0.88f, 0.40f) // Oro feérico
                        else -> Vector3f(0.95f, 0.45f, 0.85f) // Magenta pastel
                    }
                    level.addParticle(
                        DustParticleOptions(particleColor, 1.15f),
                        px, py, pz,
                        (random.nextFloat() - 0.5) * 0.04,
                        0.06 + random.nextFloat() * 0.08,
                        (random.nextFloat() - 0.5) * 0.04
                    )
                }

                // Destellos bioluminiscentes sobre el follaje (Wax on / Glow)
                if (eventElapsedTicks % 3L == 0L) {
                    val fx = player.x + (random.nextFloat() - 0.5) * 22.0
                    val fz = player.z + (random.nextFloat() - 0.5) * 22.0
                    val fy = player.y + random.nextFloat() * 1.5
                    level.addParticle(ParticleTypes.WAX_ON, fx, fy, fz, 0.0, 0.04, 0.0)
                    level.addParticle(ParticleTypes.GLOW, fx, fy, fz, 0.0, 0.03, 0.0)
                }
            }
        }

        // Yveltal: Cronología sonora de 15s y partículas de física de gravedad inversa
        val isYveltal = isEventActiveClient && currentEffectType.lowercase(Locale.ROOT) == "yveltal" && currentIntensity > 0.005f
        if (isYveltal) {
            val player = client.player
            val level = client.level
            if (player != null && level != null) {
                // 1. Cronología de audio de 15 segundos
                // 0:00 - 0:03: Latidos cardíacos viscerales en el vacío
                if (eventElapsedTicks in listOf(10L, 26L, 42L, 58L)) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.WARDEN_HEARTBEAT, net.minecraft.sounds.SoundSource.AMBIENT, 1.0f, 0.65f, false)
                }
                // 0:06 - 0:09: Onda de choque del sifón al colapsar el Y en agujero negro
                if (eventElapsedTicks == 136L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.WARDEN_SONIC_BOOM, net.minecraft.sounds.SoundSource.AMBIENT, 1.1f, 0.60f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.WITHER_SPAWN, net.minecraft.sounds.SoundSource.AMBIENT, 0.9f, 0.55f, false)
                }
                // 0:12 - 0:15: Chillido metálico desgarrador de Yveltal y trueno carmesí
                if (eventElapsedTicks == 250L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.WARDEN_ROAR, net.minecraft.sounds.SoundSource.AMBIENT, 1.2f, 1.35f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.ENDER_DRAGON_GROWL, net.minecraft.sounds.SoundSource.AMBIENT, 1.0f, 1.45f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.LIGHTNING_BOLT_THUNDER, net.minecraft.sounds.SoundSource.AMBIENT, 1.0f, 0.60f, false)
                }
                // Pasados los 15s: latido continuo del eclipse cada 2.5s sincronizado con la corona
                if (eventElapsedTicks > 300L && eventElapsedTicks % 50L == 0L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.WARDEN_HEARTBEAT, net.minecraft.sounds.SoundSource.AMBIENT, 0.85f, 0.70f, false)
                }

                // 2. Partículas con física de gravedad inversa (orbes de alma carmesí y púrpura tirados hacia el eclipse)
                val random = level.random
                val spawnCount = if (eventElapsedTicks > 180L) 3 else 1
                for (i in 0 until spawnCount) {
                    val offsetX = (random.nextFloat() - 0.5) * 28.0
                    val offsetZ = (random.nextFloat() - 0.5) * 28.0
                    val px = player.x + offsetX
                    val pz = player.z + offsetZ
                    val py = player.y - 1.0 + random.nextFloat() * 3.0

                    level.addParticle(ParticleTypes.REVERSE_PORTAL, px, py, pz, 0.0, 0.35 + random.nextFloat() * 0.2, 0.0)
                    if (random.nextBoolean()) {
                        level.addParticle(
                            DustParticleOptions(Vector3f(0.85f, 0.03f, 0.12f), 1.25f),
                            px, py, pz,
                            0.0, 0.25 + random.nextFloat() * 0.15, 0.0
                        )
                    } else {
                        level.addParticle(
                            DustParticleOptions(Vector3f(0.55f, 0.05f, 0.75f), 1.10f),
                            px, py, pz,
                            0.0, 0.22 + random.nextFloat() * 0.15, 0.0
                        )
                    }
                }

                // Plumas negras cayendo que se disuelven en humo antes de tocar el suelo
                if (eventElapsedTicks % 2L == 0L) {
                    val featherX = player.x + (random.nextFloat() - 0.5) * 20.0
                    val featherZ = player.z + (random.nextFloat() - 0.5) * 20.0
                    val featherY = player.y + 6.0 + random.nextFloat() * 4.0
                    level.addParticle(
                        ParticleTypes.SQUID_INK,
                        featherX, featherY, featherZ,
                        (random.nextFloat() - 0.5) * 0.02,
                        -0.06 - random.nextFloat() * 0.04,
                        (random.nextFloat() - 0.5) * 0.02
                    )
                    level.addParticle(
                        ParticleTypes.SMOKE,
                        featherX, featherY, featherZ,
                        (random.nextFloat() - 0.5) * 0.015,
                        -0.04 - random.nextFloat() * 0.03,
                        (random.nextFloat() - 0.5) * 0.015
                    )
                }
            }
        }

        // Rayquaza: Cronología de 15s y partículas de tempestad / viento de alta velocidad
        val isRayquaza = isEventActiveClient && (currentEffectType.lowercase(Locale.ROOT) in listOf("rayquaza", "rayquaza2")) && currentIntensity > 0.005f
        if (isRayquaza) {
            val player = client.player
            val level = client.level
            if (player != null && level != null) {
                // 1. Cronología de audio de 15 segundos
                // 0:00 - 0:02 (The Rift): Chillido metálico desgarrador de dragón en el silencio
                if (eventElapsedTicks == 10L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.ENDER_DRAGON_GROWL, net.minecraft.sounds.SoundSource.AMBIENT, 1.4f, 1.40f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.TRIDENT_THUNDER.value(), net.minecraft.sounds.SoundSource.AMBIENT, 1.1f, 1.25f, false)
                }
                // 0:02 - 0:05 (The Vortex): Desgarre violento de nubes y aceleración del vórtice
                if (eventElapsedTicks in listOf(45L, 75L)) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.ELYTRA_FLYING, net.minecraft.sounds.SoundSource.AMBIENT, 1.25f, 0.75f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.WIND_CHARGE_BURST.value(), net.minecraft.sounds.SoundSource.AMBIENT, 1.0f, 0.65f, false)
                }
                // 0:05 - 0:08 (The Cosmic Revelation): Ruptura cósmica y estelas de meteoros / filamentos
                if (eventElapsedTicks == 120L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.BEACON_ACTIVATE, net.minecraft.sounds.SoundSource.AMBIENT, 1.25f, 1.40f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.AMETHYST_BLOCK_CHIME, net.minecraft.sounds.SoundSource.AMBIENT, 1.15f, 1.25f, false)
                }
                // 0:08 - 0:12 (The Tempest Break): Pulso de HDR Bloom masivo y estallido de tempestad
                if (eventElapsedTicks == 175L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.LIGHTNING_BOLT_THUNDER, net.minecraft.sounds.SoundSource.AMBIENT, 1.35f, 0.85f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.WARDEN_SONIC_BOOM, net.minecraft.sounds.SoundSource.AMBIENT, 0.90f, 1.25f, false)
                }
                // 0:12 - 0:15 (The Ascension): Rugido celestial supremo de Rayquaza y estabilización del ojo
                if (eventElapsedTicks == 245L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.ENDER_DRAGON_GROWL, net.minecraft.sounds.SoundSource.AMBIENT, 1.4f, 0.95f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.RAID_HORN.value(), net.minecraft.sounds.SoundSource.AMBIENT, 1.2f, 1.35f, false)
                }
                // Pasados los 15s: ráfagas continuas de viento huracanado cada 3s
                if (eventElapsedTicks > 300L && eventElapsedTicks % 60L == 0L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.WIND_CHARGE_BURST.value(), net.minecraft.sounds.SoundSource.AMBIENT, 0.75f, 0.85f, false)
                }

                // 2. Partículas ambientales de alta velocidad horizontal (viento de tempestad, hojas, motes dorados y esmeraldas)
                val random = level.random
                val windSpeedX = 1.20 + random.nextFloat() * 0.40 // Viento violento hacia +X
                val windSpeedZ = 0.20 + random.nextFloat() * 0.15

                val count = if (eventElapsedTicks > 160L) 4 else 2
                for (i in 0 until count) {
                    val spawnOffsetX = -18.0 + random.nextFloat() * 8.0 // Nace en barlovento
                    val spawnOffsetY = -1.0 + random.nextFloat() * 5.0
                    val spawnOffsetZ = (random.nextFloat() - 0.5) * 26.0

                    val px = player.x + spawnOffsetX
                    val py = player.y + spawnOffsetY
                    val pz = player.z + spawnOffsetZ

                    // Cintas de viento translúcidas (Cloud / Sweep Attack)
                    if (random.nextBoolean()) {
                        level.addParticle(ParticleTypes.CLOUD, px, py, pz, windSpeedX, (random.nextFloat() - 0.5) * 0.05, windSpeedZ)
                    } else {
                        // Motes de energía dorada y verde esmeralda de Mega Rayquaza
                        val isGold = random.nextBoolean()
                        val colorVec = if (isGold) Vector3f(1.0f, 0.85f, 0.25f) else Vector3f(0.12f, 0.95f, 0.45f)
                        level.addParticle(
                            DustParticleOptions(colorVec, 1.25f),
                            px, py, pz,
                            windSpeedX * 0.85, (random.nextFloat() - 0.5) * 0.04, windSpeedZ * 0.85
                        )
                    }

                    // Hojas flotantes arrastradas por el viento
                    if (random.nextInt(3) == 0) {
                        level.addParticle(ParticleTypes.CHERRY_LEAVES, px, py, pz, windSpeedX * 0.95, -0.02, windSpeedZ * 0.95)
                    }
                }
            }
        }

        // Kyogre: Cronología de audio de 15s y partículas de diluvio primordial / mareas abisales
        val isKyogre = isEventActiveClient && (currentEffectType.lowercase(Locale.ROOT) in listOf("kyogre", "kyogre2")) && currentIntensity > 0.005f
        if (isKyogre) {
            val player = client.player
            val level = client.level
            if (player != null && level != null) {
                // 1. Cronología de audio de 15 segundos
                // 0:00 - 0:02 (The Deluge Awakens): Trueno sordo de lluvia torrencial y zambullida abisal
                if (eventElapsedTicks == 10L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.LIGHTNING_BOLT_THUNDER, net.minecraft.sounds.SoundSource.AMBIENT, 1.4f, 0.70f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.AMBIENT_UNDERWATER_ENTER, net.minecraft.sounds.SoundSource.AMBIENT, 1.3f, 0.85f, false)
                }
                // 0:02 - 0:05 (The Abyssal Vortex): Resonancia de conducto marino y oleaje tempestuoso
                if (eventElapsedTicks in listOf(45L, 75L)) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.CONDUIT_AMBIENT, net.minecraft.sounds.SoundSource.AMBIENT, 1.35f, 0.80f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, net.minecraft.sounds.SoundSource.AMBIENT, 1.15f, 0.75f, false)
                }
                // 0:05 - 0:08 (The Alpha Revelation): Activación del faro abisal y llamada de los mares
                if (eventElapsedTicks == 120L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.BEACON_ACTIVATE, net.minecraft.sounds.SoundSource.AMBIENT, 1.30f, 0.85f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.ELDER_GUARDIAN_CURSE, net.minecraft.sounds.SoundSource.AMBIENT, 0.95f, 1.35f, false)
                }
                // 0:08 - 0:12 (The Primordial Tsunami): Estallido de trueno de tridente y choque hidro-cinético
                if (eventElapsedTicks == 175L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.TRIDENT_THUNDER.value(), net.minecraft.sounds.SoundSource.AMBIENT, 1.45f, 0.75f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.CONDUIT_ACTIVATE, net.minecraft.sounds.SoundSource.AMBIENT, 1.25f, 0.95f, false)
                }
                // 0:12 - 0:15 (The Ocean Sovereign): Rugido de olas y cántico del conducto
                if (eventElapsedTicks == 245L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.WARDEN_ROAR, net.minecraft.sounds.SoundSource.AMBIENT, 1.20f, 1.45f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.CONDUIT_DEACTIVATE, net.minecraft.sounds.SoundSource.AMBIENT, 1.10f, 0.70f, false)
                }
                // Pasados los 15s: salpicaduras torrenciales cada 3s
                if (eventElapsedTicks > 300L && eventElapsedTicks % 60L == 0L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.WATER_AMBIENT, net.minecraft.sounds.SoundSource.AMBIENT, 0.80f, 0.90f, false)
                }

                // 2. Partículas ambientales de diluvio y bioluminiscencia abisal
                val random = level.random
                val count = if (eventElapsedTicks > 160L) 5 else 3
                for (i in 0 until count) {
                    val px = player.x + (random.nextFloat() - 0.5) * 28.0
                    val py = player.y + random.nextFloat() * 6.0
                    val pz = player.z + (random.nextFloat() - 0.5) * 28.0

                    if (random.nextBoolean()) {
                        level.addParticle(ParticleTypes.SPLASH, px, py, pz, 0.0, -0.25, 0.0)
                    } else {
                        val isCyan = random.nextBoolean()
                        val colorVec = if (isCyan) Vector3f(0.08f, 0.92f, 1.05f) else Vector3f(0.12f, 0.35f, 0.95f)
                        level.addParticle(
                            DustParticleOptions(colorVec, 1.25f),
                            px, py, pz,
                            (random.nextFloat() - 0.5) * 0.15, -0.15, (random.nextFloat() - 0.5) * 0.15
                        )
                    }
                }
            }
        }

        // Groudon: Cronología de audio de 15s y partículas de magma / ceniza volcánica
        val isGroudon = isEventActiveClient && (currentEffectType.lowercase(Locale.ROOT) in listOf("groudon", "groudon2")) && currentIntensity > 0.005f
        if (isGroudon) {
            val player = client.player
            val level = client.level
            if (player != null && level != null) {
                // 1. Cronología de audio de 15 segundos
                // 0:00 - 0:02 (The Earth Tremor): Retumbar sísmico y fisura de magma
                if (eventElapsedTicks == 10L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.AMBIENT, 1.30f, 0.60f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.LAVA_EXTINGUISH, net.minecraft.sounds.SoundSource.AMBIENT, 1.20f, 0.75f, false)
                }
                // 0:02 - 0:05 (The Magma Rift): Burbujeo volcánico y calor abrasador
                if (eventElapsedTicks in listOf(45L, 75L)) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.LAVA_POP, net.minecraft.sounds.SoundSource.AMBIENT, 1.40f, 0.70f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.FIRECHARGE_USE, net.minecraft.sounds.SoundSource.AMBIENT, 1.15f, 0.80f, false)
                }
                // 0:05 - 0:08 (The Omega Awakening): Bramido tectónico y activación del sol primal
                if (eventElapsedTicks == 120L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.WARDEN_ROAR, net.minecraft.sounds.SoundSource.AMBIENT, 1.35f, 0.75f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.BEACON_ACTIVATE, net.minecraft.sounds.SoundSource.AMBIENT, 1.25f, 0.70f, false)
                }
                // 0:08 - 0:12 (The Volcanic Cataclysm): Estallido piroclástico y trueno volcánico
                if (eventElapsedTicks == 175L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.LIGHTNING_BOLT_THUNDER, net.minecraft.sounds.SoundSource.AMBIENT, 1.45f, 0.60f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.AMBIENT, 1.35f, 0.75f, false)
                }
                // 0:12 - 0:15 (The Earth Sovereign): Choque de placas y estabilización del núcleo
                if (eventElapsedTicks == 245L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.WARDEN_SONIC_BOOM, net.minecraft.sounds.SoundSource.AMBIENT, 1.25f, 0.70f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.LAVA_AMBIENT, net.minecraft.sounds.SoundSource.AMBIENT, 1.20f, 0.85f, false)
                }
                // Pasados los 15s: estallidos de magma cada 3s
                if (eventElapsedTicks > 300L && eventElapsedTicks % 60L == 0L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.LAVA_POP, net.minecraft.sounds.SoundSource.AMBIENT, 0.90f, 0.75f, false)
                }

                // 2. Partículas ambientales de ceniza volcánica, chispas y polvo de magma
                val random = level.random
                val count = if (eventElapsedTicks > 160L) 5 else 3
                for (i in 0 until count) {
                    val px = player.x + (random.nextFloat() - 0.5) * 26.0
                    val py = player.y + random.nextFloat() * 5.0
                    val pz = player.z + (random.nextFloat() - 0.5) * 26.0

                    if (random.nextInt(3) == 0) {
                        level.addParticle(ParticleTypes.LAVA, px, py, pz, 0.0, 0.05, 0.0)
                    } else if (random.nextBoolean()) {
                        level.addParticle(ParticleTypes.FLAME, px, py, pz, (random.nextFloat() - 0.5) * 0.04, 0.08, (random.nextFloat() - 0.5) * 0.04)
                    } else {
                        val isGold = random.nextBoolean()
                        val colorVec = if (isGold) Vector3f(1.50f, 1.15f, 0.22f) else Vector3f(1.40f, 0.45f, 0.05f)
                        level.addParticle(
                            DustParticleOptions(colorVec, 1.30f),
                            px, py, pz,
                            (random.nextFloat() - 0.5) * 0.05, 0.12, (random.nextFloat() - 0.5) * 0.05
                        )
                    }
                }
            }
        }

        // Zygarde: Cronología de audio de 15s y partículas de células hexagonales y equilibrio telúrico
        val isZygarde = isEventActiveClient && currentEffectType.lowercase(Locale.ROOT) == "zygarde" && currentIntensity > 0.005f
        if (isZygarde) {
            val player = client.player
            val level = client.level
            if (player != null && level != null) {
                // 1. Cronología de audio de 15 segundos
                // 0:00 - 0:03 (El Despertar de los Núcleos): Resonancia telúrica celular
                if (eventElapsedTicks in listOf(12L, 30L, 50L)) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.AMETHYST_CLUSTER_STEP, net.minecraft.sounds.SoundSource.AMBIENT, 1.25f, 0.85f + (eventElapsedTicks / 50.0f) * 0.35f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.SCULK_BLOCK_SPREAD, net.minecraft.sounds.SoundSource.AMBIENT, 1.0f, 1.2f, false)
                }
                // 0:06 - 0:09 (La Gran Z): Ensamblaje de la red de células y pulso de orden
                if (eventElapsedTicks == 130L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.BEACON_POWER_SELECT, net.minecraft.sounds.SoundSource.AMBIENT, 1.35f, 1.15f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.AMETHYST_BLOCK_RESONATE, net.minecraft.sounds.SoundSource.AMBIENT, 1.1f, 1.4f, false)
                }
                // 0:12 - 0:15 (Fuerza Telúrica): Impacto armónico del Guardián del Equilibrio
                if (eventElapsedTicks == 245L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.WARDEN_SONIC_BOOM, net.minecraft.sounds.SoundSource.AMBIENT, 1.15f, 1.25f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.BEACON_ACTIVATE, net.minecraft.sounds.SoundSource.AMBIENT, 1.3f, 1.5f, false)
                }

                // 2. Partículas: Células de jade luminosas y destellos de polen esmeralda
                val random = level.random
                val count = if (eventElapsedTicks > 150L) 3 else 1
                for (i in 0 until count) {
                    val ox = (random.nextFloat() - 0.5) * 24.0
                    val oz = (random.nextFloat() - 0.5) * 24.0
                    val px = player.x + ox
                    val pz = player.z + oz
                    val py = player.y - 0.3 + random.nextFloat() * 2.2
                    level.addParticle(
                        DustParticleOptions(Vector3f(0.08f, 0.95f, 0.35f), 1.2f),
                        px, py, pz,
                        (random.nextFloat() - 0.5) * 0.03,
                        0.05 + random.nextFloat() * 0.08,
                        (random.nextFloat() - 0.5) * 0.03
                    )
                    if (random.nextInt(2) == 0) {
                        level.addParticle(ParticleTypes.GLOW, px, py, pz, 0.0, 0.04, 0.0)
                    }
                }
            }
        }

        // Audio en bucle de Dialga: sonido ambiental suave de fondo
        val isDialga = isEventActiveClient && currentEffectType.lowercase(Locale.ROOT) == "dialga" && currentIntensity > 0.005f
        if (isDialga) {
            if (dialgaSoundInstance == null || dialgaSoundInstance?.isStopped == true) {
                val sound = com.desgraciamon.vortex.client.sound.DialgaAmbientSoundInstance()
                dialgaSoundInstance = sound
                client.soundManager.play(sound)
            }
        } else if (dialgaSoundInstance != null) {
            client.soundManager.stop(dialgaSoundInstance!!)
            dialgaSoundInstance = null
        }

        // Audio en bucle de Palkia: sonido cósmico envolvente suave de fondo
        val isPalkia = isEventActiveClient && currentEffectType.lowercase(Locale.ROOT) == "palkia" && currentIntensity > 0.005f
        if (isPalkia) {
            if (palkiaSoundInstance == null || palkiaSoundInstance?.isStopped == true) {
                val sound = com.desgraciamon.vortex.client.sound.PalkiaAmbientSoundInstance()
                palkiaSoundInstance = sound
                client.soundManager.play(sound)
            }
        } else if (palkiaSoundInstance != null) {
            client.soundManager.stop(palkiaSoundInstance!!)
            palkiaSoundInstance = null
        }

        // Goosebumps / Travis Scott: Pulso 808 sub-bass y partículas neón psicodélicas
        val isGoosebumps = isEventActiveClient && currentEffectType.lowercase(Locale.ROOT) in listOf("goosebumps", "travis", "astronomical") && currentIntensity > 0.005f
        if (isGoosebumps) {
            val player = client.player
            val level = client.level
            if (player != null && level != null) {
                // Pulso rítmico sub-bass cada ~9 ticks (~130 BPM)
                if (clientTicks % 9L == 0L) {
                    val isHeavyKick = (clientTicks / 9L) % 4L == 0L
                    if (isHeavyKick) {
                        level.playLocalSound(player.x, player.y, player.z, SoundEvents.WARDEN_HEARTBEAT, net.minecraft.sounds.SoundSource.AMBIENT, 1.25f, 0.50f, false)
                        level.playLocalSound(player.x, player.y, player.z, SoundEvents.AMETHYST_BLOCK_RESONATE, net.minecraft.sounds.SoundSource.AMBIENT, 0.9f, 0.70f, false)
                    } else {
                        level.playLocalSound(player.x, player.y, player.z, SoundEvents.WARDEN_HEARTBEAT, net.minecraft.sounds.SoundSource.AMBIENT, 0.85f, 0.65f, false)
                    }
                }

                // Partículas flotantes neón: Cian eléctrico, Magenta y Verde flúor
                val random = level.random
                for (i in 0 until 3) {
                    val px = player.x + (random.nextFloat() - 0.5) * 20.0
                    val pz = player.z + (random.nextFloat() - 0.5) * 20.0
                    val py = player.y - 0.2 + random.nextFloat() * 2.5
                    val pColor = when (random.nextInt(3)) {
                        0 -> Vector3f(0.00f, 1.00f, 0.88f) // Cian Neón
                        1 -> Vector3f(1.00f, 0.05f, 0.75f) // Magenta
                        else -> Vector3f(0.55f, 1.00f, 0.05f) // Verde Flúor
                    }
                    level.addParticle(
                        DustParticleOptions(pColor, 1.2f),
                        px, py, pz,
                        (random.nextFloat() - 0.5) * 0.04,
                        0.04 + random.nextFloat() * 0.06,
                        (random.nextFloat() - 0.5) * 0.04
                    )
                }
            }
        }

        // Solgaleo: The Solar Corona Rupture
        val isSolgaleo = isEventActiveClient && currentEffectType.lowercase(Locale.ROOT) == "solgaleo" && currentIntensity > 0.005f
        if (isSolgaleo) {
            val player = client.player
            val level = client.level
            if (player != null && level != null) {
                // Phase 1 (0.0s - 3.0s, ticks 0..60): Sub-bass rumble y tensión gravitacional
                if (eventElapsedTicks in listOf(15L, 35L, 55L)) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.RESPAWN_ANCHOR_CHARGE, net.minecraft.sounds.SoundSource.AMBIENT, 1.20f, 0.55f + (eventElapsedTicks / 60.0f) * 0.35f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.BEACON_ACTIVATE, net.minecraft.sounds.SoundSource.AMBIENT, 1.0f, 0.60f, false)
                }
                // Phase 2 (3.0s - 6.5s, ticks 60..130): Desgarro de Ultraespacio y zumbido de plasma
                if (eventElapsedTicks in listOf(65L, 95L, 120L)) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.END_PORTAL_SPAWN, net.minecraft.sounds.SoundSource.AMBIENT, 1.30f, 1.10f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.LIGHTNING_BOLT_IMPACT, net.minecraft.sounds.SoundSource.AMBIENT, 1.10f, 0.75f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.BEACON_POWER_SELECT, net.minecraft.sounds.SoundSource.AMBIENT, 1.20f, 1.40f, false)
                }
                // Phase 3 (6.5s, tick 130): Estallido solar masivo e impacto de Solgaleo
                if (eventElapsedTicks == 130L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.AMBIENT, 1.80f, 0.65f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.ENDER_DRAGON_GROWL, net.minecraft.sounds.SoundSource.AMBIENT, 1.60f, 0.85f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.WARDEN_ROAR, net.minecraft.sounds.SoundSource.AMBIENT, 1.45f, 0.70f, false)
                    // Anillo expansivo de impacto cinético a nivel del suelo
                    for (deg in 0 until 360 step 15) {
                        val rad = Math.toRadians(deg.toDouble())
                        val vx = Math.cos(rad) * 0.85
                        val vz = Math.sin(rad) * 0.85
                        level.addParticle(ParticleTypes.EXPLOSION, player.x, player.y + 0.1, player.z, vx, 0.05, vz)
                        level.addParticle(ParticleTypes.FLASH, player.x, player.y + 1.2, player.z, 0.0, 0.0, 0.0)
                    }
                }
                // Pasados los 6.5s: pulsos de corona solar cada 3s
                if (eventElapsedTicks > 130L && eventElapsedTicks % 60L == 0L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.FIRECHARGE_USE, net.minecraft.sounds.SoundSource.AMBIENT, 0.95f, 0.75f, false)
                }

                // Partículas: Brasas doradas descendentes (Phase 2) y polvo solar incandescente (Phase 3)
                val random = level.random
                val count = if (eventElapsedTicks in 60L..130L) 6 else if (eventElapsedTicks > 130L) 4 else 2
                for (i in 0 until count) {
                    val px = player.x + (random.nextFloat() - 0.5) * 32.0
                    val py = player.y + 1.0 + random.nextFloat() * 6.0
                    val pz = player.z + (random.nextFloat() - 0.5) * 32.0

                    if (eventElapsedTicks in 60L..130L) {
                        // Phase 2: Golden embers drifting downward with accelerated gravity
                        val goldColor = Vector3f(1.0f, 0.82f + random.nextFloat() * 0.15f, 0.15f)
                        level.addParticle(
                            DustParticleOptions(goldColor, 1.35f),
                            px, py, pz,
                            (random.nextFloat() - 0.5) * 0.05,
                            -0.12 - random.nextFloat() * 0.10, // cayendo hacia abajo
                            (random.nextFloat() - 0.5) * 0.05
                        )
                        if (random.nextInt(3) == 0) {
                            level.addParticle(ParticleTypes.FLAME, px, py, pz, 0.0, -0.05, 0.0)
                        }
                    } else if (eventElapsedTicks > 130L) {
                        // Phase 3: Persistent solar corona dust motes & golden flares
                        if (random.nextBoolean()) {
                            level.addParticle(ParticleTypes.WAX_ON, px, player.y + random.nextFloat() * 2.5, pz, 0.0, 0.04, 0.0)
                        } else {
                            val warmGold = Vector3f(1.0f, 0.70f + random.nextFloat() * 0.25f, 0.10f)
                            level.addParticle(
                                DustParticleOptions(warmGold, 1.2f),
                                px, player.y + random.nextFloat() * 2.5, pz,
                                (random.nextFloat() - 0.5) * 0.03,
                                0.03 + random.nextFloat() * 0.05,
                                (random.nextFloat() - 0.5) * 0.03
                            )
                        }
                    }
                }
            }
        }

        // Lunala: The Full Moon Eclipse
        val isLunala = isEventActiveClient && currentEffectType.lowercase(Locale.ROOT) == "lunala" && currentIntensity > 0.005f
        if (isLunala) {
            val player = client.player
            val level = client.level
            if (player != null && level != null) {
                // Phase 1 (0.0s - 3.0s, ticks 0..60): Hum cristalino resonante
                if (eventElapsedTicks in listOf(15L, 40L)) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.AMETHYST_BLOCK_RESONATE, net.minecraft.sounds.SoundSource.AMBIENT, 1.25f, 0.65f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.CONDUIT_ACTIVATE, net.minecraft.sounds.SoundSource.AMBIENT, 1.10f, 0.80f, false)
                }
                // Phase 2 (3.0s - 6.5s, ticks 60..130): Pliegue dimensional y latido umbral rítmico
                if (eventElapsedTicks in 60L..130L && eventElapsedTicks % 18L == 0L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.WARDEN_HEARTBEAT, net.minecraft.sounds.SoundSource.AMBIENT, 1.15f, 0.70f, false)
                }
                if (eventElapsedTicks in listOf(75L, 110L)) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.PORTAL_TRAVEL, net.minecraft.sounds.SoundSource.AMBIENT, 0.85f, 1.20f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.AMETHYST_CLUSTER_STEP, net.minecraft.sounds.SoundSource.AMBIENT, 1.10f, 0.55f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.AMETHYST_BLOCK_BREAK, net.minecraft.sounds.SoundSource.AMBIENT, 0.95f, 1.45f, false)
                }
                // Phase 3 (6.5s, tick 130): Despertar de la Luna Llena Roja y onda de choque astral
                if (eventElapsedTicks == 130L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.WARDEN_SONIC_BOOM, net.minecraft.sounds.SoundSource.AMBIENT, 1.40f, 0.60f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.ELDER_GUARDIAN_CURSE, net.minecraft.sounds.SoundSource.AMBIENT, 1.30f, 0.90f, false)
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.BEACON_ACTIVATE, net.minecraft.sounds.SoundSource.AMBIENT, 1.25f, 1.45f, false)
                    // Onda expansiva astral y columna de luz estelar índigo descendiendo al suelo
                    for (deg in 0 until 360 step 12) {
                        val rad = Math.toRadians(deg.toDouble())
                        val vx = Math.cos(rad) * 0.70
                        val vz = Math.sin(rad) * 0.70
                        level.addParticle(ParticleTypes.END_ROD, player.x, player.y + 0.5, player.z, vx, 0.03, vz)
                    }
                }
                // Pasados los 6.5s: murmullo cósmico cada 3s
                if (eventElapsedTicks > 130L && eventElapsedTicks % 60L == 0L) {
                    level.playLocalSound(player.x, player.y, player.z, SoundEvents.AMETHYST_BLOCK_CHIME, net.minecraft.sounds.SoundSource.AMBIENT, 0.90f, 0.85f, false)
                }

                // Partículas: Niebla umbral violeta en el suelo (Phase 2) y lluvia de estrellas fugaces turquesa (Phase 3)
                val random = level.random
                val count = if (eventElapsedTicks in 60L..130L) 4 else if (eventElapsedTicks > 130L) 5 else 2
                for (i in 0 until count) {
                    val px = player.x + (random.nextFloat() - 0.5) * 30.0
                    val pz = player.z + (random.nextFloat() - 0.5) * 30.0

                    if (eventElapsedTicks in 60L..130L) {
                        // Phase 2: Ground-level lavender/magenta umbral mist
                        val py = player.y - 0.2 + random.nextFloat() * 1.2
                        val magentaDust = Vector3f(0.68f + random.nextFloat() * 0.25f, 0.12f, 0.85f)
                        level.addParticle(
                            DustParticleOptions(magentaDust, 1.3f),
                            px, py, pz,
                            (random.nextFloat() - 0.5) * 0.02,
                            0.02 + random.nextFloat() * 0.03,
                            (random.nextFloat() - 0.5) * 0.02
                        )
                    } else if (eventElapsedTicks > 130L) {
                        // Phase 3: Turquoise shooting stars cascading across dome + stardust
                        val py = player.y + 0.5 + random.nextFloat() * 5.0
                        if (random.nextInt(3) == 0) {
                            level.addParticle(ParticleTypes.END_ROD, px, py, pz, (random.nextFloat() - 0.5) * 0.04, -0.08, (random.nextFloat() - 0.5) * 0.04)
                        } else {
                            val cyanStar = Vector3f(0.15f, 0.88f + random.nextFloat() * 0.12f, 0.95f)
                            level.addParticle(
                                DustParticleOptions(cyanStar, 1.25f),
                                px, py, pz,
                                (random.nextFloat() - 0.5) * 0.04,
                                -0.06 - random.nextFloat() * 0.06,
                                (random.nextFloat() - 0.5) * 0.04
                            )
                        }
                    }
                }
            }
        }
    }

    private var dialgaSoundInstance: com.desgraciamon.vortex.client.sound.DialgaAmbientSoundInstance? = null
    private var palkiaSoundInstance: com.desgraciamon.vortex.client.sound.PalkiaAmbientSoundInstance? = null

    fun getCurrentCameraShakeAmount(partialTick: Float): Float {
        if (!isEventActiveClient && currentIntensity <= 0.001f) return 0.0f
        val smoothIntensity = getSmoothIntensity(partialTick)
        var extraShake = 0.0f
        val eff = currentEffectType.lowercase(Locale.ROOT)
        if (eff in listOf("yveltal", "yveltalold")) {
            if (eventElapsedTicks in 130L..165L) extraShake += 0.22f
            if (eventElapsedTicks in 245L..280L) extraShake += 0.28f
        } else if (eff in listOf("rayquaza", "rayquazaold", "rayquaza2")) {
            if (eventElapsedTicks in 90L..125L) extraShake += 0.24f
            if (eventElapsedTicks in 210L..250L) extraShake += 0.32f
        } else if (eff in listOf("kyogre", "kyogre2")) {
            if (eventElapsedTicks in 80L..120L) extraShake += 0.22f
            if (eventElapsedTicks in 200L..245L) extraShake += 0.30f
        } else if (eff in listOf("groudon", "groudon2")) {
            if (eventElapsedTicks in 70L..115L) extraShake += 0.26f
            if (eventElapsedTicks in 195L..250L) extraShake += 0.36f
        } else if (eff in listOf("palkia", "palkiaold")) {
            if (eventElapsedTicks in 110L..140L) extraShake += 0.18f
            if (eventElapsedTicks in 230L..260L) extraShake += 0.22f
        } else if (eff == "zygarde") {
            if (eventElapsedTicks in 120L..150L) extraShake += 0.20f
            if (eventElapsedTicks in 240L..270L) extraShake += 0.26f
        } else if (eff in listOf("xerneas", "xerneasold")) {
            if (eventElapsedTicks in 130L..155L) extraShake += 0.14f
            if (eventElapsedTicks in 245L..270L) extraShake += 0.18f
        } else if (eff in listOf("goosebumps", "travis", "astronomical")) {
            val beatTick = clientTicks % 9L
            val beatFrac = beatTick.toFloat() / 9.0f
            val kick = (1.0f - beatFrac).coerceAtLeast(0.0f).let { it * it * it }
            extraShake += kick * 0.18f
        } else if (eff == "solgaleo") {
            if (eventElapsedTicks in 0L..60L) extraShake += 0.10f
            if (eventElapsedTicks in 60L..130L) extraShake += 0.28f
            if (eventElapsedTicks in 130L..165L) extraShake += 0.42f
        } else if (eff == "lunala") {
            if (eventElapsedTicks in 0L..60L) extraShake += 0.05f
            if (eventElapsedTicks in 60L..130L) {
                val beat = (eventElapsedTicks % 18L).toFloat() / 18.0f
                val pulse = (1.0f - beat).coerceAtLeast(0.0f)
                extraShake += pulse * pulse * 0.16f
            }
            if (eventElapsedTicks in 130L..165L) extraShake += 0.32f
        }
        return (currentCameraShake + extraShake) * smoothIntensity
    }

    fun getSmoothIntensity(partialTick: Float): Float {
        return (prevIntensity + (currentIntensity - prevIntensity) * partialTick).coerceIn(0.0f, 1.0f)
    }

    fun getSmoothTimeSeconds(partialTick: Float): Float {
        return (clientTicks.toFloat() + partialTick) / 20.0f
    }

    fun getSmoothEventAgeSeconds(partialTick: Float): Float {
        return (eventElapsedTicks.toFloat() + partialTick) / 20.0f
    }

    /**
     * Actualiza los Uniforms Time e Intensity del filtro post-procesado (morado, azul o perla) en cada frame.
     */
    fun onPreRenderFrame(gameRenderer: GameRenderer, partialTick: Float) {
        if (postShaderFailed) return
        val accessor = gameRenderer as? GameRendererAccessor ?: return
        val smoothIntensity = getSmoothIntensity(partialTick)
        if (!isEventActiveClient && smoothIntensity <= 0.001f) {
            return
        }

        val targetShader = getPostShaderForEffect()
        var postChain = accessor.`desgraciamon$getPostEffect`()
        // Si el jugador cambió de perspectiva (F5) o no está activo el shader correcto, cargarlo
        if ((postChain == null || postChain.name != targetShader.toString()) && (isEventActiveClient || smoothIntensity > 0.005f)) {
            try {
                accessor.`desgraciamon$loadEffect`(targetShader)
                postChain = accessor.`desgraciamon$getPostEffect`()
            } catch (_: Exception) {
                postShaderFailed = true
                return
            }
        }

        val chainAccessor = postChain as? PostChainAccessor ?: return
        val timeSeconds = getSmoothTimeSeconds(partialTick)
        val eventAgeSec = getSmoothEventAgeSeconds(partialTick)

        for (pass in chainAccessor.`desgraciamon$getPasses`()) {
            val effect = pass.effect
            effect.safeGetUniform("Time").set(timeSeconds)
            effect.safeGetUniform("Intensity").set(smoothIntensity)
            effect.safeGetUniform("EventTime")?.set(eventAgeSec)
        }
    }

    /**
     * Modificador dinámico de Campo de Visión (FOV) según la cinemática de Solgaleo y Lunala.
     */
    fun getFovMultiplier(partialTick: Float): Float {
        if (!isEventActiveClient && currentIntensity <= 0.001f) return 1.0f
        val smoothIntensity = getSmoothIntensity(partialTick)
        val eventAge = getSmoothEventAgeSeconds(partialTick)
        val eff = currentEffectType.lowercase(Locale.ROOT)
        return when (eff) {
            "solgaleo" -> {
                // Phase 1 (0.0s - 3.0s): Expande suavemente de 70° a 85° (gravitational lens stretch, ~1.214x)
                // Phase 2 (3.0s - 6.5s): Mantiene perspectiva extendida
                // Phase 3 (6.5s+): Regresa suavemente a la perspectiva normal
                val targetMult = when {
                    eventAge < 3.0f -> 1.0f + (eventAge / 3.0f) * 0.214f
                    eventAge < 6.5f -> 1.214f
                    eventAge < 8.0f -> 1.214f - ((eventAge - 6.5f) / 1.5f) * 0.214f
                    else -> 1.0f
                }
                1.0f + (targetMult - 1.0f) * smoothIntensity
            }
            "lunala" -> {
                // Phase 1 (0.0s - 3.0s): Se contrae claustrofóbicamente de 70° a 62° (~0.885x)
                // Phase 2 (3.0s - 6.5s): Mantiene contracción con pulsación sutil de latido
                // Phase 3 (6.5s+): Regresa a la normalidad con la onda expansiva
                val targetMult = when {
                    eventAge < 3.0f -> 1.0f - (eventAge / 3.0f) * 0.115f
                    eventAge < 6.5f -> {
                        val beat = kotlin.math.sin((eventAge - 3.0f) * 4.0f) * 0.015f
                        0.885f + beat
                    }
                    eventAge < 8.0f -> 0.885f + ((eventAge - 6.5f) / 1.5f) * 0.115f
                    else -> 1.0f
                }
                1.0f + (targetMult - 1.0f) * smoothIntensity
            }
            else -> 1.0f
        }
    }

    fun resetClientState(client: Minecraft) {
        isEventActiveClient = false
        currentIntensity = 0.0f
        prevIntensity = 0.0f
        remainingSecondsClient = 0
        currentCameraShake = 0.0f
        eventElapsedTicks = 0L
        postShaderFailed = false
        val accessor = client.gameRenderer as? GameRendererAccessor
        val activeChain = accessor?.`desgraciamon$getPostEffect`()
        if (activeChain != null && (activeChain.name == getPostShaderForEffect().toString() || activeChain.name.contains("vortex") || activeChain.name.contains("special_effects"))) {
            client.gameRenderer.shutdownEffect()
        }
        dialgaSoundInstance?.let { sound ->
            client.soundManager.stop(sound)
        }
        dialgaSoundInstance = null

        palkiaSoundInstance?.let { sound ->
            client.soundManager.stop(sound)
        }
        palkiaSoundInstance = null
    }

    fun formatCountdown(): String {
        val mins = (remainingSecondsClient / 60).coerceAtLeast(0)
        val secs = (remainingSecondsClient % 60).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d", mins, secs)
    }
}
