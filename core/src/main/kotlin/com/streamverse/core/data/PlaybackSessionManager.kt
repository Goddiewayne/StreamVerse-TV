package com.streamverse.core.data

import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackSessionManager @Inject constructor() {

    enum class SourceSelectionMode {
        DEFAULT,      // System-selected best verified source
        MANUAL,       // User explicitly selected this source
        FAILOVER      // Auto-failover to verified source after failure
    }

    data class SessionState(
        val channel: Channel? = null,
        val defaultActiveSource: SourceType? = null,
        val currentPlaybackSource: SourceType? = null,
        val userSelectedSource: SourceType? = null,
        val selectionMode: SourceSelectionMode = SourceSelectionMode.DEFAULT,
        val isSourceValidated: Boolean = false,
        val validationStartTime: Long = 0,
        val failoverHistory: List<FailoverEvent> = emptyList(),
        val lastSuccessfulSource: SourceType? = null,
        val pendingValidation: Boolean = false,
    )

    data class FailoverEvent(
        val timestamp: Long = System.currentTimeMillis(),
        val fromSource: SourceType,
        val toSource: SourceType,
        val reason: String,
        val wasUserSelection: Boolean = false,
    )

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state

    private val validationTimeoutMs = 15_000L

    fun startSession(channel: Channel, defaultSource: SourceType, initialMode: SourceSelectionMode = SourceSelectionMode.DEFAULT) {
        _state.value = _state.value.copy(
            channel = channel,
            defaultActiveSource = defaultSource,
            currentPlaybackSource = defaultSource,
            selectionMode = initialMode,
            isSourceValidated = false,
            validationStartTime = 0,
            pendingValidation = true,
        )
    }

    fun updateDefaultSource(source: SourceType) {
        val current = _state.value
        if (current.defaultActiveSource != source) {
            _state.value = current.copy(defaultActiveSource = source)
        }
    }

    fun markSourceSelected(source: SourceType, mode: SourceSelectionMode) {
        val current = _state.value
        _state.value = current.copy(
            currentPlaybackSource = source,
            userSelectedSource = if (mode == SourceSelectionMode.MANUAL) source else null,
            selectionMode = mode,
            isSourceValidated = false,
            pendingValidation = true,
            validationStartTime = System.currentTimeMillis(),
        )
    }

    fun markValidationComplete(success: Boolean) {
        val current = _state.value
        if (!current.pendingValidation) return

        val updated = current.copy(
            isSourceValidated = success,
            pendingValidation = false,
            validationStartTime = 0,
        )
        if (success) {
            _state.value = updated.copy(lastSuccessfulSource = current.currentPlaybackSource)
        } else {
            _state.value = updated
        }
    }

    fun recordFailover(fromSource: SourceType, toSource: SourceType, reason: String, wasUserSelection: Boolean = false) {
        val current = _state.value
        val event = FailoverEvent(
            fromSource = fromSource,
            toSource = toSource,
            reason = reason,
            wasUserSelection = wasUserSelection,
        )
        _state.value = current.copy(
            failoverHistory = current.failoverHistory + event,
            currentPlaybackSource = toSource,
            selectionMode = if (wasUserSelection) SourceSelectionMode.MANUAL else SourceSelectionMode.FAILOVER,
            lastSuccessfulSource = toSource,
        )
    }

    fun getDefaultActiveSource(): SourceType? = _state.value.defaultActiveSource
    fun getCurrentPlaybackSource(): SourceType? = _state.value.currentPlaybackSource
    fun getUserSelectedSource(): SourceType? = _state.value.userSelectedSource
    fun getSelectionMode(): SourceSelectionMode = _state.value.selectionMode
    fun isSourceValidated(): Boolean = _state.value.isSourceValidated
    fun isValidationPending(): Boolean = _state.value.pendingValidation
    fun getValidationElapsed(): Long = if (_state.value.validationStartTime > 0) System.currentTimeMillis() - _state.value.validationStartTime else 0
    fun isValidationTimeout(): Boolean = getValidationElapsed() > validationTimeoutMs

    fun getFailoverHistory(): List<FailoverEvent> = _state.value.failoverHistory
    fun getLastSuccessfulSource(): SourceType? = _state.value.lastSuccessfulSource

    fun shouldRecoverToDefault(): Boolean {
        val current = _state.value
        return current.selectionMode == SourceSelectionMode.MANUAL &&
               current.userSelectedSource != null &&
               !current.isSourceValidated &&
               isValidationTimeout()
    }

    fun recoverToDefault(): SourceType? {
        val current = _state.value
        val defaultSource = current.defaultActiveSource
        if (defaultSource != null && defaultSource != current.currentPlaybackSource) {
            _state.value = current.copy(
                currentPlaybackSource = defaultSource,
                userSelectedSource = null,
                selectionMode = SourceSelectionMode.DEFAULT,
                isSourceValidated = false,
                pendingValidation = true,
                validationStartTime = System.currentTimeMillis(),
            )
            return defaultSource
        }
        return null
    }

    fun clearSession() {
        _state.value = SessionState()
    }

    fun getChannel(): Channel? = _state.value.channel
}