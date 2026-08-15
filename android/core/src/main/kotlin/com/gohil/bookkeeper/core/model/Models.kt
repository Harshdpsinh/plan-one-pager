package com.gohil.bookkeeper.core.model

import java.math.BigDecimal
import java.time.LocalDate

/** Which workbook a row belongs in. */
enum class RegisterType { PURCHASE, SALES }

/** Purchase register only. */
enum class Category(val label: String) {
    PERSONAL("Personal Expense"),
    BUSINESS("Business Expense"),
}

/**
 * Sales register only.
 *
 * RCM means the insurer pays GST directly under the Reverse Charge Mechanism rather than
 * embedding it in the commission. Getting this wrong misstates a GST filing, so an insurer
 * is only ever marked RCM when it appears in the confirmed list in categories.json.
 */
enum class GstType { RCM, DIRECT }

enum class PaymentMethod(val label: String) {
    CREDIT_CARD("Credit Card"),
    UPI("UPI"),
    BANK_TRANSFER("Bank Transfer"),
    CASH("Cash"),
    CHEQUE("Cheque"),
    UNKNOWN(""),
}

/** Where a bank line came from — decides the default payment method. */
enum class StatementSource { BANK, CREDIT_CARD }

/**
 * A parsed invoice, before it becomes a register row.
 *
 * Every field is nullable on purpose: a missing field must stay missing so it can be shown
 * for review, never silently defaulted to zero. A zero written into a GST column is a wrong
 * number in a filing, which is worse than a blank one.
 */
data class Invoice(
    val sourceFile: String,
    val date: LocalDate? = null,
    val gstNo: String? = null,
    val invoiceNo: String? = null,
    /** Extracted from the invoice letterhead, NOT from the filename. */
    val partyName: String? = null,
    val qty: String? = null,
    val ratePct: BigDecimal? = null,
    val hsn: String? = null,
    val taxable: BigDecimal? = null,
    val cgst: BigDecimal? = null,
    val sgst: BigDecimal? = null,
    val igst: BigDecimal? = null,
    val totalGrand: BigDecimal? = null,
    /** True when the party name fell back to the filename — lowers confidence. */
    val partyNameFromFilename: Boolean = false,
    val rawText: String = "",
) {
    /** The figure used for matching against bank transactions. */
    val matchAmount: BigDecimal?
        get() = totalGrand ?: taxable

    /**
     * 0.0–1.0. Anything below [com.gohil.bookkeeper.core.logic.Pipeline.CONFIDENCE_THRESHOLD]
     * is routed to review instead of being written.
     */
    fun confidence(): Double {
        var score = 0.0
        if (date != null) score += 0.25
        if (invoiceNo != null) score += 0.20
        if (totalGrand != null) score += 0.30
        if (partyName != null && !partyNameFromFilename) score += 0.15
        if (gstNo != null) score += 0.10
        return score
    }

    fun missingFields(): List<String> = buildList {
        if (date == null) add("DATE")
        if (invoiceNo == null) add("INVOICE NO")
        if (totalGrand == null) add("TOTAL GRAND")
        if (taxable == null) add("TAXABLE VALUE")
        if (partyName == null) add("NAME")
        if (partyNameFromFilename) add("NAME (guessed from filename)")
    }

    /**
     * Whether this invoice may be written to a register without a human first checking it.
     *
     * A taxable value is separately mandatory, on top of the [confidence] score. It is the
     * column a GST return is built from, and an invoice can score full confidence on the
     * fields around it — date, number, party, and a printed total — while the tax table itself
     * was never read. Airtel's bundled statement does exactly that: it offers a net
     * "amount payable" of ₹41.55 (this month's charges less last month's payment) with no
     * taxable value beside it, and that figure is not what any tax is computed on. Booking it
     * would put a wrong number and three blank tax columns into the register; the row goes to
     * review instead, where the real figures can be typed in.
     */
    fun readyToWrite(threshold: Double): Boolean =
        confidence() >= threshold && taxable != null
}

/** One line off a bank or credit-card statement. */
data class BankTxn(
    val date: LocalDate,
    val description: String,
    /** Money out. Exactly one of debit/credit is non-null. */
    val debit: BigDecimal? = null,
    /** Money in. */
    val credit: BigDecimal? = null,
    val source: StatementSource,
    val rawLine: String,
) {
    val isDebit: Boolean get() = debit != null
    val amount: BigDecimal get() = debit ?: credit ?: BigDecimal.ZERO
}

/**
 * A row destined for a worksheet.
 *
 * SR NO is deliberately absent — it is assigned by the writer by continuing the sequence
 * already in the sheet, so it can never collide with existing data.
 */
data class RegisterRow(
    val date: LocalDate?,
    val gstNo: String? = null,
    val invoiceNo: String? = null,
    val name: String? = null,
    val qty: String? = null,
    val ratePct: BigDecimal? = null,
    val hsn: String? = null,
    val taxable: BigDecimal? = null,
    val cgst: BigDecimal? = null,
    val sgst: BigDecimal? = null,
    val igst: BigDecimal? = null,
    val totalGrand: BigDecimal? = null,
    val paidDate: LocalDate? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.UNKNOWN,
    /** Purchase register only. */
    val category: Category? = null,
    /** Sales register only. */
    val gstType: GstType? = null,
    val provenance: String = "",
) {
    /**
     * Bug #2 from the desktop app: TOTAL GRAND used to be written as =SUM(TAXABLE:IGST) on
     * every row. A plain bank transaction has no tax breakdown, so that summed blank cells
     * and produced a zero. The SUM formula is only correct when TAXABLE actually has a value.
     */
    val useSumFormula: Boolean get() = taxable != null
}

/** Why an item could not be written automatically. */
enum class ReviewReason {
    LOW_CONFIDENCE,
    PARSE_FAILED,
    EXTRACTION_FAILED,
    TIMED_OUT,
    PASSWORD_REQUIRED,
    AMBIGUOUS_CATEGORY,
    UNMATCHED_CREDIT,
    DUPLICATE_SUSPECTED,
}

/**
 * Something the app refused to guess at. Nothing here reaches a workbook until a human
 * approves it — the register is handed to a CA, so a wrong number is worse than a gap.
 */
data class ReviewItem(
    val sourceFile: String,
    val reason: ReviewReason,
    val detail: String,
    val suggested: RegisterRow? = null,
    val target: RegisterType = RegisterType.PURCHASE,
    /** Shown verbatim in the review screen so the user can read what the OCR actually saw. */
    val rawText: String = "",
)

/** Result of a full month run, before anything is written. */
data class ProcessResult(
    val purchaseRows: List<RegisterRow> = emptyList(),
    val salesRows: List<RegisterRow> = emptyList(),
    val review: List<ReviewItem> = emptyList(),
    val stats: Map<String, Int> = emptyMap(),
)
