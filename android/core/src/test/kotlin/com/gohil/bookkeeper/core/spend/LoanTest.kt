package com.gohil.bookkeeper.core.spend

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoanTest {

    private fun loan(
        rate: String = "12",
        principal: String = "120000",
        term: Int = 12,
        direction: LoanDirection = LoanDirection.GIVEN,
        repayments: List<Repayment> = emptyList(),
    ) = Loan(
        id = "l1",
        counterparty = "Ramesh Patel",
        direction = direction,
        principal = BigDecimal(principal),
        annualRatePct = BigDecimal(rate),
        startDate = LocalDate.of(2026, 1, 1),
        termMonths = term,
        repayments = repayments,
    )

    @Test
    fun `an interest free loan splits evenly and needs no special case`() {
        val l = loan(rate = "0", principal = "120000", term = 12)
        assertEquals("10000.00", l.emi().setScale(2).toPlainString())
        assertEquals("0.00", l.schedule().last().balanceAfter.toPlainString())
    }

    @Test
    fun `EMI matches the standard reducing balance formula`() {
        // 1,20,000 at 12% for 12 months is 10,661.85 by the textbook formula.
        val emi = loan().emi().setScale(2, java.math.RoundingMode.HALF_UP)
        assertEquals("10661.85", emi.toPlainString())
    }

    @Test
    fun `the schedule ends at exactly zero rather than a rounding remainder`() {
        val schedule = loan().schedule()
        assertEquals(12, schedule.size)
        assertEquals("0.00", schedule.last().balanceAfter.toPlainString())
        // Interest must fall every month on a reducing balance.
        assertTrue(schedule.first().interest > schedule.last().interest)
    }

    @Test
    fun `an unpaid loan accrues interest and stays outstanding`() {
        val balance = loan().balanceOn(LocalDate.of(2026, 7, 1))
        assertEquals("120000.00", balance.outstandingPrincipal.toPlainString())
        // 181 days at 12% on 1,20,000 = 7,140.82.
        assertEquals("7140.82", balance.interestAccrued.toPlainString())
        assertFalse(balance.settled)
    }

    @Test
    fun `a repayment clears interest first and then principal`() {
        val l = loan(
            repayments = listOf(
                Repayment(LocalDate.of(2026, 7, 1), BigDecimal("20000")),
            ),
        )
        val balance = l.balanceOn(LocalDate.of(2026, 7, 1))
        // 7,140.82 of the 20,000 goes to interest; 12,859.18 comes off principal.
        assertEquals("0.00", balance.interestOutstanding.toPlainString())
        assertEquals("107140.82", balance.outstandingPrincipal.toPlainString())
        assertEquals("20000.00", balance.totalRepaid.toPlainString())
    }

    @Test
    fun `paying it all off marks the loan settled and never goes negative`() {
        val l = loan(rate = "0", principal = "50000", repayments = listOf(
            Repayment(LocalDate.of(2026, 3, 1), BigDecimal("30000")),
            // Deliberately more than what is left — an overpayment must floor at zero.
            Repayment(LocalDate.of(2026, 4, 1), BigDecimal("25000")),
        ))
        val balance = l.balanceOn(LocalDate.of(2026, 5, 1))
        assertEquals("0.00", balance.outstandingPrincipal.toPlainString())
        assertTrue(balance.settled)
    }

    @Test
    fun `repayments after the as-of date are ignored`() {
        val l = loan(rate = "0", repayments = listOf(
            Repayment(LocalDate.of(2026, 9, 1), BigDecimal("50000")),
        ))
        assertEquals("120000.00", l.balanceOn(LocalDate.of(2026, 6, 1)).outstandingPrincipal.toPlainString())
    }

    @Test
    fun `net position is what you are owed minus what you owe`() {
        val book = LoanBook(
            listOf(
                loan(rate = "0", principal = "100000", direction = LoanDirection.GIVEN),
                loan(rate = "0", principal = "40000", direction = LoanDirection.RECEIVED).copy(id = "l2"),
            ),
        )
        val asOf = LocalDate.of(2026, 6, 1)
        assertEquals("100000.00", book.outstanding(asOf).getValue(LoanDirection.GIVEN).toPlainString())
        assertEquals("60000.00", book.netPosition(asOf).toPlainString())
    }

    @Test
    fun `cash flow keeps lending out of spending but inside the month total`() {
        val spend = SpendSummary.from(
            listOf(
                SpendItem(
                    date = LocalDate.of(2026, 1, 15),
                    merchant = "Swiggy",
                    amount = BigDecimal("2000.00"),
                    category = SpendCategory.MEALS,
                    matchedOn = "swiggy",
                    confident = true,
                    source = "hdfc.pdf",
                ),
            ),
        )
        val book = LoanBook(
            listOf(
                loan(rate = "0", principal = "50000", repayments = listOf(
                    Repayment(LocalDate.of(2026, 2, 10), BigDecimal("5000")),
                )),
            ),
        )
        val flow = book.cashFlow(spend, asOf = LocalDate.of(2026, 3, 1))

        val january = flow.first { it.month == YearMonth.of(2026, 1) }
        assertEquals("2000.00", january.spending.toPlainString())
        assertEquals("50000", january.loansDisbursed.toPlainString())
        // Lending is an outflow, but it is not spending — both facts have to hold at once.
        assertEquals("52000.00", january.outflow.toPlainString())

        val february = flow.first { it.month == YearMonth.of(2026, 2) }
        assertEquals("5000", february.repaymentsReceived.toPlainString())
        assertEquals("5000", february.net.toPlainString())
    }
}
