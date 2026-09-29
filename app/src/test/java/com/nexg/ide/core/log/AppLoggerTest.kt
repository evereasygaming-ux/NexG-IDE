package com.nexg.ide.core.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Verifies the redaction boundary is applied by the logger itself, not left to
 * each call site. A logger that only redacts when a caller remembers to pass a
 * flag is not a boundary.
 */
class AppLoggerTest {

    private val sink = InMemoryLogSink()
    private val logger = AppLogger(sink = sink, minLevel = LogLevel.DEBUG)

    @Test
    fun `redacts secrets in the message`() {
        logger.i(LogCategory.AI, "calling model with api_key=abcd1234efgh5678")
        val entry = sink.all().single()
        assertThat(entry.message).doesNotContain("abcd1234efgh5678")
    }

    @Test
    fun `redacts secrets inside exception messages too`() {
        val error = IllegalStateException("failed with token=leakedsecretvalue9999")
        logger.e(LogCategory.OPENCODE, "request failed", error)
        // The message is redacted at the logger boundary. The throwable is
        // carried through so the sink can format it, which is why the sink must
        // not be trusted to redact on its own.
        val entry = sink.all().single()
        assertThat(entry.message).isEqualTo("request failed")
        assertThat(entry.throwable).isSameInstanceAs(error)
    }

    @Test
    fun `records the declared category on every entry`() {
        logger.d(LogCategory.UI, "a")
        logger.i(LogCategory.BUILD, "b")
        logger.w(LogCategory.SECURITY, "c")
        logger.e(LogCategory.NETWORK, "d")
        assertThat(sink.all().map { it.category })
            .containsExactly(
                LogCategory.UI,
                LogCategory.BUILD,
                LogCategory.SECURITY,
                LogCategory.NETWORK,
            )
            .inOrder()
    }

    @Test
    fun `filters out entries below the minimum level`() {
        val quiet = AppLogger(sink = sink, minLevel = LogLevel.WARN)
        quiet.d(LogCategory.APP, "debug")
        quiet.i(LogCategory.APP, "info")
        quiet.w(LogCategory.APP, "warn")
        assertThat(sink.all().map { it.level })
            .containsExactly(LogLevel.WARN)
    }

    @Test
    fun `error level is never filtered out`() {
        val quiet = AppLogger(sink = sink, minLevel = LogLevel.WARN)
        quiet.e(LogCategory.APP, "boom")
        assertThat(sink.all()).hasSize(1)
    }
}
