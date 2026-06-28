package com.streamverse.core.util

import com.streamverse.core.domain.model.Channel

/**
 * Normalises raw provider category strings and channel display names to the canonical
 * taxonomy defined in [CategoryTaxonomy].
 *
 * ## Migration note
 * The internal classification logic now delegates to [ChannelClassifier] for name‑based
 * inference, while the public API surface (including [C] constants) is preserved for
 * existing callers across the UI layer.
 */
object CategoryNormalizer {

    /** Canonical category constants — delegated to [CategoryTaxonomy] for the actual values. */
    object C {
        const val NEWS          = CategoryTaxonomy.NEWS
        const val SPORTS        = CategoryTaxonomy.SPORTS
        const val MOVIES        = CategoryTaxonomy.MOVIES
        const val KIDS          = CategoryTaxonomy.KIDS
        const val MUSIC         = CategoryTaxonomy.MUSIC
        const val DOCUMENTARY   = CategoryTaxonomy.DOCUMENTARY
        const val RELIGIOUS     = CategoryTaxonomy.RELIGIOUS
        const val LIFESTYLE     = CategoryTaxonomy.LIFESTYLE
        const val COMEDY        = CategoryTaxonomy.COMEDY
        const val SCIENCE       = CategoryTaxonomy.SCIENCE
        const val ENTERTAINMENT = CategoryTaxonomy.ENTERTAINMENT
        const val GENERAL       = CategoryTaxonomy.GENERAL
        const val RADIO         = CategoryTaxonomy.RADIO
        const val BUSINESS      = CategoryTaxonomy.BUSINESS

        val ALL_TV = CategoryTaxonomy.ALL_TV
        val ALL = CategoryTaxonomy.ALL
    }

    /**
     * Normalise a raw category label from a provider.
     *
     * @param raw  The group‑title or category string from the M3U / API.
     * @param isRadioOnly  When `true`, always returns [C.RADIO].
     */
    fun normalize(raw: String?, isRadioOnly: Boolean): String {
        if (isRadioOnly) return C.RADIO
        if (raw == null || raw.isBlank()) return C.GENERAL

        // Try the alias‑based normalisation first.
        CategoryTaxonomy.normaliseAliasWithSplit(raw)?.let { return it }

        return C.GENERAL
    }

    /**
     * Infer a category from the channel display name alone (used when no provider
     * metadata is available).  Delegates to [ChannelClassifier].
     */
    fun fromChannelName(name: String): String {
        if (name.isBlank()) return C.GENERAL
        val result = ChannelClassifier.classifyByName(name)
        return result?.category ?: C.GENERAL
    }

    /**
     * Full classification of a [Channel] using the layered strategy:
     * provider metadata → name‑based inference → General.
     * Used by [ChannelRepository.enrichAndMerge] to apply the best category.
     */
    fun classifyChannel(channel: Channel, providerCategory: String?): String {
        val result = ChannelClassifier.classify(channel, providerCategory)
        return result.category
    }

    // Expose internal classifier for testability / direct use.
    internal fun classifyByName(name: String): Classification? = ChannelClassifier.classifyByName(name)
}
