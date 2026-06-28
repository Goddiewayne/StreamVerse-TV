package com.streamverse.core.util

data class StreamInfo(
    val url: String,
    val drmKeyId: String? = null,
    val drmKey: String? = null,
    val drmLicenseUrl: String? = null,
    val requiresBrowser: Boolean = false,
    val forceWebView: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
) {
    val hasDrm: Boolean get() = drmKeyId != null && drmKey != null
}
