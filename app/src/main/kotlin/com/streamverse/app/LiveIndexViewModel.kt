package com.streamverse.app

import androidx.lifecycle.ViewModel
import com.streamverse.core.data.ChannelHealthEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Surfaces the Channel Health Engine's Live Availability Index to the navigation root so LIVE
 * badges can be provided (via `LocalLiveChannels`) to every screen at once.
 */
@HiltViewModel
class LiveIndexViewModel @Inject constructor(
    engine: ChannelHealthEngine,
) : ViewModel() {
    val liveChannelIds: StateFlow<Set<String>> = engine.liveChannelIds
}
