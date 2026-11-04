using UnityEngine;

namespace CubeSnake.Visual
{
    /// <summary>
    /// Number-based colour palettes ("skins"). Colours are never the only cue: every cube always shows its number.
    /// Classic: 1 blue, 2 green, 4 orange, 8 red, 16 purple, 32 cyan, 64+ progressively more distinctive.
    /// </summary>
    public static class CubePalette
    {
        static readonly Color[] Classic =
        {
            Hex(0x3D8BFF), // 1 blue
            Hex(0x35C46A), // 2 green
            Hex(0xFF9A2E), // 4 orange
            Hex(0xF2464B), // 8 red
            Hex(0x9B5CF6), // 16 purple
            Hex(0x22C7D6), // 32 cyan
            Hex(0xFFC928), // 64 gold
            Hex(0xFF5FA8), // 128 pink
            Hex(0x8BD43A), // 256 lime
            Hex(0x1E9E8F), // 512 teal
            Hex(0xD13BD6), // 1024 magenta
            Hex(0xFF6B2C), // 2048 flame
            Hex(0x3348D6), // 4096 indigo
            Hex(0x16161E), // 8192 obsidian
        };

        static readonly Color[] Pastel =
        {
            Hex(0x9CC5FF), Hex(0xA6E3B8), Hex(0xFFD1A1), Hex(0xFFA9AC), Hex(0xCDB4FF), Hex(0x9EE7EE), Hex(0xFFE9A0),
            Hex(0xFFB8D9), Hex(0xCBEB9F), Hex(0x8FD3C8), Hex(0xE7A6EA), Hex(0xFFBE9A), Hex(0xA8B3F2), Hex(0x8C8CA3),
        };

        static readonly Color[] Neon =
        {
            Hex(0x00E5FF), Hex(0x39FF14), Hex(0xFF9100), Hex(0xFF1744), Hex(0xD500F9), Hex(0x00B0FF), Hex(0xFFEA00),
            Hex(0xFF4081), Hex(0x76FF03), Hex(0x1DE9B6), Hex(0xE040FB), Hex(0xFF3D00), Hex(0x651FFF), Hex(0xFFFFFF),
        };

        static readonly Color[] Sunset =
        {
            Hex(0xFFB26B), Hex(0xFF8E72), Hex(0xFF6F91), Hex(0xD65DB1), Hex(0x845EC2), Hex(0x2C73D2), Hex(0x0081CF),
            Hex(0x0089BA), Hex(0x008E9B), Hex(0x008F7A), Hex(0xC34A36), Hex(0xFF8066), Hex(0x4B4453), Hex(0xB0A8B9),
        };

        static readonly Color[] Mono =
        {
            Hex(0xE8E8EC), Hex(0xCFCFD6), Hex(0xB5B5C0), Hex(0x9B9BA8), Hex(0x82828F), Hex(0x6A6A78), Hex(0x55555F),
            Hex(0x44444D), Hex(0x35353C), Hex(0x2A2A30), Hex(0x202025), Hex(0x18181C), Hex(0x111114), Hex(0x09090B),
        };

        static Color Hex(int rgb) => new Color(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, 1f);

        static Color[] For(string skin)
        {
            switch (skin)
            {
                case "pastel": return Pastel;
                case "neon": return Neon;
                case "sunset": return Sunset;
                case "mono": return Mono;
                default: return Classic;
            }
        }

        /// <summary>Face colour for a level. Beyond the table, hues rotate so every level stays distinct.</summary>
        public static Color ColorFor(string skin, int level)
        {
            var table = For(skin);
            if (level < 0) level = 0;
            if (level < table.Length) return table[level];
            float hue = (level * 0.137f) % 1f;
            float sat = 0.75f, val = 0.55f + 0.1f * Mathf.Sin(level);
            return Color.HSVToRGB(hue, sat, val);
        }

        /// <summary>Text colour with good contrast against the face colour.</summary>
        public static Color TextColorFor(Color face)
        {
            float lum = 0.2126f * face.r + 0.7152f * face.g + 0.0722f * face.b;
            return lum > 0.78f ? new Color(0.12f, 0.12f, 0.18f) : Color.white;
        }

        public static Color TrailColor(string trailId, float t)
        {
            switch (trailId)
            {
                case "flame": return Color.Lerp(new Color(1f, 0.85f, 0.2f), new Color(1f, 0.2f, 0.05f), t);
                case "rainbow": return Color.HSVToRGB(t % 1f, 0.8f, 1f);
                case "ice": return Color.Lerp(Color.white, new Color(0.35f, 0.8f, 1f), t);
                default: return Color.Lerp(Color.white, new Color(1f, 0.92f, 0.5f), t);
            }
        }
    }
}
