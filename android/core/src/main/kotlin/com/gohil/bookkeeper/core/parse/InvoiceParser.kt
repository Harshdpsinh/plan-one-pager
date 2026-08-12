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

    /**
     * @param ownGstin this business's own registration, when it is known. Supplying it is
     *   worth far more than [counterparty]: the other party is then simply the registration
     *   that is not ours, which holds however the issuer lays the page out. Deciding by
     *   position works on a plain invoice and fails on Amazon's, where the two addresses are
     *   columns and the buyer's GSTIN is emitted first — that put this business's own
     *   registration in the vendor column of its own purchase register.
     */
    fun parse(
        text: String,
        sourceFile: String,
        counterparty: Counterparty = Counterparty.SUPPLIER,
        ownGstin: String? = null,
    ): Invoice {
        val clean = text.replace(' ', ' ')

        // The tax table's footer, for invoices that print the components as columns rather
        // than as labelled fields. Both commission-invoice formats do it that way, so the
        // labelled patterns find nothing and the whole breakdown came back empty.
        val totalsRow = Patterns.TOTALS_ROW.find(clean)?.groupValues

        val statedTaxable = Patterns.firstMoney(Patterns.TAXABLE, clean)
            ?: totalsRow?.getOrNull(1)?.let(Patterns::money)
        val cgst = Patterns.firstMoney(Patterns.CGST, clean)
            ?: totalsRow?.getOrNull(2)?.let(Patterns::money)
        val sgst = Patterns.firstMoney(Patterns.SGST, clean)
            ?: totalsRow?.getOrNull(3)?.let(Patterns::money)
        val igst = Patterns.firstMoney(Patterns.IGST, clean)
            ?: totalsRow?.getOrNull(4)?.let(Patterns::money)
        val total = grandTotal(clean)

        // Amazon's invoice prints the tax table and the payable, and never labels a taxable
        // value at all — so the register got a blank in the column a GST return is built
        // from. The invoice states the other three figures, and the arithmetic between them
        // is not a guess: 68,989.00 less 10,523.74 of tax is 58,465.26, to the paisa.
        val taxable = statedTaxable ?: derivedTaxable(total, sumOrNull(cgst, sgst, igst))

        val party = party(clean, counterparty, ownGstin)

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
    private fun party(text: String, counterparty: Counterparty, ownGstin: String?): Party {
        val hits = Patterns.GSTIN.findAll(text).toList()

        // Knowing our own registration settles it outright: the other party is whichever one
        // is not us, and that survives any page layout.
        if (ownGstin != null) {
            val other = hits.firstOrNull { !it.groupValues[1].equals(ownGstin, ignoreCase = true) }
            if (other != null) {
                return Party(
                    other.groupValues[1].uppercase(),
                    nameNear(text, other.range.first) ?: VendorName.extract(text),
                )
            }
        }

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
        // Issuers label the number half a dozen ways; everything before the label on that
        // line is the party it belongs to.
        val sameLine = GSTIN_LABEL.split(before.substringAfterLast('\n'), limit = 2).first()
            .trim().trim(':', '-', '|').trim()
        // Held to the same test as any other candidate. Without it "Ship To State Code : 24"
        // went into the register as a vendor, because it happened to sit on the line above.
        if (VendorName.isPlausibleName(sameLine)) return sameLine.take(MAX_NAME)

        return before.lines().asReversed().take(NAME_LOOKBACK)
            .firstNotNullOfOrNull { line ->
                NAME_FIELD.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= MIN_NAME }
            }
            ?.take(MAX_NAME)
    }

    private val NAME_FIELD = Regex("""^\s*name\s*[:.\-]?\s*(.+)$""", RegexOption.IGNORE_CASE)

    private val GSTIN_LABEL = Regex(
        """gstin|gst\s*(?:registration\s*)?(?:no|number|id)""",
        RegexOption.IGNORE_CASE,
    )

    /** The GST rates that exist. Anything else means a figure was misread. */
    private val GST_RATES = listOf(0.1, 0.25, 3.0, 5.0, 12.0, 18.0, 28.0)

    /**
     * The taxable value implied by the total and the tax, when the invoice never states one.
     *
     * Only when the two agree on a real GST rate. Without that check the derivation trusts
     * whatever landed in the tax field, and on one invoice here that was "18" — the rate,
     * misread as the amount. Subtracting it gave a taxable value ₹757 too high, which is
     * exactly the kind of plausible wrong number that reaches a return unchallenged.
     */
    private fun derivedTaxable(
        total: java.math.BigDecimal?,
        taxes: java.math.BigDecimal?,
    ): java.math.BigDecimal? {
        if (total == null || taxes == null || taxes.signum() <= 0 || total <= taxes) return null
        val base = total - taxes
        if (base.signum() <= 0) return null
        val impliedRate = taxes.toDouble() * 100.0 / base.toDouble()
        // Relative, not absolute. An absolute window wide enough for rounding at 18% also
        // reached from 0.36% to the 0.25% rate, which let the misread invoice through the
        // check written to catch it.
        val looksRight = GST_RATES.any { kotlin.math.abs(impliedRate - it) <= it * RATE_TOLERANCE }
        return if (looksRight) base else null
    }

    /** Rounding a printed total moves the implied rate by a fraction of a percent of itself. */
    private const val RATE_TOLERANCE = 0.02

    /**
     * The invoice total, taking the last figure on the totals line rather than the first.
     *
     * A totals row often carries the tax and the total together — Amazon prints
     * "TOTAL: ₹10,523.74 ₹68,989.00", tax then amount payable. Stopping at the first match
     * booked a ₹68,989 laptop into the purchase register at ₹10,523.
     */
    private fun grandTotal(text: String): java.math.BigDecimal? {
        val match = Patterns.GRAND_TOTAL.findAll(text).lastOrNull() ?: return null
        val restOfLine = text.substring(match.range.last + 1).substringBefore('\n')
        val trailing = Patterns.MONEY_TOKEN.findAll(restOfLine)
            .lastOrNull()
            ?.takeIf { it.range.first <= LOOKAHEAD_ON_LINE }
            ?.groupValues?.get(1)
            ?.let(Patterns::money)
        return trailing ?: Patterns.money(match.groupValues[1])
    }

    /** How far past the first figure a second one still belongs to the same totals row. */
    private const val LOOKAHEAD_ON_LINE = 24

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
        // Fields these invoices actually carry, found by watching each one become a vendor
        // name in turn: the IRN block, Amazon's address labels, the order fields.
        "irn", "irn/qr code", "sold by", "billing address", "shipping address", "ship to",
        "ship to state code", "order number", "order date", "invoice details", "pan no",
        "gst registration no", "state/ut code", "reference number", "hsn/sac",
        "mobile", "address", "terms", "declaration", "signature", "authorised signatory",
        "for", "page", "original", "duplicate", "triplicate", "recipient", "supplier",
    )

    private val NOISE = Regex("""^[\s\-_=*#|.,:;]+$""")

    /**
     * What the document calls itself. Every invoice opens with one of these, and taking it
     * as the vendor put "Tax Invoice/Bill of Supply/Cash Memo" in the NAME column of a
     * purchase register. Rejecting them lets the name fall through to the filename, which is
     * flagged for review — a name the user can correct beats one that reads like a real
     * answer and is not.
     */
    private val DOCUMENT_TITLE = Regex(
        """tax\s*invoice|bill\s*of\s*supply|cash\s*memo|original\s*for|duplicate\s*for""" +
            """|triplicate\s*for|see\s*rule|e-?invoice|credit\s*note|debit\s*note""",
        RegexOption.IGNORE_CASE,
    )

    fun extract(text: String): String? {
        for (raw in text.lineSequence().take(SCAN_LINES)) {
            val line = raw.trim().trim('|', '*', '#', '=', '-', '_').trim()
            if (!isPlausibleName(line)) continue
            return line.take(MAX_LENGTH)
        }
        return null
    }

    /** Shared with [InvoiceParser], so a name found by position faces the same test. */
    fun isPlausibleName(line: String): Boolean {
        if (line.length < MIN_LENGTH || line.length > 120) return false
        if (NOISE.matches(line)) return false
        if (DOCUMENT_TITLE.containsMatchIn(line)) return false
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
