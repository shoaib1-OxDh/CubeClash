package com.oxdh.cubeclash.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** Gameplay rules exercised through the real simulation: solid cubes, number-based eating, front / rear encounters. */
class RulesWorldTest {
    private val dt = 1f / 60f

    private fun emptyWorld(): World {
        val w = World(GameConfig(), GameModeSettings.forMode(GameMode.CLASSIC), AIDifficulty.NORMAL, 3, options = WorldOptions(botCount = 0, respawnHumans = true))
        w.replicaClearCollectibles()
        return w
    }

    private fun place(w: World, c: Chain, x: Float, y: Float, heading: Float, levels: List<Int>): InputBrain {
        w.spawnChain(c, Vec2(x, y), heading, levels)
        c.protection = 0f
        val b = InputBrain(); b.direction = Vec2.fromAngle(heading); c.brain = b
        return b
    }

    private fun run(w: World, seconds: Float, each: () -> Unit = {}) { repeat((seconds / dt).toInt()) { w.step(dt); each() } }

    @Test fun biggerCubeIsSolidAndSlowsTheSnake() {
        val w = emptyWorld()
        val p = w.player
        val input = place(w, p, 0f, 0f, 0f, listOf(0)) // value 1, heading +X
        run(w, 1f)
        val cruise = p.speed
        assertTrue(cruise > w.move.baseSpeed * 0.9f)
        val cube = w.spawnCollectible(4, p.headX + 4f, p.headY, false) // a 16 straight ahead
        var minGap = Float.MAX_VALUE
        var minSpeed = Float.MAX_VALUE
        run(w, 2f) {
            val gap = Vec2.distance(p.headPosition, Vec2(cube.x, cube.y)) - (p.headRadius(w.move) + cube.radius * 0.95f)
            minGap = minOf(minGap, gap)
            if (gap < 0.2f) minSpeed = minOf(minSpeed, p.speed)
        }
        assertTrue("cube not eaten", cube in w.collectibles)
        assertTrue("head never enters the cube (gap $minGap)", minGap > -0.05f)
        assertTrue("strong resistance while pushing ($minSpeed vs $cruise)", minSpeed < cruise * 0.45f)
        assertTrue(w.events.isEmpty() || w.events.none { it is WorldEvent.Collected })
        // Turn away (directly away from the cube, wherever the head slid to): speed comes back smoothly.
        input.direction = (p.headPosition - Vec2(cube.x, cube.y)).normalized
        run(w, 1.5f)
        assertTrue("speed recovers (${p.speed})", p.speed > cruise * 0.9f)
    }

    @Test fun smallerOrEqualCubeIsEaten() {
        val w = emptyWorld()
        val p = w.player
        place(w, p, 0f, 0f, 0f, listOf(3, 1)) // 8, 2
        val equal = w.spawnCollectible(1, 3f, 0f, false)   // a 2: equal to my tail, smaller than my head
        val smaller = w.spawnCollectible(0, 6f, 0f, false) // a 1
        run(w, 1.5f)
        assertFalse(equal in w.collectibles); assertFalse(smaller in w.collectibles)
        assertEquals("8 + 2 + 2 + 1 merged", "[8, 4, 1]", p.model.toString())
    }

    @Test fun eatsSmallerSnakeHeadOn() {
        val w = emptyWorld()
        val me = w.player
        place(w, me, 0f, 0f, 0f, listOf(3)) // 8
        val other = w.addHuman("Small", "classic", "spark")
        place(w, other, 6f, 0f, PI.toFloat(), listOf(2, 1)) // 4, 2 coming towards me
        var eaten = false
        run(w, 2f) { if (w.events.any { it is WorldEvent.Defeated && it.victim === other && it.killer === me }) eaten = true }
        assertTrue(eaten)
        assertTrue(me.alive)
        assertEquals("ate both cubes: 8 + 4 + 2", 14.0, me.model.totalValue, 0.0)
        assertTrue("eater got the defeat", me.stats.opponentsDefeated == 1)
    }

    @Test fun cannotEatEqualOrBiggerEvenWhileBoosting() {
        for ((mine, theirs) in listOf(3 to 3, 1 to 4)) {
            val w = emptyWorld()
            val me = w.player
            val myInput = place(w, me, 0f, 0f, 0f, listOf(mine))
            myInput.boost = true
            val other = w.addHuman("Other", "classic", "spark")
            place(w, other, 8f, 0f, PI.toFloat(), listOf(theirs)).boost = true
            var minDist = Float.MAX_VALUE
            run(w, 2f) { minDist = minOf(minDist, Vec2.distance(me.headPosition, other.headPosition)) }
            if (mine == theirs) {
                assertTrue("equal numbers: nobody is eaten", me.alive && other.alive)
                assertTrue("heads never pass through each other ($minDist)", minDist > 0.6f)
            } else {
                assertTrue("the bigger snake is never eaten by the smaller one", other.alive)
                assertEquals("the smaller one was eaten by the bigger one", other.name, me.lastEatenBy)
            }
        }
    }

    @Test fun smallerSnakeBitesTailThenGetsEatenAfterTurnAround() {
        val w = emptyWorld()
        val big = w.player
        val bigInput = place(w, big, 0f, 0f, 0f, listOf(4, 3, 2, 1, 0)) // 16 8 4 2 1, heading +X
        big.speedMultiplier = 0.4f
        run(w, 0.5f)
        val tail = big.segments.last()
        val small = w.addHuman("Biter", "classic", "spark")
        // Small snake (head 2) right behind the tail, facing it: it may eat the 1 at the end of the tail.
        place(w, small, tail.x - 1.6f, tail.y, 0f, listOf(1))
        var severed = false
        run(w, 1f) { if (w.events.any { it is WorldEvent.Severed && it.victim === big && it.attacker === small }) severed = true }
        assertTrue("small snake ate the tail cube", severed)
        assertTrue(big.model.length < 5)
        assertTrue(small.alive)
        // The big snake turns around and chases the biter.
        val smallInput = small.brain as InputBrain
        smallInput.direction = Vec2.fromAngle(PI.toFloat() / 2f)
        big.speedMultiplier = 1f
        var eaten = false
        run(w, 6f) {
            bigInput.direction = (small.headPosition - big.headPosition).normalized
            bigInput.boost = true
            if (w.events.any { it is WorldEvent.Defeated && it.victim === small && it.killer === big }) eaten = true
            if (eaten) smallInput.direction = null
        }
        assertTrue("turning around lets the bigger snake eat the biter", eaten)
    }

    @Test fun backOfTheHeadDoesNotBite() {
        val w = emptyWorld()
        val big = w.player
        place(w, big, 0f, 0f, 0f, listOf(4)).direction = null
        big.speedMultiplier = 0f
        val small = w.addHuman("Small", "classic", "spark")
        // The small snake's head sits just in front of the big one, but the big one is facing away from it.
        place(w, small, -1.2f, 0f, PI.toFloat(), listOf(1)).direction = Vec2.fromAngle(PI.toFloat())
        big.heading = 0f
        w.step(dt)
        assertTrue("no bite with the back of the head", small.alive)
    }

    @Test fun peopleRespawnInMultiplayer() {
        val w = emptyWorld()
        val me = w.player
        place(w, me, 0f, 0f, 0f, listOf(4))
        val other = w.addHuman("Friend", "classic", "spark")
        place(w, other, 5f, 0f, PI.toFloat(), listOf(0))
        run(w, 1f)
        assertFalse(other.alive)
        assertEquals("Ate by the host player", me.name, other.lastEatenBy)
        run(w, 3.5f)
        assertTrue("respawned", other.alive)
        assertEquals(0, other.headLevel)
    }
}
