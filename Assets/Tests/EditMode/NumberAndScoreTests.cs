using System.Collections.Generic;
using CubeSnake.Logic;
using NUnit.Framework;

namespace CubeSnake.Tests
{
    public class NumberAndScoreTests
    {
        [TestCase(0, "1")]
        [TestCase(1, "2")]
        [TestCase(6, "64")]
        [TestCase(11, "2048")]
        [TestCase(13, "8192")]
        [TestCase(14, "16K")]
        [TestCase(19, "524K")]
        [TestCase(20, "1M")]
        [TestCase(21, "2M")]
        [TestCase(30, "1B")]
        [TestCase(40, "1T")]
        [TestCase(49, "562T")]
        [TestCase(50, "2^50")]
        public void FormatLevel(int level, string expected) => Assert.AreEqual(expected, NumberFormat.FormatLevel(level));

        [Test]
        public void FormattedLabelsFitOnACube()
        {
            for (int level = 0; level <= NumberValue.MaxLevel; level++)
                Assert.LessOrEqual(NumberFormat.FormatLevel(level).Length, 6, "level " + level);
        }

        [Test]
        public void NumberValue_FromValue()
        {
            Assert.IsTrue(NumberValue.TryFromValue(1, out var one)); Assert.AreEqual(0, one.Level);
            Assert.IsTrue(NumberValue.TryFromValue(1024, out var k)); Assert.AreEqual(10, k.Level);
            Assert.IsFalse(NumberValue.TryFromValue(3, out _));
            Assert.IsFalse(NumberValue.TryFromValue(0, out _));
            Assert.IsFalse(NumberValue.TryFromValue(-8, out _));
            Assert.AreEqual(new NumberValue(4), new NumberValue(3).Doubled);
        }

        [Test]
        public void Score_ForActions()
        {
            var r = new RulesConfig();
            Assert.AreEqual(4, ScoreCalculator.ForCollect(2, r));
            Assert.AreEqual(8, ScoreCalculator.ForMerge(3, r));
            Assert.AreEqual(50 + 10, ScoreCalculator.ForDefeat(20, r));
            Assert.AreEqual(0, ScoreCalculator.ToScore(double.NaN));
            Assert.AreEqual(long.MaxValue, ScoreCalculator.ToScore(1e30));
        }

        [Test]
        public void Score_HybridSequenceIsActionBased()
        {
            var rules = new RulesConfig();
            var merge = new MergeSystem(rules);
            var chain = new ChainModel(new IdGenerator());
            var stats = new SessionStats();
            stats.Reset(0);
            var ev = new List<ChainEvent>();
            merge.TryCollect(chain, 0, ev);  // +1 collect
            merge.StepMerges(chain, ev);     // +2 merge (result value)
            stats.Apply(ev);
            Assert.AreEqual(3, stats.Score);
            Assert.AreEqual(1, stats.Merges);
        }

        [Test]
        public void SaturatingAdd()
        {
            Assert.AreEqual(long.MaxValue, SaturatingMath.Add(long.MaxValue, 1));
            Assert.AreEqual(5, SaturatingMath.Add(2, 3));
        }

        [Test]
        public void Rank()
        {
            var scores = new List<long> { 50, 100, 10, 100 };
            Assert.AreEqual(3, RankCalculator.RankOf(scores, 0));
            Assert.AreEqual(1, RankCalculator.RankOf(scores, 1));
            Assert.AreEqual(2, RankCalculator.RankOf(scores, 3));
            Assert.AreEqual(4, RankCalculator.RankOf(scores, 2));
        }

        [Test]
        public void WeightedTable_NormalisesAndSamplesProportionally()
        {
            var table = new WeightedTable(WeightedTable.DefaultDistribution());
            float sum = 0;
            for (int i = 0; i < table.Count; i++) sum += table.Probability(i);
            Assert.AreEqual(1f, sum, 1e-4f);
            Assert.AreEqual(0.40f, table.Probability(0), 1e-4f);

            var rng = new Rng(42);
            var counts = new int[16];
            const int n = 200000;
            for (int i = 0; i < n; i++) counts[table.Sample(rng)]++;
            Assert.AreEqual(0.40, counts[0] / (double)n, 0.01);
            Assert.AreEqual(0.25, counts[1] / (double)n, 0.01);
            Assert.AreEqual(0.16, counts[2] / (double)n, 0.01);
        }

        [Test]
        public void WeightedTable_InvalidWeightsFallBack()
        {
            var table = new WeightedTable(new[] { new WeightedLevel(3, -1), new WeightedLevel(2, float.NaN) });
            Assert.AreEqual(2, table.Sample(new Rng(1)));
            var empty = new WeightedTable(null);
            Assert.AreEqual(0, empty.Sample(new Rng(1)));
        }

        [Test]
        public void Boost_EnergyDrainsAndRegenerates()
        {
            var meter = new BoostMeter(new BoostConfig { drainPerSecond = 0.5f, regenPerSecond = 0.25f, regenDelay = 0.5f, minEnergyToStart = 0.2f });
            for (int i = 0; i < 10; i++) meter.Update(0.1f, true);
            Assert.AreEqual(0.5f, meter.Energy, 1e-4f);
            Assert.IsTrue(meter.IsBoosting);
            for (int i = 0; i < 10; i++) meter.Update(0.1f, true);
            Assert.IsFalse(meter.IsBoosting, "Cannot boost on empty");
            Assert.AreEqual(0f, meter.Energy, 1e-4f);
            meter.Update(0.1f, true);
            Assert.IsFalse(meter.IsBoosting, "Needs minEnergyToStart to restart");
            for (int i = 0; i < 3; i++) meter.Update(0.1f, false); // still inside the regen delay
            Assert.AreEqual(0f, meter.Energy, 1e-4f);
            meter.Update(0.1f, false);
            for (int i = 0; i < 10; i++) meter.Update(0.1f, false);
            Assert.AreEqual(0.25f, meter.Energy, 0.03f);
        }

        [Test]
        public void Boost_CooldownMode()
        {
            var meter = new BoostMeter(new BoostConfig { mode = BoostMode.Cooldown, cooldownBoostDuration = 1f, cooldownSeconds = 2f });
            meter.Update(0.1f, true);
            Assert.IsTrue(meter.IsBoosting);
            for (int i = 0; i < 10; i++) meter.Update(0.1f, true);
            Assert.IsFalse(meter.IsBoosting);
            meter.Update(0.1f, true);
            Assert.IsFalse(meter.IsBoosting, "On cooldown");
            for (int i = 0; i < 20; i++) meter.Update(0.1f, false);
            meter.Update(0.1f, true);
            Assert.IsTrue(meter.IsBoosting);
        }
    }
}
