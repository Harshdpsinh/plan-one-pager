package com.gohil.bookkeeper.core.spend

import com.gohil.bookkeeper.core.model.BankTxn
import com.gohil.bookkeeper.core.model.StatementSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpendCategorizerTest {

    private val categorizer = SpendCategorizer()

    @Test
    fun `classifies common Indian merchants from statement narration`() {
        val cases = mapOf(
            "UPI/SWIGGY LTD/9823012/PAYMENT" to SpendCategory.MEALS,
            "POS 4315XX IRCTC WEB BOOKING" to SpendCategory.TRAVEL,
            "AMAZON WEB SERVICES INDIA" to SpendCategory.SOFTWARE,
            "NEFT DR AIRTEL PREPAID RECHARGE" to SpendCategory.UTILITIES,
            "DTDC COURIER SERVICES" to SpendCategory.OFFICE,
            "BLINKIT COMMERCE PVT LTD" to SpendCategory.PERSONAL,
        )
        for ((narration, expected) in cases) {
            assertEquals(expected, categorizer.classify(narration).category, narration)
        }
    }

    @Test
    fun `says what it matched on so a wrong guess is diagnosable`() {
        val hit = categorizer.classify("UPI/ZOMATO ONLINE/44210")
        assertEquals(SpendCategory.MEALS, hit.category)
        assertEquals("zomato", hit.matchedOn)
    }

    @Test
    fun `bank charges outrank a generic match on the same line`() {
        // "ANNUAL FEE" on a card statement is a bank charge even when the line also names a
        // merchant-ish word. The priority is what makes that deterministic.
        val hit = categorizer.classify("ANNUAL FEE CREDIT CARD PLUS GST ON CHARGES")
        assertEquals(SpendCategory.BANK_CHARGES, hit.category)
    }

    @Test
    fun `flags a line that two different categories both claim`() {
        // "cafe" is Meals, "myntra" is Personal — a single line hitting both is genuinely
        // ambiguous and must be surfaced rather than silently resolved.
        val hit = categorizer.classify("MYNTRA CAFE COMBO ORDER")
        assertFalse(hit.confident, "a contested match must not be reported as confident")
    }

    @Test
    fun `longest match wins so a specific keyword beats a generic one`() {
        val rules = listOf(
            SpendRule(SpendCategory.SOFTWARE, keywords = listOf("google", "google workspace")),
        )
        assertEquals("google workspace", SpendCategorizer(rules).classify("GOOGLE WORKSPACE IN").matchedOn)
    }

    @Test
    fun `unknown merchants land in uncategorised rather than a wrong bucket`() {
        val hit = categorizer.classify("NEFT DR / QWERTYUIOP ENTERPRISES")
        assertEquals(SpendCategory.UNCATEGORISED, hit.category)
        assertFalse(hit.confident)
    }

    @Test
    fun `summarises by category and by month with exact rupee totals`() {
        fun item(day: Int, month: Int, amount: String, category: SpendCategory) = SpendItem(
            date = LocalDate.of(2026, month, day),
            merchant = "m$day",
            amount = BigDecimal(amount),
            category = category,
            matchedOn = null,
            confident = true,
            source = "s.pdf",
        )
        val summary = SpendSummary.from(
            listOf(
                item(3, 5, "1200.50", SpendCategory.TRAVEL),
                item(9, 5, "800.25", SpendCategory.TRAVEL),
                item(2, 6, "450.00", SpendCategory.MEALS),
            ),
        )

        assertEquals("2450.75", summary.total.toPlainString())
        assertEquals(SpendCategory.TRAVEL, summary.byCategory.first().category)
        assertEquals("2000.75", summary.byCategory.first().total.toPlainString())
        assertEquals(listOf(YearMonth.of(2026, 5), YearMonth.of(2026, 6)), summary.byMonth.map { it.month })
        assertEquals("450.00", summary.byMonth.last().total.toPlainString())
    }

    @Test
    fun `a bank transaction can be classified directly`() {
        val txn = BankTxn(
            date = LocalDate.of(2026, 6, 4),
            description = "UPI/INDIGO AIRLINES/BOOKING",
            debit = BigDecimal("6420.00"),
            source = StatementSource.BANK,
            rawLine = "04/06/2026 UPI/INDIGO AIRLINES/BOOKING 6,420.00 DR",
        )
        val hit = categorizer.classify(txn)
        assertEquals(SpendCategory.TRAVEL, hit.category)
        assertNotNull(hit.matchedOn)
    }

    @Test
    fun `every charted category has its own palette slot`() {
        // Slot 0 is the neutral grey. UNCATEGORISED uses it, and so does TRANSFER, which is
        // not a kind of spending and never reaches a chart at all — so the two sharing it
        // cannot put two slices the same colour. Everything that is drawn must be distinct.
        val charted = SpendCategory.entries.filter { it.paletteSlot != 0 }
        val slots = charted.map { it.paletteSlot }
        assertEquals(slots.size, slots.distinct().size, "two categories would be drawn the same colour")
        // The eight validated categorical hues, and there must not be a ninth.
        assertTrue(slots.all { it in 1..8 }, "palette only defines slots 1-8 for data")
        assertEquals(0, SpendCategory.UNCATEGORISED.paletteSlot)
        assertEquals(0, SpendCategory.TRANSFER.paletteSlot)
    }

    @Test
    fun `a rule the user wrote beats the built-in guess`() {
        // "amazon pay" reads as SOFTWARE via the "amazon web services" family of guesses.
        // Once the user has said what it is, that has to be the answer, and it must not come
        // back flagged as contested — being asked the same question every month is the thing
        // writing it down was supposed to end.
        val mine = SpendCategorizer.userRules("""{ "PERSONAL": ["amazon pay"] }""")
        val categorizer = SpendCategorizer(SpendCategorizer.defaults() + mine)

        val hit = categorizer.classify("AMAZON PAY IN E COMMERC BANGALORE")
        assertEquals(SpendCategory.PERSONAL, hit.category)
        assertEquals("amazon pay", hit.matchedOn)
        assertTrue(hit.confident, "the user already settled this one")
    }

    @Test
    fun `a broken rules file is ignored rather than stopping the app`() {
        // A stray comma in a list of shop names must not be the reason the server will not
        // start, and an unknown category name must not take the rest of the file with it.
        assertTrue(SpendCategorizer.userRules("{ not json at all ").isEmpty())
        assertEquals(
            listOf(SpendCategory.MEALS),
            SpendCategorizer.userRules("""{ "NOT_A_CATEGORY": ["x"], "MEALS": ["dosa"] }""")
                .map { it.category },
        )
    }
}
