package com.buckey.financetracker.ui

import com.buckey.financetracker.ui.format.DateLabels
import com.buckey.financetracker.ui.format.MoneyFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** How amounts and dates read on the home and pending screens. */
class FormatTest {

    @Test
    fun `rupees use Indian grouping and drop zero paise`() {
        assertEquals("₹0", MoneyFormat.rupees(0))
        assertEquals("₹450", MoneyFormat.rupees(45000))
        assertEquals("₹1,299", MoneyFormat.rupees(129900))
        assertEquals("₹18,240", MoneyFormat.rupees(1824000))
        assertEquals("₹1,23,456.50", MoneyFormat.rupees(12345650))
        assertEquals("₹1,00,00,000", MoneyFormat.rupees(1000000000))
        assertEquals("₹0.05", MoneyFormat.rupees(5))
    }

    @Test
    fun `negative and signed amounts`() {
        assertEquals("−₹1,120", MoneyFormat.rupees(-112000))
        assertEquals("+₹2,000", MoneyFormat.signed(200000))
        assertEquals("−₹830", MoneyFormat.signed(-83000))
        assertEquals("₹0", MoneyFormat.signed(0))
    }

    @Test
    fun `spoken amounts for TalkBack`() {
        assertEquals("2,000 rupees", MoneyFormat.spoken(200000))
        assertEquals("minus 1,120 rupees 50 paise", MoneyFormat.spoken(-112050))
    }

    @Test
    fun `day labels`() {
        val today = LocalDate.of(2026, 10, 24)
        assertEquals("Today", DateLabels.day(today, today))
        assertEquals("Yesterday", DateLabels.day(today.minusDays(1), today))
        assertEquals("21 Oct", DateLabels.day(LocalDate.of(2026, 10, 21), today))
        assertEquals("31 Dec 2025", DateLabels.day(LocalDate.of(2025, 12, 31), today))
    }

    @Test
    fun `month labels add the year only outside this year`() {
        val today = LocalDate.of(2026, 10, 24)
        assertEquals("October", DateLabels.month(YearMonth.of(2026, 10), today))
        assertEquals("December 2025", DateLabels.month(YearMonth.of(2025, 12), today))
    }
}
