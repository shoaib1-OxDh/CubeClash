using System;
using UnityEngine;

namespace CubeSnake.Core
{
    public enum ControlScheme { Joystick = 0, TapToSteer = 1 }
    public enum CameraMode { FixedAngle = 0, RotateWithHeading = 1 }

    /// <summary>Player preferences persisted with PlayerPrefs. Every read is clamped so corrupt values are harmless.</summary>
    public static class SettingsManager
    {
        const string Prefix = "cs.settings.";

        public static event Action Changed;

        public static float MusicVolume { get => GetF("music", 0.55f, 0, 1); set => SetF("music", value, 0, 1); }
        public static float SfxVolume { get => GetF("sfx", 0.85f, 0, 1); set => SetF("sfx", value, 0, 1); }
        public static bool Muted { get => GetB("muted", false); set => SetB("muted", value); }
        public static ControlScheme Controls { get => (ControlScheme)GetI("controls", 0, 0, 1); set => SetI("controls", (int)value, 0, 1); }
        public static CameraMode Camera { get => (CameraMode)GetI("camMode", 0, 0, 1); set => SetI("camMode", (int)value, 0, 1); }
        public static float CameraZoom { get => GetF("camZoom", 1f, 0.7f, 1.5f); set => SetF("camZoom", value, 0.7f, 1.5f); }
        /// <summary>Camera follow responsiveness (higher = snappier).</summary>
        public static float CameraSensitivity { get => GetF("camSens", 1f, 0.4f, 2f); set => SetF("camSens", value, 0.4f, 2f); }
        public static float TurnSensitivity { get => GetF("turnSens", 1f, 0.5f, 1.6f); set => SetF("turnSens", value, 0.5f, 1.6f); }
        public static bool ShowMinimap { get => GetB("minimap", true); set => SetB("minimap", value); }
        public static bool LowEffects { get => GetB("lowFx", false); set => SetB("lowFx", value); }
        public static int TargetFps { get => GetI("fps", 60, 30, 60) >= 45 ? 60 : 30; set => SetI("fps", value >= 45 ? 60 : 30, 30, 60); }
        public static float JoystickScale { get => GetF("joySize", 1f, 0.7f, 1.4f); set => SetF("joySize", value, 0.7f, 1.4f); }
        public static int LastMode { get => GetI("lastMode", 0, 0, 2); set => SetI("lastMode", value, 0, 2); }
        public static int LastDifficulty { get => GetI("lastDiff", 1, 0, 2); set => SetI("lastDiff", value, 0, 2); }

        /// <summary>Applies frame-rate and quality related settings to the engine.</summary>
        public static void ApplyEngineSettings()
        {
            QualitySettings.vSyncCount = 0;
            Application.targetFrameRate = TargetFps;
            QualitySettings.shadows = ShadowQuality.Disable; // we use cheap blob shadows instead of shadow maps
            QualitySettings.antiAliasing = LowEffects ? 0 : 2;
        }

        /// <summary>Applies changed values immediately (live preview) without writing to disk.</summary>
        public static void Apply()
        {
            ApplyEngineSettings();
            Changed?.Invoke();
        }

        /// <summary>Applies and persists settings to disk.</summary>
        public static void Save()
        {
            PlayerPrefs.Save();
            Apply();
        }

        static float GetF(string key, float def, float min, float max)
        {
            float v = PlayerPrefs.GetFloat(Prefix + key, def);
            if (float.IsNaN(v) || float.IsInfinity(v)) return def;
            return Mathf.Clamp(v, min, max);
        }

        static void SetF(string key, float v, float min, float max) => PlayerPrefs.SetFloat(Prefix + key, Mathf.Clamp(v, min, max));
        static int GetI(string key, int def, int min, int max) => Mathf.Clamp(PlayerPrefs.GetInt(Prefix + key, def), min, max);
        static void SetI(string key, int v, int min, int max) => PlayerPrefs.SetInt(Prefix + key, Mathf.Clamp(v, min, max));
        static bool GetB(string key, bool def) => PlayerPrefs.GetInt(Prefix + key, def ? 1 : 0) != 0;
        static void SetB(string key, bool v) => PlayerPrefs.SetInt(Prefix + key, v ? 1 : 0);
    }
}
