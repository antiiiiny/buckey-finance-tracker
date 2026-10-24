package com.buckey.financetracker.summary

import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.summary.MonthSummary
import com.buckey.financetracker.data.summary.SpentMode
import com.buckey.financetracker.ui.screens.Home.staleUnsyncedCount
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/** Spent, month net and grouping on the home screen (user's definitions, 2026-10-24). */
class MonthSummaryTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private var nextId = 1L

    private fun tx(
        type: TransactionType,
        amount: Double,
        category: String?,
        at: LocalDateTime,
        synced: Boolean = true,
        createdAt: Long = 0
    ): TransactionEntity {
        val id = nextId++
        return TransactionEntity(
            id = id,
            transactionId = "t$id",
            amount = amount,
            date = at.atZone(zone).toInstant().toEpochMilli(),
            type = type,
            senderAddress = "AD-SIBSMS-S",
            rawBody = "",
            category = category,
            syncedToSheets = synced,
            createdAt = createdAt
        )
    }

    private val oct = YearMonth.of(2026, 10)

    @Test
    fun `spent excludes transfers and credits but counts uncategorized debits`() {
        val rows = listOf(
            tx(TransactionType.DEBIT, 450.0, "Groceries", LocalDateTime.of(2026, 10, 24, 21, 0)),
            tx(TransactionType.DEBIT, 330.0, "Uncategorized", LocalDateTime.of(2026, 10, 23, 10, 0)),
            tx(TransactionType.DEBIT, 85.0, null, LocalDateTime.of(2026, 10, 22, 10, 0)),
            tx(TransactionType.DEBIT, 5000.0, "Transfer", LocalDateTime.of(2026, 10, 20, 9, 0)),
            tx(TransactionType.DEBIT, 1000.0, "Self Transfer", LocalDateTime.of(2026, 10, 19, 9, 0)),
            tx(TransactionType.CREDIT, 2000.0, "Income", LocalDateTime.of(2026, 10, 18, 9, 0)),
        )
        assertEquals(86500L, MonthSummary.totals(rows, oct, zone).spentPaise)
    }

    @Test
    fun `money paid back as a Transfer credit reduces spent, other credits don't`() {
        val at = LocalDateTime.of(2026, 10, 20, 9, 0)
        val rows = listOf(
            tx(TransactionType.DEBIT, 1200.0, "Food", at),          // paid for the group
            tx(TransactionType.CREDIT, 400.0, "Transfer", at),      // a friend paid back
            tx(TransactionType.CREDIT, 3000.0, "Self Transfer", at),
            tx(TransactionType.CREDIT, 9000.0, "Income", at),
        )
        assertEquals(80000L, MonthSummary.spentPaise(rows, SpentMode.AFTER_TRANSFERS))
    }

    @Test
    fun `spent goes negative when repayments outrun spending`() {
        val at = LocalDateTime.of(2026, 10, 20, 9, 0)
        val rows = listOf(
            tx(TransactionType.DEBIT, 100.0, "Food", at),
            tx(TransactionType.CREDIT, 600.0, "Transfer", at),
        )
        assertEquals(-50000L, MonthSummary.spentPaise(rows, SpentMode.AFTER_TRANSFERS))
    }

    @Test
    fun `total spent is every debit, transfers included`() {
        val at = LocalDateTime.of(2026, 10, 20, 9, 0)
        val rows = listOf(
            tx(TransactionType.DEBIT, 450.0, "Groceries", at),
            tx(TransactionType.DEBIT, 5000.0, "Transfer", at),
            tx(TransactionType.DEBIT, 1000.0, "Self Transfer", at),
            tx(TransactionType.CREDIT, 400.0, "Transfer", at),
        )
        assertEquals(645000L, MonthSummary.totals(rows, oct, zone, SpentMode.TOTAL).spentPaise)
    }

    @Test
    fun `month net is every credit minus every debit, transfers included`() {
        val rows = listOf(
            tx(TransactionType.DEBIT, 450.0, "Groceries", LocalDateTime.of(2026, 10, 24, 21, 0)),
            tx(TransactionType.DEBIT, 5000.0, "Transfer", LocalDateTime.of(2026, 10, 20, 9, 0)),
            tx(TransactionType.CREDIT, 2000.0, "Income", LocalDateTime.of(2026, 10, 18, 9, 0)),
            tx(TransactionType.CREDIT, 9999.0, "Income", LocalDateTime.of(2026, 9, 30, 9, 0)),
        )
        assertEquals(-345000L, MonthSummary.totals(rows, oct, zone).netPaise)
    }

    @Test
    fun `months follow the zone, not UTC`() {
        // 1 Nov 00:30 IST is still 31 Oct in UTC.
        val rows = listOf(tx(TransactionType.DEBIT, 100.0, "Food", LocalDateTime.of(2026, 11, 1, 0, 30)))
        assertEquals(0L, MonthSummary.totals(rows, oct, zone).spentPaise)
        assertEquals(10000L, MonthSummary.totals(rows, YearMonth.of(2026, 11), zone).spentPaise)
    }

    @Test
    fun `months list has the current month and is newest first`() {
        val rows = listOf(
            tx(TransactionType.DEBIT, 1.0, "Food", LocalDateTime.of(2026, 8, 3, 9, 0)),
            tx(TransactionType.DEBIT, 1.0, "Food", LocalDateTime.of(2026, 9, 3, 9, 0)),
            tx(TransactionType.DEBIT, 1.0, "Food", LocalDateTime.of(2026, 9, 4, 9, 0)),
        )
        assertEquals(
            listOf(oct, YearMonth.of(2026, 9), YearMonth.of(2026, 8)),
            MonthSummary.months(rows, zone, oct)
        )
    }

    @Test
    fun `day groups keep order and total only spending`() {
        val rows = listOf(
            tx(TransactionType.DEBIT, 450.0, "Groceries", LocalDateTime.of(2026, 10, 24, 21, 0)),
            tx(TransactionType.CREDIT, 2000.0, "Income", LocalDateTime.of(2026, 10, 24, 11, 0)),
            tx(TransactionType.DEBIT, 320.0, "Food", LocalDateTime.of(2026, 10, 24, 1, 0)),
            tx(TransactionType.DEBIT, 500.0, "Transfer", LocalDateTime.of(2026, 10, 23, 20, 0)),
        )
        val groups = MonthSummary.byDay(rows, zone)

        assertEquals(listOf(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 23)), groups.map { it.key })
        assertEquals(77000L, groups[0].spentPaise)
        assertEquals(3, groups[0].transactions.size)
        assertEquals(0L, groups[1].spentPaise)
    }

    @Test
    fun `sync is stale after an hour, or at once when signed out`() {
        val now = 10 * 60 * 60 * 1000L
        val at = LocalDateTime.of(2026, 10, 24, 9, 0)
        val rows = listOf(
            tx(TransactionType.DEBIT, 1.0, "Food", at, synced = false, createdAt = now - 2 * 60 * 60 * 1000L),
            tx(TransactionType.DEBIT, 1.0, "Food", at, synced = false, createdAt = now - 60 * 1000L),
            tx(TransactionType.DEBIT, 1.0, "Food", at, synced = true, createdAt = 0),
        )
        assertEquals(1, staleUnsyncedCount(rows, now, isSignedIn = true))
        assertEquals(2, staleUnsyncedCount(rows, now, isSignedIn = false))
    }
}
