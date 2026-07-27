package com.buckey.financetracker.data.sms

import java.security.MessageDigest

/**
 * Generates a stable, unique transaction ID for deduplication.
 *
 * Hash of sender address + timestamp + raw body.
 * Used as the dedup key in both Room and Google Sheets.
 */
object TransactionIdGenerator {

    /**
     * Creates a SHA-256 hex hash from the SMS components.
     */
    fun generate(senderAddress: String, receivedAtMillis: Long, rawBody: String): String {
        val input = "$senderAddress|$receivedAtMillis|$rawBody"
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}