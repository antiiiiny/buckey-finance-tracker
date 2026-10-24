package com.buckey.financetracker.sheets

import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.sheets.SheetCategorySync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-place Category cell updates (rule 10, amended 2026-10-24). */
class SheetCategorySyncTest {

    private fun tx(hash: String, category: String?) = TransactionEntity(
        id = hash.hashCode().toLong(),
        transactionId = hash,
        amount = 10.0,
        date = 0,
        merchant = "BLINKIT",
        type = TransactionType.DEBIT,
        senderAddress = "AX-SIBSMS-S",
        rawBody = "",
        category = category,
        syncedToSheets = true,
        categoryDirty = true
    )

    private val columnA: List<List<Any?>> = listOf(
        listOf("Transaction ID"),
        listOf("aaa"),
        emptyList(),
        listOf("bbb"),
        listOf("aaa") // a repeat: the first row wins
    )

    @Test
    fun `index maps ids to 1-based sheet rows`() {
        assertEquals(mapOf("Transaction ID" to 1, "aaa" to 2, "bbb" to 4), SheetCategorySync.indexRows(columnA))
    }

    @Test
    fun `index of an empty or missing column is empty`() {
        assertTrue(SheetCategorySync.indexRows(null).isEmpty())
        assertTrue(SheetCategorySync.indexRows(emptyList()).isEmpty())
    }

    @Test
    fun `plan targets column H of each row`() {
        val plan = SheetCategorySync.plan(
            SheetCategorySync.indexRows(columnA),
            listOf(tx("aaa", "Groceries"), tx("bbb", "Food"))
        )

        assertEquals(listOf("Transactions!H2", "Transactions!H4"), plan.updates.map { it.range })
        assertEquals(listOf("Groceries", "Food"), plan.updates.map { it.value })
        assertTrue(plan.notInSheet.isEmpty())
    }

    @Test
    fun `null category is written as Uncategorized, like an append`() {
        val plan = SheetCategorySync.plan(mapOf("aaa" to 2), listOf(tx("aaa", null)))
        assertEquals("Uncategorized", plan.updates.single().value)
    }

    @Test
    fun `rows not in the sheet are reported, not written`() {
        val missing = tx("zzz", "Food")
        val plan = SheetCategorySync.plan(mapOf("aaa" to 2), listOf(missing))

        assertTrue(plan.updates.isEmpty())
        assertEquals(listOf(missing), plan.notInSheet)
    }
}
