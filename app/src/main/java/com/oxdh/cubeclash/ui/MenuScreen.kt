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
    private val best = Ui.text(context, "", 16f, Ui.ACCENT)
    private lateinit var soundButton: android.widget.TextView

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF8FBAFA.toInt(), 0xFF5B7CE0.toInt(), 0xFF3B4BA8.toInt()))
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val outer = FrameLayout(context)
        val col = Ui.vertical(context, 14f).apply { gravity = Gravity.CENTER_HORIZONTAL; val p = Ui.dpi(context, 18f); setPadding(p, p * 2, p, p * 2) }

        val icons = Ui.horizontal(context, 6f).apply { gravity = Gravity.CENTER }
        val skin = save.progress.selectedSkin
        for (l in 0..6) icons.addView(ImageView(context).apply {
            setImageBitmap(FacePainter.icon(skin, l, Ui.dpi(context, 44f))); rotation = if (l % 2 == 0) 6f else -6f
        })
        col.addView(icons, Ui.matchWrap())
        col.addView(Ui.text(context, "CUBE CLASH", 46f).apply { letterSpacing = 0.04f }, Ui.matchWrap())
        col.addView(Ui.text(context, "Collect  •  Merge  •  Grow  •  Conquer", 15f, Ui.TEXT_DIM), Ui.matchWrap())
        col.addView(best, Ui.matchWrap())
        refreshBest()

        val card = Ui.vertical(context, 10f).apply { background = Ui.rounded(0xA0141A40.toInt(), Ui.dp(context, 22f)); val p = Ui.dpi(context, 16f); setPadding(p, p, p, p) }
        val desc = Ui.text(context, GameModeSettings.forMode(GameMode.values()[settings.lastMode]).description, 13f, Ui.TEXT_DIM, bold = false)
        card.addView(Ui.segmented(context, "Mode", listOf("Classic", "Endless", "Survival"), settings.lastMode) {
            settings.lastMode = it; desc.text = GameModeSettings.forMode(GameMode.values()[it]).description
        }, Ui.matchWrap())
        card.addView(desc, Ui.matchWrap())
        card.addView(Ui.segmented(context, "Rivals", listOf("Easy", "Normal", "Hard"), settings.lastDifficulty) { settings.lastDifficulty = it }, Ui.matchWrap())
        col.addView(card, Ui.matchWrap())

        col.addView(Ui.button(context, "PLAY", Ui.GOOD, 30f) { activity.startGame(settings.lastMode, settings.lastDifficulty) }, Ui.matchWrap())
        col.addView(Ui.button(context, "PLAY WITH FRIENDS", Ui.PRIMARY, 20f) { open { MultiplayerDialog.open(this, activity) { closed() } } }, Ui.matchWrap())

        val row1 = Ui.horizontal(context, 10f)
        row1.addView(Ui.button(context, "Settings", Ui.PRIMARY, 15f) { open { Dialogs.settings(this, settings, save) { closed() } } }, Ui.weighted())
        row1.addView(Ui.button(context, "How to Play", Ui.PRIMARY, 15f) { open { Dialogs.text(this, "How to Play", Dialogs.HOW_TO_PLAY) { closed() } } }, Ui.weighted())
        row1.addView(Ui.button(context, "High Score", Ui.PRIMARY, 15f) { open { Dialogs.stats(this, save.progress) { closed() } } }, Ui.weighted())
        col.addView(row1, Ui.matchWrap())
        val row2 = Ui.horizontal(context, 10f)
        row2.addView(Ui.button(context, "Customize", Ui.ACCENT, 15f) { open { Dialogs.customize(this, save) { closed() } } }, Ui.weighted())
        soundButton = Ui.button(context, "", Ui.PANEL_LIGHT, 15f) { settings.muted = !settings.muted; refreshSound() }
        row2.addView(soundButton, Ui.weighted())
        row2.addView(Ui.button(context, "Privacy & Credits", Ui.PANEL_LIGHT, 14f) { open { Dialogs.text(this, "Privacy & Credits", Dialogs.CREDITS) { closed() } } }, Ui.weighted())
        col.addView(row2, Ui.matchWrap())
        refreshSound()

        val maxW = Ui.dpi(context, 480f)
        outer.addView(col, LayoutParams(minOf(maxW, resources.displayMetrics.widthPixels), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        scroll.addView(outer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun open(f: () -> FrameLayout) { modal = f() }
    private fun closed() { modal = null; refreshBest(); refreshSound() }

    private fun refreshBest() {
        val p = save.progress
        best.text = if (p.gamesPlayed > 0) "Best ${NumberFormat.formatScore(p.bestScore)}   •   Top cube ${NumberFormat.formatLevel(p.highestLevel)}"
        else "Start as a 1 — how big can you grow?"
    }

    private fun refreshSound() {
        soundButton.text = if (settings.muted) "Sound: OFF" else "Sound: ON"
        Ui.setButtonColor(soundButton, if (settings.muted) Ui.PANEL_LIGHT else Ui.GOOD)
    }

    /** Back: close an open dialog; returns false if nothing was open. */
    fun onBack(): Boolean {
        val m = modal ?: return false
        Ui.close(m); closed(); return true
    }
}
