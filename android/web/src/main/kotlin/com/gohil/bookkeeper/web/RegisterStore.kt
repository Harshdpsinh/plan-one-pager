package com.gohil.bookkeeper.web

import com.gohil.bookkeeper.core.model.RegisterType
import com.gohil.bookkeeper.core.xlsx.XlsxAppender
import java.nio.file.Files
import java.nio.file.Path
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * The two register workbooks, held by the server instead of re-uploaded every run.
 *
 * The upload page is meant to take PDFs and nothing else, but the registers still have to
 * come from somewhere: the whole point of this project is appending to *the user's existing
 * workbook*, continuing its own SR NO sequence and preserving everything the CA's file
 * already contains. Generating a fresh workbook each month would lose all of that.
 *
 * So the workbooks are configured once, on a separate setup page, and live here. Every later
 * run is PDFs only.
 *
 * The originals are never modified in place. These are the server's own copies; a run
 * produces a new downloadable file, and only an explicit save writes back here.
 */
class RegisterStore(private val dir: Path = PasswordStore.defaultDir().resolve("registers")) {

    data class Register(val type: RegisterType, val fileName: String, val bytes: ByteArray) {
        val sheetNames: List<String> by lazy {
            runCatching { XlsxAppender.open(bytes).sheetNames() }.getOrDefault(emptyList())
        }
    }

    @Synchronized
    fun get(type: RegisterType): Register? {
        val meta = dir.resolve("${type.name.lowercase()}.name")
        val data = dir.resolve("${type.name.lowercase()}.xlsx")
        if (!Files.exists(data)) return null
        val name = runCatching { Files.readString(meta).trim() }.getOrNull()
            ?: "${type.name.lowercase()}.xlsx"
        return Register(type, name, Files.readAllBytes(data))
    }

    /**
     * Store a workbook after checking it actually opens. A file that cannot be parsed must be
     * rejected now, at setup, rather than at 11pm on filing day.
     */
    @Synchronized
    fun put(type: RegisterType, fileName: String, bytes: ByteArray): Register {
        val sheets = runCatching { XlsxAppender.open(bytes).sheetNames() }.getOrElse {
            throw IllegalArgumentException(
                "'$fileName' could not be read as an Excel workbook (${it.message}). " +
                    "It must be a .xlsx file — not .xls, and not a PDF renamed.",
            )
        }
        if (sheets.isEmpty()) throw IllegalArgumentException("'$fileName' has no worksheets in it.")

        Files.createDirectories(dir)
        Files.write(dir.resolve("${type.name.lowercase()}.xlsx"), bytes)
        Files.writeString(dir.resolve("${type.name.lowercase()}.name"), fileName)
        return Register(type, fileName, bytes)
    }

    @Synchronized
    fun save(type: RegisterType, bytes: ByteArray) {
        Files.createDirectories(dir)
        Files.write(dir.resolve("${type.name.lowercase()}.xlsx"), bytes)
    }

    @Synchronized
    fun forget(type: RegisterType) {
        Files.deleteIfExists(dir.resolve("${type.name.lowercase()}.xlsx"))
        Files.deleteIfExists(dir.resolve("${type.name.lowercase()}.name"))
    }

    fun isConfigured(): Boolean =
        RegisterType.entries.any { get(it) != null }

    fun location(): String = dir.toString()

    companion object {
        /**
         * Pick the tab for a month from the workbook's own tab names.
         *
         * This is what replaces the two "Month tab" boxes the upload page used to ask for.
         * The two workbooks genuinely name their tabs differently — one says "Jun", the other
         * "June", and one real file has both "june" and "Jun" — so this matches on the real
         * names rather than assuming a convention, and returns null rather than guessing when
         * nothing matches. A wrong tab writes a month's rows into the wrong place.
         */
        fun tabFor(month: YearMonth, sheetNames: List<String>): String? {
            val full = month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)   // June
            val short = month.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) // Jun
            val candidates = listOf(
                "$full ${month.year}", "$short ${month.year}", "$full-${month.year}",
                "$full'${month.year % 100}", full, short,
            )
            for (candidate in candidates) {
                sheetNames.firstOrNull { it.equals(candidate, ignoreCase = true) }?.let { return it }
            }
            // Last resort: a tab that starts with the month name, catching "June 26" and
            // similar. Deliberately not a "contains" match — "Jan" is inside "January" but
            // also inside nothing else useful, while a loose match could hit a "Summary" tab.
            return sheetNames.firstOrNull {
                it.trim().startsWith(short, ignoreCase = true)
            }
        }
    }
}
