package com.streamverse.core.data.model

import com.streamverse.core.domain.model.Quality

data class StmifyChannel(
    val id: String,
    val name: String,
    val slug: String,
    val logoUrl: String?,
    val quality: Quality?,
    val description: String?,
    val genres: List<String>,
)
