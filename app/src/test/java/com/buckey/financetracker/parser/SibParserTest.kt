package com.buckey.financetracker.parser

import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.parser.banks.SibParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SibParserTest {

    // ==================== DEBIT TESTS ====================

    @Test
    fun `debit - standard UPI payment`() {
        val sms = "UPI debit:Rs.1.00,A/c X6825, 27-07-26 20:11:44 RRN:620896250123. Bal:Rs.755.99 Block A/c?Call18004251809/SMS BLK<A/c>to 9840777222-South Indian Bank"
        val result = SibParser.parse("AD-SIBSMS-S", sms)

        assertNotNull(result)
        assertEquals(1.00, result!!.amount, 0.001)
        assertEquals("6825", result.accountLast4)
        assertEquals("620896250123", result.rawRef)
        assertEquals(TransactionType.DEBIT, result.type)
        assertEquals("UPI", result.merchant)
        assertEquals(
            ZonedDateTime.of(2026, 7, 27, 20, 11, 44, 0, ZoneId.of("Asia/Kolkata")).toInstant(),
            result.date
        )
    }

    @Test
    fun `debit - larger amount`() {
        val sms = "UPI debit:Rs.2500.50,A/c X1234, 27-07-26 14:30:00 RRN:123456789012. Bal:Rs.5000.00-South Indian Bank"
        val result = SibParser.parse("AX-SIBSMS-S", sms)

        assertNotNull(result)
        assertEquals(2500.50, result!!.amount, 0.001)
        assertEquals("1234", result.accountLast4)
        assertEquals("123456789012", result.rawRef)
        assertEquals(TransactionType.DEBIT, result.type)
    }

    @Test
    fun `debit - zero amount`() {
        val sms = "UPI debit:Rs.0.00,A/c X9876, 27-07-26 10:00:00 RRN:000000000000. Bal:Rs.1000.00-South Indian Bank"
        val result = SibParser.parse("AD-SIBSMS-S", sms)

        assertNotNull(result)
        assertEquals(0.00, result!!.amount, 0.001)
        assertEquals("9876", result.accountLast4)
        assertEquals(TransactionType.DEBIT, result.type)
    }

    @Test
    fun `debit - integer amount without decimal`() {
        val sms = "UPI debit:Rs.500,A/c X5555, 27-07-26 09:15:30 RRN:987654321098. Bal:Rs.2000.00-South Indian Bank"
        val result = SibParser.parse("AD-SIBSMS-S", sms)

        assertNotNull(result)
        assertEquals(500.0, result!!.amount, 0.001)
        assertEquals("5555", result.accountLast4)
    }

    // ==================== CREDIT TESTS ====================

    @Test
    fun `credit - standard UPI credit with merchant name`() {
        val sms = "UPI Credit:INR Rs.1.00 in A/c X6825. Info:UPI/SIBL/110306661234/LEO STEV on 27-07-26 20:43:01.Final balance is Rs.756.99 -South Indian Bank"
        val result = SibParser.parse("AX-SIBSMS-S", sms)

        assertNotNull(result)
        assertEquals(1.00, result!!.amount, 0.001)
        assertEquals("6825", result.accountLast4)
        assertEquals("110306661234", result.rawRef)
        assertEquals(TransactionType.CREDIT, result.type)
        assertEquals("LEO STEV", result.merchant)
        assertEquals(
            ZonedDateTime.of(2026, 7, 27, 20, 43, 1, 0, ZoneId.of("Asia/Kolkata")).toInstant(),
            result.date
        )
    }

    @Test
    fun `credit - salary credit`() {
        val sms = "UPI Credit:INR Rs.50000.00 in A/c X4321. Info:UPI/SIBL/998877665544/ACME CORP on 27-07-26 00:00:01.Final balance is Rs.75000.00 -South Indian Bank"
        val result = SibParser.parse("AD-SIBSMS-S", sms)

        assertNotNull(result)
        assertEquals(50000.00, result!!.amount, 0.001)
        assertEquals("4321", result.accountLast4)
        assertEquals("ACME CORP", result.merchant)
        assertEquals(TransactionType.CREDIT, result.type)
    }

    @Test
    fun `credit - small amount`() {
        val sms = "UPI Credit:INR Rs.0.50 in A/c X7777. Info:UPI/SIBL/556677889900/PAYTM UPI on 27-07-26 18:30:22.Final balance is Rs.100.50 -South Indian Bank"
        val result = SibParser.parse("AX-SIBSMS-S", sms)

        assertNotNull(result)
        assertEquals(0.50, result!!.amount, 0.001)
        assertEquals("7777", result.accountLast4)
        assertEquals("PAYTM UPI", result.merchant)
        assertEquals(TransactionType.CREDIT, result.type)
    }

    @Test
    fun `credit - payer at another bank`() {
        val sms = "UPI Credit:INR Rs.600.00 in A/c X6825. Info:UPI/HDFC/658298201234/AARON JACOB on 04-08-26 14:02:11.Final balance is Rs.1200.00 -South Indian Bank"
        val result = SibParser.parse("AX-SIBSMS-S", sms)

        assertNotNull(result)
        assertEquals(600.00, result!!.amount, 0.001)
        assertEquals("658298201234", result.rawRef)
        assertEquals("AARON JACOB", result.merchant)
        assertEquals(TransactionType.CREDIT, result.type)
    }

    @Test
    fun `credit - payer on a wallet with mixed-case name`() {
        val sms = "UPI Credit:INR Rs.52.00 in A/c X6825. Info:UPI/PPIW/622137251234/Aaron Jacob on 09-08-26 09:15:40.Final balance is Rs.900.00 -South Indian Bank"
        val result = SibParser.parse("AX-SIBSMS-S", sms)

        assertNotNull(result)
        assertEquals("622137251234", result!!.rawRef)
        assertEquals("Aaron Jacob", result.merchant)
    }

    @Test
    fun `failure - bank code that is not four letters`() {
        val sms = "UPI Credit:INR Rs.52.00 in A/c X6825. Info:UPI/HDFC1/622137251234/AARON JACOB on 09-08-26 09:15:40.Final balance is Rs.900.00 -South Indian Bank"

        assertNull(SibParser.parse("AX-SIBSMS-S", sms))
    }

    // ==================== FAILURE CASES ====================

    @Test
    fun `failure - non-SIB SMS`() {
        val sms = "Your ICICI Bank a/c XX1234 is credited with Rs.1000.00 on 27-07-26"
        val result = SibParser.parse("ICICIBANK", sms)

        assertNull(result)
    }

    @Test
    fun `failure - malformed debit SMS`() {
        val sms = "UPI debit:Rs.abc,A/c X6825, 27-07-26 20:11:44 RRN:12345. Bal:..."
        val result = SibParser.parse("AD-SIBSMS-S", sms)

        assertNull(result)
    }

    @Test
    fun `failure - completely unrelated message`() {
        val sms = "Your OTP for transaction is 123456. Do not share."
        val result = SibParser.parse("AD-SIBSMS-T", sms)

        assertNull(result)
    }

    @Test
    fun `failure - empty body`() {
        val result = SibParser.parse("AD-SIBSMS-S", "")
        assertNull(result)
    }

    @Test
    fun `failure - wrong date format`() {
        val sms = "UPI debit:Rs.100.00,A/c X6825, 2026-07-27 20:11:44 RRN:123456789012. Bal:..."
        val result = SibParser.parse("AD-SIBSMS-S", sms)

        assertNull(result)
    }

    // ==================== BALANCE TESTS ====================

    @Test
    fun `balance - debit Bal field`() {
        val sms = "UPI debit:Rs.1.00,A/c X6825, 27-07-26 20:11:44 RRN:620896250123. Bal:Rs.755.99 Block A/c?Call18004251809/SMS BLK<A/c>to 9840777222-South Indian Bank"
        assertEquals(755.99, SibParser.parseBalance(sms)!!, 0.001)
    }

    @Test
    fun `balance - credit Final balance field`() {
        val sms = "UPI Credit:INR Rs.1.00 in A/c X6825. Info:UPI/SIBL/110306661234/LEO STEV on 27-07-26 20:43:01.Final balance is Rs.756.99 -South Indian Bank"
        assertEquals(756.99, SibParser.parseBalance(sms)!!, 0.001)
    }

    @Test
    fun `balance - five digit balance`() {
        val sms = "UPI Credit:INR Rs.50000.00 in A/c X4321. Info:UPI/SIBL/998877665544/ACME CORP on 27-07-26 00:00:01.Final balance is Rs.75000.00 -South Indian Bank"
        assertEquals(75000.00, SibParser.parseBalance(sms)!!, 0.001)
    }

    @Test
    fun `balance - non-UPI SMS with Bal field`() {
        // A hand-resolved row keeps its source SMS, which may be another template.
        val sms = "Rs.2000.00 withdrawn at ATM from A/c X6825 on 27-07-26. Bal:Rs.755.99"
        assertEquals(755.99, SibParser.parseBalance(sms)!!, 0.001)
    }

    @Test
    fun `balance - none when the field is missing or unreadable`() {
        assertNull(SibParser.parseBalance(""))
        assertNull(SibParser.parseBalance("UPI debit:Rs.100.00,A/c X6825, 27-07-26 20:11:44 RRN:123456789012. Bal:..."))
        assertNull(SibParser.parseBalance("Your OTP for transaction is 123456. Do not share."))
    }
}