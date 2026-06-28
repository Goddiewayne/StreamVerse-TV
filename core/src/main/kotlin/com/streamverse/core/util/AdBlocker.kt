package com.streamverse.core.util

object AdBlocker {
    private val adDomains: Set<String> by lazy {
        buildSet {
            addAll(adServerDomains)
            addAll(popupDomains)
            addAll(trackingDomains)
            addAll(malvertisingDomains)
            addAll(streamingSiteAdDomains)
        }
    }

    private val adPatterns: List<Regex> = listOf(
        Regex("""/(?:ad[s]?|banner|popup|popunder|popout)[\d]*/""", RegexOption.IGNORE_CASE),
        Regex("""[?&](?:ad_id|ad_type|ad_zone|ad_slot|banner_id|bannerid|zoneid|campaignid)=""", RegexOption.IGNORE_CASE),
        Regex("""/tracking[\d]*/""", RegexOption.IGNORE_CASE),
        Regex("""/impression[\d]*/""", RegexOption.IGNORE_CASE),
        Regex("""/click[\d]*/""", RegexOption.IGNORE_CASE),
        Regex("""/pixel[\d]*/""", RegexOption.IGNORE_CASE),
        Regex("""/beacon[\d]*/""", RegexOption.IGNORE_CASE),
        Regex("""/analytics[\d]*/""", RegexOption.IGNORE_CASE),
        Regex("""/pagead/""", RegexOption.IGNORE_CASE),
        Regex("""/pubads/""", RegexOption.IGNORE_CASE),
        Regex("""/gampad/""", RegexOption.IGNORE_CASE),
        Regex("""/ddm/""", RegexOption.IGNORE_CASE),
        Regex("""^https?://(?:[\w-]+\.)*?ad(?:[\d]+)?\.""", RegexOption.IGNORE_CASE),
        Regex("""^https?://(?:[\w-]+\.)*?ads(?:[\d]+)?\.""", RegexOption.IGNORE_CASE),
        Regex("""^https?://(?:[\w-]+\.)*?pop(?:up|under|out|in)[\d]*\.""", RegexOption.IGNORE_CASE),
        Regex("""/serve?.*[?&]type=ad""", RegexOption.IGNORE_CASE),
        Regex("""/count(?:er)?\.(?:gif|png|jpg|php|aspx?)""", RegexOption.IGNORE_CASE),
        Regex("""[?&]adid=""", RegexOption.IGNORE_CASE),
        Regex("""/banner[\d]*\.""", RegexOption.IGNORE_CASE),
        Regex("""/aff(?:iliate)?[\d]*/""", RegexOption.IGNORE_CASE),
        Regex("""/cpm[\d]*/""", RegexOption.IGNORE_CASE),
        Regex("""/display/""", RegexOption.IGNORE_CASE),
        Regex("""/sponsor(?:ed)?[\d]*/""", RegexOption.IGNORE_CASE),
        Regex("""/promo[\d]*/""", RegexOption.IGNORE_CASE),
        Regex("""/prebid[\d]*/""", RegexOption.IGNORE_CASE),
        Regex("""/bid(?:der|ding|request|response)[\d]*/""", RegexOption.IGNORE_CASE),
        Regex("""/rtb[\d]*/""", RegexOption.IGNORE_CASE),
        Regex("""[?&]pos=(?:top|bottom|left|right|middle|1|2|3|4|5)""", RegexOption.IGNORE_CASE),
    )

    private val alwaysAllowDomains: Set<String> = setOf(
        "dlhd.pk", "www.dlhd.pk",
        "stmify.com", "www.stmify.com", "cdn.stmify.com",
        "googleapis.com", "gstatic.com",
        "jsdelivr.net", "cdnjs.cloudflare.com",
        "fonts.googleapis.com", "fonts.gstatic.com",
        "jquery.com", "code.jquery.com",
        "hlsjs.video-dev-hls.xyz",
    )

    fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()

        for (allowed in alwaysAllowDomains) {
            if (lower.contains(allowed)) return false
        }

        try {
            val host = java.net.URI(lower).host ?: return false
            val domainParts = host.split('.')
            for (i in domainParts.indices) {
                val subdomain = domainParts.drop(i).joinToString(".")
                if (subdomain in adDomains) return true
            }
        } catch (_: Exception) {
            return false
        }

        for (pattern in adPatterns) {
            if (pattern.containsMatchIn(lower)) return true
        }

        return false
    }

    private val adServerDomains = setOf(
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "googletagservices.com", "googletagmanager.com", "adservice.google.com",
        "pagead2.googlesyndication.com", "adzerk.net", "adzerk.com",
        "adsrvr.org", "adsymptotic.com", "adnxs.com", "appnexus.com",
        "rubiconproject.com", "pubmatic.com", "openx.net", "openx.com",
        "criteo.com", "criteo.net", "contextweb.com", "indexexchange.com",
        "casalemedia.com", "sovrn.com", "sharethrough.com", "districtm.io",
        "tremorhub.com", "spotxchange.com", "spotx.tv", "advertising.com",
        "atdmt.com", "bluekai.com", "exelator.com", "demdex.net",
        "adsafeprotected.com", "moatads.com", "moat.com",
        "integralads.com", "scorecardresearch.com", "quantcount.com",
        "comscore.com", "smartadserver.com", "adform.com",
        "adition.com", "adriver.ru", "adfox.ru", "adscale.de",
        "adyoulike.com", "adtegrity.com", "adtech.de", "adtechus.com",
        "admedia.com", "adblade.com", "adsonar.com",
        "adbrite.com", "adbutler.com", "adlegend.com",
        "adreactor.com", "adserver.com", "adspeed.net", "adspirit.net",
        "adtech.com", "adlayer.com", "adlooxtracking.com",
        "admarketplace.net", "adotube.com", "adpulse.com",
        "adshuffle.com", "adskeeper.co.uk",
        "adslid.com", "adtaily.com", "adtelligent.com",
        "adversal.net", "advertise.com", "advertising.com",
        "afilio.com", "amobee.com", "bidr.io",
        "bidswitch.com", "bidvertiser.com",
        "burstnet.com", "clicksor.com", "clickthru.net",
        "conversantmedia.com", "cpalead.com", "cpx24.com",
        "effectivemeasure.net", "engageya.com",
        "fastclick.com", "federatedmedia.net",
        "freewheel.tv", "grapeshot.co.uk",
        "hybrid.ai", "impact-ad.com",
        "improvedigital.com", "inmobi.com", "inmobicdn.com",
        "justpremium.com", "kargo.com",
        "ligatus.com", "lockerdome.com", "lotame.com",
        "mathtag.com", "media.net", "mediapm.com",
        "millennialmedia.com", "mixpanel.com", "mobfox.com",
        "monetate.com", "mparticle.com",
        "nativeads.com", "nativo.com", "nend.net",
        "optimizely.com", "outbrain.com", "owneriq.com",
        "peer39.com", "plista.com", "pointroll.com",
        "proxad.net", "pubdirecte.com", "pulsepoint.com",
        "quantserve.com", "revcontent.com", "rlcdn.com",
        "rtbidder.net", "sabavision.com",
        "serving-sys.com", "shareasale.com", "sharethis.com",
        "simpli.fi", "skimlinks.com", "skimresources.com",
        "smartadserver.com", "smartclip.net",
        "sonobi.com", "specificmedia.com",
        "stackadapt.com", "switchadhub.com",
        "taboola.com", "tapad.com", "teads.tv",
        "thetradedesk.com", "tidaltv.com",
        "trafficfactory.biz", "trafficjunky.com",
        "tribalfusion.com", "tubemogul.com", "turn.com",
        "ucfunnel.com", "undertone.com", "unruly.com",
        "valueclick.com", "valueclickmedia.com",
        "veoxa.com", "vibrantmedia.com",
        "vidoomy.com", "yieldbot.com", "yieldlab.net",
        "yieldmo.com", "yieldtraffic.com", "zanox.com",
    )

    private val popupDomains = setOf(
        "popads.net", "popcash.net", "popunder.net", "popadscdn.net",
        "propellerads.com", "propellerclick.com", "propellerpops.com",
        "exoclick.com", "exosrv.com", "exocir.com",
        "juicyads.com", "juicysandbox.com",
        "clickunder.net", "poponclick.com",
        "popadz.com", "popuptraffic.com",
        "popcashjs.com", "popinads.com",
        "popadvert.com", "clickfuse.com", "clickadz.com",
        "clicksor.com", "poponclick.com",
        "clickaine.com", "pop6.com",
        "popads.net", "popcheck.com",
        "popunder.ru", "popub.net",
        "popmedia.com", "popadventure.com",
        "popbubble.com", "poprule.com",
        "popin.cc", "popcash.net",
        "popunder.net", "clickunder.net",
        "popmoney.net", "popadvert.com",
        "clickheat.com", "clickonometrics.com",
    )

    private val trackingDomains = setOf(
        "google-analytics.com", "googletagmanager.com",
        "connect.facebook.net", "pixel.facebook.com",
        "facebook.com", "facebook.net",
        "analytics.google.com", "analytics.yahoo.com",
        "ads.linkedin.com", "bat.bing.com",
        "snapchat.com", "pinterest.com",
        "hotjar.com", "mouseflow.com", "crazyegg.com", "luckyorange.com",
        "fullstory.com", "heap.io", "amplitude.com", "mixpanel.com",
        "segment.com", "segment.io", "branch.io", "adjust.com",
        "appsflyer.com", "flurry.com", "kochava.com",
        "localytics.com", "leanplum.com",
        "newrelic.com", "datadoghq.com", "sentry.io", "rollbar.com",
        "bugsnag.com", "crashlytics.com", "fabric.io",
        "doubleverify.com", "integralads.com",
        "forensiq.com", "pixalate.com",
        "nielsen.com", "nielsen-online.com",
        "statcounter.com", "clicky.com",
        "piwik.org", "matomo.org",
        "chartbeat.com", "chartbeat.net",
        "pingdom.com", "woopra.com",
        "extremetracking.com",
        "adtracker.com", "adtrack.co",
        "obtrckr.com",
        "clarity.ms",
    )

    private val malvertisingDomains = setOf(
        "adf.ly", "adfoc.us",
        "sh.st", "shorte.st",
        "clk.sh", "clkmein.com",
        "linkbucks.com", "link-protector.com",
        "linkvertise.com", "link-to.net",
        "direct-link.net", "leechpremium.link",
        "shrinkme.io", "shrinkurl.org",
        "tinyurl.com", "yourls.org",
        "urlcash.com", "urlcash.net",
        "shorturl.com", "shrty.com",
        "stfly.io", "t2m.io",
        "bc.vc", "adshort.co",
    )

    private val streamingSiteAdDomains = setOf(
        "exoclick.com", "exosrv.com", "juicyads.com",
        "adbull.com", "adcash.com",
        "admiral.com", "adreactor.com",
        "aniview.com", "anyclip.com",
        "applifier.com", "applovin.com", "appnext.com",
        "avantisvideo.com", "bidmachine.io",
        "bidease.com", "bids.io",
        "buysellads.com", "carbonads.com",
        "clickadu.com", "connatix.com",
        "contentabc.com", "cxense.com",
        "displayvertising.com", "eclick.vn",
        "evadav.com", "evyy.net",
        "fifty.io", "giraff.io",
        "hybrid.ai", "izooto.com",
        "kixer.com", "mgcash.com",
        "nativeads.com", "nend.net",
        "premiumtv.co.uk", "propellerads.com",
        "pushcrew.com", "pushengage.com",
        "reklamport.com", "revcontent.com",
        "rtbimedia.com", "smartyads.com",
        "snapads.com", "sociomantic.com",
        "stickeadstv.com", "supertop.ru",
        "taboola.com", "textcash.com",
        "trafficstars.com", "vidoomy.com",
        "wikia-ads.com", "wpadmngr.com",
        "yabidos.com", "yandexdirect.ru",
        "zdbb.net",
    )
}
