package com.gohil.bookkeeper.core.spend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpendRulesConfigTest {

    @Test
    fun `the shipped defaults survive a round trip through the file format`() {
        val restored = SpendRulesConfig.fromJson(SpendRulesConfig.defaults().toJson())
        val categorizer = restored.toCategorizer()

        assertEquals(SpendCategory.MEALS, categorizer.classify("UPI/SWIGGY LTD").category)
        assertEquals(SpendCategory.OFFICE, categorizer.classify("SHARMA OFFICE SUPPLIES").category)
        assertEquals(
            InstrumentType.MUTUAL_FUND,
            restored.toInvestmentRules().match("SIP INSTALMENT PARAG PARIKH")?.type,
        )
    }

    @Test
    fun `priorities survive, so bank charges still outrank a merchant match`() {
        val restored = SpendRulesConfig.fromJson(SpendRulesConfig.defaults().toJson())
        assertEquals(
            SpendCategory.BANK_CHARGES,
            restored.toCategorizer().classify("ANNUAL FEE CREDIT CARD GST ON CHARGES").category,
        )
    }

    @Test
    fun `a hand-added keyword takes effect without touching any code`() {
        // This is the whole point of the file: the user's own vendor, added by them.
        val edited = SpendRulesConfig.fromJson(
            """
            {
              "categories": {
                "PROFESSIONAL": { "keywords": ["kothari and associates"], "priority": 3 }
              },
              "investments": {}
            }
            """.trimIndent(),
        )
        val hit = edited.toCategorizer().classify("NEFT DR KOTHARI AND ASSOCIATES")
        assertEquals(SpendCategory.PROFESSIONAL, hit.category)
        assertEquals("kothari and associates", hit.matchedOn)
    }

    @Test
    fun `keywords are matched case-insensitively however they were typed in the file`() {
        val edited = SpendRulesConfig.fromJson(
            """{"categories":{"TRAVEL":{"keywords":["Ola Cabs","MakeMyTrip"]}}}""",
        )
        assertEquals(SpendCategory.TRAVEL, edited.toCategorizer().classify("upi/ola cabs/9911").category)
        assertEquals(SpendCategory.TRAVEL, edited.toCategorizer().classify("MAKEMYTRIP BOOKING").category)
    }

    @Test
    fun `a misspelled category costs that one rule, not the whole file`() {
        // A hand-edited file will have typos. Losing every other rule to one of them would
        // be a far worse outcome than losing the line that was mistyped.
        val edited = SpendRulesConfig.fromJson(
            """
            {
              "categories": {
                "TRAVELL": { "keywords": ["indigo"] },
                "MEALS":   { "keywords": ["zomato"] }
              },
              "investments": { "CRYPTOO": ["binance"], "GOLD": ["sovereign gold bond"] }
            }
            """.trimIndent(),
        )
        val categorizer = edited.toCategorizer()
        assertEquals(SpendCategory.MEALS, categorizer.classify("ZOMATO ORDER").category)
        assertEquals(SpendCategory.UNCATEGORISED, categorizer.classify("INDIGO AIRLINES").category)

        assertEquals(InstrumentType.GOLD, edited.toInvestmentRules().match("SOVEREIGN GOLD BOND")?.type)
        assertNull(edited.toInvestmentRules().match("BINANCE TRANSFER"))
    }

    @Test
    fun `an empty file is reported so the caller can fall back rather than classify nothing`() {
        assertTrue(SpendRulesConfig.fromJson("""{"categories":{},"investments":{}}""").isEmpty())
        assertTrue(!SpendRulesConfig.defaults().isEmpty())
    }

    @Test
    fun `unknown keys are ignored so a future field cannot break an old build`() {
        val parsed = SpendRulesConfig.fromJson(
            """{"categories":{"MEALS":{"keywords":["zomato"],"colour":"red"}},"somethingNew":1}""",
        )
        assertEquals(SpendCategory.MEALS, parsed.toCategorizer().classify("zomato").category)
    }

    @Test
    fun `no category loses keywords on the way to the file`() {
        // The file is keyed by category name, so two rules sharing a category would silently
        // overwrite each other on write. That happened: adding a second OFFICE rule wiped the
        // office-supplies keywords, and only a round-trip test caught it.
        val byCategory = SpendCategorizer.defaults().groupBy { it.category }
        val duplicated = byCategory.filterValues { it.size > 1 }.keys
        assertTrue(
            duplicated.isEmpty(),
            "these categories have more than one editable rule and would lose keywords: $duplicated",
        )

        val written = SpendRulesConfig.defaults()
        for (rule in SpendCategorizer.defaults()) {
            val stored = written.categories[rule.category.name]?.keywords.orEmpty()
            assertEquals(
                rule.keywords.size, stored.size,
                "${rule.category} lost keywords when written to the file",
            )
        }
    }

    @Test
    fun `a hardcoded rule survives an empty or hostile file`() {
        // Cash withdrawal is a standing instruction, not a tunable keyword. Wiping the file
        // must not switch it off.
        val emptied = SpendRulesConfig.fromJson("""{"categories":{},"investments":{}}""")
        assertEquals(SpendCategory.OFFICE, emptied.toCategorizer().classify("ATM WDL 0234").category)
        assertEquals(SpendCategory.OFFICE, SpendCategorizer(emptyList()).classify("CASH WITHDRAWAL").category)
    }
}
