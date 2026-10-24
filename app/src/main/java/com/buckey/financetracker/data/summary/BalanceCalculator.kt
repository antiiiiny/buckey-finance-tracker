package com.buckey.financetracker.data.summary

import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.parser.banks.SibParser
import kotlin.math.roundToLong

/** A rupee amount as whole paise, so sums are exact. */
fun toPaise(rupees: Double): Long = (rupees * 100).roundToLong()

/** The row's effect on the account balance: credits add, debits subtract. */
fun TransactionEntity.signedPaise(): Long =
    if (type == TransactionType.CREDIT) toPaise(amount) else -toPaise(amount)

/**
 * Buckey's balance, checked against what SIB itself reported.
 *
 * [estimatedPaise] is the anchor plus every row up to and including the latest
 * SMS that quotes a balance, so it compares like for like with [smsBalancePaise]
 * even if statement-only rows dated later exist.
 */
data class BalanceCheck(
    val estimatedPaise: Long,
    val smsBalancePaise: Long,
    val smsDate: Long,
    val anchorDate: Long
) {
    val differencePaise: Long get() = smsBalancePaise - estimatedPaise
    val matches: Boolean get() = differencePaise == 0L
}

/** The running balance through the newest row, and its [check]. */
data class RunningBalance(val balancePaise: Long, val check: BalanceCheck)

/**
 * The home screen's running balance (user's design, 2026-10-24): the balance
 * quoted by the earliest SMS that has one, plus every credit and minus every
 * debit after it, whatever its category. Statement-only rows count too; they
 * are real transactions SIB sent no SMS for.
 *
 * Verified on the user's device data the day it was written: anchor plus every
 * later row landed exactly on the latest SMS balance.
 */
object BalanceCalculator {

    /**
     * Null when no row carries a balance (a fresh install, or statement-only
     * rows). Rows are taken oldest first, ties broken by id, the same order the
     * Sheet rebuild uses.
     */
    fun compute(
        transactions: List<TransactionEntity>,
        balanceOf: (TransactionEntity) -> Double? = { SibParser.parseBalance(it.rawBody) }
    ): RunningBalance? {
        val ordered = transactions.sortedWith(compareBy({ it.date }, { it.id }))
        val quoted = ordered.map { tx -> balanceOf(tx)?.let(::toPaise) }

        val anchor = quoted.indexOfFirst { it != null }
        if (anchor < 0) return null
        val latest = quoted.indexOfLast { it != null }

        var running = quoted[anchor]!!
        var estimatedAtLatest = running
        for (i in anchor + 1 until ordered.size) {
            running += ordered[i].signedPaise()
            if (i == latest) estimatedAtLatest = running
        }

        return RunningBalance(
            balancePaise = running,
            check = BalanceCheck(
                estimatedPaise = estimatedAtLatest,
                smsBalancePaise = quoted[latest]!!,
                smsDate = ordered[latest].date,
                anchorDate = ordered[anchor].date
            )
        )
    }
}
