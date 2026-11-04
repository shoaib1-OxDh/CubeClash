package com.oxdh.cubeclash.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombatTest {
    private fun snap(id: Int, level: Int, total: Double = Math.pow(2.0, level.toDouble()), prot: Boolean = false) =
        ChainSnapshot(id, level, total, protected = prot)

    @Test fun outcomes() {
        val r = RulesConfig()
        assertEquals(CombatOutcome.DEFENDER_DEFEATED, CombatResolver.resolve(snap(1, 4), snap(2, 3), 0, r))
        assertEquals(CombatOutcome.DEFENDER_SEVERED, CombatResolver.resolve(snap(1, 4), snap(2, 3), 2, r))
        assertEquals(CombatOutcome.DEFENDER_DEFEATED, CombatResolver.resolve(snap(1, 4), snap(2, 3), 2, r.copy(severOnBodyHit = false)))
        assertEquals(CombatOutcome.ATTACKER_DEFEATED, CombatResolver.resolve(snap(1, 2), snap(2, 5), 3, r))
        assertEquals(CombatOutcome.BOUNCE, CombatResolver.resolve(snap(1, 3), snap(2, 3), 0, r))
        assertEquals(CombatOutcome.BOTH_DEFEATED, CombatResolver.resolve(snap(1, 3), snap(2, 3), 0, r.copy(tieRule = TieRule.BOTH_DEFEATED)))
        assertEquals(CombatOutcome.NONE, CombatResolver.resolve(snap(1, 3), snap(2, 3), 0, r.copy(tieRule = TieRule.IGNORE)))
        val tv = r.copy(strengthMetric = StrengthMetric.TOTAL_VALUE)
        assertEquals(CombatOutcome.ATTACKER_DEFEATED, CombatResolver.resolve(snap(1, 3, 8.0), snap(2, 2, 30.0), 0, tv))
    }

    @Test fun protectionAndPermissions() {
        val r = RulesConfig()
        assertEquals(CombatOutcome.BOUNCE, CombatResolver.resolve(snap(1, 5), snap(2, 1, prot = true), 0, r))
        assertEquals(CombatOutcome.BOUNCE, CombatResolver.resolve(snap(1, 1).copy(canBeDefeated = false), snap(2, 6), 0, r))
        assertEquals(CombatOutcome.BOUNCE, CombatResolver.resolve(snap(1, 1), snap(2, 6).copy(canDefeatOthers = false), 0, r))
        assertEquals(CombatOutcome.NONE, CombatResolver.resolve(snap(1, 5), snap(2, 1).copy(alive = false), 0, r))
        assertEquals(CombatOutcome.NONE, CombatResolver.resolve(snap(1, 5), snap(1, 5), 0, r))
    }

    @Test fun sameFrameContactsResolveOncePerChain() {
        val snaps = mapOf(1 to snap(1, 6), 2 to snap(2, 2), 3 to snap(3, 3))
        val contacts = listOf(
            Contact(1, 2, 0, 0.3f), Contact(1, 2, 1, 0.2f), Contact(2, 1, 3, 0.25f), Contact(1, 3, 0, 0.1f))
        val out = mutableListOf<ResolvedContact>()
        CombatResolver.resolveFrame(contacts, { snaps[it] }, RulesConfig(), out)
        assertEquals(1, out.size)
        assertEquals(CombatOutcome.DEFENDER_DEFEATED, out[0].outcome)
        assertEquals(2, out[0].contact.defenderId)

        val snaps2 = mapOf(1 to snap(1, 6), 2 to snap(2, 2), 3 to snap(3, 3), 4 to snap(4, 1))
        CombatResolver.resolveFrame(listOf(Contact(1, 2, 0, 0.1f), Contact(3, 4, 0, 0.1f)), { snaps2[it] }, RulesConfig(), out)
        assertEquals(2, out.size)
    }

    @Test fun dropCount() {
        assertEquals(5, CombatResolver.dropCount(5, 1f)); assertEquals(3, CombatResolver.dropCount(5, 0.5f))
        assertEquals(0, CombatResolver.dropCount(5, 0f)); assertEquals(0, CombatResolver.dropCount(0, 1f))
        assertEquals(5, CombatResolver.dropCount(5, 7f))
    }
}

class AITest {
    private fun ctx() = AIContext().apply { position = Vec2.ZERO; forward = Vec2.UNIT_Y; headLevel = 2; boostEnergy01 = 1f; arenaRadius = 100f }
    private fun perfect() = AIBehaviourConfig.forDifficulty(AIDifficulty.HARD).copy(decisionNoise = 0f, mistakeChance = 0f, threatAwareness = 1f)

    @Test fun prefersValuableEligible() {
        val c = ctx()
        c.collectibles += AICollectibleInfo(1, Vec2(10f, 0f), 0, true)
        c.collectibles += AICollectibleInfo(2, Vec2(0f, 6f), 2, true)
        c.collectibles += AICollectibleInfo(3, Vec2(0f, 2f), 6, false)
        val d = AITargetSelector.decide(c, perfect(), AIBrainMemory(), Rng(1))
        assertEquals(AIState.COLLECT, d.state); assertEquals(2, d.targetId)
    }

    @Test fun flees() {
        val c = ctx()
        c.collectibles += AICollectibleInfo(1, Vec2(0f, 5f), 2, true)
        c.rivals += AIRivalInfo(9, Vec2(0f, 3f), strengthComparison = 1, isDangerous = true)
        val d = AITargetSelector.decide(c, perfect(), AIBrainMemory(), Rng(1))
        assertEquals(AIState.FLEE, d.state); assertTrue(d.direction.y < -0.9f); assertTrue(d.boost)
    }

    @Test fun chasesWhenAggressiveButNotWhenPassive() {
        val c = ctx()
        c.rivals += AIRivalInfo(9, Vec2(5f, 0f), strengthComparison = -1, totalValue = 16.0, isPrey = true)
        assertEquals(AIState.CHASE, AITargetSelector.decide(c, perfect().copy(aggression = 1f), AIBrainMemory(), Rng(1)).state)
        assertNotEquals(AIState.CHASE, AITargetSelector.decide(c, perfect().passive(), AIBrainMemory(), Rng(1)).state)
    }

    @Test fun boundary() {
        val c = ctx(); c.position = Vec2(98f, 0f)
        assertTrue(AITargetSelector.decide(c, perfect(), AIBrainMemory(), Rng(3)).direction.x < 0f)
    }

    @Test fun presetsOrdered() {
        val e = AIBehaviourConfig.forDifficulty(AIDifficulty.EASY); val n = AIBehaviourConfig.forDifficulty(AIDifficulty.NORMAL)
        val h = AIBehaviourConfig.forDifficulty(AIDifficulty.HARD)
        assertTrue(e.speedMultiplier < n.speedMultiplier && n.speedMultiplier < h.speedMultiplier)
        assertTrue(e.reactionTime > n.reactionTime && n.reactionTime > h.reactionTime)
        assertTrue(e.aggression < n.aggression && n.aggression < h.aggression)
        assertTrue(n.ramped(1f).aggression > n.aggression)
    }

    @Test fun finiteUnitDirections() {
        val rng = Rng(5); val mem = AIBrainMemory(); val cfg = AIBehaviourConfig.forDifficulty(AIDifficulty.NORMAL)
        repeat(500) {
            val c = ctx(); c.position = rng.insideDisc(110f)
            repeat(5) { k -> c.collectibles += AICollectibleInfo(k, c.position + rng.insideDisc(20f), rng.range(0, 5), rng.chance(0.6f)) }
            c.rivals += AIRivalInfo(1, c.position + rng.insideDisc(15f), strengthComparison = rng.range(-1, 2), totalValue = 8.0, isDangerous = true, isPrey = true)
            c.obstacles += AIObstacleInfo(c.position + rng.insideDisc(6f), 2f)
            val d = AITargetSelector.decide(c, cfg, mem, rng)
            assertFalse(d.direction.x.isNaN() || d.direction.y.isNaN())
            assertEquals(1f, d.direction.length, 1e-3f)
        }
    }
}

class SaveTest {
    @Test fun sanitizeRepairsCorruptData() {
        val p = PlayerProgress(version = -4, bestScore = -10, highestLevel = 99999, coins = -1, totalPlayTimeSeconds = Double.NaN,
            unlockedSkins = mutableListOf(), selectedSkin = "nope", unlockedTrails = mutableListOf("spark", "spark", "bogus"),
            modeBests = mutableListOf(ModeBest(7), ModeBest(1, bestScore = -3)))
        SaveValidator.sanitize(p)
        assertEquals(PlayerProgress.CURRENT_VERSION, p.version); assertEquals(0L, p.bestScore)
        assertEquals(NumberValue.MAX_LEVEL, p.highestLevel); assertEquals(0L, p.coins); assertEquals(0.0, p.totalPlayTimeSeconds, 0.0)
        assertEquals(listOf("classic"), p.unlockedSkins); assertEquals(listOf("spark"), p.unlockedTrails); assertEquals("classic", p.selectedSkin)
        assertEquals(1, p.modeBests.size); assertEquals(0L, p.modeBests[0].bestScore)
    }

    @Test fun applySession() {
        val p = SaveValidator.sanitize(PlayerProgress())
        val s = SessionStats(); s.reset(0); s.addScore(2000); s.observeChain(8, 6); s.recordCollect(); s.recordMerge(); s.recordDefeat(); s.addTime(65.0)
        val r = ProgressRules.applySession(p, s, GameMode.CLASSIC)
        assertTrue(r.newBestScore); assertTrue(r.newHighestLevel)
        assertEquals(2000L, p.bestScore); assertEquals(8, p.highestLevel); assertEquals(6, p.bestChainLength); assertEquals(1, p.gamesPlayed)
        assertTrue("pastel" in r.newUnlocks); assertTrue("neon" in r.newUnlocks); assertTrue(p.coins > 0)
        val s2 = SessionStats(); s2.reset(0); s2.addScore(10)
        val r2 = ProgressRules.applySession(p, s2, GameMode.CLASSIC)
        assertFalse(r2.newBestScore); assertEquals(2000L, r2.previousBestScore); assertEquals(2000L, p.bestScore); assertEquals(2, p.gamesPlayed)
    }

    @Test fun modes() {
        val s = GameModeSettings.forMode(GameMode.SURVIVAL_CHALLENGE)
        assertEquals(0f, s.rampAt(0.0)); assertEquals(1f, s.rampAt(3600.0))
        assertTrue(s.rivalMaxLevelAt(300.0) > s.rivalMaxLevelAt(0.0))
        val e = GameModeSettings.forMode(GameMode.ENDLESS_GROWTH)
        assertFalse(e.playerCanBeDefeated); assertFalse(e.rivalsAggressive)
    }
}
