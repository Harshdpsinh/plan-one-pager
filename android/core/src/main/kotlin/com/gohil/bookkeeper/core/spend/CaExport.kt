package com.gohil.bookkeeper.core.spend

import com.gohil.bookkeeper.core.model.RegisterRow
import com.gohil.bookkeeper.core.xlsx.XlsxWriter
import com.gohil.bookkeeper.core.xlsx.XlsxWriter.Cell
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Builds the single workbook that goes to the chartered accountant.
 *
 * One file rather than four, because the thing being sent is a *month*, and a CA reconciling
 * it needs the sheets side by side — an expense that turns out to be a GST-bearing purchase
 * has to be findable in both. Every sheet is a flat table with a header row, no merged cells
 * and no formulas, so it can be sorted, filtered and pasted into whatever the CA's own
 * software wants.
 *
 * This never touches the registers. It is a report built from what was read; the registers are
 * still updated by the append path, which is the only thing allowed to write to them.
 */
object CaExport {

    data class Input(
        val spend: SpendSummary,
        val investments: InvestmentSummary,
        val loans: List<Loan>,
        /** GST rows from the bookkeeping screen, if it was run in the same session. */
        val salesRows: List<RegisterRow> = emptyList(),
        val purchaseRows: List<RegisterRow> = emptyList(),
        val businessName: String = "Gohil Bookkeeper",
        val generatedOn: LocalDate = LocalDate.now(),
    )

    /**
     * An expense row that is also a GST purchase row.
     *
     * The two sheets are read from different screens: Expenses comes off the bank and card
     * statements, Purchases (GST) off the invoices. Do both for the same purchase — which the
     * instructions actively encourage — and the same money appears twice, at two different
     * figures (statements carry the invoice total, the GST sheet splits taxable from tax).
     * Silently deduplicating would be worse than the overlap, because an expense genuinely
     * paid twice in a month is a real thing. So both stay, and both say so.
     */
    private fun overlaps(input: Input): Set<Pair<LocalDate, BigDecimal>> {
        val purchases = input.purchaseRows.mapNotNull { row ->
            val date = row.date ?: return@mapNotNull null
            val total = row.totalGrand ?: return@mapNotNull null
            date to total.stripTrailingZeros()
        }.toSet()
        return input.spend.items
            .map { it.date to it.amount.stripTrailingZeros() }
            .filter { it in purchases }
            .toSet()
    }

    fun build(input: Input): ByteArray = XlsxWriter.build(
        listOf(
            summarySheet(input),
            expensesSheet(input.spend, overlaps(input)),
            expenseCategorySheet(input.spend),
            investmentsSheet(input.investments),
            salesSheet(input.salesRows),
            purchasesSheet(input.purchaseRows),
            loansSheet(input.loans, input.generatedOn),
            cashFlowSheet(input),
        ),
    )

    fun fileName(input: Input): String {
        val period = period(input)
        return "CA-Pack-${period.replace(' ', '-')}.xlsx"
    }

    // ── sheets ───────────────────────────────────────────────────────────────────

    private fun summarySheet(input: Input): XlsxWriter.Sheet {
        val salesTaxable = input.salesRows.sumOfOrZero { it.taxable }
        val salesTax = input.salesRows.sumOfOrZero { it.cgst } +
            input.salesRows.sumOfOrZero { it.sgst } + input.salesRows.sumOfOrZero { it.igst }
        val purchaseTax = input.purchaseRows.sumOfOrZero { it.cgst } +
            input.purchaseRows.sumOfOrZero { it.sgst } + input.purchaseRows.sumOfOrZero { it.igst }

        val rows = mutableListOf<List<Cell>>()
        fun line(item: String, value: Cell, note: String = "") {
            rows += listOf(Cell.Text(item), value, Cell.of(note))
        }

        line("Period covered", Cell.Text(period(input)))
        line("Prepared on", Cell.Date(input.generatedOn))
        rows += listOf(Cell.Blank)

        line(
            "Expenses (money consumed)",
            Cell.Money(input.spend.total),
            "${input.spend.items.size} transactions — see 'Expenses'",
        )
        line(
            "Investments (money moved into assets)",
            Cell.Money(input.investments.total),
            "${input.investments.items.size} transactions — NOT an expense, see 'Investments'",
        )
        rows += listOf(Cell.Blank)

        line("Sales — taxable value", Cell.Money(salesTaxable), "see 'Sales (GST)'")
        line("Sales — GST charged (CGST+SGST+IGST)", Cell.Money(salesTax), "output tax")
        line("Sales — invoice total", Cell.Money(input.salesRows.sumOfOrZero { it.totalGrand }))
        rows += listOf(Cell.Blank)

        line("Purchases — taxable value", Cell.Money(input.purchaseRows.sumOfOrZero { it.taxable }), "see 'Purchases (GST)'")
        line("Purchases — GST paid (CGST+SGST+IGST)", Cell.Money(purchaseTax), "input tax credit, subject to your CA's check")
        rows += listOf(Cell.Blank)

        val book = LoanBook(input.loans)
        val outstanding = book.outstanding(input.generatedOn)
        line("Loans given — outstanding", Cell.Money(outstanding.getValue(LoanDirection.GIVEN)), "receivable")
        line("Loans received — outstanding", Cell.Money(outstanding.getValue(LoanDirection.RECEIVED)), "payable")
        rows += listOf(Cell.Blank)

        line(
            "Items needing a human decision",
            Cell.count(input.spend.needsReview.size),
            "listed at the bottom of 'Expenses' with a flag",
        )

        val overlapping = overlaps(input)
        if (overlapping.isNotEmpty()) {
            val overlapTotal = input.spend.items
                .filter { (it.date to it.amount.stripTrailingZeros()) in overlapping }
                .fold(BigDecimal.ZERO) { a, i -> a + i.amount }
            line(
                "Rows appearing in BOTH Expenses and Purchases (GST)",
                Cell.Money(overlapTotal),
                "${overlapping.size} row(s) — same purchase read from the statement AND the " +
                    "invoice. Count once. Flagged in the 'Also in Purchases (GST)' column.",
            )
        }

        return XlsxWriter.Sheet(
            name = "Summary",
            headers = listOf("Item", "Amount", "Note"),
            rows = rows,
            notes = listOf(
                "${input.businessName} — accounts pack for ${period(input)}",
                "Prepared automatically from bank statements, card bills and invoices. Every figure is traceable to a source file named in the sheets.",
                "IMPORTANT: investments and loans are shown separately from expenses on purpose. Money put into a SIP or lent to someone is not consumed, so it is not an expense.",
                "Nothing here is a filing. Please check the flagged rows before using any of it.",
            ),
            widths = mapOf(0 to 42, 1 to 16, 2 to 62),
        )
    }

    private fun expensesSheet(
        spend: SpendSummary,
        overlaps: Set<Pair<LocalDate, BigDecimal>>,
    ): XlsxWriter.Sheet {
        // Flagged rows last: a CA scanning the sheet should reach the settled figures first
        // and the questions in one block at the end, rather than sifting them out by eye.
        val ordered = spend.items.sortedWith(
            compareBy({ it.confident && it.category != SpendCategory.UNCATEGORISED }, { it.date }),
        ).reversed()

        val overlapTotal = spend.items
            .filter { (it.date to it.amount.stripTrailingZeros()) in overlaps }
            .fold(BigDecimal.ZERO) { a, i -> a + i.amount }

        return XlsxWriter.Sheet(
            name = "Expenses",
            headers = listOf(
                "Date", "Merchant / narration", "Amount", "Category",
                "Matched on", "Needs check", "Also in Purchases (GST)", "Source file",
            ),
            rows = ordered.map { item ->
                listOf(
                    Cell.Date(item.date),
                    Cell.Text(item.merchant),
                    Cell.Money(item.amount),
                    Cell.Text(item.category.label),
                    Cell.of(item.matchedOn),
                    Cell.of(
                        when {
                            item.category == SpendCategory.UNCATEGORISED -> "Yes - no rule matched"
                            !item.confident -> "Yes - two categories matched"
                            else -> null
                        },
                    ),
                    Cell.of(
                        if ((item.date to item.amount.stripTrailingZeros()) in overlaps) {
                            "Yes - same date and amount"
                        } else {
                            null
                        },
                    ),
                    Cell.Text(item.source),
                )
            },
            totals = listOf(Cell.Text("Total"), Cell.Blank, Cell.Money(spend.total)),
            notes = buildList {
                add("Every outflow read from the statements, minus anything identified as an investment or a loan movement.")
                add("'Matched on' is the keyword that chose the category — a wrong category is corrected by editing that keyword list, not by hand-editing this sheet.")
                if (overlaps.isNotEmpty()) {
                    add(
                        "WARNING: ${overlaps.size} row(s) totalling ${overlapTotal.setScale(2, RoundingMode.HALF_UP).toPlainString()} " +
                            "also appear on 'Purchases (GST)' with the same date and amount. They are almost certainly " +
                            "the same purchase read from two sources — the statement and the invoice. Count them ONCE. " +
                            "They are left in both sheets rather than removed, because a genuine repeat payment in the " +
                            "same month looks identical from here and deleting it would lose real data.",
                    )
                }
            },
            widths = mapOf(0 to 12, 1 to 46, 2 to 14, 3 to 24, 4 to 20, 5 to 24, 6 to 26, 7 to 28),
        )
    }

    private fun expenseCategorySheet(spend: SpendSummary): XlsxWriter.Sheet =
        XlsxWriter.Sheet(
            name = "Expense by category",
            headers = listOf("Category", "Total", "Share %", "Transactions"),
            rows = spend.byCategory.map { row ->
                listOf(
                    Cell.Text(row.category.label),
                    Cell.Money(row.total),
                    Cell.Number(
                        if (spend.total.signum() == 0) BigDecimal.ZERO
                        else row.total.multiply(BigDecimal(100)).divide(spend.total, 1, RoundingMode.HALF_UP),
                    ),
                    Cell.count(row.count),
                )
            },
            totals = listOf(
                Cell.Text("Total"),
                Cell.Money(spend.total),
                Cell.Blank,
                Cell.count(spend.items.size),
            ),
            widths = mapOf(0 to 28, 1 to 16, 2 to 10, 3 to 14),
        )

    private fun investmentsSheet(investments: InvestmentSummary): XlsxWriter.Sheet =
        XlsxWriter.Sheet(
            name = "Investments",
            headers = listOf("Date", "Description", "Amount", "Instrument", "Matched on", "Source file"),
            rows = investments.items.map { item ->
                listOf(
                    Cell.Date(item.date),
                    Cell.Text(item.description),
                    Cell.Money(item.amount),
                    Cell.Text(item.type.label),
                    Cell.Text(item.matchedOn),
                    Cell.Text(item.source),
                )
            },
            totals = listOf(Cell.Text("Total invested"), Cell.Blank, Cell.Money(investments.total)),
            notes = listOf(
                "Outflows that bought an asset — SIPs, deposits, premiums, PPF/NPS — rather than paying for something consumed.",
                "Kept out of the expense total on purpose. Money here has changed shape, not owner.",
                "Only debits appear. Commission received from an AMC or insurer is income and is in the sales register, not here.",
            ),
            widths = mapOf(0 to 12, 1 to 46, 2 to 14, 3 to 24, 4 to 22, 5 to 28),
        )

    private fun salesSheet(rows: List<RegisterRow>): XlsxWriter.Sheet = gstSheet(
        name = "Sales (GST)",
        rows = rows,
        lastColumn = "GST type" to { row: RegisterRow -> row.gstType?.name },
        notes = listOf(
            "Commission and service invoices raised, with the GST breakdown as read from each invoice.",
            "GST type RCM means the insurer pays the GST directly under the Reverse Charge Mechanism. Only insurers confirmed to do so are marked RCM; anything unconfirmed is left as DIRECT rather than guessed.",
            "Blank cells are fields that could not be read. They are blank rather than zero on purpose — a zero in a GST column is a wrong number in a filing.",
        ),
    )

    private fun purchasesSheet(rows: List<RegisterRow>): XlsxWriter.Sheet = gstSheet(
        name = "Purchases (GST)",
        rows = rows,
        lastColumn = "Personal / business" to { row: RegisterRow -> row.category?.label },
        notes = listOf(
            "Purchase invoices with a GST breakdown. These are the ones that may carry input tax credit.",
            "The 'Expenses' sheet is a different thing: it is every outflow off the bank and card statements, most of which carry no invoice.",
            "Blank cells are fields that could not be read, never assumed zeros.",
        ),
    )

    private fun gstSheet(
        name: String,
        rows: List<RegisterRow>,
        lastColumn: Pair<String, (RegisterRow) -> String?>,
        notes: List<String>,
    ): XlsxWriter.Sheet {
        val (lastHeader, lastValue) = lastColumn
        return XlsxWriter.Sheet(
            name = name,
            headers = listOf(
                "Date", "GST NO", "Invoice no", "Name", "Taxable", "CGST", "SGST", "IGST",
                "Total", "Paid date", "Payment method", lastHeader, "Source",
            ),
            rows = rows.map { row ->
                listOf(
                    Cell.of(row.date),
                    Cell.of(row.gstNo),
                    Cell.of(row.invoiceNo),
                    Cell.of(row.name),
                    Cell.of(row.taxable),
                    Cell.of(row.cgst),
                    Cell.of(row.sgst),
                    Cell.of(row.igst),
                    Cell.of(row.totalGrand),
                    Cell.of(row.paidDate),
                    Cell.of(row.paymentMethod.label),
                    Cell.of(lastValue(row)),
                    Cell.of(row.provenance),
                )
            },
            totals = if (rows.isEmpty()) emptyList() else listOf(
                Cell.Text("Total"), Cell.Blank, Cell.Blank, Cell.Blank,
                Cell.Money(rows.sumOfOrZero { it.taxable }),
                Cell.Money(rows.sumOfOrZero { it.cgst }),
                Cell.Money(rows.sumOfOrZero { it.sgst }),
                Cell.Money(rows.sumOfOrZero { it.igst }),
                Cell.Money(rows.sumOfOrZero { it.totalGrand }),
            ),
            notes = if (rows.isEmpty()) {
                notes + "No rows: the GST bookkeeping screen was not run in this session, so there is nothing to report here."
            } else {
                notes
            },
            widths = mapOf(0 to 12, 1 to 20, 2 to 18, 3 to 34, 9 to 12, 10 to 16, 11 to 22, 12 to 26),
        )
    }

    private fun loansSheet(loans: List<Loan>, asOf: LocalDate): XlsxWriter.Sheet =
        XlsxWriter.Sheet(
            name = "Loans",
            headers = listOf(
                "Counterparty", "Direction", "Principal", "Rate % p.a.", "Start date",
                "Term (months)", "Repaid so far", "Outstanding principal",
                "Interest outstanding", "Settle today for", "Status", "Note",
            ),
            rows = loans.map { loan ->
                val balance = loan.balanceOn(asOf)
                listOf(
                    Cell.Text(loan.counterparty),
                    Cell.Text(if (loan.direction == LoanDirection.GIVEN) "Given (receivable)" else "Received (payable)"),
                    Cell.Money(loan.principal),
                    Cell.Number(loan.annualRatePct),
                    Cell.Date(loan.startDate),
                    Cell.count(loan.termMonths),
                    Cell.Money(balance.totalRepaid),
                    Cell.Money(balance.outstandingPrincipal),
                    Cell.Money(balance.interestOutstanding),
                    Cell.Money(balance.payoff),
                    Cell.Text(if (balance.settled) "Settled" else "Open"),
                    Cell.of(loan.note),
                )
            },
            notes = listOf(
                "Personal loans given and received, as at ${asOf.format(DAY)}.",
                "Balances are computed from the repayments actually recorded, not from the planned schedule: interest accrues daily on the reducing balance and each repayment clears interest before principal.",
                "Neither the principal nor the repayments are expenses.",
            ),
            widths = mapOf(0 to 26, 1 to 20, 2 to 14, 3 to 12, 4 to 12, 5 to 14, 6 to 14, 7 to 20, 8 to 18, 9 to 16, 10 to 10, 11 to 30),
        )

    private fun cashFlowSheet(input: Input): XlsxWriter.Sheet {
        val flow = LoanBook(input.loans).cashFlow(input.spend, input.investments, input.generatedOn)
        return XlsxWriter.Sheet(
            name = "Cash flow",
            headers = listOf(
                "Month", "Expenses", "Invested", "Lent out", "Borrowed",
                "Repaid to you", "You repaid", "Total out", "Total in", "Net",
            ),
            rows = flow.map { m ->
                listOf(
                    Cell.Text(m.month.format(MONTH)),
                    Cell.Money(m.spending),
                    Cell.Money(m.invested),
                    Cell.Money(m.loansDisbursed),
                    Cell.Money(m.loansBorrowed),
                    Cell.Money(m.repaymentsReceived),
                    Cell.Money(m.repaymentsPaid),
                    Cell.Money(m.outflow),
                    Cell.Money(m.inflow),
                    Cell.Money(m.net),
                )
            },
            notes = listOf(
                "Money out and money in per month. Investing and lending count as outflows here because the cash really left the account — they are simply not expenses.",
                "This is not a profit and loss statement: income only appears where it was recorded as a loan repayment. Commission income is in 'Sales (GST)'.",
            ),
            widths = mapOf(0 to 14, 1 to 14, 2 to 14, 3 to 14, 4 to 14, 5 to 16, 6 to 14, 7 to 14, 8 to 14, 9 to 14),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private fun period(input: Input): String {
        val months = buildList {
            input.spend.byMonth.forEach { add(it.month) }
            input.investments.byMonth.forEach { add(it.month) }
            input.salesRows.mapNotNull { it.date }.forEach { add(YearMonth.from(it)) }
            input.purchaseRows.mapNotNull { it.date }.forEach { add(YearMonth.from(it)) }
        }.distinct().sorted()

        return when {
            months.isEmpty() -> input.generatedOn.format(MONTH_FROM_DATE)
            months.size == 1 -> months.first().format(MONTH)
            else -> "${months.first().format(MONTH)} to ${months.last().format(MONTH)}"
        }
    }

    private fun <T> List<T>.sumOfOrZero(select: (T) -> BigDecimal?): BigDecimal =
        fold(BigDecimal.ZERO) { acc, item -> acc + (select(item) ?: BigDecimal.ZERO) }

    private val MONTH = DateTimeFormatter.ofPattern("MMM yyyy")
    private val MONTH_FROM_DATE = DateTimeFormatter.ofPattern("MMM yyyy")
    private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy")
}
