using System;
using CubeSnake.Logic;
using CubeSnake.Visual;
using UnityEngine;
using UnityEngine.UI;

namespace CubeSnake.UI
{
    /// <summary>Game-over summary: final score, highest number, max chain, opponents defeated, duration and personal bests.</summary>
    public sealed class GameOverController : MonoBehaviour
    {
        GameObject _root;

        public void Show(Transform canvas, string title, SessionStats s, PersonalBestResult pb, string playerSkin, Action playAgain, Action menu)
        {
            if (_root != null) Destroy(_root);
            var content = UIFactory.Modal(canvas, title, out _root, 900, 1560);
            UIFactory.VerticalLayout(content, 12);

            var icon = UIFactory.Rect("Icon", content);
            UIFactory.Layout(icon, 190);
            var img = UIFactory.Rect("Img", icon);
            UIFactory.Place(img, new Vector2(0.5f, 0.5f), new Vector2(180, 180), Vector2.zero);
            var image = img.gameObject.AddComponent<Image>();
            image.sprite = TextureFactory.CubeFaceSprite(playerSkin, s.HighestLevel);
            image.preserveAspect = true;

            var score = UIFactory.Label(content, NumberFormat.FormatScore(s.Score), 96, Color.white);
            UIFactory.Layout(score, 120);
            string best = pb.NewBestScore ? "<color=#FFC23D>NEW PERSONAL BEST!</color>" : "Best: " + NumberFormat.FormatScore(Math.Max(pb.PreviousBestScore, s.Score));
            var bestLabel = UIFactory.Label(content, best, 44, UITheme.TextDim);
            UIFactory.Layout(bestLabel, 70);

            Row(content, "Highest number", NumberFormat.FormatLevel(s.HighestLevel) + (pb.NewHighestLevel ? "  <color=#FFC23D>NEW!</color>" : ""));
            Row(content, "Longest chain", s.MaxChainLength.ToString());
            Row(content, "Rivals defeated", s.OpponentsDefeated.ToString());
            Row(content, "Cubes collected", s.CubesCollected.ToString());
            Row(content, "Merges", s.Merges.ToString());
            Row(content, "Time", NumberFormat.FormatDuration(s.DurationSeconds));
            Row(content, "Coins earned", "+" + pb.CoinsEarned);
            if (pb.NewUnlocks != null && pb.NewUnlocks.Count > 0)
            {
                var names = new System.Collections.Generic.List<string>();
                foreach (var id in pb.NewUnlocks) names.Add(UnlockCatalog.Get(id)?.DisplayName ?? id);
                var unlock = UIFactory.Label(content, "Unlocked: " + string.Join(", ", names), 40, UITheme.Good);
                UIFactory.Layout(unlock, 70);
            }

            var spacer = UIFactory.Rect("Spacer", content);
            UIFactory.Layout(spacer, 20);
            UIFactory.Button(content, "Play Again", UITheme.Good, () => playAgain?.Invoke());
            UIFactory.Button(content, "Main Menu", UITheme.Primary, () => menu?.Invoke());
        }

        static void Row(Transform parent, string label, string value)
        {
            var row = UIFactory.Rect("Row", parent);
            UIFactory.Layout(row, 62);
            var l = UIFactory.Label(row, label, 40, UITheme.TextDim, TextAnchor.MiddleLeft, false);
            UIFactory.Stretch(l.rectTransform, 30, 0, 0, 0);
            var v = UIFactory.Label(row, value, 42, Color.white, TextAnchor.MiddleRight);
            UIFactory.Stretch(v.rectTransform, 0, 30, 0, 0);
        }

        public void Hide()
        {
            if (_root != null) Destroy(_root);
            _root = null;
        }
    }
}
