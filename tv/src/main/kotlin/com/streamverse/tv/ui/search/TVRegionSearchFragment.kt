package com.streamverse.tv.ui.search

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.lifecycle.lifecycleScope
import com.streamverse.core.data.repository.ChannelRepository
import com.streamverse.core.domain.model.Channel
import com.streamverse.tv.ui.browse.TVChannelPresenter
import com.streamverse.tv.ui.playback.TVPlaybackActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

private val WORLD_REGIONS: Map<String, Set<String>> = mapOf(
    "Middle East" to setOf("AE","SA","QA","KW","BH","OM","JO","IQ","LB","SY","YE","PS","IR"),
    "Africa" to setOf("EG","NG","GH","ZA","KE","ET","TZ","UG","CM","CI","SN","MA","TN","DZ","LY","SD","RW","MU","MG","AO","MZ","ZM","ZW","BW","NA","SL","LR","TG","BJ","BF","ML","GN","NE","TD","CD","CG","CF","GA","GQ","ER","SO","DJ"),
    "Europe" to setOf("GB","FR","DE","IT","ES","PT","NL","BE","CH","AT","SE","NO","DK","FI","PL","RU","TR","GR","RO","CZ","UA","HU","SK","BG","HR","RS","IE","IS","LU","MT","CY","GE","AM","AZ","AL","BA","ME","MK","MD","LV","LT","EE","BY","XK","SI"),
    "Americas" to setOf("US","CA","MX","BR","AR","CO","VE","PE","CL","CU","DO","JM","TT","GT","EC","HN","SV","PA","CR","BO","UY","PY","NI","HT","PR","TC","BB","BS","BZ","GY","SR"),
    "Asia" to setOf("IN","PK","BD","LK","CN","JP","KR","ID","MY","TH","VN","PH","SG","HK","TW","AF","NP","MM","KH","LA","MN","UZ","KZ","KG","TJ","TM","BN"),
    "Oceania" to setOf("AU","NZ","FJ","PG","WS","TO","SB","VU"),
)

private val COUNTRY_NAMES: Map<String, String> = mapOf(
    "AE" to "UAE", "SA" to "Saudi Arabia", "QA" to "Qatar", "KW" to "Kuwait",
    "BH" to "Bahrain", "OM" to "Oman", "JO" to "Jordan", "IQ" to "Iraq",
    "LB" to "Lebanon", "SY" to "Syria", "YE" to "Yemen", "PS" to "Palestine",
    "IR" to "Iran", "EG" to "Egypt", "NG" to "Nigeria", "GH" to "Ghana",
    "ZA" to "South Africa", "KE" to "Kenya", "ET" to "Ethiopia", "TZ" to "Tanzania",
    "UG" to "Uganda", "CM" to "Cameroon", "SN" to "Senegal", "MA" to "Morocco",
    "TN" to "Tunisia", "DZ" to "Algeria", "LY" to "Libya", "SD" to "Sudan",
    "GB" to "United Kingdom", "FR" to "France", "DE" to "Germany", "IT" to "Italy",
    "ES" to "Spain", "PT" to "Portugal", "NL" to "Netherlands", "BE" to "Belgium",
    "CH" to "Switzerland", "AT" to "Austria", "SE" to "Sweden", "NO" to "Norway",
    "DK" to "Denmark", "FI" to "Finland", "PL" to "Poland", "RU" to "Russia",
    "TR" to "Turkey", "GR" to "Greece", "RO" to "Romania", "CZ" to "Czech Republic",
    "UA" to "Ukraine", "HU" to "Hungary", "SK" to "Slovakia", "BG" to "Bulgaria",
    "HR" to "Croatia", "RS" to "Serbia", "IE" to "Ireland", "US" to "United States",
    "CA" to "Canada", "MX" to "Mexico", "BR" to "Brazil", "AR" to "Argentina",
    "CO" to "Colombia", "VE" to "Venezuela", "PE" to "Peru", "CL" to "Chile",
    "CU" to "Cuba", "DO" to "Dominican Rep.", "JM" to "Jamaica", "IN" to "India",
    "PK" to "Pakistan", "BD" to "Bangladesh", "LK" to "Sri Lanka", "CN" to "China",
    "JP" to "Japan", "KR" to "South Korea", "ID" to "Indonesia", "MY" to "Malaysia",
    "TH" to "Thailand", "VN" to "Vietnam", "PH" to "Philippines", "SG" to "Singapore",
    "HK" to "Hong Kong", "TW" to "Taiwan", "AF" to "Afghanistan", "AU" to "Australia",
    "NZ" to "New Zealand", "GE" to "Georgia", "AM" to "Armenia", "AZ" to "Azerbaijan",
    "KZ" to "Kazakhstan", "UZ" to "Uzbekistan", "AL" to "Albania", "BA" to "Bosnia",
)

@AndroidEntryPoint
class TVRegionSearchFragment : BrowseSupportFragment() {

    @Inject lateinit var channelRepository: ChannelRepository

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter(FocusHighlight.ZOOM_FACTOR_NONE))

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        title = "Browse by Region"
        setBrandColor(Color.parseColor("#0F172A"))
        setSearchAffordanceColor(Color.parseColor("#22D3EE"))
        adapter = rowsAdapter

        setOnItemViewClickedListener { _, item, _, _ ->
            if (item is Channel) {
                startActivity(
                    android.content.Intent(requireContext(), TVPlaybackActivity::class.java)
                        .putExtra(TVPlaybackActivity.EXTRA_CHANNEL_ID, item.id)
                )
            }
        }

        lifecycleScope.launch {
            val all = channelRepository.getCachedChannels().ifEmpty {
                channelRepository.load()
                channelRepository.getCachedChannels()
            }

            val channelsByCountry = all
                .mapNotNull { ch -> ch.country?.uppercase()?.takeIf { it.length == 2 }?.let { it to ch } }
                .groupBy({ it.first }, { it.second })

            var rowIndex = 0L
            WORLD_REGIONS.entries.sortedBy { it.key }.forEach { (region, codes) ->
                val regionChs = codes.flatMap { code ->
                    channelsByCountry[code].orEmpty()
                }
                if (regionChs.isNotEmpty()) {
                    val adapter = ArrayObjectAdapter(TVChannelPresenter())
                    regionChs.take(40).forEach { adapter.add(it) }
                    val emoji = when (region) {
                        "Middle East" -> "\uD83C\uDF19"
                        "Africa" -> "\uD83C\uDF0D"
                        "Europe" -> "\uD83C\uDFF0"
                        "Americas" -> "\uD83D\uDDFD"
                        "Asia" -> "\uD83C\uDFEF"
                        "Oceania" -> "\uD83E\uDD98"
                        else -> "\uD83C\uDF10"
                    }
                    rowsAdapter.add(ListRow(HeaderItem(rowIndex++, "$emoji  $region ($regionChs.size)"), adapter))
                }
            }
        }
    }
}
