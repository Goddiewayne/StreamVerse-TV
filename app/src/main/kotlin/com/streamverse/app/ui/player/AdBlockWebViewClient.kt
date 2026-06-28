package com.streamverse.app.ui.player

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamverse.core.util.AdBlocker

class AdBlockWebViewClient(
    private val onPageStarted: () -> Unit = {},
    private val onPageFinished: () -> Unit = {},
    private val onTitleChanged: (String) -> Unit = {},
    private val loginJs: String? = null,
) : WebViewClient() {

    companion object {
        private const val TAG = "AdBlockWebViewClient"
        // Clicks the YouTube/Google consent dialog's Accept/Reject button (or submits its form).
        private const val CONSENT_DISMISS_JS = """
            (function(){
              var els = document.querySelectorAll('button,[role=button],input[type=submit]');
              for (var i=0;i<els.length;i++){
                var t=((els[i].textContent||'')+(els[i].getAttribute('aria-label')||'')).toLowerCase();
                if (t.indexOf('reject all')>=0||t.indexOf('accept all')>=0||t.indexOf('i agree')>=0||t.indexOf('agree to')>=0){ els[i].click(); return 'clicked'; }
              }
              var forms=document.querySelectorAll('form');
              for (var j=0;j<forms.length;j++){ if(forms[j].action&&forms[j].action.indexOf('consent')>=0){ forms[j].submit(); return 'submitted'; } }
              return 'none';
            })();
        """
    }

    private val blockedMimeTypes = setOf(
        "text/javascript", "application/javascript", "application/x-javascript",
        "text/css", "image/gif", "image/png", "image/jpeg", "image/webp",
        "font/woff", "font/woff2", "application/x-font-ttf",
    )

    private var consentAttempts = 0

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url.toString()
        android.util.Log.d(TAG, "shouldInterceptRequest: isMain=${request.isForMainFrame} url=${url.take(150)}")
        if (AdBlocker.isAdUrl(url)) {
            return blockRequest(request)
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStarted()
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        injectAdBlockCss(view)

        // YouTube/Google "Before you continue" consent interstitial: auto-dismiss it (cookies
        // are unreliable) so the live page can load. Clicking Accept/Reject submits the consent
        // form and navigates on to the requested video. Bounded to avoid any loop.
        if ((url.contains("consent.youtube.com") || url.contains("consent.google.com")) && consentAttempts < 3) {
            consentAttempts++
            android.util.Log.d(TAG, "Auto-dismissing consent page (attempt $consentAttempts)")
            view.evaluateJavascript(CONSENT_DISMISS_JS, null)
        }

        if (loginJs != null) {
            view.evaluateJavascript(loginJs, null)
        }
        onPageFinished()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        val currentUrl = view.url ?: return false
        val currentHost = java.net.URI(currentUrl).host ?: return false
        val requestHost = java.net.URI(url).host ?: return false
        if (requestHost != currentHost && !request.isForMainFrame) {
            return true
        }
        if (AdBlocker.isAdUrl(url)) {
            return true
        }
        return false
    }

    fun setupWebView(webView: WebView) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            // Pre-accept YouTube/Google consent so the "Before you continue to YouTube" wall
            // never blocks playback (it otherwise appears for NG/EU regions and stalls the page).
            setCookie("https://www.youtube.com", "SOCS=CAISNewABA; Domain=.youtube.com; Path=/; Max-Age=31536000; Secure")
            setCookie("https://www.youtube.com", "CONSENT=YES+1; Domain=.youtube.com; Path=/")
            setCookie("https://www.google.com", "CONSENT=YES+1; Domain=.google.com; Path=/")
        }
        webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = "Mozilla/5.0 (Linux; Android 16; Pixel 9 Pro Build/AP31.240617.009) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/133.0.6943.137 Mobile Safari/537.36"
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
            }
            setBackgroundColor(android.graphics.Color.BLACK)
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            webChromeClient = AdBlockWebChromeClient(onTitleChanged)
            webViewClient = this@AdBlockWebViewClient
        }
    }

    private fun blockRequest(request: WebResourceRequest): WebResourceResponse {
        val mimeType = request.requestHeaders?.get("Accept")?.split(",")?.firstOrNull()
            ?.trim()?.takeIf { it in blockedMimeTypes } ?: "text/plain"
        return WebResourceResponse(mimeType, "UTF-8", null)
    }

    private fun injectAdBlockCss(view: WebView) {
        val css = """
            [id*="ad"],[id*="ads"],[id*="banner"],[id*="popup"],[id*="popunder"],
            [id*="sponsor"],[id*="promo"],[id*="google_ads"],[id*="div-gpt"],
            [class*="ad"],[class*="ads"],[class*="banner"],[class*="popup"],
            [class*="popunder"],[class*="sponsor"],[class*="promo"],
            [class*="google_ads"],[class*="adslot"],[class*="ad-box"],
            [class*="ad-container"],[class*="ad-wrapper"],
            iframe[src*="ad"],iframe[src*="ads"],iframe[src*="doubleclick"],
            iframe[src*="google"],iframe[src*="popup"],
            div[data-ad],div[data-ad-id],div[data-ad-slot],
            ins.adsbygoogle,
            .advertisement,.advertising,.ad-placeholder,.ad-banner,.ad-unit,
            .ad-section,.ad-sidebar,.ad-top,.ad-bottom,.ad-left,.ad-right,
            .ad-header,.ad-footer,.ad-middle,.ad-overlay,.ad-popup,
            .popup,.popunder,.overlay-ad,.interstitial,
            #adblock-detect,.adblock-alert,.adblock-detected,
            [style*="position: fixed"][style*="z-index"],
            [style*="position:fixed"][style*="z-index"]
        { display: none !important; visibility: hidden !important; height: 0 !important; width: 0 !important; overflow: hidden !important; }
        """.trimIndent()
        val encoded = android.net.Uri.encode(css)
        view.evaluateJavascript(
            "var s = document.createElement('style'); s.type = 'text/css'; " +
                "s.textContent = decodeURIComponent('$encoded'); " +
                "document.head.appendChild(s);",
            null,
        )
    }
}

private class AdBlockWebChromeClient(
    private val onTitleChanged: (String) -> Unit,
) : WebChromeClient() {
    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        title?.let { onTitleChanged(it) }
    }
}
