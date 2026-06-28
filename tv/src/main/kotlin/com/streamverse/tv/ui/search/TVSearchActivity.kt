package com.streamverse.tv.ui.search

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Hosts [TVSearchFragment] — the leanback search UI.
 *
 * Launched by the magnifying-glass affordance in [TVBrowseFragment].
 */
@AndroidEntryPoint
class TVSearchActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, TVSearchFragment())
                .commit()
        }
    }
}
