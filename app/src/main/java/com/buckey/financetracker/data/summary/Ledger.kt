package com.buckey.financetracker.data.summary

import com.buckey.financetracker.data.category.Categories
import com.buckey.financetracker.data.db.entities.LinkKind
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.wallet.Wallet
import kotlin.math.abs

/**
 * What one row does, in paise. [spent] is the default Spent rule, [totalSpent]
 * the "Total spent" setting, [net] the change in Net. [wallet], [lent] and
 * [holding] move those pots; Net = bank + wallet + lent − holding.
 */
data class Effect(
    val spent: Long = 0,
    val totalSpent: Long = 0,
    val net: Long = 0,
    val wallet: Long = 0,
    val lent: Long = 0,
    val holding: Long = 0
)

enum class GroupKind { LOAN, HOLDING }

/** One row's step in a loan or holding, and what was left after it. */
data class Movement(val transaction: TransactionEntity, val amountPaise: Long, val remainingPaise: Long)

/**
 * A loan (opened by a Lend debit) or a holding (opened by a Holding credit),
 * keyed by the opening row's `transaction_id`.
 */
data class LinkGroup(
    val id: String,
    val kind: GroupKind,
    val counterparty: String,
    val openedAt: Long,
    val openingPaise: Long,
    val remainingPaise: Long,
    val movements: List<Movement>
) {
    val isOpen: Boolean get() = remainingPaise > 0
}

/** Everything the home screen, Pending and the Holdings tab need from the rows. */
data class LedgerResult(
    val effects: Map<Long, Effect>,
    val walletPaise: Long,
    /** Date of the opening cash count; null until the wallet is set up. */
    val walletStart: Long?,
    val groups: List<LinkGroup>
) {
    val loans: List<LinkGroup> get() = groups.filter { it.kind == GroupKind.LOAN }
    val holdings: List<LinkGroup> get() = groups.filter { it.kind == GroupKind.HOLDING }
    val lentPaise: Long get() = loans.sumOf { it.remainingPaise }
    val holdingPaise: Long get() = holdings.sumOf { it.remainingPaise }

    fun effect(tx: TransactionEntity): Effect = effects[tx.id] ?: Effect()
}

/**
 * The money model (user's design, 2026-10-25; table in .github/design/wallet.md):
 *
 * | Row                               | Spent      | Net   | Pot           |
 * |-----------------------------------|------------|-------|---------------|
 * | debit, spending category          | +x         | −x    |               |
 * | debit Transfer confirmed as cash  |            | 0     | wallet +x     |
 * | debit ATM Withdrawal              |            | 0     | wallet +x     |
 * | debit Lend                        |            | 0     | lent +x       |
 * | debit Savings                     |            | −x    |               |
 * | debit paid out of a holding       |            | 0     | holding −x    |
 * | debit x clearing holding h        | max(x−h,0) | h−x   | holding −h    |
 * | credit Income / Self Transfer     |            | +x    |               |
 * | credit Transfer (repayment)       | −x         | +x    |               |
 * | credit paying back a loan         |            | 0     | lent −x       |
 * | credit Holding                    |            | 0     | holding +x    |
 * | cash payment                      | +x         | −x    | wallet −x     |
 * | wallet recount                    |            | ±x    | wallet ±x     |
 *
 * An undecided Transfer debit only leaves the bank until the user chooses cash
 * or lend. Rows are processed oldest first, since a loan or holding's state
 * depends on what came before. Pure, so every line above is unit tested.
 */
object Ledger {

    /** Default range for suggesting a holding or loan clear: ₹10 either way. */
    const val CLEAR_TOLERANCE_PAISE = 10_00L

    fun compute(rows: List<TransactionEntity>): LedgerResult {
        val ordered = rows.sortedWith(compareBy({ it.date }, { it.id }))
        val walletStart = ordered.firstOrNull(Wallet::isCash)?.date
        val groups = LinkedHashMap<String, GroupState>()
        val effects = HashMap<Long, Effect>(ordered.size)

        for (tx in ordered) {
            val x = toPaise(tx.amount)
            val group = tx.linkGroup?.let(groups::get)
            effects[tx.id] = when {
                Wallet.isCash(tx) -> cashEffect(tx, x)

                tx.linkKind == LinkKind.HOLD_IN && tx.type == TransactionType.CREDIT -> {
                    groups[tx.transactionId] = GroupState(tx, GroupKind.HOLDING, x)
                    Effect(holding = x)
                }

                tx.linkKind == LinkKind.LEND && tx.type == TransactionType.DEBIT -> {
                    groups[tx.transactionId] = GroupState(tx, GroupKind.LOAN, x)
                    Effect(totalSpent = x, lent = x)
                }

                tx.linkKind == LinkKind.HOLD_OUT && group?.kind == GroupKind.HOLDING -> {
                    val taken = group.take(tx, x)
                    val over = x - taken
                    Effect(spent = over, totalSpent = over, net = -over, holding = -taken)
                }

                tx.linkKind == LinkKind.HOLD_CLEAR && group?.kind == GroupKind.HOLDING -> {
                    val held = group.take(tx, group.remaining)
                    val over = maxOf(x - held, 0)
                    Effect(spent = over, totalSpent = over, net = held - x, holding = -held)
                }

                tx.linkKind == LinkKind.LEND_REPAID && group?.kind == GroupKind.LOAN -> {
                    val taken = group.take(tx, x)
                    Effect(net = x - taken, lent = -taken)
                }

                tx.linkKind == LinkKind.CASH && tx.type == TransactionType.DEBIT ->
                    Effect(totalSpent = x, wallet = x)

                else -> plainEffect(tx, x, walletStart)
            }
        }

        val wallet = effects.values.sumOf { it.wallet }
        return LedgerResult(effects, wallet, walletStart, groups.values.map(GroupState::toGroup))
    }

    /** Debits and credits with no link, by category. */
    private fun plainEffect(tx: TransactionEntity, x: Long, walletStart: Long?): Effect =
        when (tx.type) {
            TransactionType.DEBIT -> {
                val toWallet = if (tx.category == Categories.ATM_WITHDRAWAL &&
                    walletStart != null && tx.date >= walletStart
                ) x else 0
                val spending = tx.category !in Categories.NON_SPENDING_DEBITS
                Effect(
                    spent = if (spending) x else 0,
                    totalSpent = x,
                    net = -x + toWallet,
                    wallet = toWallet
                )
            }
            TransactionType.CREDIT -> Effect(
                spent = if (tx.category == Categories.TRANSFER) -x else 0,
                net = x
            )
        }

    /**
     * Cash rows. A cash payment is spending; a recount moves the wallet and Net;
     * the opening count only starts the wallet. "Total spent" counts bank debits
     * only, so cash taken out at an ATM isn't counted twice.
     */
    private fun cashEffect(tx: TransactionEntity, x: Long): Effect = when (tx.type) {
        TransactionType.CREDIT -> Effect(
            net = if (tx.merchant == Wallet.OPENING_MERCHANT) 0 else x,
            wallet = x
        )
        TransactionType.DEBIT -> Effect(
            spent = if (tx.category in Categories.NON_SPENDING_DEBITS) 0 else x,
            net = -x,
            wallet = -x
        )
    }

    /**
     * A Transfer debit since the wallet started that the user hasn't marked cash
     * or lend: it waits in Pending (user, 2026-10-25).
     */
    fun needsTransferDecision(tx: TransactionEntity, walletStart: Long?): Boolean =
        !Wallet.isCash(tx) && tx.type == TransactionType.DEBIT &&
            tx.category == Categories.TRANSFER && tx.linkKind == null &&
            walletStart != null && tx.date >= walletStart

    /**
     * Open holdings this debit could clear: remaining within [tolerancePaise] of
     * the amount (user, 2026-10-25; the range is a preference). Closest first.
     */
    fun clearSuggestions(
        result: LedgerResult,
        tx: TransactionEntity,
        tolerancePaise: Long = CLEAR_TOLERANCE_PAISE
    ): List<LinkGroup> =
        if (tx.type != TransactionType.DEBIT) emptyList() else near(result.holdings, tx, tolerancePaise)

    /**
     * Open loans this credit could pay back, by the same range. A Transfer credit
     * is never linked on its own: this only suggests (user, 2026-10-25).
     */
    fun repaySuggestions(
        result: LedgerResult,
        tx: TransactionEntity,
        tolerancePaise: Long = CLEAR_TOLERANCE_PAISE
    ): List<LinkGroup> =
        if (tx.type != TransactionType.CREDIT) emptyList() else near(result.loans, tx, tolerancePaise)

    private fun near(groups: List<LinkGroup>, tx: TransactionEntity, tolerancePaise: Long): List<LinkGroup> {
        if (tx.linkKind != null || Wallet.isCash(tx)) return emptyList()
        val x = toPaise(tx.amount)
        return groups
            .filter { it.isOpen && abs(it.remainingPaise - x) <= tolerancePaise && it.openedAt <= tx.date }
            .sortedBy { abs(it.remainingPaise - x) }
    }

    private class GroupState(val opener: TransactionEntity, val kind: GroupKind, val opening: Long) {
        var remaining = opening
        val movements = mutableListOf(Movement(opener, opening, opening))

        /** Takes up to [amount] from what is left; returns what was taken. */
        fun take(tx: TransactionEntity, amount: Long): Long {
            val taken = minOf(amount, remaining).coerceAtLeast(0)
            remaining -= taken
            movements += Movement(tx, toPaise(tx.amount), remaining)
            return taken
        }

        fun toGroup() = LinkGroup(
            id = opener.transactionId,
            kind = kind,
            counterparty = opener.counterparty?.takeIf { it.isNotBlank() } ?: opener.merchant,
            openedAt = opener.date,
            openingPaise = opening,
            remainingPaise = remaining,
            movements = movements.toList()
        )
    }
}
