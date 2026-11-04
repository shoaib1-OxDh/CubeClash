using System.Collections.Generic;
using CubeSnake.Logic;
using NUnit.Framework;

namespace CubeSnake.Tests
{
    public class MergeSystemTests
    {
        static RulesConfig Rules(MergeMode mode, float interval = 0.2f) => new RulesConfig { mergeMode = mode, hybridMergeInterval = interval };

        static ChainModel NewChain(int startLevel = 0) => new ChainModel(new IdGenerator(), startLevel);

        [Test]
        public void Chain_StartsWithSingleCubeOfValueOne()
        {
            var chain = NewChain();
            Assert.AreEqual(1, chain.Length);
            Assert.AreEqual(0, chain.HeadLevel);
            Assert.AreEqual(1.0, chain.Head.Value);
            Assert.AreEqual("[1]", chain.ToString());
        }

        [Test]
        public void Hybrid_SpecExampleSequence_1_1_to_2_to_4()
        {
            var merge = new MergeSystem(Rules(MergeMode.Hybrid));
            var chain = NewChain();
            var ev = new List<ChainEvent>();

            Assert.AreEqual(CollectResult.Accepted, merge.TryCollect(chain, 0, ev));
            Assert.AreEqual("[1, 1]", chain.ToString(), "Collect grows the chain first");
            Assert.AreEqual(1, merge.StepMerges(chain, ev));
            Assert.AreEqual("[2]", chain.ToString());

            merge.TryCollect(chain, 1, ev);
            Assert.AreEqual("[2, 2]", chain.ToString());
            merge.StepMerges(chain, ev);
            Assert.AreEqual("[4]", chain.ToString());
        }

        [Test]
        public void Hybrid_MergesOnePairPerStep_AndCascadesInOrder()
        {
            var merge = new MergeSystem(Rules(MergeMode.Hybrid));
            var chain = NewChain();
            chain.SetLevels(new[] { 3, 2, 1, 0 }); // [8,4,2,1]
            var ev = new List<ChainEvent>();
            merge.TryCollect(chain, 0, ev);        // [8,4,2,1,1]
            Assert.AreEqual("[8, 4, 2, 1, 1]", chain.ToString());
            Assert.AreEqual(1, merge.StepMerges(chain, ev)); Assert.AreEqual("[8, 4, 2, 2]", chain.ToString());
            Assert.AreEqual(1, merge.StepMerges(chain, ev)); Assert.AreEqual("[8, 4, 4]", chain.ToString());
            Assert.AreEqual(1, merge.StepMerges(chain, ev)); Assert.AreEqual("[8, 8]", chain.ToString());
            Assert.AreEqual(1, merge.StepMerges(chain, ev)); Assert.AreEqual("[16]", chain.ToString());
            Assert.AreEqual(0, merge.StepMerges(chain, ev), "Nothing left to merge");
        }

        [Test]
        public void Standard_ResolvesEverythingImmediately()
        {
            var merge = new MergeSystem(Rules(MergeMode.Standard));
            var chain = NewChain();
            chain.SetLevels(new[] { 3, 2, 1, 0 });
            var ev = new List<ChainEvent>();
            merge.TryCollect(chain, 0, ev);
            Assert.AreEqual("[16]", chain.ToString());
            int merges = ev.FindAll(e => e.Type == ChainEventType.Merged).Count;
            Assert.AreEqual(4, merges);
        }

        [Test]
        public void HybridWithZeroInterval_BehavesLikeInstantResolve()
        {
            var merge = new MergeSystem(Rules(MergeMode.Hybrid, 0f));
            var chain = NewChain();
            merge.TryCollect(chain, 0, null);
            Assert.AreEqual("[2]", chain.ToString());
        }

        [Test]
        public void ChainCollection_UpgradesMatchingCube_NoCascade()
        {
            var merge = new MergeSystem(Rules(MergeMode.ChainCollection));
            var chain = NewChain();
            chain.SetLevels(new[] { 2, 1 }); // [4,2]
            var ev = new List<ChainEvent>();
            merge.TryCollect(chain, 1, ev);   // 2 matches -> becomes 4: [4,4] (no cascade)
            Assert.AreEqual("[4, 4]", chain.ToString());
            Assert.AreEqual(0, merge.StepMerges(chain, ev), "Chain mode never auto-merges");
            merge.TryCollect(chain, 0, ev);   // no match -> appended
            Assert.AreEqual("[4, 4, 1]", chain.ToString());
            Assert.IsTrue(chain.IsSorted());
        }

        [Test]
        public void Eligibility_LessOrEqualHead_RejectsBiggerCubes()
        {
            var merge = new MergeSystem(new RulesConfig());
            var chain = NewChain(1); // [2]
            Assert.IsTrue(merge.CanCollect(chain, 0));
            Assert.IsTrue(merge.CanCollect(chain, 1));
            Assert.IsFalse(merge.CanCollect(chain, 2));
            var ev = new List<ChainEvent>();
            Assert.AreEqual(CollectResult.Rejected, merge.TryCollect(chain, 5, ev));
            Assert.AreEqual(0, ev.Count);
            Assert.AreEqual("[2]", chain.ToString(), "Rejected collection must not change the chain");
        }

        [Test]
        public void Eligibility_AnyValue_BiggerCubeBecomesHead()
        {
            var merge = new MergeSystem(new RulesConfig { eligibility = CollectEligibility.AnyValue });
            var chain = NewChain(1);
            merge.TryCollect(chain, 3, null);
            Assert.AreEqual("[8, 2]", chain.ToString());
        }

        [Test]
        public void Eligibility_MatchingChainValue()
        {
            var merge = new MergeSystem(new RulesConfig { eligibility = CollectEligibility.MatchingChainValue });
            var chain = NewChain();
            chain.SetLevels(new[] { 3, 1 });
            Assert.IsTrue(merge.CanCollect(chain, 1));
            Assert.IsFalse(merge.CanCollect(chain, 0));
            Assert.IsFalse(merge.CanCollect(chain, 2));
        }

        [Test]
        public void SmallerCube_ScoreOnly_DoesNotChangeChain()
        {
            var merge = new MergeSystem(new RulesConfig { smallerCubeBehaviour = SmallerCubeBehaviour.ScoreOnly });
            var chain = NewChain(1); // [2]
            var ev = new List<ChainEvent>();
            merge.TryCollect(chain, 0, ev);
            Assert.AreEqual("[2]", chain.ToString());
            Assert.AreEqual(ChainEventType.ScoreOnly, ev[0].Type);
            Assert.AreEqual(1, ev[0].Score);
            // Equal cubes still use the normal path.
            merge.TryCollect(chain, 1, ev);
            Assert.AreEqual("[2, 2]", chain.ToString());
        }

        [Test]
        public void SmallerCube_FeedLeader_DoublesLeaderWhenFed()
        {
            var merge = new MergeSystem(new RulesConfig { smallerCubeBehaviour = SmallerCubeBehaviour.FeedLeader });
            var chain = NewChain(2); // [4]
            var ev = new List<ChainEvent>();
            merge.TryCollect(chain, 1, ev); // +2 => progress 2/4
            Assert.AreEqual("[4]", chain.ToString());
            Assert.AreEqual(ChainEventType.LeaderFed, ev[0].Type);
            merge.TryCollect(chain, 0, ev); // +1 => 3/4
            merge.TryCollect(chain, 0, ev); // +1 => 4/4 => leader becomes 8
            Assert.AreEqual("[8]", chain.ToString());
            Assert.AreEqual(0.0, chain.LeaderProgress, 1e-9);
            Assert.AreEqual(1, chain.Length, "FeedLeader never changes chain length");
        }

        [Test]
        public void MaxChainLength_ShedsSmallestTailCube()
        {
            var merge = new MergeSystem(new RulesConfig { maxChainLength = 3, eligibility = CollectEligibility.AnyValue });
            var chain = NewChain();
            chain.SetLevels(new[] { 5, 3, 1 });
            var ev = new List<ChainEvent>();
            merge.TryCollect(chain, 0, ev);
            Assert.AreEqual(3, chain.Length);
            Assert.AreEqual("[32, 8, 2]", chain.ToString());
            Assert.IsTrue(ev.Exists(e => e.Type == ChainEventType.Shed && e.Level == 0));
        }

        [Test]
        public void MergesNeverDuplicateOrDestroyValue()
        {
            var rules = new RulesConfig { maxChainLength = 1000 };
            var merge = new MergeSystem(rules);
            var chain = NewChain();
            var rng = new Rng(1234);
            double collected = 1; // starting cube
            for (int i = 0; i < 5000; i++)
            {
                int level = rng.Range(0, chain.HeadLevel + 1);
                if (merge.TryCollect(chain, level, null) == CollectResult.Accepted) collected += NumberValue.ValueOf(level);
                if (rng.Chance(0.7f)) merge.StepMerges(chain, null);
                Assert.IsTrue(chain.IsSorted(), "Chain must stay sorted");
                Assert.AreEqual(collected, chain.TotalValue, collected * 1e-12, "Total value must be conserved by merges");
            }
        }

        [Test]
        public void StepMerges_EachCubeMergesAtMostOncePerStep()
        {
            var merge = new MergeSystem(new RulesConfig { hybridMergesPerStep = 10 });
            var chain = NewChain();
            chain.SetLevels(new[] { 1, 1, 1, 1 }); // [2,2,2,2]
            int merges = merge.StepMerges(chain, null);
            Assert.AreEqual(2, merges, "Two disjoint pairs merge in one step");
            Assert.AreEqual("[4, 4]", chain.ToString(), "Survivors do not merge again in the same step");
            merge.StepMerges(chain, null);
            Assert.AreEqual("[8]", chain.ToString());
        }

        [Test]
        public void MergeEvents_ReportSurvivorAndRemovedIds()
        {
            var merge = new MergeSystem(new RulesConfig());
            var chain = NewChain();
            int headId = chain.Head.Id;
            var ev = new List<ChainEvent>();
            merge.TryCollect(chain, 0, ev);
            int addedId = ev[0].CellId;
            ev.Clear();
            merge.StepMerges(chain, ev);
            Assert.AreEqual(1, ev.Count);
            Assert.AreEqual(headId, ev[0].CellId);
            Assert.AreEqual(addedId, ev[0].OtherCellId);
            Assert.AreEqual(headId, chain.Head.Id, "The front cube survives");
        }

        [Test]
        public void VeryHighValues_DoNotOverflow()
        {
            var merge = new MergeSystem(new RulesConfig { mergeMode = MergeMode.Standard });
            var chain = NewChain(70);
            merge.TryCollect(chain, 70, null);
            Assert.AreEqual(71, chain.HeadLevel);
            Assert.AreEqual(System.Math.Pow(2, 71), chain.Head.Value);
            var cell = new NumberValue(71);
            Assert.AreEqual(long.MaxValue, cell.ExactValueOrSaturated);
            Assert.AreEqual("2^71", NumberFormat.FormatLevel(71));
            Assert.AreEqual(long.MaxValue, ScoreCalculator.ForMerge(200, new RulesConfig()));
        }

        [Test]
        public void RapidCollection_ManyCubesSameFrame()
        {
            var merge = new MergeSystem(new RulesConfig());
            var chain = NewChain(3);
            var ev = new List<ChainEvent>();
            for (int i = 0; i < 8; i++) merge.TryCollect(chain, 0, ev);
            Assert.AreEqual(9, chain.Length);
            merge.ResolveAllMerges(chain, ev);
            Assert.AreEqual("[16]", chain.ToString()); // 8 + 8*1 = 16
        }
    }
}
