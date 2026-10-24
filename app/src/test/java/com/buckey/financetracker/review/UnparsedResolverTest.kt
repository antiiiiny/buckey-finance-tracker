package com.buckey.financetracker.review

import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.db.entities.UnparsedMessageEntity
import com.buckey.financetracker.data.review.UnparsedResolver
import com.buckey.financetracker.data.review.UnparsedResolver.ManualEntry
import com.buckey.financetracker.data.sms.TransactionIdGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Stage 6 manual-resolution rules. Persisting needs Room and is
 * exercised on-device; everything that decides what gets written is pure.
 */
class UnparsedResolverTest {

    private val atmSms = UnparsedMessageEntity(
        id = 7,
        senderAddress = "AD-SIBSMS-S",
        rawBody = "Rs.2000.00 withdrawn at ATM from A/c X6825 on 27-07-26. Bal:Rs.755.99",
        receivedAt = 1_785_000_000_000L,
        failureReason = "No parser matched this SMS format"
    )

    private val validEntry = ManualEntry(
        amountText = "2,000.00",
        type = TransactionType.DEBIT,
        merchant = "ATM Withdrawal",
        accountLast4 = "6825"
    )

    // ==================== AMOUNT PARSING ====================

    @Test
    fun `parses amounts in the forms a bank SMS uses`() {
        assertEquals(500.0, UnparsedResolver.parseAmount("500")!!, 0.0)
        assertEquals(1234.5, UnparsedResolver.parseAmount("1,234.50")!!, 0.0)
        assertEquals(500.0, UnparsedResolver.parseAmount("Rs.500")!!, 0.0)
        assertEquals(500.0, UnparsedResolver.parseAmount("Rs. 500")!!, 0.0)
        assertEquals(20.0, UnparsedResolver.parseAmount("₹ 20")!!, 0.0)
        assertEquals(99.99, UnparsedResolver.parseAmount(" INR 99.99 ")!!, 0.0)
    }

    @Test
    fun `rejects amounts that cannot be a real transaction`() {
        assertNull(UnparsedResolver.parseAmount(""))
        assertNull(UnparsedResolver.parseAmount("0"))
        assertNull(UnparsedResolver.parseAmount("0.00"))
        assertNull(UnparsedResolver.parseAmount("-50"))
        assertNull(UnparsedResolver.parseAmount("abc"))
        assertNull(UnparsedResolver.parseAmount("12.345"))
        assertNull(UnparsedResolver.parseAmount("1.2.3"))
    }

    // ==================== VALIDATION ====================

    @Test
    fun `accepts a complete entry`() {
        assertTrue(UnparsedResolver.validate(validEntry).isEmpty())
    }

    @Test
    fun `optional fields may be blank`() {
        val entry = validEntry.copy(accountLast4 = "", rawRef = "", category = "")
        assertTrue(UnparsedResolver.validate(entry).isEmpty())
    }

    @Test
    fun `requires amount type and merchant`() {
        val errors = UnparsedResolver.validate(
            ManualEntry(amountText = "", type = null, merchant = "  ")
        )
        assertEquals(3, errors.size)
    }

    @Test
    fun `rejects a malformed account number`() {
        val errors = UnparsedResolver.validate(validEntry.copy(accountLast4 = "X682"))
        assertEquals(1, errors.size)
        assertTrue(errors.first().contains("4 digits"))
    }

    @Test
    fun `rejects a reference with punctuation`() {
        val errors = UnparsedResolver.validate(validEntry.copy(rawRef = "6208/9625"))
        assertEquals(1, errors.size)
    }

    // ==================== BUILDING THE TRANSACTION ====================

    @Test
    fun `builds a transaction from the entry and the source message`() {
        val tx = UnparsedResolver.buildTransaction(atmSms, validEntry, "Cash")

        assertEquals(2000.0, tx.amount, 0.0)
        assertEquals(TransactionType.DEBIT, tx.type)
        assertEquals("ATM Withdrawal", tx.merchant)
        assertEquals("6825", tx.accountLast4)
        assertEquals("Cash", tx.category)
        assertEquals(atmSms.receivedAt, tx.date)
        assertEquals(atmSms.senderAddress, tx.senderAddress)
        assertEquals(atmSms.rawBody, tx.rawBody)
        assertEquals(false, tx.syncedToSheets)
    }

    @Test
    fun `trims user input before saving`() {
        val entry = validEntry.copy(merchant = "  ATM  ", rawRef = " 620896250007 ")
        val tx = UnparsedResolver.buildTransaction(atmSms, entry, "Cash")
        assertEquals("ATM", tx.merchant)
        assertEquals("620896250007", tx.rawRef)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses to build from an invalid entry`() {
        UnparsedResolver.buildTransaction(atmSms, validEntry.copy(amountText = "0"), "Cash")
    }

    // ==================== DEDUP KEY ====================

    @Test
    fun `transaction id follows rule 10 over the source message`() {
        val expected = TransactionIdGenerator.generate(
            atmSms.senderAddress, atmSms.receivedAt, atmSms.rawBody
        )
        assertEquals(expected, UnparsedResolver.transactionIdFor(atmSms))
    }

    @Test
    fun `correcting a typo does not mint a new id`() {
        // Resolving the same SMS twice must collide on the unique index, or
        // Sheets would get two rows for one bank message.
        val first = UnparsedResolver.buildTransaction(atmSms, validEntry, "Cash")
        val second = UnparsedResolver.buildTransaction(
            atmSms, validEntry.copy(amountText = "200"), "Cash"
        )
        assertEquals(first.transactionId, second.transactionId)
    }

    @Test
    fun `different messages get different ids`() {
        val other = atmSms.copy(id = 8, receivedAt = atmSms.receivedAt + 1)
        assertNotEquals(
            UnparsedResolver.transactionIdFor(atmSms),
            UnparsedResolver.transactionIdFor(other)
        )
    }
}
