package com.gohil.bookkeeper.core.logic

import com.gohil.bookkeeper.core.model.BankTxn
import com.gohil.bookkeeper.core.model.Invoice
import com.gohil.bookkeeper.core.model.ProcessResult
import com.gohil.bookkeeper.core.model.RegisterRow
import com.gohil.bookkeeper.core.model.RegisterType
import com.gohil.bookkeeper.core.model.ReviewItem
import com.gohil.bookkeeper.core.model.ReviewReason
import com.gohil.bookkeeper.core.rules.CategoryRules

/**
 * Assembles parsed documents into register rows.
 *
 * This is where the project's routing rules live, and the important property is what it
 * refuses to do. Nothing that cannot be justified from a document reaches a workbook; it
 * goes to review instead. These registers are handed to a chartered accountant, so a gap a
 * human fills in is strictly better than a plausible-looking wrong number.
 */
class Pipeline(rules: CategoryRules) {

    private val categorizer = Categorizer(rules)

    data class Input(
        val purchaseInvoices: List<Invoice> = emptyList(),
        val salesInvoices: List<Invoice> = emptyList(),
        val transactions: List<BankTxn> = emptyList(),
        /** Statement lines the parser could not resolve; surfaced rather than dropped. */
        val unparsedLines: List<String> = emptyList(),
        val failures: List<ReviewItem> = emptyList(),
        /**
         * Whether a debit with no invoice behind it becomes a purchase-register row.
         *
         * True is right when statements are uploaded *as* the record of expenses. It is
         * wrong when they are uploaded so invoices can be matched to their payment: a month
         * of two bank accounts and six cards then turns 7 real bills into 69 rows, and the
         * register a chartered accountant reads stops being a list of invoices.
         *
         * Nothing is lost when this is false — those payments are the spend analysis, which
         * is where a card transaction with no tax invoice actually belongs.
         */
        val statementLinesBecomeRows: Boolean = true,
    )

    fun process(input: Input): ProcessResult {
        val purchaseRows = ArrayList<RegisterRow>()
        val salesRows = ArrayList<RegisterRow>()
        val review = ArrayList<ReviewItem>(input.failures)

        // Sales invoices are matched first so a commission receipt is not also treated as
        // an unexplained bank credit further down.
        val salesMatch = Matcher.match(input.salesInvoices, input.transactions)
        val remainingTxns = salesMatch.unmatchedTxns
        val purchaseMatch = Matcher.match(input.purchaseInvoices, remainingTxns)

        val paymentByInvoice = (salesMatch.matches + purchaseMatch.matches)
            .associate { it.invoice to it.txn }

        for (invoice in input.salesInvoices) {
            val txn = paymentByInvoice[invoice]
            val row = RegisterRow(
                date = invoice.date,
                gstNo = invoice.gstNo,
                invoiceNo = invoice.invoiceNo,
                name = invoice.partyName,
                qty = invoice.qty,
                ratePct = invoice.ratePct,
                hsn = invoice.hsn,
                taxable = invoice.taxable,
                cgst = invoice.cgst,
                sgst = invoice.sgst,
                igst = invoice.igst,
                totalGrand = invoice.totalGrand,
                paidDate = txn?.date,
                paymentMethod = categorizer.paymentMethod(txn),
                gstType = categorizer.gstType(invoice.partyName),
                provenance = invoice.sourceFile,
            )
            if (invoice.confidence() < CONFIDENCE_THRESHOLD) {
                review += ReviewItem(
                    sourceFile = invoice.sourceFile,
                    reason = ReviewReason.LOW_CONFIDENCE,
                    detail = "Could not read: ${invoice.missingFields().joinToString(", ")}",
                    suggested = row,
                    target = RegisterType.SALES,
                    rawText = invoice.rawText,
                )
            } else {
                salesRows += row
            }
        }

        for (invoice in input.purchaseInvoices) {
            val txn = paymentByInvoice[invoice]
            val decision = categorizer.categorize(invoice.partyName, txn?.description, invoice.rawText.take(400))
            val row = RegisterRow(
                date = invoice.date,
                gstNo = invoice.gstNo,
                invoiceNo = invoice.invoiceNo,
                name = invoice.partyName,
                qty = invoice.qty,
                ratePct = invoice.ratePct,
                hsn = invoice.hsn,
                taxable = invoice.taxable,
                cgst = invoice.cgst,
                sgst = invoice.sgst,
                igst = invoice.igst,
                totalGrand = invoice.totalGrand,
                paidDate = txn?.date,
                paymentMethod = categorizer.paymentMethod(txn),
                category = decision.category,
                provenance = invoice.sourceFile,
            )
            when {
                invoice.confidence() < CONFIDENCE_THRESHOLD -> review += ReviewItem(
                    sourceFile = invoice.sourceFile,
                    reason = ReviewReason.LOW_CONFIDENCE,
                    detail = "Could not read: ${invoice.missingFields().joinToString(", ")}",
                    suggested = row,
                    target = RegisterType.PURCHASE,
                    rawText = invoice.rawText,
                )
                decision.ambiguous -> review += ReviewItem(
                    sourceFile = invoice.sourceFile,
                    reason = ReviewReason.AMBIGUOUS_CATEGORY,
                    detail = "Personal or Business? Defaulted to ${decision.category.label}.",
                    suggested = row,
                    target = RegisterType.PURCHASE,
                    rawText = invoice.rawText,
                )
                else -> purchaseRows += row
            }
        }

        for (txn in purchaseMatch.unmatchedTxns) {
            if (txn.isDebit && !input.statementLinesBecomeRows) continue
            if (txn.isDebit) {
                // An unmatched debit is a real expense with no invoice PDF — a bank charge,
                // an ATM fee, a vendor paid without paperwork. It becomes its own row with
                // the tax columns left blank and the amount straight into TOTAL GRAND.
                val decision = categorizer.categorize(txn.description)
                val row = RegisterRow(
                    date = txn.date,
                    name = txn.description,
                    totalGrand = txn.debit,
                    paidDate = txn.date,
                    paymentMethod = categorizer.paymentMethod(txn),
                    category = decision.category,
                    provenance = txn.rawLine,
                )
                if (decision.ambiguous) {
                    review += ReviewItem(
                        sourceFile = txn.description,
                        reason = ReviewReason.AMBIGUOUS_CATEGORY,
                        detail = "Personal or Business? Defaulted to ${decision.category.label}.",
                        suggested = row,
                        target = RegisterType.PURCHASE,
                        rawText = txn.rawLine,
                    )
                } else {
                    purchaseRows += row
                }
            } else {
                // An unmatched credit is NOT auto-added to the sales register. A bank credit
                // is not evidence of a GST invoice, and inventing a sale would misstate a
                // GST filing — the one error worth stopping the whole pipeline over.
                review += ReviewItem(
                    sourceFile = txn.description,
                    reason = ReviewReason.UNMATCHED_CREDIT,
                    detail = "Unexplained credit of ${txn.amount}. Not added to the Sales " +
                        "Register automatically — confirm whether a sale invoice exists.",
                    suggested = null,
                    target = RegisterType.SALES,
                    rawText = txn.rawLine,
                )
            }
        }

        for (line in input.unparsedLines) {
            review += ReviewItem(
                sourceFile = "Statement",
                reason = ReviewReason.PARSE_FAILED,
                detail = "Could not determine whether this line is money in or money out.",
                target = RegisterType.PURCHASE,
                rawText = line,
            )
        }

        return ProcessResult(
            purchaseRows = purchaseRows.sortedWith(compareBy(nullsLast()) { it.date }),
            salesRows = salesRows.sortedWith(compareBy(nullsLast()) { it.date }),
            review = review,
            stats = mapOf(
                "purchase_rows" to purchaseRows.size,
                "sales_rows" to salesRows.size,
                "matched" to (salesMatch.matches.size + purchaseMatch.matches.size),
                "review" to review.size,
                "unmatched_debits" to purchaseMatch.unmatchedTxns.count { it.isDebit },
                "unmatched_credits" to purchaseMatch.unmatchedTxns.count { !it.isDebit },
            ),
        )
    }

    companion object {
        /**
         * Below this, an extraction goes to review instead of the workbook. Set so that a
         * missing total or a missing date alone is enough to hold a row back.
         */
        const val CONFIDENCE_THRESHOLD = 0.70
    }
}
