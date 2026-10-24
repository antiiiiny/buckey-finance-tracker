package com.buckey.financetracker.sheets

import com.buckey.financetracker.data.db.entities.CategorizedBy
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.importer.MerchantMapImporter
import com.buckey.financetracker.data.sheets.SheetRowFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/** The Sheet's row layout: ten columns, Source and Categorized By last. */
class SheetRowFormatTest {

    private val ist = TimeZone.getTimeZone("Asia/Kolkata")

    private fun tx(sender: String, by: String = CategorizedBy.AUTO, category: String? = "Food") = TransactionEntity(
        transactionId = "abc",
        amount = 120.5,
        date = 1788084000000L, // 2026-08-30 15:30:00 IST
        merchant = "FOOD C",
        type = TransactionType.DEBIT,
        accountLast4 = "6825",
        rawRef = "620896250001",
        senderAddress = sender,
        rawBody = "",
        category = category,
        categorizedBy = by
    )

    @Test
    fun `headers end with source and categorized by`() {
        assertEquals(10, SheetRowFormat.HEADERS.size)
        assertEquals(listOf("Category", "Source", "Categorized By"), SheetRowFormat.HEADERS.takeLast(3))
        assertEquals('J', SheetRowFormat.LAST_COLUMN)
        assertEquals("Date", SheetRowFormat.HEADERS[SheetRowFormat.DATE_COLUMN_INDEX])
        assertEquals("Category", SheetRowFormat.HEADERS[SheetRowFormat.MUTABLE_FIRST_COLUMN - 'A'])
    }

    @Test
    fun `sms row`() {
        assertEquals(
            listOf("abc", "120.5", "2026-08-30 15:30:00", "FOOD C", "DEBIT", "6825", "620896250001", "Food", "sms", "auto"),
            SheetRowFormat.values(tx("AX-SIBSMS-S"), ist)
        )
    }

    @Test
    fun `statement row is marked bank_statement`() {
        val row = SheetRowFormat.values(tx(MerchantMapImporter.STATEMENT_SENDER, CategorizedBy.DEVELOPMENT), ist)
        assertEquals(listOf("bank_statement", "development"), row.takeLast(2))
    }

    @Test
    fun `missing category is written as Uncategorized`() {
        assertEquals("Uncategorized", SheetRowFormat.mutableValues(tx("AX-SIBSMS-S", category = null)).first())
    }

    @Test
    fun `manual edits are labelled manual`() {
        assertEquals("manual", SheetRowFormat.mutableValues(tx("AX-SIBSMS-S", CategorizedBy.MANUAL)).last())
    }
}
