using System.Collections.Generic;
using CubeSnake.Logic;
using UnityEngine;

namespace CubeSnake.Visual
{
    /// <summary>Generates every texture the game needs at runtime (cube faces, soft circles, UI sprites).</summary>
    public static class TextureFactory
    {
        public const int FaceSize = 128;

        static Texture2D s_softCircle, s_hardCircle, s_white;
        static readonly Dictionary<string, Sprite> s_sprites = new Dictionary<string, Sprite>();
        static readonly List<Vector4> s_segments = new List<Vector4>(128);

        public static Texture2D White
        {
            get
            {
                if (s_white != null) return s_white;
                s_white = new Texture2D(4, 4, TextureFormat.RGBA32, false) { name = "CS_White" };
                var px = new Color32[16];
                for (int i = 0; i < px.Length; i++) px[i] = new Color32(255, 255, 255, 255);
                s_white.SetPixels32(px);
                s_white.Apply(false, true);
                return s_white;
            }
        }

        /// <summary>Radial gradient (white, alpha falls off smoothly). Used for blob shadows, halos and particles.</summary>
        public static Texture2D SoftCircle
        {
            get
            {
                if (s_softCircle == null) s_softCircle = Circle(64, 0.0f, "CS_SoftCircle");
                return s_softCircle;
            }
        }

        public static Texture2D HardCircle
        {
            get
            {
                if (s_hardCircle == null) s_hardCircle = Circle(128, 0.92f, "CS_HardCircle");
                return s_hardCircle;
            }
        }

        static Texture2D Circle(int size, float hardness, string name)
        {
            var tex = new Texture2D(size, size, TextureFormat.RGBA32, true) { name = name, wrapMode = TextureWrapMode.Clamp };
            var px = new Color32[size * size];
            for (int y = 0; y < size; y++)
            {
                for (int x = 0; x < size; x++)
                {
                    float dx = (x + 0.5f) / size * 2f - 1f, dy = (y + 0.5f) / size * 2f - 1f;
                    float d = Mathf.Sqrt(dx * dx + dy * dy);
                    float a = hardness <= 0f ? Mathf.Clamp01(1f - d) : Mathf.Clamp01((1f - d) / Mathf.Max(0.01f, 1f - hardness));
                    a = hardness <= 0f ? a * a * (3f - 2f * a) : a;
                    px[y * size + x] = new Color32(255, 255, 255, (byte)(a * 255));
                }
            }
            tex.SetPixels32(px);
            tex.Apply(true, true);
            return tex;
        }

        /// <summary>
        /// Face texture for a cube: rounded panel in the level colour, subtle bevel and the number in bold strokes
        /// with a dark outline. Every face of the cube uses this texture.
        /// </summary>
        public static Texture2D CubeFace(int level, Color face)
        {
            int size = FaceSize;
            var tex = new Texture2D(size, size, TextureFormat.RGBA32, true) { name = "CS_Face_" + level, wrapMode = TextureWrapMode.Clamp, anisoLevel = 2, filterMode = FilterMode.Trilinear };
            string label = NumberFormat.FormatLevel(level);
            Color textCol = CubePalette.TextColorFor(face);
            Color outline = Color.Lerp(face, Color.black, 0.55f);
            Color border = Color.Lerp(face, Color.black, 0.22f);
            Color highlight = Color.Lerp(face, Color.white, 0.18f);

            // Layout text to fit inside 78% of the face.
            s_segments.Clear();
            GlyphFont.Layout(label, s_segments);
            float textW = GlyphFont.MeasureWidth(label);
            float textH = GlyphFont.GlyphHeight;
            float maxW = 0.74f, maxH = 0.5f;
            float scale = Mathf.Min(maxW / textW, maxH / textH);
            float stroke = Mathf.Clamp(0.13f * scale * 1.6f, 0.035f, 0.075f); // stroke half-width in uv units
            float outlineW = stroke * 1.55f;
            float offX = 0.5f - textW * scale * 0.5f, offY = 0.5f - textH * scale * 0.5f;
            // Transform segments into UV space once.
            for (int i = 0; i < s_segments.Count; i++)
            {
                var s = s_segments[i];
                s_segments[i] = new Vector4(offX + s.x * scale, offY + s.y * scale, offX + s.z * scale, offY + s.w * scale);
            }

            float aa = 1.2f / size;
            // Distance fields: evaluate each segment only inside its padded bounding box (fast enough for mobile).
            var dist = DistanceField(size, outlineW + 4f * aa, Vector2.zero);
            var shadowDist = DistanceField(size, outlineW + 4f * aa, new Vector2(0.018f, -0.022f));
            var px = new Color[size * size];
            for (int y = 0; y < size; y++)
            {
                for (int x = 0; x < size; x++)
                {
                    float u = (x + 0.5f) / size, v = (y + 0.5f) / size;
                    float edge = Mathf.Min(Mathf.Min(u, 1f - u), Mathf.Min(v, 1f - v));
                    float vertical = Mathf.Lerp(1.06f, 0.94f, 1f - v);
                    Color c = new Color(face.r * vertical, face.g * vertical, face.b * vertical, 1f);
                    if (edge < 0.07f) c = Color.Lerp(border, c, Mathf.SmoothStep(0f, 1f, edge / 0.07f));
                    else if (edge < 0.1f && v > 0.5f) c = Color.Lerp(c, highlight, 0.35f);

                    int idx = y * size + x;
                    float d = dist[idx];
                    float shadowA = 1f - Mathf.SmoothStep(outlineW - aa, outlineW + aa * 3f, shadowDist[idx]);
                    c = Color.Lerp(c, outline * 0.7f, shadowA * 0.5f);
                    float outlineA = 1f - Mathf.SmoothStep(outlineW - aa, outlineW + aa, d);
                    float textA = 1f - Mathf.SmoothStep(stroke - aa, stroke + aa, d);
                    c = Color.Lerp(c, outline, outlineA);
                    c = Color.Lerp(c, textCol, textA);
                    c.a = 1f;
                    px[idx] = c;
                }
            }
            tex.SetPixels(px);
            tex.Apply(true, true);
            return tex;
        }

        static float[] DistanceField(int size, float maxDist, Vector2 offset)
        {
            var field = new float[size * size];
            for (int i = 0; i < field.Length; i++) field[i] = float.MaxValue;
            for (int i = 0; i < s_segments.Count; i++)
            {
                var s = s_segments[i];
                Vector2 a = new Vector2(s.x, s.y) + offset, b = new Vector2(s.z, s.w) + offset;
                int x0 = Mathf.Max(0, Mathf.FloorToInt((Mathf.Min(a.x, b.x) - maxDist) * size));
                int x1 = Mathf.Min(size - 1, Mathf.CeilToInt((Mathf.Max(a.x, b.x) + maxDist) * size));
                int y0 = Mathf.Max(0, Mathf.FloorToInt((Mathf.Min(a.y, b.y) - maxDist) * size));
                int y1 = Mathf.Min(size - 1, Mathf.CeilToInt((Mathf.Max(a.y, b.y) + maxDist) * size));
                for (int y = y0; y <= y1; y++)
                {
                    float v = (y + 0.5f) / size;
                    for (int x = x0; x <= x1; x++)
                    {
                        float d = GlyphFont.SegmentDistance(new Vector2((x + 0.5f) / size, v), a, b);
                        int idx = y * size + x;
                        if (d < field[idx]) field[idx] = d;
                    }
                }
            }
            return field;
        }

        /// <summary>9-sliceable rounded rectangle sprite for UI panels and buttons.</summary>
        public static Sprite RoundedRect(int radius = 24, int border = 0)
        {
            string key = "rr" + radius + "_" + border;
            if (s_sprites.TryGetValue(key, out var cached)) return cached;
            int size = radius * 2 + 4;
            var tex = new Texture2D(size, size, TextureFormat.RGBA32, false) { name = "CS_" + key, wrapMode = TextureWrapMode.Clamp, filterMode = FilterMode.Bilinear };
            var px = new Color32[size * size];
            float c = size / 2f;
            for (int y = 0; y < size; y++)
            {
                for (int x = 0; x < size; x++)
                {
                    float dx = Mathf.Max(Mathf.Abs(x + 0.5f - c) - (c - radius), 0f);
                    float dy = Mathf.Max(Mathf.Abs(y + 0.5f - c) - (c - radius), 0f);
                    float d = Mathf.Sqrt(dx * dx + dy * dy);
                    float a = Mathf.Clamp01(radius - d + 0.5f);
                    if (border > 0)
                    {
                        float inner = Mathf.Clamp01(d - (radius - border) + 0.5f);
                        a *= inner;
                    }
                    px[y * size + x] = new Color32(255, 255, 255, (byte)(a * 255));
                }
            }
            tex.SetPixels32(px);
            tex.Apply(false, true);
            var sprite = Sprite.Create(tex, new Rect(0, 0, size, size), new Vector2(0.5f, 0.5f), 100f, 0, SpriteMeshType.FullRect,
                new Vector4(radius + 1, radius + 1, radius + 1, radius + 1));
            sprite.name = key;
            s_sprites[key] = sprite;
            return sprite;
        }

        public static Sprite CircleSprite(bool soft = false)
        {
            string key = soft ? "circleSoft" : "circle";
            if (s_sprites.TryGetValue(key, out var cached)) return cached;
            var tex = soft ? SoftCircle : HardCircle;
            var sprite = Sprite.Create(tex, new Rect(0, 0, tex.width, tex.height), new Vector2(0.5f, 0.5f), 100f);
            sprite.name = key;
            s_sprites[key] = sprite;
            return sprite;
        }

        /// <summary>Sprite of a cube face (used in UI: logo, HUD, unlock previews).</summary>
        public static Sprite CubeFaceSprite(string skin, int level)
        {
            string key = "face_" + skin + "_" + level;
            if (s_sprites.TryGetValue(key, out var cached)) return cached;
            var tex = CubeMaterials.FaceTexture(skin, level);
            var sprite = Sprite.Create(tex, new Rect(0, 0, tex.width, tex.height), new Vector2(0.5f, 0.5f), 100f);
            sprite.name = key;
            s_sprites[key] = sprite;
            return sprite;
        }

        /// <summary>Vertical gradient used as the menu background.</summary>
        public static Texture2D VerticalGradient(Color top, Color bottom)
        {
            var tex = new Texture2D(2, 64, TextureFormat.RGBA32, false) { wrapMode = TextureWrapMode.Clamp, name = "CS_Gradient" };
            for (int y = 0; y < 64; y++)
            {
                var c = Color.Lerp(bottom, top, y / 63f);
                tex.SetPixel(0, y, c);
                tex.SetPixel(1, y, c);
            }
            tex.Apply(false, true);
            return tex;
        }
    }
}
