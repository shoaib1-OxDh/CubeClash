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

enum class CombatOutcome { NONE, DEFENDER_DEFEATED, DEFENDER_SEVERED, ATTACKER_DEFEATED, BOUNCE, BOTH_DEFEATED }

/** Attacker's HEAD touched the defender's cube at [defenderSegmentIndex] (0 = head). */
data class Contact(val attackerId: Int, val defenderId: Int, val defenderSegmentIndex: Int, val penetration: Float)

data class ResolvedContact(val contact: Contact, val outcome: CombatOutcome)

/**
 * Pure combat rules. A chain's HEAD is its weapon.
 *  - stronger attacker: head hit => defender defeated; body hit => defender severed (or defeated if severOnBodyHit=false)
 *  - weaker attacker: attacker defeated (ran into a bigger chain)
 *  - equal: tie rule (default bounce)
 * Protection and mode permissions downgrade outcomes to BOUNCE.
 */
object CombatResolver {
    fun compareStrength(a: ChainSnapshot, b: ChainSnapshot, metric: StrengthMetric): Int =
        if (metric == StrengthMetric.TOTAL_VALUE) a.totalValue.compareTo(b.totalValue) else a.headLevel.compareTo(b.headLevel)

    fun resolve(attacker: ChainSnapshot, defender: ChainSnapshot, defenderSegmentIndex: Int, rules: RulesConfig): CombatOutcome {
        if (!attacker.alive || !defender.alive || attacker.chainId == defender.chainId) return CombatOutcome.NONE
        if (attacker.protected || defender.protected) return CombatOutcome.BOUNCE
        val cmp = compareStrength(attacker, defender, rules.strengthMetric)
        return when {
            cmp > 0 -> when {
                !attacker.canDefeatOthers || !defender.canBeDefeated -> CombatOutcome.BOUNCE
                defenderSegmentIndex > 0 && rules.severOnBodyHit -> CombatOutcome.DEFENDER_SEVERED
                else -> CombatOutcome.DEFENDER_DEFEATED
            }
            cmp < 0 -> if (!defender.canDefeatOthers || !attacker.canBeDefeated) CombatOutcome.BOUNCE else CombatOutcome.ATTACKER_DEFEATED
            else -> when (rules.tieRule) {
                TieRule.BOTH_DEFEATED -> if (!attacker.canBeDefeated || !defender.canBeDefeated) CombatOutcome.BOUNCE else CombatOutcome.BOTH_DEFEATED
                TieRule.IGNORE -> CombatOutcome.NONE
                TieRule.BOUNCE -> CombatOutcome.BOUNCE
            }
        }
    }

    /**
     * Resolves all contacts of one frame. Each chain takes part in at most ONE resolved contact per frame
     * (so a chain can be defeated at most once); order is deterministic (deepest first, then ids).
     */
    fun resolveFrame(contacts: List<Contact>, lookup: (Int) -> ChainSnapshot?, rules: RulesConfig, out: MutableList<ResolvedContact>) {
        out.clear()
        if (contacts.isEmpty()) return
        val sorted = contacts.sortedWith(compareByDescending<Contact> { it.penetration }
            .thenBy { it.attackerId }.thenBy { it.defenderId }.thenBy { it.defenderSegmentIndex })
        val involved = HashSet<Int>()
        for (c in sorted) {
            if (c.attackerId in involved || c.defenderId in involved) continue
            val a = lookup(c.attackerId) ?: continue
            val d = lookup(c.defenderId) ?: continue
            val outcome = resolve(a, d, c.defenderSegmentIndex, rules)
            if (outcome == CombatOutcome.NONE) continue
            involved += c.attackerId
            involved += c.defenderId
            out += ResolvedContact(c, outcome)
        }
    }

    fun dropCount(cubeCount: Int, dropFraction: Float): Int {
        if (cubeCount <= 0) return 0
        return MathUtil.clamp((cubeCount * MathUtil.clamp01(dropFraction) + 1e-4f).let { Math.round(it) }, 0, cubeCount)
    }
}
