package com.streamverse.pipeline.ingester

import com.streamverse.pipeline.model.RawChannel

interface SourceIngester {
    fun name(): String
    fun ingest(): List<RawChannel>
}
