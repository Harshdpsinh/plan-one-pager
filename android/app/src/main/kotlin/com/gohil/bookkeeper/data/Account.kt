package com.gohil.bookkeeper.data

import android.content.Context
import com.gohil.bookkeeper.core.model.StatementSource
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class AccountKind {
    BANK,
    CREDIT_CARD,
    ;

    val source: StatementSource
        get() = if (this == BANK) StatementSource.BANK else StatementSource.CREDIT_CARD

    val label: String get() = if (this == BANK) "Bank account" else "Credit card"
}

/**
 * One bank account or credit card the user holds.
 *
 * Statement passwords differ per account, and most people have several of each, so the
 * password belongs to the account rather than to the document type. The account also gives
 * the password somewhere stable to live: next month's statement for the same account reuses
 * it without being asked again.
 *
 * The password itself is deliberately not a field here — this record is stored in plain
 * preferences, while the password goes to the Keystore-backed vault under [vaultKey].
 */
@Serializable
data class Account(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val kind: AccountKind,
) {
    val vaultKey: String get() = "acct_$id"
}

/** The user's accounts, and the passwords that go with them. */
class AccountStore(context: Context) {

    private val prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)
    private val vault = PasswordVault(context)

    fun all(): List<Account> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        // A corrupt list must not brick the app; the user can re-add accounts.
        return runCatching { JSON.decodeFromString<List<Account>>(raw) }.getOrDefault(emptyList())
    }

    fun ofKind(kind: AccountKind): List<Account> = all().filter { it.kind == kind }

    fun add(label: String, kind: AccountKind, password: String?): Account {
        val account = Account(label = label.trim(), kind = kind)
        save(all() + account)
        if (!password.isNullOrBlank()) vault.put(account.vaultKey, password)
        return account
    }

    fun rename(id: String, label: String) {
        save(all().map { if (it.id == id) it.copy(label = label.trim()) else it })
    }

    fun setPassword(id: String, password: String) {
        all().firstOrNull { it.id == id }?.let { vault.put(it.vaultKey, password) }
    }

    fun passwordFor(account: Account): Secret? = vault.get(account.vaultKey)

    fun hasPassword(account: Account): Boolean = passwordFor(account) != null

    fun remove(id: String) {
        val account = all().firstOrNull { it.id == id } ?: return
        vault.forget(account.vaultKey)
        save(all().filterNot { it.id == id })
    }

    /**
     * Every stored password, with [preferred]'s first.
     *
     * The extractor tries these in order. Leading with the account the file was filed under
     * makes the common case a single attempt, while still opening a statement that was
     * tagged to the wrong account — which is easy to do with several similarly named cards.
     * All of these are the user's own passwords, held on their own device, and an encrypted
     * PDF has no lockout or rate limit, so trying the rest costs nothing but a moment.
     */
    fun passwordCandidates(preferred: Account?): List<String> {
        val accounts = all()
        val ordered = listOfNotNull(preferred) + accounts.filter { it.id != preferred?.id }
        return ordered.mapNotNull { vault.get(it.vaultKey)?.reveal() }.distinct()
    }

    private fun save(accounts: List<Account>) {
        prefs.edit().putString(KEY, JSON.encodeToString(accounts)).apply()
    }

    companion object {
        private const val KEY = "accounts_json"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
