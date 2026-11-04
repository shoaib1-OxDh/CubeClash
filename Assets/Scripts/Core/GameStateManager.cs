using UnityEngine;

namespace CubeSnake.Core
{
    /// <summary>Owns the session state machine (Loading → Playing ⇄ Paused → GameOver) and the time scale.</summary>
    public sealed class GameStateManager
    {
        public GameState State { get; private set; } = GameState.Loading;

        public bool IsPlaying => State == GameState.Playing;

        public void Set(GameState state)
        {
            if (state == State) return;
            State = state;
            switch (state)
            {
                case GameState.Playing: Time.timeScale = 1f; break;
                case GameState.Paused: Time.timeScale = 0f; break;
                case GameState.GameOver: Time.timeScale = 1f; break;
            }
            GameEvents.RaiseStateChanged(state);
        }
    }
}
