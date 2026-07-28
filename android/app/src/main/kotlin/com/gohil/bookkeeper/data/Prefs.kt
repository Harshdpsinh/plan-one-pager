package com.gohil.bookkeeper.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.gohil.bookkeeper.core.rules.CategoryRules
import java.io.File

/**
 * Remembers the user's setup between runs: which workbooks, which tabs, which rules.
 *
 * Nothing sensitive lives here — passwords go to [PasswordVault]. What this holds is the
 * long-lived SAF permission grants, which is what lets the app reopen the same two workbooks
 * next month without the user hunting for them again.
 */
class Prefs(private val context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var purchaseWorkbook: Uri?
        get() = prefs.getString(KEY_PURCHASE, null)?.let(Uri::parse)
        set(value) = prefs.edit().putString(KEY_PURCHASE, value?.toString()).apply()

    var salesWorkbook: Uri?
        get() = prefs.getString(KEY_SALES, null)?.let(Uri::parse)
        set(value) = prefs.edit().putString(KEY_SALES, value?.toString()).apply()

    var backupFolder: Uri?
        get() = prefs.getString(KEY_BACKUP, null)?.let(Uri::parse)
        set(value) = prefs.edit().putString(KEY_BACKUP, value?.toString()).apply()

    /**
     * The two workbooks name their tabs differently — the purchase register uses the short
     * month (Jun) and the sales register the full one (June) — so they are stored separately
     * rather than derived from one field.
     */
    var purchaseTab: String
        get() = prefs.getString(KEY_PURCHASE_TAB, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PURCHASE_TAB, value.trim()).apply()

    var salesTab: String
        get() = prefs.getString(KEY_SALES_TAB, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SALES_TAB, value.trim()).apply()

    var createMissingTabs: Boolean
        get() = prefs.getBoolean(KEY_CREATE_TABS, true)
        set(value) = prefs.edit().putBoolean(KEY_CREATE_TABS, value).apply()

    /** Keeps read/write access to a picked document across app restarts. */
    fun persist(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    // ── categorisation rules ─────────────────────────────────────────────────────

    private val rulesFile: File get() = File(context.filesDir, "categories.json")

    /**
     * Loads the editable rules, falling back to the shipped defaults.
     *
     * A malformed file after a hand-edit falls back rather than crashing, because losing
     * access to the whole app over a stray comma would be a poor trade.
     */
    fun loadRules(): CategoryRules {
        if (rulesFile.exists()) {
            runCatching { return CategoryRules.fromJson(rulesFile.readText()) }
        }
        return CategoryRules.defaults()
    }

    fun saveRules(rules: CategoryRules) {
        rulesFile.writeText(rules.toJson())
    }

    fun resetRules() {
        rulesFile.delete()
    }

    fun rulesAreCustomised(): Boolean = rulesFile.exists()

    companion object {
        private const val KEY_PURCHASE = "purchase_workbook"
        private const val KEY_SALES = "sales_workbook"
        private const val KEY_BACKUP = "backup_folder"
        private const val KEY_PURCHASE_TAB = "purchase_tab"
        private const val KEY_SALES_TAB = "sales_tab"
        private const val KEY_CREATE_TABS = "create_missing_tabs"
    }
}
