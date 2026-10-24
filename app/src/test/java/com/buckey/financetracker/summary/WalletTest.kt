package com.buckey.financetracker.summary

import com.buckey.financetracker.data.category.Categories
import com.buckey.financetracker.data.category.CategoryMatcher
import com.buckey.financetracker.data.db.entities.LinkKind
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.sheets.HoldingsSheetFormat
import com.buckey.financetracker.data.sheets.SheetRowFormat
import com.buckey.financetracker.data.summary.BalanceCalculator
import com.buckey.financetracker.data.summary.Ledger
import com.buckey.financetracker.data.wallet.CashBook
import com.buckey.financetracker.data.wallet.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Cash rows, the Savings rename, and the Holdings tab (2026-10-25). */
class WalletTest {

    @Test
    fun `cash payment is a cash debit in its category with a unique id`() {
        val a = CashBook.buildPayment(20.0, "Food", " Chai ", dateMillis = 5, nowMillis = 9)
        val b = CashBook.buildPayment(20.0, "Food", "Chai", dateMillis = 5, nowMillis = 9)

        assertEquals(TransactionType.DEBIT, a.type)
        assertEquals(Wallet.CASH_SENDER, a.senderAddress)
        assertEquals("Chai", a.merchant)
        assertEquals("Food", a.category)
        assertEquals(SheetRowFormat.SOURCE_CASH, SheetRowFormat.source(a))
        assertNotEquals(a.transactionId, b.transactionId)
    }

    @Test
    fun `first count opens the wallet, later counts record only the difference`() {
        val open = CashBook.buildCount(168.0, walletPaise = 0, walletStarted = false, dateMillis = 1)!!
        assertEquals(Wallet.OPENING_MERCHANT, open.merchant)
        assertEquals(TransactionType.CREDIT, open.type)
        assertEquals(168.0, open.amount, 0.001)

        val short = CashBook.buildCount(140.0, walletPaise = 16800, walletStarted = true, dateMillis = 2)!!
        assertEquals(TransactionType.DEBIT, short.type)
        assertEquals(28.0, short.amount, 0.001)
        assertEquals(Categories.WALLET_ADJUSTMENT, short.category)

        assertNull(CashBook.buildCount(168.0, walletPaise = 16800, walletStarted = true, dateMillis = 3))
    }

    @Test
    fun `cash rows never move the bank balance`() {
        val sms = TransactionEntity(
            id = 1, transactionId = "s", amount = 100.0, date = 1, type = TransactionType.DEBIT,
            senderAddress = "AD-SIBSMS-S", rawBody = "UPI debit:Rs.100.00 ... Bal:Rs.900.00-South Indian Bank"
        )
        val cash = CashBook.buildPayment(50.0, "Food", "Chai", dateMillis = 2, nowMillis = 2).copy(id = 2)
        assertEquals(90000L, BalanceCalculator.compute(listOf(sms, cash))!!.balancePaise)
    }

    @Test
    fun `self transfer is savings going out and self transfer coming in`() {
        assertEquals(Categories.SAVINGS, CategoryMatcher.forType(Categories.SELF_TRANSFER, TransactionType.DEBIT))
        assertEquals(Categories.SELF_TRANSFER, CategoryMatcher.forType(Categories.SAVINGS, TransactionType.CREDIT))
        assertEquals(Categories.HOLDING, CategoryMatcher.forType(Categories.HOLDING, TransactionType.CREDIT))
    }

    @Test
    fun `holdings tab lists every movement with what was left`() {
        fun row(id: Long, type: TransactionType, amount: Double, kind: String, date: Long) = TransactionEntity(
            id = id, transactionId = "t$id", amount = amount, date = date, type = type,
            senderAddress = "AD-SIBSMS-S", rawBody = "", category = Categories.HOLDING,
            linkKind = kind, linkGroup = "t1", counterparty = if (id == 1L) "A Friend" else null
        )
        val ledger = Ledger.compute(
            listOf(
                row(1, TransactionType.CREDIT, 5000.0, LinkKind.HOLD_IN, 0),
                row(2, TransactionType.DEBIT, 1200.0, LinkKind.HOLD_OUT, 86_400_000),
                row(3, TransactionType.DEBIT, 3795.0, LinkKind.HOLD_CLEAR, 2 * 86_400_000)
            )
        )
        val rows = HoldingsSheetFormat.rows(ledger, TimeZone.getTimeZone("UTC"))

        assertEquals(
            listOf(
                listOf("1970-01-01 00:00:00", "A Friend", "In", "5000.00", "5000.00", "t1"),
                listOf("1970-01-02 00:00:00", "A Friend", "Out", "1200.00", "3800.00", "t2"),
                listOf("1970-01-03 00:00:00", "A Friend", "Out (cleared)", "3795.00", "0.00", "t3")
            ),
            rows
        )
        assertTrue(LinkKind.HOLD_IN in LinkKind.HOLDING)
    }
}
