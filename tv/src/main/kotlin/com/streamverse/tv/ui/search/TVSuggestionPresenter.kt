package com.streamverse.tv.ui.search

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.Presenter

/** A recent-or-popular search suggestion rendered on the TV search screen. */
data class TVSuggestion(val term: String, val popular: Boolean)

/**
 * Renders a search suggestion (recent or popular) as a focusable pill-style card, mirroring the
 * mobile app's Recent / Popular search rows. Clicking it re-runs the search for that term.
 */
class TVSuggestionPresenter : Presenter() {

    class SuggestionHolder(root: FrameLayout) : ViewHolder(root) {
        val icon: TextView = root.findViewWithTag("icon")
        val label: TextView = root.findViewWithTag("label")
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        val w = (300 * density).toInt()
        val h = (64 * density).toInt()

        val card = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(w, h)
            background = cardBg(ctx, focused = false)
            elevation = 3 * density
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((18 * density).toInt(), 0, (16 * density).toInt(), 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        val icon = TextView(ctx).apply {
            tag = "icon"
            textSize = 16f
            setTextColor(ACCENT_CYAN)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.marginEnd = (12 * density).toInt() }
        }
        row.addView(icon)

        val label = TextView(ctx).apply {
            tag = "label"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TVChannelPresenterColors.TEXT_PRIMARY)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(label)

        card.addView(row)

        card.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.10f else 1f)
                .scaleY(if (hasFocus) 1.10f else 1f)
                .setDuration(170)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            v.background = cardBg(v.context, hasFocus)
            v.elevation = (if (hasFocus) 16 else 3) * density
        }

        return SuggestionHolder(card)
    }

    override fun onBindViewHolder(vh: ViewHolder, item: Any?) {
        val h = vh as SuggestionHolder
        val s = item as? TVSuggestion ?: return
        h.icon.text = if (s.popular) "🔥" else "🕒" // 🔥 popular / 🕒 recent
        h.label.text = s.term
    }

    override fun onUnbindViewHolder(vh: ViewHolder) {}

    private fun cardBg(ctx: android.content.Context, focused: Boolean) = GradientDrawable().apply {
        cornerRadius = 14 * ctx.resources.displayMetrics.density
        setColor(if (focused) Color.parseColor("#14223A47") else Color.parseColor("#1A1A1A"))
        if (focused) {
            setStroke((2 * ctx.resources.displayMetrics.density).toInt(), ACCENT_CYAN)
        } else {
            setStroke(0, Color.TRANSPARENT)
        }
    }

    private object TVChannelPresenterColors {
        val TEXT_PRIMARY: Int = Color.parseColor("#F1F5F9")
    }

    private companion object {
        val ACCENT_CYAN: Int = Color.parseColor("#22D3EE")
    }
}
