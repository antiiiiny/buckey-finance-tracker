package com.buckey.financetracker.summary

import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.summary.BalanceCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home screen's running balance and its check against SIB SMS (2026-10-24). */
class BalanceCalculatorTest {

    private var nextId = 1L

    // Fake rows: bodies use the real SIB balance wording, amounts are made up.
    private fun debit(amount: Double, date: Long, balance: Double? = null) = row(TransactionType.DEBIT, amount, date,
        balance?.let { "UPI debit:Rs.$amount,A/c X0000, 27-07-26 20:11:44 RRN:000000000000. Bal:Rs.$it-South Indian Bank" })

    private fun credit(amount: Double, date: Long, balance: Double? = null) = row(TransactionType.CREDIT, amount, date,
        balance?.let { "UPI Credit:INR Rs.$amount in A/c X0000. Info:UPI/SIBL/000000000000/TEST on 27-07-26 20:43:01.Final balance is Rs.$it -South Indian Bank" })

    private fun row(type: TransactionType, amount: Double, date: Long, body: String?): TransactionEntity {
        val id = nextId++
        return TransactionEntity(
            id = id,
            transactionId = "t$id",
            amount = amount,
            date = date,
            type = type,
            senderAddress = if (body == null) "SIB-STATEMENT" else "AD-SIBSMS-S",
            rawBody = body ?: ""
        )
    }

    @Test
    fun `no SMS balance means no running balance`() {
        assertNull(BalanceCalculator.compute(emptyList()))
        assertNull(BalanceCalculator.compute(listOf(debit(100.0, 1), credit(50.0, 2))))
    }

    @Test
    fun `anchor plus later rows lands on the latest SMS balance`() {
        val rows = listOf(
            debit(100.0, 10, balance = 900.0),     // anchor
            credit(250.50, 20),                     // statement-only row, no SMS
            debit(50.25, 30, balance = 1100.25)
        )
        val result = BalanceCalculator.compute(rows)!!

        assertEquals(110025L, result.balancePaise)
        assertTrue(result.check.matches)
        assertEquals(30L, result.check.smsDate)
        assertEquals(10L, result.check.anchorDate)
    }

    @Test
    fun `rows before the anchor are ignored`() {
        val rows = listOf(
            debit(5000.0, 1),                       // before capture began
            credit(100.0, 5, balance = 1000.0),
            debit(40.0, 9, balance = 960.0)
        )
        assertEquals(96000L, BalanceCalculator.compute(rows)!!.balancePaise)
    }

    @Test
    fun `a missing transaction shows as the difference`() {
        val rows = listOf(
            debit(100.0, 10, balance = 900.0),
            // SIB charged 30 between these with no SMS and no statement row yet.
            debit(20.0, 30, balance = 850.0)
        )
        val check = BalanceCalculator.compute(rows)!!.check

        assertFalse(check.matches)
        assertEquals(88000L, check.estimatedPaise)
        assertEquals(85000L, check.smsBalancePaise)
        assertEquals(-3000L, check.differencePaise)
    }

    @Test
    fun `rows after the latest SMS move the balance but not the check`() {
        val rows = listOf(
            debit(100.0, 10, balance = 900.0),
            debit(200.0, 50)                        // statement row dated later
        )
        val result = BalanceCalculator.compute(rows)!!

        assertEquals(70000L, result.balancePaise)
        assertEquals(90000L, result.check.estimatedPaise)
        assertTrue(result.check.matches)
    }

    @Test
    fun `input order does not matter and ties go by id`() {
        val a = debit(100.0, 10, balance = 900.0)
        val b = credit(100.0, 10, balance = 1000.0)   // same timestamp, later id
        val result = BalanceCalculator.compute(listOf(b, a))!!

        assertEquals(100000L, result.balancePaise)
        assertTrue(result.check.matches)
    }

    @Test
    fun `paise sums are exact`() {
        val rows = mutableListOf(credit(0.0, 0, balance = 0.0))
        repeat(10) { rows += credit(0.10, (it + 1).toLong()) }
        rows += debit(0.0, 100, balance = 1.0)

        assertTrue(BalanceCalculator.compute(rows)!!.check.matches)
    }
}
