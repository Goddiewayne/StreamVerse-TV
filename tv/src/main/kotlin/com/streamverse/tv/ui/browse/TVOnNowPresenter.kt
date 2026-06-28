package com.streamverse.tv.ui.browse

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamverse.core.domain.model.Channel

/**
 * "On Now" rail card — wide cinematic tile framing a channel as *live right now*.
 *
 *  ┌────────────────────────────────────────────┐
 *  │ ● LIVE                           ╔══════╗  │
 *  │                                  ║ logo ║  │
 *  │  Channel Name                    ║      ║  │
 *  │  Category · Watch live           ╚══════╝  │
 *  └────────────────────────────────────────────┘
 */
class TVOnNowPresenter : Presenter() {

    companion object {
        const val CARD_WIDTH_DP  = 360
        const val CARD_HEIGHT_DP = 200
        private val COLOR_LIVE = Color.parseColor("#FF453A")
    }

    private val helper = TVChannelPresenter()

    class OnNowHolder(val root: FrameLayout) : ViewHolder(root) {
        val logo:     ImageView = root.findViewWithTag("logo")
        val name:     TextView  = root.findViewWithTag("name")
        val meta:     TextView  = root.findViewWithTag("meta")
        val liveDot:  View      = root.findViewWithTag("liveDot")
        var pulse:    ObjectAnimator? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = buildCard(parent.context)
        card.isFocusable = true
        card.isFocusableInTouchMode = true
        card.isClickable = true
        card.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.06f else 1f)
                .scaleY(if (hasFocus) 1.06f else 1f).setDuration(180).start()
            v.background = cardBg(v.context, hasFocus)
            v.elevation = dp(v.context, if (hasFocus) 16f else 2f)
        }
        return OnNowHolder(card)
    }

    override fun onBindViewHolder(vh: ViewHolder, item: Any?) {
        val h = vh as OnNowHolder
        val ch = item as? Channel ?: return

        h.name.text = ch.displayName
        h.meta.text = buildString {
            ch.category?.takeIf { it.isNotBlank() }?.let { append(it).append("  ·  ") }
            append("Watch live")
        }

        val model = com.streamverse.core.util.ChannelLogoResolver.model(ch)
        if (!model.isNullOrBlank()) {
            Glide.with(h.logo.context)
                .load(model)
                .transition(DrawableTransitionOptions.withCrossFade(200))
                .placeholder(ColorDrawable(TVChannelPresenter.COLOR_NAVY_CARD))
                .error(helper.letterDrawable(h.logo.context, ch.displayName))
                .fitCenter()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(h.logo)
        } else {
            Glide.with(h.logo.context).clear(h.logo)
            h.logo.setImageDrawable(helper.letterDrawable(h.logo.context, ch.displayName))
        }

        // Pulsing LIVE dot
        h.pulse?.cancel()
        h.liveDot.alpha = 1f
        h.pulse = ObjectAnimator.ofFloat(h.liveDot, "alpha", 1f, 0.25f).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    override fun onUnbindViewHolder(vh: ViewHolder) {
        val h = vh as OnNowHolder
        h.pulse?.cancel()
        h.pulse = null
        Glide.with(h.logo.context).clear(h.logo)
        h.logo.setImageDrawable(null)
    }

    // ---- Card layout ----------------------------------------------------------------

    private fun buildCard(ctx: Context): FrameLayout {
        val w = px(ctx, CARD_WIDTH_DP)
        val h = px(ctx, CARD_HEIGHT_DP)

        val card = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(w, h)
            background = cardBg(ctx, focused = false)
            elevation = dp(ctx, 2f)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }

        val logo = ImageView(ctx).apply {
            tag = "logo"
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setPadding(px(ctx, 6), px(ctx, 6), px(ctx, 6), px(ctx, 56))
        }
        card.addView(logo)

        val scrim = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, px(ctx, 68),
            ).also { it.gravity = Gravity.BOTTOM }
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#F0000000")),
            )
        }
        card.addView(scrim)

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            ).also {
                it.gravity = Gravity.BOTTOM or Gravity.START
                it.setMargins(px(ctx, 14), 0, px(ctx, 48), px(ctx, 12))
            }
        }
        textCol.addView(TextView(ctx).apply {
            tag = "name"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        textCol.addView(TextView(ctx).apply {
            tag = "meta"
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, px(ctx, 2), 0, 0)
        })
        card.addView(textCol)

        // LIVE badge (top-start): pulsing dot + static label on dark pill
        val livePill = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 20f)
                setColor(Color.parseColor("#AA000000"))
            }
            setPadding(px(ctx, 8), px(ctx, 3), px(ctx, 8), px(ctx, 3))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            ).also {
                it.gravity = Gravity.TOP or Gravity.START
                it.setMargins(px(ctx, 12), px(ctx, 12), 0, 0)
            }
        }
        livePill.addView(View(ctx).apply {
            tag = "liveDot"
            layoutParams = LinearLayout.LayoutParams(px(ctx, 6), px(ctx, 6)).also {
                it.marginEnd = px(ctx, 4)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_LIVE)
            }
        })
        livePill.addView(TextView(ctx).apply {
            text = "LIVE"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_LIVE)
        })
        card.addView(livePill)

        return card
    }

    private fun cardBg(ctx: Context, focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(ctx, 12f)
        setColor(TVChannelPresenter.COLOR_NAVY_CARD)
        if (focused) setStroke(px(ctx, 2), Color.parseColor("#44FFFFFF"))
        else setStroke(0, Color.TRANSPARENT)
    }

    private fun px(ctx: Context, dp: Int) = (dp * ctx.resources.displayMetrics.density).toInt()
    private fun dp(ctx: Context, dp: Float) = dp * ctx.resources.displayMetrics.density
}
