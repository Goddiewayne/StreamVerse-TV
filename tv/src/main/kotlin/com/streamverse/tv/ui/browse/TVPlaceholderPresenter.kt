package com.streamverse.tv.ui.browse

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.leanback.widget.Presenter

/**
 * Animated shimmer placeholder card — the TV equivalent of the mobile app's `ShimmerBox`.
 * Shown in skeleton rows while the channel catalogue loads, so the browse screen is never blank.
 */
class TVPlaceholderPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val view = ShimmerCardView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams((300 * d).toInt(), (180 * d).toInt())
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) { /* nothing to bind */ }
    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as? ShimmerCardView)?.stop()
    }

    /** A rounded card with a soft band of light sweeping across it, looped. */
    private class ShimmerCardView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val radius = 12 * d
        private val rect = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var translate = 0f

        private val base = 0xFF1A1A1A.toInt()
        private val highlight = 0xFF2A2A2A.toInt()

        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                translate = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (!animator.isStarted) animator.start()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            animator.cancel()
        }

        fun stop() = animator.cancel()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            rect.set(0f, 0f, w, h)
            val sweep = w * 1.6f
            val start = -sweep + translate * (w + sweep)
            paint.shader = LinearGradient(
                start, 0f, start + sweep, 0f,
                intArrayOf(base, highlight, base),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
}
