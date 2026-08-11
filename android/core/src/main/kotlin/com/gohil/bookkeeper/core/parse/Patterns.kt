package com.gohil.bookkeeper.core.parse

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Shared regexes and value coercion used by both the invoice and statement parsers. */
object Patterns {

    /**
     * GSTIN: 2-digit state code, 5 letters, 4 digits, 1 letter, 1 alphanumeric, 'Z',
     * 1 alphanumeric checksum.
     */
    val GSTIN = Regex("""\b(\d{2}[A-Z]{5}\d{4}[A-Z][A-Z\d]Z[A-Z\d])\b""", RegexOption.IGNORE_CASE)

    /**
     * A rupee figure, with or without Indian digit grouping: 1,23,456.78 or 1000.00.
     *
     * The comma-grouped alternative requires at least one comma. With `*` instead of `+` it
     * also matches an ungrouped number, but only its first three digits — so 1000.00 parsed
     * as 100 and every four-figure amount was silently divided by ten on its way into the
     * register. The trailing guard stops a match that stops short of the real end of a number.
     */
    private const val MONEY = """(\d{1,3}(?:,\d{2,3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)(?![\d,])"""

    val MONEY_TOKEN = Regex("""(?<![\d.])(?:₹|Rs\.?|INR)?\s*$MONEY""", RegexOption.IGNORE_CASE)

    /**
     * Builds a "label followed by amount" matcher for a tax component.
     *
     * This is the shape of the bug that made CGST: 450.00 read as 0.00. The optional middle
     * section — which absorbs a rate like "@ 9%" — must not be allowed to match bare digits,
     * or it swallows the amount's leading figures and leaves only the trailing decimals.
     * Hence `[\d.]+\s*%` with the percent sign required, rather than a loose `.*?`.
     */
    /**
     * Horizontal whitespace only, throughout. A labelled amount sits on its label's line;
     * letting the gap cross a newline made "… CGST SGST IGST" — a row of column headings —
     * match the "1" that begins the line item underneath, so IGST came back as ₹1 and the
     * rate derived from it as 0%.
     */
    fun taxPattern(label: String): Regex = Regex(
        """$label[ \t]*(?:\(?[ \t]*@?[ \t]*[\d.]+[ \t]*%[ \t]*\)?)?[ \t]*[:\-]?[ \t]*(?:₹|Rs\.?|INR)?[ \t]*$MONEY""",
        RegexOption.IGNORE_CASE,
    )

    val CGST = taxPattern("CGST")
    val SGST = taxPattern("SGST")
    val IGST = taxPattern("IGST")

    /** Same line as its label, for the same reason as [taxPattern]. */
    val TAXABLE = Regex(
        """taxable[ \t]*(?:value|amount|amt)?[ \t]*[:\-]?[ \t]*(?:₹|Rs\.?|INR)?[ \t]*$MONEY""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The `(?:\([^)]{0,20}\))?` allows a qualifier between the label and the figure —
     * KFintech writes "Total Invoice Value (In figure) 2054.05". Without it the specific
     * alternative failed, the bare `total` alternative matched the tax table's footer
     * instead, and the register got the taxable value where the invoice total belongs.
     */
    val GRAND_TOTAL = Regex(
        """(?:grand\s*total|total\s*(?:invoice\s*)?(?:amount|value)|amount\s*payable|net\s*payable|total)""" +
            """\s*(?:\([^)]{0,20}\))?\s*[:\-]?\s*(?:₹|Rs\.?|INR)?\s*$MONEY""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The optional middle word is for "Inv serial No." — KFintech's commission invoices label
     * it that way, and requiring the two halves to be adjacent left every one of them without
     * an invoice number.
     */
    val INVOICE_NO = Regex(
        """(?:invoice|bill|inv|receipt)\s*(?:serial|sr|ref)?\s*(?:no|num|number|#)""" +
            // A run rather than one character: KFintech writes "Inv serial No. : AXTI/…",
            // and a single optional separator stopped at the full stop and never reached the
            // colon. Horizontal whitespace only — one of these invoices leaves the field
            // empty, and a run that crossed the line break took the next line's label as the
            // invoice number.
            """[ \t:.\-]*([A-Za-z0-9][A-Za-z0-9/\-_]{2,29})""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A date the document has labelled as such, which beats scanning for the first thing that
     * looks like one.
     *
     * "Distribution Commission ... for the month of JUNE - 2026" parses as 20 June 2026 —
     * month name, then "20" and "26" out of the year. Every commission invoice carries that
     * sentence, so every one of them was dated wrongly before the label was preferred.
     */
    val LABELLED_DATE = Regex(
        """(?:invoice\s*date|bill\s*date|dated|date)\s*[:.\-]?\s*(.{0,24})""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The totals line of a tax table: `Total 1740.72 0.00 0.00 313.33`.
     *
     * Commission invoices print TAXABLE / CGST / SGST / IGST as column headings with the
     * figures underneath, so the labelled patterns above — which expect "CGST: 450.00" —
     * find nothing at all and the whole tax breakdown comes back empty. Four money columns
     * on a line beginning with Total is that table's footer in both formats seen.
     */
    val TOTALS_ROW = Regex(
        """(?m)^\s*total\s+$MONEY\s+$MONEY\s+$MONEY\s+$MONEY\s*$""",
        RegexOption.IGNORE_CASE,
    )

    val HSN = Regex("""HSN(?:\s*/\s*SAC)?\s*(?:code)?\s*[:\-]?\s*(\d{4,8})""", RegexOption.IGNORE_CASE)

    /** A bare 6- or 8-digit HSN/SAC code, for invoices that print it as a table column. */
    private val HSN_BARE = Regex("""(?<![\d.])(\d{8}|\d{6})(?![\d.])""")

    /**
     * An amount with the paise written out.
     *
     * Used to tell a line item from an address: a bare house number or PIN code satisfies
     * the general money pattern, but a priced line always states its paise.
     */
    private val AMOUNT_WITH_PAISE = Regex("""\d[\d,]*\.\d{2}(?![\d])""")

    /**
     * Finds the HSN code, falling back to a bare code in a line item when the invoice uses a
     * column header rather than an inline label.
     *
     * The fallback only considers lines that also carry an amount, which keeps it away from
     * the six-digit PIN code in the vendor's address — the obvious wrong answer here.
     */
    fun findHsn(text: String): String? {
        HSN.find(text)?.let { return it.groupValues[1] }
        for (line in text.lines()) {
            if (!AMOUNT_WITH_PAISE.containsMatchIn(line)) continue
            if (GSTIN.containsMatchIn(line)) continue
            // Skip the totals block, where a stray reference number could be mistaken for a code.
            if (Regex("""\b(total|taxable|cgst|sgst|igst)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)) continue
            HSN_BARE.find(line)?.let { return it.groupValues[1] }
        }
        return null
    }

    val RATE_PCT = Regex("""(?:rate|gst|tax)\s*[:@\-]?\s*(\d{1,2}(?:\.\d{1,2})?)\s*%""", RegexOption.IGNORE_CASE)

    /** Any percentage, used only as a fallback when no labelled rate is present. */
    val ANY_PCT = Regex("""\b(\d{1,2}(?:\.\d{1,2})?)\s*%""")

    val QTY = Regex("""(?:qty|quantity)\s*[:\-]?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

    // ── dates ────────────────────────────────────────────────────────────────────

    private val NUMERIC_DATE = Regex("""\b(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2,4})\b""")
    private val ISO_DATE = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val TEXT_DATE = Regex(
        """\b(\d{1,2})[\s\-]*(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[\s\-,]*(\d{2,4})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val TEXT_DATE_FIRST = Regex(
        """\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[\s\-]*(\d{1,2})[\s\-,]*(\d{2,4})\b""",
        RegexOption.IGNORE_CASE,
    )

    private val MONTHS = listOf(
        "jan", "feb", "mar", "apr", "may", "jun",
        "jul", "aug", "sep", "oct", "nov", "dec",
    )

    /**
     * Parses the first date in [text].
     *
     * Indian invoices are overwhelmingly day-first, so an ambiguous 05/06/2025 is read as
     * 5 June. Where the first component exceeds 12 the order is unambiguous and is honoured.
     */
    fun findDate(text: String): LocalDate? {
        ISO_DATE.find(text)?.let { m ->
            return runCatching {
                LocalDate.of(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt(),
                )
            }.getOrNull()
        }
        TEXT_DATE.find(text)?.let { m ->
            val month = MONTHS.indexOf(m.groupValues[2].lowercase().take(3)) + 1
            return buildDate(m.groupValues[1].toIntOrNull(), month, m.groupValues[3].toIntOrNull())
        }
        TEXT_DATE_FIRST.find(text)?.let { m ->
            val month = MONTHS.indexOf(m.groupValues[1].lowercase().take(3)) + 1
            return buildDate(m.groupValues[2].toIntOrNull(), month, m.groupValues[3].toIntOrNull())
        }
        NUMERIC_DATE.find(text)?.let { m ->
            var day = m.groupValues[1].toIntOrNull() ?: return null
            var month = m.groupValues[2].toIntOrNull() ?: return null
            if (day <= 12 && month > 12) {
                // Unambiguously month-first (e.g. 06/25/2025).
                val swap = day; day = month; month = swap
            }
            return buildDate(day, month, m.groupValues[3].toIntOrNull())
        }
        return null
    }

    private fun buildDate(day: Int?, month: Int, year: Int?): LocalDate? {
        if (day == null || year == null || month !in 1..12) return null
        val fullYear = when {
            year >= 1000 -> year
            // A two-digit year on a bookkeeping document is this century.
            year >= 70 -> 1900 + year
            else -> 2000 + year
        }
        return runCatching { LocalDate.of(fullYear, month, day) }.getOrNull()
    }

    fun formatDate(date: LocalDate): String = DISPLAY.format(date)

    private val DISPLAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    // ── money ────────────────────────────────────────────────────────────────────

    /** Converts a captured money token to a value, tolerating Indian digit grouping. */
    fun money(raw: String?): BigDecimal? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace(",", "").replace("₹", "").trim()
            .removePrefix("Rs.").removePrefix("Rs").removePrefix("INR").trim()
        if (cleaned.isEmpty()) return null
        return runCatching { BigDecimal(cleaned) }.getOrNull()
    }

    /** First capture group of the first match, as money. */
    fun firstMoney(regex: Regex, text: String): BigDecimal? =
        regex.find(text)?.groupValues?.getOrNull(1)?.let(::money)

    /**
     * Last match wins for totals: invoices commonly restate the total in a summary block at
     * the foot of the document, and that final figure is the authoritative one.
     */
    fun lastMoney(regex: Regex, text: String): BigDecimal? =
        regex.findAll(text).lastOrNull()?.groupValues?.getOrNull(1)?.let(::money)
}
