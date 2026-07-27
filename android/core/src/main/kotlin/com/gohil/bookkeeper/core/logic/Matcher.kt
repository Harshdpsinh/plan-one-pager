package com.gohil.bookkeeper.core.logic

import com.gohil.bookkeeper.core.model.BankTxn
import com.gohil.bookkeeper.core.model.Invoice
import java.math.BigDecimal
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Pairs invoices with the bank or card transactions that settled them.
 *
 * A matched pair must not produce two register rows: the transaction only supplies PAID DATE
 * and PAYMENT METHOD on the invoice's row. Everything left unmatched is handled by
 * [Pipeline], which is where the asymmetry between unmatched debits and credits lives.
 */
object Matcher {

    /** Rounding and small charges mean amounts rarely agree to the paisa. */
    val AMOUNT_TOLERANCE: BigDecimal = BigDecimal("2.00")

    /** Settlement commonly lags the invoice date. */
    const val DATE_TOLERANCE_DAYS = 15L

    data class Match(val invoice: Invoice, val txn: BankTxn, val amountDelta: BigDecimal, val dayDelta: Long)

    data class Result(
        val matches: List<Match>,
        val unmatchedInvoices: List<Invoice>,
        val unmatchedTxns: List<BankTxn>,
    )

    fun match(invoices: List<Invoice>, txns: List<BankTxn>): Result {
        val candidates = ArrayList<Match>()

        for (invoice in invoices) {
            val invAmount = invoice.matchAmount ?: continue
            val invDate = invoice.date ?: continue
            for (txn in txns) {
                val delta = (txn.amount - invAmount).abs()
                if (delta > AMOUNT_TOLERANCE) continue
                val days = abs(ChronoUnit.DAYS.between(invDate, txn.date))
                if (days > DATE_TOLERANCE_DAYS) continue
                candidates.add(Match(invoice, txn, delta, days))
            }
        }

        // Greedy, best-first, one-to-one. Closest amount wins; the nearer date breaks ties.
        // Assigning the strongest pairs first stops a loose candidate from consuming a
        // transaction that is an exact match for some other invoice.
        val usedInvoices = HashSet<Invoice>()
        val usedTxns = HashSet<BankTxn>()
        val accepted = ArrayList<Match>()

        for (candidate in candidates.sortedWith(compareBy({ it.amountDelta }, { it.dayDelta }))) {
            if (candidate.invoice in usedInvoices || candidate.txn in usedTxns) continue
            usedInvoices.add(candidate.invoice)
            usedTxns.add(candidate.txn)
            accepted.add(candidate)
        }

        return Result(
            matches = accepted,
            unmatchedInvoices = invoices.filterNot { it in usedInvoices },
            unmatchedTxns = txns.filterNot { it in usedTxns },
        )
    }
}
