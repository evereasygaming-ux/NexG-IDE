package com.nexg.ide.core.result

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppResultTest {

    @Test
    fun `map transforms success value`() {
        val result: AppResult<Int> = AppResult.Success(2)
        val mapped = result.map { it * 21 }
        assertThat(mapped).isEqualTo(AppResult.Success(42))
    }

    @Test
    fun `map leaves loading alone`() {
        val result: AppResult<Int> = AppResult.Loading
        assertThat(result.map { it * 2 }).isEqualTo(AppResult.Loading)
    }

    @Test
    fun `map leaves failure alone`() {
        val error = AppError(kind = AppError.Kind.IO, message = "disk")
        val result: AppResult<Int> = AppResult.Failure(error)
        assertThat(result.map { it * 2 }).isEqualTo(AppResult.Failure(error))
    }

    @Test
    fun `flatMap chains another result`() {
        val result: AppResult<Int> = AppResult.Success(4)
        val chained = result.flatMap { AppResult.Success(it + 1) }
        assertThat(chained).isEqualTo(AppResult.Success(5))
    }

    @Test
    fun `flatMap short circuits on failure`() {
        val error = AppError(kind = AppError.Kind.NETWORK, message = "offline")
        val result: AppResult<Int> = AppResult.Failure(error)
        val chained = result.flatMap { AppResult.Success(it) }
        assertThat(chained).isEqualTo(AppResult.Failure(error))
    }

    @Test
    fun `onSuccess fires only for success`() {
        val seen = mutableListOf<Int>()
        AppResult.Success(7).onSuccess { seen += it }
        AppResult.Loading.onSuccess { seen += 1 }
        AppResult.Failure(AppError(AppError.Kind.UNKNOWN, "x")).onSuccess { seen += 1 }
        assertThat(seen).containsExactly(7)
    }

    @Test
    fun `onFailure fires only for failure`() {
        val seen = mutableListOf<AppError>()
        AppResult.Success(1).onFailure { seen += it }
        AppResult.Loading.onFailure { seen += AppError(AppError.Kind.UNKNOWN, "x") }
        val real = AppError(AppError.Kind.SECURITY, "denied")
        AppResult.Failure(real).onFailure { seen += it }
        assertThat(seen).containsExactly(real)
    }

    @Test
    fun `getOrNull returns value only on success`() {
        assertThat(AppResult.Success(3).getOrNull()).isEqualTo(3)
        // Loading and Failure are AppResult<Nothing>, so their getOrNull() has
        // the static type Nothing?. Truth has no assertThat for that, hence the
        // explicit Int? locals: they pin the value down as a real nullable
        // rather than letting inference fall through to a Nothing? overload.
        val fromLoading: Int? = AppResult.Loading.getOrNull()
        val fromFailure: Int? = AppResult.Failure(AppError(AppError.Kind.IO, "x")).getOrNull()
        assertThat(fromLoading).isNull()
        assertThat(fromFailure).isNull()
    }

    @Test
    fun `errorOrNull returns error only on failure`() {
        val error = AppError(AppError.Kind.PARSE, "bad json")
        assertThat(AppResult.Failure(error).errorOrNull()).isEqualTo(error)
        assertThat(AppResult.Success(1).errorOrNull()).isNull()
    }

    @Test
    fun `describe gives user facing text not exception text`() {
        val error = AppError(
            kind = AppError.Kind.IO,
            message = "java.io.FileNotFoundException: /data/user/0/secret/path",
            cause = IllegalStateException("raw"),
        )
        // The user-facing string is the generic description. The raw message
        // with a filesystem path stays available for logs only.
        assertThat(error.describe()).isEqualTo("Could not read or write local storage")
    }

    @Test
    fun `success result is covariant so it fits a narrower type`() {
        val anyResult: AppResult<Any> = AppResult.Success("text")
        assertThat(anyResult).isInstanceOf(AppResult.Success::class.java)
    }
}
