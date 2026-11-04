using System;
using CubeSnake.Chain;
using UnityEngine;

namespace CubeSnake.Core
{
    public enum GameState { Loading, Playing, Paused, GameOver }

    /// <summary>
    /// Lightweight event hub used for collection, merging, scoring, state changes and UI updates.
    /// Listeners subscribe in OnEnable and unsubscribe in OnDisable; everything is cleared when a scene unloads.
    /// </summary>
    public static class GameEvents
    {
        public static event Action<CubeChainController, int, Vector3> CubeCollected;
        public static event Action<CubeChainController, int, Vector3> CubesMerged;
        public static event Action<CubeChainController, long, Vector3> ScoreGained;
        public static event Action<CubeChainController, CubeChainController> ChainDefeated;
        public static event Action<CubeChainController> ChainSevered;
        public static event Action<CubeChainController> ChainChanged;
        public static event Action<CubeChainController> BoostStarted;
        public static event Action<GameState> StateChanged;

        public static void RaiseCubeCollected(CubeChainController c, int level, Vector3 p) => CubeCollected?.Invoke(c, level, p);
        public static void RaiseCubesMerged(CubeChainController c, int level, Vector3 p) => CubesMerged?.Invoke(c, level, p);
        public static void RaiseScoreGained(CubeChainController c, long points, Vector3 p) => ScoreGained?.Invoke(c, points, p);
        public static void RaiseChainDefeated(CubeChainController victim, CubeChainController killer) => ChainDefeated?.Invoke(victim, killer);
        public static void RaiseChainSevered(CubeChainController victim) => ChainSevered?.Invoke(victim);
        public static void RaiseChainChanged(CubeChainController c) => ChainChanged?.Invoke(c);
        public static void RaiseBoostStarted(CubeChainController c) => BoostStarted?.Invoke(c);
        public static void RaiseStateChanged(GameState s) => StateChanged?.Invoke(s);

        public static void ClearAll()
        {
            CubeCollected = null;
            CubesMerged = null;
            ScoreGained = null;
            ChainDefeated = null;
            ChainSevered = null;
            ChainChanged = null;
            BoostStarted = null;
            StateChanged = null;
        }
    }
}
