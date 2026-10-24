package com.buckey.financetracker.data.summary

import com.buckey.financetracker.data.category.Categories
import com.buckey.financetracker.data.db.entities.TransactionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** One month's figures for the home summary. */
data class MonthTotals(val spentPaise: Long, val netPaise: Long)

/** A run of rows sharing a day or month, with that run's spending. */
data class TransactionGroup<K>(
    val key: K,
    val spentPaise: Long,
    val transactions: List<TransactionEntity>
)

/**
 * Month and day arithmetic for the home screen. Spent and Net per row come from
 * the [Ledger] (user's rules, 2026-10-24/25); this sums them over a month, a
 * day or a list. Every function takes the ledger computed over *all* rows,
 * since a loan or holding row's effect depends on rows outside the list shown;
 * the overloads without one compute it from the rows given, for tests.
 */
object MonthSummary {

    /**
     * What [rows] spent, per [mode]:
     *
     * - [SpentMode.AFTER_TRANSFERS]: spending debits and cash payments, minus
     *   Transfer credits (friends paying back), plus anything paid beyond a
     *   holding when clearing it. Transfer, Savings, ATM, Lend and Holding
     *   debits move money without spending it. Can go negative when repayments
     *   outrun spending.
     * - [SpentMode.TOTAL]: every bank debit except those paid out of a holding.
     */
    fun spentPaise(
        rows: List<TransactionEntity>,
        mode: SpentMode,
        ledger: LedgerResult = Ledger.compute(rows)
    ): Long = rows.sumOf {
        val effect = ledger.effect(it)
        if (mode == SpentMode.TOTAL) effect.totalSpent else effect.spent
    }

    /** No category decision yet; what "to categorize" counts. */
    fun isUncategorized(tx: TransactionEntity): Boolean =
        tx.category == null || tx.category == Categories.UNCATEGORIZED

    fun monthOf(tx: TransactionEntity, zone: ZoneId): YearMonth =
        YearMonth.from(Instant.ofEpochMilli(tx.date).atZone(zone))

    fun dayOf(tx: TransactionEntity, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(tx.date).atZone(zone).toLocalDate()

    /** Spent: [spentPaise] over [month]. Net: the month's change in Net. */
    fun totals(
        transactions: List<TransactionEntity>,
        month: YearMonth,
        zone: ZoneId,
        spentMode: SpentMode = SpentMode.AFTER_TRANSFERS,
        ledger: LedgerResult = Ledger.compute(transactions)
    ): MonthTotals {
        val inMonth = transactions.filter { monthOf(it, zone) == month }
        return MonthTotals(
            spentPaise = spentPaise(inMonth, spentMode, ledger),
            netPaise = inMonth.sumOf { ledger.effect(it).net }
        )
    }

    /** Months that have rows, plus [current], newest first. */
    fun months(transactions: List<TransactionEntity>, zone: ZoneId, current: YearMonth): List<YearMonth> =
        (transactions.map { monthOf(it, zone) } + current).distinct().sortedDescending()

    /** Groups by calendar day, keeping the rows' order (newest first in the UI). */
    fun byDay(
        transactions: List<TransactionEntity>,
        zone: ZoneId,
        spentMode: SpentMode = SpentMode.AFTER_TRANSFERS,
        ledger: LedgerResult = Ledger.compute(transactions)
    ): List<TransactionGroup<LocalDate>> = group(transactions, spentMode, ledger) { dayOf(it, zone) }

    /** Groups by calendar month, keeping the rows' order. */
    fun byMonth(
        transactions: List<TransactionEntity>,
        zone: ZoneId,
        spentMode: SpentMode = SpentMode.AFTER_TRANSFERS,
        ledger: LedgerResult = Ledger.compute(transactions)
    ): List<TransactionGroup<YearMonth>> = group(transactions, spentMode, ledger) { monthOf(it, zone) }

    private fun <K> group(
        transactions: List<TransactionEntity>,
        spentMode: SpentMode,
        ledger: LedgerResult,
        keyOf: (TransactionEntity) -> K
    ): List<TransactionGroup<K>> =
        transactions.groupBy(keyOf).map { (key, rows) ->
            TransactionGroup(key, spentPaise(rows, spentMode, ledger), rows)
        }
}
