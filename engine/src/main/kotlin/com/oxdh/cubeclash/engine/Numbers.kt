package com.oxdh.cubeclash.engine

import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round

/**
 * Every value in the game is a power of two, stored as its exponent ("level"): level 0 = 1, 1 = 2, 2 = 4 ...
 * Merging is level + 1, which is exact and overflow-free for any reachable value.
 */
object NumberValue {
    /** Highest level whose value still fits exactly in a signed 64-bit integer. */
    const val MAX_EXACT_LEVEL = 62
    /** Hard upper bound to keep display and score maths sane. */
    const val MAX_LEVEL = 1000

    fun valueOf(level: Int): Double = 2.0.pow(level)

    fun exactOrSaturated(level: Int): Long = if (level <= MAX_EXACT_LEVEL) 1L shl level else Long.MAX_VALUE

    /** Level of a positive power of two, or null for anything else. */
    fun levelOf(value: Long): Int? {
        if (value <= 0 || (value and (value - 1)) != 0L) return null
        return 63 - java.lang.Long.numberOfLeadingZeros(value)
    }
}

object NumberFormat {
    private val suffixes = arrayOf("", "K", "M", "B", "T")

    /** Short cube label (max 5 chars): exact up to 8192, then K/M/B/T, then "2^N". */
    fun formatLevel(level: Int): String {
        val l = if (level < 0) 0 else level
        if (l <= 13) return (1L shl l).toString()
        if (l >= 50) return "2^$l"
        return abbreviate(2.0.pow(l))
    }

    /** 999 -> "999", 16384 -> "16K", 2097152 -> "2M", 1.5e6 -> "1.5M". */
    fun abbreviate(value: Double): String {
        var v = if (value.isNaN() || value < 0) 0.0 else value
        if (v < 10000) return floor(v).toLong().toString()
        var tier = 0
        while (v >= 1000 && tier < suffixes.size - 1) { v /= 1000.0; tier++ }
        if (v >= 1000) return String.format(Locale.US, "%.1e", v)
        val number = if (v < 10) {
            val t = floor(v * 10) / 10
            if (t == floor(t)) t.toLong().toString() else String.format(Locale.US, "%.1f", t)
        } else floor(v).toLong().toString()
        return number + suffixes[tier]
    }

    fun abbreviate(value: Long): String = abbreviate(value.toDouble())

    /** HUD score: thousands separators below 10M, abbreviated after. */
    fun formatScore(score: Long): String {
        val s = if (score < 0) 0 else score
        return if (s < 10_000_000) String.format(Locale.US, "%,d", s) else abbreviate(s.toDouble())
    }

    fun formatDuration(seconds: Double): String {
        val total = if (seconds.isNaN() || seconds < 0) 0L else seconds.toLong()
        val h = total / 3600; val m = (total / 60) % 60; val sec = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }
}

object SaturatingMath {
    fun add(a: Long, b: Long): Long {
        val r = a + b
        if (((a xor r) and (b xor r)) < 0) return if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE
        return r
    }
}

/** How collected cubes interact with the chain. See docs/GAME_RULES.md. */
enum class MergeMode {
    /** Mode A: insert, then every equal adjacent pair merges immediately. */
    STANDARD,
    /** Mode B: a cube equal to a chain cube doubles that cube; otherwise it is added. No cascades. */
    CHAIN_COLLECTION,
    /** Mode C (default): the cube is added; equal neighbours then merge one step at a time on a timer. */
    HYBRID,
}

enum class CollectEligibility { LESS_OR_EQUAL_TO_HEAD, ANY_VALUE, MATCHING_CHAIN_VALUE }

/** What happens with an eligible collected cube strictly smaller than the leading cube. */
enum class SmallerCubeBehaviour { ADD_BODY_CUBE, FEED_LEADER, SCORE_ONLY }

enum class StrengthMetric { HEAD_VALUE, TOTAL_VALUE }

enum class TieRule { BOUNCE, BOTH_DEFEATED, IGNORE }

/** All tunable progression, merging, scoring and combat rules. */
data class RulesConfig(
    var mergeMode: MergeMode = MergeMode.HYBRID,
    var eligibility: CollectEligibility = CollectEligibility.LESS_OR_EQUAL_TO_HEAD,
    var smallerCubeBehaviour: SmallerCubeBehaviour = SmallerCubeBehaviour.ADD_BODY_CUBE,
    /** Seconds between Hybrid merge steps; <= 0 resolves instantly. */
    var hybridMergeInterval: Float = 0.18f,
    /** Max merges per Hybrid step (each cube merges at most once per step). */
    var hybridMergesPerStep: Int = 1,
    /** When exceeded the smallest (tail) cube is shed and converted to score. */
    var maxChainLength: Int = 40,
    var startLevel: Int = 0,
    var scorePerCollectedValue: Float = 1f,
    var scorePerMergedValue: Float = 1f,
    var scoreDefeatValueFraction: Float = 0.5f,
    var scoreDefeatFlatBonus: Long = 50,
    var scoreShedValueFraction: Float = 0.5f,
    var strengthMetric: StrengthMetric = StrengthMetric.HEAD_VALUE,
    var tieRule: TieRule = TieRule.BOUNCE,
    /** Stronger head hitting a weaker body severs the chain there instead of defeating it. */
    var severOnBodyHit: Boolean = true,
    /** Fraction (0..1) of a defeated / severed chain's cubes dropped as collectibles. */
    var dropFraction: Float = 1f,
    var spawnProtectionSeconds: Float = 3f,
) {
    fun sanitize(): RulesConfig {
        if (hybridMergesPerStep < 1) hybridMergesPerStep = 1
        if (maxChainLength < 1) maxChainLength = 1
        startLevel = MathUtil.clamp(startLevel, 0, NumberValue.MAX_LEVEL)
        if (scorePerCollectedValue < 0) scorePerCollectedValue = 0f
        if (scorePerMergedValue < 0) scorePerMergedValue = 0f
        if (scoreDefeatValueFraction < 0) scoreDefeatValueFraction = 0f
        if (scoreDefeatFlatBonus < 0) scoreDefeatFlatBonus = 0
        if (scoreShedValueFraction < 0) scoreShedValueFraction = 0f
        dropFraction = MathUtil.clamp01(dropFraction)
        if (spawnProtectionSeconds < 0) spawnProtectionSeconds = 0f
        return this
    }
}

/** Score rules — every score comes from the action actually performed. */
object ScoreCalculator {
    fun forCollect(level: Int, r: RulesConfig) = toScore(NumberValue.valueOf(level) * r.scorePerCollectedValue)
    fun forMerge(resultLevel: Int, r: RulesConfig) = toScore(NumberValue.valueOf(resultLevel) * r.scorePerMergedValue)
    fun forShed(level: Int, r: RulesConfig) = toScore(NumberValue.valueOf(level) * r.scoreShedValueFraction)
    fun forDefeat(victimTotalValue: Double, r: RulesConfig) =
        SaturatingMath.add(toScore(victimTotalValue * r.scoreDefeatValueFraction), r.scoreDefeatFlatBonus)

    fun sumEvents(events: List<ChainEvent>): Long = events.fold(0L) { acc, e -> SaturatingMath.add(acc, e.score) }

    fun toScore(value: Double): Long {
        if (value.isNaN() || value <= 0) return 0
        if (value >= Long.MAX_VALUE.toDouble()) return Long.MAX_VALUE
        return round(value).toLong()
    }
}

object RankCalculator {
    /** 1-based rank of [index] in [scores] (descending; ties favour the lower index). */
    fun rankOf(scores: List<Long>, index: Int): Int {
        if (index < 0 || index >= scores.size) return 0
        val mine = scores[index]
        var rank = 1
        for (i in scores.indices) {
            if (i == index) continue
            if (scores[i] > mine || (scores[i] == mine && i < index)) rank++
        }
        return rank
    }
}
