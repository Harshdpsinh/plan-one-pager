package com.gohil.bookkeeper.core.spend

import com.gohil.bookkeeper.core.model.BankTxn
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    /**
     * Premiums paid: motor, health, term, general. A real expense, and for this business a
     * large one.
     *
     * It has no hue of its own. The categorical palette is a validated set of exactly eight,
     * and a ninth is never a generated colour — so this folds into the chart's neutral
     * "Other" slice and carries its own line in the legend and the table, where the figure a
     * chartered accountant needs actually lives.
     */
    INSURANCE("Insurance premiums", 0),

    /**
     * Bought an asset rather than paid for something consumed. Never charted as spending —
     * the money changed shape, not owner — and reported under investments instead.
     *
     * Most investments are recognised before categorisation ever runs, by [InvestmentRules].
     * This exists so the user can say so about one that was not.
     */
    INVESTMENT("Investments", 0),

    /**
     * Money that moved without being spent: a credit-card bill paid from the bank account,
     * a transfer between own accounts, cash taken out.
     *
     * Not a spending category, and never charted as one. A card bill is the single largest
     * outflow on a bank statement and it is not an expense — the expenses are the card's own
     * rows, which arrive from the card statement. Counting both charged this household
     * ₹1,44,500 it never spent. Shares the neutral palette slot because it never reaches a
     * chart.
     */
    TRANSFER("Transfers & withdrawals", 0),

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
    /**
     * True for a rule the user wrote themselves.
     *
     * It outranks the built-in guesses, and a merchant it names is never flagged as
     * contested — the user has already answered the question the flag would be asking.
     */
    val user: Boolean = false,
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
class SpendCategorizer(private val rules: List<SpendRule> = defaults()) {

    fun classify(merchant: String, narration: String = ""): Classification {
        val text = listOf(merchant, narration).filter { it.isNotBlank() }.joinToString(" ")
        if (text.isBlank()) return Classification(SpendCategory.UNCATEGORISED, null, false)

        val hits = rules.mapNotNull { rule -> rule.matches(text)?.let { rule to it } }
        if (hits.isEmpty()) return Classification(SpendCategory.UNCATEGORISED, null, false)

        val best = hits.maxWith(
            compareBy({ it.first.priority }, { it.second.length }),
        )
        // Two rules of different categories both firing means the merchant is genuinely
        // ambiguous; the category is still assigned but flagged so the UI can mark it.
        // Unless the winner is a rule the user wrote: they have already settled it, and
        // asking again every month is the opposite of what writing it down was for.
        val contested = hits.map { it.first.category }.distinct().size > 1
        return Classification(best.first.category, best.second, confident = !contested || best.first.user)
    }

    fun classify(txn: BankTxn): Classification = classify(txn.description, txn.rawLine)

    companion object {

        /**
         * Above every built-in rule, including BANK_CHARGES. A merchant the user has named
         * is not a guess to be outvoted.
         */
        private const val USER_PRIORITY = 10

        /**
         * Rules from the user's own file, which sits next to the password store and is
         * edited by hand.
         *
         * The format is deliberately the least there is — a category name, then the words
         * that mean it:
         *
         * ```json
         * { "MEALS": ["taco bell", "biriyani"], "TRAVEL": ["chitra transport"] }
         * ```
         *
         * Anything unparseable is ignored rather than thrown, because the alternative is an
         * app that will not start because of a stray comma in a list of shop names. An
         * unknown category name is skipped for the same reason; the rest of the file still
         * applies.
         */
        fun userRules(json: String): List<SpendRule> = runCatching {
            Json.parseToJsonElement(json).jsonObject.mapNotNull { (name, value) ->
                val category = SpendCategory.entries
                    .firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                    ?: return@mapNotNull null
                val keywords = value.jsonArray
                    .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.lowercase() }
                    .filter { it.isNotBlank() }
                if (keywords.isEmpty()) null
                else SpendRule(category, keywords, priority = USER_PRIORITY, user = true)
            }
        }.getOrDefault(emptyList())

        /**
         * Starting rules, weighted to Indian merchants because that is what the statements
         * contain.
         *
         * The first month of real data did show gaps, as expected, and the merchants it
         * named have been folded in — they are marked below. Everything unmarked was written
         * before any statement had been read and is still a guess.
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
                    // from July's statements
                    "amazon prime", "prime recu",
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
                    // from July's statements
                    "petroleum", "transport", "travels", "fuel sur",
                ),
                priority = 2,
            ),
            SpendRule(
                SpendCategory.MEALS,
                keywords = listOf(
                    "swiggy", "zomato", "dominos", "pizza hut", "mcdonald", "kfc", "burger king",
                    "starbucks", "cafe coffee day", "ccd", "barista", "chaayos", "restaurant",
                    "cafe", "bakery", "eatery", "dhaba", "catering", "food", "dineout", "eazydiner",
                    // from July's statements
                    "pizza", "taco bell", "biriyani", "restaur",
                ),
                priority = 2,
            ),
            SpendRule(
                SpendCategory.OFFICE,
                keywords = listOf(
                    "stationery", "staples", "office depot", "printer", "cartridge", "toner",
                    "xerox", "printing", "courier", "dtdc", "bluedart", "blue dart", "delhivery",
                    "fedex", "dhl", "india post", "speed post", "paper", "furniture",
                    // "SHARMA OFFICE SUPPLIES PVT LTD" was uncategorised: the rule had
                    // "stationery" and "office depot" but not the plainest phrase of all.
                    "office supplies", "office supply",
                ),
                priority = 2,
            ),
            SpendRule(
                SpendCategory.UTILITIES,
                keywords = listOf(
                    "airtel", "jio", "vodafone", "vi ", "bsnl", "act fibernet", "hathway",
                    "broadband", "electricity", "torrent power", "adani electricity", "mseb",
                    "gujarat gas", "water bill", "internet", "telephone", "mobile recharge",
                    // from July's statements — SBI's card routes utility bills through
                    // Bharat Connect, so the biller's own name never appears.
                    "bharat connect", "mobpostpaid", "postpaid",
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
                // Above the generic rules: "ANNUAL FEE" on a card statement is a bank charge
                // even when the merchant string also mentions the card network.
                priority = 3,
            ),
            SpendRule(
                SpendCategory.INSURANCE,
                keywords = listOf(
                    "insurance premium", "policy premium", "godigit", "go digit", "hdfc ergo",
                    "icici lombard", "bajaj allianz", "tata aig", "new india assurance",
                    "united india", "oriental insurance", "star health", "niva bupa",
                    "care health", "reliance general", "acko", "motor insurance",
                    "health insurance", "term plan", "renewal premium",
                ),
                // Above the generic rules: an insurer's name is specific, and several of
                // them read as something else in passing ("care", "star").
                priority = 3,
            ),
            SpendRule(
                SpendCategory.PERSONAL,
                keywords = listOf(
                    "bigbasket", "blinkit", "zepto", "dmart", "d-mart", "reliance fresh", "grocery",
                    "kirana", "supermarket", "pharmeasy", "netmeds", "apollo pharmacy", "medplus",
                    "pharmacy", "chemist", "myntra", "ajio", "nykaa", "netflix", "spotify",
                    "hotstar", "prime video", "sony liv", "zee5", "bookmyshow", "pvr", "inox",
                    "school fee", "tuition", "hospital", "clinic",
                    // from July's statements. ASSPL is how Amazon Seller Services settles,
                    // and it is the merchant string the card actually prints.
                    "asspl", "zudio", "trent", "metro brands", "snitch", "westside",
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
