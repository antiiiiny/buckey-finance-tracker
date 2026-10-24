package com.buckey.financetracker.summary

import com.buckey.financetracker.data.category.Categories
import com.buckey.financetracker.data.db.entities.LinkKind
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.summary.Effect
import com.buckey.financetracker.data.summary.Ledger
import com.buckey.financetracker.data.summary.SpentMode
import com.buckey.financetracker.data.summary.MonthSummary
import com.buckey.financetracker.data.wallet.LinkSuggestions
import com.buckey.financetracker.data.wallet.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The money model, one test per row of the table in Ledger's KDoc (user's
 * design, 2026-10-25). Amounts are fake; paise throughout.
 */
class LedgerTest {

    private var nextId = 1L
    private val DEBIT = TransactionType.DEBIT
    private val CREDIT = TransactionType.CREDIT

    private fun row(
        type: TransactionType,
        amount: Double,
        category: String?,
        date: Long,
        link: String? = null,
        group: String? = null,
        person: String? = null,
        cash: Boolean = false,
        merchant: String = "UPI"
    ): TransactionEntity {
        val id = nextId++
        return TransactionEntity(
            id = id,
            transactionId = "t$id",
            amount = amount,
            date = date,
            merchant = merchant,
            type = type,
            senderAddress = if (cash) Wallet.CASH_SENDER else "AD-SIBSMS-S",
            rawBody = "",
            category = category,
            linkKind = link,
            linkGroup = group,
            counterparty = person
        )
    }

    private fun opening(amount: Double, date: Long) =
        row(CREDIT, amount, Categories.WALLET_ADJUSTMENT, date, cash = true, merchant = Wallet.OPENING_MERCHANT)

    private fun effectOf(vararg rows: TransactionEntity): Effect = Ledger.compute(rows.toList()).effect(rows.last())

    @Test
    fun `spending debit is spent and lowers net`() {
        assertEquals(Effect(spent = 45000, totalSpent = 45000, net = -45000), effectOf(row(DEBIT, 450.0, "Food", 1)))
    }

    @Test
    fun `transfer debit marked cash moves bank money into the wallet`() {
        val e = effectOf(opening(168.0, 1), row(DEBIT, 500.0, Categories.TRANSFER, 2, link = LinkKind.CASH))
        assertEquals(Effect(totalSpent = 50000, wallet = 50000), e)
    }

    @Test
    fun `undecided transfer debit only leaves the bank`() {
        val start = opening(168.0, 1)
        val transfer = row(DEBIT, 500.0, Categories.TRANSFER, 2)
        val result = Ledger.compute(listOf(start, transfer))
        assertEquals(Effect(totalSpent = 50000, net = -50000), result.effect(transfer))
        assertTrue(Ledger.needsTransferDecision(transfer, result.walletStart))
    }

    @Test
    fun `transfers before the wallet started never wait for a decision`() {
        val old = row(DEBIT, 500.0, Categories.TRANSFER, 1)
        val result = Ledger.compute(listOf(old, opening(168.0, 2)))
        assertFalse(Ledger.needsTransferDecision(old, result.walletStart))
    }

    @Test
    fun `atm withdrawal fills the wallet only once it has started`() {
        val before = row(DEBIT, 700.0, Categories.ATM_WITHDRAWAL, 1)
        val start = opening(168.0, 2)
        val after = row(DEBIT, 700.0, Categories.ATM_WITHDRAWAL, 3)
        val result = Ledger.compute(listOf(before, start, after))

        assertEquals(Effect(totalSpent = 70000, net = -70000), result.effect(before))
        assertEquals(Effect(totalSpent = 70000, wallet = 70000), result.effect(after))
        assertEquals(16800L + 70000L, result.walletPaise)
    }

    @Test
    fun `lend keeps net and opens a loan, repayment closes it without touching spent`() {
        val lend = row(DEBIT, 1000.0, Categories.LEND, 1, link = LinkKind.LEND, group = "t1", person = "Ravi")
        val back = row(CREDIT, 1000.0, Categories.LOAN_REPAID, 2, link = LinkKind.LEND_REPAID, group = "t1")
        val result = Ledger.compute(listOf(lend, back))

        assertEquals(Effect(totalSpent = 100000, lent = 100000), result.effect(lend))
        assertEquals(Effect(lent = -100000), result.effect(back))
        assertEquals(0L, result.lentPaise)
        assertEquals("Ravi", result.loans.single().counterparty)
    }

    @Test
    fun `savings debit lowers net but is not spent`() {
        assertEquals(Effect(totalSpent = 200000, net = -200000), effectOf(row(DEBIT, 2000.0, Categories.SAVINGS, 1)))
    }

    @Test
    fun `self transfer credit and income raise net only`() {
        assertEquals(Effect(net = 300000), effectOf(row(CREDIT, 3000.0, Categories.SELF_TRANSFER, 1)))
        assertEquals(Effect(net = 1000000), effectOf(row(CREDIT, 10000.0, Categories.INCOME, 1)))
    }

    @Test
    fun `transfer credit is a repayment, lowering spent and raising net`() {
        assertEquals(Effect(spent = -40000, net = 40000), effectOf(row(CREDIT, 400.0, Categories.TRANSFER, 1)))
    }

    @Test
    fun `holding in and paid out leaves spent and net alone`() {
        val held = row(CREDIT, 5000.0, Categories.HOLDING, 1, link = LinkKind.HOLD_IN, group = "t1", person = "A Friend")
        val out = row(DEBIT, 1200.0, Categories.HOLDING, 2, link = LinkKind.HOLD_OUT, group = "t1")
        val result = Ledger.compute(listOf(held, out))

        assertEquals(Effect(holding = 500000), result.effect(held))
        assertEquals(Effect(holding = -120000), result.effect(out))
        assertEquals(380000L, result.holdingPaise)
        assertEquals(listOf(500000L, 380000L), result.holdings.single().movements.map { it.remainingPaise })
    }

    @Test
    fun `clearing with money left over makes the leftover yours`() {
        val held = row(CREDIT, 5000.0, Categories.HOLDING, 1, link = LinkKind.HOLD_IN, group = "t1", person = "A")
        val clear = row(DEBIT, 4995.0, Categories.HOLDING, 2, link = LinkKind.HOLD_CLEAR, group = "t1")
        val result = Ledger.compute(listOf(held, clear))

        assertEquals(Effect(net = 500, holding = -500000), result.effect(clear))
        assertEquals(0L, result.holdingPaise)
        assertFalse(result.holdings.single().isOpen)
    }

    @Test
    fun `clearing with an overpayment counts the extra as spent`() {
        val held = row(CREDIT, 5000.0, Categories.HOLDING, 1, link = LinkKind.HOLD_IN, group = "t1", person = "A")
        val clear = row(DEBIT, 5008.0, Categories.HOLDING, 2, link = LinkKind.HOLD_CLEAR, group = "t1")
        assertEquals(Effect(spent = 800, totalSpent = 800, net = -800, holding = -500000), effectOf(held, clear))
    }

    @Test
    fun `clearing exactly leaves net and spent unchanged`() {
        val held = row(CREDIT, 5000.0, Categories.HOLDING, 1, link = LinkKind.HOLD_IN, group = "t1", person = "A")
        val clear = row(DEBIT, 5000.0, Categories.HOLDING, 2, link = LinkKind.HOLD_CLEAR, group = "t1")
        assertEquals(Effect(holding = -500000), effectOf(held, clear))
    }

    @Test
    fun `cash payment is spent from the wallet, recount moves net, opening doesn't`() {
        val start = opening(168.0, 1)
        val chai = row(DEBIT, 20.0, "Food", 2, cash = true, merchant = "Chai")
        val recount = row(DEBIT, 8.0, Categories.WALLET_ADJUSTMENT, 3, cash = true, merchant = Wallet.RECOUNT_MERCHANT)
        val result = Ledger.compute(listOf(start, chai, recount))

        assertEquals(Effect(wallet = 16800), result.effect(start))
        assertEquals(Effect(spent = 2000, net = -2000, wallet = -2000), result.effect(chai))
        assertEquals(Effect(net = -800, wallet = -800), result.effect(recount))
        assertEquals(14000L, result.walletPaise)
    }

    @Test
    fun `clear is suggested within ten rupees of a holding, not beyond`() {
        val held = row(CREDIT, 5000.0, Categories.HOLDING, 1, link = LinkKind.HOLD_IN, group = "t1", person = "A")
        val near = row(DEBIT, 5010.0, "Uncategorized", 2)
        val far = row(DEBIT, 4989.0, "Uncategorized", 2)
        val result = Ledger.compute(listOf(held, near, far))

        assertEquals(listOf("t1"), Ledger.clearSuggestions(result, near).map { it.id })
        assertTrue(Ledger.clearSuggestions(result, far).isEmpty())
    }

    @Test
    fun `loan repayment is suggested within the range, never assumed`() {
        val lend = row(DEBIT, 1000.0, Categories.LEND, 1, link = LinkKind.LEND, group = "t1", person = "Ravi")
        val back = row(CREDIT, 995.0, Categories.TRANSFER, 2)
        val result = Ledger.compute(listOf(lend, back))

        // Still a plain Transfer credit until the user links it.
        assertEquals(Effect(spent = -99500, net = 99500), result.effect(back))
        assertEquals(listOf("t1"), Ledger.repaySuggestions(result, back).map { it.id })
        assertTrue(Ledger.repaySuggestions(result, back, tolerancePaise = 100).isEmpty())
    }

    @Test
    fun `not this hides a suggestion for good`() {
        val held = row(CREDIT, 5000.0, Categories.HOLDING, 1, link = LinkKind.HOLD_IN, group = "t1", person = "A")
        val pay = row(DEBIT, 5000.0, "Uncategorized", 2)
        val rows = listOf(held, pay)
        val ledger = Ledger.compute(rows)

        assertEquals(1, LinkSuggestions.find(rows, ledger, Ledger.CLEAR_TOLERANCE_PAISE, emptySet()).size)
        assertTrue(LinkSuggestions.find(rows, ledger, Ledger.CLEAR_TOLERANCE_PAISE, setOf("t2|t1")).isEmpty())
    }

    @Test
    fun `month spent and net follow the ledger across the table`() {
        // The worked example the user chose from (2026-10-25), with the wallet on.
        val rows = listOf(
            opening(168.0, 1),
            row(DEBIT, 1200.0, "Food", 2),
            row(DEBIT, 500.0, Categories.TRANSFER, 3, link = LinkKind.CASH),
            row(DEBIT, 2000.0, Categories.SAVINGS, 4),
            row(CREDIT, 400.0, Categories.TRANSFER, 5),
            row(CREDIT, 3000.0, Categories.SELF_TRANSFER, 6),
            row(CREDIT, 10000.0, Categories.INCOME, 7),
        )
        val ledger = Ledger.compute(rows)
        assertEquals(80000L, MonthSummary.spentPaise(rows, SpentMode.AFTER_TRANSFERS, ledger))
        // −1200 (food) +0 (cash) −2000 (savings) +400 +3000 +10000
        assertEquals(1020000L, rows.sumOf { ledger.effect(it).net })
        assertEquals(16800L + 50000L, ledger.walletPaise)
    }
}
