using System;

namespace CubeSnake.Logic
{
    public enum AIDifficulty { Easy = 0, Normal = 1, Hard = 2 }

    /// <summary>
    /// Every tunable AI parameter. Difficulty levels are just presets of this one class; there is a single
    /// AI implementation (AIController + AITargetSelector).
    /// </summary>
    [Serializable]
    public class AIBehaviourConfig
    {
        /// <summary>Multiplier applied to the chain's base movement speed.</summary>
        public float speedMultiplier = 0.9f;
        /// <summary>Seconds between decisions (reaction time). Randomised by +-reactionJitter.</summary>
        public float reactionTime = 0.45f;
        public float reactionJitter = 0.15f;
        /// <summary>How far the AI can "see" collectibles.</summary>
        public float sightRadius = 22f;
        /// <summary>Weight of cube value in target scoring.</summary>
        public float valueWeight = 1f;
        /// <summary>Exponent applied to the value (lower = cares less about big cubes).</summary>
        public float valueExponent = 0.6f;
        /// <summary>Weight of distance in target scoring.</summary>
        public float distanceWeight = 1f;
        /// <summary>How strongly collectibles near threats are penalised (0..1+).</summary>
        public float riskWeight = 1f;
        /// <summary>0 = never hunts, 1 = always hunts weaker chains in range.</summary>
        public float aggression = 0.35f;
        /// <summary>Distance at which stronger chains are considered a threat.</summary>
        public float fleeRadius = 10f;
        /// <summary>Distance at which weaker chains are considered prey.</summary>
        public float chaseRadius = 14f;
        /// <summary>Random steering error in radians.</summary>
        public float decisionNoise = 0.25f;
        /// <summary>Probability of making a sub-optimal choice on a decision.</summary>
        public float mistakeChance = 0.12f;
        /// <summary>Probability of noticing a threat at all on a given decision (lower = sloppier).</summary>
        public float threatAwareness = 0.8f;
        /// <summary>Seconds of predicted movement used when chasing (lead the target).</summary>
        public float chaseLeadTime = 0.3f;
        public bool boostWhenFleeing = true;
        public bool boostWhenChasing = false;
        /// <summary>Minimum boost energy (0..1) before the AI decides to boost.</summary>
        public float boostEnergyThreshold = 0.5f;

        public AIBehaviourConfig Clone() => (AIBehaviourConfig)MemberwiseClone();

        public static AIBehaviourConfig ForDifficulty(AIDifficulty difficulty)
        {
            switch (difficulty)
            {
                case AIDifficulty.Easy:
                    return new AIBehaviourConfig
                    {
                        speedMultiplier = 0.78f, reactionTime = 0.8f, reactionJitter = 0.3f, sightRadius = 15f,
                        valueWeight = 0.8f, valueExponent = 0.4f, distanceWeight = 1.3f, riskWeight = 0.4f,
                        aggression = 0.08f, fleeRadius = 7f, chaseRadius = 8f, decisionNoise = 0.5f,
                        mistakeChance = 0.3f, threatAwareness = 0.5f, chaseLeadTime = 0f,
                        boostWhenFleeing = false, boostWhenChasing = false, boostEnergyThreshold = 0.8f,
                    };
                case AIDifficulty.Hard:
                    return new AIBehaviourConfig
                    {
                        speedMultiplier = 0.98f, reactionTime = 0.22f, reactionJitter = 0.08f, sightRadius = 30f,
                        valueWeight = 1.2f, valueExponent = 0.75f, distanceWeight = 0.9f, riskWeight = 1.5f,
                        aggression = 0.7f, fleeRadius = 13f, chaseRadius = 20f, decisionNoise = 0.1f,
                        mistakeChance = 0.04f, threatAwareness = 0.97f, chaseLeadTime = 0.45f,
                        boostWhenFleeing = true, boostWhenChasing = true, boostEnergyThreshold = 0.35f,
                    };
                default:
                    return new AIBehaviourConfig();
            }
        }

        /// <summary>Survival Challenge: rivals get stronger over time. t = 0..1 ramp.</summary>
        public AIBehaviourConfig Ramped(float t)
        {
            t = MathUtil.Clamp01(t);
            var c = Clone();
            c.speedMultiplier = MathUtil.Lerp(speedMultiplier, Math.Max(speedMultiplier, 1.02f), t);
            c.reactionTime = MathUtil.Lerp(reactionTime, Math.Min(reactionTime, 0.2f), t);
            c.aggression = MathUtil.Lerp(aggression, Math.Max(aggression, 0.85f), t);
            c.mistakeChance = MathUtil.Lerp(mistakeChance, Math.Min(mistakeChance, 0.03f), t);
            c.chaseRadius = MathUtil.Lerp(chaseRadius, Math.Max(chaseRadius, 24f), t);
            c.boostWhenChasing = boostWhenChasing || t > 0.5f;
            return c;
        }

        /// <summary>Endless Growth: rivals never hunt.</summary>
        public AIBehaviourConfig Passive()
        {
            var c = Clone();
            c.aggression = 0f;
            c.boostWhenChasing = false;
            return c;
        }
    }
}
