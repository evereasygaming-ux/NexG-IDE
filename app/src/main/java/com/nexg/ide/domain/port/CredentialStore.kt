package com.nexg.ide.domain.port

/**
 * A credential this app can hold, stored by [CredentialStore] under a
 * provider-controlled name. The enum is the whitelist: storing credentials
 * under arbitrary caller-chosen keys would let the store silently grow a
 * general-purpose secret vault, which is scope this app does not have.
 */
enum class CredentialKey(val storeName: String) {
    GEMINI_API_KEY("gemini_api_key"),
}

/**
 * The bring-your-own-key boundary (PLAN.MD 4.5 / decision C5).
 *
 * The only holder of the user's Gemini key. Everything that needs the key —
 * the AI backend over the network — asks here; nothing else is allowed to
 * hold a copy, and nothing upstream may ever log what was returned.
 *
 * Pure interface so the unit tests can inject an in-memory store and assert
 * that, say, the backend never fires a request at all when no key is present.
 */
interface CredentialStore {
    suspend fun put(key: CredentialKey, value: String)

    /** `null` when never stored or explicitly cleared. */
    suspend fun get(key: CredentialKey): String?

    suspend fun clear(key: CredentialKey): Unit
}