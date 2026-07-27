package com.buckey.financetracker.data.parser

import com.buckey.financetracker.data.db.entities.TransactionType
import java.time.Instant

/**
 * Output of a bank parser — a successfully parsed transaction from an SMS.
 *
 * Every field is required per the project spec (Rule 4).
 * This is NOT a Room entity — it's the parser's output.
 * The pipeline converts it to [TransactionEntity] before inserting into Room.
 */
data class ParsedTransaction(
    val amount: Double,
    val date: Instant,
    val merchant: String,
    val type: TransactionType,
    val accountLast4: String,
    val rawRef: String
)