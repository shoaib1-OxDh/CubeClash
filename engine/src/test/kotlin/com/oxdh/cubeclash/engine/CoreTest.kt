package com.oxdh.cubeclash.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CoreTest {
    @Test fun formatLevels() {
        val cases = mapOf(0 to "1", 1 to "2", 6 to "64", 11 to "2048", 13 to "8192", 14 to "16K", 19 to "524K",
            20 to "1M", 21 to "2M", 30 to "1B", 40 to "1T", 49 to "562T", 50 to "2^50")
        for ((l, s) in cases) assertEquals("level $l", s, NumberFormat.formatLevel(l))
        for (l in 0..NumberValue.MAX_LEVEL) assertTrue(NumberFormat.formatLevel(l).length <= 6)
        assertEquals("1,234", NumberFormat.formatScore(1234))
        assertEquals("1:05", NumberFormat.formatDuration(65.0))
    }

    @Test fun levelOf() {
        assertEquals(0, NumberValue.levelOf(1)); assertEquals(10, NumberValue.levelOf(1024))
        assertNull(NumberValue.levelOf(3)); assertNull(NumberValue.levelOf(0)); assertNull(NumberValue.levelOf(-8))
    }

    @Test fun scoring() {
        val r = RulesConfig()
        assertEquals(4L, ScoreCalculator.forCollect(2, r)); assertEquals(8L, ScoreCalculator.forMerge(3, r))
        assertEquals(60L, ScoreCalculator.forDefeat(20.0, r)); assertEquals(0L, ScoreCalculator.toScore(Double.NaN))
        assertEquals(Long.MAX_VALUE, ScoreCalculator.toScore(1e30))
        assertEquals(Long.MAX_VALUE, SaturatingMath.add(Long.MAX_VALUE, 1)); assertEquals(5L, SaturatingMath.add(2, 3))
        val m = MergeSystem(r); val c = ChainModel(IdGenerator()); val s = SessionStats(); s.reset(0)
        val ev = mutableListOf<ChainEvent>()
        m.tryCollect(c, 0, ev); m.stepMerges(c, ev); s.apply(ev)
        assertEquals(3L, s.score); assertEquals(1, s.merges)
    }

    @Test fun rank() {
        val scores = listOf(50L, 100L, 10L, 100L)
        assertEquals(3, RankCalculator.rankOf(scores, 0)); assertEquals(1, RankCalculator.rankOf(scores, 1))
        assertEquals(2, RankCalculator.rankOf(scores, 3)); assertEquals(4, RankCalculator.rankOf(scores, 2))
    }

    @Test fun weightedTable() {
        val t = WeightedTable(WeightedTable.defaultDistribution())
        assertEquals(1f, (0 until t.count).sumOf { t.probability(it).toDouble() }.toFloat(), 1e-4f)
        val rng = Rng(42); val counts = IntArray(16); val n = 200_000
        repeat(n) { counts[t.sample(rng)]++ }
        assertEquals(0.40, counts[0] / n.toDouble(), 0.01); assertEquals(0.25, counts[1] / n.toDouble(), 0.01)
        assertEquals(0.16, counts[2] / n.toDouble(), 0.01)
        assertEquals(2, WeightedTable(listOf(WeightedLevel(3, -1f), WeightedLevel(2, Float.NaN))).sample(Rng(1)))
        assertEquals(0, WeightedTable(null).sample(Rng(1)))
    }

    @Test fun boostEnergy() {
        val b = BoostMeter(BoostConfig(drainPerSecond = 0.5f, regenPerSecond = 0.25f, regenDelay = 0.5f, minEnergyToStart = 0.2f))
        repeat(10) { b.update(0.1f, true) }
        assertEquals(0.5f, b.energy, 1e-4f); assertTrue(b.isBoosting)
        repeat(10) { b.update(0.1f, true) }
        assertFalse(b.isBoosting); assertEquals(0f, b.energy, 1e-4f)
        b.update(0.1f, true); assertFalse(b.isBoosting)
        repeat(3) { b.update(0.1f, false) }; assertEquals(0f, b.energy, 1e-4f)
        repeat(11) { b.update(0.1f, false) }
        assertEquals(0.25f, b.energy, 0.03f)
    }

    @Test fun boostCooldown() {
        val b = BoostMeter(BoostConfig(mode = BoostMode.COOLDOWN, cooldownBoostDuration = 1f, cooldownSeconds = 2f))
        b.update(0.1f, true); assertTrue(b.isBoosting)
        repeat(10) { b.update(0.1f, true) }; assertFalse(b.isBoosting)
        b.update(0.1f, true); assertFalse(b.isBoosting)
        repeat(20) { b.update(0.1f, false) }
        b.update(0.1f, true); assertTrue(b.isBoosting)
    }

    @Test fun pathTrailStraight() {
        val t = PathTrail(0.1f, 20f); t.reset(Vec2.ZERO, Vec2.UNIT_Y, 10f)
        for (i in 1..100) t.moveHead(0f, i * 0.05f)
        assertEquals(5f, t.headY, 1e-4f)
        var d = 0f
        while (d < 10f) { val p = t.sample(d); assertEquals(0f, p.x, 1e-3f); assertEquals(5f - d, p.y, 0.02f); d += 0.37f }
    }

    @Test fun pathTrailCircle() {
        val t = PathTrail(0.1f, 30f); val r = 3f
        t.reset(Vec2(r, 0f), Vec2.UNIT_Y, 1f)
        for (i in 0..2000) { val a = i / 2000f * MathUtil.TWO_PI * 2f; t.moveHead(cos(a) * r, sin(a) * r) }
        var d = 0f
        while (d < 15f) { assertEquals(r, t.sample(d).length, 0.05f); d += 0.5f }
    }

    @Test fun pathTrailClampGrowTeleport() {
        val t = PathTrail(0.5f, 2f); t.reset(Vec2.ZERO, Vec2.UNIT_X, 2f)
        val far = t.sample(1000f); assertFalse(far.x.isNaN()); assertTrue(far.length <= 3f)
        val g = PathTrail(0.1f, 1f); g.reset(Vec2.ZERO, Vec2.UNIT_X, 1f)
        for (i in 1..30) g.moveHead(i * 0.1f, 0f)
        g.ensureLength(50f); assertTrue(g.capacity >= 500); assertEquals(2f, g.sample(1f).x, 0.02f)
        val tp = PathTrail(0.1f, 2f); tp.reset(Vec2.ZERO, Vec2.UNIT_X, 2f); tp.moveHead(1000f, 0f)
        assertEquals(1000f, tp.headX); assertEquals(999f, tp.sample(1f).x, 0.05f)
    }

    @Test fun chainSeverAndIds() {
        val c = ChainModel(IdGenerator()); c.setLevels(listOf(5, 4, 2, 1))
        assertEquals(2, c.removeFrom(2).size); assertEquals("[32, 16]", c.toString()); assertEquals(48.0, c.totalValue, 0.0)
        val ids = IdGenerator(); val a = ChainModel(ids); val b = ChainModel(ids)
        a.setLevels(listOf(1, 2, 3)); b.setLevels(listOf(1, 2, 3))
        val set = HashSet<Int>()
        (a.cells + b.cells).forEach { assertTrue(set.add(it.id)) }
    }

    @Test fun spatialHash() {
        val h = SpatialHash<String>(4f)
        h.insert("a", 0f, 0f); h.insert("b", 10f, 0f); h.insert("c", -3f, 3.9f)
        val out = mutableListOf<String>()
        h.query(0f, 0f, 5f, out); assertEquals(setOf("a", "c"), out.toSet())
        h.move("b", 1f, 1f); out.clear(); h.query(0f, 0f, 2f, out); assertEquals(setOf("a", "b"), out.toSet())
        assertTrue(h.remove("a")); assertFalse(h.remove("a"))
        out.clear(); h.query(0f, 0f, 2f, out); assertEquals(listOf("b"), out)
        assertTrue(h.anyWithin(-3f, 4f, 0.5f))
        h.clear(); assertEquals(0, h.size)

        val rng = Rng(7); val hash = SpatialHash<Any>(3f); val items = mutableListOf<Pair<Any, Vec2>>()
        repeat(500) { val o = Any(); val p = rng.insideDisc(50f); items += o to p; hash.insert(o, p.x, p.y) }
        val res = mutableListOf<Any>()
        repeat(50) {
            val c = rng.insideDisc(50f); val r = rng.range(0.5f, 12f)
            res.clear(); hash.query(c.x, c.y, r, res)
            assertEquals(items.count { Vec2.distance(it.second, c) <= r }, res.size)
        }
    }

    @Test fun rngAndAngles() {
        val a = Rng(99); val b = Rng(99)
        repeat(1000) {
            val f = a.nextFloat(); assertEquals(f, b.nextFloat()); assertTrue(f >= 0f && f < 1f)
            val r = a.range(3, 7); b.range(3, 7); assertTrue(r in 3..6)
        }
        repeat(1000) { assertTrue(a.insideDisc(5f).length <= 5.0001f) }
        assertNotEquals(Rng(1).nextLong(), Rng(2).nextLong())
        assertEquals(0f, MathUtil.wrapAngle(MathUtil.TWO_PI), 1e-5f)
        val r = MathUtil.rotateTowards(170f * MathUtil.DEG2RAD, -170f * MathUtil.DEG2RAD, 5f * MathUtil.DEG2RAD)
        assertEquals(175f, r / MathUtil.DEG2RAD, 1e-3f)
    }
}
