package com.buckey.financetracker.data.wallet

import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.summary.Ledger
import com.buckey.financetracker.data.summary.LedgerResult
import com.buckey.financetracker.data.summary.LinkGroup

/** A row that looks like it settles [group]: a holding payout or a loan repayment. */
data class LinkSuggestion(val transaction: TransactionEntity, val group: LinkGroup)

/**
 * The suggestions Pending shows (user, 2026-10-25): payments within the match
 * range of an open holding ("pay out a holding?") and credits within it of an
 * open loan ("loan paid back?"). Only suggestions: nothing links until the user
 * taps Clear, and "Not this" hides one for good. Pure, so it is unit tested.
 */
object LinkSuggestions {

    fun find(
        rows: List<TransactionEntity>,
        ledger: LedgerResult,
        tolerancePaise: Long,
        dismissed: Set<String>
    ): List<LinkSuggestion> = rows.mapNotNull { tx ->
        val candidates = Ledger.clearSuggestions(ledger, tx, tolerancePaise) +
            Ledger.repaySuggestions(ledger, tx, tolerancePaise)
        candidates
            .firstOrNull { "${tx.transactionId}|${it.id}" !in dismissed }
            ?.let { LinkSuggestion(tx, it) }
    }
}
