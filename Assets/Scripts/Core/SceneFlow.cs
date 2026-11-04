using CubeSnake.UI;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace CubeSnake.Core
{
    /// <summary>Scene navigation. Falls back to swapping root objects in place when a scene is not in Build Settings.</summary>
    public static class SceneFlow
    {
        public const string MainMenuScene = "MainMenu";
        public const string GameScene = "GameArena";

        public static void LoadGame()
        {
            Time.timeScale = 1f;
            if (Application.CanStreamedLevelBeLoaded(GameScene)) SceneManager.LoadScene(GameScene);
            else GameBootstrap.ReplaceRoot(GameBootstrap.RootKind.Game);
        }

        public static void LoadMenu()
        {
            Time.timeScale = 1f;
            if (Application.CanStreamedLevelBeLoaded(MainMenuScene)) SceneManager.LoadScene(MainMenuScene);
            else GameBootstrap.ReplaceRoot(GameBootstrap.RootKind.Menu);
        }
    }

    /// <summary>
    /// Builds the game at runtime. The scenes only need to exist (they may even be empty): on load, the bootstrapper
    /// creates the correct root (main menu or arena) based on the scene name. This keeps scenes merge-friendly and
    /// guarantees that a fresh checkout runs without hand-wired references.
    /// </summary>
    public static class GameBootstrap
    {
        public enum RootKind { Menu, Game }

        static GameObject s_root;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void Init()
        {
            SceneManager.sceneLoaded -= OnSceneLoaded;
            SceneManager.sceneLoaded += OnSceneLoaded;
            Application.runInBackground = false;
            Screen.sleepTimeout = SleepTimeout.NeverSleep;
            SettingsManager.ApplyEngineSettings();
#if ENABLE_LEGACY_INPUT_MANAGER
            Input.multiTouchEnabled = true;
#endif
        }

        static void OnSceneLoaded(Scene scene, LoadSceneMode mode)
        {
            if (mode == LoadSceneMode.Additive) return;
            // A scene may already contain a hand-placed root (e.g. for designers); respect it.
            if (Object.FindObjectOfType<GameManagerMarker>() != null) return;
            CreateRoot(scene.name == SceneFlow.GameScene ? RootKind.Game : RootKind.Menu);
        }

        public static void ReplaceRoot(RootKind kind)
        {
            if (s_root != null) Object.Destroy(s_root);
            CreateRoot(kind);
        }

        static void CreateRoot(RootKind kind)
        {
            Services.EnsureCreated();
            s_root = new GameObject(kind == RootKind.Game ? "[GameArena]" : "[MainMenu]");
            s_root.AddComponent<GameManagerMarker>();
            if (kind == RootKind.Game) s_root.AddComponent<GameManager>();
            else s_root.AddComponent<MainMenuController>();
        }
    }

    /// <summary>Marks the root object that owns the current scene's game flow.</summary>
    public sealed class GameManagerMarker : MonoBehaviour { }
}
