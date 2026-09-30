package com.nexg.ide.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nexg.ide.domain.port.CredentialKey
import com.nexg.ide.domain.port.CredentialStore

/**
 * Keystore-backed implementation of [CredentialStore] (PLAN.MD 4.5,
 * `data/local/SecurePrefs`, decision C5: bring-your-own-key).
 *
 * The Gemini key never touches plain `SharedPreferences`, never appears in a
 * log line and is never written under a name another subsystem could
 * accidentally read. [EncryptedSharedPreferences] stores it with
 * AES-256-GCM values under AES-256-SIV keys derived from a hardware-backed
 * [MasterKey].
 *
 * Deprecation note, recorded in PLAN.MD: `androidx.security:security-crypto`
 * 1.1.0 deprecates this API on API 33+ in favour of a feature that is not yet
 * stable. The decision to stay on the deprecated API is documented there, with
 * the migration path; this class is the only place the decision is allowed to
 * leak to.
 */
class SecurePrefs(context: Context) : CredentialStore {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            // Keys do not need encryption, only integrity; values need both.
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun put(key: CredentialKey, value: String) {
        prefs.edit().putString(key.storeName, value).commit()
    }

    override suspend fun get(key: CredentialKey): String? = prefs.getString(key.storeName, null)

    override suspend fun clear(key: CredentialKey) {
        prefs.edit().remove(key.storeName).commit()
    }

    private companion object {
        const val FILE_NAME: String = "nexg_secure"
    }
}