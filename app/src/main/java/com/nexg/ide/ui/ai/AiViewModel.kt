package com.nexg.ide.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexg.ide.core.result.AppResult
import com.nexg.ide.domain.model.BackendHealth
import com.nexg.ide.domain.port.AiBackend
import com.nexg.ide.domain.port.CredentialKey
import com.nexg.ide.domain.port.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * AI assistant status shared by the AI and Settings destinations (PLAN.MD 4.9).
 *
 * Phase 4 is the boundary: backend identity, connectivity and whether a key is
 * stored. Streaming chat is Phase 5. Holding the health check here instead of
 * inside the composable is what lets the "no key stored / key rejected /
 * network down" framing run on the JVM.
 */
data class AiUiState(
    val health: AppResult<BackendHealth> = AppResult.Loading,
    val keyConfigured: Boolean = false,
    val refreshing: Boolean = false,
)

class AiViewModel(
    private val aiBackend: AiBackend,
    private val credentials: CredentialStore,
) : ViewModel() {

    private val _state = MutableStateFlow(AiUiState())
    val state: StateFlow<AiUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, health = AppResult.Loading) }
            val key = credentials.get(CredentialKey.GEMINI_API_KEY)
            val health = aiBackend.health()
            _state.update {
                it.copy(
                    refreshing = false,
                    keyConfigured = !key.isNullOrBlank(),
                    health = AppResult.Success(health),
                )
            }
        }
    }

    /** Re-checks configuration without a network round trip. */
    fun refreshConfigurationOnly() {
        viewModelScope.launch {
            val key = credentials.get(CredentialKey.GEMINI_API_KEY)
            _state.update { it.copy(keyConfigured = !key.isNullOrBlank()) }
        }
    }

    companion object {
        /**
         * Factory for whichever destination needs the status. The backend and
         * store come from the container so the composable never reaches for
         * singletons itself.
         */
        fun factory(aiBackend: AiBackend, credentials: CredentialStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AiViewModel(aiBackend = aiBackend, credentials = credentials) as T
            }
    }
}