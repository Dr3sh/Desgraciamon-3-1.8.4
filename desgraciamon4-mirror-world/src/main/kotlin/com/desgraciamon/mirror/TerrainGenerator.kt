package com.desgraciamon.mirror

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.data.worldgen.features.TreeFeatures
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature
import net.minecraft.world.phys.Vec3
import kotlin.math.*

/**
 * Tema único y por defecto para el Mundo Espejo.
 * Mantiene la compatibilidad con el shader de cielo, cinemáticas y títulos.
 */
data class MirrorTheme(
    val themeId: String = "default",
    val displayName: String = "Valle de los Recuerdos",
    val topBlock: Block = Blocks.GRASS_BLOCK,
    val subBlock: Block = Blocks.DIRT,
    val rockBlock: Block = Blocks.STONE,
    val skyTintR: Float = 1.0f,
    val skyTintG: Float = 1.0f,
    val skyTintB: Float = 1.0f,
    val overcastDensity: Float = 0.82f
)

/**
 * Generador del Mundo Espejo:
 * - Plano de 120x120 rodeado por una majestuosa cadena montañosa natural.
 * - Lago orgánico integrado en la pradera sin losas (transiciones suaves de 1 bloque).
 * - Bioma sereno por defecto para todos los jugadores.
 * - Altar central de reflexión rúnica sin losas.
 * - Cero lag con generación directa por matrices y delimitación perimetral con barreras.
 */
object TerrainGenerator {
    const val BASE = 68
    const val BOUND = 60
    private const val MEADOW_RADIUS = 46.0
    private const val MOUNTAIN_CREST_RADIUS = 59.0
    private const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE

    val DEFAULT_THEME = MirrorTheme()

    fun themeFor(@Suppress("UNUSED_PARAMETER") index: Int): MirrorTheme = DEFAULT_THEME

    class LakeLayout(
        val cx: Double,
        val cz: Double,
        val radius: Double
    )

    fun lakeLayout(index: Int): LakeLayout {
        val seed = index * 0x9E3779B97F4A7C15uL.toLong() + 0x5DEECE66DL
        val random = java.util.Random(seed)
        // Posición natural del lago a un costado de la pradera
        val angle = (random.nextDouble() * 0.8 + 0.6) * Math.PI // Entre el cuadrante noroeste y suroeste
        val dist = 18.0 + random.nextDouble() * 4.0
        val lx = cos(angle) * dist
        val lz = sin(angle) * dist
        return LakeLayout(lx, lz, 12.5 + random.nextDouble() * 2.0)
    }

    private fun hash(x: Int, z: Int, seed: Long): Double {
        var h = seed xor (x.toLong() * 0x27D4EB2DL) xor (z.toLong() * 0x165667B1L)
        h = (h xor (h ushr 15)) * 0x2C1B3C6DL
        h = (h xor (h ushr 12)) * 0x297A2D39L
        h = (h xor (h ushr 15))
        return ((h and 0xFFFFFF).toDouble() / 0xFFFFFF.toDouble()) * 2.0 - 1.0
    }

    private fun noise(x: Double, z: Double, seed: Long): Double {
        val x0 = floor(x).toInt()
        val z0 = floor(z).toInt()
        val fx = x - x0
        val fz = z - z0
        val sx = fx * fx * (3 - 2 * fx)
        val sz = fz * fz * (3 - 2 * fz)
        val a = hash(x0, z0, seed) + (hash(x0 + 1, z0, seed) - hash(x0, z0, seed)) * sx
        val b = hash(x0, z0 + 1, seed) + (hash(x0 + 1, z0 + 1, seed) - hash(x0, z0 + 1, seed)) * sx
        return a + (b - a) * sz
    }

    private fun lakeDist(layout: LakeLayout, dx: Int, dz: Int): Double {
        val x = dx - layout.cx
        val z = dz - layout.cz
        return sqrt(x * x * 0.9 + z * z * 1.1)
    }

    fun spawnPoint(@Suppress("UNUSED_PARAMETER") level: ServerLevel, index: Int): Vec3 {
        val cx = PlotManager.centerX(index)
        val cz = PlotManager.centerZ(index)
        // Spawn frente al altar central de los recuerdos, en la pradera plana
        return Vec3(cx + 0.5, (BASE + 1).toDouble(), cz + 5.0)
    }

    fun isInsideMeadow(index: Int, x: Double, z: Double): Boolean {
        val dx = x - PlotManager.centerX(index)
        val dz = z - PlotManager.centerZ(index)
        return (dx * dx + dz * dz) <= (BOUND - 4) * (BOUND - 4)
    }

    fun randomSpawn(
        level: ServerLevel,
        index: Int,
        random: RandomSource,
        aquatic: Boolean = false,
        existingPositions: List<Vec3> = emptyList(),
        preferOutOfViewFrom: List<ServerPlayer> = emptyList()
    ): Vec3 {
        val cx = PlotManager.centerX(index)
        val cz = PlotManager.centerZ(index)
        val lake = lakeLayout(index)

        if (aquatic) {
            val a = random.nextDouble() * Math.PI * 2
            val r = random.nextDouble() * (lake.radius * 0.6)
            return Vec3(cx + lake.cx + cos(a) * r + 0.5, (BASE).toDouble(), cz + lake.cz + sin(a) * r + 0.5)
        }

        repeat(35) {
            val dx = random.nextInt((MEADOW_RADIUS * 2).toInt()) - MEADOW_RADIUS.toInt()
            val dz = random.nextInt((MEADOW_RADIUS * 2).toInt()) - MEADOW_RADIUS.toInt()
            val dCenter = sqrt((dx * dx + dz * dz).toDouble())

            // Evitar spawnear justo encima del altar central o en el agua
            if (abs(dx) <= 4 && abs(dz) <= 4) return@repeat
            if (dCenter > MEADOW_RADIUS - 3.0) return@repeat
            if (lakeDist(lake, dx, dz) <= lake.radius + 1.5) return@repeat

            val candidate = Vec3(cx + dx + 0.5, (BASE + 1).toDouble(), cz + dz + 0.5)
            if (existingPositions.isNotEmpty() && existingPositions.any { candidate.distanceTo(it) < 4.0 }) return@repeat
            return candidate
        }

        return spawnPoint(level, index)
    }

    /**
     * Genera la parcela 120x120 con pradera plana, lago suave sin losas y cordillera perimetral.
     */
    fun generate(level: ServerLevel, index: Int) {
        val cx = PlotManager.centerX(index)
        val cz = PlotManager.centerZ(index)
        val seed = index * 0x9E3779B97F4A7C15uL.toLong() + 0x5DEECE66DL
        val random = RandomSource.create(seed)
        val lake = lakeLayout(index)

        // Cargar chunks del área (120x120 = radio 60)
        val clearRadius = 66
        for (chunkX in ((cx - clearRadius - 16) shr 4)..((cx + clearRadius + 16) shr 4)) {
            for (chunkZ in ((cz - clearRadius - 16) shr 4)..((cz + clearRadius + 16) shr 4)) {
                level.getChunk(chunkX, chunkZ)
            }
        }

        val pos = BlockPos.MutableBlockPos()
        fun set(dx: Int, y: Int, dz: Int, state: BlockState) {
            level.setBlock(pos.set(cx + dx, y, cz + dz), state, FLAGS)
        }

        // 0. LIMPIEZA TOTAL DEL ÁREA PREVIA
        for (dx in -clearRadius..clearRadius) for (dz in -clearRadius..clearRadius) {
            if (dx * dx + dz * dz > clearRadius * clearRadius) continue
            for (y in 40..135) {
                pos.set(cx + dx, y, cz + dz)
                if (!level.getBlockState(pos).isAir) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_NONE)
                }
            }
        }

        // 1. GENERACIÓN DE SUELO Y MONTAÑAS
        for (dx in -BOUND..BOUND) for (dz in -BOUND..BOUND) {
            val distCenter = sqrt((dx * dx + dz * dz).toDouble())
            if (distCenter > BOUND + 2.0) continue

            // Altura de superficie
            val surfaceY: Int = if (distCenter <= MEADOW_RADIUS) {
                BASE // Pradera 100% plana
            } else {
                // Cordillera montañosa que rodea la pradera
                val t = ((distCenter - MEADOW_RADIUS) / (MOUNTAIN_CREST_RADIUS - MEADOW_RADIUS)).coerceIn(0.0, 1.0)
                val smoothT = t * t * (3.0 - 2.0 * t)
                val baseMountainH = smoothT * 26.0
                val peakN = noise(dx / 8.0, dz / 8.0, seed + 101) * 5.0 + noise(dx / 4.0, dz / 4.0, seed + 202) * 2.0
                (BASE + (baseMountainH + peakN).roundToInt()).coerceAtLeast(BASE)
            }

            // LAGO DENTRO DE LA PRADERA
            val ld = lakeDist(lake, dx, dz)
            val perturbedLakeR = lake.radius + noise(dx / 5.0, dz / 5.0, seed + 50) * 1.8
            val inLake = distCenter < (MEADOW_RADIUS - 4.0) && ld <= perturbedLakeR

            if (inLake) {
                // Lecho del lago: si tiene profundidad doble, es 2 bloques de agua; si es orilla, 1 bloque.
                // Sin losas: la transición es siempre de bloques completos con paso de 1 bloque.
                val isDeep = ld < (perturbedLakeR - 3.2)
                val bedY = if (isDeep) BASE - 3 else BASE - 2

                // Cimiento de roca y lecho
                for (y in 40..(BASE - 4)) {
                    set(dx, y, dz, Blocks.STONE.defaultBlockState())
                }
                for (y in (BASE - 4)..bedY) {
                    val bedBlock = when {
                        random.nextFloat() < 0.45f -> Blocks.SAND.defaultBlockState()
                        random.nextFloat() < 0.75f -> Blocks.GRAVEL.defaultBlockState()
                        else -> Blocks.CLAY.defaultBlockState()
                    }
                    set(dx, y, dz, bedBlock)
                }
                // Agua del lago hasta BASE - 1 (a 1 bloque del césped de la orilla)
                for (y in (bedY + 1)..(BASE - 1)) {
                    set(dx, y, dz, Blocks.WATER.defaultBlockState())
                }
                continue
            }

            // Orilla del lago: si la orilla o tierra adyacente quedase a doble altura, se baja a BASE para que sea escalón de 1 bloque
            val isShoreline = distCenter < (MEADOW_RADIUS - 2.0) && ld <= (perturbedLakeR + 1.2)
            val effSurfaceY = if (isShoreline) BASE else surfaceY

            // COLUMNA DE TIERRA / MONTAÑA
            // Base inferior hasta bedrock
            set(dx, 40, dz, Blocks.BEDROCK.defaultBlockState())
            for (y in 41..(BASE - 4)) {
                set(dx, y, dz, Blocks.STONE.defaultBlockState())
            }

            if (effSurfaceY == BASE) {
                // Pradera plana estándar
                for (y in (BASE - 3)..(BASE - 1)) {
                    val subBlock = if (isShoreline && random.nextFloat() < 0.40f) Blocks.SAND.defaultBlockState() else Blocks.DIRT.defaultBlockState()
                    set(dx, y, dz, subBlock)
                }
                val topBlock = if (isShoreline && ld < perturbedLakeR + 0.8 && random.nextFloat() < 0.50f) {
                    Blocks.SAND.defaultBlockState()
                } else {
                    Blocks.GRASS_BLOCK.defaultBlockState()
                }
                set(dx, BASE, dz, topBlock)
            } else {
                // Laderas y cumbres montañosas
                for (y in (BASE - 3)..(effSurfaceY - 4)) {
                    set(dx, y, dz, Blocks.STONE.defaultBlockState())
                }
                for (y in (effSurfaceY - 3)..(effSurfaceY - 1)) {
                    val subBlock = if (effSurfaceY <= BASE + 4) Blocks.DIRT.defaultBlockState() else Blocks.STONE.defaultBlockState()
                    set(dx, y, dz, subBlock)
                }

                // Bloque de superficie montañosa según altura
                val topBlock = when {
                    effSurfaceY <= BASE + 4 -> Blocks.GRASS_BLOCK.defaultBlockState()
                    effSurfaceY <= BASE + 16 -> {
                        val roll = random.nextFloat()
                        when {
                            roll < 0.60f -> Blocks.STONE.defaultBlockState()
                            roll < 0.85f -> Blocks.ANDESITE.defaultBlockState()
                            else -> Blocks.COBBLESTONE.defaultBlockState()
                        }
                    }
                    else -> {
                        // Cumbre nevada
                        if (random.nextFloat() < 0.35f) Blocks.CALCITE.defaultBlockState() else Blocks.SNOW_BLOCK.defaultBlockState()
                    }
                }
                set(dx, effSurfaceY, dz, topBlock)

                // Capa de nieve en cumbres altas
                if (effSurfaceY > BASE + 18 && topBlock.`is`(Blocks.SNOW_BLOCK)) {
                    set(dx, effSurfaceY + 1, dz, Blocks.SNOW.defaultBlockState())
                }
            }

            // BARRERAS PERIMETRALES INVISIBLES SOBRE LA CRESTA DE LA MONTAÑA
            if (distCenter >= MOUNTAIN_CREST_RADIUS - 1.5) {
                for (y in (effSurfaceY + 1)..135) {
                    set(dx, y, dz, Blocks.BARRIER.defaultBlockState())
                }
            }
        }

        // 2. ALTAR CENTRAL DE REFLEXIÓN (Sin losas, bloques sólidos pulidos)
        buildCentralAltar(level, cx, cz)

        // 3. ARBOLEDA Y FLORA NATURAL (Robles, Abedules, Flores de Pradera y Nenúfares)
        val features = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
        fun tree(key: ResourceKey<ConfiguredFeature<*, *>>, dx: Int, dz: Int, y: Int) {
            features.getHolder(key).ifPresent {
                it.value().place(level, level.chunkSource.generator, random, BlockPos(cx + dx, y, cz + dz))
            }
        }

        // Árboles en la pradera (Robles y Abedules)
        val placedTrees = mutableListOf<Pair<Int, Int>>()
        var attempts = 0
        while (placedTrees.size < 7 && attempts++ < 80) {
            val angle = random.nextDouble() * Math.PI * 2
            val r = 11.0 + random.nextDouble() * (MEADOW_RADIUS - 16.0)
            val tx = (cos(angle) * r).toInt()
            val tz = (sin(angle) * r).toInt()

            if (abs(tx) <= 6 && abs(tz) <= 6) continue
            if (lakeDist(lake, tx, tz) < lake.radius + 3.0) continue
            if (placedTrees.any { (px, pz) -> (px - tx) * (px - tx) + (pz - tz) * (pz - tz) < 40 }) continue

            placedTrees += tx to tz
            val treeType = if (random.nextFloat() < 0.45f) TreeFeatures.FANCY_OAK else if (random.nextFloat() < 0.75f) TreeFeatures.BIRCH else TreeFeatures.OAK
            tree(treeType, tx, tz, BASE + 1)
        }

        // Árboles en faldas montañosas (Pinos y Abetos)
        for (i in 0..4) {
            val angle = (i * (Math.PI * 2 / 5.0)) + (random.nextDouble() - 0.5) * 0.4
            val r = MEADOW_RADIUS + 2.0 + random.nextDouble() * 4.0
            val tx = (cos(angle) * r).toInt()
            val tz = (sin(angle) * r).toInt()
            tree(TreeFeatures.SPRUCE, tx, tz, BASE + 3)
        }

        // 4. VEGETACIÓN DE PRADERA (Flores naturales, césped y nenúfares)
        val flowers = listOf(Blocks.DANDELION, Blocks.POPPY, Blocks.CORNFLOWER, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY)
        for (dx in -BOUND..BOUND) for (dz in -BOUND..BOUND) {
            val distCenter = sqrt((dx * dx + dz * dz).toDouble())
            if (distCenter > MEADOW_RADIUS) continue

            // Altar central despejado
            if (abs(dx) <= 4 && abs(dz) <= 4) continue

            val ground = level.getBlockState(pos.set(cx + dx, BASE, cz + dz))
            val above = pos.set(cx + dx, BASE + 1, cz + dz).immutable()

            // Nenúfares en el lago
            val lakeWater = level.getBlockState(pos.set(cx + dx, BASE - 1, cz + dz))
            if (lakeWater.`is`(Blocks.WATER) && ground.isAir && random.nextFloat() < 0.08f) {
                level.setBlock(pos.set(cx + dx, BASE, cz + dz), Blocks.LILY_PAD.defaultBlockState(), FLAGS)
                continue
            }

            if (!ground.`is`(Blocks.GRASS_BLOCK) || !level.getBlockState(above).isAir) continue

            val roll = random.nextFloat()
            when {
                roll < 0.02f && level.getBlockState(above.above()).isAir ->
                    DoublePlantBlock.placeAt(level, Blocks.TALL_GRASS.defaultBlockState(), above, FLAGS)
                roll < 0.20f -> level.setBlock(above, Blocks.SHORT_GRASS.defaultBlockState(), FLAGS)
                roll < 0.28f -> {
                    val flowerBlock = flowers[random.nextInt(flowers.size)]
                    level.setBlock(above, flowerBlock.defaultBlockState(), FLAGS)
                }
            }
        }
    }

    /**
     * Construye el altar central de reflexión rúnica con bloques completos limpios (cero losas).
     */
    private fun buildCentralAltar(level: ServerLevel, cx: Int, cz: Int) {
        val pos = BlockPos.MutableBlockPos()
        fun setA(dx: Int, y: Int, dz: Int, state: BlockState) {
            level.setBlock(pos.set(cx + dx, y, cz + dz), state, FLAGS)
        }

        // Nivel suelo: Explanada de Cuarzo Cincelado / Cuarzo Liso (radio 3)
        for (dx in -3..3) for (dz in -3..3) {
            val r = sqrt((dx * dx + dz * dz).toDouble())
            if (r <= 3.4) {
                val baseBlock = if (r > 2.2) Blocks.SMOOTH_QUARTZ.defaultBlockState() else Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState()
                setA(dx, BASE, dz, baseBlock)
            }
        }

        // 4 Pilares de las Esquinas con Linternas del Alma
        val cornerCoords = listOf(Pair(-2, -2), Pair(2, -2), Pair(-2, 2), Pair(2, 2))
        for ((px, pz) in cornerCoords) {
            setA(px, BASE + 1, pz, Blocks.POLISHED_DEEPSLATE.defaultBlockState())
            setA(px, BASE + 2, pz, Blocks.CHISELED_STONE_BRICKS.defaultBlockState())
            setA(px, BASE + 3, pz, Blocks.SOUL_LANTERN.defaultBlockState())
        }

        // Monolito Central de Cristal y Obsidiana Llorosa (dx=0, dz=0)
        setA(0, BASE + 1, 0, Blocks.LODESTONE.defaultBlockState())
        setA(0, BASE + 2, 0, Blocks.CRYING_OBSIDIAN.defaultBlockState())
        setA(0, BASE + 3, 0, Blocks.CALCITE.defaultBlockState())
        setA(0, BASE + 4, 0, Blocks.CRYING_OBSIDIAN.defaultBlockState())
        setA(0, BASE + 5, 0, Blocks.SEA_LANTERN.defaultBlockState())
        setA(0, BASE + 6, 0, Blocks.AMETHYST_CLUSTER.defaultBlockState())

        // Espejo de Agua/Cristal de Reminiscencia al pie del monolito
        setA(0, BASE + 1, 1, Blocks.TINTED_GLASS.defaultBlockState())
    }
}
