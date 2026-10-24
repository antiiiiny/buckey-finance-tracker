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

    @Query("UPDATE transactions SET category = :category WHERE id = :id")
    suspend fun updateCategory(id: Long, category: String)

    /**
     * Repairs a row's merchant from an external source, keyed by the stable
     * transaction hash rather than the local row id.
     *
     * Used by the Stage 7 merchant map import: the bank statement carries the
     * payee that the debit SMS omits. Returns the number of rows updated, so the
     * caller can report hashes that no longer exist on this device.
     */
    @Query(
        "UPDATE transactions SET merchant = :merchant, category = :category " +
            "WHERE transaction_id = :transactionId"
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
        "UPDATE transactions SET category = :to " +
            "WHERE category = :from AND UPPER(TRIM(merchant)) IN (:merchants)"
    )
    suspend fun recategorizeMerchants(merchants: List<String>, from: String, to: String): Int

    @Query("SELECT * FROM transactions WHERE raw_ref = :rawRef LIMIT 1")
    suspend fun getByRawRef(rawRef: String): TransactionEntity?

    @Query("UPDATE transactions SET synced_to_sheets = 1 WHERE id = :id")
    suspend fun markAsSynced(id: Long)

    @Query("SELECT * FROM transactions WHERE synced_to_sheets = 0 ORDER BY date DESC")
    suspend fun getUnsyncedTransactions(): List<TransactionEntity>

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}