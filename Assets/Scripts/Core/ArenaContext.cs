using System.Collections.Generic;
using CubeSnake.Chain;
using CubeSnake.Collectibles;
using CubeSnake.Logic;
using CubeSnake.Visual;
using UnityEngine;

namespace CubeSnake.Core
{
    public struct Obstacle
    {
        public Vec2 Position;
        public float Radius;
    }

    /// <summary>Shared services and configuration for one arena session (passed to chains, AI, spawner, collisions).</summary>
    public sealed class ArenaContext
    {
        public GameConfig Config;
        public GameModeSettings Mode;
        public RulesConfig Rules;
        public MergeSystem Merge;
        public IdGenerator Ids = new IdGenerator();
        public Rng Rng;
        public float ArenaRadius;
        public readonly List<Obstacle> Obstacles = new List<Obstacle>();
        public readonly List<CubeChainController> Chains = new List<CubeChainController>();
        public ObjectPool<ChainSegment> SegmentPool;
        public Transform SegmentRoot;
        public CollectibleSpawner Spawner;
        public EffectsManager Effects;
        public double ElapsedSeconds;

        public CubeChainController Player;

        public CubeChainController FindChain(int id)
        {
            for (int i = 0; i < Chains.Count; i++) if (Chains[i].ChainId == id) return Chains[i];
            return null;
        }

        /// <summary>True when the point (with clearance) is inside the arena and outside every obstacle.</summary>
        public bool IsFreeSpot(Vec2 p, float clearance)
        {
            if (p.Length > ArenaRadius - clearance) return false;
            for (int i = 0; i < Obstacles.Count; i++)
                if (Vec2.Distance(p, Obstacles[i].Position) < Obstacles[i].Radius + clearance) return false;
            return true;
        }

        public static Vector3 ToWorld(Vec2 p, float y = 0f) => new Vector3(p.X, y, p.Y);
        public static Vec2 ToPlane(Vector3 v) => new Vec2(v.x, v.z);
    }
}
