package com.buckey.financetracker.data.wallet

import com.buckey.financetracker.data.category.Categories
import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.db.entities.CategorizedBy
import com.buckey.financetracker.data.db.entities.LinkKind
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.summary.GroupKind
import com.buckey.financetracker.data.summary.LinkGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The user's choices that tie a bank row to the wallet, a loan or a holding
 * (design, 2026-10-25). Every link is set here and only on the user's say-so:
 * no rule or import ever links a row, and bulk category writes skip linked rows.
 *
 * What each link means for Spent, Net and the pots is decided by
 * data/summary/Ledger; this only records the choice.
 */
object Links {

    /** A Transfer debit that came back as cash in hand: wallet +. */
    suspend fun markCash(database: AppDatabase, tx: TransactionEntity) {
        require(tx.type == TransactionType.DEBIT) { "Only a debit can turn into cash." }
        set(database, tx, Categories.TRANSFER, LinkKind.CASH, group = null, person = null)
    }

    /** A debit lent to [person]; opens a loan keyed by this row. */
    suspend fun markLend(database: AppDatabase, tx: TransactionEntity, person: String) {
        require(tx.type == TransactionType.DEBIT) { "Only a debit can be a loan." }
        set(database, tx, Categories.LEND, LinkKind.LEND, tx.transactionId, person.requireName())
    }

    /** A credit held for [person]; opens a holding keyed by this row. */
    suspend fun markHolding(database: AppDatabase, tx: TransactionEntity, person: String) {
        require(tx.type == TransactionType.CREDIT) { "Only money received can be held." }
        set(database, tx, Categories.HOLDING, LinkKind.HOLD_IN, tx.transactionId, person.requireName())
    }

    /**
     * A debit paid out of [holding]. With [clear], the holding closes whatever
     * is left: a leftover becomes the user's money, an overspend counts as Spent.
     */
    suspend fun payFromHolding(database: AppDatabase, tx: TransactionEntity, holding: LinkGroup, clear: Boolean) {
        require(tx.type == TransactionType.DEBIT) { "Only a payment can come out of a holding." }
        require(holding.kind == GroupKind.HOLDING && holding.isOpen) { "That holding is already cleared." }
        set(
            database, tx, Categories.HOLDING,
            if (clear) LinkKind.HOLD_CLEAR else LinkKind.HOLD_OUT,
            holding.id, holding.counterparty,
            amount = if (clear) holding.remainingPaise / 100.0 else null
        )
    }

    /** A credit paying back [loan]. */
    suspend fun repayLoan(database: AppDatabase, tx: TransactionEntity, loan: LinkGroup) {
        require(tx.type == TransactionType.CREDIT) { "Only money received can repay a loan." }
        require(loan.kind == GroupKind.LOAN && loan.isOpen) { "That loan is already repaid." }
        set(database, tx, Categories.LOAN_REPAID, LinkKind.LEND_REPAID, loan.id, loan.counterparty)
    }

    /**
     * Before [tx] gets a new plain category: if it opened a loan or holding, the
     * rows linked to it lose their link too (back to Uncategorized), so nothing
     * points at a group that no longer exists. Returns how many were released.
     */
    suspend fun releaseDependents(database: AppDatabase, tx: TransactionEntity): Int =
        withContext(Dispatchers.IO) {
            if (tx.linkKind != LinkKind.LEND && tx.linkKind != LinkKind.HOLD_IN) return@withContext 0
            val dao = database.transactionDao()
            val dependents = dao.getByLinkGroup(tx.transactionId).filter { it.id != tx.id }
            dependents.forEach { dao.updateCategory(it.id, Categories.UNCATEGORIZED, CategorizedBy.MANUAL) }
            dependents.size
        }

    private suspend fun set(
        database: AppDatabase,
        tx: TransactionEntity,
        category: String,
        kind: String,
        group: String?,
        person: String?,
        amount: Double? = null
    ) = withContext(Dispatchers.IO) {
        database.transactionDao().updateLink(tx.id, category, kind, group, person, amount)
    }

    private fun String.requireName(): String =
        trim().also { require(it.isNotEmpty()) { "Enter who it's with." } }
}
