package com.gohil.bookkeeper.core.xlsx

import com.gohil.bookkeeper.core.model.RegisterType

/**
 * The register's column vocabulary.
 *
 * The first thirteen are the pre-existing columns and their order is fixed by the workbooks
 * the accountant already uses. The rest were added by this project and are always appended
 * to the right of whatever is already there — never inserted between existing columns.
 */
enum class Col(val header: String, val aliases: Set<String> = emptySet()) {
    SR_NO("SR NO", setOf("SRNO", "SR", "SR NO.", "S NO", "SNO", "SERIAL NO")),
    DATE("DATE", setOf("INVOICE DATE", "BILL DATE")),
    GST_NO("GST NO", setOf("GSTNO", "GSTIN", "GST NUMBER", "GST NO.")),
    INVOICE_NO("INVOICE NO", setOf("INVOICENO", "INV NO", "BILL NO", "INVOICE NO.", "INVOICE NUMBER")),
    NAME("NAME", setOf("PARTY NAME", "VENDOR NAME", "PARTY", "CUSTOMER NAME")),
    QTY("QTY", setOf("QUANTITY")),
    RATE_PCT("RATE %", setOf("RATE%", "RATE", "GST RATE", "TAX RATE")),
    HSN("HSN", setOf("HSN CODE", "HSN/SAC", "SAC")),
    TAXABLE("TAXABLE", setOf("TAXABLE VALUE", "TAXABLE AMOUNT")),
    CGST("CGST"),
    SGST("SGST"),
    IGST("IGST"),
    TOTAL_GRAND("TOTAL GRAND", setOf("GRAND TOTAL", "TOTAL", "TOTAL AMOUNT")),

    // Added by this project.
    PAID_DATE("PAID DATE", setOf("PAIDDATE", "PAYMENT DATE")),
    PAYMENT_METHOD("PAYMENT METHOD", setOf("PAYMENTMETHOD", "PAYMENT MODE", "MODE")),
    CATEGORY("CATEGORY", setOf("EXPENSE CATEGORY")),
    GST_TYPE("GST TYPE", setOf("GSTTYPE", "TAX TYPE")),
    ;

    fun matches(text: String): Boolean {
        val n = normalise(text)
        return n == normalise(header) || aliases.any { normalise(it) == n }
    }

    companion object {
        /** Columns this project appends, per workbook. */
        fun addedColumnsFor(type: RegisterType): List<Col> = when (type) {
            RegisterType.PURCHASE -> listOf(PAID_DATE, PAYMENT_METHOD, CATEGORY)
            RegisterType.SALES -> listOf(PAID_DATE, PAYMENT_METHOD, GST_TYPE)
        }

        /** The tax columns that TOTAL GRAND sums over. */
        val SUM_RANGE: List<Col> = listOf(TAXABLE, CGST, SGST, IGST)

        fun fromHeader(text: String): Col? = entries.firstOrNull { it.matches(text) }

        /**
         * Header text as typed by a human: case, spacing and trailing punctuation all vary
         * between the two workbooks and between months.
         */
        fun normalise(s: String): String =
            s.uppercase()
                .replace(Regex("""[. ]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
    }
}
