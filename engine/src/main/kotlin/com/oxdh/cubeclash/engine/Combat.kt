package com.oxdh.cubeclash.engine

import kotlin.math.roundToInt

/** Immutable view of a chain for the combat rules. */
data class ChainSnapshot(
    val chainId: Int,
    val headLevel: Int,
    val totalValue: Double,
    val length: Int = 1,
    val alive: Boolean = true,
    /** Spawn protection: cannot be defeated and cannot defeat. */
    val protected: Boolean = false,
    val canBeDefeated: Boolean = true,
    val canDefeatOthers: Boolean = true,
)

enum class CombatOutcome {
    NONE,
    /** The attacker's head reached the defender's head and may eat it: the whole defender is eaten. */
    DEFENDER_DEFEATED,
    /** The attacker ate the touched body cube and everything behind it. */
    DEFENDER_SEVERED,
    /** The attacker may not eat what it touched: it is stopped like by a wall (solid contact). */
    BLOCKED,
    /** Head-to-head without an eat: both are pushed apart. */
    BOUNCE,
    /** Equal heads with TieRule.BOTH_DEFEATED. */
    BOTH_DEFEATED,
}

/** Attacker's HEAD (front) touched the defender's cube at [defenderSegmentIndex] (0 = head) whose level is [segmentLevel]. */
data class Contact(val attackerId: Int, val defenderId: Int, val defenderSegmentIndex: Int, val penetration: Float, val segmentLevel: Int = -1)

data class ResolvedContact(val contact: Contact, val outcome: CombatOutcome)

/**
 * Pure number-based eating rules. A chain's HEAD is its mouth; its number is the head cube's value.
 *  - A head can eat any cube whose number is SMALLER than its own head number (equal or bigger: never).
 *    Eating the head eats the whole snake; eating a body cube eats that cube and everything behind it.
 *  - Anything it may not eat is solid: the attacker is BLOCKED (or both BOUNCE when heads meet).
 * Speed, boosting or standing still never matter — only the numbers do. Protection / mode permissions turn
 * an eat into a solid contact.
 */
object CombatResolver {
    fun compareStrength(a: ChainSnapshot, b: ChainSnapshot, metric: StrengthMetric): Int =
        if (metric == StrengthMetric.TOTAL_VALUE) a.totalValue.compareTo(b.totalValue) else a.headLevel.compareTo(b.headLevel)

    /** Can a head of [attackerHeadLevel] eat a cube of [targetLevel]? Strictly smaller only. */
    fun canEat(attackerHeadLevel: Int, targetLevel: Int) = attackerHeadLevel > targetLevel

    fun resolve(attacker: ChainSnapshot, defender: ChainSnapshot, defenderSegmentIndex: Int, segmentLevel: Int, rules: RulesConfig): CombatOutcome {
        if (!attacker.alive || !defender.alive || attacker.chainId == defender.chainId) return CombatOutcome.NONE
        val target = if (segmentLevel >= 0) segmentLevel else defender.headLevel
        val head = defenderSegmentIndex <= 0
        val protectedAny = attacker.protected || defender.protected
        val eat = when (rules.strengthMetric) {
            StrengthMetric.HEAD_VALUE -> canEat(attacker.headLevel, target)
            StrengthMetric.TOTAL_VALUE -> attacker.totalValue > defender.totalValue && attacker.headLevel >= target
        }
        if (eat && !protectedAny && attacker.canDefeatOthers && defender.canBeDefeated)
            return if (head || !rules.severOnBodyHit) CombatOutcome.DEFENDER_DEFEATED else CombatOutcome.DEFENDER_SEVERED
        if (head && attacker.headLevel == defender.headLevel && !protectedAny) {
            when (rules.tieRule) {
                TieRule.BOTH_DEFEATED -> if (attacker.canBeDefeated && defender.canBeDefeated) return CombatOutcome.BOTH_DEFEATED
                TieRule.IGNORE -> return CombatOutcome.NONE
                TieRule.BOUNCE -> {}
            }
        }
        return if (head) CombatOutcome.BOUNCE else CombatOutcome.BLOCKED
    }

    /** Backwards-compatible overload: contact with the defender's cube at [defenderSegmentIndex] of unknown level (= head level). */
    fun resolve(attacker: ChainSnapshot, defender: ChainSnapshot, defenderSegmentIndex: Int, rules: RulesConfig): CombatOutcome =
        resolve(attacker, defender, defenderSegmentIndex, -1, rules)

    private fun priority(o: CombatOutcome) = when (o) {
        CombatOutcome.DEFENDER_DEFEATED, CombatOutcome.BOTH_DEFEATED -> 3
        CombatOutcome.DEFENDER_SEVERED -> 2
        CombatOutcome.BOUNCE, CombatOutcome.BLOCKED -> 1
        CombatOutcome.NONE -> 0
    }

    private fun isEat(o: CombatOutcome) = priority(o) >= 2

    /**
     * Resolves all contacts of one frame deterministically. Eats are applied first (biggest first); a chain takes
     * part in at most ONE eat per frame (so it can be eaten at most once). Each attacker then gets at most one
     * solid response (blocked / bounce), unless it was eaten.
     */
    fun resolveFrame(contacts: List<Contact>, lookup: (Int) -> ChainSnapshot?, rules: RulesConfig, out: MutableList<ResolvedContact>) {
        out.clear()
        if (contacts.isEmpty()) return
        val evaluated = ArrayList<ResolvedContact>(contacts.size)
        for (c in contacts) {
            val a = lookup(c.attackerId) ?: continue
            val d = lookup(c.defenderId) ?: continue
            val o = resolve(a, d, c.defenderSegmentIndex, c.segmentLevel, rules)
            if (o != CombatOutcome.NONE) evaluated += ResolvedContact(c, o)
        }
        evaluated.sortWith(compareByDescending<ResolvedContact> { priority(it.outcome) }
            .thenByDescending { it.contact.penetration }.thenBy { it.contact.attackerId }
            .thenBy { it.contact.defenderId }.thenBy { it.contact.defenderSegmentIndex })
        val inEat = HashSet<Int>()
        val physical = HashSet<Int>()
        for (r in evaluated) {
            val c = r.contact
            if (isEat(r.outcome)) {
                if (c.attackerId in inEat || c.defenderId in inEat) continue
                inEat += c.attackerId; inEat += c.defenderId
                out += r
            } else {
                if (c.attackerId in inEat || c.defenderId in inEat || c.attackerId in physical) continue
                physical += c.attackerId
                out += r
            }
        }
    }

    fun dropCount(cubeCount: Int, dropFraction: Float): Int {
        if (cubeCount <= 0) return 0
        return MathUtil.clamp((cubeCount * MathUtil.clamp01(dropFraction) + 1e-4f).let { Math.round(it) }, 0, cubeCount)
    }
}
