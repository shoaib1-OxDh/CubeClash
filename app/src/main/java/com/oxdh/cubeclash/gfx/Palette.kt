package com.oxdh.cubeclash.gfx

import android.graphics.Color

/**
 * Number-based colour palettes ("skins"). Colour is never the only cue: every cube always shows its number.
 * Classic: 1 blue, 2 green, 4 orange, 8 red, 16 purple, 32 cyan, 64+ progressively more distinctive.
 */
object Palette {
    private val classic = intArrayOf(0x3D8BFF, 0x35C46A, 0xFF9A2E, 0xF2464B, 0x9B5CF6, 0x22C7D6, 0xFFC928, 0xFF5FA8, 0x8BD43A, 0x1E9E8F, 0xD13BD6, 0xFF6B2C, 0x3348D6, 0x16161E)
    private val pastel = intArrayOf(0x9CC5FF, 0xA6E3B8, 0xFFD1A1, 0xFFA9AC, 0xCDB4FF, 0x9EE7EE, 0xFFE9A0, 0xFFB8D9, 0xCBEB9F, 0x8FD3C8, 0xE7A6EA, 0xFFBE9A, 0xA8B3F2, 0x8C8CA3)
    private val neon = intArrayOf(0x00E5FF, 0x39FF14, 0xFF9100, 0xFF1744, 0xD500F9, 0x00B0FF, 0xFFEA00, 0xFF4081, 0x76FF03, 0x1DE9B6, 0xE040FB, 0xFF3D00, 0x651FFF, 0xFFFFFF)
    private val sunset = intArrayOf(0xFFB26B, 0xFF8E72, 0xFF6F91, 0xD65DB1, 0x845EC2, 0x2C73D2, 0x0081CF, 0x0089BA, 0x008E9B, 0x008F7A, 0xC34A36, 0xFF8066, 0x4B4453, 0xB0A8B9)
    private val mono = intArrayOf(0xE8E8EC, 0xCFCFD6, 0xB5B5C0, 0x9B9BA8, 0x82828F, 0x6A6A78, 0x55555F, 0x44444D, 0x35353C, 0x2A2A30, 0x202025, 0x18181C, 0x111114, 0x09090B)

    private fun table(skin: String) = when (skin) { "pastel" -> pastel; "neon" -> neon; "sunset" -> sunset; "mono" -> mono; else -> classic }

    /** Opaque ARGB face colour for a level; beyond the table hues rotate so levels stay distinct. */
    fun color(skin: String, level: Int): Int {
        val t = table(skin)
        val l = level.coerceAtLeast(0)
        if (l < t.size) return 0xFF000000.toInt() or t[l]
        val hsv = floatArrayOf((l * 0.137f % 1f) * 360f, 0.75f, 0.55f + 0.1f * kotlin.math.sin(l.toFloat()))
        return Color.HSVToColor(hsv)
    }

    fun textColor(face: Int): Int {
        val lum = 0.2126f * Color.red(face) / 255f + 0.7152f * Color.green(face) / 255f + 0.0722f * Color.blue(face) / 255f
        return if (lum > 0.78f) Color.rgb(30, 30, 46) else Color.WHITE
    }

    fun trail(id: String, t: Float): Int = when (id) {
        "flame" -> lerp(Color.rgb(255, 217, 51), Color.rgb(255, 51, 13), t)
        "rainbow" -> Color.HSVToColor(floatArrayOf((t % 1f) * 360f, 0.8f, 1f))
        "ice" -> lerp(Color.WHITE, Color.rgb(89, 204, 255), t)
        else -> lerp(Color.WHITE, Color.rgb(255, 235, 128), t)
    }

    fun lerp(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())
    }

    fun darken(c: Int, k: Float) = lerp(c, Color.BLACK, k)
}
