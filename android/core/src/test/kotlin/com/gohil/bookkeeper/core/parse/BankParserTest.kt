package com.gohil.bookkeeper.core.parse

import com.gohil.bookkeeper.core.model.StatementSource
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BankParserTest {

    @Test
    fun `reads a bank statement with explicit Dr and Cr markers`() {
        val text = """
            01/06/2025  UPI/SHARMA OFFICE/1234      1,180.00 Dr   48,820.00
            03/06/2025  NEFT CR COMMISSION PAYOUT  25,000.00 Cr   73,820.00
        """.trimIndent()

        val result = BankParser.parse(text, StatementSource.BANK)
        assertEquals(2, result.transactions.size)
        assertTrue(result.transactions[0].isDebit)
        assertEquals(BigDecimal("1180.00"), result.transactions[0].debit)
        assertTrue(!result.transactions[1].isDebit)
        assertEquals(BigDecimal("25000.00"), result.transactions[1].credit)
    }

    @Test
    fun `uses the falling balance to identify an unmarked bank debit`() {
        val text = """
            01/06/2025  OPENING ENTRY UPI PAID      1,000.00 Dr   50,000.00
            02/06/2025  SOME MERCHANT PAYMENT         500.00      49,500.00
        """.trimIndent()

        val result = BankParser.parse(text, StatementSource.BANK)
        assertEquals(2, result.transactions.size)
        assertTrue(
            result.transactions[1].isDebit,
            "balance fell by exactly the transaction amount, so it left the account",
        )
    }

    @Test
    fun `does not read a card purchase as a credit because the amount owed rose`() {
        // Regression: a credit card's running figure is the amount OWED, which rises on a
        // purchase. Applying the bank rule — balance up means money in — classified ordinary
        // purchases as credits, which kept them out of the purchase register entirely and
        // surfaced them as "unexplained money received".
        val text = """
            01/06/2025  SWIGGY BANGALORE Dr           612.50    12,612.50
            02/06/2025  RELIANCE DIGITAL STORE      2,400.00    15,012.50
        """.trimIndent()

        val result = BankParser.parse(text, StatementSource.CREDIT_CARD)
        val credits = result.transactions.filter { !it.isDebit }
        assertTrue(
            credits.isEmpty(),
            "no card line here is money received, but got: ${credits.map { it.description }}",
        )
    }

    @Test
    fun `sends an unresolvable card line to review rather than guessing its direction`() {
        val text = """
            01/06/2025  SWIGGY BANGALORE Dr           612.50    12,612.50
            02/06/2025  UNRECOGNISED MERCHANT       2,400.00    15,012.50
        """.trimIndent()

        val result = BankParser.parse(text, StatementSource.CREDIT_CARD)
        assertEquals(1, result.transactions.size, "only the Dr-marked line is unambiguous")
        assertEquals(1, result.unparsed.size)
        assertTrue("UNRECOGNISED MERCHANT" in result.unparsed.single())
    }

    @Test
    fun `still reads card lines that carry their own markers or keywords`() {
        val text = """
            01/06/2025  AMAZON PURCHASE Dr          2,400.00    15,012.50
            05/06/2025  PAYMENT RECEIVED Cr        10,000.00     5,012.50
            07/06/2025  ANNUAL FEE CHARGE             500.00     5,512.50
        """.trimIndent()

        val result = BankParser.parse(text, StatementSource.CREDIT_CARD)
        assertEquals(3, result.transactions.size)
        assertTrue(result.transactions[0].isDebit)
        assertTrue(!result.transactions[1].isDebit, "an explicit Cr is still honoured")
        assertTrue(result.transactions[2].isDebit, "'charge' and 'fee' read as money out")
    }

    @Test
    fun `skips headers and summary lines that happen to carry a date and figures`() {
        val text = """
            Statement period 01/06/2025 to 30/06/2025
            Opening Balance 01/06/2025             50,000.00
            05/06/2025  REAL TRANSACTION Dr         1,000.00   49,000.00
            Closing Balance 30/06/2025             49,000.00
        """.trimIndent()

        val result = BankParser.parse(text, StatementSource.BANK)
        assertEquals(1, result.transactions.size)
        assertTrue("REAL TRANSACTION" in result.transactions.single().description)
    }

    @Test
    fun `strips dates and amounts out of the narration`() {
        val result = BankParser.parse(
            "05/06/2025  UPI/SWIGGY/9876543 Dr    612.50   49,000.00",
            StatementSource.BANK,
        )
        val description = result.transactions.single().description
        assertTrue("SWIGGY" in description)
        assertTrue("612.50" !in description, "amounts do not belong in the name column")
        assertTrue("05/06/2025" !in description)
    }
}
