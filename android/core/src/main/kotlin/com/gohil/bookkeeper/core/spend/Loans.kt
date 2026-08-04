package com.gohil.bookkeeper.core.spend

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Which way the money went when the loan was made. */
enum class LoanDirection(val label: String) {
    /** You lent it out. Repayments are money coming back in. */
    GIVEN("Given"),

    /** You borrowed it. Repayments are money going out. */
    RECEIVED("Received"),
}

/** One actual payment against a loan, as it happened rather than as it was planned. */
data class Repayment(
    val date: LocalDate,
    val amount: BigDecimal,
    val note: String = "",
)

/**
 * A personal loan, given or received.
 *
 * Interest accrues on the reducing balance at [annualRatePct], day-counted on a 365-day year.
 * That is the convention informal loans in India are actually settled on, and it has the
 * property that matters here: paying early genuinely costs less, so the number the app shows
 * moves the same way the money does. A zero rate is a normal case, not an edge case — half of
 * these are interest-free family loans — and it is handled without a special branch.
 */
data class Loan(
    val id: String,
    val counterparty: String,
    val direction: LoanDirection,
    val principal: BigDecimal,
    val annualRatePct: BigDecimal = BigDecimal.ZERO,
    val startDate: LocalDate,
    /** Planned tenure. Used for the schedule and the EMI; the real balance follows [repayments]. */
    val termMonths: Int = 12,
    val repayments: List<Repayment> = emptyList(),
    val note: String = "",
) {
    /**
     * The level instalment that would clear the loan over [termMonths] at [annualRatePct],
     * compounded monthly — the standard EMI formula. Shown as guidance next to the schedule;
     * it is never used to invent a repayment that did not happen.
     */
    fun emi(): BigDecimal {
        if (termMonths <= 0) return BigDecimal.ZERO
        val monthlyRate = annualRatePct.divide(BigDecimal("1200"), MC)
        if (monthlyRate.signum() == 0) return principal.divide(BigDecimal(termMonths), MONEY)
        val growth = (BigDecimal.ONE + monthlyRate).pow(termMonths, MC)
        return principal.multiply(monthlyRate).multiply(growth)
            .divide(growth - BigDecimal.ONE, MONEY)
    }

    /** The planned amortisation, ignoring what was actually paid. */
    fun schedule(): List<ScheduleEntry> {
        if (termMonths <= 0) return emptyList()
        val instalment = emi()
        val monthlyRate = annualRatePct.divide(BigDecimal("1200"), MC)
        var balance = principal
        val out = mutableListOf<ScheduleEntry>()
        for (n in 1..termMonths) {
            val interest = balance.multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP)
            // The last instalment absorbs the rounding drift so the schedule ends at exactly
            // zero. Without this the final balance lands a rupee or two either side and looks
            // like a bug to anyone reconciling it by hand.
            val due = if (n == termMonths) balance + interest else instalment
            val principalPart = (due - interest).coerceAtMost(balance)
            balance = (balance - principalPart).coerceAtLeast(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
            out += ScheduleEntry(
                instalmentNo = n,
                dueDate = startDate.plusMonths(n.toLong()),
                due = due.setScale(2, RoundingMode.HALF_UP),
                interest = interest,
                principal = principalPart.setScale(2, RoundingMode.HALF_UP),
                balanceAfter = balance,
            )
        }
        return out
    }

    /**
     * What is actually outstanding on [asOf], from the repayments that really happened.
     *
     * Interest is accrued on the balance standing before each repayment, then the repayment is
     * applied to interest first and principal second. Overpayment is not treated as an error —
     * it simply drives the balance to zero and stops, because a loan cannot go negative.
     */
    fun balanceOn(asOf: LocalDate = LocalDate.now()): Balance {
        var balance = principal
        var interestAccrued = BigDecimal.ZERO
        var interestPaid = BigDecimal.ZERO
        var principalPaid = BigDecimal.ZERO
        var cursor = startDate

        val events = repayments.filter { !it.date.isAfter(asOf) }.sortedBy { it.date }
        for (event in events) {
            val accrued = accrue(balance, cursor, event.date)
            interestAccrued += accrued
            cursor = event.date

            val outstandingInterest = interestAccrued - interestPaid
            val toInterest = event.amount.coerceAtMost(outstandingInterest.coerceAtLeast(BigDecimal.ZERO))
            val toPrincipal = (event.amount - toInterest).coerceAtMost(balance).coerceAtLeast(BigDecimal.ZERO)
            interestPaid += toInterest
            principalPaid += toPrincipal
            balance = (balance - toPrincipal).coerceAtLeast(BigDecimal.ZERO)
        }
        if (asOf.isAfter(cursor)) interestAccrued += accrue(balance, cursor, asOf)

        return Balance(
            outstandingPrincipal = balance.setScale(2, RoundingMode.HALF_UP),
            interestAccrued = interestAccrued.setScale(2, RoundingMode.HALF_UP),
            interestOutstanding = (interestAccrued - interestPaid).coerceAtLeast(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP),
            principalRepaid = principalPaid.setScale(2, RoundingMode.HALF_UP),
            totalRepaid = events.fold(BigDecimal.ZERO) { a, r -> a + r.amount }.setScale(2, RoundingMode.HALF_UP),
            settled = balance.signum() == 0 && (interestAccrued - interestPaid).signum() <= 0,
            asOf = asOf,
        )
    }

    private fun accrue(balance: BigDecimal, from: LocalDate, to: LocalDate): BigDecimal {
        if (annualRatePct.signum() == 0 || balance.signum() == 0) return BigDecimal.ZERO
        val days = ChronoUnit.DAYS.between(from, to)
        if (days <= 0) return BigDecimal.ZERO
        return balance.multiply(annualRatePct).multiply(BigDecimal(days))
            .divide(BigDecimal("36500"), MC)
    }

    /** The next instalment still ahead of [asOf], or null once the tenure is over. */
    fun nextDue(asOf: LocalDate = LocalDate.now()): ScheduleEntry? =
        schedule().firstOrNull { it.dueDate.isAfter(asOf) }

    companion object {
        private val MC = MathContext.DECIMAL64
        private val MONEY = MathContext(12, RoundingMode.HALF_UP)
    }
}

data class ScheduleEntry(
    val instalmentNo: Int,
    val dueDate: LocalDate,
    val due: BigDecimal,
    val interest: BigDecimal,
    val principal: BigDecimal,
    val balanceAfter: BigDecimal,
)

data class Balance(
    val outstandingPrincipal: BigDecimal,
    val interestAccrued: BigDecimal,
    val interestOutstanding: BigDecimal,
    val principalRepaid: BigDecimal,
    val totalRepaid: BigDecimal,
    val settled: Boolean,
    val asOf: LocalDate,
) {
    /** Principal plus unpaid interest — what would settle the loan today. */
    val payoff: BigDecimal get() = (outstandingPrincipal + interestOutstanding).setScale(2, RoundingMode.HALF_UP)
}

/** Money in and money out for one month, loans and spending together. */
data class MonthlyCashFlow(
    val month: YearMonth,
    val spending: BigDecimal,
    val loansDisbursed: BigDecimal,
    val loansBorrowed: BigDecimal,
    val repaymentsReceived: BigDecimal,
    val repaymentsPaid: BigDecimal,
) {
    val outflow: BigDecimal get() = spending + loansDisbursed + repaymentsPaid
    val inflow: BigDecimal get() = loansBorrowed + repaymentsReceived
    val net: BigDecimal get() = inflow - outflow
}

/**
 * The whole loan book, and the bridge between loans and the spend dashboard.
 *
 * Loans are kept out of [SpendSummary] deliberately. Lending someone ₹50,000 is not an
 * expense — the money is still yours — so folding it into a spend chart would overstate what
 * was actually consumed. It belongs in cash flow, which is what this joins them in.
 */
class LoanBook(val loans: List<Loan> = emptyList()) {

    fun outstanding(asOf: LocalDate = LocalDate.now()): Map<LoanDirection, BigDecimal> =
        LoanDirection.entries.associateWith { direction ->
            loans.filter { it.direction == direction }
                .fold(BigDecimal.ZERO) { a, l -> a + l.balanceOn(asOf).payoff }
                .setScale(2, RoundingMode.HALF_UP)
        }

    /** Owed to you, minus what you owe. */
    fun netPosition(asOf: LocalDate = LocalDate.now()): BigDecimal {
        val o = outstanding(asOf)
        return (o.getValue(LoanDirection.GIVEN) - o.getValue(LoanDirection.RECEIVED))
            .setScale(2, RoundingMode.HALF_UP)
    }

    fun cashFlow(spend: SpendSummary, asOf: LocalDate = LocalDate.now()): List<MonthlyCashFlow> {
        val months = sortedSetOf<YearMonth>()
        spend.byMonth.forEach { months += it.month }
        loans.forEach { loan ->
            months += YearMonth.from(loan.startDate)
            loan.repayments.filter { !it.date.isAfter(asOf) }.forEach { months += YearMonth.from(it.date) }
        }

        val spendByMonth = spend.byMonth.associate { it.month to it.total }
        return months.map { month ->
            fun loansStarting(direction: LoanDirection) = loans
                .filter { it.direction == direction && YearMonth.from(it.startDate) == month }
                .fold(BigDecimal.ZERO) { a, l -> a + l.principal }

            fun repaymentsIn(direction: LoanDirection) = loans
                .filter { it.direction == direction }
                .flatMap { it.repayments }
                .filter { YearMonth.from(it.date) == month && !it.date.isAfter(asOf) }
                .fold(BigDecimal.ZERO) { a, r -> a + r.amount }

            MonthlyCashFlow(
                month = month,
                spending = spendByMonth[month] ?: BigDecimal.ZERO,
                loansDisbursed = loansStarting(LoanDirection.GIVEN),
                loansBorrowed = loansStarting(LoanDirection.RECEIVED),
                repaymentsReceived = repaymentsIn(LoanDirection.GIVEN),
                repaymentsPaid = repaymentsIn(LoanDirection.RECEIVED),
            )
        }
    }
}
