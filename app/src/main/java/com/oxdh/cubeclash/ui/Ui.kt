package com.oxdh.cubeclash.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.Html
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Theme colours and small builders for the programmatic (XML-free) UI. */
object Ui {
    val PRIMARY = Color.rgb(79, 125, 255)
    val ACCENT = Color.rgb(255, 176, 33)
    val GOOD = Color.rgb(56, 199, 115)
    val DANGER = Color.rgb(255, 77, 94)
    val PANEL = Color.argb(225, 20, 26, 51)
    val PANEL_LIGHT = Color.argb(40, 255, 255, 255)
    val TEXT_DIM = Color.argb(185, 255, 255, 255)
    val OVERLAY = Color.argb(150, 8, 10, 26)
    val BOLD: Typeface = Typeface.create("sans-serif-black", Typeface.BOLD) ?: Typeface.DEFAULT_BOLD

    fun dp(c: Context, v: Float) = v * c.resources.displayMetrics.density
    fun dpi(c: Context, v: Float) = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radiusPx }

    fun text(c: Context, s: CharSequence, sizeSp: Float, color: Int = Color.WHITE, bold: Boolean = true, gravity: Int = Gravity.CENTER) =
        TextView(c).apply {
            text = s; textSize = sizeSp; setTextColor(color); this.gravity = gravity
            typeface = if (bold) BOLD else Typeface.DEFAULT
            setShadowLayer(dp(c, 2f), 0f, dp(c, 1.5f), Color.argb(110, 0, 0, 30))
        }

    @Suppress("DEPRECATION")
    fun html(s: String): CharSequence = Html.fromHtml(s)

    fun button(c: Context, label: String, color: Int, sizeSp: Float = 18f, onClick: (() -> Unit)? = null): TextView =
        text(c, label, sizeSp).apply {
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)), rounded(color, dp(c, 16f)), null)
            val pv = dpi(c, 12f); val ph = dpi(c, 14f)
            setPadding(ph, pv, ph, pv)
            minHeight = dpi(c, 52f)
            isClickable = true; isFocusable = true
            if (onClick != null) setOnClickListener { Sounds.click(); onClick() }
        }

    fun setButtonColor(b: TextView, color: Int) {
        b.background = RippleDrawable(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)), rounded(color, dp(b.context, 16f)), null)
    }

    fun vertical(c: Context, spacingDp: Float = 10f) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(1, dpi(c, spacingDp)); setColor(Color.TRANSPARENT) }
    }

    fun horizontal(c: Context, spacingDp: Float = 10f) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(dpi(c, spacingDp), 1); setColor(Color.TRANSPARENT) }
    }

    fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** Segmented selector; returns the container. */
    fun segmented(c: Context, label: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val col = vertical(c, 6f)
        if (label != null) col.addView(text(c, label, 14f, TEXT_DIM, gravity = Gravity.START), matchWrap())
        val row = horizontal(c, 8f)
        val buttons = ArrayList<TextView>()
        options.forEachIndexed { i, o ->
            val b = button(c, o, if (i == selected) PRIMARY else PANEL_LIGHT, 15f)
            b.setOnClickListener {
                Sounds.click()
                buttons.forEachIndexed { k, bb -> setButtonColor(bb, if (k == i) PRIMARY else PANEL_LIGHT) }
                onSelect(i)
            }
            buttons += b
            row.addView(b, weighted())
        }
        col.addView(row, matchWrap())
        return col
    }

    fun toggleRow(c: Context, label: String, get: () -> Boolean, set: (Boolean) -> Unit): LinearLayout {
        val row = horizontal(c, 10f).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(c, label, 16f, gravity = Gravity.START or Gravity.CENTER_VERTICAL), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        val b = button(c, if (get()) "ON" else "OFF", if (get()) GOOD else PANEL_LIGHT, 15f)
        b.setOnClickListener {
            Sounds.click()
            val v = !get(); set(v)
            b.text = if (v) "ON" else "OFF"; setButtonColor(b, if (v) GOOD else PANEL_LIGHT)
        }
        row.addView(b, weighted())
        return row
    }

    fun sliderRow(c: Context, label: String, min: Float, max: Float, value: Float, onChange: (Float) -> Unit): LinearLayout {
        val row = horizontal(c, 10f).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(c, label, 16f, gravity = Gravity.START or Gravity.CENTER_VERTICAL), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val bar = SeekBar(c).apply {
            this.max = 100
            progress = (((value - min) / (max - min)) * 100).toInt().coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(PRIMARY)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(min + (max - min) * p / 100f) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        row.addView(bar, LinearLayout.LayoutParams(0, dpi(c, 44f), 1.3f))
        return row
    }

    fun header(c: Context, s: String) = text(c, s.uppercase(), 14f, ACCENT, gravity = Gravity.START).apply { setPadding(0, dpi(c, 10f), 0, 0) }

    /**
     * Full-screen dimmed overlay with a centred card. [content] receives the scrollable column. Returns the overlay;
     * remove it from its parent to close.
     */
    fun modal(parent: FrameLayout, title: String, maxWidthDp: Float = 460f, scroll: Boolean = true, content: (LinearLayout) -> Unit): FrameLayout {
        val c = parent.context
        val overlay = FrameLayout(c).apply { setBackgroundColor(OVERLAY); isClickable = true }
        val card = vertical(c, 12f).apply {
            background = rounded(PANEL, dp(c, 24f))
            val p = dpi(c, 20f); setPadding(p, p, p, p)
        }
        card.addView(text(c, title, 28f), matchWrap())
        val body = vertical(c, 10f)
        content(body)
        if (scroll) {
            val sv = ScrollView(c).apply { isFillViewport = false; addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
            card.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else card.addView(body, matchWrap())
        val screenW = c.resources.displayMetrics.widthPixels
        val w = minOf(dpi(c, maxWidthDp), (screenW * 0.94f).toInt())
        val lp = FrameLayout.LayoutParams(w, if (scroll) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        val m = dpi(c, 24f); lp.setMargins(0, m, 0, m)
        overlay.addView(card, lp)
        parent.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.alpha = 0f; overlay.animate().alpha(1f).setDuration(150).start()
        return overlay
    }

    fun close(v: View) { (v.parent as? ViewGroup)?.removeView(v) }
}

/** Global click sound hook (set by the activity). */
object Sounds {
    var onClick: (() -> Unit)? = null
    fun click() { onClick?.invoke() }
}
