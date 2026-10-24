package com.buckey.financetracker.data.sheets

import com.buckey.financetracker.data.db.entities.LinkKind
import com.buckey.financetracker.data.summary.LedgerResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The Holdings tab (2026-10-25): every movement of money held for someone, in
 * and out, with what was left after it. Holding rows never go to the
 * Transactions tab (rule 10, amended 2026-10-25), so this is their only place in
 * the Sheet. Written whole from Room on each sync when it differs; Room is the
 * source of truth (rule 8).
 *
 * Pure, so it is unit tested on the JVM.
 */
object HoldingsSheetFormat {

    const val SHEET_NAME = "Holdings"

    val HEADERS = listOf("Date", "Holding for", "Movement", "Amount", "Remaining", "Transaction ID")

    /** Data rows, oldest first across all holdings. */
    fun rows(ledger: LedgerResult, zone: TimeZone = TimeZone.getDefault()): List<List<String>> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone }
        return ledger.holdings
            .flatMap { holding -> holding.movements.map { holding.counterparty to it } }
            .sortedWith(compareBy({ it.second.transaction.date }, { it.second.transaction.id }))
            .map { (person, movement) ->
                listOf(
                    dateFormat.format(Date(movement.transaction.date)),
                    person,
                    when (movement.transaction.linkKind) {
                        LinkKind.HOLD_IN -> "In"
                        LinkKind.HOLD_CLEAR -> "Out (cleared)"
                        else -> "Out"
                    },
                    rupees(movement.amountPaise),
                    rupees(movement.remainingPaise),
                    movement.transaction.transactionId
                )
            }
    }

    private fun rupees(paise: Long): String = "%d.%02d".format(paise / 100, paise % 100)
}
