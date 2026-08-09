package com.gohil.bookkeeper.core.spend

import com.gohil.bookkeeper.core.model.BankTxn
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * Spend categories for the analysis dashboard.
 *
 * Deliberately separate from the GST register's Personal/Business split. That one decides
 * what a chartered accountant files; this one decides what a chart says. Conflating them
 * would mean a chart tweak could change a tax return.
 */
enum class SpendCategory(val label: String, val paletteSlot: Int) {
    SOFTWARE("Software & Subscriptions", 1),
    TRAVEL("Travel", 2),
    MEALS("Meals & Entertainment", 3),
    OFFICE("Office Supplies", 4),
    UTILITIES("Utilities & Telecom", 5),
    PROFESSIONAL("Professional Fees", 6),
    BANK_CHARGES("Bank Charges", 7),
    PERSONAL("Personal", 8),
    UNCATEGORISED("Uncategorised", 0),
}

/**
 * A single rule. Kept as data so the whole rule set can be edited without a rebuild, and so
 * the reason for a classification can be shown back to the user.
 */
data class SpendRule(
    val category: SpendCategory,
    /** Matched case-insensitively against the merchant/narration. */
    val keywords: List<String> = emptyList(),
    /** Applied to the same text. Use for structured narration like "UPI/<merchant>/<ref>". */
    val patterns: List<Regex> = emptyList(),
    /** Higher wins when several rules match. */
    val priority: Int = 0,
) {
    fun matches(text: String): String? {
        val lower = text.lowercase()
        // The longest keyword, not the first: a rule listing both "google" and
        // "google workspace" must report the specific one, whatever order they were typed in.
        keywords.filter { it in lower }.maxByOrNull { it.length }?.let { return it }
        patterns.firstOrNull { it.containsMatchIn(text) }?.let { return it.pattern }
        return null
    }
}

data class Classification(
    val category: SpendCategory,
    /** Which keyword or pattern fired. Shown in the UI so a wrong guess is diagnosable. */
    val matchedOn: String?,
    val confident: Boolean,
)

/**
 * Rule-based auto-categorisation.
 *
 * Rules rather than a model, for three reasons that matter more here than accuracy on a
 * benchmark: the result is explainable (the UI can say *why* something was called Travel),
 * it is correctable by editing a list rather than retraining, and it runs offline with no
 * model to ship. A statistical classifier would also need labelled history this user does
 * not have yet.
 *
 * The longest match wins rather than the first, so "credit card" beats a bare "card" and
 * "google workspace" beats "google".
 */
class SpendCategorizer(editable: List<SpendRule> = defaults()) {

    /**
     * The hardcoded rules always apply, on top of whatever the editable file says.
     *
     * They are kept out of [defaults] — and therefore out of the file the user edits —
     * because they are standing accounting decisions, not merchant keywords to tune. Emptying
     * the file cannot switch them off.
     */
    private val rules: List<SpendRule> = hardcoded() + editable

    fun classify(merchant: String, narration: String = ""): Classification {
        val text = listOf(merchant, narration).filter { it.isNotBlank() }.joinToString(" ")
        if (text.isBlank()) return Classification(SpendCategory.UNCATEGORISED, null, false)

        val hits = rules.mapNotNull { rule -> rule.matches(text)?.let { rule to it } }
        if (hits.isEmpty()) return Classification(SpendCategory.UNCATEGORISED, null, false)

        val best = hits.maxWith(
            compareBy({ it.first.priority }, { it.second.length }),
        )
        // Two rules of different categories both firing means the merchant is genuinely
        // ambiguous; the category is still assigned but flagged so the UI can ask about it.
        //
        // Only rules at the winning priority count. A deliberate ordering — "ATM WDL CHARGE"
        // is a bank charge, not the cash withdrawal it also looks like — is an explicit
        // resolution, not an ambiguity, and stopping to ask about it every month would make
        // the human-in-the-loop prompt worthless through noise.
        val topPriority = hits.maxOf { it.first.priority }
        val contested = hits.filter { it.first.priority == topPriority }
            .map { it.first.category }.distinct().size > 1
        return Classification(best.first.category, best.second, confident = !contested)
    }

    fun classify(txn: BankTxn): Classification = classify(txn.description, txn.rawLine)

    companion object {
        /**
         * Rules that are not up for editing.
         *
         * Cash off the bank account is booked to Office Expenses, by explicit instruction.
         * It sits above the ordinary rules so a withdrawal is never re-read as something
         * else, and below BANK_CHARGES so the withdrawal *fee* stays a bank charge.
         *
         * Deliberately NOT part of [defaults]: that list is written out as the editable file,
         * which is keyed by category, so a second OFFICE rule there would silently overwrite
         * the office-supplies keywords on the way to disk.
         */
        fun hardcoded(): List<SpendRule> = listOf(
            SpendRule(
                SpendCategory.OFFICE,
                keywords = listOf(
                    "cash withdrawal", "cash wdl", "atm wdl", "atm withdrawal", "atm cash",
                    "self withdrawal", "cash withdrawn", "by cash withdrawal", "atm-cash",
                    "eaw-", "nwd-", "atw-",
                ),
                priority = 4,
            ),
        )

        /**
         * Starting rules, weighted to Indian merchants because that is what the statements
         * contain. Meant to be edited: the first month of real data will show gaps.
         */
        fun defaults(): List<SpendRule> = listOf(
            SpendRule(
                SpendCategory.SOFTWARE,
                keywords = listOf(
                    "aws", "amazon web services", "google workspace", "google cloud", "microsoft",
                    "office 365", "microsoft 365", "adobe", "atlassian", "github", "gitlab",
                    "slack", "zoom", "notion", "figma", "dropbox", "canva", "openai", "anthropic",
                    "godaddy", "hostinger", "digitalocean", "vercel", "netlify", "jetbrains",
                    "tally solutions", "zoho", "freshworks", "subscription",
                ),
                priority = 2,
            ),
            SpendRule(
                SpendCategory.TRAVEL,
                keywords = listOf(
                    "irctc", "indigo", "air india", "vistara", "spicejet", "akasa", "makemytrip",
                    "goibibo", "cleartrip", "yatra", "ixigo", "uber", "ola", "rapido", "redbus",
                    "petrol", "fuel", "hpcl", "bpcl", "indian oil", "iocl", "toll", "fastag",
                    "parking", "railway", "airlines", "airport", "oyo", "taxi",
                ),
                priority = 2,
            ),
            SpendRule(
                SpendCategory.MEALS,
                keywords = listOf(
                    "swiggy", "zomato", "dominos", "pizza hut", "mcdonald", "kfc", "burger king",
                    "starbucks", "cafe coffee day", "ccd", "barista", "chaayos", "restaurant",
                    "cafe", "bakery", "eatery", "dhaba", "catering", "food", "dineout", "eazydiner",
                ),
                priority = 2,
            ),
            SpendRule(
                SpendCategory.OFFICE,
                keywords = listOf(
                    "stationery", "staples", "office depot", "printer", "cartridge", "toner",
                    "xerox", "printing", "courier", "dtdc", "bluedart", "blue dart", "delhivery",
                    "fedex", "dhl", "india post", "speed post", "paper", "furniture",
                    // Added after a live run left a vendor called "SHARMA OFFICE SUPPLIES
                    // PVT LTD" uncategorised: the list had "office depot" but not the plain
                    // words an Indian supplier actually trades under.
                    "office supplies", "office supply", "stationers", "computer supplies",
                ),
                priority = 2,
            ),
            SpendRule(
                SpendCategory.UTILITIES,
                keywords = listOf(
                    "airtel", "jio", "vodafone", "vi ", "bsnl", "act fibernet", "hathway",
                    "broadband", "electricity", "torrent power", "adani electricity", "mseb",
                    "gujarat gas", "water bill", "internet", "telephone", "mobile recharge",
                ),
                priority = 2,
            ),
            SpendRule(
                SpendCategory.PROFESSIONAL,
                keywords = listOf(
                    "chartered accountant", "audit fee", "professional fee", "consultancy",
                    "consulting", "legal fee", "advocate", "notary", "registrar", "gst filing",
                    "roc filing", "licence fee", "license fee",
                ),
                priority = 2,
            ),
            SpendRule(
                SpendCategory.BANK_CHARGES,
                keywords = listOf(
                    "bank charge", "service charge", "processing fee", "annual fee", "late fee",
                    "penalty", "sms charge", "amc charge", "atm charge", "atm wdl charge",
                    "cheque return", "gst on charges", "interest charge", "finance charge",
                ),
                // Above the generic rules AND above the cash-withdrawal rule below: "ATM WDL
                // CHARGE INCL GST" is the bank's fee for the withdrawal, not the withdrawal.
                // The fee is a bank charge; the cash that came out is an office expense.
                priority = 5,
            ),
            SpendRule(
                SpendCategory.PERSONAL,
                keywords = listOf(
                    "bigbasket", "blinkit", "zepto", "dmart", "d-mart", "reliance fresh", "grocery",
                    "kirana", "supermarket", "pharmeasy", "netmeds", "apollo pharmacy", "medplus",
                    "pharmacy", "chemist", "myntra", "ajio", "nykaa", "netflix", "spotify",
                    "hotstar", "prime video", "sony liv", "zee5", "bookmyshow", "pvr", "inox",
                    "school fee", "tuition", "hospital", "clinic",
                ),
                priority = 2,
            ),
        )
    }
}

/** One classified outflow, ready to be charted. */
data class SpendItem(
    val date: LocalDate,
    val merchant: String,
    val amount: BigDecimal,
    val category: SpendCategory,
    val matchedOn: String?,
    val confident: Boolean,
    val source: String,
)

data class CategoryTotal(val category: SpendCategory, val total: BigDecimal, val count: Int)

data class MonthTotal(val month: YearMonth, val total: BigDecimal, val count: Int)

/** The aggregates the dashboard draws. Computed once so the charts and the table agree. */
data class SpendSummary(
    val items: List<SpendItem>,
    val byCategory: List<CategoryTotal>,
    val byMonth: List<MonthTotal>,
    val total: BigDecimal,
    val needsReview: List<SpendItem>,
) {
    companion object {
        fun from(items: List<SpendItem>): SpendSummary {
            val byCategory = items.groupBy { it.category }
                .map { (cat, rows) ->
                    CategoryTotal(cat, rows.fold(BigDecimal.ZERO) { a, r -> a + r.amount }, rows.size)
                }
                .sortedByDescending { it.total }

            val byMonth = items.groupBy { YearMonth.from(it.date) }
                .map { (month, rows) ->
                    MonthTotal(month, rows.fold(BigDecimal.ZERO) { a, r -> a + r.amount }, rows.size)
                }
                .sortedBy { it.month }

            return SpendSummary(
                items = items.sortedByDescending { it.date },
                byCategory = byCategory,
                byMonth = byMonth,
                total = items.fold(BigDecimal.ZERO) { a, r -> a + r.amount },
                needsReview = items.filter {
                    it.category == SpendCategory.UNCATEGORISED || !it.confident
                },
            )
        }
    }
}
