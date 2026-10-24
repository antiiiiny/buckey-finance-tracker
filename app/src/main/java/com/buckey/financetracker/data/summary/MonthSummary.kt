package com.buckey.financetracker.data.summary

import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
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
 * Month and day arithmetic for the home screen. Pure functions over rows, so
 * the rules below are unit tested without a database.
 */
object MonthSummary {

    /** Debits in these categories move money without spending it. */
    val TRANSFER_CATEGORIES: Set<String> = setOf("Transfer", "Self Transfer")

    /**
     * Credits in this category are someone paying the user back, so they reduce
     * spending. Self Transfer credits are the user's own money and don't.
     */
    const val REPAYMENT_CATEGORY = "Transfer"

    private const val UNCATEGORIZED = "Uncategorized"

    /**
     * A debit not categorized as a transfer. An uncategorized debit counts,
     * since it is most likely spending.
     */
    fun isSpending(tx: TransactionEntity): Boolean =
        tx.type == TransactionType.DEBIT && tx.category !in TRANSFER_CATEGORIES

    /** A credit that pays the user back for something ([REPAYMENT_CATEGORY]). */
    fun isRepayment(tx: TransactionEntity): Boolean =
        tx.type == TransactionType.CREDIT && tx.category == REPAYMENT_CATEGORY

    /**
     * What the rows spent, per [mode] (user's definitions, 2026-10-24):
     *
     * - [SpentMode.AFTER_TRANSFERS]: [isSpending] debits minus [isRepayment]
     *   credits. A Transfer debit is usually handing someone money for cash, a
     *   Transfer credit is a friend paying back something the user paid for;
     *   neither changes what the user actually spent. Can go negative when
     *   repayments outrun spending.
     * - [SpentMode.TOTAL]: every debit.
     */
    fun spentPaise(rows: List<TransactionEntity>, mode: SpentMode): Long = when (mode) {
        SpentMode.AFTER_TRANSFERS ->
            rows.filter(::isSpending).sumOf { toPaise(it.amount) } -
                rows.filter(::isRepayment).sumOf { toPaise(it.amount) }
        SpentMode.TOTAL ->
            rows.filter { it.type == TransactionType.DEBIT }.sumOf { toPaise(it.amount) }
    }

    /** No category decision yet; what "to categorize" counts. */
    fun isUncategorized(tx: TransactionEntity): Boolean =
        tx.category == null || tx.category == UNCATEGORIZED

    fun monthOf(tx: TransactionEntity, zone: ZoneId): YearMonth =
        YearMonth.from(Instant.ofEpochMilli(tx.date).atZone(zone))

    fun dayOf(tx: TransactionEntity, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(tx.date).atZone(zone).toLocalDate()

    /**
     * Spent: [spentPaise] over [month]. Net: every credit minus every debit in
     * [month], transfers included, so it is the month's change in balance.
     */
    fun totals(
        transactions: List<TransactionEntity>,
        month: YearMonth,
        zone: ZoneId,
        spentMode: SpentMode = SpentMode.AFTER_TRANSFERS
    ): MonthTotals {
        val inMonth = transactions.filter { monthOf(it, zone) == month }
        return MonthTotals(
            spentPaise = spentPaise(inMonth, spentMode),
            netPaise = inMonth.sumOf { it.signedPaise() }
        )
    }

    /** Months that have rows, plus [current], newest first. */
    fun months(transactions: List<TransactionEntity>, zone: ZoneId, current: YearMonth): List<YearMonth> =
        (transactions.map { monthOf(it, zone) } + current).distinct().sortedDescending()

    /** Groups by calendar day, keeping the rows' order (newest first in the UI). */
    fun byDay(
        transactions: List<TransactionEntity>,
        zone: ZoneId,
        spentMode: SpentMode = SpentMode.AFTER_TRANSFERS
    ): List<TransactionGroup<LocalDate>> = group(transactions, spentMode) { dayOf(it, zone) }

    /** Groups by calendar month, keeping the rows' order. */
    fun byMonth(
        transactions: List<TransactionEntity>,
        zone: ZoneId,
        spentMode: SpentMode = SpentMode.AFTER_TRANSFERS
    ): List<TransactionGroup<YearMonth>> = group(transactions, spentMode) { monthOf(it, zone) }

    private fun <K> group(
        transactions: List<TransactionEntity>,
        spentMode: SpentMode,
        keyOf: (TransactionEntity) -> K
    ): List<TransactionGroup<K>> =
        transactions.groupBy(keyOf).map { (key, rows) ->
            TransactionGroup(key, spentPaise(rows, spentMode), rows)
        }
}
