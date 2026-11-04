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
import com.oxdh.cubeclash.ui.GameScreen
import com.oxdh.cubeclash.ui.MenuScreen
import com.oxdh.cubeclash.ui.Sounds

/**
 * Single activity hosting the main menu and the arena. Everything is built in code; the game is fully offline.
 *
 * Debug/CI extras: `--ez autoplay true [--ei mode 0..2]` starts a run where the AI plays for the player
 * (used by the emulator screenshot workflow).
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
        if (intent?.getBooleanExtra("autoplay", false) == true) startGame(intent.getIntExtra("mode", 0), 1, autoplay = true)
        else showMenu()
    }

    fun showMenu() {
        game?.shutdown(); game = null
        root.removeAllViews()
        menu = MenuScreen(this).also { root.addView(it) }
    }

    fun startGame(mode: Int, difficulty: Int, autoplay: Boolean = false) {
        game?.shutdown()
        root.removeAllViews()
        menu = null
        val p = save.progress
        val session = GameSession(GameMode.values()[mode.coerceIn(0, 2)], AIDifficulty.values()[difficulty.coerceIn(0, 2)], p.selectedSkin, p.selectedTrail, autoplay)
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
