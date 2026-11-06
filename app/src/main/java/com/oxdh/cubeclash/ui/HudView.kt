package com.oxdh.cubeclash.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.oxdh.cubeclash.GameSession
import com.oxdh.cubeclash.Settings
import com.oxdh.cubeclash.engine.NumberFormat
import com.oxdh.cubeclash.engine.Vec2
import com.oxdh.cubeclash.engine.net.ClientSession
import com.oxdh.cubeclash.gfx.FacePainter
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Canvas HUD drawn over the GL view, plus free multi-touch controls:
 *  - Steering: put a finger down ANYWHERE and drag; the snake turns towards the drag direction (the anchor follows
 *    long drags so turning stays instant). Alternative scheme: point to steer (towards the finger).
 *  - Boost: hold a SECOND finger anywhere (two thumbs: steer with one, boost with the other), or double-tap and
 *    hold with one finger. Boost uses an energy meter shown at the bottom.
 * Layout respects system bars and display cutouts.
 */
class HudView(
    context: Context,
    private val session: GameSession,
    private val settings: Settings,
    private val onPause: () -> Unit,
    private val onReconnect: () -> Unit = {},
) : View(context) {
    private val d = resources.displayMetrics.density
    private val hud = session.hud
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ui.BOLD; color = Color.WHITE; setShadowLayer(3 * d, 0f, 2 * d, Color.argb(120, 0, 0, 30)) }
    private val rect = RectF()
    private var canvas: Canvas? = null

    private var insetL = 0f; private var insetT = 0f; private var insetR = 0f; private var insetB = 0f

    // Steering finger.
    private var steerPointer = -1
    private var anchorX = 0f; private var anchorY = 0f
    private var fingerX = 0f; private var fingerY = 0f
    private var lastDirX = 0f; private var lastDirY = 0f; private var hasDir = false
    // Boost fingers (any extra finger) and the one-finger double-tap-and-hold gesture.
    private val boostPointers = HashSet<Int>()
    private var doubleTapBoost = false
    private var lastUpTime = 0L
    private var lastUpX = 0f; private var lastUpY = 0f
    private var pulse = 0f
    private val shownAt = SystemClock.uptimeMillis()
    private val maxDrag get() = 70 * d * settings.joystickScale
    private var reconnectRect = RectF()

    @SuppressLint("NewApi")
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val ins = rootWindowInsets
        val m = 10 * d
        if (ins != null) {
            @Suppress("DEPRECATION") var l = ins.systemWindowInsetLeft.toFloat()
            @Suppress("DEPRECATION") var t = ins.systemWindowInsetTop.toFloat()
            @Suppress("DEPRECATION") var r = ins.systemWindowInsetRight.toFloat()
            @Suppress("DEPRECATION") var b = ins.systemWindowInsetBottom.toFloat()
            if (Build.VERSION.SDK_INT >= 28) ins.displayCutout?.let { c ->
                l = maxOf(l, c.safeInsetLeft.toFloat()); t = maxOf(t, c.safeInsetTop.toFloat())
                r = maxOf(r, c.safeInsetRight.toFloat()); b = maxOf(b, c.safeInsetBottom.toFloat())
            }
            insetL = l + m; insetT = t + m; insetR = r + m; insetB = b + m
        } else { insetL = m; insetT = m; insetR = m; insetB = m }
    }

    private fun pauseCX() = width - insetR - PAUSE_R * d
    private fun pauseCY() = insetT + PAUSE_R * d

    // ------------------------------------------------------------------ input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i); val y = e.getY(i)
                when {
                    hypot(x - pauseCX(), y - pauseCY()) < 30 * d -> { Sounds.click(); onPause() }
                    reconnectRect.contains(x, y) && clientStatus() == ClientSession.Status.RECONNECTING -> { Sounds.click(); onReconnect() }
                    steerPointer < 0 -> {
                        steerPointer = id
                        anchorX = x; anchorY = y; fingerX = x; fingerY = y
                        // Double tap and hold with the same finger = boost (one-handed play).
                        val now = SystemClock.uptimeMillis()
                        doubleTapBoost = now - lastUpTime < 300 && hypot(x - lastUpX, y - lastUpY) < 80 * d
                    }
                    else -> boostPointers += id // second thumb anywhere: boost
                }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                if (e.getPointerId(i) == steerPointer) { fingerX = e.getX(i); fingerY = e.getY(i) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val all = e.actionMasked != MotionEvent.ACTION_POINTER_UP
                val id = e.getPointerId(e.actionIndex)
                if (all || id == steerPointer) {
                    if (steerPointer >= 0) { lastUpTime = SystemClock.uptimeMillis(); lastUpX = fingerX; lastUpY = fingerY }
                    steerPointer = -1; doubleTapBoost = false
                    // If a boost finger is still down, it becomes the new steering finger.
                    if (!all && boostPointers.isNotEmpty()) {
                        val next = boostPointers.first()
                        val idx = e.findPointerIndex(next)
                        if (idx >= 0) {
                            boostPointers.remove(next); steerPointer = next
                            anchorX = e.getX(idx); anchorY = e.getY(idx); fingerX = anchorX; fingerY = anchorY
                        }
                    }
                }
                if (all) boostPointers.clear() else boostPointers.remove(id)
            }
        }
        updateInput()
        return true
    }

    /** Converts touch state into a world direction (rotated by the camera yaw) for the player's brain. */
    private fun updateInput() {
        var sx = 0f; var sy = 0f; var has = false
        if (steerPointer >= 0) {
            if (settings.controls == 1) {
                val dx = fingerX - hud.headScreenX; val dy = fingerY - hud.headScreenY
                val len = hypot(dx, dy)
                if (len > 24 * d) { sx = dx / len; sy = -dy / len; has = true }
            } else {
                var dx = fingerX - anchorX; var dy = fingerY - anchorY
                var len = hypot(dx, dy)
                if (len > maxDrag) {
                    // The anchor trails the finger so a long drag in a new direction turns immediately.
                    anchorX = fingerX - dx / len * maxDrag; anchorY = fingerY - dy / len * maxDrag
                    dx = fingerX - anchorX; dy = fingerY - anchorY; len = maxDrag
                }
                if (len > 8 * d) { sx = dx / len; sy = -dy / len; has = true; lastDirX = sx; lastDirY = sy; hasDir = true }
                else if (hasDir) { sx = lastDirX; sy = lastDirY; has = true } // finger resting: keep the last direction
            }
        } else hasDir = false
        val input = session.input
        input.turnSensitivity = settings.turnSensitivity
        input.boost = boostPointers.isNotEmpty() || doubleTapBoost || keyboardBoost
        if (!has && keyboardDir != null) { sx = keyboardDir!!.x; sy = keyboardDir!!.y; has = true }
        // Light time-based smoothing of the touch direction removes finger jitter without adding noticeable lag.
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastInputMs) / 1000f).coerceIn(0f, 0.1f)
        lastInputMs = now
        if (has) {
            if (!smoothValid) { smX = sx; smY = sy; smoothValid = true }
            else {
                val k = 1f - kotlin.math.exp(-INPUT_SHARPNESS * dt)
                smX += (sx - smX) * k; smY += (sy - smY) * k
                val len = hypot(smX, smY)
                if (len > 1e-3f) { smX /= len; smY /= len } else { smX = sx; smY = sy }
            }
            sx = smX; sy = smY
        } else smoothValid = false
        if (has) {
            val yaw = hud.cameraYaw
            val c = cos(yaw); val s = sin(yaw)
            input.direction = Vec2(sx * c - sy * s, sx * s + sy * c)
        } else input.direction = null
    }

    private var smX = 0f; private var smY = 0f; private var smoothValid = false
    private var lastInputMs = SystemClock.uptimeMillis()

    var keyboardDir: Vec2? = null
    var keyboardBoost = false

    fun releaseAll() { steerPointer = -1; boostPointers.clear(); doubleTapBoost = false; keyboardDir = null; keyboardBoost = false; updateInput() }

    private fun clientStatus(): ClientSession.Status? = session.client?.status

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        updateInput()
        pulse += 0.12f
        this.canvas = canvas
        synchronized(hud.lock) { drawHud(canvas) }
        drawControls(canvas)
        drawNetworkStatus(canvas)
    }

    private fun panel(l: Float, t: Float, r: Float, b: Float, color: Int = Color.argb(140, 20, 26, 56)) {
        paint.style = Paint.Style.FILL; paint.color = color
        rect.set(l, t, r, b); canvas?.drawRoundRect(rect, 14 * d, 14 * d, paint)
    }

    private fun text(s: String, x: Float, y: Float, sizeDp: Float, color: Int, align: Paint.Align = Paint.Align.LEFT, alpha: Float = 1f) {
        textPaint.textSize = sizeDp * d; textPaint.color = color; textPaint.textAlign = align
        textPaint.alpha = (alpha * Color.alpha(color)).toInt().coerceIn(0, 255)
        canvas?.drawText(s, x, y, textPaint)
    }

    private fun drawHud(c: Canvas) {
        val h = hud
        for (i in 0 until h.labelCount) text(h.labelText[i] ?: "", h.labelX[i], h.labelY[i], 11f, h.labelColor[i], Paint.Align.CENTER)
        for (i in 0 until h.ftCount) text(h.ftText[i] ?: "", h.ftX[i], h.ftY[i], 20f * h.ftSize[i], h.ftColor[i], Paint.Align.CENTER, h.ftAlpha[i])

        val l = insetL; val t = insetT
        // Compact status card: leading cube + rank (by cube number) + score.
        val pw = 132 * d
        panel(l, t, l + pw, t + 44 * d, Color.argb(130, 20, 26, 56))
        val icon = FacePainter.icon(h.skin, h.leadLevel, (34 * d).toInt())
        c.drawBitmap(icon, l + 5 * d, t + 5 * d, null)
        text("#${h.rank}", l + 46 * d, t + 21 * d, 16f, Ui.ACCENT)
        text("/ ${h.competitors}", l + 46 * d + textWidth("#${h.rank}", 16f) + 4 * d, t + 21 * d, 11f, Color.WHITE, alpha = 0.8f)
        text("${NumberFormat.formatScore(h.score)} pts  •  ${h.chainLength} cubes", l + 46 * d, t + 37 * d, 9.5f, Color.WHITE, alpha = 0.85f)

        if (h.lbCount > 0) {
            val top = t + 48 * d
            val row = 14 * d
            panel(l, top, l + pw, top + 6 * d + h.lbCount * row, Color.argb(95, 20, 26, 56))
            for (i in 0 until h.lbCount) {
                val y = top + 15 * d + i * row
                val col = if (h.lbIsPlayer[i]) Ui.ACCENT else if (h.lbIsHuman[i]) Color.rgb(140, 220, 255) else Color.WHITE
                text("${h.lbRanks[i]}. ${ellipsize(h.lbNames[i] ?: "", 82 * d, 10.5f)}", l + 7 * d, y, 10.5f, col)
                text(NumberFormat.formatLevel(h.lbLevels[i]), l + pw - 7 * d, y, 10.5f, col, Paint.Align.RIGHT)
            }
        }

        session.roomLabel?.let { text("$it  •  ${h.peopleCount} ${if (h.peopleCount == 1) "player" else "players"}", width / 2f, t + 10 * d, 10f, Color.WHITE, Paint.Align.CENTER, 0.85f) }

        val wy = t + 34 * d
        if (h.danger) text("DANGER! BIGGER SNAKE NEARBY", width / 2f, wy, 14f, Ui.DANGER, Paint.Align.CENTER, 0.65f + 0.35f * sin(pulse * 1.5f))
        if (h.nearEdge) text("Edge of the arena", width / 2f, wy + 18 * d, 12f, Ui.ACCENT, Paint.Align.CENTER)
        if (!h.alive && session.isMultiplayer) {
            text(if (h.eatenBy != null) "Eaten by ${h.eatenBy}" else "You were eaten", width / 2f, height * 0.42f, 22f, Color.WHITE, Paint.Align.CENTER)
            if (h.respawnIn > 0f) text("Respawning in ${kotlin.math.ceil(h.respawnIn).toInt()}…", width / 2f, height * 0.42f + 28 * d, 16f, Ui.ACCENT, Paint.Align.CENTER)
        }

        // Pause.
        paint.style = Paint.Style.FILL; paint.color = Color.argb(150, 20, 26, 56)
        c.drawCircle(pauseCX(), pauseCY(), PAUSE_R * d, paint)
        paint.color = Color.WHITE
        rect.set(pauseCX() - 6 * d, pauseCY() - 7 * d, pauseCX() - 2 * d, pauseCY() + 7 * d); c.drawRoundRect(rect, 1.5f * d, 1.5f * d, paint)
        rect.set(pauseCX() + 2 * d, pauseCY() - 7 * d, pauseCX() + 6 * d, pauseCY() + 7 * d); c.drawRoundRect(rect, 1.5f * d, 1.5f * d, paint)

        if (settings.showMinimap) {
            // Minimap sits to the left of the pause button so both stay in the top-right corner.
            val r = MAP_R * d
            val mx = pauseCX() - PAUSE_R * d - 8 * d - r; val my = insetT + r
            paint.color = Color.argb(130, 15, 20, 51); c.drawCircle(mx, my, r, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * d; paint.color = Color.argb(80, 255, 255, 255)
            c.drawCircle(mx, my, r, paint)
            paint.style = Paint.Style.FILL
            val inner = r - 4 * d
            for (i in 0 until h.dotCount) { paint.color = h.dotColor[i]; c.drawCircle(mx + h.dotX[i] * inner, my - h.dotY[i] * inner, 2f * d * h.dotSize[i], paint) }
            if (h.edgeVisible) { paint.color = Color.argb(230, 255, 102, 115); c.drawCircle(mx + h.edgeX * inner, my - h.edgeY * inner, 2.8f * d, paint) }
            paint.color = Color.WHITE; c.drawCircle(mx, my, 3 * d, paint)
        }

        // Boost energy meter (bottom centre) — boost itself is a second finger anywhere.
        val bw = 110 * d; val bh = 6 * d
        val bx = width / 2f - bw / 2f; val by = height - insetB - 8 * d
        paint.color = Color.argb(110, 20, 26, 56); rect.set(bx - 3 * d, by - 3 * d, bx + bw + 3 * d, by + bh + 3 * d); c.drawRoundRect(rect, 6 * d, 6 * d, paint)
        paint.color = if (h.boosting) Color.WHITE else if (h.boostReady) Ui.ACCENT else Color.rgb(150, 150, 175)
        rect.set(bx, by, bx + bw * h.boost01.coerceIn(0f, 1f), by + bh); c.drawRoundRect(rect, 3 * d, 3 * d, paint)
        if (h.boosting) text("BOOST!", width / 2f, by - 6 * d, 10f, Color.WHITE, Paint.Align.CENTER)
    }

    private fun textWidth(s: String, sizeDp: Float): Float { textPaint.textSize = sizeDp * d; return textPaint.measureText(s) }

    private fun ellipsize(s: String, maxW: Float, sizeDp: Float): String {
        if (textWidth(s, sizeDp) <= maxW) return s
        var n = s.length
        while (n > 1 && textWidth(s.take(n) + "…", sizeDp) > maxW) n--
        return s.take(n) + "…"
    }

    private fun drawControls(c: Canvas) {
        // A short hint at the start of every run.
        val age = (SystemClock.uptimeMillis() - shownAt) / 1000f
        if (age < 7f) {
            val a = if (age > 5.5f) (7f - age) / 1.5f else 1f
            val line = if (settings.controls == 1) "Touch where you want to go" else "Drag anywhere to steer"
            text(line, width / 2f, height * 0.72f, 14f, Color.WHITE, Paint.Align.CENTER, a)
            text("Second finger (or double-tap & hold) = BOOST", width / 2f, height * 0.72f + 19 * d, 12f, Ui.ACCENT, Paint.Align.CENTER, a)
        }
        if (steerPointer < 0) return
        paint.style = Paint.Style.FILL
        if (settings.controls == 1) { paint.color = Color.argb(90, 255, 255, 255); c.drawCircle(fingerX, fingerY, 22 * d, paint); return }
        val r = maxDrag
        paint.color = Color.argb(35, 255, 255, 255); c.drawCircle(anchorX, anchorY, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * d; paint.color = Color.argb(80, 255, 255, 255); c.drawCircle(anchorX, anchorY, r, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (session.input.boost) Color.argb(220, 255, 200, 80) else Color.argb(200, 255, 255, 255)
        c.drawCircle(fingerX, fingerY, r * 0.38f, paint)
    }

    private fun drawNetworkStatus(c: Canvas) {
        val client = session.client ?: return
        val st = client.status
        if (st == ClientSession.Status.PLAYING) return
        paint.style = Paint.Style.FILL; paint.color = Color.argb(170, 8, 10, 26)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        canvas = c
        val msg = when (st) {
            ClientSession.Status.CONNECTING -> "Connecting to ${session.roomLabel ?: "room"}…"
            ClientSession.Status.RECONNECTING -> client.statusMessage
            else -> client.statusMessage
        }
        text(msg, width / 2f, height * 0.42f, 18f, Color.WHITE, Paint.Align.CENTER)
        if (st == ClientSession.Status.RECONNECTING) {
            val w = 180 * d; val hh = 48 * d
            reconnectRect.set(width / 2f - w / 2, height * 0.42f + 24 * d, width / 2f + w / 2, height * 0.42f + 24 * d + hh)
            paint.color = Ui.PRIMARY; c.drawRoundRect(reconnectRect, 14 * d, 14 * d, paint)
            text("Reconnect now", width / 2f, reconnectRect.centerY() + 6 * d, 16f, Color.WHITE, Paint.Align.CENTER)
        } else reconnectRect.setEmpty()
        if (st == ClientSession.Status.CLOSED) text("Open the menu (II) to leave", width / 2f, height * 0.42f + 32 * d, 14f, Ui.TEXT_DIM, Paint.Align.CENTER)
    }

    private companion object {
        const val PAUSE_R = 19f
        const val MAP_R = 44f
        /** Touch direction smoothing (1/s): higher = snappier, lower = smoother. */
        const val INPUT_SHARPNESS = 26f
    }
}
