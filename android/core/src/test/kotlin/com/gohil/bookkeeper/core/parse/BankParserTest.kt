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
    fun `reads an unmarked card line as a purchase`() {
        // This used to go to review, on the grounds that no real card statement had been
        // seen and the convention could not be checked. Six of them have now been checked —
        // Axis, CSB, HDFC, ICICI, RBL and SBI — and all six list purchases with no marker
        // and announce their exceptions in words ("PAYMENT RECEIVED", "REWARD POINT
        // REDEMPTION", a leading +). Reviewing every purchase on a card meant reviewing
        // essentially the whole statement.
        val text = """
            01/06/2025  SWIGGY BANGALORE Dr           612.50    12,612.50
            02/06/2025  UNRECOGNISED MERCHANT       2,400.00    15,012.50
        """.trimIndent()

        val result = BankParser.parse(text, StatementSource.CREDIT_CARD)
        assertEquals(2, result.transactions.size)
        assertTrue(result.transactions.all { it.isDebit })
        assertTrue(result.unparsed.isEmpty())
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

    @Test
    fun `the year in the date is never mistaken for the amount`() {
        // Regression: MONEY_TOKEN scanned the raw line, so 05/06/2026 contributed "2026".
        // On a line with no running balance that became the transaction amount, and every
        // June 2026 row would have been written as Rs 2,026.00.
        val result = BankParser.parse("05/06/2026  POS IRCTC WEB BOOKING  3,410.50 DR", StatementSource.BANK)
        val txn = result.transactions.single()
        assertEquals("3410.50", txn.amount.toPlainString())
        assertTrue(txn.isDebit)
    }

    @Test
    fun `a reference number glued to a slash is not an amount`() {
        // "UPI/SWIGGY LTD/8812" contributed 8812. With the year also counted the line had
        // three "amounts", so the last-column-is-the-balance rule picked the reference as the
        // transaction and the real figure as the balance.
        val result = BankParser.parse("05/06/2026 UPI/SWIGGY LTD/8812 1,240.00 DR", StatementSource.BANK)
        assertEquals("1240.00", result.transactions.single().amount.toPlainString())
    }

    @Test
    fun `a genuine amount that looks like a year is still read`() {
        // The fix works on shape, not magnitude. A real Rs 2,026 payment must survive.
        val result = BankParser.parse("14/07/2025  NEFT DR VENDOR PAYMENT  2,026.00", StatementSource.BANK)
        assertEquals("2026.00", result.transactions.single().amount.toPlainString())
    }

    @Test
    fun `the running balance column is still found when one is present`() {
        val result = BankParser.parse(
            "05/06/2026  UPI/SWIGGY LTD/8812  1,240.00 DR  45,231.00",
            StatementSource.BANK,
        )
        assertEquals("1240.00", result.transactions.single().amount.toPlainString())
    }

    // ── what the first real statements exposed ───────────────────────────────────
    //
    // Everything below reproduces a defect found by running this parser over the user's own
    // July statements — two bank accounts and six credit cards. Each one had reached the
    // dashboard, and most would have reached the accountant's workbook.

    @Test
    fun `the terms and conditions are not read as transactions`() {
        // Every card issuer prints a worked example of how interest is calculated. Those
        // examples produced 2018 "purchases", 2023 "late payment fees" and a 1996 "interest
        // charge" that a chartered accountant would have been asked to explain.
        val text = """
            15 Jul 2026  UPI-BLINKIT COMMERCE PRE LIMITED     293.00 D
            Interest on Rs. 25,000 @ 3.75% p.m. from 12th June to 11th July (30 Days) 924.66
            2 Total Amount Due on statement dated Oct 08, 2023 2,000.00
            Purchase on Sep 20, 2023, Rent Pay 5,000.00
            3. W.E.F 10JUN25, SPEND RS.50,000 OR MORE IN A CALENDAR QUARTER
        """.trimIndent()

        val result = BankParser.parse(text, StatementSource.CREDIT_CARD)
        assertEquals(1, result.transactions.size, "only the table row is a transaction")
        assertEquals("293.00", result.transactions.single().amount.toPlainString())
    }

    @Test
    fun `reads a row whose amount wrapped onto the next line`() {
        // ICICI prints the date and payee on one line and the amount and balance on the
        // next. Line-at-a-time parsing found a date with no figures, then figures with no
        // date, and read 1 transaction out of 32.
        val text = """
            1 01.07.2026 LIC OF INDIA BHAVNAGAR DIVISION AC
            NEFT-AXISP26071234567-LIC OF INDIA 47569.00 165980.56
            BHAVNAGAR DIVISION AC
            2 02.07.2026 NACH trxn
            ACH/ZERODHA BROKING 500.00 165480.56
            LTD/ICIC2607123456/W44U9NN8S5XZN7
        """.trimIndent()

        val result = BankParser.parse(text, StatementSource.BANK)
        val zerodha = result.transactions.single { "ZERODHA" in it.description }
        assertEquals("500.00", zerodha.amount.toPlainString())
        assertTrue(zerodha.isDebit, "the balance fell by exactly 500")
    }

    @Test
    fun `the row serial number is not an amount`() {
        // The serial in "6 03.07.2026 NACH trxn" satisfied the two-figures-found test on its
        // own, so the row never went looking for the line its amount had wrapped to — and
        // the serial was then carried forward as the running balance, throwing off the sign
        // of every row after it.
        val text = """
            5 03.07.2026 NACH trxn
            ACH/INDIAN CLEARING 500.00 163720.56
            6 03.07.2026 NACH trxn
            ACH/INDIAN CLEARING 1250.00 162470.56
        """.trimIndent()

        val result = BankParser.parse(text, StatementSource.BANK)
        assertTrue(result.transactions.none { it.amount.toPlainString() in setOf("5", "6") })
        assertEquals("1250.00", result.transactions.single().amount.toPlainString())
    }

    @Test
    fun `reads the single letter D and C markers SBI prints`() {
        val text = """
            22 Jun 26 PAYMENT RECEIVED 000HD26061234BALAAAEF6T2V 37,580.42 C
            23 Jun 26 UPI-BLINKIT COMMERCE PRE LIMITED 293.00 D
        """.trimIndent()

        val result = BankParser.parse(text, StatementSource.CREDIT_CARD)
        assertEquals(2, result.transactions.size)
        assertTrue(!result.transactions[0].isDebit, "C is a credit")
        assertEquals("37580.42", result.transactions[0].amount.toPlainString())
        assertTrue(result.transactions[1].isDebit, "D is a debit")
    }

    @Test
    fun `takes the amount the issuer marked, not the last column`() {
        // Axis prints "AMOUNT ... CASHBACK EARNED", so both the last column and the second
        // to last are the wrong figure. The marker says which one is the transaction.
        val result = BankParser.parse(
            "25/06/2026 MYNTRA DESIGNS,BANGALORE   CLOTH STORES 1,702.00 Cr 127.00 Dr",
            StatementSource.CREDIT_CARD,
        )
        val txn = result.transactions.single()
        assertEquals("1702.00", txn.amount.toPlainString())
        assertTrue(!txn.isDebit)
    }

    @Test
    fun `a reference number glued to letters is not an amount`() {
        // SBI writes "NEFT*SCBL26071234567*SCBLH18400". 18400 is not Rs 18,400.
        val text = """
            DEP TFR
            03/07/2026 03/07/2026 NEFT*SCBL26071234567*SCBLH18400 - - 834.43 2,92,769.81
        """.trimIndent()

        val result = BankParser.parse(text, StatementSource.BANK)
        assertEquals("834.43", result.transactions.single().amount.toPlainString())
    }

    @Test
    fun `an empty column printed as a dash is not a negative amount`() {
        // SBI prints an unused debit or credit column as a bare "-". Reading that as a sign
        // turned every deposit into a withdrawal.
        val text = """
            DEP TFR
            06/07/2026 06/07/2026 NEFT*HDFC26071234567*HDFCH0110 - - 2,349.78 2,98,413.84
        """.trimIndent()

        val result = BankParser.parse(text, StatementSource.BANK)
        assertTrue(!result.transactions.single().isDebit, "DEP TFR is money arriving")
    }

    @Test
    fun `the column label printed above a row belongs to that row, not the one before it`() {
        // SBI puts "DEBIT ACHDr" on its own line above the row it describes. Absorbed into
        // the row above, that word overrode a rising balance and made a deposit a withdrawal.
        val text = """
            DEP TFR
            06/07/2026 06/07/2026 NEFT*HDFC26071234567*HDFCH0110 - - 2,349.78 2,98,413.84
            DEBIT   ACHDr
            06/07/2026 06/07/2026 YESB26071234 INDIAN - 875.00 - 2,97,538.84
        """.trimIndent()

        val result = BankParser.parse(text, StatementSource.BANK)
        assertEquals(2, result.transactions.size)
        assertTrue(!result.transactions[0].isDebit, "the deposit keeps its direction")
        assertTrue(result.transactions[1].isDebit)
    }

    @Test
    fun `resolves direction from the set of balances when the rows arrive out of order`() {
        // PDFBox lays a page out by position, and where rows share a date ICICI hands them
        // back with the amount of one beside the balance of another. Row 6 below cannot be
        // reconciled against row 5, but 165470.56 - 1250 = 164220.56 is on the statement and
        // 164220.56 + 1250 is not, so its direction is still certain.
        val text = """
            4 03.07.2026 NACH trxn
            ACH/ZERODHA BROKING 500.00 165470.56
            5 03.07.2026 NACH trxn
            ACH/INDIAN CLEARING 500.00 163720.56
            6 03.07.2026 NACH trxn
            ACH/INDIAN CLEARING 1250.00 164220.56
        """.trimIndent()

        val result = BankParser.parse(text, StatementSource.BANK)
        val amounts = result.transactions.filter { it.isDebit }.map { it.amount.toPlainString() }
        assertTrue("1250.00" in amounts, "165470.56 - 1250 is a balance on this statement")
        assertTrue("500.00" in amounts, "163720.56 + 500 is one too")
        assertTrue(result.transactions.none { !it.isDebit }, "nothing here is money in")
    }

    @Test
    fun `does not invent a direction for a row the balances cannot settle`() {
        // Both readings agree with a balance on the statement, so there is no answer to give
        // and the row goes to review. The alternative is a confident, inverted debit.
        val text = """
            18 17.07.2026 NACH trxn
            ACH/ZERODHA BROKING 1,250.00 174317.26
            19 17.07.2026 NACH trxn
            ACH/INDIAN CLEARING 500.00 173317.26
            20 17.07.2026 NACH trxn
            ACH/INDIAN CLEARING 500.00 173817.26
        """.trimIndent()

        val result = BankParser.parse(text, StatementSource.BANK)
        assertTrue(
            result.transactions.none { !it.isDebit },
            "nothing here is money in, so nothing may be written as a credit",
        )
    }

    @Test
    fun `leaves no trailing separator on the narration`() {
        val result = BankParser.parse(
            "05/06/2026 UPI/QWERTY ENTERPRISES/8812  1,240.00 DR  45,231.00",
            StatementSource.BANK,
        )
        val description = result.transactions.single().description
        assertTrue("QWERTY ENTERPRISES" in description)
        assertTrue(
            !description.trimEnd().endsWith("/"),
            "the reference is gone but its separator reached the accountant: '$description'",
        )
    }
}
