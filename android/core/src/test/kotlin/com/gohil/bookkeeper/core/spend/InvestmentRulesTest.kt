package com.gohil.bookkeeper.core.spend

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InvestmentRulesTest {

    @Test
    fun `recognises the instruments an Indian statement actually shows`() {
        val cases = mapOf(
            "ACH D- SIP INSTALMENT PARAG PARIKH FLEXI CAP" to InstrumentType.MUTUAL_FUND,
            "UPI/GROWW INVEST TECH/4471" to InstrumentType.MUTUAL_FUND,
            "NEFT DR ZERODHA BROKING LTD" to InstrumentType.EQUITY,
            "LIC PREMIUM POLICY 8842119" to InstrumentType.LIFE_INSURANCE,
            "AUTO SWEEP TO FIXED DEPOSIT 00012" to InstrumentType.DEPOSIT,
            "PPF ACCOUNT TRANSFER SB" to InstrumentType.RETIREMENT,
            "SOVEREIGN GOLD BOND SERIES IV" to InstrumentType.GOLD,
        )
        for ((narration, expected) in cases) {
            assertEquals(expected, InvestmentRules.match(narration)?.type, narration)
        }
    }

    @Test
    fun `an ordinary expense is left alone`() {
        // The list is deliberately tight. An expense wrongly pulled out of the expense total
        // is money that quietly stops being counted, which is worse than the reverse.
        assertNull(InvestmentRules.match("UPI/SWIGGY LTD/8812 FOOD ORDER"))
        assertNull(InvestmentRules.match("POS PURCHASE RELIANCE FRESH"))
        assertNull(InvestmentRules.match("ATM WDL CHARGE"))
    }

    @Test
    fun `the longest keyword decides the instrument`() {
        val hit = InvestmentRules.match("HDFC LIFE INSURANCE PREMIUM DEBIT")
        assertEquals(InstrumentType.LIFE_INSURANCE, hit?.type)
        assertEquals("life insurance premium", hit?.matchedOn)
    }

    @Test
    fun `summarises by instrument and month`() {
        fun item(month: Int, amount: String, type: InstrumentType) = InvestmentItem(
            date = LocalDate.of(2026, month, 5),
            description = "d",
            amount = BigDecimal(amount),
            type = type,
            matchedOn = "kw",
            source = "s.pdf",
        )
        val summary = InvestmentSummary.from(
            listOf(
                item(5, "10000.00", InstrumentType.MUTUAL_FUND),
                item(6, "10000.00", InstrumentType.MUTUAL_FUND),
                item(6, "2500.50", InstrumentType.RETIREMENT),
            ),
        )
        assertEquals("22500.50", summary.total.toPlainString())
        assertEquals(InstrumentType.MUTUAL_FUND, summary.byType.first().type)
        assertEquals("20000.00", summary.byType.first().total.toPlainString())
        assertEquals(2, summary.byMonth.size)
        assertEquals("12500.50", summary.byMonth.last().total.toPlainString())
    }

    @Test
    fun `cash flow counts investing as an outflow but never as spending`() {
        val spend = SpendSummary.from(
            listOf(
                SpendItem(
                    date = LocalDate.of(2026, 6, 5),
                    merchant = "Swiggy",
                    amount = BigDecimal("2000.00"),
                    category = SpendCategory.MEALS,
                    matchedOn = "swiggy",
                    confident = true,
                    source = "s.pdf",
                ),
            ),
        )
        val invested = InvestmentSummary.from(
            listOf(
                InvestmentItem(
                    date = LocalDate.of(2026, 6, 1),
                    description = "SIP",
                    amount = BigDecimal("10000.00"),
                    type = InstrumentType.MUTUAL_FUND,
                    matchedOn = "sip instalment",
                    source = "s.pdf",
                ),
            ),
        )
        val june = LoanBook().cashFlow(spend, invested, LocalDate.of(2026, 7, 1)).single()
        assertEquals("2000.00", june.spending.toPlainString())
        assertEquals("10000.00", june.invested.toPlainString())
        assertEquals("12000.00", june.outflow.toPlainString())
        assertEquals("-12000.00", june.net.toPlainString())
    }
}
