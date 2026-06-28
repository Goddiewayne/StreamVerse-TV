package com.streamverse.core.util

import com.streamverse.core.domain.model.Channel

/**
 * Resolves the best image URL to display for a channel.
 *
 *  1. If the channel already carries a `logoUrl` (most DLHD/Stmify channels do) → use it.
 *  2. Otherwise derive a best-effort logo from a public, CDN-backed source using the channel's
 *     brand name (Clearbit Logo API — free, fast, no key). This recovers real logos for the many
 *     logo-less IPTV/FAST channels that are well-known brands (CNN, ESPN, BBC News, …).
 *  3. If that 404s or fails, the display layer falls back to a generated gradient avatar
 *     (see the mobile `ChannelCard` / TV `TVChannelPresenter`). So a wrong/missing guess is never
 *     fatal — the user always sees a clean, branded tile.
 *
 * The derivation is intentionally conservative: brand-name → `<slug>.com` → Clearbit. A miss costs
 * nothing (clean fallback); a hit is a crisp real logo. Results are image-loader cached by URL.
 */
object ChannelLogoResolver {

    /** The image model to hand to Coil/Glide. Null only when there is nothing reasonable to try. */
    fun model(channel: Channel): String? {
        channel.logoUrl?.takeIf { it.isNotBlank() }?.let { return it }
        return clearbitUrl(channel.displayName)
    }

    /** True when [model] is a derived guess (not the channel's own logo) — callers may want a
     *  faster fallback to the generated avatar for these. */
    fun isDerived(channel: Channel): Boolean = channel.logoUrl.isNullOrBlank()

    // Words that describe the feed, not the brand — stripped before deriving a domain.
    private val NOISE = setOf(
        "hd", "fhd", "uhd", "4k", "sd", "tv", "channel", "live", "the",
        "us", "usa", "uk", "ca", "au", "network", "plus", "hq", "feed", "east", "west",
    )

    private fun clearbitUrl(name: String): String? {
        val slug = name
            .lowercase()
            .replace("&", "and")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() && it !in NOISE }
            .joinToString("")
        if (slug.length < 2) return null
        return "https://logo.clearbit.com/$slug.com"
    }
}
