package com.oxdh.cubeclash.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class AIDifficulty { EASY, NORMAL, HARD }

/** Every tunable AI parameter. Difficulties are presets of this single class (one AI implementation). */
data class AIBehaviourConfig(
    var speedMultiplier: Float = 0.9f,
    var reactionTime: Float = 0.45f,
    var reactionJitter: Float = 0.15f,
    var sightRadius: Float = 22f,
    var valueWeight: Float = 1f,
    var valueExponent: Float = 0.6f,
    var distanceWeight: Float = 1f,
    var riskWeight: Float = 1f,
    var aggression: Float = 0.35f,
    var fleeRadius: Float = 10f,
    var chaseRadius: Float = 14f,
    var decisionNoise: Float = 0.25f,
    var mistakeChance: Float = 0.12f,
    var threatAwareness: Float = 0.8f,
    var chaseLeadTime: Float = 0.3f,
    var boostWhenFleeing: Boolean = true,
    var boostWhenChasing: Boolean = false,
    var boostEnergyThreshold: Float = 0.5f,
) {
    /** Survival Challenge: rivals get stronger; t = 0..1. */
    fun ramped(tIn: Float): AIBehaviourConfig {
        val t = MathUtil.clamp01(tIn)
        return copy(
            speedMultiplier = MathUtil.lerp(speedMultiplier, max(speedMultiplier, 1.02f), t),
            reactionTime = MathUtil.lerp(reactionTime, min(reactionTime, 0.2f), t),
            aggression = MathUtil.lerp(aggression, max(aggression, 0.85f), t),
            mistakeChance = MathUtil.lerp(mistakeChance, min(mistakeChance, 0.03f), t),
            chaseRadius = MathUtil.lerp(chaseRadius, max(chaseRadius, 24f), t),
            boostWhenChasing = boostWhenChasing || t > 0.5f,
        )
    }

    /** Endless Growth: rivals never hunt. */
    fun passive() = copy(aggression = 0f, boostWhenChasing = false)

    companion object {
        fun forDifficulty(d: AIDifficulty): AIBehaviourConfig = when (d) {
            AIDifficulty.EASY -> AIBehaviourConfig(
                speedMultiplier = 0.78f, reactionTime = 0.8f, reactionJitter = 0.3f, sightRadius = 15f,
                valueWeight = 0.8f, valueExponent = 0.4f, distanceWeight = 1.3f, riskWeight = 0.4f,
                aggression = 0.08f, fleeRadius = 7f, chaseRadius = 8f, decisionNoise = 0.5f,
                mistakeChance = 0.3f, threatAwareness = 0.5f, chaseLeadTime = 0f,
                boostWhenFleeing = false, boostWhenChasing = false, boostEnergyThreshold = 0.8f)
            AIDifficulty.HARD -> AIBehaviourConfig(
                speedMultiplier = 0.98f, reactionTime = 0.22f, reactionJitter = 0.08f, sightRadius = 30f,
                valueWeight = 1.2f, valueExponent = 0.75f, distanceWeight = 0.9f, riskWeight = 1.5f,
                aggression = 0.7f, fleeRadius = 13f, chaseRadius = 20f, decisionNoise = 0.1f,
                mistakeChance = 0.04f, threatAwareness = 0.97f, chaseLeadTime = 0.45f,
                boostWhenFleeing = true, boostWhenChasing = true, boostEnergyThreshold = 0.35f)
            AIDifficulty.NORMAL -> AIBehaviourConfig()
        }
    }
}

enum class AIState { WANDER, COLLECT, CHASE, FLEE, AVOID_BOUNDARY }

data class AICollectibleInfo(val id: Int, val position: Vec2, val level: Int, val eligible: Boolean)

data class AIRivalInfo(
    val chainId: Int,
    val headPosition: Vec2,
    val headVelocity: Vec2 = Vec2.ZERO,
    /** > 0: rival stronger than self; < 0: weaker; 0: equal. */
    val strengthComparison: Int,
    val totalValue: Double = 0.0,
    val isDangerous: Boolean = false,
    val isPrey: Boolean = false,
)

data class AIObstacleInfo(val position: Vec2, val radius: Float)

/** Input for one AI decision (lists are reused by the caller). */
class AIContext {
    var position = Vec2.ZERO
    var forward = Vec2.UNIT_Y
    var headLevel = 0
    var boostEnergy01 = 1f
    var arenaRadius = 100f
    val collectibles = ArrayList<AICollectibleInfo>(64)
    val rivals = ArrayList<AIRivalInfo>(16)
    val dangerPoints = ArrayList<Vec2>(64)
    val obstacles = ArrayList<AIObstacleInfo>(16)
    fun clearLists() { collectibles.clear(); rivals.clear(); dangerPoints.clear(); obstacles.clear() }
}

data class AIDecision(
    val state: AIState = AIState.WANDER,
    val direction: Vec2 = Vec2.UNIT_Y,
    val targetPosition: Vec2 = Vec2.ZERO,
    val targetId: Int = -1,
    val boost: Boolean = false,
)

class AIBrainMemory {
    var wanderAngle = 0f
    var committedTargetId = -1
    var lastState = AIState.WANDER
}

/**
 * Pure decision function: scores collectibles by value / distance / risk, flees stronger chains, hunts weaker ones
 * by aggression, wanders otherwise, and adds deliberate imperfection (noise, mistakes, missed threats).
 */
object AITargetSelector {
    fun decide(ctx: AIContext, cfg: AIBehaviourConfig, memory: AIBrainMemory, rng: Rng): AIDecision {
        // 1) Threats.
        var flee = Vec2.ZERO
        if (rng.nextFloat() < cfg.threatAwareness) {
            for (r in ctx.rivals) {
                if (!r.isDangerous || r.strengthComparison <= 0) continue
                val away = ctx.position - r.headPosition
                val d = away.length
                if (d >= cfg.fleeRadius || d < 1e-4f) continue
                flee += away / d * (1f - d / cfg.fleeRadius)
            }
            val radius = cfg.fleeRadius * 0.45f
            for (p in ctx.dangerPoints) {
                val away = ctx.position - p
                val d = away.length
                if (d >= radius || d < 1e-4f) continue
                if (Vec2.dot(-away / d, ctx.forward) < -0.2f) continue
                flee += away / d * (1f - d / radius) * 0.7f
            }
        }
        if (flee.length > 0.15f) {
            val boost = cfg.boostWhenFleeing && ctx.boostEnergy01 >= cfg.boostEnergyThreshold && flee.length > 0.5f
            val d = finish(AIDecision(AIState.FLEE, flee.normalized, boost = boost), ctx, cfg, rng)
            memory.lastState = d.state; memory.committedTargetId = -1
            return d
        }

        // 2) Prey.
        var bestPrey = 0f
        var prey: AIRivalInfo? = null
        if (cfg.aggression > 0f) for (r in ctx.rivals) {
            if (!r.isPrey || r.strengthComparison >= 0) continue
            val d = Vec2.distance(ctx.position, r.headPosition)
            if (d >= cfg.chaseRadius) continue
            val s = cfg.aggression * max(1.0, r.totalValue).pow(cfg.valueExponent.toDouble()).toFloat() / (1f + d * cfg.distanceWeight)
            if (s > bestPrey) { bestPrey = s; prey = r }
        }

        // 3) Collectibles.
        var best = 0f; var second = 0f
        var bestC: AICollectibleInfo? = null; var secondC: AICollectibleInfo? = null
        val sight2 = cfg.sightRadius * cfg.sightRadius
        for (c in ctx.collectibles) {
            if (!c.eligible) continue
            val d2 = Vec2.distanceSquared(ctx.position, c.position)
            if (d2 > sight2) continue
            val d = sqrt(d2)
            val value = NumberValue.valueOf(c.level).pow(cfg.valueExponent.toDouble()).toFloat()
            var s = cfg.valueWeight * value / (1f + d * cfg.distanceWeight)
            val to = if (d > 1e-4f) (c.position - ctx.position) / d else ctx.forward
            s *= 0.75f + 0.25f * (Vec2.dot(to, ctx.forward) + 1f)
            if (c.id == memory.committedTargetId) s *= 1.25f
            if (cfg.riskWeight > 0) for (r in ctx.rivals) {
                if (!r.isDangerous || r.strengthComparison <= 0) continue
                val dr = Vec2.distance(r.headPosition, c.position)
                if (dr < cfg.fleeRadius) s *= MathUtil.clamp01(1f - cfg.riskWeight * (1f - dr / cfg.fleeRadius))
            }
            if (s > best) { second = best; secondC = bestC; best = s; bestC = c }
            else if (s > second) { second = s; secondC = c }
        }

        val mistake = rng.nextFloat() < cfg.mistakeChance
        var decision = when {
            prey != null && bestPrey > best * 0.6f && !mistake -> {
                val predicted = prey.headPosition + prey.headVelocity * cfg.chaseLeadTime
                AIDecision(AIState.CHASE, (predicted - ctx.position).normalized, predicted, prey.chainId,
                    cfg.boostWhenChasing && ctx.boostEnergy01 >= cfg.boostEnergyThreshold &&
                        Vec2.distance(predicted, ctx.position) < cfg.chaseRadius * 0.6f)
            }
            bestC != null -> {
                val c = if (mistake && secondC != null) secondC else bestC
                AIDecision(AIState.COLLECT, (c.position - ctx.position).normalized, c.position, c.id)
            }
            else -> {
                memory.wanderAngle = MathUtil.wrapAngle(memory.wanderAngle + rng.range(-0.8f, 0.8f))
                var dir = Vec2.fromAngle(memory.wanderAngle)
                val edge = ctx.position.length / max(1f, ctx.arenaRadius)
                if (edge > 0.6f) dir = (dir + (-ctx.position.normalized) * ((edge - 0.4f) * 2f)).normalized
                AIDecision(AIState.WANDER, dir)
            }
        }
        if (decision.direction.lengthSquared < 1e-6f) decision = decision.copy(direction = ctx.forward)
        decision = finish(decision, ctx, cfg, rng)
        memory.lastState = decision.state
        memory.committedTargetId = if (decision.state == AIState.COLLECT) decision.targetId else -1
        return decision
    }

    private fun finish(dIn: AIDecision, ctx: AIContext, cfg: AIBehaviourConfig, rng: Rng): AIDecision {
        var d = dIn
        var dir = d.direction
        val dist = ctx.position.length
        val edgeStart = ctx.arenaRadius * 0.88f
        if (dist > edgeStart) {
            val k = MathUtil.clamp01((dist - edgeStart) / max(1f, ctx.arenaRadius - edgeStart))
            dir = (dir * (1f - k) + (-ctx.position.normalized) * (k * 2f)).normalized
            if (k > 0.5f && d.state != AIState.FLEE) d = d.copy(state = AIState.AVOID_BOUNDARY)
        }
        for (o in ctx.obstacles) {
            val to = o.position - ctx.position
            val dd = to.length
            if (dd > o.radius + 4f || dd < 1e-4f) continue
            val toN = to / dd
            if (Vec2.dot(toN, dir) <= 0f) continue
            var tangent = toN.perpendicular
            if (Vec2.dot(tangent, dir) < 0) tangent = -tangent
            val k = MathUtil.clamp01(1f - (dd - o.radius) / 4f)
            dir = (dir + tangent * (k * 1.5f) - toN * k).normalized
        }
        if (cfg.decisionNoise > 0) dir = Vec2.fromAngle(dir.angle + rng.range(-cfg.decisionNoise, cfg.decisionNoise))
        return d.copy(direction = if (dir.lengthSquared > 1e-6f) dir.normalized else ctx.forward)
    }
}
