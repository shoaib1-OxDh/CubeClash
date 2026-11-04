using System;
using System.IO;
using CubeSnake.Logic;
using UnityEngine;

namespace CubeSnake.Save
{
    /// <summary>
    /// Local, offline persistence of <see cref="PlayerProgress"/> as JSON in Application.persistentDataPath.
    /// Writes go to a temp file first and the previous save is kept as a backup; on load, a missing or corrupt
    /// file falls back to the backup and then to defaults. All data is sanitised before use.
    /// </summary>
    public static class SaveManager
    {
        const string FileName = "progress.json";
        static PlayerProgress s_progress;

        public static PlayerProgress Progress
        {
            get
            {
                if (s_progress == null) Load();
                return s_progress;
            }
        }

        static string PathFor(string name) => System.IO.Path.Combine(Application.persistentDataPath, name);
        static string MainPath => PathFor(FileName);
        static string BackupPath => PathFor(FileName + ".bak");
        static string TempPath => PathFor(FileName + ".tmp");

        public static void Load()
        {
            s_progress = TryRead(MainPath) ?? TryRead(BackupPath) ?? new PlayerProgress();
            SaveValidator.Sanitize(s_progress);
        }

        static PlayerProgress TryRead(string path)
        {
            try
            {
                if (!File.Exists(path)) return null;
                string json = File.ReadAllText(path);
                if (string.IsNullOrWhiteSpace(json)) return null;
                var p = JsonUtility.FromJson<PlayerProgress>(json);
                return p;
            }
            catch (Exception e)
            {
                Debug.LogWarning($"[CubeSnake] Could not read save '{path}': {e.Message}");
                return null;
            }
        }

        public static void Save()
        {
            if (s_progress == null) return;
            try
            {
                SaveValidator.Sanitize(s_progress);
                string json = JsonUtility.ToJson(s_progress, true);
                File.WriteAllText(TempPath, json);
                if (File.Exists(MainPath)) File.Copy(MainPath, BackupPath, true);
                File.Copy(TempPath, MainPath, true);
                File.Delete(TempPath);
            }
            catch (Exception e)
            {
                Debug.LogWarning($"[CubeSnake] Could not write save: {e.Message}");
            }
        }

        /// <summary>Deletes all progress (used by the settings "Reset progress" button).</summary>
        public static void ResetProgress()
        {
            s_progress = SaveValidator.Sanitize(new PlayerProgress());
            Save();
        }
    }
}
