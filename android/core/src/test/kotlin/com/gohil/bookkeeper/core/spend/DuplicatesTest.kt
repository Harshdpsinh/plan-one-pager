package com.gohil.bookkeeper.core.spend

import com.gohil.bookkeeper.core.model.BankTxn
import com.gohil.bookkeeper.core.model.StatementSource
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuplicatesTest {

    private fun debit(day: Int, amount: String, description: String = "SOMETHING") = BankTxn(
        date = LocalDate.of(2026, 7, day),
        description = description,
        debit = BigDecimal(amount),
        source = StatementSource.CREDIT_CARD,
        rawLine = "",
    )

    private fun fingerprint(name: String, vararg txns: BankTxn) =
        Duplicates.Fingerprint(name, txns.map(Duplicates::key))

    @Test
    fun `the same statement downloaded twice is recognised even at a different file size`() {
        // csb.pdf is 3 MB and csb1.pdf is 10 MB — the same statement, saved twice. Hashing
        // the bytes says they are unrelated, which is why the rows are compared instead.
        val rows = arrayOf(debit(16, "500.00"), debit(17, "9990"), debit(20, "1323"), debit(25, "149.00"))
        val first = fingerprint("csb.pdf", *rows)
        val second = fingerprint("csb1.pdf", *rows)

        assertEquals("csb.pdf", Duplicates.duplicateOf(second, listOf(first))?.fileName)
    }

    @Test
    fun `a re-export with a few extra days is still the same statement`() {
        val original = fingerprint(
            "june.pdf",
            debit(1, "100.00"), debit(2, "200.00"), debit(3, "300.00"),
            debit(4, "400.00"), debit(5, "500.00"),
        )
        val extended = fingerprint(
            "june-again.pdf",
            debit(1, "100.00"), debit(2, "200.00"), debit(3, "300.00"),
            debit(4, "400.00"), debit(5, "500.00"), debit(6, "600.00"),
        )
        assertNotNull(Duplicates.duplicateOf(extended, listOf(original)))
    }

    @Test
    fun `two different accounts are not duplicates just because one payment matches`() {
        val bank = fingerprint(
            "icici.pdf",
            debit(1, "500.00"), debit(2, "1250.00"), debit(3, "299.00"), debit(4, "9981.00"),
        )
        val card = fingerprint(
            "sbi-card.pdf",
            debit(1, "500.00"), debit(9, "1131.26"), debit(10, "21633.26"), debit(18, "2004.48"),
        )
        assertNull(Duplicates.duplicateOf(card, listOf(bank)))
    }

    @Test
    fun `a statement that repeats itself is not a duplicate of anything`() {
        // The real trap. ICICI debits Rs 500 to the same broker twice on 10 July, and CSB
        // charges Rs 2,000 twice on 5 July. Six real payments across the month look like
        // three listed twice, and removing them would delete Rs 5,500 nobody would miss
        // until the accountant asked.
        val statement = fingerprint(
            "icici.pdf",
            debit(10, "500.00"), debit(10, "500.00"),
            debit(24, "500.00"), debit(24, "500.00"),
            debit(31, "500.00"), debit(31, "500.00"),
        )
        assertNull(Duplicates.duplicateOf(statement, emptyList()))
        assertEquals(6, statement.keys.size, "every occurrence is kept")
    }

    @Test
    fun `an invoice is too small to be called a duplicate statement`() {
        val one = fingerprint("invoice-a.pdf", debit(1, "5310.00"))
        val two = fingerprint("invoice-b.pdf", debit(1, "5310.00"))
        assertNull(
            Duplicates.duplicateOf(two, listOf(one)),
            "two vendors billing the same amount on the same day is not one invoice twice",
        )
    }

    @Test
    fun `a payment in two statements that are not copies is reported, not removed`() {
        val bank = fingerprint("icici.pdf", debit(1, "500.00"), debit(2, "1250.00"), debit(3, "299.00"))
        val card = fingerprint("sbi-card.pdf", debit(1, "500.00"), debit(9, "1131.26"), debit(10, "999.00"))

        val overlaps = Duplicates.overlaps(listOf(bank, card))
        assertEquals(1, overlaps.size)
        assertEquals(listOf("icici.pdf", "sbi-card.pdf"), overlaps.single().files)
        assertTrue("500" in overlaps.single().key)
    }

    @Test
    fun `a statement on its own reports no overlaps however much it repeats`() {
        val statement = fingerprint("icici.pdf", debit(10, "500.00"), debit(10, "500.00"))
        assertTrue(Duplicates.overlaps(listOf(statement)).isEmpty())
    }

    @Test
    fun `direction is part of what makes a payment the same payment`() {
        val out = debit(1, "500.00")
        val back = BankTxn(
            date = LocalDate.of(2026, 7, 1),
            description = "REFUND",
            credit = BigDecimal("500.00"),
            source = StatementSource.CREDIT_CARD,
            rawLine = "",
        )
        assertTrue(Duplicates.key(out) != Duplicates.key(back), "a refund is not the purchase")
    }

    @Test
    fun `trailing zeroes do not make two identical amounts look different`() {
        val a = debit(1, "500.00")
        val b = debit(1, "500")
        assertEquals(Duplicates.key(a), Duplicates.key(b))
    }
}
