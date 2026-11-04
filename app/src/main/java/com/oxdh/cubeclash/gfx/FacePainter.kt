package com.oxdh.cubeclash.gfx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.oxdh.cubeclash.engine.NumberFormat

/** Draws a numbered cube face (rounded panel + bold outlined number). Used for the GL atlas and for UI icons. */
object FacePainter {
    private val typeface: Typeface = Typeface.create("sans-serif-black", Typeface.BOLD) ?: Typeface.DEFAULT_BOLD
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = this@FacePainter.typeface }

    /** Draws into the square (x, y, size). [label] null draws a plain panel (used for tinted decorations). */
    @Synchronized
    fun draw(canvas: Canvas, x: Float, y: Float, size: Float, face: Int, label: String?) {
        val r = RectF(x, y, x + size, y + size)
        fill.shader = null
        fill.style = Paint.Style.FILL
        fill.color = Palette.darken(face, 0.25f)
        canvas.drawRect(r, fill)
        val inset = size * 0.06f
        val inner = RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
        fill.shader = LinearGradient(0f, inner.top, 0f, inner.bottom, Palette.lerp(face, android.graphics.Color.WHITE, 0.12f), Palette.darken(face, 0.06f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(inner, size * 0.1f, size * 0.1f, fill)
        fill.shader = null
        if (label == null) return
        val maxW = size * 0.74f
        var ts = size * 0.5f
        text.textSize = ts
        val w = text.measureText(label)
        if (w > maxW) { ts *= maxW / w; text.textSize = ts }
        val baseline = y + size / 2f - (text.descent() + text.ascent()) / 2f
        text.style = Paint.Style.STROKE
        text.strokeJoin = Paint.Join.ROUND
        text.strokeWidth = ts * 0.16f
        text.color = Palette.darken(face, 0.55f)
        canvas.drawText(label, x + size / 2f + ts * 0.03f, baseline + ts * 0.05f, text)
        text.style = Paint.Style.FILL
        text.color = Palette.textColor(face)
        canvas.drawText(label, x + size / 2f, baseline, text)
    }

    private val cache = HashMap<String, Bitmap>()

    /** Small cached bitmap of a face for UI (HUD lead cube, menus). */
    @Synchronized
    fun icon(skin: String, level: Int, px: Int): Bitmap = cache.getOrPut("$skin/$level/$px") {
        Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { draw(Canvas(it), 0f, 0f, px.toFloat(), Palette.color(skin, level), NumberFormat.formatLevel(level)) }
    }
}
