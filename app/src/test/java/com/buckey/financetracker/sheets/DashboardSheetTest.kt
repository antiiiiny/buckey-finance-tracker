package com.buckey.financetracker.sheets

import com.buckey.financetracker.data.category.Categories
import com.buckey.financetracker.data.sheets.DashboardSheetFormat
import com.buckey.financetracker.data.sheets.DashboardSheetRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The Dashboard tab: formulas over Transactions, created once by the app
 * (user, 2026-10-25). Checks the formulas follow the app's money rules.
 */
class DashboardSheetTest {

    private val cells = DashboardSheetFormat.layoutCells(LocalDate.of(2026, 10, 25)).toMap()

    @Test
    fun `dropdowns start on this month and the default spent rule`() {
        assertEquals("2026-10-01", cells["B3"])
        assertEquals(DashboardSheetFormat.SPENT_DEFAULT, cells["D3"])
    }

    @Test
    fun `spent leaves out every money-moving category, or counts every bank debit`() {
        val spent = cells.getValue("A6")
        Categories.NON_SPENDING_DEBITS.forEach { assertTrue(it, spent.contains("\"<>$it\"")) }
        assertTrue(spent.contains("-SUMIFS(Transactions!\$B\$2:\$B,Transactions!\$E\$2:\$E,\"CREDIT\""))
        // "Total spent": bank debits only, so cash spent from an ATM isn't counted twice.
        assertTrue(spent.contains("IF(\$D\$3=\"${DashboardSheetFormat.SPENT_TOTAL}\""))
        assertTrue(spent.contains("Transactions!\$I\$2:\$I,\"<>cash\""))
    }

    @Test
    fun `net treats atm as cash, lending as still yours, and skips the opening count`() {
        val net = cells.getValue("C6")
        assertTrue(net.contains("\"<>Lend\""))
        assertTrue(net.contains("\"<>Loan Repaid\""))
        assertTrue(net.contains("\"<>Opening cash\""))
        assertTrue(net.contains("\"ATM Withdrawal\",Transactions!\$C\$2:\$C,\">=\"&\$X\$2"))
    }

    @Test
    fun `category pie follows the spent dropdown`() {
        val query = cells.getValue("Y1")
        assertTrue(query.startsWith("=IFERROR(IF(\$D\$3=\"Total spent\""))
        assertTrue(query.contains("and Col1 <> 'Savings'"))
        assertTrue(query.contains("and Col5 <> 'cash'"))
    }

    @Test
    fun `requests stay on the dashboard tab with a pie and two column charts`() {
        val requests = DashboardSheetRequests.build(4242)
        // The client's toString prints either JSON ("sheetId": 1) or maps (sheetId=1).
        val ids = Regex("sheetId\"?\\s*[:=]\\s*(\\d+)").findAll(requests.joinToString()).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("4242"), ids)
        val charts = requests.mapNotNull { it.addChart?.chart?.spec }
        assertEquals(1, charts.count { it.pieChart != null })
        assertEquals(2, charts.count { it.basicChart?.chartType == "COLUMN" })
    }
}
