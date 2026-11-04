using System.Collections.Generic;
using CubeSnake.Logic;
using NUnit.Framework;

namespace CubeSnake.Tests
{
    public class CombatTests
    {
        static ChainSnapshot Snap(int id, int headLevel, double total = 0, bool prot = false) => new ChainSnapshot
        {
            ChainId = id, HeadLevel = headLevel, TotalValue = total > 0 ? total : System.Math.Pow(2, headLevel), Length = 1,
            Alive = true, Protected = prot, CanBeDefeated = true, CanDefeatOthers = true,
        };

        [Test]
        public void StrongerHeadOnHead_DefeatsDefender()
        {
            var r = new RulesConfig();
            Assert.AreEqual(CombatOutcome.DefenderDefeated, CombatResolver.Resolve(Snap(1, 4), Snap(2, 3), 0, r));
        }

        [Test]
        public void StrongerHeadOnBody_SeversOrDefeatsPerConfig()
        {
            var r = new RulesConfig { severOnBodyHit = true };
            Assert.AreEqual(CombatOutcome.DefenderSevered, CombatResolver.Resolve(Snap(1, 4), Snap(2, 3), 2, r));
            r.severOnBodyHit = false;
            Assert.AreEqual(CombatOutcome.DefenderDefeated, CombatResolver.Resolve(Snap(1, 4), Snap(2, 3), 2, r));
        }

        [Test]
        public void WeakerAttacker_IsDefeated()
        {
            Assert.AreEqual(CombatOutcome.AttackerDefeated, CombatResolver.Resolve(Snap(1, 2), Snap(2, 5), 3, new RulesConfig()));
        }

        [Test]
        public void TieRules()
        {
            var r = new RulesConfig();
            Assert.AreEqual(CombatOutcome.Bounce, CombatResolver.Resolve(Snap(1, 3), Snap(2, 3), 0, r));
            r.tieRule = TieRule.BothDefeated;
            Assert.AreEqual(CombatOutcome.BothDefeated, CombatResolver.Resolve(Snap(1, 3), Snap(2, 3), 0, r));
            r.tieRule = TieRule.Ignore;
            Assert.AreEqual(CombatOutcome.None, CombatResolver.Resolve(Snap(1, 3), Snap(2, 3), 0, r));
        }

        [Test]
        public void TotalValueMetric()
        {
            var r = new RulesConfig { strengthMetric = StrengthMetric.TotalValue };
            // Head 8 but total 8 vs head 4 with total 30 -> the second chain is stronger.
            Assert.AreEqual(CombatOutcome.AttackerDefeated, CombatResolver.Resolve(Snap(1, 3, 8), Snap(2, 2, 30), 0, r));
        }

        [Test]
        public void ProtectionAndPermissions_PreventDefeat()
        {
            var r = new RulesConfig();
            Assert.AreEqual(CombatOutcome.Bounce, CombatResolver.Resolve(Snap(1, 5), Snap(2, 1, prot: true), 0, r));
            var player = Snap(1, 1); player.CanBeDefeated = false; // Endless Growth
            Assert.AreEqual(CombatOutcome.Bounce, CombatResolver.Resolve(player, Snap(2, 6), 0, r));
            var passive = Snap(2, 6); passive.CanDefeatOthers = false;
            Assert.AreEqual(CombatOutcome.Bounce, CombatResolver.Resolve(Snap(1, 1), passive, 0, r));
            var dead = Snap(2, 1); dead.Alive = false;
            Assert.AreEqual(CombatOutcome.None, CombatResolver.Resolve(Snap(1, 5), dead, 0, r));
            Assert.AreEqual(CombatOutcome.None, CombatResolver.Resolve(Snap(1, 5), Snap(1, 5), 0, r), "No self combat");
        }

        [Test]
        public void ResolveFrame_EachChainResolvedAtMostOnce()
        {
            var snaps = new Dictionary<int, ChainSnapshot> { { 1, Snap(1, 6) }, { 2, Snap(2, 2) }, { 3, Snap(3, 3) } };
            var contacts = new List<Contact>
            {
                // Chain 1 touches chain 2 with several cubes in the same frame (duplicate callbacks).
                new Contact { AttackerId = 1, DefenderId = 2, DefenderSegmentIndex = 0, Penetration = 0.3f },
                new Contact { AttackerId = 1, DefenderId = 2, DefenderSegmentIndex = 1, Penetration = 0.2f },
                // ...and chain 2's head is also touching chain 1's body.
                new Contact { AttackerId = 2, DefenderId = 1, DefenderSegmentIndex = 3, Penetration = 0.25f },
                // ...and chain 1 hits chain 3 too.
                new Contact { AttackerId = 1, DefenderId = 3, DefenderSegmentIndex = 0, Penetration = 0.1f },
            };
            var results = new List<ResolvedContact>();
            CombatResolver.ResolveFrame(contacts, id => snaps[id], new RulesConfig(), results);
            Assert.AreEqual(1, results.Count, "Only one combat per chain per frame");
            Assert.AreEqual(CombatOutcome.DefenderDefeated, results[0].Outcome);
            Assert.AreEqual(2, results[0].Contact.DefenderId);
        }

        [Test]
        public void ResolveFrame_IndependentPairsBothResolve()
        {
            var snaps = new Dictionary<int, ChainSnapshot> { { 1, Snap(1, 6) }, { 2, Snap(2, 2) }, { 3, Snap(3, 3) }, { 4, Snap(4, 1) } };
            var contacts = new List<Contact>
            {
                new Contact { AttackerId = 1, DefenderId = 2, Penetration = 0.1f },
                new Contact { AttackerId = 3, DefenderId = 4, Penetration = 0.1f },
            };
            var results = new List<ResolvedContact>();
            CombatResolver.ResolveFrame(contacts, id => snaps[id], new RulesConfig(), results);
            Assert.AreEqual(2, results.Count);
        }

        [Test]
        public void DropCount()
        {
            Assert.AreEqual(5, CombatResolver.DropCount(5, 1f));
            Assert.AreEqual(3, CombatResolver.DropCount(5, 0.5f));
            Assert.AreEqual(0, CombatResolver.DropCount(5, 0f));
            Assert.AreEqual(0, CombatResolver.DropCount(0, 1f));
            Assert.AreEqual(5, CombatResolver.DropCount(5, 7f));
        }
    }

    public class AITests
    {
        static AIContext Ctx()
        {
            var ctx = new AIContext { Position = Vec2.Zero, Forward = Vec2.UnitY, HeadLevel = 2, BoostEnergy01 = 1f, ArenaRadius = 100f };
            return ctx;
        }

        static AIBehaviourConfig Perfect()
        {
            var c = AIBehaviourConfig.ForDifficulty(AIDifficulty.Hard);
            c.decisionNoise = 0; c.mistakeChance = 0; c.threatAwareness = 1f;
            return c;
        }

        [Test]
        public void PrefersValuableNearbyEligibleCubes()
        {
            var ctx = Ctx();
            ctx.Collectibles.Add(new AICollectibleInfo { Id = 1, Position = new Vec2(10, 0), Level = 0, Eligible = true });
            ctx.Collectibles.Add(new AICollectibleInfo { Id = 2, Position = new Vec2(0, 6), Level = 2, Eligible = true });
            ctx.Collectibles.Add(new AICollectibleInfo { Id = 3, Position = new Vec2(0, 2), Level = 6, Eligible = false });
            var d = AITargetSelector.Decide(ctx, Perfect(), new AIBrainMemory(), new Rng(1));
            Assert.AreEqual(AIState.Collect, d.State);
            Assert.AreEqual(2, d.TargetId);
        }

        [Test]
        public void FleesFromStrongerRival()
        {
            var ctx = Ctx();
            ctx.Collectibles.Add(new AICollectibleInfo { Id = 1, Position = new Vec2(0, 5), Level = 2, Eligible = true });
            ctx.Rivals.Add(new AIRivalInfo { ChainId = 9, HeadPosition = new Vec2(0, 3), StrengthComparison = 1, IsDangerous = true });
            var d = AITargetSelector.Decide(ctx, Perfect(), new AIBrainMemory(), new Rng(1));
            Assert.AreEqual(AIState.Flee, d.State);
            Assert.Less(d.Direction.Y, -0.9f, "Runs directly away");
            Assert.IsTrue(d.Boost);
        }

        [Test]
        public void ChasesWeakerRivalWhenAggressive()
        {
            var ctx = Ctx();
            ctx.Rivals.Add(new AIRivalInfo { ChainId = 9, HeadPosition = new Vec2(5, 0), StrengthComparison = -1, IsPrey = true, TotalValue = 16 });
            var cfg = Perfect(); cfg.aggression = 1f;
            var d = AITargetSelector.Decide(ctx, cfg, new AIBrainMemory(), new Rng(1));
            Assert.AreEqual(AIState.Chase, d.State);
            Assert.AreEqual(9, d.TargetId);
        }

        [Test]
        public void PassiveRivalNeverChases()
        {
            var ctx = Ctx();
            ctx.Rivals.Add(new AIRivalInfo { ChainId = 9, HeadPosition = new Vec2(5, 0), StrengthComparison = -1, IsPrey = true, TotalValue = 16 });
            var cfg = Perfect().Passive();
            var d = AITargetSelector.Decide(ctx, cfg, new AIBrainMemory(), new Rng(1));
            Assert.AreNotEqual(AIState.Chase, d.State);
        }

        [Test]
        public void WandersInwardNearBoundary()
        {
            var ctx = Ctx();
            ctx.Position = new Vec2(98, 0);
            var d = AITargetSelector.Decide(ctx, Perfect(), new AIBrainMemory(), new Rng(3));
            Assert.Less(d.Direction.X, 0f);
        }

        [Test]
        public void DifficultyPresetsAreOrdered()
        {
            var e = AIBehaviourConfig.ForDifficulty(AIDifficulty.Easy);
            var n = AIBehaviourConfig.ForDifficulty(AIDifficulty.Normal);
            var h = AIBehaviourConfig.ForDifficulty(AIDifficulty.Hard);
            Assert.Less(e.speedMultiplier, n.speedMultiplier);
            Assert.Less(n.speedMultiplier, h.speedMultiplier);
            Assert.Greater(e.reactionTime, n.reactionTime);
            Assert.Greater(n.reactionTime, h.reactionTime);
            Assert.Less(e.aggression, n.aggression);
            Assert.Less(n.aggression, h.aggression);
            var ramped = n.Ramped(1f);
            Assert.Greater(ramped.aggression, n.aggression);
        }

        [Test]
        public void DecisionsAlwaysProduceFiniteUnitDirections()
        {
            var rng = new Rng(5);
            var mem = new AIBrainMemory();
            var cfg = AIBehaviourConfig.ForDifficulty(AIDifficulty.Normal);
            for (int i = 0; i < 500; i++)
            {
                var ctx = Ctx();
                ctx.Position = rng.InsideDisc(110f);
                for (int k = 0; k < 5; k++) ctx.Collectibles.Add(new AICollectibleInfo { Id = k, Position = ctx.Position + rng.InsideDisc(20), Level = rng.Range(0, 5), Eligible = rng.Chance(0.6f) });
                ctx.Rivals.Add(new AIRivalInfo { ChainId = 1, HeadPosition = ctx.Position + rng.InsideDisc(15), StrengthComparison = rng.Range(-1, 2), IsDangerous = true, IsPrey = true, TotalValue = 8 });
                ctx.Obstacles.Add(new AIObstacleInfo { Position = ctx.Position + rng.InsideDisc(6), Radius = 2 });
                var d = AITargetSelector.Decide(ctx, cfg, mem, rng);
                Assert.IsFalse(float.IsNaN(d.Direction.X) || float.IsNaN(d.Direction.Y));
                Assert.AreEqual(1f, d.Direction.Length, 1e-3f);
            }
        }
    }

    public class SaveAndProgressTests
    {
        [Test]
        public void Sanitize_RepairsCorruptData()
        {
            var p = new PlayerProgress
            {
                version = -4, bestScore = -10, highestLevel = 99999, coins = -1, totalPlayTimeSeconds = double.NaN,
                unlockedSkins = null, selectedSkin = "does-not-exist", unlockedTrails = new List<string> { "spark", "spark", "bogus", null },
                modeBests = null,
            };
            SaveValidator.Sanitize(p);
            Assert.AreEqual(PlayerProgress.CurrentVersion, p.version);
            Assert.AreEqual(0, p.bestScore);
            Assert.AreEqual(NumberValue.MaxLevel, p.highestLevel);
            Assert.AreEqual(0, p.coins);
            Assert.AreEqual(0, p.totalPlayTimeSeconds);
            CollectionAssert.AreEqual(new[] { UnlockCatalog.DefaultSkin }, p.unlockedSkins);
            CollectionAssert.AreEqual(new[] { "spark" }, p.unlockedTrails);
            Assert.AreEqual(UnlockCatalog.DefaultSkin, p.selectedSkin);
            Assert.IsNotNull(p.modeBests);
            Assert.IsNotNull(SaveValidator.Sanitize(null));
        }

        [Test]
        public void ApplySession_TracksBestsAndUnlocks()
        {
            var p = SaveValidator.Sanitize(new PlayerProgress());
            var s = new SessionStats();
            s.Reset(0);
            s.AddScore(2000);
            s.ObserveChain(8, 6);
            s.RecordCollect(); s.RecordMerge(); s.RecordDefeat();
            s.AddTime(65);
            var result = ProgressRules.ApplySession(p, s, GameMode.Classic);
            Assert.IsTrue(result.NewBestScore);
            Assert.IsTrue(result.NewHighestLevel);
            Assert.AreEqual(2000, p.bestScore);
            Assert.AreEqual(8, p.highestLevel);
            Assert.AreEqual(6, p.bestChainLength);
            Assert.AreEqual(1, p.gamesPlayed);
            Assert.Contains("pastel", result.NewUnlocks);
            Assert.Contains("neon", result.NewUnlocks);
            Assert.Greater(p.coins, 0);

            var s2 = new SessionStats();
            s2.Reset(0);
            s2.AddScore(10);
            var r2 = ProgressRules.ApplySession(p, s2, GameMode.Classic);
            Assert.IsFalse(r2.NewBestScore);
            Assert.AreEqual(2000, r2.PreviousBestScore);
            Assert.AreEqual(2000, p.bestScore, "Best score is never lowered");
            Assert.AreEqual(2, p.gamesPlayed);
        }

        [Test]
        public void ModeSettings_SurvivalRamps()
        {
            var s = GameModeSettings.For(GameMode.SurvivalChallenge);
            Assert.AreEqual(0f, s.RampAt(0));
            Assert.AreEqual(1f, s.RampAt(60 * 60));
            Assert.Greater(s.RivalMaxLevelAt(300), s.RivalMaxLevelAt(0));
            var e = GameModeSettings.For(GameMode.EndlessGrowth);
            Assert.IsFalse(e.playerCanBeDefeated);
            Assert.IsFalse(e.rivalsAggressive);
        }
    }
}
