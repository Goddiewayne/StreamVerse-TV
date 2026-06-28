package com.streamverse.tv.ui.browse

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamverse.core.domain.model.Channel

/**
 * Netflix-style full-bleed hero billboard with auto-cycling channels.
 *
 *   ┌──────────────────────────────────────────────────────┐
 *   │                                                      │
 *   │                                                      │
 *   │                   channel logo                       │
 *   │                   (centered)                         │
 *   │                                                      │
 *   │  ──── gradient scrim ──────────────────────────────  │
 *   │  CATEGORY                              ● ● ○ ○ ○    │
 *   │  Channel Name (28sp)                                 │
 *   │  [ Watch Now ]                                       │
 *   └──────────────────────────────────────────────────────┘
 */
class TVBillboardView(context: Context) : FrameLayout(context) {

    private val helper  = TVChannelPresenter()
    private val handler = Handler(Looper.getMainLooper())
    private val advance = Runnable { nextChannel() }

    private var channels: List<Channel> = emptyList()
    private var index   = 0

    var currentChannel: Channel? = null
        private set

    var onWatchClick: ((Channel) -> Unit)? = null

    private val logoView:      ImageView
    private val categoryLabel: TextView
    private val nameLabel:     TextView
    private val watchBtn:      TextView
    private val dotsRow:       LinearLayout

    init {
        setBackgroundColor(Color.parseColor("#000000"))
        isFocusable          = true
        isFocusableInTouchMode = true
        isClickable          = true
        setOnClickListener { currentChannel?.let { ch -> onWatchClick?.invoke(ch) } }

        // Full-bleed centered logo
        logoView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(24), dp(16), dp(24), dp(80))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(logoView)

        // Bottom gradient overlay: transparent → black
        val bottomScrim = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#00000000"), Color.parseColor("#FF000000")),
            )
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(140)).also {
                it.gravity = Gravity.BOTTOM
            }
        }
        addView(bottomScrim)

        // Bottom content: info (category, name, compact Watch Now) stacked ABOVE a centered carousel.
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.BOTTOM
            }
            setPadding(dp(32), dp(20), dp(32), dp(18))
        }

        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        categoryLabel = TextView(context).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#22D3EE"))
            letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = dp(4) }
        }
        infoCol.addView(categoryLabel)

        nameLabel = TextView(context).apply {
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(6f, 0f, 2f, Color.parseColor("#80000000"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = dp(10) }
        }
        infoCol.addView(nameLabel)

        watchBtn = TextView(context).apply {
            text = "▶   Watch Now"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.02f
            isFocusable = false
            isClickable = false
            setTextColor(Color.parseColor("#08090C"))
            background = watchBtnBg(focused = false)
            setPadding(dp(26), dp(11), dp(28), dp(11))
            elevation = dp(3f)
            // Compact round pill — wraps its text, never stretches toward the carousel.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        infoCol.addView(watchBtn)

        bottomRow.addView(infoCol)

        // Centered dots carousel, beneath the button.
        dotsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(14) }
        }
        bottomRow.addView(dotsRow)

        addView(bottomRow)

        // Focus: the whole hero lifts slightly and the Watch Now CTA pops forward with a cyan
        // accent ring — so it clearly reads as "press OK to watch", not a passive label.
        setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .setDuration(180).start()
            watchBtn.background = watchBtnBg(hasFocus)
            watchBtn.animate()
                .scaleX(if (hasFocus) 1.06f else 1f)
                .scaleY(if (hasFocus) 1.06f else 1f)
                .setDuration(180).start()
            // Pause auto-cycle while focused so manual ◄/► browsing doesn't fight the timer.
            if (hasFocus) stop() else scheduleAdvance()
        }

        // ◄/► step through featured channels while focused. LEFT at the first item is NOT consumed
        // so focus can still escape back to the header rail.
        setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { cycleBy(+1); true }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                    if (index > 0) { cycleBy(-1); true } else false
                else -> false
            }
        }
    }

    /** Manual ◄/► browse — flips featured channel without re-arming the auto-cycle timer
     *  (auto-advance stays paused while the hero is focused). */
    private fun cycleBy(delta: Int) {
        if (channels.size < 2) return
        index = ((index + delta) % channels.size + channels.size) % channels.size
        showChannel(animate = true)
    }

    /** Watch Now pill: solid white, gaining a cyan accent ring + lift when the hero is focused. */
    private fun watchBtnBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(26f)
        setColor(Color.WHITE)
        if (focused) setStroke(dp(3), Color.parseColor("#22D3EE"))
    }

    // ---- Public API -----------------------------------------------------------------

    fun setChannels(list: List<Channel>) {
        if (list.isEmpty()) return
        channels = list
        index    = 0
        buildDots()
        showChannel(animate = false)
        scheduleAdvance()
    }

    fun stop() {
        handler.removeCallbacks(advance)
    }

    // ---- Internal -------------------------------------------------------------------

    private fun nextChannel() {
        index = (index + 1) % channels.size
        showChannel(animate = true)
        scheduleAdvance()
    }

    private fun showChannel(animate: Boolean) {
        val ch = channels[index]
        currentChannel = ch

        val bind = Runnable {
            nameLabel.text     = ch.displayName
            categoryLabel.text = ch.category?.uppercase() ?: ""
            categoryLabel.visibility = if (ch.category.isNullOrBlank()) GONE else VISIBLE

            val fallback = helper.letterDrawable(context, ch.displayName)
            val model = com.streamverse.core.util.ChannelLogoResolver.model(ch)
            if (!model.isNullOrBlank()) {
                Glide.with(this)
                    .load(model)
                    .transition(DrawableTransitionOptions.withCrossFade(400))
                    .placeholder(fallback)
                    .error(fallback)
                    .centerInside()
                    .into(logoView)
            } else {
                logoView.setImageDrawable(fallback)
            }
            updateDots()
            if (animate) this.animate().alpha(1f).setDuration(300).start()
        }

        if (animate) {
            this.animate().alpha(0.6f).setDuration(250).withEndAction(bind).start()
        } else {
            bind.run()
        }
    }

    private fun buildDots() {
        dotsRow.removeAllViews()
        repeat(minOf(channels.size, 7)) { i ->
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape        = GradientDrawable.OVAL
                    cornerRadius = dp(8f)
                    setColor(if (i == 0) Color.WHITE else Color.parseColor("#44FFFFFF"))
                }
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).also {
                    it.marginEnd = dp(6)
                }
                tag = i
            }
            dotsRow.addView(dot)
        }
    }

    private fun updateDots() {
        for (i in 0 until dotsRow.childCount) {
            val dot = dotsRow.getChildAt(i) ?: continue
            (dot.background as? GradientDrawable)?.setColor(
                if (i == index) Color.WHITE
                else Color.parseColor("#44FFFFFF")
            )
            dot.layoutParams = (dot.layoutParams as LinearLayout.LayoutParams).also {
                it.width  = if (i == index) dp(20) else dp(8)
                it.height = dp(8)
            }
        }
    }

    private fun scheduleAdvance() {
        handler.removeCallbacks(advance)
        if (channels.size > 1) handler.postDelayed(advance, 8_000L)
    }

    // ---- Unit helpers ---------------------------------------------------------------

    private fun dp(value: Int)   = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float) = value * resources.displayMetrics.density
}
