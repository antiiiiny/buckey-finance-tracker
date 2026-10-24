package com.buckey.financetracker.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["transaction_id"], unique = true),
        Index(value = ["date"])
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Stable hash of sender + timestamp + raw body — used for Sheets dedup */
    @ColumnInfo(name = "transaction_id")
    val transactionId: String,

    @ColumnInfo(name = "amount")
    val amount: Double,

    /** Epoch millis */
    @ColumnInfo(name = "date")
    val date: Long,

    @ColumnInfo(name = "merchant")
    val merchant: String = "",

    @ColumnInfo(name = "type")
    val type: TransactionType,

    @ColumnInfo(name = "account_last4")
    val accountLast4: String = "",

    /** Transaction reference number from the SMS (RRN, UPI ref, etc.) */
    @ColumnInfo(name = "raw_ref")
    val rawRef: String = "",

    @ColumnInfo(name = "sender_address")
    val senderAddress: String,

    /** Raw SMS body — never logged, never sent over network */
    @ColumnInfo(name = "raw_body")
    val rawBody: String,

    @ColumnInfo(name = "category")
    val category: String? = null,

    @ColumnInfo(name = "synced_to_sheets")
    val syncedToSheets: Boolean = false,

    /**
     * True when the category changed after this row reached the Sheet, so the
     * next sync rewrites its Category cell in place (rule 10, amended
     * 2026-10-24). Every DAO write to `category` sets it.
     */
    @ColumnInfo(name = "category_dirty", defaultValue = "0")
    val categoryDirty: Boolean = false,

    /** Epoch millis when this row was created */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)