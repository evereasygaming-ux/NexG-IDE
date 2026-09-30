package com.nexg.ide.integration.gemini

import com.google.common.truth.Truth.assertThat
import com.nexg.ide.core.dispatch.DefaultDispatcherProvider
import com.nexg.ide.core.log.AppLogger
import com.nexg.ide.core.log.InMemoryLogSink
import com.nexg.ide.core.log.SecretRedactor
import com.nexg.ide.core.result.AppError
import com.nexg.ide.domain.model.AiEvent
import com.nexg.ide.domain.model.AiMessage
import com.nexg.ide.domain.model.AiRequest
import com.nexg.ide.domain.model.AiRole
import com.nexg.ide.domain.port.CredentialKey
import com.nexg.ide.domain.port.CredentialStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Backend contract tests against a real localhost HTTP server (PLAN.MD 4.9
 * gate: "green + backend tests (mock server)"). These are the tests that pin
 * the two security properties: the key travels only in a header, and a missing
 * key never opens a socket.
 */
class GeminiBackendTest {

    private val server = MockWebServer()
    private lateinit var store: FakeCredentialStore

    // 39 characters total (AIza + 35), the real Google API-key shape that
    // SecretRedactor's GOOGLE_API_KEY rule matches.
    private val DUMMY_KEY = "AIza" + "A".repeat(35)
    private val logger = AppLogger(InMemoryLogSink())

    @Before
    fun setUp() {
        server.start()
        store = FakeCredentialStore(DUMMY_KEY)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `streams deltas and done from sse lines`() = runTest {
        val backend = backend()
        server.enqueue(
            MockResponse().setBody(
                """data: {"candidates":[{"content":{"parts":[{"text":"Hello "}],"role":"model"}}]}
                    |
                    |data: {"candidates":[{"content":{"parts":[{"text":"world"}],"role":"model"},"finishReason":"STOP"}]}
                """.trimMargin(),
            ),
        )

        val events = backend.complete(request("help")).toList()

        assertThat(events.filterIsInstance<AiEvent.Delta>().map { it.text }.joinToString(""))
            .isEqualTo("Hello world")
        assertThat(events.filterIsInstance<AiEvent.Done>().map { it.finishReason })
            .containsExactly("STOP")

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isNotNull()
        assertThat(recorded.path).startsWith("/v1beta/models/")
        assertThat(recorded.path).endsWith(":streamGenerateContent?alt=sse")
    }

    @Test
    fun `the key travels in a header, never in the query string`() = runTest {
        val backend = backend()
        server.enqueue(MockResponse().setBody("""data: {"candidates":[{"content":{"parts":[{"text":"ok"}],"role":"model"}}]}"""))

        backend.complete(request("help")).toList()

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("x-goog-api-key")).isEqualTo(DUMMY_KEY)
        // The path+query is the entire URL surface MockWebServer records; the
        // key must appear nowhere in it, and the only query parameter is `alt`.
        assertThat(recorded.path).isNotNull()
        assertThat(recorded.path).doesNotContain(DUMMY_KEY)
        assertThat(recorded.path).doesNotContain("?key=")
        assertThat(recorded.path).endsWith(":streamGenerateContent?alt=sse")
    }

    @Test
    fun `no key returns a security error without touching the network`() = runTest {
        store.key = null
        val backend = backend()
        server.enqueue(MockResponse().setBody("ignored"))

        val events = backend.complete(request("help")).toList()

        val error = events.single() as AiEvent.Error
        assertThat(error.error.kind).isEqualTo(AppError.Kind.SECURITY)
        assertThat(error.error.step).isEqualTo("gemini-key")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a 4xx api error becomes a network error with the message redacted`() = runTest {
        val backend = backend()
        // The API echoes nothing, but a leak-prone server that echoed the key
        // back must not let it reach the UI or the log.
        val echoMessage = "API key not valid. You sent: $DUMMY_KEY"
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":{"code":403,"message":"$echoMessage","status":"PERMISSION_DENIED"}}"""),
        )

        val events = backend.complete(request("help")).toList()

        val error = (events.single() as AiEvent.Error).error
        assertThat(error.kind).isEqualTo(AppError.Kind.NETWORK)
        assertThat(error.message).doesNotContain(DUMMY_KEY)
        assertThat(error.message).contains(SecretRedactor.MASK)
    }

    @Test
    fun `health reports authorized on 200`() = runTest {
        val backend = backend()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val health = backend.health()

        assertThat(health.reachable).isTrue()
        assertThat(health.authorized).isTrue()
        assertThat(health.model).isEqualTo("gemini-test")
    }

    @Test
    fun `health reports reachable but unauthorized on 403`() = runTest {
        val backend = backend()
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))

        val health = backend.health()

        // The plan's decision: a network that answers 403 is reachable.
        assertThat(health.reachable).isTrue()
        assertThat(health.authorized).isFalse()
    }

    @Test
    fun `health reports unauthorized on 400`() = runTest {
        val backend = backend()
        server.enqueue(MockResponse().setResponseCode(400).setBody("{}"))

        val health = backend.health()

        assertThat(health.reachable).isTrue()
        assertThat(health.authorized).isFalse()
    }

    @Test
    fun `health reports unreachable when nothing answers`() = runTest {
        val goneServer = MockWebServer()
        goneServer.start()
        val backend = backend(goneServer)
        goneServer.shutdown()

        val health = backend.health()

        assertThat(health.reachable).isFalse()
        assertThat(health.authorized).isFalse()
    }

    @Test
    fun `a stalled stream becomes a network error, not a hang`() = runTest {
        val slowClient = OkHttpClient.Builder()
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .connectTimeout(2, TimeUnit.SECONDS)
            .build()
        val backend = GeminiBackend(
            client = slowClient,
            credentials = store,
            logger = logger,
            dispatchers = DefaultDispatcherProvider(),
            baseUrl = server.url("/"),
            model = "gemini-test",
        )
        // The server answers, then drips after the read timeout has passed.
        server.enqueue(MockResponse().setBody("started").setBodyDelay(2, TimeUnit.SECONDS))

        val events = backend.complete(request("help")).toList()

        val error = events.single() as AiEvent.Error
        assertThat(error.error.kind).isEqualTo(AppError.Kind.NETWORK)
        assertThat(error.error.step).isEqualTo("gemini-stream")
    }

    // ---------------------------------------------------------------- fixtures

    private fun backend(server: MockWebServer = this.server): GeminiBackend {
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
        return GeminiBackend(
            client = client,
            credentials = store,
            logger = logger,
            dispatchers = DefaultDispatcherProvider(),
            baseUrl = server.url("/"),
            model = "gemini-test",
        )
    }

    private fun request(prompt: String): AiRequest = AiRequest(
        conversation = listOf(AiMessage(AiRole.USER, prompt)),
    )

    private class FakeCredentialStore(var key: String?) : CredentialStore {
        override suspend fun put(k: CredentialKey, value: String) {
            key = value
        }

        override suspend fun get(k: CredentialKey): String? = key

        override suspend fun clear(k: CredentialKey) {
            key = null
        }
    }
}