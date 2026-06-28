package com.streamverse.tv.ui.settings

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import dagger.hilt.android.AndroidEntryPoint

/**
 * Hosts [TVSettingsFragment] — the leanback guided-step settings UI.
 *
 * Reachable via the "⚙ Settings" row at the bottom of the browse sidebar,
 * or via any future deep-link.
 */
@AndroidEntryPoint
class TVSettingsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, TVSettingsFragment(), android.R.id.content)
        }
    }
}
