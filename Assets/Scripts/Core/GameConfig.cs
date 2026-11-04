using System;
using CubeSnake.Logic;
using UnityEngine;

namespace CubeSnake.Core
{
    [Serializable]
    public class SpawnConfig
    {
        [Tooltip("Collectibles kept alive in the arena (scaled by mode density).")]
        public int targetCollectibles = 260;
        [Tooltip("Maximum collectibles including drops from defeated chains.")]
        public int maxCollectibles = 520;
        [Tooltip("Minimum distance between spawned collectibles where practical.")]
        public float minSpacing = 2.2f;
        [Tooltip("Collectibles never spawn closer than this to any chain head.")]
        public float minDistanceFromHeads = 6f;
        [Tooltip("Placement attempts per spawn before giving up for this frame.")]
        public int maxPlacementAttempts = 12;
        [Tooltip("Maximum new spawns per frame (spreads cost over frames).")]
        public int maxSpawnsPerFrame = 6;
        [Tooltip("Relative frequencies per level (level 0 = value 1). Normalised automatically.")]
        public WeightedLevel[] distribution = WeightedTable.DefaultDistribution();
        [Tooltip("Fraction of spawns that use the distribution shifted up by the player's progress.")]
        [Range(0, 1)] public float progressionMix = 0.4f;
        [Tooltip("Head level at which the shifted distribution starts moving up.")]
        public int progressionStartLevel = 3;
        [Tooltip("Levels shifted per head level above the start level.")]
        public float progressionShiftPerLevel = 0.5f;
        [Tooltip("Seconds a dropped cube stays before it may be recycled when over budget.")]
        public float dropLifetime = 40f;
    }

    [Serializable]
    public class ArenaConfig
    {
        public float radius = 120f;
        public int obstacleCount = 14;
        public float obstacleMinRadius = 2f;
        public float obstacleMaxRadius = 4.5f;
        public float boundaryWarningDistance = 14f;
        public int decorationCount = 28;
        public Color groundColor = new Color(0.86f, 0.90f, 0.97f);
        public Color gridColor = new Color(0.70f, 0.77f, 0.92f);
        public Color outsideColor = new Color(0.60f, 0.66f, 0.84f);
        public Color skyColor = new Color(0.56f, 0.73f, 0.98f);
        public Color boundaryColor = new Color(1f, 0.36f, 0.42f);
    }

    [Serializable]
    public class CameraConfig
    {
        public float height = 17f;
        public float distance = 12f;
        public float fieldOfView = 55f;
        public float positionSmoothTime = 0.18f;
        public float yawSmoothTime = 0.6f;
        public float lookAhead = 2.5f;
        [Tooltip("Extra zoom-out per cube in the chain.")]
        public float zoomPerCube = 0.012f;
        public float maxChainZoom = 1.5f;
        public float minHeight = 3f;
        public float shakeDecay = 4f;
    }

    [Serializable]
    public class EffectsConfig
    {
        [Tooltip("Cubes at or above this level glow (64 = level 6).")]
        public int glowLevel = 6;
        public int particlesPerCollect = 8;
        public int particlesPerMerge = 18;
        public int particlesPerDefeat = 40;
    }

    /// <summary>
    /// Central, designer-editable game configuration. A default asset is created at Assets/Resources/GameConfig.asset by
    /// the project setup tool; if it is missing at runtime, built-in defaults are used so the game always runs.
    /// </summary>
    [CreateAssetMenu(menuName = "Cube Snake/Game Config", fileName = "GameConfig")]
    public class GameConfig : ScriptableObject
    {
        public RulesConfig rules = new RulesConfig();
        public MovementConfig movement = new MovementConfig();
        public BoostConfig boost = new BoostConfig();
        public SpawnConfig spawn = new SpawnConfig();
        public ArenaConfig arena = new ArenaConfig();
        public CameraConfig cameraSettings = new CameraConfig();
        public EffectsConfig effects = new EffectsConfig();
        public AIBehaviourConfig aiEasy = AIBehaviourConfig.ForDifficulty(AIDifficulty.Easy);
        public AIBehaviourConfig aiNormal = AIBehaviourConfig.ForDifficulty(AIDifficulty.Normal);
        public AIBehaviourConfig aiHard = AIBehaviourConfig.ForDifficulty(AIDifficulty.Hard);

        static GameConfig s_instance;

        public static GameConfig Instance
        {
            get
            {
                if (s_instance != null) return s_instance;
                s_instance = Resources.Load<GameConfig>("GameConfig");
                if (s_instance == null)
                {
                    s_instance = CreateInstance<GameConfig>();
                    s_instance.name = "GameConfig (defaults)";
                }
                s_instance.Validate();
                return s_instance;
            }
        }

        public AIBehaviourConfig AIFor(AIDifficulty d)
        {
            switch (d)
            {
                case AIDifficulty.Easy: return aiEasy ?? AIBehaviourConfig.ForDifficulty(d);
                case AIDifficulty.Hard: return aiHard ?? AIBehaviourConfig.ForDifficulty(d);
                default: return aiNormal ?? AIBehaviourConfig.ForDifficulty(d);
            }
        }

        public void Validate()
        {
            if (rules == null) rules = new RulesConfig();
            if (movement == null) movement = new MovementConfig();
            if (boost == null) boost = new BoostConfig();
            if (spawn == null) spawn = new SpawnConfig();
            if (arena == null) arena = new ArenaConfig();
            if (cameraSettings == null) cameraSettings = new CameraConfig();
            if (effects == null) effects = new EffectsConfig();
            if (spawn.distribution == null || spawn.distribution.Length == 0) spawn.distribution = WeightedTable.DefaultDistribution();
            rules.Sanitize();
            arena.radius = Mathf.Max(30f, arena.radius);
            spawn.targetCollectibles = Mathf.Max(10, spawn.targetCollectibles);
            spawn.maxCollectibles = Mathf.Max(spawn.targetCollectibles, spawn.maxCollectibles);
            movement.baseSpeed = Mathf.Max(0.5f, movement.baseSpeed);
            movement.chainGapFactor = Mathf.Max(0.5f, movement.chainGapFactor);
        }

        void OnValidate() => Validate();
    }
}
