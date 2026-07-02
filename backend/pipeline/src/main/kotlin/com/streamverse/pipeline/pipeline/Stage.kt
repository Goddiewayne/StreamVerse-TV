package com.streamverse.pipeline.pipeline

sealed class StageResult {
    data class Success(val message: String, val durationMs: Long) : StageResult()
    data class Failure(val message: String, val durationMs: Long, val error: Throwable? = null) : StageResult()
    data class Skipped(val reason: String) : StageResult()
}

interface Stage {
    val name: String
    fun execute(): StageResult
}
