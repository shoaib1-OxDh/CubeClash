package com.oxdh.cubeclash

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.oxdh.cubeclash.engine.AIDifficulty
import com.oxdh.cubeclash.engine.GameMode
import com.oxdh.cubeclash.engine.Vec2
import com.oxdh.cubeclash.engine.net.ClientSession
import com.oxdh.cubeclash.engine.net.Lan
import com.oxdh.cubeclash.engine.net.Online
import com.oxdh.cubeclash.ui.GameScreen
import com.oxdh.cubeclash.ui.MenuScreen
import com.oxdh.cubeclash.ui.Sounds

/**
 * Single activity hosting the main menu and the arena. Everything is built in code; the game is fully offline.
 *
 * Debug/CI extras: `--ez autoplay true [--ei mode 0..2]` starts a run where the AI plays for the player;
 * `--es mp lan_host|online_join [--es code K7Q2PX] [--ei bots 2]` starts a multiplayer room (emulator tests).
 */
class MainActivity : Activity() {
    lateinit var settings: Settings; private set
    lateinit var save: SaveStore; private set
    lateinit var sound: Sound; private set
    private lateinit var root: FrameLayout
    private var game: GameScreen? = null
    private var menu: MenuScreen? = null
    private val keys = HashSet<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28)
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        settings = Settings(this)
        save = SaveStore(this)
        sound = Sound(this, settings)
        Sounds.onClick = { sound.play(Sfx.CLICK) }
        root = FrameLayout(this)
        setContentView(root)
        val autoplay = intent?.getBooleanExtra("autoplay", false) == true
        when (intent?.getStringExtra("mp")) {
            "lan_host" -> hostLocal(intent.getIntExtra("bots", 2), autoplay)
            "online_host" -> hostOnline(intent.getIntExtra("bots", 2), autoplay) {}
            "lan_join" -> joinLocal(intent.getStringExtra("host") ?: "127.0.0.1", intent.getIntExtra("port", Lan.DEFAULT_PORT), autoplay)
            "online_join" -> joinOnline(intent.getStringExtra("code") ?: "", autoplay)
            else -> if (autoplay) startGame(intent.getIntExtra("mode", 0), 1, autoplay = true) else showMenu()
        }
    }

    // ------------------------------------------------------------------ multiplayer

    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    /** Room discovery and hosting need broadcast packets, which Android filters unless we hold this lock. */
    private fun holdMulticast(on: Boolean) {
        try {
            if (on) {
                if (multicastLock == null) {
                    val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
                    multicastLock = wm.createMulticastLock("cubeclash").apply { setReferenceCounted(false) }
                }
                multicastLock?.acquire()
            } else multicastLock?.release()
        } catch (_: Exception) {}
    }

    private fun toast(msg: String) = runOnUiThread { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() }

    fun hostLocal(bots: Int, autoplay: Boolean = false) {
        holdMulticast(true)
        Thread {
            var holder: GameSession? = null
            val code = Online.newCode(0).take(4)
            val transport = try {
                Lan.HostTransportImpl({ settings.playerName + "'s room" }, code, { holder?.host?.connectedCount?.plus(1) ?: 1 })
            } catch (e: Exception) {
                try { Lan.HostTransportImpl({ settings.playerName + "'s room" }, code, { holder?.host?.connectedCount?.plus(1) ?: 1 }, port = 0) }
                catch (e2: Exception) { toast("Could not open a local room: ${e2.message}"); return@Thread }
            }
            val address = Lan.localAddresses().firstOrNull()
            val label = "Local room " + (address?.let { if (transport.port == Lan.DEFAULT_PORT) it else "$it:${transport.port}" } ?: "(no Wi-Fi?)")
            runOnUiThread {
                val p = save.progress
                val session = GameSession.host(transport, code, label, null, settings.playerName, bots, p.selectedSkin, p.selectedTrail, autoplay)
                holder = session
                startSession(session)
                if (address == null) toast("Connect to Wi-Fi or turn on your hotspot so friends can join.")
            }
        }.start()
    }

    fun hostOnline(bots: Int, autoplay: Boolean = false, onError: (String) -> Unit) {
        Thread {
            var holder: GameSession? = null
            val transport = Online.HostTransportImpl(settings.playerName) { holder?.host?.connectedCount?.plus(1) ?: 1 }
            val code = try { transport.open() } catch (e: Exception) {
                runOnUiThread { onError("Could not reach the online server. Check your internet connection and try again.") }
                return@Thread
            }
            runOnUiThread {
                val p = save.progress
                val session = GameSession.host(transport, code, "Online room $code", code, settings.playerName, bots, p.selectedSkin, p.selectedTrail, autoplay)
                holder = session
                startSession(session)
            }
        }.start()
    }

    fun joinLocal(host: String, port: Int, autoplay: Boolean = false) {
        holdMulticast(true)
        val p = save.progress
        val client = ClientSession({ Lan.ClientTransportImpl(host, port) }, settings.clientId, settings.playerName, p.selectedSkin, p.selectedTrail, interpolationDelay = 0.08f)
        startSession(GameSession.join(client, "Local room $host", autoplay))
    }

    fun joinOnline(code: String, autoplay: Boolean = false) {
        val p = save.progress
        val client = ClientSession({ Online.ClientTransportImpl(code, settings.clientId) }, settings.clientId, settings.playerName, p.selectedSkin, p.selectedTrail, interpolationDelay = 0.15f)
        startSession(GameSession.join(client, "Online room $code", autoplay))
    }

    /** Searches the local network for rooms (on a background thread); [done] runs on the UI thread. */
    fun findLocalRooms(done: (List<Lan.FoundRoom>) -> Unit) {
        holdMulticast(true)
        Thread {
            val rooms = try { Lan.discover(1800) } catch (_: Exception) { emptyList() }
            runOnUiThread { done(rooms) }
        }.start()
    }

    private fun startSession(session: GameSession) {
        game?.shutdown()
        root.removeAllViews()
        menu = null
        session.localSkin = save.progress.selectedSkin
        game = GameScreen(this, session).also { root.addView(it) }
    }

    fun showMenu() {
        game?.shutdown(); game = null
        holdMulticast(false)
        root.removeAllViews()
        menu = MenuScreen(this).also { root.addView(it) }
    }

    fun startGame(mode: Int, difficulty: Int, autoplay: Boolean = false) {
        game?.shutdown()
        root.removeAllViews()
        menu = null
        val p = save.progress
        val session = GameSession.solo(GameMode.values()[mode.coerceIn(0, 2)], AIDifficulty.values()[difficulty.coerceIn(0, 2)], p.selectedSkin, p.selectedTrail, autoplay)
        session.localSkin = p.selectedSkin
        game = GameScreen(this, session).also { root.addView(it) }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        game?.onResume()
        sound.startMusic()
    }

    override fun onPause() {
        game?.onPause()
        sound.pauseMusic()
        save.save()
        super.onPause()
    }

    override fun onDestroy() {
        game?.session?.shutdownNetwork()
        holdMulticast(false)
        sound.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    @Deprecated("Handles the system back button on all API levels")
    override fun onBackPressed() {
        val g = game
        when {
            g != null -> g.onBack()
            menu?.onBack() == true -> {}
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // Keyboard / gamepad steering (emulators, Chromebooks): WASD / arrows, Space or Shift to boost, Esc to pause.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) { game?.onBack(); return true }
        if (handlesKey(keyCode)) { keys += keyCode; applyKeys(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (handlesKey(keyCode)) { keys -= keyCode; applyKeys(); return true }
        return super.onKeyUp(keyCode, event)
    }

    private fun handlesKey(k: Int) = game != null && k in KEYS

    private fun applyKeys() {
        val hud = game?.hudView ?: return
        var x = 0f; var y = 0f
        if (KeyEvent.KEYCODE_A in keys || KeyEvent.KEYCODE_DPAD_LEFT in keys) x -= 1
        if (KeyEvent.KEYCODE_D in keys || KeyEvent.KEYCODE_DPAD_RIGHT in keys) x += 1
        if (KeyEvent.KEYCODE_W in keys || KeyEvent.KEYCODE_DPAD_UP in keys) y += 1
        if (KeyEvent.KEYCODE_S in keys || KeyEvent.KEYCODE_DPAD_DOWN in keys) y -= 1
        hud.keyboardDir = if (x != 0f || y != 0f) Vec2(x, y).normalized else null
        hud.keyboardBoost = KeyEvent.KEYCODE_SPACE in keys || KeyEvent.KEYCODE_SHIFT_LEFT in keys || KeyEvent.KEYCODE_BUTTON_A in keys
    }

    private companion object {
        val KEYS = setOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_BUTTON_A)
    }
}
