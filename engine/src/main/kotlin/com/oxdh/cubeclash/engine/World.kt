package com.oxdh.cubeclash.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Look of an obstacle. Collision is always the circle of [Obstacle.radius]; the shape is visual only. */
enum class ObstacleShape { PILLAR, HEX, DOME, CONE, ROCK, BOX }

data class Obstacle(val position: Vec2, val radius: Float, val height: Float) {
    /**
     * Derived from the obstacle's own numbers, so host and every client agree without sending it. Only round shapes
     * whose footprint matches the collision circle are picked; a few classic boxes remain for variety.
     */
    val shape: ObstacleShape = run {
        var h = position.x.toRawBits() * 31 + position.y.toRawBits()
        h = h * 31 + radius.toRawBits()
        h = h xor (h ushr 15); h *= 0x2c1b3c6d; h = h xor (h ushr 12)
        val k = (h and 0x7fffffff) % 100
        when {
            k < 24 -> ObstacleShape.PILLAR
            k < 44 -> ObstacleShape.HEX
            k < 60 -> ObstacleShape.DOME
            k < 74 -> ObstacleShape.CONE
            k < 92 -> ObstacleShape.ROCK
            else -> ObstacleShape.BOX
        }
    }
}

/** Visual + collision state of one chain cube (mirrors a [ChainCell]). */
class Segment internal constructor(val cellId: Int, level: Int, size: Float) {
    var level = level; internal set
    var index = 0; internal set
    var x = 0f; internal set
    var y = 0f; internal set
    /** Facing direction on the ground plane. */
    var dirX = 0f; internal set
    var dirY = 1f; internal set
    var size = size * 0.35f; internal set
    internal var targetSize = size
    internal var distance = 0f
    internal var targetDistance = 0f
    /** 1 -> 0 after a merge/upgrade; drives the "pop" scale. */
    var punch = 0f; internal set
    val radius: Float get() = size * 0.5f
    lateinit var chain: Chain; internal set
}

/** A cube merging into another; animates for a moment, then disappears. */
class AbsorbingSegment internal constructor(val segment: Segment, val into: Segment) {
    internal var t = 0f
    internal val startX = segment.x
    internal val startY = segment.y
}

/** A snake-like chain of numbered cubes (player or rival). */
class Chain internal constructor(
    val id: Int,
    val name: String,
    val isPlayer: Boolean,
    val skin: String,
    val trailId: String,
    /** ARGB team colour for the ring under the head and the name tag. */
    val teamColor: Int,
    ids: IdGenerator,
    startLevel: Int,
    boostConfig: BoostConfig,
) {
    val model = ChainModel(ids, startLevel)
    val trail = PathTrail(0.12f, 40f)
    val boost = BoostMeter(boostConfig)
    val stats = SessionStats()
    var brain: Brain? = null

    var alive = false; internal set
    var protection = 0f; internal set
    val isProtected: Boolean get() = protection > 0f
    var canBeDefeated = true
    var canDefeatOthers = true
    var speedMultiplier = 1f
    var turnSensitivity = 1f

    /** Radians, 0 = +X, PI/2 = +Y (world +Z). */
    var heading = 0f; internal set
    var speed = 0f; internal set
    var desiredDirection: Vec2? = null
    var wantsBoost = false

    /** 0..1: how strongly the chain is currently held back by something solid (bigger cube or snake). */
    var resistance = 0f; internal set
    /** True for chains controlled by people (local or remote), false for bots. */
    var isHuman = isPlayer; internal set
    /** Multiplayer: false while a remote player is disconnected (an AI keeps the chain alive meanwhile). */
    var connected = true
    /** Name of the chain that last ate this one (for "eaten by X" messages). */
    var lastEatenBy: String? = null; internal set
    /** Seconds until this chain respawns (0 when alive or not respawning). */
    var respawnCountdown = 0f
    internal fun lastEatenByName(n: String?) { lastEatenBy = n }

    val segments = ArrayList<Segment>(32)
    val absorbing = ArrayList<AbsorbingSegment>(8)
    internal val byId = HashMap<Int, Segment>(32)
    internal var mergeTimer = 0f
    internal var lastHeadLevel = -1
    internal var wasBoosting = false

    val headX: Float get() = trail.headX
    val headY: Float get() = trail.headY
    val headPosition: Vec2 get() = trail.head
    val forward: Vec2 get() = Vec2.fromAngle(heading)
    val velocity: Vec2 get() = forward * speed
    val headLevel: Int get() = model.headLevel
    fun headRadius(m: MovementConfig) = m.cubeSize(model.headLevel) * 0.5f

    fun snapshot() = ChainSnapshot(id, model.headLevel, model.totalValue, model.length, alive, isProtected, canBeDefeated, canDefeatOthers)
}

/** A numbered cube lying in the arena. */
class Collectible internal constructor(val id: Int, val level: Int, x: Float, y: Float, val size: Float, val isDrop: Boolean, val spawnTime: Double) {
    /** Position; bigger cubes can be pushed (slowly) by snakes ramming them. */
    var x = x; internal set
    var y = y; internal set
    enum class State { SPAWNING, IDLE, COLLECTING }
    var state = State.SPAWNING; internal set
    /** Animation clock (seconds since spawn / since collection started). */
    var animTime = 0f; internal set
    /** Current render position (moves towards the collector while COLLECTING). */
    var renderX = x; internal set
    var renderY = y; internal set
    /** Visual scale 0..1 (spawn pop, collect shrink). */
    var scale = 0f; internal set
    val spin: Float = ((id * 2654435761L) and 0xffff).toFloat() / 0xffff * 360f
    internal var collector: Chain? = null
    val radius: Float get() = size * 0.5f
}

sealed class WorldEvent {
    data class Collected(val chain: Chain, val level: Int, val x: Float, val y: Float) : WorldEvent()
    data class Merged(val chain: Chain, val level: Int, val x: Float, val y: Float) : WorldEvent()
    data class ScoreGained(val chain: Chain, val points: Long, val x: Float, val y: Float) : WorldEvent()
    data class Defeated(val victim: Chain, val killer: Chain?, val x: Float, val y: Float, val topLevel: Int) : WorldEvent()
    data class Severed(val victim: Chain, val attacker: Chain, val x: Float, val y: Float) : WorldEvent()
    data class Bounced(val a: Chain, val b: Chain, val x: Float, val y: Float) : WorldEvent()
    data class BoostStarted(val chain: Chain) : WorldEvent()
    /** A chain pushed against something solid it may not eat (throttled per chain). */
    data class Blocked(val chain: Chain, val x: Float, val y: Float) : WorldEvent()
    data class Respawned(val chain: Chain) : WorldEvent()
}

interface Brain { fun think(world: World, chain: Chain, dt: Float) }

/** Options for multiplayer hosts and network replicas. */
data class WorldOptions(
    /** Number of AI rivals (defaults to the mode's rival count). */
    val botCount: Int? = null,
    /** Multiplayer: defeated people respawn after [humanRespawnDelay] instead of ending the game. */
    val respawnHumans: Boolean = false,
    val humanRespawnDelay: Float = 3f,
    /** Record collectible spawns / removals / moves for network replication. */
    val trackNetChanges: Boolean = false,
    /** Client replica: no simulation; state is applied from the network. */
    val replica: Boolean = false,
    /** Use these obstacles instead of generating them (replicas receive them from the host). */
    val obstacles: List<Obstacle>? = null,
    val playerName: String = "You",
)

/**
 * The complete arena simulation: chain movement along recorded paths, Hybrid merging, weighted collectible
 * spawning with a spatial hash, solid bigger cubes, number-based eating resolved by [CombatResolver], AI rivals,
 * human players (multiplayer), respawns and the Survival ramp. Deterministic for a given seed.
 * Call [step] once per frame; read [events] afterwards.
 *
 * In replica mode (network clients) nothing is simulated: the client applies host state with the replica* methods
 * and calls [replicaFrame] to animate.
 */
class World(
    val config: GameConfig,
    val mode: GameModeSettings,
    val difficulty: AIDifficulty,
    seed: Long,
    playerSkin: String = UnlockCatalog.DEFAULT_SKIN,
    playerTrail: String = UnlockCatalog.DEFAULT_TRAIL,
    val options: WorldOptions = WorldOptions(),
) {
    val seed = seed
    val rng = Rng(seed)
    val rules = config.rules.copy().sanitize()
    val merge = MergeSystem(rules)
    val move = config.movement
    private val ids = IdGenerator()
    val arenaRadius = max(30f, config.arena.radius)
    val obstacles = ArrayList<Obstacle>()
    val chains = ArrayList<Chain>()
    /** The chain controlled on this device. */
    var player: Chain; private set
    val collectibles = ArrayList<Collectible>(512)
    val collecting = ArrayList<Collectible>(32)
    private val collectibleById = HashMap<Int, Collectible>(512)
    private val hash = SpatialHash<Collectible>(6f)
    private val segmentHash = SpatialHash<Segment>(4f)
    private val table = WeightedTable(config.spawn.distribution)
    private val targetCollectibles = (config.spawn.targetCollectibles * mode.collectibleDensityMultiplier).toInt()
    private val respawn = HashMap<Chain, Float>()
    private val baseAI: AIBehaviourConfig
    private var rampTimer = 0f
    private var nextCollectibleId = 1
    private var nextChainId = 1000
    private val blockedSoundTimer = HashMap<Int, Float>()

    /** Events produced by the last [step] / [replicaFrame]. */
    val events = ArrayList<WorldEvent>(32)
    var time = 0.0; private set
    /** Single player: set when the player is eaten (game over in Classic / Survival). */
    var playerDefeated = false; private set
    /** When true the local player is ignored by the simulation (run ended). */
    var playerFrozen = false

    // Network change tracking (host).
    val netSpawned = ArrayList<Collectible>()
    /** (collectible id, collector chain id or 0). */
    val netRemoved = ArrayList<Long>()
    val netMoved = LinkedHashSet<Collectible>()
    val netEvents = ArrayList<WorldEvent>()

    private val tmp = FloatArray(2)
    private val chainEvents = ArrayList<ChainEvent>(16)
    private val colQuery = ArrayList<Collectible>(32)
    private val segQuery = ArrayList<Segment>(32)
    private val contacts = ArrayList<Contact>(16)
    private val resolved = ArrayList<ResolvedContact>(8)

    init {
        if (options.obstacles != null) obstacles.addAll(options.obstacles) else if (!options.replica) buildObstacles()
        player = createChain(1, options.playerName, true, playerSkin, playerTrail, 0xFFFFFFFF.toInt())
        player.canBeDefeated = mode.playerCanBeDefeated
        baseAI = config.aiFor(difficulty).let { if (mode.rivalsAggressive) it.copy() else it.passive() }
        if (!options.replica) {
            spawnChain(player, Vec2.ZERO, MathUtil.PI * 0.5f, listOf(rules.startLevel))
            val names = RIVAL_NAMES.toMutableList()
            for (i in 0 until (options.botCount ?: mode.rivalCount).coerceIn(0, 20)) {
                val name = if (names.isNotEmpty()) names.removeAt(rng.range(0, names.size)) else "Bot ${i + 1}"
                val rival = createChain(i + 2, name, false, UnlockCatalog.DEFAULT_SKIN, UnlockCatalog.DEFAULT_TRAIL, TEAM_COLORS[i % TEAM_COLORS.size])
                rival.brain = AIBrain(baseAI.copy(), Rng(seed + i * 7919L + 13))
                spawnRival(rival, true)
            }
            fillInitial()
        } else {
            chains.remove(player) // placeholder until the host assigns our chain
        }
    }

    private fun createChain(id: Int, name: String, isPlayer: Boolean, skin: String, trail: String, color: Int): Chain {
        val c = Chain(id, name, isPlayer, skin, trail, color, ids, rules.startLevel, config.boost.copy())
        c.boost.regenMultiplier = mode.boostRegenMultiplier
        chains.add(c)
        return c
    }

    fun findChain(id: Int): Chain? = chains.firstOrNull { it.id == id }
    fun findCollectible(id: Int): Collectible? = collectibleById[id]

    fun canCollect(chain: Chain, level: Int) = chain.alive && merge.canCollect(chain.model, level)

    fun isFreeSpot(x: Float, y: Float, clearance: Float): Boolean {
        if (sqrt(x * x + y * y) > arenaRadius - clearance) return false
        for (o in obstacles) {
            val dx = x - o.position.x; val dy = y - o.position.y
            if (sqrt(dx * dx + dy * dy) < o.radius + clearance) return false
        }
        return true
    }

    private fun buildObstacles() {
        val a = config.arena
        val group = ArrayList<Obstacle>(6)
        repeat(a.obstacleCount) {
            for (attempt in 0 until 24) {
                val p = rng.insideDisc(arenaRadius - 14f)
                if (p.length < 20f) continue
                group.clear()
                val kind = rng.nextFloat()
                when {
                    // A curved wall of overlapping pillars: something to hide behind and steer around.
                    kind < 0.18f -> {
                        val n = 4 + (rng.nextFloat() * 3).toInt()
                        val r = rng.range(1.3f, 1.9f)
                        val h = rng.range(2.5f, 4f)
                        val dir = rng.nextFloat() * MathUtil.TWO_PI
                        val bend = rng.range(-0.25f, 0.25f)
                        var x = p.x; var y = p.y; var ang = dir
                        repeat(n) { group.add(Obstacle(Vec2(x, y), r, h)); ang += bend; x += kotlin.math.cos(ang) * r * 1.5f; y += kotlin.math.sin(ang) * r * 1.5f }
                    }
                    // A cluster of boulders.
                    kind < 0.42f -> {
                        val n = 3 + (rng.nextFloat() * 2).toInt()
                        val big = rng.range(2.2f, 3.4f)
                        group.add(Obstacle(p, big, rng.range(2.5f, 4.5f)))
                        repeat(n - 1) {
                            val ang = rng.nextFloat() * MathUtil.TWO_PI
                            val r = rng.range(1.1f, 1.8f)
                            val dd = big + r * 0.6f
                            group.add(Obstacle(Vec2(p.x + kotlin.math.cos(ang) * dd, p.y + kotlin.math.sin(ang) * dd), r, rng.range(1.6f, 3f)))
                        }
                    }
                    else -> group.add(Obstacle(p, rng.range(a.obstacleMinRadius, a.obstacleMaxRadius), rng.range(3f, 7.5f)))
                }
                val fits = group.all { g ->
                    g.position.length < arenaRadius - 10f - g.radius && g.position.length > 18f &&
                        obstacles.none { Vec2.distance(it.position, g.position) < it.radius + g.radius + 9f }
                }
                if (!fits) continue
                obstacles.addAll(group)
                break
            }
        }
    }

    // ------------------------------------------------------------------ people (multiplayer)

    /** Adds a person's chain (multiplayer host). It is controlled by an [InputBrain]. */
    fun addHuman(name: String, skin: String, trail: String): Chain {
        val id = nextChainId++
        val c = createChain(id, name.take(16).ifBlank { "Player" }, false, skin, trail, TEAM_COLORS[(id * 7) % TEAM_COLORS.size])
        c.isHuman = true
        c.brain = InputBrain()
        spawnChain(c, findRivalSpot(false), rng.nextFloat() * MathUtil.TWO_PI, listOf(rules.startLevel))
        return c
    }

    fun removeChain(c: Chain) {
        if (c === player) return
        if (c.alive) {
            val drops = c.segments.map { it.level to Vec2(it.x, it.y) }
            c.alive = false
            spawnDrops(drops)
        }
        respawn.remove(c)
        chains.remove(c)
    }

    /** AI brain used while a remote player is disconnected. */
    fun makeStandInBrain(): Brain = AIBrain(AIBehaviourConfig.forDifficulty(AIDifficulty.NORMAL).passive(), Rng(rng.nextLong()))

    // ------------------------------------------------------------------ spawning chains

    internal fun spawnChain(c: Chain, pos: Vec2, heading: Float, levels: List<Int>, keepStats: Boolean = false) {
        c.segments.clear(); c.absorbing.clear(); c.byId.clear()
        c.model.setLevels(levels)
        c.heading = heading
        c.speed = 0f
        c.resistance = 0f
        c.desiredDirection = null
        c.wantsBoost = false
        c.boost.refill()
        c.mergeTimer = 0f
        c.alive = true
        c.protection = rules.spawnProtectionSeconds
        c.trail.reset(pos, Vec2.fromAngle(heading), 30f)
        if (!keepStats) c.stats.reset(c.model.headLevel)
        c.lastHeadLevel = -1
        syncSegments(c, true)
    }

    private fun spawnRival(rival: Chain, initial: Boolean) {
        val baseMax = mode.rivalMaxLevelAt(time)
        val playerLevel = player.headLevel
        var hi = max(baseMax, playerLevel + 1)
        var lo = max(0, min(baseMax, playerLevel - 3))
        if (initial) { lo = 0; hi = baseMax }
        val head = rng.range(lo, hi + 1)
        val levels = mutableListOf(head)
        for (l in head - 1 downTo 0) if (rng.chance(0.55f)) levels.add(l) // distinct values: no pending merges
        spawnChain(rival, findRivalSpot(initial), rng.nextFloat() * MathUtil.TWO_PI, levels)
        (rival.brain as? AIBrain)?.reset(rival)
    }

    private fun findRivalSpot(initial: Boolean): Vec2 {
        val minFromPlayer = if (initial) 25f else 38f
        var best = Vec2.ZERO
        var bestScore = -Float.MAX_VALUE
        for (attempt in 0 until 30) {
            val p = rng.insideDisc(arenaRadius - 10f)
            if (!isFreeSpot(p.x, p.y, 3f)) continue
            var nearest = Float.MAX_VALUE
            for (c in chains) {
                if (!c.alive) continue
                nearest = min(nearest, Vec2.distance(c.headPosition, p) - (if (c.isHuman) minFromPlayer else 12f))
            }
            if (nearest >= 0f) return p
            if (nearest > bestScore) { bestScore = nearest; best = p }
        }
        return if (bestScore > -Float.MAX_VALUE) best else -player.headPosition.normalized * (arenaRadius * 0.7f)
    }

    // ------------------------------------------------------------------ main loop

    fun step(dtIn: Float) {
        events.clear()
        if (options.replica) return
        var remaining = min(dtIn, 1f / 15f)
        if (remaining <= 0f) return
        // Sub-steps keep fast (boosting) snakes from tunnelling through cubes or each other.
        while (remaining > 1e-5f) {
            val dt = min(remaining, MAX_SUBSTEP)
            remaining -= dt
            subStep(dt)
        }
        val visualDt = min(dtIn, 1f / 15f)
        for (c in chains) updateVisuals(c, visualDt)
        updateCollectibles(visualDt)
        replenish()
        if (options.trackNetChanges) netEvents.addAll(events)
    }

    private fun subStep(dt: Float) {
        time += dt
        if (player.alive && !playerFrozen) player.stats.addTime(dt.toDouble())
        for (c in chains) if (c.alive && c.isHuman && c !== player) c.stats.addTime(dt.toDouble())
        for (c in chains) if (c.alive && !(c === player && playerFrozen)) c.brain?.think(this, c, dt)
        if (playerFrozen) { player.desiredDirection = null; player.wantsBoost = false; player.speedMultiplier = 0f }
        for (c in chains) simulate(c, dt)
        collectAndFight()
        // Eating can grow a head next to a pillar or the edge: keep every head in a legal spot.
        for (c in chains) if (c.alive) {
            constrain(c.headRadius(move), c.headX, c.headY)
            if (legal[0] != c.headX || legal[1] != c.headY) c.trail.moveHead(legal[0], legal[1])
        }
        updateRespawns(dt)
        updateRamp(dt)
        if (blockedSoundTimer.isNotEmpty()) blockedSoundTimer.replaceAll { _, v -> v - dt }
    }

    private fun simulate(c: Chain, dt: Float) {
        if (!c.alive) return
        if (c.protection > 0f) c.protection -= dt
        c.boost.update(dt, c.wantsBoost)
        if (c.boost.isBoosting && !c.wasBoosting) events.add(WorldEvent.BoostStarted(c))
        c.wasBoosting = c.boost.isBoosting
        c.resistance = max(0f, c.resistance - dt * 2.5f)

        var target = move.baseSpeed * move.speedFactor(c.model.headLevel) * c.speedMultiplier *
            (if (c.boost.isBoosting) c.boost.config.speedMultiplier else 1f)
        target *= 1f - 0.7f * c.resistance
        c.speed = MathUtil.moveTowards(c.speed, target, (if (target > c.speed) move.acceleration else move.deceleration * 3f) * dt)

        val desired = c.desiredDirection
        if (desired != null && desired.lengthSquared > 1e-6f) {
            val maxTurn = move.maxTurnRateDegrees * MathUtil.DEG2RAD * move.turnSensitivity * c.turnSensitivity * dt
            // Ease into the target heading: full turn rate for big turns, a smooth glide for small corrections,
            // so steering never snaps or wobbles around the finger direction.
            val diff = kotlin.math.abs(MathUtil.wrapAngle(desired.angle - c.heading))
            val eased = max(diff * MathUtil.smoothFactor(move.turnEasing * c.turnSensitivity, dt), 0.15f * maxTurn)
            c.heading = MathUtil.rotateTowards(c.heading, desired.angle, min(maxTurn, eased))
        }
        var fwd = Vec2.fromAngle(c.heading)
        var px = c.headX + fwd.x * c.speed * dt
        var py = c.headY + fwd.y * c.speed * dt
        val headR = c.headRadius(move)

        // Boundary: slide along the edge, never leave.
        val limit = arenaRadius - headR
        val d = sqrt(px * px + py * py)
        if (d > limit) {
            val n = Vec2(px / d, py / d)
            px = n.x * limit; py = n.y * limit
            fwd = slide(c, n, fwd)
        }
        for (o in obstacles) {
            val dx = px - o.position.x; val dy = py - o.position.y
            val dist = sqrt(dx * dx + dy * dy)
            val minD = o.radius + headR
            if (dist >= minD) continue
            val n = if (dist > 1e-4f) Vec2(dx / dist, dy / dist) else Vec2.UNIT_X
            px = o.position.x + n.x * minD; py = o.position.y + n.y * minD
            fwd = slide(c, -n, fwd)
        }

        // Bigger numbered cubes are solid: the head cannot enter them. Ramming one pushes it slowly and the snake
        // is held back strongly until it turns away.
        colQuery.clear()
        hash.query(px, py, headR + 1.6f, colQuery)
        for (col in colQuery) {
            if (col.state == Collectible.State.COLLECTING || merge.canCollect(c.model, col.level)) continue
            var dx = px - col.x; var dy = py - col.y
            var dist = sqrt(dx * dx + dy * dy)
            val minD = headR + col.radius * 0.95f
            if (dist >= minD) continue
            val n = if (dist > 1e-4f) Vec2(dx / dist, dy / dist) else -fwd
            val pen = minD - dist
            // The cube gives way a little (heavier the bigger it is), the rest is resolved by moving the head out.
            val give = pen * (0.3f / (1f + 0.15f * (col.level - c.model.headLevel).coerceAtLeast(0)))
            val nx = col.x - n.x * give; val ny = col.y - n.y * give
            if (isFreeSpot(nx, ny, col.radius + 0.2f)) moveCollectible(col, nx, ny)
            dx = px - col.x; dy = py - col.y
            dist = sqrt(dx * dx + dy * dy)
            if (dist < minD) { px = col.x + n.x * minD; py = col.y + n.y * minD }
            fwd = slide(c, -n, fwd)
            hold(c, px, py)
        }
        constrain(headR, px, py)
        c.trail.moveHead(legal[0], legal[1])

        if (merge.hasPendingMerge(c.model)) {
            c.mergeTimer += dt
            if (c.mergeTimer >= rules.hybridMergeInterval) {
                c.mergeTimer = 0f
                chainEvents.clear()
                merge.stepMerges(c.model, chainEvents)
                applyChainEvents(c, chainEvents)
            }
        } else c.mergeTimer = 0f
    }

    private val legal = FloatArray(2)

    /** Final safety pass: puts a head position back inside the arena and outside every pillar (into [legal]). */
    private fun constrain(headR: Float, xIn: Float, yIn: Float) {
        var x = xIn; var y = yIn
        repeat(2) {
            for (o in obstacles) {
                val dx = x - o.position.x; val dy = y - o.position.y
                val dist = sqrt(dx * dx + dy * dy)
                val minD = o.radius + headR
                if (dist >= minD) continue
                val nx = if (dist > 1e-4f) dx / dist else 1f; val ny = if (dist > 1e-4f) dy / dist else 0f
                x = o.position.x + nx * minD; y = o.position.y + ny * minD
            }
            val lim = arenaRadius - headR
            val l = sqrt(x * x + y * y)
            if (l > lim) { x = x / l * lim; y = y / l * lim }
        }
        legal[0] = x; legal[1] = y
    }

    /** Applies strong resistance (something solid is in the way). */
    private fun hold(c: Chain, x: Float, y: Float) {
        c.resistance = 1f
        val limit = move.baseSpeed * 0.3f
        if (c.speed > limit) c.speed = limit
        val t = blockedSoundTimer[c.id] ?: 0f
        if (t <= 0f) { events.add(WorldEvent.Blocked(c, x, y)); blockedSoundTimer[c.id] = 0.6f }
    }

    /** Removes the movement component into a wall with outward normal [n]. */
    private fun slide(c: Chain, n: Vec2, fwd: Vec2): Vec2 {
        val into = Vec2.dot(fwd, n)
        if (into <= 0f) return fwd
        var tangent = fwd - n * into
        if (tangent.lengthSquared < 1e-4f) tangent = n.perpendicular
        val f = (tangent.normalized - n * 0.2f).normalized
        c.heading = f.angle
        return f
    }

    private fun bounce(c: Chain, awayIn: Vec2) {
        if (!c.alive || awayIn.lengthSquared < 1e-6f) return
        val away = awayIn.normalized
        val p = c.headPosition + away * 0.8f
        constrain(c.headRadius(move), p.x, p.y)
        c.trail.moveHead(legal[0], legal[1])
        c.heading = away.angle
        c.speed *= 0.4f
    }

    // ------------------------------------------------------------------ collection & merging

    /** Collects a world cube for [c]; returns false (no change) if the rules reject it. */
    fun tryCollect(c: Chain, level: Int, x: Float, y: Float): Boolean {
        if (!c.alive) return false
        chainEvents.clear()
        if (!merge.tryCollect(c.model, level, chainEvents)) return false
        c.stats.recordCollect()
        events.add(WorldEvent.Collected(c, level, x, y))
        applyChainEvents(c, chainEvents)
        return true
    }

    private fun applyChainEvents(c: Chain, list: List<ChainEvent>) {
        var gained = 0L
        for (e in list) {
            gained = SaturatingMath.add(gained, e.score)
            when (e.type) {
                ChainEventType.MERGED -> {
                    c.stats.recordMerge()
                    val removed = c.byId.remove(e.otherCellId)
                    val survivor = c.byId[e.cellId]
                    if (removed != null) {
                        c.segments.remove(removed)
                        if (survivor != null) c.absorbing.add(AbsorbingSegment(removed, survivor))
                    }
                    if (survivor != null) {
                        survivor.level = e.level; survivor.punch = 1f
                        events.add(WorldEvent.Merged(c, e.level, survivor.x, survivor.y))
                    }
                }
                ChainEventType.UPGRADED -> {
                    c.stats.recordMerge()
                    c.byId[e.cellId]?.let { it.level = e.level; it.punch = 1f; events.add(WorldEvent.Merged(c, e.level, it.x, it.y)) }
                }
                ChainEventType.SHED -> c.byId.remove(e.cellId)?.let { c.segments.remove(it) }
                else -> {}
            }
        }
        if (gained > 0) {
            c.stats.addScore(gained)
            events.add(WorldEvent.ScoreGained(c, gained, c.headX, c.headY))
        }
        syncSegments(c, false)
    }

    /** Makes the segment list mirror the model exactly. */
    private fun syncSegments(c: Chain, snap: Boolean) {
        val cells = c.model.cells
        val live = HashSet<Int>(cells.size * 2)
        for (cell in cells) live.add(cell.id)
        c.byId.keys.retainAll(live)
        c.segments.clear()
        var d = 0f
        for (i in cells.indices) {
            val cell = cells[i]
            val size = move.cubeSize(cell.level)
            if (i > 0) d += (move.cubeSize(cells[i - 1].level) + size) * 0.5f * move.chainGapFactor
            var seg = c.byId[cell.id]
            val created = seg == null
            if (seg == null) {
                seg = Segment(cell.id, cell.level, size)
                seg.chain = c
                c.byId[cell.id] = seg
            }
            seg.level = cell.level
            seg.targetSize = size
            seg.index = i
            if (created || snap) {
                seg.distance = d; seg.targetDistance = d
                placeSegment(c, seg)
            } else seg.targetDistance = d
            c.segments.add(seg)
        }
        c.trail.ensureLength(d + 6f)
        c.stats.observeChain(c.model.headLevel, c.model.length)
        c.lastHeadLevel = c.model.headLevel
    }

    private fun placeSegment(c: Chain, s: Segment) {
        c.trail.sampleInto(s.distance, tmp)
        s.x = tmp[0]; s.y = tmp[1]
        c.trail.sampleInto(max(0f, s.distance - 0.35f), tmp)
        val ax = tmp[0]; val ay = tmp[1]
        c.trail.sampleInto(s.distance + 0.35f, tmp)
        val dx = ax - tmp[0]; val dy = ay - tmp[1]
        val l = sqrt(dx * dx + dy * dy)
        if (l > 1e-5f) { s.dirX = dx / l; s.dirY = dy / l }
    }

    private fun updateVisuals(c: Chain, dt: Float) {
        val k = MathUtil.smoothFactor(move.followSharpness, dt)
        val ks = MathUtil.smoothFactor(12f, dt)
        for (i in c.segments.indices) {
            val s = c.segments[i]
            if (i == 0) { s.distance = 0f; s.targetDistance = 0f } else s.distance += (s.targetDistance - s.distance) * k
            placeSegment(c, s)
            s.size += (s.targetSize - s.size) * ks
            if (s.punch > 0f) s.punch = max(0f, s.punch - dt * 4f)
        }
        val it = c.absorbing.iterator()
        while (it.hasNext()) {
            val a = it.next()
            a.t += dt / 0.14f
            val t = min(1f, a.t)
            a.segment.x = a.startX + (a.into.x - a.startX) * t * t
            a.segment.y = a.startY + (a.into.y - a.startY) * t * t
            a.segment.size = a.segment.targetSize * (1f - 0.4f * t)
            if (t >= 1f) it.remove()
        }
    }

    // ------------------------------------------------------------------ collisions & eating

    private fun collectAndFight() {
        // 1) Collection: eligible cubes are eaten on contact; bigger ones are solid (handled in movement).
        for (c in chains) {
            if (!c.alive || c.segments.isEmpty()) continue
            colQuery.clear()
            val headR = c.headRadius(move)
            hash.query(c.headX, c.headY, headR + 1.6f, colQuery)
            for (col in colQuery) {
                if (col.state == Collectible.State.COLLECTING) continue
                val r = headR + col.radius * 0.95f + 0.05f
                val dx = col.x - c.headX; val dy = col.y - c.headY
                if (dx * dx + dy * dy > r * r) continue
                if (!canCollect(c, col.level)) continue
                if (tryCollect(c, col.level, col.x, col.y)) beginCollect(col, c)
            }
        }

        // 2) Snake contacts: the FRONT of a head touching another chain's cube.
        segmentHash.clear()
        for (c in chains) if (c.alive) for (s in c.segments) segmentHash.insert(s, s.x, s.y)
        contacts.clear()
        for (a in chains) {
            if (!a.alive || a.segments.isEmpty()) continue
            segQuery.clear()
            val headR = a.headRadius(move)
            val f = a.forward
            segmentHash.query(a.headX, a.headY, headR + 1.5f, segQuery)
            for (s in segQuery) {
                val dch = s.chain
                if (dch === a || !dch.alive) continue
                val dx = s.x - a.headX; val dy = s.y - a.headY
                val dist = sqrt(dx * dx + dy * dy)
                val pen = headR + s.radius * 0.9f - dist
                if (pen <= 0f) continue
                // Only the front half of the head bites; brushing past with the back of the head does nothing.
                if (dist > 1e-4f && (dx * f.x + dy * f.y) / dist < -0.25f) continue
                val existing = contacts.indexOfFirst { it.attackerId == a.id && it.defenderId == dch.id }
                val contact = Contact(a.id, dch.id, s.index, pen, s.level)
                if (existing < 0) contacts.add(contact) else {
                    // Prefer biting the head, then the deepest contact.
                    val e = contacts[existing]
                    if ((s.index == 0 && e.defenderSegmentIndex != 0) || (e.defenderSegmentIndex != 0 && e.penetration < pen)) contacts[existing] = contact
                }
            }
        }
        if (contacts.isEmpty()) return
        CombatResolver.resolveFrame(contacts, { id -> findChain(id)?.snapshot() }, rules, resolved)
        for (r in resolved) applyCombat(r)
    }

    private fun applyCombat(r: ResolvedContact) {
        val a = findChain(r.contact.attackerId) ?: return
        val d = findChain(r.contact.defenderId) ?: return
        when (r.outcome) {
            CombatOutcome.DEFENDER_DEFEATED -> defeat(d, a)
            CombatOutcome.BOTH_DEFEATED -> { defeat(a, null); defeat(d, null) }
            CombatOutcome.DEFENDER_SEVERED -> sever(d, a, r.contact.defenderSegmentIndex)
            CombatOutcome.BOUNCE -> {
                var away = a.headPosition - d.headPosition
                if (away.lengthSquared < 1e-4f) away = -a.forward
                bounce(a, away)
                bounce(d, -away)
                events.add(WorldEvent.Bounced(a, d, a.headX, a.headY))
            }
            CombatOutcome.BLOCKED -> block(a, d, r.contact.defenderSegmentIndex)
            CombatOutcome.NONE -> {}
        }
    }

    /** Solid contact with a cube the attacker may not eat: push the head back out and hold it back. */
    private fun block(a: Chain, d: Chain, index: Int) {
        val s = d.segments.getOrNull(index) ?: return
        val headR = a.headRadius(move)
        var nx = a.headX - s.x; var ny = a.headY - s.y
        var dist = sqrt(nx * nx + ny * ny)
        if (dist < 1e-4f) { nx = -a.forward.x; ny = -a.forward.y; dist = 1f }
        nx /= dist; ny /= dist
        val minD = headR + s.radius * 0.9f + 0.02f
        constrain(headR, s.x + nx * minD, s.y + ny * minD)
        a.trail.moveHead(legal[0], legal[1])
        slide(a, Vec2(-nx, -ny), a.forward)
        hold(a, legal[0], legal[1])
    }

    /** [attacker] eats [victim]'s cube at [index] and everything behind it. */
    private fun sever(victim: Chain, attacker: Chain, index: Int) {
        if (index <= 0 || index >= victim.model.length) return
        val at = victim.segments[index]
        val ex = at.x; val ey = at.y
        val removed = victim.model.removeFrom(index)
        val leftovers = ArrayList<Pair<Int, Vec2>>()
        var value = 0.0
        for (cell in removed) {
            val seg = victim.byId.remove(cell.id)
            value += cell.value
            val pos = if (seg != null) Vec2(seg.x, seg.y) else victim.headPosition
            if (!tryCollect(attacker, cell.level, pos.x, pos.y)) leftovers.add(cell.level to pos)
        }
        syncSegments(victim, false)
        spawnDrops(leftovers)
        val pts = ScoreCalculator.toScore(value * rules.scoreDefeatValueFraction)
        if (pts > 0) {
            attacker.stats.addScore(pts)
            events.add(WorldEvent.ScoreGained(attacker, pts, ex, ey))
        }
        events.add(WorldEvent.Severed(victim, attacker, ex, ey))
    }

    /** [victim] is eaten whole by [killer] (or burst into cubes when [killer] is null). */
    private fun defeat(victim: Chain, killer: Chain?) {
        if (!victim.alive) return
        val hx = victim.segments.firstOrNull()?.x ?: victim.headX
        val hy = victim.segments.firstOrNull()?.y ?: victim.headY
        val total = victim.model.totalValue
        val top = victim.model.headLevel
        val cubes = victim.segments.map { it.level to Vec2(it.x, it.y) }
        victim.alive = false
        victim.lastEatenBy = killer?.name
        victim.segments.clear(); victim.absorbing.clear(); victim.byId.clear()
        victim.speed = 0f; victim.wantsBoost = false
        if (killer != null && killer.alive) {
            val leftovers = cubes.filter { (level, pos) -> !tryCollect(killer, level, pos.x, pos.y) }
            spawnDrops(leftovers)
            val pts = ScoreCalculator.forDefeat(total, rules)
            killer.stats.addScore(pts)
            killer.stats.recordDefeat()
            events.add(WorldEvent.ScoreGained(killer, pts, hx, hy))
        } else spawnDrops(cubes)
        events.add(WorldEvent.Defeated(victim, killer, hx, hy, top))
        when {
            victim.isHuman && options.respawnHumans -> respawn[victim] = options.humanRespawnDelay
            victim === player -> playerDefeated = true
            victim.isHuman -> respawn[victim] = options.humanRespawnDelay
            else -> respawn[victim] = mode.rivalRespawnDelay
        }
    }

    // ------------------------------------------------------------------ collectibles

    private fun fillInitial() {
        var guard = targetCollectibles * 3
        while (collectibles.size < targetCollectibles && guard-- > 0) trySpawnRandom(false)
    }

    private fun sampleLevel(): Int {
        var level = table.sample(rng)
        val s = config.spawn
        // Progression shift follows the biggest person in the arena.
        var top = 0
        for (c in chains) if (c.alive && c.isHuman) top = max(top, c.headLevel)
        if (rng.nextFloat() < s.progressionMix)
            level += (max(0, top - s.progressionStartLevel) * s.progressionShiftPerLevel).toInt()
        return MathUtil.clamp(level, 0, NumberValue.MAX_LEVEL)
    }

    /** Tries to place one random collectible; returns false if no valid spot was found this time. */
    fun trySpawnRandom(preferNearPlayer: Boolean): Boolean {
        val level = sampleLevel()
        val people = chains.filter { it.alive && it.isHuman }
        repeat(config.spawn.maxPlacementAttempts) {
            val p = if (preferNearPlayer && people.isNotEmpty() && rng.nextFloat() < 0.5f)
                people[rng.range(0, people.size)].headPosition + Vec2.fromAngle(rng.nextFloat() * MathUtil.TWO_PI) * rng.range(18f, 50f)
            else rng.insideDisc(arenaRadius - 3f)
            if (isValidSpot(p.x, p.y)) { spawnCollectible(level, p.x, p.y, false); return true }
        }
        return false
    }

    private fun isValidSpot(x: Float, y: Float): Boolean {
        if (!isFreeSpot(x, y, 1.5f)) return false
        if (hash.anyWithin(x, y, config.spawn.minSpacing)) return false
        val minHead = config.spawn.minDistanceFromHeads
        for (c in chains) {
            if (!c.alive) continue
            val dx = c.headX - x; val dy = c.headY - y
            if (dx * dx + dy * dy < minHead * minHead) return false
            for (i in 1 until c.segments.size) {
                val s = c.segments[i]
                val sx = s.x - x; val sy = s.y - y
                val r = s.radius + 1f
                if (sx * sx + sy * sy < r * r) return false
            }
        }
        return true
    }

    fun spawnCollectible(level: Int, x: Float, y: Float, isDrop: Boolean, id: Int = nextCollectibleId++): Collectible {
        val c = Collectible(id, level, x, y, move.cubeSize(level) * 0.92f, isDrop, time)
        collectibles.add(c)
        collectibleById[id] = c
        hash.insert(c, x, y)
        if (options.trackNetChanges) netSpawned.add(c)
        return c
    }

    private fun moveCollectible(c: Collectible, x: Float, y: Float) {
        c.x = x; c.y = y; c.renderX = x; c.renderY = y
        hash.move(c, x, y)
        if (options.trackNetChanges) netMoved.add(c)
    }

    private fun spawnDrops(drops: List<Pair<Int, Vec2>>) {
        val n = CombatResolver.dropCount(drops.size, rules.dropFraction)
        for (i in 0 until n) {
            val (level, pos) = drops[i]
            var p = pos + rng.insideDisc(0.8f)
            if (p.length > arenaRadius - 2f) p = p.normalized * (arenaRadius - 2f)
            for (o in obstacles) {
                val dv = p - o.position
                val minD = o.radius + 1.2f
                if (dv.length < minD) p = o.position + (if (dv.lengthSquared > 1e-4f) dv.normalized else Vec2.UNIT_X) * minD
            }
            spawnCollectible(level, p.x, p.y, true)
        }
    }

    private fun beginCollect(col: Collectible, by: Chain?) {
        hash.remove(col)
        collectibles.remove(col)
        collectibleById.remove(col.id)
        if (options.trackNetChanges) netRemoved.add((col.id.toLong() shl 32) or ((by?.id ?: 0).toLong() and 0xffffffffL))
        if (by == null) return
        col.state = Collectible.State.COLLECTING
        col.animTime = 0f
        col.collector = by
        collecting.add(col)
    }

    private fun updateCollectibles(dt: Float) {
        for (c in collectibles) {
            c.animTime += dt
            if (c.state == Collectible.State.SPAWNING) {
                val s = min(1f, c.animTime / 0.3f)
                val t = s - 1f
                c.scale = max(0.01f, 1f + 2.2f * t * t * t + 1.2f * t * t)
                if (s >= 1f) { c.state = Collectible.State.IDLE; c.scale = 1f }
            }
        }
        val it = collecting.iterator()
        while (it.hasNext()) {
            val c = it.next()
            c.animTime += dt
            val t = min(1f, c.animTime / 0.16f)
            val target = c.collector?.segments?.firstOrNull()
            val tx = target?.x ?: c.x; val ty = target?.y ?: c.y
            c.renderX = c.x + (tx - c.x) * t * t
            c.renderY = c.y + (ty - c.y) * t * t
            c.scale = 1f - 0.8f * t
            if (t >= 1f) it.remove()
        }
    }

    private fun replenish() {
        var spawns = 0
        while (collectibles.size < targetCollectibles && spawns < config.spawn.maxSpawnsPerFrame) {
            if (!trySpawnRandom(true)) break
            spawns++
        }
        if (collectibles.size > config.spawn.maxCollectibles) {
            val old = collectibles.filter { it.isDrop && time - it.spawnTime > config.spawn.dropLifetime }
            for (c in old) {
                if (collectibles.size <= config.spawn.maxCollectibles) break
                beginCollect(c, null)
            }
        }
    }

    fun queryCollectibles(x: Float, y: Float, radius: Float, out: MutableList<Collectible>) = hash.query(x, y, radius, out)

    // ------------------------------------------------------------------ respawn & ramp

    private fun updateRespawns(dt: Float) {
        if (respawn.isEmpty()) return
        val ready = ArrayList<Chain>()
        for ((c, t) in respawn) if (t - dt <= 0f) ready.add(c) else { respawn[c] = t - dt; c.respawnCountdown = t - dt }
        for (c in ready) {
            respawn.remove(c)
            c.respawnCountdown = 0f
            if (c.isHuman) {
                spawnChain(c, findRivalSpot(false), rng.nextFloat() * MathUtil.TWO_PI, listOf(rules.startLevel), keepStats = true)
                events.add(WorldEvent.Respawned(c))
            } else spawnRival(c, false)
        }
    }

    /** Seconds until [c] respawns, or null. */
    fun respawnIn(c: Chain): Float? = respawn[c]

    private fun updateRamp(dt: Float) {
        if (mode.difficultyRampMinutes <= 0f) return
        rampTimer -= dt
        if (rampTimer > 0f) return
        rampTimer = 5f
        val ramp = mode.rampAt(time)
        for (c in chains) (c.brain as? AIBrain)?.config = baseAI.ramped(ramp)
    }

    // ------------------------------------------------------------------ network replica (clients)

    /** Creates or returns the replica of the host's chain [id]. */
    fun replicaChain(id: Int, name: String, isHuman: Boolean, skin: String, trail: String, color: Int): Chain {
        findChain(id)?.let { return it }
        val c = Chain(id, name, false, skin, trail, color, ids, rules.startLevel, config.boost.copy())
        c.isHuman = isHuman
        chains.add(c)
        return c
    }

    fun replicaSetLocalPlayer(c: Chain) { player = c }

    fun replicaRemoveChain(id: Int) { findChain(id)?.let { if (it !== player) chains.remove(it) } }

    /** Applies host state for one chain. [x], [y], [heading] are the (interpolated) head pose. */
    fun replicaApplyChain(c: Chain, alive: Boolean, protectedFlag: Boolean, boosting: Boolean, energy: Float,
                          levels: IntArray, score: Long, highest: Int, maxLen: Int, defeats: Int) {
        val wasAlive = c.alive
        c.alive = alive
        c.protection = if (protectedFlag) 1f else 0f
        c.boost.syncFromNetwork(energy, boosting)
        c.stats.syncFromNetwork(score, highest, maxLen, defeats)
        if (!alive) { c.segments.clear(); c.absorbing.clear(); c.byId.clear(); return }
        if (!wasAlive || c.segments.isEmpty()) {
            c.model.setLevels(levels.toList())
            c.byId.clear(); c.segments.clear()
            syncSegments(c, true)
        } else if (!c.model.levelsEqual(levels)) {
            val grew = c.model.replaceLevelsInPlace(levels)
            syncSegments(c, false)
            for (i in grew) c.segments.getOrNull(i)?.punch = 1f
        }
    }

    /** Moves a replica chain's head (from interpolation); a big jump re-seeds the trail. */
    fun replicaMoveHead(c: Chain, x: Float, y: Float, heading: Float, speed: Float, reset: Boolean) {
        c.heading = heading
        c.speed = speed
        if (reset) {
            c.trail.reset(Vec2(x, y), Vec2.fromAngle(heading), 30f)
            syncSegments(c, true)
        } else c.trail.moveHead(x, y)
    }

    fun replicaSpawnCollectible(id: Int, level: Int, x: Float, y: Float, isDrop: Boolean) {
        if (collectibleById.containsKey(id)) return
        spawnCollectible(level, x, y, isDrop, id)
    }

    fun replicaRemoveCollectible(id: Int, collectorId: Int) {
        val c = collectibleById[id] ?: return
        beginCollect(c, if (collectorId != 0) findChain(collectorId) else null)
    }

    fun replicaMoveCollectible(id: Int, x: Float, y: Float) { collectibleById[id]?.let { moveCollectible(it, x, y) } }

    fun replicaClearCollectibles() {
        for (c in collectibles.toList()) { hash.remove(c); collectibleById.remove(c.id) }
        collectibles.clear(); collecting.clear()
    }

    /** One replica frame: publish [incoming] events and animate. */
    fun replicaFrame(dt: Float, incoming: List<WorldEvent>) {
        events.clear()
        events.addAll(incoming)
        time += dt
        for (c in chains) if (c.alive) updateVisuals(c, dt)
        updateCollectibles(dt)
    }

    /** Rival AI brain: builds an [AIContext] every reaction tick and steers at the chosen target in between. */
    class AIBrain(var config: AIBehaviourConfig, private val rng: Rng) : Brain {
        private val ctx = AIContext()
        private val memory = AIBrainMemory().also { it.wanderAngle = rng.nextFloat() * MathUtil.TWO_PI }
        private val nearby = ArrayList<Collectible>(64)
        var decision = AIDecision(); private set
        private var timer = 0f

        fun reset(chain: Chain) { timer = 0f; memory.committedTargetId = -1; decision = AIDecision(direction = chain.forward) }

        override fun think(world: World, chain: Chain, dt: Float) {
            chain.speedMultiplier = config.speedMultiplier
            timer -= dt
            val targetGone = decision.state == AIState.COLLECT && Vec2.distanceSquared(decision.targetPosition, chain.headPosition) < 0.8f
            if (timer <= 0f || targetGone || chain.resistance > 0.9f) {
                timer = config.reactionTime + rng.range(-config.reactionJitter, config.reactionJitter)
                buildContext(world, chain)
                decision = AITargetSelector.decide(ctx, config, memory, rng)
                // Stuck against something solid: turn away for a moment.
                if (chain.resistance > 0.9f) decision = decision.copy(state = AIState.WANDER,
                    direction = Vec2.fromAngle(chain.heading + MathUtil.PI * (0.5f + rng.nextFloat())))
            }
            var dir = decision.direction
            if (decision.state == AIState.COLLECT || decision.state == AIState.CHASE) {
                var to = decision.targetPosition - chain.headPosition
                if (decision.state == AIState.CHASE) {
                    val prey = world.findChain(decision.targetId)
                    if (prey != null && prey.alive) to = prey.headPosition + prey.velocity * config.chaseLeadTime - chain.headPosition
                }
                if (to.lengthSquared > 0.01f) dir = to.normalized
            }
            chain.desiredDirection = dir
            chain.wantsBoost = decision.boost
        }

        private fun buildContext(world: World, chain: Chain) {
            ctx.clearLists()
            ctx.position = chain.headPosition
            ctx.forward = chain.forward
            ctx.headLevel = chain.headLevel
            ctx.boostEnergy01 = chain.boost.energy
            ctx.arenaRadius = world.arenaRadius
            nearby.clear()
            world.queryCollectibles(chain.headX, chain.headY, config.sightRadius, nearby)
            for (i in 0 until min(nearby.size, 48)) {
                val c = nearby[i]
                val can = world.canCollect(chain, c.level)
                ctx.collectibles.add(AICollectibleInfo(c.id, Vec2(c.x, c.y), c.level, can))
                // Bigger cubes are solid: steer around the ones close by.
                if (!can && Vec2.distance(Vec2(c.x, c.y), chain.headPosition) < 6f) ctx.obstacles.add(AIObstacleInfo(Vec2(c.x, c.y), c.radius + 0.4f))
            }
            val me = chain.snapshot()
            val interest = max(config.fleeRadius, config.chaseRadius) * 1.4f
            for (o in world.chains) {
                if (o === chain || !o.alive) continue
                if (Vec2.distance(o.headPosition, chain.headPosition) > interest + o.model.length) continue
                val snap = o.snapshot()
                val cmp = CombatResolver.compareStrength(snap, me, world.rules.strengthMetric)
                val dangerous = cmp > 0 && o.canDefeatOthers && chain.canBeDefeated && !o.isProtected
                val prey = cmp < 0 && chain.canDefeatOthers && o.canBeDefeated && !o.isProtected
                ctx.rivals.add(AIRivalInfo(o.id, o.headPosition, o.velocity, cmp, snap.totalValue, dangerous, prey))
                if (dangerous) for (i in 1 until o.segments.size) {
                    val s = o.segments[i]
                    val p = Vec2(s.x, s.y)
                    if (Vec2.distance(p, chain.headPosition) < config.fleeRadius) ctx.dangerPoints.add(p)
                }
            }
            for (o in world.obstacles) if (Vec2.distance(o.position, chain.headPosition) < o.radius + 8f)
                ctx.obstacles.add(AIObstacleInfo(o.position, o.radius))
        }
    }

    companion object {
        const val MAX_SUBSTEP = 1f / 60f
        val RIVAL_NAMES = listOf(
            "Blocky", "Cubert", "Quadra", "Hexa", "Pixel", "Tetro", "Voxel", "Brick", "Prism", "Nova", "Bolt", "Zappy",
            "Mochi", "Dicey", "Kubo", "Nimbus", "Sprout", "Rumble", "Echo", "Glint", "Orbit", "Pebble", "Turbo", "Wobble",
        )
        val TEAM_COLORS = intArrayOf(
            0xFFFF7373.toInt(), 0xFF73D9FF.toInt(), 0xFF99FF80.toInt(), 0xFFFFCC59.toInt(), 0xFFD98CFF.toInt(), 0xFFFF99D9.toInt(),
            0xFF66FFD9.toInt(), 0xFFFFA64D.toInt(), 0xFFB3BFFF.toInt(), 0xFFE6FF73.toInt(), 0xFFFF80A6.toInt(), 0xFF8CF2B3.toInt(),
        )
    }
}

/** Simple player brain: the app writes [direction] / [boost] from touch input. */
class InputBrain : Brain {
    @Volatile var direction: Vec2? = null
    @Volatile var boost = false
    var turnSensitivity = 1f
    override fun think(world: World, chain: Chain, dt: Float) {
        chain.turnSensitivity = turnSensitivity
        chain.desiredDirection = direction
        chain.wantsBoost = boost
    }
}
