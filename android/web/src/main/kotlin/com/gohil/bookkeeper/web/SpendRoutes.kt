package com.gohil.bookkeeper.web

import com.gohil.bookkeeper.core.model.ProcessResult
import com.gohil.bookkeeper.core.model.StatementSource
import com.gohil.bookkeeper.core.parse.BankParser
import com.gohil.bookkeeper.core.parse.InvoiceParser
import com.gohil.bookkeeper.core.spend.CaExport
import com.gohil.bookkeeper.core.spend.Duplicates
import com.gohil.bookkeeper.core.spend.InvestmentItem
import com.gohil.bookkeeper.core.spend.InvestmentRules
import com.gohil.bookkeeper.core.spend.InvestmentSummary
import com.gohil.bookkeeper.core.spend.Loan
import com.gohil.bookkeeper.core.spend.LoanBook
import com.gohil.bookkeeper.core.spend.LoanDirection
import com.gohil.bookkeeper.core.spend.Repayment
import com.gohil.bookkeeper.core.spend.SpendCategorizer
import com.gohil.bookkeeper.core.spend.SpendCategory
import com.gohil.bookkeeper.core.spend.SpendItem
import com.gohil.bookkeeper.core.spend.SpendRule
import com.gohil.bookkeeper.core.spend.SpendSummary
import io.javalin.Javalin
import io.javalin.http.Context
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * The Spend Analysis half of the app: smart uploads, per-file passwords, categorised charts
 * and the loan book.
 *
 * It shares the bookkeeper's parsers rather than growing its own. The same statement should
 * not read one way on the GST screen and another way on the dashboard — if it did, the two
 * numbers would disagree and neither could be trusted.
 */
class SpendRoutes(
    private val store: PasswordStore?,
    private val loans: LoanRepository,
    private val extractor: JvmExtractor = JvmExtractor(),
    private val categorizer: SpendCategorizer = SpendCategorizer(
        SpendCategorizer.defaults() + userRules(),
    ),
) {

    /**
     * Uploaded files live here until the tab is closed. Bytes stay in memory: a statement is
     * the most sensitive thing in this app, and the server has no reason to leave one on disk.
     */
    class Session {
        val files = ConcurrentHashMap<String, Staged>()
        @Volatile var summary: SpendSummary? = null
        @Volatile var investments: InvestmentSummary? = null
    }

    class Staged(
        val fileName: String,
        val bytes: ByteArray,
        @Volatile var state: PdfProbe.State,
        @Volatile var password: String? = null,
        @Volatile var note: String = "",
    )

    /**
     * @param gstOf the bookkeeping screen's result for the same browser session, so the CA
     *   pack can carry the GST detail without asking for the invoices a second time.
     */
    fun register(
        app: Javalin,
        sessionOf: (Context) -> Session,
        gstOf: (Context) -> ProcessResult? = { null },
    ) {
        app.post("/api/spend/upload") { ctx -> upload(ctx, sessionOf(ctx)) }
        app.post("/api/spend/unlock") { ctx -> unlock(ctx, sessionOf(ctx)) }
        app.post("/api/spend/analyse") { ctx -> analyse(ctx, sessionOf(ctx)) }
        app.post("/api/spend/remove") { ctx -> remove(ctx, sessionOf(ctx)) }
        app.get("/api/spend/export") { ctx -> export(ctx, sessionOf(ctx), gstOf(ctx)) }
        app.get("/api/spend/loans") { ctx -> ctx.json(loanPayload()) }
        app.post("/api/spend/loans") { ctx -> saveLoan(ctx) }
        app.post("/api/spend/loans/repayment") { ctx -> addRepayment(ctx) }
        app.post("/api/spend/loans/delete") { ctx -> deleteLoan(ctx) }
        app.get("/api/spend/cashflow") { ctx -> ctx.json(cashFlow(sessionOf(ctx))) }
        app.get("/api/spend/passwords") { ctx ->
            ctx.json(
                mapOf(
                    "remembered" to (store?.size() ?: 0),
                    "location" to PasswordStore.describeLocation(),
                    "enabled" to (store != null),
                ),
            )
        }
        app.post("/api/spend/passwords/forget") { ctx ->
            store?.forgetAll()
            ctx.json(mapOf("remembered" to 0))
        }
    }

    // ── uploads and passwords ────────────────────────────────────────────────────

    /**
     * Take the files and report, per file, whether that specific one needs a password.
     *
     * Nothing is parsed here. Probing first is what lets the UI ask about exactly one file
     * instead of demanding a pile of passwords up front for documents that may not even be
     * encrypted.
     */
    private fun upload(ctx: Context, session: Session) {
        val incoming = ctx.uploadedFiles("files")
        if (incoming.isEmpty()) throw IllegalArgumentException("No files were uploaded.")

        for (file in incoming) {
            val bytes = file.content().readBytes()
            val probe = PdfProbe.probe(bytes, file.filename(), store)
            val existing = session.files[probe.fileHash]
            if (existing != null) {
                // Same document twice in one session. Keep the unlocked copy rather than
                // resetting it to "needs password" and asking again.
                continue
            }
            session.files[probe.fileHash] = Staged(
                fileName = probe.fileName,
                bytes = bytes,
                state = probe.state,
                password = probe.password,
                note = probe.detail,
            )
        }
        session.summary = null
        ctx.json(fileList(session))
    }

    /** A password typed for one named file, checked against that file only. */
    private fun unlock(ctx: Context, session: Session) {
        val hash = ctx.formParam("fileHash").orEmpty()
        val password = ctx.formParam("password").orEmpty()
        // Remembering is the default because the user asked never to be asked twice, but it
        // stays a per-file choice — see PasswordStore for what writing it down actually costs.
        val remember = ctx.formParam("remember") != "false"
        val staged = session.files[hash] ?: throw IllegalArgumentException("That file is no longer loaded.")

        if (PdfProbe.verify(staged.bytes, password, store, remember)) {
            staged.state = PdfProbe.State.UNLOCKED_FROM_STORE
            staged.password = password
            staged.note = if (remember) "Remembered — you will not be asked for this file again." else ""
            session.summary = null
            ctx.json(mapOf("ok" to true, "files" to fileList(session)))
        } else {
            ctx.json(
                mapOf(
                    "ok" to false,
                    "error" to "That password did not open ${staged.fileName}. " +
                        "Bank passwords are case sensitive and often combine name and date of birth.",
                    "files" to fileList(session),
                ),
            )
        }
    }

    private fun remove(ctx: Context, session: Session) {
        session.files.remove(ctx.formParam("fileHash").orEmpty())
        session.summary = null
        ctx.json(fileList(session))
    }

    private fun fileList(session: Session) = mapOf(
        "files" to session.files.entries
            .sortedBy { it.value.fileName }
            .map { (hash, staged) ->
                mapOf(
                    "fileHash" to hash,
                    "fileName" to staged.fileName,
                    "state" to staged.state.name,
                    "needsPassword" to (staged.state == PdfProbe.State.NEEDS_PASSWORD),
                    "note" to staged.note,
                )
            },
        // Never the passwords themselves, only the count. The browser has no reason to hold
        // them, so it is not given the chance to leak them into a screenshot or a bug report.
        "needsPassword" to session.files.values.count { it.state == PdfProbe.State.NEEDS_PASSWORD },
    )

    // ── analysis ─────────────────────────────────────────────────────────────────

    private fun analyse(ctx: Context, session: Session) {
        val items = mutableListOf<SpendItem>()
        val invested = mutableListOf<InvestmentItem>()
        val skipped = mutableListOf<Map<String, String>>()
        val accepted = mutableListOf<Duplicates.Fingerprint>()

        for ((_, staged) in session.files) {
            if (staged.state == PdfProbe.State.NEEDS_PASSWORD) {
                skipped += mapOf("file" to staged.fileName, "why" to "Still locked — enter its password.")
                continue
            }
            if (staged.state == PdfProbe.State.UNREADABLE) {
                skipped += mapOf("file" to staged.fileName, "why" to "Could not be opened. ${staged.note}")
                continue
            }
            val passwords = listOfNotNull(staged.password)
            when (val extracted = extractor.extract(staged.bytes, staged.fileName, passwords)) {
                is JvmExtractor.Result.Text -> {
                    val found = itemsFrom(extracted.text, staged.fileName)
                    val fingerprint = Duplicates.Fingerprint(staged.fileName, found.keys)
                    val alreadyHave = Duplicates.duplicateOf(fingerprint, accepted)
                    when {
                        alreadyHave != null -> skipped += mapOf(
                            "file" to staged.fileName,
                            "why" to "The same statement as ${alreadyHave.fileName} — " +
                                "left out so this month is not counted twice.",
                        )
                        found.spend.isEmpty() && found.investments.isEmpty() -> {
                            skipped += mapOf(
                                "file" to staged.fileName,
                                "why" to "Opened, but no dated amounts were found in it.",
                            )
                            if (found.keys.isNotEmpty()) accepted += fingerprint
                        }
                        else -> {
                            items += found.spend
                            invested += found.investments
                            accepted += fingerprint
                        }
                    }
                }
                is JvmExtractor.Result.PasswordProblem -> {
                    staged.state = PdfProbe.State.NEEDS_PASSWORD
                    skipped += mapOf("file" to staged.fileName, "why" to "Needs a password.")
                }
                is JvmExtractor.Result.Failed ->
                    skipped += mapOf("file" to staged.fileName, "why" to extracted.reason)
            }
        }

        val summary = SpendSummary.from(items)
        val investmentSummary = InvestmentSummary.from(invested)
        session.summary = summary
        session.investments = investmentSummary
        ctx.json(
            summaryPayload(summary) + investmentPayload(investmentSummary) +
                mapOf(
                    "skipped" to skipped,
                    "overlaps" to overlapPayload(Duplicates.overlaps(accepted), items, invested),
                ),
        )
    }

    /**
     * Payments that appeared in two statements neither of which is a copy of the other.
     *
     * Shown rather than removed. Two accounts really can both pay ₹500 on the same day, and
     * nothing in the data distinguishes that from one payment listed twice — so this asks
     * instead of deciding. The amount is repeated back so the user can see what is at stake
     * without opening the statements.
     */
    private fun overlapPayload(
        overlaps: List<Duplicates.Overlap>,
        spend: List<SpendItem>,
        invested: List<InvestmentItem>,
    ): List<Map<String, Any>> = overlaps.mapNotNull { overlap ->
        val (date, amount) = overlap.key.split('|').let { it[0] to it[1] }
        val what = spend.firstOrNull { it.date.toString() == date && it.amount.stripTrailingZeros().toPlainString() == amount }?.merchant
            ?: invested.firstOrNull { it.date.toString() == date && it.amount.stripTrailingZeros().toPlainString() == amount }?.description
            ?: return@mapNotNull null
        mapOf(
            "date" to date,
            "amount" to amount,
            "what" to what,
            "files" to overlap.files,
        )
    }

    /** What one document turned into. Investments are separated from spending at the source. */
    data class Extracted(
        val spend: List<SpendItem> = emptyList(),
        val investments: List<InvestmentItem> = emptyList(),
        /**
         * Every transaction the file described, including credits and transfers, as
         * [Duplicates] keys. Taken before anything is filtered out so that a statement made
         * entirely of transfers is still recognisable when it arrives a second time.
         */
        val keys: List<String> = emptyList(),
    )

    /**
     * Turn one document's text into spend and investment rows.
     *
     * Statements are tried first because they yield many rows and are unambiguous when they
     * parse. A document that produces no transactions is treated as a single receipt or bill.
     * Only outflows are classified at all: a credit is money arriving, and charting it as
     * spending would double-count the month.
     */
    internal fun itemsFrom(text: String, fileName: String): Extracted {
        val source = if (looksLikeCard(text)) StatementSource.CREDIT_CARD else StatementSource.BANK
        val statement = BankParser.parse(text, source)
        if (statement.transactions.isNotEmpty()) {
            val spend = mutableListOf<SpendItem>()
            val invested = mutableListOf<InvestmentItem>()
            for (txn in statement.transactions.filter { it.isDebit }) {
                val description = txn.description.ifBlank { "(no description)" }
                // The investment test runs first: a SIP debit is not an expense at all, so
                // asking "which expense category?" about it is already the wrong question.
                val asset = InvestmentRules.match("$description ${txn.rawLine}")
                if (asset != null) {
                    invested += InvestmentItem(
                        date = txn.date,
                        description = description,
                        amount = txn.amount,
                        type = asset.type,
                        matchedOn = asset.matchedOn,
                        source = fileName,
                    )
                    continue
                }
                val hit = categorizer.classify(txn)
                // Same argument as the investment test above: a credit-card bill paid from
                // the bank account is not an expense. Its expenses are the card's own rows,
                // which arrive from the card statement, and counting the payment as well
                // charges the month twice.
                if (hit.category == SpendCategory.TRANSFER) continue
                spend += SpendItem(
                    date = txn.date,
                    merchant = description,
                    amount = txn.amount,
                    category = hit.category,
                    matchedOn = hit.matchedOn,
                    confident = hit.confident,
                    source = fileName,
                )
            }
            return Extracted(spend, invested, statement.transactions.map(Duplicates::key))
        }

        val invoice = InvoiceParser.parse(text, fileName)
        val amount = invoice.totalGrand ?: invoice.taxable ?: return Extracted()
        val date = invoice.date ?: return Extracted()
        val merchant = invoice.partyName ?: fileName.substringBeforeLast('.')

        InvestmentRules.match("$merchant ${text.take(400)}")?.let { asset ->
            return Extracted(
                investments = listOf(
                    InvestmentItem(date, merchant, amount, asset.type, asset.matchedOn, fileName),
                ),
            )
        }

        val hit = categorizer.classify(merchant, text.take(400))
        return Extracted(
            spend = listOf(
                SpendItem(
                    date = date,
                    merchant = merchant,
                    amount = amount,
                    category = hit.category,
                    matchedOn = hit.matchedOn,
                    confident = hit.confident && invoice.partyName != null,
                    source = fileName,
                ),
            ),
        )
    }

    /**
     * Which kind of statement this is, weighed rather than counted.
     *
     * This decides how a row with no Dr/Cr marker is read, so getting it wrong does not
     * degrade the answer — it inverts it. The previous rule looked for two card words
     * anywhere in the document, and every bank prints the sentence "never share your
     * Debit/Credit Card number with anyone" at the foot of the page. That is two card words.
     * On the July SBI statement it turned 53 commission receipts into 53 expenses and put
     * ₹1,14,898 of income into the spending chart.
     *
     * A card statement and a bank statement both mention cards and both mention balances, so
     * neither vocabulary is decisive on its own. What separates them is which vocabulary
     * dominates: a card is the document that talks about limits and amounts due, a bank
     * account is the document that talks about IFSC codes and withdrawals. Across nine real
     * statements the winning side is never close — the smallest margin is 2 to 0.
     */
    internal fun looksLikeCard(text: String): Boolean {
        val lower = text.lowercase()
        return CARD_MARKERS.count { it in lower } > BANK_MARKERS.count { it in lower }
    }

    private fun investmentPayload(summary: InvestmentSummary) = mapOf(
        "investedTotal" to summary.total.toPlainString(),
        "investedCount" to summary.items.size,
        "investments" to summary.items.map {
            mapOf(
                "date" to it.date.toString(),
                "description" to it.description,
                "amount" to it.amount.toPlainString(),
                "type" to it.type.name,
                "typeLabel" to it.type.label,
                "matchedOn" to it.matchedOn,
                "source" to it.source,
            )
        },
        "investedByType" to summary.byType.map {
            mapOf(
                "key" to it.type.name,
                "label" to it.type.label,
                "total" to it.total.toPlainString(),
                "count" to it.count,
            )
        },
    )

    // ── the accountant's workbook ────────────────────────────────────────────────

    /**
     * Builds the CA pack and hands it straight back as a download.
     *
     * Nothing is stored: the workbook is generated per request from whatever the session
     * currently holds, so there is no stale copy to hand over by mistake.
     */
    private fun export(ctx: Context, session: Session, gst: ProcessResult?) {
        val spend = session.summary
        val investments = session.investments ?: InvestmentSummary.from(emptyList())
        if (spend == null && gst == null) {
            throw IllegalStateException(
                "Nothing to export yet. Analyse some statements here, or process invoices on " +
                    "the GST screen first — both end up in the same workbook.",
            )
        }

        val input = CaExport.Input(
            spend = spend ?: SpendSummary.from(emptyList()),
            investments = investments,
            loans = loans.all(),
            salesRows = gst?.salesRows.orEmpty(),
            purchaseRows = gst?.purchaseRows.orEmpty(),
        )
        val bytes = CaExport.build(input)
        ctx.contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .header("Content-Disposition", "attachment; filename=\"${CaExport.fileName(input)}\"")
            .result(bytes)
    }

    private fun summaryPayload(summary: SpendSummary) = mapOf(
        "total" to summary.total.toPlainString(),
        "count" to summary.items.size,
        "needsReview" to summary.needsReview.size,
        "byCategory" to summary.byCategory.map {
            mapOf(
                "key" to it.category.name,
                "label" to it.category.label,
                "slot" to it.category.paletteSlot,
                "total" to it.total.toPlainString(),
                "count" to it.count,
            )
        },
        "byMonth" to summary.byMonth.map {
            mapOf(
                "month" to it.month.toString(),
                "label" to monthLabel(it.month),
                "total" to it.total.toPlainString(),
                "count" to it.count,
            )
        },
        "items" to summary.items.map {
            mapOf(
                "date" to it.date.toString(),
                "merchant" to it.merchant,
                "amount" to it.amount.toPlainString(),
                "category" to it.category.name,
                "categoryLabel" to it.category.label,
                "slot" to it.category.paletteSlot,
                "matchedOn" to it.matchedOn,
                "confident" to it.confident,
                "source" to it.source,
            )
        },
    )

    // ── loans ────────────────────────────────────────────────────────────────────

    private fun saveLoan(ctx: Context) {
        val id = ctx.formParam("id").orEmpty().ifBlank { java.util.UUID.randomUUID().toString() }
        val existing = loans.all().firstOrNull { it.id == id }
        val loan = Loan(
            id = id,
            counterparty = ctx.formParam("counterparty").orEmpty().trim()
                .ifBlank { throw IllegalArgumentException("Who is the loan with?") },
            direction = LoanDirection.valueOf(ctx.formParam("direction") ?: "GIVEN"),
            principal = money(ctx.formParam("principal"), "principal"),
            annualRatePct = ctx.formParam("annualRatePct").orEmpty().trim()
                .ifBlank { "0" }.let { BigDecimal(it) },
            startDate = LocalDate.parse(ctx.formParam("startDate") ?: LocalDate.now().toString()),
            termMonths = ctx.formParam("termMonths").orEmpty().trim().toIntOrNull() ?: 12,
            // Editing the terms of a loan must not erase the payments already recorded
            // against it — those are history, not settings.
            repayments = existing?.repayments ?: emptyList(),
            note = ctx.formParam("note").orEmpty().trim(),
        )
        loans.save(loan)
        ctx.json(loanPayload())
    }

    private fun addRepayment(ctx: Context) {
        val id = ctx.formParam("id") ?: throw IllegalArgumentException("Which loan?")
        val loan = loans.all().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("That loan no longer exists.")
        val repayment = Repayment(
            date = LocalDate.parse(ctx.formParam("date") ?: LocalDate.now().toString()),
            amount = money(ctx.formParam("amount"), "repayment amount"),
            note = ctx.formParam("note").orEmpty().trim(),
        )
        loans.save(loan.copy(repayments = loan.repayments + repayment))
        ctx.json(loanPayload())
    }

    private fun deleteLoan(ctx: Context) {
        loans.delete(ctx.formParam("id").orEmpty())
        ctx.json(loanPayload())
    }

    private fun money(raw: String?, what: String): BigDecimal {
        val cleaned = raw.orEmpty().replace(",", "").replace("₹", "").trim()
        val value = cleaned.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Enter a number for the $what.")
        if (value.signum() <= 0) throw IllegalArgumentException("The $what must be more than zero.")
        return value
    }

    private fun loanPayload(): Map<String, Any> {
        val today = LocalDate.now()
        val book = LoanBook(loans.all())
        val outstanding = book.outstanding(today)
        return mapOf(
            "loans" to book.loans.map { loan ->
                val balance = loan.balanceOn(today)
                mapOf(
                    "id" to loan.id,
                    "counterparty" to loan.counterparty,
                    "direction" to loan.direction.name,
                    "directionLabel" to loan.direction.label,
                    "principal" to loan.principal.toPlainString(),
                    "annualRatePct" to loan.annualRatePct.toPlainString(),
                    "startDate" to loan.startDate.toString(),
                    "termMonths" to loan.termMonths,
                    "note" to loan.note,
                    "emi" to loan.emi().toPlainString(),
                    "outstandingPrincipal" to balance.outstandingPrincipal.toPlainString(),
                    "interestOutstanding" to balance.interestOutstanding.toPlainString(),
                    "payoff" to balance.payoff.toPlainString(),
                    "totalRepaid" to balance.totalRepaid.toPlainString(),
                    "settled" to balance.settled,
                    "nextDue" to loan.nextDue(today)?.let {
                        mapOf("date" to it.dueDate.toString(), "amount" to it.due.toPlainString())
                    },
                    "repayments" to loan.repayments.sortedBy { it.date }.map {
                        mapOf(
                            "date" to it.date.toString(),
                            "amount" to it.amount.toPlainString(),
                            "note" to it.note,
                        )
                    },
                    "schedule" to loan.schedule().map {
                        mapOf(
                            "no" to it.instalmentNo,
                            "dueDate" to it.dueDate.toString(),
                            "due" to it.due.toPlainString(),
                            "interest" to it.interest.toPlainString(),
                            "principal" to it.principal.toPlainString(),
                            "balanceAfter" to it.balanceAfter.toPlainString(),
                        )
                    },
                )
            },
            "owedToYou" to outstanding.getValue(LoanDirection.GIVEN).toPlainString(),
            "owedByYou" to outstanding.getValue(LoanDirection.RECEIVED).toPlainString(),
            "netPosition" to book.netPosition(today).toPlainString(),
        )
    }

    private fun cashFlow(session: Session): Map<String, Any> {
        val summary = session.summary ?: SpendSummary.from(emptyList())
        val invested = session.investments ?: InvestmentSummary.from(emptyList())
        val rows = LoanBook(loans.all()).cashFlow(summary, invested)
        return mapOf(
            "months" to rows.map {
                mapOf(
                    "month" to it.month.toString(),
                    "label" to monthLabel(it.month),
                    "spending" to it.spending.toPlainString(),
                    "invested" to it.invested.toPlainString(),
                    "loansDisbursed" to it.loansDisbursed.toPlainString(),
                    "loansBorrowed" to it.loansBorrowed.toPlainString(),
                    "repaymentsReceived" to it.repaymentsReceived.toPlainString(),
                    "repaymentsPaid" to it.repaymentsPaid.toPlainString(),
                    "inflow" to it.inflow.toPlainString(),
                    "outflow" to it.outflow.toPlainString(),
                    "net" to it.net.toPlainString(),
                )
            },
        )
    }

    companion object {
        /**
         * Words a credit-card statement uses about itself.
         *
         * "credit card", "card number" and "statement period" are gone: all three appear in
         * the fraud warning at the foot of an ordinary bank statement, which is what made
         * the old rule call SBI's current account a credit card.
         */
        private val CARD_MARKERS = listOf(
            "total amount due", "total payment due", "minimum amount due", "minimum payment due",
            "payment due date", "available credit limit", "credit limit", "cash limit",
            "reward points", "rupay", "card statement", "cardholder", "billing period",
            "statement date", "pay your bill",
        )

        /**
         * Words only an account statement uses. CSB's card carries almost no text at all and
         * wins on two card words against none of these, which is the narrowest real case.
         */
        private val BANK_MARKERS = listOf(
            "statement of account", "statement of transactions", "account number", "account no",
            "ifsc", "micr", "branch code", "branch name", "closing balance", "opening balance",
            "withdrawal", "deposit", "cheque number", "value date", "narration",
        )
        private val MONTH_FORMAT = DateTimeFormatter.ofPattern("MMM yyyy")

        private fun monthLabel(month: YearMonth): String = month.atDay(1).format(MONTH_FORMAT)

        /** Where the user's own category words live. Alongside the password store. */
        fun rulesFile(): Path = PasswordStore.defaultDir().resolve("spend-categories.json")

        /**
         * The user's category words, if they have written any.
         *
         * The rules were always described as "correctable by editing a list", which was only
         * true for whoever could rebuild the app. Reading them from a file makes it true for
         * the person who actually knows what MYNTRA DESIGNS is.
         *
         * Read once at startup, not per request: a statement should not be categorised one
         * way at the top of a run and another way at the bottom because the file changed in
         * between.
         */
        fun userRules(): List<SpendRule> {
            val file = rulesFile()
            if (!Files.exists(file)) return emptyList()
            return runCatching { SpendCategorizer.userRules(Files.readString(file)) }
                .getOrDefault(emptyList())
        }
    }
}
