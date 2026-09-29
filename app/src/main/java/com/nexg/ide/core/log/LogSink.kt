package com.nexg.ide.core.log

import android.util.Log

/**
 * Where formatted log lines end up.
 *
 * Behind an interface so the logger can be exercised on the JVM in unit tests
 * (no Robolectric, no device) by swapping in [InMemoryLogSink].
 */
interface LogSink {
    fun write(entry: LogEntry)
}

data class LogEntry(
    val category: LogCategory,
    val level: LogLevel,
    val message: String,
    val throwable: Throwable? = null,
)

/**
 * The production sink.
 *
 * Redaction is applied in [AppLogger] before this is reached, so nothing
 * sensitive is ever handed to `Log`. Category is folded into the tag
 * (`"NexG/AI"`) rather than the message, so `logcat -s` filtering works.
 */
class LogcatSink : LogSink {
    override fun write(entry: LogEntry) {
        val tag = "NexG/${entry.category.name}"
        val body = if (entry.throwable == null) {
            entry.message
        } else {
            entry.message + "\n" + Log.getStackTraceString(entry.throwable)
        }
        when (entry.level) {
            LogLevel.DEBUG -> Log.d(tag, body)
            LogLevel.INFO -> Log.i(tag, body)
            LogLevel.WARN -> Log.w(tag, body)
            LogLevel.ERROR -> Log.e(tag, body)
        }
    }
}

/** Collects entries in memory. Unit tests and future in-app Logs screen. */
class InMemoryLogSink : LogSink {
    private val entries = mutableListOf<LogEntry>()

    override fun write(entry: LogEntry) {
        entries += entry
    }

    fun all(): List<LogEntry> = entries.toList()

    fun clear() = entries.clear()
}

/**
 * The single logging entry point for the app (PLAN.MD R13).
 *
 * Every message and every throwable message passes through [SecretRedactor]
 * here, so no call site can skip redaction by forgetting it. Debug builds may
 * raise verbosity; release builds should be raised to [LogLevel.INFO] or above
 * once real call sites exist.
 */
class AppLogger(
    private val sink: LogSink,
    private val minLevel: LogLevel = LogLevel.DEBUG,
) {
    fun d(category: LogCategory, message: String) = log(LogLevel.DEBUG, category, message, null)
    fun i(category: LogCategory, message: String) = log(LogLevel.INFO, category, message, null)
    fun w(category: LogCategory, message: String, throwable: Throwable? = null) =
        log(LogLevel.WARN, category, message, throwable)

    fun e(category: LogCategory, message: String, throwable: Throwable? = null) =
        log(LogLevel.ERROR, category, message, throwable)

    private fun log(
        level: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable?,
    ) {
        if (level.ordinal < minLevel.ordinal) return
        sink.write(
            LogEntry(
                category = category,
                level = level,
                message = SecretRedactor.redact(message),
                throwable = throwable,
            ),
        )
    }
}
