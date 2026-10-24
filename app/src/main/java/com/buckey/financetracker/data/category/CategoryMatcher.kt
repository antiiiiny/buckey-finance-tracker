package com.buckey.financetracker.data.category

import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.db.entities.CategoryRuleEntity
import com.buckey.financetracker.data.db.entities.TransactionType

/**
 * Matches a transaction merchant string against the category rules table.
 *
 * Uses a case-insensitive substring match: if the merchant contains
 * any rule's merchantPattern, the rule's category is applied.
 *
 * If no rule matches, returns "Uncategorized".
 */
object CategoryMatcher {

    private const val DEFAULT_CATEGORY = "Uncategorized"

    /**
     * The only categories a credit may carry (user's rule, 2026-10-24): money in
     * is income or a transfer. Self Transfer counts as a transfer.
     */
    val CREDIT_CATEGORIES: List<String> = listOf("Income", "Transfer", "Self Transfer")

    /**
     * What any other credit becomes, including one with no rule. Income only
     * ever comes from an explicit rule.
     */
    const val CREDIT_FALLBACK = "Transfer"

    /**
     * Narrows [category] to what a transaction of [type] may carry. A rule like
     * `abel paul → Food` labels the debits to that person; money from them is
     * still a Transfer.
     */
    fun forType(category: String, type: TransactionType): String =
        if (type == TransactionType.CREDIT && category !in CREDIT_CATEGORIES) CREDIT_FALLBACK else category

    /** [getCategory], narrowed by [forType]. Use this wherever the type is known. */
    suspend fun getCategory(database: AppDatabase, merchant: String, type: TransactionType): String =
        forType(getCategory(database, merchant), type)

    /**
     * Returns the category for a given merchant string.
     * Queries the DAO for a matching rule via SQL LIKE.
     */
    suspend fun getCategory(database: AppDatabase, merchant: String): String {
        if (merchant.isBlank()) return DEFAULT_CATEGORY

        val match = database.categoryDao().findMatch(merchant.trim())
        return match?.category ?: DEFAULT_CATEGORY
    }
}