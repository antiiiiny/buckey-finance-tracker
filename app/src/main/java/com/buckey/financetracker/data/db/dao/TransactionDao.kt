package com.buckey.financetracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.buckey.financetracker.data.db.entities.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(transactions: List<TransactionEntity>): List<Long>

    @Query("SELECT * FROM transactions ORDER BY date DESC")
    fun getAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE date BETWEEN :startMillis AND :endMillis ORDER BY date DESC")
    fun getTransactionsByDateRange(startMillis: Long, endMillis: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE transaction_id = :transactionId LIMIT 1")
    suspend fun getByTransactionId(transactionId: String): TransactionEntity?

    // Every write to `category` below also sets `category_dirty`, so the sync
    // rewrites the Sheet's Category cells for rows already in it (rule 10), and
    // records who made the change in `categorized_by`.

    /** One row, by the user or by an import's fill pass ([by]). */
    @Query(
        "UPDATE transactions SET category = :category, categorized_by = :by, " +
            "category_dirty = 1 WHERE id = :id"
    )
    suspend fun updateCategory(id: Long, category: String, by: String)

    // The bulk writes below narrow a credit to [creditCategories], falling back
    // to [creditFallback] — CategoryMatcher.forType in SQL, so one statement
    // handles a rule that covers debits and credits alike.
    //
    // With [protectManual] (imports), a row the user categorized by hand is left
    // alone, and so is a row already holding the target category, so a re-import
    // relabels only what actually changes and "auto" keeps meaning "the rule got
    // it right on arrival". Without it (the user's own bulk edit), every row is
    // set and marked as the user's.

    /** Sets the category of the row(s) with this reference; for import overrides. */
    @Query(
        "UPDATE transactions SET category = CASE WHEN type = 'CREDIT' AND :category " +
            "NOT IN (:creditCategories) THEN :creditFallback ELSE :category END, " +
            "categorized_by = :by, category_dirty = 1 WHERE raw_ref = :rawRef " +
            "AND (:protectManual = 0 OR (categorized_by <> 'manual' AND category IS NOT " +
            "CASE WHEN type = 'CREDIT' AND :category NOT IN (:creditCategories) " +
            "THEN :creditFallback ELSE :category END))"
    )
    suspend fun updateCategoryByRawRef(
        rawRef: String,
        category: String,
        creditCategories: List<String>,
        creditFallback: String,
        by: String,
        protectManual: Boolean
    ): Int

    /** Bulk form of [updateCategory], for "this and later" / "every" edits and imports. */
    @Query(
        "UPDATE transactions SET category = CASE WHEN type = 'CREDIT' AND :category " +
            "NOT IN (:creditCategories) THEN :creditFallback ELSE :category END, " +
            "categorized_by = :by, category_dirty = 1 WHERE id IN (:ids) " +
            "AND (:protectManual = 0 OR (categorized_by <> 'manual' AND category IS NOT " +
            "CASE WHEN type = 'CREDIT' AND :category NOT IN (:creditCategories) " +
            "THEN :creditFallback ELSE :category END))"
    )
    suspend fun updateCategoryForIds(
        ids: List<Long>,
        category: String,
        creditCategories: List<String>,
        creditFallback: String,
        by: String,
        protectManual: Boolean
    ): Int

    /**
     * Moves every credit outside [creditCategories] (or with none) to
     * [creditFallback]. Idempotent; CategoryRepair runs it at launch so the
     * credit rule holds whatever path wrote the row. That's the credit rule
     * applying itself, so it counts as automatic.
     */
    @Query(
        "UPDATE transactions SET category = :creditFallback, categorized_by = 'auto', " +
            "category_dirty = 1 " +
            "WHERE type = 'CREDIT' AND (category IS NULL OR category NOT IN (:creditCategories))"
    )
    suspend fun normalizeCreditCategories(creditCategories: List<String>, creditFallback: String): Int

    /**
     * Rows whose merchant contains [pattern], dated at or after [sinceMillis].
     * Same `LIKE '%pattern%'` semantics as CategoryDao.findMatch, so the caller
     * sees exactly the rows a rule on [pattern] could govern.
     */
    @Query(
        "SELECT * FROM transactions " +
            "WHERE merchant LIKE '%' || :pattern || '%' AND date >= :sinceMillis"
    )
    suspend fun getByMerchantLikeSince(pattern: String, sinceMillis: Long): List<TransactionEntity>

    /**
     * Repairs a row's merchant from an external source, keyed by the stable
     * transaction hash rather than the local row id.
     *
     * Used by the Stage 7 merchant map import: the bank statement carries the
     * payee that the debit SMS omits. Returns the number of rows updated, so the
     * caller can report hashes that no longer exist on this device.
     */
    @Query(
        "UPDATE transactions SET merchant = :merchant, category = :category, " +
            "categorized_by = :by, category_dirty = 1 WHERE transaction_id = :transactionId"
    )
    suspend fun updateMerchantAndCategory(
        transactionId: String,
        merchant: String,
        category: String,
        by: String
    ): Int

    /**
     * Moves rows with one of [merchants] (uppercase) from category [from] to [to].
     * Returns the number of rows changed. Used by CategoryRepair to undo labels a
     * `upi → Transfer` rule stamped onto every debit; a development-time repair.
     */
    @Query(
        "UPDATE transactions SET category = :to, categorized_by = 'development', " +
            "category_dirty = 1 WHERE category = :from AND UPPER(TRIM(merchant)) IN (:merchants)"
    )
    suspend fun recategorizeMerchants(merchants: List<String>, from: String, to: String): Int

    @Query("SELECT * FROM transactions WHERE raw_ref = :rawRef LIMIT 1")
    suspend fun getByRawRef(rawRef: String): TransactionEntity?

    @Query("UPDATE transactions SET synced_to_sheets = 1 WHERE id = :id")
    suspend fun markAsSynced(id: Long)

    @Query("SELECT * FROM transactions WHERE synced_to_sheets = 0 ORDER BY date DESC")
    suspend fun getUnsyncedTransactions(): List<TransactionEntity>

    /** Every row in Sheet order (oldest first), for a full Sheet rebuild. */
    @Query("SELECT * FROM transactions ORDER BY date ASC, id ASC")
    suspend fun getAllOldestFirst(): List<TransactionEntity>

    /**
     * After a rebuild wrote exactly these rows: synced, and their Sheet cells
     * current. Callers chunk [ids] below SQLite's 999-variable limit.
     */
    @Query("UPDATE transactions SET synced_to_sheets = 1, category_dirty = 0 WHERE id IN (:ids)")
    suspend fun markRebuilt(ids: List<Long>)

    /** Rows still carrying the v4 migration's default, with a real category. */
    @Query(
        "SELECT * FROM transactions WHERE categorized_by = 'auto' " +
            "AND category IS NOT NULL AND category <> 'Uncategorized'"
    )
    suspend fun getCategorizedAuto(): List<TransactionEntity>

    /** Relabels who categorized a row, without touching the category itself. */
    @Query("UPDATE transactions SET categorized_by = :by, category_dirty = 1 WHERE id = :id")
    suspend fun setCategorizedBy(id: Long, by: String)

    /** Rows with no category decision yet, for the import's fill pass. */
    @Query("SELECT * FROM transactions WHERE category IS NULL OR category = 'Uncategorized'")
    suspend fun getUncategorized(): List<TransactionEntity>

    /** [getUncategorized] as a live list, newest first, for the Pending tab. */
    @Query(
        "SELECT * FROM transactions WHERE category IS NULL OR category = 'Uncategorized' " +
            "ORDER BY date DESC"
    )
    fun observeUncategorized(): Flow<List<TransactionEntity>>

    /** Synced rows whose Sheet Category cells are stale. */
    @Query("SELECT * FROM transactions WHERE synced_to_sheets = 1 AND category_dirty = 1")
    suspend fun getCategoryDirty(): List<TransactionEntity>

    /**
     * Clears the flag only if the category is still the one just written to the
     * Sheet. An edit made mid-sync keeps the row dirty for the next run.
     */
    @Query("UPDATE transactions SET category_dirty = 0 WHERE id = :id AND category IS :category")
    suspend fun clearCategoryDirty(id: Long, category: String?)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}
