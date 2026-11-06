package com.oxdh.cubeclash.engine

import kotlin.math.max
import kotlin.math.sqrt

/** Small, fast, deterministic xorshift64* RNG (seedable for tests). */
class Rng(seed: Long = System.nanoTime()) {
    private var state: Long = if (seed == 0L) -0x61c8864680b583ebL else seed

    fun nextLong(): Long {
        state = state xor (state ushr 12)
        state = state xor (state shl 25)
        state = state xor (state ushr 27)
        return state * 0x2545F4914F6CDD1DL
    }

    /** Uniform in [0, 1). */
    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() * (1f / (1 shl 24))
    fun range(min: Float, max: Float) = min + (max - min) * nextFloat()
    /** Uniform int in [min, maxExclusive). */
    fun range(min: Int, maxExclusive: Int): Int {
        if (maxExclusive <= min) return min
        return min + ((nextLong() ushr 1) % (maxExclusive - min).toLong()).toInt()
    }
    fun chance(p: Float) = nextFloat() < p
    fun insideDisc(radius: Float): Vec2 {
        val r = radius * sqrt(nextFloat())
        return Vec2.fromAngle(nextFloat() * MathUtil.TWO_PI) * r
    }
}

data class WeightedLevel(val level: Int, val weight: Float)

/** Normalised weighted distribution over levels; invalid weights count as 0; all-zero returns the lowest level. */
class WeightedTable(entries: List<WeightedLevel>?) {
    private val levels: IntArray
    private val cumulative: FloatArray
    private val total: Float

    init {
        if (entries.isNullOrEmpty()) {
            levels = intArrayOf(0); cumulative = floatArrayOf(1f); total = 1f
        } else {
            levels = IntArray(entries.size); cumulative = FloatArray(entries.size)
            var sum = 0f
            entries.forEachIndexed { i, e ->
                val w = if (e.weight.isNaN() || e.weight < 0) 0f else e.weight
                sum += w
                levels[i] = max(0, e.level)
                cumulative[i] = sum
            }
            total = sum
        }
    }

    val count: Int get() = levels.size
    fun levelAt(i: Int) = levels[i]
    fun probability(i: Int): Float {
        if (total <= 0) return if (i == 0) 1f else 0f
        val prev = if (i == 0) 0f else cumulative[i - 1]
        return (cumulative[i] - prev) / total
    }

    fun sample(rng: Rng): Int {
        if (total <= 0) return levels.min()
        val r = rng.nextFloat() * total
        for (i in cumulative.indices) if (r < cumulative[i]) return levels[i]
        return levels.last()
    }

    companion object {
        /** Design distribution: 1:40, 2:25, 4:16, 8:10, 16:5, 32:3, 64+:1. */
        fun defaultDistribution() = listOf(
            WeightedLevel(0, 40f), WeightedLevel(1, 25f), WeightedLevel(2, 16f), WeightedLevel(3, 10f),
            WeightedLevel(4, 5f), WeightedLevel(5, 3f), WeightedLevel(6, 0.7f), WeightedLevel(7, 0.3f),
        )
    }
}
