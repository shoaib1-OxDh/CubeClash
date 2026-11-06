package com.oxdh.cubeclash.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the real arena simulation headlessly (the same code the app runs every frame). */
class WorldTest {
    private fun world(mode: GameMode = GameMode.CLASSIC, d: AIDifficulty = AIDifficulty.NORMAL, seed: Long = 1) =
        World(GameConfig(), GameModeSettings.forMode(mode), d, seed)

    @Test fun newGameStartsWithSingleOne() {
        val w = world()
        assertEquals("[1]", w.player.model.toString())
        assertEquals(0L, w.player.stats.score)
        assertTrue(w.player.alive)
        assertEquals(GameModeSettings.forMode(GameMode.CLASSIC).rivalCount + 1, w.chains.size)
        assertTrue(w.collectibles.size > 100)
        for (c in w.collectibles) assertTrue(w.isFreeSpot(c.x, c.y, 1f))
    }

    @Test fun playerMovesInAllDirectionsAndStaysInside() {
        val w = world(mode = GameMode.ENDLESS_GROWTH)
        val input = InputBrain(); w.player.brain = input
        val dirs = listOf(Vec2.UNIT_X, Vec2.UNIT_Y, -Vec2.UNIT_X, -Vec2.UNIT_Y, Vec2(1f, 1f).normalized, Vec2(-1f, 1f).normalized)
        for (d in dirs) {
            input.direction = d
            val before = w.player.headPosition
            repeat(60) { w.step(1f / 60f) }
            val moved = w.player.headPosition - before
            assertTrue("moved along $d", Vec2.dot(moved.normalized, d) > 0.5f)
        }
        // Full circles and reversals.
        repeat(600) { i -> input.direction = Vec2.fromAngle(i * 0.08f); w.step(1f / 60f) }
        input.direction = Vec2.UNIT_X
        repeat(3000) { w.step(1f / 60f) } // run straight into the boundary
        assertTrue(w.player.headPosition.length <= w.arenaRadius + 1e-3f)
    }

    @Test fun collectingAndMergingThroughTheWorld() {
        val w = world(mode = GameMode.ENDLESS_GROWTH)
        w.player.brain = null
        // Put a 1 right in front of the player.
        val c = w.spawnCollectible(0, w.player.headX, w.player.headY + 0.5f, false)
        w.step(1f / 60f)
        assertTrue(w.events.any { it is WorldEvent.Collected && it.chain === w.player })
        assertFalse(c in w.collectibles)
        assertEquals("[1, 1]", w.player.model.toString())
        repeat(30) { w.step(1f / 60f) }
        assertEquals("[2]", w.player.model.toString())
        assertEquals(3L, w.player.stats.score) // collect 1 + merge 2
        assertEquals(1, w.player.stats.highestLevel)
        // A bigger cube is ignored.
        val big = w.spawnCollectible(5, w.player.headX, w.player.headY, false)
        w.step(1f / 60f)
        assertTrue(big in w.collectibles)
        assertEquals(1, w.player.model.length)
    }

    @Test fun longSessionsKeepInvariants() {
        for ((i, d) in AIDifficulty.values().withIndex()) for (mode in GameMode.values()) {
            val w = world(mode, d, seed = 100L + i * 10 + mode.ordinal)
            // Let an AI play for the player too, so the whole loop is exercised.
            w.player.brain = World.AIBrain(GameConfig().aiFor(d), Rng(5))
            var defeats = 0; var merges = 0; var collects = 0
            val steps = 60 * 180 // three simulated minutes at 60 fps
            for (s in 0 until steps) {
                w.step(1f / 60f)
                val defeatedNow = HashSet<Int>()
                for (e in w.events) when (e) {
                    is WorldEvent.Defeated -> { assertTrue("double defeat in one frame", defeatedNow.add(e.victim.id)); defeats++ }
                    is WorldEvent.Merged -> merges++
                    is WorldEvent.Collected -> collects++
                    else -> {}
                }
                if (w.playerDefeated) break
                for (c in w.chains) {
                    assertTrue(c.model.isSorted())
                    assertTrue(c.model.length <= w.rules.maxChainLength)
                    if (!c.alive) continue
                    assertEquals(c.model.length, c.segments.size)
                    assertFalse(c.headX.isNaN() || c.headY.isNaN())
                    assertTrue(c.headPosition.length <= w.arenaRadius + 1e-3f)
                    for (o in w.obstacles) assertTrue(Vec2.distance(o.position, c.headPosition) >= o.radius + c.headRadius(w.move) - 1e-3f)
                }
                assertTrue(w.collectibles.size <= w.config.spawn.maxCollectibles + 200)
            }
            val maxHead = w.chains.maxOf { it.stats.highestLevel }
            println("$mode/$d: t=${"%.0f".format(w.time)}s collects=$collects merges=$merges defeats=$defeats maxHead=${NumberFormat.formatLevel(maxHead)} playerScore=${w.player.stats.score}")
            assertTrue(collects > 100); assertTrue(merges > 30); assertTrue(maxHead >= 3)
            if (mode == GameMode.ENDLESS_GROWTH) assertFalse("player can't die in Endless", w.playerDefeated)
        }
    }

    @Test fun deterministicForSeed() {
        val a = world(seed = 77); val b = world(seed = 77)
        a.player.brain = World.AIBrain(AIBehaviourConfig(), Rng(1)); b.player.brain = World.AIBrain(AIBehaviourConfig(), Rng(1))
        repeat(600) { a.step(1f / 60f); b.step(1f / 60f) }
        assertEquals(a.player.headX, b.player.headX, 0f)
        assertEquals(a.chains.map { it.stats.score }, b.chains.map { it.stats.score })
    }
}
