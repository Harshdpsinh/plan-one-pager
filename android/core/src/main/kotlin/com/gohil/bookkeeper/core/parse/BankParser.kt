package com.gohil.bookkeeper.core.parse

import com.gohil.bookkeeper.core.model.BankTxn
import com.gohil.bookkeeper.core.model.StatementSource
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Turns statement text into transactions.
 *
 * Bank PDF layouts vary enormously, and this runs entirely on heuristics, so its job is as
 * much to recognise what it cannot read as to parse what it can. Anything ambiguous is
 * surfaced rather than guessed: a mis-signed transaction silently corrupts a register.
 *
 * The shape of the file matters more than the words on it. Every statement checked against
 * this parser — two bank accounts and six credit cards — prints its transactions as a table
 * whose rows begin with the date, and prints everything else (marketing copy, fee schedules,
 * the worked interest example every card issuer includes) as prose. That single structural
 * fact does most of the work here; see [ROW_START].
 */
object BankParser {

    data class Result(
        val transactions: List<BankTxn>,
        /** Lines that looked like transactions but could not be resolved. */
        val unparsed: List<String>,
    )

    private val CREDIT_MARKERS = listOf(
        "cr", "credit", "deposit", "dep tfr", "received", "refund", "reversal",
        "surcharge rev", "interest credit", "cashback", "salary", "neft cr", "imps cr",
        "by transfer",
    )
    private val DEBIT_MARKERS = listOf(
        "dr", "debit", "achdr", "withdrawal", "wdl", "wdl tfr", "atm wdl", "paid",
        "purchase", "charge", "fee", "emi", "to transfer",
    )

    fun parse(
        text: String,
        source: StatementSource,
        openingBalance: BigDecimal? = null,
    ): Result {
        val txns = ArrayList<BankTxn>()
        val unparsed = ArrayList<String>()

        val parsed = rows(text.lines(), source)
            .filter { it.text.length >= MIN_LINE && !looksLikeHeader(it.raw) }
            .map { it to tokensOn(it.money, source) }
            .filter { (_, tokens) -> tokens.isNotEmpty() }

        // Every balance the statement prints, wherever it ended up. See [uniqueInBalances].
        val balances = parsed
            .filter { (_, tokens) -> source == StatementSource.BANK && tokens.size >= 2 }
            .mapTo(HashSet()) { (_, tokens) -> tokens.last().value.setScale(2, RoundingMode.HALF_UP) }

        var runningBalance = openingBalance

        for ((row, tokens) in parsed) {
            // The row's own leading date, not one picked up from an absorbed continuation.
            val date = Patterns.findDate(row.raw) ?: continue

            val resolved = resolve(row.text, tokens, runningBalance, balances, source)
            if (resolved == null) {
                // The running balance is now untrustworthy, so it is dropped rather than
                // carried forward. Carrying it forward looks helpful and is not: on a
                // statement whose rows arrive out of order it produces a delta that matches
                // the next row's amount with the sign inverted, and a confidently wrong
                // debit is the one outcome this parser exists to prevent.
                runningBalance = null
                // The whole row, not just the line the date sat on: a wrapped statement puts
                // the amount on the next line, and a review item showing only
                // "9 09.07.2026 NACH trxn" gives the user nothing to decide with.
                unparsed.add(row.text)
                continue
            }

            val (amount, isDebit, newBalance) = resolved
            runningBalance = newBalance

            txns.add(
                BankTxn(
                    date = date,
                    description = describe(row.text),
                    debit = if (isDebit) amount else null,
                    credit = if (isDebit) null else amount,
                    source = source,
                    rawLine = row.text,
                ),
            )
        }

        return Result(plausibleDates(txns), unparsed)
    }

    /**
     * Drops transactions dated far from the rest — a worked example, not real spend.
     *
     * A statement covers one cycle, so its transactions cluster inside a few weeks. RBL prints
     * a "Sample Transaction" table in its terms, and those rows lead with a date the same way a
     * real one does — "02-Jan-19 Membership Fee + GST 588.82" — so [ROW_START] cannot tell them
     * apart. Their dates give them away: years before the statement month. Anything more than
     * [MAX_SPAN_DAYS] before the newest transaction is one of those examples, not a purchase.
     */
    private fun plausibleDates(txns: List<BankTxn>): List<BankTxn> {
        if (txns.size < 2) return txns
        val cutoff = txns.maxOf { it.date }.minusDays(MAX_SPAN_DAYS)
        return txns.filter { !it.date.isBefore(cutoff) }
    }

    // ── finding the rows ─────────────────────────────────────────────────────────

    /**
     * One statement row, reassembled.
     *
     * @param raw       the line the row started on — the date and the text shown for review.
     * @param money     that line plus only as many wrapped lines as it took to reach the
     *                  figures. Everything after them is narration and must not contribute
     *                  amounts.
     * @param text      the whole row including trailing narration, used for the description
     *                  and for direction keywords.
     */
    private data class Row(val raw: String, val money: String, val text: String)

    /**
     * A transaction row begins with its date, optionally behind a serial number.
     *
     * Requiring that is the single highest-value rule in this file. Without it, real
     * statements produced 2018 "purchases", 2023 "late payment fees" and a 1996 "interest
     * charge" — all of them lifted out of the worked examples printed in the terms and
     * conditions — and they reached the accountant's workbook as genuine spend. Prose
     * mentions a date mid-sentence; a table row leads with it.
     *
     * ponytail: a statement that does not lead its rows with the date parses to nothing
     * rather than to noise. That is the intended trade — a visibly empty result is a
     * problem the user reports, while invented rows are a problem nobody notices until the
     * CA asks about them. Widen this pattern when a real statement needs it.
     */
    /** The three date shapes a statement row can begin with, shared by the patterns below. */
    private const val DATE_BODY =
        """\d{1,2}[/\-.]\d{1,2}[/\-.]\d{2,4}""" +
            """|\d{4}-\d{2}-\d{2}""" +
            """|\d{1,2}[\-\s](?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[\-\s,]*\d{2,4}"""

    private val ROW_START = Regex(
        """^(?:\d{1,3}\s+)?(?:$DATE_BODY)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A transaction glued to the right of a left-column summary item, on a two-column card
     * statement.
     *
     * RBL prints the account summary and the transaction list side by side, and PDFBox lays
     * the page out left-to-right, so a summary line and the transaction beside it arrive as one
     * line: `Total Amount Due PAY NOW 6,793.00 23 Jun 2026 CHITRA TRANSPORT 404.72`. The row no
     * longer begins with its date, so [ROW_START] skips it — and every such row was a debit
     * dropped from the total, which is why the card read ₹469 against its own printed ₹6,843.79.
     *
     * The signature is narrow on purpose: a full date, mid-line, followed by a description that
     * ends the line in an amount stated to the paise. The left prefix is whatever the summary
     * column happened to print — a figure ("6,793.00"), or an offer ("Flat Rs.100 OFF") — so it
     * is not required to be anything in particular. A marketing line dates itself as "31st July"
     * with no year and does not match; a summary line that ends in a date carries no trailing
     * figure and does not match; a transaction that already leads with its date is handled the
     * normal way and never reaches here. Worked examples in the terms can match this shape, but
     * they are dated years back and [plausibleDates] drops them. It runs on card statements
     * alone, so the bank parsers are untouched.
     */
    private val CARD_INTERLEAVE = Regex(
        """\b((?:$DATE_BODY)\b.*\d[.,]\d{2})\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** Recovers the transaction hiding after a left-column summary item; see [CARD_INTERLEAVE]. */
    private fun deInterleave(line: String, source: StatementSource): String {
        if (source != StatementSource.CREDIT_CARD || ROW_START.containsMatchIn(line)) return line
        return CARD_INTERLEAVE.find(line)?.groupValues?.get(1) ?: line
    }

    private fun rows(lines: List<String>, source: StatementSource): List<Row> {
        val clean = lines.map { deInterleave(it.trim().replace(Regex("""\s+"""), " "), source) }
        val out = ArrayList<Row>()
        var i = 0

        while (i < clean.size) {
            if (!ROW_START.containsMatchIn(clean[i])) {
                i++
                continue
            }

            // A card transaction is one self-contained line: date, description and amount,
            // with no wrapped figures. The label-above and continuation machinery below is a
            // bank-statement mechanism, and on a two-column card layout it does harm rather
            // than good — the line above and below the transaction are the neighbouring
            // summary column, and absorbing "Payments & Credits" turned a ₹2,549 purchase
            // into a credit. So a card row is exactly its own line and nothing more.
            if (source == StatementSource.CREDIT_CARD) {
                out.add(Row(raw = clean[i], money = clean[i], text = clean[i]))
                i++
                continue
            }

            // Some banks print the direction above the row rather than on it: SBI's
            // "DEP TFR"/"WDL TFR"/"DEBIT ACHDr" sit on their own short line, and on the
            // statement's first row they are the only thing that says which way it went,
            // because there is no previous balance to compare against yet.
            val label = clean.getOrNull(i - 1)
                ?.takeIf {
                    it.isNotEmpty() && it.length <= LABEL_ABOVE && !ROW_START.containsMatchIn(it)
                }
                .orEmpty()

            var money = listOfNotNull(label.ifEmpty { null }, clean[i]).joinToString(" ")
            val all = StringBuilder(money)
            // Only a bank statement wraps its figures onto the next line — ICICI prints the
            // date and narration on one line and the amount and balance on the next. All six
            // card statements checked keep the amount on the row's own line, and letting a
            // card row reach forward picked up the summary block's figures instead.
            var moneyClosed = source != StatementSource.BANK ||
                tokensOn(money, source).size >= 2

            var j = i + 1
            while (j < clean.size && j - i <= CONTINUATION &&
                !ROW_START.containsMatchIn(clean[j]) && !looksLikeHeader(clean[j]) &&
                !isLabelFor(clean, j)
            ) {
                if (clean[j].isNotEmpty()) {
                    all.append(' ').append(clean[j])
                    if (!moneyClosed) {
                        val grown = "$money ${clean[j]}"
                        // Stop at the wrapped line that carries the figures. Anything past it
                        // is narration, and on a bank statement the page footer's phone
                        // number reads as an amount if it is allowed in.
                        if (tokensOn(grown, source).size > tokensOn(money, source).size) {
                            moneyClosed = true
                        }
                        money = grown
                    }
                }
                j++
            }

            out.add(Row(raw = clean[i], money = money, text = all.toString()))
            i = j
        }
        return out
    }

    /**
     * True when this line is the column label belonging to the row that starts on the next
     * line, so the row above must not swallow it.
     *
     * SBI prints "DEBIT ACHDr" on its own line above the row it describes. Absorbed into the
     * row above, that word made a ₹2,349 deposit read as a withdrawal — the balance movement
     * said credit, but an explicit marker outranks it.
     */
    private fun isLabelFor(lines: List<String>, index: Int): Boolean =
        lines[index].length <= LABEL_ABOVE &&
            lines.getOrNull(index + 1)?.let { ROW_START.containsMatchIn(it) } == true

    // ── reading the figures ──────────────────────────────────────────────────────

    /** One money column on a row, with its direction marker if the issuer printed one. */
    private data class Token(val value: BigDecimal, val credit: Boolean?)

    /**
     * Everything on a row that has the shape of money but is not.
     *
     * Removed by shape rather than by size — guessing by magnitude ("2026 looks like a
     * year") would misread a genuine ₹2,026 payment.
     */
    private val NOISE = listOf(
        Regex("""\b\d{1,2}[/\-.]\d{1,2}[/\-.]\d{2,4}\b"""),
        Regex("""\b\d{4}-\d{2}-\d{2}\b"""),
        Regex(
            """\b\d{1,2}[\-\s](?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[\-\s,]*\d{2,4}\b""",
            RegexOption.IGNORE_CASE,
        ),
        // Times, including the half of a timestamp left behind when the rest wraps to the
        // next line: "01 Jul 26 09: RHYTHM KUMARAKOM" read 09 as the amount.
        Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\s*(?:am|pm)?""", RegexOption.IGNORE_CASE),
        Regex("""\b\d{1,2}:(?!\d)"""),
        // Reference, branch, serial and account numbers. Long, and — unlike every amount on
        // a statement — printed without grouping or paise. A card's serial number was being
        // read as the transaction amount on every ICICI row.
        Regex("""(?<![\d.,])\d{5,}(?![\d.,])"""),
    )

    /**
     * A money column, with the currency glyph, sign and direction marker around it.
     *
     * The leading `A-Za-z` in the lookbehind is what keeps reference numbers glued to letters
     * out: SBI writes `NEFT*SCBL...*SCBLH18400`, and 18400 is not ₹18,400.
     *
     * `C` appears in the currency alternation because PDFBox extracts the ₹ glyph in HDFC's
     * card statement as a capital C. It cannot swallow an ordinary word ending in C, because
     * the lookbehind still rejects a letter before it.
     */
    private val MONEY_WITH_MARKER = Regex(
        """(?<![\d.A-Za-z])(?<sign>[+\-])?\s*(?:₹|Rs\.?|INR|C)?\s*""" +
            """(?<amt>\d{1,3}(?:,\d{2,3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)(?![\d,])""" +
            """(?:\s*(?<mk>Cr|Dr)\b|\s+(?<end>[CD])\s*$)?""",
        RegexOption.IGNORE_CASE,
    )

    private fun tokensOn(row: String, source: StatementSource): List<Token> {
        val cleaned = NOISE.fold(row) { acc, pattern -> pattern.replace(acc, " ") }
        return MONEY_WITH_MARKER.findAll(cleaned).mapNotNull { match ->
            val raw = match.groups["amt"]?.value ?: return@mapNotNull null
            // A money column is grouped, or it states its paise, or both. A bare run of
            // digits is something else: the row's serial number, a rewards balance, CSB's
            // "Rupay Transactions - 0677" table heading, or the leftovers of a reference
            // that began with a digit.
            //
            // This is the rule the whole file rests on. Without it a row whose amount wrapped
            // to the next line already had "enough" figures — its own serial number — so it
            // never went looking for them, and the serial was then recorded as the running
            // balance, which threw off every row after it.
            if (!raw.contains(',') && !raw.contains('.')) return@mapNotNull null
            val value = Patterns.money(raw) ?: return@mapNotNull null
            if (value <= BigDecimal.ZERO) return@mapNotNull null

            // A figure glued to a slash is part of a reference, never a money column —
            // money columns are whitespace-separated. Measured from the digits, not from the
            // start of the match: the match also covers any sign, currency glyph and the
            // whitespace before them, so "CORP/  160.00" looked slash-glued and the real
            // amount was thrown away, leaving the row with only its balance.
            val digitsAt = match.groups["amt"]?.range?.first ?: return@mapNotNull null
            val before = cleaned.getOrNull(digitsAt - 1)
            if (before == '/' || before == '\\') return@mapNotNull null

            val marker = match.groups["mk"]?.value ?: match.groups["end"]?.value
            // A sign is only trusted on a card. SBI prints an empty debit or credit column
            // as a bare "-", so on a bank statement "- - 834.43" is a credit with two empty
            // columns before it, not a negative amount.
            val sign = match.groups["sign"]?.value
                ?.takeIf { source == StatementSource.CREDIT_CARD }

            val credit = when {
                marker != null -> marker.startsWith("c", ignoreCase = true)
                sign == "+" -> true
                sign == "-" -> false
                else -> null
            }
            Token(value, credit)
        }.toList()
    }

    private data class Resolved(val amount: BigDecimal, val isDebit: Boolean, val balance: BigDecimal?)

    /**
     * Decides which figure on the row is the transaction and which way it went.
     *
     * Most reliable first: a marker printed against a specific figure, then a marker somewhere
     * on the row, then the direction the running balance moved, then narration keywords.
     */
    private fun resolve(
        row: String,
        tokens: List<Token>,
        previousBalance: BigDecimal?,
        balances: Set<BigDecimal>,
        source: StatementSource,
    ): Resolved? {
        val lower = row.lowercase()

        // A marker attached to a figure beats everything, and it also identifies which figure
        // is the transaction. Axis prints "1,702.00 Cr 127.00 Dr" — amount then cashback —
        // so neither "the last column" nor "the second to last" finds the right one, but
        // "the one the issuer marked" does.
        val marked = tokens.indexOfFirst { it.credit != null }
        if (marked >= 0) {
            val balance = if (source == StatementSource.BANK && marked != tokens.lastIndex) {
                tokens.last().value
            } else {
                null
            }
            return Resolved(tokens[marked].value, !tokens[marked].credit!!, balance)
        }

        // A bank statement carries a running balance in its last column; a card statement
        // does not, so its last figure is the transaction itself.
        val hasBalanceColumn = source == StatementSource.BANK && tokens.size >= 2
        val balance = if (hasBalanceColumn) tokens.last().value else null
        val candidate = if (hasBalanceColumn) tokens[tokens.size - 2].value else tokens.last().value

        explicitMarker(lower)?.let { isDebit ->
            return Resolved(candidate, isDebit, balance)
        }

        // Balance movement is only meaningful on a bank account, where the balance falls as
        // money leaves. A credit-card statement's running figure is the amount owed, which
        // rises on a purchase.
        if (source == StatementSource.BANK && previousBalance != null && balance != null) {
            val delta = balance - previousBalance
            if (delta.abs().compareTo(BigDecimal.ZERO) != 0) {
                val matching = tokens.firstOrNull { it.value.compareTo(delta.abs()) == 0 }
                if (matching != null) {
                    return Resolved(matching.value, delta.signum() < 0, balance)
                }
            }
        }

        if (balance != null) {
            uniqueInBalances(candidate, balance, balances)?.let { isDebit ->
                // Deliberately does not carry the balance forward. Needing this test at all
                // means the rows around here did not arrive in order, so the next row's
                // delta would be measured against a figure that is not its predecessor —
                // and such a delta still matches an amount often enough to produce a
                // confident, inverted debit. The next row re-derives its own answer instead.
                return Resolved(candidate, isDebit, null)
            }
        }

        keywordDirection(lower)?.let { isDebit ->
            return Resolved(candidate, isDebit, balance)
        }

        // A card statement is a list of purchases. Its exceptions announce themselves —
        // "payment received", "refund", "cashback", a leading + — and every one of them is
        // caught above. Six real card statements agree on this; the earlier rule, written
        // before any of them could be checked, sent every unmarked purchase to review.
        if (source == StatementSource.CREDIT_CARD) return Resolved(candidate, true, null)

        // A bank line with no marker, no usable balance movement and no keyword could be
        // either direction, and guessing corrupts a register.
        return null
    }

    /**
     * Direction from the statement's own balances, ignoring what order they arrived in.
     *
     * PDFBox lays a page out by position, and where several rows share a date ICICI's layout
     * hands them back transposed — the amount of one row beside the balance of another. The
     * running-balance test then compares against the wrong figure and either fails or, worse,
     * matches with the sign inverted.
     *
     * The set of balances is still right even when the sequence is not. A debit of X leaving
     * balance B means B + X was a balance on this statement; a credit means B - X was. When
     * exactly one of those is present the direction is settled without trusting the order at
     * all. When both are — which happens where a run of identical amounts makes either
     * reading consistent — the row goes to review, as it should.
     */
    private fun uniqueInBalances(
        amount: BigDecimal,
        balance: BigDecimal,
        balances: Set<BigDecimal>,
    ): Boolean? {
        if (balances.isEmpty()) return null
        val asDebit = (balance + amount).setScale(2, RoundingMode.HALF_UP) in balances
        val asCredit = (balance - amount).setScale(2, RoundingMode.HALF_UP) in balances
        return when {
            asDebit && !asCredit -> true
            asCredit && !asDebit -> false
            else -> null
        }
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

    /**
     * The longest marker wins, not the most numerous.
     *
     * Counting made "Petrol Surcharge Rev" a tie — "surcharge rev" says credit and the
     * "charge" inside the same word says debit — so a reversal was read as a purchase. The
     * longer match is the more specific one, which is the same rule the spend categoriser
     * already uses to let "google workspace" beat "google".
     */
    private fun keywordDirection(lower: String): Boolean? {
        val credit = CREDIT_MARKERS.filter { it in lower }.maxOfOrNull { it.length } ?: 0
        val debit = DEBIT_MARKERS.filter { it in lower }.maxOfOrNull { it.length } ?: 0
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
        "total credit", "brought forward", "carried forward", "page ", "page no",
        "statement period", "statement summary", "account number", "customer id", "ifsc",
        "branch address", "date narration", "date description", "value date",
        "transaction date", "end of statement", "end of transactions",
    )

    /** Strips dates, amounts and column noise, leaving the narration. */
    private fun describe(row: String): String {
        var d = row
        for (pattern in NOISE) d = pattern.replace(d, " ")
        d = MONEY_WITH_MARKER.replace(d, " ")
        d = Regex("""\b(dr|cr|am|pm)\b""", RegexOption.IGNORE_CASE).replace(d, " ")
        // Separators left behind once the reference number between them is gone: stripping
        // "UPI/QWERTY ENTERPRISES/8812" used to leave the trailing slash on the row a
        // chartered accountant reads.
        d = Regex("""[\s*|]+""").replace(d, " ").trim()
        d = d.trim('-', '|', ':', '/', '\\', ',', '.', ' ')
        return d.take(MAX_DESCRIPTION)
    }

    private const val MIN_LINE = 12
    private const val MAX_DESCRIPTION = 120

    /** How many wrapped lines belong to one row. Four covers every layout checked. */
    private const val CONTINUATION = 4

    /**
     * How far before the newest transaction a date can still be a real one. A statement spans a
     * single cycle; a row dated further back is a worked example. See [plausibleDates].
     */
    private const val MAX_SPAN_DAYS = 120L

    /** Longest line above a row still treated as its column label rather than as prose. */
    private const val LABEL_ABOVE = 25
}
