package com.streamverse.core.data.source

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceRegistryInitializer @Inject constructor(
    private val registry: SourceRegistry,
    private val providers: List<ProviderAdapter>,
) {
    init {
        for (provider in providers) {
            registry.register(provider)
        }
    }
}
