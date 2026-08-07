package com.gohil.bookkeeper.core.spend

import com.gohil.bookkeeper.core.model.BankTxn

/**
 * Recognising the same statement, and the same transaction, when they arrive twice.
 *
 * ## Why this is not simply "drop repeated rows"
 *
 * A statement legitimately repeats itself. The July ICICI statement debits ₹500 to the same
 * broker twice on the same day, three separate times in the month, and the CSB card charges
 * ₹2,000 twice on 5 July. Those are six real payments, not three payments listed twice.
 * Deduplicating rows inside one file would quietly delete ₹5,500 of genuine spending, and
 * nobody would see it go.
 *
 * So the unit of duplication is the **file**, not the row. Two files that describe the same
 * transactions are the same statement downloaded twice — which is exactly what
 * `csb.pdf` and `csb1.pdf` are, at 3 MB and 10 MB, so no amount of hashing the bytes would
 * have noticed.
 *
 * ## Why the threshold is high
 *
 * Across the first real month, every pair of different statements shared **nothing at all**,
 * and the one duplicate pair shared **everything**. There is no middle ground to tune
 * against, so the threshold sits high and the uncertain case is reported rather than acted
 * on. Two statements that partly overlap — a 1–30 June export and a 15 June–15 July one —
 * are not the same statement and must both be kept, but the rows they share would be
 * counted twice, so those get flagged for a human instead.
 */
object Duplicates {

    /**
     * What makes two rows the same payment: when, how much, and which way.
     *
     * Deliberately not the narration. The same payment read off two different exports of the
     * same account can differ by a reference number or a truncation, and a key that includes
     * the description would then miss the duplicate it exists to catch.
     */
    fun key(txn: BankTxn): String = buildString {
        append(txn.date)
        append('|')
        append(txn.amount.stripTrailingZeros().toPlainString())
        append('|')
        append(if (txn.isDebit) 'D' else 'C')
    }

    /** One uploaded file, reduced to the payments it describes. */
    data class Fingerprint(val fileName: String, val keys: List<String>)

    /**
     * The already-accepted file this one repeats, or null if it is new.
     *
     * Compared against the smaller of the two, so a statement re-exported with a few extra
     * days on the end is still recognised as the same statement.
     */
    fun duplicateOf(candidate: Fingerprint, accepted: List<Fingerprint>): Fingerprint? {
        val mine = candidate.keys.toSet()
        if (mine.size < MIN_ROWS) return null
        return accepted.firstOrNull { other ->
            val theirs = other.keys.toSet()
            if (theirs.size < MIN_ROWS) return@firstOrNull false
            val shared = mine.count { it in theirs }
            shared.toDouble() / minOf(mine.size, theirs.size) >= SAME_STATEMENT
        }
    }

    /** One payment that turned up in more than one file that is not a duplicate statement. */
    data class Overlap(val key: String, val files: List<String>)

    /**
     * Payments present in two different files.
     *
     * Reported, never removed. Two accounts really can both show ₹500 leaving on the same
     * day, and there is no way to tell that apart from one payment listed twice — so this
     * says "look at these" rather than choosing.
     */
    fun overlaps(files: List<Fingerprint>): List<Overlap> {
        if (files.size < 2) return emptyList()
        val where = LinkedHashMap<String, MutableList<String>>()
        for (file in files) {
            for (key in file.keys.distinct()) {
                where.getOrPut(key) { mutableListOf() }.add(file.fileName)
            }
        }
        return where.entries
            .filter { it.value.size > 1 }
            .map { Overlap(it.key, it.value) }
    }

    /** Below this a "statement" is a receipt or an invoice, and sharing rows means nothing. */
    private const val MIN_ROWS = 3

    /**
     * How much two files must share to be the same statement. High on purpose: the cost of
     * being wrong is a whole statement silently missing from the month.
     */
    private const val SAME_STATEMENT = 0.90
}
