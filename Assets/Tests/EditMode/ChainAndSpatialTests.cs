using System;
using System.Collections.Generic;
using CubeSnake.Logic;
using NUnit.Framework;

namespace CubeSnake.Tests
{
    public class ChainAndSpatialTests
    {
        [Test]
        public void PathTrail_StraightLineSamplesExactDistances()
        {
            var trail = new PathTrail(0.1f, 20f);
            trail.Reset(Vec2.Zero, Vec2.UnitY, 10f);
            for (int i = 1; i <= 100; i++) trail.MoveHead(new Vec2(0, i * 0.05f));
            Vec2 head = trail.Head;
            Assert.AreEqual(5f, head.Y, 1e-4f);
            for (float d = 0; d < 10f; d += 0.37f)
            {
                Vec2 p = trail.Sample(d);
                Assert.AreEqual(0f, p.X, 1e-3f);
                Assert.AreEqual(head.Y - d, p.Y, 0.02f, "distance " + d);
            }
        }

        [Test]
        public void PathTrail_FollowsCircleWithoutCuttingCorners()
        {
            var trail = new PathTrail(0.1f, 30f);
            float radius = 3f;
            trail.Reset(new Vec2(radius, 0), Vec2.UnitY, 1f);
            // Travel two full circles (also exercises reversal of direction relative to the start).
            for (int i = 0; i <= 2000; i++)
            {
                float a = i / 2000f * MathUtil.TwoPi * 2f;
                trail.MoveHead(new Vec2((float)Math.Cos(a) * radius, (float)Math.Sin(a) * radius));
            }
            for (float d = 0; d < 15f; d += 0.5f)
            {
                float r = trail.Sample(d).Length;
                Assert.AreEqual(radius, r, 0.05f, "Sample at " + d + " should stay on the circle");
            }
        }

        [Test]
        public void PathTrail_SampleBeyondStoredLengthClamps()
        {
            var trail = new PathTrail(0.5f, 2f);
            trail.Reset(Vec2.Zero, Vec2.UnitX, 2f);
            Vec2 far = trail.Sample(1000f);
            Assert.IsFalse(float.IsNaN(far.X));
            Assert.LessOrEqual(far.Length, 3f);
        }

        [Test]
        public void PathTrail_GrowsCapacityAndKeepsHistory()
        {
            var trail = new PathTrail(0.1f, 1f);
            trail.Reset(Vec2.Zero, Vec2.UnitX, 1f);
            for (int i = 1; i <= 30; i++) trail.MoveHead(new Vec2(i * 0.1f, 0));
            trail.EnsureLength(50f);
            Assert.GreaterOrEqual(trail.Capacity, 500);
            Assert.AreEqual(2.0f, trail.Sample(1.0f).X, 0.02f);
        }

        [Test]
        public void PathTrail_TeleportReseeds()
        {
            var trail = new PathTrail(0.1f, 2f);
            trail.Reset(Vec2.Zero, Vec2.UnitX, 2f);
            trail.MoveHead(new Vec2(1000, 0));
            Assert.AreEqual(1000f, trail.Head.X);
            Assert.AreEqual(999f, trail.Sample(1f).X, 0.05f);
        }

        [Test]
        public void ChainModel_RemoveFromSeversTail()
        {
            var chain = new ChainModel(new IdGenerator());
            chain.SetLevels(new[] { 5, 4, 2, 1 });
            var removed = chain.RemoveFrom(2);
            Assert.AreEqual(2, removed.Count);
            Assert.AreEqual("[32, 16]", chain.ToString());
            Assert.AreEqual(48.0, chain.TotalValue);
        }

        [Test]
        public void ChainModel_IdsAreUnique()
        {
            var ids = new IdGenerator();
            var a = new ChainModel(ids);
            var b = new ChainModel(ids);
            a.SetLevels(new[] { 1, 2, 3 });
            b.SetLevels(new[] { 1, 2, 3 });
            var set = new HashSet<int>();
            foreach (var c in a.Cells) Assert.IsTrue(set.Add(c.Id));
            foreach (var c in b.Cells) Assert.IsTrue(set.Add(c.Id));
        }

        [Test]
        public void SpatialHash_QueryInsertMoveRemove()
        {
            var hash = new SpatialHash<string>(4f);
            hash.Insert("a", new Vec2(0, 0));
            hash.Insert("b", new Vec2(10, 0));
            hash.Insert("c", new Vec2(-3, 3.9f));
            var results = new List<string>();
            hash.Query(Vec2.Zero, 5f, results);
            CollectionAssert.AreEquivalent(new[] { "a", "c" }, results);

            hash.Move("b", new Vec2(1, 1));
            results.Clear();
            hash.Query(Vec2.Zero, 2f, results);
            CollectionAssert.AreEquivalent(new[] { "a", "b" }, results);

            Assert.IsTrue(hash.Remove("a"));
            Assert.IsFalse(hash.Remove("a"));
            results.Clear();
            hash.Query(Vec2.Zero, 2f, results);
            CollectionAssert.AreEquivalent(new[] { "b" }, results);
            Assert.IsTrue(hash.AnyWithin(new Vec2(-3, 4), 0.5f));

            hash.Clear();
            Assert.AreEqual(0, hash.Count);
            results.Clear();
            hash.Query(Vec2.Zero, 100f, results);
            Assert.AreEqual(0, results.Count);
        }

        [Test]
        public void SpatialHash_MatchesBruteForce()
        {
            var rng = new Rng(7);
            var hash = new SpatialHash<object>(3f);
            var items = new List<(object o, Vec2 p)>();
            for (int i = 0; i < 500; i++)
            {
                var o = new object();
                var p = rng.InsideDisc(50f);
                items.Add((o, p));
                hash.Insert(o, p);
            }
            var results = new List<object>();
            for (int q = 0; q < 50; q++)
            {
                var c = rng.InsideDisc(50f);
                float r = rng.Range(0.5f, 12f);
                results.Clear();
                hash.Query(c, r, results);
                int brute = 0;
                foreach (var it in items) if (Vec2.Distance(it.p, c) <= r) brute++;
                Assert.AreEqual(brute, results.Count);
            }
        }

        [Test]
        public void Rng_IsDeterministicAndInRange()
        {
            var a = new Rng(99);
            var b = new Rng(99);
            for (int i = 0; i < 1000; i++)
            {
                float fa = a.NextFloat();
                Assert.AreEqual(fa, b.NextFloat());
                Assert.GreaterOrEqual(fa, 0f);
                Assert.Less(fa, 1f);
                int r = a.Range(3, 7); b.Range(3, 7);
                Assert.GreaterOrEqual(r, 3);
                Assert.Less(r, 7);
            }
            for (int i = 0; i < 1000; i++) Assert.LessOrEqual(a.InsideDisc(5f).Length, 5.0001f);
        }

        [Test]
        public void MathUtil_AngleHelpers()
        {
            Assert.AreEqual(0f, MathUtil.WrapAngle(MathUtil.TwoPi), 1e-5f);
            Assert.AreEqual(0.1f, MathUtil.RotateTowards(0f, 1f, 0.1f), 1e-5f);
            // Shortest way around: from 170deg to -170deg goes +20deg, not -340deg.
            float from = 170f * MathUtil.Pi / 180f, to = -170f * MathUtil.Pi / 180f;
            float r = MathUtil.RotateTowards(from, to, 5f * MathUtil.Pi / 180f);
            Assert.AreEqual(175f, r * 180f / MathUtil.Pi, 1e-3f);
        }
    }
}
