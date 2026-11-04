using System;
using CubeSnake.Core;
using CubeSnake.Logic;
using CubeSnake.Save;
using UnityEngine;
using UnityEngine.UI;

namespace CubeSnake.UI
{
    /// <summary>Settings screen (audio, controls, camera, graphics) shared by the main menu and the pause menu.</summary>
    public static class SettingsPanel
    {
        public static GameObject Open(Transform parent, Action onClose, bool allowReset)
        {
            var content = UIFactory.Modal(parent, "Settings", out var root, 940, 1600);
            var list = UIFactory.ScrollList(content, 18);

            Header(list, "Audio");
            UIFactory.ToggleRow(list, "Mute all", () => SettingsManager.Muted, v => { SettingsManager.Muted = v; SettingsManager.Save(); });
            UIFactory.SliderRow(list, "Music", 0, 1, SettingsManager.MusicVolume, v => { SettingsManager.MusicVolume = v; SettingsManager.Apply(); });
            UIFactory.SliderRow(list, "Effects", 0, 1, SettingsManager.SfxVolume, v => { SettingsManager.SfxVolume = v; SettingsManager.Apply(); });

            Header(list, "Controls");
            UIFactory.Segmented(list, "Steering", new[] { "Joystick", "Tap to steer" }, (int)SettingsManager.Controls,
                i => { SettingsManager.Controls = (ControlScheme)i; SettingsManager.Save(); });
            UIFactory.SliderRow(list, "Turn speed", 0.5f, 1.6f, SettingsManager.TurnSensitivity, v => { SettingsManager.TurnSensitivity = v; SettingsManager.Apply(); });
            UIFactory.SliderRow(list, "Joystick size", 0.7f, 1.4f, SettingsManager.JoystickScale, v => { SettingsManager.JoystickScale = v; SettingsManager.Apply(); });

            Header(list, "Camera");
            UIFactory.Segmented(list, "Camera", new[] { "Fixed angle", "Follow heading" }, (int)SettingsManager.Camera,
                i => { SettingsManager.Camera = (CameraMode)i; SettingsManager.Save(); });
            UIFactory.SliderRow(list, "Zoom", 0.7f, 1.5f, SettingsManager.CameraZoom, v => { SettingsManager.CameraZoom = v; SettingsManager.Apply(); });
            UIFactory.SliderRow(list, "Follow speed", 0.4f, 2f, SettingsManager.CameraSensitivity, v => { SettingsManager.CameraSensitivity = v; SettingsManager.Apply(); });
            UIFactory.ToggleRow(list, "Minimap", () => SettingsManager.ShowMinimap, v => { SettingsManager.ShowMinimap = v; SettingsManager.Save(); });

            Header(list, "Graphics");
            UIFactory.ToggleRow(list, "Low effects", () => SettingsManager.LowEffects, v => { SettingsManager.LowEffects = v; SettingsManager.Save(); });
            UIFactory.Segmented(list, "Frame rate", new[] { "30 FPS", "60 FPS" }, SettingsManager.TargetFps >= 60 ? 1 : 0,
                i => { SettingsManager.TargetFps = i == 1 ? 60 : 30; SettingsManager.Save(); });

            if (allowReset)
            {
                Header(list, "Data");
                Button reset = null;
                bool armed = false;
                reset = UIFactory.Button(list, "Reset progress", UITheme.PanelLight, () =>
                {
                    if (!armed) { armed = true; UIFactory.ButtonLabel(reset).text = "Tap again to confirm"; reset.image.color = UITheme.Danger; return; }
                    SaveManager.ResetProgress();
                    UIFactory.ButtonLabel(reset).text = "Progress reset";
                    reset.interactable = false;
                }, 40);
            }

            var close = UIFactory.Button(list, "Done", UITheme.Primary, () => { SettingsManager.Save(); UnityEngine.Object.Destroy(root); onClose?.Invoke(); });
            UIFactory.Layout(close, 130);
            return root;
        }

        static void Header(Transform parent, string text)
        {
            var t = UIFactory.Label(parent, text.ToUpperInvariant(), 36, UITheme.Accent, TextAnchor.LowerLeft);
            UIFactory.Layout(t, 80);
        }
    }

    public static class InfoPanels
    {
        public const string HowToPlay =
            "<b>Move</b>\nDrag the joystick (lower-left) to steer. Your chain never stops moving. Prefer tapping? Switch to <i>Tap to steer</i> in Settings.\n\n" +
            "<b>Collect</b>\nYou start as a single cube showing <b>1</b>. Drive into cubes whose number is <b>equal to or lower than your leading cube</b> to add them to your chain. Bigger cubes look greyed out: you can't take them yet.\n\n" +
            "<b>Merge</b>\nTwo neighbouring cubes with the same number merge into double: 1+1=2, 2+2=4, 4+4=8 ... Your chain stays sorted, biggest at the front, and merges cascade one step at a time.\n\n" +
            "<b>Boost</b>\nHold BOOST (lower-right) to speed up. It drains the energy ring, which refills when you let go.\n\n" +
            "<b>Rivals</b>\nYour leading cube is your strength. Hit a weaker rival's head to defeat it, or its body to cut off its tail. Run into a stronger rival and you're out. Equal strength bounces apart. Defeated chains burst into collectible cubes.\n\n" +
            "<b>Watch out</b>\nNames turn <color=#FF8C99>red</color> on rivals bigger than you, <color=#9CFFB3>green</color> on rivals you can beat. The minimap shows the same colours.\n\n" +
            "<b>Modes</b>\nClassic Arena: compete and survive.\nEndless Growth: relaxed, no defeats for you.\nSurvival Challenge: rivals get tougher every minute and boost recovers slowly.";

        public const string Credits =
            "<b>Cube Clash</b> — an original game inspired by number-merging snake .io games.\n\n" +
            "Design, code, art and audio: created for this project. All visuals (meshes, textures, font) and all sounds and music are generated procedurally by the game itself; no third-party assets are used.\n\n" +
            "Built with Unity.\n\n" +
            "<b>Privacy</b>\nCube Clash works fully offline. It does not require an account, does not collect personal data, does not use advertising or analytics SDKs, and requests no special Android permissions. Your progress and settings are stored only on this device and are removed when you uninstall the app or use Settings > Reset progress.";

        public static GameObject OpenText(Transform parent, string title, string body, Action onClose)
        {
            var content = UIFactory.Modal(parent, title, out var root, 940, 1600);
            var scrollArea = UIFactory.Rect("ScrollArea", content);
            UIFactory.Stretch(scrollArea, 0, 0, 0, 170);
            var list = UIFactory.ScrollList(scrollArea, 10);
            var text = UIFactory.Label(list, body, 38, UITheme.Text, TextAnchor.UpperLeft, false, false);
            text.lineSpacing = 1.1f;
            var close = UIFactory.Button(content, "Close", UITheme.Primary, () => { UnityEngine.Object.Destroy(root); onClose?.Invoke(); });
            var crt = (RectTransform)close.transform;
            crt.anchorMin = new Vector2(0, 0); crt.anchorMax = new Vector2(1, 0); crt.pivot = new Vector2(0.5f, 0);
            crt.sizeDelta = new Vector2(0, 130); crt.anchoredPosition = Vector2.zero;
            return root;
        }

        public static string StatsText(PlayerProgress p)
        {
            var sb = new System.Text.StringBuilder();
            sb.Append("<b>Best score</b>   ").Append(NumberFormat.FormatScore(p.bestScore)).Append('\n');
            sb.Append("<b>Highest number</b>   ").Append(NumberFormat.FormatLevel(p.highestLevel)).Append('\n');
            sb.Append("<b>Longest chain</b>   ").Append(p.bestChainLength).Append('\n');
            sb.Append("<b>Games played</b>   ").Append(p.gamesPlayed).Append('\n');
            sb.Append("<b>Cubes collected</b>   ").Append(p.totalCubesCollected.ToString("#,0")).Append('\n');
            sb.Append("<b>Merges</b>   ").Append(p.totalMerges.ToString("#,0")).Append('\n');
            sb.Append("<b>Rivals defeated</b>   ").Append(p.opponentsDefeated.ToString("#,0")).Append('\n');
            sb.Append("<b>Play time</b>   ").Append(NumberFormat.FormatDuration(p.totalPlayTimeSeconds)).Append('\n');
            sb.Append("<b>Longest game</b>   ").Append(NumberFormat.FormatDuration(p.longestSessionSeconds)).Append('\n');
            sb.Append("<b>Coins</b>   ").Append(p.coins.ToString("#,0")).Append("\n\n");
            string[] modes = { "Classic Arena", "Endless Growth", "Survival Challenge" };
            for (int m = 0; m < 3; m++)
            {
                var mb = p.GetModeBest((GameMode)m);
                sb.Append("<color=#FFC23D><b>").Append(modes[m]).Append("</b></color>\n");
                sb.Append("  Best ").Append(NumberFormat.FormatScore(mb.bestScore)).Append("  •  Top ").Append(NumberFormat.FormatLevel(mb.highestLevel));
                sb.Append("  •  ").Append(NumberFormat.FormatDuration(mb.longestSurvivalSeconds)).Append('\n');
            }
            return sb.ToString();
        }
    }
}
