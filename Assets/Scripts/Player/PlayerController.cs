using CubeSnake.Chain;
using CubeSnake.Core;
using CubeSnake.Logic;
using UnityEngine;

namespace CubeSnake.Player
{
    /// <summary>Brain that steers the player's chain from <see cref="PlayerInputController"/>.</summary>
    public sealed class PlayerController : IChainBrain
    {
        readonly CubeChainController _chain;
        readonly PlayerInputController _input;

        public PlayerController(CubeChainController chain, PlayerInputController input)
        {
            _chain = chain;
            _input = input;
        }

        public void Think(float dt)
        {
            if (!_chain.Alive) return;
            _chain.TurnSensitivity = SettingsManager.TurnSensitivity;
            Vector3 head = ArenaContext.ToWorld(_chain.HeadPosition);
            if (_input.TryGetWorldDirection(head, out Vector3 dir)) _chain.DesiredDirection = new Vec2(dir.x, dir.z);
            else _chain.DesiredDirection = null;
            _chain.WantsBoost = _input.BoostHeld;
        }
    }

    /// <summary>Read-only view of the player's session statistics for the HUD and game-over screen.</summary>
    public sealed class PlayerStats
    {
        readonly CubeChainController _chain;
        public PlayerStats(CubeChainController chain) { _chain = chain; }

        public SessionStats Session => _chain.Stats;
        public long Score => _chain.Stats.Score;
        public int LeadingLevel => _chain.Model.HeadLevel;
        public int ChainLength => _chain.Model.Length;
        public int HighestLevel => _chain.Stats.HighestLevel;
        public int MaxChainLength => _chain.Stats.MaxChainLength;
        public int OpponentsDefeated => _chain.Stats.OpponentsDefeated;
        public double Duration => _chain.Stats.DurationSeconds;
        public float BoostDisplay => _chain.Boost.Display01;
        public bool BoostReady => _chain.Boost.Ready;
    }
}
