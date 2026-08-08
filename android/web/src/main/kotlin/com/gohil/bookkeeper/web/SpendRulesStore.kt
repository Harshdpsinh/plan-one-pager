package com.gohil.bookkeeper.web

import com.gohil.bookkeeper.core.spend.InvestmentRules
import com.gohil.bookkeeper.core.spend.SpendCategorizer
import com.gohil.bookkeeper.core.spend.SpendRulesConfig
import java.nio.file.Files
import java.nio.file.Path

/**
 * The keyword lists on disk, as plain JSON the user can edit in Notepad.
 *
 * Written out from the shipped defaults on first run so there is something to edit rather
 * than a blank file to invent from. Re-read on every analysis, so correcting a category is
 * "edit, save, press Analyse again" — no restart, no rebuild.
 *
 * A file that will not parse falls back to the defaults instead of failing the run. Someone
 * fixing a category at 11pm should get their statements processed with a stale rule set and a
 * warning, not an app that refuses to start.
 */
class SpendRulesStore(private val file: Path = PasswordStore.defaultDir().resolve("spend-categories.json")) {

    /** Non-null when the last load fell back — shown in the UI so a bad edit is not silent. */
    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun config(): SpendRulesConfig {
        if (!Files.exists(file)) {
            runCatching {
                Files.createDirectories(file.parent)
                Files.writeString(file, SpendRulesConfig.defaults().toJson())
            }
            lastError = null
            return SpendRulesConfig.defaults()
        }
        return runCatching {
            val parsed = SpendRulesConfig.fromJson(Files.readString(file))
            if (parsed.isEmpty()) {
                lastError = "${file.fileName} has no categories or investments in it — using the built-in lists."
                SpendRulesConfig.defaults()
            } else {
                lastError = null
                parsed
            }
        }.getOrElse { e ->
            lastError = "${file.fileName} could not be read (${e.message}) — using the built-in lists."
            SpendRulesConfig.defaults()
        }
    }

    fun categorizer(): SpendCategorizer = config().toCategorizer()

    fun investmentRules(): InvestmentRules = config().toInvestmentRules()

    /** Overwrite with the shipped lists — the way back from an edit that went wrong. */
    @Synchronized
    fun reset(): SpendRulesConfig {
        Files.createDirectories(file.parent)
        Files.writeString(file, SpendRulesConfig.defaults().toJson())
        lastError = null
        return SpendRulesConfig.defaults()
    }

    fun location(): String = file.toString()
}
