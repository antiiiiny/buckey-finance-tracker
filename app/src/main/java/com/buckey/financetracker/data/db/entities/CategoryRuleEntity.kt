package com.buckey.financetracker.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "category_rules",
    indices = [Index(value = ["merchant_pattern"], unique = true)]
)
data class CategoryRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The merchant string to match against (case-insensitive substring match) */
    @ColumnInfo(name = "merchant_pattern")
    val merchantPattern: String,

    /** The category to assign (e.g. "Food", "Transport", "Shopping") */
    @ColumnInfo(name = "category")
    val category: String
)