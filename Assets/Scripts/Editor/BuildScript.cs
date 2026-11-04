using System;
using System.IO;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEngine;

namespace CubeSnake.EditorTools
{
    /// <summary>
    /// Android build entry points (menu and command line).
    ///
    /// Command line examples (see README):
    ///   Unity -batchmode -quit -projectPath . -buildTarget Android -executeMethod CubeSnake.EditorTools.BuildScript.BuildDebugApk
    ///   Unity -batchmode -quit -projectPath . -buildTarget Android -executeMethod CubeSnake.EditorTools.BuildScript.BuildReleaseApk
    ///   Unity -batchmode -quit -projectPath . -buildTarget Android -executeMethod CubeSnake.EditorTools.BuildScript.BuildReleaseAab
    ///
    /// Release signing reads these environment variables (never commit keystores or passwords):
    ///   CUBECLASH_KEYSTORE_PATH, CUBECLASH_KEYSTORE_PASS, CUBECLASH_KEY_ALIAS, CUBECLASH_KEY_PASS
    /// Optional: CUBECLASH_VERSION_NAME (e.g. 1.0.3), CUBECLASH_VERSION_CODE (integer), CUBECLASH_BUILD_DIR.
    /// </summary>
    public static class BuildScript
    {
        enum Kind { DebugApk, ReleaseApk, ReleaseAab }

        [MenuItem("Cube Clash/Build/Android Debug APK", priority = 20)]
        public static void BuildDebugApk() => Build(Kind.DebugApk);

        [MenuItem("Cube Clash/Build/Android Release APK", priority = 21)]
        public static void BuildReleaseApk() => Build(Kind.ReleaseApk);

        [MenuItem("Cube Clash/Build/Android Release AAB (Google Play)", priority = 22)]
        public static void BuildReleaseAab() => Build(Kind.ReleaseAab);

        /// <summary>Builds the debug APK, release APK and release AAB in one go.</summary>
        [MenuItem("Cube Clash/Build/All Android Builds", priority = 40)]
        public static void BuildAll()
        {
            Build(Kind.DebugApk);
            Build(Kind.ReleaseApk);
            Build(Kind.ReleaseAab);
        }

        static void Build(Kind kind)
        {
            ProjectSetup.Run(false);
            if (EditorUserBuildSettings.activeBuildTarget != BuildTarget.Android)
                EditorUserBuildSettings.SwitchActiveBuildTarget(BuildTargetGroup.Android, BuildTarget.Android);

            ApplyVersion();
            bool release = kind != Kind.DebugApk;
            bool signed = release && ConfigureSigning();
            if (!release) PlayerSettings.Android.useCustomKeystore = false;

            EditorUserBuildSettings.buildAppBundle = kind == Kind.ReleaseAab;
            EditorUserBuildSettings.development = !release;
            EditorUserBuildSettings.androidBuildSystem = AndroidBuildSystem.Gradle;
            EditorUserBuildSettings.exportAsGoogleAndroidProject = false;

            string dir = Environment.GetEnvironmentVariable("CUBECLASH_BUILD_DIR");
            if (string.IsNullOrEmpty(dir)) dir = Path.Combine("Builds", "Android");
            Directory.CreateDirectory(dir);
            string version = PlayerSettings.bundleVersion;
            string file = kind switch
            {
                Kind.DebugApk => $"CubeClash-{version}-debug.apk",
                Kind.ReleaseApk => $"CubeClash-{version}-release.apk",
                _ => $"CubeClash-{version}-release.aab",
            };
            string output = Path.Combine(dir, file);

            var options = new BuildPlayerOptions
            {
                scenes = new[] { ProjectSetup.MenuScenePath, ProjectSetup.GameScenePath },
                locationPathName = output,
                target = BuildTarget.Android,
                targetGroup = BuildTargetGroup.Android,
                options = release ? BuildOptions.None : (BuildOptions.Development | BuildOptions.AllowDebugging),
            };

            Debug.Log($"[CubeSnake] Building {kind} -> {output} (signed with {(signed ? "release keystore" : "debug keystore")})");
            BuildReport report = BuildPipeline.BuildPlayer(options);
            var summary = report.summary;
            if (summary.result == BuildResult.Succeeded)
            {
                Debug.Log($"[CubeSnake] {kind} succeeded: {output} ({summary.totalSize / (1024f * 1024f):0.0} MB, {summary.totalTime})");
                if (release && !signed)
                    Debug.LogWarning("[CubeSnake] Release build was signed with the DEBUG keystore. Set CUBECLASH_KEYSTORE_* env vars to sign for Google Play.");
            }
            else
            {
                Debug.LogError($"[CubeSnake] {kind} FAILED: {summary.result}, {summary.totalErrors} error(s).");
                if (Application.isBatchMode) EditorApplication.Exit(1);
            }
        }

        static void ApplyVersion()
        {
            string name = Environment.GetEnvironmentVariable("CUBECLASH_VERSION_NAME");
            if (!string.IsNullOrEmpty(name)) PlayerSettings.bundleVersion = name;
            string code = Environment.GetEnvironmentVariable("CUBECLASH_VERSION_CODE");
            if (int.TryParse(code, out int c) && c > 0) PlayerSettings.Android.bundleVersionCode = c;
            else if (PlayerSettings.Android.bundleVersionCode < 1) PlayerSettings.Android.bundleVersionCode = 1;
        }

        static bool ConfigureSigning()
        {
            string path = Environment.GetEnvironmentVariable("CUBECLASH_KEYSTORE_PATH");
            string pass = Environment.GetEnvironmentVariable("CUBECLASH_KEYSTORE_PASS");
            string alias = Environment.GetEnvironmentVariable("CUBECLASH_KEY_ALIAS");
            string aliasPass = Environment.GetEnvironmentVariable("CUBECLASH_KEY_PASS");
            if (string.IsNullOrEmpty(path) || !File.Exists(path) || string.IsNullOrEmpty(pass) || string.IsNullOrEmpty(alias))
            {
                PlayerSettings.Android.useCustomKeystore = false;
                return false;
            }
            PlayerSettings.Android.useCustomKeystore = true;
            PlayerSettings.Android.keystoreName = Path.GetFullPath(path);
            PlayerSettings.Android.keystorePass = pass;
            PlayerSettings.Android.keyaliasName = alias;
            PlayerSettings.Android.keyaliasPass = string.IsNullOrEmpty(aliasPass) ? pass : aliasPass;
            return true;
        }
    }
}
