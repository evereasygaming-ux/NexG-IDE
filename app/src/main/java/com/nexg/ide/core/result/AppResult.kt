package com.nexg.ide.core.result

/**
 * Minimal result wrapper for async work crossing the domain/UI boundary.
 *
 * Phase 1 intentionally keeps this small: three cases, no sealed class
 * hierarchies per feature, no exception wrapping beyond [AppError]. The point
 * is that ViewModels and screens have one way to express "loading / value /
 * failed" instead of inventing one per feature.
 */
sealed interface AppResult<out T> {

    data object Loading : AppResult<Nothing>

    data class Success<out T>(val data: T) : AppResult<T>

    data class Failure(val error: AppError) : AppResult<Nothing>
}

/**
 * A failure that is safe to show a user and safe to log.
 *
 * [cause] is deliberately kept separate from [message] so a raw exception with
 * a stack trace never becomes user-facing text. Anything that must not reach a
 * log sink has to be converted through [AppError] first.
 */
data class AppError(
    val kind: Kind,
    val message: String,
    val cause: Throwable? = null,
    /**
     * Short, author-controlled label naming the step that failed, or `null`.
     *
     * [describe] alone is not enough to debug with. Phase 2 measured this the
     * hard way: ELEVEN distinct `Kind.IO` failure sites all rendered as the one
     * sentence "Could not read or write local storage", so a failed project
     * creation was undiagnosable from the UI and the failing step had to be
     * guessed. [message] cannot simply be shown instead, because several of
     * ours embed a document tree URI and the UI is documented as never
     * rendering those.
     *
     * So the step is a separate field: enough to identify where the failure
     * came from ("writing settings.gradle.kts", "taking the folder write
     * grant"), short enough to put on screen, and containing nothing but a
     * literal this codebase chose. Never a URI, never a user string, never
     * part of an exception message.
     */
    val step: String? = null,
) {
    enum class Kind {
        IO,
        NETWORK,
        SECURITY,
        PARSE,
        UNSUPPORTED,
        UNKNOWN,
    }

    fun describe(): String = when (kind) {
        Kind.IO -> "Could not read or write local storage"
        Kind.NETWORK -> "Network request failed"
        Kind.SECURITY -> "Permission or credential problem"
        Kind.PARSE -> "Unexpected data format"
        Kind.UNSUPPORTED -> "Not supported on this device"
        Kind.UNKNOWN -> "Something went wrong"
    }

    /**
     * The user-facing phrase plus the failing step, when one is known.
     *
     * This is what the create/open and Explorer error surfaces show, because
     * [describe] on its own cannot tell a refused directory creation apart from
     * a failed file write — they are both `Kind.IO` and they render identically.
     * [step] is safe to show precisely because it is a literal this codebase
     * writes, not a URI and not a provider exception string; [message] is still
     * never rendered.
     */
    fun describeWithStep(): String =
        if (step.isNullOrBlank()) describe() else "${describe()} (at $step)"
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Loading -> AppResult.Loading
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}

inline fun <T, R> AppResult<T>.flatMap(
    transform: (T) -> AppResult<R>,
): AppResult<R> = when (this) {
    is AppResult.Loading -> AppResult.Loading
    is AppResult.Success -> transform(data)
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> = apply {
    if (this is AppResult.Success) action(data)
}

inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> = apply {
    if (this is AppResult.Failure) action(error)
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.data

fun <T> AppResult<T>.errorOrNull(): AppError? = (this as? AppResult.Failure)?.error
