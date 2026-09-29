package com.nexg.ide.core.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Redaction is a security boundary, so it is tested against the shapes of
 * secrets the app will actually handle: a Gemini key, an OpenAI-style key, a
 * bearer token, and key/value pairs in logs and URLs.
 */
class SecretRedactorTest {

    @Test
    fun `redacts google api key by shape`() {
        // Assembled from two parts deliberately. The runtime value is exactly
        // the shape GOOGLE_API_KEY matches ("AIza" + 35 word characters), so
        // the rule is exercised identically, but no contiguous credential-
        // shaped literal is ever stored in the repository. Never re-join these
        // two literals.
        val key = "AIza" + "NotARealGoogleApiKey_ThisIsAFixture"
        val redacted = SecretRedactor.redact("sending key=$key to model")
        assertThat(redacted).doesNotContain(key)
        assertThat(redacted).contains(SecretRedactor.MASK)
    }

    @Test
    fun `redacts bearer token`() {
        val redacted = SecretRedactor.redact("Authorization: Bearer abc123def456ghi789")
        assertThat(redacted).doesNotContain("abc123def456ghi789")
        assertThat(redacted).contains("Bearer")
    }

    @Test
    fun `redacts named assignment but keeps the name`() {
        val redacted = SecretRedactor.redact("api_key = super-secret-value")
        assertThat(redacted).contains("api_key")
        assertThat(redacted).doesNotContain("super-secret-value")
    }

    @Test
    fun `redacts password in colon form`() {
        val redacted = SecretRedactor.redact("password: hunter2hunter2")
        assertThat(redacted).doesNotContain("hunter2hunter2")
    }

    @Test
    fun `redacts secret passed as url query parameter`() {
        // Same split-literal rule as the shape test above; never re-join.
        val keyInUrl = "AIza" + "NotARealQueryParamSecret"
        val redacted = SecretRedactor.redact("GET /v1/models?key=$keyInUrl")
        assertThat(redacted).doesNotContain(keyInUrl)
        assertThat(redacted).contains("key=")
    }

    @Test
    fun `redacts provider prefixed key`() {
        val prefixedKey = "sk-" + "NotARealOpenAiKeyJustATestFixture"
        val redacted = SecretRedactor.redact("token $prefixedKey")
        assertThat(redacted).doesNotContain(prefixedKey)
    }

    @Test
    fun `leaves ordinary logs untouched`() {
        val message = "Build finished in 1.2s with 0 errors"
        assertThat(SecretRedactor.redact(message)).isEqualTo(message)
    }

    @Test
    fun `does not redact prose that merely mentions token or key`() {
        // Guards against an over-eager pattern turning every log line into
        // "[REDACTED]" and making the logs useless.
        val message = "Token limit reached while counting keys in the project"
        assertThat(SecretRedactor.redact(message)).isEqualTo(message)
    }

    @Test
    fun `redaction is idempotent`() {
        val once = SecretRedactor.redact("api_key=abcd1234")
        assertThat(SecretRedactor.redact(once)).isEqualTo(once)
    }

    @Test
    fun `handles empty string`() {
        assertThat(SecretRedactor.redact("")).isEqualTo("")
    }

    @Test
    fun `reports which rule fired`() {
        val fired = SecretRedactor.rulesTriggered("Authorization: Bearer abc123def456ghi789")
        assertThat(fired).contains("bearer")
    }
}
