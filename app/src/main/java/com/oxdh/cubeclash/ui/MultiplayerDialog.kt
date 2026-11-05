package com.oxdh.cubeclash.ui

import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.oxdh.cubeclash.MainActivity
import com.oxdh.cubeclash.engine.net.Lan
import com.oxdh.cubeclash.engine.net.Online

/**
 * "Play with friends": create or join a room on the local network (same Wi-Fi or a phone hotspot) or online with a
 * 6-character room code. All rooms use the same authoritative-host game; only the connection differs.
 */
object MultiplayerDialog {
    fun open(parent: FrameLayout, activity: MainActivity, onClose: () -> Unit): FrameLayout {
        val s = activity.settings
        lateinit var overlay: FrameLayout
        overlay = Ui.modal(parent, "Play with Friends") { col ->
            val c = col.context
            fun note(t: String) = Ui.text(c, t, 13f, Ui.TEXT_DIM, bold = false, gravity = Gravity.START)
            val name = Ui.editText(c, s.playerName, "Your name")
            col.addView(Ui.text(c, "Your name", 14f, Ui.TEXT_DIM, gravity = Gravity.START), Ui.matchWrap())
            col.addView(name, Ui.matchWrap())
            fun saveName() { s.playerName = name.text.toString().ifBlank { s.playerName } }
            col.addView(Ui.segmented(c, "Bots in your room", listOf("None", "3", "6"), when (s.bots) { 0 -> 0; 6 -> 2; else -> 1 }) {
                s.bots = listOf(0, 3, 6)[it]
            }, Ui.matchWrap())

            val status = note("")

            // ---------------- Local
            col.addView(Ui.header(c, "Local — same Wi-Fi or hotspot"))
            col.addView(note("Everyone joins the same Wi-Fi, or friends connect to your phone's hotspot. No internet needed."), Ui.matchWrap())
            val localRow = Ui.horizontal(c, 10f)
            val rooms = Ui.vertical(c, 8f)
            localRow.addView(Ui.button(c, "Create local room", Ui.GOOD, 15f) {
                saveName(); Ui.close(overlay); activity.hostLocal(s.bots)
            }, Ui.weighted())
            localRow.addView(Ui.button(c, "Find rooms", Ui.PRIMARY, 15f) {
                saveName()
                status.text = "Searching for rooms on this network…"
                rooms.removeAllViews()
                activity.findLocalRooms { found ->
                    rooms.removeAllViews()
                    status.text = if (found.isEmpty()) "No rooms found. Make sure you are on the same Wi-Fi / hotspot, or join by address below." else "Rooms nearby:"
                    for (r in found) {
                        val row = Ui.horizontal(c, 10f).apply { gravity = Gravity.CENTER_VERTICAL }
                        row.addView(Ui.text(c, "${r.name}\n${r.players} playing • ${r.address}", 14f, gravity = Gravity.START), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        row.addView(Ui.button(c, "Join", Ui.GOOD, 15f) { Ui.close(overlay); activity.joinLocal(r.address, r.port) },
                            LinearLayout.LayoutParams(Ui.dpi(c, 96f), LinearLayout.LayoutParams.WRAP_CONTENT))
                        rooms.addView(row, Ui.matchWrap())
                    }
                }
            }, Ui.weighted())
            col.addView(localRow, Ui.matchWrap())
            col.addView(status, Ui.matchWrap())
            col.addView(rooms, Ui.matchWrap())
            val addrRow = Ui.horizontal(c, 10f)
            val addr = Ui.editText(c, "", "Host address, e.g. 192.168.1.20")
            addrRow.addView(addr, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addrRow.addView(Ui.button(c, "Join", Ui.PRIMARY, 15f) {
                saveName()
                val text = addr.text.toString().trim()
                if (text.isEmpty()) { status.text = "Type the address shown on the host's screen."; return@button }
                val host = text.substringBefore(':'); val port = text.substringAfter(':', "").toIntOrNull() ?: Lan.DEFAULT_PORT
                Ui.close(overlay); activity.joinLocal(host, port)
            }, LinearLayout.LayoutParams(Ui.dpi(c, 96f), LinearLayout.LayoutParams.WRAP_CONTENT))
            col.addView(addrRow, Ui.matchWrap())

            // ---------------- Online
            col.addView(Ui.header(c, "Online — friends anywhere"))
            col.addView(note("Create a private room and share its 6-character code. Uses a free public relay server; needs internet on every phone."), Ui.matchWrap())
            val onlineStatus = note("")
            col.addView(Ui.button(c, "Create online room", Ui.GOOD, 16f) {
                saveName()
                onlineStatus.text = "Creating room…"
                activity.hostOnline(s.bots) { error -> onlineStatus.text = error }
            }, Ui.matchWrap())
            val joinRow = Ui.horizontal(c, 10f)
            val code = Ui.editText(c, "", "Room code", caps = true)
            joinRow.addView(code, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            joinRow.addView(Ui.button(c, "Join", Ui.PRIMARY, 15f) {
                saveName()
                val k = Online.normalize(code.text.toString())
                if (!Online.isValidCode(k)) { onlineStatus.text = "Room codes have 6 letters/digits, e.g. K7Q2PX."; return@button }
                Ui.close(overlay); activity.joinOnline(k)
            }, LinearLayout.LayoutParams(Ui.dpi(c, 96f), LinearLayout.LayoutParams.WRAP_CONTENT))
            col.addView(joinRow, Ui.matchWrap())
            col.addView(onlineStatus, Ui.matchWrap())

            col.addView(Ui.button(c, "Close", Ui.PANEL_LIGHT) { saveName(); Ui.close(overlay); onClose() }, Ui.matchWrap())
        }
        return overlay
    }

    /** Small helper for status lines updated from callbacks. */
    fun setText(v: TextView, s: String) { v.post { v.text = s } }
}
