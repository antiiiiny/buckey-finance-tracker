package com.buckey.financetracker.data.category

import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.db.entities.CategoryRuleEntity

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
     * Returns the category for a given merchant string.
     * Queries the DAO for a matching rule via SQL LIKE.
     */
    suspend fun getCategory(database: AppDatabase, merchant: String): String {
        if (merchant.isBlank()) return DEFAULT_CATEGORY

        val match = database.categoryDao().findMatch(merchant.trim())
        return match?.category ?: DEFAULT_CATEGORY
    }
}