package com.buckey.financetracker.data.sheets

import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.importer.MerchantMapImporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The one definition of a Sheet row: header names, column order, and how a
 * [TransactionEntity] becomes cell values. Appends, the full rebuild and the
 * in-place category updates all go through here, so they can't drift apart.
 *
 * Pure, so it is unit tested on the JVM.
 */
object SheetRowFormat {

    const val SHEET_NAME = "Transactions"

    val HEADERS = listOf(
        "Transaction ID", "Amount", "Date", "Merchant", "Type",
        "Account Last4", "Raw Ref", "Category", "Source", "Categorized By"
    )

    /** "A1:J1" for ten columns. */
    val LAST_COLUMN: Char = 'A' + HEADERS.size - 1

    /** Zero-based index of the Date column, the sort key. */
    const val DATE_COLUMN_INDEX = 2

    /**
     * Category, Source and Categorized By sit side by side (H:J), so one range
     * write refreshes everything about a row that can change after it's synced.
     */
    const val MUTABLE_FIRST_COLUMN = 'H'

    const val SOURCE_SMS = "sms"
    const val SOURCE_STATEMENT = "bank_statement"

    private const val UNCATEGORIZED = "Uncategorized"

    fun source(tx: TransactionEntity): String =
        if (tx.senderAddress == MerchantMapImporter.STATEMENT_SENDER) SOURCE_STATEMENT else SOURCE_SMS

    /** The H:J cells: category, source, categorized by. */
    fun mutableValues(tx: TransactionEntity): List<String> =
        listOf(tx.category ?: UNCATEGORIZED, source(tx), tx.categorizedBy)

    /**
     * One full row. The date is written `yyyy-MM-dd HH:mm:ss` with USER_ENTERED,
     * so Sheets stores it as a real date and sorting by it is chronological.
     */
    fun values(tx: TransactionEntity, zone: TimeZone = TimeZone.getDefault()): List<String> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone }
        return listOf(
            tx.transactionId,
            tx.amount.toString(),
            dateFormat.format(Date(tx.date)),
            tx.merchant,
            tx.type.name,
            tx.accountLast4,
            tx.rawRef
        ) + mutableValues(tx)
    }
}
