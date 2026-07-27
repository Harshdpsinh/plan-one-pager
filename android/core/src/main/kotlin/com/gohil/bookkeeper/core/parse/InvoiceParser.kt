package com.gohil.bookkeeper.core.parse

import com.gohil.bookkeeper.core.model.Invoice

/** Extracts register fields from the text of an invoice, however that text was obtained. */
object InvoiceParser {

    fun parse(text: String, sourceFile: String): Invoice {
        val clean = text.replace(' ', ' ')

        val taxable = Patterns.firstMoney(Patterns.TAXABLE, clean)
        val cgst = Patterns.firstMoney(Patterns.CGST, clean)
        val sgst = Patterns.firstMoney(Patterns.SGST, clean)
        val igst = Patterns.firstMoney(Patterns.IGST, clean)
        val total = Patterns.lastMoney(Patterns.GRAND_TOTAL, clean)

        val extracted = VendorName.extract(clean)

        return Invoice(
            sourceFile = sourceFile,
            date = Patterns.findDate(clean),
            gstNo = Patterns.GSTIN.find(clean)?.groupValues?.get(1)?.uppercase(),
            invoiceNo = Patterns.INVOICE_NO.find(clean)?.groupValues?.get(1)?.trim()?.trimEnd('.', ',', '-'),
            partyName = extracted ?: VendorName.fromFilename(sourceFile),
            qty = Patterns.QTY.find(clean)?.groupValues?.get(1),
            ratePct = Patterns.RATE_PCT.find(clean)?.groupValues?.get(1)?.let(Patterns::money)
                ?: Patterns.ANY_PCT.find(clean)?.groupValues?.get(1)?.let(Patterns::money),
            hsn = Patterns.findHsn(clean),
            taxable = taxable,
            cgst = cgst,
            sgst = sgst,
            igst = igst,
            // Fall back to the arithmetic only when the document never states a total.
            totalGrand = total ?: sumOrNull(taxable, cgst, sgst, igst),
            partyNameFromFilename = extracted == null,
            rawText = text,
        )
    }

    private fun sumOrNull(vararg parts: java.math.BigDecimal?): java.math.BigDecimal? {
        if (parts.all { it == null }) return null
        return parts.filterNotNull().fold(java.math.BigDecimal.ZERO) { a, b -> a + b }
    }
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
