package com.buckey.financetracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.buckey.financetracker.data.db.entities.UnparsedMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UnparsedMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: UnparsedMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<UnparsedMessageEntity>): List<Long>

    @Query("SELECT * FROM unparsed_messages WHERE manually_resolved = 0 ORDER BY received_at DESC")
    fun getUnresolvedMessages(): Flow<List<UnparsedMessageEntity>>

    @Query("SELECT * FROM unparsed_messages ORDER BY received_at DESC")
    fun getAllMessages(): Flow<List<UnparsedMessageEntity>>

    @Query("DELETE FROM unparsed_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE unparsed_messages SET manually_resolved = 1 WHERE id = :id")
    suspend fun markAsResolved(id: Long)

    @Query("SELECT * FROM unparsed_messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): UnparsedMessageEntity?

    /**
     * Resolves queued messages whose body mentions [ref]. Used when a statement
     * import has already recorded that transaction; the rows are kept (rule 5).
     */
    @Query(
        "UPDATE unparsed_messages SET manually_resolved = 1 " +
            "WHERE manually_resolved = 0 AND raw_body LIKE '%' || :ref || '%'"
    )
    suspend fun resolveByReference(ref: String): Int

    @Query("DELETE FROM unparsed_messages")
    suspend fun deleteAll()
}