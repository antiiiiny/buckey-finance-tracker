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

    @Query("SELECT * FROM category_rules ORDER BY category ASC, merchant_pattern ASC")
    fun getAllRules(): Flow<List<CategoryRuleEntity>>

    @Query("SELECT * FROM category_rules WHERE merchant_pattern = :pattern LIMIT 1")
    suspend fun getByMerchantPattern(pattern: String): CategoryRuleEntity?

    @Query("SELECT * FROM category_rules WHERE :merchant LIKE '%' || merchant_pattern || '%' LIMIT 1")
    suspend fun findMatch(merchant: String): CategoryRuleEntity?

    @Query("DELETE FROM category_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM category_rules")
    suspend fun deleteAll()
}