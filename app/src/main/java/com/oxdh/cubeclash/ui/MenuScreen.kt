package com.oxdh.cubeclash.ui

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.oxdh.cubeclash.MainActivity
import com.oxdh.cubeclash.engine.GameMode
import com.oxdh.cubeclash.engine.GameModeSettings
import com.oxdh.cubeclash.engine.NumberFormat
import com.oxdh.cubeclash.gfx.FacePainter

/** Main menu: logo, mode & rival difficulty, Play, Settings, How to Play, High Score, Customize, Sound, Privacy & Credits. */
class MenuScreen(private val activity: MainActivity) : FrameLayout(activity) {
    private val settings = activity.settings
    private val save = activity.save
    private var modal: FrameLayout? = null
    private val best = Ui.text(context, "", 13f, Ui.ACCENT)
    private lateinit var soundButton: android.widget.TextView

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF8FBAFA.toInt(), 0xFF5B7CE0.toInt(), 0xFF3B4BA8.toInt()))
        val dm = resources.displayMetrics
        val landscape = dm.widthPixels > dm.heightPixels
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val outer = FrameLayout(context)
        val p = Ui.dpi(context, 14f)

        // Left: logo, tagline, best and the mode / rivals pickers.
        val left = Ui.vertical(context, 8f).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val icons = Ui.horizontal(context, 4f).apply { gravity = Gravity.CENTER }
        val skin = save.progress.selectedSkin
        for (l in 0..6) icons.addView(ImageView(context).apply {
            setImageBitmap(FacePainter.icon(skin, l, Ui.dpi(context, 30f))); rotation = if (l % 2 == 0) 6f else -6f
        })
        left.addView(icons, Ui.matchWrap())
        left.addView(Ui.text(context, "CUBE CLASH", 34f).apply { letterSpacing = 0.04f }, Ui.matchWrap())
        left.addView(Ui.text(context, "Collect  •  Merge  •  Grow  •  Conquer", 12f, Ui.TEXT_DIM), Ui.matchWrap())
        left.addView(best, Ui.matchWrap())
        refreshBest()

        val card = Ui.vertical(context, 6f).apply { background = Ui.rounded(0xA0141A40.toInt(), Ui.dp(context, 16f)); val cp = Ui.dpi(context, 10f); setPadding(cp, cp, cp, cp) }
        val desc = Ui.text(context, GameModeSettings.forMode(GameMode.values()[settings.lastMode]).description, 11f, Ui.TEXT_DIM, bold = false)
        card.addView(Ui.segmented(context, "Mode", listOf("Classic", "Endless", "Survival"), settings.lastMode, compact = true) {
            settings.lastMode = it; desc.text = GameModeSettings.forMode(GameMode.values()[it]).description
        }, Ui.matchWrap())
        card.addView(desc, Ui.matchWrap())
        card.addView(Ui.segmented(context, "Rivals", listOf("Easy", "Normal", "Hard"), settings.lastDifficulty, compact = true) { settings.lastDifficulty = it }, Ui.matchWrap())
        left.addView(card, Ui.matchWrap())

        // Right: the big actions and a compact grid of the rest.
        val right = Ui.vertical(context, 8f).apply { gravity = Gravity.CENTER_VERTICAL }
        right.addView(Ui.button(context, "PLAY", Ui.GOOD, 26f, compact = true) { activity.startGame(settings.lastMode, settings.lastDifficulty) }, Ui.matchWrap())
        right.addView(Ui.button(context, "PLAY WITH FRIENDS", Ui.PRIMARY, 16f, compact = true) { open { MultiplayerDialog.open(this, activity) { closed() } } }, Ui.matchWrap())
        val row1 = Ui.horizontal(context, 8f)
        row1.addView(Ui.button(context, "Settings", Ui.PRIMARY, 13f, compact = true) { open { Dialogs.settings(this, settings, save, { modal = it }) { closed() } } }, Ui.weighted())
        row1.addView(Ui.button(context, "How to Play", Ui.PRIMARY, 13f, compact = true) { open { Dialogs.text(this, "How to Play", Dialogs.HOW_TO_PLAY) { closed() } } }, Ui.weighted())
        row1.addView(Ui.button(context, "High Score", Ui.PRIMARY, 13f, compact = true) { open { Dialogs.stats(this, save.progress) { closed() } } }, Ui.weighted())
        right.addView(row1, Ui.matchWrap())
        val row2 = Ui.horizontal(context, 8f)
        row2.addView(Ui.button(context, "Customize", Ui.ACCENT, 13f, compact = true) { open { Dialogs.customize(this, save) { closed() } } }, Ui.weighted())
        soundButton = Ui.button(context, "", Ui.PANEL_LIGHT, 13f, compact = true) { settings.muted = !settings.muted; refreshSound() }
        row2.addView(soundButton, Ui.weighted())
        row2.addView(Ui.button(context, "Privacy", Ui.PANEL_LIGHT, 13f, compact = true) { open { Dialogs.text(this, "Privacy & Credits", Dialogs.CREDITS) { closed() } } }, Ui.weighted())
        right.addView(row2, Ui.matchWrap())
        refreshSound()

        val content: LinearLayout
        val maxW: Int
        if (landscape) {
            content = Ui.horizontal(context, 18f).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(p, p, p, p) }
            content.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            maxW = Ui.dpi(context, 820f)
        } else {
            content = Ui.vertical(context, 12f).apply { setPadding(p, p, p, p) }
            content.addView(left, Ui.matchWrap()); content.addView(right, Ui.matchWrap())
            maxW = Ui.dpi(context, 440f)
        }
        outer.addView(content, LayoutParams(minOf(maxW, dm.widthPixels), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        scroll.addView(outer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun open(f: () -> FrameLayout) { modal = f() }
    private fun closed() { modal = null; refreshBest(); refreshSound() }

    private fun refreshBest() {
        val p = save.progress
        best.text = if (p.gamesPlayed > 0) "Best ${NumberFormat.formatScore(p.bestScore)}  •  Top cube ${NumberFormat.formatLevel(p.highestLevel)}"
        else "Start as a 1 — how big can you grow?"
    }

    private fun refreshSound() {
        soundButton.text = if (settings.muted) "Sound OFF" else "Sound ON"
        Ui.setButtonColor(soundButton, if (settings.muted) Ui.PANEL_LIGHT else Ui.GOOD)
    }

    /** Back: close an open dialog; returns false if nothing was open. */
    fun onBack(): Boolean {
        val m = modal ?: return false
        Ui.close(m); closed(); return true
    }
}
