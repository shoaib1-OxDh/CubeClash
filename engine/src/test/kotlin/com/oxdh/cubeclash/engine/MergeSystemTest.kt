package com.oxdh.cubeclash.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeSystemTest {
    private fun chain(start: Int = 0) = ChainModel(IdGenerator(), start)
    private fun hybrid(interval: Float = 0.2f) = MergeSystem(RulesConfig(hybridMergeInterval = interval))

    @Test fun startsWithSingleCubeOfValueOne() {
        val c = chain()
        assertEquals(1, c.length); assertEquals(0, c.headLevel); assertEquals(1.0, c.head!!.value, 0.0)
        assertEquals("[1]", c.toString())
    }

    @Test fun hybridSpecSequence() {
        val m = hybrid(); val c = chain(); val ev = mutableListOf<ChainEvent>()
        assertTrue(m.tryCollect(c, 0, ev)); assertEquals("[1, 1]", c.toString())
        assertEquals(1, m.stepMerges(c, ev)); assertEquals("[2]", c.toString())
        m.tryCollect(c, 1, ev); assertEquals("[2, 2]", c.toString())
        m.stepMerges(c, ev); assertEquals("[4]", c.toString())
    }

    @Test fun hybridCascadesOnePairPerStep() {
        val m = hybrid(); val c = chain(); c.setLevels(listOf(3, 2, 1, 0))
        m.tryCollect(c, 0, null)
        assertEquals("[8, 4, 2, 1, 1]", c.toString())
        for (expected in listOf("[8, 4, 2, 2]", "[8, 4, 4]", "[8, 8]", "[16]")) {
            assertEquals(1, m.stepMerges(c, null)); assertEquals(expected, c.toString())
        }
        assertEquals(0, m.stepMerges(c, null))
    }

    @Test fun standardResolvesImmediately() {
        val m = MergeSystem(RulesConfig(mergeMode = MergeMode.STANDARD)); val c = chain(); c.setLevels(listOf(3, 2, 1, 0))
        val ev = mutableListOf<ChainEvent>()
        m.tryCollect(c, 0, ev)
        assertEquals("[16]", c.toString())
        assertEquals(4, ev.count { it.type == ChainEventType.MERGED })
    }

    @Test fun hybridZeroIntervalIsInstant() {
        val m = hybrid(0f); val c = chain(); m.tryCollect(c, 0, null); assertEquals("[2]", c.toString())
    }

    @Test fun chainCollectionUpgradesWithoutCascade() {
        val m = MergeSystem(RulesConfig(mergeMode = MergeMode.CHAIN_COLLECTION)); val c = chain(); c.setLevels(listOf(2, 1))
        m.tryCollect(c, 1, null); assertEquals("[4, 4]", c.toString())
        assertEquals(0, m.stepMerges(c, null))
        m.tryCollect(c, 0, null); assertEquals("[4, 4, 1]", c.toString()); assertTrue(c.isSorted())
    }

    @Test fun eligibilityRules() {
        val m = MergeSystem(RulesConfig()); val c = chain(1)
        assertTrue(m.canCollect(c, 0)); assertTrue(m.canCollect(c, 1)); assertFalse(m.canCollect(c, 2))
        val ev = mutableListOf<ChainEvent>()
        assertFalse(m.tryCollect(c, 5, ev)); assertTrue(ev.isEmpty()); assertEquals("[2]", c.toString())

        val any = MergeSystem(RulesConfig(eligibility = CollectEligibility.ANY_VALUE)); val c2 = chain(1)
        any.tryCollect(c2, 3, null); assertEquals("[8, 2]", c2.toString())

        val match = MergeSystem(RulesConfig(eligibility = CollectEligibility.MATCHING_CHAIN_VALUE)); val c3 = chain(); c3.setLevels(listOf(3, 1))
        assertTrue(match.canCollect(c3, 1)); assertFalse(match.canCollect(c3, 0)); assertFalse(match.canCollect(c3, 2))
    }

    @Test fun smallerScoreOnly() {
        val m = MergeSystem(RulesConfig(smallerCubeBehaviour = SmallerCubeBehaviour.SCORE_ONLY)); val c = chain(1)
        val ev = mutableListOf<ChainEvent>()
        m.tryCollect(c, 0, ev)
        assertEquals("[2]", c.toString()); assertEquals(ChainEventType.SCORE_ONLY, ev[0].type); assertEquals(1L, ev[0].score)
        m.tryCollect(c, 1, ev); assertEquals("[2, 2]", c.toString())
    }

    @Test fun smallerFeedLeader() {
        val m = MergeSystem(RulesConfig(smallerCubeBehaviour = SmallerCubeBehaviour.FEED_LEADER)); val c = chain(2)
        val ev = mutableListOf<ChainEvent>()
        m.tryCollect(c, 1, ev); assertEquals("[4]", c.toString()); assertEquals(ChainEventType.LEADER_FED, ev[0].type)
        m.tryCollect(c, 0, ev); m.tryCollect(c, 0, ev)
        assertEquals("[8]", c.toString()); assertEquals(0.0, c.leaderProgress, 1e-9); assertEquals(1, c.length)
    }

    @Test fun maxLengthShedsTail() {
        val m = MergeSystem(RulesConfig(maxChainLength = 3, eligibility = CollectEligibility.ANY_VALUE)); val c = chain(); c.setLevels(listOf(5, 3, 1))
        val ev = mutableListOf<ChainEvent>()
        m.tryCollect(c, 0, ev)
        assertEquals("[32, 8, 2]", c.toString())
        assertTrue(ev.any { it.type == ChainEventType.SHED && it.level == 0 })
    }

    @Test fun mergesConserveValueAndOrder() {
        val m = MergeSystem(RulesConfig(maxChainLength = 1000)); val c = chain(); val rng = Rng(1234)
        var collected = 1.0
        repeat(5000) {
            val level = rng.range(0, c.headLevel + 1)
            if (m.tryCollect(c, level, null)) collected += NumberValue.valueOf(level)
            if (rng.chance(0.7f)) m.stepMerges(c, null)
            assertTrue(c.isSorted())
            assertEquals(collected, c.totalValue, collected * 1e-12)
        }
    }

    @Test fun eachCubeMergesAtMostOncePerStep() {
        val m = MergeSystem(RulesConfig(hybridMergesPerStep = 10)); val c = chain(); c.setLevels(listOf(1, 1, 1, 1))
        assertEquals(2, m.stepMerges(c, null)); assertEquals("[4, 4]", c.toString())
        m.stepMerges(c, null); assertEquals("[8]", c.toString())
    }

    @Test fun mergeEventIds() {
        val m = hybrid(); val c = chain(); val headId = c.head!!.id
        val ev = mutableListOf<ChainEvent>()
        m.tryCollect(c, 0, ev); val added = ev[0].cellId; ev.clear()
        m.stepMerges(c, ev)
        assertEquals(headId, ev[0].cellId); assertEquals(added, ev[0].otherCellId); assertEquals(headId, c.head!!.id)
    }

    @Test fun veryHighValues() {
        val m = MergeSystem(RulesConfig(mergeMode = MergeMode.STANDARD)); val c = chain(70)
        m.tryCollect(c, 70, null)
        assertEquals(71, c.headLevel); assertEquals(Math.pow(2.0, 71.0), c.head!!.value, 0.0)
        assertEquals(Long.MAX_VALUE, NumberValue.exactOrSaturated(71))
        assertEquals("2^71", NumberFormat.formatLevel(71))
        assertEquals(Long.MAX_VALUE, ScoreCalculator.forMerge(200, RulesConfig()))
    }

    @Test fun rapidCollection() {
        val m = hybrid(); val c = chain(3)
        repeat(8) { m.tryCollect(c, 0, null) }
        assertEquals(9, c.length)
        m.resolveAllMerges(c, null)
        assertEquals("[16]", c.toString())
    }
}
