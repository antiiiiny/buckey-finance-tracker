package com.buckey.financetracker.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "unparsed_messages")
data class UnparsedMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "sender_address")
    val senderAddress: String,

    @ColumnInfo(name = "raw_body")
    val rawBody: String,

    /** Epoch millis when the message was received */
    @ColumnInfo(name = "received_at")
    val receivedAt: Long = System.currentTimeMillis(),

    /** Why parsing failed — null if not yet attempted */
    @ColumnInfo(name = "failure_reason")
    val failureReason: String? = null,

    /** Whether the user has manually resolved this into a transaction */
    @ColumnInfo(name = "manually_resolved")
    val manuallyResolved: Boolean = false
)