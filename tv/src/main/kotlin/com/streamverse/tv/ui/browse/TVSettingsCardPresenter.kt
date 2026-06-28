package com.streamverse.tv.ui.browse

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.Presenter

class TVSettingsCardPresenter(
    private val mainText: String = "\u2699  Settings",
    private val subText: String = "Sources \u00b7  Playback  \u00b7  About",
) : Presenter() {

    class SettingsHolder(root: FrameLayout) : ViewHolder(root) {
        var titleView: TextView? = null
        var subView: TextView? = null
        var chevronView: TextView? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        val w = (400 * density).toInt()
        val h = (90 * density).toInt()

        val card = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(w, h)
            background = navyCardBg(ctx, focused = false)
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
            setPadding(
                (22 * density).toInt(), 0,
                (18 * density).toInt(), 0,
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(ctx).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TVChannelPresenter.COLOR_TEXT_PRIMARY)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = (3 * density).toInt() }
        }
        textCol.addView(title)

        val sub = TextView(ctx).apply {
            textSize = 12f
            setTextColor(TVChannelPresenter.COLOR_TEXT_SECONDARY)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        textCol.addView(sub)
        row.addView(textCol)

        val chevron = TextView(ctx).apply {
            text = "›"            // ›  — signals "opens / cycles"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TVChannelPresenter.COLOR_TEXT_SECONDARY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.marginStart = (12 * density).toInt() }
        }
        row.addView(chevron)

        card.addView(row)

        card.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.10f else 1f)
                .scaleY(if (hasFocus) 1.10f else 1f)
                .setDuration(170)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            v.background = navyCardBg(ctx, hasFocus)
            v.elevation = (if (hasFocus) 16 else 3) * density
            chevron.setTextColor(
                if (hasFocus) ACCENT_CYAN else TVChannelPresenter.COLOR_TEXT_SECONDARY,
            )
        }

        return SettingsHolder(card).also { h ->
            h.titleView = title
            h.subView = sub
            h.chevronView = chevron
        }
    }

    override fun onBindViewHolder(vh: Presenter.ViewHolder, item: Any?) {
        val h = vh as SettingsHolder
        h.titleView?.text = mainText
        h.subView?.text = subText
    }

    override fun onUnbindViewHolder(vh: Presenter.ViewHolder) {}

    private fun navyCardBg(ctx: android.content.Context, focused: Boolean) =
        GradientDrawable().apply {
            cornerRadius = 14 * ctx.resources.displayMetrics.density
            setColor(if (focused) Color.parseColor("#14223A47") else TVChannelPresenter.COLOR_NAVY_CARD)
            if (focused) {
                setStroke((2 * ctx.resources.displayMetrics.density).toInt(), ACCENT_CYAN)
            } else {
                setStroke(0, Color.TRANSPARENT)
            }
        }

    private companion object {
        val ACCENT_CYAN: Int = Color.parseColor("#22D3EE")
    }
}
