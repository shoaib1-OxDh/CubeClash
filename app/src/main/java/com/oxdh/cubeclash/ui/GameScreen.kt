package com.oxdh.cubeclash.ui

import android.annotation.SuppressLint
import android.opengl.GLSurfaceView
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.oxdh.cubeclash.GameSession
import com.oxdh.cubeclash.MainActivity
import com.oxdh.cubeclash.Sfx
import com.oxdh.cubeclash.engine.NumberFormat
import com.oxdh.cubeclash.engine.ProgressRules
import com.oxdh.cubeclash.engine.UnlockCatalog
import com.oxdh.cubeclash.gfx.FacePainter
import com.oxdh.cubeclash.gfx.GameRenderer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/** Arena screen: GL view + HUD + pause / game-over overlays. */
@SuppressLint("ViewConstructor")
class GameScreen(private val activity: MainActivity, val session: GameSession) : FrameLayout(activity), GameRenderer.Listener {
    private val settings = activity.settings
    private val glView = GLSurfaceView(activity)
    val hudView = HudView(activity, session, settings, { pause() }, { glView.queueEvent { session.client?.reconnectNow() } })
    private var pauseOverlay: FrameLayout? = null
    private var subModal: FrameLayout? = null
    private var gameOverOverlay: FrameLayout? = null
    private var recorded = false

    init {
        glView.setEGLContextClientVersion(3)
        glView.setEGLConfigChooser(MsaaChooser(!settings.lowEffects))
        glView.preserveEGLContextOnPause = true
        glView.setRenderer(GameRenderer(session, settings, activity.sound, this))
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        addView(glView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(hudView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun requestHudRedraw() { hudView.postInvalidateOnAnimation() }

    override fun onRunOver(defeated: Boolean) { post { showGameOver(defeated) } }

    fun onPause() { glView.onPause(); hudView.releaseAll(); if (!session.ended && !session.autoplay && !session.isMultiplayer) pause() }
    fun onResume() { glView.onResume() }

    fun pause() {
        if (session.isMultiplayer) { roomMenu(); return }
        if (session.paused || session.ended || gameOverOverlay != null) return
        session.paused = true
        hudView.releaseAll()
        lateinit var o: FrameLayout
        o = Ui.modal(this, "Paused", 380f, scroll = false) { col ->
            val c = col.context
            col.addView(Ui.button(c, "Resume", Ui.GOOD) { resume() }, Ui.matchWrap())
            col.addView(Ui.button(c, "Restart", Ui.PRIMARY) { record(); activity.startGame(session.mode.ordinal, session.difficulty.ordinal) }, Ui.matchWrap())
            col.addView(Ui.button(c, "Settings", Ui.PANEL_LIGHT) {
                o.visibility = INVISIBLE
                subModal = Dialogs.settings(this, settings, null, { subModal = it }) { subModal = null; o.visibility = VISIBLE }
            }, Ui.matchWrap())
            col.addView(Ui.button(c, "End Run", Ui.ACCENT) { Ui.close(o); pauseOverlay = null; session.paused = false; session.endRequested = true }, Ui.matchWrap())
            col.addView(Ui.button(c, "Main Menu", Ui.DANGER) { record(); activity.showMenu() }, Ui.matchWrap())
        }
        pauseOverlay = o
    }

    fun resume() {
        if (session.isMultiplayer) session.paused = false
        pauseOverlay?.let { Ui.close(it) }
        pauseOverlay = null
        session.paused = false
    }

    /** Multiplayer menu: the room keeps running for everyone while it is open. */
    private fun roomMenu() {
        if (pauseOverlay != null) return
        hudView.releaseAll()
        session.paused = true
        lateinit var o: FrameLayout
        val title = if (session.kind == GameSession.Kind.HOST) "Your Room" else "Room"
        o = Ui.modal(this, title, 400f, scroll = false) { col ->
            val c = col.context
            session.roomLabel?.let { col.addView(Ui.text(c, it, 16f, Ui.ACCENT), Ui.matchWrap()) }
            session.host?.let { h ->
                // Read from the UI thread while the game thread may change the list: copy defensively.
                val names = try { h.peers.toList().joinToString { it.chain.name + if (it.connected) "" else " (reconnecting)" } } catch (_: Exception) { "" }
                col.addView(Ui.text(c, if (names.isEmpty()) "Waiting for friends to join…" else "Playing: $names", 14f, Ui.TEXT_DIM, bold = false), Ui.matchWrap())
                if (session.inviteCode == null) col.addView(Ui.text(c, "Friends on the same Wi-Fi / hotspot: Play with Friends › Find rooms, or join by the address above.", 13f, Ui.TEXT_DIM, bold = false), Ui.matchWrap())
            }
            session.client?.let { cl -> if (cl.lastRttMs >= 0) col.addView(Ui.text(c, "Ping ${cl.lastRttMs} ms", 13f, Ui.TEXT_DIM, bold = false), Ui.matchWrap()) }
            col.addView(Ui.button(c, "Resume", Ui.GOOD) { Ui.close(o); pauseOverlay = null; session.paused = false }, Ui.matchWrap())
            session.inviteCode?.let { code ->
                col.addView(Ui.button(c, "Invite friends (code $code)", Ui.PRIMARY) {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, "Join my Cube Clash room! Open Cube Clash › Play with Friends › Online and enter the code $code")
                    }
                    activity.startActivity(android.content.Intent.createChooser(send, "Invite friends"))
                }, Ui.matchWrap())
            }
            if (session.client?.status == com.oxdh.cubeclash.engine.net.ClientSession.Status.RECONNECTING)
                col.addView(Ui.button(c, "Reconnect now", Ui.PRIMARY) { glView.queueEvent { session.client?.reconnectNow() } }, Ui.matchWrap())
            col.addView(Ui.button(c, "Settings", Ui.PANEL_LIGHT) {
                o.visibility = INVISIBLE
                subModal = Dialogs.settings(this, settings, null, { subModal = it }) { subModal = null; o.visibility = VISIBLE }
            }, Ui.matchWrap())
            val leave = if (session.kind == GameSession.Kind.HOST) "End room for everyone" else "Leave room"
            col.addView(Ui.button(c, leave, Ui.DANGER) { record(); activity.showMenu() }, Ui.matchWrap())
        }
        pauseOverlay = o
    }

    /** Folds this run into the saved progress exactly once. */
    private fun record(): com.oxdh.cubeclash.engine.PersonalBestResult? {
        if (recorded || session.autoplay) return null
        recorded = true
        val r = ProgressRules.applySession(activity.save.progress, session.world.player.stats, session.mode)
        activity.save.save()
        return r
    }

    private fun showGameOver(defeated: Boolean) {
        session.ended = true
        pauseOverlay?.let { Ui.close(it) }; pauseOverlay = null
        hudView.releaseAll()
        val stats = session.world.player.stats
        val prev = activity.save.progress.bestScore
        val r = record()
        if (r != null && (r.newBestScore || r.newHighestLevel)) activity.sound.play(Sfx.HIGH_SCORE)
        gameOverOverlay = Ui.modal(this, if (defeated) "Game Over" else "Run Complete", 420f) { col ->
            val c = col.context
            col.addView(ImageView(c).apply { setImageBitmap(FacePainter.icon(session.world.player.skin, stats.highestLevel, Ui.dpi(c, 84f))) },
                android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dpi(c, 88f)))
            col.addView(Ui.text(c, NumberFormat.formatScore(stats.score), 40f), Ui.matchWrap())
            val bestLine = if (r?.newBestScore == true) "<font color='#FFC23D'>NEW PERSONAL BEST!</font>" else "Best: ${NumberFormat.formatScore(maxOf(prev, stats.score))}"
            col.addView(Ui.text(c, Ui.html(bestLine), 16f, Ui.TEXT_DIM), Ui.matchWrap())
            fun row(k: String, v: CharSequence) {
                val rw = Ui.horizontal(c, 8f)
                rw.addView(Ui.text(c, k, 15f, Ui.TEXT_DIM, bold = false, gravity = Gravity.START), Ui.weighted())
                rw.addView(Ui.text(c, v, 16f, gravity = Gravity.END), Ui.weighted())
                col.addView(rw, Ui.matchWrap())
            }
            row("Highest number", Ui.html(NumberFormat.formatLevel(stats.highestLevel) + if (r?.newHighestLevel == true) " <font color='#FFC23D'>NEW!</font>" else ""))
            row("Longest chain", stats.maxChainLength.toString())
            row("Rivals defeated", stats.opponentsDefeated.toString())
            row("Cubes collected", stats.cubesCollected.toString())
            row("Merges", stats.merges.toString())
            row("Time", NumberFormat.formatDuration(stats.durationSeconds))
            if (r != null) row("Coins earned", "+${r.coinsEarned}")
            if (r != null && r.newUnlocks.isNotEmpty())
                col.addView(Ui.text(c, "Unlocked: " + r.newUnlocks.joinToString { UnlockCatalog.get(it)?.displayName ?: it }, 15f, Ui.GOOD), Ui.matchWrap())
            col.addView(Ui.button(c, "Play Again", Ui.GOOD) { activity.startGame(session.mode.ordinal, session.difficulty.ordinal) }, Ui.matchWrap())
            col.addView(Ui.button(c, "Main Menu", Ui.PRIMARY) { activity.showMenu() }, Ui.matchWrap())
        }
    }

    /** Android back button. */
    fun onBack() {
        val sm = subModal
        when {
            sm != null -> { Ui.close(sm); subModal = null; pauseOverlay?.visibility = VISIBLE }
            gameOverOverlay != null -> activity.showMenu()
            pauseOverlay != null -> resume()
            else -> pause()
        }
    }

    fun shutdown() {
        glView.onPause()
        session.shutdownNetwork()
    }

    /** Prefers 4x MSAA (unless low effects), falls back to no MSAA, then to anything ES-capable. */
    private class MsaaChooser(private val msaa: Boolean) : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val es3 = 0x40 // EGL_OPENGL_ES3_BIT_KHR
            val attempts = mutableListOf<IntArray>()
            for (type in intArrayOf(es3, 4)) {
                if (msaa) attempts += attribs(type, 4)
                attempts += attribs(type, 0)
            }
            for (a in attempts) {
                val num = IntArray(1)
                val configs = arrayOfNulls<EGLConfig>(1)
                if (egl.eglChooseConfig(display, a, configs, 1, num) && num[0] > 0 && configs[0] != null) return configs[0]!!
            }
            throw IllegalStateException("No suitable EGL config")
        }

        private fun attribs(type: Int, samples: Int): IntArray {
            val base = mutableListOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, type)
            if (samples > 0) base += listOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples)
            base += EGL10.EGL_NONE
            return base.toIntArray()
        }
    }
}
