using System;

namespace CubeSnake.Logic
{
    public enum BoostMode
    {
        /// <summary>Boost drains an energy bar that regenerates after a short delay.</summary>
        Energy = 0,
        /// <summary>Boost lasts a fixed duration, then a cooldown must elapse.</summary>
        Cooldown = 1,
    }

    [Serializable]
    public class BoostConfig
    {
        public BoostMode mode = BoostMode.Energy;
        public float speedMultiplier = 1.75f;
        /// <summary>Energy per second consumed while boosting (energy is 0..1).</summary>
        public float drainPerSecond = 0.38f;
        /// <summary>Energy per second regained while not boosting.</summary>
        public float regenPerSecond = 0.16f;
        /// <summary>Seconds after boosting before regeneration starts.</summary>
        public float regenDelay = 0.6f;
        /// <summary>Minimum energy required to START a boost (prevents flicker at empty).</summary>
        public float minEnergyToStart = 0.12f;
        public float cooldownBoostDuration = 1.6f;
        public float cooldownSeconds = 4f;

        public BoostConfig Clone() => (BoostConfig)MemberwiseClone();
    }

    /// <summary>Frame-rate independent boost resource. Pure logic; the chain controller reads IsBoosting.</summary>
    public sealed class BoostMeter
    {
        readonly BoostConfig _cfg;
        float _regenTimer;
        float _boostTimer;
        float _cooldownTimer;

        public float Energy { get; private set; } = 1f;
        public bool IsBoosting { get; private set; }
        public float RegenMultiplier { get; set; } = 1f;
        public BoostConfig Config => _cfg;

        public BoostMeter(BoostConfig cfg) { _cfg = cfg ?? new BoostConfig(); }

        /// <summary>Normalised value for HUD display (energy, or cooldown progress).</summary>
        public float Display01
        {
            get
            {
                if (_cfg.mode == BoostMode.Energy) return Energy;
                if (IsBoosting) return 1f - _boostTimer / Math.Max(0.01f, _cfg.cooldownBoostDuration);
                return 1f - _cooldownTimer / Math.Max(0.01f, _cfg.cooldownSeconds);
            }
        }

        public bool Ready => _cfg.mode == BoostMode.Energy ? Energy >= _cfg.minEnergyToStart : (_cooldownTimer <= 0f && !IsBoosting);

        public void Refill()
        {
            Energy = 1f;
            _cooldownTimer = 0f;
            _boostTimer = 0f;
            IsBoosting = false;
        }

        public void Update(float dt, bool wantsBoost)
        {
            if (dt <= 0f) return;
            if (_cfg.mode == BoostMode.Energy) UpdateEnergy(dt, wantsBoost);
            else UpdateCooldown(dt, wantsBoost);
        }

        void UpdateEnergy(float dt, bool wants)
        {
            if (wants && (IsBoosting ? Energy > 0f : Energy >= _cfg.minEnergyToStart))
            {
                IsBoosting = true;
                Energy = Math.Max(0f, Energy - _cfg.drainPerSecond * dt);
                _regenTimer = _cfg.regenDelay;
                if (Energy <= 0f) IsBoosting = false;
                return;
            }
            IsBoosting = false;
            if (_regenTimer > 0f) { _regenTimer -= dt; return; }
            Energy = Math.Min(1f, Energy + _cfg.regenPerSecond * RegenMultiplier * dt);
        }

        void UpdateCooldown(float dt, bool wants)
        {
            if (IsBoosting)
            {
                _boostTimer += dt;
                if (_boostTimer >= _cfg.cooldownBoostDuration)
                {
                    IsBoosting = false;
                    _cooldownTimer = _cfg.cooldownSeconds;
                }
                Energy = Display01;
                return;
            }
            if (_cooldownTimer > 0f) _cooldownTimer = Math.Max(0f, _cooldownTimer - dt * RegenMultiplier);
            if (wants && _cooldownTimer <= 0f)
            {
                IsBoosting = true;
                _boostTimer = 0f;
            }
            Energy = Display01;
        }
    }

    [Serializable]
    public class MovementConfig
    {
        public float baseSpeed = 7.5f;
        public float acceleration = 14f;
        public float deceleration = 10f;
        /// <summary>Multiplier on maxTurnRate, exposed as a player setting.</summary>
        public float turnSensitivity = 1f;
        /// <summary>Degrees per second.</summary>
        public float maxTurnRateDegrees = 280f;
        /// <summary>Gap between consecutive cubes as a fraction of their average size.</summary>
        public float chainGapFactor = 1.08f;
        /// <summary>How quickly cubes slide to their path distance after merges / insertions.</summary>
        public float followSharpness = 10f;
        /// <summary>Base edge length of a level-0 cube.</summary>
        public float baseCubeSize = 1.1f;
        /// <summary>Extra size per level.</summary>
        public float sizePerLevel = 0.06f;
        public float maxCubeSize = 2.6f;
        /// <summary>Speed reduction per level of the head (bigger chains are slightly slower).</summary>
        public float speedLossPerLevel = 0.012f;
        public float minSpeedFactor = 0.75f;

        public MovementConfig Clone() => (MovementConfig)MemberwiseClone();

        public float CubeSize(int level) => Math.Min(maxCubeSize, baseCubeSize * (1f + sizePerLevel * level));
        public float SpeedFactor(int headLevel) => Math.Max(minSpeedFactor, 1f - speedLossPerLevel * headLevel);
    }
}
