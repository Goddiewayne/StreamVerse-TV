package com.streamverse.core.util

import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamVerseDispatchers @Inject constructor() {
    val io = Dispatchers.IO
    val main = Dispatchers.Main
    val default = Dispatchers.Default
}
