using System;
using System.Collections.Generic;

namespace CubeSnake.Logic
{
    public enum AIState { Wander, Collect, Chase, Flee, AvoidBoundary }

    public struct AICollectibleInfo
    {
        public int Id;
        public Vec2 Position;
        public int Level;
        public bool Eligible;
    }

    public struct AIRivalInfo
    {
        public int ChainId;
        public Vec2 HeadPosition;
        public Vec2 HeadVelocity;
        /// <summary>Positive: rival stronger than self; negative: weaker; 0: equal.</summary>
        public int StrengthComparison;
        public double TotalValue;
        /// <summary>Whether that rival is allowed to defeat us (false for passive rivals / protected).</summary>
        public bool IsDangerous;
        /// <summary>Whether we are allowed to defeat it.</summary>
        public bool IsPrey;
    }

    public struct AIObstacleInfo
    {
        public Vec2 Position;
        public float Radius;
    }

    /// <summary>Input for one AI decision. Lists are filled by the caller (reused, no per-decision allocation).</summary>
    public sealed class AIContext
    {
        public Vec2 Position;
        public Vec2 Forward = Vec2.UnitY;
        public int HeadLevel;
        public float BoostEnergy01;
        public float ArenaRadius = 100f;
        public readonly List<AICollectibleInfo> Collectibles = new List<AICollectibleInfo>(64);
        public readonly List<AIRivalInfo> Rivals = new List<AIRivalInfo>(16);
        /// <summary>Body cubes of dangerous (stronger) chains near us, to steer around.</summary>
        public readonly List<Vec2> DangerPoints = new List<Vec2>(64);
        public readonly List<AIObstacleInfo> Obstacles = new List<AIObstacleInfo>(16);

        public void ClearLists()
        {
            Collectibles.Clear();
            Rivals.Clear();
            DangerPoints.Clear();
            Obstacles.Clear();
        }
    }

    public struct AIDecision
    {
        public AIState State;
        public Vec2 Direction;
        public Vec2 TargetPosition;
        public int TargetId;
        public bool Boost;
    }

    /// <summary>Per-AI memory between decisions.</summary>
    public sealed class AIBrainMemory
    {
        public float WanderAngle;
        public int CommittedTargetId = -1;
        public AIState LastState = AIState.Wander;
    }

    /// <summary>
    /// Pure decision function for rival chains: scores collectibles by value / distance / risk, flees stronger
    /// chains, hunts weaker ones based on aggression, wanders otherwise, and adds deliberate imperfection.
    /// </summary>
    public static class AITargetSelector
    {
        public static AIDecision Decide(AIContext ctx, AIBehaviourConfig cfg, AIBrainMemory memory, Rng rng)
        {
            var decision = new AIDecision { State = AIState.Wander, TargetId = -1 };

            // 1) Threat assessment -------------------------------------------------------------
            Vec2 flee = Vec2.Zero;
            bool noticedThreat = rng.NextFloat() < cfg.threatAwareness;
            if (noticedThreat)
            {
                for (int i = 0; i < ctx.Rivals.Count; i++)
                {
                    var r = ctx.Rivals[i];
                    if (!r.IsDangerous || r.StrengthComparison <= 0) continue;
                    Vec2 away = ctx.Position - r.HeadPosition;
                    float d = away.Length;
                    if (d >= cfg.fleeRadius || d < 1e-4f) continue;
                    flee += away / d * (1f - d / cfg.fleeRadius);
                }
                for (int i = 0; i < ctx.DangerPoints.Count; i++)
                {
                    Vec2 away = ctx.Position - ctx.DangerPoints[i];
                    float d = away.Length;
                    float radius = cfg.fleeRadius * 0.45f;
                    if (d >= radius || d < 1e-4f) continue;
                    // Only care about danger roughly in front of us.
                    if (Vec2.Dot(-away / d, ctx.Forward) < -0.2f) continue;
                    flee += away / d * (1f - d / radius) * 0.7f;
                }
            }

            if (flee.Length > 0.15f)
            {
                decision.State = AIState.Flee;
                decision.Direction = flee.Normalized;
                decision.Boost = cfg.boostWhenFleeing && ctx.BoostEnergy01 >= cfg.boostEnergyThreshold && flee.Length > 0.5f;
                FinishDirection(ref decision, ctx, cfg, rng);
                memory.LastState = decision.State;
                memory.CommittedTargetId = -1;
                return decision;
            }

            // 2) Prey -------------------------------------------------------------------------
            float bestPreyScore = 0f;
            int preyIndex = -1;
            if (cfg.aggression > 0f)
            {
                for (int i = 0; i < ctx.Rivals.Count; i++)
                {
                    var r = ctx.Rivals[i];
                    if (!r.IsPrey || r.StrengthComparison >= 0) continue;
                    float d = Vec2.Distance(ctx.Position, r.HeadPosition);
                    if (d >= cfg.chaseRadius) continue;
                    float score = cfg.aggression * (float)Math.Pow(Math.Max(1.0, r.TotalValue), cfg.valueExponent) / (1f + d * cfg.distanceWeight);
                    if (score > bestPreyScore) { bestPreyScore = score; preyIndex = i; }
                }
            }

            // 3) Collectibles -----------------------------------------------------------------
            float bestScore = 0f, secondScore = 0f;
            int bestIndex = -1, secondIndex = -1;
            float sight2 = cfg.sightRadius * cfg.sightRadius;
            for (int i = 0; i < ctx.Collectibles.Count; i++)
            {
                var c = ctx.Collectibles[i];
                if (!c.Eligible) continue;
                float d2 = Vec2.DistanceSquared(ctx.Position, c.Position);
                if (d2 > sight2) continue;
                float d = (float)Math.Sqrt(d2);
                float value = (float)Math.Pow(NumberValue.ValueOf(c.Level), cfg.valueExponent);
                float score = cfg.valueWeight * value / (1f + d * cfg.distanceWeight);
                // Prefer things roughly ahead (turning costs time).
                Vec2 to = d > 1e-4f ? (c.Position - ctx.Position) / d : ctx.Forward;
                score *= 0.75f + 0.25f * (Vec2.Dot(to, ctx.Forward) + 1f) * 0.5f * 2f;
                // Commitment bonus avoids dithering between equally good targets.
                if (c.Id == memory.CommittedTargetId) score *= 1.25f;
                // Risk: penalise cubes near danger.
                if (cfg.riskWeight > 0)
                {
                    for (int r = 0; r < ctx.Rivals.Count; r++)
                    {
                        var rv = ctx.Rivals[r];
                        if (!rv.IsDangerous || rv.StrengthComparison <= 0) continue;
                        float dr = Vec2.Distance(rv.HeadPosition, c.Position);
                        if (dr < cfg.fleeRadius) score *= MathUtil.Clamp01(1f - cfg.riskWeight * (1f - dr / cfg.fleeRadius));
                    }
                }
                if (score > bestScore)
                {
                    secondScore = bestScore; secondIndex = bestIndex;
                    bestScore = score; bestIndex = i;
                }
                else if (score > secondScore)
                {
                    secondScore = score; secondIndex = i;
                }
            }

            bool mistake = rng.NextFloat() < cfg.mistakeChance;

            if (preyIndex >= 0 && bestPreyScore > bestScore * 0.6f && !mistake)
            {
                var prey = ctx.Rivals[preyIndex];
                Vec2 predicted = prey.HeadPosition + prey.HeadVelocity * cfg.chaseLeadTime;
                decision.State = AIState.Chase;
                decision.TargetPosition = predicted;
                decision.TargetId = prey.ChainId;
                decision.Direction = (predicted - ctx.Position).Normalized;
                decision.Boost = cfg.boostWhenChasing && ctx.BoostEnergy01 >= cfg.boostEnergyThreshold &&
                                 Vec2.Distance(predicted, ctx.Position) < cfg.chaseRadius * 0.6f;
            }
            else if (bestIndex >= 0)
            {
                int pick = mistake && secondIndex >= 0 ? secondIndex : bestIndex;
                var c = ctx.Collectibles[pick];
                decision.State = AIState.Collect;
                decision.TargetPosition = c.Position;
                decision.TargetId = c.Id;
                decision.Direction = (c.Position - ctx.Position).Normalized;
            }
            else
            {
                memory.WanderAngle = MathUtil.WrapAngle(memory.WanderAngle + rng.Range(-0.8f, 0.8f));
                decision.State = AIState.Wander;
                decision.Direction = Vec2.FromAngle(memory.WanderAngle);
                // Wander gently back towards the middle when far out.
                float edge = ctx.Position.Length / Math.Max(1f, ctx.ArenaRadius);
                if (edge > 0.6f) decision.Direction = (decision.Direction + (-ctx.Position.Normalized) * (edge - 0.4f) * 2f).Normalized;
            }

            if (decision.Direction.LengthSquared < 1e-6f) decision.Direction = ctx.Forward;
            FinishDirection(ref decision, ctx, cfg, rng);
            memory.LastState = decision.State;
            memory.CommittedTargetId = decision.State == AIState.Collect ? decision.TargetId : -1;
            return decision;
        }

        static void FinishDirection(ref AIDecision decision, AIContext ctx, AIBehaviourConfig cfg, Rng rng)
        {
            Vec2 dir = decision.Direction;

            // Boundary avoidance: strongly steer inward near the edge.
            float dist = ctx.Position.Length;
            float edgeStart = ctx.ArenaRadius * 0.88f;
            if (dist > edgeStart)
            {
                float k = MathUtil.Clamp01((dist - edgeStart) / Math.Max(1f, ctx.ArenaRadius - edgeStart));
                dir = (dir * (1f - k) + (-ctx.Position.Normalized) * (k * 2f)).Normalized;
                if (k > 0.5f && decision.State != AIState.Flee) decision.State = AIState.AvoidBoundary;
            }

            // Obstacle avoidance: push away from obstacles that lie ahead.
            for (int i = 0; i < ctx.Obstacles.Count; i++)
            {
                var o = ctx.Obstacles[i];
                Vec2 to = o.Position - ctx.Position;
                float d = to.Length;
                float range = o.Radius + 4f;
                if (d > range || d < 1e-4f) continue;
                Vec2 toN = to / d;
                if (Vec2.Dot(toN, dir) <= 0f) continue;
                // Steer along the tangent that is closest to the current direction.
                Vec2 tangent = toN.Perpendicular;
                if (Vec2.Dot(tangent, dir) < 0) tangent = -tangent;
                float k = 1f - (d - o.Radius) / 4f;
                dir = (dir + tangent * MathUtil.Clamp01(k) * 1.5f - toN * MathUtil.Clamp01(k)).Normalized;
            }

            // Imperfection: random steering error.
            if (cfg.decisionNoise > 0)
            {
                float a = dir.Angle + rng.Range(-cfg.decisionNoise, cfg.decisionNoise);
                dir = Vec2.FromAngle(a);
            }
            decision.Direction = dir.LengthSquared > 1e-6f ? dir.Normalized : ctx.Forward;
        }
    }
}
