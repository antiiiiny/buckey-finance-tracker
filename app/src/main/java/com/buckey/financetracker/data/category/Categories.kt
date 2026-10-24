package com.buckey.financetracker.data.category

/**
 * Category names with meaning beyond a label: they decide how a row moves the
 * wallet, loans, holdings, Spent and Net (see data/summary/Ledger). Defined once
 * so the ledger, the editor, the repair pass and the Sheet agree.
 */
object Categories {
    const val UNCATEGORIZED = "Uncategorized"
    const val INCOME = "Income"

    /** Debit: money to a friend who hands back cash (wallet +). Credit: a repayment. */
    const val TRANSFER = "Transfer"

    /** Credit only: the user's own money coming back from another account. */
    const val SELF_TRANSFER = "Self Transfer"

    /** Debit only: the user's own money moved to another account (was "Self Transfer"). */
    const val SAVINGS = "Savings"

    /** Debit: cash out of an ATM, bank − wallet +. */
    const val ATM_WITHDRAWAL = "ATM Withdrawal"

    /** Debit: money lent; tracked as owed to the user until repaid. */
    const val LEND = "Lend"

    /** Credit: a loan paid back, linked to the loan it clears. */
    const val LOAN_REPAID = "Loan Repaid"

    /** Money held for someone else: in (credit) or paid out of it (debit). */
    const val HOLDING = "Holding"

    /** A cash row correcting the wallet: the opening count or a recount. */
    const val WALLET_ADJUSTMENT = "Wallet Adjustment"

    /**
     * Plain categories offered for a debit. Lend and Holding are not here: they
     * need a person or a holding, so the editor sets them through Links.
     */
    val DEBIT_PRESETS: List<String> = listOf(
        "Food", "Transport", "Shopping", "Groceries", "Utilities",
        "Housing", "Home", "Room", SAVINGS, ATM_WITHDRAWAL, TRANSFER, "Entertainment",
        "Health", "Education", "College", "Scooter", "Travel", "Bills",
        "SMS Charges", "Miscellaneous", "Other", UNCATEGORIZED
    )

    /** Plain categories offered for a credit; Holding and Loan Repaid go through Links. */
    val CREDIT_PRESETS: List<String> = listOf(INCOME, TRANSFER, SELF_TRANSFER)

    /** Categories a cash payment can take: the spending ones. */
    val CASH_PRESETS: List<String> by lazy {
        DEBIT_PRESETS.filter { it !in NON_SPENDING_DEBITS && it != UNCATEGORIZED }
    }

    /** Debits that move money without spending it (user's rules, 2026-10-25). */
    val NON_SPENDING_DEBITS: Set<String> = setOf(
        TRANSFER, SELF_TRANSFER, SAVINGS, ATM_WITHDRAWAL, LEND, HOLDING, WALLET_ADJUSTMENT
    )
}
