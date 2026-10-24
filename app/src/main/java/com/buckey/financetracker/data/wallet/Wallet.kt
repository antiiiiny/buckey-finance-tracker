package com.buckey.financetracker.data.wallet

import com.buckey.financetracker.data.db.entities.TransactionEntity

/**
 * The wallet: cash in hand (user's design, 2026-10-25). Cash rows are ordinary
 * [TransactionEntity] rows with [CASH_SENDER], so Home, the category editor,
 * Spent and the Sheets sync (Source = `cash`) reuse the existing paths. Bank
 * rows feed it too: ATM withdrawals and Transfer debits confirmed as cash.
 *
 * The wallet starts at its first cash row, the opening count; nothing dated
 * before it moves the wallet.
 */
object Wallet {
    /** `sender_address` of every cash row. Never a real SMS sender. */
    const val CASH_SENDER = "CASH"

    /** `account_last4` of cash rows, shown in the Sheet's Account column. */
    const val CASH_ACCOUNT = "cash"

    /** Merchant of the opening count: it starts the wallet, so it isn't income. */
    const val OPENING_MERCHANT = "Opening cash"

    /** Merchant of a later correction after counting the cash. */
    const val RECOUNT_MERCHANT = "Cash recount"

    fun isCash(tx: TransactionEntity): Boolean = tx.senderAddress == CASH_SENDER
}
