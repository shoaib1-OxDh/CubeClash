using System.Collections.Generic;
using CubeSnake.Logic;
using NUnit.Framework;

namespace CubeSnake.Tests
{
    /// <summary>
    /// Headless soak test of the gameplay rules: several AI chains move, collect, merge and fight for minutes of
    /// simulated time using the same logic classes as the game (MergeSystem, AITargetSelector, CombatResolver,
    /// SpatialHash, PathTrail, BoostMeter). Verifies invariants that must hold in long sessions.
    /// </summary>
    public class ArenaSimulationTests
    {
        sealed class SimChain
        {
            public int Id;
            public ChainModel Model;
            public PathTrail Trail;
            public BoostMeter Boost;
            public float Heading;
            public bool Alive = true;
            public float Respawn;
            public AIBrainMemory Memory = new AIBrainMemory();
            public AIBehaviourConfig Cfg;
            public float DecisionTimer;
            public AIDecision Decision;
            public long Score;
            public float MergeTimer;
            public float Protection;
            public Vec2 Pos => Trail.Head;
        }

        sealed class SimCube
        {
            public int Id;
            public int Level;
            public Vec2 Pos;
        }

        [TestCase(AIDifficulty.Easy, 11UL)]
        [TestCase(AIDifficulty.Normal, 22UL)]
        [TestCase(AIDifficulty.Hard, 33UL)]
        public void LongSessionKeepsInvariants(AIDifficulty difficulty, ulong seed)
        {
            var rules = new RulesConfig();
            var merge = new MergeSystem(rules);
            var move = new MovementConfig();
            var rng = new Rng(seed);
            var ids = new IdGenerator();
            const float radius = 80f;
            const float dt = 1f / 30f;
            var cubes = new List<SimCube>();
            var hash = new SpatialHash<SimCube>(6f);
            var table = new WeightedTable(WeightedTable.DefaultDistribution());
            int cubeId = 1;

            void SpawnCube(int level, Vec2 p)
            {
                var c = new SimCube { Id = cubeId++, Level = level, Pos = p };
                cubes.Add(c);
                hash.Insert(c, p);
            }

            for (int i = 0; i < 180; i++) SpawnCube(table.Sample(rng), rng.InsideDisc(radius - 2));

            var chains = new List<SimChain>();
            for (int i = 0; i < 8; i++)
            {
                var c = new SimChain
                {
                    Id = i + 1, Model = new ChainModel(ids), Trail = new PathTrail(0.12f, 30f), Boost = new BoostMeter(new BoostConfig()),
                    Heading = rng.NextFloat() * MathUtil.TwoPi, Cfg = AIBehaviourConfig.ForDifficulty(difficulty),
                };
                c.Trail.Reset(rng.InsideDisc(radius - 10), Vec2.FromAngle(c.Heading), 20f);
                chains.Add(c);
            }

            var ctx = new AIContext { ArenaRadius = radius };
            var events = new List<ChainEvent>();
            var query = new List<SimCube>();
            var contacts = new List<Contact>();
            var resolved = new List<ResolvedContact>();
            int totalMerges = 0, totalCollects = 0, totalDefeats = 0, totalSevers = 0;

            ChainSnapshot Snap(SimChain c) => new ChainSnapshot
            {
                ChainId = c.Id, HeadLevel = c.Model.HeadLevel, TotalValue = c.Model.TotalValue, Length = c.Model.Length,
                Alive = c.Alive, Protected = c.Protection > 0, CanBeDefeated = true, CanDefeatOthers = true,
            };

            Vec2 SegmentPos(SimChain c, int index)
            {
                float d = 0;
                for (int i = 1; i <= index; i++) d += (move.CubeSize(c.Model.Cells[i - 1].Level) + move.CubeSize(c.Model.Cells[i].Level)) * 0.5f * move.chainGapFactor;
                return c.Trail.Sample(d);
            }

            int steps = (int)(240f / dt); // four simulated minutes
            for (int step = 0; step < steps; step++)
            {
                foreach (var c in chains)
                {
                    if (!c.Alive)
                    {
                        c.Respawn -= dt;
                        if (c.Respawn <= 0)
                        {
                            c.Alive = true;
                            c.Model.SetLevels(new[] { rng.Range(0, 4) });
                            c.Trail.Reset(rng.InsideDisc(radius - 10), Vec2.FromAngle(c.Heading), 20f);
                            c.Protection = 2f;
                        }
                        continue;
                    }
                    c.Protection -= dt;
                    // Think.
                    c.DecisionTimer -= dt;
                    if (c.DecisionTimer <= 0)
                    {
                        c.DecisionTimer = c.Cfg.reactionTime;
                        ctx.ClearLists();
                        ctx.Position = c.Pos; ctx.Forward = Vec2.FromAngle(c.Heading); ctx.HeadLevel = c.Model.HeadLevel; ctx.BoostEnergy01 = c.Boost.Energy;
                        query.Clear();
                        hash.Query(c.Pos, c.Cfg.sightRadius, query);
                        foreach (var q in query) ctx.Collectibles.Add(new AICollectibleInfo { Id = q.Id, Position = q.Pos, Level = q.Level, Eligible = merge.CanCollect(c.Model, q.Level) });
                        foreach (var o in chains)
                        {
                            if (o == c || !o.Alive) continue;
                            int cmp = CombatResolver.CompareStrength(Snap(o), Snap(c), rules.strengthMetric);
                            ctx.Rivals.Add(new AIRivalInfo { ChainId = o.Id, HeadPosition = o.Pos, StrengthComparison = cmp, TotalValue = o.Model.TotalValue, IsDangerous = cmp > 0, IsPrey = cmp < 0 });
                        }
                        c.Decision = AITargetSelector.Decide(ctx, c.Cfg, c.Memory, rng);
                    }
                    // Move.
                    c.Boost.Update(dt, c.Decision.Boost);
                    c.Heading = MathUtil.RotateTowards(c.Heading, c.Decision.Direction.Angle, move.maxTurnRateDegrees * MathUtil.Pi / 180f * dt);
                    float speed = move.baseSpeed * c.Cfg.speedMultiplier * (c.Boost.IsBoosting ? 1.75f : 1f);
                    Vec2 p = c.Pos + Vec2.FromAngle(c.Heading) * speed * dt;
                    if (p.Length > radius) p = p.Normalized * radius;
                    c.Trail.MoveHead(p);
                    c.Trail.EnsureLength(c.Model.Length * 3f + 5f);
                    // Merge timer.
                    if (merge.HasPendingMerge(c.Model))
                    {
                        c.MergeTimer += dt;
                        if (c.MergeTimer >= rules.hybridMergeInterval)
                        {
                            c.MergeTimer = 0;
                            events.Clear();
                            totalMerges += merge.StepMerges(c.Model, events);
                            c.Score += ScoreCalculator.SumEvents(events);
                        }
                    }
                    // Collect.
                    query.Clear();
                    hash.Query(c.Pos, move.CubeSize(c.Model.HeadLevel) * 0.5f + 0.6f, query);
                    foreach (var q in query)
                    {
                        events.Clear();
                        if (merge.TryCollect(c.Model, q.Level, events) != CollectResult.Accepted) continue;
                        totalCollects++;
                        c.Score += ScoreCalculator.SumEvents(events);
                        hash.Remove(q);
                        cubes.Remove(q);
                    }
                }

                // Combat.
                contacts.Clear();
                foreach (var a in chains)
                {
                    if (!a.Alive) continue;
                    float ra = move.CubeSize(a.Model.HeadLevel) * 0.5f;
                    foreach (var d in chains)
                    {
                        if (d == a || !d.Alive) continue;
                        for (int s = 0; s < d.Model.Length; s++)
                        {
                            float rd = move.CubeSize(d.Model.Cells[s].Level) * 0.5f;
                            float pen = ra + rd - Vec2.Distance(a.Pos, SegmentPos(d, s));
                            if (pen > 0) { contacts.Add(new Contact { AttackerId = a.Id, DefenderId = d.Id, DefenderSegmentIndex = s, Penetration = pen }); break; }
                        }
                    }
                }
                CombatResolver.ResolveFrame(contacts, id => Snap(chains[id - 1]), rules, resolved);
                var defeatedThisFrame = new HashSet<int>();
                foreach (var r in resolved)
                {
                    var a = chains[r.Contact.AttackerId - 1];
                    var d = chains[r.Contact.DefenderId - 1];
                    SimChain victim = r.Outcome == CombatOutcome.DefenderDefeated ? d : r.Outcome == CombatOutcome.AttackerDefeated ? a : null;
                    if (victim != null)
                    {
                        Assert.IsTrue(defeatedThisFrame.Add(victim.Id), "A chain was defeated twice in one frame");
                        Assert.IsTrue(victim.Alive);
                        foreach (var cell in victim.Model.Cells) SpawnCube(cell.Level, victim.Pos + rng.InsideDisc(1.5f));
                        victim.Alive = false;
                        victim.Respawn = 2f;
                        totalDefeats++;
                    }
                    else if (r.Outcome == CombatOutcome.DefenderSevered)
                    {
                        int before = d.Model.Length;
                        var removed = d.Model.RemoveFrom(r.Contact.DefenderSegmentIndex);
                        Assert.AreEqual(before, d.Model.Length + removed.Count);
                        Assert.GreaterOrEqual(d.Model.Length, 1, "Severing never removes the head");
                        foreach (var cell in removed) SpawnCube(cell.Level, d.Pos + rng.InsideDisc(2f));
                        totalSevers++;
                    }
                }

                // Replenish.
                while (cubes.Count < 180) SpawnCube(table.Sample(rng), rng.InsideDisc(radius - 2));

                // Invariants.
                foreach (var c in chains)
                {
                    Assert.IsTrue(c.Model.IsSorted(), "chain sorted");
                    Assert.GreaterOrEqual(c.Model.Length, 1);
                    Assert.LessOrEqual(c.Model.Length, rules.maxChainLength);
                    Assert.IsFalse(float.IsNaN(c.Pos.X) || float.IsNaN(c.Pos.Y), "finite position");
                    Assert.LessOrEqual(c.Pos.Length, radius + 0.01f, "inside arena");
                    Assert.GreaterOrEqual(c.Score, 0);
                }
            }

            Assert.Greater(totalCollects, 100, "AIs should collect plenty of cubes");
            Assert.Greater(totalMerges, 30, "AIs should merge");
            int maxLevel = 0;
            foreach (var c in chains) maxLevel = System.Math.Max(maxLevel, c.Model.HeadLevel);
            Assert.GreaterOrEqual(maxLevel, 3, "Someone should have grown to at least 8");
            TestContext.WriteLine($"{difficulty}: collects={totalCollects} merges={totalMerges} defeats={totalDefeats} severs={totalSevers} maxHead={NumberFormat.FormatLevel(maxLevel)}");
        }
    }
}
