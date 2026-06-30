package com.streamverse.core.data.source

class EntityResolutionEngine(
    val aliasDictionary: AliasDictionary = AliasDictionary(),
) {
    private val byHashKey = mutableMapOf<String, MutableSet<String>>()
    private val byAlias = mutableMapOf<String, String>()
    private val byTvgId = mutableMapOf<String, String>()
    private val byCountryName = mutableMapOf<String, MutableSet<String>>()
    private val byExactName = mutableMapOf<String, String>()

    val aliasDict: AliasDictionary get() = aliasDictionary

    fun indexChannel(id: String, canonical: CanonicalResult, tvgId: String?, country: String?, displayName: String? = null) {
        byHashKey.getOrPut(canonical.hashKey) { mutableSetOf() }.add(id)
        if (canonical.resolvedAlias) {
            byAlias[canonical.hashKey] = id
        }
        if (!tvgId.isNullOrBlank()) {
            byTvgId[tvgId.lowercase().trim()] = id
        }
        if (!country.isNullOrBlank()) {
            byCountryName.getOrPut(country.lowercase().trim()) { mutableSetOf() }.add(id)
        }
        if (!displayName.isNullOrBlank()) {
            byExactName[displayName.lowercase().trim()] = id
        }
    }

    fun removeChannel(id: String, oldCanonical: CanonicalResult?, oldTvgId: String?, oldCountry: String?, oldDisplayName: String? = null) {
        if (oldCanonical != null) {
            byHashKey[oldCanonical.hashKey]?.remove(id)
            if (oldCanonical.resolvedAlias) byAlias.remove(oldCanonical.hashKey)
        }
        if (!oldTvgId.isNullOrBlank()) byTvgId.remove(oldTvgId.lowercase().trim())
        if (!oldCountry.isNullOrBlank()) byCountryName[oldCountry.lowercase().trim()]?.remove(id)
        if (!oldDisplayName.isNullOrBlank()) byExactName.remove(oldDisplayName.lowercase().trim())
    }

    fun clear() {
        byHashKey.clear(); byAlias.clear(); byTvgId.clear(); byCountryName.clear(); byExactName.clear()
    }

    fun findIdByExactName(name: String): String? = byExactName[name.trim().lowercase()]

    fun findIdByHashKey(hashKey: String): String? {
        val ids = byHashKey[hashKey]
        return ids?.firstOrNull()
    }

    fun findIdByTvgId(tvgId: String): String? = byTvgId[tvgId.trim().lowercase()]

    fun findIdByAlias(hashKey: String): String? = byAlias[hashKey]
}
