package com.gohil.bookkeeper.core.parse

import com.gohil.bookkeeper.core.model.BankTxn
import com.gohil.bookkeeper.core.model.StatementSource
import java.math.BigDecimal

/**
 * Turns statement text into transactions.
 *
 * Bank PDF layouts vary enormously, and this runs entirely on heuristics, so its job is as
 * much to recognise what it cannot read as to parse what it can. Anything ambiguous is
 * surfaced rather than guessed: a mis-signed transaction silently corrupts a register.
 */
object BankParser {

    data class Result(
        val transactions: List<BankTxn>,
        /** Lines that looked like transactions but could not be resolved. */
        val unparsed: List<String>,
    )

    private val CREDIT_MARKERS = listOf(
        "cr", "credit", "deposit", "received", "refund", "reversal", "interest credit",
        "cashback", "salary", "neft cr", "imps cr", "by transfer",
    )
    private val DEBIT_MARKERS = listOf(
        "dr", "debit", "withdrawal", "wdl", "paid", "purchase", "charge", "fee",
        "emi", "atm wdl", "to transfer",
    )

    fun parse(
        text: String,
        source: StatementSource,
        openingBalance: BigDecimal? = null,
    ): Result {
        val txns = ArrayList<BankTxn>()
        val unparsed = ArrayList<String>()
        var runningBalance = openingBalance

        for (rawLine in text.lines()) {
            val line = rawLine.trim().replace(Regex("""\s+"""), " ")
            if (line.length < MIN_LINE) continue

            val date = Patterns.findDate(line) ?: continue
            val amounts = Patterns.MONEY_TOKEN.findAll(line)
                .mapNotNull { Patterns.money(it.groupValues[1]) }
                .filter { it > BigDecimal.ZERO }
                .toList()
            if (amounts.isEmpty()) continue

            // Header and summary lines contain a date and figures but are not transactions.
            if (looksLikeHeader(line)) continue

            val description = describe(line)
            val resolved = resolve(line, amounts, runningBalance, source)

            if (resolved == null) {
                unparsed.add(rawLine.trim())
                continue
            }

            val (amount, isDebit, newBalance) = resolved
            runningBalance = newBalance ?: runningBalance

            txns.add(
                BankTxn(
                    date = date,
                    description = description,
                    debit = if (isDebit) amount else null,
                    credit = if (isDebit) null else amount,
                    source = source,
                    rawLine = rawLine.trim(),
                ),
            )
        }

        return Result(txns, unparsed)
    }

    private data class Resolved(val amount: BigDecimal, val isDebit: Boolean, val balance: BigDecimal?)

    /**
     * Decides which figure on the line is the transaction and which way it went.
     *
     * Three strategies, most reliable first: an explicit Dr/Cr marker; the direction the
     * running balance moved; then keywords in the narration. The middle one applies to bank
     * accounts only — see the comment at its guard for why a credit card cannot use it.
     */
    private fun resolve(
        line: String,
        amounts: List<BigDecimal>,
        previousBalance: BigDecimal?,
        source: StatementSource,
    ): Resolved? {
        val lower = line.lowercase()

        // Most statements print a running balance as the final column.
        val hasBalanceColumn = amounts.size >= 2
        val balance = if (hasBalanceColumn) amounts.last() else null
        val candidate = if (hasBalanceColumn) amounts[amounts.size - 2] else amounts.first()

        explicitMarker(lower)?.let { isDebit ->
            return Resolved(candidate, isDebit, balance)
        }

        // Balance movement is only meaningful on a bank account, where the balance falls as
        // money leaves. A credit-card statement's running figure is the amount owed, which
        // RISES on a purchase, so the same test would read every unmarked purchase as a
        // credit — routing real expenses out of the purchase register and into "unexplained
        // money received". Inverting it here would assume a statement convention that has
        // not been checked against a real card PDF, and a mis-signed transaction silently
        // corrupts a register, so card lines fall through to the narration instead and go to
        // review when that is inconclusive.
        if (source == StatementSource.BANK && previousBalance != null && balance != null) {
            val delta = balance - previousBalance
            if (delta.abs().compareTo(BigDecimal.ZERO) != 0) {
                // The balance movement is authoritative when it agrees with a figure on the line.
                val matching = amounts.firstOrNull { it.compareTo(delta.abs()) == 0 }
                if (matching != null) {
                    return Resolved(matching, delta.signum() < 0, balance)
                }
            }
        }

        keywordDirection(lower)?.let { isDebit ->
            return Resolved(candidate, isDebit, balance)
        }

        return null
    }

    /** A standalone Dr/Cr token, not the letters appearing inside a word. */
    private fun explicitMarker(lower: String): Boolean? {
        val creditHit = Regex("""\b(cr|credit)\b""").containsMatchIn(lower)
        val debitHit = Regex("""\b(dr|debit)\b""").containsMatchIn(lower)
        return when {
            creditHit && !debitHit -> false
            debitHit && !creditHit -> true
            else -> null
        }
    }

    private fun keywordDirection(lower: String): Boolean? {
        val credit = CREDIT_MARKERS.count { it in lower }
        val debit = DEBIT_MARKERS.count { it in lower }
        return when {
            debit > credit -> true
            credit > debit -> false
            else -> null
        }
    }

    private fun looksLikeHeader(line: String): Boolean {
        val lower = line.lowercase()
        return HEADER_HINTS.any { it in lower }
    }

    private val HEADER_HINTS = listOf(
        "opening balance", "closing balance", "statement of account", "total debit",
        "total credit", "brought forward", "carried forward", "page ", "statement period",
        "account number", "customer id", "ifsc", "branch address", "date narration",
        "date description", "value date", "transaction date",
    )

    /** Strips dates, amounts and column noise, leaving the narration. */
    private fun describe(line: String): String {
        var d = line
        d = Regex("""\b\d{1,2}[/\-.]\d{1,2}[/\-.]\d{2,4}\b""").replace(d, " ")
        d = Regex("""\b\d{4}-\d{2}-\d{2}\b""").replace(d, " ")
        d = Patterns.MONEY_TOKEN.replace(d, " ")
        d = Regex("""\b(dr|cr)\b""", RegexOption.IGNORE_CASE).replace(d, " ")
        d = Regex("""\s+""").replace(d, " ").trim().trim('-', '|', ':').trim()
        return d.take(MAX_DESCRIPTION)
    }

    private const val MIN_LINE = 12
    private const val MAX_DESCRIPTION = 120
}
