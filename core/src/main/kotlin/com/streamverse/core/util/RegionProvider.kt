package com.streamverse.core.util

object RegionProvider {
    fun getRegionCode(): String? {
        val country = java.util.Locale.getDefault().country
        return country.takeIf { it.isNotBlank() }?.uppercase()
    }
}
