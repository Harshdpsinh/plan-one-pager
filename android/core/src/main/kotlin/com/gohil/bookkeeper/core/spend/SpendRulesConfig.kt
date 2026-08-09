package com.gohil.bookkeeper.core.spend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The spend and investment keyword lists, as an editable file.
 *
 * `SpendCategorizer` has always claimed a wrong guess is "corrected by editing a list rather
 * than retraining". That was only half true: the list lived in Kotlin, so correcting it meant
 * a rebuild. This makes the claim honest — the file sits next to the loan book, and a new
 * vendor keyword takes effect on the next run.
 *
 * The shape mirrors `CategoryRules` / config/categories.json, so someone who has edited one
 * already knows how to edit the other.
 *
 * Unknown category or instrument names are skipped rather than failing the load. A typo in a
 * hand-edited file should cost that one rule, not the whole run.
 */
@Serializable
data class SpendRulesConfig(
    /** Category name (SOFTWARE, TRAVEL, …) to its rule. */
    @SerialName("categories")
    val categories: Map<String, CategoryRule> = emptyMap(),
    /** Instrument name (MUTUAL_FUND, EQUITY, …) to its keywords. */
    @SerialName("investments")
    val investments: Map<String, List<String>> = emptyMap(),
) {

    @Serializable
    data class CategoryRule(
        val keywords: List<String> = emptyList(),
        /** Higher wins when several categories match the same line. */
        val priority: Int = 2,
    )

    fun toCategorizer(): SpendCategorizer = SpendCategorizer(
        categories.mapNotNull { (name, rule) ->
            val category = runCatching { SpendCategory.valueOf(name.uppercase()) }.getOrNull()
                ?: return@mapNotNull null
            SpendRule(category, keywords = rule.keywords.map { it.lowercase() }, priority = rule.priority)
        },
    )

    fun toInvestmentRules(): InvestmentRules = InvestmentRules(
        investments.mapNotNull { (name, keywords) ->
            val type = runCatching { InstrumentType.valueOf(name.uppercase()) }.getOrNull()
                ?: return@mapNotNull null
            type to keywords
        }.toMap(),
    )

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    /** True when the file has nothing usable in it, so the caller can fall back to defaults. */
    fun isEmpty(): Boolean = categories.isEmpty() && investments.isEmpty()

    companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            isLenient = true
        }

        fun fromJson(text: String): SpendRulesConfig = JSON.decodeFromString(serializer(), text)

        /** The shipped lists, in the same shape the file uses. */
        fun defaults(): SpendRulesConfig = SpendRulesConfig(
            categories = SpendCategorizer.defaults().associate { rule ->
                rule.category.name to CategoryRule(rule.keywords, rule.priority)
            },
            investments = InvestmentRules.defaults().entries.associate { (type, words) ->
                type.name to words
            },
        )
    }
}
