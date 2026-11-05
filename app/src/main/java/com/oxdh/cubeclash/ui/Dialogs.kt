package com.oxdh.cubeclash.ui

import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.oxdh.cubeclash.SaveStore
import com.oxdh.cubeclash.Settings
import com.oxdh.cubeclash.engine.GameMode
import com.oxdh.cubeclash.engine.NumberFormat
import com.oxdh.cubeclash.engine.PlayerProgress
import com.oxdh.cubeclash.engine.UnlockCatalog
import com.oxdh.cubeclash.engine.UnlockKind
import com.oxdh.cubeclash.gfx.FacePainter
import com.oxdh.cubeclash.gfx.Palette

/** Settings, How to Play, High Score & Stats, Customize and Privacy & Credits screens. */
object Dialogs {
    fun settings(parent: FrameLayout, s: Settings, save: SaveStore?, onClose: () -> Unit): FrameLayout {
        lateinit var overlay: FrameLayout
        overlay = Ui.modal(parent, "Settings") { col ->
            val c = col.context
            col.addView(Ui.header(c, "Audio"))
            col.addView(Ui.toggleRow(c, "Mute all", { s.muted }, { s.muted = it }))
            col.addView(Ui.sliderRow(c, "Music", 0f, 1f, s.musicVolume) { s.musicVolume = it })
            col.addView(Ui.sliderRow(c, "Effects", 0f, 1f, s.sfxVolume) { s.sfxVolume = it })
            col.addView(Ui.header(c, "Controls"))
            col.addView(Ui.segmented(c, "Steering", listOf("Drag anywhere", "Point to steer"), s.controls) { s.controls = it })
            col.addView(Ui.sliderRow(c, "Turn speed", 0.5f, 1.6f, s.turnSensitivity) { s.turnSensitivity = it })
            col.addView(Ui.sliderRow(c, "Drag distance", 0.7f, 1.4f, s.joystickScale) { s.joystickScale = it })
            col.addView(Ui.header(c, "Camera"))
            col.addView(Ui.segmented(c, "Camera", listOf("Fixed angle", "Follow heading"), s.cameraMode) { s.cameraMode = it })
            col.addView(Ui.sliderRow(c, "Zoom", 0.7f, 1.5f, s.cameraZoom) { s.cameraZoom = it })
            col.addView(Ui.sliderRow(c, "Follow speed", 0.4f, 2f, s.cameraSensitivity) { s.cameraSensitivity = it })
            col.addView(Ui.toggleRow(c, "Minimap", { s.showMinimap }, { s.showMinimap = it }))
            col.addView(Ui.header(c, "Graphics"))
            col.addView(Ui.toggleRow(c, "Low effects", { s.lowEffects }, { s.lowEffects = it }))
            col.addView(Ui.segmented(c, "Frame rate", listOf("30 FPS", "60 FPS"), if (s.targetFps >= 60) 1 else 0) { s.targetFps = if (it == 1) 60 else 30 })
            if (save != null) {
                col.addView(Ui.header(c, "Data"))
                var armed = false
                lateinit var reset: android.widget.TextView
                reset = Ui.button(c, "Reset progress", Ui.PANEL_LIGHT, 16f) {
                    if (!armed) { armed = true; reset.text = "Tap again to confirm"; Ui.setButtonColor(reset, Ui.DANGER) }
                    else { save.reset(); reset.text = "Progress reset"; reset.isEnabled = false }
                }
                col.addView(reset, Ui.matchWrap())
            }
            col.addView(Ui.button(c, "Done", Ui.PRIMARY) { Ui.close(overlay); onClose() }, Ui.matchWrap())
        }
        return overlay
    }

    fun text(parent: FrameLayout, title: String, html: String, onClose: () -> Unit): FrameLayout {
        lateinit var overlay: FrameLayout
        overlay = Ui.modal(parent, title) { col ->
            val c = col.context
            col.addView(Ui.text(c, Ui.html(html), 15f, bold = false, gravity = Gravity.START).apply { setLineSpacing(0f, 1.15f) }, Ui.matchWrap())
            col.addView(Ui.button(c, "Close", Ui.PRIMARY) { Ui.close(overlay); onClose() }, Ui.matchWrap())
        }
        return overlay
    }

    fun stats(parent: FrameLayout, p: PlayerProgress, onClose: () -> Unit): FrameLayout {
        val sb = StringBuilder()
        fun row(k: String, v: String) = sb.append("<b>").append(k).append("</b>&nbsp;&nbsp;&nbsp;").append(v).append("<br>")
        row("Best score", NumberFormat.formatScore(p.bestScore))
        row("Highest number", NumberFormat.formatLevel(p.highestLevel))
        row("Longest chain", p.bestChainLength.toString())
        row("Games played", p.gamesPlayed.toString())
        row("Cubes collected", "%,d".format(p.totalCubesCollected))
        row("Merges", "%,d".format(p.totalMerges))
        row("Rivals defeated", "%,d".format(p.opponentsDefeated))
        row("Play time", NumberFormat.formatDuration(p.totalPlayTimeSeconds))
        row("Longest game", NumberFormat.formatDuration(p.longestSessionSeconds))
        row("Coins", "%,d".format(p.coins))
        sb.append("<br>")
        val names = listOf("Classic Arena", "Endless Growth", "Survival Challenge")
        for (m in GameMode.values()) {
            val b = p.modeBest(m)
            sb.append("<font color='#FFC23D'><b>").append(names[m.ordinal]).append("</b></font><br>")
            sb.append("Best ").append(NumberFormat.formatScore(b.bestScore)).append(" • Top ").append(NumberFormat.formatLevel(b.highestLevel))
                .append(" • ").append(NumberFormat.formatDuration(b.longestSurvivalSeconds)).append("<br><br>")
        }
        return text(parent, "High Score & Stats", sb.toString(), onClose)
    }

    fun customize(parent: FrameLayout, save: SaveStore, onClose: () -> Unit): FrameLayout {
        lateinit var overlay: FrameLayout
        val p = save.progress
        val buttons = ArrayList<Pair<android.widget.TextView, com.oxdh.cubeclash.engine.UnlockItem>>()
        fun refresh() {
            for ((b, item) in buttons) {
                val unlocked = UnlockCatalog.isUnlocked(item, p)
                val sel = if (item.kind == UnlockKind.SKIN) p.selectedSkin == item.id else p.selectedTrail == item.id
                b.text = if (!unlocked) "Locked" else if (sel) "Selected" else "Select"
                Ui.setButtonColor(b, if (!unlocked) Ui.PANEL_LIGHT else if (sel) Ui.GOOD else Ui.PRIMARY)
                b.isEnabled = unlocked
            }
        }
        overlay = Ui.modal(parent, "Customize") { col ->
            val c = col.context
            col.addView(Ui.text(c, "Coins: %,d  (earned by playing)".format(p.coins), 15f, Ui.ACCENT), Ui.matchWrap())
            for (kind in UnlockKind.values()) {
                col.addView(Ui.header(c, if (kind == UnlockKind.SKIN) "Cube skins" else "Boost trails"))
                for (item in UnlockCatalog.items.filter { it.kind == kind }) {
                    val row = Ui.horizontal(c, 12f).apply { gravity = Gravity.CENTER_VERTICAL }
                    val img = ImageView(c)
                    if (kind == UnlockKind.SKIN) img.setImageBitmap(FacePainter.icon(item.id, 4, Ui.dpi(c, 56f)))
                    else img.background = Ui.rounded(Palette.trail(item.id, 0.4f), Ui.dp(c, 28f))
                    row.addView(img, LinearLayout.LayoutParams(Ui.dpi(c, 56f), Ui.dpi(c, 56f)))
                    val texts = Ui.vertical(c, 2f)
                    texts.addView(Ui.text(c, item.displayName, 17f, gravity = Gravity.START))
                    texts.addView(Ui.text(c, item.requirementText, 13f, Ui.TEXT_DIM, bold = false, gravity = Gravity.START))
                    row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    val b = Ui.button(c, "", Ui.PRIMARY, 14f) {
                        if (!UnlockCatalog.isUnlocked(item, p)) return@button
                        if (item.kind == UnlockKind.SKIN) p.selectedSkin = item.id else p.selectedTrail = item.id
                        save.save(); refresh()
                    }
                    buttons += b to item
                    row.addView(b, LinearLayout.LayoutParams(Ui.dpi(c, 110f), LinearLayout.LayoutParams.WRAP_CONTENT))
                    col.addView(row, Ui.matchWrap())
                }
            }
            col.addView(Ui.button(c, "Done", Ui.PRIMARY) { Ui.close(overlay); onClose() }, Ui.matchWrap())
        }
        refresh()
        return overlay
    }

    const val HOW_TO_PLAY =
        "<b>Move</b><br>Put a finger down anywhere and drag in the direction you want to go. Your snake never stops moving — you steer. Prefer pointing? Switch to <i>Point to steer</i> in Settings.<br><br>" +
        "<b>Collect</b><br>You start as a single cube showing <b>1</b>. Drive into cubes whose number is <b>not bigger than your leading cube</b> to eat them. Bigger cubes are solid and greyed out: ramming them only slows you down — steer around them.<br><br>" +
        "<b>Merge</b><br>Two neighbouring cubes with the same number merge into double: 1+1=2, 2+2=4, 4+4=8 … Your chain stays sorted, biggest at the front, and merges cascade one step at a time.<br><br>" +
        "<b>Boost</b><br>While steering with one thumb, hold a <b>second finger</b> anywhere to boost (or double-tap and hold). It drains the energy bar, which refills when you let go.<br><br>" +
        "<b>Eating snakes</b><br>Your number is your leading cube. With the front of your head you can eat any snake cube with a <b>smaller</b> number: bite the head to eat the whole snake, or the tail to eat it from there. Equal or bigger numbers are solid — you bounce off. Small snakes can nibble your small tail cubes from behind, so turn around fast and eat them!<br><br>" +
        "<b>Watch out</b><br>Name tags are <font color='#FF8C99'>red</font> on rivals bigger than you and <font color='#9CFFB3'>green</font> on rivals you can beat. The minimap uses the same colours.<br><br>" +
        "<b>Modes</b><br>Classic Arena: compete and survive.<br>Endless Growth: relaxed — you can't be eaten.<br>Survival Challenge: rivals get tougher every minute and boost recovers slowly.<br><br>" +
        "<b>Play with friends</b><br>Local: everyone on the same Wi-Fi or your hotspot. Online: create a room and share its code. In multiplayer you respawn after being eaten; the leaderboard shows everyone live."

    const val CREDITS =
        "<b>Cube Clash</b> — an original game inspired by number-merging snake .io games.<br><br>" +
        "Design, code, art and audio were created for this project. All cube textures, effects, sounds and music are generated by the game itself; no third-party assets are used.<br><br>" +
        "<b>Privacy</b><br>Cube Clash works fully offline. It needs no account, collects no personal data, contains no ads or analytics, and requests no Android permissions. Your progress and settings are stored only on this device and are removed when you uninstall the app or use Settings › Reset progress."
}
