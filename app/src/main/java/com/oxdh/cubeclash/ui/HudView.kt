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
        if (has) {
            val yaw = hud.cameraYaw
            val c = cos(yaw); val s = sin(yaw)
            input.direction = Vec2(sx * c - sy * s, sx * s + sy * c)
        } else input.direction = null
    }

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
        for (i in 0 until h.labelCount) text(h.labelText[i] ?: "", h.labelX[i], h.labelY[i], 13f, h.labelColor[i], Paint.Align.CENTER)
        for (i in 0 until h.ftCount) text(h.ftText[i] ?: "", h.ftX[i], h.ftY[i], 20f * h.ftSize[i], h.ftColor[i], Paint.Align.CENTER, h.ftAlpha[i])

        val l = insetL; val t = insetT
        panel(l, t, l + 170 * d, t + 90 * d)
        text(NumberFormat.formatScore(h.score), l + 12 * d, t + 34 * d, 26f, Color.WHITE)
        text("Rank #${h.rank} / ${h.competitors}", l + 12 * d, t + 58 * d, 15f, Ui.ACCENT)
        text("Chain ${h.chainLength}  •  Best ${NumberFormat.formatLevel(h.highestLevel)}", l + 12 * d, t + 80 * d, 13f, Color.WHITE)

        if (h.lbCount > 0) {
            val top = t + 98 * d
            panel(l, top, l + 170 * d, top + (12 + h.lbCount * 19) * d, Color.argb(100, 20, 26, 56))
            for (i in 0 until h.lbCount) {
                val y = top + (24 + i * 19) * d
                val col = if (h.lbIsPlayer[i]) Ui.ACCENT else if (h.lbIsHuman[i]) Color.rgb(140, 220, 255) else Color.WHITE
                text("${h.lbRanks[i]}. ${h.lbNames[i]}", l + 10 * d, y, 13f, col)
                text(NumberFormat.abbreviate(h.lbScores[i]), l + 160 * d, y, 13f, col, Paint.Align.RIGHT)
            }
        }

        // Leading cube between the score panel and the minimap.
        val icon = FacePainter.icon(h.skin, h.leadLevel, (60 * d).toInt())
        val leftEdge = l + 170 * d
        val rightEdge = width - insetR - (if (settings.showMinimap) 124 * d else 56 * d)
        val cx = if (rightEdge - leftEdge > icon.width + 8 * d) (leftEdge + rightEdge) / 2f else width / 2f
        c.drawBitmap(icon, cx - icon.width / 2f, t, null)
        session.roomLabel?.let { text("$it  •  ${h.peopleCount} ${if (h.peopleCount == 1) "player" else "players"}", cx, t + 78 * d, 11f, Color.WHITE, Paint.Align.CENTER, 0.9f) }

        val wy = t + 230 * d
        if (h.danger) text("DANGER! BIGGER SNAKE NEARBY", width / 2f, wy, 18f, Ui.DANGER, Paint.Align.CENTER, 0.65f + 0.35f * sin(pulse * 1.5f))
        if (h.nearEdge) text("Edge of the arena", width / 2f, wy + 24 * d, 15f, Ui.ACCENT, Paint.Align.CENTER)
        if (!h.alive && session.isMultiplayer) {
            text(if (h.eatenBy != null) "Eaten by ${h.eatenBy}" else "You were eaten", width / 2f, height * 0.42f, 24f, Color.WHITE, Paint.Align.CENTER)
            if (h.respawnIn > 0f) text("Respawning in ${kotlin.math.ceil(h.respawnIn).toInt()}…", width / 2f, height * 0.42f + 32 * d, 18f, Ui.ACCENT, Paint.Align.CENTER)
        }

        // Pause.
        paint.style = Paint.Style.FILL; paint.color = Color.argb(150, 20, 26, 56)
        c.drawCircle(pauseCX(), pauseCY(), 24 * d, paint)
        paint.color = Color.WHITE
        rect.set(pauseCX() - 8 * d, pauseCY() - 9 * d, pauseCX() - 3 * d, pauseCY() + 9 * d); c.drawRoundRect(rect, 2 * d, 2 * d, paint)
        rect.set(pauseCX() + 3 * d, pauseCY() - 9 * d, pauseCX() + 8 * d, pauseCY() + 9 * d); c.drawRoundRect(rect, 2 * d, 2 * d, paint)

        if (settings.showMinimap) {
            val r = 62 * d
            val mx = width - insetR - r; val my = pauseCY() + 32 * d + r
            paint.color = Color.argb(140, 15, 20, 51); c.drawCircle(mx, my, r, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * d; paint.color = Color.argb(80, 255, 255, 255)
            c.drawCircle(mx, my, r, paint)
            paint.style = Paint.Style.FILL
            val inner = r - 6 * d
            for (i in 0 until h.dotCount) { paint.color = h.dotColor[i]; c.drawCircle(mx + h.dotX[i] * inner, my - h.dotY[i] * inner, 2.6f * d * h.dotSize[i], paint) }
            if (h.edgeVisible) { paint.color = Color.argb(230, 255, 102, 115); c.drawCircle(mx + h.edgeX * inner, my - h.edgeY * inner, 3.5f * d, paint) }
            paint.color = Color.WHITE; c.drawCircle(mx, my, 4 * d, paint)
        }

        // Boost energy meter (bottom centre) — boost itself is a second finger anywhere.
        val bw = 150 * d; val bh = 10 * d
        val bx = width / 2f - bw / 2f; val by = height - insetB - 18 * d
        paint.color = Color.argb(110, 20, 26, 56); rect.set(bx - 4 * d, by - 4 * d, bx + bw + 4 * d, by + bh + 4 * d); c.drawRoundRect(rect, 9 * d, 9 * d, paint)
        paint.color = if (h.boosting) Color.WHITE else if (h.boostReady) Ui.ACCENT else Color.rgb(150, 150, 175)
        rect.set(bx, by, bx + bw * h.boost01.coerceIn(0f, 1f), by + bh); c.drawRoundRect(rect, 5 * d, 5 * d, paint)
        text(if (h.boosting) "BOOST!" else "BOOST", width / 2f, by - 8 * d, 12f, if (h.boosting) Color.WHITE else Color.argb(220, 255, 255, 255), Paint.Align.CENTER)
    }

    private fun drawControls(c: Canvas) {
        // A short hint at the start of every run.
        val age = (SystemClock.uptimeMillis() - shownAt) / 1000f
        if (age < 7f) {
            val a = if (age > 5.5f) (7f - age) / 1.5f else 1f
            val line = if (settings.controls == 1) "Touch where you want to go" else "Drag anywhere to steer"
            text(line, width / 2f, height * 0.7f, 17f, Color.WHITE, Paint.Align.CENTER, a)
            text("Second finger (or double-tap & hold) = BOOST", width / 2f, height * 0.7f + 24 * d, 14f, Ui.ACCENT, Paint.Align.CENTER, a)
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
}
