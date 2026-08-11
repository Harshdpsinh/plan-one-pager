package com.gohil.bookkeeper.core.parse

import com.gohil.bookkeeper.core.model.Invoice

/** Extracts register fields from the text of an invoice, however that text was obtained. */
object InvoiceParser {

    /**
     * Which of the two parties on the invoice the register is about.
     *
     * A purchase register records who you bought from — the supplier — and on an ordinary
     * vendor bill that is whose letterhead it is, so the first GSTIN on the page is right.
     *
     * A sales register records who you sold to. The commission invoices here are self-billed:
     * the distributor raises them, so *he* is the supplier and the fund house is the
     * recipient. Taking the first GSTIN put the user's own registration in the counterparty
     * column of all twenty-one rows of a GST return, next to his own name as the customer.
     */
    enum class Counterparty { SUPPLIER, RECIPIENT }

    fun parse(
        text: String,
        sourceFile: String,
        counterparty: Counterparty = Counterparty.SUPPLIER,
    ): Invoice {
        val clean = text.replace(' ', ' ')

        // The tax table's footer, for invoices that print the components as columns rather
        // than as labelled fields. Both commission-invoice formats do it that way, so the
        // labelled patterns find nothing and the whole breakdown came back empty.
        val totalsRow = Patterns.TOTALS_ROW.find(clean)?.groupValues

        val taxable = Patterns.firstMoney(Patterns.TAXABLE, clean)
            ?: totalsRow?.getOrNull(1)?.let(Patterns::money)
        val cgst = Patterns.firstMoney(Patterns.CGST, clean)
            ?: totalsRow?.getOrNull(2)?.let(Patterns::money)
        val sgst = Patterns.firstMoney(Patterns.SGST, clean)
            ?: totalsRow?.getOrNull(3)?.let(Patterns::money)
        val igst = Patterns.firstMoney(Patterns.IGST, clean)
            ?: totalsRow?.getOrNull(4)?.let(Patterns::money)
        val total = Patterns.lastMoney(Patterns.GRAND_TOTAL, clean)

        val party = party(clean, counterparty)

        return Invoice(
            sourceFile = sourceFile,
            date = labelledDate(clean) ?: Patterns.findDate(clean),
            gstNo = party.gstin,
            invoiceNo = Patterns.INVOICE_NO.find(clean)?.groupValues?.get(1)?.trim()?.trimEnd('.', ',', '-'),
            partyName = party.name ?: VendorName.fromFilename(sourceFile),
            qty = Patterns.QTY.find(clean)?.groupValues?.get(1),
            ratePct = Patterns.RATE_PCT.find(clean)?.groupValues?.get(1)?.let(Patterns::money)
                ?: derivedRate(taxable, cgst, sgst, igst)
                ?: Patterns.ANY_PCT.find(clean)?.groupValues?.get(1)?.let(Patterns::money),
            hsn = Patterns.findHsn(clean),
            taxable = taxable,
            cgst = cgst,
            sgst = sgst,
            igst = igst,
            // Fall back to the arithmetic only when the document never states a total.
            totalGrand = total ?: sumOrNull(taxable, cgst, sgst, igst),
            partyNameFromFilename = party.name == null,
            rawText = text,
        )
    }

    private data class Party(val gstin: String?, val name: String?)

    /**
     * The GSTIN and name of the side of the invoice the register is about.
     *
     * Chosen by position rather than by vocabulary: an Indian tax invoice names the supplier
     * first and the recipient second, and both commission formats follow that — one under
     * headings ("Details of Recipient (Billed to)"), the other by simply printing the fund's
     * name and GSTIN further down. Matching the headings instead would need a rule per issuer
     * and would still miss the one that has no headings.
     */
    private fun party(text: String, counterparty: Counterparty): Party {
        val hits = Patterns.GSTIN.findAll(text).toList()
        // A single-GSTIN document has named exactly one party, and by elimination that is the
        // counterparty whichever side we were asked for.
        val chosen = when (counterparty) {
            Counterparty.SUPPLIER -> hits.firstOrNull()
            Counterparty.RECIPIENT -> hits.lastOrNull()
        } ?: return Party(null, VendorName.extract(text))

        val gstin = chosen.groupValues[1].uppercase()
        if (counterparty == Counterparty.SUPPLIER) return Party(gstin, VendorName.extract(text))
        return Party(gstin, nameNear(text, chosen.range.first) ?: VendorName.extract(text))
    }

    /**
     * The counterparty's name, given where its GSTIN sits.
     *
     * CAMS prints them on one line — "Aditya Birla Sun Life Mutual Fund GSTIN : 27AAA…" — so
     * the name is whatever precedes the label. KFintech instead puts "Name Axis Mutual Fund"
     * a few lines above, so failing that, the nearest preceding Name field wins.
     */
    private fun nameNear(text: String, gstinAt: Int): String? {
        val before = text.take(gstinAt)
        val sameLine = before.substringAfterLast('\n')
            .substringBefore("GSTIN", "")
            .trim().trim(':', '-', '|').trim()
        if (sameLine.length >= MIN_NAME && sameLine.any { it.isLetter() }) return sameLine.take(MAX_NAME)

        return before.lines().asReversed().take(NAME_LOOKBACK)
            .firstNotNullOfOrNull { line ->
                NAME_FIELD.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= MIN_NAME }
            }
            ?.take(MAX_NAME)
    }

    private val NAME_FIELD = Regex("""^\s*name\s*[:.\-]?\s*(.+)$""", RegexOption.IGNORE_CASE)

    /** A date the document labelled, read out of the short run of text after the label. */
    private fun labelledDate(text: String): java.time.LocalDate? =
        Patterns.LABELLED_DATE.findAll(text)
            .firstNotNullOfOrNull { Patterns.findDate(it.groupValues[1]) }

    /**
     * The GST rate implied by the figures, for a document that prints it only as a column.
     *
     * More reliable than reading a percent sign off the page: a commission invoice shows
     * "0.00%" for the two taxes that do not apply before it shows the one that does, so the
     * first percentage on the page is reliably the wrong one.
     */
    private fun derivedRate(
        taxable: java.math.BigDecimal?,
        vararg taxes: java.math.BigDecimal?,
    ): java.math.BigDecimal? {
        if (taxable == null || taxable.signum() <= 0) return null
        val tax = taxes.filterNotNull().fold(java.math.BigDecimal.ZERO) { a, b -> a + b }
        if (tax.signum() <= 0) return null
        return tax.multiply(java.math.BigDecimal(100))
            .divide(taxable, 0, java.math.RoundingMode.HALF_UP)
    }

    private fun sumOrNull(vararg parts: java.math.BigDecimal?): java.math.BigDecimal? {
        if (parts.all { it == null }) return null
        return parts.filterNotNull().fold(java.math.BigDecimal.ZERO) { a, b -> a + b }
    }

    private const val MIN_NAME = 3
    private const val MAX_NAME = 80
    private const val NAME_LOOKBACK = 12
}

/**
 * Recovers the counterparty name from the invoice text.
 *
 * Bug #3 in the desktop app took this from the filename, which silently broke both RCM
 * detection and Personal/Business categorisation whenever a file arrived as IMG_2034.pdf —
 * and it broke them quietly, producing a plausible-looking wrong answer rather than an
 * error. The name is almost always the letterhead: the first line that is not a GSTIN, a
 * date, or a field label.
 */
object VendorName {

    private val FIELD_LABELS = setOf(
        "invoice", "tax invoice", "bill", "bill of supply", "receipt", "total", "grand total",
        "taxable", "cgst", "sgst", "igst", "gst", "gstin", "hsn", "sac", "date", "qty",
        "quantity", "rate", "amount", "subtotal", "sub total", "description", "particulars",
        "sr no", "s no", "serial", "to", "from", "bill to", "ship to", "buyer", "seller",
        "consignee", "place of supply", "state", "state code", "pan", "cin", "email", "phone",
        "mobile", "address", "terms", "declaration", "signature", "authorised signatory",
        "for", "page", "original", "duplicate", "triplicate", "recipient", "supplier",
    )

    private val NOISE = Regex("""^[\s\-_=*#|.,:;]+$""")

    fun extract(text: String): String? {
        for (raw in text.lineSequence().take(SCAN_LINES)) {
            val line = raw.trim().trim('|', '*', '#', '=', '-', '_').trim()
            if (!isPlausibleName(line)) continue
            return line.take(MAX_LENGTH)
        }
        return null
    }

    private fun isPlausibleName(line: String): Boolean {
        if (line.length < MIN_LENGTH || line.length > 120) return false
        if (NOISE.matches(line)) return false
        if (Patterns.GSTIN.containsMatchIn(line)) return false
        if (Patterns.findDate(line) != null) return false

        val lower = line.lowercase().trimEnd(':', '-').trim()
        if (lower in FIELD_LABELS) return false

        // A label with its value on the same line, e.g. "Invoice No: INV-1001" or
        // "Bill To : Someone". The label may carry a qualifier ("No", "Date"), so the test
        // is on what precedes the colon rather than on an exact match.
        val beforeColon = line.substringBefore(':', "").trim().lowercase()
        if (beforeColon.isNotEmpty() && beforeColon.length <= MAX_LABEL_LENGTH &&
            FIELD_LABELS.any { beforeColon == it || beforeColon.startsWith("$it ") }
        ) {
            return false
        }

        // Mostly digits means it is an amount, a phone number or a reference, not a name.
        val letters = line.count { it.isLetter() }
        val digits = line.count { it.isDigit() }
        if (letters < 3) return false
        if (digits > letters) return false

        return true
    }

    /**
     * Last resort when the letterhead is unreadable. The caller records that this happened
     * so the row is flagged for review rather than trusted.
     */
    fun fromFilename(fileName: String): String? {
        val base = fileName.substringAfterLast('/').substringBeforeLast('.')
        val cleaned = base.replace(Regex("""[_\-]+"""), " ")
            .replace(Regex("""\d{4,}"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        // Generic camera and scanner names carry no information at all.
        if (cleaned.length < MIN_LENGTH) return null
        if (Regex("""^(img|image|doc|document|scan|photo|pdf|file|new)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(cleaned) && cleaned.count { it.isLetter() } < 6
        ) {
            return null
        }
        return cleaned.take(MAX_LENGTH)
    }

    private const val SCAN_LINES = 15
    private const val MIN_LENGTH = 3
    private const val MAX_LENGTH = 80

    /** Longer than this before a colon and it is prose, not a field label. */
    private const val MAX_LABEL_LENGTH = 25
}
