package com.buckey.financetracker.category

import com.buckey.financetracker.data.category.CategoryMatcher
import com.buckey.financetracker.data.db.entities.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The credit rule: money in is income or a transfer (user, 2026-10-24). */
class CategoryMatcherTest {

    @Test
    fun `credit keeps income and transfer categories`() {
        assertEquals("Income", CategoryMatcher.forType("Income", TransactionType.CREDIT))
        assertEquals("Transfer", CategoryMatcher.forType("Transfer", TransactionType.CREDIT))
        assertEquals("Self Transfer", CategoryMatcher.forType("Self Transfer", TransactionType.CREDIT))
    }

    @Test
    fun `credit with a spending category becomes transfer`() {
        assertEquals("Transfer", CategoryMatcher.forType("Food", TransactionType.CREDIT))
    }

    @Test
    fun `uncategorized credit becomes transfer`() {
        assertEquals("Transfer", CategoryMatcher.forType("Uncategorized", TransactionType.CREDIT))
    }

    @Test
    fun `debits are never narrowed`() {
        assertEquals("Food", CategoryMatcher.forType("Food", TransactionType.DEBIT))
        assertEquals("Uncategorized", CategoryMatcher.forType("Uncategorized", TransactionType.DEBIT))
    }
}
