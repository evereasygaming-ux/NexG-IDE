package com.nexg.ide.ui.ai

import com.google.common.truth.Truth.assertThat
import com.nexg.ide.core.result.AppResult
import com.nexg.ide.domain.model.AiEvent
import com.nexg.ide.domain.model.AiRequest
import com.nexg.ide.domain.model.BackendHealth
import com.nexg.ide.domain.port.AiBackend
import com.nexg.ide.domain.port.CredentialKey
import com.nexg.ide.domain.port.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Health-framing tests for the shared AI status holder. */
@OptIn(ExperimentalCoroutinesApi::class)
class AiViewModelTest {

    @Before
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main; give it the eager unconfined
        // dispatcher so init{refresh()} completes before the assertions.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init reports a configured key and the backend health`() {
        val backend = ScriptedBackend(BackendHealth(true, true, "gemini-test", "ok"))
        val viewModel = AiViewModel(backend, FakeCredentialStore("stored-key"))

        val state = viewModel.state.value
        assertThat(state.keyConfigured).isTrue()
        assertThat(state.health).isEqualTo(AppResult.Success(BackendHealth(true, true, "gemini-test", "ok")))
        assertThat(backend.healthCalls).isEqualTo(1)
    }

    @Test
    fun `missing key is reflected`() {
        val viewModel = AiViewModel(ScriptedBackend(BackendHealth(false, false, "m", "x")), FakeCredentialStore(null))

        assertThat(viewModel.state.value.keyConfigured).isFalse()
    }

    @Test
    fun `refresh updates health`() {
        val backend = ScriptedBackend(BackendHealth(true, false, "gemini-test", "denied"))
        val viewModel = AiViewModel(backend, FakeCredentialStore("stored-key"))

        viewModel.refresh()

        assertThat(backend.healthCalls).isEqualTo(2)
        assertThat((viewModel.state.value.health as AppResult.Success).data.authorized).isFalse()
    }

    @Test
    fun `an in-flight refresh is not re-run on a second tap`() {
        // Gate the health so the first refresh is still in flight when the
        // second tap lands; the guard must skip the duplicate call.
        val gate = kotlinx.coroutines.CompletableDeferred<BackendHealth>()
        val backend = GatedBackend(gate)
        val viewModel = AiViewModel(backend, FakeCredentialStore("stored-key"))

        viewModel.refresh()
        viewModel.refresh()
        assertThat(backend.healthCalls).isEqualTo(1)

        // Unconfined Main resumes the awaited coroutine inline, so the state
        // is settled as soon as the gate is released.
        gate.complete(BackendHealth(true, true, "gemini-test", "ok"))

        assertThat(backend.healthCalls).isEqualTo(1)
        assertThat((viewModel.state.value.health as AppResult.Success).data.authorized).isTrue()
    }

    @Test
    fun `refreshConfigurationOnly never touches the network`() {
        val backend = ScriptedBackend(BackendHealth(false, false, "m", "x"))
        val store = FakeCredentialStore(null)
        val viewModel = AiViewModel(backend, store)
        backend.healthCalls = 0

        store.setKey("now-configured")
        viewModel.refreshConfigurationOnly()

        assertThat(viewModel.state.value.keyConfigured).isTrue()
        assertThat(backend.healthCalls).isEqualTo(0)
    }

    // ------------------------------------------------------------------ fakes

    private class FakeCredentialStore(private var key: String?) : CredentialStore {
        override suspend fun put(k: CredentialKey, value: String) {
            key = value
        }

        override suspend fun get(k: CredentialKey): String? = key

        override suspend fun clear(k: CredentialKey) {
            key = null
        }

        fun setKey(value: String?) {
            key = value
        }
    }

    private class ScriptedBackend(private val health0: BackendHealth) : AiBackend {
        var healthCalls: Int = 0
            internal set

        override val id: String = "gemini"
        override val requiresNetwork: Boolean = true
        override suspend fun health(): BackendHealth {
            healthCalls++
            return health0
        }

        override suspend fun complete(request: AiRequest): Flow<AiEvent> = flowOf()
    }

    private class GatedBackend(
        private val gate: kotlinx.coroutines.CompletableDeferred<BackendHealth>,
    ) : AiBackend {
        var healthCalls: Int = 0
            internal set

        override val id: String = "gemini"
        override val requiresNetwork: Boolean = true
        override suspend fun health(): BackendHealth {
            healthCalls++
            return gate.await()
        }

        override suspend fun complete(request: AiRequest): Flow<AiEvent> = flowOf()
    }
}