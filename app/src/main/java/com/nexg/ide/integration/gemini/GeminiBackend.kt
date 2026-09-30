package com.nexg.ide.integration.gemini

import com.nexg.ide.core.dispatch.DefaultDispatcherProvider
import com.nexg.ide.core.dispatch.DispatcherProvider
import com.nexg.ide.core.log.AppLogger
import com.nexg.ide.core.log.LogCategory
import com.nexg.ide.core.log.SecretRedactor
import com.nexg.ide.core.result.AppError
import com.nexg.ide.domain.model.AiEvent
import com.nexg.ide.domain.model.AiRequest
import com.nexg.ide.domain.model.BackendHealth
import com.nexg.ide.domain.port.AiBackend
import com.nexg.ide.domain.port.CredentialKey
import com.nexg.ide.domain.port.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The Tier 1 AI backend (PLAN.MD 4.9: HTTPS + SSE to
 * `generativelanguage.googleapis.com`, BYOK, streaming).
 *
 * Two rules are contractual and tested:
 *
 *  1. **The key travels in the `x-goog-api-key` header, never in the URL.** A
 *     query parameter ends up in server logs, proxy logs and our own
 *     network-facing logs the moment anyone makes the transport mistake this
 *     class is wired to refuse. The key also never appears in a log line: the
 *     only logger calls here log counts and statuses, and anything that could
 *     carry it is run through [SecretRedactor].
 *
 *  2. **Not configured is a terminal error, not a network attempt.** With no
 *     key stored, [complete] returns a single `SECURITY` error without opening
 *     a socket, so the UI can say "add your key in Settings" instead of the
 *     lie "Gemini is unreachable".
 *
 * `complete` streams Server-Sent Events one line at a time through
 * [GeminiStream], which keeps the SSE + JSON parsing pure and unit-tested and
 * this class a thin transport layer.
 */
class GeminiBackend(
    private val client: OkHttpClient,
    private val credentials: CredentialStore,
    private val logger: AppLogger,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider(),
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
) : AiBackend {

    private val json = Json { ignoreUnknownKeys = true }

    override val id: String = "gemini"
    override val requiresNetwork: Boolean = true

    // ------------------------------------------------------------------ health

    override suspend fun health(): BackendHealth = withContext(dispatchers.io) {
        val url = modelEndpoint().build()
        val key = credentials.get(CredentialKey.GEMINI_API_KEY).orEmpty()
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", key)
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> BackendHealth(
                        reachable = true,
                        authorized = true,
                        model = model,
                        detail = "reachable and authorized",
                    )
                    // A 400/403 with a request made of the right shape is the
                    // plan's proof of connectivity: the network works, the key is
                    // missing or rejected. Reporting "offline" would be a lie.
                    response.code == 400 || response.code == 403 -> BackendHealth(
                        reachable = true,
                        authorized = false,
                        model = model,
                        detail = "network OK; API key missing or rejected (${response.code})",
                    )
                    else -> BackendHealth(
                        reachable = true,
                        authorized = false,
                        model = model,
                        detail = "unexpected status ${response.code}",
                    )
                }
            }
        } catch (e: IOException) {
            // No key appears in an IOException message (it never reaches the
            // URL), but redact anyway: this message is user-facing.
            BackendHealth(
                reachable = false,
                authorized = false,
                model = model,
                detail = "not reachable (${SecretRedactor.redact(e.message ?: "network failure")})",
            )
        }
    }

    // --------------------------------------------------------------- streaming

    override suspend fun complete(request: AiRequest): Flow<AiEvent> = flow {
        val key = credentials.get(CredentialKey.GEMINI_API_KEY)
        if (key.isNullOrBlank()) {
            emit(
                AiEvent.Error(
                    AppError(
                        kind = AppError.Kind.SECURITY,
                        message = "No Gemini API key is configured. Add one in Settings.",
                        step = "gemini-key",
                    ),
                ),
            )
            return@flow
        }

        val prompt = PromptBuilder.build(request)
        val payload = buildWireRequest(prompt, request)

        val url = baseUrl.newBuilder()
            .addPathSegment("v1beta")
            .addPathSegment("models")
            .addPathSegment(model)
            .addPathSegment(":streamGenerateContent")
            .addQueryParameter("alt", "sse")
            .build()

        val httpRequest = Request.Builder()
            .url(url)
            .header("x-goog-api-key", key)
            .header("Accept", "text/event-stream")
            .post(payload)
            .build()

        // Counts and lengths only — never the key, never the prompt text.
        logger.i(
            LogCategory.NETWORK,
            "gemini.complete model=$model turns=${prompt.contents.size} " +
                "systemChars=${prompt.systemInstruction.length}",
        )

        // Everything below runs directly in the flow body, which `flowOn`
        // already dispatches to the IO dispatcher. A nested `withContext(io)`
        // would wrap emissions in an extra context element and trip the flow
        // invariant, so the blocking OkHttp call must live on the flowOn thread
        // rather than inside another dispatcher hop.
        val response = try {
            client.newCall(httpRequest).execute()
        } catch (e: IOException) {
            emit(AiEvent.Error(toNetworkError(e, "gemini-connect")))
            return@flow
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val status = resp.code
                val body = resp.body?.string()
                val apiMessage = parseApiMessage(body)
                logger.w(
                    LogCategory.NETWORK,
                    "gemini.complete non-2xx status=$status ${apiMessage?.let { "detailLen=${it.length}" } ?: ""}",
                )
                emit(
                    AiEvent.Error(
                        AppError(
                            kind = AppError.Kind.NETWORK,
                            message = SecretRedactor.redact(
                                apiMessage ?: "Gemini returned HTTP $status",
                            ),
                            step = "gemini-complete",
                        ),
                    ),
                )
                return@flow
            }

            val source = resp.body?.source()
            if (source == null) {
                emit(AiEvent.Error(toNetworkError(IOException("empty response body"), "gemini-stream")))
                return@flow
            }

            var sawDone = false
            try {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    GeminiStream.eventsFor(line).forEach { parsed ->
                        when (parsed) {
                            is GeminiStreamEvent.Delta -> emit(AiEvent.Delta(parsed.text))
                            is GeminiStreamEvent.Finished -> {
                                sawDone = true
                                emit(AiEvent.Done(parsed.finishReason))
                            }
                            is GeminiStreamEvent.Failure ->
                                emit(
                                    AiEvent.Error(
                                        AppError(
                                            kind = AppError.Kind.PARSE,
                                            message = SecretRedactor.redact(parsed.message),
                                            step = "gemini-stream",
                                        ),
                                    ),
                                )
                        }
                    }
                }
            } catch (e: IOException) {
                emit(AiEvent.Error(toNetworkError(e, "gemini-stream")))
                return@flow
            }

            if (!sawDone) emit(AiEvent.Done(finishReason = null))
        }
    }.flowOn(dispatchers.io)

    // ------------------------------------------------------------------ wiring

    private fun modelEndpoint(): HttpUrl.Builder = baseUrl.newBuilder()
        .addPathSegment("v1beta")
        .addPathSegment("models")
        .addPathSegment(model)

    private fun buildWireRequest(prompt: GeminiPrompt, request: AiRequest): RequestBody {
        val contents = prompt.contents.map { turn ->
            WireContentDto(
                role = turn.role.name.lowercase(),
                parts = listOf(WirePartDto(text = turn.content)),
            )
        }.filter { it.role != "system" }

        val dto = GenerateRequestDto(
            systemInstruction = if (prompt.systemInstruction.isNotBlank()) {
                WireContentDto(parts = listOf(WirePartDto(text = prompt.systemInstruction)))
            } else null,
            contents = contents,
            generationConfig = GenerationConfigDto(
                temperature = request.temperature,
                maxOutputTokens = request.maxOutputTokens,
            ),
        )
        return json.encodeToString(GenerateRequestDto.serializer(), dto)
            .toRequestBody("application/json".toMediaType())
    }

    private fun parseApiMessage(body: String?): String? = body
        ?.let { runCatching { json.decodeFromString<StreamPayload>(it) }.getOrNull() }
        ?.error
        ?.takeIf { !it.message.isNullOrBlank() }
        ?.message

    private fun toNetworkError(e: IOException, step: String): AppError = AppError(
        kind = AppError.Kind.NETWORK,
        message = SecretRedactor.redact(
            e.message?.takeIf { it.isNotBlank() } ?: "network request failed",
        ),
        step = step,
    )

    companion object {
        val DEFAULT_BASE_URL: HttpUrl = "https://generativelanguage.googleapis.com".toHttpUrl()
        const val DEFAULT_MODEL: String = "gemini-2.0-flash"
    }
}

@Serializable
internal data class GenerateRequestDto(
    @SerialName("systemInstruction") val systemInstruction: WireContentDto? = null,
    val contents: List<WireContentDto> = emptyList(),
    @SerialName("generationConfig") val generationConfig: GenerationConfigDto = GenerationConfigDto(),
)

@Serializable
internal data class WireContentDto(
    val role: String? = null,
    val parts: List<WirePartDto> = emptyList(),
)

@Serializable
internal data class WirePartDto(val text: String? = null)

@Serializable
internal data class GenerationConfigDto(
    val temperature: Double = 0.2,
    @SerialName("maxOutputTokens") val maxOutputTokens: Int = 4096,
)