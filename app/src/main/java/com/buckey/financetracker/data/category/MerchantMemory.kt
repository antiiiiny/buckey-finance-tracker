package com.buckey.financetracker.data.category

import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.db.entities.CategoryRuleEntity

/**
 * Writes user category preferences back to the category_rules table.
 *
 * When a user manually recategorizes a transaction, this saves the
 * merchant → category mapping so future transactions from the same
 * merchant are auto-categorized (Rule 7).
 */
object MerchantMemory {

    /**
     * Saves or updates the category for a given merchant.
     * If a rule already exists for this merchant, it's overwritten.
     * If not, a new rule is created.
     */
    suspend fun rememberCategory(database: AppDatabase, merchant: String, category: String) {
        if (merchant.isBlank()) return

        val rule = CategoryRuleEntity(
            merchantPattern = merchant.trim().lowercase(),
            category = category.trim()
        )
        database.categoryDao().insertOrUpdate(rule)
    }
}