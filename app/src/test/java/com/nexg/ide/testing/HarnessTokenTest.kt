package com.nexg.ide.testing

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.SecureRandom

/**
 * The per-install token itself: generation, shape, and comparison.
 *
 * [HarnessToken] is deliberately free of Android types in the parts tested here,
 * so these run on the plain JVM with no Robolectric.
 */
class HarnessTokenTest {

    private val real = HarnessToken.newToken()

    @Test
    fun generates128BitsOfHex() {
        assertThat(real).hasLength(HarnessToken.TOKEN_CHARS)
        assertThat(real).isEqualTo(real.lowercase())
        assertThat(HarnessToken.TOKEN_CHARS).isEqualTo(32)
    }

    @Test
    fun generationIsNotConstant() {
        // A hard-coded or seeded-once token would defeat the entire control.
        val tokens = List(64) { HarnessToken.newToken() }
        assertThat(tokens.toSet()).hasSize(tokens.size)
    }

    @Test
    fun wellFormedAcceptsAGeneratedTokenAndRejectsEverythingElse() {
        assertThat(HarnessToken.isWellFormed(real)).isTrue()
        assertThat(HarnessToken.isWellFormed("")).isFalse()
        assertThat(HarnessToken.isWellFormed("short")).isFalse()
        assertThat(HarnessToken.isWellFormed("z".repeat(HarnessToken.TOKEN_CHARS))).isFalse()
        // Uppercase hex is a different string; it must not be silently accepted.
        assertThat(HarnessToken.isWellFormed(real.uppercase())).isFalse()
        // Right length, one character of trailing whitespace.
        assertThat(HarnessToken.isWellFormed("$real ")).isFalse()
    }

    @Test
    fun matchesTheExactToken() {
        assertThat(HarnessToken.matches(real, real)).isTrue()
    }

    @Test
    fun rejectsMissingAndEmpty() {
        assertThat(HarnessToken.matches(real, null)).isFalse()
        assertThat(HarnessToken.matches(null, real)).isFalse()
        assertThat(HarnessToken.matches(null, null)).isFalse()
        assertThat(HarnessToken.matches(real, "")).isFalse()
    }

    @Test
    fun rejectsAWellFormedButDifferentToken() {
        // Same length, same alphabet, differs in exactly one character, so this
        // is the case a prefix-comparing equality check could get wrong.
        val other = if (real.startsWith("0")) "1${real.substring(1)}" else "0${real.substring(1)}"
        assertThat(HarnessToken.isWellFormed(other)).isTrue()
        assertThat(HarnessToken.matches(real, other)).isFalse()
    }

    @Test
    fun rejectsTheSameTokenWithOneCharacterTruncated() {
        assertThat(HarnessToken.matches(real, real.dropLast(1))).isFalse()
    }

    @Test
    fun usesTheInjectedGeneratorDeterministically() {
        // Proves the generator really is the only entropy source.
        var counter = 0
        val fake = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                bytes.indices.forEach { bytes[it] = (counter++ and 0xFF).toByte() }
            }
        }
        val token = HarnessToken.newToken(fake)
        assertThat(HarnessToken.isWellFormed(token)).isTrue()
        assertThat(token).isEqualTo("000102030405060708090a0b0c0d0e0f")
    }
}

/** The authorization decision, as distinct from the token primitives. */
class HarnessAuthTest {

    private val expected = HarnessToken.newToken()

    @Test
    fun missingTokenIsRejected() {
        assertThat(HarnessAuth.authorize(expected, null))
            .isEqualTo(HarnessAuth.Verdict.Reject(HarnessAuth.MISSING_TOKEN))
        assertThat(HarnessAuth.authorize(expected, ""))
            .isEqualTo(HarnessAuth.Verdict.Reject(HarnessAuth.MISSING_TOKEN))
    }

    @Test
    fun invalidTokenIsRejected() {
        // Right idea, wrong shape.
        val verdict = HarnessAuth.authorize(expected, "hunter2")
        assertThat(verdict).isEqualTo(HarnessAuth.Verdict.Reject(HarnessAuth.MALFORMED_TOKEN))
    }

    @Test
    fun wellFormedButWrongTokenIsRejected() {
        val other = HarnessToken.newToken()
        assertThat(HarnessAuth.authorize(expected, other))
            .isEqualTo(HarnessAuth.Verdict.Reject(HarnessAuth.WRONG_TOKEN))
    }

    @Test
    fun validTokenIsAllowed() {
        assertThat(HarnessAuth.authorize(expected, expected))
            .isEqualTo(HarnessAuth.Verdict.Allow)
    }

    @Test
    fun noExpectedTokenMeansNothingIsAllowed() {
        // A receiver that somehow has no token must not accept an empty one.
        assertThat(HarnessAuth.authorize(null, ""))
            .isEqualTo(HarnessAuth.Verdict.Reject(HarnessAuth.MISSING_TOKEN))
        assertThat(HarnessAuth.authorize(null, expected))
            .isEqualTo(HarnessAuth.Verdict.Reject(HarnessAuth.WRONG_TOKEN))
    }

    @Test
    fun everyRejectionCarriesOneIndistinguishableCode() {
        // The caller must not learn whether the token was malformed or merely
        // wrong, so all three reasons collapse to the same wire code.
        listOf(null, "", "hunter2", HarnessToken.newToken()).forEach { provided ->
            val response = when (HarnessAuth.authorize(expected, provided)) {
                is HarnessAuth.Verdict.Reject -> HarnessAuth.rejectionResponse()
                HarnessAuth.Verdict.Allow -> error("must not be allowed")
            }
            assertThat(response.errorCode).isEqualTo(HarnessAuth.UNAUTHORIZED)
        }
    }
}
