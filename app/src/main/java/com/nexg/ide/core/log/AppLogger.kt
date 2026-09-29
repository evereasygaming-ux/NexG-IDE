package com.nexg.ide.core.log

/**
 * Log categories from PLAN.MD (R13). Every log line must declare one, so
 * filtering stays predictable and no line ends up in an "misc" bucket.
 */
enum class LogCategory {
    UI,
    VOICE,
    AI,
    OPENCODE,
    TERMUX,
    UBUNTU,
    TERMINAL,
    FILES,
    SECURITY,
    BUILD,
    NETWORK,
    APP,
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Strips credential-shaped substrings before anything reaches a log sink.
 *
 * This is the Phase 1 safety boundary the plan asks for: keys, tokens and
 * passwords must not end up in logcat, where any app with READ_LOGS (or an
 * adb shell) can read them. Redaction happens on the way in rather than being
 * remembered at each call site, because call sites are where it gets forgotten.
 *
 * Pure Kotlin on purpose, so it is unit-testable on the JVM with no device.
 */
object SecretRedactor {

    const val MASK = "[REDACTED]"

    /** Named so the redaction report can say what was masked, not just that it was. */
    data class Rule(val name: String, val pattern: Regex)

    /**
     * `key = value` / `key: value` for known credential names.
     *
     * Requires a separator after the name, so prose like "token limit" is not
     * touched. Group 4 is the secret.
     */
    private val NAMED_ASSIGNMENT = Rule(
        name = "named-assignment",
        pattern = Regex(
            """(?i)\b(api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|auth[_-]?token|id[_-]?token|token|secret|password|passwd|pwd|authorization)\b\s*([:=])\s*(["']?)([^"'\s,;&]+)""",
        ),
    )

    /** `Authorization: Bearer <token>` and bare `Bearer <token>`. */
    private val BEARER = Rule(
        name = "bearer",
        pattern = Regex("""(?i)\bbearer\s+[A-Za-z0-9._\-]{8,}"""),
    )

    /** Google API keys, matched by shape rather than by name. */
    private val GOOGLE_API_KEY = Rule(
        name = "google-api-key",
        pattern = Regex("""\bAIza[0-9A-Za-z\-_]{35}\b"""),
    )

    /** Provider-style prefixed keys (`sk-`, `gsk_`, `xai-`, ...). */
    private val PREFIXED_KEY = Rule(
        name = "prefixed-key",
        pattern = Regex("""\b(?:sk-|gsk_|xai-|ghp_|github_pat_)[A-Za-z0-9_\-]{16,}\b"""),
    )

    /** Credentials passed as URL query parameters, e.g. `?key=abc`. */
    private val QUERY_PARAM = Rule(
        name = "query-param",
        pattern = Regex("""(?i)([?&](?:api[_-]?key|apikey|key|token|access_token)=)([^&\s"']+)"""),
    )

    /**
     * Ordered most-specific-first, which is the order [rulesTriggered] reports
     * in. Note this is deliberately *not* the execution order in [redact]:
     * bearer has to run before named-assignment, for the reason documented
     * there.
     */
    val rules: List<Rule> = listOf(
        NAMED_ASSIGNMENT,
        BEARER,
        GOOGLE_API_KEY,
        PREFIXED_KEY,
        QUERY_PARAM,
    )

    fun redact(input: String): String {
        if (input.isEmpty()) return input
        var out = input
        // Bearer runs FIRST. If the named-assignment rule went first it would
        // match "Authorization: Bearer <token>", treat the word "Bearer" as the
        // value, and rewrite it to "Authorization: [REDACTED] <token>" —
        // masking the scheme word and leaving the actual token in the log.
        out = BEARER.pattern.replace(out) { "Bearer $MASK" }
        out = replaceNamedAssignment(out)
        out = GOOGLE_API_KEY.pattern.replace(out, MASK)
        out = PREFIXED_KEY.pattern.replace(out, MASK)
        out = QUERY_PARAM.pattern.replace(out) { "${it.groupValues[1]}$MASK" }
        return out
    }

    private fun replaceNamedAssignment(input: String): String =
        NAMED_ASSIGNMENT.pattern.replace(input) { match ->
            // 1=name 2=separator 3=opening quote 4=secret 5=closing quote
            val name = match.groupValues[1]
            val separator = match.groupValues[2]
            val quote = match.groupValues[3]
            val value = match.groupValues[4]
            // "Authorization: Bearer [REDACTED]" — the bearer rule already
            // handled this line, so leave the scheme word alone rather than
            // producing "Authorization: [REDACTED] [REDACTED]".
            if (value.equals("bearer", ignoreCase = true)) {
                match.value
            } else {
                "$name$separator$quote$MASK"
            }
        }

    /** Test/debug helper: which rules would fire on this input. */
    fun rulesTriggered(input: String): List<String> =
        rules.filter { it.pattern.containsMatchIn(input) }.map { it.name }
}
