package com.streamverse.tv.ui.browse

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamverse.core.domain.model.Channel

/**
 * Hero-sized featured card presenter (16:9 ratio, 380 × 214 dp) — used for the
 * "Featured" spotlight row at the top of the browse screen. Larger cards give premium
 * channels cinematic presence, mirroring the mobile app's HeroPager.
 *
 * Card anatomy:
 *  ┌──────────────────────────────────────────────────────┐
 *  │                                         [ ▶ LIVE ]  │  ← LIVE badge (top-right)
 *  │                                                      │
 *  │              channel logo / thumbnail                │  ← fills card
 *  │                                                      │
 *  ├──────────────────────────────────────────────────────┤  ← gradient scrim
 *  │ CATEGORY                                             │  ← cyan label
 *  │ Channel Name                                         │  ← bold, 18sp
 *  └──────────────────────────────────────────────────────┘
 */
class TVFeaturedPresenter : Presenter() {

    companion object {
        private const val CARD_WIDTH_DP  = 380
        private const val CARD_HEIGHT_DP = 214 // exactly 16:9 → 380 × 213.75 ≈ 214
    }

    private val base = TVChannelPresenter() // reuse helpers

    // ---- ViewHolder -----------------------------------------------------------------

    class FeaturedHolder(val root: FrameLayout) : ViewHolder(root) {
        val logo:     ImageView = root.findViewWithTag("logo")
        val category: TextView  = root.findViewWithTag("category")
        val name:     TextView  = root.findViewWithTag("name")
        val liveBadge: TextView = root.findViewWithTag("live")
    }

    // ---- Presenter contract ---------------------------------------------------------

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx  = parent.context
        val card = buildCard(ctx)
        card.isFocusable          = true
        card.isFocusableInTouchMode = true
        card.isClickable          = true
        card.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.05f else 1f)
                .scaleY(if (hasFocus) 1.05f else 1f)
                .setDuration(180).start()
            v.background = cardBg(v.context, hasFocus)
            v.elevation  = base.dp(v.context, if (hasFocus) 20f else 4f)
        }
        return FeaturedHolder(card)
    }

    override fun onBindViewHolder(vh: ViewHolder, item: Any?) {
        val h  = vh as FeaturedHolder
        val ch = item as? Channel ?: return

        h.name.text     = ch.displayName
        h.category.text = ch.category?.uppercase() ?: ""
        h.category.visibility = if (ch.category.isNullOrBlank()) View.GONE else View.VISIBLE

        val fallback = base.letterDrawable(h.logo.context, ch.displayName)
        val url      = ch.logoUrl
        if (!url.isNullOrBlank()) {
            Glide.with(h.logo.context)
                .load(url)
                .transition(DrawableTransitionOptions.withCrossFade(300))
                .placeholder(fallback)
                .error(fallback)
                .fitCenter()
                .into(h.logo)
        } else {
            h.logo.setImageDrawable(fallback)
        }
    }

    override fun onUnbindViewHolder(vh: ViewHolder) {
        val h = vh as FeaturedHolder
        Glide.with(h.logo.context).clear(h.logo)
        h.logo.setImageDrawable(null)
    }

    // ---- Card layout ----------------------------------------------------------------

    private fun buildCard(ctx: Context): FrameLayout {
        val w = base.dpToPx(ctx, CARD_WIDTH_DP)
        val h = base.dpToPx(ctx, CARD_HEIGHT_DP)

        val card = FrameLayout(ctx).apply {
            layoutParams    = ViewGroup.LayoutParams(w, h)
            background      = cardBg(ctx, focused = false)
            elevation       = base.dp(ctx, 4f)
            clipToOutline   = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }

        // Full-bleed channel logo
        val logo = ImageView(ctx).apply {
            tag       = "logo"
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setPadding(
                base.dpToPx(ctx, 10), base.dpToPx(ctx, 8),
                base.dpToPx(ctx, 10), base.dpToPx(ctx, 52),
            )
        }
        card.addView(logo)

        val scrim = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                base.dpToPx(ctx, 88),
            ).also { it.gravity = Gravity.BOTTOM }
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#F0000000")),
            )
        }
        card.addView(scrim)

        // Info block at the bottom
        val info = LinearLayout(ctx).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).also {
                it.gravity = Gravity.BOTTOM or Gravity.START
                it.setMargins(
                    base.dpToPx(ctx, 14), 0,
                    base.dpToPx(ctx, 14), base.dpToPx(ctx, 12),
                )
            }
        }

        val category = TextView(ctx).apply {
            tag      = "category"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TVChannelPresenter.COLOR_CYBER_CYAN)
            letterSpacing = 0.12f
        }
        info.addView(category)

        val name = TextView(ctx).apply {
            tag      = "name"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TVChannelPresenter.COLOR_TEXT_PRIMARY)
            maxLines  = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        info.addView(name)
        card.addView(info)

        // LIVE badge (top-right)
        val live = TextView(ctx).apply {
            tag      = "live"
            text     = "▶ LIVE"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = base.dpToPx(ctx, 20).toFloat()
                setColor(Color.parseColor("#AA000000"))
                setStroke(0, Color.TRANSPARENT)
            }
            setPadding(
                base.dpToPx(ctx, 8), base.dpToPx(ctx, 4),
                base.dpToPx(ctx, 8), base.dpToPx(ctx, 4),
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).also {
                it.gravity = Gravity.TOP or Gravity.END
                it.setMargins(0, base.dpToPx(ctx, 10), base.dpToPx(ctx, 10), 0)
            }
        }
        card.addView(live)

        return card
    }

    private fun cardBg(ctx: Context, focused: Boolean) = GradientDrawable().apply {
        cornerRadius = base.dp(ctx, 12f)
        setColor(TVChannelPresenter.COLOR_NAVY_CARD)
        if (focused) {
            setStroke(base.dpToPx(ctx, 2), Color.parseColor("#44FFFFFF"))
        } else {
            setStroke(0, Color.TRANSPARENT)
        }
    }
}
