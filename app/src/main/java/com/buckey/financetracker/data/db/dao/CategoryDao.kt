package com.buckey.financetracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.buckey.financetracker.data.db.entities.CategoryRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(rule: CategoryRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<CategoryRuleEntity>)

    /**
     * Inserts only patterns not already present, so a starter rule the user has
     * since re-pointed is left alone. Returns -1 for each pattern that existed.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfAbsent(rules: List<CategoryRuleEntity>): List<Long>

    /** Case-insensitive delete by pattern; returns the number of rules removed. */
    @Query("DELETE FROM category_rules WHERE UPPER(merchant_pattern) IN (:patterns)")
    suspend fun deleteByPatterns(patterns: List<String>): Int

    @Query("SELECT * FROM category_rules ORDER BY category ASC, merchant_pattern ASC")
    fun getAllRules(): Flow<List<CategoryRuleEntity>>

    @Query("SELECT * FROM category_rules WHERE merchant_pattern = :pattern LIMIT 1")
    suspend fun getByMerchantPattern(pattern: String): CategoryRuleEntity?

    /** Longest matching pattern wins, so the most specific rule is applied. */
    @Query("SELECT * FROM category_rules WHERE :merchant LIKE '%' || merchant_pattern || '%' ORDER BY LENGTH(merchant_pattern) DESC LIMIT 1")
    suspend fun findMatch(merchant: String): CategoryRuleEntity?

    @Query("DELETE FROM category_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM category_rules")
    suspend fun deleteAll()
}