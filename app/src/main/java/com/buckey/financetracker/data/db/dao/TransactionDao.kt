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
    // rewrites the Sheet's Category cell for rows already appended (rule 10).

    @Query("UPDATE transactions SET category = :category, category_dirty = 1 WHERE id = :id")
    suspend fun updateCategory(id: Long, category: String)

    // The bulk writes below narrow a credit to [creditCategories], falling back
    // to [creditFallback] — CategoryMatcher.forType in SQL, so one statement
    // handles a rule that covers debits and credits alike.

    /** Sets the category of the row(s) with this reference; for import overrides. */
    @Query(
        "UPDATE transactions SET category = CASE WHEN type = 'CREDIT' AND :category " +
            "NOT IN (:creditCategories) THEN :creditFallback ELSE :category END, " +
            "category_dirty = 1 WHERE raw_ref = :rawRef"
    )
    suspend fun updateCategoryByRawRef(
        rawRef: String,
        category: String,
        creditCategories: List<String>,
        creditFallback: String
    ): Int

    /** Bulk form of [updateCategory], for "this and later" / "every" edits. */
    @Query(
        "UPDATE transactions SET category = CASE WHEN type = 'CREDIT' AND :category " +
            "NOT IN (:creditCategories) THEN :creditFallback ELSE :category END, " +
            "category_dirty = 1 WHERE id IN (:ids)"
    )
    suspend fun updateCategoryForIds(
        ids: List<Long>,
        category: String,
        creditCategories: List<String>,
        creditFallback: String
    ): Int

    /**
     * Moves every credit outside [creditCategories] (or with none) to
     * [creditFallback]. Idempotent; CategoryRepair runs it at launch so the
     * credit rule holds whatever path wrote the row.
     */
    @Query(
        "UPDATE transactions SET category = :creditFallback, category_dirty = 1 " +
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
            "category_dirty = 1 WHERE transaction_id = :transactionId"
    )
    suspend fun updateMerchantAndCategory(
        transactionId: String,
        merchant: String,
        category: String
    ): Int

    /**
     * Moves rows with one of [merchants] (uppercase) from category [from] to [to].
     * Returns the number of rows changed. Used by CategoryRepair to undo labels a
     * `upi → Transfer` rule stamped onto every debit.
     */
    @Query(
        "UPDATE transactions SET category = :to, category_dirty = 1 " +
            "WHERE category = :from AND UPPER(TRIM(merchant)) IN (:merchants)"
    )
    suspend fun recategorizeMerchants(merchants: List<String>, from: String, to: String): Int

    @Query("SELECT * FROM transactions WHERE raw_ref = :rawRef LIMIT 1")
    suspend fun getByRawRef(rawRef: String): TransactionEntity?

    @Query("UPDATE transactions SET synced_to_sheets = 1 WHERE id = :id")
    suspend fun markAsSynced(id: Long)

    @Query("SELECT * FROM transactions WHERE synced_to_sheets = 0 ORDER BY date DESC")
    suspend fun getUnsyncedTransactions(): List<TransactionEntity>

    /** Rows with no category decision yet, for the import's fill pass. */
    @Query("SELECT * FROM transactions WHERE category IS NULL OR category = 'Uncategorized'")
    suspend fun getUncategorized(): List<TransactionEntity>

    /** Synced rows whose Sheet Category cell is stale. */
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