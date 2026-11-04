using System.IO;
using System.Linq;
using CubeSnake.Core;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.Rendering;
using UnityEngine.SceneManagement;

namespace CubeSnake.EditorTools
{
    /// <summary>
    /// One-click (and automatic, on first open) project configuration:
    ///  - creates Assets/Scenes/MainMenu.unity and GameArena.unity and registers them in Build Settings;
    ///  - creates the editable Assets/Resources/GameConfig.asset;
    ///  - creates and assigns a URP pipeline asset (falls back to the built-in pipeline if that fails; every shader
    ///    in the game is pipeline-agnostic, so both render identically);
    ///  - applies Android player settings (package id, IL2CPP, ARM64+ARMv7, min API 24, no extra permissions).
    /// Menu: Cube Clash > Setup Project.
    /// </summary>
    [InitializeOnLoad]
    public static class ProjectSetup
    {
        public const string ScenesFolder = "Assets/Scenes";
        public const string MenuScenePath = ScenesFolder + "/MainMenu.unity";
        public const string GameScenePath = ScenesFolder + "/GameArena.unity";
        const string ConfigPath = "Assets/Resources/GameConfig.asset";
        const string SettingsFolder = "Assets/Settings";
        const string AutoSetupKey = "CubeSnake.AutoSetupDone.v1";

        public const string PackageId = "com.oxdh.cubeclash";
        public const string ProductName = "Cube Clash";
        public const string CompanyName = "OxDH";

        static ProjectSetup()
        {
            EditorApplication.delayCall += () =>
            {
                if (Application.isBatchMode) return; // build scripts call Run() explicitly
                if (NeedsSetup() || !EditorPrefs.GetBool(ProjectKey(AutoSetupKey), false))
                {
                    Run(false);
                    EditorPrefs.SetBool(ProjectKey(AutoSetupKey), true);
                }
            };
        }

        static string ProjectKey(string key) => key + "." + Application.dataPath.GetHashCode();

        static bool NeedsSetup() =>
            !File.Exists(MenuScenePath) || !File.Exists(GameScenePath) || !File.Exists(ConfigPath) ||
            EditorBuildSettings.scenes.Length < 2;

        [MenuItem("Cube Clash/Setup Project", priority = 0)]
        public static void RunFromMenu() => Run(true);

        public static void Run(bool verbose)
        {
            EnsureFolder("Assets/Resources");
            EnsureFolder(ScenesFolder);
            EnsureFolder(SettingsFolder);
            EnsureConfig();
            EnsureScenes();
            ConfigurePlayerSettings();
            ConfigureInputHandling();
            ConfigureRenderPipeline();
            AssetDatabase.SaveAssets();
            AssetDatabase.Refresh();
            if (verbose) Debug.Log("[CubeSnake] Project setup complete. Open Assets/Scenes/MainMenu.unity and press Play.");
        }

        static void EnsureFolder(string path)
        {
            if (AssetDatabase.IsValidFolder(path)) return;
            string parent = Path.GetDirectoryName(path).Replace('\\', '/');
            string name = Path.GetFileName(path);
            if (!AssetDatabase.IsValidFolder(parent)) EnsureFolder(parent);
            AssetDatabase.CreateFolder(parent, name);
        }

        static void EnsureConfig()
        {
            if (AssetDatabase.LoadAssetAtPath<GameConfig>(ConfigPath) != null) return;
            var config = ScriptableObject.CreateInstance<GameConfig>();
            config.Validate();
            AssetDatabase.CreateAsset(config, ConfigPath);
        }

        static void EnsureScenes()
        {
            bool created = false;
            if (!File.Exists(MenuScenePath)) { CreateEmptyScene(MenuScenePath); created = true; }
            if (!File.Exists(GameScenePath)) { CreateEmptyScene(GameScenePath); created = true; }
            var scenes = new[]
            {
                new EditorBuildSettingsScene(MenuScenePath, true),
                new EditorBuildSettingsScene(GameScenePath, true),
            };
            var current = EditorBuildSettings.scenes;
            bool same = current.Length == 2 && current[0].path == MenuScenePath && current[1].path == GameScenePath && current.All(s => s.enabled);
            if (!same) EditorBuildSettings.scenes = scenes;
            if (created && !Application.isBatchMode && !EditorApplication.isPlayingOrWillChangePlaymode)
                EditorSceneManager.OpenScene(MenuScenePath, OpenSceneMode.Single);
        }

        /// <summary>
        /// Scenes are intentionally empty: GameBootstrap builds the menu or the arena at runtime based on the scene
        /// name, so scenes never contain fragile hand-wired references.
        /// </summary>
        static void CreateEmptyScene(string path)
        {
            var active = SceneManager.GetActiveScene();
            bool restore = active.IsValid() && !string.IsNullOrEmpty(active.path);
            string restorePath = active.path;
            var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);
            EditorSceneManager.SaveScene(scene, path);
            if (restore && File.Exists(restorePath)) EditorSceneManager.OpenScene(restorePath, OpenSceneMode.Single);
        }

        static void ConfigurePlayerSettings()
        {
            PlayerSettings.companyName = CompanyName;
            PlayerSettings.productName = ProductName;
            if (string.IsNullOrEmpty(PlayerSettings.bundleVersion) || PlayerSettings.bundleVersion == "0.1") PlayerSettings.bundleVersion = "1.0.0";
            PlayerSettings.SetApplicationIdentifier(BuildTargetGroup.Android, PackageId);
            PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel24;
            PlayerSettings.Android.targetSdkVersion = AndroidSdkVersions.AndroidApiLevelAuto;
            PlayerSettings.SetScriptingBackend(BuildTargetGroup.Android, ScriptingImplementation.IL2CPP);
            PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64 | AndroidArchitecture.ARMv7;
            PlayerSettings.Android.forceInternetPermission = false;
            PlayerSettings.Android.forceSDCardPermission = false;
            PlayerSettings.Android.startInFullscreen = true;
            PlayerSettings.Android.renderOutsideSafeArea = true;
            PlayerSettings.defaultInterfaceOrientation = UIOrientation.AutoRotation;
            PlayerSettings.allowedAutorotateToPortrait = true;
            PlayerSettings.allowedAutorotateToPortraitUpsideDown = false;
            PlayerSettings.allowedAutorotateToLandscapeLeft = true;
            PlayerSettings.allowedAutorotateToLandscapeRight = true;
            PlayerSettings.colorSpace = ColorSpace.Gamma;
            PlayerSettings.gpuSkinning = false;
            PlayerSettings.SetManagedStrippingLevel(BuildTargetGroup.Android, ManagedStrippingLevel.Low);
            PlayerSettings.runInBackground = false;
            PlayerSettings.SetMobileMTRendering(BuildTargetGroup.Android, true);
            QualitySettings.vSyncCount = 0;
        }

        /// <summary>Enables both the Input System package and the legacy manager ("Both"), which the code supports.</summary>
        static void ConfigureInputHandling()
        {
            var assets = AssetDatabase.LoadAllAssetsAtPath("ProjectSettings/ProjectSettings.asset");
            if (assets == null || assets.Length == 0) return;
            var so = new SerializedObject(assets[0]);
            var prop = so.FindProperty("activeInputHandler");
            if (prop == null || prop.intValue == 2) return;
            prop.intValue = 2;
            so.ApplyModifiedProperties();
            Debug.Log("[CubeSnake] Active Input Handling set to 'Both'. Restart the editor if Unity asks you to.");
        }

        static void ConfigureRenderPipeline()
        {
#if CUBESNAKE_URP
            if (GraphicsSettings.defaultRenderPipeline != null) return;
            try
            {
                const string assetPath = SettingsFolder + "/CubeClash_URP.asset";
                const string rendererPath = SettingsFolder + "/CubeClash_URP_Renderer.asset";
                var urpType = typeof(UnityEngine.Rendering.Universal.UniversalRenderPipelineAsset);
                var pipeline = AssetDatabase.LoadAssetAtPath<UnityEngine.Rendering.Universal.UniversalRenderPipelineAsset>(assetPath);
                if (pipeline == null)
                {
                    // URP's own "Create > Rendering > URP Asset (with Universal Renderer)" path, invoked via reflection
                    // because it is internal; it fills in all default shader/resource references.
                    var createRenderer = urpType.GetMethod("CreateRendererAsset", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static);
                    UnityEngine.Rendering.Universal.ScriptableRendererData rendererData = null;
                    if (createRenderer != null)
                    {
                        var ps = createRenderer.GetParameters();
                        var args = new object[ps.Length];
                        args[0] = rendererPath;
                        args[1] = System.Enum.Parse(ps[1].ParameterType, "UniversalRenderer");
                        for (int i = 2; i < ps.Length; i++) args[i] = ps[i].HasDefaultValue ? ps[i].DefaultValue : null;
                        if (ps.Length >= 3 && ps[2].ParameterType == typeof(bool)) args[2] = false; // absolute path
                        rendererData = createRenderer.Invoke(null, args) as UnityEngine.Rendering.Universal.ScriptableRendererData;
                    }
                    if (rendererData == null)
                    {
                        rendererData = ScriptableObject.CreateInstance<UnityEngine.Rendering.Universal.UniversalRendererData>();
                        AssetDatabase.CreateAsset(rendererData, rendererPath);
                    }
                    pipeline = UnityEngine.Rendering.Universal.UniversalRenderPipelineAsset.Create(rendererData);
                    pipeline.supportsHDR = false;
                    pipeline.msaaSampleCount = 2;
                    pipeline.shadowDistance = 0f;
                    AssetDatabase.CreateAsset(pipeline, assetPath);
                }
                GraphicsSettings.defaultRenderPipeline = pipeline;
                for (int i = 0; i < QualitySettings.names.Length; i++)
                {
                    QualitySettings.SetQualityLevel(i, false);
                    QualitySettings.renderPipeline = pipeline;
                }
                Debug.Log("[CubeSnake] URP pipeline asset created and assigned.");
            }
            catch (System.Exception e)
            {
                Debug.LogWarning("[CubeSnake] Could not configure URP automatically (" + e.Message + "). The built-in pipeline will be used; all game shaders support both.");
                GraphicsSettings.defaultRenderPipeline = null;
            }
#endif
        }

        [MenuItem("Cube Clash/Rendering/Use Built-in Pipeline")]
        static void UseBuiltIn()
        {
            GraphicsSettings.defaultRenderPipeline = null;
            for (int i = 0; i < QualitySettings.names.Length; i++)
            {
                QualitySettings.SetQualityLevel(i, false);
                QualitySettings.renderPipeline = null;
            }
            Debug.Log("[CubeSnake] Switched to the built-in render pipeline.");
        }

        [MenuItem("Cube Clash/Open Main Menu Scene")]
        static void OpenMenu()
        {
            if (!File.Exists(MenuScenePath)) Run(false);
            if (EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo()) EditorSceneManager.OpenScene(MenuScenePath);
        }

        [MenuItem("Cube Clash/Reset Saved Progress")]
        static void ResetProgress()
        {
            string path = Path.Combine(Application.persistentDataPath, "progress.json");
            foreach (var p in new[] { path, path + ".bak", path + ".tmp" }) if (File.Exists(p)) File.Delete(p);
            PlayerPrefs.DeleteAll();
            Debug.Log("[CubeSnake] Saved progress and settings deleted.");
        }
    }
}
