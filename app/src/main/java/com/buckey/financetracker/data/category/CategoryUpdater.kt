package com.buckey.financetracker.data.category

import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.db.entities.CategorizedBy
import com.buckey.financetracker.data.db.entities.TransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Applies a manual category change, at one of three scopes the user picks.
 *
 * - [Scope.THIS_ONLY]: this row only. No rule is written, so nothing else
 *   changes now or later. This is the one exception to rule 7, at the user's
 *   request (2026-10-24): some payments to a merchant are one-offs.
 * - [Scope.THIS_AND_LATER]: writes the merchant rule (rule 7), so future SMS
 *   match it, and re-labels the existing rows from this one onwards.
 * - [Scope.ALL]: writes the rule and re-labels every existing row it governs.
 *
 * A row counts as "from this merchant" exactly when the new rule is the one
 * [CategoryDao.findMatch] picks for it: its merchant contains the pattern, and no
 * longer, more specific rule claims it. Bulk edits therefore never disagree with
 * what tier-1 matching would do for the same merchant string.
 *
 * Credits only take Income, Transfer or Self Transfer; anything else becomes
 * Transfer for them (see [CategoryMatcher.forType]), so `abel paul → Food`
 * labels payments to Abel, not money from Abel.
 *
 * Generic merchants ("UPI") are always [Scope.THIS_ONLY]. One debit's payee says
 * nothing about another's (see [GenericMerchant]).
 */
object CategoryUpdater {

    enum class Scope { THIS_ONLY, THIS_AND_LATER, ALL }

    /** Number of rows whose category was set, including [transaction] itself. */
    suspend fun update(
        database: AppDatabase,
        transaction: TransactionEntity,
        category: String,
        scope: Scope
    ): Int = withContext(Dispatchers.IO) {
        val dao = database.transactionDao()
        val effective = effectiveScope(transaction, scope)
        if (effective == Scope.THIS_ONLY) {
            dao.updateCategory(transaction.id, category, CategorizedBy.MANUAL)
            return@withContext 1
        }

        MerchantMemory.rememberCategory(database, transaction.merchant, category)
        val pattern = patternFor(transaction.merchant)
        val since = if (effective == Scope.ALL) Long.MIN_VALUE else transaction.date
        val ids = (governedIds(database, pattern, since) + transaction.id).distinct()
        dao.updateCategoryForIds(
            ids, category, CategoryMatcher.CREDIT_CATEGORIES, CategoryMatcher.CREDIT_FALLBACK,
            by = CategorizedBy.MANUAL, protectManual = false
        )
    }

    /**
     * Re-labels every existing row the rule on [pattern] governs. The rule must
     * already be saved. Used by the merchant map import, which writes the user's
     * rules in bulk, so changed rows are marked [CategorizedBy.DEVELOPMENT]; rows
     * the user categorized by hand, and rows already right, are skipped. Returns
     * the number of rows changed.
     */
    suspend fun applyRuleToExisting(database: AppDatabase, pattern: String, category: String): Int =
        withContext(Dispatchers.IO) {
            val ids = governedIds(database, pattern, Long.MIN_VALUE)
            if (ids.isEmpty()) 0 else database.transactionDao().updateCategoryForIds(
                ids, category, CategoryMatcher.CREDIT_CATEGORIES, CategoryMatcher.CREDIT_FALLBACK,
                by = CategorizedBy.DEVELOPMENT, protectManual = true
            )
        }

    /** Rows dated at or after [sinceMillis] whose winning rule is [pattern]. */
    private suspend fun governedIds(database: AppDatabase, pattern: String, sinceMillis: Long): List<Long> {
        val candidates = database.transactionDao().getByMerchantLikeSince(pattern, sinceMillis)
        val winners = candidates.map { it.merchant }.distinct().associateWith { merchant ->
            database.categoryDao().findMatch(merchant)?.merchantPattern
        }
        return selectGoverned(candidates, winners, pattern)
    }

    /** Candidates whose winning rule (merchant → pattern) is [pattern]. Pure. */
    fun selectGoverned(
        candidates: List<TransactionEntity>,
        winningPattern: Map<String, String?>,
        pattern: String
    ): List<Long> = candidates.filter { winningPattern[it.merchant] == pattern }.map { it.id }.distinct()

    /** What [update] will actually do for this row; the UI disables the rest. */
    fun effectiveScope(transaction: TransactionEntity, requested: Scope): Scope =
        if (GenericMerchant.isGeneric(transaction.merchant)) Scope.THIS_ONLY else requested

    /** Same normalization as [MerchantMemory.rememberCategory]. */
    fun patternFor(merchant: String): String = merchant.trim().lowercase()

    /**
     * Rows to re-label: [transaction] itself, plus every candidate whose winning
     * rule (per [winningPattern], merchant → pattern) is [pattern]. Pure, so the
     * selection is unit tested without a database.
     */
    fun targetIds(
        transaction: TransactionEntity,
        candidates: List<TransactionEntity>,
        winningPattern: Map<String, String?>,
        pattern: String
    ): List<Long> = (selectGoverned(candidates, winningPattern, pattern) + transaction.id).distinct()
}
