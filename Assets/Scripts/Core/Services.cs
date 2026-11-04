using CubeSnake.Audio;
using CubeSnake.Save;
using UnityEngine;
using UnityEngine.EventSystems;

namespace CubeSnake.Core
{
    /// <summary>Persistent services that survive scene loads: audio, save data and the UI event system.</summary>
    public static class Services
    {
        static GameObject s_go;

        public static AudioManager Audio { get; private set; }

        public static void EnsureCreated()
        {
            if (s_go != null) return;
            s_go = new GameObject("[Services]");
            Object.DontDestroyOnLoad(s_go);
            SaveManager.Load();
            Audio = s_go.AddComponent<AudioManager>();
            CreateEventSystem(s_go.transform);
            s_go.AddComponent<AudioListener>(); // all game audio is 2D, one persistent listener
            s_go.AddComponent<AppLifecycle>();
        }

        static void CreateEventSystem(Transform parent)
        {
            var existing = Object.FindObjectOfType<EventSystem>();
            if (existing != null)
            {
                Object.DontDestroyOnLoad(existing.gameObject);
                return;
            }
            var es = new GameObject("EventSystem");
            es.transform.SetParent(parent, false);
            es.AddComponent<EventSystem>();
#if CUBESNAKE_INPUT_SYSTEM && ENABLE_INPUT_SYSTEM
            var module = es.AddComponent<UnityEngine.InputSystem.UI.InputSystemUIInputModule>();
            module.AssignDefaultActions();
#else
            es.AddComponent<StandaloneInputModule>();
#endif
        }
    }

    /// <summary>Handles application backgrounding: pauses the game and flushes saves.</summary>
    public sealed class AppLifecycle : MonoBehaviour
    {
        void OnApplicationPause(bool paused)
        {
            if (!paused) return;
            GameManager.Current?.PauseFromSystem();
            SaveManager.Save();
        }

        void OnApplicationFocus(bool focused)
        {
            if (!focused && !Application.isEditor) GameManager.Current?.PauseFromSystem();
        }

        void OnApplicationQuit() => SaveManager.Save();
    }
}
