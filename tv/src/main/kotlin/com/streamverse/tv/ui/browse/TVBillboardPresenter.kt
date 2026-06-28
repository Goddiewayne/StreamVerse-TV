package com.streamverse.tv.ui.browse

import android.view.ViewGroup
import androidx.leanback.widget.Presenter

/**
 * Leanback presenter that wraps [TVBillboardView] as a single full-width hero item.
 *
 * The adapter for the billboard row holds ONE item: a List<Channel> that the billboard
 * cycles through on its own timer. Width is set to 78% of the screen so it fills
 * the content area of the browse layout (the header sidebar occupies the remaining ~22%).
 */
class TVBillboardPresenter(
    /** Called when the hero is clicked, with the channel currently on display. */
    private val onClick: (com.streamverse.core.domain.model.Channel) -> Unit = {},
) : Presenter() {

    class BillboardHolder(val billboard: TVBillboardView) : ViewHolder(billboard)

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val density  = ctx.resources.displayMetrics.density
        val screenW  = ctx.resources.displayMetrics.widthPixels
        val cardW    = (screenW * 0.78f).toInt()
        val cardH    = (280 * density).toInt()

        val view = TVBillboardView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(cardW, cardH)
            onWatchClick = onClick
        }
        return BillboardHolder(view)
    }

    override fun onBindViewHolder(vh: ViewHolder, item: Any?) {
        @Suppress("UNCHECKED_CAST")
        val channels = item as? List<*> ?: return
        @Suppress("UNCHECKED_CAST")
        (vh as BillboardHolder).billboard.setChannels(
            channels.filterIsInstance<com.streamverse.core.domain.model.Channel>()
        )
    }

    override fun onUnbindViewHolder(vh: ViewHolder) {
        (vh as BillboardHolder).billboard.stop()
    }
}
