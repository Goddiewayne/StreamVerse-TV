package com.streamverse.tv.ui.browse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamverse.core.data.sourceProviderCount
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.Quality
import com.streamverse.core.util.ChannelLogoResolver

class TVChannelPresenter(
    private val recentIds: Set<String> = emptySet(),
    private val isFavorite: (Channel) -> Boolean = { false },
    private val onToggleFavorite: ((Channel) -> Unit)? = null,
    // Live Availability Index lookup — drives the LIVE badge. Read fresh on each bind so a
    // notifyItemChanged() refreshes the badge without rebuilding rows.
    private val isLive: (Channel) -> Boolean = { false },
) : Presenter() {

    companion object {
        const val CARD_WIDTH_DP  = 300
        const val CARD_HEIGHT_DP = 180

        val COLOR_NAVY_CARD      = Color.parseColor("#1A1A1A")
        val COLOR_DEEP_SPACE     = Color.parseColor("#000000")
        val COLOR_CYBER_CYAN     = Color.parseColor("#22D3EE")
        val COLOR_CYBER_CYAN_DK  = Color.parseColor("#0891B2")
        val COLOR_VIOLET         = Color.parseColor("#818CF8")
        val COLOR_VIOLET_DK      = Color.parseColor("#4F46E5")
        val COLOR_TEXT_PRIMARY   = Color.parseColor("#F1F5F9")
        val COLOR_TEXT_SECONDARY = Color.parseColor("#94A3B8")
        val COLOR_SPACE_NAVY     = Color.parseColor("#0A0A0A")
        val COLOR_FAV_HEART      = Color.parseColor("#F43F5E")
        val COLOR_FAV_HEART_BG   = Color.parseColor("#1A000000")

        private val letterCache = android.util.LruCache<String, BitmapDrawable>(80)

        private val AVATAR_GRADIENTS = arrayOf(
            intArrayOf(Color.parseColor("#0EA5E9"), Color.parseColor("#6366F1")),
            intArrayOf(Color.parseColor("#22D3EE"), Color.parseColor("#0891B2")),
            intArrayOf(Color.parseColor("#818CF8"), Color.parseColor("#4F46E5")),
            intArrayOf(Color.parseColor("#F43F5E"), Color.parseColor("#9333EA")),
            intArrayOf(Color.parseColor("#10B981"), Color.parseColor("#0891B2")),
            intArrayOf(Color.parseColor("#F59E0B"), Color.parseColor("#EA580C")),
            intArrayOf(Color.parseColor("#06B6D4"), Color.parseColor("#3B82F6")),
        )
    }

    class CardHolder(val root: FrameLayout) : ViewHolder(root) {
        val backdrop:      View      = root.findViewWithTag("backdrop")
        val blur:          ImageView = root.findViewWithTag("blur")
        val logo:          ImageView = root.findViewWithTag("logo")
        val name:          TextView  = root.findViewWithTag("name")
        val quality:       TextView  = root.findViewWithTag("quality")
        val srcCount:      TextView  = root.findViewWithTag("srcs")
        val liveBadge:     TextView  = root.findViewWithTag("live")
        val watchedStrip:  View      = root.findViewWithTag("watched")
        val favIcon:       TextView  = root.findViewWithTag("favIcon")
        val glowOverlay:   View      = root.findViewWithTag("glow")
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val card = buildCard(ctx)
        card.isFocusable = true
        card.isFocusableInTouchMode = true
        card.isClickable = true

        card.setOnFocusChangeListener { v, hasFocus ->
            // Bold, unmistakable focus: the card jumps forward (scale + Z) with a bright cyan ring,
            // so the active tile reads clearly from across the room even amid dozens of cards.
            v.animate().scaleX(if (hasFocus) 1.16f else 1f)
                .scaleY(if (hasFocus) 1.16f else 1f)
                .setDuration(170)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            v.background = cardBg(v.context, hasFocus)
            v.elevation = dp(v.context, if (hasFocus) 32f else 2f)
            v.translationZ = dp(v.context, if (hasFocus) 12f else 0f) // draw above neighbours
            val glow = v.findViewWithTag<View>("glow")
            glow?.animate()?.alpha(if (hasFocus) 1f else 0f)?.setDuration(170)?.start()
            try {
                val audio = v.context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                if (hasFocus) audio.playSoundEffect(android.media.AudioManager.FX_KEYPRESS_STANDARD, 0.3f)
            } catch (_: Exception) {}
        }
        return CardHolder(card)
    }

    override fun onBindViewHolder(vh: ViewHolder, item: Any?) {
        val h = vh as CardHolder
        val ch = item as? Channel ?: return

        h.name.text = ch.displayName

        val grad = AVATAR_GRADIENTS[Math.abs(ch.displayName.hashCode()) % AVATAR_GRADIENTS.size]
        h.backdrop.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(dim(grad[0], 0.45f), dim(grad[1], 0.45f)),
        )

        val q = ch.quality
        if (q != null) {
            h.quality.text = qualityLabel(q)
            h.quality.background = qualityBg(h.quality.context, q)
            h.quality.setTextColor(if (q == Quality.SD) COLOR_TEXT_SECONDARY else Color.BLACK)
            h.quality.visibility = View.VISIBLE
        } else {
            h.quality.visibility = View.GONE
        }

        val n = ch.sourceProviderCount()
        if (n > 1) {
            h.srcCount.text = "$n"
            h.srcCount.visibility = View.VISIBLE
        } else {
            h.srcCount.visibility = View.GONE
        }

        h.liveBadge.visibility = if (isLive(ch)) View.VISIBLE else View.GONE

        h.watchedStrip.visibility = if (ch.id in recentIds) View.VISIBLE else View.GONE

        // Favorite icon
        val fav = isFavorite(ch)
        h.favIcon.text = if (fav) "\u2764" else "\u2661"
        h.favIcon.setTextColor(if (fav) COLOR_FAV_HEART else Color.argb(128, 255, 255, 255))
        h.favIcon.visibility = if (onToggleFavorite != null) View.VISIBLE else View.GONE
        h.favIcon.setOnClickListener {           // touch devices (Fire TV touch remotes)
            onToggleFavorite?.invoke(ch)
        }

        // Remote-friendly favouriting: the heart is not DPAD-reachable, so long-press the focused
        // card to toggle favourite. Short press still plays (the fragment's click listener fires
        // only when long-press doesn't consume the event).
        val toggle = onToggleFavorite
        if (toggle != null) {
            h.root.setOnLongClickListener {
                val nowFav = !isFavorite(ch)
                toggle.invoke(ch)
                android.widget.Toast.makeText(
                    h.root.context,
                    if (nowFav) "\u2665 Added to Favourites" else "Removed from Favourites",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                true
            }
        } else {
            h.root.setOnLongClickListener(null)
            h.root.isLongClickable = false
        }

        val model = ChannelLogoResolver.model(ch)
        if (!model.isNullOrBlank()) {
            Glide.with(h.logo.context)
                .load(model)
                .transition(DrawableTransitionOptions.withCrossFade(200))
                .placeholder(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                .error(letterDrawable(h.logo.context, ch.displayName))
                .fitCenter()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(h.logo)
            h.blur.visibility = View.VISIBLE
            Glide.with(h.blur.context)
                .load(model)
                .transform(DownsampleBlurTransformation())
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(h.blur)
        } else {
            Glide.with(h.logo.context).clear(h.logo)
            Glide.with(h.blur.context).clear(h.blur)
            h.blur.visibility = View.GONE
            h.logo.setImageDrawable(letterDrawable(h.logo.context, ch.displayName))
        }
    }

    override fun onUnbindViewHolder(vh: ViewHolder) {
        val h = vh as CardHolder
        Glide.with(h.logo.context).clear(h.logo)
        Glide.with(h.blur.context).clear(h.blur)
        h.logo.setImageDrawable(null)
        h.blur.setImageDrawable(null)
    }

    private fun buildCard(ctx: Context): FrameLayout {
        val w = dpToPx(ctx, CARD_WIDTH_DP)
        val h = dpToPx(ctx, CARD_HEIGHT_DP)

        val card = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(w, h)
            background = cardBg(ctx, focused = false)
            elevation = dp(ctx, 3f)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }

        val backdrop = View(ctx).apply {
            tag = "backdrop"
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        card.addView(backdrop)

        val blur = ImageView(ctx).apply {
            tag = "blur"
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.6f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        card.addView(blur)

        val scrimFull = View(ctx).apply {
            setBackgroundColor(Color.parseColor("#30080C14"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        card.addView(scrimFull)

        val glow = View(ctx).apply {
            tag = "glow"
            alpha = 0f
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14f)
                setStroke(dpToPx(ctx, 4), COLOR_CYBER_CYAN)
                setColor(Color.TRANSPARENT)
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        card.addView(glow)

        val logo = ImageView(ctx).apply {
            tag = "logo"
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setPadding(dpToPx(ctx, 8), dpToPx(ctx, 8), dpToPx(ctx, 8), dpToPx(ctx, 36))
        }
        card.addView(logo)

        val scrim = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dpToPx(ctx, 48),
            ).also { it.gravity = Gravity.BOTTOM }
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#F0000000")),
            )
        }
        card.addView(scrim)

        val name = TextView(ctx).apply {
            tag = "name"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).also {
                it.gravity = Gravity.BOTTOM or Gravity.START
                it.setMargins(dpToPx(ctx, 10), 0, dpToPx(ctx, 10), dpToPx(ctx, 10))
            }
        }
        card.addView(name)

        val quality = TextView(ctx).apply {
            tag = "quality"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(ctx, 5), dpToPx(ctx, 2), dpToPx(ctx, 5), dpToPx(ctx, 2))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).also {
                it.gravity = Gravity.TOP or Gravity.END
                it.setMargins(0, dpToPx(ctx, 8), dpToPx(ctx, 8), 0)
            }
            visibility = View.GONE
        }
        card.addView(quality)

        val srcs = TextView(ctx).apply {
            tag = "srcs"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#999999"))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(ctx, 20).toFloat()
                setColor(Color.parseColor("#33000000"))
            }
            setPadding(dpToPx(ctx, 5), dpToPx(ctx, 2), dpToPx(ctx, 5), dpToPx(ctx, 2))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).also {
                it.gravity = Gravity.BOTTOM or Gravity.END
                it.setMargins(0, 0, dpToPx(ctx, 8), dpToPx(ctx, 8))
            }
            visibility = View.GONE
        }
        card.addView(srcs)

        // LIVE badge top-left (broadcast style) — shown only for verified-available channels.
        val live = TextView(ctx).apply {
            tag = "live"
            text = "● LIVE"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dpToPx(ctx, 6), dpToPx(ctx, 2), dpToPx(ctx, 6), dpToPx(ctx, 2))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(ctx, 4).toFloat()
                setColor(Color.parseColor("#E11D48"))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).also {
                it.gravity = Gravity.TOP or Gravity.START
                it.setMargins(dpToPx(ctx, 8), dpToPx(ctx, 8), 0, 0)
            }
            visibility = View.GONE
        }
        card.addView(live)

        // Favorite heart icon top-right
        val favIcon = TextView(ctx).apply {
            tag = "favIcon"
            textSize = 18f
            gravity = Gravity.CENTER
            isFocusable = false          // toggled via long-press (remote) / tap (touch), never DPAD-focused
            setTextColor(Color.argb(128, 255, 255, 255))
            text = "\u2661"
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(ctx, 12).toFloat()
                setColor(Color.parseColor("#33000000"))
            }
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(ctx, 26), dpToPx(ctx, 26),
            ).also {
                it.gravity = Gravity.TOP or Gravity.END
                it.setMargins(0, dpToPx(ctx, 6), dpToPx(ctx, 6), 0)
            }
            visibility = View.GONE
        }
        card.addView(favIcon)

        val watchedStrip = View(ctx).apply {
            tag = "watched"
            background = GradientDrawable().apply {
                cornerRadius = 0f
                setColor(Color.WHITE)
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dpToPx(ctx, 2),
            ).also { it.gravity = Gravity.BOTTOM }
            visibility = View.GONE
        }
        card.addView(watchedStrip)

        return card
    }

    private fun cardBg(ctx: Context, focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(ctx, 12f)
        if (focused) {
            setColor(Color.parseColor("#2AFFFFFF"))
            setStroke(dpToPx(ctx, 2), Color.parseColor("#44FFFFFF"))
        } else {
            setColor(COLOR_NAVY_CARD)
            setStroke(0, Color.TRANSPARENT)
        }
    }

    private fun qualityBg(ctx: Context, quality: Quality) = GradientDrawable().apply {
        cornerRadius = dp(ctx, 20f)
        setColor(when (quality) {
            Quality._4K -> COLOR_CYBER_CYAN
            Quality.FHD -> COLOR_VIOLET
            Quality.HD  -> COLOR_CYBER_CYAN_DK
            Quality.SD  -> Color.parseColor("#334155")
        })
    }

    private fun qualityLabel(q: Quality) = when (q) {
        Quality._4K -> "4K"; Quality.FHD -> "FHD"; Quality.HD -> "HD"; Quality.SD -> "SD"
    }

    fun letterDrawable(ctx: Context, name: String): BitmapDrawable {
        val gradIdx = Math.abs(name.hashCode()) % AVATAR_GRADIENTS.size
        val initials = monogramInitials(name)
        val cacheKey = "$initials:$gradIdx"
        letterCache[cacheKey]?.let { return it }

        val size = 240
        val radius = size * 0.16f
        val (c1, c2) = AVATAR_GRADIENTS[gradIdx].let { it[0] to it[1] }

        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                c1, c2, android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = if (initials.length > 1) size * 0.34f else size * 0.46f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val yOffset = -(textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(initials, size / 2f, size / 2f + yOffset, textPaint)

        return BitmapDrawable(ctx.resources, bmp).also { letterCache.put(cacheKey, it) }
    }

    private fun monogramInitials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> "${words[0].first()}${words[1].first()}".uppercase()
        }
    }

    private fun dim(color: Int, factor: Float): Int {
        val a = (factor.coerceIn(0f, 1f) * 255).toInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    internal fun dpToPx(ctx: Context, dp: Int) = (dp * ctx.resources.displayMetrics.density).toInt()
    internal fun dp(ctx: Context, dp: Float) = dp * ctx.resources.displayMetrics.density
}
