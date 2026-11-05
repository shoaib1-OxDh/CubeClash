package com.oxdh.cubeclash.engine

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class BoostMode { ENERGY, COOLDOWN }

data class BoostConfig(
    var mode: BoostMode = BoostMode.ENERGY,
    var speedMultiplier: Float = 1.75f,
    var drainPerSecond: Float = 0.38f,
    var regenPerSecond: Float = 0.16f,
    var regenDelay: Float = 0.6f,
    var minEnergyToStart: Float = 0.12f,
    var cooldownBoostDuration: Float = 1.6f,
    var cooldownSeconds: Float = 4f,
)

/** Frame-rate independent boost resource. */
class BoostMeter(val config: BoostConfig = BoostConfig()) {
    private var regenTimer = 0f
    private var boostTimer = 0f
    private var cooldownTimer = 0f
    var energy = 1f
        private set
    var isBoosting = false
        private set
    var regenMultiplier = 1f

    /** 0..1 for the HUD: energy, or cooldown progress. */
    val display01: Float
        get() = when {
            config.mode == BoostMode.ENERGY -> energy
            isBoosting -> 1f - boostTimer / max(0.01f, config.cooldownBoostDuration)
            else -> 1f - cooldownTimer / max(0.01f, config.cooldownSeconds)
        }

    val ready: Boolean get() = if (config.mode == BoostMode.ENERGY) energy >= config.minEnergyToStart else cooldownTimer <= 0f && !isBoosting

    fun refill() { energy = 1f; cooldownTimer = 0f; boostTimer = 0f; isBoosting = false }

    /** Network replicas mirror the host's boost state. */
    fun syncFromNetwork(energy: Float, boosting: Boolean) { this.energy = energy.coerceIn(0f, 1f); isBoosting = boosting }

    fun update(dt: Float, wantsBoost: Boolean) {
        if (dt <= 0f) return
        if (config.mode == BoostMode.ENERGY) updateEnergy(dt, wantsBoost) else updateCooldown(dt, wantsBoost)
    }

    private fun updateEnergy(dt: Float, wants: Boolean) {
        if (wants && (if (isBoosting) energy > 0f else energy >= config.minEnergyToStart)) {
            isBoosting = true
            energy = max(0f, energy - config.drainPerSecond * dt)
            regenTimer = config.regenDelay
            if (energy <= 0f) isBoosting = false
            return
        }
        isBoosting = false
        if (regenTimer > 0f) { regenTimer -= dt; return }
        energy = min(1f, energy + config.regenPerSecond * regenMultiplier * dt)
    }

    private fun updateCooldown(dt: Float, wants: Boolean) {
        if (isBoosting) {
            boostTimer += dt
            if (boostTimer >= config.cooldownBoostDuration) { isBoosting = false; cooldownTimer = config.cooldownSeconds }
            energy = display01
            return
        }
        if (cooldownTimer > 0f) cooldownTimer = max(0f, cooldownTimer - dt * regenMultiplier)
        if (wants && cooldownTimer <= 0f) { isBoosting = true; boostTimer = 0f }
        energy = display01
    }
}

data class MovementConfig(
    var baseSpeed: Float = 7.5f,
    var acceleration: Float = 14f,
    var deceleration: Float = 10f,
    var turnSensitivity: Float = 1f,
    /** Degrees per second. */
    var maxTurnRateDegrees: Float = 360f,
    /** Gap between consecutive cubes as a fraction of their average size. */
    var chainGapFactor: Float = 1.08f,
    var followSharpness: Float = 10f,
    var baseCubeSize: Float = 1.1f,
    var sizePerLevel: Float = 0.06f,
    var maxCubeSize: Float = 2.6f,
    var speedLossPerLevel: Float = 0.012f,
    var minSpeedFactor: Float = 0.75f,
) {
    fun cubeSize(level: Int) = min(maxCubeSize, baseCubeSize * (1f + sizePerLevel * level))
    fun speedFactor(headLevel: Int) = max(minSpeedFactor, 1f - speedLossPerLevel * headLevel)
}

data class SpawnConfig(
    var targetCollectibles: Int = 260,
    var maxCollectibles: Int = 520,
    var minSpacing: Float = 2.2f,
    var minDistanceFromHeads: Float = 6f,
    var maxPlacementAttempts: Int = 12,
    var maxSpawnsPerFrame: Int = 6,
    var distribution: List<WeightedLevel> = WeightedTable.defaultDistribution(),
    /** Fraction of spawns shifted up by the player's progress. */
    var progressionMix: Float = 0.4f,
    var progressionStartLevel: Int = 3,
    var progressionShiftPerLevel: Float = 0.5f,
    /** Seconds before an old drop may be recycled when over budget. */
    var dropLifetime: Float = 40f,
)

data class ArenaConfig(
    var radius: Float = 120f,
    var obstacleCount: Int = 14,
    var obstacleMinRadius: Float = 2f,
    var obstacleMaxRadius: Float = 4.5f,
    var boundaryWarningDistance: Float = 14f,
)

/** Everything the simulation needs; defaults are the shipped game balance. */
data class GameConfig(
    val rules: RulesConfig = RulesConfig(),
    val movement: MovementConfig = MovementConfig(),
    val boost: BoostConfig = BoostConfig(),
    val spawn: SpawnConfig = SpawnConfig(),
    val arena: ArenaConfig = ArenaConfig(),
    val aiEasy: AIBehaviourConfig = AIBehaviourConfig.forDifficulty(AIDifficulty.EASY),
    val aiNormal: AIBehaviourConfig = AIBehaviourConfig.forDifficulty(AIDifficulty.NORMAL),
    val aiHard: AIBehaviourConfig = AIBehaviourConfig.forDifficulty(AIDifficulty.HARD),
    /** Cubes at or above this level glow (64 = level 6). */
    val glowLevel: Int = 6,
) {
    fun aiFor(d: AIDifficulty) = when (d) { AIDifficulty.EASY -> aiEasy; AIDifficulty.NORMAL -> aiNormal; AIDifficulty.HARD -> aiHard }
}

enum class GameMode { CLASSIC, ENDLESS_GROWTH, SURVIVAL_CHALLENGE }

/** Per-mode rules. The main loop is identical; only these values change. */
data class GameModeSettings(
    val mode: GameMode,
    val displayName: String,
    val description: String,
    val rivalCount: Int = 9,
    val playerCanBeDefeated: Boolean = true,
    val rivalsAggressive: Boolean = true,
    val collectibleDensityMultiplier: Float = 1f,
    val boostRegenMultiplier: Float = 1f,
    val rivalRespawnDelay: Float = 4f,
    val difficultyRampMinutes: Float = 0f,
    val rivalLevelPerMinute: Float = 0f,
    val rivalBaseMaxLevel: Int = 3,
) {
    fun rampAt(elapsedSeconds: Double): Float =
        if (difficultyRampMinutes <= 0) 0f else MathUtil.clamp01((elapsedSeconds / 60.0 / difficultyRampMinutes).toFloat())

    fun rivalMaxLevelAt(elapsedSeconds: Double): Int = rivalBaseMaxLevel + floor(elapsedSeconds / 60.0 * rivalLevelPerMinute).toInt()

    companion object {
        fun forMode(mode: GameMode): GameModeSettings = when (mode) {
            GameMode.ENDLESS_GROWTH -> GameModeSettings(mode, "Endless Growth",
                "Relaxed exploration. Rivals never hunt you and you cannot be defeated. Reach the biggest number you can!",
                rivalCount = 7, playerCanBeDefeated = false, rivalsAggressive = false, collectibleDensityMultiplier = 1.35f,
                boostRegenMultiplier = 1.5f, rivalRespawnDelay = 3f, rivalBaseMaxLevel = 3)
            GameMode.SURVIVAL_CHALLENGE -> GameModeSettings(mode, "Survival Challenge",
                "Rivals grow stronger and more aggressive every minute and boost energy recovers slowly. How long can you last?",
                rivalCount = 10, collectibleDensityMultiplier = 0.9f, boostRegenMultiplier = 0.5f, rivalRespawnDelay = 3f,
                difficultyRampMinutes = 6f, rivalLevelPerMinute = 0.8f, rivalBaseMaxLevel = 3)
            GameMode.CLASSIC -> GameModeSettings(mode, "Classic Arena",
                "Collect cubes, merge numbers, outgrow your rivals and survive as long as possible.",
                rivalCount = 9, rivalRespawnDelay = 4f, rivalBaseMaxLevel = 4)
        }
    }
}
