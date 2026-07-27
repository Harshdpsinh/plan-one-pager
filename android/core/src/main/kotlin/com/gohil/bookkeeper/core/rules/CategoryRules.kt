package com.gohil.bookkeeper.core.rules

import com.gohil.bookkeeper.core.model.Category
import com.gohil.bookkeeper.core.model.PaymentMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Editable classification rules, mirroring the desktop app's config/categories.json.
 *
 * Kept as data rather than code so a new vendor keyword or a newly confirmed RCM insurer is
 * a settings edit, not a rebuild.
 */
@Serializable
data class CategoryRules(
    @SerialName("personal_keywords")
    val personalKeywords: List<String> = emptyList(),
    @SerialName("business_keywords")
    val businessKeywords: List<String> = emptyList(),
    /**
     * Insurers confirmed to pay GST directly under the Reverse Charge Mechanism.
     *
     * Only ever added to after confirming with the insurer: marking a Direct insurer as RCM
     * misstates a GST return, so an unknown insurer must fall through to Direct rather than
     * being guessed at.
     */
    @SerialName("rcm_insurers")
    val rcmInsurers: List<String> = emptyList(),
    /** Keywords that mark a transaction as needing a human decision either way. */
    @SerialName("ambiguous_keywords")
    val ambiguousKeywords: List<String> = emptyList(),
    @SerialName("payment_methods")
    val paymentMethods: Map<String, List<String>> = emptyMap(),
) {

    private val personal by lazy { personalKeywords.map { it.lowercase() } }
    private val business by lazy { businessKeywords.map { it.lowercase() } }
    private val rcm by lazy { rcmInsurers.map { it.lowercase() } }
    private val ambiguous by lazy { ambiguousKeywords.map { it.lowercase() } }

    private val methodKeywords by lazy {
        paymentMethods.mapNotNull { (name, words) ->
            runCatching { PaymentMethod.valueOf(name.uppercase()) }.getOrNull()
                ?.let { it to words.map { w -> w.lowercase() } }
        }
    }

    fun categoryFor(text: String): Category? {
        val t = text.lowercase()
        // Personal is checked first: it is the narrower, explicitly enumerated list, and
        // everything unrecognised is meant to fall through to Business.
        if (personal.any { it in t }) return Category.PERSONAL
        if (business.any { it in t }) return Category.BUSINESS
        return null
    }

    fun isAmbiguous(text: String): Boolean {
        val t = text.lowercase()
        return ambiguous.any { it in t }
    }

    fun isRcmInsurer(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val t = name.lowercase()
        return rcm.any { it in t }
    }

    fun paymentMethodFor(description: String): PaymentMethod {
        val t = description.lowercase()
        // Longest keyword first so "credit card" is not pre-empted by a bare "card".
        return methodKeywords
            .flatMap { (method, words) -> words.map { method to it } }
            .sortedByDescending { it.second.length }
            .firstOrNull { it.second in t }
            ?.first
            ?: PaymentMethod.UNKNOWN
    }

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            isLenient = true
        }

        fun fromJson(text: String): CategoryRules = JSON.decodeFromString(serializer(), text)

        /**
         * Shipped defaults. Personal is enumerated because it is the exception; anything not
         * listed defaults to Business, matching the documented rule.
         */
        fun defaults(): CategoryRules = CategoryRules(
            personalKeywords = listOf(
                // Food delivery and restaurants
                "swiggy", "zomato", "dominos", "pizza hut", "mcdonald", "kfc", "starbucks",
                "restaurant", "cafe", "bakery", "hotel food",
                // Groceries
                "bigbasket", "blinkit", "zepto", "dmart", "d-mart", "reliance fresh",
                "more supermarket", "grocery", "kirana", "vegetable", "supermarket",
                // Pharmacy and health
                "pharmeasy", "netmeds", "apollo pharmacy", "medplus", "pharmacy", "chemist",
                "medical store",
                // Shopping and streaming
                "myntra", "ajio", "nykaa", "netflix", "spotify", "hotstar", "prime video",
                "sony liv", "zee5", "youtube premium", "bookmyshow", "pvr", "inox",
                // Education and household
                "school fee", "tuition", "college fee", "hostel",
            ),
            businessKeywords = listOf(
                "office", "stationery", "printing", "courier", "internet", "broadband",
                "electricity", "professional fee", "audit fee", "consultancy", "software",
                "subscription business", "gst", "insurance premium",
                // Any levied charge or fee is a business cost. Listed explicitly because
                // "ATM WDL CHARGES" would otherwise be held back as ambiguous on the bare
                // word "atm", when a levied charge is never a personal expense.
                "charge", "service fee", "processing fee", "annual fee", "late fee",
                "advertisement", "marketing", "travel business", "petrol", "fuel", "toll",
            ),
            // Confirmed so far. Do not extend without checking with the insurer.
            rcmInsurers = listOf("star health"),
            ambiguousKeywords = listOf(
                "amazon", "flipkart", "paytm", "phonepe", "atm", "cash withdrawal",
                "self transfer", "upi transfer",
            ),
            paymentMethods = mapOf(
                "CREDIT_CARD" to listOf("credit card", "cc payment", "card no", "visa", "mastercard", "rupay"),
                "UPI" to listOf("upi", "gpay", "google pay", "phonepe", "paytm", "bhim", "@ok", "@ybl", "@paytm"),
                "BANK_TRANSFER" to listOf("neft", "rtgs", "imps", "net banking", "netbanking", "transfer", "ach", "nach"),
                "CHEQUE" to listOf("cheque", "chq", "chk"),
                "CASH" to listOf("cash dep", "cash wdl", "atm", "by cash"),
            ),
        )
    }
}
