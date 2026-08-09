package com.gohil.bookkeeper.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores statement passwords in the OS-backed encrypted store.
 *
 * This is the Android equivalent of the desktop app's use of the Windows Credential Manager.
 * The encryption key lives in the Android Keystore, backed by hardware where the device
 * provides it, and never leaves it — the app handles ciphertext and asks the Keystore to
 * decrypt. Passwords are never written to a config file, never logged, and never leave
 * the device (the app has no INTERNET permission at all).
 */
class PasswordVault(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * @param source a stable label for the document, e.g. "BANK" or "CREDIT_CARD", so the
     *   password is asked for once and reused for later months.
     */
    fun get(source: String): Secret? =
        prefs.getString(key(source), null)?.let(::Secret)

    fun put(source: String, password: String) {
        prefs.edit().putString(key(source), password).apply()
    }

    fun forget(source: String) {
        prefs.edit().remove(key(source)).apply()
    }

    fun forgetAll() {
        prefs.edit().clear().apply()
    }

    fun knownSources(): Set<String> =
        prefs.all.keys.filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) }.toSet()

    private fun key(source: String) = PREFIX + source.uppercase()

    companion object {
        private const val FILE_NAME = "statement_passwords"
        private const val PREFIX = "pw_"
    }
}

/**
 * Wraps a password so it cannot be logged by accident.
 *
 * String interpolation into a log line or a crash report is the realistic way a credential
 * escapes, and it is silent when it happens. Reading the value has to be deliberate.
 */
@JvmInline
value class Secret(private val value: String) {

    fun reveal(): String = value

    override fun toString(): String = "Secret(****)"
}
