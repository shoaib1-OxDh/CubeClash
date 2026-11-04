package com.oxdh.cubeclash.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import com.oxdh.cubeclash.GameSession
import com.oxdh.cubeclash.Settings
import com.oxdh.cubeclash.engine.NumberFormat
import com.oxdh.cubeclash.engine.Vec2
import com.oxdh.cubeclash.gfx.FacePainter
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Canvas HUD drawn over the GL view, plus all touch controls (multi-touch): floating analog joystick (lower-left),
 * boost button (lower-right), pause button, and tap-to-steer. Layout respects system bars and display cutouts.
 */
class HudView(context: Context, private val session: GameSession, private val settings: Settings, private val onPause: () -> Unit) : View(context) {
    private val d = resources.displayMetrics.density
    private val hud = session.hud
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ui.BOLD; color = Color.WHITE; setShadowLayer(3 * d, 0f, 2 * d, Color.argb(120, 0, 0, 30)) }
    private val rect = RectF()

    // Safe insets.
    private var insetL = 0f; private var insetT = 0f; private var insetR = 0f; private var insetB = 0f

    // Joystick.
    private var joyPointer = -1
    private var joyBaseX = 0f; private var joyBaseY = 0f
    private var joyX = 0f; private var joyY = 0f
    private val joyRadius get() = 62 * d * settings.joystickScale
    // Boost & steer.
    private var boostPointer = -1
    private var steerPointer = -1
    private var steerX = 0f; private var steerY = 0f
    private var pulse = 0f

    @SuppressLint("NewApi")
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val ins = rootWindowInsets
        val m = 10 * d
        if (ins != null) {
            @Suppress("DEPRECATION")
            var l = ins.systemWindowInsetLeft.toFloat(); @Suppress("DEPRECATION") var t = ins.systemWindowInsetTop.toFloat()
            @Suppress("DEPRECATION")
            var r = ins.systemWindowInsetRight.toFloat(); @Suppress("DEPRECATION") var b = ins.systemWindowInsetBottom.toFloat()
            if (Build.VERSION.SDK_INT >= 28) ins.displayCutout?.let { c ->
                l = maxOf(l, c.safeInsetLeft.toFloat()); t = maxOf(t, c.safeInsetTop.toFloat())
                r = maxOf(r, c.safeInsetRight.toFloat()); b = maxOf(b, c.safeInsetBottom.toFloat())
            }
            insetL = l + m; insetT = t + m; insetR = r + m; insetB = b + m
        } else { insetL = m; insetT = m; insetR = m; insetB = m }
    }

    private fun joyRestX() = insetL + 90 * d
    private fun joyRestY() = height - insetB - 90 * d
    private fun boostCX() = width - insetR - 70 * d
    private fun boostCY() = height - insetB - 80 * d
    private val boostR get() = 52 * d
    private fun pauseCX() = width - insetR - 28 * d
    private fun pauseCY() = insetT + 28 * d

    // ------------------------------------------------------------------ input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i); val y = e.getY(i)
                when {
                    hypot(x - pauseCX(), y - pauseCY()) < 34 * d -> { Sounds.click(); onPause() }
                    hypot(x - boostCX(), y - boostCY()) < boostR * 1.3f && boostPointer < 0 -> boostPointer = id
                    settings.controls == 0 && joyPointer < 0 && x < width * 0.55f && y > height * 0.45f -> {
                        joyPointer = id; joyBaseX = x; joyBaseY = y; joyX = x; joyY = y
                    }
                    settings.controls == 1 && steerPointer < 0 -> { steerPointer = id; steerX = x; steerY = y }
                }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                if (id == joyPointer) { joyX = e.getX(i); joyY = e.getY(i) }
                if (id == steerPointer) { steerX = e.getX(i); steerY = e.getY(i) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val all = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
                val id = e.getPointerId(e.actionIndex)
                if (all || id == joyPointer) joyPointer = -1
                if (all || id == boostPointer) boostPointer = -1
                if (all || id == steerPointer) steerPointer = -1
            }
        }
        updateInput()
        return true
    }

    /** Converts touch state into a world direction (rotated by the camera yaw) for the player brain. */
    private fun updateInput() {
        var sx = 0f; var sy = 0f; var has = false
        if (joyPointer >= 0) {
            val dx = joyX - joyBaseX; val dy = joyY - joyBaseY
            val len = hypot(dx, dy)
            if (len > joyRadius * 0.12f) { sx = dx / len; sy = -dy / len; has = true }
        } else if (steerPointer >= 0) {
            val dx = steerX - hud.headScreenX; val dy = steerY - hud.headScreenY
            val len = hypot(dx, dy)
            if (len > 20 * d) { sx = dx / len; sy = -dy / len; has = true }
        }
        val input = session.input
        input.turnSensitivity = settings.turnSensitivity
        input.boost = boostPointer >= 0 || keyboardBoost
        if (!has && keyboardDir != null) { sx = keyboardDir!!.x; sy = keyboardDir!!.y; has = true }
        if (has) {
            val yaw = hud.cameraYaw
            val c = cos(yaw); val s = sin(yaw)
            input.direction = Vec2(sx * c - sy * s, sx * s + sy * c)
        } else input.direction = null
    }

    /** Keyboard / gamepad support for emulators and Chromebooks (set by the activity). */
    var keyboardDir: Vec2? = null
    var keyboardBoost = false

    fun releaseAll() { joyPointer = -1; boostPointer = -1; steerPointer = -1; keyboardDir = null; keyboardBoost = false; updateInput() }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        updateInput()
        pulse += 0.12f
        synchronized(hud.lock) { drawHud(canvas) }
        drawControls(canvas)
    }

    private fun panel(l: Float, t: Float, r: Float, b: Float, color: Int = Color.argb(140, 20, 26, 56)) {
        paint.style = Paint.Style.FILL; paint.color = color
        rect.set(l, t, r, b); canvas?.drawRoundRect(rect, 14 * d, 14 * d, paint)
    }
    private var canvas: Canvas? = null

    private fun text(s: String, x: Float, y: Float, sizeDp: Float, color: Int, align: Paint.Align = Paint.Align.LEFT, alpha: Float = 1f) {
        textPaint.textSize = sizeDp * d; textPaint.color = color; textPaint.textAlign = align
        textPaint.alpha = (alpha * Color.alpha(color)).toInt().coerceIn(0, 255)
        canvas?.drawText(s, x, y, textPaint)
    }

    private fun drawHud(c: Canvas) {
        canvas = c
        val h = hud
        // Rival name tags & floating texts (under the panels).
        for (i in 0 until h.labelCount) text(h.labelText[i] ?: "", h.labelX[i], h.labelY[i], 13f, h.labelColor[i], Paint.Align.CENTER)
        for (i in 0 until h.ftCount) text(h.ftText[i] ?: "", h.ftX[i], h.ftY[i], 20f * h.ftSize[i], h.ftColor[i], Paint.Align.CENTER, h.ftAlpha[i])

        // Score + rank.
        val l = insetL; val t = insetT
        panel(l, t, l + 170 * d, t + 70 * d)
        text(NumberFormat.formatScore(h.score), l + 12 * d, t + 34 * d, 26f, Color.WHITE)
        text("Rank #${h.rank} / ${h.competitors}", l + 12 * d, t + 58 * d, 15f, Ui.ACCENT)

        // Leaderboard.
        if (h.lbCount > 0) {
            val top = t + 78 * d
            panel(l, top, l + 170 * d, top + (12 + h.lbCount * 19) * d, Color.argb(100, 20, 26, 56))
            for (i in 0 until h.lbCount) {
                val y = top + (24 + i * 19) * d
                val col = if (h.lbIsPlayer[i]) Ui.ACCENT else Color.WHITE
                text("${h.lbRanks[i]}. ${h.lbNames[i]}", l + 10 * d, y, 13f, col)
                text(NumberFormat.abbreviate(h.lbScores[i]), l + 160 * d, y, 13f, col, Paint.Align.RIGHT)
            }
        }

        // Leading cube + chain info (top centre).
        val icon = FacePainter.icon(h.skin, h.leadLevel, (64 * d).toInt())
        val cx = width / 2f
        c.drawBitmap(icon, cx - icon.width / 2f, t, null)
        text("Chain ${h.chainLength}   •   Best ${NumberFormat.formatLevel(h.highestLevel)}", cx, t + 64 * d + 20 * d, 14f, Color.WHITE, Paint.Align.CENTER)

        // Warnings.
        if (h.danger) {
            val a = 0.65f + 0.35f * sin(pulse * 1.5f)
            text("DANGER! BIGGER RIVAL NEARBY", cx, t + 118 * d, 18f, Ui.DANGER, Paint.Align.CENTER, a)
        }
        if (h.nearEdge) text("Edge of the arena", cx, t + 142 * d, 15f, Ui.ACCENT, Paint.Align.CENTER)

        // Pause button.
        paint.style = Paint.Style.FILL; paint.color = Color.argb(150, 20, 26, 56)
        c.drawCircle(pauseCX(), pauseCY(), 24 * d, paint)
        paint.color = Color.WHITE
        rect.set(pauseCX() - 8 * d, pauseCY() - 9 * d, pauseCX() - 3 * d, pauseCY() + 9 * d); c.drawRoundRect(rect, 2 * d, 2 * d, paint)
        rect.set(pauseCX() + 3 * d, pauseCY() - 9 * d, pauseCX() + 8 * d, pauseCY() + 9 * d); c.drawRoundRect(rect, 2 * d, 2 * d, paint)

        // Minimap.
        if (settings.showMinimap) {
            val r = 62 * d
            val mx = width - insetR - r; val my = pauseCY() + 32 * d + r
            paint.color = Color.argb(140, 15, 20, 51); c.drawCircle(mx, my, r, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * d; paint.color = Color.argb(80, 255, 255, 255)
            c.drawCircle(mx, my, r, paint)
            paint.style = Paint.Style.FILL
            val inner = r - 6 * d
            for (i in 0 until h.dotCount) {
                paint.color = h.dotColor[i]
                c.drawCircle(mx + h.dotX[i] * inner, my - h.dotY[i] * inner, 2.6f * d * h.dotSize[i], paint)
            }
            if (h.edgeVisible) { paint.color = Color.argb(230, 255, 102, 115); c.drawCircle(mx + h.edgeX * inner, my - h.edgeY * inner, 3.5f * d, paint) }
            paint.color = Color.WHITE; c.drawCircle(mx, my, 4 * d, paint)
        }

        // Boost button with energy ring.
        val bx = boostCX(); val by = boostCY()
        paint.color = Color.argb(140, 20, 26, 56); c.drawCircle(bx, by, boostR, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 8 * d; paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.argb(60, 255, 255, 255)
        rect.set(bx - boostR + 6 * d, by - boostR + 6 * d, bx + boostR - 6 * d, by + boostR - 6 * d)
        c.drawArc(rect, 0f, 360f, false, paint)
        paint.color = if (h.boosting) Color.WHITE else if (h.boostReady) Ui.ACCENT else Color.rgb(150, 150, 175)
        c.drawArc(rect, -90f, 360f * h.boost01, false, paint)
        paint.style = Paint.Style.FILL
        text("BOOST", bx, by + 6 * d, 15f, Color.WHITE, Paint.Align.CENTER)
    }

    private fun drawControls(c: Canvas) {
        if (settings.controls != 0) {
            if (steerPointer >= 0) { paint.color = Color.argb(90, 255, 255, 255); c.drawCircle(steerX, steerY, 22 * d, paint) }
            return
        }
        val r = joyRadius
        val bx = if (joyPointer >= 0) joyBaseX else joyRestX()
        val by = if (joyPointer >= 0) joyBaseY else joyRestY()
        paint.style = Paint.Style.FILL; paint.color = Color.argb(45, 255, 255, 255); c.drawCircle(bx, by, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * d; paint.color = Color.argb(90, 255, 255, 255); c.drawCircle(bx, by, r, paint)
        paint.style = Paint.Style.FILL
        var kx = bx; var ky = by
        if (joyPointer >= 0) {
            val dx = joyX - joyBaseX; val dy = joyY - joyBaseY
            val len = hypot(dx, dy)
            val k = if (len > r) r / len else 1f
            kx = bx + dx * k; ky = by + dy * k
        }
        paint.color = Color.argb(220, 255, 255, 255); c.drawCircle(kx, ky, r * 0.42f, paint)
    }
}
