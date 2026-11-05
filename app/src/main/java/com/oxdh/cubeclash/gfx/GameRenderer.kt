package com.oxdh.cubeclash.gfx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import com.oxdh.cubeclash.GameSession
import com.oxdh.cubeclash.Settings
import com.oxdh.cubeclash.Sfx
import com.oxdh.cubeclash.Sound
import com.oxdh.cubeclash.engine.Chain
import com.oxdh.cubeclash.engine.Collectible
import com.oxdh.cubeclash.engine.CombatResolver
import com.oxdh.cubeclash.engine.NumberFormat
import com.oxdh.cubeclash.engine.RankCalculator
import com.oxdh.cubeclash.engine.Rng
import com.oxdh.cubeclash.engine.Vec2
import com.oxdh.cubeclash.engine.WorldEvent
import com.oxdh.cubeclash.ui.HudState
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Steps the [GameSession.world] and renders it with OpenGL ES 3.0: instanced rounded cubes with a number atlas,
 * a procedural grid ground with the arena boundary, blob shadows, team rings, glow halos and particles. Also
 * fills [HudState] for the Canvas HUD and turns world events into sound, particles and floating text.
 */
class GameRenderer(
    private val session: GameSession,
    private val settings: Settings,
    private val sound: Sound,
    private val listener: Listener,
) : GLSurfaceView.Renderer {

    interface Listener {
        fun requestHudRedraw()
        /** Called on the GL thread once the run is over (after the short slow-motion). */
        fun onRunOver(defeated: Boolean)
    }

    private val world get() = session.world
    private val hud = session.hud

    // GL objects.
    private var cubeProg = 0; private var groundProg = 0; private var spriteProg = 0
    private var cubeVao = 0; private var groundVao = 0; private var spriteVao = 0
    private var cubeInstVbo = 0; private var spriteInstVbo = 0
    private var atlasTex = 0
    private var indexCount = 0
    private val cubeInst: FloatBuffer = GlUtil.floatBuffer(MAX_CUBES * CUBE_FLOATS)
    private val spriteInst: FloatBuffer = GlUtil.floatBuffer(MAX_SPRITES * SPRITE_FLOATS)
    private var cubeCount = 0
    private var spriteCount = 0

    // Camera.
    private val proj = FloatArray(16); private val view = FloatArray(16); private val vp = FloatArray(16)
    private var width = 1; private var height = 1
    private var camX = 0f; private var camY = 20f; private var camZ = 15f
    private var focusX = 0f; private var focusZ = 0f
    private var yaw = 0f
    private var zoom = 1f
    private var shake = 0f
    private var camInit = false
    private val camRight = FloatArray(3); private val camUp = FloatArray(3)
    private val light = floatArrayOf(0.35f, 0.85f, 0.4f).also { val l = sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]); for (i in 0..2) it[i] /= l }

    // Timing.
    private var lastNanos = 0L
    private var realTime = 0f
    private var slowMoUntil = -1f
    private var runOverSent = false

    // Decoration.
    private class Deco(val x: Float, val y: Float, val z: Float, val size: Float, val color: Int, val spin: Float, val phase: Float)
    private val decorations = ArrayList<Deco>()

    // Particles (struct of arrays).
    private val pX = FloatArray(MAX_PARTICLES); private val pY = FloatArray(MAX_PARTICLES); private val pZ = FloatArray(MAX_PARTICLES)
    private val pVX = FloatArray(MAX_PARTICLES); private val pVY = FloatArray(MAX_PARTICLES); private val pVZ = FloatArray(MAX_PARTICLES)
    private val pLife = FloatArray(MAX_PARTICLES); private val pMax = FloatArray(MAX_PARTICLES); private val pSize = FloatArray(MAX_PARTICLES)
    private val pCol = IntArray(MAX_PARTICLES); private val pGrav = FloatArray(MAX_PARTICLES)
    private var pNext = 0
    private val fxRng = Rng(99)

    // Floating texts.
    private class Floating(var x: Float, var y: Float, var z: Float, var text: String, var color: Int, var age: Float, var life: Float, var size: Float)
    private val floating = ArrayList<Floating>()

    // HUD bookkeeping.
    private var leaderboardTimer = 0f
    private var minimapTimer = 0f
    private var lastDangerSound = -10f
    private var wasDanger = false
    private val labelCache = HashMap<Int, Pair<Int, String>>()
    private val sortedScratch = ArrayList<Chain>()
    private val scoreScratch = ArrayList<Long>()
    private val colScratch = ArrayList<Collectible>(128)
    private val clip = FloatArray(4); private val world4 = FloatArray(4)

    // ------------------------------------------------------------------ GL lifecycle

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        cubeProg = GlUtil.program(Shaders.CUBE_VS, Shaders.CUBE_FS)
        groundProg = GlUtil.program(Shaders.GROUND_VS, Shaders.GROUND_FS)
        spriteProg = GlUtil.program(Shaders.SPRITE_VS, Shaders.SPRITE_FS)

        // Cube mesh + instance buffer.
        cubeVao = GlUtil.genVao()
        GLES30.glBindVertexArray(cubeVao)
        val vbo = GlUtil.genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, CubeMesh.vertices.size * 4, GlUtil.floatBuffer(CubeMesh.vertices), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 32, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 32, 12)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 32, 24)
        val ibo = GlUtil.genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, CubeMesh.indices.size * 2, GlUtil.shortBuffer(CubeMesh.indices), GLES30.GL_STATIC_DRAW)
        indexCount = CubeMesh.indices.size
        cubeInstVbo = GlUtil.genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cubeInstVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, MAX_CUBES * CUBE_FLOATS * 4, null, GLES30.GL_DYNAMIC_DRAW)
        for (i in 0..2) {
            GLES30.glEnableVertexAttribArray(3 + i)
            GLES30.glVertexAttribPointer(3 + i, 4, GLES30.GL_FLOAT, false, CUBE_FLOATS * 4, i * 16)
            GLES30.glVertexAttribDivisor(3 + i, 1)
        }
        GLES30.glBindVertexArray(0)

        // Ground quad.
        groundVao = GlUtil.genVao()
        GLES30.glBindVertexArray(groundVao)
        val g = world.arenaRadius + 200f
        val gvbo = GlUtil.genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gvbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 8 * 4, GlUtil.floatBuffer(floatArrayOf(-g, -g, g, -g, -g, g, g, g)), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindVertexArray(0)

        // Sprite quad + instances.
        spriteVao = GlUtil.genVao()
        GLES30.glBindVertexArray(spriteVao)
        val svbo = GlUtil.genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, svbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 8 * 4, GlUtil.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        spriteInstVbo = GlUtil.genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, spriteInstVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, MAX_SPRITES * SPRITE_FLOATS * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, SPRITE_FLOATS * 4, 0); GLES30.glVertexAttribDivisor(1, 1)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, SPRITE_FLOATS * 4, 16); GLES30.glVertexAttribDivisor(2, 1)
        GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, SPRITE_FLOATS * 4, 32); GLES30.glVertexAttribDivisor(3, 1)
        GLES30.glBindVertexArray(0)

        atlasTex = buildAtlas(session.localSkin)
        if (decorations.isEmpty()) buildDecorations()
        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = max(1, w); height = max(1, h)
        GLES30.glViewport(0, 0, width, height)
        Matrix.perspectiveM(proj, 0, 55f, width.toFloat() / height, 0.3f, 420f)
    }

    /** Atlas of numbered faces: cells 0..63 classic skin, 64..127 player skin, 128 plain panel (tinted per instance). */
    private fun buildAtlas(playerSkin: String): Int {
        val bmp = Bitmap.createBitmap(ATLAS_COLS * CELL, ATLAS_ROWS * CELL, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        for (i in 0 until 64) {
            draw(canvas, i, "classic", i)
            draw(canvas, 64 + i, playerSkin, i)
        }
        val px = (PLAIN_CELL % ATLAS_COLS) * CELL.toFloat(); val py = (PLAIN_CELL / ATLAS_COLS) * CELL.toFloat()
        FacePainter.draw(canvas, px, py, CELL.toFloat(), Color.WHITE, null)
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        bmp.recycle()
        return t[0]
    }

    private fun draw(canvas: Canvas, cell: Int, skin: String, level: Int) {
        val x = (cell % ATLAS_COLS) * CELL.toFloat(); val y = (cell / ATLAS_COLS) * CELL.toFloat()
        FacePainter.draw(canvas, x, y, CELL.toFloat(), Palette.color(skin, level), NumberFormat.formatLevel(level))
    }

    private fun buildDecorations() {
        val r = Rng(4242)
        val colors = intArrayOf(Color.rgb(255, 199, 89), Color.rgb(140, 217, 255), Color.rgb(255, 140, 178), Color.rgb(166, 242, 166))
        repeat(28) { i ->
            val a = r.nextFloat() * PI.toFloat() * 2f
            val d = world.arenaRadius + r.range(30f, 110f)
            decorations += Deco(cos(a) * d, r.range(4f, 26f), sin(a) * d, r.range(5f, 16f), colors[i % colors.size], r.range(-12f, 12f), r.nextFloat() * 6f)
        }
    }

    // ------------------------------------------------------------------ frame

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        var dt = if (lastNanos == 0L) 1f / 60f else ((now - lastNanos) / 1e9f)
        lastNanos = now
        dt = dt.coerceIn(0f, 0.05f)
        realTime += dt

        if (session.endRequested && !world.playerFrozen) {
            world.playerFrozen = true
            finishRun(false)
        }

        if (!session.paused || session.isMultiplayer) {
            val scale = if (realTime < slowMoUntil) 0.35f else 1f
            session.tick(dt * scale)
            handleEvents()
            if (!session.isMultiplayer && world.playerDefeated && !runOverSent && slowMoUntil < 0f) slowMoUntil = realTime + 1.4f
            if (slowMoUntil > 0f && realTime >= slowMoUntil && !runOverSent) finishRun(true)
            updateParticles(dt * scale)
        }
        updateCamera(dt)
        render()
        updateHud(dt)
        listener.requestHudRedraw()

        // 30 FPS mode: sleep the rest of the frame.
        if (settings.targetFps == 30) {
            val spent = (System.nanoTime() - now) / 1_000_000L
            val wait = 33L - spent
            if (wait > 1) try { Thread.sleep(wait) } catch (_: InterruptedException) {}
        }
    }

    private fun finishRun(defeated: Boolean) {
        if (runOverSent) return
        runOverSent = true
        listener.onRunOver(defeated)
    }

    // ------------------------------------------------------------------ events → feedback

    private fun isPlayer(c: Chain) = c === world.player

    private fun handleEvents() {
        val low = settings.lowEffects
        for (e in world.events) when (e) {
            is WorldEvent.Collected -> {
                burst(e.x, 0.5f, e.y, Palette.color("classic", e.level), if (low) 4 else 8, 3f, 0.45f, 0.4f)
                if (isPlayer(e.chain)) sound.collect(e.level)
            }
            is WorldEvent.Merged -> {
                val c = Palette.color(e.chain.skin, e.level)
                burst(e.x, 0.7f, e.y, Palette.lerp(c, Color.WHITE, 0.35f), (if (low) 9 else 18) + min(30, e.level * 2), 5f, 0.5f, 0.5f)
                if (isPlayer(e.chain)) {
                    sound.merge(e.level)
                    if (e.level >= 5) shake = min(1f, shake + (0.05f * (e.level - 4)).coerceIn(0.05f, 0.4f))
                }
            }
            is WorldEvent.ScoreGained -> if (isPlayer(e.chain)) addFloating("+" + NumberFormat.abbreviate(e.points), e.x, 1.6f, e.y, 0xFFFFC23D.toInt(), 0.9f, 1f)
            is WorldEvent.Defeated -> {
                burst(e.x, 0.7f, e.y, Color.WHITE, if (low) 20 else 40, 7f, 0.8f, 0.6f)
                burst(e.x, 0.7f, e.y, Palette.color(e.victim.skin, e.topLevel), if (low) 15 else 30, 9f, 1f, 0.45f, gravity = 9f)
                when {
                    isPlayer(e.victim) -> {
                        sound.play(Sfx.PLAYER_DEFEAT); shake = 0.8f
                        if (session.isMultiplayer) addFloating(if (e.killer != null) "Eaten by ${e.killer!!.name}!" else "Eaten!", e.x, 3f, e.y, 0xFFFF6B78.toInt(), 2.2f, 1.4f)
                    }
                    e.killer != null && isPlayer(e.killer!!) -> {
                        sound.play(Sfx.OPPONENT_DEFEAT)
                        addFloating("Defeated ${e.victim.name}!", e.x, 3f, e.y, 0xFF7CF29A.toInt(), 1.6f, 1.4f)
                        shake = min(1f, shake + 0.25f)
                    }
                }
            }
            is WorldEvent.Severed -> {
                burst(e.x, 0.6f, e.y, Color.WHITE, 24, 6f, 0.6f, 0.5f)
                if (isPlayer(e.victim) || isPlayer(e.attacker)) sound.play(Sfx.OPPONENT_DEFEAT, if (isPlayer(e.victim)) 0.8f else 1.2f, 0.7f)
                if (isPlayer(e.victim)) addFloating("Tail lost!", e.x, 3f, e.y, 0xFFFF6B78.toInt(), 1.4f, 1.3f)
            }
            is WorldEvent.Bounced -> if (isPlayer(e.a) || isPlayer(e.b)) sound.play(Sfx.CLICK, 0.6f, 0.7f)
            is WorldEvent.BoostStarted -> if (isPlayer(e.chain)) sound.play(Sfx.BOOST, 1f, 0.7f)
            is WorldEvent.Blocked -> if (isPlayer(e.chain)) {
                // Solid contact: a dull thud, a puff of dust and a small camera nudge.
                sound.play(Sfx.CLICK, 0.55f, 0.8f)
                burst(e.x, 0.3f, e.y, Color.rgb(210, 215, 235), if (low) 4 else 8, 2.5f, 0.35f, 0.35f)
                shake = min(1f, shake + 0.08f)
            }
            is WorldEvent.Respawned -> if (isPlayer(e.chain)) addFloating("Back in the game!", e.chain.headX, 3f, e.chain.headY, Color.WHITE, 1.6f, 1.3f)
        }
        // Boost trail sparkles.
        for (c in world.chains) if (c.alive && c.boost.isBoosting && c.segments.isNotEmpty() && !low) {
            val s = c.segments.last()
            emit(s.x + fxRng.range(-0.3f, 0.3f), 0.3f, s.y + fxRng.range(-0.3f, 0.3f), Palette.trail(c.trailId, fxRng.nextFloat()), 0f, 0.8f, 0f, 0.5f, 0.35f, 0f)
        }
    }

    /** Spawns [count] particles at engine position (x, z=engineY) with the given height [y]. */
    private fun burst(x: Float, y: Float, engineY: Float, color: Int, count: Int, speed: Float, life: Float, size: Float, gravity: Float = 2f) {
        if (!onScreen(x, y, -engineY)) return
        repeat(count) {
            val a = fxRng.nextFloat() * PI.toFloat() * 2f
            val up = fxRng.range(0.3f, 1f)
            val s = speed * fxRng.range(0.4f, 1f)
            emit(x, y, engineY, color, cos(a) * s * (1 - up * 0.5f), up * s, sin(a) * s * (1 - up * 0.5f), life * fxRng.range(0.6f, 1f), size * fxRng.range(0.6f, 1.2f), gravity)
        }
    }

    private fun emit(x: Float, y: Float, engineY: Float, color: Int, vx: Float, vy: Float, vEngineY: Float, life: Float, size: Float, gravity: Float) {
        val i = pNext
        pNext = (pNext + 1) % MAX_PARTICLES
        pX[i] = x; pY[i] = y; pZ[i] = -engineY
        pVX[i] = vx; pVY[i] = vy; pVZ[i] = -vEngineY
        pLife[i] = life; pMax[i] = life; pSize[i] = size; pCol[i] = color; pGrav[i] = gravity
    }

    private fun updateParticles(dt: Float) {
        for (i in 0 until MAX_PARTICLES) {
            if (pLife[i] <= 0f) continue
            pLife[i] -= dt
            pVY[i] -= pGrav[i] * dt
            pX[i] += pVX[i] * dt; pY[i] += pVY[i] * dt; pZ[i] += pVZ[i] * dt
            if (pY[i] < 0.05f) { pY[i] = 0.05f; pVY[i] = -pVY[i] * 0.3f }
        }
        val it = floating.iterator()
        while (it.hasNext()) { val f = it.next(); f.age += dt; if (f.age >= f.life) it.remove() }
    }

    private fun addFloating(text: String, x: Float, y: Float, engineY: Float, color: Int, life: Float, size: Float) {
        if (floating.size >= HudState.MAX_FLOAT) floating.removeAt(0)
        floating += Floating(x, y, -engineY, text, color, 0f, life, size)
    }

    // ------------------------------------------------------------------ camera

    private fun updateCamera(dt: Float) {
        val p = world.player
        val head = p.segments.firstOrNull()
        val hx = head?.x ?: p.headX; val hy = head?.y ?: p.headY
        val f = p.forward
        val look = 2.2f + p.speed * 0.12f
        val tx = hx + f.x * look; val tz = -(hy + f.y * look)
        val sens = settings.cameraSensitivity
        if (!camInit) { focusX = tx; focusZ = tz }
        val kf = 1f - exp(-8f * sens * dt)
        focusX += (tx - focusX) * kf; focusZ += (tz - focusZ) * kf

        val targetYaw = if (settings.cameraMode == 1) p.heading - PI.toFloat() / 2f else 0f
        var dy = targetYaw - yaw
        while (dy > PI) dy -= (2 * PI).toFloat(); while (dy < -PI) dy += (2 * PI).toFloat()
        yaw += dy * (1f - exp(-(if (settings.cameraMode == 1) 1.8f else 5f) * sens * dt))
        hud.cameraYaw = yaw

        val aspect = width.toFloat() / height
        val chainZoom = 1f + min(0.45f, p.model.length * 0.01f + p.headLevel * 0.018f)
        // Portrait sees less sideways, so pull back a bit; landscape gets a much closer third-person view.
        val aspectZoom = if (aspect < 1f) 1.18f - 0.18f * ((aspect - 0.45f) / 0.55f).coerceIn(0f, 1f) else 0.78f
        val boostZoom = if (p.boost.isBoosting) 1.06f else 1f
        val targetZoom = chainZoom * aspectZoom * boostZoom * settings.cameraZoom
        zoom += (targetZoom - zoom) * (1f - exp(-2f * dt))
        if (!camInit) zoom = targetZoom

        // Third-person: behind and above the head, looking slightly ahead of it.
        val ox = sin(yaw) * 10.5f * zoom; val oz = cos(yaw) * 10.5f * zoom; val oy = 13.5f * zoom
        val wx = focusX + ox; val wy = max(3f, oy); val wz = focusZ + oz
        val kp = 1f - exp(-dt / (0.18f / sens) * 1.6f)
        if (!camInit) { camX = wx; camY = wy; camZ = wz; camInit = true }
        camX += (wx - camX) * kp; camY += (wy - camY) * kp; camZ += (wz - camZ) * kp

        var sx = 0f; var sy = 0f
        if (shake > 0f) {
            val amp = shake * (if (settings.lowEffects) 0.3f else 0.6f)
            sx = sin(realTime * 61f) * amp; sy = cos(realTime * 47f) * amp
            shake = max(0f, shake - 4f * dt)
        }
        Matrix.setLookAtM(view, 0, camX + sx, camY + sy, camZ, focusX, 0f, focusZ, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        camRight[0] = view[0]; camRight[1] = view[4]; camRight[2] = view[8]
        camUp[0] = view[1]; camUp[1] = view[5]; camUp[2] = view[9]
    }

    /** Projects a GL world point to screen pixels; returns false if behind the camera. */
    private fun project(x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        world4[0] = x; world4[1] = y; world4[2] = z; world4[3] = 1f
        Matrix.multiplyMV(clip, 0, vp, 0, world4, 0)
        if (clip[3] <= 0.01f) return false
        out[0] = (clip[0] / clip[3] * 0.5f + 0.5f) * width
        out[1] = (1f - (clip[1] / clip[3] * 0.5f + 0.5f)) * height
        return true
    }

    private val scr = FloatArray(2)
    private fun onScreen(x: Float, y: Float, z: Float): Boolean =
        project(x, y, z, scr) && scr[0] > -width * 0.2f && scr[0] < width * 1.2f && scr[1] > -height * 0.2f && scr[1] < height * 1.2f

    // ------------------------------------------------------------------ rendering

    private fun render() {
        val sky = SKY
        GLES30.glClearColor(sky[0], sky[1], sky[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        buildInstances()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, spriteInstVbo)
        if (spriteCount > 0) GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, spriteCount * SPRITE_FLOATS * 4, spriteInst)

        // Ground.
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glUseProgram(groundProg)
        uniformsCommon(groundProg)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(groundProg, "uRadius"), world.arenaRadius)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(groundProg, "uGround"), 0.86f, 0.90f, 0.97f)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(groundProg, "uGrid"), 0.70f, 0.77f, 0.92f)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(groundProg, "uOutside"), 0.60f, 0.66f, 0.84f)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(groundProg, "uBoundary"), 1f, 0.36f, 0.42f)
        GLES30.glBindVertexArray(groundVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // Shadows and rings (alpha, no depth write) — drawn before cubes so cubes cover them.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        drawSprites(0, groundSpriteCount, billboard = false)

        // Cubes.
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glUseProgram(cubeProg)
        uniformsCommon(cubeProg)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(cubeProg, "uCells"), ATLAS_COLS.toFloat(), ATLAS_ROWS.toFloat())
        GLES30.glUniform3f(GLES30.glGetUniformLocation(cubeProg, "uLight"), light[0], light[1], light[2])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(cubeProg, "uTex"), 0)
        GLES30.glBindVertexArray(cubeVao)
        cubeInst.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cubeInstVbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, cubeCount * CUBE_FLOATS * 4, cubeInst)
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0, cubeCount)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        // Additive: halos (ground) and particles (billboards).
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        GLES30.glDepthMask(false)
        drawSprites(groundSpriteCount, haloEnd - groundSpriteCount, billboard = false)
        drawSprites(haloEnd, spriteCount - haloEnd, billboard = true)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(0)
    }

    private fun uniformsCommon(prog: Int) {
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog, "uVP"), 1, false, vp, 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(prog, "uCam"), camX, camY, camZ)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(prog, "uFogColor"), SKY[0], SKY[1], SKY[2])
        GLES30.glUniform2f(GLES30.glGetUniformLocation(prog, "uFog"), 70f * zoom, 230f * zoom)
    }

    private var groundSpriteCount = 0
    private var haloEnd = 0

    private fun drawSprites(first: Int, count: Int, billboard: Boolean) {
        if (count <= 0) return
        GLES30.glUseProgram(spriteProg)
        uniformsCommon(spriteProg)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(spriteProg, "uBillboard"), if (billboard) 1 else 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(spriteProg, "uCamRight"), camRight[0], camRight[1], camRight[2])
        GLES30.glUniform3f(GLES30.glGetUniformLocation(spriteProg, "uCamUp"), camUp[0], camUp[1], camUp[2])
        GLES30.glBindVertexArray(spriteVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, spriteInstVbo)
        // Re-point the instance attributes at this range.
        val base = first * SPRITE_FLOATS * 4
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, SPRITE_FLOATS * 4, base)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, SPRITE_FLOATS * 4, base + 16)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, SPRITE_FLOATS * 4, base + 32)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, count)
    }

    // Instance writers ----------------------------------------------------------------------------

    private fun cube(x: Float, y: Float, z: Float, size: Float, dirX: Float, dirY: Float, cell: Int, heightMul: Float, color: Int, emission: Float) {
        if (cubeCount >= MAX_CUBES) return
        cubeInst.put(x).put(y).put(z).put(size)
        cubeInst.put(dirX).put(dirY).put(cell.toFloat()).put(heightMul)
        cubeInst.put(Color.red(color) / 255f).put(Color.green(color) / 255f).put(Color.blue(color) / 255f).put(emission)
        cubeCount++
    }

    private fun sprite(x: Float, y: Float, z: Float, radius: Float, color: Int, alpha: Float, type: Int) {
        if (spriteCount >= MAX_SPRITES) return
        spriteInst.put(x).put(y).put(z).put(radius)
        spriteInst.put(Color.red(color) / 255f).put(Color.green(color) / 255f).put(Color.blue(color) / 255f).put(alpha)
        spriteInst.put(type.toFloat())
        spriteCount++
    }

    private fun glow(level: Int) = if (level >= world.config.glowLevel) (0.18f + 0.05f * (level - world.config.glowLevel)).coerceIn(0.18f, 0.55f) else 0f

    private fun buildInstances() {
        cubeInst.clear(); spriteInst.clear()
        cubeCount = 0; spriteCount = 0
        val low = settings.lowEffects
        val viewR2 = 75f * 75f * zoom * zoom
        val fx = focusX; val fz = focusZ
        fun near(x: Float, z: Float) = (x - fx) * (x - fx) + (z - fz) * (z - fz) < viewR2

        // Pass 1: ground sprites (shadows + team rings), collected alongside cube data in a temporary list.
        val playerCells = 64
        haloCount = 0

        for (c in world.chains) {
            if (!c.alive) continue
            val skinBase = if (c === world.player) playerCells else 0
            for ((i, s) in c.segments.withIndex()) {
                if (!near(s.x, -s.y)) continue
                val punch = 1f + sin(s.punch * PI.toFloat()) * 0.28f
                val size = s.size * punch
                cube(s.x, size * 0.5f + 0.02f, -s.y, size, s.dirX, s.dirY, skinBase + min(63, s.level), 1f, Color.WHITE, glow(s.level))
                sprite(s.x, 0.015f, -s.y, size * 0.72f, SHADOW, 0.38f, 0)
                if (glow(s.level) > 0f && !low) halo(s.x, -s.y, size * 1.3f, Palette.color(c.skin, s.level))
                if (i == 0) {
                    val blinkOff = c.isProtected && (realTime * 4f) % 1f < 0.35f
                    if (!blinkOff) sprite(s.x, 0.025f, -s.y, size * 0.95f, c.teamColor, 0.85f, 1)
                }
            }
            for (a in c.absorbing) {
                val s = a.segment
                cube(s.x, s.size * 0.5f + 0.02f, -s.y, s.size, s.dirX, s.dirY, skinBase + min(63, s.level), 1f, Color.WHITE, glow(s.level))
            }
        }

        val t = realTime
        for (col in world.collectibles) {
            val x = col.renderX; val z = -col.renderY
            if (!near(x, z)) continue
            drawCollectible(col, x, z, t, low)
        }
        for (col in world.collecting) drawCollectible(col, col.renderX, -col.renderY, t, low)

        // Static world: obstacles, posts, decorations.
        for (o in world.obstacles) {
            val ox = o.position.x; val oz = -o.position.y
            if (!near(ox, oz)) continue
            val w = o.radius * 2f
            cube(ox, o.height * 0.5f, oz, w, 0f, 1f, PLAIN_CELL, o.height / w, OBSTACLE, 0f)
            cube(ox, o.height + 0.9f + sin(t * 1.3f + ox) * 0.2f, oz, 0.9f, sin(t * 0.8f), cos(t * 0.8f), PLAIN_CELL, 1f, Color.WHITE, 0.35f)
            sprite(ox, 0.01f, oz, o.radius * 1.6f, SHADOW, 0.38f, 0)
        }
        val r = world.arenaRadius + 1f
        val posts = (2 * PI * world.arenaRadius / 9f).toInt()
        for (i in 0 until posts) {
            val a = i / posts.toFloat() * PI.toFloat() * 2f
            val px = cos(a) * r; val pz = sin(a) * r
            if (!near(px, pz)) continue
            cube(px, 0.6f, pz, 1f, -sin(a), cos(a), PLAIN_CELL, 1.2f, BOUNDARY, 0.35f)
        }
        for (d in decorations) {
            val a = t * d.spin * PI.toFloat() / 180f
            cube(d.x, d.y + sin(t * 0.4f + d.phase) * 1.5f, d.z, d.size, sin(a), cos(a), PLAIN_CELL, 1f, d.color, 0.1f)
        }

        groundSpriteCount = spriteCount
        for (i in 0 until haloCount) sprite(haloX[i], 0.03f, haloZ[i], haloR[i], haloC[i], 0.5f, 0)
        haloEnd = spriteCount
        for (i in 0 until MAX_PARTICLES) {
            if (pLife[i] <= 0f) continue
            val k = pLife[i] / pMax[i]
            sprite(pX[i], pY[i], pZ[i], pSize[i] * (0.3f + 0.7f * k), pCol[i], k, 2)
        }
        cubeInst.position(0); spriteInst.position(0)
    }

    private val haloX = FloatArray(512); private val haloZ = FloatArray(512); private val haloR = FloatArray(512); private val haloC = IntArray(512)
    private var haloCount = 0
    private fun halo(x: Float, z: Float, r: Float, c: Int) {
        if (haloCount >= haloX.size) return
        haloX[haloCount] = x; haloZ[haloCount] = z; haloR[haloCount] = r; haloC[haloCount] = c; haloCount++
    }

    private fun drawCollectible(col: Collectible, x: Float, z: Float, t: Float, low: Boolean) {
        val size = col.size * col.scale
        if (size <= 0.01f) return
        val bob = if (col.state == Collectible.State.IDLE && !low) sin(t * 2.2f + col.id) * 0.08f else 0f
        val ang = (col.spin + t * ((col.id % 7) - 3) * 8f) * PI.toFloat() / 180f
        val dim = !world.canCollect(world.player, col.level)
        cube(x, size * 0.5f + 0.06f + bob, z, size, sin(ang), cos(ang), min(63, col.level), 1f, if (dim) DIM else Color.WHITE, if (dim) 0f else glow(col.level))
        sprite(x, 0.012f, z, size * 0.7f, SHADOW, 0.38f, 0)
        if (!dim && glow(col.level) > 0f && !low) halo(x, z, size * 1.2f, Palette.color("classic", col.level))
    }

    // ------------------------------------------------------------------ HUD

    private fun updateHud(dt: Float) {
        val p = world.player
        val h = hud
        synchronized(h.lock) {
            h.score = p.stats.score
            h.leadLevel = p.model.headLevel
            h.chainLength = p.model.length
            h.highestLevel = p.stats.highestLevel
            h.boost01 = p.boost.display01
            h.boosting = p.boost.isBoosting
            h.boostReady = p.boost.ready
            h.skin = p.skin
            h.alive = p.alive
            h.eatenBy = p.lastEatenBy
            h.respawnIn = p.respawnCountdown
            h.peopleCount = world.chains.count { it.isHuman }
            p.segments.firstOrNull()?.let { if (project(it.x, 0.5f, -it.y, scr)) { h.headScreenX = scr[0]; h.headScreenY = scr[1] } }

            // Danger / edge.
            var danger = false
            if (p.alive && p.canBeDefeated) {
                val me = p.snapshot()
                for (c in world.chains) {
                    if (c === p || !c.alive || !c.canDefeatOthers) continue
                    if (Vec2.distanceSquared(c.headPosition, p.headPosition) > 144f) continue
                    if (CombatResolver.compareStrength(c.snapshot(), me, world.rules.strengthMetric) > 0) { danger = true; break }
                }
            }
            h.danger = danger
            if (danger && !wasDanger && realTime - lastDangerSound > 3f) { lastDangerSound = realTime; sound.play(Sfx.WARNING, 1f, 0.6f) }
            wasDanger = danger
            h.nearEdge = p.alive && p.headPosition.length > world.arenaRadius - world.config.arena.boundaryWarningDistance

            // Leaderboard and rank.
            leaderboardTimer -= dt
            if (leaderboardTimer <= 0f) {
                leaderboardTimer = 0.4f
                sortedScratch.clear(); scoreScratch.clear()
                for (c in world.chains) if (c.alive || c === p || c.isHuman) { sortedScratch += c; scoreScratch += c.stats.score }
                val pi = sortedScratch.indexOf(p)
                h.rank = RankCalculator.rankOf(scoreScratch, pi)
                h.competitors = sortedScratch.size
                sortedScratch.sortByDescending { it.stats.score }
                var n = 0; var shown = false
                for (i in 0 until min(5, sortedScratch.size)) {
                    val c = sortedScratch[i]
                    h.lbNames[n] = c.name; h.lbScores[n] = c.stats.score; h.lbRanks[n] = i + 1; h.lbIsPlayer[n] = c === p; h.lbIsHuman[n] = c.isHuman
                    if (c === p) shown = true
                    n++
                }
                if (!shown) { h.lbNames[n] = p.name; h.lbScores[n] = p.stats.score; h.lbRanks[n] = h.rank; h.lbIsPlayer[n] = true; h.lbIsHuman[n] = true; n++ }
                h.lbCount = n
            }

            // Rival labels.
            var lc = 0
            val me = p.snapshot()
            for (c in world.chains) {
                if (c === p || !c.alive || c.segments.isEmpty() || lc >= HudState.MAX_LABELS) continue
                val s = c.segments[0]
                if (!project(s.x, s.size + 1.2f, -s.y, scr)) continue
                if (scr[0] < -50 || scr[0] > width + 50 || scr[1] < -50 || scr[1] > height + 50) continue
                val cached = labelCache[c.id]
                val text = if (cached != null && cached.first == c.headLevel) cached.second
                    else (c.name + "  " + NumberFormat.formatLevel(c.headLevel)).also { labelCache[c.id] = c.headLevel to it }
                val cmp = CombatResolver.compareStrength(c.snapshot(), me, world.rules.strengthMetric)
                h.labelX[lc] = scr[0]; h.labelY[lc] = scr[1]; h.labelText[lc] = text
                h.labelColor[lc] = if (cmp > 0) 0xFFFF8C99.toInt() else if (cmp < 0) 0xFF9CFFB3.toInt() else Color.WHITE
                lc++
            }
            h.labelCount = lc

            // Floating texts.
            var fc = 0
            for (f in floating) {
                if (fc >= HudState.MAX_FLOAT) break
                if (!project(f.x, f.y + f.age * 2.2f, f.z, scr)) continue
                val k = f.age / f.life
                h.ftX[fc] = scr[0]; h.ftY[fc] = scr[1]; h.ftText[fc] = f.text; h.ftColor[fc] = f.color
                h.ftAlpha[fc] = 1f - k * k
                h.ftSize[fc] = f.size * (1f + 0.25f * sin(min(1f, k * 4f) * PI.toFloat()))
                fc++
            }
            h.ftCount = fc

            // Minimap.
            minimapTimer -= dt
            if (minimapTimer <= 0f) {
                minimapTimer = 0.1f
                val range = 48f
                val cy = cos(yaw); val sy = sin(yaw)
                var dc = 0
                fun dot(dx: Float, dy: Float, color: Int, size: Float) {
                    if (dc >= HudState.MAX_DOTS) return
                    // Rotate into camera space so "up" on the map is "up" on screen.
                    val rx = dx * cy + dy * sy; val ry = -dx * sy + dy * cy
                    h.dotX[dc] = rx / range; h.dotY[dc] = ry / range; h.dotColor[dc] = color; h.dotSize[dc] = size
                    dc++
                }
                colScratch.clear()
                world.queryCollectibles(p.headX, p.headY, range, colScratch)
                for (i in 0 until min(colScratch.size, 70)) {
                    val c = colScratch[i]
                    val can = world.canCollect(p, c.level)
                    dot(c.x - p.headX, c.y - p.headY, if (can) Palette.color("classic", c.level) else 0x73999BB3, if (can) 1f else 0.7f)
                }
                for (c in world.chains) {
                    if (c === p || !c.alive) continue
                    var dx = c.headX - p.headX; var dy = c.headY - p.headY
                    val d = sqrt(dx * dx + dy * dy)
                    val inRange = d < range
                    if (!inRange) { dx = dx / d * range * 0.97f; dy = dy / d * range * 0.97f }
                    val cmp = CombatResolver.compareStrength(c.snapshot(), me, world.rules.strengthMetric)
                    var color = if (cmp > 0) 0xFFFF4D5E.toInt() else if (cmp < 0) 0xFF38C773.toInt() else 0xFFFFB020.toInt()
                    if (!inRange) color = (color and 0x00FFFFFF) or 0x99000000.toInt()
                    dot(dx, dy, color, if (inRange) 1.8f else 1.2f)
                }
                h.dotCount = dc
                val toEdge = world.arenaRadius - p.headPosition.length
                h.edgeVisible = toEdge < range
                if (h.edgeVisible) {
                    val n = p.headPosition.normalized
                    val ex = n.x * toEdge; val ey = n.y * toEdge
                    h.edgeX = (ex * cy + ey * sy) / range; h.edgeY = (-ex * sy + ey * cy) / range
                }
            }
        }
    }

    companion object {
        const val MAX_CUBES = 2048
        const val CUBE_FLOATS = 12
        const val MAX_SPRITES = 4096
        const val SPRITE_FLOATS = 9
        const val MAX_PARTICLES = 700
        const val CELL = 128
        const val ATLAS_COLS = 16
        const val ATLAS_ROWS = 9
        const val PLAIN_CELL = 128
        val SKY = floatArrayOf(0.56f, 0.73f, 0.98f)
        val SHADOW = Color.rgb(26, 31, 64)
        val DIM = Color.rgb(158, 158, 173)
        val OBSTACLE = Color.rgb(140, 153, 209)
        val BOUNDARY = Color.rgb(255, 92, 107)
    }
}
