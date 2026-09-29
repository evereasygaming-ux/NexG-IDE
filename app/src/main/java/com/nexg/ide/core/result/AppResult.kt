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
