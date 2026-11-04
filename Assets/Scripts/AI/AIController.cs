using System.Collections.Generic;
using CubeSnake.Chain;
using CubeSnake.Collectibles;
using CubeSnake.Core;
using CubeSnake.Logic;

namespace CubeSnake.AI
{
    /// <summary>
    /// Brain for a rival chain. Every reaction tick it builds an <see cref="AIContext"/> from spatial queries and asks
    /// <see cref="AITargetSelector"/> (pure, unit-tested) for a decision. Between ticks it keeps steering at the
    /// chosen target, so reaction time is a real, tunable property.
    /// </summary>
    public sealed class AIController : IChainBrain
    {
        readonly CubeChainController _chain;
        readonly ArenaContext _ctx;
        readonly Rng _rng;
        readonly AIContext _ai = new AIContext();
        readonly AIBrainMemory _memory = new AIBrainMemory();
        readonly List<CollectibleCube> _nearby = new List<CollectibleCube>(64);
        AIDecision _decision;
        float _timer;

        public AIBehaviourConfig Config { get; set; }
        public AIState State => _decision.State;

        public AIController(CubeChainController chain, ArenaContext ctx, AIBehaviourConfig config, Rng rng)
        {
            _chain = chain;
            _ctx = ctx;
            Config = config;
            _rng = rng;
            _memory.WanderAngle = rng.NextFloat() * MathUtil.TwoPi;
            _decision.Direction = chain.Forward;
        }

        public void ResetBrain()
        {
            _timer = 0f;
            _memory.CommittedTargetId = -1;
            _decision = new AIDecision { Direction = _chain.Forward, State = AIState.Wander, TargetId = -1 };
        }

        public void Think(float dt)
        {
            if (!_chain.Alive) return;
            _chain.SpeedMultiplier = Config.speedMultiplier;
            _timer -= dt;
            if (_timer <= 0f || TargetGone())
            {
                _timer = Config.reactionTime + _rng.Range(-Config.reactionJitter, Config.reactionJitter);
                BuildContext();
                _decision = AITargetSelector.Decide(_ai, Config, _memory, _rng);
            }

            // Re-aim at moving / fixed targets between decisions.
            Vec2 dir = _decision.Direction;
            if (_decision.State == AIState.Collect || _decision.State == AIState.Chase)
            {
                Vec2 to = _decision.TargetPosition - _chain.HeadPosition;
                if (_decision.State == AIState.Chase)
                {
                    var prey = _ctx.FindChain(_decision.TargetId);
                    if (prey != null && prey.Alive) to = prey.HeadPosition + prey.Velocity * Config.chaseLeadTime - _chain.HeadPosition;
                }
                if (to.LengthSquared > 0.01f) dir = to.Normalized;
            }
            _chain.DesiredDirection = dir;
            _chain.WantsBoost = _decision.Boost;
        }

        bool TargetGone()
        {
            if (_decision.State != AIState.Collect) return false;
            // Target reached or taken by someone else.
            return Vec2.DistanceSquared(_decision.TargetPosition, _chain.HeadPosition) < 0.8f;
        }

        void BuildContext()
        {
            _ai.ClearLists();
            _ai.Position = _chain.HeadPosition;
            _ai.Forward = _chain.Forward;
            _ai.HeadLevel = _chain.HeadLevel;
            _ai.BoostEnergy01 = _chain.Boost.Energy;
            _ai.ArenaRadius = _ctx.ArenaRadius;

            _nearby.Clear();
            _ctx.Spawner.Query(_chain.HeadPosition, Config.sightRadius, _nearby);
            int max = System.Math.Min(_nearby.Count, 48);
            for (int i = 0; i < max; i++)
            {
                var c = _nearby[i];
                _ai.Collectibles.Add(new AICollectibleInfo { Id = c.Id, Position = c.PlanePosition, Level = c.Level, Eligible = _chain.CanCollect(c.Level) });
            }

            var me = _chain.Snapshot();
            float interest = System.Math.Max(Config.fleeRadius, Config.chaseRadius) * 1.4f;
            for (int i = 0; i < _ctx.Chains.Count; i++)
            {
                var other = _ctx.Chains[i];
                if (other == _chain || !other.Alive) continue;
                if (Vec2.Distance(other.HeadPosition, _chain.HeadPosition) > interest + other.Model.Length) continue;
                var snap = other.Snapshot();
                int cmp = CombatResolver.CompareStrength(snap, me, _ctx.Rules.strengthMetric);
                bool dangerous = cmp > 0 && other.CanDefeatOthers && _chain.CanBeDefeated && !other.IsProtected;
                bool prey = cmp < 0 && _chain.CanDefeatOthers && other.CanBeDefeated && !other.IsProtected;
                _ai.Rivals.Add(new AIRivalInfo
                {
                    ChainId = other.ChainId, HeadPosition = other.HeadPosition, HeadVelocity = other.Velocity,
                    StrengthComparison = cmp, TotalValue = snap.TotalValue, IsDangerous = dangerous, IsPrey = prey,
                });
                if (dangerous)
                {
                    var segs = other.Segments;
                    for (int s = 1; s < segs.Count; s++)
                    {
                        var p = ArenaContext.ToPlane(segs[s].Position);
                        if (Vec2.Distance(p, _chain.HeadPosition) < Config.fleeRadius) _ai.DangerPoints.Add(p);
                    }
                }
            }

            for (int i = 0; i < _ctx.Obstacles.Count; i++)
            {
                var o = _ctx.Obstacles[i];
                if (Vec2.Distance(o.Position, _chain.HeadPosition) < o.Radius + 8f)
                    _ai.Obstacles.Add(new AIObstacleInfo { Position = o.Position, Radius = o.Radius });
            }
        }
    }
}
