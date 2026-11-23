package com.desgraciamon.mirror

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.KeyDispatchDataCodec
import net.minecraft.world.level.levelgen.DensityFunction
import kotlin.math.sqrt

/**
 * Función de densidad procedural que confina la dimensión a un círculo de 500x500 bloques (radio 250).
 * - Dentro del círculo (radio <= innerRadius = 200.0): Eleva el terreno por encima del nivel del mar
 *   (Y=70..85) evitando que el mundo sea mayoritariamente agua y permitiendo llanuras, valles y pequeños lagos/ríos.
 * - Anillo de montañas (radio 200.0 a 245.0): La densidad se dispara verticalmente creando murallas
 *   colosales e inescalables que alcanzan Y=280..300+.
 * - Borde exterior (radio 245.0 a 255.0): Caída abrupta hacia el vacío (-50.0).
 * - Fuera del círculo (radio > 255.0): Densidad negativa profunda (-50.0), nada más se genera (vacío total).
 */
class ParcelBarrierDensityFunction(
    val innerRadius: Double = 200.0,
    val outerRadius: Double = 245.0,
    val wallBonus: Double = 6.0,
    val floorBonus: Double = 0.35
) : DensityFunction.SimpleFunction {

    override fun compute(context: DensityFunction.FunctionContext): Double {
        val x = context.blockX().toDouble()
        val z = context.blockZ().toDouble()
        val y = context.blockY().toDouble()

        // Distancia euclidiana pura desde el centro (0,0) -> Círculo perfecto
        val dist = sqrt(x * x + z * z)

        // Fuera de las montañas: corte absoluto de la dimensión -> Vacío total
        if (dist >= 255.0) {
            return -50.0
        }

        // Borde exterior de las montañas: caída hacia el vacío
        if (dist > outerRadius) {
            val tVoid = ((dist - outerRadius) / (255.0 - outerRadius)).coerceIn(0.0, 1.0)
            return wallBonus * (1.0 - tVoid) - 50.0 * tVoid
        }

        // Anillo de montañas inescalables (entre innerRadius y outerRadius)
        if (dist > innerRadius) {
            val t = ((dist - innerRadius) / (outerRadius - innerRadius)).coerceIn(0.0, 1.0)
            val curve = t * t * (3.0 - 2.0 * t) // Smoothstep
            val mountainHeightBonus = curve * wallBonus
            // También mantenemos el floorBonus en la base para que no haya huecos de agua en las faldas
            return mountainHeightBonus + floorBonus * (1.0 - curve)
        }

        // Interior jugable (dist <= innerRadius):
        // Levantamos el suelo si está cerca o debajo de Y=85 para que la superficie quede en Y=70-80,
        // muy por encima del mar (Y=62/63). Así la mayoría es tierra fértil con pequeños lagos/ríos.
        return if (y < 85.0) {
            val factor = ((85.0 - y) / 35.0).coerceIn(0.0, 1.0)
            floorBonus * factor
        } else {
            0.0
        }
    }

    override fun minValue(): Double = -50.0
    override fun maxValue(): Double = wallBonus + floorBonus
    override fun codec(): KeyDispatchDataCodec<out DensityFunction> = CODEC

    companion object {
        val MAP_CODEC: MapCodec<ParcelBarrierDensityFunction> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.DOUBLE.optionalFieldOf("inner_radius", 200.0).forGetter { it.innerRadius },
                Codec.DOUBLE.optionalFieldOf("outer_radius", 245.0).forGetter { it.outerRadius },
                Codec.DOUBLE.optionalFieldOf("wall_bonus", 6.0).forGetter { it.wallBonus },
                Codec.DOUBLE.optionalFieldOf("floor_bonus", 0.35).forGetter { it.floorBonus }
            ).apply(instance, ::ParcelBarrierDensityFunction)
        }
        val CODEC: KeyDispatchDataCodec<ParcelBarrierDensityFunction> = KeyDispatchDataCodec.of(MAP_CODEC)
    }
}
