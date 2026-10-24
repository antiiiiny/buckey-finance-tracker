package com.buckey.financetracker.category

import com.buckey.financetracker.data.category.CategoryUpdater
import com.buckey.financetracker.data.category.CategoryUpdater.Scope
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Row selection for the three category-edit scopes. The database half (date
 * filter, LIKE match, findMatch) is SQL; what's tested here is the pure decision
 * of which candidates a new rule actually governs.
 */
class CategoryUpdaterTest {

    private fun tx(id: Long, merchant: String, date: Long = id * 1_000) = TransactionEntity(
        id = id,
        transactionId = "hash$id",
        amount = 10.0,
        date = date,
        merchant = merchant,
        type = TransactionType.DEBIT,
        senderAddress = "AX-SIBSMS-S",
        rawBody = ""
    )

    @Test
    fun `generic merchant is always this-only`() {
        val upi = tx(1, "UPI")
        assertEquals(Scope.THIS_ONLY, CategoryUpdater.effectiveScope(upi, Scope.ALL))
        assertEquals(Scope.THIS_ONLY, CategoryUpdater.effectiveScope(upi, Scope.THIS_AND_LATER))
    }

    @Test
    fun `real merchant keeps the requested scope`() {
        val blinkit = tx(1, "BLINKIT")
        assertEquals(Scope.ALL, CategoryUpdater.effectiveScope(blinkit, Scope.ALL))
        assertEquals(Scope.THIS_ONLY, CategoryUpdater.effectiveScope(blinkit, Scope.THIS_ONLY))
    }

    @Test
    fun `pattern matches MerchantMemory normalization`() {
        assertEquals("anish kaarthik m", CategoryUpdater.patternFor("  ANISH KAARTHIK M "))
    }

    @Test
    fun `selects candidates the new rule wins`() {
        val edited = tx(1, "BLINKIT")
        val candidates = listOf(edited, tx(2, "BLINKIT"), tx(3, "BLINKIT COMMERCE PRIVATE LIM"))
        val winners = mapOf("BLINKIT" to "blinkit", "BLINKIT COMMERCE PRIVATE LIM" to "blinkit")

        assertEquals(
            listOf(1L, 2L, 3L),
            CategoryUpdater.targetIds(edited, candidates, winners, "blinkit")
        )
    }

    @Test
    fun `skips rows a longer, more specific rule claims`() {
        val edited = tx(1, "SHOPPERS CHOICE")
        val veg = tx(2, "SHOPPERS CHOICE VEG")
        val winners = mapOf("SHOPPERS CHOICE" to "shoppers choice", "SHOPPERS CHOICE VEG" to "shoppers choice veg")

        assertEquals(
            listOf(1L),
            CategoryUpdater.targetIds(edited, listOf(edited, veg), winners, "shoppers choice")
        )
    }

    @Test
    fun `the edited row is included even if not among candidates`() {
        val edited = tx(5, "FOOD C")
        assertEquals(listOf(5L), CategoryUpdater.targetIds(edited, emptyList(), emptyMap(), "food c"))
    }

    @Test
    fun `no duplicate ids`() {
        val edited = tx(1, "SANTOSH")
        val ids = CategoryUpdater.targetIds(edited, listOf(edited, edited), mapOf("SANTOSH" to "santosh"), "santosh")
        assertEquals(listOf(1L), ids)
    }

    @Test
    fun `selectGoverned excludes the edited row unless it matches`() {
        val a = tx(1, "JJ STALL CHRIST")
        val b = tx(2, "JJ STALLION")
        val winners = mapOf("JJ STALL CHRIST" to "jj stall", "JJ STALLION" to "jj stallion")
        assertEquals(listOf(1L), CategoryUpdater.selectGoverned(listOf(a, b), winners, "jj stall"))
    }
}
