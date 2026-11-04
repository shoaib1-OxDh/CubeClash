using System.Collections.Generic;
using UnityEngine;

namespace CubeSnake.Visual
{
    /// <summary>
    /// Tiny original stroke font (digits, K, M, B, T, '.', '^') rendered with signed-distance capsules, so cube
    /// labels are crisp, bold and anti-aliased at any resolution without shipping a font asset.
    /// Glyph space: x in [0, width], y in [0, 1.6] (y up).
    /// </summary>
    public static class GlyphFont
    {
        public const float GlyphHeight = 1.6f;

        public sealed class Glyph
        {
            public float Width = 1f;
            public readonly List<Vector2[]> Strokes = new List<Vector2[]>();
        }

        static Dictionary<char, Glyph> s_glyphs;

        static Vector2[] Arc(float cx, float cy, float rx, float ry, float a0Deg, float a1Deg, int steps = 18)
        {
            var pts = new Vector2[steps + 1];
            for (int i = 0; i <= steps; i++)
            {
                float a = Mathf.Lerp(a0Deg, a1Deg, i / (float)steps) * Mathf.Deg2Rad;
                pts[i] = new Vector2(cx + Mathf.Cos(a) * rx, cy + Mathf.Sin(a) * ry);
            }
            return pts;
        }

        static Vector2[] Line(params float[] xy)
        {
            var pts = new Vector2[xy.Length / 2];
            for (int i = 0; i < pts.Length; i++) pts[i] = new Vector2(xy[i * 2], xy[i * 2 + 1]);
            return pts;
        }

        static Vector2[] Concat(params Vector2[][] parts)
        {
            var list = new List<Vector2>();
            foreach (var p in parts) list.AddRange(p);
            return list.ToArray();
        }

        static Dictionary<char, Glyph> Glyphs
        {
            get
            {
                if (s_glyphs != null) return s_glyphs;
                s_glyphs = new Dictionary<char, Glyph>();
                Glyph G(char c, float w = 1f) { var g = new Glyph { Width = w }; s_glyphs[c] = g; return g; }

                G('0').Strokes.Add(Arc(0.5f, 0.8f, 0.4f, 0.7f, 0, 360, 36));
                G('1').Strokes.Add(Line(0.18f, 1.22f, 0.56f, 1.52f, 0.56f, 0.08f));
                s_glyphs['1'].Strokes.Add(Line(0.2f, 0.08f, 0.9f, 0.08f));
                G('2').Strokes.Add(Concat(Arc(0.5f, 1.1f, 0.4f, 0.42f, 160, -30), Line(0.1f, 0.08f, 0.92f, 0.08f)));
                G('3').Strokes.Add(Arc(0.48f, 1.16f, 0.37f, 0.36f, 150, -90));
                s_glyphs['3'].Strokes.Add(Arc(0.48f, 0.44f, 0.42f, 0.38f, 90, -160));
                G('4').Strokes.Add(Line(0.7f, 0.08f, 0.7f, 1.52f, 0.08f, 0.48f, 0.94f, 0.48f));
                G('5').Strokes.Add(Concat(Line(0.86f, 1.52f, 0.2f, 1.52f, 0.14f, 0.86f), Arc(0.5f, 0.5f, 0.42f, 0.44f, 128, -150)));
                G('6').Strokes.Add(Arc(0.5f, 0.48f, 0.4f, 0.42f, 0, 360, 32));
                s_glyphs['6'].Strokes.Add(Arc(0.95f, 0.55f, 0.85f, 0.95f, 100, 180));
                G('7').Strokes.Add(Line(0.08f, 1.52f, 0.92f, 1.52f, 0.36f, 0.08f));
                G('8').Strokes.Add(Arc(0.5f, 1.18f, 0.32f, 0.33f, 0, 360, 30));
                s_glyphs['8'].Strokes.Add(Arc(0.5f, 0.45f, 0.41f, 0.38f, 0, 360, 32));
                var nine = G('9');
                foreach (var stroke in s_glyphs['6'].Strokes)
                {
                    var r = new Vector2[stroke.Length];
                    for (int i = 0; i < r.Length; i++) r[i] = new Vector2(1f - stroke[i].x, GlyphHeight - stroke[i].y);
                    nine.Strokes.Add(r);
                }
                var k = G('K', 0.95f);
                k.Strokes.Add(Line(0.12f, 0.08f, 0.12f, 1.52f));
                k.Strokes.Add(Line(0.86f, 1.52f, 0.14f, 0.66f));
                k.Strokes.Add(Line(0.4f, 0.96f, 0.9f, 0.08f));
                G('M', 1.2f).Strokes.Add(Line(0.1f, 0.08f, 0.14f, 1.52f, 0.6f, 0.6f, 1.06f, 1.52f, 1.1f, 0.08f));
                var b = G('B');
                b.Strokes.Add(Concat(Line(0.14f, 0.08f, 0.14f, 1.52f, 0.52f, 1.52f), Arc(0.52f, 1.18f, 0.34f, 0.34f, 90, -90), Line(0.52f, 0.84f, 0.14f, 0.84f)));
                b.Strokes.Add(Concat(Line(0.14f, 0.84f, 0.56f, 0.84f), Arc(0.56f, 0.46f, 0.38f, 0.38f, 90, -90), Line(0.56f, 0.08f, 0.14f, 0.08f)));
                var t = G('T');
                t.Strokes.Add(Line(0.06f, 1.52f, 0.94f, 1.52f));
                t.Strokes.Add(Line(0.5f, 1.52f, 0.5f, 0.08f));
                G('.', 0.42f).Strokes.Add(Line(0.21f, 0.12f, 0.21f, 0.12f));
                G('^', 0.8f).Strokes.Add(Line(0.1f, 1.0f, 0.4f, 1.5f, 0.7f, 1.0f));
                return s_glyphs;
            }
        }

        public static float MeasureWidth(string text, float spacing = 0.18f)
        {
            float w = 0;
            for (int i = 0; i < text.Length; i++)
            {
                w += Glyphs.TryGetValue(text[i], out var g) ? g.Width : 0.6f;
                if (i < text.Length - 1) w += spacing;
            }
            return w;
        }

        /// <summary>Collects every stroke segment of <paramref name="text"/> laid out from origin (glyph units).</summary>
        public static void Layout(string text, List<Vector4> segments, float spacing = 0.18f)
        {
            float x = 0;
            foreach (char c in text)
            {
                if (Glyphs.TryGetValue(c, out var g))
                {
                    foreach (var stroke in g.Strokes)
                    {
                        if (stroke.Length == 1) segments.Add(new Vector4(stroke[0].x + x, stroke[0].y, stroke[0].x + x, stroke[0].y));
                        for (int i = 0; i + 1 < stroke.Length; i++)
                            segments.Add(new Vector4(stroke[i].x + x, stroke[i].y, stroke[i + 1].x + x, stroke[i + 1].y));
                    }
                    x += g.Width + spacing;
                }
                else x += 0.6f + spacing;
            }
        }

        /// <summary>Distance from p to segment (a,b).</summary>
        public static float SegmentDistance(Vector2 p, Vector2 a, Vector2 b)
        {
            Vector2 ab = b - a;
            float len2 = ab.sqrMagnitude;
            float t = len2 > 1e-8f ? Mathf.Clamp01(Vector2.Dot(p - a, ab) / len2) : 0f;
            return (p - (a + ab * t)).magnitude;
        }
    }
}
