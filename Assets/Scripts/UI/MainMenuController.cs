using System.Collections.Generic;
using CubeSnake.Core;
using CubeSnake.Logic;
using CubeSnake.Save;
using CubeSnake.Visual;
using CubeSnake.World;
using UnityEngine;
using UnityEngine.UI;

namespace CubeSnake.UI
{
    /// <summary>
    /// Main menu: logo, mode and difficulty selection, Play, Settings, How to Play, High Score / statistics,
    /// cosmetics, sound toggle, and privacy & credits. A slow 3D cube showcase runs in the background.
    /// </summary>
    public sealed class MainMenuController : MonoBehaviour
    {
        Canvas _canvas;
        RectTransform _safe;
        GameObject _mainPanel;
        Text _bestText, _soundLabel;
        Button _soundButton;
        readonly List<Transform> _showcase = new List<Transform>();
        readonly List<Vector3> _spin = new List<Vector3>();
        Camera _camera;

        void Start()
        {
            Time.timeScale = 1f;
            BuildBackground();
            BuildUI();
        }

        void BuildBackground()
        {
            var camGo = new GameObject("MenuCamera");
            camGo.transform.SetParent(transform, false);
            _camera = camGo.AddComponent<Camera>();
            _camera.clearFlags = CameraClearFlags.SolidColor;
            var arena = GameConfig.Instance.arena;
            _camera.backgroundColor = arena.skyColor;
            _camera.fieldOfView = 50f;
            _camera.transform.position = new Vector3(0, 9, -16);
            _camera.transform.LookAt(new Vector3(0, 1.5f, 0));
            camGo.tag = "MainCamera";
            ArenaBuilder.ApplyLighting(arena);
            RenderSettings.fogStartDistance = 25f;
            RenderSettings.fogEndDistance = 70f;

            var ground = new GameObject("Ground");
            ground.transform.SetParent(transform, false);
            ground.AddComponent<MeshFilter>().sharedMesh = MeshFactory.GroundQuad;
            var mat = new Material(CubeMaterials.FindShader("CubeSnake/Ground"));
            mat.SetColor("_Color", arena.groundColor);
            mat.SetColor("_GridColor", arena.gridColor);
            mat.SetColor("_OutsideColor", arena.groundColor);
            mat.SetColor("_BoundaryColor", arena.groundColor);
            mat.SetFloat("_ArenaRadius", 1000f);
            mat.SetFloat("_GridSize", 2f);
            mat.SetFloat("_LineWidth", 0.03f);
            ground.AddComponent<MeshRenderer>().sharedMaterial = mat;
            ground.transform.localScale = new Vector3(200, 1, 200);

            string skin = SaveManager.Progress.selectedSkin;
            var rng = new Rng(11);
            for (int i = 0; i < 14; i++)
            {
                int level = i % 9;
                var go = new GameObject("ShowcaseCube");
                go.transform.SetParent(transform, false);
                go.AddComponent<MeshFilter>().sharedMesh = MeshFactory.RoundedCube;
                go.AddComponent<MeshRenderer>().sharedMaterial = CubeMaterials.Cube(skin, level);
                float a = i / 14f * Mathf.PI * 2f;
                float r = rng.Range(6f, 13f);
                go.transform.position = new Vector3(Mathf.Cos(a) * r, rng.Range(1f, 5f), Mathf.Sin(a) * r + 4f);
                go.transform.localScale = Vector3.one * rng.Range(1.2f, 2.4f);
                go.transform.rotation = Quaternion.Euler(rng.Range(0, 360), rng.Range(0, 360), 0);
                _showcase.Add(go.transform);
                _spin.Add(new Vector3(rng.Range(-30, 30), rng.Range(-40, 40), rng.Range(-20, 20)));
            }
        }

        void Update()
        {
            float dt = Time.unscaledDeltaTime;
            for (int i = 0; i < _showcase.Count; i++)
            {
                _showcase[i].Rotate(_spin[i] * dt, Space.World);
                var p = _showcase[i].position;
                p.y += Mathf.Sin(Time.unscaledTime * 0.8f + i) * 0.004f;
                _showcase[i].position = p;
            }
            if (_camera != null)
            {
                float a = Time.unscaledTime * 0.05f;
                _camera.transform.position = new Vector3(Mathf.Sin(a) * 4f, 9f, -16f);
                _camera.transform.LookAt(new Vector3(0, 1.5f, 3f));
            }
        }

        void BuildUI()
        {
            _canvas = UIFactory.CreateCanvas("MenuCanvas", 0, transform);
            _safe = UIFactory.SafeArea(_canvas.transform);

            _mainPanel = UIFactory.Rect("Main", _safe).gameObject;
            var main = UIFactory.Stretch((RectTransform)_mainPanel.transform, 60, 60, 40, 40);
            var column = UIFactory.Rect("Column", main);
            UIFactory.Place(column, new Vector2(0.5f, 0.5f), new Vector2(960, 1800), Vector2.zero);
            column.gameObject.AddComponent<FitInsideParent>();
            UIFactory.VerticalLayout(column, 22, null, TextAnchor.MiddleCenter);

            // Logo: a row of cubes 1..64 and the title.
            var logoRow = UIFactory.Rect("LogoCubes", column);
            UIFactory.Layout(logoRow, 150);
            var h = UIFactory.HorizontalLayout(logoRow, 12);
            h.childForceExpandWidth = false;
            h.childControlWidth = false;
            h.childControlHeight = false;
            string skin = SaveManager.Progress.selectedSkin;
            for (int l = 0; l <= 6; l++)
            {
                var c = UIFactory.Rect("Cube" + l, logoRow);
                c.sizeDelta = new Vector2(112, 112);
                var img = c.gameObject.AddComponent<Image>();
                img.sprite = TextureFactory.CubeFaceSprite(skin, l);
                img.raycastTarget = false;
                c.localRotation = Quaternion.Euler(0, 0, (l % 2 == 0 ? 6 : -6));
            }
            var title = UIFactory.Label(column, "CUBE CLASH", 132, Color.white);
            UIFactory.Layout(title, 170);
            var outline = title.gameObject.AddComponent<Outline>();
            outline.effectColor = new Color(0.15f, 0.2f, 0.5f, 0.9f);
            outline.effectDistance = new Vector2(5, -5);
            var sub = UIFactory.Label(column, "Collect  •  Merge  •  Grow  •  Conquer", 44, new Color(1, 1, 1, 0.9f));
            UIFactory.Layout(sub, 70);

            _bestText = UIFactory.Label(column, "", 44, UITheme.Accent);
            UIFactory.Layout(_bestText, 70);
            RefreshBest();

            var modeCard = UIFactory.Panel(column, new Color(0.08f, 0.1f, 0.22f, 0.65f), 36, "ModeCard");
            UIFactory.Layout(modeCard, 560);
            var modeContent = UIFactory.Stretch(UIFactory.Rect("Content", modeCard.transform), 34, 34, 26, 26);
            UIFactory.VerticalLayout(modeContent, 12);
            var modeDesc = UIFactory.Label(modeContent, "", 34, UITheme.TextDim, TextAnchor.MiddleCenter, false);
            string[] modeNames = { "Classic", "Endless", "Survival" };
            int selMode = SettingsManager.LastMode;
            GameSession.Mode = (GameMode)selMode;
            UIFactory.Segmented(modeContent, "Mode", modeNames, selMode, i =>
            {
                SettingsManager.LastMode = i;
                GameSession.Mode = (GameMode)i;
                modeDesc.text = GameModeSettings.For((GameMode)i).description;
                PlayerPrefs.Save();
            });
            modeDesc.transform.SetAsLastSibling();
            UIFactory.Layout(modeDesc, 110);
            modeDesc.text = GameModeSettings.For((GameMode)selMode).description;
            int selDiff = SettingsManager.LastDifficulty;
            GameSession.Difficulty = (AIDifficulty)selDiff;
            UIFactory.Segmented(modeContent, "Rivals", new[] { "Easy", "Normal", "Hard" }, selDiff, i =>
            {
                SettingsManager.LastDifficulty = i;
                GameSession.Difficulty = (AIDifficulty)i;
                PlayerPrefs.Save();
            });

            var play = UIFactory.Button(column, "PLAY", UITheme.Good, () => SceneFlow.LoadGame(), 84);
            UIFactory.Layout(play, 190);

            var row1 = UIFactory.Rect("Row1", column);
            UIFactory.Layout(row1, 130);
            UIFactory.HorizontalLayout(row1, 20);
            UIFactory.Button(row1, "Settings", UITheme.Primary, OpenSettings, 42);
            UIFactory.Button(row1, "How to Play", UITheme.Primary, () => OpenModal(() => InfoPanels.OpenText(_canvas.transform, "How to Play", InfoPanels.HowToPlay, ShowMain)), 42);
            UIFactory.Button(row1, "High Score", UITheme.Primary, () => OpenModal(() => InfoPanels.OpenText(_canvas.transform, "High Score & Stats", InfoPanels.StatsText(SaveManager.Progress), ShowMain)), 42);

            var row2 = UIFactory.Rect("Row2", column);
            UIFactory.Layout(row2, 130);
            UIFactory.HorizontalLayout(row2, 20);
            UIFactory.Button(row2, "Customize", UITheme.Accent, () => OpenModal(() => CustomizePanel.Open(_canvas.transform, ShowMain)), 42);
            _soundButton = UIFactory.Button(row2, "", UITheme.PanelLight, ToggleSound, 42);
            _soundLabel = UIFactory.ButtonLabel(_soundButton);
            RefreshSound();
            UIFactory.Button(row2, "Privacy & Credits", UITheme.PanelLight, () => OpenModal(() => InfoPanels.OpenText(_canvas.transform, "Privacy & Credits", InfoPanels.Credits, ShowMain)), 36);
        }

        void RefreshBest()
        {
            var p = SaveManager.Progress;
            _bestText.text = p.gamesPlayed > 0
                ? "Best " + NumberFormat.FormatScore(p.bestScore) + "   •   Top cube " + NumberFormat.FormatLevel(p.highestLevel)
                : "Start as a 1 — how big can you grow?";
        }

        void ToggleSound()
        {
            SettingsManager.Muted = !SettingsManager.Muted;
            SettingsManager.Save();
            RefreshSound();
        }

        void RefreshSound()
        {
            _soundLabel.text = SettingsManager.Muted ? "Sound: OFF" : "Sound: ON";
            _soundButton.image.color = SettingsManager.Muted ? UITheme.PanelLight : UITheme.Good;
        }

        void OpenSettings() => OpenModal(() => SettingsPanel.Open(_canvas.transform, () => { RefreshSound(); ShowMain(); }, true));

        GameObject _modal;

        void OpenModal(System.Func<GameObject> open)
        {
            _mainPanel.SetActive(false);
            _modal = open();
        }

        void ShowMain()
        {
            _modal = null;
            _mainPanel.SetActive(true);
            RefreshBest();
        }

        void LateUpdate()
        {
            if (Player.KeyboardInput.BackPressed())
            {
                if (_modal != null) { Destroy(_modal); ShowMain(); }
                else Application.Quit();
            }
        }
    }

    /// <summary>Cosmetic selection (skins and trails) unlocked through play.</summary>
    public static class CustomizePanel
    {
        public static GameObject Open(Transform parent, System.Action onClose)
        {
            var content = UIFactory.Modal(parent, "Customize", out var root, 940, 1600);
            var area = UIFactory.Rect("Area", content);
            UIFactory.Stretch(area, 0, 0, 0, 170);
            var list = UIFactory.ScrollList(area, 16);
            var p = SaveManager.Progress;
            var coins = UIFactory.Label(list, "Coins: " + p.coins.ToString("#,0") + "   (earned by playing)", 38, UITheme.Accent);
            UIFactory.Layout(coins, 70);

            var skinButtons = new List<(Button b, UnlockItem item)>();
            var trailButtons = new List<(Button b, UnlockItem item)>();
            Header(list, "Cube skins");
            foreach (var item in UnlockCatalog.Items)
            {
                if (item.Kind != UnlockKind.Skin) continue;
                skinButtons.Add((ItemRow(list, item, p, () => Refresh(skinButtons, trailButtons)), item));
            }
            Header(list, "Boost trails");
            foreach (var item in UnlockCatalog.Items)
            {
                if (item.Kind != UnlockKind.Trail) continue;
                trailButtons.Add((ItemRow(list, item, p, () => Refresh(skinButtons, trailButtons)), item));
            }
            Refresh(skinButtons, trailButtons);

            var close = UIFactory.Button(content, "Done", UITheme.Primary, () => { Object.Destroy(root); onClose?.Invoke(); });
            var crt = (RectTransform)close.transform;
            crt.anchorMin = new Vector2(0, 0); crt.anchorMax = new Vector2(1, 0); crt.pivot = new Vector2(0.5f, 0);
            crt.sizeDelta = new Vector2(0, 130); crt.anchoredPosition = Vector2.zero;
            return root;
        }

        static void Header(Transform parent, string text)
        {
            var t = UIFactory.Label(parent, text.ToUpperInvariant(), 36, UITheme.Accent, TextAnchor.LowerLeft);
            UIFactory.Layout(t, 80);
        }

        static Button ItemRow(Transform parent, UnlockItem item, PlayerProgress p, System.Action changed)
        {
            var row = UIFactory.Rect("Item_" + item.Id, parent);
            UIFactory.Layout(row, 150);
            var preview = UIFactory.Rect("Preview", row);
            UIFactory.Place(preview, new Vector2(0, 0.5f), new Vector2(120, 120), new Vector2(10, 0));
            var img = preview.gameObject.AddComponent<Image>();
            if (item.Kind == UnlockKind.Skin) img.sprite = TextureFactory.CubeFaceSprite(item.Id, 4);
            else { img.sprite = TextureFactory.CircleSprite(true); img.color = CubePalette.TrailColor(item.Id, 0.4f); }
            img.raycastTarget = false;
            var name = UIFactory.Label(row, item.DisplayName, 44, Color.white, TextAnchor.UpperLeft);
            UIFactory.Stretch(name.rectTransform, 150, 300, 18, 70);
            var req = UIFactory.Label(row, item.RequirementText, 30, UITheme.TextDim, TextAnchor.LowerLeft, false);
            UIFactory.Stretch(req.rectTransform, 150, 300, 70, 16);
            var b = UIFactory.Button(row, "", UITheme.PanelLight, () =>
            {
                if (!UnlockCatalog.IsUnlocked(item, p)) return;
                if (item.Kind == UnlockKind.Skin) p.selectedSkin = item.Id; else p.selectedTrail = item.Id;
                SaveManager.Save();
                changed();
            }, 38);
            var brt = (RectTransform)b.transform;
            brt.anchorMin = new Vector2(1, 0.12f); brt.anchorMax = new Vector2(1, 0.88f); brt.pivot = new Vector2(1, 0.5f);
            brt.sizeDelta = new Vector2(260, 0); brt.anchoredPosition = Vector2.zero;
            return b;
        }

        static void Refresh(List<(Button b, UnlockItem item)> skins, List<(Button b, UnlockItem item)> trails)
        {
            var p = SaveManager.Progress;
            void Apply(List<(Button b, UnlockItem item)> list, string selected)
            {
                foreach (var (b, item) in list)
                {
                    bool unlocked = UnlockCatalog.IsUnlocked(item, p);
                    bool sel = item.Id == selected;
                    UIFactory.ButtonLabel(b).text = !unlocked ? "Locked" : (sel ? "Selected" : "Select");
                    b.image.color = !unlocked ? new Color(1, 1, 1, 0.08f) : (sel ? UITheme.Good : UITheme.Primary);
                    b.interactable = unlocked;
                }
            }
            Apply(skins, p.selectedSkin);
            Apply(trails, p.selectedTrail);
        }
    }
}
