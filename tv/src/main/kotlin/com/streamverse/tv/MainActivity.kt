package com.streamverse.tv

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint

// FragmentActivity (not ComponentActivity): the leanback browse UI is inflated from a <fragment>
// tag in activity_tv_main, which only a FragmentActivity host can inflate.
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_main)
    }
}
