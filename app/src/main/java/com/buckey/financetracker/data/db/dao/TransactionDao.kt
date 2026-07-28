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

    @Query("UPDATE transactions SET synced_to_sheets = 1 WHERE id = :id")
    suspend fun markAsSynced(id: Long)

    @Query("SELECT * FROM transactions WHERE synced_to_sheets = 0 ORDER BY date DESC")
    suspend fun getUnsyncedTransactions(): List<TransactionEntity>
}