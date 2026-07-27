package com.buckey.financetracker.data.parser.banks

import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.parser.ParsedTransaction
import com.buckey.financetracker.data.parser.TransactionParser
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Parser for South Indian Bank (SIB) SMS messages.
 *
 * Handles two formats based on real captured SMS:
 *
 * **Debit:** `UPI debit:Rs.{amount},A/c X{last4}, {date} {time} RRN:{ref}. Bal:...`
 * **Credit:** `UPI Credit:INR Rs.{amount} in A/c X{last4}. Info:UPI/SIBL/{ref}/{merchant} on {date} {time}.Final balance...`
 */
object SibParser : TransactionParser {

    private val dateFormatter = DateTimeFormatter.ofPattern("dd-MM-yy HH:mm:ss")

    // UPI debit:Rs.1.00,A/c X6825, 27-07-26 20:11:44 RRN:620896250xxxx. Bal:...
    private val debitPattern = Regex(
        """UPI debit:Rs\.(\d+\.?\d*),A/c X(\w+), (\d{2}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) RRN:(\w+)\.""",
        RegexOption.IGNORE_CASE
    )

    // UPI Credit:INR Rs.1.00 in A/c X6825. Info:UPI/SIBL/11030666xxxx/LEO STEV on 27-07-26 20:43:01....
    private val creditPattern = Regex(
        """UPI Credit:INR Rs\.(\d+\.?\d*) in A/c X(\w+)\. Info:UPI/SIBL/(\w+)/([\w\s]+) on (\d{2}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\.""",
        RegexOption.IGNORE_CASE
    )

    override fun parse(senderAddress: String, rawBody: String): ParsedTransaction? {
        // Try debit pattern first
        debitPattern.find(rawBody)?.let { match ->
            val amount = match.groupValues[1].toDoubleOrNull() ?: return null
            val accountLast4 = match.groupValues[2]
            val dateString = match.groupValues[3]
            val rawRef = match.groupValues[4]
            val date = parseDate(dateString) ?: return null

            return ParsedTransaction(
                amount = amount,
                date = date,
                merchant = "UPI", // generic — debit SMS doesn't include merchant name
                type = TransactionType.DEBIT,
                accountLast4 = accountLast4,
                rawRef = rawRef
            )
        }

        // Try credit pattern
        creditPattern.find(rawBody)?.let { match ->
            val amount = match.groupValues[1].toDoubleOrNull() ?: return null
            val accountLast4 = match.groupValues[2]
            val rawRef = match.groupValues[3]
            val merchant = match.groupValues[4].trim()
            val dateString = match.groupValues[5]
            val date = parseDate(dateString) ?: return null

            return ParsedTransaction(
                amount = amount,
                date = date,
                merchant = merchant,
                type = TransactionType.CREDIT,
                accountLast4 = accountLast4,
                rawRef = rawRef
            )
        }

        // No pattern matched
        return null
    }

    private fun parseDate(dateString: String): Instant? {
        return try {
            val localDateTime = LocalDateTime.parse(dateString, dateFormatter)
            localDateTime.toInstant(ZoneOffset.ofHoursMinutes(5, 30)) // IST
        } catch (e: Exception) {
            null
        }
    }
}